package com.tj.portfolio

import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.net.DayTradingTechnicals
import com.tj.portfolio.net.MarketClock
import com.tj.portfolio.ui.effectiveTechnicals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A DROPPED REQUEST MUST NOT LOOK LIKE A NEW TRADING SESSION.
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * `DayTechnicals.sessionDay` comes back BLANK in two unrelated situations, and
 * `effectiveTechnicals` used to treat them identically - every intraday field fell to 0:
 *
 *   1. the intraday request FAILED (both Yahoo hosts throttled or erroring), which on a
 *      30-symbol sweep every 30 seconds is routine; and
 *   2. the request SUCCEEDED carrying no bars dated today - a weekend, a market holiday, or
 *      any tick before 04:00 ET.
 *
 * Only the second is information. The first tells you nothing at all, and zeroing on it was
 * not cosmetic: with `vwap` and `sessionLow` at 0, `ResearchScore.planInternal` can no longer
 * take the VWAP-reclaim or pullback branch, `rangeUsed` reads 0, the room cap disappears, and
 * `vol` swaps from the real 5-minute ATR to `atr14 * 0.10`. The stop is sized at 1.5-2.5x
 * `vol`, so the entry, stop and target on screen changed by a large factor on that tick and
 * changed back on the next - three money levels flickering between two different trade plans
 * because one request was dropped.
 *
 * The fix must not reintroduce the 09:31 bug it sits next to: a finished session has spent
 * essentially all of its average daily range, so carrying yesterday's high and low into this
 * morning makes every affected row read "already extended - do not chase" on a stock that has
 * barely traded. Hence the last two tests.
 */
class DayTradingSessionCarryTest {

    /** A fixed instant, so `dayKey` is deterministic without hard-coding its format. */
    private val now = 1_757_600_000_000L
    private val today = MarketClock.dayKey(now)
    private val yesterday = MarketClock.dayKey(now - 86_400_000L)

    private fun row(day: String) = ResearchRow(
        symbol = "GME", price = 22.5, score = 88,
        entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5, setup = "Breakout",
        atr = 2.5, vwap = 21.9, atrIntraday = 0.35,
        openingRangeHigh = 22.2, openingRangeLow = 21.6,
        sessionHigh = 22.8, sessionLow = 21.4, premarketHigh = 22.0,
        sessionDay = day
    )

    /** What `fetch` returns when the intraday half never came back. */
    private fun intradayRequestFailed() = DayTradingTechnicals.DayTechnicals(
        atr14 = 2.5, adr = 1.1, prevHigh = 23.0,
        sessionDay = "", intradayFetched = false
    )

    /** What it returns when the request DID answer, with nothing dated today. */
    private fun noSessionToday() = DayTradingTechnicals.DayTechnicals(
        atr14 = 2.5, adr = 1.1, prevHigh = 23.0,
        sessionDay = "", intradayFetched = true
    )

    @Test fun `a failed intraday request keeps this session's readings`() {
        val out = effectiveTechnicals(row(today), intradayRequestFailed(), now)

        assertEquals("VWAP was wiped by a dropped request", 21.9, out.vwap, 1e-9)
        assertEquals("the intraday ATR the stop is sized from was wiped",
            0.35, out.atrIntraday, 1e-9)
        assertEquals(22.2, out.openingRangeHigh, 1e-9)
        assertEquals(21.6, out.openingRangeLow, 1e-9)
        assertEquals(22.8, out.sessionHigh, 1e-9)
        assertEquals(21.4, out.sessionLow, 1e-9)
        assertEquals(22.0, out.premarketHigh, 1e-9)
    }

    /**
     * And it must say which session it now describes, or the carry-forward only survives one
     * tick: the merged row would go back out with a blank `sessionDay` and the NEXT failure
     * would find nothing to match against.
     */
    @Test fun `a carried-forward reading reports the session it belongs to`() {
        val out = effectiveTechnicals(row(today), intradayRequestFailed(), now)
        assertEquals(today, out.sessionDay)
    }

    @Test fun `a request that answered with no session today does not carry anything forward`() {
        val out = effectiveTechnicals(row(today), noSessionToday(), now)

        assertEquals("a weekend or holiday reading resurrected a stale VWAP",
            0.0, out.vwap, 1e-9)
        assertEquals(0.0, out.sessionHigh, 1e-9)
        assertEquals(0.0, out.sessionLow, 1e-9)
    }

    /** THE 09:31 BUG, which the fix must not reopen. */
    @Test fun `a failed request never carries yesterday's session into today`() {
        val out = effectiveTechnicals(row(yesterday), intradayRequestFailed(), now)

        assertEquals("yesterday's VWAP was carried into a new session", 0.0, out.vwap, 1e-9)
        assertEquals("yesterday's session high was carried into a new session",
            0.0, out.sessionHigh, 1e-9)
        assertEquals(0.0, out.sessionLow, 1e-9)
    }

    /** The daily-bar fields are about COMPLETED days, so they survive either way. */
    @Test fun `daily fields still come from this tick regardless`() {
        val out = effectiveTechnicals(row(yesterday), intradayRequestFailed(), now)
        assertEquals(2.5, out.atr14, 1e-9)
        assertEquals(1.1, out.adr, 1e-9)
        assertEquals(23.0, out.prevHigh, 1e-9)
    }

    /** A real reading from this tick always beats a carried one. */
    @Test fun `a fresh value wins over the row's previous one`() {
        val fresh = DayTradingTechnicals.DayTechnicals(
            atr14 = 2.5, vwap = 22.4, sessionDay = today, intradayFetched = true
        )
        assertEquals(22.4, effectiveTechnicals(row(today), fresh, now).vwap, 1e-9)
    }
}
