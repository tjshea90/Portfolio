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
import androidx.compose.foundation.layout.requiredHeightIn
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.data.approxSpanMs
import com.tj.portfolio.data.candleMs
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
     * THE CONTINUOUS ZOOM (Round 64).
     *
     * [window] is the stretch of time on screen and [onWindow] is how the chart reports a
     * pinch or a pan back. Together they replace [onZoom]'s rung-stepping: the window scales
     * by whatever factor the fingers moved, and the CALLER decides - from the span - which
     * series to fetch behind it. Null leaves the chart exactly as it was, drawing the whole
     * series, which is what every caller that has no range selector wants.
     *
     * [windowBounds] is how far a zoom may go, built from every series the app holds for this
     * symbol so pinching out past the end of a one-month chart continues into the five-year
     * one instead of stopping at a boundary the user cannot see.
     */
    window: ChartWindow? = null,
    windowBounds: ChartWindow? = null,
    onWindow: ((ChartWindow) -> Unit)? = null,
    /**
     * Undo the zoom: go back to drawing the whole of the fetched series.
     *
     * SEPARATE FROM [onWindow] because "reset" is not a window at all - it is the ABSENCE of
     * one, and the caller is the only thing that can express that. Passing `windowBounds` back
     * through `onWindow` instead looked equivalent and was not: those bounds are how far a
     * pinch may reach, which is deliberately wider than the data.
     */
    onResetWindow: (() -> Unit)? = null,
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
    compareLivePrice: Double = 0.0,
    /**
     * How tall the drawn area is (Round 64).
     *
     * A PARAMETER because the full-screen viewer is the same chart at a different size, and
     * two copies of this composable that could drift apart is exactly the kind of duplication
     * that has cost this project rounds. The default is the 170dp the detail screen has always
     * used, so every existing caller is unchanged.
     */
    chartHeight: Dp = CHART_HEIGHT.dp,
    /**
     * Give the drawn area whatever height is left over, instead of [chartHeight].
     *
     * FOR THE FULL-SCREEN VIEWER, and it replaces the arithmetic that was there first: that
     * subtracted a fixed 150dp for the header, chips, readout, axis and caption, which had no
     * slack in it at all. Turn the SPY overlay on and its legend row pushed the caption off
     * the bottom; raise the font scale and the axis labels went with it. Measuring is not a
     * refinement here - a constant in dp cannot describe a stack of text in sp.
     *
     * The caller must give this composable a bounded height for it to divide up.
     */
    chartFillsHeight: Boolean = false,
    /**
     * Tap-to-open-full-screen, or null for a chart that has nowhere to open to.
     *
     * TJ: *"for any chart anywhere in the app, make it so I can press it and it opens full
     * screen and can rotate landscape or portrait with the phone sensors."*
     *
     * AN EXPLICIT BUTTON RATHER THAN A TAP ON THE CANVAS. The canvas already belongs to the
     * crosshair: a tap there is the beginning of a scrub, and the two cannot share it without
     * one of them becoming unreliable - either the crosshair flickers on every tap, or the
     * expand needs a long-press, which nothing on this screen would advertise.
     */
    onExpand: (() -> Unit)? = null,
    /**
     * Called with true while a gesture owns the chart's window - a pinch, or the one-finger
     * pan round 65 added - and false when the fingers leave.
     *
     * SO THE CALLER CAN STAND BACK. The screen re-anchors the zoom window whenever new data
     * arrives, and a fetch landing mid-gesture would otherwise reset the window under the
     * fingers for a frame before the gesture wrote it again.
     */
    onZoomingChanged: ((Boolean) -> Unit)? = null
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
    // ---- WHAT IS ACTUALLY ON SCREEN, AND WHAT THE X-AXIS IS (Round 64).
    //
    // `shown` is the whole fetched series; `drawnAll` is the part inside the zoom window plus
    // one point past each edge so the line reaches both sides (see [clipToWindow]).
    // `axisWindow` is what x is scaled to - the WINDOW, not the clipped points' own extent, or
    // those edge points would stretch the scale and the zoom would not line up with the
    // fingers that placed it.
    //
    // With no window both are the identity: `clipToWindow` returns the series unchanged and
    // the axis is its own span, so an unzoomed chart is the one that shipped in v7.4.
    //
    // The percentages come out right on their own, which is worth stating because it looks
    // like luck: `ChartSeries.from` is the baseline when there is one and the first point
    // otherwise - so a zoomed 1D line still measures from yesterday's close, and a zoomed
    // six-month line measures from the first point ON SCREEN, which is what a reader of a
    // zoomed chart means by "over this window".
    //
    // ABOVE THE EMPTY-STATE BRANCH, like the gesture state below it and for the same reason:
    // the pinch handler reads both, and a pinch has to survive a window whose series has not
    // been fetched yet.
    val drawnAll = remember(shown, window) { clipToWindow(shown, window, pad = true) }
    // WHAT IS MEASURED, as opposed to what is drawn: the same clip WITHOUT the carried edge
    // points, so every figure printed anywhere on this chart describes the picture the user is
    // looking at. The two are the same object when nothing is zoomed.
    // ---- IS THIS THE DEFAULT VIEW, OR HAS THE USER MOVED IT? ONE ANSWER (sweep 4).
    //
    // THE BUG THIS FIXES, and it is the reason this is computed once and read everywhere.
    // The measurements were switched to "the window's own first point" on `window != null`,
    // while every LABEL that explains what those measurements mean switched on `zoomedIn` -
    // and the two disagree for any window WIDER than the series, which is what one pinch
    // outward on a 1D chart produces. The readout then quietly measured from the session's
    // open while still printing "since yesterday's close" beside it, disagreed with the price
    // block above it and with its own range chip, and offered no reset chip to escape with.
    //
    // EITHER DIRECTION COUNTS. A window wider than the line is as much "not the default view"
    // as a narrower one: it is what a pinch-out looks like while the wider series is still
    // being fetched, and it needs the same caption, the same baseline rule and the same way
    // back out.
    val seriesWindowAll = remember(shown) {
        shown?.takeIf { it.points.size >= 2 }?.let { ChartWindow(it.startMs, it.endMs) }
    }
    val movedAll = remember(window, seriesWindowAll) {
        window != null && !ChartWindow.isDefaultView(window, seriesWindowAll)
    }
    // ---- CAN A DRAG MOVE THE WINDOW? ONE ANSWER, READ BY THE GESTURE AND BY THE CAPTION.
    //
    // Round 65 gives a zoomed chart a one-finger pan, and a caption that names it. Those were
    // two separate tests in the first draft and they disagreed (review M03): the caption
    // offered "drag to move" whenever the chart was zoomed at all, while the pan itself
    // refuses to move a window that is not NARROWER than the line - there is nothing to pan
    // over when the whole series is already on screen. So the chart promised a gesture that
    // did nothing, which is worse than not mentioning it.
    //
    // There is one definition now and everything reads it: the caption, the choice between
    // panning and scrubbing, and the pan guard itself.
    // `onWindow != null` IS PART OF THE TEST (sweep 2, N05). Both `window` and `onWindow` are
    // optional parameters, so a caller can legally hand this chart a window with nowhere to
    // report a new one - and without this clause such a chart captioned "drag to move" and
    // armed the hold while the pan callback was null and the drag scrubbed. That is M03 again,
    // approached from the other side: the caption and the gesture must be one predicate, and
    // the predicate has to include everything the gesture needs.
    // `windowBounds != null` IS ALSO PART OF IT (Round 66 audit, CHT-4). Same shape as N05,
    // one parameter further along: `windowBounds` is optional too, and `pan()` returns null
    // without it. So a chart given `window` and `onWindow` but no bounds captioned "drag to
    // move, hold to scrub", armed the 350ms hold - which consumes from the moment it fires,
    // before the touch slop, so a press that pauses and then turns into a page scroll stopped
    // the page dead - and then scrubbed when the drag was finally decided. One predicate has
    // to mean one thing, and it has to include EVERYTHING the gesture needs.
    val canReportWindow = onWindow != null
    val canPanNow = remember(window, seriesWindowAll, canReportWindow, windowBounds) {
        canReportWindow && windowBounds != null && window != null && seriesWindowAll != null &&
            window.spanMs < seriesWindowAll.spanMs
    }
    val axisWindow = remember(drawnAll, window) {
        window ?: drawnAll?.let { ChartWindow(it.startMs, it.endMs) }
    }

    val scrub = remember { mutableIntStateOf(NO_SCRUB) }
    // THE DRAWN SERIES, NOT THE WHOLE ONE. `scrub` is an index into whatever the canvas
    // drew, and on a zoomed chart that is the clipped list - indexing the full series would
    // put the crosshair on a point that is not on screen.
    val liveSeries = rememberUpdatedState(drawnAll)
    val liveAxis = rememberUpdatedState(axisWindow)
    val liveZoom = rememberUpdatedState(onZoom)
    val liveOnWindow = rememberUpdatedState(onWindow)
    val liveWindow = rememberUpdatedState(window)
    val liveBounds = rememberUpdatedState(windowBounds)
    // The fetched series' own extent, which is what "unzoomed" means to the person looking
    // at it. See the pan guard and `zoomedIn`.
    val liveZoomingChanged = rememberUpdatedState(onZoomingChanged)
    val liveSeriesWindow = rememberUpdatedState(
        shown?.takeIf { it.points.size >= 2 }?.let { ChartWindow(it.startMs, it.endMs) }
    )
    val liveCanPan = rememberUpdatedState(canPanNow)
    // The double-tap's reset: only while the chart is moved from its default view (idea 8).
    val liveReset = rememberUpdatedState(if (movedAll) onResetWindow else null)
    // Captured here because a pointer handler is not a composition and cannot read a
    // CompositionLocal; the tick itself is fired from inside the gesture.
    val haptics = LocalHapticFeedback.current
    // TWO FLAGS, NOT ONE (round 65). `zooming` means "two fingers are resizing the window" and
    // is what the span badge follows - a one-finger pan does not change the span, so a badge
    // naming it would be noise. `gestureLive` means "some gesture owns the window right now"
    // and is what the Reset chip and the caller's stand-back flag follow: the chip used to sit
    // under the finger through a whole pan (review M08).
    var zooming by remember { mutableStateOf(false) }
    var gestureLive by remember { mutableStateOf(false) }
    var zoomSpan by remember { mutableStateOf(0L) }

    // ---- THE WINDOW THE FINGERS ARE WORKING FROM, HELD OUTSIDE COMPOSITION.
    //
    // A pinch produces several pointer frames per recomposition. Reading `window` back on
    // every frame would therefore apply each frame's factor to the SAME stale window and
    // throw most of the gesture away - the zoom would move in visible jerks, which is the
    // exact complaint this round exists to fix. The gesture keeps its own running window
    // here, seeded when the pinch begins, and the hoisted state is told about each result.
    //
    // A plain holder rather than `mutableStateOf`: writing Compose state from a pointer
    // handler would schedule a recomposition per frame for a value composition never reads.
    val held = remember { WindowHold() }

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
                        val axis = liveAxis.value
                        val idx = nearestIndex(pts, x / w, axis)
                        // ---- NOTHING UNDER THE FINGER IS AN ANSWER (Round 66 audit, CHT-3).
                        //
                        // THE BUG THIS FIXES. `nearestIndex`'s on-screen clamp has a fallback:
                        // when NO point falls inside the axis it returns the nearest one
                        // anyway, "which is all there is". That case is reachable - zoom into
                        // the middle of a 5Y (weekly) or All (monthly) chart and the window can
                        // land entirely between two candles, since `MIN_SPAN_MS` is thirty
                        // minutes. The readout then printed a price and a date from a candle
                        // WEEKS outside the window, while the crosshair meant to show where
                        // that price came from was drawn at an x hundreds of screen-widths away
                        // and clipped off the canvas. A number under the finger, a date
                        // contradicting the axis right below it, and nothing marking the spot.
                        //
                        // A point outside the axis is not a point the reader is touching, so
                        // the honest answer is the one both halves already understand: no
                        // scrub. The readout falls back to the live price and the crosshair
                        // draws nothing, which is what the chart looks like before a finger
                        // lands at all.
                        //
                        // COMPARED IN SECONDS, THE SAME UNIT `nearestIndex` CLAMPS IN (Round
                        // 66 audit, REG-6 - a regression in this guard's own first draft).
                        // The clamp truncates the axis to whole seconds (`startMs / 1000L`);
                        // this compared the point's millisecond timestamp against the raw
                        // bounds. After any pan or zoom `startMs` is almost never a whole
                        // second, so the two disagreed by up to 999ms at each edge and this
                        // guard refused a leftmost-candle scrub that the clamp had just
                        // accepted - a legitimate reading, rejected, a few times per session.
                        scrub.intValue = if (axis == null) idx else {
                            val tSec = pts[idx].t
                            if (tSec in (axis.startMs / 1000L)..(axis.endMs / 1000L)) idx
                            else NO_SCRUB
                        }
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
                // ---- THE CONTINUOUS ZOOM AND PAN (Round 64).
                //
                // Providers, for the same reason `zoom` is one: `pointerInput(Unit)` runs its
                // block once for the life of the node, so a captured lambda would be pinned to
                // whatever the callbacks were on first composition and would go on writing
                // into a previous screen's state when the node is reused.
                //
                // Both return null - which makes `chartGestures` fall back to the rung ladder,
                // or to nothing - whenever this chart has no window to move: no `onWindow`
                // from the caller, or no data yet to seed one from.
                pinch = {
                    val send = liveOnWindow.value
                    val b = liveBounds.value
                    if (send == null || b == null) null
                    else { factor: Float, focus: Float ->
                        val cur = held.window ?: liveWindow.value ?: liveAxis.value
                        if (cur != null) {
                            val next = ChartWindow.zoomed(cur, factor, focus, b)
                            held.window = next
                            zoomSpan = next.spanMs
                            send(next)
                        }
                    }
                },
                pan = {
                    val send = liveOnWindow.value
                    val b = liveBounds.value
                    if (send == null || b == null) null
                    else { byFraction: Float ->
                        val cur = held.window ?: liveWindow.value ?: liveAxis.value
                        // NOT WHILE THE WHOLE SERIES IS ON SCREEN, and measured against the
                        // SERIES rather than against the bounds (Round 64 sweep). The bounds
                        // are optimistic - up to forty years - so `panned`'s own guard stopped
                        // firing, and a two-finger drag on an unzoomed chart, which used to do
                        // nothing, dragged the window into decades of pre-history and blanked
                        // the line until a wider fetch landed.
                        // NOT WHILE THE SERIES IS UNKNOWN either (Round 64 sweep 3). Falling
                        // back to the bounds' span here made the guard permissive at exactly
                        // the wrong moment: during the load that a pinch has just started, a
                        // two-finger drag could slide the window into decades before the
                        // company listed. There is nothing to pan over until the line exists.
                        val seriesSpan = liveSeriesWindow.value?.spanMs
                        if (cur != null && seriesSpan != null && cur.spanMs < seriesSpan) {
                            val next = ChartWindow.panned(cur, byFraction, b)
                            held.window = next
                            send(next)
                        }
                    }
                },
                // ---- WHICH GESTURE OWNS THE WINDOW, IF ANY (round 65).
                //
                // One callback rather than two booleans, because the interesting event is a
                // TRANSITION - a one-finger pan that a second finger turns into a pinch - and
                // two independent flags cannot describe it without a moment where both are
                // wrong.
                canPan = { liveCanPan.value },
                // A press shows its value at once wherever a drag would scrub anyway (idea 2).
                pressShows = { !liveCanPan.value },
                doubleTap = { liveReset.value },
                onHold = {
                    // The tick that says "the chart has taken this gesture". Without it a
                    // press-and-hold on a zoomed chart is indistinguishable from a pan that
                    // has not started moving yet.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onGesture = { g ->
                    val active = g != ChartGesture.NONE
                    liveZoomingChanged.value?.invoke(active)
                    // ---- SEEDED ONCE PER GESTURE, AND NEVER RE-SEEDED MID-GESTURE.
                    //
                    // `liveWindow` is composition state, and composition lags a pointer loop
                    // that emits several frames per recomposition. Re-reading it when a second
                    // finger lands - which is what the first draft did, because pan and pinch
                    // each seeded on their own activation - handed the pinch a window one or
                    // more frames old and snapped the chart backwards at the exact moment the
                    // user added a finger (review M04). Keeping whatever the pan has already
                    // written is both simpler and correct: a pinch continues from where the
                    // pan left the window.
                    held.window =
                        if (!active) null
                        else held.window ?: liveWindow.value ?: liveAxis.value
                    zooming = g == ChartGesture.ZOOM
                    gestureLive = active
                    zoomSpan = if (g == ChartGesture.ZOOM) held.window?.spanMs ?: 0L else 0L
                }
            )
        }

    Column(modifier.fillMaxWidth()) {
        val plotSize =
            // A FLOOR UNDER THE WEIGHT (sweep 4). `weight` hands out what is left, and when
            // the fixed children out-measure the window - a short landscape window at a large
            // font scale, or split-screen - what is left is nothing, and the chart itself
            // disappeared while its caption stayed. Overflowing the bottom of a cramped window
            // is a far better failure than deleting the subject of the screen.
            // `requiredHeightIn`, NOT `heightIn` (sweep 5). `weight` hands the child FIXED
            // height constraints, and `heightIn` coerces its minimum into whatever it is
            // given - so against a share of zero it produced zero and the floor was inert.
            // The `required` form ignores the incoming constraints, which is the whole point:
            // overflowing the bottom of a cramped window is a far better failure than deleting
            // the subject of the screen.
            if (chartFillsHeight)
                Modifier.fillMaxWidth().weight(1f).requiredHeightIn(min = 120.dp)
            else Modifier.fillMaxWidth().height(chartHeight)
        if (shown == null || shown.isEmpty) {
            Box(
                plotSize
                    // FILL, don't just meet the floor (sweep 6). `requiredHeightIn` lets this
                    // Box be SHORTER than the height it was handed, and a Box seeds itself
                    // from its minimum - so in the full-screen viewer the placeholder measured
                    // 120dp with the rest of the screen blank, and the gesture surface that is
                    // deliberately kept alive here (see the note above) shrank with it, losing
                    // any pinch begun in the empty area.
                    .fillMaxSize()
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

        // ---- ONE GREEN, ONE RED, FOR THE WHOLE CHART.
        //
        // The line used the FILL palette while the readout, the chips and the spread used the
        // TEXT one, so the legend dot and the figure eight dp beside it were two different
        // greens describing the same line. On a 2.2dp stroke the darker value is not a
        // compromise either - `#16C784` on white is 2.20:1, which is faint for a line as well
        // as illegible for text. The fill palette stays where it belongs: the sparkline's
        // gradient, the badge and chip backgrounds.
        // `drawnAll` and `axisWindow` are computed above the empty-state branch because the
        // gesture handler needs them; here they are simply narrowed to non-null.
        val drawn = drawnAll ?: shown
        val axis = axisWindow ?: ChartWindow(drawn.startMs, drawn.endMs)
        // ---- WHAT COUNTS AS "ZOOMED", AND WHY IT IS NOT MEASURED AGAINST THE BOUNDS.
        //
        // `windowBounds` is how far a pinch may REACH, and since the sweep it is deliberately
        // optimistic - up to forty years, so a pinch-out can ask for a series that has not
        // been fetched yet. Measured against that, every window is "zoomed", including one
        // the user has pinched all the way back out: the chart looked exactly as it started
        // but had grown a "Reset zoom" chip that would not go away.
        //
        // What the user means by zoomed is "showing less than the whole line in front of me",
        // so that is what is asked. `seriesWindow` is the fetched series' own extent.
        val zoomedIn = movedAll

        // ---- THE COMPARISON ANCHOR IS THE SELECTED RANGE'S TRUE START - NEVER THE PANNED
        // WINDOW'S EDGE (round 79).
        //
        // Tj, again, with a second screen recording: *"if I move a stock chart by dragging
        // left or right when it is zoomed in, the spy baseline sometimes jumps up and down,
        // making it appear that sometimes the stock outperforms spy but when I move the
        // chart, suddenly the stock at the same point of time drops below spy."*
        //
        // Round 67's `frozenBaseT` (below, now removed) only froze the anchor WITHIN one
        // continuous drag and let it re-sync to the settled window's own first candle the
        // moment the finger lifted - which is a different day every time the window is
        // panned. Two lines each measured from a different day can genuinely swap which is
        // on top for the SAME calendar date on screen: that is not the same question asked
        // twice, it is two different questions ("since day A" and "since day B") that happen
        // to share an axis. "Is this stock beating SPY" only has one honest answer per date
        // if both lines are always measured from the SAME day - so the anchor is now always
        // the selected range's own true start, read from `shown` (the whole fetched series,
        // never re-sliced by a pinch or pan), regardless of how far the window has since
        // panned or zoomed. `intraday` ranges are untouched: [comparePercents] already
        // measures both lines from the previous close there, which it only does when the
        // anchor passed in is null, and that rule was never the bug.
        //
        // ---- AND THE INTRADAY RANGES TOO (2026-09-22c). Tj, a THIRD recording - "why is the spy
        // baseline still jumping up and down when I hold and drag left and right on the stock
        // chart?" - on a zoomed 1D chart. The exemption above was wrong in two ways:
        //
        //  * 1D and Overnight. `comparePercents` does measure SPY from its previous close there,
        //    and never moves it - but the STOCK line was still measured from the first candle on
        //    screen (`fromPoint = zoomedIn`), so as the window slid, SNDC's zero slid with it
        //    while SPY's stayed put: the same 11:30 candle read above SPY in one frame and below
        //    it the next. Both lines now keep the range's own rule when zoomed - each from its
        //    own previous close, exactly what the unzoomed chart and the 1D chip show.
        //  * 5D. `intraday` (30-minute candles) but not a previous-close range, so neither rule
        //    applied and BOTH lines were re-zeroed at the window's left edge on every frame of a
        //    drag - one shared zero, but a moving one, which flips the order just the same. It
        //    now anchors at the range's first candle like every other multi-day range.
        //
        // The principle, for every range: zooming and dragging change WHICH PART of the chart is
        // on screen, never WHAT the lines measure.
        val prevCloseRange = range == ChartRange.D1 || range == ChartRange.OVERNIGHT

        // ---- COMPARISON MODE, computed once per data change rather than per frame.
        //
        // `remember(shown, compare)` and not `remember(compare)`: `shown` is rebuilt on every
        // quote tick by `withLiveEdge`, and the overlay has to follow the line it is drawn
        // against or the two disagree at the right-hand edge. Both arrays are null whenever
        // the comparison cannot be made honestly, and every reader below treats null as
        // "draw the ordinary price chart", so there is no half-comparison state.
        val tipT = remember(shown) { shown.points.lastOrNull()?.t ?: Long.MIN_VALUE }
        // ---- WHICH OF THE DRAWN POINTS ARE ACTUALLY ON SCREEN.
        //
        // The drawn list carries one candle beyond each edge so the line reaches both sides.
        // Everything that MEASURES rather than draws - the y-axis, the resting readout, the
        // point count - works over this slice of it instead.
        val insideRange = remember(drawn, window) { insideIndices(drawn, window) }

        // ---- THE OVERLAY USES THE SAME FIXED ANCHOR AS `inside`, ABOVE.
        //
        // Nothing here needs to freeze for the life of a gesture any more (round 67 used to,
        // and round 79 removed it): `compareAnchorT` is the selected range's own true start,
        // read from `shown`, which does not change shape as the window pans - so there is no
        // "settled window's first candle" for a gesture to jump to in the first place.
        val cmp = remember(drawn, compare, compareLivePrice, liveEdge, tipT, zoomedIn, shown) {
            val benchmark = withLiveEdge(compare, compareLivePrice, liveEdge)
            // ON THE SAME TERMS AS `inside`, and keyed on the same anchor. Short-circuiting to
            // "the series' own rule" whenever the anchor is null gave the overlay the
            // benchmark's PREVIOUS CLOSE while the readout beside it used the first point on
            // screen - two percentages on one line measured from two different moments.
            compareLines(drawn, benchmark, shown, range, tipT, zoomedIn)
        }

        // COMPARING MEANS AN OVERLAY IS ACTUALLY DRAWN (full test 2026-09-24, C-6). Keyed on the
        // toggle alone, a comparison that could not be made (misaligned or too few SPY readings)
        // still gave the DOLLAR chart the comparison's anchor: a zoomed window measured from the
        // range's first candle, "since <date>", and a y-axis stretched to reach that old price.
        val comparing = cmp != null
        val compareAnchorT = if (comparing) compareAnchorFor(shown, range, compare) else null


        // ---- WHAT IS MEASURED: exactly the points [insideRange] names.
        //
        // DERIVED FROM THE SAME RANGE the y-axis, the point count and the comparison's zero
        // are taken over, rather than from a second, independent clip - which is how they came
        // to disagree: a window containing a single candle produced a caption reading "1 point"
        // over a two-point measurement whose second price was off screen.
        //
        // ITS BASELINE IS DROPPED once the view has been moved. `ChartSeries.from` is the
        // previous close on an intraday range, and on a chart zoomed to three hours of the
        // afternoon that is not what the reader is being shown a change OF - the caption says
        // "over 3 hr". It would also widen the y-axis to reach a price from outside the
        // window. Zeroing it makes `from` the first point on screen, which is what every
        // other figure on a moved chart is measured from.
        //
        // EXCEPT WHILE COMPARING (`compareAnchorT` != null, see its own note above): then this
        // summary has to share ITS anchor with the overlay's, or "TSXU +95.99%" printed right
        // beside "SPY +15.44%" would be two different starting days wearing one "over 6m"
        // caption - the exact "two moments" bug this file has already fixed once, just
        // between the two readouts instead of between a readout and the line under it.
        val inside = remember(drawn, insideRange, zoomedIn, compareAnchorT, shown, comparing) {
            val whole = insideRange.first == 0 && insideRange.last == drawn.points.lastIndex
            when {
                compareAnchorT != null -> drawn.copy(
                    points = drawn.points.subList(insideRange.first, insideRange.last + 1),
                    baseline = valueAtOrBefore(shown.points, compareAnchorT) ?: 0.0
                )
                // Comparing on a previous-close range: the summary keeps the previous close too,
                // zoomed or not - the same zero as both lines (2026-09-22c, see above).
                comparing && prevCloseRange -> drawn.copy(
                    points = drawn.points.subList(insideRange.first, insideRange.last + 1),
                    baseline = shown.from
                )
                whole && !zoomedIn -> drawn
                else -> drawn.copy(
                    points = drawn.points.subList(insideRange.first, insideRange.last + 1),
                    baseline = if (zoomedIn) 0.0 else drawn.baseline
                )
            }
        }

        // ---- ONE GREEN, ONE RED, AND THEY DESCRIBE WHAT IS ON SCREEN.
        //
        // From `inside`, not from the whole fetched series (sweep 4). The readout's figure
        // takes this colour, so a six-month line that is up overall, zoomed into a week in
        // which the stock fell, printed "-1.00  -0.43%" IN GREEN - and drew the line green
        // too. The colour and the number it colours have to come from the same series.
        val line = signColor(inside.change, Fmt.changeEps(inside.last))   // R2-1: its own precision

        // ---- the readout: what the line did over this window, or what it did at your finger
        //
        // ITS OWN COMPOSABLE so that scrubbing recomposes only this line. Read `scrub` here
        // in `PriceChart` instead and every drag event would recompose the whole chart -
        // canvas, axis labels and caption included - to change one string.
        // THE READOUT IS TEXT, so it takes the text-legible green/red rather than the line's
        // fill colour - see the note on `signColor`. The line itself keeps the brand colour.
        ChartReadout(
            drawn, range, line, muted, scrub, cmp, compareLabel,
            summary = inside, summaryCmpIndex = cmp?.pairedIndexIn(
                insideRange.first, insideRange.last
            ),
            windowLabel = if (zoomedIn) spanLabel(axis.spanMs) else null,
            // A COMPARED figure is measured from the fixed anchor, not from the window's left
            // edge, so "over 4 hr" would misname it (2026-09-22c). A previous-close range keeps
            // its own "since yesterday's close"; any other range says which day it counts from.
            sinceLabel = when {
                !zoomedIn || !comparing -> null
                prevCloseRange -> if (range == ChartRange.OVERNIGHT) "since today's close"
                else "since yesterday's close"
                compareAnchorT != null -> "since " + (
                    if (range == ChartRange.D5 || range == ChartRange.M1 || range == ChartRange.M6)
                        Fmt.shortDay(compareAnchorT * 1000L)
                    else Fmt.day(compareAnchorT * 1000L)
                )
                else -> null
            }
        )

        Spacer(Modifier.height(8.dp))

        Box(
            plotSize
                // CLIPPED, because a zoomed line is deliberately drawn past both edges - see
                // [clipToWindow]. Compose does not clip a `Canvas` to its node by default, so
                // without this the carried points' segments bleed into the 16dp gutters the
                // detail screen pads the chart with.
                .clipToBounds()
                .then(gestures)
        ) {
            // ---- THE Y-AXIS, COMPUTED ONCE AND USED BY BOTH THE CANVAS AND THE LABELS.
            //
            // FROM WHAT IS ON SCREEN (`inside`), not from the padded drawing list: read off
            // the padded one, the figure pinned to the top corner could name a price the line
            // never reaches, which is the fault the note below describes.
            val yBounds = remember(cmp, inside, insideRange) {
                cmp?.boundsIn(insideRange.first, insideRange.last) ?: priceBounds(inside)
            }
            ChartCanvas(
                drawn, line, MaterialTheme.colorScheme.outline,
                scrub, MaterialTheme.colorScheme.surface, Modifier.fillMaxSize(),
                cmp, benchmarkColor, axis, yBounds,
                // `inside.baseline` is zeroed on a moved chart, which is what takes the dotted
                // line off it: the previous close is the reference for "today's move", not for
                // a three-hour slice of the afternoon.
                baselineValue = inside.baseline.takeIf { it > 0.0 }
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
            // ONE OBJECT DECIDES THE VALUE, THE UNIT AND THE SCALE. An earlier attempt at
            // this had the value coming from a second, separately-rebased comparison and the
            // unit from the first, which could print the stock's PRICE IN DOLLARS with a
            // percent sign after it.
            val bounds = yBounds
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
                        // THE SPAN, NOT THE RANGE, once the zoom is continuous: the fingers
                        // are moving a window, and naming the range underneath it would leave
                        // the badge stuck on "1M" through most of a spread that is visibly
                        // changing the picture. Falls back to the range label on a chart with
                        // no window, where the rung ladder is still what the pinch moves.
                        if (zoomSpan > 0L) spanLabel(zoomSpan) else range.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            if (onExpand != null) {
                // ---- BOTTOM LEFT, AND A FULL TAP TARGET (Round 64 sweep 3).
                //
                // It was 34dp in the TOP-RIGHT corner, which is where the high-price label
                // lives: the two abutted at the default font scale and the corner brackets
                // were drawn straight through the price from about 1.15x upward. The bottom
                // left is the one corner of this chart nothing else claims. 48dp because that
                // is this app's measured minimum - see `minTapTarget`, and the two rounds it
                // took to learn it on this very screen.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .size(48.dp)
                        .clickable(
                            onClick = onExpand,
                            onClickLabel = "Open the chart full screen"
                        )
                        .semantics { contentDescription = "Open the chart full screen" }
                        .testTag(EXPAND_TEST_TAG),
                    contentAlignment = Alignment.Center
                ) {
                    ExpandGlyph(muted)
                }
            }

            // `gestureLive`, not `zooming` (review M08): a one-finger pan is a gesture that
            // moves the window too, and leaving the chip up through it put a tappable target
            // under the moving finger and a label over the line being dragged.
            if (!gestureLive && zoomedIn && onResetWindow != null) {
                // ---- THE WAY BACK OUT.
                //
                // A continuous zoom can leave the chart anywhere, and pinching all the way
                // back is fiddly at the wide end where a whole spread is worth one rung of
                // span. The range chips above do reset it, but they scroll off a screen that
                // is itself scrolling - so the escape hatch belongs ON the chart.
                Box(
                    Modifier
                        // TOP LEFT, for the same reason the expand button moved: this chip is
                        // ~82dp wide and was painted over the high-price label at any font
                        // scale above about 1.3x.
                        .align(Alignment.TopStart)
                        .padding(top = 6.dp)
                        // THE APP'S OWN 48dp RULE (same as RangeChips below) - this was the
                        // one tappable chip on the chart that never got it, ~22dp tall as
                        // measured. `defaultMinSize` before the background, so the painted
                        // chip is the full 48dp rather than a smaller pill with dead space.
                        .minTapTarget()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        // UNDO THE ZOOM, rather than "select everything the app could ever
                        // fetch". Aimed at `windowBounds` this set a forty-year window on any
                        // chart whose all-time series was not cached, which drew the loaded
                        // line as a sliver at the right-hand edge until a MAX fetch landed.
                        // Not `?.invoke()`: this branch is only entered when the callback is
                        // non-null, so the safe call was dead code the compiler warned about.
                        .clickable { onResetWindow.invoke() }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .testTag(RESET_ZOOM_TAG),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Reset zoom",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(3.dp))

        // ---- the x-axis, as its two ends
        // FROM THE AXIS, NOT FROM THE SERIES. The two are the same thing on an unzoomed
        // chart and different on a zoomed one, where `drawn` carries a point beyond each
        // edge: labelling the ends with those points' timestamps would print two dates that
        // are not the two ends of the picture.
        Row(Modifier.fillMaxWidth()) {
            val withDate = spansMoreThanADay(axis.startMs, axis.endMs)
            val withYear = spansMoreThanAYear(axis.startMs, axis.endMs)
            Text(
                axisLabel(axis.startMs, range, withDate, withYear),
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )
            Spacer(Modifier.weight(1f))
            Text(
                axisLabel(axis.endMs, range, withDate, withYear, zoned = true),
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
                LegendDot(benchmarkColor)
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
                // OVER THE WINDOW, like every other figure on this chart (sweep 4). Read
                // across the whole padded list this printed the out-performance one candle
                // PAST the right-hand edge - a month, on an all-time monthly line - while the
                // readout above it printed the pair inside the window. Three numbers on one
                // screen that could not be reconciled.
                val spread = remember(cmp, insideRange) {
                    cmp.spread(insideRange.first, insideRange.last)
                }
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
                // ---- WHAT IS ON SCREEN, NOT WHAT WAS FETCHED (Round 64 sweep).
                //
                // Both halves were the whole series: "One month, daily closes  -  120 points"
                // sat under a chart showing three days and eight of them. The caption is the
                // line that says what the chart is showing, so on a zoomed chart it has to
                // say the window - and it names the underlying candles too, because "3 days,
                // daily closes" explains why a three-day picture has three points in it.
                if (zoomedIn) {
                    append(spanLabel(axis.spanMs))
                    // The candle size, taken from the range's own caption where it names one.
                    // "After-hours and overnight only" has no comma and no candle size in it,
                    // so it is joined rather than spliced.
                    if (range.caption.contains(", ")) {
                        append(", ")
                        append(range.caption.substringAfter(", "))
                    } else {
                        append(" of ")
                        append(range.caption.replaceFirstChar { it.lowercase() })
                    }
                } else {
                    append(range.caption)
                }
                append("  -  ")
                // THE POINTS ON SCREEN, which is `inside` and not the padded drawing list.
                val onScreen = insideRange.last - insideRange.first + 1
                append(onScreen)
                append(if (onScreen == 1) " point" else " points")
                if (onZoom != null || onWindow != null) {
                    // ---- DISCOVERABILITY, NAMING WHAT IS ACTUALLY LIVE (round 65).
                    //
                    // A gesture nothing on screen mentions is a gesture nobody finds, and this
                    // caption is already the line that says what the chart is showing.
                    //
                    // IT READS THE SAME `canPanNow` THE GESTURE READS. Offering "drag to move"
                    // on a chart whose window is not narrower than the line promises a gesture
                    // the pan guard then refuses, and a caption that lies about the controls is
                    // worse than one that says nothing (review M03).
                    append("  -  pinch to zoom")
                    // ONLY WHERE THE GESTURE CHANGED (sweep 3, N07). An unzoomed chart scrubs
                    // on a drag exactly as it has since v7.4, so naming that here would add a
                    // line of text to the screen TJ reads every day to tell him something he
                    // already knows. A zoomed chart is where one finger acquired a second
                    // meaning, and that is the only place worth spending the words.
                    if (canPanNow) append(", drag to move, hold to scrub")
                }
                // ---- AN OLD CHART SAYS SO (chart idea 5, 2026-09-24b). Past its range's TTL,
                // not loading, and not final after the close means the refresh that should have
                // replaced it failed - the line is real, but it is not now.
                val nowMs = System.currentTimeMillis()
                val f = shown.fetched
                if (!loading && f > 0L && nowMs - f > range.ttlMs + 60_000L &&
                    !intradayChartIsFinal(range, shown.endMs, f, nowMs)
                ) {
                    append("  -  last updated ")
                    if (Fmt.iso(f) != Fmt.iso(nowMs)) append(Fmt.shortDay(f)).append(", ")
                    append(Fmt.clock(f))
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
    compareLabel: String = BENCHMARK_SYMBOL,
    /**
     * WHAT THE WINDOW DID, as opposed to what is drawn (Round 64 sweep).
     *
     * [s] is the drawn series, which on a zoomed chart carries one point beyond each edge so
     * the line reaches both sides; the scrub index points into it, so the crosshair half of
     * this readout has to use it. Every RESTING figure - the change, the percentage, the
     * baseline the scrubbed price is measured from - must come from this one instead, or the
     * chart prints a figure for a stretch of time it is not showing.
     */
    summary: ChartSeries = s,
    /**
     * Which index of [cmp] the RESTING readout should quote the benchmark at.
     *
     * The last moment inside the window at which both lines have a reading. Null when there is
     * none, and - on an unzoomed chart - simply the pair's own last shared index.
     */
    summaryCmpIndex: Int? = cmp?.pairedIndex(),
    /**
     * What to call the window when it no longer matches the selected range.
     *
     * Null on an unzoomed chart, where the range's own label is the honest answer. Once
     * pinched, "over 1m" beside a three-week picture is the chart contradicting itself - and
     * the range chip above is still showing the whole month's figure, so the two disagree in
     * public. Naming the actual span settles it.
     */
    windowLabel: String? = null,
    /** What a COMPARED figure counts from, when zoomed - see where [PriceChart] sets it. Takes
     *  precedence over [windowLabel], which would describe the window rather than the zero. */
    sinceLabel: String? = null
) {
    val i = scrub.intValue
    val point = if (i in s.points.indices) s.points[i] else null
    // HOISTED OUT OF THE PER-FRAME PATH. This formats two ISO dates through SimpleDateFormat,
    // and it was being asked on every frame of a drag to answer a question whose answer
    // cannot change during one.
    val withDate = remember(s) { spansMoreThanADay(s) }
    // The scrub label names one moment, and on a multi-year line "Sep 9" alone does not say
    // which September the finger is on.
    val withYear = remember(s) { spansMoreThanAYear(s) }

    Row(
        Modifier.fillMaxWidth().height(READOUT_HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (point == null) {
            Text(
                Fmt.changeMoney(summary.last, summary.change) + "   " +
                    Fmt.pctSignedBeside(summary.changePct, summary.change, summary.last),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = line
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // NAMES THE BASELINE. The same omission the price block was corrected for in
                // Round 51: a percentage with nothing saying what it is a percentage OF.
                when {
                    sinceLabel != null -> sinceLabel
                    windowLabel != null -> "over $windowLabel"
                    range == ChartRange.D1 -> "since yesterday's close"
                    range == ChartRange.OVERNIGHT -> "since today's close"
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
            summaryCmpIndex?.let { pi -> cmp?.other?.getOrNull(pi) }
                ?.takeIf { it.isFinite() }?.let { m ->
                Spacer(Modifier.weight(1f))
                Text(
                    "$compareLabel " + Fmt.pctSigned(m),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = benchmarkColor,
                    maxLines = 1
                )
            }
        } else {
            // THE PRICE AT THE FINGER, and the change measured from the SAME baseline the
            // resting readout uses - not from the left-hand edge of the line. Those differ on
            // the 1D and after-hours views, where the baseline is the previous close rather
            // than the first plotted point, and quietly switching reference mid-gesture would
            // make the number disagree with the one shown a moment earlier.
            // FROM THE WINDOW'S OWN BASELINE, not the drawn list's - see [summary].
            val from = summary.from
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
                    Fmt.changeMoney(point.close, delta) + "   " + Fmt.pctSignedBeside(pct, delta, point.close),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = signColor(delta, Fmt.changeEps(point.close))   // R2-1
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
                    color = benchmarkColor,
                    maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                // Always dated when the window spans more than a day, because on the
                // after-hours and 5-day views a bare clock does not say which day it is.
                axisLabel(point.t * 1000L, range, withDate, withYear, zoned = true),
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

/** Test handle for the reset-zoom affordance. */
internal const val RESET_ZOOM_TAG = "chartResetZoom"

/** Test handle for the open-full-screen button. */
internal const val EXPAND_TEST_TAG = "chartExpand"

/**
 * FOUR CORNER BRACKETS - the universal "make this bigger" mark.
 *
 * DRAWN RATHER THAN AN ICON because this app depends on `material-icons-core`, which has no
 * fullscreen glyph, and pulling in the whole extended icon set (several thousand vectors) to
 * get one 16dp mark is not a trade worth making on an app that is sideloaded over a phone
 * connection. Four strokes are cheaper than the dependency and look the same.
 */
@Composable
private fun ExpandGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val arm = w * 0.32f
        val stroke = 1.6.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), strokeWidth = stroke,
                cap = StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), strokeWidth = stroke,
                cap = StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f)
        corner(w, 0f, -1f, 1f)
        corner(0f, h, 1f, -1f)
        corner(w, h, -1f, -1f)
    }
}

/**
 * The window a pinch is currently working from, held OUTSIDE Compose state (Round 64).
 *
 * A pointer handler runs several times per frame and many times per recomposition. Keeping
 * the running window in `mutableStateOf` would schedule a recomposition for each of those, to
 * publish a value composition never reads; keeping it in a plain holder lets the gesture
 * accumulate at pointer speed and tell the hoisted state about each result once.
 */
internal class WindowHold {
    var window: com.tj.portfolio.data.ChartWindow? = null
}

/**
 * How much time is on screen, in the shortest words that are still true.
 *
 * FOR THE ZOOM BADGE, which has to name a window that no longer lines up with any range chip:
 * a continuous pinch lands on "3 months" and "7 weeks" as readily as on "1Y". Rounded to a
 * unit the eye can check against the axis labels rather than given exactly, because the badge
 * is a running readout during a gesture, not a figure anyone copies down.
 */
internal fun spanLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        minutes < 90L -> "${minutes.coerceAtLeast(1L)} min"
        minutes < 60L * 36L -> "${Math.round(minutes / 60.0)} hr"
        minutes < 60L * 24L * 14L -> "${Math.round(minutes / (60.0 * 24))} days"
        minutes < 60L * 24L * 60L -> "${Math.round(minutes / (60.0 * 24 * 7))} weeks"
        minutes < 60L * 24L * 730L -> "${Math.round(minutes / (60.0 * 24 * 30.44))} months"
        else -> {
            val years = minutes / (60.0 * 24 * 365.25)
            if (years < 10.0) String.format(java.util.Locale.US, "%.1f yr", years)
            else "${Math.round(years)} yr"
        }
    }
}

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
    /**
     * REQUIRED, not defaulted. It briefly defaulted to `Benchmark` - the raw fill - while the
     * only caller passed the theme-aware `benchmarkColor`, which is a trap rather than a
     * convenience: a second call site would silently get the wrong colour in one theme, and
     * nothing would fail.
     */
    benchmarkColor: Color,
    /**
     * THE X-AXIS, AS A STRETCH OF TIME (Round 64).
     *
     * The scale x is drawn against, and deliberately NOT the drawn points' own extent. When
     * the chart is zoomed, [clipToWindow] hands this function one point beyond each edge so
     * the line reaches both sides - and if x were scaled to those points, the axis would be
     * a candle wider than the window on each side and the picture would not line up with the
     * fingers that placed it. Scaling to the window instead puts the carried points just
     * outside the canvas, where they are clipped and simply not seen.
     *
     * With no zoom the caller passes the drawn series' own span, which is what the old code
     * computed here - so an unzoomed chart is pixel-for-pixel the one that shipped in v7.4.
     */
    axis: ChartWindow,
    /**
     * The top and bottom of the y-axis, as [priceBounds] returns them.
     *
     * The CALLER owns this because on a zoomed chart the scale must come from the points
     * inside the window while the line drawn against it carries one point beyond each edge.
     * Those outside points are then drawn off the top or bottom and clipped, which is right:
     * they exist to make the line reach the sides, not to move the axis.
     */
    yBounds: DoubleArray,
    /**
     * The dotted previous-close line, or null for none.
     *
     * PASSED IN for the same reason [yBounds] is: the axis no longer widens to reach it on a
     * moved chart, so a canvas that decided for itself would draw a line outside its own
     * scale - clipped away on a gap day, which is exactly the day it exists for. One decision,
     * made where the axis is made.
     */
    baselineValue: Double?
) {
    val pts = s.points
    // ---- THE Y SCALE. Two different questions, so two different answers.
    //
    // In price mode the axis spans the series' own high and low, widened to include the
    // dotted previous-close baseline so that line is always visible. In comparison mode it
    // spans BOTH percent series together and always includes zero - the zero line is the
    // whole reference and an axis that excluded it would draw two lines with nothing to
    // measure them against.
    val base = if (cmp != null) null else baselineValue?.takeIf { it > 0.0 }
    // ONE ARRAY FOR BOTH THE SCALE AND THE LABELS, so the number printed at the top of the
    // axis is by construction the value drawn there.
    //
    // PASSED IN NOW, NOT COMPUTED HERE (Round 64 sweep). The first pass at the zoom made the
    // corner labels read the strictly-clipped series while this function went on scaling to
    // the padded one - so on every zoomed chart the axis was stretched by a candle the user
    // could not see and the label pinned to the top corner named a price the line never
    // reached. That is the exact fault the labels' own note says was fixed; the only way the
    // two cannot drift is for there to be one value.
    val bounds = yBounds
    val lo = bounds[0]
    val hi = bounds[1]
    val span = (hi - lo).let { if (it < 1e-9) 1.0 else it }
    // SECONDS, because `ChartPoint.t` is in seconds and the window is in milliseconds. The
    // division is done once here rather than per point.
    val t0 = axis.startMs / 1000L
    val tSpan = (axis.endMs / 1000L - t0).let { if (it <= 0L) 1L else it }
    // CLOSED-MARKET GAPS (C-9) - where the line breaks instead of drawing a straight diagonal
    // across a night or a weekend. See [closedGaps].
    val gapAfter = remember(pts, s.range) { closedGaps(pts, s.range) }
    // VOLUME BARS on the two intraday windows people trade from (chart idea 7).
    val maxVolume = remember(pts, s.range) {
        if (s.range != ChartRange.D1 && s.range != ChartRange.D5) 0.0
        else pts.maxOfOrNull { it.volume } ?: 0.0
    }
    // THE PREVIOUS CLOSE, LABELLED at the right edge of its dotted line (chart idea 6).
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val baseLabel = remember(base, labelStyle) {
        base?.let { measurer.measure("Prev close " + Fmt.price(it), labelStyle) }
    }

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
            // Labelled at the right edge, above the line (below it when there is no room), on
            // a pill in the surface colour so it reads over the fill and the gridlines.
            baseLabel?.let { lbl ->
                val lw = lbl.size.width.toFloat()
                val lh = lbl.size.height.toFloat()
                val px = 3.dp.toPx()
                val left = (w - lw - px * 2f).coerceAtLeast(0f)
                val top = (by - lh - px * 1.5f).let { if (it < 0f) by + px else it }
                drawRoundRect(
                    dot.copy(alpha = 0.85f), Offset(left, top),
                    androidx.compose.ui.geometry.Size(lw + px * 2f, lh),
                    androidx.compose.ui.geometry.CornerRadius(px, px)
                )
                drawText(lbl, topLeft = Offset(left + px, top))
            }
        }

        // ---- VOLUME, as faint bars along the bottom fifth (chart idea 7). Drawn before the
        // line so the price stays the subject; not with a benchmark, whose second line would
        // make "whose volume?" a fair question.
        if (maxVolume > 0.0 && cmp == null) {
            val band = (h - pad) * 0.2f
            val candlePx = w * (s.range.candleMs / 1000f) / tSpan.toFloat()
            val barW = (candlePx * 0.7f).coerceIn(1f, 10.dp.toPx())
            val barColor = grid.copy(alpha = 0.45f)
            for (p in pts) {
                if (p.volume <= 0.0) continue
                val bh = (band * (p.volume / maxVolume)).toFloat().coerceAtLeast(1f)
                drawRect(barColor, Offset(x(p) - barW / 2f, h - bh),
                    androidx.compose.ui.geometry.Size(barW, bh))
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

        // ONE PATH, BROKEN AT EVERY CLOSED-MARKET GAP (C-9): a straight line across a night
        // or a weekend drew trading that never happened. The gap itself gets a faint dashed
        // bridge below, so the eye still follows the line from session to session.
        val path = Path()
        val fill = if (cmp == null) Path() else null
        var segFirst = 0
        for (i in pts.indices) {
            val px = x(pts[i]); val py = y(value(i))
            if (i == segFirst) path.moveTo(px, py) else path.lineTo(px, py)
            if (i == pts.lastIndex || gapAfter[i]) {
                // NO FILL UNDER A COMPARED LINE. The gradient reads as "area", and an area
                // under a percentage line that crosses zero is meaningless - worse, it would be
                // painted over the benchmark line wherever the two cross.
                fill?.apply {
                    moveTo(x(pts[segFirst]), y(value(segFirst)))
                    for (j in segFirst + 1..i) lineTo(x(pts[j]), y(value(j)))
                    lineTo(px, h)
                    lineTo(x(pts[segFirst]), h)
                    close()
                }
                segFirst = i + 1
            }
        }
        if (fill != null) {
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0f))))
        }
        for (i in 0 until pts.lastIndex) {
            if (!gapAfter[i]) continue
            val a = Offset(x(pts[i]), y(value(i)))
            val b = Offset(x(pts[i + 1]), y(value(i + 1)))
            drawLine(line.copy(alpha = 0.35f), a, b, strokeWidth = baseStroke,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
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
 * WHERE AN INTRADAY LINE CROSSES A CLOSED MARKET (full test 2026-09-24, C-9): true at index i
 * when the step from point i to i+1 is longer than three candles and at least two hours - a
 * night, a weekend, or the regular session the after-hours view leaves out. Never on a daily
 * or longer chart, where a weekend is simply the next candle.
 */
internal fun closedGaps(pts: List<ChartPoint>, range: ChartRange): BooleanArray {
    val out = BooleanArray(pts.size)
    if (!range.intraday || pts.size < 2) return out
    val limit = maxOf(3L * range.candleMs / 1000L, 2L * 3600L)
    for (i in 0 until pts.lastIndex) if (pts[i + 1].t - pts[i].t > limit) out[i] = true
    return out
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
internal fun nearestIndex(
    points: List<ChartPoint>,
    xFraction: Float,
    /**
     * The axis the chart is drawn against, or null for the points' own extent (Round 64).
     *
     * IT HAS TO BE THE SAME SCALE THE CANVAS USED. On a zoomed chart the drawn list carries
     * one point beyond each edge, so its own first and last timestamps are wider than the
     * picture - a crosshair placed from them would sit a candle away from the finger, and
     * further the more the chart is zoomed. Null keeps the pre-zoom behaviour exactly.
     */
    axis: ChartWindow? = null
): Int {
    if (points.size < 2) return 0
    val frac = xFraction.coerceIn(0f, 1f).toDouble()
    val target = if (axis != null) {
        axis.startMs / 1000L + Math.round(frac * (axis.spanMs / 1000L).coerceAtLeast(1L))
    } else {
        val t0 = points.first().t
        val span = (points.last().t - t0).coerceAtLeast(1L)
        t0 + Math.round(frac * span)
    }

    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].t < target) lo = mid + 1 else hi = mid
    }
    // `lo` is the first point at or after the target; whichever neighbour is closer wins, and
    // a tie goes to the earlier one so dragging left and right lands on the same point.
    var best = lo
    if (lo > 0 && (target - points[lo - 1].t) <= (points[lo].t - target)) best = lo - 1

    // ---- AND NEVER ON A POINT THAT IS NOT ON SCREEN (Round 64 sweep).
    //
    // The drawn list carries one candle beyond each edge so the line reaches both sides. At
    // the far left of a zoomed chart the nearer of the two neighbours is often that carried
    // point - so the readout printed a price and a date from outside the window while the
    // crosshair itself was drawn at a negative x and could not be seen. Clamping to the points
    // inside the axis keeps the two together; a window with no point inside it at all (a
    // monthly line zoomed into one day) falls back to the nearest, which is all there is.
    if (axis != null) {
        val lowSec = axis.startMs / 1000L
        val highSec = axis.endMs / 1000L
        val firstIn = points.indexOfFirst { it.t >= lowSec }
        val lastIn = points.indexOfLast { it.t <= highSec }
        if (firstIn in 0..lastIn) best = best.coerceIn(firstIn, lastIn)
    }
    return best
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
    return s.copy(points = s.points.dropLast(1) + last.copy(close = livePrice))
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
internal fun axisLabel(
    ms: Long,
    range: ChartRange,
    withDate: Boolean,
    /**
     * Put the YEAR on a date label. Off by default, which keeps every existing caller and
     * test on the old behaviour.
     *
     * THE BUG THIS CLOSES. A dated label was "MMM d" and nothing else, so the two ends of a
     * three-year window read "Sep 9" and "Sep 10" - which is exactly what two consecutive
     * days look like. Dragging such a chart moves the window by months or years and BOTH
     * lines rebase to the new left edge, so every figure on screen changes a lot; with no
     * year on the axis there was nothing to say the window had moved that far, and the
     * honest rebasing read as the chart contradicting itself. TJ reported precisely this
     * after panning a 5Y FIVE-vs-SPY chart. The percentages were right; the axis was hiding
     * the reason they changed.
     */
    withYear: Boolean = false,
    /** Say the zone - "4:00 PM ET" - on the label that names one (the end, the crosshair). */
    zoned: Boolean = false
): String = when {
    ms <= 0L -> ""
    !range.intraday -> if (withYear) Fmt.day(ms) else Fmt.shortDay(ms)
    // MARKET TIME (chart idea 3, 2026-09-24b) - see Fmt.clockEt.
    withDate -> Fmt.shortDayEt(ms) + "  " + Fmt.clockEt(ms) + (if (zoned) " ET" else "")
    else -> Fmt.clockEt(ms) + (if (zoned) " ET" else "")
}

/** True when the series' two ends fall on different calendar days. */
internal fun spansMoreThanADay(s: ChartSeries): Boolean =
    spansMoreThanADay(s.startMs, s.endMs)

/**
 * True when the two ends fall in different calendar YEARS - the point at which a bare
 * "MMM d" label stops being enough to say where the window is. See [axisLabel]'s `withYear`.
 *
 * CALENDAR YEARS, not "365 days apart": a window running Nov 2025 to Feb 2026 is four
 * months long but its labels are far more readable with the year on them, while May to
 * October of one year needs no year at all to be unambiguous.
 */
internal fun spansMoreThanAYear(startMs: Long, endMs: Long): Boolean =
    startMs > 0L && endMs > 0L && Fmt.year(startMs) != Fmt.year(endMs)

/** The same question asked of a series. */
internal fun spansMoreThanAYear(s: ChartSeries): Boolean =
    spansMoreThanAYear(s.startMs, s.endMs)

/**
 * The same question asked of two moments, which is what a zoom window is.
 *
 * The series overload delegates here so a zoomed chart and an unzoomed one cannot answer it
 * differently - the axis labels read this one and the caption reads the other.
 */
internal fun spansMoreThanADay(startMs: Long, endMs: Long): Boolean =
    startMs > 0L && endMs > 0L && Fmt.isoEt(startMs) != Fmt.isoEt(endMs)   // market days

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
 * How far the fingers must have spread before it counts as a zoom rather than as noise.
 *
 * 1.0015 - fifteen hundredths of a percent of separation, which at a comfortable 200px grip is
 * a third of a pixel. Below that the reading is float rounding in the pointer positions, not a
 * hand: two fingers sliding across the glass together produce a steady drizzle of factors just
 * either side of 1, and applying them turned a pure pan into a slow creeping zoom.
 *
 * NOT A THRESHOLD THAT THROWS ANYTHING AWAY. The factor below it is kept and multiplied into
 * the next frame's, so a spread slow enough to sit under the floor still arrives in full a few
 * frames later. The floor delays; it does not filter.
 */
private const val ZOOM_DEAD_ZONE = 1.0015f

/** Which gesture, if any, currently owns the chart's window. */
internal enum class ChartGesture { NONE, PAN, ZOOM }

/**
 * How long a finger must sit still before a press becomes a scrub.
 *
 * MEASURED FROM THE LAST MOVEMENT, not from the press. Timing it from the press is what broke
 * the first draft of this feature (review M02): a deliberate, slow drag can easily take more
 * than a third of a second to travel eight dp, so every careful little pan was captured as a
 * hold-scrub and the gesture the round exists for never fired. What the user means by "held"
 * is a finger that has STOPPED, and stopping is a thing you measure from the last movement.
 */
private const val HOLD_SCRUB_MS = 350L

/**
 * ONE POINTER LOOP FOR SCRUBBING, PANNING AND ZOOMING.
 *
 * The gestures share a surface, so they have to share a decision:
 *
 *   * TWO OR MORE FINGERS - pinch to zoom and pan, and once a gesture has become a zoom it
 *     stays one until every finger is lifted; a pinch that ends with one finger still on the
 *     glass must not turn into a scrub as the other leaves.
 *   * ONE FINGER, CHART NOT ZOOMED - drag scrubs. Untouched, deliberately: it is the gesture
 *     TJ uses every day and it is what shipped in v7.4.
 *   * ONE FINGER, CHART ZOOMED - drag moves the window. A zoomed chart with no way to slide
 *     sideways is a window the reader is stuck in, and asking for two fingers to escape it is
 *     asking for the harder gesture to do the commoner thing.
 *   * PRESS AND HOLD, THEN DRAG - scrubs either way, with a tick when it takes hold. This is
 *     what keeps the crosshair reachable on a zoomed chart.
 *
 * ---- HOW A ONE-FINGER DRAG IS TOLD FROM THE PAGE'S SCROLL
 *
 * The chart is a strip of a screen that scrolls, so the interesting question is never "is this
 * a scrub or a pan" but "is this the chart's gesture at all". Two rules, and both exist
 * because the first draft got them wrong:
 *
 *   * A DRAG THAT IS MOSTLY VERTICAL IS THE PAGE'S. Once it passes the touch slop downwards or
 *     upwards this loop hands it back and stops looking - it does not consume, so the scroller
 *     above sees every event. The first draft only ever measured horizontal movement, so a
 *     page scroll that paused on the chart armed the hold and the page stopped dead under the
 *     finger (review M01).
 *   * A HOLD CAN ONLY ARM WHILE THE FINGER IS STILL NEAR WHERE IT LANDED. Together with
 *     measuring stillness from the last movement, that is what separates "pressed and waited"
 *     from "scrolled, paused, carried on": a real scroll has travelled well past the slop
 *     before it pauses.
 *
 * WHAT IS CONSUMED, AND WHEN. Nothing at all until the gesture has been identified: a scrub or
 * a pan consumes only once it is decided, and a zoom consumes from the second finger down,
 * immediately, because there is no such thing as an accidental two-finger drag and the list
 * underneath must not scroll while the chart is being pinched.
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
    /**
     * THE CONTINUOUS ZOOM (Round 64), as a provider on the same terms as [zoom].
     *
     * Called with the ratio the fingers' separation changed by SINCE THE LAST FRAME - above 1
     * for a spread - and where the pinch is centred as a fraction of the width. When this
     * returns non-null it REPLACES the rung ladder: reporting both would zoom the window and
     * change the range under it in the same gesture, and the two would fight.
     */
    pinch: () -> ((Float, Float) -> Unit)? = { null },
    /**
     * MOVE THE WINDOW, in fractions of the visible width, positive when the content is dragged
     * leftwards (later in time).
     *
     * Driven by a two-finger drag on any chart, and by a ONE-finger drag on a zoomed one - see
     * [canPan], which decides which of a one-finger drag's two meanings applies.
     */
    pan: () -> ((Float) -> Unit)? = { null },
    /**
     * Whether a one-finger drag should move the window instead of scrubbing.
     *
     * A PROVIDER, and the caller feeds it the same value the caption is written from, so the
     * two can never disagree about which gestures are live (review M03).
     */
    canPan: () -> Boolean = { false },
    /** Fired once, when a press-and-hold takes the gesture. The caller ticks the phone. */
    onHold: () -> Unit = {},
    /**
     * SHOW THE VALUE THE MOMENT A FINGER LANDS (chart idea 2, 2026-09-24b) - as Robinhood and
     * most broker apps do - instead of only after a sideways drag has crossed the slop. Only
     * where a one-finger drag scrubs anyway (an unzoomed chart); nothing is consumed, so a drag
     * that turns out to be the page's scroll still scrolls, and the crosshair goes with it.
     */
    pressShows: () -> Boolean = { false },
    /**
     * DOUBLE-TAP TO RESET THE ZOOM (chart idea 8, 2026-09-24b). A provider like the others:
     * the reset callback while the chart is moved away from its default view, else null - so a
     * double-tap on an unzoomed chart does nothing.
     */
    doubleTap: () -> (() -> Unit)? = { null },
    /** Which gesture owns the window now. Called only when the answer changes. */
    onGesture: (ChartGesture) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    // Across gestures, for the double-tap: when and where the last plain tap lifted.
    var lastTapUp = 0L
    var lastTapAt = androidx.compose.ui.geometry.Offset.Zero
    /**
     * How far a finger may wander and still count as "not moving".
     *
     * HALF THE TOUCH SLOP. A per-frame test cannot do this job: a drag of one pixel per frame
     * moves too little each frame to reset any per-frame threshold, so it reads as still and
     * arms the hold - which is review M02 all over again. Measuring drift from a remembered
     * ANCHOR instead means a slow drag accumulates: at half a slop it has plainly moved, the
     * anchor resets, and the hold clock starts over. A finger that is actually resting stays
     * inside the circle indefinitely.
     */
    val jitter = slop / 2f
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        var mode = GestureMode.UNDECIDED
        var accX = 0f
        var accY = 0f
        var zoomAccum = 1f
        // The two fingers this pinch is being measured between. Re-chosen whenever one of
        // them leaves, so a third finger landing or one of two lifting costs a single frame
        // rather than an arbitrary jump. See [zoomFactor].
        var pair: Pair<androidx.compose.ui.input.pointer.PointerId,
            androidx.compose.ui.input.pointer.PointerId>? = null
        // Where the pinch was centred on the previous frame, for the pan. NaN means "no
        // reading yet" - the first frame of a pinch, or the frame after the pair changed.
        var centroid = Float.NaN
        /**
         * How many pointers [centroid] was averaged over (Round 66 audit, CHT-1). A reading
         * taken over a different number of fingers is not comparable with this one, so the
         * next frame re-seeds instead of reporting the difference as hand movement.
         */
        var centroidN = 0
        // Zoom the fingers have done but that has not yet cleared the noise floor. See the
        // note at its use below.
        var pendingZoom = 1f

        // ---- PRESS AND HOLD.
        var holdArmed = false
        // Where the finger was when it last plainly moved, and when that was.
        var stillAt = first.position
        var stillSince = first.uptimeMillis
        // Still close enough to where it landed for a hold to be believable.
        var nearDown = true
        var wait = HOLD_SCRUB_MS

        var reported = ChartGesture.NONE
        fun report(g: ChartGesture) {
            if (reported != g) { reported = g; onGesture(g) }
        }
        // Still a candidate for a TAP: one finger throughout, never past the slop.
        var tapLike = true
        var liftedAt = 0L
        var liftedPos = first.position

        if (pressShows()) pointAt(first.position.x)

        try {
            while (true) {
                // ---- WAIT WITH A DEADLINE ONLY WHILE A HOLD IS STILL POSSIBLE.
                //
                // A finger that is perfectly still produces NO events, so the hold cannot be
                // detected by waiting for one. Once the gesture is decided the deadline is
                // dropped: a plain await is cheaper and there is nothing left to arm.
                // ---- THE HOLD ONLY EXISTS WHERE IT CHANGES SOMETHING (sweep 1, N01).
                //
                // On an unzoomed chart a plain drag already scrubs, so arming a hold there
                // buys the user nothing and costs something real: an armed hold consumes from
                // that moment on, BEFORE the touch slop, so a press that paused and then
                // turned into a page scroll would stop the page dead - on the one chart shape
                // that never needed the gesture. `canPan` is the whole reason the hold exists,
                // so it is also the condition for arming it.
                val holdPossible =
                    !holdArmed && nearDown && mode == GestureMode.UNDECIDED && canPan()
                val event =
                    if (holdPossible) {
                        withTimeoutOrNull(wait.coerceAtLeast(1L)) {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                    } else {
                        awaitPointerEvent(PointerEventPass.Main)
                    }

                if (event == null) {
                    // Nothing at all arrived before the deadline: the finger has stopped.
                    holdArmed = true
                    mode = GestureMode.SCRUB
                    clearPoint()
                    onHold()
                    // Put the crosshair where the finger already is, so a hold shows something
                    // without having to be dragged first.
                    pointAt(stillAt.x)
                    continue
                }

                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) {
                    event.changes.firstOrNull()?.let { liftedAt = it.uptimeMillis; liftedPos = it.position }
                    break
                }
                if (pressed > 1) tapLike = false

                val onZoomStep = zoom()
                val onPinch = pinch()
                val onPan = pan()
                if (pressed > 1 && (onZoomStep != null || onPinch != null)) {
                    if (mode != GestureMode.ZOOM) {
                        mode = GestureMode.ZOOM
                        // A crosshair left behind by the finger that started as a scrub would
                        // sit frozen on the line for the whole pinch.
                        clearPoint()
                        zoomAccum = 1f
                        pendingZoom = 1f
                        pair = null
                        centroid = Float.NaN
                        centroidN = 0
                        // The window itself is NOT re-seeded here - see the caller's note on
                        // review M04. A pinch that follows a pan continues from where the pan
                        // left the window.
                        report(ChartGesture.ZOOM)
                    }
                    event.changes.forEach { if (it.pressed) it.consume() }
                    val width = size.width.toFloat()
                    // Measure between the pair chosen when the pinch began; if either finger
                    // has gone, adopt a new pair and take no reading from this frame - the
                    // accumulator keeps whatever it had, so the gesture continues smoothly
                    // from wherever the remaining fingers now are.
                    val z = pair?.let { (a, b) -> zoomFactor(event, a, b) }
                    if (z == null) {
                        pair = zoomPair(event)
                        // The pan reference goes with the pair: measuring the next frame's
                        // centroid against one computed from different fingers would report a
                        // jump the hand never made.
                        centroid = Float.NaN
                        centroidN = 0
                        continue
                    }

                    if (onPinch != null) {
                        // ---- CONTINUOUS. Every frame's ratio is reported as it happens, so
                        // the window follows the fingers exactly rather than in rungs.
                        //
                        // EXCEPT FOR NOISE. Two fingers dragged across the glass together
                        // never hold their separation to the pixel, so a pure pan arrives here
                        // as a long run of factors like 1.00006 - and applied one by one they
                        // creep the span while the user is only sliding the chart sideways.
                        // The remainder is ACCUMULATED rather than discarded, so a genuinely
                        // slow spread still zooms at exactly its own rate; it just waits until
                        // it has moved further than the noise floor before it counts.
                        pendingZoom *= z
                        if (!pendingZoom.isFinite() || pendingZoom <= 0f) pendingZoom = 1f
                        val mid = centroidX(event)
                        if (width > 0f && mid.isFinite()) {
                            if (pendingZoom > ZOOM_DEAD_ZONE ||
                                pendingZoom < 1f / ZOOM_DEAD_ZONE
                            ) {
                                onPinch(pendingZoom, (mid / width).coerceIn(0f, 1f))
                                pendingZoom = 1f
                            }
                            // PAN AFTER ZOOM, and against the PREVIOUS centroid: the zoom has
                            // already moved the window under a fixed point, so what is left is
                            // how far that point itself travelled.
                            // ---- AND OVER THE SAME NUMBER OF FINGERS (Round 66 audit, CHT-1).
                            //
                            // THE BUG THIS FIXES. `pair` is re-chosen, and `centroid` reset,
                            // only when one of the two MEASURED fingers has gone - but
                            // `centroidX` averages EVERY pressed pointer. A third finger
                            // landing (a resting palm, a steadying thumb) leaves the pair
                            // intact, so `zoomFactor` still returns a value and none of the
                            // resets above fire, while `mid` jumps by a third of the distance
                            // to the new finger. On a 1080px chart, fingers at 100 and 300
                            // with a third arriving at 1000: `mid` goes 200 -> 466.7 and this
                            // line reports a pan of a quarter of the visible span, in one
                            // frame, from a finger that only touched down. Lifting it reports
                            // the same jump back the other way. `centroidX`'s own KDoc
                            // promises the opposite: "a third finger landing should not make
                            // the window jump".
                            //
                            // A frame where the count changed re-seeds the reference and
                            // reports nothing - exactly what the pair-changed path already
                            // does one screen up.
                            if (onPan != null && centroid.isFinite() && centroidN == pressed) {
                                val moved = mid - centroid
                                // The content follows the fingers, so the WINDOW moves the
                                // other way: dragging right shows earlier time.
                                if (moved != 0f) onPan(-moved / width)
                            }
                        }
                        centroid = mid
                        centroidN = pressed
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
                    if (steps != 0 && onZoomStep != null) onZoomStep(steps)
                    continue
                }

                if (mode == GestureMode.ZOOM) {
                    // ---- A PINCH DOWN TO ONE FINGER BECOMES A PAN (Round 66 audit, CHT-2).
                    //
                    // THE BUG THIS FIXES. This arm consumed and `continue`d unconditionally,
                    // and nothing else could ever assign `mode` once it had left UNDECIDED -
                    // so ZOOM latched until the LAST finger lifted. Lift one finger of a pinch
                    // and keep dragging with the other and the chart was inert: the window did
                    // not move, no crosshair appeared, and because the frame was still being
                    // consumed the page underneath could not scroll either. The natural way to
                    // end a two-finger zoom is to lift one finger, so this was reachable by
                    // ordinary use, and the app simply stopped responding until the hand came
                    // off the glass.
                    //
                    // PAN, NEVER SCRUB. The rule at the top of this function is that the tail
                    // of a pinch must not turn into a scrub, and it still holds - a pinch only
                    // happens on a chart that can pan, and panning is the continuation of what
                    // the two fingers were already doing. The window is not re-seeded, so it
                    // carries on from exactly where the pinch left it, and a second finger
                    // landing again re-enters ZOOM through the branch above.
                    if (pressed == 1 && canPan() && onPan != null) {
                        mode = GestureMode.PAN
                    } else {
                        // Still the tail of a pinch with nowhere to go. Keep consuming so the
                        // list below does not suddenly start scrolling.
                        event.changes.forEach { if (it.pressed) it.consume() }
                        continue
                    }
                }

                val change = event.changes.firstOrNull { it.pressed } ?: break
                if (change.isConsumed && mode == GestureMode.UNDECIDED) {
                    // Somebody inside claimed it first. Leave it alone.
                    break
                }

                // ---- HAS IT MOVED, AND HOW FAR FROM WHERE IT LANDED.
                //
                // Both are measured against remembered points rather than per frame, for the
                // reason in the note on [jitter].
                if ((change.position - stillAt).getDistance() > jitter) {
                    stillAt = change.position
                    stillSince = change.uptimeMillis
                }
                // LATCHED, NOT RECOMPUTED (sweep 4, N08). Once a finger has travelled past the
                // slop this gesture is no longer a press, and a drag that wandered out and came
                // back should not be able to buy the guarantee again by returning.
                if (nearDown) {
                    nearDown = (change.position - first.position).getDistance() <= slop
                }
                if (!nearDown) tapLike = false
                val stillFor = change.uptimeMillis - stillSince
                wait = HOLD_SCRUB_MS - stillFor

                val delta = change.positionChange()
                accX += delta.x
                accY += delta.y

                if (mode == GestureMode.UNDECIDED) {
                    // RE-EVALUATED here rather than reusing `holdPossible`, which was computed
                    // from the PREVIOUS frame's reading of `nearDown` because it had to be
                    // known before this event was awaited.
                    if (!holdArmed && nearDown && canPan() && stillFor >= HOLD_SCRUB_MS) {
                        // The finger stopped without ever really leaving where it landed.
                        holdArmed = true
                        mode = GestureMode.SCRUB
                        onHold()
                    } else if (kotlin.math.abs(accY) > slop &&
                        kotlin.math.abs(accY) > kotlin.math.abs(accX)
                    ) {
                        // ---- THE PAGE'S GESTURE, NOT THE CHART'S (review M01).
                        //
                        // Handed back untouched - nothing has been consumed - and this loop
                        // stops looking, so a later sideways wobble cannot reclaim a drag the
                        // reader has already committed to scrolling with.
                        break
                    } else if (kotlin.math.abs(accX) > slop) {
                        // A ZOOMED CHART PANS; AN UNZOOMED ONE SCRUBS. Read at the moment the
                        // drag is decided rather than when the finger landed, so a pinch that
                        // ends with one finger down leaves the right gesture behind it.
                        mode = if (canPan() && onPan != null) GestureMode.PAN
                        else GestureMode.SCRUB
                    }
                }

                when (mode) {
                    GestureMode.SCRUB -> {
                        change.consume()
                        pointAt(change.position.x)
                    }
                    GestureMode.PAN -> {
                        change.consume()
                        if (reported != ChartGesture.PAN) {
                            // Seeded before the first movement is applied, so the pan starts
                            // from the window on screen rather than from a stale one.
                            clearPoint()
                            report(ChartGesture.PAN)
                        }
                        val width = size.width.toFloat()
                        // The content follows the finger, so the WINDOW moves the other way -
                        // the same rule as the two-finger pan above.
                        if (width > 0f && delta.x != 0f) onPan?.invoke(-delta.x / width)
                    }
                    else -> Unit
                }
            }
        } finally {
            // In a `finally` because this loop can be cancelled - the composable leaving the
            // screen mid-drag - and a crosshair or a badge stranded on by a cancellation never
            // comes off.
            clearPoint()
            report(ChartGesture.NONE)
        }
        // ---- A PLAIN TAP: the second of two inside the double-tap window resets the zoom.
        val tap = tapLike && !holdArmed && liftedAt > 0L &&
            liftedAt - first.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        if (!tap) {
            lastTapUp = 0L
        } else if (lastTapUp > 0L &&
            first.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis &&
            (liftedPos - lastTapAt).getDistance() <= slop * 4f
        ) {
            lastTapUp = 0L
            doubleTap()?.invoke()
        } else {
            lastTapUp = liftedAt
            lastTapAt = liftedPos
        }
    }
}

private enum class GestureMode { UNDECIDED, SCRUB, PAN, ZOOM }

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

/**
 * The average x of every finger down, or NaN when there are none.
 *
 * The PAN reference, and the ZOOM's focal point. An average rather than the midpoint of the
 * measured pair, because a third finger landing should not make the window jump - the pair is
 * for measuring how much the hand SPREAD, which two fingers answer better than three; where
 * the hand IS, all of them answer.
 */
internal fun centroidX(event: androidx.compose.ui.input.pointer.PointerEvent): Float {
    var sum = 0f
    var n = 0
    for (c in event.changes) if (c.pressed) { sum += c.position.x; n++ }
    return if (n == 0) Float.NaN else sum / n
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
/**
 * THE COMPARISON'S ZERO: the moment both lines are measured from, or null for the previous-close
 * rule. FIXED FOR THE RANGE - it depends on the whole fetched series and the range, never on the
 * zoom window, which is what keeps "stock above SPY at 11:30" the same answer however the chart
 * is dragged (2026-09-22c; round 79 had exempted every intraday range, see [PriceChart]).
 *
 *  - 1D and Overnight: null - each line from its own previous close, as the unzoomed chart and
 *    the 1D chip measure them.
 *  - Every other range, 5D included: the first candle of the fetched series - or the
 *    BENCHMARK's first candle when that is later (full test 2026-09-24, C-3). KO, IBM or MSFT
 *    on "All" start decades before SPY (1993); anchored at the stock's first candle, SPY fell
 *    back to its own first close and the chart measured the stock from 1962 and SPY from 1993
 *    under a caption saying "both lines from the same start". Both now start where both exist.
 */
internal fun compareAnchorFor(
    shown: ChartSeries,
    range: ChartRange,
    benchmark: ChartSeries? = null
): Long? =
    if (range == ChartRange.D1 || range == ChartRange.OVERNIGHT) null
    else shown.points.firstOrNull()?.t?.let { t ->
        maxOf(t, benchmark?.points?.firstOrNull()?.t ?: t)
    }

/**
 * Both comparison lines over [drawn] (the on-screen slice of [shown]), measured from the ONE fixed
 * zero [compareAnchorFor] names: the benchmark through [comparePercents], the stock from its own
 * price at that same zero, looked up in [shown] - the whole series, so a fixed timestamp finds
 * the same price however far the window has panned. Null when the comparison cannot be made.
 */
internal fun compareLines(
    drawn: ChartSeries,
    benchmark: ChartSeries?,
    shown: ChartSeries,
    range: ChartRange,
    tipT: Long = Long.MAX_VALUE,
    zoomedIn: Boolean = false
): ComparePair? {
    val anchorT = compareAnchorFor(shown, range, benchmark)
    val other = comparePercents(drawn, benchmark, tipT, anchorT) ?: return null
    val ownFrom = if (anchorT != null) valueAtOrBefore(shown.points, anchorT) else shown.from
    val own = primaryPercents(drawn, fromPoint = zoomedIn, fromValue = ownFrom) ?: return null
    return ComparePair(own, other)
}

internal fun comparePercents(
    primary: ChartSeries?,
    compare: ChartSeries?,
    /**
     * The timestamp of the PRIMARY SERIES' TRUE LAST POINT (Round 64 sweep).
     *
     * THE BUG THIS CLOSES. The tip-pairing below used to fire on `primary.points.lastIndex`,
     * and since the zoom arrived `primary` is often a CLIPPED series whose last index is the
     * right-hand edge of the window rather than the newest candle. `cs.last().t` is always
     * later than a mid-window timestamp, so the branch fired on every zoomed intraday chart
     * and pasted SPY's CURRENT price onto a point in the middle of the morning: the benchmark
     * line jumped at its end and the out-performance figure compared 11:05 against 15:55.
     *
     * Passing the real tip means the pairing happens where it was meant to and nowhere else.
     * The default keeps every existing caller and test on the old behaviour.
     */
    primaryTipT: Long = Long.MAX_VALUE,
    /**
     * The moment the benchmark is rebased at, or null for the series' own rule.
     *
     * The mirror of [primaryPercents]'s `baseIndex`, and it has to be passed with it: two
     * percentage series drawn on one axis must share a zero, and the two functions choose
     * theirs independently. Non-null means a zoomed chart, where the shared zero is the first
     * point INSIDE the window rather than the carried candle before it.
     */
    baseT: Long? = null
): DoubleArray? {
    if (primary == null || compare == null) return null
    if (primary.isEmpty || compare.isEmpty) return null
    val pts = primary.points
    val cs = compare.points
    // A BENCHMARK THAT ENDS BEFORE THE STOCK'S WINDOW BEGINS IS NOT A COMPARISON (full test
    // 2026-09-24, C-5): every lookup carried its last value forward, so SPY drew flat at
    // yesterday's move beside today's pre-market line. And on 1D both lines must describe the
    // SAME session, or the spread subtracts yesterday's SPY move from today's stock move.
    if (cs.last().t < pts.first().t) return null
    if (primary.range == ChartRange.D1 && compare.range == ChartRange.D1 &&
        com.tj.portfolio.net.MarketClock.dayKey(cs.last().t * 1000L) !=
        com.tj.portfolio.net.MarketClock.dayKey(pts.last().t * 1000L)
    ) return null

    val base = if (baseT != null) {
        // A ZOOMED CHART. The window's own first point is the shared zero, for both lines -
        // including on an intraday range, where the previous close is no longer what the
        // reader is being shown a percentage of.
        // NO FALLBACK TO THE BENCHMARK'S OWN FIRST CLOSE (C-3): that is a different start
        // from the stock's, which is precisely the comparison this must never draw.
        valueAtOrBefore(cs, baseT) ?: return null
    } else if (primary.range == ChartRange.D1 || primary.range == ChartRange.OVERNIGHT) {
        // Both lines measured from their own previous close - the same reference the readout
        // and the chips use for an intraday window.
        compare.from
    } else {
        // Rebased to where the benchmark stood when THIS window opened. (Unanchored - only a
        // direct caller reaches this; [compareLines] always passes the shared anchor, which since
        // C-3 is never earlier than the benchmark's first candle.)
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
    // AND ONLY AT THE SERIES' REAL TIP - see [primaryTipT]. `MAX_VALUE` (the default) means
    // "the caller did not say", and then the drawn list's own last point is the tip, which is
    // true for every unzoomed chart.
    val pairTips = primary.range.intraday
    val lastIndex = pts.lastIndex
    val tipIsReal = primaryTipT == Long.MAX_VALUE || pts[lastIndex].t == primaryTipT
    val out = DoubleArray(pts.size)
    var drawn = 0
    for (i in pts.indices) {
        val v = if (pairTips && tipIsReal && i == lastIndex && cs.last().t > pts[i].t)
            cs.last().close
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
internal fun primaryPercents(
    s: ChartSeries,
    /**
     * Which point the percentages are measured FROM (Round 64 sweep 3).
     *
     * 0 - the default - keeps the series' own rule exactly: the previous close on an intraday
     * range, the first point on anything longer. Anything else is a ZOOMED chart, where the
     * first point of the drawn list is the carried candle just OUTSIDE the window: measuring
     * from it puts every percentage on the chart, and the axis they are drawn against, one
     * candle out of step with the picture.
     */
    baseIndex: Int = 0,
    /**
     * True to measure from `points[baseIndex]` rather than from the series' own rule.
     *
     * AN EXPLICIT FLAG, not "baseIndex != 0" (sweep 4). The index is 0 whenever the window
     * reaches past the left-hand end of the series - one pinch outward does it - and inferring
     * the rule from it silently put this line back on the previous close while everything else
     * on the chart had moved to the first point on screen.
     */
    fromPoint: Boolean = false,
    /**
     * The anchor price itself, looked up by the caller - overrides [baseIndex]/[fromPoint]
     * when given.
     *
     * FOR A FROZEN GESTURE ANCHOR (see the note in [PriceChart] where this is called). An
     * index into [s]`.points` only means the same candle as long as [s] itself does not
     * change shape, and a chart being actively panned re-slices its drawn series on every
     * frame - so a frozen INDEX quietly points at a different candle each time, while a
     * frozen VALUE, looked up once by timestamp from a series that is not being re-sliced,
     * does not.
     */
    fromValue: Double? = null
): DoubleArray? {
    if (s.isEmpty) return null
    val from = fromValue ?: if (fromPoint && baseIndex in s.points.indices) {
        s.points[baseIndex].close
    } else {
        s.from
    }
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
    fun pairedIndex(): Int? = pairedIndexIn(0, minOf(own.size, other.size) - 1)

    /**
     * The same question asked of a SLICE - the indices that are actually on screen.
     *
     * A zoomed chart's drawn list carries one point beyond each edge, so the plain
     * [pairedIndex] answers with a moment past the right-hand edge: the readout would print
     * the benchmark at a time the user is not looking at.
     */
    fun pairedIndexIn(first: Int, last: Int): Int? {
        val hi = minOf(last, own.size - 1, other.size - 1)
        val lo = maxOf(first, 0)
        for (i in hi downTo lo) if (own[i].isFinite() && other[i].isFinite()) return i
        return null
    }

    /**
     * The y-axis range over a SLICE of the two lines (Round 64 sweep 3).
     *
     * THE BUG THIS CLOSES. The first attempt at an honest y-axis computed a SECOND
     * [ComparePair] over the strictly-clipped series and took its bounds - which meant the
     * axis and the lines were rebased at two different moments, so on a zoomed five-year
     * comparison both lines were drawn several percent off the scale printed beside them.
     * One pair, one zero, and the axis is a slice of the very numbers being drawn.
     */
    fun boundsIn(first: Int, last: Int): DoubleArray {
        val hi = minOf(last, own.size - 1, other.size - 1)
        val lo = maxOf(first, 0)
        var min = 0.0
        var max = 0.0
        for (i in lo..hi) {
            val a = own[i]
            if (a.isFinite()) { if (a < min) min = a; if (a > max) max = a }
            val b = other[i]
            if (b.isFinite()) { if (b < min) min = b; if (b > max) max = b }
        }
        if (max - min < 1e-9) { min -= 1.0; max += 1.0 }
        return doubleArrayOf(min, max)
    }

    /**
     * How far ahead of the benchmark the stock is, in PERCENTAGE POINTS, at the last moment
     * both were measured. Null when they were never measured together.
     *
     * `+ 0.0` normalises a negative zero: without it a dead-level pair formats as "+-0.00",
     * because the sign is chosen before the number is formatted. The same guard `rangePct`
     * carries, for the same reason.
     */
    fun spread(first: Int = 0, last: Int = Int.MAX_VALUE): Double? {
        val i = pairedIndexIn(first, last) ?: return null
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

// -------------------------------------------------------------- the zoom window

/**
 * The part of a series inside a window, plus one point beyond each edge (Round 64).
 *
 * ---- WHY THE EXTRA POINT AT EACH END
 *
 * Without it the line stops at the first candle INSIDE the window, leaving a gap between the
 * edge of the chart and where the data starts - which reads as "nothing traded here" rather
 * than "the window begins mid-candle". Carrying one point beyond each edge lets the line be
 * drawn across the full width and clipped by the canvas, which is what every charting tool
 * does and what makes a pan feel like moving a sheet of paper rather than redrawing a picture.
 *
 * The window itself is what the x-axis is scaled to - see [ChartCanvas] - so those outside
 * points are drawn past the edges and simply not seen.
 *
 * Returns the series UNCHANGED when the window covers all of it, which is the ordinary case
 * and must stay allocation-free: this runs on every quote tick for every chart on screen.
 */
/**
 * Which of a drawn series' points fall inside the window (Round 64 sweep 3).
 *
 * The drawn list carries one candle beyond each edge so the line reaches both sides of the
 * picture; this is the range that is genuinely on screen, and it is what the y-axis, the
 * resting readout, the point count and the comparison's shared zero are all taken over.
 *
 * TOTAL. A window with no point inside it at all - a monthly line zoomed into a single day -
 * falls back to the whole list, because two points that straddle the window are the only line
 * that can be drawn there and the figures have to describe something.
 */
internal fun insideIndices(s: ChartSeries, w: ChartWindow?): IntRange {
    val pts = s.points
    if (w == null || pts.size < 2) return pts.indices
    val lowSec = w.startMs / 1000L
    val highSec = w.endMs / 1000L
    val first = pts.indexOfFirst { it.t >= lowSec }
    val last = pts.indexOfLast { it.t <= highSec }
    if (first < 0 || last < first) return pts.indices
    // ---- NEVER A SINGLE POINT (Round 64 sweep 5).
    //
    // A fine window over a coarse series - zooming into a monthly line while the finer fetch
    // is still on its way - can contain exactly one candle. Everything measured over one point
    // is degenerate in the same direction: the change is 0.00, the percentage is 0.00, and the
    // two y-axis corners print the same price - all of it drawn over a line that is visibly
    // sloping, because the CANVAS has the straddling pair. Reaching back one candle makes the
    // figures describe the line that is actually on screen, which is the honest answer to a
    // question the data cannot answer more precisely.
    if (last == first) {
        if (first > 0) return (first - 1)..last
        if (last < pts.lastIndex) return first..(last + 1)
    }
    return first..last
}

internal fun clipToWindow(
    s: ChartSeries?,
    w: ChartWindow?,
    /**
     * Whether to carry one point beyond each edge (Round 64 sweep).
     *
     * TWO DIFFERENT QUESTIONS, AND THEY NEEDED TWO DIFFERENT ANSWERS. Drawing wants the extra
     * points, so the line reaches both sides of the picture. MEASURING must not have them:
     * everything a `ChartSeries` derives - `first`, `last`, `from`, `change`, `changePct`,
     * `startMs`, `endMs` - comes from its point list, so a padded series reports the change
     * between two candles the user cannot see. On a one-month chart zoomed to a week that was
     * a nine-day figure printed over a seven-day picture, and on an all-time chart zoomed into
     * a single day it was the change between two monthly closes, labelled "over all". The
     * y-axis had the same fault: its top label could name a price nothing on screen reached.
     */
    pad: Boolean = true
): ChartSeries? {
    if (s == null || w == null || s.points.size < 2) return s
    val startSec = w.startMs / 1000L
    val endSec = w.endMs / 1000L
    if (s.points.first().t >= startSec && s.points.last().t <= endSec) return s

    val pts = s.points
    var lo = pts.indexOfFirst { it.t >= startSec }
    if (lo < 0) lo = pts.size - 1
    if (pad && lo > 0) lo--                // one before the left edge
    var hi = pts.indexOfLast { it.t <= endSec }
    if (hi < 0) hi = 0
    if (pad && hi < pts.lastIndex) hi++    // one after the right edge
    if (hi < lo) {
        // A window that falls between two candles - possible on a five-year weekly line
        // zoomed into a single day. Two points is the least that can be a line; showing the
        // pair that straddles the window is more honest than showing nothing.
        //
        // THE SAME PAIR IN BOTH MODES (sweep 4). With `pad` the left index has already been
        // stepped back, so `lo` IS the straddling point; without it, `lo` is the first point
        // AFTER the window and the pair starts one earlier. Written as `lo.coerceIn(...)` for
        // both, the padded and the strict clip returned different pairs - so the figures and
        // the line described different candles.
        lo = (if (pad) lo else lo - 1).coerceIn(0, pts.lastIndex - 1)
        hi = lo + 1
    }
    return s.copy(points = pts.subList(lo, hi + 1))
}

/**
 * The widest window the data can honestly support, for clamping a zoom.
 *
 * Built from the WIDEST series the app is holding for this symbol rather than from the one on
 * screen, so pinching out past the end of a one-month chart keeps going into the five-year one
 * instead of stopping dead at a boundary the user cannot see. Falls back to the drawn series
 * when nothing else is held, which is what happens on the very first chart of a session.
 */
internal fun windowBounds(drawn: ChartSeries?, all: Collection<ChartSeries>): ChartWindow? {
    // ---- THE AFTER-HOURS VIEW IS ITS OWN WORLD (Round 64 sweep).
    //
    // THE BUG THIS FIXES, and it was a bad one. The overnight series is the stretch from the
    // last regular close to now - so it sits ENTIRELY AFTER the 1D series, with no overlap at
    // all. Excluding it from the bounds (which is right: it is a filter, not a wider window)
    // while still clamping its window against them meant that the first pinch on the
    // after-hours chart slid the window back into the regular session, where the overnight
    // series has no points - and the chart went blank, with "Reset zoom" restoring it to the
    // same empty regular session. The only way out was another range chip.
    //
    // While it is what is drawn, it is the whole of what a zoom may cover.
    if (drawn != null && drawn.range == ChartRange.OVERNIGHT) {
        // The same `hi <= lo` guard the main path applies: two points stamped at the same
        // second would otherwise make a zero-width bound, which every clamp downstream would
        // then have to defend against separately.
        if (drawn.points.size < 2 || drawn.endMs <= drawn.startMs) return null
        return ChartWindow(drawn.startMs, drawn.endMs)
    }

    var lo = Long.MAX_VALUE
    var hi = Long.MIN_VALUE
    var haveMax = false
    for (s in all) {
        if (s.points.size < 2) continue
        // The after-hours view is a filtered stretch of days, not a wider or narrower window
        // over the same thing, so its extent says nothing about how far a zoom may go.
        if (s.range == ChartRange.OVERNIGHT) continue
        if (s.startMs < lo) lo = s.startMs
        if (s.endMs > hi) hi = s.endMs
        if (s.range == ChartRange.MAX) haveMax = true
    }
    if (lo == Long.MAX_VALUE || hi <= lo) {
        val d = drawn ?: return null
        if (d.points.size < 2) return null
        lo = d.startMs
        hi = d.endMs
        haveMax = d.range == ChartRange.MAX
    }

    // ---- HOW FAR OUT A PINCH MAY GO, WHICH IS NOT THE SAME AS WHAT IS LOADED.
    //
    // THE BUG THIS FIXES. Bounds built only from the series in hand meant that on a stock
    // opened for the first time - one 1D series cached, nothing else - a pinch outward
    // saturated instantly at today, because `ChartWindow.zoomed` clamps to the bounds' span.
    // TJ asked for exactly the opposite: *"pinch gesture in to zoom back out, all the way to
    // the stock's all time chart"*. The window is what DECIDES which range to fetch, so
    // bounding it by what has already been fetched is circular - nothing wider could ever be
    // asked for.
    //
    // So until the all-time series has actually arrived, a zoom may reach back as far as that
    // series could possibly go. The reach is optimistic on purpose, and it is corrected the
    // moment the data lands: once a MAX series is held, its real first point is the limit - a
    // company that listed in 2019 has no chart before 2019 and must not be zoomable into the
    // blank years before it. `ChartWindow.clamped` pulls the window back in when that happens.
    if (!haveMax) {
        val reach = hi - ChartRange.MAX.approxSpanMs
        if (reach < lo) lo = reach
    }
    return ChartWindow(lo, hi)
}
