package com.tj.portfolio

import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.HoldingsJson
import com.tj.portfolio.net.HoldingsFeed
import com.tj.portfolio.ui.DetailTab
import com.tj.portfolio.ui.visibleTabs
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
 * ROUND 58: what an ETF holds.
 *
 * The fixtures carry every shape Yahoo actually sends and that a reasonable-looking parser
 * gets wrong - the same list that has caught this project before:
 *
 *   - most numbers are `{"raw": 0.0812, "fmt": "8.12%"}`;
 *   - some are a BARE number (`stockPosition` here);
 *   - "not reported" is an EMPTY OBJECT, and reading it as 0.0 would draw a holding at 0% of
 *     the fund and a sector at 0% as though those were facts;
 *   - `sectorWeightings` elements are ONE-KEY objects whose key IS the sector name, not
 *     `{name, weight}` pairs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldingsTest {

    private fun result(name: String): JSONObject =
        JSONObject(
            javaClass.classLoader!!.getResourceAsStream(name)!!
                .bufferedReader().use { it.readText() }
        ).getJSONObject("quoteSummary").getJSONArray("result").getJSONObject(0)

    private val etf: FundHoldings by lazy {
        HoldingsFeed.parse("TESTF", result("yahoo_topholdings_etf.json"))
    }

    // --------------------------------------------------------------- the holdings

    @Test
    fun `an etf parses into holdings with weights`() {
        assertTrue(etf.isFund)
        assertEquals("ETF", etf.quoteType)
        assertEquals(10, etf.holdings.size)
        assertEquals("NVDA", etf.holdings.first().symbol)
        assertEquals(0.0812, etf.holdings.first().weight, 1e-9)
        assertEquals("NVIDIA Corporation", etf.holdings.first().name)
    }

    /** The empty-object trap. A holding the provider could not price is not a 0% holding. */
    @Test
    fun `a holding with no reported weight is dropped, not shown at zero percent`() {
        assertTrue(
            "an unpriced holding was kept",
            etf.holdings.none { it.symbol == "ZZZZ" }
        )
        assertTrue(etf.holdings.all { it.weight > 0.0 })
    }

    @Test
    fun `holdings come back largest first`() {
        val w = etf.holdings.map { it.weight }
        assertEquals(w.sortedDescending(), w)
    }

    /**
     * The honest number on the tab header. It has to be the real sum, because the whole
     * point of printing it is telling the reader how much of the fund they are NOT seeing.
     */
    @Test
    fun `coverage is the sum of the listed weights`() {
        assertEquals(etf.holdings.sumOf { it.weight }, etf.coverage, 1e-12)
        assertTrue("coverage should be a fraction, not a percent", etf.coverage < 1.0)
    }

    // ---------------------------------------------------------------- the sectors

    @Test
    fun `sector weightings read the key as the sector name`() {
        assertTrue(etf.sectors.isNotEmpty())
        val top = etf.sectors.first()
        assertEquals("Technology", top.sector)
        assertEquals(0.3412, top.weight, 1e-9)
        assertTrue(
            "an underscore survived into a displayed name",
            etf.sectors.none { it.sector.contains('_') }
        )
        assertTrue(etf.sectors.any { it.sector == "Financial services" })
        // real_estate was reported as an empty object - "not reported", so absent.
        assertTrue(etf.sectors.none { it.sector.startsWith("Real") })
        val w = etf.sectors.map { it.weight }
        assertEquals(w.sortedDescending(), w)
    }

    @Test
    fun `prettySector turns a provider key into something readable`() {
        assertEquals("Consumer cyclical", HoldingsFeed.prettySector("consumer_cyclical"))
        assertEquals("Energy", HoldingsFeed.prettySector("energy"))
    }

    // -------------------------------------------------------------- the asset mix

    @Test
    fun `the asset mix reads a bare number as well as a wrapped one`() {
        assertEquals(0.9954, etf.stockPct, 1e-9)   // bare
        assertEquals(0.0041, etf.cashPct, 1e-9)    // {raw, fmt}
        assertEquals(0.0, etf.bondPct, 1e-9)
        assertTrue(etf.hasAssetMix)
    }

    /**
     * Yahoo splits the residual three ways and the screen shows one "Other" line, so they
     * are summed - otherwise the mix visibly fails to add up to the fund.
     */
    @Test
    fun `other, preferred and convertible are summed into one residual`() {
        assertEquals(0.0003 + 0.0001, etf.otherPct, 1e-9)
        val total = etf.stockPct + etf.bondPct + etf.cashPct + etf.otherPct
        assertEquals(1.0, total, 0.002)
    }

    @Test
    fun `the profile fields are read when present`() {
        assertEquals("Large Blend", etf.category)
        assertEquals("Test Funds", etf.family)
        assertEquals(0.0003, etf.expenseRatio, 1e-9)
    }

    // ------------------------------------------------------------- ordinary shares

    /**
     * The verdict that decides whether the tab exists at all. It must be read from the
     * provider's `quoteType`, never guessed from the ticker.
     */
    @Test
    fun `an ordinary share is not a fund and has nothing to list`() {
        val eq = HoldingsFeed.parse("TESTS", result("yahoo_topholdings_equity.json"))
        assertFalse(eq.isFund)
        assertEquals("EQUITY", eq.quoteType)
        assertTrue(eq.holdings.isEmpty())
        assertTrue(eq.sectors.isEmpty())
        assertFalse(eq.hasAssetMix)
        assertTrue(eq.isEmpty)
    }

    /** A populated topHoldings is fund enough on its own, for listings Yahoo does not type. */
    @Test
    fun `holdings without a quoteType still count as a fund`() {
        val o = JSONObject(
            """{"topHoldings":{"holdings":[
                 {"symbol":"AAA","holdingName":"A","holdingPercent":{"raw":0.5}}]}}"""
        )
        assertTrue(HoldingsFeed.parse("X", o).isFund)
    }

    @Test
    fun `an empty response is not a fund and does not throw`() {
        val h = HoldingsFeed.parse("X", JSONObject("{}"))
        assertFalse(h.isFund)
        assertTrue(h.isEmpty)
    }

    // ------------------------------------------------------------ tab visibility

    @Test
    fun `the holdings tab is hidden for anything that is not a fund`() {
        assertFalse(visibleTabs(false).contains(DetailTab.HOLDINGS))
        assertTrue(visibleTabs(true).contains(DetailTab.HOLDINGS))
        assertEquals(DetailTab.entries.size, visibleTabs(true).size)
        assertEquals(DetailTab.entries.size - 1, visibleTabs(false).size)
        // Every other tab is present in both, and in the same order.
        assertEquals(
            DetailTab.entries.filter { it != DetailTab.HOLDINGS },
            visibleTabs(false)
        )
    }

    /**
     * The tab indicator is driven by `tabs.indexOf(tab)`, so the ORDER of the visible list
     * has to match the order they are rendered in. Overview must stay first: it is what a
     * detail screen opens on.
     */
    @Test
    fun `overview is first in both tab sets`() {
        assertEquals(DetailTab.OVERVIEW, visibleTabs(true).first())
        assertEquals(DetailTab.OVERVIEW, visibleTabs(false).first())
        assertEquals(DetailTab.HOLDINGS, visibleTabs(true)[1])
    }

    // --------------------------------------------------------------------- codec

    @Test
    fun `holdings survive a round trip through the on-disk codec`() {
        val back = HoldingsJson.decode(HoldingsJson.encode(etf))
        assertNotNull(back)
        back!!
        assertEquals(etf.symbol, back.symbol)
        assertEquals(etf.isFund, back.isFund)
        assertEquals(etf.quoteType, back.quoteType)
        assertEquals(etf.holdings.size, back.holdings.size)
        assertEquals(etf.holdings.first().symbol, back.holdings.first().symbol)
        assertEquals(etf.holdings.first().weight, back.holdings.first().weight, 1e-12)
        assertEquals(etf.sectors.size, back.sectors.size)
        assertEquals(etf.sectors.first().sector, back.sectors.first().sector)
        assertEquals(etf.category, back.category)
        assertEquals(etf.expenseRatio, back.expenseRatio, 1e-12)
        assertEquals(etf.stockPct, back.stockPct, 1e-12)
        assertEquals(etf.otherPct, back.otherPct, 1e-12)
        assertEquals(etf.fetched, back.fetched)
    }

    @Test
    fun `an unreadable row decodes to null so it reads as a cache miss`() {
        assertNull(HoldingsJson.decode("not json"))
        assertNull(HoldingsJson.decode(""))
    }

    /**
     * "This is an ordinary share" is a real answer with a real lifetime, and caching it is
     * what stops the app asking Yahoo the same dead question on every screen open. So the
     * empty case has to survive the codec intact.
     */
    @Test
    fun `a not-a-fund verdict survives the codec`() {
        val eq = HoldingsFeed.parse("TESTS", result("yahoo_topholdings_equity.json"))
        val back = HoldingsJson.decode(HoldingsJson.encode(eq))!!
        assertFalse(back.isFund)
        assertEquals("EQUITY", back.quoteType)
        assertTrue(back.isEmpty)
    }

    // ----------------------------------------------------------------- staleness

    @Test
    fun `holdings go stale after their ttl and a never-fetched row always is`() {
        val now = 1_000_000_000L
        assertFalse(FundHoldings("X", fetched = now - 3_600_000L).stale(now))
        assertTrue(FundHoldings("X", fetched = now - 13 * 3_600_000L).stale(now))
        assertTrue(FundHoldings("X").stale(now))
    }

    // ------------------------------------------------------------- the num() rule

    @Test
    fun `num reads all three shapes and refuses the empty object`() {
        val o = JSONObject(
            """{"wrapped":{"raw":0.5,"fmt":"50%"},"bare":0.25,"missing":{},
                "nulled":null,"text":"0.125","bad":{"fmt":"n/a"}}"""
        )
        assertEquals(0.5, HoldingsFeed.num(o, "wrapped")!!, 1e-12)
        assertEquals(0.25, HoldingsFeed.num(o, "bare")!!, 1e-12)
        assertEquals(0.125, HoldingsFeed.num(o, "text")!!, 1e-12)
        assertNull(HoldingsFeed.num(o, "missing"))
        assertNull(HoldingsFeed.num(o, "nulled"))
        assertNull(HoldingsFeed.num(o, "bad"))
        assertNull(HoldingsFeed.num(o, "absent"))
        assertNull(HoldingsFeed.num(null, "wrapped"))
    }
}
