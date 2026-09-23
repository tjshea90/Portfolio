package com.tj.portfolio

import com.tj.portfolio.data.AnalystRating
import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.FundamentalsJson
import com.tj.portfolio.data.MetricCatalog
import com.tj.portfolio.data.MetricUnit
import com.tj.portfolio.net.FundamentalsFeed
import com.tj.portfolio.ui.Explain
import com.tj.portfolio.ui.Verdict
import com.tj.portfolio.ui.formatMetric
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
 * THE FUNDAMENTALS FEED, RUN AGAINST REAL CAPTURED PAYLOADS.
 *
 * The fixtures in `src/test/resources` are genuine responses pulled from the live
 * providers - Yahoo's quoteSummary for AAPL and NVDA, Finviz's quote page for AAPL and
 * FIVE, and two of Nasdaq's public JSON endpoints - trimmed only in the sense that the
 * analyst history was cut to its newest 45 rows so the archive stays small.
 *
 * That matters more than it sounds. Every parser in this file was written against the
 * SHAPE of a provider's response, and every one of these providers hands back things a
 * reasonable-looking parser gets wrong: Yahoo wraps numbers as `{raw, fmt}` but sends an
 * EMPTY OBJECT for "not reported", Finviz spreads one logical table across six separate
 * `<table>` elements and prints "-" for a missing figure, and Nasdaq writes "N/A" as a
 * string. Testing against a hand-written JSON blob would have proved only that the parser
 * agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FundamentalsTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().use { it.readText() }

    private fun yahoo(sym: String): Fundamentals {
        val res = JSONObject(fixture("yahoo_$sym.json"))
            .getJSONObject("quoteSummary").getJSONArray("result").getJSONObject(0)
        return FundamentalsFeed.parseYahoo(sym, res)
    }

    // ------------------------------------------------------------------ Yahoo

    @Test
    fun `yahoo payload yields the headline valuation numbers`() {
        val f = yahoo("AAPL")
        assertEquals(37.599, f.value("peTrailing")!!, 0.01)
        assertEquals(33.419, f.value("peForward")!!, 0.01)
        assertEquals(4.6697e12, f.value("marketCap")!!, 1e9)
        assertEquals(8.51, f.value("epsTrailing")!!, 0.001)
        assertEquals(43.474, f.value("priceToBook")!!, 0.01)
        assertEquals(27.933, f.value("evToEbitda")!!, 0.01)
        assertEquals(1.085, f.value("beta")!!, 0.001)
        assertEquals(344.57, f.value("fiftyTwoWeekHigh")!!, 0.01)
        assertEquals(78.445, f.value("debtToEquity")!!, 0.01)
        assertEquals(0.27619, f.value("profitMargins")!!, 0.0001)
        assertEquals(1.4875, f.value("returnOnEquity")!!, 0.0001)
    }

    /**
     * Yahoo has shipped this field both as a fraction (0.0033) and as a percentage (0.33)
     * across API revisions, and the two differ by a factor of a hundred. The parser
     * normalises to a fraction; anything else would print a 0.33% yield as 33%.
     */
    @Test
    fun `dividend yield is normalised to a fraction`() {
        val f = yahoo("AAPL")
        val y = f.value("dividendYield")!!
        assertTrue("yield should be a small fraction, was $y", y > 0.0 && y < 0.1)
        // Cross-check it against the dividend and the price it was captured at.
        val rate = f.value("dividendRate")!!
        assertEquals(1.08, rate, 0.001)
    }

    /**
     * An empty `{}` where a number should be must read as ABSENT, not as 0.0. This is the
     * same class of bug as the fabricated previous close that made a day change come out as
     * exactly +0.00% - a wrong number that looks like a real one.
     */
    @Test
    fun `a field yahoo did not report is absent rather than zero`() {
        val res = JSONObject(
            """{"summaryDetail":{"trailingPE":{},"marketCap":{"raw":123.0}}}"""
        )
        val f = FundamentalsFeed.parseYahoo("TEST", res)
        assertNull("an empty object must not become 0.0", f.value("peTrailing"))
        assertEquals(123.0, f.value("marketCap")!!, 0.001)
    }

    @Test
    fun `yahoo ratings carry firm, grade, target and date, newest first`() {
        val f = yahoo("AAPL")
        assertTrue("expected analyst ratings", f.ratings.size > 5)
        val first = f.ratings.first()
        assertTrue(first.firm.isNotBlank())
        assertTrue("date should be a real epoch", first.date > 1_600_000_000_000L)
        // sorted newest first
        for (i in 1 until f.ratings.size) {
            assertTrue(
                "ratings must be sorted newest first",
                f.ratings[i - 1].date >= f.ratings[i].date
            )
        }
        // The whole point of choosing Yahoo over the other free feeds: a price target on
        // the individual rating, not just a consensus.
        assertTrue(
            "at least some ratings must carry a price target",
            f.ratings.count { it.target > 0 } > 3
        )
        // No two rows may share an id - the list is keyed by it and Compose throws on a
        // duplicate key, which is what killed the app on the insider-filings list once.
        assertEquals(f.ratings.size, f.ratings.map { it.id }.toSet().size)
    }

    @Test
    fun `yahoo consensus carries the vote split and the target range`() {
        val c = yahoo("AAPL").consensus!!
        assertTrue(c.hasVotes)
        assertTrue(c.hasTarget)
        assertTrue(c.targetLow > 0 && c.targetHigh > c.targetLow)
        assertTrue(c.targetMean in c.targetLow..c.targetHigh)
        assertTrue(c.analysts > 0)
        assertEquals("buy", c.key)
        assertEquals("Buy", c.meanLabel)
    }

    @Test
    fun `yahoo estimates parse for every period`() {
        val f = yahoo("AAPL")
        assertTrue(f.estimates.isNotEmpty())
        val q = f.estimates.first { it.period == "0q" }
        assertTrue(q.epsAvg > 0)
        assertTrue(q.epsLow <= q.epsAvg && q.epsAvg <= q.epsHigh)
        assertTrue(q.analysts > 0)
        assertEquals("Current quarter", q.label)
    }

    @Test
    fun `the second fixture parses too, so nothing is tuned to one symbol`() {
        val f = yahoo("NVDA")
        assertTrue(f.values.size > 30)
        assertNotNull(f.consensus)
        assertTrue(f.ratings.isNotEmpty())
        // NVDA's fixture kept assetProfile, so the company text should be there.
        assertTrue(f.text("sector").isNotBlank())
        assertTrue(f.profile.isNotBlank())
    }

    // ----------------------------------------------------------------- Finviz

    @Test
    fun `finviz snapshot is parsed despite being six separate tables`() {
        val f = FundamentalsFeed.parseFinviz("AAPL", fixture("finviz_AAPL.html"))
        assertEquals(36.68, f.value("peTrailing")!!, 0.01)
        assertEquals(33.43, f.value("peForward")!!, 0.01)
        assertEquals(2.64, f.value("pegRatio")!!, 0.01)
        assertEquals(4.6697e12, f.value("marketCap")!!, 1e9)
        assertEquals(1.07, f.value("beta")!!, 0.01)
        assertEquals(166000.0, f.value("employees")!!, 1.0)
        // A percentage cell must become a fraction, matching the catalogue's unit.
        assertEquals(0.2762, f.value("profitMargins")!!, 0.0001)
        assertEquals(0.4865, f.value("grossMargins")!!, 0.0001)
        assertEquals(0.008, f.value("shortPercentOfFloat")!!, 0.0001)
        // Finviz prints debt/equity as a multiple (0.78); the catalogue's unit is PERCENT,
        // which is how Yahoo reports the identical figure (78.45).
        assertEquals(78.0, f.value("debtToEquity")!!, 0.5)
        // "1.08 (0.34%)" - both halves.
        assertEquals(1.08, f.value("dividendRate")!!, 0.001)
        assertEquals(0.0034, f.value("dividendYield")!!, 0.0001)
        // "344.57 -7.14%" - the level, not the distance.
        assertEquals(344.57, f.value("fiftyTwoWeekHigh")!!, 0.01)
        assertEquals(225.95, f.value("fiftyTwoWeekLow")!!, 0.01)
    }

    /**
     * Finviz lists "EPS next Y" twice - once as a dollar estimate and once as a growth
     * percentage. Taking whichever came last would have recorded a forward EPS of 8.41
     * for a company whose analysts expect $9.57.
     */
    @Test
    fun `the duplicated finviz label does not become the wrong number`() {
        val f = FundamentalsFeed.parseFinviz("AAPL", fixture("finviz_AAPL.html"))
        assertEquals(9.57, f.value("epsForward")!!, 0.01)
    }

    @Test
    fun `finviz analyst table gives firm, action, grade change and both targets`() {
        val ratings = FundamentalsFeed.parseFinvizRatings(fixture("finviz_AAPL.html"))
        assertTrue("expected rating rows, got ${ratings.size}", ratings.size >= 5)
        val top = ratings.first()
        assertEquals("Rosenblatt", top.firm)
        assertEquals(AnalystRating.REITERATE, top.action)
        assertEquals("Neutral", top.toGrade)
        assertEquals(AnalystRating.HOLD, top.bucket)
        assertEquals(303.0, top.target, 0.01)
        assertEquals(300.0, top.priorTarget, 0.01)

        // An upgrade row carries both grades and decodes the arrow entity.
        val upgrade = ratings.first { it.action == AnalystRating.UPGRADE }
        assertTrue(upgrade.fromGrade.isNotBlank())
        assertFalse(upgrade.toGrade.contains("&"))
        assertFalse(upgrade.firm.contains("&amp;"))

        // The header row must not become a rating.
        assertTrue(ratings.none { it.firm.equals("Analyst", true) })
        for (i in 1 until ratings.size) {
            assertTrue(ratings[i - 1].date >= ratings[i].date)
        }
    }

    @Test
    fun `the second finviz fixture parses too`() {
        val f = FundamentalsFeed.parseFinviz("FIVE", fixture("finviz_FIVE.html"))
        assertTrue("expected a useful number of metrics, got ${f.values.size}", f.values.size > 25)
        assertTrue(FundamentalsFeed.parseFinvizRatings(fixture("finviz_FIVE.html")).isNotEmpty())
    }

    // ----------------------------------------------------------------- Nasdaq

    @Test
    fun `nasdaq summary parses, and N slash A never becomes a number`() {
        val v = HashMap<String, Double>()
        val t = HashMap<String, String>()
        FundamentalsFeed.parseNasdaqSummary(fixture("nasdaq_summary_FIVE.json"), v, t)
        assertEquals(1.39454e10, v["marketCap"]!!, 1e6)
        assertEquals(263.875, v["fiftyTwoWeekHigh"]!!, 0.01)
        assertEquals(137.77, v["fiftyTwoWeekLow"]!!, 0.01)
        assertEquals("Consumer Discretionary", t["sector"])
        // FIVE pays no dividend, so every dividend field on this fixture is the string
        // "N/A". None of them may turn into a zero.
        assertNull(v["dividendRate"])
        assertNull(v["dividendYield"])
        assertNull(v["exDividendDate"])
    }

    @Test
    fun `nasdaq target price becomes a consensus with a derived mean`() {
        val c = FundamentalsFeed.parseNasdaqTarget(fixture("nasdaq_target_FIVE.json"))!!
        assertEquals(305.71, c.targetMean, 0.01)
        assertEquals(235.0, c.targetLow, 0.01)
        assertEquals(420.0, c.targetHigh, 0.01)
        assertEquals(12, c.buy)
        assertEquals(7, c.hold)
        assertEquals(0, c.sell)
        // Nasdaq has no 1-5 mean of its own; one is derived so the consensus card reads the
        // same whichever provider answered. 12 buys and 7 holds must land in "Buy".
        assertTrue(c.mean > 2.0 && c.mean < 2.6)
        assertEquals("Buy", c.meanLabel)
    }

    // ------------------------------------------------------------------ merge

    @Test
    fun `merge fills gaps and never overwrites the preferred provider`() {
        val base = Fundamentals(
            symbol = "X",
            values = mapOf("peTrailing" to 20.0),
            sources = listOf("A"),
            fetched = 100L
        )
        val fill = Fundamentals(
            symbol = "X",
            values = mapOf("peTrailing" to 99.0, "beta" to 1.5),
            sources = listOf("B"),
            fetched = 50L
        )
        val m = Fundamentals.merge(base, fill)
        assertEquals(20.0, m.value("peTrailing")!!, 0.001)   // base wins
        assertEquals(1.5, m.value("beta")!!, 0.001)          // gap filled
        assertEquals(listOf("A", "B"), m.sources)
        assertEquals(100L, m.fetched)
    }

    /**
     * The same action reported by two providers must collapse to one row. Yahoo timestamps
     * to the second and Finviz only to the day, so the identity truncates to the day - and
     * the list is keyed by that identity, so a duplicate would be a crash rather than a
     * cosmetic problem.
     */
    @Test
    fun `the same rating from two sources de-duplicates`() {
        val day = 1_788_265_027_000L
        val a = AnalystRating("Rosenblatt", day, AnalystRating.REITERATE, "Neutral", target = 303.0)
        val b = AnalystRating("rosenblatt ", day + 3_600_000L, AnalystRating.REITERATE, "neutral")
        val m = Fundamentals.merge(
            Fundamentals("X", ratings = listOf(a)),
            Fundamentals("X", ratings = listOf(b))
        )
        assertEquals(1, m.ratings.size)
        assertEquals(303.0, m.ratings.first().target, 0.01)
    }

    // ------------------------------------------------------------ json codec

    @Test
    fun `json round trip preserves everything the screen draws`() {
        val f = yahoo("AAPL")
        val back = FundamentalsJson.fromJson(FundamentalsJson.toJson(f))!!
        assertEquals(f.values.size, back.values.size)
        assertEquals(f.value("peTrailing")!!, back.value("peTrailing")!!, 1e-9)
        assertEquals(f.ratings.size, back.ratings.size)
        assertEquals(f.ratings.first().firm, back.ratings.first().firm)
        assertEquals(f.ratings.first().target, back.ratings.first().target, 1e-9)
        assertEquals(f.consensus!!.targetMean, back.consensus!!.targetMean, 1e-9)
        assertEquals(f.estimates.size, back.estimates.size)
        assertEquals(f.sources, back.sources)
        assertEquals(f.earningsDate, back.earningsDate)
    }

    /** A half-written row surviving a process kill must read as a cache MISS, not a crash. */
    @Test
    fun `corrupt cache json reads as a miss`() {
        assertNull(FundamentalsJson.fromJson("{not json"))
        assertNull(FundamentalsJson.fromJson(""))
    }

    // ------------------------------------------------------ number extraction

    @Test
    fun `loose number parsing survives every dress these pages use`() {
        assertEquals(1.08, FundamentalsFeed.loose("$1.08")!!, 0.001)
        assertEquals(4.6697e12, FundamentalsFeed.loose("4669.70B")!!, 1e9)
        assertEquals(1.1633e8, FundamentalsFeed.loose("116.33M")!!, 1e4)
        assertEquals(-0.0225 * 100, FundamentalsFeed.loose("-2.25%")!!, 0.001)
        assertEquals(1908115.0, FundamentalsFeed.loose("1,908,115")!!, 1.0)
        assertEquals(344.57, FundamentalsFeed.loose("344.57 -7.14%")!!, 0.01)
        // The three ways a page says "nothing here".
        assertNull(FundamentalsFeed.loose("-"))
        assertNull(FundamentalsFeed.loose("N/A"))
        assertNull(FundamentalsFeed.loose(""))
        assertNull(FundamentalsFeed.loose(null))
    }

    @Test
    fun `entity decoding and arrow splitting`() {
        assertEquals("Rothschild & Co", FundamentalsFeed.clean("Rothschild &amp; Co"))
        assertEquals("Neutral → Buy", FundamentalsFeed.clean("<b>Neutral &rarr; Buy</b>"))
        val (from, to) = FundamentalsFeed.splitArrow("Neutral → Buy")
        assertEquals("Neutral", from)
        assertEquals("Buy", to)
        val (only, none) = FundamentalsFeed.splitArrow("$400")
        assertEquals("$400", only)
        assertEquals("", none)
    }

    @Test
    fun `finviz dates parse and us dates parse`() {
        assertNotNull(FundamentalsFeed.parseFinvizDate("Sep-01-26"))
        assertNull(FundamentalsFeed.parseFinvizDate("not a date"))
        assertNotNull(FundamentalsFeed.parseUsDate("Aug 10, 2026"))
        assertNull(FundamentalsFeed.parseUsDate("N/A"))
    }

    // ---------------------------------------------------------- rating buckets

    /**
     * Wall Street has no shared vocabulary, and the failure that matters is a SELL rating
     * being read as a hold: "Market Underperform" contains "perform".
     */
    @Test
    fun `every common grade wording lands in the right bucket`() {
        val buy = listOf(
            "Buy", "Strong Buy", "Outperform", "Overweight", "Accumulate", "Add",
            "Market Outperform", "Positive", "Conviction Buy", "Top Pick"
        )
        val hold = listOf(
            "Hold", "Neutral", "Equal-Weight", "Market Perform", "Sector Perform",
            "Peer Perform", "In-Line", "Equal Weight",
            // full test 2026-09-23, S-4 - these had no bucket, so the firm got no vote
            "Sector Weight", "Market Weight", "Peer Weight"
        )
        val sell = listOf(
            "Sell", "Underperform", "Underweight", "Reduce", "Market Underperform",
            "Sector Underperform", "Negative"
        )
        buy.forEach { assertEquals(it, AnalystRating.BUY, AnalystRating.bucketOf(it)) }
        hold.forEach { assertEquals(it, AnalystRating.HOLD, AnalystRating.bucketOf(it)) }
        sell.forEach { assertEquals(it, AnalystRating.SELL, AnalystRating.bucketOf(it)) }
        assertEquals(AnalystRating.NONE, AnalystRating.bucketOf(""))
    }

    @Test
    fun `yahoo action codes decode`() {
        assertEquals(AnalystRating.UPGRADE, AnalystRating.actionOf("up"))
        assertEquals(AnalystRating.DOWNGRADE, AnalystRating.actionOf("down"))
        assertEquals(AnalystRating.REITERATE, AnalystRating.actionOf("reit"))
        assertEquals(AnalystRating.REITERATE, AnalystRating.actionOf("main"))
        assertEquals(AnalystRating.INIT, AnalystRating.actionOf("init"))
        assertEquals(AnalystRating.UPGRADE, AnalystRating.actionOf("Upgrade"))
    }

    // --------------------------------------------------------- explainer cover

    /**
     * THE CONTRACT BETWEEN THE CATALOGUE AND THE EXPLAINER.
     *
     * The Stats tab draws whatever is in [MetricCatalog], and every row it draws has an "i"
     * that opens [Explain]. If a metric were added to the catalogue without prose, that "i"
     * would open a generic paragraph and the whole promise of the feature would be quietly
     * broken for that one number. This is the check that makes it impossible.
     */
    @Test
    fun `every catalogued metric has a real explanation`() {
        val missing = MetricCatalog.keys.filterNot { Explain.covers(it) }
        assertTrue("metrics with no explanation: $missing", missing.isEmpty())
    }

    /** And the reverse: nothing a parser writes may be missing from the catalogue. */
    @Test
    fun `every key the parsers produce is in the catalogue`() {
        val produced = HashSet<String>()
        produced += yahoo("AAPL").values.keys
        produced += yahoo("NVDA").values.keys
        produced += FundamentalsFeed.parseFinviz("AAPL", fixture("finviz_AAPL.html")).values.keys
        val v = HashMap<String, Double>()
        FundamentalsFeed.parseNasdaqSummary(fixture("nasdaq_summary_FIVE.json"), v, HashMap())
        produced += v.keys
        val orphans = produced - MetricCatalog.keys
        assertTrue("parsed keys with no catalogue entry: $orphans", orphans.isEmpty())
    }

    @Test
    fun `explanations are complete prose, not placeholders`() {
        val f = yahoo("AAPL")
        for (key in MetricCatalog.keys) {
            val e = Explain.of(key, f, 320.0)
            assertTrue("$key has no title", e.title.isNotBlank())
            assertTrue("$key plain text too short", e.plain.length > 40)
            assertTrue("$key scale text too short", e.scale.length > 40)
            assertTrue("$key short-term text too short", e.shortTerm.length > 30)
            assertTrue("$key long-term text too short", e.longTerm.length > 30)
            assertTrue("$key has no live read", e.read.isNotBlank())
        }
    }

    /** A metric with no value must explain the absence, not read a zero back. */
    @Test
    fun `a missing value explains itself instead of reading zero`() {
        val e = Explain.of("peTrailing", Fundamentals("X"), 0.0)
        assertEquals(Verdict.UNKNOWN, e.verdict)
        assertTrue(e.read.contains("No value"))
        assertFalse(e.read.contains("0.00"))
    }

    /** The live read has to actually change with the number, or it is decoration. */
    @Test
    fun `the live read and verdict track the value`() {
        fun pe(v: Double) = Explain.of(
            "peTrailing", Fundamentals("X", values = mapOf("peTrailing" to v)), 100.0
        )
        assertEquals(Verdict.BAD, pe(-5.0).verdict)
        assertEquals(Verdict.NEUTRAL, pe(18.0).verdict)
        assertEquals(Verdict.MIXED, pe(80.0).verdict)
        assertTrue(pe(18.0).read.contains("18"))

        fun payout(v: Double) = Explain.of(
            "payoutRatio", Fundamentals("X", values = mapOf("payoutRatio" to v)), 0.0
        )
        assertEquals(Verdict.GOOD, payout(0.25).verdict)
        // Paying out more than it earns is the classic warning before a dividend cut.
        assertEquals(Verdict.BAD, payout(1.4).verdict)

        fun debt(v: Double) = Explain.of(
            "debtToEquity", Fundamentals("X", values = mapOf("debtToEquity" to v)), 0.0
        )
        assertEquals(Verdict.GOOD, debt(30.0).verdict)
        assertEquals(Verdict.BAD, debt(400.0).verdict)
    }

    @Test
    fun `analyst topics explain themselves with live numbers`() {
        val f = yahoo("AAPL")
        val c = Explain.analystTopic(Explain.TOPIC_CONSENSUS, f, 320.0)
        assertTrue(c.read.contains("analysts"))
        assertTrue(c.plain.length > 60)
        val t = Explain.analystTopic(Explain.TOPIC_TARGET, f, 320.0)
        assertTrue(t.read.isNotBlank())
        assertTrue(t.longTerm.isNotBlank())
        val r = Explain.analystTopic(Explain.TOPIC_RATINGS, f, 320.0)
        assertTrue(r.read.isNotBlank())
        // With no data at all the topics must say so rather than inventing a consensus.
        val none = Explain.analystTopic(Explain.TOPIC_CONSENSUS, Fundamentals("X"), 0.0)
        assertEquals(Verdict.UNKNOWN, none.verdict)
    }

    // -------------------------------------------------------------- formatting

    @Test
    fun `each unit prints the way that unit should read`() {
        assertEquals("$4.67T", formatMetric(MetricUnit.MONEY, 4.6697e12))
        assertEquals("$128.93B", formatMetric(MetricUnit.MONEY, 1.2893e11))
        assertEquals("-$1.00B", formatMetric(MetricUnit.MONEY, -1e9))
        assertEquals("36.68", formatMetric(MetricUnit.RATIO, 36.68))
        assertEquals("78.45%", formatMetric(MetricUnit.PERCENT, 78.445))
        assertEquals("27.62%", formatMetric(MetricUnit.FRACTION, 0.27619))
        assertEquals("14.59B", formatMetric(MetricUnit.COUNT, 1.4594e10))
        assertEquals("-", formatMetric(MetricUnit.DATE, 0.0))
    }

    @Test
    fun `consensus upside is measured against the live price`() {
        val c = Consensus(targetMean = 330.0)
        assertEquals(10.0, c.upsidePct(300.0)!!, 0.001)
        assertNull(c.upsidePct(0.0))
        assertEquals("Strong buy", Consensus(mean = 1.2).meanLabel)
        assertEquals("Hold", Consensus(mean = 3.0).meanLabel)
        assertEquals("Sell", Consensus(mean = 4.0).meanLabel)
        assertEquals("", Consensus(mean = 0.0).meanLabel)
    }
    // ---- A RATINGS FALLBACK BRINGS RATINGS, NOT A SCRAPED VALUATION (full-tests audit S-L9).
    @Test fun `the ratings path keeps only the analyst half of a fallback provider`() {
        val scraped = com.tj.portfolio.data.Fundamentals(
            symbol = "ABC",
            values = mapOf("peForward" to 99.0),
            texts = mapOf("sector" to "Scraped"),
            ratings = listOf(com.tj.portfolio.data.AnalystRating(firm = "F", date = 1L, toGrade = "Buy"))
        )
        val kept = com.tj.portfolio.net.FundamentalsFeed.ratingsOnly(scraped)
        assertEquals(1, kept.ratings.size)
        assertTrue(kept.values.isEmpty())
        assertTrue(kept.texts.isEmpty())
        // And merged over the core numbers, as mergeFundamentals does, the API value survives.
        val core = com.tj.portfolio.data.Fundamentals(symbol = "ABC", values = mapOf("peForward" to 21.0))
        val merged = com.tj.portfolio.data.Fundamentals.merge(kept, core)
        assertEquals(21.0, merged.values["peForward"]!!, 0.0)
    }
}
