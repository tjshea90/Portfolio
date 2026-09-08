package com.tj.portfolio.data

import kotlin.math.roundToLong

/**
 * A CONTINUOUS VIEW OVER A PRICE SERIES (Round 64).
 *
 * TJ's report: *"make the pinch to zoom smooth instead of chopping between intervals."*
 *
 * ---- WHAT WAS WRONG, AND WHY IT WAS BUILT THAT WAY
 *
 * Round 63's pinch moved between the eight [ChartRange] rungs: spread far enough and the
 * chart jumped from 1M to 5D, then to 1D. Every step redrew the whole picture at a new scale,
 * so a continuous gesture produced a sequence of discontinuous pictures. It was the honest
 * thing to build first, because a range IS the unit the app fetches in - the provider hands
 * over "one month of daily candles", not "the 43 days ending last Tuesday".
 *
 * ---- WHAT THIS SEPARATES
 *
 * The window a person is LOOKING AT and the series the app FETCHED are two different things,
 * and only the second one has to be quantised. A window is any two moments in time. It scales
 * by any factor, around any focal point, as smoothly as the fingers move. The range underneath
 * it is then chosen for RESOLUTION - the finest one whose candles can draw that window - and
 * swapped without the window moving, so the swap is invisible: the same stretch of time stays
 * on screen, drawn from finer or coarser data.
 *
 * Everything here is pure. No clock, no network, no Compose - so the zoom's behaviour can be
 * tested as arithmetic, which is the only way the edges (a window wider than the data, a
 * pinch at the very end of the series, a degenerate one-point series) get checked at all.
 */
data class ChartWindow(val startMs: Long, val endMs: Long) {

    /** How much time is on screen. At least 1 so nothing downstream divides by zero. */
    val spanMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    /** Where a moment falls across the width, 0 at the left edge and 1 at the right. */
    fun fractionOf(ms: Long): Float = ((ms - startMs).toDouble() / spanMs).toFloat()

    /** The moment at a fraction of the width. The inverse of [fractionOf]. */
    fun momentAt(fraction: Float): Long =
        startMs + (fraction.coerceIn(0f, 1f).toDouble() * spanMs).roundToLong()

