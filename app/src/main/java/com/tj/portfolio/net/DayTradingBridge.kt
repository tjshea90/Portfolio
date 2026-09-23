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
 * ROUND 69: CLAUDE NOW OWNS THIS SECTION, AND THAT IS A DELIBERATE REVERSAL.
 *
 * Until Round 69 this file did the opposite of what it does now. It told Claude, in these
 * words, *"Do not replace or second-guess these numbers"*, sent the app's entry/stop/target as
 * read-only context, and [merge] threw away any level that came back - the same rule
 * [ResearchRow.conviction]'s note describes, which keeps a model's figure out of a field the
 * app computes.
 *
 * Tj, 2026-09-11, asked for the reverse, explicitly: *"for the Claude prompt, allow Claude to
 * change the entire section as needed using real time information from the market. for example,
 * the Claude prompt can change the stocks in the list if it finds better ones and it can give
 * advice and buy and sell targets for all the stocks."*
 *
 * So this prompt now hands over the whole section: the picks array that comes back IS the new
 * list, in Claude's order, and Claude sets its own entry, stop and target per name.
 *
 * WHY THAT IS DEFENSIBLE AND NOT A REGRESSION. The reason the app's arithmetic was protected
 * from the model in the first place was that a screener score is REPRODUCIBLE - it can be
 * recomputed, tested, and shown its own workings - where an asserted number cannot be checked
 * against anything. That reasoning is intact, and it is exactly why the two are kept apart
 * rather than blended:
 *
 *  - `score` and `reasons` are STILL the app's alone. Claude cannot write either. A row it adds
 *    still arrives with score 0 and shows "CLAUDE n/10" on a visibly different scale.
 *  - A trade plan is now wholly one or the other, never a mixture, and
 *    [ResearchRow.planByClaude] records which. The card and the dialog both label it.
 *  - Claude's levels are validated as a SHAPE before they are accepted ([levelsUsable]):
 *    stop < entry < target, all positive and finite, and each within half-to-double the price
 *    the app independently knows for that symbol. That catches the failure that actually
 *    happens - a decimal slipped, a stale price from last year, a target below the stop - and
 *    refuses it without needing to second-guess the judgment itself.
 *
 * And the thing the app genuinely cannot do, Claude can: read the news that moved the stock an
 * hour ago. The app's own plan is computed from price structure alone and is blind to a halt,
 * an offering priced overnight, or a guidance cut - all of which change where a sane entry sits.
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
        "why": <string - 1-3 sentences: the SPECIFIC reason this stock is worth trading today. Name the event: the short squeeze, the earnings beat, the FDA decision, the guidance, the halt and reopen, the analyst upgrade. Never "high investor interest">,
        "risk": <string - 1 sentence: the specific thing that could invalidate this setup today, e.g. an earnings print after the close, a lockup expiry, a pending halt, a Fed announcement>,
        "conviction": <integer 1-10, 10 = strongest case>,
        "setup": <string - the setup in 1-3 words: "Breakout", "Pullback", "VWAP reclaim", "Gap and go", "Short squeeze", "Range">,
        "entry": <number - the price the BUY TRIGGERS AT. A level the market has to reach, above resistance for a breakout or down at support for a pullback. NOT the current price>,
        "stop": <number - the price that says the setup failed. Must be below entry>,
        "target": <number - where you would take profit. Must be above entry>,
        "trigger": <string - one sentence saying exactly what has to happen before buying, e.g. "Buy the break above 12.40 on volume; if it opens above it, wait for the first pullback to hold 12.40">
      }
      ... your list, best first. See the instructions above for how many and which.
    ],
    "notes": <string - your overall read on the day: the tape, which names you dropped and why, anything the app's numbers got wrong. One short paragraph>
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

# Day trading watchlist - please rebuild it

