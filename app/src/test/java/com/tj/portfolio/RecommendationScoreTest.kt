package com.tj.portfolio

import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.net.ResearchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE PER-HOLDING BUY/HOLD/SELL SCORER, PINNED DOWN.
 *
 * TJ's own words: "it is very important that the advice for buy hold and sell for each stock
 * is well grounded and good advice." A sign flip in [ResearchScore.holding] produces confident,
 * plausible, wrong output that looks exactly like a real recommendation - so every term is
 * exercised in isolation here, the same discipline `ResearchTest` already holds `best()` and
 * `trending()` to.
 *
 * Deliberately no network anywhere in this file: [ResearchScore.holding] is a pure function of
 * numbers already in hand, per its own KDoc, and that is what makes it testable at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecommendationScoreTest {

    private val input = ResearchScore.HoldingInput(price = 100.0)

    // -------------------------------------------------------------- the neutral default

    @Test fun `nothing published lands exactly on a HOLD with zero confidence`() {
        val sc = ResearchScore.holding(input)
        assertEquals(50, sc.score)
        assertEquals(TradeVerdict.HOLD, ResearchScore.verdictFor(sc.score))
        assertEquals(0, sc.confidence)
        assertTrue(sc.reasons.isNotEmpty())
    }

    // -------------------------------------------------------------------- verdict bands

    @Test fun `verdictFor respects the dead band around neutral`() {
        assertEquals(TradeVerdict.SELL, ResearchScore.verdictFor(37))
        assertEquals(TradeVerdict.HOLD, ResearchScore.verdictFor(38))
        assertEquals(TradeVerdict.HOLD, ResearchScore.verdictFor(50))
        assertEquals(TradeVerdict.HOLD, ResearchScore.verdictFor(62))
        assertEquals(TradeVerdict.BUY, ResearchScore.verdictFor(63))
    }

    // ------------------------------------------------------------- analyst consensus

    private fun consensus(
        strongBuy: Int = 0, buy: Int = 0, hold: Int = 0, sell: Int = 0, strongSell: Int = 0,
        target: Double = 0.0
    ) = Consensus(
        mean = 0.0, strongBuy = strongBuy, buy = buy, hold = hold, sell = sell,
        strongSell = strongSell, targetMean = target, targetHigh = target, targetLow = target
    )

    @Test fun `an overwhelming buy consensus with real upside is a BUY`() {
        val c = consensus(strongBuy = 12, buy = 4, hold = 2, sell = 0, target = 130.0)
        val sc = ResearchScore.holding(input.copy(consensus = c))
        assertEquals(TradeVerdict.BUY, ResearchScore.verdictFor(sc.score))
        assertTrue(sc.reasons.any { it.contains("consensus") })
        assertTrue(sc.reasons.any { it.contains("target") })
    }

    @Test fun `an overwhelming sell consensus with the price above target is a SELL`() {
        val c = consensus(strongBuy = 0, buy = 1, hold = 2, sell = 8, strongSell = 6, target = 70.0)
        val sc = ResearchScore.holding(input.copy(consensus = c))
        assertEquals(TradeVerdict.SELL, ResearchScore.verdictFor(sc.score))
    }

    @Test fun `more buy votes than sell votes always scores at least as high as the reverse`() {
        val bullish = consensus(buy = 10, hold = 5, sell = 2, target = 105.0)
        val bearish = consensus(buy = 2, hold = 5, sell = 10, target = 95.0)
        val a = ResearchScore.holding(input.copy(consensus = bullish)).score
        val b = ResearchScore.holding(input.copy(consensus = bearish)).score
        assertTrue("bullish consensus ($a) should outscore bearish ($b)", a > b)
    }

    @Test fun `a target below today's price pulls the score down, above pushes it up`() {
        val below = consensus(buy = 5, hold = 5, target = 80.0)   // -20% "upside"
        val above = consensus(buy = 5, hold = 5, target = 120.0)  // +20% upside
        val a = ResearchScore.holding(input.copy(consensus = below)).score
        val b = ResearchScore.holding(input.copy(consensus = above)).score
        assertTrue("a target below price ($a) should score under one above it ($b)", a < b)
    }

    @Test fun `no consensus at all means no analyst reasons are invented`() {
        val sc = ResearchScore.holding(input.copy(consensus = Consensus()))
        assertTrue(sc.reasons.none { it.contains("consensus") || it.contains("target") })
    }

    // ------------------------------------------------------------------------ valuation

    @Test fun `a cheap PEG scores higher than an expensive one, all else equal`() {
        val cheap = ResearchScore.holding(input.copy(values = mapOf("pegRatio" to 0.6))).score
        val rich = ResearchScore.holding(input.copy(values = mapOf("pegRatio" to 3.5))).score
        assertTrue("PEG 0.6 ($cheap) should outscore PEG 3.5 ($rich)", cheap > rich)
    }

    @Test fun `forward PE is only used when PEG is absent`() {
        val withBoth = ResearchScore.holding(
            input.copy(values = mapOf("pegRatio" to 1.0, "peForward" to 200.0))
        )
        val pegOnly = ResearchScore.holding(input.copy(values = mapOf("pegRatio" to 1.0)))
        // A wildly high forward P/E must not leak in once a PEG is already reported.
        assertEquals(pegOnly.score, withBoth.score)
    }

    // --------------------------------------------------------------------------- growth

    @Test fun `growing earnings scores higher than shrinking earnings`() {
        val growing = ResearchScore.holding(input.copy(values = mapOf("earningsGrowth" to 0.25))).score
        val shrinking = ResearchScore.holding(input.copy(values = mapOf("earningsGrowth" to -0.25))).score
        assertTrue(growing > shrinking)
    }

    @Test fun `revenue growth is the fallback when earnings growth is not reported`() {
        val sc = ResearchScore.holding(input.copy(values = mapOf("revenueGrowth" to 0.10)))
        assertTrue(sc.reasons.any { it.contains("Revenue growing", ignoreCase = false) })
    }

    // ---------------------------------------------------------------------- performance

    @Test fun `beating the S&P over a year scores higher than trailing it by the same margin`() {
        val beat = ResearchScore.holding(
            input.copy(values = mapOf("change52Week" to 0.30, "sp500Change52Week" to 0.10))
        ).score
        val trail = ResearchScore.holding(
            input.copy(values = mapOf("change52Week" to 0.10, "sp500Change52Week" to 0.30))
        ).score
        assertTrue(beat > trail)
    }

    // ---------------------------------------------------------------------- red flags

    @Test fun `a negative book value only ever subtracts`() {
        val base = ResearchScore.holding(input.copy(values = mapOf("earningsGrowth" to 0.10))).score
        val withFlag = ResearchScore.holding(
            input.copy(values = mapOf("earningsGrowth" to 0.10, "priceToBook" to -1.0))
        ).score
        assertTrue(withFlag < base)
    }

    @Test fun `heavy leverage only ever subtracts`() {
        val base = ResearchScore.holding(input.copy(values = mapOf("earningsGrowth" to 0.10))).score
        val withFlag = ResearchScore.holding(
            input.copy(values = mapOf("earningsGrowth" to 0.10, "debtToEquity" to 350.0))
        ).score
        assertTrue(withFlag < base)
    }

    @Test fun `heavy short interest only ever subtracts`() {
        val base = ResearchScore.holding(input.copy(values = mapOf("earningsGrowth" to 0.10))).score
        val withFlag = ResearchScore.holding(
            input.copy(values = mapOf("earningsGrowth" to 0.10, "shortPercentOfFloat" to 0.30))
        ).score
        assertTrue(withFlag < base)
    }

    // ---------------------------------------------------------------------- confidence

    @Test fun `confidence rises with how much of the picture was actually published`() {
        val bare = ResearchScore.holding(input).confidence
        val full = ResearchScore.holding(
            input.copy(
                consensus = consensus(buy = 5, hold = 2, target = 110.0),
                values = mapOf(
                    "pegRatio" to 1.2,
                    "earningsGrowth" to 0.08,
                    "change52Week" to 0.15,
                    "sp500Change52Week" to 0.10
                )
            )
        ).confidence
        assertTrue(full > bare)
    }

    // ------------------------------------------------------------ score stays in range

    @Test fun `the score never leaves 0 to 100 even under the most extreme inputs`() {
        val extremeBullish = ResearchScore.holding(
            input.copy(
                consensus = consensus(strongBuy = 30, target = 500.0),
                values = mapOf(
                    "pegRatio" to 0.01, "earningsGrowth" to 5.0,
                    "change52Week" to 5.0, "sp500Change52Week" to -5.0
                )
            )
        )
        val extremeBearish = ResearchScore.holding(
            input.copy(
                consensus = consensus(strongSell = 30, target = 1.0),
                values = mapOf(
                    "pegRatio" to 50.0, "earningsGrowth" to -5.0,
                    "change52Week" to -5.0, "sp500Change52Week" to 5.0,
                    "priceToBook" to -10.0, "debtToEquity" to 900.0, "shortPercentOfFloat" to 0.9
                )
            )
        )
        assertTrue(extremeBullish.score in 0..100)
        assertTrue(extremeBearish.score in 0..100)
        assertEquals(100, extremeBullish.score)
        assertEquals(0, extremeBearish.score)
    }
}
