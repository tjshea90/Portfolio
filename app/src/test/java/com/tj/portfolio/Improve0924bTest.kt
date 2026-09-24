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
}
