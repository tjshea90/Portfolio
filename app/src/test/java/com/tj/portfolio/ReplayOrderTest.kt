package com.tj.portfolio

import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.ImportDup
import com.tj.portfolio.domain.ImportDupes
import com.tj.portfolio.domain.ImportOrder
import com.tj.portfolio.domain.Ledger
import com.tj.portfolio.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SAME-DAY TRADES MUST REPLAY IN THE ORDER THEY HAPPENED (full-tests audit 2026-09-22).
 *
 * Every entry point stamps a trade with a date and no time (local noon), so the ledger can
 * only order one day's trades by row id - the order they were inserted in.
 *
 *  - A-H2: screenshot imports inserted rows in SCREEN order, and a brokerage activity screen
 *    is newest first. "Bought 100 in the morning, sold them at lunch" was stored sell-then-buy:
 *    the sale found nothing to sell and the buy opened 100 phantom shares.
 *  - A-M3: a split recorded on the same date as trades was applied after them, multiplying
 *    shares that had already been bought at the post-split price.
 *  - A-M4: two identical partial fills - both real - collapsed into one on import.
 *  - A-L7: "09/15/26" parsed as the year 26 AD.
 */
class ReplayOrderTest {

    private val day = 86_400_000L
    private val d1 = Fmt.parseDate("2026-03-02")!!
    private val d2 = d1 + day
    private val d3 = d1 + 2 * day
    private val session = d3 + 30 * day

    private fun buy(id: Long, qty: Double, px: Double, at: Long, sym: String = "SOFI") =
        Txn(id = id, type = TxnType.BUY, symbol = sym, quantity = qty, price = px,
            amount = -(qty * px), date = at)

    private fun sell(id: Long, qty: Double, px: Double, at: Long, sym: String = "SOFI") =
        Txn(id = id, type = TxnType.SELL, symbol = sym, quantity = qty, price = px,
            amount = qty * px, date = at)

    private fun split(id: Long, ratio: Double, at: Long, sym: String = "SOFI") =
        Txn(id = id, type = TxnType.SPLIT, symbol = sym, quantity = ratio,
            amount = Txn.cashEffect(TxnType.SPLIT, ratio, 0.0, 0.0, 0.0), date = at)

    private fun pos(txns: List<Txn>, method: String, sym: String = "SOFI") =
        Ledger.positions(txns, method = method, sessionInstant = session).first { it.symbol == sym }

    private val methods = listOf(Ledger.FIFO, Ledger.AVERAGE)

    // ---------------------------------------------------------------- A-H2, rows on file

    /** THE REPORTED SHAPE: a day trade stored newest-first. */
    @Test fun `a same-day round trip stored newest-first replays as buy then sell`() {
        val txns = listOf(
            sell(1, 100.0, 12.0, d1),     // screen row 1 - lunch
            buy(2, 100.0, 10.0, d1)       // screen row 2 - morning
        )
        for (m in methods) {
            val p = pos(txns, m)
            assertEquals("$m: nothing is held", 0.0, p.shares, 1e-9)
            assertEquals("$m: nothing was sold without cover", 0.0, p.oversold, 1e-9)
            assertEquals("$m: the round trip is realized", 200.0, p.realized, 1e-6)
        }
    }

    /** Several trades in one day, newest first, on top of an existing holding. */
    @Test fun `a longer newest-first day on top of a holding is repaired`() {
        val txns = listOf(
            buy(1, 50.0, 9.0, d1),
            // d2, chronologically: buy 100 @10, sell 150 @12, buy 30 @11 - stored backwards
            buy(2, 30.0, 11.0, d2),
            sell(3, 150.0, 12.0, d2),
            buy(4, 100.0, 10.0, d2)
        )
        for (m in methods) {
            val p = pos(txns, m)
            assertEquals("$m: shares", 30.0, p.shares, 1e-9)
            assertEquals("$m: no phantom shortfall", 0.0, p.oversold, 1e-9)
            assertEquals("$m: basis is the last buy", 330.0, p.costBasis, 1e-6)
            assertEquals("$m: realized", 50 * 3.0 + 100 * 2.0, p.realized, 1e-6)
        }
    }

    /**
     * AN ORDER THAT ALREADY MAKES SENSE IS NEVER TOUCHED. Sell the old shares, buy back
     * cheaper, same day: under average cost the buy-first order would blend the two prices
     * (10.50); the entered order gives the true 11.00.
     */
    @Test fun `a consistent same-day order is kept exactly as entered`() {
        val txns = listOf(
            buy(1, 100.0, 10.0, d1),
            sell(2, 100.0, 12.0, d2),
            buy(3, 100.0, 11.0, d2)
        )
        val p = pos(txns, Ledger.AVERAGE)
        assertEquals(100.0, p.shares, 1e-9)
        assertEquals("sold at the old basis", 200.0, p.realized, 1e-6)
        assertEquals("bought back at 11, not blended", 11.0, p.avgCost, 1e-9)
    }

    /** Rows with a real time of day are information, and keep their order. */
    @Test fun `trades with distinct timestamps are never reordered`() {
        val txns = listOf(
            sell(1, 10.0, 12.0, d1 + 3_600_000L),
            buy(2, 10.0, 10.0, d1 + 7_200_000L)
        )
        for (m in methods) {
            val p = pos(txns, m)
            assertEquals("$m: the sale really came first - reported, not guessed away",
                10.0, p.oversold, 1e-9)
            assertEquals("$m", 10.0, p.shares, 1e-9)
        }
    }

