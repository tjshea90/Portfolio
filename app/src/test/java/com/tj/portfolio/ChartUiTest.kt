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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.FundHolding
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.SectorWeight
import com.tj.portfolio.ui.HoldingsTab
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.RangeChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE ROUND 58 SCREENS, ACTUALLY RENDERED AND MEASURED.
 *
 * Same argument as `UiTest` and `DetailTabsUiTest`: this project has shipped layout bugs
 * that reading the source could not have caught - a percentage silently clipped off the
 * right edge of a fixed-width column, and a 33dp tap target that a careful review had
 * already signed off as fixed. So the new widgets are composed for real, at the font scales
 * a phone actually uses, in both themes.
 *
 * The three things asserted are the three that have gone wrong before:
 *   1. nothing runs off the right edge, at 1.0 and at 1.3 font scale;
 *   2. every tappable thing is big enough to hit;
 *   3. the chart states its window in words - which is the literal thing TJ asked for, so
 *      an empty caption is a regression, not a cosmetic detail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun hasTextAny() = androidx.compose.ui.test.SemanticsMatcher("has text") {
        it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
    }

    private fun rootWidth(): Float =
        rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
            .maxOf { it.size.width }.toFloat()

    private fun assertNothingOverflows(label: String) {
        val w = rootWidth()
        val nodes = rule.onAllNodes(hasTextAny()).fetchSemanticsNodes()
        assertTrue("$label: nothing rendered at all", nodes.isNotEmpty())
        val spills = nodes.filter { it.boundsInRoot.right > w + 0.5f }.map {
            "\"${it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")}\" " +
                "ends at ${it.boundsInRoot.right} (screen is $w)"
        }
        assertTrue("$label: text runs off the right edge:\n  " + spills.joinToString("\n  "),
            spills.isEmpty())
    }

    /** `size`, not `boundsInRoot` - a row straddling a clipping edge reports only what shows. */
    private fun assertTouchTargets(label: String, minDp: Int) {
        val density = 2.0f
        val min = minDp * density
        val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
            it.size.height > 0 && it.size.height < min - 0.5f
        }.map {
            "\"${it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "(icon)"}\"" +
                " is ${"%.0f".format(it.size.height / density)}dp tall"
        }
        assertTrue("$label: tap targets under ${minDp}dp:\n  " + small.joinToString("\n  "),
            small.isEmpty())
    }

    // ---------------------------------------------------------------- fixtures

    private fun series(
        range: ChartRange = ChartRange.D1,
        n: Int = 78,
        start: Double = 240.0,
        baseline: Double = 239.0
    ) = ChartSeries(
        symbol = "FIVE", range = range,
        points = (0 until n).map {
            ChartPoint(1_757_000_000L + it * 300L, start + Math.sin(it / 6.0) * 3.0)
        },
        baseline = baseline, currency = "USD", fetched = System.currentTimeMillis()
    )

    private fun fund() = FundHoldings(
        symbol = "TESTF", isFund = true, quoteType = "ETF",
        holdings = listOf(
            FundHolding("NVDA", "NVIDIA Corporation", 0.0812),
            FundHolding("MSFT", "Microsoft Corporation", 0.0705),
            FundHolding("BRK.B", "Berkshire Hathaway Inc. Class B Common Stock", 0.0161)
        ),
        sectors = listOf(
            SectorWeight("Technology", 0.3412),
            SectorWeight("Communication services", 0.0881)
        ),
        category = "Large Blend", family = "Test Funds", expenseRatio = 0.0003,
        stockPct = 0.9954, cashPct = 0.0041, otherPct = 0.0005,
        fetched = System.currentTimeMillis()
    )

    // ------------------------------------------------------------------- chart

    /** The literal answer to "I cannot see how long the chart is tracking". */
    @Test
    fun `the chart states its window in words`() {
        show { PriceChart(series(ChartRange.M6), ChartRange.M6, loading = false) }
        rule.onNodeWithText(ChartRange.M6.caption, substring = true).assertExists()
        assertNothingOverflows("6M chart")
    }

    /**
     * ONE composition, every range driven through the chips - which is also the real
     * interaction, rather than eight independent first-compositions. `createComposeRule` is
     * a per-test rule, so a loop of fresh rules is not available here and would not be a
     * better test if it were.
     */
    @Test
    fun `every range renders without overflowing and its caption follows the chips`() {
        var range by mutableStateOf(ChartRange.D1)
        show {
            androidx.compose.foundation.layout.Column {
                RangeChips(selected = range, onSelect = { range = it })
                PriceChart(series(range), range, loading = false)
            }
        }
        ChartRange.entries.forEach { r ->
            rule.onNodeWithText(r.label).performClick()
            rule.waitForIdle()
            assertEquals(r, range)
            rule.onNodeWithText(r.caption, substring = true).assertExists()
            assertNothingOverflows("range ${r.label}")
        }
    }

    /**
     * Round 46 found a 33dp target on this screen that a review had already called fixed.
     * Eight chips in a row is the worst case for a mis-tap: everything nearby is another
     * range button, so a near-miss silently changes what you are looking at.
     */
    @Test
    fun `every range chip is at least 48dp tall`() {
        show { RangeChips(selected = ChartRange.D1, onSelect = {}) }
        assertTouchTargets("range chips", 48)
    }

    @Test
    fun `the chips still fit at a larger font scale`() {
        show(fontScale = 1.3f) { RangeChips(selected = ChartRange.Y5, onSelect = {}) }
        assertTouchTargets("range chips at 1.3x", 48)
        ChartRange.entries.forEach { rule.onNodeWithText(it.label).assertExists() }
    }

    /**
     * "No chart" and "the chart has not arrived yet" are different situations, and showing
     * the first for the second is alarming. Both must say which is which.
     */
    @Test
    fun `a chart that has not arrived yet says it is loading`() {
        show { PriceChart(null, ChartRange.Y1, loading = true) }
        rule.onNodeWithText("Loading", substring = true).assertExists()
        // ...and does NOT say the chart is unavailable, which is the alarming reading.
        rule.onAllNodes(hasTextAny()).fetchSemanticsNodes().forEach {
            val s = it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()
            assertTrue("a loading chart claimed to be unavailable: $s",
                !s.contains("No 1Y chart"))
        }
    }

    @Test
    fun `an unavailable chart says so and points at the fix`() {
        show { PriceChart(null, ChartRange.Y1, loading = false) }
        rule.onNodeWithText("pull down", substring = true).assertExists()
    }

    @Test
    fun `a chart with one point is treated as no chart rather than drawn`() {
        val one = ChartSeries("X", ChartRange.D1, listOf(ChartPoint(1, 10.0)))
        show { PriceChart(one, ChartRange.D1, loading = false) }
        rule.onNodeWithText("No 1D chart", substring = true).assertExists()
    }

    @Test
    fun `the chart survives a large font scale in dark mode`() {
        show(fontScale = 1.3f, dark = true) {
            PriceChart(series(ChartRange.OVERNIGHT), ChartRange.OVERNIGHT, loading = false)
        }
        assertNothingOverflows("after-hours chart, dark, 1.3x")
    }

    // ---------------------------------------------------------------- holdings

    @Test
    fun `the holdings tab lists each holding with its weight`() {
        show { HoldingsTab(fund(), loading = false, onOpenSymbol = {}) }
        rule.onNodeWithText("NVDA").assertExists()
        rule.onNodeWithText("8.12%").assertExists()
        // SectionHeader uppercases its text - assert what is actually drawn.
        rule.onNodeWithText("TOP HOLDINGS").assertExists()
        assertNothingOverflows("holdings tab")
    }

    /**
     * The honesty line. The free feed gives the top of the book, not the register, and the
     * tab has to say so - dropping this line is how a limit quietly becomes a lie.
     */
    @Test
    fun `the holdings tab says how much of the fund it is showing`() {
        show { HoldingsTab(fund(), loading = false, onOpenSymbol = {}) }
        rule.onNodeWithText("3 positions listed", substring = true).assertExists()
        rule.onNodeWithText("16.78% of the fund", substring = true).assertExists()
    }

    @Test
    fun `sector split and asset mix are both shown`() {
        show { HoldingsTab(fund(), loading = false, onOpenSymbol = {}) }
        rule.onNodeWithText("SECTOR SPLIT").assertExists()
        rule.onNodeWithText("Technology").assertExists()
        rule.onNodeWithText("WHAT IT IS INVESTED IN").assertExists()
        rule.onNodeWithText("Stocks").assertExists()
    }

    @Test
    fun `tapping a holding opens that symbol`() {
        var opened: String? = null
        show { HoldingsTab(fund(), loading = false, onOpenSymbol = { opened = it }) }
        rule.onNodeWithText("NVDA").performClick()
        rule.waitForIdle()
        assertEquals("NVDA", opened)
    }

    /** An ordinary share must be told apart from a fund the provider had no data for. */
    @Test
    fun `an ordinary share is told it is not a fund, not that data is missing`() {
        show {
            HoldingsTab(
                FundHoldings("AAPL", isFund = false, quoteType = "EQUITY", fetched = 1L),
                loading = false, onOpenSymbol = {}
            )
        }
        rule.onNodeWithText("not a fund", substring = true).assertExists()
    }

    @Test
    fun `holdings survive a long company name at a large font scale`() {
        show(fontScale = 1.3f) { HoldingsTab(fund(), loading = false, onOpenSymbol = {}) }
        assertNothingOverflows("holdings at 1.3x")
    }

    @Test
    fun `holdings rows are big enough to tap`() {
        show { HoldingsTab(fund(), loading = false, onOpenSymbol = {}) }
        assertTouchTargets("holding rows", 44)
    }
}
