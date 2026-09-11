package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.net.DayTradingTechnicals
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.mergeDayTradingTech
import com.tj.portfolio.ui.withDayTradingLevels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * TRENDING ROWS THAT ARRIVE WITHOUT A PRICE (Round 66 audit, R2).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * Trending's candidate set is the union of three sources - Reddit chatter, Yahoo's own
 * trending list, and whatever the market-wide headlines are about - but every NUMBER on a
 * trending row is read out of `universe`, the map of what the nine equity screeners returned.
 * A stock that is trending without also being a day gainer, a day loser, most active, most
 * shorted or one of the other six is simply not in that map, so `Research.buildTrending`
 * emitted it with an empty name, a price of 0.0 and a day change of 0.0.
 *
 * Nothing downstream went back for them. `enrichPass` walks Best and only Best, and the
 * quote fill had exactly one caller - the Claude import path - so a row that arrived blank
 * from a normal rebuild stayed blank for as long as it trended. That is the worst possible
 * set to lose, because a symbol the screeners ALREADY carry is not the interesting half of
 * a trending list.
 *
 * `pricelessRows` is the selection as a pure function, so the rule can be checked without a
 * network: which symbols the fill asks about, in what order, and what it leaves alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchPriceFillTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun vm(): PortfolioViewModel {
        val v = PortfolioViewModel(app)
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
        return v
    }

    private fun row(sym: String, price: Double = 0.0, score: Int = 0) =
        ResearchRow(symbol = sym, price = price, score = score)

    /** The whole point: a trending row with no price is asked about. */
    @Test fun picksUpTrendingRowsWithNoPrice() {
        val set = ResearchSet(
            trending = listOf(row("GME", 0.0), row("NVDA", 123.4), row("BBBY", 0.0))
        )
        assertEquals(listOf("GME", "BBBY"), vm().pricelessRows(set))
    }

    /** A row that already has a price costs nothing - it is not in the request. */
    @Test fun leavesPricedRowsAlone() {
        val set = ResearchSet(
            trending = listOf(row("NVDA", 123.4), row("AAPL", 200.0)),
            best = listOf(row("MSFT", 400.0))
        )
        assertTrue(vm().pricelessRows(set).isEmpty())
    }

    /**
     * ETFs are excluded on purpose. They are built by `loadEtfs` on a six-hour clock and are
     * not part of what a stock rebuild produced; including them would re-request a fund
     * Yahoo cannot resolve on every half-hourly rebuild, forever.
     */
    @Test fun ignoresTheEtfSection() {
        val set = ResearchSet(
            trending = listOf(row("GME", 0.0)),
            etfs = listOf(row("XXXX", 0.0), row("YYYY", 0.0))
        )
        assertEquals(listOf("GME"), vm().pricelessRows(set))
    }

    /** Best is included - a Claude-added stock lands there with no price too. */
    @Test fun coversBestAsWell() {
        val set = ResearchSet(
            trending = listOf(row("GME", 0.0)),
            best = listOf(row("PLTR", 0.0), row("MSFT", 400.0))
        )
        assertEquals(listOf("GME", "PLTR"), vm().pricelessRows(set))
    }

    /** One symbol in both lists is one request, not two. */
    @Test fun deduplicates() {
        val set = ResearchSet(
            trending = listOf(row("GME", 0.0)),
            best = listOf(row("GME", 0.0))
        )
        assertEquals(listOf("GME"), vm().pricelessRows(set))
    }

    /**
     * The cap holds, and because both lists arrive sorted by score the survivors are the ones
     * nearest the top of the screen - not an arbitrary twenty.
     */
    @Test fun capsTheBatchAndKeepsTheHighestScoringFirst() {
        val many = (1..40).map { row("S$it", 0.0, score = 100 - it) }
        val picked = vm().pricelessRows(ResearchSet(trending = many), cap = 20)
        assertEquals(20, picked.size)
        assertEquals("S1", picked.first())
        assertEquals("S20", picked.last())
    }

    /** A blank symbol is not a request anyone can make. */
    @Test fun dropsBlankSymbols() {
        val set = ResearchSet(trending = listOf(row("", 0.0), row("GME", 0.0)))
        assertEquals(listOf("GME"), vm().pricelessRows(set))
    }

    /**
     * A negative price is as unusable as a zero one. `buildTrending` cannot produce one, but
     * an imported answer file can, and the row would render just as blank.
     */
    @Test fun treatsNegativePriceAsMissing() {
        val set = ResearchSet(trending = listOf(row("GME", -1.0)))
        assertEquals(listOf("GME"), vm().pricelessRows(set))
    }

    // ============================================================ withDayTradingLevels
    //
    // A DAY-TRADING ROW CLAUDE ADDED HAS NO TRADE LEVELS EITHER (Round 67) - only a symbol.
    // `withDayTradingLevels` is the fill for that, the same shape as the price fill above:
    // pure, so it can be checked with no network and no ViewModel, the moment a real price
    // exists to compute entry/stop/target from.

    /** The whole point: a priced row with no levels yet gets them computed. */
    @Test fun computesLevelsForAPricedRowThatHasNone() {
        val out = withDayTradingLevels(listOf(row("GME", price = 20.0)))
        val g = out.first()
        assertEquals(20.0, g.entryPrice, 0.001)
        assertTrue("stop should sit below entry", g.stopPrice < g.entryPrice)
        assertTrue("target should sit above entry", g.targetPrice > g.entryPrice)
    }

    /** Still no price - Claude's pick has not been quoted yet, so there is nothing to compute from. */
    @Test fun leavesAPricelessRowAlone() {
        val out = withDayTradingLevels(listOf(row("GME", price = 0.0)))
        assertEquals(0.0, out.first().entryPrice, 0.0)
        assertEquals(0.0, out.first().stopPrice, 0.0)
        assertEquals(0.0, out.first().targetPrice, 0.0)
    }

    /** A row the screener already built carries its own levels - this fill must not touch it. */
    @Test fun leavesAnAlreadyLeveledRowUntouched() {
        val already = ResearchRow(
            symbol = "AMD", price = 150.0, entryPrice = 150.0, stopPrice = 145.0, targetPrice = 160.0
        )
        val out = withDayTradingLevels(listOf(already))
        assertEquals(150.0, out.first().entryPrice, 0.001)
        assertEquals(145.0, out.first().stopPrice, 0.001)
        assertEquals(160.0, out.first().targetPrice, 0.001)
    }

    // ============================================================ mergeDayTradingTech
    //
    // A REAL BUG, CAUGHT BY CODE REVIEW BEFORE SHIPPING (Round 68). The daily-bar fetch
    // (ATR) and the intraday-bar fetch (VWAP, opening range) that make up one
    // `DayTradingTechnicals.fetch` call can succeed or fail INDEPENDENTLY - one host cooling
    // down while the other answers, say - so a technicals reading that is not "empty" can
    // still carry a real zero for exactly the field that failed. `mergeDayTradingTech` is
    // what has to tell "this field genuinely failed this tick" from "the whole reading is
    // fresh", and get it right per field, not per reading.

    private fun leveled(atr: Double = 0.0, vwap: Double = 0.0, orHigh: Double = 0.0, orLow: Double = 0.0) =
        ResearchRow(
            symbol = "GME", price = 22.5, score = 88,
            entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5,
            atr = atr, vwap = vwap, openingRangeHigh = orHigh, openingRangeLow = orLow
        )

    private fun tech(
        atr: Double = 0.0, vwap: Double = 0.0, orHigh: Double = 0.0, orLow: Double = 0.0,
        orComplete: Boolean = false
    ) = DayTradingTechnicals.DayTechnicals(atr, vwap, orHigh, orLow, orComplete)

    @Test fun `a fresh full reading replaces every technicals field`() {
        val out = mergeDayTradingTech(
            leveled(atr = 1.0, vwap = 21.0, orHigh = 22.0, orLow = 21.5),
            tech(atr = 1.2, vwap = 21.9, orHigh = 22.2, orLow = 21.6)
        )
        assertEquals(1.2, out.atr, 0.001)
        assertEquals(21.9, out.vwap, 0.001)
        assertEquals(22.2, out.openingRangeHigh, 0.001)
        assertEquals(21.6, out.openingRangeLow, 0.001)
    }

    /** THE BUG ITSELF: only the ATR half of this tick's fetch failed - VWAP still came back. */
    @Test fun `a partially failed fetch keeps the previously-good field it did not answer`() {
        val hadRealAtr = leveled(atr = 2.5, vwap = 0.0, orHigh = 0.0, orLow = 0.0)
        // This tick's daily-bar (ATR) request failed - atr comes back 0.0 - but the
        // intraday-bar (VWAP) request succeeded.
        val thisTick = tech(atr = 0.0, vwap = 21.9)
        val out = mergeDayTradingTech(hadRealAtr, thisTick)
        assertEquals(
            "a transient ATR failure must not erase the real ATR already on the row",
            2.5, out.atr, 0.001
        )
        assertEquals(21.9, out.vwap, 0.001) // and the field that DID answer still updates
    }

    @Test fun `the reverse also holds - a failed VWAP half keeps the row's real VWAP`() {
        val hadRealVwap = leveled(atr = 0.0, vwap = 21.9)
        val out = mergeDayTradingTech(hadRealVwap, tech(atr = 1.5, vwap = 0.0))
        assertEquals(21.9, out.vwap, 0.001)
        assertEquals(1.5, out.atr, 0.001)
    }

    @Test fun `a genuinely fresh zero is not possible to distinguish from a failure, and that is by design`() {
        // Documented, not a bug: DayTradingTechnicals never returns a true zero for a real
        // reading (ATR and VWAP are always positive prices/ranges), so 0.0 IS the failure
        // sentinel everywhere in this system - `mergeDayTradingTech` relies on exactly that.
        val out = mergeDayTradingTech(leveled(atr = 3.0), tech(atr = 0.0))
        assertEquals(3.0, out.atr, 0.001)
    }

    @Test fun `entry-stop-target upgrade only when this tick's ATR is real`() {
        val stale = leveled(atr = 0.0) // still on tradeLevels()'s pre-ATR estimate
        val upgraded = mergeDayTradingTech(stale, tech(atr = 1.0))
        assertEquals(22.5, upgraded.entryPrice, 0.001)
        assertTrue(upgraded.stopPrice < upgraded.entryPrice)

        // No real ATR this tick either (both this reading's and the row's are 0) - the
        // pre-existing entry/stop/target from the row are left exactly as they were.
        val notUpgraded = mergeDayTradingTech(stale, tech(atr = 0.0))
        assertEquals(stale.entryPrice, notUpgraded.entryPrice, 0.001)
        assertEquals(stale.stopPrice, notUpgraded.stopPrice, 0.001)
        assertEquals(stale.targetPrice, notUpgraded.targetPrice, 0.001)
    }
}
