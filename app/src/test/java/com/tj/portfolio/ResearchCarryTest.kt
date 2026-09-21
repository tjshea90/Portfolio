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

    // `whyAt` defaults to "just now" - fresh enough to survive the staleness gate
    // (`WHY_STALE_MS`) that `carryExplanations`/`carryEtfExplanations` now check - so every
    // existing test below still exercises the CARRY logic itself. The staleness gate gets its
    // own tests, further down, which pass an explicit old `whyAt`.
    private fun stock(sym: String, why: String = "", whyAt: Long = System.currentTimeMillis()) =
        ResearchRow(symbol = sym, score = 70, why = why, whyAt = if (why.isNotBlank()) whyAt else 0L)

    private fun fund(
        sym: String,
        why: String = "",
        category: String = "",
        whyAt: Long = System.currentTimeMillis()
    ) = ResearchRow(
        symbol = sym, score = 80, why = why, catalyst = category,
        whyAt = if (why.isNotBlank() || category.isNotBlank()) whyAt else 0L,
        etf = EtfFacts(expenseRatio = 0.03, fiveYearAnnualPct = 14.0)
    )

    // ---------------------------------------------- the fund list survives a stock rebuild

    @Test fun `a stock rebuild keeps the fund list, its clock and its warnings`() {
        // RECENT, not an arbitrary fixed epoch - round 79's staleness gate on `explained`
        // (see `carryExplainedStamp`) now evicts an explain-pass stamp past `WHY_STALE_MS`,
        // and this test is about the CARRY mechanism, not staleness (that has its own tests).
        val explainedRecent = System.currentTimeMillis()
        val old = ResearchSet(
            best = listOf(stock("NVDA", why = "old note")),
            etfs = listOf(fund("VOO")),
            etfGenerated = 1_600_000_000_000L,
            etfWarnings = listOf("the bond screen was quiet"),
            notes = "Claude's note about the data",
            explained = explainedRecent,
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
        assertEquals(explainedRecent, out.explained)
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

    // ---------------------------------------- Day Trading's own explain state (Round 67)
    //
    // Same bug shape as the fund list above, on a newer section: `dtExplained`/
    // `dtExplainedBy`/`dtNotes` are Day Trading's own counterparts to `explained`/
    // `explainedBy`/`notes`, kept separate because Day Trading is a wholly different Claude
    // bridge and button - but `Research.build()` knows nothing about them either, so the same
    // "returns a fresh set verbatim" bug would silently blank them on the very next
    // thirty-minute rebuild if `carryExplanations` did not carry them forward too.

    @Test fun `a stock rebuild keeps the day-trading explanation, its clock and its notes`() {
        val old = ResearchSet(
            best = listOf(stock("NVDA")),
            dayTrading = listOf(stock("GME", why = "old day-trading note")),
            dtNotes = "Claude's note about the day-trading data",
            dtExplained = 1_650_000_000_000L,
            dtExplainedBy = "API",
            generated = 1_700_000_000_000L
        )
        val fresh = ResearchSet(
            best = listOf(stock("NVDA")),
            dayTrading = listOf(stock("GME")),
            generated = 1_700_001_800_000L
        )
        val out = carryExplanations(old, fresh)
        assertEquals("Claude's note about the day-trading data", out.dtNotes)
        assertEquals(1_650_000_000_000L, out.dtExplained)
        assertEquals("API", out.dtExplainedBy)
        assertEquals("old day-trading note", out.dayTrading.first().why)
        // The two explain states stay apart - carrying Day Trading's forward must not leak
        // into, or borrow from, Research's own.
        assertEquals("", out.notes)
        assertEquals(0L, out.explained)
    }

    @Test fun `an ETF-only cache is not thrown away and keeps day-trading state too`() {
        // The same early-exit path as the ETF version of this test - `old.isEmpty` is true
        // because it only asks about the STOCK lists - so this is the other place the bug
        // shape above could have been reintroduced.
        val old = ResearchSet(
            etfs = listOf(fund("VOO")),
            dtNotes = "day-trading note",
            dtExplained = 1_600_000_000_000L,
            dtExplainedBy = "Claude app"
        )
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals("day-trading note", out.dtNotes)
        assertEquals(1_600_000_000_000L, out.dtExplained)
        assertEquals("Claude app", out.dtExplainedBy)
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
            ResearchRow(
                symbol = "VTI", score = 90, why = "The whole market.",
                whyAt = System.currentTimeMillis()
            )
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
            ResearchRow(
                symbol = "SCHD", score = 70, catalyst = "US dividend equity",
                whyAt = System.currentTimeMillis()
            )
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

    // ------------------------------------------------- staleness eviction (round 79)
    //
    // Tj: "I haven't run the Claude analysis in weeks... if Claude advice hasn't been used in
    // a while, the app should remove it from cache." Everything above proves `why` survives a
    // rebuild while it is RECENT; these prove it stops surviving once it is not - the actual
    // eviction, not just a display-side warning.

    private val old14Days = System.currentTimeMillis() - (com.tj.portfolio.ui.WHY_STALE_MS + 3_600_000L)

    @Test fun `a stock's stale explanation is evicted, not carried forward`() {
        val old = ResearchSet(best = listOf(stock("NVDA", why = "weeks-old note", whyAt = old14Days)))
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals("stale why should have been evicted", "", out.best.first().why)
        assertEquals(0L, out.best.first().whyAt)
    }

    @Test fun `a recent explanation is unaffected by the staleness gate`() {
        val old = ResearchSet(best = listOf(stock("NVDA", why = "fresh note")))
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals("fresh note", out.best.first().why)
    }

    @Test fun `an ETF's stale explanation is evicted on its own rebuild too`() {
        val old = listOf(fund("VOO", why = "weeks-old note", whyAt = old14Days))
        val out = carryEtfExplanations(old, listOf(fund("VOO")))
        assertEquals("", out.first().why)
    }

    @Test fun `a Claude-added fund is dropped outright once its content goes stale`() {
        // Unlike an app-screened fund, a Claude-added row (`etf == null`) has nothing else on
        // its card - once its one piece of content is stale there is nothing left to show, so
        // it is dropped rather than kept with a blank paragraph.
        val old = listOf(
            fund("VOO"),
            ResearchRow(symbol = "VTI", score = 90, why = "The whole market.", whyAt = old14Days)
        )
        val out = carryEtfExplanations(old, listOf(fund("VOO")))
        assertTrue("a stale Claude-added fund should not survive", out.none { it.symbol == "VTI" })
    }

    @Test fun `evictStaleWhy blanks a stale row on a cold launch, with no rebuild involved`() {
        // The one path `carryExplanations`/`carryEtfExplanations` never see: a cache this old
        // restored straight from disk, with no rebuild in between to run the carry logic at
        // all - see `loadCachedResearch`'s own call to this.
        val set = ResearchSet(
            best = listOf(stock("NVDA", why = "weeks-old note", whyAt = old14Days)),
            trending = listOf(stock("GME", why = "fresh note")),
            etfs = listOf(fund("VOO", why = "weeks-old note", whyAt = old14Days))
        )
        val out = com.tj.portfolio.ui.evictStaleWhy(set)
        assertEquals("stale best row not evicted", "", out.best.first().why)
        assertEquals("fresh trending row wrongly touched", "fresh note", out.trending.first().why)
        assertEquals("stale etf row not evicted", "", out.etfs.first().why)
    }

    // ---- full-tests audit, round 79: `whyAt` narrowed to track `why` specifically broke a
    // pre-existing case unless the ETF-added filter was updated to match (see
    // `carryEtfExplanations`'s own note)

    @Test fun `a category-only Claude-added fund survives even though it never gets a whyAt`() {
        // `whyAt` now only stamps when `why` itself is fresh (ResearchBridge.merge's fix for
        // the HIGH finding), so a category-only row's `whyAt` is always 0 - it must not be
        // read as "stale" the way a row that once HAD a why and lost it would be.
        val old = listOf(
            fund("VOO"),
            ResearchRow(symbol = "SCHD", score = 70, catalyst = "US dividend equity", whyAt = 0L)
        )
        val out = carryEtfExplanations(old, listOf(fund("VOO")))
        assertTrue("a category-only fund must not be dropped for lacking a whyAt", out.any { it.symbol == "SCHD" })
    }

    // ---- full-tests audit, round 79: the BATCH-level "explained via Claude" stamp
    // (`explained`/`explainedBy`/`notes` and their day-trading counterparts) goes stale the
    // same way a single row's `why` does - it used to carry forward unconditionally too.

    @Test fun `a stale batch explain-pass stamp is evicted on a stock rebuild`() {
        val old = ResearchSet(
            best = listOf(stock("NVDA")),
            notes = "Claude's note about the data",
            explained = old14Days,
            explainedBy = "API"
        )
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals("", out.notes)
        assertEquals(0L, out.explained)
        assertEquals("", out.explainedBy)
    }

    @Test fun `a recent batch explain-pass stamp survives a stock rebuild`() {
        val old = ResearchSet(
            best = listOf(stock("NVDA")),
            notes = "Claude's note about the data",
            explained = System.currentTimeMillis(),
            explainedBy = "API"
        )
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals("Claude's note about the data", out.notes)
    }

    @Test fun `a stale day-trading explain-pass stamp is evicted the same way`() {
        val old = ResearchSet(
            best = listOf(stock("NVDA")),
            dtNotes = "Claude's day-trading note",
            dtExplained = old14Days,
            dtExplainedBy = "API"
        )
        val out = carryExplanations(old, ResearchSet(best = listOf(stock("NVDA"))))
        assertEquals("", out.dtNotes)
        assertEquals(0L, out.dtExplained)
    }

    @Test fun `a stale batch explain-pass stamp is evicted on a cold launch too`() {
        val set = ResearchSet(
            best = listOf(stock("NVDA")),
            notes = "Claude's note", explained = old14Days, explainedBy = "API",
            dtNotes = "Claude's day-trading note", dtExplained = old14Days, dtExplainedBy = "API"
        )
        val out = com.tj.portfolio.ui.evictStaleWhy(set)
        assertEquals("", out.notes)
        assertEquals(0L, out.explained)
        assertEquals("", out.dtNotes)
        assertEquals(0L, out.dtExplained)
    }

    // ---- full-tests audit, round 79: a day-trading PLAN (entry/stop/target) is only ever
    // valid for the trading day it was computed on - a cold launch after a prior trading day
    // must not leave it on screen.

    @Test fun `a day-trading plan from a previous trading day is cleared on cold launch`() {
        val yesterday = System.currentTimeMillis() - 2L * 86_400_000L
        val row = ResearchRow(
            symbol = "GME", score = 88, price = 22.5,
            entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5,
            setup = "Breakout", trigger = "Buy the break of 22.50", planByClaude = true
        )
        val out = com.tj.portfolio.ui.evictStaleDayTradingPlan(listOf(row), generated = yesterday)
        val gme = out.first()
        assertEquals(0.0, gme.entryPrice, 0.0)
        assertEquals(0.0, gme.stopPrice, 0.0)
        assertEquals(0.0, gme.targetPrice, 0.0)
        assertEquals("", gme.setup)
        assertTrue("the row itself (score, price) must survive - only the plan is cleared", out.first().price == 22.5)
    }

    @Test fun `a same-day day-trading plan is left alone`() {
        val now = System.currentTimeMillis()
        val row = ResearchRow(
            symbol = "GME", score = 88, price = 22.5,
            entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5, setup = "Breakout"
        )
        val out = com.tj.portfolio.ui.evictStaleDayTradingPlan(listOf(row), generated = now)
        assertEquals(22.5, out.first().entryPrice, 0.0)
    }

    @Test fun `a row with no plan at all is untouched by the day-trading eviction`() {
        val yesterday = System.currentTimeMillis() - 2L * 86_400_000L
        val row = ResearchRow(symbol = "MEH", score = 40, price = 5.0)
        val out = com.tj.portfolio.ui.evictStaleDayTradingPlan(listOf(row), generated = yesterday)
        assertEquals(row, out.first())
    }
}
