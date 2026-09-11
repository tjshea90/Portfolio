package com.tj.portfolio

import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.ui.comparePercents
import com.tj.portfolio.ui.primaryPercents
import com.tj.portfolio.ui.valueAtOrBefore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BENCHMARK OVERLAY'S ARITHMETIC (Round 63).
 *
 * The overlay makes exactly one claim - "this is how the stock did against the market over
 * this window" - and there are only a few ways to get that wrong, all of them silent:
 *
 *  1. measuring the two lines from different moments;
 *  2. rebasing the benchmark to its own first point when its history is longer than the
 *     stock's, which on a five-year chart of a recent listing would show SPY up several
 *     hundred percent against a stock that had never been measured against it;
 *  3. reading a benchmark value from AFTER the point being drawn;
 *  4. drawing a straight line across a gap the benchmark has no data for.
 *
 * Every one of those has a test here.
 */
class CompareChartTest {

    private val t0 = 1_757_000_000L

    private fun series(
        symbol: String,
        range: ChartRange,
        closes: List<Double>,
        stepSecs: Long = 300L,
        startAt: Long = t0,
        baseline: Double = 0.0
    ) = ChartSeries(
        symbol = symbol, range = range,
        points = closes.mapIndexed { i, c -> ChartPoint(startAt + i * stepSecs, c) },
        baseline = baseline, fetched = System.currentTimeMillis()
    )

    // ---------------------------------------------------------- valueAtOrBefore

    @Test fun `the lookup returns the last value at or before the moment asked for`() {
        val pts = listOf(ChartPoint(100, 1.0), ChartPoint(200, 2.0), ChartPoint(300, 3.0))
        assertEquals(1.0, valueAtOrBefore(pts, 100)!!, 1e-9)
        assertEquals(1.0, valueAtOrBefore(pts, 150)!!, 1e-9)
        assertEquals(2.0, valueAtOrBefore(pts, 200)!!, 1e-9)
        assertEquals(3.0, valueAtOrBefore(pts, 999)!!, 1e-9)
    }

    @Test fun `the lookup never reads from the future`() {
        val pts = listOf(ChartPoint(100, 1.0), ChartPoint(200, 2.0))
        // Before the series begins there is nothing to read, and returning the first point
        // would put a later market move under an earlier price.
        assertNull(valueAtOrBefore(pts, 99))
        assertNull(valueAtOrBefore(emptyList(), 100))
    }

    // --------------------------------------------------- intraday: previous close

    @Test fun `on a one-day chart both lines are measured from their own previous close`() {
        // The stock closed at 100 and is at 110: +10%. The benchmark closed at 400 and is at
        // 404: +1%. Those are the two numbers the readout and the range chips already show,
        // so the overlay must reproduce them exactly rather than inventing its own baseline.
        val stock = series("TEST", ChartRange.D1, listOf(105.0, 110.0), baseline = 100.0)
        val bench = series("SPY", ChartRange.D1, listOf(402.0, 404.0), baseline = 400.0)
        val other = comparePercents(stock, bench)!!
        assertEquals(0.5, other[0], 1e-9)
        assertEquals(1.0, other[1], 1e-9)

        val own = primaryPercents(stock)!!
        assertEquals(5.0, own[0], 1e-9)
        assertEquals(10.0, own[1], 1e-9)
    }

    // ------------------------------------------- longer windows: rebase to the start

    @Test fun `a longer benchmark history is rebased to the start of the stock's window`() {
        // THE BUG THIS EXISTS TO PREVENT. The benchmark ran from 100 to 400 over its own
        // history, but the stock's window opens where the benchmark stood at 200. Measured
        // from the benchmark's own first point the overlay would read +100%; measured
        // correctly it reads +100% only relative to where the window began - which here is
        // 200 -> 400, i.e. +100%... so the numbers are chosen to differ:
        //   benchmark: 100, 200, 400   stock's window starts at the SECOND benchmark point.
        // From its own first point the last value is +300%. Rebased, it is +100%.
        val bench = series("SPY", ChartRange.Y5, listOf(100.0, 200.0, 400.0), stepSecs = 1000L)
        val stock = series(
            "TEST", ChartRange.Y5, listOf(50.0, 60.0),
            stepSecs = 1000L, startAt = t0 + 1000L
        )
        val other = comparePercents(stock, bench)!!
        assertEquals("rebased to the window start", 0.0, other[0], 1e-9)
        assertEquals("rebased to the window start", 100.0, other[1], 1e-9)
    }

    @Test fun `the stock's own percentages use the same baseline the readout uses`() {
        val stock = series("TEST", ChartRange.Y1, listOf(50.0, 75.0, 100.0), stepSecs = 1000L)
        val own = primaryPercents(stock)!!
        assertEquals(0.0, own[0], 1e-9)
        assertEquals(50.0, own[1], 1e-9)
        assertEquals(100.0, own[2], 1e-9)
        // And it agrees with the series' own headline figure, which is what the chart prints.
        assertEquals(stock.changePct, own.last(), 1e-9)
    }

    // ------------------------------------------------------------------- gaps

