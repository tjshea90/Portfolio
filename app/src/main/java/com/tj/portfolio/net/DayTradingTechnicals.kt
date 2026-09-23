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
 * than the single ad-hoc volatility blend the pre-Round-68 level maths used before this:
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
 * ROUND 69 ADDITIONS - THE LEVELS AN ENTRY TRIGGER IS ACTUALLY MADE OF.
 *
 * Tj, 2026-09-11: *"the target buy price just matches the current market price. I don't think
 * this is how day traders operate."* He is right, and everything below exists because of it.
 * A day trader's buy price is a LEVEL PRICE HAS TO REACH - a buy-stop above overhead
 * resistance, or a buy-limit down at support - never the last print. [ResearchScore.tradePlan]
 * is the engine; this file supplies the levels it chooses between:
 *
 *  - **Prior-session high / low / close**, and the **floor-trader pivots** derived from them
 *    (PP = (H+L+C)/3, R1 = 2*PP - Low, R2 = PP + (H-L), S1 = 2*PP - High). Pivots are the
 *    oldest published intraday level set there is - literally what pit traders computed by
 *    hand overnight - and are still the standard static support/resistance grid on an
 *    intraday chart (TradingView, TC2000, TradingSim all document this exact formula). R1/R2
 *    are used here as profit targets, which is the use practitioners describe.
 *  - **The premarket high**, the "gap and go" trigger: Warrior Trading's own description of
 *    the setup is to mark the premarket high and buy the break of it at the open.
 *  - **Session high / low so far**, for both the high-of-day breakout trigger and the
 *    range-used calculation below.
 *  - **ADR (Average Daily Range)** - the mean of (high - low) over the last 14 completed
 *    sessions. Used to answer the question practitioner sources put at the centre of target
 *    setting: HOW MUCH ROOM IS LEFT. Two identical-looking setups differ completely when one
 *    has used 30% of its typical daily range and the other 110%, and a profit target placed
 *    outside the day's realistic range is not a target, it is a wish.
 *  - **An INTRADAY ATR(14) computed on the 5-minute bars**, alongside the daily one.
 *
 * THE INTRADAY ATR IS A CORRECTION OF A REAL BUG, not an extra. Until Round 69 the stop was
 * `price - 1.5 * ATR(14 DAILY)`. The "1.5x-2x ATR" figure in the day-trading literature means
 * the ATR OF THE TIMEFRAME BEING TRADED - the near-universal rule is to size a stop from the
 * chart you are trading, because mixing timeframes gives "mismatched stop distances." 1.5x a
 * DAILY range is roughly one and a half ENTIRE average sessions of risk on a trade meant to be
 * closed the same afternoon: on a $100 stock with a $3 daily ATR it put the stop 4.5% away and
 * the 2:1 target 9% away, which is why the levels read as implausible. A 5-minute ATR(14) is
 * the right scale, and it cross-checks against the only peer-reviewed number available: the
 * Zarattini-Barbon-Aziz "stocks in play" ORB study used a stop of a small fraction of the
 * 14-day ATR, and 1.5x-2.5x a 5-minute ATR lands in that same fraction-of-a-daily-range band
 * from the other direction. Two independent derivations agreeing is the reason to trust it.
 *
 * WHY THESE SOURCES AND NOT A BACKTEST OF OUR OWN. The strongest published evidence for any of
 * this is Zarattini, Barbon & Aziz, "A Profitable Day Trading Strategy For The U.S. Equity
 * Market" (Swiss Finance Institute / SSRN, 2024), which tested the 5-minute opening-range
 * breakout across 7,000+ US stocks over 2016-2023 and found the edge concentrated in "stocks
 * in play" - the ~20 names each day with the highest opening relative volume, filtered to
 * price > $5, 14-day average volume >= 1M shares and 14-day ATR >= $0.50. That is the same
 * shape this tab already had (screen for what is genuinely in play, then trade a level), which
 * is why the filters and the level set here follow it rather than something invented.
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
        val openingRangeComplete: Boolean = false,
        /**
         * High/low of the FIVE-minute opening range (09:30-09:35 ET) - the variant the
         * strongest published test of this setup found best, against the 30-minute one above
         * which it found worst. 0.0 before the first bar prints. See [openingBar].
         */
        val or5High: Double = 0.0,
        val or5Low: Double = 0.0,
        /**
         * The opening five-minute bar closed ABOVE its open - the direction filter Zarattini,
         * Barbon & Aziz apply before taking any long (a flat or down opening bar is no trade).
         * False both when the bar was not bullish AND when it has not printed yet; callers that
         * need to tell those apart read [or5High] being 0.0.
         */
        val openingBarBullish: Boolean = false,
        /**
         * Wilder's ATR(14) on the 5-MINUTE bars - the volatility scale a same-session stop is
         * actually sized from. See this file's header for why the daily one was the wrong
         * ruler for that job. 0.0 before enough intraday bars have printed.
         */
        val atrIntraday: Double = 0.0,
        /** Mean (high - low) over the last 14 COMPLETED sessions - "how big is a normal day". */
        val adr: Double = 0.0,
        /** The most recent COMPLETED session - never the one currently in progress. */
        val prevHigh: Double = 0.0,
        val prevLow: Double = 0.0,
        val prevClose: Double = 0.0,
        /** High of the 04:00-09:30 ET pre-market - the "gap and go" trigger level. */
        val premarketHigh: Double = 0.0,
        /** High/low of the regular session these intraday bars describe, so far. */
        val sessionHigh: Double = 0.0,
        val sessionLow: Double = 0.0,
        /** True only while the regular session is actually open - see [fetch]. */
        val sessionLive: Boolean = false,
        /**
         * WHETHER THE INTRADAY REQUEST CAME BACK AT ALL, as distinct from what it said.
         *
         * [sessionDay] is blank in two completely different situations, and the difference
         * decides whether a caller may keep the reading it already has:
         *
         *  - the request FAILED (both hosts throttled or erroring) - nothing was learned about
         *    this session, so the row's own reading from thirty seconds ago is still the best
         *    information available;
         *  - the request SUCCEEDED and carried no bars dated today (a weekend, a market
         *    holiday, any time before 04:00 ET) - that is real information, and yesterday's
         *    VWAP and session high/low must NOT be carried into it.
         *
         * Without this flag `PortfolioViewModel.effectiveTechnicals` had to treat both as a
         * session change and zero every intraday field, which on a routine transient failure
         * rebuilt the trade plan from a daily-ATR fallback and made the displayed entry, stop
         * and target jump to a different plan and back again on the next tick.
         */
        val intradayFetched: Boolean = false,
        /**
         * WHICH TRADING DAY THE INTRADAY HALF OF THIS READING DESCRIBES ([MarketClock.dayKey]).
         *
         * Carried so a stale reading can be told from an out-of-date one. VWAP, the opening
         * range and the session high/low are all defined per session and meaningless across
         * sessions, and the live sweep reuses a row's previous values whenever this tick's
         * intraday request failed. Without this key, a row cached overnight would hand
         * YESTERDAY's session high and low to this morning's plan the first time the 09:31
         * fetch missed - and since `rangeUsed` on a finished session is about 1.0, every
         * affected row would open the day reading "already extended, do not chase".
         *
         * BLANK, NOT TODAY'S DATE, when [fetch] found no intraday bars actually dated today -
         * weekends, market holidays, and any request before 4am ET all hand back the LAST
         * trading day's bars instead (see [latestDay]), and every intraday-derived field above
         * is 0.0 in that case for the same reason: a finished session's real numbers are not
         * this morning's, however non-zero they are.
         */
        val sessionDay: String = "",
        /**
         * The latest print in TODAY's intraday bars (pre-market included) - the close of the
         * newest 5-minute bar, which Yahoo keeps updating while that bar is still forming.
         * 0.0 when no bar is dated today. It is what lets the live sweep plan against the
         * price NOW rather than the one the screener saw when the list was built (full-tests
         * audit 2026-09-22, D-H1).
         */
        val lastPrice: Double = 0.0
    ) {
        /** Classic floor-trader pivot, from the prior completed session. 0.0 without one. */
        val pivot: Double get() =
            if (prevHigh > 0 && prevLow > 0 && prevClose > 0) (prevHigh + prevLow + prevClose) / 3.0
            else 0.0

        /** First resistance above the pivot: 2*PP - prior low. */
        val r1: Double get() = pivot.takeIf { it > 0 }?.let { 2.0 * it - prevLow } ?: 0.0

        /** Second resistance: PP + the prior session's whole range. */
        val r2: Double get() = pivot.takeIf { it > 0 }?.let { it + (prevHigh - prevLow) } ?: 0.0

        /** First support below the pivot: 2*PP - prior high. */
        val s1: Double get() = pivot.takeIf { it > 0 }?.let { 2.0 * it - prevHigh } ?: 0.0

        /** How much of a normal day's range this session has already travelled, 0.0 if unknown. */
        val rangeUsed: Double get() =
            if (adr > 0 && sessionHigh > 0 && sessionLow > 0 && sessionHigh >= sessionLow)
                (sessionHigh - sessionLow) / adr
            else 0.0

        // ALL FOUR CHECKED, NOT JUST atr14/vwap - a real bug caught by
        // `DayTradingTest`'s opening-range-breakout case: the daily-bar fetch (ATR) and the
        // intraday-bar fetch (VWAP, opening range) can succeed or fail independently, so a
        // technicals reading that got the opening range but not VWAP (or vice versa) is not
        // empty, and `withTechnicals` must still be able to award its breakout bonus.
        //
        // ROUND 69: `prevHigh` JOINS THEM, for exactly the same reason. Outside market hours
        // the intraday half of the fetch has nothing to say (no VWAP, no opening range) while
        // the daily half still carries the prior-session levels the overnight plan is built
        // from - "break above yesterday's high" is the whole gap-and-go setup. Reading that as
        // empty would have thrown away the only reading available for most of the day.
        val isEmpty: Boolean get() =
            atr14 <= 0.0 && vwap <= 0.0 && openingRangeHigh <= 0.0 && openingRangeLow <= 0.0 &&
                prevHigh <= 0.0
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
    suspend fun fetch(symbol: String, now: Long = System.currentTimeMillis()): DayTechnicals {
        // ---- THE DAILY BARS ARE IMMUTABLE DURING A SESSION, SO ASK FOR THEM ONCE.
        //
        // This leg is `range=3mo&interval=1d`, and everything computed from it - `atr14`,
        // `adr`, `prevHigh/prevLow/prevClose` - comes out of `completedSessions`, i.e.
        // sessions that have ALREADY CLOSED. None of those numbers can change while the
        // market is open. It was nevertheless re-downloaded on every tick of the 30-second
        // day-trading sweep: with the default page of 10 rows that is 1,200 requests an hour
        // for three months of candles that were settled before the bell, on top of the 1,200
        // for the intraday leg. BRIEF.md's locked decision is "chart refresh rate: the range's
        // own candle interval, never faster", and a 1-day candle asked for twice a minute is
        // the clearest possible breach of it.
        //
        // Memoised per symbol, per ET date, and per side of the 4pm close - that last part
        // matters because `completedSessions` starts counting today's bar once the session
        // ends, so the series legitimately changes exactly once a day, at the close.
        val dailyKey = "$symbol|${etDateKey(now / 1000)}|${if (afterClose(now)) 1 else 0}"
        val daily = cachedDaily(dailyKey) ?: fetchBars(
            symbol, range = "3mo", interval = "1d", prePost = false
        )?.also { cacheDaily(dailyKey, it) }
        // PRE/POST INCLUDED SINCE ROUND 69 - the premarket high is a real trigger level (see
        // the header), and it costs nothing: the same one request now carries both sessions,
        // and [regularSession] splits them back apart so VWAP, the opening range and the
        // session high/low stay regular-hours-only, exactly as their definitions require.
        val intradayAll = fetchBars(symbol, range = "1d", interval = "5m", prePost = true)
            ?.let { latestDay(it) }
        // `intradayAll` CAN BE A PRIOR, FULLY-CLOSED SESSION - weekends, market holidays, and
        // every request before 4am ET, when Yahoo has printed nothing for "today" yet and
        // `latestDay` anchors to the newest date actually present, which is the last trading
        // day. Its VWAP/opening-range/session-high-low are then real, complete numbers for a
        // session that finished hours or days ago - exactly what [sessionDay]'s own doc warns
        // against treating as this morning's. Gating on the bars' own date, not just carrying
        // `sessionDay` through, is required: `sessionDay` alone only protects the `tech.field <=
        // 0` fallback branch in `PortfolioViewModel.effectiveTechnicals` - a non-zero stale
        // reading sails through its direct pass-through untouched.
        val intradayToday = intradayAll?.takeIf { bars ->
            bars.firstOrNull()?.let { etDateKey(it.t) == etDateKey(now / 1000) } ?: false
        }
        val regular = intradayToday?.let { regularSession(it) }
        // COMPUTED ONCE, not once per field - `openingRange` filters and re-scans the whole
        // intraday bar list, and calling it twice (once for the high, once for the low) did
        // that work twice for no reason on every symbol, every 30-second tick.
        val or = regular?.let { openingRange(it) }
        val or5 = regular?.let { openingBar(it) }
        val completed = daily?.let { completedSessions(it, now) }
        val prev = completed?.lastOrNull()
        return DayTechnicals(
            atr14 = completed?.let { atr14(it) } ?: 0.0,
            vwap = regular?.let { vwap(it) } ?: 0.0,
            openingRangeHigh = or?.first ?: 0.0,
            openingRangeLow = or?.second ?: 0.0,
            openingRangeComplete = regular?.let { openingRangeComplete(it) } ?: false,
            or5High = or5?.high ?: 0.0,
            or5Low = or5?.low ?: 0.0,
            openingBarBullish = or5 != null && or5.close > or5.open,
            atrIntraday = regular?.let { atr14(it) } ?: 0.0,
            adr = completed?.let { adr(it) } ?: 0.0,
            prevHigh = prev?.high ?: 0.0,
            prevLow = prev?.low ?: 0.0,
            prevClose = prev?.close ?: 0.0,
            premarketHigh = intradayToday?.let { premarketHigh(it) } ?: 0.0,
            sessionHigh = regular?.maxOfOrNull { it.high } ?: 0.0,
            sessionLow = regular?.minOfOrNull { it.low } ?: 0.0,
            sessionLive = MarketClock.phase(now) == MarketClock.Phase.OPEN,
            // The REQUEST's fate, not the data's date - see the field's own note.
            intradayFetched = intradayAll != null,
            // Blank, not today's date, when there is no intraday reading for today - so
            // `sameSession` downstream correctly refuses to carry a row's own cached values
            // forward either, rather than agreeing with itself that nothing is today's.
            sessionDay = if (intradayToday != null) MarketClock.dayKey(now) else "",
            lastPrice = intradayToday?.lastOrNull()?.close?.takeIf { it > 0.0 } ?: 0.0
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
                // CONDITIONAL, like every other repeat request in the app. Without a validator
            // key an unchanged series is re-sent in full on every tick; with one it is a
            // bodyless 304 that `Http` answers from `http_cache`. See BRIEF.md's locked
            // "Response caching" decision - nothing already stored should be downloaded again.
            val r = Http.get(url, mapOf("Accept" to "application/json"), conditionalKey = true)
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

    private const val PREMARKET_START_MIN = 4 * 60
    private const val OR_START_MIN = 9 * 60 + 30
    private const val OR_END_MIN = 10 * 60

    /**
     * Just the most recent trading day's bars.
     *
     * REQUIRED BECAUSE THE INTRADAY REQUEST NOW ASKS FOR PRE/POST BARS. Everything below
     * filters purely on TIME OF DAY, which was safe while one request meant one session's
     * regular hours. With extended hours included, a `range=1d` window can straddle a
     * boundary - yesterday's after-hours alongside this morning's pre-market - and a
     * time-of-day filter would then quietly merge two days: an "opening range" spanning two
     * 09:30-10:00 windows, a "session high" set yesterday afternoon. Anchoring to the newest
     * date present makes every window below mean one session again.
     */
    internal fun latestDay(intraday: List<Bar>): List<Bar> {
        val newest = intraday.maxOfOrNull { etDateKey(it.t) } ?: return intraday
        return intraday.filter { etDateKey(it.t) == newest }
    }

    /**
     * The regular-hours slice of a pre/post-inclusive bar list - 09:30 up to 16:00 ET.
     *
     * EVERYTHING THAT READS "THE SESSION" MUST GO THROUGH THIS. VWAP is defined as resetting at
     * the open, the opening range is defined by the clock, and a session high that quietly
     * included a thin after-hours print would move a breakout trigger to a price no regular-hours
     * order could ever have been filled at.
     */
    // THE DAY'S OWN CLOSE, not a fixed 16:00 (full-tests audit 2026-09-22, with D-M4). These
    // bars include post-market prints, so on a 13:00 half day the 13:00-16:00 after-hours tape
    // was being read as regular session - into VWAP, the session high/low and the intraday ATR.
    internal fun regularSession(intraday: List<Bar>): List<Bar> =
        intraday.filter { etMinutes(it.t) in OR_START_MIN until MarketClock.closeMinuteAt(it.t * 1000L) }

    /** High of the 04:00-09:30 ET pre-market, or 0.0 when none of the bars fall in it. */
    internal fun premarketHigh(intraday: List<Bar>): Double =
        intraday.filter { etMinutes(it.t) in PREMARKET_START_MIN until OR_START_MIN }
            .maxOfOrNull { it.high } ?: 0.0

    /** True once the close (16:00 ET, 13:00 on a half day) has passed on [nowMs]'s New York date. */
    private fun afterClose(nowMs: Long): Boolean = closeSettled(nowMs)

    /**
     * Today's daily bar counts as FINAL only once the close has had time to settle (full test
     * 2026-09-23, D-10): Yahoo's daily candle takes a few minutes to absorb the closing-auction
     * print, and the first post-close fetch is memoised for the rest of the evening - so a tick
     * at 16:00:40 froze a prevClose/high/low that was off by the auction until midnight. The
     * same grace [DayTradingEval.SETTLE_GRACE_MS] gives outcome evaluation.
     */
    private fun closeSettled(nowMs: Long): Boolean =
        etMinutes(nowMs / 1000) * 60_000L >=
            MarketClock.closeMinuteAt(nowMs) * 60_000L + DayTradingEval.SETTLE_GRACE_MS

    /**
     * The memo behind `fetch`'s daily leg.
     *
     * Deliberately tiny and deliberately in memory. The bars it holds are already on disk in
     * `http_cache` (the request is conditional), so this is not the durable cache - it exists
     * to stop the app opening a socket at all for a series that provably cannot have changed
     * since the last tick. `DAY_TRADING_BUFFER` is 40 symbols, so the cap is generous enough
     * to hold a whole sweep and small enough to be irrelevant to memory; when the ET date or
     * the side of the close changes, every key changes with it and the old entries fall out.
     */
    private const val DAILY_CACHE_MAX = 64
    private val dailyCache = object : LinkedHashMap<String, List<Bar>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Bar>>) =
            size > DAILY_CACHE_MAX
    }

    // SYNCHRONISED, because the sweep is parallel. `enrichDayTradingVisible` fetches symbols
    // concurrently under a Semaphore, and an access-ordered LinkedHashMap rewrites its own
    // links on a plain `get` - so reads race with reads here, not just with writes.
    private fun cachedDaily(key: String): List<Bar>? = synchronized(dailyCache) {
        dailyCache[key]
    }

    private fun cacheDaily(key: String, bars: List<Bar>) = synchronized(dailyCache) {
        dailyCache.put(key, bars); Unit
    }

    /** Test seam: the memo is process-wide, so a test that fakes the clock must be able to drop it. */
    internal fun clearDailyCache() = synchronized(dailyCache) { dailyCache.clear() }

    /** Calendar date in New York, as a comparable yyyymmdd integer. */
    private fun etDateKey(epochSeconds: Long): Int {
        val c = Calendar.getInstance(ET)
        c.timeInMillis = epochSeconds * 1000L
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * Daily bars with the IN-PROGRESS session removed, oldest first.
     *
     * WHY THIS MATTERS ENOUGH TO EXIST. Yahoo emits a daily bar for today from the first trade
     * onwards, and that bar's high/low grow all afternoon. Three separate readings here are
     * only meaningful over FINISHED sessions: the prior-session levels a plan triggers off
     * ("break above yesterday's high" must not mean "break above the high it already made an
     * hour ago"), the ADR that says how big a normal day is, and the daily ATR. A partial bar
     * in any of them understates the number and drags every level built on it.
     *
     * Today's bar counts as finished once 16:00 ET has passed, which is also what makes the
     * overnight and weekend case come out right: after the close, "the prior session" IS today.
     */
    internal fun completedSessions(daily: List<Bar>, now: Long): List<Bar> {
        val nowSec = now / 1000L
        val today = etDateKey(nowSec)
        // The day's own close - 13:00 on a half day (see [regularSession]).
        val closed = closeSettled(now)
        return daily.sortedBy { it.t }.filter { etDateKey(it.t) != today || closed }
    }

    /**
     * Average Daily Range: the mean of (high - low) over the last 14 completed sessions - see
     * the header for what it is used for. Null under 5 sessions, the same floor [atr14] uses
     * for the same reason: a "typical day" averaged from two days is not a typical day.
     */
    internal fun adr(completed: List<Bar>, lookback: Int = 14): Double? {
        if (completed.size < 5) return null
        val ranges = completed.takeLast(lookback).map { it.high - it.low }.filter { it > 0.0 }
        if (ranges.size < 5) return null
        return ranges.average()
    }

    /** High/low of the 09:30-10:00 ET window, or null if none of the bars fall in it. */
    internal fun openingRange(intraday: List<Bar>): Pair<Double, Double>? {
        val inWindow = intraday.filter { etMinutes(it.t) in OR_START_MIN until OR_END_MIN }
        if (inWindow.isEmpty()) return null
        return inWindow.maxOf { it.high } to inWindow.minOf { it.low }
    }

    /** End of the FIVE-minute opening range: 09:35 ET. See [openingBar]. */
    private const val OR5_END_MIN = 9 * 60 + 35

    /**
     * THE FIRST FIVE-MINUTE BAR OF THE SESSION (Round 73) - 09:30-09:35 ET, or null.
     *
     * ---- WHY A SECOND, SHORTER OPENING RANGE
     *
     * The 09:30-10:00 range this file has used since Round 68 is the Crabel-era classic, and it
     * is the version practitioner writing describes most often - but it is not the version the
     * strongest published evidence supports. Zarattini, Barbon & Aziz test 5-, 15-, 30- and
     * 60-minute opening ranges over the same 7,000+ US stocks, the same 2016-2023 window and
     * the same filters, and report the 30-MINUTE VARIANT AS THE WEAKEST OF THE FOUR BY A LARGE
     * MARGIN, with the 5-minute one the strongest. The app was citing that paper as its
     * evidence base while using the one window in it that performed worst.
     *
     * COSTS NOTHING TO ADD. The intraday request is already `interval=5m`, so the first
     * regular-hours bar IS the five-minute opening range - no new fetch, no new parsing, just a
     * window this file was not looking at.
     *
     * BOTH ARE KEPT, because they answer different questions. The 5-minute high is an EARLY
     * trigger: it is only still overhead in the first minutes of the session, and
     * [ResearchScore.tradePlan] drops any level price has already passed, so it selects itself
     * out by about 10:00 without needing a clock. The 30-minute range stays where it already
     * was - as a support level once price is above it, and as the range whose completion the
     * confidence checklist reads.
     *
     * [Bar.open] AND [Bar.close] ARE WHY THIS RETURNS THE BAR AND NOT A HIGH/LOW PAIR. The same
     * study only takes a long when the opening bar closed ABOVE its open (a doji is no trade) -
     * direction information that a range alone throws away. See [DayTechnicals.openingBarBullish].
     */
    internal fun openingBar(intraday: List<Bar>): Bar? =
        intraday.filter { etMinutes(it.t) in OR_START_MIN until OR5_END_MIN }
            .maxByOrNull { it.t }

    /**
     * True once a bar timestamped 10:00 ET or later exists - i.e. the opening range has
     * actually finished printing, not just that some bars fall inside it. A breakout is only
     * a breakout once the range it breaks is complete.
     */
    internal fun openingRangeComplete(intraday: List<Bar>): Boolean =
        intraday.any { etMinutes(it.t) >= OR_END_MIN }
}
