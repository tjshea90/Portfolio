package com.tj.portfolio.net

import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.data.DayTradingStats
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * THE TUNING PROMPT (2026-09-24c) - everything a fresh Claude chat needs to review and improve the
 * day-trading engine from its graded results, with nothing typed by Tj.
 *
 * Tj's rule 4: *"each Claude prompt should make the prompt file that includes the current
 * algorithms and all changes already made to the engine ... The goal is to improve the system with
 * every Claude input ... It should have all of the data and history needed for a thorough and
 * accurate review and optimization of the system. Include any information in the prompt file that
 * would explain to Claude from a fresh chat what is needed, what has already been done, what to
 * check, the goals, etc."*
 *
 * So the file carries, in order: the goal; the rules the app itself enforces (so Claude does not
 * propose what will be refused); the engine's algorithm with every current value; the grading
 * rules the results were measured with; every change already made and how each engine version has
 * done since; the results broken down every way the parameters act on; what other stop and target
 * distances would have done on the same bars; the full parameter table; one line per graded trade;
 * what to check before recommending anything; and the exact answer file to write.
 *
 * The parameter table and the rules are generated from [DayTradingParams.SPECS] and
 * [EngineTuning.Tier] - the same objects the validator uses - so the prompt cannot describe a
 * different set of limits from the one the app applies.
 */
object EngineTuningPrompt {

    const val PROMPT_FILE = "claude-daytrading-tuning-prompt.md"

    /** Trade-table rows at most - the newest; aggregates always cover every graded trade. */
    const val MAX_TRADE_ROWS = 1500

    private val ET: ZoneId = ZoneId.of("America/New_York")

    private fun f2(v: Double) = if (v.isFinite()) String.format(java.util.Locale.US, "%.2f", v) else "n/a"
    private fun r2(v: Double) = if (v.isFinite()) String.format(java.util.Locale.US, "%+.2f", v) else "n/a"
    private fun pct(v: Double) = String.format(java.util.Locale.US, "%.0f%%", v)

    /** One graded (or unfilled) plan with everything the tables need, computed once. */
    private class Row(val e: DayTradingLogEntry, val f: JSONObject, val d: DayTradingGrader.Detail?) {
        val decided = e.outcome in setOf(DayTradingOutcome.WIN, DayTradingOutcome.LOSS,
            DayTradingOutcome.CLOSED_PROFIT, DayTradingOutcome.CLOSED_LOSS)
        val app = e.source != DayTradingLogEntry.SOURCE_CLAUDE
        val netR: Double = if (!decided) 0.0 else {
            val exit = e.outcomeExitPrice ?: e.entry
            val fill = d?.fill?.takeIf { it > 0 } ?: e.entry
            val risk = e.entry - e.stop
            if (risk > 1e-9) (DayTradingEval.Costs.exitFill(e.outcome, exit) - DayTradingEval.Costs.entryFill(fill)) / risk else 0.0
        }
        fun num(k: String): Double? = if (f.has(k)) f.optDouble(k, Double.NaN).takeIf { it.isFinite() } else null
    }

    private class Agg(val label: String) {
        var planned = 0; var filled = 0; var wins = 0; var profitable = 0; val rs = ArrayList<Double>()
        fun add(r: Row) {
            planned++
            if (!r.decided) return
            filled++
            if (r.e.outcome == DayTradingOutcome.WIN) wins++
            if (r.netR > 0) profitable++
            rs.add(r.netR)
        }
        fun line(): String {
            if (filled == 0) return "| $label | $planned | 0 | - | - | - | - | - |"
            val (lo, hi) = DayTradingEval.meanInterval(rs)
            return "| $label | $planned | $filled (${pct(filled * 100.0 / planned)}) | ${pct(wins * 100.0 / filled)} | " +
                "${pct(profitable * 100.0 / filled)} | ${r2(rs.average())} | " +
                (if (rs.size >= 2) "${r2(lo)} to ${r2(hi)}" else "-") + " | ${r2(rs.sum())} |"
        }
    }

