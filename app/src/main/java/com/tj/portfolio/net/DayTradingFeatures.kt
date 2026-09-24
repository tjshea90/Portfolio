package com.tj.portfolio.net

import com.tj.portfolio.data.ResearchRow
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * WHAT A PLAN WAS MADE UNDER, WRITTEN DOWN THE MOMENT IT IS LOGGED (2026-09-24c).
 *
 * The tuning loop's whole premise is "learn which methods actually worked" - and a grade on its
 * own cannot say WHY a trade worked. Every recommendation therefore carries the conditions it was
 * made in, as a small JSON object in `day_trading_log.features`: the setup and the level it was
 * built on, how far the trigger sat from the price and how wide the stop was (in the stock's own
 * 5-minute ATRs), the reward:risk, how extended the stock was over VWAP and through its normal
 * day, the score and its raw ingredients, and the time of day. These are exactly the inputs the
 * engine's tunable parameters act on, so Claude can see, for example, that pullbacks with a
 * trigger under 0.5 ATR away worked and ones over 2 ATRs never filled.
 *
 * AND THE TWO TIMES THE GRADE DEPENDS ON: the entry's cancel-by time and the flat-by time, computed
 * from the ENGINE THAT MADE THE PLAN - so a later tuning (or a revert) can never re-grade an old
 * plan against rules it was not made under.
 */
object DayTradingFeatures {

    private val ET: ZoneId = ZoneId.of("America/New_York")

    private fun at(tradingDay: String, minuteOfDay: Int): Long? = runCatching {
        LocalDate.of(tradingDay.substring(0, 4).toInt(), tradingDay.substring(4, 6).toInt(),
            tradingDay.substring(6, 8).toInt())
            .atStartOfDay(ET).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun closeMinute(tradingDay: String): Int? =
        at(tradingDay, 12 * 60)?.let { MarketClock.closeMinuteAt(it) }

    /** When every open position is sold at market: the close minus [DayTradingParams.flatBeforeCloseMinutes]. */
    fun flatMs(tradingDay: String, p: DayTradingParams = DayTradingEngine.params): Long? =
        closeMinute(tradingDay)?.let { at(tradingDay, it - p.flatBeforeCloseMinutes) }

    /**
     * When an unfilled entry is cancelled: the "too late to start" time (close minus
     * [DayTradingParams.lastEntryMinutes]) - or, on an engine that sits out the midday lull, the
     * lull's start when the plan was made before it.
     */
    fun entryDeadlineMs(tradingDay: String, recordedAt: Long, p: DayTradingParams = DayTradingEngine.params): Long? {
        val close = closeMinute(tradingDay) ?: return null
        var deadline = at(tradingDay, close - p.lastEntryMinutes) ?: return null
        if (p.avoidMiddayLull) {
            val lull = at(tradingDay, MarketClock.LULL_START_MINUTE)
            if (lull != null && recordedAt < lull) deadline = minOf(deadline, lull)
        }
        return deadline
    }

    private fun r(v: Double, places: Int = 3): Double {
        if (!v.isFinite()) return 0.0
        val m = Math.pow(10.0, places.toDouble())
        return Math.round(v * m) / m
    }

    /**
     * The features object for one row at the moment it is logged. [dataAtSec] is the newest
     * intraday bar the plan saw (epoch s, 0 unknown); [claude] marks a Claude plan (no engine
     * version applies to its levels).
     */
    fun build(
        row: ResearchRow,
        tradingDay: String,
        recordedAt: Long,
        dataAtSec: Long,
        claude: Boolean,
        minutesSinceOpen: Int,
        minutesLeft: Int,
        middayLull: Boolean,
        p: DayTradingParams = DayTradingEngine.params,
        engineVersion: Int = DayTradingEngine.version
    ): JSONObject = JSONObject().apply {
        val atr = row.atrIntraday
        val price = row.price
        val risk = row.entryPrice - row.stopPrice
        put("v", if (claude) -1 else engineVersion)
        if (row.setup.isNotBlank()) put("setup", row.setup)
        if (row.planLevel.isNotBlank()) put("lvl", row.planLevel)
        put("px", r(price, 4))
        if (atr > 0) {
            put("atr", r(atr, 4))
            if (price > 0) put("atrPct", r(atr / price * 100.0))
            put("riskAtr", r(risk / atr))
            put("trigAtr", r((row.entryPrice - price) / atr))
            if (row.vwap > 0) put("vwapAtr", r((price - row.vwap) / atr))
        }
        if (row.atr > 0) put("atrD", r(row.atr, 4))
        if (risk > 1e-9) put("rr", r((row.targetPrice - row.entryPrice) / risk))
        if (row.adr > 0) {
            if (price > 0) put("adrPct", r(row.adr / price * 100.0))
            if (row.sessionHigh > 0 && row.sessionLow > 0 && row.sessionHigh >= row.sessionLow)
                put("rangeUsed", r((row.sessionHigh - row.sessionLow) / row.adr))
        }
        put("chg", r(row.changePct, 2))
        if (row.score > 0) put("score", row.score)
        if (row.dtLikelihood > 0) put("lik", row.dtLikelihood)
        if (row.dtConfidence > 0) put("conf", row.dtConfidence)
        if (row.dtRvol > 0) put("rvol", r(row.dtRvol, 2))
        if (row.dtRangePos >= 0) put("rangePos", r(row.dtRangePos, 2))
        if (row.dtShorted) put("shorted", true)
        if (row.dtCatalystSoon) put("catSoon", true)
        if (row.catalyst.startsWith(Research.CATALYST_EARNINGS_TODAY)) put("earnToday", true)
        if (row.mentions > 0) put("ment", row.mentions)
        if (row.newsCount > 0) put("news", row.newsCount)
        put("orc", row.openingRangeComplete)
        put("obb", row.openingBarBullish)
        if (minutesSinceOpen >= 0) put("mso", minutesSinceOpen)
        if (minutesLeft > 0) put("mleft", minutesLeft)
        if (middayLull) put("lull", true)
        entryDeadlineMs(tradingDay, recordedAt, p)?.let { put("deadline", it) }
        flatMs(tradingDay, p)?.let { put("flat", it) }
        if (dataAtSec > 0) put("dataAt", dataAtSec)
    }

    /** The cancel-by and flat-by times a logged row was made with - or today's defaults for an older row. */
    fun timesFor(tradingDay: String, recordedAt: Long, features: String): Pair<Long?, Long?> {
        val o = runCatching { JSONObject(features) }.getOrNull()
        val deadline = o?.optLong("deadline", 0L)?.takeIf { it > 0 }
            ?: entryDeadlineMs(tradingDay, recordedAt, DayTradingParams.DEFAULTS)
        val flat = o?.optLong("flat", 0L)?.takeIf { it > 0 } ?: flatMs(tradingDay, DayTradingParams.DEFAULTS)
        return deadline to flat
    }
}
