package com.tj.portfolio.net

import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.util.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE DAY-TRADING ENGINE LEARNS FROM ITS OWN GRADED RESULTS - WITH CLAUDE, AND WITHIN LIMITS
 * THE APP ENFORCES ITSELF (2026-09-24c).
 *
 * Tj: *"a mechanism in the portfolio app which allows the imported Claude file to change the
 * settings and algorithms in the day trading section"*, under four rules: (1) no major change on a
 * small sample, (2) the tracking it learns from must be accurate (see [DayTradingGrader]), (3) a
 * one-tap revert to the original engine, and (4) every prompt carries the current engine and every
 * change already made, so each round builds on the last.
 *
 * The round trip: the tuning prompt ([EngineTuningPrompt]) -> Claude writes a `dayTradingEngine`
 * answer file -> shared (or imported) back -> [parse] -> [review] against the CURRENT engine and
 * the CURRENT log, where the sample-size rules are applied by the app, not merely requested of
 * Claude -> Tj sees what would change and taps Apply -> [apply] installs it and records it in
 * [State.history].
 *
 * WHAT A CHANGE CAN TOUCH: exactly the parameters in [DayTradingParams.SPECS], each inside its hard
 * bounds - plan geometry, stops, targets, filters, time windows, setup and level switches, ranking
 * weights. Not code, and not position sizing. Ideas that need new code come back as
 * [Proposal.codeIdeas] and are shown to Tj, not executed.
 */
object EngineTuning {

    const val PAYLOAD_KEY = "dayTradingEngine"
    private val WANTED = listOf(PAYLOAD_KEY, "portfolioAppResponse")

    // ================================================================ STATE AND HISTORY

    data class Change(val key: String, val from: Double, val to: Double)

    /** One apply / undo / revert, in the order they happened. */
    data class HistoryEntry(
        /** The engine version this entry produced. */
        val version: Int,
        val at: Long,
        /** [KIND_APPLY], [KIND_UNDO] or [KIND_REVERT]. */
        val kind: String,
        val changes: List<Change>,
        /** Claude's verdict for an apply; a plain description for an undo or revert. */
        val summary: String = "",
        /** "param: rationale" lines, for an apply. */
        val rationale: List<String> = emptyList(),
        /** Graded trades of the app's own plans when this happened. */
        val gradedTrades: Int = 0,
        val paramsBefore: DayTradingParams = DayTradingParams.DEFAULTS,
        val paramsAfter: DayTradingParams = DayTradingParams.DEFAULTS,
        /** For an apply: when a later undo or revert took it back (0 = still in force or superseded normally). */
        val undoneAt: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("version", version); put("at", at); put("kind", kind)
            put("changes", JSONArray().apply {
                changes.forEach { put(JSONObject().put("param", it.key).put("from", it.from).put("to", it.to)) }
            })
            if (summary.isNotBlank()) put("summary", summary)
            if (rationale.isNotEmpty()) put("rationale", JSONArray(rationale))
            put("gradedTrades", gradedTrades)
            put("before", paramsBefore.toJson())
            put("after", paramsAfter.toJson())
            if (undoneAt > 0) put("undoneAt", undoneAt)
        }

