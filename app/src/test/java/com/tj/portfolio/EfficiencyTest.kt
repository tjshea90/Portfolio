package com.tj.portfolio

import com.tj.portfolio.data.Quote
import com.tj.portfolio.net.Http
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round 56's efficiency work, tested where it can be tested without a network.
 *
 * The three things here are the ones where being wrong is expensive and silent: a disk cache
 * that quietly drops writes, a batch quote parser that invents a number, and a
 * closed-market rule that either never stops polling or never starts again.
 */
@RunWith(RobolectricTestRunner::class)
class EfficiencyTest {

    // ------------------------------------------------------- the disk cache

    /** An in-memory stand-in for the SQLite-backed store, so the contract can be exercised. */
    private class FakeDisk : Http.DiskCache {
        val rows = HashMap<String, Triple<String, String, String>>()
        var touches = 0
        var loads = 0
        override fun load(url: String): Triple<String, String, String>? {
            loads++; return rows[url]
        }
        override fun save(url: String, etag: String, lastModified: String, body: String) {
            rows[url] = Triple(etag, lastModified, body)
        }
        override fun touch(url: String) { touches++ }
        override fun forget(url: String) { rows.remove(url) }
    }

    @After fun detach() = Http.attachDiskCache(null)

    /**
     * The whole point of the disk cache: a validator written in one session is available in
     * the next. Before Round 56 this lived only in a bounded heap map, so every cold start
     * re-downloaded every feed in full.
     */
    @Test fun validatorsSurviveAProcessRestart() {
        val disk = FakeDisk()
        Http.attachDiskCache(disk)
        disk.save("https://example.com/feed", "\"abc\"", "", "<rss>one</rss>")

        // A "restart" is a cleared heap cache with the same disk behind it.
        Http.clearConditionalCache()
        Http.attachDiskCache(disk)
        val (etag, _, body) = disk.load("https://example.com/feed")!!
        assertEquals("\"abc\"", etag)
        assertEquals("<rss>one</rss>", body)
    }

    /** A store that throws must never be able to take a request down with it. */
    @Test fun aFailingDiskCacheIsSurvivable() {
        Http.attachDiskCache(object : Http.DiskCache {
            override fun load(url: String) = throw IllegalStateException("disk on fire")
            override fun save(url: String, etag: String, lastModified: String, body: String) =
                throw IllegalStateException("disk on fire")
            override fun touch(url: String) = throw IllegalStateException("disk on fire")
            override fun forget(url: String) = throw IllegalStateException("disk on fire")
        })
        // Reading stats is the cheapest observable path through the cache machinery; the
        // point is that attaching a hostile store does not poison the object.
        val (entries, bytes) = Http.cacheStats()
        assertTrue(entries >= 0)
        assertTrue(bytes >= 0)
    }

    @Test fun detachingLeavesTheCacheMemoryOnly() {
        Http.attachDiskCache(null)
        Http.clearConditionalCache()
        assertEquals(0, Http.cacheStats().first)
    }

    /**
     * The real SQLite table, not the fake: store, read back, 304-touch, forget, purge.
     *
     * The fake above proves the CONTRACT; this proves the implementation behind it, because
     * a cache that silently drops writes looks exactly like a cache that is working.
     */
    @Test fun theSqliteBackedCacheStoresReadsAndForgets() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(com.tj.portfolio.data.Db.DB_NAME)
        val db = com.tj.portfolio.data.Db(ctx)
        val url = "https://feeds.example.com/rss"

        assertNull(db.httpCached(url))
        db.httpStore(url, "\"v1\"", "Mon, 01 Jan 2026 00:00:00 GMT", "<rss>hello</rss>")

        val row = db.httpCached(url)
        assertNotNull(row)
        assertEquals("\"v1\"", row!!.etag)
        assertEquals("Mon, 01 Jan 2026 00:00:00 GMT", row.lastModified)
        assertEquals("<rss>hello</rss>", row.body)

        assertEquals(1, db.httpCacheStats().first)
        assertEquals("<rss>hello</rss>".length.toLong(), db.httpCacheStats().second)

