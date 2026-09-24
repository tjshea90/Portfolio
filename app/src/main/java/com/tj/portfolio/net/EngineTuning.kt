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
        /** The most recent apply still in force - what "Undo last change" takes back. */
        val undoable: HistoryEntry? get() = history.lastOrNull { it.kind == KIND_APPLY && it.undoneAt == 0L }
        /** When the last change Claude made was applied (0 = never) - the start of the "since then" count. */
        val lastApplyAt: Long get() = history.lastOrNull { it.kind == KIND_APPLY }?.at ?: 0L

        fun engineJson(): String = JSONObject().put("version", version).put("params", params.toJson()).toString()
        fun historyJson(): String = JSONArray().apply { history.forEach { put(it.toJson()) } }.toString()
    }

    /** TOTAL - a missing or corrupt store reads as the original engine, never as an exception. */
    fun load(engineJson: String?, historyJson: String?): State {
        val e = runCatching { JSONObject(engineJson ?: "") }.getOrNull()
        val h = runCatching { JSONArray(historyJson ?: "") }.getOrNull()
        val history = h?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(HistoryEntry::fromJson) } }.orEmpty()
        return State(
            params = DayTradingParams.fromJson(e?.optJSONObject("params")),
            version = (e?.optInt("version", 0) ?: 0).coerceAtLeast(history.maxOfOrNull { it.version } ?: 0),
            history = history
        )
    }

    private fun record(state: State, entry: HistoryEntry, mark: (HistoryEntry) -> HistoryEntry = { it }): State {
        val history = (state.history.map(mark) + entry).takeLast(HISTORY_MAX)
        return State(entry.paramsAfter, entry.version, history)
    }

    /** "Undo last change": back to the parameters before the most recent apply still in force. */
    fun undo(state: State, now: Long, gradedTrades: Int): State? {
        val target = state.undoable ?: return null
        val entry = HistoryEntry(
            version = state.version + 1, at = now, kind = KIND_UNDO,
            changes = target.paramsBefore.diffFrom(state.params).map { Change(it.first, it.second, it.third) },
            summary = "Undid the change applied ${java.time.Instant.ofEpochMilli(target.at)
                .atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()} (engine v${target.version})",
            gradedTrades = gradedTrades,
            paramsBefore = state.params, paramsAfter = target.paramsBefore
        )
        return record(state, entry) { if (it === target) it.copy(undoneAt = now) else it }
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
                it.outcome in DECIDED
        }
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
                "level" -> decided.count { levelOf(it).equals(value, true) }
                "time" -> decided.count { timeBucket(it.recordedAt).equals(value, true) }
                "engine" -> decided.count { it.engine.equals(value, true) }
                else -> null
            }
        }
    }

    private val DECIDED = setOf(DayTradingOutcome.WIN, DayTradingOutcome.LOSS,
        DayTradingOutcome.CLOSED_PROFIT, DayTradingOutcome.CLOSED_LOSS)

    fun levelOf(e: DayTradingLogEntry): String =
        runCatching { JSONObject(e.features).optString("lvl", "") }.getOrDefault("")

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
        val to: Double,
        val basis: String,
        val evidenceTrades: Int,
        val expectedEffect: String,
        val rationale: String
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

    private fun num(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> v.trim().let { t -> t.toDoubleOrNull() ?: when (t.lowercase()) {
                "true", "on", "yes" -> 1.0; "false", "off", "no" -> 0.0; else -> null } }
            else -> null
        }?.takeIf { it.isFinite() }
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
            val to = num(c, "to") ?: continue
            if (key.isBlank()) continue
            changes.add(ProposedChange(
                key = key, from = num(c, "from"), to = to,
                basis = c.text("basis").ifBlank { "all" },
                evidenceTrades = c.optInt("evidenceTrades", 0),
                expectedEffect = ClaudeBridge.scrub(c.text("expectedEffect")),
                rationale = ClaudeBridge.scrub(c.text("rationale"))
            ))
        }
        return Proposal(
            basedOnVersion = based?.let { num(it, "engineVersion")?.toInt() },
            basedOnTrades = based?.let { num(it, "gradedTrades")?.toInt() },
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
        val reason: String
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

    private fun fmt(v: Double, spec: DayTradingParams.Spec?): String = when (spec?.kind) {
        DayTradingParams.Kind.BOOL -> if (v >= 0.5) "on" else "off"
        DayTradingParams.Kind.INT -> v.toInt().toString()
        else -> if (spec?.offAllowed == true && v == 0.0) "off" else
            java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun describe(key: String, v: Double): String = fmt(v, DayTradingParams.SPEC_BY_KEY[key])

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
        for (c in proposal.changes) {
            val spec = DayTradingParams.SPEC_BY_KEY[c.key]
            val current = spec?.let { state.params[it.key] }
            fun refuse(why: String) = Reviewed(c, Status.REFUSED, null, current, why)
            val r: Reviewed = when {
                spec == null -> refuse("Not a parameter this app has - nothing to change.")
                !seen.add(c.key) -> refuse("Listed twice - only the first is used.")
                blocker.isNotBlank() -> refuse(blocker.substringBefore(" - ").let { "Not applied: $it." })
                c.from != null && current != null && kotlin.math.abs(c.from - current) > 1e-6 ->
                    refuse("Claude read it as ${fmt(c.from, spec)}, but it is ${fmt(current, spec)} now - the prompt is out of date.")
                current != null && kotlin.math.abs(c.to - current) < 1e-9 ->
                    Reviewed(c, Status.UNCHANGED, current, current, "Already ${fmt(current, spec)}.")
                !spec.allows(c.to) -> refuse("${fmt(c.to, spec)} is outside what this parameter allows " +
                    "(${fmt(spec.min, spec)} to ${fmt(spec.max, spec)}${if (spec.offAllowed) ", or off" else ""}).")
                else -> {
                    val isSwitch = spec.kind == DayTradingParams.Kind.BOOL ||
                        (spec.offAllowed && (current == 0.0 || c.to == 0.0))
                    val groupN = ev.count(c.basis)
                    when {
                        used >= tier.maxChanges ->
                            refuse("More changes than ${n} graded trades allow at once (${tier.maxChanges}) - the rest wait for the next review.")
                        groupN == null -> refuse("Its evidence (\"${c.basis}\") is not a group the app can count - " +
                            "use all, setup:<name>, level:<name>, time:<First hour|Midday|Last two hours> or engine:v<n>.")
                        groupN < MIN_GROUP_FOR_CHANGE ->
                            refuse("Only $groupN graded trade${if (groupN == 1) "" else "s"} in \"${c.basis}\" - " +
                                "at least $MIN_GROUP_FOR_CHANGE are needed before a change rests on that group.")
                        isSwitch && !tier.switches ->
                            refuse("Switching this ${if (c.to == 0.0) "off" else "on"} is a major change - it needs " +
                                "${Tier.MEDIUM.minTrades}+ graded trades (there are $n).")
                        isSwitch && groupN < MIN_GROUP_FOR_SWITCH ->
                            refuse("A switch needs at least $MIN_GROUP_FOR_SWITCH graded trades in its group " +
                                "(\"${c.basis}\" has $groupN).")
                        else -> {
                            // The step, limited to what the tier allows - in the direction Claude chose.
                            val from = current ?: spec.default
                            var to = c.to
                            var limited = false
                            if (!isSwitch) {
                                val maxDelta = tier.maxStep * spec.range
                                if (kotlin.math.abs(to - from) > maxDelta + 1e-12) {
                                    to = from + kotlin.math.sign(to - from) * maxDelta
                                    if (spec.kind == DayTradingParams.Kind.INT) to = if (to > from) kotlin.math.floor(to) else kotlin.math.ceil(to)
                                    to = to.coerceIn(spec.min, spec.max)
                                    limited = true
                                }
                            }
                            val candidate = runCatching { params.with(mapOf(c.key to to)) }.getOrNull()
                            when {
                                candidate == null || (limited && kotlin.math.abs(to - from) < 1e-9) ->
                                    refuse("The step this sample allows is too small to move it.")
                                !consistent(candidate) ->
                                    refuse("It would put a stop floor above its ceiling - refused to keep the engine consistent.")
                                else -> {
                                    params = candidate
                                    used++
                                    if (limited) Reviewed(c, Status.LIMITED, to, current,
                                        "Limited to ${fmt(to, spec)} - with $n graded trades one import may move it at most " +
                                            "${(tier.maxStep * 100).toInt()}% of its range. The direction stands; the next review can go further.")
                                    else Reviewed(c, Status.ACCEPTED, to, current, "")
                                }
                            }
                        }
                    }
                }
            }
            items.add(r)
        }
        return Review(proposal, items, tier, n, ev.sinceLastChange, blocker, params)
    }

    /** Stop floors never above their ceilings, globally and per setup. */
    private fun consistent(p: DayTradingParams): Boolean {
        if (p[DayTradingParams.MIN_RISK] > p[DayTradingParams.MAX_RISK] + 1e-9) return false
        for (k in DayTradingParams.SETUP_KEYS.values) {
            val lo = p["setup.$k.minRiskAtrs"]; val hi = p["setup.$k.maxRiskAtrs"]
            val effLo = if (lo > 0) lo else p[DayTradingParams.MIN_RISK]
            val effHi = if (hi > 0) hi else p[DayTradingParams.MAX_RISK]
            if (effLo > effHi + 1e-9) return false
        }
        return true
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
