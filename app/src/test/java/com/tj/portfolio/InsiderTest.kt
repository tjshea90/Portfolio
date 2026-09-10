package com.tj.portfolio

import com.tj.portfolio.data.InsiderFiling
import com.tj.portfolio.net.Form4
import com.tj.portfolio.net.Insider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Form 4 pipeline, against filings and listings pulled from the live SEC service on
 * 5 September 2026 and checked in beside this file.
 *
 * WHY REAL FILINGS RATHER THAN HAND-WRITTEN XML. Every bug this round fixed was a difference
 * between what the SEC actually publishes and what the app assumed it published - EDGAR's
 * entry title losing the insider's name, `type=4` returning 424B5 prospectuses, `<isOfficer>`
 * arriving as `true` from one filing agent and `1` from another. A fixture I wrote myself
 * would have agreed with my assumptions and caught none of it.
 *
 * Robolectric is here only for `org.json`, which the cache round-trip uses.
 */
@RunWith(RobolectricTestRunner::class)
class InsiderTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().use { it.readText() }

    private fun parse(file: String, symbol: String, acc: String = "0001-26-000001") =
        Form4.parse(symbol, acc, "https://sec.gov/x", 1_757_000_000_000L, res(file))

    // ------------------------------------------------------------------ listing

    /**
     * THE 424B5 TRAP. EDGAR's `type=` filter is a PREFIX match, so a request for Form 4s also
     * returns 424B5 prospectuses - the INTC listing has two. Their full submissions are half
     * a megabyte each; fetching them on a phone to discover they are the wrong form is the
     * whole reason the exact form type is checked in the listing.
     */
    @Test fun listingKeepsOnlyRealForm4s() {
        val xml = res("edgar_listing_intc.xml")
        assertTrue("fixture must contain the 424B5 noise", xml.contains("424B5"))
        val refs = Insider.parseListing(xml)
        assertTrue("should find the Form 4", refs.isNotEmpty())
        assertTrue(
            "424B5 must never reach the fetcher",
            refs.none { it.docUrl.contains("000119312526345221") }
        )
    }

    @Test fun listingIsNewestFirstWithDistinctStamps() {
        val refs = Insider.parseListing(res("edgar_listing_nvda.xml"))
        assertTrue(refs.size >= 3)
        assertEquals(refs.map { it.filedAt }.sortedDescending(), refs.map { it.filedAt })
        // The precise <updated> stamp, not the day-resolution <filing-date>. Two filings
        // accepted on the same day must not collapse onto one timestamp - that is what used
        // to flatten the ordering and produce duplicate list keys.
        val sameDay = refs.filter { it.filedAt > 0 }
        assertEquals(sameDay.size, sameDay.map { it.filedAt }.distinct().size)
        assertTrue(refs.all { it.accession.isNotBlank() })
        assertEquals(refs.size, refs.map { it.accession }.distinct().size)
    }

    @Test fun documentUrlIsDerivedFromTheIndexPage() {
        assertEquals(
            "https://www.sec.gov/Archives/edgar/data/1045810/000119764726000009/" +
                "0001197647-26-000009.txt",
            Insider.docUrlFor(
                "https://www.sec.gov/Archives/edgar/data/1045810/000119764726000009/" +
                    "0001197647-26-000009-index.htm",
                "0001197647-26-000009"
            )
        )
    }

    // ---------------------------------------------------------------- the filing

    /**
     * The case that makes the whole feature worth having: an unplanned open-market purchase
     * by a chief executive. Intel's CEO bought 105,263 shares at $95.00 on 11 Aug 2026.
     */
    @Test fun ceoOpenMarketPurchase() {
        val f = parse("form4_buy_ceo.txt", "INTC")!!
        assertEquals("INTC", f.symbol)
        assertEquals("CEO", f.role)
        assertEquals("Lip Bu Tan", f.person)
        assertEquals(Form4.BUY, f.action)
        assertEquals("P", f.headlineTrade!!.code)
        assertEquals(105_263.0, f.shares, 0.001)
        assertEquals(95.0, f.price, 0.001)
        assertEquals(9_999_985.0, f.value, 1.0)
        assertFalse(f.planned)
        assertTrue(f.isTrade)
        assertTrue("an unplanned P is the signal this tab exists for", f.isDiscretionary)
        assertEquals("CEO bought 105,263 shares — $10.00M", f.headline())
        assertTrue(f.subtitle().startsWith("Lip Bu Tan · at \$95.00"))
    }

    /**
     * The case that must NOT be presented as a decision. Apple's general counsel sold under a
     * Rule 10b5-1 plan adopted four months earlier.
     */
    @Test fun plannedSaleIsFlaggedAndExcludedFromOpenMarket() {
        val f = parse("form4_sell_planned.txt", "AAPL")!!
        assertEquals(Form4.SELL, f.action)
        assertEquals("Jennifer Newstead", f.person)
        assertEquals("SVP, GC and Government Affairs", f.role)
        assertTrue("<aff10b5One>true</aff10b5One> must be read", f.planned)
        assertTrue(f.isTrade)
        assertFalse("a scheduled sale is not a decision made today", f.isDiscretionary)
        assertEquals(1_439.0, f.shares, 0.001)
        assertTrue(f.headline().startsWith("SVP, GC and Government Affairs sold 1,439 shares"))
    }

    /** `1`/`0` and `true`/`false` are both live in the wild, from different filing agents. */
    @Test fun bothBooleanSpellingsAreUnderstood() {
        assertTrue(parse("form4_sell_planned.txt", "AAPL")!!.role.startsWith("SVP")) // true/false
        assertEquals("CEO", parse("form4_buy_ceo.txt", "INTC")!!.role)               // 1/0
        assertEquals("Director", parse("form4_grant.txt", "F")!!.role)               // 1/0
    }

    /**
     * A grant is compensation, not a purchase. It has to be classified as such or the tab
     * fills up with "the board bought stock" every vesting season.
     */
    @Test fun grantIsNotATrade() {
        val f = parse("form4_grant.txt", "F")!!
        assertEquals(Form4.GRANT, f.action)
        assertFalse(f.isTrade)
        assertFalse(f.isDiscretionary)
        // Named by what it actually is. "Director was granted 1,594 shares" would be wrong -
        // these are Ford Stock Units that convert later, not stock held today.
        assertEquals("Director was granted 1,594 Ford Stock Units", f.headline())
    }

    /**
     * ONE DECISION, SEVEN LINES. A broker filling a large sale across price bands reports each
     * band separately - this NVDA filing has seven S lines. Left unmerged they appeared as
     * seven separate sales, which overstates insider activity by a factor of seven on exactly
     * the largest trades.
     */
    @Test fun samePriceBandsMergeAtTheWeightedAverage() {
        val f = parse("form4_multi.txt", "NVDA")!!
        assertEquals(7, f.trades.size)
        assertEquals(Form4.SELL, f.action)
        assertEquals(1_848_501.0, f.shares, 0.5)
        val expected = f.trades.sumOf { it.shares * it.price } / f.trades.sumOf { it.shares }
        assertEquals(expected, f.price, 0.0001)
        assertTrue("price must sit inside the band", f.price > 220.0 && f.price < 227.0)
        // DOCUMENT ORDER, not latest date: four of the seven lines share 2 Sept, and picking
        // by date returns whichever came first - a holding several hundred thousand shares
        // too high.
        assertEquals(3_358_770.0, f.sharesAfter, 0.5)
        assertTrue(f.headline(), f.headline().startsWith("Director sold 1.85M shares — $4"))
    }

    /**
     * The checkbox only exists on filings made since the SEC's December 2022 amendments, and
     * some filing agents still omit it. A footnote naming the rule has to count, or a planned
     * sale from such an agent is presented as a decision the insider made that week.
     */
    @Test fun aPlanNamedOnlyInAFootnoteStillCounts() {
        val doc = """
            <ownershipDocument><documentType>4</documentType>
            <issuerTradingSymbol>ZZZ</issuerTradingSymbol>
            <reportingOwner><reportingOwnerId><rptOwnerName>DOE JANE</rptOwnerName>
            </reportingOwnerId><reportingOwnerRelationship><isOfficer>1</isOfficer>
            <officerTitle>CFO</officerTitle></reportingOwnerRelationship></reportingOwner>
            <nonDerivativeTable><nonDerivativeTransaction>
            <transactionDate><value>2026-09-01</value></transactionDate>
            <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
            <transactionAmounts><transactionShares><value>1000</value></transactionShares>
            <transactionPricePerShare><value>10</value></transactionPricePerShare>
            <transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode>
            </transactionAmounts></nonDerivativeTransaction></nonDerivativeTable>
            <footnotes><footnote id="F1">Sold pursuant to a Rule 10b5-1 trading plan adopted
            on 2 January 2026.</footnote></footnotes></ownershipDocument>
        """.trimIndent()
        val f = Form4.parse("ZZZ", "acc", "u", 1L, doc)!!
        assertTrue("no aff10b5One element, but the footnote names the rule", f.planned)
        assertFalse(f.isDiscretionary)
        assertEquals("CFO sold 1,000 shares — \$10K", f.headline())
    }

    /** A small trade keeps its exact figure; "$1K" for $1,234 tells the reader nothing. */
    @Test fun smallValuesAreNotRoundedToThousands() {
        assertEquals("\$1,234", InsiderFiling.money(1_234.0))
        assertEquals("\$9,999", InsiderFiling.money(9_999.0))
        assertEquals("\$10K", InsiderFiling.money(10_000.0))
        assertEquals("\$456K", InsiderFiling.money(456_177.0))
        assertEquals("\$4.50M", InsiderFiling.money(4_500_000.0))
        assertEquals("\$1.20B", InsiderFiling.money(1_200_000_000.0))
    }

    /** A Form 3, a Form 5 or a prospectus must never parse into something that looks like a trade. */
    @Test fun nonForm4DocumentsAreRejected() {
        assertNull(Form4.parse("X", "a", "u", 0L, "<html>not a filing at all</html>"))
        assertNull(
            Form4.parse(
                "X", "a", "u", 0L,
                "<ownershipDocument><documentType>3</documentType>" +
                    "<nonDerivativeTransaction><transactionCoding><transactionCode>P" +
                    "</transactionCode></transactionCoding></nonDerivativeTransaction>" +
                    "</ownershipDocument>"
            )
        )
    }

    // ------------------------------------------------------------------ names

    /**
     * EDGAR files people as LAST FIRST MIDDLE. Entities are filed as themselves and must not
     * be reordered - "Vanguard Group Inc" becoming "Group Inc Vanguard" would be worse than
     * doing nothing at all.
     */
    @Test fun personNamesAreReorderedAndEntitiesAreNot() {
        assertEquals("Tench Coxe", Form4.personName("COXE TENCH"))
        assertEquals("Lip Bu Tan", Form4.personName("TAN LIP BU"))
        assertEquals("Jennifer Newstead", Form4.personName("Newstead Jennifer"))
        assertEquals("Lynn Vojvodich Radakovich", Form4.personName("Radakovich Lynn Vojvodich"))
        assertEquals("Vanguard Group Inc", Form4.personName("Vanguard Group Inc"))
        assertEquals("Blackrock Inc.", Form4.personName("BLACKROCK INC."))
        assertEquals("Soros Fund Management Llc", Form4.personName("SOROS FUND MANAGEMENT LLC"))
        assertEquals("Cortez", Form4.personName("CORTEZ"))
        assertEquals("", Form4.personName("   "))
    }

    /**
     * Shapes taken from a 109-filing live sample. The Amazon one is why the comma matters:
     * unstripped it read "sold 3,741 Common Stock, par value $.01 per share".
     */
    @Test fun ordinaryStockIsNotNamedInTheHeadline() {
        assertEquals("", Form4.securityName("Common Stock"))
        assertEquals("", Form4.securityName("common"))
        assertEquals("", Form4.securityName("Common Stock, par value \$.01 per share"))
        // A share class is a different security with different votes - that survives, and
        // the boilerplate around it does not.
        assertEquals("Class A shares", Form4.securityName("Class A Common Stock"))
        assertEquals("Class C shares", Form4.securityName("Class C Capital Stock"))
        assertEquals("Class B shares", Form4.securityName("Class B Common Stock, \$0.01 par"))
        assertEquals("Restricted Stock Units", Form4.securityName("Restricted Stock Units"))
        assertEquals(
            "Class C Google Stock Units",
            Form4.securityName("Class C Google Stock Units")
        )
    }

    /**
     * An ALL-CAPS officer title must be softened without its abbreviations being destroyed.
     * Every one of these is a real title from the live sample, and an earlier version turned
     * the first into "Ceo" and the third into "Evp, Gbul, Sips".
     */
    @Test fun shoutedTitlesKeepTheirAbbreviations() {
        assertEquals("CEO", role("CEO"))
        assertEquals("EVP & CSO", role("EVP & CSO"))
        assertEquals("EVP, GBUL, SIPS", role("EVP, GBUL, SIPS"))
        assertEquals("Chair, President & CEO", role("CHAIR, PRESIDENT & CEO"))
        assertEquals("President and Chief Executive Officer",
            role("PRESIDENT AND CHIEF EXECUTIVE OFFICER"))
        // Mixed case is the filer's own choice of presentation and is left alone.
        assertEquals("SVP, GC and Secretary", role("SVP, GC and Secretary"))
    }

    private fun role(title: String) =
        Form4.roleFor(director = false, officer = true, tenPercent = false,
            officerTitle = title, otherText = "")

    @Test fun securityNamesArePluralisedByTheCount() {
        assertEquals("Restricted Stock Units", InsiderFiling.plural("Restricted Stock Unit", 7690.0))
        assertEquals("Restricted Stock Unit", InsiderFiling.plural("Restricted Stock Unit", 1.0))
        assertEquals("Ford Stock Units", InsiderFiling.plural("Ford Stock Units", 500.0))
    }

    /**
     * A LEAF VALUE NEVER CONTAINS MARKUP. Filers may attach a footnote to an element instead
     * of giving it a value - about one filing in ten in the live sample does - and the naive
     * fallback returns the footnote element itself as the value. Found by
     * `tools_insider_sim.py` against the live service, not by a fixture.
     */
    @Test fun anElementHoldingOnlyAFootnoteHasNoValue() {
        val xml = "<transactionPricePerShare><footnoteId id=\"F1\"/></transactionPricePerShare>"
        assertEquals("", Form4.valueOf(xml, "transactionPricePerShare"))
        assertEquals("", Form4.valueOf("<officerTitle><footnoteId id=\"F2\"/></officerTitle>",
            "officerTitle"))
        // ...and a real value still comes back.
        assertEquals("95.00", Form4.valueOf(
            "<transactionPricePerShare><value>95.00</value></transactionPricePerShare>",
            "transactionPricePerShare"
        ))
    }

    @Test fun transactionCodesMapToPlainEnglish() {
        assertEquals(Form4.BUY, Form4.actionFor("P"))
        assertEquals(Form4.SELL, Form4.actionFor("s"))
        assertEquals(Form4.GRANT, Form4.actionFor("A"))
        assertEquals(Form4.EXERCISE, Form4.actionFor("M"))
        assertEquals(Form4.TAX, Form4.actionFor("F"))
        assertEquals(Form4.GIFT, Form4.actionFor("G"))
        assertEquals(Form4.OTHER, Form4.actionFor("J"))
    }

    // ------------------------------------------------------------------ cache

    /**
     * The cache is what keeps a repeat refresh down to one request per symbol, so a field
     * lost in the round trip is a field silently missing from the screen after a restart.
     */
    @Test fun jsonRoundTripKeepsEverythingTheScreenShows() {
        val originals = listOf(
            parse("form4_buy_ceo.txt", "INTC", "acc-1")!!,
            parse("form4_sell_planned.txt", "AAPL", "acc-2")!!,
            parse("form4_multi.txt", "NVDA", "acc-3")!!
        )
        val back = InsiderFiling.fromJson(InsiderFiling.toJson(originals))
        assertEquals(3, back.size)
        originals.zip(back).forEach { (a, b) ->
            assertEquals(a.accession, b.accession)
            assertEquals(a.symbol, b.symbol)
            assertEquals(a.person, b.person)
            assertEquals(a.role, b.role)
            assertEquals(a.planned, b.planned)
            assertEquals(a.trades.size, b.trades.size)
            assertEquals(a.headline(), b.headline())
            assertEquals(a.subtitle(), b.subtitle())
            assertEquals(a.isDiscretionary, b.isDiscretionary)
        }
    }

    @Test fun corruptCacheIsIgnoredRatherThanCrashing() {
        assertEquals(emptyList<InsiderFiling>(), InsiderFiling.fromJson("not json"))
        assertEquals(emptyList<InsiderFiling>(), InsiderFiling.fromJson("[{\"acc\":\"\"}]"))
    }

    // ------------------------------------------------------------------ feed row

    /**
     * The accession number is what makes a feed row's key unique. Two insiders filing on the
     * same day used to produce a byte-identical id, and a keyed LazyColumn handed the same key
     * twice throws - a crash this app has actually shipped.
     */
    @Test fun feedRowIdentityIsTheAccessionNumber() {
        val a = parse("form4_buy_ceo.txt", "INTC", "0000000000-26-000001")!!.asFeedItem(true)
        val b = parse("form4_buy_ceo.txt", "INTC", "0000000000-26-000002")!!.asFeedItem(true)
        assertEquals(a.title, b.title)
        assertTrue("same title, same day, different filings - ids must differ", a.id != b.id)
        assertNotNull(a.detail)
        assertTrue(a.owned)
    }
}
