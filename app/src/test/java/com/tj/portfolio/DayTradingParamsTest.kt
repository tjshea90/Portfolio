package com.tj.portfolio

import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.net.DayTradingParams
import com.tj.portfolio.net.DayTradingParams.Companion.DEFAULTS
import com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.ui.loggableDayTradingRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tunable engine (2026-09-24c, B1/B5): every knob does what its spec says, the defaults are
 * the original engine (see also `DayTradingGoldenTest`), and nothing - a corrupt file, an unknown
 * key, an out-of-range value - can install an engine outside the hard bounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingParamsTest {

    /** A live breakout: price 100 under the prior high 101, plenty of room left in the day. */
    private val live = DayTechnicals(
        atr14 = 3.0, vwap = 99.0, openingRangeHigh = 99.8, openingRangeLow = 98.2,
        openingRangeComplete = true, or5High = 99.5, or5Low = 98.5, openingBarBullish = true,
        atrIntraday = 0.5, adr = 6.0, prevHigh = 101.0, prevLow = 95.0, prevClose = 97.0,
        premarketHigh = 0.0, sessionHigh = 100.2, sessionLow = 98.0, sessionLive = true,
        intradayFetched = true, sessionDay = "20260924", lastPrice = 100.0
    )

    private fun plan(p: DayTradingParams, price: Double = 100.0, t: DayTechnicals = live,
                     minutesSinceOpen: Int = 120, lull: Boolean = false, score: Int = -1) =
        ResearchScore.planInternal(price, t, minutesLeft = 200, middayLull = lull, p = p,
            minutesSinceOpen = minutesSinceOpen, score = score)

    @Test fun defaultsAreTheOriginalConstants() {
        assertTrue(DEFAULTS.isDefault)
        assertEquals(ResearchScore.BREAK_BUFFER_ATRS, DEFAULTS.breakBufferAtrs, 0.0)
        assertEquals(ResearchScore.MIN_RISK_ATRS, DEFAULTS.minRiskAtrs(ResearchScore.SETUP_BREAKOUT), 0.0)
        assertEquals(ResearchScore.MAX_RISK_ATRS, DEFAULTS.maxRiskAtrs(ResearchScore.SETUP_PULLBACK), 0.0)
        assertEquals(ResearchScore.MIN_MINUTES_FOR_NEW_ENTRY, DEFAULTS.lastEntryMinutes)
        assertEquals(10, DEFAULTS.flatBeforeCloseMinutes)
        assertEquals(0.0, DEFAULTS.targetCapR(ResearchScore.SETUP_BREAKOUT), 0.0)
        for (s in DayTradingParams.SPECS) assertTrue("${s.key} default allowed", s.allows(s.default))
        assertEquals(DayTradingParams.SPECS.size, DayTradingParams.SPEC_BY_KEY.size)   // no duplicate keys
    }

    @Test fun baselineIsABreakoutOverThePriorHigh() {
        val (p, reason) = plan(DEFAULTS)
        assertNotNull(reason, p)
        assertEquals(ResearchScore.SETUP_BREAKOUT, p!!.setup)
        assertEquals("the high of day", p.entryLevel)   // 100.2 is the nearest level overhead
        assertEquals("", p.waitReason)
    }

    @Test fun withRefusesOutOfBoundsAndUnknownKeys() {
        assertTrue(runCatching { DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 99.0)) }.isFailure)
        assertTrue(runCatching { DEFAULTS.with(mapOf("stop.nonsense" to 1.0)) }.isFailure)
        assertTrue(runCatching { DEFAULTS.with(mapOf("setup.pullback.enabled" to 0.5)) }.isFailure)
        assertTrue(runCatching { DEFAULTS.with(mapOf(DayTradingParams.LAST_ENTRY_MIN to 30.5)) }.isFailure)
        // off (0) is allowed only where the spec says so
        assertTrue(runCatching { DEFAULTS.with(mapOf(DayTradingParams.TARGET_CAP_R to 0.0)) }.isSuccess)
        assertTrue(runCatching { DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 0.0)) }.isFailure)
        // setting a value back to its default leaves an engine equal to the original
        assertEquals(DEFAULTS, DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 2.0)).with(mapOf(DayTradingParams.MIN_RISK to 1.5)))
    }

    @Test fun fromJsonIsTotalAndSkipsBadValues() {
        val o = JSONObject().put(DayTradingParams.MIN_RISK, 2.0).put("unknown.key", 5.0)
            .put(DayTradingParams.MAX_RISK, 1000.0).put("setup.reclaim.enabled", 0.0)
        val p = DayTradingParams.fromJson(o)
        assertEquals(2.0, p[DayTradingParams.MIN_RISK], 0.0)
        assertEquals(ResearchScore.MAX_RISK_ATRS, p[DayTradingParams.MAX_RISK], 0.0)   // out of bounds -> original
        assertFalse(p.setupEnabled(ResearchScore.SETUP_RECLAIM))
        assertEquals(DEFAULTS, DayTradingParams.fromJson(null))
        assertEquals(p, DayTradingParams.fromJson(p.toJson()))   // round trip
        assertEquals(listOf(DayTradingParams.MIN_RISK, "setup.reclaim.enabled"), p.diffFrom(DEFAULTS).map { it.first })
    }

    @Test fun targetCapLimitsTheTargetInR() {
        val base = plan(DEFAULTS).first!!
        val capped = plan(DEFAULTS.with(mapOf(DayTradingParams.TARGET_CAP_R to 0.5))).first!!
        assertEquals(base.entry, capped.entry, 1e-9)
        assertEquals(base.stop, capped.stop, 1e-9)
        assertEquals(capped.entry + capped.risk * 0.5, capped.target, 1e-9)
        assertTrue(capped.target < base.target)
        // a per-setup cap wins over the global one
        val perSetup = plan(DEFAULTS.with(mapOf(DayTradingParams.TARGET_CAP_R to 0.5, "setup.breakout.targetCapR" to 0.8))).first!!
        assertEquals(perSetup.entry + perSetup.risk * 0.8, perSetup.target, 1e-9)
    }

    @Test fun stopMultiplesMoveTheStop() {
        val wide = plan(DEFAULTS.with(mapOf(DayTradingParams.MIN_RISK to 3.0, DayTradingParams.MAX_RISK to 4.0))).first!!
        assertTrue("risk at least 3 intraday ATRs", wide.risk >= 3.0 * 0.5 - 1e-9)
        val tight = plan(DEFAULTS.with(mapOf("setup.breakout.minRiskAtrs" to 0.5, "setup.breakout.maxRiskAtrs" to 1.0))).first!!
        assertTrue(tight.risk <= 1.0 * 0.5 + 1e-9)
    }

    @Test fun aSwitchedOffSetupDeclinesWithAReason() {
        val (p, reason) = plan(DEFAULTS.with(mapOf("setup.breakout.enabled" to 0.0)))
        assertNull(p)
        assertTrue(reason, reason.contains("switched off"))
    }

    @Test fun aSwitchedOffLevelIsNotATrigger() {
        val p = plan(DEFAULTS.with(mapOf("level.sessionHigh.enabled" to 0.0))).first!!
        assertEquals("pivot R1", p.entryLevel)   // next level up, R1 = 100.33
        val q = plan(DEFAULTS.with(mapOf("level.sessionHigh.enabled" to 0.0, "level.r1.enabled" to 0.0))).first
        assertTrue(q == null || q.entry > 101.0)   // prior high 101 is next, or nothing is left to target
    }

    @Test fun minRewardRiskAndTriggerDistanceDecline() {
        val rr = plan(DEFAULTS).first!!.rMultiple
        assertNull(plan(DEFAULTS.with(mapOf(DayTradingParams.MIN_RR to (rr + 0.5).coerceIn(0.5, 4.0)))).first)
        assertNotNull(plan(DEFAULTS.with(mapOf(DayTradingParams.MIN_RR to 0.5))).first)
        // trigger 100.2 + buffer is ~0.5 ATR above the 100.0 price
        assertNull(plan(DEFAULTS.with(mapOf(DayTradingParams.MAX_TRIGGER_ATRS to 0.5)), price = 99.5).first)
    }

    @Test fun minScoreAndOpeningBarFilters() {
        val strict = DEFAULTS.with(mapOf(DayTradingParams.MIN_SCORE to 40.0))
        assertNull(plan(strict, score = 39).first)
        assertNotNull(plan(strict, score = 40).first)
        assertNotNull("unknown score is not a decline", plan(strict, score = -1).first)
        val bar = DEFAULTS.with(mapOf(DayTradingParams.REQUIRE_BULLISH_BAR to 1.0))
        assertNotNull(plan(bar).first)
        assertNull(plan(bar, t = live.copy(openingBarBullish = false)).first)
        assertNotNull("original engine only warns", plan(DEFAULTS, t = live.copy(openingBarBullish = false)).first)
    }

    @Test fun timeWindowsSayNotYetAndAreNotLogged() {
        val early = DEFAULTS.with(mapOf(DayTradingParams.EARLIEST_ENTRY_MIN to 15.0))
        assertTrue(plan(early, minutesSinceOpen = 5).first!!.waitReason.contains("first 15 minutes"))
        assertEquals("", plan(early, minutesSinceOpen = 15).first!!.waitReason)
        val lull = DEFAULTS.with(mapOf(DayTradingParams.AVOID_LULL to 1.0))
        assertTrue(plan(lull, lull = true).first!!.waitReason.contains("Midday"))
        assertEquals("original engine has no windows", "", plan(DEFAULTS, minutesSinceOpen = 1, lull = true).first!!.waitReason)

        val row = ResearchRow(symbol = "ABC", price = 100.0, entryPrice = 100.3, stopPrice = 99.5,
            targetPrice = 101.5, sessionDay = "20260924")
        assertEquals(1, loggableDayTradingRows(listOf(row), "20260924", setOf("ABC")).size)
        assertEquals(0, loggableDayTradingRows(listOf(row.copy(planWait = "wait")), "20260924", setOf("ABC")).size)
    }

    @Test fun lastEntryAndFlatMinutesDriveTheClockRules() {
        val p = DEFAULTS.with(mapOf(DayTradingParams.LAST_ENTRY_MIN to 60.0, DayTradingParams.FLAT_BEFORE_CLOSE_MIN to 20.0))
        assertTrue(ResearchScore.tooLateToStart(45, p))
        assertFalse(ResearchScore.tooLateToStart(45, DEFAULTS))
        assertTrue(ResearchScore.exitPlan(10.0, 200, true, 16 * 60, p).contains("be flat by 15:40 ET"))
        assertTrue(ResearchScore.exitPlan(10.0, 200, true, 16 * 60, DEFAULTS).contains("be flat by 15:50 ET"))
    }

    @Test fun scoreWeightsMoveTheLikelihood() {
        val row = com.tj.portfolio.data.ScreenRow(symbol = "X", price = 20.0, changePct = 6.0,
            volume = 6e6, avgVolume3M = 2e6, fiftyDayAvg = 18.0, fiftyTwoWeekHigh = 40.0, fiftyTwoWeekLow = 10.0)
        val base = ResearchScore.dayTrading(row, null, 0, 0, p = DEFAULTS).score
        val noMove = ResearchScore.dayTrading(row, null, 0, 0, p = DEFAULTS.with(mapOf("score.movePoints" to 0.0))).score
        assertEquals(base - 10, noMove)   // 6% of a 12% ramp to 20 points = 10
    }
}
