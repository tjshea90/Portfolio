package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.swipeBetweenTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE TWO NEW GESTURES, DRIVEN BY REAL TOUCH EVENTS (Round 63).
 *
 * Source review cannot answer the only question that matters about either of them: **has this
 * gesture stolen something the user already had?** A tab swipe that eats a list's vertical
 * scroll, or a pinch handler that makes the chart a dead zone for the page it sits in, would
 * each be a bigger regression than the feature is a feature - and the app has shipped exactly
 * that class of bug before, which is why `ScrubGestureUiTest` exists at all.
 *
 * So every test here checks both halves: the new gesture works, AND the old one still does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureUiTest {

    @get:Rule val rule = createComposeRule()

    private val PAGE = "swipePage"

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun series(range: ChartRange = ChartRange.D1) = ChartSeries(
        symbol = "TEST", range = range,
        points = (0 until 100).map { ChartPoint(1_757_000_000L + it * 300L, 100.0 + it) },
        baseline = 99.0, currency = "USD", fetched = System.currentTimeMillis(),
        regularOnly = range == ChartRange.D1
    )

    private fun texts(): List<String> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    // =========================================================== the tab swipe

    /** A tab body that scrolls vertically, wrapped in the swipe handler. */
    @Composable
    private fun SwipePage(onSwitch: (Int) -> Unit, current: Int = 2) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(PAGE)
                .swipeBetweenTabs(enabled = true, current = current, tabCount = 6, onSwitch = onSwitch)
        ) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                repeat(60) { Text("row $it", Modifier.fillMaxWidth().height(40.dp).testTag("s$it")) }
            }
        }
    }

    @Test fun `a horizontal swipe changes tab`() {
        var landed = -1
        show { SwipePage(onSwitch = { landed = it }) }
        rule.onNodeWithTag(PAGE).performTouchInput {
            down(Offset(width * 0.8f, height * 0.5f))
            moveTo(Offset(width * 0.55f, height * 0.5f))
            moveTo(Offset(width * 0.25f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        assertEquals("a leftward swipe should advance one tab", 3, landed)
    }

    @Test fun `a swipe the other way goes back one tab`() {
        var landed = -1
        show { SwipePage(onSwitch = { landed = it }) }
        rule.onNodeWithTag(PAGE).performTouchInput {
            down(Offset(width * 0.2f, height * 0.5f))
            moveTo(Offset(width * 0.45f, height * 0.5f))
            moveTo(Offset(width * 0.75f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        assertEquals("a rightward swipe should go back one tab", 1, landed)
    }

    @Test fun `a vertical swipe still scrolls the page and changes no tab`() {
        // THE REGRESSION THIS FILE EXISTS FOR. A parent that claims every drag turns every
        // scrolling tab into a dead surface.
        var landed = -1
        var scrollState: androidx.compose.foundation.ScrollState? = null
        rule.setContent {
            PortfolioTheme(dark = false) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(PAGE)
                        .swipeBetweenTabs(true, 2, 6) { landed = it }
                ) {
                    val scroll = rememberScrollState()
                    scrollState = scroll
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        repeat(60) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
                    }
                }
            }
        }
        rule.onNodeWithTag(PAGE).performTouchInput {
            down(Offset(width * 0.5f, height * 0.7f))
            moveTo(Offset(width * 0.5f, height * 0.4f))
            moveTo(Offset(width * 0.5f, height * 0.15f))
            up()
        }
        rule.waitForIdle()
        assertEquals("a vertical swipe must not change tab", -1, landed)
        assertTrue(
            "the page did not scroll - the swipe handler has stolen it",
            (scrollState?.value ?: 0) > 0
        )
    }

    @Test fun `a diagonal drag that is mostly vertical is not a tab change`() {
        var landed = -1
        show { SwipePage(onSwitch = { landed = it }) }
        rule.onNodeWithTag(PAGE).performTouchInput {
            down(Offset(width * 0.7f, height * 0.7f))
            moveTo(Offset(width * 0.6f, height * 0.4f))
            moveTo(Offset(width * 0.5f, height * 0.1f))
            up()
        }
        rule.waitForIdle()
        assertEquals(-1, landed)
    }

    @Test fun `a tap is not a swipe`() {
        var landed = -1
        show { SwipePage(onSwitch = { landed = it }) }
        rule.onNodeWithTag(PAGE).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(-1, landed)
    }

    @Test fun `the gesture does nothing while a layer is open above the tab`() {
        var landed = -1
        show {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(PAGE)
                    .swipeBetweenTabs(enabled = false, current = 2, tabCount = 6) { landed = it }
            ) { Text("detail screen") }
        }
        rule.onNodeWithTag(PAGE).performTouchInput {
            down(Offset(width * 0.8f, height * 0.5f))
            moveTo(Offset(width * 0.2f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        assertEquals("a swipe over an open detail screen must do nothing", -1, landed)
    }

    // ============================================================== the pinch

    @Test fun `spreading two fingers zooms in and pinching zooms out`() {
        val steps = ArrayList<Int>()
        show { PriceChart(series(), ChartRange.M6, loading = false, onZoom = { steps.add(it) }) }

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            // Two fingers, 40px apart, spread to 400px: a factor of ten, which at 1.55 per
            // rung is four rungs - more than enough to prove it steps repeatedly rather than
            // once.
            down(0, Offset(center.x - 20f, center.y))
            down(1, Offset(center.x + 20f, center.y))
            moveTo(0, Offset(center.x - 200f, center.y))
            moveTo(1, Offset(center.x + 200f, center.y))
            up(0); up(1)
        }
        rule.waitForIdle()
        assertTrue("a spread produced no zoom steps", steps.isNotEmpty())
        assertTrue("a spread must zoom IN (positive steps): $steps", steps.all { it > 0 })

        steps.clear()
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(center.x - 200f, center.y))
            down(1, Offset(center.x + 200f, center.y))
            moveTo(0, Offset(center.x - 20f, center.y))
            moveTo(1, Offset(center.x + 20f, center.y))
            up(0); up(1)
        }
        rule.waitForIdle()
        assertTrue("a pinch produced no zoom steps", steps.isNotEmpty())
        assertTrue("a pinch must zoom OUT (negative steps): $steps", steps.all { it < 0 })
    }

    @Test fun `a small pinch that has not crossed a rung does nothing`() {
        val steps = ArrayList<Int>()
        show { PriceChart(series(), ChartRange.M6, loading = false, onZoom = { steps.add(it) }) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(center.x - 100f, center.y))
            down(1, Offset(center.x + 100f, center.y))
            // 200px to 220px: a factor of 1.1, well inside one rung.
            moveTo(0, Offset(center.x - 110f, center.y))
            moveTo(1, Offset(center.x + 110f, center.y))
            up(0); up(1)
        }
        rule.waitForIdle()
        assertTrue("a nudge should not change range: $steps", steps.isEmpty())
    }

    @Test fun `a pinch does not also scrub`() {
        // Two fingers moving apart also move individually, and a scrub detector that only
        // looks at one pointer would chase whichever it latched onto - leaving a crosshair
        // frozen on the line for the whole gesture.
        show { PriceChart(series(), ChartRange.D1, loading = false, onZoom = { }) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, Offset(center.x - 20f, center.y))
            down(1, Offset(center.x + 20f, center.y))
            moveTo(0, Offset(center.x - 200f, center.y))
            moveTo(1, Offset(center.x + 200f, center.y))
        }
        rule.waitForIdle()
        assertTrue(
            "the readout was scrubbed during a pinch:\n" + texts().joinToString("\n"),
            texts().any { it.contains("since yesterday's close") }
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up(0); up(1) }
    }

    @Test fun `one finger still scrubs with the pinch handler installed`() {
        show { PriceChart(series(), ChartRange.D1, loading = false, onZoom = { }) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        rule.waitForIdle()
        assertTrue(
            "scrubbing stopped working when zoom was added:\n" + texts().joinToString("\n"),
            texts().none { it.contains("since yesterday's close") }
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    @Test fun `the chart still lets the page scroll past it`() {
        // Re-asserted here rather than left to `ScrubGestureUiTest`, because the gesture
        // handler those tests were written against has been replaced wholesale.
        var scrollState: androidx.compose.foundation.ScrollState? = null
        rule.setContent {
            PortfolioTheme(dark = false) {
                val scroll = rememberScrollState()
                scrollState = scroll
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Text("above", Modifier.fillMaxWidth().height(200.dp))
                    PriceChart(series(), ChartRange.D1, loading = false, onZoom = { })
                    repeat(30) { Text("row $it", Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue(
            "the chart is a dead zone for the page's scroll",
            (scrollState?.value ?: 0) > 0
        )
    }
}
