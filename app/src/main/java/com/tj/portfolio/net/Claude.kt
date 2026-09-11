package com.tj.portfolio.net

import com.tj.portfolio.data.Advice
import com.tj.portfolio.data.StockRating
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.util.Fmt
import org.json.JSONArray
import org.json.JSONObject

data class ExtractResult(
    val transactions: List<Txn>,
    val notes: String,
    val error: String? = null,
    val raw: String = ""
)

object Claude {

    private const val BASE = "https://api.anthropic.com/v1"
    private const val VERSION = "2023-06-01"

    /**
     * Only used until the live /v1/models list has been fetched. Kept as a bare alias so it
     * follows the family forward; dated snapshots and older families get retired and 404.
     */
    const val DEFAULT_MODEL = "claude-sonnet-5"

    private fun headers(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to VERSION
    )

    /** Live model list, so the app keeps working as models are added or retired. */
    suspend fun models(key: String): List<String> {
        val r = Http.get("$BASE/models?limit=100", headers(key), 30000)
        if (!r.ok) return emptyList()
        return try {
            val arr = JSONObject(r.body).optJSONArray("data") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Newest Sonnet if present, else the newest Opus, else whatever came back first.
     *
     * Plain `sortedDescending()` on these ids is a string sort, and a string sort gets model
     * numbers wrong the moment they reach two digits: "claude-sonnet-5" sorts ABOVE
     * "claude-sonnet-10". Dated snapshots outrank their own bare alias too. So: prefer the
     * undated alias (it follows the family forward on its own), and rank by the version
     * numbers in the id read as numbers.
     */
    fun preferredModel(ids: List<String>): String {
        fun pick(family: String): String? = ids.filter { it.contains(family, true) }
            .maxWithOrNull(
                // VERSION FIRST, alias second. These two keys were the other way round, which
                // made "is it an alias?" outrank the version number entirely: a list holding
                // `claude-sonnet-4-5` and `claude-sonnet-5-20260201` picked 4-5, because the
                // undated one won before the versions were ever compared. A new family often
                // ships as a dated snapshot before its bare alias exists, which is exactly
                // when auto-pick matters. Now the newest version wins, and the undated alias
                // only breaks a tie BETWEEN the same version - which is what the old comment
                // said it did.
                compareBy<String> { versionKey(it) }
                    .thenBy { if (isDatedSnapshot(it)) 0 else 1 }
                    .thenBy { it }
            )
        pick("sonnet")?.let { return it }
        pick("opus")?.let { return it }
        return ids.firstOrNull() ?: DEFAULT_MODEL
    }

    private val DATE_SUFFIX = Regex("-\\d{8}$")
    private val NUMBERS = Regex("\\d+")

    /** e.g. claude-sonnet-4-5-20250929 - a pinned snapshot rather than a rolling alias. */
    private fun isDatedSnapshot(id: String) = DATE_SUFFIX.containsMatchIn(id)

    /**
     * Version numbers in order, ignoring any trailing date, zero-padded so that a plain
     * string comparison orders them numerically: "5" -> "0005", "10" -> "0010".
     */
    private fun versionKey(id: String): String =
        NUMBERS.findAll(id.replace(DATE_SUFFIX, ""))
            .map { (it.value.toIntOrNull() ?: 0).toString().padStart(4, '0') }
            .joinToString(".")

    // ------------------------------------------------------- screenshot OCR

    private const val EXTRACT_PROMPT = """You are reading screenshots from an Ally Invest brokerage account.
Extract EVERY transaction, holding row, or balance line you can see into structured JSON.

Return ONLY a JSON object, no prose, no markdown fences:

{
  "transactions": [
    {"type":"BUY","symbol":"XXXX","quantity":3,"price":227.44,"amount":682.32,"fees":0,"date":"1900-01-01","note":"PLACEHOLDER - do not copy"}
  ],
  "notes": "anything ambiguous or unreadable, one short paragraph"
}

The row above is a PLACEHOLDER showing the field layout, not data. The ticker XXXX and the
date 1900-01-01 mark it as fake and the app discards any row still carrying either. Every
row you return must come from something visible in a screenshot.

RULES
- "type" is exactly one of: BUY, SELL, DEPOSIT, WITHDRAWAL, DIVIDEND, INTEREST, FEE.
- "amount" is always a POSITIVE number: the total dollar value of the transaction. The app applies the sign.
- For BUY/SELL: "quantity" = number of shares, "price" = price per share, "amount" = total principal.
  If only two of the three are visible, compute the third. If price is missing, price = amount / quantity.
- For DEPOSIT/WITHDRAWAL/DIVIDEND/INTEREST/FEE: set symbol to null (except dividends, where the
  paying symbol should be kept), quantity 0, price 0, and put the dollar figure in "amount".
- "date" must be "YYYY-MM-DD". If a screenshot shows only a settlement date, use it. If no date is
  visible anywhere, use null and explain in notes.
- "fees" is ONLY a commission or regulatory fee printed on the row itself. Use 0 when the
  screenshot does not show one. This is an ALLY INVEST account: Ally charges NO commission
  on stock and ETF trades, so a buy should almost always be 0. Never estimate, infer or
  back-calculate a fee from the totals - a fee that was charged is already inside the net
  amount, and adding it again overstates the cost basis.
- Symbols are uppercase tickers only (NVDA, not "NVIDIA Corporation").

IMPORTANT — a HOLDINGS or POSITIONS screen is NOT a transaction list. If a screenshot shows current
positions (columns like Symbol/Qty, Market Value, Total G/L, Last Price), convert each row into a
single synthetic BUY that reproduces the cost basis:
  quantity = shares shown,
  price    = (market value - total gain/loss) / shares   (this is the average cost per share),
  amount   = market value - total gain/loss,
  date     = null,
  note     = "position snapshot".
Say clearly in "notes" that these are snapshots, not real trades.

Never invent a transaction you cannot see. If a value is genuinely unreadable, omit that row and
say so in notes. Numbers must not contain commas or currency symbols."""

    suspend fun extractTransactions(
        key: String,
        model: String,
        imagesBase64: List<Pair<String, String>>,  // (mediaType, base64)
        alreadyHave: String = ""
    ): ExtractResult {
        if (imagesBase64.isEmpty()) return ExtractResult(emptyList(), "", "No images selected")

        val content = JSONArray()
        imagesBase64.forEach { (media, b64) ->
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", media)
                    put("data", b64)
                })
            })
        }
        content.put(JSONObject().apply {
            put("type", "text")
            put(
                "text",
                if (alreadyHave.isBlank()) EXTRACT_PROMPT
                else EXTRACT_PROMPT +
                    "\n\nTRANSACTIONS ALREADY RECORDED - do not return any of these again. " +
                    "A row matches if date + symbol + quantity are the same. Only report rows " +
                    "that are NOT listed here. If everything visible is already listed, return " +
                    "an empty transactions array and say so in notes.\n\n" + alreadyHave
            )
        })

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 8000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }

        val r = Http.postJson("$BASE/messages", body.toString(), headers(key))
        if (!r.ok) return ExtractResult(emptyList(), "", apiError(r.code, r.body), r.body)

        val text = textOf(r.body)
        val obj = firstJsonObject(text)
            ?: return ExtractResult(
                emptyList(), "",
                // A big batch of screenshots can run past max_tokens, and the reply then ends
                // mid-JSON. That used to surface as the same opaque "not usable JSON" as a
                // genuinely malformed answer, with no hint that fewer images would fix it.
                if (stopReason(r.body) == "max_tokens")
                    "Claude ran out of room before finishing. Import fewer screenshots at a time."
                else "Claude did not return usable JSON",
                text
            )

        val out = ArrayList<Txn>()
        var unusable = 0
        val arr = obj.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type").uppercase()
            if (type !in TxnType.ALL) continue
            val sym = o.optString("symbol").takeIf { it.isNotBlank() && it != "null" }?.uppercase()
            val qty = o.optDouble("quantity", 0.0).let { if (it.isNaN()) 0.0 else it }
            var px = o.optDouble("price", 0.0).let { if (it.isNaN()) 0.0 else it }
            var amt = o.optDouble("amount", 0.0).let { if (it.isNaN()) 0.0 else it }
            val fees = o.optDouble("fees", 0.0).let { if (it.isNaN()) 0.0 else it }
            // The prompt tells Claude that a fee already sits inside the net amount, so the
            // fee has to come back out before the total is turned into a price per share -
            // otherwise cashEffect subtracts it a second time. See Txn.unitPriceFromTotal.
            if (px <= 0.0 && qty > 0 && amt > 0) px = Txn.unitPriceFromTotal(type, qty, amt, fees)
            if (amt <= 0.0 && qty > 0 && px > 0) amt = qty * px
            val dateStr = o.optString("date")
            // Same guard as the offline bridge: a row still carrying the prompt's own
            // placeholder ticker or date is the example being echoed, not a real trade.
            if (ClaudeBridge.isExampleRow(sym, dateStr)) continue
            // A BUY or SELL with no share count is not a trade the ledger can hold: it moves
            // cash but produces no position, so the money disappears into the headline total
            // with nothing on any screen to attribute it to. Counted and reported, never
            // dropped in silence.
            if ((type == TxnType.BUY || type == TxnType.SELL) && qty < 1e-9) {
                unusable++; continue
            }
            val date = Fmt.parseDate(dateStr) ?: Fmt.todayMs()
            val note = o.optString("note").ifBlank { null }
            out.add(
                Txn(
                    type = type, symbol = sym, quantity = qty, price = px,
                    amount = Txn.cashEffect(type, qty, px, amt, fees),
                    fees = fees, date = date,
                    note = if (dateStr.isBlank() || dateStr == "null")
                        listOfNotNull(note, "date estimated").joinToString(" - ") else note,
                    source = "SCREENSHOT"
                )
            )
        }
        return ExtractResult(out, appendUnusable(obj.optString("notes"), unusable), null, text)
    }

    /** Says so on screen when a row had to be left out, rather than quietly shipping fewer. */
    internal fun appendUnusable(notes: String, unusable: Int): String {
        if (unusable <= 0) return notes
        val line = "$unusable row(s) showed a buy or sell with no share count and were left " +
            "out - a trade with no quantity would move your cash without creating a " +
            "position. Add those by hand if they are real."
        return if (notes.isBlank()) line else "$notes\n\n$line"
    }

    // ------------------------------------------------------------ research

    /**
     * The API-key half of the Research tab.
     *
     * It asks EXACTLY the question the offline prompt file asks, from the same data bundle
     * built by [ResearchBridge.bundleJson], and parses the answer with the same
     * [ResearchBridge.parse]. Two routes, one question, one parser - so an answer that works
     * in one path cannot fail in the other, and there is only one place to fix a schema.
     *
     * Returns a [ResearchBridge.Parsed] whose `error` is set on failure; the caller keeps
     * whatever is already on screen rather than blanking the tab.
     */
    suspend fun research(
        key: String,
        model: String,
        bundleJson: String,
        useWebSearch: Boolean
    ): ResearchBridge.Parsed {
        val prompt = ResearchBridge.apiPrompt(bundleJson, useWebSearch)
        val msg = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }
        if (useWebSearch) {
            msg.put("tools", JSONArray().put(JSONObject().apply {
                put("type", "web_search_20250305")
                put("name", "web_search")
                put("max_uses", 10)
            }))
        }

        var r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
        if (!r.ok && useWebSearch && r.code != 401 && r.code != 429) {
            msg.remove("tools")
            r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
        }
        if (!r.ok) return ResearchBridge.Parsed(error = apiError(r.code, r.body))

        val text = textOf(r.body)
        if (stopReason(r.body) == "max_tokens" && ResearchBridge.parse(text).isEmpty) {
            return ResearchBridge.Parsed(
                error = "Claude ran out of room before finishing the JSON. Turn web search " +
                    "off in Settings, or explain one section at a time."
            )
        }
        return ResearchBridge.parse(text)
    }

    suspend fun dayTrading(
        key: String,
        model: String,
        bundleJson: String,
        useWebSearch: Boolean
    ): DayTradingBridge.Parsed {
        val prompt = DayTradingBridge.apiPrompt(bundleJson, useWebSearch)
        val msg = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }
        if (useWebSearch) {
            msg.put("tools", JSONArray().put(JSONObject().apply {
                put("type", "web_search_20250305")
                put("name", "web_search")
                put("max_uses", 10)
            }))
        }

        var r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
        if (!r.ok && useWebSearch && r.code != 401 && r.code != 429) {
            msg.remove("tools")
            r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
        }
        if (!r.ok) return DayTradingBridge.Parsed(error = apiError(r.code, r.body))

        val text = textOf(r.body)
        if (stopReason(r.body) == "max_tokens" && DayTradingBridge.parse(text).isEmpty) {
            return DayTradingBridge.Parsed(
                error = "Claude ran out of room before finishing the JSON. Turn web search " +
                    "off in Settings, or explain fewer picks at a time."
            )
        }
        return DayTradingBridge.parse(text)
    }

    // -------------------------------------------------------------- advice

    suspend fun advice(
        key: String,
        model: String,
        portfolioJson: String,
        headlines: String,
        useWebSearch: Boolean
    ): Advice {
        val prompt = """You are a candid, numerate equity analyst reviewing a real personal portfolio.
Be direct about weaknesses. Do not hedge everything; the user wants a clear read.

PORTFOLIO (live prices, cost basis, realized and unrealized P/L, cash):
$portfolioJson

RECENT HEADLINES for the holdings:
$headlines

${if (useWebSearch) "Use web search to check current price action, valuation, earnings dates and any material news from the last few weeks for each holding before you judge it.\n" else ""}
Return ONLY a JSON object, no markdown fences. The block below is a SCHEMA, not an example
answer: replace every <...> with your own real value. The output must be valid JSON with no
`<`, no `>`, no `...`, and none of the placeholder wording carried through.

{
  "summary": <string - 3-5 sentences on the portfolio as a whole: concentration, sector tilt, cash level, how the realized/unrealized split looks, biggest problem>,
  "risks": <string - 2-4 sentences on the specific risks in THIS portfolio>,
  "actions": [<string - concrete step, most important first>, ...],
  "stocks": [
    {
      "symbol": <string - the ticker, uppercase>,
      "rating": <integer 1-10>,
      "action": <one of "BUY", "ADD", "HOLD", "TRIM", "SELL">,
      "target": <string - a short price or valuation anchor, or "">,
      "reasoning": <string - 2-4 sentences: business quality, valuation, momentum, and what the user's specific cost basis and position size mean for what they should do>
    }
    ... one object per holding
  ]
}

Rate every holding 1-10 where 10 is a high-conviction buy at today's price and 1 means exit now.
Reference the user's actual cost basis and position weight when it changes the recommendation.
Include one entry in "stocks" for every symbol in the portfolio with shares > 0."""

        val msg = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }
        if (useWebSearch) {
            msg.put("tools", JSONArray().put(JSONObject().apply {
                put("type", "web_search_20250305")
                put("name", "web_search")
                put("max_uses", 8)
            }))
        }

        var r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
        // If the account or model cannot use the server-side search tool, retry plainly.
        // A 401/429 is never the tool's fault, so do not waste a second call on those.
        if (!r.ok && useWebSearch && r.code != 401 && r.code != 429) {
            msg.remove("tools")
            r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
        }
        if (!r.ok) return Advice(
            error = apiError(r.code, r.body),
            generated = System.currentTimeMillis(),
            raw = r.body
        )

        val text = textOf(r.body)
        val truncated = stopReason(r.body) == "max_tokens"
        val obj = firstJsonObject(text)
            ?: return Advice(
                error = if (truncated)
                    "Claude ran out of room before finishing the JSON. Try turning web search " +
                        "off in Settings, or analyse again."
                else "Claude replied but not in the expected JSON shape.",
                summary = text.take(4000),
                generated = System.currentTimeMillis(),
                raw = text
            )

        // Placeholder wording is blanked rather than shown as analysis - see ClaudeBridge.
        val actions = ArrayList<String>()
        obj.optJSONArray("actions")?.let { a ->
            for (i in 0 until a.length())
                ClaudeBridge.scrub(a.optString(i)).takeIf { it.isNotBlank() }?.let { actions.add(it) }
        }
        val stocks = ArrayList<StockRating>()
        obj.optJSONArray("stocks")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                stocks.add(
                    StockRating(
                        symbol = o.optString("symbol").uppercase(),
                        rating = o.optInt("rating", 0).coerceIn(0, 10),
                        action = o.optString("action").uppercase(),
                        reasoning = ClaudeBridge.scrub(o.optString("reasoning")),
                        target = ClaudeBridge.scrub(o.optString("target"))
                    )
                )
            }
        }
        val summary = ClaudeBridge.scrub(obj.optString("summary"))
        val risks = ClaudeBridge.scrub(obj.optString("risks"))
        if (summary.isBlank() && risks.isBlank() && actions.isEmpty() &&
            stocks.all { it.reasoning.isBlank() }
        ) return Advice(
            error = "Claude returned the example shape instead of a real answer. Analyse again.",
            generated = System.currentTimeMillis(),
            raw = text
        )
        return Advice(
            summary = summary,
            actions = actions,
            stocks = stocks.sortedByDescending { it.rating },
            risks = risks,
            generated = System.currentTimeMillis(),
            raw = text
        )
    }

    // ------------------------------------------------------------- helpers

    private fun apiError(code: Int, body: String): String {
        if (code == -1) return "Network error: $body"
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(300)
        } catch (e: Exception) { body.take(300) }
        return when (code) {
            401 -> "Invalid API key (401). Check the key in Settings."
            400 -> "Request rejected (400): $msg"
            404 -> "Model not found (404). Pick a different model in Settings."
            429 -> "Rate limited (429). Wait a moment and try again."
            in 500..599 -> "Anthropic server error ($code). Try again."
            else -> "API error $code: $msg"
        }
    }

    private fun stopReason(body: String): String =
        try { JSONObject(body).optString("stop_reason") } catch (e: Exception) { "" }

    /** Concatenate every text block in a Messages API response. */
    private fun textOf(body: String): String {
        return try {
            val arr = JSONObject(body).optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("text"))
            }
            sb.toString()
        } catch (e: Exception) { body }
    }

    /**
     * Pull the payload object out of a text response. Delegates to ClaudeBridge's scanner,
     * which walks EVERY balanced {...} and keeps the richest match - the naive
     * "first brace in the string" version tripped over any prose that contained a brace.
     */
    fun firstJsonObject(text: String): JSONObject? = ClaudeBridge.findObject(text)

}
