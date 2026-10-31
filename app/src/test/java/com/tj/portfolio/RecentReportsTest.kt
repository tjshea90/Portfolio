package com.tj.portfolio

import com.tj.portfolio.net.EarningsFeed
import com.tj.portfolio.net.OfflinePages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentReportsTest {
    private val now = 1_761_000_000_000L

    private fun body(reportSecs: Long) = """
    {"chart":{"result":[{"timestamp":[${reportSecs - 172800},${reportSecs - 86400},${reportSecs + 86400},${reportSecs + 172800}],
     "indicators":{"quote":[{"close":[100.0,100.0,110.0,111.0]}]},
     "events":{"earnings":{"$reportSecs":{"date":$reportSecs,"epsActual":1.50,"epsEstimate":1.25,"quarter":"3Q2026"}}}}]}}
    """.trimIndent()

    @Test fun parsesBeatWithReaction() {
        val r = EarningsFeed.parseReports(body(1_760_000_000L), now)!!.single()
        assertEquals(20.0, r.surprisePct, 0.01)
        assertEquals(10.0, r.reactionPct!!, 0.01)
        assertEquals(EarningsFeed.Verdict.GOOD, EarningsFeed.verdict(r))
        assertEquals("Beat", EarningsFeed.beatWord(r))
    }

    @Test fun missIsPoorAndInLineIsNeutral() {
        val miss = EarningsFeed.Report(1L, "", 0.8, 1.0, -20.0, -4.0)
        assertEquals(EarningsFeed.Verdict.POOR, EarningsFeed.verdict(miss))
        val flat = EarningsFeed.Report(1L, "", 1.0, 1.0, 0.0, 0.5)
        assertEquals(EarningsFeed.Verdict.NEUTRAL, EarningsFeed.verdict(flat))
        assertEquals("In line", EarningsFeed.beatWord(flat))
    }

    @Test fun noEventsIsEmptyNotNull() {
        assertEquals(0, EarningsFeed.parseReports("""{"chart":{"result":[{"timestamp":[1],"indicators":{"quote":[{"close":[1]}]}}]}}""", now)!!.size)
        assertNull(EarningsFeed.parseReports("{}", now))
    }

    @Test fun roundTripsForOffline() {
        val l = EarningsFeed.parseReports(body(1_760_000_000L), now)!!
        assertEquals(l, EarningsFeed.decodeReports(EarningsFeed.encodeReports(l)))
    }

    @Test fun cardNeverNull() {
        val c = OfflinePages.card("Headline", "Src", "", 0L)
        assertTrue(c.contains("Headline"))
    }

    @Test fun structuredBodyFromJsonLd() {
        val text = "Shares rose after the company reported results. ".repeat(12)
        val html = """<html><script type="application/ld+json">{"@type":"NewsArticle","articleBody":"$text"}</script></html>"""
        assertNotNull(OfflinePages.sanitize(html))
    }
}
