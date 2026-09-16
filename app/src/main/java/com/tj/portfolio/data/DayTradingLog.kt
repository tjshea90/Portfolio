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
    val outcomeEvaluatedAt: Long? = null
) {
    companion object {
        const val SOURCE_APP = "APP"
        const val SOURCE_CLAUDE = "CLAUDE"
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
