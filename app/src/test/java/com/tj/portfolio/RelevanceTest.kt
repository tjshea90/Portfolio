package com.tj.portfolio

import com.tj.portfolio.net.Relevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FIVE bug, pinned down.
 *
 * TJ reported that tapping News on FIVE (Five Below) returned stories that merely contain
 * the word "five". Every "must not match" case below is a REAL headline pulled from the live
 * Google News feed on 4 September 2026 using the query the app was actually sending -
 * `"Five Below, Inc." OR FIVE stock` - which matched 15 of 31 headlines correctly.
 *
 * The rest of the cases are the traps found while designing the fix. They are the reason this
 * file is worth more than the fix itself: each one is a way the obvious implementation breaks.
 */
class RelevanceTest {

    private fun m(title: String, sym: String, company: String) =
        Relevance.matches(title, "", sym, company)

    private val FIVE = "Five Below, Inc."

    // ------------------------------------------------- the reported bug

    @Test
    fun `the real polluted headlines are rejected`() {
        // These are verbatim from the live feed. Every one of them was being shown to TJ
        // under FIVE. The shared shape is a simplywall_st / Yahoo template:
        // "<COMPANY> (<TICKER>) Stock Looks ... On Its N% Five Year Run".
        val junk = listOf(
            "Axsome Therapeutics (AXSM) Stock Looks Like A Bargain On Its 7x Five Year Run",
            "Upstart (UPST) Stock Looks Expensive Following a 90% Five Year Slump",
            "Moog (MOG.A) Stock Looks Fully Priced Following Its 401% Five Year Run",
            "Wheaton Precious Metals (TSX:WPM) Stock Trades Rich On A 272% Five Year Run",
            "Charter (CHTR) Stock Still Looks Undervalued After Its 82% Five Year Fall",
            "Albertsons (ACI) Stock Looks Expensive On a 41% Five Year Slump",
            "Archrock (AROC) Stock Looks Pricey Despite Its Very Large Five Year Run",
            "Texas Roadhouse (TXRH) Stock Looks Above Fair Value Following Its 129% Five Year Run",
            "GSK (LSE:GSK) Stock Looks Reasonable On Its 54% Five Year Run",
            "EOG Resources (EOG) Stock Looks Reasonable After A 174% Five Year Run",
            "Tulane Outlasts Panthers to Win in Five",
            "Five things to know about the New Orleans Saints for Friday, September 4",
            "Five Key Moments: Hearing on Welfare Reform 30th Anniversary",
            "Five EU nations inch towards deal on migrant 'return hubs' by 2027",
            "Friday's ETF with Unusual Volume: FLCV"
        )
        junk.forEach { assertFalse("should be rejected: $it", m(it, "FIVE", FIVE)) }
    }

    @Test
    fun `real Five Below headlines are kept`() {
        val good = listOf(
            "Why Five Below (FIVE) Stock Is Trading Up Today",
            "Jim Cramer says Five Below stock is a buy after Wall Street misread its earnings",
            "Five Below (NASDAQ: FIVE) COO plans stock sale in 2026",
            "Five Below (FIVE) is an Incredible Growth Stock: 3 Reasons Why",
            "Five Below Unveils \$600 Million Share Repurchase Plan",
            "Five Below Q2 Earnings Call Highlights",
            "Why is Five Below stock surging today?",
            // the ticker appearing in an earnings round-up is a genuine mention
            "After-Hours Earnings Report for September 2, 2026 : AVGO, SNOW, HPE, NTAP, FIVE, AGX",
            // two tickers leading a headline, then the company name
            "FIVE, WOOF Stocks Rise Premarket: Five Below And Petco Get Fresh Price Target Boosts"
        )
        good.forEach { assertTrue("should be kept: $it", m(it, "FIVE", FIVE)) }
    }

    // ------------------------------------------------- the traps

