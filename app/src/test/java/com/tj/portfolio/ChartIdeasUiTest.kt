package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.FullScreenChart
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chart ideas Tj approved on 2026-09-24b, on a real composed chart: the value on touch-down,
 * double-tap to reset a zoom, the "last updated" note on an old chart, and the vs-SPY switch in
 * the full-screen view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartIdeasUiTest {

    @get:Rule val rule = createComposeRule()

    private fun series(range: ChartRange = ChartRange.D1, fetched: Long = System.currentTimeMillis()) =
        ChartSeries(
            symbol = "TEST", range = range,
            points = (0 until 100).map { ChartPoint(1_757_000_000L + it * 300L, 100.0 + it, 1000.0 + it) },
            baseline = 99.0, currency = "USD", fetched = fetched,
            regularOnly = range == ChartRange.D1
        )

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun texts(): List<String> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    @Test fun `a finger that lands shows the value there before it moves, and lifting clears it`() {
        show { PriceChart(series(), ChartRange.D1, loading = false) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { down(center) }
        rule.waitForIdle()
        val price = texts().firstOrNull { it.startsWith("$1") }
            ?.removePrefix("$")?.replace(",", "")?.toDoubleOrNull()
        assertTrue("no value on touch-down: ${texts()}", price != null && price in 130.0..170.0)
        assertFalse(texts().any { it.contains("since yesterday's close") })
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("lifting restores the window readout", texts().any { it.contains("since yesterday's close") })
    }

    @Test fun `double-tap resets a zoomed chart and does nothing to an unzoomed one`() {
        val s = series()
        var resets = 0
        val zoomed = ChartWindow(s.startMs + 20 * 300_000L, s.startMs + 60 * 300_000L)
        show {
            PriceChart(s, ChartRange.D1, loading = false, window = zoomed,
                windowBounds = ChartWindow(s.startMs, s.endMs), onWindow = {},
                onResetWindow = { resets++ })
        }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(1, resets)
    }

    @Test fun `double-tap on an unzoomed chart is not a reset`() {
        val s = series()
        var resets = 0
        show {
            PriceChart(s, ChartRange.D1, loading = false, window = null,
                windowBounds = ChartWindow(s.startMs, s.endMs), onWindow = {},
                onResetWindow = { resets++ })
        }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(0, resets)
    }

    @Test fun `an old chart whose refresh failed says when it was last updated`() {
        val old = series(ChartRange.M6, fetched = System.currentTimeMillis() - 7 * 3_600_000L)
        show { PriceChart(old, ChartRange.M6, loading = false) }
        rule.waitForIdle()
        assertTrue(texts().joinToString("\n"), texts().any { it.contains("last updated") })
    }

    @Test fun `a fresh chart, or one being refreshed, does not`() {
        show { PriceChart(series(ChartRange.M6), ChartRange.M6, loading = false) }
        rule.waitForIdle()
        assertFalse(texts().any { it.contains("last updated") })
    }

    @Test fun `the full-screen view has its own vs SPY switch`() {
        var toggles = 0
        val s = series(ChartRange.M6)
        show {
            FullScreenChart(
                symbol = "NVDA", series = s, range = ChartRange.M6, loading = false, onRange = {},
                perf = emptyMap(), loadingRanges = emptySet(), livePrice = 0.0, liveEdge = false,
                window = null, windowBounds = ChartWindow(s.startMs, s.endMs), onWindow = {},
                onResetWindow = {}, onZoomingChanged = {}, compare = null, compareLabel = "SPY",
                compareLivePrice = 0.0, onClose = {},
                compareOffered = true, compareOn = false, onToggleCompare = { toggles++ }
            )
        }
        rule.onNodeWithContentDescription("Compare against SPY").performClick()
        rule.waitForIdle()
        assertEquals(1, toggles)
    }
}
