package com.tj.portfolio.net

import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.data.DayTradingStats
import org.json.JSONObject

/**
 * DID A RECORDED DAY TRADING RECOMMENDATION ACTUALLY WORK - measured against real intraday
 * prices, never against anything before the recommendation was made.
 *
 * Tj, 2026-09-16: *"Make sure that the data shows whether the stock performed as recommended
 * AFTER the recommendation was made. It makes no sense to gauge how well the advice is based
 * on stock price movement before the advice was ever given."* [evaluate] enforces that
 * directly - every bar before `recordedAt` is filtered out before anything else runs.
 *
 * WHY A SEPARATE FETCH PATH FROM [ChartFeed]. That file's [com.tj.portfolio.data.ChartPoint]
 * carries only a close, which is what every OTHER chart in this app needs - a line, not a
 * verdict. Deciding whether a specific price level was crossed needs the bar's high and low
 * too, or a level touched between two closes reads as never reached. Reusing [ChartPoint]
 * would have meant widening a type every screen in the app depends on for one feature; a
 * parallel, narrower model here costs nothing and cannot regress a chart anyone else looks at.
 *
 * WHY [evaluate] READS BOTH [IntradayBar.high] AND [IntradayBar.low] BUT STILL CANNOT SEE
 * INSIDE A 5-MINUTE BAR. If a single bar's range covers both the target and the stop, this
 * cannot know which the price actually reached first - only that the bar's low and high both
 * qualify. It is read as the stop, deliberately conservative: a backtest that resolves every
 * ambiguous bar in its own favour is not measuring the strategy, it is measuring the
 * ambiguity. See [evaluate]'s own note at that branch.
 */
object DayTradingEval {

    /** One 5-minute bar of REAL, ALREADY-HAPPENED intraday trading - never the current, still
     *  forming one; see [fetchDaySeries]'s own bounds. [t] is epoch SECONDS, matching Yahoo's
     *  own timestamp unit. */
    data class IntradayBar(val t: Long, val high: Double, val low: Double, val close: Double)

