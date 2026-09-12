package com.tj.portfolio

import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LEDGER'S CORE BEHAVIOUR, IN THE GATED SUITE (Part 10 audit).
 *
 * Until this file, the only Kotlin test that drove [Ledger.positions] at all was
 * `SameDayRoundTripTest`, which is about one narrow property. Everything else - the FIFO/
 * average invariants, the reconciliation identity, the fee schedule - lived in
 * `tests/LedgerPropTest.java` and `tests/ledger_props.py`: harnesses that have to be
 * compiled or invoked BY HAND and that `ship.sh` never runs. The most consequential
 * arithmetic in the app was therefore gated by almost nothing.
 */
class LedgerTest {

    private val day = 86_400_000L
    private val session = 1_756_909_800_000L      // 2025-09-03 ~13:50 UTC, a Wednesday

    private fun buy(qty: Double, px: Double, at: Long, sym: String = "ONDS", fees: Double = 0.0) =
        Txn(type = TxnType.BUY, symbol = sym, quantity = qty, price = px,
            amount = -(qty * px) - fees, fees = fees, date = at)

    private fun sell(qty: Double, px: Double, at: Long, sym: String = "ONDS", fees: Double = 0.0) =
        Txn(type = TxnType.SELL, symbol = sym, quantity = qty, price = px,
            amount = qty * px - fees, fees = fees, date = at)

    private fun pos(txns: List<Txn>, method: String, sym: String = "ONDS") =
        Ledger.positions(txns, method = method, sessionInstant = session).first { it.symbol == sym }

    // ============================================================ overselling

    /**
     * SELLING MORE THAN THE RECORDS HOLD IS REPORTED, NOT GUESSED AT.
     *
     * See [com.tj.portfolio.domain.Position.oversold] for why this is surfaced rather than
     * resolved: the arithmetic is the only honest one available (proceeds in full, cost for
     * what was actually on the books), but whether the cause is a missing buy or a short
     * sale is not something the ledger can know, and the two want opposite treatments.
     */
    @Test fun `a sale bigger than the position records the shortfall`() {
        val txns = listOf(
            buy(4.0, 100.0, session - 40 * day),
            sell(10.0, 150.0, session - 20 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: six shares were sold that were never bought",
                6.0, p.oversold, 1e-9)
            assertEquals("$method: the position is closed", 0.0, p.shares, 1e-9)
            // The money genuinely arrived, so all of it is booked - the realized figure is
            // overstated by whatever the missing buy cost, which is what the UI now says.
            assertEquals("$method: proceeds booked in full", 1500.0 - 400.0, p.realized, 1e-6)
        }
    }

    /** An ordinary history reports nothing - this must stay silent unless it is real. */
    @Test fun `a normal history has no shortfall`() {
        val txns = listOf(
            buy(10.0, 100.0, session - 40 * day),
            sell(4.0, 150.0, session - 20 * day),
            buy(5.0, 120.0, session - 10 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            assertEquals(method, 0.0, pos(txns, method).oversold, 1e-9)
        }
    }

    /** Both replays must count the same shortfall - they derive it independently. */
    @Test fun `both methods agree on the shortfall across repeated oversells`() {
        val txns = listOf(
            sell(3.0, 50.0, session - 50 * day),        // sold with nothing on the books
            buy(2.0, 40.0, session - 40 * day),
            sell(5.0, 60.0, session - 30 * day),        // 2 covered, 3 not
            buy(1.0, 45.0, session - 20 * day)
        )
        val f = pos(txns, Ledger.FIFO)
        val a = pos(txns, Ledger.AVERAGE)
        assertEquals("3 + 3 shares sold that were never bought", 6.0, f.oversold, 1e-9)
        assertEquals(f.oversold, a.oversold, 1e-9)
        assertEquals(f.shares, a.shares, 1e-9)
    }

    /**
     * THE INTERPRETATION, PINNED DELIBERATELY. A buy after an uncovered sale opens a LONG
     * position - it is not treated as covering a short. That is the right answer for the
     * missing-buy case, which is overwhelmingly the likelier one in a ledger fed by
     * screenshot imports, and short selling is nowhere in this app's scope. The shortfall
     * is what tells the user the records need a correction.
     */
    @Test fun `a buy after an uncovered sale opens a long, and says so`() {
        val txns = listOf(
            sell(100.0, 50.0, session - 40 * day),
            buy(100.0, 40.0, session - 30 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: the later buy is a long position", 100.0, p.shares, 1e-9)
            assertEquals("$method: and the discrepancy is still reported",
                100.0, p.oversold, 1e-9)
        }
    }

    /** Correcting the POSITION with an override does not un-say what the history shows. */
    @Test fun `an override does not clear the shortfall`() {
        val txns = listOf(
            buy(4.0, 100.0, session - 40 * day),
            sell(10.0, 150.0, session - 20 * day)
        )
        val ov = mapOf("ONDS" to Override("ONDS", avgCost = null, shares = 12.0))
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = Ledger.positions(txns, ov, method, session).first { it.symbol == "ONDS" }
            assertEquals("$method", 12.0, p.shares, 1e-9)
            assertTrue("$method: override applied", p.overridden)
            assertEquals("$method: the records still disagree with themselves",
                6.0, p.oversold, 1e-9)
        }
    }

    // ==================================================== the invariant both methods share

    /**
     * The identity this project has always claimed: the cost method moves the split between
     * realized and unrealized, never the total. Asserted here in the GATED suite rather than
     * only in the hand-run harnesses.
     */
    @Test fun `total lifetime P-L is identical under both methods`() {
        val txns = listOf(
            buy(10.0, 100.0, session - 60 * day, fees = 1.0),
            buy(5.0, 130.0, session - 40 * day),
            sell(7.0, 150.0, session - 30 * day, fees = 0.62),
            buy(3.0, 90.0, session - 10 * day),
            sell(2.0, 95.0, session - 5 * day)
        )
        val price = 120.0
        val f = pos(txns, Ledger.FIFO)
        val a = pos(txns, Ledger.AVERAGE)
        assertEquals("share counts must agree", f.shares, a.shares, 1e-9)
        val lifeF = f.realized + (f.shares * price - f.costBasis)
        val lifeA = a.realized + (a.shares * price - a.costBasis)
        assertEquals("only the realized/unrealized split may differ", lifeF, lifeA, 1e-6)
        // ...and they really do differ, or this test would prove nothing.
        assertTrue("the two methods should split it differently here",
            kotlin.math.abs(f.realized - a.realized) > 1e-6)
    }
}
