package com.tj.portfolio.net

import com.tj.portfolio.data.Advice
import com.tj.portfolio.data.StockRating
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.util.Fmt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The no-API-key path.
 *
 * The app writes a prompt file to Downloads; the user attaches it to a chat in the
 * Claude app; Claude answers with a fenced JSON block; the user saves that answer as a
 * file and imports it back here. Nothing is billed to an API key.
 */
data class BridgeResult(
    val advice: Advice? = null,
    val transactions: List<Txn> = emptyList(),
    val notes: String = "",
    val error: String? = null
)

object ClaudeBridge {

    /**
     * First line of every prompt file the app writes.
     *
     * A prompt file is the QUESTION, not the ANSWER, and it sits in Downloads right next to
     * the reply the user saves - so it gets picked by mistake in the file chooser. Before
     * v5.2 the app happily parsed it: the worked example inside it was valid JSON carrying
     * the right keys, so the placeholder text ("3-5 sentences on the portfolio as a
     * whole...") was imported and rendered as TJ's actual portfolio review. `parse()` now
     * recognises this marker and says so in plain language instead.
     */
    const val PROMPT_MARK = "portfolio-app-prompt-file"

    /**
     * THE INSTRUCTION THAT ASKS CLAUDE FOR AN ACTUAL FILE, NOT JUST A CHAT MESSAGE (Round 74).
     * Shared by every prompt this app writes, so the fix cannot land in one and be missed in
     * the other three.
     *
     * THE BUG THIS FIXES. Every prompt used to say only *"save that reply as a .txt or .md
     * file"* - putting the file-creation step entirely on the user, after the fact, by hand.
     * On a phone that means copying Claude's whole chat reply out and pasting it into some
     * other app that can save plain text, which is not obvious and easy to get wrong - and is
     * exactly what one report of "it gave me an answer in the chat and a copy-and-paste JSON
     * code, but the app is looking for a file" described. Claude's own apps can write a file
     * directly into the chat as a download; asking for that FIRST - with the old manual
     * copy-paste kept only as a fallback for a client that genuinely cannot make one - gets a
     * real file with nothing more than tapping the same "Import answer" button that was
     * already there.
     */
    const val FILE_DELIVERY_INSTRUCTIONS = """**Create your answer as a downloadable file, not only a chat message.** Use your file or code tool to write a single `.md` file containing your full answer, ending with the fenced ```json code block described below - then I can download that file straight from this chat and import it, with nothing to copy or retype. If your interface genuinely cannot create a file, put the same fenced block at the end of your chat reply instead; I will save the whole reply as a `.txt` or `.md` file myself before importing it."""

    private const val PROMPT_HEADER =
        "<!-- $PROMPT_MARK: this file is the QUESTION for Claude, not the ANSWER. " +
            "Attach it to a chat in the Claude app - do NOT import this file back. -->"

    /** Older prompt files (written before v5.2) carry no marker; match their headings. */
    private val LEGACY_PROMPT_MARKS = listOf(
        "# portfolio review request",
        "# brokerage screenshot -> transactions"
    )

    /**
     * The answer shape, written as a SCHEMA rather than as a filled-in example.
     *
     * The `<...>` placeholders are deliberately not valid JSON, so this block can never be
     * parsed as a payload - not when it sits in the prompt file, and not if the model echoes
     * the template back before answering. That is the structural half of the v5.2 fix; the
     * fingerprint check in [templateHits] is the belt to its braces.
     */
    private const val ADVICE_SHAPE = """{
  "portfolioAppResponse": 1,
  "advice": {
    "summary": <string - 3-5 sentences on the portfolio as a whole: concentration, sector tilt, cash level, how the realized vs unrealized split looks, and the single biggest problem>,
    "risks": <string - 2-4 sentences on the specific risks in THIS portfolio>,
    "actions": [<string - most important concrete step first>, <string - next step>, ...],
    "stocks": [
      {
        "symbol": <string - the ticker, uppercase>,
        "rating": <integer 1-10, where 10 is the strongest conviction to own it here>,
        "action": <one of "BUY", "ADD", "HOLD", "TRIM", "SELL">,
        "target": <string - a short price target or valuation note, or "">,
        "reasoning": <string - 2-3 sentences: why this rating, what would change it>
      }
      ... one object per holding
    ]
  }
}"""