    @Test
    fun `matching the leading company word alone would reintroduce the bug`() {
        // THIS IS THE WHOLE POINT. "Five" leads both "Five Below" and "Five Year Run", so a
        // fix that accepts the company's first word brings the reported bug straight back.
        // A company whose distinctive word is ordinary English must be named in full.
        assertFalse(m("Charter (CHTR) Stock Undervalued After Its 82% Five Year Fall", "FIVE", FIVE))
        assertTrue(m("Five Below beats estimates", "FIVE", FIVE))
    }

    @Test
    fun `finance vocabulary that doubles as a company name does not match`() {
        // "price target" appears in a large share of all finance headlines.
        assertFalse(m("Wall Street Analysts Raise Price Target On Nike", "TGT", "Target Corporation"))
        assertTrue(m("Target (TGT) same-store sales fall", "TGT", "Target Corporation"))
        // "the valuation gap", "a block trade", "at the open", "snow"
        assertFalse(m("Investors weigh the valuation Gap between the two", "GAP", "The Gap, Inc."))
        assertFalse(m("Snow expected across the northeast this weekend", "SNOW", "Snowflake Inc."))
        assertTrue(m("Snowflake beats on product revenue", "SNOW", "Snowflake Inc."))
    }

    @Test
    fun `a company named by its first word only is still matched`() {
        // The opposite failure: over-filtering. "Ford Motor Company" is almost never written
        // out in a headline, and NVIDIA is rarely capitalised the way the exchange lists it.
        assertTrue(m("Ford recalls 250,000 trucks over brake fault", "F", "Ford Motor Company"))
        assertTrue(m("Nvidia earnings beat expectations", "NVDA", "NVIDIA Corporation"))
        assertTrue(m("NVIDIA announces new data-center GPU", "NVDA", "NVIDIA Corporation"))
        assertTrue(m("Cisco lifts its dividend", "CSCO", "Cisco Systems, Inc."))
        assertTrue(m("Agilent raises full-year guidance", "A", "Agilent Technologies, Inc."))
        assertTrue(m("Apple unveils new iPhone lineup", "AAPL", "Apple Inc."))
    }

    @Test
    fun `case is the signal that separates a ticker from a word`() {
        // The Feed's old matcher upper-cased the whole headline first, which destroyed
        // exactly this distinction and is why "Five Year Run" matched the FIVE holding.
        assertFalse(m("apple orchards report a record harvest in vermont", "AAPL", "Apple Inc."))
        assertTrue(m("Apple (AAPL) hits a record high", "AAPL", "Apple Inc."))
    }

    @Test
    fun `an all-caps headline proves nothing by containing the ticker`() {
        assertFalse(m("AI STOCKS SURGE AS CHIPMAKERS RALLY ACROSS THE BOARD", "AI", "C3.ai, Inc."))
        assertTrue(m("C3.ai (AI) posts a narrower loss", "AI", "C3.ai, Inc."))
    }

    @Test
    fun `a one-letter ticker needs an explicit ticker marker`() {
        // "A" appears in almost every headline ever written.
        assertFalse(m("A big day for markets as the Fed holds", "A", "Agilent Technologies, Inc."))
        assertTrue(m("Earnings today: (A) Agilent, (F) Ford", "A", "Agilent Technologies, Inc."))
    }

    @Test
    fun `a dot is not a word boundary`() {
        // Without this, "MOG.A" contains a standalone "A" and every Moog headline would be
        // filed under Agilent.
        assertFalse(m("Moog (MOG.A) Stock Looks Fully Priced", "A", "Agilent Technologies, Inc."))
    }

    @Test
    fun `a symbol with no company name falls back to the ticker`() {
        assertTrue(m("AVGG declares its monthly distribution", "AVGG", ""))
        assertFalse(m("Some headline about something else entirely", "AVGG", ""))
    }

    @Test
    fun `legal suffixes are not identifying`() {
        assertEquals(listOf("Five", "Below"), Relevance.coreWords("Five Below, Inc."))
        assertEquals(listOf("Ford"), Relevance.coreWords("Ford Motor Company"))
        assertEquals(listOf("NVIDIA"), Relevance.coreWords("NVIDIA Corporation"))
        // a name that is ONLY suffixes must not produce an empty-string match
        assertTrue(Relevance.coreWords("The Company").isEmpty())
        assertFalse(m("anything at all", "ZZZZ", "The Company"))
    }

