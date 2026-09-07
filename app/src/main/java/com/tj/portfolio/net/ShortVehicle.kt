package com.tj.portfolio.net

import org.json.JSONObject

/**
 * "If this stock is going to fall, what can I actually buy?"
 *
 * TJ asked the Worst section to name the ETF or stock that shorts a given company. There is
 * no free API that maps a stock to its inverse ETF, and the obvious answer - hard-code a
 * table - is the wrong one: single-stock inverse funds launch, close, reverse-split and
 * change ticker constantly (TSLQ, NVDS and half a dozen others have all moved or been
 * renamed since 2024), so a table baked into an APK is wrong within months and there is no
 * way for the app to know it has gone stale.
 *
 * So this RESOLVES THE ANSWER LIVE from Yahoo's own symbol search, which indexes every
 * listed fund by name. Issuers name these products to a rigid formula:
 *
 *     Direxion Daily TSLA Bear 1X ETF
 *     GraniteShares 2x Short NVDA Daily ETF
 *     Tradr 1.5X Short NVDA Daily ETF
 *     T-REX 2X Inverse CRWV Daily Target ETF
 *
 * Every one of them contains the underlying's ticker AND an inverse word. That pair is the
 * whole matcher, and it keeps working when the roster changes because the roster is Yahoo's,
 * not ours. Verified live in September 2026: "TSLA bear" -> TSLS, "short NVDA" -> NVD /
 * NVDS / DIPS, "PLTR bear" -> PLTD.
 *
 * WHAT IS HARD-CODED IS ONLY THE FALLBACK - the broad index inverse funds (SH, PSQ, SQQQ,
 * RWM, DOG). Those have traded under the same tickers for fifteen-plus years and are the
 * honest answer to "there is no fund that shorts this specific company".
 */
object ShortVehicle {

    /** A resolved way to bet against a stock. */
    data class Vehicle(val ticker: String, val name: String, val note: String)

    private val INVERSE_WORDS = listOf("bear", "short", "inverse", "-1x", "-2x")

    /**
     * Words that mean the fund is NOT a straight inverse bet even though it shorts something.
     * A covered-call or option-income fund sells upside; it does not profit from a decline
     * the way an inverse fund does, and presenting it as one would be actively misleading.
     */
    private val NOT_INVERSE = listOf("option income", "covered call", "yieldmax", "buffer", "premium income")

    /**
     * Look for a single-stock inverse fund on [symbol].
     *
     * Two queries, because issuers split between the two naming conventions and Yahoo's
     * search does not stem across them: "<SYM> bear" finds Direxion, "short <SYM>" finds
     * GraniteShares, Tradr and T-Rex. Results are ranked so a -1x fund beats a -2x one, since
     * daily-reset leverage decays and the 1x product is the less dangerous suggestion.
     */
    suspend fun forSymbol(symbol: String): Vehicle? {
        val sym = symbol.uppercase().trim()
        if (sym.isBlank() || sym.length > 5) return null
        val found = LinkedHashMap<String, Vehicle>()
        for (q in listOf("$sym bear", "short $sym")) {
            val hits = search(q)
            for (h in hits) {
                if (h.ticker == sym) continue
                if (!nameTargets(h.name, sym)) continue
                found.putIfAbsent(h.ticker, h)
            }
            if (found.size >= 3) break
        }
        if (found.isEmpty()) return null
        return found.values.minByOrNull { leverageOf(it.name) }
    }

    /**
     * Does this fund NAME say it is an inverse bet on exactly this ticker?
     *
     * Both halves are required. "Direxion Daily Semiconductor Bear 3X" contains an inverse
     * word but not the ticker; "Direxion Daily NVDA Bull 2X" contains the ticker but bets the
     * other way. Getting either wrong would put a fund on screen that moves WITH the stock
     * the user is being told is failing.
     */
    internal fun nameTargets(name: String, symbol: String): Boolean {
        val low = name.lowercase()
        if (NOT_INVERSE.any { low.contains(it) }) return false
        if (INVERSE_WORDS.none { low.contains(it) }) return false
        if (low.contains("bull") || low.contains("long ")) return false
        // The ticker as its own word - "NVDA" must not match inside "NVDAX". Written by hand
        // rather than with a per-call regex: this runs for every search hit (10 hits x 2
        // queries x every row of the Worst page), and compiling a pattern to find a word is
        // the kind of thing that only looks cheap.
        val needle = symbol.lowercase()
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val i = low.indexOf(needle, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else low[i - 1]
            val afterIdx = i + needle.length
            val after = if (afterIdx >= low.length) ' ' else low[afterIdx]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }

    /** Compiled once - this ran per search hit. */
    private val LEVERAGE = Regex("(-?\\d(?:\\.\\d)?)\\s*x", RegexOption.IGNORE_CASE)

    /** 1.0, 1.5, 2.0 ... parsed out of the fund name; unknown sorts last. */
    internal fun leverageOf(name: String): Double {
        val v = LEVERAGE.find(name)?.groupValues?.get(1)
            ?.toDoubleOrNull()?.let { kotlin.math.abs(it) }
        return v ?: 9.0
    }

    private suspend fun search(query: String): List<Vehicle> {
        for (host in listOf("query1", "query2")) {
            val r = Http.get(
                "https://$host.finance.yahoo.com/v1/finance/search?q=" +
                    MarketData.enc(query) + "&quotesCount=10&newsCount=0&enableFuzzyQuery=false",
                timeoutMs = 15000, conditionalKey = true
            )
            if (r.throttledLocally) return emptyList()
            if (!r.ok) continue
            val out = runCatching { parse(r.body) }.getOrDefault(emptyList())
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    internal fun parse(body: String): List<Vehicle> {
        val arr = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
        val out = ArrayList<Vehicle>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("quoteType") !in setOf("ETF", "MUTUALFUND")) continue
            val t = o.optString("symbol").uppercase()
            // "^PLTD" and the like are index proxies for the fund, not something to buy.
            if (t.isBlank() || t.startsWith("^")) continue
            val n = o.optString("longname").ifBlank { o.optString("shortname") }
            if (n.isBlank()) continue
            out.add(Vehicle(t, n, o.optString("exchDisp")))
        }
        return out
    }

    /**
     * When no fund shorts the company itself. These are broad-market inverse ETFs, chosen by
     * where the stock lives rather than pretending to be a substitute for it.
     */
    fun broadFallback(exchange: String, marketCap: Double): Vehicle = when {
        marketCap in 1.0..2e9 ->
            Vehicle("RWM", "ProShares Short Russell2000", "small-cap index, not this company")
        exchange.contains("Nasdaq", true) ->
            Vehicle("PSQ", "ProShares Short QQQ", "Nasdaq-100 index, not this company")
        else ->
            Vehicle("SH", "ProShares Short S&P500", "S&P 500 index, not this company")
    }

    /**
     * The warning that goes on screen next to every one of these.
     *
     * It is not boilerplate. Daily-reset inverse funds do not track the inverse of a
     * multi-day move - they decay in a choppy market even when the direction was right - and
     * the names in the Worst section are the heavily-shorted ones, which are precisely the
     * ones that squeeze violently upward. Both facts belong beside the ticker, not in a
     * settings screen nobody opens.
     */
    const val WARNING =
        "Inverse funds reset daily: over more than a day or two they do not return the " +
            "opposite of the stock's move, and they lose value in a choppy market even when " +
            "the direction is right. Heavily shorted stocks are also the ones most prone to " +
            "short squeezes. This is what exists, not a suggestion to buy it."
}
