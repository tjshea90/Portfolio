package com.tj.portfolio.net

import android.util.Xml
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.util.Fmt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Headlines, from several free sources with no keys.
 *
 * SOURCES AND WHY THESE ONES. Every feed here was fetched and read before being wired in
 * (see the research block in CHECKPOINT.md). That matters more than it sounds: several
 * well-known finance feeds are still *valid RSS* but have not published in over a year -
 * MarketWatch's MarketPulse and Real-time Headlines feeds are both dead in exactly that
 * way, and Nasdaq's market-wide feed runs weeks behind while its per-symbol feed off the
 * same host is current to the hour.
 *
 *   PER SYMBOL   Yahoo Finance -> Nasdaq -> Google News -> Finnhub (only with a key)
 *   MARKET-WIDE  Google News (3 topic queries) + MarketWatch + Investing.com
 *
 * REQUEST BUDGET. Adding sources must not multiply the load on any of them - a free feed
 * that gets hammered starts answering 403. So there are two modes:
 *
 *   deep = false  the cascade. Yahoo alone answers for almost every symbol, so this is
 *                 normally ONE request per symbol. Used by the background feed refresh,
 *                 which runs over every holding and every watchlist entry at once.
 *   deep = true   all sources in parallel, merged. Three or four requests, but for ONE
 *                 symbol, and only when the user has actually opened that stock's screen.
 *
 * So the extra breadth lands exactly where it is read, and the bulk refresh costs no more
 * than it did before.
 */
object News {

    /** Enough headlines that the cascade stops early on a well-covered stock. */
    private const val ENOUGH = 8

    suspend fun forSymbol(
        symbol: String,
        companyName: String,
        finnhubKey: String,
        deep: Boolean = false
    ): List<NewsItem> = if (deep) deepFetch(symbol, companyName, finnhubKey)
    else cascade(symbol, companyName, finnhubKey)

    /**
     * Cheapest path: stop as soon as there are enough RELEVANT headlines to fill the list.
     *
     * EVERY SOURCE IS FILTERED NOW, NOT JUST GOOGLE NEWS - v6.2, and this reverses a
     * judgement made in v6.1. That round filtered only the unscoped source, on the reasoning
     * that a provider-scoped feed reflects the provider's own editorial decision about what
     * belongs on a symbol's page. TJ then reported his NVDA feed carrying
     *
     *     "Why ASML Holding Stock Bumped 4% Higher Today"
     *     "ChronoScale Says It Plans a 50 MW Microsoft AI Deployment. Can CHRN Fund the Build?"
     *
     * both from a provider-scoped feed, and both plainly not news about NVIDIA. The user is
     * the authority on that, so the filter now runs everywhere.
     *
     * THE OBJECTION TO THIS WAS MEASURED AND DOES NOT HOLD. The worry was that filtering a
     * scoped feed would leave a stock with almost no news - Cisco's Nasdaq feed is largely
     * sector coverage, and only 4 of 15 items survive the filter. But the cascade does not
     * stop there: because only RELEVANT items count toward [ENOUGH], a thin source makes it
     * reach for the next one instead of stopping early. Measured live, 4 Sept 2026:
     *
     *     symbol   Nasdaq kept   Google kept   total relevant
     *     CSCO       4/15          18/20            22
     *     NVDA      10/15          18/20            28
     *     FIVE      12/15          20/20            32
     *     AVGO      11/15          20/20            31
     *
     * Even the worst case ends with 22 relevant headlines against a target of 8. The cost is
     * that a thin symbol now spends a second request, which is why the counting change and
     * the filter had to land together.
     */
    private suspend fun cascade(
        symbol: String,
        companyName: String,
        finnhubKey: String
    ): List<NewsItem> {
        val out = LinkedHashMap<String, NewsItem>()
        // Kept aside so a symbol whose every headline is rejected still shows something
        // rather than an empty news tab - see the note on [keepRelevant]'s safety valve.
        val unfiltered = LinkedHashMap<String, NewsItem>()

        suspend fun stage(fetch: suspend () -> List<NewsItem>) {
            if (out.size >= ENOUGH) return
            val raw = runCatching { fetch() }.getOrDefault(emptyList())
            raw.forEach { unfiltered.putIfAbsent(key(it), it) }
            relevantOnly(raw, symbol, companyName).forEach { out.putIfAbsent(key(it), it) }
        }

        stage { yahooRss(symbol) }
        stage { nasdaq(symbol) }
        stage { googleRss(symbol, companyName) }
        if (finnhubKey.isNotBlank()) stage { finnhub(symbol, finnhubKey) }

        val chosen = if (out.isNotEmpty()) out else unfiltered
        return chosen.values.sortedByDescending { it.published }
    }

