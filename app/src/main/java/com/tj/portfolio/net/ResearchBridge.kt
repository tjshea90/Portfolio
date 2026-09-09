package com.tj.portfolio.net

import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.util.Fmt
import com.tj.portfolio.util.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE RESEARCH TAB'S NO-API-KEY PATH.
 *
 * TJ's requirement, verbatim: a file he can hand to the Claude app "with no explanation from
 * me", and an answer file he can import back that fills the Research section with current
 * data. So the prompt file has to be self-contained in three separate ways:
 *
 *  1. It says what the app is, what each section means, and what Claude is being asked to do
 *     - because the chat it lands in has no history.
 *  2. It CARRIES THE LIVE DATA. Every candidate the app ranked, with its price, its score,
 *     and the reasons behind that score, is embedded as JSON. Claude does not have to guess
 *     what is on TJ's screen, and can explain the exact rows he is looking at.
 *  3. It states the answer schema precisely enough that the reply parses first time, and
 *     marks itself so it can never be mistaken for the answer ([ClaudeBridge.PROMPT_MARK] -
 *     the v5.2 bug this app already paid for once).
 *
 * The reply may do more than explain. If Claude - which can search the web and knows what
 * happened since the app's last refresh - returns symbols the app did not have, those are
 * added to the section rather than dropped. That is the "fill the research section with
 * current data" half of the request.
 */
object ResearchBridge {

    const val PROMPT_FILE = "claude-research-prompt.md"

    /**
     * The answer shape, as a SCHEMA. Every `<...>` is deliberately invalid JSON so this block
     * cannot be parsed as a payload if the model echoes it back before answering.
     */
    private const val SHAPE = """{
  "portfolioAppResponse": 1,
  "research": {
    "asOf": <string - today's date, YYYY-MM-DD>,
    "trending": [
      {
        "symbol": <string - ticker, uppercase>,
        "why": <string - 1-2 sentences: the specific reason this stock is being talked about TODAY. Name the event: the earnings print, the guidance, the deal, the filing, the product, the short report. Never "high investor interest">
      }
      ... one per stock in the trending list below, same order
    ],
    "best": [
      {
        "symbol": <string - ticker, uppercase>,
        "why": <string - 2-4 sentences: why this is a good buy AT TODAY'S PRICE. Business, valuation, what analysts see, and what specifically could push it higher>,
        "catalyst": <string - the next dated event that could move it, or "">,
        "target": <string - a price or valuation anchor with its basis, or "">,
        "conviction": <integer 1-10, 10 = strongest buy case>
      }
      ... one per stock in the best list below
    ],
    "etfs": [
      {
        "symbol": <string - ticker, uppercase>,
        "why": <string - 2-4 sentences: what this fund actually holds, what it has returned over the long run, what it costs, and who it suits. Say plainly if it does not deserve a place on a best-ETF list>,
        "category": <string - what kind of fund it is in a few words, e.g. "broad US equity index", "short-term Treasuries", "semiconductor sector">,
        "conviction": <integer 1-10, 10 = strongest case as a long-term core holding>
      }
      ... one per fund in the ETF list below, PLUS any fund you add - see the ETF note above
    ],
    "notes": <string - anything the app's numbers got wrong or missed, one short paragraph>
  }
}"""

