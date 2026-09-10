package com.tj.portfolio

import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.data.approxSpanMs
import com.tj.portfolio.data.candleMs
import com.tj.portfolio.ui.clipToWindow
import com.tj.portfolio.ui.insideIndices
import com.tj.portfolio.ui.nearestIndex
import com.tj.portfolio.ui.spanLabel
import com.tj.portfolio.ui.windowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CONTINUOUS ZOOM, AS ARITHMETIC (Round 64).
 *
 * TJ: *"make the pinch to zoom smooth instead of chopping between intervals."*
 *
 * The whole feature rests on [ChartWindow] being total and exact: it runs inside a pointer
 * handler, dozens of times a second, on numbers that come from fingers. An exception there is
 * a crash on a screen the user is touching, and an off-by-one is a chart that does not line up
 * with the hand that placed it. None of that can be seen by reading the code, and none of it
 * needs a device to check - it is all pure functions over longs.
 */
class ChartWindowTest {

    private val DAY = 86_400_000L
    private val base = ChartWindow(1_000_000_000_000L, 1_000_000_000_000L + 365L * DAY)
    private val bounds = ChartWindow(base.startMs - 3650L * DAY, base.endMs)

    // ------------------------------------------------------------------ the model

    @Test fun `a window reports where a moment falls across it`() {
        assertEquals(0f, base.fractionOf(base.startMs), 1e-6f)
        assertEquals(1f, base.fractionOf(base.endMs), 1e-6f)
        assertEquals(0.5f, base.fractionOf(base.startMs + base.spanMs / 2), 1e-4f)
    }

    @Test fun `fractionOf and momentAt are inverses`() {
        for (f in listOf(0f, 0.13f, 0.5f, 0.87f, 1f)) {
            assertEquals(f, base.fractionOf(base.momentAt(f)), 1e-4f)
        }
    }

    @Test fun `momentAt clamps rather than extrapolating`() {
        assertEquals(base.startMs, base.momentAt(-3f))
        assertEquals(base.endMs, base.momentAt(4f))
    }

    @Test fun `a degenerate window still has a positive span`() {
        assertTrue(ChartWindow(5L, 5L).spanMs >= 1L)
        assertTrue(ChartWindow(9L, 4L).spanMs >= 1L)
    }

    // ------------------------------------------------------------------- zooming

    @Test fun `spreading the fingers shows less time`() {
        val z = ChartWindow.zoomed(base, factor = 2f, focus = 0.5f, bounds = bounds)
        assertEquals(
            "a spread should halve the span",
            (base.spanMs / 2).toDouble(), z.spanMs.toDouble(), 2.0
        )
    }

    @Test fun `pinching in shows more time`() {
        val z = ChartWindow.zoomed(base, factor = 0.5f, focus = 0.5f, bounds = bounds)
        assertEquals(
            "a pinch should double the span",
            (base.spanMs * 2).toDouble(), z.spanMs.toDouble(), 2.0
        )
    }

    @Test fun `the moment under the fingers stays put`() {
        // The one property that makes a zoom feel attached to the hand.
        for (focus in listOf(0.1f, 0.35f, 0.5f, 0.8f)) {
            val anchor = base.momentAt(focus)
            val z = ChartWindow.zoomed(base, 1.7f, focus, bounds)
            assertEquals(
                "the moment at focus=$focus moved",
                anchor.toDouble(), z.momentAt(focus).toDouble(), (z.spanMs * 0.01)
            )
        }
    }

    @Test fun `many small zooms compose into one big one`() {
        // THE POINT OF THE WHOLE ROUND. Sixteen frames of a steady spread must land where one
        // large step would, or the gesture drifts away from the fingers as it goes.
        var w = base
        repeat(16) { w = ChartWindow.zoomed(w, 1.1f, 0.5f, bounds) }
        val expected = base.spanMs / Math.pow(1.1, 16.0)
        assertEquals(expected, w.spanMs.toDouble(), expected * 0.02)
    }

