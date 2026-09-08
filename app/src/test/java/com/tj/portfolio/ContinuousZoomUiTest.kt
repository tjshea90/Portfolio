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

    @Test fun `one finger still scrubs while a window is set`() {
        show { ZoomableChart() }
        spread(steps = 6, perStep = 1.25f)
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.2f, height * 0.5f))
            moveTo(Offset(width * 0.6f, height * 0.5f))
        }
        rule.waitForIdle()
        val shown = texts()
        assertTrue(
            "the crosshair readout never appeared on a zoomed chart:\n" +
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
        val inside = clipToWindow(series(), w, pad = false)!!
        val b = priceBounds(inside)
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

    private fun texts(): List<String> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }
}
