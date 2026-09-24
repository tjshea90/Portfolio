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
}
