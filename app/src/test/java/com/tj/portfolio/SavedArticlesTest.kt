package com.tj.portfolio

import com.tj.portfolio.net.GoogleNewsLink
import com.tj.portfolio.net.OfflinePages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Saved-article fixes of 2026-10-28: Google News links resolve, and every story has a saved card. */
class SavedArticlesTest {
    private fun legacyId(url: String): String {
        val bytes = byteArrayOf(0x08, 0x13, 0x22, url.length.toByte()) + url.toByteArray() + byteArrayOf(0xd2.toByte(), 0x01, 0x00)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Test fun legacyIdDecodesToPublisherUrl() {
        val id = legacyId("https://www.example.com/news/story-1")
        val link = "https://news.google.com/rss/articles/$id?oc=5"
        assertTrue(GoogleNewsLink.isGoogleNews(link))
        assertEquals(id, GoogleNewsLink.articleId(link))
        assertEquals("https://www.example.com/news/story-1", GoogleNewsLink.decodeLegacy(id))
    }

    @Test fun newStyleIdIsNotDecodedLocally() {
        val body = "AU_yqLabcdef"
        val bytes = byteArrayOf(0x08, 0x13, 0x22, body.length.toByte()) + body.toByteArray()
        assertNull(GoogleNewsLink.decodeLegacy(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
    }

    @Test fun signatureAndExchange() {
        val html = """<c-wiz><div data-n-a-id="X" data-n-a-sg="SIG123" data-n-a-ts="1700000000"></div></c-wiz>"""
        assertEquals("SIG123" to "1700000000", GoogleNewsLink.signature(html))
        assertNull(GoogleNewsLink.signature("<html></html>"))
        val body = GoogleNewsLink.exchangeBody("IDX", "SIG123", "1700000000")
        assertTrue(body.startsWith("f.req=") && body.contains("Fbv4je") && body.contains("IDX"))
    }

    @Test fun exchangeReplyParses() {
        val reply = ")]}'\n\n[[\"wrb.fr\",\"Fbv4je\",\"[\\\"garturlres\\\",\\\"https://pub.test/a?x=1\\\\u0026y=2\\\",1]\",null]]"
        assertEquals("https://pub.test/a?x=1&y=2", GoogleNewsLink.parseExchange(reply))
        assertNull(GoogleNewsLink.parseExchange("garbage"))
    }

    @Test fun cardKeepsSummaryWhenPageCannotBeSaved() {
        val c = OfflinePages.card("T", "Reuters", "Shares rose after the company reported quarterly results above estimates.", 0L)
        assertNotNull(c)
        assertTrue(c!!.contains("Reuters") && c.contains("Summary only"))
        assertNull(OfflinePages.card("T", "S", "short", 0L))
        assertNull(OfflinePages.card("Same title text that is long enough to count here", "S", "Same title text that is long enough to count here", 0L))
    }
}
