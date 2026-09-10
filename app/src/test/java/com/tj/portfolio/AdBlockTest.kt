package com.tj.portfolio

import com.tj.portfolio.net.AdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the in-app reader refuses to load.
 *
 * The interesting half of a blocklist is not what it blocks - that is a list, and a list is
 * either right or it is not. It is what it must NOT block: a rule that is even slightly too
 * loose silently breaks real articles, and the user experiences that as "the app can't open
 * news", which is far worse than seeing an advert.
 */
class AdBlockTest {

    // ------------------------------------------------------ it blocks the right things

    @Test
    fun `the big ad and tracking hosts are blocked`() {
        listOf(
            "https://securepubads.g.doubleclick.net/gampad/ads?foo=1",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://www.google-analytics.com/collect?v=2",
            "https://s.amazon-adsystem.com/iu3",
            "https://ib.adnxs.com/ut/v3/prebid",
            "https://static.criteo.net/js/ld/publishertag.js",
            "https://sb.scorecardresearch.com/b2?c1=2",
            "https://cdn.taboola.com/libtrc/loader.js",
            "https://widgets.outbrain.com/outbrain.js",
            "https://script.hotjar.com/modules.js",
            "https://cdn.onesignal.com/sdks/OneSignalSDK.js",
            "https://www.googletagmanager.com/gtm.js?id=GTM-XYZ"
        ).forEach { assertTrue("should block $it", AdBlock.blocks(it)) }
    }

    @Test
    fun `a deep subdomain of a blocked host is still blocked`() {
        assertTrue(AdBlock.blocks("https://a.b.c.d.doubleclick.net/x"))
    }

    // ------------------------------------------------------ it does NOT block the article

    @Test
    fun `real news hosts load`() {
        listOf(
            "https://finance.yahoo.com/news/nvidia-earnings-123456.html",
            "https://www.nasdaq.com/articles/five-below-q2",
            "https://www.reuters.com/business/",
            "https://www.cnbc.com/2026/09/04/cramer.html",
            "https://www.marketwatch.com/story/abc",
            "https://simplywall.st/stocks/us/retail/nasdaq-five",
            "https://news.google.com/rss/articles/xyz",
            "https://www.sec.gov/cgi-bin/browse-edgar"
        ).forEach { assertFalse("must NOT block $it", AdBlock.blocks(it)) }
    }

    @Test
    fun `page assets a story needs are not blocked`() {
        // Images, fonts, stylesheets and the site's own CDN. Blocking any of these produces a
        // page that looks broken, which is a worse outcome than the advert.
        listOf(
            "https://s.yimg.com/uu/api/res/1.2/image.jpg",
            "https://fonts.googleapis.com/css2?family=Inter",
            "https://fonts.gstatic.com/s/inter/v12/font.woff2",
            "https://images.wsj.net/im-12345",
            "https://cdn.cnn.com/cnnnext/dam/assets/photo.jpg",
            "https://static.foxnews.com/static/orion/styles/main.css"
        ).forEach { assertFalse("must NOT block $it", AdBlock.blocks(it)) }
    }

    @Test
    fun `substring lookalikes are not blocked`() {
        // This is the whole reason matching is by host suffix and not by `contains`. Every
        // one of these would be blocked by a naive substring rule, and every one of them is
        // a legitimate request.
        listOf(
            "https://www.reuters.com/business/roadside-ads-are-back-2026-09-04/",
            "https://downloads.example.com/report.pdf",
            "https://notdoubleclick.net.example.com/x",
            "https://example.com/?ref=https://doubleclick.net",
            "https://myadnxs.com/safe",
            "https://criteo.com.evil-lookalike.org/x"
        ).forEach { assertFalse("must NOT block $it", AdBlock.blocks(it)) }
    }

    @Test
    fun `a blocked name as a path or query does not block the request`() {
        assertFalse(AdBlock.blocks("https://finance.yahoo.com/taboola.com/article"))
        assertFalse(AdBlock.blocks("https://finance.yahoo.com/x?utm=google-analytics.com"))
    }

    // ------------------------------------------------------ parsing edge cases
    //
    // These run on the render path for every subresource, so they must never throw and must
    // never build an exception - the reason the host is parsed by hand rather than with
    // java.net.URL, whose constructor throws on half of what a page contains.

    @Test
    fun `non-network urls have no host and are never blocked`() {
        assertFalse(AdBlock.blocks("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(AdBlock.blocks("blob:https://example.com/1234"))
        assertFalse(AdBlock.blocks("about:blank"))
        assertFalse(AdBlock.blocks("javascript:void(0)"))
        assertFalse(AdBlock.blocks("file:///android_asset/x.html"))
        assertFalse(AdBlock.blocks(""))
        assertFalse(AdBlock.blocks(null))
    }

    @Test
    fun `ports userinfo and trailing dots are handled`() {
        assertEquals("doubleclick.net", AdBlock.hostOf("https://doubleclick.net:443/x"))
        assertEquals("doubleclick.net", AdBlock.hostOf("https://user:pw@doubleclick.net/x"))
        assertEquals("doubleclick.net", AdBlock.hostOf("https://doubleclick.net./x"))
        assertEquals("doubleclick.net", AdBlock.hostOf("HTTPS://DoubleClick.NET/x"))
        assertTrue(AdBlock.blocks("https://user:pw@ads.doubleclick.net:8443/x?y=z"))
    }

    @Test
    fun `a url with no path still parses`() {
        assertEquals("example.com", AdBlock.hostOf("https://example.com"))
        assertEquals("example.com", AdBlock.hostOf("//example.com/x"))
    }

    @Test
    fun `malformed input returns null rather than throwing`() {
        assertNull(AdBlock.hostOf("https://"))
        assertNull(AdBlock.hostOf("not a url at all"))
        assertNull(AdBlock.hostOf("https:///path-only"))
        // and the public entry point stays quiet about all of it
        assertFalse(AdBlock.blocks("https://"))
        assertFalse(AdBlock.blocks("::::"))
    }

    @Test
    fun `a bare hostname with no dot is not blocked`() {
        assertFalse(AdBlock.blocks("http://localhost/x"))
        assertFalse(AdBlock.blocks("http://intranet/x"))
    }
}
