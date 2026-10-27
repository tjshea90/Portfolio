package com.tj.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FeedItem
import com.tj.portfolio.net.Http
import com.tj.portfolio.net.HttpResult
import com.tj.portfolio.net.OfflineBudget
import com.tj.portfolio.net.OfflineBudget.Bucket
import com.tj.portfolio.net.OfflinePages
import com.tj.portfolio.ui.OfflineSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MB = 1024L * 1024L

/** The priority order Tj asked for, as a pure function (no database, no Android). */
class OfflineBudgetTest {

    @Test fun underBudgetEvictsNothing() {
        assertTrue(OfflineBudget.plan(10 * MB, mapOf(Bucket.FEED to 5 * MB, Bucket.NEWS to 5 * MB)).isEmpty())
    }

    @Test fun lowestPriorityGoesFirst() {
        // 60 MB held, 50 allowed: 10 MB must go, and all of it from the cheapest bucket.
        val plan = OfflineBudget.plan(
            10 * MB,
            mapOf(Bucket.HTTP to 4 * MB, Bucket.FEED to 20 * MB, Bucket.RESEARCH to 6 * MB, Bucket.NEWS to 20 * MB)
        )
        assertEquals(mapOf(Bucket.HTTP to 4 * MB, Bucket.FEED to 6 * MB), plan)
    }

    @Test fun eachTierIsEmptiedBeforeTheNextIsTouched() {
        val sizes = mapOf(
            Bucket.FEED to 5 * MB, Bucket.RESEARCH to 5 * MB, Bucket.WATCH to 5 * MB,
            Bucket.ANALYSIS to 5 * MB, Bucket.NEWS to 30 * MB
        )
        // 20 MB over: feed, research, watch, analysis are consumed in that order, news untouched.
        val plan = OfflineBudget.plan(20 * MB, sizes)
        assertEquals(listOf(Bucket.FEED, Bucket.RESEARCH, Bucket.WATCH, Bucket.ANALYSIS), plan.keys.toList())
        assertFalse(Bucket.NEWS in plan)
    }

    @Test fun newsOfHoldingsOutranksAnalysisOutranksWatchOutranksResearchOutranksFeed() {
        val order = Bucket.entries.map { it.tier }
        assertEquals(order.sortedDescending(), order)   // evict order = descending tier number
        assertTrue(Bucket.NEWS.tier < Bucket.ANALYSIS.tier)
        assertTrue(Bucket.ANALYSIS.tier < Bucket.WATCH.tier)
        assertTrue(Bucket.WATCH.tier < Bucket.RESEARCH.tier)
        assertTrue(Bucket.RESEARCH.tier < Bucket.FEED.tier)
    }

    @Test fun pinnedDataIsNeverTheVictim() {
        // Pinned alone is over budget: nothing evictable is left, and nothing pinned is named.
        assertTrue(OfflineBudget.plan(60 * MB, emptyMap()).isEmpty())
    }

    @Test fun roomForExcludesHigherPriorityBuckets() {
        val sizes = mapOf(Bucket.NEWS to 30 * MB, Bucket.FEED to 2 * MB)
        // Feed (tier 7) gets what is left after pinned + every higher bucket + its own bytes.
        assertEquals(50 * MB - 10 * MB - 30 * MB - 2 * MB, OfflineBudget.roomFor(7, 10 * MB, sizes))
        assertEquals(0L, OfflineBudget.roomFor(7, 40 * MB, sizes))
    }
}

class OfflinePagesTest {

    private val article = "<html><head><title>NVDA jumps &amp; rallies</title><script>var x=1;</script>" +
        "<style>.a{}</style></head><body><nav>Home Markets</nav><article>" +
        "<h1 class=\"h\" onclick=\"x()\">NVDA jumps</h1>" +
        (1..30).joinToString("") { "<p class=\"c\">Paragraph $it about Nvidia earnings and data centre demand, with enough words to count.</p>" } +
        "<img src=\"big.jpg\"><a href=\"https://example.com/more\" target=\"_blank\">more</a>" +
        "</article><footer>copyright</footer><script>track()</script></body></html>"

    @Test fun keepsTextDropsScriptsNavAndAttributes() {
        val out = OfflinePages.sanitize(article)!!
        assertTrue(out.contains("Paragraph 12 about Nvidia"))
        assertFalse(out.contains("script")); assertFalse(out.contains("track()"))
        assertFalse(out.contains("Home Markets")); assertFalse(out.contains("copyright"))
        assertFalse(out.contains("onclick")); assertFalse(out.contains("class="))
        assertFalse(out.contains("<img"))
        assertTrue(out.contains("<a href=\"https://example.com/more\">"))
        assertTrue(out.length < article.length)
    }

    @Test fun thinPagesAreRejected() {
        assertNull(OfflinePages.sanitize("<html><body><p>Please enable JavaScript to continue.</p></body></html>"))
    }

