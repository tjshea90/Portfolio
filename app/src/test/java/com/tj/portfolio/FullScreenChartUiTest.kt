package com.tj.portfolio

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow
import com.tj.portfolio.ui.CHART_TEST_TAG
import com.tj.portfolio.ui.EXPAND_TEST_TAG
import com.tj.portfolio.ui.FULLSCREEN_CLOSE_TAG
import com.tj.portfolio.ui.FULLSCREEN_TAG
import com.tj.portfolio.ui.FullScreenChart
import com.tj.portfolio.ui.PortfolioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE FULL-SCREEN CHART (Round 64).
 *
 * TJ: *"for any chart anywhere in the app, make it so I can press it and it opens full screen
 * and can rotate landscape or portrait with the phone sensors."*
 *
 * Two things here cannot be checked by reading the code, and both have a cost if wrong:
 *
 *   1. **The chart is actually bigger.** The viewer computes its height by subtracting a fixed
 *      allowance from the window, so any change to the header, the chips or the axis silently
 *      eats the drawn area. Measured, at a portrait size and a landscape one.
 *   2. **The rotation lock is put back.** The viewer forces `FULL_SENSOR` while it is open,
 *      because a phone with rotation lock on will not turn an app that merely permits it. If
 *      the restore is ever missed, closing one chart disables the user's rotation lock for the
 *      rest of the session - a bug that would look like an Android fault rather than an app
 *      one, and would never be reported as this feature's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullScreenChartUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun series() = ChartSeries(
        symbol = "NVDA", range = ChartRange.M6,
        points = (0 until 180).map { ChartPoint(1_740_000_000L + it * 86_400L, 100.0 + it) },
        baseline = 100.0, currency = "USD", fetched = System.currentTimeMillis()
    )

    private var closed = 0

    private fun show(open: Boolean = true) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) {
                    var showing by remember { mutableStateOf(open) }
                    if (showing) {
                        FullScreenChart(
                            symbol = "NVDA",
                            series = series(),
                            range = ChartRange.M6,
                            loading = false,
                            onRange = {},
                            perf = emptyMap(),
                            loadingRanges = emptySet(),
                            livePrice = 0.0,
                            liveEdge = false,
                            window = null,
                            windowBounds = ChartWindow(series().startMs, series().endMs),
                            onWindow = {},
                            compare = null,
                            compareLabel = "SPY",
                            compareLivePrice = 0.0,
                            onClose = { closed++; showing = false }
                        )
                    }
                }
            }
        }
    }

    /** The drawn chart's height, in dp. */
    private fun chartHeightDp(): Float {
        val d = rule.density.density
        val b = rule.onNodeWithTag(CHART_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return (b.bottom - b.top) / d
    }

    @Test fun `the viewer opens`() {
        show()
        rule.onNodeWithTag(FULLSCREEN_TAG).assertIsDisplayed()
    }

    @Test fun `the chart is much taller than the one on the detail screen`() {
        show()
        val h = chartHeightDp()
        assertTrue(
            "the full-screen chart measured ${h}dp - the inline one is already 170dp, " +
                "so this is not worth opening",
            h >= 400f
        )
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun `in landscape the chart still gets a usable height`() {
        show()
        val h = chartHeightDp()
        assertTrue("landscape left only ${h}dp for the chart", h >= 140f)
        assertTrue("landscape chart overflowed its window: ${h}dp", h <= 411f)
    }

    @Test fun `there is no expand button inside the expanded view`() {
        show()
        rule.onNodeWithTag(EXPAND_TEST_TAG).assertDoesNotExist()
    }

    @Test fun `the close button closes it`() {
        show()
        rule.onNodeWithTag(FULLSCREEN_CLOSE_TAG).performClick()
        rule.waitForIdle()
        assertEquals("close did not fire", 1, closed)
        rule.onNodeWithTag(FULLSCREEN_TAG).assertDoesNotExist()
    }

    @Test fun `the phone is allowed to turn while it is open`() {
        show()
        assertEquals(
            "the viewer did not ask for sensor rotation, so a phone with rotation lock on " +
                "would never turn it",
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            rule.activity.requestedOrientation
        )
    }

    @Test fun `and the rotation setting is put back when it closes`() {
        val before = rule.activity.requestedOrientation
        show()
        rule.onNodeWithTag(FULLSCREEN_CLOSE_TAG).performClick()
        rule.waitForIdle()
        assertEquals(
            "closing the full-screen chart left the activity's orientation forced",
            before, rule.activity.requestedOrientation
        )
    }
}
