package com.tj.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.ui.RetryClock
import com.tj.portfolio.util.Connectivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ROUND 59: THE FAILURE BACKOFF, AND SKIPPING WORK WHEN THERE IS NO NETWORK.
 *
 * Both fixes exist because of request STORMS that no test could have caught, because nothing
 * was measuring how often a fetch that fails gets retried:
 *
 *  - `loadChart` stamped a "last fetched" field and never read it, so a chart that cannot be
 *    had was re-requested on every quote tick - about 240 requests an hour, for one symbol;
 *  - `refreshSparklines` un-marked every failed symbol so it would retry promptly, which
 *    fixed TJ's missing charts and simultaneously turned a permanently dead ticker into a
 *    fetch every fifteen seconds.
 *
 * `RetryClock` is private to the ViewModel, so its RULE is restated here. What is asserted is
 * the property that matters and that neither original had: **a fetch that keeps failing must
 * be asked for less and less often, and one success must put it back to normal.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryBackoffTest {

    /**
     * THE REAL CLASS, not a copy of it (Round 63).
     *
     * This used to be a hand-written restatement of the ViewModel's private `RetryClock`, on
     * the reasoning that a private class cannot be reached from a test. The consequence was
     * that changing the real backoff could not fail this file - the test asserted a
     * duplicate. `RetryClock` is now top-level and `internal`, so what is exercised below is
     * the code that actually runs.
     */
    private fun clock() = RetryClock()

    private val t0 = 1_757_000_000_000L

    /**
     * The property that makes this safe to add to an existing guard: something that has never
     * failed is never held back, so nothing that already works changes behaviour.
     */
    @Test
    fun `a key that has never failed is never blocked`() {
        val c = clock()
        assertFalse(c.blocked("AAPL", t0))
        c.success("AAPL", t0)
        assertFalse(c.blocked("AAPL", t0 + 1))
        assertFalse(c.blocked("AAPL", t0 + 3_600_000L))
    }

    /**
     * THE SPARKLINE STORM. Ticks are 15s apart; one failure must not mean a retry on the very
     * next one. Thirty seconds still recovers a passing blip quickly - which is what TJ's
     * missing charts needed - without asking four times a minute forever.
     */
    @Test
    fun `one failure blocks the next tick but not the next minute`() {
        val c = clock()
        c.failure("AAPL", t0)
        assertTrue("retried on the very next 15s tick", c.blocked("AAPL", t0 + 15_000L))
        assertTrue(c.blocked("AAPL", t0 + 29_000L))
        assertFalse("still blocked after its 30s window", c.blocked("AAPL", t0 + 31_000L))
    }

    @Test
    fun `the window widens with each consecutive failure`() {
        val c = clock()
        val expected = listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
        expected.forEachIndexed { i, window ->
            val now = t0 + i * 1_000_000L
            c.failure("X", now)
            assertEquals("after ${i + 1} failures", window, c.backoffMs(i + 1))
            assertTrue(c.blocked("X", now + window - 1))
            assertFalse(c.blocked("X", now + window + 1))
        }
    }

    /** Capped, so a symbol that is dead forever still gets a look every five minutes. */
    @Test
    fun `the backoff is capped at five minutes however many times it fails`() {
        val c = clock()
        repeat(50) { c.failure("DEAD", t0) }
        assertEquals(300_000L, c.backoffMs(50))
        assertFalse(c.blocked("DEAD", t0 + 300_001L))
    }

    /**
     * The window is about CONSECUTIVE failures. One good answer means the next failure starts
     * again at thirty seconds rather than inheriting five minutes of history.
     */
    @Test
    fun `a success resets the count`() {
        val c = clock()
        repeat(4) { c.failure("X", t0) }
        assertTrue(c.blocked("X", t0 + 60_000L))
        c.success("X", t0 + 300_001L)
        assertFalse(c.blocked("X", t0 + 300_002L))
        c.failure("X", t0 + 400_000L)
        assertTrue(c.blocked("X", t0 + 410_000L))
        assertFalse("should be back to the 30s window", c.blocked("X", t0 + 431_000L))
    }

    /** A deliberate pull-to-refresh is the user saying "try it now, whatever you think". */
    @Test
    fun `clearing unblocks everything`() {
        val c = clock()
        repeat(5) { c.failure("A", t0); c.failure("B", t0) }
        assertTrue(c.blocked("A", t0 + 1))
        c.clear()
        assertFalse(c.blocked("A", t0 + 1))
        assertFalse(c.blocked("B", t0 + 1))
    }

    /** Keys are independent - one dead symbol must not hold back its neighbours. */
    @Test
    fun `one failing key does not block another`() {
        val c = clock()
        c.failure("DEAD", t0)
        assertTrue(c.blocked("DEAD", t0 + 1_000L))
        assertFalse(c.blocked("ALIVE", t0 + 1_000L))
    }

    // ------------------------------------------------------------- connectivity

    /**
     * THE WHOLE TRUTH TABLE, ROW BY ROW.
     *
     * DELIBERATELY OPTIMISTIC: the only thing this suppresses is the case Android is certain
     * about. Everything else - no manager, capabilities that will not read, a captive portal,
     * a VPN still coming up - has to return true, because an app that refuses to refresh on a
     * connection that works is a far worse bug than one wasted pass.
     *
     * The first version of this file promised exactly that in a comment while returning false
     * whenever it could not read the manager. Writing the decision out as a table is what made
     * that visible, so the table is what gets asserted.
     */
    @Test
    fun `offline is the only answer the check is willing to be certain about`() {
        // no manager at all -> assume online
        assertTrue(Connectivity.decide(false, false, false, false))
        assertTrue(Connectivity.decide(false, true, true, true))
        // manager, but no active network -> the one definitive offline
        assertFalse(Connectivity.decide(true, false, false, false))
        assertFalse(Connectivity.decide(true, false, true, true))
        // a network that will not describe itself -> try anyway
        assertTrue(Connectivity.decide(true, true, false, false))
        // a network that describes itself -> believe it
        assertTrue(Connectivity.decide(true, true, true, true))
        assertFalse(Connectivity.decide(true, true, true, false))
    }

    /**
     * VALIDATION IS NOT REQUIRED, and that is a decision rather than an oversight: a captive
     * portal and a still-negotiating VPN both report INTERNET without being validated, and on
     * those the app should try.
     */
    @Test
    fun `an unvalidated network with internet still counts as online`() {
        assertTrue(Connectivity.decide(true, true, true, hasInternet = true))
    }

    @Test
    fun `reading the real framework never throws and is stable`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val first = Connectivity.isOnline(ctx)
        repeat(5) { assertEquals(first, Connectivity.isOnline(ctx)) }
    }
}