    @Test fun titleIsUnescaped() = assertEquals("NVDA jumps & rallies", OfflinePages.titleOf(article))

    @Test fun packRoundTripsAndShrinks() {
        val html = OfflinePages.sanitize(article)!!
        val packed = OfflinePages.pack(html)
        assertEquals(html, OfflinePages.unpack(packed))
        assertTrue(packed.size < html.length / 2)
    }

    @Test fun wrapEscapesTitleAndNamesTheSource() {
        val doc = OfflinePages.wrap("A <b>& B", "https://x.test/a?b=1&c=2", 0L, "<p>hi</p>")
        assertTrue(doc.contains("A &lt;b&gt;&amp; B"))
        assertTrue(doc.contains("https://x.test/a?b=1&amp;c=2"))
        assertTrue(doc.contains("Saved copy"))
    }

    @Test fun googleRedirectStubsAndMediaAreNotFetched() {
        assertFalse(OfflinePages.worthFetching("https://news.google.com/rss/articles/CBMi123"))
        assertFalse(OfflinePages.worthFetching("https://x.test/report.pdf"))
        assertFalse(OfflinePages.worthFetching("ftp://x.test/a"))
        assertTrue(OfflinePages.worthFetching("https://finance.yahoo.com/news/nvda-jumps-123.html"))
    }

