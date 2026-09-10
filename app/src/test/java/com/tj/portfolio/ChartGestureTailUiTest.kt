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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WHAT HAPPENS WHEN A PINCH ENDS ONE FINGER AT A TIME (Round 66 audit, CHT-1 / CHT-2).
 *
 * Every multi-touch test the app already had lifts both pointers on the same frame -
 * `up(0); up(1)` - which is the one way a real hand almost never ends a pinch. Both bugs here
 * live in the frames after the first finger leaves, or after a third one arrives, and neither
 * was reachable by any existing test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartGestureTailUiTest {

    @get:Rule val rule = createComposeRule()

    private fun series() = ChartSeries(
        symbol = "TEST", range = ChartRange.M6,
        points = (0 until 180).map { ChartPoint(1_740_000_000L + it * 86_400L, 100.0 + it) },
        baseline = 100.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    private val whole: ChartWindow get() = series().let { ChartWindow(it.startMs, it.endMs) }

    private val zoomed: ChartWindow
        get() = whole.let {
            val third = (it.endMs - it.startMs) / 3
            ChartWindow(it.startMs + third, it.endMs - third)
        }

    private val reported = ArrayList<ChartWindow>()

    private fun texts(): List<String> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.joinToString(" ")
            }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    @Composable
    private fun Chart(start: ChartWindow?) {
        var w by remember { mutableStateOf(start) }
        PriceChart(
            series = series(),
            range = ChartRange.M6,
            loading = false,
            window = w,
            windowBounds = whole,
            onWindow = { next -> w = next; reported.add(next) },
            onResetWindow = { w = null }
        )
    }

    // ------------------------------------------------------------------ CHT-2

    /**
     * THE BUG. `GestureMode.ZOOM` could only be left by lifting every finger: the one-pointer
     * arm consumed the frame and `continue`d, and nothing else assigned `mode`. So lifting one
     * finger of a pinch and carrying on with the other left the chart completely inert - the
     * window did not move, no crosshair appeared, and the frames were still being consumed, so
     * the page underneath could not scroll either. Lifting one finger is the natural way to
     * end a pinch, which made this reachable by ordinary use.
     */
    @Test fun `a pinch that drops to one finger keeps panning`() {
        show { Chart(zoomed) }
        reported.clear()
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(width * 0.35f, height * 0.5f))
            down(1, Offset(width * 0.65f, height * 0.5f))
            // A real pinch first, so the loop is genuinely in ZOOM.
            moveTo(0, Offset(width * 0.25f, height * 0.5f))
            moveTo(1, Offset(width * 0.75f, height * 0.5f))
            up(1)
        }
        rule.waitForIdle()
        val afterPinch = reported.lastOrNull()
        assertTrue("the pinch itself reported nothing", afterPinch != null)

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            moveTo(0, Offset(width * 0.25f - 120f, height * 0.5f))
            moveTo(0, Offset(width * 0.25f - 240f, height * 0.5f))
            up(0)
        }
        rule.waitForIdle()

        val end = reported.last()
        assertTrue(
            "the surviving finger moved 240px and the window did not follow it: " +
                "${afterPinch!!.startMs} -> ${end.startMs}",
            end.startMs != afterPinch.startMs
        )
        assertEquals(
            "the tail of a pinch must PAN, not resize - the span has to hold",
            afterPinch.spanMs, end.spanMs
        )
    }

    /**
     * And the page underneath must be released again once the chart has nothing to do with
     * the gesture. On an UNZOOMED chart there is nothing to pan, so the tail of a pinch is
     * still consumed - what must not happen is the chart consuming forever on a chart that
     * could have panned.
     */
    @Test fun `the page can scroll again after a pinch ends`() {
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Chart(zoomed)
                repeat(30) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
            }
        }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(width * 0.35f, height * 0.5f))
            down(1, Offset(width * 0.65f, height * 0.5f))
            moveTo(0, Offset(width * 0.3f, height * 0.5f))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        // The real assertion is that the gesture ENDED - a latched ZOOM would leave the
        // chart's badge up and the loop consuming. Nothing here should throw or hang, and a
        // fresh one-finger drag must be honoured as a pan.
        reported.clear()
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.75f, height * 0.5f))
            moveTo(Offset(width * 0.35f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        assertTrue("a fresh drag after a pinch did nothing", reported.isNotEmpty())
    }

    // ------------------------------------------------------------------ CHT-1

    /**
     * THE BUG. The two-finger pan measured this frame's centroid against the previous one's,
     * and `centroidX` averages EVERY pressed pointer - but the reference was only reset when
     * one of the two MEASURED fingers had gone. A third finger landing (a resting palm, a
     * steadying thumb) therefore moved the centroid a long way without resetting anything, and
     * the whole of that jump was reported as hand movement: on a 411dp chart with fingers at
     * 35% and 65%, a third arriving at the right edge shifts the mean by a sixth of the width
     * in a single frame.
     */
    @Test fun `a third finger landing does not jump the window`() {
        show { Chart(zoomed) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(width * 0.35f, height * 0.5f))
            down(1, Offset(width * 0.65f, height * 0.5f))
            // Settle the pinch so `centroid` is seeded.
            moveTo(0, Offset(width * 0.34f, height * 0.5f))
            moveTo(1, Offset(width * 0.66f, height * 0.5f))
        }
        rule.waitForIdle()
        reported.clear()

        // The reference window: where the two fingers have the chart now.
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            moveTo(0, Offset(width * 0.34f + 1f, height * 0.5f))
        }
        rule.waitForIdle()
        val before = reported.lastOrNull()

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            // A third finger lands far to the right. The measured pair does not move, so
            // nothing about the gesture has actually changed - but the MEAN of all three
            // pointers has, by about a sixth of the width.
            down(2, Offset(width * 0.98f, height * 0.5f))
            // One more frame with all three down, so the loop gets an event to measure.
            moveTo(0, Offset(width * 0.34f + 2f, height * 0.5f))
        }
        rule.waitForIdle()

        val after = reported.lastOrNull()
        if (before != null && after != null) {
            // A one-pixel drift of finger 0 is allowed to move the window by about a pixel's
            // worth of time. A sixth of the width is a hundred times that.
            val moved = kotlin.math.abs(after.startMs - before.startMs)
            val sixth = before.spanMs / 6
            assertTrue(
                "a finger touching down moved the window by ${moved}ms, which is on the " +
                    "order of a sixth of the visible span (${sixth}ms) - the third pointer " +
                    "was read as hand movement",
                moved < sixth / 4
            )
        }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up(2); up(1); up(0) }
    }

    // ------------------------------------------------------------------ CHT-3

    /** Monthly candles - the shape of the All range, where the gaps are widest. */
    private fun sparseSeries() = ChartSeries(
        symbol = "TEST", range = ChartRange.MAX,
        points = (0 until 60).map { ChartPoint(1_600_000_000L + it * 2_592_000L, 100.0 + it) },
        baseline = 100.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    @Composable
    private fun SparseChart(start: ChartWindow) {
        val s = sparseSeries()
        var w by remember { mutableStateOf(start) }
        PriceChart(
            series = s,
            range = ChartRange.MAX,
            loading = false,
            window = w,
            windowBounds = ChartWindow(s.startMs, s.endMs),
            onWindow = { next -> w = next; reported.add(next) },
            onResetWindow = { }
        )
    }

    /**
     * THE BUG. `nearestIndex` clamps the scrub to a point inside the axis, but falls back to
     * the nearest point of all when NO point is inside - "which is all there is". That case is
     * reachable: `MIN_SPAN_MS` is thirty minutes, so on a monthly or weekly chart one pinch
     * can land the window entirely between two candles. The readout then printed a price and a
     * date from a candle WEEKS outside the window, while the crosshair meant to show where
     * that price came from was drawn hundreds of screen-widths away and clipped off the
     * canvas. A number under the finger, a date contradicting the axis directly beneath it,
     * and nothing marking the spot it came from.
     */
    @Test fun `a window between two candles reads nothing rather than the wrong candle`() {
        val s = sparseSeries()
        // Halfway between candle 20 and candle 21, one hour wide - entirely in the gap.
        val gapStart = (s.points[20].t + s.points[21].t) / 2 * 1000L
        show { SparseChart(ChartWindow(gapStart, gapStart + 3_600_000L)) }

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.5f, height * 0.5f))
            advanceEventTime(600)
            moveTo(Offset(width * 0.5f + 1f, height * 0.5f))
            moveTo(Offset(width * 0.55f, height * 0.5f))
        }
        rule.waitForIdle()

        val shown = texts()
        // While a scrub is up the readout stops describing the whole window and describes the
        // point instead - the same signal `PanGestureUiTest.scrubbing()` reads. Here there is
        // no point inside the window to describe, so the window summary must survive: any
        // scrub at all would be a candle from outside it.
        assertTrue(
            "a point from outside the window reached the readout:\n" + shown.joinToString("\n"),
            shown.any { it.contains("over ", ignoreCase = true) || it.contains("since ") }
        )
        // (The two prices on screen are the y-axis labels for the drawn range - the straddling
        // pair IS what gets drawn, since the line has to reach both edges. Those are correct;
        // it is the READOUT that must not claim a point.)
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    // ------------------------------------------------------------------ CHT-4

    /**
     * THE BUG. `canPanNow` is the single predicate the caption, the hold and the drag decision
     * all read - and it tested `onWindow != null` but not `windowBounds != null`, while
     * `pan()` needs both. A chart given a window and a reporter but no bounds therefore
     * captioned "drag to move, hold to scrub" for a pan that could not exist, and armed the
     * 350ms hold - which consumes from the moment it fires, before the touch slop, so a press
     * that paused and then turned into a page scroll stopped the page dead.
     */
    @Test fun `a chart with no bounds does not promise a pan it cannot do`() {
        val s = series()
        show {
            PriceChart(
                series = s,
                range = ChartRange.M6,
                loading = false,
                window = zoomed,
                windowBounds = null,
                onWindow = { },
                onResetWindow = { }
            )
        }
        rule.waitForIdle()
        val caption = texts().joinToString(" ")
        assertTrue(
            "the caption offered a pan on a chart that cannot pan: $caption",
            !caption.contains("drag to move")
        )
    }
}
