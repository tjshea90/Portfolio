package com.tj.portfolio

import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.VisibleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much the app asks of its data providers, as a pure function.
 *
 * This is the single most load-bearing piece of the provider-safety work: every automatic
 * quote request the app makes is chosen here. Before it existed, every tick fetched EVERY
 * tracked symbol regardless of what was on screen - one Yahoo chart call per symbol, so a
 * 20-symbol portfolio at the default 15-second interval is 80 requests a minute, sustained,
 * including while sitting on screens that display no price at all.
 *
 * Round 47's closing note said the ViewModel was untestable because its `init` starts a
 * polling loop that never settles, and that a seam would fix it. This is that seam.
 */
class VisibleScopeTest {

    private fun row(sym: String, watch: Boolean) =
        Row(symbol = sym, name = sym, position = null, quote = null, watchOnly = watch)

    private val rows = listOf(
        row("FIVE", false), row("NVDA", false), row("CSCO", false),
        row("AAPL", true), row("TSLA", true)
    )
    private val all = { listOf("FIVE", "NVDA", "CSCO", "AAPL", "TSLA") }

    private fun quoted(scope: VisibleScope, r: List<Row> = rows) =
        VisibleScope.symbolsFor(scope, r, all)

    // ------------------------------------------- which scope the navigation state means

    private fun choose(
        reader: Boolean = false,
        searching: Boolean = false,
        detail: String? = null,
        portfolio: Boolean = false,
        watch: Boolean = false,
        research: Boolean = false
    ) = VisibleScope.choose(reader, searching, detail, portfolio, watch, research)

    @Test
    fun `the research sub-tab polls nothing even though it is inside the watch tab`() {
        // Round 54. Research shows no watchlist row - its prices come from the screener
        // payload - so treating it as "the Watch tab" would poll every watched symbol behind
        // a screen that displays none of them.
        assertEquals(VisibleScope.None, choose(watch = true, research = true))
        assertEquals(VisibleScope.Watchlist, choose(watch = true, research = false))
        assertTrue(quoted(choose(watch = true, research = true)).isEmpty())
    }

    @Test
    fun `the deepest layer on screen wins`() {
        // A detail screen opened FROM the research list is still one symbol, not none.
        assertEquals(
            VisibleScope.Detail("NVDA"),
            choose(detail = "NVDA", watch = true, research = true)
        )
        // The reader and the search sheet cover everything, including a detail screen.
        assertEquals(VisibleScope.None, choose(reader = true, detail = "NVDA", watch = true))
        assertEquals(VisibleScope.None, choose(searching = true, detail = "NVDA", portfolio = true))
    }

    @Test
    fun `tabs that show no price choose None`() {
        assertEquals(VisibleScope.None, choose())
    }

    @Test
    fun `the portfolio polls its holdings and not the watchlist`() {
        assertEquals(listOf("FIVE", "NVDA", "CSCO"), quoted(VisibleScope.Portfolio))
    }

    @Test
    fun `the watchlist polls the watched symbols and not the holdings`() {
        assertEquals(listOf("AAPL", "TSLA"), quoted(VisibleScope.Watchlist))
    }

    @Test
    fun `a detail screen polls exactly one symbol`() {
        // Was 20 requests to update the one number on screen.
        assertEquals(listOf("NVDA"), quoted(VisibleScope.Detail("NVDA")))
    }

    @Test
    fun `a detail screen for a symbol that is not a row still polls it`() {
        // Opened from search: the stock is not held or watched, but it is what is on screen.
        assertEquals(listOf("XYZ"), quoted(VisibleScope.Detail("XYZ")))
    }

    @Test
    fun `screens with no prices poll nothing at all`() {
        // Activity, Advice, Settings, the reader, the search sheet. This is the case that
        // was costing the full 80 requests a minute for prices nobody could see.
        assertTrue(quoted(VisibleScope.None).isEmpty())
    }

    // ------------------------------------------------- the two fallbacks

    @Test
    fun `a cold start with no rows yet still fetches something`() {
        // Without this a first launch - where recompute has not built rows - would fetch
        // nothing and the app would open blank with no way to fill itself.
        assertEquals(all(), quoted(VisibleScope.Portfolio, emptyList()))
    }

    @Test
    fun `an empty watchlist does NOT fall back to everything`() {
        // The mirror-image mistake: falling back here would quietly undo the entire saving
        // every time the user opened an empty Watchlist tab.
        val holdingsOnly = rows.filter { !it.watchOnly }
        assertTrue(quoted(VisibleScope.Watchlist, holdingsOnly).isEmpty())
    }

    @Test
    fun `a portfolio of only watched symbols falls back rather than polling nothing`() {
        // Someone who holds nothing but watches five stocks still has a Portfolio tab.
        val watchOnly = rows.filter { it.watchOnly }
        assertEquals(all(), quoted(VisibleScope.Portfolio, watchOnly))
    }

    // ------------------------------------------------- the saving, stated

    @Test
    fun `the visible scopes are strictly smaller than polling everything`() {
        val everything = all().size
        assertTrue(quoted(VisibleScope.Portfolio).size < everything)
        assertTrue(quoted(VisibleScope.Watchlist).size < everything)
        assertEquals(1, quoted(VisibleScope.Detail("NVDA")).size)
        assertEquals(0, quoted(VisibleScope.None).size)
    }

    @Test
    fun `scope equality is what makes the setter free to call from composition`() {
        // setVisibleScope is invoked on every recomposition that touches navigation state and
        // returns immediately when nothing changed - which relies on these being data types.
        assertEquals(VisibleScope.Portfolio, VisibleScope.Portfolio)
        assertEquals(VisibleScope.Detail("NVDA"), VisibleScope.Detail("NVDA"))
        assertTrue(VisibleScope.Detail("NVDA") != VisibleScope.Detail("FIVE"))
        assertTrue(VisibleScope.Portfolio != VisibleScope.Watchlist)
    }
}
