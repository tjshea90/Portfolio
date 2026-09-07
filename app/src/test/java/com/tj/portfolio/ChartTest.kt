package com.tj.portfolio

import com.tj.portfolio.data.ChartJson
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.net.ChartFeed
import com.tj.portfolio.ui.axisLabel
import com.tj.portfolio.ui.spansMoreThanADay
import com.tj.portfolio.ui.withLiveEdge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ROUND 58: the price chart, its ranges, and the after-hours filter.
 *
 * The fixture is a synthetic five-day, five-minute Yahoo chart body carrying the two shapes
 * that break naive parsers and that this app has been bitten by before:
 *
 *   - `close` contains a NULL for a candle with no trades. Read as 0.0 it drags the y-axis
 *     to the floor and paints a cliff that never happened - the same class of bug as the
 *     fabricated previous close fixed in v2.3.
 *   - `meta.tradingPeriods` is the NESTED array shape, one entry per day. Reading only
 *     `currentTradingPeriod` describes today and nothing else, which would silently let the
 *     after-hours filter keep the whole week's regular sessions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().use { it.readText() }

    private val body get() = fixture("yahoo_chart_5d_5m.json")

    private fun parse(range: ChartRange) = ChartFeed.parse("TEST", range, body)

    // ------------------------------------------------------------------ parsing

    @Test
    fun `a five day intraday body parses into a series`() {
        val s = parse(ChartRange.D5)
        assertNotNull(s)
        s!!
        assertTrue("expected a real series, got ${s.points.size}", s.points.size > 100)
        assertEquals("TEST", s.symbol)
        assertEquals(ChartRange.D5, s.range)
        assertEquals("USD", s.currency)
    }

    /** The whole point of the null check. A zero here would be a price that never traded. */
    @Test
    fun `a null candle is skipped, never read as zero`() {
        val s = parse(ChartRange.D5)!!
        assertTrue("a 0.0 close got through", s.points.none { it.close <= 0.0 })
        // And it is genuinely absent rather than carried forward as a duplicate timestamp.
        assertEquals(s.points.size, s.points.map { it.t }.distinct().size)
    }

    /**
     * `meta.previousClose` is the RAW prior regular close; `chartPreviousClose` is adjusted
     * for dividends and splits, so on an ex-dividend day it is lower and every day-change
     * figure derived from it comes out wrong. The fixture carries both, deliberately
     * different, so preferring the wrong one is a visible failure.
     */
    @Test
    fun `the 1D baseline is the raw previous close, not the adjusted one`() {
        val s = parse(ChartRange.D1)!!
        assertEquals(99.5, s.baseline, 1e-9)
    }

    /** A month or a decade is not measured from yesterday's close. */
    @Test
    fun `long ranges have no baseline and measure from their own first point`() {
        val s = parse(ChartRange.Y1)!!
        assertEquals(0.0, s.baseline, 1e-9)
        assertEquals(s.first, s.from, 1e-9)
    }

    // ------------------------------------------------------- the overnight filter

    @Test
    fun `the after-hours range keeps only points outside a regular session`() {
        val meta = JSONObject(body).getJSONObject("chart").getJSONArray("result")
            .getJSONObject(0).getJSONObject("meta")
        val regular = ChartFeed.regularWindows(meta)
        assertEquals("expected one regular window per day", 5, regular.size)

        val s = parse(ChartRange.OVERNIGHT)!!
        assertTrue(
            "a regular-session point survived the after-hours filter",
            s.points.none { p -> regular.any { p.t >= it.first && p.t < it.second } }
        )
    }

    /**
     * ONE session, not five days of them. The request has to span several days - before
     * 09:30 the only extended points that exist are this morning's - but what belongs on
     * screen is the current stretch, from the last regular close to now.
     */
    @Test
    fun `the after-hours range shows a single session, not every night in the window`() {
        val s = parse(ChartRange.OVERNIGHT)!!
        val spanHours = (s.endMs - s.startMs) / 3_600_000.0
        assertTrue(
            "after-hours span was $spanHours hours; expected one session's worth",
            spanHours in 0.5..20.0
        )
    }

    /** An overnight move is measured from the close it followed, not from Friday's. */
    @Test
    fun `the after-hours baseline is the regular close the stretch follows`() {
        val meta = JSONObject(body).getJSONObject("chart").getJSONArray("result")
            .getJSONObject(0).getJSONObject("meta")
        val regular = ChartFeed.regularWindows(meta)
        val s = parse(ChartRange.OVERNIGHT)!!
        val lastCloseEnd = regular.map { it.second }.filter { it <= s.points.last().t }.max()
        assertTrue("no baseline was found", s.baseline > 0.0)
        // The baseline must come from BEFORE the drawn stretch and after the previous one.
        assertTrue(s.points.first().t >= lastCloseEnd)
    }

    // --------------------------------------------------------------------- codec

    @Test
    fun `a series survives a round trip through the on-disk codec`() {
        val s = parse(ChartRange.M1)!!
        val back = ChartJson.decode(ChartJson.encode(s))
        assertNotNull(back)
        back!!
        assertEquals(s.symbol, back.symbol)
        assertEquals(s.range, back.range)
        assertEquals(s.points.size, back.points.size)
        assertEquals(s.baseline, back.baseline, 1e-9)
        assertEquals(s.fetched, back.fetched)
        assertEquals(s.points.first().t, back.points.first().t)
        assertEquals(s.points.last().close, back.points.last().close, 1e-9)
    }

    /** A corrupt row must read as a cache MISS, never as an exception or an empty answer. */
    @Test
    fun `unreadable json decodes to null rather than throwing`() {
        assertNull(ChartJson.decode("not json at all"))
        assertNull(ChartJson.decode(""))
        assertNull(ChartJson.decode("{\"v\":1}"))
    }

    @Test
    fun `a zero close in a stored series is dropped on the way back in`() {
        val json = """{"v":1,"symbol":"X","range":"D1","t":[1,2,3],"c":[10.0,0.0,12.0],
            "baseline":9.0,"currency":"USD","fetched":5,"truncated":false}"""
        val s = ChartJson.decode(json)!!
        assertEquals(2, s.points.size)
        assertTrue(s.points.none { it.close <= 0.0 })
    }

    // ------------------------------------------------------------------ staleness

    @Test
    fun `staleness follows the range's own ttl`() {
        val now = 1_000_000_000L
        fun at(r: ChartRange, age: Long) =
            ChartSeries("X", r, listOf(ChartPoint(1, 1.0), ChartPoint(2, 2.0)),
                fetched = now - age).stale(now)

        // Six minutes old: the intraday chart wants refreshing, the five-year one does not.
        assertTrue(at(ChartRange.D1, 6 * 60_000L))
        assertFalse(at(ChartRange.Y5, 6 * 60_000L))
        // A never-fetched series is always stale, whatever the range.
        assertTrue(ChartSeries("X", ChartRange.MAX).stale(now))
    }

    // ------------------------------------------------------------------ live edge

    private fun series(range: ChartRange, vararg closes: Double) =
        ChartSeries("X", range, closes.mapIndexed { i, c -> ChartPoint(i.toLong(), c) })

    /**
     * REPLACE, NEVER APPEND. Appending would grow the series by a point on every quote tick,
     * turning a 78-point line into a thousand-point one over an afternoon.
     */
    @Test
    fun `the live edge replaces the last point and does not lengthen the series`() {
        val s = series(ChartRange.D1, 10.0, 11.0, 12.0)
        val out = withLiveEdge(s, 12.5, true)!!
        assertEquals(3, out.points.size)
        assertEquals(12.5, out.points.last().close, 1e-9)
        assertEquals(11.0, out.points[1].close, 1e-9)
        // The timestamp of the point it replaced is kept, so the x-axis does not move.
        assertEquals(s.points.last().t, out.points.last().t)
    }

    @Test
    fun `the live edge is not applied to a non-intraday range`() {
        val s = series(ChartRange.Y1, 10.0, 11.0, 12.0)
        assertEquals(12.0, withLiveEdge(s, 99.0, true)!!.points.last().close, 1e-9)
    }

    @Test
    fun `the live edge is not applied when the session is not live`() {
        val s = series(ChartRange.D1, 10.0, 11.0, 12.0)
        assertEquals(12.0, withLiveEdge(s, 99.0, false)!!.points.last().close, 1e-9)
        // ...nor on a price the provider did not give us.
        assertEquals(12.0, withLiveEdge(s, 0.0, true)!!.points.last().close, 1e-9)
    }

    @Test
    fun `the live edge leaves an absent or one-point series alone`() {
        assertNull(withLiveEdge(null, 10.0, true))
        val one = series(ChartRange.D1, 10.0)
        assertEquals(1, withLiveEdge(one, 99.0, true)!!.points.size)
    }

    // ---------------------------------------------------------------- axis labels

    /**
     * The case that matters: an intraday window crossing midnight. Labelling its two ends
     * "4:00 PM" and "9:35 AM" says nothing about WHICH days those are, and on the
     * after-hours chart the two ends are different days by definition.
     */
    @Test
    fun `an intraday window that crosses midnight is labelled with the date too`() {
        val day = 86_400_000L
        val sameDay = ChartSeries(
            "X", ChartRange.D1,
            listOf(ChartPoint(1_757_000_000L, 1.0), ChartPoint(1_757_020_000L, 2.0))
        )
        val across = ChartSeries(
            "X", ChartRange.OVERNIGHT,
            listOf(
                ChartPoint(1_757_000_000L, 1.0),
                ChartPoint(1_757_000_000L + day / 1000, 2.0)
            )
        )
        assertFalse(spansMoreThanADay(sameDay))
        assertTrue(spansMoreThanADay(across))

        val short = axisLabel(across.startMs, ChartRange.OVERNIGHT, false)
        val long = axisLabel(across.startMs, ChartRange.OVERNIGHT, true)
        assertTrue("the dated label should be longer", long.length > short.length)
        // A daily range never gets a clock, whatever the flag says.
        assertFalse(axisLabel(across.startMs, ChartRange.Y1, true).contains(":"))
        assertEquals("", axisLabel(0L, ChartRange.D1, true))
    }

    // ------------------------------------------------------------------- ranges

    /**
     * Every range must ask Yahoo a question Yahoo answers, and must state its own window in
     * words - the caption is the literal answer to "I cannot see how long the chart is
     * tracking", so an empty one is a bug rather than a cosmetic omission.
     */
    @Test
    fun `every range is completely specified`() {
        ChartRange.entries.forEach { r ->
            assertTrue("${r.name} has no label", r.label.isNotBlank())
            assertTrue("${r.name} has no caption", r.caption.isNotBlank())
            assertTrue("${r.name} has no yahoo range", r.yRange.isNotBlank())
            assertTrue("${r.name} has no interval", r.interval.isNotBlank())
            assertTrue("${r.name} has a non-positive ttl", r.ttlMs > 0L)
        }
        // The eight TJ asked for, and no silent omission.
        assertEquals(8, ChartRange.entries.size)
        assertEquals(
            listOf("1D", "5D", "1M", "6M", "1Y", "5Y", "All", "After hrs"),
            ChartRange.entries.map { it.label }
        )
    }

    @Test
    fun `an unknown stored range name falls back to the default rather than throwing`() {
        assertEquals(ChartRange.DEFAULT, ChartRange.byName("NOT_A_RANGE"))
        assertEquals(ChartRange.DEFAULT, ChartRange.byName(null))
        assertEquals(ChartRange.Y5, ChartRange.byName("y5"))
    }
}