    /**
     * Drop headlines that are not about this stock.
     *
     * Note this is the plain filter, NOT [Relevance.keepRelevant] - the never-empty valve is
     * applied once at the end of the whole cascade instead of per source, because a valve per
     * source would let a single junk-only feed re-admit everything it just rejected.
     */
    private fun relevantOnly(
        items: List<NewsItem>,
        symbol: String,
        companyName: String
    ): List<NewsItem> = items.filter {
        Relevance.matches(it.title, it.summary, symbol, companyName)
    }

    /**
     * Everything at once, for the stock the user is looking at. Sources are merged in a
     * fixed order so the de-duplicator keeps the copy from the most useful publisher when
     * two of them carry the same story.
     */
    private suspend fun deepFetch(
        symbol: String,
        companyName: String,
        finnhubKey: String
    ): List<NewsItem> = coroutineScope {
        val jobs = listOf(
            async { runCatching { yahooRss(symbol) }.getOrDefault(emptyList()) },
            async { runCatching { nasdaq(symbol) }.getOrDefault(emptyList()) },
            async { runCatching { googleRss(symbol, companyName) }.getOrDefault(emptyList()) }
        ) + if (finnhubKey.isBlank()) emptyList() else listOf(
            async { runCatching { finnhub(symbol, finnhubKey) }.getOrDefault(emptyList()) }
        )
        // Same relevance rule as the cascade - a detail screen must not show a stock's news
        // tab full of other companies either, which is exactly what TJ reported on NVDA.
        // The valve is applied to the merged set, not per source, for the reason in
        // [relevantOnly].
        val all = jobs.awaitAll().flatten()
        val out = LinkedHashMap<String, NewsItem>()
        relevantOnly(all, symbol, companyName).forEach { out.putIfAbsent(key(it), it) }
        if (out.isEmpty()) all.forEach { out.putIfAbsent(key(it), it) }
        out.values.sortedByDescending { it.published }
    }

    /**
     * Two headlines are the same story when their titles agree.
     *
     * Compared on a normalised title rather than the raw one: the same wire story reaches
     * Yahoo, Nasdaq and Google News with different punctuation, smart quotes, and a
     * " - Publisher" suffix bolted on by Google. Without normalising, running three sources
     * at once would show the reader the same headline three times.
     */
    private fun key(n: NewsItem): String = normaliseTitle(n.title)

    /**
     * The same identity the merge above uses, exposed so callers that combine two lists of
     * headlines de-duplicate them exactly the way this object does. If they disagreed, a
     * story could survive in one list and not the other and the reader would see it twice.
     */
    fun dedupeKey(n: NewsItem): String = key(n)

    /**
     * Separators publishers use to bolt their own name onto the end of a headline. Google
     * News uses " - ", Yahoo and MarketWatch often use " | ", and wire copy turns up with
     * em and en dashes.
     *
     * The suffix has to come off BEFORE punctuation is stripped, or the outlet's name
     * survives as bare words and the same story from two sources keys differently - which
     * is exactly what a test across four real title variants caught.
     */
    private val SUFFIX_SEPARATORS = listOf(" - ", " | ", " — ", " – ", " :: ")

    /**
     * COMPILED ONCE. These were `Regex(...)` literals inside the function bodies below, so a
     * fresh `Pattern.compile` ran on every single call - and [normaliseTitle] is the hottest
     * string operation in the app: the feed de-duplicates its whole list on every repaint,
     * and an incremental refresh repaints once per symbol. Benchmarked against the shipped
     * release bytecode at 400 items x 20 repaints - one ordinary refresh - it was **54.6 ms
     * of main-thread work**, on a desktop JVM; a phone is several times slower. Hoisting them
     * is a two-line change that removes nearly all of it.
     */
    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val WHITESPACE = Regex("\\s+")
    private val HTML_TAG = Regex("<[^>]*>")

    private fun normaliseTitle(t: String): String {
        var s = t.lowercase()
        // Only cut well into the string: "AAPL - Reuters" is nearly all publisher, and
        // trimming it would leave a key so short it would collide with anything.
        val cut = SUFFIX_SEPARATORS.maxOf { s.lastIndexOf(it) }
        if (cut > 20) s = s.substring(0, cut)
        return s.replace(NON_ALNUM, "")
            .replace(WHITESPACE, " ")
            .trim()
            .take(70)
    }

