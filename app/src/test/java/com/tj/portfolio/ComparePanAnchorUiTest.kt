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
 * THE SPY-OVERLAY PAN BUG, PINNED DOWN (round 67).
 *
 * TJ, with a screen recording: *"when I put a stock chart in full screen and scroll left to
 * right, the stock chart stays accurate but the spy line jumps up and down with the dotted
 * line."*
 *
 * `CompareChartTest`'s new `fromValue` cases prove the arithmetic is sound in isolation; this
 * proves the THREADING is right - that the anchor `PriceChart` actually draws from stays fixed
 * for as long as a real gesture (finger down, several moves, no lift) is live, and only
 * re-syncs to the settled window once it ends. Both the "if frozen" and "if not frozen"
 * expectations are computed here by calling the exact same pure functions `PriceChart` itself
 * calls, fed the REAL window it reported through `onWindow` at each step - not a hand-predicted
 * one - so this cannot pass by coincidence of the touch-to-pixel math working out.
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
     * window and a specific rebase anchor - the exact expression `cmp` builds in
     * `PriceChart.kt`, called directly rather than re-derived by hand.
     */
    private fun expectedBenchPct(window: ChartWindow, anchorT: Long?): Double? {
        val drawn = clipToWindow(stock(), window, pad = true) ?: return null
        val inside = insideIndices(drawn, window)
        val other = comparePercents(drawn, bench(), baseT = anchorT) ?: return null
        val ownFromValue = anchorT?.let { valueAtOrBefore(stock().points, it) }
        val own = primaryPercents(drawn, fromPoint = anchorT != null, fromValue = ownFromValue)
            ?: return null
        val idx = ComparePair(own, other).pairedIndexIn(inside.first, inside.last) ?: return null
        return other[idx]
    }

    @Test fun `the benchmark anchor is frozen while the gesture is live, and re-syncs once it ends`() {
        val startWindow = zoomed
        val drawnAtStart = clipToWindow(stock(), startWindow, pad = true)!!
        val insideAtStart = insideIndices(drawnAtStart, startWindow)
        val frozenAnchorT = drawnAtStart.points[insideAtStart.first].t

        show { Chart(startWindow) }
        reported.clear()

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { down(centerRight) }
        rule.waitForIdle()

        // Several SEPARATE drag steps within ONE continuous gesture - the finger is never
        // lifted between them - each one moving far enough that the cumulative pan crosses
        // several of the 200 daily candles.
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
        val renderedMidDrag = benchReadoutPct()
        assertTrue("no benchmark readout while dragging", renderedMidDrag != null)

        val ifFrozen = expectedBenchPct(midDragWindow, frozenAnchorT)
        val liveDrawn = clipToWindow(stock(), midDragWindow, pad = true)!!
        val liveAnchorT = liveDrawn.points[insideIndices(liveDrawn, midDragWindow).first].t
        val ifNotFrozen = expectedBenchPct(midDragWindow, liveAnchorT)

        assertTrue(
            "test data does not distinguish a frozen anchor from a live one at this drag " +
                "distance (frozen=$ifFrozen, live=$ifNotFrozen) - widen the drag or the candle spacing",
            ifFrozen != null && ifNotFrozen != null && kotlin.math.abs(ifFrozen - ifNotFrozen) > 0.3
        )
        assertEquals(
            "mid-drag the chart should still be using the anchor from when the gesture began, " +
                "not the live window's own first candle",
            ifFrozen!!, renderedMidDrag!!, 0.05
        )

        // End the gesture - the anchor should now re-sync to the settled window.
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
        rule.waitForIdle()
        val renderedAfterUp = benchReadoutPct()
        assertTrue("no benchmark readout after the gesture ends", renderedAfterUp != null)
        assertEquals(
            "after the finger lifts the anchor should re-sync to the settled window",
            ifNotFrozen!!, renderedAfterUp!!, 0.05
        )
    }
}
