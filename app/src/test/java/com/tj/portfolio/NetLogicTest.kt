package com.tj.portfolio

import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.data.Trending
import com.tj.portfolio.net.Http
import com.tj.portfolio.net.MarketClock
import com.tj.portfolio.net.News
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * The networking layer's decisions, executed.
 *
 * None of this needs a device or a live server: the back-off state machine, the market
 * clock, headline de-duplication and the r/wallstreetbets merge are all pure logic that has
 * only ever been reasoned about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetLogicTest {

    @Before fun clean() { Http.clearCooldowns() }

    // ------------------------------------------------------------- back-off

    /**
     * A host that cannot be connected to is left alone after repeated failures.
     *
     * What this really guards is the OFFLINE phone: the back-off only ever watched for HTTP
     * 429/503/403, so a failure with no status code did not arm it and the app went on
     * retrying every host on its normal schedule - twenty doomed connections every fifteen
     * seconds for a twenty-symbol portfolio, indefinitely. (The case that led here was
     * api.tradestie.com's expired certificate, but that source is polled only every fifteen
     * minutes, so the saving there alone is small.)
     *
     * 127.0.0.1:1 refuses instantly, which is the same shape of failure without the wait.
     */
    @Test fun `three failures to connect earn a cooldown`() = runBlocking {
        val dead = "http://127.0.0.1:1/nothing"
        assertEquals("a host starts free", 0L, Http.cooldownRemaining(dead))

        val first = Http.get(dead, timeoutMs = 800)
        assertEquals("a refused connection reports -1", -1, first.code)
        assertEquals("one failure must NOT back off - a blip is not an outage",
            0L, Http.cooldownRemaining(dead))

        Http.get(dead, timeoutMs = 800)
        assertEquals("two failures must not back off either", 0L, Http.cooldownRemaining(dead))

        Http.get(dead, timeoutMs = 800)
        assertTrue("three consecutive failures should arm the cooldown",
            Http.cooldownRemaining(dead) > 0L)

        // and while it is armed the app must not even open a socket
        val skipped = Http.get(dead, timeoutMs = 800)
        assertTrue("a cooled-down host was contacted anyway", skipped.throttledLocally)
        assertFalse(skipped.ok)
    }

    @Test fun `a user refresh clears the cooldown so pulling down always tries`() = runBlocking {
        val dead = "http://127.0.0.1:1/nothing"
        repeat(3) { Http.get(dead, timeoutMs = 800) }
        assertTrue(Http.cooldownRemaining(dead) > 0L)
        Http.clearCooldowns()
        assertEquals("a deliberate pull-to-refresh must not be blocked by an invisible timer",
            0L, Http.cooldownRemaining(dead))
    }

    /**
     * A whole refresh failing at once must not jump to the maximum backoff.
     *
     * `refresh()` fires every held symbol in parallel, so one offline tick produces twenty
     * transport failures within a second. An escalation counted per REQUEST would take a
     * momentary blip straight to the five-minute ceiling; escalation is counted per cooldown
     * instead, so a single burst only ever earns the first step.
     */
    @Test fun `one burst of failures earns only the first backoff step`() = runBlocking {
        val dead = "http://127.0.0.3:1/x"
        repeat(20) { Http.get(dead, timeoutMs = 800) }
        val remaining = Http.cooldownRemaining(dead)
        assertTrue("no cooldown was armed at all", remaining > 0L)
        assertTrue("a single burst escalated to ${remaining}ms - it should stay at the first " +
            "step of 15s", remaining <= 15_000L)
    }

    /**
     * ROUND 66 AUDIT (H4). The rate-limit ladder escalated per 429 RESPONSE, not per cooldown.
     *
     * Up to four requests are in flight to one host at a time - `Research.build` runs four
     * screener calls under a Semaphore(4), and the feed pulls several RSS sources together.
     * When the host answered 429 they all landed within milliseconds, `strikes` jumped 0 to 4,
     * and the FIRST rate-limit event armed a four-minute cooldown instead of the documented
     * thirty seconds. The next lapse sent the same four out together and hit the ten-minute
     * ceiling, so the middle rungs of the ladder were unreachable in practice and every
     * Yahoo-dependent screen froze for minutes over what may have been a one-second throttle.
     *
     * `noteUnreachable` had always done it per cooldown and its own comment calls the
     * distinction load-bearing; this is the same rule, finally applied to both.
     */
    @Test fun `a burst of rate-limit responses earns only the first backoff step`() {
        val now = 1_800_000_000_000L
        var strikes = 0
        var until = 0L
        // Four concurrent requests all come back 429 within the same millisecond.
        repeat(4) {
            val (s2, u2) = Http.nextRateLimit(now, until, strikes, 0L)
            strikes = s2; until = u2
        }
        assertEquals("four responses, one cooldown", 1, strikes)
        assertEquals("the first step is 30 seconds", now + 30_000L, until)
    }

    @Test fun `the ladder still escalates once per cooldown`() {
        var strikes = 0
        var until = 0L
        var now = 1_800_000_000_000L
        val steps = ArrayList<Long>()
        repeat(4) {
            val (s2, u2) = Http.nextRateLimit(now, until, strikes, 0L)
            strikes = s2; until = u2
            steps.add(u2 - now)
            now = u2 + 1          // wait the cooldown out, then get 429 again
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L), steps)
    }

    @Test fun `a server Retry-After wins when it is longer, and never shortens a cooldown`() {
        val now = 1_800_000_000_000L
        val (_, longer) = Http.nextRateLimit(now, 0L, 0, 90_000L)
        assertEquals("the server asked for 90s and our step was 30s", now + 90_000L, longer)
        // A late 429 arriving inside an existing cooldown must not pull the deadline back.
        val (_, kept) = Http.nextRateLimit(now, now + 300_000L, 3, 1_000L)
        assertEquals(now + 300_000L, kept)
    }

    /**
     * ROUND-9-AUDIT FINDING: `postJson` used to skip the cooldown/gate machinery entirely -
     * a host already in cooldown from `get()` traffic was hit anyway, and a connection
     * failure here armed no cooldown at all. Same shape of proof as `get`'s own test above:
     * a host refusing every connection must arm a cooldown, and once armed, must not even be
     * dialled again.
     */
    @Test fun `postJson arms and honours the same cooldown get does`() = runBlocking {
        val dead = "http://127.0.0.4:1/nothing"
        repeat(3) { Http.postJson(dead, "{}", timeoutMs = 800) }
        assertTrue("three refused connections should arm postJson's cooldown too",
            Http.cooldownRemaining(dead) > 0L)

        val skipped = Http.postJson(dead, "{}", timeoutMs = 800)
        assertTrue("a cooled-down host was posted to anyway", skipped.throttledLocally)
    }

    @Test fun `postJson's cooldown is shared with get, not a separate one`() = runBlocking {
        val dead = "http://127.0.0.5:1/nothing"
        repeat(3) { Http.get(dead, timeoutMs = 800) }
        assertTrue(Http.cooldownRemaining(dead) > 0L)

        val posted = Http.postJson(dead, "{}", timeoutMs = 800)
        assertTrue("postJson must respect a cooldown get() already armed for this host",
            posted.throttledLocally)
    }

    @Test fun `the cooldown is per host, not global`() = runBlocking {
        val a = "http://127.0.0.1:1/a"
        val b = "http://127.0.0.2:1/b"
        repeat(3) { Http.get(a, timeoutMs = 800) }
        assertTrue(Http.cooldownRemaining(a) > 0L)
        assertEquals("backing off one host must not silence another",
            0L, Http.cooldownRemaining(b))
    }

    // --------------------------------------------------------- market clock

    private fun et(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        c.set(y, mo - 1, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test fun `dayKey agrees for two moments on the same ET calendar day`() {
        val morning = et(2026, 9, 11, 6, 0)
        val night = et(2026, 9, 11, 23, 30)
        assertEquals(MarketClock.dayKey(morning), MarketClock.dayKey(night))
        assertEquals("20260911", MarketClock.dayKey(morning))
    }

    @Test fun `dayKey changes at ET midnight, not UTC midnight`() {
        // 23:30 ET on the 11th and 00:30 ET on the 12th are only an hour apart, but they are
        // on either side of the boundary this key is supposed to track. A UTC-based
        // implementation would get this wrong for hours around US midnight, since ET trails
        // UTC by four or five hours depending on daylight saving.
        val before = et(2026, 9, 11, 23, 30)
        val after = et(2026, 9, 12, 0, 30)
        assertNotEquals(MarketClock.dayKey(before), MarketClock.dayKey(after))
        assertEquals("20260911", MarketClock.dayKey(before))
        assertEquals("20260912", MarketClock.dayKey(after))
    }

    @Test fun `market phases sit on the right side of every boundary`() {
        // Thursday 3 September 2026 is a normal weekday
        val cases = listOf(
            Triple(3, 59 to 0, MarketClock.Phase.CLOSED),     // 03:59 overnight
            Triple(4, 0 to 0, MarketClock.Phase.EXTENDED),    // 04:00 pre-market opens
            Triple(9, 29 to 0, MarketClock.Phase.EXTENDED),   // 09:29 still pre
            Triple(9, 30 to 0, MarketClock.Phase.OPEN),       // 09:30 the bell
            Triple(15, 59 to 0, MarketClock.Phase.OPEN),      // 15:59 still open
            Triple(16, 0 to 0, MarketClock.Phase.EXTENDED),   // 16:00 close -> after hours
            Triple(19, 59 to 0, MarketClock.Phase.EXTENDED),  // 19:59 last after-hours minute
            Triple(20, 0 to 0, MarketClock.Phase.CLOSED)      // 20:00 done for the day
        )
        for ((hour, mm, want) in cases) {
            val t = et(2026, 9, 3, hour, mm.first)
            assertEquals("%02d:%02d ET".format(hour, mm.first), want, MarketClock.phase(t))
        }
    }

    // ---- A RATE-LIMITED BATCH IS NOT A BROKEN ONE (full-tests audit 2026-09-22, N-M2).
    @Test fun `a throttle or rate-limit holds the batch off, a real error does not`() {
        for (c in listOf(com.tj.portfolio.net.HttpResult.CODE_COOLDOWN, 429, 503, 403)) {
            assertTrue("$c", com.tj.portfolio.net.MarketData.heldOff(c))
        }
        for (c in listOf(200, 404, 500, 502, -1)) {
            assertFalse("$c", com.tj.portfolio.net.MarketData.heldOff(c))
        }
    }

    // ---- A SPARKLINE PULLED AFTER THE CLOSE IS FINAL UNTIL THE NEXT OPEN (audit N-M3).
    @Test fun `an after-close sparkline is not re-downloaded before the next opening bell`() {
        val fri1630 = et(2026, 9, 11, 16, 30)
        assertTrue(com.tj.portfolio.ui.sparkIsFinal(fri1630, et(2026, 9, 11, 19, 0)))
        assertTrue("all weekend", com.tj.portfolio.ui.sparkIsFinal(fri1630, et(2026, 9, 14, 8, 0)))
        assertFalse("once Monday opens", com.tj.portfolio.ui.sparkIsFinal(fri1630, et(2026, 9, 14, 9, 31)))
        assertFalse("a mid-session pull is not final",
            com.tj.portfolio.ui.sparkIsFinal(et(2026, 9, 11, 15, 58), et(2026, 9, 11, 17, 0)))
        assertFalse("never fetched", com.tj.portfolio.ui.sparkIsFinal(0L, et(2026, 9, 11, 17, 0)))
        // Labor Day 2026 is 7 September: Friday's line holds through the long weekend.
        assertTrue(com.tj.portfolio.ui.sparkIsFinal(et(2026, 9, 4, 17, 0), et(2026, 9, 7, 11, 0)))
        assertEquals(et(2026, 9, 8, 9, 30), MarketClock.nextOpenAfter(et(2026, 9, 4, 17, 0)))
    }

    // ---- THE DAY TRADING LOOP SLOWS DOWN AFTER THE CLOSE (full-tests audit, N-M4).
    @Test fun `the live day-trading loop is fast pre-market and slow after the close`() {
        fun delayAt(ms: Long) = com.tj.portfolio.ui.dayTradingLiveDelay(
            MarketClock.phase(ms), MarketClock.sessionElapsedFraction(ms))
        val fast = delayAt(et(2026, 9, 11, 10, 0))
        assertEquals("pre-market keeps the live pace", fast, delayAt(et(2026, 9, 11, 8, 0)))
        assertTrue("after the close is slower", delayAt(et(2026, 9, 11, 17, 0)) > fast)
        assertEquals(delayAt(et(2026, 9, 11, 23, 0)), delayAt(et(2026, 9, 11, 17, 0)))
    }

    // ---- THE EXCHANGE CALENDAR (full-tests audit 2026-09-22, D-M4/D-L9): computed from
    // NYSE's own rules, checked against dates the exchange actually published.

    @Test fun `NYSE full-day holidays are closed at what would be market hours`() {
        val holidays = listOf(
            Triple(2026, 1, 1), Triple(2026, 1, 19), Triple(2026, 2, 16),
            Triple(2026, 4, 3),                    // Good Friday 2026 (Easter 5 April)
            Triple(2025, 4, 18),                   // Good Friday 2025
            Triple(2026, 5, 25), Triple(2026, 6, 19),
            Triple(2026, 7, 3),                    // 4 July on a Saturday -> Friday
            Triple(2026, 9, 7), Triple(2026, 11, 26), Triple(2026, 12, 25),
            Triple(2021, 12, 24),                  // Christmas on a Saturday -> Friday
            Triple(2023, 1, 2),                    // New Year's on a Sunday -> Monday
            Triple(2027, 6, 18)                    // Juneteenth on a Saturday -> Friday
        )
        for ((y, m, d) in holidays) {
            assertEquals("$y-$m-$d", MarketClock.Phase.CLOSED, MarketClock.phase(et(y, m, d, 11, 0)))
        }
    }

    @Test fun `ordinary sessions next to the holidays stay open`() {
        val open = listOf(
            Triple(2021, 12, 31),                  // New Year's on a Saturday is NOT moved back
            Triple(2026, 4, 2), Triple(2026, 11, 25), Triple(2026, 7, 2),
            Triple(2026, 9, 8), Triple(2026, 5, 18)
        )
        for ((y, m, d) in open) {
            assertEquals("$y-$m-$d", MarketClock.Phase.OPEN, MarketClock.phase(et(y, m, d, 11, 0)))
        }
    }

    @Test fun `the half days close at 1pm and the clock knows it`() {
        // Day after Thanksgiving 2026, Christmas Eve 2026 (a Thursday), 3 July 2025 (Thursday).
        for ((y, m, d) in listOf(Triple(2026, 11, 27), Triple(2026, 12, 24), Triple(2025, 7, 3))) {
            assertEquals(MarketClock.Phase.OPEN, MarketClock.phase(et(y, m, d, 12, 59)))
            assertEquals(MarketClock.Phase.EXTENDED, MarketClock.phase(et(y, m, d, 13, 0)))
            assertEquals(MarketClock.Phase.EXTENDED, MarketClock.phase(et(y, m, d, 16, 59)))
            assertEquals(MarketClock.Phase.CLOSED, MarketClock.phase(et(y, m, d, 17, 0)))
            assertEquals(30, MarketClock.minutesLeftInSession(et(y, m, d, 12, 30)))
            assertEquals(0.5, MarketClock.sessionElapsedFraction(et(y, m, d, 11, 15)), 1e-9)
        }
        // 3 July 2022 was a Sunday; the Friday before it is a full day.
        assertEquals(390, MarketClock.minutesLeftInSession(et(2022, 7, 1, 9, 30)))
    }

    @Test fun `the weekend is closed even at what would be market hours`() {
        assertEquals(MarketClock.Phase.CLOSED, MarketClock.phase(et(2026, 9, 5, 11, 0)))  // Sat
        assertEquals(MarketClock.Phase.CLOSED, MarketClock.phase(et(2026, 9, 6, 11, 0)))  // Sun
    }

    @Test fun `the poll interval honours the user during the session and floors it outside`() {
        val open = et(2026, 9, 3, 11, 0)
        val ext = et(2026, 9, 3, 18, 0)
        val shut = et(2026, 9, 5, 11, 0)
        assertEquals("the user's choice is exact while the market is open",
            15, MarketClock.quoteIntervalSecs(15, open))
        assertEquals("pre/post is floored at a minute", 60, MarketClock.quoteIntervalSecs(15, ext))
        assertEquals("closed is floored at fifteen minutes", 900, MarketClock.quoteIntervalSecs(15, shut))
        assertEquals("a slower user setting is never sped up", 1800,
            MarketClock.quoteIntervalSecs(1800, open))
        assertEquals("off stays off in every phase", 0, MarketClock.quoteIntervalSecs(0, open))
        assertEquals(0, MarketClock.quoteIntervalSecs(0, shut))
    }

    @Test fun `the feed rides a slower clock than prices in every phase`() {
        for (t in listOf(et(2026, 9, 3, 11, 0), et(2026, 9, 3, 18, 0), et(2026, 9, 5, 11, 0))) {
            assertTrue("the feed should never poll faster than quotes",
                MarketClock.feedIntervalSecs(t) >= MarketClock.quoteIntervalSecs(15, t))
        }
    }

    // ------------------------------------------------------ headline identity

    private fun item(title: String, src: String = "X", pub: Long = 1L) =
        NewsItem(symbol = "NVDA", title = title, url = "http://x/$title", source = src, published = pub)

    @Test fun `the same wire story from three sources collapses to one`() {
        val a = item("Nvidia beats on earnings as data centre revenue climbs 40%", "Yahoo")
        val b = item("Nvidia beats on earnings as data centre revenue climbs 40% - Reuters", "Google News")
        val c = item("Nvidia beats on earnings as data centre revenue climbs 40% | MarketWatch", "MW")
        assertEquals(News.dedupeKey(a), News.dedupeKey(b))
        assertEquals(News.dedupeKey(a), News.dedupeKey(c))
        assertEquals(1, listOf(a, b, c).distinctBy { News.dedupeKey(it) }.size)
    }

    @Test fun `two genuinely different headlines stay separate`() {
        val a = item("Nvidia beats on earnings as data centre revenue climbs")
        val b = item("Nvidia slips as China export rules tighten again")
        assertNotEquals(News.dedupeKey(a), News.dedupeKey(b))
    }

    @Test fun `a publisher suffix is only trimmed when there is a headline left`() {
        // "AAPL - Reuters" is nearly all publisher; trimming it would leave a key so short
        // it would collide with anything else short
        val short = item("AAPL - Reuters")
        val other = item("MSFT - Reuters")
        assertNotEquals("two short headlines collapsed into one",
            News.dedupeKey(short), News.dedupeKey(other))
    }

    @Test fun `punctuation and smart quotes do not split one story into two`() {
        val a = item("Nvidia's Q3: a beat, and a warning about supply")
        val b = item("Nvidia’s Q3 — a beat, and a warning about supply")
        assertEquals(News.dedupeKey(a), News.dedupeKey(b))
    }

    // ------------------------------------------------------------- trending

    @Test fun `wsb momentum compares two ranks from the same source`() {
        // Tradestie ranks by its own ordering, ApeWisdom by its own. Comparing one against
        // the other invents movement: a ticker at #3 on Tradestie and #15 on both ApeWisdom
        // lists must read as "no change", not "up 12".
        val merged = Trending(
            symbol = "GME", rank = 3, apeRank = 15, rank24hAgo = 15,
            mentions = 100, mentions24hAgo = 80, source = "r/wallstreetbets"
        )
        assertEquals("momentum was computed across two different rankings", 0, merged.rankDelta)
        assertEquals(20, merged.mentionDelta)

        val climbing = merged.copy(apeRank = 3, rank24hAgo = 15)
        assertEquals(12, climbing.rankDelta)
    }

    @Test fun `a ticker with no history reports no movement rather than a fake jump`() {
        val fresh = Trending(symbol = "NEW", rank = 5, apeRank = 5, rank24hAgo = 0, mentions = 40)
        assertEquals(0, fresh.rankDelta)
        assertEquals(0, fresh.mentionDelta)
    }

    @Test fun `activity falls back to comments when mentions are absent`() {
        assertEquals(42, Trending(symbol = "A", mentions = 42, comments = 7).activity)
        assertEquals(7, Trending(symbol = "A", mentions = 0, comments = 7).activity)
    }
}
