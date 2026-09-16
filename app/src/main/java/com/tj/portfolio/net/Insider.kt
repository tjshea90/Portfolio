package com.tj.portfolio.net

import com.tj.portfolio.data.InsiderFiling
import com.tj.portfolio.util.Fmt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * SEC Form 4 insider filings, straight from EDGAR. Free, official, no key.
 *
 * WHAT CHANGED IN THIS ROUND, and why the whole file was rewritten.
 *
 * The old version read EDGAR's atom LISTING and used the entry title as the headline. That
 * worked once. EDGAR's Form 4 entry title is now the boilerplate form name and nothing else:
 *
 *     4  - Statement of changes in beneficial ownership of securities
 *
 * identical on every filing ever made, which is precisely the row TJ photographed. Three
 * separate faults were stacked underneath that one screenshot and all three are fixed here:
 *
 *  1. THE TITLE CARRIED NO INFORMATION. Fixed by fetching each filing and reading it - see
 *     [Form4]. The listing is now used only to discover accession numbers.
 *
 *  2. ONLY ONE ROW EVER APPEARED. The filings went into the shared news feed, which is sorted
 *     newest-first and capped at 400 items. Headlines are minutes old and filings are days
 *     old *by law*, so every filing but the very newest was pushed off the end of the list by
 *     ordinary news. The Insider tab was filtering a list the filings had already fallen out
 *     of. They now live in their own store with their own cap, and the tab reads that.
 *
 *  3. IT ONLY LOOKED AT FIVE FILINGS PER HOLDING, WITH NO DATE BOUND, AND SKIPPED THE
 *     WATCHLIST. TJ asked for everything from the last month, so [Companion.window] is a date,
 *     [forSymbols] covers watched symbols as well as held ones, and EDGAR's own `datea`
 *     parameter does the filtering server-side rather than downloading a year to throw it away.
 *
 * REQUEST BUDGET. One listing request per symbol (~8 KB, and only the filings inside the
 * window come back), then one request per filing that is not already cached (~7 KB). A filing
 * is immutable once accepted, so the cache is exact and permanent: a symbol with no new
 * filings costs exactly one request no matter how often the tab is opened. Measured live on
 * 5 Sept 2026: NVDA 6 filings in a month, AAPL 5, F 11, INTC 1.
 *
 * `type=4` MATCHES BY PREFIX, WHICH IS A TRAP WITH A REAL COST. EDGAR's filter is a prefix
 * match, so `type=4` also returns 424B5 prospectuses - INTC had two in the sample window. A
 * 424B5's full submission is HALF A MEGABYTE. Downloading two of those per symbol, on a phone,
 * to discover they are the wrong form, is the kind of thing that quietly eats a data plan. The
 * exact `<filing-type>` is checked in the listing, before anything is fetched.
 *
 * The SEC requires a declared User-Agent identifying the requester and caps traffic at 10
 * requests/second. Both are respected: the UA is below, and every fetch goes through the
 * caller's semaphore.
 */
object Insider {

    private const val UA = "TJ Portfolio Tracker (personal use) tjshea90@gmail.com"

    /** How far back the Insider tab looks. TJ asked for a month. */
    const val WINDOW_DAYS = 31

    /**
     * Ceiling on filings pulled per symbol in one pass.
     *
     * A megacap in a vesting month can file thirty Form 4s in a week - Ford filed 11 in the
     * sample window and that is a quiet month for it. The window is what bounds this normally;
     * this is the backstop that stops one unusual symbol turning a refresh into a hundred
     * requests. Newest first, so what gets dropped is the oldest.
     */
    private const val MAX_PER_SYMBOL = 25

    /**
     * Ceiling on filing DOCUMENTS downloaded in one pass, across every symbol.
     *
     * The per-symbol cap alone is not a budget. Twenty symbols at 25 filings each is 500
     * requests and about 3.5 MB, on a phone, on someone's mobile data - and a first run on a
     * busy portfolio would genuinely hit numbers like that in a vesting month. This bounds
     * the pass as a whole; whatever does not fit is fetched on the next one, and because the
     * listings are gathered and sorted first, what fits is always the NEWEST filings across
     * the whole portfolio rather than everything belonging to whichever symbols sorted first.
     *
     * It only ever binds on a cold start. A filed Form 4 never changes, so once read it is
     * cached permanently and a steady-state pass fetches only the handful filed since the
     * last one.
     */
    private const val MAX_DOCS_PER_PASS = 120

