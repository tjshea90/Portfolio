package com.tj.portfolio

import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Fees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ALLY'S PUBLISHED FEE SCHEDULE, INSIDE THE GATE (Part 10 audit).
 *
 * ---- WHY THIS FILE EXISTS
 *
 * These cases existed - in `tests/LedgerPropTest.java`, a plain Java file compiled by hand
 * against the release bytecode per `tests/README.md`. Nothing runs it automatically, so
 * `./gradlew testDebugUnitTest` - the suite `ship.sh` actually gates on - had NO coverage of
 * [Fees] at all, on either side of the schedule.
 *
 * That matters because these numbers are not decoration. [Fees.forEquityTrade] fills the
 * fee box in the transaction editor, so it lands in the ledger, in cost basis and in every
 * figure derived from them; and [Fees.buyFeeLooksWrong] drives the Settings warning that
 * tells TJ a recorded fee is implausible. A silent regression in either is exactly the
 * "confident, plausible, wrong" failure this project's tests exist to prevent, and it could
 * ship without a single automated check noticing.
 *
 * The rates themselves are a moving target - the file's own header records that the FINRA
 * TAF is being phased up through 2029 and that an earlier version of it shipped stale rates
 * copied from a footnote that had not caught up. So these assert the SCHEDULE AS CHECKED on
 * the date in [Fees.RATES_CHECKED]: if a rate is updated deliberately, this file is supposed
 * to fail and be updated with it. That is the point, not an inconvenience.
 */
class FeesTest {

    private fun total(type: String, shares: Double, price: Double) =
        Fees.forEquityTrade(type, shares, price).total

    // ------------------------------------------------------------ the two headline rules

    /** Rule one: buying an ordinary stock or ETF costs nothing at all. */
    @Test fun `an ordinary buy is free`() {
        assertEquals(0.00, total(TxnType.BUY, 100.0, 50.0), 0.005)
        assertEquals(0.00, total(TxnType.BUY, 1.0, 291.06), 0.005)
        // The $2.00 boundary exactly - "under $2" is strict, so this is an ordinary trade.
        assertEquals(0.00, total(TxnType.BUY, 100.0, 2.00), 0.005)
    }

    /**
     * Rule two: a security under $2 is charged on BOTH sides - $4.95 + 1c/share, and never
     * more than 5% of what the trade is worth.
     */
    @Test fun `a low-priced buy carries a commission, capped at five percent`() {
        // 4.95 + 100c = 5.95 raw, but 5% of the $100 trade is 5.00, so the cap binds.
        assertEquals(5.00, total(TxnType.BUY, 100.0, 1.00), 0.005)
        assertEquals(5.00, Fees.lowPricedCommission(100.0, 1.00), 0.005)
        // And where it does NOT bind, the raw schedule stands: 1,000 shares at $1.99 is
        // 4.95 + $10.00 = $14.95, against a 5% cap of $99.50 on the $1,990 trade.
        assertEquals(14.95, Fees.lowPricedCommission(1000.0, 1.99), 0.005)
        // A small trade is nearly all cap: 10 shares at $1.80 caps at 5% of $18.
        assertEquals(0.90, Fees.lowPricedCommission(10.0, 1.80), 0.005)
        assertEquals(0.00, Fees.lowPricedCommission(100.0, 2.00), 0.005)
    }

    // ------------------------------------------------------------ the sell side

    /**
     * Both regulatory fees are levied on SALES only, and they round differently: the SEC fee
     * goes UP to the next cent, the TAF to the nearest one.
     */
    @Test fun `a sale pays the SEC fee and the TAF`() {
        // One share at 291.06: SEC fee on $291.06 is 0.0060 -> rounded up to a whole cent.
        assertEquals(0.01, total(TxnType.SELL, 1.0, 291.06), 0.005)
        assertEquals(0.62, total(TxnType.SELL, 100.0, 291.06), 0.005)
    }

    /** The TAF is capped per trade, which only a very large sale reaches. */
    @Test fun `the TAF stops at its per-trade maximum`() {
        val b = Fees.forEquityTrade(TxnType.SELL, 100_000.0, 50.0)
        assertEquals("the TAF should be pinned at its cap", Fees.TAF_MAX, b.taf, 0.005)
        assertEquals(103.00 + Fees.TAF_MAX, b.total, 0.005)
    }

    /**
     * A low-priced SALE pays the commission AND the regulatory fees - and the 5% cap applies
     * to the commission alone, not to the total, so the SEC fee sits on top of it.
     */
    @Test fun `a low-priced sale pays the capped commission plus the regulatory fees`() {
        // 5% of a $15 sale is 0.75 commission, plus the SEC fee rounded up to a cent.
        assertEquals(0.76, total(TxnType.SELL, 10.0, 1.50), 0.005)
    }

    // ------------------------------------------------------------ backdated trades