    /** Prompt file for the Advice tab. */
    fun advicePrompt(portfolioJson: String, headlines: String): String = """
$PROMPT_HEADER

# Portfolio review request

I am attaching a snapshot of my real brokerage portfolio, exported from my portfolio
tracker app. Please act as a candid, numerate equity analyst. Be direct about
weaknesses - I want a clear read, not hedging on every sentence.

Please:

1. Judge the portfolio as a whole: concentration, sector tilt, cash level, the split
   between realized and unrealized P/L, and the biggest single problem with it.
2. Rate **every** holding from **1 to 10** (10 = strongest conviction to own here).
3. Give each holding an action: BUY, ADD, HOLD, TRIM, or SELL.
4. List concrete next steps in priority order.
5. Search the web for current price action, valuation, earnings dates and any material
   news from the last few weeks before you judge each holding.

## IMPORTANT - how to answer

$FILE_DELIVERY_INSTRUCTIONS Include one entry in "stocks" for every holding.

**The block below is a SCHEMA, not an example answer.** Every `<...>` is a description of
what belongs there - replace each one with your own real value. Your JSON must be valid:
no `<`, no `>`, no `...`, no comments, no trailing commas, no extra keys, and none of the
placeholder wording copied through.

```json
$ADVICE_SHAPE
```

Import the file (or your saved reply) in the app's Advice tab.

---

## PORTFOLIO SNAPSHOT

Generated ${Fmt.day(System.currentTimeMillis())}. All figures in USD.

```json
$portfolioJson
```

## RECENT HEADLINES FOR THESE HOLDINGS

$headlines
""".trimIndent()

    /** Prompt file for screenshot import without an API key. */
    fun screenshotPrompt(
        lastImport: String,
        knownSymbols: List<String>,
        alreadyHave: String = ""
    ): String = """
$PROMPT_HEADER

# Brokerage screenshot -> transactions

**Attach your Ally Invest screenshots to this chat along with this file.**

Read every transaction, holding row and balance line visible in the attached
screenshots and turn them into structured JSON for my portfolio tracker app.

$lastImport

Known symbols already in the app: ${if (knownSymbols.isEmpty()) "(none yet)" else knownSymbols.joinToString(", ")}

## Transactions I ALREADY have - do not send these back

Every line below is already recorded. If a row in the screenshots matches one of these on
date + symbol + quantity, **leave it out entirely**. Only send rows that are not in this
list. If a screenshot is entirely made up of rows I already have, return an empty
"transactions" array and say so in "notes".

$alreadyHave

## Rules

- `type` must be one of: BUY, SELL, DEPOSIT, WITHDRAWAL, DIVIDEND, INTEREST, FEE.
- `fees` is ONLY a commission or regulatory fee printed on the row itself. Use 0 when the
  screenshot does not show one. This is an ALLY INVEST account: Ally charges NO commission
  on stock and ETF trades, so a buy should almost always be 0. Never estimate, infer or
  back-calculate a fee from the totals - a fee that was charged is already inside the net
  amount, and adding it again overstates the cost basis.
- `amount` is always a **positive** number - the app applies the sign itself.
- `date` is `YYYY-MM-DD`. If a row shows no date, use the statement/period date and
  say so in `notes`.
- Numbers must not contain commas, currency symbols, or `%`.
- Never invent a row you cannot actually see. If a value is unreadable, omit that row
  and explain in `notes`.
- Include EVERY new row you can see, even from the same day as rows I already have -
  I may have traded the same stock more than once in a day. The test is the whole line
  (date + symbol + quantity + amount), not just the date.
- If a screenshot shows a **current holdings list** rather than trades, emit one BUY per
  row using the average cost shown, set `note` to `position snapshot`, and say clearly
  in `notes` that these are snapshots rather than real trades.

## IMPORTANT - how to answer

End your reply with a single fenced ```json code block in exactly this shape.

**The two rows below are placeholders showing the field layout - they are NOT data.** The
ticker `$EXAMPLE_SYMBOL` and the date `$EXAMPLE_DATE` exist only to mark them as fake, and my app
throws away any row that still carries either. Never copy them into your answer; every row
you send must come from something you can actually see in a screenshot.

```json
{
  "portfolioAppResponse": 1,
  "notes": "anything ambiguous, unreadable, or worth flagging - one short paragraph",
  "transactions": [
    {"type":"BUY","symbol":"$EXAMPLE_SYMBOL","quantity":3,"price":227.44,"amount":682.32,"fees":0,"date":"$EXAMPLE_DATE","note":"PLACEHOLDER - do not copy"},
    {"type":"DEPOSIT","symbol":null,"quantity":0,"price":0,"amount":500,"fees":0,"date":"$EXAMPLE_DATE","note":"PLACEHOLDER - do not copy"}
  ]
}
```

Then save that reply as a `.txt` or `.md` file and import it in the app's Activity tab.
""".trimIndent()