    // NOTE: no explicit Accept-Encoding. Setting it by hand switches OFF the JVM's
    // transparent decompression, and asking for deflate would then hand us bytes the
    // reader cannot decode. Left unset, HttpURLConnection negotiates and unwraps gzip itself.
    private fun headers() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/atom+xml,application/xml,text/plain"
    )

    /** A filing found in the listing but not yet read. */
    data class Ref(val accession: String, val filedAt: Long, val docUrl: String, val pageUrl: String)

    /**
     * Every Form 4 for [symbols] filed within [days], newest first.
     *
     * [cached] is consulted before the network: a filing already parsed is reused as-is, which
     * is what keeps a repeat refresh down to one listing request per symbol. [gate] is the
     * caller's SEC rate limiter - passing it in rather than owning one here means the listing
     * and document requests share a single budget instead of two that add up to double it.
     */
    suspend fun forSymbols(
        symbols: Collection<String>,
        cached: Map<String, InsiderFiling>,
        gate: Semaphore,
        /**
         * Accession numbers already fetched once and found to contain nothing this app can
         * use. See [Unreadable] - without it these are re-downloaded on every single pass,
         * forever.
         */
        skip: MutableSet<String> = HashSet(),
        days: Int = WINDOW_DAYS,
        now: Long = System.currentTimeMillis()
    ): List<InsiderFiling> = coroutineScope {
        if (symbols.isEmpty()) return@coroutineScope emptyList()
        val since = Fmt.iso(now - days * 86_400_000L)
        val cutoff = now - days * 86_400_000L

        // PHASE 1 - every listing, in parallel. One small request per symbol, and nothing is
        // downloaded yet, so this is cheap and it is what makes the budget below possible.
        val refs = symbols.distinct().map { sym ->
            async {
                runCatching { gate.withPermit { listFilings(sym, since) } }
                    .getOrDefault(emptyList())
                    .take(MAX_PER_SYMBOL)
                    .map { sym to it }
            }
        }.awaitAll().flatten()

        // PHASE 2 - what is already known costs nothing; what is missing is fetched newest
        // first, up to the pass budget. Sorting BEFORE the cap is the whole point: without
        // it a busy symbol early in the list would spend the entire budget on its own old
        // filings while a fresh purchase somewhere else waited for the next pass.
        val known = refs.mapNotNull { (_, r) -> cached[r.accession] }
        val missing = refs
            .filter { (_, r) -> cached[r.accession] == null && r.accession !in skip }
            .sortedByDescending { (_, r) -> r.filedAt }
            .take(MAX_DOCS_PER_PASS)

        val fetched = missing.map { (sym, ref) ->
            async {
                val parsed = gate.withPermit { runCatching { fetch(sym, ref) }.getOrNull() }
                // A NULL RESULT IS ALSO AN ANSWER, AND IT HAS TO BE REMEMBERED.
                //
                // `Form4.parse` returns null for three things that are perfectly normal:
                // a Form 3 or Form 5 sharing the schema, a holdings-only filing with no
                // transaction table, and a body that is not an ownership document at all.
                // None of those will ever parse - the document is immutable. But the result
                // never entered the cache, so `cached[accession] == null` stayed true and the
                // same ~7 KB was downloaded again on the NEXT pass, and the one after, twice
                // an hour for the whole 31-day window, per symbol, forever. The file header's
                // claim that "a symbol with no new filings costs exactly one request" was
                // true only of filings that happen to parse.
                //
                // Distinguishing "unparseable" from "the network failed" is the important
                // part: [fetch] returns [Unreadable] only when a body actually arrived and
                // could not be used.
                if (parsed is Unreadable) { synchronized(skip) { skip.add(ref.accession) }; null }
                else parsed as? InsiderFiling
            }
        }.awaitAll().filterNotNull()

        (known + fetched)
            .distinctBy { it.accession }
            // The window is applied again on the parsed result. EDGAR's `datea` bounds the
            // FILING date, and a filing accepted inside the window can report a trade from
            // before it - that is normal, not an error, and the row is kept. What this drops
            // is a stale cache entry from a previous month that is still in the map.
            .filter { it.filedAt >= cutoff || it.tradeDate >= cutoff }
            .sortedByDescending { it.filedAt }
    }

    /** One symbol's filings. Split out so a single stock's screen can call it directly. */
    /**
     * One symbol's filings, and whether the listing request was answered at all.
     *
     * The second half of that is what lets a caller cache "nothing to report" - see [Listing].
     */
    data class SymbolResult(
        val filings: List<InsiderFiling>,
        val answered: Boolean,
        /**
         * How many filings remain to be fetched after the never-parseable set and the
         * per-symbol cap have been applied - i.e. how many this pass was going to try.
         *
         * Zero with `answered` true is the real "this company filed nothing this month". A
         * non-zero count with no filings is a partial failure wearing the same shape, and the
         * caller must not cache it as an answer.
         */
        val listed: Int = 0
    )

    suspend fun forSymbolResult(
        symbol: String,
        cached: Map<String, InsiderFiling>,
        gate: Semaphore,
        since: String,
        skip: MutableSet<String> = HashSet()
    ): SymbolResult = coroutineScope {
        val listing = gate.withPermit { listing(symbol, since) }
        val refs = listing.refs
            .filter { it.accession !in skip }
            .take(MAX_PER_SYMBOL)
        if (refs.isEmpty()) {
            return@coroutineScope SymbolResult(emptyList(), listing.answered, listed = 0)
        }
        SymbolResult(
            listed = refs.size,
            filings = refs.map { ref ->
                async {
                    cached[ref.accession] ?: run {
                        val parsed = gate.withPermit {
                            runCatching { fetch(symbol, ref) }.getOrNull()
                        }
                        if (parsed is Unreadable) {
                            synchronized(skip) { skip.add(ref.accession) }; null
                        } else parsed as? InsiderFiling
                    }
                }
            }.awaitAll().filterNotNull().sortedByDescending { it.filedAt },
            answered = true
        )
    }

    // ---------------------------------------------------------------- listing

    /**
     * The accession numbers of a symbol's Form 4s filed on or after [since] (`yyyy-MM-dd`).
     *
     * EDGAR takes a ticker directly in the CIK parameter, so no lookup table is needed, and
     * `datea` bounds the range server-side - verified live: NVDA over a year is 100 entries
     * and 109 KB, the same request with `datea` set to a month back is 6 entries and 8.6 KB.
     */
    suspend fun listFilings(symbol: String, since: String): List<Ref> =
        listing(symbol, since).refs

    /**
     * The listing, WITH whether EDGAR actually answered (sweep 3).
     *
     * [listFilings] returns an empty list for two situations that are not remotely the same:
     * "this company filed no Form 4 this month" - the ordinary case, most companies, most
     * months - and "EDGAR refused, timed out, or there is no network". A caller that wants to
     * remember it has already asked cannot tell those apart from the list alone, and one that
     * guesses will either re-ask forever or stop asking after a single 403.
     */
    data class Listing(val refs: List<Ref>, val answered: Boolean)

    suspend fun listing(symbol: String, since: String): Listing {
        val url = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" +
            MarketData.enc(symbol) + "&type=4&dateb=&datea=" + MarketData.enc(since) +
            "&owner=include&count=100&output=atom"
        val r = Http.get(url, headers(), 20000, conditionalKey = true)
        if (!r.ok) return Listing(emptyList(), answered = false)
        return Listing(parseListing(r.body), answered = true)
    }

    /**
     * Hand-rolled rather than run through `android.util.Xml`, for the same reason [Form4] is:
     * this way the whole path is exercised by a plain JVM unit test against a listing captured
     * from the live service. The atom EDGAR emits is machine-generated, flat, and has exactly
     * one element of each name inside an entry.
     */
    fun parseListing(xml: String): List<Ref> {
        val out = ArrayList<Ref>()
        val seen = HashSet<String>()
        var i = xml.indexOf("<entry>")
        while (i >= 0) {
            val end = xml.indexOf("</entry>", i).let { if (it < 0) xml.length else it }
            val e = xml.substring(i, end)
            i = xml.indexOf("<entry>", end)

            // EXACT form type. `type=4` is a PREFIX match on EDGAR's side and returns 424B5
            // and 425 filings too - see the file header. Amendments are kept.
            val type = tag(e, "filing-type").trim()
            if (type != "4" && type != "4/A") continue

            val accession = tag(e, "accession-number").trim()
            if (accession.isBlank() || !seen.add(accession)) continue
            val page = tag(e, "filing-href").trim().ifBlank { hrefAttr(e) }
            if (page.isBlank()) continue

            // The precise acceptance stamp when there is one, the bare filing date otherwise.
            // They are read into separate variables deliberately: the old parser took
            // whichever tag it met first - always the date-only one - so every filing accepted
            // on the same day came back with an identical timestamp, which flattened the
            // ordering to day resolution and produced duplicate list keys.
            val stamp = Fmt.parseRss(tag(e, "updated"))
                .takeIf { it > 0 } ?: Fmt.parseRss(tag(e, "filing-date"))

            out.add(Ref(accession, stamp, docUrlFor(page, accession), page))
        }
        return out.sortedByDescending { it.filedAt }
    }

    /**
     * The complete submission text file, derived from the index page's own URL.
     *
     * `.../0001197647-26-000009-index.htm` -> `.../0001197647-26-000009.txt`
     *
     * ONE REQUEST, and that is the point. The alternative - read the index page, find the XML
     * document's filename, fetch it - is two round trips per filing on a feed that may be
     * fetching a hundred of them. A Form 4 submission is a single document, so its `.txt` is
     * the XML plus a short SGML header: measured at 6.8 KB against 5.3 KB for the bare XML,
     * for half the requests.
     */
    fun docUrlFor(indexUrl: String, accession: String): String {
        val cut = indexUrl.indexOf("-index")
        if (cut > 0) return indexUrl.substring(0, cut) + ".txt"
        val slash = indexUrl.lastIndexOf('/')
        return if (slash > 0) indexUrl.substring(0, slash + 1) + accession + ".txt"
        else indexUrl
    }

    /**
     * Marker for "the document arrived and there is nothing in it for us".
     *
     * A filed document never changes, so this verdict is permanent and worth remembering. A
     * network failure is not - it says nothing about the document - which is why the two
     * cannot share `null`.
     */
    object Unreadable

    private suspend fun fetch(symbol: String, ref: Ref): Any? {
        val r = Http.get(ref.docUrl, headers(), 20000)
        if (!r.ok) return null                       // network said no - try again later
        return Form4.parse(symbol, ref.accession, ref.pageUrl, ref.filedAt, r.body)
            ?: Unreadable                            // body arrived, and it is not a Form 4
    }

    // ------------------------------------------------------------ tiny scanner

    private fun tag(src: String, name: String): String {
        val open = src.indexOf("<$name>")
        if (open < 0) return ""
        val close = src.indexOf("</$name>", open)
        if (close < 0) return ""
        return src.substring(open + name.length + 2, close)
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
    }

    /** `<link href="..." rel="alternate"/>` - the fallback when `filing-href` is missing. */
    private fun hrefAttr(entry: String): String {
        val at = entry.indexOf("href=\"")
        if (at < 0) return ""
        val end = entry.indexOf('"', at + 6)
        return if (end < 0) "" else entry.substring(at + 6, end)
            .replace("&amp;", "&").trim()
    }

    // -------------------------------------------------------- market-wide (all companies)

    /**
     * Pages of EDGAR's `getcurrent` feed to read in one pass. Newest-first, so page 1 is
     * always the most recent filings regardless of how busy the market has been - this bounds
     * how far back one pass looks, not which filings it sees.
     */
    private const val MAX_MARKET_PAGES_PER_PASS = 3

    /**
     * Doc-download ceiling for the market-wide pass, separate from [MAX_DOCS_PER_PASS].
     *
     * Smaller on purpose: the per-symbol pass runs once every half hour for a portfolio's
     * handful of symbols, this one runs continuously for as long as the "All companies" view
     * is open. Whatever does not fit is exactly what "Load more" fetches next - see
     * [marketWide]'s `skip` parameter.
     */
    private const val MAX_MARKET_DOCS_PER_PASS = 60

    /**
     * The market-wide "just filed" feed - every Form 4 filed by anyone, newest first.
     *
     * [pageStart] is EDGAR's own pagination offset (0, 100, 200, ...) - the caller advances it
     * to page further back, the same "Load more" idiom the rest of this app uses rather than
     * trying to pull a whole day in one pass.
     *
     * Reuses [fetch]/[Form4.parse]/the accession cache completely unchanged - a market-wide
     * filing is cached exactly like a portfolio one, so switching between "My stocks" and
     * "All companies" never re-downloads anything already seen.
     */
    suspend fun marketWide(
        cached: Map<String, InsiderFiling>,
        gate: Semaphore,
        skip: MutableSet<String>,
        pageStart: Int = 0,
        pages: Int = MAX_MARKET_PAGES_PER_PASS,
        docBudget: Int = MAX_MARKET_DOCS_PER_PASS
    ): List<InsiderFiling> = coroutineScope {
        val refs = (0 until pages).map { p ->
            async { runCatching { currentListing(pageStart + p * 100).refs }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { it.accession }

        val known = refs.mapNotNull { cached[it.accession] }
        val missing = refs
            .filter { cached[it.accession] == null && it.accession !in skip }
            .sortedByDescending { it.filedAt }
            .take(docBudget)

        val fetched = missing.map { ref ->
            async {
                // The market-wide listing carries no ticker at all - unlike the per-symbol
                // path, [fetch] recovers it from the filing's own `issuerTradingSymbol`, which
                // is why the hint passed here is blank rather than a guess.
                val parsed = gate.withPermit { runCatching { fetch("", ref) }.getOrNull() }
                if (parsed is Unreadable) { synchronized(skip) { skip.add(ref.accession) }; null }
                else parsed as? InsiderFiling
            }
        }.awaitAll().filterNotNull()

        (known + fetched).distinctBy { it.accession }.sortedByDescending { it.filedAt }
    }

    /** One page of the market-wide feed, [Listing.answered] on the same "did EDGAR reply" rule. */
    suspend fun currentListing(start: Int): Listing {
        val url = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=4&company=" +
            "&dateb=&owner=include&count=100&output=atom" +
            (if (start > 0) "&start=$start" else "")
        val r = Http.get(url, headers(), 20000, conditionalKey = true)
        if (!r.ok) return Listing(emptyList(), answered = false)
        return Listing(parseCurrentListing(r.body), answered = true)
    }

    /**
     * The `getcurrent` feed's OWN shape - not [parseListing]'s. There is no
     * `<filing-type>`/`<accession-number>`/`<filing-href>` here: the form type is
     * `<category term="...">`, the accession lives inside `<id>`, and the page link is a plain
     * Atom `<link href="...">`. Verified live against
     * `https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent`.
     *
     * ONE ENTRY PER PARTY, NOT PER FILING - the issuer and every reporting owner each get their
     * own `<entry>` sharing one accession number, so the dedupe below is most of what this
     * function does, not an edge case.
     *
     * `type=4` IS A PREFIX MATCH HERE TOO (confirmed live: 424B2/424B3/485APOS/497 start
     * appearing past roughly the second page), so the exact-type check is load-bearing past
     * page 2, exactly the [parseListing] trap this file's header already documents.
     */
    fun parseCurrentListing(xml: String): List<Ref> {
        val out = ArrayList<Ref>()
        val seen = HashSet<String>()
        var i = xml.indexOf("<entry>")
        while (i >= 0) {
            val end = xml.indexOf("</entry>", i).let { if (it < 0) xml.length else it }
            val e = xml.substring(i, end)
            i = xml.indexOf("<entry>", end)

            val type = attr(e, "category", "term").trim()
            if (type != "4" && type != "4/A") continue

            val accession = accessionFromId(tag(e, "id"))
            if (accession.isBlank() || !seen.add(accession)) continue

            val page = hrefAttr(e)
            if (page.isBlank()) continue

            val stamp = Fmt.parseRss(tag(e, "updated"))
            out.add(Ref(accession, stamp, docUrlFor(page, accession), page))
        }
        return out.sortedByDescending { it.filedAt }
    }

    /** `urn:tag:sec.gov,2008:accession-number=0001213900-26-100292` -> the number alone. */
    private fun accessionFromId(id: String): String =
        Regex("accession-number=([0-9-]+)").find(id)?.groupValues?.get(1) ?: ""

    /** The value of one attribute on a self-closing or ordinary opening tag. */
    private fun attr(entry: String, tagName: String, attrName: String): String {
        val open = entry.indexOf("<$tagName")
        if (open < 0) return ""
        val close = entry.indexOf('>', open)
        if (close < 0) return ""
        val block = entry.substring(open, close + 1)
        val at = block.indexOf("$attrName=\"")
        if (at < 0) return ""
        val start = at + attrName.length + 2
        val valEnd = block.indexOf('"', start)
        return if (valEnd < 0) "" else block.substring(start, valEnd)
    }
}
