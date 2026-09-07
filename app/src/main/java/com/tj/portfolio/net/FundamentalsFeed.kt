package com.tj.portfolio.net

import com.tj.portfolio.data.AnalystRating
import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.EarningsEstimate
import com.tj.portfolio.data.EarningsResult
import com.tj.portfolio.data.Fundamentals

import com.tj.portfolio.data.RatingTrend
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * THE FUNDAMENTALS FEED: the valuation, dividend, growth, margin, balance-sheet and
 * ownership numbers other stock apps print, plus professional analyst ratings with each
 * firm's price target.
 *
 * PROVIDER CHAIN, and why it is in this order:
 *
 *  1. **Yahoo quoteSummary v10** - keyless, and the only free feed found that carries a
 *     PRICE TARGET next to each analyst's firm and grade (`upgradeDowngradeHistory`).
 *     It is also the provider the app already uses for quotes, so nothing new has to be
 *     trusted. The catch is that it needs a session: a cookie AND a matching crumb, or it
 *     answers 401 "Invalid Crumb". [YahooAuth] below does that handshake once per launch.
 *  2. **Nasdaq's public JSON** - what nasdaq.com's own pages call. No key, no handshake,
 *     small responses. It has the consensus target and the buy/hold/sell split but no
 *     per-analyst detail, so it is a floor rather than a substitute.
 *  3. **Finviz's quote page** - parsed HTML, and the only one of the three that is a scrape,
 *     so it is last and is only reached when the two APIs left real gaps. It does carry the
 *     per-analyst table with targets, which is what makes it worth having behind Yahoo.
 *
 * Everything merges with [Fundamentals.merge], which fills gaps and never overwrites: a
 * provider further down the chain can only supply a number the ones above it did not have.
 *
 * WHY THE RATINGS ARE A SEPARATE REQUEST. `upgradeDowngradeHistory` is the full history of
 * every action ever taken on the stock - for AAPL that alone is ~195KB, against ~15KB for
 * every other module combined. Pulling it on every detail-screen open, for a section the
 * user may never scroll to, would be the single heaviest thing the app does. So [core] gets
 * the numbers and [ratings] is fetched when the Analysts tab is actually opened.
 */
object FundamentalsFeed {

    /** Numbers move slowly - most of these change once a quarter. */
    const val CORE_TTL_MS = 6 * 3_600_000L

    /**
     * Analyst actions land through the day - but not THIS often.
     *
     * WAS 90 MINUTES, WHICH WAS THE WORST TTL IN THE APP (Round 56). This payload is
     * `upgradeDowngradeHistory`: the complete history of every analyst action ever taken on
     * the stock, ~195 KB for a large cap. A given stock gets a handful of new actions in a
     * YEAR, so a 90-minute expiry meant re-downloading 195 KB - of which 119 rows out of 120
     * were months old - every time the user came back to the Analysts tab after lunch. It was
     * the heaviest repeated transfer in the app by a wide margin, for a payload that is
     * essentially immutable.
     *
     * Twelve hours is still comfortably inside the window in which a new rating matters: an
     * upgrade published this morning is on the screen by this afternoon, and pulling to
     * refresh on the tab bypasses the TTL entirely for anyone who wants it sooner.
     */
    const val RATINGS_TTL_MS = 12 * 3_600_000L

    const val SRC_YAHOO = "Yahoo Finance"
    const val SRC_NASDAQ = "Nasdaq"
    const val SRC_FINVIZ = "Finviz"

    /**
     * The module set for the numbers.
     *
     * Every module in [CORE_MODULES_PROVEN] was verified against a live 200 response and is
     * present in the captured test fixtures. `earningsHistory` - the four reported quarters
     * of estimate-against-actual on the Earnings tab - was NOT, because Yahoo began
     * rate-limiting this container's IP before it could be captured.
     *
     * That distinction matters because of how quoteSummary fails: an unrecognised module
     * name does not get ignored, it rejects the WHOLE request. Asking for one unverified
     * module alongside seven proven ones would put every number on the Stats tab behind a
     * guess. So the request is tried with the extra module and, if and ONLY if the server
     * answers with a 4xx that means "I did not understand that", retried without it. A
     * throttle or a network failure does not trigger the retry - that would just double the
     * requests at the exact moment a provider is asking for fewer.
     */
    private const val CORE_MODULES_PROVEN =
        "assetProfile,summaryDetail,defaultKeyStatistics,financialData," +
            "recommendationTrend,calendarEvents,earningsTrend"

    private const val CORE_MODULES = "$CORE_MODULES_PROVEN,earningsHistory"

    private const val RATINGS_MODULES = "upgradeDowngradeHistory,financialData,recommendationTrend"

    // ================================================================= public

