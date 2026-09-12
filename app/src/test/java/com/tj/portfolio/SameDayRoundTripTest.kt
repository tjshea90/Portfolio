package com.tj.portfolio

import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUYING AND SELLING THE SAME STOCK IN ONE SESSION (Round 66 audit, CRX-1).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * `sharesToday` / `costToday` exist so that shares bought TODAY are measured from what was
 * paid rather than from a previous close they were never exposed to. They were kept in two
 * side maps that a BUY added to and that NOTHING ever took from - a SELL consumed the lot and
 * left the same shares sitting in the "bought today" pool, at their old cost, for the rest of
 * the session.
 *
 * A same-day round trip is all it took:
 *   09:45  BUY  100 SOFI @ 10.00
 *   11:00  SELL 100 SOFI @ 12.00   -> +$200, correctly booked in `realized`
 *   14:00  BUY  100 SOFI @ 12.00
 * The 100 shares now held cost exactly 12.00 each. But `sharesToday` read 200 and `costToday`
 * read 2,200, so `avgCostToday` was 11.00 - and with the price at 12.00, `dayPnl` reported
 * **+$100.00** on a position whose value had not moved since it was opened, and `dayPnlPct`
 * reported +9.09% instead of 0.00%. The portfolio's "Today" headline summed the same
 * fabricated hundred dollars, while `brokerDayGain` - which never touches these fields -
 * stayed correct, so the two figures that exist to reconcile with each other disagreed by
 * exactly the invented amount.
 *
 * Both replay methods are tested, because both carried the same hole.
 */
class SameDayRoundTripTest {

    /** Mid-session on a fixed weekday, so every transaction lands inside `today`. */
    private val session = 1_756_909_800_000L      // 2025-09-03 ~13:50 UTC, a Wednesday

    private fun buy(qty: Double, px: Double, at: Long) =
        Txn(type = TxnType.BUY, symbol = "SOFI", quantity = qty, price = px,
            amount = -(qty * px), date = at)

    private fun sell(qty: Double, px: Double, at: Long) =
        Txn(type = TxnType.SELL, symbol = "SOFI", quantity = qty, price = px,
            amount = qty * px, date = at)

    private fun quote(price: Double, prevClose: Double) =
        Quote(symbol = "SOFI", price = price, prevClose = prevClose,
            updated = session, quoteTime = session)

    private fun position(txns: List<Txn>, method: String) =
        Ledger.positions(txns, method = method, sessionInstant = session)
            .first { it.symbol == "SOFI" }

    private val roundTrip = listOf(
        buy(100.0, 10.00, session - 5 * 3_600_000L),    // 09:45-ish
        sell(100.0, 12.00, session - 3 * 3_600_000L),   // 11:00-ish
        buy(100.0, 12.00, session)                      // now
    )

