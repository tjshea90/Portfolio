package com.tj.portfolio

import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.ui.TxnFields
import com.tj.portfolio.ui.toNum
import com.tj.portfolio.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPENING A TRANSACTION AND SAVING IT UNCHANGED MUST CHANGE NOTHING (Round 66).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * The editor seeded its boxes from the DISPLAY formatters - `Fmt.priceBare` (two or three
 * decimals) and `Fmt.shares` (four). Prices in this ledger routinely carry more, because
 * `Txn.unitPriceFromTotal` derives them from a net total: a 1,000-share buy for $1,559.50 is
 * stored at 1.5595.
 *
 * On Save the boxes are parsed back and `Txn.cashEffect` recomputes the cash from
 * quantity x price - it falls back to the stored total only when one of the two is missing.
 * So the rounded "1.560" became the new truth and the row was rewritten as $1,560.00: fifty
 * cents of drift in the cash balance, the cost basis, and every figure derived from them,
 * from opening a transaction and pressing Save without touching it.
 *
 * ---- WHY THIS TEST IS PURE
 *
 * It drives [TxnFields], which is the code the dialog itself calls for both its seeds and its
 * Save - so this cannot pass while the screen does something else. Rendering the real
 * `AlertDialog` under Robolectric was tried first and proved slow enough to trip Espresso's
 * idle timeout intermittently, and a flaky test guarding the ledger is worse than none.
 */
class TxnEditorTest {

    /** Exactly what the dialog does: seed the four boxes, then Save without touching them. */
    private fun untouchedSave(t: Txn): Txn {
        val q = TxnFields.qty(t)
        val p = TxnFields.price(t)
        val a = TxnFields.amount(t)
        val f = TxnFields.fees(t)
        val r = TxnFields.resolve(t.type, q, p, a, f)
        return t.copy(
            quantity = r.quantity,
            price = r.price,
            amount = TxnFields.cashOf(t.type, r),
            fees = r.fees
        )
    }

    @Test fun `a four-decimal price survives an untouched save`() {
        // Exactly the shape `Txn.unitPriceFromTotal` produces from a $1,559.50 net total.
        val original = Txn(
            id = 7L, type = TxnType.BUY, symbol = "ONDS",
            quantity = 1000.0, price = 1.5595, amount = -1559.50, fees = 0.0,
            date = 1_756_000_000_000L, source = "IMPORT"
        )
        val saved = untouchedSave(original)
        assertEquals("the price was re-rounded by opening the editor",
            1.5595, saved.price, 1e-9)
        assertEquals(
            "an untouched save moved the recorded cash by " +
                "${"%.2f".format(saved.amount - original.amount)}",
            -1559.50, saved.amount, 1e-6
        )
        assertEquals(1000.0, saved.quantity, 1e-9)
    }

    @Test fun `a fractional share count survives an untouched save`() {
        val original = Txn(
            id = 8L, type = TxnType.BUY, symbol = "NVDA",
            quantity = 0.123456, price = 230.115, amount = -28.4059, fees = 0.0,
            date = 1_756_000_000_000L, source = "IMPORT"
        )
        val saved = untouchedSave(original)
        assertEquals("the share count was re-rounded", 0.123456, saved.quantity, 1e-12)
        assertEquals("the price was re-rounded", 230.115, saved.price, 1e-9)
    }

    @Test fun `a cash transaction keeps its exact amount`() {
        val original = Txn(
            id = 9L, type = TxnType.DEPOSIT, symbol = null,
            quantity = 0.0, price = 0.0, amount = 12345.67, fees = 0.0,
            date = 1_756_000_000_000L, source = "MANUAL"
        )
        assertEquals(12345.67, untouchedSave(original).amount, 1e-9)
    }

    @Test fun `a sell keeps its sign and its fee`() {
        val original = Txn(
            id = 10L, type = TxnType.SELL, symbol = "ONDS",
            quantity = 45.0, price = 7.565, amount = 340.42, fees = 4.95,
            date = 1_756_000_000_000L, source = "IMPORT"
        )
        val saved = untouchedSave(original)
        assertEquals(7.565, saved.price, 1e-9)
        assertEquals(4.95, saved.fees, 1e-9)
        // cashEffect for a SELL is gross - fees, and gross here is 45 x 7.565 = 340.425.
        assertEquals(340.425 - 4.95, saved.amount, 1e-6)
        assertTrue("a sell must still credit cash", saved.amount > 0)
    }