    private const val AGG_HEAD = "| group | plans | filled (fill rate) | target hit | profitable after costs | avg net R | 95% range of avg R | total net R |\n" +
        "|---|---|---|---|---|---|---|---|"

    private fun table(title: String, rows: List<Row>, key: (Row) -> String?): String {
        val groups = LinkedHashMap<String, Agg>()
        for (r in rows) { val k = key(r) ?: continue; groups.getOrPut(k) { Agg(k) }.add(r) }
        if (groups.isEmpty()) return ""
        return "### $title\n\n$AGG_HEAD\n" + groups.values.sortedByDescending { it.planned }.joinToString("\n") { it.line() } + "\n"
    }

    private fun bucket(v: Double?, edges: List<Double>, unit: String = ""): String? {
        v ?: return null
        val i = edges.indexOfFirst { v < it }
        return when (i) {
            -1 -> ">= ${edges.last()}$unit"
            0 -> "< ${edges.first()}$unit"
            else -> "${edges[i - 1]}-${edges[i]}$unit"
        }
    }

    fun prompt(
        state: EngineTuning.State,
        log: List<DayTradingLogEntry>,
        stats: DayTradingStats,
        now: Long = System.currentTimeMillis()
    ): String {
        val p = state.params
        val current = log.filter { it.evalVersion >= DayTradingGrader.VERSION }
        val rows = current.map { e ->
            Row(e, runCatching { JSONObject(e.features) }.getOrElse { JSONObject() }, DayTradingGrader.Detail.parse(e.evalDetail))
        }
        val graded = rows.filter { it.decided || it.e.outcome == DayTradingOutcome.NO_ENTRY }
        val app = graded.filter { it.app }
        val ev = EngineTuning.Evidence(log, state.lastApplyAt)
        val n = ev.total
        val tier = EngineTuning.Tier.of(n)
        val next = EngineTuning.Tier.next(n)
        val today = Instant.ofEpochMilli(now).atZone(ET).toLocalDate().toString()

        val sb = StringBuilder()
        sb.append("<!-- ${ClaudeBridge.PROMPT_MARK}: this file is the QUESTION for Claude, not the ANSWER. Attach it to a chat in the Claude app - do NOT import this file back. -->\n\n")
        sb.append(ClaudeBridge.startNow()).append("\n\n")
        sb.append("# Improve my day-trading engine from its graded results\n\n")
        sb.append("""
This file comes from my personal Android portfolio app (Kotlin, sideloaded on my own phone). Its
Day Trading tab screens the market for stocks in play and computes, for each, a trade plan: an
entry trigger, a stop and a target, from the stock's own intraday levels. The app records every
plan it shows me and grades it against the stock's real prices afterwards, as one real order
placed the moment the plan was shown. You are reviewing those graded results to tune the engine
that makes the plans - this is round ${state.history.count { it.kind == EngineTuning.KIND_APPLY } + 1} of an
ongoing process, and the full history of earlier rounds is below.

## The goal

Make the app's own plans more profitable **after costs**, measured by the average net R per
trade (1R = the loss if the stop is hit) and the total net R, without fooling ourselves:

- Every change you propose must be supported by the graded trades in this file - not by general
  trading lore alone. Cite the group and its trade count.
- Prefer a few robust changes that help across groups and across both halves of the history over
  many small tweaks fitted to noise. With few trades, the right answer is usually "change nothing
  yet" - an empty `changes` list is a valid and often correct answer.
- Each round builds on the last: check how every earlier change has done since it was applied (the
  per-version table), keep what worked, and propose taking back what did not (set it back towards
  its original value).
- Filled-trade expectancy is not the only lever: a setup or level that rarely fills, or that fills
  and loses, costs nothing but opportunity - a plan that fills and loses costs money.

""".trimStart())

        // ---------------------------------------------------------------- the rules
        sb.append("## Rules the app enforces on your answer (it will refuse or limit anything else)\n\n")
        sb.append("- There are **$n graded trades from the app's own plans** (Claude-made plans are listed separately and do not count - they do not measure the engine).\n")
        sb.append("- Sample-size tiers, by graded trades of the app's own plans:\n")
        for (t in EngineTuning.Tier.values()) sb.append("  - ${t.minTrades}+: ${t.label}${if (t == tier) "  <- **this answer**" else ""}\n")
        next?.let { sb.append("- The next tier starts at ${it.minTrades} graded trades (${it.minTrades - n} to go).\n") }
        sb.append("- A change resting on a group (its `basis`) needs at least ${EngineTuning.MIN_GROUP_FOR_CHANGE} graded trades in that group; a switch (turning a setup, level or filter on or off) needs at least ${EngineTuning.MIN_GROUP_FOR_SWITCH}. The app re-counts the group itself.\n")
        if (state.lastApplyAt > 0) sb.append("- The last change was applied ${Instant.ofEpochMilli(state.lastApplyAt).atZone(ET).toLocalDate()}; ${ev.sinceLastChange} graded trades since. Another change needs at least ${EngineTuning.MIN_TRADES_BETWEEN_CHANGES} since the last one, so each change can be measured on its own.\n")
        sb.append("- A step larger than the tier allows is cut down to the allowed step (same direction). Values outside a parameter's hard range are refused. Your `from` must equal the current value in the table, and `basedOn.engineVersion` must be ${state.version}.\n")
        sb.append("- Position sizing (1% of the portfolio risked per trade, at most 25% of it in one position, no margin) is fixed - it is not a parameter and must not be the lever.\n")
        sb.append("- Tj confirms every change before it applies, and can undo the last change or revert to the original engine at any time.\n\n")

        // ---------------------------------------------------------------- the algorithm
        sb.append("## How the engine works (current values in brackets)\n\n")
        sb.append(algorithm(p)).append("\n")

        // ---------------------------------------------------------------- grading
        sb.append("## How each trade was graded\n\n")
        sb.append(DayTradingGrader.RULES_TEXT).append("\n\n")
        sb.append("Costs taken off every trade: ${DayTradingEval.Costs.ENTRY_BPS} bp on the entry, " +
            "${DayTradingEval.Costs.STOP_BPS} bp on a stop exit, ${DayTradingEval.Costs.CLOSE_BPS} bp on a flat-time exit, 0 on a target (a resting limit). " +
            "**net R** = (exit after costs - fill after costs) / (entry - stop). Only the first plan the app showed for a symbol each day is recorded.\n\n")

        // ---------------------------------------------------------------- history
        sb.append("## Changes already made to the engine\n\n")
        if (state.history.isEmpty()) sb.append("None - the engine is still the original (v0). This is the first review.\n\n")
        else {
            sb.append("The engine is now **v${state.version}** (${if (state.isOriginal) "identical to the original" else "differs from the original in ${p.diffFrom(DayTradingParams.DEFAULTS).size} parameters"}).\n\n")
            for (h in state.history) {
                val day = Instant.ofEpochMilli(h.at).atZone(ET).toLocalDate()
                sb.append("- **v${h.version}**, $day, ${h.kind}${if (h.undoneAt > 0) " (later undone)" else ""}, on ${h.gradedTrades} graded trades: ")
                sb.append(h.changes.joinToString("; ") { "${it.key} ${EngineTuning.describe(it.key, it.from)} -> ${EngineTuning.describe(it.key, it.to)}" }.ifBlank { "no parameter change" })
                if (h.summary.isNotBlank()) sb.append(". Verdict then: ").append(h.summary.replace('\n', ' '))
                sb.append("\n")
                h.rationale.forEach { sb.append("  - why: ").append(it.replace('\n', ' ')).append("\n") }
            }
            sb.append("\n")
        }
        sb.append(table("Results by engine version (the app's own plans) - did each change help?", app) { r ->
            r.e.engine.ifBlank { "v0 (logged before versions were recorded)" }
        })
        sb.append("\n")

        // ---------------------------------------------------------------- results
        sb.append("## Results\n\n")
        sb.append("Overall (every graded plan, app and Claude): ${stats.entriesTriggered} filled trades, profitable after costs " +
            "${f2(stats.profitableRate)}% (95% range ${f2(stats.profitableLow)}-${f2(stats.profitableHigh)}%), average ${r2(stats.avgR)}R " +
            "(95% range ${r2(stats.avgRLow)} to ${r2(stats.avgRHigh)}), average win ${r2(stats.avgWinR)}R, average loss ${r2(stats.avgLossR)}R, " +
            "profit factor ${if (stats.profitFactor.isInfinite()) "inf" else f2(stats.profitFactor)}, max drawdown ${f2(stats.maxDrawdownR)}R, " +
            "${stats.noEntry} plans never filled before their cut-off. Portfolio (fundable trades only): ${f2(stats.accountReturnPct)}%. " +
            "Sample: ${stats.sampleNote}.\n\n")
        sb.append(table("Who made the plan", graded) { if (it.app) "The app's engine" else "Claude's plans" })
        sb.append(table("By setup (app plans)", app) { it.e.setup.ifBlank { "?" } })
        sb.append(table("By the level the entry was built on (app plans)", app) { it.f.optString("lvl", "").ifBlank { null } })
        sb.append(table("By time of day it was shown (app plans)", app) { EngineTuning.timeBucket(it.e.recordedAt) })
        sb.append(table("By trigger distance from the price, in intraday ATRs (app plans)", app) { bucket(it.num("trigAtr"), listOf(-1.0, 0.0, 0.5, 1.0, 2.0)) })
        sb.append(table("By planned reward:risk (app plans)", app) { bucket(it.num("rr"), listOf(1.0, 1.5, 2.0, 3.0)) })
        sb.append(table("By stop width, in intraday ATRs (app plans)", app) { bucket(it.num("riskAtr"), listOf(1.5, 2.0, 2.5)) })
        sb.append(table("By share of the average daily range already used (app plans)", app) { bucket(it.num("rangeUsed"), listOf(0.3, 0.6, 0.85)) })
        sb.append(table("By price vs VWAP, in intraday ATRs (app plans)", app) { bucket(it.num("vwapAtr"), listOf(0.0, 1.0, 2.5)) })
        sb.append(table("By blended score (app plans)", app) { bucket(it.num("score"), listOf(20.0, 40.0, 60.0)) })
        sb.append(table("By paced relative volume (app plans)", app) { bucket(it.num("rvol"), listOf(1.5, 2.0, 3.0, 5.0), "x") })
        sb.append(table("By opening bar direction (app plans)", app) { if (it.f.has("obb")) (if (it.f.optBoolean("obb")) "first 5-min bar closed up" else "first 5-min bar did not close up") else null })
        val sortedApp = app.filter { it.decided }.sortedBy { it.e.recordedAt }
        if (sortedApp.size >= 10) {
            val half = sortedApp.size / 2
            val cut = sortedApp[half].e.recordedAt
            sb.append(table("Consistency: first half vs second half of the history (app plans)", app) {
                if (it.e.recordedAt < cut) "first half" else "second half"
            })
        }
        sb.append("\n")

        // ---------------------------------------------------------------- the grid
        sb.append(gridSection(app.filter { it.decided && it.d?.grid?.size == DayTradingGrader.GRID_STOPS.size }))

        // ---------------------------------------------------------------- params
        sb.append("## Every parameter you can change\n\n")
        sb.append("| param | current | original | allowed | what it does |\n|---|---|---|---|---|\n")
        for (s in DayTradingParams.SPECS) {
            val range = when (s.kind) {
                DayTradingParams.Kind.BOOL -> "0 or 1"
                else -> "${EngineTuning.describe(s.key, s.min).let { if (it == "off") "0" else it }} to ${EngineTuning.describe(s.key, s.max)}" +
                    (if (s.offAllowed) ", or 0 = off" else "")
            }
            sb.append("| ${s.key} | ${EngineTuning.describe(s.key, p[s.key])} | ${EngineTuning.describe(s.key, s.default)} | $range | ${s.doc.replace("|", "/")} |\n")
        }
        sb.append("\n")

        // ---------------------------------------------------------------- what to check
        sb.append("""
## What to check before you recommend anything

1. **Is there an edge at all?** Look at the 95% range of the average net R, overall and for the
   app's own plans. If it straddles zero, say so plainly in `verdict`.
2. **Sample size per group.** A group with a handful of trades is noise. Do not build a change on
   it, and say which groups are too small to read.
3. **Consistency.** A real effect shows in both halves of the history and in more than one setup or
   time of day. One that appears in only one slice is probably luck.
4. **The counterfactual grid** says what other stop and target distances would have done on the
   very same bars. Use it for stop/target parameters - but note that stops and targets are placed
   in ATRs and at levels, not in fixed R, so translate carefully and move gradually.
5. **Fill rates.** A setup, level or trigger distance that seldom fills wastes attention; one that
   fills and loses wastes money. Unfilled plans are in the tables as plans minus filled.
6. **Earlier changes.** For each past version, did the trades made under it do better or worse
   than before? If a change did not help, propose moving it back towards its original value.
7. **Costs and realism.** Every figure is already after costs and conservative fills. Do not
   assume better execution than the grading allows.
8. **Overfitting.** More parameters changed at once means less certainty about which one helped.
   Change the few with the strongest, broadest evidence.
9. Anything the data suggests that no parameter can express goes in `codeIdeas`.

""".trimStart())

        // ---------------------------------------------------------------- answer
        sb.append("## IMPORTANT - how to answer\n\n")
        sb.append(ClaudeBridge.fileDelivery(ClaudeBridge.ANSWER_ENGINE)).append("\n\n")
        sb.append("**The block is a SCHEMA, not an example answer.** Every `<...>` is a description of what belongs there - replace each with your own real value. Your JSON must be valid: no `<`, no `>`, no `...`, no comments, no trailing commas.\n\n")
        sb.append("```json\n").append(SHAPE.replace("{VERSION}", state.version.toString()).replace("{TRADES}", n.toString())).append("\n```\n\n")
        sb.append("Share the file to the Portfolio app, or import it in the app: Watch tab -> Research -> Day Trading -> Improve the engine -> Import tuning answer. The app shows every change for approval before anything applies.\n\n")
        sb.append("---\n\n")

        // ---------------------------------------------------------------- data
        sb.append("## DATA FROM THE APP\n\n")
        sb.append("Generated $today. engineVersion = ${state.version}. gradedTrades (app plans) = $n. " +
            "Trades graded under an older grader whose price history has expired, and so excluded: ${stats.legacyExcluded}.\n\n")
        sb.append("### Every graded plan (newest first")
        if (graded.size > MAX_TRADE_ROWS) sb.append("; the newest $MAX_TRADE_ROWS of ${graded.size} - the tables above cover all of them")
        sb.append(")\n\n")
        sb.append("Columns: day, time (ET, when shown), sym, src (app/claude), eng (engine version), setup, lvl (entry level), px (price when shown), " +
            "entry, stop, target, rr (planned reward:risk), riskAtr (stop width in intraday ATRs), trigAtr (entry minus price, in intraday ATRs), " +
            "atrPct (intraday ATR as % of price), vwapAtr (price minus VWAP in ATRs), rangeUsed (share of the average daily range used), chg (% day change), " +
            "score/lik/conf (blended score, likelihood, confidence), rvol (paced relative volume), mso (minutes since the open), outcome, " +
            "netR, fillMin (minutes from shown to filled), holdMin (minutes held), mfeR/maeR (best/worst move after the fill until the exit, R), " +
            "runR (best move after the fill until the flat time, R), holdR (gross R with no target: the stop or the flat-time price), res (bar minutes it was graded on).\n\n")
        sb.append("```csv\n")
        sb.append("day,time,sym,src,eng,setup,lvl,px,entry,stop,target,rr,riskAtr,trigAtr,atrPct,vwapAtr,rangeUsed,chg,score,lik,conf,rvol,mso,outcome,netR,fillMin,holdMin,mfeR,maeR,runR,holdR,res\n")
        for (r in graded.sortedByDescending { it.e.recordedAt }.take(MAX_TRADE_ROWS)) {
            val t = Instant.ofEpochMilli(r.e.recordedAt).atZone(ET)
            fun o(k: String) = r.num(k)?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
            fun csv(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "'") + "\"" else s
            val d = r.d
            val fillMin = if (d != null && d.fillAt > 0) ((d.fillAt * 1000 - r.e.recordedAt) / 60000).coerceAtLeast(0).toString() else ""
            val holdMin = if (d != null && d.fillAt > 0 && d.exitAt >= d.fillAt) ((d.exitAt - d.fillAt) / 60 + d.res).toString() else ""
            sb.append(listOf(
                r.e.tradingDay, "%02d:%02d".format(t.hour, t.minute), r.e.symbol, if (r.app) "app" else "claude",
                r.e.engine, csv(r.e.setup), csv(r.f.optString("lvl", "")), o("px"),
                f2(r.e.entry), f2(r.e.stop), f2(r.e.target), o("rr"), o("riskAtr"), o("trigAtr"), o("atrPct"), o("vwapAtr"),
                o("rangeUsed"), o("chg"), o("score"), o("lik"), o("conf"), o("rvol"), r.f.optString("mso", ""),
                r.e.outcome ?: "", if (r.decided) f2(r.netR) else "", fillMin, holdMin,
                d?.takeIf { it.fill > 0 }?.let { f2(it.mfeR) } ?: "", d?.takeIf { it.fill > 0 }?.let { f2(it.maeR) } ?: "",
                d?.takeIf { it.fill > 0 }?.let { f2(it.mfeFlatR) } ?: "", d?.takeIf { it.fill > 0 }?.let { f2(it.holdR) } ?: "",
                d?.res?.toString() ?: ""
            ).joinToString(",")).append("\n")
        }
        sb.append("```\n")
        return sb.toString()
    }