    /** THE CASE FROM THE REPORT, under FIFO. */
    @Test fun `a same-day round trip does not fabricate a day gain`() {
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(roundTrip, method)
            assertEquals("$method: shares held", 100.0, p.shares, 1e-9)
            assertEquals("$method: the closed round trip is realized", 200.0, p.realized, 1e-6)
            assertEquals(
                "$method: only the 100 shares still held were bought today",
                100.0, p.sharesToday, 1e-9
            )
            assertEquals(
                "$method: and they cost 12.00, not a blend with the lot that was sold",
                12.00, p.avgCostToday, 1e-6
            )

            // Price unchanged since the shares were bought: today's move on the open
            // position is zero, and the +$200 is already in `realized`.
            val q = quote(price = 12.00, prevClose = 9.50)
            assertEquals("$method: fabricated day gain", 0.0, p.dayPnl(q), 1e-6)
            assertEquals("$method: fabricated day percent", 0.0, p.dayPnlPct(q), 1e-6)
        }
    }

    /** A real move after the second buy is still measured, from the right cost. */
    @Test fun `a genuine move after the round trip is still counted`() {
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(roundTrip, method)
            val q = quote(price = 12.50, prevClose = 9.50)
            assertEquals("$method", 50.0, p.dayPnl(q), 1e-6)
        }
    }

    /** A partial same-day sell takes its share of the pool and no more. */
    @Test fun `a partial same-day sell shrinks the pool proportionally`() {
        val txns = listOf(
            buy(100.0, 10.00, session - 4 * 3_600_000L),
            sell(40.0, 11.00, session - 2 * 3_600_000L)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(txns, method)
            assertEquals("$method: shares", 60.0, p.shares, 1e-9)
            assertEquals("$method: 60 of the 100 bought today are still held",
                60.0, p.sharesToday, 1e-9)
            assertEquals("$method: they still cost 10.00", 10.00, p.avgCostToday, 1e-6)
            val q = quote(price = 11.00, prevClose = 9.00)
            assertEquals("$method", 60.0, p.dayPnl(q), 1e-6)
        }
    }

    /** Selling out completely leaves nothing in the pool. */
    @Test fun `closing the position empties the same-day pool`() {
        val txns = listOf(
            buy(100.0, 10.00, session - 4 * 3_600_000L),
            sell(100.0, 11.00, session - 2 * 3_600_000L)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(txns, method)
            assertEquals("$method: shares", 0.0, p.shares, 1e-9)
            assertEquals("$method: nothing bought today is still held",
                0.0, p.sharesToday, 1e-9)
            assertEquals("$method: realized", 100.0, p.realized, 1e-6)
        }
    }

    /**
     * THE CASE THE FEATURE EXISTS FOR, unchanged: shares bought today with no sell are still
     * measured from the fill, not from a previous close they never saw.
     */
    @Test fun `a plain same-day buy is still measured from the fill`() {
        val txns = listOf(
            buy(50.0, 20.00, session - 6 * 3_600_000L),   // held from before? no - same day
            buy(50.0, 22.00, session)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(txns, method)
            assertEquals("$method", 100.0, p.sharesToday, 1e-9)
            assertEquals("$method", 21.00, p.avgCostToday, 1e-6)
            val q = quote(price = 23.00, prevClose = 5.00)
            // Both lots bought today: 100 * (23.00 - 21.00), and the absurd prevClose is
            // deliberately ignored.
            assertEquals("$method", 200.0, p.dayPnl(q), 1e-6)
        }
    }

    /** And shares held from BEFORE today are still measured from the previous close. */
    @Test fun `older shares are still measured from the previous close`() {
        val txns = listOf(
            buy(100.0, 10.00, session - 30L * 86_400_000L),
            buy(100.0, 12.00, session)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(txns, method)
            assertEquals("$method", 100.0, p.sharesToday, 1e-9)
            val q = quote(price = 13.00, prevClose = 12.50)
            // 100 old shares from 12.50, 100 fresh from 12.00.
            assertEquals("$method", 100 * 0.50 + 100 * 1.00, p.dayPnl(q), 1e-6)
        }
    }

    // ================================ the mixed pool (Part 9 audit)
    //
    // EVERY TEST ABOVE HAS THE WHOLE POSITION BOUGHT TODAY, or no sell at all. That is the
    // shape CRX-1 was reported in, and it is exactly why the bug below survived it: with no
    // pre-today shares in the position, "take today's shares first" and "take the oldest
    // shares first" consume the same shares, so the two methods agreed by accident.

    /**
     * THE BUG THIS PROVES FIXED. A position holding shares from before today AND shares
     * bought today, with a same-day sell SMALLER than today's buy.
     *
     * A sale consumes the oldest shares first - that is what FIFO does, what Ally reports,
     * and what physically happened. So the sale comes out of the 100 held overnight and
     * every one of today's 50 shares is still held. `averageCost` took `minOf(covered,
     * todayShares)` instead - today's shares FIRST - and reported 20.
     *
     * `sharesToday` feeds `dayPnl`, so this made the app's "Today" headline depend on the
     * cost-basis preference in Settings: the same trades, the same prices, a different
     * number on screen depending on a setting that is only supposed to move the split
     * between realized and unrealized.
     */
    @Test fun `a same-day sell smaller than the pre-today holding leaves today's shares alone`() {
        val txns = listOf(
            buy(100.0, 10.00, session - 30L * 86_400_000L),   // a month ago
            buy(50.0, 12.00, session - 4 * 3_600_000L),       // this morning
            sell(30.0, 13.00, session - 2 * 3_600_000L)       // midday
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(txns, method)
            assertEquals("$method: shares", 120.0, p.shares, 1e-9)
            assertEquals(
                "$method: the sale consumed the OLDEST shares, so all 50 bought today are held",
                50.0, p.sharesToday, 1e-9
            )
            assertEquals("$method: and they still cost 12.00", 12.00, p.avgCostToday, 1e-6)
        }
    }

    /** Once the sale exhausts everything held from before today, it reaches today's pool. */
    @Test fun `a same-day sell bigger than the pre-today holding eats into today's shares`() {
        val txns = listOf(
            buy(100.0, 10.00, session - 30L * 86_400_000L),
            buy(50.0, 12.00, session - 4 * 3_600_000L),
            sell(120.0, 13.00, session - 2 * 3_600_000L)      // 100 old + 20 of today's
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = position(txns, method)
            assertEquals("$method: shares", 30.0, p.shares, 1e-9)
            assertEquals(
                "$method: 30 left, and every one of them was bought today",
                30.0, p.sharesToday, 1e-9
            )
            assertEquals("$method", 12.00, p.avgCostToday, 1e-6)
        }
    }

    /**
     * THE GENERAL PROPERTY, not one more hand-built case: over randomised histories that
     * straddle the session boundary, the two methods must always agree on HOW MANY of the
     * held shares were bought today.
     *
     * That is a fact about the world - were these shares exposed to the overnight move or
     * not - and it cannot depend on which cost-basis convention the user picked. What the
     * two methods may legitimately disagree about is what those shares COST
     * (`avgCostToday`): FIFO knows which of today's lots is still open, an average-cost
     * book genuinely does not, which is the same reason their cost bases differ at all. So
     * this asserts share counts only, deliberately.
     */
    @Test fun `both methods always agree on how many held shares were bought today`() {
        val rng = kotlin.random.Random(20260912)
        repeat(400) { run ->
            val txns = ArrayList<Txn>()
            var held = 0.0
            var id = 0L
            repeat(rng.nextInt(1, 12)) {
                // Dates land before the session window, inside it, AND AFTER it - a
                // generator that never crosses those boundaries cannot see either of the
                // two bugs this test exists for. The after-the-window case is not exotic:
                // the window is the session the quotes describe, which lags the calendar
                // every evening, night and weekend (see Ledger.dayBounds), so a transaction
                // dated later than it is ordinary.
                val at = when (rng.nextInt(4)) {
                    0 -> session - (1L + rng.nextInt(60)) * 86_400_000L   // days before
                    1 -> session + (1L + rng.nextInt(5)) * 86_400_000L    // days after
                    else -> session - rng.nextInt(8) * 3_600_000L         // inside the window
                }
                val px = 1.0 + rng.nextInt(5000) / 100.0
                if (held < 1e-9 || rng.nextInt(3) != 0) {
                    val qty = 1.0 + rng.nextInt(100)
                    txns.add(buy(qty, px, at).copy(id = ++id))
                    held += qty
                } else {
                    // Deliberately allowed to exceed the holding sometimes - overselling is
                    // a real shape in this ledger (a buy that was never imported).
                    val qty = 1.0 + rng.nextInt((held * 1.2).toInt().coerceAtLeast(1))
                    txns.add(sell(qty, px, at).copy(id = ++id))
                    held = (held - qty).coerceAtLeast(0.0)
                }
            }
            val f = position(txns, Ledger.FIFO)
            val a = position(txns, Ledger.AVERAGE)
            assertEquals("run $run: shares disagree\n$txns", f.shares, a.shares, 1e-6)
            assertEquals("run $run: sharesToday disagree\n$txns", f.sharesToday, a.sharesToday, 1e-6)
            // And neither method may ever claim more shares were bought today than are held.
            assertTrue("run $run: FIFO sharesToday > shares", f.sharesToday <= f.shares + 1e-6)
            assertTrue("run $run: AVG sharesToday > shares", a.sharesToday <= a.shares + 1e-6)
        }
    }
}
