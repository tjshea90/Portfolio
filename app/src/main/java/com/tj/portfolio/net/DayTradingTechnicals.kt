package com.tj.portfolio.net

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * REAL, RESEARCH-BACKED TECHNICALS FOR THE DAY-TRADING RISK PLAN (Round 68).
 *
 * Tj: *"do comprehensive, deep research on proven day trading algorithms, timing, volatility,
 * and how to calculate good buy and sell price targets. use many legitimate and professional
 * sources."* This file is that research, made into code - three measures with decades of
 * published, practitioner-verified use behind them, computed from real price history rather
 * than the single ad-hoc volatility blend [ResearchScore.tradeLevels] used before this:
 *
 *  - **ATR (Average True Range)**, J. Welles Wilder, "New Concepts in Technical Trading
 *    Systems" (1978) - the industry-standard volatility measure for stop-loss placement,
 *    because the plain high-low range misses a gap move that a TRUE range catches
 *    (max of high-low, |high-prevClose|, |low-prevClose|). StockCharts' ChartSchool documents
 *    Wilder's own smoothing (`ATR = (prevATR * 13 + TR) / 14`), used here exactly. A day
 *    trader's stop is commonly sized at 1.5x-2x ATR(14) - tighter than a swing trader's
 *    2x-3x, because a day trade is closed the same session - and this uses the tight end,
 *    1.5x (LuxAlgo, QuantVPS and NetPicks' trading-education guides all describe this range
 *    the same way independently).
 *  - **VWAP (Volume-Weighted Average Price)** - the reference line professional and
 *    institutional intraday traders read as the day's "fair value": price holding above VWAP
 *    on real volume reads as buyers in control, below as sellers (Schwab, Warrior Trading,
 *    LuxAlgo). Computed here from the session's own 5-minute bars: cumulative(typical price x
 *    volume) / cumulative(volume), resetting every session, as the definition requires.
 *  - **The opening range**, Toby Crabel's "Day Trading With Short Term Price Patterns And
 *    Opening Range Breakout" (1990) and the modern data behind it: the first 30 minutes
 *    (09:30-10:00 ET) carry close to the day's heaviest volume and its sharpest moves, and a
 *    breakout above that range's high on real volume is one of the most widely cited,
 *    backtested day-trading setups in print. Used here as a scoring signal
 *    ([ResearchScore.withTechnicals]) and shown in the per-stock explanation, not folded into
 *    the single target number - the full Crabel "stretch" (a 10-day average of the opening
 *    move) is a further refinement this does not attempt.
 *
 * WHAT THIS STILL DOES NOT CLAIM. All three describe risk and levels ALREADY IN THE MARKET
 * DATA - none of them predicts direction. That is deliberate: see
 * [ResearchScore.dayTrading]'s header for the feasibility finding this whole feature rests on.
 * Real, published, peer-reviewed research on retail day trading (Barber & Odean's study of
 * Taiwanese accounts, and others since, across Brazil, India and the US) finds that a large
 * majority of retail day traders lose money over time - so this stays a RISK PLAN for a trade
 * the user is already about to make, computed from real technicals instead of a rough
 * approximation, never a prediction that the plan will work out.
 *
 * DELIBERATELY SEPARATE FROM [ChartFeed]. That file's own header says a change to charting
 * must never regress the price chart, which is the app's most-looked-at screen; this file
 * fetches the same Yahoo chart endpoint but keeps its own parsing (OHLCV, not just closes) so
 * the two can never interfere with each other.
 *
 * NO CLAUDE ANYWHERE IN THIS FILE. Tj, 2026-09-11, screened explicitly: *"Do not use Claude
 * api at all in the app unless I explicitly press a button... The app should do as much as
 * possible without Claude."* Every function here is a plain HTTP fetch and arithmetic on the
 * result - the existing Explain/import buttons are the only place this feature ever calls
 * Claude, unchanged by this file.
 */
object DayTradingTechnicals {

    /** One OHLCV candle. [t] is Yahoo's epoch-SECONDS timestamp, not millis. */
    data class Bar(
        val t: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )

