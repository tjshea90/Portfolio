package com.tj.portfolio

import com.tj.portfolio.data.FeedItem
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.net.News
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feed row identity: what counts as the same story, and what the list is keyed by.
 *
 * These two have to agree. When they did not, de-duplication kept two rows that Compose
 * considered identical and the LazyColumn threw - the crash that used to land a few seconds
 * after a stock's news appeared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedIdentityTest {

    private fun news(sym: String, title: String, pub: Long = 1_756_000_000_000L) = FeedItem(
        kind = FeedItem.NEWS, symbol = sym, title = title,
        url = "https://example.com/$title", source = "Nasdaq", published = pub, owned = true
    )

    private fun market(sym: String, title: String, pub: Long = 1_756_000_000_000L) = FeedItem(
        kind = FeedItem.MARKET, symbol = sym, title = title,
        url = "https://other.com/$title", source = "Google News", published = pub,
        owned = sym.isNotBlank()
    )

    private fun filing(sym: String, title: String, uid: String) = FeedItem(
        kind = FeedItem.INSIDER, symbol = sym, title = title, source = "SEC Form 4",
        published = 1_756_000_000_000L, uid = uid
    )

    /** The de-duplication publishFeed performs, mirrored so it can be asserted directly. */
    private fun dedupe(items: List<FeedItem>): List<FeedItem> =
        items.filter { it.title.isNotBlank() }
            .distinctBy { storyKeyOf(it) }
            .distinctBy { it.id }

    private fun storyKeyOf(f: FeedItem): String =
        if (f.kind == FeedItem.INSIDER) f.id
        else "STORY|" + News.dedupeKey(
            NewsItem(f.symbol, f.title, f.url, f.source, f.published)
        )

    @Test fun `the same story from a symbol feed and a market feed is shown once`() {
        // Nasdaq carries it on the NVDA feed; Google's business topic carries it too. Both
        // reach the Live feed's All tab, and their ids differ only in `kind`, so an id-based
        // de-duplication keeps both and the reader sees the headline twice.
        val a = news("NVDA", "Nvidia beats on earnings as data centre revenue climbs 40%")
        val b = market("NVDA", "Nvidia beats on earnings as data centre revenue climbs 40% - Reuters")

        assertNotEquals("the ids differ, which is why id-alone was not enough", a.id, b.id)
        assertEquals("the two are not being recognised as one story",
            storyKeyOf(a), storyKeyOf(b))

        val shown = dedupe(listOf(a, b))
        assertEquals("the same headline appeared twice in the feed", 1, shown.size)
        assertEquals("the per-symbol copy should win - it is attributed to a holding",
            FeedItem.NEWS, shown[0].kind)
    }

    @Test fun `two different stories about the same symbol both survive`() {
        val a = news("NVDA", "Nvidia beats on earnings as data centre revenue climbs")
        val b = news("NVDA", "Nvidia slips as China export rules tighten again")
        assertEquals(2, dedupe(listOf(a, b)).size)
    }

    @Test fun `two Form 4 filings on the same day are never merged`() {
        // EDGAR dates a filing to the DAY, and two insiders can file the same-titled entry.
        // The accession number is what keeps them apart; collapsing them would hide a filing.
        val a = filing("NVDA", "Huang Jen-Hsun - insider filing", "0001199039-26-000012")
        val b = filing("NVDA", "Huang Jen-Hsun - insider filing", "0001199039-26-000013")
        assertNotEquals(a.id, b.id)
        assertEquals("a distinct filing was swallowed", 2, dedupe(listOf(a, b)).size)
    }

    @Test fun `a filing is never confused with a headline of the same words`() {
        val f = filing("NVDA", "Nvidia insider sells shares", "0001199039-26-000012")
        val n = news("NVDA", "Nvidia insider sells shares")
        assertEquals(2, dedupe(listOf(f, n)).size)
    }

    @Test fun `every surviving row still has a unique list key`() {
        val items = listOf(
            news("NVDA", "Nvidia beats on earnings"),
            market("NVDA", "Nvidia beats on earnings - Reuters"),
            news("CSCO", "Cisco raises guidance"),
            market("", "Payrolls blow past expectations"),
            market("", "Payrolls blow past expectations"),
            filing("BA", "A Director - insider filing", "0001225208-26-007500"),
            filing("BA", "A Director - insider filing", "0001225208-26-007501")
        )
        val shown = dedupe(items)
        val ids = shown.map { it.id }
        assertEquals("a duplicate key would make the LazyColumn throw", ids.size, ids.toSet().size)
        assertTrue(shown.isNotEmpty())
    }

    @Test fun `a blank title never reaches the list`() {
        assertEquals(0, dedupe(listOf(news("NVDA", ""), news("NVDA", "   "))).size)
    }
}
