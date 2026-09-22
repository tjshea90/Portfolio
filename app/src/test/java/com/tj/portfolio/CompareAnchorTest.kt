package com.tj.portfolio

import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.BENCHMARK_SYMBOL
import com.tj.portfolio.ui.clipToWindow
import com.tj.portfolio.ui.compareAnchorFor
import com.tj.portfolio.ui.compareLines
import com.tj.portfolio.ui.insideIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DRAGGING A COMPARED CHART NEVER CHANGES WHAT THE LINES SAY (2026-09-22c).
 *
 * Tj's third screen recording: a zoomed 1D SNDC chart, finger held and dragged left and right,
 * and the SPY line "still jumping up and down". Round 79 had fixed the comparison's zero to the
 * range's true start - but only for daily-and-longer ranges. On 1D the STOCK was still measured
 * from the first candle on screen while SPY was measured from its previous close, and on 5D both
 * were re-zeroed at the window's edge on every frame, so the same 11:30 candle could read above
 * SPY in one frame and below it in the next.
 *
 * The invariant pinned here, for every range: at any one timestamp, the stock's percentage, the
 * benchmark's percentage and the gap between them are IDENTICAL whichever window the timestamp
 * is seen through - unzoomed, zoomed, or zoomed and dragged somewhere else. Everything is
 * computed through `compareLines`, the exact function `PriceChart` draws from.
 */
class CompareAnchorTest {

    private data class Case(val range: ChartRange, val stepSec: Long, val n: Int)

    private val cases = listOf(
        Case(ChartRange.D1, 5 * 60L, 78),          // today, 5-minute candles
        Case(ChartRange.D5, 30 * 60L, 65),         // five days, 30-minute candles
        Case(ChartRange.Y1, 86_400L, 250)          // a year, daily closes
    )

    private val t0 = 1_758_547_800L                // 2025-09-22 09:30 ET

    /** A stock that swings hard in both directions, so a moving zero flips the order. */
    private fun stock(c: Case) = ChartSeries(
        symbol = "SNDC", range = c.range,
        points = (0 until c.n).map { i ->
            ChartPoint(t0 + i * c.stepSec, 10.0 + 0.8 * kotlin.math.sin(i / 6.0) + i * 0.004)
        },
        baseline = if (c.range == ChartRange.D1) 9.6 else 0.0,
        fetched = System.currentTimeMillis()
    )

    /** A benchmark that barely moves - SPY on a quiet day. */
    private fun bench(c: Case) = ChartSeries(
        symbol = BENCHMARK_SYMBOL, range = c.range,
        points = (0 until c.n).map { i -> ChartPoint(t0 + i * c.stepSec, 660.0 + 0.3 * kotlin.math.sin(i / 9.0)) },
        baseline = if (c.range == ChartRange.D1) 659.5 else 0.0,
        fetched = System.currentTimeMillis()
    )

    /** stock %, benchmark % and the gap, keyed by timestamp, for the part of [w] on screen. */
    private fun readings(c: Case, w: ChartWindow?): Map<Long, Triple<Double, Double, Double>> {
        val shown = stock(c)
        val drawn = if (w == null) shown else clipToWindow(shown, w, pad = true)!!
        // tipT as PriceChart passes it: the whole series' own newest candle.
        val pair = compareLines(drawn, bench(c), shown, c.range, shown.points.last().t, zoomedIn = w != null)!!
        val inside = if (w == null) drawn.points.indices else insideIndices(drawn, w)
        return inside.associate { i ->
            drawn.points[i].t to Triple(pair.own[i], pair.other[i], pair.own[i] - pair.other[i])
        }
    }

    private fun window(c: Case, fromIdx: Int, span: Int): ChartWindow =
        ChartWindow((t0 + fromIdx * c.stepSec) * 1000L, (t0 + (fromIdx + span) * c.stepSec) * 1000L)

    @Test fun `every moment reads the same through every window - 1D, 5D and 1Y`() {
        for (c in cases) {
            val span = c.n / 2
            val unzoomed = readings(c, null)
            // The window dragged across the series a step at a time, as a finger would.
            for (from in 2 until c.n - span - 2 step 3) {
                val seen = readings(c, window(c, from, span))
                assertTrue("${c.range}: window at $from shows nothing", seen.isNotEmpty())
                for ((t, r) in seen) {
                    val ref = unzoomed.getValue(t)
                    assertEquals("${c.range} stock % at $t, window at $from", ref.first, r.first, 1e-9)
                    assertEquals("${c.range} SPY % at $t, window at $from", ref.second, r.second, 1e-9)
                    assertEquals("${c.range} gap at $t, window at $from", ref.third, r.third, 1e-9)
                }
            }
        }
    }

    @Test fun `1D measures both lines from their own previous close, zoomed or not`() {
        val c = cases.first { it.range == ChartRange.D1 }
        assertNull(compareAnchorFor(stock(c), ChartRange.D1))
        val t = t0 + 40 * c.stepSec
        val r = readings(c, window(c, 30, 20)).getValue(t)
        val stockClose = stock(c).points.first { it.t == t }.close
        val spyClose = bench(c).points.first { it.t == t }.close
        assertEquals((stockClose - 9.6) / 9.6 * 100.0, r.first, 1e-9)
        assertEquals((spyClose - 659.5) / 659.5 * 100.0, r.second, 1e-9)
    }

    @Test fun `5D is anchored at the range's first candle, not the window's edge`() {
        val c = cases.first { it.range == ChartRange.D5 }
        assertEquals(t0, compareAnchorFor(stock(c), ChartRange.D5))
    }
}
