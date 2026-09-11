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
        // Structure says 100.35; that is inside the minimum 0.75-ATR risk, so the stop widens.
        assertEquals(99.90, plan.stop, 0.001)
        // Clear air above, so the standard 2:1 applies: 100.65 + 2 * 0.75.
        assertEquals(102.15, plan.target, 0.001)
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
        assertEquals(100.8, plan.target, 0.001)
        assertTrue("reward is thinner than 2:1 and must not be inflated", plan.rMultiple < 2.0)
    }

    @Test fun `a stop is never wider than a same-session trade should carry`() {
        // Structure 8 ATRs below entry would be a swing-trade stop, not a day-trade one.
        val plan = ResearchScore.tradePlan(
            100.0,
            tech(
                atrIntraday = 1.0, vwap = 99.5,
                orHigh = 100.5, orLow = 92.0,
                sessionHigh = 100.5, sessionLow = 92.0
            )
        )!!
        val risk = plan.entry - plan.stop
        assertTrue("risk $risk should be clamped to 2.5 intraday ATRs", risk <= 2.5001)
        assertTrue(plan.note.contains("tightened"))
    }

    @Test fun `a day that has already run its whole range says so`() {
        val plan = ResearchScore.tradePlan(
            110.0,
            tech(
                atrIntraday = 1.0, vwap = 100.0, adr = 10.0,
                orHigh = 105.0, orLow = 102.0,
                sessionHigh = 110.0, sessionLow = 99.0
            )
        )!!
        assertTrue(plan.note.contains("average daily range"))
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

    @Test fun `merging keeps the app score and computed levels, and adds Claude's explanation`() {
        val app = listOf(
            ResearchRow(
                symbol = "GME", price = 22.5, score = 88,
                entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5
            ),
            ResearchRow(symbol = "MEH", price = 5.0, score = 40)
        )
        val claude = listOf(
            ResearchRow(symbol = "GME", why = "Short squeeze in progress.", catalyst = "Could fade fast.", conviction = 8),
            ResearchRow(symbol = "NEW", why = "The app missed this one.", catalyst = "Earnings tonight.", conviction = 6)
        )
        val merged = DayTradingBridge.merge(app, claude)
        assertEquals(3, merged.size)
        val gme = merged.first { it.symbol == "GME" }
        assertEquals("Short squeeze in progress.", gme.why)
        assertEquals("Could fade fast.", gme.catalyst)
        assertEquals(8, gme.conviction)
        // The app's own arithmetic and risk plan survive untouched.
        assertEquals(88, gme.score)
        assertEquals(22.5, gme.entryPrice, 0.001)
        assertEquals(21.0, gme.stopPrice, 0.001)
        assertEquals(25.5, gme.targetPrice, 0.001)
        // A pick the app never had is appended, not dropped, with no levels yet.
        val added = merged.first { it.symbol == "NEW" }
        assertEquals(0.0, added.entryPrice, 0.0)
        // A pick Claude ignored is untouched.
        assertEquals("", merged.first { it.symbol == "MEH" }.why)
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
