package com.tj.portfolio.net

import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.Consensus2
import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.data.TradeVerdict
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
        catalystSoon: Boolean = false
    ): Scored {
        val why = ArrayList<String>()
        var s = 0.0
        var have = 0
        var want = 0

        // --- relative volume (0-30): the single best "is this actually in play today" proxy
        // the published day-trading literature points to - see the class header.
        want++
        val rvol = r.volumeRatio
        if (rvol > 0) {
            have++
            s += ramp(rvol, 1.0, 5.0, 30.0)
            if (rvol >= 2.0) why.add(
                "Trading at ${Fmt.priceBare(rvol)}x its normal volume today" +
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
            if (r.changePct >= 3.0) why.add("Up ${pct(r.changePct)} already today")
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
        val note: String = ""
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

    /** The standard "at least 2:1" reward:risk floor, and the ceiling on projecting one day. */
    private const val TARGET_REWARD_RISK_RATIO = 2.0
    private const val MAX_REWARD_RISK_RATIO = 3.0

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
     *  4. **Target.** The nearest real resistance above entry, because that is where the move
     *     runs into supply. Floored at [TARGET_REWARD_RISK_RATIO]R when there is clear air
     *     above, capped at [MAX_REWARD_RISK_RATIO]R, and capped again by how much of the day's
     *     average range is left. When the nearest resistance is closer than 2R the target is
     *     placed AT IT and the thin reward is reported rather than a 2R target being drawn
     *     straight through a wall.
     *
     * Null when there is nothing real to build from - no price, or neither an intraday nor a
     * daily ATR. The caller then shows no plan at all, which is the honest output: the levels
     * ARE the feature, and a fabricated one is worse than a blank.
     */
    fun tradePlan(price: Double, tech: DayTradingTechnicals.DayTechnicals): TradePlan? {
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

        // ---- 4: the target.
        val nearestAbove = overhead
            .filter { it.price > entry + risk * 0.3 }
            .minByOrNull { it.price }
        val standard = entry + risk * TARGET_REWARD_RISK_RATIO
        // "How much room is left in the day" is a live-session question - the same reason the
        // extension checks above are gated on `live`. Yesterday's low plus a normal day's range
        // is not a ceiling on tomorrow.
        val ceiling = if (live && tech.adr > 0.0 && tech.sessionLow > 0.0) tech.sessionLow + tech.adr
        else Double.MAX_VALUE
        val target = when {
            nearestAbove == null -> standard
            nearestAbove.price < standard -> nearestAbove.price
            else -> minOf(
                nearestAbove.price,
                entry + risk * MAX_REWARD_RISK_RATIO,
                if (ceiling >= standard) ceiling else Double.MAX_VALUE
            )
        }
        if (target <= entry) return null

        val plan = TradePlan(
            entry = entry,
            stop = stop,
            target = target,
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
            note = planNote(price, entry, risk, target, tech, rawRisk, maxRisk)
        )
        return plan
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
        maxRisk: Double
    ): String {
        val parts = ArrayList<String>(4)
        // THE THIN-REWARD WARNING, which the target rule above deliberately creates rather than
        // hides: when real resistance sits closer than 2R, the target is placed AT it and the
        // trade is reported as the thin one it is, instead of drawing an obedient 2:1 target
        // straight through the level that is going to stop the move.
        val rr = if (risk > 1e-9) (target - entry) / risk else 0.0
        if (rr in 0.0..1.5) parts.add(
            "Only ${Fmt.oneDp(rr)} to 1 - the next resistance sits closer than a 2:1 target " +
                "would, so this is a thin trade for the risk"
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
        if (tech.vwap > 0.0 && price > tech.vwap) {
            s += 8.0
            why.add(
                "Trading above its session VWAP (${Fmt.price(tech.vwap)}) - buyers in control today"
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
