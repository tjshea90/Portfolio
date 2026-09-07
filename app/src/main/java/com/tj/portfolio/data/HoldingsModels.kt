package com.tj.portfolio.data

import org.json.JSONArray
import org.json.JSONObject

/** One position inside a fund, and how much of the fund it is. */
data class FundHolding(
    val symbol: String,
    val name: String,
    /** Fraction of the fund, 0..1. Yahoo reports 0.0721 for 7.21%. */
    val weight: Double
)

/** One sector's share of a fund, 0..1. */
data class SectorWeight(val sector: String, val weight: Double)

/**
 * WHAT AN ETF ACTUALLY HOLDS.
 *
 * TJ: *"when I click on etfs in the app, add a tab called holdings and show all of the
 * stocks that the etf currently holds and the percentage of each holding of the entire etf."*
 *
 * ONE HONESTY NOTE, WHICH THE SCREEN ALSO MAKES: the free feed gives the fund's TOP holdings
 * - typically ten - not its full book. A 500-name index fund does not publish its whole
 * register through any keyless endpoint, and inventing the tail would be worse than
 * admitting the limit. So the tab shows what is actually known, says how many positions that
 * is, says what share of the fund they add up to, and fills the rest of the page with the
 * things that DO describe the whole fund: the sector split and the asset mix, both of which
 * cover 100% of it.
 *
 * [isFund] is what decides whether the tab appears at all. It is read from Yahoo's own
 * `quoteType` rather than guessed from the ticker - a four-letter symbol is not a fund, and
 * the app has a rule against inferring a fact a provider will state.
 */
data class FundHoldings(
    val symbol: String,
    val isFund: Boolean = false,
    /** ETF / MUTUALFUND / EQUITY, straight from the provider. */
    val quoteType: String = "",
    val holdings: List<FundHolding> = emptyList(),
    val sectors: List<SectorWeight> = emptyList(),
    /** Category, e.g. "Large Blend". Blank when not reported. */
    val category: String = "",
    val family: String = "",
    /** Annual expense ratio as a fraction, 0..1. 0 when not reported. */
    val expenseRatio: Double = 0.0,
    /** Asset mix, each 0..1. All zero when the provider did not break it down. */
    val stockPct: Double = 0.0,
    val bondPct: Double = 0.0,
    val cashPct: Double = 0.0,
    val otherPct: Double = 0.0,
    val fetched: Long = 0L
) {
    val isEmpty: Boolean get() = holdings.isEmpty() && sectors.isEmpty() && !hasAssetMix

    val hasAssetMix: Boolean
        get() = stockPct > 0.0 || bondPct > 0.0 || cashPct > 0.0 || otherPct > 0.0

    /** What the listed holdings add up to, 0..1. The honest "how much of the fund is this". */
    val coverage: Double get() = holdings.sumOf { it.weight }

    fun stale(now: Long = System.currentTimeMillis()): Boolean =
        fetched <= 0L || now - fetched > TTL_MS

    companion object {
        /**
         * A fund's register is republished daily at most, and an index fund's top ten barely
         * move from one month to the next. Twelve hours is already far more often than the
         * data changes; anything shorter is a request that can only return what it returned
         * last time.
         */
        const val TTL_MS = 12 * 3_600_000L
    }
}

/** On-disk codec, stored in the existing `fundamentals` table under its own kind. */
object HoldingsJson {

    fun encode(h: FundHoldings): String = JSONObject().apply {
        put("v", 1)
        put("symbol", h.symbol)
        put("isFund", h.isFund)
        put("quoteType", h.quoteType)
        put("holdings", JSONArray().also { arr ->
            h.holdings.forEach {
                arr.put(JSONObject().apply {
                    put("s", it.symbol); put("n", it.name); put("w", it.weight)
                })
            }
        })
        put("sectors", JSONArray().also { arr ->
            h.sectors.forEach {
                arr.put(JSONObject().apply { put("s", it.sector); put("w", it.weight) })
            }
        })
        put("category", h.category)
        put("family", h.family)
        put("expense", h.expenseRatio)
        put("stock", h.stockPct); put("bond", h.bondPct)
        put("cash", h.cashPct); put("other", h.otherPct)
        put("fetched", h.fetched)
    }.toString()

    /** Total: an unreadable row degrades to "fetch it again", never to an exception. */
    fun decode(json: String): FundHoldings? = runCatching {
        val o = JSONObject(json)
        val hs = ArrayList<FundHolding>()
        o.optJSONArray("holdings")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val w = e.optDouble("w", 0.0)
                if (!w.isFinite()) continue
                hs.add(FundHolding(e.optString("s"), e.optString("n"), w))
            }
        }
        val ss = ArrayList<SectorWeight>()
        o.optJSONArray("sectors")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val w = e.optDouble("w", 0.0)
                if (!w.isFinite()) continue
                ss.add(SectorWeight(e.optString("s"), w))
            }
        }
        fun d(k: String) = o.optDouble(k, 0.0).let { if (it.isFinite()) it else 0.0 }
        FundHoldings(
            symbol = o.optString("symbol").uppercase(),
            isFund = o.optBoolean("isFund", false),
            quoteType = o.optString("quoteType"),
            holdings = hs,
            sectors = ss,
            category = o.optString("category"),
            family = o.optString("family"),
            expenseRatio = d("expense"),
            stockPct = d("stock"), bondPct = d("bond"),
            cashPct = d("cash"), otherPct = d("other"),
            fetched = o.optLong("fetched", 0L)
        )
    }.getOrNull()
}