    @Test fun `every frame of a slow spread changes the window`() {
        // "Smooth instead of chopping between intervals" means literally this: a gesture that
        // moves a little must change the picture a little, every time, rather than nine times
        // out of ten changing nothing and the tenth time jumping a whole range.
        var w = base
        var changes = 0
        repeat(20) {
            val next = ChartWindow.zoomed(w, 1.03f, 0.5f, bounds)
            if (next.spanMs != w.spanMs) changes++
            w = next
        }
        assertEquals("a 3%-per-frame spread should move the window every frame", 20, changes)
    }

    @Test fun `a zoom cannot go below the minimum span`() {
        var w = base
        repeat(200) { w = ChartWindow.zoomed(w, 2f, 0.5f, bounds) }
        assertEquals(ChartWindow.MIN_SPAN_MS, w.spanMs)
    }

    @Test fun `a zoom cannot go wider than the data`() {
        var w = base
        repeat(200) { w = ChartWindow.zoomed(w, 0.5f, 0.5f, bounds) }
        assertEquals(bounds.spanMs, w.spanMs)
        assertEquals(bounds.startMs, w.startMs)
        assertEquals(bounds.endMs, w.endMs)
    }

    @Test fun `a zoom at the right edge slides rather than clipping`() {
        // Zooming out with the fingers at the very end of the series: the span asked for must
        // be honoured, by moving the start back rather than by silently shortening it.
        val atEnd = ChartWindow(base.endMs - 10L * DAY, base.endMs)
        val z = ChartWindow.zoomed(atEnd, 0.25f, 1f, bounds)
        assertEquals((40L * DAY).toDouble(), z.spanMs.toDouble(), (DAY / 10).toDouble())
        assertEquals("the window ran past the newest data", bounds.endMs, z.endMs)
    }

    @Test fun `a nonsense factor is ignored rather than thrown`() {
        assertEquals(base, ChartWindow.zoomed(base, Float.NaN, 0.5f, bounds))
        assertEquals(base, ChartWindow.zoomed(base, 0f, 0.5f, bounds))
        assertEquals(base, ChartWindow.zoomed(base, -2f, 0.5f, bounds))
        assertEquals(base, ChartWindow.zoomed(base, Float.POSITIVE_INFINITY, 0.5f, bounds))
    }

    @Test fun `an out of range focus is clamped rather than thrown`() {
        val a = ChartWindow.zoomed(base, 2f, -9f, bounds)
        val b = ChartWindow.zoomed(base, 2f, 9f, bounds)
        assertTrue(a.startMs >= bounds.startMs && a.endMs <= bounds.endMs)
        assertTrue(b.startMs >= bounds.startMs && b.endMs <= bounds.endMs)
    }

    // -------------------------------------------------------------------- panning

    @Test fun `a pan moves the window without resizing it`() {
        val zoomed = ChartWindow.zoomed(base, 4f, 0.5f, bounds)
        val moved = ChartWindow.panned(zoomed, 0.25f, bounds)
        assertEquals("a pan changed the span", zoomed.spanMs, moved.spanMs)
        assertEquals(
            (zoomed.startMs + zoomed.spanMs / 4).toDouble(), moved.startMs.toDouble(),
            zoomed.spanMs * 0.01
        )
    }

    @Test fun `a pan stops at both ends of the data`() {
        val zoomed = ChartWindow.zoomed(base, 4f, 0.5f, bounds)
        var w = zoomed
        repeat(50) { w = ChartWindow.panned(w, 0.5f, bounds) }
        assertEquals("panning forward ran past the newest data", bounds.endMs, w.endMs)
        repeat(500) { w = ChartWindow.panned(w, -0.5f, bounds) }
        assertEquals("panning back ran past the oldest data", bounds.startMs, w.startMs)
        assertEquals("the span changed while panning", zoomed.spanMs, w.spanMs)
    }

    @Test fun `panning an unzoomed chart does nothing`() {
        val whole = ChartWindow(bounds.startMs, bounds.endMs)
        assertEquals(whole, ChartWindow.panned(whole, 0.4f, bounds))
    }

