package com.tj.portfolio.net

import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.Recommendation

/**
 * Turns already-fetched [Fundamentals] into a [Recommendation] - the thin wrapper around
 * [ResearchScore.holding] that adds what the pure scorer deliberately does not know about:
 * which symbol this is and what day it is. Kept separate so the scorer itself stays a pure
 * function of numbers, testable with hand-built input and no clock - see `ResearchScoreTest`.
 *
 * SINCE 2026-09-18 IT ALSO CARRIES THE RATING DATES. [Fundamentals.ratings] - Yahoo's
 * `upgradeDowngradeHistory`, the only free per-analyst DATE this app has - is what lets
 * [ResearchScore.holding] tell a note published yesterday from one nobody has touched since
 * last year. See [RatingRecency] for the timeframes and why they are what they are. An empty
 * ratings list is a supported state, not a failure: the scorer falls back to the undated
 * consensus at a discount and says so on screen.
 */
object Recommend {

    fun build(
        symbol: String,
        price: Double,
        fundamentals: Fundamentals,
        now: Long = System.currentTimeMillis()
    ): Recommendation? {
        if (fundamentals.isEmpty || price <= 0.0) return null
        val input = ResearchScore.HoldingInput(
            price = price,
            consensus = fundamentals.consensus,
            values = fundamentals.values,
            ratings = fundamentals.ratings,
            trend = fundamentals.trend,
            now = now
        )
        val sc = ResearchScore.holding(input)
        val c = fundamentals.consensus
        val panel = RatingRecency.panel(fundamentals.ratings, now)

        // WHICH TARGET THE CARD PRINTS HAS TO BE THE ONE THE SCORE WAS COMPUTED FROM. The
        // scorer prefers the recency-weighted target once enough firms have a dated one
        // ([RatingRecency.MIN_TARGET_FIRMS]); showing Yahoo's all-ages mean next to a score
        // that used a different number is exactly the "a reason line the arithmetic does not
        // support" fault ResearchScore's own header exists to prevent.
        val weighted = panel?.takeIf { it.hasTarget && it.targetFirms >= RatingRecency.MIN_TARGET_FIRMS }

        // ---- AND THE SAME RULE FOR THE VOTES, NOT JUST THE TARGET.
        //
        // `RatingRecency.panel` returns non-null as soon as ONE firm survives the cutoff, even
        // when every surviving firm's grade is unbucketable - such a firm "keeps its place in
        // `firms`" and casts no vote (see AnalystRecencyTest's "an unbucketable grade keeps its
        // seat"). The scorer handles that correctly: it branches on `panel.hasVotes`, so a
        // vote-less panel falls through to the UNDATED consensus and scores
        // `undatedLean * 30 * undatedTrust` - 45-90% of full weight.
        //
        // The card, though, reported the panel regardless, and the two then described
        // different arithmetic: `ratingsDated = true`, `currentRatings = 4`,
        // `analystWeight = panel.strength` = breadth(0 votes) = 0.0. `freshnessNote()` printed
        // "4 analyst ratings still current ... Counted at 0% of full weight" underneath a
        // verdict that had actually counted them at 60%. Same fault as the target above, and
        // the same fix: report from the branch the score was computed from.
        val voting = panel?.takeIf { it.hasVotes }
        return Recommendation(
            symbol = symbol.uppercase(),
            verdict = ResearchScore.verdictFor(sc.score),
            score = sc.score,
            reasons = sc.reasons,
            confidence = sc.confidence,
            targetMean = weighted?.target ?: c?.targetMean ?: 0.0,
            targetHigh = c?.targetHigh ?: 0.0,
            targetLow = c?.targetLow ?: 0.0,
            // The feed's headcount when there is one; the dated panel's otherwise, so a symbol
            // with real rating history but no `financialData` consensus does not read as
            // "0 analysts" next to a verdict its analysts helped decide.
            analystCount = (c?.votes ?: 0).takeIf { it > 0 } ?: (panel?.firms ?: 0),
            price = price,
            dayKey = MarketClock.dayKey(now),
            computedAt = now,
            ratingsDated = voting != null,
            currentRatings = voting?.firms ?: 0,
            staleRatingsDropped = voting?.droppedStale ?: 0,
            effectiveAnalysts = voting?.effectiveAnalysts ?: 0.0,
            newestRatingDays = voting?.newestAgeDays ?: -1,
            analystWeight = voting?.strength
                ?: (if (c?.hasVotes == true) RatingRecency.undatedTrust(fundamentals.trend) else 0.0),
            targetIsWeighted = weighted != null,
            targetAgeDays = weighted?.targetAgeDays ?: -1,
            fundamentalsAt = fundamentals.fetched
        )
    }
}
