package com.tj.portfolio.data

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
    // --- best / worst
    val consensus: Consensus2? = null,
    val catalyst: String = "",
    /** The listed way to bet against this name, when one exists. Worst section only. */
    val shortVehicle: String = "",
    val shortVehicleNote: String = "",
    /** True when the user already holds or watches this symbol - shown as a chip. */
    val followed: Boolean = false
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
        if (shortVehicle.isNotBlank()) put("shortVehicle", shortVehicle)
        if (shortVehicleNote.isNotBlank()) put("shortVehicleNote", shortVehicleNote)
    }

    companion object {
        fun fromJson(o: JSONObject): ResearchRow? {
            val sym = o.optString("symbol").uppercase()
            if (sym.isBlank()) return null
            val reasons = ArrayList<String>()
            o.optJSONArray("reasons")?.let { a ->
                for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }
                    ?.let { reasons.add(it) }
            }
            val an = o.optJSONObject("analyst")
            return ResearchRow(
                symbol = sym,
                name = o.optString("name"),
                price = o.optDouble("price", 0.0).orZero(),
                changePct = o.optDouble("changePct", 0.0).orZero(),
                score = o.optInt("score", 0),
                reasons = reasons,
                why = o.optString("why"),
                mentions = o.optInt("mentions", 0),
                mentionDelta = o.optInt("mentionDelta", 0),
                rankDelta = o.optInt("rankDelta", 0),
                sentiment = o.optString("sentiment"),
                newsCount = o.optInt("newsCount", 0),
                headline = o.optString("headline"),
                headlineUrl = o.optString("headlineUrl"),
                headlineSource = o.optString("headlineSource"),
                onYahooTrending = o.optBoolean("yahooTrending", false),
                consensus = if (an == null) null else Consensus2(
                    buy = an.optInt("buy", 0),
                    hold = an.optInt("hold", 0),
                    sell = an.optInt("sell", 0),
                    target = an.optDouble("target", 0.0).orZero()
                ),
                catalyst = o.optString("catalyst"),
                shortVehicle = o.optString("shortVehicle"),
                shortVehicleNote = o.optString("shortVehicleNote")
            )
        }

        private fun Double.orZero(): Double = if (isNaN() || isInfinite()) 0.0 else this
    }
}

/**
 * A complete Research payload: three ranked lists plus provenance.
 *
 * [trending], [best] and [worst] hold MORE than the ten rows the screen shows. The screen
 * reveals ten at a time from what is already here, so "Load more" costs nothing on the wire -
 * TJ's rule was that nothing beyond ten is loaded unless he asks, and the expensive per-symbol
 * work (analyst consensus, the short vehicle lookup) is done for the visible ten only.
 */
data class ResearchSet(
    val trending: List<ResearchRow> = emptyList(),
    val best: List<ResearchRow> = emptyList(),
    val worst: List<ResearchRow> = emptyList(),
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
    val isEmpty: Boolean get() = trending.isEmpty() && best.isEmpty() && worst.isEmpty()

    fun section(name: String): List<ResearchRow> = when (name) {
        SECTION_TRENDING -> trending
        SECTION_BEST -> best
        else -> worst
    }

    fun withSection(name: String, rows: List<ResearchRow>): ResearchSet = when (name) {
        SECTION_TRENDING -> copy(trending = rows)
        SECTION_BEST -> copy(best = rows)
        else -> copy(worst = rows)
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
        put("worst", JSONArray().also { a -> worst.forEach { a.put(it.toJson()) } })
    }

    companion object {
        const val SECTION_TRENDING = "TRENDING"
        const val SECTION_BEST = "BEST"
        const val SECTION_WORST = "WORST"
        val SECTIONS = listOf(SECTION_TRENDING, SECTION_BEST, SECTION_WORST)

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
                for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }
                    ?.let { warn.add(it) }
            }
            return ResearchSet(
                trending = rows("trending"),
                best = rows("best"),
                worst = rows("worst"),
                generated = o.optLong("generated", 0L),
                sources = o.optString("sources"),
                warnings = warn,
                explained = o.optLong("explained", 0L),
                explainedBy = o.optString("explainedBy"),
                notes = o.optString("notes")
            )
        }
    }
}
