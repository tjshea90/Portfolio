package com.tj.portfolio.net

import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.Consensus2
import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.util.Fmt
import kotlin.math.abs
import kotlin.math.floor
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

    // --------------------------------------------------------------- DAY TRADING

    /**
     * WHY THIS IS SEPARATE FROM [trending] AND [best], AND WHAT IT DOES NOT CLAIM.
     *
     * TJ asked for a tab that surfaces "that day's top stocks for day trading... expected to
     * rise in value... due to things like short squeezes or news or catalysts," with "a
     * target buy price and target sell price," and said explicitly: "it must be accurate and
     * give sound signals... if this is not possible do not make the feature."
     *
     * RESEARCHED BEFORE THIS WAS WRITTEN, per that instruction - see `TASKS.md`'s day-trading
     * feasibility write-up for the sources. The literal ask - reliably predicting WHICH
     * stocks will rise TODAY, with price targets accurate enough to trade on - is not
     * something this app, or any system built on free public data, can honestly deliver.
     * Published research on intraday prediction reports that whatever edge machine-learning
     * models found in market data largely vanished after 2009 as markets absorbed it, and the
     * mainstream finding on market efficiency is that future returns are "hardly predictable"
     * from public information at the timescale of a single trading day. If reliably possible
     * from data this app can reach for free, the edge would already be priced in by people
     * with far better data and far more compute than a phone app.
     *
     * WHAT THIS ACTUALLY DOES INSTEAD, HONESTLY: surfaces stocks that are OBJECTIVELY IN PLAY
     * RIGHT NOW - unusually heavy volume, a real price move already under way, elevated
     * wallstreetbets/news attention, a technical breakout, or a short-squeeze-prone setup -
     * and says WHY, the same "app scores, shows its work" rule [best] and [trending] already
     * follow. None of this is a claim that the move continues. It describes what is
     * happening, for a reader about to make their own trading decision, not a promise of what
     * happens next.
     *
     * "TARGET BUY / TARGET SELL", REFRAMED HONESTLY: since a genuine price forecast is not
     * available, [tradePlan] computes the entry/stop/target an ordinary risk-managed day trade
     * would use instead - a TRIGGER LEVEL price has to reach before anything is bought, a stop
     * under the structure that would invalidate it, and a target at the next real resistance.
     * Real, computed numbers off real levels - a risk plan, not a forecast.
     *
     * ROUND 69 CORRECTION. Until then the "entry" was simply the last traded price, which Tj
     * spotted and called out: *"the target buy price just matches the current market price. I
     * don't think this is how day traders operate."* It does not, and [TradePlan]'s own header
     * carries the fix and the reasoning.
     */
    fun dayTrading(
        r: ScreenRow,
        t: TrendInput?,
        maxMentions: Int,
        maxNews: Int,
        /** Reports earnings today or tomorrow - the caller already knows this from [catalystFor]. */
        catalystSoon: Boolean = false,
        /**
         * WHICH SESSION `changePct` AND `volumeRatio` ACTUALLY DESCRIBE - "today", "this
         * session", or "in the last session". Supplied by the caller, because this file is
         * pure by design and owns no clock (see the object header).
         *
         * Tj reported the underlying defect in Round 68: *"the market is currently closed and
         * yet the stocks claim to be 'already up today' which makes no sense."* That round
         * fixed the price line on the card and left these two REASON lines still saying
         * "today" over a closed market - the same sentence, a centimetre lower.
         */
        sessionWord: String = "today",
        /**
         * How much of the session `r.volume` has had to accumulate in
         * ([MarketClock.sessionElapsedFraction]). 1.0 - the default - is a complete session,
         * which is what the figure describes outside market hours and what every caller before
         * Round 73 meant. See [pacedVolumeRatio] for why the raw ratio cannot be compared to
         * these thresholds mid-session.
         */
        sessionFraction: Double = 1.0
    ): Scored {
        val why = ArrayList<String>()
        var s = 0.0
        var have = 0
        var want = 0

        // --- relative volume (0-30): the single best "is this actually in play today" proxy
        // the published day-trading literature points to - see the class header.
        want++
        val rvol = pacedVolumeRatio(r.volumeRatio, sessionFraction)
        val partial = sessionFraction < 1.0
        if (rvol > 0) {
            have++
            s += ramp(rvol, 1.0, 5.0, 30.0)
            if (rvol >= 2.0) why.add(
                if (partial)
                    "Running at ${Fmt.priceBare(rvol)}x its normal volume pace" +
                        if (rvol >= 5.0) " - heavily in play" else ""
                else
                    "Trading at ${Fmt.priceBare(rvol)}x its normal volume $sessionWord" +
                        if (rvol >= 5.0) " - heavily in play" else ""
            )
        }

        // --- today's move (0-20): already RISING, not just active. "Expected to rise" means
        // stocks moving up now, not merely loud ones - a falling stock earns nothing here
        // even with huge volume.
        want++
        if (r.changePct.isFinite() && r.price > 0) {
            have++
            s += ramp(r.changePct, 0.0, 12.0, 20.0)
            if (r.changePct >= 3.0) why.add("Up ${pct(r.changePct)} $sessionWord")
        }

        // --- social + news attention (0-20), at a lower weight than in [trending]: day
        // trading needs the move and the volume to be REAL first - chatter alone describes
        // what people are saying, not what the tape is doing.
        want++
        if (t != null) {
            have++
            var chat = 0.0
            if (t.mentions > 0 && maxMentions > 0) {
                chat += ramp(t.mentions.toDouble(), 0.0, maxMentions.toDouble(), 12.0)
                why.add("${t.mentions} r/wallstreetbets mentions today")
            }
            if (t.newsCount > 0 && maxNews > 0) {
                chat += ramp(t.newsCount.toDouble(), 0.0, maxNews.toDouble(), 8.0)
                why.add("${t.newsCount} news ${if (t.newsCount == 1) "story" else "stories"} today")
            }
            s += chat
        }

        // --- short-squeeze setup: membership on Yahoo's own most-shorted screen, boosted
        // when it is ALSO showing real volume and a real move today - the classic squeeze
        // shape (heavy short interest meeting buying pressure). Short interest alone is a
        // slow-moving structural fact (FINRA reports it twice a month), not a trigger for
        // today, so plain membership earns far less than the combination does.
        if (Screener.Lists.MOST_SHORTED in r.lists) {
            val squeeze = rvol >= 2.0 && r.changePct >= 3.0
            s += if (squeeze) 20.0 else 8.0
            why.add(
                if (squeeze)
                    "Heavily shorted AND moving up on strong volume - a classic short-squeeze shape"
                else "On Yahoo's most-shorted screen - squeeze-prone if volume picks up"
            )
        }

        // --- breakout / trend (0-10)
        want++
        if (r.price > 0 && r.fiftyDayAvg > 0) {
            have++
            if (r.rangePos in 0.0..1.0 && r.rangePos > 0.85) {
                s += 10.0
                why.add("Within 15% of its 52-week high - breaking out")
            } else if (r.price > r.fiftyDayAvg) {
                s += 5.0
            }
        }

        if (catalystSoon) {
            s += 8.0
            why.add("Reports earnings today or tomorrow")
        }

        if (why.isEmpty()) why.add("Screened in, but nothing about it stands out today")

        return Scored(s.coerceIn(0.0, 100.0).toInt(), why, confidence(have, want))
    }

    // =============================================================== THE TRADE PLAN (Round 69)

    /**
     * One day trade, planned off real levels.
     *
     * WHY [entry] IS NOT THE CURRENT PRICE, WHICH IS THE WHOLE POINT OF THIS TYPE. Tj,
     * 2026-09-11: *"the target buy price just matches the current market price. I don't think
     * this is how day traders operate."* He was right, and the two functions this replaced
     * both literally assigned `entry = price`. A day trader's buy price is a LEVEL THE MARKET
     * HAS TO COME TO - a buy-STOP placed above overhead resistance, so the trade only starts
     * if the move actually proves itself, or a buy-LIMIT down at support, so a move that has
     * already run is bought on the retrace instead of chased. Both are orders that sit unfilled
     * until price arrives. "Buy at whatever it is right now" is not a setup, it is the absence
     * of one, and every level derived from it (a stop under it, a target 2:1 above it) inherits
     * that emptiness.
     *
     * [setup] names which of those it is; [trigger] is the instruction in plain English; [note]
     * carries anything that should make the reader hesitate. See [tradePlan] for the rules.
     */
    data class TradePlan(
        val entry: Double,
        val stop: Double,
        val target: Double,
        val setup: String,
        val trigger: String,
        val note: String = "",
        /**
         * HOW THE TRADE ENDS - the half [target] on its own was never able to say (Round 73).
         *
         * A single take-profit price implies the trade is over when it prints, and that is not
         * what the evidence behind this feature actually supports. Zarattini, Barbon & Aziz's
         * profitable variants use NO fixed profit target at all: a tight volatility stop, and
         * otherwise hold to the closing bell, accepting a win rate in the 20s in exchange for
         * the minority of trades that run several times the risk. Their follow-up SPY paper
         * found that replacing a static exit with a VWAP-anchored TRAIL improved risk-adjusted
         * return substantially (and turned the return distribution's skew positive) even though
         * the hit rate FELL - the winners got bigger, which is the whole mechanism.
         *
         * SO WHY IS THERE STILL A [target]? Because Tj asked for one, in his own words and
         * twice - *"include a target buy price and target sell price for each of the stocks"* -
         * and because the plain-English summary a beginner reads is built on it. Removing it
         * would answer a question he did not ask. This field is the honest other half: the
         * target is the first objective, and this says what the research says about what to do
         * when price gets there, plus the one rule that is not optional in any of it - a day
         * trade is flat before the close.
         */
        val exit: String = "",
        /**
         * Not enough of the session is left to START this trade - see [tradePlan]'s time rules.
         * The levels are still real and still shown; what has run out is the clock.
         */
        val tooLateToStart: Boolean = false
    ) {
        val risk: Double get() = entry - stop
        val reward: Double get() = target - entry

        /** Reward-to-risk. The number a day trader actually decides on. */
        val rMultiple: Double get() = if (risk > 1e-9) reward / risk else 0.0
    }

    /** Price breaks a level, it does not touch it - so a trigger sits this far past the line. */
    private const val BREAK_BUFFER_ATRS = 0.15

    /** Above this many intraday ATRs over VWAP, buying the current price is chasing. */
    private const val EXTENDED_ATRS = 2.5

    /** Or: it has already travelled this much of a normal day's whole range. */
    private const val EXTENDED_RANGE_USED = 0.85

    /**
     * Day-trade stop, in intraday ATRs - the 1.5x-2.5x band practitioner sources give for a
     * hold of fifteen minutes to the close, on the ATR OF THE TIMEFRAME BEING TRADED.
     *
     * THE FLOOR IS THE HALF THAT DOES THE WORK, which is not obvious. For a breakout the
     * nearest level below entry IS the level just broken, so the structural stop comes out a
     * couple of cents under the trigger - far too tight to survive one ordinary 5-minute bar,
     * and a stop taken out by noise while the setup is still intact is worse than no stop.
     * The floor is what turns that into a real volatility stop. The ceiling matters in the
     * other direction, on a pullback whose next support is a long way down.
     */
    private const val MIN_RISK_ATRS = 1.5
    private const val MAX_RISK_ATRS = 2.5

    /**
     * The "at least 2:1" convention - now a FALLBACK ONLY, not a rule (Round 73).
     *
     * It is used in exactly one case: no real resistance overhead AND no average daily range to
     * say how far a normal day reaches. With nothing structural and nothing measured to work
     * from, a conventional 2R objective is the least-bad answer. Everywhere else the target now
     * comes from structure and from how much room the day actually has left - see [tradePlan]
     * step 4 and [MAX_REWARD_RISK_RATIO] for what this replaced.
     */
    private const val TARGET_REWARD_RISK_RATIO = 2.0

    /**
     * NO LONGER A CAP ON THE TARGET - a threshold for WARNING about one (Round 73).
     *
     * ---- WHAT THIS CONSTANT USED TO DO, AND WHY THAT WAS WRONG
     *
     * Until now every target was `min(nearest resistance, entry + 3R, room left in the day)`,
     * so no plan this app produced could ever aim higher than three times its risk. That looks
     * prudent and is, for this strategy family, the opposite: the published evidence for
     * profitable intraday momentum is explicit that the edge lives in a THIN RIGHT TAIL - a
     * minority of trades running many times the initial risk, paying for a majority of small
     * losers. Zarattini/Barbon/Aziz report per-stock win rates in the 17-27% band on their
     * best names, with cumulative results only possible because individual trades ran to 10R
     * and beyond; their sensitivity work, and Wu et al. (2020) on Taiwanese futures, both point
     * the same way - stops help, fixed profit targets hurt. Truncating every winner at 3R while
     * keeping every full-sized loser is the one modification most likely to turn a positive
     * expectancy negative.
     *
     * ---- WHAT REPLACED IT
     *
     * The realism constraint that stayed is the one grounded in measurement rather than
     * convention: how much of a normal day's range is actually left ([DayTechnicals.adr]). A
     * target beyond that is not conservative or aggressive, it is simply unlikely to print
     * before the close - and since the same number feeds [rMultiple] and the beginner
     * summary's "sell at $X", letting it overstate the reward would be a money-accuracy fault,
     * not just an optimistic one.
     *
     * ---- A CORRECTION TO THE RESEARCH THAT PROMPTED THIS
     *
     * The brief behind this round argued the 3R cap gives negative expectancy by arithmetic:
     * `0.22 x 3 - 0.78 x 1 = -0.12R`. That specific sum does not hold - it multiplies the win
     * rate of a HOLD-TO-CLOSE system by the payoff of a CAPPED-TARGET one, and capping at 3R
     * mechanically converts some would-be losers into winners, so the two numbers belong to
     * different systems. The conclusion survives the correction (a hard cap does truncate the
     * tail the edge depends on), but the arithmetic does not, and the cap is therefore relaxed
     * on the strength of the direct evidence above rather than that calculation.
     *
     * What it now does: a plan needing more than this much of a move is flagged in [planNote],
     * because a 6R objective is a real reading of the levels and also a warning that most days
     * will not deliver it.
     */
    private const val MAX_REWARD_RISK_RATIO = 3.0

    /**
     * A trade needs this many minutes of session left to be worth STARTING (Round 73).
     *
     * A day trade is closed the same session - that is what makes it one - so an entry trigger
     * is only meaningful while there is still time for the move it waits for. Thirty minutes is
     * a judgment call, not a measured constant, and is documented as such: it is roughly the
     * shortest window in which a trigger can fill and a target can plausibly print, and it sits
     * just outside the 15:35-16:00 window in which exchange volatility bands (LULD) double and
     * closing-auction imbalances start to dominate the tape. Below it the plan is still shown -
     * the levels are real, and tomorrow they may matter - but it is marked as no longer
     * startable today rather than presented as a live instruction.
     */
    internal const val MIN_MINUTES_FOR_NEW_ENTRY = 30

    /**
     * IS IT TOO LATE IN THE SESSION TO START A NEW DAY TRADE - a pure function of the clock.
     *
     * Deliberately NOT read off [TradePlan.tooLateToStart] by callers that have the clock
     * (second code-review pass). The flag belongs to the moment, not to the plan, and bundling
     * it with the plan made it stick in two ways that both showed on screen: a row whose plan
     * came back null - now reachable mid-session, when the day's range is spent - kept the
     * morning's flag for the rest of the afternoon, and a Claude-imported plan, which
     * `mergeDayTradingTech` never recomputes at all, kept whatever the flag was at import time
     * forever. Both meant the "TOO LATE TO START TODAY" badge and the beginner card's matching
     * branch failed to appear on exactly the rows a reader is most likely to act on late in the
     * day. Computed from the clock for every row instead, Claude's included.
     *
     * 0 means "the caller has no session clock" (see [tradePlan]'s own parameter), never "the
     * day is over" - which is why the test is a range and not `< MIN_MINUTES_FOR_NEW_ENTRY`.
     */
    fun tooLateToStart(minutesLeft: Int): Boolean =
        minutesLeft in 1 until MIN_MINUTES_FOR_NEW_ENTRY

    /**
     * How far above the last price a trigger can sit before it is a different trade (Round 73).
     *
     * A breakout trigger is the nearest overhead level, and "nearest" can still be a long way
     * off on a quiet name - at which point the plan is no longer "buy this if it goes", it is
     * "buy something several percent higher than anything happening now", with a stop and a
     * target measured from a price the stock may never see. Capped in intraday ATRs, the same
     * ruler everything else in this engine is measured in, and warned about rather than
     * rejected: the level itself is still the right level, it is the distance that deserves
     * saying out loud.
     */
    private const val MAX_TRIGGER_DISTANCE_ATRS = 2.0

    /**
     * The least the day's remaining room must cover for a plan to be worth drawing at all -
     * one times the risk. See [tradePlan] step 4 for the sub-1R "target" this prevents.
     */
    private const val MIN_CEILING_REWARD_RATIO = 1.0

    /**
     * A 5-minute ATR as a fraction of the daily one, for the overnight case where no intraday
     * bars exist yet. Ranges grow with roughly the square root of time, and a session holds 78
     * five-minute bars, so a 5-minute range lands near 1/sqrt(78) - about a tenth - of the
     * day's. An estimate, used only when the measured one is unavailable.
     */
    private const val INTRADAY_ATR_FROM_DAILY = 0.10

    /** One candidate price level, with the name the trigger sentence calls it by. */
    private data class Level(val price: Double, val name: String)

    private fun levelsOf(vararg pairs: Pair<Double, String>): List<Level> =
        pairs.filter { it.first > 0.0 }.map { Level(it.first, it.second) }

    /**
     * THE ENGINE. Turns one live technicals reading into a real day-trading plan.
     *
     * The rules, and where each comes from (full citations in [DayTradingTechnicals]'s header):
     *
     *  1. **Which setup.** Where price sits against real structure decides it, not a preference.
     *     Below VWAP, buyers are not in control and a long is premature - the trigger is a
     *     RECLAIM of VWAP. Already extended (more than [EXTENDED_ATRS] intraday ATRs above VWAP,
     *     or [EXTENDED_RANGE_USED] of a normal day's range spent) - the published guidance is
     *     unanimous that this is where chasing loses, so the trigger is a PULLBACK to the
     *     nearest level below. Otherwise it is a BREAKOUT of the nearest level overhead.
     *  2. **Entry.** The level itself - plus [BREAK_BUFFER_ATRS] of an ATR when the trade needs
     *     price to clear a line rather than reach it. The candidate levels are the ones
     *     intraday traders actually watch: the premarket high, the opening range, the prior
     *     session's high, the session high so far, and floor-trader R1/R2.
     *  3. **Stop.** Under the structure that would invalidate the setup - the level below
     *     entry, buffered - then clamped into [MIN_RISK_ATRS]..[MAX_RISK_ATRS] intraday ATRs so
     *     it stays a same-session stop.
     *  4. **Target** (reworked in Round 73). The nearest real resistance ABOVE BOTH the entry
     *     and the last price, because that is where the move runs into supply - a level price
     *     has already traded through is not resistance. It is capped only by how much of a
     *     normal day's range is left, never by a fixed multiple of the risk any more: see
     *     [MAX_REWARD_RISK_RATIO] for the evidence that a hard cap truncates exactly the tail
     *     this kind of trade earns from. When the nearest resistance is closer than 2R the
     *     target is placed AT IT and the thin reward is reported rather than a 2R target being
     *     drawn straight through a wall; when the day's measured remaining range will not cover
     *     even 1R ([MIN_CEILING_REWARD_RATIO]), there is no plan at all rather than a
     *     few-cents-of-upside one. With neither structure nor a measured range,
     *     [TARGET_REWARD_RISK_RATIO] is the last-resort convention.
     *
     * Null when there is nothing real to build from - no price, or neither an intraday nor a
     * daily ATR. The caller then shows no plan at all, which is the honest output: the levels
     * ARE the feature, and a fabricated one is worse than a blank.
     */
    fun tradePlan(
        price: Double,
        tech: DayTradingTechnicals.DayTechnicals,
        /**
         * Minutes of REGULAR session left ([MarketClock.minutesLeftInSession]), 0 when it is
         * not open. Passed in rather than read, because this file owns no clock by design -
         * the same reason `sessionWord` is a parameter of [dayTrading]. Defaulted so the
         * overnight/no-clock case behaves exactly as it did before this round.
         */
        minutesLeft: Int = 0,
        /** In the 11:30-13:30 ET lull ([MarketClock.inMiddayLull]) - a caution, never a block. */
        middayLull: Boolean = false,
        /**
         * This name reports earnings today, so a release may land after the close. Matters to a
         * day trade for one specific reason - see [planNote]'s earnings branch - and it is NOT
         * a disqualifier: an earnings day is the canonical reason a stock is in play at all.
         */
        earningsToday: Boolean = false
    ): TradePlan? {
        if (price <= 0.0) return null
        val vol = when {
            tech.atrIntraday > 0.0 -> tech.atrIntraday
            tech.atr14 > 0.0 -> tech.atr14 * INTRADAY_ATR_FROM_DAILY
            else -> return null
        }
        if (vol <= 0.0) return null
        val buffer = maxOf(0.01, vol * BREAK_BUFFER_ATRS)

        // ONLY THE LIVE SESSION'S LEVELS COUNT AS THE LIVE SESSION'S - a correction caught
        // while writing this. Outside market hours the intraday readings describe a session
        // that has ALREADY ENDED: its VWAP is gone (VWAP resets at every open), its opening
        // range is yesterday's, and `rangeUsed` is pinned near 100% simply because the day
        // finished - which would have routed literally every overnight plan into "extended, buy
        // the pullback" on the strength of a number that says nothing about tomorrow. Outside
        // hours this plans the only thing that is actually plannable: the break of prior-session
        // structure, which is the gap-and-go trader's overnight homework.
        val live = tech.sessionLive
        val overhead = if (live) levelsOf(
            tech.premarketHigh to "the premarket high",
            // THE FIVE-MINUTE OPENING RANGE COMES FIRST (Round 73) - it is the lowest of the
            // opening levels and therefore the earliest trigger, and it is the variant the
            // strongest published test of this setup found best while finding the 30-minute one
            // below it worst (see [DayTradingTechnicals.openingBar]). It needs no clock of its
            // own to stay honest: price passes it within minutes on any stock actually in play,
            // and the `>= price` filter below then drops it automatically.
            tech.or5High to "the first 5-minute bar's high",
            tech.openingRangeHigh to "the opening-range high",
            tech.prevHigh to "the prior session's high",
            tech.sessionHigh to "the high of day",
            tech.r1 to "pivot R1",
            tech.r2 to "pivot R2"
        ) else levelsOf(
            tech.premarketHigh to "the premarket high",
            tech.prevHigh to "the last session's high",
            tech.r1 to "pivot R1",
            tech.r2 to "pivot R2"
        )
        val below = if (live) levelsOf(
            tech.vwap to "VWAP",
            tech.openingRangeHigh to "the opening-range high, now support",
            tech.or5High to "the first 5-minute bar's high, now support",
            tech.or5Low to "the first 5-minute bar's low",
            tech.openingRangeLow to "the opening-range low",
            tech.prevHigh to "the prior session's high, now support",
            tech.prevClose to "the prior close",
            tech.pivot to "the daily pivot",
            tech.sessionLow to "the session low",
            tech.s1 to "pivot S1"
        ) else levelsOf(
            tech.prevHigh to "the last session's high, now support",
            tech.prevClose to "the last close",
            tech.pivot to "the daily pivot",
            tech.prevLow to "the last session's low",
            tech.s1 to "pivot S1"
        )

        val extendedOverVwap = live && tech.vwap > 0.0 && (price - tech.vwap) / vol >= EXTENDED_ATRS
        val rangeSpent = live && tech.rangeUsed >= EXTENDED_RANGE_USED

        // ---- 1 and 2: the setup, and the price it triggers at.
        val setup: String
        val entry: Double
        val entryLevel: String
        when {
            live && tech.vwap > 0.0 && price < tech.vwap -> {
                setup = SETUP_RECLAIM
                entry = tech.vwap + buffer
                entryLevel = "VWAP"
            }

            extendedOverVwap || rangeSpent -> {
                val support = below.filter { it.price < price }.maxByOrNull { it.price }
                setup = SETUP_PULLBACK
                entry = support?.price ?: (price - vol)
                entryLevel = support?.name ?: "one intraday ATR below the current price"
            }

            else -> {
                val next = overhead.filter { it.price >= price }.minByOrNull { it.price }
                setup = SETUP_BREAKOUT
                entry = (next?.price ?: price) + buffer
                entryLevel = next?.name ?: "the current session high"
            }
        }
        if (entry <= 0.0) return null

        // ---- 3: the stop, under the structure that would say the setup failed.
        val structural = below.filter { it.price < entry }.maxByOrNull { it.price }
        val rawRisk = structural?.let { entry - (it.price - buffer) } ?: (vol * 1.5)
        val minRisk = maxOf(vol * MIN_RISK_ATRS, 0.01)
        val maxRisk = maxOf(vol * MAX_RISK_ATRS, minRisk)
        val risk = rawRisk.coerceIn(minRisk, maxRisk)
        val stop = entry - risk
        if (stop <= 0.0) return null

        // ---- 4: the target - the nearest real supply, capped by the room the day has left.
        //
        // WHAT CHANGED IN ROUND 73: the hardcoded `entry + 3R` ceiling is gone. See
        // [MAX_REWARD_RISK_RATIO]'s own header for the evidence (in short: this strategy family
        // earns its expectancy in a thin right tail, and truncating every winner at 3R while
        // keeping every full-sized loser is the single modification most likely to invert it).
        // The realism constraint that remains is the measured one rather than the conventional
        // one - how much of a normal day's range is actually left.
        // A LEVEL PRICE HAS ALREADY TRADED THROUGH IS NOT RESISTANCE (code-review fix).
        //
        // Filtering overhead levels against the ENTRY alone is right for a breakout, where the
        // entry is above the last price - but a pullback entry sits BELOW it, and then a level
        // the stock has already passed on its way up qualifies as the "next resistance" and
        // becomes the target. The result was a target UNDER the current price: the grid saying
        // "buy the pullback to $105, target $106" while the beginner card underneath read "too
        // late for this one today", because by its own arithmetic the price had passed the
        // target already. Whichever of the two is higher is the real floor for supply overhead.
        val above = maxOf(entry, price)
        val nearestAbove = overhead
            .filter { it.price > above + risk * 0.3 }
            .minByOrNull { it.price }

        // "How much room is left in the day" is a live-session question - the same reason the
        // extension checks above are gated on `live`. Yesterday's low plus a normal day's range
        // is not a ceiling on tomorrow, so outside hours the ceiling is measured from the ENTRY
        // instead: one whole average day's range above the trigger is already the optimistic
        // end of what a single session delivers.
        val roomCeiling = when {
            live && tech.adr > 0.0 && tech.sessionLow > 0.0 -> tech.sessionLow + tech.adr
            !live && tech.adr > 0.0 -> entry + tech.adr
            else -> Double.MAX_VALUE
        }

        // ---- THE CEILING MAY CAP A TARGET. IT MAY NOT MANUFACTURE A POINTLESS ONE.
        //
        // Caught by code review before shipping. The first draft applied the room ceiling
        // whenever it was merely above the entry, which on an already-extended stock produces
        // targets a few cents up: a $2 average day, a session low of $50 and a VWAP pullback
        // entry at $51.90 gives a "target" of $52.00 against $0.34 of risk - a 0.29R plan, at a
        // price that is not a level of any kind. That number then feeds [rMultiple], the share
        // count and the beginner card's "sell at $52.00 for a profit", which after costs is a
        // loss dressed as a plan. When the measured room left will not cover even one times the
        // risk, the honest output is the one this function already has for "nothing real to
        // build from" - no plan at all. The row keeps its score and its reasons; what it loses
        // is levels that were never worth acting on.
        // `ceilingKnown` IS NOT REDUNDANT - an unmeasured ceiling is Double.MAX_VALUE, which
        // sails past any "is it far enough above the entry" test and would then be USED as the
        // target. Caught by the "neither structure nor a measured range" test below, which
        // reported a reward:risk of 1.2e308.
        val ceilingKnown = roomCeiling < Double.MAX_VALUE
        // MEASURED FROM `above`, NOT FROM `entry` (second code-review pass). Measuring the
        // ceiling's usefulness from the entry alone repeated - on the ceiling path - the exact
        // bug `above` was introduced to fix on the resistance path: on a pullback the entry sits
        // below the last price, so a ceiling comfortably above the ENTRY can still sit below the
        // PRICE, and the grid then reads "Buy at 105, target 108" with the stock trading at 110
        // while the beginner card underneath says "too late for this one today". A target under
        // the current price is not a target on either path.
        val ceilingUsable = ceilingKnown && roomCeiling > above + risk * MIN_CEILING_REWARD_RATIO

        val target: Double
        val targetFromRoom: Boolean
        when {
            // Measured, and there is no room worth trading into today.
            ceilingKnown && !ceilingUsable -> return null
            // Real supply overhead: that is the objective, never further than the day reaches.
            nearestAbove != null && roomCeiling < nearestAbove.price -> {
                target = roomCeiling
                targetFromRoom = true
            }
            nearestAbove != null -> {
                target = nearestAbove.price
                targetFromRoom = false
            }
            // Clear air above, and a measured idea of how far the day goes: use it. This is the
            // case the old flat 2R rule served worst - it answered "how far can this run" with a
            // convention when an actual measurement of this stock's normal day was available.
            ceilingUsable -> {
                target = roomCeiling
                targetFromRoom = true
            }
            // Neither structure nor range: the 2:1 convention, explicitly as a last resort.
            else -> {
                target = entry + risk * TARGET_REWARD_RISK_RATIO
                targetFromRoom = false
            }
        }
        // AND THE SAME FLOOR ON THE LAST-RESORT PATH (second code-review pass). The two paths
        // above now measure from `above`, but the 2:1 convention below them is computed from the
        // entry alone, and on a pullback the entry sits below the last price - so with no
        // overhead level and no measured range (a failed daily-bar half leaves `adr`, the prior
        // session and the pivots all at zero, which is routine), a $110 stock pulling back to a
        // $100 VWAP was handed "buy at 100, sell at 104" while trading at 110. Rejecting it
        // against `entry` alone could not see that: 104 is comfortably above 100. A target the
        // price has already passed is not a target on ANY of the three paths.
        if (target <= above) return null

        val tooLate = live && tooLateToStart(minutesLeft)
        val plan = TradePlan(
            entry = entry,
            stop = stop,
            target = target,
            exit = exitPlan(target, minutesLeft, live),
            tooLateToStart = tooLate,
            setup = setup,
            trigger = when (setup) {
                SETUP_RECLAIM ->
                    "Trading BELOW VWAP - sellers in control. No long until it reclaims " +
                        "${Fmt.price(entry)}; a buy-stop there, not here."
                SETUP_PULLBACK ->
                    "Already extended - do not chase. Buy the pullback to " +
                        "${Fmt.price(entry)} ($entryLevel); a buy-limit there, not here."
                else ->
                    "Buy the break above ${Fmt.price(entry)} ($entryLevel) - a buy-stop, " +
                        "so nothing is bought unless the move proves itself."
            },
            note = planNote(
                price, entry, risk, target, tech, rawRisk, maxRisk,
                minutesLeft, middayLull, earningsToday, vol, tooLate, targetFromRoom
            )
        )
        return plan
    }

    /** Flatten by 15:50 ET rather than 16:00 - see [exitPlan]. */
    private const val FLATTEN_BEFORE_CLOSE_MINUTES = 10

    /**
     * WHAT TO DO ONCE THE TRADE IS ON - the half a single target price cannot express.
     *
     * Two things, in the order they matter:
     *
     *  1. **Flat before the close, win or lose.** This is not advice, it is the definition of
     *     the trade: every profitable variant in the literature this feature is built on exits
     *     at the bell, and a position carried overnight is a different trade with a different
     *     risk (an overnight gap can open straight through the stop, which is an intraday order
     *     that does not exist while the market is shut). 15:50 rather than 16:00 because the
     *     exchange volatility bands that pause trading (LULD) DOUBLE from 15:35, and because
     *     the closing auction - now something like a tenth of the day's whole volume - is not
     *     where a retail market order wants to be discovering its price.
     *  2. **The target is the first objective, not necessarily the end.** See [TradePlan.exit]
     *     and [MAX_REWARD_RISK_RATIO] for the evidence that fixed targets truncate exactly the
     *     tail this kind of trade earns from. The honest instruction is therefore conditional:
     *     take it at the target if a fixed exit is what you want, or trail the stop up behind
     *     the move and let the close end it.
     *
     * DELIBERATELY NOT OFFERED: "take half at 1R and move the stop to breakeven". It is the
     * most commonly repeated intraday management rule there is and the evidence for it is
     * vendor blog backtests, not research - while the arithmetic cuts the other way. On a
     * system whose expectancy lives in a minority of large winners, halving those winners to
     * raise the win rate produces a smoother equity curve and a LOWER expected return. Adding
     * it because it is popular would be adding a number this app cannot defend.
     */
    internal fun exitPlan(target: Double, minutesLeft: Int, live: Boolean): String {
        val flat = "Day trade: be flat by 15:50 ET at the latest, win or lose - never carry it " +
            "overnight, where a gap can open straight through the stop."
        val runner = "Take profit at ${Fmt.price(target)} if you want a fixed exit. The research " +
            "behind this section says the alternative pays better on average: trail the stop up " +
            "under the move instead and let the closing bell end it, because a few trades " +
            "running far past the target are what cover the many small losers."
        val clock = when {
            !live -> ""
            minutesLeft in 1 until MIN_MINUTES_FOR_NEW_ENTRY ->
                " Only $minutesLeft minutes of the session are left - too little to start this one today."
            minutesLeft in MIN_MINUTES_FOR_NEW_ENTRY..(MIN_MINUTES_FOR_NEW_ENTRY * 2) ->
                " Only $minutesLeft minutes left - enough to start, but not for much to develop."
            else -> ""
        }
        return "$runner $flat$clock"
    }

    const val SETUP_BREAKOUT = "Breakout"
    const val SETUP_PULLBACK = "Pullback"
    const val SETUP_RECLAIM = "VWAP reclaim"

    /** Everything about a plan that should give the reader pause, in one line. */
    private fun planNote(
        price: Double,
        entry: Double,
        risk: Double,
        target: Double,
        tech: DayTradingTechnicals.DayTechnicals,
        rawRisk: Double,
        maxRisk: Double,
        minutesLeft: Int,
        middayLull: Boolean,
        earningsToday: Boolean,
        vol: Double,
        tooLate: Boolean,
        /**
         * The target came from the day's remaining RANGE, not from a price level. The two notes
         * that describe the target have to say which, or they attribute a number derived from
         * an average daily range to "the next real resistance" - a level that, in that case,
         * does not exist. Caught by code review before shipping.
         */
        targetFromRoom: Boolean
    ): String {
        val parts = ArrayList<String>(8)

        // ---- THE CLOCK, FIRST, because it can invalidate everything under it.
        if (tooLate) parts.add(
            "Too late in the session to start this - $minutesLeft minutes left, and a day trade " +
                "has to be closed before the bell"
        )

        // A trigger a long way above the last print is a different trade from the one the
        // reader thinks they are being shown - see [MAX_TRIGGER_DISTANCE_ATRS].
        if (vol > 0.0 && entry > price && (entry - price) / vol > MAX_TRIGGER_DISTANCE_ATRS) parts.add(
            "The trigger sits ${Fmt.oneDp((entry - price) / vol)} intraday ATRs above the last " +
                "price - a long way for it to travel before this even starts, so it may simply " +
                "never fill today"
        )

        // NOT A DISQUALIFIER - an earnings date is the canonical reason a stock is in play at
        // all, and the confidence checklist already refuses to treat it as bullish confirmation
        // because it can resolve either way. What it IS, for a DAY trade specifically, is an
        // order-management trap: a resting buy-stop that nobody cancelled can fill on the
        // post-release move, in a session the trader is not watching and had not planned to be
        // in at all.
        if (earningsToday) parts.add(
            "Earnings are due today - cancel any unfilled buy order before the close, or it can " +
                "fill on the after-hours reaction to a report you never planned to trade"
        )

        if (middayLull) parts.add(
            "Midday (11:30-13:30 ET) - volume, volatility and continuation are all at their " +
                "weakest of the session, so intraday breakouts fail more often through it"
        )

        // The other side of relaxing the old 3R cap: a target that needs a very large move is a
        // real reading of the levels AND a warning. See [MAX_REWARD_RISK_RATIO].
        val bigR = rewardRisk(entry, risk, target)
        if (bigR > MAX_REWARD_RISK_RATIO) parts.add(
            (if (targetFromRoom)
                "A normal day's remaining range puts the target ${Fmt.oneDp(bigR)}x the risk away"
            else
                "The next real resistance is ${Fmt.oneDp(bigR)}x the risk away") +
                " - a big ask for one session, so treat the target as where the move would run " +
                "out, not where it is expected to get"
        )

        if (tech.sessionLive && tech.or5High > 0.0 && !tech.openingBarBullish) parts.add(
            "The opening 5-minute bar did not close up - the published version of this setup " +
                "skips longs on that alone"
        )
        // THE THIN-REWARD WARNING, which the target rule above deliberately creates rather than
        // hides: when real resistance sits closer than 2R, the target is placed AT it and the
        // trade is reported as the thin one it is, instead of drawing an obedient 2:1 target
        // straight through the level that is going to stop the move.
        val rr = rewardRisk(entry, risk, target)
        if (rr in 0.0..THIN_REWARD_RATIO) parts.add(
            "Only ${Fmt.oneDp(rr)} to 1 - " +
                (if (targetFromRoom)
                    "a normal day's range does not reach far enough above this entry for a 2:1 " +
                        "target"
                else
                    "the next resistance sits closer than a 2:1 target would") +
                ", so this is a thin trade for the risk"
        )
        if (tech.sessionLive && tech.rangeUsed >= EXTENDED_RANGE_USED) parts.add(
            "Already travelled ${(tech.rangeUsed * 100).toInt()}% of its average daily range - " +
                "little room left today"
        )
        if (rawRisk > maxRisk * 1.05) parts.add(
            "The level that would invalidate this sits further away than a same-session stop " +
                "should carry, so the stop is tightened to ${MAX_RISK_ATRS}x the 5-minute ATR - " +
                "it can be taken out with the setup still intact"
        )
        if (!tech.sessionLive) parts.add(
            "Market closed - these are the last completed session's levels, to plan from before " +
                "the open, not live readings"
        )
        if (entry < price) parts.add(
            "The entry is BELOW the last price (${Fmt.price(price)}) on purpose - it waits for " +
                "the pullback instead of buying the extension"
        )
        return parts.joinToString(". ")
    }

    /**
     * Reward:risk at or below this reads as thin - shared by [planNote] and [beginnerSummary]
     * so the plain-English summary can never disagree with the technical warning under it.
     */
    private const val THIN_REWARD_RATIO = 1.5

    /**
     * Reward:risk for one trade plan - the SAME arithmetic [planNote], [beginnerSummary] and
     * the UI's own reward:risk line ([com.tj.portfolio.ui.rewardToRisk]) all need, pulled into
     * one place after a code-review pass (Round 71) found it independently reimplemented in all
     * three: a future fix to how this number is derived (the kind [com.tj.portfolio.ui
     * .rewardToRisk] itself already needed once, for a stopless plan) had to land in three spots
     * by hand, and missing one would leave the plain-English card, the technical note and the
     * dialog's own reward:risk line silently disagreeing about the same trade.
     */
    internal fun rewardRisk(entry: Double, risk: Double, target: Double): Double =
        if (risk > 1e-9) (target - entry) / risk else 0.0

    // ================================================ RELATIVE VOLUME, BY THE CLOCK (Round 73)

    /**
     * The exponent that turns "how much of the session has elapsed" into "how much of a normal
     * day's volume should have traded by now". See [expectedVolumeFraction].
     */
    private const val VOLUME_CURVE_EXPONENT = 0.7

    /**
     * WHAT SHARE OF A NORMAL DAY'S VOLUME HAS USUALLY TRADED BY THIS POINT IN THE SESSION.
     *
     * ---- WHY THIS IS NOT JUST THE CLOCK
     *
     * `ScreenRow.volumeRatio` divides volume SO FAR TODAY by a full three-month DAILY average,
     * so every threshold written against it - the 30-point ramp in [dayTrading], the "2x normal
     * volume" confirmation in [dayTradingConfidence], the screen's own admission gate - is
     * comparing a part-day number to a whole-day one and is wrong by however much of the day
     * is left. Dividing by the elapsed CLOCK fraction would fix the units and introduce a
     * different error, because intraday volume is not spread evenly: the open and the close
     * carry far more than their share of the day, which is one of the oldest documented facts
     * in market microstructure (Wood, McInish & Ord 1985; Harris 1986, and every volume profile
     * since). By 10:00 ET roughly a sixth of a typical day has already traded, against a twelfth
     * of the clock - so pacing by the clock alone would report every stock as running at twice
     * its normal rate at 10:00, and the morning list would be nothing but that artefact.
     *
     * `elapsed^0.7` is a deliberately simple stand-in for that curve. It is exact at both ends
     * (nothing at the bell, everything at the close), and between them it tracks the published
     * shape closely enough for this purpose: about 16% by 10:00, half by around noon, 90% by
     * 15:00. Where it is wrong it is wrong in the safe direction - it sits slightly ABOVE the
     * usual measured curve through the middle of the day, so the volume a stock needs to look
     * busy is if anything overstated, and the error flatters nothing.
     *
     * NOT FITTED TO ANYTHING, and should not be read as though it were. A real implementation
     * would build each symbol's own volume profile from its own history; that needs 14 days of
     * intraday bars per symbol, which is a materially heavier fetch than this section makes
     * today (noted in TASKS.md). This is the honest approximation available for free.
     */
    internal fun expectedVolumeFraction(elapsed: Double): Double {
        val f = elapsed.coerceIn(0.0, 1.0)
        if (f <= 0.0) return 0.0
        return Math.pow(f, VOLUME_CURVE_EXPONENT)
    }

    /**
     * Relative volume PROJECTED TO A FULL SESSION - "at this rate it finishes the day at N times
     * its normal volume" - which is the question every threshold in this file was already
     * written as though it were asking. See [expectedVolumeFraction].
     *
     * 0.0 before the opening bell, because nothing of today has traded and there is no rate to
     * measure. That is "not confirmed", the same as any other signal this app has not got yet -
     * never a false zero standing in for a real reading.
     */
    internal fun pacedVolumeRatio(volumeRatio: Double, sessionFraction: Double): Double {
        if (volumeRatio <= 0.0) return 0.0
        val expected = expectedVolumeFraction(sessionFraction)
        return if (expected <= 0.0) 0.0 else volumeRatio / expected
    }

    // ========================================================= POSITION SIZING (Round 73)

    /** Fraction of total equity risked on one day trade - the standard fixed-fractional rule. */
    private const val RISK_FRACTION = 0.01

    /**
     * And the share of equity ONE position may be worth, whatever that risk maths says.
     * See [positionSize] for why this cap is the load-bearing half rather than a formality.
     */
    private const val MAX_POSITION_FRACTION = 0.25

    data class PositionSize(
        /** Whole shares. 0 when even one share risks more than the budget allows. */
        val shares: Int,
        /** What is actually at risk if the stop fills - never more than the budget. */
        val riskDollars: Double,
        /** What the position costs at the trigger price. */
        val notional: Double,
        /** Why the number is what it is, when it is not simply the risk maths. Often blank. */
        val note: String
    )

    /**
     * HOW MANY SHARES - the question every plan above implies and none of them answered.
     *
     * Until now a plan said "risk is $0.42 a share" and stopped, which is only half an
     * instruction: the same $0.42 is a rounding error on one account and a serious loss on
     * another. Fixed-fractional sizing is the rule both cited papers use and the one piece of
     * this whole feature that is arithmetic rather than judgment:
     *
     *     shares = (equity x [RISK_FRACTION]) / (entry - stop)
     *
     * ---- THE NOTIONAL CAP IS THE PART THAT MATTERS, NOT THE 1%
     *
     * This is the trap the formula sets for exactly this strategy. A day-trade stop is TIGHT -
     * [MIN_RISK_ATRS] to [MAX_RISK_ATRS] of a FIVE-MINUTE ATR, often well under 1% of the share
     * price - so dividing a 1% risk budget by it produces a share count whose COST can be a
     * large multiple of the account. "Risk 1%" silently becomes "put the entire portfolio into
     * one intraday position", and the 1% only holds if the stop fills at the stop price, which
     * is the one thing a stop cannot promise: it becomes a market order when touched, and gaps,
     * halts and fast tapes are when it is touched. So the position is also capped at
     * [MAX_POSITION_FRACTION] of equity, and when that cap binds the note says so - the trade is
     * then risking LESS than the budget, which is the safe direction to be wrong in.
     *
     * NO LEVERAGE, DELIBERATELY, AND THIS IS A DEPARTURE FROM THE SOURCE. Zarattini/Barbon/Aziz
     * size to a 4x broker constraint, and their headline figures assume it. That is a levered
     * institutional-style backtest of a 20-name long/short book; this is one person's actual
     * savings in a phone app, where a 4x intraday position is not a parameter but a different
     * financial decision, and not one this app should make on his behalf or quietly assume in a
     * share count it prints. A position here never exceeds [MAX_POSITION_FRACTION] of total
     * equity, so the whole account is never implied, let alone a multiple of it.
     *
     * ---- WHAT [equity] IS, AND THE ONE THING THIS CANNOT KNOW (code-review correction)
     *
     * It is TOTAL equity - holdings at market plus cash - which is the standard base for the
     * fixed-fractional rule and what both papers size against. It is NOT buying power, and this
     * app has no way to compute that: it is a tracker, not a broker, so it does not know what is
     * settled, what is marginable, or what a given account will actually let through. An earlier
     * draft of this note claimed sizing "never exceeds the cash value of the account", which was
     * simply wrong - on a fully invested portfolio there may be no cash to buy with at all. The
     * share count is a RISK answer, not a confirmation that the trade can be funded, and the
     * note below says so on screen rather than leaving it to be inferred.
     *
     * Null when [equity] is not known (no portfolio loaded yet) or the levels are not a trade -
     * the same "a blank is honest, a fabricated number is not" rule [tradePlan] follows.
     */
    fun positionSize(
        equity: Double,
        entry: Double,
        stop: Double,
        riskFraction: Double = RISK_FRACTION
    ): PositionSize? {
        if (equity <= 0.0 || entry <= 0.0 || stop <= 0.0 || stop >= entry) return null
        val riskPerShare = entry - stop
        val budget = equity * riskFraction
        val byRisk = floor(budget / riskPerShare).toInt()
        if (byRisk < 1) return PositionSize(
            shares = 0,
            riskDollars = 0.0,
            notional = 0.0,
            note = "One share risks ${Fmt.price(riskPerShare)}, which is more than the " +
                "${Fmt.pct(riskFraction * 100)} of the portfolio this sizing allows for a single " +
                "day trade. Skip it rather than size up - the stop is what makes the plan a plan."
        )
        val byCost = floor(equity * MAX_POSITION_FRACTION / entry).toInt()
        val shares = minOf(byRisk, byCost)
        if (shares < 1) return PositionSize(
            shares = 0,
            riskDollars = 0.0,
            notional = 0.0,
            note = "One share costs ${Fmt.price(entry)} - more than the " +
                "${Fmt.pct(MAX_POSITION_FRACTION * 100)} of the portfolio one position is capped at."
        )
        val capped = byCost < byRisk
        return PositionSize(
            shares = shares,
            riskDollars = shares * riskPerShare,
            notional = shares * entry,
            note = if (capped)
                "Capped at ${Fmt.pct(MAX_POSITION_FRACTION * 100)} of the portfolio for one " +
                    "position. The stop on this one is tight enough that a full " +
                    "${Fmt.pct(riskFraction * 100)} risk would have meant buying " +
                    "${Fmt.usd(byRisk * entry)} of it - risking less than the budget here, not more."
            else ""
        )
    }

    // ======================================================= THE BEGINNER SUMMARY (Round 71)

    /**
     * ONE PLAIN-ENGLISH VERDICT ON TOP OF [tradePlan]'S NUMBERS, FOR A READER WHO DOES NOT KNOW
     * WHAT "VWAP", "RECLAIM" OR "R1 PIVOT" MEAN.
     *
     * Tj, 2026-09-11: *"add a summary of what to do and why that is simple to read for complete
     * beginners who don't understand market technical language (for example, 'buy this at
     * $3.56, and sell at $3.98' or 'too late for this one, don't buy') plus any reasoning in
     * simple language for beginners."*
     *
     * THIS NEVER COMPUTES A NEW NUMBER OR A NEW JUDGMENT. It restates [entry]/[stop]/[target] -
     * the same three prices the technical grid already shows - in a sentence a beginner can act
     * on. Its "too late" / "skip" cases are read directly off those same numbers rather than a
     * separate opinion: a plan the app is still showing as live, sitting next to a plain-English
     * summary calling it "too late", would be a worse bug than not having the summary at all. So
     * every branch here is a direct comparison of [price] against the levels [tradePlan] already
     * computed - never a new signal, a new threshold Tj hasn't seen, or a claim the technical
     * section disagrees with. [THIN_REWARD_RATIO] is shared with [planNote] for the same reason:
     * the two pieces of text may describe the same trade differently, but never contradict it.
     *
     * Null when there is no plan to summarise, OR when [price] itself is not known yet - the
     * same "a blank is honest, a fabricated one is not" rule [tradePlan] itself follows for a
     * row with no real levels. A DAY-TRADING ROW CAN HAVE LEVELS WITH NO PRICE (Round 71 review
     * fix): `PortfolioViewModel.applyDayTradingAnswer` can publish a Claude-imported pick before
     * its price fill resolves, and `levelsUsable` deliberately leaves such a row's levels intact
     * while `price <= 0.0` ("no price to sanity-check... taken on trust"). Without this guard
     * every branch below that compares [price] against a level would silently fall through to
     * the default "Buy if it climbs..." case for a stock whose current price the app has not
     * actually fetched - a confident instruction built from a placeholder zero.
     */
    data class BeginnerSummary(
        /** The one-line instruction - "Buy if it climbs to $12.40, then sell at $13.10..." */
        val headline: String,
        /** One or two sentences of why, in plain language - no jargon. */
        val explanation: String,
        /** True for a "too late" / "skip" verdict, so the UI can de-emphasise it. */
        val skip: Boolean
    )

    fun beginnerSummary(
        symbol: String,
        price: Double,
        entry: Double,
        stop: Double,
        target: Double,
        /**
         * [TradePlan.tooLateToStart] - and it MUST reach this function (Round 73).
         *
         * The grid above this summary now prints "TOO LATE TO START TODAY" when the session has
         * too little left. Without this parameter the plain-English card underneath it would go
         * on saying "Buy if it climbs to $12.40, then sell at $13.10" in the same breath - the
         * exact contradiction this function's own header calls "a worse bug than not having the
         * summary at all", and the beginner reading the simple sentence is precisely the reader
         * least equipped to notice the technical line above disagreeing with it.
         */
        tooLateToStart: Boolean = false
    ): BeginnerSummary? {
        if (price <= 0.0 || entry <= 0.0 || stop <= 0.0 || target <= 0.0) return null
        val risk = entry - stop
        val rr = rewardRisk(entry, risk, target)
        val thin = if (rr in 0.0..THIN_REWARD_RATIO)
            " Heads up: the likely profit here is small next to the risk, so even experienced " +
                "traders might pass on this particular one."
        else ""

        return when {
            // THE CLOCK BEATS EVERY OTHER BRANCH, because it is the only one that can be true
            // while all the prices still look perfectly reasonable. A day trade has to be
            // closed before the market shuts, so with minutes left there is no version of this
            // that is worth starting - whatever the levels say.
            tooLateToStart -> BeginnerSummary(
                headline = "Not today - there isn't enough time left.",
                explanation = "This kind of trade has to be finished before the market closes, " +
                    "and there isn't enough of today left for it to work out. The prices here " +
                    "are still worth a look tomorrow, but don't start it now.",
                skip = true
            )
            // The price already reached the profit target - most of the likely gain is gone.
            price >= target -> BeginnerSummary(
                headline = "Too late for this one today - don't buy now.",
                explanation = "$symbol already climbed to the price this plan was hoping it " +
                    "would reach. Buying now means paying close to the top, with much less " +
                    "room left for it to go up and just as much room for it to fall.",
                skip = true
            )
            // The price already fell through the level that would have kept the plan valid.
            price <= stop -> BeginnerSummary(
                headline = "Skip this one - the plan already fell apart.",
                explanation = "$symbol already dropped through the price this plan needed to " +
                    "hold. The original reason to buy it doesn't apply anymore today.",
                skip = true
            )
            // THE ENTRY IS ALREADY REACHED (Round 71 review fix). [tradePlan] always computes
            // entry strictly on the far side of the price it was built from - above it for a
            // breakout or a VWAP reclaim, below it for a pullback - so entry == price never
            // happens at the instant a plan is actually built. But this row's live price and
            // its plan are not always read at that same instant, and guessing a "climb" or
            // "drop" direction from a price sitting exactly ON the level would be a coin flip
            // that is right for a breakout and backwards for a pullback. Say the one thing that
            // is true regardless of which setup this is, instead.
            price == entry -> BeginnerSummary(
                headline = "It's at the buy price right now (${Fmt.price(entry)}) - sell at " +
                    "${Fmt.price(target)} for a profit.",
                explanation = "This is the exact level the plan was watching for. If it falls " +
                    "to ${Fmt.price(stop)} instead of going up, sell there too to keep a loss " +
                    "small.$thin",
                skip = false
            )
            else -> {
                val climbing = entry > price
                val direction = if (climbing)
                    "It hasn't proven the move is real yet, so this waits for it to climb a " +
                        "little higher first - buying too early risks jumping in before " +
                        "anything has actually happened."
                else
                    "It has already jumped up fast, so buying at today's price would mean " +
                        "paying a premium - this waits for it to cool off and come back down " +
                        "a bit first, for a better price."
                BeginnerSummary(
                    headline = (if (climbing) "Buy if it climbs to " else "Buy if it drops to ") +
                        "${Fmt.price(entry)}, then sell at ${Fmt.price(target)} for a profit.",
                    explanation = "$direction If it falls to ${Fmt.price(stop)} instead of " +
                        "going up, sell there too - that keeps a loss small instead of " +
                        "letting it grow.$thin",
                    skip = false
                )
            }
        }
    }

    // ================================================== LIKELIHOOD x CONFIDENCE (Round 72)

    /**
     * HOW MANY INDEPENDENT SIGNALS ACTUALLY CONFIRM THE BULLISH CASE, OUT OF A FIXED CHECKLIST -
     * not a probability, and never claimed as one; the closest honest proxy this app can compute
     * for "how sure is this call" from public data. See [dayTrading]'s header for why a genuine
     * forecast is not achievable at all - this measures AGREEMENT among real signals, not the
     * odds of an outcome.
     *
     * Tj, 2026-09-11: *"make the scores reflect a blend of how likely the stock is to rise in
     * value from its target buy price and how confident this prediction is... a score of 100
     * means the stock is very likely to raise in value... and that the model is extremely
     * confident that this will happen."* [dayTrading] (plus [withTechnicals]) already computes
     * the "how likely" half - the standard volume/momentum/breakout continuation signals the
     * day-trading literature cited throughout this file points to. What it never produced is a
     * genuine, independent CONFIDENCE number: [Scored.confidence] exists but only ever measured
     * data completeness, and both call sites that produce a `Scored` for a Day Trading row
     * discard it before it reaches the screen. This is that missing half.
     *
     * FIVE FIXED CHECKS, EACH COUNTED ONLY WHEN ACTUALLY CONFIRMED - never when merely unknown.
     * A signal this app has not fetched yet (VWAP and the opening range, before the live
     * technicals sweep runs) counts as "not confirmed," the same as a signal that was checked
     * and came back negative - NOT as "skip this check," which would let an early row reach the
     * same confidence as a fully-enriched one on a fifth of the evidence. The denominator is
     * always the full five, so confidence is honestly lower before the fuller picture has
     * arrived, and only rises as real confirmations arrive - never the other way round.
     *
     *  1. Heavy relative volume - the single best "is this real" proxy [dayTrading] itself leads
     *     with.
     *  2. The move is already real - up a meaningful amount today, not just loud.
     *  3. Trading above VWAP - buyers in control this session (once VWAP has been fetched).
     *  4. A CONFIRMED opening-range breakout - not just a high opening range, one price has
     *     actually broken above (once the opening range has completed and been fetched).
     *  5. Structural strength - near the 52-week high, or a genuine short-squeeze shape (heavy
     *     volume AND a real move on a heavily-shorted name, not membership alone).
     *
     * DELIBERATELY EXCLUDED: an upcoming earnings print ([dayTrading]'s own catalyst bonus) can
     * send a stock either direction, so it is not bullish confirmation of anything; WSB/news
     * mention volume is chatter, and [dayTrading]'s own comment on that input already says "day
     * trading needs the move and the volume to be REAL first... chatter alone describes what
     * people are saying, not what the tape is doing" - counting it here would let loud, unproven
     * talk buy confidence the tape has not earned.
     */
    fun dayTradingConfidence(
        r: ScreenRow,
        tech: DayTradingTechnicals.DayTechnicals? = null,
        /** See [dayTrading]'s parameter of the same name, and [pacedVolumeRatio]. */
        sessionFraction: Double = 1.0
    ): Int {
        var confirmed = 0
        // PACED, LIKE THE SCORE ITSELF. Testing a half-day volume figure against a whole-day
        // "2x normal" threshold meant this check could not confirm before the early afternoon
        // however busy the stock was - so the confidence half of the score, and through it the
        // blended score on screen, was systematically depressed all morning for reasons that had
        // nothing to do with the stock.
        val rvol = pacedVolumeRatio(r.volumeRatio, sessionFraction)
        if (rvol >= 2.0) confirmed++
        if (r.changePct.isFinite() && r.changePct >= 3.0) confirmed++
        val squeeze = Screener.Lists.MOST_SHORTED in r.lists &&
            rvol >= 2.0 && r.changePct >= 3.0
        val nearHigh = r.rangePos in 0.0..1.0 && r.rangePos > 0.85
        if (squeeze || nearHigh) confirmed++
        return confirmed * 100 / CONFIRMATION_CHECKS + technicalConfirmationBonus(r.price, tech)
    }

    private const val CONFIRMATION_CHECKS = 5

    /**
     * Checks 3 and 4 of [dayTradingConfidence]'s checklist (trading above VWAP, a confirmed
     * opening-range breakout), split out on their own points scale (0, 20 or 40) so a caller
     * that no longer has a [ScreenRow] - only a [ResearchRow], once a row has been built and the
     * raw relative-volume/52-week-range/most-shorted facts checks 1, 2 and 5 need are gone - can
     * still add just the technicals half once real readings arrive, instead of re-deriving all
     * five checks from data it no longer has. See
     * [com.tj.portfolio.ui.PortfolioViewModel.enrichDayTradingVisible]'s merge for the one place
     * this is used, and why it is safe there (an at-most-once gate on the caller's side, so the
     * base it adds this to never already has a technicals contribution baked in).
     */
    internal fun technicalConfirmationBonus(
        price: Double,
        tech: DayTradingTechnicals.DayTechnicals?
    ): Int {
        if (tech == null) return 0
        var confirmed = 0
        if (tech.vwap > 0.0 && price > tech.vwap) confirmed++
        if (tech.openingRangeComplete && tech.openingRangeHigh > 0.0 && price > tech.openingRangeHigh)
            confirmed++
        return confirmed * 100 / CONFIRMATION_CHECKS
    }

    /**
     * THE SCORE TJ ASKED FOR: how likely this stock is to rise, blended with how confident that
     * call is - multiplicatively, so it takes BOTH being high to reach 100, matching his own
     * example directly. A stock with every bullish signal firing but only two of five
     * confirmations checkable yet (score 90, confidence 40%) reads 36, not 90 - the low
     * confidence pulls the displayed number down rather than being a footnote beside a
     * still-impressive-looking score, which is the whole point of blending them instead of
     * showing them side by side unreduced.
     */
    fun blendedScore(likelihood: Int, confidence: Int): Int =
        (likelihood * confidence / 100).coerceIn(0, 100)

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

    // ------------------------------------------------------- day-trading technicals overlay

    /**
     * Fold real intraday technicals into a day-trading score computed with none - same
     * separation-of-concerns reason [withAnalyst] exists apart from [best]: [dayTrading] runs
     * at build time with zero extra requests, and [DayTradingTechnicals.fetch] only answers
     * later, for the rows actually on screen. See [DayTradingTechnicals]'s header for the
     * research behind VWAP position and the opening-range breakout as day-trading signals.
     *
     * ONLY ADDS, NEVER SUBTRACTS - the same rule every other reason line in [dayTrading]
     * follows: a condition that is not met earns nothing and prints nothing, rather than a
     * "why this DIDN'T score" line no other line in this list has a counterpart for.
     */
    fun withTechnicals(base: Scored, tech: DayTradingTechnicals.DayTechnicals, price: Double): Scored {
        if (tech.isEmpty || price <= 0.0) return base
        var s = base.score.toDouble()
        val why = ArrayList(base.reasons)
        // SAME SESSION-WORDING RULE AS [dayTrading]'s reason lines, and it needs no parameter
        // here: `tech` carries [DayTradingTechnicals.DayTechnicals.sessionLive] itself, so this
        // stays a pure function of its inputs while still refusing to say "today" about a
        // session that ended hours ago.
        val word = if (tech.sessionLive) "today" else "in the last session"
        if (tech.vwap > 0.0 && price > tech.vwap) {
            s += 8.0
            why.add(
                "Trading above its session VWAP (${Fmt.price(tech.vwap)}) - buyers in control $word"
            )
        }
        if (tech.openingRangeComplete && tech.openingRangeHigh > 0.0 && price > tech.openingRangeHigh) {
            s += 12.0
            why.add(
                "Broke above its opening-range high (${Fmt.price(tech.openingRangeHigh)}) on " +
                    "the first 30 minutes' volume - a classic opening-range breakout"
            )
        }
        return Scored(s.coerceIn(0.0, 100.0).toInt(), why, min(100, base.confidence + 10))
    }

    // ------------------------------------------------------------- HOLDING

    /**
     * Everything [holding] needs about one symbol already on TJ's board, all of it numbers
     * the app fetches anyway for the Overview/Stats/Analysts tabs ([FundamentalsFeed.core]).
     * No new provider, no new request - see the note on [Recommend] in `net/Recommend.kt`.
     */
    data class HoldingInput(
        val price: Double,
        val consensus: Consensus? = null,
        val values: Map<String, Double> = emptyMap()
    )

    /**
     * BUY / HOLD / SELL for a position ALREADY OWNED OR WATCHED - a different question from
     * [best]'s "is this worth buying fresh", and the difference is the whole reason this is a
     * separate function rather than a third state bolted onto that one: [best] has no HOLD,
     * because "don't buy it" and "don't buy MORE of it" are not the same sentence, and every
     * one of its terms is phrased as a case FOR buying. Here the natural, honest default is a
     * HOLD - TJ is not being asked whether to open a position, only whether to change one he
     * already has - so the scale is CENTERED AT 50 rather than starting at 0, and every term
     * below moves it up or down from there rather than only ever adding to it.
     *
     * Weighted toward the analyst consensus and its price target (up to +-30 and +-12.5)
     * because that is the one signal here that is *already* three-way buy/hold/sell, from
     * people paid to watch this stock full time, and it is literally the thing TJ asked for -
     * "professional analysts, target prices". Valuation, growth and a year of relative
     * performance fill in the rest, and a couple of balance-sheet red flags can only ever
     * subtract - a stretched balance sheet is a reason for caution, never a reason to buy.
     *
     * DEGRADES HONESTLY, same rule as [best]: a field that is absent scores nothing for its
     * term rather than being guessed at, [confidence] says how much of the picture was
     * actually there, and a stock with almost nothing published lands at exactly 50 - a HOLD,
     * with a reason list that says why it could not move either way. That is the correct
     * answer for thin data, not a coin flip dressed up as one.
     */
    fun holding(input: HoldingInput): Scored {
        val why = ArrayList<String>()
        var s = 50.0
        var have = 0
        var want = 0
        val v = input.values
        val price = input.price
        val c = input.consensus

        // --- analyst verdict (+-30): three-way already, from professional coverage.
        want++
        if (c != null && c.hasVotes) {
            have++
            val buyVotes = c.strongBuy + c.buy
            val sellVotes = c.sell + c.strongSell
            val lean = (buyVotes - sellVotes).toDouble() / c.votes
            s += lean * 30.0
            val lab = c.meanLabel.ifBlank { "Mixed" }
            why.add(
                "$lab consensus - $buyVotes buy / ${c.hold} hold / $sellVotes sell across " +
                    "${c.votes} analysts"
            )
        }

        // --- price vs. target (+-12.5): "for how much", the number TJ asked for by name.
        want++
        if (c != null && c.hasTarget && price > 0.0) {
            val up = c.upsidePct(price)
            if (up != null) {
                have++
                s += ramp(up, -30.0, 30.0, 25.0) - 12.5
                why.add(
                    if (up >= 0)
                        "Average analyst target ${Fmt.price(c.targetMean)} - ${pct(up)} above today"
                    else
                        "Average analyst target ${Fmt.price(c.targetMean)} - ${pct(-up)} BELOW today"
                )
            }
        }

        // --- valuation (+-20): PEG where it exists, since it already prices in growth;
        // forward P/E against a plain reasonable-multiple band otherwise.
        want++
        val peg = v["pegRatio"]
        val fwdPe = v["peForward"]
        if (peg != null && peg > 0.0) {
            have++
            val pts = ((1.5 - peg) * 10.0).coerceIn(-20.0, 20.0)
            s += pts
            why.add(
                "PEG ratio ${Fmt.priceBare(peg)} - " + when {
                    peg <= 1.0 -> "cheap for its growth"
                    peg <= 2.0 -> "reasonably priced for its growth"
                    else -> "expensive relative to its growth"
                }
            )
        } else if (fwdPe != null && fwdPe > 0.0) {
            have++
            val pts = ((25.0 - fwdPe) * (20.0 / 25.0)).coerceIn(-20.0, 20.0)
            s += pts
            why.add(
                "Forward P/E ${Fmt.priceBare(fwdPe)}" +
                    if (fwdPe <= 20.0) " - reasonable" else " - rich, priced for continued strength"
            )
        }

        // --- growth (+-15): earnings growth first, revenue as the fallback.
        want++
        val eg = v["earningsGrowth"]
        val rg = v["revenueGrowth"]
        val g = eg ?: rg
        if (g != null) {
            have++
            s += (g * 100.0).coerceIn(-20.0, 20.0) * 0.75
            val label = if (eg != null) "Earnings" else "Revenue"
            why.add(
                if (g >= 0) "$label growing ${pct(g * 100.0)} year over year"
                else "$label shrinking ${pct(-g * 100.0)} year over year"
            )
        }

        // --- a year of performance against the market (+-15).
        want++
        val chg = v["change52Week"]
        if (chg != null) {
            have++
            val sp = v["sp500Change52Week"]
            val relPct = (chg - (sp ?: 0.0)) * 100.0
            s += relPct.coerceIn(-20.0, 20.0) * 0.75
            why.add(
                "Up ${pct(chg * 100.0)} over the past year" +
                    if (sp != null) " vs ${pct(sp * 100.0)} for the S&P 500" else ""
            )
        }

        // --- balance-sheet red flags: subtract only. A stretched balance sheet is a reason
        // for caution, never a reason in favour of buying more.
        val ptb = v["priceToBook"]
        if (ptb != null && ptb < 0.0) {
            s -= 10.0
            why.add("Negative book value - liabilities exceed assets on the balance sheet")
        }
        val dte = v["debtToEquity"]
        if (dte != null && dte > 200.0) {
            s -= 8.0
            why.add("High leverage - debt/equity over ${Fmt.priceBare(dte)}%")
        }
        val short = v["shortPercentOfFloat"]
        if (short != null && short > 0.15) {
            s -= 6.0
            why.add("Elevated short interest (${pct(short * 100.0)} of float) - added volatility")
        }

        if (why.isEmpty()) why.add("Not enough public data yet to form a view either way")

        return Scored(s.coerceIn(0.0, 100.0).toInt(), why, confidence(have, want))
    }

    /**
     * Score to verdict. A dead band around the neutral midpoint on purpose: [holding] centers
     * at 50 and moves from there in both directions, so anything that could not clear a real
     * margin either way is exactly the case for staying put, not a coin flip rounded to a side.
     */
    fun verdictFor(score: Int): TradeVerdict = when {
        score >= 63 -> TradeVerdict.BUY
        score <= 37 -> TradeVerdict.SELL
        else -> TradeVerdict.HOLD
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