    @Test fun analysisClassifier() {
        assertTrue(OfflineSync.isAnalysis("Analyst · Google News", "anything"))
        assertTrue(OfflineSync.isAnalysis("Reuters", "Morgan Stanley upgrades Nvidia, raises price target"))
        assertFalse(OfflineSync.isAnalysis("Reuters", "Nvidia opens new office in Taipei"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineSyncTest {

    private lateinit var ctx: Context
    private lateinit var db: Db

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
        db = Db(ctx)
    }

    @After fun tearDown() {
        Http.scriptedForTests = null
        db.close(); ctx.deleteDatabase(Db.DB_NAME)
    }

    private fun page(n: Int) = "<html><head><title>Story $n</title></head><body><article>" +
        (1..25).joinToString("") { "<p>Story $n sentence $it that discusses the company at some useful length.</p>" } +
        "</article></body></html>"

    private fun html(n: Int) = HttpResult(200, page(n))

    private class FakeHost(
        val db: Db,
        override val lowRam: Boolean = false,
        var online: Boolean = true,
        val held: List<OfflineSync.Sym> = listOf(OfflineSync.Sym("NVDA", "Nvidia"), OfflineSync.Sym("AAPL", "Apple")),
        val watch: List<OfflineSync.Sym> = listOf(OfflineSync.Sym("TSLA", "Tesla")),
        var pricesOk: Boolean = true
    ) : OfflineSync.Host {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun online() = online
        override fun holdings() = held
        override fun watchlist() = watch
        override suspend fun quotes(): Boolean { calls += "quotes"; return pricesOk }
        override suspend fun chart(symbol: String, rangeKey: String) { calls += "chart:$symbol:$rangeKey" }
        override suspend fun news(symbol: String, name: String, deep: Boolean) {
            calls += "news:$symbol:${if (deep) "deep" else "cheap"}"
            val n = symbol.hashCode().and(0xff)
            db.cacheNews((1..3).map {
                FeedItem(FeedItem.NEWS, symbol, "$symbol headline $it", url = "https://news.test/$symbol/$it",
                    source = "Wire", published = 1_000L + it)
            })
        }
        override suspend fun analysisPieces(symbol: String, name: String) { calls += "analysis:$symbol" }
        override suspend fun fundamentals(symbol: String) { calls += "fund:$symbol" }
        override suspend fun dividends(held: List<String>) { calls += "dividends" }
        override suspend fun earnings() { calls += "earnings" }
        override suspend fun research() { calls += "research" }
        override fun researchPicks(limit: Int) = listOf(OfflineSync.Sym("AMD", "AMD"))
        override suspend fun feed(includeInsider: Boolean) { calls += "feed:$includeInsider" }
    }

    private fun runSync(host: FeedHostLike, mode: OfflineSync.Mode): OfflineSync {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sync = OfflineSync(db, host.h, { scope })
        assertTrue(sync.start(mode))
        runBlocking { withTimeout(20_000) { sync.progress.first { !it.running && it.lastFinishedAt > 0 } } }
        return sync
    }

    private class FeedHostLike(val h: FakeHost)

    @Test fun fullPassRunsTiersInPriorityOrderAndSavesPages() {
        Http.scriptedForTests = { url -> if (url.startsWith("https://news.test/")) html(url.hashCode().and(0xff)) else null }
        val host = FakeHost(db)
        runSync(FeedHostLike(host), OfflineSync.Mode.FULL)
        val calls = host.calls.toList()
        fun first(prefix: String) = calls.indexOfFirst { it.startsWith(prefix) }
        // prices+charts < dividends/earnings < holdings news < analysis < watch < research < feed
        assertTrue(first("quotes") < first("chart:NVDA"))
        assertTrue(first("chart:AAPL:MAX") >= 0)
        assertTrue(calls.indexOf("dividends") > calls.indexOfLast { it.startsWith("chart:NVDA") })
        assertTrue(first("news:NVDA:deep") > calls.indexOf("earnings"))
        assertTrue(first("analysis:NVDA") > calls.indexOfLast { it.startsWith("news:AAPL:deep") })
        assertTrue(first("news:TSLA") > calls.indexOfLast { it.startsWith("analysis:") })
        assertTrue(calls.indexOf("research") > calls.indexOfLast { it.startsWith("news:TSLA") })
        assertTrue(calls.indexOf("feed:true") > calls.indexOf("research"))
        assertTrue(db.offlinePageCount() > 0)
        assertNotNull(db.offlinePage("https://news.test/NVDA/1"))
    }

    @Test fun lightPassIsMinimal() {
        val host = FakeHost(db)
        runSync(FeedHostLike(host), OfflineSync.Mode.LIGHT)
        val calls = host.calls.toList()
        assertTrue("quotes" in calls)
        assertEquals(listOf("D1", "M1", "Y1"), calls.filter { it.startsWith("chart:NVDA") }.map { it.substringAfterLast(':') })
        assertTrue("feed:false" in calls)
        assertTrue(calls.none { it == "dividends" || it == "earnings" || it == "research" || it.startsWith("analysis") || it.startsWith("fund:") })
        assertEquals(0, db.offlinePageCount())
    }

    @Test fun declinesWithoutANetwork() {
        val host = FakeHost(db, online = false)
        val sync = OfflineSync(db, host, { CoroutineScope(Dispatchers.Unconfined) })
        assertFalse(sync.start(OfflineSync.Mode.FULL))
        assertTrue(host.calls.isEmpty())
        assertTrue(sync.progress.value.lastSummary.contains("No connection"))
    }

    @Test fun stalePagesAreRemovedOnlyForTiersTheRunCovered() {
        // A page from an earlier run, in tier 3 (covered) and tier 7 (feed, covered), plus one
        // in a tier the run never visits is impossible in FULL - so check the purge itself.
        db.putOfflinePage("https://old.test/a", "NVDA", 3, "old", "<p>x</p>", run = 1L)
        db.putOfflinePage("https://old.test/b", "NVDA", 6, "old", "<p>x</p>", run = 1L)
        assertEquals(1, db.purgeUnseenOfflinePages(listOf(3), run = 2L))
        assertTrue(db.hasOfflinePage("https://old.test/b"))
        assertFalse(db.hasOfflinePage("https://old.test/a"))
    }

    @Test fun anInterruptedOrPricelessPassPurgesNothing() {
        Http.scriptedForTests = { url -> if (url.startsWith("https://news.test/")) html(7) else null }
        db.putOfflinePage("https://old.test/keep", "NVDA", 3, "old", "<p>x</p>", run = 1L)
        val host = FakeHost(db, pricesOk = false)
        runSync(FeedHostLike(host), OfflineSync.Mode.FULL)
        assertTrue(db.hasOfflinePage("https://old.test/keep"))
    }

    @Test fun evictionFollowsPriorityAgainstARealDatabase() {
        val big = "x".repeat(2000) + (1..800).joinToString(" ") { "w$it" }
        for (t in 3..7) for (i in 0 until 4) db.putOfflinePage("https://t$t.test/$i", "S", t, "t", big + t + i, run = 1L)
        val before = db.offlinePageBytes()
        val total = before.values.sum()
        // Squeeze the budget so ~ the cheapest two buckets must go.
        val budget = total - before.getValue(Bucket.FEED) - before.getValue(Bucket.RESEARCH) + db.pinnedCacheBytes() + db.httpCacheStats().second
        db.enforceOfflineBudget(budget)
        val after = db.offlinePageBytes()
        assertTrue((after[Bucket.FEED] ?: 0L) == 0L)
        assertTrue((after[Bucket.RESEARCH] ?: 0L) == 0L)
        assertEquals(before[Bucket.NEWS], after[Bucket.NEWS])
        assertEquals(before[Bucket.ANALYSIS], after[Bucket.ANALYSIS])
        assertEquals(before[Bucket.WATCH], after[Bucket.WATCH])
    }

    @Test fun savedPagesSurviveReopeningTheDatabase() {
        db.putOfflinePage("https://p.test/1", "NVDA", 3, "Persist", OfflinePages.sanitize(page(1))!!, run = 1L)
        db.close()
        val reopened = Db(ctx)
        try {
            val doc = reopened.offlinePage("https://p.test/1")
            assertNotNull(doc)
            assertTrue(doc!!.contains("Story 1 sentence 3"))
        } finally { reopened.close() }
        db = Db(ctx)
    }
}