    /**
     * MARKET-WIDE headlines, not scoped to any one ticker: what is moving traded companies
     * generally. The Feed's "All" tab used to be nothing but the user's own symbols, which
     * made it a duplicate of "My stocks" with the watchlist added.
     *
     * ============================================================================
     * THE SOURCE LIST WAS REBUILT THIS ROUND, AND THE OLD ONE IS WHY.
     * ============================================================================
     *
     * TJ reported the All tab "shows a lot of news unrelated to the stock market". It was two
     * of these seven feeds doing nearly all of it, and both are now gone:
     *
     *   `mw_topstories` was MarketWatch's FRONT PAGE, not its markets desk. Pulled live on
     *   5 Sept 2026, 9 of its 10 items were personal-finance and lifestyle columns - the
     *   Moneyist letter about moving to California, the Gen Z homebuying piece, plastic
     *   surgery, job-recruiter scams. Every headline in TJ's screenshot came from this one
     *   feed. Replaced by `mw_bulletins`, MarketWatch's markets bulletin, which on the same
     *   pull was Fed/jobs/S&P coverage and scored 7 of 10 past the filter.
     *
     *   Google News's BUSINESS TOPIC feed is general business news, and it is enormous: 61
     *   items and 204 KB on that pull, of which 32 survived the filter - "American Airlines
     *   passenger duct-taped to seat", "Labor Day sales are here", a Porsche review. Replaced
     *   by a targeted markets query, which returned 100 items in 89 KB and scored 92.
     *
     * Two feeds were ADDED, both verified same-day, free and keyless:
     *
     *   Yahoo Finance's newsroom index - 49 items, essentially all market copy.
     *   Seeking Alpha's market currents - small, dense, and the best sector wrap of the set.
     *
     * NOT ADDED, and worth writing down so nobody spends the afternoon rediscovering it:
     * CNBC's markets and earnings RSS both answer 403 to any non-browser client, and
     * MarketWatch's `mw_marketpulse` and `mw_realtimeheadlines` are still valid RSS that
     * have published nothing since 2024/2025 - the same rot documented at the top of this
     * file, re-verified this round.
     *
     * All seven are fetched at once and cost nothing on the clock because they run alongside
     * the per-symbol fetches. A source that goes away just returns nothing and the rest of
     * the feed carries on.
     *
     * The `strict` flag on each source is [MarketRelevance]'s tier - see the note on
     * [MarketRelevance.isMarketNews]. A finance desk gets the advice-column filter only; a
     * broad source additionally has to prove it is talking about markets.
     */
    suspend fun market(): List<NewsItem> = coroutineScope {
        // (url, label, strict) - strict = this source publishes non-market copy too
        val sources = listOf(
            // ---- finance desks: everything they carry is market copy by construction
            Triple("https://finance.yahoo.com/news/rssindex", "Yahoo Finance", false),
            Triple("https://seekingalpha.com/market_currents.xml", "Seeking Alpha", false),
            Triple("https://www.investing.com/rss/news_25.rss", "Investing.com", false),
            // the events that actually move a share price - the query is already the filter
            Triple(
                "https://news.google.com/rss/search?q=" + MarketData.enc(
                    "stock when:2d (earnings OR guidance OR \"quarterly results\")"
                ) + "&hl=en-US&gl=US&ceid=US:en", "Google News", false
            ),
            Triple(
                "https://news.google.com/rss/search?q=" + MarketData.enc(
                    "shares when:2d (acquisition OR merger OR upgraded OR downgraded OR " +
                        "\"price target\" OR recall OR lawsuit OR \"FDA approval\")"
                ) + "&hl=en-US&gl=US&ceid=US:en", "Google News", false
            ),
            // ---- broad sources: filtered on market vocabulary as well
            Triple(
                "https://feeds.content.dowjones.io/public/rss/mw_bulletins",
                "MarketWatch", true
            ),
            Triple(
                "https://news.google.com/rss/search?q=" + MarketData.enc(
                    "when:1d (\"stock market\" OR \"Wall Street\" OR \"S&P 500\" OR Nasdaq " +
                        "OR \"Dow Jones\" OR \"Federal Reserve\")"
                ) + "&hl=en-US&gl=US&ceid=US:en", "Google News", true
            )
        )

        val out = LinkedHashMap<String, NewsItem>()
        sources.map { (url, label, strict) ->
            async { keepMarket(rss(url, "", label), strict) }
        }.awaitAll().flatten()
            .forEach { out.putIfAbsent(key(it), it) }
        out.values.sortedByDescending { it.published }
    }

