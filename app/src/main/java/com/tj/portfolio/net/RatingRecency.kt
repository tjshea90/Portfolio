package com.tj.portfolio.net

import com.tj.portfolio.data.AnalystRating
import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.RatingTrend
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * HOW OLD AN ANALYST RATING IS, AND HOW MUCH THAT SHOULD STILL COUNT.
 *
 * Tj, 2026-09-18: *"Make it so this app doesn't base any buy sell hold recommendations on
 * stale analyst ratings. For example, it doesn't make sense to buy a stock based on an
 * analyst rating from 2 months ago."*
 *
 * ---- THE HOLE THIS CLOSES
 *
 * [ResearchScore.holding] weights the analyst term at +-30 and the price target at +-12.5 -
 * +-42.5 of a scale centred on 50, by a distance the heaviest input it has. It took those
 * numbers from [Consensus], which the app parses out of Yahoo's `financialData` and the "0m"
 * bucket of `recommendationTrend`. NEITHER CARRIES A DATE, and - this is the part that is easy
 * to get wrong - "0m" does not mean "rated this month". It is the STANDING consensus: every
 * analyst's CURRENT rating, however long ago they last touched it. An analyst who wrote Buy in
 * March and has not looked since is still a Buy in the 0m bucket in September. So the app's
 * single heaviest input could be, and for a thinly-followed name usually was, driven entirely
 * by opinions nobody had revisited in a year - with nothing on screen saying so.
 *
 * ---- WHAT IS ACTUALLY AVAILABLE
 *
 * Yahoo's `upgradeDowngradeHistory` carries `epochGradeDate` and the firm's own price target
 * per action, which [FundamentalsFeed.parseYahooRatings] already turns into dated
 * [AnalystRating] rows. That is the only free per-analyst DATE this app has found, and it is
 * what everything below is built on. The undated [Consensus] remains the fallback, discounted -
 * see [undatedTrust].
 *
 * ---- WHY THESE TIMEFRAMES
 *
 * The natural clock for equity research is the QUARTERLY EARNINGS CYCLE, not a round number of
 * days. Every covering desk publishes a note after a print; a rating carried across an earnings
 * date without being reaffirmed is a rating built on numbers the company has since superseded.
 * That is the reasoning behind all three constants:
 *
 *  - [FULL_WEIGHT_DAYS] = 30. Inside a month a rating is still the analyst's live view, and the
 *    published evidence on where recommendation changes actually pay (Womack 1996; Barber,
 *    Lehavy, McNichols & Trueman 2001, whose consensus portfolios need frequent rebalancing to
 *    hold their edge at all) puts the information concentrated in the weeks after publication.
 *  - [HALF_LIFE_DAYS] = 60. Two thirds of an earnings cycle. It puts a 90-day-old rating - one
 *    full quarter, exactly the "has this survived a print" line - at about 45% of a fresh one,
 *    and Tj's own two-month example at about 68%. Decay rather than a cliff because a cliff
 *    makes a verdict flip on a calendar boundary with nothing having happened in the market.
 *  - [CUTOFF_DAYS] = 240. Eight months, most of three earnings cycles. Past it the firm is not
 *    meaningfully covering the stock any more and its last note is dropped outright rather than
 *    decayed to a sliver, so it cannot pad the breadth count either.
 *
 * Industry practice brackets the same range: TipRanks computes its consensus from a rolling
 * three months, and the terminal feeds mark ratings untouched for six to twelve months as
 * inactive. 30 / 60 / 240 sits inside that bracket and is stated here as the judgment call it
 * is, not as a measured constant.
 *
 * PURE, LIKE [ResearchScore] ITSELF - no clock of its own, no network. `now` is a parameter, so
 * `AnalystRecencyTest` can drive it at any age without waiting for one.
 */
object RatingRecency {

    /** Inside this many days a rating counts in full. */
    const val FULL_WEIGHT_DAYS = 30.0

    /** After [FULL_WEIGHT_DAYS], a rating's weight halves every this many days. */
    const val HALF_LIFE_DAYS = 60.0

    /** At and beyond this age a rating counts for NOTHING and the firm is dropped. */
    const val CUTOFF_DAYS = 240.0

    private const val DAY_MS = 86_400_000.0

    /**
     * Fresh-equivalent analysts needed before the panel's lean carries its full +-30. Three is
     * the smallest number at which one contrarian desk cannot be the whole signal.
     */
    const val FULL_BREADTH_ANALYSTS = 3.0

