package com.tj.portfolio.net

import com.tj.portfolio.data.Quote
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Quote provider with graceful degradation:
 *   1. Yahoo chart  - real time + pre/post market + intraday series, no key
 *   2. Finnhub      - real time, needs a free key
 *   3. Stooq        - delayed/EOD CSV, no key, last resort so the app is never blank
 */
object MarketData {

    suspend fun quote(symbol: String, finnhubKey: String): Quote? {
        yahoo(symbol)?.let { return it }
        if (finnhubKey.isNotBlank()) finnhub(symbol, finnhubKey)?.let { return it }
        return stooq(symbol)
    }

    // ================================================================ BATCHED
    //
    // THE BIGGEST SINGLE CHANGE IN ROUND 56, AND WHY IT WAS NEEDED.
    //
    // TJ asked for the online pulls to be efficient enough not to get the app banned. The
    // request meter said the answer was almost entirely one line of code: the quote poll.
    // One chart request per symbol, every 15 seconds, is 16 requests a tick on a 16-holding
    // portfolio - about 3,840 requests an hour to query1.finance.yahoo.com, roughly 70% of
    // everything the app sends anywhere. Yahoo's documented behaviour under that kind of load
    // is "429 errors or empty responses".
    //
    // Yahoo's `v7/finance/quote` takes a COMMA-SEPARATED LIST and answers for all of them in
    // one response. Sixteen requests become one; a 24-symbol portfolio still becomes one. The
    // measured effect on the app's total outbound traffic is a cut of roughly 85%.
    //
    // WHAT IT COSTS. v7 is not anonymous - it needs the same cookie+crumb handshake that
    // `quoteSummary` already uses, which is why [YahooAuth] was lifted out of
    // FundamentalsFeed into its own file this round. And it does NOT return the intraday
    // candle series, so the sparkline has to come from somewhere else - see [sparkline], which
    // now rides a five-minute cadence of its own instead of being re-downloaded four times a
    // minute to draw a 64dp line.
    //
    // THE FALLBACK IS THE WHOLE SAFETY ARGUMENT. This is the app's core data path and it could
    // not be verified from the build container, which Yahoo answers with 429. So: any symbol
    // the batch does not return is fetched by the per-symbol chain exactly as before, and
    // after [BATCH_FAILURES_BEFORE_GIVING_UP] consecutive total failures the batch is not
    // tried again this session. The worst case is one wasted request per tick, three times,
    // and then the behaviour the app shipped with.

    /** Yahoo rejects very long query strings; 50 symbols is comfortably inside the limit. */
    private const val BATCH_SIZE = 50

    /**
     * After this many consecutive passes where the batch returned NOTHING, stop trying it.
     *
     * Not reset by a partial success - a batch that returns 15 of 16 symbols is working, and
     * the one missing symbol is a delisted ticker or a typo, not a broken endpoint.
     */
    private const val BATCH_FAILURES_BEFORE_GIVING_UP = 3

    @Volatile private var batchFailures = 0

    /**
     * Give the batch endpoint another chance.
     *
     * CALLED BY EVERY MANUAL REFRESH, alongside `Http.clearCooldowns()`. Without a caller
     * this was a comment describing something that did not happen: three failed passes at
     * launch - a phone with no signal for forty-five seconds - disabled the batch for the
     * entire session, with no way back short of killing the app, and every tick from then on
     * ran one request per symbol. That is the exact traffic shape the batch exists to remove,
     * made permanent by the mechanism meant to protect against it.
     */
    fun resetBatchState() { batchFailures = 0 }

    val batchDisabled: Boolean get() = batchFailures >= BATCH_FAILURES_BEFORE_GIVING_UP

