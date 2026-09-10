package com.tj.portfolio.net

import com.tj.portfolio.data.Consensus2
import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.util.Fmt
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * THE SCORING ENGINE - the app's own arithmetic, and the reason the Research tab can be
 * trusted at all.
 *
 * TJ's decision for this round was "app scores, Claude explains". Everything in this file is
 * a PURE function of numbers the app fetched: no network, no clock, no model. That has three
 * consequences worth stating, because they are the whole point:
 *
 *  1. **It is testable.** `ResearchScoreTest` feeds it hand-built rows and asserts the
 *     ordering, so a change that quietly turns "cheap and growing" into "expensive and
 *     shrinking" fails a build rather than surfacing as a bad recommendation.
 *  2. **It can show its work.** Every scorer returns the reasons alongside the number, in
 *     the user's language, and the UI prints them. A score with no visible reason is a
 *     number to be suspicious of.
 *  3. **It degrades honestly.** A missing field scores ZERO for its component instead of
 *     being guessed at, and [confidence] reports how much of the input was actually there,
 *     so a thinly-covered small cap cannot outrank a well-covered one on absent data.
 *
 * NONE OF THIS IS A RECOMMENDATION. It is a ranking of public numbers, and the UI says so.
 */
object ResearchScore {

    // ------------------------------------------------------------------ helpers

    /** Linear ramp: 0 at [lo], [maxPoints] at [hi], clamped both ends. */
    internal fun ramp(v: Double, lo: Double, hi: Double, maxPoints: Double): Double {
        if (v.isNaN() || hi <= lo) return 0.0
        return ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * maxPoints
    }

    /** Log-scaled ramp for quantities that span orders of magnitude (market cap, volume). */
    private fun logRamp(v: Double, lo: Double, hi: Double, maxPoints: Double): Double {
        if (v <= 0 || hi <= lo) return 0.0
        return ramp(ln(v), ln(lo), ln(hi), maxPoints)
    }

    private fun pct(v: Double) = Fmt.pct(v)

    data class Scored(val score: Int, val reasons: List<String>, val confidence: Int)

    // --------------------------------------------------------------------- BEST

