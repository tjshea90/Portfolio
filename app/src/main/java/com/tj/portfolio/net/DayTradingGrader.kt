package com.tj.portfolio.net

import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.net.DayTradingEval.IntradayBar
import org.json.JSONArray
import org.json.JSONObject

/**
 * WHAT A LOGGED PLAN WOULD REALLY HAVE DONE, TRADED EXACTLY AS THE CARD SAID (2026-09-24c).
 *
 * Tj: *"make sure the check day trading success rate is an accurate representation of the
 * profits if I were to trade using the day trading system in real life ... it doesn't track
 * anything illogical such as counting a success when I couldn't have traded it in real life,
 * counting a success based on a profitable movement that was realized before the app actually
 * recommended it ... or any other error or anomaly."*
 *
 * THE TRADE BEING GRADED. At the moment a plan was recorded ([Spec.recordedAt]) Tj places one
 * bracket order, exactly as the card reads: a BUY-STOP at the entry when the entry is above the
 * price (breakout, VWAP reclaim), a BUY-LIMIT at the entry when it is below (pullback), with a
 * protective SELL-STOP at the stop and a SELL-LIMIT at the target attached. It is a day order:
 *  - an entry that has not filled by the plan's own "too late to start" time is CANCELLED
 *    ([Spec.entryDeadlineSec]) - the card itself turns to "TOO LATE TO START TODAY" then;
 *  - whatever is open at the plan's "be flat by" time is SOLD AT MARKET ([Spec.flatSec]).
 *
 * HOW EACH ORDER FILLS - the rules that stop the grade flattering the system (audit E1-E4, E7 in
 * audits/2026-09-24c/DESIGN.md):
 *  - Nothing before the recommendation: only bars that START at or after [Spec.recordedAt]. On
 *    one-minute bars (preferred - see [DayTradingEval.fetchDaySeries]) that leaves under a minute
 *    unseen; the old grader lost up to five, which could hide a fill-and-stop-out and then credit
 *    a win on the next bar.
 *  - A buy-stop fills at the entry, or at the bar's OPEN when price was already through it (a gap,
 *    a halt, a plan whose price had run past its entry) - it becomes a market order.
 *  - A buy-limit fills at the entry only if price trades THROUGH it by a tick, or at the open when
 *    the bar opens below it (a better price, which a real limit gets).
 *  - The stop is a stop order: a touch triggers it; a bar that OPENS below it fills at that open.
 *  - The target is a resting limit: it needs a trade-through of one tick, and a lone bad print (a
 *    wick many times the day's normal bar that no neighbouring bar comes near) does not fill it.
 *  - Inside one bar the order of events is unknown: when a bar could be read either way it is read
 *    against the trade ([Graded.ambiguous] - the caller asks one-minute bars first).
 *  - Then [DayTradingEval.Costs]: entry slippage, stop slippage, a market exit at the flat time.
 *
 * WHAT IT ALSO MEASURES, for the tuning loop: the best and worst excursion after the fill (MFE /
 * MAE, in units of the plan's risk), where holding to the flat time with only the stop would have
 * ended, and a GRID of what other stop and target distances would have done on the very same bars
 * (net R of each variant's own risk) - the evidence Claude needs to move a stop or a target
 * without guessing.
 */
object DayTradingGrader {

    /** Bump when the rules above change: every row graded by an older version is re-graded. */
    const val VERSION = 2

    /**
     * The rules above in plain words - shown on the success card ("How are trades graded?") and
     * sent in the tuning prompt, so Tj and Claude read the same description of the same grader.
     */
    const val RULES_TEXT =
    "Each recommendation is graded as one real order placed the moment the card showed it: a " +
        "buy-stop at the buy price when that is above the price (it buys only if the stock rises " +
        "to it), a buy-limit when it is below (it buys only if the stock drops to it), with the " +
        "stop and the target attached.\n\n" +
        "- Only prices from after that moment count - a move that happened before the " +
        "recommendation never does. One-minute price bars are used whenever they still exist " +
        "(about 30 days), five-minute bars after that.\n" +
        "- A buy that could only have happened above the buy price (the stock jumped past it) is " +
        "filled at that worse price. A stop that the price gapped through fills at the gap.\n" +
        "- The target only counts when the price trades past it by at least a cent, and a single " +
        "stray price far from every trade around it is ignored.\n" +
        "- When a single bar reached both the stop and the target, it is read as the stop.\n" +
        "- If the price jumped straight past both the buy price and the target, the order is " +
        "counted as bought and sold at once at that price - a small loss, never a win.\n" +
        "- An order that has not filled by the plan's own \"too late to start\" time is cancelled " +
        "(no trade). Anything still open at the \"be flat by\" time is sold there.\n" +
        "- Costs are taken off every trade, and the portfolio figure skips any trade the " +
        "portfolio could not have paid for at the time (no margin).\n" +
        "- Recommendations the card said to skip (already past the target, under the stop, or " +
        "already through the buy price) are never recorded."

