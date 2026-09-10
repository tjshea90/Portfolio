package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.ui.BENCHMARK_SYMBOL
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE BENCHMARK OVERLAY, RENDERED (Round 63).
 *
 * `CompareChartTest` proves the arithmetic. This proves the thing arithmetic cannot: that the
 * screen says what the numbers mean. The specific failure being guarded against is a chart
 * whose axis has become a PERCENTAGE while its labels still read as DOLLARS - every number on
 * it real, every one of them describing a different chart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompareChartUiTest {

    @get:Rule val rule = createComposeRule()

    private val t0 = 1_757_000_000L

    /** The stock: 100 -> 120 over the window, measured from a previous close of 100. */
    private val stock = ChartSeries(
        symbol = "TEST", range = ChartRange.D1,
        points = (0 until 40).map { ChartPoint(t0 + it * 300L, 100.0 + it * 0.5) },
        baseline = 100.0, fetched = System.currentTimeMillis(), regularOnly = true
    )

    /** The benchmark: 400 -> 408, i.e. +2%, against the stock's +19.5%. */
    private val bench = ChartSeries(
        symbol = BENCHMARK_SYMBOL, range = ChartRange.D1,
        points = (0 until 40).map { ChartPoint(t0 + it * 300L, 400.0 + it * 0.2) },
        baseline = 400.0, fetched = System.currentTimeMillis(), regularOnly = true
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

    private fun all() = texts().joinToString("\n") { "  \"$it\"" }

    // -------------------------------------------------------------- without it

    @Test fun `with no benchmark the chart is unchanged`() {
        show { PriceChart(stock, ChartRange.D1, loading = false) }
        val t = texts()
        assertTrue("the y-axis should still be prices:\n" + all(), t.any { it.startsWith("$") })
        assertFalse("no legend without a second line", t.any { it.contains(BENCHMARK_SYMBOL) })
        assertFalse(
            "the caption must not claim percent mode",
            t.any { it.contains("Percent change") }
        )
    }

    // ----------------------------------------------------------------- with it

    @Test fun `the axis switches to percentages when comparing`() {
        show { PriceChart(stock, ChartRange.D1, loading = false, compare = bench) }
        val t = texts()
        // THE BUG THIS TEST EXISTS FOR: dollar labels left on a percentage axis.
        assertTrue(
            "the axis is still labelled in dollars on a percent chart:\n" + all(),
            t.none { it.matches(Regex("^\\$[0-9,.]+$")) }
        )
        assertTrue(
            "the axis should carry signed percentages:\n" + all(),
            t.any { it.matches(Regex("^[+-][0-9.]+%$")) }
        )
    }

    @Test fun `the caption says what the chart has become`() {
        show { PriceChart(stock, ChartRange.D1, loading = false, compare = bench) }
        assertTrue(
            "nothing on screen says the lines are percentages:\n" + all(),
            texts().any { it.contains("Percent change") && it.contains("same start") }
        )
    }

    @Test fun `the legend names both lines`() {
        show { PriceChart(stock, ChartRange.D1, loading = false, compare = bench) }
        val t = texts()
        assertTrue("the stock is not named:\n" + all(), t.any { it.trim() == "TEST" })
        assertTrue(
            "the benchmark is not named:\n" + all(),
            t.any { it.trim() == BENCHMARK_SYMBOL }
        )
    }

    @Test fun `the readout states what the market did over the same window`() {
        show { PriceChart(stock, ChartRange.D1, loading = false, compare = bench) }
        // The benchmark ran 400 -> 407.8, i.e. +1.95%.
        assertTrue(
            "the benchmark's figure is missing from the readout:\n" + all(),
            texts().any { it.startsWith("$BENCHMARK_SYMBOL +1.9") }
        )
    }

    @Test fun `scrubbing reads both lines at the same moment`() {
        show { PriceChart(stock, ChartRange.D1, loading = false, compare = bench) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        rule.waitForIdle()
        val t = texts()
        // Mid-window the stock is about $110 and the benchmark about +0.95%. The point is
        // that the benchmark figure shown is the one AT THE FINGER, not the whole window's.
        assertTrue("no scrubbed price:\n" + all(), t.any { it.startsWith("$1") })
        val benchLine = t.firstOrNull { it.startsWith("$BENCHMARK_SYMBOL ") }
        assertTrue("the benchmark is not read at the finger:\n" + all(), benchLine != null)
        val pct = benchLine!!.removePrefix("$BENCHMARK_SYMBOL ").removeSuffix("%")
            .removePrefix("+").toDoubleOrNull()
        assertTrue("unparseable benchmark figure: $benchLine", pct != null)
        assertTrue(
            "the benchmark figure $pct% looks like the window's end, not the finger",
            pct!! in 0.5..1.4
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    @Test fun `a benchmark that cannot be aligned simply is not drawn`() {
        // Its points all fall after the stock's window, so there is nothing honest to draw.
        val elsewhere = bench.copy(
            points = (0 until 10).map { ChartPoint(t0 + 900_000L + it * 300L, 400.0 + it) }
        )
        show { PriceChart(stock, ChartRange.D1, loading = false, compare = elsewhere) }
        val t = texts()
        assertFalse(
            "an unalignable benchmark must not switch the chart into percent mode:\n" + all(),
            t.any { it.contains("Percent change") }
        )
        assertTrue("the ordinary price axis should be back:\n" + all(), t.any { it.startsWith("$") })
    }

    @Test fun `the pinch and the overlay coexist`() {
        val steps = ArrayList<Int>()
        show {
            PriceChart(
                stock, ChartRange.D1, loading = false,
                compare = bench, onZoom = { steps.add(it) }
            )
        }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(center.x - 20f, center.y))
            down(1, androidx.compose.ui.geometry.Offset(center.x + 20f, center.y))
            moveTo(0, androidx.compose.ui.geometry.Offset(center.x - 200f, center.y))
            moveTo(1, androidx.compose.ui.geometry.Offset(center.x + 200f, center.y))
            up(0); up(1)
        }
        rule.waitForIdle()
        assertTrue("zoom stopped working with an overlay on the chart", steps.isNotEmpty())
        assertTrue(steps.all { it > 0 })
    }
}
