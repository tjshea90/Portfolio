package com.tj.portfolio

import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.net.DayTradingEval
import com.tj.portfolio.net.DayTradingEval.IntradayBar
import com.tj.portfolio.net.DayTradingFeatures
import com.tj.portfolio.net.DayTradingGrader
import com.tj.portfolio.net.DayTradingGrader.Spec
import com.tj.portfolio.net.DayTradingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tj, 2026-09-24c: *"the success tracking data itself absolutely must be accurate. If it is
 * counting successes based on anomalies or errors or trades I could not have made in real life,
 * then the system is broken."* One test per way the old grade could credit a trade that could not
 * have happened (audits/2026-09-24c/DESIGN.md, E1-E8), several of them showing the old rule's
 * answer beside the new one. All on 2026-09-15, a real session (09:30 ET = 1_789_479_000).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingGraderTest {

    private val open = 1_789_479_000L
    private fun m(minute: Int) = open + 60L * minute
    private fun b(minute: Int, o: Double, h: Double, l: Double, c: Double) = IntradayBar(m(minute), h, l, c, o)
    private val settled = Long.MAX_VALUE

    /** A breakout: buy-stop 10.50, stop 10.00, target 11.50 - recorded at 10:00:30 (minute 30). */
    private fun breakout(deadline: Int = 360, flat: Int = 380) = Spec(
        entry = 10.50, stop = 10.00, target = 11.50, rises = true,
        recordedAt = m(30) * 1000L + 30_000L, entryDeadlineSec = m(deadline), flatSec = m(flat)
    )

    /** Flat filler bars from [from] to [to] (exclusive) at [px]. */
    private fun flatBars(from: Int, to: Int, px: Double) = (from until to).map { b(it, px, px + 0.02, px - 0.02, px) }

    @Test fun `E1 a fill and stop-out inside the first minutes is a loss, not a later win`() {
        // 10:00:30 recorded. 10:01 fills at 10.50, 10:02 falls to 9.95 (stop), then it rips to 11.60.
        val one = listOf(b(30, 10.40, 10.45, 10.38, 10.44), b(31, 10.44, 10.55, 10.44, 10.52),
            b(32, 10.52, 10.52, 9.95, 10.02), b(33, 10.02, 10.30, 10.01, 10.28)) +
            flatBars(34, 40, 10.6) + listOf(b(40, 10.6, 11.60, 10.6, 11.55)) + flatBars(41, 380, 11.4)
        val g = DayTradingGrader.grade(breakout(), one, settled, res = 1)
        assertEquals(DayTradingOutcome.LOSS, g.outcome)
        assertEquals(10.00, g.exitPrice!!, 1e-9)
        // THE OLD RULE, on the same day's five-minute bars: the 10:00 bar (fill + stop-out) straddles
        // the recommendation and was dropped whole, and the 10:05 bar onward never reached the stop.
        val five = listOf(IntradayBar(m(30), 10.55, 9.95, 10.28, 10.40), IntradayBar(m(35), 10.62, 10.58, 10.60, 10.6),
            IntradayBar(m(40), 11.60, 10.58, 11.55, 10.6))
        val old = DayTradingEval.evaluate("Breakout", 10.50, 10.00, 11.50, 10.40, m(30) * 1000L + 30_000L, five, false)
        assertEquals("the old grade credited this as a win", DayTradingOutcome.WIN, old.first)
    }

    @Test fun `E2 a buy-stop already through its entry fills at the open, not the entry`() {
        // Price is 10.80 when the plan is recorded (a Claude plan made the night before, say).
        val bars = listOf(b(31, 10.80, 10.90, 10.75, 10.85)) + flatBars(32, 60, 10.9) +
            listOf(b(60, 10.9, 11.55, 10.9, 11.5)) + flatBars(61, 380, 11.4)
        val g = DayTradingGrader.grade(breakout(), bars, settled, res = 1)
        assertEquals(DayTradingOutcome.WIN, g.outcome)
        assertEquals(10.80, g.detail!!.fill, 1e-9)
        // P&L runs from 10.80, not 10.50: +0.70 a share gross, not +1.00.
        val row = DayTradingLogEntry(1, "T", "20260915", m(30) * 1000L, "Breakout", 10.50, 10.00, 11.50, 10.4,
            "APP", g.outcome, g.exitPrice, 1L, "v0", "", DayTradingGrader.VERSION, g.detail!!.toJson())
        val s = DayTradingEval.stats(listOf(row))
        assertEquals((11.50 - 10.80) / 10.80 * 100.0, s.avgReturnPct, 1e-6)
    }

    @Test fun `a buy-stop that jumps straight past its target is no win - it is sold at once, for its costs`() {
        // the first bar after the recommendation opens at 11.70, above the 11.50 target
        val bars = listOf(b(31, 11.70, 11.90, 11.60, 11.80)) + flatBars(32, 380, 11.8)
        val g = DayTradingGrader.grade(breakout(), bars, settled, res = 1)
        assertEquals(DayTradingOutcome.CLOSED_LOSS, g.outcome)
        assertEquals(11.70, g.exitPrice!!, 1e-9)
        assertEquals("gap-target", g.detail!!.why)
        val row = DayTradingLogEntry(1, "T", "20260915", m(30) * 1000L, "Breakout", 10.50, 10.00, 11.50, 10.4,
            "APP", g.outcome, g.exitPrice, 1L, "v0", "", DayTradingGrader.VERSION, g.detail!!.toJson())
        val s = DayTradingEval.stats(listOf(row))
        assertEquals(0, s.targetHit)
        assertTrue("it lost its costs", s.avgR < 0.0 && s.avgR > -0.05)
    }

    @Test fun `E2 a gap through the stop fills at the open, below the stop`() {
        val bars = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 50, 10.4) +
            listOf(b(50, 9.70, 9.80, 9.60, 9.75)) + flatBars(51, 380, 9.8)
        val g = DayTradingGrader.grade(breakout(), bars, settled, res = 1)
        assertEquals(DayTradingOutcome.LOSS, g.outcome)
        assertEquals("a halt reopening below the stop", 9.70, g.exitPrice!!, 1e-9)
        assertEquals("gap-stop", g.detail!!.why)
    }

    @Test fun `E3 a target touched but not traded through does not fill`() {
        val touch = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 60, 10.9) +
            listOf(b(60, 11.0, 11.50, 10.95, 11.2)) + flatBars(61, 380, 11.1)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, DayTradingGrader.grade(breakout(), touch, settled, 1).outcome)
        val through = touch.map { if (it.t == m(60)) it.copy(high = 11.51, close = 11.45) else it }
        assertEquals(DayTradingOutcome.WIN, DayTradingGrader.grade(breakout(), through, settled, 1).outcome)
        // the old touch rule called the first one a win
        assertEquals(DayTradingOutcome.WIN,
            DayTradingEval.evaluate("Breakout", 10.50, 10.00, 11.50, 10.4, m(30) * 1000L + 30_000L, touch, false).first)
    }

    @Test fun `E3 a buy-limit needs price to trade through it`() {
        val pull = Spec(entry = 10.00, stop = 9.60, target = 10.80, rises = false,
            recordedAt = m(30) * 1000L, entryDeadlineSec = m(360), flatSec = m(380))
        val touch = listOf(b(31, 10.2, 10.25, 10.00, 10.1)) + flatBars(32, 380, 10.3)
        assertEquals(DayTradingOutcome.NO_ENTRY, DayTradingGrader.grade(pull, touch, settled, 1).outcome)
        val through = listOf(b(31, 10.2, 10.25, 9.99, 10.1)) + flatBars(32, 380, 10.3)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, DayTradingGrader.grade(pull, through, settled, 1).outcome)
        // and one that opens below the limit fills there - a better price, as a real limit would
        val gapDown = listOf(b(31, 9.90, 9.95, 9.85, 9.9)) + flatBars(32, 380, 10.3)
        assertEquals(9.90, DayTradingGrader.grade(pull, gapDown, settled, 1).detail!!.fill, 1e-9)
    }

    @Test fun `a buy-limit's own fill bar cannot prove its target`() {
        val pull = Spec(entry = 10.00, stop = 9.60, target = 10.80, rises = false,
            recordedAt = m(30) * 1000L, entryDeadlineSec = m(360), flatSec = m(380))
        // the bar spikes to 10.90 then falls to 9.95 - the high may have come first
        val bars = listOf(b(31, 10.7, 10.90, 9.95, 10.0)) + flatBars(32, 380, 10.1)
        val g = DayTradingGrader.grade(pull, bars, settled, 1)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, g.outcome)
        assertTrue(g.ambiguous)
    }

    @Test fun `E4 an entry that only triggers after the plan's too-late time is no trade`() {
        // deadline 15:30 (minute 360): the break comes at 15:40
        val bars = flatBars(31, 370, 10.3) + listOf(b(370, 10.3, 10.60, 10.3, 10.55)) + flatBars(371, 380, 10.55)
        val g = DayTradingGrader.grade(breakout(), bars, settled, 1)
        assertEquals(DayTradingOutcome.NO_ENTRY, g.outcome)
        assertEquals("unfilled", g.detail!!.why)
        // without a deadline (the old rule) it was graded as a trade
        assertEquals(DayTradingOutcome.CLOSED_PROFIT,
            DayTradingEval.evaluate("Breakout", 10.50, 10.00, 11.50, 10.4, m(30) * 1000L + 30_000L, bars, false).first)
    }

    @Test fun `an open trade is sold at the flat time, not the bell`() {
        val bars = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 379, 10.8) +
            listOf(b(379, 10.8, 10.9, 10.8, 10.85), b(380, 10.85, 11.60, 10.85, 11.55))
        val g = DayTradingGrader.grade(breakout(), bars, settled, 1)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, g.outcome)
        assertEquals(10.85, g.exitPrice!!, 1e-9)   // the 15:49 bar's close, not the 15:50 spike
        assertEquals("flat", g.detail!!.why)
    }

    @Test fun `mid-session nothing but a stop or a target is final`() {
        val bars = flatBars(31, 100, 10.3)
        assertEquals(DayTradingOutcome.PENDING, DayTradingGrader.grade(breakout(), bars, 0L, 1).outcome)
        val filled = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 100, 10.6)
        assertEquals(DayTradingOutcome.PENDING, DayTradingGrader.grade(breakout(), filled, 0L, 1).outcome)
        val stopped = filled + listOf(b(100, 10.3, 10.3, 9.9, 10.0))
        assertEquals(DayTradingOutcome.LOSS, DayTradingGrader.grade(breakout(), stopped, 0L, 1).outcome)
    }

    @Test fun `E7 a lone bad print does not fill the target, a real run does`() {
        val quiet = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 100, 10.6)
        val spike = quiet + listOf(b(100, 10.6, 11.80, 10.58, 10.6)) + flatBars(101, 380, 10.6)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, DayTradingGrader.grade(breakout(), spike, settled, 1).outcome)
        val real = quiet + listOf(b(100, 10.6, 11.80, 10.58, 11.6), b(101, 11.6, 11.7, 11.4, 11.5)) + flatBars(102, 380, 11.4)
        assertEquals(DayTradingOutcome.WIN, DayTradingGrader.grade(breakout(), real, settled, 1).outcome)
    }

    @Test fun `the grid's plan cell is the verdict, and excursions are measured from the fill`() {
        val bars = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 60, 10.7) +
            listOf(b(60, 10.7, 10.7, 10.25, 10.3)) + flatBars(61, 90, 10.6) +
            listOf(b(90, 10.6, 11.55, 10.6, 11.5)) + flatBars(91, 380, 11.8)
        val g = DayTradingGrader.grade(breakout(), bars, settled, 1)
        assertEquals(DayTradingOutcome.WIN, g.outcome)
        val d = g.detail!!
        val planCell = d.grid[DayTradingGrader.GRID_STOPS.indexOf(1.0)][DayTradingGrader.GRID_TARGETS.indexOf(DayTradingGrader.GRID_PLAN)]
        val paid = DayTradingEval.Costs.entryFill(10.50)
        // IN ACCOUNT % (DA-6): here the 1% risk budget sizes it (0.02 shares per $ of account), so
        // the cell is the same number as the trade's R - the capped case is its own test below.
        assertEquals(DayTradingGrader.accountPct(10.50, 10.00, paid, 11.50), planCell, 0.001)
        assertEquals((11.50 - paid) / 0.50, planCell, 0.01)
        assertEquals((11.55 - 10.50) / 0.50, d.mfeR, 0.01)
        assertEquals((10.50 - 10.25) / 0.50, d.maeR, 0.01)
        // a 0.5R stop (10.25) was hit at 10:30, before the target
        val half = d.grid[DayTradingGrader.GRID_STOPS.indexOf(0.5)][DayTradingGrader.GRID_TARGETS.indexOf(DayTradingGrader.GRID_PLAN)]
        assertTrue(half < 0.0)
        // held with no target it ran to the 15:49 close, 11.80
        assertEquals((11.80 - 10.50) / 0.50, d.holdR, 0.05)
        val back = DayTradingGrader.Detail.parse(d.toJson())!!
        assertEquals(d.fillAt, back.fillAt); assertEquals(d.exitAt, back.exitAt); assertEquals(d.why, back.why)
        assertEquals(d.mfeR, back.mfeR, 0.01); assertEquals(d.grid.size, back.grid.size)
        assertEquals(planCell, back.grid[2][7], 0.01)
    }

    @Test fun `deadline and flat times follow the engine and the calendar`() {
        val day = "20260915"
        val et = java.time.ZoneId.of("America/New_York")
        fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(et).let { it.hour * 100 + it.minute }
        val d0 = DayTradingParams.DEFAULTS
        assertEquals(1530, hm(DayTradingFeatures.entryDeadlineMs(day, m(30) * 1000L, d0)!!))
        assertEquals(1550, hm(DayTradingFeatures.flatMs(day, d0)!!))
        val lull = d0.with(mapOf(DayTradingParams.AVOID_LULL to 1.0))
        assertEquals(1130, hm(DayTradingFeatures.entryDeadlineMs(day, m(30) * 1000L, lull)!!))
        assertEquals("made after the lull: normal cut-off", 1530, hm(DayTradingFeatures.entryDeadlineMs(day, m(250) * 1000L, lull)!!))
        // the day after Thanksgiving 2026 closes at 13:00
        assertEquals(1230, hm(DayTradingFeatures.entryDeadlineMs("20261127", 0L, d0)!!))
        assertEquals(1250, hm(DayTradingFeatures.flatMs("20261127", d0)!!))
        // a row logged before features existed gets the original engine's times
        val (dl, fl) = DayTradingFeatures.timesFor(day, m(30) * 1000L, "")
        assertEquals(1530, hm(dl!!)); assertEquals(1550, hm(fl!!))
    }

    private fun decided(id: Long, fillMin: Int, exitMin: Int, outcome: String = DayTradingOutcome.WIN): DayTradingLogEntry {
        val detail = DayTradingGrader.Detail(res = 1, fill = 10.50, fillAt = m(fillMin), exitAt = m(exitMin), why = "target")
        return DayTradingLogEntry(id, "S$id", "20260915", m(fillMin - 1) * 1000L, "Breakout", 10.50, 10.00, 11.50,
            10.4, "APP", outcome, if (outcome == DayTradingOutcome.WIN) 11.50 else 10.00, 1L, "v0", "",
            DayTradingGrader.VERSION, detail.toJson())
    }

    @Test fun `E8 the account figure only counts trades the portfolio could fund at once`() {
        // 10.50 with a 0.50 stop: 1% risk buys 0.02 shares per $ of equity = 21% of it per position.
        // Five open together from 10:00 to 11:00 - 105% of the portfolio: the fifth cannot be funded.
        val rows = (1L..5L).map { decided(it, 31, 90) }
        val s = DayTradingEval.stats(rows)
        assertEquals(5, s.entriesTriggered)
        assertEquals(1, s.unfundedTrades)
        assertEquals(s.accountReturnAllPct * 4.0 / 5.0, s.accountReturnPct, 1e-9)
        // the same five one after another all fit
        val serial = (1L..5L).map { decided(it, 31 + it.toInt() * 20, 40 + it.toInt() * 20) }
        assertEquals(0, DayTradingEval.stats(serial).unfundedTrades)
    }

    @Test fun `E9 verdicts from the old grader are kept out of the headline`() {
        val old = decided(9, 31, 90).copy(evalVersion = 0)
        val later = m(400) * 1000L + 90L * 86_400_000L   // three months on: its bars are gone
        val s = DayTradingEval.stats(listOf(old, decided(1, 31, 90, DayTradingOutcome.LOSS)), later)
        assertEquals(1, s.legacyExcluded)
        assertEquals(0, s.regrading)
        assertEquals(1, s.entriesTriggered)
        assertEquals(0, s.targetHit)
        // the same day, bars still there: queued for re-grading, still not counted
        val soon = DayTradingEval.stats(listOf(old, decided(1, 31, 90, DayTradingOutcome.LOSS)), m(400) * 1000L)
        assertEquals(0, soon.legacyExcluded)
        assertEquals(1, soon.regrading)
        assertEquals(1, soon.entriesTriggered)
        // and while its bars exist it is queued for re-grading
        val now = m(400) * 1000L
        assertEquals(listOf(9L), com.tj.portfolio.ui.dayTradingRowsNeedingGrade(listOf(old, decided(1, 31, 90)), now).map { it.id })
    }

    @Test fun `E10 intervals are honest about small samples`() {
        val (lo, hi) = DayTradingEval.wilson(3, 5)
        assertTrue(lo < 25.0 && hi > 85.0)   // 3 of 5 says almost nothing
        val (lo2, hi2) = DayTradingEval.wilson(300, 500)
        assertTrue(lo2 > 55.0 && hi2 < 65.0)
        val (mLo, mHi) = DayTradingEval.meanInterval(listOf(1.0, -1.0, 2.0, -1.0))
        assertTrue(mLo < 0.0 && mHi > 0.0)
        assertEquals(3.0, DayTradingEval.maxDrawdown(listOf(1.0, -1.0, -2.0, 0.5)), 1e-9)
        assertEquals(com.tj.portfolio.data.DayTradingStats.sampleNote(5).startsWith("Too few"), true)
        assertEquals("A solid sample", com.tj.portfolio.data.DayTradingStats.sampleNote(150))
    }

    @Test fun `bar opens are parsed, and an open outside the bar's own range is dropped`() {
        val body = """{"chart":{"result":[{"timestamp":[1,2],"indicators":{"quote":[{"open":[10.0,99.0],"high":[10.5,10.5],"low":[9.5,9.5],"close":[10.2,10.1]}]}}],"error":null}}"""
        val bars = DayTradingEval.parseBars(body)
        assertEquals(10.0, bars[0].open, 0.0)
        assertTrue(bars[1].open.isNaN())
        assertNotNull(bars)
        assertFalse(bars.isEmpty())
    }

    // ---------------------------------------------------------------- audit round 2 (daytrading.md)

    @Test fun `DA-1 a verdict reached mid-session carries no grid or hold, and the settled re-grade fills them in`() {
        // Target traded through at 10:40; the check runs at 11:00; the stock then runs to 13.00.
        val morning = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 70, 10.9) +
            listOf(b(70, 10.9, 11.60, 10.9, 11.55)) + flatBars(71, 90, 11.6)
        val mid = DayTradingGrader.grade(breakout(), morning, 0L, 1)
        assertEquals(DayTradingOutcome.WIN, mid.outcome)
        val md = mid.detail!!
        assertTrue("partial", md.partial)
        assertTrue("no grid from half a day", md.grid.isEmpty())
        val json = md.toJson()
        assertFalse(json.contains("grid")); assertFalse(json.contains("hold")); assertFalse(json.contains("mfeFlat"))
        assertTrue(DayTradingGrader.Detail.isPartial(json))
        assertTrue(DayTradingGrader.Detail.parse(json)!!.partial)

        val full = morning + flatBars(90, 300, 12.0) + flatBars(300, 380, 13.0)
        val settledGrade = DayTradingGrader.grade(breakout(), full, settled, 1)
        assertEquals(DayTradingOutcome.WIN, settledGrade.outcome)
        val fd = settledGrade.detail!!
        assertFalse(fd.partial)
        assertTrue("held with no target it ran to 13", fd.holdR > 4.5)
        val none = fd.grid[DayTradingGrader.GRID_STOPS.indexOf(1.0)][DayTradingGrader.GRID_TARGETS.indexOf(DayTradingGrader.GRID_NONE)]
        assertTrue("the no-target cell sees the whole day: $none", none > 4.0)
        assertFalse(DayTradingGrader.Detail.isPartial(fd.toJson()))

        // Re-graded once after the settle, never again.
        val day = "20260915"
        val closeMs = DayTradingEval.sessionBoundsMs(day)!!.second
        val afterSettle = closeMs + DayTradingEval.SETTLE_GRACE_MS + 60_000L
        assertFalse("not before the settle", DayTradingGrader.needsSettledRegrade(json, closeMs - 1000L, day, closeMs))
        assertTrue(DayTradingGrader.needsSettledRegrade(json, closeMs - 1000L, day, afterSettle))
        assertFalse("already re-graded after the settle", DayTradingGrader.needsSettledRegrade(json, afterSettle, day, afterSettle + 1))
        assertFalse("a complete working never", DayTradingGrader.needsSettledRegrade(fd.toJson(), 0L, day, afterSettle))
    }

    @Test fun `DA-7 a lone bad LOW print does not fill a pullback's buy-limit, a real flush does`() {
        // Pullback: buy-limit 50.00, stop 49.40, target 51.20, recorded with price at 50.60.
        val spec = Spec(entry = 50.00, stop = 49.40, target = 51.20, rises = false,
            recordedAt = m(30) * 1000L + 30_000L, entryDeadlineSec = m(360), flatSec = m(380))
        fun quiet(from: Int, to: Int, px: Double) = (from until to).map { b(it, px, px + 0.03, px - 0.02, px + 0.01) }
        val spike = quiet(31, 100, 50.60) + listOf(b(100, 50.62, 50.64, 49.80, 50.61)) + quiet(101, 270, 50.60) +
            listOf(b(270, 50.9, 51.25, 50.9, 51.2)) + quiet(271, 380, 51.1)
        val g = DayTradingGrader.grade(spec, spike, settled, 1)
        assertEquals("the limit never really filled", DayTradingOutcome.NO_ENTRY, g.outcome)
        // A real flush: the next bar trades through the entry too.
        val flush = quiet(31, 100, 50.60) + listOf(b(100, 50.62, 50.64, 49.80, 49.95), b(101, 49.95, 50.05, 49.90, 50.0)) +
            quiet(102, 270, 50.60) + listOf(b(270, 50.9, 51.25, 50.9, 51.2)) + quiet(271, 380, 51.1)
        val real = DayTradingGrader.grade(spec, flush, settled, 1)
        assertEquals(DayTradingOutcome.WIN, real.outcome)
        assertEquals(50.00, real.detail!!.fill, 1e-9)
    }

    @Test fun `DA-8 with no open, a gap is priced where the bar really traded`() {
        // Buy-stop 10.50: the 10:32 bar gaps wholly above it (low 10.70) and its open is unknown. The
        // previous close (10.40) is outside the bar, so where it opened is unknown: read against the
        // trade (R2G-6) - its high, 10.90, flagged ambiguous - never the 10.50 no one traded at.
        val gapUp = listOf(b(31, 10.40, 10.45, 10.38, 10.40), IntradayBar(m(32), 10.90, 10.70, 10.85, Double.NaN)) +
            flatBars(33, 380, 10.8)
        val g = DayTradingGrader.grade(breakout(), gapUp, settled, 1)
        assertEquals(10.90, g.detail!!.fill, 1e-9)
        assertTrue(g.ambiguous)
        // A continuous bar (the previous close inside it) opens at that close.
        val flowing = listOf(b(31, 10.40, 10.45, 10.38, 10.44), IntradayBar(m(32), 10.60, 10.42, 10.55, Double.NaN)) +
            flatBars(33, 380, 10.6)
        assertEquals(10.50, DayTradingGrader.grade(breakout(), flowing, settled, 1).detail!!.fill, 1e-9)
        // And a stop the price gapped wholly through (high 9.80) with no open exits at <= 9.80, not 10.00.
        val gapDown = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 60, 10.6) +
            listOf(IntradayBar(m(60), 9.80, 9.60, 9.70, Double.NaN)) + flatBars(61, 380, 9.7)
        val s = DayTradingGrader.grade(breakout(), gapDown, settled, 1)
        assertEquals(DayTradingOutcome.LOSS, s.outcome)
        assertEquals("a gap with no open is sold at the bar's low, against the trade", 9.60, s.exitPrice!!, 1e-9)
        // The day's very first bar has no previous close: it is read against the trade.
        val firstBar = listOf(IntradayBar(m(31), 10.90, 10.70, 10.85, Double.NaN)) + flatBars(32, 380, 10.8)
        val f = DayTradingGrader.grade(breakout(), firstBar, settled, 1)
        assertEquals(10.90, f.detail!!.fill, 1e-9)
        assertTrue(f.ambiguous)
        // The touch rules (evaluateResolved) keep their old reading.
        assertEquals(DayTradingOutcome.CLOSED_PROFIT,
            DayTradingEval.evaluateResolved("Breakout", 10.50, 10.0, 11.5, 10.4, m(30) * 1000L + 30_000L, gapUp, false).outcome)
    }

    @Test fun `DA-17 a settled series that stops well short of the flat time decides nothing it cannot see`() {
        // Filled, still open when the data stops at 13:40 (minute 250): not "closed at the flat time".
        val cut = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 250, 10.8)
        assertEquals(DayTradingOutcome.PENDING, DayTradingGrader.grade(breakout(), cut, settled, 1).outcome)
        // Not filled by 13:40 with a 15:30 cut-off: not "never filled" either.
        assertEquals(DayTradingOutcome.PENDING, DayTradingGrader.grade(breakout(), flatBars(31, 250, 10.2), settled, 1).outcome)
        // But a stop or target inside the data still decides it - with a partial working.
        val won = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 100, 10.8) +
            listOf(b(100, 10.8, 11.6, 10.8, 11.55)) + flatBars(101, 250, 11.5)
        val w = DayTradingGrader.grade(breakout(), won, settled, 1)
        assertEquals(DayTradingOutcome.WIN, w.outcome)
        assertTrue(w.detail!!.partial)
        // A series reaching within 15 minutes of the flat time is the whole day.
        val nearly = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 368, 10.8)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, DayTradingGrader.grade(breakout(), nearly, settled, 1).outcome)
        // And an unfilled order whose cut-off (the lull, 11:30) is inside the data is a real NO_ENTRY.
        assertEquals(DayTradingOutcome.NO_ENTRY,
            DayTradingGrader.grade(breakout(deadline = 120), flatBars(31, 250, 10.2), settled, 1).outcome)
    }

    @Test fun `DA-13 on five-minute bars nothing trades past a cut-off that is not on the grid`() {
        // Flat at 15:47 (minute 377): the 15:45 bar ends 15:50 and is not traded or held.
        val spec = breakout(flat = 377)
        val five = listOf(IntradayBar(m(35), 10.60, 10.44, 10.55, 10.44)) +
            (40 until 375 step 5).map { IntradayBar(m(it), 10.62, 10.58, 10.6, 10.6) } +
            listOf(IntradayBar(m(375), 11.60, 10.6, 11.55, 10.6))
        val g = DayTradingGrader.grade(spec, five, settled, 5)
        assertEquals("the 15:45 bar's rally is past the flat time", DayTradingOutcome.CLOSED_PROFIT, g.outcome)
        assertEquals(10.6, g.exitPrice!!, 1e-9)
        // Entry cut-off 15:32 (minute 362): a trigger in the 15:30 bar (ends 15:35) is too late.
        val late = (35 until 360 step 5).map { IntradayBar(m(it), 10.3, 10.2, 10.25, 10.25) } +
            listOf(IntradayBar(m(360), 10.60, 10.25, 10.55, 10.25)) + (365 until 380 step 5).map { IntradayBar(m(it), 10.6, 10.5, 10.55, 10.55) }
        assertEquals(DayTradingOutcome.NO_ENTRY, DayTradingGrader.grade(breakout(deadline = 362), late, settled, 5).outcome)
    }

    @Test fun `DA-6 the grid is what the account made - a capped trade's tighter stop earns no more`() {
        // entry 50 / stop 49.40: the 25% cap sizes it (0.005 shares per $), not the 1% budget.
        val spec = Spec(entry = 50.00, stop = 49.40, target = 51.20, rises = true,
            recordedAt = m(30) * 1000L + 30_000L, entryDeadlineSec = m(360), flatSec = m(380))
        val bars = listOf(b(31, 49.9, 50.10, 49.9, 50.05)) + (32 until 100).map { b(it, 50.2, 50.25, 50.15, 50.2) } +
            listOf(b(100, 50.2, 51.25, 50.2, 51.2)) + (101 until 380).map { b(it, 51.1, 51.15, 51.05, 51.1) }
        val g = DayTradingGrader.grade(spec, bars, settled, 1)
        assertEquals(DayTradingOutcome.WIN, g.outcome)
        val d = g.detail!!
        val ti = DayTradingGrader.GRID_TARGETS.indexOf(DayTradingGrader.GRID_PLAN)
        val plan = d.grid[DayTradingGrader.GRID_STOPS.indexOf(1.0)][ti]
        val tight = d.grid[DayTradingGrader.GRID_STOPS.indexOf(0.5)][ti]
        // Same exit, same money: the R doubles at the half stop, the account does not move.
        assertEquals(plan, tight, 0.001)
        val paid = DayTradingEval.Costs.entryFill(50.0)
        assertEquals(0.25 / 50.0 * (51.20 - paid) * 100.0, plan, 0.001)
    }

    @Test fun `settled bars survive a round trip through the cache encoding`() {
        val bars = listOf(IntradayBar(m(0), 10.5, 10.1, 10.4, 10.2), IntradayBar(m(1), 10.45, 10.3, 10.35, Double.NaN),
            IntradayBar(m(2), 0.1234, 0.1201, 0.1222, 0.121))
        val back = DayTradingEval.decodeBars(DayTradingEval.encodeBars(bars))
        assertEquals(bars.size, back.size)
        for ((a, c) in bars.zip(back)) {
            assertEquals(a.t, c.t); assertEquals(a.high, c.high, 1e-9); assertEquals(a.low, c.low, 1e-9)
            assertEquals(a.close, c.close, 1e-9)
            if (a.open.isNaN()) assertTrue(c.open.isNaN()) else assertEquals(a.open, c.open, 1e-9)
        }
        assertTrue(DayTradingEval.decodeBars(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(DayTradingEval.decodeBars(null).isEmpty())
    }

    // ---------------------------------------------------------------- audit round 2 (round2-grading.md)

    /** Pullback: buy-limit 50.00, stop 49.40, target 51.20, recorded at 10:00:30 with the price at 50.60. */
    private fun pullback() = Spec(entry = 50.00, stop = 49.40, target = 51.20, rises = false,
        recordedAt = m(30) * 1000L + 30_000L, entryDeadlineSec = m(360), flatSec = m(380))
    private fun quiet(from: Int, to: Int, px: Double) = (from until to).map { b(it, px, px + 0.03, px - 0.02, px + 0.01) }

    @Test fun `R2G-2 a suspect low print that also runs through the stop is graded as the loss it may have been`() {
        // The wick goes to 49.30 - through the entry AND the stop - and no neighbour comes near.
        val flush = quiet(0, 100, 50.60) + listOf(b(100, 50.62, 50.64, 49.30, 50.61)) + quiet(101, 380, 50.60)
        val g = DayTradingGrader.grade(pullback(), flush, settled, 1)
        assertEquals("not a quiet NO_ENTRY", DayTradingOutcome.LOSS, g.outcome)
        assertTrue(g.ambiguous)
        // A suspect low above the stop: "filled, held to a small profit" and "never filled" - the
        // worse of the two, no trade, stands. It never becomes a win on a fill that may not have happened.
        val shallow = quiet(0, 100, 50.60) + listOf(b(100, 50.62, 50.64, 49.80, 50.61)) + quiet(101, 380, 50.60)
        assertEquals(DayTradingOutcome.NO_ENTRY, DayTradingGrader.grade(pullback(), shallow, settled, 1).outcome)
        // ...and when holding it would have lost, the loss stands.
        val fading = quiet(0, 100, 50.60) + listOf(b(100, 50.62, 50.64, 49.80, 50.61)) + quiet(101, 380, 49.60)
        assertEquals(DayTradingOutcome.CLOSED_LOSS, DayTradingGrader.grade(pullback(), fading, settled, 1).outcome)
    }

    @Test fun `R2G-5 a print is judged the same mid-session and after the close`() {
        // A volatile first 40 minutes (1m ranges ~0.20), quiet after (~0.04). The 10:05 wick to 49.30
        // was graded at 10:10 and again after the close: the ruler is the bars BEFORE it, so both agree.
        val open = (0 until 35).map { b(it, 50.35, 50.45, 50.25, 50.36) }
        val wick = listOf(b(35, 50.35, 50.40, 49.30, 50.30))
        val after = (36 until 380).map { b(it, 50.30, 50.32, 50.28, 50.30) }
        val spec = pullback().copy(recordedAt = m(20) * 1000L + 30_000L)
        val mid = DayTradingGrader.grade(spec, open + wick + after.take(4), 0L, 1)
        val full = DayTradingGrader.grade(spec, open + wick + after, settled, 1)
        assertEquals(DayTradingOutcome.LOSS, mid.outcome)
        assertEquals(mid.outcome, full.outcome)
        assertEquals(mid.exitPrice, full.exitPrice)
    }

    @Test fun `R2G-4 a thin stock's quiet last quarter-hour is not a short reply when it printed after the flat time`() {
        val filled = listOf(b(31, 10.44, 10.60, 10.44, 10.55)) + flatBars(32, 363, 10.8) +
            listOf(b(382, 10.8, 10.85, 10.75, 10.8), b(388, 10.8, 10.82, 10.78, 10.8))   // prints at 15:52 and 15:58
        val g = DayTradingGrader.grade(breakout(), filled, settled, 1)
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, g.outcome)
        assertTrue(g.complete)
    }

    @Test fun `R2G-10 an order window shorter than one bar is not graded as never filled`() {
        // 5-minute bars, recorded 11:26 with an 11:30 cut-off: no bar starts after it and ends by it.
        val spec = breakout(deadline = 120).copy(recordedAt = m(116) * 1000L)
        val five = (0 until 380 step 5).map { IntradayBar(m(it), 10.3, 10.2, 10.25, 10.25) }
        val g = DayTradingGrader.grade(spec, five, settled, 5)
        assertEquals(DayTradingOutcome.PENDING, g.outcome)
        assertTrue("the day's bars are whole, so they may be kept", g.complete)
        // The same plan on 1-minute bars has four minutes to fill in.
        assertEquals(DayTradingOutcome.NO_ENTRY,
            DayTradingGrader.grade(spec, (0 until 380).map { b(it, 10.25, 10.3, 10.2, 10.25) }, settled, 1).outcome)
    }

    @Test fun `R2P-2 float32 prices are rounded, so an exact touch is decided the same fresh and from the cache`() {
        val body = """{"chart":{"result":[{"timestamp":[${m(31)},${m(32)}],"indicators":{"quote":[{""" +
            """"open":[12.40000057220459,12.39000034332275],"high":[12.40999984741211,12.39999961853027],""" +
            """"low":[12.35000038146973,12.34000015258789],"close":[12.39000034332275,12.35000038146973]}]}}],"error":null}}"""
        val bars = DayTradingEval.parseBars(body)
        assertEquals(12.34, bars[1].low, 0.0)
        val spec = Spec(entry = 12.35, stop = 12.0, target = 13.0, rises = false,
            recordedAt = m(30) * 1000L + 30_000L, entryDeadlineSec = m(360), flatSec = m(380))
        val all = bars + (33 until 380).map { b(it, 12.36, 12.37, 12.35, 12.36) }
        val fresh = DayTradingGrader.grade(spec, all, settled, 1)
        val cached = DayTradingGrader.grade(spec, DayTradingEval.decodeBars(DayTradingEval.encodeBars(all)), settled, 1)
        assertEquals("12.34 is one tick through 12.35 - filled", 12.35, fresh.detail!!.fill, 1e-9)
        assertEquals(fresh.outcome, cached.outcome)
        assertEquals(fresh.exitPrice, cached.exitPrice)
    }

    @Test fun `R2G-8 an old row logged already through its buy price is not graded as the opposite order`() {
        fun old(setup: String, price: Double) = DayTradingLogEntry(1, "OLD", "20260915", m(30) * 1000L, setup,
            20.0, 19.5, 21.0, price, "CLAUDE", DayTradingOutcome.WIN, 21.0, 1L, "", "", 0, "")
        assertTrue("a breakout shown above its buy-stop", DayTradingEval.notTradeableOldRow(old("Breakout", 20.30)))
        assertFalse(DayTradingEval.notTradeableOldRow(old("Breakout", 19.80)))
        assertTrue("a pullback shown below its buy-limit", DayTradingEval.notTradeableOldRow(old("Pullback", 19.80)))
        assertFalse(DayTradingEval.notTradeableOldRow(old("Pullback", 20.30)))
        assertFalse("a free-text setup cannot be checked", DayTradingEval.notTradeableOldRow(old("Support bounce", 20.30)))
        assertFalse("a current row is never an old row",
            DayTradingEval.notTradeableOldRow(old("Breakout", 20.30).copy(features = "{\"v\":0}")))
    }

    @Test fun `R2P-3 a 400 is a failure to ask again, a 422 from both hosts is an answer`() = kotlinx.coroutines.runBlocking {
        try {
            com.tj.portfolio.net.Http.scriptedForTests = { com.tj.portfolio.net.HttpResult(400, "bad") }
            assertEquals(null, DayTradingEval.fetchDaySeries("ZZZ", "20260915", interval = "1m"))
            val seen = java.util.Collections.synchronizedList(ArrayList<String>())
            com.tj.portfolio.net.Http.scriptedForTests = { url -> seen.add(url); com.tj.portfolio.net.HttpResult(422, "{}") }
            assertEquals(emptyList<IntradayBar>(), DayTradingEval.fetchDaySeries("ZZZ", "20260915", interval = "1m"))
            assertEquals("both hosts asked", 2, seen.size)
        } finally {
            com.tj.portfolio.net.Http.scriptedForTests = null
        }
    }
}
