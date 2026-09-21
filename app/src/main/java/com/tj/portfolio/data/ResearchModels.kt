package com.tj.portfolio.data

import com.tj.portfolio.util.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE RESEARCH TAB'S DATA MODEL (Round 54).
 *
 * Three sections - what the market is TALKING about, what the numbers say is worth BUYING,
 * and the FUNDS worth holding - built from free, keyless feeds and scored by the
 * app itself. Claude never decides the ranking; it explains one the app can already justify
 * line by line. That split is deliberate and TJ chose it: a score the app computes can be
 * reproduced, tested and shown its own workings ([ResearchRow.reasons]), where a score a
 * language model invents cannot be checked against anything.
 *
 * Everything here is a plain data class with an explicit JSON codec, because the same shape
 * has to survive three round trips:
 *   1. app -> SQLite cache -> app       (so the tab opens instantly and works offline)
 *   2. app -> prompt file -> Claude app (the no-API-key bridge)
 *   3. Claude's reply file -> app       (the import that fills in the explanations)
 */

/** One row of a Yahoo predefined screener, with every field the scorers actually read. */
data class ScreenRow(
    val symbol: String,
    val name: String = "",
    val price: Double = 0.0,
    val prevClose: Double = 0.0,
    val changePct: Double = 0.0,
    val marketCap: Double = 0.0,
    val volume: Double = 0.0,
    val avgVolume3M: Double = 0.0,
    val forwardPe: Double = 0.0,
    val trailingPe: Double = 0.0,
    val priceToBook: Double = 0.0,
    val epsTtm: Double = 0.0,
    val epsForward: Double = 0.0,
    val epsCurrentYear: Double = 0.0,
    val fiftyDayAvg: Double = 0.0,
    val twoHundredDayAvg: Double = 0.0,
    val fiftyTwoWeekHigh: Double = 0.0,
    val fiftyTwoWeekLow: Double = 0.0,
    val fiftyTwoWeekChangePct: Double = 0.0,
    val dividendYield: Double = 0.0,
    val earningsAt: Long = 0L,
    val earningsEstimated: Boolean = false,
    val exchange: String = "",
    /** Which predefined screeners this symbol turned up in - itself a signal. */
    val lists: Set<String> = emptySet()
) {
    /** Where the price sits between the 52-week low (0.0) and high (1.0). */
    val rangePos: Double
        get() {
            val span = fiftyTwoWeekHigh - fiftyTwoWeekLow
            return if (span > 1e-9 && price > 0) ((price - fiftyTwoWeekLow) / span).coerceIn(0.0, 1.0)
            else -1.0
        }

    /** Forward EPS growth as a fraction, only when both sides are positive and meaningful. */
    val epsGrowth: Double
        get() = if (epsTtm > 0.01 && epsForward > 0.0) (epsForward - epsTtm) / epsTtm else Double.NaN

    val volumeRatio: Double
        get() = if (avgVolume3M > 1000) volume / avgVolume3M else 0.0

    fun merge(other: ScreenRow): ScreenRow = ScreenRow(
        symbol = symbol,
        name = name.ifBlank { other.name },
        price = if (price > 0) price else other.price,
        prevClose = if (prevClose > 0) prevClose else other.prevClose,
        changePct = if (changePct != 0.0) changePct else other.changePct,
        marketCap = if (marketCap > 0) marketCap else other.marketCap,
        volume = if (volume > 0) volume else other.volume,
        avgVolume3M = if (avgVolume3M > 0) avgVolume3M else other.avgVolume3M,
        forwardPe = if (forwardPe != 0.0) forwardPe else other.forwardPe,
        trailingPe = if (trailingPe != 0.0) trailingPe else other.trailingPe,
        priceToBook = if (priceToBook != 0.0) priceToBook else other.priceToBook,
        epsTtm = if (epsTtm != 0.0) epsTtm else other.epsTtm,
        epsForward = if (epsForward != 0.0) epsForward else other.epsForward,
        epsCurrentYear = if (epsCurrentYear != 0.0) epsCurrentYear else other.epsCurrentYear,
        fiftyDayAvg = if (fiftyDayAvg > 0) fiftyDayAvg else other.fiftyDayAvg,
        twoHundredDayAvg = if (twoHundredDayAvg > 0) twoHundredDayAvg else other.twoHundredDayAvg,
        fiftyTwoWeekHigh = if (fiftyTwoWeekHigh > 0) fiftyTwoWeekHigh else other.fiftyTwoWeekHigh,
        fiftyTwoWeekLow = if (fiftyTwoWeekLow > 0) fiftyTwoWeekLow else other.fiftyTwoWeekLow,
        fiftyTwoWeekChangePct = if (fiftyTwoWeekChangePct != 0.0) fiftyTwoWeekChangePct
        else other.fiftyTwoWeekChangePct,
        dividendYield = if (dividendYield != 0.0) dividendYield else other.dividendYield,
        earningsAt = if (earningsAt > 0) earningsAt else other.earningsAt,
        // Read from whichever side's earningsAt actually survives above, not ANDed blindly -
        // otherwise a real date from `other` inherits this side's flag regardless of whether
        // this side even had a date to be estimated or confirmed.
        earningsEstimated = if (earningsAt > 0) earningsEstimated else other.earningsEstimated,
        exchange = exchange.ifBlank { other.exchange },
        lists = lists + other.lists
    )
}

