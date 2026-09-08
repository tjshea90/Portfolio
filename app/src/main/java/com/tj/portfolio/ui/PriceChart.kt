package com.tj.portfolio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
    liveEdge: Boolean = false,
    /**
     * PINCH TO ZOOM (Round 63). Called with +1 for each rung the user has spread the chart IN
     * by, and -1 for each rung pinched OUT - never with 0, and never for a gesture that has
     * not crossed a full rung. The chart itself does not know what a range IS, so the caller
     * decides where a rung lands ([ChartRange.zoomed]) and what it costs to get there.
     *
     * Null - the default - leaves the pinch handler out of the tree entirely, which is what
     * the places that draw a chart without a range selector want.
     */
    onZoom: ((Int) -> Unit)? = null,
    /**
     * The benchmark series to draw against this one, or null for the ordinary price chart.
     *
     * When this is present the chart switches to PERCENTAGE mode - both lines measured from
     * the same moment, a percentage y-axis and a zero line - because two prices in dollars
     * cannot honestly share an axis. See [comparePercents].
     */
    compare: ChartSeries? = null,
    compareLabel: String = BENCHMARK_SYMBOL,
    /**
     * The benchmark's own live price, on the same terms as [livePrice].
     *
     * NEEDED, not a nicety. On an intraday chart the stock's right-hand tip is replaced with
     * its live quote so the line agrees with the big price above it - and if the benchmark's
     * tip were left as its last five-minute candle, the two ends being compared would be up
     * to five minutes apart. The gap between them is the entire number this feature exists to
     * show, so measuring it between two different moments would be wrong in exactly the place
     * it matters most. Zero when the app holds no quote for the benchmark, which simply
     * leaves its line ending on its last candle.
     */
    compareLivePrice: Double = 0.0
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = remember(series, livePrice, liveEdge) {
        withLiveEdge(series, livePrice, liveEdge)
    }

    // ---- GESTURE STATE, HOISTED ABOVE THE EMPTY-STATE BRANCH (Round 63 sweep).
    //
    // THE BUG THIS FIXES. Everything below used to sit after a `return@Column` taken when the
    // series is null or empty, so the whole gesture node - crosshair state, pinch handler,
    // zoom badge - simply did not exist while a chart was missing. And a chart is missing for
    // exactly the moment that matters most: pinch to a range this symbol has never been
    // fetched at and `chartMap` has no entry for it, so the node was DESTROYED mid-gesture.
    // The zoom stopped after one rung, the badge vanished at the instant it was supposed to
    // name the new window, and the fingers still on the glass fell through to the list
    // underneath. "Zoom in gradually all the way down to 5 minute" was impossible in one
    // gesture on any stock opened for the first time.
    //
    // The state and the handler are now declared once, above the branch, and the placeholder
    // carries the same surface - so a pinch continues across a window that has not arrived
    // yet, which is the whole point of a continuous zoom.
    val scrub = remember { mutableIntStateOf(NO_SCRUB) }
    val liveSeries = rememberUpdatedState(shown)
    val liveZoom = rememberUpdatedState(onZoom)
    var zooming by remember { mutableStateOf(false) }

    // A different symbol or a different range is a different chart, so the old position means
    // nothing. New DATA for the same chart is not - that is just the line moving under a
    // finger that is still pointing at the same moment in time.
    LaunchedEffect(shown?.symbol, shown?.range) { scrub.intValue = NO_SCRUB }

    val gestures = Modifier
        // So the gesture can be driven from a rendered test. The one thing about scrubbing
        // that source review cannot answer is whether it has stolen the list's vertical
        // scroll, and that has to be measured on a real touch.
        .testTag(CHART_TEST_TAG)
        // ---- THE SCRUB AND THE PINCH, IN ONE HANDLER.
        //
        // ONE HANDLER FOR BOTH, not two `pointerInput` modifiers. Two independent detectors
        // on the same node both see every event, so a two-finger pinch would ALSO be read as
        // a one-finger drag by the scrub detector and the crosshair would chase a finger that
        // is zooming. Deciding once, from the pointer count, is the only way the two agree.
        //
        // `pointerInput(Unit)`, NOT `pointerInput(shown)`. Changing that key CANCELS and
        // restarts the handler, and `shown` is rebuilt on every quote tick - so keying on it
        // aborted an in-progress drag every fifteen seconds during market hours. The series
        // and the callback are read through `rememberUpdatedState` instead.
        .pointerInput(Unit) {
            chartGestures(
                pointAt = { x ->
                    val pts = liveSeries.value?.points.orEmpty()
                    val w = size.width.toFloat()
                    if (pts.size >= 2 && w > 0f) {
                        scrub.intValue = nearestIndex(pts, x / w)
                    }
                },
                clearPoint = { scrub.intValue = NO_SCRUB },
                // A PROVIDER, NOT A CAPTURED VALUE, and that is the second half of the same
                // fix. `onZoomStep = liveZoom.value` read the callback ONCE, in the body of
                // `pointerInput(Unit)`, which runs exactly once for the life of the node -
                // defeating the `rememberUpdatedState` two lines above it. Opening a fund and
                // tapping through to one of its holdings reuses this node, so the stale
                // lambda went on writing into the previous screen's `zoomSettling` state: the
                // 380ms settle silently stopped applying and a four-rung spread fired four
                // chart fetches, which is the exact traffic the settle exists to remove.
                zoom = { liveZoom.value },
                onZoomActive = { active -> zooming = active }
            )
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
                    )
                    .then(gestures),
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

        // ---- COMPARISON MODE, computed once per data change rather than per frame.
        //
        // `remember(shown, compare)` and not `remember(compare)`: `shown` is rebuilt on every
        // quote tick by `withLiveEdge`, and the overlay has to follow the line it is drawn
        // against or the two disagree at the right-hand edge. Both arrays are null whenever
        // the comparison cannot be made honestly, and every reader below treats null as
        // "draw the ordinary price chart", so there is no half-comparison state.
        val cmp = remember(shown, compare, compareLivePrice, liveEdge) {
            val benchmark = withLiveEdge(compare, compareLivePrice, liveEdge)
            val other = comparePercents(shown, benchmark)
            val own = if (other == null) null else primaryPercents(shown)
            if (other == null || own == null) null else ComparePair(own, other)
        }

        // ---- the readout: what the line did over this window, or what it did at your finger
        //
        // ITS OWN COMPOSABLE so that scrubbing recomposes only this line. Read `scrub` here
        // in `PriceChart` instead and every drag event would recompose the whole chart -
        // canvas, axis labels and caption included - to change one string.
        // THE READOUT IS TEXT, so it takes the text-legible green/red rather than the line's
        // fill colour - see the note on `signColor`. The line itself keeps the brand colour.
        ChartReadout(shown, range, signColor(shown.change), muted, scrub, cmp, compareLabel)

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT.dp)
                .then(gestures)
        ) {
            ChartCanvas(
                shown, line, MaterialTheme.colorScheme.outline,
                scrub, MaterialTheme.colorScheme.surface, Modifier.fillMaxSize(),
                cmp, Benchmark
            )
            // The y-axis, as two labels rather than a drawn scale: on a 170dp chart on a
            // phone the high and the low are the only two values anyone reads off it.
            //
            // IN PERCENT WHEN COMPARING, because that is what the axis is then measuring.
            // Leaving dollar labels on a percentage chart is the kind of quiet wrongness this
            // project has spent rounds removing - the numbers would be real and would describe
            // a different chart.
            //
            // THEY DESCRIBE THE AXIS, NOT THE SERIES, and the difference is real. The canvas
            // widens its scale to include the dotted previous-close baseline so that line is
            // always visible - so on a gap-down day (previous close 110, the whole session
            // between 98 and 104) the top of the drawn area is 110 while the label pinned to
            // that corner used to read $104.00, two-thirds of the way down the chart. A label
            // at the top of an axis has to be the top of that axis; the day's own high and
            // low are already legible from the line itself.
            val bounds = remember(cmp, shown) { cmp?.bounds() ?: priceBounds(shown) }
            Text(
                if (cmp != null) Fmt.pctSigned(bounds[1]) else Fmt.price(bounds[1]),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Text(
                if (cmp != null) Fmt.pctSigned(bounds[0]) else Fmt.price(bounds[0]),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.align(Alignment.BottomEnd)
            )

            // ---- WHERE THE ZOOM HAS GOT TO.
            //
            // Shown only while two fingers are down. The range chips already say which window
            // is selected, but they sit above the chart on a screen that scrolls, so during a
            // pinch they are often not on screen at all - and a zoom that changes the picture
            // with nothing naming the new window is the exact complaint the range selector was
            // built to answer in the first place.
            if (zooming) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .background(Accent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        range.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
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

        // ---- THE LEGEND, only when there are two lines to tell apart.
        if (cmp != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(line)
                Text(
                    "  " + shown.symbol + "  ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = muted
                )
                LegendDot(Benchmark)
                Text(
                    "  " + compareLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = muted
                )
                // ---- THE NUMBER THE OVERLAY EXISTS TO PRODUCE.
                //
                // Two percentages on one screen still leave the reader doing the subtraction,
                // and "did it beat the market" is the entire question. Stated in percentage
                // POINTS and labelled as such: 42.6% against 6.8% is 35.9 points of
                // difference, not 35.9 percent, and the two are not the same quantity.
                // AT THE LAST MOMENT BOTH LINES HAVE A READING, not at each line's own last
                // point. `other` carries NaN wherever the benchmark has no value for one of
                // the stock's points - a fund that did not trade in an extended session, say -
                // so taking each series' own final figure would subtract two different
                // moments and print the result as this window's out-performance.
                val spread = remember(cmp) { cmp.spread() }
                if (spread != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        (if (spread >= 0) "+" else "") +
                            Fmt.pct(spread).removeSuffix("%") + " pts vs " + compareLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = signColor(spread),
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            buildString {
                if (cmp != null) {
                    // SAID BEFORE THE WINDOW, because it changes what every number on the
                    // chart means. A reader who misses this reads percentages as prices.
                    append("Percent change, both lines from the same start  -  ")
                }
                append(range.caption)
                append("  -  ")
                append(shown.points.size)
                append(if (shown.points.size == 1) " point" else " points")
                if (onZoom != null) {
                    // DISCOVERABILITY, in four words. A gesture nothing on screen mentions is
                    // a gesture nobody finds, and this caption is already the line that says
                    // what the chart is showing.
                    append("  -  pinch to zoom")
                }
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
    scrub: MutableIntState,
    /** Both percent series when comparing, null for the ordinary price readout. */
    cmp: ComparePair? = null,
    compareLabel: String = BENCHMARK_SYMBOL
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
                color = muted,
                maxLines = 1
            )
            // ---- AND WHAT THE MARKET DID OVER THE SAME WINDOW.
            //
            // The whole reason for the overlay, in one line: the difference between the two
            // is what "beat the market" means, and reading it off two lines by eye is exactly
            // what people get wrong.
            // The benchmark AT THE LAST SHARED MOMENT, for the same reason the spread is -
            // see [ComparePair.pairedIndex].
            cmp?.pairedIndex()?.let { pi -> cmp.other[pi] }?.let { m ->
                Spacer(Modifier.weight(1f))
                Text(
                    "$compareLabel " + Fmt.pctSigned(m),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Benchmark,
                    maxLines = 1
                )
            }
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
            // THE BENCHMARK AT THE SAME MOMENT, not at the end of the window. Comparing a
            // scrubbed price against a whole-window benchmark figure would be comparing two
            // different moments and calling it out-performance.
            val atFinger = cmp?.other?.getOrNull(i)?.takeIf { it.isFinite() }
            if (atFinger != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "$compareLabel " + Fmt.pctSigned(atFinger),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Benchmark,
                    maxLines = 1
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

    // ---- KEEP THE SELECTED CHIP IN VIEW (Round 63).
    //
    // Eight chips do not fit across a phone, so this row scrolls - which was fine while the
    // only way to change range was to tap a chip you could already see. Pinch zoom changes
    // the selection WITHOUT touching this row, and a four-rung spread could leave the
    // highlighted chip well off the right-hand edge with the row still showing the window the
    // user started from. Each chip reports where it was laid out and the row scrolls the
    // selected one back into view.
    //
    // ONLY WHEN IT IS ACTUALLY OFF SCREEN. Re-centring a chip that is already visible would
    // make the row twitch on every tap, which is a worse fault than the one being fixed.
    val chipBounds = remember { mutableStateMapOf<ChartRange, IntArray>() }
    val selectedBounds = chipBounds[selected]
    LaunchedEffect(selected, selectedBounds, scroll.maxValue) {
        val b = selectedBounds ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0 || scroll.maxValue <= 0) return@LaunchedEffect
        val x = b[0]
        val w = b[1]
        val lead = 16
        val target = when {
            x < scroll.value -> x - lead
            x + w > scroll.value + viewport -> x + w - viewport + lead
            else -> return@LaunchedEffect
        }
        runCatching { scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue)) }
    }

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
                    // Where this chip sits inside the scrolling row, so the row can bring it
                    // back into view when a pinch selects it. Written only when it MOVES -
                    // an unconditional write in a layout callback re-enters composition on
                    // every frame.
                    .onGloballyPositioned { co ->
                        val x = co.positionInParent().x.toInt()
                        val w = co.size.width
                        val had = chipBounds[r]
                        if (had == null || had[0] != x || had[1] != w) {
                            chipBounds[r] = intArrayOf(x, w)
                        }
                    }
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

/**
 * How old a cached series may be and still put a figure on a chip.
 *
 * A day, which is also the TTL of the longest ranges - so for those "fresh enough to print"
 * and "fresh enough not to re-fetch" are the same line. See [rangePct].
 */
internal const val FIGURE_MAX_AGE_MS = 24 * 3_600_000L

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
internal fun rangePct(
    series: ChartSeries?,
    livePrice: Double,
    liveEdge: Boolean,
    now: Long = System.currentTimeMillis()
): Double? {
    if (series == null || series.isEmpty) return null

    // ---- TWO THINGS A CHIP IS NOT ALLOWED TO CLAIM.
    //
    // The chart can be drawn from a cache row of any age and from a window shorter than the
    // one asked for, because it SAYS SO: it prints the first and last date on its axis and
    // spells out "this is the whole history on record, which is shorter than 5Y" underneath.
    // A chip is a two-character label and a number. It has nowhere to put a caveat, so where
    // the chart would caption one, the chip shows nothing instead.
    //
    //   1. AGE. A cached 1M series that was fetched last week describes last week's month.
    //      Inside a day the figure is worth having and the error is at most the part of a
    //      session it has not seen; past that it is a different window wearing the same
    //      label. A stamp of zero is an unknown age, which is not good enough either.
    //   2. TRUNCATION. A stock that listed eighteen months ago has no five-year window, and
    //      "+40%" under a button marked 5Y is a claim about five years.
    //
    // Neither costs anything to recover: selecting the range fetches it, and the chip fills
    // in from the same answer the chart draws.
    if (series.truncated) return null
    if (series.fetched <= 0L || now - series.fetched > FIGURE_MAX_AGE_MS) return null

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
    modifier: Modifier,
    /** Both percent series when comparing; null draws the ordinary price chart. */
    cmp: ComparePair? = null,
    benchmarkColor: Color = Benchmark
) {
    val pts = s.points
    // ---- THE Y SCALE. Two different questions, so two different answers.
    //
    // In price mode the axis spans the series' own high and low, widened to include the
    // dotted previous-close baseline so that line is always visible. In comparison mode it
    // spans BOTH percent series together and always includes zero - the zero line is the
    // whole reference and an axis that excluded it would draw two lines with nothing to
    // measure them against.
    val base = if (cmp != null) null else s.baseline.takeIf { it > 0.0 }
    // ONE FUNCTION FOR BOTH THE SCALE AND THE LABELS ([priceBounds]), so the number printed
    // at the top of the axis is by construction the value drawn there.
    val bounds = cmp?.bounds() ?: priceBounds(s)
    val lo = bounds[0]
    val hi = bounds[1]
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

        // WHAT EACH POINT IS WORTH ON THE AXIS. Price normally; percent change when a
        // benchmark is drawn beside it. One function, so the fill, the stroke and the
        // crosshair below cannot end up reading different scales.
        fun value(i: Int): Double = cmp?.own?.get(i) ?: pts[i].close

        // ---- THE ZERO LINE, in comparison mode only. It replaces the dotted previous-close
        // baseline rather than joining it, and it is what both lines are measured from.
        //
        // DASHED, LIKE THAT BASELINE, AND NOT LIKE THE GRIDLINES. Drawn solid in the grid
        // colour it was a third horizontal line among three, distinguishable only by being
        // slightly darker - and on a chart where the stock is below the market the zero line
        // lands near the top, right beside a real gridline, exactly where the difference
        // matters most. A dash pattern is unmistakable at a glance and needs no colour.
        if (cmp != null && 0.0 in lo..hi) {
            val zy = y(0.0)
            val dash = 4.dp.toPx()
            val gap = 4.dp.toPx()
            var zx = 0f
            while (zx < w) {
                drawLine(
                    Color.Gray.copy(alpha = 0.75f),
                    Offset(zx, zy), Offset(minOf(zx + dash, w), zy), strokeWidth = baseStroke
                )
                zx += dash + gap
            }
        }

        val path = Path().apply {
            moveTo(x(pts[0]), y(value(0)))
            for (i in 1 until pts.size) lineTo(x(pts[i]), y(value(i)))
        }
        // NO FILL UNDER A COMPARED LINE. The gradient reads as "area", and an area under a
        // percentage line that crosses zero is meaningless - worse, it would be painted over
        // the benchmark line wherever the two cross.
        if (cmp == null) {
            drawPath(
                Path().apply {
                    addPath(path)
                    lineTo(x(pts.last()), h)
                    lineTo(x(pts.first()), h)
                    close()
                },
                Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0f)))
            )
        }

        // ---- THE BENCHMARK, DRAWN FIRST AND THINNER, so the stock stays the subject of its
        // own chart. Broken into segments at every gap: the benchmark can be missing points
        // the stock has - a fund that did not trade in an extended session, a stock that
        // listed mid-window - and joining across a gap would draw a straight line through
        // days that were never measured.
        cmp?.let { c ->
            var started = false
            var seg = Path()
            for (i in pts.indices) {
                val v = c.other[i]
                if (!v.isFinite()) {
                    if (started) {
                        drawPath(
                            seg, benchmarkColor.copy(alpha = 0.85f),
                            style = Stroke(width = baseStroke * 1.4f, cap = StrokeCap.Round)
                        )
                        seg = Path()
                        started = false
                    }
                    continue
                }
                if (!started) { seg.moveTo(x(pts[i]), y(v)); started = true }
                else seg.lineTo(x(pts[i]), y(v))
            }
            if (started) drawPath(
                seg, benchmarkColor.copy(alpha = 0.85f),
                style = Stroke(width = baseStroke * 1.4f, cap = StrokeCap.Round)
            )
        }

        drawPath(
            path, line,
            style = Stroke(width = lineStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // ---- the crosshair, drawn last so it sits over the line
        val i = scrub.intValue
        if (i in pts.indices) {
            val p = pts[i]
            val px = x(p)
            val py = y(value(i))
            drawLine(
                grid.copy(alpha = 0.9f),
                Offset(px, 0f), Offset(px, h),
                strokeWidth = lineStroke
            )
            // A ring in the surface colour under the dot, so it reads clearly wherever it
            // lands - over the filled gradient, over a gridline, or over the line itself.
            drawCircle(dot, radius = 5.dp.toPx(), center = Offset(px, py))
            drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(px, py))
            // A second, smaller dot on the benchmark, so the crosshair reads BOTH lines at
            // the moment under the finger rather than only one of them.
            cmp?.other?.getOrNull(i)?.takeIf { it.isFinite() }?.let { v ->
                val by2 = y(v)
                drawCircle(dot, radius = 4.dp.toPx(), center = Offset(px, by2))
                drawCircle(benchmarkColor, radius = 2.5.dp.toPx(), center = Offset(px, by2))
            }
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

// ------------------------------------------------------------------ the gestures

/**
 * How much the fingers have to spread before the chart drops one rung.
 *
 * 1.55 is chosen from the geometry rather than by taste: on a phone the comfortable range of
 * a two-finger spread is roughly 60dp to 280dp apart, a factor of about 4.6, and 1.55^3 is
 * 3.7 - so one full, unhurried spread crosses three rungs and a deliberate one crosses four.
 * That is what "zoom in gradually" has to mean on a seven-rung ladder: the whole ladder in
 * two gestures, not one, and not seven.
 *
 * Smaller than this and the chart flickers through three ranges before the fingers have
 * really moved; larger and the far ends of the ladder cannot be reached with human hands.
 */
private const val ZOOM_STEP = 1.55f

/**
 * ONE POINTER LOOP FOR SCRUBBING AND ZOOMING.
 *
 * The two gestures share a surface, so they have to share a decision. The rule is simply the
 * number of fingers down: one scrubs, two or more zoom, and once a gesture has become a zoom
 * it stays one until every finger is lifted - a pinch that ends with one finger still on the
 * glass must not turn into a scrub as the other leaves.
 *
 * WHAT IS CONSUMED, AND WHEN. Nothing at all until the gesture has been identified:
 *
 *   * a scrub consumes only after the horizontal touch slop is passed, so a vertical swipe
 *     starting on the chart still scrolls the page it sits in - the chart is 170dp of a
 *     scrolling screen and making that a dead zone would be worse than the feature is good;
 *   * a zoom consumes from the second finger down, immediately, because there is no such
 *     thing as an accidental two-finger drag and the list underneath must not scroll while
 *     the chart is being pinched.
 *
 * TOTAL BY CONSTRUCTION. This runs in a gesture handler, where an exception is a crash on a
 * screen the user is touching: a degenerate series, a zero width and a non-finite zoom factor
 * are all handled by doing nothing rather than by throwing.
 */
internal suspend fun PointerInputScope.chartGestures(
    pointAt: (Float) -> Unit,
    clearPoint: () -> Unit,
    /**
     * The zoom callback, as a PROVIDER read at gesture time rather than a value captured
     * once. `pointerInput(Unit)` runs its block exactly once for the life of the node, so a
     * plain parameter here silently pins whatever the callback was on first composition -
     * which defeats the `rememberUpdatedState` the caller uses and, when the node is reused
     * for another symbol, leaves the pinch writing into the previous screen's state.
     * Null means "no zoom on this chart".
     */
    zoom: () -> ((Int) -> Unit)?,
    onZoomActive: (Boolean) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        var mode = GestureMode.UNDECIDED
        var dx = 0f
        var zoomAccum = 1f
        // The two fingers this pinch is being measured between. Re-chosen whenever one of
        // them leaves, so a third finger landing or one of two lifting costs a single frame
        // rather than an arbitrary jump. See [zoomFactor].
        var pair: Pair<androidx.compose.ui.input.pointer.PointerId,
            androidx.compose.ui.input.pointer.PointerId>? = null
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break

                val onZoomStep = zoom()
                if (pressed > 1 && onZoomStep != null) {
                    if (mode != GestureMode.ZOOM) {
                        mode = GestureMode.ZOOM
                        // A crosshair left behind by the finger that started as a scrub would
                        // sit frozen on the line for the whole pinch.
                        clearPoint()
                        zoomAccum = 1f
                        pair = null
                        onZoomActive(true)
                    }
                    event.changes.forEach { if (it.pressed) it.consume() }
                    // Measure between the pair chosen when the pinch began; if either finger
                    // has gone, adopt a new pair and take no reading from this frame - the
                    // accumulator keeps whatever it had, so the gesture continues smoothly
                    // from wherever the remaining fingers now are.
                    val z = pair?.let { (a, b) -> zoomFactor(event, a, b) }
                    if (z == null) {
                        pair = zoomPair(event)
                        continue
                    }
                    zoomAccum *= z
                    if (!zoomAccum.isFinite() || zoomAccum <= 0f) zoomAccum = 1f
                    // A LOOP, NOT AN `if`. A fast spread can cross two rungs between two
                    // frames, and swallowing the second one makes the zoom feel like it is
                    // lagging the fingers. The accumulator keeps the remainder either way, so
                    // the ladder is walked at exactly the rate the hands move.
                    var steps = 0
                    while (zoomAccum >= ZOOM_STEP) { steps++; zoomAccum /= ZOOM_STEP }
                    while (zoomAccum <= 1f / ZOOM_STEP) { steps--; zoomAccum *= ZOOM_STEP }
                    if (steps != 0) onZoomStep(steps)
                    continue
                }

                if (mode == GestureMode.ZOOM) {
                    // Down to one finger, but this is still the tail of a pinch. Keep
                    // consuming so the list below does not suddenly start scrolling.
                    event.changes.forEach { if (it.pressed) it.consume() }
                    continue
                }

                val change = event.changes.firstOrNull { it.pressed } ?: break
                if (change.isConsumed && mode == GestureMode.UNDECIDED) {
                    // Somebody inside claimed it first. Leave it alone.
                    break
                }
                dx += change.positionChange().x
                if (mode == GestureMode.UNDECIDED && kotlin.math.abs(dx) > slop) {
                    mode = GestureMode.SCRUB
                }
                if (mode == GestureMode.SCRUB) {
                    change.consume()
                    pointAt(change.position.x)
                }
            }
        } finally {
            // In a `finally` because this loop can be cancelled - the composable leaving the
            // screen mid-drag - and a crosshair or a zoom badge stranded on by a cancellation
            // never comes off.
            clearPoint()
            if (mode == GestureMode.ZOOM) onZoomActive(false)
        }
    }
}

