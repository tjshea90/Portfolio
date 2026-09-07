package com.tj.portfolio.domain

import com.tj.portfolio.data.TxnType
import kotlin.math.ceil
import kotlin.math.round

/**
 * Ally Invest's published commission and fee schedule, plus the regulatory fees every US
 * broker passes through. Taken from ally.com/invest/commissions-and-fees on 5 Sep 2026 and
 * cross-checked against FINRA and the SEC for the pass-through rates.
 *
 * THE TWO RULES THAT MATTER MOST
 *  1. Buying an ordinary stock or ETF costs NOTHING. No commission, and neither regulatory
 *     fee touches a purchase - both are levied on sales.
 *  2. A security trading under $2 is the exception, and it is charged on BOTH sides:
 *     $4.95 base + 1c per share, capped at 5% of the trade value.
 *
 * A CORRECTION WORTH RECORDING. The first version of this file used a FINRA TAF of
 * $0.000166 per share capped at $8.30. Those are the OLD rates. FINRA is phasing in an
 * increase over 2026-2029; the rate in force for 2026 is $0.000195 capped at $9.79, rising
 * to $0.000249 and $12.50 by 2029. The stale figure came from a rule page whose footnote
 * had not caught up - Ally's own fee page had it right. Check the rates against a primary
 * source before trusting them, and update [RATES_CHECKED] when you do.
 */
object Fees {

    const val RATES_CHECKED = "5 Sep 2026"

    const val SOURCE_NOTE =
        "Ally Invest commissions and fees, SEC Section 31 rate (effective 4 Apr 2026) and " +
            "the FINRA Trading Activity Fee rate in force for 2026. Checked $RATES_CHECKED."

    // ---------------------------------------------------------- regulatory

    /** SEC Section 31: per $1,000,000 of principal, on SALES only. Was $0.00 until 4 Apr 2026. */
    const val SEC_FEE_PER_MILLION = 20.60

    /** FINRA TAF, 2026 rate. Rises to $0.000249 / $12.50 max by 2029. Sales only. */
    const val TAF_PER_SHARE = 0.000195
    const val TAF_MAX = 9.79

    /** FINRA TAF and the Options Regulatory Fee, per contract. */
    const val TAF_PER_OPTION_CONTRACT = 0.00329
    const val ORF_PER_CONTRACT = 0.02000

    // ------------------------------------------------------------ Ally's own

    const val LOW_PRICED_UNDER = 2.00
    const val LOW_PRICED_BASE = 4.95
    const val LOW_PRICED_PER_SHARE = 0.01

    /** The low-priced commission can never exceed this share of the trade's value. */
    const val LOW_PRICED_MAX_PCT = 0.05

    const val OPTION_PER_CONTRACT = 0.50

    /** Every component of what a trade costs, so the app can show its working. */
    data class Breakdown(
        val commission: Double = 0.0,
        val secFee: Double = 0.0,
        val taf: Double = 0.0,
        val orf: Double = 0.0
    ) {
        val total: Double get() = commission + secFee + taf + orf
        val isZero: Boolean get() = total < 0.005

        fun explain(): String {
            if (isZero) return "No fees - Ally charges no commission on stock and ETF trades."
            val parts = ArrayList<String>()
            if (commission > 0.004) parts.add("commission ${money(commission)}")
            if (secFee > 0.004) parts.add("SEC fee ${money(secFee)}")
            if (taf > 0.004) parts.add("FINRA TAF ${money(taf)}")
            if (orf > 0.004) parts.add("options regulatory fee ${money(orf)}")
            if (parts.isEmpty()) return "About ${money(total)} in regulatory fees."
            return parts.joinToString(" + ") + " = ${money(total)}"
        }

        private fun money(v: Double) = "$" + String.format(java.util.Locale.US, "%.2f", v)
    }

    /** Brokers round the SEC fee UP to the next cent; the TAF goes to the nearest cent. */
    private fun ceilCent(v: Double) = ceil(v * 100.0 - 1e-9) / 100.0
    private fun roundCent(v: Double) = round(v * 100.0) / 100.0

    /**
     * Ally's low-priced-security commission, charged on a BUY as well as a SELL.
     * Zero for anything trading at $2 or more.
     */
    fun lowPricedCommission(shares: Double, price: Double): Double {
        val qty = kotlin.math.abs(shares)
        if (qty < 1e-9 || price <= 0.0 || price >= LOW_PRICED_UNDER) return 0.0
        val raw = LOW_PRICED_BASE + LOW_PRICED_PER_SHARE * qty
        val cap = LOW_PRICED_MAX_PCT * qty * price      // never more than 5% of the trade
        return roundCent(minOf(raw, cap))
    }