    data class DayTechnicals(
        /** Wilder's ATR(14) on daily bars, or a shorter-lookback average when the symbol's
         *  history does not reach 14 sessions yet (e.g. a recent IPO). 0.0 if unavailable. */
        val atr14: Double = 0.0,
        /** Today's (or, when the market is closed, the last session's) volume-weighted average price. */
        val vwap: Double = 0.0,
        val openingRangeHigh: Double = 0.0,
        val openingRangeLow: Double = 0.0,
        /** True once the 09:30-10:00 ET window has fully printed - false pre-market or mid-range. */
        val openingRangeComplete: Boolean = false
    ) {
        // ALL FOUR CHECKED, NOT JUST atr14/vwap - a real bug caught by
        // `DayTradingTest`'s opening-range-breakout case: the daily-bar fetch (ATR) and the
        // intraday-bar fetch (VWAP, opening range) can succeed or fail independently, so a
        // technicals reading that got the opening range but not VWAP (or vice versa) is not
        // empty, and `withTechnicals` must still be able to award its breakout bonus.
        val isEmpty: Boolean get() =
            atr14 <= 0.0 && vwap <= 0.0 && openingRangeHigh <= 0.0 && openingRangeLow <= 0.0
    }

    private val ET: TimeZone = TimeZone.getTimeZone("America/New_York")

    /**
     * Everything above for one symbol - up to two requests (daily bars for ATR, intraday bars
     * for VWAP and the opening range), each tried against both Yahoo hosts the same way
     * [ChartFeed] does. Never throws; a fetch that fails or a symbol too new for 14 days of
     * history simply comes back with that one field at 0.0; the caller ([ResearchScore]'s
     * enrichment overlay) treats 0.0 as "not available" and falls back to the pre-existing
     * estimate rather than showing a broken number.
     */
    suspend fun fetch(symbol: String): DayTechnicals {
        val daily = fetchBars(symbol, range = "3mo", interval = "1d", prePost = false)
        val intraday = fetchBars(symbol, range = "1d", interval = "5m", prePost = false)
        return DayTechnicals(
            atr14 = daily?.let { atr14(it) } ?: 0.0,
            vwap = intraday?.let { vwap(it) } ?: 0.0,
            openingRangeHigh = intraday?.let { openingRange(it) }?.first ?: 0.0,
            openingRangeLow = intraday?.let { openingRange(it) }?.second ?: 0.0,
            openingRangeComplete = intraday?.let { openingRangeComplete(it) } ?: false
        )
    }

    // ------------------------------------------------------------------ fetch

    /**
     * Same two-host, cooldown-aware fetch [ChartFeed.series] uses, kept as its own copy per
     * this file's header - a raw OHLCV bar list rather than a close-only [com.tj.portfolio.data.ChartSeries].
     */
    private suspend fun fetchBars(
        symbol: String,
        range: String,
        interval: String,
        prePost: Boolean
    ): List<Bar>? {
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/" +
                MarketData.enc(symbol) +
                "?range=$range&interval=$interval" +
                if (prePost) "&includePrePost=true" else ""
            val r = Http.get(url, mapOf("Accept" to "application/json"))
            if (r.throttledLocally) continue
            if (!r.ok) continue
            val parsed = runCatching { parseBars(r.body) }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return null
    }