private enum class GestureMode { UNDECIDED, SCRUB, ZOOM }

/**
 * How much wider a NAMED PAIR of fingers got between the previous frame and this one, or null
 * when that pair is no longer both on the glass.
 *
 * ---- WHY THE PAIR IS NAMED RATHER THAN TAKEN POSITIONALLY
 *
 * The obvious form reads `event.changes[0]` and `[1]`. That list is reshuffled the moment a
 * third finger lands or one of the first two lifts, so the "previous" separation can belong
 * to a different pair of fingers than the current one. The ratio that falls out is arbitrary -
 * and because the caller accumulates it, an arbitrary ratio is spent as several real zoom
 * rungs: a stray third finger resting on the glass would jump the chart from a month to five
 * years. Returning null instead lets the caller re-pair and simply skip a frame.
 *
 * ---- AND WHY THE TWO POINTERS ARE MEASURED AGAINST EACH OTHER
 *
 * Compose ships `PointerEvent.calculateZoom()`, which averages every pointer's distance from
 * the centroid. That is right for a canvas being scaled by three fingers and wrong here: for
 * the very common two-finger pinch where one thumb stays put and only the index finger moves,
 * the centroid moves with the finger and the function returns 1.0 - "no zoom" - for a gesture
 * that is plainly a zoom.
 *
 * Returns null rather than 1f for a separation too small to divide by, so the caller can tell
 * "the fingers did not move" from "there is nothing here to measure".
 */
