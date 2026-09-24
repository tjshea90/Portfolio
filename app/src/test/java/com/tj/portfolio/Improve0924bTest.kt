package com.tj.portfolio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.net.DayTradingBridge
import com.tj.portfolio.net.ResearchBridge
import com.tj.portfolio.net.SharedAnswer
import com.tj.portfolio.util.PromptShare
import com.tj.portfolio.util.ShareInbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for Tj's 2026-09-24b request: the recommended improvements, and the Claude-app share
 * round trip. One section per item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Improve0924bTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun foreignUri(text: String, name: String = "answer.md"): Uri {
        val uri = Uri.parse("content://com.anthropic.claude.files/answers/$name")
        org.robolectric.Shadows.shadowOf(app.contentResolver)
            .registerInputStreamSupplier(uri) { java.io.ByteArrayInputStream(text.toByteArray()) }
        return uri
    }

    // ---- The Claude round trip: the prompt file is the whole message.

    @Test fun `every prompt file tells Claude to start now, before anything else`() {
        val set = ResearchSet(trending = listOf(ResearchRow(symbol = "AAA", score = 50)),
            dayTrading = listOf(ResearchRow(symbol = "BBB", score = 50)))
        val prompts = mapOf(
            "advice" to ClaudeBridge.advicePrompt("{}", "(none)"),
            "screenshots" to ClaudeBridge.screenshotPrompt("first import", emptyList()),
            "research" to ResearchBridge.prompt(set, emptyList(), emptyList()),
            "day trading" to DayTradingBridge.prompt(set, emptyList(), emptyList())
        )
        for ((name, p) in prompts) {
            val start = p.indexOf("this attached file is my whole message")
            assertTrue("$name prompt has no start-now line", start >= 0)
            assertTrue("$name: the line must come before the request itself",
                start < p.indexOf("\n# "))
            // Still recognised as the QUESTION if it is shared back by mistake.
            assertTrue(name, ClaudeBridge.isPromptFile(p))
            assertEquals(SharedAnswer.Kind.PROMPT_FILE, SharedAnswer.classify(p))
        }
        assertTrue(prompts.getValue("screenshots").contains("ask me to attach them"))
        assertFalse(prompts.getValue("advice").contains("ask me to attach them"))
    }

    @Test fun `a shared chat link is told apart from an answer`() {
        assertEquals(SharedAnswer.Kind.LINK, SharedAnswer.classify("https://claude.ai/share/0b1c2d3e"))
        assertEquals(SharedAnswer.Kind.LINK,
            SharedAnswer.classify("Check out this chat with Claude https://claude.ai/chat/abc-123"))
        assertTrue(SharedAnswer.rejection(SharedAnswer.Kind.LINK)!!.contains("answer file"))
        // An answer that mentions a link is still an answer.
        val answer = """Sources: https://example.com/news
```json
{"portfolioAppResponse": 1, "advice": {"summary": "Fine.", "risks": "", "actions": [], "stocks": []}}
```"""
        assertEquals(SharedAnswer.Kind.CLAUDE, SharedAnswer.classify(answer))
    }

    @Test fun `several answer files shared at once are all read, our own files never`() {
        val a = foreignUri("first answer", "a.md")
        val b = foreignUri("second answer", "b.md")
        val own = PromptShare.stage(app, "claude-advice-prompt.md", "our prompt")!!
        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, own, b))
        assertEquals(listOf("first answer", "second answer"),
            ShareInbox.readAll(app, multi, SharedAnswer.MAX_CHARS))
        // A single share is the one-element case.
        val one = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(listOf("first answer"), ShareInbox.readAll(app, one, SharedAnswer.MAX_CHARS))
    }

    // ---- Network: fewer requests for the same answers.

    private fun scripted(answer: (String) -> com.tj.portfolio.net.HttpResult?): MutableList<String> {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        com.tj.portfolio.net.Http.scriptedForTests = { url -> seen.add(url); answer(url) }
        return seen
    }

    @After fun unscript() { com.tj.portfolio.net.Http.scriptedForTests = null }

    @Test fun `analyst consensus is remembered for twelve hours, coverage or none`() = kotlinx.coroutines.runBlocking {
        val R = com.tj.portfolio.net.Research
        val seen = scripted { com.tj.portfolio.net.HttpResult(500, "down") }
        fun row(sym: String) = ResearchRow(symbol = sym, score = 60, reasons = listOf("r"), price = 100.0)
        val c = com.tj.portfolio.data.Consensus2(buy = 10, hold = 2, sell = 0, target = 150.0)
        R.rememberConsensus("MEMOA", c)
        R.rememberConsensus("MEMON", null)                       // Nasdaq said: nobody covers it
        R.rememberConsensus("MEMOS", c, at = System.currentTimeMillis() - R.CONSENSUS_MEMO_MS - 1)
        val out = R.enrichAnalyst(listOf(row("MEMOA"), row("MEMON"), row("MEMOS"))).associateBy { it.symbol }
        assertEquals(c, out.getValue("MEMOA").consensus)
        assertTrue("blended like a fresh answer", out.getValue("MEMOA").score != 60)
        assertEquals(null, out.getValue("MEMON").consensus)
        assertEquals("only the expired memo is asked again",
            listOf("MEMOS"), seen.filter { "nasdaq" in it }.map { it.substringAfter("analyst/").substringBefore('/') })
        // A failed request is not remembered: asked again next time.
        R.enrichAnalyst(listOf(row("MEMOS")))
        assertEquals(2, seen.count { "MEMOS" in it })
    }

    @Test fun `a stock the quote calls EQUITY needs no fund-holdings request`() {
        val q = com.tj.portfolio.data.Quote(symbol = "NVDA", quoteType = "EQUITY")
        assertTrue(com.tj.portfolio.ui.holdingsNotNeeded(q))
        assertFalse(com.tj.portfolio.ui.holdingsNotNeeded(q.copy(quoteType = "ETF")))
        assertFalse(com.tj.portfolio.ui.holdingsNotNeeded(q.copy(quoteType = "")))
        assertFalse(com.tj.portfolio.ui.holdingsNotNeeded(null))
    }

    private fun ny(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = java.time.ZonedDateTime.of(
        y, mo, d, h, mi, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    @Test fun `5D and 1M charts fetched after the close stay final until the next open`() {
        fun fin(range: com.tj.portfolio.data.ChartRange, end: Long, fetched: Long, now: Long) =
            com.tj.portfolio.ui.intradayChartIsFinal(range, end, fetched, now)
        // 2026-09-25 is a Friday. Last 30-minute candle 15:30, last daily candle 09:30.
        val fri1530 = ny(2026, 9, 25, 15, 30); val fri0930 = ny(2026, 9, 25, 9, 30)
        val sat = ny(2026, 9, 26, 11); val sun = ny(2026, 9, 27, 20)
        assertTrue(fin(ChartRange.D5, fri1530, sat, sun))
        assertTrue(fin(ChartRange.M1, fri0930, sat, sun))
        assertFalse("Monday's session has opened", fin(ChartRange.D5, fri1530, sat, ny(2026, 9, 28, 9, 45)))
        assertFalse("fetched during the session", fin(ChartRange.D5, fri1530, ny(2026, 9, 25, 15, 45), sun))
        // Fetched Friday evening, but the newest candle is Thursday's: Yahoo was behind.
        assertFalse(fin(ChartRange.M1, ny(2026, 9, 24, 9, 30), ny(2026, 9, 25, 18), sat))
        // Pre-market: the regular-only 5D cannot change before 09:30.
        assertTrue(fin(ChartRange.D5, fri1530, ny(2026, 9, 28, 8), ny(2026, 9, 28, 9, 0)))
        // Longer ranges keep their own TTLs.
        assertFalse(fin(ChartRange.M6, fri0930, sat, sun))
    }

    // ---- Charts: gaps, volume, market time.

    @Test fun `C-9 an intraday line breaks at a closed market, a daily one never does`() {
        val fri1530 = ny(2026, 9, 25, 15, 30) / 1000; val mon0930 = ny(2026, 9, 28, 9, 30) / 1000
        val d5 = listOf(com.tj.portfolio.data.ChartPoint(fri1530 - 1800, 10.0),
            com.tj.portfolio.data.ChartPoint(fri1530, 10.1), com.tj.portfolio.data.ChartPoint(mon0930, 10.5),
            com.tj.portfolio.data.ChartPoint(mon0930 + 1800, 10.6))
        assertEquals(listOf(false, true, false, false),
            com.tj.portfolio.ui.closedGaps(d5, ChartRange.D5).toList())
        val daily = listOf(com.tj.portfolio.data.ChartPoint(fri1530, 10.0),
            com.tj.portfolio.data.ChartPoint(mon0930, 10.5))
        assertTrue(com.tj.portfolio.ui.closedGaps(daily, ChartRange.M1).none { it })
    }

    @Test fun `volume survives the chart cache, and an older row reads as none`() {
        val s = com.tj.portfolio.data.ChartSeries("AAA", ChartRange.D1,
            listOf(com.tj.portfolio.data.ChartPoint(1_000L, 10.0, 500.0),
                com.tj.portfolio.data.ChartPoint(1_300L, 10.2, 0.0)), fetched = 1L)
        val back = com.tj.portfolio.data.ChartJson.decode(com.tj.portfolio.data.ChartJson.encode(s))!!
        assertEquals(listOf(500.0, 0.0), back.points.map { it.volume })
        val old = org.json.JSONObject(com.tj.portfolio.data.ChartJson.encode(s)).apply { remove("vol") }
        assertEquals(listOf(0.0, 0.0), com.tj.portfolio.data.ChartJson.decode(old.toString())!!.points.map { it.volume })
    }

    @Test fun `Yahoo's volume array is read beside the closes`() {
        val body = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAA",
            "regularMarketPrice":10.3,"chartPreviousClose":9.9},
            "timestamp":[1758800000,1758801800,1758803600],
            "indicators":{"quote":[{"close":[10.0,10.1,10.3],"volume":[1200,null,900]}]}}]}}"""
        val s = com.tj.portfolio.net.ChartFeed.parse("AAA", ChartRange.D5, body)!!
        assertEquals(listOf(1200.0, 0.0, 900.0), s.points.map { it.volume })
    }

    @Test fun `intraday chart times are market time, labelled ET`() {
        val was = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val open = ny(2026, 9, 28, 9, 30)
            assertEquals("9:30 AM", com.tj.portfolio.ui.axisLabel(open, ChartRange.D1, withDate = false))
            assertEquals("9:30 AM ET",
                com.tj.portfolio.ui.axisLabel(open, ChartRange.D1, withDate = false, zoned = true))
            // A 9 PM Pacific print is already the next day in New York - dated by the market.
            assertTrue(com.tj.portfolio.ui.spansMoreThanADay(ny(2026, 9, 28, 16), ny(2026, 9, 29, 0, 30)))
        } finally { java.util.TimeZone.setDefault(was) }
    }

    // ---- L-4: Android 14+ only ever sends 20 and 40.

    private fun settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(120)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test fun `L-4 a background trim frees the invisible caches and keeps what is on screen`() {
        val vm = com.tj.portfolio.ui.PortfolioViewModel(app).also { settle() }
        fun field(name: String) = com.tj.portfolio.ui.PortfolioViewModel::class.java
            .getDeclaredField(name).apply { isAccessible = true }.get(vm)
        @Suppress("UNCHECKED_CAST")
        val quotes = field("_quotes") as kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.tj.portfolio.data.Quote>>
        quotes.value = mapOf("AAA" to com.tj.portfolio.data.Quote("AAA", price = 10.0, spark = listOf(9.0, 10.0)))
        @Suppress("UNCHECKED_CAST")
        val keys = field("storyKeys") as HashMap<String, String>
        keys["id"] = "STORY|key"
        com.tj.portfolio.util.MemoryTrim.trim(com.tj.portfolio.util.MemoryTrim.UI_HIDDEN)
        assertEquals("an app switch frees nothing", 1, keys.size)
        com.tj.portfolio.util.MemoryTrim.trim(40)
        assertTrue("the story-key memo is freed at 40", keys.isEmpty())
        assertEquals("the sparkline on screen is kept", listOf(9.0, 10.0), quotes.value.getValue("AAA").spark)
    }

    @Test fun `R1-9 an answered-empty batch does not ask the second host`() = kotlinx.coroutines.runBlocking {
        val auth = com.tj.portfolio.net.YahooAuth
        val crumbF = auth::class.java.getDeclaredField("crumb").apply { isAccessible = true }
        val atF = auth::class.java.getDeclaredField("mintedAt").apply { isAccessible = true }
        val (crumb0, at0) = crumbF.get(auth) to atF.get(auth)
        try {
            crumbF.set(auth, "abcdEFGH"); atF.set(auth, System.currentTimeMillis())
            val seen = scripted { url ->
                if ("/v7/finance/quote" in url)
                    com.tj.portfolio.net.HttpResult(200, """{"quoteResponse":{"result":[],"error":null}}""")
                else null
            }
            val (verdict, quotes) = com.tj.portfolio.net.MarketData.batchYahoo(listOf("SPX"))
            assertEquals(com.tj.portfolio.net.MarketData.Batch.INCONCLUSIVE, verdict)
            assertTrue(quotes.isEmpty())
            assertEquals(listOf("query1"), seen.filter { "/v7/" in it }.map { it.substringAfter("//").substringBefore('.') })
        } finally { crumbF.set(auth, crumb0); atF.set(auth, at0) }
    }
}
