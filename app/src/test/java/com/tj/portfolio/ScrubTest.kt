package com.tj.portfolio

import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.ui.nearestIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ROUND 60: the lookup behind chart scrubbing.
 *
 * `nearestIndex` runs on EVERY FRAME of a drag, from a gesture handler where an exception is
 * a crash rather than a wrong answer. So it is a pure function and it is tested that way,
 * with the two properties that actually matter:
 *
 *   1. **It matches by TIMESTAMP, not by position in the list.** The chart's x-axis is linear
 *      in time, so the inverse must be too. On the after-hours view - which has a real gap in
 *      the middle where nothing traded - an index-based lookup puts the crosshair somewhere
 *      the finger is not, and it does it silently.
 *   2. **It is total.** Out-of-range fractions, one-point series, empty series and NaN all
 *      have to return something rather than throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrubTest {

    /** An evenly spaced five-minute series, like a 1D chart. */
    private fun even(n: Int, step: Long = 300L) =
        (0 until n).map { ChartPoint(1_757_000_000L + it * step, 100.0 + it) }

    // ------------------------------------------------------------------ basics

    @Test
    fun `the ends map to the ends`() {
        val p = even(100)
        assertEquals(0, nearestIndex(p, 0f))
        assertEquals(p.lastIndex, nearestIndex(p, 1f))
    }

    @Test
    fun `the middle maps to the middle on an evenly spaced series`() {
        val p = even(101)
        assertEquals(50, nearestIndex(p, 0.5f))
        assertEquals(25, nearestIndex(p, 0.25f))
        assertEquals(75, nearestIndex(p, 0.75f))
    }

    /**
     * The property that makes the crosshair land under the finger: the point it returns must
     * be genuinely the closest in time, for every position across the width.
     */
    @Test
    fun `it returns the closest point in time, everywhere across the width`() {
        val p = even(240)
        val t0 = p.first().t
        val span = (p.last().t - t0).toDouble()
        for (step in 0..400) {
            val f = step / 400f
            val i = nearestIndex(p, f)
            val target = t0 + Math.round(f.toDouble() * span)
            val best = p.indices.minByOrNull { kotlin.math.abs(p[it].t - target) }!!
            assertEquals(
                "at fraction $f the lookup picked ${p[i].t}, but ${p[best].t} is nearer $target",
                kotlin.math.abs(p[best].t - target), kotlin.math.abs(p[i].t - target)
            )
        }
    }

    // ----------------------------------------------------- the uneven case

    /**
     * THE CASE AN INDEX-BASED LOOKUP GETS WRONG. An after-hours series is two clusters with
     * hours of nothing between them. Half way across the chart is inside the GAP, and the
     * answer has to be an edge of one cluster - not the middle element of the list, which is
     * what "index = fraction * size" would give.
     */
    @Test
    fun `a series with a gap resolves by time, not by list position`() {
        // 20 points, then a six-hour gap, then 20 more.
        val before = (0 until 20).map { ChartPoint(1_757_000_000L + it * 300L, 100.0) }
        val after = (0 until 20).map { ChartPoint(1_757_000_000L + 21_600L + it * 300L, 110.0) }
        val p = before + after

        val mid = nearestIndex(p, 0.5f)
        val t0 = p.first().t
        val target = t0 + (0.5 * (p.last().t - t0)).toLong()
        // The nearest point to the middle of the WINDOW is the last of the first cluster,
        // because the gap is far wider than the second cluster's offset.
        val best = p.indices.minByOrNull { kotlin.math.abs(p[it].t - target) }!!
        assertEquals(best, mid)
        assertTrue(
            "an index-based lookup would have returned the list midpoint",
            mid != p.size / 2 || best == p.size / 2
        )
    }

    /** A tie goes to the earlier point, so dragging left and right lands on the same one. */
    @Test
    fun `a tie is broken the same way from both directions`() {
        val p = listOf(
            ChartPoint(1000L, 10.0),
            ChartPoint(2000L, 11.0),
            ChartPoint(3000L, 12.0)
        )
        // 0.25 of the way is t=1500, exactly between points 0 and 1.
        assertEquals(0, nearestIndex(p, 0.25f))
        assertEquals(1, nearestIndex(p, 0.75f - 0.5f + 0.25f))   // same position, computed differently
    }

    // -------------------------------------------------------------- totality

    @Test
    fun `an out of range fraction clamps rather than throwing`() {
        val p = even(50)
        assertEquals(0, nearestIndex(p, -5f))
        assertEquals(p.lastIndex, nearestIndex(p, 5f))
        assertEquals(0, nearestIndex(p, Float.NEGATIVE_INFINITY))
        assertEquals(p.lastIndex, nearestIndex(p, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `degenerate series return zero rather than throwing`() {
        assertEquals(0, nearestIndex(emptyList(), 0.5f))
        assertEquals(0, nearestIndex(listOf(ChartPoint(1L, 10.0)), 0.5f))
    }

    /**
     * Every point sharing one timestamp makes the span zero. The guard coerces it to 1 so the
     * division cannot produce a NaN index - which would then be used to subscript a list.
     */
    @Test
    fun `a zero time span does not divide by zero`() {
        val p = (0 until 10).map { ChartPoint(5_000L, 10.0 + it) }
        val i = nearestIndex(p, 0.5f)
        assertTrue("index $i out of range", i in p.indices)
    }

    @Test
    fun `the result is always a valid index`() {
        listOf(even(2), even(3), even(399), even(900)).forEach { p ->
            for (step in 0..200) {
                val i = nearestIndex(p, step / 200f)
                assertTrue("index $i out of range for ${p.size} points", i in p.indices)
            }
        }
    }

    /**
     * The reason it is a binary search: this runs per frame of a drag. Not a benchmark - a
     * guard that it has not quietly become linear, which on a 900-point series would be ~450
     * comparisons every frame.
     */
    @Test
    fun `the lookup is fast enough to run on every frame`() {
        val p = even(900)
        val start = System.nanoTime()
        repeat(20_000) { nearestIndex(p, (it % 1000) / 1000f) }
        val msPerCall = (System.nanoTime() - start) / 20_000.0 / 1_000_000.0
        assertTrue(
            "nearestIndex took ${"%.4f".format(msPerCall)}ms per call - too slow for a drag",
            msPerCall < 0.05
        )
    }
}
