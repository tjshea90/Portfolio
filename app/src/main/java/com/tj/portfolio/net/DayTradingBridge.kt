package com.tj.portfolio.net

import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.util.Fmt
import com.tj.portfolio.util.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE DAY TRADING TAB'S NO-API-KEY PATH - same shape as [ResearchBridge], one section instead
 * of three.
 *
 * WHAT CLAUDE IS, AND IS NOT, ASKED FOR HERE. See [ResearchScore.dayTrading]'s header for the
 * feasibility finding this whole section rests on: a genuine same-day price forecast is not
 * something this app, or Claude, can honestly deliver. So this bridge asks Claude for exactly
 * two things a language model that can search the web is actually good at - explaining WHY
 * each name is in play today, in plain English, and naming the SPECIFIC risk that could
 * invalidate the setup (an earnings print tonight, a halt, a lockup expiry) - and nothing that
 * would look like Claude inventing a price. `entry`/`stop`/`target` are the app's OWN computed
 * risk-management levels ([ResearchScore.tradeLevels]), sent to Claude as context and never
 * accepted back: same rule [ResearchRow.conviction] already states for `score`, applied to the
 * numbers that matter most on this particular tab.
 */
object DayTradingBridge {

    const val PROMPT_FILE = "claude-daytrading-prompt.md"

    /**
     * The answer shape. Every `<...>` is deliberately invalid JSON so this block cannot be
     * parsed as a payload if the model echoes it back before answering.
     */
    private const val SHAPE = """{
  "portfolioAppResponse": 1,
  "dayTrading": {
    "asOf": <string - today's date, YYYY-MM-DD>,
    "picks": [
      {
        "symbol": <string - ticker, uppercase>,
        "why": <string - 1-3 sentences: the SPECIFIC reason this stock is in play today. Name the event: the short squeeze, the earnings beat, the FDA decision, the guidance, the halt and reopen, the analyst upgrade. Never "high investor interest">,
        "risk": <string - 1 sentence: the specific thing that could invalidate this setup today, e.g. an earnings print after the close, a lockup expiry, a pending halt, a Fed announcement>,
        "conviction": <integer 1-10, 10 = strongest case that this is genuinely in play right now>
      }
      ... one per stock in the list below, PLUS any genuinely in-play stock you find that the app missed - see below
    ],
    "notes": <string - anything the app's numbers got wrong or missed, one short paragraph>
  }
}"""

    /**
     * Builds the prompt file.
     *
     * [set] is trimmed by the caller to the rows actually on screen, same rule [ResearchBridge]
     * follows.
     */
    fun prompt(set: ResearchSet, holdings: List<String>, watchlist: List<String>): String {
        val bundle = bundleJson(set, holdings, watchlist)
        return """
<!-- ${ClaudeBridge.PROMPT_MARK}: this file is the QUESTION for Claude, not the ANSWER. Attach it to a chat in the Claude app - do NOT import this file back. -->

# Day trading watchlist request

I am attaching live market data exported from my personal Android portfolio app. The app has
screened the whole market for stocks that are OBJECTIVELY IN PLAY RIGHT NOW - unusually heavy
volume, a real price move already under way, elevated wallstreetbets/news attention, a
technical breakout, or a short-squeeze-prone setup - and scored them with its own arithmetic.
It cannot explain them in plain English, and it cannot search the web. That is what I need
from you.

## What this list is, and is not

This is NOT a prediction of which stocks will keep rising - no system built on free public
data can honestly promise that, and I don't want you to pretend otherwise. It is a list of
stocks that are ALREADY moving, with real volume behind the move, for a reader who is about to
make their own trading decision and wants to understand WHY each name showed up.

Each row also carries `entry`, `stop` and `target` - these are the APP'S OWN computed
risk-management levels (today's price, a stop sized at 1.5x this stock's own 14-day Average
True Range, and a 2:1 reward-to-risk target), NOT a forecast of where the price is going. Do
not replace or second-guess these numbers - just explain the setup around them. When present,
`atr14`, `vwap` and `openingRangeHigh`/`openingRangeLow` are the real technicals those levels
came from - use them if they help explain the setup (e.g. "trading above VWAP" or "broke the
opening range").

Each row carries the app's own score out of 100 and the reason lines behind it, so you can see
exactly what the app based its ranking on.

## What I want back

1. For **every** row below, one plain-English explanation of WHY it is in play today. Name the
   specific event - the short squeeze, the earnings beat, the FDA news, the halt - never
   "high investor interest."
2. **Search the web** for what is actually happening with these names right now, and correct
   the app where its data is stale or wrong - say so in `notes`.
3. For every row, name the one SPECIFIC thing that could go wrong today - an earnings print
   after the close, a lockup expiry, a scheduled Fed announcement, a pending halt.
4. If a stock is genuinely in play today for a real reason - a short squeeze, breaking news, a
   halt and reopen - and the app missed it, ADD it. A new object with a symbol not in my data
   is fine; the app will price it and compute its own risk levels for it. Only add stocks
   trading at $2 a share or more.

Be candid. If a row does not actually look like it is in play, or the app's score looks wrong,
say so in `notes` rather than inventing a reason.

## IMPORTANT - how to answer

End your reply with a single fenced ```json code block matching the schema below.

**The block is a SCHEMA, not an example answer.** Every `<...>` is a description of what
belongs there - replace each with your own real value. Your JSON must be valid: no `<`, no
`>`, no `...`, no comments, no trailing commas, and none of the placeholder wording copied
through.

```json
$SHAPE
```

Then save that reply as a `.txt` or `.md` file and import it in the app: Watch tab -> Research
-> Day Trading -> Import Claude's answer.

---

## LIVE DATA FROM THE APP

Generated ${Fmt.day(System.currentTimeMillis())}. Prices in USD, as of the app's last refresh.

```json
$bundle
```
""".trimIndent()
    }