    @Test fun `a nonsense pan is ignored`() {
        assertEquals(base, ChartWindow.panned(base, Float.NaN, bounds))
        assertEquals(base, ChartWindow.panned(base, 0f, bounds))
    }

    // --------------------------------------------------------------- is it zoomed

    @Test fun `a window that covers everything is not a zoom`() {
        assertTrue(ChartWindow.isWhole(ChartWindow(bounds.startMs, bounds.endMs), bounds))
        assertTrue("null means nothing has been pinched", ChartWindow.isWhole(null, bounds))
        assertTrue("no bounds means nothing to compare", ChartWindow.isWhole(base, null))
    }

    @Test fun `a window a fraction inside the data is not a zoom either`() {
        val nearly = ChartWindow(bounds.startMs + 1000L, bounds.endMs - 1000L)
        assertTrue(ChartWindow.isWhole(nearly, bounds))
    }

    @Test fun `a genuinely narrower window is a zoom`() {
        assertTrue(!ChartWindow.isWhole(base, bounds))
    }

    // ------------------------------------------------------- which series to fetch

    @Test fun `the range follows the window's lookback, not its span`() {
        // THE TRAP THIS CLOSES. Every range the provider serves ENDS AT NOW, so a three-day
        // window two years back cannot be drawn from the 5D series - it does not overlap it.
        val twoYearsBack = 730L * DAY
        val r = ChartRange.rangeForLookback(twoYearsBack, ChartRange.M1)
        assertTrue(
            "a window two years back was given a range that cannot reach it: $r",
            r == ChartRange.Y5 || r == ChartRange.MAX
        )
    }

    @Test fun `a window at the right edge picks a range by its own size`() {
        assertEquals(ChartRange.D5, ChartRange.rangeForLookback(6L * DAY, null))
        assertEquals(ChartRange.M1, ChartRange.rangeForLookback(20L * DAY, null))
        assertEquals(ChartRange.Y1, ChartRange.rangeForLookback(300L * DAY, null))
    }

    @Test fun `the range sticks until the window has clearly left it`() {
        // Hysteresis: without it a window sitting on a boundary flips between two ranges as
        // the fingers wobble, and every flip is a chart fetch.
        val m1 = ChartRange.M1.let { it }
        val within = 20L * DAY
        assertEquals(m1, ChartRange.rangeForLookback(within, m1))
        // Far enough inside that a finer rung would show real detail.
        assertTrue(ChartRange.rangeForLookback(2L * DAY, m1) != m1)
        // Wider than it can draw.
        assertTrue(ChartRange.rangeForLookback(200L * DAY, m1) != m1)
    }

    @Test fun `the after-hours view is never swapped out from under the user`() {
        assertEquals(
            ChartRange.OVERNIGHT,
            ChartRange.rangeForLookback(400L * DAY, ChartRange.OVERNIGHT)
        )
    }

    @Test fun `a negative lookback is treated as none`() {
        assertNotNull(ChartRange.rangeForLookback(-5L, null))
    }

    // ----------------------------------------------------------------- the clipping

    private fun series(n: Int, stepSec: Long, range: ChartRange = ChartRange.M6) = ChartSeries(
        symbol = "T", range = range,
        points = (0 until n).map { ChartPoint(1_700_000_000L + it * stepSec, 100.0 + it) },
        baseline = 100.0, fetched = 1L
    )

    @Test fun `a window covering everything returns the same object`() {
        val s = series(50, 86_400L)
        val w = ChartWindow(s.startMs - 1000L, s.endMs + 1000L)
        assertSame("the ordinary case must not allocate", s, clipToWindow(s, w))
        assertSame(s, clipToWindow(s, null))
    }

