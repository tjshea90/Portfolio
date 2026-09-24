package com.tj.portfolio.data

/**
 * One recommendation, captured once, exactly as `Db.day_trading_log` holds it - see
 * `Db.createDayTradingLog`'s own header for why the fields below this comment can never
 * change once written, and [DayTradingOutcome] for why [outcome] is the one exception.
 */
data class DayTradingLogEntry(
    val id: Long,
    val symbol: String,
    /** `MarketClock.dayKey` - e.g. "20260916". Which trading session this belongs to. */
    val tradingDay: String,
    val recordedAt: Long,
    val setup: String,
    val entry: Double,
    val stop: Double,
    val target: Double,
    /** The quote price at the moment this plan was made - what "entry above/below the
     *  current price" is measured against when working out which direction triggers it. */
    val priceAtRecommendation: Double,
    /** [SOURCE_APP] or [SOURCE_CLAUDE]. */
    val source: String,
    /** Null until `PortfolioViewModel.evaluateDayTradingLog` has resolved it once. */
    val outcome: String? = null,
    val outcomeExitPrice: Double? = null,
    val outcomeEvaluatedAt: Long? = null,
    /** Which engine made the plan - "v0" is the original, "claude" a Claude plan, "" an older row (2026-09-24c). */
    val engine: String = "",
    /** The conditions the plan was made under, JSON ([com.tj.portfolio.net.DayTradingFeatures]); "" on older rows. */
    val features: String = "",
    /** Which grader decided [outcome] - 0 = the pre-2026-09-24c rules ([com.tj.portfolio.net.DayTradingGrader.VERSION]). */
    val evalVersion: Int = 0,
    /** The grader's working, JSON ([com.tj.portfolio.net.DayTradingGrader.Detail]); "" when none. */
    val evalDetail: String = ""
) {
    companion object {
        const val SOURCE_APP = "APP"
        const val SOURCE_CLAUDE = "CLAUDE"
        /** [engine] for a Claude plan - no engine version made its levels. */
        const val ENGINE_CLAUDE = "claude"
    }
}

/**
 * How one recorded recommendation actually turned out, evaluated ONLY from real intraday
 * prices AFTER [DayTradingLogEntry.recordedAt] - see `net/DayTradingEval.kt`'s `evaluate()`
 * for the sequencing rules this encodes.
 *
 * [PENDING] and [DATA_UNAVAILABLE] are the only two states `evaluateDayTradingLog` ever
 * re-resolves on a later pass - every other value describes a trading day that has already
 * closed, which cannot change no matter how many times it is looked at again.
 */
object DayTradingOutcome {
    /** The trading day is today and still in progress - try again later. */
    const val PENDING = "PENDING"

    /** The session closed and price never reached [DayTradingLogEntry.entry] - no trade. */
    const val NO_ENTRY = "NO_ENTRY"

    /** Entry triggered, and [DayTradingLogEntry.target] was reached before the stop. */
    const val WIN = "WIN"

    /** Entry triggered, and [DayTradingLogEntry.stop] was reached before the target (or both
     *  were reachable in the same 5-minute bar - see `evaluate()`'s note on why that is read
     *  as the stop, not the target). */
    const val LOSS = "LOSS"

    /** Entry triggered; the session closed with neither target nor stop hit, and the last
     *  price of the day was above entry - a day trade is flat by the bell either way. */
    const val CLOSED_PROFIT = "CLOSED_PROFIT"

    /** Same as [CLOSED_PROFIT], but the session's last price was at or below entry. */
    const val CLOSED_LOSS = "CLOSED_LOSS"

    /** Real intraday history for this symbol and day could not be read - Yahoo's minute-level
     *  retention window is limited, and a request can also just fail. Retried on request. */
    const val DATA_UNAVAILABLE = "DATA_UNAVAILABLE"

    /** A resolved value - the trading day is closed and the answer will never change. */
    fun isFinal(outcome: String?): Boolean =
        outcome != null && outcome != PENDING && outcome != DATA_UNAVAILABLE
}