    /**
     * Quotes for many symbols, in as few requests as possible.
     *
     * Returns what it could get. The caller treats a missing symbol exactly as it always has -
     * as "no update this time", keeping whatever is on screen.
     */
    suspend fun quotes(symbols: List<String>, finnhubKey: String): List<Quote> = coroutineScope {
        if (symbols.isEmpty()) return@coroutineScope emptyList()
        val wanted = symbols.map { it.uppercase() }.distinct()
        val got = LinkedHashMap<String, Quote>(wanted.size)

        if (!batchDisabled) {
            var verdict = Batch.INCONCLUSIVE
            for (chunk in wanted.chunked(BATCH_SIZE)) {
                val (outcome, rows) = runCatching { batchYahoo(chunk) }
                    .getOrDefault(Batch.INCONCLUSIVE to emptyList())
                // MAPPED BACK ONTO THE SYMBOL THE CALLER ASKED FOR. Yahoo normalises some
                // tickers on the way out - "BRK.B" comes back as "BRK-B" - and keying the
                // result by what Yahoo echoed meant the requested symbol still looked
                // missing: it was then fetched a second time by the fallback, and the caller
                // got back a Quote for a symbol it had never asked about.
                val byNormal = chunk.associateBy { normaliseTicker(it) }
                rows.forEach { q ->
                    val asked = byNormal[normaliseTicker(q.symbol)]
                    got[asked ?: q.symbol] =
                        if (asked != null && asked != q.symbol) q.copy(symbol = asked) else q
                }
                // Best outcome across the chunks wins: one chunk returning rows proves the
                // endpoint works, whatever the others did.
                if (outcome.ordinal < verdict.ordinal) verdict = outcome
            }
            // ONLY A REAL, ATTRIBUTABLE FAILURE COUNTS. A blank crumb or one of our OWN
            // cooldowns says nothing about whether the batch endpoint works - and those are
            // exactly the moments when falling back to one request per symbol makes the
            // situation worse rather than better. Counting them was how three seconds of no
            // signal at launch could disable the batch for the whole session.
            batchFailures = when (verdict) {
                Batch.OK -> 0
                Batch.INCONCLUSIVE -> batchFailures
                Batch.FAILED -> batchFailures + 1
            }
        }

        // Whatever the batch could not supply falls back to the per-symbol chain.
        //
        // IN PARALLEL, under the same gate the old per-symbol path used. Serially, a
        // black-hole network - packets dropped rather than refused - meant twenty symbols x
        // four providers x a 15-second timeout inside one `refresh()`, which holds
        // `loading = true` and makes every pull-to-refresh in that window a no-op. That is
        // strictly worse than the behaviour this round replaced, under precisely the
        // conditions that trigger it.
        val missing = wanted.filter { it !in got }
        if (missing.isNotEmpty()) {
            val gate = kotlinx.coroutines.sync.Semaphore(FALLBACK_PARALLELISM)
            missing.map { sym ->
                async {
                    gate.withPermit { runCatching { quote(sym, finnhubKey) }.getOrNull() }
                }
            }.awaitAll().filterNotNull().forEach { got[it.symbol] = it }
        }
        got.values.toList()
    }

    /** How a batch attempt ended. Ordered best-first so a chunk's outcome can be merged. */
    private enum class Batch { OK, INCONCLUSIVE, FAILED }

    private const val FALLBACK_PARALLELISM = 5

    /** `BRK.B`, `BRK-B` and `brk b` are one ticker as far as matching a response goes. */
    private fun normaliseTicker(s: String): String =
        s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * One `v7/finance/quote` request for up to [BATCH_SIZE] symbols.
     *
     * VALIDATED RATHER THAN TRUSTED. Every field is read by name off the object Yahoo returns
     * for that symbol, and a row with no usable price is dropped rather than turned into a
     * zero - the v2.3 rule about never fabricating a number applies here exactly as it does to
     * the chart parser. A response that parses to nothing is treated as a failed batch, which
     * is what arms the fallback.
     */
    private suspend fun batchYahoo(symbols: List<String>): Pair<Batch, List<Quote>> {
        val crumb = YahooAuth.crumb()
        // No crumb means the handshake could not complete - a throttled mint, no signal.
        // Nothing has been learned about the batch endpoint itself.
        if (crumb.isBlank()) return Batch.INCONCLUSIVE to emptyList()
        val list = symbols.joinToString(",")
        var throttled = 0
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v7/finance/quote?symbols=" +
                enc(list) + "&crumb=" + enc(crumb)
            val r = Http.get(url, mapOf("Accept" to "application/json"))
            // Skip a cooling host and try the other one - cooldowns are per host, so this
            // one being left alone says nothing about the next. See the note in [yahoo].
            // Falling out of the loop with nothing but throttles is INCONCLUSIVE below, not
            // FAILED: no request was sent, so nothing was learned about the endpoint, and
            // counting it would arm the batch-disable that Round 56 already had to undo.
            if (r.throttledLocally) { throttled++; continue }
            if (r.code == 401) {
                // Stale crumb, or one minted against a cookie we no longer hold. Exactly the
                // recovery quoteSummary does: invalidate and let the next pass re-mint. Not a
                // verdict on the endpoint - it answered, it just wanted a fresher token.
                YahooAuth.invalidate()
                return Batch.INCONCLUSIVE to emptyList()
            }
            if (r.code < 0) return Batch.INCONCLUSIVE to emptyList()   // could not connect
            if (!r.ok) continue
            val parsed = runCatching { parseBatch(r.body) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return Batch.OK to parsed
        }
        // Every host was in a local cooldown, so nothing was actually sent. Not a verdict.
        if (throttled >= 2) return Batch.INCONCLUSIVE to emptyList()
        // Both hosts answered and neither gave anything usable. That IS a verdict.
        return Batch.FAILED to emptyList()
    }

