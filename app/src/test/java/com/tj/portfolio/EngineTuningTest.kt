package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.net.DayTradingBridge
import com.tj.portfolio.net.DayTradingEngine
import com.tj.portfolio.net.DayTradingEval
import com.tj.portfolio.net.DayTradingGrader
import com.tj.portfolio.net.DayTradingParams
import com.tj.portfolio.net.DayTradingParams.Companion.DEFAULTS
import com.tj.portfolio.net.EngineTuning
import com.tj.portfolio.net.EngineTuning.Status
import com.tj.portfolio.net.EngineTuningPrompt
import com.tj.portfolio.net.SharedAnswer
import com.tj.portfolio.ui.PortfolioViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Tj's four rules for the learning loop (2026-09-24c), each pinned:
 *  1. no major change on a small sample - the app's own tiers, not Claude's say-so;
 *  2. it learns only from the accurate grade (current grader, the app's own plans);
 *  3. undo and a one-step revert to the original engine;
 *  4. the prompt carries the current engine, every change made, and the data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineTuningTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        DayTradingEngine.install(DEFAULTS, 0)
    }

    @After fun tearDown() {
        app.deleteDatabase(Db.DB_NAME)
        DayTradingEngine.install(DEFAULTS, 0)
    }

    private val day0 = 1_789_479_000_000L   // 2026-09-15 09:30 ET

    /** [n] graded trades of the app's own plans: breakouts unless [setup], levels and times rotating. */
    private fun log(n: Int, setup: String = "Breakout", startId: Long = 1, at: (Int) -> Long = { day0 + it * 7 * 60_000L }): List<DayTradingLogEntry> =
        (0 until n).map { i ->
            val win = i % 3 == 0
            val detail = DayTradingGrader.Detail(res = 1, fill = 10.5, fillAt = at(i) / 1000 + 60, exitAt = at(i) / 1000 + 600,
                why = if (win) "target" else "stop", grid = emptyList())
            DayTradingLogEntry(
                id = startId + i, symbol = "S$i", tradingDay = "20260915", recordedAt = at(i), setup = setup,
                entry = 10.5, stop = 10.0, target = 11.5, priceAtRecommendation = 10.3, source = DayTradingLogEntry.SOURCE_APP,
                outcome = if (win) DayTradingOutcome.WIN else DayTradingOutcome.LOSS, outcomeExitPrice = if (win) 11.5 else 10.0,
                outcomeEvaluatedAt = 1L, engine = "v0",
                features = JSONObject().put("lvl", if (i % 2 == 0) "the prior session's high" else "the high of day").put("rr", 2.0).toString(),
                evalVersion = DayTradingGrader.VERSION, evalDetail = detail.toJson()
            )
        }

    private fun answer(version: Int, vararg changes: JSONObject, verdict: String = "Slightly profitable, small sample."): String {
        val root = JSONObject().put("portfolioAppResponse", 1).put("dayTradingEngine", JSONObject()
            .put("basedOn", JSONObject().put("engineVersion", version).put("gradedTrades", 40))
            .put("verdict", verdict).put("analysis", "Breakouts off the prior high worked.")
            .put("changes", JSONArray().apply { changes.forEach { put(it) } })
            .put("keep", "The stop rules.").put("watchNext", "Midday fills.")
            .put("codeIdeas", JSONArray().put("Trail the stop under VWAP."))
            .put("nextReviewAfterTrades", 40))
        return "Here is my review.\n\n```json\n${root.toString(2)}\n```\n\n${ClaudeBridge.SHARE_BACK_LINE}"
    }

    private fun change(param: String, from: Any?, to: Any, basis: String = "all", n: Int = 40) = JSONObject()
        .put("param", param).apply { if (from != null) put("from", from) }.put("to", to).put("basis", basis)
        .put("evidenceTrades", n).put("expectedEffect", "fewer losers").put("rationale", "the numbers")

    // ------------------------------------------------------------------ parse

    @Test fun parsesAWholeAnswerAndItsRouting() {
        val text = answer(0, change(DayTradingParams.MIN_RISK, 1.5, 1.6), change(DayTradingParams.AVOID_LULL, false, "true"))
        val p = EngineTuning.parse(text)
        assertNull(p.error)
        assertEquals(0, p.basedOnVersion)
        assertEquals(2, p.changes.size)
        assertEquals(1.6, p.changes[0].to, 0.0)
        assertEquals(1.0, p.changes[1].to, 0.0)   // "true" read as a switch on
        assertEquals(0.0, p.changes[1].from!!, 0.0)
        assertEquals(listOf("Trail the stop under VWAP."), p.codeIdeas)
        assertEquals(SharedAnswer.Kind.ENGINE_TUNING, SharedAnswer.classify(text))
        assertFalse("never mistaken for a day-trading list answer", DayTradingBridge.looksLikeDayTrading(text))
        assertTrue(EngineTuning.parse("nothing here").error != null)
    }

    // ------------------------------------------------------------------ rule 1: the sample-size guard

    @Test fun belowThirtyTradesNothingChanges() {
        val r = EngineTuning.review(EngineTuning.parse(answer(0, change(DayTradingParams.MIN_RISK, 1.5, 1.6))),
            EngineTuning.State(), log(29))
        assertEquals(EngineTuning.Tier.NONE, r.tier)
        assertFalse(r.canApply)
        assertTrue(r.blocker.contains("fewer than 30"))
        assertTrue(r.items.all { it.status == Status.REFUSED })
    }

    @Test fun claudeOwnPlansAndOldGradesDoNotCount() {
        val claude = log(40).map { it.copy(source = DayTradingLogEntry.SOURCE_CLAUDE) }
        assertEquals(0, EngineTuning.Evidence(claude, 0).total)
        val old = log(40).map { it.copy(evalVersion = 0) }
        assertEquals(0, EngineTuning.Evidence(old, 0).total)
    }

    @Test fun aSmallSampleAllowsThreeSmallStepsAndNoSwitches() {
        val p = EngineTuning.parse(answer(0,
            change(DayTradingParams.MIN_RISK, 1.5, 3.0),                      // big step -> limited
            change(DayTradingParams.BREAK_BUFFER, 0.15, 0.2),                 // small -> accepted
            change("setup.pullback.enabled", 1, 0, "setup:Pullback"),        // switch -> refused (tier)
            change(DayTradingParams.EXTENDED_ATRS, 2.5, 2.7),                 // accepted
            change(DayTradingParams.TARGET_FALLBACK_R, 2.0, 2.1),             // 4th numeric -> too many
            change("stop.nonsense", 1, 2),                                    // unknown
            change(DayTradingParams.MAX_RISK, 2.5, 99.0),                     // out of bounds
            change(DayTradingParams.TARGET_STANDOFF_R, 0.9, 0.4)              // wrong from
        ))
        val r = EngineTuning.review(p, EngineTuning.State(), log(40))
        assertEquals(EngineTuning.Tier.SMALL, r.tier)
        val by = r.items.associateBy { it.change.key }
        assertEquals(Status.LIMITED, by[DayTradingParams.MIN_RISK]!!.status)
        assertEquals(1.5 + 0.10 * (4.0 - 0.5), by[DayTradingParams.MIN_RISK]!!.applied!!, 1e-9)
        assertEquals(Status.ACCEPTED, by[DayTradingParams.BREAK_BUFFER]!!.status)
        assertEquals(Status.REFUSED, by["setup.pullback.enabled"]!!.status)
        assertTrue(by["setup.pullback.enabled"]!!.reason.contains("major change"))
        assertEquals(Status.ACCEPTED, by[DayTradingParams.EXTENDED_ATRS]!!.status)
        assertEquals(Status.REFUSED, by[DayTradingParams.TARGET_FALLBACK_R]!!.status)
        assertTrue(by[DayTradingParams.TARGET_FALLBACK_R]!!.reason.contains("More changes"))
        assertEquals(Status.REFUSED, by["stop.nonsense"]!!.status)
        assertEquals(Status.REFUSED, by[DayTradingParams.MAX_RISK]!!.status)
        assertEquals(Status.REFUSED, by[DayTradingParams.TARGET_STANDOFF_R]!!.status)
        assertTrue(r.canApply)
        assertEquals(3, r.applicable.size)
    }

    @Test fun switchesNeedTheBiggerSampleAndThirtyInTheirGroup() {
        val rows = log(60, "Breakout") + log(40, "Pullback", startId = 1000)
        val ok = EngineTuning.review(EngineTuning.parse(answer(0, change("setup.pullback.enabled", 1, 0, "setup:Pullback"))),
            EngineTuning.State(), rows)
        assertEquals(EngineTuning.Tier.MEDIUM, ok.tier)
        assertEquals(Status.ACCEPTED, ok.items.single().status)
        val thin = log(80, "Breakout") + log(25, "Pullback", startId = 1000)
        val no = EngineTuning.review(EngineTuning.parse(answer(0, change("setup.pullback.enabled", 1, 0, "setup:Pullback"))),
            EngineTuning.State(), thin)
        assertEquals(Status.REFUSED, no.items.single().status)
        assertTrue(no.items.single().reason.contains("at least 30"))
        // and a number resting on a group of 8 trades is refused too
        val tiny = EngineTuning.review(EngineTuning.parse(answer(0, change(DayTradingParams.MIN_RISK, 1.5, 1.6, "time:Last two hours"))),
            EngineTuning.State(), log(40, at = { day0 + it * 60_000L }))
        assertEquals(Status.REFUSED, tiny.items.single().status)
        // an uncountable basis is refused rather than trusted
        val odd = EngineTuning.review(EngineTuning.parse(answer(0, change(DayTradingParams.MIN_RISK, 1.5, 1.6, "vibes"))),
            EngineTuning.State(), log(40))
        assertEquals(Status.REFUSED, odd.items.single().status)
    }

    @Test fun aStalePromptOrAFreshChangeBlocksEverything() {
        val stale = EngineTuning.review(EngineTuning.parse(answer(3, change(DayTradingParams.MIN_RISK, 1.5, 1.6))),
            EngineTuning.State(version = 4), log(40))
        assertFalse(stale.canApply)
        assertTrue(stale.blocker.contains("v3"))
        // applied yesterday, 10 graded trades since -> wait for 20
        val applied = EngineTuning.State(DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.6)), 1,
            listOf(EngineTuning.HistoryEntry(1, day0 + 30 * 7 * 60_000L, EngineTuning.KIND_APPLY, emptyList(),
                paramsAfter = DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 1.6)))))
        val cool = EngineTuning.review(EngineTuning.parse(answer(1, change(DayTradingParams.MIN_RISK, 1.6, 1.7))), applied, log(40))
        assertFalse(cool.canApply)
        assertTrue(cool.blocker.contains("measured"))
        val later = EngineTuning.review(EngineTuning.parse(answer(1, change(DayTradingParams.MIN_RISK, 1.6, 1.7))), applied, log(60))
        assertTrue(later.canApply)
    }

    @Test fun aStopFloorAboveItsCeilingIsRefused() {
        val start = EngineTuning.State(DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 2.4)), 0)
        val r = EngineTuning.review(EngineTuning.parse(answer(0, change(DayTradingParams.MIN_RISK, 2.4, 2.7))), start, log(40))
        assertEquals(Status.REFUSED, r.items.single().status)
    }

    // ------------------------------------------------------------------ rule 3: undo and revert

    @Test fun applyUndoAndRevertAreAChainThatAlwaysEndsAtTheOriginal() {
        val rows = log(200)
        var st = EngineTuning.State()
        fun applyOne(key: String, from: Double, to: Double, at: Long) {
            val r = EngineTuning.review(EngineTuning.parse(answer(st.version, change(key, from, to))), st, rows)
            st = EngineTuning.apply(r, st, at)!!
        }
        applyOne(DayTradingParams.MIN_RISK, 1.5, 1.8, 1000L)
        assertEquals(1, st.version)
        assertEquals(1.8, st.params[DayTradingParams.MIN_RISK], 1e-9)
        // the second change needs 20 trades after the first - the fixture's rows are all "earlier",
        // so move the clock of the apply back for the purpose of this chain test
        st = st.copy(history = st.history.map { it.copy(at = 0L) })
        applyOne(DayTradingParams.BREAK_BUFFER, 0.15, 0.2, 2000L)
        assertEquals(2, st.version)
        val undone = EngineTuning.undo(st, 3000L, 200)!!
        assertEquals(3, undone.version)
        assertEquals(0.15, undone.params[DayTradingParams.BREAK_BUFFER], 1e-9)
        assertEquals(1.8, undone.params[DayTradingParams.MIN_RISK], 1e-9)
        val undone2 = EngineTuning.undo(undone, 4000L, 200)!!
        assertEquals(DEFAULTS, undone2.params)
        assertNull("nothing left to undo", EngineTuning.undo(undone2, 5000L, 200))
        // revert from a tuned engine: one step back to the original, every apply marked undone
        val reverted = EngineTuning.revert(st, 6000L, 200)!!
        assertEquals(DEFAULTS, reverted.params)
        assertTrue(reverted.isOriginal)
        assertTrue(reverted.history.filter { it.kind == EngineTuning.KIND_APPLY }.all { it.undoneAt == 6000L })
        assertNull(EngineTuning.undo(reverted, 7000L, 200))
        assertNull("already original", EngineTuning.revert(reverted, 8000L, 200))
        // and the whole history survives storage
        val back = EngineTuning.load(reverted.engineJson(), reverted.historyJson())
        assertEquals(reverted.version, back.version)
        assertEquals(reverted.history.map { it.kind }, back.history.map { it.kind })
        assertEquals(reverted.history.map { it.paramsAfter }, back.history.map { it.paramsAfter })
    }

    @Test fun aCorruptStoreIsTheOriginalEngine() {
        val st = EngineTuning.load("{not json", "[also not")
        assertEquals(DEFAULTS, st.params)
        assertEquals(0, st.version)
        assertTrue(EngineTuning.load(null, null).isOriginal)
        // an out-of-range stored value falls back to the original for that one parameter
        val bad = EngineTuning.load("""{"version":2,"params":{"stop.minRiskAtrs":99,"plan.breakBufferAtrs":0.2}}""", "[]")
        assertEquals(1.5, bad.params[DayTradingParams.MIN_RISK], 1e-9)
        assertEquals(0.2, bad.params[DayTradingParams.BREAK_BUFFER], 1e-9)
        assertEquals(2, bad.version)
    }

    // ------------------------------------------------------------------ rule 4: the prompt

    @Test fun thePromptCarriesTheEngineItsHistoryAndTheData() {
        val rows = log(40)
        var st = EngineTuning.State()
        val r = EngineTuning.review(EngineTuning.parse(answer(0, change(DayTradingParams.MIN_RISK, 1.5, 1.6))), st, rows)
        st = EngineTuning.apply(r, st, day0 - 1)!!
        val text = EngineTuningPrompt.prompt(st, rows, DayTradingEval.stats(rows), now = day0 + 86_400_000L)
        assertTrue(text.contains(ClaudeBridge.PROMPT_MARK))
        assertTrue(text.contains(ClaudeBridge.startNow()))
        assertEquals(SharedAnswer.Kind.PROMPT_FILE, SharedAnswer.classify(text))
        for (spec in DayTradingParams.SPECS) assertTrue("param table names ${spec.key}", text.contains("| ${spec.key} |"))
        assertTrue(text.contains("engineVersion = 1"))
        assertTrue(text.contains("\"engineVersion\": 1"))
        assertTrue("the change already made", text.contains("stop.minRiskAtrs 1.5 -> 1.6"))
        assertTrue("the current value in the algorithm", text.contains("`stop.minRiskAtrs` [1.6]"))
        assertTrue(text.contains("gradedTrades (app plans) = 40"))
        assertTrue(text.contains("day,time,sym,src,eng,setup"))
        assertTrue(text.contains(ClaudeBridge.ANSWER_ENGINE))
        assertTrue(text.contains(DayTradingGrader.RULES_TEXT.take(60)))
        assertTrue("the enforced tier is spelled out", text.contains("<- **this answer**"))
        // the prompt itself is never importable as an answer
        assertTrue(EngineTuning.parse(text).error != null)
        assertEquals(0, EngineTuning.parse(text).changes.size)
    }

    @Test fun theAlgorithmTextNamesEveryParameter() {
        val text = EngineTuningPrompt.algorithm(DEFAULTS)
        val missing = DayTradingParams.SPECS.map { it.key }.filterNot { k -> text.contains("`$k`") }
        assertEquals(emptyList<String>(), missing)
    }

    // ------------------------------------------------------------------ the round trip, through the ViewModel

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }

    @Test fun importApplyAndRevertThroughTheViewModel() {
        val db = Db(app)
        // 40 graded trades of the app's own plans in the log
        db.logDayTradingRecommendations(log(40).map {
            Db.PendingDayTradingLog(it.symbol, it.tradingDay, it.setup, it.entry, it.stop, it.target,
                it.priceAtRecommendation, it.source, it.recordedAt, it.engine, it.features)
        })
        db.dayTradingLog().forEachIndexed { i, e ->
            val win = i % 3 == 0
            db.setDayTradingOutcome(e.id, if (win) DayTradingOutcome.WIN else DayTradingOutcome.LOSS,
                if (win) 11.5 else 10.0, DayTradingGrader.VERSION, DayTradingGrader.Detail(res = 1, fill = 10.5).toJson())
        }
        val vm = PortfolioViewModel(app)
        settle()
        val msg = vm.importShared(answer(0, change(DayTradingParams.MIN_RISK, 1.5, 1.6))).message
        assertTrue(msg, msg.contains("ready for you to approve"))
        assertNotNull(vm.engineReview.value)
        assertEquals("nothing changes before Apply", DEFAULTS, DayTradingEngine.params)
        vm.applyEngineReview()
        repeat(20) { if (vm.engine.value.version == 0 || vm.engineReview.value != null) settle() }
        assertEquals(1, vm.engine.value.version)
        assertEquals(1.6, DayTradingEngine.params[DayTradingParams.MIN_RISK], 1e-9)
        assertEquals(1, DayTradingEngine.version)
        assertNull(vm.engineReview.value)
        // stored: a fresh ViewModel (a restart) runs the tuned engine
        DayTradingEngine.install(DEFAULTS, 0)
        val vm2 = PortfolioViewModel(app)
        settle()
        assertEquals(1.6, DayTradingEngine.params[DayTradingParams.MIN_RISK], 1e-9)
        // the backup files exist in app storage
        val dir = java.io.File(app.filesDir, "daytrading-engine")
        repeat(10) { if (!java.io.File(dir, "current.json").exists()) settle() }
        assertTrue(java.io.File(dir, "original.json").readText().contains(DayTradingParams.MIN_RISK))
        assertTrue(java.io.File(dir, "current.json").readText().contains("1.6"))
        // revert
        vm2.revertEngine()
        repeat(20) { if (!vm2.engine.value.isOriginal) settle() }
        assertTrue(vm2.engine.value.isOriginal)
        assertEquals(DEFAULTS, DayTradingEngine.params)
        assertEquals(2, vm2.engine.value.version)
    }
}