/** Analyst consensus for one symbol, as Nasdaq publishes it. */
data class Consensus2(
    val buy: Int = 0,
    val hold: Int = 0,
    val sell: Int = 0,
    val target: Double = 0.0
) {
    val total: Int get() = buy + hold + sell
    val buyShare: Double get() = if (total > 0) buy.toDouble() / total else -1.0
    val sellShare: Double get() = if (total > 0) sell.toDouble() / total else -1.0
    fun upsidePct(price: Double): Double =
        if (target > 0 && price > 0) (target - price) / price * 100.0 else Double.NaN

    fun label(): String = when {
        total == 0 -> ""
        buyShare >= 0.75 -> "Strong Buy"
        buyShare >= 0.55 -> "Buy"
        sellShare >= 0.30 && sellShare > buyShare -> "Sell"
        else -> "Hold"
    }
}

/**
 * One row in a Research section.
 *
 * [reasons] is the app's own arithmetic, written out in plain English; [why] is Claude's
 * paragraph and stays empty until an API call or an imported reply fills it in. They are
 * separate fields on purpose - the row must still say something useful with no Claude at all.
 */
data class ResearchRow(
    val symbol: String,
    val name: String = "",
    val price: Double = 0.0,
    val changePct: Double = 0.0,
    /** 0-100, the app's own score for the section this row belongs to. */
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    val why: String = "",
    /**
     * WHEN [why] WAS LAST WRITTEN BY CLAUDE - not when this row was last built or cached.
     *
     * `why` used to carry forward across every rebuild on nothing but a blank check, so a
     * paragraph written once could ride along by symbol match indefinitely - weeks past the
     * point Tj had last run any Claude analysis at all, with nothing on screen distinguishing
     * it from a paragraph written five minutes ago. This is the clock that carry-forward and
     * eviction (`carryExplanations`/`carryEtfExplanations` in PortfolioViewModel.kt) now check
     * before treating [why] as current. 0 means "no timestamp" - either `why` is blank, or
     * this row predates the field and its age is unknown, which the staleness check treats the
     * same as "too old": unknown is not evidence of current.
     */
    val whyAt: Long = 0L,
    // --- trending only
    val mentions: Int = 0,
    val mentionDelta: Int = 0,
    val rankDelta: Int = 0,
    val sentiment: String = "",
    val newsCount: Int = 0,
    val headline: String = "",
    val headlineUrl: String = "",
    val headlineSource: String = "",
    val onYahooTrending: Boolean = false,
    // --- best
    val consensus: Consensus2? = null,
    val catalyst: String = "",
    /**
     * CLAUDE'S CONVICTION, 1-10, KEPT OUT OF [score] (Round 66 audit, R1).
     *
     * THE BUG THIS FIXES. The bridge used to write `conviction * 10` straight into `score`,
     * and the card draws `score` inside a circle labelled SCORE with the accessibility text
     * "Score N out of 100". So a fund Claude ADDED - one the app never screened and has no
     * numbers for - could arrive as row 1 of the ETF list showing SCORE 100, above every fund
     * the app actually measured, with no facts grid and no reason lines. Nothing on screen
     * separated a 100 computed from a five-year NAV return and an expense ratio from a 100 a
     * language model asserted. That is the one thing this app's design note says must never
     * happen, on the list TJ said he is going to buy from.
     *
     * `score` is now the app's arithmetic and nothing else - zero for a row the app did not
     * score. This orders those rows among themselves, and the card shows it as "CLAUDE n/10",
     * which is visibly a different scale from a different source.
     */
    val conviction: Int = 0,
    // ---- `followed` WAS HERE AND WAS DEAD (Round 66 audit, RES-4).
    //
    // Its KDoc said "True when the user already holds or watches this symbol - shown as a
    // chip", and nothing wrote it, nothing read it, and it was not in the JSON codec, so it
    // would not have survived the cache either. The FOLLOWING chip really comes from
    // `ResearchCard`'s own `followed` PARAMETER, which the screen computes as
    // `r.symbol in (watchedSymbols() + heldSymbols())`. A field that documents a behaviour it
    // does not have is worse than no field: the next person to build a row sets it, sees no
    // chip, and goes looking for the bug somewhere real.
    /**
     * The fund numbers, for rows in the ETF section (Round 63). Null for a stock.
     *
     * CARRIED ON THE SAME ROW TYPE rather than in a parallel model, so the ETF list gets the
     * cache, the Claude bridge, the merge, the de-duplication and the card layout that the
     * stock sections already have - and so a change to any of those cannot fix one list and
     * forget another.
     */
    val etf: EtfFacts? = null,
    /**
     * A structured RISK PLAN, not a prediction - the day-trading section only (Round 67).
     *
     * TJ asked for "a target buy price and target sell price". Research says genuinely
     * accurate same-day direction prediction is not achievable from public data (market
     * efficiency; see the note on [com.tj.portfolio.net.ResearchScore.dayTrading] and
     * `net/DayTradingBridge.kt`'s header) - so these three are NOT a forecast of where the
     * price will go. They are the entry, stop-loss and profit-target an ordinary day-trading
     * risk plan would set from real intraday structure. All zero for every row outside the
     * day-trading section.
     *
     * ROUND 69: [entryPrice] IS A TRIGGER LEVEL, NOT THE CURRENT PRICE. It used to be exactly
     * the last traded price, which Tj correctly called out as not being how day traders
     * operate - see [com.tj.portfolio.net.ResearchScore.TradePlan]'s header. It is now the
     * price a buy-stop or buy-limit would sit at, and [setup]/[trigger] say which and why, so
     * an entry deliberately above or below the last price cannot read as a stale number.
     */
    val entryPrice: Double = 0.0,
    val stopPrice: Double = 0.0,
    val targetPrice: Double = 0.0,
    /** Which setup produced the levels - "Breakout", "Pullback", "VWAP reclaim". */
    val setup: String = "",
    /** The entry instruction in plain English, e.g. "Buy the break above $12.40...". */
    val trigger: String = "",
    /** Anything about the plan that should give the reader pause. Often blank. */
    val planNote: String = "",
    /**
     * HOW THE TRADE ENDS (Round 73) - the take-profit-or-trail choice, and the flat-by-the-bell
     * rule that is not a choice. See [com.tj.portfolio.net.ResearchScore.TradePlan.exit] for why
     * a single [targetPrice] could never carry this on its own.
     */
    val planExit: String = "",
    /**
     * Too little of the session is left to START this trade - the levels stand, the clock does
     * not. See [com.tj.portfolio.net.ResearchScore.tradePlan]'s time rules.
     */
    val tooLateToStart: Boolean = false,
    /**
     * TRUE WHEN THE THREE PRICES ABOVE ARE CLAUDE'S, NOT THE APP'S ARITHMETIC.
     *
     * Round 69 is the first time anything in this app lets a language model set a NUMBER a
     * decision gets made on - Tj asked for it explicitly ("it can give advice and buy and sell
     * targets for all the stocks"), and it is a real departure from the rule [conviction]'s own
     * note describes, where a model's figure is kept out of a field the app computes. The rule
     * is preserved the only way it still can be: the two sources are never blended and never
     * indistinguishable. A plan is wholly the app's or wholly Claude's, this flag says which,
     * and the card and the dialog both label it on screen.
     */
    val planByClaude: Boolean = false,
    /**
     * HOW MANY LIVE TICKS IN A ROW [com.tj.portfolio.net.ResearchScore.tradePlan] HAS DECLINED
     * TO PLAN THIS ROW (Round 74) - bookkeeping for [com.tj.portfolio.ui.mergeDayTradingTech]'s
     * hysteresis, not shown anywhere in the UI.
     *
     * THE BUG THIS EXISTS TO FIX. `tradePlan` is recomputed from the live price every 30
     * seconds, and several of its "no trade" verdicts (no room left today, the target already
     * passed) are decided against a boundary the live price sits right next to for exactly the
     * volatile, already-moving stocks this section screens for - so a price wobbling a few
     * cents either side of that boundary flipped the verdict, and with it, every tick, between
     * a real plan and none. The old rule cleared [entryPrice]/[stopPrice]/[targetPrice] the
     * FIRST time a tick declined, so the grid - and the red planNote / beginner-summary text
     * that goes with it - would load, vanish, and reappear on a clock the reader could not see,
     * over a plan that had not actually changed. Reset to 0 the moment a real plan returns.
     */
    val planDeclineStreak: Int = 0,
    /**
     * WHY THE ENGINE DECLINED A PLAN FOR THIS ROW (Round 75) - blank whenever [entryPrice] > 0,
     * set only once a decline is CONFIRMED (the same 2-tick hysteresis [planDeclineStreak]
     * already provides, so this never flashes in and out with the levels it explains). Tj: "for
     * all the ones that are not good candidates, give a short explanation." Bookkeeping/UI text,
     * not sent to Claude and not in [toJson] - same treatment as [planDeclineStreak] itself,
     * since a model reasoning about a stock from scratch has no use for the app's own reason it
     * declined to plan one.
     */
    val planReason: String = "",
    /**
     * REAL TECHNICALS BEHIND THE RISK PLAN ABOVE (Round 68) - Wilder's ATR(14), the session's
     * volume-weighted average price, and the 09:30-10:00 ET opening range. See
     * `net/DayTradingTechnicals.kt`'s header for the research these come from. All zero until
     * [com.tj.portfolio.net.ResearchScore.tradePlan]/`withTechnicals` have enriched this
     * row - which only happens for rows actually on screen, the same rule analyst consensus
     * already follows for the Best list - and all zero for every row outside the day-trading
     * section, same as [entryPrice] and its siblings.
     */
    val atr: Double = 0.0,
    val vwap: Double = 0.0,
    val openingRangeHigh: Double = 0.0,
    val openingRangeLow: Double = 0.0,
    /**
     * THE LEVELS THE ROUND 69 TRIGGER IS CHOSEN FROM - the 5-minute ATR the stop is sized by,
     * the average daily range the target is bounded by, and the four structural prices an
     * intraday trader actually watches. Kept on the row, not just used and discarded inside
     * the scorer, because the per-stock explanation has to be able to SHOW its work and the
     * Claude bundle has to be able to hand over the same numbers the app reasoned from.
     */
    val atrIntraday: Double = 0.0,
    val adr: Double = 0.0,
    val prevHigh: Double = 0.0,
    val premarketHigh: Double = 0.0,
    val sessionHigh: Double = 0.0,
    val sessionLow: Double = 0.0,
    /**
     * The trading day the four intraday readings above belong to - see
     * [com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals.sessionDay]. Blank until a live
     * technicals sweep has filled them.
     */
    val sessionDay: String = "",
    /**
     * THE TWO HALVES OF [score] FOR A DAY-TRADING ROW (Round 72) - kept alongside the blended
     * number, not just folded into it, so "why is this only 62" has a visible answer instead of
     * one opaque figure. Tj: *"make the scores reflect a blend of how likely the stock is to
     * rise... and how confident this prediction is... a score of 100 means... very likely to
     * raise... and... extremely confident."* [score] itself becomes
     * `com.tj.portfolio.net.ResearchScore.blendedScore(dtLikelihood, dtConfidence)` for a
     * day-trading row; [dtLikelihood] is the existing "in play" momentum score
     * ([com.tj.portfolio.net.ResearchScore.dayTrading]/`withTechnicals`, unchanged arithmetic,
     * only renamed conceptually) and [dtConfidence] is
     * [com.tj.portfolio.net.ResearchScore.dayTradingConfidence]'s fixed five-item confirmation
     * checklist. Both zero for every row outside the day-trading section and for a Claude-added
     * day-trading pick - there is no app-computed `Scored` to blend from for either, the same
     * reason [conviction] is kept out of [score] for a Claude-added fund.
     */
    val dtLikelihood: Int = 0,
    val dtConfidence: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol)
        if (name.isNotBlank()) put("name", name)
        if (price > 0) put("price", price)
        if (changePct != 0.0) put("changePct", changePct)
        put("score", score)
        if (reasons.isNotEmpty()) put("reasons", JSONArray(reasons))
        if (why.isNotBlank()) put("why", why)
        if (whyAt > 0) put("whyAt", whyAt)
        if (mentions > 0) put("mentions", mentions)
        if (mentionDelta != 0) put("mentionDelta", mentionDelta)
        if (rankDelta != 0) put("rankDelta", rankDelta)
        if (sentiment.isNotBlank()) put("sentiment", sentiment)
        if (newsCount > 0) put("newsCount", newsCount)
        if (headline.isNotBlank()) put("headline", headline)
        if (headlineUrl.isNotBlank()) put("headlineUrl", headlineUrl)
        if (headlineSource.isNotBlank()) put("headlineSource", headlineSource)
        if (onYahooTrending) put("yahooTrending", true)
        consensus?.let {
            if (it.total > 0 || it.target > 0) put(
                "analyst",
                JSONObject().apply {
                    put("buy", it.buy); put("hold", it.hold); put("sell", it.sell)
                    if (it.target > 0) put("target", it.target)
                }
            )
        }
        if (catalyst.isNotBlank()) put("catalyst", catalyst)
        if (conviction > 0) put("conviction", conviction)
        etf?.let { if (!it.isEmpty || it.dollarVolume > 0 || it.inceptionMs > 0) put("etf", it.toJson()) }
        if (entryPrice > 0) put("entryPrice", entryPrice)
        if (stopPrice > 0) put("stopPrice", stopPrice)
        if (targetPrice > 0) put("targetPrice", targetPrice)
        if (atr > 0) put("atr", atr)
        if (vwap > 0) put("vwap", vwap)
        if (openingRangeHigh > 0) put("openingRangeHigh", openingRangeHigh)
        if (openingRangeLow > 0) put("openingRangeLow", openingRangeLow)
        if (setup.isNotBlank()) put("setup", setup)
        if (trigger.isNotBlank()) put("trigger", trigger)
        if (planNote.isNotBlank()) put("planNote", planNote)
        if (planExit.isNotBlank()) put("planExit", planExit)
        if (tooLateToStart) put("tooLateToStart", true)
        if (planByClaude) put("planByClaude", true)
        if (atrIntraday > 0) put("atrIntraday", atrIntraday)
        if (adr > 0) put("adr", adr)
        if (prevHigh > 0) put("prevHigh", prevHigh)
        if (premarketHigh > 0) put("premarketHigh", premarketHigh)
        if (sessionHigh > 0) put("sessionHigh", sessionHigh)
        if (sessionLow > 0) put("sessionLow", sessionLow)
        if (sessionDay.isNotBlank()) put("sessionDay", sessionDay)
        if (dtLikelihood > 0) put("dtLikelihood", dtLikelihood)
        if (dtConfidence > 0) put("dtConfidence", dtConfidence)
    }

    companion object {

        /**
         * THE CACHE WRITTEN BY AN OLDER BUILD STILL HAS THE OLD BUG IN IT (Round 66 audit,
         * RES-1).
         *
         * ---- WHAT THIS REPAIRS
         *
         * Before [conviction] existed, a fund Claude ADDED to the ETF list was stored with the
         * model's own number written straight into [score] as `conviction * 10`. Round 66 fixed
         * the writer and added `conviction` so the two can never be confused again - and
         * stopped there. `Keys.RESEARCH_CACHE` is a settings row: it survives the upgrade. So
         * a row already on disk still deserialises with `score = 100, conviction = 0`, and
         * every downstream test of "did the app measure this?" is `score <= 0 && conviction > 0`
         * - which is false. The card draws a SCORE badge reading 100 out of 100, over a row
         * with no facts grid and no reason lines, and `carryEtfExplanations` re-adds it on
         * every six-hourly rebuild and sorts by score first. It sits at row 1, above every
         * fund the app actually measured, for ever; a later import cannot heal it either,
         * because `merge` copies `why` and `conviction` and leaves `score` alone.
         *
         * On the list TJ said he is going to buy from, and the model's number is exactly the
         * one thing on that screen nobody measured.
         *
         * ---- HOW A ROW IS RECOGNISED
         *
         * Not by the score's shape - by the absence of the app's own working. Every row the
         * app scored carries reason lines: `ResearchScore.trending`, `.best` and
         * `EtfScore.best` all produce them, and an ETF row additionally carries its facts. A
         * row with a score, no reasons and no facts is a row nothing in this app computed, so
         * its number came from a model and belongs in [conviction].
         */
        private const val VERSION_CONVICTION_SPLIT = 2

        fun fromJson(
            o: JSONObject,
            version: Int = VERSION_CONVICTION_SPLIT,
            /**
             * True only for the `etfs` array (Round 66 audit, REG-2). The migration below is
             * scoped to it because the bug was: only the ETF list has a second entrance where
             * a model's number could be written into `score`.
             */
            isFundList: Boolean = false
        ): ResearchRow? {
            val sym = o.text("symbol").uppercase()
            if (sym.isBlank()) return null
            val reasons = ArrayList<String>()
            o.optJSONArray("reasons")?.let { a ->
                for (i in 0 until a.length()) a.text(i).takeIf { it.isNotBlank() }
                    ?.let { reasons.add(it) }
            }
            val an = o.optJSONObject("analyst")
            return ResearchRow(
                symbol = sym,
                name = o.text("name"),
                price = o.optDouble("price", 0.0).orZero(),
                changePct = o.optDouble("changePct", 0.0).orZero(),
                score = o.optInt("score", 0),
                reasons = reasons,
                why = o.text("why"),
                mentions = o.optInt("mentions", 0),
                mentionDelta = o.optInt("mentionDelta", 0),
                rankDelta = o.optInt("rankDelta", 0),
                sentiment = o.text("sentiment"),
                newsCount = o.optInt("newsCount", 0),
                headline = o.text("headline"),
                headlineUrl = o.text("headlineUrl"),
                headlineSource = o.text("headlineSource"),
                onYahooTrending = o.optBoolean("yahooTrending", false),
                consensus = if (an == null) null else Consensus2(
                    buy = an.optInt("buy", 0),
                    hold = an.optInt("hold", 0),
                    sell = an.optInt("sell", 0),
                    target = an.optDouble("target", 0.0).orZero()
                ),
                catalyst = o.text("catalyst"),
                conviction = o.optInt("conviction", 0).coerceIn(0, 10),
                etf = EtfFacts.fromJson(o.optJSONObject("etf")),
                entryPrice = o.optDouble("entryPrice", 0.0).orZero(),
                stopPrice = o.optDouble("stopPrice", 0.0).orZero(),
                targetPrice = o.optDouble("targetPrice", 0.0).orZero(),
                atr = o.optDouble("atr", 0.0).orZero(),
                vwap = o.optDouble("vwap", 0.0).orZero(),
                openingRangeHigh = o.optDouble("openingRangeHigh", 0.0).orZero(),
                openingRangeLow = o.optDouble("openingRangeLow", 0.0).orZero(),
                planExit = o.text("planExit"),
                tooLateToStart = o.optBoolean("tooLateToStart", false),
                setup = o.text("setup"),
                trigger = o.text("trigger"),
                planNote = o.text("planNote"),
                planByClaude = o.optBoolean("planByClaude", false),
                atrIntraday = o.optDouble("atrIntraday", 0.0).orZero(),
                adr = o.optDouble("adr", 0.0).orZero(),
                prevHigh = o.optDouble("prevHigh", 0.0).orZero(),
                premarketHigh = o.optDouble("premarketHigh", 0.0).orZero(),
                sessionHigh = o.optDouble("sessionHigh", 0.0).orZero(),
                sessionLow = o.optDouble("sessionLow", 0.0).orZero(),
                sessionDay = o.text("sessionDay"),
                dtLikelihood = o.optInt("dtLikelihood", 0).coerceIn(0, 100),
                dtConfidence = o.optInt("dtConfidence", 0).coerceIn(0, 100)
            ).let {
                if (isFundList && version < VERSION_CONVICTION_SPLIT) it.repairModelScore()
                else it
            }.dropPreTriggerPlan()
        }

        /**
         * THROW AWAY A DAY-TRADING PLAN WRITTEN BEFORE [entryPrice] MEANT WHAT IT MEANS NOW.
         *
         * The Research cache is a settings row: it survives the app upgrade, the same reason
         * [repairModelScore] has to exist. Before Round 69 `entryPrice` was simply the last
         * traded price. After it, the card labels that number "Buy at" and the dialog explains
         * at length that it is a TRIGGER the market has to reach - so a row still on disk from
         * the old build would have the exact defect Tj reported dressed in new prose insisting
         * it had been fixed. Worse than the original, because the prose is now a claim.
         *
         * Detected by SHAPE rather than by a cache version, because the shape is decisive and
         * needs no plumbing: every Round 69 plan carries a [setup] (the app's own always names
         * one) or [planByClaude]. A priced plan with neither can only be a pre-Round-69 row.
         * Cleared rather than converted - the live technicals sweep computes a real one within
         * a tick of the tab opening, and a blank is honest where a converted guess is not.
         */
        private fun ResearchRow.dropPreTriggerPlan(): ResearchRow =
            if (entryPrice > 0.0 && setup.isBlank() && !planByClaude)
                copy(
                    entryPrice = 0.0, stopPrice = 0.0, targetPrice = 0.0,
                    trigger = "", planNote = ""
                )
            else this

        /**
         * See [VERSION_CONVICTION_SPLIT]. Only ever applied to the `etfs` array of a cache an
         * older build wrote.
         *
         * ---- WHY THE TEST IS THIS NARROW (Round 66 audit, REG-2)
         *
         * The first version of this repair asked only "did the app compute this?" and answered
         * it with `reasons.isEmpty() && etf == null`. That is not sound: every reason line in
         * `ResearchScore.best` is conditional and there is no fallback, so a genuinely poor
         * stock - shrinking EPS, a demanding multiple, below both averages, too small for the
         * size line - can score in the twenties and emit NO reasons at all. Such a Best row
         * would have been relabelled "CLAUDE 2/10", told the screen reader "this fund was not
         * scored by the app", and had its real score destroyed on the next write. A migration
         * that damages good data is worse than the bug it repairs, and this one would have
         * fabricated an attribution to a model that never saw the row.
         *
         * So the test is now the full fingerprint of the thing being repaired, and every part
         * of it has to hold:
         *  - it is in the FUND list, the only list with a model-added entrance;
         *  - it carries no [EtfFacts], which every screener-built fund row has;
         *  - it carries a paragraph, which is the only reason a model-added row exists;
         *  - and the score is a positive multiple of ten, because the old writer stored
         *    `conviction * 10` and could not produce anything else.
         *
         * A row that fails any one of them is left exactly as it was found.
         */
        private fun ResearchRow.repairModelScore(): ResearchRow {
            val looksComputed = reasons.isNotEmpty() || etf != null
            val looksLikeAModelNumber = score in 10..100 && score % 10 == 0
            if (looksComputed || why.isBlank() || !looksLikeAModelNumber) return this
            return copy(
                score = 0,
                // The old writer stored `conviction * 10`, so the reverse is exact. Clamped
                // anyway: a value from disk is data, not a promise.
                conviction = maxOf(conviction, (score + 5) / 10).coerceIn(0, 10)
            )
        }

        private fun Double.orZero(): Double = if (isNaN() || isInfinite()) 0.0 else this
    }
}