    /**
     * Builds the prompt file.
     *
     * [set] is trimmed by the caller to the rows actually on screen - there is no point
     * asking for an explanation of row 34 of a list that shows ten.
     */
    fun prompt(set: ResearchSet, holdings: List<String>, watchlist: List<String>): String {
        val bundle = bundleJson(set, holdings, watchlist)
        return """
<!-- ${ClaudeBridge.PROMPT_MARK}: this file is the QUESTION for Claude, not the ANSWER. Attach it to a chat in the Claude app - do NOT import this file back. -->

# Stock research request

I am attaching live market data exported from my personal Android portfolio app. The app has
already built these lists from free market feeds and scored them with its own arithmetic. It
cannot explain them in plain English, and it cannot search the web. That is what I need from
you.

## What the lists are

- **Trending** - stocks being talked about right now. The app blends r/wallstreetbets mention
  counts with how often each name appears in today's market headlines.
- **Best** - stocks the app's screen rates as good buys: forward earnings growth, a forward
  multiple that has not already priced it in, price above its 50- and 200-day averages, and
  enough size and liquidity to be ownable.
- **ETFs** - funds the app ranked on long-run return (five- and three-year annualised NAV
  returns weighted above anything recent), expense ratio, net assets, dollar volume, how long
  the fund has existed, and its position against its own 50- and 200-day averages. Leveraged
  and inverse funds are excluded outright. Distribution yield is shown but deliberately
  scores nothing - a bond fund and a growth fund are not competing on that axis.

Each row carries the app's own score out of 100 and the reason lines behind it, so you can
see exactly what the app based its ranking on.

## What I want back

1. For **every** row below, one plain-English explanation. Write for someone who understands
   the basics but is not a professional: say what is actually happening at the company, not
   that the metric is high or low.
2. **Search the web** for what has happened to these companies in the last few days, and
   correct the app where its data is stale or wrong - say so in `notes`.
3. If a stock genuinely belongs in one of these lists and the app missed it, ADD it - a new
   object with a symbol not in my data is fine and the app will pick it up.

### The ETF list needs real research, not just explanation

This is the one section where I need you to go and find things out, because the app's fund
universe has a genuine hole in it. It is built from Yahoo Finance's own ETF, bond-ETF and
commodity-ETF screens - about 850 funds - and those screens leave out several of the most
widely held funds in the US market. Measured directly: VTI, SCHD, AGG, BND, TLT, IWM, VXUS and
VYM are all absent from them, and there will be others.

So for the ETF list:

1. **Search the web** for what is currently regarded as the best ETFs to hold, and on what
   grounds - long-run total return, expense ratio, fund size, tracking difference against the
   index, liquidity and spread, tax efficiency, and how concentrated the fund has become.
2. **Add the funds my app could not see.** A `symbol` in `etfs` that is not in my data is
   fine and the app will pick it up, fetch its price and rank it in. If a major, obviously
   better fund is missing from my list, that is the most useful thing you can hand back.
3. **Say when the app's ranking is wrong.** A fund can top my score by having had one
   extraordinary five-year stretch in a single sector. If a row is really a bet rather than a
   holding, say so in its `why`, and put a low `conviction` on it.
4. Give each fund a plain `category` so the list can be read as a set rather than as a
   leaderboard - it is not useful to be told the ten best funds if they are all the same fund.

Be candid. If a row on the "best" list does not deserve to be there, say that in its `why`.

## IMPORTANT - how to answer

End your reply with a single fenced ```json code block matching the schema below.

**The block is a SCHEMA, not an example answer.** Every `<...>` is a description of what
belongs there - replace each with your own real value. Your JSON must be valid: no `<`, no
`>`, no `...`, no comments, no trailing commas, and none of the placeholder wording copied
through.

```json
$SHAPE
```

Then save that reply as a `.txt` or `.md` file and import it in the app: Watch tab ->
Research -> Import Claude's answer.

---

## LIVE DATA FROM THE APP

Generated ${Fmt.day(System.currentTimeMillis())}. Prices in USD, as of the app's last refresh.

```json
$bundle
```
""".trimIndent()
    }

