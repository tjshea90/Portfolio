package com.tj.portfolio

import com.tj.portfolio.net.EtfExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE FUND PER EXPOSURE (Round 66).
 *
 * TJ: *"I'm going to buy some of the top ETFs... tell me healthy etfs that perform well and
 * are strong buys, listed from best at the top."*
 *
 * A page of ten ranked funds can easily be five decisions wearing ten tickers: VOO, IVV and
 * SPLG hold the same index within a basis point of each other on cost, so they score within a
 * point or two and arrive as three consecutive rows - pushing out the funds that would have
 * been the rest of the page. Schwab's own "How to evaluate ETFs" puts exposure first; Saxo's
 * guide says to compare funds with identical exposure against each other rather than ranking
 * every broad index fund against every other. So the list groups by exposure and keeps the
 * best of each group.
 *
 * THE FAILURE THAT MATTERS IS OVER-GROUPING - merging two genuinely different funds hides a
 * real choice from someone about to spend money, silently. Most of these tests are therefore
 * about what must NOT be grouped.
 */
class EtfExposureTest {

    // ------------------------------------------------------- what must group together

    @Test fun `the S&P 500 funds are one decision`() {
        val k = EtfExposure.keyOf("Vanguard S&P 500 ETF")
        assertTrue("a plain S&P 500 fund was not recognised", k != null)
        assertEquals(k, EtfExposure.keyOf("iShares Core S&P 500 ETF"))
        assertEquals(k, EtfExposure.keyOf("SPDR Portfolio S&P 500 ETF"))
        assertEquals(k, EtfExposure.keyOf("SPDR S&P 500 ETF Trust"))
    }

    @Test fun `the total-market funds are one decision`() {
        val k = EtfExposure.keyOf("Vanguard Total Stock Market ETF")
        assertTrue(k != null)
        assertEquals(k, EtfExposure.keyOf("iShares Core S&P Total U.S. Stock Market ETF"))
        assertEquals(k, EtfExposure.keyOf("Schwab U.S. Broad Market ETF"))
    }

    @Test fun `the Nasdaq-100 funds are one decision`() {
        val k = EtfExposure.keyOf("Invesco QQQ Trust")
        assertTrue(k != null)
        assertEquals(k, EtfExposure.keyOf("Invesco NASDAQ 100 ETF"))
    }

    // ------------------------------------------------- what must NOT group together

    @Test fun `a growth or value tilt is a different fund`() {
        // These hold roughly half the index each. Collapsing them into the plain fund would
        // silently remove the actual choice being made.
        assertNull(EtfExposure.keyOf("Vanguard S&P 500 Growth ETF"))
        assertNull(EtfExposure.keyOf("iShares S&P 500 Value ETF"))
        assertNull(EtfExposure.keyOf("Invesco S&P 500 Equal Weight ETF"))
        assertNull(EtfExposure.keyOf("JPMorgan Nasdaq Equity Premium Income ETF"))
    }

    @Test fun `different sizes and regions are different decisions`() {
        val keys = listOf(
            "Vanguard S&P 500 ETF",
            "iShares Russell 2000 ETF",
            "Vanguard Mid-Cap ETF",
            "Vanguard FTSE Emerging Markets ETF",
            "iShares MSCI EAFE ETF",
            "Vanguard Total World Stock ETF"
        ).map { EtfExposure.keyOf(it) }
        assertEquals("every one of these should be recognised", 6, keys.count { it != null })
        assertEquals("and every one distinct", 6, keys.toSet().size)
    }

    @Test fun `bond funds are separated by what they actually hold`() {
        val t = EtfExposure.keyOf("iShares 20+ Year Treasury Bond ETF")
        val h = EtfExposure.keyOf("iShares iBoxx High Yield Corporate Bond ETF")
        val a = EtfExposure.keyOf("Vanguard Total Bond Market ETF")
        val i = EtfExposure.keyOf("Schwab U.S. TIPS ETF")
        assertTrue(listOf(t, h, a, i).all { it != null })
        assertEquals("a Treasury fund and a junk-bond fund are not the same decision",
            4, listOf(t, h, a, i).toSet().size)
    }

    @Test fun `an unrecognised name is left alone rather than guessed at`() {
        assertNull(EtfExposure.keyOf("First Trust Cloud Computing ETF"))
        assertNull(EtfExposure.keyOf("Global X Uranium ETF"))
        assertNull(EtfExposure.keyOf(""))
    }

    // ------------------------------------------------------------------ the dedupe

    private data class F(val sym: String, val name: String)

    private fun run(vararg f: F) =
        EtfExposure.dedupe(f.toList(), name = { it.name }, symbol = { it.sym })

    @Test fun `the highest-ranked fund of a group survives and names the others`() {
        val out = run(
            F("VOO", "Vanguard S&P 500 ETF"),
            F("IVV", "iShares Core S&P 500 ETF"),
            F("SPLG", "SPDR Portfolio S&P 500 ETF"),
            F("QQQ", "Invesco QQQ Trust")
        )
        assertEquals("three S&P 500 funds should collapse to one", 2, out.size)
        assertEquals("VOO", out[0].first.sym)
        assertEquals("the alternatives must be named, not silently dropped",
            listOf("IVV", "SPLG"), out[0].second)
        assertEquals("QQQ", out[1].first.sym)
        assertTrue(out[1].second.isEmpty())
    }

    @Test fun `order is preserved - this decides placement, it does not re-rank`() {
        val out = run(
            F("QQQ", "Invesco QQQ Trust"),
            F("VOO", "Vanguard S&P 500 ETF"),
            F("IVV", "iShares Core S&P 500 ETF")
        )
        assertEquals(listOf("QQQ", "VOO"), out.map { it.first.sym })
    }

    @Test fun `ungrouped funds are all kept`() {
        val out = run(
            F("SKYY", "First Trust Cloud Computing ETF"),
            F("URA", "Global X Uranium ETF"),
            F("ARKK", "ARK Innovation ETF")
        )
        assertEquals(3, out.size)
        assertTrue(out.all { it.second.isEmpty() })
    }

    @Test fun `the list of alternatives is bounded`() {
        val many = (1..9).map { F("S$it", "Issuer $it S&P 500 ETF") }
        val out = EtfExposure.dedupe(many, name = { it.name }, symbol = { it.sym })
        assertEquals(1, out.size)
        assertTrue("naming eleven alternatives is a wall of text", out[0].second.size <= 3)
    }
}