    /**
     * Applied PER SOURCE, before the merge, so each feed is judged at its own tier. Once
     * everything is in one list the tier is gone, and it is the tier that makes the filter
     * safe to apply at all.
     *
     * There is no never-empty valve here, unlike [Relevance.keepRelevant]. That valve exists
     * so a single stock's news tab cannot go blank; the All tab merges seven sources and
     * cannot go blank because one of them had a quiet hour. Adding a valve here would mean a
     * feed that happened to publish nothing but advice columns re-admitted all of them -
     * which is the exact bug being fixed.
     */
    private fun keepMarket(items: List<NewsItem>, strict: Boolean): List<NewsItem> =
        items.filter {
            it.title.isNotBlank() &&
                MarketRelevance.isMarketNews(it.title, it.summary, strict)
        }

    // ------------------------------------------------------------- per source

    private suspend fun yahooRss(symbol: String): List<NewsItem> = rss(
        "https://feeds.finance.yahoo.com/rss/2.0/headline?s=" +
            MarketData.enc(symbol) + "&region=US&lang=en-US",
        symbol, "Yahoo Finance"
    )

    /**
     * Nasdaq's per-symbol feed. Verified current to the hour on 4 Sept 2026, and it carries
     * wire coverage (Zacks, Motley Fool, Barchart) that Yahoo's own feed often misses.
     * Nasdaq's market-wide feed on the same host was weeks stale and is deliberately not used.
     */
    private suspend fun nasdaq(symbol: String): List<NewsItem> = rss(
        "https://www.nasdaq.com/feed/rssoutbound?symbol=" + MarketData.enc(symbol.uppercase()),
        symbol, "Nasdaq"
    )

    /**
     * Google News is the ONLY unscoped source in the chain - Yahoo, Nasdaq and Finnhub are all
     * addressed by ticker and answer with that company's own coverage. So this is where the
     * FIVE bug lived, and where the query has to be right.
     *
     * WHAT WAS WRONG. The query was `"$companyName" OR $symbol stock`, i.e. for Five Below
     *
     *     "Five Below, Inc." OR FIVE stock
     *
     * which reads as an OR of two alternatives but is not evaluated that way: Google matched
     * documents containing "five" and "stock", and returned a page dominated by the
     * "<COMPANY> Stock Looks ... On Its N% Five Year Run" template. Measured live on
     * 4 Sept 2026: 15 of 31 headlines were about Five Below. Repeating the identical request
     * two minutes later returned ZERO items - the query was unstable as well as wrong.
     *
     * WHAT IT IS NOW. Five variants were fetched from the live feed and scored, 40 items each:
     *
     *     "Five Below, Inc." OR FIVE stock   (old)   unstable, ~48% at best
     *     "Five Below"                                95%
     *     "Five Below" OR "NASDAQ: FIVE"              95%
     *     "Five Below" OR "(FIVE)"                    17%  Google strips parens -> bare "five"
     *     "Five Below" stock                          97%  <- this one
     *
     * The quoted phrase is what scopes it; the bare word `stock` biases the remainder towards
     * market coverage without widening the match. Note particularly that adding the ticker as
     * an alternative made things WORSE, not better - do not put it back.
     *
     * With no company name there is nothing to quote, so the ticker is all there is. That
     * query cannot be made safe on its own, which is why [Relevance] filters the result.
     */
    private suspend fun googleRss(symbol: String, companyName: String): List<NewsItem> {
        val q = if (companyName.isNotBlank()) "\"$companyName\" stock"
        else "\"$symbol\" stock market"
        val items = rss(
            "https://news.google.com/rss/search?q=" + MarketData.enc(q) +
                "&hl=en-US&gl=US&ceid=US:en",
            symbol, "Google News"
        )
        // Returned RAW. Filtering now happens once, for every source alike, in [cascade] and
        // [deepFetch] - and crucially the never-empty VALVE is applied to the merged result
        // rather than here. A valve per source would let a Google page that is entirely junk
        // re-admit all of its own junk, which is precisely the bug being fixed.
        return items
    }

    /** One place that fetches and parses a feed, so every source gets the same treatment. */
    private suspend fun rss(url: String, symbol: String, label: String): List<NewsItem> {
        // conditionalKey: these exact URLs are re-fetched every few minutes, so an unchanged
        // feed comes back as a bodyless 304 instead of the same few hundred KB again.
        val r = Http.get(url, mapOf("Accept" to "application/rss+xml,application/xml,text/xml"),
            conditionalKey = true)
        if (!r.ok) return emptyList()
        return parseRss(symbol, r.body, label)
    }

