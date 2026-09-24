package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals
import com.tj.portfolio.net.MarketClock
import com.tj.portfolio.ui.PortfolioViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Regression tests for the independent review of the 2026-09-24 full test's own fixes
 * (audits/2026-09-24/review-hm.md = R1-*, review-lq.md = R2-*). One section per finding ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Review0924Test {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    private fun field(vm: PortfolioViewModel, name: String) =
        PortfolioViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun ny(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = java.time.ZonedDateTime.of(
        y, mo, d, h, mi, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    /** A daily-only reading: the intraday request failed, the memoised daily leg did not. */
    private fun dailyOnly() = DayTechnicals(atr14 = 2.0, adr = 2.5, prevHigh = 51.0, prevLow = 47.0,
        prevClose = 49.0, sessionLive = true, intradayFetched = false)

    /** A full live reading for [day] at 11:00: opening range and the 09:30 bar both closed. */
    private fun live(day: String) = DayTechnicals(atr14 = 2.0, adr = 2.5, prevHigh = 51.0,
        prevLow = 47.0, prevClose = 49.0, vwap = 49.4, openingRangeHigh = 50.2,
        openingRangeLow = 48.1, openingRangeComplete = true, or5High = 49.6, or5Low = 48.6,
        openingBarBullish = true, atrIntraday = 0.35, sessionHigh = 50.4, sessionLow = 48.0,
        sessionLive = true, intradayFetched = true, sessionDay = day, lastPrice = 49.9)

    // ---- R1-1: only rows re-planned from today's bars are logged.

    @Test fun `R1-1 a daily-only reading on a failed first tick is not a live re-plan`() {
        val today = MarketClock.dayKey(System.currentTimeMillis())
        val daily = dailyOnly()
        assertFalse("the old test let this through", daily.isEmpty)
        val picked = com.tj.portfolio.ui.replannedLive(mapOf(
            "XYZ" to daily, "LIVE" to live(today), "GONE" to null,
            "SHUT" to live(today).copy(sessionLive = false)))
        assertEquals(setOf("LIVE"), picked)
    }

    // ---- R1-2 (+ R1-2b): the opening levels travel with their flag through a failed tick.

    @Test fun `R1-2 a failed intraday tick keeps the complete opening range and the opening bar`() {
        val now = System.currentTimeMillis()
        val today = MarketClock.dayKey(now)
        val row = ResearchRow(symbol = "XYZ", price = 49.9, atr = 2.0, adr = 2.5, prevHigh = 51.0,
            vwap = 49.4, openingRangeHigh = 50.2, openingRangeLow = 48.1, openingRangeComplete = true,
            or5High = 49.6, or5Low = 48.6, openingBarBullish = true, atrIntraday = 0.35,
            sessionHigh = 50.4, sessionLow = 48.0, sessionDay = today)
        val eff = com.tj.portfolio.ui.effectiveTechnicals(row, dailyOnly(), now)
        assertTrue("carried without its flag the plan ignored the range", eff.openingRangeComplete)
        assertEquals(48.1, eff.openingRangeLow, 1e-9)
        assertEquals(49.6, eff.or5High, 1e-9); assertEquals(48.6, eff.or5Low, 1e-9)
        assertTrue(eff.openingBarBullish)
        // A fetched reading that says "not complete yet" is the truth, whatever the row had.
        val early = live(today).copy(openingRangeComplete = false)
        assertFalse(com.tj.portfolio.ui.effectiveTechnicals(row, early, now).openingRangeComplete)
        // Another session's range is carried neither with nor without its flag.
        val stale = row.copy(sessionDay = MarketClock.dayKey(now - 5 * 86_400_000L))
        val notCarried = com.tj.portfolio.ui.effectiveTechnicals(stale, dailyOnly(), now)
        assertFalse(notCarried.openingRangeComplete)
        assertEquals(0.0, notCarried.or5High, 1e-9)
        // And all of it survives the disk cache.
        val back = ResearchRow.fromJson(row.toJson())!!
        assertTrue(back.openingRangeComplete); assertTrue(back.openingBarBullish)
        assertEquals(49.6, back.or5High, 1e-9); assertEquals(48.6, back.or5Low, 1e-9)
    }

    @Test fun `R1-2 the plan does not change on a tick whose intraday request failed`() {
        val today = MarketClock.dayKey(System.currentTimeMillis())
        val start = ResearchRow(symbol = "XYZ", price = 49.9, sessionDay = today)
        val good = com.tj.portfolio.ui.mergeDayTradingTech(start, live(today), minutesLeft = 300)
        assertTrue("the fixture must produce a plan", good.entryPrice > 0.0)
        val failed = com.tj.portfolio.ui.mergeDayTradingTech(good, dailyOnly(), minutesLeft = 300)
        assertEquals(good.entryPrice, failed.entryPrice, 1e-9)
        assertEquals(good.stopPrice, failed.stopPrice, 1e-9)
        assertEquals(good.targetPrice, failed.targetPrice, 1e-9)
        assertEquals(good.trigger, failed.trigger)
    }

    // ---- R1-3: Claude's own earnings words are kept when the app has none.

    @Test fun `R1-3 a merge keeps Claude's earnings line whole when the app row has no phrase`() {
        val R = com.tj.portfolio.net.Research
        val line = "Earnings today after the close could gap it"
        assertEquals(line, R.combineCatalyst("", line))
        assertEquals("Earnings tomorrow", R.combineCatalyst("", "Earnings tomorrow"))
        // The carry path still drops a frozen app phrase - that is what it is for (S-5).
        assertEquals("Guidance risk",
            R.combineCatalyst("", "Earnings in 6 days - Sep 27 - Guidance risk", fromCarry = true))
        // Day Trading: the merged row still carries the warning `mergeDayTradingTech` reads.
        val app = ResearchRow(symbol = "HOT", price = 20.0)
        val merged = com.tj.portfolio.net.DayTradingBridge.merge(listOf(app),
            listOf(ResearchRow(symbol = "HOT", why = "In play.", catalyst = line))).single()
        assertTrue(merged.catalyst, merged.catalyst.startsWith(R.CATALYST_EARNINGS_TODAY))
        // Research: Claude's catalyst is not deleted.
        val research = com.tj.portfolio.net.ResearchBridge.merge(listOf(ResearchRow(symbol = "ABC")),
            listOf(ResearchRow(symbol = "ABC", catalyst = "Earnings tomorrow"))).single()
        assertEquals("Earnings tomorrow", research.catalyst)
    }

    // ---- R1-4 + R2-2: an imported answer is fresh, and the rows it added are on the page.

    @Test fun `R1-4 R2-2 an earlier-dated answer is not stale on import and its additions are shown`() {
        val vm = PortfolioViewModel(app).also { settle() }
        val now = System.currentTimeMillis()
        val rows = (1..15).map { ResearchRow(symbol = "T$it", score = 80 - it, price = 10.0,
            reasons = listOf("r")) }
        @Suppress("UNCHECKED_CAST")
        val research = field(vm, "_research").get(vm) as MutableStateFlow<ResearchSet>
        research.value = ResearchSet(trending = rows, generated = now - 2 * 3_600_000L)
        assertTrue("fixture: a two-hour-old list is stale", vm.researchStale())

        val yesterday = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))
            .minusDays(1).toString()
        val reply = """{"portfolioAppResponse": 1, "research": {"asOf": "$yesterday", "trending": [
            {"symbol": "T1", "why": "Still the leader.", "conviction": 6},
            {"symbol": "NEWA", "why": "Claude's first pick.", "conviction": 7},
            {"symbol": "NEWB", "why": "Claude's second pick.", "conviction": 7}]}}"""
        val msg = vm.importResearchFile(reply)
        assertTrue(msg, msg.contains("2 added"))
        assertFalse("R1-4: the answer just imported read as stale", vm.researchStale())
        // The paragraphs still age from the answer's own date (S-4).
        assertTrue(vm.research.value.trending.first { it.symbol == "T1" }.whyAt < now - 3_600_000L)

        val shown = vm.researchShown.value[ResearchSet.SECTION_TRENDING] ?: ResearchSet.PAGE
        assertEquals("R2-2: the page grows by what was added", ResearchSet.PAGE + 2, shown)
        val visible = vm.research.value.trending.take(shown).map { it.symbol }
        assertTrue(visible.toString(), visible.containsAll(listOf("NEWA", "NEWB")))
    }

    // ---- R1-6: a stated maturity band keeps a junk fund out of HYG's group.

    @Test fun `R1-6 SHYG is not HYG, and an unknown stated band is left ungrouped`() {
        val k = com.tj.portfolio.net.EtfExposure::keyOf
        val hyg = k("iShares iBoxx \$ High Yield Corporate Bond ETF")
        assertEquals("Bonds - high yield", hyg)
        val shyg = k("iShares 0-5 Year High Yield Corporate Bond ETF")
        assertNotEquals(hyg, shyg)
        assertEquals("SHYG and SJNK are the same exposure",
            shyg, k("SPDR Bloomberg 0-5 Year High Yield Bond ETF"))
        assertNull("a band the table does not know is not the unbanded group",
            k("Xtrackers 2-9 Year High Yield Corporate Bond ETF"))
        assertNotEquals(k("iShares National Muni Bond ETF"), k("Some 0-5 Year Municipal Bond ETF"))
    }

    // ---- R1-7: a levels-only evening plan keeps its own clock.

    @Test fun `R1-7 a levels-only Claude plan imported in the evening survives Tuesday's 04 00 tick`() {
        val imported = ny(2026, 9, 28, 18)                     // Monday evening
        val app = ResearchRow(symbol = "GME", price = 22.5, score = 70)
        val claude = ResearchRow(symbol = "GME", catalyst = "Gap risk", entryPrice = 23.0,
            stopPrice = 21.5, targetPrice = 26.0, setup = "Claude breakout", planByClaude = true)
        val merged = com.tj.portfolio.net.DayTradingBridge.merge(listOf(app), listOf(claude), imported)
            .single()
        assertTrue(merged.planByClaude)
        assertEquals("no paragraph - its clock does not move", 0L, merged.whyAt)
        assertEquals(imported, merged.planAt)

        // The post-close sweep stamps Monday on it; Tuesday 04:05 is a new session.
        val stamped = merged.copy(sessionDay = MarketClock.dayKey(imported))
        val tuesday = ny(2026, 9, 29, 4, 5)
        val tech = DayTechnicals(atr14 = 2.5, adr = 1.1, prevHigh = 23.0, intradayFetched = true,
            sessionDay = MarketClock.dayKey(tuesday), lastPrice = 22.6)
        val out = com.tj.portfolio.ui.mergeDayTradingTech(stamped, tech, minutesLeft = 390, now = tuesday)
        assertTrue("Claude's plan for Tuesday was replaced", out.planByClaude)
        assertEquals(23.0, out.entryPrice, 1e-9)
        assertEquals(imported, out.planAt)
        // The old reading (by `whyAt`) is exactly what used to drop it.
        assertFalse(com.tj.portfolio.ui.mergeDayTradingTech(stamped.copy(planAt = 0L), tech,
            minutesLeft = 390, now = tuesday).planByClaude)
        // Handed back to the engine, the stamp goes with the plan.
        assertEquals(0L, com.tj.portfolio.ui.mergeDayTradingTech(stamped.copy(planAt = ny(2026, 9, 28, 11)),
            tech, minutesLeft = 390, now = tuesday).planAt)
        // And it survives the disk cache.
        assertEquals(imported, ResearchRow.fromJson(merged.toJson())!!.planAt)
    }

    @Test fun `R1-7 a rebuild carries a levels-only Claude plan and a levels-only Claude pick`() {
        val now = ny(2026, 9, 29, 10)
        val planAt = ny(2026, 9, 29, 9, 40)
        val planned = ResearchRow(symbol = "GME", score = 70, entryPrice = 23.0, stopPrice = 21.5,
            targetPrice = 26.0, setup = "Claude breakout", planByClaude = true, planAt = planAt,
            planPrice = 22.5)
        val added = ResearchRow(symbol = "NEWP", entryPrice = 5.0, stopPrice = 4.6, targetPrice = 5.9,
            planByClaude = true, planAt = planAt, catalyst = "Halt risk")
        val old = ResearchSet(dayTrading = listOf(planned, added), generated = now - 1_800_000L)
        val fresh = ResearchSet(dayTrading = listOf(ResearchRow(symbol = "GME", score = 68)),
            generated = now)
        val out = com.tj.portfolio.ui.carryExplanations(old, fresh, now).dayTrading
        val gme = out.first { it.symbol == "GME" }
        assertTrue(gme.planByClaude)
        assertEquals(23.0, gme.entryPrice, 1e-9); assertEquals(planAt, gme.planAt)
        assertTrue("the levels-only pick was dropped", out.any { it.symbol == "NEWP" })
        // Yesterday's session plan is not carried into today's.
        val stale = old.copy(dayTrading = listOf(planned.copy(planAt = ny(2026, 9, 28, 10))))
        assertFalse(com.tj.portfolio.ui.carryExplanations(stale, fresh, now).dayTrading.single().planByClaude)
    }

    // ---- R1-8: only a whole-list tick with today's bars is "live".

    @Test fun `R1-8 a one-symbol run or an all-failed tick does not refresh the list's clock`() {
        val today = MarketClock.dayKey(System.currentTimeMillis())
        fun f(only: String?, fetched: Map<String, DayTechnicals?>) =
            com.tj.portfolio.ui.tickRefreshedList(only, fetched)
        assertTrue(f(null, mapOf("A" to live(today), "B" to null)))
        assertFalse("a detail screen's single-symbol run", f("A", mapOf("A" to live(today))))
        assertFalse("every intraday request failed", f(null, mapOf("A" to dailyOnly(), "B" to null)))
    }

    // ---- R2-1: the sign and colour follow the most precise figure printed.

    @Test fun `R2-1 a sub-cent dollar move decides the sign of a percentage that rounds to zero`() {
        val F = com.tj.portfolio.util.Fmt
        assertEquals("-$0.02", F.changeMoney(500.0, -0.02))
        assertEquals("-0.00%", F.pctSignedBeside(-0.004, -0.02, 500.0))
        assertEquals("+0.00%", F.pctSignedBeside(0.004, 0.02, 500.0))
        assertEquals("+0.00%", F.pctSignedBeside(0.0, 0.0, 500.0))
        assertEquals("-0.71%", F.pctSignedBeside(-0.71, -0.003, 0.42))
        // The colour threshold is the dollar figure's own precision: a $0.003 fall on a $0.42
        // stock prints with its minus, so it must not snap to "up".
        assertTrue(F.snapZero(-0.003, F.changeEps(0.42)) < 0)
        assertTrue(F.snapZero(-0.0004, F.changeEps(500.0)) == 0.0)
    }

    // ---- R2-3: the log reads a Claude plan's direction from the price it was made at.

    @Test fun `R2-3 a Claude pullback logged below its entry is still logged as a pullback`() {
        val claude = ResearchRow(symbol = "HOT", price = 98.0, entryPrice = 100.0, stopPrice = 97.0,
            targetPrice = 108.0, setup = "Pullback", planByClaude = true, planPrice = 105.0)
        val logged = com.tj.portfolio.ui.loggedPlanPrice(claude)
        assertEquals(105.0, logged, 1e-9)
        assertFalse("scored as a buy-stop above", com.tj.portfolio.net.DayTradingEval.entryRises(
            claude.setup, claude.entryPrice, logged))
        // The app's own plan is logged at the price it was made at on the same tick.
        assertEquals(98.0, com.tj.portfolio.ui.loggedPlanPrice(claude.copy(planByClaude = false)), 1e-9)
        assertEquals(98.0, com.tj.portfolio.ui.loggedPlanPrice(claude.copy(planPrice = 0.0)), 1e-9)
    }

    // ---- R2-5: rows landing on an open review keep the ticks already chosen.

    private fun txn(sym: String, px: Double = 10.0) = Txn(type = TxnType.BUY, symbol = sym,
        quantity = 1.0, price = px, amount = -px, fees = 0.0, date = 1_758_000_000_000L,
        note = null, source = "MANUAL")

    @Test fun `R2-5 an open review's ticks survive rows being added after them`() {
        val shared = com.tj.portfolio.net.ExtractResult(listOf(txn("A"), txn("B")), "shared")
        val late = com.tj.portfolio.net.ExtractResult(listOf(txn("C")), "screens")
        val merged = com.tj.portfolio.ui.mergeIntoPendingReview(shared, late)
        val ticks = listOf(true, false)                       // Tj unticked B
        assertEquals(ticks, com.tj.portfolio.ui.carriedChecks(shared, ticks, merged))
        // Anything that is not "the same rows plus more" starts from the defaults.
        assertNull(com.tj.portfolio.ui.carriedChecks(shared, ticks, shared))
        assertNull(com.tj.portfolio.ui.carriedChecks(shared, ticks, late))
        assertNull(com.tj.portfolio.ui.carriedChecks(shared, ticks,
            com.tj.portfolio.net.ExtractResult(listOf(txn("B"), txn("A"), txn("C")), "other")))
    }

    // ---- R2-6: a row cached by an older build is not rescored on top of its old bonus.

    @Test fun `R2-6 a row with no recorded base is left exactly as it was`() {
        val legacy = ResearchRow(symbol = "GME", price = 22.5, dtLikelihood = 62, dtConfidence = 80,
            reasons = listOf("base reason", "Trading above its session VWAP (\$21.00) - buyers in control today"))
        val tech = DayTechnicals(atr14 = 1.0, vwap = 30.0, sessionDay = "2026-09-24", sessionLive = true)
        assertEquals(legacy, com.tj.portfolio.ui.scoreDayTradingRow(legacy, tech))
    }

    // ---- R2-7: a failed save says so AND keeps the editor open.

    @Test fun `R2-7 a transaction that fails to save returns false, one that saves returns true`() {
        val vm = PortfolioViewModel(app).also { settle() }
        val db = field(vm, "db").get(vm) as Db
        db.writableDatabase.execSQL("CREATE TRIGGER no_save BEFORE INSERT ON txns " +
            "BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        assertFalse(vm.addTxnRecord(txn("FAIL")))
        assertTrue(vm.toast.value.orEmpty(), vm.toast.value.orEmpty().startsWith("Couldn't save"))
        db.writableDatabase.execSQL("DROP TRIGGER no_save")
        assertTrue(vm.addTxnRecord(txn("OK")))
        settle()
    }
}
