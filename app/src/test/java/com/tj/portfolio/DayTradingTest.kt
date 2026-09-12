package com.tj.portfolio

import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.net.DayTradingBridge
import com.tj.portfolio.net.DayTradingTechnicals
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.net.Screener
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
 * THE DAY TRADING TAB, PINNED DOWN (Round 67).
 *
 * Same three concerns [ResearchTest] exists for, on the newer section: the scorer that decides
 * what is "objectively in play" (see [ResearchScore.dayTrading]'s header for the feasibility
 * finding it is written from - accurate same-day price prediction is not the question this
 * answers), the setup-and-trigger arithmetic in [ResearchScore.tradePlan] that stands in for a
 * forecast honestly, and the bridge, because a Day Trading reply must not be mistaken for a
 * Research or Advice reply now that one file chooser accepts all three.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingTest {

    // ================================================================== scorer

    /** High relative volume, already rising, above its 50-day average - objectively in play. */
    private fun inPlay() = ScreenRow(
        symbol = "HOT",
        price = 12.0,
        changePct = 8.0,
        volume = 20_000_000.0,
        avgVolume3M = 4_000_000.0,
        fiftyDayAvg = 10.0,
        fiftyTwoWeekHigh = 14.0,
        fiftyTwoWeekLow = 6.0
    )

    /** Nothing unusual today - normal volume, flat, sitting in the middle of its range. */
    private fun quiet() = ScreenRow(
        symbol = "MEH",
        price = 12.0,
        changePct = 0.1,
        volume = 4_000_000.0,
        avgVolume3M = 4_000_000.0,
        fiftyDayAvg = 12.0,
        fiftyTwoWeekHigh = 14.0,
        fiftyTwoWeekLow = 10.0
    )

    @Test fun `a stock objectively in play outscores a quiet one`() {
        val hot = ResearchScore.dayTrading(inPlay(), null, maxMentions = 0, maxNews = 0)
        val meh = ResearchScore.dayTrading(quiet(), null, maxMentions = 0, maxNews = 0)
        assertTrue("hot=${hot.score} meh=${meh.score}", hot.score > meh.score + 20)
    }

    @Test fun `every day-trading score carries the specific reasons behind it`() {
        val why = ResearchScore.dayTrading(inPlay(), null, maxMentions = 0, maxNews = 0)
            .reasons.joinToString(" ").lowercase()
        assertTrue("should name the volume multiple: $why", why.contains("normal volume"))
        assertTrue("should name today's move: $why", why.contains("up"))
    }

    @Test fun `a falling stock earns nothing from today's move, even on huge volume`() {
        val falling = inPlay().copy(changePct = -8.0)
        val rising = inPlay()
        val fallingScore = ResearchScore.dayTrading(falling, null, maxMentions = 0, maxNews = 0)
        val risingScore = ResearchScore.dayTrading(rising, null, maxMentions = 0, maxNews = 0)
        assertTrue(
            "a falling stock must not out-score the same stock rising: falling=${fallingScore.score} rising=${risingScore.score}",
            fallingScore.score < risingScore.score
        )
        assertFalse(
            "a falling stock must not be credited with 'expected to rise' language",
            fallingScore.reasons.joinToString(" ").contains("Up ")
        )
    }

    @Test fun `a classic short-squeeze shape scores higher than most-shorted membership alone`() {
        val squeeze = inPlay().copy(lists = setOf(Screener.Lists.MOST_SHORTED))
        val quietlyShorted = quiet().copy(lists = setOf(Screener.Lists.MOST_SHORTED))
        val squeezeScore = ResearchScore.dayTrading(squeeze, null, maxMentions = 0, maxNews = 0)
        val quietScore = ResearchScore.dayTrading(quietlyShorted, null, maxMentions = 0, maxNews = 0)
        assertTrue(squeezeScore.score > quietScore.score)
        assertTrue(
            squeezeScore.reasons.joinToString(" ").contains("short-squeeze shape")
        )
    }

    @Test fun `social and news attention lift the score but rely on real volume first`() {
        val chatter = ResearchScore.TrendInput("HOT", mentions = 500, newsCount = 20)
        val withChatter = ResearchScore.dayTrading(inPlay(), chatter, maxMentions = 500, maxNews = 20)
        val withoutChatter = ResearchScore.dayTrading(inPlay(), null, maxMentions = 500, maxNews = 20)
        assertTrue(withChatter.score > withoutChatter.score)
    }

    @Test fun `an earnings print today or tomorrow adds to the score and says so`() {
        val soon = ResearchScore.dayTrading(inPlay(), null, maxMentions = 0, maxNews = 0, catalystSoon = true)
        val notSoon = ResearchScore.dayTrading(inPlay(), null, maxMentions = 0, maxNews = 0)
        assertTrue(soon.score > notSoon.score)
        assertTrue(soon.reasons.any { it.contains("earnings today or tomorrow") })
    }

    @Test fun `the reason lines name the session they describe, not always "today"`() {
        // Tj, Round 68: "the market is currently closed and yet the stocks claim to be
        // 'already up today' which makes no sense." That round fixed the price line on the
        // card; these two reason lines kept saying "today" regardless.
        val closed = ResearchScore.dayTrading(
            inPlay(), null, maxMentions = 0, maxNews = 0, sessionWord = "in the last session"
        ).reasons.joinToString(" ")
        assertTrue("the move must name the session: $closed", closed.contains("in the last session"))
        assertFalse("must not claim 'today' over a closed market: $closed", closed.contains("today"))

        val open = ResearchScore.dayTrading(inPlay(), null, maxMentions = 0, maxNews = 0)
            .reasons.joinToString(" ")
        assertTrue("and must still read naturally while open: $open", open.contains("today"))
    }

    // ==================================================== technicals overlay (Round 68)

    private fun tech(
        atr: Double = 0.0,
        vwap: Double = 0.0,
        orHigh: Double = 0.0,
        orLow: Double = 0.0,
        orComplete: Boolean = false,
        atrIntraday: Double = 0.0,
        adr: Double = 0.0,
        prevHigh: Double = 0.0,
        prevLow: Double = 0.0,
        prevClose: Double = 0.0,
        premarketHigh: Double = 0.0,
        sessionHigh: Double = 0.0,
        sessionLow: Double = 0.0,
        live: Boolean = true
    ) = DayTradingTechnicals.DayTechnicals(
        atr14 = atr,
        vwap = vwap,
        openingRangeHigh = orHigh,
        openingRangeLow = orLow,
        openingRangeComplete = orComplete,
        atrIntraday = atrIntraday,
        adr = adr,
        prevHigh = prevHigh,
        prevLow = prevLow,
        prevClose = prevClose,
        premarketHigh = premarketHigh,
        sessionHigh = sessionHigh,
        sessionLow = sessionLow,
        sessionLive = live
    )

    // ============================================== the trade plan (Round 69)
    //
    // THE REGRESSION THESE EXIST FOR, in Tj's own words, 2026-09-11: "the target buy price just
    // matches the current market price. I don't think this is how day traders operate." It did,
    // literally - both level functions assigned `entry = price`. Every test below that compares
    // `entry` against `price` is guarding that specific defect from coming back.

    @Test fun `tradePlan refuses without a price or without any volatility reading`() {
        assertNull(ResearchScore.tradePlan(0.0, tech(atrIntraday = 1.0)))
        assertNull(ResearchScore.tradePlan(100.0, tech()))
    }

    @Test fun `a breakout entry sits ABOVE the current price, at the level plus a break buffer`() {
        // Price 100, holding above VWAP, with the opening-range high at 100.50 overhead.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                orHigh = 100.5, orLow = 99.5,
                sessionHigh = 100.5, sessionLow = 99.0
            )
        )!!
        assertEquals(ResearchScore.SETUP_BREAKOUT, plan.setup)
        // 100.50 + 0.15 (0.15x the 1.00 intraday ATR) - NOT 100.00.
        assertEquals(100.65, plan.entry, 0.001)
        assertTrue("entry must be above the last price, not equal to it", plan.entry > 100.0)
        // Structure alone says 100.35, which is inside one 5-minute bar of noise - widened to
        // the 1.5-ATR floor.
        assertEquals(99.15, plan.stop, 0.001)
        // Clear air above, so the standard 2:1 applies: 100.65 + 2 * 1.50.
        assertEquals(103.65, plan.target, 0.001)
        assertEquals(2.0, plan.rMultiple, 0.001)
        assertTrue(plan.trigger.contains("break above"))
    }

    @Test fun `an extended stock is a pullback to support, BELOW the price - never a chase`() {
        // 10 intraday ATRs above VWAP: buying here is the mistake the research names.
        val plan = ResearchScore.tradePlan(
            110.0,
            tech(
                atrIntraday = 1.0, vwap = 100.0,
                orHigh = 105.0, orLow = 102.0,
                sessionHigh = 110.0, sessionLow = 99.0
            )
        )!!
        assertEquals(ResearchScore.SETUP_PULLBACK, plan.setup)
        assertEquals("buy the retest of the broken opening-range high", 105.0, plan.entry, 0.001)
        assertTrue("a pullback entry must be below the last price", plan.entry < 110.0)
        assertTrue(plan.stop < plan.entry)
        assertTrue(plan.trigger.contains("do not chase"))
        assertTrue(plan.note.contains("BELOW the last price"))
    }

    @Test fun `below VWAP there is no long until it is reclaimed`() {
        val plan = ResearchScore.tradePlan(
            98.0,
            tech(atrIntraday = 1.0, vwap = 100.0, prevHigh = 101.0, prevLow = 97.0, prevClose = 99.0)
        )!!
        assertEquals(ResearchScore.SETUP_RECLAIM, plan.setup)
        assertEquals(100.15, plan.entry, 0.001)
        assertTrue(plan.trigger.contains("reclaims"))
    }

    @Test fun `the target stops at the nearest real resistance rather than drawing through it`() {
        // Prior high 100.80 sits between entry and the 2R target - a wall, so the target goes
        // to the wall and the thinner reward is reported honestly.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                orHigh = 100.2, orLow = 99.5,
                prevHigh = 100.8, prevLow = 98.0, prevClose = 99.5,
                sessionHigh = 100.2, sessionLow = 99.0
            )
        )!!
        // Pivot R1 at 100.867 is the first real obstacle clear of the entry - the target is
        // placed AT it, not at an obedient 2:1 that would sit above it.
        assertEquals(100.867, plan.target, 0.01)
        assertTrue("reward is thinner than 2:1 and must not be inflated", plan.rMultiple < 2.0)
        assertTrue("a thin trade has to say so: ${plan.note}", plan.note.contains("thin trade"))
    }

    @Test fun `a stop is never wider than a same-session trade should carry`() {
        // A pullback to 105 whose next support below is 102 - a 3.15 structural stop, wider
        // than a trade meant to be closed this afternoon should carry.
        val plan = ResearchScore.tradePlan(
            110.0,
            tech(
                atrIntraday = 1.0, vwap = 100.0,
                orHigh = 105.0, orLow = 102.0,
                sessionHigh = 110.0, sessionLow = 99.0
            )
        )!!
        val risk = plan.entry - plan.stop
        assertTrue("risk $risk should be clamped to 2.5 intraday ATRs", risk <= 2.5001)
        assertTrue("the clamp has to be disclosed: ${plan.note}", plan.note.contains("tightened"))
    }

    @Test fun `a stop is never tighter than market noise either`() {
        // The level just broken sits 0.30 below entry - a third of one 5-minute ATR, a stop
        // any ordinary bar would take out with the setup still perfectly intact. It widens to
        // the 1.5-ATR floor instead.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.0,
                orHigh = 100.5, orLow = 99.5,
                sessionHigh = 100.5, sessionLow = 99.0
            )
        )!!
        assertEquals(1.5, plan.entry - plan.stop, 0.001)
    }

    @Test fun `a day that has run most of its range still plans, and says so`() {
        // 87% of a 12-point average day is spent (99.50 to 110.00), which trips the note - but
        // the ceiling (session low + ADR = 111.50) is still a full risk unit above the LAST
        // PRICE, so there is a trade to describe. The margin is deliberately only about half an
        // intraday ATR: this fixture sits just on the plannable side of the ceiling rule below,
        // and the case just past it is the next test.
        val plan = ResearchScore.tradePlan(
            110.0,
            tech(
                atrIntraday = 0.5, vwap = 100.0, adr = 12.0,
                orHigh = 105.0, orLow = 102.0,
                sessionHigh = 110.0, sessionLow = 99.5
            )
        )!!
        assertTrue(plan.note.contains("average daily range"))
        assertTrue("a target must clear the last price", plan.target > 110.0)
    }

    @Test fun `a day whose remaining room sits below the last price produces no plan at all`() {
        // THE SECOND-PASS CEILING FIX, pinned with the fixture that used to pass above.
        //
        // The ceiling (session low 99 + a 10-point ADR = 109) is more than a risk unit above the
        // PULLBACK ENTRY near 105, so measuring its usefulness from the entry - which the first
        // pass did - called it usable and published a target of 109 on a stock trading at 110.
        // The grid then read "buy at 105, sell at 109" directly above a beginner card saying the
        // day was done. A target the price has already passed is not a target, and the honest
        // output is no plan.
        assertNull(
            ResearchScore.tradePlan(
                110.0,
                tech(
                    atrIntraday = 1.0, vwap = 100.0, adr = 10.0,
                    orHigh = 105.0, orLow = 102.0,
                    sessionHigh = 110.0, sessionLow = 99.0
                )
            )
        )
    }

    @Test fun `outside market hours the plan is built from prior-session structure only`() {
        // The same inputs as the reclaim case, but with the session closed. A dead session's
        // VWAP must not produce a "reclaim VWAP" plan for tomorrow, and a completed session's
        // range must not read as "already extended".
        val plan = ResearchScore.tradePlan(
            98.0,
            tech(
                atrIntraday = 1.0, vwap = 100.0, adr = 4.0,
                orHigh = 99.0, orLow = 97.0,
                prevHigh = 101.0, prevLow = 97.0, prevClose = 99.0,
                sessionHigh = 101.0, sessionLow = 97.0,
                live = false
            )
        )!!
        assertEquals(ResearchScore.SETUP_BREAKOUT, plan.setup)
        // The prior session's high, not the stale VWAP and not the stale opening range.
        assertEquals(101.15, plan.entry, 0.001)
        assertTrue(plan.note.contains("Market closed"))
    }

    @Test fun `with no intraday bars yet the daily ATR still sizes a plan`() {
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(atr = 10.0, prevHigh = 101.0, prevLow = 97.0, prevClose = 99.0, live = false)
        )
        assertNotNull("a daily ATR is enough to plan an overnight break", plan)
        assertTrue(plan!!.entry > 100.0)
    }

    @Test fun `floor-trader pivots follow the published formula`() {
        val t = tech(prevHigh = 110.0, prevLow = 90.0, prevClose = 100.0)
        assertEquals(100.0, t.pivot, 0.001)          // (110 + 90 + 100) / 3
        assertEquals(110.0, t.r1, 0.001)             // 2 * 100 - 90
        assertEquals(120.0, t.r2, 0.001)             // 100 + (110 - 90)
        assertEquals(90.0, t.s1, 0.001)              // 2 * 100 - 110
    }

    @Test fun `every plan is internally coherent - stop below entry below target`() {
        // A sweep across the shapes the engine actually sees, guarding the one invariant that
        // must never break no matter which branch produced the numbers.
        val cases = listOf(
            tech(atrIntraday = 0.5, vwap = 99.0, orHigh = 100.5, orLow = 99.5, sessionHigh = 101.0, sessionLow = 98.0),
            tech(atrIntraday = 2.0, vwap = 120.0, sessionHigh = 100.0, sessionLow = 90.0),
            tech(atr = 5.0, prevHigh = 102.0, prevLow = 95.0, prevClose = 99.0, live = false),
            tech(atrIntraday = 0.05, vwap = 99.99, orHigh = 100.01, orLow = 99.98),
            tech(atrIntraday = 1.0, adr = 2.0, vwap = 95.0, orHigh = 99.0, orLow = 96.0, sessionHigh = 100.0, sessionLow = 95.0)
        )
        cases.forEachIndexed { i, t ->
            val plan = ResearchScore.tradePlan(100.0, t) ?: return@forEachIndexed
            assertTrue("case $i: stop must be below entry", plan.stop < plan.entry)
            assertTrue("case $i: target must be above entry", plan.target > plan.entry)
            assertTrue("case $i: stop must be a real price", plan.stop > 0.0)
            assertTrue("case $i: setup must be named", plan.setup.isNotBlank())
            assertTrue("case $i: trigger must say what to do", plan.trigger.isNotBlank())
        }
    }

    @Test fun `withTechnicals leaves the score untouched when nothing was fetched`() {
        val base = ResearchScore.Scored(60, listOf("base reason"), 80)
        val out = ResearchScore.withTechnicals(base, tech(), price = 100.0)
        assertEquals(60, out.score)
        assertEquals(listOf("base reason"), out.reasons)
    }

    @Test fun `withTechnicals rewards trading above VWAP and says so`() {
        val base = ResearchScore.Scored(50, listOf("base reason"), 80)
        val above = ResearchScore.withTechnicals(base, tech(vwap = 95.0), price = 100.0)
        assertTrue(above.score > 50)
        assertTrue(above.reasons.any { it.contains("VWAP") })
        // Below VWAP earns nothing - the same "only adds, never subtracts" rule every other
        // reason line in this list follows.
        val below = ResearchScore.withTechnicals(base, tech(vwap = 105.0), price = 100.0)
        assertEquals(50, below.score)
        assertEquals(listOf("base reason"), below.reasons)
    }

    @Test fun `withTechnicals rewards a completed opening-range breakout and says so`() {
        val base = ResearchScore.Scored(50, listOf("base reason"), 80)
        val broke = ResearchScore.withTechnicals(
            base, tech(orHigh = 98.0, orComplete = true), price = 100.0
        )
        assertTrue(broke.score > 50)
        assertTrue(broke.reasons.any { it.contains("opening-range breakout") })
        // Not yet complete - too early to call it a breakout of a range that has not finished.
        val tooEarly = ResearchScore.withTechnicals(
            base, tech(orHigh = 98.0, orComplete = false), price = 100.0
        )
        assertEquals(50, tooEarly.score)
    }

    @Test fun `withTechnicals combines both bonuses and never exceeds 100`() {
        val base = ResearchScore.Scored(95, listOf("base reason"), 80)
        val out = ResearchScore.withTechnicals(
            base, tech(vwap = 95.0, orHigh = 98.0, orComplete = true), price = 100.0
        )
        assertEquals(100, out.score)
        assertTrue(out.reasons.any { it.contains("VWAP") })
        assertTrue(out.reasons.any { it.contains("opening-range breakout") })
    }

    // ==================================== likelihood x confidence (Round 72)
    //
    // Tj: "make the scores reflect a blend of how likely the stock is to rise... and how
    // confident this prediction is... a score of 100 means... very likely to raise... and...
    // extremely confident." [ResearchScore.dayTradingConfidence] is the new half; [dayTrading]
    // and [withTechnicals] above are the unchanged "likelihood" half.

    private fun confRow(
        rvol: Double = 1.0,
        changePct: Double = 0.0,
        mostShorted: Boolean = false
    ) = ScreenRow(
        symbol = "HOT",
        price = 12.0,
        changePct = changePct,
        volume = (rvol * 4_000_000.0),
        avgVolume3M = 4_000_000.0,
        fiftyTwoWeekLow = 6.0,
        fiftyTwoWeekHigh = 20.0,
        lists = if (mostShorted) setOf(Screener.Lists.MOST_SHORTED) else emptySet()
    )

    @Test fun `no confirming signal at all means zero confidence, not a fabricated baseline`() {
        val quietRow = ScreenRow(
            symbol = "MEH", price = 12.0, changePct = 0.1,
            volume = 4_000_000.0, avgVolume3M = 4_000_000.0,
            fiftyTwoWeekLow = 10.0, fiftyTwoWeekHigh = 14.0
        )
        assertEquals(0, ResearchScore.dayTradingConfidence(quietRow))
    }

    @Test fun `each of the three non-technical checks adds one fifth on its own`() {
        val rvolOnly = confRow(rvol = 3.0)
        assertEquals(20, ResearchScore.dayTradingConfidence(rvolOnly))

        val moveOnly = confRow(changePct = 5.0)
        assertEquals(20, ResearchScore.dayTradingConfidence(moveOnly))

        val nearHighOnly = ScreenRow(
            symbol = "HOT", price = 19.0,
            fiftyTwoWeekLow = 6.0, fiftyTwoWeekHigh = 20.0
        )
        assertEquals(20, ResearchScore.dayTradingConfidence(nearHighOnly))
    }

    @Test fun `squeeze shape and nearing the 52-week high are the SAME check, not two`() {
        // Both conditions true at once must still only contribute one fifth - they are read as
        // "structural strength," a single confirmation, not double-counted.
        val both = ScreenRow(
            symbol = "HOT", price = 19.0, changePct = 5.0,
            volume = 12_000_000.0, avgVolume3M = 4_000_000.0,
            fiftyTwoWeekLow = 6.0, fiftyTwoWeekHigh = 20.0,
            lists = setOf(Screener.Lists.MOST_SHORTED)
        )
        // rvol (20) + move (20) + structure (20, not 40) = 60, no technicals yet.
        assertEquals(60, ResearchScore.dayTradingConfidence(both))
    }

    @Test fun `most-shorted membership ALONE, with no real volume or move, confirms nothing`() {
        // Membership on the screen is a slow-moving structural fact, not today's evidence - see
        // [dayTrading]'s own reasoning on this exact input.
        val membershipOnly = confRow(mostShorted = true)
        assertEquals(0, ResearchScore.dayTradingConfidence(membershipOnly))
    }

    @Test fun `an unfetched technical counts as not confirmed, never as confirmed false`() {
        val row = confRow(rvol = 3.0, changePct = 5.0)
        // Two of three non-technical checks pass; VWAP/opening-range are simply unknown
        // (tech = null), which must score as "not yet confirmed" - not skip the denominator.
        assertEquals(40, ResearchScore.dayTradingConfidence(row, tech = null))
    }

    @Test fun `VWAP and the opening-range breakout each add their own fifth once confirmed`() {
        val row = confRow()
        val aboveVwap = ResearchScore.dayTradingConfidence(row, tech(vwap = 10.0))
        assertEquals(20, aboveVwap)
        val brokeOut = ResearchScore.dayTradingConfidence(row, tech(orHigh = 11.0, orComplete = true))
        assertEquals(20, brokeOut)
        val both = ResearchScore.dayTradingConfidence(
            row, tech(vwap = 10.0, orHigh = 11.0, orComplete = true)
        )
        assertEquals(40, both)
    }

    @Test fun `below VWAP or an incomplete opening range confirms nothing, not a negative`() {
        val row = confRow()
        assertEquals(0, ResearchScore.dayTradingConfidence(row, tech(vwap = 20.0))) // price 12 < vwap 20
        assertEquals(
            0,
            ResearchScore.dayTradingConfidence(row, tech(orHigh = 11.0, orComplete = false))
        )
    }

    @Test fun `all five checks confirming reaches exactly 100, never more`() {
        val row = ScreenRow(
            symbol = "HOT", price = 19.0, changePct = 5.0,
            volume = 12_000_000.0, avgVolume3M = 4_000_000.0,
            fiftyTwoWeekLow = 6.0, fiftyTwoWeekHigh = 20.0,
            lists = setOf(Screener.Lists.MOST_SHORTED)
        )
        val maxed = ResearchScore.dayTradingConfidence(
            row, tech(vwap = 15.0, orHigh = 17.0, orComplete = true)
        )
        assertEquals(100, maxed)
    }

    @Test fun `technicalConfirmationBonus alone matches its half of the full checklist`() {
        assertEquals(0, ResearchScore.technicalConfirmationBonus(12.0, null))
        assertEquals(20, ResearchScore.technicalConfirmationBonus(12.0, tech(vwap = 10.0)))
        assertEquals(
            40,
            ResearchScore.technicalConfirmationBonus(
                12.0, tech(vwap = 10.0, orHigh = 11.0, orComplete = true)
            )
        )
    }

    @Test fun `blendedScore needs both halves high to reach anywhere near 100`() {
        assertEquals(100, ResearchScore.blendedScore(100, 100))
        assertEquals(0, ResearchScore.blendedScore(100, 0))
        assertEquals(0, ResearchScore.blendedScore(0, 100))
        // A strong-looking 90 likelihood at only 40% confidence reads as a modest 36, not a
        // footnote beside an unreduced 90 - the whole point of multiplying instead of averaging.
        assertEquals(36, ResearchScore.blendedScore(90, 40))
    }

    @Test fun `blendedScore never exceeds 100 or goes negative for in-range inputs`() {
        assertEquals(100, ResearchScore.blendedScore(100, 100))
        assertEquals(0, ResearchScore.blendedScore(0, 0))
    }

    // ============================================== the beginner summary (Round 71)
    //
    // Tj: "add a summary of what to do and why that is simple to read for complete beginners
    // who don't understand market technical language (for example, 'buy this at $3.56, and sell
    // at $3.98' or 'too late for this one, don't buy')." [ResearchScore.beginnerSummary] restates
    // [ResearchScore.tradePlan]'s own numbers rather than computing a second opinion - these
    // tests pin down that it can never disagree with the plan it is describing.

    @Test fun `no computed plan means no beginner summary at all`() {
        assertNull(ResearchScore.beginnerSummary("HOT", price = 100.0, entry = 0.0, stop = 0.0, target = 0.0))
        // A partial plan (only an entry, say from a row the technicals sweep hasn't finished
        // enriching) is just as much "nothing to summarise" as a fully blank one.
        assertNull(ResearchScore.beginnerSummary("HOT", price = 100.0, entry = 105.0, stop = 0.0, target = 0.0))
    }

    @Test fun `real levels with no price yet also means no beginner summary`() {
        // Round 71 review fix: `applyDayTradingAnswer` can publish a Claude-imported pick whose
        // levels are real but whose price fill has not resolved yet (`levelsUsable` leaves such
        // a row's levels intact while price <= 0.0). Without this guard every branch below would
        // fall through to a confident "Buy if it climbs..." built from a placeholder zero.
        assertNull(
            ResearchScore.beginnerSummary("HOT", price = 0.0, entry = 105.0, stop = 100.0, target = 115.0)
        )
    }

    @Test fun `a price sitting exactly on the entry says so, instead of guessing a direction`() {
        // Round 71 review fix: guessing "climbs" vs "drops" from price vs entry is right for a
        // breakout and backwards for a pullback - at the exact level, say what is actually true
        // for both instead of a coin flip.
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 105.0, entry = 105.0, stop = 100.0, target = 115.0
        )
        assertNotNull(s)
        assertFalse(s!!.skip)
        assertTrue("must say it's at the price now: ${s.headline}", s.headline.contains("right now"))
        assertFalse("must not guess a direction: ${s.headline}", s.headline.contains("climbs"))
        assertFalse(s.headline.contains("drops"))
    }

    @Test fun `once the price already reached the target, it is too late - not a buy instruction`() {
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 115.0, entry = 105.0, stop = 100.0, target = 110.0
        )
        assertNotNull(s)
        assertTrue("a spent plan must be marked skip", s!!.skip)
        assertTrue("must say it's too late: ${s.headline}", s.headline.contains("Too late", ignoreCase = true))
        assertFalse("must not still tell a beginner to buy: ${s.headline}", s.headline.contains("Buy"))
        assertTrue("must name the symbol in the reasoning: ${s.explanation}", s.explanation.contains("HOT"))
    }

    @Test fun `once the price already broke the stop, the setup already failed - skip it`() {
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 95.0, entry = 105.0, stop = 100.0, target = 115.0
        )
        assertNotNull(s)
        assertTrue(s!!.skip)
        assertTrue("must say to skip: ${s.headline}", s.headline.contains("Skip", ignoreCase = true))
    }

    @Test fun `a plan still ahead of the price tells a beginner to wait for it to climb`() {
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 101.0, entry = 105.0, stop = 100.0, target = 125.0
        )
        assertNotNull(s)
        assertFalse(s!!.skip)
        assertTrue("must state the buy trigger price: ${s.headline}", s.headline.contains("105.00"))
        assertTrue("must state the sell target price: ${s.headline}", s.headline.contains("125.00"))
        assertTrue("must say it climbs: ${s.headline}", s.headline.contains("climbs"))
        assertFalse("no jargon for a beginner: ${s.explanation}", s.explanation.contains("VWAP"))
    }

    @Test fun `a plan below the price tells a beginner to wait for it to drop back`() {
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 110.0, entry = 100.0, stop = 95.0, target = 115.0
        )
        assertNotNull(s)
        assertFalse(s!!.skip)
        assertTrue("must say it drops: ${s.headline}", s.headline.contains("drops"))
        assertTrue(s.explanation.contains("jumped up fast"))
    }

    @Test fun `a thin reward-to-risk plan still gives real numbers, plus a plain-English caution`() {
        // rr = (110-105)/(105-100) = 1.0, at or under the same threshold `planNote` itself
        // warns about - the two must never contradict each other (see the header on
        // [ResearchScore.beginnerSummary]).
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 101.0, entry = 105.0, stop = 100.0, target = 110.0
        )
        assertNotNull(s)
        assertFalse("still a real trade, not a skip", s!!.skip)
        assertTrue("must flag the thin reward in plain words: ${s.explanation}",
            s.explanation.contains("Heads up"))
    }

    @Test fun `a healthy reward-to-risk plan carries no thin-reward caution`() {
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 101.0, entry = 105.0, stop = 100.0, target = 125.0
        )
        assertNotNull(s)
        assertFalse(s!!.explanation.contains("Heads up"))
    }

    @Test fun `every beginner summary always tells the reader where to cut a loss`() {
        val s = ResearchScore.beginnerSummary(
            "HOT", price = 101.0, entry = 105.0, stop = 100.0, target = 125.0
        )
        assertNotNull(s)
        assertTrue("a beginner must be told the stop too: ${s!!.explanation}", s.explanation.contains("100.00"))
    }

    // ================================================================== bridge

    private val realReply = """
Here is my read on today's list. I searched the web for what is actually moving.

```json
{
  "portfolioAppResponse": 1,
  "dayTrading": {
    "asOf": "2026-09-11",
    "picks": [
      {"symbol": "GME", "why": "A short squeeze is forming after a surprise earnings beat and heavy call buying pushed dealers to hedge into the rally.", "risk": "Could reverse hard if the short-covering push stalls before the close.", "conviction": 8}
    ],
    "notes": "The app's volume ratio for GME looks about 15 minutes stale."
  }
}
```
""".trimIndent()

    @Test fun `a real reply parses out of surrounding prose and a fenced block`() {
        val p = DayTradingBridge.parse(realReply)
        assertNull(p.error)
        assertEquals(1, p.picks.size)
        val gme = p.picks[0]
        assertEquals("GME", gme.symbol)
        assertTrue(gme.why.contains("short squeeze"))
        // The risk line lands in `catalyst` - see DayTradingBridge's own header for why.
        assertTrue(gme.catalyst.contains("reverse hard"))
        assertEquals(8, gme.conviction)
        // Claude's conviction is never the app's score.
        assertEquals(0, gme.score)
        assertTrue(p.notes.contains("stale"))
        assertTrue(DayTradingBridge.looksLikeDayTrading(realReply))
    }

    @Test fun `the app's own prompt file is recognised and refused`() {
        val set = ResearchSet(
            dayTrading = listOf(ResearchRow(symbol = "GME", score = 71, reasons = listOf("in play"))),
            generated = System.currentTimeMillis()
        )
        val promptFile = DayTradingBridge.prompt(set, listOf("AAPL"), listOf("NVDA"))
        assertTrue(promptFile.contains(ClaudeBridge.PROMPT_MARK))
        val p = DayTradingBridge.parse(promptFile)
        assertNotNull(p.error)
        assertTrue(p.error!!.contains("prompt file"))
        assertTrue(p.isEmpty)
    }

    @Test fun `the prompt file carries the live data and computed risk levels Claude needs`() {
        val set = ResearchSet(
            dayTrading = listOf(
                ResearchRow(
                    symbol = "GME", name = "GameStop", price = 22.5, changePct = 12.0, score = 88,
                    reasons = listOf("Trading at 6.0x its normal volume today"),
                    mentions = 340, newsCount = 9,
                    entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5,
                    atr = 1.0, vwap = 21.8, openingRangeHigh = 22.0, openingRangeLow = 21.2
                )
            ),
            generated = System.currentTimeMillis(),
            sources = "test"
        )
        val text = DayTradingBridge.prompt(set, listOf("AAPL"), listOf("TSLA"))
        listOf("GME", "GameStop", "22.5", "340", "AAPL", "TSLA", "21", "25.5")
            .forEach { assertTrue("prompt file is missing $it", text.contains(it)) }
        // The $2 floor Tj asked for is stated as a fact about the data, not left implicit.
        assertTrue(text.contains("\"minSharePrice\": 2"))
        // The real technicals behind the levels travel with the bundle too (Round 68), so
        // Claude can reference them rather than describe the setup in the abstract.
        listOf("\"atr14\": 1", "\"vwap\": 21.8", "\"openingRangeHigh\": 22", "\"openingRangeLow\": 21.2")
            .forEach { assertTrue("prompt file is missing $it", text.contains(it)) }
    }

    @Test fun `rows with only a ticker are dropped rather than blanking a good app row`() {
        val thin = """{"dayTrading":{"picks":[{"symbol":"AAA"},{"symbol":"BBB","why":"Real reason."}]}}"""
        val p = DayTradingBridge.parse(thin)
        assertEquals(1, p.picks.size)
        assertEquals("BBB", p.picks[0].symbol)
    }

    @Test fun `Claude's list REPLACES the section - a row it left out is gone`() {
        // ROUND 69, and the reverse of what this file asserted for two rounds. Tj: "the Claude
        // prompt can change the stocks in the list if it finds better ones."
        val app = listOf(
            ResearchRow(
                symbol = "GME", price = 22.5, score = 88, reasons = listOf("6x normal volume"),
                entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5
            ),
            ResearchRow(symbol = "MEH", price = 5.0, score = 40)
        )
        val claude = listOf(
            ResearchRow(symbol = "GME", why = "Short squeeze in progress.", catalyst = "Could fade fast.", conviction = 8),
            ResearchRow(symbol = "NEW", why = "The app missed this one.", catalyst = "Earnings tonight.", conviction = 6)
        )
        val merged = DayTradingBridge.merge(app, claude)
        assertEquals(2, merged.size)
        assertEquals("Claude's order is the list's order", listOf("GME", "NEW"), merged.map { it.symbol })
        assertTrue("a name Claude dropped must be gone", merged.none { it.symbol == "MEH" })

        val gme = merged.first { it.symbol == "GME" }
        assertEquals("Short squeeze in progress.", gme.why)
        assertEquals(8, gme.conviction)
        // What the APP measured still survives - the score, the reason lines, the price.
        assertEquals(88, gme.score)
        assertEquals(listOf("6x normal volume"), gme.reasons)
        assertEquals(22.5, gme.price, 0.001)
        // Claude sent no levels, so the app's own plan is untouched.
        assertEquals(22.5, gme.entryPrice, 0.001)
        assertFalse(gme.planByClaude)
    }

    @Test fun `an empty answer never wipes the list`() {
        val app = listOf(ResearchRow(symbol = "GME", price = 22.5, score = 88))
        assertEquals(app, DayTradingBridge.merge(app, emptyList()))
    }

    @Test fun `Claude's own entry, stop and target are accepted and labelled as Claude's`() {
        val app = listOf(
            ResearchRow(
                symbol = "GME", price = 22.5, score = 88,
                entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5, setup = "Breakout"
            )
        )
        val claude = DayTradingBridge.parse(
            """{"dayTrading":{"picks":[{"symbol":"GME","why":"Squeeze.","setup":"Gap and go",
               "entry":23.10,"stop":22.40,"target":25.00,
               "trigger":"Buy the break of the premarket high at 23.10."}]}}"""
        ).picks
        val gme = DayTradingBridge.merge(app, claude).first()
        assertEquals(23.10, gme.entryPrice, 0.001)
        assertEquals(22.40, gme.stopPrice, 0.001)
        assertEquals(25.00, gme.targetPrice, 0.001)
        assertEquals("Gap and go", gme.setup)
        assertTrue("the card must be able to say whose plan this is", gme.planByClaude)
        assertEquals("the app's own score is still not Claude's to set", 88, gme.score)
    }

    @Test fun `a mangled level set is refused and the app's own plan survives`() {
        val app = listOf(
            ResearchRow(
                symbol = "GME", price = 22.5, score = 88,
                entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5
            )
        )
        // A decimal point in the wrong place: a $2.31 entry on a $22.50 stock. Structurally a
        // valid trade, which is exactly why the price check has to exist as well.
        val slipped = DayTradingBridge.parse(
            """{"dayTrading":{"picks":[{"symbol":"GME","why":"Squeeze.","entry":2.31,"stop":2.24,"target":2.50}]}}"""
        ).picks
        val gme = DayTradingBridge.merge(app, slipped).first()
        assertEquals("the app's own entry must survive a bad one", 22.5, gme.entryPrice, 0.001)
        assertFalse(gme.planByClaude)
        assertEquals("the explanation is still taken", "Squeeze.", gme.why)
    }

    @Test fun `a level set that is not a trade at all is refused at parse time`() {
        // Target below the stop - not a long, not a short, not anything.
        val p = DayTradingBridge.parse(
            """{"dayTrading":{"picks":[{"symbol":"GME","why":"Real reason.","entry":22.5,"stop":23.0,"target":21.0}]}}"""
        )
        assertEquals(1, p.picks.size)
        assertEquals(0.0, p.picks[0].entryPrice, 0.0)
        assertFalse(p.picks[0].planByClaude)
    }

    @Test fun `levelsUsable checks the shape and the distance from the real price`() {
        assertTrue(DayTradingBridge.levelsUsable(22.5, entry = 23.0, stop = 22.0, target = 24.0))
        assertFalse("stop above entry", DayTradingBridge.levelsUsable(22.5, 23.0, 23.5, 24.0))
        assertFalse("target below entry", DayTradingBridge.levelsUsable(22.5, 23.0, 22.0, 22.9))
        assertFalse("a decimal slip", DayTradingBridge.levelsUsable(22.5, 2.3, 2.2, 2.4))
        assertFalse("a stale price from another era", DayTradingBridge.levelsUsable(22.5, 90.0, 88.0, 95.0))
        assertTrue("no app price to check against yet", DayTradingBridge.levelsUsable(0.0, 23.0, 22.0, 24.0))
    }

    @Test fun `a research reply is not mistaken for a day-trading reply, and vice versa`() {
        val research = """{"portfolioAppResponse":1,"research":{"best":[{"symbol":"GOOD","why":"Cheap."}]}}"""
        assertFalse(DayTradingBridge.looksLikeDayTrading(research))
        val advice = """{"portfolioAppResponse":1,"advice":{"summary":"Concentrated.","stocks":[]}}"""
        assertFalse(DayTradingBridge.looksLikeDayTrading(advice))
        // ...and a day-trading reply carries none of the advice keys, so reaching the advice
        // parser with one is exactly the routing bug this guards against.
        assertNotNull(ClaudeBridge.parse(realReply).error)
    }
}