    @Test fun `clipping keeps one point beyond each edge`() {
        val s = series(50, 86_400L)
        val w = ChartWindow(s.points[10].t * 1000L, s.points[20].t * 1000L)
        val c = clipToWindow(s, w)!!
        assertEquals("the line must reach the left edge", s.points[9].t, c.points.first().t)
        assertEquals("the line must reach the right edge", s.points[21].t, c.points.last().t)
    }

    @Test fun `a window between two candles still yields a line`() {
        val s = series(20, 7L * 86_400L)          // weekly candles
        val mid = s.points[5].t * 1000L + 86_400_000L   // a day after one of them
        val c = clipToWindow(s, ChartWindow(mid, mid + 3_600_000L))!!
        assertTrue("two points is the least that can be a line", c.points.size >= 2)
    }

    @Test fun `clipping a null or tiny series is safe`() {
        assertNull(clipToWindow(null, base))
        val one = series(1, 300L)
        assertSame(one, clipToWindow(one, base))
    }

    // ------------------------------------------------------------------ the bounds

    @Test fun `bounds span every series the app holds`() {
        val short = series(20, 300L, ChartRange.D1)
        val long = ChartSeries(
            symbol = "T", range = ChartRange.MAX,
            points = (0 until 60).map { ChartPoint(1_500_000_000L + it * 2_592_000L, 50.0) },
            baseline = 50.0, fetched = 1L
        )
        val b = windowBounds(short, listOf(short, long))!!
        assertEquals(long.startMs, b.startMs)
        assertEquals(short.endMs, b.endMs)
    }

    @Test fun `a pinch may reach back further than what has been fetched`() {
        // THE BUG THIS PROVES FIXED. Bounds built only from the series in hand meant that on a
        // stock opened for the first time - one 1D series cached and nothing else - a pinch
        // outward saturated at today, because the zoom clamps to the bounds' span. The window
        // is what CHOOSES the range to fetch, so bounding it by what has already been fetched
        // made "zoom out to the all-time chart" impossible.
        val day = series(80, 300L, ChartRange.D1)
        val b = windowBounds(day, listOf(day))!!
        assertTrue(
            "a pinch out from a 1D chart cannot reach past a day: span is ${b.spanMs}ms",
            b.spanMs > 3650L * DAY
        )
        assertEquals("the right-hand edge must stay at the newest data", day.endMs, b.endMs)
    }

    @Test fun `and stops reaching once the all-time series has actually arrived`() {
        // A company that listed in 2019 has no chart before 2019: once the MAX series is held,
        // its real first point is the limit, or a pinch-out shows blank years before the IPO.
        val day = series(80, 300L, ChartRange.D1)
        val max = ChartSeries(
            symbol = "T", range = ChartRange.MAX,
            points = (0 until 60).map { ChartPoint(1_600_000_000L + it * 2_592_000L, 50.0) },
            baseline = 50.0, fetched = 1L
        )
        val b = windowBounds(day, listOf(day, max))!!
        assertEquals(
            "the all-time series is loaded, so its own start is the limit",
            max.startMs, b.startMs
        )
    }

    @Test fun `the after-hours view is bounded by itself`() {
        // THE BUG THIS PROVES FIXED. The overnight series runs from the last regular close to
        // now, so it sits ENTIRELY AFTER the 1D series. Clamping its window against bounds
        // that exclude it slid the window into the regular session, where the overnight series
        // has no points at all - one pinch and the after-hours chart went blank, with "Reset
        // zoom" restoring it to the same empty stretch.
        val day = series(20, 300L, ChartRange.D1)
        val overnight = ChartSeries(
            symbol = "T", range = ChartRange.OVERNIGHT,
            points = (0 until 20).map {
                ChartPoint(day.points.last().t + 3_600L + it * 300L, 50.0)
            },
            baseline = 50.0, fetched = 1L
        )
        val b = windowBounds(overnight, listOf(day, overnight))!!
        assertEquals(overnight.startMs, b.startMs)
        assertEquals(overnight.endMs, b.endMs)
    }