/**
 * The whole point of the log, answered: is this section's advice actually profitable? See
 * `net/DayTradingEval.stats` for how this is computed - a plain reading of every DECIDED
 * (entry triggered, outcome final) recommendation, nothing else.
 *
 * [avgReturnPct] is Tj's own second question, in his own words: *"how much percent up or down
 * my portfolio would be if I bought and sold stocks only using the app day trading system."*
 * It is the EQUAL-WEIGHTED AVERAGE of every decided trade's own percentage return (a same-
 * dollar-amount trade per pick, no compounding across trades or days) - the standard, honest
 * way to answer "if I traded this system" without inventing an account size Tj never gave.
 * That assumption is real and is spelled out on screen next to the number, not left implicit.
 *
 * ---- WHAT 2026-09-18 ADDED, AND WHY THE CARD WAS UNDER-ANSWERING HIM
 *
 * An AVERAGE PER TRADE was the only figure on the card, under a row labelled "If you only
 * traded this system". Those are two different questions and the gap between them is not
 * small: sixty trades averaging a genuine +0.5% is not "+0.5%", it is roughly +30% of the
 * money staked. A reader glancing at that row got a number an order of magnitude away from
 * what he asked for. [totalReturnPct] and [accountReturnPct] are the cumulative readings; the
 * average stays, as the per-trade statistic it always was.
 *
 * AND EVERY EXIT WAS ASSUMED TO FILL PERFECTLY. [com.tj.portfolio.net.DayTradingEval.evaluate]
 * exits at exactly `stop` and exactly `target`, because those are the only prices a 5-minute
 * bar can prove were reached. A real stop is a MARKET order once touched, and it is touched
 * precisely when the tape is fast; a real buy-stop entry fills at or above its trigger. Every
 * one of those errors runs the same way, so the measured result was systematically optimistic -
 * the one direction a "did this actually work" number must never be wrong in. The `net` figures
 * below are the same trades after [com.tj.portfolio.net.DayTradingEval.Costs], and both the
 * gross and the net are shown so the size of that assumption is visible rather than buried.
 */
