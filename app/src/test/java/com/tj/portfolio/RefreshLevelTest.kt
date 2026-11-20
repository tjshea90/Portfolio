package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.net.Http
import com.tj.portfolio.ui.OfflineSync
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.offlineStatusLine
import com.tj.portfolio.ui.refreshLevelName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * THE LIGHTEST REFRESH + THE ONE-STOCK REFRESH (Tj, 2026-11-20).
 *
 * "Make an option which is even lighter than the light refresh which only refreshes current prices
 * of my holdings and watch list and their charts. Also, if I'm currently looking at a stock page
 * and press refresh or pull down to refresh, automatically only refresh the data for that
 * particular stock and do not refresh anything else in the background."
 */
class RefreshLevelPureTest {

    @Test fun `a stored level wins, otherwise the old Light switch decides`() {
        val m = OfflineSync.Mode.values()
        assertEquals(OfflineSync.Mode.FULL, OfflineSync.modeFromSetting("", legacyLight = false))
        assertEquals("an update must not change anyone's choice",
            OfflineSync.Mode.LIGHT, OfflineSync.modeFromSetting("", legacyLight = true))
        for (mode in m) assertEquals(mode, OfflineSync.modeFromSetting(mode.name, legacyLight = true))
        assertEquals("the new level is authoritative over the old boolean",
            OfflineSync.Mode.PRICES, OfflineSync.modeFromSetting("PRICES", legacyLight = true))
        assertEquals("an unknown value falls back rather than crashing",
            OfflineSync.Mode.LIGHT, OfflineSync.modeFromSetting("TURBO", legacyLight = true))
    }

