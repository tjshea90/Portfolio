package com.tj.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FeedItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A CACHED STORY KEEPS ITS BLURB (Round 66).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * `loadNews` wrote its stories through to disk with `summaries = emptyMap()`, and it is the
 * only place a summary could ever be supplied - so the `summary` column added for exactly
 * this was written as '' for every row ever stored. The detail screen only draws a blurb when
 * it is non-blank, so: open a stock and see headlines with their one-line summaries; leave and
 * come back, and the list repaints from cache with every blurb gone until the network fetch
 * lands - and stories that have since fallen out of the source's window never get one back.
 *
 * The second half is `cacheNews`'s conflict path, which only ever updated `owned`. Even after
 * summaries started being passed, a row already on file with a blank one could never gain it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewsCacheSummaryTest {

    private lateinit var ctx: Context
    private lateinit var db: Db

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
        db = Db(ctx)
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(Db.DB_NAME) }

    private fun story(id: String = "s1") = FeedItem(
        kind = FeedItem.NEWS, symbol = "NVDA",
        title = "Nvidia guides above consensus",
        url = "https://example.com/$id", source = "Reuters",
        published = 1_756_000_000_000L, owned = true
    )

    private fun cachedSummary(): String =
        db.cachedNewsFor("NVDA").firstOrNull()?.summary ?: "MISSING"

    @Test fun `a summary written with the story survives the round trip`() {
        val s = story()
        db.cacheNews(listOf(s), summaries = mapOf(s.id to "Data-centre revenue up 62%."))
        assertEquals("Data-centre revenue up 62%.", cachedSummary())
    }

    @Test fun `a row already on file with no blurb gains one on the next pass`() {
        val s = story()
        db.cacheNews(listOf(s))                       // the pre-fix shape: no summaries at all
        assertEquals("", cachedSummary())
        db.cacheNews(listOf(s), summaries = mapOf(s.id to "Now with a blurb."))
        assertEquals("the conflict path never updated the summary", "Now with a blurb.", cachedSummary())
    }

    @Test fun `a later pass with no blurb does not erase one`() {
        // The multi-source cascade means the same story can arrive from a source that carries
        // no summary. That must not delete the one an earlier source supplied.
        val s = story()
        db.cacheNews(listOf(s), summaries = mapOf(s.id to "Keep me."))
        db.cacheNews(listOf(s))
        assertEquals("Keep me.", cachedSummary())
        db.cacheNews(listOf(s), summaries = mapOf(s.id to ""))
        assertEquals("an explicitly empty summary also must not erase it", "Keep me.", cachedSummary())
    }
}
