package com.tj.portfolio

import com.tj.portfolio.data.EtfRow
import com.tj.portfolio.net.EtfScore
import com.tj.portfolio.net.EtfScreener
import com.tj.portfolio.net.Research
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOW MANY FUNDS THE RANKING ACTUALLY SAW, AND WHICH OF TWO EQUALS IT KEEPS.
 *
 * Round 66 audit, ETF-2 / ETF-4 / ETF-8. Three failures with one thing in common: each of
 * them changed WHICH FUNDS TJ IS SHOWN without changing anything he could see, on the list he
 * said he is going to buy from.
 */
class EtfUniverseTest {

    private fun rows(n: Int, from: Int = 0) =
        (0 until n).map { EtfRow(symbol = "S${from + it}", name = "Fund ${from + it}") }

    // ------------------------------------------------------------------ ETF-2

    /**
     * THE BUG. `fetch` returned `emptyList()` for four different reasons - past the end of the
     * list, both hosts cooling, both refusing, an unparseable body - and `fetchAll` stops on
     * an empty page. `Http` arms cooldowns per host and every feed in the app shares query1,
     * so one 429 from a chart or a quote batch was enough: page 2 came back empty, the loop
     * broke, and the universe was 200 funds instead of 523. The caller warned only when a
     * list returned NOTHING, so 200 passed in silence while the screen went on saying it had
     * ranked about 850 funds.
     */
    @Test fun `a page nobody answered is not the end of the list`() = runBlocking {
        val got = EtfScreener.fetchAllWith(6) { start ->
            when (start) {
                0 -> rows(100, 0)
                100 -> rows(100, 100)
                else -> null          // both hosts cooling, from page 3 on
            }
        }
        assertEquals("the two pages that answered must be kept", 200, got.rows.size)
        assertFalse("a truncated universe must not report itself complete", got.complete)
        assertEquals(2, got.pagesRead)
        assertEquals(6, got.pagesAsked)
    }

    /** And the opposite: a list that genuinely runs out early IS complete. */
    @Test fun `reaching the end of a short list is complete`() = runBlocking {
        val got = EtfScreener.fetchAllWith(6) { start ->
            when (start) {
                0 -> rows(100, 0)
                100 -> rows(40, 100)
                else -> emptyList()   // Yahoo answered: there is nothing more
            }
        }
        assertEquals(140, got.rows.size)
        assertTrue("a short list is not a broken one", got.complete)
    }

    /** Every page delivered is complete too, obviously - and the count must be right. */
    @Test fun `all pages answered is complete`() = runBlocking {
        val got = EtfScreener.fetchAllWith(3) { rows(100) }
        assertEquals(300, got.rows.size)
        assertTrue(got.complete)
        assertEquals(3, got.pagesRead)
    }

    /** Nothing at all answered: no rows, and emphatically not complete. */
    @Test fun `a list that never answered is not complete`() = runBlocking {
        val got = EtfScreener.fetchAllWith(3) { null }
        assertTrue(got.rows.isEmpty())
        assertFalse(got.complete)
        assertEquals(0, got.pagesRead)
    }

    // ------------------------------------------------------------------ ETF-4

    private fun scored(
        symbol: String, score: Int, expense: Double, assets: Double
    ): Pair<EtfRow, com.tj.portfolio.net.ResearchScore.Scored> =
        EtfRow(symbol = symbol, name = symbol, expenseRatio = expense, netAssets = assets) to
            com.tj.portfolio.net.ResearchScore.Scored(score = score, reasons = emptyList(), confidence = 100)

    /**
     * THE BUG. `sortedByDescending { score }` is stable, so equal scores kept Yahoo's screen
     * order - and since the exposure de-duplication was added, the fund that loses a tie is
     * DELETED. Ties are the normal case for two trackers of one index, because every term
     * that could separate them is saturated at that size. Yahoo's page order was choosing
     * which S&P 500 fund TJ gets shown.
     */
    @Test fun `equal scores are broken by cost, then size, then ticker`() {
        // Deliberately handed to the sort in the WORST order, so a stable sort alone fails.
        val list = listOf(
            scored("IVV", 91, 0.03, 6.0e11),
            scored("SPLG", 91, 0.02, 8.0e10),
            scored("VOO", 91, 0.03, 7.0e11)
        )
        assertEquals(
            listOf("SPLG", "VOO", "IVV"),
            list.sortedWith(Research.ETF_ORDER).map { it.first.symbol }
        )
    }