    /** US equities above $1 trade in cents; below it in hundredths of a cent. */
    fun tick(price: Double): Double = if (price >= 1.0) 0.01 else 0.0001

    /** Stop distances the grid tries, as multiples of the plan's own risk (1.0 = the plan's stop). */
    val GRID_STOPS = listOf(0.5, 0.75, 1.0, 1.5, 2.0)

    /** Target distances the grid tries, in R above the entry; [GRID_PLAN] = the plan's own target, [GRID_NONE] = no target, hold to the flat time. */
    val GRID_TARGETS = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, GRID_PLAN, GRID_NONE)
    const val GRID_PLAN = -1.0
    const val GRID_NONE = -2.0

    data class Spec(
        val entry: Double,
        val stop: Double,
        val target: Double,
        /** Buy-stop above the price (true) or buy-limit below it (false) - [DayTradingEval.entryRises]. */
        val rises: Boolean,
        val recordedAt: Long,
        /** Bars starting at or after this (epoch s) may not fill the entry any more. */
        val entryDeadlineSec: Long = Long.MAX_VALUE,
        /** Bars starting at or after this (epoch s) are not traded; the position closes at the last one's close. */
        val flatSec: Long = Long.MAX_VALUE
    ) {
        val risk: Double get() = entry - stop
    }

    /** Where a position ended. [idx] is the bar it ended in (-1 when it has not ended). */
    class Exit(val outcome: String, val price: Double?, val idx: Int, val ambiguous: Boolean, val reason: String)

    class Graded(
        val outcome: String,
        /** The exit price (before costs), or null while undecided. */
        val exitPrice: Double?,
        val ambiguous: Boolean,
        val detail: Detail?
    )

    /** The grader's working, stored as `eval_detail` JSON. */
    data class Detail(
        /** 1 or 5 - the bar size it was decided on. */
        val res: Int,
        /** The entry's real fill (before slippage), and when (epoch s, the bar it filled in). */
        val fill: Double = 0.0,
        val fillAt: Long = 0L,
        val exitAt: Long = 0L,
        /** "target", "stop", "gap-stop", "gap-target" (filled above the target, sold at once), "flat", or "unfilled". */
        val why: String = "",
        /** Best / worst excursion from the fill to the exit, in R of the plan's risk (gross). */
        val mfeR: Double = 0.0,
        val maeR: Double = 0.0,
        /** Best excursion from the fill to the flat time, whatever the exit - "how far could it have run". */
        val mfeFlatR: Double = 0.0,
        /** Gross R had the target been left off: the stop, or the flat-time price. */
        val holdR: Double = 0.0,
        /** Net R per [GRID_STOPS] x [GRID_TARGETS] cell - each variant measured in its OWN risk. */
        val grid: List<List<Double>> = emptyList(),
        val ambiguous: Boolean = false,
        /**
         * The bars did not reach the flat time when this was graded (a stop or a target decided it
         * mid-session, or a settled day's series stops short): the verdict is final, but
         * [mfeFlatR], [holdR] and [grid] - which need the rest of the day - are left out rather
         * than measured to "whatever time it was then" (audit DA-1). A mid-session one is graded
         * again once the session settles ([needsSettledRegrade]).
         */
        val partial: Boolean = false
    ) {
        fun toJson(): String = JSONObject().apply {
            put("res", res)
            if (fill > 0) { put("fill", r4(fill)); put("fillAt", fillAt) }
            if (exitAt > 0) put("exitAt", exitAt)
            if (why.isNotBlank()) put("why", why)
            if (fill > 0) {
                put("mfe", r2(mfeR)); put("mae", r2(maeR))
                if (!partial) { put("mfeFlat", r2(mfeFlatR)); put("hold", r2(holdR)) }
            }
            if (grid.isNotEmpty() && !partial) put("grid", JSONArray().apply { grid.forEach { row -> put(JSONArray(row.map { r3(it) })) } })
            if (ambiguous) put("amb", true)
            if (partial) put(PARTIAL_KEY, true)
        }.toString()

        companion object {
            fun parse(json: String?): Detail? {
                if (json.isNullOrBlank()) return null
                return runCatching {
                    val o = JSONObject(json)
                    Detail(
                        res = o.optInt("res", 5),
                        fill = o.optDouble("fill", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        fillAt = o.optLong("fillAt", 0L),
                        exitAt = o.optLong("exitAt", 0L),
                        why = o.optString("why", ""),
                        mfeR = o.optDouble("mfe", 0.0),
                        maeR = o.optDouble("mae", 0.0),
                        mfeFlatR = o.optDouble("mfeFlat", 0.0),
                        holdR = o.optDouble("hold", 0.0),
                        grid = o.optJSONArray("grid")?.let { g ->
                            (0 until g.length()).map { i ->
                                val row = g.optJSONArray(i) ?: JSONArray()
                                (0 until row.length()).map { row.optDouble(it, 0.0) }
                            }
                        }.orEmpty(),
                        ambiguous = o.optBoolean("amb", false),
                        partial = o.optBoolean(PARTIAL_KEY, false)
                    )
                }.getOrNull()
            }

            private const val PARTIAL_KEY = "partial"

            /** Cheap test on the stored JSON - no parse - for the re-grade selection over the whole log. */
            fun isPartial(json: String?): Boolean = json != null && json.contains("\"$PARTIAL_KEY\":true")
        }
    }

    private fun r2(v: Double) = if (v.isFinite()) Math.round(v * 100.0) / 100.0 else 0.0
    private fun r3(v: Double) = if (v.isFinite()) Math.round(v * 1000.0) / 1000.0 else 0.0
    private fun r4(v: Double) = if (v.isFinite()) Math.round(v * 10000.0) / 10000.0 else 0.0

    /** Median bar range of the day - the ruler a "bad print" is measured against. */
    internal fun medianRange(bars: List<IntradayBar>): Double {
        if (bars.isEmpty()) return 0.0
        val r = bars.map { it.high - it.low }.sorted()
        return r[r.size / 2]
    }

    /** A wick this many times the day's median bar range, and this share of the price, before it can be a bad print. */
    private const val SPIKE_MEDIANS = 6.0
    private const val SPIKE_PRICE_FRACTION = 0.015

    /**
     * Is bar [i]'s high a lone bad print for a [level] it seems to reach? The wick above the bar's
     * own body is more than six times the day's median bar range AND more than 1.5% of the price,
     * and neither neighbour reaches [level]. Real, tradeable wicks are left alone; the kind of
     * single erroneous tick that data feeds do occasionally carry is not credited as a fill.
     */
    internal fun isSpikeHigh(bars: List<IntradayBar>, i: Int, level: Double, median: Double): Boolean {
        val b = bars[i]
        val body = if (b.open.isFinite()) maxOf(b.open, b.close) else b.close
        val wick = b.high - body
        if (median <= 0.0 || wick <= SPIKE_MEDIANS * median || wick <= SPIKE_PRICE_FRACTION * b.high) return false
        val prevReaches = i > 0 && bars[i - 1].high >= level
        val nextReaches = i + 1 < bars.size && bars[i + 1].high >= level
        return !prevReaches && !nextReaches
    }

    /**
     * Runs one open position forward from its fill. Shared by the verdict AND every grid cell, so
     * the grid's (plan stop, plan target) cell is the verdict by construction.
     *
     * [target] null = no target (hold to the end of [bars]). [complete] = the bars run all the way
     * to the flat time, so a position still open at the last one is closed there, not left pending.
     */
    internal fun runPosition(
        bars: List<IntradayBar>,
        fillIdx: Int,
        fill: Double,
        rises: Boolean,
        stop: Double,
        target: Double?,
        tick: Double,
        complete: Boolean,
        spikeFilter: Boolean,
        median: Double
    ): Exit {
        // A limit that filled at an open already under the stop is stopped out at once.
        if (fill <= stop) return Exit(DayTradingOutcome.LOSS, fill, fillIdx, false, "gap-stop")
        // AND A BUY-STOP THAT FILLED AT AN OPEN ALREADY ABOVE THE TARGET (a jump straight through
        // both) is sold at once too: the attached sell-limit is below the market, so it fills at
        // the market - about the fill price - not at the target. Crediting that as a target WIN
        // counted a trade that lost its costs as a success.
        if (target != null && fill >= target)
            return Exit(DayTradingOutcome.CLOSED_LOSS, fill, fillIdx, false, "gap-target")
        var deferred = false
        val need = target?.let { it + tick }
        for (i in fillIdx until bars.size) {
            val b = bars[i]
            val first = i == fillIdx
            // GAPPED THROUGH THE STOP between bars: the stop order fills at this bar's open.
            if (!first && b.open.isFinite() && b.open <= stop)
                return Exit(DayTradingOutcome.LOSS, b.open, i, false, "gap-stop")
            val reachesTarget = need != null && b.high >= need &&
                !(spikeFilter && isSpikeHigh(bars, i, need, median))
            if (b.low <= stop) {
                // Stop first, always, when the bar could be read either way (see the header).
                return Exit(DayTradingOutcome.LOSS, stop, i,
                    ambiguous = deferred || (first && rises) || reachesTarget, reason = "stop")
            }
            // In a buy-limit's own fill bar the high may have printed BEFORE the fill - a target
            // there is deferred to the next bar, never credited from that bar alone.
            if (reachesTarget && (rises || !first)) return Exit(DayTradingOutcome.WIN, target, i, false, "target")
            if (reachesTarget) deferred = true
        }
        if (!complete) return Exit(DayTradingOutcome.PENDING, null, -1, deferred, "")
        val last = bars.last()
        return Exit(
            if (last.close > fill) DayTradingOutcome.CLOSED_PROFIT else DayTradingOutcome.CLOSED_LOSS,
            last.close, bars.size - 1, deferred, "flat"
        )
    }

    /**
     * A settled day's series must reach this close to the flat time before it is trusted as the
     * whole day (audit DA-17): a reply that stops at 13:40 for a liquid stock is a truncated one,
     * and closing a trade "at the flat time" at the 13:40 price, or calling an entry that would
     * have filled at 14:10 "never filled", would be deciding from bars that are not there.
     */
    internal const val TRUNCATED_SEC = 15 * 60L

    /**
     * Bars whose open the feed did not give (or gave outside the bar's own range) take the
     * previous bar's close, kept inside their own range, as their open (audit DA-8). One-minute
     * bars are continuous, so that is where trading in the bar began to within a tick or two -
     * and, unlike the old "no open, no gap" reading, it never prices a fill or a stop exit at a
     * level the bar did not trade at. The day's first bar has no previous one and keeps NaN;
     * [grade] prices it against the trade.
     */
    internal fun withKnownOpens(bars: List<IntradayBar>): List<IntradayBar> {
        if (bars.none { !it.open.isFinite() }) return bars
        return bars.mapIndexed { i, b ->
            if (b.open.isFinite() || i == 0) b
            else b.copy(open = bars[i - 1].close.coerceIn(b.low, b.high))
        }
    }

    /**
     * The mirror of [isSpikeHigh] for a BUY-LIMIT's fill (audit DA-7): bar [i]'s low is a lone bad
     * print under [level] - a wick below the body over six median ranges and 1.5% of the price,
     * with neither neighbour reaching [level]. The same erroneous tick the target is protected
     * from must not open a position at a limit nothing traded at, and then have the rest of the
     * day credited to it. (A spike still counts against a STOP - that is the conservative side.)
     */
    internal fun isSpikeLow(bars: List<IntradayBar>, i: Int, level: Double, median: Double): Boolean {
        val b = bars[i]
        val body = if (b.open.isFinite()) minOf(b.open, b.close) else b.close
        val wick = body - b.low
        if (median <= 0.0 || wick <= SPIKE_MEDIANS * median || wick <= SPIKE_PRICE_FRACTION * body) return false
        val prevReaches = i > 0 && bars[i - 1].low <= level
        val nextReaches = i + 1 < bars.size && bars[i + 1].low <= level
        return !prevReaches && !nextReaches
    }

    /**
     * What one grid variant, or the verdict itself, did to the ACCOUNT: the fraction of equity
     * gained or lost, in percent, with the position sized the way the card sizes it
     * ([ResearchScore.dayTradeSharesPerEquity] - 1% risk, at most 25% of the account in one
     * stock) against [stop]. Audit DA-6: that 25% cap is what sizes most day trades, and under it
     * a tighter stop raises the R of a trade without making the account a cent more, so the
     * grid is measured in what the account made, not in R.
     */
    fun accountPct(entry: Double, stop: Double, paid: Double, got: Double): Double =
        ResearchScore.dayTradeSharesPerEquity(entry, stop) * (got - paid) * 100.0

    /**
     * The full grade of one plan against one day's bars.
     *
     * [decidedThroughSec]: bars are known to be complete up to here (epoch s) - `Long.MAX_VALUE`
     * for a settled session, 0 for today's (only a stop or a target can decide a trade then). It
     * decides when "no fill yet" becomes "expired" and "still open" becomes "closed at the flat
     * time". [res] is 1 or 5 (minutes per bar).
     *
     * A PENDING answer for a settled session means its series was too short to decide from
     * ([TRUNCATED_SEC]) - the caller treats that like a failed fetch, never as a verdict.
     */
    fun grade(
        spec: Spec,
        bars: List<IntradayBar>,
        decidedThroughSec: Long,
        res: Int,
        spikeFilter: Boolean = true,
        withGrid: Boolean = true,
        /** The trade-through a limit needs; 0 only for [DayTradingEval.evaluateResolved]'s touch rules. */
        tk: Double = tick(spec.entry),
        /** Fill a missing open from the previous close ([withKnownOpens]); off only for the touch rules. */
        realOpens: Boolean = true
    ): Graded {
        val barSec = res.coerceAtLeast(1) * 60L
        // A BAR COUNTS ONLY WHEN IT ENDS BY THE CUT-OFF (audit DA-13): on five-minute bars a
        // tuned flat time of 15:47 used to keep the 15:45 bar - trading, and holding, three
        // minutes past the time the card said to be out. The same for the entry deadline below.
        val sorted = bars.filter { spec.flatSec == Long.MAX_VALUE || it.t + barSec <= spec.flatSec }.sortedBy { it.t }
        val day = if (realOpens) withKnownOpens(sorted) else sorted
        val median = medianRange(day)
        val start = day.indexOfFirst { it.t * 1000L >= spec.recordedAt }
        // HOW FAR THE BARS REALLY GO. A settled day is decided through its flat time only when
        // its last bar gets there (DA-17); otherwise only as far as the data does.
        val through = if (decidedThroughSec == Long.MAX_VALUE && spec.flatSec != Long.MAX_VALUE) {
            val last = day.lastOrNull()?.t ?: 0L
            if (last >= spec.flatSec - TRUNCATED_SEC) Long.MAX_VALUE else last + barSec
        } else decidedThroughSec
        val complete = through >= spec.flatSec
        val entryWindowOver = through >= spec.entryDeadlineSec

        // ---- the entry
        var fillIdx = -1
        var fill = 0.0
        var fillAmbiguous = false
        if (start >= 0) for (i in start until day.size) {
            val b = day[i]
            if (spec.entryDeadlineSec != Long.MAX_VALUE && b.t + barSec > spec.entryDeadlineSec) break
            val openKnown = b.open.isFinite()
            if (spec.rises) {
                if (b.high >= spec.entry) {
                    fillIdx = i
                    fill = when {
                        openKnown && b.open >= spec.entry -> b.open
                        // The whole bar traded above the trigger and where it began is unknown
                        // (the day's first bar): read against the trade - its high.
                        realOpens && !openKnown && b.low > spec.entry -> { fillAmbiguous = true; b.high }
                        else -> spec.entry
                    }
                    break
                }
            } else {
                val opensBelow = openKnown && b.open < spec.entry
                val tradedThrough = b.low <= spec.entry - tk &&
                    !(spikeFilter && isSpikeLow(day, i, spec.entry - tk, median))
                if (tradedThrough || opensBelow) {
                    fillIdx = i
                    fill = when {
                        opensBelow -> b.open
                        realOpens && !openKnown && b.high < spec.entry -> { fillAmbiguous = true; b.high }
                        else -> spec.entry
                    }
                    break
                }
            }
        }
        if (fillIdx < 0) {
            // Unfilled until the plan's own cut-off (or the end of the session): the order is
            // cancelled, and no trade happened - not a win, not a loss.
            if (!(entryWindowOver || complete)) return Graded(DayTradingOutcome.PENDING, null, false, null)
            return Graded(DayTradingOutcome.NO_ENTRY, null, false, Detail(res = res, why = "unfilled"))
        }

        // ---- the exit
        val exit = runPosition(day, fillIdx, fill, spec.rises, spec.stop, spec.target, tk, complete, spikeFilter, median)
        if (exit.outcome == DayTradingOutcome.PENDING)
            return Graded(DayTradingOutcome.PENDING, null, exit.ambiguous || fillAmbiguous, null)
        val ambiguous = exit.ambiguous || fillAmbiguous

        val risk = spec.risk
        fun inR(v: Double) = if (risk > 1e-9) v / risk else 0.0
        // Excursions. A buy-stop's own fill bar: its low may predate the fill (so it counts only
        // as adverse, which is the conservative side) and its high follows it. A buy-limit's fill
        // bar: its high may predate the fill, so it is left out of the favourable side.
        fun mfeTo(end: Int): Double {
            var best = 0.0
            for (i in fillIdx..end) {
                if (!spec.rises && i == fillIdx) continue
                best = maxOf(best, day[i].high - fill)
            }
            return best
        }
        var worst = 0.0
        for (i in fillIdx..exit.idx) worst = maxOf(worst, fill - day[i].low)

        // THE REST OF THE DAY'S MEASURES NEED THE REST OF THE DAY (audit DA-1). Mid-session, or
        // on a series that stops short, the verdict stands but "hold to the flat time", "how far
        // it ran" and the grid are left out - measured to the last bar they would have claimed
        // the stock stopped moving at whatever time the check happened to run.
        if (!complete) {
            return Graded(exit.outcome, exit.price, ambiguous, Detail(
                res = res, fill = fill, fillAt = day[fillIdx].t, exitAt = day[exit.idx].t, why = exit.reason,
                mfeR = inR(mfeTo(exit.idx)), maeR = inR(worst), ambiguous = ambiguous, partial = true
            ))
        }
        val hold = runPosition(day, fillIdx, fill, spec.rises, spec.stop, null, tk, true, spikeFilter, median)
        val paid = DayTradingEval.Costs.entryFill(fill)
        val grid = if (!withGrid || risk <= 1e-9) emptyList() else GRID_STOPS.map { s ->
            val stopV = spec.entry - s * risk
            GRID_TARGETS.map { t ->
                val tgt = when (t) {
                    GRID_PLAN -> spec.target
                    GRID_NONE -> null
                    else -> spec.entry + t * risk
                }
                val e = runPosition(day, fillIdx, fill, spec.rises, stopV, tgt, tk, true, spikeFilter, median)
                accountPct(spec.entry, stopV, paid, DayTradingEval.Costs.exitFill(e.outcome, e.price ?: fill))
            }
        }
        val detail = Detail(
            res = res,
            fill = fill,
            fillAt = day[fillIdx].t,
            exitAt = day[exit.idx].t,
            why = exit.reason,
            mfeR = inR(mfeTo(exit.idx)),
            maeR = inR(worst),
            mfeFlatR = inR(mfeTo(day.size - 1)),
            holdR = inR((hold.price ?: fill) - fill),
            grid = grid,
            ambiguous = ambiguous
        )
        return Graded(exit.outcome, exit.price, ambiguous, detail)
    }

    /**
     * Should a decided row be graded again? Once, after its session settles, when it was decided
     * mid-session with a [Detail.partial] working (audit DA-1) - its verdict cannot change, but its
     * grid, hold and run measures can now be taken over the whole day. A settled re-grade that is
     * still partial (the day's series stops short) is not asked again.
     */
    fun needsSettledRegrade(evalDetail: String?, evaluatedAt: Long?, tradingDay: String, now: Long): Boolean {
        if (!Detail.isPartial(evalDetail)) return false
        val bounds = DayTradingEval.sessionBoundsMs(tradingDay) ?: return false
        val settledAt = bounds.second + DayTradingEval.SETTLE_GRACE_MS
        return now >= settledAt && (evaluatedAt ?: 0L) < settledAt
    }
}
