package com.tj.portfolio.net

import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import org.json.JSONArray
import org.json.JSONObject

/**
 * PRICE SERIES FOR AN ARBITRARY WINDOW.
 *
 * `MarketData.yahoo` already talks to Yahoo's chart endpoint, but it asks one fixed
 * question - one day at five-minute candles - because all it ever needed was a 64dp
 * sparkline and today's extended-hours print. This file is the same endpoint asked properly:
 * any of the ranges in [ChartRange], parsed into a series with timestamps, and with the
 * regular/extended session boundary worked out per DAY rather than only for today.
 *
 * IT IS DELIBERATELY SEPARATE FROM MarketData. The quote path is the app's most load-bearing
 * code and is the thing that must never break; a chart is a picture. Keeping them apart means
 * a change to the charting cannot regress the prices, and the chart can afford a slower,
 * more careful parse than a poll running every fifteen seconds could.
 */
object ChartFeed {

    /**
     * Fetch one range for one symbol, or null if it could not be had.
     *
     * BOTH YAHOO HOSTS ARE TRIED EVEN WHEN THE FIRST IS COOLING DOWN, which is the fix for
     * charts that intermittently never appeared. `Http`'s cooldowns are armed PER HOST, so
     * query1 being left alone says nothing at all about query2 - but the older quote path
     * read `throttledLocally` as "give up entirely" and abandoned the request with a
     * perfectly usable host sitting unused. Here a locally-throttled host is simply skipped
     * and the next one is tried; only when EVERY host is unavailable does the call fail, and
     * it fails without sending anything, so the cooldown still does its job.
     */
    suspend fun series(symbol: String, range: ChartRange): ChartSeries? {
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/" +
                MarketData.enc(symbol) +
                "?range=${range.yRange}&interval=${range.interval}" +
                if (range.prePost) "&includePrePost=true" else ""
            // Shared for a few seconds with the other consumers of the same url - see
            // [RecentBodies] (N-1): Yahoo sends no validator here, so a repeat is a full download.
            RecentBodies.get(url)?.let { body ->
                runCatching { parse(symbol, range, body) }.getOrNull()
                    ?.takeIf { !it.isEmpty }?.let { return it }
            }
            val r = Http.get(url, mapOf("Accept" to "application/json"), conditionalKey = true)
            if (r.throttledLocally) continue
            if (!r.ok) continue
            val parsed = runCatching { parse(symbol, range, r.body) }.getOrNull()
            if (parsed != null && !parsed.isEmpty) {
                RecentBodies.put(url, r.body)
                return parsed
            }
        }
        // Every host either refused, could not be parsed, or was being deliberately left
        // alone. Null means "keep whatever is on screen and try again later" - the caller
        // never caches a failure, so a cooldown costs a redraw, not a blank chart.
        return null
    }

    // ------------------------------------------------------------------ parse

    /**
     * Yahoo's chart body -> a series.
     *
     * THE SESSION BOUNDARY IS PER DAY. `meta.currentTradingPeriod` describes TODAY only, and
     * every multi-day intraday request spans several. `meta.tradingPeriods` carries one entry
     * per day and is what makes the [ChartRange.OVERNIGHT] filter correct across a weekend;
     * when it is absent (Yahoo omits it on daily-and-longer intervals, where it would mean
     * nothing) the code falls back to the single current period.
     */
    internal fun parse(symbol: String, range: ChartRange, body: String): ChartSeries? {
        val res = JSONObject(body).optJSONObject("chart")
            ?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val meta = res.optJSONObject("meta") ?: return null

        val ts = res.optJSONArray("timestamp") ?: return null
        val quote = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
        val closes = quote?.optJSONArray("close") ?: return null

        val regular = regularWindows(meta)
        val onlyExtended = range == ChartRange.OVERNIGHT

        // WITHOUT SESSION BOUNDARIES THE AFTER-HOURS VIEW CANNOT BE HONEST.
        //
        // With no regular windows the extended-only filter below keeps everything, and the
        // result is an ordinary five-day intraday chart still captioned "After-hours and
        // overnight only". Refusing is the right answer: the caller keeps whatever chart it
        // already had and retries, which is strictly better than drawing a different window
        // under this one's label.
        if (onlyExtended && regular.isEmpty()) return null

        // The 1D line is the TRADING DAY, and its dotted baseline is the previous REGULAR
        // close - so mixing pre-market and after-hours points into it would draw a line
        // measured against something it is not. The extended sessions have their own range,
        // which is the whole reason it exists. This also keeps the series identical to what
        // `MarketData.parseYahoo` puts in `Quote.spark`, which matters because
        // `adoptAsSparkline` feeds this exact series into the row sparkline to save a
        // duplicate request: if the two ever diverge, that optimisation silently changes
        // what every row on the portfolio screen is drawing.
        val regularOnly = range == ChartRange.D1 && regular.isNotEmpty()

        val n = minOf(ts.length(), closes.length())
        val pts = ArrayList<ChartPoint>(n)
        /** Points outside the regular session, kept only for the pre-open fallback below. */
        val outside = ArrayList<ChartPoint>()
        for (i in 0 until n) {
            if (closes.isNull(i)) continue
            val v = closes.optDouble(i, Double.NaN)
            // NEVER READ A GAP AS ZERO. Yahoo nulls a candle with no trades in it, and a 0.0
            // in a price series drags the whole y-axis to the floor and paints a cliff that
            // never happened.
            if (v.isNaN() || v <= 0.0) continue
            val t = ts.optLong(i, 0L)
            if (t <= 0L) continue
            val inRegular = regular.any { t >= it.first && t < it.second }
            if (onlyExtended && inRegular) continue
            if (regularOnly && !inRegular) { outside.add(ChartPoint(t, v)); continue }
            pts.add(ChartPoint(t, v))
        }
        // BEFORE 09:30 THERE IS NO REGULAR SESSION YET, and an empty chart on the tab the
        // screen opens on reads as broken. Same fallback `MarketData.parseYahoo` has always
        // had: with nothing from the regular session, plot what there is.
        var usedPreOpenFallback = false
        if (regularOnly && pts.size < 2 && outside.size >= 2) {
            pts.addAll(outside)
            pts.sortBy { it.t }
            usedPreOpenFallback = true
        }
        if (pts.size < 2) return null

        // ---- OVERNIGHT: keep ONE session, not five days of them.
        //
        // The request has to span several days - before 09:30 the only extended points that
        // exist are this morning's, and the after-hours they continue from belongs to
        // yesterday - but what belongs on screen is the CURRENT overnight stretch: from the
        // most recent regular close up to now. Anything older is a different night.
        val overnightPts = if (!onlyExtended) pts else {
            val lastT = pts.last().t
            val cutoff = regular.map { it.second }.filter { it <= lastT }.maxOrNull()
            if (cutoff == null) pts else pts.filter { it.t >= cutoff }
        }
        if (overnightPts.size < 2) return null

        val prevClose = meta.optDouble("previousClose", 0.0)
            .takeIf { it > 0.0 }
        // chartPreviousClose is adjusted for dividends and splits, so on an ex-dividend day
        // it is not the number a broker shows. Only used when the raw one is absent - the
        // same rule the quote parser has followed since v2.5.
            ?: meta.optDouble("chartPreviousClose", 0.0).takeIf { it > 0.0 }

        val baseline = when (range) {
            // Measured from the previous regular close, which is what "up on the day" and
            // "up overnight" both mean.
            ChartRange.D1 -> prevClose ?: 0.0
            ChartRange.OVERNIGHT ->
                lastRegularCloseBefore(ts, closes, regular, overnightPts.first().t)
                    ?: prevClose ?: 0.0
            // Over a month or a decade the previous day's close is not a reference anyone
            // wants; the change is measured from the left-hand edge of the line itself.
            else -> 0.0
        }

        // The window Yahoo actually had data for, against the window that was asked for.
        // A stock that listed last year has no five-year chart, and the caption should say
        // so rather than relabelling one year as five.
        val spanDays = (overnightPts.last().t - overnightPts.first().t) / 86_400.0
        val truncated = spanDays > 0 && spanDays < expectedDays(range) * TRUNCATION_RATIO

        return ChartSeries(
            symbol = symbol.uppercase(),
            range = range,
            points = overnightPts,
            baseline = baseline,
            currency = meta.optString("currency", "USD").ifBlank { "USD" },
            fetched = System.currentTimeMillis(),
            truncated = truncated,
            // Only true when the regular-session filter ran AND produced the line - the
            // pre-open fallback above puts extended points into `pts`, and this must stay
            // false for those. See the field's own note: it is the precondition
            // `adoptAsSparkline` relies on, not a description of the request.
            regularOnly = regularOnly && !usedPreOpenFallback
        )
    }

    /**
     * Below this fraction of the requested window, the series is reported as truncated.
     *
     * Not 1.0, and not close to it: a "1 month" request spans about 30 calendar days but
     * only ~21 of them carry candles, and the first and last are partial. 0.6 flags a genuine
     * short history without crying wolf on every ordinary chart.
     */
    private const val TRUNCATION_RATIO = 0.6

    private fun expectedDays(range: ChartRange): Double = when (range) {
        ChartRange.D1, ChartRange.OVERNIGHT -> 0.0     // never flagged
        ChartRange.D5 -> 7.0
        ChartRange.M1 -> 30.0
        ChartRange.M6 -> 182.0
        ChartRange.Y1 -> 365.0
        ChartRange.Y5 -> 1825.0
        ChartRange.MAX -> 0.0                          // "all" is by definition all there is
    }

    /**
     * Every regular-session window in the response, as (start, end) epoch seconds.
     *
     * `meta.tradingPeriods` is a nested array - one entry per day, each holding one period
     * object - EXCEPT that Yahoo sometimes flattens it to a single array of objects. Both
     * shapes are read here, because getting this wrong does not throw: it silently produces
     * an empty window list, which makes the overnight filter keep everything and quietly
     * turns the after-hours chart back into an ordinary intraday one.
     */
    internal fun regularWindows(meta: JSONObject): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()

        fun add(o: JSONObject?) {
            val s = o?.optLong("start", 0L) ?: 0L
            val e = o?.optLong("end", 0L) ?: 0L
            if (s > 0 && e > s) out.add(s to e)
        }

        val tp = meta.optJSONArray("tradingPeriods")
        if (tp != null) {
            for (i in 0 until tp.length()) {
                when (val day = tp.opt(i)) {
                    is JSONArray -> for (j in 0 until day.length()) add(day.optJSONObject(j))
                    is JSONObject -> add(day)
                }
            }
        }
        // `tradingPeriods` can also arrive as an object keyed "pre"/"regular"/"post", each
        // holding the nested-array shape above.
        meta.optJSONObject("tradingPeriods")?.optJSONArray("regular")?.let { reg ->
            for (i in 0 until reg.length()) {
                when (val day = reg.opt(i)) {
                    is JSONArray -> for (j in 0 until day.length()) add(day.optJSONObject(j))
                    is JSONObject -> add(day)
                }
            }
        }
        if (out.isEmpty()) add(meta.optJSONObject("currentTradingPeriod")?.optJSONObject("regular"))
        return out
    }

    /**
     * The last regular-session close that happened BEFORE the extended stretch starts.
     *
     * This is the baseline an overnight move is measured from, and it is not the same thing
     * as `meta.previousClose`: at 07:00 on a Tuesday, `previousClose` is Monday's close,
     * which is right - but at 18:00 on a Monday the extended points being drawn follow
     * MONDAY'S close, and `previousClose` would still be Friday's. Reading it out of the
     * series itself is correct in both cases.
     */
    private fun lastRegularCloseBefore(
        ts: JSONArray,
        closes: JSONArray,
        regular: List<Pair<Long, Long>>,
        startT: Long
    ): Double? {
        if (regular.isEmpty()) return null
        var best: Double? = null
        var bestT = Long.MIN_VALUE
        val n = minOf(ts.length(), closes.length())
        for (i in 0 until n) {
            if (closes.isNull(i)) continue
            val v = closes.optDouble(i, Double.NaN)
            if (v.isNaN() || v <= 0.0) continue
            val t = ts.optLong(i, 0L)
            if (t <= 0L || t >= startT) continue
            if (regular.none { t >= it.first && t < it.second }) continue
            if (t > bestT) { bestT = t; best = v }
        }
        return best
    }
}
