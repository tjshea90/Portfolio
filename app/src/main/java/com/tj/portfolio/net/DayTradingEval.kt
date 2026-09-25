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
    data class IntradayBar(
        val t: Long,
        val high: Double,
        val low: Double,
        val close: Double,
        /** The bar's first print - NaN when the feed did not say (2026-09-24c: gap fills, see [DayTradingGrader]). */
        val open: Double = Double.NaN
    )

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
        var refusals = 0
        val hosts = listOf("query1", "query2")
        for (host in hosts) {
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
            // AND SO IS YAHOO'S REFUSAL OF A WINDOW IT DOES NOT SERVE (audit PL-13): one-minute
            // bars older than its limit come back 422 ("must be within the last 30 days") - read as
            // "nothing here" once BOTH hosts have said so, and the caller falls back to five-minute
            // bars. Only a 422: a 400 can be an edge or proxy blip, and reading it as "no one-minute
            // bars" would grade a row on the coarser bars for good (audit R2P-3) - it is a failure,
            // asked again next time.
            if (r.code == 422) { refusals++; continue }
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
        // BOTH, not either (audit R3G-1): one host's 422 beside the other's failure or cooldown is not
        // yet an answer - read as one, the row was graded on five-minute bars for good.
        return if (refusals == hosts.size) emptyList() else null
    }

    /**
     * A settled session's bars as a compact, deflated blob for [com.tj.portfolio.data.Db.cacheDayBars]
     * (audit PL-6): one `t,o,h,l,c` line per bar, the open left empty when unknown.
     */
    fun encodeBars(bars: List<IntradayBar>): ByteArray {
        val sb = StringBuilder(bars.size * 40)
        fun n(v: Double) = if (v.isFinite()) java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() else ""
        for (b in bars) sb.append(b.t).append(',').append(n(b.open)).append(',').append(n(b.high)).append(',')
            .append(n(b.low)).append(',').append(n(b.close)).append('\n')
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** TOTAL - an unreadable blob is an empty list (the caller then fetches again). */
    fun decodeBars(blob: ByteArray?): List<IntradayBar> = runCatching {
        if (blob == null || blob.isEmpty()) return@runCatching emptyList()
        val text = java.util.zip.InflaterInputStream(blob.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split(',')
            if (f.size != 5) return@mapNotNull null
            val t = f[0].toLongOrNull() ?: return@mapNotNull null
            val h = f[2].toDoubleOrNull() ?: return@mapNotNull null
            val l = f[3].toDoubleOrNull() ?: return@mapNotNull null
            val c = f[4].toDoubleOrNull() ?: return@mapNotNull null
            IntradayBar(t, h, l, c, f[1].toDoubleOrNull() ?: Double.NaN)
        }.toList()
    }.getOrDefault(emptyList())

    /**
     * A row logged before 2026-09-24c (no `features`) whose own price at the time shows the card
     * told Tj NOT to take it (audit DA-19): already at or past its target, at or under its stop,
     * or with no price to show either way. The logging gate that now keeps such plans out (E5)
     * did not exist then, and re-grading one as a live order - its direction read from a price
     * that was past the target - credited trades the card said to skip. Never graded, never
     * counted; the card says how many.
     */
    fun notTradeableOldRow(e: DayTradingLogEntry): Boolean {
        if (e.features.isNotBlank()) return false
        val p = e.priceAtRecommendation
        if (!(p > 0.0 && e.stop < p && p < e.target)) return true
        // AND ALREADY THROUGH ITS BUY PRICE (audit R2G-8), where the setup says which side the card's
        // order was on - a Breakout or VWAP reclaim logged with the price above its buy-stop, a
        // Pullback logged below its buy-limit, would otherwise be graded as the opposite order. A
        // free-text Claude setup cannot be checked this way and is kept.
        return when (ResearchScore.planEntryRises(e.setup, e.entry, 0.0)) {
            true -> p >= e.entry
            false -> p <= e.entry
            null -> false
        }
    }

    /** A well-formed chart reply for the window - `chart.result[0]` present, no error - with no bars in it. */
    internal fun answeredNoBars(body: String): Boolean = runCatching {
        val chart = org.json.JSONObject(body).optJSONObject("chart") ?: return@runCatching false
        val err = chart.opt("error")
        chart.optJSONArray("result")?.optJSONObject(0) != null && (err == null || err == org.json.JSONObject.NULL)
    }.getOrDefault(false)

    /** A price to 1/10000 of a dollar - the finest tick a US stock quotes in; NaN stays NaN. */
    private fun px(v: Double): Double = if (v.isFinite()) Math.round(v * 10_000.0) / 10_000.0 else v

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
        val opens = quote.optJSONArray("open")
        if (highs == null || lows == null || closes == null) return@runCatching emptyList()
        val n = minOf(ts.length(), highs.length(), lows.length(), closes.length())
        val out = ArrayList<IntradayBar>(n)
        for (i in 0 until n) {
            if (highs.isNull(i) || lows.isNull(i) || closes.isNull(i)) continue
            // ROUNDED TO 1/10000 OF A DOLLAR (audit R2P-2): Yahoo sends float32 values (12.34 arrives as
            // 12.34000015258789), so an exact touch of a round-cent plan level was a coin toss - and
            // a fresh reply and the same day read back from the phone's cache disagreed about it.
            val h = px(highs.optDouble(i, Double.NaN))
            val l = px(lows.optDouble(i, Double.NaN))
            val c = px(closes.optDouble(i, Double.NaN))
            // NEVER READ A GAP AS ZERO - same rule every other parser in this app follows. A
            // fabricated 0.0 here would read as "price crashed to zero", which would fire
            // every stop-loss check unconditionally.
            if (h.isNaN() || l.isNaN() || c.isNaN() || h <= 0.0 || l <= 0.0 || c <= 0.0) continue
            val t = ts.optLong(i, 0L)
            if (t <= 0L) continue
            // An open outside the bar's own range is a bad value, not a gap - dropped, not used.
            val o = opens?.takeIf { i < it.length() && !it.isNull(i) }?.optDouble(i, Double.NaN)?.let(::px)
                ?.takeIf { it.isFinite() && it >= l - 1e-9 && it <= h + 1e-9 } ?: Double.NaN
            out.add(IntradayBar(t, h, l, c, o))
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
        // SINCE 2026-09-24c THIS IS [DayTradingGrader] WITH ITS FILL REALISM SWITCHED OFF - touch
        // fills (no trade-through tick), no spike filter, no entry cut-off or flat time, and a bar
        // with no open never gaps. That keeps the original sequencing rules (bars before the
        // recommendation excluded whole, stop before target, the pullback bar's deferred target)
        // pinned by this file's tests against the SAME code the app grades with. The app itself
        // calls [DayTradingGrader.grade] with every rule on.
        val g = DayTradingGrader.grade(
            DayTradingGrader.Spec(entry, stop, target, entryRises(setup, entry, priceAtRecommendation), recordedAt),
            bars,
            decidedThroughSec = if (sessionStillOpen) 0L else Long.MAX_VALUE,
            res = 5, spikeFilter = false, withGrid = false, tk = 0.0, realOpens = false
        )
        return Resolved(g.outcome, g.exitPrice, g.ambiguous)
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
     *
     * 2026-09-24c: only rows graded by the CURRENT grader count ([DayTradingGrader.VERSION] - an
     * older verdict whose bars are gone is counted as [DayTradingStats.legacyExcluded] instead);
     * each trade's P&L runs from its REAL fill, not the entry it was aiming for; the account
     * figure only counts trades the account could actually have funded at the time (audit E8);
     * and the rates carry 95% intervals, so a handful of trades cannot read like a verdict (E10).
     */
    fun stats(entries: List<DayTradingLogEntry>, now: Long = System.currentTimeMillis()): DayTradingStats {
        var targetHit = 0; var stopHit = 0; var closedProfit = 0; var closedLoss = 0
        var noEntry = 0; var pending = 0; var dataUnavailable = 0; var legacy = 0; var regrading = 0
        var oldSkipped = 0
        var unchecked = 0; var appTrades = 0; var claudeTrades = 0
        var res1 = 0; var res5 = 0
        val returns = ArrayList<Double>()
        val netReturns = ArrayList<Double>()
        val rMultiples = ArrayList<Double>()
        var accountAll = 0.0
        var capped = 0
        var netProfitable = 0
        val trades = ArrayList<Trade>()

        fun pctReturn(entry: Double, exit: Double): Double =
            if (entry > 1e-9) (exit - entry) / entry * 100.0 else 0.0

        /**
         * One decided row, three ways: as the levels said it went, as it would have gone after
         * [Costs], and in units of its own risk. Kept in one place so a future change to the
         * cost model cannot update the percentage figures and leave the R figures behind
         * describing a different trade.
         */
        fun record(e: DayTradingLogEntry, exit: Double, d: DayTradingGrader.Detail?) {
            // THE REAL FILL (2026-09-24c, E2): a buy-stop that gapped through its entry filled at
            // the open, not at the entry; the grade recorded which.
            val fill = d?.fill?.takeIf { it > 0.0 } ?: e.entry
            returns.add(pctReturn(fill, exit))
            val paid = Costs.entryFill(fill)
            val got = Costs.exitFill(e.outcome, exit)
            netReturns.add(pctReturn(paid, got))
            if (got > paid) netProfitable++
            // RISK IS MEASURED FROM THE PLAN'S OWN LEVELS, NOT FROM THE FILLED PRICES. `entry -
            // stop` is what the position was SIZED against by `ResearchScore.positionSize` when
            // the order went in; re-deriving it from the slipped fill would be measuring the
            // result against a risk budget that was never actually set.
            val risk = e.entry - e.stop
            if (risk > 1e-9) {
                val r = (got - paid) / risk
                rMultiples.add(r)
                val perEquity = ResearchScore.dayTradeSharesPerEquity(e.entry, e.stop)
                accountAll += perEquity * (got - paid)
                val isCapped = perEquity < ResearchScore.dayTradeRiskFraction() / risk - 1e-12
                if (isCapped) capped++
                trades.add(Trade(e, r, perEquity * (got - paid), perEquity * paid,
                    d?.fillAt ?: (e.recordedAt / 1000L), d?.exitAt ?: (e.recordedAt / 1000L), isCapped))
            }
            if (d?.res == 1) res1++ else res5++
            if (e.source == DayTradingLogEntry.SOURCE_CLAUDE) claudeTrades++ else appTrades++
        }

        for (e in entries) {
            if (notTradeableOldRow(e)) { oldSkipped++; continue }
            val final = DayTradingOutcome.isFinal(e.outcome)
            // GRADED UNDER THE OLD RULES (E9): while its bars exist it is queued for re-grading;
            // once they are gone it is kept and counted, never used. Neither is in the headline.
            if (final && e.evalVersion < DayTradingGrader.VERSION) {
                if (intradayStillAvailable(e.tradingDay, now)) regrading++ else legacy++
                continue
            }
            val d = DayTradingGrader.Detail.parse(e.evalDetail)
            when (e.outcome) {
                DayTradingOutcome.WIN -> { targetHit++; record(e, e.outcomeExitPrice ?: e.target, d) }
                DayTradingOutcome.LOSS -> { stopHit++; record(e, e.outcomeExitPrice ?: e.stop, d) }
                DayTradingOutcome.CLOSED_PROFIT -> { closedProfit++; record(e, e.outcomeExitPrice ?: e.entry, d) }
                DayTradingOutcome.CLOSED_LOSS -> { closedLoss++; record(e, e.outcomeExitPrice ?: e.entry, d) }
                DayTradingOutcome.NO_ENTRY -> noEntry++
                DayTradingOutcome.DATA_UNAVAILABLE -> dataUnavailable++
                // Never evaluated, for a session already over: "not checked yet", not "in progress" (UI-26).
                null -> if (sessionSettled(e.tradingDay, now)) unchecked++ else pending++
                else -> pending++
            }
        }
        val decided = targetHit + stopHit + closedProfit + closedLoss
        val counted = entries.filterNot {
            notTradeableOldRow(it) || (DayTradingOutcome.isFinal(it.outcome) && it.evalVersion < DayTradingGrader.VERSION)
        }
        val totalR = rMultiples.sum()
        val wins = rMultiples.filter { it > 0.0 }
        val losses = rMultiples.filter { it <= 0.0 }
        val (rLow, rHigh) = meanInterval(rMultiples)
        val (pLow, pHigh) = wilson(netProfitable, decided)
        val funded = fundable(trades)
        return DayTradingStats(
            totalRecommendations = entries.size - legacy - oldSkipped,
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
            accountReturnPct = funded.sumOf { it.account } * 100.0,
            // OF THE TRADES IN THAT FIGURE (UI-2) - counted over every trade, it could exceed them.
            cappedTrades = funded.count { it.capped },
            // EVERY row's session, not just the decided ones - "42 picks across 9 sessions" is
            // the context an average per trade needs, and a day whose picks all expired without
            // triggering is still a day the system was followed.
            sessions = counted.mapTo(HashSet()) { it.tradingDay }.size,
            evaluatedAt = now,
            breakdown = breakdown(counted),
            legacyExcluded = legacy,
            oldSkipped = oldSkipped,
            regrading = regrading,
            graded1m = res1,
            graded5m = res5,
            avgWinR = if (wins.isNotEmpty()) wins.average() else 0.0,
            avgLossR = if (losses.isNotEmpty()) losses.average() else 0.0,
            profitFactor = if (losses.sumOf { -it } > 1e-9) wins.sum() / losses.sumOf { -it }
                else if (wins.isNotEmpty()) Double.POSITIVE_INFINITY else 0.0,
            maxDrawdownR = maxDrawdown(trades.sortedWith(compareBy({ it.exitAt }, { it.e.recordedAt })).map { it.r }),
            avgRLow = rLow,
            avgRHigh = rHigh,
            profitableLow = pLow,
            profitableHigh = pHigh,
            unfundedTrades = trades.size - funded.size,
            accountReturnAllPct = accountAll * 100.0,
            fundedTrades = funded.size,
            unchecked = unchecked,
            appTrades = appTrades,
            claudeTrades = claudeTrades
        )
    }

    /** One decided trade, for the capital simulation and the drawdown. */
    private class Trade(val e: DayTradingLogEntry, val r: Double, val account: Double,
                        val notional: Double, val fillAt: Long, val exitAt: Long, val capped: Boolean = false)

    /**
     * THE TRADES AN ACCOUNT COULD ACTUALLY HAVE HELD AT ONCE (2026-09-24c, audit E8). Each is
     * sized as the app sizes it - at most a quarter of the portfolio - so a morning with eight
     * picks triggering together is two portfolios' worth of positions. Walked in fill order, a
     * trade whose cost would take the open positions past the whole portfolio is skipped: no
     * margin, no borrowed money. A position frees its cash only after the bar it exited in.
     */
    private fun fundable(trades: List<Trade>): List<Trade> {
        val open = ArrayList<Trade>()
        val out = ArrayList<Trade>()
        for (t in trades.sortedWith(compareBy({ it.fillAt }, { it.e.recordedAt }, { it.e.id }))) {
            open.removeAll { it.exitAt < t.fillAt }
            if (open.sumOf { it.notional } + t.notional <= 1.0 + 1e-9) { open.add(t); out.add(t) }
        }
        return out
    }

    /** Largest peak-to-trough fall of the cumulative R curve, as a positive number of R. */
    internal fun maxDrawdown(rs: List<Double>): Double {
        var peak = 0.0; var cum = 0.0; var dd = 0.0
        for (r in rs) { cum += r; peak = maxOf(peak, cum); dd = maxOf(dd, peak - cum) }
        return dd
    }

    /** Student-t 97.5% points, for a 95% interval on a small sample's mean. */
    private val T975 = listOf(1 to 12.71, 2 to 4.30, 3 to 3.18, 4 to 2.78, 5 to 2.57, 6 to 2.45, 7 to 2.36,
        8 to 2.31, 9 to 2.26, 10 to 2.23, 12 to 2.18, 15 to 2.13, 20 to 2.09, 25 to 2.06, 30 to 2.04,
        40 to 2.02, 60 to 2.00, 120 to 1.98)

    private fun tCrit(df: Int): Double = T975.lastOrNull { it.first <= df }?.second?.let { v ->
        if (df > 120) 1.96 else v } ?: 12.71

    /** 95% interval on the mean of [xs] (the expectancy per trade in R); (0, 0) under two values. */
    internal fun meanInterval(xs: List<Double>): Pair<Double, Double> {
        if (xs.size < 2) return 0.0 to 0.0
        val m = xs.average()
        val sd = kotlin.math.sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        val half = tCrit(xs.size - 1) * sd / kotlin.math.sqrt(xs.size.toDouble())
        return (m - half) to (m + half)
    }

    /** Wilson 95% interval on k successes in n, in percent - honest at small n, unlike +/-2 SE. */
    internal fun wilson(k: Int, n: Int): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 0.0
        val z = 1.96
        val p = k.toDouble() / n
        val d = 1 + z * z / n
        val c = (p + z * z / (2 * n)) / d
        val h = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
        return ((c - h).coerceAtLeast(0.0) * 100.0) to ((c + h).coerceAtMost(1.0) * 100.0)
    }

    /** The decided outcomes - a trade that actually happened and is over. */
    private val DECIDED = setOf(DayTradingOutcome.WIN, DayTradingOutcome.LOSS,
        DayTradingOutcome.CLOSED_PROFIT, DayTradingOutcome.CLOSED_LOSS)

    /** See [DayTradingStats.breakdown]. Net "profitable" is the same test the headline uses. */
    internal fun breakdown(entries: List<DayTradingLogEntry>): List<com.tj.portfolio.data.StatSlice> {
        val decided = entries.filter { it.outcome in DECIDED }
        fun netOf(e: DayTradingLogEntry): Pair<Double, Double> {
            val exit = e.outcomeExitPrice ?: when (e.outcome) {
                DayTradingOutcome.WIN -> e.target
                DayTradingOutcome.LOSS -> e.stop
                else -> e.entry
            }
            val fill = DayTradingGrader.Detail.parse(e.evalDetail)?.fill?.takeIf { it > 0.0 } ?: e.entry
            return Costs.entryFill(fill) to Costs.exitFill(e.outcome, exit)
        }
        fun profitable(e: DayTradingLogEntry): Boolean = netOf(e).let { (paid, got) -> got > paid }
        fun rOf(e: DayTradingLogEntry): Double {
            val risk = e.entry - e.stop
            return if (risk > 1e-9) netOf(e).let { (paid, got) -> (got - paid) / risk } else 0.0
        }
        fun slices(group: String, key: (DayTradingLogEntry) -> String, order: List<String>) =
            decided.groupBy(key).map { (label, rows) ->
                com.tj.portfolio.data.StatSlice(group, label, rows.size,
                    rows.count { it.outcome == DayTradingOutcome.WIN }, rows.count { profitable(it) },
                    rows.sumOf { rOf(it) })
            }.sortedBy { order.indexOf(it.label).let { i -> if (i < 0) order.size else i } }
        val who = listOf("The app's plans", "Claude's plans")
        val setups = listOf(ResearchScore.SETUP_BREAKOUT, ResearchScore.SETUP_PULLBACK,
            ResearchScore.SETUP_RECLAIM, "Claude's own setups")
        val times = listOf("First hour", "Midday", "Last two hours")
        val out = slices("Who planned it", {
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
        // WHICH ENGINE MADE IT (2026-09-24c) - only once the app's own plans come from more than
        // one engine version, i.e. once a tuning has been applied: then "did the change help" is
        // the question, and this is its answer.
        val appRows = decided.filter { it.source != DayTradingLogEntry.SOURCE_CLAUDE }
        val versions = appRows.map { engineLabel(it.engine) }.distinct()
        if (versions.size < 2) return out
        return out + appRows.groupBy { engineLabel(it.engine) }.map { (label, rows) ->
            com.tj.portfolio.data.StatSlice("Engine version (the app's plans)", label, rows.size,
                rows.count { it.outcome == DayTradingOutcome.WIN }, rows.count { profitable(it) },
                rows.sumOf { rOf(it) })
        }.sortedBy { it.label.substringAfterLast('v').toIntOrNull() ?: -1 }   // v2 before v10 (UI-16)
    }

    /** "v0" -> "Original engine", "v3" -> "Tuned engine v3"; older rows carry no label. */
    fun engineLabel(engine: String): String = when {
        engine.isBlank() || engine == "v0" -> "Original engine"
        else -> "Tuned engine $engine"
    }
}
