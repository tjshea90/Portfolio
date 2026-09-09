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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.tj.portfolio.ui.RESET_ZOOM_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ONE FINGER: PAN, SCRUB, OR THE PAGE'S (Round 65).
 *
 * TJ, on a zoomed chart: *"one finger should move the chart along, and press and hold should
 * still let me read a price."*
 *
 * Round 64 gave a zoomed chart nowhere to go but a two-finger drag, and two fingers is the
 * harder gesture to make one-handed on a phone. Giving one finger a second meaning is cheap to
 * describe and expensive to get right, because the chart is a strip of a page that scrolls and
 * a one-finger drag already means three different things depending on where it goes.
 *
 * EVERY TEST HERE IS DRIVEN BY REAL TOUCH EVENTS, and four of them exist because the first
 * draft of this feature shipped the bug they describe. Source review cannot answer "did the
 * page still scroll" or "did a slow drag pan"; only injecting the events can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanGestureUiTest {

    @get:Rule val rule = createComposeRule()

    /** Six months of daily candles - the same shape `ContinuousZoomUiTest` pinches. */
    private fun series() = ChartSeries(
        symbol = "TEST", range = ChartRange.M6,
        points = (0 until 180).map { ChartPoint(1_740_000_000L + it * 86_400L, 100.0 + it) },
        baseline = 100.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    private val whole: ChartWindow get() = series().let { ChartWindow(it.startMs, it.endMs) }

    /** The middle third: a genuinely zoomed-in window with room to move both ways. */
    private val zoomed: ChartWindow
        get() = whole.let {
            val third = (it.endMs - it.startMs) / 3
            ChartWindow(it.startMs + third, it.endMs - third)
        }

    /** Every window the chart reported, in order. */
    private val reported = ArrayList<ChartWindow>()

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    /** The chart under test, starting at [start] and holding whatever the gesture gives it. */
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

    private fun texts(): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    /** True while the crosshair is up: the readout stops describing the whole window. */
    private fun scrubbing() = texts().none { it.contains("Over ") || it.contains("since ") }

    // ------------------------------------------------------------ the feature itself

    @Test fun `one finger moves the window on a zoomed chart`() {
        show { Chart(zoomed) }
        val before = zoomed
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.75f, height * 0.5f))
            moveTo(Offset(width * 0.25f, height * 0.5f))
            up()
        }
        rule.waitForIdle()

        assertTrue("a one-finger drag on a zoomed chart moved nothing", reported.isNotEmpty())
        val after = reported.last()
        assertEquals("a pan must not resize the window", before.spanMs, after.spanMs)
        assertTrue(
            "dragging the content leftwards should show LATER time: " +
                "${before.startMs} -> ${after.startMs}",
            after.startMs > before.startMs
        )
    }

    @Test fun `one finger still scrubs when the chart is not zoomed`() {
        // THE GESTURE TJ USES EVERY DAY. Whatever else this round changed, an unzoomed chart
        // has to behave exactly as v7.4 did.
        show { Chart(null) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.2f, height * 0.5f))
            moveTo(Offset(width * 0.6f, height * 0.5f))
        }
        rule.waitForIdle()
        assertTrue(
            "an unzoomed chart no longer scrubs:\n" + texts().joinToString("\n"),
            scrubbing()
        )
        assertTrue("an unzoomed drag moved the window", reported.isEmpty())
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    @Test fun `press and hold then drag scrubs a zoomed chart instead of panning`() {
        // THE WAY BACK TO THE CROSSHAIR once one finger means "pan". The hold is measured from
        // the last movement, so pressing and waiting arms it whatever the chart is showing.
        show { Chart(zoomed) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.5f, height * 0.5f))
            advanceEventTime(600)
            // A pixel of drift, so the loop gets an event carrying the elapsed time.
            moveTo(Offset(width * 0.5f + 1f, height * 0.5f))
            moveTo(Offset(width * 0.8f, height * 0.5f))
        }
        rule.waitForIdle()

        assertTrue(
            "press-and-hold did not scrub a zoomed chart:\n" + texts().joinToString("\n"),
            scrubbing()
        )
        assertTrue(
            "press-and-hold panned instead of scrubbing - it reported $reported",
            reported.isEmpty()
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    // ------------------------------------------------- the three the old suite could not catch

    /**
     * REVIEW M01. The first draft asked "has the finger moved horizontally?" to decide whether
     * it was still, so a page scroll that paused on the chart looked exactly like a press and
     * hold: the chart armed a scrub, consumed everything from then on, and the page stopped
     * dead under the finger.
     *
     * NO EXISTING TEST COULD CATCH IT. Every scroll test in this suite swipes in one continuous
     * motion well under the hold threshold, so none of them ever reaches the branch. This one
     * rests 600ms in the middle of the swipe, which is what a thumb actually does.
     */
    @Test fun `a vertical drag that rests on a zoomed chart still scrolls the page`() {
        rule.setContent {
            PortfolioTheme(dark = false) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Text("top", Modifier.height(30.dp))
                    Chart(zoomed)
                    repeat(40) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        val before = rule.onNodeWithTag(CHART_TEST_TAG).fetchSemanticsNode().positionInRoot.y

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(center.x, center.y))
            moveTo(Offset(center.x, center.y - 120f))
            // THE PAUSE. Longer than the hold threshold, in the middle of a real scroll.
            advanceEventTime(600)
            moveTo(Offset(center.x, center.y - 240f))
            moveTo(Offset(center.x, center.y - 360f))
            up()
        }
        rule.waitForIdle()

        val after = rule.onNodeWithTag(CHART_TEST_TAG).fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "a vertical drag that paused on the chart stopped the page scrolling " +
                "($before -> $after)",
            after < before - 50f
        )
        assertTrue("a page scroll moved the chart's window", reported.isEmpty())
    }

    /**
     * REVIEW M02. The first draft timed the hold from the PRESS, so any drag slow enough to
     * take a third of a second to travel one touch slop became a hold-scrub - and a small,
     * careful pan is exactly that drag. The feature never fired for the gesture it was built
     * for.
     *
     * THE DRAG HERE IS SIZED FROM THE REAL TOUCH SLOP rather than from a pixel count, because
     * the threshold this is about is the slop and a literal would be testing the emulator's
     * density instead. It crosses the slop in about 420ms - comfortably past the 350ms hold -
     * and must still pan.
     *
     * (A finger drifting slower than about half a slop per hold window IS treated as still,
     * deliberately: at that speed it is a resting thumb's tremor, not a pan. See `jitter`.)
     */
    @Test fun `a slow drag that outlasts the hold still pans`() {
        var slop = 0f
        rule.setContent {
            slop = LocalViewConfiguration.current.touchSlop
            PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { Chart(zoomed) } }
        }
        rule.waitForIdle()
        assertTrue("the touch slop could not be read", slop > 0f)

        val before = zoomed
        val step = slop * 0.15f
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            val x0 = width * 0.75f
            val y = height * 0.5f
            down(Offset(x0, y))
            for (i in 1..8) {
                advanceEventTime(60)
                moveTo(Offset(x0 - i * step, y))
            }
            up()
        }
        rule.waitForIdle()

        assertTrue(
            "a slow drag was captured as a hold-scrub and never panned " +
                "(slop=${slop}px, ${step}px every 60ms)",
            reported.isNotEmpty()
        )
        val after = reported.last()
        assertEquals("the slow drag resized the window", before.spanMs, after.spanMs)
        assertTrue(
            "the slow drag moved the window the wrong way: " +
                "${before.startMs} -> ${after.startMs}",
            after.startMs > before.startMs
        )
    }

    /**
     * REVIEW M03. The caption offered "drag to move" whenever the chart was zoomed at all,
     * while the pan itself refuses a window that is not NARROWER than the line - so on a
     * pinched-OUT chart the caption named a gesture that did nothing when tried.
     */
    @Test fun `the caption does not offer a pan on a window wider than the series`() {
        val wide = whole.let {
            val pad = (it.endMs - it.startMs) / 2
            ChartWindow(it.startMs - pad, it.endMs + pad)
        }
        show { Chart(wide) }
        rule.waitForIdle()
        val caption = texts().firstOrNull { it.contains("pinch to zoom") }
        assertTrue("the gesture caption is missing entirely:\n" + texts().joinToString("\n"),
            caption != null)
        assertTrue(
            "the caption offers a pan the chart will refuse: \"$caption\"",
            !caption!!.contains("drag to move")
        )
    }

    @Test fun `the caption does offer a pan on a zoomed-in chart`() {
        show { Chart(zoomed) }
        rule.waitForIdle()
        val caption = texts().firstOrNull { it.contains("pinch to zoom") }
        assertTrue("the gesture caption is missing entirely", caption != null)
        assertTrue(
            "a zoomed chart can be panned but the caption does not say so: \"$caption\"",
            caption!!.contains("drag to move")
        )
    }

    // ---------------------------------------------------------- the rest of the review

    /**
     * REVIEW M08. The Reset chip is a tappable target in the top-left of the plot. Leaving it
     * up through a pan puts it under the moving finger and paints a label over the line being
     * dragged - the pinch already hides it, and a pan is the same kind of gesture.
     */
    @Test fun `the reset chip is not on screen during a pan`() {
        show { Chart(zoomed) }
        rule.waitForIdle()
        assertTrue(
            "a zoomed chart should offer a way back out before the gesture starts",
            rule.onAllNodesWithTag(RESET_ZOOM_TAG).fetchSemanticsNodes().isNotEmpty()
        )

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(width * 0.75f, height * 0.5f))
            moveTo(Offset(width * 0.35f, height * 0.5f))
        }
        rule.waitForIdle()
        assertTrue(
            "the reset chip stayed on screen through the pan",
            rule.onAllNodesWithTag(RESET_ZOOM_TAG).fetchSemanticsNodes().isEmpty()
        )

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(
            "the reset chip did not come back when the finger lifted",
            rule.onAllNodesWithTag(RESET_ZOOM_TAG).fetchSemanticsNodes().isNotEmpty()
        )
    }

    /**
     * SWEEP 1, N01. The hold is only armed on a chart where it CHANGES something.
     *
     * An unzoomed chart already scrubs on a plain drag, so a hold there would buy nothing -
     * and it would cost something real, because an armed hold consumes from that moment on,
     * before the touch slop. A press that paused and then turned into a page scroll would stop
     * the page dead, on the one chart shape that never needed the gesture. This is the same
     * swipe as the M01 test with the zoom taken away.
     */
    @Test fun `a rest on an UNZOOMED chart does not capture the page scroll`() {
        rule.setContent {
            PortfolioTheme(dark = false) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Text("top", Modifier.height(30.dp))
                    Chart(null)
                    repeat(40) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        val before = rule.onNodeWithTag(CHART_TEST_TAG).fetchSemanticsNode().positionInRoot.y

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(Offset(center.x, center.y))
            // The pause comes FIRST here, before anything has moved: the most favourable
            // moment there is for a hold to arm.
            advanceEventTime(600)
            moveTo(Offset(center.x, center.y - 4f))
            moveTo(Offset(center.x, center.y - 160f))
            moveTo(Offset(center.x, center.y - 320f))
            up()
        }
        rule.waitForIdle()

        val after = rule.onNodeWithTag(CHART_TEST_TAG).fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "a press-and-hold on an unzoomed chart swallowed the page's scroll " +
                "($before -> $after)",
            after < before - 50f
        )
    }

    /**
     * REVIEW M04. A pan writes the window several times per recomposition, so the composed
     * `window` lags what the fingers have actually done. The first draft re-read that lagging
     * value when a second finger landed, which snapped the chart back to an older window at
     * the exact moment the user added a finger.
     *
     * COMPOSITION IS FROZEN HERE on purpose - `autoAdvance = false` - so the lag is total
     * rather than a matter of luck. With the re-seed, the pinch starts from the window the
     * chart was given at the beginning and every reading jumps backwards; without it, the
     * pinch continues from wherever the pan left off.
     */
    @Test fun `a second finger landing mid-pan does not snap the window back`() {
        show { Chart(zoomed) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(width * 0.8f, height * 0.5f))
            moveTo(0, Offset(width * 0.4f, height * 0.5f))
            moveTo(0, Offset(width * 0.2f, height * 0.5f))
        }
        val afterPan = reported.lastOrNull()
        assertTrue("the pan reported nothing at all", afterPan != null)

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            // A second finger, then both moving together: a pure pan, no spread.
            down(1, Offset(width * 0.6f, height * 0.5f))
            updatePointerTo(0, Offset(width * 0.18f, height * 0.5f))
            updatePointerTo(1, Offset(width * 0.58f, height * 0.5f))
            move()
            up(0); up(1)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val afterPinch = reported.last()
        assertTrue(
            "the window jumped backwards when the second finger landed: " +
                "${afterPan!!.startMs} -> ${afterPinch.startMs}",
            afterPinch.startMs >= afterPan.startMs - afterPan.spanMs / 20
        )
    }
}
