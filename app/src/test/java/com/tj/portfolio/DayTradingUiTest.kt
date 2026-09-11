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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.ui.DayTradingDetailDialog
import com.tj.portfolio.ui.PortfolioTheme
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
}