    /**
     * The least the panel's own currency factor can fall to while ANY rating still survives
     * [CUTOFF_DAYS]. Broad-but-old coverage is weaker evidence, not no evidence - and below the
     * cutoff there is no panel at all, so this floor stops applying by construction.
     */
    const val MIN_PANEL_CURRENCY = 0.35

    /** Dated per-firm targets needed before the weighted target is preferred to Yahoo's mean. */
    const val MIN_TARGET_FIRMS = 3

    /**
     * HOW MUCH OF A FRESH RATING ONE THIS OLD IS WORTH, 1.0 down to 0.0, continuous throughout.
     *
     * Full weight to [FULL_WEIGHT_DAYS], then a [HALF_LIFE_DAYS] exponential RESCALED so it
     * arrives at exactly zero on [CUTOFF_DAYS] rather than being chopped off there. The rescale
     * is the whole reason this is not two lines: a bare half-life truncated at the cutoff leaves
     * a ~9% step, and a step means a stock's verdict can change overnight because a date rolled
     * over, which is precisely the kind of unexplainable flip this app's scoring rules exist to
     * prevent.
     *
     * A NEGATIVE AGE READS AS FRESH. Feeds do occasionally stamp an action a few hours into the
     * future (a note published to a different timezone's date). Clamping to full weight treats
     * it as today's news, which it is; the alternative - letting it fall through arithmetic
     * written for positive ages - is undefined behaviour on a number Tj trades against.
     */
    fun weight(ageDays: Double): Double {
        if (ageDays.isNaN()) return 0.0
        if (ageDays <= FULL_WEIGHT_DAYS) return 1.0
        if (ageDays >= CUTOFF_DAYS) return 0.0
        val decayed = 2.0.pow(-(ageDays - FULL_WEIGHT_DAYS) / HALF_LIFE_DAYS)
        val atCutoff = 2.0.pow(-(CUTOFF_DAYS - FULL_WEIGHT_DAYS) / HALF_LIFE_DAYS)
        return ((decayed - atCutoff) / (1.0 - atCutoff)).coerceIn(0.0, 1.0)
    }

    /** [weight] from two epoch-millis timestamps. A missing or zero date is worth nothing. */
    fun weightAt(date: Long, now: Long): Double =
        if (date <= 0L) 0.0 else weight((now - date) / DAY_MS)

    /**
     * THE PANEL, AFTER AGE HAS BEEN TAKEN INTO ACCOUNT.
     *
     * [buy]/[hold]/[sell] are vote MASS, not counts - eight analysts at 25% weight contribute
     * the same two units as two at full weight. [effectiveAnalysts] is their sum: "how many
     * fresh analysts is this panel worth". [firms] is the honest headcount behind it, so the UI
     * can say "9 analysts, worth 3.4 fresh ones" rather than picking one and hiding the other.
     */
    data class Panel(
        val buy: Double,
        val hold: Double,
        val sell: Double,
        val effectiveAnalysts: Double,
        /** Distinct firms whose latest action still counts. */
        val firms: Int,
        /** Distinct firms dropped for being past [CUTOFF_DAYS] - said out loud, not hidden. */
        val droppedStale: Int,
        /** Age of the MOST RECENT surviving action, in days. */
        val newestAgeDays: Int,
        /** Weight-weighted mean age of the surviving panel, in days. */
        val meanAgeDays: Int,
        /** Recency-weighted mean price target, 0.0 when no surviving firm published one. */
        val target: Double,
        /** Firms behind [target]. */
        val targetFirms: Int,
        /** Weight-weighted mean age of the ratings behind [target], in days. */
        val targetAgeDays: Int
    ) {
        val votes: Double get() = buy + hold + sell
        val hasVotes: Boolean get() = votes > 1e-9
        val hasTarget: Boolean get() = target > 0.0 && targetFirms > 0

        /**
         * -1 (unanimous sell) to +1 (unanimous buy), by weighted mass. The DIRECTION half of
         * the analyst term; [breadth] and [currency] decide how much of the +-30 it may move.
         */
        val lean: Double get() = if (hasVotes) (buy - sell) / votes else 0.0

        /** Enough fresh-equivalent opinions for the lean to carry full weight? 0.0-1.0. */
        val breadth: Double get() = min(1.0, effectiveAnalysts / FULL_BREADTH_ANALYSTS)

        /**
         * IS ANYONE STILL ACTIVELY COVERING THIS - read off the NEWEST surviving action, and
         * applied ON TOP of the per-rating decay already inside [effectiveAnalysts].
         *
         * That double discount is deliberate, and it is the statistical point of the whole
         * file. Decaying each rating on its own treats staleness like independent noise, where
         * averaging more opinions recovers precision. Staleness is not noise: every rating on a
         * panel nobody has revisited is stale about THE SAME missed quarter, the same guidance
         * cut, the same product cycle. Twenty opinions from before the last earnings print are
         * not a more reliable read on today than three - they are the same blind spot, twenty
         * times. So breadth alone must not be able to buy back currency, and this factor is
         * what stops it.
         */
        val currency: Double get() = max(MIN_PANEL_CURRENCY, weight(newestAgeDays.toDouble()))

        /** The one number the scorer multiplies its analyst term by. */
        val strength: Double get() = breadth * currency

        /** "9 analysts (worth 3.4 fresh), newest 12 days old" - the UI's own phrasing. */
        fun ageSummary(): String {
            val eff = (effectiveAnalysts * 10).roundToInt() / 10.0
            return "$firms rating${if (firms == 1) "" else "s"} still current " +
                "(worth $eff fresh), newest $newestAgeDays day${if (newestAgeDays == 1) "" else "s"} old"
        }
    }

