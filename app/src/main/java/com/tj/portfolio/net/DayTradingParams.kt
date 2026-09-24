package com.tj.portfolio.net

import org.json.JSONObject

/**
 * EVERY NUMBER THAT SHAPES A DAY-TRADING PLAN OR ITS RANKING, IN ONE PLACE (2026-09-24c).
 *
 * Tj: *"help the app 'learn' which algorithms or methods to use based on actual successful
 * methods ... the Claude export file can properly alter the app"* - and, rule 3, *"an option
 * ... to revert the app back to its original day trading section engine"*.
 *
 * Until now these were `private const val`s scattered through [ResearchScore]: fine for an engine
 * nobody changes, useless for one that has to be tuned from graded results and reverted on
 * demand. They are now entries in [SPECS], each with:
 *  - its ORIGINAL value ([Spec.default]) - [DEFAULTS] is exactly the engine as it was before any
 *    tuning existed, compiled into the APK, so "revert to original" can never be lost or corrupted
 *    (`DayTradingGoldenTest` proves the defaults reproduce the pre-refactor engine line for line);
 *  - HARD BOUNDS no import can cross, whatever the sample size;
 *  - an optional OFF value (0) for the new filters, which default to off so the original engine
 *    is untouched until a tuning deliberately switches one on;
 *  - a one-line description - the SAME text the tuning prompt shows Claude, so the prompt and
 *    the validator can never describe two different engines.
 *
 * WHAT IS DELIBERATELY NOT HERE: position sizing (1% of the portfolio risked per trade, 25% cap
 * per position). That is Tj's money management, not a property of the signals, and a backtest
 * that "finds" a bigger risk fraction is finding leverage, not edge - so no import can touch it.
 *
 * Immutable; a change is a new instance ([with]). Values are stored as doubles (booleans 0/1,
 * integers rounded) so validation, JSON, diffs and the prompt are one generic path.
 */
class DayTradingParams private constructor(private val values: Map<String, Double>) {

    enum class Kind { NUMBER, INT, BOOL }

    data class Spec(
        val key: String,
        val default: Double,
        val min: Double,
        val max: Double,
        val kind: Kind,
        val doc: String,
        /** A value outside [min]..[max] that means "this filter is switched off" (always 0). */
        val offAllowed: Boolean = false
    ) {
        fun allows(v: Double): Boolean = v.isFinite() &&
            ((offAllowed && v == 0.0) || (v >= min - 1e-9 && v <= max + 1e-9)) &&
            (kind != Kind.BOOL || v == 0.0 || v == 1.0) &&
            (kind != Kind.INT || v == Math.rint(v))
        val range: Double get() = if (kind == Kind.BOOL) 1.0 else (max - (if (offAllowed) 0.0 else min)).coerceAtLeast(1e-9)
    }

    operator fun get(key: String): Double = values[key] ?: SPEC_BY_KEY[key]?.default
        ?: throw IllegalArgumentException("unknown day-trading parameter $key")

    fun flag(key: String): Boolean = get(key) >= 0.5

    /** A copy with [changes] applied. Unknown keys and values a spec does not allow are refused here. */
    fun with(changes: Map<String, Double>): DayTradingParams {
        val m = HashMap(values)
        for ((k, v) in changes) {
            val spec = SPEC_BY_KEY[k] ?: throw IllegalArgumentException("unknown day-trading parameter $k")
            require(spec.allows(v)) { "$k = $v is outside its allowed range" }
            if (v == spec.default) m.remove(k) else m[k] = v
        }
        return DayTradingParams(m)
    }

    /** Every parameter whose value differs from [other]'s, as (key, other's value, this value). */
    fun diffFrom(other: DayTradingParams): List<Triple<String, Double, Double>> =
        SPECS.mapNotNull { s -> val a = other[s.key]; val b = this[s.key]; if (a != b) Triple(s.key, a, b) else null }

    val isDefault: Boolean get() = values.isEmpty()

    /** Only what differs from the original engine - the original is implied by its absence. */
    fun toJson(): JSONObject = JSONObject().apply { values.toSortedMap().forEach { (k, v) -> put(k, v) } }

