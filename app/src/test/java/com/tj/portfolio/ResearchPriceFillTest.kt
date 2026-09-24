package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.net.DayTradingTechnicals
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.dropUnusableClaudeLevels
import com.tj.portfolio.ui.loggableDayTradingRows
import com.tj.portfolio.ui.mergeDayTradingTech
import com.tj.portfolio.ui.scoreDayTradingRow
import com.tj.portfolio.ui.sortDayTradingForActionability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ====================================================== dropUnusableClaudeLevels (Round 69)
    //
    // Claude may now set entry/stop/target itself. A pick it ADDED is a symbol the app has
    // never seen, so at merge time there is no price to check those levels against - the price
    // fill is the first moment that check is possible, and the first moment a decimal slip on
    // an unfamiliar ticker would otherwise reach the card looking authoritative.

    @Test fun `a Claude plan that survives contact with a real price is kept`() {
        val row = ResearchRow(
            symbol = "NEW", price = 22.5, entryPrice = 23.1, stopPrice = 22.4,
            targetPrice = 25.0, setup = "Gap and go", planByClaude = true
        )
        val out = dropUnusableClaudeLevels(listOf(row)).first()
        assertEquals(23.1, out.entryPrice, 0.001)
        assertTrue(out.planByClaude)
    }

    @Test fun `a Claude plan the real price contradicts is cleared, not shown`() {
        // $2.31 entry on a stock the app just quoted at $22.50 - a decimal point, not a trade.
        val row = ResearchRow(
            symbol = "NEW", price = 22.5, entryPrice = 2.31, stopPrice = 2.24,
            targetPrice = 2.50, setup = "Gap and go", trigger = "Buy 2.31.", planByClaude = true
        )
        val out = dropUnusableClaudeLevels(listOf(row)).first()
        assertEquals(0.0, out.entryPrice, 0.0)
        assertEquals(0.0, out.stopPrice, 0.0)
        assertEquals(0.0, out.targetPrice, 0.0)
        assertEquals("", out.setup)
        assertEquals("", out.trigger)
        assertFalse("it must stop claiming to be Claude's plan once cleared", out.planByClaude)
    }

    @Test fun `the app's own plan is never second-guessed by this pass`() {
        // Deliberately outside the half-to-double band: the app computed this from the same
        // price, so there is nothing here to disagree with and nothing to check.
        val row = ResearchRow(
            symbol = "GME", price = 22.5, entryPrice = 2.31, stopPrice = 2.24, targetPrice = 2.50
        )
        assertEquals(2.31, dropUnusableClaudeLevels(listOf(row)).first().entryPrice, 0.001)
    }

    @Test fun `a row with no price yet is left for the next pass`() {
        val row = ResearchRow(
            symbol = "NEW", price = 0.0, entryPrice = 2.31, stopPrice = 2.24,
            targetPrice = 2.50, planByClaude = true
        )
        assertEquals(2.31, dropUnusableClaudeLevels(listOf(row)).first().entryPrice, 0.001)
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

    /**
     * A DAY IDENTIFIER ON BOTH SIDES, because the per-field fallback for the INTRADAY readings
     * is now scoped to one session - see `effectiveTechnicals`. A row and a reading that do not
     * name the same day are two different sessions, and carrying VWAP or a session high across
     * that boundary is not staleness, it is the wrong number.
     */
    private val TODAY = "2026-09-11"

    private fun leveled(
        atr: Double = 0.0, vwap: Double = 0.0, orHigh: Double = 0.0, orLow: Double = 0.0,
        day: String = TODAY
    ) = ResearchRow(
        symbol = "GME", price = 22.5, score = 88,
        entryPrice = 22.5, stopPrice = 21.0, targetPrice = 25.5, setup = "Breakout",
        atr = atr, vwap = vwap, openingRangeHigh = orHigh, openingRangeLow = orLow,
        sessionDay = day
    )

    private fun tech(
        atr: Double = 0.0, vwap: Double = 0.0, orHigh: Double = 0.0, orLow: Double = 0.0,
        orComplete: Boolean = false, prevHigh: Double = 0.0, prevLow: Double = 0.0,
        prevClose: Double = 0.0, day: String = TODAY
    ) = DayTradingTechnicals.DayTechnicals(
        atr14 = atr, vwap = vwap, openingRangeHigh = orHigh, openingRangeLow = orLow,
        openingRangeComplete = orComplete, prevHigh = prevHigh, prevLow = prevLow,
        prevClose = prevClose, sessionDay = day
    )

    @Test fun `a previous session's VWAP is never carried into a new one`() {
        // THE 09-31 BUG. A row cached overnight holds yesterday's VWAP and session range. The
        // first intraday fetch of the new day fails, so the per-field fallback would hand those
        // to today's plan - and a finished session has spent its whole average daily range, so
        // every affected row would open the morning reading "already extended, do not chase".
        val yesterday = ResearchRow(
            symbol = "GME", price = 22.5, vwap = 21.9, sessionHigh = 23.0, sessionLow = 21.0,
            atrIntraday = 0.1, atr = 1.0, sessionDay = "2026-09-10"
        )
        val out = mergeDayTradingTech(yesterday, tech(atr = 1.0, day = "2026-09-11"))
        assertEquals("yesterday's VWAP must not survive the open", 0.0, out.vwap, 0.0)
        assertEquals(0.0, out.sessionHigh, 0.0)
        assertEquals(0.0, out.sessionLow, 0.0)
        // The DAILY readings are about completed days by construction and do survive.
        assertEquals(1.0, out.atr, 0.001)
    }

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

    @Test fun `the trade plan is recomputed when a tick has levels, and left whole when it has none`() {
        // ROUND 69: the assertion that used to live here was `entry == price`. That WAS the
        // bug Tj reported - a test can pin a defect in place just as firmly as it pins correct
        // behaviour, which is why this one is rewritten rather than adjusted.
        val stale = leveled(atr = 0.0)
        val upgraded = mergeDayTradingTech(
            stale, tech(atr = 1.0, prevHigh = 23.0, prevLow = 22.0, prevClose = 22.4)
        )
        assertTrue(
            "entry must be a trigger level above the price, not the price itself",
            upgraded.entryPrice > 22.5
        )
        assertTrue(upgraded.stopPrice < upgraded.entryPrice)
        assertTrue(upgraded.targetPrice > upgraded.entryPrice)
        assertTrue("the setup has to be named on the row", upgraded.setup.isNotBlank())
        assertTrue("the trigger has to say what to do", upgraded.trigger.isNotBlank())

        // Nothing to plan from this tick - the previous plan survives INTACT, all six fields.
        // A half-replaced plan would describe a trade nobody chose.
        val notUpgraded = mergeDayTradingTech(stale, tech(atr = 0.0))
        assertEquals(stale.entryPrice, notUpgraded.entryPrice, 0.001)
        assertEquals(stale.stopPrice, notUpgraded.stopPrice, 0.001)
        assertEquals(stale.targetPrice, notUpgraded.targetPrice, 0.001)
    }

    // ============================================================ scoreDayTradingRow (Round 72)
    //
    // Tj: "make the scores reflect a blend of how likely the stock is to rise... and how
    // confident this prediction is." [scoreDayTradingRow] is the LIVE half of that blend - the
    // one that runs once real VWAP/opening-range readings arrive; the build-time half
    // ([ResearchScore.dayTradingConfidence] with no technicals yet) has its own tests in
    // [com.tj.portfolio.DayTradingTest].

    private fun scoredRow(
        likelihood: Int = 50, confidence: Int = 40, price: Double = 22.5,
        reasons: List<String> = listOf("base reason")
    ) = ResearchRow(
        symbol = "GME", price = price,
        score = ResearchScore.blendedScore(likelihood, confidence),
        reasons = reasons, dtLikelihood = likelihood, dtConfidence = confidence
    )

    @Test fun `the likelihood half still updates from withTechnicals, exactly as before this round`() {
        val row = scoredRow(likelihood = 50, confidence = 0)
        val out = scoreDayTradingRow(row, tech(vwap = 21.0)) // price 22.5 is above VWAP 21.0
        assertTrue("the VWAP bonus must lift the likelihood", out.dtLikelihood > 50)
        assertTrue(out.reasons.any { it.contains("VWAP") })
    }

    @Test fun `nothing technical confirming leaves both halves exactly where they were`() {
        val row = scoredRow(likelihood = 50, confidence = 40)
        val out = scoreDayTradingRow(row, tech()) // no VWAP, no opening range at all
        assertEquals(50, out.dtLikelihood)
        assertEquals(40, out.dtConfidence)
        assertEquals(listOf("base reason"), out.reasons)
    }

    @Test fun `confidence is the build-time base plus whatever technicals confirm this tick`() {
        val row = scoredRow(likelihood = 50, confidence = 40)
        val out = scoreDayTradingRow(row, tech(vwap = 21.0)) // above VWAP: +20
        assertEquals(60, out.dtConfidence)
    }

    @Test fun `confidence does not move when the technicals check fails, not a negative`() {
        val row = scoredRow(likelihood = 50, confidence = 40)
        val out = scoreDayTradingRow(row, tech(vwap = 30.0)) // price 22.5 is BELOW VWAP 30.0
        assertEquals(40, out.dtConfidence)
    }

    @Test fun `confidence is clamped at 100, never allowed past it`() {
        val row = scoredRow(likelihood = 90, confidence = 80)
        val out = scoreDayTradingRow(
            row, tech(vwap = 21.0, orHigh = 22.0, orLow = 21.5, orComplete = true)
        )
        assertEquals(100, out.dtConfidence)
    }

    @Test fun `the displayed score is always the blend of the two halves returned alongside it`() {
        val row = scoredRow(likelihood = 50, confidence = 40)
        val out = scoreDayTradingRow(
            row, tech(vwap = 21.0, orHigh = 22.0, orLow = 21.5, orComplete = true)
        )
        assertEquals(ResearchScore.blendedScore(out.dtLikelihood, out.dtConfidence), out.score)
    }

    // ============================== the second code-review pass: a null plan now has two meanings

    /**
     * Levels a stock can no longer support: 110 with the session low at 99 and a 10-point
     * average day. `tradePlan` sees good inputs and returns null because the room left sits
     * below the last price - see `DayTradingTest`'s matching case.
     */
    private fun spentDay() = DayTradingTechnicals.DayTechnicals(
        atr14 = 1.0, atrIntraday = 1.0, vwap = 100.0, adr = 10.0,
        openingRangeHigh = 105.0, openingRangeLow = 102.0,
        sessionHigh = 110.0, sessionLow = 99.0,
        sessionLive = true, sessionDay = TODAY
    )

    private fun extended(planByClaude: Boolean = false) = ResearchRow(
        symbol = "GME", price = 110.0, score = 88,
        entryPrice = 105.1, stopPrice = 103.6, targetPrice = 109.0,
        setup = "Pullback", trigger = "a limit at 105.10", planNote = "morning plan",
        sessionDay = TODAY, planByClaude = planByClaude
    )

    @Test fun `a single declined tick is noise, not a verdict - the plan survives it`() {
        // THE ROUND 74 BUG. Several of `tradePlan`'s "no trade" verdicts are decided against a
        // boundary the LIVE PRICE sits right next to for exactly the stocks this section
        // screens for - already moving hard - so a price wobbling a few cents either side of it
        // flipped the verdict every 30-second tick. Clearing the whole grid on the FIRST decline
        // meant a plan the reader was just looking at - and the red planNote/beginner-summary
        // text drawn from it - would vanish and reappear on a clock nobody could see, describing
        // a trade whose real state had not changed. One declined tick must not withdraw a level.
        val out = mergeDayTradingTech(extended(), spentDay(), minutesLeft = 120)
        assertEquals("one declined tick must not clear a level already on screen", 105.1, out.entryPrice, 0.001)
        assertEquals(103.6, out.stopPrice, 0.001)
        assertEquals(109.0, out.targetPrice, 0.001)
        assertEquals("Pullback", out.setup)
        assertEquals("a decline is counted even though it is not yet acted on", 1, out.planDeclineStreak)
    }

    @Test fun `a single declined tick sets no reason yet - it has not been confirmed`() {
        // The reason is bookkeeping-free UI text with the same debounce as the levels it
        // explains: showing it a tick before the grid it describes has actually gone would be
        // exactly the flash Round 74 already fixed for the levels themselves.
        val out = mergeDayTradingTech(extended(), spentDay(), minutesLeft = 120)
        assertEquals("", out.planReason)
    }

    @Test fun `a confirmed decline sets the real reason, matching why tradePlan actually said no`() {
        val once = mergeDayTradingTech(extended(), spentDay(), minutesLeft = 120)
        val out = mergeDayTradingTech(once, spentDay(), minutesLeft = 120)
        assertTrue(
            "reason must match tradePlanDeclineReason for the same inputs",
            out.planReason.contains("Already moved most of today's likely range")
        )
    }

    @Test fun `a real plan returning clears any reason that was showing`() {
        val hadReason = extended().copy(entryPrice = 0.0, planReason = "Already moved most of today's likely range - not enough room left to target a worthwhile reward.")
        val recovered = mergeDayTradingTech(
            hadReason,
            DayTradingTechnicals.DayTechnicals(
                atr14 = 1.0, prevHigh = 115.0, prevLow = 105.0, prevClose = 108.0, sessionDay = TODAY
            ),
            minutesLeft = 120
        )
        assertTrue(recovered.entryPrice > 0.0)
        assertEquals("", recovered.planReason)
    }

    @Test fun `levels the engine has DECLINED to stand behind on two ticks running are cleared, not frozen on screen`() {
        // THE ORIGINAL ROUND 73 BUG, STILL FIXED. Round 73 made "no plan" reachable mid-session
        // for the first time: once a stock has spent its average daily range, every subsequent
        // tick declines. The old rule - keep the previous plan whenever a tick produced none -
        // then pinned the MORNING's entry, stop and target to the card for the rest of the
        // afternoon, describing a trade the app itself no longer believed in, directly above a
        // beginner card reading "too late for this one today". A SECOND straight decline still
        // clears it - the Round 74 hysteresis above only buys one extra tick, not indefinitely.
        val once = mergeDayTradingTech(extended(), spentDay(), minutesLeft = 120)
        val out = mergeDayTradingTech(once, spentDay(), minutesLeft = 120)
        assertEquals("a level the engine withdrew twice running is a blank", 0.0, out.entryPrice, 0.0)
        assertEquals(0.0, out.stopPrice, 0.0)
        assertEquals(0.0, out.targetPrice, 0.0)
        assertEquals("", out.setup)
        assertEquals("", out.trigger)
        assertEquals("", out.planNote)
        assertEquals(2, out.planDeclineStreak)
    }

    @Test fun `a real plan returning resets the decline streak to zero`() {
        // A row that has already declined once (the streak is pre-set directly, rather than
        // chained through `spentDay()`'s reading, so this test is not also fighting
        // `effectiveTechnicals`'s carry-forward of `adr`/`sessionLow`/etc - that mechanism has
        // its own tests above; this one isolates only the streak-reset behaviour).
        val hadOneDecline = extended().copy(planDeclineStreak = 1)
        // The same prior-session structure the already-proven "recomputed... left whole"
        // test above uses, scaled to this row's $110 price - a real breakout plan.
        val recovered = mergeDayTradingTech(
            hadOneDecline,
            DayTradingTechnicals.DayTechnicals(
                atr14 = 1.0, prevHigh = 115.0, prevLow = 105.0, prevClose = 108.0, sessionDay = TODAY
            ),
            minutesLeft = 120
        )
        assertTrue(
            "a real plan must have been found for this test to mean anything",
            recovered.entryPrice > 0.0
        )
        assertEquals(
            "a real plan is not a verdict this function debounces - it takes effect immediately",
            0, recovered.planDeclineStreak
        )
    }

    @Test fun `but levels the engine could not even LOOK at survive, exactly as before`() {
        // The other half of the same distinction, and the behaviour this file already protects
        // elsewhere: with no volatility reading anywhere - this tick's or the row's -
        // `tradePlan` bails before judging anything, and a transient gap must never erase a
        // real plan. Only a DECISION clears levels.
        val blind = DayTradingTechnicals.DayTechnicals(
            vwap = 100.0, sessionHigh = 110.0, sessionLow = 99.0,
            sessionLive = true, sessionDay = TODAY
        )
        val out = mergeDayTradingTech(extended(), blind, minutesLeft = 120)
        assertEquals(105.1, out.entryPrice, 0.001)
        assertEquals(103.6, out.stopPrice, 0.001)
        assertEquals(109.0, out.targetPrice, 0.001)
        assertEquals("Pullback", out.setup)
    }

    @Test fun `a Claude-imported plan keeps its levels but not a stale clock verdict`() {
        // BOTH HALVES OF THE OTHER STICKING BUG IN ONE ROW. `mergeDayTradingTech` never
        // re-plans a Claude row, so before this pass its `tooLateToStart` was whatever the flag
        // happened to be when the plan was imported or built - and nothing could ever clear or
        // set it again. Imported at 09:45 and read at 15:45, the card stayed confidently
        // startable all afternoon. The flag is read off the clock now, for every row; the
        // LEVELS, which are Claude's and not the app's, are still left alone.
        val late = mergeDayTradingTech(extended(planByClaude = true), spentDay(), minutesLeft = 10)
        assertTrue("10 minutes left is too late to start", late.tooLateToStart)
        assertEquals("Claude's own entry is never overwritten here", 105.1, late.entryPrice, 0.001)
        assertEquals(109.0, late.targetPrice, 0.001)

        val early = mergeDayTradingTech(extended(planByClaude = true), spentDay(), minutesLeft = 300)
        assertFalse("and it clears again on a row imported earlier", early.tooLateToStart)
    }

    // ---- A REAL SESSION ROLLOVER (v7.33's fix, which shipped with no test of its own, and the
    // Claude-plan half of it found by the 2026-09-22 full-tests audit).

    /** A reading from the NEW session with enough structure for the engine to plan a breakout
     *  at this row's $110 price - the same shape `a real plan returning...` uses. */
    private fun newSessionPlannable() = DayTradingTechnicals.DayTechnicals(
        atr14 = 1.0, prevHigh = 115.0, prevLow = 105.0, prevClose = 108.0, sessionDay = TODAY
    )

    @Test fun `yesterday's app plan is cleared on the new session's first declining tick, no debounce`() {
        val yesterday = extended().copy(sessionDay = "2026-09-10")
        val out = mergeDayTradingTech(yesterday, spentDay(), minutesLeft = 120)
        assertEquals("a level from another session must not wait a tick under today's date", 0.0, out.entryPrice, 0.0)
        assertEquals(0.0, out.stopPrice, 0.0)
        assertEquals(0.0, out.targetPrice, 0.0)
        assertEquals("the new session's streak starts at this one decline", 1, out.planDeclineStreak)
        assertEquals(TODAY, out.sessionDay)
    }

    @Test fun `yesterday's Claude plan is handed back to the engine, not left blank all session`() {
        val yesterday = extended(planByClaude = true).copy(sessionDay = "2026-09-10")
        val out = mergeDayTradingTech(yesterday, newSessionPlannable(), minutesLeft = 300)
        assertFalse("a rollover ends the Claude plan's claim on the row", out.planByClaude)
        assertTrue("the engine plans the new session straight away", out.entryPrice > 0.0)
        assertTrue("and it is not yesterday's Claude entry", kotlin.math.abs(out.entryPrice - 105.1) > 0.001)
    }

    @Test fun `yesterday's Claude plan with nothing to plan today is cleared and stays the engine's`() {
        val yesterday = extended(planByClaude = true).copy(sessionDay = "2026-09-10")
        val out = mergeDayTradingTech(yesterday, spentDay(), minutesLeft = 120)
        assertFalse(out.planByClaude)
        assertEquals(0.0, out.entryPrice, 0.0)
        assertEquals(0.0, out.targetPrice, 0.0)
        // And the NEXT tick is an ordinary engine tick, not a frozen Claude row.
        val next = mergeDayTradingTech(out, newSessionPlannable(), minutesLeft = 290)
        assertTrue("the engine keeps re-planning the row on later ticks", next.entryPrice > 0.0)
    }

    @Test fun `a Claude plan within its own session still stands and keeps its label`() {
        val out = mergeDayTradingTech(extended(planByClaude = true), newSessionPlannable(), minutesLeft = 300)
        assertTrue(out.planByClaude)
        assertEquals("Claude's own entry is never overwritten mid-session", 105.1, out.entryPrice, 0.001)
    }

    @Test fun `a Claude plan on a row with no session reading yet still stands`() {
        // What `DayTradingBridge.merge` leaves after a morning import onto yesterday's row.
        val justImported = extended(planByClaude = true).copy(sessionDay = "")
        val out = mergeDayTradingTech(justImported, newSessionPlannable(), minutesLeft = 300)
        assertTrue(out.planByClaude)
        assertEquals(105.1, out.entryPrice, 0.001)
        assertEquals(TODAY, out.sessionDay)
    }

    // ==================================== sortDayTradingForActionability (Round 75)
    //
    // Tj: "try to find and show the actual stocks that I can act on currently at the top of
    // the list, with buy and sell target prices clearly listed."

    private fun row(symbol: String, entry: Double, likelihood: Int, confidence: Int) = ResearchRow(
        symbol = symbol, price = 100.0, entryPrice = entry,
        dtLikelihood = likelihood, dtConfidence = confidence
    )

    @Test fun `actionable rows sort above declined rows regardless of score`() {
        val weakButActionable = row("WEAK", entry = 10.0, likelihood = 20, confidence = 20)
        val strongButDeclined = row("STRONG", entry = 0.0, likelihood = 90, confidence = 90)
        val out = sortDayTradingForActionability(listOf(strongButDeclined, weakButActionable))
        assertEquals(listOf("WEAK", "STRONG"), out.map { it.symbol })
    }

    @Test fun `within each group, higher blended score sorts first`() {
        val low = row("LOW", entry = 10.0, likelihood = 40, confidence = 40)
        val high = row("HIGH", entry = 10.0, likelihood = 90, confidence = 90)
        val out = sortDayTradingForActionability(listOf(low, high))
        assertEquals(listOf("HIGH", "LOW"), out.map { it.symbol })
    }

    @Test fun `a tie on both keys keeps the original relative order - a stable sort, not a coin flip`() {
        val a = row("A", entry = 0.0, likelihood = 50, confidence = 50)
        val b = row("B", entry = 0.0, likelihood = 50, confidence = 50)
        val out = sortDayTradingForActionability(listOf(a, b))
        assertEquals(listOf("A", "B"), out.map { it.symbol })
    }

    @Test fun `a Claude-authored actionable row has no likelihood to rank by, but still sorts above every decline`() {
        // planByClaude rows carry dtLikelihood/dtConfidence = 0 (ResearchRow.dtLikelihood's own
        // header) - so a real Claude plan must still out-rank every row the app declined, even
        // though its blended score is the lowest possible.
        val claudePlan = row("CLAUDE", entry = 25.0, likelihood = 0, confidence = 0)
            .copy(planByClaude = true)
        val appDeclined = row("DECLINED", entry = 0.0, likelihood = 99, confidence = 99)
        val out = sortDayTradingForActionability(listOf(appDeclined, claudePlan))
        assertEquals(listOf("CLAUDE", "DECLINED"), out.map { it.symbol })
    }
    // ---- THE LIVE TICK PLANS AGAINST THE PRICE NOW (full-tests audit 2026-09-22, D-H1).
    // `row.price` used to stay at whatever the screener saw when the list was built.

    @Test fun `a reading from today's bars moves the row's price to the latest print`() {
        val row = leveled(atr = 1.0)
        val out = mergeDayTradingTech(row, tech(atr = 1.0).copy(lastPrice = 24.1))
        assertEquals(24.1, out.price, 1e-9)
    }

    @Test fun `with no bars dated today the row keeps its own price`() {
        val row = leveled(atr = 1.0)
        val out = mergeDayTradingTech(row, tech(atr = 1.0, day = "").copy(lastPrice = 24.1))
        assertEquals("a last print from a finished session is not today's price", 22.5, out.price, 1e-9)
    }

    @Test fun `the day's change follows the live price only while the session is open`() {
        val row = leveled(atr = 1.0).copy(changePct = 3.0)
        val open = mergeDayTradingTech(row, tech(atr = 1.0, prevClose = 20.0)
            .copy(lastPrice = 22.0, sessionLive = true))
        assertEquals(10.0, open.changePct, 1e-9)
        val pre = mergeDayTradingTech(row, tech(atr = 1.0, prevClose = 20.0)
            .copy(lastPrice = 22.0, sessionLive = false))
        assertEquals("pre-market keeps the screener's figure", 3.0, pre.changePct, 1e-9)
        assertEquals("but the price itself is still the latest print", 22.0, pre.price, 1e-9)
    }

    @Test fun `the plan is computed from the live price, not the build-time one`() {
        // Pivot R1 is ~113.67 and the prior high 115. Built at 110, the next level overhead is
        // R1; the stock has since traded up to 114, past R1, so the breakout is now over 115.
        val base = newSessionPlannable()
        val stale = mergeDayTradingTech(extended().copy(sessionDay = TODAY, price = 110.0),
            base, minutesLeft = 300)
        val live = mergeDayTradingTech(extended().copy(sessionDay = TODAY, price = 110.0),
            base.copy(lastPrice = 114.0), minutesLeft = 300)
        assertEquals(114.0, live.price, 1e-9)
        assertTrue("built-time plan breaks out over R1", stale.entryPrice in 113.6..114.0)
        assertTrue("live plan breaks out over the prior high", live.entryPrice > 115.0)
    }
    // ---- ONLY A LIVE INSTRUCTION IS LOGGED AS A RECOMMENDATION (full-tests audit, D-M3).

    @Test fun `a too-late or pending-decline plan is not logged, a live one is`() {
        val live = extended()
        val tooLate = extended().copy(symbol = "LATE", tooLateToStart = true)
        val declining = extended().copy(symbol = "DECL", planDeclineStreak = 1)
        val yesterday = extended().copy(symbol = "OLD", sessionDay = "2026-09-10")
        val everyone = setOf("GME", "LATE", "DECL", "OLD")
        assertEquals(listOf("GME"),
            loggableDayTradingRows(listOf(live, tooLate, declining, yesterday), TODAY, everyone)
                .map { it.symbol })
    }

    /**
     * Full test 2026-09-24, D-1: A PLAN IS ONLY LOGGED ON THE TICK THAT RE-PLANNED IT LIVE.
     * This morning's pre-market plan carries `sessionDay == today` too, so the date cannot
     * tell them apart; the sweep's own "fetched live this tick" set can. A row whose fetch
     * failed on the busy first open tick, or any row published by a caller that did not
     * re-plan it (Best's analyst pass, an ETF rebuild, an import), is not in that set.
     */
    @Test fun `D-1 a same-day plan that was not re-planned live this tick is not logged`() {
        val premarket = extended()   // sessionDay = TODAY, levels set: indistinguishable by date
        assertEquals(emptyList<String>(),
            loggableDayTradingRows(listOf(premarket), TODAY, liveNow = emptySet()).map { it.symbol })
        assertEquals(emptyList<String>(),
            loggableDayTradingRows(listOf(premarket), TODAY, liveNow = setOf("OTHER")).map { it.symbol })
        assertEquals(listOf("GME"),
            loggableDayTradingRows(listOf(premarket), TODAY, liveNow = setOf("GME")).map { it.symbol })
    }
}