    /**
     * The data bundle, also used verbatim by the API path - same reason [ResearchBridge]'s
     * does.
     */
    fun bundleJson(set: ResearchSet, holdings: List<String>, watchlist: List<String>): String {
        val rows = JSONArray().also { arr ->
            set.dayTrading.forEach { r ->
                arr.put(JSONObject().apply {
                    put("symbol", r.symbol)
                    if (r.name.isNotBlank()) put("name", r.name)
                    if (r.price > 0) put("price", round2(r.price))
                    if (r.changePct != 0.0) put("dayChangePct", round2(r.changePct))
                    put("appScore", r.score)
                    if (r.reasons.isNotEmpty()) put("appReasons", JSONArray(r.reasons))
                    if (r.mentions > 0) put("wsbMentions", r.mentions)
                    if (r.newsCount > 0) put("headlinesToday", r.newsCount)
                    if (r.headline.isNotBlank()) put("topHeadline", r.headline)
                    if (r.catalyst.isNotBlank()) put("nextEvent", r.catalyst)
                    if (r.entryPrice > 0) put("entry", round2(r.entryPrice))
                    if (r.stopPrice > 0) put("stop", round2(r.stopPrice))
                    if (r.targetPrice > 0) put("target", round2(r.targetPrice))
                    // THE REAL TECHNICALS THE LEVELS ABOVE WERE COMPUTED FROM (Round 68) - so
                    // Claude's explanation can reference this stock's own ATR/VWAP/opening
                    // range instead of describing the entry/stop/target in the abstract. Zero
                    // for a row the live enrichment pass has not reached yet - see
                    // `net/DayTradingTechnicals.kt`'s header for what these are and why.
                    if (r.atr > 0) put("atr14", round2(r.atr))
                    if (r.vwap > 0) put("vwap", round2(r.vwap))
                    if (r.openingRangeHigh > 0) put("openingRangeHigh", round2(r.openingRangeHigh))
                    if (r.openingRangeLow > 0) put("openingRangeLow", round2(r.openingRangeLow))
                })
            }
        }

        val root = JSONObject().apply {
            put("app", "portfolio-day-trading")
            put("asOf", Fmt.day(System.currentTimeMillis()))
            put("dataAgeMinutes", if (set.generated > 0)
                (System.currentTimeMillis() - set.generated) / 60000L else 0L)
            put("sources", set.sources.ifBlank { Research.SOURCES })
            if (set.warnings.isNotEmpty()) put("feedProblems", JSONArray(set.warnings))
            if (holdings.isNotEmpty()) put("iAlreadyHold", JSONArray(holdings))
            if (watchlist.isNotEmpty()) put("onMyWatchlist", JSONArray(watchlist))
            put("minSharePrice", 2)
            put("picks", rows)
        }
        return root.toString(2)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /**
     * The same request, phrased for the Messages API rather than for a chat window.
     */
    fun apiPrompt(bundleJson: String, useWebSearch: Boolean): String = """
You are a candid, numerate day-trading desk analyst. Below is live market data from a personal
Android portfolio app. The app has screened the whole market for stocks OBJECTIVELY IN PLAY
RIGHT NOW - unusually heavy volume, a real price move already under way, elevated
wallstreetbets/news attention, a technical breakout, or a short-squeeze-prone setup - and
scored them with its own arithmetic. It cannot explain them and it cannot search the web.

This is NOT a request to predict which stocks will keep rising - no system built on free
public data can honestly promise that. Each row carries `entry`, `stop` and `target`: the
app's OWN computed risk-management levels (today's price, a volatility-sized stop, a 2:1
reward-to-risk target), not a forecast. Do not replace or second-guess these numbers.

DATA:
$bundleJson

${if (useWebSearch) "Search the web for what is actually happening with these names right now before you write anything, and correct the app's data where it is stale - say so in \"notes\".\n" else ""}
For every row, write one plain-English explanation of WHY it is in play today - name the
specific event, never "high investor interest" - and name the one specific thing that could
go wrong today (earnings after the close, a lockup expiry, a scheduled Fed announcement, a
pending halt). If a stock is genuinely in play today and the app missed it, add it - only
stocks trading at \$2 a share or more. Be candid in "notes" if a row does not actually look
like it is in play.

Return ONLY a JSON object, no markdown fences. The block below is a SCHEMA, not an example
answer: replace every <...> with your own real value. The output must be valid JSON with no
`<`, no `>`, no `...`, and none of the placeholder wording carried through.

$SHAPE
""".trimIndent()

    // ------------------------------------------------------------------- parsing

    data class Parsed(
        val picks: List<ResearchRow> = emptyList(),
        val notes: String = "",
        val error: String? = null
    ) {
        val isEmpty: Boolean get() = picks.isEmpty()
    }

    /** True when this text carries a day-trading payload at all - used to route an import. */
    fun looksLikeDayTrading(text: String): Boolean =
        ClaudeBridge.findObject(text, WANTED)?.has("dayTrading") == true

    private val WANTED = listOf("dayTrading", "portfolioAppResponse")

    /**
     * Accepts a raw Claude reply, a fenced block, or a bare JSON file - the same three shapes
     * [ClaudeBridge.parse] accepts.
     */
    fun parse(text: String): Parsed {
        if (ClaudeBridge.isPromptFile(text)) return Parsed(
            error = "That is the prompt file this app wrote, not Claude's answer. Attach it to " +
                "a chat in the Claude app, then save what Claude replies as a .txt or .md " +
                "file and import that one."
        )
        val root = ClaudeBridge.findObject(text, WANTED)
            ?: return Parsed(
                error = "No day-trading JSON found in that file. Make sure you saved Claude's " +
                    "whole reply, including the ```json block at the end."
            )
        // A reply that skipped the wrapper and returned "picks" at the top level is still a
        // valid answer - accept it rather than making the user re-ask.
        val bare = root.has("picks")
        val dt = root.optJSONObject("dayTrading") ?: (if (bare) root else null)
        if (dt == null) return Parsed(
            error = "That file has JSON in it, but no \"dayTrading\" block. It may be the " +
                "answer to a different prompt - the Research tab imports those."
        )

        val arr = dt.optJSONArray("picks") ?: JSONArray()
        val out = ArrayList<ResearchRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.text("symbol").uppercase().trim()
            if (sym.isBlank() || sym.length > 6) continue
            val why = ClaudeBridge.scrub(o.text("why"))
            val risk = ClaudeBridge.scrub(o.text("risk"))
            // A row with nothing but a ticker adds nothing and would blank a good app row.
            if (why.isBlank() && risk.isBlank()) continue
            out.add(
                ResearchRow(
                    symbol = sym,
                    why = why,
                    // NOT `score` - see the class header. A model's conviction never
                    // overwrites the app's own arithmetic.
                    conviction = o.optInt("conviction", 0).coerceIn(0, 10),
                    catalyst = risk
                )
            )
        }
        val notes = ClaudeBridge.scrub(dt.text("notes"))
        if (out.isEmpty()) return Parsed(
            notes = notes,
            error = "That file only contained the example shape from the prompt, not a real " +
                "answer. Make sure you saved Claude's whole reply."
        )
        return Parsed(picks = out, notes = notes)
    }

