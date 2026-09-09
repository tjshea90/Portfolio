package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.EXPAND_TEST_TAG
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.RESET_ZOOM_TAG
import com.tj.portfolio.ui.clipToWindow
import com.tj.portfolio.ui.comparePercents
import com.tj.portfolio.ui.insideIndices
import com.tj.portfolio.ui.primaryPercents
import com.tj.portfolio.ui.priceBounds
import com.tj.portfolio.ui.windowBounds
import com.tj.portfolio.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE SMOOTH PINCH, DRIVEN BY REAL FINGERS (Round 64).
 *
 * TJ: *"make the pinch to zoom smooth instead of chopping between intervals."*
 *
 * `ChartWindowTest` proves the arithmetic; this proves the arithmetic is actually reached from
 * a touch, at the rate a hand moves, without taking anything away from the gestures that were
 * already there. The distinction matters because the last two rounds' worst bugs were both in
 * the wiring rather than in the maths: a handler pinned to a stale callback, and a gesture node
 * destroyed mid-pinch by a branch above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousZoomUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    /** Six months of daily candles, which is the window a pinch usually starts from. */
    private fun series(range: ChartRange = ChartRange.M6) = ChartSeries(
        symbol = "TEST", range = range,
        points = (0 until 180).map { ChartPoint(1_740_000_000L + it * 86_400L, 100.0 + it) },
        baseline = 100.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    private val bounds: ChartWindow
        get() = series().let { ChartWindow(it.startMs, it.endMs) }

    /**
     * The bounds `windowBounds` really produces before the all-time series has been fetched:
     * forty years of reach, so a pinch-out can ask for a range nobody has downloaded yet.
     *
     * WORTH A SEPARATE HARNESS. The first version of these tests passed the SERIES' OWN extent
     * as the bounds, which is the one shape the app never actually uses - and three real
     * defects hid in the difference, including a two-finger drag on an unzoomed chart panning
     * into decades of pre-history.
     */
    private val optimisticBounds: ChartWindow
        get() = windowBounds(series(), listOf(series()))!!

    /** Every window the chart reported, in order. */
    private val reported = ArrayList<ChartWindow>()

    /** How many times the reset affordance was used. */
    private var resets = 0

    /** The chart under test, holding the window the gesture gives it. */
    @Composable
    private fun ZoomableChart(
        expand: (() -> Unit)? = null,
        chartBounds: ChartWindow = bounds
    ) {
        var w by remember { mutableStateOf<ChartWindow?>(null) }
        PriceChart(
            series = series(),
            range = ChartRange.M6,
            loading = false,
            window = w,
            windowBounds = chartBounds,
            onWindow = { next -> w = next; reported.add(next) },
            onResetWindow = { w = null; resets++ },
            onExpand = expand
        )
    }

    /**
     * A two-finger spread, BOTH FINGERS MOVING IN ONE EVENT.
     *
     * `updatePointerTo` + `move()` rather than two `moveTo` calls, because two `moveTo`s are
     * two separate MotionEvents: the first moves one finger while the other stands still,
     * which is a genuine pinch inward, and the second is the matching pinch back out. Real
     * multi-touch hardware reports both pointers in the same event, and a test that does not
     * is measuring a gesture no hand can make.
     */
    private fun spread(steps: Int, perStep: Float, from: Float = 40f) {
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            var gap = from
            down(0, Offset(center.x - gap, center.y))
            down(1, Offset(center.x + gap, center.y))
            repeat(steps) {
                gap *= perStep
                updatePointerTo(0, Offset(center.x - gap, center.y))
                updatePointerTo(1, Offset(center.x + gap, center.y))
                move()
            }
            up(0); up(1)
        }
        rule.waitForIdle()
    }

    // ------------------------------------------------------------------ the zoom

    @Test fun `a spread narrows the window`() {
        show { ZoomableChart() }
        spread(steps = 6, perStep = 1.25f)
        assertTrue("no window was ever reported", reported.isNotEmpty())
        assertTrue(
            "a spread should show LESS time, not more: " +
                "${bounds.spanMs} -> ${reported.last().spanMs}",
            reported.last().spanMs < bounds.spanMs
        )
    }

    @Test fun `a pinch widens it again`() {
        show { ZoomableChart() }
        spread(steps = 8, perStep = 1.3f)
        val zoomedIn = reported.last().spanMs
        reported.clear()
        // Now the other way, from a fresh gesture.
        spread(steps = 8, perStep = 1f / 1.3f, from = 300f)
        assertTrue(
            "pinching back out should show MORE time: $zoomedIn -> ${reported.last().spanMs}",
            reported.last().spanMs > zoomedIn
        )
    }

    @Test fun `every frame of the gesture moves the window`() {
        // THE WHOLE COMPLAINT, MEASURED. Round 63's pinch could only report a change when it
        // crossed one of seven rungs, so a twelve-frame spread produced at most two or three
        // different pictures. A continuous one has to produce a different window on very
        // nearly every frame, or it is still chopping - just at a finer grain.
        show { ZoomableChart() }
        spread(steps = 12, perStep = 1.12f)
        val distinct = reported.map { it.spanMs }.distinct()
        assertTrue(
            "a twelve-frame spread produced only ${distinct.size} distinct windows: $distinct",
            distinct.size >= 8
        )
    }

    @Test fun `the zoom is monotonic - a steady spread never widens the window`() {
        show { ZoomableChart() }
        spread(steps = 10, perStep = 1.15f)
        val spans = reported.map { it.spanMs }
        for (i in 1 until spans.size) {
            assertTrue(
                "frame $i widened the window during a spread: $spans",
                spans[i] <= spans[i - 1]
            )
        }
    }

    @Test fun `the window never escapes the data`() {
        show { ZoomableChart() }
        spread(steps = 20, perStep = 1.4f)
        for (w in reported) {
            assertTrue("window started before the data: $w", w.startMs >= bounds.startMs)
            assertTrue("window ended after the data: $w", w.endMs <= bounds.endMs)
            assertTrue("window fell below the floor: $w", w.spanMs >= ChartWindow.MIN_SPAN_MS)
        }
    }

    // ------------------------------------------------------------------- the pan

    @Test fun `two fingers moved together pan without resizing`() {
        show { ZoomableChart() }
        spread(steps = 8, perStep = 1.3f)          // get zoomed in first
        val span = reported.last().spanMs
        val startedAt = reported.last().startMs
        reported.clear()

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(center.x - 60f, center.y))
            down(1, Offset(center.x + 60f, center.y))
            // Both fingers travel left by the same amount, IN ONE EVENT EACH TIME: no change
            // of separation at all, so this is a pure pan and must not resize anything.
            repeat(4) { i ->
                val dx = -30f * (i + 1)
                updatePointerTo(0, Offset(center.x - 60f + dx, center.y))
                updatePointerTo(1, Offset(center.x + 60f + dx, center.y))
                move()
            }
            up(0); up(1)
        }
        rule.waitForIdle()

        assertTrue("the pan reported nothing", reported.isNotEmpty())
        val moved = reported.last()
        assertEquals("a pan must not resize the window", span, moved.spanMs)
        assertTrue(
            "dragging the content leftwards should show LATER time: " +
                "$startedAt -> ${moved.startMs}",
            moved.startMs > startedAt
        )
    }

    // ------------------------------------------ what must NOT have been taken away

    /**
     * ROUND 65 CHANGED WHAT THIS ASSERTS, deliberately.
     *
     * Round 64 left one finger meaning "scrub" everywhere, so a zoomed chart could only be
     * moved with two. Round 65 gives a zoomed chart a one-finger pan and puts the crosshair
     * behind a press-and-hold - so the thing to prove here is that the crosshair is still
     * REACHABLE on a zoomed chart, not that a plain drag reaches it. `PanGestureUiTest` covers
     * the full contract; this keeps the check attached to a chart zoomed by real fingers.
     */
    @Test fun `the crosshair is still reachable on a chart zoomed by fingers`() {
        show { ZoomableChart() }
        spread(steps = 6, perStep = 1.25f)
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.2f, height * 0.5f))
            advanceEventTime(600)
            moveTo(Offset(width * 0.2f + 1f, height * 0.5f))
            moveTo(Offset(width * 0.6f, height * 0.5f))
        }
        rule.waitForIdle()
        val shown = texts()
        assertTrue(
            "press-and-hold did not reach the crosshair on a zoomed chart:\n" +
                shown.joinToString("\n"),
            shown.any { it.contains("$") }
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    @Test fun `the chart still lets the page scroll past it`() {
        // The regression this app has actually shipped once: a gesture on the chart that eats
        // the vertical scroll of the screen it sits in.
        var w by mutableStateOf<ChartWindow?>(null)
        rule.setContent {
            PortfolioTheme(dark = false) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("page")) {
                    Text("top", Modifier.height(30.dp))
                    PriceChart(
                        series = series(), range = ChartRange.M6, loading = false,
                        window = w, windowBounds = bounds, onWindow = { w = it }
                    )
                    repeat(40) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        val before = rule.onNodeWithTag(CHART_TEST_TAG)
            .fetchSemanticsNode().positionInRoot.y
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(center.x, center.y))
            moveTo(Offset(center.x, center.y - 200f))
            moveTo(Offset(center.x, center.y - 400f))
            up()
        }
        rule.waitForIdle()
        val after = rule.onNodeWithTag(CHART_TEST_TAG)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "a vertical drag on the chart no longer scrolls the page ($before -> $after)",
            after < before - 50f
        )
    }

    // -------------------------------------------------------------- the affordances

    @Test fun `there is no reset chip until the chart is actually zoomed`() {
        show { ZoomableChart() }
        rule.onNodeWithTag(RESET_ZOOM_TAG).assertDoesNotExist()
    }

    @Test fun `the reset chip appears once zoomed and puts the whole series back`() {
        show { ZoomableChart() }
        spread(steps = 8, perStep = 1.3f)
        rule.onNodeWithTag(RESET_ZOOM_TAG).assertIsDisplayed()
        reported.clear()
        rule.onNodeWithTag(RESET_ZOOM_TAG).performClick()
        rule.waitForIdle()
        // RESET IS THE ABSENCE OF A WINDOW, not a window covering everything: the bounds a
        // pinch may reach are deliberately wider than the data, so "select the bounds" would
        // put the chart somewhere the user has never seen.
        assertEquals("reset did not fire", 1, resets)
        rule.onNodeWithTag(RESET_ZOOM_TAG).assertDoesNotExist()
    }

    @Test fun `the expand button is there when there is somewhere to expand to`() {
        var opened = 0
        show { ZoomableChart(expand = { opened++ }) }
        rule.onNodeWithTag(EXPAND_TEST_TAG).assertIsDisplayed()
        rule.onNodeWithTag(EXPAND_TEST_TAG).performClick()
        rule.waitForIdle()
        assertEquals("tapping expand did not open the full-screen chart", 1, opened)
    }

    @Test fun `a chart with nowhere to expand to shows no button`() {
        show { ZoomableChart() }
        rule.onNodeWithTag(EXPAND_TEST_TAG).assertDoesNotExist()
    }


    // ------------------------------------- against the bounds the app really produces

    @Test fun `a two-finger drag on an unzoomed chart moves nothing`() {
        // THE REGRESSION THIS PROVES FIXED. Once the bounds became optimistic - forty years,
        // so a pinch-out can reach a series that has not been fetched - the pan's own "is the
        // whole series on screen" guard stopped firing, because the window was no longer as
        // wide as the bounds. A two-finger drag on an ordinary chart, which used to do
        // nothing at all, dragged the window back into decades of pre-history and left the
        // line blank until a wider fetch landed.
        show { ZoomableChart(chartBounds = optimisticBounds) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(center.x - 60f, center.y))
            down(1, Offset(center.x + 60f, center.y))
            repeat(4) { i ->
                val dx = -40f * (i + 1)
                updatePointerTo(0, Offset(center.x - 60f + dx, center.y))
                updatePointerTo(1, Offset(center.x + 60f + dx, center.y))
                move()
            }
            up(0); up(1)
        }
        rule.waitForIdle()
        assertTrue(
            "a pan on an unzoomed chart moved the window to $reported",
            reported.isEmpty()
        )
    }

    @Test fun `an unzoomed chart shows no reset chip even when the bounds reach further`() {
        // The bounds are how far a pinch MAY go, not what the user is looking at. Measured
        // against them, every window counted as zoomed - so a chart pinched all the way back
        // out kept a "Reset zoom" chip it could never get rid of.
        show { ZoomableChart(chartBounds = optimisticBounds) }
        rule.onNodeWithTag(RESET_ZOOM_TAG).assertDoesNotExist()
        // Pinch in and straight back out again by the same amount.
        spread(steps = 6, perStep = 1.3f)
        spread(steps = 12, perStep = 1f / 1.3f, from = 300f)
        rule.waitForIdle()
        assertTrue(
            "pinching back out did not restore the whole series: ${reported.last().spanMs} " +
                "of ${bounds.spanMs}",
            reported.last().spanMs >= bounds.spanMs
        )
    }

    // ------------------------------------------------ the axis says what is on screen

    @Test fun `the y-axis labels name prices the line actually reaches`() {
        // THE REGRESSION THIS PROVES FIXED. The corner labels were switched to the strictly
        // clipped series while the canvas went on scaling to the padded one, so on every
        // zoomed chart the axis was stretched by a candle the user could not see and the top
        // label named a price the line never reaches.
        show { ZoomableChart(chartBounds = optimisticBounds) }
        spread(steps = 10, perStep = 1.25f)
        val w = reported.last()
        // The zoomed chart measures from the first point ON SCREEN, and drops the previous
        // close as a baseline - it is outside the window, and widening the axis to reach it
        // would put a price on the corner that the line never goes near. So the expected
        // labels are the plain high and low of the points inside the window.
        val inside = clipToWindow(series(), w, pad = false)!!.copy(baseline = 0.0)
        val b = priceBounds(inside)
        val closes = inside.points.map { it.close }
        val shown = texts()
        assertTrue(
            "the top label does not name the high of what is on screen " +
                "(${Fmt.price(b[1])}):\n" + shown.joinToString("\n"),
            shown.contains(Fmt.price(b[1]))
        )
        assertTrue(
            "the bottom label does not name the low of what is on screen " +
                "(${Fmt.price(b[0])}):\n" + shown.joinToString("\n"),
            shown.contains(Fmt.price(b[0]))
        )
        // AND THE PROPERTY BEHIND BOTH: every corner label is a price the line reaches.
        assertTrue(
            "the axis labels ${b.toList()} fall outside the drawn prices " +
                "${closes.min()}..${closes.max()}",
            b[0] >= closes.min() - 1e-9 && b[1] <= closes.max() + 1e-9
        )
    }

    @Test fun `a zoomed chart names the window rather than the range`() {
        // The range chip above the chart still shows the whole range's figure, so a readout
        // saying "over 6m" beside a three-week picture is two figures on one screen
        // disagreeing about the same window.
        show { ZoomableChart(chartBounds = optimisticBounds) }
        spread(steps = 10, perStep = 1.25f)
        val shown = texts()
        assertTrue(
            "the readout still claims to cover the whole range:\n" + shown.joinToString("\n"),
            shown.none { it == "over 6m" }
        )
        assertTrue(
            "nothing on the chart names the window that is actually drawn:\n" +
                shown.joinToString("\n"),
            shown.any { it.startsWith("over ") }
        )
    }


    // ------------------------------------------------ the comparison, once zoomed

    /** A benchmark line covering the same window, so the overlay can be drawn. */
    private fun benchmark() = ChartSeries(
        symbol = "SPY", range = ChartRange.M6,
        points = (0 until 180).map { ChartPoint(1_740_000_000L + it * 86_400L, 400.0 + it * 0.5) },
        baseline = 400.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    @Composable
    private fun ComparedChart() {
        var w by remember { mutableStateOf<ChartWindow?>(null) }
        PriceChart(
            series = series(),
            range = ChartRange.M6,
            loading = false,
            window = w,
            windowBounds = optimisticBounds,
            onWindow = { next -> w = next; reported.add(next) },
            onResetWindow = { w = null },
            compare = benchmark(),
            compareLabel = "SPY"
        )
    }

    @Test fun `a zoomed comparison chart keeps a percentage axis`() {
        // THE REGRESSION THIS PROVES FIXED. The y-axis briefly took its VALUE from one
        // comparison and its UNIT from another, and the two were computed independently - so a
        // narrow zoom could print the stock's price in dollars with a percent sign after it,
        // or draw two percentage lines against a dollar scale.
        show { ComparedChart() }
        spread(steps = 10, perStep = 1.25f)
        val shown = texts()
        val corners = shown.filter { it.matches(Regex("[+-]\\d+\\.\\d\\d%")) }
        assertTrue(
            "the axis labels are not percentages on a comparison chart:\n" +
                shown.joinToString("\n"),
            corners.size >= 2
        )
        val values = corners.map { it.removeSuffix("%").toDouble() }
        assertTrue(
            "a percentage axis label is the size of a share price: $values",
            values.all { kotlin.math.abs(it) < 500.0 }
        )
    }

    @Test fun `both lines of a zoomed comparison share one zero`() {
        // Two percentage series on one axis must be rebased at the same moment. When they were
        // not, both lines were drawn several percent off the scale printed beside them - on a
        // weekly or monthly series, the whole return of the candle just off the left edge.
        val s = series()
        val b = benchmark()
        val w = ChartWindow(
            s.points[60].t * 1000L, s.points[120].t * 1000L
        )
        val drawn = clipToWindow(s, w, pad = true)!!
        val range = insideIndices(drawn, w)
        val baseT = drawn.points[range.first].t
        val own = primaryPercents(drawn, range.first, fromPoint = true)!!
        val other = comparePercents(drawn, b, Long.MAX_VALUE, baseT)!!
        assertEquals(
            "the stock's line does not start at zero in its own window",
            0.0, own[range.first], 1e-9
        )
        assertEquals(
            "the benchmark's line does not start at the same zero",
            0.0, other[range.first], 1e-9
        )
    }


    // ------------------------------------------------------- the affordances, measured

    @Test fun `the expand button is a real tap target and is described out loud`() {
        // It was 34dp - under this app's own measured 48dp minimum, a rule it took two rounds
        // to learn on this very screen - and an unlabelled Canvas, which TalkBack announces as
        // a nameless button.
        var opened = 0
        show { ZoomableChart(expand = { opened++ }) }
        val d = rule.density.density
        val n = rule.onNodeWithTag(EXPAND_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val b = n.boundsInRoot
        assertTrue(
            "the expand button measured ${(b.right - b.left) / d}x" +
                "${(b.bottom - b.top) / d}dp, under the 48dp minimum",
            (b.right - b.left) / d >= 47f && (b.bottom - b.top) / d >= 47f
        )
        assertTrue(
            "the expand button has no content description for a screen reader",
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
        )
    }

    @Test fun `neither affordance sits on top of a price label`() {
        // Both used to live in the top-right corner, which is where the high-price label is:
        // they abutted at the default font scale and overlapped from about 1.15x upward.
        show { ZoomableChart(expand = {}, chartBounds = optimisticBounds) }
        spread(steps = 8, perStep = 1.3f)
        val labels = rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("a price label") {
                it.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString(" ")?.startsWith("$") == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("no price labels were drawn", labels.isNotEmpty())
        for (tag in listOf(EXPAND_TEST_TAG, RESET_ZOOM_TAG)) {
            val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
                .boundsInRoot
            for (l in labels) {
                val overlaps = b.left < l.right && l.left < b.right &&
                    b.top < l.bottom && l.top < b.bottom
                assertTrue("$tag is drawn over a price label ($b vs $l)", !overlaps)
            }
        }
    }


    // ------------------------------------------- what a pinch OUTWARD must not do

    @Test fun `pinching outward past the data still says what it is measuring`() {
        // THE REGRESSION THIS PROVES FIXED. The measurements switched to "the window's first
        // point on screen" whenever a window existed, while every label that explains what
        // those measurements MEAN switched on a separate "is it zoomed" test that only looked
        // for a NARROWER window. One pinch outward on a 1D chart landed between the two: the
        // readout quietly measured from the session's open while still printing "since
        // yesterday's close" beside it, and offered no way back.
        show { ZoomableChart(chartBounds = optimisticBounds) }
        spread(steps = 10, perStep = 1f / 1.25f, from = 300f)
        assertTrue("the pinch reported nothing", reported.isNotEmpty())
        val w = reported.last()
        assertTrue(
            "this test needs a window wider than the series to be meaningful " +
                "(${w.spanMs} vs ${bounds.spanMs})",
            w.spanMs > bounds.spanMs
        )
        val shown = texts()
        assertTrue(
            "a chart showing more than its own data still calls itself the plain range:\n" +
                shown.joinToString("\n"),
            shown.none { it == "over 6m" }
        )
        assertTrue(
            "there is no way back from a chart the user has visibly moved",
            rule.onAllNodesWithTag(RESET_ZOOM_TAG).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test fun `a rising chart zoomed into a falling stretch reports the fall`() {
        // The readout's colour is taken from the very series its figure is measured over, so
        // this is the checkable half of "a negative change must not be printed in green": a
        // six-month line that rises overall, zoomed into a stretch where the stock fell, has
        // to print a NEGATIVE change. It used to print the window's fall in the whole series'
        // colour, because the two came from different places.
        val rising = ChartSeries(
            symbol = "TEST", range = ChartRange.M6,
            points = (0 until 180).map { i ->
                // Up overall, with a clear dip between day 100 and day 130.
                val v = if (i in 100..130) 200.0 - (i - 100) else 100.0 + i
                ChartPoint(1_740_000_000L + i * 86_400L, v)
            },
            baseline = 100.0, fetched = System.currentTimeMillis()
        )
        assertTrue("the fixture must rise overall", rising.change > 0.0)
        val dip = ChartWindow(
            rising.points[105].t * 1000L, rising.points[128].t * 1000L
        )
        show {
            PriceChart(
                series = rising, range = ChartRange.M6, loading = false,
                window = dip,
                windowBounds = ChartWindow(rising.startMs, rising.endMs),
                onWindow = {}, onResetWindow = {}
            )
        }
        val shown = texts()
        assertTrue(
            "the readout does not report the window's fall:\n" + shown.joinToString("\n"),
            shown.any { it.startsWith("-$") || it.contains("   -") }
        )
    }

    private fun texts(): List<String> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }
}
