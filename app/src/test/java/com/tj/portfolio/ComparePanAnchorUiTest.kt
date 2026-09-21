package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.BENCHMARK_SYMBOL
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.ComparePair
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.clipToWindow
import com.tj.portfolio.ui.comparePercents
import com.tj.portfolio.ui.insideIndices
import com.tj.portfolio.ui.primaryPercents
import com.tj.portfolio.ui.valueAtOrBefore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE SPY-OVERLAY PAN BUG, PINNED DOWN AGAIN (round 79) - this time with a fixed anchor.
 *
 * Round 67 (git history carries the old version of this file) froze the comparison anchor for
 * the life of a single continuous drag, then let it re-sync to the settled window's own first
 * candle the instant the finger lifted. Tj, with a second screen recording, reported the exact
 * symptom that fix was supposed to have already closed: *"if I move a stock chart by dragging
 * left or right when it is zoomed in, the spy baseline sometimes jumps up and down, making it
 * appear that sometimes the stock outperforms spy but when I move the chart, suddenly the stock
 * at the same point of time drops below spy."* Re-syncing on release IS that bug: two lines each
 * measured from a different day can swap which is on top for the same calendar date on screen,
 * and that is confusing regardless of how deliberately it was designed.
 *
 * The fix (`PriceChart.kt`'s `compareAnchorT`): the comparison anchor is now always the
 * selected range's true start, read from the whole fetched series, never the panned window's
 * edge - so it no longer matters whether a gesture is live, mid-drag, or long since released.
 * This test drags across many candles - well past what round 67's own version needed to move
 * the window at all - and checks the benchmark readout at every stage against the ONE fixed
 * anchor `PriceChart` now always uses, computed by calling its exact pure functions rather than
 * re-deriving the arithmetic by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComparePanAnchorUiTest {

    @get:Rule val rule = createComposeRule()

    private val t0 = 1_740_000_000L
    private val days = 200

    // A wide-swinging stock (roughly +300% over the whole series) beside a nearly-flat
    // benchmark (roughly +2%) - the same shape as NVDA against SPY, which is exactly what
    // makes the shared, stock-dominated y-axis amplify the benchmark's step and hide the
    // stock's own (see the note in PriceChart.kt where `cmp` is built).
    private fun stock() = ChartSeries(
        symbol = "TEST", range = ChartRange.Y1,
        points = (0 until days).map { ChartPoint(t0 + it * 86_400L, 100.0 + it * 1.5) },
        baseline = 100.0, fetched = System.currentTimeMillis()
    )

    private fun bench() = ChartSeries(
        symbol = BENCHMARK_SYMBOL, range = ChartRange.Y1,
        points = (0 until days).map { ChartPoint(t0 + it * 86_400L, 400.0 + it * 0.04) },
        baseline = 400.0, fetched = System.currentTimeMillis()
    )

    private val whole: ChartWindow get() = stock().let { ChartWindow(it.startMs, it.endMs) }

    // The middle third - narrow enough that a one-finger pan is offered at all (`canPanNow`
    // needs the window strictly narrower than the series), same shape ChartGestureTailUiTest
    // already uses for its own zoomed window.
    private val zoomed: ChartWindow
        get() = whole.let {
            val third = (it.endMs - it.startMs) / 3
            ChartWindow(it.startMs + third, it.endMs - third)
        }

    private val reported = ArrayList<ChartWindow>()

    private fun texts(): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

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
            series = stock(), range = ChartRange.Y1, loading = false,
            compare = bench(),
            window = w, windowBounds = whole,
            onWindow = { next -> w = next; reported.add(next) }
        )
    }

    /** The benchmark's readout percentage, e.g. "SPY +1.95%" -> 1.95. */
    private fun benchReadoutPct(): Double? {
        val line = texts().firstOrNull { it.startsWith("$BENCHMARK_SYMBOL ") } ?: return null
        return line.removePrefix("$BENCHMARK_SYMBOL ").removeSuffix("%")
            .removePrefix("+").toDoubleOrNull()
    }

    /**
     * What `PriceChart` itself computes for the benchmark readout given a specific settled
     * window, now that the anchor is always the series' own true start (`compareAnchorT`) - the
     * exact expression `cmp` builds in `PriceChart.kt`, called directly rather than re-derived
     * by hand.
     */
    private fun expectedBenchPct(window: ChartWindow): Double? {
        val fixedAnchorT = stock().points.first().t
        val drawn = clipToWindow(stock(), window, pad = true) ?: return null
        val inside = insideIndices(drawn, window)
        val other = comparePercents(drawn, bench(), baseT = fixedAnchorT) ?: return null
        val ownFromValue = valueAtOrBefore(stock().points, fixedAnchorT)
        val own = primaryPercents(drawn, fromPoint = true, fromValue = ownFromValue) ?: return null
        val idx = ComparePair(own, other).pairedIndexIn(inside.first, inside.last) ?: return null
        return other[idx]
    }

    @Test fun `the benchmark anchor never moves - not before, during, or after a drag`() {
        val startWindow = zoomed
        val expectedAtStart = expectedBenchPct(startWindow)
        assertTrue("test data has no benchmark readout at the start window", expectedAtStart != null)

        show { Chart(startWindow) }
        reported.clear()

        assertEquals(
            "the fixed anchor should already apply before any gesture",
            expectedAtStart!!, benchReadoutPct()!!, 0.05
        )

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { down(centerRight) }
        rule.waitForIdle()

        // Several SEPARATE drag steps within ONE continuous gesture - the finger is never
        // lifted between them - each one moving far enough that the cumulative pan crosses
        // several of the 200 daily candles. Round 67's version of this test only needed this
        // to distinguish a frozen anchor from a live one; here it exists to prove the anchor
        // does not move at all, however far the window is dragged.
        repeat(6) { i ->
            rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
                moveTo(Offset(centerRight.x - (i + 1) * 80f, centerRight.y))
            }
            rule.waitForIdle()
        }

        assertTrue(
            "the drag never moved the window - test setup is not exercising a real pan",
            reported.isNotEmpty()
        )
        val midDragWindow = reported.last()
        val expectedMidDrag = expectedBenchPct(midDragWindow)
        val renderedMidDrag = benchReadoutPct()
        assertTrue("no benchmark readout while dragging", renderedMidDrag != null)
        assertEquals(
            "mid-drag the anchor must still be the series' own true start, computed the exact " +
                "same way as before the gesture began",
            expectedMidDrag!!, renderedMidDrag!!, 0.05
        )

        // End the gesture. Nothing should change: the anchor was never tied to the window, so
        // lifting the finger has nothing to re-sync.
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
        rule.waitForIdle()
        val renderedAfterUp = benchReadoutPct()
        assertTrue("no benchmark readout after the gesture ends", renderedAfterUp != null)
        assertEquals(
            "lifting the finger must not move the anchor - this is what closes the 'stock at " +
                "the same point of time drops below spy' report",
            renderedMidDrag, renderedAfterUp!!, 0.001
        )
    }
}
