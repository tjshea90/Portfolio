package com.tj.portfolio

import com.tj.portfolio.data.Consensus2
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.net.Research
import com.tj.portfolio.net.ResearchBridge
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.net.Screener
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
 * THE RESEARCH TAB, PINNED DOWN (Round 54).
 *
 * Three things are tested here and each one is a way the feature can be quietly wrong rather
 * than visibly broken:
 *
 *  1. **The parsers**, against RESPONSES CAPTURED FROM THE LIVE PROVIDERS on 5 September 2026
 *     and checked into `src/test/resources`. A screener response that stops parsing is not an
 *     exception - it is an empty section, which looks exactly like a quiet market day.
 *  2. **The scorers**, which decide what TJ is shown as a good buy. A sign flip here produces
 *     confident, plausible, wrong output; nothing else in the app would notice.
 *  3. **The bridge**, because the offline path's whole promise is that Claude's reply file
 *     imports cleanly, and because a research reply must not be mistaken for an advice reply
 *     (or vice versa) now that one file chooser accepts both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    // ================================================================== parsers

    @Test
    fun `screener response parses into fully populated rows`() {
        val rows = Screener.parse(
            Screener.Lists.DAY_LOSERS,
            res("yahoo_screener_day_losers.json")
        )
        assertEquals(8, rows.size)

        val gwre = rows.first { it.symbol == "GWRE" }
        assertEquals("Guidewire Software, Inc.", gwre.name)
        assertEquals(162.42, gwre.price, 0.01)
        assertEquals(-19.93, gwre.changePct, 0.01)
        assertEquals(1.49, gwre.epsTtm, 0.001)
        assertEquals(5.33909, gwre.epsForward, 0.001)
        assertEquals(102.3, gwre.fiftyTwoWeekLow, 0.01)
        assertEquals(264.13, gwre.fiftyTwoWeekHigh, 0.01)
        assertTrue(gwre.marketCap > 1e10)
        assertTrue(Screener.Lists.DAY_LOSERS in gwre.lists)

        // Every row must carry the fields the scorers actually read, or the scorers silently
        // fall through to zero and the ranking becomes "whatever Yahoo returned first".
        rows.forEach {
            assertTrue("no price for ${it.symbol}", it.price > 0)
            assertTrue("no 50-day for ${it.symbol}", it.fiftyDayAvg > 0)
            assertTrue("no 200-day for ${it.symbol}", it.twoHundredDayAvg > 0)
        }
    }

    @Test
    fun `earnings timestamps are converted from seconds to milliseconds`() {
        val rows = Screener.parse(Screener.Lists.DAY_LOSERS, res("yahoo_screener_day_losers.json"))
        val dated = rows.filter { it.earningsAt > 0 }
        assertTrue("fixture has no earnings dates to check", dated.isNotEmpty())
        // Yahoo publishes seconds. A row still in seconds lands in 1970 in millisecond terms;
        // one already in milliseconds is somewhere sane this decade.
        dated.forEach {
            assertTrue(
                "${it.symbol} earnings date looks like raw seconds: ${it.earningsAt}",
                it.earningsAt > 1_600_000_000_000L
            )
        }
    }

    @Test
    fun `trending list drops indices and crypto pairs`() {
        val syms = Screener.parseTrending(res("yahoo_trending_us.json"))
        assertTrue("NFLX" in syms)
        assertTrue("FICO" in syms)
        // The live list mixes these in and none of them is a stock this app can research.
        assertFalse(syms.any { it.startsWith("^") })
        assertFalse(syms.any { it.contains("-") })
    }

    @Test
    fun `nasdaq consensus parses counts and target`() {
        val c = Research.parseConsensus(res("nasdaq_consensus_AAPL.json"))
        assertNotNull(c)
        assertEquals(16, c!!.buy)
        assertEquals(9, c.hold)
        assertEquals(4, c.sell)
        assertEquals(337.55, c.target, 0.01)
        assertEquals(29, c.total)
        assertEquals("Buy", c.label())
    }

    @Test
    fun `consensus upside is measured against the live price`() {
        val c = Consensus2(buy = 10, hold = 2, sell = 0, target = 120.0)
        assertEquals(20.0, c.upsidePct(100.0), 0.001)
        assertEquals(-20.0, c.upsidePct(150.0), 0.001)
        // No target and no price must produce NaN, not a confident zero.
        assertTrue(Consensus2(target = 0.0).upsidePct(100.0).isNaN())
    }

    @Test
    fun `a response with no results parses to nothing rather than throwing`() {
        assertEquals(0, Screener.parse("x", """{"finance":{"result":[]}}""").size)
        assertEquals(0, Screener.parseTrending("""{"finance":{"error":"nope"}}""").size)
        assertNull(Research.parseConsensus("""{"data":null}"""))
    }

    // ================================================================== scorers

    /** A large, cheap, growing, uptrending company - the shape the BEST list is looking for. */
    private fun goodStock() = ScreenRow(
        symbol = "GOOD",
        name = "Good Industries",
        price = 100.0,
        changePct = 1.0,
        marketCap = 8.0e10,
        avgVolume3M = 4_000_000.0,
        forwardPe = 14.0,
        priceToBook = 3.0,
        epsTtm = 5.0,
        epsForward = 7.0,
        fiftyDayAvg = 95.0,
        twoHundredDayAvg = 88.0,
        fiftyTwoWeekHigh = 110.0,
        fiftyTwoWeekLow = 70.0,
        fiftyTwoWeekChangePct = 25.0,
        lists = setOf(Screener.Lists.UNDERVALUED_GROWTH)
    )

    /** Loss-making, shrinking, below both averages, near the low, heavily shorted, tiny. */
    private fun badStock() = ScreenRow(
        symbol = "BAD",
        name = "Bad Corp",
        price = 2.0,
        changePct = -6.0,
        marketCap = 2.0e8,
        avgVolume3M = 900_000.0,
        forwardPe = 0.0,
        priceToBook = -1.5,
        epsTtm = -1.2,
        epsForward = -0.9,
        fiftyDayAvg = 3.0,
        twoHundredDayAvg = 6.0,
        fiftyTwoWeekHigh = 12.0,
        fiftyTwoWeekLow = 1.9,
        fiftyTwoWeekChangePct = -78.0,
        lists = setOf(Screener.Lists.MOST_SHORTED, Screener.Lists.DAY_LOSERS)
    )

    @Test
    fun `the good stock outscores the bad one on the best list`() {
        val good = ResearchScore.best(goodStock())
        val bad = ResearchScore.best(badStock())
        assertTrue("good=${good.score} bad=${bad.score}", good.score > bad.score + 30)
    }

    @Test
    fun `every score carries the reasons behind it`() {
        assertTrue(ResearchScore.best(goodStock()).reasons.isNotEmpty())
        // The specific claims the UI will print have to be real, not generic filler.
        val why = ResearchScore.best(goodStock()).reasons.joinToString(" ").lowercase()
        assertTrue("the reasons should name the earnings growth: $why", why.contains("earnings"))
    }

    /**
     * ROUND 66 AUDIT (R3 and R4). Two reason lines that asserted things the arithmetic had
     * refused to credit - the class of defect this file's header exists to prevent, because
     * a plausible wrong sentence is worse than no sentence.
     */
    @Test fun `a low multiple on shrinking earnings is not called cheap for that growth`() {
        val shrinking = goodStock().copy(
            epsTtm = 5.0, epsForward = 4.0,     // earnings falling 20%
            forwardPe = 11.0
        )
        val why = ResearchScore.best(shrinking).reasons.joinToString(" ")
        assertTrue("the P/E line should still appear: $why", why.contains("Forward P/E"))
        assertFalse(
            "the growth term scored zero, so nothing may claim growth: $why",
            why.contains("for that growth")
        )
        assertTrue("and it should say so plainly: $why", why.contains("not growing"))
        // A company that IS growing keeps the original wording.
        val growing = goodStock().copy(epsTtm = 4.0, epsForward = 6.0, forwardPe = 11.0)
        assertTrue(ResearchScore.best(growing).reasons.joinToString(" ").contains("for that growth"))
    }

    @Test fun `a bearish screen is never listed among the reasons to buy`() {
        val onlyBearish = goodStock().copy(
            lists = setOf(Screener.Lists.DAY_LOSERS, Screener.Lists.MOST_SHORTED)
        )
        val why = ResearchScore.best(onlyBearish).reasons.joinToString(" ").lowercase()
        assertFalse(
            "heavy short interest read as a reason to buy: $why",
            why.contains("most shorted")
        )
        assertFalse("nor a day-losers listing: $why", why.contains("day losers"))
        // A screen that actually scores is still named.
        val scoring = goodStock().copy(lists = setOf(Screener.Lists.UNDERVALUED_GROWTH))
        assertTrue(
            ResearchScore.best(scoring).reasons.joinToString(" ").lowercase()
                .contains("undervalued growth")
        )
    }

    @Test
    fun `a missing field scores zero for its component instead of being guessed`() {
        // Same company, but Yahoo reported nothing but the price. It must not out-rank a
        // fully-reported peer by defaulting anything favourably.
        val blank = ScreenRow(symbol = "THIN", price = 40.0)
        val scored = ResearchScore.best(blank)
        assertEquals(0, scored.score)
        assertTrue("confidence should reflect the missing fields", scored.confidence <= 20)
    }

    @Test
    fun `growth is only claimed when both earnings figures are real`() {
        // Trailing loss to forward profit is a turnaround, not a growth percentage - dividing
        // by a negative would have produced a large NEGATIVE growth for an improving company.
        val turn = goodStock().copy(epsTtm = -2.0, epsForward = 1.0)
        assertTrue(turn.epsGrowth.isNaN())
        val why = ResearchScore.best(turn).reasons.joinToString(" ").lowercase()
        assertTrue(why.contains("turning profitable"))
    }

    @Test
    fun `analyst coverage moves a score in the right direction`() {
        val base = ResearchScore.Scored(60, listOf("base"), 100)
        val strongBuy = Consensus2(buy = 20, hold = 2, sell = 0, target = 150.0)
        val strongSell = Consensus2(buy = 0, hold = 3, sell = 12, target = 60.0)

        // A strong buy with 50% upside raises it; a strong sell with 40% downside lowers it.
        assertTrue(ResearchScore.withAnalyst(base, strongBuy, 100.0).score > 60)
        assertTrue(ResearchScore.withAnalyst(base, strongSell, 100.0).score < 60)
    }

    @Test
    fun `no analyst coverage leaves the score exactly as it was`() {
        val base = ResearchScore.Scored(72, listOf("base"), 90)
        assertEquals(72, ResearchScore.withAnalyst(base, null, 100.0).score)
        assertEquals(72, ResearchScore.withAnalyst(base, Consensus2(), 100.0).score)
    }

    @Test
    fun `trending blends social volume with news volume`() {
        val socialOnly = ResearchScore.TrendInput("A", mentions = 200, newsCount = 0)
        val newsOnly = ResearchScore.TrendInput("B", mentions = 0, newsCount = 12)
        val both = ResearchScore.TrendInput("C", mentions = 200, newsCount = 12)
        val a = ResearchScore.trending(socialOnly, 200, 12).score
        val b = ResearchScore.trending(newsOnly, 200, 12).score
        val c = ResearchScore.trending(both, 200, 12).score
        assertTrue("a name that is loud on BOTH must beat either alone", c > a && c > b)
        assertTrue("social carries more weight than headline count", a > b)
    }

    @Test
    fun `trending scores are relative to the busiest name in the same pass`() {
        // The same 40 mentions is a big deal on a quiet day and nothing on a loud one. If it
        // scored the same in both, the section would be near-empty every quiet day.
        val t = ResearchScore.TrendInput("X", mentions = 40)
        val quietDay = ResearchScore.trending(t, maxMentions = 50, maxNews = 0).score
        val loudDay = ResearchScore.trending(t, maxMentions = 800, maxNews = 0).score
        assertTrue(quietDay > loudDay)
    }

    // ---- THE SHORT-VEHICLE TESTS WENT WITH THE FEATURE IN ROUND 66.
    //
    // They covered `ShortVehicle`, which resolved "what can I buy that shorts this company"
    // live from Yahoo's fund search. The Worst section it served is gone - measured against
    // Yahoo in September 2026, 16 of 20 high-momentum mega-caps have a US single-stock
    // inverse fund and 2 of 40 beaten-down names do, one of those only on foreign listings -
    // so the matcher, its two searches per visible row, and these tests are all deleted
    // rather than left maintaining something nothing calls.

    // ================================================================== bridge

    private val realReply = """
Here is my read on your lists. I searched the web for the latest on each name.

```json
{
  "portfolioAppResponse": 1,
  "research": {
    "asOf": "2026-09-05",
    "trending": [
      {"symbol": "MU", "why": "Micron guided Q4 well above consensus on HBM demand and the stock gapped up, which is what the Reddit volume is reacting to."}
    ],
    "best": [
      {"symbol": "GOOD", "why": "Steady operator trading at 14x forward with mid-teens earnings growth.", "catalyst": "Q3 earnings 12 Oct", "target": "\${'$'}125 on 17x forward", "conviction": 8}
    ],
    "etfs": [
      {"symbol": "VOO", "why": "The cheapest broad US index fund at 3bp, and the default first holding for most people.", "category": "broad US equity index", "conviction": 9}
    ],
    "notes": "The app's price for VOO is about 40 minutes stale."
  }
}
```
""".trimIndent()

    @Test
    fun `a real reply parses out of surrounding prose and a fenced block`() {
        val p = ResearchBridge.parse(realReply)
        assertNull(p.error)
        assertEquals(1, p.trending.size)
        assertEquals("MU", p.trending[0].symbol)
        assertTrue(p.best[0].why.contains("14x forward"))
        assertEquals("VOO", p.etfs[0].symbol)
        // ROUND 66 AUDIT (R1): conviction is its own field now, never the app's score.
        assertEquals("the app scored nothing here", 0, p.etfs[0].score)
        assertEquals(9, p.etfs[0].conviction)
        assertTrue("the category should land in the catalyst line",
            p.etfs[0].catalyst.contains("broad US equity index"))
        assertTrue(p.notes.contains("stale"))
        assertTrue(ResearchBridge.looksLikeResearch(realReply))
    }

    @Test
    fun `the app's own prompt file is recognised and refused`() {
        val set = ResearchSet(
            best = listOf(ResearchRow(symbol = "GOOD", score = 71, reasons = listOf("cheap"))),
            generated = System.currentTimeMillis()
        )
        val promptFile = ResearchBridge.prompt(set, listOf("AAPL"), listOf("NVDA"))
        // This is the v5.2 bug in its new home: the prompt sits in Downloads next to the
        // answer and is the easiest file to pick by mistake.
        assertTrue(promptFile.contains(ClaudeBridge.PROMPT_MARK))
        val p = ResearchBridge.parse(promptFile)
        assertNotNull(p.error)
        assertTrue(p.error!!.contains("prompt file"))
        assertTrue(p.isEmpty)
    }

    @Test
    fun `the prompt file carries the live data Claude needs`() {
        val set = ResearchSet(
            trending = listOf(
                ResearchRow(symbol = "MU", name = "Micron", price = 210.5, mentions = 248, score = 88)
            ),
            best = listOf(ResearchRow(symbol = "GOOD", price = 100.0, score = 71,
                reasons = listOf("Forward P/E 14.00 - cheap for that growth"))),
            etfs = listOf(ResearchRow(symbol = "VOO", price = 500.0, score = 83)),
            generated = System.currentTimeMillis(),
            sources = "test"
        )
        val text = ResearchBridge.prompt(set, listOf("AAPL"), listOf("TSLA"))
        // The whole promise of the offline path is "no explanation from me", so the numbers
        // on screen have to be inside the file.
        listOf("MU", "Micron", "210.5", "248", "GOOD", "VOO", "AAPL", "TSLA")
            .forEach { assertTrue("prompt file is missing $it", text.contains(it)) }
        // The app's reason lines travel with the rows.
        assertTrue(text.contains("cheap for that growth"))
        // NOTE, found by this test: Android's org.json escapes "/" as "\/", so a reason line
        // reading "Forward P/E 14.00" is written into the bundle as "Forward P\/E 14.00".
        // That is legal JSON and every parser reads it back as the slash, so it is left
        // alone rather than post-processed - but assert the shape so a future change to the
        // encoder does not silently alter what Claude is handed.
        assertTrue(
            text.contains("Forward P\\/E 14.00") || text.contains("Forward P/E 14.00")
        )
    }

    @Test
    fun `an advice reply is not mistaken for a research reply`() {
        val advice = """{"portfolioAppResponse":1,"advice":{"summary":"Concentrated in tech.","stocks":[{"symbol":"NVDA","rating":7,"action":"HOLD","reasoning":"Fine."}]}}"""
        assertFalse(ResearchBridge.looksLikeResearch(advice))
        // ...and the advice parser still works now that findObject takes a key set.
        val r = ClaudeBridge.parse(advice)
        assertNull(r.error)
        assertEquals(1, r.advice!!.stocks.size)
    }

    @Test
    fun `a research reply is routed away from the advice parser`() {
        assertTrue(ResearchBridge.looksLikeResearch(realReply))
        // Reaching ClaudeBridge with a research file is the routing bug this guards against:
        // it carries none of the advice keys, so it would report "nothing usable".
        assertNotNull(ClaudeBridge.parse(realReply).error)
    }

    @Test
    fun `rows with only a ticker are dropped rather than blanking a good app row`() {
        val thin = """{"research":{"best":[{"symbol":"AAA"},{"symbol":"BBB","why":"Real reason."}]}}"""
        val p = ResearchBridge.parse(thin)
        assertEquals(1, p.best.size)
        assertEquals("BBB", p.best[0].symbol)
    }

    @Test
    fun `merging keeps the app score and adds Claude's explanation`() {
        val app = listOf(
            ResearchRow(symbol = "GOOD", price = 100.0, score = 71, reasons = listOf("cheap")),
            ResearchRow(symbol = "MEH", price = 20.0, score = 55, reasons = listOf("ok"))
        )
        val claude = listOf(
            ResearchRow(symbol = "GOOD", why = "Great business.", score = 100),
            ResearchRow(symbol = "NEW", why = "The app missed this one.", score = 90)
        )
        val merged = ResearchBridge.merge(app, claude)
        assertEquals(3, merged.size)
        val good = merged.first { it.symbol == "GOOD" }
        assertEquals("Great business.", good.why)
        // The app's own arithmetic survives - Claude's conviction never overwrites it.
        assertEquals(71, good.score)
        assertEquals(listOf("cheap"), good.reasons)
        // A row the app never had is appended, not dropped.
        assertEquals("NEW", merged.last().symbol)
        // A row Claude ignored is untouched.
        assertEquals("", merged.first { it.symbol == "MEH" }.why)
    }

    // ============================================================== round trip

    @Test
    fun `a research set survives the cache round trip byte for byte`() {
        val original = ResearchSet(
            trending = listOf(
                ResearchRow(
                    symbol = "MU", name = "Micron Technology", price = 210.5, changePct = 4.2,
                    score = 88, reasons = listOf("248 mentions", "12 stories today"),
                    why = "HBM guidance.", mentions = 248, mentionDelta = 121, rankDelta = 3,
                    sentiment = "Bullish", newsCount = 12, headline = "Micron soars",
                    headlineUrl = "https://example.com/a", headlineSource = "Reuters",
                    onYahooTrending = true
                )
            ),
            best = listOf(
                ResearchRow(
                    symbol = "GOOD", price = 100.0, score = 71, reasons = listOf("cheap"),
                    consensus = Consensus2(16, 9, 4, 337.55), catalyst = "Earnings in 9 days"
                )
            ),
            etfs = listOf(ResearchRow(symbol = "VOO", price = 500.0, score = 83)),
            generated = 1_757_000_000_000L,
            sources = "test sources",
            warnings = listOf("one feed was quiet"),
            explained = 1_757_000_100_000L,
            explainedBy = "API",
            notes = "a note"
        )
        val back = ResearchSet.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.toJson().toString(), back.toJson().toString())
        assertEquals(337.55, back.best[0].consensus!!.target, 0.001)
        assertEquals("VOO", back.etfs[0].symbol)
        assertEquals(listOf("one feed was quiet"), back.warnings)
    }

    /**
     * Round 67's addition: a day-trading row's entry/stop/target, and the section's own
     * dtExplained/dtExplainedBy/dtNotes - kept separate from explained/explainedBy/notes above,
     * so both halves have to round-trip WITHOUT bleeding into each other.
     */
    @Test
    fun `a day-trading row and its own explain state survive the cache round trip`() {
        val original = ResearchSet(
            dayTrading = listOf(
                ResearchRow(
                    symbol = "GME", price = 22.5, changePct = 12.0, score = 88,
                    reasons = listOf("Trading at 6.0x its normal volume today"),
                    catalyst = "Could fade fast if the squeeze stalls.",
                    entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5
                )
            ),
            generated = 1_757_000_000_000L,
            explained = 1_757_000_100_000L,
            explainedBy = "API",
            notes = "the research note",
            dtExplained = 1_757_000_200_000L,
            dtExplainedBy = "Claude app",
            dtNotes = "the day-trading note"
        )
        val back = ResearchSet.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.toJson().toString(), back.toJson().toString())
        assertEquals(22.5, back.dayTrading[0].entryPrice, 0.001)
        assertEquals(21.0, back.dayTrading[0].stopPrice, 0.001)
        assertEquals(25.5, back.dayTrading[0].targetPrice, 0.001)
        // The two explain states did not bleed into each other in either direction.
        assertEquals("Claude app", back.dtExplainedBy)
        assertEquals("API", back.explainedBy)
        assertEquals("the day-trading note", back.dtNotes)
        assertEquals("the research note", back.notes)
    }

    @Test
    fun `paging never asks for more rows than the section holds`() {
        val rows = (1..14).map { ResearchRow(symbol = "S$it", score = 100 - it) }
        val set = ResearchSet(best = rows)
        assertEquals(10, ResearchSet.PAGE)
        assertEquals(10, set.best.take(ResearchSet.PAGE).size)
        // Page two is the remainder, not another full page off the end of the list.
        assertEquals(4, set.best.drop(ResearchSet.PAGE).take(ResearchSet.PAGE).size)
    }

    @Test
    fun `sections are addressed by name in both directions`() {
        val set = ResearchSet(best = listOf(ResearchRow(symbol = "A")))
        assertEquals("A", set.section(ResearchSet.SECTION_BEST)[0].symbol)
        val updated = set.withSection(ResearchSet.SECTION_BEST, listOf(ResearchRow(symbol = "B")))
        assertEquals("B", updated.best[0].symbol)
        assertTrue(updated.trending.isEmpty())
    }
}