    /** Other symbols' rows on the same day are left in their slots. */
    @Test fun `repairing one symbol leaves another alone`() {
        val txns = listOf(
            sell(1, 100.0, 12.0, d1),
            buy(2, 5.0, 100.0, d1, sym = "NVDA"),
            buy(3, 100.0, 10.0, d1),
            sell(4, 5.0, 110.0, d1, sym = "NVDA")
        )
        val order = Ledger.replayOrder(txns).map { it.id }
        assertEquals(listOf(3L, 2L, 1L, 4L), order)
    }

    // ---------------------------------------------------------------- A-M3, same-date split

    @Test fun `a split on the same date as a buy is applied before it`() {
        val txns = listOf(
            buy(1, 10.0, 1200.0, d1),
            buy(2, 50.0, 130.0, d2),       // entered first, bought at the post-split price
            split(3, 10.0, d2)
        )
        for (m in methods) {
            val p = pos(txns, m)
            assertEquals("$m: 100 split-adjusted + 50 bought after", 150.0, p.shares, 1e-9)
            assertEquals("$m: basis", 12_000.0 + 6_500.0, p.costBasis, 1e-6)
        }
    }

    // ---------------------------------------------------------------- A-H2, import order

    @Test fun `a newest-first multi-day batch is inserted oldest first`() {
        val batch = listOf(sell(0, 1.0, 5.0, d3), buy(0, 2.0, 5.0, d2), buy(0, 3.0, 5.0, d1))
        assertEquals(listOf(3.0, 2.0, 1.0), ImportOrder.chronological(batch).map { it.quantity })
    }

    @Test fun `a single day is taken to be newest first, as the screen shows it`() {
        val batch = listOf(buy(0, 1.0, 11.0, d1), sell(0, 2.0, 12.0, d1), buy(0, 3.0, 10.0, d1))
        assertEquals(listOf(3.0, 2.0, 1.0), ImportOrder.chronological(batch).map { it.quantity })
    }

    @Test fun `a batch that is already oldest first is kept`() {
        val batch = listOf(buy(0, 1.0, 5.0, d1), buy(0, 2.0, 5.0, d1), sell(0, 3.0, 5.0, d2))
        assertEquals(listOf(1.0, 2.0, 3.0), ImportOrder.chronological(batch).map { it.quantity })
    }

    /** Page two picked before page one: each page newest-first, the pages out of order. */
    @Test fun `screenshots picked out of order still come out oldest first`() {
        val batch = listOf(
            buy(0, 1.0, 5.0, d2), sell(0, 2.0, 5.0, d1), buy(0, 3.0, 5.0, d1),   // page 2
            sell(0, 4.0, 5.0, d3), buy(0, 5.0, 5.0, d3)                           // page 1
        )
        assertEquals(listOf(3.0, 2.0, 1.0, 5.0, 4.0),
            ImportOrder.chronological(batch).map { it.quantity })
    }

    // ---------------------------------------------------------------- A-M4, partial fills

    @Test fun `one stored row absorbs one extracted row, not every identical copy`() {
        val fill = buy(0, 50.0, 10.0, d1)
        val stored = listOf(fill.copy(id = 7))
        // Two identical rows extracted, one of them already on file.
        val kinds = ImportDupes.classify(listOf(fill, fill, buy(0, 1.0, 3.0, d1))) { t, claimed ->
            stored.firstOrNull { it.id !in claimed && ImportDupes.fingerprint(it) == ImportDupes.fingerprint(t) }?.id
        }
        assertEquals(listOf(ImportDup.ON_FILE, ImportDup.REPEAT, ImportDup.NEW), kinds)
    }

    @Test fun `two identical new fills are both shown - the second as a possible repeat`() {
        val fill = buy(0, 50.0, 10.0, d1)
        val kinds = ImportDupes.classify(listOf(fill, fill)) { _, _ -> null }
        assertEquals(listOf(ImportDup.NEW, ImportDup.REPEAT), kinds)
    }

    @Test fun `two stored fills absorb two extracted copies`() {
        val fill = buy(0, 50.0, 10.0, d1)
        val stored = listOf(fill.copy(id = 7), fill.copy(id = 8))
        val kinds = ImportDupes.classify(listOf(fill, fill)) { t, claimed ->
            stored.firstOrNull { it.id !in claimed && ImportDupes.fingerprint(it) == ImportDupes.fingerprint(t) }?.id
        }
        assertEquals(listOf(ImportDup.ON_FILE, ImportDup.ON_FILE), kinds)
    }

    // ---------------------------------------------------------------- A-L7, 2-digit years

    @Test fun `a two-digit year is this century, not antiquity`() {
        assertEquals("2026-09-15", Fmt.iso(Fmt.parseDate("09/15/26")!!))
        assertEquals("2026-09-15", Fmt.iso(Fmt.parseDate("2026-09-15")!!))
        assertEquals("2026-09-05", Fmt.iso(Fmt.parseDate("9/5/2026")!!))
    }
}