internal fun zoomFactor(
    event: androidx.compose.ui.input.pointer.PointerEvent,
    a: androidx.compose.ui.input.pointer.PointerId,
    b: androidx.compose.ui.input.pointer.PointerId
): Float? {
    val pa = event.changes.firstOrNull { it.id == a && it.pressed } ?: return null
    val pb = event.changes.firstOrNull { it.id == b && it.pressed } ?: return null
    val now = (pa.position - pb.position).getDistance()
    val was = (pa.previousPosition - pb.previousPosition).getDistance()
    if (was < 1f || now < 1f) return null
    val f = now / was
    return if (f.isFinite() && f > 0f) f else null
}

/** The first two fingers currently down, or null when there are not two. */
internal fun zoomPair(
    event: androidx.compose.ui.input.pointer.PointerEvent
): Pair<androidx.compose.ui.input.pointer.PointerId,
    androidx.compose.ui.input.pointer.PointerId>? {
    val pressed = event.changes.filter { it.pressed }
    return if (pressed.size < 2) null else pressed[0].id to pressed[1].id
}

// ------------------------------------------------------- the comparison overlay

/**
 * THE BENCHMARK, and the one place its ticker is written down.
 *
 * SPY rather than ^GSPC: the index itself is not tradeable, its ticker needs escaping in a
 * URL, and the app's chart cache is keyed by symbol - so using the fund means the overlay
 * shares the exact cache, TTL, disk row and retry clock every other chart already uses,
 * rather than needing a parallel path for one special case.
 */
