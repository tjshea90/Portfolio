package com.tj.portfolio.ui

import com.tj.portfolio.data.Db
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.net.News
import com.tj.portfolio.domain.DivEvent
import com.tj.portfolio.net.DividendFeed
import com.tj.portfolio.net.EarningsFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * THE DIVIDEND AND EARNINGS FEEDS' STATE (2026-10-22), kept out of [PortfolioViewModel] so the
 * 9,000-line class does not grow another fifty properties, and so the rules live in one place:
 *
 *  - DISK FIRST. Everything fetched is written through to the `settings` table (keys
 *    [DIV_HISTORY], [DIV_CALENDAR], [EARN_DATE]; excluded from backups as derived data), and the
 *    first call of a session paints from it before any request is sent. A cold start, a flight,
 *    a rate-limited IP all still show the last known figures.
 *  - TTL-GATED, FORCEABLE. Entering the screen asks again once the TTL has lapsed; a pull to
 *    refresh passes `force` and bypasses it.
 *  - A FAILURE KEEPS WHAT IS HELD. A null from the network never replaces a good value.
 *  - ONE PASS AT A TIME. A second call while a pass is running joins it instead of doubling it.
 *
 * Declared in the ViewModel ABOVE its `init`, like everything else there (tools/checkinit.py).
 */