    /**
     * The regular session's own 5-minute bars for [tradingDay] (`MarketClock.dayKey` format,
     * `yyyyMMdd`), fetched with an explicit `period1`/`period2` window rather than
     * [com.tj.portfolio.data.ChartRange]'s `range=` buckets - those only ever mean "ending
     * now", and this has to be able to ask about a day that is not today. Verified live
     * against the real Yahoo endpoint before writing this: `period1`/`period2` with
     * `interval=5m` returns exactly the bars inside the window asked for, for a day well in
     * the past.
     *
     * Null means the request itself failed (try again later); an EMPTY list is a real answer -
     * the symbol did not trade in this window, which on a `yyyyMMdd` that is a real weekday
     * usually means the data is simply no longer available (Yahoo's minute-level history has a
     * limited retention window) rather than that nothing happened.
     */
    suspend fun fetchDaySeries(symbol: String, tradingDay: String): List<IntradayBar>? {
        val bounds = sessionBoundsMs(tradingDay) ?: return null
        val period1 = bounds.first / 1000L
        val period2 = bounds.second / 1000L
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/" +
                MarketData.enc(symbol) + "?period1=$period1&period2=$period2&interval=5m"
            val r = Http.get(url, mapOf("Accept" to "application/json"))
            if (r.throttledLocally) continue
            if (!r.ok) continue
            return parseBars(r.body)
        }
        return null
    }

    /** TOTAL - malformed input is a real possibility (a proxy error page, a truncated body)
     *  and always reads as "nothing parsed", never an exception. */
    internal fun parseBars(body: String): List<IntradayBar> = runCatching {
        val res = JSONObject(body).optJSONObject("chart")
            ?.optJSONArray("result")?.optJSONObject(0) ?: return@runCatching emptyList()
        val ts = res.optJSONArray("timestamp") ?: return@runCatching emptyList()
        val quote = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: return@runCatching emptyList()
        val highs = quote.optJSONArray("high")
        val lows = quote.optJSONArray("low")
        val closes = quote.optJSONArray("close")
        if (highs == null || lows == null || closes == null) return@runCatching emptyList()
        val n = minOf(ts.length(), highs.length(), lows.length(), closes.length())
        val out = ArrayList<IntradayBar>(n)
        for (i in 0 until n) {
            if (highs.isNull(i) || lows.isNull(i) || closes.isNull(i)) continue
            val h = highs.optDouble(i, Double.NaN)
            val l = lows.optDouble(i, Double.NaN)
            val c = closes.optDouble(i, Double.NaN)
            // NEVER READ A GAP AS ZERO - same rule every other parser in this app follows. A
            // fabricated 0.0 here would read as "price crashed to zero", which would fire
            // every stop-loss check unconditionally.
            if (h.isNaN() || l.isNaN() || c.isNaN() || h <= 0.0 || l <= 0.0 || c <= 0.0) continue
            val t = ts.optLong(i, 0L)
            if (t <= 0L) continue
            out.add(IntradayBar(t, h, l, c))
        }
        out.sortedBy { it.t }
    }.getOrDefault(emptyList())

    /** [tradingDay]'s regular session (09:30-16:00 ET), as epoch ms. Null for an unparseable
     *  key - never a guessed range, since a wrong window would silently exclude real bars. */
    fun sessionBoundsMs(tradingDay: String): Pair<Long, Long>? {
        if (tradingDay.length != 8) return null
        val y = tradingDay.substring(0, 4).toIntOrNull() ?: return null
        val m = tradingDay.substring(4, 6).toIntOrNull() ?: return null
        val d = tradingDay.substring(6, 8).toIntOrNull() ?: return null
        return runCatching {
            val et = java.util.TimeZone.getTimeZone("America/New_York")
            val open = java.util.GregorianCalendar(et)
            open.clear(); open.set(y, m - 1, d, 9, 30, 0)
            val close = java.util.GregorianCalendar(et)
            close.clear(); close.set(y, m - 1, d, 16, 0, 0)
            open.timeInMillis to close.timeInMillis
        }.getOrNull()
    }

    /**
     * Which way price has to move to reach [com.tj.portfolio.data.DayTradingLogEntry.entry] -
     * decided from [priceAtRecommendation] whenever it is usable, which is the DIRECT answer
     * ("is entry above or below where the stock actually was") rather than an inference from
     * [setup]'s text.
     *
     * THE SETUP STRING IS ONLY A FALLBACK, AND A REAL BUG SHIPPED WHEN IT WAS THE ONLY SIGNAL
     * (caught by a requested pre-ship review). It is exact for this app's own three setups -
     * see [com.tj.portfolio.net.ResearchScore.TradePlan]'s header: a breakout or VWAP reclaim
     * sits ABOVE the price when it is set, a pullback sits BELOW it - but a Claude-authored
     * plan's `setup` is free text Claude wrote (`DayTradingBridge.kt`'s merge only requires
     * `stop < entry < target`, nothing about the setup NAME), so a real pullback-style Claude
     * plan called anything other than the literal word "Pullback" - "Support bounce", "Buy the
     * dip" - was read as RISING, made `entry` look already triggered on the very first bar
     * (price starts above a falling entry, so `high >= entry` is trivially true), and could
     * credit or blame a trade that was never actually placed.
     */
    internal fun entryRises(setup: String, entry: Double, priceAtRecommendation: Double): Boolean =
        if (priceAtRecommendation > 0.0 && kotlin.math.abs(entry - priceAtRecommendation) > 1e-9)
            entry > priceAtRecommendation
        else setup != ResearchScore.SETUP_PULLBACK

    /**
     * The real outcome of one recorded recommendation, decided ONLY from [bars] at or after
     * [recordedAt] - never anything earlier, per Tj's own explicit requirement (this file's
     * header). Returns the outcome plus the price it was decided at, or null when nothing was
     * decided yet.
     *
     * [sessionStillOpen] is whether [tradingDay]'s regular session has not yet closed - the
     * caller works this out from [sessionBoundsMs], since this function owns no clock (same
     * rule [ResearchScore.tradePlan] already follows, for the same reason: a pure function a
     * test can drive at any moment without waiting for one).
     */
    fun evaluate(
        setup: String,
        entry: Double,
        stop: Double,
        target: Double,
        priceAtRecommendation: Double,
        recordedAt: Long,
        bars: List<IntradayBar>,
        sessionStillOpen: Boolean
    ): Pair<String, Double?> {
        // A BAR ALREADY UNDER WAY WHEN recordedAt LANDS IS EXCLUDED WHOLE, not sliced at the
        // moment of recording - [IntradayBar] carries no `open`, so there is no way to tell how
        // much of a straddled bar's high/low happened before vs. after recordedAt. This can
        // discard up to ~5 real minutes of genuine post-recommendation trading (a conservative
        // miss - PENDING/NO_ENTRY a beat later than reality) rather than risk the opposite and
        // far worse mistake: crediting an entry/target/stop touch that may have happened before
        // the recommendation was ever made, which is exactly what Tj's own requirement (this
        // file's header) rules out.
        val after = bars.filter { it.t * 1000L >= recordedAt }
        val rises = entryRises(setup, entry, priceAtRecommendation)
        val entryIndex = after.indexOfFirst { bar -> if (rises) bar.high >= entry else bar.low <= entry }
        if (entryIndex < 0) {
            return (if (sessionStillOpen) DayTradingOutcome.PENDING else DayTradingOutcome.NO_ENTRY) to null
        }
        for (i in entryIndex until after.size) {
            val bar = after[i]
            // BOTH TARGET AND STOP REACHABLE IN THE SAME BAR - checked STOP first, always, so
            // this can never credit a win it did not actually prove happened. See this file's
            // own header on why a 5-minute high/low cannot say which came first inside it.
            if (bar.low <= stop) return DayTradingOutcome.LOSS to stop
            // A FALLING (PULLBACK) ENTRY'S OWN TRIGGER BAR IS THE ONE CASE WHERE THE TARGET
            // CHECK ABOVE IS ITSELF AMBIGUOUS, not just the stop/target pair. Entry there
            // triggers off the bar's LOW (price fell to the buy-limit); target is read off the
            // same bar's HIGH - two different extremes with no way to know which came first
            // inside one 5-minute bar. That is NOT true of the rising case (entry and target
            // both read off the HIGH, so target > entry mathematically guarantees price passed
            // through entry on the way up - no ambiguity) or of the stop check just above
            // (entry and stop both read off the LOW, same reasoning). Crediting a WIN here
            // would be exactly the "resolve an ambiguous bar in the strategy's own favour"
            // mistake this file's header warns against, so it is deferred to the first LATER
            // bar instead - by then entry is known to have already triggered, so any target hit
            // is unambiguous. A real intrabar win in the entry bar itself reads one bar late
            // rather than not at all, and one that never really filled before retracing is
            // never credited - both err toward under-, not over-, crediting the strategy.
            val targetProvable = rises || i > entryIndex
            if (targetProvable && bar.high >= target) return DayTradingOutcome.WIN to target
        }
        if (sessionStillOpen) return DayTradingOutcome.PENDING to null
        // A DAY TRADE IS FLAT BEFORE THE CLOSE (TradePlan's own rule) - simulated the same way
        // here: neither level was reached, so the trade is marked closed at the session's own
        // last print rather than left open indefinitely. `after` is guaranteed non-empty here -
        // `entryIndex >= 0` only ever comes from a real match inside it.
        val lastClose = after.last().close
        return (if (lastClose > entry) DayTradingOutcome.CLOSED_PROFIT else DayTradingOutcome.CLOSED_LOSS) to
            lastClose
    }

    /**
     * The "success rate" button's other half - turns a pile of resolved log rows into the two
     * headline numbers [DayTradingStats.targetHitRate] and [DayTradingStats.avgReturnPct]. See
     * [DayTradingStats]'s own header for what each means and why.
     */
    fun stats(entries: List<DayTradingLogEntry>): DayTradingStats {
        var targetHit = 0; var stopHit = 0; var closedProfit = 0; var closedLoss = 0
        var noEntry = 0; var pending = 0; var dataUnavailable = 0
        val returns = ArrayList<Double>()

        fun pctReturn(entry: Double, exit: Double): Double =
            if (entry > 1e-9) (exit - entry) / entry * 100.0 else 0.0

        for (e in entries) {
            when (e.outcome) {
                DayTradingOutcome.WIN -> {
                    targetHit++; returns.add(pctReturn(e.entry, e.outcomeExitPrice ?: e.target))
                }
                DayTradingOutcome.LOSS -> {
                    stopHit++; returns.add(pctReturn(e.entry, e.outcomeExitPrice ?: e.stop))
                }
                DayTradingOutcome.CLOSED_PROFIT -> {
                    closedProfit++; returns.add(pctReturn(e.entry, e.outcomeExitPrice ?: e.entry))
                }
                DayTradingOutcome.CLOSED_LOSS -> {
                    closedLoss++; returns.add(pctReturn(e.entry, e.outcomeExitPrice ?: e.entry))
                }
                DayTradingOutcome.NO_ENTRY -> noEntry++
                DayTradingOutcome.DATA_UNAVAILABLE -> dataUnavailable++
                else -> pending++ // null (never evaluated) reads the same as an explicit PENDING
            }
        }
        val decided = targetHit + stopHit + closedProfit + closedLoss
        return DayTradingStats(
            totalRecommendations = entries.size,
            entriesTriggered = decided,
            targetHit = targetHit,
            stopHit = stopHit,
            closedProfit = closedProfit,
            closedLoss = closedLoss,
            noEntry = noEntry,
            pending = pending,
            dataUnavailable = dataUnavailable,
            targetHitRate = if (decided > 0) targetHit.toDouble() / decided * 100.0 else 0.0,
            profitableRate = if (decided > 0) (targetHit + closedProfit).toDouble() / decided * 100.0 else 0.0,
            avgReturnPct = if (returns.isNotEmpty()) returns.average() else 0.0,
            evaluatedAt = System.currentTimeMillis()
        )
    }
}