    /**
     * Fold a symbol's full rating history into one age-aware [Panel].
     *
     * ONE VOTE PER FIRM, AND IT IS THE FIRM'S LATEST. `upgradeDowngradeHistory` is the whole
     * history - every action ever taken, often a dozen from the same desk. Counting them all
     * would let one prolific firm outvote the street and would count its own superseded
     * opinions against it; taking the latest per firm is what "the current consensus" actually
     * means. A REITERATE counts exactly like an upgrade for this purpose: the desk looked at it
     * again on that date, which is the only question being asked here.
     *
     * A row with no usable date is EXCLUDED rather than assumed recent. Finviz dates to the day
     * and Yahoo occasionally returns `epochGradeDate` 0; either way an undated row's age is not
     * known, and assuming "today" for it is how a year-old opinion would sneak back in at full
     * weight through the exact door this file exists to shut.
     *
     * Null when nothing survives - no dated rows at all, or every firm past [CUTOFF_DAYS]. The
     * caller then falls back to the undated [Consensus], capped by [undatedTrust].
     */
    fun panel(ratings: List<AnalystRating>, now: Long): Panel? {
        if (ratings.isEmpty()) return null

        // Latest DATED action per firm. `maxByOrNull` on the date rather than trusting the
        // list's order: `Fundamentals.merge` sorts descending, but a caller handing this a
        // hand-built or re-ordered list must get the same answer.
        val latest = HashMap<String, AnalystRating>()
        for (r in ratings) {
            if (r.date <= 0L) continue
            val key = r.firm.lowercase().trim()
            if (key.isEmpty()) continue
            val have = latest[key]
            if (have == null || r.date > have.date) latest[key] = r
        }
        if (latest.isEmpty()) return null

        var buy = 0.0; var hold = 0.0; var sell = 0.0
        var totalWeight = 0.0
        var ageWeight = 0.0
        var firms = 0
        var dropped = 0
        var newestAge = Int.MAX_VALUE
        var targetWeight = 0.0
        var targetValue = 0.0
        var targetAgeWeight = 0.0
        var targetFirms = 0

        for (r in latest.values) {
            val ageDays = (now - r.date) / DAY_MS
            val w = weight(ageDays)
            if (w <= 0.0) { dropped++; continue }
            firms++
            val age = max(0.0, ageDays)
            newestAge = min(newestAge, age.roundToInt())
            when (r.bucket) {
                AnalystRating.BUY -> { buy += w; totalWeight += w; ageWeight += w * age }
                AnalystRating.HOLD -> { hold += w; totalWeight += w; ageWeight += w * age }
                AnalystRating.SELL -> { sell += w; totalWeight += w; ageWeight += w * age }
                // A grade this app cannot bucket (a blank `toGrade` from a Finviz row) is not a
                // vote. It still proves the desk looked, so it keeps its place in `firms` and
                // can still contribute a TARGET below - it just cannot lean the consensus.
                else -> Unit
            }
            if (r.target > 0.0) {
                targetFirms++
                targetWeight += w
                targetValue += w * r.target
                targetAgeWeight += w * age
            }
        }
        if (firms == 0) return null

        return Panel(
            buy = buy,
            hold = hold,
            sell = sell,
            effectiveAnalysts = buy + hold + sell,
            firms = firms,
            droppedStale = dropped,
            newestAgeDays = if (newestAge == Int.MAX_VALUE) 0 else newestAge,
            meanAgeDays = if (totalWeight > 1e-9) (ageWeight / totalWeight).roundToInt() else 0,
            target = if (targetWeight > 1e-9) targetValue / targetWeight else 0.0,
            targetFirms = targetFirms,
            targetAgeDays = if (targetWeight > 1e-9) (targetAgeWeight / targetWeight).roundToInt() else 0
        )
    }

