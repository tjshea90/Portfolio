package com.tj.portfolio.ui

import com.tj.portfolio.data.Db
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.net.News
import com.tj.portfolio.domain.DivEvent
import com.tj.portfolio.net.DividendFeed
import com.tj.portfolio.net.EarningsFeed
import kotlinx.coroutines.CoroutineScope
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

    private val _earningsLoading = MutableStateFlow(false)
    val earningsLoading: StateFlow<Boolean> = _earningsLoading.asStateFlow()

    private val _headlines = MutableStateFlow<Map<String, List<NewsItem>>>(emptyMap())
    val headlines: StateFlow<Map<String, List<NewsItem>>> = _headlines.asStateFlow()

    private val newsFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
        if (_dividendsLoading.value) return
        val heldSyms = held.map { it.uppercase() }.distinct()
        val otherSyms = others.map { it.uppercase() }.distinct() - heldSyms.toSet()
        if (heldSyms.isEmpty() && otherSyms.isEmpty()) return
        scope().launch {
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
        if (_earningsLoading.value) return
        val syms = symbols.map { it.uppercase() }.distinct()
        if (syms.isEmpty()) return
        scope().launch {
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
                val now = System.currentTimeMillis()
                val due = syms.filter { force || now - (earnFetchedAt[it] ?: 0L) >= EarningsFeed.TTL_MS }
                coroutineScope {
                    due.chunked(3).forEach { batch ->
                        batch.map { sym ->
                            async {
                                val d = runCatching { EarningsFeed.calendar(sym) }.getOrNull()
                                if (d != null) {
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
            }
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
        val PREFIXES = listOf(DIV_HISTORY, DIV_CALENDAR, EARN_DATE)
    }
}
