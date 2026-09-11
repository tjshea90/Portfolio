package com.tj.portfolio.net

import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.Recommendation

/**
 * Turns already-fetched [Fundamentals] into a [Recommendation] - the thin wrapper around
 * [ResearchScore.holding] that adds what the pure scorer deliberately does not know about:
 * which symbol this is and what day it is. Kept separate so the scorer itself stays a pure
 * function of numbers, testable with hand-built input and no clock - see `ResearchScoreTest`.
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
            values = fundamentals.values
        )
        val sc = ResearchScore.holding(input)
        val c = fundamentals.consensus
        return Recommendation(
            symbol = symbol.uppercase(),
            verdict = ResearchScore.verdictFor(sc.score),
            score = sc.score,
            reasons = sc.reasons,
            confidence = sc.confidence,
            targetMean = c?.targetMean ?: 0.0,
            targetHigh = c?.targetHigh ?: 0.0,
            targetLow = c?.targetLow ?: 0.0,
            analystCount = c?.votes ?: 0,
            price = price,
            dayKey = MarketClock.dayKey(now),
            computedAt = now
        )
    }
}
