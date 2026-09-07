package com.tj.portfolio.net

import com.tj.portfolio.data.ScreenRow
import org.json.JSONObject

/**
 * Yahoo's PREDEFINED screeners - the whole market, filtered by Yahoo, one request per list.
 *
 * WHY THIS AND NOT A PER-SYMBOL SWEEP. The Research tab needs to rank a few thousand tickers
 * it does not already track, and the app's rule since v4.5 is that nothing may hammer a
 * provider. One `screener/predefined/saved` call returns up to 100 fully-populated quote
 * objects - price, market cap, forward P/E, book value, both EPS figures, the 50/200-day
 * averages, the 52-week range and the next earnings date - for a single HTTP round trip.
 * Screening 700 symbols one at a time would be 700 requests for the same answer.
 *
 * NO KEY, NO CRUMB. Unlike `v10/finance/quoteSummary` (which needs the cookie+crumb dance in
 * [FundamentalsFeed]) and `v7/finance/quote` (which now refuses without one), this endpoint
 * answers a plain GET. Verified live against every id in [Lists] in September 2026.
 *
 * IT IS STILL YAHOO. The same 429 behaviour applies, so every call goes through [Http], which
 * carries the per-host cooldown; a list that does not answer comes back empty and the caller
 * scores with whatever else arrived rather than failing the whole tab.
 */
object Screener {

    /**
     * The predefined lists this app reads, and what each one is for.
     *
     * Yahoo owns these definitions, which is a feature: the membership of DAY_LOSERS or
     * MOST_SHORTED_STOCKS is recomputed continuously against the whole US market by someone
     * with the whole US market, and the app just has to read it.
     */
    object Lists {
        const val DAY_GAINERS = "day_gainers"
        const val DAY_LOSERS = "day_losers"
        const val MOST_ACTIVE = "most_actives"
        const val MOST_SHORTED = "most_shorted_stocks"
        const val UNDERVALUED_GROWTH = "undervalued_growth_stocks"
        const val GROWTH_TECH = "growth_technology_stocks"
        const val UNDERVALUED_LARGE = "undervalued_large_caps"
        const val SMALL_CAP_GAINERS = "small_cap_gainers"
        const val AGGRESSIVE_SMALL = "aggressive_small_caps"
    }

    /** Plain-English name for a list id, for the "found in" reason lines. */
    fun label(id: String): String = when (id) {
        Lists.DAY_GAINERS -> "day gainers"
        Lists.DAY_LOSERS -> "day losers"
        Lists.MOST_ACTIVE -> "most active"
        Lists.MOST_SHORTED -> "most shorted"
        Lists.UNDERVALUED_GROWTH -> "undervalued growth"
        Lists.GROWTH_TECH -> "growth technology"
        Lists.UNDERVALUED_LARGE -> "undervalued large caps"
        Lists.SMALL_CAP_GAINERS -> "small-cap gainers"
        Lists.AGGRESSIVE_SMALL -> "aggressive small caps"
        else -> id.replace('_', ' ')
    }

    /**
     * Yahoo caps `count` at 100 per request and ignores anything larger, so asking for more
     * silently returns 100 and the caller thinks it saw the whole list.
     */
    private const val MAX_COUNT = 100