const val BENCHMARK_SYMBOL = "SPY"

/**
 * A SECOND LINE, MEASURED THE SAME WAY AS THE FIRST (Round 63).
 *
 * "Did this stock beat the market?" is not a question a price chart can answer, because two
 * prices in dollars share no axis - a $900 stock and a $600 fund drawn together are one line
 * and one flat streak at the bottom. The only honest way to draw them together is to draw
 * neither in dollars: both become PERCENTAGE CHANGE from the same moment, and the axis
 * becomes a percentage.
 *
 * ---- WHAT "THE SAME MOMENT" MEANS, AND WHY IT IS NOT SIMPLY THE FIRST POINT
 *
 * Two cases, and getting the second one wrong is the whole trap:
 *
 *   * **Intraday** (1D, after-hours). Both series are measured from their own PREVIOUS
 *     CLOSE, which is what [ChartSeries.from] already returns for these ranges and what the
 *     readout and the range chips already print. So the stock's number here is exactly the
 *     number shown above the chart - the overlay adds a line, it does not change one.
 *   * **Everything longer.** The benchmark is rebased to ITS OWN VALUE AT THE START OF THE
 *     STOCK'S WINDOW, not to the start of its own series. On a five-year chart of a company
 *     that listed eighteen months ago, the stock's line covers eighteen months and SPY's
 *     covers thirty years; drawn from its own first point SPY would show several hundred
 *     percent against the stock's forty, and the chart would say the stock had been
 *     annihilated by a benchmark it had never been measured against.
 *
 * Returns null - draw no overlay at all - whenever the answer would be a guess: too few
 * points, no usable baseline, or a benchmark series that does not reach the window being
 * drawn. A missing overlay is a small disappointment; a wrong one is a false claim about
 * performance.
 */
