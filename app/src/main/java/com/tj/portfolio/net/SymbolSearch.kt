package com.tj.portfolio.net

import org.json.JSONObject

data class SearchHit(
    val symbol: String,
    val name: String,
    val exchange: String = "",
    val type: String = ""
)

/**
 * Type-ahead ticker lookup by symbol OR company name.
 *   1. Yahoo Finance search - no key, matches both name and ticker
 *   2. Finnhub /search      - if a key is configured
 * Results are equities/ETFs only; indices and futures are filtered out.
 */
object SymbolSearch {

    /**
     * The result list is keyed by symbol on screen, and a keyed list handed the same key
     * twice throws. Finnhub in particular returns one row per venue, so a search for a
     * cross-listed name comes back with the same displaySymbol several times. De-duplicated
     * here, at the source, so no caller can be caught out by it.
     */
    /**
     * A small memo of what has already been asked for this session.
     *
     * Type-ahead is a request generator by construction: the debounce in `searchSymbols`
     * stops a request per KEYSTROKE, but nothing stopped the same term being asked for twice -
     * and backspacing to a prefix already typed, or retyping a ticker looked up a minute
     * earlier, is completely normal. A ticker lookup is also about as immutable as data gets
     * within one session.
     *
     * Bounded and access-ordered, so it is a fixed handful of kilobytes.
     */
    private const val MEMO_MAX = 48

    private val memo = object : LinkedHashMap<String, List<SearchHit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchHit>>) =
            size > MEMO_MAX
    }

    suspend fun query(q: String, finnhubKey: String = ""): List<SearchHit> {
        val term = q.trim()
        if (term.isEmpty()) return emptyList()
        val key = term.lowercase()
        synchronized(memo) { memo[key] }?.let { return it }
        val yahoo = yahoo(term)
        val out = when {
            yahoo.isNotEmpty() -> yahoo.distinctBy { it.symbol }
            finnhubKey.isNotBlank() -> finnhub(term, finnhubKey).distinctBy { it.symbol }
            else -> emptyList()
        }
        // An empty result is NOT memoised: it is far more likely to mean "the request failed"
        // or "the user is halfway through typing" than "this term has no matches", and
        // remembering it would make a transient failure permanent for the session.
        if (out.isNotEmpty()) synchronized(memo) { memo[key] = out }
        return out
    }

    /** Dropped on a memory trim - it is a convenience, not state. */
    fun clearMemo() { synchronized(memo) { memo.clear() } }

    private suspend fun yahoo(term: String): List<SearchHit> {
        val r = Http.get(
            "https://query2.finance.yahoo.com/v1/finance/search?q=" + MarketData.enc(term) +
                "&quotesCount=12&newsCount=0&listsCount=0",
            mapOf("Accept" to "application/json"),
            12000, conditionalKey = true
        )
        if (!r.ok) return emptyList()
        return try {
            val arr = JSONObject(r.body).optJSONArray("quotes") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sym = o.optString("symbol").uppercase()
                if (sym.isBlank()) continue
                val type = o.optString("quoteType").uppercase()
                if (type !in setOf("EQUITY", "ETF", "MUTUALFUND", "CRYPTOCURRENCY")) continue
                out.add(
                    SearchHit(
                        symbol = sym,
                        name = o.optString("shortname").ifBlank { o.optString("longname") },
                        exchange = o.optString("exchDisp").ifBlank { o.optString("exchange") },
                        type = type
                    )
                )
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun finnhub(term: String, key: String): List<SearchHit> {
        val r = Http.get(
            "https://finnhub.io/api/v1/search?q=" + MarketData.enc(term) + "&token=" + key,
            timeoutMs = 12000
        )
        if (!r.ok) return emptyList()
        return try {
            val arr = JSONObject(r.body).optJSONArray("result") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until minOf(arr.length(), 12)) {
                val o = arr.optJSONObject(i) ?: continue
                val sym = o.optString("displaySymbol").ifBlank { o.optString("symbol") }.uppercase()
                if (sym.isBlank() || sym.contains(".")) continue
                out.add(SearchHit(sym, o.optString("description"), "", o.optString("type").uppercase()))
            }
            out
        } catch (e: Exception) { emptyList() }
    }
}