    @Test fun `the after-hours series never widens another chart's bounds`() {
        // With the all-time series present there is no optimistic reach, so the bounds are
        // exactly the data - which is the state in which a stray series would show up.
        val day = series(20, 300L, ChartRange.D1)
        val max = ChartSeries(
            symbol = "T", range = ChartRange.MAX,
            points = (0 until 60).map { ChartPoint(1_500_000_000L + it * 2_592_000L, 50.0) },
            baseline = 50.0, fetched = 1L
        )
        val overnight = ChartSeries(
            symbol = "T", range = ChartRange.OVERNIGHT,
            points = (0 until 20).map { ChartPoint(1_000_000_000L + it * 300L, 50.0) },
            baseline = 50.0, fetched = 1L
        )
        val b = windowBounds(day, listOf(day, max, overnight))!!
        assertEquals(
            "the after-hours view is a filter, not a wider window",
            day.endMs, b.endMs
        )
        assertEquals(
            "an overnight series from another era leaked into the bounds",
            max.startMs, b.startMs
        )
    }

    @Test fun `bounds fall back to the drawn series and then to nothing`() {
        val s = series(20, 300L)
        val b = windowBounds(s, emptyList())!!
        assertEquals("the newest data is still the right-hand edge", s.endMs, b.endMs)
        assertTrue("the reach should still apply", b.startMs <= s.startMs)
        assertNull(windowBounds(null, emptyList()))
    }

    // ------------------------------------------------- re-anchoring when data moves

    @Test fun `a window wider than the data is pulled back inside it`() {
        val wide = ChartWindow(bounds.startMs - 5000L * DAY, bounds.endMs)
        val c = ChartWindow.clamped(wide, bounds, wasAtRightEdge = true)!!
        assertEquals(bounds.startMs, c.startMs)
        assertEquals(bounds.endMs, c.endMs)
    }

    @Test fun `a window at the newest data follows the data forward`() {
        val atEnd = ChartWindow(base.endMs - 5L * DAY, base.endMs)
        val moved = ChartWindow(bounds.startMs, base.endMs + 30L * DAY)
        assertTrue(ChartWindow.atRightEdge(atEnd, ChartWindow(bounds.startMs, base.endMs)))
        val c = ChartWindow.clamped(atEnd, moved, wasAtRightEdge = true)!!
        assertEquals("it should still be showing the newest data", moved.endMs, c.endMs)
        assertEquals("and must not have changed size", atEnd.spanMs, c.spanMs)
    }

    @Test fun `a window parked in history stays where it was put`() {
        val old = ChartWindow(bounds.startMs + 100L * DAY, bounds.startMs + 130L * DAY)
        assertTrue(!ChartWindow.atRightEdge(old, bounds))
        val c = ChartWindow.clamped(old, bounds, wasAtRightEdge = false)!!
        assertEquals(old, c)
    }

    @Test fun `clamping is safe with nothing to clamp`() {
        assertNull(ChartWindow.clamped(null, bounds, false))
        assertEquals(base, ChartWindow.clamped(base, null, false))
    }

    @Test fun `a chart pinched back out counts as unzoomed again`() {
        // The reset chip used to appear on its own: `isWhole` asked whether the two ENDS were
        // near the bounds' ends, and the newest data moves on every five-minute refresh.
        val whole = ChartWindow(bounds.startMs, bounds.endMs)
        val refreshed = ChartWindow(bounds.startMs, bounds.endMs + 5L * 60_000L)
        assertTrue(
            "five minutes of fresh data made an unzoomed chart look zoomed",
            ChartWindow.isWhole(whole, refreshed)
        )
    }

    // ------------------------------------------------------- the crosshair and axis

    @Test fun `the crosshair reads the axis, not the carried edge points`() {
        // On a zoomed chart the drawn list reaches a candle past each edge. Placing the
        // crosshair from the points' own extent would put it that far from the finger.
        val s = series(50, 86_400L)
        val w = ChartWindow(s.points[10].t * 1000L, s.points[20].t * 1000L)
        val c = clipToWindow(s, w)!!
        val i = nearestIndex(c.points, 0f, w)
        assertEquals("the left edge of the window is point 10", s.points[10].t, c.points[i].t)
        val j = nearestIndex(c.points, 1f, w)
        assertEquals("the right edge of the window is point 20", s.points[20].t, c.points[j].t)
    }

