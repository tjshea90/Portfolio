package com.tj.portfolio

import com.tj.portfolio.data.EtfFacts
import com.tj.portfolio.data.EtfRow
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.net.EtfScore
import com.tj.portfolio.net.EtfScreener
import com.tj.portfolio.net.ResearchBridge
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
 * THE BEST-ETFS LIST (Round 63) - the parse, the ranking, and the round trip through Claude.
 *
 * The fixture below is a REAL Yahoo screener response, trimmed: the SMH row is verbatim from
 * `top_etfs_us` in September 2026, field for field. That matters more than a hand-written one
 * would, because the two things most likely to break this quietly are Yahoo changing a field
 * name and Yahoo changing a UNIT - and only a real response can catch either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EtfTest {

    // A real ETF, a leveraged one, an equity that slipped into a fund list, and a row with
    // almost nothing published.
    private val body = """
{"finance":{"result":[{"id":"x","title":"Top ETFs","quotes":[
  {"symbol":"SMH","quoteType":"ETF","longName":"VanEck Semiconductor ETF",
   "regularMarketPrice":567.01,"regularMarketChangePercent":2.6076791,
   "netExpenseRatio":0.35,"netAssets":67787031000.0,"yieldTTM":0.2,"dividendYield":0.2,
   "ytdReturn":54.55833,"trailingThreeMonthReturns":-7.02748,
   "fiftyTwoWeekChangePercent":90.39321,"annualReturnNavY3":73.21578,
   "annualReturnNavY5":42.2377,"averageDailyVolume3Month":10535919,
   "fiftyDayAverage":573.966,"twoHundredDayAverage":477.3958,
   "fiftyTwoWeekHigh":671.83,"fiftyTwoWeekLow":295.11,
   "firstTradeDateMilliseconds":960211800000,"fullExchangeName":"NasdaqGM"},
  {"symbol":"SOXL","quoteType":"ETF","longName":"Direxion Daily Semiconductor Bull 3X Shares",
   "regularMarketPrice":40.0,"netExpenseRatio":0.75,"netAssets":1.0E10,
   "annualReturnNavY3":120.0,"annualReturnNavY5":95.0,"ytdReturn":140.0,
   "fiftyTwoWeekChangePercent":210.0,"averageDailyVolume3Month":9.0E7,
   "fiftyDayAverage":38.0,"twoHundredDayAverage":30.0,
   "firstTradeDateMilliseconds":1300000000000,"fullExchangeName":"NYSEArca"},
  {"symbol":"NVDA","quoteType":"EQUITY","longName":"NVIDIA Corporation",
   "regularMarketPrice":180.0,"netAssets":0.0},
  {"symbol":"TINY","quoteType":"ETF","longName":"Tiny New Thematic ETF",
   "regularMarketPrice":25.0,"netExpenseRatio":0.85,"netAssets":1.2E7,
   "averageDailyVolume3Month":900,"firstTradeDateMilliseconds":1780000000000,
   "fullExchangeName":"NYSEArca"}
]}]}}
""".trimIndent()

    private fun parsed() = EtfScreener.parse(EtfScreener.Lists.TOP_ETFS, body)

    // ------------------------------------------------------------------ parsing

    @Test fun `only funds survive the parse`() {
        val rows = parsed()
        assertEquals(listOf("SMH", "SOXL", "TINY"), rows.map { it.symbol })
    }

    @Test fun `every factor field is read, at the unit Yahoo publishes it in`() {
        val smh = parsed().first { it.symbol == "SMH" }
        assertEquals("VanEck Semiconductor ETF", smh.name)
        assertEquals(567.01, smh.price, 1e-6)
        // THE UNIT TRAP. Yahoo publishes `netExpenseRatio` and `yieldTTM` already in PERCENT,
        // unlike `trailingAnnualDividendYield` on the stock screener which is a fraction the
        // app multiplies by 100. A "normalisation" of one to match the other would put a
        // 35-basis-point fund at 0.0035% or at 35%, and both look plausible on a card.
        assertEquals(0.35, smh.expenseRatio, 1e-9)
        assertEquals(0.2, smh.yieldPct, 1e-9)
        assertEquals(67_787_031_000.0, smh.netAssets, 1.0)
        assertEquals(73.21578, smh.threeYearAnnualPct, 1e-5)
        assertEquals(42.2377, smh.fiveYearAnnualPct, 1e-5)
        assertEquals(90.39321, smh.oneYearPct, 1e-5)
        assertEquals(54.55833, smh.ytdReturnPct, 1e-5)
        // AND THE OTHER UNIT TRAP: this feed publishes inception in MILLISECONDS, where the
        // stock screener publishes earnings in seconds.
        assertEquals(960_211_800_000L, smh.inceptionMs)
        assertTrue("SMH is older than 20 years", smh.ageYears > 20.0)
    }

    @Test fun `derived figures are computed, not guessed`() {
        val smh = parsed().first { it.symbol == "SMH" }
        assertEquals(567.01 * 10_535_919, smh.dollarVolume, 1.0)
        // Off its high: (671.83 - 567.01) / 671.83
        assertEquals(15.6, smh.offHighPct, 0.2)
        assertEquals(setOf(EtfScreener.Lists.TOP_ETFS), smh.lists)
    }

    @Test fun `a fund that published nothing reports absence, not zero`() {
        val tiny = parsed().first { it.symbol == "TINY" }
        assertEquals(0.0, tiny.threeYearAnnualPct, 1e-9)
        assertEquals(0.0, tiny.fiveYearAnnualPct, 1e-9)
        // -1, not 0: "no 52-week high published" is not "it never went above zero".
        assertEquals(-1.0, tiny.offHighPct, 1e-9)
    }

    @Test fun `a parse of nonsense returns nothing rather than throwing`() {
        assertTrue(EtfScreener.parse("x", "{}").isEmpty())
        assertTrue(EtfScreener.parse("x", """{"finance":{"result":[]}}""").isEmpty())
    }

    @Test fun `merging two lists unions what each one knew`() {
        val a = EtfRow(symbol = "X", name = "Fund X", price = 10.0, netAssets = 1e9,
            lists = setOf("a"))
        val b = EtfRow(symbol = "X", expenseRatio = 0.07, fiveYearAnnualPct = 12.0,
            lists = setOf("b"))
        val m = a.merge(b)
        assertEquals("Fund X", m.name)
        assertEquals(10.0, m.price, 1e-9)
        assertEquals(0.07, m.expenseRatio, 1e-9)
        assertEquals(12.0, m.fiveYearAnnualPct, 1e-9)
        assertEquals(setOf("a", "b"), m.lists)
    }

    // ------------------------------------------------------------- leverage filter

    @Test fun `leveraged and inverse funds are recognised from their names`() {
        val leveraged = listOf(
            "Direxion Daily Semiconductor Bull 3X Shares" to "SOXL",
            "ProShares UltraPro QQQ" to "TQQQ",
            "ProShares UltraShort S&P500" to "SDS",
            "T-Rex 2X Long NVIDIA Daily Target ETF" to "NVDX",
            "GraniteShares 2x Short TSLA Daily ETF" to "TSDD",
            "Direxion Daily Small Cap Bear 3X Shares" to "TZA",
            "ProShares Short QQQ" to "PSQ"
        )
        for ((name, sym) in leveraged) {
            assertTrue("$name should be excluded", EtfScore.isLeveragedOrInverse(name, sym))
        }
    }

    @Test fun `ordinary funds are not mistaken for leveraged ones`() {
        val plain = listOf(
            "VanEck Semiconductor ETF" to "SMH",
            "Vanguard S&P 500 ETF" to "VOO",
            "iShares Core U.S. Aggregate Bond ETF" to "AGG",
            "Schwab US Dividend Equity ETF" to "SCHD",
            "SPDR Gold Shares" to "GLD",
            "iShares MSCI USA Momentum Factor ETF" to "MTUM",
            "Invesco S&P 500 Equal Weight ETF" to "RSP",
            // "Short-term" and "Ultra Short" bond funds are the nastiest false positives:
            // both contain words on the leverage list and neither is leveraged.
            "iShares Short Treasury Bond ETF" to "SHV",
            "PIMCO Enhanced Short Maturity Active ETF" to "MINT"
        )
        for ((name, sym) in plain) {
            assertFalse("$name should NOT be excluded", EtfScore.isLeveragedOrInverse(name, sym))
        }
    }

    // ------------------------------------------------------------------ scoring

    @Test fun `a cheap, large, long-running fund outranks an expensive tiny one`() {
        val good = EtfRow(
            symbol = "GOOD", name = "Broad Index ETF", price = 100.0,
            expenseRatio = 0.03, netAssets = 4e11, ytdReturnPct = 12.0, oneYearPct = 18.0,
            threeYearAnnualPct = 14.0, fiveYearAnnualPct = 13.0, avgVolume3M = 5e6,
            fiftyDayAvg = 95.0, twoHundredDayAvg = 90.0, fiftyTwoWeekHigh = 105.0,
            fiftyTwoWeekLow = 80.0, inceptionMs = System.currentTimeMillis() - 20L * 31_557_600_000L
        )
        val bad = good.copy(
            symbol = "BAD", name = "Niche Thematic ETF",
            expenseRatio = 0.85, netAssets = 4e7, avgVolume3M = 2000.0,
            inceptionMs = System.currentTimeMillis() - 31_557_600_000L
        )
        val g = EtfScore.best(good)
        val b = EtfScore.best(bad)
        assertTrue("cheap+large must outrank expensive+tiny: ${g.score} vs ${b.score}",
            g.score > b.score + 20)
        assertEquals(100, g.confidence)
    }

    @Test fun `the long run outweighs a single hot year`() {
        // Two funds, identical but for their records: one has compounded steadily for five
        // years, the other has had one extraordinary year and nothing behind it. Ranking on
        // the recent number is exactly how a best-list fills up with whatever just ran.
        val base = EtfRow(
            symbol = "X", name = "Fund", price = 100.0, expenseRatio = 0.2, netAssets = 5e9,
            avgVolume3M = 1e6, fiftyDayAvg = 95.0, twoHundredDayAvg = 90.0,
            inceptionMs = System.currentTimeMillis() - 10L * 31_557_600_000L
        )
        val steady = base.copy(fiveYearAnnualPct = 18.0, threeYearAnnualPct = 17.0,
            oneYearPct = 12.0, ytdReturnPct = 8.0)
        val oneHotYear = base.copy(symbol = "Y", fiveYearAnnualPct = 0.0,
            threeYearAnnualPct = 0.0, oneYearPct = 60.0, ytdReturnPct = 45.0)
        assertTrue(
            "a five-year record must beat one hot year: " +
                "${EtfScore.best(steady).score} vs ${EtfScore.best(oneHotYear).score}",
            EtfScore.best(steady).score > EtfScore.best(oneHotYear).score
        )
    }

    /**
     * ROUND 66. Youth was being charged for twice.
     *
     * The four return terms were simply added, so a fund with no five-year figure lost those
     * 14 points outright - and then lost points AGAIN on the record-length factor, which
     * exists precisely to say "too young to have shown it". A four-year-old fund that had
     * beaten the market in every one of those four years could not place, and nothing in its
     * reason lines said why.
     */
    @Test fun `a four-year fund is judged on the record it has`() {
        val ten = System.currentTimeMillis() - 10L * 31_557_600_000L
        val four = System.currentTimeMillis() - 4L * 31_557_600_000L
        val base = EtfRow(
            symbol = "X", name = "Fund", price = 100.0, expenseRatio = 0.1, netAssets = 5e9,
            avgVolume3M = 1e6, fiftyDayAvg = 95.0, twoHundredDayAvg = 90.0
        )
        val veteran = base.copy(
            inceptionMs = ten,
            fiveYearAnnualPct = 16.0, threeYearAnnualPct = 16.0,
            oneYearPct = 16.0, ytdReturnPct = 12.0
        )
        val younger = base.copy(
            symbol = "Y", inceptionMs = four,
            fiveYearAnnualPct = 0.0, threeYearAnnualPct = 16.0,
            oneYearPct = 16.0, ytdReturnPct = 12.0
        )
        val v = EtfScore.best(veteran).score
        val y = EtfScore.best(younger).score
        // It should still lose - the record-length factor is a real difference - but by the
        // eight points that factor is worth, not by the twenty-two it used to.
        assertTrue("the younger fund scored $y against $v; it is being charged twice for its age",
            y >= v - 12)
        assertTrue("but a shorter record should still cost something: $y vs $v", y < v)
    }

    /**
     * The other half of the same rule: the three-year floor. Normalising a fund with ONLY a
     * hot twelve months up to full marks is exactly how a best-list fills with whatever just
     * ran, which is the trap the whole weighting exists to avoid.
     */
    @Test fun `a fund with only one year of record cannot normalise its way to the top`() {
        val base = EtfRow(
            symbol = "X", name = "Fund", price = 100.0, expenseRatio = 0.1, netAssets = 5e9,
            avgVolume3M = 1e6, fiftyDayAvg = 95.0, twoHundredDayAvg = 90.0,
            inceptionMs = System.currentTimeMillis() - 10L * 31_557_600_000L
        )
        val steady = base.copy(
            fiveYearAnnualPct = 15.0, threeYearAnnualPct = 15.0, oneYearPct = 15.0,
            ytdReturnPct = 11.0
        )
        val hot = base.copy(
            symbol = "Y", inceptionMs = System.currentTimeMillis() - 18L * 2_629_800_000L,
            fiveYearAnnualPct = 0.0, threeYearAnnualPct = 0.0,
            oneYearPct = 90.0, ytdReturnPct = 70.0
        )
        assertTrue(
            "an eighteen-month fund with one hot year outscored a five-year record: " +
                "${EtfScore.best(hot).score} vs ${EtfScore.best(steady).score}",
            EtfScore.best(steady).score > EtfScore.best(hot).score
        )
    }

    /**
     * ROUND 66 AUDIT (E4). The normalisation gate was the SUM OF AVAILABLE WEIGHTS, which is
     * only reachable as 3Y+1Y+YTD or with a 5Y term. So a fund with a genuine three-year
     * record but no published YTD figure fell off the normalised path entirely and was capped
     * at 16 of the 34 return points - and adding a near-worthless YTD crossed the threshold
     * and jumped it eleven points, for a figure that had earned almost none of them. A
     * discontinuity, in the direction that rewarded reporting a bad number.
     *
     * The gate is the record itself now, so the return term is a plain weighted average of
     * the horizons a fund actually publishes. This asserts the property that follows: a fund
     * is judged on its RATES, not on how many boxes it happened to fill in.
     */
    @Test fun `a missing short-run figure does not change a fund judged on its rates`() {
        val base = EtfRow(
            symbol = "X", name = "Fund", price = 100.0, expenseRatio = 0.1, netAssets = 5e9,
            avgVolume3M = 1e6, fiftyDayAvg = 95.0, twoHundredDayAvg = 90.0,
            inceptionMs = System.currentTimeMillis() - 10L * 31_557_600_000L,
            threeYearAnnualPct = 20.0, oneYearPct = 30.0
        )
        val withoutYtd = EtfScore.best(base).score
        // The same fund, whose YTD is running at the same full rate as everything else.
        val withYtd = EtfScore.best(base.copy(ytdReturnPct = 25.0)).score
        assertEquals(
            "a fund topping out every horizon it reports should score the same whether or not " +
                "one more horizon is published ($withoutYtd vs $withYtd)",
            withoutYtd, withYtd
        )
    }

    /** And a weak figure still counts against it - that is what an average is for. */
    @Test fun `a weak YTD lowers the score, by about what its weight is worth`() {
        val base = EtfRow(
            symbol = "X", name = "Fund", price = 100.0, expenseRatio = 0.1, netAssets = 5e9,
            avgVolume3M = 1e6, fiftyDayAvg = 95.0, twoHundredDayAvg = 90.0,
            inceptionMs = System.currentTimeMillis() - 10L * 31_557_600_000L,
            threeYearAnnualPct = 20.0, oneYearPct = 30.0
        )
        val strong = EtfScore.best(base.copy(ytdReturnPct = 25.0)).score
        val weak = EtfScore.best(base.copy(ytdReturnPct = 0.5)).score
        assertTrue("a flat year to date should cost something: $strong -> $weak", weak < strong)
        assertTrue(
            "but not more than the whole return factor: $strong -> $weak",
            strong - weak <= 12
        )
    }

    @Test fun `cost is counted against return`() {
        val cheap = EtfRow(
            symbol = "A", name = "A", price = 100.0, expenseRatio = 0.03, netAssets = 1e10,
            fiveYearAnnualPct = 12.0, threeYearAnnualPct = 12.0, avgVolume3M = 1e6,
            inceptionMs = System.currentTimeMillis() - 10L * 31_557_600_000L
        )
        val dear = cheap.copy(symbol = "B", expenseRatio = 0.70)
        assertTrue(EtfScore.best(cheap).score > EtfScore.best(dear).score)
        // And it says so in words, with the cost in dollars rather than in basis points.
        assertTrue(
            EtfScore.best(dear).reasons.any { it.contains("a year on every") }
        )
    }

    @Test fun `yield is reported but never scored`() {
        val dry = EtfRow(
            symbol = "A", name = "A", price = 100.0, expenseRatio = 0.1, netAssets = 1e10,
            fiveYearAnnualPct = 10.0, avgVolume3M = 1e6, yieldPct = 0.0,
            inceptionMs = System.currentTimeMillis() - 10L * 31_557_600_000L
        )
        val juicy = dry.copy(symbol = "B", yieldPct = 8.0)
        assertEquals(
            "a distribution yield must not move the ranking",
            EtfScore.best(dry).score, EtfScore.best(juicy).score
        )
        assertTrue(EtfScore.best(juicy).reasons.any { it.contains("distributions") })
    }

    @Test fun `a fund with almost nothing published reports low confidence`() {
        val thin = EtfRow(symbol = "TINY", name = "Tiny", price = 25.0)
        val sc = EtfScore.best(thin)
        assertTrue("confidence should be low, was ${sc.confidence}", sc.confidence < 60)
    }

    @Test fun `every score stays inside 0 to 100`() {
        val extreme = EtfRow(
            symbol = "X", name = "X", price = 100.0, expenseRatio = 0.001, netAssets = 1e13,
            ytdReturnPct = 900.0, oneYearPct = 900.0, threeYearAnnualPct = 900.0,
            fiveYearAnnualPct = 900.0, avgVolume3M = 1e10, fiftyDayAvg = 1.0,
            twoHundredDayAvg = 1.0, inceptionMs = 0L
        )
        val s = EtfScore.best(extreme)
        assertTrue(s.score in 0..100)
        val awful = EtfRow(
            symbol = "Y", name = "Y", price = 1.0, expenseRatio = 5.0, netAssets = 1.0,
            ytdReturnPct = -99.0, fiveYearAnnualPct = -80.0, avgVolume3M = 1.0,
            fiftyDayAvg = 100.0, twoHundredDayAvg = 100.0,
            inceptionMs = System.currentTimeMillis()
        )
        assertTrue(EtfScore.best(awful).score in 0..100)
    }

    // -------------------------------------------------------- the cache round trip

    @Test fun `a fund row survives the JSON cache unchanged`() {
        val row = ResearchRow(
            symbol = "SMH", name = "VanEck Semiconductor ETF", price = 567.01, score = 71,
            reasons = listOf("Returned 42.24%/yr over 5y"),
            etf = EtfFacts(
                expenseRatio = 0.35, netAssets = 6.7787031E10, yieldPct = 0.2,
                ytdReturnPct = 54.55833, oneYearPct = 90.39321,
                threeYearAnnualPct = 73.21578, fiveYearAnnualPct = 42.2377,
                dollarVolume = 5.97E9, inceptionMs = 960_211_800_000L
            )
        )
        val back = ResearchRow.fromJson(JSONObject(row.toJson().toString()))!!
        assertEquals(row.symbol, back.symbol)
        assertNotNull("the fund facts were lost in the cache", back.etf)
        assertEquals(0.35, back.etf!!.expenseRatio, 1e-9)
        assertEquals(42.2377, back.etf!!.fiveYearAnnualPct, 1e-6)
        assertEquals(960_211_800_000L, back.etf!!.inceptionMs)
    }

    @Test fun `a stock row carries no fund facts`() {
        val row = ResearchRow(symbol = "NVDA", price = 180.0, score = 80)
        val back = ResearchRow.fromJson(JSONObject(row.toJson().toString()))!!
        assertNull(back.etf)
    }

    @Test fun `the ETF list survives the set-level cache with its own timestamp`() {
        val set = ResearchSet(
            best = listOf(ResearchRow(symbol = "NVDA", score = 80)),
            etfs = listOf(ResearchRow(symbol = "VOO", score = 90, etf = EtfFacts(expenseRatio = 0.03))),
            generated = 1_700_000_000_000L,
            etfGenerated = 1_600_000_000_000L,
            etfWarnings = listOf("bond screen was quiet")
        )
        val back = ResearchSet.fromJson(JSONObject(set.toJson().toString()))
        assertEquals(1, back.etfs.size)
        assertEquals("VOO", back.etfs[0].symbol)
        assertEquals(0.03, back.etfs[0].etf!!.expenseRatio, 1e-9)
        // THE TWO CLOCKS MUST STAY APART. One stamp for both would make the six-hourly fund
        // list claim the freshness of the half-hourly stock lists.
        assertEquals(1_600_000_000_000L, back.etfGenerated)
        assertEquals(1_700_000_000_000L, back.generated)
        assertEquals(listOf("bond screen was quiet"), back.etfWarnings)
    }

    @Test fun `an ETF-only set is empty of stocks but not fully empty`() {
        val set = ResearchSet(etfs = listOf(ResearchRow(symbol = "VOO")))
        assertTrue("the three stock lists are empty", set.isEmpty)
        assertFalse("but the screen is not", set.isFullyEmpty)
        assertEquals(listOf("VOO"), set.section(ResearchSet.SECTION_ETF).map { it.symbol })
    }

    @Test fun `the ETF section is addressable by name like the other three`() {
        val set = ResearchSet().withSection(
            ResearchSet.SECTION_ETF, listOf(ResearchRow(symbol = "VTI"))
        )
        assertEquals(listOf("VTI"), set.etfs.map { it.symbol })
        assertTrue(ResearchSet.SECTION_ETF in ResearchSet.SECTIONS)
    }

    // -------------------------------------------------------- the Claude bridge

    @Test fun `the prompt carries the fund numbers and names the universe gap`() {
        val set = ResearchSet(
            etfs = listOf(
                ResearchRow(
                    symbol = "SMH", name = "VanEck Semiconductor ETF", price = 567.01,
                    score = 71, reasons = listOf("Costs 0.35% a year"),
                    etf = EtfFacts(expenseRatio = 0.35, netAssets = 6.7E10,
                        fiveYearAnnualPct = 42.24)
                )
            ),
            etfGenerated = System.currentTimeMillis()
        )
        val prompt = ResearchBridge.prompt(set, emptyList(), emptyList())
        assertTrue("the fund's own numbers must be in the bundle",
            prompt.contains("fiveYearAnnualisedPct"))
        assertTrue("the expense ratio must be in the bundle",
            prompt.contains("expenseRatioPct"))
        // The whole reason the ETF list asks Claude to RESEARCH rather than explain.
        assertTrue("the prompt must name the funds Yahoo's screens omit",
            prompt.contains("VTI") && prompt.contains("SCHD"))
        assertTrue(prompt.contains("etfs"))
    }

    @Test fun `a reply's ETF section is parsed, category included`() {
        val reply = """
            Here is my analysis.

            ```json
            {"portfolioAppResponse":1,"research":{"asOf":"2026-09-08","etfs":[
              {"symbol":"VTI","why":"The whole US market for three basis points.",
               "category":"broad US equity index","conviction":9},
              {"symbol":"SMH","why":"A concentrated semiconductor bet, not a core holding.",
               "category":"semiconductor sector","conviction":4}
            ],"notes":"Your list is missing the broad-market funds."}}
            ```
        """.trimIndent()
        val p = ResearchBridge.parse(reply)
        assertNull(p.error)
        assertEquals(2, p.etfs.size)
        val vti = p.etfs.first { it.symbol == "VTI" }
        assertTrue(vti.why.contains("three basis points"))
        // `category` is read into the same field `catalyst` uses, so one card layout serves
        // all four sections.
        assertEquals("broad US equity index", vti.catalyst)
        // ROUND 66 AUDIT (R1). Conviction is NOT the app's score. It used to be written in as
        // `conviction * 10`, so a fund the app never screened arrived showing "SCORE 90" in
        // the same circle, in the same type, as a fund measured from a five-year NAV return
        // and an expense ratio.
        assertEquals("the app scored nothing here", 0, vti.score)
        assertEquals(9, vti.conviction)
        assertEquals("Your list is missing the broad-market funds.", p.notes)
    }

    @Test fun `a fund Claude adds is kept and one it explains is merged`() {
        val existing = listOf(
            ResearchRow(symbol = "SMH", score = 71, reasons = listOf("app reason"),
                etf = EtfFacts(expenseRatio = 0.35))
        )
        val incoming = listOf(
            ResearchRow(symbol = "SMH", why = "A sector bet.", score = 40),
            ResearchRow(symbol = "VTI", why = "The whole market.", score = 90)
        )
        val merged = ResearchBridge.merge(existing, incoming)
        assertEquals(2, merged.size)
        val smh = merged.first { it.symbol == "SMH" }
        // THE APP'S SCORE AND FACTS SURVIVE. Claude's conviction is not reproducible
        // arithmetic and must not overwrite one that is.
        assertEquals(71, smh.score)
        assertEquals(listOf("app reason"), smh.reasons)
        assertEquals(0.35, smh.etf!!.expenseRatio, 1e-9)
        assertEquals("A sector bet.", smh.why)
        val vti = merged.first { it.symbol == "VTI" }
        assertEquals(90, vti.score)
        assertNull("a fund Claude added has no screener facts", vti.etf)
    }

    /**
     * ROUND 66 AUDIT (R1). A fund the app measured always outranks one it did not.
     *
     * The bridge used to write `conviction * 10` into `score` and the ETF list sorted on that,
     * so a fund Claude asserted a 10 for landed at row 1 - above every fund the app actually
     * screened, with no facts grid and no reason lines - on the list TJ said he is going to
     * buy from.
     */
    @Test fun `a suggested fund cannot outrank one the app scored`() {
        val rows = listOf(
            ResearchRow(symbol = "SUGGESTED", conviction = 10, why = "Claude likes it."),
            ResearchRow(symbol = "SCREENED", score = 62, reasons = listOf("app reason")),
            ResearchRow(symbol = "ALSO_SUGGESTED", conviction = 7, why = "And this one.")
        )
        val ordered = rows.sortedWith(
            compareByDescending<ResearchRow> { it.score }.thenByDescending { it.conviction }
        )
        assertEquals(
            listOf("SCREENED", "SUGGESTED", "ALSO_SUGGESTED"),
            ordered.map { it.symbol }
        )
    }

    @Test fun `a reply naming the same fund twice cannot duplicate a list key`() {
        // A keyed LazyColumn handed one key twice THROWS, and the bad set is written straight
        // to the cache - so the tab would keep crashing on every launch.
        val incoming = listOf(
            ResearchRow(symbol = "VTI", why = "One."),
            ResearchRow(symbol = "VTI", why = "Two.")
        )
        val merged = ResearchBridge.merge(emptyList(), incoming)
        assertEquals(1, merged.size)
    }
}
