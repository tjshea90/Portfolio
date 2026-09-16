package com.tj.portfolio

import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.net.DayTradingEval
import com.tj.portfolio.net.DayTradingEval.IntradayBar
import com.tj.portfolio.net.ResearchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tj's own words, twice over, are the whole point of this file: *"make sure it doesn't delete
 * or modify any of the data"* and *"it makes no sense to gauge how well the advice is based on
 * stock price movement before the advice was ever given."* [evaluate] is where both of those
 * actually get enforced, so it is tested exhaustively - a wrong verdict here is a wrong answer
 * to the one question this whole feature exists to answer.
 */
class DayTradingEvalTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    private fun bar(t: Long, high: Double, low: Double, close: Double) = IntradayBar(t, high, low, close)

    // ------------------------------------------------------------------ parsing (live fixture)

    @Test fun parseBarsReadsARealCapturedYahooResponse() {
        val bars = DayTradingEval.parseBars(res("yahoo_chart_5m_aapl_20260915.json"))
        assertEquals(79, bars.size)
        assertEquals(1_789_479_000L, bars.first().t)
        assertEquals(1_789_502_400L, bars.last().t)
        assertEquals(bars.map { it.t }.sorted(), bars.map { it.t })
        for (b in bars) {
            assertTrue("high must be >= low", b.high >= b.low)
            assertTrue("close must sit within the bar's own range", b.close <= b.high && b.close >= b.low)
            assertTrue(b.high > 0.0 && b.low > 0.0 && b.close > 0.0)
        }
    }

    @Test fun parseBarsIsTotalOnGarbage() {
        assertEquals(emptyList<IntradayBar>(), DayTradingEval.parseBars("not json"))
        assertEquals(emptyList<IntradayBar>(), DayTradingEval.parseBars("{}"))
        assertEquals(
            emptyList<IntradayBar>(),
            DayTradingEval.parseBars("""{"chart":{"result":[{"meta":{}}]}}""")
        )
    }

    // ------------------------------------------------------------------ session bounds

    /** Cross-checked against a live request for this exact symbol/day - the response's own
     *  `tradingPeriods` reported {"start":1789479000,"end":1789502400}. */
    @Test fun sessionBoundsMatchTheRealRegularSessionForThatDay() {
        val (open, close) = DayTradingEval.sessionBoundsMs("20260915")!!
        assertEquals(1_789_479_000_000L, open)
        assertEquals(1_789_502_400_000L, close)
    }

    @Test fun sessionBoundsRejectsAnUnparseableKey() {
        assertNull(DayTradingEval.sessionBoundsMs(""))
        assertNull(DayTradingEval.sessionBoundsMs("2026-09-15"))
        assertNull(DayTradingEval.sessionBoundsMs("2026091"))
        assertNull(DayTradingEval.sessionBoundsMs("abcdefgh"))
    }

    // ------------------------------------------------------------------ trigger direction

    @Test fun onlyAPullbackWaitsForPriceToFall() {
        assertTrue(DayTradingEval.entryRises(ResearchScore.SETUP_BREAKOUT))
        assertTrue(DayTradingEval.entryRises(ResearchScore.SETUP_RECLAIM))
        assertTrue("an unrecognised (e.g. Claude-authored) setup defaults to rising, the more " +
            "common of the two", DayTradingEval.entryRises("Some Claude-invented setup name"))
        assertEquals(false, DayTradingEval.entryRises(ResearchScore.SETUP_PULLBACK))
    }

    // ------------------------------------------------------------------ evaluate(): no lookahead

    /**
     * THE ONE TEST THAT MATTERS MOST. Tj: *"It makes no sense to gauge how well the advice is
     * based on stock price movement before the advice was ever given."* Bars 0-2 touch (and
     * would "win") the target well before [recordedAt] - a buggy filter would report WIN. The
     * only bars that exist AFTER [recordedAt] never reach entry at all.
     */
    @Test fun barsBeforeTheRecommendationAreNeverConsidered() {
        val recordedAt = 2000L
        val bars = listOf(
            // BEFORE recordedAt - touches both target(20) and stop(9.0) already. A buggy
            // filter would report WIN or LOSS from these; the correct answer ignores them
            // entirely because entry(15) never triggers again afterward.
            bar(0L, high = 20.0, low = 9.0, close = 15.0),
            bar(1L, high = 20.0, low = 9.0, close = 15.0),
            // AFTER recordedAt (t*1000 >= 2000) - stays well below entry(15) the whole time.
            bar(2L, high = 11.0, low = 10.5, close = 10.8),
            bar(3L, high = 11.2, low = 10.6, close = 11.0),
            bar(4L, high = 11.3, low = 10.7, close = 11.1)
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 15.0, stop = 9.0, target = 20.0,
            recordedAt = recordedAt, bars = bars, sessionStillOpen = false
        )
        assertEquals(
            "the only bars after recordedAt never reach entry(15) at all",
            DayTradingOutcome.NO_ENTRY, outcome
        )
        assertNull(exit)
    }

    // ------------------------------------------------------------------ evaluate(): breakout (rising)

    @Test fun risingEntryTriggersThenHitsTargetBeforeStop() {
        val bars = listOf(
            bar(0L, 9.5, 9.0, 9.2),     // before entry
            bar(1L, 10.1, 9.4, 10.0),   // entry(10) triggers - high >= 10
            bar(2L, 10.3, 9.9, 10.2),
            bar(3L, 12.1, 10.1, 12.0)   // target(12) hit, stop(8.5) not
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.WIN, outcome)
        assertEquals(12.0, exit!!, 1e-9)
    }

    @Test fun risingEntryTriggersThenHitsStopBeforeTarget() {
        val bars = listOf(
            bar(0L, 10.1, 9.9, 10.0),   // entry(10) triggers
            bar(1L, 10.2, 8.4, 8.5)     // stop(8.5) hit, target(12) not
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.LOSS, outcome)
        assertEquals(8.5, exit!!, 1e-9)
    }

    // ------------------------------------------------------------------ evaluate(): pullback (falling)

    @Test fun fallingEntryWaitsForAPriceDropThenHitsTarget() {
        val bars = listOf(
            bar(0L, 15.5, 15.0, 15.2),   // above entry(14), not triggered
            bar(1L, 14.5, 13.9, 14.0),   // entry(14) triggers - low <= 14
            bar(2L, 16.1, 13.8, 16.0)    // target(16) hit, stop(12) not
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_PULLBACK, entry = 14.0, stop = 12.0, target = 16.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.WIN, outcome)
        assertEquals(16.0, exit!!, 1e-9)
    }

    @Test fun fallingEntryTriggersThenHitsStop() {
        val bars = listOf(
            bar(0L, 14.5, 13.9, 14.0),   // entry(14) triggers
            bar(1L, 13.9, 11.9, 12.0)    // stop(12) hit
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_PULLBACK, entry = 14.0, stop = 12.0, target = 16.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.LOSS, outcome)
        assertEquals(12.0, exit!!, 1e-9)
    }

    // ------------------------------------------------------------------ ambiguous same-bar case

    /** The one bar where this cannot actually know which came first - read conservatively. */
    @Test fun targetAndStopBothReachableInTheSameBarReadsAsTheStop() {
        val bars = listOf(
            bar(0L, 10.1, 9.9, 10.0),    // entry(10) triggers
            bar(1L, 12.5, 8.0, 10.5)     // BOTH target(12) and stop(8.5) are inside this bar's range
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.LOSS, outcome)
        assertEquals(8.5, exit!!, 1e-9)
    }

    @Test fun entryTargetAndStopAllInTheSameOpeningBarStillResolves() {
        val bars = listOf(bar(0L, 12.5, 8.0, 10.5)) // entry(10), target(12) and stop(8.5) all inside
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.LOSS, outcome)
        assertEquals(8.5, exit!!, 1e-9)
    }

    // ------------------------------------------------------------------ pending / no-entry / closed flat

    @Test fun entryNotYetTriggeredWithTheSessionStillOpenIsPending() {
        val bars = listOf(bar(0L, 9.5, 9.0, 9.2))
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = true
        )
        assertEquals(DayTradingOutcome.PENDING, outcome)
        assertNull(exit)
    }

    @Test fun entryNeverTriggeredByACloseSessionIsNoEntry() {
        val bars = listOf(bar(0L, 9.5, 9.0, 9.2))
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.NO_ENTRY, outcome)
        assertNull(exit)
    }

    @Test fun entryTriggeredNeitherHitYetSessionStillOpenIsPending() {
        val bars = listOf(
            bar(0L, 10.1, 9.9, 10.0),
            bar(1L, 10.4, 10.0, 10.2)
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = true
        )
        assertEquals(DayTradingOutcome.PENDING, outcome)
        assertNull(exit)
    }

    /** A day trade is flat before the close - simulated as closed at the last real print. */
    @Test fun entryTriggeredNeitherHitByACloseSessionClosesAtTheLastPrintAboveEntry() {
        val bars = listOf(
            bar(0L, 10.1, 9.9, 10.0),
            bar(1L, 10.9, 10.0, 10.8)   // last print, above entry(10) - never reached target(12)
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.CLOSED_PROFIT, outcome)
        assertEquals(10.8, exit!!, 1e-9)
    }

    @Test fun entryTriggeredNeitherHitByACloseSessionClosesAtTheLastPrintAtOrBelowEntry() {
        val bars = listOf(
            bar(0L, 10.1, 9.9, 10.0),
            bar(1L, 10.05, 9.9, 9.95)   // last print, at/below entry(10)
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = bars, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.CLOSED_LOSS, outcome)
        assertEquals(9.95, exit!!, 1e-9)
    }

    @Test fun exactBoundaryTouchesCountAsHits() {
        // high exactly equal to entry - proven by a WIN, since NO_ENTRY would also result if
        // the boundary were wrongly excluded and this bar simply never triggered.
        val entryExact = listOf(
            bar(0L, high = 10.0, low = 9.9, close = 10.0),  // entry(10) exactly on the high
            bar(1L, high = 12.0, low = 10.0, close = 11.9)  // target(12) reached next bar
        )
        val (e, exitA) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = entryExact, sessionStillOpen = false
        )
        assertEquals("high==entry must count as triggered, not almost-triggered",
            DayTradingOutcome.WIN, e)
        assertEquals(12.0, exitA!!, 1e-9)

        val targetExact = listOf(
            bar(0L, 10.1, 9.9, 10.0),
            bar(1L, 12.0, 10.0, 11.9)
        )
        val (outcome, exit) = DayTradingEval.evaluate(
            ResearchScore.SETUP_BREAKOUT, entry = 10.0, stop = 8.5, target = 12.0,
            recordedAt = 0L, bars = targetExact, sessionStillOpen = false
        )
        assertEquals(DayTradingOutcome.WIN, outcome)
        assertEquals(12.0, exit!!, 1e-9)
    }

    // ------------------------------------------------------------------ stats()

    private fun entry(
        outcome: String?,
        entry: Double = 10.0,
        stop: Double = 8.0,
        target: Double = 14.0,
        exitPrice: Double? = null
    ) = DayTradingLogEntry(
        id = 0L, symbol = "TST", tradingDay = "20260101", recordedAt = 0L, setup = "Breakout",
        entry = entry, stop = stop, target = target, priceAtRecommendation = 9.0,
        source = DayTradingLogEntry.SOURCE_APP, outcome = outcome, outcomeExitPrice = exitPrice,
        outcomeEvaluatedAt = if (outcome != null) 1L else null
    )

    @Test fun statsOnAnEmptyLogIsAllZero() {
        val s = DayTradingEval.stats(emptyList())
        assertEquals(0, s.totalRecommendations)
        assertEquals(0, s.entriesTriggered)
        assertEquals(0.0, s.targetHitRate, 1e-9)
        assertEquals(0.0, s.avgReturnPct, 1e-9)
    }

    @Test fun neverEvaluatedAndPendingRowsAreExcludedFromTheDenominator() {
        val s = DayTradingEval.stats(
            listOf(entry(outcome = null), entry(outcome = DayTradingOutcome.PENDING),
                entry(outcome = DayTradingOutcome.DATA_UNAVAILABLE))
        )
        assertEquals(3, s.totalRecommendations)
        assertEquals(0, s.entriesTriggered)
        assertEquals(2, s.pending) // null (never evaluated) and PENDING both read as "pending"
        assertEquals(1, s.dataUnavailable)
        assertEquals(0.0, s.targetHitRate, 1e-9)
    }

    @Test fun noEntryRowsAreExcludedFromTheDenominatorToo() {
        val s = DayTradingEval.stats(listOf(entry(outcome = DayTradingOutcome.NO_ENTRY)))
        assertEquals(1, s.totalRecommendations)
        assertEquals(0, s.entriesTriggered)
        assertEquals(1, s.noEntry)
    }

    @Test fun winAndLossRatesAndTheAverageReturnAreComputedCorrectly() {
        val entries = listOf(
            // entry 10, target 14 -> +40%
            entry(outcome = DayTradingOutcome.WIN, entry = 10.0, target = 14.0, exitPrice = 14.0),
            // entry 10, stop 8 -> -20%
            entry(outcome = DayTradingOutcome.LOSS, entry = 10.0, stop = 8.0, exitPrice = 8.0),
            // entry 10, closed at 11 -> +10%
            entry(outcome = DayTradingOutcome.CLOSED_PROFIT, entry = 10.0, exitPrice = 11.0),
            // entry 10, closed at 9.5 -> -5%
            entry(outcome = DayTradingOutcome.CLOSED_LOSS, entry = 10.0, exitPrice = 9.5)
        )
        val s = DayTradingEval.stats(entries)
        assertEquals(4, s.entriesTriggered)
        assertEquals(1, s.targetHit)
        assertEquals(1, s.stopHit)
        assertEquals(1, s.closedProfit)
        assertEquals(1, s.closedLoss)
        assertEquals(25.0, s.targetHitRate, 1e-9)       // 1 of 4
        assertEquals(50.0, s.profitableRate, 1e-9)      // WIN + CLOSED_PROFIT = 2 of 4
        // (40 - 20 + 10 - 5) / 4 = 6.25
        assertEquals(6.25, s.avgReturnPct, 1e-9)
    }

    @Test fun aMissingExitPriceFallsBackToTheOwnLevel() {
        // outcomeExitPrice null - e.g. a row written by an older code path - falls back to the
        // recommendation's own target/stop/entry rather than crashing or reading as zero.
        val entries = listOf(
            entry(outcome = DayTradingOutcome.WIN, entry = 10.0, target = 15.0, exitPrice = null),
            entry(outcome = DayTradingOutcome.LOSS, entry = 10.0, stop = 7.0, exitPrice = null)
        )
        val s = DayTradingEval.stats(entries)
        // (50 + -30) / 2 = 10
        assertEquals(10.0, s.avgReturnPct, 1e-9)
    }
}
