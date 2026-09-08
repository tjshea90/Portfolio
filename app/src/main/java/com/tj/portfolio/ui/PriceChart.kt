package com.tj.portfolio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.util.Fmt

/**
 * THE PRICE CHART, AND THE RANGE SELECTOR ABOVE IT.
 *
 * TJ's report, in full: *"there is a chart showing the price change but I cannot see how
 * long the chart is tracking. if possible, make a menu or buttons near the charts that let
 * me select time length for each graph."*
 *
 * Two separate problems in one sentence, and both are answered here:
 *
 *   1. **You could not tell what you were looking at.** The old chart was a bare line with
 *      no axis, no dates and no caption. This one states the window in words underneath it
 *      ("Six months, daily closes"), prints the first and last date on the x-axis, the high
 *      and low on the y-axis, and says what the price did over exactly that window.
 *   2. **You could not ask for a different one.** [RangeChips] is the row of buttons, and
 *      it includes the after-hours-only view TJ asked for by name.
 *
 * The chart deliberately draws from TIMESTAMPS rather than from evenly spaced samples. On a
 * one-day line that is barely visible; on the after-hours view it is the whole point, because
 * an overnight session has a real gap in the middle of it and drawing the points evenly
 * spaced would quietly invent trading that did not happen.
 */
@Composable
fun PriceChart(
    series: ChartSeries?,
    range: ChartRange,
    loading: Boolean,
    modifier: Modifier = Modifier,
    /**
     * The live quote price, used ONLY to keep the right-hand tip of an intraday line
     * current. See [withLiveEdge] - this is what lets the series be fetched every five
     * minutes instead of every fifteen seconds without the chart visibly disagreeing with
     * the big price above it.
     */
    livePrice: Double = 0.0,
    /**
     * True when [livePrice] belongs to the SAME session the chart is drawing. The caller
     * decides, because the answer differs per range: the 1D line's live edge is the regular
     * price while the market is open, and the after-hours line's is the extended print once
     * it has closed. Getting this wrong does not crash anything - it silently paints one
     * session's price onto another's line - which is why it is a decision and not a guess.
     */
    liveEdge: Boolean = false
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = remember(series, livePrice, liveEdge) {
        withLiveEdge(series, livePrice, liveEdge)
    }

    Column(modifier.fillMaxWidth()) {
        if (shown == null || shown.isEmpty) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    // Told apart deliberately. "No chart" and "the chart has not arrived
                    // yet" are different situations and the first is alarming if it is
                    // really the second.
                    if (loading) "Loading ${range.label} chart..."
                    else "No ${range.label} chart available - pull down to retry",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }
            return@Column
        }

        val up = shown.change >= 0
        val line = if (up) Green else Red

        // ---- SCRUB STATE.
        //
        // An INDEX, not a raw x, and that is the whole reason this stays cheap. The pointer
        // handler already knows the layout width, so the x -> point lookup happens once per
        // drag event there instead of being redone by every consumer of the position. -1 is
        // "not scrubbing".
        //
        // `mutableIntStateOf`, not `mutableStateOf(Int)`: a drag emits an event per frame and
        // the boxed version would allocate an Integer for every one of them.
        //
        // NOT KEYED ON `shown`, AND THAT IS THE IMPORTANT PART. `withLiveEdge` rebuilds
        // `shown` on every quote tick while the market is open, so `remember(shown)` handed
        // back a FRESH state every fifteen seconds - which cleared the crosshair out from
        // under a finger that was still on the screen, during exactly the hours the feature
        // is for. The index is reset deliberately below instead, when the chart being drawn
        // actually changes; a stale index in between is harmless because every reader
        // bounds-checks it against `points.indices`.
        val scrub = remember { mutableIntStateOf(NO_SCRUB) }

        // A different symbol or a different range is a different chart, so the old position
        // means nothing. New DATA for the same chart is not - that is just the line moving
        // under a finger that is still pointing at the same moment in time.
        LaunchedEffect(shown.symbol, shown.range) { scrub.intValue = NO_SCRUB }

        // Read INSIDE the gesture handler so the handler itself never has to restart - see
        // the note on `pointerInput(Unit)` below.
        val liveSeries = rememberUpdatedState(shown)

        // ---- the readout: what the line did over this window, or what it did at your finger
        //
        // ITS OWN COMPOSABLE so that scrubbing recomposes only this line. Read `scrub` here
        // in `PriceChart` instead and every drag event would recompose the whole chart -
        // canvas, axis labels and caption included - to change one string.
        ChartReadout(shown, range, line, muted, scrub)

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT.dp)
                // So the gesture can be driven from a rendered test. The one thing about
                // scrubbing that source review cannot answer is whether it has stolen the
                // list's vertical scroll, and that has to be measured on a real touch.
                .testTag(CHART_TEST_TAG)
                // ---- THE SCRUB GESTURE.
                //
                // `detectHorizontalDragGestures` is chosen over a raw pointer loop for one
                // specific reason: it waits for the HORIZONTAL touch slop before it claims the
                // gesture, and it does not consume the initial press. A vertical swipe that
                // starts on the chart therefore still scrolls the page it sits in, which
                // matters because the chart is 170dp of a scrolling screen and making that a
                // dead zone would be a worse bug than the feature is a feature.
                // `pointerInput(Unit)`, NOT `pointerInput(shown)`. Changing that key
                // CANCELS and restarts the handler, and `shown` is rebuilt on every quote
                // tick - so keying on it aborted an in-progress drag every fifteen seconds
                // during market hours. The series is read through `rememberUpdatedState`
                // instead, which keeps the handler alive while still seeing current data.
                .pointerInput(Unit) {
                    fun at(x: Float) {
                        val pts = liveSeries.value.points
                        if (pts.size < 2) return
                        val w = size.width.toFloat()
                        if (w <= 0f) return
                        scrub.intValue = nearestIndex(pts, x / w)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { at(it.x) },
                        onDragEnd = { scrub.intValue = NO_SCRUB },
                        onDragCancel = { scrub.intValue = NO_SCRUB },
                        onHorizontalDrag = { change, _ ->
                            // Consumed so the parent cannot also act on a gesture this has
                            // already claimed.
                            change.consume()
                            at(change.position.x)
                        }
                    )
                }
        ) {
            ChartCanvas(
                shown, line, MaterialTheme.colorScheme.outline,
                scrub, MaterialTheme.colorScheme.surface, Modifier.fillMaxSize()
            )
            // The y-axis, as two labels rather than a drawn scale: on a 170dp chart on a
            // phone the high and the low are the only two values anyone reads off it.
            Text(
                Fmt.price(shown.high),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Text(
                Fmt.price(shown.low),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        Spacer(Modifier.height(3.dp))

        // ---- the x-axis, as its two ends
        Row(Modifier.fillMaxWidth()) {
            val withDate = spansMoreThanADay(shown)
            Text(
                axisLabel(shown.startMs, range, withDate),
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )
            Spacer(Modifier.weight(1f))
            Text(
                axisLabel(shown.endMs, range, withDate),
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            buildString {
                append(range.caption)
                append("  -  ")
                append(shown.points.size)
                append(if (shown.points.size == 1) " point" else " points")
                if (shown.truncated) {
                    // Said out loud rather than drawn as if it were the full window. A stock
                    // that listed eighteen months ago has no five-year chart, and relabelling
                    // eighteen months as five years is the kind of quiet wrongness this app
                    // has spent several rounds removing.
                    append("  -  this is the whole history on record, which is shorter than ")
                    append(range.label)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = muted
        )
    }
}

/**
 * The line above the chart: what the price did over the whole window, or - while a finger is
 * on it - what it was at that exact point.
 *
 * SEPARATE FROM [PriceChart] ON PURPOSE. This is the only thing that has to recompose while
 * scrubbing, and a drag emits an event per frame. Reading the scrub state up in `PriceChart`
 * would recompose the canvas, both axis labels and the caption sixty times a second to change
 * one string.
 *
 * IT IS ALSO A FIXED HEIGHT. The scrubbed readout is a different length from the resting one,
 * and letting the row resize would make the chart jump up and down under the finger.
 */
@Composable
private fun ChartReadout(
    s: ChartSeries,
    range: ChartRange,
    line: Color,
    muted: Color,
    scrub: MutableIntState
) {
    val i = scrub.intValue
    val point = if (i in s.points.indices) s.points[i] else null
    // HOISTED OUT OF THE PER-FRAME PATH. This formats two ISO dates through SimpleDateFormat,
    // and it was being asked on every frame of a drag to answer a question whose answer
    // cannot change during one.
    val withDate = remember(s) { spansMoreThanADay(s) }

    Row(
        Modifier.fillMaxWidth().height(READOUT_HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (point == null) {
            Text(
                Fmt.changeMoney(s.last, s.change) + "   " + Fmt.pctSigned(s.changePct),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = line
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // NAMES THE BASELINE. The same omission the price block was corrected for in
                // Round 51: a percentage with nothing saying what it is a percentage OF.
                when (range) {
                    ChartRange.D1 -> "since yesterday's close"
                    ChartRange.OVERNIGHT -> "since today's close"
                    else -> "over ${range.label.lowercase()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
        } else {
            // THE PRICE AT THE FINGER, and the change measured from the SAME baseline the
            // resting readout uses - not from the left-hand edge of the line. Those differ on
            // the 1D and after-hours views, where the baseline is the previous close rather
            // than the first plotted point, and quietly switching reference mid-gesture would
            // make the number disagree with the one shown a moment earlier.
            val from = s.from
            val delta = if (from > 0.0) point.close - from else 0.0
            val pct = if (from > 0.0) delta / from * 100.0 else 0.0
            Text(
                Fmt.price(point.close),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (from > 0.0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    Fmt.changeMoney(point.close, delta) + "   " + Fmt.pctSigned(pct),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = signColor(pct)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                // Always dated when the window spans more than a day, because on the
                // after-hours and 5-day views a bare clock does not say which day it is.
                axisLabel(point.t * 1000L, range, withDate),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1
            )
        }
    }
}

/** Height reserved for the readout, so scrubbing cannot make the chart jump. */
private const val READOUT_HEIGHT = 22

/** Nothing under the finger. */
internal const val NO_SCRUB = -1

/** Test handle for the scrubbable area. See the note at its use site. */
internal const val CHART_TEST_TAG = "priceChartSurface"

/** How tall the detail chart is. One place, so the empty state matches the drawn one. */
private const val CHART_HEIGHT = 170

/**
 * The row of range buttons, each carrying what that window actually did (Round 62).
 *
 * Horizontally scrollable rather than wrapped onto two lines: eight chips do not fit across
 * a phone, and a second row pushes the chart itself below the fold on the screen whose whole
 * job is to show it.
 *
 * ---- THE FIGURE ON THE CHIP
 *
 * [perf] is what each window did, as a percentage, and it comes from the caller rather than
 * being worked out here for two reasons. The first is that this composable has no idea which
 * symbol it belongs to. The second is the one that matters: **the number on a chip must be
 * the number the chart itself would show for that range**, and only the screen holding the
 * quote can say what the live edge is for each window - see [rangePct] and `liveEdgePrice`.
 * Two figures on one screen that disagree about the same window would be worse than no
 * figures at all.
 *
 * NO CHIP EVER CAUSES A FETCH, and that is a deliberate limit rather than an oversight. The
 * disk read in `PortfolioViewModel.loadChart` already pulls EVERY cached range for a symbol
 * in one query, so every window the user has looked at before is answered for free; a window
 * that has never been loaded shows no figure until it is selected. Filling the blanks would
 * mean up to seven extra chart requests per stock opened, which is exactly the traffic shape
 * Rounds 56-58 spent three rounds removing.
 *
 * [loading] is told apart from "nothing held" for the same reason the empty chart is: a chip
 * that is blank because a request is in flight and one that is blank because nothing has ever
 * been fetched are different situations, and showing the same thing for both makes the first
 * look broken.
 */
@Composable
fun RangeChips(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
    perf: Map<ChartRange, Double> = emptyMap(),
    loading: Set<ChartRange> = emptySet()
) {
    val scroll = rememberScrollState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChartRange.entries.forEach { r ->
            val on = r == selected
            val pct = perf[r]
            Box(
                Modifier
                    // THE APP'S OWN 48dp RULE, and it is not decorative here. Round 46 found
                    // a 33dp target on this screen and the fix was measured, not estimated.
                    // Eight chips in a scrolling row are the worst case for a mis-tap:
                    // everything a near-miss can land on is another range button.
                    //
                    // `defaultMinSize` before the background, so the painted chip is the
                    // full 48dp rather than a smaller pill with dead space around it.
                    .minTapTarget()
                    .background(
                        if (on) Accent else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(r) }
                    // 12dp rather than 14dp: the chip is wider now that it carries a figure,
                    // and eight of them still have to be reachable with one thumb-flick.
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        r.label,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Color.White else muted,
                        maxLines = 1
                    )
                    Text(
                        // ALWAYS RENDERED, even when there is nothing to say, and the blank
                        // is a NON-BREAKING SPACE rather than an empty string. Every chip
                        // then reserves the same two lines, so the row does not go ragged
                        // when one window has a figure and its neighbour does not - which at
                        // a large font scale is a visible step in the middle of the row.
                        rangeFigure(pct, r in loading),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            // On the selected chip the sign colour would be read against
                            // Accent, where Red in particular does not carry. The "+" or "-"
                            // already says the direction.
                            on -> Color.White
                            pct != null -> signColor(pct)
                            else -> muted
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** What the second line of a chip reads: the figure, "..." while fetching, blank otherwise. */
internal fun rangeFigure(pct: Double?, loading: Boolean): String = when {
    pct != null -> Fmt.pctSigned(pct)
    loading -> "..."
    // A NON-BREAKING SPACE, written as an escape so it cannot be mistaken for an ordinary
    // one or trimmed by an editor. An empty string would collapse the line entirely and
    // leave that chip shorter than its neighbours.
    else -> "\u00A0"
}

/**
 * WHAT ONE RANGE DID, as a percentage - or null when the app cannot honestly say.
 *
 * THIS IS THE SAME ARITHMETIC THE CHART READOUT DOES, and it has to stay that way: the chip
 * for the selected range sits directly above a line that states the same window's move, and
 * two different numbers for one window on one screen is the kind of quiet contradiction this
 * project has spent rounds removing. `ChartSeries.changePct` measured on [withLiveEdge]'s
 * output is the definition; this reproduces it WITHOUT the copy, because `withLiveEdge`
 * rebuilds the whole point list to replace one element and this runs for every chip on every
 * quote tick. `RangeChipTest` asserts the two agree rather than trusting the comment.
 *
 * TOTAL BY CONSTRUCTION. A missing series, a one-point series, a zero or non-finite baseline
 * and a non-finite result all return null - "no figure" - because the alternative is printing
 * "NaN%" or "+Infinity%" on a button.
 */
internal fun rangePct(series: ChartSeries?, livePrice: Double, liveEdge: Boolean): Double? {
    if (series == null || series.isEmpty) return null
    val from = series.from
    if (from <= 0.0 || !from.isFinite()) return null
    // Exactly [withLiveEdge]'s condition, and deliberately written the same way round.
    val last =
        if (series.range.intraday && liveEdge && livePrice > 0.0) livePrice else series.last
    if (last <= 0.0 || !last.isFinite()) return null
    val pct = (last - from) / from * 100.0
    if (!pct.isFinite()) return null
    // `+ 0.0` turns a negative zero into a positive one. Without it a dead-flat window can
    // format as "+-0.00%", because the sign is chosen before the number is formatted.
    return pct + 0.0
}

// ---------------------------------------------------------------------- drawing

@Composable
private fun ChartCanvas(
    s: ChartSeries,
    line: Color,
    grid: Color,
    /**
     * Read INSIDE the draw block, never in composition.
     *
     * `Canvas` is `drawBehind`, and a state read inside a draw lambda registers a
     * DRAW-phase dependency: changing it re-runs the drawing and nothing else. Taking an
     * `Int` parameter here instead would put the read in the caller and recompose this
     * composable on every frame of a drag, which is the expensive way to move one line.
     */
    scrub: MutableIntState,
    dot: Color,
    modifier: Modifier
) {
    val pts = s.points
    val base = s.baseline.takeIf { it > 0.0 }
    val lo = minOf(s.low, base ?: s.low)
    val hi = maxOf(s.high, base ?: s.high)
    val span = (hi - lo).let { if (it < 1e-9) 1.0 else it }
    val t0 = pts.first().t
    val tSpan = (pts.last().t - t0).let { if (it <= 0L) 1L else it }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val pad = h * 0.08f
        // STROKE WIDTHS IN DP, NOT IN RAW PIXELS.
        //
        // `DrawScope` measures in pixels, so a literal `2.6f` is 2.6 PHYSICAL pixels - about
        // 1.3dp on an xhdpi screen and well under 1dp on the ~400dpi panel this app actually
        // runs on. The existing 64dp sparkline gets away with a pixel literal because at that
        // size a hairline reads as delicate; on a 170dp chart that is the main thing on the
        // screen it just reads as faint. `DrawScope` is a `Density`, so the conversion is
        // free and correct on every phone.
        val lineStroke = 2.2.dp.toPx()
        val baseStroke = 1.2.dp.toPx()
        val gridStroke = 1.dp.toPx()
        fun y(v: Double) = (h - pad) - ((v - lo) / span).toFloat() * (h - pad * 2)
        // X FROM THE TIMESTAMP, NOT THE INDEX. On the after-hours view the session has a
        // genuine gap in it; spacing the points evenly would draw a continuous line across
        // hours in which nothing traded.
        fun x(p: ChartPoint) = w * ((p.t - t0).toFloat() / tSpan.toFloat())

        // faint gridlines, so the eye has something to measure the line against
        for (i in 1..2) {
            val gy = pad + (h - pad * 2) * i / 3f
            drawLine(
                grid.copy(alpha = 0.55f), Offset(0f, gy), Offset(w, gy),
                strokeWidth = gridStroke
            )
        }

        if (base != null) {
            val by = y(base)
            // The dash pitch is in dp too, or the dotted baseline is a solid line on a
            // high-density screen and a row of far-apart specks on a low-density one.
            val dash = 4.dp.toPx()
            val gap = 4.dp.toPx()
            var sx = 0f
            while (sx < w) {
                drawLine(
                    Color.Gray.copy(alpha = 0.55f),
                    Offset(sx, by), Offset(minOf(sx + dash, w), by), strokeWidth = baseStroke
                )
                sx += dash + gap
            }
        }

        val path = Path().apply {
            moveTo(x(pts[0]), y(pts[0].close))
            for (i in 1 until pts.size) lineTo(x(pts[i]), y(pts[i].close))
        }
        drawPath(
            Path().apply {
                addPath(path)
                lineTo(x(pts.last()), h)
                lineTo(x(pts.first()), h)
                close()
            },
            Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0f)))
        )
        drawPath(
            path, line,
            style = Stroke(width = lineStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // ---- the crosshair, drawn last so it sits over the line
        val i = scrub.intValue
        if (i in pts.indices) {
            val p = pts[i]
            val px = x(p)
            val py = y(p.close)
            drawLine(
                grid.copy(alpha = 0.9f),
                Offset(px, 0f), Offset(px, h),
                strokeWidth = lineStroke
            )
            // A ring in the surface colour under the dot, so it reads clearly wherever it
            // lands - over the filled gradient, over a gridline, or over the line itself.
            drawCircle(dot, radius = 5.dp.toPx(), center = Offset(px, py))
            drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(px, py))
        }
    }
}

/**
 * The index of the point nearest a horizontal position, given as a fraction of the width.
 *
 * BY TIMESTAMP, NOT BY INDEX, because the chart is drawn that way: `x` is linear in `t`, so
 * the inverse has to be too. On the after-hours view - which has a real gap in the middle of
 * it where nothing traded - an index-based lookup would put the crosshair somewhere the
 * finger is not.
 *
 * A BINARY SEARCH, because this runs on every frame of a drag and an intraday series is ~400
 * points, a five-day one ~900. Linear would be ~500 comparisons per frame for no reason.
 *
 * Total: an out-of-range fraction clamps to an end, and a degenerate series returns 0 rather
 * than throwing - this is called from a gesture handler, where an exception is a crash.
 */
internal fun nearestIndex(points: List<ChartPoint>, xFraction: Float): Int {
    if (points.size < 2) return 0
    val t0 = points.first().t
    val span = (points.last().t - t0).coerceAtLeast(1L)
    val frac = xFraction.coerceIn(0f, 1f).toDouble()
    val target = t0 + Math.round(frac * span)

    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].t < target) lo = mid + 1 else hi = mid
    }
    // `lo` is the first point at or after the target; whichever neighbour is closer wins, and
    // a tie goes to the earlier one so dragging left and right lands on the same point.
    if (lo > 0 && (target - points[lo - 1].t) <= (points[lo].t - target)) return lo - 1
    return lo
}

// ---------------------------------------------------------------------- helpers

/**
 * Replace the last point with the live price on an intraday chart while the market is open.
 *
 * WHY THIS EXISTS AT ALL. It is what buys the five-minute fetch interval. Yahoo's finest
 * intraday candle is five minutes, so asking more often cannot return a point that did not
 * already exist - but without this the line's right-hand tip is up to five minutes behind
 * the big price directly above it, which reads as the app contradicting itself.
 *
 * REPLACE, NEVER APPEND. Appending grows the series by a point on every quote tick, which
 * over an afternoon turns a 78-point line into a thousand-point one that is mostly the same
 * price repeated. The last point is either a candle at most five minutes old or a live price
 * written by an earlier tick; in both cases the current price is the better value for it.
 * (This is the same rule `carryDisplayFields` follows for the row sparkline, and it is
 * written down twice on purpose - the two got out of step once already.)
 *
 * Only for the intraday ranges: on a five-year monthly line, moving the last point to
 * today's price would silently redraw a month that has not finished.
 */
internal fun withLiveEdge(s: ChartSeries?, livePrice: Double, liveEdge: Boolean): ChartSeries? {
    if (s == null || s.isEmpty) return s
    // `!(livePrice > 0.0)` rather than `livePrice <= 0.0`, and the difference is NaN: every
    // comparison against NaN is false, so the old form let a NaN price through and PAINTED
    // it as the last point of the line. Round 62: the range chips do this same test to work
    // out what a window did, and the two must not be able to disagree about a price.
    if (!s.range.intraday || !liveEdge || !(livePrice > 0.0)) return s
    val last = s.points.last()
    if (last.close == livePrice) return s
    return s.copy(points = s.points.dropLast(1) + ChartPoint(last.t, livePrice))
}

/**
 * A clock for a window inside one day, a date for anything longer, and BOTH for an intraday
 * window that crosses midnight.
 *
 * The last case is the one that matters and the reason this is not a one-liner: the 5-day
 * and after-hours views are both intraday AND multi-day, so labelling their ends "4:00 PM"
 * and "9:35 AM" says nothing about which days those are - and on the after-hours chart the
 * two ends are usually different days by definition.
 */
internal fun axisLabel(ms: Long, range: ChartRange, withDate: Boolean): String = when {
    ms <= 0L -> ""
    !range.intraday -> Fmt.shortDay(ms)
    withDate -> Fmt.shortDay(ms) + "  " + Fmt.clock(ms)
    else -> Fmt.clock(ms)
}

/** True when the series' two ends fall on different calendar days. */
internal fun spansMoreThanADay(s: ChartSeries): Boolean =
    s.startMs > 0L && s.endMs > 0L && Fmt.iso(s.startMs) != Fmt.iso(s.endMs)
