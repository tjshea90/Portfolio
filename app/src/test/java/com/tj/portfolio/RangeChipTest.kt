package com.tj.portfolio

import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.ui.rangeFigure
import com.tj.portfolio.ui.rangePct
import com.tj.portfolio.ui.withLiveEdge
import com.tj.portfolio.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FIGURE ON A RANGE CHIP, AS A PURE FUNCTION (Round 62).
 *
 * Two things are being defended here and they are not the same thing:
 *
 *  1. **The chip agrees with the chart.** `rangePct` exists only because doing this the
 *     obvious way - `withLiveEdge(series, ...).changePct` - rebuilds a 400-point list to
 *     replace one element, once per chip per quote tick. That makes it a DUPLICATE of an
 *     existing definition, and this project's own history says duplicated definitions drift.
 *     So the equivalence is asserted against the real one across every range and every live
 *     edge case, rather than being asserted in a comment.
 *  2. **It is total.** This feeds a button on a screen. A missing series, a series with one
 *     point, a zero or non-finite baseline and a non-finite result all have to come back as
 *     "no figure" - never as "NaN%" printed on a chip, and never as an exception.
 */
class RangeChipTest {

    private val HOUR = 3_600_000L

    private fun series(
        range: ChartRange = ChartRange.M1,
        closes: List<Double> = listOf(100.0, 101.0, 103.5),
        baseline: Double = 0.0
    ) = ChartSeries(
        symbol = "FIVE",
        range = range,
        points = closes.mapIndexed { i, c -> ChartPoint(1_757_000_000L + i * 300L, c) },
        baseline = baseline,
        fetched = System.currentTimeMillis()
    )

    // ------------------------------------------------------------------ totality

    @Test
    fun `a range the app holds nothing for has no figure`() {
        assertNull(rangePct(null, 0.0, false))
    }

    @Test
    fun `a one-point series is not a window and has no figure`() {
        assertNull(rangePct(series(closes = listOf(100.0)), 0.0, false))
        assertNull(rangePct(series(closes = emptyList()), 0.0, false))
    }

    @Test
    fun `a series with nothing to measure from has no figure`() {
        // Cannot happen from the parser, which drops non-positive closes - but a corrupt
        // cache row decodes to whatever it decodes to, and this runs on a button.
        assertNull(rangePct(series(closes = listOf(0.0, 0.0)), 0.0, false))
        assertNull(rangePct(series(closes = listOf(-2.0, -1.0)), 0.0, false))
    }

    @Test
    fun `non-finite numbers never reach the chip`() {
        // A corrupt baseline is not a corrupt window: `ChartSeries.from` falls back to the
        // first point when the baseline is not a usable number, and the chip follows the
        // chart there rather than inventing a second rule.
        val nanBase = series(closes = listOf(100.0, 110.0), baseline = Double.NaN)
        assertEquals(
            withLiveEdge(nanBase, 0.0, false)!!.changePct, rangePct(nanBase, 0.0, false)!!, 1e-12
        )
        val infEnd = series(closes = listOf(100.0, Double.POSITIVE_INFINITY))
        assertNull(rangePct(infEnd, 0.0, false))
        val tiny = series(closes = listOf(Double.MIN_VALUE, 1.0e308))
        val v = rangePct(tiny, 0.0, false)
        assertTrue("an overflowing ratio must be dropped, not printed", v == null || v.isFinite())
    }

    @Test
    fun `a live price is only believed when it is a real price`() {
        val s = series(ChartRange.D1, listOf(100.0, 101.0), baseline = 100.0)
        assertEquals(1.0, rangePct(s, 0.0, true)!!, 1e-9)
        assertEquals(1.0, rangePct(s, -5.0, true)!!, 1e-9)
        assertEquals(1.0, rangePct(s, Double.NaN, true)!!, 1e-9)
    }