I am attaching live market data exported from my personal Android portfolio app. The app
screened the whole market for stocks that are objectively in play right now - unusually heavy
volume, a real move already under way, elevated wallstreetbets/news attention, a technical
breakout, or a short-squeeze-prone setup - and computed a trade plan for each from its own
intraday levels. It cannot search the web, so it is blind to anything that happened in the
news today. That is what I need from you.

## You own this list

$AUTHORITY

## What the app already did, so you can improve on it rather than repeat it

For each name the app computed a real trade plan from real intraday structure - not from the
current price. `entry` is a TRIGGER: a buy-stop above the level price has to clear, or a
buy-limit down at support when the stock has already run too far to chase. `stop` sits under
the structure that would invalidate the setup, sized from this stock's own 5-minute ATR.
`target` is the next real resistance above entry, floored at 2:1 reward-to-risk and capped by
how much of a normal day's range is left.

The levels it worked from are in each row where available, and you should reason from the same
ones: `vwap`, `openingRangeHigh`/`openingRangeLow`, `prevHigh` (the prior session's high),
`premarketHigh`, `sessionHigh`/`sessionLow`, `atr14` (daily), `atrIntraday` (5-minute) and
`adr` (average daily range - how big a normal day is for this stock). A field that is absent
was not available; do not assert a level the data does not contain.

$LEVEL_RULES

## What I want back

1. **The list you would actually trade**, best first, with a plain-English `why` that names the
   specific event - never "high investor interest."
2. **Search the web** for what is happening with these names right now, and with anything you
   want to add. Correct the app where its data is stale or wrong, and say so in `notes`.
3. **Entry, stop and target for every name**, to the rules above.
4. **The one specific thing that could go wrong today** for each - an earnings print after the
   close, a lockup expiry, a scheduled Fed announcement, a pending halt.
5. Only stocks trading at $2 a share or more.

Be candid. If a name the app found does not actually look worth trading, drop it and say why in
`notes`. If the whole tape looks bad today, say that too - a short list, or a list with low
conviction scores, is a more useful answer than a padded one.

## IMPORTANT - how to answer

${ClaudeBridge.fileDelivery(ClaudeBridge.ANSWER_DAY_TRADING)}

**The block is a SCHEMA, not an example answer.** Every `<...>` is a description of what
belongs there - replace each with your own real value. Your JSON must be valid: no `<`, no
`>`, no `...`, no comments, no trailing commas, and none of the placeholder wording copied
through.

```json
$SHAPE
```

Share the file to the Portfolio app, or import it (or your saved reply) in the app: Watch tab -> Research -> Day Trading ->
Import Claude's answer.

---

## LIVE DATA FROM THE APP

Generated ${Fmt.day(System.currentTimeMillis())}. Prices in USD, as of the app's last refresh.

```json
$bundle
```
""".trimIndent()
    }

    /**
     * The grant of authority, shared word-for-word by the file prompt and the API prompt.
     *
     * ONE CONSTANT, NOT TWO COPIES. The two paths are the same request asked in two places, and
     * the thing most likely to drift between them is exactly this - the part that says what
     * Claude is allowed to change. A user who exports the file and a user who taps Explain
     * must not get different lists because one prompt was edited and the other was not.
     */
    private const val AUTHORITY = """You can change all of it. Drop any name you would not trade
today and add any name you would - the array you return IS my new list, in your order, and
anything you leave out is removed. Set your own entry, stop and target for every name, from
what you can see in the market right now. You are not annotating the app's list; you are
replacing it with the one you would trade."""

    /** The rules an entry price has to satisfy - shared by both prompts for the same reason. */
    private const val LEVEL_RULES =
        """**Your entry must be a level, not the last price.** This matters more than anything
else here: an "entry" equal to the current quote is not a plan, it is the absence of one, and
it was the bug in this app that prompted the rewrite. Give me the price at which I should
actually place the order - above a level for a breakout so the move has to prove itself first,
or below the current price at real support when the stock has already extended and buying it
here would be chasing. If a name is worth watching but there is no sane entry right now, say so
in `trigger` and set the entry where it WOULD become buyable. Keep stop and target on real
levels too, and keep the reward at least twice the risk unless you explain in `why` why a
thinner trade is still worth it."""

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
                    if (r.entryPrice > 0) put("appEntry", round2(r.entryPrice))
                    if (r.stopPrice > 0) put("appStop", round2(r.stopPrice))
                    if (r.targetPrice > 0) put("appTarget", round2(r.targetPrice))
                    if (r.setup.isNotBlank()) put("appSetup", r.setup)
                    if (r.trigger.isNotBlank()) put("appTrigger", r.trigger)
                    if (r.planNote.isNotBlank()) put("appPlanWarning", r.planNote)
                    // THE REAL LEVELS THE PLAN ABOVE WAS COMPUTED FROM - so Claude reasons from
                    // the same structure the app did rather than describing the entry in the
                    // abstract. Absent for a row the live enrichment pass has not reached yet -
                    // see `net/DayTradingTechnicals.kt`'s header for what each one is.
                    if (r.atr > 0) put("atr14", round2(r.atr))
                    if (r.atrIntraday > 0) put("atrIntraday", round2(r.atrIntraday))
                    if (r.adr > 0) put("adr", round2(r.adr))
                    if (r.vwap > 0) put("vwap", round2(r.vwap))
                    if (r.openingRangeHigh > 0) put("openingRangeHigh", round2(r.openingRangeHigh))
                    if (r.openingRangeLow > 0) put("openingRangeLow", round2(r.openingRangeLow))
                    if (r.prevHigh > 0) put("prevHigh", round2(r.prevHigh))
                    if (r.premarketHigh > 0) put("premarketHigh", round2(r.premarketHigh))
                    if (r.sessionHigh > 0) put("sessionHigh", round2(r.sessionHigh))
                    if (r.sessionLow > 0) put("sessionLow", round2(r.sessionLow))
                })
            }
        }

        val root = JSONObject().apply {
            put("app", "portfolio-day-trading")
            put("asOf", Fmt.day(System.currentTimeMillis()))
            put("marketPhase", MarketClock.label())
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
Android portfolio app. The app screened the whole market for stocks objectively in play right
now - unusually heavy volume, a real move already under way, elevated wallstreetbets/news
attention, a technical breakout, or a short-squeeze-prone setup - and computed a trade plan for
each from its own intraday levels. It cannot search the web.

$AUTHORITY

Each row carries what the app computed (`appEntry`, `appStop`, `appTarget`, `appSetup`,
`appTrigger`) and the levels it reasoned from, where available: `vwap`,
`openingRangeHigh`/`openingRangeLow`, `prevHigh`, `premarketHigh`, `sessionHigh`/`sessionLow`,
`atr14` (daily), `atrIntraday` (5-minute) and `adr` (average daily range). A field that is
absent was not available - do not assert a level the data does not contain.

$LEVEL_RULES

DATA:
$bundleJson

${if (useWebSearch) "Search the web for what is actually happening with these names right now, and with anything you want to add, before you write anything. Correct the app's data where it is stale and say so in \"notes\".\n" else ""}
For every name you return, write one plain-English explanation of WHY it is worth trading today
- name the specific event, never "high investor interest" - and name the one specific thing
that could go wrong today (earnings after the close, a lockup expiry, a scheduled Fed
announcement, a pending halt). Only stocks trading at $2 a share or more. Be candid in "notes"
about what you dropped and why; a short list is a better answer than a padded one.

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
    fun parse(text: String, now: Long = System.currentTimeMillis()): Parsed {
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

        // ---- AN OLD ANSWER IS NOT TODAY'S PLAN (full test 2026-09-23, D-5). `asOf` was asked
        // for and never read. With the share flow, yesterday's answer file sits one tap away in
        // the Claude chat - and its entry/stop/target easily pass the half-to-double price check,
        // so it became today's CLAUDE'S PLAN and was logged permanently under today's date. A
        // dated answer from an earlier session keeps its explanations and loses its levels.
        val current = answerIsCurrent(dt.text("asOf"), now)
        val arr = dt.optJSONArray("picks") ?: JSONArray()
        val out = ArrayList<ResearchRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.text("symbol").uppercase().trim()
            if (sym.isBlank() || sym.length > 6) continue
            val why = ClaudeBridge.scrub(o.text("why"))
            val risk = ClaudeBridge.scrub(o.text("risk"))
            val entry = o.optDouble("entry", 0.0)
            val stop = o.optDouble("stop", 0.0)
            val target = o.optDouble("target", 0.0)
            // SHAPE-CHECKED HERE, PRICE-CHECKED IN `merge`. This half needs no market data:
            // a triple that is not stop < entry < target does not describe a trade at all,
            // whatever the prices are. The half that does need it - are these numbers anywhere
            // near this stock's actual price - can only run where the app's own price is known.
            val sane = current && levelsSane(entry, stop, target)
            // A row with nothing but a ticker adds nothing.
            if (why.isBlank() && risk.isBlank() && !sane) continue
            out.add(
                ResearchRow(
                    symbol = sym,
                    why = why,
                    // NOT `score` - see the class header. A model's conviction never
                    // overwrites the app's own arithmetic, even now that its LEVELS can.
                    conviction = o.optInt("conviction", 0).coerceIn(0, 10),
                    catalyst = risk,
                    entryPrice = if (sane) entry else 0.0,
                    stopPrice = if (sane) stop else 0.0,
                    targetPrice = if (sane) target else 0.0,
                    setup = if (sane) ClaudeBridge.scrub(o.text("setup")) else "",
                    trigger = if (sane) ClaudeBridge.scrub(o.text("trigger")) else "",
                    planByClaude = sane
                )
            )
        }
        val notes = ClaudeBridge.scrub(dt.text("notes")).let { n ->
            if (current) n
            else listOf(
                "This answer is dated ${dt.text("asOf")}, an earlier session - its explanations " +
                    "were kept, its entry/stop/target levels were not.",
                n
            ).filter { it.isNotBlank() }.joinToString(" ")
        }
        if (out.isEmpty()) return Parsed(
            notes = notes,
            error = "That file only contained the example shape from the prompt, not a real " +
                "answer. Make sure you saved Claude's whole reply."
        )
        return Parsed(picks = out, notes = notes)
    }

    /**
     * Is an answer dated [asOf] (YYYY-MM-DD, as the prompt asks) about the session [now] is in?
     * Today's New York date is; so is YESTERDAY's before today's open - an evening's "plan for
     * tomorrow" read the next morning. Missing or unreadable counts as current: tolerance for a
     * reply that left the field out, which the price check in [merge] still guards.
     */
    internal fun answerIsCurrent(asOf: String, now: Long): Boolean {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(asOf) ?: return true
        val key = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
        val today = MarketClock.dayKey(now)
        if (key >= today) return true
        val et = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("America/New_York"))
        val beforeOpen = et.hour * 60 + et.minute < 9 * 60 + 30
        return beforeOpen && key == MarketClock.dayKey(now - 86_400_000L)
    }

    /** The fallback trigger sentence, for a reply that gave levels but no wording of its own. */
    private fun describe(r: ResearchRow): String =
        "Claude's plan: buy at ${Fmt.price(r.entryPrice)}, stop ${Fmt.price(r.stopPrice)}, " +
            "target ${Fmt.price(r.targetPrice)}."

    /** Does this triple describe a trade at all? Shape only - no market data needed. */
    internal fun levelsSane(entry: Double, stop: Double, target: Double): Boolean =
        entry.isFinite() && stop.isFinite() && target.isFinite() &&
            entry > 0.0 && stop > 0.0 && target > 0.0 &&
            stop < entry && entry < target

    /** How far from the app's own price a level may sit before it reads as a mistake. */
    private const val LEVEL_SANITY_LOW = 0.5
    private const val LEVEL_SANITY_HIGH = 2.0

    /**
     * [levelsSane] plus the check that needs the app's own price: every level within
     * half-to-double it.
     *
     * WHAT THIS IS FOR, AND WHAT IT IS NOT. It is not a second opinion on the trade - Tj asked
     * for Claude's judgment and this does not override it. It catches the mechanical failure:
     * a decimal point in the wrong place, a price recalled from an old training snapshot, a
     * pre-split number. A stop at $1.20 on a $120 stock is not a tight stop, it is a typo, and
     * the app cannot tell the difference from the number alone - but it can tell that no day
     * trade has levels that far from the price it just quoted.
     */
    internal fun levelsUsable(price: Double, entry: Double, stop: Double, target: Double): Boolean {
        if (!levelsSane(entry, stop, target)) return false
        if (price <= 0.0) return true
        val low = price * LEVEL_SANITY_LOW
        val high = price * LEVEL_SANITY_HIGH
        return entry in low..high && stop in low..high && target in low..high
    }

    /**
     * Fold Claude's answer into the rows the app already has.
     *
     * ROUND 69: CLAUDE'S LIST IS THE NEW LIST. This used to keep every app row and append
     * Claude's additions to the end. It now returns Claude's picks, in Claude's order, and a
     * row the app found that Claude left out is GONE - which is what "the Claude prompt can
     * change the stocks in the list if it finds better ones" asks for, and what the prompt
     * tells Claude will happen. Nothing is lost permanently: the next screener rebuild
     * repopulates the section from the app's own feeds.
     *
     * What survives from the app's row for a symbol both sides know: the score, the reason
     * lines, the price, the technicals - everything the app measured. Only the explanation and,
     * when they pass [levelsUsable], the trade plan come from Claude.
     */
    fun merge(
        existing: List<ResearchRow>,
        incoming: List<ResearchRow>,
        // A parameter with a default, not a bare clock read, so the session rule on `sessionDay`
        // below can be tested on a day a test chooses. Every caller in the app omits it.
        now: Long = System.currentTimeMillis()
    ): List<ResearchRow> {
        if (incoming.isEmpty()) return existing
        // STAMPED ONLY WHEN `why` ITSELF IS FRESH - see [ResearchBridge.merge]'s own note
        // (full-tests audit, round 79 sweep: the first version stamped this unconditionally,
        // which let an old `why` paragraph ride forward under a fresh clock any time Claude's
        // reply only touched the trade levels or catalyst, not the explanation).
        val today = MarketClock.dayKey(now)
        val afterTodaysClose = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.of("America/New_York"))
            .let { it.hour * 60 + it.minute } >= MarketClock.closeMinuteAt(now)
        val byExisting = existing.associateBy { it.symbol }
        // De-duplicated - same reason [ResearchBridge.merge] does it: a keyed LazyColumn
        // crashes on a repeated key, and a model repeating a ticker is not a hypothetical.
        return incoming.distinctBy { it.symbol }.map { c ->
            val app = byExisting[c.symbol]
                ?: return@map if (c.why.isNotBlank()) c.copy(whyAt = now) else c
            val takeLevels = c.planByClaude &&
                levelsUsable(app.price, c.entryPrice, c.stopPrice, c.targetPrice)
            app.copy(
                why = c.why.ifBlank { app.why },
                whyAt = if (c.why.isNotBlank()) now else app.whyAt,
                catalyst = c.catalyst.ifBlank { app.catalyst },
                conviction = if (c.conviction > 0) c.conviction else app.conviction,
                // ALL SIX MOVE TOGETHER OR NONE DO - the same rule the live technicals pass
                // follows. A Claude entry over an app stop is a trade neither of them planned.
                entryPrice = if (takeLevels) c.entryPrice else app.entryPrice,
                stopPrice = if (takeLevels) c.stopPrice else app.stopPrice,
                targetPrice = if (takeLevels) c.targetPrice else app.targetPrice,
                setup = if (takeLevels) c.setup.ifBlank { "Claude's plan" } else app.setup,
                // NEVER `ifBlank { app.trigger }` - the app's trigger sentence has the app's own
                // entry price written INTO it ("Buy the break above $12.40"), so pairing it with
                // Claude's entry prints one number in the grid and a different one in the
                // sentence under it. When Claude gave levels but no sentence, the sentence is
                // built from ITS numbers.
                trigger = if (takeLevels) c.trigger.ifBlank { describe(c) } else app.trigger,
                // AND THE APP'S WARNING GOES WITH THE APP'S PLAN. `planNote` describes the plan
                // that was just replaced - "the entry is BELOW the last price on purpose", the
                // tightened-stop disclosure - and would otherwise be drawn in error red
                // underneath Claude's levels, warning about a trade no longer on screen.
                planNote = if (takeLevels) "" else app.planNote,
                // THE EXIT TEXT CARRIES A PRICE TOO, so it cannot simply be kept either (Round 73,
                // caught by code review). `planExit` names the app's own target - "Take profit at
                // $12.00" - and leaving it over Claude's levels prints that under a grid showing
                // Claude's $13.50, the exact mismatch the `trigger` note above exists to prevent.
                // It is REBUILT from Claude's target rather than blanked, because the half of it
                // that matters most is not about any price: a day trade is flat before the close
                // whoever planned it. No clock is passed - this bridge has none, and the runner
                // and flat-by rules are not clock-dependent.
                planExit = if (takeLevels)
                    ResearchScore.exitPlan(c.targetPrice, minutesLeft = 0, live = false)
                else app.planExit,
                // `tooLateToStart` IS NOT SET HERE AT ALL, in either direction. It is a fact
                // about the clock rather than about whose plan this is, and pinning it to an
                // imported plan was itself a bug (a second code-review pass caught that
                // `mergeDayTradingTech` never re-plans a `planByClaude` row, so anything pinned
                // here would never clear). `mergeDayTradingTech` now recomputes it from the
                // session clock on every tick for every row, Claude's included, so an import at
                // 15:45 correctly carries the late-session badge the app would give its own.
                planByClaude = takeLevels,
                // The app's "declined" state belongs to the app's plan (D-2): a row the app had
                // declined but Claude planned is a planned row, and must be loggable as one.
                planDeclineStreak = if (takeLevels) 0 else app.planDeclineStreak,
                planReason = if (takeLevels) "" else app.planReason,
                // A PLAN IMPORTED ONTO A ROW STILL STAMPED WITH AN EARLIER SESSION starts that
                // row's session fresh (full-tests audit, 2026-09-22). `mergeDayTradingTech`
                // drops a Claude plan the moment it sees the row's `sessionDay` roll over - right
                // for a plan imported yesterday, wrong for one imported this morning before the
                // live sweep had ticked once since midnight (a cold launch restores rows with
                // yesterday's day on them): the first tick would read "new session" and throw
                // away the plan the user had just imported. Blank means "no reading for today
                // yet", which is exactly true - and it is also what keeps yesterday's VWAP and
                // session range from being carried into today by `effectiveTechnicals`.
                //
                // AND AN IMPORT AFTER TODAY'S CLOSE belongs to the NEXT session too (full test
                // 2026-09-23, D-8): at 22:00 the row still carries today's date, so the 04:00
                // pre-market tick read a rollover and replaced Claude's evening plan with the
                // app's - while the same import at 00:05 survived.
                sessionDay = if (takeLevels && app.sessionDay.isNotBlank() &&
                    (app.sessionDay != today || afterTodaysClose)
                ) ""
                else app.sessionDay
            )
        }
    }
}
