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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performScrollTo
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
        accountReturnAllPct = 2.2, unfundedTrades = 2, fundedTrades = 10, cappedTrades = 6, legacyExcluded = 3, regrading = 4, graded1m = 10, graded5m = 2, sessions = 4,
        breakdown = listOf(StatSlice("Setup", "Breakout", 8, 3, 4, 1.6))
    )

    @Test fun `the success card leads with how much the sample can bear and names what it left out`() {
        show { DayTradingSuccessRate(stats, loading = false, onCheck = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("12 graded trades - Too few trades to judge"))
        assertTrue(t, t.contains("Likely true rate: 19.3% to 68.0%"))
        assertTrue(t, t.contains("+0.12R"))
        // UI-1: under 20 trades, a range and a caution - never a verdict
        assertTrue(t, t.contains("with under 20 trades this range is not reliable yet"))
        assertFalse(t, t.contains("real edge"))
        assertTrue(t, t.contains("2 more could not have been bought"))
        assertTrue(t, t.contains("+0.20R avg"))   // the slice: 1.6R over 8 trades
        assertTrue("a small slice says so (UI-16)", t.contains("too few to judge"))
        assertTrue(t, t.contains("Biggest drop from a high"))
        // the long notes sit behind Details (UI-21)
        rule.onNodeWithText("Details: sizing, percent figures, costs, counts").performScrollTo().performClick()
        val d = texts().joinToString(" | ")
        assertTrue(d, d.contains("3 older results graded under the previous, less strict rules are no longer re-checkable"))
        assertTrue(d, d.contains("2 graded on 5-minute bars"))
        assertTrue(d, d.contains("4 being re-checked under the current, stricter rules"))
        assertTrue(d, d.contains("the cap sized"))
        rule.onNodeWithText("How are trades graded?").performScrollTo().performClick()
        assertTrue(texts().any { it.contains("buy-stop at the buy price") })
    }

    @Test fun `a first open after the update says results are being re-checked, not that none exist`() {
        show { DayTradingSuccessRate(DayTradingStats(totalRecommendations = 40, regrading = 40), loading = false, onCheck = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("40 earlier results are being re-checked"))
        assertFalse(t, t.contains("has a decided outcome"))
    }

    @Test fun `plans that all expired unfilled are no trade, not missing data`() {
        show { DayTradingSuccessRate(DayTradingStats(totalRecommendations = 3, noEntry = 3), loading = false, onCheck = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("None of the 3 recorded recommendations filled before its cut-off"))
        assertTrue(t, t.contains("3 never filled before their cut-off"))
    }

    @Test fun `a verdict needs twenty trades`() {
        val small = stats.copy(entriesTriggered = 10, avgRLow = 0.06, avgRHigh = 2.1, avgR = 1.1)
        assertEquals("", small.edgeVerdict)
        val big = small.copy(entriesTriggered = 60)
        assertEquals("positive", big.edgeVerdict)
    }

    @Test fun `the success card still lays out at a large font scale`() {
        show(fontScale = 1.6f) { DayTradingSuccessRate(stats, loading = false, onCheck = {}) }
        assertTrue(texts().any { it.contains("Your portfolio, trading this system") })
    }

    @Test fun `the tuning card says what the sample allows and only offers what can be done`() {
        show { EngineTuningCard(EngineTuning.State(), evidence = 12 to 0,
            onMakePrompt = {}, onImport = {}, onUndo = {}, onRevert = {}) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("Running the original engine"))
        assertTrue(t, t.contains("12 graded trades from the app's own plans"))
        assertTrue(t, t.contains("not changed until there are 30"))
        assertTrue(t, t.contains("Nothing to undo - this is the original engine."))
        rule.onNodeWithText("Make tuning prompt").assertIsEnabled()
        rule.onNodeWithText("Undo last change").assertIsNotEnabled()
        rule.onNodeWithText("Revert to original").assertIsNotEnabled()
    }

    @Test fun `while grading only the prompt waits, and says why`() {
        val tuned = DayTradingParams.DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.8))
        show { EngineTuningCard(EngineTuning.State(tuned, 1), evidence = null,
            onMakePrompt = {}, onImport = {}, onUndo = {}, onRevert = {}, grading = true) }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("Counting graded trades..."))
        assertTrue(t, t.contains("Grading new results"))
        rule.onNodeWithText("Make tuning prompt").assertIsNotEnabled()
        rule.onNodeWithText("Import answer").assertIsEnabled()
        rule.onNodeWithText("Revert to original").assertIsEnabled()
    }

    @Test fun `the plan label names the engine that made the plan, and NOT YET reads as a wait`() {
        show { Column {
            com.tj.portfolio.ui.TradeLevelsGrid(com.tj.portfolio.data.ResearchRow(symbol = "A", price = 10.0,
                entryPrice = 10.2, stopPrice = 9.8, targetPrice = 10.9, setup = "Breakout", planEngine = "v3"))
            com.tj.portfolio.ui.TradeLevelsGrid(com.tj.portfolio.data.ResearchRow(symbol = "B", price = 10.0,
                entryPrice = 10.2, stopPrice = 9.8, targetPrice = 10.9, setup = "Breakout",
                planWait = "Too early - the tuned engine starts no new trade in the first 15 minutes after the open."))
        } }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("RISK PLAN (tuned engine v3) - computed, not a forecast"))
        assertTrue(t, t.contains("RISK PLAN - computed, not a forecast"))
        assertTrue(t, t.contains("NOT YET - Too early"))
    }

    @Test fun `a tuned engine shows its history and asks before reverting`() {
        val tuned = DayTradingParams.DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.8))
        val st = EngineTuning.State(tuned, 1, listOf(EngineTuning.HistoryEntry(1, 1_789_479_000_000L,
            EngineTuning.KIND_APPLY, listOf(EngineTuning.Change(DayTradingParams.MIN_RISK, 1.5, 1.8)),
            summary = "Stops were too tight.", gradedTrades = 80, paramsAfter = tuned)))
        var reverted = false
        show {
            var ask by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            EngineTuningCard(st, evidence = 95 to 15, onMakePrompt = {}, onImport = {},
                onUndo = { ask = com.tj.portfolio.ui.ENGINE_UNDO }, onRevert = { ask = com.tj.portfolio.ui.ENGINE_REVERT })
            if (ask.isNotEmpty()) com.tj.portfolio.ui.EngineConfirmDialog(ask, st,
                onConfirm = { if (ask == com.tj.portfolio.ui.ENGINE_REVERT) reverted = true; ask = "" }, onDismiss = { ask = "" })
        }
        val t = texts().joinToString(" | ")
        assertTrue(t, t.contains("Running tuned engine v1 - 1 setting differs from the original"))
        assertTrue(t, t.contains("15 since the change now in force - the next change waits for 20"))
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
        assertTrue(t, t.contains("Will apply, limited to 1.85"))
        assertTrue(t, t.contains("Refused:"))
        // UI-4: the app's own count beside Claude's claim; UI-14: a refused change does not read as one
        assertTrue(t, t.contains("Evidence (setup:Pullback): 0 graded trades by the app's count - Claude cited 12"))
        assertTrue(t, t.contains("setup.pullback.enabled: stays on (Claude proposed off)"))
        assertTrue(t, t.contains("Trail under VWAP"))
        assertEquals(0, applied)
        rule.onNodeWithText("Apply 1 change").performClick()
        assertEquals(1, applied)
    }

    @Test fun `R2P-1 after a revert, Undo says it brings the tuned engine back`() {
        val tuned = DayTradingParams.DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.8))
        val applied = EngineTuning.State(tuned, 1, listOf(EngineTuning.HistoryEntry(1, 1000L,
            EngineTuning.KIND_APPLY, listOf(EngineTuning.Change(DayTradingParams.MIN_RISK, 1.5, 1.8)), paramsAfter = tuned)))
        assertEquals("Undo last change", com.tj.portfolio.ui.undoLabel(applied))
        assertEquals("Undo the last change?", com.tj.portfolio.ui.engineConfirmText(com.tj.portfolio.ui.ENGINE_UNDO, applied).first)
        val reverted = EngineTuning.revert(applied, 2000L, 40)!!
        assertEquals("Undo revert", com.tj.portfolio.ui.undoLabel(reverted))
        val (title, body) = com.tj.portfolio.ui.engineConfirmText(com.tj.portfolio.ui.ENGINE_UNDO, reverted)
        assertEquals("Undo the revert?", title)
        assertTrue(body, body.contains("comes back in force") && body.contains("1 setting different"))
        // and in the history, the undone revert is marked
        val back = EngineTuning.undo(reverted, 3000L, 40)!!
        show { EngineTuningCard(back, evidence = 40 to 0, onMakePrompt = {}, onImport = {}, onUndo = {}, onRevert = {}) }
        rule.onNodeWithText("Engine history (3)").performClick()
        assertTrue(texts().joinToString(" | "), texts().any { it.contains("reverted to the original (later taken back)") })
    }
}
