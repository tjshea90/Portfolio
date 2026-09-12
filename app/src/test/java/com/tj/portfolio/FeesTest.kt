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
        // Well above the cap's reach: 4.95 + 10c = 5.05 against 5% of $1,800 = $90.
        assertEquals(5.05, Fees.lowPricedCommission(10.0, 180.0 / 1.0 * 0.0 + 1.80), 0.005)
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
