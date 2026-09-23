package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.lastCloseInWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Tj's request: a running %-since-added on the watchlist, EXCLUDING the day it was added -
 * "I may have added it to the watchlist in the middle of the day and I don't want to include
 * the change for that day."
 *
 * [Row.watchedBasePrice] is what makes that literally true rather than a disclaimer: it is the
 * CLOSE of the day added (resolved by [PortfolioViewModel.resolveWatchBaselines], tested
 * against the database directly here since it needs a live network fetch to resolve for real -
 * see [com.tj.portfolio.data.WatchEntry]'s own note), not the price at the moment of tapping
 * "watch". That day's own move, whichever way it went before or after the tap, is already
 * baked into the reference point, so only the NEXT session onward ever shows up in
 * [Row.sinceWatchedPct].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchSinceAddedTest {

    private lateinit var app: Application
    private lateinit var db: Db

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        db = Db(app)
    }

    @After fun tearDown() { db.close(); app.deleteDatabase(Db.DB_NAME) }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    private fun vm() = PortfolioViewModel(app).also { settle() }

    private fun rowFor(vm: PortfolioViewModel, symbol: String) =
        vm.ui.value.rows.first { it.symbol == symbol }

    @Test fun `a freshly watched symbol carries the add date but withholds a percentage`() {
        val vm = vm()
        vm.addWatch("NVDA"); settle()
        val r = rowFor(vm, "NVDA")
        assertTrue("added should be stamped to roughly now", r.watchedAt > 0)
        assertEquals(0.0, r.watchedBasePrice, 1e-9)
        assertNull(
            "no baseline resolved yet - 0.00% would read as a real answer, not an unknown one",
            r.sinceWatchedPct
        )
    }

    @Test fun `once the baseline is written the row picks it up on the next recompute`() {
        val vm = vm()
        vm.addWatch("NVDA"); settle()
        db.setWatchBaseline("NVDA", 100.0)
        vm.recompute()
        val r = rowFor(vm, "NVDA")
        assertEquals(100.0, r.watchedBasePrice, 1e-9)
        // WHETHER A LIVE QUOTE EXISTS HERE DEPENDS ON THE NETWORK (full test 2026-09-23). This
        // asserted "no live quote, so null" - but the ViewModel's own start-up refresh really
        // fetches NVDA when the machine running the suite has network access, and whether that
        // reply landed before this line decided the result (it failed once with 128.87 and
        // passed on the rerun of identical code). The no-quote case is proven deterministically
        // by the pure test below; what THIS test owns is that the recompute picked the baseline
        // up, so the row's percentage must be measured from it whichever way the fetch went.
        if (r.price > 0.0) assertEquals((r.price - 100.0) / 100.0 * 100.0, r.sinceWatchedPct!!, 1e-9)
        else assertNull(r.sinceWatchedPct)
    }

    @Test fun `an unwatched symbol has no add date at all`() {
        val vm = vm()
        vm.addTxnRecord(
            Txn(
                type = TxnType.BUY, symbol = "NVDA", quantity = 1.0, price = 10.0,
                date = System.currentTimeMillis()
            )
        )
        settle()
        val r = rowFor(vm, "NVDA")
        assertEquals(0L, r.watchedAt)
        assertNull(r.sinceWatchedPct)
    }

    // -------------------------------------------------------- pure arithmetic, no ViewModel

    @Test fun `sinceWatchedPct is the plain percentage change from the baseline close`() {
        val row = Row(
            symbol = "NVDA", name = "", position = null,
            quote = Quote(symbol = "NVDA", price = 104.20),
            watchOnly = true, watched = true, watchedAt = 1L, watchedBasePrice = 100.0
        )
        assertEquals(4.20, row.sinceWatchedPct!!, 1e-6)
    }

    @Test fun `a drop below the baseline reads negative, not absolute`() {
        val row = Row(
            symbol = "NVDA", name = "", position = null,
            quote = Quote(symbol = "NVDA", price = 90.0),
            watchOnly = true, watched = true, watchedAt = 1L, watchedBasePrice = 100.0
        )
        assertEquals(-10.0, row.sinceWatchedPct!!, 1e-6)
    }

    @Test fun `no live quote yet withholds the percentage even with a real baseline`() {
        val row = Row(
            symbol = "NVDA", name = "", position = null, quote = null,
            watchOnly = true, watched = true, watchedAt = 1L, watchedBasePrice = 100.0
        )
        assertNull(row.sinceWatchedPct)
    }

    // ---------------------------------------- lastCloseInWindow (the shipped bug, fixed)

    private val dayStart = 1_000_000_000_000L
    private val todayStart = dayStart + 86_400_000L

    /**
     * THE BUG THAT SHIPPED IN v7.23: a sub-daily series (this app's own D5/D1 ranges) holds
     * several points for one calendar day, and the wrong one was being picked - the FIRST
     * (near the opening bell) instead of the LAST (the actual close).
     */
    @Test fun `picks the LAST point of the day, not the first`() {
        val points = listOf(
            ChartPoint(t = (dayStart / 1000) + 100, close = 50.0),   // early in the add-day
            ChartPoint(t = (dayStart / 1000) + 20000, close = 55.0), // mid-day
            ChartPoint(t = (dayStart / 1000) + 23000, close = 60.0)  // latest - this is the close
        )
        assertEquals(60.0, lastCloseInWindow(points, dayStart, todayStart)!!, 1e-9)
    }

    @Test fun `points outside the window are excluded on both ends`() {
        val points = listOf(
            ChartPoint(t = (dayStart / 1000) - 100, close = 40.0),      // before the add-day
            ChartPoint(t = (dayStart / 1000) + 100, close = 50.0),      // inside
            ChartPoint(t = (todayStart / 1000) + 100, close = 999.0)    // today or later - excluded
        )
        assertEquals(50.0, lastCloseInWindow(points, dayStart, todayStart)!!, 1e-9)
    }

    @Test fun `no points in the window returns null rather than a wrong guess`() {
        val points = listOf(ChartPoint(t = (dayStart / 1000) - 100, close = 40.0))
        assertNull(lastCloseInWindow(points, dayStart, todayStart))
        assertNull(lastCloseInWindow(emptyList(), dayStart, todayStart))
    }

    /** A daily-interval series has exactly one point per day - the bug was invisible here,
     *  which is exactly why it shipped once already. */
    @Test fun `a single point in the window is returned as-is`() {
        val points = listOf(ChartPoint(t = (dayStart / 1000) + 100, close = 42.0))
        assertEquals(42.0, lastCloseInWindow(points, dayStart, todayStart)!!, 1e-9)
    }
}
