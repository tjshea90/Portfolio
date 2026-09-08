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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipe
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ROUND 60: SCRUBBING, DRIVEN BY REAL TOUCH EVENTS.
 *
 * `ScrubTest` covers the lookup as a pure function. This covers the thing source review
 * genuinely cannot answer, and the thing most likely to make TJ's app worse rather than
 * better: **has the chart stolen the page's vertical scroll?**
 *
 * The chart is 170dp tall and sits in a scrolling screen. A naive implementation - a raw
 * pointer loop that consumes the initial press - turns that into a dead zone where a normal
 * downward swipe does nothing, and nobody would describe that as a chart feature. So the
 * chart is placed inside a real `verticalScroll` here and the scroll position is measured
 * before and after a downward swipe that starts ON the chart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrubGestureUiTest {

    @get:Rule val rule = createComposeRule()

    /** 100 evenly spaced points climbing from $100 to $199, so any index has a unique price. */
    private fun series(range: ChartRange = ChartRange.D1) = ChartSeries(
        symbol = "TEST", range = range,
        points = (0 until 100).map { ChartPoint(1_757_000_000L + it * 300L, 100.0 + it) },
        baseline = 99.0, currency = "USD", fetched = System.currentTimeMillis(),
        regularOnly = range == ChartRange.D1
    )

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
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

    private fun readoutShowsWindowChange() =
        texts().any { it.contains("since yesterday's close") }

    // ------------------------------------------------------- the resting state

    @Test
    fun `at rest the readout describes the whole window`() {
        show { PriceChart(series(), ChartRange.D1, loading = false) }
        assertTrue("the resting readout is missing", readoutShowsWindowChange())
    }

    // ------------------------------------------------------------ scrubbing

    @Test
    fun `a horizontal drag shows the price at that point`() {
        show { PriceChart(series(), ChartRange.D1, loading = false) }

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            // Press, cross the horizontal touch slop, and HOLD - no up event, so the
            // readout is asserted mid-gesture, which is when it matters.
            down(centerLeft)
            moveTo(center)
        }
        rule.waitForIdle()

        assertTrue(
            "the readout still describes the window - the drag did not scrub:\n" +
                texts().joinToString("\n") { "  \"$it\"" },
            !readoutShowsWindowChange()
        )
        // The series climbs $100 -> $199 across the width, so the middle is around $150.
        val price = texts().firstOrNull { it.startsWith("$1") }
        assertTrue("no price was shown at the finger: ${texts()}", price != null)
        val value = price!!.removePrefix("$").replace(",", "").toDoubleOrNull()
        assertTrue("scrubbed price $price is outside the series", value != null)
        assertTrue(
            "scrubbed price $value is not near the middle of 100..199",
            value!! in 130.0..170.0
        )
    }

    @Test
    fun `lifting the finger restores the window readout`() {
        show { PriceChart(series(), ChartRange.D1, loading = false) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(centerLeft); moveTo(center)
        }
        rule.waitForIdle()
        assertTrue(!readoutShowsWindowChange())

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("the readout did not reset when the finger lifted", readoutShowsWindowChange())
    }

    @Test
    fun `dragging to the far left and far right reaches both ends of the series`() {
        show { PriceChart(series(), ChartRange.D1, loading = false) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(center); moveTo(centerLeft); moveTo(topLeft)
        }
        rule.waitForIdle()
        assertTrue("the left edge should read \$100.00: ${texts()}",
            texts().any { it == "$100.00" })

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { moveTo(centerRight) }
        rule.waitForIdle()
        assertTrue("the right edge should read \$199.00: ${texts()}",
            texts().any { it == "$199.00" })
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    // --------------------------------------- THE ONE THAT MATTERS MOST

    /**
     * A downward swipe that STARTS ON THE CHART must still scroll the page.
     *
     * This is the regression the feature could most easily cause, it would affect every use
     * of the detail screen rather than just the chart, and it is invisible to source review.
     */
    @Test
    fun `a vertical swipe starting on the chart still scrolls the page`() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            PortfolioTheme(dark = false) {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Text("header")
                    PriceChart(series(), ChartRange.D1, loading = false)
                    // Enough below the chart to make the page genuinely scrollable.
                    Box(Modifier.fillMaxWidth().height(2000.dp))
                }
            }
        }
        rule.waitForIdle()
        assertEquals("the page did not start at the top", 0, scroll.value)

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            // Upward swipe from the chart = scroll the page down.
            swipeUp()
        }
        rule.waitForIdle()

        assertTrue(
            "the chart swallowed the page's vertical scroll - scroll is still ${scroll.value}",
            scroll.value > 0
        )
    }

    /**
     * ...and the reverse, so the chart is not a one-way valve either.
     *
     * SHORT, EXPLICIT SWIPES, and a tall header above the chart. A full-height `swipeUp`
     * scrolls far enough that the chart itself leaves the screen, and the second gesture then
     * lands on a node that is no longer visible - which fails for a reason that has nothing
     * to do with the app. The header keeps the chart on screen throughout so the test
     * measures what it claims to.
     */
    @Test
    fun `the page can be scrolled back up from the chart`() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            PortfolioTheme(dark = false) {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(400.dp))
                    PriceChart(series(), ChartRange.D1, loading = false)
                    Box(Modifier.fillMaxWidth().height(2000.dp))
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 120f), durationMillis = 200)
        }
        rule.waitForIdle()
        val down = scroll.value
        assertTrue("the page did not scroll down from the chart", down > 0)

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 120f), durationMillis = 200)
        }
        rule.waitForIdle()
        assertTrue(
            "could not scroll back up from the chart - it is a one-way valve " +
                "(was $down, now ${scroll.value})",
            scroll.value < down
        )
    }

    // ------------------------------------ the bug a quote tick would have caused

    /**
     * THE REGRESSION THIS FEATURE ALMOST SHIPPED WITH.
     *
     * `withLiveEdge` rebuilds the series on every quote tick - four times a minute while the
     * market is open. The first version keyed both the scrub state and the pointer handler on
     * that rebuilt series, so a tick mid-gesture cleared the crosshair and cancelled the drag.
     * It would have worked perfectly in testing after hours and broken every fifteen seconds
     * during the day, which is the worst possible shape for a bug.
     *
     * Driven here by changing `livePrice` while a finger is down.
     */
    @Test
    fun `a live price update mid-drag does not cancel the scrub`() {
        var price by androidx.compose.runtime.mutableDoubleStateOf(150.0)
        rule.setContent {
            PortfolioTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    PriceChart(
                        series(), ChartRange.D1, loading = false,
                        livePrice = price, liveEdge = true
                    )
                }
            }
        }

        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { down(centerLeft); moveTo(center) }
        rule.waitForIdle()
        val during = texts().firstOrNull { it.startsWith("$1") }
        assertTrue("the drag did not scrub at all", during != null)

        // A quote tick lands, exactly as it would at 15-second intervals all day.
        price = 151.0
        rule.waitForIdle()

        assertTrue(
            "a quote tick cleared the crosshair out from under the finger: " +
                texts().joinToString(" | "),
            !readoutShowsWindowChange()
        )
        // ...and the gesture is still live, so moving still moves the crosshair.
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { moveTo(topLeft) }
        rule.waitForIdle()
        assertTrue(
            "the drag was cancelled by the tick - moving no longer scrubs: ${texts()}",
            texts().any { it == "$100.00" }
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    // ------------------------------------------------------------ robustness

    /**
     * A chart with nothing in it must not respond to, or crash on, a drag - but it MUST still
     * carry the gesture surface.
     *
     * THIS ASSERTION WAS INVERTED UNTIL ROUND 63, and the old form was hiding a real bug. It
     * required the surface NOT to exist while the series was missing, which was true and
     * looked harmless - until pinch zoom arrived. Pinching to a range this symbol has never
     * been fetched at leaves the series null for the length of a network round trip, so a
     * chart with no gesture surface meant the node was DESTROYED with the user's fingers
     * still on the glass: the zoom stopped after one rung and the remaining touches fell
     * through to the list underneath. The surface is now always present; what an empty chart
     * must not do is scrub, throw, or claim a price it does not have.
     */
    @Test
    fun `an empty chart keeps its gesture surface but does not scrub`() {
        show { PriceChart(null, ChartRange.D1, loading = false, onZoom = {}) }
        rule.onAllNodesWithTag(CHART_TEST_TAG).fetchSemanticsNodes().let {
            assertTrue(
                "an empty chart must still be pinchable - a zoom lands here mid-gesture",
                it.isNotEmpty()
            )
        }
        // A drag on it does nothing at all, and above all does not crash.
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { down(centerLeft); moveTo(center) }
        rule.waitForIdle()
        assertTrue(
            "an empty chart printed a price at the finger: ${texts()}",
            texts().none { it.startsWith("$") }
        )
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }

    /** And the pinch really does survive the window it is zooming INTO not being loaded. */
    @Test
    fun `a pinch keeps working while the chart it landed on is still loading`() {
        val steps = ArrayList<Int>()
        show { PriceChart(null, ChartRange.M6, loading = true, onZoom = { steps.add(it) }) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(center.x - 20f, center.y))
            down(1, androidx.compose.ui.geometry.Offset(center.x + 20f, center.y))
            moveTo(0, androidx.compose.ui.geometry.Offset(center.x - 200f, center.y))
            moveTo(1, androidx.compose.ui.geometry.Offset(center.x + 200f, center.y))
            up(0); up(1)
        }
        rule.waitForIdle()
        assertTrue(
            "the zoom died on a chart that had not arrived yet - the fingers were still down",
            steps.isNotEmpty()
        )
    }

    @Test
    fun `the after-hours range scrubs too and dates its readout`() {
        show { PriceChart(series(ChartRange.OVERNIGHT), ChartRange.OVERNIGHT, loading = false) }
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { down(centerLeft); moveTo(center) }
        rule.waitForIdle()
        assertTrue("no price at the finger on the after-hours chart: ${texts()}",
            texts().any { it.startsWith("$1") })
        rule.onNodeWithTag(CHART_TEST_TAG).performTouchInput { up() }
    }
}