internal fun comparePercents(primary: ChartSeries?, compare: ChartSeries?): DoubleArray? {
    if (primary == null || compare == null) return null
    if (primary.isEmpty || compare.isEmpty) return null
    val pts = primary.points
    val cs = compare.points

    val base = if (primary.range == ChartRange.D1 || primary.range == ChartRange.OVERNIGHT) {
        // Both lines measured from their own previous close - the same reference the readout
        // and the chips use for an intraday window.
        compare.from
    } else {
        // Rebased to where the benchmark stood when THIS window opened.
        valueAtOrBefore(cs, pts.first().t) ?: cs.first().close
    }
    if (base <= 0.0 || !base.isFinite()) return null

    // ---- THE TWO RIGHT-HAND TIPS ARE THE SAME MOMENT: "NOW".
    //
    // Every benchmark value is looked up by the STOCK's timestamp, which is right everywhere
    // except at the very last point of an intraday line. There, both lines mean "now" - the
    // stock's tip has been replaced with its live quote and so has the benchmark's - but if
    // the benchmark's final candle is stamped LATER than the stock's (a thinly traded stock
    // whose 15:55 candle was empty and skipped, against SPY's 15:55), `valueAtOrBefore`
    // returns the benchmark's SECOND-to-last point and the live price written into its tip is
    // never read. The out-performance figure would then be a difference between two moments
    // up to five minutes apart, which is exactly what the live edge exists to prevent.
    //
    // Only for the intraday ranges, and deliberately so: on a five-year monthly line a
    // benchmark whose series runs a month past a delisted stock's is not "now", it is a month
    // of trading the stock was not present for, and pairing them would invent a comparison.
    val pairTips = primary.range.intraday
    val lastIndex = pts.lastIndex
    val out = DoubleArray(pts.size)
    var drawn = 0
    for (i in pts.indices) {
        val v = if (pairTips && i == lastIndex && cs.last().t > pts[i].t) cs.last().close
        else valueAtOrBefore(cs, pts[i].t)
        if (v == null || v <= 0.0) {
            // NaN, not zero. Zero is a real percentage - "the benchmark was flat here" - and
            // painting it where there is simply no data invents a horizontal line.
            out[i] = Double.NaN
        } else {
            out[i] = (v - base) / base * 100.0
            if (out[i].isFinite()) drawn++ else out[i] = Double.NaN
        }
    }
    // Two points is the minimum that can be a line rather than a dot.
    return if (drawn >= 2) out else null
}

