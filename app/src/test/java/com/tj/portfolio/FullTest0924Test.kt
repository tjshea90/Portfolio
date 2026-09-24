package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.net.ClaudeBridge
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
}
