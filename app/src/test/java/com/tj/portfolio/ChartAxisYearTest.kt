package com.tj.portfolio

import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.FeedItem
import com.tj.portfolio.net.News
import com.tj.portfolio.ui.axisLabel
import com.tj.portfolio.ui.spansMoreThanAYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The axis has to say WHERE the window is, and a feed list has to be de-duplicated by the
 * expression it is keyed by.
 *
 * BOTH COME FROM ONE REPORT. TJ panned a 5Y FIVE-vs-SPY chart and the stock's performance
 * against SPY changed a lot as he dragged, which looked wrong. It was not: dragging moves
 * the window by months, and both lines rebase to the new left edge, so every figure on
 * screen legitimately changes. What was wrong is that the axis read "Sep 9" and "Sep 10" on
 * a three-year window - the labels of two consecutive days - so nothing on screen said the
 * window had moved that far.
 *
 * The second half is the crash from the same report, guarded the way the news list already
 * guards itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartAxisYearTest {

    // 2023-09-09 and 2026-09-10, the two ends TJ's chart actually showed.
    private val sep2023 = 1_694_260_800_000L
    private val sep2026 = 1_789_000_000_000L

    @Test
    fun `a window inside one calendar year needs no year on its labels`() {
        val feb = 1_772_000_000_000L          // 2026-02-25
        val jul = 1_785_000_000_000L          // 2026-07-25
        assertFalse(spansMoreThanAYear(feb, jul))
        val label = axisLabel(jul, ChartRange.M6, withDate = true, withYear = false)
        // "contains(\"20\")" was wrong here - a day-of-month can be 20-something.
        assertFalse("a same-year label should not name a year: $label", label.contains("2026"))
    }

    @Test
    fun `a window crossing calendar years puts the year on both ends`() {
        assertTrue(spansMoreThanAYear(sep2023, sep2026))
        val start = axisLabel(sep2023, ChartRange.Y5, withDate = true, withYear = true)
        val end = axisLabel(sep2026, ChartRange.Y5, withDate = true, withYear = true)
        assertTrue("start label must name its year: $start", start.contains("2023"))
        assertTrue("end label must name its year: $end", end.contains("2026"))
        // The exact fault TJ hit: without the year these two are indistinguishable as
        // anything but consecutive days.
        assertNotEquals(start, end)
    }

    @Test
    fun `the year is off by default so existing callers are unchanged`() {
        assertEquals(
            axisLabel(sep2026, ChartRange.Y5, withDate = true),
            axisLabel(sep2026, ChartRange.Y5, withDate = true, withYear = false)
        )
    }

    @Test
    fun `an intraday label is unaffected by the year flag`() {
        val a = axisLabel(sep2026, ChartRange.D1, withDate = false, withYear = true)
        val b = axisLabel(sep2026, ChartRange.D1, withDate = false, withYear = false)
        assertEquals(a, b)
    }

    // ---- the crash guard -----------------------------------------------------------

    /**
     * Two stories that survive the merge's de-duplication but collide on the LazyColumn key.
     *
     * `News.dedupeKey` compares NORMALISED titles - punctuation stripped, then 70 characters.
     * `FeedItem.id` uses the first 80 RAW characters. A punctuation-heavy headline carries far
     * fewer than 70 alphanumerics in its first 80 raw characters, so the two questions can
     * disagree: different normalised titles (both kept) with an identical id (Compose throws).
     */
    @Test
    fun `feed rows that share an id are collapsed even when the merge keeps both`() {
        // MUST be at least 80 characters: `FeedItem.id` truncates the title there, so a
        // shorter shared prefix would leave the two ids different and the fixture would
        // prove nothing. This one is 88.
        val prefix = "Q2 2026: U.S. Retailer's E.P.S. Beats; F.D.A., S.E.C. & F.T.C. All Weigh In On The Result"
        val a = FeedItem(
            kind = FeedItem.NEWS, symbol = "FIVE", title = "$prefix on margins",
            url = "https://a.example/1", source = "Nasdaq", published = 1_756_000_000_000L
        )
        val b = FeedItem(
            kind = FeedItem.NEWS, symbol = "FIVE", title = "$prefix on guidance",
            url = "https://b.example/2", source = "Yahoo", published = 1_756_000_000_000L
        )

        // Same key, so a LazyColumn keyed by `id` would throw on these two.
        assertTrue("the fixture prefix must exceed the 80-char id truncation", prefix.length >= 80)
        assertEquals("the fixture must actually collide on id", a.id, b.id)

        // Dedupe by id - what the Feed now does - collapses them.
        assertEquals(1, listOf(a, b).distinctBy { it.id }.size)
    }

    @Test
    fun `dedupe by id keeps genuinely different stories`() {
        val a = FeedItem(
            kind = FeedItem.NEWS, symbol = "FIVE", title = "Five Below beats on earnings",
            url = "https://a.example/1", source = "Nasdaq", published = 1_756_000_000_000L
        )
        val b = FeedItem(
            kind = FeedItem.NEWS, symbol = "FIVE", title = "Five Below opens 30 new stores",
            url = "https://b.example/2", source = "Yahoo", published = 1_756_000_000_100L
        )
        assertNotEquals(a.id, b.id)
        assertEquals(2, listOf(a, b).distinctBy { it.id }.size)
    }

    /** The two identity questions are genuinely different; nothing here assumes otherwise. */
    @Test
    fun `dedupeKey and id answer different questions`() {
        val wire = "Five Below reports Q2 results"
        val fromNasdaq = com.tj.portfolio.data.NewsItem(
            symbol = "FIVE", title = "$wire - Nasdaq", url = "https://n.example",
            source = "Nasdaq", published = 1_756_000_000_000L
        )
        val fromYahoo = com.tj.portfolio.data.NewsItem(
            symbol = "FIVE", title = "$wire | Yahoo Finance", url = "https://y.example",
            source = "Yahoo", published = 1_756_000_009_000L
        )
        // Same story to the merge (publisher suffix stripped)...
        assertEquals(News.dedupeKey(fromNasdaq), News.dedupeKey(fromYahoo))
    }
}