    /** Every parameter with its current value, for the prompt and the backup file. */
    fun toFullJson(): JSONObject = JSONObject().apply { SPECS.forEach { put(it.key, this@DayTradingParams[it.key]) } }

    override fun equals(other: Any?): Boolean = other is DayTradingParams && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "DayTradingParams(${toJson()})"

    // ------------------------------------------------------------------ typed reads

    val breakBufferAtrs get() = get(BREAK_BUFFER)
    val extendedAtrs get() = get(EXTENDED_ATRS)
    val extendedRangeUsed get() = get(EXTENDED_RANGE)
    val fallbackTargetR get() = get(TARGET_FALLBACK_R)
    val targetStandoffR get() = get(TARGET_STANDOFF_R)
    val minCeilingR get() = get(TARGET_MIN_CEILING_R)
    val intradayAtrFromDaily get() = get(ATR_FROM_DAILY)
    val lastEntryMinutes: Int get() = get(LAST_ENTRY_MIN).toInt()
    val flatBeforeCloseMinutes: Int get() = get(FLAT_BEFORE_CLOSE_MIN).toInt()
    val earliestEntryMinutes: Int get() = get(EARLIEST_ENTRY_MIN).toInt()
    val avoidMiddayLull: Boolean get() = flag(AVOID_LULL)
    val requireBullishOpeningBar: Boolean get() = flag(REQUIRE_BULLISH_BAR)
    val minScoreForPlan: Int get() = get(MIN_SCORE).toInt()
    val bigTargetWarnR get() = get(WARN_BIG_TARGET_R)
    val thinRewardR get() = get(WARN_THIN_R)
    val triggerWarnAtrs get() = get(WARN_TRIGGER_ATRS)

    fun setupEnabled(setup: String): Boolean = setupKey(setup)?.let { flag("setup.$it.enabled") } ?: true

    /** A per-setup override when one is set (non-zero), else the global value. */
    private fun perSetup(setup: String, field: String, global: String): Double {
        val s = setupKey(setup) ?: return get(global)
        val v = get("setup.$s.$field")
        return if (v > 0.0) v else get(global)
    }

    fun minRiskAtrs(setup: String) = perSetup(setup, "minRiskAtrs", MIN_RISK)
    fun maxRiskAtrs(setup: String) = maxOf(perSetup(setup, "maxRiskAtrs", MAX_RISK), minRiskAtrs(setup))
    fun targetCapR(setup: String) = perSetup(setup, "targetCapR", TARGET_CAP_R)
    fun minRewardRisk(setup: String) = perSetup(setup, "minRewardRisk", MIN_RR)
    fun maxTriggerAtrs(setup: String) = get(MAX_TRIGGER_ATRS).let { if (setup == ResearchScore.SETUP_BREAKOUT) it else 0.0 }

    fun levelEnabled(level: String): Boolean = flag("level.$level.enabled")

    // ------------------------------------------------------------------ the specs

