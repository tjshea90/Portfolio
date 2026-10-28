package com.tj.portfolio.ui

import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FeedItem
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.net.GoogleNewsLink
import com.tj.portfolio.net.Http
import com.tj.portfolio.net.OfflineBudget
import com.tj.portfolio.net.OfflinePages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * THE OFFLINE REFRESH (2026-10-27): one pull-to-refresh, done with signal, fills the app so it
 * can be read with none.
 *
 * Tj, verbatim: "when I do have internet access, when I pull down to refresh, make it so the app
 * downloads a lot of pertinent and up to date information about each of my holdings ... update
 * the entire watch list and research sections except the day trading section, the live feed
 * section ... around 50mb ... always prioritize the current prices and graphs of my holdings,
 * then dividends and earnings ..., then news ..., then analysis ..., then my watch list, then the
 * research section, and finally as much of the live feed as possible."
 *
 * HOW IT WORKS. Nothing here fetches data itself except article pages: every screen already has
 * a loader that fetches, parses and writes through to SQLite (so it survives closing the app and
 * restarting the tablet). This class only runs those loaders — through [Host] — IN PRIORITY
 * ORDER, so a connection that dies half-way has already delivered the most valuable tiers. Then
 * it saves readable copies of the stories ([OfflinePages]), purges what this pass superseded, and
 * trims the whole cache back to [OfflineBudget.BUDGET_BYTES], lowest tier first.
 *
 * LIGHT mode runs only phase 1 (quotes + a few charts) and headlines: no dividends, no analysis,
 * no research rebuild, and never a full page.
 */