    /**
     * The numbers for one symbol. Falls down the provider chain until the result looks
     * complete enough to be worth showing.
     */
    suspend fun core(symbol: String): Fundamentals {
        val sym = symbol.uppercase()
        var out = yahoo(sym, CORE_MODULES, fallbackModules = CORE_MODULES_PROVEN)
            ?.let { parseYahoo(sym, it) } ?: empty(sym)

        // Nasdaq is cheap (two small JSON bodies) and is the only floor when Yahoo is
        // throttling us, which it does to a whole IP rather than to a key.
        if (out.values.size < MIN_USABLE_VALUES) {
            out = Fundamentals.merge(out, nasdaq(sym))
        }
        // Last resort, and a scrape, so it has to be earning its place: only when the two
        // APIs between them still left the page half empty.
        if (out.values.size < MIN_USABLE_VALUES) {
            out = Fundamentals.merge(out, finviz(sym))
        }
        return out.copy(fetched = System.currentTimeMillis())
    }

    /**
     * Analyst actions, newest first, with each firm's target. Yahoo first; Finviz fills in
     * when Yahoo has nothing, because a stock with no ratings at all and a stock the feed
     * could not be reached for look identical on screen otherwise.
     */
    suspend fun ratings(symbol: String): Fundamentals {
        val sym = symbol.uppercase()
        var out = yahoo(sym, RATINGS_MODULES)?.let { parseYahoo(sym, it) } ?: empty(sym)
        if (out.ratings.isEmpty()) out = Fundamentals.merge(out, finviz(sym))
        if (out.consensus?.hasTarget != true) {
            out = Fundamentals.merge(out, nasdaq(sym))
        }
        return out.copy(fetched = System.currentTimeMillis())
    }

    /**
     * Below this many numbers the page reads as broken rather than sparse, so the next
     * provider is worth the request. A genuinely thin symbol (a small ADR, a new listing)
     * will fall through the whole chain and still come back thin - that is correct; the
     * screen says which numbers are not reported rather than inventing them.
     */
    private const val MIN_USABLE_VALUES = 14

    private fun empty(sym: String) = Fundamentals(symbol = sym)

    // ================================================================== Yahoo

    /** What one quoteSummary request came back with, so the caller can tell WHY it failed. */
    private class YahooReply(val result: JSONObject?, val code: Int) {
        /**
         * The server understood the request but not the modules in it. Yahoo answers a bad
         * module name with a 4xx rather than by ignoring it, so this is the only condition
         * under which dropping a module and asking again is the right move.
         */
        val moduleRejected: Boolean get() = result == null && (code == 400 || code == 404)
    }

    /**
     * @param fallbackModules a reduced module set to retry with, but ONLY when the first
     *        attempt was rejected for its modules. Null means "do not retry".
     */
    /**
     * The quoteSummary plumbing, exposed for [HoldingsFeed].
     *
     * Sharing this rather than copying it is the point: the cookie+crumb handshake, the 401
     * re-mint, the crumb-independent cache key and the module-rejection fallback are four
     * things that took two rounds to get right and must not exist twice.
     */
    internal suspend fun quoteSummary(
        symbol: String,
        modules: String,
        fallbackModules: String? = null
    ): JSONObject? = yahoo(symbol, modules, fallbackModules)

    private suspend fun yahoo(
        symbol: String,
        modules: String,
        fallbackModules: String? = null
    ): JSONObject? {
        val first = yahooFetch(symbol, modules)
        if (first.result != null) return first.result
        if (fallbackModules != null && first.moduleRejected) {
            return yahooFetch(symbol, fallbackModules).result
        }
        return null
    }

    /** GET one quoteSummary module set, retrying once with a fresh crumb on a 401. */
    private suspend fun yahooFetch(symbol: String, modules: String): YahooReply {
        var last = 0
        for (attempt in 0..1) {
            val crumb = YahooAuth.crumb(force = attempt > 0)
            if (crumb.isBlank()) return YahooReply(null, last)
            for (host in listOf("query1", "query2")) {
                val base = "https://$host.finance.yahoo.com/v10/finance/quoteSummary/" +
                    MarketData.enc(symbol) + "?modules=" + modules
                val url = base + "&crumb=" + MarketData.enc(crumb)
                // CACHED UNDER THE URL WITHOUT THE CRUMB (Round 57).
                //
                // This is the app's heaviest payload - `upgradeDowngradeHistory` alone is
                // ~195 KB for a large cap, and it is the complete history of every analyst
                // action ever taken, a handful of new rows a YEAR. It had no conditional GET
                // at all, so a 12-hour TTL meant downloading the whole thing twice a day per
                // symbol whose Analysts tab was opened. And keying the cache on the raw URL
                // would not have helped either: the crumb is re-minted every twelve hours, so
                // the URL for the same resource changes twice a day and every entry would be
                // orphaned before it was ever used.
                val r = Http.get(
                    url, mapOf("Accept" to "application/json"),
                    conditionalKey = true, cacheAs = base
                )
                // A local cooldown means this host is being deliberately left alone; trying
                // the other Yahoo host would defeat the point, since the throttle is on us.
                if (r.throttledLocally) return YahooReply(null, r.code)
                last = r.code
                if (r.code == 401) {
                    // The crumb is stale or was minted against a cookie we no longer hold.
                    YahooAuth.invalidate()
                    break                       // out of the host loop, into the retry
                }
                if (!r.ok) continue
                val res = runCatching {
                    JSONObject(r.body).optJSONObject("quoteSummary")
                        ?.optJSONArray("result")?.optJSONObject(0)
                }.getOrNull()
                if (res != null) return YahooReply(res, r.code)
            }
        }
        return YahooReply(null, last)
    }

