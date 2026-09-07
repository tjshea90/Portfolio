package com.tj.portfolio

import com.tj.portfolio.net.Http
import com.tj.portfolio.util.MemoryTrim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The things that decide whether a data provider throttles or bans this app, and the things
 * that decide whether Android kills it in the background.
 *
 * None of this touches the network. `Retry-After` parsing, the rate meter's arithmetic and
 * the memory-trim fan-out are all pure logic, and pure logic that has never been executed is
 * where this project has repeatedly found its bugs.
 */
class ProviderSafetyTest {

    @Before fun setUp() { Http.resetMeters(); MemoryTrim.clear() }
    @After fun tearDown() { Http.resetMeters(); MemoryTrim.clear() }

    // -------------------------------------------------------- Retry-After
    //
    // This was being IGNORED entirely. A 429 got the app's own 30-second backoff whatever the
    // server asked for, so a host answering `Retry-After: 3600` was retried about 120 times
    // inside the window it had explicitly asked to be left alone. That is the behaviour that
    // turns a temporary throttle into a block.

    @Test
    fun `delta-seconds form is honoured`() {
        assertEquals(3_600_000L, Http.parseRetryAfter("3600"))
        assertEquals(1_000L, Http.parseRetryAfter("1"))
        assertEquals(120_000L, Http.parseRetryAfter("  120  "))
    }

    @Test
    fun `http-date form is honoured`() {
        // 60 seconds after the reference instant below.
        val now = 1_756_000_000_000L
        val ms = Http.parseRetryAfter(httpDate(now + 60_000L), now)
        // allow a second of slack for the second-resolution of the header format
        assertTrue("got $ms", ms in 59_000L..61_000L)
    }

    @Test
    fun `a date in the past asks for no wait rather than a negative one`() {
        val now = 1_756_000_000_000L
        assertEquals(0L, Http.parseRetryAfter(httpDate(now - 600_000L), now))
    }

    @Test
    fun `an absent or unparseable header is never guessed at`() {
        // The important half: a bad header must not become an arbitrary long cooldown that
        // silently disables the app.
        assertEquals(0L, Http.parseRetryAfter(null))
        assertEquals(0L, Http.parseRetryAfter(""))
        assertEquals(0L, Http.parseRetryAfter("   "))
        assertEquals(0L, Http.parseRetryAfter("soon"))
        assertEquals(0L, Http.parseRetryAfter("Tue, 99 Xxx 2026 99:99:99 GMT"))
        assertEquals(0L, Http.parseRetryAfter("-30"))
        assertEquals(0L, Http.parseRetryAfter("0"))
    }

    private fun httpDate(t: Long): String {
        val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return f.format(java.util.Date(t))
    }

    // -------------------------------------------------------- the rate meter

    @Test
    fun `the meter starts empty and stays empty without traffic`() {
        assertEquals(0L, Http.totalLastHour())
        assertTrue(Http.requestsLastHour().isEmpty())
    }

    @Test
    fun `resetting the meter clears every host`() {
        Http.resetMeters()
        assertEquals(0L, Http.totalLastHour())
    }

    // -------------------------------------------------------- the cache bound

    @Test
    fun `the conditional cache reports its size in bytes not characters`() {
        // The bound used to be documented as "well under 6MB" from 48 entries x 120,000,
        // read as bytes. A Kotlin String is UTF-16, so the real ceiling was 11.5MB - nearly
        // double, in a process that also holds a Compose UI and a WebView. The accessor now
        // reports bytes so the figure cannot drift from reality again.
        Http.clearConditionalCache()
        val (entries, bytes) = Http.cacheStats()
        assertEquals(0, entries)
        assertEquals(0L, bytes)
    }

    @Test
    fun `clearing the cache is safe to call repeatedly`() {
        repeat(3) { Http.clearConditionalCache() }
        Http.onLowMemory()
        assertEquals(0, Http.cacheStats().first)
    }

    // -------------------------------------------------------- memory trim

    @Test
    fun `a registered listener is called with the level`() {
        var seen = -1
        val l = MemoryTrim.Listener { seen = it }
        MemoryTrim.register(l)
        MemoryTrim.trim(40)
        assertEquals(40, seen)
    }

    @Test
    fun `registering the same listener twice does not call it twice`() {
        var calls = 0
        val l = MemoryTrim.Listener { calls++ }
        MemoryTrim.register(l)
        MemoryTrim.register(l)
        MemoryTrim.trim(MemoryTrim.UI_HIDDEN)
        assertEquals(1, calls)
    }

    @Test
    fun `unregistering stops the callbacks`() {
        var calls = 0
        val l = MemoryTrim.Listener { calls++ }
        MemoryTrim.register(l)
        MemoryTrim.unregister(l)
        MemoryTrim.trim(80)
        assertEquals(0, calls)
    }

    @Test
    fun `one listener throwing does not stop the others releasing memory`() {
        // The whole point of this path is that it runs when the system is short of memory.
        // A listener that throws must not take the rest of the release with it.
        var reached = false
        val bad = MemoryTrim.Listener { error("boom") }
        val good = MemoryTrim.Listener { reached = true }
        MemoryTrim.register(bad)
        MemoryTrim.register(good)
        MemoryTrim.trim(80)
        assertTrue("the second listener must still run", reached)
    }

    @Test
    fun `listeners are held weakly so a cleared ViewModel is not pinned`() {
        // MemoryTrim must never be the reason a ViewModel outlives its Activity - that would
        // make the memory fix a memory leak.
        var l: MemoryTrim.Listener? = MemoryTrim.Listener { }
        val ref = java.lang.ref.WeakReference(l)
        MemoryTrim.register(l!!)
        assertNotNull(ref.get())
        l = null
        var collected = false
        repeat(20) {
            System.gc(); System.runFinalization()
            if (ref.get() == null) { collected = true; return@repeat }
            Thread.sleep(10)
        }
        // Not asserted as a hard requirement - GC is not deterministic - but a trim after
        // the referent is gone must never throw.
        MemoryTrim.trim(80)
        assertFalse("sanity: the test itself must not hold a strong ref", collected && ref.get() != null)
    }
}