    /** Score still wins over everything: a cheaper fund does not outrank a better one. */
    @Test fun `score comes first`() {
        val list = listOf(
            scored("CHEAP", 70, 0.00, 1.0e9),
            scored("GOOD", 90, 0.60, 1.0e9)
        )
        assertEquals(
            listOf("GOOD", "CHEAP"),
            list.sortedWith(Research.ETF_ORDER).map { it.first.symbol }
        )
    }

    /** An unknown fee cannot claim to be cheap - it sorts last among equals. */
    @Test fun `an unknown fee sorts behind a known one`() {
        val list = listOf(
            scored("UNKNOWN", 80, -1.0, 9.0e10),
            scored("KNOWN", 80, 0.40, 1.0e9)
        )
        assertEquals(
            listOf("KNOWN", "UNKNOWN"),
            list.sortedWith(Research.ETF_ORDER).map { it.first.symbol }
        )
    }

    /** A free fund IS cheap, and beats a 3bp one on the tie-break (ETF-6 meets ETF-4). */
    @Test fun `a free fund wins the tie-break`() {
        val list = listOf(
            scored("CHEAP", 88, 0.03, 5.0e11),
            scored("FREE", 88, 0.00, 1.0e9)
        )
        assertEquals(
            listOf("FREE", "CHEAP"),
            list.sortedWith(Research.ETF_ORDER).map { it.first.symbol }
        )
    }

    /** Fully deterministic: the same funds always produce the same list, in any input order. */
    @Test fun `the order does not depend on the order they arrived in`() {
        val a = listOf(
            scored("AAA", 80, 0.10, 1.0e9),
            scored("BBB", 80, 0.10, 1.0e9),
            scored("CCC", 80, 0.10, 1.0e9)
        )
        val forwards = a.sortedWith(Research.ETF_ORDER).map { it.first.symbol }
        val backwards = a.reversed().sortedWith(Research.ETF_ORDER).map { it.first.symbol }
        assertEquals(forwards, backwards)
        assertEquals(listOf("AAA", "BBB", "CCC"), forwards)
    }

    // ------------------------------------------------------------------ ETF-8

    /**
     * The list has a second entrance: Claude is asked to add funds the screener universe
     * cannot see, and a model naming "the best ETFs" may reasonably name TQQQ. The screener
     * path filtered these; the import path did not, on a list whose own sources note says
     * they are excluded. These are the names a model actually produces.
     */
    @Test fun `the funds a model is most likely to add are recognised as leveraged`() {
        listOf(
            "ProShares UltraPro QQQ" to "TQQQ",
            "ProShares UltraPro Short QQQ" to "SQQQ",
            "Direxion Daily Semiconductor Bull 3X Shares" to "SOXL",
            "ProShares Ultra S&P500" to "SSO",
            "Direxion Daily S&P 500 Bull 3X Shares" to "SPXL",
            "ProShares Short S&P500" to "SH",
            "ProShares UltraShort QQQ" to "QID",
            "T-Rex 2X Long NVIDIA Daily Target ETF" to "NVDX"
        ).forEach { (name, sym) ->
            assertTrue(
                "\"$name\" ($sym) must be excluded from a best-ETF list",
                EtfScore.isLeveragedOrInverse(name, sym)
            )
        }
    }

    /** And the ordinary funds beside them are still let through - the other half of ETF-8. */
    @Test fun `ordinary funds a model would add are not mistaken for leveraged ones`() {
        listOf(
            "Vanguard S&P 500 ETF" to "VOO",
            "iShares Short Treasury Bond ETF" to "SHV",
            "Vanguard Short-Term Bond ETF" to "BSV",
            "PIMCO Enhanced Short Maturity Active ETF" to "MINT",
            "iShares Ultra Short-Term Bond Active ETF" to "ICSH",
            "Schwab U.S. Dividend Equity ETF" to "SCHD",
            "Vanguard Total World Bond ETF" to "BNDW"
        ).forEach { (name, sym) ->
            assertFalse(
                "\"$name\" ($sym) is an ordinary fund and must not be excluded",
                EtfScore.isLeveragedOrInverse(name, sym)
            )
        }
    }
}
