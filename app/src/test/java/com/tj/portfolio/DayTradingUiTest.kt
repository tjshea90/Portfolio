package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.ui.BeginnerSummaryCard
import com.tj.portfolio.ui.DayTradingDetailDialog
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.ResearchCard
import com.tj.portfolio.ui.TradeLevelsGrid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE DAY TRADING RISK PLAN AND ITS EXPLANATION, ACTUALLY RENDERED (Round 68).
 *
 * Tj's two visible complaints about the shipped tab were both UI, not scoring - "the app
 * doesn't have buy and sell target numbers" and "click on each stock" for an explanation - so
 * this file checks the pixels, not just the model. Same reasoning [EtfCardUiTest] and
 * [DetailTabsUiTest] already document for this project: a layout can be wrong in a way reading
 * the source never catches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayTradingUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun texts(): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    // ================================================================ TradeLevelsGrid

    @Test fun `the risk plan shows entry, stop and target as real numbers`() {
        show { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TradeLevelsGrid(
                ResearchRow(
                    symbol = "GME", entryPrice = 22.50, stopPrice = 21.00, targetPrice = 25.50,
                    setup = "Breakout", trigger = "Buy the break above 22.50."
                )
            )
        } }
        val t = texts()
        listOf("Buy at", "Stop", "Target").forEach {
            assertTrue("the \"$it\" label is missing: $t", t.contains(it))
        }
        assertTrue(t.any { it.contains("22.50") })
        assertTrue(t.any { it.contains("21.00") })
        assertTrue(t.any { it.contains("25.50") })
    }

    @Test fun `a stop or target the app has not computed yet shows a dash, never a zero`() {
        show { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TradeLevelsGrid(
                ResearchRow(symbol = "GME", entryPrice = 22.50, stopPrice = 0.0, targetPrice = 0.0)
            )
        } }
        val t = texts()
        assertFalse("a fabricated zero stop/target reached the card: $t", t.any { it.contains("0.00") })
        assertTrue("an absent figure must be an em dash", t.count { it == "—" } == 2)
    }

    @Test fun `the grid still lays out at a large font scale`() {
        show(fontScale = 1.6f) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TradeLevelsGrid(
                ResearchRow(
                    symbol = "GME", entryPrice = 22.50, stopPrice = 21.00, targetPrice = 25.50,
                    setup = "Breakout",
                    trigger = "Buy the break above 22.50 (the opening-range high)."
                )
            )
        } }
        rule.onNodeWithText("Buy at").assertIsDisplayed()
        rule.onNodeWithText("Stop").assertIsDisplayed()
        rule.onNodeWithText("Target").assertIsDisplayed()
    }

    // ==================================================== BeginnerSummaryCard (Round 71)
    //
    // Tj: "add a summary of what to do and why that is simple to read for complete beginners
    // who don't understand market technical language." These check the actual rendered words a
    // beginner would read, not just that [ResearchScore.beginnerSummary] returns something.

    @Test fun `a normal plan tells a beginner what to buy and sell in plain words`() {
        show { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            BeginnerSummaryCard(
                ResearchRow(
                    symbol = "GME", price = 21.50,
                    entryPrice = 22.50, stopPrice = 21.00, targetPrice = 30.00
                )
            )
        } }
        val t = texts().joinToString(" ")
        assertTrue("must say IN PLAIN ENGLISH: $t", t.contains("IN PLAIN ENGLISH"))
        assertTrue("must state the buy trigger: $t", t.contains("22.50"))
        assertTrue("must state the sell target: $t", t.contains("30.00"))
        assertFalse("must not use jargon a beginner won't know: $t", t.contains("VWAP"))
        assertFalse(t.contains("reclaim", ignoreCase = true))
    }

    @Test fun `a plan the price already blew past reads as too late, not a buy`() {
        show { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            BeginnerSummaryCard(
                ResearchRow(
                    symbol = "GME", price = 31.00,
                    entryPrice = 22.50, stopPrice = 21.00, targetPrice = 30.00
                )
            )
        } }
        val t = texts().joinToString(" ")
        assertTrue("must say it's too late: $t", t.contains("Too late", ignoreCase = true))
        assertFalse("must not tell a beginner to buy at the top: $t", t.contains("Buy if"))
    }

    @Test fun `a price sitting exactly on the entry says so, not a guessed direction`() {
        show { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            BeginnerSummaryCard(
                ResearchRow(
                    symbol = "GME", price = 22.50,
                    entryPrice = 22.50, stopPrice = 21.00, targetPrice = 30.00
                )
            )
        } }
        val t = texts().joinToString(" ")
        assertTrue("must say it's at the price now: $t", t.contains("right now"))
        assertFalse("must not guess a direction: $t", t.contains("climbs"))
        assertFalse(t.contains("drops"))
    }

    @Test fun `a row with no plan yet draws no beginner summary at all`() {
        show { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            BeginnerSummaryCard(ResearchRow(symbol = "GME", price = 21.00))
        } }
        val t = texts().joinToString(" ")
        assertFalse("nothing to summarise yet: $t", t.contains("IN PLAIN ENGLISH"))
    }

    // ==================================== the likelihood x confidence score (Round 72)
    //
    // Tj: "make the scores reflect a blend of how likely the stock is to rise... and how
    // confident this prediction is." These check the actual rendered breakdown, and that the
    // score badge's own description reaches a screen reader too.

    private fun scoredRow(likelihood: Int = 82, confidence: Int = 60) = ResearchRow(
        symbol = "GME", name = "GameStop", price = 22.50,
        score = ResearchScore.blendedScore(likelihood, confidence),
        dtLikelihood = likelihood, dtConfidence = confidence
    )

    @Test fun `a day-trading row the app scored shows the likelihood x confidence breakdown`() {
        show { Column(Modifier.fillMaxWidth()) {
            ResearchCard(scoredRow(likelihood = 82, confidence = 60), followed = false, onOpen = {}, onOpenUrl = { _, _ -> })
        } }
        val t = texts().joinToString(" ")
        assertTrue("the breakdown must show both halves: $t", t.contains("82"))
        assertTrue(t.contains("60%"))
        // 82 x 60 / 100 = 49 - the number actually shown in the score circle.
        assertTrue("the badge itself must show the blended number: $t", t.contains("49"))
    }

    @Test fun `a row the app never scored - Trending, Best, ETF, or a Claude pick - shows no breakdown`() {
        show { Column(Modifier.fillMaxWidth()) {
            ResearchCard(
                ResearchRow(symbol = "AAPL", name = "Apple", price = 190.0, score = 75),
                followed = false, onOpen = {}, onOpenUrl = { _, _ -> }
            )
        } }
        val t = texts().joinToString(" ")
        assertFalse("no likelihood/confidence to show for a row with no dtLikelihood: $t",
            t.contains("likelihood"))
    }

    @Test fun `the score badge describes the blend to a screen reader, not just a bare number`() {
        show { Column(Modifier.fillMaxWidth()) {
            ResearchCard(scoredRow(likelihood = 82, confidence = 60), followed = false, onOpen = {}, onOpenUrl = { _, _ -> })
        } }
        rule.onNodeWithContentDescription(
            "Score 49 out of 100 - a blend of how likely this stock is to keep rising " +
                "(82 out of 100) and how confident that call is (60 percent)"
        ).assertExists()
    }

    // ============================================================ DayTradingDetailDialog

    private fun row(
        entry: Double = 22.50, stop: Double = 21.00, target: Double = 25.50,
        atr: Double = 1.0, vwap: Double = 21.80, orHigh: Double = 22.00, orLow: Double = 21.20,
        reasons: List<String> = listOf("Trading at 6.0x its normal volume today"),
        why: String = "", catalyst: String = ""
    ) = ResearchRow(
        symbol = "GME", name = "GameStop", price = 22.50, score = 88,
        reasons = reasons, why = why, catalyst = catalyst,
        entryPrice = entry, stopPrice = stop, targetPrice = target,
        atr = atr, vwap = vwap, openingRangeHigh = orHigh, openingRangeLow = orLow
    )

    @Test fun `the explanation dialog shows the symbol, the risk plan and why it's in play`() {
        show { DayTradingDetailDialog(row(), onDismiss = {}) }
        rule.onNodeWithText("GME - Day Trading").assertExists()
        val t = texts()
        assertTrue(t.any { it.contains("22.50") }) // entry
        assertTrue(t.any { it.contains("21.00") }) // stop
        assertTrue(t.any { it.contains("25.50") }) // target
        assertTrue(t.any { it.contains("Trading at 6.0x its normal volume today") })
    }

    @Test fun `the explanation dialog also shows the plain-English beginner summary`() {
        // Tj: "keep all the advice it already shows for each stock, but add a summary... simple
        // to read for complete beginners" - this dialog is the OTHER place [TradeLevelsGrid]
        // already draws (`DayTradingPlanContent`), so the beginner summary has to be there too,
        // not only on the list card.
        show { DayTradingDetailDialog(row(), onDismiss = {}) }
        val t = texts().joinToString(" ")
        assertTrue("the beginner summary must show in the tap-to-expand view: $t",
            t.contains("IN PLAIN ENGLISH"))
        // row()'s default price (22.50) equals its default entry (22.50), which is the "right
        // at the buy price now" case, not "Buy if..." - either way the entry price itself must
        // be stated in plain words.
        assertTrue("it must state the buy price in plain words: $t", t.contains("22.50"))
    }

    @Test fun `the explanation names the real technicals behind the plan`() {
        show { DayTradingDetailDialog(row(), onDismiss = {}) }
        val t = texts().joinToString(" ")
        assertTrue("ATR should be named: $t", t.contains("ATR(14)"))
        assertTrue("VWAP should be named: $t", t.contains("VWAP"))
        assertTrue("the opening range should be named: $t", t.contains("Opening range"))
        assertTrue("above VWAP should say so plainly: $t", t.contains("buyers in control"))
    }

    @Test fun `a row with no technicals names none of them rather than inventing a zero`() {
        show { DayTradingDetailDialog(row(atr = 0.0, vwap = 0.0, orHigh = 0.0, orLow = 0.0), onDismiss = {}) }
        val t = texts().joinToString(" ")
        assertFalse("must not claim an ATR it does not have", t.contains("ATR(14):"))
        assertFalse("must not claim a VWAP it does not have", t.contains("VWAP:"))
        assertFalse("must not claim an opening range it does not have", t.contains("Opening range"))
        // The plan itself still reads correctly - it is the levels section that is absent.
        assertTrue(t.contains("22.50"))
    }

    @Test fun `the entry reads as a trigger, not as the current price`() {
        // THE REGRESSION GUARD, on the surface the user actually sees. Tj read "Entry $22.50"
        // next to a $22.50 quote and correctly concluded the app was not planning a trade.
        show { DayTradingDetailDialog(row(entry = 23.10), onDismiss = {}) }
        val t = texts().joinToString(" ")
        assertTrue("the label must say what the number is for: $t", t.contains("Buy at"))
        assertTrue("the dialog must explain it is a trigger: $t", t.contains("TRIGGER"))
    }

    @Test fun `a plan with no stop does not claim the whole share price is at risk`() {
        // The grid draws an em dash for a missing stop; this line must agree with it rather
        // than treating 0.0 as a real stop and reporting "risking $22.50 a share".
        show { DayTradingDetailDialog(row(stop = 0.0, target = 0.0), onDismiss = {}) }
        val t = texts().joinToString(" ")
        assertFalse("must not invent a risk from a missing stop: $t", t.contains("Risking"))
    }

    @Test fun `the dialog explains how a scored row's score was built, and that it is not a probability`() {
        show {
            DayTradingDetailDialog(
                row().copy(dtLikelihood = 82, dtConfidence = 60, score = 49),
                onDismiss = {}
            )
        }
        val t = texts().joinToString(" ")
        assertTrue("must name the two halves: $t", t.contains("How the score is built"))
        assertTrue(t.contains("Likelihood 82"))
        assertTrue(t.contains("confidence 60%"))
        assertTrue(
            "must not overclaim a real probability: $t",
            t.contains("not a probability")
        )
    }

    @Test fun `a Claude-authored pick's dialog shows no score breakdown - it has none to show`() {
        show {
            DayTradingDetailDialog(
                row().copy(planByClaude = true, setup = "Gap and go"),
                onDismiss = {}
            )
        }
        val t = texts().joinToString(" ")
        assertFalse("no likelihood/confidence for a row the app did not score: $t",
            t.contains("How the score is built"))
    }

    @Test fun `a plan Claude set is labelled as Claude's, never as the app's arithmetic`() {
        show {
            DayTradingDetailDialog(
                row().copy(planByClaude = true, setup = "Gap and go"),
                onDismiss = {}
            )
        }
        val t = texts().joinToString(" ")
        assertTrue("whose plan this is must be on screen: $t", t.contains("CLAUDE'S PLAN"))
        assertFalse(
            "a model's prices must never be shown as the app's own computation: $t",
            t.contains("RISK PLAN - computed")
        )
    }

    @Test fun `claude's explanation and the specific risk both show when present`() {
        show {
            DayTradingDetailDialog(
                row(why = "A short squeeze is forming after a surprise earnings beat.",
                    catalyst = "Could reverse hard if the rally stalls before the close."),
                onDismiss = {}
            )
        }
        val t = texts()
        assertTrue(t.any { it.contains("short squeeze is forming") })
        assertTrue(t.any { it.contains("reverse hard") })
        assertTrue(t.contains("CLAUDE"))
    }

    @Test fun `the honest disclaimer is always shown, never a prediction`() {
        show { DayTradingDetailDialog(row(), onDismiss = {}) }
        val t = texts().joinToString(" ")
        assertTrue(t.contains("Not financial advice"))
        assertFalse("must never promise which stocks rise", t.contains("will rise"))
    }

    // ==================================================== the card's own 1-day chart (Round 70)
    //
    // Tj: "put a stock chart next to each of the stocks in the day trading section... only
    // that current day's chart, good for day trading." `ResearchCard` draws whatever
    // `ResearchScreen` hands it through `dayChart`/`dayChartLoading` - it does no fetching of
    // its own, so these render the card directly with each state that lookup can produce.

    @Test fun `a day trading card draws today's chart once one has arrived`() {
        val series = ChartSeries(
            symbol = "GME", range = ChartRange.D1,
            points = listOf(ChartPoint(1L, 21.0), ChartPoint(2L, 22.5)),
            baseline = 21.0
        )
        show { Column(Modifier.fillMaxWidth()) {
            ResearchCard(row(), followed = false, onOpen = {}, onOpenUrl = { _, _ -> }, dayChart = series)
        } }
        // The chart's own caption is proof [PriceChart] actually rendered, not just that
        // nothing crashed - same reasoning `assertNothingOverflows` elsewhere in this project
        // documents for why these tests compose the real widget.
        rule.onNodeWithText(ChartRange.D1.caption, substring = true).assertExists()
    }

    @Test fun `a day trading card still fetching its chart shows the loading line, not a hole`() {
        show { Column(Modifier.fillMaxWidth()) {
            ResearchCard(row(), followed = false, onOpen = {}, onOpenUrl = { _, _ -> }, dayChartLoading = true)
        } }
        rule.onNodeWithText("Loading ${ChartRange.D1.label} chart...").assertExists()
    }

    @Test fun `a card with no chart yet and none loading draws no chart caption at all`() {
        // The Best/Trending/ETF cards reuse this same composable and never pass a chart -
        // this is the regression guard that they stay exactly as they were.
        show { Column(Modifier.fillMaxWidth()) {
            ResearchCard(row(), followed = false, onOpen = {}, onOpenUrl = { _, _ -> })
        } }
        val t = texts().joinToString(" ")
        assertFalse("no chart was requested for this card: $t", t.contains(ChartRange.D1.caption))
        assertFalse(t.contains("Loading"))
    }
}
