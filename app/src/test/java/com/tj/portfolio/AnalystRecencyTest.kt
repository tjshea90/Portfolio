package com.tj.portfolio

import com.tj.portfolio.data.AnalystRating
import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.RatingTrend
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.net.RatingRecency
import com.tj.portfolio.net.ResearchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * STALE ANALYST RATINGS MUST NOT DRIVE A BUY - Tj, 2026-09-18, in his own words: *"it doesn't
 * make sense to buy a stock based on an analyst rating from 2 months ago."*
 *
 * The scoring change this pins down is the heaviest single input in the whole app
 * ([ResearchScore.holding]'s +-30 analyst term and +-12.5 target term, against a scale centred
 * on 50), so every piece of it is exercised on its own here: the decay curve's shape and both
 * its endpoints, the hard cutoff, the one-vote-per-firm rule, the undated fallback, and the
 * end-to-end case that actually matters - the same unanimous BUY panel scored fresh and scored
 * stale, with only the dates different.
 *
 * No clock anywhere: `now` is a parameter of every function under test, which is what lets a
 * 239-day-old rating and a 241-day-old one both be checked in the same millisecond.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalystRecencyTest {

    private val now = 1_800_000_000_000L
    private fun daysAgo(d: Double): Long = now - (d * 86_400_000L).toLong()

    private fun rating(
        firm: String,
        ageDays: Double,
        grade: String = "Buy",
        target: Double = 0.0
    ) = AnalystRating(
        firm = firm, date = daysAgo(ageDays), toGrade = grade, target = target
    )

    // ============================================================== the decay curve

    @Test fun `a rating inside the full-weight window counts in full`() {
        assertEquals(1.0, RatingRecency.weight(0.0), 1e-9)
        assertEquals(1.0, RatingRecency.weight(29.0), 1e-9)
        assertEquals(1.0, RatingRecency.weight(RatingRecency.FULL_WEIGHT_DAYS), 1e-9)
    }

    @Test fun `a future-stamped rating reads as fresh rather than as undefined`() {
        assertEquals(1.0, RatingRecency.weight(-3.0), 1e-9)
    }

    @Test fun `weight falls monotonically and never leaves zero-to-one`() {
        var prev = 2.0
        var d = 0.0
        while (d <= 300.0) {
            val w = RatingRecency.weight(d)
            assertTrue("weight $w out of range at $d days", w in 0.0..1.0)
            assertTrue("weight rose from $prev to $w at $d days", w <= prev + 1e-12)
            prev = w
            d += 1.0
        }
    }

    @Test fun `the curve hits zero exactly at the cutoff, with no cliff just before it`() {
        assertEquals(0.0, RatingRecency.weight(RatingRecency.CUTOFF_DAYS), 1e-12)
        assertEquals(0.0, RatingRecency.weight(RatingRecency.CUTOFF_DAYS + 50.0), 1e-12)
        // A CLIFF IS THE FAILURE MODE THIS SHAPE EXISTS TO PREVENT: a bare half-life chopped off
        // at the cutoff leaves a ~9% step, and a step means a verdict can flip overnight because
        // a date rolled over with nothing having happened in the market.
        assertTrue(
            "the last day before the cutoff must not be worth a meaningful step",
            RatingRecency.weight(RatingRecency.CUTOFF_DAYS - 1.0) < 0.01
        )
    }

    @Test fun `the named checkpoints land where the design says they do`() {
        // Tj's own example - two months - keeps roughly two thirds of its weight.
        assertEquals(0.68, RatingRecency.weight(60.0), 0.03)
        // One full earnings cycle: under half. This is the line the timeframes are built around.
        assertTrue(RatingRecency.weight(90.0) < 0.5)
        assertTrue(RatingRecency.weight(90.0) > 0.40)
        // Six months: a sliver, but not yet nothing.
        assertTrue(RatingRecency.weight(180.0) < 0.12)
        assertTrue(RatingRecency.weight(180.0) > 0.0)
    }

    @Test fun `an undated row is worth nothing rather than being assumed recent`() {
        assertEquals(0.0, RatingRecency.weightAt(0L, now), 1e-12)
        assertEquals(0.0, RatingRecency.weightAt(-1L, now), 1e-12)
    }

    // ==================================================================== the panel

    @Test fun `only the latest action from each firm counts`() {
        val p = RatingRecency.panel(
            listOf(
                rating("Goldman Sachs", 200.0, "Sell"),
                rating("Goldman Sachs", 120.0, "Hold"),
                rating("Goldman Sachs", 5.0, "Buy"),
                rating("goldman sachs", 400.0, "Sell")   // same desk, different casing
            ),
            now
        )
        assertNotNull(p)
        assertEquals(1, p!!.firms)
        assertEquals(1.0, p.buy, 1e-9)
        assertEquals(0.0, p.sell, 1e-9)
        assertEquals(5, p.newestAgeDays)
    }

    @Test fun `a firm silent past the cutoff is dropped, not decayed to a sliver`() {
        val p = RatingRecency.panel(
            listOf(
                rating("A", 10.0, "Buy"),
                rating("B", 300.0, "Buy"),
                rating("C", 400.0, "Sell")
            ),
            now
        )!!
        assertEquals(1, p.firms)
        assertEquals(2, p.droppedStale)
        assertEquals(1.0, p.effectiveAnalysts, 1e-9)
    }

    @Test fun `a panel where every firm is past the cutoff is no panel at all`() {
        assertNull(RatingRecency.panel(listOf(rating("A", 300.0), rating("B", 500.0)), now))
    }

    @Test fun `undated rows alone produce no panel`() {
        assertNull(
            RatingRecency.panel(
                listOf(AnalystRating(firm = "A", date = 0L, toGrade = "Buy")),
                now
            )
        )
    }

    @Test fun `a missing clock produces no panel rather than infinitely fresh ratings`() {
        // The trap: every age is `now - date`, and a negative age reads as full weight by
        // design. A zero clock would therefore restore the exact bug this file exists to fix.
        assertNull(RatingRecency.panel(listOf(rating("A", 10.0)), now = 0L))
    }

    @Test fun `an unbucketable grade keeps its seat but cannot lean the consensus`() {
        val p = RatingRecency.panel(
            listOf(rating("A", 5.0, grade = ""), rating("B", 5.0, "Buy")),
            now
        )!!
        assertEquals(2, p.firms)
        assertEquals(1.0, p.votes, 1e-9)      // only B voted
        assertEquals(1.0, p.lean, 1e-9)
    }

    @Test fun `effective analysts is the decayed headcount, not the raw one`() {
        val p = RatingRecency.panel((1..10).map { rating("F$it", 90.0, "Buy") }, now)!!
        assertEquals(10, p.firms)
        assertTrue("10 quarter-old ratings should be worth well under 10 fresh ones",
            p.effectiveAnalysts < 5.0)
        assertTrue(p.effectiveAnalysts > 4.0)
    }

    // ------------------------------------------------------------ breadth x currency

    @Test fun `breadth alone cannot buy back currency on a uniformly stale panel`() {
        // THE STATISTICAL POINT OF THE WHOLE FILE. Twenty opinions from before the last earnings
        // print are not a more reliable read on today than three - they are the same blind spot
        // twenty times over, so piling on more stale coverage must not restore full weight.
        val stale = RatingRecency.panel((1..20).map { rating("F$it", 150.0, "Buy") }, now)!!
        assertEquals(1.0, stale.breadth, 1e-9)          // plenty of fresh-equivalent mass
        assertTrue(stale.currency < 0.45)               // but nobody has looked in five months
        assertTrue(stale.strength < 0.45)
    }

    @Test fun `one recent note on an otherwise old panel makes the panel current again`() {
        val rows = (1..12).map { rating("F$it", 150.0, "Buy") } + rating("Fresh", 6.0, "Buy")
        val p = RatingRecency.panel(rows, now)!!
        assertEquals(6, p.newestAgeDays)
        assertEquals(1.0, p.currency, 1e-9)
        assertEquals(1.0, p.strength, 1e-9)
    }

    @Test fun `a thinly covered fresh panel is discounted for breadth, not for age`() {
        val p = RatingRecency.panel(listOf(rating("A", 3.0, "Buy")), now)!!
        assertEquals(1.0, p.currency, 1e-9)
        assertEquals(1.0 / RatingRecency.FULL_BREADTH_ANALYSTS, p.breadth, 1e-9)
    }

    // ------------------------------------------------------------------- the target

    @Test fun `the weighted target leans toward the newest number`() {
        val p = RatingRecency.panel(
            listOf(
                rating("A", 5.0, "Buy", target = 200.0),
                rating("B", 200.0, "Buy", target = 100.0)
            ),
            now
        )!!
        assertEquals(2, p.targetFirms)
        // A plain mean would be 150. The 200-day-old number is worth a fraction of the fresh one.
        assertTrue("weighted target ${p.target} should sit well above the flat mean of 150",
            p.target > 185.0)
    }

    @Test fun `a firm past the cutoff cannot contribute a target either`() {
        val p = RatingRecency.panel(
            listOf(
                rating("A", 5.0, "Buy", target = 120.0),
                rating("B", 400.0, "Buy", target = 40.0)
            ),
            now
        )!!
        assertEquals(1, p.targetFirms)
        assertEquals(120.0, p.target, 1e-9)
    }

    // ============================================================ the undated fallback

    private fun trend(vararg counts: Triple<String, Int, Int>) =
        counts.map { (p, buy, sell) -> RatingTrend(period = p, buy = buy, hold = 2, sell = sell) }

    @Test fun `a consensus that moved last month is read as live coverage`() {
        val t = trend("0m" to 9 to 1, "-1m" to 7 to 1, "-2m" to 7 to 1, "-3m" to 7 to 1)
        assertEquals(0, RatingRecency.monthsWithoutObservedChange(t))
        assertEquals(0.90, RatingRecency.undatedTrust(t), 1e-9)
    }

    @Test fun `a consensus unchanged across all four snapshots is discounted hardest`() {
        val t = trend("0m" to 7 to 1, "-1m" to 7 to 1, "-2m" to 7 to 1, "-3m" to 7 to 1)
        assertEquals(3, RatingRecency.monthsWithoutObservedChange(t))
        assertEquals(0.45, RatingRecency.undatedTrust(t), 1e-9)
    }

    @Test fun `no snapshots at all falls back to the documented middle`() {
        assertEquals(-1, RatingRecency.monthsWithoutObservedChange(emptyList()))
        assertEquals(RatingRecency.UNKNOWN_TRUST, RatingRecency.undatedTrust(emptyList()), 1e-9)
    }

    @Test fun `the undated trust never reaches full weight`() {
        for (t in listOf(
            trend("0m" to 9 to 1, "-1m" to 7 to 1),
            trend("0m" to 7 to 1, "-1m" to 7 to 1, "-2m" to 7 to 1, "-3m" to 7 to 1),
            emptyList()
        )) {
            assertTrue(RatingRecency.undatedTrust(t) < 1.0)
        }
    }

    // ================================================ end to end, through the scorer

    private fun holding(ratings: List<AnalystRating>, price: Double = 100.0) =
        ResearchScore.holding(
            ResearchScore.HoldingInput(price = price, ratings = ratings, now = now)
        )

    @Test fun `a fresh unanimous buy panel with real upside is a BUY`() {
        val rows = (1..6).map { rating("F$it", 8.0, "Buy", target = 130.0) }
        val sc = holding(rows)
        assertEquals(TradeVerdict.BUY, ResearchScore.verdictFor(sc.score))
    }

    @Test fun `THE HEADLINE CASE - the same panel five months old is no longer a BUY`() {
        // Identical votes, identical targets, identical price. ONLY the dates differ.
        val fresh = (1..6).map { rating("F$it", 8.0, "Buy", target = 130.0) }
        val stale = (1..6).map { rating("F$it", 150.0, "Buy", target = 130.0) }
        val hot = holding(fresh).score
        val cold = holding(stale).score
        assertEquals(TradeVerdict.BUY, ResearchScore.verdictFor(hot))
        assertTrue("a five-month-old panel ($cold) must score below a fresh one ($hot)",
            cold < hot)
        assertTrue(
            "six analysts nobody has revisited in five months must not on their own reach BUY " +
                "(scored $cold)",
            ResearchScore.verdictFor(cold) != TradeVerdict.BUY
        )
    }

    @Test fun `a stale bearish panel likewise cannot on its own reach SELL`() {
        // The discount has to be symmetric, or the fix would quietly bias the app bullish.
        val stale = (1..6).map { rating("F$it", 150.0, "Sell", target = 70.0) }
        assertTrue(ResearchScore.verdictFor(holding(stale).score) != TradeVerdict.SELL)
        val fresh = (1..6).map { rating("F$it", 8.0, "Sell", target = 70.0) }
        assertEquals(TradeVerdict.SELL, ResearchScore.verdictFor(holding(fresh).score))
    }

    @Test fun `the reasons say how old the panel is, never just the verdict`() {
        val sc = holding((1..6).map { rating("F$it", 150.0, "Buy", target = 130.0) })
        assertTrue(
            "no reason line mentioned the age: ${sc.reasons}",
            sc.reasons.any { it.contains("days old") }
        )
        assertTrue(
            "no reason line mentioned the discount: ${sc.reasons}",
            sc.reasons.any { it.contains("% of full weight") }
        )
    }

    @Test fun `a stale target below the price does not manufacture a full-strength SELL`() {
        // The specific bug this guards: an analyst target is a TWELVE-MONTH figure set against
        // the price on the day it was written. A stock that has run since leaves a stale panel's
        // average target below today's price, which the target term would otherwise read as
        // analysts calling the stock overvalued - when nothing happened except the calendar.
        val ranAway = (1..6).map { rating("F$it", 170.0, "Buy", target = 60.0) }
        val sc = holding(ranAway, price = 100.0)
        assertTrue(
            "a 40% 'downside' from targets nobody has updated in six months should not be a " +
                "SELL on its own (scored ${sc.score})",
            ResearchScore.verdictFor(sc.score) != TradeVerdict.SELL
        )
    }

    @Test fun `with no dated ratings the undated consensus is used but capped`() {
        val c = Consensus(strongBuy = 12, buy = 6, hold = 1, sell = 0, targetMean = 130.0)
        val capped = ResearchScore.holding(
            ResearchScore.HoldingInput(price = 100.0, consensus = c, now = now)
        )
        // Still a real signal - an 18-0 buy consensus is not thrown away...
        assertEquals(TradeVerdict.BUY, ResearchScore.verdictFor(capped.score))
        // ...but it must score strictly below the same balance of opinion with real dates on it.
        val dated = holding((1..6).map { rating("F$it", 5.0, "Strong Buy", target = 130.0) })
        assertTrue(
            "undated (${capped.score}) must not outscore dated-and-fresh (${dated.score})",
            capped.score < dated.score
        )
        assertTrue(
            "the reasons must say the dates were missing: ${capped.reasons}",
            capped.reasons.any { it.contains("no publication dates") }
        )
    }

    @Test fun `dated ratings take precedence over the undated consensus`() {
        // Yahoo's standing consensus says unanimous BUY; the dated history says every one of
        // those notes is from last year. The dates win - that is the entire request.
        val c = Consensus(strongBuy = 15, buy = 5, hold = 0, sell = 0, targetMean = 160.0)
        val sc = ResearchScore.holding(
            ResearchScore.HoldingInput(
                price = 100.0,
                consensus = c,
                ratings = (1..20).map { rating("F$it", 300.0, "Strong Buy", target = 160.0) },
                now = now
            )
        )
        assertFalse(
            "a panel whose every note is ten months old must not read as a live BUY consensus",
            sc.reasons.any { it.contains("weighted by how recent") }
        )
        // Every dated row is past the cutoff, so there is no panel - it falls back to the
        // undated consensus AT A DISCOUNT rather than to full-strength face value.
        assertTrue(sc.reasons.any { it.contains("no publication dates") })
    }
}