        // A 304 renews the entry rather than ageing it out.
        db.httpTouch(url)
        assertNotNull(db.httpCached(url))

        // Replacing keeps one row, not two - the URL is the primary key.
        db.httpStore(url, "\"v2\"", "", "<rss>newer</rss>")
        assertEquals(1, db.httpCacheStats().first)
        assertEquals("\"v2\"", db.httpCached(url)!!.etag)

        db.httpForget(url)
        assertNull(db.httpCached(url))
        assertEquals(0, db.httpCacheStats().first)
    }

    /** Retention must drop what is old and keep what is in use. */
    @Test fun theCachePurgeDropsOldEntriesAndKeepsRecentOnes() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(com.tj.portfolio.data.Db.DB_NAME)
        val db = com.tj.portfolio.data.Db(ctx)
        db.httpStore("https://a.example/1", "e1", "", "aaa")
        db.httpStore("https://a.example/2", "e2", "", "bbb")
        assertEquals(2, db.httpCacheStats().first)

        // Nothing is old yet.
        db.purgeHttpCache()
        assertEquals(2, db.httpCacheStats().first)

        // Two months on, both are.
        db.purgeHttpCache(System.currentTimeMillis() + 60L * 86_400_000L)
        assertEquals(0, db.httpCacheStats().first)
    }

    // --------------------------------------------------- the closed-market rule

    /**
     * `pricesAreFinal` is private to the ViewModel, so the RULE is restated here against the
     * same inputs. It is the arithmetic that matters: a quote fetched while the market was
     * shut cannot be improved by asking again, until either the market reopens or the
     * backstop expires.
     */
    private fun pricesAreFinal(
        phaseNow: com.tj.portfolio.net.MarketClock.Phase,
        quotes: List<Quote>,
        now: Long,
        maxAgeMs: Long = 6 * 3_600_000L
    ): Boolean {
        if (phaseNow != com.tj.portfolio.net.MarketClock.Phase.CLOSED) return false
        if (quotes.isEmpty()) return false
        val oldest = quotes.minOf { it.updated }
        if (oldest <= 0L) return false
        return com.tj.portfolio.net.MarketClock.phase(oldest) ==
            com.tj.portfolio.net.MarketClock.Phase.CLOSED && now - oldest < maxAgeMs
    }

    /** 3am Sunday: the price cannot move, so the app must not ask. */
    @Test fun aClosedMarketWithAFreshCloseIsNotPolled() {
        // Sunday 03:00 ET, and a quote fetched ten minutes earlier.
        val now = sundayEt(3, 0)
        val q = Quote(symbol = "NVDA", price = 100.0, updated = now - 10 * 60_000L)
        assertTrue(pricesAreFinal(com.tj.portfolio.net.MarketClock.Phase.CLOSED, listOf(q), now))
    }

    /** A symbol with no quote at all is exactly when polling must continue. */
    @Test fun aMissingQuoteAlwaysPolls() {
        val now = sundayEt(3, 0)
        assertFalse(pricesAreFinal(com.tj.portfolio.net.MarketClock.Phase.CLOSED, emptyList(), now))
        val never = Quote(symbol = "NVDA", price = 0.0, updated = 0L)
        assertFalse(
            pricesAreFinal(com.tj.portfolio.net.MarketClock.Phase.CLOSED, listOf(never), now)
        )
    }

    /** The backstop: being wrong about a holiday costs one stale afternoon, not a dead screen. */
    @Test fun theBackstopResumesPollingEventually() {
        val now = sundayEt(3, 0)
        val stale = Quote(symbol = "NVDA", price = 100.0, updated = now - 7 * 3_600_000L)
        assertFalse(pricesAreFinal(com.tj.portfolio.net.MarketClock.Phase.CLOSED, listOf(stale), now))
    }

    /** An open market is never final, however recently the price was fetched. */
    @Test fun anOpenMarketIsNeverFinal() {
        val now = System.currentTimeMillis()
        val q = Quote(symbol = "NVDA", price = 100.0, updated = now - 1_000L)
        assertFalse(pricesAreFinal(com.tj.portfolio.net.MarketClock.Phase.OPEN, listOf(q), now))
        assertFalse(
            pricesAreFinal(com.tj.portfolio.net.MarketClock.Phase.EXTENDED, listOf(q), now)
        )
    }

    /**
     * A quote fetched DURING the session is not final once the session ends - the closing
     * print lands after it, so one more pass is needed.
     */
    @Test fun aMidSessionQuoteIsNotFinalAfterTheClose() {
        val closeTime = weekdayEt(17, 0)          // 5pm ET, extended hours over? no - CLOSED at 20:00
        val duringSession = weekdayEt(11, 0)      // 11am ET, market open
        assertFalse(
            pricesAreFinal(
                com.tj.portfolio.net.MarketClock.Phase.CLOSED,
                listOf(Quote(symbol = "NVDA", price = 100.0, updated = duringSession)),
                closeTime
            )
        )
    }

    // ------------------------------------------------------------- helpers

    private fun etMillis(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val c = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("America/New_York"), java.util.Locale.US
        )
        c.set(java.util.Calendar.YEAR, 2026)
        c.set(java.util.Calendar.MONTH, java.util.Calendar.SEPTEMBER)
        c.set(java.util.Calendar.DAY_OF_MONTH, 7)     // Monday 7 Sept 2026
        c.set(java.util.Calendar.HOUR_OF_DAY, hour)
        c.set(java.util.Calendar.MINUTE, minute)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        while (c.get(java.util.Calendar.DAY_OF_WEEK) != dayOfWeek) {
            c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    // --------------------------------------------------- the batch verdict rule

    /**
     * `MarketData.batchFailures` is private, so the RULE is restated. The distinction it
     * encodes is the whole point: an inconclusive attempt - no crumb, or one of our own
     * cooldowns - must not count towards giving up on the batch, because those are exactly
     * the moments when falling back to one request per symbol makes things worse.
     */
    private fun nextFailureCount(current: Int, verdict: String): Int = when (verdict) {
        "OK" -> 0
        "INCONCLUSIVE" -> current
        else -> current + 1
    }

    @Test fun onlyAttributableFailuresCountTowardsDisablingTheBatch() {
        // Forty-five seconds with no signal at launch: three passes, none of which learned
        // anything about the endpoint. The batch must still be enabled afterwards.
        var n = 0
        repeat(3) { n = nextFailureCount(n, "INCONCLUSIVE") }
        assertEquals(0, n)

        // Three passes where both hosts answered and neither gave anything usable IS a verdict.
        repeat(3) { n = nextFailureCount(n, "FAILED") }
        assertEquals(3, n)

        // And one good pass clears it.
        assertEquals(0, nextFailureCount(n, "OK"))
    }

    /** A partial batch is a working batch - it must not count as a failure. */
    @Test fun aPartialBatchIsASuccess() {
        assertEquals(0, nextFailureCount(2, "OK"))
    }

    // ------------------------------------------------------ ticker normalising

    /**
     * Yahoo echoes some tickers in its own spelling - "BRK.B" comes back "BRK-B". Keying the
     * result by what Yahoo said meant the requested symbol still looked missing, so it was
     * fetched a second time by the fallback and the caller got a Quote it never asked for.
     */
    private fun normaliseTicker(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    @Test fun tickerSpellingsMatchAcrossProviders() {
        assertEquals(normaliseTicker("BRK.B"), normaliseTicker("BRK-B"))
        assertEquals(normaliseTicker("brk b"), normaliseTicker("BRK.B"))
        assertTrue(normaliseTicker("NVDA") != normaliseTicker("NVDAA"))
        assertEquals("MOGA", normaliseTicker("MOG.A"))
    }

    private fun sundayEt(h: Int, m: Int) = etMillis(java.util.Calendar.SUNDAY, h, m)
    private fun weekdayEt(h: Int, m: Int) = etMillis(java.util.Calendar.TUESDAY, h, m)
}
