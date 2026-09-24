package com.tj.portfolio.data

import org.json.JSONObject

/**
 * WHAT AN ETF IS, TO THIS APP (Round 63).
 *
 * TJ asked for a fourth Research list: *"another tab for Best ETFs. this tab will do online
 * research for the up-to-date best etfs to invest in by many relevant factors."*
 *
 * "Many relevant factors" is the requirement, and a fund is not a company - the whole
 * [ScreenRow] apparatus (forward P/E, EPS growth, book value, the next earnings date) means
 * nothing for a basket of two hundred stocks. What a fund IS judged on, in every serious
 * write-up of how to choose one, is a different and much shorter list:
 *
 *   * **what it has returned**, over more than one horizon, because one good year is luck;
 *   * **what it charges** - the expense ratio is the only number in investing that is known
 *     in advance and compounds against you every year;
 *   * **how big it is** - a fund with $20m under management is a fund that can be closed,
 *     and a closure is a taxable event the holder did not choose;
 *   * **how easily it trades** - dollar volume is what the spread is paid out of;
 *   * **how long it has existed**, because a three-year record needs three years;
 *   * **where it sits now** relative to its own trend and its 52-week range.
 *
 * Every one of those arrives in the SAME screener response, which is why this is affordable:
 * see [com.tj.portfolio.net.EtfScreener]. Nothing here is derived from a company filing and
 * nothing here needs a second request per fund.
 *
 * Sources for the factor list: State Street and Schwab both publish "how to evaluate an ETF"
 * checklists and they agree on cost, size, liquidity, tracking and performance; the risk
 * dimension (annualised return over 3 and 5 years rather than a single trailing figure) is
 * the standard correction for reading one hot year as skill.
 */
