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
    val computedAt: Long = 0L,

    // ---- HOW FRESH THE ANALYST HALF OF THIS VERDICT ACTUALLY IS (2026-09-18).
    //
    // Tj: "it doesn't make sense to buy a stock based on an analyst rating from 2 months ago."
    // The scoring fix lives in [com.tj.portfolio.net.RatingRecency]; these fields are what the
    // POPUP needs to say so out loud, because a discount the reader cannot see is a discount he
    // has no way to disagree with. All of them are defaulted, so a row decoded from a cache
    // written before this existed reads as "unknown" rather than as a confident zero.

    /** True when real publication dates were available, so the weighting below is measured. */
    val ratingsDated: Boolean = false,
    /** Firms whose latest note still counts (inside [com.tj.portfolio.net.RatingRecency.CUTOFF_DAYS]). */
    val currentRatings: Int = 0,
    /** Firms dropped outright for being past the cutoff - said out loud, not silently ignored. */
    val staleRatingsDropped: Int = 0,
    /** [currentRatings] after age weighting - "worth this many fresh analysts". */
    val effectiveAnalysts: Double = 0.0,
    /** Age in days of the most recent surviving rating; -1 when no dates were available. */
    val newestRatingDays: Int = -1,
    /** 0.0-1.0: the fraction of its full weight the analyst term was actually allowed. */
    val analystWeight: Double = 1.0,
    /** True when [targetMean] is the recency-weighted target rather than the feed's flat mean. */
    val targetIsWeighted: Boolean = false,
    /** Weighted mean age in days of the targets behind [targetMean]; -1 when unknown. */
    val targetAgeDays: Int = -1,
    /**
     * WHEN THE [Fundamentals] THIS VERDICT WAS SCORED FROM WAS ITSELF LAST FETCHED (full-tests
     * audit, round 79 sweep) - not when this verdict was computed ([computedAt]. `dayKey`
     * freezes the verdict for the trading day, but `loadRecommendation` recomputes from
     * whatever `Fundamentals` is currently cached, which has its own six-hour refresh clock and
     * can be stale if that refresh has been failing (offline, a provider cooldown). Without
     * this there is nothing on screen distinguishing "recomputed just now, from data fetched
     * just now" from "recomputed just now, from data that is days old" - the same class of
     * unmarked-stale number Tj's original analyst-age complaint was about, one level further
     * back. 0 for a row cached before this field existed.
     */
    val fundamentalsAt: Long = 0L,
    /**
     * Dated analyst firms on file when EVERY one is past the 8-month cutoff; 0 otherwise (see
     * `RatingRecency.allStaleFirms`). Lets the popup say "all N ratings are over 8 months old"
     * instead of the false "came back with no publication dates" (full-tests audit, S-H1).
     */
    val allRatingsStale: Int = 0
) {
    val hasTarget: Boolean get() = targetMean > 0.0

    val upsidePct: Double
        get() = if (hasTarget && price > 0.0) (targetMean - price) / price * 100.0 else Double.NaN

    /** The analyst term was cut for age or thin coverage by enough to be worth showing. */
    val analystDiscounted: Boolean get() = analystWeight < 0.95

    /**
     * One line for the popup: how old this verdict's analyst input is and what was done about
     * it. Blank when there was no analyst input at all - a blank is honest, an invented
     * freshness claim is not.
     */
    fun freshnessNote(): String {
        val cut = "Counted at ${Math.round(analystWeight * 100)}% of full weight"
        return when {
            ratingsDated && currentRatings > 0 -> {
                val eff = Math.round(effectiveAnalysts * 10.0) / 10.0
                "$currentRatings analyst rating${if (currentRatings == 1) "" else "s"} still " +
                    "current, newest $newestRatingDays day${if (newestRatingDays == 1) "" else "s"} " +
                    "old - worth $eff fresh" +
                    (if (staleRatingsDropped > 0)
                        ", and $staleRatingsDropped older than 8 months dropped entirely"
                    else "") +
                    ". " + (if (analystDiscounted) "$cut." else "Counted in full.")
            }
            allRatingsStale > 0 -> (if (allRatingsStale == 1) "The only analyst rating on file is"
                else "All $allRatingsStale analyst ratings on file are") + " over 8 months old - " +
                (if (analystWeight > 0.0) "the consensus only counts because it moved last month. $cut."
                else "not counted.")
            analystCount > 0 -> "Analyst ratings came back with no publication dates, so their " +
                "age could not be checked. $cut."
            else -> ""
        }
    }
}

/** On-disk codec, stored in the existing `fundamentals` table under its own kind. */
object RecommendationJson {

    fun encode(r: Recommendation): String = JSONObject().apply {
        // v2 ADDED THE FRESHNESS FIELDS. Nothing reads this number to branch on - every new
        // field below is defaulted in [Recommendation] and read with an `opt*` default here, so
        // a v1 row written before 2026-09-18 decodes cleanly and simply reports "no dates".
        // It is stored because a version that was never written is a version nobody can check
        // against when the shape does eventually have to change incompatibly.
        put("v", 2)
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
        put("ratingsDated", r.ratingsDated)
        put("currentRatings", r.currentRatings)
        put("staleRatingsDropped", r.staleRatingsDropped)
        put("effectiveAnalysts", r.effectiveAnalysts)
        put("newestRatingDays", r.newestRatingDays)
        put("analystWeight", r.analystWeight)
        put("targetIsWeighted", r.targetIsWeighted)
        put("targetAgeDays", r.targetAgeDays)
        put("fundamentalsAt", r.fundamentalsAt)
        put("allRatingsStale", r.allRatingsStale)
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
            computedAt = o.optLong("computedAt", 0L),
            ratingsDated = o.optBoolean("ratingsDated", false),
            currentRatings = o.optInt("currentRatings", 0),
            staleRatingsDropped = o.optInt("staleRatingsDropped", 0),
            effectiveAnalysts = d("effectiveAnalysts"),
            newestRatingDays = o.optInt("newestRatingDays", -1),
            // 1.0, NOT 0.0, FOR A ROW THAT PREDATES THIS FIELD. A missing weight means "this
            // was scored before ages were checked", and the score in the same row was computed
            // at full weight - reporting 0% next to it would describe a discount that was never
            // applied. It is corrected the moment the day rolls over and the row is recomputed.
            analystWeight = if (o.has("analystWeight")) d("analystWeight") else 1.0,
            targetIsWeighted = o.optBoolean("targetIsWeighted", false),
            targetAgeDays = o.optInt("targetAgeDays", -1),
            fundamentalsAt = o.optLong("fundamentalsAt", 0L),
            allRatingsStale = o.optInt("allRatingsStale", 0)
        )
    }.getOrNull()
}
