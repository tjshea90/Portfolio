package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.approxSpanMs
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.net.FundamentalsFeed
import com.tj.portfolio.net.HttpResult
import com.tj.portfolio.net.Insider
import com.tj.portfolio.net.MarketData
import com.tj.portfolio.net.SharedAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.tj.portfolio.ui.BUSY_EXPLAINING
import com.tj.portfolio.ui.PULL_PRICES
import com.tj.portfolio.ui.PortfolioViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Regression tests for the 2026-09-24 full test (the reports in audits/2026-09-24). One section per
 * finding ID; the pure-logic halves live next to the code they test where a file exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullTest0924Test {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    @Suppress("UNCHECKED_CAST")
    private fun researchBusy(vm: PortfolioViewModel): MutableStateFlow<String> =
        PortfolioViewModel::class.java.getDeclaredField("_researchBusy")
            .apply { isAccessible = true }.get(vm) as MutableStateFlow<String>

    private fun awaitQuotesIdle(vm: PortfolioViewModel) {
        repeat(60) { if (!vm.ui.value.loading) return; settle() }
    }

    // ---- U-1: a pull on a price screen waits only on the price pass.

    /**
     * Tj's 09-23c screenshot, in the ViewModel: "Explain with Claude" is running on Research,
     * he pulls down on Portfolio, the prices land - and the circle used to keep spinning until
     * Claude answered, then for up to another fifteen minutes (a research job's end never
     * re-derived the flag; only the closed-market poll tick did).
     */
    @Test fun `U-1 a price pull ends with its own pass while a research job is still running`() {
        val vm = PortfolioViewModel(app).also { settle() }
        awaitQuotesIdle(vm)
        val busy = researchBusy(vm)
        busy.value = BUSY_EXPLAINING

        vm.refresh(manual = true)
        settle()
        awaitQuotesIdle(vm)

        assertFalse("the price pass should have finished", vm.ui.value.loading)
        assertTrue("the research job is still running", busy.value.isNotEmpty())
        assertFalse("the Portfolio circle must stop when ITS prices land, not when Claude answers",
            vm.ui.value.pulling(PULL_PRICES))
    }

    // ---- N-2: a well-formed empty v7 answer is not a broken endpoint.

    @Test fun `N-2 Yahoo knowing none of the symbols is an answer, an error page is not`() {
        // What v7 sends for SPX / VIX / a delisted ticker: the endpoint worked.
        assertTrue(MarketData.batchAnswered("""{"quoteResponse":{"result":[],"error":null}}"""))
        assertTrue(MarketData.batchAnswered("""{"quoteResponse":{"result":[{"symbol":"X"}]}}"""))
        // What a broken endpoint looks like: those still count toward the disable.
        assertFalse(MarketData.batchAnswered("<html>Service Unavailable</html>"))
        assertFalse(MarketData.batchAnswered(""))
        assertFalse(MarketData.batchAnswered("""{"finance":{"error":{"code":"Not Found"}}}"""))
        assertFalse(MarketData.batchAnswered(
            """{"quoteResponse":{"result":null,"error":{"code":"Bad Request"}}}"""))
        assertFalse(MarketData.batchAnswered(
            """{"quoteResponse":{"result":[],"error":{"code":"Unauthorized"}}}"""))
    }

    // ---- A-1: a backup shared or picked into the answer importer is refused, not imported.

    @Test fun `A-1 a Portfolio backup is recognised and never imported as an answer`() {
        val db = Db(app)
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 10.0, price = 100.0,
            amount = -1000.0, date = 1_726_156_800_000L))
        val backup = db.exportJson()
        assertEquals(SharedAnswer.Kind.BACKUP, SharedAnswer.classify(backup))
        assertEquals(SharedAnswer.BACKUP_MESSAGE, SharedAnswer.rejection(SharedAnswer.Kind.BACKUP))

        val vm = PortfolioViewModel(app).also { settle() }
        val shared = vm.importShared(backup)
        assertEquals(SharedAnswer.BACKUP_MESSAGE, shared.message)
        assertNull("a refused backup opens no screen", shared.dest)
        assertEquals(SharedAnswer.BACKUP_MESSAGE, vm.importClaudeFile(backup))
        assertTrue("nothing may be waiting for review",
            vm.importResult.value?.transactions.isNullOrEmpty())

        // A real answer that merely mentions the format name is still an answer.
        assertFalse(SharedAnswer.isBackup("""{"notes":"not a tj-portfolio-backup","transactions":[]}"""))
    }

    @Test fun `A-1 an epoch-number date in an answer is a real date, not an estimate`() {
        val r = ClaudeBridge.parse("""{"portfolioAppResponse":1,"transactions":[
            {"type":"BUY","symbol":"NVDA","quantity":10,"price":100,"date":1726156800000},
            {"type":"BUY","symbol":"AAPL","quantity":1,"price":200,"date":1726156800}]}""")
        assertEquals(2, r.transactions.size)
        r.transactions.forEach { t ->
            assertEquals(1_726_156_800_000L, t.date)
            assertFalse("${t.symbol} must not be flagged estimated",
                (t.note ?: "").contains(Txn.DATE_ESTIMATED))
        }
        assertNull(ClaudeBridge.epochDate(42))
        assertNull(ClaudeBridge.epochDate("1726156800000"))
    }

    // ---- N-3: quoteSummary re-mints the crumb only for a 401.

    private class Probe(val codes: List<Int>) {
        val urls = ArrayList<String>()
        val mints = ArrayList<Boolean>()
        var invalidations = 0
        suspend fun run(): FundamentalsFeed.YahooReply = FundamentalsFeed.yahooFetchWith(
            "SPX", "price",
            get = { url, _ -> urls.add(url); HttpResult(codes[(urls.size - 1).coerceAtMost(codes.size - 1)], "") },
            mint = { force -> mints.add(force); "crumb" },
            invalidate = { invalidations++ }
        )
    }

    @Test fun `N-3 a 404 on both hosts is two requests and no forced crumb mint`() = kotlinx.coroutines.runBlocking {
        val p = Probe(listOf(404, 404))
        assertNull(p.run().result)
        assertEquals(2, p.urls.size)
        assertEquals("never forces a handshake", listOf(false), p.mints)
        assertEquals(0, p.invalidations)
    }

    @Test fun `N-3 a timeout on both hosts does not retry either`() = kotlinx.coroutines.runBlocking {
        val p = Probe(listOf(-1, -1))
        p.run()
        assertEquals(2, p.urls.size)
        assertEquals(listOf(false), p.mints)
    }

    @Test fun `N-3 a 401 still earns exactly one fresh-crumb retry`() = kotlinx.coroutines.runBlocking {
        val p = Probe(listOf(401, 404, 404))
        p.run()
        assertEquals(listOf(false, true), p.mints)
        assertEquals(1, p.invalidations)
        assertEquals("401 on query1, then both hosts once more", 3, p.urls.size)
    }

    // ---- N-4: SEC requests are paced, not just bounded.

    @Test fun `N-4 twenty back-to-back SEC requests span at least nineteen gaps`() {
        var last = 0L
        val start = 1_000_000L
        var t = start
        repeat(20) {
            val at = Insider.nextSendAt(last, t)
            last = at
            t = at            // the next caller arrives the instant this one leaves
        }
        assertTrue("20 requests in ${last - start} ms is faster than 8/s",
            last - start >= 19 * Insider.SEC_MIN_GAP_MS)
        assertTrue("under EDGAR's 10/s cap", Insider.SEC_MIN_GAP_MS >= 100)
        // A request after a long quiet spell goes out at once.
        assertEquals(5_000_000L, Insider.nextSendAt(lastSendAt = 1_000_000L, now = 5_000_000L))
    }

    // ---- L-2: a cancelled pass still unwinding does not block the replacement.

    private fun field(vm: PortfolioViewModel, name: String) =
        PortfolioViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun `L-2 coming back while a cancelled pass unwinds still starts a new one`() {
        val vm = PortfolioViewModel(app).also { settle() }
        awaitQuotesIdle(vm)
        // The state the app is in a moment after coming back: the old pass was cancelled on
        // the way out but its `finally` (behind a blocking read) has not cleared `loading`.
        @Suppress("UNCHECKED_CAST")
        val ui = field(vm, "_ui").get(vm) as MutableStateFlow<com.tj.portfolio.ui.UiState>
        ui.value = ui.value.copy(loading = true)
        val cancelled = kotlinx.coroutines.Job().apply { cancel() }
        field(vm, "quoteJob").set(vm, cancelled)

        vm.refresh()
        assertTrue("refresh() returned at the loading guard - no replacement pass",
            field(vm, "quoteJob").get(vm) !== cancelled)
        settle(); awaitQuotesIdle(vm)
        assertFalse("the replacement pass must clear the flag when it ends", vm.ui.value.loading)
    }

    // ---- L-3: a price fill asked for in the background runs on return.

    @Test fun `L-3 a research price fill requested in the background runs when the app returns`() {
        val vm = PortfolioViewModel(app).also { settle() }
        @Suppress("UNCHECKED_CAST")
        val pending = field(vm, "pendingPriceFill").get(vm) as MutableSet<String>
        val fill = PortfolioViewModel::class.java
            .getDeclaredMethod("fillResearchPrices", List::class.java).apply { isAccessible = true }

        vm.setForeground(false)
        fill.invoke(vm, listOf("NEWCO"))
        settle()
        assertTrue("the fill must be owed, not dropped into the cancelled scope", "NEWCO" in pending)

        vm.setForeground(true)
        repeat(40) { if (pending.isEmpty()) return@repeat; settle() }
        assertTrue("returning must run the owed fill: $pending", pending.isEmpty())
    }

    // ---- U-3: the Feed's headline sweep stops while something covers the list.

    @Test fun `U-3 the feed list counts as visible only when nothing covers it`() {
        assertTrue(com.tj.portfolio.ui.feedListVisible(onFeedTab = true, detail = null, readerOpen = false))
        assertFalse("an article open over the Feed",
            com.tj.portfolio.ui.feedListVisible(onFeedTab = true, detail = null, readerOpen = true))
        assertFalse("a stock opened from a Feed row",
            com.tj.portfolio.ui.feedListVisible(onFeedTab = true, detail = "NVDA", readerOpen = false))
        assertFalse(com.tj.portfolio.ui.feedListVisible(onFeedTab = false, detail = null, readerOpen = false))
    }

    // ---- A-2: a Merge restore re-arms the replay repair only for the rows it inserted.

    @Test fun `A-2 rows a merge inserted are repair candidates, rows already here are not`() {
        val session = 1_756_909_800_000L
        fun pair(sellId: Long, buyId: Long) = listOf(
            Txn(id = sellId, type = TxnType.SELL, symbol = "NVDA", quantity = 100.0, price = 180.0,
                amount = 18_000.0, date = session, source = "SCREENSHOT"),
            Txn(id = buyId, type = TxnType.BUY, symbol = "NVDA", quantity = 100.0, price = 175.0,
                amount = -17_500.0, date = session, source = "SCREENSHOT"))
        // Mark = 5; a merge inserted ids 50..60.
        val ranges = com.tj.portfolio.ui.parseRepairRanges("50-60")
        listOf(Ledger.FIFO, Ledger.AVERAGE).forEach { m ->
            // Entered chronologically after the fix (ids 10-11, between the mark and the merge):
            // kept as stored - 100 shares, the missing earlier buy reported, NOT erased to 0.
            val kept = Ledger.positions(pair(10, 11), method = m, sessionInstant = session,
                repairBelowId = 5, repairRanges = ranges).single()
            assertEquals("$m kept shares", 100.0, kept.shares, 1e-9)
            assertEquals("$m kept oversold", 100.0, kept.oversold, 1e-9)
            // The merged pair (ids 51-52) may be a pre-fix device's screen order: repaired.
            val merged = Ledger.positions(pair(51, 52), method = m, sessionInstant = session,
                repairBelowId = 5, repairRanges = ranges).single()
            assertEquals("$m merged repaired", 0.0, merged.oversold, 1e-9)
        }
    }

    @Test fun `A-2 repair ranges round-trip and ignore junk`() {
        val r = listOf(1L..5L, 9L..12L)
        assertEquals(r, com.tj.portfolio.ui.parseRepairRanges(com.tj.portfolio.ui.formatRepairRanges(r)))
        assertEquals(emptyList<LongRange>(), com.tj.portfolio.ui.parseRepairRanges(""))
        assertEquals(emptyList<LongRange>(), com.tj.portfolio.ui.parseRepairRanges(null))
        assertEquals(listOf(3L..4L), com.tj.portfolio.ui.parseRepairRanges("x-y;9-2;3-4;7"))
    }

    // ---- A-3: a second same-size fill at a different price is a different trade.

    @Test fun `A-3 a second same-size fill a few cents a share away is not a duplicate`() {
        val db = Db(app)
        val day = 1_756_909_800_000L
        val morning = Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 10.0, price = 180.0,
            amount = -1800.0, date = day)
        val id = db.insertTxn(morning)
        val afternoon = morning.copy(price = 180.80, amount = -1808.0, date = day + 3_600_000L)
        assertNull("10 @ 180.80 is a new trade, not the 10 @ 180.00 already on file",
            db.findDuplicateId(afternoon))
        assertNull(db.findDuplicateIdAnyDate(afternoon))
        // The same trade re-imported with a cent-rounded price still matches.
        val again = morning.copy(price = 180.0, amount = -1800.04)
        assertEquals(id, db.findDuplicateId(again))
        // A big sale's SEC/TAF fee difference still matches.
        val bigId = db.insertTxn(Txn(type = TxnType.SELL, symbol = "AAPL", quantity = 100.0,
            price = 180.0, amount = 18_000.0, date = day))
        assertEquals(bigId, db.findDuplicateId(Txn(type = TxnType.SELL, symbol = "AAPL",
            quantity = 100.0, price = 180.0, amount = 17_999.49, date = day)))
    }

    // ---- A-4: MediaStore's numbered copy of the autosave is still ours.

    @Test fun `A-4 the autosave's numbered copies are recognised, look-alikes are not`() {
        val m = com.tj.portfolio.util.Storage::matchesOwnName
        assertTrue(m("portfolio-autosave.json", "portfolio-autosave.json"))
        assertTrue(m("portfolio-autosave (1).json", "portfolio-autosave.json"))
        assertTrue(m("portfolio-autosave (12).json", "portfolio-autosave.json"))
        assertFalse(m("portfolio-autosave-previous (1).json", "portfolio-autosave.json"))
        assertFalse(m("portfolio-autosave (x).json", "portfolio-autosave.json"))
        assertFalse(m("portfolio-autosave (1).json.bak", "portfolio-autosave.json"))
        assertFalse(m("other (1).json", "portfolio-autosave.json"))
    }

    // ---- S-1: the popup describes the branch the score used for a dated, voteless panel.

    @Test fun `S-1 a dated panel with no readable grade is not called undated, and its weight matches`() {
        val now = 1_800_000_000_000L
        val f = com.tj.portfolio.data.Fundamentals(
            symbol = "XYZ",
            values = mapOf("marketCap" to 1e10),
            consensus = com.tj.portfolio.data.Consensus(mean = 2.0, analysts = 8, buy = 6, hold = 2,
                targetMean = 120.0),
            ratings = listOf(com.tj.portfolio.data.AnalystRating(
                firm = "Firm", date = now - 200L * 86_400_000L, toGrade = "", target = 0.0)),
            fetched = now
        )
        val rec = com.tj.portfolio.net.Recommend.build("XYZ", 100.0, f, now = now)!!
        val note = rec.freshnessNote()
        assertFalse("the dates were there and were used: $note", note.contains("no publication dates"))
        assertTrue(note, note.contains("dated"))
        val panel = com.tj.portfolio.net.RatingRecency.panel(f.ratings, now)!!
        assertTrue("the popup's weight must be capped by the panel's currency like the score's: " +
            "${rec.analystWeight} vs ${panel.currency}", rec.analystWeight <= panel.currency + 1e-9)
    }

    // ---- S-4: an old Research / Advice answer ages from its own date.

    @Test fun `S-4 an answer's asOf decides its age, today's or an unreadable one is now`() {
        val now = java.time.ZonedDateTime.of(2026, 9, 24, 15, 0, 0, 0,
            java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val old = ClaudeBridge.answeredAt("2026-09-04", now)!!
        assertEquals(20L, (now - old) / 86_400_000L)
        assertEquals(old, ClaudeBridge.answeredAt("Sep 4, 2026", now))
        assertNull(ClaudeBridge.answeredAt("2026-09-24", now))
        assertNull(ClaudeBridge.answeredAt("", now))
        assertNull(ClaudeBridge.answeredAt("yesterday-ish", now))
    }

    @Test fun `S-4 an old research answer's paragraphs are stamped with its date`() {
        val tenDaysAgo = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York")).minusDays(10)
        val parsed = com.tj.portfolio.net.ResearchBridge.parse("""{"portfolioAppResponse":1,
            "research":{"asOf":"$tenDaysAgo","best":[{"symbol":"XYZ","why":"Cheap today.","conviction":7}]}}""")
        assertTrue(parsed.error ?: "", parsed.error == null)
        val at = parsed.answeredAt!!
        assertTrue(parsed.notes.contains("dated"))
        val row = com.tj.portfolio.data.ResearchRow(symbol = "XYZ")
        val merged = com.tj.portfolio.net.ResearchBridge.merge(listOf(row), parsed.best, at).single()
        assertEquals(at, merged.whyAt)
        val days = (System.currentTimeMillis() - merged.whyAt) / 86_400_000L
        assertTrue("stamped $days days ago", days in 9L..10L)
    }

    @Test fun `S-4 an old advice answer is not generated just now`() {
        val r = ClaudeBridge.parse("""{"portfolioAppResponse":1,"asOf":"2020-01-02",
            "advice":{"summary":"Too concentrated in one name.","risks":"","actions":[],"stocks":[]}}""")
        val gen = r.advice!!.generated
        assertTrue("generated ${gen} should be in 2020", gen < 1_600_000_000_000L)
    }

    // ---- S-5: the app's relative-date earnings line is rebuilt, never frozen as Claude's.

    @Test fun `S-5 the app's earnings phrase is recognised and split from Claude's words`() {
        val R = com.tj.portfolio.net.Research
        assertEquals("Earnings in 6 days - Sep 27 (estimated)",
            R.appEarningsPart("Earnings in 6 days - Sep 27 (estimated)"))
        assertEquals("", R.claudeCatalystPart("Earnings in 6 days - Sep 27"))
        assertEquals("FDA decision Friday", R.claudeCatalystPart("Earnings today - FDA decision Friday"))
        assertEquals("FDA decision Friday", R.claudeCatalystPart("FDA decision Friday"))
        // Claude's line keeps TODAY's earnings phrase in front, so "earnings due today" survives.
        val merged = R.combineCatalyst("Earnings today", "Guidance risk on the call")
        assertTrue(merged, merged.startsWith(R.CATALYST_EARNINGS_TODAY))
        assertTrue(merged.endsWith("Guidance risk on the call"))
        assertEquals("Earnings today", R.combineCatalyst("Earnings today", ""))
        assertEquals("Buyback news", R.combineCatalyst("", "Buyback news"))
    }

    @Test fun `S-5 a rebuild does not carry a frozen app catalyst over the fresh one`() {
        val now = System.currentTimeMillis()
        val old = com.tj.portfolio.data.ResearchSet(best = listOf(com.tj.portfolio.data.ResearchRow(
            symbol = "XYZ", why = "Cheap.", whyAt = now - 2 * 86_400_000L, conviction = 7,
            catalyst = "Earnings in 6 days - Sep 27")), generated = now - 3_600_000L)
        val fresh = com.tj.portfolio.data.ResearchSet(best = listOf(com.tj.portfolio.data.ResearchRow(
            symbol = "XYZ", catalyst = "Earnings in 4 days - Sep 27")), generated = now)
        val out = com.tj.portfolio.ui.carryExplanations(old, fresh, now).best.single()
        assertEquals("Earnings in 4 days - Sep 27", out.catalyst)
        assertEquals("Claude's paragraph still carries", "Cheap.", out.why)
        // Claude's own words ride on top of the FRESH phrase.
        val old2 = old.copy(best = listOf(old.best.single().copy(
            catalyst = "Earnings in 6 days - Sep 27 - Guidance risk")))
        assertEquals("Earnings in 4 days - Sep 27 - Guidance risk",
            com.tj.portfolio.ui.carryExplanations(old2, fresh, now).best.single().catalyst)
    }

    // ---- S-6: a stale fund Claude added does not become a permanent row.

    @Test fun `S-6 a Claude-added fund with a stale paragraph is gone after a cold launch and a rebuild`() {
        val now = System.currentTimeMillis()
        val vti = com.tj.portfolio.data.ResearchRow(symbol = "VTI", why = "Broad US market.",
            whyAt = now - 20 * 86_400_000L, catalyst = "broad US equity", conviction = 9)
        val set = com.tj.portfolio.data.ResearchSet(etfs = listOf(vti))
        val launched = com.tj.portfolio.ui.evictStaleWhy(set, now)
        assertTrue("dropped outright at launch", launched.etfs.none { it.symbol == "VTI" })
        // And even if a row reached a rebuild blanked, the rebuild does not resurrect a stale one.
        val rebuilt = com.tj.portfolio.ui.carryEtfExplanations(launched.etfs, emptyList(), now)
        assertTrue(rebuilt.none { it.symbol == "VTI" })
    }

    // ---- S-7: a Day Trading paragraph lives for its own session only.

    @Test fun `S-7 a previous session's Day Trading paragraph is not carried onto today's row`() {
        val now = System.currentTimeMillis()
        val weekOld = com.tj.portfolio.data.ResearchRow(symbol = "GME", why = "In play today.",
            whyAt = now - 7 * 86_400_000L, conviction = 8, catalyst = "Halt risk")
        val old = com.tj.portfolio.data.ResearchSet(dayTrading = listOf(weekOld), generated = now - 3_600_000L)
        val fresh = com.tj.portfolio.data.ResearchSet(dayTrading = listOf(
            com.tj.portfolio.data.ResearchRow(symbol = "GME")), generated = now)
        val row = com.tj.portfolio.ui.carryExplanations(old, fresh, now).dayTrading.single()
        assertEquals("", row.why)
        assertEquals(0, row.conviction)
        val launched = com.tj.portfolio.ui.evictStaleWhy(old, now).dayTrading.single()
        assertEquals("the cold launch expires it too", "", launched.why)
        assertEquals(0, launched.conviction)
    }

    // ---- S-8: strategy products and state/HY munis are not "the same exposure".

    @Test fun `S-8 strategy funds, state munis and banded junk no longer join the plain index group`() {
        val k = com.tj.portfolio.net.EtfExposure::keyOf
        val voo = k("Vanguard S&P 500 ETF")
        assertEquals("US large cap - S&P 500", voo)
        listOf("Invesco S&P 500 Top 50 ETF", "NEOS S&P 500 High Income ETF",
            "Invesco S&P 500 High Beta ETF", "Invesco S&P 500 Revenue ETF",
            "Invesco S&P 500 GARP ETF", "SPDR S&P 500 Fossil Fuel Reserves Free ETF"
        ).forEach { assertTrue("$it keyed with VOO", k(it) != voo) }
        assertTrue(k("NEOS Nasdaq-100 High Income ETF") != k("Invesco QQQ Trust"))
        assertTrue(k("iShares MSCI Emerging Markets ex China ETF") != k("iShares MSCI Emerging Markets ETF"))
        assertTrue(k("ProShares Bitcoin Strategy ETF") != k("iShares Bitcoin Trust ETF"))
        val mub = k("iShares National Muni Bond ETF")
        assertEquals("Bonds - municipal", mub)
        assertNull(k("iShares California Muni Bond ETF"))
        assertTrue(k("VanEck High Yield Muni ETF") != mub)
        assertTrue(k("JPMorgan Ultra-Short Municipal Income ETF") != mub)
        assertTrue(k("SPDR Bloomberg Short Term High Yield Bond ETF") !=
            k("iShares iBoxx High Yield Corporate Bond ETF"))
    }

    @Test fun `S-8 every fund a survivor beat is named on its card`() {
        val names = listOf("Vanguard S&P 500 ETF" to "VOO", "iShares Core S&P 500 ETF" to "IVV",
            "SPDR Portfolio S&P 500 ETF" to "SPLG", "SPDR S&P 500 ETF Trust" to "SPY",
            "Some Other S&P 500 Index Fund" to "OTHR")
        val out = com.tj.portfolio.net.EtfExposure.dedupe(names, { it.first }, { it.second })
        assertEquals(1, out.size)
        assertEquals(listOf("IVV", "SPLG", "SPY", "OTHR"), out.single().second)
    }

    // ---- D-3: a weekend or holiday plan is for the next session.

    private fun ny(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = java.time.ZonedDateTime.of(
        y, mo, d, h, mi, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    @Test fun `D-3 weekend plans survive to Monday, a Friday session plan does not`() {
        // 2026-09-25 is a Friday.
        val keep = com.tj.portfolio.ui.planStillForSession(ny(2026, 9, 25, 22), ny(2026, 9, 26, 10))
        assertTrue("Fri 22:00 -> Sat 10:00", keep)
        assertTrue("Sat 10:00 -> Mon 08:00",
            com.tj.portfolio.ui.planStillForSession(ny(2026, 9, 26, 10), ny(2026, 9, 28, 8)))
        assertTrue("Sun 11:00 -> Mon 08:00",
            com.tj.portfolio.ui.planStillForSession(ny(2026, 9, 27, 11), ny(2026, 9, 28, 8)))
        assertFalse("Friday's own session plan is not Monday's",
            com.tj.portfolio.ui.planStillForSession(ny(2026, 9, 25, 10), ny(2026, 9, 28, 10)))
        assertEquals(com.tj.portfolio.net.MarketClock.dayKey(ny(2026, 9, 28, 12)),
            com.tj.portfolio.net.MarketClock.sessionFor(ny(2026, 9, 26, 12)))
    }

    @Test fun `D-3 Friday's answer imported over the weekend is current until Monday's open`() {
        val b = com.tj.portfolio.net.DayTradingBridge
        assertTrue(b.answerIsCurrent("2026-09-25", ny(2026, 9, 26, 11)))
        assertTrue(b.answerIsCurrent("2026-09-25", ny(2026, 9, 28, 8)))
        assertFalse("not once Monday's session has started",
            b.answerIsCurrent("2026-09-25", ny(2026, 9, 28, 10)))
        assertFalse("Thursday's answer is not Monday's",
            b.answerIsCurrent("2026-09-24", ny(2026, 9, 26, 11)))
    }

    // ---- D-2: an evening Claude plan is not replaced by the next pre-market tick.

    @Test fun `D-2 a plan imported after the close survives the post-close stamp and the 04 00 tick`() {
        val imported = ny(2026, 9, 28, 17, 10)          // Monday evening
        val claude = com.tj.portfolio.data.ResearchRow(symbol = "GME", price = 22.5,
            entryPrice = 23.0, stopPrice = 21.5, targetPrice = 26.0, setup = "Claude breakout",
            planByClaude = true, why = "Plan for tomorrow.", whyAt = imported,
            // What the 18:00 post-close sweep left on it: today's date.
            sessionDay = com.tj.portfolio.net.MarketClock.dayKey(imported))
        val tuesday = com.tj.portfolio.net.MarketClock.dayKey(ny(2026, 9, 29, 4, 5))
        val tech = com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals(
            atr14 = 2.5, adr = 1.1, prevHigh = 23.0, sessionDay = tuesday, intradayFetched = true,
            lastPrice = 22.6)
        val out = com.tj.portfolio.ui.mergeDayTradingTech(claude, tech, minutesLeft = 390,
            now = ny(2026, 9, 29, 4, 5))
        assertTrue("Claude's plan for Tuesday was replaced at Tuesday 04:05", out.planByClaude)
        assertEquals(23.0, out.entryPrice, 1e-9)
        // A plan made during Monday's session does roll over.
        val mondaySession = claude.copy(whyAt = ny(2026, 9, 28, 11))
        assertFalse(com.tj.portfolio.ui.mergeDayTradingTech(mondaySession, tech, minutesLeft = 390,
            now = ny(2026, 9, 29, 4, 5)).planByClaude)
    }

    // ---- D-5: a bar or range still printing is not a trigger level.

    @Test fun `D-5 the opening bar counts only once it has closed`() {
        val T = com.tj.portfolio.net.DayTradingTechnicals
        val open930 = ny(2026, 9, 28, 9, 30) / 1000
        val first = com.tj.portfolio.net.DayTradingTechnicals.Bar(t = open930, open = 20.0, high = 20.07, low = 19.95, close = 19.98, volume = 1000.0)
        assertFalse("09:32 - the 09:30 bar is still printing", T.openingBarComplete(listOf(first)))
        val second = com.tj.portfolio.net.DayTradingTechnicals.Bar(t = open930 + 300, open = 19.98, high = 20.2, low = 19.9, close = 20.1, volume = 900.0)
        assertTrue(T.openingBarComplete(listOf(first, second)))
    }

    @Test fun `D-5 an incomplete 30-minute range is not offered as the breakout level`() {
        val tech = com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals(
            atr14 = 2.0, adr = 1.0, atrIntraday = 0.3, sessionLive = true, sessionDay = "20260928",
            openingRangeHigh = 20.07, openingRangeLow = 19.9, openingRangeComplete = false,
            prevHigh = 21.5, prevClose = 19.8, vwap = 19.95, sessionHigh = 20.07, sessionLow = 19.9,
            lastPrice = 20.0)
        val (plan, _) = com.tj.portfolio.net.ResearchScore.planInternal(20.0, tech, minutesLeft = 385)
        if (plan != null) assertFalse("planned off a range still printing: ${plan.trigger}",
            plan.trigger.contains("opening-range"))
    }

    // ---- C-2: five weekday sessions are not a truncated 5D chart.

    @Test fun `C-2 Monday open to Friday morning is a whole 5D chart, two days is not`() {
        val f = com.tj.portfolio.net.ChartFeed
        assertFalse("Mon 09:30 -> Fri 09:35", f.truncatedSpan(com.tj.portfolio.data.ChartRange.D5, 4.0))
        assertFalse(f.truncatedSpan(com.tj.portfolio.data.ChartRange.D5, 6.3))
        assertTrue("listed two days ago", f.truncatedSpan(com.tj.portfolio.data.ChartRange.D5, 1.3))
    }

    // ---- C-3 / C-5: both comparison lines share a real start, in the same session.

    private fun cseries(sym: String, range: com.tj.portfolio.data.ChartRange, start: Long, step: Long,
                        closes: List<Double>) = com.tj.portfolio.data.ChartSeries(
        symbol = sym, range = range,
        points = closes.mapIndexed { i, c -> com.tj.portfolio.data.ChartPoint(start + i * step, c) },
        baseline = closes.first(), currency = "USD", fetched = System.currentTimeMillis())

    @Test fun `C-3 a stock older than SPY is anchored where both exist`() {
        val day = 86_400L
        val stock = cseries("KO", com.tj.portfolio.data.ChartRange.MAX, 1_000_000L, day, List(10) { 10.0 + it })
        val spy = cseries("SPY", com.tj.portfolio.data.ChartRange.MAX, 1_000_000L + 4 * day, day, List(6) { 100.0 + it })
        assertEquals("the later of the two first candles", 1_000_000L + 4 * day,
            com.tj.portfolio.ui.compareAnchorFor(stock, com.tj.portfolio.data.ChartRange.MAX, spy))
        val pair = com.tj.portfolio.ui.compareLines(stock, spy, stock, com.tj.portfolio.data.ChartRange.MAX)!!
        // At the shared anchor (index 4) both lines read 0%.
        assertEquals(0.0, pair.other[4], 1e-9)
        assertEquals(0.0, pair.own[4], 1e-9)
    }

    @Test fun `C-5 a benchmark that ends before the stock's window is no overlay`() {
        val stock = cseries("X", com.tj.portfolio.data.ChartRange.M1, 2_000_000L, 1000L, listOf(1.0, 2.0, 3.0))
        val stale = cseries("SPY", com.tj.portfolio.data.ChartRange.M1, 1_000_000L, 1000L, listOf(5.0, 6.0, 7.0))
        assertNull(com.tj.portfolio.ui.comparePercents(stock, stale))
    }

    // ---- C-4: a window held on a boundary does not flip the range every frame.

    @Test fun `C-4 wobbling around every ladder boundary settles instead of flipping`() {
        val R = com.tj.portfolio.data.ChartRange
        val ladder = R.ZOOM_LADDER          // widest first
        for (i in 0 until ladder.size - 1) {
            val coarse = ladder[i]; val fine = ladder[i + 1]
            val edge = fine.approxSpanMs
            var cur = R.rangeForSpan((edge * 1.01).toLong(), fine)
            assertEquals("just past ${fine.label} needs ${coarse.label}", coarse, cur)
            // The wobble back and forth across the edge must not flip back.
            repeat(6) { k ->
                val span = if (k % 2 == 0) (edge * 0.99).toLong() else (edge * 1.01).toLong()
                cur = R.rangeForSpan(span, cur)
                assertEquals("frame $k at ${fine.label}/${coarse.label}", coarse, cur)
            }
            // Clearly inside the finer rung, it does go finer.
            assertEquals(fine, R.rangeForSpan((edge * 0.5).toLong(), coarse))
        }
    }
}
