package com.tj.portfolio.data

import org.json.JSONArray
import org.json.JSONObject

/** What the app is telling TJ to do with a position already on his board. */
enum class TradeVerdict { BUY, HOLD, SELL }

/**
 * BUY / HOLD / SELL for one symbol on TJ's board - a different question from the Research
 * tab's "is this worth buying fresh" (see [com.tj.portfolio.net.ResearchScore.best], which has
 * no HOLD state at all). This is "what do I do with what's already in my portfolio", computed
 * by [com.tj.portfolio.net.ResearchScore.holding] from data the app already fetches for the
 * Overview/Stats/Analysts tabs - no new provider, no new request.
 *
 * FROZEN FOR THE TRADING DAY, ON PURPOSE. TJ asked explicitly that this not reshuffle through
 * the day as fundamentals happen to refresh underneath it every few hours for other tabs, so
 * [dayKey] - not a rolling TTL like every other cache in this app - is what gates a recompute.
 * See [com.tj.portfolio.net.MarketClock.dayKey] and `PortfolioViewModel.loadRecommendation`.
 */
data class Recommendation(
    val symbol: String,
    val verdict: TradeVerdict,
    /** 0-100, the same blended scale [com.tj.portfolio.net.ResearchScore]'s other scorers use. */
    val score: Int,
    /** In TJ's language, most important first - the popup prints these as-is. */
    val reasons: List<String>,
    /** 0-100: how much of the input this was actually computed from, not guessed at. */
    val confidence: Int,
    val targetMean: Double = 0.0,
    val targetHigh: Double = 0.0,
    val targetLow: Double = 0.0,
    val analystCount: Int = 0,
    /** The price this was computed against, so a stale-looking upside can be explained. */
    val price: Double = 0.0,
    val dayKey: String = "",
    val computedAt: Long = 0L
) {
    val hasTarget: Boolean get() = targetMean > 0.0

    val upsidePct: Double
        get() = if (hasTarget && price > 0.0) (targetMean - price) / price * 100.0 else Double.NaN
}

/** On-disk codec, stored in the existing `fundamentals` table under its own kind. */
object RecommendationJson {

    fun encode(r: Recommendation): String = JSONObject().apply {
        put("v", 1)
        put("symbol", r.symbol)
        put("verdict", r.verdict.name)
        put("score", r.score)
        put("reasons", JSONArray().also { a -> r.reasons.forEach { a.put(it) } })
        put("confidence", r.confidence)
        put("targetMean", r.targetMean)
        put("targetHigh", r.targetHigh)
        put("targetLow", r.targetLow)
        put("analystCount", r.analystCount)
        put("price", r.price)
        put("dayKey", r.dayKey)
        put("computedAt", r.computedAt)
    }.toString()

    /** Total: an unreadable row degrades to "compute it again", never to an exception. */
    fun decode(json: String): Recommendation? = runCatching {
        val o = JSONObject(json)
        val reasons = ArrayList<String>()
        o.optJSONArray("reasons")?.let { a -> for (i in 0 until a.length()) reasons.add(a.optString(i)) }
        val verdict = runCatching { TradeVerdict.valueOf(o.optString("verdict")) }
            .getOrDefault(TradeVerdict.HOLD)
        fun d(k: String) = o.optDouble(k, 0.0).let { if (it.isFinite()) it else 0.0 }
        Recommendation(
            symbol = o.optString("symbol").uppercase(),
            verdict = verdict,
            score = o.optInt("score", 0),
            reasons = reasons,
            confidence = o.optInt("confidence", 0),
            targetMean = d("targetMean"),
            targetHigh = d("targetHigh"),
            targetLow = d("targetLow"),
            analystCount = o.optInt("analystCount", 0),
            price = d("price"),
            dayKey = o.optString("dayKey"),
            computedAt = o.optLong("computedAt", 0L)
        )
    }.getOrNull()
}