    /**
     * "Excellent buy" as the numbers can define it: growing, not expensive for that growth,
     * in an uptrend, big and liquid enough to be ownable, and with a catalyst in sight.
     *
     * Deliberately NOT momentum-only. A stock up 40% this week scores nothing here for the
     * move itself - that is what the Trending section is for. What earns points is the
     * combination of forward earnings above trailing earnings, a forward multiple that has
     * not already priced it in, and a price above both moving averages.
     */
    fun best(r: ScreenRow): Scored {
        // Whether the growth term actually scored, so the valuation line below cannot claim
        // growth the arithmetic refused to credit. See the note at its use (audit R3).
        var grewThisRound = false
        val why = ArrayList<String>()
        var s = 0.0
        var have = 0
        var want = 0

        // --- forward earnings growth (0-25)
        want++
        val g = r.epsGrowth
        if (!g.isNaN()) {
            have++
            val pts = ramp(g * 100.0, 0.0, 40.0, 25.0)
            s += pts
            if (pts > 0.0) grewThisRound = true
            if (g > 0.05) why.add(
                "Earnings expected to grow ${pct(g * 100.0)} - forward EPS " +
                    "${Fmt.priceBare(r.epsForward)} vs ${Fmt.priceBare(r.epsTtm)} trailing"
            )
        } else if (r.epsForward > 0 && r.epsTtm <= 0) {
            have++
            s += 14.0
            // A turnaround is a forward earnings improvement, so the valuation line may say
            // "for that growth" - it is the same claim the 14 points were awarded for.
            grewThisRound = true
            why.add(
                "Turning profitable: forward EPS ${Fmt.priceBare(r.epsForward)} against a " +
                    "trailing loss"
            )
        }

        // --- valuation (0-20)
        want++
        if (r.forwardPe > 0) {
            have++
            // 8x is a full score, 45x is none; anything above that is priced for perfection.
            val pts = ramp(-r.forwardPe, -45.0, -8.0, 20.0)
            s += pts
            if (r.forwardPe <= 25) {
                // ---- "FOR THAT GROWTH" ONLY IF THERE WAS GROWTH (Round 66 audit, R3).
                //
                // THE BUG THIS FIXES. The phrase was unconditional. A company whose forward
                // EPS is BELOW its trailing EPS scores zero on the growth term - `ramp` of a
                // negative is zero and no growth reason line is written - and then its top
                // reason line read "Forward P/E 11.00 - cheap for that growth", asserting the
                // one thing the arithmetic had just refused to credit. A low multiple on
                // SHRINKING earnings is not cheap; it is usually the market pricing the
                // shrinkage.
                why.add(
                    "Forward P/E ${Fmt.priceBare(r.forwardPe)} - " +
                        (if (r.forwardPe <= 12) "cheap" else "reasonable") +
                        (if (grewThisRound) " for that growth"
                        else " - but earnings are not growing")
                )
            }
        }

        // --- price trend (0-20)
        want++
        if (r.price > 0 && r.fiftyDayAvg > 0 && r.twoHundredDayAvg > 0) {
            have++
            var t = 0.0
            if (r.price > r.fiftyDayAvg) t += 10.0
            if (r.fiftyDayAvg > r.twoHundredDayAvg) t += 10.0
            s += t
            if (t >= 20.0) why.add("In an uptrend - above its 50-day and the 50-day is above the 200-day")
            else if (t > 0) why.add("Trend is mixed - above one moving average, below the other")
        }

        // --- position in the 52-week range (0-10)
        want++
        val pos = r.rangePos
        if (pos >= 0) {
            have++
            // Best between 45% and 90% of the range: past the damage, short of the blow-off.
            val pts = when {
                pos in 0.45..0.90 -> 10.0
                pos > 0.90 -> 5.0
                else -> ramp(pos, 0.10, 0.45, 6.0)
            }
            s += pts
            if (pos > 0.90) why.add("Trading within 10% of its 52-week high")
        }

        // --- size and liquidity (0-15)
        want++
        if (r.marketCap > 0) {
            have++
            s += logRamp(r.marketCap, 3e8, 2e10, 10.0)
        }
        want++
        if (r.avgVolume3M > 0) {
            have++
            s += logRamp(r.avgVolume3M, 1e5, 5e6, 5.0)
        }
        if (r.marketCap >= 1e10) why.add("Large cap (${Fmt.compact(r.marketCap)}) - liquid and widely covered")

        // --- which screens it turned up on (0-10)
        var listPts = 0.0
        if (Screener.Lists.UNDERVALUED_GROWTH in r.lists) listPts += 5.0
        if (Screener.Lists.GROWTH_TECH in r.lists) listPts += 4.0
        if (Screener.Lists.UNDERVALUED_LARGE in r.lists) listPts += 3.0
        if (Screener.Lists.MOST_ACTIVE in r.lists) listPts += 2.0
        s += min(listPts, 10.0)
        // ---- NAME ONLY THE SCREENS THAT ACTUALLY SCORED (Round 66 audit, R4).
        //
        // THE BUG THIS FIXES. The sentence was built from every list the symbol appeared on
        // except day-gainers - so a stock that turned up only in `day_losers` and
        // `most_shorted_stocks`, which score nothing here, printed "On Yahoo's day losers and
        // most shorted screen" among the reasons the app rates it a GOOD BUY. Heavy short
        // interest read as a bullish reason is exactly the kind of confident, plausible,
        // wrong line this file's header exists to prevent.
        val scoringScreens = listOf(
            Screener.Lists.UNDERVALUED_GROWTH, Screener.Lists.GROWTH_TECH,
            Screener.Lists.UNDERVALUED_LARGE, Screener.Lists.MOST_ACTIVE
        ).filter { it in r.lists }
        if (scoringScreens.isNotEmpty()) why.add(
            "On Yahoo's " + scoringScreens.joinToString(" and ") { Screener.label(it) } + " screen"
        )

        // --- book value sanity: a negative one is a red flag even in the BEST list
        if (r.priceToBook < 0) {
            s -= 8.0
            why.add("Negative book value - liabilities exceed assets on the balance sheet")
        }

        return Scored(s.coerceIn(0.0, 100.0).toInt(), why, confidence(have, want))
    }

    // ---- THE "WORST" SCORER WAS REMOVED IN ROUND 66.
    //
    // It ranked companies the screen rated as failing, for a section whose point was that
    // each name could be paired with something buyable that shorts it. Measured against
    // Yahoo in September 2026: 16 of 20 high-momentum mega-caps have a US single-stock
    // inverse fund; 2 of 40 beaten-down names do, and one of those two only on London and
    // Milan listings a US account cannot buy. Issuers launch these products on whatever
    // retail trades heavily, not on companies in trouble - so the section could not be made
    // actionable, and a list of failing companies with no way to act on it is not what the
    // app is for. The section, its scorer, the inverse-ETF lookup and its two Yahoo searches
    // per visible row all went together.

    // ----------------------------------------------------------------- TRENDING

    /** Everything the trending blend needs about one candidate, already gathered. */
    data class TrendInput(
        val symbol: String,
        val mentions: Int = 0,
        val mentions24hAgo: Int = 0,
        val rankDelta: Int = 0,
        val sentiment: String = "",
        val sentimentScore: Double = 0.0,
        val newsCount: Int = 0,
        val onYahooTrending: Boolean = false,
        val changePct: Double = 0.0
    )