data class EtfRow(
    val symbol: String,
    val name: String = "",
    val price: Double = 0.0,
    val changePct: Double = 0.0,
    /**
     * Net expense ratio as a PERCENT - 0.03 means three basis points.
     *
     * **-1.0 means Yahoo did not publish one.** Zero is a real fee (Round 66 audit, ETF-6):
     * BKLC and BKAG charge nothing, so this field cannot use 0.0 as its absent-sentinel the
     * way the rest of them do. Anything reading it must test `>= 0.0`, not `> 0.0`.
     */
    val expenseRatio: Double = -1.0,
    /** Total net assets in dollars. */
    val netAssets: Double = 0.0,
    /** Trailing yield as a percent. */
    val yieldPct: Double = 0.0,
    val ytdReturnPct: Double = 0.0,
    /** Trailing three-month total return, percent. */
    val threeMonthPct: Double = 0.0,
    val oneYearPct: Double = 0.0,
    /** ANNUALISED NAV return over three years, percent - not the cumulative figure. */
    val threeYearAnnualPct: Double = 0.0,
    val fiveYearAnnualPct: Double = 0.0,
    val avgVolume3M: Double = 0.0,
    val fiftyDayAvg: Double = 0.0,
    val twoHundredDayAvg: Double = 0.0,
    val fiftyTwoWeekHigh: Double = 0.0,
    val fiftyTwoWeekLow: Double = 0.0,
    /** First trade, in ms. How old the fund is - a 3-year record needs three years. */
    val inceptionMs: Long = 0L,
    val exchange: String = "",
    /** Which screener lists this fund turned up in. */
    val lists: Set<String> = emptySet()
) {
    /** Dollars traded on an average day - what the bid-ask spread is actually paid out of. */
    val dollarVolume: Double get() = if (price > 0 && avgVolume3M > 0) price * avgVolume3M else 0.0

    /** Whole years since the fund started trading, or -1 when it does not say. */
    val ageYears: Double
        get() = if (inceptionMs <= 0L) -1.0
        else (System.currentTimeMillis() - inceptionMs) / 31_557_600_000.0

    /** How far below its own 52-week high it is trading, as a positive percent. */
    val offHighPct: Double
        get() = if (fiftyTwoWeekHigh > 0 && price > 0)
            ((fiftyTwoWeekHigh - price) / fiftyTwoWeekHigh * 100.0).coerceAtLeast(0.0)
        else -1.0

    /**
     * Two lists can each carry half the picture - `top_etfs_us` is ordered one way and
     * `top_performing_etfs` another, and a fund in both should end up with the union of what
     * they each knew. Same field-by-field rule as [ScreenRow.merge]: a value present wins
     * over a value absent, and neither overwrites the other with a zero.
     */
    fun merge(other: EtfRow): EtfRow = EtfRow(
        symbol = symbol,
        name = name.ifBlank { other.name },
        price = if (price > 0) price else other.price,
        changePct = if (changePct != 0.0) changePct else other.changePct,
        // `>= 0`, NOT `> 0` (Round 66 audit, REG-1 - a regression from ETF-6's own fix).
        //
        // When ETF-6 made 0.0 a REAL FEE and -1.0 the unknown, every reader had to move to
        // `>= 0` - and this one was missed, which is worse than not having fixed it. The same
        // fund appears on more than one Yahoo screen (a bond ETF is in `top_etfs_us` and
        // `bond_etfs`), and `buildEtfs` merges the two rows. With `> 0`, a genuine 0.00% on
        // this row lost to the other page's -1.0, so BKLC and BKAG - the very funds ETF-6's
        // comment names - went straight back to forfeiting all 20 cost points and printing a
        // dash. A sentinel is only worth having if every reader agrees on it.
        expenseRatio = if (expenseRatio >= 0) expenseRatio else other.expenseRatio,
        netAssets = if (netAssets > 0) netAssets else other.netAssets,
        yieldPct = if (yieldPct != 0.0) yieldPct else other.yieldPct,
        ytdReturnPct = if (ytdReturnPct != 0.0) ytdReturnPct else other.ytdReturnPct,
        threeMonthPct = if (threeMonthPct != 0.0) threeMonthPct else other.threeMonthPct,
        oneYearPct = if (oneYearPct != 0.0) oneYearPct else other.oneYearPct,
        threeYearAnnualPct = if (threeYearAnnualPct != 0.0) threeYearAnnualPct
        else other.threeYearAnnualPct,
        fiveYearAnnualPct = if (fiveYearAnnualPct != 0.0) fiveYearAnnualPct
        else other.fiveYearAnnualPct,
        avgVolume3M = if (avgVolume3M > 0) avgVolume3M else other.avgVolume3M,
        fiftyDayAvg = if (fiftyDayAvg > 0) fiftyDayAvg else other.fiftyDayAvg,
        twoHundredDayAvg = if (twoHundredDayAvg > 0) twoHundredDayAvg else other.twoHundredDayAvg,
        fiftyTwoWeekHigh = if (fiftyTwoWeekHigh > 0) fiftyTwoWeekHigh else other.fiftyTwoWeekHigh,
        fiftyTwoWeekLow = if (fiftyTwoWeekLow > 0) fiftyTwoWeekLow else other.fiftyTwoWeekLow,
        inceptionMs = if (inceptionMs > 0) inceptionMs else other.inceptionMs,
        exchange = exchange.ifBlank { other.exchange },
        lists = lists + other.lists
    )
}

/**
 * The handful of fund numbers a Research row carries so the card can show them.
 *
 * SEPARATE FROM [EtfRow] on purpose: [EtfRow] is the raw screener output, ~20 fields wide and
 * only meaningful to the scorer. This is what survives into the cache, into the Claude prompt
 * and onto the screen - the six figures a person actually compares two funds on.
 *
 * Every field is optional. A fund whose provider published no five-year record shows no
 * five-year figure rather than a zero, because a zero here reads as "it returned nothing".
 */