    @Test fun `without an axis the crosshair behaves exactly as it did`() {
        val s = series(50, 86_400L)
        assertEquals(0, nearestIndex(s.points, 0f))
        assertEquals(49, nearestIndex(s.points, 1f))
        assertEquals(nearestIndex(s.points, 0.5f), nearestIndex(s.points, 0.5f, null))
    }

    @Test fun `the crosshair is total on degenerate input`() {
        assertEquals(0, nearestIndex(emptyList(), 0.5f, base))
        assertEquals(0, nearestIndex(listOf(ChartPoint(1L, 2.0)), 9f, base))
    }


    // --------------------------------------------- one candle of lag, not the wall clock

    @Test fun `a candle's own length is what the newest point can be behind by`() {
        assertEquals(5L * 60_000L, ChartRange.D1.candleMs)
        assertEquals(30L * 60_000L, ChartRange.D5.candleMs)
        assertEquals(DAY, ChartRange.M6.candleMs)
        assertEquals(7L * DAY, ChartRange.Y5.candleMs)
        assertEquals(31L * DAY, ChartRange.MAX.candleMs)
    }

    @Test fun `the five-minute chart survives a weekend`() {
        // THE REGRESSION THIS PROVES FIXED. Measuring the lookback from the wall clock instead
        // of from the newest candle looked tidier and broke the 1D chart every weekend: at
        // noon on a Saturday the newest point is Friday afternoon, so the lookback was fifty
        // hours and the first pinch swapped five-minute candles for thirty-minute ones - on
        // the one view whose entire purpose is five-minute detail.
        val sessionMs = 6L * 3_600_000L + 30L * 60_000L      // 09:30 to 16:00
        val lookback = sessionMs + ChartRange.D1.candleMs     // newest candle plus its own lag
        assertEquals(
            "a whole 1D session must still be drawn from the 1D series",
            ChartRange.D1, ChartRange.rangeForLookback(lookback, ChartRange.D1)
        )
    }

    @Test fun `a window at the right edge of a monthly chart reaches the finer series`() {
        // The other half of the same fix: a monthly candle is stamped at the month's OPEN, so
        // a lookback measured from the last point alone came out up to a month short and asked
        // for a series that does not cover the window.
        val lookback = 2L * 3_600_000L + ChartRange.MAX.candleMs
        val r = ChartRange.rangeForLookback(lookback, ChartRange.MAX)
        assertTrue("it stayed on the monthly candles", r != ChartRange.MAX)
        assertTrue(
            "$r cannot reach back far enough to cover the window plus the monthly lag",
            r.approxSpanMs >= lookback
        )
        // AND IT KEEPS GOING. Once that series is loaded the newest candle is a day old
        // rather than a month, so the next pinch reaches the intraday rungs - which is how a
        // zoom walks down from an all-time chart to five-minute candles a gesture at a time.
        val thenLookback = 2L * 3_600_000L + r.candleMs
        assertTrue(
            "the next step could not reach the intraday rungs",
            ChartRange.rangeForLookback(thenLookback, r).approxSpanMs <= 7L * DAY
        )
    }


