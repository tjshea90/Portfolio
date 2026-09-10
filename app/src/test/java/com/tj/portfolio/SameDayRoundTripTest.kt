package com.tj.portfolio

import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import org.junit.Assert.assertEquals
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
}
