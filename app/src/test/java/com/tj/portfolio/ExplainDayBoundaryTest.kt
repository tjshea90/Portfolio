package com.tj.portfolio

import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.ui.Explain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAIN `/` ON A LONG TRUNCATES TOWARD ZERO, NOT FLOOR.
 *
 * `Explain` dated its three forward-looking topics - the ex-dividend cut-off, the dividend
 * payment date and the next earnings report - with `(then - now) / 86_400_000L`. For a date
 * between 1 and 23 hours in the PAST that divides a small negative numerator and lands on
 * `0`, so every "is it still ahead of us?" test written as `d >= 0` stayed true for the whole
 * day AFTER the date had passed. The reader was told a cut-off they had already missed was
 * "essentially now" (act on that and you buy the shares and do NOT get the dividend), that a
 * dividend paid yesterday was still "due", and that a report released last night was still
 * coming with a price move attached.
 *
 * `Research.daysUntilEarnings` hit the identical trap and fixed it with `Math.floorDiv`;
 * these are the same fix, pinned here so the boundary cannot drift back.
 */
class ExplainDayBoundaryTest {

    private val hour = 3_600_000L
    private val now: Long get() = System.currentTimeMillis()

    private fun divRead(key: String, at: Long): String =
        Explain.of(key, Fundamentals("TEST", values = mapOf(key to at.toDouble()))).read

    private fun earningsRead(at: Long): String =
        Explain.analystTopic(
            Explain.TOPIC_EARNINGS_DATE,
            Fundamentals("TEST", earningsDate = at)
        ).read

    // ------------------------------------------------------------ ex-dividend

    @Test fun `ex-dividend cut-off 17 hours ago is in the past, not 'essentially now'`() {
        val s = divRead("exDividendDate", now - 17 * hour)
        assertFalse("a missed cut-off must not read as still actionable: $s",
            s.contains("essentially now"))
        assertTrue("expected the past-tense branch: $s", s.contains("The last cut-off was"))
    }

    @Test fun `ex-dividend one day ago says 'day', not '1 days'`() {
        val s = divRead("exDividendDate", now - 17 * hour)
        assertTrue("singular expected: $s", s.contains("1 day ago"))
        assertFalse("plural is wrong at one: $s", s.contains("1 days ago"))
    }

    @Test fun `ex-dividend cut-off later today is still 'essentially now'`() {
        val s = divRead("exDividendDate", now + 5 * hour)
        assertTrue("a cut-off still ahead must stay actionable: $s",
            s.contains("essentially now"))
    }

    // NOTE the extra 6 hours, here and in the earnings case below. An EXACT multiple of a
    // day sits precisely on the floor boundary: `now` is read here and again inside Explain
    // a few millis later, so the gap is a hair UNDER nine days by the time it is divided and
    // floors to 8. That is the function behaving correctly - it is the test that would be
    // racy - so the fixture is placed mid-day instead of on the edge.
    @Test fun `ex-dividend cut-off well ahead counts the days`() {
        val s = divRead("exDividendDate", now + 9 * 24 * hour + 6 * hour)
        assertTrue("expected a forward count: $s", s.contains("in 9 days"))
    }

    // -------------------------------------------------------- dividend payday

    @Test fun `dividend paid 17 hours ago is not still 'due in 0 days'`() {
        val s = divRead("dividendDate", now - 17 * hour)
        assertFalse("a paid dividend must not read as pending: $s", s.contains("in 0 days"))
        assertFalse(s.contains("is due"))
        assertTrue("expected the past-tense branch: $s", s.contains("The last payment was"))
    }

    @Test fun `dividend landing today says today rather than 'in 0 days'`() {
        val s = divRead("dividendDate", now + 5 * hour)
        assertFalse("'in 0 days' is not how a person says today: $s", s.contains("in 0 days"))
        assertTrue("expected today's wording: $s", s.contains("today"))
    }

    @Test fun `dividend landing tomorrow says tomorrow`() {
        val s = divRead("dividendDate", now + 30 * hour)
        assertTrue("expected tomorrow's wording: $s", s.contains("tomorrow"))
    }

    // -------------------------------------------------------------- earnings

    @Test fun `earnings released last night is reported as past, not still expected`() {
        val s = earningsRead(now - 17 * hour)
        assertFalse("a released report must not promise a coming move: $s",
            s.contains("The next report is expected"))
        assertTrue("expected the past-tense branch: $s", s.contains("The most recent report was"))
    }

    @Test fun `earnings due later today says today`() {
        val s = earningsRead(now + 5 * hour)
        assertFalse("'in 0 days' is not how a person says today: $s", s.contains("in 0 days"))
        assertTrue("expected today's wording: $s", s.contains("today"))
    }

    @Test fun `earnings several days out still counts the days`() {
        val s = earningsRead(now + 4 * 24 * hour + 6 * hour)
        assertTrue("expected a forward count: $s", s.contains("in 4 days"))
    }
}