    @Test fun `every level has its own name and its own status wording`() {
        val names = OfflineSync.Mode.values().map { refreshLevelName(it) }
        assertEquals(names.size, names.toSet().size)
        val idle = OfflineSync.Mode.values().map { offlineStatusLine(OfflineSync.Progress(), it) }
        assertEquals(idle.size, idle.toSet().size)
        assertTrue(idle[OfflineSync.Mode.PRICES.ordinal].contains("prices and charts"))
        val running = OfflineSync.Progress(
            running = true, mode = OfflineSync.Mode.PRICES, phase = "Prices and charts", step = 1, steps = 2
        )
        assertEquals("Prices refresh: Prices and charts (1/2)", offlineStatusLine(running, OfflineSync.Mode.PRICES))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RefreshLevelSyncTest {

    private lateinit var ctx: android.content.Context
    private lateinit var db: Db

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
        db = Db(ctx)
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(Db.DB_NAME) }

    /** Records every request the sync makes of its host, in order. */
    private class RecHost : OfflineSync.Host {
        override val lowRam = false
        val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun online() = true
        override fun holdings() = listOf(OfflineSync.Sym("NVDA", "Nvidia"), OfflineSync.Sym("AAPL", "Apple"))
        override fun watchlist() = listOf(OfflineSync.Sym("TSLA", "Tesla"))
        override suspend fun quotes(): Boolean { calls += "quotes"; return true }
        override suspend fun chart(symbol: String, rangeKey: String) { calls += "chart:$symbol:$rangeKey" }
        override suspend fun news(symbol: String, name: String, deep: Boolean) { calls += "news:$symbol" }
        override suspend fun analysisPieces(symbol: String, name: String) { calls += "analysis:$symbol" }
        override suspend fun fundamentals(symbol: String) { calls += "fund:$symbol" }
        override suspend fun dividends(held: List<String>) { calls += "dividends" }
        override suspend fun earnings() { calls += "earnings" }
        override suspend fun research() { calls += "research" }
        override fun researchPicks(limit: Int) = listOf(OfflineSync.Sym("AMD", "AMD"))
        override suspend fun feed(includeInsider: Boolean) { calls += "feed:$includeInsider" }
        override suspend fun indices() { calls += "indices" }
    }

    private fun run(mode: OfflineSync.Mode): Pair<OfflineSync, List<String>> {
        val host = RecHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = OfflineSync(db, host, { scope })
        assertTrue(sync.start(mode))
        runBlocking { withTimeout(20_000) { sync.progress.first { !it.running && it.lastFinishedAt > 0 } } }
        return sync to host.calls.toList()
    }

    @Test fun `the prices level fetches holdings and watch list prices and charts and NOTHING else`() {
        val (sync, calls) = run(OfflineSync.Mode.PRICES)
        assertEquals("quotes", calls.first())
        val charts = calls.filter { it.startsWith("chart:") }.toSet()
        val expected = listOf("NVDA", "AAPL", "TSLA").flatMap { s -> listOf("D1", "M1", "Y1").map { "chart:$s:$it" } }.toSet()
        assertEquals("their charts, and only theirs - no market-index charts", expected, charts)
        assertEquals("quotes plus charts, not one other call",
            emptyList<String>(), calls.filterNot { it == "quotes" || it.startsWith("chart:") })
        assertEquals(0, db.offlinePageCount())
        val p = sync.progress.value
        assertEquals(OfflineSync.Mode.PRICES, p.lastMode)
        assertEquals(2, p.steps)
        assertTrue(p.lastSummary, p.lastSummary.startsWith("Prices refresh finished"))
    }

    @Test fun `the light level still does what it did, which is exactly what makes prices lighter`() {
        val (_, calls) = run(OfflineSync.Mode.LIGHT)
        assertTrue("indices" in calls)
        assertTrue("market-index charts", calls.any { it.startsWith("chart:^GSPC") })
        assertTrue("feed:false" in calls)
        assertTrue(calls.any { it.startsWith("news:NVDA") })
        assertTrue("light never touches dividends or research", calls.none { it == "dividends" || it == "research" })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RefreshLevelViewModelTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        Http.clearCooldowns()
    }

    @After fun tearDown() {
        Http.scriptedForTests = null
        app.deleteDatabase(Db.DB_NAME)
    }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    private fun vm() = PortfolioViewModel(app).also { settle() }

    private fun buy(vm: PortfolioViewModel, symbol: String) {
        vm.addTxnRecord(Txn(type = TxnType.BUY, symbol = symbol, quantity = 10.0, price = 100.0, date = System.currentTimeMillis()))
        settle()
    }

    /** Idle the main looper until [done], so work hopping between Main and IO can finish. */
    private fun waitFor(what: String, ms: Long = 30_000L, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper()
            if (done()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun quiet(vm: PortfolioViewModel) = waitFor("every loader to go quiet") {
        vm.stockRefreshing.value.isEmpty() && vm.newsLoading.value.isEmpty() && vm.fundLoading.value.isEmpty() &&
            vm.ratingsLoading.value.isEmpty() && vm.holdingsLoading.value.isEmpty() &&
            vm.chartLoading.value.isEmpty() && !vm.ui.value.loading && !vm.offline.progress.value.running
    }

    private fun record(): MutableList<String> {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        Http.scriptedForTests = { url -> seen.add(url); null }     // null = the ordinary offline answer
        return seen
    }

    private fun mentions(url: String, vararg symbols: String): Boolean {
        val u = url.uppercase().replace("%5E", "^")
        return symbols.any { u.contains(it.uppercase()) }
    }

    @Test fun `the chosen refresh level is remembered across a restart`() {
        val first = vm()
        assertEquals(OfflineSync.Mode.FULL, first.refreshMode.value)
        first.setRefreshMode(OfflineSync.Mode.PRICES)
        assertEquals(OfflineSync.Mode.PRICES, first.refreshMode.value)
        settle()
        assertEquals(OfflineSync.Mode.PRICES, vm().refreshMode.value)
    }

    @Test fun `someone who had Light switched on keeps Light after the update`() {
        Db(app).also { it.setB(Keys.LIGHT_REFRESH, true); it.close() }
        assertEquals(OfflineSync.Mode.LIGHT, vm().refreshMode.value)
    }

    @Test fun `a fetched quote updates only its own symbol and never the portfolio refresh time`() {
        val vm = vm()
        buy(vm, "AAPL"); buy(vm, "NVDA")
        val before = vm.ui.value.lastRefresh
        val aaplBefore = vm.quotes.value["AAPL"]
        val stamp = vm.applyFetchedQuotes(listOf(Quote(symbol = "NVDA", price = 123.45, prevClose = 120.0)))
        assertEquals(123.45, vm.quotes.value.getValue("NVDA").price, 0.0)
        assertEquals(stamp, vm.quotes.value.getValue("NVDA").updated)
        assertEquals("another stock's quote is untouched", aaplBefore, vm.quotes.value["AAPL"])
        assertEquals("`lastRefresh` means the whole portfolio was repriced", before, vm.ui.value.lastRefresh)
    }

    @Test fun `refreshing one stock asks about that stock only and starts no offline pass`() {
        val vm = vm()
        buy(vm, "AAPL"); buy(vm, "NVDA"); vm.addWatch("TSLA"); settle()
        quiet(vm)

        // CONTROL: a portfolio-wide pull does ask about the other symbols, so the recorder can
        // see it. The Prices level keeps the offline pass this starts small and quick.
        vm.setRefreshMode(OfflineSync.Mode.PRICES)
        val seen = record()
        vm.refresh(manual = true)
        quiet(vm)
        assertTrue("control: a portfolio pull touches the other tracked symbols",
            seen.any { mentions(it, "AAPL", "TSLA") })
        seen.clear()

        val lastRefresh = vm.ui.value.lastRefresh
        val passesBefore = vm.offline.progress.value.lastFinishedAt
        vm.refreshStock("NVDA", ChartRange.D1)
        assertEquals("the page's own spinner is up at once", setOf("NVDA"), vm.stockRefreshing.value)
        quiet(vm)

        assertTrue("something was fetched for NVDA", seen.any { mentions(it, "NVDA") })
        assertEquals("no other holding, watch-list stock or index was requested",
            emptyList<String>(), seen.filter { mentions(it, "AAPL", "TSLA", "^GSPC", "^DJI", "^IXIC", "SPY") })
        assertFalse("no whole-app offline pass", vm.offline.progress.value.running)
        assertEquals("...and none was started", passesBefore, vm.offline.progress.value.lastFinishedAt)
        assertEquals(lastRefresh, vm.ui.value.lastRefresh)
        assertFalse("the shared portfolio spinner never lit", vm.ui.value.manualRefresh)
        assertTrue("the failure is said out loud", vm.toast.value.orEmpty().contains("NVDA"))
    }

    @Test fun `the comparison line is refreshed only while it is switched on`() {
        val vm = vm()
        buy(vm, "NVDA")
        val seen = record()
        vm.refreshStock("NVDA", ChartRange.D1)
        quiet(vm)
        assertEquals(emptyList<String>(), seen.filter { mentions(it, "SPY") })
        seen.clear()
        vm.refreshStock("NVDA", ChartRange.D1, benchmark = "SPY")
        quiet(vm)
        assertTrue(seen.any { mentions(it, "SPY") })
    }

    @Test fun `a second pull while one is running joins it and the flag always clears`() {
        val vm = vm()
        buy(vm, "NVDA")
        record()
        vm.refreshStock("NVDA", ChartRange.D1)
        vm.refreshStock("NVDA", ChartRange.D1)
        assertEquals(setOf("NVDA"), vm.stockRefreshing.value)
        quiet(vm)
        assertTrue(vm.stockRefreshing.value.isEmpty())
        // ...and it can be run again afterwards.
        vm.refreshStock("NVDA", ChartRange.D1)
        assertEquals(setOf("NVDA"), vm.stockRefreshing.value)
        quiet(vm)
    }
}
