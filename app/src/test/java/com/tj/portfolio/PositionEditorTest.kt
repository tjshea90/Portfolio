package com.tj.portfolio

import com.tj.portfolio.ui.PositionFields
import com.tj.portfolio.ui.toNum
import com.tj.portfolio.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE POSITION EDITOR'S SEEDS MUST SURVIVE A ROUND TRIP (Round 66 audit, PUI-1).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * "Edit shares / average cost" pre-fills two boxes with the position's current numbers and
 * saves whatever is in them back as a permanent `Override`. It seeded them with the DISPLAY
 * formatters - `Fmt.shares` (`#,##0.####`) and `Fmt.priceBare` (two or three decimals) - so
 * the rounding done for reading became the value written to the ledger, applied by pressing
 * Save without typing anything at all.
 *
 * `avgCost` is `costBasis / shares`, which almost never lands that short: 3,000 shares bought
 * for $1,270.65 average 0.42355 a share. Seeded as "0.424", saved back, `Ledger.applyOverride`
 * recomputes the cost as `shares * avgCost` = $1,272.00 - and the position is now pinned to a
 * manual override that later buys will not update.
 *
 * This is the identical bug `Fmt.exact` was written for in the TRANSACTION editor in this
 * same round. The other editor that writes to the ledger was missed.
 *
 * The invariant, and the only one that matters: seed -> parse must return the value unchanged.
 */
class PositionEditorTest {

    /**
     * The invariant is materiality, not bit-equality. [Fmt.exact] rounds to 12 significant
     * digits so a quotient like `1270.65 / 3000.0` seeds as "0.42355" rather than as the
     * seventeen digits its nearest double actually needs - see the KDoc there. A relative
     * error of 1e-12 on a $100,000 position is a ten-millionth of a cent; a VISIBLE rounding,
     * which is what this test exists to catch, is thousands of times larger than that.
     */
    private fun assertRoundTrips(what: String, v: Double, seeded: String) {
        val back = seeded.toNum()
        val slack = kotlin.math.max(kotlin.math.abs(v) * 1e-11, 1e-12)
        assertEquals("$what $v seeded as \"$seeded\"", v, back, slack)
    }

    private fun roundTripsCost(v: Double) =
        assertRoundTrips("cost", v, PositionFields.cost(v))

    private fun roundTripsShares(v: Double) =
        assertRoundTrips("shares", v, PositionFields.shares(v))

    /** The exact case from the bug report. */
    @Test fun subDollarAverageCostSurvives() {
        val avg = 1270.65 / 3000.0          // 0.42355
        roundTripsCost(avg)
        assertEquals("0.42355", PositionFields.cost(avg))
    }

    /** A four-decimal price, which `Txn.unitPriceFromTotal` produces routinely. */
    @Test fun fourDecimalPriceSurvives() = roundTripsCost(1.5595)

    /** A fractional DRIP share count, which `Fmt.shares` rounded to four places. */
    @Test fun fractionalSharesSurvive() = roundTripsShares(12.345678)

    /** Ordinary whole numbers must not gain decimals or grouping commas. */
    @Test fun wholeNumbersStayPlain() {
        assertEquals("3000", PositionFields.shares(3000.0))
        assertEquals("150", PositionFields.cost(150.0))
    }

    /**
     * The seed must never contain a grouping comma. `toNum` strips them, so a comma is not a
     * crash - but it is what `Fmt.shares` would have produced, and the value shown to the
     * user must be the value that is saved.
     */
    @Test fun noGroupingCommas() {
        assertEquals(false, PositionFields.shares(1_234_567.0).contains(","))
    }

    /** Nothing to edit means an empty box, not "0". */
    @Test fun blankWhenThereIsNothingToSeed() {
        assertEquals("", PositionFields.shares(0.0))
        assertEquals("", PositionFields.cost(0.0))
        assertEquals("", PositionFields.shares(-1.0))
        assertEquals("", PositionFields.cost(-1.0))
    }

    /** A spread of real-looking values, every one of them round-tripped. */
    @Test fun aSpreadOfRealValuesAllSurvive() {
        listOf(
            0.0001, 0.42355, 1.5595, 12.345678, 87.33, 199.999, 1234.5678, 98765.4321
        ).forEach { roundTripsCost(it); roundTripsShares(it) }
    }
}
