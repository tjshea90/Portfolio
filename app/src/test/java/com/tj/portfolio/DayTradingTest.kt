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
 * answers), the risk-plan arithmetic in [ResearchScore.tradeLevels] that stands in for a
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

    // ================================================================== trade levels

    @Test fun `tradeLevels refuses a priceless row rather than inventing a plan`() {
        assertNull(ResearchScore.tradeLevels(ScreenRow(symbol = "X", price = 0.0)))
    }

    @Test fun `entry is simply today's price`() {
        val levels = ResearchScore.tradeLevels(ScreenRow(symbol = "X", price = 42.0))
        assertNotNull(levels)
        assertEquals(42.0, levels!!.entry, 0.001)
    }

    @Test fun `stop sits below entry and target above it, at a 2 to 1 reward-to-risk`() {
        val levels = ResearchScore.tradeLevels(
            ScreenRow(symbol = "X", price = 100.0, changePct = 4.0)
        )!!
        assertTrue(levels.stop < levels.entry)
        assertTrue(levels.target > levels.entry)
        val risk = levels.entry - levels.stop
        val reward = levels.target - levels.entry
        assertEquals("reward should be exactly 2x the risk", reward, risk * 2.0, 0.01)
    }

    @Test fun `daily volatility is clamped so an illiquid or wild row cannot get an absurd stop`() {
        // A huge 52-week range and a huge move today - would blow past 15% unclamped.
        val wild = ResearchScore.tradeLevels(
            ScreenRow(
                symbol = "WILD", price = 10.0, changePct = 90.0,
                fiftyTwoWeekHigh = 500.0, fiftyTwoWeekLow = 1.0
            )
        )!!
        val stopPct = (wild.entry - wild.stop) / wild.entry * 100.0
        assertTrue("stop distance should be clamped, was $stopPct%", stopPct <= 9.01)

        // No move today and no usable 52-week range - would fall to zero unclamped.
        val flat = ResearchScore.tradeLevels(ScreenRow(symbol = "FLAT", price = 10.0))!!
        val flatStopPct = (flat.entry - flat.stop) / flat.entry * 100.0
        assertTrue("stop distance should have a floor, was $flatStopPct%", flatStopPct >= 0.89)
    }

    // ==================================================== technicals overlay (Round 68)

    private fun tech(
        atr: Double = 0.0,
        vwap: Double = 0.0,
        orHigh: Double = 0.0,
        orLow: Double = 0.0,
        orComplete: Boolean = false
    ) = DayTradingTechnicals.DayTechnicals(atr, vwap, orHigh, orLow, orComplete)

    @Test fun `upgradeLevels refuses without a real ATR rather than guessing`() {
        assertNull(ResearchScore.upgradeLevels(100.0, tech(atr = 0.0)))
        assertNull(ResearchScore.upgradeLevels(0.0, tech(atr = 2.0)))
    }

    @Test fun `upgradeLevels sets entry to price, stop 1-5x ATR below it, target at 2-to-1`() {
        val levels = ResearchScore.upgradeLevels(100.0, tech(atr = 2.0))!!
        assertEquals(100.0, levels.entry, 0.001)
        // stop = 100 - (2.0 * 1.5) = 97.0
        assertEquals(97.0, levels.stop, 0.001)
        // target = 100 + (100 - 97) * 2 = 106.0
        assertEquals(106.0, levels.target, 0.001)
    }

    @Test fun `upgradeLevels floors the stop so an extreme ATR cannot erase the trade`() {
        // ATR of 40 on a $100 stock would place a naive stop at 100 - 60 = 40, more than half
        // the entry price away - floored at 50.0 instead.
        val levels = ResearchScore.upgradeLevels(100.0, tech(atr = 40.0))!!
        assertEquals(50.0, levels.stop, 0.001)
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
                    entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5
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
