package com.tj.portfolio.data

import com.tj.portfolio.util.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE RESEARCH TAB'S DATA MODEL (Round 54).
 *
 * Three sections - what the market is TALKING about, what the numbers say is worth BUYING,
 * and what the numbers say is BREAKING - built from free, keyless feeds and scored by the
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
        earningsEstimated = earningsEstimated && other.earningsEstimated,
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
        sellShare >= 0.30 -> "Sell"
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
    /** True when the user already holds or watches this symbol - shown as a chip. */
    val followed: Boolean = false,
    /**
     * The fund numbers, for rows in the ETF section (Round 63). Null for a stock.
     *
     * CARRIED ON THE SAME ROW TYPE rather than in a parallel model, so the ETF list gets the
     * cache, the Claude bridge, the merge, the de-duplication and the card layout that the
     * stock sections already have - and so a change to any of those cannot fix one list and
     * forget another.
     */
    val etf: EtfFacts? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol)
        if (name.isNotBlank()) put("name", name)
        if (price > 0) put("price", price)
        if (changePct != 0.0) put("changePct", changePct)
        put("score", score)
        if (reasons.isNotEmpty()) put("reasons", JSONArray(reasons))
        if (why.isNotBlank()) put("why", why)
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
    }

    companion object {
        fun fromJson(o: JSONObject): ResearchRow? {
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
                etf = EtfFacts.fromJson(o.optJSONObject("etf"))
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
 * work (analyst consensus, the short vehicle lookup) is done for the visible ten only.
 */
data class ResearchSet(
    val trending: List<ResearchRow> = emptyList(),
    val best: List<ResearchRow> = emptyList(),
    /**
     * BEST ETFS (Round 63) - ranked funds, and the one section with its own clock.
     *
     * TJ: *"It should periodically update the best etfs list, but keep the current list in
     * cache until each update so it doesn't load on every refresh."* [etfGenerated] is that
     * sentence: this list is built on its own long TTL and survives a rebuild of the other
     * three, which run on the thirty-minute one. A fund ranking that changed every half hour
     * would be noise - the inputs are five-year annualised returns and expense ratios, and
     * neither moves before lunch.
     */
    val etfs: List<ResearchRow> = emptyList(),
    /** When [etfs] was last built. Its own stamp, because it has its own refresh clock. */
    val etfGenerated: Long = 0L,
    /** Non-fatal problems from the ETF pass alone, kept apart from [warnings]. */
    val etfWarnings: List<String> = emptyList(),
    val generated: Long = 0L,
    /** Where the numbers came from, shown under each section. */
    val sources: String = "",
    /** Non-fatal problems - a feed that did not answer this time. */
    val warnings: List<String> = emptyList(),
    /** When Claude last explained these rows, and by which path. */
    val explained: Long = 0L,
    val explainedBy: String = "",
    val notes: String = "",
    val error: String? = null
) {
    /**
     * True when the three MARKET sections are empty.
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
        else -> emptyList()
    }

    fun withSection(name: String, rows: List<ResearchRow>): ResearchSet = when (name) {
        SECTION_TRENDING -> copy(trending = rows)
        SECTION_BEST -> copy(best = rows)
        SECTION_ETF -> copy(etfs = rows)
        else -> this
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "portfolio-research")
        put("version", 1)
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
    }

    companion object {
        const val SECTION_TRENDING = "TRENDING"
        const val SECTION_BEST = "BEST"
        const val SECTION_ETF = "ETF"
        val SECTIONS = listOf(SECTION_TRENDING, SECTION_BEST, SECTION_ETF)

        /** How many rows one page of a section shows. */
        const val PAGE = 10

        fun fromJson(o: JSONObject): ResearchSet {
            fun rows(key: String): List<ResearchRow> {
                val a = o.optJSONArray(key) ?: return emptyList()
                val out = ArrayList<ResearchRow>(a.length())
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    ResearchRow.fromJson(r)?.let { out.add(it) }
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
                generated = o.optLong("generated", 0L),
                sources = o.text("sources"),
                warnings = warn,
                explained = o.optLong("explained", 0L),
                explainedBy = o.text("explainedBy"),
                notes = o.text("notes")
            )
        }
    }
}