    @Test fun `a zoomed window keeps following new data as candles arrive`() {
        // THE REGRESSION THIS PROVES FIXED. The re-anchor runs BECAUSE new data arrived, so it
        // holds the new bounds and a window still at the old edge - and the gap it has to
        // tolerate is one candle, not a percentage of the window. Written as two percent of
        // the window, the pin only held for windows spanning fifty candles or more: zoom into
        // the last half hour of a five-minute chart and it froze there for the rest of the
        // session while the price above it went on ticking.
        val candle = ChartRange.D1.candleMs
        val newest = 1_000_000_000_000L
        val old = ChartWindow(newest - 30L * 60_000L, newest)
        val grown = ChartWindow(newest - 6L * 3_600_000L, newest + candle)
        assertTrue(
            "a half-hour window at the edge was not recognised as pinned",
            ChartWindow.atRightEdge(old, grown, candle)
        )
        val moved = ChartWindow.clamped(old, grown, wasAtRightEdge = true)!!
        assertEquals("it did not follow the new candle", grown.endMs, moved.endMs)
        assertEquals("and it changed size doing so", old.spanMs, moved.spanMs)
    }

    @Test fun `a window parked in history is still not dragged forward`() {
        val candle = ChartRange.D1.candleMs
        val newest = 1_000_000_000_000L
        val parked = ChartWindow(newest - 5L * 3_600_000L, newest - 3L * 3_600_000L)
        val grown = ChartWindow(newest - 6L * 3_600_000L, newest + candle)
        assertTrue(!ChartWindow.atRightEdge(parked, grown, candle))
    }


    @Test fun `a window holding one candle still measures the line that is drawn`() {
        // A fine window over a coarse series contains a single point, and everything measured
        // over one point is degenerate in the same direction - a 0.00 change and two identical
        // axis corners, printed over a line the canvas draws visibly sloping.
        val s = series(20, 7L * 86_400L)                  // weekly candles
        val t = s.points[5].t * 1000L
        val w = ChartWindow(t - 3_600_000L, t + 3_600_000L)
        val drawn = clipToWindow(s, w, pad = true)!!
        val r = insideIndices(drawn, w)
        assertTrue(
            "the measured range is a single point, so every figure over it is 0.00",
            r.last > r.first
        )
    }

    // ------------------------------------------------ one predicate for "is this default"

    @Test fun `a same-width window dragged sideways is not the default view`() {
        // The chart and the screen used to answer this differently - span-only on one side,
        // start-sensitive on the other - so a slight pinch plus a sideways drag panned the
        // chart and then had the pan thrown away the instant the fingers lifted.
        val series = ChartWindow(base.startMs, base.endMs)
        val panned = ChartWindow(
            base.startMs + base.spanMs / 5, base.endMs + base.spanMs / 5
        )
        assertTrue(
            "a window the same width but somewhere else read as untouched",
            !ChartWindow.isDefaultView(panned, series)
        )
        assertTrue(
            "the same window still reads as untouched with a candle of slack",
            !ChartWindow.isDefaultView(panned, series, ChartRange.M6.candleMs)
        )
    }

    @Test fun `the untouched view is recognised in both directions`() {
        val series = ChartWindow(base.startMs, base.endMs)
        assertTrue(ChartWindow.isDefaultView(series, series))
        assertTrue("a hair narrower is still the default view",
            ChartWindow.isDefaultView(
                ChartWindow(series.startMs, series.endMs - series.spanMs / 100), series
            )
        )
        assertTrue("a clearly wider window is NOT the default view",
            !ChartWindow.isDefaultView(
                ChartWindow(series.startMs - series.spanMs, series.endMs), series
            )
        )
    }

    // -------------------------------------------------------------- the zoom badge

    @Test fun `the badge names the span in words a reader can check`() {
        assertEquals("30 min", spanLabel(30L * 60_000L))
        assertEquals("4 hr", spanLabel(4L * 3_600_000L))
        assertEquals("3 days", spanLabel(3L * DAY))
        assertEquals("3 weeks", spanLabel(21L * DAY))
        assertEquals("6 months", spanLabel(183L * DAY))
        assertEquals("5.0 yr", spanLabel(1826L * DAY))
        assertEquals("30 yr", spanLabel(10958L * DAY))
    }

    @Test fun `the badge never prints an empty or negative span`() {
        for (ms in listOf(0L, 1L, -50L, Long.MAX_VALUE / 4)) {
            assertTrue("spanLabel($ms) was blank", spanLabel(ms).isNotBlank())
        }
    }
}