class IncomeHub(
    private val db: Db,
    /** The CURRENT foreground scope - a lambda because the ViewModel rebuilds it on every ON_START. */
    private val scope: () -> CoroutineScope
) {
    data class DividendData(
        val history: Map<String, List<DivEvent>> = emptyMap(),
        val calendar: Map<String, DividendFeed.Calendar> = emptyMap(),
        /** When the last full pass completed, 0 = none yet this process. */
        val completedAt: Long = 0L
    )

    private val _dividends = MutableStateFlow(DividendData())
    val dividends: StateFlow<DividendData> = _dividends.asStateFlow()

    private val _dividendsLoading = MutableStateFlow(false)
    val dividendsLoading: StateFlow<Boolean> = _dividendsLoading.asStateFlow()

    private val _earnings = MutableStateFlow<Map<String, EarningsFeed.Date>>(emptyMap())
    val earningsDates: StateFlow<Map<String, EarningsFeed.Date>> = _earnings.asStateFlow()

    /** Reports from the last month per symbol (newest first), disk-backed for offline reading. */
    private val _reports = MutableStateFlow<Map<String, List<EarningsFeed.Report>>>(emptyMap())
    val recentReports: StateFlow<Map<String, List<EarningsFeed.Report>>> = _reports.asStateFlow()

    /** The date a symbol's most recent report ran, kept when the calendar moves on to the next one. */
    private val _lastDates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastReportDates: StateFlow<Map<String, Long>> = _lastDates.asStateFlow()

    private val _indices = MutableStateFlow<Map<String, com.tj.portfolio.data.Quote>>(emptyMap())
    val indices: StateFlow<Map<String, com.tj.portfolio.data.Quote>> = _indices.asStateFlow()
    private val _indicesLoading = MutableStateFlow(false)
    val indicesLoading: StateFlow<Boolean> = _indicesLoading.asStateFlow()
    private val indicesRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Paints the saved prices at once, then asks for fresh ones. A failure keeps what is held. */
    fun refreshIndices() {
        if (!indicesRunning.compareAndSet(false, true)) return
        scope().launch(start = CoroutineStart.ATOMIC) {
            _indicesLoading.value = true
            try {
                if (_indices.value.isEmpty()) {
                    val saved = withContext(Dispatchers.IO) { com.tj.portfolio.net.Indices.decode(db.get(com.tj.portfolio.net.Indices.KEY)) }
                    if (saved.isNotEmpty()) _indices.update { if (it.isEmpty()) saved else it }
                }
                val fresh = com.tj.portfolio.net.Indices.fetch()
                if (fresh != null) {
                    val now = System.currentTimeMillis()
                    val merged = _indices.value + fresh.associate { it.symbol to it.copy(updated = now) }
                    _indices.value = merged
                    withContext(Dispatchers.IO) { runCatching { db.set(com.tj.portfolio.net.Indices.KEY, com.tj.portfolio.net.Indices.encode(merged.values)) } }
                }
            } finally {
                _indicesLoading.value = false
                indicesRunning.set(false)
            }
        }
    }

    private val reportFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _earningsLoading = MutableStateFlow(false)
    val earningsLoading: StateFlow<Boolean> = _earningsLoading.asStateFlow()

    private val _headlines = MutableStateFlow<Map<String, List<NewsItem>>>(emptyMap())
    val headlines: StateFlow<Map<String, List<NewsItem>>> = _headlines.asStateFlow()

    private val newsFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val divRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var divQueued: Triple<List<String>, List<String>, Boolean>? = null
    private val earnRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var earnQueued: Pair<List<String>, Boolean>? = null

    private val histFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val calFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val earnFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ============================================================ dividends

    /**
     * @param held symbols currently owned - their history AND calendar are kept fresh.
     * @param others symbols previously owned or with recorded dividends - their history is
     *        fetched only when there is nothing cached at all, since it no longer changes.
     */
    fun refreshDividends(held: List<String>, others: List<String>, force: Boolean) {
        // A call made while a pass is running is not dropped - the held set may have changed
        // under it, or this is the pull the user just made - it runs once the pass ends.
        if (!divRunning.compareAndSet(false, true)) {
            divQueued = Triple(held, others, force || (divQueued?.third == true))
            return
        }
        val heldSyms = held.map { it.uppercase() }.distinct()
        val otherSyms = others.map { it.uppercase() }.distinct() - heldSyms.toSet()
        if (heldSyms.isEmpty() && otherSyms.isEmpty()) { divRunning.set(false); return }
        // ATOMIC: if the foreground scope was cancelled between the flag and here, the body must
        // still start so its `finally` clears the flag - or the hub would stay "running" forever.
        scope().launch(start = CoroutineStart.ATOMIC) {
            _dividendsLoading.value = true
            try {
                paintDividendsFromDisk(heldSyms + otherSyms)
                val now = System.currentTimeMillis()
                coroutineScope {
                    heldSyms.chunked(3).forEach { batch ->
                        batch.map { sym ->
                            async {
                                val needHist = force || now - (histFetchedAt[sym] ?: 0L) >= DividendFeed.HISTORY_TTL_MS
                                val needCal = force || now - (calFetchedAt[sym] ?: 0L) >= DividendFeed.CALENDAR_TTL_MS
                                if (needHist) fetchHistory(sym)
                                if (needCal) fetchCalendar(sym)
                            }
                        }.awaitAll()
                    }
                    otherSyms.filter { _dividends.value.history[it] == null }.chunked(3).forEach { batch ->
                        batch.map { sym -> async { fetchHistory(sym) } }.awaitAll()
                    }
                }
                _dividends.update { it.copy(completedAt = System.currentTimeMillis()) }
            } finally {
                _dividendsLoading.value = false
                divRunning.set(false)
                divQueued?.let { (h, o, f) -> divQueued = null; refreshDividends(h, o, f) }
            }
        }
    }

    private suspend fun paintDividendsFromDisk(symbols: List<String>) {
        val have = _dividends.value
        val missing = symbols.filter { have.history[it] == null || have.calendar[it] == null }
        if (missing.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) {
            missing.map { s ->
                Triple(s,
                    DividendFeed.decodeHistory(db.get(DIV_HISTORY + s)),
                    DividendFeed.decodeCalendar(db.get(DIV_CALENDAR + s)))
            }
        }
        _dividends.update { cur ->
            var h = cur.history; var c = cur.calendar
            for ((s, hist, cal) in loaded) {
                if (hist != null && h[s] == null) {
                    h = h + (s to hist.second)
                    histFetchedAt.putIfAbsent(s, hist.first)
                }
                if (cal != null && c[s] == null) {
                    c = c + (s to cal)
                    calFetchedAt.putIfAbsent(s, cal.fetched)
                }
            }
            cur.copy(history = h, calendar = c)
        }
    }

    private suspend fun fetchHistory(sym: String) {
        val ev = runCatching { DividendFeed.history(sym) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        histFetchedAt[sym] = now
        _dividends.update { it.copy(history = it.history + (sym to ev)) }
        withContext(Dispatchers.IO) { runCatching { db.set(DIV_HISTORY + sym, DividendFeed.encodeHistory(ev, now)) } }
    }

    private suspend fun fetchCalendar(sym: String) {
        val cal = runCatching { DividendFeed.calendar(sym) }.getOrNull() ?: return
        calFetchedAt[sym] = cal.fetched
        _dividends.update { it.copy(calendar = it.calendar + (sym to cal)) }
        withContext(Dispatchers.IO) { runCatching { db.set(DIV_CALENDAR + sym, DividendFeed.encodeCalendar(cal)) } }
    }

    // ============================================================ earnings dates

    fun refreshEarningsDates(symbols: List<String>, force: Boolean) {
        if (!earnRunning.compareAndSet(false, true)) {
            earnQueued = ((earnQueued?.first.orEmpty() + symbols).distinct()) to (force || earnQueued?.second == true)
            return
        }
        val syms = symbols.map { it.uppercase() }.distinct()
        if (syms.isEmpty()) { earnRunning.set(false); return }
        scope().launch(start = CoroutineStart.ATOMIC) {
            _earningsLoading.value = true
            try {
                val missing = syms.filter { _earnings.value[it] == null }
                if (missing.isNotEmpty()) {
                    val loaded = withContext(Dispatchers.IO) {
                        missing.mapNotNull { s -> EarningsFeed.decode(db.get(EARN_DATE + s))?.let { s to it } }
                    }
                    _earnings.update { cur ->
                        var m = cur
                        for ((s, d) in loaded) if (m[s] == null) { m = m + (s to d); earnFetchedAt.putIfAbsent(s, d.fetched) }
                        m
                    }
                }
                paintReportsFromDisk(syms)
                val now = System.currentTimeMillis()
                val dueReports = syms.filter { force || now - (reportFetchedAt[it] ?: 0L) >= EarningsFeed.REPORT_TTL_MS }
                val due = syms.filter { force || now - (earnFetchedAt[it] ?: 0L) >= EarningsFeed.TTL_MS }
                coroutineScope {
                    due.chunked(3).forEach { batch ->
                        batch.map { sym ->
                            async {
                                if (sym in dueReports) fetchReports(sym)
                                val d = runCatching { EarningsFeed.calendar(sym) }.getOrNull()
                                if (d != null) {
                                    noteLastDate(sym, _earnings.value[sym], d)
                                    earnFetchedAt[sym] = d.fetched
                                    _earnings.update { it + (sym to d) }
                                    withContext(Dispatchers.IO) { runCatching { db.set(EARN_DATE + sym, EarningsFeed.encode(d)) } }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                _earningsLoading.value = false
                earnRunning.set(false)
                earnQueued?.let { (s, f) -> earnQueued = null; refreshEarningsDates(s, f) }
            }
        }
    }

    private suspend fun paintReportsFromDisk(syms: List<String>) {
        val missing = syms.filter { _reports.value[it] == null }
        if (missing.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) {
            missing.map { s -> Triple(s, EarningsFeed.decodeReports(db.get(EARN_REPORTS + s).ifBlank { null }), db.get(EARN_LAST + s).toLongOrNull()) }
        }
        _reports.update { cur ->
            var m = cur
            for ((s, r, _) in loaded) if (r != null && m[s] == null) { m = m + (s to r); reportFetchedAt.putIfAbsent(s, r.maxOfOrNull { it.fetched } ?: 0L) }
            m
        }
        _lastDates.update { cur -> cur + loaded.mapNotNull { (s, _, l) -> l?.let { s to it } }.filter { (s, _) -> cur[s] == null } }
    }

    private suspend fun fetchReports(sym: String) {
        val now = System.currentTimeMillis()
        val list = runCatching { EarningsFeed.recent(sym, now) }.getOrNull() ?: return
        reportFetchedAt[sym] = now
        val cutoff = now - 45L * 86_400_000L
        val keep = list.filter { it.dateMs >= cutoff }
        _reports.update { it + (sym to keep) }
        withContext(Dispatchers.IO) { runCatching { db.set(EARN_REPORTS + sym, EarningsFeed.encodeReports(keep)) } }
    }

    /** When the calendar rolls from a report that just ran to the next one, remember the one that ran. */
    private suspend fun noteLastDate(sym: String, old: EarningsFeed.Date?, new: EarningsFeed.Date) {
        val o = old ?: return
        val now = System.currentTimeMillis()
        if (o.startMs < new.startMs && o.startMs < now && o.startMs >= now - 45L * 86_400_000L) {
            _lastDates.update { it + (sym to o.startMs) }
            withContext(Dispatchers.IO) { runCatching { db.set(EARN_LAST + sym, o.startMs.toString()) } }
        }
    }

    // ============================================================ headlines for the outlook

    /**
     * Recent headlines per symbol, for the news-tone part of the outlook. Disk first (the Feed
     * and detail screens already store what they fetch), then the cheap source cascade - which
     * stops as soon as it has enough relevant stories - only when nothing under [NEWS_TTL_MS]
     * old is held. In memory only: the stories themselves are already persisted by their owners.
     */
    fun refreshHeadlines(symbols: List<Pair<String, String>>, finnhubKey: String, force: Boolean) {
        val wanted = symbols.distinctBy { it.first.uppercase() }
        if (wanted.isEmpty()) return
        scope().launch {
            val missing = wanted.filter { _headlines.value[it.first.uppercase()] == null }
            if (missing.isNotEmpty()) {
                val loaded = withContext(Dispatchers.IO) {
                    missing.map { (s, _) -> s.uppercase() to runCatching { db.cachedNewsFor(s) }.getOrDefault(emptyList()) }
                }
                _headlines.update { cur -> cur + loaded.filter { (s, v) -> v.isNotEmpty() && cur[s] == null } }
            }
            val now = System.currentTimeMillis()
            coroutineScope {
                wanted.filter { (s, _) ->
                    val sym = s.uppercase()
                    val newest = _headlines.value[sym]?.maxOfOrNull { it.published } ?: 0L
                    force || (now - (newsFetchedAt[sym] ?: 0L) >= NEWS_TTL_MS && now - newest >= NEWS_TTL_MS)
                }.chunked(3).forEach { batch ->
                    batch.map { (s, name) ->
                        async {
                            val sym = s.uppercase()
                            val items = runCatching { News.forSymbol(sym, name, finnhubKey, deep = false) }
                                .getOrDefault(emptyList())
                            newsFetchedAt[sym] = System.currentTimeMillis()
                            if (items.isNotEmpty()) {
                                _headlines.update { cur ->
                                    val merged = (items + cur[sym].orEmpty()).distinctBy { News.dedupeKey(it) }
                                        .sortedByDescending { it.published }.take(40)
                                    cur + (sym to merged)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    companion object {
        /**
         * The holdings of a fund big enough to move it: at least 2% each and at most five.
         * The Earnings screen follows these reports for a fund, since it has none of its own.
         */
        fun topHoldings(h: com.tj.portfolio.data.FundHoldings?): List<com.tj.portfolio.data.FundHolding> =
            h?.holdings.orEmpty()
                .filter { it.weight >= 0.02 && it.symbol.isNotBlank() }
                .sortedByDescending { it.weight }.take(5)

        /** Headlines this fresh are not fetched again on entering the screen (pull ignores it). */
        const val NEWS_TTL_MS = 3 * 3_600_000L

        /** Settings-key prefixes. Excluded from backups (`Db.isDerivedCache`): all re-fetchable. */
        const val DIV_HISTORY = "divhist:"
        const val DIV_CALENDAR = "divcal:"
        const val EARN_DATE = "earndate:"
        const val EARN_REPORTS = "earnrep:"
        const val EARN_LAST = "earnlast:"
        val PREFIXES = listOf(DIV_HISTORY, DIV_CALENDAR, EARN_DATE, EARN_REPORTS, EARN_LAST, com.tj.portfolio.net.Indices.KEY)
    }
}
