package com.tj.portfolio

import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.EarningsEstimate
import com.tj.portfolio.data.EarningsResult
import com.tj.portfolio.data.FundHolding
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.RatingTrend
import com.tj.portfolio.domain.Position
import com.tj.portfolio.net.EarningsFeed
import com.tj.portfolio.net.EarningsOutlook
import com.tj.portfolio.net.EarningsOutlook.Light
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.UiState
import com.tj.portfolio.ui.buildEarningsBoard
import com.tj.portfolio.ui.reportedLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarningsTest {

    private val now = 1_790_000_000_000L
    private val day = 86_400_000L
    private fun news(title: String, ageDays: Double = 1.0) =
        NewsItem("X", title, "u$title", "src", now - (ageDays * day).toLong())

    private fun beat(surprise: Double) = EarningsResult("q", now - 90 * day, 1.0, 1.0 + surprise / 100, surprise)
    private val strong = EarningsOutlook.Input(
        price = 100.0,
        estimates = listOf(EarningsEstimate("0q", epsAvg = 1.30, epsYearAgo = 1.00, revenueGrowth = 0.15)),
        history = listOf(beat(8.0), beat(6.0), beat(10.0), beat(5.0)),
        consensus = Consensus(mean = 1.8, analysts = 30, targetMean = 125.0),
        trend = listOf(RatingTrend("0m", strongBuy = 10, buy = 12, hold = 6), RatingTrend("-3m", strongBuy = 6, buy = 10, hold = 12)),
        headlines = listOf(news("Acme beats estimates and raises guidance"), news("Analyst upgrade on Acme"), news("Acme record revenue")),
        fiftyDayAverage = 96.0, now = now
    )
    private val weak = EarningsOutlook.Input(
        price = 100.0,
        estimates = listOf(EarningsEstimate("0q", epsAvg = 0.70, epsYearAgo = 1.00, revenueGrowth = -0.08)),
        history = listOf(beat(-6.0), beat(-9.0), beat(2.0), beat(-4.0)),
        consensus = Consensus(mean = 3.9, analysts = 20, targetMean = 85.0),
        trend = listOf(RatingTrend("0m", buy = 4, hold = 12, sell = 8), RatingTrend("-3m", buy = 10, hold = 10, sell = 4)),
        headlines = listOf(news("Acme misses estimates, cuts guidance"), news("Acme downgraded"), news("Acme lawsuit probe widens")),
        fiftyDayAverage = 112.0, now = now
    )

    // ---------------------------------------------------------------- tone

    @Test fun headlineToneReadsTheObvious() {
        assertTrue(EarningsOutlook.headlineTone("Acme beats estimates, raises guidance") > 0.5)
        assertTrue(EarningsOutlook.headlineTone("Acme misses estimates and cuts guidance") < -0.5)
        assertEquals(0.0, EarningsOutlook.headlineTone("Acme to hold annual meeting Tuesday"), 0.0)
    }

    @Test fun negatedGoodNewsIsNotGoodNews() {
        assertTrue(EarningsOutlook.headlineTone("Acme fails to beat estimates") < 0.0)
    }

    // ---------------------------------------------------------------- outlook

    @Test fun strongProfileIsGreenWeakIsRed() {
        val g = EarningsOutlook.evaluate(strong)
        val r = EarningsOutlook.evaluate(weak)
        assertTrue(g.known); assertEquals(Light.GREEN, g.light); assertTrue(g.score >= EarningsOutlook.GREEN_AT)
        assertTrue(r.known); assertEquals(Light.RED, r.light); assertTrue(r.score <= EarningsOutlook.RED_AT)
        assertEquals(1.0, g.coverage, 1e-9)
        assertTrue(g.reasons.isNotEmpty() && g.reasons.all { it.detail.isNotBlank() })
    }

    @Test fun mixedSignalsAreYellow() {
        val mixed = strong.copy(history = listOf(beat(4.0), beat(-5.0), beat(3.0), beat(-3.0)),
            headlines = listOf(news("Acme beats estimates"), news("Acme downgraded")),
            estimates = listOf(EarningsEstimate("0q", epsAvg = 1.02, epsYearAgo = 1.00)),
            consensus = Consensus(mean = 3.0, analysts = 20, targetMean = 101.0), trend = emptyList())
        val m = EarningsOutlook.evaluate(mixed)
        assertTrue(m.known); assertEquals("score ${m.score}", Light.YELLOW, m.light)
    }

    @Test fun tooLittleDataIsYellowAndSaysUnknown() {
        val none = EarningsOutlook.evaluate(EarningsOutlook.Input(now = now))
        assertFalse(none.known); assertEquals(Light.YELLOW, none.light)
        // One lonely signal is also not enough.
        val one = EarningsOutlook.evaluate(EarningsOutlook.Input(consensus = Consensus(mean = 1.2, analysts = 9), now = now))
        assertFalse(one.known); assertEquals(Light.YELLOW, one.light)
    }

    @Test fun aBigRunUpCountsAgainstNotFor() {
        val calm = EarningsOutlook.evaluate(strong.copy(price = 110.0, fiftyDayAverage = 100.0))   // +10%
        val parabolic = EarningsOutlook.evaluate(strong.copy(price = 140.0, fiftyDayAverage = 100.0)) // +40%
        assertTrue(parabolic.score < calm.score)
    }

    @Test fun oldHeadlinesDoNotCount() {
        val stale = strong.copy(headlines = listOf(news("Acme beats estimates", 40.0), news("Acme upgrade", 60.0)))
        val r = EarningsOutlook.evaluate(stale)
        assertTrue(r.reasons.none { it.label == "News tone" })
    }

    @Test fun reportedLightReflectsTheActualSurprise() {
        assertEquals(Light.GREEN, reportedLight(beat(5.0)))
        assertEquals(Light.RED, reportedLight(beat(-5.0)))
        assertEquals(Light.YELLOW, reportedLight(beat(0.4)))
    }

    // ---------------------------------------------------------------- dates

    @Test fun confirmedDateVersusEstimatedWindow() {
        // 2026-10-29 20:30 UTC = 4:30pm ET (after close)
        val t = 1793305800L
        val one = EarningsFeed.parse(org.json.JSONObject(
            """{"calendarEvents":{"earnings":{"earningsDate":[{"raw":$t}],"isEarningsDateEstimate":false}}}"""))!!
        assertFalse(one.estimated)
        assertEquals(LocalDate.of(2026, 10, 29), one.day)
        assertEquals("After close", one.timing)
        val win = EarningsFeed.parse(org.json.JSONObject(
            """{"calendarEvents":{"earnings":{"earningsDate":[{"raw":$t},{"raw":${t + 5 * 86400}}]}}}"""))!!
        assertTrue(win.estimated); assertEquals("", win.timing)
        assertEquals(LocalDate.of(2026, 11, 3), win.endDay)
        assertNull(EarningsFeed.parse(org.json.JSONObject("""{"calendarEvents":{"earnings":{"earningsDate":[]}}}""")))
    }

    @Test fun dateOnlyStampKeepsItsUtcDay() {
        // midnight UTC Oct 30 must stay Oct 30, not slip to the evening of Oct 29 in New York
        val ms = LocalDate.of(2026, 10, 30).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 30), EarningsFeed.dayOf(ms))
        assertEquals("", EarningsFeed.timingOf(ms))
    }

    @Test fun dateCacheRoundTrips() {
        val d = EarningsFeed.Date(5L, 9L, true, 7L)
        assertEquals(d, EarningsFeed.decode(EarningsFeed.encode(d)))
        assertNull(EarningsFeed.decode("junk"))
    }

    // ---------------------------------------------------------------- board

    private fun row(sym: String, type: String, shares: Double = 10.0, price: Double = 100.0) =
        Row(sym, "$sym Inc", Position(sym, shares, shares * 50, 0.0, 0L), Quote(sym, price = price, quoteType = type), false)

    private fun fund(vararg parts: Pair<String, Double>) = Fundamentals(symbol = "X", earningsDate = 0L)

    @Test fun boardOrdersByDateMergesFundExposureAndSortsOutTheRest() {
        val today = LocalDate.of(2026, 10, 22)
        fun ms(d: LocalDate) = d.atTime(20, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val state = UiState(rows = listOf(row("NVDA", "EQUITY"), row("AAPL", "EQUITY"), row("KO", "EQUITY"), row("QQQ", "ETF")))
        val holdings = mapOf("QQQ" to FundHoldings("QQQ", isFund = true, quoteType = "ETF", holdings = listOf(
            FundHolding("NVDA", "Nvidia", 0.09), FundHolding("MSFT", "Microsoft", 0.08), FundHolding("TINY", "Tiny", 0.005))))
        val dates = mapOf(
            "NVDA" to EarningsFeed.Date(ms(today.plusDays(9))),
            "MSFT" to EarningsFeed.Date(ms(today.plusDays(3))),
            "AAPL" to EarningsFeed.Date(ms(today.minusDays(4))),     // just reported
            "KO" to EarningsFeed.Date(ms(today.minusDays(60)))       // stale: next one not announced
        )
        val fundamentals = mapOf("AAPL" to Fundamentals("AAPL", history = listOf(
            EarningsResult("q", ms(today.minusDays(4)), 1.0, 1.1, 10.0))))
        val b = buildEarningsBoard(state, fundamentals, dates, emptyMap(), holdings, today, now)

        // Rows: owned stocks and the fund itself - never a holding you only own through the fund.
        assertEquals(listOf("QQQ", "NVDA"), b.upcoming.map { it.symbol })            // QQQ's soonest holding is MSFT (+3d)
        val q = b.upcoming.first()
        assertTrue(q.isFund)
        assertEquals(today.plusDays(3), q.day)
        assertEquals(listOf("MSFT", "NVDA"), q.children.map { it.first.symbol })
        assertTrue(b.upcoming.none { it.symbol == "MSFT" })
        assertTrue(b.upcoming[1].owned && !b.upcoming[1].isFund)
        assertEquals(listOf("AAPL"), b.recent.map { it.symbol })
        assertNotNull(b.recent.single().reported)
        assertEquals(Light.GREEN, reportedLight(b.recent.single().reported!!))
        assertEquals(listOf("KO"), b.undated.map { it.symbol })
        assertTrue(b.upcoming.none { it.symbol == "TINY" } && q.children.none { it.first.symbol == "TINY" })   // under 2%: ignored
    }

    @Test fun realYahooEarningsFixtureParses() {
        val res = org.json.JSONObject(
            javaClass.classLoader!!.getResourceAsStream("yahoo_AAPL.json")!!.bufferedReader().use { it.readText() }
        ).getJSONObject("quoteSummary").getJSONArray("result").getJSONObject(0)
        val d = EarningsFeed.parse(res)!!
        assertEquals(LocalDate.of(2026, 10, 29), d.day)
        assertFalse(d.estimated)
        assertEquals("After close", d.timing)
    }
}