    // ============================================================ THE UNDATED FALLBACK

    /**
     * HOW MANY WHOLE MONTHS THE STANDING CONSENSUS HAS NOT MOVED, from the four free monthly
     * snapshots in `recommendationTrend` that [FundamentalsFeed.core] already fetches.
     *
     * THE SIGNAL IS ONLY SOUND IN ONE DIRECTION, and that asymmetry is the whole design. Counts
     * that CHANGED between two snapshots PROVE a desk acted in between - that is real, positive
     * evidence of live coverage. Counts that are identical prove nothing on their own: an
     * upgrade and a downgrade in the same month cancel exactly. So this returns "months with no
     * OBSERVED change", and [undatedTrust] reads it as absence of evidence rather than evidence
     * of absence - it discounts, it never zeroes.
     *
     * -1 when the snapshots are missing or too few to compare.
     */
    fun monthsWithoutObservedChange(trend: List<RatingTrend>): Int {
        val byPeriod = trend.associateBy { it.period }
        val now = byPeriod["0m"] ?: return -1
        fun same(other: RatingTrend) =
            other.strongBuy == now.strongBuy && other.buy == now.buy && other.hold == now.hold &&
                other.sell == now.sell && other.strongSell == now.strongSell
        var months = 0
        for (p in listOf("-1m", "-2m", "-3m")) {
            val prior = byPeriod[p] ?: return months
            if (!same(prior)) return months
            months++
        }
        return months
    }

    /**
     * How much of its face value an UNDATED [Consensus] may keep, 0.0-1.0.
     *
     * Reached when there are no dated ratings to build a [Panel] from - a symbol nobody's
     * `upgradeDowngradeHistory` covers, or a ratings fetch that simply failed. The consensus is
     * still real information; what is missing is any idea of its age, and an unknown age on the
     * app's heaviest input cannot be treated as if it were today's.
     *
     * [monthsWithoutObservedChange] sharpens this past a flat constant at no cost: a consensus
     * observed MOVING last month is demonstrably live coverage and keeps nearly all its weight,
     * while one that has not visibly moved in three months keeps under half. [UNKNOWN_TRUST] is
     * the middle when even the snapshots are absent - a judgment call, documented as one.
     */
    fun undatedTrust(trend: List<RatingTrend>): Double =
        when (monthsWithoutObservedChange(trend)) {
            0 -> 0.90
            1 -> 0.75
            2 -> 0.60
            3 -> 0.45
            else -> UNKNOWN_TRUST
        }

    const val UNKNOWN_TRUST = 0.60

    /** Plain-English "why is the analyst term discounted", for the reason list and the popup. */
    fun undatedNote(trend: List<RatingTrend>): String = when (monthsWithoutObservedChange(trend)) {
        0 -> "Analyst ratings have no publication dates from the feed, but the consensus moved " +
            "within the last month - counted at 90%"
        1 -> "Analyst ratings have no publication dates from the feed and the consensus has not " +
            "moved in a month - counted at 75%"
        2 -> "Analyst ratings have no publication dates from the feed and the consensus has not " +
            "moved in two months - counted at 60%"
        3 -> "Analyst ratings have no publication dates from the feed and the consensus has not " +
            "moved in three months - counted at 45%"
        else -> "Analyst ratings arrived with no publication dates, so their age cannot be " +
            "checked - counted at 60%"
    }

    /**
     * The undated buy/hold/sell lean, on the same -1..+1 scale as [Panel.lean], so the scorer's
     * two paths differ ONLY in how much weight they are allowed - never in how they read votes.
     */
    fun undatedLean(c: Consensus): Double {
        if (!c.hasVotes) return 0.0
        val buys = c.strongBuy + c.buy
        val sells = c.sell + c.strongSell
        return (buys - sells).toDouble() / c.votes
    }
}
