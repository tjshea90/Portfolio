package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.BigTabBar
import com.tj.portfolio.ui.PortfolioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE BOTTOM TAB BAR, MEASURED INSIDE A REAL SCAFFOLD (Round 63, sweep 3).
 *
 * This file exists because of a regression that would have shipped and made the app render
 * NOTHING. The sweep changed the bar's `height(74.dp)` to `heightIn(min = 74.dp)` so it could
 * grow with the font scale - a reasonable-looking change that is catastrophic here, because
 * each tab's `Column` carries `.weight(1f).fillMaxSize()` and `height` was the only thing
 * bounding their maximum. `Scaffold` measures a `bottomBar` with LOOSE constraints, so every
 * child filled the screen, the Row sized to its tallest child, and the bar consumed the lot:
 * 891dp of bar and 0dp of content.
 *
 * No amount of reading the modifier chain would have caught that. Measuring it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabBarUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = false) {
                    Scaffold(
                        bottomBar = {
                            Box(Modifier.testTag("bar")) { BigTabBar(selected = 0, onSelect = {}) }
                        }
                    ) { pad ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("body")
                                .padding(pad)
                        ) { Text("content", Modifier.testTag("bodyText")) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun barHeightDp(): Float {
        val b = rule.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        return (b.bottom - b.top).value
    }

    private fun bodyHeightDp(): Float {
        val b = rule.onNodeWithTag("body").getUnclippedBoundsInRoot()
        return (b.bottom - b.top).value
    }

    @Test fun `the bar is the height it says it is`() {
        show(1.0f)
        val bar = barHeightDp()
        // 74dp plus whatever navigation inset the device reports. Anything materially larger
        // means the bar is sizing to its children instead of pinning them.
        assertTrue("the tab bar measured ${bar}dp, expected about 74dp", bar in 60f..140f)
        val body = bodyHeightDp()
        assertTrue(
            "the tab bar has eaten the content area - body is only ${body}dp of 891dp",
            body > 700f
        )
    }

    @Test fun `it is still that height at a large font scale`() {
        show(2.0f)
        val bar = barHeightDp()
        assertTrue("at 2x the tab bar measured ${bar}dp", bar in 60f..140f)
    }

    @Test fun `it still leaves the screen at 1_5x`() {
        show(1.5f)
        assertTrue("at 1.5x the bar left only ${bodyHeightDp()}dp", bodyHeightDp() > 600f)
        rule.onNodeWithTag("bodyText").assertIsDisplayed()
    }

    @Test fun `it still leaves the screen at 2x`() {
        // 2.0x is a real Android setting and it is what tempted the change that broke this.
        // Whatever the bar does about its labels, it may not take the screen.
        show(2.0f)
        assertTrue("at 2x the bar left only ${bodyHeightDp()}dp", bodyHeightDp() > 600f)
        rule.onNodeWithTag("bodyText").assertIsDisplayed()
    }

    @Test fun `every tab is still labelled and reachable`() {
        show(1.0f)
        listOf("Portfolio", "Watch", "Feed", "Activity", "Advice", "Settings").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
    }
}
