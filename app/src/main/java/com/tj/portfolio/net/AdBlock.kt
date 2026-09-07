package com.tj.portfolio.net

/**
 * Which subresource requests the in-app reader refuses to load.
 *
 * WHY. TJ asked for ads and pop-ups to be blocked in the reader. A finance article typically
 * pulls a hundred-odd extra requests - ad exchanges, bidders, trackers, tag managers, session
 * recorders - and on a phone those are most of the page's load time, most of its battery
 * cost, and all of its pop-ups. Blocking them at the network layer is both the fastest and
 * the most reliable place: an ad that never loads cannot render an interstitial, cannot open
 * a window, and cannot run a script that re-inserts itself.
 *
 * HOW IT IS MATCHED. By HOST, on the registrable-domain suffix, never by URL substring. A
 * substring rule like `contains("ads")` blocks `https://reuters.com/business/roadside-ads`
 * and `downloads.example.com`, and that class of false positive breaks real articles. The
 * host is split on dots and every suffix is looked up, so `securepubads.g.doubleclick.net`
 * matches the `doubleclick.net` entry with one hash lookup per label and no regex at all -
 * which matters because this runs on the WebView's IO thread for every single subresource.
 *
 * WHAT IS DELIBERATELY *NOT* BLOCKED. Anything a news page needs to render itself: CDNs,
 * image hosts, font providers, comment systems the reader might legitimately show. The list
 * is ad exchanges, analytics and session recording only. It is also deliberately short -
 * a 100,000-entry blocklist is not maintainable inside an app with no update channel beyond
 * a sideloaded APK, and the top few dozen hosts are the overwhelming majority of the traffic.
 *
 * There is a Settings switch to turn the whole thing off, because a blocklist occasionally
 * breaks a site and the user needs a way out that is not "wait for a new build".
 */
object AdBlock {

    /**
     * Registrable domains whose requests are dropped. Matched on any dot-suffix of the host.
     *
     * Grouped by what they are, so the list can be reasoned about rather than just trusted.
     */
    private val BLOCKED: Set<String> = setOf(
        // --- Google's ad stack (the single biggest source on a news page)
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "google-analytics.com", "googletagmanager.com",
        "adservice.google.com", "pagead2.googlesyndication.com",

        // --- exchanges, SSPs and header-bidding
        "amazon-adsystem.com", "adnxs.com", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "criteo.com", "criteo.net", "adform.net",
        "smartadserver.com", "sharethrough.com", "33across.com", "indexww.com",
        "media.net", "yieldmo.com", "triplelift.com", "sovrn.com", "gumgum.com",
        "districtm.io", "bidswitch.net", "adsrvr.org", "everesttech.net",
        "moatads.com", "adsafeprotected.com", "serving-sys.com", "flashtalking.com",
        "teads.tv", "outbrain.com", "taboola.com", "revcontent.com", "zergnet.com",
        "mgid.com", "content-ad.net", "adblade.com",

        // --- analytics, attribution and session recording
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "chartbeat.com",
        "chartbeat.net", "parsely.com", "parse.ly", "newrelic.com", "nr-data.net",
        "segment.com", "segment.io", "mixpanel.com", "amplitude.com", "heap.io",
        "hotjar.com", "hotjar.io", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "clarity.ms", "branch.io", "adjust.com", "appsflyer.com", "kochava.com",
        "bounceexchange.com", "permutive.com", "krxd.net", "demdex.net", "omtrdc.net",
        "2o7.net", "tiqcdn.com", "cxense.com", "lytics.io", "browsiprod.com",

        // --- social trackers (the pixels, not the sites themselves)
        "connect.facebook.net", "facebook.net", "ads-twitter.com", "analytics.twitter.com",
        "px.ads.linkedin.com", "bat.bing.com", "ads.pinterest.com", "analytics.tiktok.com",

        // --- consent walls and pop-up / push-notification vendors
        //     These are the actual source of most "allow notifications?" and
        //     "subscribe to our newsletter" interstitials.
        "onesignal.com", "pushcrew.com", "pushengage.com", "izooto.com", "sailthru.com",
        "wisepops.com", "privy.com", "sumo.com", "optinmonster.com", "getsitecontrol.com",
        "justuno.com", "exponea.com", "insider.com.tr", "dynamicyield.com"
    )

    /**
     * True when this request should be dropped.
     *
     * Cheap by construction: one host parse, then at most a handful of set lookups. No regex,
     * no allocation beyond the substrings, because the WebView calls this for every image,
     * script, stylesheet and beacon on the page.
     */
    fun blocks(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        // Walk the dot-suffixes: a.b.c -> "a.b.c", "b.c", "c". The first two labels of a
        // suffix are enough for every entry in the list, so this is 2-4 lookups typically.
        var i = 0
        while (true) {
            val suffix = if (i == 0) host else host.substring(i)
            if (suffix in BLOCKED) return true
            val dot = host.indexOf('.', i)
            if (dot < 0) return false
            i = dot + 1
            // a bare TLD can never match, and stopping here avoids a pointless final lookup
            if (host.indexOf('.', i) < 0) return false
        }
    }

    /**
     * Lower-cased host of a URL, without a port, or null when there isn't one.
     *
     * Parsed by hand rather than with `java.net.URL`: that constructor throws on the
     * protocol-relative and `data:` URLs a page is full of, and building an exception per
     * blocked beacon is not something to do on the render path.
     */
    internal fun hostOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val schemeEnd = url.indexOf("//")
        if (schemeEnd < 0) return null
        // only ever block network requests; data:, blob: and file: have no host
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (!(scheme.startsWith("http") || scheme.isEmpty())) return null
        var start = schemeEnd + 2
        // strip any userinfo
        val slash = url.indexOf('/', start).let { if (it < 0) url.length else it }
        val at = url.lastIndexOf('@', slash - 1)
        if (at in start until slash) start = at + 1
        var end = slash
        val colon = url.indexOf(':', start)
        if (colon in start until end) end = colon
        if (end <= start) return null
        return url.substring(start, end).lowercase().removeSuffix(".")
    }
}