    // -------------------------------------------------- template detection

    /** Placeholder ticker and date in the screenshot prompt's worked example. */
    const val EXAMPLE_SYMBOL = "XXXX"
    const val EXAMPLE_DATE = "1900-01-01"

    /**
     * Instruction-voice fragments that only ever appear in a template, never in a real
     * answer. Drawn from BOTH prompt paths - the offline one here and the API one in
     * [Claude] - and from the wording used before v5.2, because prompt files written by
     * older builds are still sitting in TJ's Downloads folder and are exactly the files
     * most likely to be picked by mistake.
     *
     * Matching is done on the lowercased JSON text of a candidate block.
     */
    private val TEMPLATE_PHRASES = listOf(
        "sentences on the portfolio as a whole",
        "sentences on the specific risks in this portfolio",
        "most important concrete step first",
        "concrete step, most important first",
        "optional price target or valuation note",
        "a short price target or valuation note",
        "why this rating, what would change it",
        "business quality, valuation, momentum",
        "anything ambiguous, unreadable, or worth flagging",
        "anything ambiguous or unreadable, one short paragraph",
        "buy | add | hold | trim | sell",
        "the ticker, uppercase",
        "one object per holding",
        "placeholder - do not copy",
        // Round 54 - the Research bridge's own schema wording.
        "the specific reason this stock is being talked about today",
        "why this is a good buy at today's price",
        "what is actually going wrong at this company",
        "the next dated event that could move it",
        "the main way this call goes wrong",
        "anything the app's numbers got wrong or missed"
    )

    private fun templateHits(json: String): Int {
        val low = json.lowercase()
        return TEMPLATE_PHRASES.count { low.contains(it) }
    }

    /**
     * Two or more placeholder fragments in one block means the template, not an answer.
     *
     * The threshold is 2 rather than 1 on purpose: a real review that happens to leave one
     * stray placeholder in a single `target` field is still a real review, and [scrub]
     * blanks that one field rather than throwing the whole thing away.
     */
    private fun isTemplate(json: String): Boolean = templateHits(json) >= 2

    /** A field whose value is still the instruction text is shown as empty, not as advice. */
    fun scrub(s: String): String =
        if (templateHits(s) > 0) "" else s

    /** True when this is one of the app's own prompt files rather than a reply. */
    fun isPromptFile(text: String): Boolean {
        if (text.contains(PROMPT_MARK)) return true
        val low = text.lowercase()
        // A legacy prompt file is its heading PLUS the block it hands to Claude - a reply
        // that merely quotes the heading back is not a prompt file.
        return LEGACY_PROMPT_MARKS.any { low.contains(it) } &&
            (low.contains("## important - how to answer") || low.contains("## portfolio snapshot"))
    }

    /** The advice the app already has cached may itself be placeholder text - see v5.2. */
    fun isPlaceholderAdvice(summary: String, risks: String, reasonings: List<String>): Boolean =
        templateHits((listOf(summary, risks) + reasonings).joinToString(" ")) >= 2

    /** Rows carrying the prompt's placeholder ticker or date are never real. */
    fun isExampleRow(symbol: String?, dateStr: String): Boolean {
        if (symbol != null && symbol.equals(EXAMPLE_SYMBOL, true)) return true
        if (dateStr.startsWith(EXAMPLE_DATE)) return true
        // Pre-v5.2 prompt files used realistic-looking demo rows. Only reject them when BOTH
        // appear together, which is the template and could not plausibly be a real pair of
        // trades - a lone 3-share NVDA buy stays importable.
        return false
    }