    /** Yahoo's chart body -> OHLCV bars. Unlike [ChartFeed.parse] this keeps high/low/volume. */
    internal fun parseBars(body: String): List<Bar>? {
        val res = JSONObject(body).optJSONObject("chart")
            ?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val ts = res.optJSONArray("timestamp") ?: return null
        val quote = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: return null
        val highs = quote.optJSONArray("high") ?: return null
        val lows = quote.optJSONArray("low") ?: return null
        val closes = quote.optJSONArray("close") ?: return null
        val opens = quote.optJSONArray("open")
        val vols = quote.optJSONArray("volume")

        val n = ts.length()
        val out = ArrayList<Bar>(n)
        for (i in 0 until n) {
            if (closes.isNull(i) || highs.isNull(i) || lows.isNull(i)) continue
            val h = highs.optDouble(i, Double.NaN)
            val l = lows.optDouble(i, Double.NaN)
            val c = closes.optDouble(i, Double.NaN)
            // NEVER READ A GAP AS ZERO - same rule ChartFeed.parse follows. A null/absent
            // candle (no trades that period) must be skipped, not read as a real 0.0 price.
            if (h.isNaN() || l.isNaN() || c.isNaN() || h <= 0.0 || l <= 0.0 || c <= 0.0) continue
            val t = ts.optLong(i, 0L)
            if (t <= 0L) continue
            val o = opens?.takeIf { !it.isNull(i) }?.optDouble(i, c) ?: c
            val v = vols?.takeIf { !it.isNull(i) }?.optDouble(i, 0.0) ?: 0.0
            out.add(Bar(t, o, h, l, c, v.coerceAtLeast(0.0)))
        }
        return out
    }

    // ------------------------------------------------------------------ ATR

    /**
     * Wilder's ATR(14) - see this file's header for the citation. Needs at least 15 daily
     * bars (14 true-range values). A symbol with less history than that (a recent IPO) gets a
     * plain average of whatever true ranges exist, down to a floor of 5 - still a real,
     * computed volatility measure, just over a shorter lookback than Wilder specified. Fewer
     * than 5 returns null so the caller can fall back to the pre-existing estimate rather than
     * trust a number built from almost nothing.
     */
    internal fun atr14(daily: List<Bar>): Double? {
        if (daily.size < 2) return null
        val sorted = daily.sortedBy { it.t }
        val trueRanges = ArrayList<Double>(sorted.size - 1)
        for (i in 1 until sorted.size) {
            val h = sorted[i].high
            val l = sorted[i].low
            val prevClose = sorted[i - 1].close
            trueRanges.add(maxOf(h - l, abs(h - prevClose), abs(l - prevClose)))
        }
        if (trueRanges.size < 5) return null
        if (trueRanges.size < 14) return trueRanges.average()
        // Wilder smoothing: bootstrap on a simple average of the first 14, then smooth forward
        // - "Current ATR = (Prior ATR x 13 + Current TR) / 14" (StockCharts ChartSchool).
        var atr = trueRanges.take(14).average()
        for (i in 14 until trueRanges.size) {
            atr = (atr * 13.0 + trueRanges[i]) / 14.0
        }
        return atr
    }

    // ------------------------------------------------------------------ VWAP

    /** Cumulative(typical price x volume) / cumulative(volume) - resets every session by construction. */
    internal fun vwap(intraday: List<Bar>): Double? {
        if (intraday.isEmpty()) return null
        var pv = 0.0
        var v = 0.0
        for (b in intraday) {
            val typical = (b.high + b.low + b.close) / 3.0
            pv += typical * b.volume
            v += b.volume
        }
        if (v <= 0.0) return null
        return pv / v
    }

    // ------------------------------------------------------------- opening range

    /** Minutes since midnight, New York time - same helper shape [MarketClock] uses. */
    private fun etMinutes(epochSeconds: Long): Int {
        val c = Calendar.getInstance(ET)
        c.timeInMillis = epochSeconds * 1000L
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private const val OR_START_MIN = 9 * 60 + 30
    private const val OR_END_MIN = 10 * 60

    /** High/low of the 09:30-10:00 ET window, or null if none of the bars fall in it. */
    internal fun openingRange(intraday: List<Bar>): Pair<Double, Double>? {
        val inWindow = intraday.filter { etMinutes(it.t) in OR_START_MIN until OR_END_MIN }
        if (inWindow.isEmpty()) return null
        return inWindow.maxOf { it.high } to inWindow.minOf { it.low }
    }

    /**
     * True once a bar timestamped 10:00 ET or later exists - i.e. the opening range has
     * actually finished printing, not just that some bars fall inside it. A breakout is only
     * a breakout once the range it breaks is complete.
     */
    internal fun openingRangeComplete(intraday: List<Bar>): Boolean =
        intraday.any { etMinutes(it.t) >= OR_END_MIN }
}
