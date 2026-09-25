package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.net.DayTradingEngine
import com.tj.portfolio.net.DayTradingEval
import com.tj.portfolio.net.DayTradingEval.IntradayBar
import com.tj.portfolio.net.DayTradingGrader
import com.tj.portfolio.net.DayTradingParams.Companion.DEFAULTS
import com.tj.portfolio.net.HttpResult
import com.tj.portfolio.net.MarketClock
import com.tj.portfolio.ui.DT_RETRY_EMPTY_MS
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.dayTradingRowsNeedingGrade
import com.tj.portfolio.ui.dayTradingRowsToResolve
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Which log rows are graded again, and what a re-grade may never destroy (audits/2026-09-24c
 * daytrading.md DA-1, DA-12, DA-19; platform.md PL-2, PL-6, PL-13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingRegradeTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        DayTradingEngine.install(DEFAULTS, 0)
    }

    @After fun tearDown() {
        com.tj.portfolio.net.Http.scriptedForTests = null
        app.deleteDatabase(Db.DB_NAME)
    }

    /** The most recent weekday at least two days back - settled, and inside the one-minute window. */
    private val day: String = run {
        val now = System.currentTimeMillis()
        (2..9).map { MarketClock.dayKey(now - it * 86_400_000L) }.first { d ->
            val ld = java.time.LocalDate.parse(d, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            ld.dayOfWeek != java.time.DayOfWeek.SATURDAY && ld.dayOfWeek != java.time.DayOfWeek.SUNDAY
        }
    }
    private val openMs get() = DayTradingEval.sessionBoundsMs(day)!!.first
    private fun m(minute: Int) = openMs / 1000L + 60L * minute

    private fun row(
        id: Long, outcome: String? = null, evalVersion: Int = DayTradingGrader.VERSION, detail: String = "",
        evaluatedAt: Long? = null, price: Double = 10.30, features: String = "{}", tradingDay: String = day
    ) = DayTradingLogEntry(id, "S$id", tradingDay, openMs + 30 * 60_000L + 30_000L, "Breakout", 10.50, 10.00, 11.50,
        price, DayTradingLogEntry.SOURCE_APP, outcome, if (outcome == DayTradingOutcome.WIN) 11.5 else null,
        evaluatedAt, "v0", features, evalVersion, detail)

    @Test fun `which rows are graded again`() {
        val now = System.currentTimeMillis()
        val settleAt = DayTradingEval.sessionBoundsMs(day)!!.second + DayTradingEval.SETTLE_GRACE_MS
        val partial = DayTradingGrader.Detail(res = 1, fill = 10.5, partial = true).toJson()
        val rows = listOf(
            row(1),                                                                          // never graded
            row(2, DayTradingOutcome.DATA_UNAVAILABLE, evaluatedAt = now - 3_600_000L),     // asked an hour ago
            row(3, DayTradingOutcome.DATA_UNAVAILABLE, evaluatedAt = now - DT_RETRY_EMPTY_MS - 1),
            row(4, DayTradingOutcome.WIN, evalVersion = 0),                                  // older grader
            row(5, DayTradingOutcome.WIN, detail = partial, evaluatedAt = settleAt - 3_600_000L), // decided mid-session
            row(6, DayTradingOutcome.WIN, detail = partial, evaluatedAt = settleAt + 1),     // already re-graded
            row(7, DayTradingOutcome.WIN, detail = DayTradingGrader.Detail(res = 1, fill = 10.5).toJson()),
            row(8, price = 11.80, features = ""),                                            // old row past its target
            row(9, DayTradingOutcome.WIN, evalVersion = 0)                                   // just came back empty
        )
        val picked = dayTradingRowsNeedingGrade(rows, now, recentlyEmpty = setOf(9L)).map { it.id }
        assertEquals(listOf(1L, 3L, 4L, 5L), picked)
        // Rows asked again because their bars were missing go after every other row, older or not.
        val older = row(10, DayTradingOutcome.DATA_UNAVAILABLE, evaluatedAt = 0L, tradingDay = "20260102")
        val order = dayTradingRowsToResolve(listOf(older, row(11))).map { it.id }
        assertEquals(listOf(11L, 10L), order)
        // A pre-rule row whose own price shows the card said skip it is never counted either.
        val s = DayTradingEval.stats(listOf(row(8, DayTradingOutcome.WIN, price = 11.80, features = "")))
        assertEquals(1, s.oldSkipped)
        assertEquals(0, s.entriesTriggered)
        assertEquals(0, s.totalRecommendations)
    }

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(100); ShadowLooper.idleMainLooper() }

    private fun chartBody(bars: List<IntradayBar>): String {
        fun col(f: (IntradayBar) -> Double) = bars.joinToString(",") { f(it).toString() }
        return """{"chart":{"result":[{"timestamp":[${bars.joinToString(",") { it.t.toString() }}],""" +
            """"indicators":{"quote":[{"open":[${col { it.open }}],"high":[${col { it.high }}],""" +
            """"low":[${col { it.low }}],"close":[${col { it.close }}]}]}}],"error":null}}"""
    }

    /** A day that fills the 10.50 buy-stop at 10:30 and hits the 11.50 target at 11:30. */
    private val winningDay: List<IntradayBar> = (0 until 390).map { i ->
        val px = when { i < 60 -> 10.30; i < 120 -> 10.70; else -> 11.40 }
        when (i) {
            60 -> IntradayBar(m(i), 10.75, 10.30, 10.70, 10.30)
            120 -> IntradayBar(m(i), 11.60, 10.70, 11.50, 10.70)
            else -> IntradayBar(m(i), px + 0.02, px - 0.02, px, px)
        }
    }

    private fun insert(db: Db, r: DayTradingLogEntry) = db.logDayTradingRecommendations(listOf(
        Db.PendingDayTradingLog(r.symbol, r.tradingDay, r.setup, r.entry, r.stop, r.target,
            r.priceAtRecommendation, r.source, r.recordedAt, r.engine, r.features)))

    private fun waitFor(vm: PortfolioViewModel, done: () -> Boolean) {
        repeat(60) { if (!done() || vm.dayTradingStatsLoading.value) settle() }
    }

    @Test fun `a re-grade that finds no bars never destroys an old verdict`() {
        val db = Db(app)
        insert(db, row(0).copy(symbol = "OLD"))
        insert(db, row(0).copy(symbol = "NEW"))
        val old = db.dayTradingLog().first { it.symbol == "OLD" }
        db.setDayTradingOutcome(old.id, DayTradingOutcome.WIN, 11.5, 0, "")   // graded by an older grader
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        com.tj.portfolio.net.Http.scriptedForTests = { url ->
            if ("/v8/finance/chart/" in url && "period1" in url) { seen.add(url); HttpResult(404, "not found") }
            else HttpResult(-1, "offline")
        }
        val vm = PortfolioViewModel(app)
        settle()
        vm.evaluateDayTradingLog()
        waitFor(vm) { db.dayTradingLog().first { it.symbol == "NEW" }.outcome != null }
        val after = db.dayTradingLog().associateBy { it.symbol }
        assertEquals("the old WIN stays", DayTradingOutcome.WIN, after.getValue("OLD").outcome)
        assertEquals(11.5, after.getValue("OLD").outcomeExitPrice!!, 1e-9)
        assertEquals("never decided: now says so", DayTradingOutcome.DATA_UNAVAILABLE, after.getValue("NEW").outcome)
        // 1m then 5m for each, and a second press does not ask again for either
        val first = seen.size
        vm.evaluateDayTradingLog()
        repeat(10) { settle() }
        assertEquals("nothing asked again straight away", first, seen.size)
    }

    @Test fun `a settled day's bars are kept, and a later re-grade reads them instead of the network`() {
        val db = Db(app)
        insert(db, row(0).copy(symbol = "BARS"))
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        var answer: (String) -> HttpResult = { HttpResult(200, chartBody(winningDay)) }
        com.tj.portfolio.net.Http.scriptedForTests = { url ->
            if ("/v8/finance/chart/" in url && "period1" in url) { seen.add(url); answer(url) }
            else HttpResult(-1, "offline")
        }
        val vm = PortfolioViewModel(app)
        settle()
        vm.evaluateDayTradingLog()
        waitFor(vm) { db.dayTradingLog().single().outcome != null }
        val graded = db.dayTradingLog().single()
        assertEquals(DayTradingOutcome.WIN, graded.outcome)
        assertTrue("one-minute bars", seen.single().contains("interval=1m"))
        val d = DayTradingGrader.Detail.parse(graded.evalDetail)!!
        assertFalse(d.partial)
        assertEquals(DayTradingGrader.GRID_STOPS.size, d.grid.size)
        assertNotNull("kept on the phone", db.cachedDayBars("BARS", day, 1))
        // A future grader: the row is stale again, and Yahoo is down - graded from the phone.
        db.setDayTradingOutcome(graded.id, DayTradingOutcome.WIN, 11.5, 0, "")
        answer = { HttpResult(500, "down") }
        vm.evaluateDayTradingLog()
        waitFor(vm) { db.dayTradingLog().single().evalVersion == DayTradingGrader.VERSION }
        assertEquals(DayTradingGrader.VERSION, db.dayTradingLog().single().evalVersion)
        assertEquals("no second download", 1, seen.size)
        // Purged once past any re-grade window.
        db.purgeDayBars(MarketClock.dayKey(System.currentTimeMillis() + 86_400_000L))
        assertEquals(null, db.cachedDayBars("BARS", day, 1))
    }

    // ---------------------------------------------------------------- audit round 2

    /** A weekday about five weeks back: past Yahoo's one-minute window, inside the five-minute one. */
    private val oldDay: String = run {
        val now = System.currentTimeMillis()
        (35..42).map { MarketClock.dayKey(now - it * 86_400_000L) }.first { d ->
            val ld = java.time.LocalDate.parse(d, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            ld.dayOfWeek != java.time.DayOfWeek.SATURDAY && ld.dayOfWeek != java.time.DayOfWeek.SUNDAY
        }
    }

    @Test fun `R2G-1 a verdict decided on one-minute bars is never re-decided on five-minute ones`() {
        val db = Db(app)
        val open = DayTradingEval.sessionBoundsMs(oldDay)!!.first
        insert(db, row(0).copy(symbol = "ONE", tradingDay = oldDay, recordedAt = open + 30 * 60_000L + 30_000L))
        val id = db.dayTradingLog().single().id
        val partial = DayTradingGrader.Detail(res = 1, fill = 10.5, why = "stop", partial = true).toJson()
        db.setDayTradingOutcome(id, DayTradingOutcome.LOSS, 10.0, DayTradingGrader.VERSION, partial)
        // written at the time - i.e. before the settle
        db.writableDatabase.execSQL("UPDATE day_trading_log SET outcome_evaluated_at=? WHERE id=?", arrayOf<Any>(open + 3_600_000L, id))
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        com.tj.portfolio.net.Http.scriptedForTests = { url ->
            if ("/v8/finance/chart/" in url && "period1" in url) { seen.add(url); HttpResult(200, chartBody(winningDay)) }
            else HttpResult(-1, "offline")
        }
        val vm = PortfolioViewModel(app)
        settle()
        assertTrue("queued for its settled re-grade", dayTradingRowsNeedingGrade(db.dayTradingLog()).isNotEmpty())
        vm.evaluateDayTradingLog()
        waitFor(vm) { (db.dayTradingLog().single().outcomeEvaluatedAt ?: 0L) > open + 3_600_000L }
        val after = db.dayTradingLog().single()
        assertEquals("the LOSS stands", DayTradingOutcome.LOSS, after.outcome)
        assertEquals(partial, after.evalDetail)
        assertTrue("no five-minute download for it", seen.isEmpty())
        assertTrue("and it is not asked again", dayTradingRowsNeedingGrade(db.dayTradingLog()).isEmpty())
    }

    @Test fun `R2G-3 a reply that stops short is not kept as the day's bars`() {
        val db = Db(app)
        insert(db, row(0).copy(symbol = "CUT"))
        val cut = winningDay.filter { it.t < m(250) }   // ends 13:40
        com.tj.portfolio.net.Http.scriptedForTests = { url ->
            if ("/v8/finance/chart/" in url && "period1" in url) HttpResult(200, chartBody(cut)) else HttpResult(-1, "offline")
        }
        val vm = PortfolioViewModel(app)
        settle()
        vm.evaluateDayTradingLog()
        waitFor(vm) { db.dayTradingLog().single().outcome != null }
        val r = db.dayTradingLog().single()
        assertEquals("the target inside the data still decides it", DayTradingOutcome.WIN, r.outcome)
        assertTrue(DayTradingGrader.Detail.isPartial(r.evalDetail))
        assertEquals("not frozen on the phone", null, db.cachedDayBars("CUT", day, 1))
    }

    @Test fun `R2G-7 a decided row whose re-grade came back empty waits, across restarts`() {
        val db = Db(app)
        insert(db, row(0).copy(symbol = "GONE"))
        val id = db.dayTradingLog().single().id
        db.setDayTradingOutcome(id, DayTradingOutcome.WIN, 11.5, 0, "")
        com.tj.portfolio.net.Http.scriptedForTests = { url ->
            if ("/v8/finance/chart/" in url && "period1" in url) HttpResult(404, "gone") else HttpResult(-1, "offline")
        }
        val vm = PortfolioViewModel(app)
        settle()
        vm.evaluateDayTradingLog()
        waitFor(vm) { db.dayTradingLog().single().retryAt > 0L }
        val r = db.dayTradingLog().single()
        assertEquals(DayTradingOutcome.WIN, r.outcome)
        assertTrue("stored, so a restarted app waits too", r.retryAt > System.currentTimeMillis() + DT_RETRY_EMPTY_MS / 2)
        assertTrue(dayTradingRowsNeedingGrade(db.dayTradingLog()).isEmpty())
    }
}