    /**
     * The data bundle, also used verbatim by the API path so the two routes ask the same
     * question of the same model and cannot drift apart.
     */
    fun bundleJson(set: ResearchSet, holdings: List<String>, watchlist: List<String>): String {
        fun rows(list: List<ResearchRow>) = JSONArray().also { arr ->
            list.forEach { r ->
                arr.put(JSONObject().apply {
                    put("symbol", r.symbol)
                    if (r.name.isNotBlank()) put("name", r.name)
                    if (r.price > 0) put("price", round2(r.price))
                    if (r.changePct != 0.0) put("dayChangePct", round2(r.changePct))
                    put("appScore", r.score)
                    if (r.reasons.isNotEmpty()) put("appReasons", JSONArray(r.reasons))
                    if (r.mentions > 0) put("wsbMentions", r.mentions)
                    if (r.mentionDelta != 0) put("wsbMentionChange24h", r.mentionDelta)
                    if (r.newsCount > 0) put("headlinesToday", r.newsCount)
                    if (r.headline.isNotBlank()) put("topHeadline", r.headline)
                    if (r.sentiment.isNotBlank()) put("redditSentiment", r.sentiment)
                    if (r.catalyst.isNotBlank()) put("nextEvent", r.catalyst)
                    r.consensus?.let { c ->
                        put("analysts", JSONObject().apply {
                            put("buy", c.buy); put("hold", c.hold); put("sell", c.sell)
                            if (c.target > 0) put("averageTarget", round2(c.target))
                        })
                    }
                    r.etf?.let { e ->
                        put("fund", JSONObject().apply {
                            if (e.expenseRatio > 0) put("expenseRatioPct", e.expenseRatio)
                            if (e.netAssets > 0) put("netAssetsUsd", e.netAssets)
                            if (e.yieldPct != 0.0) put("yieldPct", round2(e.yieldPct))
                            if (e.ytdReturnPct != 0.0) put("ytdReturnPct", round2(e.ytdReturnPct))
                            // NAMED FOR WHAT IT IS: a price change, where the neighbouring
                            // three- and five-year figures are NAV total returns. Handing a
                            // model "oneYearPct" beside "threeYearAnnualisedPct" invited it to
                            // compare them (Round 66 audit, E5).
                            if (e.oneYearPct != 0.0)
                                put("oneYearPriceChangePct", round2(e.oneYearPct))
                            if (e.threeYearAnnualPct != 0.0)
                                put("threeYearAnnualisedPct", round2(e.threeYearAnnualPct))
                            if (e.fiveYearAnnualPct != 0.0)
                                put("fiveYearAnnualisedPct", round2(e.fiveYearAnnualPct))
                            if (e.dollarVolume > 0) put("avgDailyDollarVolume", e.dollarVolume)
                            if (e.inceptionMs > 0) put("firstTraded", Fmt.day(e.inceptionMs))
                        })
                    }
                })
            }
        }

        val root = JSONObject().apply {
            put("app", "portfolio-research")
            put("asOf", Fmt.day(System.currentTimeMillis()))
            put("dataAgeMinutes", if (set.generated > 0)
                (System.currentTimeMillis() - set.generated) / 60000L else 0L)
            // THE FUND LIST HAS ITS OWN CLOCK AND MUST DECLARE IT. Someone who has only
            // opened the ETFs tab has `generated == 0`, so the line above was telling Claude
            // the data was zero minutes old over a fund list that can be six hours old - and
            // the whole reason the ETF section asks for web research is that its numbers may
            // have moved since the app last screened them.
            if (set.etfs.isNotEmpty()) {
                put(
                    "etfDataAgeMinutes",
                    if (set.etfGenerated > 0)
                        (System.currentTimeMillis() - set.etfGenerated) / 60000L else -1L
                )
            }
            put("sources", set.sources.ifBlank { Research.SOURCES })
            if (set.warnings.isNotEmpty()) put("feedProblems", JSONArray(set.warnings))
            if (holdings.isNotEmpty()) put("iAlreadyHold", JSONArray(holdings))
            if (watchlist.isNotEmpty()) put("onMyWatchlist", JSONArray(watchlist))
            put("trending", rows(set.trending))
            put("best", rows(set.best))
            put("etfs", rows(set.etfs))
            put("etfUniverse", Research.ETF_SOURCES)
            // SAID OUT LOUD, in the data as well as in the prose, because it is the single
            // most useful correction the model can make and a reader skimming JSON should
            // not have to infer it from an absence.
            put(
                "etfUniverseGaps",
                JSONArray(listOf("VTI", "SCHD", "AGG", "BND", "TLT", "IWM", "VXUS", "VYM"))
            )
        }
        return root.toString(2)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /**
     * The same request, phrased for the Messages API rather than for a chat window.
     *
     * The difference is only the framing - no "attach this file", no "save the reply". The
     * task, the data and the schema are identical to [prompt], which is what lets one parser
     * serve both routes.
     */
    fun apiPrompt(bundleJson: String, useWebSearch: Boolean): String = """
You are a candid, numerate equity analyst. Below is live market data from a personal Android
portfolio app. The app has built these lists from free market feeds and scored them with its
own arithmetic; it cannot explain them and it cannot search the web.

- "trending" - stocks being talked about now: r/wallstreetbets mention counts blended with
  how often each name appears in today's market headlines.
- "best" - the app's screen rates these good buys: forward earnings growth, an undemanding
  forward multiple, price above the 50- and 200-day averages, adequate size and liquidity.
- "etfs" - funds ranked on five- and three-year annualised NAV returns (weighted above
  anything recent), expense ratio, net assets, dollar volume, fund age and trend. Leveraged
  and inverse funds are excluded. The universe is Yahoo's own ETF screens and it OMITS
  several of the most widely held US funds - the symbols in "etfUniverseGaps" are examples.
  Add any fund that belongs on a best-ETF list and is not there; give each one a short
  "category" so the list reads as a set rather than a leaderboard.

Each row carries the app's score out of 100 and the reason lines behind it.

DATA:
$bundleJson

${if (useWebSearch) "Search the web for what has happened to these companies in the last few days before you write anything, and correct the app's data where it is stale - say so in \"notes\".\n" else ""}
For every row, write one plain-English explanation: what is actually happening at the
company, not that a metric is high or low. If a stock belongs in a list and the app missed it,
add it. Be candid - if a row on the "best" list does not deserve to be there, say so in its
"why".

Return ONLY a JSON object, no markdown fences. The block below is a SCHEMA, not an example
answer: replace every <...> with your own real value. The output must be valid JSON with no
`<`, no `>`, no `...`, and none of the placeholder wording carried through.

$SHAPE
""".trimIndent()

    // ------------------------------------------------------------------- parsing

    data class Parsed(
        val trending: List<ResearchRow> = emptyList(),
        val best: List<ResearchRow> = emptyList(),
        val etfs: List<ResearchRow> = emptyList(),
        val notes: String = "",
        val error: String? = null
    ) {
        val isEmpty: Boolean
            get() = trending.isEmpty() && best.isEmpty() && etfs.isEmpty()
    }

    /** True when this text carries a research payload at all - used to route an import. */
    fun looksLikeResearch(text: String): Boolean =
        ClaudeBridge.findObject(text, WANTED)?.has("research") == true

    private val WANTED = listOf("research", "portfolioAppResponse")

    /**
     * Accepts a raw Claude reply, a fenced block, or a bare JSON file - the same three shapes
     * [ClaudeBridge.parse] accepts, through the same balanced-brace scanner, so a file that
     * works for the Advice tab cannot mysteriously fail here.
     */
    fun parse(text: String): Parsed {
        if (ClaudeBridge.isPromptFile(text)) return Parsed(
            error = "That is the prompt file this app wrote, not Claude's answer. Attach it to " +
                "a chat in the Claude app, then save what Claude replies as a .txt or .md " +
                "file and import that one."
        )
        val root = ClaudeBridge.findObject(text, WANTED)
            ?: return Parsed(
                error = "No research JSON found in that file. Make sure you saved Claude's " +
                    "whole reply, including the ```json block at the end."
            )
        // A reply that skipped the wrapper and returned the three arrays at the top level is
        // still a valid answer - accept it rather than making the user re-ask.
        val bare = root.has("trending") || root.has("best") || root.has("etfs")
        val res = root.optJSONObject("research") ?: (if (bare) root else null)
        if (res == null) return Parsed(
            error = "That file has JSON in it, but no \"research\" block. It may be the " +
                "answer to a different prompt - the Advice tab imports those."
        )

        val out = Parsed(
            trending = section(res, "trending"),
            best = section(res, "best"),
            etfs = section(res, "etfs"),
            notes = ClaudeBridge.scrub(res.text("notes"))
        )
        if (out.isEmpty) return Parsed(
            notes = out.notes,
            error = "That file only contained the example shape from the prompt, not a real " +
                "answer. Make sure you saved Claude's whole reply."
        )
        return out
    }

    private fun section(res: JSONObject, key: String): List<ResearchRow> {
        val arr = res.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<ResearchRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.text("symbol").uppercase().trim()
            if (sym.isBlank() || sym.length > 6) continue
            val why = ClaudeBridge.scrub(o.text("why"))
            // `category` is the ETF list's version of `catalyst` - the one line under the
            // reasons that says what kind of thing this row IS. Read into the same field so
            // one card layout serves all four sections.
            val catalyst = ClaudeBridge.scrub(
                o.text("catalyst").ifBlank { o.text("category") }
            )
            val risk = ClaudeBridge.scrub(o.text("risk"))
            val target = ClaudeBridge.scrub(o.text("target"))
            // A row with nothing but a ticker adds nothing and would blank a good app row.
            if (why.isBlank() && catalyst.isBlank() && risk.isBlank()) continue
            out.add(
                ResearchRow(
                    symbol = sym,
                    why = why,
                    // NOT `score` (Round 66 audit, R1). `score` is the app's own arithmetic
                    // and a language model's conviction is not that, however confident it is.
                    // See [ResearchRow.conviction].
                    conviction = o.optInt("conviction", 0).coerceIn(0, 10),
                    catalyst = listOf(catalyst, target, risk).filter { it.isNotBlank() }
                        .joinToString(" - ")
                )
            )
        }
        return out
    }