data class EtfFacts(
    /**
     * As [EtfRow.expenseRatio]: a PERCENT, and **-1.0 means unknown** - 0.0 is a real fee
     * (Round 66 audit, ETF-6). Every test on this field is `>= 0.0`, never `> 0.0`.
     */
    val expenseRatio: Double = -1.0,
    val netAssets: Double = 0.0,
    val yieldPct: Double = 0.0,
    val ytdReturnPct: Double = 0.0,
    val oneYearPct: Double = 0.0,
    val threeYearAnnualPct: Double = 0.0,
    val fiveYearAnnualPct: Double = 0.0,
    val dollarVolume: Double = 0.0,
    val inceptionMs: Long = 0L
) {
    val isEmpty: Boolean
        get() = expenseRatio < 0.0 && netAssets <= 0.0 && oneYearPct == 0.0 &&
            threeYearAnnualPct == 0.0 && fiveYearAnnualPct == 0.0 && ytdReturnPct == 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        if (expenseRatio >= 0) put("expenseRatio", expenseRatio)
        if (netAssets > 0) put("netAssets", netAssets)
        if (yieldPct != 0.0) put("yieldPct", yieldPct)
        if (ytdReturnPct != 0.0) put("ytdReturnPct", ytdReturnPct)
        if (oneYearPct != 0.0) put("oneYearPct", oneYearPct)
        if (threeYearAnnualPct != 0.0) put("threeYearAnnualPct", threeYearAnnualPct)
        if (fiveYearAnnualPct != 0.0) put("fiveYearAnnualPct", fiveYearAnnualPct)
        if (dollarVolume > 0) put("dollarVolume", dollarVolume)
        if (inceptionMs > 0) put("inceptionMs", inceptionMs)
    }

    companion object {
        fun fromJson(o: JSONObject?): EtfFacts? {
            if (o == null) return null
            fun d(k: String): Double {
                val v = o.optDouble(k, 0.0)
                return if (v.isNaN() || v.isInfinite()) 0.0 else v
            }
            val f = EtfFacts(
                // The one field whose absent-sentinel is -1.0, so a stored 0.00% survives a
                // cache round trip as a fee rather than coming back as "unknown".
                expenseRatio = o.optDouble("expenseRatio", -1.0)
                    .let { if (it.isNaN() || it.isInfinite()) -1.0 else it },
                netAssets = d("netAssets"),
                yieldPct = d("yieldPct"),
                ytdReturnPct = d("ytdReturnPct"),
                oneYearPct = d("oneYearPct"),
                threeYearAnnualPct = d("threeYearAnnualPct"),
                fiveYearAnnualPct = d("fiveYearAnnualPct"),
                dollarVolume = d("dollarVolume"),
                inceptionMs = o.optLong("inceptionMs", 0L)
            )
            return if (f.isEmpty && f.dollarVolume <= 0.0 && f.inceptionMs <= 0L) null else f
        }
    }
}

/**
 * A fund that tracks the same exposure as the card it is listed on and lost its own place to it
 * (research idea 3, 2026-09-24b) - kept with the two numbers people compare such funds by, so
 * "other ways to hold this" is a real choice (a different issuer, a broker's commission-free
 * list) and not only a ticker in a sentence. [expenseRatio] is a percent, -1.0 unknown.
 */
data class EtfAlternative(
    val symbol: String,
    val name: String = "",
    val expenseRatio: Double = -1.0,
    val fiveYearAnnualPct: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("s", symbol)
        if (name.isNotBlank()) put("n", name)
        if (expenseRatio >= 0.0) put("er", expenseRatio)
        if (fiveYearAnnualPct != 0.0) put("y5", fiveYearAnnualPct)
    }

    companion object {
        fun fromJson(o: JSONObject?): EtfAlternative? {
            val sym = o?.optString("s").orEmpty().uppercase()
            if (sym.isBlank()) return null
            return EtfAlternative(
                symbol = sym,
                name = o!!.optString("n"),
                expenseRatio = o.optDouble("er", -1.0).let { if (it.isFinite()) it else -1.0 },
                fiveYearAnnualPct = o.optDouble("y5", 0.0).let { if (it.isFinite()) it else 0.0 }
            )
        }

        /** What a row itself contributes when it loses its place to another. */
        fun of(r: ResearchRow): EtfAlternative = EtfAlternative(
            r.symbol, r.name, r.etf?.expenseRatio ?: -1.0, r.etf?.fiveYearAnnualPct ?: 0.0)
    }
}
