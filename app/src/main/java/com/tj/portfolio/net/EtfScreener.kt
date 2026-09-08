package com.tj.portfolio.net

import com.tj.portfolio.data.EtfRow
import org.json.JSONObject

/**
 * THE ETF UNIVERSE - Yahoo's own fund screens, keyless, six requests for ~600 funds.
 *
 * [Screener] does this for stocks and deliberately DROPS anything that is not `EQUITY`,
 * because the stock scorers read company fundamentals. Funds need the opposite filter and a
 * different set of fields, so this is its own object rather than a flag on that one - trying
 * to serve both from one parser is how a fund ends up being scored on its forward P/E.
 *
 * ---- WHY THESE TWO LISTS
 *
 * Verified live in September 2026: `top_etfs_us` and `top_performing_etfs` each report ~523
 * funds and their first hundred rows overlap by SEVEN. They are ranked on different things -
 * roughly size and roughly return - so taking both is not redundancy, it is the difference
 * between "the biggest funds" and "the funds that have actually done well", and the answer
 * TJ asked for lives in the intersection of those two ideas rather than in either one.
 *
 * ---- WHY PAGINATION IS WORTH IT
 *
 * Yahoo caps `count` at 100 and honours `start`, so three pages of each list is six requests
 * for ~600 distinct funds, every one arriving with its expense ratio, its net assets, its
 * three- and five-year annualised NAV returns, its YTD, its yield, its liquidity and its
 * moving averages already attached. The per-fund alternative - `quoteSummary` with the
 * cookie-and-crumb handshake, one call each - would be six hundred requests for the same
 * answer, which the app's provider-safety rule forbids outright.
 *
 * A page that does not answer costs nothing but its own rows: the caller scores whatever
 * arrived. That matters because this is still Yahoo and still 429s in bursts.
 */
object EtfScreener {

    object Lists {
        /** Ranked roughly by size and standing. */
        const val TOP_ETFS = "top_etfs_us"

        /** Ranked roughly by what they have returned. */
        const val TOP_PERFORMING = "top_performing_etfs"
    }

    fun label(id: String): String = when (id) {
        Lists.TOP_ETFS -> "Yahoo's top US ETFs"
        Lists.TOP_PERFORMING -> "Yahoo's top-performing ETFs"
        else -> id.replace('_', ' ')
    }

    /** Yahoo silently truncates anything above this and the caller never notices. */
    private const val MAX_COUNT = 100

    /**
     * One page of one list.
     *
     * [start] is Yahoo's own offset. A page beyond the end of a list returns no quotes rather
     * than an error, which is the natural stopping condition for [fetchAll].
     */
    suspend fun fetch(listId: String, count: Int = MAX_COUNT, start: Int = 0): List<EtfRow> {
        val n = count.coerceIn(1, MAX_COUNT)
        val from = start.coerceAtLeast(0)
        for (host in listOf("query1", "query2")) {
            val r = Http.get(
                "https://$host.finance.yahoo.com/v1/finance/screener/predefined/saved" +
                    "?scrIds=" + MarketData.enc(listId) + "&count=$n&start=$from",
                timeoutMs = 20000, conditionalKey = true
            )
            // A local cooldown means this host is being left alone deliberately; hopping to
            // the other Yahoo host would defeat a throttle that is ours, not theirs.
            if (r.throttledLocally) return emptyList()
            if (!r.ok) continue
            val parsed = runCatching { parse(listId, r.body) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /**
     * [pages] pages of one list, IN SEQUENCE.
     *
     * Sequential rather than parallel on purpose: these are three requests to the same host
     * within a second of each other, which is precisely the burst shape that earns a 429, and
     * page two is worthless without page one anyway. The whole ETF pass is six requests - it
     * does not need to be fast, it needs to arrive.
     *
     * Stops early on an empty page, so a list shorter than expected costs one wasted request
     * rather than all of them.
     */
    suspend fun fetchAll(listId: String, pages: Int = 3): List<EtfRow> {
        val out = ArrayList<EtfRow>(pages * MAX_COUNT)
        for (p in 0 until pages.coerceAtLeast(1)) {
            val page = fetch(listId, MAX_COUNT, p * MAX_COUNT)
            if (page.isEmpty()) break
            out.addAll(page)
            if (page.size < MAX_COUNT) break
        }
        return out
    }

    /** Split out so it can be tested against a saved response with no network. */
    internal fun parse(listId: String, body: String): List<EtfRow> {
        val res = JSONObject(body).optJSONObject("finance")?.optJSONArray("result")
            ?: return emptyList()
        val first = res.optJSONObject(0) ?: return emptyList()
        val quotes = first.optJSONArray("quotes") ?: return emptyList()
        val out = ArrayList<EtfRow>(quotes.length())
        for (i in 0 until quotes.length()) {
            val q = quotes.optJSONObject(i) ?: continue
            // BOTH LISTS RETURN THE OCCASIONAL NON-FUND. `top_performing_etfs` in particular
            // carries a few rows typed EQUITY, and scoring a share on an expense ratio it does
            // not have would put a fund-shaped hole in the middle of the list.
            if (q.optString("quoteType") != "ETF") continue
            val sym = q.optString("symbol").uppercase()
            if (sym.isBlank() || sym.contains('^')) continue
            out.add(
                EtfRow(
                    symbol = sym,
                    name = q.optString("longName").ifBlank {
                        q.optString("displayName").ifBlank { q.optString("shortName") }
                    },
                    price = d(q, "regularMarketPrice"),
                    changePct = d(q, "regularMarketChangePercent"),
                    // Yahoo publishes this already in percent - 0.03 is three basis points -
                    // unlike `dividendYield`, which it publishes as a fraction. Do not
                    // "normalise" one to match the other; they arrive different.
                    expenseRatio = d(q, "netExpenseRatio"),
                    netAssets = d(q, "netAssets"),
                    yieldPct = d(q, "yieldTTM").takeIf { it != 0.0 } ?: d(q, "dividendYield"),
                    ytdReturnPct = d(q, "ytdReturn"),
                    threeMonthPct = d(q, "trailingThreeMonthReturns").takeIf { it != 0.0 }
                        ?: d(q, "trailingThreeMonthNavReturns"),
                    // The 52-week CHANGE is a price return over exactly one year, which is
                    // the closest thing this feed carries to a one-year total return.
                    oneYearPct = d(q, "fiftyTwoWeekChangePercent"),
                    threeYearAnnualPct = d(q, "annualReturnNavY3"),
                    fiveYearAnnualPct = d(q, "annualReturnNavY5"),
                    avgVolume3M = d(q, "averageDailyVolume3Month").takeIf { it > 0 }
                        ?: d(q, "averageDailyVolume10Day"),
                    fiftyDayAvg = d(q, "fiftyDayAverage"),
                    twoHundredDayAvg = d(q, "twoHundredDayAverage"),
                    fiftyTwoWeekHigh = d(q, "fiftyTwoWeekHigh"),
                    fiftyTwoWeekLow = d(q, "fiftyTwoWeekLow"),
                    // Already in ms in this feed, unlike `earningsTimestamp` on the stock
                    // screener which arrives in seconds. Checked, not assumed.
                    inceptionMs = q.optLong("firstTradeDateMilliseconds", 0L),
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
}