    companion object {

        /**
         * The narrowest window the app will show.
         *
         * Thirty minutes, because the finest candle the provider publishes is five minutes -
         * so this is six points, which is the fewest that still reads as a line rather than as
         * a zigzag. Zooming further would magnify the same six points, which looks like
         * detail and is not.
         */
        const val MIN_SPAN_MS = 30 * 60_000L

        /** The whole of a series, which is what selecting a range chip means. */
        fun of(series: ChartSeries?): ChartWindow? {
            if (series == null || series.points.size < 2) return null
            return ChartWindow(series.startMs, series.endMs)
        }

        /**
         * ZOOM, CONTINUOUSLY, ABOUT A POINT.
         *
         * [factor] is how much wider the fingers got: above 1 the user is spreading, which
         * SHRINKS the window (less time on screen, more detail). [focus] is where the pinch is
         * centred as a fraction of the width - the moment under the fingers stays put, which
         * is what makes a zoom feel attached to the hand rather than to the screen.
         *
         * Total by construction. A non-finite or non-positive factor, a degenerate window and
         * an out-of-range focus are all handled by returning something sane rather than by
         * throwing: this runs inside a gesture handler, where an exception is a crash on a
         * screen the user is touching.
         */
        fun zoomed(
            w: ChartWindow,
            factor: Float,
            focus: Float,
            bounds: ChartWindow,
            minSpanMs: Long = MIN_SPAN_MS
        ): ChartWindow {
            if (!factor.isFinite() || factor <= 0f) return w
            val boundSpan = bounds.spanMs
            val maxSpan = boundSpan.coerceAtLeast(minSpanMs)
            val target = (w.spanMs / factor.toDouble()).roundToLong()
                .coerceIn(minSpanMs.coerceAtMost(maxSpan), maxSpan)
            if (target == w.spanMs) return w

            // The moment under the fingers is the fixed point of the transform.
            val anchor = w.momentAt(focus)
            val f = focus.coerceIn(0f, 1f).toDouble()
            var start = anchor - (f * target).roundToLong()
            var end = start + target
            // Slid, not clipped, when it runs off an end: clipping would silently change the
            // span the user just chose and make the zoom feel like it was fighting them.
            if (start < bounds.startMs) { start = bounds.startMs; end = start + target }
            if (end > bounds.endMs) { end = bounds.endMs; start = end - target }
            if (start < bounds.startMs) start = bounds.startMs
            return ChartWindow(start, end)
        }

        /**
         * PAN, by a fraction of the visible width.
         *
         * Positive [byFraction] moves the window LATER, which is what dragging the content
         * leftwards means. The span never changes - a pan that shortened the window at an end
         * would be a zoom the user did not ask for.
         */
        fun panned(w: ChartWindow, byFraction: Float, bounds: ChartWindow): ChartWindow {
            if (!byFraction.isFinite() || byFraction == 0f) return w
            val span = w.spanMs
            if (span >= bounds.spanMs) return ChartWindow(bounds.startMs, bounds.endMs)
            val shift = (byFraction.toDouble() * span).roundToLong()
            var start = w.startMs + shift
            if (start < bounds.startMs) start = bounds.startMs
            if (start + span > bounds.endMs) start = bounds.endMs - span
            return ChartWindow(start, start + span)
        }

        /**
         * True when this window is (near enough) the whole of what is available.
         *
         * ---- BY PROPORTION, NOT BY POSITION (Round 64 sweep)
         *
         * This was written as "starts within a percent of the start AND ends within a percent
         * of the end", and the second half of that made the "Reset zoom" chip appear on its
         * own. `bounds` is rebuilt from every series the app holds, and the newest of those
         * moves on every refresh: on a 1D chart the one-percent slack is under four minutes,
         * and the chart reloads every five - so a chart the user had pinched back OUT to whole
         * sprouted a reset chip roughly once per refresh, for a zoom that no longer existed.
         *
         * What actually matters is how much of the data is on screen. A window covering nine
         * tenths of it is not something anyone needs a button to escape from, wherever its two
         * ends happen to sit, and a few minutes of fresh data at the right-hand edge changes
         * that proportion by a fraction of a percent instead of by a boolean.
         */
        fun isWhole(w: ChartWindow?, bounds: ChartWindow?): Boolean {
            if (w == null || bounds == null) return true
            return w.spanMs >= (bounds.spanMs * WHOLE_FRACTION).toLong()
        }

        /** How much of the data has to be on screen before a window counts as unzoomed. */
        private const val WHOLE_FRACTION = 0.92

        /**
         * A window pulled back inside bounds that have MOVED UNDER IT (Round 64 sweep).
         *
         * Bounds change whenever a series arrives, and a zoom is what MAKES them change: the
         * window picks a finer range, the fetch lands, and the app is suddenly holding data it
         * did not have when the fingers left the glass. Two things then go wrong if nothing
         * re-anchors the window:
         *
         *   * it can be wider than everything now known, which draws a stretch of nothing;
         *   * it can sit entirely outside the newly loaded series - a real case, because a
         *     coarse series is stamped at the candle's OPEN, so the right-hand edge of a
         *     five-year monthly chart can be three weeks behind today. Zooming into that edge
         *     produces a window in a week the finer series does not cover, and the chart goes
         *     blank with no way back but the range chips.
         *
         * PINNED TO THE RIGHT WHEN IT WAS ALREADY THERE. A window whose end was at the newest
         * data is a request to watch the latest, so it follows the data forward rather than
         * being left behind by it; one parked in the middle of history stays where it was put.
         */
        fun clamped(w: ChartWindow?, bounds: ChartWindow?, wasAtRightEdge: Boolean): ChartWindow? {
            if (w == null || bounds == null) return w
            val span = w.spanMs.coerceAtMost(bounds.spanMs)
            if (wasAtRightEdge) {
                val start = (bounds.endMs - span).coerceAtLeast(bounds.startMs)
                return ChartWindow(start, bounds.endMs)
            }
            var start = w.startMs.coerceIn(bounds.startMs, bounds.endMs - span)
            if (start < bounds.startMs) start = bounds.startMs
            return ChartWindow(start, start + span)
        }

        /** True when a window's right-hand edge is at (or past) the newest data it knows of. */
        fun atRightEdge(w: ChartWindow?, bounds: ChartWindow?): Boolean {
            if (w == null || bounds == null) return false
            val slack = (w.spanMs / 50L).coerceAtLeast(1L)
            return w.endMs >= bounds.endMs - slack
        }
    }
}
