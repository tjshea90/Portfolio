package com.tj.portfolio.net

/**
 * THE SAME YAHOO CHART BODY, FETCHED ONCE AND SHARED FOR A FEW SECONDS (full test 2026-09-23,
 * N-1).
 *
 * `range=1d&interval=5m&includePrePost=true` is ONE url with two consumers here - the Day
 * Trading technicals ([DayTradingTechnicals]) and the 1D chart ([ChartFeed]) - and the Day
 * Trading sweep asked for it through both for every row. (The row sparkline's own fetch,
 * `MarketData.yahoo`, does not go through this memo - it runs on its own five-minute clock, and
 * `adoptAsSparkline` already feeds it from a fresh 1D chart. Corrected 2026-09-24, N-Q2.) The code assumed the
 * repeat was a cheap bodyless 304; measured on 2026-09-23, Yahoo's chart endpoint sends no
 * ETag and no Last-Modified (only `cache-control: max-age=10`), so `Http`'s conditional cache
 * never engages for it and every repeat was a full download.
 *
 * In memory, tiny, and short-lived on purpose: this is not a cache of record (charts have
 * `Db.cacheChart` for that), only a way for requests a few seconds apart to share one answer.
 * Keyed WITHOUT the host, so a body from query2 serves a query1 ask for the same data.
 */
object RecentBodies {

    /** Longer than Yahoo's own `max-age=10`, shorter than the 30-second sweep. */
    const val MAX_AGE_MS = 25_000L
    private const val MAX_ENTRIES = 64

    private class Entry(val at: Long, val body: String)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > MAX_ENTRIES
    }

    /** The host-independent key for a Yahoo chart url. */
    fun keyOf(url: String): String = url.substringAfter("/v8/finance/chart/", url)

    @Synchronized
    fun get(url: String, maxAgeMs: Long = MAX_AGE_MS, now: Long = System.currentTimeMillis()): String? {
        val e = map[keyOf(url)] ?: return null
        return if (now - e.at in 0..maxAgeMs) e.body else null
    }

    /** [get] plus WHEN the body was fetched - the time a series parsed from it is "from" (N-9). */
    @Synchronized
    fun getStamped(url: String, maxAgeMs: Long = MAX_AGE_MS, now: Long = System.currentTimeMillis()): Pair<Long, String>? {
        val e = map[keyOf(url)] ?: return null
        return if (now - e.at in 0..maxAgeMs) e.at to e.body else null
    }

    @Synchronized
    fun put(url: String, body: String, now: Long = System.currentTimeMillis()) {
        map[keyOf(url)] = Entry(now, body)
    }

    @Synchronized
    fun clear() = map.clear()
}