    /** What the same trades would have done with other stops and targets - averaged, by setup. */
    private fun gridSection(rows: List<Row>): String {
        if (rows.isEmpty()) return "## What other stops and targets would have done\n\nNo graded trades with a counterfactual grid yet.\n\n"
        val sb = StringBuilder("## What other stops and targets would have done (same fills, same bars)\n\n")
        sb.append("Each cell: the average net R of the app's trades had the stop been at S x the planned risk below the entry and the " +
            "target T x that risk above it (\"plan\" = the plan's own target, \"none\" = no target, sold at the flat time). R is measured in each " +
            "variant's OWN risk, so cells are comparable under the app's fixed 1%-risk sizing. The (1.0, plan) cell is what actually happened.\n\n")
        fun block(title: String, rs: List<Row>) {
            if (rs.isEmpty()) return
            val tHead = DayTradingGrader.GRID_TARGETS.map {
                when (it) { DayTradingGrader.GRID_PLAN -> "plan"; DayTradingGrader.GRID_NONE -> "none"; else -> "${it}R" }
            }
            sb.append("### $title (${rs.size} trades)\n\n| stop \\ target | ${tHead.joinToString(" | ")} |\n|---|${tHead.joinToString("") { "---|" }}\n")
            for ((si, s) in DayTradingGrader.GRID_STOPS.withIndex()) {
                val cells = DayTradingGrader.GRID_TARGETS.indices.map { ti ->
                    rs.mapNotNull { it.d?.grid?.getOrNull(si)?.getOrNull(ti) }.takeIf { it.isNotEmpty() }?.average()
                }
                sb.append("| ${s}R | ${cells.joinToString(" | ") { it?.let { v -> r2(v) } ?: "-" }} |\n")
            }
            sb.append("\n")
        }
        block("All app trades", rows)
        rows.groupBy { it.e.setup }.filter { it.value.size >= 10 }.forEach { (k, v) -> block("Setup: $k", v) }
        return sb.toString()
    }

