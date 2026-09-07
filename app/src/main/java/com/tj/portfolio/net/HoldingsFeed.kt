package com.tj.portfolio.net

import com.tj.portfolio.data.FundHolding
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.SectorWeight
import org.json.JSONObject

/**
 * WHAT A FUND HOLDS, FROM YAHOO'S `topHoldings` MODULE.
 *
 * The same `quoteSummary` endpoint and the same cookie+crumb handshake `FundamentalsFeed`
 * already uses, asked for three more modules. It is deliberately its OWN file and its own
 * request rather than more modules bolted onto `FundamentalsFeed.core`, for two reasons:
 *
 *   - `topHoldings` is meaningless for an ordinary stock, and every holding of TJ's is one.
 *     Adding it to the core module list would put a module that cannot answer into every
 *     request the app makes for every symbol - and Yahoo rejects a module set with a 4xx
 *     rather than ignoring the parts it does not like, which would have risked the core
 *     numbers falling back for the sake of a tab that was not open;
 *   - it is fetched only when the Holdings tab is actually opened, on a 12-hour cache. A
 *     fund's register is republished daily at most.
 *
 * WHAT IT CANNOT DO, said plainly here and on screen. `topHoldings` returns the fund's TOP
 * holdings - ten for most funds - not its complete book. No keyless feed publishes the full
 * register of a 500-name index fund. So the parse records how many positions it actually got
 * and what share of the fund they add up to, and the screen states both rather than implying
 * the list is exhaustive. The sector split and the asset mix that come back in the same
 * response DO describe 100% of the fund, and are shown alongside for that reason.
 */
object HoldingsFeed {

    /**
     * `quoteType` is what decides whether this is a fund at all - read from the provider
     * rather than guessed from the ticker. `fundProfile` carries the category and the
     * expense ratio, which are the two things anyone asks about a fund straight after
     * "what's in it".
     */
    private const val MODULES = "topHoldings,fundProfile,quoteType"

    /**
     * A reduced set to retry with if the full one is rejected.
     *
     * Yahoo answers an unrecognised module with a 4xx for the WHOLE request, so one module
     * going away takes the other two with it. `topHoldings` alone still answers TJ's actual
     * question; the profile is a nicety.
     */
    private const val MODULES_MINIMAL = "topHoldings,quoteType"

    suspend fun holdings(symbol: String): FundHoldings? {
        val sym = symbol.uppercase()
        val res = FundamentalsFeed.quoteSummary(sym, MODULES, MODULES_MINIMAL) ?: return null
        return runCatching { parse(sym, res) }.getOrNull()
    }

    internal fun parse(symbol: String, res: JSONObject): FundHoldings {
        val qt = res.optJSONObject("quoteType")
        val quoteType = qt?.optString("quoteType").orEmpty().uppercase()
        val top = res.optJSONObject("topHoldings")
        val profile = res.optJSONObject("fundProfile")

        val holdings = ArrayList<FundHolding>()
        top?.optJSONArray("holdings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = num(o, "holdingPercent") ?: continue
                // A weight of zero is a row with nothing to say; a negative one is a short
                // leg the free feed does not describe well enough to draw. Neither belongs
                // on a page whose whole point is "what fraction of the fund is this".
                if (w <= 0.0) continue
                val sym = o.optString("symbol").uppercase()
                val name = o.optString("holdingName")
                if (sym.isBlank() && name.isBlank()) continue
                holdings.add(FundHolding(sym, name, w))
            }
        }

        val sectors = ArrayList<SectorWeight>()
        top?.optJSONArray("sectorWeightings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Each element is a ONE-KEY object - {"technology": {"raw": 0.31}} - rather
                // than {"name": ..., "weight": ...}. The key IS the sector name.
                val key = o.keys().asSequence().firstOrNull() ?: continue
                val w = num(o, key) ?: continue
                if (w <= 0.0) continue
                sectors.add(SectorWeight(prettySector(key), w))
            }
        }

        return FundHoldings(
            symbol = symbol,
            // BOTH tests, because either alone is wrong. `quoteType` is authoritative when
            // present but Yahoo omits it on some listings; a populated `topHoldings` is
            // strong evidence on its own, since an ordinary share has no holdings at all.
            isFund = quoteType == "ETF" || quoteType == "MUTUALFUND" ||
                holdings.isNotEmpty() || sectors.isNotEmpty(),
            quoteType = quoteType,
            holdings = holdings.sortedByDescending { it.weight },
            sectors = sectors.sortedByDescending { it.weight },
            category = profile?.optString("categoryName").orEmpty(),
            family = profile?.optString("family").orEmpty(),
            expenseRatio = num(
                profile?.optJSONObject("feesExpensesInvestment"),
                "annualReportExpenseRatio"
            ) ?: 0.0,
            stockPct = num(top, "stockPosition") ?: 0.0,
            bondPct = num(top, "bondPosition") ?: 0.0,
            cashPct = num(top, "cashPosition") ?: 0.0,
            // "Other" is the residual and Yahoo splits it three ways; summed here so the
            // asset mix on screen adds to the whole fund rather than leaving a silent gap.
            otherPct = (num(top, "otherPosition") ?: 0.0) +
                (num(top, "preferredPosition") ?: 0.0) +
                (num(top, "convertiblePosition") ?: 0.0),
            fetched = System.currentTimeMillis()
        )
    }

    /** `consumer_cyclical` -> `Consumer cyclical`. */
    internal fun prettySector(key: String): String =
        key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /**
     * Yahoo wraps most numbers as `{"raw": 0.0721, "fmt": "7.21%"}`, hands back a bare number
     * for some, and an EMPTY OBJECT for "not reported". Same three shapes, same rule, as
     * `FundamentalsFeed.num`: a value, or nothing. An empty object read as 0.0 would draw a
     * holding at 0% of the fund as though that were a fact.
     */
    internal fun num(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toDouble().takeIf { it.isFinite() }
            is JSONObject -> if (v.has("raw") && !v.isNull("raw"))
                v.optDouble("raw", Double.NaN).takeIf { it.isFinite() } else null
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}