    private suspend fun finnhub(symbol: String, key: String): List<NewsItem> {
        val to = Fmt.iso(System.currentTimeMillis())
        val from = Fmt.iso(System.currentTimeMillis() - 21L * 86_400_000L)
        val r = Http.get(
            "https://finnhub.io/api/v1/company-news?symbol=" + MarketData.enc(symbol) +
                "&from=$from&to=$to&token=" + MarketData.enc(key)
        )
        if (!r.ok) return emptyList()
        return try {
            val arr = JSONArray(r.body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("headline")
                if (t.isBlank()) null else NewsItem(
                    symbol = symbol.uppercase(),
                    title = t,
                    url = o.optString("url"),
                    source = o.optString("source", "Finnhub"),
                    published = o.optLong("datetime", 0L) * 1000L,
                    summary = o.optString("summary")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseRss(symbol: String, xml: String, sourceLabel: String): List<NewsItem> {
        val items = ArrayList<NewsItem>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml))
            var inItem = false
            var title = ""; var link = ""; var pub = ""; var desc = ""; var src = ""
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                        "item", "entry" -> {
                            inItem = true
                            title = ""; link = ""; pub = ""; desc = ""; src = ""
                        }
                        "title" -> if (inItem) title = p.nextText().trim()
                        "link" -> if (inItem) {
                            // RSS puts the URL in the element text; Atom puts it in href.
                            val href = p.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) {
                                if (link.isBlank()) link = href.trim()
                            } else {
                                val t = runCatching { p.nextText().trim() }.getOrDefault("")
                                if (t.isNotBlank() && link.isBlank()) link = t
                            }
                        }
                        "pubdate", "published", "updated" ->
                            if (inItem && pub.isBlank()) pub = p.nextText().trim()
                        "description", "summary" ->
                            if (inItem && desc.isBlank()) desc = p.nextText().trim()
                        "source" -> if (inItem) src = p.nextText().trim()
                    }
                    XmlPullParser.END_TAG ->
                        if ((p.name.equals("item", true) || p.name.equals("entry", true)) && inItem) {
                            inItem = false
                            if (title.isNotBlank()) {
                                items.add(
                                    NewsItem(
                                        symbol = symbol.uppercase(),
                                        title = stripHtml(title),
                                        url = link,
                                        source = src.ifBlank { sourceLabel },
                                        published = Fmt.parseRss(pub),
                                        summary = stripHtml(desc).take(400)
                                    )
                                )
                            }
                        }
                }
                event = p.next()
            }
        } catch (e: Exception) { /* return whatever parsed */ }
        return items
    }

    /** `&#8217;` and `&#x2019;` - the shapes a publisher's own CMS emits for curly quotes. */
    private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

    /**
     * Named entities that are HTML but NOT XML, so an XML parser leaves them as literal text.
     * Only the handful finance copy actually uses - curly quotes, dashes, ellipsis.
     */
    private val NAMED_ENTITIES = mapOf(
        "&nbsp;" to " ", "&rsquo;" to "’", "&lsquo;" to "‘",
        "&rdquo;" to "”", "&ldquo;" to "“", "&mdash;" to "—",
        "&ndash;" to "–", "&hellip;" to "…", "&apos;" to "'",
        "&middot;" to "·", "&bull;" to "•", "&trade;" to "™",
        "&reg;" to "®", "&deg;" to "°", "&euro;" to "€",
        "&pound;" to "£", "&eacute;" to "é"
    )

    /**
     * Strip markup and decode the entities that survive XML parsing.
     *
     * The pull parser resolves the five XML entities and numeric references itself, but a
     * feed that DOUBLE-escapes (`&amp;#8217;`) hands back the literal text `&#8217;`, and
     * HTML-only names like `&rsquo;` and `&mdash;` are not XML entities at all - so both used
     * to reach the screen as raw source in the middle of a headline. `&amp;` is expanded
     * first, then the references, so a double-escaped one resolves in a single pass.
     */
    private fun stripHtml(s: String): String {
        var out = s.replace(HTML_TAG, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
        for ((k, v) in NAMED_ENTITIES) out = out.replace(k, v, ignoreCase = true)
        out = NUMERIC_ENTITY.replace(out) { m ->
            val raw = m.groupValues[1]
            val code = runCatching {
                if (raw.startsWith("x", true)) raw.substring(1).toInt(16) else raw.toInt()
            }.getOrNull()
            // an out-of-range or unparseable reference is left exactly as it was found
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code))
            else m.value
        }
        return out.replace(WHITESPACE, " ").trim()
    }
}
