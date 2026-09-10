package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.EtfFacts
import com.tj.portfolio.ui.EtfFactsGrid
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
 * THE FUND FACTS GRID, RENDERED (Round 63).
 *
 * Two things this has to get right and only a rendered test can check:
 *
 *  1. **An absent figure must not print as a zero.** "0.00%" in the cost cell reads as a free
 *     fund, which is a claim; an em dash reads as "the fund did not publish this", which is
 *     the truth. The app has fixed this exact class of bug before - see the note on
 *     "never fabricate a previous close".
 *  2. **The layout must survive a large font scale**, because six cells across a phone is the
 *     narrowest thing on the screen and TJ reads the app at his own accessibility settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtfCardUiTest {

    @get:Rule val rule = createComposeRule()

    private val full = EtfFacts(
        expenseRatio = 0.03, netAssets = 1.741139870E12, yieldPct = 1.04,
        ytdReturnPct = 13.11, oneYearPct = 18.41, threeYearAnnualPct = 26.25,
        fiveYearAnnualPct = 28.66, dollarVolume = 9.4E9,
        inceptionMs = 1_283_000_000_000L
    )

    private fun show(fontScale: Float = 1f, dark: Boolean = false, f: EtfFacts) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = dark) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column { EtfFactsGrid(f) }
                    }
                }
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

    @Test fun `every cell is labelled and filled`() {
        show(f = full)
        val t = texts()
        // "1Y price", not "1Y" (Round 66 audit, E5): the cell beside two NAV TOTAL returns is
        // a 52-week PRICE change, and three identically-labelled cells implied three
        // comparable numbers. For an income fund the difference is the whole yield.
        listOf("5Y / yr", "3Y / yr", "1Y price", "Expense", "Assets", "Yield").forEach {
            assertTrue("the \"$it\" cell is missing: $t", t.contains(it))
        }
        assertTrue(t.contains("+28.66%"))
        assertTrue(t.contains("+26.25%"))
        assertTrue(t.contains("+18.41%"))
        assertTrue(t.contains("0.03%"))
        assertTrue(t.contains("1.04%"))
        assertTrue("assets should be compact money: $t", t.any { it == "$1.74T" })
    }

    /**
     * `expenseRatio = -1.0`, not 0.0 (Round 66 audit, ETF-6). Zero is a REAL FEE - BKLC and
     * BKAG charge nothing - so it stopped being this field's "not published" sentinel, and
     * -1.0 took over. The test says what it always meant to say: a fund that published no fee
     * shows a dash.
     */
    @Test fun `a figure the fund never published prints an em dash, never a zero`() {
        show(f = EtfFacts(expenseRatio = -1.0, netAssets = 0.0, yieldPct = 0.0,
            oneYearPct = 12.0, threeYearAnnualPct = 0.0, fiveYearAnnualPct = 0.0))
        val t = texts()
        assertEquals(
            "an absent figure must be an em dash, and there are five of them here",
            5, t.count { it == "—" }
        )
        assertFalse("a fabricated zero reached the card: $t", t.any { it == "+0.00%" })
        assertFalse(t.any { it == "0.00%" })
        assertTrue("the one real figure is still shown", t.contains("+12.00%"))
    }

    /** And the other half of it: a fund that really charges nothing says so. */
    @Test fun `a genuinely free fund shows its zero fee, not a dash`() {
        show(f = full.copy(expenseRatio = 0.0))
        val t = texts()
        assertTrue("a 0.00% fee must be printed as a fee: $t", t.contains("0.00%"))
    }

    @Test fun `a negative return is shown with its sign`() {
        show(f = full.copy(oneYearPct = -14.5, fiveYearAnnualPct = -3.25))
        val t = texts()
        assertTrue(t.contains("-14.50%"))
        assertTrue(t.contains("-3.25%"))
    }

    @Test fun `the grid still lays out at a large font scale`() {
        // 1.6x is a real Android accessibility setting, and six cells across a phone is the
        // narrowest thing on this card.
        show(fontScale = 1.6f, f = full)
        rule.onNodeWithText("Expense").assertIsDisplayed()
        rule.onNodeWithText("Assets").assertIsDisplayed()
        rule.onNodeWithText("Yield").assertIsDisplayed()
        rule.onNodeWithText("5Y / yr").assertIsDisplayed()
    }

    @Test fun `it renders in dark mode too`() {
        show(dark = true, f = full)
        rule.onNodeWithText("+28.66%").assertIsDisplayed()
    }
}