/**
 * A complete Research payload: three ranked lists plus provenance.
 *
 * [trending] and [best] hold MORE than the ten rows the screen shows. The screen
 * reveals ten at a time from what is already here, so "Load more" costs nothing on the wire -
 * TJ's rule was that nothing beyond ten is loaded unless he asks, and the expensive per-symbol
 * work (analyst consensus) is done for the visible ten only.
 */
data class ResearchSet(
    val trending: List<ResearchRow> = emptyList(),
    val best: List<ResearchRow> = emptyList(),
    /**
     * BEST ETFS (Round 63) - ranked funds, and the one section with its own clock.
     *
     * TJ: *"It should periodically update the best etfs list, but keep the current list in
     * cache until each update so it doesn't load on every refresh."* [etfGenerated] is that
     * sentence: this list is built on its own long TTL and survives a rebuild of the two
     * stock lists, which run on the thirty-minute one. A fund ranking that changed every half hour
     * would be noise - the inputs are five-year annualised returns and expense ratios, and
     * neither moves before lunch.
     */
    val etfs: List<ResearchRow> = emptyList(),
    /** When [etfs] was last built. Its own stamp, because it has its own refresh clock. */
    val etfGenerated: Long = 0L,
    /** Non-fatal problems from the ETF pass alone, kept apart from [warnings]. */
    val etfWarnings: List<String> = emptyList(),
    /**
     * TODAY'S DAY-TRADING CANDIDATES (Round 67) - built from the SAME screener universe as
     * [trending] and [best] in the same pass, so it shares [generated] rather than owning a
     * clock of its own. See `net/ResearchScore.kt`'s `dayTrading()` for what it is actually
     * ranked on, and its own header for why "accurate same-day price prediction" is not the
     * question this answers.
     */
    val dayTrading: List<ResearchRow> = emptyList(),
    val generated: Long = 0L,
    /** Where the numbers came from, shown under each section. */
    val sources: String = "",
    /** Non-fatal problems - a feed that did not answer this time. */
    val warnings: List<String> = emptyList(),
    /** When Claude last explained these rows, and by which path. */
    val explained: Long = 0L,
    val explainedBy: String = "",
    val notes: String = "",
    /**
     * [explained]/[explainedBy]/[notes]'s own counterparts for [dayTrading] (Round 67) - NOT
     * shared with them, on purpose. Those three are already shared across Trending, Best and
     * ETFs because one `ResearchBridge` call explains all three at once; Day Trading is a
     * wholly separate bridge, prompt and button, the same reason [etfGenerated] is not
     * [generated]. Sharing the fields would mean explaining Day Trading silently overwrites
     * the timestamp and paragraph the Research tab is showing, and vice versa, for two
     * conversations that never touched each other.
     */
    val dtExplained: Long = 0L,
    val dtExplainedBy: String = "",
    val dtNotes: String = "",
    val error: String? = null
) {
    /**
     * True when both STOCK sections are empty - Trending and Best. The funds are not
     * counted here: they run on their own six-hour clock and their own rebuild, so an empty
     * stock pass says nothing about them.
     *
     * DELIBERATELY DOES NOT COUNT [etfs]. Every existing caller means "is there anything for
     * the 30-minute stock pass to carry forward / explain / rebuild", and folding the ETF
     * list in would make a populated ETF tab suppress the stock rebuild that fills the other
     * three. [isFullyEmpty] is the one for "is there anything on this screen at all".
     */
    val isEmpty: Boolean get() = trending.isEmpty() && best.isEmpty()

    val isFullyEmpty: Boolean get() = isEmpty && etfs.isEmpty()

    // EXHAUSTIVE, with no `else` (Round 66). The fallback used to be `worst`, so an unknown
    // section name silently returned the wrong list rather than an empty one - and when that
    // section was removed the fallback would have started returning Best's rows to anybody
    // asking for something that no longer exists.
    fun section(name: String): List<ResearchRow> = when (name) {
        SECTION_TRENDING -> trending
        SECTION_BEST -> best
        SECTION_ETF -> etfs
        SECTION_DAY_TRADING -> dayTrading
        else -> emptyList()
    }

    fun withSection(name: String, rows: List<ResearchRow>): ResearchSet = when (name) {
        SECTION_TRENDING -> copy(trending = rows)
        SECTION_BEST -> copy(best = rows)
        SECTION_ETF -> copy(etfs = rows)
        SECTION_DAY_TRADING -> copy(dayTrading = rows)
        else -> this
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "portfolio-research")
        // BUMPED TO 2 by the conviction/score split - see [ResearchRow.fromJson]. A payload
        // this app writes today needs no repair; one written before it does.
        put("version", 2)
        put("generated", generated)
        if (sources.isNotBlank()) put("sources", sources)
        if (warnings.isNotEmpty()) put("warnings", JSONArray(warnings))
        if (explained > 0) put("explained", explained)
        if (explainedBy.isNotBlank()) put("explainedBy", explainedBy)
        if (notes.isNotBlank()) put("notes", notes)
        put("trending", JSONArray().also { a -> trending.forEach { a.put(it.toJson()) } })
        put("best", JSONArray().also { a -> best.forEach { a.put(it.toJson()) } })
        put("etfs", JSONArray().also { a -> etfs.forEach { a.put(it.toJson()) } })
        if (etfGenerated > 0) put("etfGenerated", etfGenerated)
        if (etfWarnings.isNotEmpty()) put("etfWarnings", JSONArray(etfWarnings))
        put("dayTrading", JSONArray().also { a -> dayTrading.forEach { a.put(it.toJson()) } })
        if (dtExplained > 0) put("dtExplained", dtExplained)
        if (dtExplainedBy.isNotBlank()) put("dtExplainedBy", dtExplainedBy)
        if (dtNotes.isNotBlank()) put("dtNotes", dtNotes)
    }

    companion object {
        const val SECTION_TRENDING = "TRENDING"
        const val SECTION_BEST = "BEST"
        const val SECTION_ETF = "ETF"
        const val SECTION_DAY_TRADING = "DAY_TRADING"
        val SECTIONS = listOf(SECTION_TRENDING, SECTION_BEST, SECTION_ETF, SECTION_DAY_TRADING)

        /** How many rows one page of a section shows. */
        const val PAGE = 10

        fun fromJson(o: JSONObject): ResearchSet {
            // ---- THE VERSION IS FINALLY READ (Round 66 audit, RES-1).
            //
            // `toJson` has written `"version"` since this format existed and `fromJson` has
            // never looked at it, so there was no way to repair anything a previous build
            // stored - and the conviction/score split needed exactly that. Absent means 1: a
            // payload written before the field was consulted.
            val version = o.optInt("version", 1)
            fun rows(key: String): List<ResearchRow> {
                val a = o.optJSONArray(key) ?: return emptyList()
                val out = ArrayList<ResearchRow>(a.length())
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    ResearchRow.fromJson(r, version, isFundList = key == "etfs")
                        ?.let { out.add(it) }
                }
                return out
            }

            val warn = ArrayList<String>()
            o.optJSONArray("warnings")?.let { a ->
                for (i in 0 until a.length()) a.text(i).takeIf { it.isNotBlank() }
                    ?.let { warn.add(it) }
            }
            val etfWarn = ArrayList<String>()
            o.optJSONArray("etfWarnings")?.let { a ->
                for (i in 0 until a.length()) a.text(i).takeIf { it.isNotBlank() }
                    ?.let { etfWarn.add(it) }
            }
            return ResearchSet(
                trending = rows("trending"),
                best = rows("best"),
                etfs = rows("etfs"),
                etfGenerated = o.optLong("etfGenerated", 0L),
                etfWarnings = etfWarn,
                dayTrading = rows("dayTrading"),
                generated = o.optLong("generated", 0L),
                sources = o.text("sources"),
                warnings = warn,
                explained = o.optLong("explained", 0L),
                explainedBy = o.text("explainedBy"),
                notes = o.text("notes"),
                dtExplained = o.optLong("dtExplained", 0L),
                dtExplainedBy = o.text("dtExplainedBy"),
                dtNotes = o.text("dtNotes")
            )
        }
    }
}
