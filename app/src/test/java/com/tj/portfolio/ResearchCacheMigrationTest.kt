package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.ui.PortfolioViewModel
import org.json.JSONObject
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
 * A CACHE WRITTEN BY THE PREVIOUS BUILD STILL HAS THE PREVIOUS BUILD'S BUG IN IT
 * (Round 66 audit, RES-1 / RES-5).
 *
 * ---- THE BUG RES-1 PROVES FIXED
 *
 * Before `conviction` existed, a fund Claude ADDED to the ETF list was stored with the model's
 * own number written straight into `score` as `conviction * 10`. Round 66 fixed the writer and
 * added `conviction` so the two could never be confused again - and stopped there.
 * `Keys.RESEARCH_CACHE` is a settings row: it survives the upgrade.
 *
 * So a row already on disk still came back with `score = 100, conviction = 0`, and every
 * downstream test of "did the app measure this?" is `score <= 0 && conviction > 0`, which is
 * false for it. The card drew a SCORE badge reading 100 out of 100 - the app's own scale, on a
 * row with no facts and no reason lines - and `carryEtfExplanations` re-added it on every
 * six-hourly rebuild while the sort put score first. It sat at row 1, above every fund the app
 * had actually measured, permanently, and a later import could not heal it because `merge`
 * leaves `score` alone.
 *
 * On the list TJ said he is going to buy from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchCacheMigrationTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    /** A payload in the shape the PREVIOUS build wrote: version 1, conviction inside score. */
    private fun oldCache(): JSONObject = JSONObject(
        """
        {
          "format": "portfolio-research",
          "version": 1,
          "generated": 1750000000000,
          "trending": [],
          "best": [],
          "etfs": [
            {
              "symbol": "VOO", "name": "Vanguard S&P 500 ETF", "price": 500.0,
              "score": 88,
              "reasons": ["Costs 0.03% a year", "\$1.20T under management"],
              "etf": { "expenseRatio": 0.03, "netAssets": 1.2e12, "fiveYearAnnualPct": 14.8 }
            },
            {
              "symbol": "VTI", "name": "Vanguard Total Stock Market ETF",
              "score": 100,
              "why": "The whole US market in one fund - Claude added this one."
            }
          ]
        }
        """.trimIndent()
    )

    @Test fun `a model's number stored as a score is moved back to conviction`() {
        val set = ResearchSet.fromJson(oldCache())
        val vti = set.etfs.first { it.symbol == "VTI" }
        assertEquals("a model's number must not survive as an app score", 0, vti.score)
        assertEquals("100 was conviction 10 all along", 10, vti.conviction)
        assertTrue("and the paragraph must survive the repair", vti.why.isNotBlank())
    }

    /** A row the app really scored is untouched - it has the working to prove it. */
    @Test fun `a row the app measured keeps its score`() {
        val set = ResearchSet.fromJson(oldCache())
        val voo = set.etfs.first { it.symbol == "VOO" }
        assertEquals(88, voo.score)
        assertEquals(0, voo.conviction)
        assertTrue("its facts must survive too", voo.etf != null)
    }

    /** Nothing written by THIS build is repaired - version 2 means the writer was already right. */
    /**
     * REG-2, a regression the first version of this migration introduced. Every reason line in
     * `ResearchScore.best` is conditional with no fallback, so a genuinely poor stock can
     * score in the twenties and emit NO reasons. The first repair asked only "no reasons and
     * no facts?" and would have relabelled that row "CLAUDE 2/10", told the screen reader it
     * was never scored by the app, and destroyed its real score on the next write - inventing
     * an attribution to a model that never saw it.
     */
    @Test fun `a scored stock with no reason lines is left alone`() {
        val old = JSONObject(
            """
            {
              "format": "portfolio-research", "version": 1, "generated": 1750000000000,
              "trending": [{"symbol":"GME","name":"GameStop","score":40}],
              "best": [{"symbol":"WEAK","name":"Weak Co","score":20}],
              "etfs": []
            }
            """.trimIndent()
        )
        val set = ResearchSet.fromJson(old)
        assertEquals(
            "a Best row the app scored must keep its score even with no reason lines",
            20, set.best.first().score
        )
        assertEquals(0, set.best.first().conviction)
        assertEquals(
            "and a Trending row is not in the fund list at all",
            40, set.trending.first().score
        )
        assertEquals(0, set.trending.first().conviction)
    }

    /** A fund row with a score that is not a multiple of ten was never a model's number. */
    @Test fun `a fund score that is not a multiple of ten is left alone`() {
        val old = JSONObject(
            """
            {
              "format": "portfolio-research", "version": 1, "generated": 1750000000000,
              "trending": [], "best": [],
              "etfs": [{"symbol":"ODD","name":"Odd Fund","score":73,"why":"a paragraph"}]
            }
            """.trimIndent()
        )
        assertEquals(73, ResearchSet.fromJson(old).etfs.first().score)
    }

    /** And one with no paragraph was not added by a model either. */
    @Test fun `a fund row with no paragraph is left alone`() {
        val old = JSONObject(
            """
            {
              "format": "portfolio-research", "version": 1, "generated": 1750000000000,
              "trending": [], "best": [],
              "etfs": [{"symbol":"QUIET","name":"Quiet Fund","score":90}]
            }
            """.trimIndent()
        )
        assertEquals(90, ResearchSet.fromJson(old).etfs.first().score)
    }

    @Test fun `a current payload passes through untouched`() {
        val current = oldCache().put("version", 2)
        val set = ResearchSet.fromJson(current)
        assertEquals(
            "a version-2 payload must not be second-guessed",
            100, set.etfs.first { it.symbol == "VTI" }.score
        )
    }

    /** And a round trip through this build's own writer is stable. */
    @Test fun `what this build writes it reads back unchanged`() {
        val once = ResearchSet.fromJson(oldCache())
        val twice = ResearchSet.fromJson(JSONObject(once.toJson().toString()))
        assertEquals(once.etfs.map { it.symbol }, twice.etfs.map { it.symbol })
        assertEquals(once.etfs.map { it.score }, twice.etfs.map { it.score })
        assertEquals(once.etfs.map { it.conviction }, twice.etfs.map { it.conviction })
    }

    // ------------------------------------------------------------------ RES-5

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    /**
     * `resetResearchPaging` had NO CALLERS, so a page count never went back down: press "Load
     * more" three times, wait for the thirty-minute rebuild, and the enrich pass read the
     * surviving 40 and fired up to fifty Nasdaq requests for a list nobody had asked to see
     * more of. It now resets, and resets only the sections the rebuild actually replaced -
     * the funds run on a six-hour clock of their own.
     */
    @Test fun `resetting the stock pages leaves the fund page alone`() {
        // `showMoreResearch` refuses to page past the end of a list, so the VM has to start
        // with real rows in it. It restores `Keys.RESEARCH_CACHE` at construction, so seeding
        // the row before constructing it is the whole setup.
        fun rows(prefix: String, n: Int) = (0 until n).joinToString(",") {
            """{"symbol":"$prefix$it","name":"Fund $it","score":${90 - it}}"""
        }
        Db(app).use { db ->
            db.set(
                com.tj.portfolio.data.Keys.RESEARCH_CACHE,
                """{"format":"portfolio-research","version":2,"generated":1750000000000,
                   "trending":[],"best":[${rows("B", 50)}],"etfs":[${rows("E", 50)}]}"""
            )
        }

        val vm = PortfolioViewModel(app).also { settle() }
        assertEquals("the seeded cache did not load", 50, vm.research.value.best.size)

        // Three taps of "Load more" on Best, two on the funds.
        repeat(3) { vm.showMoreResearch(ResearchSet.SECTION_BEST) }
        repeat(2) { vm.showMoreResearch(ResearchSet.SECTION_ETF) }
        settle()
        assertEquals(
            "the setup itself did not page - the rest of this test proves nothing",
            ResearchSet.PAGE * 4, vm.researchShown.value[ResearchSet.SECTION_BEST]
        )
        val fundsBefore = vm.researchShown.value[ResearchSet.SECTION_ETF]
        assertEquals(ResearchSet.PAGE * 3, fundsBefore)

        vm.resetResearchPaging(
            listOf(ResearchSet.SECTION_TRENDING, ResearchSet.SECTION_BEST)
        )
        settle()

        val shown = vm.researchShown.value
        assertEquals(
            "the stock page must go back to one page on a stock rebuild",
            ResearchSet.PAGE, shown[ResearchSet.SECTION_BEST]
        )
        assertEquals(
            "a stock rebuild must not collapse the fund list the user is part-way down",
            fundsBefore, shown[ResearchSet.SECTION_ETF]
        )
    }
}
