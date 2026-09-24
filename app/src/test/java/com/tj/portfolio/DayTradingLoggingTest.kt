package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.net.DayTradingEngine
import com.tj.portfolio.net.DayTradingFeatures
import com.tj.portfolio.net.DayTradingParams
import com.tj.portfolio.net.DayTradingParams.Companion.DEFAULTS
import com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals
import com.tj.portfolio.net.EngineTuning
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.ShareDest
import com.tj.portfolio.ui.beforeOwnCutoff
import com.tj.portfolio.ui.cutoffParams
import com.tj.portfolio.ui.mergeDayTradingTech
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * What gets RECORDED as a recommendation, and the engine store behind it (audits/2026-09-24c
 * daytrading.md DA-2, DA-10, DA-13, DA-20, DA-21; platform.md PL-4, PL-9, PL-10, PL-14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingLoggingTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        java.io.File(app.filesDir, "daytrading-engine").deleteRecursively()
        DayTradingEngine.install(DEFAULTS, 0)
    }

    @After fun tearDown() {
        app.deleteDatabase(Db.DB_NAME)
        java.io.File(app.filesDir, "daytrading-engine").deleteRecursively()
        DayTradingEngine.install(DEFAULTS, 0)
    }

    private val day = "2026-09-15"
    /** Live, price 20 under VWAP 20.4 - a VWAP reclaim, with nothing that confirms (confidence 0). */
    private fun live(or5High: Double = 20.2, lastPrice: Double = 20.0) = DayTechnicals(
        atr14 = 1.0, vwap = 20.4, atrIntraday = 0.2, adr = 1.2, prevHigh = 21.0, prevLow = 19.0, prevClose = 19.8,
        sessionHigh = 20.6, sessionLow = 19.7, sessionLive = true, or5High = or5High, or5Low = 19.8,
        openingBarBullish = true, sessionDay = day, lastPrice = lastPrice
    )

    @Test fun `DA-2 the minimum score judges this tick's score, and a score of 0 is not "unscored"`() {
        val scored = ResearchRow(symbol = "LOW", price = 20.0, score = 0, dtLikelihood = 45, dtConfidence = 0,
            dtBaseLikelihood = 45, dtBaseConfidence = 0, dtBaseReasonCount = 0, sessionDay = day)
        assertTrue("the original engine plans it", mergeDayTradingTech(scored, live(), minutesLeft = 300).entryPrice > 0.0)
        DayTradingEngine.install(DEFAULTS.with(mapOf(DayTradingParams.MIN_SCORE to 20.0)), 1)
        assertEquals("a real score of 0 is below 20", 0.0, mergeDayTradingTech(scored, live(), minutesLeft = 300).entryPrice, 0.0)
        // A pick the app never scored (Claude added it) is not filtered on a score it does not have.
        val unscored = ResearchRow(symbol = "NEW", price = 20.0, sessionDay = day)
        assertTrue(mergeDayTradingTech(unscored, live(), minutesLeft = 300).entryPrice > 0.0)
    }

    @Test fun `DA-20 with the opening-bar filter on, a plan waits for the first bar to close`() {
        val p = DEFAULTS.with(mapOf(DayTradingParams.REQUIRE_BULLISH_BAR to 1.0))
        val early = ResearchScore.planInternal(20.0, live(or5High = 0.0), minutesLeft = 388, minutesSinceOpen = 2, p = p).first!!
        assertTrue(early.waitReason, early.waitReason.contains("first 5-minute bar"))
        val after = ResearchScore.planInternal(20.0, live(), minutesLeft = 380, minutesSinceOpen = 10, p = p).first!!
        assertEquals("", after.waitReason)
        assertEquals("the original engine never waits", "",
            ResearchScore.planInternal(20.0, live(or5High = 0.0), minutesLeft = 388, p = DEFAULTS).first!!.waitReason)
    }

    @Test fun `DA-21 a Claude plan with no price takes the first live price the app sees`() {
        val claude = ResearchRow(symbol = "CLD", price = 0.0, entryPrice = 20.5, stopPrice = 19.8, targetPrice = 22.0,
            setup = "Breakout", planByClaude = true, planPrice = 0.0, sessionDay = day)
        val out = mergeDayTradingTech(claude, live(lastPrice = 20.1), minutesLeft = 300)
        assertTrue(out.planByClaude)
        assertEquals(20.1, out.planPrice, 1e-9)
        // and keeps it after that
        assertEquals(20.1, mergeDayTradingTech(out, live(lastPrice = 20.6), minutesLeft = 299).planPrice, 1e-9)
    }

    @Test fun `DA-10 and DA-13 a plan's cut-off is the one its card shows, and the last minute is not recorded`() {
        val lull = DEFAULTS.with(mapOf(DayTradingParams.AVOID_LULL to 1.0))
        val et = java.time.ZoneId.of("America/New_York")
        fun at(h: Int, m: Int, s: Int = 0) = java.time.ZonedDateTime.of(2026, 9, 15, h, m, s, 0, et).toInstant().toEpochMilli()
        fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(et).let { it.hour * 100 + it.minute }
        val appRow = ResearchRow(symbol = "APP")
        val claudeRow = ResearchRow(symbol = "CLD", planByClaude = true)
        assertEquals(1130, hm(DayTradingFeatures.entryDeadlineMs("20260915", at(10, 45), cutoffParams(appRow, lull))!!))
        assertEquals("the Claude card has no lull rule", 1530,
            hm(DayTradingFeatures.entryDeadlineMs("20260915", at(10, 45), cutoffParams(claudeRow, lull))!!))
        assertTrue(beforeOwnCutoff(appRow, "20260915", at(15, 28, 50), DEFAULTS))
        assertFalse("inside the last minute", beforeOwnCutoff(appRow, "20260915", at(15, 29, 10), DEFAULTS))
        assertFalse("after it", beforeOwnCutoff(appRow, "20260915", at(15, 30, 40), DEFAULTS))
        assertTrue("a Claude plan at 11:40 under a lull engine is still live", beforeOwnCutoff(claudeRow, "20260915", at(11, 40), lull))
    }

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(100); ShadowLooper.idleMainLooper() }

    @Test fun `PL-4 a lost settings table adopts the engine in the backup files, never overwrites them`() {
        val tuned = DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.8))
        val dir = java.io.File(app.filesDir, "daytrading-engine").apply { mkdirs() }
        java.io.File(dir, "current.json").writeText(JSONObject().put("version", 3).put("params", tuned.toFullJson()).toString(2))
        java.io.File(dir, "history.json").writeText(JSONArray().put(
            EngineTuning.HistoryEntry(3, 1L, EngineTuning.KIND_APPLY, emptyList(), paramsAfter = tuned).toJson()).toString(2))
        val vm = PortfolioViewModel(app)
        repeat(30) { if (vm.engine.value.version != 3) settle() }
        assertEquals(3, vm.engine.value.version)
        assertEquals(tuned, DayTradingEngine.params)
        assertTrue("stored in settings now", Db(app).get(Keys.DT_ENGINE).contains("1.8"))
        assertTrue(java.io.File(dir, "current.json").readText().contains("1.8"))
    }

    @Test fun `PL-9 an engine behind the log's labels moves past them, and stays there`() {
        val db = Db(app)
        db.logDayTradingRecommendations(listOf(Db.PendingDayTradingLog("OLD", "20260915", "Breakout", 10.5, 10.0, 11.5,
            10.3, "APP", 1L, engine = "v5", features = "{}")))
        db.set(Keys.DT_ENGINE, EngineTuning.State(DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.7)), 2).engineJson())
        val vm = PortfolioViewModel(app)
        settle()
        assertEquals(6, vm.engine.value.version)
        assertTrue(Db(app).get(Keys.DT_ENGINE).contains("\"version\":6"))
    }

    @Test fun `PL-10 and PL-15 a revert and its undo are two changes, in order`() {
        val tuned = DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.8))
        Db(app).set(Keys.DT_ENGINE, EngineTuning.State(tuned, 1).engineJson())
        Db(app).set(Keys.DT_ENGINE_HISTORY, JSONArray().put(
            EngineTuning.HistoryEntry(1, 1L, EngineTuning.KIND_APPLY, emptyList(), paramsAfter = tuned).toJson()).toString())
        val vm = PortfolioViewModel(app)
        settle()
        vm.revertEngine()
        vm.undoEngineChange()
        repeat(40) { if (vm.engine.value.history.size < 3) settle() }
        val h = vm.engine.value.history
        assertEquals(listOf(1, 2, 3), h.map { it.version })
        assertEquals(listOf(EngineTuning.KIND_APPLY, EngineTuning.KIND_REVERT, EngineTuning.KIND_UNDO), h.map { it.kind })
        assertEquals("the revert was undone: tuned again", tuned, DayTradingEngine.params)
    }

    @Test fun `PL-14 a tuning answer picked from the Advice tab opens the Day Trading tab`() {
        val vm = PortfolioViewModel(app)
        settle()
        val answer = JSONObject().put("portfolioAppResponse", 1).put("dayTradingEngine", JSONObject()
            .put("basedOn", JSONObject().put("engineVersion", 0).put("gradedTrades", 0))
            .put("verdict", "Too early.").put("changes", JSONArray()))
        val msg = vm.importClaudeFile("```json\n${answer.toString(2)}\n```\n${ClaudeBridge.SHARE_BACK_LINE}")
        assertTrue(msg, msg.contains("Checking"))
        assertEquals(ShareDest.DAY_TRADING, vm.shareNav.value)
        repeat(20) { if (vm.engineReview.value == null) settle() }
        assertTrue(vm.engineReview.value != null)
    }

    @Test fun `DA-14 an opening plan sized on daily ATR still carries its ATR features, and the prompt says why`() {
        val early = ResearchRow(symbol = "EARLY", price = 20.0, entryPrice = 20.5, stopPrice = 20.0, targetPrice = 21.5,
            atr = 1.0, atrIntraday = 0.0, vwap = 20.2, setup = "Breakout")
        val f = DayTradingFeatures.build(early, "20260915", 1L, 0L, false, 5, 385, false, DEFAULTS, 0)
        assertEquals(0.1, f.getDouble("atr"), 1e-9)                 // daily 1.0 x 0.10
        assertEquals("daily", f.getString("volSrc"))
        assertEquals(5.0, f.getDouble("riskAtr"), 1e-9)
        val later = DayTradingFeatures.build(early.copy(atrIntraday = 0.25), "20260915", 1L, 0L, false, 60, 330, false, DEFAULTS, 0)
        assertEquals("intraday", later.getString("volSrc"))
        val text = com.tj.portfolio.net.EngineTuningPrompt.algorithm(DEFAULTS)
        assertTrue(text.contains("top 15% of its 52-week range"))
        assertTrue(text.contains("six"))
        assertTrue(text.contains("$50M+ market cap"))
        assertFalse(text.contains("within 15% of the 52-week high"))
    }

    /** PL-3: the tab's own check stops at the first request Yahoo does not answer, and asks two at a time. */
    @Test fun `PL-3 the automatic check stops at the first unanswered request`() {
        val db = Db(app)
        val now = System.currentTimeMillis()
        val days = (2..9).map { com.tj.portfolio.net.MarketClock.dayKey(now - it * 86_400_000L) }.filter { d ->
            val ld = java.time.LocalDate.parse(d, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            ld.dayOfWeek != java.time.DayOfWeek.SATURDAY && ld.dayOfWeek != java.time.DayOfWeek.SUNDAY
        }
        val bounds = com.tj.portfolio.net.DayTradingEval.sessionBoundsMs(days.first())!!
        db.logDayTradingRecommendations((1..30).map {
            Db.PendingDayTradingLog("S$it", days.first(), "Breakout", 10.5, 10.0, 11.5, 10.3, "APP",
                bounds.first + 40 * 60_000L, engine = "v0", features = "{}")
        })
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        com.tj.portfolio.net.Http.scriptedForTests = { url ->
            if ("/v8/finance/chart/" in url && "period1" in url) { seen.add(url); com.tj.portfolio.net.HttpResult(500, "down") }
            else com.tj.portfolio.net.HttpResult(-1, "offline")
        }
        try {
            val vm = PortfolioViewModel(app)
            settle()
            if (!com.tj.portfolio.util.Connectivity.isOnline(app)) return   // the auto check never runs offline
            vm.evaluateDayTradingLog(auto = true)
            repeat(80) { if (!vm.dayTradingStatsLoading.value && seen.isEmpty()) settle() else if (vm.dayTradingStatsLoading.value) settle() }
            repeat(5) { settle() }
            // two rows at once, each asked of both Yahoo hosts once - then it stops, 28 rows untouched
            assertTrue("sent ${seen.size}", seen.size in 1..4)
            assertEquals(30, db.dayTradingLog().count { it.outcome == null })
        } finally {
            com.tj.portfolio.net.Http.scriptedForTests = null
        }
    }
}