    /**
     * THE ROUND TRIP THIS ALL RESTS ON: whatever the editor prints, its own parser must read
     * back as the identical double. A formatter that is merely "close" is what caused the bug.
     */
    @Test fun `every seeded field round-trips through the editor's own parser`() {
        val values = listOf(
            1.5595, 230.115, 0.123456, 12345.67, 5.0, 0.0001, 1_234_567.89, 7.565,
            0.000_001, 999_999.999_9
        )
        for (v in values) {
            val text = Fmt.exact(v)
            assertTrue(
                "\"$text\" is in scientific notation and the editor's parser returns 0 for it",
                !text.contains('E') && !text.contains('e')
            )
            assertEquals("Fmt.exact($v) -> \"$text\" did not read back", v, text.toNum(), 1e-12)
        }
        assertEquals("0", Fmt.exact(0.0))
        assertEquals("a whole number should not read as 5.0000", "5", Fmt.exact(5.0))
        assertEquals("", Fmt.exact(Double.NaN))
    }

    /** A blank or absent field must stay blank rather than becoming a zero the user must clear. */
    @Test fun `absent fields seed empty`() {
        val t = Txn(
            id = 1L, type = TxnType.BUY, symbol = "X",
            quantity = 3.0, price = 0.0, amount = -30.0, fees = 0.0,
            date = 1L, source = "MANUAL"
        )
        assertEquals("", TxnFields.price(t))
        assertEquals("", TxnFields.fees(t))
        assertEquals("3", TxnFields.qty(t))
        assertEquals("30", TxnFields.amount(t))
    }

    /**
     * And the derivation still works when only a total is known - the import path's shape.
     * A $1,559.50 net total over 1,000 shares must come back out as 1.5595, not 1.56.
     */
    @Test fun `a price derived from a total keeps its fourth decimal`() {
        val r = TxnFields.resolve(TxnType.BUY, "1000", "", "1559.50", "0")
        assertEquals(1.5595, r.price, 1e-9)
        assertEquals(-1559.50, TxnFields.cashOf(TxnType.BUY, r), 1e-6)
    }

    /**
     * THE NaN/Infinity BUG. `toDoubleOrNull()` parses the literal text "NaN" or "Infinity" into
     * a real, non-finite Double, and every "<= 0 means invalid" guard in the dialog (qNum, pNum,
     * aNum) is an IEEE-754 comparison that evaluates false against a non-finite value - so typing
     * that text used to sail straight past validation and into the ledger. `toNum()` must
     * collapse any non-finite parse to 0.0, which every existing guard already rejects.
     */
    @Test fun `NaN and Infinity text never parse to a non-finite number`() {
        for (bad in listOf("NaN", "-NaN", "Infinity", "-Infinity", "+Infinity")) {
            val n = bad.toNum()
            assertTrue("\"$bad\".toNum() = $n, which is not finite", n.isFinite())
            assertEquals("\"$bad\".toNum() should collapse to 0.0", 0.0, n, 0.0)
        }
    }

    /**
     * A SPLIT row carries its RATIO in `quantity` and must carry nothing else - see
     * [com.tj.portfolio.data.TxnType.SPLIT]. Resolved clean even when the price, total and
     * fee boxes still hold whatever was typed before the type was switched, since those
     * fields are not on screen for a split and a stray figure on the row would be invisible.
     */
    @Test fun `a split resolves to a bare ratio and moves no cash`() {
        val clean = TxnFields.resolve(TxnType.SPLIT, "10", "", "", "")
        assertEquals(10.0, clean.quantity, 1e-9)
        assertEquals(0.0, clean.price, 1e-9)
        assertEquals(0.0, clean.amount, 1e-9)
        assertEquals(0.0, clean.fees, 1e-9)
        assertEquals(0.0, TxnFields.cashOf(TxnType.SPLIT, clean), 1e-12)

        val stale = TxnFields.resolve(TxnType.SPLIT, "0.1", "55", "999", "3")
        assertEquals("the ratio survives", 0.1, stale.quantity, 1e-9)
        assertEquals("a leftover price must not", 0.0, stale.price, 1e-9)
        assertEquals("nor a leftover total", 0.0, stale.amount, 1e-9)
        assertEquals("nor a leftover fee", 0.0, stale.fees, 1e-9)
        assertEquals(0.0, TxnFields.cashOf(TxnType.SPLIT, stale), 1e-12)
    }

    @Test fun `a NaN quantity cannot resolve into a savable transaction`() {
        val r = TxnFields.resolve(TxnType.BUY, "NaN", "10", "", "0")
        // qty collapses to 0.0, which every "isTrade && qNum <= 0" guard in the dialog rejects -
        // it must not silently become a real (if wrong) share count.
        assertEquals(0.0, r.quantity, 0.0)
    }
}