    /**
     * The blend TJ asked for: what r/wallstreetbets is posting about AND what the news is
     * carrying, in one ranking rather than two lists side by side.
     *
     * Both halves are normalised against the busiest name in the same pass, so the score
     * means "how loud is this relative to today", not "how loud in absolute mentions" -
     * a quiet market day would otherwise produce a section of near-zero scores.
     */
    fun trending(t: TrendInput, maxMentions: Int, maxNews: Int): Scored {
        val why = ArrayList<String>()
        var s = 0.0

        // --- social volume (0-45)
        if (t.mentions > 0 && maxMentions > 0) {
            s += ramp(t.mentions.toDouble(), 0.0, maxMentions.toDouble(), 45.0)
            why.add("${t.mentions} r/wallstreetbets mentions today")
        }

        // --- social momentum (0-15): today against yesterday, as a ratio
        if (t.mentions24hAgo > 0 && t.mentions > 0) {
            val growth = (t.mentions - t.mentions24hAgo).toDouble() / t.mentions24hAgo
            s += ramp(growth * 100.0, 0.0, 150.0, 15.0)
            if (growth >= 0.5) why.add(
                "Mentions up ${pct(growth * 100.0)} from yesterday (${t.mentions24hAgo} -> ${t.mentions})"
            )
        }

        // --- climbing the board (0-10)
        if (t.rankDelta > 0) {
            s += ramp(t.rankDelta.toDouble(), 0.0, 25.0, 10.0)
            why.add("Climbed ${t.rankDelta} places on the wallstreetbets board in 24h")
        }

        // --- news volume (0-25)
        if (t.newsCount > 0 && maxNews > 0) {
            s += ramp(t.newsCount.toDouble(), 0.0, maxNews.toDouble(), 25.0)
            why.add("${t.newsCount} news ${if (t.newsCount == 1) "story" else "stories"} today")
        }

        // --- Yahoo's own trending tickers (0-5)
        if (t.onYahooTrending) {
            s += 5.0
            why.add("On Yahoo Finance's trending tickers list")
        }

        if (t.sentiment.isNotBlank()) {
            why.add("Reddit sentiment reads ${t.sentiment.lowercase()}")
        }
        if (abs(t.changePct) >= 5.0) {
            why.add("Price ${if (t.changePct > 0) "up" else "down"} ${pct(abs(t.changePct))} today")
        }

        // Confidence here is about how many independent sources saw it at all.
        val sources = listOf(t.mentions > 0, t.newsCount > 0, t.onYahooTrending).count { it }
        return Scored(s.coerceIn(0.0, 100.0).toInt(), why, confidence(sources, 3))
    }

    // ----------------------------------------------------------- analyst overlay

    /**
     * Fold analyst coverage into a score that was computed from price and earnings alone.
     *
     * Kept SEPARATE from [best] because the coverage arrives later: the screener
     * pass ranks a few hundred candidates with no extra requests, and only the ten rows the
     * user can actually see are then enriched with one Nasdaq call each. Blending rather
     * than adding keeps the result on the same 0-100 scale as an un-enriched row, so a
     * covered stock and an uncovered one can still sit in the same list.
     *
     * 70/30 in favour of the app's own numbers. Analysts are a real signal and a lagging,
     * herd-prone one; they get a third of the vote, not a veto.
     */
    // ---- NO `bullish` PARAMETER SINCE ROUND 66.
    //
    // It existed to serve the "Worst" list, where a strong-SELL consensus and a price target
    // BELOW the market were confirming evidence rather than a warning. That list is gone (see
    // the note where its scorer used to be), so every caller passed `true` and both branches
    // of every `if (bullish)` in here were dead on one side. Reading the bullish arithmetic
    // straight is clearer than reading a conditional that can only go one way.
    fun withAnalyst(base: Scored, c: Consensus2?, price: Double): Scored {
        if (c == null || (c.total == 0 && c.target <= 0)) return base
        val why = ArrayList(base.reasons)
        var a = 50.0

        if (c.total > 0) {
            val share = c.buyShare
            a = 20.0 + share.coerceIn(0.0, 1.0) * 60.0
            val lab = c.label()
            why.add(
                "$lab consensus - ${c.buy} buy / ${c.hold} hold / ${c.sell} sell " +
                    "across ${c.total} analysts"
            )
        }

        val up = c.upsidePct(price)
        if (!up.isNaN()) {
            a += ramp(up, -20.0, 40.0, 30.0) - 12.0
            why.add(
                if (up >= 0)
                    "Average price target ${Fmt.price(c.target)} - ${pct(up)} above today"
                else
                    "Average price target ${Fmt.price(c.target)} - ${pct(abs(up))} BELOW today"
            )
        }

        val blended = base.score * 0.7 + a.coerceIn(0.0, 100.0) * 0.3
        return Scored(
            blended.coerceIn(0.0, 100.0).toInt(),
            why,
            min(100, base.confidence + 15)
        )
    }

    /** How much of what the scorer wanted to read was actually reported, 0-100. */
    private fun confidence(have: Int, want: Int): Int =
        if (want <= 0) 0 else (have * 100 / max(1, want)).coerceIn(0, 100)

    // `grade()` WAS REMOVED IN ROUND 66. It turned a score into "Strong"/"Good"/"Fair" and
    // its KDoc said it was "used as the row's headline label" - nothing in the app has called
    // it for several rounds. The card shows the number and the reason lines instead, which is
    // strictly more information, and a function nobody calls is a claim about the UI that
    // stopped being true without anybody noticing.

}