    // ------------------------------------------------- the safety valve

    @Test
    fun `filtering never returns an empty list`() {
        // A stock's news tab going permanently blank because a heuristic got strict is a
        // worse failure than a little noise. If everything is rejected the caller gets the
        // unfiltered list back and the reader judges for themselves.
        val all = listOf("Completely unrelated one", "Completely unrelated two")
        val kept = Relevance.keepRelevant(all, "FIVE", FIVE, title = { it })
        assertEquals(all, kept)
    }

    @Test
    fun `filtering keeps only the relevant ones when there are some`() {
        val mixed = listOf(
            "Five Below (FIVE) beats on earnings",
            "Upstart (UPST) Stock Looks Expensive Following a 90% Five Year Slump",
            "Five Below raises its outlook"
        )
        val kept = Relevance.keepRelevant(mixed, "FIVE", FIVE, title = { it })
        assertEquals(2, kept.size)
        assertFalse(kept.any { it.contains("Upstart") })
    }

    @Test
    fun `the summary is searched as well as the title`() {
        assertTrue(
            Relevance.matches(
                "Cramer says buy this retail stock after the market got the quarter wrong",
                "The CNBC host was discussing Five Below's second quarter.",
                "FIVE", FIVE
            )
        )
    }

    // ------------------------------------------------- the Feed's My stocks filter

    @Test
    fun `matchHolding does not tag an unrelated story with a holding`() {
        // The second home of the same bug: the Feed upper-cased the headline before looking
        // for a ticker, so this landed under "My stocks" as Five Below.
        val owned = setOf("FIVE", "NVDA")
        val names = mapOf("FIVE" to FIVE, "NVDA" to "NVIDIA Corporation")
        assertNull(
            Relevance.matchHolding(
                "Upstart (UPST) Stock Looks Expensive Following a 90% Five Year Slump", owned, names
            )
        )
        assertEquals("FIVE", Relevance.matchHolding("Five Below tops estimates", owned, names))
        assertEquals("NVDA", Relevance.matchHolding("Nvidia unveils Rubin", owned, names))
    }

    @Test
    fun `a headline naming two holdings claims neither`() {
        val owned = setOf("FIVE", "NVDA")
        val names = mapOf("FIVE" to FIVE, "NVDA" to "NVIDIA Corporation")
        assertNull(
            Relevance.matchHolding("Five Below and Nvidia both report today", owned, names)
        )
    }

    @Test
    fun `matchHolding tolerates an empty portfolio and a blank title`() {
        assertNull(Relevance.matchHolding("", setOf("FIVE"), mapOf("FIVE" to FIVE)))
        assertNull(Relevance.matchHolding("Five Below tops estimates", emptySet(), emptyMap()))
    }

    // ------------------------------------------ full test 2026-09-23, S-1

    @Test
    fun `a short-word company phrase no longer matches inside other words`() {
        // "S&P Global Inc." -> core words S, P; "AT&T Inc." -> AT, T. As a bare substring,
        // "s p" matched "stock-s p-lunge" and "at t" matched "at the" - hundreds of false hits.
        assertFalse(m("Stocks plunge as yields jump", "SPGI", "S&P Global Inc."))
        assertFalse(m("Fed holds rates at the June meeting", "T", "AT&T Inc."))
        assertFalse(m("Nvidia posts profit that tops estimates", "T", "AT&T Inc."))
        // The ticker rules still find them when they really are the subject.
        assertTrue(m("Shares of AT&T (T) climbed after earnings", "T", "AT&T Inc."))
        assertTrue(m("SPGI beats on ratings revenue", "SPGI", "S&P Global Inc."))
    }

    @Test
    fun `a real multi-word phrase still matches, on whole words only`() {
        assertTrue(m("five below beats on holiday sales", "FIVE", FIVE))
        assertTrue(m("Ford Motor recalls 100,000 trucks", "F", "Ford Motor Company"))
        // A phrase that only appears glued inside longer words is not the company.
        assertFalse(m("Thefive belowground sensors", "FIVE", FIVE))
    }
}
