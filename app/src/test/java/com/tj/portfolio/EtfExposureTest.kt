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

    /**
     * ROUND 66 AUDIT (E2). The first version of this file tested the US size ladder BEFORE the
     * region words, so " small cap " matched and " eafe " was never reached: a developed-markets
     * small-cap fund was handed the key "US small cap" and merged with a US one. The loser
     * vanished from the page under a card claiming they were the same exposure - the exact
     * over-grouping failure this file's own note says is the one that matters.
     */
    @Test fun `a non-US fund is never merged with a US one`() {
        val usSmall = EtfExposure.keyOf("iShares Core S&P Small-Cap ETF")
        val eafeSmall = EtfExposure.keyOf("iShares MSCI EAFE Small-Cap ETF")
        val worldSmall = EtfExposure.keyOf("Vanguard FTSE All-World ex-US Small-Cap ETF")
        assertTrue("all three should be recognised",
            listOf(usSmall, eafeSmall, worldSmall).all { it != null })
        assertNotEquals("EAFE small-cap is not US small-cap", usSmall, eafeSmall)
        assertNotEquals("all-world ex-US small-cap is not US small-cap", usSmall, worldSmall)
        assertNotEquals("and the two non-US ones are different regions", eafeSmall, worldSmall)
    }

    @Test fun `a region's own size bands stay separate`() {
        val eafe = EtfExposure.keyOf("iShares MSCI EAFE ETF")
        val eafeSmall = EtfExposure.keyOf("iShares MSCI EAFE Small-Cap ETF")
        assertNotEquals("EAFE large and EAFE small are not one decision", eafe, eafeSmall)
    }

    /** Bullion holds metal; miners hold companies that dig it up. Not one decision. */
    @Test fun `gold bullion is not merged with gold miners`() {
        val bullion = EtfExposure.keyOf("SPDR Gold Shares")
        val miners = EtfExposure.keyOf("VanEck Gold Miners ETF")
        assertTrue("bullion funds should group with each other", bullion != null)
        assertEquals(bullion, EtfExposure.keyOf("iShares Gold Trust"))
        assertNotEquals("a miner is not bullion", bullion, miners)
    }

    /**
     * SGOV holds 0-3 month bills; TLT holds 20+ year bonds. Both are "Treasuries" and one is
     * a cash substitute while the other is a duration bet.
     */
    @Test fun `Treasury funds are separated by maturity`() {
        val bills = EtfExposure.keyOf("iShares 0-3 Month Treasury Bond ETF")
        val long = EtfExposure.keyOf("iShares 20+ Year Treasury Bond ETF")
        val mid = EtfExposure.keyOf("iShares 7-10 Year Treasury Bond ETF")
        assertTrue(listOf(bills, long, mid).all { it != null })
        assertEquals("three different decisions", 3, listOf(bills, long, mid).toSet().size)
        // A fund that does not state a band is left alone rather than guessed at.
        assertNull(EtfExposure.keyOf("iShares U.S. Treasury Bond ETF"))
    }

    @Test fun `bond funds are separated by what they actually hold`() {
        val t = EtfExposure.keyOf("iShares 20+ Year Treasury Bond ETF")
        val h = EtfExposure.keyOf("iShares iBoxx High Yield Corporate Bond ETF")
        val a = EtfExposure.keyOf("Vanguard Total Bond Market ETF")
        val m = EtfExposure.keyOf("Vanguard Tax-Exempt Municipal Bond ETF")
        assertTrue(listOf(t, h, a, m).all { it != null })
        assertEquals("a Treasury fund and a junk-bond fund are not the same decision",
            4, listOf(t, h, a, m).toSet().size)
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

    // ------------------------------------------- ROUND 66 AUDIT: ETF-3 and ETF-7

    /**
     * ETF-3. A WORLD BOND FUND IS NOT A WORLD STOCK FUND.
     *
     * The region ladder matched on the whole name with no idea what the fund held, so
     * " total international " matched inside "Vanguard Total INTERNATIONAL BOND Index Fund"
     * and " total world " inside "Vanguard Total WORLD BOND ETF". Both were keyed as global
     * EQUITY, de-duplicated against VT and ACWI, and - because VT scores highest of that
     * group - DELETED from the list, under a card saying they were the same exposure.
     * The bond screen is one of the three lists fetched, so both are really in the universe.
     */
    @Test fun `a world bond fund is never grouped with world equity`() {
        val equity = EtfExposure.keyOf("Vanguard Total World Stock Index Fund ETF Shares")
        assertTrue("a world stock fund should still group", equity != null)
        for (bond in listOf(
            "Vanguard Total International Bond Index Fund ETF Shares",
            "Vanguard Total World Bond ETF"
        )) {
            assertNotEquals(
                "\"$bond\" was keyed as world EQUITY",
                equity, EtfExposure.keyOf(bond)
            )
        }
    }

    /** The same class of error one branch further down: IAGG is not BND. */
    @Test fun `an international aggregate bond fund is not the US aggregate`() {
        val us = EtfExposure.keyOf("Vanguard Total Bond Market ETF")
        assertTrue("the US aggregate funds should still group", us != null)
        assertEquals(us, EtfExposure.keyOf("iShares Core U.S. Aggregate Bond ETF"))
        assertNotEquals(
            "IAGG holds hedged ex-US debt and was merged into the US aggregate group",
            us, EtfExposure.keyOf("iShares Core International Aggregate Bond ETF")
        )
    }

    /** And the US aggregate funds must not have stopped grouping in the process. */
    @Test fun `the US aggregate bond funds are still one decision`() {
        val k = EtfExposure.keyOf("Vanguard Total Bond Market ETF")
        assertTrue(k != null)
        assertEquals(k, EtfExposure.keyOf("iShares Core U.S. Aggregate Bond ETF"))
        assertEquals(k, EtfExposure.keyOf("SPDR Portfolio Aggregate Bond ETF"))
    }

    /**
     * ETF-7. "THE WHOLE WORLD" AND "THE WORLD EXCEPT AMERICA" ARE OPPOSITE ANSWERS.
     *
     * One key covered both. VT and ACWI hold about 60% United States; VXUS, IXUS, VEU and
     * ACWX hold none at all - which is exactly what somebody who already owns VOO is asking
     * for. Merged, VT wins the group on the strength of that US weight and every ex-US fund
     * is deleted as a duplicate of it, so the reader who wanted the rest of the world is
     * handed a fund that is more than half the index they already hold.
     */
    @Test fun `all-world and all-world-ex-US are not the same decision`() {
        val inclUs = EtfExposure.keyOf("Vanguard Total World Stock Index Fund ETF Shares")
        val exUs = EtfExposure.keyOf("Vanguard Total International Stock Index Fund ETF Shares")
        assertTrue(inclUs != null && exUs != null)
        assertNotEquals("a world fund WITH the US was merged with one without it", inclUs, exUs)

        // Each half still groups internally - the point is two groups, not none.
        assertEquals(inclUs, EtfExposure.keyOf("iShares MSCI ACWI ETF"))
        assertEquals(exUs, EtfExposure.keyOf("iShares Core MSCI Total International Stock ETF"))
        assertEquals(exUs, EtfExposure.keyOf("iShares MSCI ACWI ex U.S. ETF"))
    }

    /**
     * The order of the two ex-US tests is load-bearing: "Vanguard FTSE All-World ex-US Index
     * Fund" contains BOTH " all world " and " ex us ", and ex-US is the correct reading.
     */
    @Test fun `a name containing both spellings reads as ex-US`() {
        val exUs = EtfExposure.keyOf("Vanguard Total International Stock Index Fund ETF Shares")
        assertEquals(exUs, EtfExposure.keyOf("Vanguard FTSE All-World ex-US Index Fund ETF Shares"))
    }

    /**
     * CHANGED IN THE 2026-09-24 FULL TEST (S-8): every fund that lost its place is named. The
     * old cap of three let a fourth member vanish with no mention anywhere, although the
     * sources note promises "the rest are named on its card". What keeps the line short now is
     * that strategy products (Top 50, High Income, High Beta ...) no longer join a plain
     * index's group, so a real group is three to five funds.
     */
    @Test fun `every alternative is named, none silently dropped`() {
        val many = (1..9).map { F("S$it", "Issuer $it S&P 500 ETF") }
        val out = EtfExposure.dedupe(many, name = { it.name }, symbol = { it.sym })
        assertEquals(1, out.size)
        assertEquals((2..9).map { "S$it" }, out[0].second)
    }
}
