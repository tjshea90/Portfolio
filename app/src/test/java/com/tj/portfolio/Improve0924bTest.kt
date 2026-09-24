package com.tj.portfolio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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
        val R = com.tj.portfolio.data.ChartRange
        fun fin(range: com.tj.portfolio.data.ChartRange, end: Long, fetched: Long, now: Long) =
            com.tj.portfolio.ui.intradayChartIsFinal(range, end, fetched, now)
        // 2026-09-25 is a Friday. Last 30-minute candle 15:30, last daily candle 09:30.
        val fri1530 = ny(2026, 9, 25, 15, 30); val fri0930 = ny(2026, 9, 25, 9, 30)
        val sat = ny(2026, 9, 26, 11); val sun = ny(2026, 9, 27, 20)
        assertTrue(fin(R.D5, fri1530, sat, sun))
        assertTrue(fin(R.M1, fri0930, sat, sun))
        assertFalse("Monday's session has opened", fin(R.D5, fri1530, sat, ny(2026, 9, 28, 9, 45)))
        assertFalse("fetched during the session", fin(R.D5, fri1530, ny(2026, 9, 25, 15, 45), sun))
        // Fetched Friday evening, but the newest candle is Thursday's: Yahoo was behind.
        assertFalse(fin(R.M1, ny(2026, 9, 24, 9, 30), ny(2026, 9, 25, 18), sat))
        // Pre-market: the regular-only 5D cannot change before 09:30.
        assertTrue(fin(R.D5, fri1530, ny(2026, 9, 28, 8), ny(2026, 9, 28, 9, 0)))
        // Longer ranges keep their own TTLs.
        assertFalse(fin(R.M6, fri0930, sat, sun))
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
