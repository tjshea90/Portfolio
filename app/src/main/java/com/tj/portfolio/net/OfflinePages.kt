package com.tj.portfolio.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * SAVING A WEB PAGE FOR OFFLINE READING (2026-10-27).
 *
 * Tj: "The app may save websites for offline viewing." A live page is 1-3 MB of scripts,
 * trackers and images that cannot run offline anyway. What is worth keeping is the ARTICLE TEXT,
 * so a saved page is the readable core only: scripts, styles, frames, forms, navigation, images
 * and every attribute but `href` are dropped, then the text is gzip-packed (~4:1). A typical
 * article lands at 10-40 KB, so the 50 MB budget holds hundreds of them.
 *
 * Pure string work — no Android types — so it is covered by plain JVM unit tests.
 */
object OfflinePages {

    /** Visible-text floor. Below this the page was a consent wall, a JS shell or a redirect stub. */
    const val MIN_TEXT_CHARS = 600

    /** Hard ceiling on one saved page's readable HTML, before gzip. */
    const val MAX_HTML_CHARS = 200_000

    /** Hosts whose links are JS redirect stubs that never yield an article to a plain GET. */
    private val UNFETCHABLE_HOSTS = listOf("news.google.com", "consent.yahoo.com", "t.co", "bit.ly")

    /** Google News links are redirects; [GoogleNewsLink] resolves them to the publisher first. */
    fun needsResolving(url: String) = GoogleNewsLink.isGoogleNews(url)

    fun worthFetching(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
        if (UNFETCHABLE_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        val path = u.substringAfter(host).lowercase().substringBefore('?')
        if (path.endsWith(".pdf") || path.endsWith(".jpg") || path.endsWith(".png") ||
            path.endsWith(".mp4") || path.endsWith(".zip")
        ) return false
        return true
    }

    private val BLOCKS = listOf(
        "script", "style", "noscript", "iframe", "svg", "form", "nav", "header", "footer",
        "aside", "template", "picture", "video", "audio", "canvas", "object", "embed", "button",
        "select", "dialog"
    ).map { Regex("<$it\\b[^>]*>.*?</$it\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ARTICLE = Regex("<article\\b[^>]*>(.*)</article\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BODY = Regex("<body\\b[^>]*>(.*)</body\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val HREF = Regex("href\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val KEEP = setOf(
        "p", "h1", "h2", "h3", "h4", "ul", "ol", "li", "blockquote", "a", "b", "strong", "i", "em",
        "br", "table", "thead", "tbody", "tr", "td", "th", "pre", "code", "figcaption", "time", "span", "div"
    )
    private val TEXT_STRIP = Regex("<[^>]*>")
    private val WS = Regex("\\s+")

    /** The page's `<title>`, unescaped and trimmed; blank if none. */
    fun titleOf(html: String): String =
        TITLE.find(html)?.groupValues?.get(1)?.let { unescape(it.replace(WS, " ")).trim() }.orEmpty()

    /** Visible characters left once tags are gone. */
    fun textLength(html: String): Int = unescape(html.replace(TEXT_STRIP, " ")).replace(WS, " ").trim().length

    /**
     * The readable core of [raw] as small, attribute-free HTML, or null when the page has too
     * little real text to be worth a row (a paywall stub, a JS shell, an error page).
     */
    fun sanitize(raw: String): String? {
        var h = COMMENT.replace(raw, "")
        for (r in BLOCKS) h = r.replace(h, "")
        // The article element is the cleanest cut; fall back to the body, then to everything.
        val core = ARTICLE.find(h)?.groupValues?.get(1)?.takeIf { textLength(it) >= MIN_TEXT_CHARS }
            ?: BODY.find(h)?.groupValues?.get(1)
            ?: h
        val cleaned = TAG.replace(core) { m ->
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            when {
                name !in KEEP -> ""
                closing -> "</$name>"
                name == "a" -> {
                    val href = HREF.find(m.groupValues[3])?.groupValues?.get(2).orEmpty()
                    if (href.startsWith("http")) "<a href=\"${href.replace("\"", "&quot;")}\">" else "<a>"
                }
                // span/div/time carry no meaning once styling is gone; keep paragraphs only.
                name == "span" || name == "div" || name == "time" -> ""
                else -> "<$name>"
            }
        }.replace(WS, " ").trim()
        if (textLength(cleaned) < MIN_TEXT_CHARS) return null
        return if (cleaned.length > MAX_HTML_CHARS) cleaned.substring(0, MAX_HTML_CHARS) else cleaned
    }

    /** The complete document shown in the reader: viewport, readable CSS, source line, body. */
    fun wrap(title: String, sourceUrl: String, savedAt: Long, body: String): String {
        val t = escape(title.ifBlank { sourceUrl })
        val src = escape(sourceUrl)
        val date = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(java.util.Date(savedAt))
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$t</title><style>" +
            "body{font-family:sans-serif;line-height:1.55;font-size:17px;max-width:46em;margin:0 auto;padding:14px 16px;color:#1b1b1b;background:#fff}" +
            "@media(prefers-color-scheme:dark){body{background:#121212;color:#e6e6e6}a{color:#8ab4f8}}" +
            "h1,h2,h3{line-height:1.25}.src{font-size:13px;opacity:.7;margin-bottom:14px;word-break:break-all}" +
            "table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #8886;padding:3px 6px}" +
            "</style></head><body><h2>$t</h2><div class=\"src\">Saved copy &middot; $date<br>$src</div>$body</body></html>"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /**
     * A page built from what the feed itself delivered - headline, source, summary - for stories
     * whose full page cannot be saved (paywall, JavaScript shell, blocked fetch). A tap still
     * opens something offline instead of an error. Null when the feed gave no summary at all.
     */
    fun card(title: String, source: String, summary: String, published: Long): String? {
        val sum = summary.trim()
        if (sum.length < 40 || sum.equals(title.trim(), true)) return null
        val when_ = if (published > 0)
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(published)) else ""
        return "<p><b>${escape(source)}</b>${if (when_.isNotEmpty()) " &middot; $when_" else ""}</p>" +
            "<p>${escape(sum)}</p>" +
            "<p><i>Summary only - the full article could not be saved. Open it when you are online.</i></p>"
    }

    fun pack(html: String): ByteArray {
        val out = ByteArrayOutputStream(html.length / 3 + 64)
        GZIPOutputStream(out).use { it.write(html.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun unpack(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
}