    /**
     * ...and the chart refuses it the same way. This is the one place the two functions used
     * to differ: `livePrice <= 0.0` is FALSE for NaN, so a NaN price was painted as the last
     * point of the line while the chip ignored it. Round 62 made both tests `> 0.0`.
     */
    @Test
    fun `a nonsense live price is refused by the chart and the chip alike`() {
        val s = series(ChartRange.D1, listOf(100.0, 101.0), baseline = 100.0)
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.0, -3.0).forEach { bad ->
            assertEquals("live=$bad", 101.0, withLiveEdge(s, bad, true)!!.points.last().close, 1e-9)
            assertEquals("live=$bad", 1.0, rangePct(s, bad, true)!!, 1e-9)
        }
    }

    // ------------------------------------------------------------------ the maths

    @Test
    fun `a long window is measured from its first point to its last`() {
        val s = series(ChartRange.Y1, listOf(100.0, 90.0, 120.0))
        assertEquals(20.0, rangePct(s, 0.0, false)!!, 1e-9)
    }

    @Test
    fun `a day window is measured from the previous close, not the first candle`() {
        // The baseline is what makes "since yesterday's close" mean what it says.
        val s = series(ChartRange.D1, listOf(102.0, 103.0), baseline = 100.0)
        assertEquals(3.0, rangePct(s, 0.0, false)!!, 1e-9)
    }

    @Test
    fun `the live edge moves an intraday figure and never a long one`() {
        val intraday = series(ChartRange.D1, listOf(100.0, 101.0), baseline = 100.0)
        assertEquals(5.0, rangePct(intraday, 105.0, true)!!, 1e-9)
        assertEquals(1.0, rangePct(intraday, 105.0, false)!!, 1e-9)

        // A five-year monthly line's last point is a month that has not finished. Moving it
        // to today's price would redraw history - see the note on withLiveEdge.
        val longRange = series(ChartRange.Y5, listOf(100.0, 101.0))
        assertEquals(1.0, rangePct(longRange, 105.0, true)!!, 1e-9)
    }

    @Test
    fun `a flat window reads as plus zero, never as plus minus zero`() {
        val flat = series(ChartRange.M1, listOf(100.0, 100.0))
        val pct = rangePct(flat, 0.0, false)!!
        assertEquals("+0.00%", Fmt.pctSigned(pct))
        // The bug this guards: -0.0 is >= 0, so the sign is chosen as "+" and then the
        // formatter prints its own "-".
        assertEquals("+-0.00%", Fmt.pctSigned(-0.0))
    }

    // ------------------------------------- the chip and the chart cannot disagree

    /**
     * THE TEST THIS FILE EXISTS FOR.
     *
     * The chip for the selected range sits directly above a readout of the same window. If
     * these two ever differ, one of them is lying, and there is no way to tell which from
     * the screen.
     */
    @Test
    fun `the chip figure is exactly what the chart readout would show`() {
        val cases = listOf(
            Triple(ChartRange.D1, 0.0, false),
            Triple(ChartRange.D1, 107.25, true),
            Triple(ChartRange.D1, 107.25, false),
            Triple(ChartRange.OVERNIGHT, 98.4, true),
            Triple(ChartRange.D5, 107.25, true),
            Triple(ChartRange.M1, 107.25, true),
            Triple(ChartRange.Y5, 107.25, true),
            Triple(ChartRange.MAX, 0.0, false)
        )
        cases.forEach { (range, live, edge) ->
            listOf(0.0, 99.0).forEach { baseline ->
                val s = series(range, listOf(100.0, 104.0, 103.0), baseline)
                val drawn = withLiveEdge(s, live, edge)!!
                assertEquals(
                    "$range live=$live edge=$edge baseline=$baseline",
                    drawn.changePct,
                    rangePct(s, live, edge)!!,
                    1e-12
                )
            }
        }
    }

    @Test
    fun `an empty series is dropped by both, so neither prints anything`() {
        val one = series(ChartRange.D1, listOf(100.0))
        // The chart draws nothing for it...
        assertTrue(withLiveEdge(one, 105.0, true)!!.isEmpty)
        // ...and the chip says nothing about it.
        assertNull(rangePct(one, 105.0, true))
    }

    // ------------------------------------------- what a chip is not allowed to claim

    /**
     * A cached row can be any age at all - the TTL decides when to re-FETCH, not how long a
     * row lives - so without this a chip could label last week's month "1M".
     */
    @Test
    fun `a figure older than a day is not printed`() {
        val now = 1_800_000_000_000L
        val s = series(ChartRange.M1, listOf(100.0, 110.0)).copy(fetched = now - 23 * HOUR)
        assertEquals(10.0, rangePct(s, 0.0, false, now)!!, 1e-9)

        val old = s.copy(fetched = now - 25 * HOUR)
        assertNull(rangePct(old, 0.0, false, now))
    }

    /** An unstamped row is of unknown age, which is not the same as a fresh one. */
    @Test
    fun `a row with no fetch stamp is treated as too old, not as new`() {
        val now = 1_800_000_000_000L
        val s = series(ChartRange.M1, listOf(100.0, 110.0)).copy(fetched = 0L)
        assertNull(rangePct(s, 0.0, false, now))
    }

    /**
     * The chart says "this is the whole history on record, which is shorter than 5Y" in
     * words. A chip has nowhere to put that sentence, so it makes no claim at all.
     */
    @Test
    fun `a window shorter than its label makes no claim`() {
        val s = series(ChartRange.Y5, listOf(100.0, 140.0)).copy(truncated = true)
        assertNull(rangePct(s, 0.0, false))
        assertEquals(40.0, rangePct(s.copy(truncated = false), 0.0, false)!!, 1e-9)
    }

    // ------------------------------------------------------------------ the label

    @Test
    fun `a figure beats a spinner, a spinner beats a blank`() {
        assertEquals("+1.50%", rangeFigure(1.5, loading = false))
        assertEquals("+1.50%", rangeFigure(1.5, loading = true))
        assertEquals("...", rangeFigure(null, loading = true))
    }

    /**
     * The blank is a non-breaking space, NOT an empty string: an empty string collapses the
     * line and leaves that chip a different height from the ones next to it.
     */
    @Test
    fun `a range with nothing held still reserves its line`() {
        assertEquals("\u00A0", rangeFigure(null, loading = false))
    }
}