    /**
     * Yahoo wraps most numbers as `{ "raw": 36.68, "fmt": "36.68" }` but hands back a bare
     * number for some and an EMPTY OBJECT for "not reported". All three shapes have to mean
     * the same thing here: a value, or nothing. An empty object read as 0.0 is exactly the
     * fabricated-zero bug this project fixed on previous close.
     */
    private fun num(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toDouble().takeIf { it.isFinite() }
            is JSONObject -> if (v.has("raw") && !v.isNull("raw")) {
                v.optDouble("raw", Double.NaN).takeIf { it.isFinite() }
            } else null
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private fun str(o: JSONObject?, key: String): String {
        if (o == null || !o.has(key) || o.isNull(key)) return ""
        val v = o.opt(key)
        return when (v) {
            is String -> v
            is JSONObject -> v.optString("fmt")
            is Number -> v.toString()
            else -> ""
        }
    }

    /** Yahoo dates are epoch SECONDS. Also accepts the `{raw: seconds}` wrapper. */
    private fun date(o: JSONObject?, key: String): Double? =
        num(o, key)?.takeIf { it > 0 }?.times(1000.0)

    internal fun parseYahoo(symbol: String, res: JSONObject): Fundamentals {
        val summary = res.optJSONObject("summaryDetail")
        val stats = res.optJSONObject("defaultKeyStatistics")
        val fin = res.optJSONObject("financialData")
        val profile = res.optJSONObject("assetProfile")
        val cal = res.optJSONObject("calendarEvents")

        val v = LinkedHashMap<String, Double>()
        fun put(key: String, value: Double?) {
            if (value != null && value.isFinite()) v[key] = value
        }

        // ---- valuation
        put("marketCap", num(summary, "marketCap"))
        put("enterpriseValue", num(stats, "enterpriseValue"))
        put("peTrailing", num(summary, "trailingPE"))
        put("peForward", num(summary, "forwardPE") ?: num(stats, "forwardPE"))
        put("pegRatio", num(stats, "pegRatio") ?: num(stats, "trailingPegRatio"))
        put("priceToSales", num(summary, "priceToSalesTrailing12Months"))
        put("priceToBook", num(stats, "priceToBook"))
        put("evToEbitda", num(stats, "enterpriseToEbitda"))
        put("evToRevenue", num(stats, "enterpriseToRevenue"))
        put("bookValue", num(stats, "bookValue"))

        // ---- earnings and cash
        put("epsTrailing", num(stats, "trailingEps"))
        put("epsForward", num(stats, "forwardEps"))
        put("revenue", num(fin, "totalRevenue"))
        put("netIncome", num(stats, "netIncomeToCommon"))
        put("ebitda", num(fin, "ebitda"))
        put("grossProfit", num(fin, "grossProfits"))
        put("operatingCashflow", num(fin, "operatingCashflow"))
        put("freeCashflow", num(fin, "freeCashflow"))
        put("revenuePerShare", num(fin, "revenuePerShare"))

        // ---- growth
        put("revenueGrowth", num(fin, "revenueGrowth"))
        put("earningsGrowth", num(fin, "earningsGrowth"))
        put("earningsQuarterlyGrowth", num(stats, "earningsQuarterlyGrowth"))
        put("change52Week", num(stats, "52WeekChange"))
        put("sp500Change52Week", num(stats, "SandP52WeekChange"))

        // ---- profitability
        put("grossMargins", num(fin, "grossMargins"))
        put("operatingMargins", num(fin, "operatingMargins"))
        put("profitMargins", num(fin, "profitMargins") ?: num(stats, "profitMargins"))
        put("ebitdaMargins", num(fin, "ebitdaMargins"))
        put("returnOnEquity", num(fin, "returnOnEquity"))
        put("returnOnAssets", num(fin, "returnOnAssets"))

        // ---- dividends. Yahoo has flip-flopped between a fraction (0.0033) and a
        // percentage (0.33) on the yield fields across API revisions, and the two differ by
        // a factor of a hundred - "0.33% yield" against "33% yield" is not a rounding
        // difference, it is a different investment. Normalised to a fraction here by the
        // only test that works on either revision: no real common stock yields over 100%.
        put("dividendRate", num(summary, "dividendRate"))
        put("dividendYield", asFraction(num(summary, "dividendYield")))
        put("trailingDividendRate", num(summary, "trailingAnnualDividendRate"))
        put("trailingDividendYield", asFraction(num(summary, "trailingAnnualDividendYield")))
        put("payoutRatio", num(summary, "payoutRatio"))
        put("fiveYearAvgDividendYield", num(summary, "fiveYearAvgDividendYield"))
        put("exDividendDate", date(summary, "exDividendDate") ?: date(cal, "exDividendDate"))
        put("dividendDate", date(cal, "dividendDate"))

        // ---- balance sheet
        put("totalCash", num(fin, "totalCash"))
        put("totalCashPerShare", num(fin, "totalCashPerShare"))
        put("totalDebt", num(fin, "totalDebt"))
        put("debtToEquity", num(fin, "debtToEquity"))
        put("currentRatio", num(fin, "currentRatio"))
        put("quickRatio", num(fin, "quickRatio"))

        // ---- price and risk
        put("beta", num(summary, "beta") ?: num(stats, "beta"))
        put("fiftyTwoWeekHigh", num(summary, "fiftyTwoWeekHigh"))
        put("fiftyTwoWeekLow", num(summary, "fiftyTwoWeekLow"))
        put("fiftyDayAverage", num(summary, "fiftyDayAverage"))
        put("twoHundredDayAverage", num(summary, "twoHundredDayAverage"))
        put("volume", num(summary, "volume") ?: num(summary, "regularMarketVolume"))
        put("averageVolume", num(summary, "averageVolume"))
        put("averageVolume10days", num(summary, "averageVolume10days"))

        // ---- ownership
        put("sharesOutstanding", num(stats, "sharesOutstanding"))
        put("floatShares", num(stats, "floatShares"))
        put("heldPercentInsiders", num(stats, "heldPercentInsiders"))
        put("heldPercentInstitutions", num(stats, "heldPercentInstitutions"))
        put("shortPercentOfFloat", num(stats, "shortPercentOfFloat") ?: num(stats, "sharesPercentSharesOut"))
        put("sharesShort", num(stats, "sharesShort"))
        put("shortRatio", num(stats, "shortRatio"))
        put("employees", num(profile, "fullTimeEmployees"))

        val texts = LinkedHashMap<String, String>()
        fun putText(k: String, s: String) { if (s.isNotBlank()) texts[k] = s }
        putText("sector", str(profile, "sector"))
        putText("industry", str(profile, "industry"))
        putText("country", str(profile, "country"))
        putText("website", str(profile, "website"))
        putText("currency", str(fin, "financialCurrency").ifBlank { str(summary, "currency") })
        putText("lastSplitFactor", str(stats, "lastSplitFactor"))

        return Fundamentals(
            symbol = symbol,
            values = v,
            texts = texts,
            consensus = parseYahooConsensus(fin, res.optJSONObject("recommendationTrend")),
            ratings = parseYahooRatings(res.optJSONObject("upgradeDowngradeHistory")),
            trend = parseYahooTrend(res.optJSONObject("recommendationTrend")),
            estimates = parseYahooEstimates(res.optJSONObject("earningsTrend")),
            history = parseYahooHistory(res.optJSONObject("earningsHistory")),
            earningsDate = nextEarnings(cal),
            profile = str(profile, "longBusinessSummary"),
            sources = listOf(SRC_YAHOO),
            fetched = System.currentTimeMillis()
        )
    }

    /** A yield that arrived as 3.4 rather than 0.034. See the note at the call site. */
    private fun asFraction(v: Double?): Double? =
        when {
            v == null -> null
            v > 1.0 -> v / 100.0
            else -> v
        }

    private fun nextEarnings(cal: JSONObject?): Long {
        val e = cal?.optJSONObject("earnings") ?: return 0L
        val arr = e.optJSONArray("earningsDate") ?: return 0L
        for (i in 0 until arr.length()) {
            val o = arr.opt(i)
            val secs = when (o) {
                is JSONObject -> o.optDouble("raw", 0.0)
                is Number -> o.toDouble()
                else -> 0.0
            }
            if (secs > 0) return (secs * 1000).toLong()
        }
        return 0L
    }

    private fun parseYahooConsensus(fin: JSONObject?, trend: JSONObject?): Consensus? {
        val now = trend?.optJSONArray("trend")?.optJSONObject(0)
        val mean = num(fin, "recommendationMean") ?: 0.0
        val target = num(fin, "targetMeanPrice") ?: 0.0
        val sb = now?.optInt("strongBuy", 0) ?: 0
        val b = now?.optInt("buy", 0) ?: 0
        val h = now?.optInt("hold", 0) ?: 0
        val s = now?.optInt("sell", 0) ?: 0
        val ss = now?.optInt("strongSell", 0) ?: 0
        if (mean <= 0.0 && target <= 0.0 && sb + b + h + s + ss == 0) return null
        return Consensus(
            mean = mean,
            key = str(fin, "recommendationKey"),
            analysts = (num(fin, "numberOfAnalystOpinions") ?: 0.0).toInt(),
            targetMean = target,
            targetHigh = num(fin, "targetHighPrice") ?: 0.0,
            targetLow = num(fin, "targetLowPrice") ?: 0.0,
            targetMedian = num(fin, "targetMedianPrice") ?: 0.0,
            strongBuy = sb, buy = b, hold = h, sell = s, strongSell = ss
        )
    }

    private fun parseYahooTrend(trend: JSONObject?): List<RatingTrend> {
        val arr = trend?.optJSONArray("trend") ?: return emptyList()
        val out = ArrayList<RatingTrend>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = RatingTrend(
                period = o.optString("period"),
                strongBuy = o.optInt("strongBuy", 0),
                buy = o.optInt("buy", 0),
                hold = o.optInt("hold", 0),
                sell = o.optInt("sell", 0),
                strongSell = o.optInt("strongSell", 0)
            )
            if (t.total > 0) out.add(t)
        }
        return out
    }

