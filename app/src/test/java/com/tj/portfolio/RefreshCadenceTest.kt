package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.ui.PortfolioViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * THE SLOW REFRESH CADENCES (Round 66).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * The feed and SEC-filing cadences were `var sinceFeedRefresh` and `var sinceFilings`, both
 * declared INSIDE the coroutine `startAuto()` launches. `setForeground(true)` calls
 * `startAuto()`, which cancels and relaunches that coroutine - so both counters restarted at
 * zero every time TJ came back to the app. They measured uninterrupted foreground seconds,
 * not elapsed time.
 *
 * The feed's three minutes usually survived that. Filings' half hour did not: half an hour of
 * one continuous foreground session is not how a phone is used, so the Insider tab never
 * refreshed itself at all. It kept whatever the cache restored, aged rows out of its own
 * 30-day window on each launch, and only ever gained a filing when the tab happened to be
 * opened completely empty. Free data that was never fetched - the exact opposite of what TJ
 * asked for ("refresh data from the Internet liberally where it actually is beneficial").
 *
 * `passDue` is that decision as a pure function, so the OR rule and the wall-clock arithmetic
 * can be checked without waiting half an hour for a coroutine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshCadenceTest {

    private lateinit var app: Application
    private val minute = 60_000L
    private val feedSecs = 180          // three minutes, the market-open interval

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    private fun vm() = PortfolioViewModel(app).also { settle() }

    @Test fun `nothing is due when both marks are fresh`() {
        val now = 1_800_000_000_000L
        val d = vm().passDue(now, feedAt = now - minute, filingsAt = now - minute, feedIntervalSecs = feedSecs)
        assertFalse("the feed was pulled a minute ago", d.feed)
        assertFalse("filings were pulled a minute ago", d.filings)
        assertFalse(d.anything)
    }

    @Test fun `the feed is due once its interval has passed`() {
        val now = 1_800_000_000_000L
        val d = vm().passDue(now, feedAt = now - 4 * minute, filingsAt = now, feedIntervalSecs = feedSecs)
        assertTrue(d.feed)
        assertFalse(d.filings)
    }

    /**
     * THE ONE THAT MATTERS. A user who checks the Feed tab keeps `_feedAt` fresh out of band,
     * so filings must be able to come due on their own - otherwise the fix is undone by the
     * app being used.
     */
    @Test fun `filings come due even when the feed was just refreshed`() {
        val now = 1_800_000_000_000L
        val d = vm().passDue(
            now,
            feedAt = now - 10_000L,          // the Feed tab was opened ten seconds ago
            filingsAt = now - 45 * minute,   // filings last pulled 45 minutes ago
            feedIntervalSecs = feedSecs
        )
        assertFalse("the feed is not due", d.feed)
        assertTrue("filings must still get their turn", d.filings)
        assertTrue("so a pass must run", d.anything)
    }

    @Test fun `a fresh install is due for both`() {
        val d = vm().passDue(1_800_000_000_000L, feedAt = 0L, filingsAt = 0L, feedIntervalSecs = feedSecs)
        assertTrue(d.feed); assertTrue(d.filings)
    }

    @Test fun `auto-refresh switched off never makes the feed due`() {
        val now = 1_800_000_000_000L
        val d = vm().passDue(now, feedAt = 0L, filingsAt = now, feedIntervalSecs = 0)
        assertFalse("interval 0 means the user turned it off", d.feed)
    }

    /**
     * The mark has to outlive the process, or every launch re-pulls EDGAR for symbols whose
     * filings are two business days behind by law and cannot have changed.
     */
    @Test fun `the filings mark is restored from disk at launch`() {
        val at = 1_799_999_000_000L
        Db(app).use { it.set(Keys.FILINGS_AT, at.toString()) }
        val v = vm()
        val d = v.passDue(at + minute, feedAt = at, filingsAt = readFilingsMark(), feedIntervalSecs = feedSecs)
        assertFalse("a mark a minute old must not be due again", d.filings)
        assertEquals(at, readFilingsMark())
    }

    private fun readFilingsMark(): Long =
        Db(app).use { it.get(Keys.FILINGS_AT).toLongOrNull() ?: 0L }
}