    /**
     * Fold Claude's answer into the rows the app already has.
     *
     * The app's score AND its entry/stop/target risk levels SURVIVE - they are reproducible
     * arithmetic and a model's conviction is not, same rule [ResearchBridge.merge] follows for
     * `score`. Rows Claude adds arrive with no price and no risk levels yet; the caller fills
     * both from a live quote, the same way a Research import fills price for an added row.
     */
    fun merge(existing: List<ResearchRow>, incoming: List<ResearchRow>): List<ResearchRow> {
        if (incoming.isEmpty()) return existing
        val byIncoming = incoming.associateBy { it.symbol }
        val merged = existing.map { row ->
            val c = byIncoming[row.symbol] ?: return@map row
            row.copy(
                why = c.why.ifBlank { row.why },
                catalyst = c.catalyst.ifBlank { row.catalyst },
                conviction = if (c.conviction > 0) c.conviction else row.conviction
            )
        }
        val known = existing.map { it.symbol }.toSet()
        // De-duplicated within the incoming list too - same reason [ResearchBridge.merge]
        // does this: a keyed LazyColumn crashes on a repeated key, and a model repeating a
        // ticker is not a hypothetical.
        val added = incoming.filter { it.symbol !in known }.distinctBy { it.symbol }
        return merged + added
    }
}