class OfflineSync(
    private val db: Db,
    private val host: Host,
    private val scope: () -> CoroutineScope,
    private val onFinished: () -> Unit = {}
) {
    enum class Mode { FULL, LIGHT }

    data class Sym(val symbol: String, val name: String = "")

    /** What the ViewModel does for us. Every method returns when its work has finished. */
    interface Host {
        val lowRam: Boolean
        fun online(): Boolean
        fun holdings(): List<Sym>
        fun watchlist(): List<Sym>
        /** Batched quotes for everything tracked, plus the home-row sparklines. True if prices arrived. */
        suspend fun quotes(): Boolean
        suspend fun chart(symbol: String, rangeKey: String)
        suspend fun news(symbol: String, name: String, deep: Boolean)
        suspend fun analysisPieces(symbol: String, name: String)
        suspend fun fundamentals(symbol: String)
        suspend fun dividends(held: List<String>)
        suspend fun earnings()
        suspend fun research()
        /** Symbols of the top research picks (stocks and ETFs), best first. */
        fun researchPicks(limit: Int): List<Sym>
        suspend fun feed(includeInsider: Boolean)
    }

    data class Progress(
        val running: Boolean = false,
        val mode: Mode = Mode.FULL,
        val phase: String = "",
        val step: Int = 0,
        val steps: Int = 0,
        val pagesSaved: Int = 0,
        val lastFinishedAt: Long = 0L,
        val lastMode: Mode? = null,
        val lastSummary: String = "",
        val cacheBytes: Long = 0L
    )

    private val _progress = MutableStateFlow(Progress(cacheBytes = 0L))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile private var running = false

    /** Start a pass unless one is already running. Returns false when it declined. */
    fun start(mode: Mode): Boolean {
        if (running) return false
        if (!host.online()) {
            _progress.value = _progress.value.copy(
                lastSummary = "No connection - showing what is already saved on this device."
            )
            return false
        }
        running = true
        // ATOMIC: if the foreground scope was cancelled between the flag and here, the body must
        // still start so its `finally` clears the flag - or sync would stay "running" forever.
        scope().launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            try { run(mode) } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { running = false; runCatching(onFinished) }
        }
        return true
    }

    fun refreshCacheSize() {
        scope().launch(Dispatchers.IO) {
            val n = runCatching { db.offlineCacheBytes() }.getOrDefault(0L)
            _progress.value = _progress.value.copy(cacheBytes = n)
        }
    }

    private suspend fun run(mode: Mode) {
        val runId = System.currentTimeMillis()
        val conc = if (host.lowRam) 2 else 3
        val gate = Semaphore(conc)
        val held = host.holdings().distinctBy { it.symbol.uppercase() }
        val heldSet = held.map { it.symbol.uppercase() }.toSet()
        val watch = host.watchlist().distinctBy { it.symbol.uppercase() }.filter { it.symbol.uppercase() !in heldSet }
        val full = mode == Mode.FULL
        val totalSteps = if (full) 8 else 3
        var step = 0
        var pages = 0
        val completedTiers = LinkedHashSet<Int>()
        var priceOk = false

        fun phase(name: String) {
            step++
            _progress.value = _progress.value.copy(
                running = true, mode = mode, phase = name, step = step, steps = totalSteps, pagesSaved = pages
            )
        }

        suspend fun <T> each(items: List<T>, body: suspend (T) -> Unit) = coroutineScope {
            items.map { item -> async { gate.withPermit { try { body(item) } catch (e: CancellationException) { throw e } catch (_: Exception) { } } } }.awaitAll()
        }

        _progress.value = _progress.value.copy(running = true, mode = mode, phase = "Starting", step = 0, steps = totalSteps, pagesSaved = 0)
        try {
            // ---------------------------------------------------- 1. prices + charts (tier 1)
            phase("Prices and charts")
            priceOk = runCatching { host.quotes() }.getOrDefault(false)
            val chartRanges = if (full) listOf("D1", "D5", "M1", "M6", "Y1", "Y5", "MAX") else listOf("D1", "M1", "Y1")
            each(held) { s -> chartRanges.forEach { r -> host.chart(s.symbol, r) } }

            if (full) {
                // ------------------------------------------------ 2. dividends + earnings (tier 2)
                phase("Dividends and earnings")
                runCatching { host.dividends(held.map { it.symbol }) }
                runCatching { host.earnings() }

                // ------------------------------------------------ 3. news of holdings (tier 3)
                phase("News on your holdings")
                each(held) { s -> host.news(s.symbol, s.name, deep = true) }
                pages += savePages(runId, 3, heldUrls(held, perSymbol = 8), gate)
                completedTiers += 3

                // ------------------------------------------------ 4. analysis of holdings (tier 4)
                phase("Analyst research and opinion")
                each(held) { s ->
                    host.fundamentals(s.symbol)
                    host.analysisPieces(s.symbol, s.name)
                }
                pages += savePages(runId, 4, analysisUrls(held, perSymbol = 5), gate)
                completedTiers += 4

                // ------------------------------------------------ 5. watch list (tier 5)
                phase("Watch list")
                each(watch) { s ->
                    chartRanges.forEach { r -> host.chart(s.symbol, r) }
                    host.news(s.symbol, s.name, deep = false)
                    host.fundamentals(s.symbol)
                }
                pages += savePages(runId, 5, heldUrls(watch, perSymbol = 3), gate)
                completedTiers += 5

                // ------------------------------------------------ 6. research (tier 6)
                phase("Research")
                runCatching { host.research() }
                val picks = host.researchPicks(10)
                    .filter { it.symbol.uppercase() !in heldSet }
                each(picks) { s -> host.news(s.symbol, s.name, deep = false) }
                pages += savePages(runId, 6, heldUrls(picks, perSymbol = 2), gate)
                completedTiers += 6
            }

            // -------------------------------------------------------- headlines (+ feed in full)
            phase(if (full) "Live feed" else "Headlines")
            if (!full) each(held) { s -> host.news(s.symbol, s.name, deep = false) }
            runCatching { host.feed(includeInsider = full) }
            if (full) {
                val feedUrls = withContext(Dispatchers.IO) {
                    db.recentStories(FeedItem.NEWS, 80, notSymbols = heldSet + watch.map { it.symbol.uppercase() })
                        .plus(db.recentStories(FeedItem.MARKET, 60))
                }
                pages += savePages(runId, 7, feedUrls, gate)
                completedTiers += 7
            }

            // ------------------------------------------------------------ housekeeping
            phase("Tidying up")
            withContext(Dispatchers.IO) {
                if (priceOk && full) {
                    // Only a pass that really reached the network may declare anything stale.
                    db.purgeUnseenOfflinePages(completedTiers, runId)
                    db.purgeOldNews(System.currentTimeMillis() - 16L * 86_400_000L)
                    db.purgeChartsExcept((held + watch).map { it.symbol })
                }
                db.purgeOldOfflinePages(21L * 86_400_000L)
                db.purgeHttpCache()
                db.enforceOfflineBudget()
            }
            val bytes = withContext(Dispatchers.IO) { runCatching { db.offlineCacheBytes() }.getOrDefault(0L) }
            _progress.value = _progress.value.copy(
                running = false, phase = "", lastFinishedAt = System.currentTimeMillis(), lastMode = mode,
                cacheBytes = bytes, pagesSaved = pages,
                lastSummary = (if (priceOk) "" else "Prices could not be reached - ") +
                    (if (full) "Offline refresh finished: ${held.size} holdings, ${watch.size} watched, $pages pages saved, ${mb(bytes)} stored."
                    else "Light refresh finished: prices, charts and headlines updated.")
            )
        } catch (e: CancellationException) {
            // Leaving the app mid-pass: nothing was purged (that happens only at the end), so
            // everything saved so far stands.
            _progress.value = _progress.value.copy(running = false, phase = "", lastSummary = "Refresh interrupted - what was saved is kept.")
            throw e
        } finally {
            running = false
            if (_progress.value.running) _progress.value = _progress.value.copy(running = false, phase = "")
        }
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)

    /** Story URLs on file for these symbols, newest first. */
    private suspend fun heldUrls(syms: List<Sym>, perSymbol: Int): List<NewsItem> =
        withContext(Dispatchers.IO) {
            syms.flatMap { s ->
                runCatching { db.cachedNewsFor(s.symbol, limit = perSymbol) }.getOrDefault(emptyList())
                    .filter { it.url.isNotBlank() }
            }
        }

    /** The analyst / opinion pieces among a symbol's saved stories (see [isAnalysis]). */
    private suspend fun analysisUrls(syms: List<Sym>, perSymbol: Int): List<NewsItem> =
        withContext(Dispatchers.IO) {
            syms.flatMap { s ->
                runCatching { db.cachedNewsFor(s.symbol, limit = 80) }.getOrDefault(emptyList())
                    .filter { it.url.isNotBlank() && isAnalysis(it.source, it.title) }
                    .take(perSymbol)
            }
        }

    companion object {
        private val ANALYSIS_WORDS = Regex(
            "\\b(analyst|upgrade[sd]?|downgrade[sd]?|price target|rating|outlook|forecast|" +
                "opinion|valuation|buy or sell|should you|why .* (stock|shares)|deep dive|" +
                "overweight|underweight|outperform|initiat\\w+ coverage)\\b", RegexOption.IGNORE_CASE
        )

        /** Is this story an analyst note or opinion piece rather than a plain news item? */
        fun isAnalysis(source: String, title: String): Boolean =
            source.startsWith("Analyst") || source.startsWith("Opinion") || ANALYSIS_WORDS.containsMatchIn(title)
    }

    /**
     * Save readable copies of [urls] for [tier]. A page already held is only re-confirmed (an
     * article does not change), a new one is fetched, stripped and stored if it has real text.
     * Stops when the tier's share of the budget is spent.
     */
    private suspend fun savePages(
        run: Long, tier: Int, urls: List<NewsItem>, gate: Semaphore
    ): Int {
        if (urls.isEmpty()) return 0
        var saved = 0
        val wanted = urls.distinctBy { it.url }.filter { it.url.startsWith("http") }
        val sizes = withContext(Dispatchers.IO) { db.offlinePageBytes() }
        var room = OfflineBudget.roomFor(tier, withContext(Dispatchers.IO) { db.pinnedCacheBytes() },
            sizes + (OfflineBudget.Bucket.HTTP to withContext(Dispatchers.IO) { db.httpCacheStats().second }))
        val lock = Any()
        coroutineScope {
            wanted.map { n ->
                async {
                    gate.withPermit {
                        ensureActive()
                        val have = withContext(Dispatchers.IO) {
                            db.hasOfflinePage(n.url).also { if (it) db.touchOfflinePage(n.url, tier, run) }
                        }
                        if (have) return@withPermit
                        if (synchronized(lock) { room } < 20_000L) return@withPermit
                        // The page is filed under the URL the LIST holds (what a tap opens),
                        // even when it had to be fetched from the publisher's own address.
                        var title = n.title
                        var body: String? = null
                        val real = if (OfflinePages.needsResolving(n.url)) resolveGoogle(n.url) else n.url
                        if (real != null && OfflinePages.worthFetching(real)) {
                            val r = Http.get(real, mapOf("Accept" to "text/html"), timeoutMs = 12_000)
                            if (r.ok && r.body.isNotBlank()) {
                                body = withContext(Dispatchers.Default) { OfflinePages.sanitize(r.body) }
                                if (body != null) title = OfflinePages.titleOf(r.body).ifBlank { n.title }
                            }
                        }
                        // Paywalled / script-only / unresolvable: keep what the feed gave us so the
                        // tap still opens a readable summary offline.
                        if (body == null) body = OfflinePages.card(n.title, n.source, n.summary, n.published)
                        val page = body ?: return@withPermit
                        withContext(Dispatchers.IO) { db.putOfflinePage(n.url, "", tier, title, page, run) }
                        synchronized(lock) { room -= page.length / 3; saved++ }
                        _progress.value = _progress.value.copy(pagesSaved = _progress.value.pagesSaved + 1)
                    }
                }
            }.awaitAll()
        }
        return saved
    }

    /** A Google News redirect link -> the publisher's URL, or null when it cannot be resolved. */
    private suspend fun resolveGoogle(url: String): String? {
        val id = GoogleNewsLink.articleId(url) ?: return null
        GoogleNewsLink.decodeLegacy(id)?.let { return it }
        val page = Http.get("https://news.google.com/rss/articles/$id?hl=en-US&gl=US&ceid=US:en",
            mapOf("Accept" to "text/html", "Cookie" to "CONSENT=YES+cb"), timeoutMs = 10_000)
        if (!page.ok) return null
        val (sg, ts) = GoogleNewsLink.signature(page.body) ?: return null
        val r = Http.postJson(
            GoogleNewsLink.EXCHANGE_URL, GoogleNewsLink.exchangeBody(id, sg, ts),
            mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"), timeoutMs = 10_000
        )
        return if (r.ok) GoogleNewsLink.parseExchange(r.body) else null
    }
}