/**
 * The last close at or before [t], or null when the series begins after it.
 *
 * A binary search: this is called once per point of the primary series, which on a five-day
 * chart is ~900 lookups into a ~900-point benchmark. Linear would be ~400,000 comparisons
 * every time the overlay is rebuilt, which happens on every quote tick while the market is
 * open.
 *
 * AT OR BEFORE, never after: a benchmark value from the future of the point being drawn would
 * put tomorrow's market move under today's price.
 */
internal fun valueAtOrBefore(points: List<ChartPoint>, t: Long): Double? {
    if (points.isEmpty()) return null
    if (t < points.first().t) return null
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        // Upper-biased midpoint: this searches for the LAST index whose t <= target, and the
        // ordinary `(lo + hi) / 2` form loops forever on that variant when hi == lo + 1.
        val mid = (lo + hi + 1) ushr 1
        if (points[mid].t <= t) lo = mid else hi = mid - 1
    }
    return points[lo].close
}

/** What the stock itself did at each point, on the same percentage scale as the overlay. */
internal fun primaryPercents(s: ChartSeries): DoubleArray? {
    if (s.isEmpty) return null
    val from = s.from
    if (from <= 0.0 || !from.isFinite()) return null
    val out = DoubleArray(s.points.size)
    for (i in s.points.indices) {
        val v = (s.points[i].close - from) / from * 100.0
        out[i] = if (v.isFinite()) v else Double.NaN
    }
    return out
}