    /**
     * THE ENGINE, IN WORDS, WITH ITS CURRENT VALUES - the algorithm `ResearchScore.planInternal`,
     * `dayTrading`, `withTechnicals` and `dayTradingConfidence` implement. Any change to those
     * functions must be reflected here; `EngineTuningTest` pins that every parameter is named.
     */
    internal fun algorithm(p: DayTradingParams): String {
        // THE KEY AND ITS VALUE TOGETHER, so every number in the description maps straight onto a
        // row of the parameter table - and onto the `param` an answer has to name.
        fun v(k: String) = "`$k` [${EngineTuning.describe(k, p[k])}]"
        val setups = DayTradingParams.SETUP_KEYS.values.joinToString { "$it ${v("setup.$it.enabled")}" }
        val levels = DayTradingParams.LEVELS.joinToString { "$it ${v("level.$it.enabled")}" }
        val over = DayTradingParams.SETUP_KEYS.values.joinToString("; ") { s ->
            "$s: minRiskAtrs ${v("setup.$s.minRiskAtrs")}, maxRiskAtrs ${v("setup.$s.maxRiskAtrs")}, targetCapR ${v("setup.$s.targetCapR")}, minRewardRisk ${v("setup.$s.minRewardRisk")}"
        }
        return """
**Which stocks.** A screener pass (every ~30 min) scores the market's most active, biggest-moving,
most-shorted and most-discussed stocks ($2+ a share, 1M+ average volume, trading above their normal
volume pace). **Likelihood** (0-100) = relative volume paced to the time of day ramped 1x -> ${v("score.rvolFullAt")}x worth ${v("score.rvolPoints")}
points + today's % move ramped 0 -> ${v("score.moveFullAt")}% worth ${v("score.movePoints")} + r/wallstreetbets mentions ${v("score.mentionPoints")} and
news ${v("score.newsPoints")} (relative to the day's busiest) + most-shorted: ${v("score.squeezePoints")} when also rvol >= ${v("conf.rvolThreshold")} and
up >= ${v("conf.moveThreshold")}% (a squeeze shape), else ${v("score.shortedPoints")} + within 15% of the 52-week high ${v("score.nearHighPoints")} (else above
the 50-day average ${v("score.aboveFiftyDayPoints")}) + earnings today/tomorrow ${v("score.catalystPoints")}; live, every 30 s: + above VWAP
${v("score.vwapPoints")} + above a completed 30-minute opening range ${v("score.orbPoints")}. **Confidence** = 20 points per confirmed check of five
(rvol >= ${v("conf.rvolThreshold")}, up >= ${v("conf.moveThreshold")}%, near high or squeeze, above VWAP, above the completed opening range).
**Score** = likelihood x confidence / 100. The list is the top 40 by score; the plans on the first page (10, more if I tap
"load more") are the ones recorded. After the first full live pass the list is re-sorted: rows with a plan first, by score.

**Inputs per stock** (Yahoo, every 30 s while the tab is open): 5-minute bars today with pre-market (VWAP, the
30-minute opening range 09:30-10:00, the first 5-minute bar, session high/low, an intraday ATR(14) on 5-minute
bars); daily bars (ATR(14), 14-day average daily range ADR, the prior session's high/low/close, floor pivots
PP=(H+L+C)/3, R1=2PP-L, R2=PP+(H-L), S1=2PP-H); the pre-market high. `vol` = the intraday ATR, or daily ATR x
${v(DayTradingParams.ATR_FROM_DAILY)} before any intraday bars exist. `buffer` = max(${'$'}0.01, vol x ${v(DayTradingParams.BREAK_BUFFER)}).

**Filters first:** no plan when the score is below ${v(DayTradingParams.MIN_SCORE)}; while live, no plan when the first 5-minute bar closed
at or below its open and ${v(DayTradingParams.REQUIRE_BULLISH_BAR)} is on.

**1. Setup and entry.** (Setups enabled: $setups.) While live:
- price below VWAP -> **VWAP reclaim**: entry = VWAP + buffer (a buy-stop).
- else, price >= ${v(DayTradingParams.EXTENDED_ATRS)} vol above VWAP, or the session range >= ${v(DayTradingParams.EXTENDED_RANGE)} x ADR -> **Pullback**: entry =
  the highest support level below the price (VWAP, the opening-range high once complete, the first 5-minute bar's
  high and low, the opening-range low, the prior high, the prior close, the pivot, the session low, S1), or
  price - vol when there is none (a buy-limit).
- else -> **Breakout**: entry = the lowest ENABLED overhead level at or above the price, + buffer (a buy-stop).
  Overhead levels (enabled: $levels): pre-market high, first 5-minute bar high, the 30-minute opening-range high
  (only once complete), the prior session's high, the high of day, R1, R2. With none: the price + buffer.
Before the open (the plan for the coming session): Breakout only, off the pre-market high (only before today's
session has printed), the last session's high, R1, R2. A disabled setup -> no plan.

**2. Stop.** The highest support level below the entry, minus buffer; the distance is then clamped into
[${v(DayTradingParams.MIN_RISK)}, ${v(DayTradingParams.MAX_RISK)}] x vol (with no level: 1.5 x vol, clamped the same way).

**3. Target.** The lowest enabled overhead level more than ${v(DayTradingParams.TARGET_STANDOFF_R)} x risk above max(entry, price), capped by the
day's remaining room: session low + ADR while live, entry + ADR before the open. No plan when that room is less than
${v(DayTradingParams.TARGET_MIN_CEILING_R)} x risk above max(entry, price). With neither a level nor an ADR: entry + ${v(DayTradingParams.TARGET_FALLBACK_R)} x risk. Then, when
set, the target is capped at entry + ${v(DayTradingParams.TARGET_CAP_R)} x risk. No plan if the target is not above max(entry, price).

**4. Rejections.** No plan when reward:risk < ${v(DayTradingParams.MIN_RR)}, or (breakouts) when the trigger is more than ${v(DayTradingParams.MAX_TRIGGER_ATRS)} vol above
the price. Per-setup overrides (0 = use the global value): $over.

**5. Time.** No new trade with fewer than ${v(DayTradingParams.LAST_ENTRY_MIN)} minutes of session left (and an unfilled entry is cancelled then);
plans in the first ${v(DayTradingParams.EARLIEST_ENTRY_MIN)} minutes after the open, and (${v(DayTradingParams.AVOID_LULL)}) in the 11:30-13:30 lull, are shown as
"not yet" and not recorded; with the lull rule on, an entry still unfilled at 11:30 is cancelled.

**6. Exit.** The target (a resting limit), the stop, or sold at market ${v(DayTradingParams.FLAT_BEFORE_CLOSE_MIN)} minutes before the close. Warnings
only (no effect on levels or grading): target more than ${v(DayTradingParams.WARN_BIG_TARGET_R)}R away, reward:risk at or below ${v(DayTradingParams.WARN_THIN_R)}, trigger more
than ${v(DayTradingParams.WARN_TRIGGER_ATRS)} vol above the price, the midday lull, earnings today.

**Recorded** only when the plan is live (fresh intraday data under 10 minutes old, market open), still waiting for its
entry (stop < price < target, price on the near side of the entry), not "too late" or "not yet", $1+, and on the page I see.
""".trim()
    }

