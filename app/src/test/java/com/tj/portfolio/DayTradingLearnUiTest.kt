package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.DayTradingStats
import com.tj.portfolio.data.StatSlice
import com.tj.portfolio.net.DayTradingParams
import com.tj.portfolio.net.EngineTuning
import com.tj.portfolio.ui.DayTradingSuccessRate
import com.tj.portfolio.ui.EngineReviewDialog
import com.tj.portfolio.ui.EngineTuningCard
import com.tj.portfolio.ui.PortfolioTheme
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
 * The 2026-09-24c screens, rendered: the success card says how much its numbers can bear and
 * what it left out, the tuning card says what the sample allows and offers the way back, and the
 * review sheet says, per change, what would really happen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayTradingLearnUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme { Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
                } }
            }
        }
    }

    private fun texts(): List<String> =
        rule.onAllNodes(SemanticsMatcher("has text") {
            it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    private val stats = DayTradingStats(
        totalRecommendations = 30, entriesTriggered = 12, targetHit = 4, stopHit = 6, closedProfit = 1, closedLoss = 1,
        noEntry = 14, pending = 2, dataUnavailable = 2, targetHitRate = 33.3, profitableRate = 41.7, profitableCount = 5,
        avgR = 0.12, avgRLow = -0.4, avgRHigh = 0.64, profitableLow = 19.3, profitableHigh = 68.0,
        avgWinR = 1.4, avgLossR = -0.95, profitFactor = 1.2, maxDrawdownR = 3.1, accountReturnPct = 1.8,
        accountReturnAllPct = 2.2, unfundedTrades = 2, legacyExcluded = 3, graded1m = 10, graded5m = 2, sessions = 4,
        breakdown = listOf(StatSlice("Setup", "Breakout", 8, 3, 4, 1.6))
    )

    @Test fun `the success card leads with how much the sample can bear and names what it left out`() {
        show { DayTradingSuccessRate(stats, loading = false, onCheck = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("12 graded trades - Too few trades to judge"))
        assertTrue(t, t.contains("Likely true rate: 19.3% to 68.0%"))
        assertTrue(t, t.contains("+0.12R"))
        assertTrue(t, t.contains("not yet distinguishable from zero"))
        assertTrue(t, t.contains("2 more could not have been bought"))
        assertTrue(t, t.contains("3 older results were graded under the previous"))
        assertTrue(t, t.contains("2 graded on 5-minute bars"))
        assertTrue(t, t.contains("+0.20R avg"))   // the slice: 1.6R over 8 trades
        rule.onNodeWithText("How are trades graded?").performClick()
        assertTrue(texts().any { it.contains("buy-stop at the buy price") })
    }

    @Test fun `the success card still lays out at a large font scale`() {
        show(fontScale = 1.6f) { DayTradingSuccessRate(stats, loading = false, onCheck = {}) }
        assertTrue(texts().any { it.contains("Your portfolio, trading this system") })
    }

    @Test fun `the tuning card says what the sample allows and only offers what can be done`() {
        show { EngineTuningCard(EngineTuning.State(), graded = 12, sinceLastChange = 0,
            onMakePrompt = {}, onImport = {}, onUndo = {}, onRevert = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("Running the original engine"))
        assertTrue(t, t.contains("not changed until there are 30"))
        rule.onNodeWithText("Make tuning prompt").assertIsEnabled()
        rule.onNodeWithText("Undo last change").assertIsNotEnabled()
        rule.onNodeWithText("Revert to original").assertIsNotEnabled()
    }

    @Test fun `a tuned engine shows its history and asks before reverting`() {
        val tuned = DayTradingParams.DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.8))
        val st = EngineTuning.State(tuned, 1, listOf(EngineTuning.HistoryEntry(1, 1_789_479_000_000L,
            EngineTuning.KIND_APPLY, listOf(EngineTuning.Change(DayTradingParams.MIN_RISK, 1.5, 1.8)),
            summary = "Stops were too tight.", gradedTrades = 80, paramsAfter = tuned)))
        var reverted = false
        show { EngineTuningCard(st, graded = 95, sinceLastChange = 15, onMakePrompt = {}, onImport = {},
            onUndo = {}, onRevert = { reverted = true }) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("Running tuned engine v1 - 1 setting differs from the original"))
        assertTrue(t, t.contains("15 since the last change - the next change waits for 20"))
        rule.onNodeWithText("Engine history (1)").performClick()
        assertTrue(texts().any { it.contains("stop.minRiskAtrs: 1.5 -> 1.8") })
        rule.onNodeWithText("Revert to original").performClick()
        assertFalse("never without asking", reverted)
        rule.onNodeWithText("Revert").performClick()
        assertTrue(reverted)
    }

    @Test fun `the review sheet shows every change's fate and applies only on the tap`() {
        val p = EngineTuning.Proposal(basedOnVersion = 0, verdict = "Marginal edge.", analysis = "Pullbacks lose.",
            changes = listOf(
                EngineTuning.ProposedChange(DayTradingParams.MIN_RISK, 1.5, 3.0, "all", 40, "fewer stop-outs", "MAE data"),
                EngineTuning.ProposedChange("setup.pullback.enabled", 1.0, 0.0, "setup:Pullback", 12, "", "they lose")
            ), codeIdeas = listOf("Trail under VWAP"))
        val rows = (0 until 40).map { i ->
            com.tj.portfolio.data.DayTradingLogEntry(i.toLong(), "S$i", "20260915", 1_789_479_000_000L + i * 60_000L, "Breakout",
                10.5, 10.0, 11.5, 10.3, "APP", "WIN", 11.5, 1L, "v0", "{}", com.tj.portfolio.net.DayTradingGrader.VERSION, "")
        }
        val review = EngineTuning.review(p, EngineTuning.State(), rows)
        var applied = 0
        show { EngineReviewDialog(review, onApply = { applied++ }, onDismiss = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("Marginal edge."))
        assertTrue(t, t.contains("Limited to 1.85"))
        assertTrue(t, t.contains("Refused:"))
        assertTrue(t, t.contains("Trail under VWAP"))
        assertEquals(0, applied)
        rule.onNodeWithText("Apply 1 change").performClick()
        assertEquals(1, applied)
    }
}
