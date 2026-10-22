package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.EarningsResult
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.DivEvent
import com.tj.portfolio.domain.DivHeld
import com.tj.portfolio.domain.DivInput
import com.tj.portfolio.domain.Dividends
import com.tj.portfolio.net.EarningsFeed
import com.tj.portfolio.net.EarningsOutlook
import com.tj.portfolio.ui.DividendsContent
import com.tj.portfolio.ui.EarnEntry
import com.tj.portfolio.ui.EarningsBoard
import com.tj.portfolio.ui.EarningsContent
import com.tj.portfolio.ui.PortfolioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/** The Dividends and Earnings bodies, actually rendered, at the font scales a phone really uses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IncomeUiTest {

    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 22)

    private fun show(fontScale: Float, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun assertNothingOverflows(label: String) {
        val w = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxOf { it.size.width }.toFloat()
        val nodes = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("has text") {
            it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
        }).fetchSemanticsNodes()
        assertTrue("$label: nothing rendered", nodes.isNotEmpty())
        val spills = nodes.filter { it.boundsInRoot.right > w + 0.5f }.map {
            "\"${it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")}\" ends at ${it.boundsInRoot.right} (screen $w)"
        }
        assertTrue("$label: text runs off the right edge:\n  " + spills.joinToString("\n  "), spills.isEmpty())
    }

    private fun ms(d: LocalDate) = d.atTime(12, 0).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    private fun report(): com.tj.portfolio.domain.DividendReport {
        val q = (0 until 10).map { DivEvent(LocalDate.of(2024, 3, 8).plusMonths(3L * it), 0.5125) }
        val txns = listOf(
            Txn(1, TxnType.BUY, "KO", 100.0, 60.0, -6000.0, 0.0, ms(LocalDate.of(2023, 1, 5))),
            Txn(2, TxnType.DIVIDEND, "KO", amount = 51.25, date = ms(LocalDate.of(2025, 3, 25))),
            Txn(3, TxnType.BUY, "JEPI", 250.0, 55.0, -13750.0, 0.0, ms(LocalDate.of(2024, 6, 1))),
            Txn(4, TxnType.BUY, "TSLA", 5.0, 200.0, -1000.0, 0.0, ms(LocalDate.of(2024, 6, 1)))
        )
        val monthly = (0 until 18).map { DivEvent(LocalDate.of(2025, 5, 1).plusMonths(it.toLong()), 0.40 + (it % 3) * 0.05) }
        return Dividends.build(
            today, txns,
            listOf(DivHeld("KO", 100.0, 6000.0), DivHeld("JEPI", 250.0, 13750.0), DivHeld("TSLA", 5.0, 1000.0)),
            listOf(
                DivInput("KO", "Coca-Cola Company With A Long Name Incorporated", 70.0, history = q),
                DivInput("JEPI", "JPMorgan Equity Premium Income ETF", 57.0, isEtf = true, history = monthly),
                DivInput("TSLA", "Tesla", 400.0)
            )
        )
    }

    @Test fun dividendsRenderAtEveryFontScaleAndTheme() {
        val r = report()
        assertTrue("fixture should have rows", r.past.isNotEmpty() && r.upcoming.isNotEmpty())
        for (scale in listOf(1f, 1.3f, 2f)) for (dark in listOf(false, true)) {
            val label = "scale=$scale dark=$dark"
            show(scale, dark) { DividendsContent(r, false, false, 0L, 2) {} }
            rule.onNodeWithText("Total dividends paid").assertExists()
            rule.onNodeWithText("Upcoming dividends", ignoreCase = true).assertExists()
            assertNothingOverflows(label)
            rule.activityRule.scenario.recreate()
        }
    }

    @Test fun dividendsEmptyAndLoadingStatesRender() {
        show(1f, false) { DividendsContent(report(), true, true, 0L, 0) {} }
        rule.onNodeWithText("Nothing held yet").assertExists()
    }

    private fun entry(sym: String, days: Long, light: EarningsOutlook.Result, owned: Boolean = true) = EarnEntry(
        symbol = sym, name = "$sym Corporation Holdings Incorporated", owned = owned,
        viaFunds = if (owned) emptyList() else listOf("QQQ" to 0.081),
        date = EarningsFeed.Date(ms(today.plusDays(days)).let { it + 8 * 3_600_000L }),
        day = today.plusDays(days), daysAway = days, outlook = light, reported = null, epsExpected = 1.42,
        headlines = listOf(com.tj.portfolio.data.NewsItem(sym, "$sym beats estimates and raises guidance", "u", "Reuters", ms(today)))
    )

    private fun lights() = listOf(
        EarningsOutlook.Result(42.0, EarningsOutlook.Light.GREEN, true, 1.0,
            listOf(EarningsOutlook.Reason("Track record", "Beat estimates in 4 of the last 4 quarters", 1, 0.3))),
        EarningsOutlook.Result(0.0, EarningsOutlook.Light.YELLOW, false, 0.0, EarningsOutlook.Result.UNKNOWN.reasons),
        EarningsOutlook.Result(-30.0, EarningsOutlook.Light.RED, true, 0.8,
            listOf(EarningsOutlook.Reason("News tone", "6 recent headlines: 0 positive, 5 negative", -1, 0.2)))
    )

    @Test fun earningsRenderWithAllThreeLights() {
        val l = lights()
        val board = EarningsBoard(
            upcoming = listOf(entry("NVDA", 3, l[0]), entry("MSFT", 6, l[1], owned = false), entry("INTC", 11, l[2])),
            recent = emptyList(), undated = listOf(entry("KO", -80, l[1]))
        )
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) {
            show(scale, dark) { EarningsContent(board, today, false, false, setOf("NVDA"), {}) {} }
            rule.onNodeWithText("Upcoming reports", ignoreCase = true).assertExists()
            // The green row is expanded: its reasons are on screen.
            rule.onNodeWithText("Track record: Beat estimates in 4 of the last 4 quarters", substring = true).assertExists()
            assertNothingOverflows("scale=$scale dark=$dark")
            rule.activityRule.scenario.recreate()
        }
    }

    @Test fun tappingARowAsksToExpandIt() {
        val l = lights()
        var toggled: String? = null
        val board = EarningsBoard(listOf(entry("NVDA", 3, l[0])), emptyList(), emptyList())
        show(1f, false) { EarningsContent(board, today, false, false, emptySet(), {}) { toggled = it } }
        rule.onNodeWithText("NVDA").performClick()
        assertTrue(toggled == "NVDA")
    }
}
