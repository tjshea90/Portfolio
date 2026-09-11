package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.withDayTradingLevels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