    /**
     * Fold Claude's answer into the rows the app already has.
     *
     * The app's score and reasons SURVIVE - they are reproducible arithmetic and Claude's
     * conviction number is not, so a returned `conviction` never overwrites a computed score;
     * it only orders rows Claude added that the app had no score for. Everything Claude is
     * uniquely good at - the explanation and the catalyst - is merged in.
     */
    fun merge(existing: List<ResearchRow>, incoming: List<ResearchRow>): List<ResearchRow> {
        if (incoming.isEmpty()) return existing
        val byIncoming = incoming.associateBy { it.symbol }
        val merged = existing.map { row ->
            val c = byIncoming[row.symbol] ?: return@map row
            row.copy(
                why = c.why.ifBlank { row.why },
                catalyst = c.catalyst.ifBlank { row.catalyst },
                // The app's own score survives untouched - see [ResearchRow.conviction].
                conviction = if (c.conviction > 0) c.conviction else row.conviction
            )
        }
        val known = existing.map { it.symbol }.toSet()
        // DE-DUPLICATED WITHIN THE INCOMING LIST AS WELL AS AGAINST THE EXISTING ONE.
        //
        // This filter only ever compared against what was already there, so a reply that
        // listed the same ticker twice - which language models do - added it twice. The
        // Research list is drawn by a keyed LazyColumn, and a keyed list handed the same key
        // twice THROWS: the tab would crash on composition, and keep crashing, because the
        // bad set is written straight through to the research cache and restored on every
        // launch. One `distinctBy` is the difference between a duplicated row and an app
        // that cannot open its own screen.
        val added = incoming.filter { it.symbol !in known }.distinctBy { it.symbol }
        return merged + added
    }
}
