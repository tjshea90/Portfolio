package com.tj.portfolio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.Refreshable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PullStressScratchTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var state: PullToRefreshState
    private lateinit var list: LazyListState
    private var refreshing by mutableStateOf(false)

    @Test fun stress() {
        rule.setContent {
            PortfolioTheme(dark = true) {
                state = rememberPullToRefreshState()
                list = rememberLazyListState()
                Refreshable(refreshing = refreshing, onRefresh = { refreshing = true }, state = state) {
                    LazyColumn(Modifier.fillMaxSize().testTag("list"), state = list) {
                        items(100) { i -> Text("row $i", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val rnd = java.util.Random(7)
        var failures = 0
        for (round in 0 until 80) {
            // back to top
            rule.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollToItem(0) } }
            rule.waitForIdle()
            val pullSteps = 10 + rnd.nextInt(15)
            val refreshMs = listOf(0L, 16L, 50L, 120L, 250L, 400L, 800L)[rnd.nextInt(7)]
            val touchAfterMs = listOf(0L, 16L, 60L, 150L, 300L)[rnd.nextInt(5)]
            val node = rule.onNodeWithTag("list")
            node.performTouchInput {
                down(topCenter + Offset(0f, 20f))
                repeat(pullSteps) { moveBy(Offset(0f, 30f)) }
                up()
            }
            // refresh completes after refreshMs; a second touch lands after touchAfterMs
            var t = 0L
            var touched = false
            var ended = !refreshing
            while (t < 1500) {
                if (!ended && t >= refreshMs) { rule.runOnIdle { refreshing = false }; ended = true }
                if (!touched && t >= touchAfterMs) {
                    node.performTouchInput { down(center); repeat(4) { moveBy(Offset(0f, -40f)) } }
                    touched = true
                }
                if (touched) {
                    val dy = if ((t / 16) % 2 == 0L) 25f else -25f
                    node.performTouchInput { moveBy(Offset(0f, dy)) }
                }
                rule.mainClock.advanceTimeBy(16); t += 16
            }
            node.performTouchInput { repeat(6) { moveBy(Offset(0f, 30f)); moveBy(Offset(0f, -30f)) } }
            rule.mainClock.advanceTimeByFrame()
            val midScroll = state.distanceFraction
            node.performTouchInput { up() }
            rule.mainClock.advanceTimeBy(1500)
            rule.waitForIdle()
            val settled = state.distanceFraction
            if (midScroll > 0.01f || settled > 0.01f) {
                failures++
                println("STRESS FAIL round=$round pull=$pullSteps refresh=$refreshMs touch=$touchAfterMs mid=$midScroll settled=$settled")
            }
        }
        println("STRESS failures=$failures")
    }
}
