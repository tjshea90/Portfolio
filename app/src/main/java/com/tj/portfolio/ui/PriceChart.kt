package com.tj.portfolio.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

        // ---- what the line did over exactly this window
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Fmt.changeMoney(shown.last, shown.change) + "   " +
                    Fmt.pctSigned(shown.changePct),
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
        }

        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT.dp)) {
            ChartCanvas(shown, line, MaterialTheme.colorScheme.outline, Modifier.fillMaxSize())
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

/** How tall the detail chart is. One place, so the empty state matches the drawn one. */
private const val CHART_HEIGHT = 170

/**
 * The row of range buttons.
 *
 * Horizontally scrollable rather than wrapped onto two lines: eight chips do not fit across
 * a phone, and a second row pushes the chart itself below the fold on the screen whose whole
 * job is to show it.
 */
@Composable
fun RangeChips(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChartRange.entries.forEach { r ->
            val on = r == selected
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
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    r.label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- drawing

@Composable
private fun ChartCanvas(
    s: ChartSeries,
    line: Color,
    grid: Color,
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
    }
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
    if (!s.range.intraday || !liveEdge || livePrice <= 0.0) return s
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
