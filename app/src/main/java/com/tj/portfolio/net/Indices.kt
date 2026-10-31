package com.tj.portfolio.net

import com.tj.portfolio.data.Quote
import org.json.JSONArray
import org.json.JSONObject

/**
 * MAJOR MARKET INDICES (2026-10-31). Tj: "show all current major indices prices such as sp500,
 * dow, nasdaq". One batched quote request for the whole list, saved to disk so the Markets
 * sub-tab also reads offline.
 */
object Indices {
    data class Def(val symbol: String, val name: String, val group: String)

    const val US = "United States"
    const val GLOBAL = "World"
    const val GAUGES = "Volatility and rates"

    val ALL = listOf(
        Def("^GSPC", "S&P 500", US), Def("^DJI", "Dow Jones Industrial Average", US),
        Def("^IXIC", "Nasdaq Composite", US), Def("^NDX", "Nasdaq-100", US),
        Def("^RUT", "Russell 2000", US), Def("^NYA", "NYSE Composite", US),
        Def("^FTSE", "FTSE 100 (UK)", GLOBAL), Def("^GDAXI", "DAX (Germany)", GLOBAL),
        Def("^FCHI", "CAC 40 (France)", GLOBAL), Def("^N225", "Nikkei 225 (Japan)", GLOBAL),
        Def("^HSI", "Hang Seng (Hong Kong)", GLOBAL), Def("^GSPTSE", "S&P/TSX (Canada)", GLOBAL),
        Def("^VIX", "VIX volatility", GAUGES), Def("^TNX", "10-year Treasury yield (%)", GAUGES)
    )

    val SYMBOLS: List<String> get() = ALL.map { it.symbol }

    /** Disk key (derived data, excluded from backups via IncomeHub.PREFIXES). */
    const val KEY = "indices:quotes"

    suspend fun fetch(): List<Quote>? =
        runCatching { MarketData.quotes(SYMBOLS, "") }.getOrNull()
            ?.filter { it.price > 0 && it.symbol in SYMBOLS }?.takeIf { it.isNotEmpty() }

    fun encode(qs: Collection<Quote>): String = JSONArray().also { a ->
        qs.forEach { q ->
            a.put(JSONObject().put("s", q.symbol).put("p", q.price).put("c", q.prevClose)
                .put("h", q.dayHigh).put("l", q.dayLow).put("m", q.marketState).put("u", q.updated))
        }
    }.toString()

    fun decode(s: String?): Map<String, Quote> = runCatching {
        val a = JSONArray(s ?: return emptyMap())
        (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
            o.getString("s") to Quote(o.getString("s"), price = o.getDouble("p"), prevClose = o.getDouble("c"),
                dayHigh = o.optDouble("h", 0.0), dayLow = o.optDouble("l", 0.0),
                marketState = o.optString("m"), updated = o.optLong("u"))
        }
    }.getOrDefault(emptyMap())
}
