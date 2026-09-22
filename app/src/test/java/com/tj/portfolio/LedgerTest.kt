package com.tj.portfolio

import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE LEDGER'S CORE BEHAVIOUR, IN THE GATED SUITE (Part 10 audit).
 *
 * Until this file, the only Kotlin test that drove [Ledger.positions] at all was
 * `SameDayRoundTripTest`, which is about one narrow property. Everything else - the FIFO/
 * average invariants, the reconciliation identity, the fee schedule - lived in
 * `tests/LedgerPropTest.java` and `tests/ledger_props.py`: harnesses that have to be
 * compiled or invoked BY HAND and that `ship.sh` never runs. The most consequential
 * arithmetic in the app was therefore gated by almost nothing.
 *
 * RUN UNDER ROBOLECTRIC even though the ledger itself is pure, because one test here drives
 * `ClaudeBridge.parse`, and `org.json` is an ANDROID class: in a plain JVM unit test it is
 * the stubbed `android.jar` version, where every `JSONObject(...)` throws and any parse
 * quietly comes back empty. A test asserting "the import path dropped this row" would then
 * pass for entirely the wrong reason - it drops every row, including the ones it should
 * keep - which is precisely how a guard gets pinned by a test that proves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    // ================================================================ stock splits

    private fun split(ratio: Double, at: Long, sym: String = "ONDS") =
        Txn(type = TxnType.SPLIT, symbol = sym, quantity = ratio,
            amount = Txn.cashEffect(TxnType.SPLIT, ratio, 0.0, 0.0, 0.0), date = at)

    /**
     * THE CASE THE FEATURE EXISTS FOR. Ten times the shares at a tenth of the price, and the
     * money unchanged - which is what a split is. Before this, the ledger kept the pre-split
     * count forever and under-reported the position by the whole ratio.
     */
    @Test fun `a ten for one split multiplies the shares and leaves the basis alone`() {
        val txns = listOf(
            buy(10.0, 1200.0, session - 60 * day),
            split(10.0, session - 30 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: shares", 100.0, p.shares, 1e-9)
            assertEquals("$method: total cost basis is untouched", 12_000.0, p.costBasis, 1e-6)
            assertEquals("$method: so the cost per share is divided by the ratio",
                120.0, p.avgCost, 1e-9)
            assertEquals("$method: nothing is realized by a split", 0.0, p.realized, 1e-9)
        }
        // And the split row itself moves no cash: the balance is the buy's, unchanged by it.
        assertEquals(-12_000.0, Ledger.cash(txns), 1e-9)
        assertEquals("adding the split must not touch cash",
            Ledger.cash(txns.filter { it.type != TxnType.SPLIT }), Ledger.cash(txns), 1e-9)
    }

    /** And the same arithmetic backwards, for a reverse split. */
    @Test fun `a one for ten reverse split divides the shares`() {
        val txns = listOf(
            buy(500.0, 2.0, session - 60 * day),
            split(0.1, session - 30 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: shares", 50.0, p.shares, 1e-9)
            assertEquals("$method: basis", 1000.0, p.costBasis, 1e-6)
            assertEquals("$method: cost per share", 20.0, p.avgCost, 1e-9)
        }
    }

    /** Only what was held when the split happened scales - a later buy is already adjusted. */
    @Test fun `a buy after the split is not scaled by it`() {
        val txns = listOf(
            buy(10.0, 1200.0, session - 60 * day),     // -> 100 shares at 120 after the split
            split(10.0, session - 30 * day),
            buy(50.0, 130.0, session - 10 * day)       // bought post-split, at post-split prices
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: shares", 150.0, p.shares, 1e-9)
            assertEquals("$method: basis", 12_000.0 + 6_500.0, p.costBasis, 1e-6)
        }
    }

    /** A sale after a split realizes against the SPLIT-ADJUSTED cost, not the old one. */
    @Test fun `selling after a split realizes against the adjusted basis`() {
        val txns = listOf(
            buy(10.0, 100.0, session - 60 * day),      // basis 1,000 -> 100 shares at 10
            split(10.0, session - 30 * day),
            sell(50.0, 15.0, session - 10 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: shares left", 50.0, p.shares, 1e-9)
            assertEquals("$method: 50 x (15 - 10)", 250.0, p.realized, 1e-6)
            assertEquals("$method: basis left", 500.0, p.costBasis, 1e-6)
        }
    }

    /** The same-day pool is a share count too, so it scales with everything else. */
    @Test fun `a split scales the shares bought today`() {
        val txns = listOf(
            buy(10.0, 100.0, session - 4 * 3_600_000L),
            split(10.0, session - 2 * 3_600_000L)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: shares bought today", 100.0, p.sharesToday, 1e-9)
            assertEquals("$method: at a tenth of the fill price", 10.0, p.avgCostToday, 1e-9)
        }
    }

    /**
     * A requested audit found this one: [Position.oversold] is real share units of the symbol
     * it belongs to ("shares sold beyond what the recorded history could cover"), so a split on
     * that symbol has to rescale it exactly like every other share count on the position - a
     * presumed-missing 3 shares must read as 30 after a 10-for-1 split, not stay pinned at 3.
     */
    @Test fun `a split rescales an existing oversold shortfall too`() {
        val txns = listOf(
            sell(3.0, 50.0, session - 60 * day),      // sold with nothing on the books
            split(10.0, session - 30 * day)
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val p = pos(txns, method)
            assertEquals("$method: shortfall scales with the split", 30.0, p.oversold, 1e-9)
        }
    }

    /**
     * A RATIO THAT MAKES NO SENSE IS IGNORED, NOT APPLIED. Multiplying a holding by zero or
     * by a negative would destroy it outright - the one outcome worse than having no split
     * support at all - so [TxnType.splitRatio] refuses it and the replay skips the row.
     */
    @Test fun `an unusable split ratio leaves the position untouched`() {
        for (bad in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val txns = listOf(
                buy(10.0, 100.0, session - 60 * day),
                split(bad, session - 30 * day)
            )
            for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
                val p = pos(txns, method)
                assertEquals("ratio $bad under $method: shares", 10.0, p.shares, 1e-9)
                assertEquals("ratio $bad under $method: basis", 1000.0, p.costBasis, 1e-6)
            }
        }
    }

    /** A split for a symbol that was never held is a no-op, not a phantom holding. */
    @Test fun `a split on an unheld symbol creates nothing`() {
        val txns = listOf(
            buy(10.0, 100.0, session - 60 * day, sym = "ONDS"),
            split(10.0, session - 30 * day, sym = "NVDA")
        )
        for (method in listOf(Ledger.FIFO, Ledger.AVERAGE)) {
            val all = Ledger.positions(txns, method = method, sessionInstant = session)
            assertTrue("$method: NVDA should not appear", all.none { it.symbol == "NVDA" })
            assertEquals("$method: ONDS untouched", 10.0, pos(txns, method).shares, 1e-9)
        }
    }

    /** Whatever else is on the row, a split can never move the cash balance. */
    @Test fun `a split has no cash effect`() {
        assertEquals(0.0, Txn.cashEffect(TxnType.SPLIT, 10.0, 0.0, 0.0, 0.0), 1e-12)
        // Even if a stray price/amount/fee were somehow recorded on one.
        assertEquals(0.0, Txn.cashEffect(TxnType.SPLIT, 10.0, 55.0, 999.0, 3.0), 1e-12)
    }

    /**
     * A MODEL'S REPLY MAY NEVER CARRY A SPLIT - see [TxnType.IMPORTABLE].
     *
     * Adding SPLIT to [TxnType.ALL] silently widened the accept-list both Claude-fed import
     * paths use, and on a split row `quantity` is a RATIO where every other type reads it as
     * a share count. A reply paraphrasing "Stock split - 90 shares" as
     * `{"type":"SPLIT","quantity":90}` would have been applied as a ninety-fold split. The
     * share-count sanity guards do not catch it either: they are scoped to BUY and SELL.
     */
    @Test fun `an imported reply cannot carry a split`() {
        assertTrue("SPLIT must not be importable", TxnType.SPLIT !in TxnType.IMPORTABLE)
        // Everything else still is - this must not have narrowed anything by accident.
        for (t in TxnType.ALL.filter { it != TxnType.SPLIT }) {
            assertTrue("$t should still be importable", t in TxnType.IMPORTABLE)
        }
        // And the file-import path really does drop the row rather than merely ignoring it.
        val reply = """
            {"transactions":[
              {"type":"SPLIT","symbol":"NVDA","quantity":90,"price":0,"amount":0,"fees":0,
               "date":"2026-06-10"},
              {"type":"BUY","symbol":"NVDA","quantity":5,"price":120.50,"amount":-602.50,
               "fees":0,"date":"2026-06-11"}
            ]}
        """.trimIndent()
        val parsed = com.tj.portfolio.net.ClaudeBridge.parse(reply)
        assertTrue("the split row must be dropped",
            parsed.transactions.none { it.type == TxnType.SPLIT })
        assertEquals("the ordinary buy still imports", 1, parsed.transactions.size)
    }

    /** The lifetime identity has to survive a split under both methods, too. */
    @Test fun `both methods still agree across a split`() {
        val txns = listOf(
            buy(10.0, 100.0, session - 80 * day),
            buy(5.0, 140.0, session - 70 * day),
            split(4.0, session - 60 * day),
            sell(30.0, 40.0, session - 40 * day),
            buy(10.0, 35.0, session - 20 * day)
        )
        val price = 38.0
        val f = pos(txns, Ledger.FIFO)
        val a = pos(txns, Ledger.AVERAGE)
        assertEquals("shares", f.shares, a.shares, 1e-9)
        assertEquals(
            "lifetime P/L",
            f.realized + (f.shares * price - f.costBasis),
            a.realized + (a.shares * price - a.costBasis),
            1e-6
        )
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
    // ---- IMPORTED NUMBERS ARE READ THE WAY A PERSON READS THEM (full-tests audit, A-L6).
    @Test fun `an imported row with currency strings, a signed sell and a yearless date`() {
        val reply = """
            {"transactions":[
              {"type":"BUY","symbol":"ABC","quantity":"10","price":"${'$'}1,227.44",
               "amount":"${'$'}12,274.40","fees":0,"date":"2026-06-11"},
              {"type":"SELL","symbol":"ABC","quantity":-4,"price":1300,"amount":5200,
               "fees":0,"date":"Sep 15"}
            ]}
        """.trimIndent()
        val t = com.tj.portfolio.net.ClaudeBridge.parse(reply).transactions
        assertEquals("both rows survive", 2, t.size)
        val buy = t.first { it.type == TxnType.BUY }
        assertEquals("not a zero-cost buy", 1227.44, buy.price, 1e-9)
        assertEquals(-12_274.40, buy.amount, 1e-6)
        val sell = t.first { it.type == TxnType.SELL }
        assertEquals("a negative share count is still four shares", 4.0, sell.quantity, 1e-9)
        assertTrue("a date that does not parse is flagged: ${sell.note}",
            sell.note?.contains("date estimated") == true)
    }
}
