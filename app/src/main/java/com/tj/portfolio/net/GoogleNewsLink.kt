package com.tj.portfolio.net

import java.util.Base64

/**
 * GOOGLE NEWS LINKS -> THE PUBLISHER'S OWN URL (2026-10-28).
 *
 * Most headlines in the app come from Google News RSS, whose `<link>` is an opaque redirect
 * (`news.google.com/rss/articles/CBMi...`) that only a JavaScript browser can follow. The offline
 * page saver used to skip those hosts outright, so almost no article was ever saved - Tj: "It
 * wasn't saving websites. I couldn't click on websites to see cached articles."
 *
 * Two shapes exist:
 *  - older ids are protobuf with the publisher URL embedded in the clear: decoded here, offline;
 *  - newer ids (`AU_yqL...`) are opaque and must be exchanged through Google's own
 *    `batchexecute` endpoint using a signature + timestamp printed on the article page.
 *
 * Everything in this object is pure string work so it is unit-tested; [Http] calls live in
 * [OfflineSync]. If Google changes the exchange, [resolve] fails closed (null) and the saver
 * falls back to a headline-and-summary card, so nothing breaks.
 */
object GoogleNewsLink {

    private val ARTICLE = Regex("""news\.google\.com/(?:rss/)?(?:read|articles)/([A-Za-z0-9_\-=]+)""")
    private val SIG = Regex("""data-n-a-sg="([^"]+)"""")
    private val TS = Regex("""data-n-a-ts="([^"]+)"""")
    private val RESULT = Regex("""garturlres\\+"\s*,\s*\\+"(https?:.*?)\\+"""")

    fun isGoogleNews(url: String): Boolean = ARTICLE.containsMatchIn(url)

    fun articleId(url: String): String? = ARTICLE.find(url)?.groupValues?.get(1)

    /** The publisher URL when the id carries it in the clear (older ids), else null. */
    fun decodeLegacy(id: String): String? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(id.trimEnd('=') .let { it + "=".repeat((4 - it.length % 4) % 4) })
        var s = String(bytes, Charsets.ISO_8859_1)
        // Strip the 0x08 0x13 0x22 header, then a varint length.
        if (s.length < 5 || s[0].code != 0x08 || s[1].code != 0x13 || s[2].code != 0x22) return null
        s = s.substring(3)
        var len = s[0].code
        var off = 1
        if (len >= 0x80) { len = (len and 0x7f) or (s[1].code shl 7); off = 2 }
        val body = s.substring(off).take(len)
        // Newer ids start with this marker and hold no URL at all.
        if (body.startsWith("AU_yqL")) return null
        body.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrNull()

    /** (signature, timestamp) printed on the article page, or null. */
    fun signature(html: String): Pair<String, String>? {
        val sg = SIG.find(html)?.groupValues?.get(1) ?: return null
        val ts = TS.find(html)?.groupValues?.get(1) ?: return null
        return sg to ts
    }

    /** The form body for the `Fbv4je` batchexecute call. */
    fun exchangeBody(id: String, signature: String, timestamp: String): String {
        val inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1]," +
            "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"$id\",$timestamp,\"$signature\"]"
        val outer = "[[[\"Fbv4je\",${jsonString(inner)},null,\"generic\"]]]"
        return "f.req=" + java.net.URLEncoder.encode(outer, "UTF-8")
    }

    const val EXCHANGE_URL = "https://news.google.com/_/DotsSplashUi/data/batchexecute"

    /** The publisher URL inside a batchexecute reply, or null. */
    fun parseExchange(reply: String): String? {
        val raw = RESULT.find(reply)?.groupValues?.get(1) ?: return null
        return unicodeUnescape(raw).takeIf { it.startsWith("http") && !isGoogleNews(it) }
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val UNI = Regex("""\\+u([0-9a-fA-F]{4})""")
    private fun unicodeUnescape(s: String) =
        UNI.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\/", "/")
}