/**
 * The two aligned percent series, held together because they are only ever meaningful
 * together: [own] is the stock, [other] the benchmark, both measured from the same moment and
 * both indexed by the STOCK's points, so index i is the same instant in either.
 *
 * [other] may contain NaN where the benchmark has no reading for one of the stock's points;
 * [own] never does, because a point with no price is not plotted at all.
 */
internal class ComparePair(val own: DoubleArray, val other: DoubleArray) {

    /**
     * The y-axis range: the lowest and highest value across BOTH lines, and always including
     * zero.
     *
     * Zero is included unconditionally because it is the reference the whole chart is about -
     * an axis running from +4% to +9% would draw two lines with nothing on screen saying that
     * both are up. A little wasted vertical space is the correct price for that.
     */
    fun bounds(): DoubleArray {
        var lo = 0.0
        var hi = 0.0
        for (v in own) if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
        for (v in other) if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
        // A dead-flat pair would give a zero-height axis and divide by nothing.
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        return doubleArrayOf(lo, hi)
    }

    /**
     * The last index at which BOTH lines have a reading, or null when there is none.
     *
     * `other` carries NaN wherever the benchmark has no value for one of the stock's points,
     * and `own` never does - so taking each series' own final figure compares two different
     * moments. Every number this class publishes is read at this one index instead.
     */
    fun pairedIndex(): Int? {
        val n = minOf(own.size, other.size)
        for (i in n - 1 downTo 0) if (own[i].isFinite() && other[i].isFinite()) return i
        return null
    }

