package com.tj.portfolio

import com.tj.portfolio.data.EtfFacts
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.ui.carryEtfExplanations
import com.tj.portfolio.ui.carryExplanations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT SURVIVES A REBUILD (Round 63 sweep).
 *
 * The Research screen holds FOUR lists on TWO clocks: the three stock lists rebuild every
 * thirty minutes, the fund list every six hours. They share one `ResearchSet`, which is what
 * makes the failure mode here so easy to reintroduce - `Research.build()` returns a set
 * containing only the three stock lists, so anything that returns it verbatim silently
 * destroys the fourth.
 *
 * That is not a theoretical worry: it SHIPPED into this round and the sweep caught it. Ten
 * Yahoo requests were being re-spent every half hour to rebuild a list the app already had,
 * which is the exact opposite of TJ's rule for it - "keep the current list in cache until
 * each update so it doesn't load on every refresh".
 */
class ResearchCarryTest {

    private fun stock(sym: String, why: String = "") =
        ResearchRow(symbol = sym, score = 70, why = why)

    private fun fund(sym: String, why: String = "", category: String = "") = ResearchRow(
        symbol = sym, score = 80, why = why, catalyst = category,
        etf = EtfFacts(expenseRatio = 0.03, fiveYearAnnualPct = 14.0)
    )

    // ---------------------------------------------- the fund list survives a stock rebuild

    @Test fun `a stock rebuild keeps the fund list, its clock and its warnings`() {
        val old = ResearchSet(
            best = listOf(stock("NVDA", why = "old note")),
            etfs = listOf(fund("VOO")),
            etfGenerated = 1_600_000_000_000L,
            etfWarnings = listOf("the bond screen was quiet"),
            notes = "Claude's note about the data",
            explained = 1_650_000_000_000L,
            explainedBy = "API",
            generated = 1_700_000_000_000L
        )
        // Exactly what `Research.build()` returns: three stock lists and nothing else.
        val fresh = ResearchSet(
            best = listOf(stock("NVDA")),
            generated = 1_700_001_800_000L
        )
        val out = carryExplanations(old, fresh)
        assertEquals("the fund list was destroyed", listOf("VOO"), out.etfs.map { it.symbol })
        assertEquals("the fund clock was reset", 1_600_000_000_000L, out.etfGenerated)
        assertEquals(listOf("the bond screen was quiet"), out.etfWarnings)
        // `explained`/`explainedBy` were already carried, so losing the text left the screen
        // claiming an explanation with nothing to show.
        assertEquals("Claude's note about the data", out.notes)
        assertEquals(1_650_000_000_000L, out.explained)
        // And the new stock data really is the new stock data.
        assertEquals(1_700_001_800_000L, out.generated)
        assertEquals("old note", out.best.first().why)
    }

    @Test fun `an ETF-only cache is not thrown away by the first stock build behind it`() {
        // THE EASIEST WAY TO HIT THE BUG, and the one a user would hit first: open the ETFs
        // tab, then tap Best. `old.isEmpty` is true - it asks only about the STOCK lists - so
        // this takes the early-exit path, which is where the fund list used to vanish.
        val old = ResearchSet(etfs = listOf(fund("VOO")), etfGenerated = 1_600_000_000_000L)
        assertTrue("precondition: the stock lists are empty", old.isEmpty)
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals(listOf("VOO"), out.etfs.map { it.symbol })
        assertEquals(1_600_000_000_000L, out.etfGenerated)
    }

    @Test fun `a rebuild with no prior explanations still keeps the fund list`() {
        // The second early exit: stock lists present but none of them explained.
        val old = ResearchSet(
            best = listOf(stock("NVDA")),
            etfs = listOf(fund("VOO")),
            etfGenerated = 1_600_000_000_000L
        )
        val out = carryExplanations(
            old.copy(best = emptyList(), etfs = old.etfs), ResearchSet(best = listOf(stock("AMD")))
        )
        assertEquals(listOf("VOO"), out.etfs.map { it.symbol })
    }

    /**
     * ROUND 66 replaced the inverse-ETF version of this test. That mapping went with the
     * Worst section; what still has to survive a rebuild is Claude's explanation, which is
     * the expensive part - it costs an API call or a file round trip, and a company's story
     * does not go stale in the thirty minutes between screener rebuilds.
     */
    @Test fun `an explanation survives a rebuild of the same symbol`() {
        val old = ResearchSet(best = listOf(stock("XYZ", why = "w")))
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("XYZ"))))
        assertEquals("w", out.best.first().why)
    }

    // ------------------------------------------------- the fund list's own rebuild

    @Test fun `a fund rebuild keeps the explanation for a fund that survived`() {
        val old = listOf(fund("VOO", why = "The whole US market for three basis points."))
        val fresh = listOf(fund("VOO"), fund("QQQ"))
        val out = carryEtfExplanations(old, fresh)
        assertEquals(2, out.size)
        assertEquals(
            "The whole US market for three basis points.",
            out.first { it.symbol == "VOO" }.why
        )
    }

    @Test fun `a fund Claude added survives a rebuild that cannot rediscover it`() {
        // The point of the whole online-research path: VTI is not in Yahoo's screens, so a
        // fresh pass can NEVER contain it. Dropping it would silently undo the import six
        // hours later and make the user redo the file round trip.
        val old = listOf(
            fund("VOO"),
            ResearchRow(symbol = "VTI", score = 90, why = "The whole market.")
        )
        val out = carryEtfExplanations(old, listOf(fund("VOO")))
        assertTrue("the fund Claude added was dropped", out.any { it.symbol == "VTI" })
        assertEquals("it should rank by its own score", "VTI", out.first().symbol)
    }

    @Test fun `a fund Claude added with only a category also survives`() {
        // `ResearchBridge.section` admits a row on any of why / catalyst / risk / vehicle, so
        // requiring `why` here made a category-only row appear and then vanish.
        val old = listOf(
            fund("VOO"),
            ResearchRow(symbol = "SCHD", score = 70, catalyst = "US dividend equity")
        )
        val out = carryEtfExplanations(old, listOf(fund("VOO")))
        assertTrue(out.any { it.symbol == "SCHD" })
    }

    @Test fun `a fund the screener still finds is not duplicated by the carry-over`() {
        // A keyed LazyColumn handed one key twice throws, and this set is written straight to
        // the cache - so a duplicate here would keep crashing the tab on every launch.
        val old = listOf(fund("VOO", why = "explained"))
        val out = carryEtfExplanations(old, listOf(fund("VOO")))
        assertEquals(1, out.size)
    }

    @Test fun `an empty prior list changes nothing`() {
        val fresh = listOf(fund("VOO"), fund("QQQ"))
        assertEquals(fresh.map { it.symbol }, carryEtfExplanations(emptyList(), fresh).map { it.symbol })
    }
}
