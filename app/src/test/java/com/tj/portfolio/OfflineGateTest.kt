package com.tj.portfolio

import com.tj.portfolio.net.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full test 2026-09-24, T-1: the unit suite never reaches the internet.
 *
 * Every Robolectric test that builds the ViewModel used to send real quote, chart and news
 * requests from whatever machine ran the suite, so results depended on the market and on that
 * machine's network. `app/build.gradle.kts` now sets `portfolio.test.offline` for every test
 * JVM and `Http` refuses non-loopback hosts while it is set. If this file fails, that wiring
 * has come undone and the suite is live again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineGateTest {

    @Before fun clean() { Http.clearCooldowns() }

    @Test fun `the test JVM carries the offline property`() {
        assertEquals("true", System.getProperty("portfolio.test.offline"))
    }

    @Test fun `an internet GET is refused locally, uncounted and without a cooldown`() = runBlocking {
        val url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=NVDA"
        val before = Http.requestsLastHour().sumOf { it.second }
        val started = System.nanoTime()
        val r = Http.get(url)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(-1, r.code)
        assertFalse(r.ok)
        assertEquals("offline (unit test)", r.body)
        assertTrue("a refused request must not wait on a socket ($tookMs ms)", tookMs < 2_000)
        assertEquals("a request never sent is not traffic", before, Http.requestsLastHour().sumOf { it.second })
        assertEquals("the refusal must not arm a cooldown that leaks into other tests",
            0L, Http.cooldownRemaining(url))
    }

    @Test fun `an internet POST is refused the same way`() = runBlocking {
        val r = Http.postJson("https://api.anthropic.com/v1/messages", "{}")
        assertEquals(-1, r.code)
        assertEquals("offline (unit test)", r.body)
    }

    @Test fun `loopback stays open so the real failure paths can still be driven`() = runBlocking {
        val r = Http.get("http://127.0.0.1:1/nothing", timeoutMs = 800)
        assertEquals(-1, r.code)
        assertTrue("loopback must reach the socket, not the gate", r.body != "offline (unit test)")
    }
}