    /**
     * A requested audit found this one: the fee box auto-fills from [Fees.forEquityTrade]
     * with no idea what date the user actually entered, so a sale backfilled from an old
     * statement got 2026's current SEC/TAF rates silently written in - wrong for a trade dated
     * before those rates existed. A trade dated before [Fees.RATES_EFFECTIVE_MS] gets NO
     * auto-computed regulatory fee at all now, rather than a number computed at the wrong rate -
     * the same "a blank is safer than a guess" rule this app follows elsewhere.
     */
    @Test fun `a sale dated before the rates took effect gets no regulatory fee guessed`() {
        val before = Fees.RATES_EFFECTIVE_MS - 86_400_000L
        val b = Fees.forEquityTrade(TxnType.SELL, 100.0, 291.06, tradeDate = before)
        assertEquals("no SEC fee guessed for an old trade", 0.0, b.secFee, 1e-9)
        assertEquals("no TAF guessed for an old trade", 0.0, b.taf, 1e-9)
    }

    /** The low-priced commission is Ally's own, not a rate that changed over time - it still
     *  applies to a backdated low-priced trade. */
    @Test fun `a backdated low-priced sale still pays Ally's own commission`() {
        val before = Fees.RATES_EFFECTIVE_MS - 86_400_000L
        val b = Fees.forEquityTrade(TxnType.SELL, 10.0, 1.50, tradeDate = before)
        assertEquals(0.75, b.commission, 0.005)
        assertEquals("no regulatory fee guessed", 0.0, b.secFee + b.taf, 1e-9)
    }

    /** A sale dated ON or AFTER the effective date is unaffected - same numbers as no date at all. */
    @Test fun `a sale dated on or after the effective date uses the current schedule`() {
        val on = Fees.RATES_EFFECTIVE_MS
        val withDate = Fees.forEquityTrade(TxnType.SELL, 100.0, 291.06, tradeDate = on).total
        val noDate = Fees.forEquityTrade(TxnType.SELL, 100.0, 291.06).total
        assertEquals(noDate, withDate, 1e-9)
    }

    // ------------------------------------------------------------ the guards

    @Test fun `a zero-share or unpriced trade has no fee`() {
        assertEquals(0.00, total(TxnType.BUY, 0.0, 50.0), 1e-9)
        assertEquals(0.00, total(TxnType.SELL, 100.0, 0.0), 1e-9)
        assertTrue(Fees.forEquityTrade(TxnType.BUY, 0.0, 50.0).isZero)
    }

    /** A non-trade row never carries a trade fee, whatever else is on it. */
    @Test fun `only buys and sells are charged`() {
        for (t in listOf(TxnType.DEPOSIT, TxnType.WITHDRAWAL, TxnType.DIVIDEND,
                         TxnType.INTEREST, TxnType.FEE)) {
            assertEquals(t, 0.00, total(t, 100.0, 50.0), 1e-9)
        }
    }

    /** Sign is irrelevant - a sell is recorded with a positive or negative quantity. */
    @Test fun `share count is taken as an absolute value`() {
        assertEquals(total(TxnType.SELL, 100.0, 291.06), total(TxnType.SELL, -100.0, 291.06), 1e-9)
    }

    // ------------------------------------------- the warning this drives in Settings

    /**
     * [Fees.buyFeeLooksWrong] is deliberately one-sided: it only reports a charge on a BUY
     * bigger than the schedule allows. A low-priced buy really does carry a commission, and
     * sell-side cents depend on which rates were in force on the day - both changed during
     * 2026 - so neither is ever questioned.
     */
    @Test fun `an impossible fee on an ordinary buy is reported`() {
        assertTrue(Fees.buyFeeLooksWrong(TxnType.BUY, 100.0, 50.0, 6.95))
        assertFalse("a free buy with no fee recorded is correct",
            Fees.buyFeeLooksWrong(TxnType.BUY, 100.0, 50.0, 0.0))
    }

    @Test fun `a legitimate low-priced buy commission is left alone`() {
        // The real charge on this trade is the 5%-capped $5.00; recording it is not an error.
        assertFalse(Fees.buyFeeLooksWrong(TxnType.BUY, 100.0, 1.00, 5.00))
        // A cent over the schedule still is.
        assertTrue(Fees.buyFeeLooksWrong(TxnType.BUY, 100.0, 1.00, 5.50))
    }

    @Test fun `sales are never questioned`() {
        assertFalse(Fees.buyFeeLooksWrong(TxnType.SELL, 100.0, 50.0, 99.0))
    }

    // ------------------------------------------------------------ the rates themselves

    /**
     * The constants, asserted as literals. An earlier version of this file shipped the OLD
     * FINRA rates ($0.000166 / $8.30) copied from a rule page whose footnote had not caught
     * up with the 2026 phase-in - a change nothing would have caught. If these are updated
     * deliberately, update the date in [Fees.RATES_CHECKED] with them.
     */
    @Test fun `the published rates are the ones this schedule was checked against`() {
        assertEquals(20.60, Fees.SEC_FEE_PER_MILLION, 1e-9)
        assertEquals(0.000195, Fees.TAF_PER_SHARE, 1e-12)
        assertEquals(9.79, Fees.TAF_MAX, 1e-9)
        assertEquals(2.00, Fees.LOW_PRICED_UNDER, 1e-9)
        assertEquals(4.95, Fees.LOW_PRICED_BASE, 1e-9)
        assertEquals(0.01, Fees.LOW_PRICED_PER_SHARE, 1e-9)
        assertEquals(0.05, Fees.LOW_PRICED_MAX_PCT, 1e-9)
        assertTrue("the schedule should say when it was last checked",
            Fees.RATES_CHECKED.isNotBlank() && Fees.SOURCE_NOTE.contains(Fees.RATES_CHECKED))
    }
}