    @Test fun `points the benchmark has no reading for become NaN, not zero`() {
        // The stock starts trading before the benchmark series does - the benchmark simply
        // has nothing for the first two points. A zero there would draw a flat line through
        // a stretch that was never measured.
        val stock = series("TEST", ChartRange.M1, listOf(10.0, 11.0, 12.0, 13.0), stepSecs = 1000L)
        val bench = series(
            "SPY", ChartRange.M1, listOf(400.0, 404.0),
            stepSecs = 1000L, startAt = t0 + 2000L
        )
        val other = comparePercents(stock, bench)!!
        assertTrue("a missing reading must be NaN", other[0].isNaN())
        assertTrue("a missing reading must be NaN", other[1].isNaN())
        assertEquals(0.0, other[2], 1e-9)
        assertEquals(1.0, other[3], 1e-9)
    }

    @Test fun `a benchmark with a coarser grid holds its last value forward`() {
        // Daily benchmark closes against a five-minute stock line: every stock point inside a
        // benchmark day reads that day's close, and none of them reads the NEXT day's.
        val stock = series("TEST", ChartRange.D5, (0..9).map { 100.0 + it }, stepSecs = 100L)
        val bench = series("SPY", ChartRange.D5, listOf(200.0, 210.0), stepSecs = 500L)
        val other = comparePercents(stock, bench)!!
        for (i in 0..4) assertEquals("point $i", 0.0, other[i], 1e-9)
        for (i in 5..9) assertEquals("point $i", 5.0, other[i], 1e-9)
    }

    // ------------------------------------------------------- refusing to guess

    @Test fun `no overlay at all when there is nothing honest to draw`() {
        val stock = series("TEST", ChartRange.Y1, listOf(10.0, 11.0), stepSecs = 1000L)
        assertNull("no benchmark", comparePercents(stock, null))
        assertNull("no stock", comparePercents(null, stock))
        // A one-point benchmark is not a line.
        assertNull(comparePercents(stock, series("SPY", ChartRange.Y1, listOf(400.0))))
        // A benchmark that begins after the stock's window ENDS overlaps nowhere.
        assertNull(
            comparePercents(
                stock,
                series("SPY", ChartRange.Y1, listOf(400.0, 404.0), stepSecs = 1000L, startAt = t0 + 90_000L)
            )
        )
    }

    @Test fun `a zero or absent baseline refuses rather than dividing by nothing`() {
        val stock = series("TEST", ChartRange.D1, listOf(10.0, 11.0), baseline = 100.0)
        // An intraday benchmark with no previous close and no usable first point.
        val bad = ChartSeries(
            symbol = "SPY", range = ChartRange.D1,
            points = listOf(ChartPoint(t0, 0.0), ChartPoint(t0 + 300, 0.0)),
            baseline = 0.0, fetched = System.currentTimeMillis()
        )
        assertNull(comparePercents(stock, bad))
        assertNull(primaryPercents(ChartSeries(symbol = "X", range = ChartRange.D1)))
    }

    @Test fun `an identical benchmark draws a flat line at zero, not nothing`() {
        // Comparing something with itself is degenerate but not an error, and the overlay
        // must still produce a line - it is what the SPY-vs-SPY case would look like if the
        // toggle were ever offered there.
        val s = series("SPY", ChartRange.Y1, listOf(100.0, 110.0), stepSecs = 1000L)
        val other = comparePercents(s, s)!!
        assertNotNull(other)
        assertEquals(0.0, other[0], 1e-9)
        assertEquals(10.0, other[1], 1e-9)
    }

    // -------------------------------------------------- primaryPercents' fromValue override
    //
    // Added for the frozen-during-a-gesture rebase anchor (round 67) - see the note in
    // `PriceChart.kt` where `cmp` is built. `baseIndex` names a position in the series being
    // measured, which stops meaning the same candle the instant that series is re-sliced to a
    // different window; `fromValue` is the anchor PRICE itself, looked up once by timestamp
    // from a series that is not being re-sliced, so it keeps meaning the same candle no matter
    // how the view around it changes afterward.

    @Test fun `fromValue overrides baseIndex and fromPoint entirely`() {
        val s = series("TEST", ChartRange.Y1, listOf(100.0, 150.0, 200.0), stepSecs = 86_400L)
        // Without fromValue: measured from index 1 (150.0) per the ordinary rule.
        val byIndex = primaryPercents(s, baseIndex = 1, fromPoint = true)!!
        assertEquals(-33.33, byIndex[0], 0.01)
        assertEquals(0.0, byIndex[1], 1e-9)
        assertEquals(33.33, byIndex[2], 0.01)

        // With fromValue given, baseIndex/fromPoint are ignored entirely - measured from an
        // anchor that appears nowhere in this series' own points, exactly the shape of a
        // benchmark's own close price fed in as the stock's frozen anchor.
        val byValue = primaryPercents(s, baseIndex = 1, fromPoint = true, fromValue = 50.0)!!
        assertEquals(100.0, byValue[0], 1e-9)
        assertEquals(200.0, byValue[1], 1e-9)
        assertEquals(300.0, byValue[2], 1e-9)
    }

    @Test fun `a non-finite or non-positive fromValue is refused, same as an unusable baseline`() {
        val s = series("TEST", ChartRange.Y1, listOf(100.0, 150.0), stepSecs = 86_400L)
        assertNull(primaryPercents(s, fromValue = 0.0))
        assertNull(primaryPercents(s, fromValue = -5.0))
        assertNull(primaryPercents(s, fromValue = Double.NaN))
    }
}
