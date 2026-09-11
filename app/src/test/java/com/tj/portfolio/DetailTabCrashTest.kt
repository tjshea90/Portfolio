package com.tj.portfolio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.tj.portfolio.ui.DetailTab
import com.tj.portfolio.ui.DetailTabRow
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.visibleTabs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tab strip must survive its own list changing size under a live selection.
 *
 * THE CRASH THIS REPRODUCES, from TJ's phone (Sep 10 2026, four times in two minutes):
 *
 *     java.lang.IndexOutOfBoundsException: Index 5 out of bounds for length 5
 *         at androidx.compose.material3.TabRowKt$ScrollableTabRow$1.invoke(TabRow.kt:1409)
 *
 * Material3's default indicator reads `tabPositions[selectedTabIndex]`. `selectedTabIndex`
 * is a composition value; `tabPositions` comes from the strip's measure pass. Grow the tab
 * list while the LAST tab is selected and, for one frame, the index is one past the end of
 * the positions - which is fatal, not cosmetic.
 *
 * Here that happens because Holdings is hidden until the fund lookup returns, which is about
 * a second after the screen opens - the same second the News tab's headlines land.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailTabCrashTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the tab list growing under the last selection does not crash`() {
        var isFund by mutableStateOf(false)

        rule.setContent {
            PortfolioTheme {
                val tabs = visibleTabs(isFund)
                var tab by remember { mutableStateOf(DetailTab.NEWS) }
                DetailTabRow(tabs, tab) { tab = it }
            }
        }
        rule.waitForIdle()

        // Six tabs, News selected at index 5. Now the lookup returns "fund" and the list
        // becomes seven, so News moves to index 6 while the measured positions still hold six.
        assertEquals(6, visibleTabs(false).size)
        assertEquals(7, visibleTabs(true).size)
        assertEquals(6, visibleTabs(true).indexOf(DetailTab.NEWS))

        isFund = true
        rule.waitForIdle()      // threw before the indicator was bounded

        // And back again - a memory trim drops the holdings and the list shrinks.
        isFund = false
        rule.waitForIdle()
    }

    @Test
    fun `every tab survives the list resizing under it`() {
        // ONE setContent for the whole test: `createComposeRule` allows exactly one per
        // rule, and calling it in a loop throws "has already set content" - which is what
        // the first version of this test did, and it failed for that reason rather than
        // for anything about the tab strip.
        var isFund by mutableStateOf(false)
        var current by mutableStateOf(DetailTab.OVERVIEW)

        rule.setContent {
            PortfolioTheme {
                DetailTabRow(visibleTabs(isFund), current) { current = it }
            }
        }
        rule.waitForIdle()

        for (t in DetailTab.entries) {
            for (fund in listOf(false, true, false)) {
                current = t
                isFund = fund
                rule.waitForIdle()
            }
        }
        assertEquals(5, visibleTabs(false).size)
        assertEquals(6, visibleTabs(true).size)
    }
}
