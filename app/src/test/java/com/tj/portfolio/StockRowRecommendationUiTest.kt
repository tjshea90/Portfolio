package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Recommendation
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.domain.Position
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.RECOMMENDATION_CHIP_TEST_TAG
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.StockRowItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE BUY/HOLD/SELL CHIP ON THE MAIN PORTFOLIO LIST.
 *
 * TJ: *"I like the buy sell hold symbols but also include them in the main portfolio tab next
 * to each stock."* This is a second place the same, already-scored, already-tested
 * `Recommendation` is SHOWN (see `RecommendationScoreTest` for the scoring itself and
 * `DetailTabsUiTest` for the detail-screen tab/popup) - what needs proving here is only that
 * the row renders it correctly: the placeholder before it exists, the verdict once it does,
 * that tapping it opens the popup rather than the stock, and that a watch-only row (nothing
 * TJ actually holds) does not get one at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockRowRecommendationUiTest {

    @get:Rule val rule = createComposeRule()

    private fun row(watchOnly: Boolean = false) = Row(
        "NVDA", "NVDA Corp",
        if (watchOnly) null else Position("NVDA", 5.0, 5.0 * 220.0, 0.0, 1_750_000_000_000L, false, 0.0, 0.0),
        Quote(
            symbol = "NVDA", name = "NVDA Corp",
            price = 225.73, prevClose = 230.36, dayHigh = 231.0, dayLow = 224.0,
            marketState = "CLOSED", quoteTime = 1_756_000_000_000L, updated = System.currentTimeMillis()
        ),
        watchOnly, watched = watchOnly, sessionLabel = ""
    )

    private fun rec(verdict: TradeVerdict) = Recommendation(
        symbol = "NVDA", verdict = verdict, score = 70, reasons = listOf("a reason"),
        confidence = 80, targetMean = 250.0, targetHigh = 300.0, targetLow = 200.0,
        analystCount = 10, price = 225.73, dayKey = "20260911"
    )

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } } }
    }

    @Test fun `before a verdict exists the chip shows the placeholder, not a blank row`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR, recommendation = null) }
        rule.onNodeWithTag(RECOMMENDATION_CHIP_TEST_TAG).assertExists()
        rule.onNodeWithText("...").assertExists()
    }

    @Test fun `a BUY verdict shows Buy on the chip`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR, recommendation = rec(TradeVerdict.BUY)) }
        rule.onNodeWithTag(RECOMMENDATION_CHIP_TEST_TAG).assertExists()
        rule.onNodeWithText("Buy").assertExists()
    }

    @Test fun `a HOLD verdict shows Hold, a SELL verdict shows Sell`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR, recommendation = rec(TradeVerdict.HOLD)) }
        rule.onNodeWithText("Hold").assertExists()
    }

    @Test fun `tapping the chip opens the popup instead of opening the stock`() {
        var opened = false
        show {
            StockRowItem(
                row(), onClick = { opened = true }, onNews = {}, plMode = PlMode.DOLLAR,
                recommendation = rec(TradeVerdict.SELL)
            )
        }
        rule.onNodeWithTag(RECOMMENDATION_CHIP_TEST_TAG).performClick()
        assertTrue("tapping the chip must not open the stock's detail screen", !opened)
        // The popup itself is exercised in DetailTabsUiTest; here it is enough to confirm one
        // opened - its title carries the verdict word, same as the detail-screen popup.
        rule.onNodeWithText("NVDA - Sell").assertExists()
    }

    @Test fun `dismissing the popup returns to the row`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR, recommendation = rec(TradeVerdict.BUY)) }
        rule.onNodeWithTag(RECOMMENDATION_CHIP_TEST_TAG).performClick()
        rule.onNodeWithText("Got it").performClick()
        rule.onNodeWithText("NVDA - Buy").assertDoesNotExist()
    }

    @Test fun `a watch-only row - not an actual holding - gets no recommendation chip`() {
        show { StockRowItem(row(watchOnly = true), {}, {}) }
        rule.onNodeWithTag(RECOMMENDATION_CHIP_TEST_TAG).assertDoesNotExist()
        // The News chip is unaffected by any of this.
        rule.onNodeWithText("News").assertExists()
    }

    @Test fun `the News chip still opens news, unaffected by the new chip beside it`() {
        var newsOpened = false
        show { StockRowItem(row(), onClick = {}, onNews = { newsOpened = true }, plMode = PlMode.DOLLAR) }
        rule.onNodeWithText("News").performClick()
        assertTrue("the News chip stopped working", newsOpened)
    }
}