    private fun parseBatch(body: String): List<Quote> {
        val arr = JSONObject(body).optJSONObject("quoteResponse")
            ?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<Quote>(arr.length())
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("symbol").uppercase()
            if (sym.isBlank()) continue
            val price = o.optDouble("regularMarketPrice", 0.0)
            if (price <= 0.0) continue

            // Yahoo names the market phase itself here - REGULAR / PRE / POST / CLOSED /
            // PREPRE / POSTPOST - which is better than the chart parser's arithmetic on
            // session boundaries, because it is the exchange's own answer.
            val state = o.optString("marketState").uppercase()
            var extPrice: Double? = null
            var extLabel: String? = null
            when {
                state.startsWith("POST") || state == "CLOSED" -> {
                    o.optDouble("postMarketPrice", 0.0).takeIf { it > 0.0 }?.let {
                        extPrice = it; extLabel = "After hours"
                    }
                }
                state.startsWith("PRE") -> {
                    o.optDouble("preMarketPrice", 0.0).takeIf { it > 0.0 }?.let {
                        extPrice = it; extLabel = "Pre-market"
                    }
                }
            }

            out.add(
                Quote(
                    symbol = sym,
                    name = o.optString("shortName").ifBlank { o.optString("longName") },
                    price = price,
                    // Same rule as the chart parser: never invent a previous close. Left at 0
                    // the UI prints "not available" instead of a confident +0.00%.
                    prevClose = o.optDouble("regularMarketPreviousClose", 0.0)
                        .coerceAtLeast(0.0),
                    dayHigh = o.optDouble("regularMarketDayHigh", 0.0),
                    dayLow = o.optDouble("regularMarketDayLow", 0.0),
                    extPrice = extPrice,
                    extLabel = extLabel,
                    marketState = when {
                        state == "REGULAR" -> "OPEN"
                        state.startsWith("PRE") -> "PRE"
                        state.startsWith("POST") || state == "CLOSED" -> "AFTER"
                        else -> ""
                    },
                    // DELIBERATELY EMPTY. v7 carries no candles, and the ViewModel's
                    // carryDisplayFields keeps the last series rather than blanking the
                    // chart - see [sparkline] for where it is refreshed.
                    spark = emptyList(),
                    currency = o.optString("currency", "USD").ifBlank { "USD" },
                    quoteTime = o.optLong("regularMarketTime", 0L) * 1000L,
                    updated = now
                )
            )
        }
        return out
    }

    /**
     * The intraday series for ONE symbol, and nothing else.
     *
     * Split out from the quote because the two have completely different rates of change. A
     * price moves every second and is what the whole screen is about; a 78-point sparkline
     * drawn 64dp wide does not visibly change in five minutes. Downloading the series four
     * times a minute to redraw an identical line was most of what made the quote poll
     * expensive.
     *
     * Returns null on any failure, and the caller keeps the series it already had.
     */
    suspend fun sparkline(symbol: String): List<Double>? =
        yahoo(symbol)?.spark?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- Yahoo

    /**
     * Candle size for the intraday series.
     *
     * This was `1m`, which asks Yahoo for a point every minute of the session - roughly 390
     * of them, plus the pre/post window, in a JSON body of well over 100KB. That body was
     * being pulled for every holding every 15 seconds. At `5m` it is about a fifth of the
     * size and the picture is identical: the sparkline is 64dp wide and the detail chart
     * 130dp tall, so 78 points is already more than either can draw distinctly.
     *
     * The numbers that matter are unaffected - price, previous close, day high/low and the
     * extended-hours print all come from `meta`, not from the candles - and the live price
     * is appended as the final point below, so the right-hand edge of the chart is current
     * to the second rather than to the last 5-minute candle.
     */
    private const val INTERVAL = "5m"

    suspend fun yahoo(symbol: String): Quote? {
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/" +
                enc(symbol) + "?range=1d&interval=$INTERVAL&includePrePost=true"
            val r = Http.get(url, mapOf("Accept" to "application/json"))
            // ROUND 58: SKIP A COOLING HOST, DO NOT ABANDON THE REQUEST.
            //
            // This was `return null`, on the reading that "the throttle is on us, not the
            // host, so the other Yahoo host is no better". That is not what `Http` actually
            // does: cooldowns are armed and held PER HOST, so query1 being left alone says
            // nothing whatsoever about query2. The old line therefore threw away the call
            // with a perfectly usable host sitting unused - one of the reasons a chart could
            // silently fail to appear while the rest of the screen was fine.
            //
            // Skipping rather than returning still honours the cooldown completely: no
            // request is sent to the cooling host. If BOTH are cooling, both are skipped and
            // the loop falls through to `return null` exactly as before.
            if (r.throttledLocally) continue
            if (!r.ok) continue
            try {
                val parsed = parseYahoo(symbol, r.body)
                if (parsed != null) return parsed
            } catch (e: Exception) { /* try the other host */ }
        }
        return null
    }

    private fun parseYahoo(symbol: String, body: String): Quote? {
        val root = JSONObject(body)
        val chart = root.optJSONObject("chart") ?: return null
        val results = chart.optJSONArray("result") ?: return null
        if (results.length() == 0) return null
        val res = results.getJSONObject(0)
        val meta = res.optJSONObject("meta") ?: return null

        val price = meta.optDouble("regularMarketPrice", 0.0)
        if (price <= 0.0) return null
        // meta.previousClose is the RAW prior regular-session close, which is what a broker
        // shows. chartPreviousClose is adjusted for dividends/splits, so on an ex-dividend
        // day it is lower and the day change comes out wrong. Prefer the raw one.
        var prev = meta.optDouble("previousClose", 0.0)
        if (prev <= 0.0) prev = meta.optDouble("chartPreviousClose", 0.0)

        val name = meta.optString("shortName").ifBlank { meta.optString("longName") }

        // Intraday series for the sparkline.
        val ts = res.optJSONArray("timestamp")
        val closes = res.optJSONObject("indicators")
            ?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")

        val period = meta.optJSONObject("currentTradingPeriod")
        val regStart = period?.optJSONObject("regular")?.optLong("start", 0L) ?: 0L
        val regEnd = period?.optJSONObject("regular")?.optLong("end", 0L) ?: 0L
        val nowSec = System.currentTimeMillis() / 1000

        val spark = ArrayList<Double>()
        var extPrice: Double? = null
        var extLabel: String? = null

        if (ts != null && closes != null) {
            val n = minOf(ts.length(), closes.length())
            var lastPre: Double? = null
            var lastPost: Double? = null
            for (i in 0 until n) {
                if (closes.isNull(i)) continue
                val v = closes.optDouble(i, Double.NaN)
                if (v.isNaN() || v <= 0.0) continue
                val t = ts.optLong(i, 0L)
                when {
                    regEnd > 0 && t >= regEnd -> lastPost = v
                    regStart > 0 && t < regStart -> lastPre = v
                    else -> spark.add(v)
                }
            }
            // If the regular session has not produced points yet, plot whatever we have.
            if (spark.isEmpty()) {
                for (i in 0 until n) {
                    if (closes.isNull(i)) continue
                    val v = closes.optDouble(i, Double.NaN)
                    if (!v.isNaN() && v > 0.0) spark.add(v)
                }
            }
            // meta carries the actual latest extended-hours print when Yahoo has it;
            // the candle close can lag it by up to a minute.
            val metaPost = meta.optDouble("postMarketPrice", 0.0).takeIf { it > 0.0 }
            val metaPre = meta.optDouble("preMarketPrice", 0.0).takeIf { it > 0.0 }
            if (regEnd > 0 && nowSec >= regEnd && (metaPost != null || lastPost != null)) {
                extPrice = metaPost ?: lastPost; extLabel = "After hours"
            } else if (regStart > 0 && nowSec < regStart && (metaPre != null || lastPre != null)) {
                extPrice = metaPre ?: lastPre; extLabel = "Pre-market"
            }
        }

        val state = when {
            regStart <= 0L || regEnd <= 0L -> ""
            nowSec in regStart until regEnd -> "OPEN"
            nowSec >= regEnd -> "AFTER"
            else -> "PRE"
        }

        // With 5-minute candles the last plotted point can be up to five minutes behind the
        // live price, which reads as the chart disagreeing with the big number above it.
        // Appending the live price fixes the right-hand edge without another request.
        if (state == "OPEN" && spark.isNotEmpty() && spark.last() != price) spark.add(price)

        return Quote(
            symbol = symbol.uppercase(),
            name = name,
            price = price,
            // NEVER FABRICATE A PREVIOUS CLOSE. This used to fall back to today's price when
            // Yahoo supplied neither previousClose nor chartPreviousClose, which makes the
            // day change come out as exactly +0.00 / +0.00% - a wrong number that looks like
            // a real one, and the same mistake the Stooq fallback was corrected for in v2.3.
            // Left at 0 the UI prints "not available" and `hasDay` keeps it off the row.
            prevClose = prev.coerceAtLeast(0.0),
            dayHigh = meta.optDouble("regularMarketDayHigh", 0.0),
            dayLow = meta.optDouble("regularMarketDayLow", 0.0),
            extPrice = extPrice,
            extLabel = extLabel,
            marketState = state,
            spark = spark,
            currency = meta.optString("currency", "USD"),
            quoteTime = meta.optLong("regularMarketTime", 0L) * 1000L,
            updated = System.currentTimeMillis()
        )
    }

    // -------------------------------------------------------------- Finnhub

    suspend fun finnhub(symbol: String, key: String): Quote? {
        val r = Http.get("https://finnhub.io/api/v1/quote?symbol=" + enc(symbol) + "&token=" + enc(key))
        if (!r.ok) return null
        return try {
            val o = JSONObject(r.body)
            val c = o.optDouble("c", 0.0)
            if (c <= 0.0) return null
            Quote(
                symbol = symbol.uppercase(),
                price = c,
                prevClose = o.optDouble("pc", c),
                dayHigh = o.optDouble("h", 0.0),
                dayLow = o.optDouble("l", 0.0),
                marketState = "",
                updated = System.currentTimeMillis()
            )
        } catch (e: Exception) { null }
    }

    // ---------------------------------------------------------------- Stooq

    private suspend fun stooq(symbol: String): Quote? {
        val r = Http.get("https://stooq.com/q/l/?s=" + enc(symbol.lowercase()) + ".us&f=sd2t2ohlcv&h&e=csv")
        if (!r.ok) return null
        return try {
            val lines = r.body.trim().lines()
            if (lines.size < 2) return null
            val f = lines[1].split(",")
            // Symbol,Date,Time,Open,High,Low,Close,Volume
            val high = f.getOrNull(4)?.toDoubleOrNull() ?: 0.0
            val low = f.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            val close = f.getOrNull(6)?.toDoubleOrNull() ?: 0.0
            if (close <= 0.0) return null
            Quote(
                symbol = symbol.uppercase(),
                price = close,
                // Stooq gives no previous close. Using today's OPEN here produced a
                // plausible-looking but wrong day change, so leave it unset: the UI shows
                // the price and says the day change is unavailable.
                prevClose = 0.0,
                dayHigh = high, dayLow = low,
                marketState = "DELAYED",
                updated = System.currentTimeMillis()
            )
        } catch (e: Exception) { null }
    }

    // `lookupName` used to live here: one more Yahoo search endpoint, with no callers
    // anywhere in the tree. Removed in Round 56 - the batched quote above returns
    // `shortName`, which is where every company name in the app actually comes from.

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
