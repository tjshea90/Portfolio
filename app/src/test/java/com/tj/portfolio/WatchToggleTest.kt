package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.ui.PortfolioViewModel
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
 * HELD AND WATCHED ARE NOT THE SAME QUESTION (Round 66 audit, PUI-7).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * `Row.watchOnly` answers "which list does this row belong to". The row's long-press menu was
 * reading it as if it answered "is this symbol on the watchlist", and the two come apart for
 * exactly one case - a symbol that is BOTH held and watched. `recompute` emits such a symbol
 * once, from the positions loop, with `watchOnly = false`, and the watchlist loop then skips
 * it so it does not appear twice.
 *
 * So on the Portfolio tab that stock's menu offered "Also watch this", with nothing to say it
 * was already there. Tapping it called `addWatch` on a row already in the table - a no-op
 * write, plus a network refresh, plus a toast claiming it had been added. And there was no
 * way back: the "Remove from watchlist" branch was unreachable for a held symbol, so the only
 * way to take it off the watchlist was to sell out of the position first, at which point it
 * silently reappeared on the Watchlist tab.
 *
 * `Row.watched` is the second question, asked separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchToggleTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    private fun vm() = PortfolioViewModel(app).also { settle() }

    private fun buy(vm: PortfolioViewModel, symbol: String) {
        vm.addTxnRecord(
            Txn(
                type = TxnType.BUY, symbol = symbol, quantity = 10.0, price = 100.0,
                date = System.currentTimeMillis()
            )
        )
        settle()
    }

    private fun rowFor(vm: PortfolioViewModel, symbol: String) =
        vm.ui.value.rows.first { it.symbol == symbol }

    /** A held stock that is not on the watchlist: watched is false, and the menu says "Also watch". */
    @Test fun heldOnly() {
        val vm = vm()
        buy(vm, "NVDA")
        val r = rowFor(vm, "NVDA")
        assertFalse("held-only row must not be watchOnly", r.watchOnly)
        assertFalse("held-only row must not be watched", r.watched)
    }

    /** A watchlist-only symbol: both flags true, and it is removable, as it always was. */
    @Test fun watchedOnly() {
        val vm = vm()
        vm.addWatch("TSM"); settle()
        val r = rowFor(vm, "TSM")
        assertTrue(r.watchOnly)
        assertTrue("a watchlist row is by definition watched", r.watched)
    }

    /** THE CASE THAT WAS BROKEN, in both orders it can be reached. */
    @Test fun watchedThenBought() {
        val vm = vm()
        vm.addWatch("NVDA"); settle()
        buy(vm, "NVDA")
        val r = rowFor(vm, "NVDA")
        assertFalse("it is held, so it belongs on the Portfolio tab", r.watchOnly)
        assertTrue("but it IS on the watchlist, and the menu must know", r.watched)
    }

    @Test fun boughtThenWatched() {
        val vm = vm()
        buy(vm, "NVDA")
        vm.addWatch("NVDA"); settle()
        val r = rowFor(vm, "NVDA")
        assertFalse(r.watchOnly)
        assertTrue(r.watched)
    }

    /** Still exactly one row for the symbol - the de-duplication is untouched. */
    @Test fun aHeldAndWatchedSymbolIsOneRow() {
        val vm = vm()
        vm.addWatch("NVDA"); settle()
        buy(vm, "NVDA")
        assertEquals(1, vm.ui.value.rows.count { it.symbol == "NVDA" })
    }

    /**
     * The round trip the old code could not do: take a held symbol off the watchlist and see
     * `watched` go false, with the position untouched.
     */
    @Test fun aHeldSymbolCanBeUnwatchedAgain() {
        val vm = vm()
        buy(vm, "NVDA")
        vm.addWatch("NVDA"); settle()
        assertTrue(rowFor(vm, "NVDA").watched)

        vm.removeWatch("NVDA"); settle()
        val r = rowFor(vm, "NVDA")
        assertFalse("removeWatch must clear it", r.watched)
        assertEquals("the position must survive", 10.0, r.shares, 1e-9)
    }

    /** And selling out of it does not resurrect a watchlist entry that was removed. */
    @Test fun sellingOutDoesNotResurrectTheWatchEntry() {
        val vm = vm()
        buy(vm, "NVDA")
        vm.addWatch("NVDA"); settle()
        vm.removeWatch("NVDA"); settle()
        vm.addTxnRecord(
            Txn(
                type = TxnType.SELL, symbol = "NVDA", quantity = 10.0, price = 110.0,
                date = System.currentTimeMillis()
            )
        )
        settle()
        assertTrue(
            "NVDA was un-watched and is now unheld - it should be off both lists",
            vm.ui.value.rows.none { it.symbol == "NVDA" }
        )
    }
}
