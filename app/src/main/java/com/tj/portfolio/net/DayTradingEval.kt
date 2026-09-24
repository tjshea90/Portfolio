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
    /** Whether a bar stamped [tSec] (its open) starts before the plan's flat time that day (D-11). */
    internal fun beforeFlatTime(tSec: Long): Boolean {
        val et = java.time.Instant.ofEpochSecond(tSec).atZone(java.time.ZoneId.of("America/New_York"))
        return et.hour * 60 + et.minute < MarketClock.closeMinuteAt(tSec * 1000L) - 10
    }

    suspend fun fetchDaySeries(
        symbol: String,
        tradingDay: String,
        /** "5m" normally; "1m" only to settle a bar [evaluateResolved] could not (2026-09-24b). */
        interval: String = "5m"
    ): List<IntradayBar>? {
        val bounds = sessionBoundsMs(tradingDay) ?: return null
        val period1 = bounds.first / 1000L
        val period2 = bounds.second / 1000L
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/" +
                MarketData.enc(symbol) + "?period1=$period1&period2=$period2&interval=$interval"
            // CONDITIONAL in case Yahoo ever sends a validator for this CLOSED, immutable window -
            // but as of 2026-09-23 its chart endpoint sends none, so this is a full download per
            // press (N-1); what bounds the cost is the per-press cap in `evaluateDayTradingLog`.
            val r = Http.get(url, mapOf("Accept" to "application/json"))
            if (r.throttledLocally) continue
            // A 404 IS AN ANSWER: "no data found, symbol may be delisted" (D-12) - and an
            // answer needs no second host asking the same question (N-12).
            if (r.code == 404) return emptyList()
            if (!r.ok) continue
            // `continue`, NOT `return`: a 200 that parses to nothing (a truncated body, a
            // proxy error page) used to end the loop, so query2 never got its turn and the
            // caller wrote DATA_UNAVAILABLE against a day whose bars the other host had.
            val bars = parseBars(r.body)
            if (bars.isNotEmpty()) return bars
            if (answeredNoBars(r.body)) return emptyList()   // answered: nothing there (N-12)
        }
        // NULL = ASKED AND NOT ANSWERED; EMPTY = ANSWERED, NOTHING THERE (full test 2026-09-24,
        // D-12). Both came back null, so a press that tripped a host cooldown wrote every
        // remaining settled row as "no price history available" - which is what the doc above
        // promised only a real answer could mean.
        return null
    }

    /** A well-formed chart reply for the window - `chart.result[0]` present, no error - with no bars in it. */
    internal fun answeredNoBars(body: String): Boolean = runCatching {
        val chart = org.json.JSONObject(body).optJSONObject("chart") ?: return@runCatching false
        val err = chart.opt("error")
        chart.optJSONArray("result")?.optJSONObject(0) != null && (err == null || err == org.json.JSONObject.NULL)
    }.getOrDefault(false)

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
     * HOW LONG AFTER 16:00 A SESSION'S BARS ARE TRUSTED TO BE COMPLETE (full-tests audit
     * 2026-09-22, D-L6). The 15:55 bar is published a few minutes after the bell, and a closed
     * session's outcome is FINAL - never re-evaluated. Settling at 16:01 could therefore decide
     * "closed at the bell" from the 15:50 bar, for good. Until this has passed the day is still
     * treated as open: an undecided trade stays PENDING and the next press looks again.
     */
    const val SETTLE_GRACE_MS = 20L * 60_000L

    /** Has [tradingDay]'s session been over long enough for its bars to be final? */
    fun sessionSettled(tradingDay: String, now: Long = System.currentTimeMillis()): Boolean {
        val bounds = sessionBoundsMs(tradingDay) ?: return true
        return now >= bounds.second + SETTLE_GRACE_MS
    }

    /**
     * HOW FAR BACK YAHOO ACTUALLY KEEPS 5-MINUTE BARS, and why this app has to know.
     *
     * [fetchDaySeries] asks for `interval=5m` over one past session. Yahoo serves minute-level
     * history for roughly the last 60 days and then stops - the request still succeeds, it just
     * comes back with no bars, which [com.tj.portfolio.ui.PortfolioViewModel
     * .resolveOneDayTradingEntry] correctly records as
     * [com.tj.portfolio.data.DayTradingOutcome.DATA_UNAVAILABLE].
     *
     * THE BUG THAT NEEDED THIS CONSTANT. `DATA_UNAVAILABLE` is deliberately not
     * [com.tj.portfolio.data.DayTradingOutcome.isFinal] - a fetch can fail for a day that is
     * still perfectly readable, and that row deserves another try. But a day whose bars have
     * aged out of the provider's window is NOT coming back, ever, and the log only grows. So
     * every press of "re-check success rate" was re-requesting one session of intraday history
     * for every recommendation ever recorded past the window, getting the same empty answer
     * every time, forever - a cost that rises with the age of the log and buys nothing. Rows
     * older than this are left alone.
     *
     * 55, not 60: the edge of a provider's retention window is not a documented contract, and
     * being a few days conservative costs at most a handful of retries on days that were about
     * to expire anyway.
     */
    const val INTRADAY_RETENTION_DAYS = 55

    /**
     * Is [tradingDay]'s intraday history still inside the provider's window? Pure, and a
     * `false` here is the only thing that stops a permanently-empty row being re-requested on
     * every press - see [INTRADAY_RETENTION_DAYS].
     *
     * An unparseable key answers TRUE: "I cannot tell how old this is" must not become "skip
     * it", or a corrupt row would silently stop being evaluated instead of being marked.
     */
    fun intradayStillAvailable(tradingDay: String, now: Long = System.currentTimeMillis()): Boolean {
        val bounds = sessionBoundsMs(tradingDay) ?: return true
        return (now - bounds.second) <= INTRADAY_RETENTION_DAYS * 86_400_000L
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
    ): Pair<String, Double?> = evaluateResolved(
        setup, entry, stop, target, priceAtRecommendation, recordedAt, bars, sessionStillOpen
    ).let { it.outcome to it.price }

    /**
     * [evaluate]'s answer, and whether it rests on a bar whose INSIDE decides it (2026-09-24b,
     * the 09-19 "needs Tj's call" item, which Tj settled: resolve it, pessimistic only when it
     * cannot be). A 5-minute high and low cannot say which came first, so three cases are
     * genuinely unknown from them:
     *  - a rising entry's own trigger bar that also went below the stop (stopped out after the
     *    fill, or dipped first and filled after - a loss either way was the old answer);
     *  - any bar that touched BOTH the stop and the target (stop first was the old answer);
     *  - a falling entry's trigger bar that also reached the target (deferred to later bars).
     * [ambiguous] true sends the caller to the day's ONE-MINUTE bars, which settle almost every
     * such case; with none available the conservative answer above stands.
     */
    class Resolved(val outcome: String, val price: Double?, val ambiguous: Boolean)

    fun evaluateResolved(
        setup: String,
        entry: Double,
        stop: Double,
        target: Double,
        priceAtRecommendation: Double,
        recordedAt: Long,
        bars: List<IntradayBar>,
        sessionStillOpen: Boolean
    ): Resolved {
        // A BAR ALREADY UNDER WAY WHEN recordedAt LANDS IS EXCLUDED WHOLE, not sliced at the
        // moment of recording - [IntradayBar] carries no `open`, so there is no way to tell how
        // much of a straddled bar's high/low happened before vs. after recordedAt. This can
        // discard up to ~5 real minutes of genuine post-recommendation trading (a conservative
        // miss - PENDING/NO_ENTRY a beat later than reality) rather than risk the opposite and
        // far worse mistake: crediting an entry/target/stop touch that may have happened before
        // the recommendation was ever made, which is exactly what Tj's own requirement (this
        // file's header) rules out.
        // (The plan's "flat by 15:50" cut is applied by the caller to real bars - see
        // [beforeFlatTime], D-11.)
        val after = bars.filter { it.t * 1000L >= recordedAt }
        val rises = entryRises(setup, entry, priceAtRecommendation)
        val entryIndex = after.indexOfFirst { bar -> if (rises) bar.high >= entry else bar.low <= entry }
        if (entryIndex < 0) {
            return Resolved(
                if (sessionStillOpen) DayTradingOutcome.PENDING else DayTradingOutcome.NO_ENTRY, null, false)
        }
        var deferredWin = false
        for (i in entryIndex until after.size) {
            val bar = after[i]
            // BOTH TARGET AND STOP REACHABLE IN THE SAME BAR - checked STOP first, always, so
            // this can never credit a win it did not actually prove happened. See this file's
            // own header on why a 5-minute high/low cannot say which came first inside it.
            if (bar.low <= stop) return Resolved(DayTradingOutcome.LOSS, stop,
                ambiguous = deferredWin || (i == entryIndex && rises) || bar.high >= target)
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
            if (targetProvable && bar.high >= target) return Resolved(DayTradingOutcome.WIN, target, false)
            if (!targetProvable && bar.high >= target) deferredWin = true
        }
        if (sessionStillOpen) return Resolved(DayTradingOutcome.PENDING, null, deferredWin)
        // A DAY TRADE IS FLAT BEFORE THE CLOSE (TradePlan's own rule) - simulated the same way
        // here: neither level was reached, so the trade is marked closed at the session's own
        // last print rather than left open indefinitely. `after` is guaranteed non-empty here -
        // `entryIndex >= 0` only ever comes from a real match inside it.
        val lastClose = after.last().close
        return Resolved(
            if (lastClose > entry) DayTradingOutcome.CLOSED_PROFIT else DayTradingOutcome.CLOSED_LOSS,
            lastClose, deferredWin)
    }

    /**
     * How far back Yahoo keeps ONE-MINUTE bars - 30 days, one short of it to stay clear of the
     * edge. Past it an ambiguous bar keeps its conservative 5-minute answer.
     */
    const val ONE_MINUTE_RETENTION_DAYS = 29

    fun oneMinuteStillAvailable(tradingDay: String, now: Long = System.currentTimeMillis()): Boolean {
        val bounds = sessionBoundsMs(tradingDay) ?: return false
        return (now - bounds.second) <= ONE_MINUTE_RETENTION_DAYS * 86_400_000L
    }

    /**
     * WHAT A REAL FILL COSTS, THAT A 5-MINUTE BAR CANNOT SHOW (2026-09-18).
     *
     * [evaluate] exits at exactly `stop` and exactly `target` because those are the only prices
     * the bar data can PROVE were reached. Reported straight, that is a backtest of a trader who
     * never pays a spread and whose stop always fills at its own price - and every one of those
     * idealisations flatters the system, so the error does not average out, it accumulates in one
     * direction. Tj asked whether the tracker "tells me an accurate number if I were to trade
     * using the day trading system"; a figure that quietly assumes perfect fills does not.
     *
     * The three numbers below are in BASIS POINTS OF PRICE, not cents, because a $2 stock and a
     * $200 stock have completely different tick economics and this section screens both:
     *
     *  - [ENTRY_BPS] 5bp, charged to EVERY entry alike, not split by setup. A day-trade entry
     *    here is a buy-STOP above resistance or a buy-LIMIT at support: in principle the limit
     *    gets its price or better while the stop becomes a market order and pays. [entryFill]
     *    does not make that distinction - it is one more place this model errs toward
     *    UNDERSTATING the system, the same rule the rest of this section follows: a limit
     *    order's "price or better" is the optimistic case, or not a fill at all, and charging
     *    it the stop's cost anyway is the conservative side to be wrong on. Five basis points
     *    is about the measured effective spread on a liquid US name - and this section already
     *    floors its candidates at [Research]'s $2 / 1M-share filters, so a liquid name is what
     *    it screens.
     *  - [STOP_BPS] 15bp, three times the entry. A protective stop is a market order that
     *    triggers precisely when the tape is moving fast against the position - which is when
     *    slippage is worst, not average. This is the one asymmetry in the model and it is
     *    deliberate.
     *  - [CLOSE_BPS] 5bp, for the flat-by-the-bell exit, which is a market order in the busiest
     *    part of the session.
     *
     * A TARGET EXIT PAYS NOTHING, because it is a resting limit order at a price the tape
     * actually traded through - it fills at the target or better. That is the optimistic corner
     * left standing: a target only TICKED may not have filled a real order at all, and nothing
     * in bar data can say. Noted here rather than modelled with a number that would be invented.
     *
     * JUDGMENT CALLS, DOCUMENTED AS SUCH - measured spreads vary by name, time of day and order
     * type, and no free feed available here reports the fill Tj would actually have got. They
     * are set to err toward UNDERSTATING the system, which is the safe direction for a number
     * whose whole job is to say whether the advice is worth following.
     */
    object Costs {
        const val ENTRY_BPS = 5.0
        const val STOP_BPS = 15.0
        const val CLOSE_BPS = 5.0

        private fun bps(v: Double) = v / 10_000.0

        /** What was actually paid to get in - the trigger price plus the entry slippage. */
        fun entryFill(entry: Double): Double = entry * (1.0 + bps(ENTRY_BPS))

        /** What the exit actually returned, by how the trade ended. */
        fun exitFill(outcome: String?, exit: Double): Double = when (outcome) {
            // A resting limit at a level price traded through: its own price or better.
            DayTradingOutcome.WIN -> exit
            DayTradingOutcome.LOSS -> exit * (1.0 - bps(STOP_BPS))
            else -> exit * (1.0 - bps(CLOSE_BPS))
        }

        /** The round trip's total drag, in percent, for the on-screen note. */
        fun roundTripPct(outcome: String?): Double = ENTRY_BPS / 100.0 + when (outcome) {
            DayTradingOutcome.WIN -> 0.0
            DayTradingOutcome.LOSS -> STOP_BPS / 100.0
            else -> CLOSE_BPS / 100.0
        }
    }

    /**
     * The "success rate" button's other half - turns a pile of resolved log rows into the
     * headline numbers on [DayTradingStats]. See that class's own header for what each means
     * and why, including what 2026-09-18 added and the two real gaps it closed.
     */
    fun stats(entries: List<DayTradingLogEntry>): DayTradingStats {
        var targetHit = 0; var stopHit = 0; var closedProfit = 0; var closedLoss = 0
        var noEntry = 0; var pending = 0; var dataUnavailable = 0
        val returns = ArrayList<Double>()
        val netReturns = ArrayList<Double>()
        val rMultiples = ArrayList<Double>()
        // Each trade's result as a fraction of the account, sized exactly as positionSize sizes
        // it - see [ResearchScore.dayTradeSharesPerEquity].
        var account = 0.0
        var capped = 0
        // Trades that made money AFTER the modelled costs - see `profitableRate`.
        var netProfitable = 0

        fun pctReturn(entry: Double, exit: Double): Double =
            if (entry > 1e-9) (exit - entry) / entry * 100.0 else 0.0

        /**
         * One decided row, three ways: as the levels said it went, as it would have gone after
         * [Costs], and in units of its own risk. Kept in one place so a future change to the
         * cost model cannot update the percentage figures and leave the R figures behind
         * describing a different trade.
         */
        fun record(e: DayTradingLogEntry, exit: Double) {
            returns.add(pctReturn(e.entry, exit))
            val paid = Costs.entryFill(e.entry)
            val got = Costs.exitFill(e.outcome, exit)
            netReturns.add(pctReturn(paid, got))
            if (got > paid) netProfitable++
            // RISK IS MEASURED FROM THE PLAN'S OWN LEVELS, NOT FROM THE FILLED PRICES. `entry -
            // stop` is what the position was SIZED against by `ResearchScore.positionSize` when
            // the order went in; re-deriving it from the slipped fill would be measuring the
            // result against a risk budget that was never actually set.
            val risk = e.entry - e.stop
            if (risk > 1e-9) {
                rMultiples.add((got - paid) / risk)
                val perEquity = ResearchScore.dayTradeSharesPerEquity(e.entry, e.stop)
                account += perEquity * (got - paid)
                if (perEquity < ResearchScore.dayTradeRiskFraction() / risk - 1e-12) capped++
            }
        }

        for (e in entries) {
            when (e.outcome) {
                DayTradingOutcome.WIN -> { targetHit++; record(e, e.outcomeExitPrice ?: e.target) }
                DayTradingOutcome.LOSS -> { stopHit++; record(e, e.outcomeExitPrice ?: e.stop) }
                DayTradingOutcome.CLOSED_PROFIT -> { closedProfit++; record(e, e.outcomeExitPrice ?: e.entry) }
                DayTradingOutcome.CLOSED_LOSS -> { closedLoss++; record(e, e.outcomeExitPrice ?: e.entry) }
                DayTradingOutcome.NO_ENTRY -> noEntry++
                DayTradingOutcome.DATA_UNAVAILABLE -> dataUnavailable++
                else -> pending++ // null (never evaluated) reads the same as an explicit PENDING
            }
        }
        val decided = targetHit + stopHit + closedProfit + closedLoss
        val totalR = rMultiples.sum()
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
            // NET, like the headline beside it (full-tests audit 2026-09-22, D-L10). Counting
            // every CLOSED_PROFIT - a close a cent above the entry included - called a trade
            // profitable that the account figure, after costs, counted as a loss.
            profitableRate = if (decided > 0) netProfitable.toDouble() / decided * 100.0 else 0.0,
            profitableCount = netProfitable,
            avgReturnPct = if (returns.isNotEmpty()) returns.average() else 0.0,
            totalReturnPct = returns.sum(),
            netAvgReturnPct = if (netReturns.isNotEmpty()) netReturns.average() else 0.0,
            netTotalReturnPct = netReturns.sum(),
            totalR = totalR,
            avgR = if (rMultiples.isNotEmpty()) totalR / rMultiples.size else 0.0,
            accountReturnPct = account * 100.0,
            cappedTrades = capped,
            // EVERY row's session, not just the decided ones - "42 picks across 9 sessions" is
            // the context an average per trade needs, and a day whose picks all expired without
            // triggering is still a day the system was followed.
            sessions = entries.mapTo(HashSet()) { it.tradingDay }.size,
            evaluatedAt = System.currentTimeMillis(),
            breakdown = breakdown(entries)
        )
    }

    /** The decided outcomes - a trade that actually happened and is over. */
    private val DECIDED = setOf(DayTradingOutcome.WIN, DayTradingOutcome.LOSS,
        DayTradingOutcome.CLOSED_PROFIT, DayTradingOutcome.CLOSED_LOSS)

    /** See [DayTradingStats.breakdown]. Net "profitable" is the same test the headline uses. */
    internal fun breakdown(entries: List<DayTradingLogEntry>): List<com.tj.portfolio.data.StatSlice> {
        val decided = entries.filter { it.outcome in DECIDED }
        fun profitable(e: DayTradingLogEntry): Boolean {
            val exit = e.outcomeExitPrice ?: when (e.outcome) {
                DayTradingOutcome.WIN -> e.target
                DayTradingOutcome.LOSS -> e.stop
                else -> e.entry
            }
            return Costs.exitFill(e.outcome, exit) > Costs.entryFill(e.entry)
        }
        fun slices(group: String, key: (DayTradingLogEntry) -> String, order: List<String>) =
            decided.groupBy(key).map { (label, rows) ->
                com.tj.portfolio.data.StatSlice(group, label, rows.size,
                    rows.count { it.outcome == DayTradingOutcome.WIN }, rows.count { profitable(it) })
            }.sortedBy { order.indexOf(it.label).let { i -> if (i < 0) order.size else i } }
        val who = listOf("The app's plans", "Claude's plans")
        val setups = listOf(ResearchScore.SETUP_BREAKOUT, ResearchScore.SETUP_PULLBACK,
            ResearchScore.SETUP_RECLAIM, "Claude's own setups")
        val times = listOf("First hour", "Midday", "Last two hours")
        return slices("Who planned it", {
            if (it.source == DayTradingLogEntry.SOURCE_CLAUDE) who[1] else who[0]
        }, who) + slices("Setup", {
            if (it.setup in setups.take(3)) it.setup else "Claude's own setups"
        }, setups) + slices("When it was recommended", { e ->
            val et = java.time.Instant.ofEpochMilli(e.recordedAt).atZone(java.time.ZoneId.of("America/New_York"))
            val m = et.hour * 60 + et.minute
            when {
                m < 10 * 60 + 30 -> times[0]
                m < MarketClock.closeMinuteAt(e.recordedAt) - 120 -> times[1]
                else -> times[2]
            }
        }, times)
    }
}
