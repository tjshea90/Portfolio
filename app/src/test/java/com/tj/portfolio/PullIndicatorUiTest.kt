package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.Refreshable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE STRANDED PULL-TO-REFRESH CIRCLE (Tj, 2026-09-23c).
 *
 * Material3 1.4.0 hides the indicator after a release only from inside the list's fling
 * coroutine; a touch that cancels that fling leaves the circle where it was, and a refresh that
 * already finished never moves it again. These tests put the indicator into exactly that state -
 * showing, not refreshing, nothing animating - and check [Refreshable] clears it, while leaving
 * alone the two cases where a visible indicator is right: a refresh in progress, and a finger
 * still pulling. MUTATION-CHECKED: with the watchdog removed, the first test fails (the circle
 * stays at 1.0 indefinitely, which is the bug).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PullIndicatorUiTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var state: PullToRefreshState
    private lateinit var scope: CoroutineScope
    private var refreshing by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            PortfolioTheme(dark = true) {
                state = rememberPullToRefreshState()
                scope = rememberCoroutineScope()
                Refreshable(refreshing = refreshing, onRefresh = {}, state = state) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }
        rule.waitForIdle()
    }

    /** What the cancelled hide leaves behind: the circle parked at the threshold, idle. */
    private fun strand() {
        rule.runOnIdle { scope.launch { state.snapTo(1f) } }
        rule.mainClock.advanceTimeByFrame()
    }

    @Test fun `an abandoned indicator is put away`() {
        show()
        strand()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(0f, state.distanceFraction, 1e-3f)
    }

    @Test fun `the indicator stays while a refresh is running`() {
        refreshing = true
        show()
        strand()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(1f, state.distanceFraction, 1e-3f)
    }

    @Test fun `the indicator is not pulled out from under a finger`() {
        show()
        rule.onNodeWithTag("content").performTouchInput { down(center) }
        strand()
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals("hidden while the finger was still down", 1f, state.distanceFraction, 1e-3f)
        // ...and once the finger lifts, it goes.
        rule.onNodeWithTag("content").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(0f, state.distanceFraction, 1e-3f)
    }
}
