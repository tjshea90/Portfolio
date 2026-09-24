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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.bridgedChart
import com.tj.portfolio.ui.windowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Full test 2026-09-24, C-1: A ZOOM THAT CROSSES INTO A RANGE NOT FETCHED YET KEEPS GOING.
 *
 * The detail screen switches range as the zoom window narrows or widens. The first time a
 * stock is zoomed, the new range has no series yet - and handing PriceChart a null series
 * swaps its chart node for the placeholder node, orphaning the fingers already on the glass
 * (Compose hit-tests on DOWN only). The harness below is the detail screen's wiring in
 * miniature: M6 is fetched, anything narrower than [NEEDS_M1_BELOW] needs M1, which never
 * arrives during the gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomIntoUnfetchedUiTest {

    @get:Rule val rule = createComposeRule()

    private val day = 86_400_000L
    private val NEEDS_M1_BELOW = 40 * day

    private fun series(range: ChartRange = ChartRange.M6) = ChartSeries(
        symbol = "TEST", range = range,
        points = (0 until 180).map { ChartPoint(1_740_000_000L + it * 86_400L, 100.0 + it) },
        baseline = 100.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    private val reported = ArrayList<ChartWindow>()

    @Composable
    private fun DetailLikeChart(bridge: Boolean) {
        var w by remember { mutableStateOf<ChartWindow?>(null) }
        var pinching by remember { mutableStateOf(false) }
        val lastDrawn = remember { arrayOfNulls<Pair<ChartSeries, ChartRange>>(1) }
        val range = if ((w?.spanMs ?: Long.MAX_VALUE) < NEEDS_M1_BELOW) ChartRange.M1 else ChartRange.M6
        val chart = if (range == ChartRange.M6) series() else null   // M1 is still loading
        if (chart != null) lastDrawn[0] = chart to range
        val (drawn, drawnRange) =
            if (bridge) bridgedChart(chart, range, lastDrawn[0], zoomed = w != null, awaiting = true)
            else chart to range
        PriceChart(
            series = drawn,
            range = drawnRange,
            loading = chart == null,
            window = w,
            windowBounds = windowBounds(series(), listOf(series())),
            onWindow = { next -> w = next; reported.add(next) },
            onResetWindow = { w = null },
            onZoomingChanged = { pinching = it }
        )
    }

    private fun show(bridge: Boolean) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) {
                    Box(Modifier.fillMaxSize()) { DetailLikeChart(bridge) }
                }
            }
        }
    }

    /** A two-finger spread, both fingers moving in one event (see ContinuousZoomUiTest). */
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

    @Test fun `a spread keeps zooming after its window crosses into an unfetched range`() {
        show(bridge = true)
        spread(steps = 12, perStep = 1.3f)
        val narrowest = reported.minOf { it.spanMs }
        assertTrue(
            "the zoom stopped at ${narrowest / day} days - it should have carried on well past " +
                "the ${NEEDS_M1_BELOW / day}-day switch into the range that had not arrived",
            narrowest < NEEDS_M1_BELOW / 2
        )
    }

    /**
     * THE HARNESS CAN SEE THE BUG: the same spread handing PriceChart the null series, which
     * is what the detail screen did before C-1. If this ever stops failing to zoom, PriceChart
     * has learned to keep one gesture node across the placeholder and the bridge is no longer
     * load-bearing.
     */
    @Test fun `without the bridge the same spread dies at the switch`() {
        show(bridge = false)
        spread(steps = 12, perStep = 1.3f)
        val narrowest = reported.minOf { it.spanMs }
        assertTrue("expected the gesture to die near the switch, got ${narrowest / day} days",
            narrowest >= NEEDS_M1_BELOW / 2)
    }

    @Test fun `the bridge only covers a live zoom whose series is still coming`() {
        val m6 = series()
        val last = m6 to ChartRange.M6
        // Live zoom, series coming: draw the last one, with ITS range.
        assertEquals(last, bridgedChart(null, ChartRange.M1, last, zoomed = true, awaiting = true))
        // Not zoomed (a chip tap), or nothing coming (a real failure): the truth, placeholder and all.
        assertEquals(null to ChartRange.M1,
            bridgedChart(null, ChartRange.M1, last, zoomed = false, awaiting = true))
        assertEquals(null to ChartRange.M1,
            bridgedChart(null, ChartRange.M1, last, zoomed = true, awaiting = false))
        // A real series always wins.
        val m1 = series(ChartRange.M1)
        assertEquals(m1 to ChartRange.M1,
            bridgedChart(m1, ChartRange.M1, last, zoomed = true, awaiting = true))
    }
}
