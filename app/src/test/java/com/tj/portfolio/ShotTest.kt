package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.ui.BENCHMARK_SYMBOL
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceChart
import com.tj.portfolio.ui.EtfFactsGrid
import com.tj.portfolio.ui.KeyValue
import com.tj.portfolio.ui.RangeChips
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Throwaway: renders the new UI to PNGs so it can be looked at rather than reasoned about. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t0 = 1_757_000_000L
    private val stock = ChartSeries(
        symbol = "NVDA", range = ChartRange.M6,
        points = (0 until 120).map {
            ChartPoint(t0 + it * 86_400L, 140.0 + it * 0.55 + Math.sin(it / 7.0) * 6)
        },
        baseline = 0.0, fetched = System.currentTimeMillis()
    )
    private val bench = ChartSeries(
        symbol = BENCHMARK_SYMBOL, range = ChartRange.M6,
        points = (0 until 120).map {
            ChartPoint(t0 + it * 86_400L, 600.0 + it * 0.35 + Math.cos(it / 9.0) * 4)
        },
        baseline = 0.0, fetched = System.currentTimeMillis()
    )

    private fun shootScaled(name: String, scale: Float, content: @Composable () -> Unit) {
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(base.density, scale)
            ) {
                PortfolioTheme(dark = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxWidth().testTag("shot").padding(12.dp)) { content() }
                    }
                }
            }
        }
        capture(name)
    }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            PortfolioTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag("shot").padding(12.dp)) { content() }
                }
            }
        }
        capture(name)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val view = rule.activity.window.decorView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 1080, 2340)
        val bmp = android.graphics.Bitmap.createBitmap(1080, 2340, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("/home/claude/shots").also { it.mkdirs() }
        File(out, "$name.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun plainLight() {
        shoot("chart_plain_light", false) {
            Column {
                RangeChips(ChartRange.M6, {}, perf = mapOf(
                    ChartRange.D1 to 1.24, ChartRange.M1 to -3.1, ChartRange.M6 to 41.2,
                    ChartRange.Y1 to 88.0
                ))
                PriceChart(stock, ChartRange.M6, loading = false, onZoom = {})
            }
        }
    }

    @Test fun compareLight() {
        shoot("chart_compare_light", false) {
            Column {
                RangeChips(ChartRange.M6, {}, perf = mapOf(ChartRange.M6 to 41.2))
                PriceChart(stock, ChartRange.M6, loading = false, onZoom = {}, compare = bench)
            }
        }
    }

    // ---- the font-scale cases the sweep fixed, at the scale that used to break them.

    @Test fun rowsBig() = shootScaled("rows_2x", 2.0f) {
        Column {
            KeyValue("Gain on stocks you still own", "+$1,234.56  (+12.34%)")
            KeyValue("Market value  (45 x $230.115)", "$10,355.18")
            KeyValue("Cash not invested", "$1,204.77")
        }
    }

    @Test fun rowsMedium() = shootScaled("rows_1_3x", 1.3f) {
        Column {
            KeyValue("Gain on stocks you still own", "+$1,234.56  (+12.34%)")
            KeyValue("Market value  (45 x $230.115)", "$10,355.18")
        }
    }

    @Test fun factsBig() = shootScaled("etf_facts_2x", 2.0f) {
        EtfFactsGrid(
            com.tj.portfolio.data.EtfFacts(
                expenseRatio = 0.03, netAssets = 1.741139870E12, yieldPct = 1.04,
                ytdReturnPct = 13.11, oneYearPct = 118.41, threeYearAnnualPct = 126.25,
                fiveYearAnnualPct = 28.66
            )
        )
    }
}