    private fun parseYahooRatings(hist: JSONObject?): List<AnalystRating> {
        val arr = hist?.optJSONArray("history") ?: return emptyList()
        val out = ArrayList<AnalystRating>(minOf(arr.length(), MAX_RATINGS))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val firm = o.optString("firm").trim()
            if (firm.isEmpty()) continue
            val secs = o.optLong("epochGradeDate", 0L)
            out.add(
                AnalystRating(
                    firm = firm,
                    date = if (secs > 0) secs * 1000L else 0L,
                    action = AnalystRating.actionOf(o.optString("action")),
                    toGrade = o.optString("toGrade").trim(),
                    fromGrade = o.optString("fromGrade").trim(),
                    target = o.optDouble("currentPriceTarget", 0.0).let { if (it.isFinite()) it else 0.0 },
                    priorTarget = o.optDouble("priorPriceTarget", 0.0).let { if (it.isFinite()) it else 0.0 },
                    targetAction = o.optString("priceTargetAction").trim(),
                    source = SRC_YAHOO
                )
            )
        }
        return out
            .distinctBy { it.id }
            .sortedByDescending { it.date }
            .take(MAX_RATINGS)
    }

    /**
     * Yahoo returns the WHOLE history - hundreds of rows going back years for a large cap.
     * Capped here rather than in the UI so the cap also bounds what is written to the
     * database and held in memory.
     */
    private const val MAX_RATINGS = 120

    private fun parseYahooEstimates(trend: JSONObject?): List<EarningsEstimate> {
        val arr = trend?.optJSONArray("trend") ?: return emptyList()
        val out = ArrayList<EarningsEstimate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val eps = o.optJSONObject("earningsEstimate")
            val rev = o.optJSONObject("revenueEstimate")
            val e = EarningsEstimate(
                period = o.optString("period"),
                endDate = o.optString("endDate"),
                epsAvg = num(eps, "avg") ?: 0.0,
                epsLow = num(eps, "low") ?: 0.0,
                epsHigh = num(eps, "high") ?: 0.0,
                epsYearAgo = num(eps, "yearAgoEps") ?: 0.0,
                epsGrowth = num(eps, "growth") ?: 0.0,
                analysts = (num(eps, "numberOfAnalysts") ?: 0.0).toInt(),
                revenueAvg = num(rev, "avg") ?: 0.0,
                revenueGrowth = num(rev, "growth") ?: 0.0
            )
            if (e.epsAvg != 0.0 || e.revenueAvg != 0.0) out.add(e)
        }
        return out
    }

    private fun parseYahooHistory(hist: JSONObject?): List<EarningsResult> {
        val arr = hist?.optJSONArray("history") ?: return emptyList()
        val out = ArrayList<EarningsResult>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val actual = num(o, "epsActual") ?: continue
            out.add(
                EarningsResult(
                    quarter = o.optString("quarter").ifBlank { str(o, "quarter") },
                    date = (date(o, "quarter") ?: 0.0).toLong(),
                    epsEstimate = num(o, "epsEstimate") ?: 0.0,
                    epsActual = actual,
                    surprisePct = (num(o, "surprisePercent") ?: 0.0) * 100.0
                )
            )
        }
        return out.sortedByDescending { it.date }
    }

    // ================================================================= Nasdaq

    /**
     * nasdaq.com's own JSON. No key and no handshake, which is exactly what a fallback
     * needs to be. It gives a consensus target and the buy/hold/sell split, plus a short
     * summary block - not the per-analyst detail, so it never replaces Yahoo.
     */
    private suspend fun nasdaq(symbol: String): Fundamentals {
        val v = LinkedHashMap<String, Double>()
        val texts = LinkedHashMap<String, String>()
        var consensus: Consensus? = null

        val s = Http.get(
            "https://api.nasdaq.com/api/quote/" + MarketData.enc(symbol) +
                "/summary?assetclass=stocks",
            mapOf("Accept" to "application/json"), conditionalKey = true
        )
        if (s.ok) runCatching { parseNasdaqSummary(s.body, v, texts) }

        val t = Http.get(
            "https://api.nasdaq.com/api/analyst/" + MarketData.enc(symbol) + "/targetprice",
            mapOf("Accept" to "application/json"), conditionalKey = true
        )
        if (t.ok) consensus = runCatching { parseNasdaqTarget(t.body) }.getOrNull()

        if (v.isEmpty() && texts.isEmpty() && consensus == null) return empty(symbol)
        return Fundamentals(
            symbol = symbol,
            values = v,
            texts = texts,
            consensus = consensus,
            sources = listOf(SRC_NASDAQ),
            fetched = System.currentTimeMillis()
        )
    }

    internal fun parseNasdaqSummary(
        body: String,
        v: MutableMap<String, Double>,
        texts: MutableMap<String, String>
    ) {
        val d = JSONObject(body).optJSONObject("data")?.optJSONObject("summaryData") ?: return
        fun field(k: String): String {
            val s = d.optJSONObject(k)?.optString("value").orEmpty().trim()
            return if (s.isBlank() || s.equals("N/A", true)) "" else s
        }
        loose(field("MarketCap"))?.let { v["marketCap"] = it }
        loose(field("ShareVolume"))?.let { v["volume"] = it }
        loose(field("AverageVolume"))?.let { v["averageVolume"] = it }
        loose(field("AnnualizedDividend"))?.let { v["dividendRate"] = it }
        loose(field("Yield"))?.let { v["dividendYield"] = it / 100.0 }
        loose(field("PERatio"))?.let { v["peTrailing"] = it }
        loose(field("EarningsPerShare"))?.let { v["epsTrailing"] = it }
        parseUsDate(field("ExDividendDate"))?.let { v["exDividendDate"] = it.toDouble() }
        parseUsDate(field("DividendPaymentDate"))?.let { v["dividendDate"] = it.toDouble() }
        // "$344.5699/$225.95" - high first, then low.
        val range = field("FiftTwoWeekHighLow").split("/")
        if (range.size == 2) {
            loose(range[0])?.let { v["fiftyTwoWeekHigh"] = it }
            loose(range[1])?.let { v["fiftyTwoWeekLow"] = it }
        }
        field("Sector").takeIf { it.isNotBlank() }?.let { texts["sector"] = it }
        field("Industry").takeIf { it.isNotBlank() }?.let { texts["industry"] = it }
        field("Exchange").takeIf { it.isNotBlank() }?.let { texts["exchange"] = it }
    }

    internal fun parseNasdaqTarget(body: String): Consensus? {
        val o = JSONObject(body).optJSONObject("data")?.optJSONObject("consensusOverview")
            ?: return null
        val buy = o.optInt("buy", 0)
        val hold = o.optInt("hold", 0)
        val sell = o.optInt("sell", 0)
        val target = o.optDouble("priceTarget", 0.0)
        if (buy + hold + sell == 0 && target <= 0.0) return null
        // Nasdaq gives three buckets, not Yahoo's five, and no 1-5 mean. Deriving one on the
        // same scale keeps the consensus card reading the same whichever provider answered:
        // buy = 2, hold = 3, sell = 4, which is where those buckets sit on Yahoo's scale.
        val votes = buy + hold + sell
        val mean = if (votes > 0) (buy * 2.0 + hold * 3.0 + sell * 4.0) / votes else 0.0
        return Consensus(
            mean = mean,
            analysts = votes,
            targetMean = target,
            targetHigh = o.optDouble("highPriceTarget", 0.0),
            targetLow = o.optDouble("lowPriceTarget", 0.0),
            buy = buy, hold = hold, sell = sell
        )
    }

    // ================================================================= Finviz

    /**
     * The last resort, and the only scrape in the chain: finviz.com's quote page carries
     * both an 84-row snapshot table and a dated analyst table with each firm's target.
     *
     * PARSED BY MARKER, NOT BY TABLE. The snapshot is laid out as SIX separate `<table>`
     * elements inside a CSS grid, so anything that walks "the table" finds a sixth of the
     * numbers. Every cell does carry a stable class - `snapshot-td-label` against
     * `snapshot-td-content` - so the parser walks those in document order and pairs them,
     * which is layout-independent and survives the grid being re-arranged again.
     */
    private suspend fun finviz(symbol: String): Fundamentals {
        val r = Http.get(
            "https://finviz.com/quote.ashx?t=" + MarketData.enc(symbol),
            mapOf("Accept" to "text/html,application/xhtml+xml"), conditionalKey = true
        )
        if (!r.ok || r.body.isBlank()) return empty(symbol)
        return runCatching { parseFinviz(symbol, r.body) }.getOrDefault(empty(symbol))
    }

    private val FINVIZ_CELL = Regex(
        "snapshot-td-(label|content)\"[^>]*>(.*?)</div>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val TAG = Regex("<[^>]+>")
    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CELL = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    internal fun parseFinviz(symbol: String, html: String): Fundamentals {
        val pairs = LinkedHashMap<String, String>()
        var pendingLabel: String? = null
        for (m in FINVIZ_CELL.findAll(html)) {
            val kind = m.groupValues[1]
            val text = clean(m.groupValues[2])
            if (kind.equals("label", true)) {
                pendingLabel = text
            } else {
                val l = pendingLabel
                pendingLabel = null
                // A repeated label is Finviz reusing one word for two different rows -
                // "EPS next Y" is both a dollar estimate and a growth percentage. The FIRST
                // wins here and the growth one is picked out by name below.
                if (l != null && l.isNotBlank() && !pairs.containsKey(l)) pairs[l] = text
            }
        }

        val v = LinkedHashMap<String, Double>()
        fun map(label: String, key: String, scale: Double = 1.0) {
            val raw = pairs[label] ?: return
            val n = loose(raw) ?: return
            v[key] = n * scale
        }
        // A Finviz percentage cell reads "27.62%" - a percentage, where the catalogue's
        // FRACTION metrics want 0.2762.
        fun pctFrac(label: String, key: String) = map(label, key, 0.01)

        map("Market Cap", "marketCap")
        map("Enterprise Value", "enterpriseValue")
        map("Income", "netIncome")
        map("Sales", "revenue")
        map("Book/sh", "bookValue")
        map("Cash/sh", "totalCashPerShare")
        map("P/E", "peTrailing")
        map("Forward P/E", "peForward")
        map("PEG", "pegRatio")
        map("P/S", "priceToSales")
        map("P/B", "priceToBook")
        map("EV/EBITDA", "evToEbitda")
        map("EV/Sales", "evToRevenue")
        map("Quick Ratio", "quickRatio")
        map("Current Ratio", "currentRatio")
        // Finviz prints debt/equity as a plain multiple (0.78); the catalogue's unit for
        // this metric is PERCENT, matching Yahoo's 78.45. Same number, different dress.
        map("Debt/Eq", "debtToEquity", 100.0)
        map("EPS (ttm)", "epsTrailing")
        map("Employees", "employees")
        map("Beta", "beta")
        map("Avg Volume", "averageVolume")
        map("Volume", "volume")
        map("Shs Outstand", "sharesOutstanding")
        map("Shs Float", "floatShares")
        map("Short Interest", "sharesShort")
        map("Short Ratio", "shortRatio")
        pctFrac("Short Float", "shortPercentOfFloat")
        pctFrac("Insider Own", "heldPercentInsiders")
        pctFrac("Inst Own", "heldPercentInstitutions")
        pctFrac("ROA", "returnOnAssets")
        pctFrac("ROE", "returnOnEquity")
        pctFrac("Gross Margin", "grossMargins")
        pctFrac("Oper. Margin", "operatingMargins")
        pctFrac("Profit Margin", "profitMargins")
        pctFrac("Payout", "payoutRatio")
        pctFrac("EPS Q/Q", "earningsQuarterlyGrowth")
        pctFrac("Sales Q/Q", "revenueGrowth")
        pctFrac("EPS Y/Y TTM", "earningsGrowth")
        pctFrac("Perf Year", "change52Week")
        // "344.57 -7.14%" - the level first, then the distance from it.
        pairs["52W High"]?.let { loose(it)?.let { n -> v["fiftyTwoWeekHigh"] = n } }
        pairs["52W Low"]?.let { loose(it)?.let { n -> v["fiftyTwoWeekLow"] = n } }
        // "1.08 (0.34%)" - the dollar rate, then the yield.
        pairs["Dividend Est."]?.let { s ->
            loose(s)?.let { v["dividendRate"] = it }
            insideParens(s)?.let { v["dividendYield"] = it / 100.0 }
        }
        pairs["Dividend TTM"]?.let { s ->
            loose(s)?.let { v["trailingDividendRate"] = it }
            insideParens(s)?.let { v["trailingDividendYield"] = it / 100.0 }
        }
        pairs["Dividend Ex-Date"]?.let { s ->
            parseUsDate(s)?.let { v["exDividendDate"] = it.toDouble() }
        }
        // "EPS next Y" is listed twice: once as a dollar estimate and once as a growth
        // percentage. Only the dollar one is the forward EPS.
        pairs["EPS next Y"]?.let { s -> if (!s.contains('%')) loose(s)?.let { v["epsForward"] = it } }

        val texts = LinkedHashMap<String, String>()

        return Fundamentals(
            symbol = symbol,
            values = v,
            texts = texts,
            ratings = parseFinvizRatings(html),
            sources = listOf(SRC_FINVIZ),
            fetched = System.currentTimeMillis()
        )
    }

    internal fun parseFinvizRatings(html: String): List<AnalystRating> {
        val start = html.indexOf("js-table-ratings")
        if (start < 0) return emptyList()
        val tableStart = html.lastIndexOf("<table", start).let { if (it < 0) start else it }
        val end = html.indexOf("</table>", start).let { if (it < 0) html.length else it }
        val table = html.substring(tableStart, end)

        val out = ArrayList<AnalystRating>()
        for (row in ROW.findAll(table)) {
            val cells = CELL.findAll(row.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
            if (cells.size < 4) continue
            if (cells[0].equals("Date", true)) continue          // the header row
            val date = parseFinvizDate(cells[0]) ?: continue
            val firm = cells[2].trim()
            if (firm.isBlank()) continue
            val grades = splitArrow(cells[3])
            val targets = if (cells.size > 4) splitArrow(cells[4]) else "" to ""
            out.add(
                AnalystRating(
                    firm = firm,
                    date = date,
                    action = AnalystRating.actionOf(cells[1]),
                    toGrade = grades.second.ifBlank { grades.first },
                    fromGrade = if (grades.second.isBlank()) "" else grades.first,
                    target = loose(targets.second.ifBlank { targets.first }) ?: 0.0,
                    priorTarget = if (targets.second.isBlank()) 0.0 else (loose(targets.first) ?: 0.0),
                    source = SRC_FINVIZ
                )
            )
        }
        return out.distinctBy { it.id }.sortedByDescending { it.date }.take(MAX_RATINGS)
    }

    /** "Neutral &rarr; Buy" -> ("Neutral", "Buy"). A single value -> (value, ""). */
    internal fun splitArrow(s: String): Pair<String, String> {
        val i = s.indexOf('→')
        return if (i < 0) s.trim() to "" else s.substring(0, i).trim() to s.substring(i + 1).trim()
    }

    private val FINVIZ_DATE = java.lang.ThreadLocal.withInitial {
        java.text.SimpleDateFormat("MMM-dd-yy", java.util.Locale.US)
    }

    /** Finviz dates the rating table as "Sep-01-26". */
    internal fun parseFinvizDate(s: String): Long? = runCatching {
        val f = FINVIZ_DATE.get() ?: return null
        f.isLenient = false
        f.parse(s.trim())?.time
    }.getOrNull()

    // ============================================================== utilities

    /** Strip tags, decode the handful of entities these pages use, collapse whitespace. */
    internal fun clean(raw: String): String =
        TAG.replace(raw, " ")
            .replace("&rarr;", "→")
            .replace("&#8594;", "→")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * The first number in a string that may be dressed up any number of ways:
     * "$1.08", "4669.70B", "-2.25%", "1,908,115", "344.57 -7.14%", "N/A", "-".
     *
     * A bare "-" is Finviz's "not reported" and MUST come back as null rather than as a
     * minus sign attached to nothing - the same fabricated-value trap as a zeroed previous
     * close, one screen further along.
     */
    internal fun loose(raw: String?): Double? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "-" || s.equals("N/A", true)) return null
        val m = LOOSE_NUM.find(s.replace(",", "")) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val suffix = m.groupValues[2].uppercase()
        val scaled = when (suffix) {
            "T" -> n * 1e12
            "B" -> n * 1e9
            "M" -> n * 1e6
            "K" -> n * 1e3
            else -> n
        }
        return scaled.takeIf { it.isFinite() }
    }

    private val LOOSE_NUM = Regex("(-?\\d+(?:\\.\\d+)?)\\s*([TBMK])?", RegexOption.IGNORE_CASE)

    /** "1.08 (0.34%)" -> 0.34. Null when there is no parenthesised number. */
    internal fun insideParens(s: String): Double? {
        val i = s.indexOf('(')
        val j = s.indexOf(')', i + 1)
        if (i < 0 || j < 0) return null
        return loose(s.substring(i + 1, j))
    }

    /**
     * BUILT ONCE PER THREAD, not four times per call.
     *
     * `parseUsDate` constructed up to four `SimpleDateFormat` instances on every invocation,
     * and it is called per date field per symbol. `SimpleDateFormat` is one of the more
     * expensive objects in the JDK to construct - it builds a `DateFormatSymbols` and a
     * `Calendar` - and it is not thread-safe, which is why the rest of this project uses a
     * `ThreadLocal` (see `util/Format.kt` and `FINVIZ_DATE` below). This one had been missed.
     */
    private val US_DATE_FORMATS = object : ThreadLocal<List<java.text.SimpleDateFormat>>() {
        override fun initialValue(): List<java.text.SimpleDateFormat> =
            listOf("MMM d, yyyy", "MMM dd, yyyy", "MM/dd/yyyy", "yyyy-MM-dd").map {
                java.text.SimpleDateFormat(it, java.util.Locale.US).apply { isLenient = false }
            }
    }

    /** "Aug 10, 2026" / "08/10/2026" -> epoch millis. Null when it is not a date. */
    internal fun parseUsDate(s: String): Long? {
        val t = s.trim()
        if (t.isBlank()) return null
        for (f in US_DATE_FORMATS.get() ?: return null) {
            val r = runCatching { f.parse(t)?.time }.getOrNull()
            if (r != null && r > 0) return r
        }
        return null
    }
}
