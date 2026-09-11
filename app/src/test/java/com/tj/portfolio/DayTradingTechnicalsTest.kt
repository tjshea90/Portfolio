package com.tj.portfolio

import com.tj.portfolio.net.DayTradingTechnicals
import com.tj.portfolio.net.DayTradingTechnicals.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * THE REAL TECHNICALS BEHIND THE DAY-TRADING RISK PLAN (Round 68).
 *
 * See [DayTradingTechnicals]'s own header for the research these formulas come from - Wilder's
 * ATR, session VWAP, and the 09:30-10:00 ET opening range. Every case here is checked against a
 * value worked out by hand, not just "did it run" - a sign error or an off-by-one in a
 * volatility formula produces a confident, plausible, WRONG stop-loss, which is exactly the
 * class of bug this app's own scoring-file header warns about.
 *
 * ROBOLECTRIC, LIKE EVERY OTHER TEST IN THIS PROJECT THAT PARSES JSON. Plain `org.json` on
 * the JVM unit-test classpath is the Android SDK's stub jar, not a working implementation -
 * only Robolectric's shadow makes [DayTradingTechnicals.parseBars] usable here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayTradingTechnicalsTest {

    private val ET: TimeZone = TimeZone.getTimeZone("America/New_York")

    /** Epoch seconds for a given ET wall-clock time on a fixed, arbitrary weekday. */
    private fun etEpoch(hour: Int, minute: Int, dayOffset: Int = 0): Long {
        val c = Calendar.getInstance(ET)
        c.set(2026, Calendar.SEPTEMBER, 9 + dayOffset, hour, minute, 0) // a Wednesday
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000L
    }

    private fun bar(t: Long, h: Double, l: Double, c: Double, v: Double = 0.0, o: Double = c) =
        Bar(t, o, h, l, c, v)

    // ================================================================== ATR

    @Test fun `too little history refuses rather than guessing`() {
        // Two bars is one true-range value - nowhere near enough for a volatility estimate.
        val bars = listOf(
            bar(etEpoch(0, 0, 0), h = 100.0, l = 95.0, c = 98.0),
            bar(etEpoch(0, 0, 1), h = 102.0, l = 97.0, c = 100.0)
        )
        assertNull(DayTradingTechnicals.atr14(bars))
    }

    @Test fun `a short history under 14 days averages the true ranges it has`() {
        // Hand-computed true ranges: max(high-low, |high-prevClose|, |low-prevClose|).
        val bars = listOf(
            bar(etEpoch(0, 0, 0), h = 100.0, l = 95.0, c = 98.0),
            bar(etEpoch(0, 0, 1), h = 102.0, l = 97.0, c = 100.0),  // TR = max(5, 4, 1) = 5
            bar(etEpoch(0, 0, 2), h = 105.0, l = 99.0, c = 103.0),  // TR = max(6, 5, 1) = 6
            bar(etEpoch(0, 0, 3), h = 104.0, l = 100.0, c = 101.0), // TR = max(4, 1, 3) = 4
            bar(etEpoch(0, 0, 4), h = 103.0, l = 98.0, c = 99.0),   // TR = max(5, 2, 3) = 5
            bar(etEpoch(0, 0, 5), h = 101.0, l = 96.0, c = 97.0)    // TR = max(5, 2, 3) = 5
        )
        // (5 + 6 + 4 + 5 + 5) / 5 = 5.0
        assertEquals(5.0, DayTradingTechnicals.atr14(bars)!!, 0.001)
    }

    @Test fun `fewer than five true ranges is still too thin to trust`() {
        val bars = (0..3).map { bar(etEpoch(0, 0, it), h = 100.0 + it, l = 95.0 + it, c = 98.0 + it) }
        assertNull(DayTradingTechnicals.atr14(bars))
    }

    @Test fun `at 14 or more true ranges Wilder smoothing takes over and holds steady on constant volatility`() {
        // Every bar has the same true range (4.0: high-low=4, no gap from the prior close),
        // so both the bootstrap average and every Wilder-smoothed step must stay exactly 4.0 -
        // (4*13 + 4) / 14 = 4.0. 20 bars -> 19 true-range values, past the 14-value threshold.
        val bars = (0..19).map { bar(etEpoch(0, 0, it), h = 104.0, l = 100.0, c = 102.0) }
        assertEquals(4.0, DayTradingTechnicals.atr14(bars)!!, 0.0001)
    }

    @Test fun `a real volatility spike moves the smoothed average, not just the raw one`() {
        // 15 quiet bars produce 14 true-range values (TR = 2.0 each), bootstrapping the
        // average at 2.0; one violent 16th bar (TR = 30.0) then smooths in by Wilder's
        // formula - not the raw jump to 30, and not left at 2.0 either:
        // (2.0*13 + 30.0) / 14 = 4.0.
        val quiet = (0..14).map { bar(etEpoch(0, 0, it), h = 101.0, l = 99.0, c = 100.0) }
        val spike = bar(etEpoch(0, 0, 15), h = 130.0, l = 100.0, c = 115.0)
        val atr = DayTradingTechnicals.atr14(quiet + spike)!!
        assertTrue("a real spike should raise the smoothed ATR: got $atr", atr > 2.0)
        assertEquals(4.0, atr, 0.001)
    }

    @Test fun `out-of-order bars are sorted before the true range is computed`() {
        // Same three bars as the short-history test's first three, shuffled - the true-range
        // math reads consecutive PAIRS, so an unsorted list would compute nonsense.
        val inOrder = listOf(
            bar(etEpoch(0, 0, 0), h = 100.0, l = 95.0, c = 98.0),
            bar(etEpoch(0, 0, 1), h = 102.0, l = 97.0, c = 100.0),
            bar(etEpoch(0, 0, 2), h = 105.0, l = 99.0, c = 103.0),
            bar(etEpoch(0, 0, 3), h = 104.0, l = 100.0, c = 101.0),
            bar(etEpoch(0, 0, 4), h = 103.0, l = 98.0, c = 99.0),
            bar(etEpoch(0, 0, 5), h = 101.0, l = 96.0, c = 97.0)
        )
        val shuffled = listOf(inOrder[3], inOrder[0], inOrder[5], inOrder[1], inOrder[4], inOrder[2])
        assertEquals(
            DayTradingTechnicals.atr14(inOrder)!!,
            DayTradingTechnicals.atr14(shuffled)!!,
            0.0001
        )
    }

    // ================================================================== VWAP

    @Test fun `vwap weights the typical price by volume, not a plain average`() {
        val bars = listOf(
            bar(etEpoch(9, 30), h = 10.0, l = 8.0, c = 9.0, v = 100.0),   // typical 9,  pv = 900
            bar(etEpoch(9, 35), h = 12.0, l = 10.0, c = 11.0, v = 300.0)  // typical 11, pv = 3300
        )
        // (900 + 3300) / (100 + 300) = 10.5 - NOT the plain average of 9 and 11 (10.0), which
        // is exactly the bug a volume-BLIND average would introduce.
        assertEquals(10.5, DayTradingTechnicals.vwap(bars)!!, 0.001)
    }

    @Test fun `an empty session has no vwap to report`() {
        assertNull(DayTradingTechnicals.vwap(emptyList()))
    }

    @Test fun `zero volume across every bar refuses rather than dividing by zero`() {
        val bars = listOf(bar(etEpoch(9, 30), h = 10.0, l = 8.0, c = 9.0, v = 0.0))
        assertNull(DayTradingTechnicals.vwap(bars))
    }

    // ============================================================ opening range

    @Test fun `the opening range is the high and low of 9-30 to 10-00 ET only`() {
        val bars = listOf(
            bar(etEpoch(9, 25), h = 999.0, l = 1.0, c = 50.0),   // before the window - excluded
            bar(etEpoch(9, 30), h = 105.0, l = 100.0, c = 102.0),
            bar(etEpoch(9, 45), h = 108.0, l = 98.0, c = 104.0), // the window's real high/low
            bar(etEpoch(9, 55), h = 106.0, l = 101.0, c = 103.0),
            bar(etEpoch(10, 5), h = 500.0, l = 0.5, c = 50.0)    // after the window - excluded
        )
        val (high, low) = DayTradingTechnicals.openingRange(bars)!!
        assertEquals(108.0, high, 0.001)
        assertEquals(98.0, low, 0.001)
    }

    @Test fun `no bars in the window means no opening range yet`() {
        val bars = listOf(bar(etEpoch(9, 0), h = 100.0, l = 90.0, c = 95.0))
        assertNull(DayTradingTechnicals.openingRange(bars))
    }

    @Test fun `the opening range is not complete until a bar at or after 10-00 ET exists`() {
        val midRange = listOf(bar(etEpoch(9, 45), h = 105.0, l = 100.0, c = 102.0))
        assertFalse(DayTradingTechnicals.openingRangeComplete(midRange))
        val afterRange = midRange + bar(etEpoch(10, 0), h = 106.0, l = 101.0, c = 103.0)
        assertTrue(DayTradingTechnicals.openingRangeComplete(afterRange))
    }

    // ================================================================== parseBars

    private val sampleChartJson = """
    {
      "chart": {
        "result": [{
          "meta": {"currency": "USD"},
          "timestamp": [${etEpoch(9, 30)}, ${etEpoch(9, 35)}, ${etEpoch(9, 40)}],
          "indicators": {
            "quote": [{
              "open":   [100.0, 101.5, null],
              "high":   [102.0, 103.0, 104.0],
              "low":    [99.0, 100.5, 101.0],
              "close":  [101.5, 102.5, null],
              "volume": [10000, 12000, 5000]
            }]
          }
        }]
      }
    }
    """.trimIndent()

    @Test fun `parseBars reads OHLCV, not just closes`() {
        val bars = DayTradingTechnicals.parseBars(sampleChartJson)!!
        assertEquals(2, bars.size) // the third candle has a null close and is dropped
        assertEquals(102.0, bars[0].high, 0.001)
        assertEquals(99.0, bars[0].low, 0.001)
        assertEquals(101.5, bars[0].close, 0.001)
        assertEquals(10000.0, bars[0].volume, 0.001)
        assertEquals(100.0, bars[0].open, 0.001)
    }

    @Test fun `a candle with no trades is dropped, never read as a zero price`() {
        val json = """
        {"chart":{"result":[{
          "timestamp": [${etEpoch(9, 30)}],
          "indicators": {"quote": [{"high": [null], "low": [null], "close": [null]}]}
        }]}}
        """.trimIndent()
        val bars = DayTradingTechnicals.parseBars(json)
        assertTrue(bars.isNullOrEmpty())
    }

    @Test fun `an empty result array parses to null rather than throwing`() {
        assertNull(DayTradingTechnicals.parseBars("""{"chart":{"result":[]}}"""))
    }

    // ============================================ session splitting and levels (Round 69)
    //
    // The intraday fetch now asks for pre- and post-market bars too, because the premarket
    // high is a real trigger level. Everything that means "the session" has to be split back
    // out of that list, or VWAP silently starts averaging 4am prints and a "session high"
    // becomes a price no regular-hours order could have been filled at.

    @Test fun `the regular session is 09-30 up to 16-00, and nothing else`() {
        val bars = listOf(
            bar(etEpoch(7, 0), h = 120.0, l = 118.0, c = 119.0),   // premarket
            bar(etEpoch(9, 25), h = 121.0, l = 119.0, c = 120.0),  // still premarket
            bar(etEpoch(9, 30), h = 105.0, l = 100.0, c = 104.0),  // the open
            bar(etEpoch(15, 55), h = 106.0, l = 103.0, c = 105.0), // the close
            bar(etEpoch(16, 5), h = 130.0, l = 128.0, c = 129.0)   // after hours
        )
        val regular = DayTradingTechnicals.regularSession(bars)
        assertEquals(2, regular.size)
        assertEquals(
            "an after-hours print must never become the session high",
            106.0, regular.maxOf { it.high }, 0.001
        )
    }

    @Test fun `the premarket high is the 04-00 to 09-30 window only`() {
        val bars = listOf(
            bar(etEpoch(3, 30), h = 200.0, l = 199.0, c = 199.5),  // before 4am - excluded
            bar(etEpoch(7, 0), h = 120.0, l = 118.0, c = 119.0),
            bar(etEpoch(9, 25), h = 122.0, l = 119.0, c = 121.0),  // the premarket high
            bar(etEpoch(10, 0), h = 130.0, l = 125.0, c = 129.0)   // regular hours - excluded
        )
        assertEquals(122.0, DayTradingTechnicals.premarketHigh(bars), 0.001)
    }

    @Test fun `no premarket bars is zero, not a crash`() {
        assertEquals(0.0, DayTradingTechnicals.premarketHigh(emptyList()), 0.0)
    }

    // ---- the in-progress session, which must never count as a completed one

    /** Milliseconds for an ET wall-clock time on the same fixed weekday scheme. */
    private fun etMillis(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        etEpoch(hour, minute, dayOffset) * 1000L

    @Test fun `today's partial bar is excluded while the market is still open`() {
        val daily = listOf(
            bar(etEpoch(16, 0, -2), h = 101.0, l = 99.0, c = 100.0),
            bar(etEpoch(16, 0, -1), h = 103.0, l = 101.0, c = 102.0),
            bar(etEpoch(11, 0, 0), h = 104.0, l = 103.5, c = 103.8)  // today, mid-session
        )
        val completed = DayTradingTechnicals.completedSessions(daily, etMillis(11, 30, 0))
        assertEquals(2, completed.size)
        assertEquals(
            "\"yesterday's high\" must not mean \"the high it made an hour ago\"",
            103.0, completed.last().high, 0.001
        )
    }

    @Test fun `after the close, today IS the prior session`() {
        val daily = listOf(
            bar(etEpoch(16, 0, -1), h = 103.0, l = 101.0, c = 102.0),
            bar(etEpoch(11, 0, 0), h = 104.0, l = 103.5, c = 103.8)
        )
        val completed = DayTradingTechnicals.completedSessions(daily, etMillis(18, 0, 0))
        assertEquals(2, completed.size)
        assertEquals(104.0, completed.last().high, 0.001)
    }

    @Test fun `ADR averages the completed daily ranges and refuses too short a history`() {
        val bars = (0 until 6).map { i ->
            bar(etEpoch(16, 0, -6 + i), h = 102.0 + i, l = 100.0 + i, c = 101.0 + i)
        }
        // Every range is exactly 2.0.
        assertEquals(2.0, DayTradingTechnicals.adr(bars)!!, 0.001)
        assertNull("four sessions is not a typical day", DayTradingTechnicals.adr(bars.take(4)))
    }

    @Test fun `ADR looks back only the requested number of sessions`() {
        // Nine quiet 2.0-range days, then five wide 10.0-range ones.
        val quiet = (0 until 9).map { i -> bar(etEpoch(16, 0, -20 + i), h = 102.0, l = 100.0, c = 101.0) }
        val wide = (0 until 5).map { i -> bar(etEpoch(16, 0, -5 + i), h = 110.0, l = 100.0, c = 105.0) }
        val adr = DayTradingTechnicals.adr(quiet + wide)!!
        assertEquals((9 * 2.0 + 5 * 10.0) / 14.0, adr, 0.001)
        // A shorter window weights the recent, wider days far more heavily.
        assertEquals(10.0, DayTradingTechnicals.adr(quiet + wide, lookback = 5)!!, 0.001)
    }
}