    companion object {
        const val BREAK_BUFFER = "plan.breakBufferAtrs"
        const val EXTENDED_ATRS = "plan.extendedAtrs"
        const val EXTENDED_RANGE = "plan.extendedRangeUsed"
        const val MIN_RISK = "stop.minRiskAtrs"
        const val MAX_RISK = "stop.maxRiskAtrs"
        const val TARGET_FALLBACK_R = "target.fallbackR"
        const val TARGET_STANDOFF_R = "target.standoffR"
        const val TARGET_MIN_CEILING_R = "target.minCeilingR"
        const val TARGET_CAP_R = "target.capR"
        const val MIN_RR = "filter.minRewardRisk"
        const val MAX_TRIGGER_ATRS = "filter.maxTriggerAtrs"
        const val MIN_SCORE = "filter.minScore"
        const val REQUIRE_BULLISH_BAR = "filter.requireBullishOpeningBar"
        const val EARLIEST_ENTRY_MIN = "time.earliestEntryMinutes"
        const val LAST_ENTRY_MIN = "time.lastEntryMinutes"
        const val AVOID_LULL = "time.avoidMiddayLull"
        const val FLAT_BEFORE_CLOSE_MIN = "time.flatBeforeCloseMinutes"
        const val WARN_BIG_TARGET_R = "warn.bigTargetR"
        const val WARN_THIN_R = "warn.thinRewardR"
        const val WARN_TRIGGER_ATRS = "warn.triggerAtrs"
        const val ATR_FROM_DAILY = "vol.intradayAtrFromDaily"

        /** The engine's three setups, as parameter-key segments. */
        val SETUP_KEYS = linkedMapOf(
            ResearchScore.SETUP_BREAKOUT to "breakout",
            ResearchScore.SETUP_PULLBACK to "pullback",
            ResearchScore.SETUP_RECLAIM to "reclaim"
        )

        fun setupKey(setup: String): String? = SETUP_KEYS[setup]

        /** The overhead levels a breakout can trigger off, as parameter-key segments. */
        const val LVL_PREMARKET = "premarketHigh"
        const val LVL_OR5 = "or5High"
        const val LVL_OR = "orHigh"
        const val LVL_PREV_HIGH = "prevHigh"
        const val LVL_SESSION_HIGH = "sessionHigh"
        const val LVL_R1 = "r1"
        const val LVL_R2 = "r2"
        val LEVELS = listOf(LVL_PREMARKET, LVL_OR5, LVL_OR, LVL_PREV_HIGH, LVL_SESSION_HIGH, LVL_R1, LVL_R2)

        private fun num(key: String, d: Double, lo: Double, hi: Double, doc: String, off: Boolean = false) =
            Spec(key, d, lo, hi, Kind.NUMBER, doc, off)
        private fun int(key: String, d: Int, lo: Int, hi: Int, doc: String, off: Boolean = false) =
            Spec(key, d.toDouble(), lo.toDouble(), hi.toDouble(), Kind.INT, doc, off)
        private fun bool(key: String, d: Boolean, doc: String) =
            Spec(key, if (d) 1.0 else 0.0, 0.0, 1.0, Kind.BOOL, doc)

        val SPECS: List<Spec> = buildList {
            // ---- plan geometry (ResearchScore.planInternal)
            add(num(BREAK_BUFFER, ResearchScore.BREAK_BUFFER_ATRS, 0.0, 0.6, "Entry buffer: a breakout/reclaim entry sits this many intraday (5-minute) ATRs past the level it breaks, and stops sit the same distance under structure."))
            add(num(EXTENDED_ATRS, ResearchScore.EXTENDED_ATRS, 1.0, 5.0, "Price this many intraday ATRs above VWAP counts as extended -> Pullback setup (buy-limit at support) instead of a breakout."))
            add(num(EXTENDED_RANGE, ResearchScore.EXTENDED_RANGE_USED, 0.5, 1.5, "Session range already used (fraction of the 14-day average daily range) at which the stock counts as extended -> Pullback setup."))
            add(num(MIN_RISK, ResearchScore.MIN_RISK_ATRS, 0.5, 4.0, "Stop floor: risk (entry - stop) is at least this many intraday ATRs."))
            add(num(MAX_RISK, ResearchScore.MAX_RISK_ATRS, 1.0, 6.0, "Stop ceiling: risk is at most this many intraday ATRs (a further structural stop is tightened to this)."))
            add(num(TARGET_FALLBACK_R, ResearchScore.TARGET_REWARD_RISK_RATIO, 1.0, 5.0, "Target when there is neither resistance overhead nor a measured daily range: entry + this many R."))
            add(num(TARGET_STANDOFF_R, ResearchScore.MIN_TARGET_STANDOFF_R, 0.0, 1.5, "A resistance level must clear max(entry, price) by at least this many R to be the target."))
            add(num(TARGET_MIN_CEILING_R, ResearchScore.MIN_CEILING_REWARD_RATIO, 0.5, 3.0, "No plan when the day's remaining measured range (session low + ADR live, entry + ADR pre-market) is less than this many R above max(entry, price)."))
            add(num(TARGET_CAP_R, 0.0, 0.5, 10.0, "OFF by default. When set, the target is capped at entry + this many R (a closer fixed profit-take).", off = true))
            add(num(ATR_FROM_DAILY, ResearchScore.INTRADAY_ATR_FROM_DAILY, 0.05, 0.3, "When no intraday ATR exists yet (pre-market), intraday ATR = daily ATR(14) x this."))
            // ---- filters (a plan that fails one is declined, with the reason on the card)
            add(num(MIN_RR, 0.0, 0.5, 4.0, "OFF by default. Decline any plan whose reward:risk is below this.", off = true))
            add(num(MAX_TRIGGER_ATRS, 0.0, 0.5, 6.0, "OFF by default. Decline a breakout whose trigger is more than this many intraday ATRs above the price.", off = true))
            add(int(MIN_SCORE, 0, 1, 80, "OFF by default. Only rows whose blended score (likelihood x confidence) is at least this get a plan.", off = true))
            add(bool(REQUIRE_BULLISH_BAR, false, "OFF by default. While the session is live, decline new plans when the first 5-minute bar closed at or below its open (the Zarattini/Barbon/Aziz ORB direction filter)."))
            // ---- time rules
            add(int(EARLIEST_ENTRY_MIN, 0, 1, 120, "OFF by default. No new plan is recorded (or should be started) in the first N minutes after the open.", off = true))
            add(int(LAST_ENTRY_MIN, ResearchScore.MIN_MINUTES_FOR_NEW_ENTRY, 10, 120, "No new trade with fewer than N minutes of session left; an unfilled entry order is cancelled at that point."))
            add(bool(AVOID_LULL, false, "OFF by default. No new plans 11:30-13:30 ET, and an unfilled entry is cancelled when the lull starts."))
            add(int(FLAT_BEFORE_CLOSE_MIN, ResearchScore.FLATTEN_BEFORE_CLOSE_MINUTES, 5, 60, "Every open trade is closed at market this many minutes before the close."))
            // ---- warnings only (text on the card, no effect on levels or grading)
            add(num(WARN_BIG_TARGET_R, ResearchScore.MAX_REWARD_RISK_RATIO, 1.5, 10.0, "Warning only: note when the target is more than this many R away."))
            add(num(WARN_THIN_R, ResearchScore.THIN_REWARD_RATIO, 0.5, 3.0, "Warning only: note a reward:risk at or below this as thin."))
            add(num(WARN_TRIGGER_ATRS, ResearchScore.MAX_TRIGGER_DISTANCE_ATRS, 0.5, 6.0, "Warning only: note a trigger more than this many intraday ATRs above the price."))
            // ---- setups
            for ((name, k) in SETUP_KEYS) {
                add(bool("setup.$k.enabled", true, "The $name setup is used. OFF = the engine declines instead of planning a $name."))
                add(num("setup.$k.minRiskAtrs", 0.0, 0.5, 4.0, "OFF (= global $MIN_RISK). Stop floor in intraday ATRs for $name plans only.", off = true))
                add(num("setup.$k.maxRiskAtrs", 0.0, 1.0, 6.0, "OFF (= global $MAX_RISK). Stop ceiling in intraday ATRs for $name plans only.", off = true))
                add(num("setup.$k.targetCapR", 0.0, 0.5, 10.0, "OFF (= global $TARGET_CAP_R). Target cap in R for $name plans only.", off = true))
                add(num("setup.$k.minRewardRisk", 0.0, 0.5, 4.0, "OFF (= global $MIN_RR). Minimum reward:risk for $name plans only.", off = true))
            }
            // ---- breakout trigger levels
            val levelDoc = mapOf(
                LVL_PREMARKET to "the pre-market high", LVL_OR5 to "the first 5-minute bar's high",
                LVL_OR to "the 30-minute opening-range high", LVL_PREV_HIGH to "the prior session's high",
                LVL_SESSION_HIGH to "the high of day", LVL_R1 to "floor pivot R1", LVL_R2 to "floor pivot R2"
            )
            for (l in LEVELS) add(bool("level.$l.enabled", true,
                "${levelDoc[l]} may be a breakout trigger and a target. OFF = ignored as a level overhead."))
            // ---- ranking (ResearchScore.dayTrading / withTechnicals / dayTradingConfidence)
            add(num("score.rvolPoints", 30.0, 0.0, 60.0, "Likelihood points for relative volume (paced to the clock), ramped from 1x to score.rvolFullAt."))
            add(num("score.rvolFullAt", 5.0, 2.0, 10.0, "Relative volume that earns the full rvol points."))
            add(num("score.movePoints", 20.0, 0.0, 40.0, "Likelihood points for today's % move, ramped from 0% to score.moveFullAt."))
            add(num("score.moveFullAt", 12.0, 4.0, 25.0, "% move that earns the full move points."))
            add(num("score.mentionPoints", 12.0, 0.0, 24.0, "Likelihood points for r/wallstreetbets mentions (relative to the day's busiest name)."))
            add(num("score.newsPoints", 8.0, 0.0, 16.0, "Likelihood points for today's news count (relative to the busiest)."))
            add(num("score.squeezePoints", 20.0, 0.0, 40.0, "Points for most-shorted AND heavy volume AND a real move (squeeze shape)."))
            add(num("score.shortedPoints", 8.0, 0.0, 16.0, "Points for being on the most-shorted screen without the squeeze shape."))
            add(num("score.nearHighPoints", 10.0, 0.0, 20.0, "Points for trading within 15% of the 52-week high."))
            add(num("score.aboveFiftyDayPoints", 5.0, 0.0, 10.0, "Points for trading above the 50-day average (when not near the 52-week high)."))
            add(num("score.catalystPoints", 8.0, 0.0, 16.0, "Points for earnings today or tomorrow."))
            add(num("score.vwapPoints", 8.0, 0.0, 16.0, "Live bonus points for trading above session VWAP."))
            add(num("score.orbPoints", 12.0, 0.0, 24.0, "Live bonus points for trading above a completed 30-minute opening range."))
            add(num("conf.rvolThreshold", 2.0, 1.0, 5.0, "Confidence check 1 (and squeeze shape): paced relative volume at least this."))
            add(num("conf.moveThreshold", 3.0, 1.0, 10.0, "Confidence check 2 (and squeeze shape): up at least this % today."))
        }

        val SPEC_BY_KEY: Map<String, Spec> = SPECS.associateBy { it.key }

        /** THE ORIGINAL ENGINE. Never changes at runtime; "revert to original" installs this. */
        val DEFAULTS = DayTradingParams(emptyMap())

        /**
         * Reads a stored or imported params object. TOTAL: an unknown key (a newer app wrote it)
         * or a value its spec does not allow (a corrupt file) is skipped - that one parameter keeps
         * its original value - never an exception and never an out-of-bounds engine.
         */
        fun fromJson(o: JSONObject?): DayTradingParams {
            if (o == null) return DEFAULTS
            val m = HashMap<String, Double>()
            for (k in o.keys()) {
                val spec = SPEC_BY_KEY[k] ?: continue
                val v = o.optDouble(k, Double.NaN)
                if (spec.allows(v) && v != spec.default) m[k] = v
            }
            return DayTradingParams(m)
        }
    }
}

/**
 * THE ENGINE THE APP IS RUNNING RIGHT NOW - read by [ResearchScore] through default arguments,
 * installed by the ViewModel from settings at start-up and on every apply / undo / revert.
 * One volatile reference to an immutable value, so a plan computed on a sweep thread sees one
 * consistent parameter set, never half of an update.
 */
object DayTradingEngine {
    /** The parameters and their version, ONE reference - a reader never sees the new version with the old values. */
    class Snapshot(val params: DayTradingParams, val version: Int) {
        /** "" for the original engine's values (whatever the version number), else "v3" - what a plan is labelled with. */
        val tunedLabel: String get() = if (params.isDefault) "" else "v$version"
    }

    @Volatile var current: Snapshot = Snapshot(DayTradingParams.DEFAULTS, 0)
        private set

    val params: DayTradingParams get() = current.params
    /** 0 = the original engine; +1 for every apply, undo or revert since. */
    val version: Int get() = current.version

    fun install(p: DayTradingParams, v: Int) {
        current = Snapshot(p, v)
    }

    /** The label a logged plan carries - which engine made it. */
    fun label(v: Int = version): String = "v$v"
}
