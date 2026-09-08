package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.RangeChips
import com.tj.portfolio.ui.rangePct
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
 * THE RANGE CHIPS, RENDERED AND MEASURED (Round 62).
 *
 * `RangeChipTest` proves the arithmetic. This proves the things only a real composition can
 * answer, and every one of them is something this project has actually shipped wrong before:
 *
 *  - a figure that disagrees with the number directly below it on the same screen;
 *  - a row that goes ragged because one item has a second line and its neighbour does not;
 *  - a tap target that shrank when something was added to it (Round 46, measured at 33dp);
 *  - a blank that means two different things and says neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RangeChipUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun series(
        range: ChartRange,
        closes: List<Double>,
        baseline: Double = 0.0
    ) = ChartSeries(
        symbol = "FIVE",
        range = range,
        points = closes.mapIndexed { i, c -> ChartPoint(1_757_000_000L + i * 300L, c) },
        baseline = baseline,
        fetched = System.currentTimeMillis()
    )

    /** Everything a merged node says, as one string. */
    private fun textOf(label: String): String =
        rule.onNodeWithText(label).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()

    private fun chipHeights(): List<Int> =
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.size.height }

    // ------------------------------------------------------------------ the figure

    @Test
    fun `a chip carries what its own window did`() {
        show {
            RangeChips(
                selected = ChartRange.D1,
                onSelect = {},
                perf = mapOf(
                    ChartRange.D1 to 1.25,
                    ChartRange.M1 to -3.5,
                    ChartRange.Y5 to 140.0
                )
            )
        }
        assertTrue(textOf("1D"), textOf("1D").contains("+1.25%"))
        assertTrue(textOf("1M"), textOf("1M").contains("-3.50%"))
        assertTrue(textOf("5Y"), textOf("5Y").contains("+140.00%"))
    }

    /**
     * A window the app is holding nothing for says NOTHING - it does not say "0.00%", which
     * would be a claim, and it does not say "-", which reads as "there is no such thing".
     */
    @Test
    fun `a window with nothing held claims nothing`() {
        show {
            RangeChips(selected = ChartRange.D1, onSelect = {}, perf = mapOf(ChartRange.D1 to 1.25))
        }
        val quiet = textOf("6M")
        assertTrue("an unloaded window invented a figure: $quiet", !quiet.contains("%"))
        assertTrue("an unloaded window invented a zero: $quiet", !quiet.contains("0.00"))
    }

    /**
     * "Nothing yet" and "on its way" are different, exactly as they are on the empty chart.
     */
    @Test
    fun `a window being fetched says so instead of looking empty`() {
        show {
            RangeChips(
                selected = ChartRange.Y1,
                onSelect = {},
                perf = emptyMap(),
                loading = setOf(ChartRange.Y1)
            )
        }
        assertTrue(textOf("1Y"), textOf("1Y").contains("..."))
        assertTrue(textOf("6M"), !textOf("6M").contains("..."))
    }

    /** A figure that has arrived beats the spinner it replaces. */
    @Test
    fun `a figure that has arrived is not hidden by a refresh in flight`() {
        show {
            RangeChips(
                selected = ChartRange.Y1,
                onSelect = {},
                perf = mapOf(ChartRange.Y1 to 8.0),
                loading = setOf(ChartRange.Y1)
            )
        }
        val t = textOf("1Y")
        assertTrue(t, t.contains("+8.00%"))
        assertTrue(t, !t.contains("..."))
    }

    // -------------------------------------------- the chip cannot contradict the chart

    /**
     * THE ONE THAT MATTERS. The selected chip and the readout under the chart describe the
     * same window, one above the other, and a screen that shows two different answers to one
     * question is worse than a screen that shows none.
     *
     * Driven through the live-edge case on purpose: that is where the two are computed
     * differently - the chart replaces the last point and re-measures, the chip does the
     * arithmetic directly - so it is where they could drift apart.
     */
    @Test
    fun `the selected chip and the chart readout agree, live edge included`() {
        val s = series(ChartRange.D1, listOf(100.0, 101.0, 100.5), baseline = 100.0)
        val live = 103.75
        val pct = rangePct(s, live, true)!!
        show {
            Column {
                RangeChips(
                    selected = ChartRange.D1,
                    onSelect = {},
                    perf = mapOf(ChartRange.D1 to pct)
                )
                PriceChart(s, ChartRange.D1, loading = false, livePrice = live, liveEdge = true)
            }
        }
        val figure = Fmt.pctSigned(pct)
        assertEquals("+3.75%", figure)
        assertTrue("the chip lost its figure", textOf("1D").contains(figure))
        // The readout is the line that states the money change and the percentage together.
        val readout = rule.onAllNodes(hasTextAny()).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty() }
            .firstOrNull { (it.startsWith("+$") || it.startsWith("-$")) && it.endsWith("%") }
        assertTrue("no chart readout was rendered at all", readout != null)
        assertTrue(
            "chip says $figure, chart readout says $readout",
            readout!!.endsWith(figure)
        )
    }

    // ------------------------------------------------------------------ the layout

    /**
     * The reason the blank line is a non-breaking space. With an empty string the chips that
     * have a figure are taller than the ones that do not, and the row shows a visible step.
     */
    @Test
    fun `chips are all the same height whether or not they carry a figure`() {
        show {
            RangeChips(
                selected = ChartRange.D1,
                onSelect = {},
                perf = mapOf(ChartRange.D1 to 1.25, ChartRange.Y5 to -12.0)
            )
        }
        val heights = chipHeights().distinct()
        assertEquals("chips came out at different heights: $heights", 1, heights.size)
    }

    @Test
    fun `chips stay the same height at a large font scale`() {
        show(fontScale = 2f) {
            RangeChips(
                selected = ChartRange.M6,
                onSelect = {},
                perf = mapOf(ChartRange.M6 to 4.0),
                loading = setOf(ChartRange.Y1)
            )
        }
        val heights = chipHeights().distinct()
        assertEquals("chips came out at different heights at 2.0x: $heights", 1, heights.size)
    }

    /**
     * Round 46's lesson, re-measured because the chip changed shape. A near-miss on this row
     * lands on another range button, so it silently changes what you are looking at.
     */
    @Test
    fun `a chip carrying a figure is still at least 48dp tall`() {
        show {
            RangeChips(
                selected = ChartRange.D1,
                onSelect = {},
                perf = ChartRange.entries.associateWith { 2.5 }
            )
        }
        val density = 2.0f
        val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.size.height in 1 until (48 * density).toInt() }
        assertTrue("tap targets under 48dp: ${small.map { it.size.height / density }}",
            small.isEmpty())
    }

    @Test
    fun `chips with figures still fit and still select, at 1_3x`() {
        var range by mutableStateOf(ChartRange.D1)
        show(fontScale = 1.3f) {
            RangeChips(
                selected = range,
                onSelect = { range = it },
                perf = ChartRange.entries.associateWith { -11.25 }
            )
        }
        ChartRange.entries.forEach { r ->
            // SCROLLED TO FIRST. Eight chips with figures on them are wider than a phone, so
            // the last of them is genuinely off-screen - which is what the horizontal scroll
            // is for, and what a finger would do.
            rule.onNodeWithText(r.label).performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(r, range)
            assertTrue(textOf(r.label).contains("-11.25%"))
        }
    }

    @Test
    fun `figures survive dark mode`() {
        show(dark = true) {
            RangeChips(
                selected = ChartRange.MAX,
                onSelect = {},
                perf = mapOf(ChartRange.MAX to 623.5, ChartRange.D1 to -0.5)
            )
        }
        assertTrue(textOf("All").contains("+623.50%"))
        assertTrue(textOf("1D").contains("-0.50%"))
    }

    private fun hasTextAny() = androidx.compose.ui.test.SemanticsMatcher("has text") {
        it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
    }
}
