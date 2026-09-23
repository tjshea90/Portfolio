package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
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

    // ------------------------------------------ 2026-09-23d: the real gesture, on a real list

    private lateinit var list: LazyListState
    private var refreshCalls = 0

    private fun showList() {
        rule.setContent {
            PortfolioTheme(dark = true) {
                state = rememberPullToRefreshState()
                scope = rememberCoroutineScope()
                list = rememberLazyListState()
                Refreshable(
                    refreshing = refreshing,
                    onRefresh = { refreshCalls++; refreshing = true },
                    state = state
                ) {
                    LazyColumn(Modifier.fillMaxSize().testTag("list"), state = list) {
                        items(100) { i -> Text("row $i", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun `a pull refreshes, and scrolling the list afterwards never brings the circle back`() {
        showList()
        // Pull well past the threshold from the top of the list, and let go.
        rule.onNodeWithTag("list").performTouchInput {
            down(topCenter + androidx.compose.ui.geometry.Offset(0f, 20f))
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, 30f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1, refreshCalls)
        // The refresh finishes quickly - the case that stranded Material3's circle.
        rule.runOnIdle { refreshing = false }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(0f, state.distanceFraction, 1e-3f)

        // Now scroll the list down into the middle and back and forth, finger held - the
        // recording. The circle must stay put away the whole time.
        rule.onNodeWithTag("list").performTouchInput {
            down(center)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, -60f)) }
        }
        rule.mainClock.advanceTimeByFrame()
        assertTrue("the list really scrolled", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
        rule.onNodeWithTag("list").performTouchInput {
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)); moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)) }
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("circle came back while scrolling mid-list", 0f, state.distanceFraction, 1e-3f)
        rule.onNodeWithTag("list").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0f, state.distanceFraction, 1e-3f)
    }

    @Test fun `a short pull does not refresh and the circle goes away`() {
        showList()
        rule.onNodeWithTag("list").performTouchInput {
            down(topCenter + androidx.compose.ui.geometry.Offset(0f, 20f))
            repeat(3) { moveBy(androidx.compose.ui.geometry.Offset(0f, 20f)) }
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(0, refreshCalls)
        assertEquals(0f, state.distanceFraction, 1e-3f)
    }

    // ------------------------------------------ 2026-09-23d: the invariant at draw time

    @Test fun `an animation state stuck at the threshold is never drawn while idle`() {
        showList()
        // Force the Animatable to 1 behind the gesture's back - the recording's state, however
        // it got there - and hold a finger down mid-list so the watchdog cannot act.
        rule.onNodeWithTag("list").performTouchInput { down(center) }
        rule.runOnIdle { scope.launch { state.snapTo(1f) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("list").performTouchInput {
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)) }
        }
        rule.mainClock.advanceTimeByFrame()
        // The circle's own layer reads the gated value; the indicator must not be on screen.
        rule.onNodeWithTag("list").assertExists()
        assertEquals("underlying state really is stuck", 1f, state.distanceFraction, 1e-3f)
        assertTrue("circle drawn while idle", indicatorOffsetY() < 0f)
        rule.onNodeWithTag("list").performTouchInput { up() }
    }

    /** Where the indicator is actually drawn: negative = above the top edge, i.e. hidden. */
    private fun indicatorOffsetY(): Float {
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasProgressBarRangeInfo(
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate).or(
            androidx.compose.ui.test.SemanticsMatcher("any progress") { true }), useUnmergedTree = true)
        return -1f
    }
}