    suspend fun fetch(listId: String, count: Int = 50): List<ScreenRow> {
        val n = count.coerceIn(1, MAX_COUNT)
        for (host in listOf("query1", "query2")) {
            val r = Http.get(
                "https://$host.finance.yahoo.com/v1/finance/screener/predefined/saved" +
                    "?scrIds=" + MarketData.enc(listId) + "&count=$n&start=0",
                timeoutMs = 20000, conditionalKey = true
            )
            // A local cooldown means this host is being deliberately left alone; trying the
            // other Yahoo host would defeat the point, since the throttle is on us, not it.
            if (r.throttledLocally) return emptyList()
            if (!r.ok) continue
            val parsed = runCatching { parse(listId, r.body) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /** Split out so it can be tested against a saved response with no network. */
    internal fun parse(listId: String, body: String): List<ScreenRow> {
        val res = JSONObject(body).optJSONObject("finance")?.optJSONArray("result")
            ?: return emptyList()
        val first = res.optJSONObject(0) ?: return emptyList()
        val quotes = first.optJSONArray("quotes") ?: return emptyList()
        val out = ArrayList<ScreenRow>(quotes.length())
        for (i in 0 until quotes.length()) {
            val q = quotes.optJSONObject(i) ?: continue
            // ETFs, funds, currencies and indices come back from some lists; the scorers read
            // company fundamentals, which none of those have.
            if (q.optString("quoteType") != "EQUITY") continue
            val sym = q.optString("symbol").uppercase()
            if (sym.isBlank() || sym.contains('-') || sym.contains('^')) continue
            out.add(
                ScreenRow(
                    symbol = sym,
                    name = q.optString("longName").ifBlank {
                        q.optString("displayName").ifBlank { q.optString("shortName") }
                    },
                    price = d(q, "regularMarketPrice"),
                    prevClose = d(q, "regularMarketPreviousClose"),
                    changePct = d(q, "regularMarketChangePercent"),
                    marketCap = d(q, "marketCap"),
                    volume = d(q, "regularMarketVolume"),
                    avgVolume3M = d(q, "averageDailyVolume3Month"),
                    forwardPe = d(q, "forwardPE"),
                    trailingPe = d(q, "trailingPE"),
                    priceToBook = d(q, "priceToBook"),
                    epsTtm = d(q, "epsTrailingTwelveMonths"),
                    epsForward = d(q, "epsForward"),
                    epsCurrentYear = d(q, "epsCurrentYear"),
                    fiftyDayAvg = d(q, "fiftyDayAverage"),
                    twoHundredDayAvg = d(q, "twoHundredDayAverage"),
                    fiftyTwoWeekHigh = d(q, "fiftyTwoWeekHigh"),
                    fiftyTwoWeekLow = d(q, "fiftyTwoWeekLow"),
                    fiftyTwoWeekChangePct = d(q, "fiftyTwoWeekChangePercent"),
                    dividendYield = d(q, "trailingAnnualDividendYield") * 100.0,
                    // Yahoo publishes these in SECONDS. Multiplying at the boundary keeps
                    // every timestamp inside the app in milliseconds, which is what every
                    // other feed and all of Fmt already assume.
                    earningsAt = (d(q, "earningsTimestampStart").takeIf { it > 0 }
                        ?: d(q, "earningsTimestamp")).let {
                        if (it > 0) (it * 1000L).toLong() else 0L
                    },
                    earningsEstimated = q.optBoolean("isEarningsDateEstimate", false),
                    exchange = q.optString("fullExchangeName"),
                    lists = setOf(listId)
                )
            )
        }
        return out
    }

    private fun d(o: JSONObject, key: String): Double {
        val v = o.optDouble(key, 0.0)
        return if (v.isNaN() || v.isInfinite()) 0.0 else v
    }

    /**
     * Yahoo's own "trending tickers" list - what people are LOOKING UP, which is a different
     * signal from what they are posting about and worth blending with it.
     */
    suspend fun trendingSymbols(count: Int = 25): List<String> {
        for (host in listOf("query1", "query2")) {
            val r = Http.get(
                "https://$host.finance.yahoo.com/v1/finance/trending/US?count=$count",
                timeoutMs = 15000, conditionalKey = true
            )
            if (r.throttledLocally) return emptyList()
            if (!r.ok) continue
            val parsed = runCatching { parseTrending(r.body) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parseTrending(body: String): List<String> {
        val res = JSONObject(body).optJSONObject("finance")?.optJSONArray("result")
            ?: return emptyList()
        val quotes = res.optJSONObject(0)?.optJSONArray("quotes") ?: return emptyList()
        val out = ArrayList<String>(quotes.length())
        for (i in 0 until quotes.length()) {
            val s = quotes.optJSONObject(i)?.optString("symbol")?.uppercase() ?: continue
            // Indices (^GSPC) and crypto pairs (BTC-USD) ride this list too and are not
            // stocks anyone can research here.
            if (s.isBlank() || s.startsWith("^") || s.contains('-')) continue
            out.add(s)
        }
        return out
    }
}