        companion object {
            fun fromJson(o: JSONObject): HistoryEntry? = runCatching {
                HistoryEntry(
                    version = o.getInt("version"),
                    at = o.optLong("at"),
                    kind = o.optString("kind", KIND_APPLY),
                    changes = o.optJSONArray("changes")?.let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            a.optJSONObject(i)?.let { c ->
                                Change(c.optString("param"), c.optDouble("from", 0.0), c.optDouble("to", 0.0))
                            }
                        }
                    }.orEmpty(),
                    summary = o.optString("summary", ""),
                    rationale = o.optJSONArray("rationale")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    gradedTrades = o.optInt("gradedTrades", 0),
                    paramsBefore = DayTradingParams.fromJson(o.optJSONObject("before")),
                    paramsAfter = DayTradingParams.fromJson(o.optJSONObject("after")),
                    undoneAt = o.optLong("undoneAt", 0L)
                )
            }.getOrNull()
        }
    }

    const val KIND_APPLY = "apply"
    const val KIND_UNDO = "undo"
    const val KIND_REVERT = "revert"

    /** At most this many history entries are kept - far more than a year of weekly tunings. */
    const val HISTORY_MAX = 200

    data class State(
        val params: DayTradingParams = DayTradingParams.DEFAULTS,
        val version: Int = 0,
        val history: List<HistoryEntry> = emptyList()
    ) {
        val isOriginal: Boolean get() = params.isDefault
        /** The most recent apply still in force - when the change now in force was made. */
        private val applyInForce: HistoryEntry? get() = history.lastOrNull { it.kind == KIND_APPLY && it.undoneAt == 0L }
        /**
         * What "Undo last change" takes back: a "Revert to original" that was the last thing done
         * (audit PL-15 - a mistaken revert used to be final, with the tuned engine sitting in its own
         * history), else the most recent apply still in force.
         */
        val undoable: HistoryEntry? get() =
            history.lastOrNull()?.takeIf { it.kind == KIND_REVERT && it.undoneAt == 0L } ?: applyInForce
        /**
         * When the engine now IN FORCE came into force (0 = the original engine: never tuned, or every
         * change taken back) - the start of the "since then" count. An undone or reverted change is
         * not being measured any more, so it no longer holds the next one back (UI-10); and after an
         * undo - of a change or of a revert - the count starts at that undo, not at the old apply, so
         * trades another engine made meanwhile are not counted for this one (audit R2T-2).
         */
        val lastApplyAt: Long get() = when {
            params.isDefault -> 0L
            else -> history.lastOrNull()?.takeIf { it.paramsAfter == params }?.at ?: applyInForce?.at ?: 0L
        }

        fun engineJson(): String = JSONObject().put("version", version).put("params", params.toJson()).toString()
        fun historyJson(): String = JSONArray().apply { history.forEach { put(it.toJson()) } }.toString()
    }

    /**
     * TOTAL - a missing or corrupt store reads as the original engine, never as an exception.
     *
     * [logVersion] is the highest `vN` label already in the day-trading log (audit DA-11 / PL-9): a
     * restore of an older backup rolls the engine store back while the log keeps rows made by the
     * later versions, and re-issuing "v3" for a different set of values would mix two engines in
     * every per-version figure. When the log is ahead, the engine becomes a version the log has
     * never seen (the caller stores it, so it stays that number).
     *
     * An unreadable engine row with an intact history runs what the history says is in force (its
     * last entry's result), not the original under a tuned version number (DA-11 c).
     */
    fun load(engineJson: String?, historyJson: String?, logVersion: Int = 0): State {
        val e = runCatching { JSONObject(engineJson ?: "") }.getOrNull()
        val h = runCatching { JSONArray(historyJson ?: "") }.getOrNull()
        val history = h?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(HistoryEntry::fromJson) } }.orEmpty()
        val stored = e?.optJSONObject("params")
        return State(
            params = if (stored != null) DayTradingParams.fromJson(stored) else history.lastOrNull()?.paramsAfter ?: DayTradingParams.DEFAULTS,
            version = maxOf(e?.optInt("version", 0) ?: 0, history.maxOfOrNull { it.version } ?: 0)
                .let { v -> if (logVersion > v) logVersion + 1 else v },
            history = history
        )
    }

    /** The version number in an engine label ("v3" -> 3), or 0. */
    fun versionOfLabel(label: String?): Int =
        label?.trim()?.takeIf { it.length > 1 && (it[0] == 'v' || it[0] == 'V') }?.substring(1)?.toIntOrNull() ?: 0

    private fun record(state: State, entry: HistoryEntry, mark: (HistoryEntry) -> HistoryEntry = { it }): State {
        val history = (state.history.map(mark) + entry).takeLast(HISTORY_MAX)
        return State(entry.paramsAfter, entry.version, history)
    }

    /**
     * "Undo last change": back to the parameters before the most recent apply still in force - or,
     * when the last thing done was a revert, back to the tuned engine that revert replaced, with the
     * changes it took back in force again.
     */
    fun undo(state: State, now: Long, gradedTrades: Int): State? {
        val target = state.undoable ?: return null
        val day = java.time.Instant.ofEpochMilli(target.at).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
        val entry = HistoryEntry(
            version = state.version + 1, at = now, kind = KIND_UNDO,
            changes = target.paramsBefore.diffFrom(state.params).map { Change(it.first, it.second, it.third) },
            summary = if (target.kind == KIND_REVERT) "Undid the revert to the original made $day (back to the engine before it)"
                else "Undid the change applied $day (engine v${target.version})",
            gradedTrades = gradedTrades,
            paramsBefore = state.params, paramsAfter = target.paramsBefore
        )
        return record(state, entry) {
            when {
                it === target -> it.copy(undoneAt = now)
                // The applies that revert took back are in force again.
                target.kind == KIND_REVERT && it.kind == KIND_APPLY && it.undoneAt == target.at -> it.copy(undoneAt = 0L)
                else -> it
            }
        }
    }

    /** "Revert to the original engine": every change ever applied, taken back in one step. */
    fun revert(state: State, now: Long, gradedTrades: Int): State? {
        if (state.isOriginal) return null
        val entry = HistoryEntry(
            version = state.version + 1, at = now, kind = KIND_REVERT,
            changes = DayTradingParams.DEFAULTS.diffFrom(state.params).map { Change(it.first, it.second, it.third) },
            summary = "Reverted to the original engine",
            gradedTrades = gradedTrades,
            paramsBefore = state.params, paramsAfter = DayTradingParams.DEFAULTS
        )
        return record(state, entry) { if (it.kind == KIND_APPLY && it.undoneAt == 0L) it.copy(undoneAt = now) else it }
    }

    // ================================================================ EVIDENCE (what the log proves)

    /**
     * The graded trades the app itself counts - never Claude's own tally. Only the app's own plans
     * (a Claude plan says nothing about the engine) graded by the current grader, decided.
     */
    class Evidence(val rows: List<DayTradingLogEntry>, since: Long) {
        val decided: List<DayTradingLogEntry> = rows.filter {
            it.source != DayTradingLogEntry.SOURCE_CLAUDE && it.evalVersion >= DayTradingGrader.VERSION &&
                it.outcome in DECIDED && !DayTradingEval.notTradeableOldRow(it)
        }
        /** Each decided row's trigger level, parsed once (audit PL-11) - not once per proposed change. */
        private val levels: List<String> by lazy { decided.map { levelOf(it) } }
        val total: Int get() = decided.size
        /** Graded since the last applied change - what that change has been measured on. */
        val sinceLastChange: Int = decided.count { it.recordedAt > since }

        /** Decided trades in a basis group - "all", "setup:Pullback", "level:the prior session's high", "time:Midday", "engine:v2". */
        fun count(basis: String): Int? {
            val b = basis.trim()
            if (b.isEmpty() || b.equals("all", true)) return total
            val kind = b.substringBefore(':').trim().lowercase()
            val value = b.substringAfter(':', "").trim()
            if (value.isEmpty()) return null
            return when (kind) {
                "setup" -> decided.count { it.setup.equals(value, true) ||
                    DayTradingParams.setupKey(it.setup)?.equals(value, true) == true }
                // A level's KEY ("prevHigh") counts every label it is shown under; a label counts itself.
                "level" -> DayTradingParams.LEVELS.firstOrNull { it.equals(value, true) }
                    ?.let { key -> DayTradingParams.LEVEL_LABELS.getValue(key).let { labels -> levels.count { it in labels } } }
                    ?: levels.count { it.equals(value, true) }
                "time" -> decided.count { timeBucket(it.recordedAt).equals(value, true) }
                // "v0" is the original engine, which is also what rows logged before versions were
                // recorded ran (their label is blank) - the prompt's table lists both as v0 (R2T-19).
                "engine" -> value.substringBefore(' ').let { v ->
                    decided.count { it.engine.equals(v, true) || (v.equals("v0", true) && it.engine.isBlank()) }
                }
                else -> null
            }
        }
    }

    private val DECIDED = setOf(DayTradingOutcome.WIN, DayTradingOutcome.LOSS,
        DayTradingOutcome.CLOSED_PROFIT, DayTradingOutcome.CLOSED_LOSS)

    fun levelOf(e: DayTradingLogEntry): String =
        runCatching { JSONObject(e.features).optString("lvl", "") }.getOrDefault("")

    /**
     * THE GROUP A PARAMETER ACTS ON (audit DA-3) - the trades whose evidence a change to [key] has
     * to rest on, whatever group the answer cites: a setup's settings on that setup's trades, a
     * level's switch on the trades that triggered off it, a time rule on the trades of its window.
     * Null = the whole sample. The review uses the SMALLER of this and the cited group, so citing
     * "all" can no longer carry a switch that rests on four trades.
     */
    fun groupFor(key: String): String? {
        val parts = key.split('.')
        return when {
            parts.size == 3 && parts[0] == "setup" -> "setup:${parts[1]}"
            parts.size == 3 && parts[0] == "level" -> "level:${parts[1]}"
            key == DayTradingParams.AVOID_LULL -> "time:Midday"
            key == DayTradingParams.EARLIEST_ENTRY_MIN -> "time:First hour"
            key == DayTradingParams.LAST_ENTRY_MIN -> "time:Last two hours"
            else -> null
        }
    }

    /** The same three buckets the success card uses. */
    fun timeBucket(recordedAt: Long): String {
        val et = java.time.Instant.ofEpochMilli(recordedAt).atZone(java.time.ZoneId.of("America/New_York"))
        val m = et.hour * 60 + et.minute
        return when {
            m < 10 * 60 + 30 -> "First hour"
            m < MarketClock.closeMinuteAt(recordedAt) - 120 -> "Midday"
            else -> "Last two hours"
        }
    }

    // ================================================================ THE SAMPLE-SIZE RULE (Tj's rule 1)

    /**
     * How much an import may change, by how many graded trades of the app's own plans exist.
     * [maxStep] is a fraction of the parameter's allowed range per import; switches (a setup or
     * level on/off, a filter switched on or off) are the "major" changes and need both a larger
     * sample and at least [minGroupForSwitch] trades in the group the change cites.
     */
    enum class Tier(val minTrades: Int, val maxChanges: Int, val maxStep: Double, val switches: Boolean, val label: String) {
        NONE(0, 0, 0.0, false, "Not enough graded trades yet - no changes can be applied"),
        SMALL(30, 3, 0.10, false, "Small, gradual changes only (up to 3, each at most a tenth of its range)"),
        MEDIUM(75, 5, 0.20, true, "Moderate changes (up to 5, each at most a fifth of its range; switches need 30+ trades in their group)"),
        LARGE(150, 8, 0.35, true, "Larger changes (up to 8, each at most about a third of its range; switches need 30+ trades in their group)");

        companion object {
            fun of(n: Int): Tier = values().last { n >= it.minTrades }
            fun next(n: Int): Tier? = values().firstOrNull { it.minTrades > n }
        }
    }

    /** Trades a cited group needs before a switch may be flipped on its evidence. */
    const val MIN_GROUP_FOR_SWITCH = 30
    /** Trades a cited group needs before any number may move on its evidence. */
    const val MIN_GROUP_FOR_CHANGE = 20
    /** Graded trades needed since the last applied change before another is accepted. */
    const val MIN_TRADES_BETWEEN_CHANGES = 20

    // ================================================================ PARSE

    data class ProposedChange(
        val key: String,
        val from: Double?,
        /** NaN when Claude gave none, or not a number ([toMissing] says which). */
        val to: Double,
        val basis: String,
        val evidenceTrades: Int,
        val expectedEffect: String,
        val rationale: String,
        /** The change had no `to` at all (audit R2T-23) - shown refused, never silently dropped. */
        val toMissing: Boolean = false
    )

    data class Proposal(
        val basedOnVersion: Int? = null,
        val basedOnTrades: Int? = null,
        val verdict: String = "",
        val analysis: String = "",
        val keep: String = "",
        val watchNext: String = "",
        val codeIdeas: List<String> = emptyList(),
        val nextReviewAfterTrades: Int = 0,
        val changes: List<ProposedChange> = emptyList(),
        val error: String? = null
    )

    /** True when this text carries a tuning answer at all - used to route a share or an import. */
    fun looksLikeTuning(text: String): Boolean =
        ClaudeBridge.findObject(text, WANTED)?.has(PAYLOAD_KEY) == true

    /**
     * A number, or null when absent. [onOff] also reads true/false/"on"/"off" as 1/0 - only for an
     * on/off setting (audit DA-18: `"to": true` on a target cap used to become a 1R cap nobody
     * wrote). Anything else present but unreadable is NaN, which the review refuses by name.
     */
    private fun num(o: JSONObject, key: String, onOff: Boolean = false, offOk: Boolean = false): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is Boolean -> if (onOff) (if (v) 1.0 else 0.0) else if (offOk && !v) 0.0 else Double.NaN
            is String -> v.trim().let { t -> t.toDoubleOrNull() ?: when (t.lowercase()) {
                "true", "on", "yes" -> if (onOff) 1.0 else Double.NaN
                // "off" IS 0 for a setting that 0 switches off (audit R2T-5) - the prompt's own table
                // prints it that way, so a `from` copied from it must read.
                "false", "off", "no", "0 (off)" -> if (onOff || offOk) 0.0 else Double.NaN
                else -> Double.NaN } }
            else -> Double.NaN
        }?.let { if (it.isFinite()) it else Double.NaN }
    }

    fun parse(text: String): Proposal {
        if (ClaudeBridge.isPromptFile(text)) return Proposal(
            error = "That is the tuning prompt this app wrote, not Claude's answer. Share it to a " +
                "Claude chat, then share the file Claude writes back here."
        )
        val root = ClaudeBridge.findObject(text, WANTED)
            ?: return Proposal(error = "No engine-tuning answer found in that file. Make sure you shared Claude's whole reply, including the ```json block.")
        val t = root.optJSONObject(PAYLOAD_KEY) ?: (if (root.has("changes")) root else null)
            ?: return Proposal(error = "That file has JSON in it, but no \"$PAYLOAD_KEY\" block - it may answer a different prompt.")
        val based = t.optJSONObject("basedOn")
        val arr = t.optJSONArray("changes") ?: JSONArray()
        val changes = ArrayList<ProposedChange>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val key = c.text("param").ifBlank { c.text("key") }.trim()
            val spec = DayTradingParams.SPEC_BY_KEY[key]
            val onOff = spec?.kind == DayTradingParams.Kind.BOOL
            val offOk = spec?.offAllowed == true
            // A CHANGE WITH NO NEW VALUE OR NO PARAMETER IS KEPT, AND REFUSED BY NAME (audit R2T-23):
            // dropped, the sheet said "Claude recommends no changes" beside an analysis proposing one.
            val to = num(c, "to", onOff, offOk)
            if (key.isBlank() && to == null) continue
            changes.add(ProposedChange(
                key = key, from = num(c, "from", onOff, offOk), to = to ?: Double.NaN, toMissing = to == null,
                basis = c.text("basis").ifBlank { "all" },
                evidenceTrades = c.optInt("evidenceTrades", 0),
                expectedEffect = ClaudeBridge.scrub(c.text("expectedEffect")),
                rationale = ClaudeBridge.scrub(c.text("rationale"))
            ))
        }
        return Proposal(
            basedOnVersion = based?.let { num(it, "engineVersion")?.takeIf { v -> v.isFinite() }?.toInt() },
            basedOnTrades = based?.let { num(it, "gradedTrades")?.takeIf { v -> v.isFinite() }?.toInt() },
            verdict = ClaudeBridge.scrub(t.text("verdict")),
            analysis = ClaudeBridge.scrub(t.text("analysis")),
            keep = ClaudeBridge.scrub(t.text("keep")),
            watchNext = ClaudeBridge.scrub(t.text("watchNext")),
            codeIdeas = t.optJSONArray("codeIdeas")?.let { a ->
                (0 until a.length()).map { ClaudeBridge.scrub(a.optString(it)) }.filter { it.isNotBlank() }
            }.orEmpty(),
            nextReviewAfterTrades = t.optInt("nextReviewAfterTrades", 0).coerceAtLeast(0),
            changes = changes
        )
    }

    // ================================================================ REVIEW (the app's own guard)

    enum class Status { ACCEPTED, LIMITED, REFUSED, UNCHANGED }

    data class Reviewed(
        val change: ProposedChange,
        val status: Status,
        /** What the parameter would actually become (null when refused). */
        val applied: Double?,
        /** The current value. */
        val current: Double?,
        val reason: String,
        /** The cited group's graded trades BY THE APP'S OWN COUNT (UI-4); null when it could not be counted. */
        val groupCount: Int? = null,
        /** The group [groupCount] is of - the cited one, or the smaller group the parameter acts on (R2T-4). */
        val groupName: String = ""
    )

    data class Review(
        val proposal: Proposal,
        val items: List<Reviewed>,
        val tier: Tier,
        val trades: Int,
        val sinceLastChange: Int,
        /** Why nothing at all can be applied (stale prompt, too few trades), or blank. */
        val blocker: String,
        val paramsAfter: DayTradingParams
    ) {
        val applicable: List<Reviewed> get() = items.filter { it.status == Status.ACCEPTED || it.status == Status.LIMITED }
        val canApply: Boolean get() = blocker.isBlank() && applicable.isNotEmpty()
    }

    private fun fmt(v: Double, spec: DayTradingParams.Spec?): String = when {
        !v.isFinite() -> "?"
        spec?.kind == DayTradingParams.Kind.BOOL -> if (v >= 0.5) "on" else "off"
        spec?.offAllowed == true && v == 0.0 -> "off"
        spec?.kind == DayTradingParams.Kind.INT -> v.toInt().toString()
        else -> java.math.BigDecimal.valueOf(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun describe(key: String, v: Double): String = fmt(v, DayTradingParams.SPEC_BY_KEY[key])

    private fun raw(v: Double): String = if (!v.isFinite()) "?" else
        java.math.BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Every proposed change checked against the CURRENT engine and the log as it stands now:
     * the prompt must be about this engine version, each `from` must still be the value in force,
     * each value must be inside its hard bounds, and the sample-size tier decides how many changes,
     * how big a step, and whether switches are allowed at all. An over-large step is LIMITED to
     * the allowed step (the direction Claude found, taken carefully) rather than refused.
     */
    fun review(proposal: Proposal, state: State, log: List<DayTradingLogEntry>): Review {
        val ev = Evidence(log, state.lastApplyAt)
        val n = ev.total
        val tier = Tier.of(n)
        val blocker = when {
            proposal.error != null -> proposal.error
            proposal.basedOnVersion != null && proposal.basedOnVersion != state.version ->
                "This answer was written for engine v${proposal.basedOnVersion}, but the app is now on " +
                    "v${state.version} - a change was applied, undone or reverted since that prompt was " +
                    "made. Make a new tuning prompt so Claude sees the engine as it is now."
            proposal.changes.isEmpty() -> ""
            // AN ANSWER THAT DOES NOT SAY WHICH ENGINE IT IS ABOUT CANNOT BE CHECKED AGAINST THIS ONE
            // (audit DA-5) - a hand-edited or truncated reply, or an old answer for an engine since
            // changed, would otherwise be applied as though Claude had seen this one.
            proposal.basedOnVersion == null ->
                "This answer does not say which engine version it was written for (its \"basedOn\" is " +
                    "missing), so the app cannot tell whether it still fits the engine as it is now. Make " +
                    "a new tuning prompt and share Claude's complete reply."
            tier == Tier.NONE ->
                "Only $n graded trade${if (n == 1) "" else "s"} from the app's own plans so far - the " +
                    "engine is not changed on fewer than ${Tier.SMALL.minTrades}. Claude's analysis is " +
                    "still shown; keep using the Day Trading tab and ask again later."
            state.lastApplyAt > 0 && ev.sinceLastChange < MIN_TRADES_BETWEEN_CHANGES ->
                "The last change has only been measured on ${ev.sinceLastChange} graded trade" +
                    "${if (ev.sinceLastChange == 1) "" else "s"} since it was applied - another change " +
                    "waits for at least $MIN_TRADES_BETWEEN_CHANGES, so each one can be judged on its own."
            else -> ""
        }
        var params = state.params
        var used = 0
        val items = ArrayList<Reviewed>()
        val seen = HashSet<String>()
        for (c0 in proposal.changes) {
            val spec = DayTradingParams.SPEC_BY_KEY[c0.key]
            // THE PRECISION THE PROMPT SHOWS (audit DA-16): a value is kept to three decimals (a whole
            // number for a count), so the next prompt's table shows exactly what is in force and
            // Claude's `from` can match it.
            val c = if (spec != null && c0.to.isFinite()) c0.copy(to = roundFor(c0.to, spec)) else c0
            val current = spec?.let { state.params[it.key] }
            // THE EVIDENCE THE CHANGE RESTS ON (DA-3): the cited group, but never more than the group
            // the parameter itself acts on.
            val cited = ev.count(c.basis)
            // BACK TO THE ORIGINAL VALUE rests on the cited group alone (audit R2T-12): it is the less
            // risky direction, and a setup or level switched off makes no new trades of its own - its
            // group only shrinks, and after a grader change it could never be switched back on.
            val backToOriginal = spec != null && c.to.isFinite() && kotlin.math.abs(c.to - spec.default) < 1e-9
            val own = if (backToOriginal) null else groupFor(c.key)
            val ownN = own?.let { ev.count(it) }
            val counted = if (cited != null && ownN != null) minOf(cited, ownN) else cited
            val groupName = if (cited != null && ownN != null && ownN < cited) own!! else c.basis
            fun refuse(why: String) = Reviewed(c, Status.REFUSED, null, current, why, counted, groupName)
            val r: Reviewed = when {
                spec == null -> refuse(if (c.key.isBlank()) "Claude named no parameter for this change."
                    else "Not a parameter this app has - nothing to change.")
                !seen.add(c.key) -> refuse("Listed twice - only the first is used.")
                blocker.isNotBlank() -> refuse("Not applied - see the reason above.")
                c.from != null && c.from.isNaN() ->
                    refuse("Claude's \"from\" for it is not a number - make a new prompt and ask again.")
                c.from == null ->
                    refuse("Claude did not say what it read this as (\"from\"), so the app cannot check the " +
                        "prompt it saw is the engine as it is now.")
                current != null && kotlin.math.abs(c.from - current) > FROM_TOLERANCE ->
                    refuse("Claude read it as ${fmt(c.from, spec)}, but it is ${fmt(current, spec)} now - the prompt is out of date.")
                c.toMissing -> refuse("Claude gave no new value (\"to\") for it - make a new prompt and ask again.")
                c.to.isNaN() -> refuse(if (spec.kind == DayTradingParams.Kind.BOOL) "Not a value this on/off setting can take."
                    else "Not a number - this setting needs a number (true/on are for on/off settings only).")
                current != null && kotlin.math.abs(c.to - current) < 1e-9 ->
                    Reviewed(c, Status.UNCHANGED, current, current, "Already ${fmt(current, spec)} - nothing to change.", counted, groupName)
                // THE WRONG KIND OF VALUE, said as such (UI-15) - rounding 0.7 to "on" first would
                // print "on is outside what this parameter allows (off to on)".
                spec.kind == DayTradingParams.Kind.BOOL && c.to != 0.0 && c.to != 1.0 ->
                    refuse("${raw(c.to)} - this setting is on/off only (0 or 1).")
                spec.kind == DayTradingParams.Kind.INT && c.to != Math.rint(c.to) ->
                    refuse("${raw(c.to)} - this setting takes whole numbers only.")
                !spec.allows(c.to) -> refuse("${fmt(c.to, spec)} is outside what this parameter allows " +
                    "(${fmt(spec.min, spec)} to ${fmt(spec.max, spec)}${if (spec.offAllowed) ", or off" else ""}).")
                else -> {
                    val turningOn = spec.offAllowed && current == 0.0 && c.to != 0.0
                    // SWITCHING A PER-SETUP OVERRIDE OFF moves that setup to the GLOBAL value, which
                    // earlier rounds may have moved far (audit R2T-8): the same step limit applies.
                    val offDistance = if (spec.offAllowed && current != null && current != 0.0 && c.to == 0.0 &&
                        c.key.startsWith("setup.")) kotlin.math.abs(current - onBase(c.key, spec, params)) else 0.0
                    val isSwitch = spec.kind == DayTradingParams.Kind.BOOL ||
                        (spec.offAllowed && (current == 0.0 || c.to == 0.0))
                    val groupN = counted
                    when {
                        used >= tier.maxChanges ->
                            refuse("More changes than ${n} graded trades allow at once (${tier.maxChanges}) - the rest wait for the next review.")
                        isSwitch && !tier.switches ->
                            refuse("Switching this ${if (c.to == 0.0) "off" else "on"} is a major change - it needs " +
                                "${Tier.MEDIUM.minTrades}+ graded trades (there are $n).")
                        groupN == null -> refuse("Its evidence (\"${c.basis}\") is not a group the app can count - " +
                            "use all, setup:<name>, level:<name>, time:<First hour|Midday|Last two hours> or engine:v<n>.")
                        groupN < MIN_GROUP_FOR_CHANGE ->
                            refuse("Only $groupN graded trade${if (groupN == 1) "" else "s"} in \"$groupName\"" +
                                (if (groupName != c.basis) " (the trades this setting acts on)" else "") + " - " +
                                "at least $MIN_GROUP_FOR_CHANGE are needed before a change rests on that group.")
                        isSwitch && groupN < MIN_GROUP_FOR_SWITCH ->
                            refuse("A switch needs at least $MIN_GROUP_FOR_SWITCH graded trades in its group " +
                                "(\"$groupName\" has $groupN).")
                        offDistance > tier.maxStep * spec.range + 1e-9 ->
                            refuse("Switching it off moves this setup from ${fmt(current!!, spec)} to " +
                                // The global may be off too (audit R3T-1): then "off" is as lenient as the
                                // range goes, and that end is what it is measured to - not "the global".
                                (if (globalOn(c.key, params)) "the global ${fmt(onBase(c.key, spec, params), spec)}"
                                else "no limit at all (the global setting is off too - as lenient as " +
                                    "${fmt(onBase(c.key, spec, params), spec)})") +
                                " - more than one step at this sample size. " +
                                "Move it toward that value instead; the next review can switch it off.")
                        else -> {
                            // The step, limited to what the tier allows - in the direction Claude chose.
                            // A filter or override SWITCHED ON is a step too (audit DA-4), measured from
                            // where it would have least effect: the global value an override replaces,
                            // or the lenient end of a filter's range - never "anything in bounds".
                            val from = if (turningOn) onBase(c.key, spec, params) else current ?: spec.default
                            var to = c.to
                            var limited = false
                            if (!isSwitch || turningOn) {
                                val maxDelta = tier.maxStep * spec.range
                                if (kotlin.math.abs(to - from) > maxDelta + 1e-12) {
                                    to = towards(from + kotlin.math.sign(to - from) * maxDelta, from, spec)
                                    to = to.coerceIn(spec.min, spec.max)
                                    limited = true
                                }
                            }
                            val candidate = runCatching { params.with(mapOf(c.key to to)) }.getOrNull()
                            when {
                                candidate == null || (limited && !turningOn && kotlin.math.abs(to - from) < 1e-9) ->
                                    refuse("The step this sample allows is too small to move it.")
                                else -> {
                                    // Consistency is judged on the whole answer below, not change by
                                    // change in the order Claude listed them (audit R2T-6).
                                    params = candidate
                                    used++
                                    if (limited) Reviewed(c, Status.LIMITED, to, current,
                                        "Will apply, limited to ${fmt(to, spec)} - with $n graded trades one import may move it at most " +
                                            "${(tier.maxStep * 100).toInt()}% of its range" +
                                            (if (turningOn) ", counted from where switching it on changes least (${fmt(from, spec)})" else "") +
                                            ". The direction stands; the next review can go further.", counted, groupName)
                                    else Reviewed(c, Status.ACCEPTED, to, current, "", counted, groupName)
                                }
                            }
                        }
                    }
                }
            }
            items.add(r)
        }
        // THE WHOLE ANSWER MUST LEAVE A CONSISTENT ENGINE (audits DA-15, R2T-6, R2T-20). A pair that is
        // consistent together (a floor and its ceiling both raised) is accepted in either order; when
        // the result conflicts, the LAST listed change that takes part is refused, until it does not.
        // A conflict the engine already had before this answer is not blamed on it.
        val before = conflicts(state.params).map { it.first }.toSet()
        while (true) {
            val clash = conflicts(params).firstOrNull { it.first !in before } ?: break
            val idx = items.indexOfLast {
                (it.status == Status.ACCEPTED || it.status == Status.LIMITED) && it.change.key in clash.second
            }
            if (idx < 0) break
            val drop = items[idx]
            items[idx] = Reviewed(drop.change, Status.REFUSED, null, drop.current,
                "${clash.first} - refused to keep the engine consistent.", drop.groupCount, drop.groupName)
            params = items.filter { it.status == Status.ACCEPTED || it.status == Status.LIMITED }
                .fold(state.params) { p, it -> p.with(mapOf(it.change.key to it.applied!!)) }
        }
        return Review(proposal, items, tier, n, ev.sinceLastChange, blocker, params)
    }

    /** How far a `from` may differ from the value in force - half the prompt's last shown digit (DA-16). */
    const val FROM_TOLERANCE = 5e-4 + 1e-9

    /** Three decimals for a number, a whole number for a count, 0/1 for a switch - what the prompt shows. */
    private fun roundFor(v: Double, spec: DayTradingParams.Spec): Double = when (spec.kind) {
        DayTradingParams.Kind.NUMBER -> java.math.BigDecimal.valueOf(v).setScale(3, java.math.RoundingMode.HALF_UP).toDouble()
        else -> v
    }

    /** A limited step, rounded TOWARD [from] - so rounding never takes it past the step it was limited to. */
    private fun towards(v: Double, from: Double, spec: DayTradingParams.Spec): Double {
        // The binary error of `from + step` first (audit R2T-10: 1.5 + 0.35 x 3.5 is 2.7249999999999996,
        // which floored to 2.724), then toward `from`.
        val clean = java.math.BigDecimal.valueOf(v).setScale(9, java.math.RoundingMode.HALF_EVEN)
        return when (spec.kind) {
            DayTradingParams.Kind.INT -> clean.setScale(0, if (v > from) java.math.RoundingMode.FLOOR else java.math.RoundingMode.CEILING).toDouble()
            DayTradingParams.Kind.NUMBER -> clean.setScale(3,
                if (v > from) java.math.RoundingMode.FLOOR else java.math.RoundingMode.CEILING).toDouble()
            else -> v
        }
    }

    /** The filters where a LOWER value is the stricter one - switched on from the top of their range. */
    private val LOWER_IS_STRICTER: Set<String> = setOf(DayTradingParams.TARGET_CAP_R, DayTradingParams.MAX_TRIGGER_ATRS) +
        DayTradingParams.SETUP_KEYS.values.map { "setup.$it.targetCapR" }

    /**
     * Where a switched-off parameter starts from when it is switched on (DA-4): a per-setup override
     * from the global value it replaces (when that is on), anything else from the lenient end of its
     * range.
     */
    /** A per-setup override's global counterpart is set (non-zero) - [onBase] is then that global value. */
    private fun globalOn(key: String, p: DayTradingParams): Boolean {
        val parts = key.split('.')
        if (parts.size != 3 || parts[0] != "setup") return false
        val global = when (parts[2]) {
            "minRiskAtrs" -> DayTradingParams.MIN_RISK
            "maxRiskAtrs" -> DayTradingParams.MAX_RISK
            "targetCapR" -> DayTradingParams.TARGET_CAP_R
            "minRewardRisk" -> DayTradingParams.MIN_RR
            else -> return false
        }
        return p[global] > 0.0
    }

    private fun onBase(key: String, spec: DayTradingParams.Spec, p: DayTradingParams): Double {
        val parts = key.split('.')
        if (parts.size == 3 && parts[0] == "setup") {
            val global = when (parts[2]) {
                "minRiskAtrs" -> DayTradingParams.MIN_RISK
                "maxRiskAtrs" -> DayTradingParams.MAX_RISK
                "targetCapR" -> DayTradingParams.TARGET_CAP_R
                "minRewardRisk" -> DayTradingParams.MIN_RR
                else -> null
            }
            val g = global?.let { p[it] } ?: 0.0
            if (g > 0.0) return g.coerceIn(spec.min, spec.max)
        }
        return if (key in LOWER_IS_STRICTER) spec.max else spec.min
    }

    /** Why [p] would be self-contradictory, or null - the first of [conflicts]. */
    internal fun inconsistency(p: DayTradingParams): String? = conflicts(p).firstOrNull()?.first

    /**
     * Everything that would make [p] self-contradictory, each with the parameters taking part: a stop
     * floor above its ceiling (globally or for a setup); a last-entry time that leaves no room before
     * the flat time (DA-15 - plans startable after the card's own "be flat by"); every setup switched
     * off, or time rules that leave no window to start a trade in (R2T-20 - an engine that makes no
     * plans also makes no evidence to learn from).
     */
    internal fun conflicts(p: DayTradingParams): List<Pair<String, Set<String>>> {
        val out = ArrayList<Pair<String, Set<String>>>()
        val L = DayTradingParams
        if (p[L.MIN_RISK] > p[L.MAX_RISK] + 1e-9) out.add("It would put the stop floor above its ceiling" to setOf(L.MIN_RISK, L.MAX_RISK))
        for (k in L.SETUP_KEYS.values) {
            val lo = p["setup.$k.minRiskAtrs"]; val hi = p["setup.$k.maxRiskAtrs"]
            val effLo = if (lo > 0) lo else p[L.MIN_RISK]
            val effHi = if (hi > 0) hi else p[L.MAX_RISK]
            if (effLo > effHi + 1e-9) out.add("It would put the $k stop floor above its ceiling" to
                setOf("setup.$k.minRiskAtrs", "setup.$k.maxRiskAtrs", L.MIN_RISK, L.MAX_RISK))
        }
        if (p.lastEntryMinutes < p.flatBeforeCloseMinutes + MIN_ENTRY_TO_FLAT_MINUTES)
            out.add(("New trades must stop at least $MIN_ENTRY_TO_FLAT_MINUTES minutes before the flat time " +
                "(last entry ${p.lastEntryMinutes} min before the close, flat ${p.flatBeforeCloseMinutes})") to
                setOf(L.LAST_ENTRY_MIN, L.FLAT_BEFORE_CLOSE_MIN))
        if (L.SETUP_KEYS.values.none { p.flag("setup.$it.enabled") })
            out.add("It would switch every setup off - no plans at all" to L.SETUP_KEYS.values.map { "setup.$it.enabled" }.toSet())
        if (entryWindowMinutes(p) < MIN_ENTRY_WINDOW_MINUTES)
            out.add("The time rules would leave under $MIN_ENTRY_WINDOW_MINUTES minutes a day to start a trade" to
                setOf(L.EARLIEST_ENTRY_MIN, L.LAST_ENTRY_MIN, L.AVOID_LULL))
        return out
    }

    /** Minutes of a full session (09:30-16:00) in which the time rules allow a new trade. */
    internal fun entryWindowMinutes(p: DayTradingParams): Int {
        val start = p.earliestEntryMinutes.coerceAtLeast(0)
        val end = 390 - p.lastEntryMinutes
        if (end <= start) return 0
        var total = end - start
        if (p.avoidMiddayLull) total -= (minOf(end, 240) - maxOf(start, 120)).coerceAtLeast(0)
        return total
    }

    /** The least time the rules must leave for starting a trade (R2T-20). */
    const val MIN_ENTRY_WINDOW_MINUTES = 30

    /** The least room between the last new entry and the flat time, in minutes (DA-15). */
    const val MIN_ENTRY_TO_FLAT_MINUTES = 10

    /**
     * Would applying [b] do exactly what [a] showed? Same items, same fates, same values (UI-3): the
     * sheet Tj approved must be the change that is installed, never a re-review's different one.
     */
    fun sameDecisions(a: Review, b: Review): Boolean =
        a.blocker.isBlank() == b.blocker.isBlank() &&
            a.items.size == b.items.size &&
            a.items.zip(b.items).all { (x, y) ->
                x.change.key == y.change.key && x.status == y.status &&
                    (x.applied ?: Double.NaN).let { xa -> val ya = y.applied ?: Double.NaN
                        (xa.isNaN() && ya.isNaN()) || kotlin.math.abs(xa - ya) < 1e-9 }
            }

    /** Installs a reviewed answer's accepted and limited changes as a new engine version. */
    fun apply(review: Review, state: State, now: Long): State? {
        if (!review.canApply) return null
        val after = review.paramsAfter
        val diff = after.diffFrom(state.params)
        if (diff.isEmpty()) return null
        val entry = HistoryEntry(
            version = state.version + 1, at = now, kind = KIND_APPLY,
            changes = diff.map { Change(it.first, it.second, it.third) },
            summary = review.proposal.verdict.take(600),
            rationale = review.applicable.map { "${it.change.key}: ${it.change.rationale}".take(400) },
            gradedTrades = review.trades,
            paramsBefore = state.params, paramsAfter = after
        )
        return record(state, entry)
    }
}