    /**
     * How far ahead of the benchmark the stock is, in PERCENTAGE POINTS, at the last moment
     * both were measured. Null when they were never measured together.
     *
     * `+ 0.0` normalises a negative zero: without it a dead-level pair formats as "+-0.00",
     * because the sign is chosen before the number is formatted. The same guard `rangePct`
     * carries, for the same reason.
     */
    fun spread(): Double? {
        val i = pairedIndex() ?: return null
        val d = own[i] - other[i]
        return if (d.isFinite()) d + 0.0 else null
    }
}

/**
 * The y-axis range for an ordinary price chart: the series' own high and low, WIDENED to
 * include the dotted previous-close baseline so that line is always on screen.
 *
 * Its own function because the canvas and the corner labels both need it and must not be able
 * to disagree - which they did: the canvas widened the scale and the labels printed the
 * series' extremes, so on a gap-down day the top of the axis and the label pinned to it were
 * different numbers.
 */
internal fun priceBounds(s: ChartSeries): DoubleArray {
    val base = s.baseline.takeIf { it > 0.0 }
    val lo = minOf(s.low, base ?: s.low)
    val hi = maxOf(s.high, base ?: s.high)
    return doubleArrayOf(lo, hi)
}

/** A filled dot for the legend, at text size. */
@Composable
private fun LegendDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape)
    )
}
