package com.tj.portfolio

import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.net.DayTradingTechnicals
import com.tj.portfolio.net.MarketClock
import com.tj.portfolio.net.Research
import com.tj.portfolio.net.ResearchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * ROUND 73 - THE SOUNDNESS PASS ON THE DAY-TRADING LOGIC.
 *
 * Tj: *"do more research on the day trading section for the best advice possible. make the day
 * trading logic very sound."* [DayTradingTest] already pins down the Round 67-72 engine; this
 * file covers only what that round changed, and each test names the defect it guards:
 *
 *  - the section had NO liquidity or relative-volume floor at all, while citing a study whose
 *    every profitable variant runs behind exactly those floors;
 *  - the plan was identical at 09:35 and at 15:55, on a trade that must be closed the same day;
 *  - the profit target could never exceed 3x the risk, truncating the right tail this kind of
 *    strategy earns from;
 *  - the plan gave a per-share risk and never a share count, though the app knows the portfolio;
 *  - and the 09:30-10:00 opening range was the one window the cited study found WORST.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingSoundnessTest {

    // ------------------------------------------------------------------ helpers

    private fun tech(
        atrIntraday: Double = 0.0,
        atr: Double = 0.0,
        vwap: Double = 0.0,
        orHigh: Double = 0.0,
        orLow: Double = 0.0,
        or5High: Double = 0.0,
        or5Low: Double = 0.0,
        openingBarBullish: Boolean = true,
        adr: Double = 0.0,
        prevHigh: Double = 0.0,
        prevLow: Double = 0.0,
        prevClose: Double = 0.0,
        premarketHigh: Double = 0.0,
        sessionHigh: Double = 0.0,
        sessionLow: Double = 0.0,
        live: Boolean = true
    ) = DayTradingTechnicals.DayTechnicals(
        atr14 = atr,
        vwap = vwap,
        openingRangeHigh = orHigh,
        openingRangeLow = orLow,
        or5High = or5High,
        or5Low = or5Low,
        openingBarBullish = openingBarBullish,
        atrIntraday = atrIntraday,
        adr = adr,
        prevHigh = prevHigh,
        prevLow = prevLow,
        prevClose = prevClose,
        premarketHigh = premarketHigh,
        sessionHigh = sessionHigh,
        sessionLow = sessionLow,
        sessionLive = live
    )

    /** A liquid, genuinely-in-play row, so each gate test varies exactly one thing. */
    private fun row(
        price: Double = 20.0,
        avgVolume3M: Double = 5e6,
        volume: Double = 1e7,
        marketCap: Double = 2e9
    ) = ScreenRow(
        symbol = "TEST",
        price = price,
        avgVolume3M = avgVolume3M,
        volume = volume,
        marketCap = marketCap
    )

    /** Epoch millis for a wall-clock moment in New York - the only timezone this app trades in. */
    private fun et(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    // FRIDAY 11 September 2026 - an ordinary weekday, asserted below rather than assumed.
    private fun friday(hour: Int, minute: Int) = et(2026, 9, 11, hour, minute)

    // ================================================================ the clock

    @Test fun `the test day really is a weekday, so every clock case below means what it says`() {
        assertEquals(MarketClock.Phase.OPEN, MarketClock.phase(friday(10, 0)))
    }

    @Test fun `minutesLeftInSession counts down to the 4pm close`() {
        assertEquals(390, MarketClock.minutesLeftInSession(friday(9, 30)))
        assertEquals(360, MarketClock.minutesLeftInSession(friday(10, 0)))
        assertEquals(30, MarketClock.minutesLeftInSession(friday(15, 30)))
        assertEquals(5, MarketClock.minutesLeftInSession(friday(15, 55)))
    }

    @Test fun `outside the regular session there are zero minutes left, never negative`() {
        assertEquals("pre-market", 0, MarketClock.minutesLeftInSession(friday(8, 0)))
        assertEquals("after hours", 0, MarketClock.minutesLeftInSession(friday(17, 0)))
        assertEquals("overnight", 0, MarketClock.minutesLeftInSession(friday(2, 0)))
        assertEquals("the weekend", 0, MarketClock.minutesLeftInSession(et(2026, 9, 12, 11, 0)))
    }

    @Test fun `the midday lull is 1130 to 1330 ET, and only while the market is open`() {
        assertFalse(MarketClock.inMiddayLull(friday(11, 29)))
        assertTrue(MarketClock.inMiddayLull(friday(11, 30)))
        assertTrue(MarketClock.inMiddayLull(friday(12, 45)))
        assertFalse("13:30 is the end of it, not inside it", MarketClock.inMiddayLull(friday(13, 30)))
        assertFalse("closed", MarketClock.inMiddayLull(friday(12, 0) - 86_400_000L * 5))
    }

    // ==================================================== the tradability gates
    //
    // THE DEFECT: `buildDayTrading` filtered on price >= $2 and market cap alone. A $2.10 stock
    // trading 80,000 shares a day passed both and could top the list, while the study this
    // feature cites screens at a million shares a day before it trades anything.

    @Test fun `a liquid stock genuinely in play passes`() {
        assertTrue(Research.dayTradable(row()))
    }

    @Test fun `a thinly traded stock is rejected however exciting it looks`() {
        assertFalse(
            "80k shares a day cannot be day traded - the spread is the whole planned risk",
            Research.dayTradable(row(avgVolume3M = 8e4, volume = 4e5))
        )
    }

    @Test fun `a stock trading below its own normal volume is not in play by definition`() {
        // Liquid enough, but quiet today: the study measures this bucket at slightly NEGATIVE
        // average outcome, so it is excluded rather than merely scored zero for it.
        assertFalse(Research.dayTradable(row(avgVolume3M = 5e6, volume = 2e6)))
        assertTrue("exactly its normal volume is the floor, and passes",
            Research.dayTradable(row(avgVolume3M = 5e6, volume = 5e6)))
    }

    @Test fun `unknown average volume excludes, which is deliberately not the house rule`() {
        // Everywhere else an absent figure passes its filter. Here the absent figure IS the
        // question - see `Research.dayTradable`'s header.
        assertFalse(Research.dayTradable(row(avgVolume3M = 0.0)))
    }

    @Test fun `Tj's two-dollar floor is kept, NOT raised to the study's five`() {
        // His own words: "include only stocks that are at least 2 dollars a share when
        // searched." A cheap stock that is genuinely liquid still belongs in the list.
        assertTrue(Research.dayTradable(row(price = 2.5, avgVolume3M = 5e6, volume = 1e7)))
        assertFalse(Research.dayTradable(row(price = 1.99, avgVolume3M = 5e6, volume = 1e7)))
    }

    // ========================================================== the 5-minute opening range

    @Test fun `the opening bar is the 0930 to 0935 one, and its direction is read`() {
        fun bar(hour: Int, min: Int, open: Double, close: Double) = DayTradingTechnicals.Bar(
            t = et(2026, 9, 11, hour, min) / 1000L,
            open = open, high = maxOf(open, close) + 0.1, low = minOf(open, close) - 0.1,
            close = close, volume = 1000.0
        )
        val bars = listOf(
            bar(9, 30, open = 10.0, close = 10.4),
            bar(9, 35, open = 10.4, close = 10.2),
            bar(9, 40, open = 10.2, close = 10.6)
        )
        val opening = DayTradingTechnicals.openingBar(bars)
        assertNotNull(opening)
        assertEquals(10.0, opening!!.open, 0.001)
        assertEquals(10.4, opening.close, 0.001)
        assertTrue("closed up, so a long is allowed by the published rule", opening.close > opening.open)
    }

    @Test fun `with no 0930 bar there is no opening bar rather than a guessed one`() {
        val afterOnly = listOf(
            DayTradingTechnicals.Bar(
                t = et(2026, 9, 11, 10, 0) / 1000L,
                open = 10.0, high = 10.5, low = 9.9, close = 10.3, volume = 1000.0
            )
        )
        assertNull(DayTradingTechnicals.openingBar(afterOnly))
    }

    @Test fun `the 5-minute opening high becomes the breakout trigger when it is nearest`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                or5High = 100.2, orHigh = 100.5,
                sessionHigh = 100.5, sessionLow = 99.0
            )
        )!!
        assertEquals(ResearchScore.SETUP_BREAKOUT, plan.setup)
        assertEquals("100.20 + a 0.15 break buffer", 100.35, plan.entry, 0.001)
        assertTrue(
            "the trigger sentence must name the level it came from",
            plan.trigger.contains("first 5-minute bar's high")
        )
    }

    @Test fun `an opening bar that did not close up is called out`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                or5High = 100.2, openingBarBullish = false,
                sessionHigh = 100.5, sessionLow = 99.0
            )
        )!!
        assertTrue(plan.note.contains("did not close up"))
    }

    // ================================================================= the clock, in the plan

    @Test fun `too little session left flags the plan and says so in plain sight`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, orHigh = 100.5, sessionHigh = 100.5, sessionLow = 99.0),
            minutesLeft = 10
        )!!
        assertTrue(plan.tooLateToStart)
        assertTrue(plan.note.contains("Too late in the session"))
        assertTrue(plan.exit.contains("too little to start"))
    }

    @Test fun `a full session ahead is not flagged`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, orHigh = 100.5, sessionHigh = 100.5, sessionLow = 99.0),
            minutesLeft = 300
        )!!
        assertFalse(plan.tooLateToStart)
        assertFalse(plan.note.contains("Too late in the session"))
    }

    @Test fun `zero minutes means the caller gave no clock, NOT that the day is over`() {
        // The default. Every pre-Round-73 caller and test passes nothing, and an overnight plan
        // built from the prior session's levels must not be stamped "too late to start today".
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, orHigh = 100.5, sessionHigh = 100.5, sessionLow = 99.0)
        )!!
        assertFalse(plan.tooLateToStart)
    }

    @Test fun `the midday lull is reported when the caller says the clock is in it`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, orHigh = 100.5, sessionHigh = 100.5, sessionLow = 99.0),
            minutesLeft = 240,
            middayLull = true
        )!!
        assertTrue(plan.note.contains("Midday"))
    }

    @Test fun `every plan carries the flat-before-the-close rule, which is not optional`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, orHigh = 100.5, sessionHigh = 100.5, sessionLow = 99.0),
            minutesLeft = 240
        )!!
        assertTrue(plan.exit.contains("flat by 15:50"))
        assertTrue("and why, not just the instruction", plan.exit.contains("overnight"))
    }

    // ====================================================================== the target

    @Test fun `a far resistance is no longer truncated at three times the risk`() {
        // THE DEFECT THIS GUARDS: every target used to be min(resistance, entry + 3R, room),
        // so no plan could ever aim past 3R - which truncates exactly the tail this kind of
        // trade earns from. Real resistance at 108, and a wide enough average day to reach it.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                orHigh = 100.5, prevHigh = 108.0,
                adr = 10.0, sessionHigh = 100.5, sessionLow = 99.0
            )
        )!!
        assertEquals(100.65, plan.entry, 0.001)
        assertEquals(99.15, plan.stop, 0.001)
        assertEquals("the real level, not a capped 105.15", 108.0, plan.target, 0.001)
        assertTrue("which is far past the old 3R ceiling", plan.rMultiple > 4.0)
        assertTrue(
            "and a target that big is itself worth a warning",
            plan.note.contains("big ask for one session")
        )
    }

    @Test fun `the day's remaining range still caps a target it cannot reach`() {
        // Same resistance at 108, but a stock whose whole normal day is $3: 108 is simply not
        // happening today, and letting it stand would overstate the reward:risk the beginner
        // summary and the R-multiple are both built on.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                orHigh = 100.5, prevHigh = 108.0,
                adr = 3.0, sessionHigh = 100.5, sessionLow = 99.0
            )
        )!!
        assertEquals("session low 99 + a 3.00 average day", 102.0, plan.target, 0.001)
        assertTrue(plan.target < 108.0)
    }

    @Test fun `with clear air the target comes from the measured day, not a flat 2R`() {
        // No resistance overhead at all. The old rule answered "how far can this run" with the
        // 2:1 convention even when this stock's own average range was known.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                adr = 6.0, sessionHigh = 100.0, sessionLow = 99.0
            )
        )!!
        assertEquals("session low 99 + a 6.00 average day", 105.0, plan.target, 0.001)
        assertTrue("and that is more than the old flat 2R would have allowed", plan.rMultiple > 2.0)
    }

    @Test fun `with neither structure nor a measured range the 2 to 1 convention still applies`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, sessionHigh = 100.0, sessionLow = 99.0)
        )!!
        assertEquals(2.0, plan.rMultiple, 0.001)
    }

    // ==================================================================== the warnings

    @Test fun `a trigger a long way above the last price is called out as possibly unreachable`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                prevHigh = 103.0, adr = 8.0, sessionHigh = 100.0, sessionLow = 99.0
            )
        )!!
        assertTrue("entry is 3+ ATRs above the last price", plan.entry - 100.0 > 2.0)
        assertTrue(plan.note.contains("intraday ATRs above the last price"))
    }

    @Test fun `earnings today warns about the resting order, and is NOT a disqualifier`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atrIntraday = 1.0, vwap = 99.0, orHigh = 100.5, sessionHigh = 100.5, sessionLow = 99.0),
            minutesLeft = 240,
            earningsToday = true
        )
        assertNotNull("an earnings day is the canonical reason a stock is in play", plan)
        assertTrue(plan!!.note.contains("cancel any unfilled buy order before the close"))
    }

    // ================================================================ position sizing

    @Test fun `shares come from the one-percent risk budget divided by the risk per share`() {
        val size = ResearchScore.positionSize(equity = 10_000.0, entry = 10.0, stop = 9.5)!!
        assertEquals("$100 of risk budget / $0.50 a share", 200, size.shares)
        assertEquals(2_000.0, size.notional, 0.001)
        assertEquals(100.0, size.riskDollars, 0.001)
        assertTrue("nothing to explain when the risk maths simply applies", size.note.isBlank())
    }

    @Test fun `a tight stop is capped by position size, not allowed to buy the whole account`() {
        // THE TRAP THIS GUARDS. A 5-cent stop on a $10 stock means "1% risk" alone would buy
        // $20,000 of a $10,000 account. The cap binds, and the trade then risks LESS than the
        // budget - the safe direction to be wrong in.
        val size = ResearchScore.positionSize(equity = 10_000.0, entry = 10.0, stop = 9.95)!!
        assertEquals("25% of 10,000 / $10 a share", 250, size.shares)
        assertEquals(2_500.0, size.notional, 0.001)
        assertTrue("risking less than the 1% budget, not more", size.riskDollars < 100.0)
        assertTrue(size.note.contains("Capped at"))
    }

    @Test fun `a stop too wide for the account returns zero shares and says to skip it`() {
        val size = ResearchScore.positionSize(equity = 1_000.0, entry = 100.0, stop = 80.0)!!
        assertEquals(0, size.shares)
        assertEquals(0.0, size.riskDollars, 0.001)
        assertTrue(size.note.contains("Skip it rather than size up"))
    }

    @Test fun `sizing refuses rather than inventing a number it cannot compute`() {
        assertNull("no portfolio loaded", ResearchScore.positionSize(0.0, 10.0, 9.5))
        assertNull("not a trade - stop above entry", ResearchScore.positionSize(10_000.0, 10.0, 10.5))
        assertNull("no levels", ResearchScore.positionSize(10_000.0, 0.0, 0.0))
    }

    @Test fun `sizing never assumes leverage, unlike the study it is drawn from`() {
        // Deliberate departure: the paper sizes to a 4x broker constraint. This is one person's
        // real savings, so a position never exceeds the cash value of the account.
        val size = ResearchScore.positionSize(equity = 10_000.0, entry = 1.0, stop = 0.999)!!
        assertTrue("never more than the account holds", size.notional <= 10_000.0)
    }

    // =========================================================== the beginner summary

    @Test fun `the plain-English card cannot say buy while the grid says too late`() {
        // The contradiction this parameter exists to prevent - see `beginnerSummary`'s own
        // header, which calls a summary disagreeing with the plan above it "a worse bug than
        // not having the summary at all".
        val s = ResearchScore.beginnerSummary(
            "TEST", price = 100.0, entry = 100.65, stop = 99.15, target = 103.0,
            tooLateToStart = true
        )!!
        assertTrue(s.skip)
        assertTrue(s.headline.contains("Not today"))
        assertFalse("must not still be telling a beginner to buy", s.headline.contains("Buy if"))
    }

    @Test fun `with time left the summary still gives the ordinary buy instruction`() {
        val s = ResearchScore.beginnerSummary(
            "TEST", price = 100.0, entry = 100.65, stop = 99.15, target = 103.0
        )!!
        assertFalse(s.skip)
        assertTrue(s.headline.contains("Buy if it climbs"))
    }
}
