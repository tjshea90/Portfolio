package com.tj.portfolio.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE org.json NULL TRAP, IN ONE PLACE.
 *
 * `JSONObject.optString(key)` on an explicit JSON `null` returns the literal STRING `"null"`
 * on Android - not the empty fallback anyone reading the call site would expect. This app has
 * already paid for that once: the v1 backup restore turned all eight of TJ's cash rows
 * (DEPOSIT, `symbol: null`) into a phantom symbol called "NULL".
 *
 * It keeps coming back because every guard has to be written by hand at the call site, and
 * there are dozens of them. So this is the guard, written once:
 *
 *   * [text] returns "" for a missing key AND for an explicit null;
 *   * `.ifBlank { ... }` then behaves the way the call site plainly means it to - which
 *     `optString` breaks, because `"null"` is not blank and every fallback is skipped.
 *
 * USE IT FOR ANYTHING THAT CROSSES A TRUST BOUNDARY: a provider's JSON, and a reply pasted
 * back from the Claude app. Language models routinely emit `null` for an optional field even
 * when the schema asks for `""`, and a provider's row can carry a null in any string field.
 */
fun JSONObject.text(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val v = optString(key)
    // A belt-and-braces check: some org.json builds return the string "null" even from
    // `getString` on a real null, and a value that IS the four characters "null" is never a
    // ticker, a fund name or a sentence worth showing.
    return if (v == "null") "" else v
}

/** The same, with a fallback - so a call site reads as one expression. */
fun JSONObject.text(key: String, fallback: String): String = text(key).ifBlank { fallback }

/**
 * The same guard for an array element. A `null` inside a JSON array reads back as the string
 * "null" for exactly the same reason, and a reasons list or a warnings list is the kind of
 * place a model drops one.
 */
fun JSONArray.text(index: Int): String {
    if (index < 0 || index >= length() || isNull(index)) return ""
    val v = optString(index)
    return if (v == "null") "" else v
}

/**
 * Yahoo wraps most numbers as `{"raw": 36.68, "fmt": "36.68"}` but hands back a bare number
 * for some fields and an EMPTY OBJECT for "not reported". All three shapes have to mean the
 * same thing: a value, or nothing. An empty object read as 0.0 is a fabricated zero - this
 * app has been bitten by exactly that before, for a fundamentals field and again for a fund
 * holding's weight. Was duplicated verbatim in `FundamentalsFeed` and `HoldingsFeed`; both now
 * delegate here.
 */
fun JSONObject?.yahooNum(key: String): Double? {
    if (this == null || !has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is JSONObject -> if (v.has("raw") && !v.isNull("raw"))
            v.optDouble("raw", Double.NaN).takeIf { it.isFinite() } else null
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