    /**
     * What Ally would charge on one stock or ETF trade.
     *
     * @param type [TxnType.BUY] or [TxnType.SELL]; anything else has no trade fee.
     */
    fun forEquityTrade(type: String, shares: Double, price: Double): Breakdown {
        val qty = kotlin.math.abs(shares)
        if (qty < 1e-9 || price <= 0.0) return Breakdown()
        if (type != TxnType.BUY && type != TxnType.SELL) return Breakdown()

        val commission = lowPricedCommission(qty, price)

        // Both regulatory fees are on SALES only. An ordinary purchase is genuinely free.
        if (type == TxnType.BUY) return Breakdown(commission = commission)

        val proceeds = qty * price
        return Breakdown(
            commission = commission,
            secFee = ceilCent(proceeds * SEC_FEE_PER_MILLION / 1_000_000.0),
            taf = minOf(roundCent(qty * TAF_PER_SHARE), TAF_MAX)
        )
    }

    /**
     * What one options trade costs. The app has no options transaction type yet, so this is
     * here to keep the schedule complete and correct in one place rather than scattered
     * through the Settings copy.
     */
    fun forOptionTrade(type: String, contracts: Double, premiumPerContract: Double): Breakdown {
        val n = kotlin.math.abs(contracts)
        if (n < 1e-9) return Breakdown()
        val commission = roundCent(n * OPTION_PER_CONTRACT)
        val orf = roundCent(n * ORF_PER_CONTRACT)
        if (type != TxnType.SELL) return Breakdown(commission = commission, orf = orf)
        // Section 31 applies to option SALES on the aggregate premium (100 shares/contract).
        val principal = n * premiumPerContract * 100.0
        return Breakdown(
            commission = commission,
            secFee = ceilCent(principal * SEC_FEE_PER_MILLION / 1_000_000.0),
            taf = roundCent(n * TAF_PER_OPTION_CONTRACT),
            orf = orf
        )
    }

    /**
     * Does a recorded fee look wrong for this trade?
     *
     * Only reports what it is sure about: a charge on a BUY bigger than Ally's schedule
     * allows. Note this now correctly leaves a low-priced buy alone - that one really does
     * carry a commission. Sell-side amounts are never questioned, because the correct cents
     * depend on the rates in force on the day and both have changed during 2026.
     */
    fun buyFeeLooksWrong(type: String, shares: Double, price: Double, recordedFee: Double): Boolean {
        if (type != TxnType.BUY || recordedFee <= 0.005) return false
        return recordedFee > forEquityTrade(type, shares, price).total + 0.005
    }

    /** One line of the published schedule, for the Settings screen. */
    data class Item(val label: String, val amount: String, val note: String = "")

    /** Everything Ally publishes that can touch a self-directed account. */
    val TRADING: List<Item> = listOf(
        Item("Stocks and ETFs", "$0", "no commission, buying or selling"),
        Item("Buying a stock or ETF", "free", "no commission and no regulatory fee"),
        Item("Stocks under $2", "$4.95 + $0.01/share", "both buying and selling, max 5% of trade value"),
        Item("Options", "$0 + $0.50/contract", "plus regulatory fees below"),
        Item("No-load mutual funds", "$0", "load funds charge their own fees"),
        Item("Bonds", "$1 per bond", "$10 minimum, $250 maximum per transaction"),
        Item("CDs", "$24.95", "per transaction"),
        Item("Phone or broker-assisted order", "$20", "plus the regular commission"),
        Item("Foreign stock transaction", "$50", "plus the regular commission"),
        Item("Margin sellout", "$40", "plus the regular commission")
    )

    val REGULATORY: List<Item> = listOf(
        Item("SEC Section 31 fee", "$20.60 per $1M", "sales only; was $0.00 before 4 Apr 2026"),
        Item("FINRA TAF (shares)", "$0.000195/share", "sales only, max $9.79 per trade"),
        Item("FINRA TAF (options)", "$0.00329/contract", "sales only"),
        Item("Options regulatory fee", "$0.02/contract", "")
    )

    val ACCOUNT: List<Item> = listOf(
        Item("Account and inactivity fees", "$0", ""),
        Item("Dividends and DRIP", "$0", "no charge to receive or reinvest"),
        Item("IRA annual fee", "$0", "transfer $50, closure $25"),
        Item("ACAT transfer out", "$50", "DRS $115/position, DWAC $50/position + agent fees"),
        Item("Outgoing domestic wire", "$30", "ACH deposits and withdrawals are free"),
        Item("Returned ACH", "$30", ""),
        Item("Paper statement / confirmation", "$4 / $2", "each"),
        Item("ADR custody", "~$0.02/share", "semi-annual, set by the depositary bank"),
        Item("1099 request", "$50", ""),
        Item("Vault fee", "$60/year", "charged monthly"),
        Item("Option position management", "$100", "")
    )
}