data class DayTradingStats(
    val totalRecommendations: Int = 0,
    /** Recommendations whose entry actually triggered AND whose outcome is now final -
     *  [targetHit] + [stopHit] + [closedProfit] + [closedLoss]. The denominator both rates
     *  below are measured against - a recommendation nobody could have traded (entry never
     *  triggered) is not a win or a loss, so it is excluded rather than diluting either rate. */
    val entriesTriggered: Int = 0,
    val targetHit: Int = 0,
    val stopHit: Int = 0,
    val closedProfit: Int = 0,
    val closedLoss: Int = 0,
    val noEntry: Int = 0,
    val pending: Int = 0,
    val dataUnavailable: Int = 0,
    /** % of [entriesTriggered] where TARGET was reached before the stop - the strict reading
     *  of "did the plan work exactly as stated." */
    val targetHitRate: Double = 0.0,
    /** % of [entriesTriggered] that made money AFTER modelled costs - a target hit, or a trade
     *  still up by more than its costs when the session ended. Net, like the account figure. */
    val profitableRate: Double = 0.0,
    /** The count behind [profitableRate]. */
    val profitableCount: Int = 0,
    val avgReturnPct: Double = 0.0,

    // ---- CUMULATIVE, AND AFTER MODELLED COSTS (2026-09-18). See this class's header.

    /**
     * Every decided trade's GROSS return added up - the same fixed equal stake per trade, no
     * compounding. "Put $1,000 into each of these N trades and you would be up this much of one
     * $1,000 stake." Not a compounded account curve, which would need an account size and a
     * one-trade-at-a-time assumption neither of which is true here.
     */
    val totalReturnPct: Double = 0.0,
    /** [avgReturnPct] after [com.tj.portfolio.net.DayTradingEval.Costs]. */
    val netAvgReturnPct: Double = 0.0,
    /** [totalReturnPct] after [com.tj.portfolio.net.DayTradingEval.Costs]. */
    val netTotalReturnPct: Double = 0.0,
    /**
     * Sum of every decided trade's NET R-MULTIPLE - its result measured in units of the risk it
     * actually put up, `(exit - entry) / (entry - stop)`.
     *
     * THIS IS THE ONE FIGURE THAT ANSWERS TJ'S QUESTION IN THE APP'S OWN TERMS, which is why it
     * is here rather than left as a trader's nicety. [com.tj.portfolio.net.ResearchScore
     * .positionSize] sizes every plan to risk a fixed 1% of equity, so a system that returned
     * +8R over a stretch moved the ACCOUNT about +8%, whatever each individual stock cost. A
     * percentage-of-stake figure cannot say that, because a $2 stock with a 3% stop and a $200
     * stock with a 0.4% stop are sized completely differently by that rule.
     */
    val totalR: Double = 0.0,
    /** [totalR] divided by [entriesTriggered] - expectancy per trade, in R. */
    val avgR: Double = 0.0,
    /**
     * Every decided trade's net result as a share of the account, each sized exactly as
     * `ResearchScore.positionSize` sizes it: 1% of equity at risk, but never more than 25% of
     * equity in one position (full-tests audit 2026-09-22, D-H2). This used to be [totalR] x 1%,
     * which ignores the cap - and a day trade's stop is usually so close to the entry that the
     * cap, not the 1%, is what sets the size, so that figure ran several times too high.
     * Whole-share rounding is the only thing left out. Not compounded.
     */
    val accountReturnPct: Double = 0.0,
    /** How many of the decided trades the 25% position cap sized below the full 1% risk. */
    val cappedTrades: Int = 0,
    /** Distinct trading days the log covers - the context an average per trade needs. */
    val sessions: Int = 0,
    val evaluatedAt: Long = 0L,
    /**
     * WHAT WORKED, SPLIT THREE WAYS (2026-09-24b, Day Trading ideas 1-2): by who planned it
     * (the app or Claude), by setup, and by the time of day it was recommended - the breakdown
     * trading journals lead with. Only groups with at least one decided trade.
     */
    val breakdown: List<StatSlice> = emptyList(),

    // ---- 2026-09-24c: WHAT THE NUMBERS REST ON, AND THE ACCOUNT THAT COULD REALLY HOLD THEM.

    /** Rows graded by an older, less strict grader whose bars are gone - kept, not counted. */
    val legacyExcluded: Int = 0,
    /** Rows graded by an older grader whose bars still exist - queued for re-grading, not yet counted. */
    val regrading: Int = 0,
    /**
     * Rows logged before these rules whose own price shows the card said NOT to take them (past the
     * target, under the stop, or no price recorded) - never graded, never counted (audit DA-19).
     */
    val oldSkipped: Int = 0,
    /** Decided trades graded on one-minute / five-minute bars. */
    val graded1m: Int = 0,
    val graded5m: Int = 0,
    /** Average net R of the winning / losing trades (a loss is negative). */
    val avgWinR: Double = 0.0,
    val avgLossR: Double = 0.0,
    /** Gross won R over gross lost R; infinite with no losing trade yet, 0 with no trades. */
    val profitFactor: Double = 0.0,
    /** Worst peak-to-trough run of the cumulative net R, in exit order. */
    val maxDrawdownR: Double = 0.0,
    /** 95% interval on [avgR] - where the true expectancy plausibly sits. */
    val avgRLow: Double = 0.0,
    val avgRHigh: Double = 0.0,
    /** Wilson 95% interval on [profitableRate], in percent. */
    val profitableLow: Double = 0.0,
    val profitableHigh: Double = 0.0,
    /**
     * Decided trades the portfolio could NOT have funded when they filled - already 100% invested
     * in other picks at 25% each - and so left out of [accountReturnPct] (audit E8).
     */
    val unfundedTrades: Int = 0,
    /** [accountReturnPct] as if every trade could have been funded - shown only for comparison. */
    val accountReturnAllPct: Double = 0.0,
    /** The decided trades [accountReturnPct] is made of - the fundable ones (UI-2). */
    val fundedTrades: Int = 0,
    /** Recommendations from a finished session that have not been graded yet (UI-26). */
    val unchecked: Int = 0,
    /** Decided trades from the app's own plans / from Claude's (UI-20). */
    val appTrades: Int = 0,
    val claudeTrades: Int = 0
) {
    /** How much weight the figures can bear, in words - see [SAMPLE_TIERS]. */
    val sampleNote: String get() = sampleNote(entriesTriggered)

    /**
     * "positive", "negative" or "" (not yet distinguishable from zero) - the expectancy's 95% interval,
     * and NEVER below the first sample tier (UI-1): under 20 trades the card says "too few to judge",
     * and a confident verdict beside it would contradict it - and the t-interval is not reliable on a
     * day-trade R distribution that small anyway.
     */
    val edgeVerdict: String get() = when {
        entriesTriggered < SAMPLE_TIERS[0].first -> ""
        avgRLow > 0.0 -> "positive"
        avgRHigh < 0.0 -> "negative"
        else -> ""
    }

    companion object {
        /** Below each count, the wording the card and the prompt use for the sample. */
        val SAMPLE_TIERS = listOf(
            20 to "Too few trades to judge - results this small can easily be luck either way",
            50 to "An early read - treat it with caution",
            100 to "Moderate evidence"
        )

        fun sampleNote(n: Int): String =
            SAMPLE_TIERS.firstOrNull { n < it.first }?.second ?: "A solid sample"
    }
}

/** One slice of [DayTradingStats.breakdown]: [decided] trades, how many hit target / made money net. */
data class StatSlice(
    val group: String,
    val label: String,
    val decided: Int,
    val targetHits: Int,
    val profitable: Int,
    /** The slice's summed net R (2026-09-24c) - expectancy, not just a hit rate. */
    val sumR: Double = 0.0
) {
    val avgR: Double get() = if (decided > 0) sumR / decided else 0.0
    val targetHitRate: Double get() = if (decided > 0) targetHits * 100.0 / decided else 0.0
    val profitableRate: Double get() = if (decided > 0) profitable * 100.0 / decided else 0.0
}