    private const val LEGACY_DEMO_BUY = "\"symbol\":\"NVDA\",\"quantity\":3,\"price\":227.44"
    private const val LEGACY_DEMO_DEP = "\"amount\":500,\"fees\":0,\"date\":\"2026-08-01\""

    /** The pre-v5.2 worked example, recognised only as the complete pair. */
    private fun isLegacyDemoBlock(json: String): Boolean {
        val flat = json.replace(" ", "")
        return flat.contains(LEGACY_DEMO_BUY) && flat.contains(LEGACY_DEMO_DEP)
    }

    // ------------------------------------------------------------- parsing

    /** Accepts a raw Claude reply, a fenced block, or a bare JSON file. */
    fun parse(text: String): BridgeResult {
        val obj = findObject(text)
            ?: return BridgeResult(
                error = if (isPromptFile(text))
                    "That is the prompt file this app wrote, not Claude's answer. Attach it to " +
                        "a chat in the Claude app, then save what Claude replies as a .txt or " +
                        ".md file and import that one."
                else
                    "No JSON block found in that file. Make sure you saved Claude's whole reply, " +
                        "including the ```json block."
            )

        // findObject already discards template blocks, so reaching here with nothing left
        // while the file clearly IS a prompt file means the user picked the wrong file.
        val notes = obj.optString("notes")
        val demoBlock = isLegacyDemoBlock(obj.toString())
        var advice: Advice? = null
        val adviceObj = obj.optJSONObject("advice")
            ?: if (obj.has("summary") || obj.has("stocks")) obj else null
        if (adviceObj != null && (adviceObj.has("summary") || adviceObj.has("stocks"))) {
            val actions = ArrayList<String>()
            val aa = adviceObj.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until aa.length()) scrub(aa.optString(i)).takeIf { it.isNotBlank() }?.let { actions.add(it) }
            val stocks = ArrayList<StockRating>()
            val sa = adviceObj.optJSONArray("stocks") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val s = sa.optJSONObject(i) ?: continue
                val sym = s.optString("symbol").uppercase()
                if (sym.isBlank()) continue
                stocks.add(
                    StockRating(
                        symbol = sym,
                        rating = s.optInt("rating", 0).coerceIn(0, 10),
                        action = s.optString("action"),
                        reasoning = scrub(s.optString("reasoning")),
                        target = scrub(s.optString("target"))
                    )
                )
            }
            val built = Advice(
                summary = scrub(adviceObj.optString("summary")),
                actions = actions,
                stocks = stocks,
                risks = scrub(adviceObj.optString("risks")),
                generated = System.currentTimeMillis()
            )
            // Everything scrubbed away means the block was the template after all.
            val empty = built.summary.isBlank() && built.risks.isBlank() &&
                built.actions.isEmpty() && built.stocks.all { it.reasoning.isBlank() }
            advice = if (empty) null else built
        }

        val txns = ArrayList<Txn>()
        var unusable = 0
        val ta = obj.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until ta.length()) {
            val o = ta.optJSONObject(i) ?: continue
            val type = o.optString("type").uppercase()
            // IMPORTABLE, not ALL - an imported reply may never carry a SPLIT. See its note.
            if (type !in TxnType.IMPORTABLE) continue
            val sym = o.optString("symbol").takeIf { it.isNotBlank() && it != "null" }?.uppercase()
            val qty = o.optDouble("quantity", 0.0).nz()
            var px = o.optDouble("price", 0.0).nz()
            var amt = o.optDouble("amount", 0.0).nz()
            val fees = o.optDouble("fees", 0.0).nz()
            // The prompt says a fee is already inside the net amount, so it has to come back
            // out before the total becomes a price per share - or cashEffect charges it
            // twice. Same helper the transaction editor and the API path use.
            if (px <= 0.0 && qty > 0 && amt > 0) px = Txn.unitPriceFromTotal(type, qty, amt, fees)
            if (amt <= 0.0 && qty > 0 && px > 0) amt = qty * px
            val dateStr = o.optString("date")
            // A row from the prompt's worked example is not something the user owns.
            if (isExampleRow(sym, dateStr) || demoBlock) continue
            // A BUY or SELL with no share count moves cash but creates no position, so the
            // money vanishes into the portfolio total unattributed. Reported, not dropped.
            if ((type == TxnType.BUY || type == TxnType.SELL) && qty < 1e-9) {
                unusable++; continue
            }
            val date = Fmt.parseDate(dateStr) ?: Fmt.todayMs()
            val note = o.optString("note").ifBlank { null }
            txns.add(
                Txn(
                    type = type, symbol = sym, quantity = qty, price = px,
                    amount = Txn.cashEffect(type, qty, px, amt, fees),
                    fees = fees, date = date,
                    note = if (dateStr.isBlank() || dateStr == "null")
                        listOfNotNull(note, "date estimated").joinToString(" - ") else note,
                    source = "CLAUDE_FILE"
                )
            )
        }

        val allNotes = Claude.appendUnusable(notes, unusable)

        if (advice == null && txns.isEmpty()) {
            return BridgeResult(
                notes = allNotes,
                error = if (isPromptFile(text))
                    "That is the prompt file this app wrote, not Claude's answer. Attach it to " +
                        "a chat in the Claude app, then save what Claude replies as a .txt or " +
                        ".md file and import that one."
                else
                    "That file only contained the example shape from the prompt, not a real " +
                        "answer. Make sure you saved Claude's whole reply, including its own " +
                        "```json block at the end."
            )
        }
        return BridgeResult(advice, txns, allNotes)
    }

    private fun Double.nz(): Double = if (isNaN() || isInfinite()) 0.0 else this

    /**
     * Finds the JSON object that actually carries the payload: scans every balanced
     * `{...}` in the text and keeps the first one with a key we recognise, preferring
     * later blocks (Claude puts the answer at the end).
     */
    /** The payload keys an ADVICE / SCREENSHOT reply can carry. */
    private val ADVICE_KEYS =
        listOf("portfolioAppResponse", "transactions", "advice", "stocks", "summary")

    fun findObject(text: String): JSONObject? = findObject(text, ADVICE_KEYS)

    /**
     * @param wanted which payload keys identify a real answer. The Research tab passes its
     *        own set ([ResearchBridge]) so the same proven scanner serves both bridges - the
     *        alternative was a second brace walker, and this one is the version that was
     *        port-tested in Python against realistic replies.
     */
    fun findObject(text: String, wanted: List<String>): JSONObject? {
        val candidates = ArrayList<JSONObject>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            var depth = 0
            var j = i
            var inStr = false
            var esc = false
            while (j < text.length) {
                val c = text[j]
                when {
                    esc -> esc = false
                    c == '\\' && inStr -> esc = true
                    c == '"' -> inStr = !inStr
                    !inStr && c == '{' -> depth++
                    !inStr && c == '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            if (depth == 0 && j < text.length) {
                val slice = text.substring(i, j + 1)
                runCatching { JSONObject(slice) }
                    .getOrNull()
                    ?.let { o ->
                        // A block made of the prompt's own placeholder wording is the
                        // template being echoed back, not an answer. Dropping it here means
                        // it can never win the ranking below, whatever else is in the file.
                        if (wanted.any { o.has(it) } && !isTemplate(slice)) candidates.add(o)
                    }
                i = j + 1
            } else i++
        }
        // Rank by how many payload keys a block has, then by how many transactions it
        // actually carries, and break any remaining tie in favour of the LAST block.
        //
        // This used to be `maxByOrNull { keyCount * 1000 + o.length() }`, which returns the
        // FIRST maximum on a tie - the exact opposite of the comment above it. The prompt
        // file shows Claude a worked example with the same three keys and the same key
        // count as a real answer, so if Claude echoed the template before answering, the
        // example won the tie and the app offered to import ITS rows: a 3-share NVDA buy
        // and a $500 deposit that never happened.
        fun score(o: JSONObject): Int =
            wanted.count { o.has(it) } * 1_000_000 +
                (o.optJSONArray("transactions")?.length() ?: 0) * 1_000 +
                o.length()
        return candidates.reversed().maxByOrNull { score(it) }
    }
}
