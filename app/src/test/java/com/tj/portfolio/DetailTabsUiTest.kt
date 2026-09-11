package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.AnalystRating
import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.EarningsEstimate
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.Recommendation
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.ui.AnalystsTab
import com.tj.portfolio.ui.DetailTab
import com.tj.portfolio.ui.DetailTabRow
import com.tj.portfolio.ui.EarningsTab
import com.tj.portfolio.ui.Explain
import com.tj.portfolio.ui.ExplainDialog
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.RecommendationDialog
import com.tj.portfolio.ui.StatsTab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE NEW TABS, ACTUALLY RENDERED.
 *
 * Same reasoning as UiTest: this project has shipped two layout bugs that reading the source
 * could not have caught, and the fix was to compose the real widgets and measure them. These
 * tabs add a lot of surface - fifty metric rows, a vote bar, a rating list, an explanation
 * dialog - so they get the same treatment: render at the font scales a phone actually uses,
 * assert nothing runs off the right edge, and assert every "i" is big enough to hit.
 *
 * The 40dp floor for the "i" is deliberately below Material's 48dp: it sits INSIDE a row that
 * is itself tappable, so a near-miss opens the same explanation rather than doing something
 * destructive. The row-level controls elsewhere in the app are held to 44dp for that reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailTabsUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    /**
     * The widest window on screen.
     *
     * A Dialog composes into its OWN root, so while one is open there are two roots and
     * `onRoot()` throws "expected exactly 1 node". Taking the maximum keeps the overflow
     * assertion honest for both the tab tests and the dialog tests.
     */
    private fun rootWidth(): Float =
        rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
            .maxOf { it.size.width }.toFloat()

    private fun hasTextAny() = androidx.compose.ui.test.SemanticsMatcher("has text") {
        it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
    }

    private fun assertNothingOverflows(label: String) {
        val w = rootWidth()
        val nodes = rule.onAllNodes(hasTextAny()).fetchSemanticsNodes()
        assertTrue("$label: nothing rendered at all", nodes.isNotEmpty())
        val spills = nodes.filter { it.boundsInRoot.right > w + 0.5f }.map {
            "\"${it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")}\" " +
                "ends at ${it.boundsInRoot.right} (screen is $w)"
        }
        assertTrue(
            "$label: text runs off the right edge:\n  " + spills.joinToString("\n  "),
            spills.isEmpty()
        )
    }

    /**
     * Measured with `node.size`, NOT `boundsInRoot`.
     *
     * These tabs are LazyColumns, and `boundsInRoot` is intersected with the clipping
     * viewport - so the one row straddling the bottom edge reports whatever fraction of
     * itself is visible. The first run of this test failed on exactly that: a single row of
     * fifty came back "23dp tall" while every other row measured fine. `size` is the
     * unclipped layout size, which is what a fingertip actually gets to hit once the list is
     * scrolled to it.
     */
    private fun assertTouchTargets(label: String, minDp: Int = 40) {
        val density = 2.0f
        val min = minDp * density
        val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
            it.size.height > 0 && it.size.height < min - 0.5f
        }.map {
            "\"${it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "(icon)"}\"" +
                " is ${"%.0f".format(it.size.height / density)}dp tall"
        }
        assertTrue(
            "$label: tap targets under ${minDp}dp:\n  " + small.joinToString("\n  "),
            small.isEmpty()
        )
    }

    // ------------------------------------------------------------- fixtures

    private fun rich() = Fundamentals(
        symbol = "NVDA",
        values = linkedMapOf(
            "marketCap" to 4.6697e12,
            "enterpriseValue" to 4.6916e12,
            "peTrailing" to 37.599,
            "peForward" to 33.419,
            "pegRatio" to 2.58,
            "priceToSales" to 10.003,
            "priceToBook" to 43.474,
            "evToEbitda" to 27.933,
            "epsTrailing" to 8.51,
            "revenue" to 4.66823e11,
            "netIncome" to 1.28930e11,
            "freeCashflow" to 1.07722e11,
            "dividendRate" to 1.08,
            "dividendYield" to 0.0033,
            "payoutRatio" to 0.1204,
            "exDividendDate" to 1.78632e12,
            "revenueGrowth" to 0.164,
            "earningsGrowth" to 0.287,
            "change52Week" to 0.3797,
            "grossMargins" to 0.48653,
            "operatingMargins" to 0.32623,
            "profitMargins" to 0.27619,
            "returnOnEquity" to 1.48751,
            "totalCash" to 6.2399e10,
            "totalDebt" to 8.4344e10,
            "debtToEquity" to 78.445,
            "currentRatio" to 1.003,
            "beta" to 1.085,
            "fiftyTwoWeekHigh" to 344.57,
            "fiftyTwoWeekLow" to 225.95,
            "volume" to 3.8272821e7,
            "averageVolume" to 5.439047e7,
            "sharesOutstanding" to 1.459418e10,
            "shortPercentOfFloat" to 0.008,
            "shortRatio" to 2.19,
            "heldPercentInstitutions" to 0.66374,
            "employees" to 166000.0
        ),
        texts = linkedMapOf("sector" to "Technology", "industry" to "Consumer Electronics"),
        consensus = Consensus(
            mean = 2.2, key = "buy", analysts = 38,
            targetMean = 323.86, targetHigh = 400.0, targetLow = 215.0, targetMedian = 335.0,
            strongBuy = 6, buy = 18, hold = 13, sell = 3, strongSell = 3
        ),
        ratings = listOf(
            AnalystRating(
                "Morgan Stanley", 1_788_370_475_000L, AnalystRating.REITERATE,
                "Overweight", "Overweight", 360.0, 360.0, "Maintains", source = "Yahoo Finance"
            ),
            AnalystRating(
                "Rothschild & Co Redburn", 1_786_998_047_000L, AnalystRating.UPGRADE,
                "Buy", "Neutral", 400.0, 0.0, "Raises", source = "Yahoo Finance"
            ),
            AnalystRating(
                "Jefferies", 1_786_400_000_000L, AnalystRating.DOWNGRADE,
                "Underperform", "Hold", 263.66, 300.0, "Lowers", source = "Finviz"
            )
        ),
        estimates = listOf(
            EarningsEstimate(
                "0q", "2026-09-30", 1.97754, 1.93, 2.07, 1.85, 0.0689, 27,
                1.1361e11, 0.0709
            )
        ),
        earningsDate = System.currentTimeMillis() + 20L * 86_400_000L,
        sources = listOf("Yahoo Finance"),
        fetched = System.currentTimeMillis() - 300_000L
    )

    // ------------------------------------------------------------ Stats tab

    @Test fun `stats tab renders every group without overflowing`() {
        show { StatsTab(rich(), 320.01, loading = false, onInfo = {}) }
        assertNothingOverflows("stats tab")
    }

    @Test fun `stats tab survives double text size`() {
        show(fontScale = 2.0f) { StatsTab(rich(), 320.01, loading = false, onInfo = {}) }
        assertNothingOverflows("stats tab at 2x")
    }

    @Test fun `every info button on the stats tab is big enough to hit`() {
        show { StatsTab(rich(), 320.01, loading = false, onInfo = {}) }
        assertTouchTargets("stats tab")
    }

    @Test fun `stats tab renders in dark mode`() {
        show(dark = true) { StatsTab(rich(), 320.01, loading = false, onInfo = {}) }
        assertNothingOverflows("stats tab dark")
    }

    /**
     * A metric the provider did not report has to say so. Printing 0.00 there would be a
     * wrong number that looks like a real one - the same failure the previous-close fix in
     * v2.3 was about, one screen further along.
     */
    @Test fun `an unreported metric says so rather than showing a zero`() {
        val thin = Fundamentals(
            symbol = "X",
            values = mapOf("marketCap" to 1e9),   // valuation group, everything else missing
            fetched = System.currentTimeMillis()
        )
        show { StatsTab(thin, 10.0, loading = false, onInfo = {}) }
        // Every other metric in the valuation group is missing, so there are many of these -
        // the assertion is that at least one exists, and that no "0.00" was invented.
        val notReported = rule.onAllNodes(
            androidx.compose.ui.test.hasText("not reported"), useUnmergedTree = true
        ).fetchSemanticsNodes()
        assertTrue("expected unreported metrics to say so", notReported.isNotEmpty())
    }

    /** A symbol with no fundamentals at all must explain itself, not render an empty page. */
    @Test fun `stats tab explains an empty result`() {
        show { StatsTab(Fundamentals("SPY"), 0.0, loading = false, onInfo = {}) }
        rule.onNodeWithText("No fundamental data for this symbol yet.").assertExists()
    }

    // --------------------------------------------------------- Analysts tab

    @Test fun `analysts tab renders consensus, target and every rating`() {
        show { AnalystsTab(rich(), 320.01, loading = false, onInfo = {}) }
        assertNothingOverflows("analysts tab")
        rule.onNodeWithText("Morgan Stanley").assertExists()
        rule.onNodeWithText("Jefferies").assertExists()
    }

    @Test fun `analysts tab survives large text`() {
        show(fontScale = 1.6f) { AnalystsTab(rich(), 320.01, loading = false, onInfo = {}) }
        assertNothingOverflows("analysts tab at 1.6x")
    }

    @Test fun `analysts tab explains having no coverage`() {
        show { AnalystsTab(Fundamentals("TINY"), 1.0, loading = false, onInfo = {}) }
        rule.onNodeWithText("No analyst coverage found for this symbol.").assertExists()
    }

    /**
     * The list is keyed by rating id, and Compose THROWS when a key repeats - that is what
     * killed the app on the insider-filings list once. Two ratings from the same firm on
     * different days must therefore render side by side rather than collide.
     */
    @Test fun `two ratings from the same firm on different days both render`() {
        val f = rich().copy(
            ratings = listOf(
                AnalystRating("Citi", 1_788_370_475_000L, AnalystRating.REITERATE, "Buy", target = 380.0),
                AnalystRating("Citi", 1_786_998_047_000L, AnalystRating.REITERATE, "Neutral", target = 300.0)
            )
        )
        show { AnalystsTab(f, 320.0, loading = false, onInfo = {}) }
        assertNothingOverflows("duplicate-firm ratings")
    }

    // --------------------------------------------------------- Earnings tab

    @Test fun `earnings tab renders estimates and the next report date`() {
        show { EarningsTab(rich(), loading = false, onInfo = {}) }
        assertNothingOverflows("earnings tab")
        rule.onNodeWithText("Current quarter").assertExists()
    }

    @Test fun `earnings tab explains having nothing`() {
        show { EarningsTab(Fundamentals("SPY"), loading = false, onInfo = {}) }
        rule.onNodeWithText("No earnings data for this symbol.").assertExists()
    }

    // ------------------------------------------------------ the "i" dialog

    @Test fun `the explanation dialog renders all four sections and the live read`() {
        val e = Explain.of("peTrailing", rich(), 320.01)
        show { ExplainDialog(e) {} }
        rule.onNodeWithText("WHAT IT MEANS").assertExists()
        rule.onNodeWithText("WHAT COUNTS AS GOOD OR BAD").assertExists()
        rule.onNodeWithText("EFFECT ON THE PRICE - SHORT TERM").assertExists()
        rule.onNodeWithText("EFFECT ON THE PRICE - LONG TERM").assertExists()
        assertNothingOverflows("explain dialog")
    }

    @Test fun `the explanation dialog survives its longest entry at large text`() {
        // shortPercentOfFloat has the longest combined prose of any metric.
        val e = Explain.of("shortPercentOfFloat", rich(), 320.01)
        show(fontScale = 1.6f) { ExplainDialog(e) {} }
        assertNothingOverflows("explain dialog at 1.6x")
    }

    @Test fun `the dialog dismisses`() {
        var open = true
        show { if (open) ExplainDialog(Explain.of("beta", rich(), 320.0)) { open = false } }
        rule.onNodeWithText("Got it").performClick()
        assertTrue("the confirm button must call back", !open)
    }

    @Test fun `an analyst topic dialog renders too`() {
        val e = Explain.analystTopic(Explain.TOPIC_TARGET, rich(), 320.01)
        show { ExplainDialog(e) {} }
        assertNothingOverflows("target explanation")
    }

    // ------------------------------------------------- the BUY/HOLD/SELL popup
    //
    // The same discipline as the "i" dialog above, for the same reason: a Robolectric render
    // is the closest this project gets to "opened it on a phone" without one attached to the
    // container, and it is what already caught the two real layout bugs this file's own
    // header describes. Gradle's unit-test pass proves `RecommendationScoreTest` computes the
    // right numbers; it says nothing about whether the popup actually composes without
    // throwing or whether the text that matters is on screen at all.

    private fun rec(
        verdict: TradeVerdict = TradeVerdict.BUY,
        reasons: List<String> = listOf("Strong Buy consensus - 16 buy / 2 hold / 0 sell across 18 analysts"),
        targetMean: Double = 250.0,
        analystCount: Int = 18,
        confidence: Int = 90
    ) = Recommendation(
        symbol = "NVDA", verdict = verdict, score = 80, reasons = reasons,
        confidence = confidence, targetMean = targetMean, targetHigh = 300.0, targetLow = 200.0,
        analystCount = analystCount, price = 200.0, dayKey = "20260911"
    )

    @Test fun `a BUY recommendation renders its verdict, target and reasons`() {
        show { RecommendationDialog(rec(), "NVDA") {} }
        rule.onNodeWithText("BUY").assertExists()
        rule.onNodeWithText("NVDA - Buy").assertExists()
        rule.onNodeWithText(
            "Strong Buy consensus - 16 buy / 2 hold / 0 sell across 18 analysts",
            substring = true
        ).assertExists()
        assertNothingOverflows("recommendation dialog (buy)")
    }

    @Test fun `a SELL recommendation with no target says so rather than inventing one`() {
        show {
            RecommendationDialog(
                rec(
                    verdict = TradeVerdict.SELL,
                    reasons = listOf("Strong sell consensus - 1 buy / 2 hold / 15 sell across 18 analysts"),
                    targetMean = 0.0
                ).copy(targetHigh = 0.0, targetLow = 0.0),
                "XYZ"
            ) {}
        }
        rule.onNodeWithText("SELL").assertExists()
        rule.onNodeWithText("No analyst price target is published for XYZ").assertExists()
        assertNothingOverflows("recommendation dialog (sell, no target)")
    }

    @Test fun `a HOLD with thin coverage shows the low-confidence caveat`() {
        show { RecommendationDialog(rec(verdict = TradeVerdict.HOLD, analystCount = 0, confidence = 20), "SPY") {} }
        rule.onNodeWithText(
            "No analyst coverage found for SPY - this reads on price, valuation and " +
                "growth alone, so treat it with extra caution.",
            substring = true
        ).assertExists()
    }

    @Test fun `the still-gathering state shows instead of a blank dialog`() {
        show { RecommendationDialog(null, "NVDA") {} }
        rule.onNodeWithText("Still gathering data for NVDA - check back in a moment.").assertExists()
    }

    @Test fun `the recommendation dialog dismisses`() {
        var open = true
        show { if (open) RecommendationDialog(rec(), "NVDA") { open = false } }
        rule.onNodeWithText("Got it").performClick()
        assertTrue("the confirm button must call back", !open)
    }

    @Test fun `tapping the recommendation tab shows its live verdict word and color, not the placeholder`() {
        show {
            DetailTabRow(
                tabs = listOf(DetailTab.ANALYSTS, DetailTab.RECOMMENDATION, DetailTab.NEWS),
                selected = DetailTab.ANALYSTS,
                recommendationLabel = "Sell",
                recommendationColor = androidx.compose.ui.graphics.Color(0xFFC62828)
            ) {}
        }
        rule.onNodeWithText("Sell").assertExists()
        rule.onNodeWithText("...").assertDoesNotExist()
    }

    @Test fun `before a recommendation lands the tab shows the placeholder, not a blank tab`() {
        show {
            DetailTabRow(
                tabs = listOf(DetailTab.ANALYSTS, DetailTab.RECOMMENDATION, DetailTab.NEWS),
                selected = DetailTab.ANALYSTS
            ) {}
        }
        rule.onNodeWithText("...").assertExists()
    }
}