    private const val SHAPE = """{
  "portfolioAppResponse": 1,
  "dayTradingEngine": {
    "basedOn": { "engineVersion": {VERSION}, "gradedTrades": {TRADES} },
    "verdict": <string - one or two sentences: is the engine making money after costs, and how sure can we be from this sample>,
    "analysis": <string - one short paragraph: what worked, what did not, with the groups and their trade counts>,
    "changes": [
      {
        "param": <string - a param name from the parameter table>,
        "from": <number - its CURRENT value, copied from the table>,
        "to": <number - the new value; 0 or 1 for a switch; 0 turns an optional filter off>,
        "basis": <string - the group whose graded trades justify it: "all", "setup:Breakout", "setup:Pullback", "setup:VWAP reclaim", "level:<level name from the tables>", "time:First hour", "time:Midday", "time:Last two hours", or "engine:v<n>">,
        "evidenceTrades": <integer - graded trades in that group>,
        "expectedEffect": <string - what should improve, and roughly how much, per the tables>,
        "rationale": <string - the specific evidence, quoting the numbers>
      }
    ],
    "keep": <string - what should NOT change yet, and why>,
    "watchNext": <string - what to look at in the next review>,
    "codeIdeas": [<string - an improvement that would need new app code rather than a parameter, or leave the list empty>],
    "nextReviewAfterTrades": <integer - how many more graded trades before another review is worth doing>
  }
}"""
}
