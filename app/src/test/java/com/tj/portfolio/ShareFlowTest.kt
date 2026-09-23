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
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.ShareDest
import com.tj.portfolio.util.PromptShare
import com.tj.portfolio.util.ShareInbox
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
import org.robolectric.shadows.ShadowLooper

/**
 * THE CLAUDE-APP ROUND TRIP THROUGH THE SHARE SHEET (2026-09-23b).
 *
 * Tj's ask: "Make prompt file" opens the share sheet so he can pick a new Claude chat, and
 * sharing Claude's answer file back to Portfolio imports it and "already know[s] how to use
 * the data just from the share". No button on this side says which prompt an answer belongs
 * to, so the whole feature rests on three things proven here:
 *   1. a shared answer is routed by its CONTENT to the right importer and the right screen,
 *      and anything that is not an answer - above all the prompt file itself - is refused;
 *   2. the outgoing file really is readable by whichever app is picked, through the
 *      FileProvider the manifest declares, as the share intent the sheet is handed;
 *   3. the inbox hand-off imports a share exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareFlowTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        ShareInbox.take(app)
    }

    @After fun tearDown() {
        app.deleteDatabase(Db.DB_NAME)
        ShareInbox.take(app)
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper()
    }

    // ------------------------------------------------------------------ fixtures

    private val dayTradingReply = """
Here is my read on today's list.

```json
{
  "portfolioAppResponse": 1,
  "dayTrading": {
    "asOf": "2026-09-23",
    "picks": [
      {"symbol": "GME", "why": "A short squeeze is forming after a surprise earnings beat and heavy call buying.", "risk": "Could reverse hard if the short-covering push stalls.", "conviction": 8}
    ],
    "notes": "Volume ratio looks a little stale."
  }
}
```

${ClaudeBridge.SHARE_BACK_LINE}
""".trimIndent()

    private val researchReply = """
```json
{"portfolioAppResponse": 1, "research": {"best": [
  {"symbol": "GOOD", "why": "Steady operator trading at 14x forward with mid-teens growth.", "conviction": 8}
]}}
```
""".trimIndent()

    private val adviceReply = """
{"portfolioAppResponse": 1, "advice": {
  "summary": "Concentrated in semiconductors with very little cash on hand.",
  "risks": "One sector drives nearly all of the result.",
  "actions": ["Trim NVDA into strength"],
  "stocks": [{"symbol": "NVDA", "rating": 7, "action": "HOLD", "target": "", "reasoning": "Strong franchise at a full price."}]
}}
""".trimIndent()

    private val transactionsReply = """
{"portfolioAppResponse": 1, "notes": "", "transactions": [
  {"type": "BUY", "symbol": "AAPL", "quantity": 2, "price": 100, "amount": 200, "fees": 0, "date": "2026-09-01", "note": ""}
]}
""".trimIndent()

    private fun dtSet() = ResearchSet(
        dayTrading = listOf(ResearchRow(symbol = "GME", score = 71, reasons = listOf("in play"))),
        generated = System.currentTimeMillis()
    )

    // ------------------------------------------------------------ classification

    @Test fun `each answer is recognised by what is inside it`() {
        assertEquals(SharedAnswer.Kind.DAY_TRADING, SharedAnswer.classify(dayTradingReply))
        assertEquals(SharedAnswer.Kind.RESEARCH, SharedAnswer.classify(researchReply))
        assertEquals(SharedAnswer.Kind.CLAUDE, SharedAnswer.classify(adviceReply))
        assertEquals(SharedAnswer.Kind.CLAUDE, SharedAnswer.classify(transactionsReply))
    }

    @Test fun `every prompt file shared back by mistake is refused as the prompt, not parsed`() {
        val prompts = listOf(
            ClaudeBridge.advicePrompt("{}", "(none)"),
            ClaudeBridge.screenshotPrompt("first import", emptyList()),
            ResearchBridge.prompt(dtSet(), emptyList(), emptyList()),
            DayTradingBridge.prompt(dtSet(), emptyList(), emptyList())
        )
        prompts.forEach {
            assertEquals(SharedAnswer.Kind.PROMPT_FILE, SharedAnswer.classify(it))
            assertTrue(SharedAnswer.rejection(SharedAnswer.Kind.PROMPT_FILE)!!.contains("prompt file"))
        }
    }

    @Test fun `empty and binary shares are refused before any importer sees them`() {
        assertEquals(SharedAnswer.Kind.EMPTY, SharedAnswer.classify(null))
        assertEquals(SharedAnswer.Kind.EMPTY, SharedAnswer.classify("  \n "))
        // What a PNG looks like decoded as text: its signature, NULs and replacement chars.
        val png = "�PNG\r\n\u001A\n" + "\u0000\u0000\u0000\rIHDR" + "\u0000�".repeat(200)
        assertEquals(SharedAnswer.Kind.NOT_TEXT, SharedAnswer.classify(png))
        // One stray control character in a real answer is not enough to reject it.
        assertEquals(SharedAnswer.Kind.DAY_TRADING, SharedAnswer.classify("\u0007" + dayTradingReply))
    }

    @Test fun `every prompt asks for its own named answer file and the share-back line`() {
        val cases = mapOf(
            ClaudeBridge.advicePrompt("{}", "(none)") to ClaudeBridge.ANSWER_ADVICE,
            ClaudeBridge.screenshotPrompt("first import", emptyList()) to ClaudeBridge.ANSWER_TRANSACTIONS,
            ResearchBridge.prompt(dtSet(), emptyList(), emptyList()) to ClaudeBridge.ANSWER_RESEARCH,
            DayTradingBridge.prompt(dtSet(), emptyList(), emptyList()) to ClaudeBridge.ANSWER_DAY_TRADING
        )
        cases.forEach { (prompt, name) ->
            assertTrue("prompt does not name $name", prompt.contains("`$name`"))
            assertTrue("prompt does not ask for the share-back line", prompt.contains(ClaudeBridge.SHARE_BACK_LINE))
        }
    }

    // ----------------------------------------------------------- outgoing share

    @Test fun `a staged prompt is readable through the provider as the share intent carries it`() {
        val body = DayTradingBridge.prompt(dtSet(), emptyList(), emptyList())
        val uri = PromptShare.stage(app, DayTradingBridge.PROMPT_FILE, body)
        assertNotNull("staging failed - is the FileProvider declared?", uri)
        assertEquals("content", uri!!.scheme)
        assertEquals(PromptShare.authority(app), uri.authority)

        val chooser = PromptShare.chooser(uri, DayTradingBridge.PROMPT_FILE)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        // No EXTRA_TEXT - a target that prefers text would drop the attachment.
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))

        @Suppress("DEPRECATION")
        val streamed = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val read = app.contentResolver.openInputStream(streamed)!!.use { String(it.readBytes()) }
        assertEquals(body, read)
    }

    @Test fun `staging again replaces the file rather than accumulating copies`() {
        val a = PromptShare.stage(app, "claude-advice-prompt.md", "first")!!
        val b = PromptShare.stage(app, "claude-advice-prompt.md", "second")!!
        assertEquals(a, b)
        assertEquals("second", app.contentResolver.openInputStream(b)!!.use { String(it.readBytes()) })
    }

    // ------------------------------------------------------------ incoming share

    @Test fun `a shared file, an opened file and shared text all read the same`() {
        val uri = PromptShare.stage(app, "answer.md", dayTradingReply)!!
        val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, dayTradingReply)
        listOf(send, view, text).forEach {
            assertEquals(dayTradingReply, ShareInbox.readShared(app, it, SharedAnswer.MAX_CHARS))
        }
        // Too large is refused, not truncated into a half answer.
        assertNull(ShareInbox.readShared(app, text, maxChars = 10))
        assertNull(ShareInbox.readShared(app, send, maxChars = 10))
    }

    @Test fun `the inbox hands over a share exactly once`() {
        assertTrue(ShareInbox.put(app, "hello"))
        assertEquals("hello", ShareInbox.take(app))
        assertNull("a re-delivered intent would import it twice", ShareInbox.take(app))
    }

    // ------------------------------------------------------- import and navigate

    @Test fun `a shared day-trading answer fills Day Trading and opens it`() {
        val vm = PortfolioViewModel(app)
        settle()
        assertTrue(ShareInbox.put(app, dayTradingReply))
        vm.importSharedInbox()
        settle()
        assertEquals(ShareDest.DAY_TRADING, vm.shareNav.value)
        val dt = ResearchSet.SECTIONS.indexOf(ResearchSet.SECTION_DAY_TRADING)
        assertEquals(dt, vm.researchJump.value)
        assertEquals(dt, vm.researchTab())
        assertTrue(vm.research.value.dayTrading.any { it.symbol == "GME" && it.why.contains("short squeeze") })
        assertNull("the inbox was not cleared", ShareInbox.take(app))
    }

    @Test fun `a shared research answer opens Research on a list it filled`() {
        val vm = PortfolioViewModel(app)
        settle()
        // Day Trading was open last - a Research answer did not touch it, so it must move off.
        vm.setResearchTab(ResearchSet.SECTIONS.indexOf(ResearchSet.SECTION_DAY_TRADING))
        val r = vm.importShared(researchReply)
        assertEquals(ShareDest.RESEARCH, r.dest)
        assertEquals(0, vm.researchTab())
        assertTrue(vm.research.value.best.any { it.symbol == "GOOD" })
    }

    @Test fun `advice opens Advice and transactions open the review on Activity`() {
        val vm = PortfolioViewModel(app)
        settle()
        val a = vm.importShared(adviceReply)
        assertEquals(ShareDest.ADVICE, a.dest)
        assertEquals("NVDA", vm.advice.value!!.stocks.single().symbol)

        val t = vm.importShared(transactionsReply)
        assertEquals(ShareDest.ACTIVITY, t.dest)
        assertEquals("AAPL", vm.importResult.value!!.transactions.single().symbol)
    }

    @Test fun `junk and the prompt file navigate nowhere and change nothing`() {
        val vm = PortfolioViewModel(app)
        settle()
        val before = vm.research.value
        listOf(
            DayTradingBridge.prompt(dtSet(), emptyList(), emptyList()),
            "just some notes I meant to paste somewhere else",
            ""
        ).forEach {
            val r = vm.importShared(it)
            assertNull("'${it.take(30)}' navigated to ${r.dest}", r.dest)
            assertFalse(r.message.isBlank())
        }
        assertEquals(before, vm.research.value)
        assertNull(vm.advice.value)
        assertNull(vm.importResult.value)
    }
}
