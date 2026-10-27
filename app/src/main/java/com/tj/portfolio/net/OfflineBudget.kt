package com.tj.portfolio.net

/**
 * THE OFFLINE CACHE'S SIZE BUDGET (2026-10-27) — a pure function, so the priority order Tj asked
 * for is asserted by tests rather than remembered.
 *
 * Tj, verbatim: "up to around 50mb ... If the 50mb limit is not enough, always prioritize the
 * current prices and graphs of my holdings, then dividends and earnings reports/schedules of my
 * holdings, then news of my holdings, then analysis of my holdings, then my watch list, then the
 * research section, and finally as much of the live feed as possible within limits."
 *
 * TIERS (1 = last to be given up):
 *   1 prices + charts            — never evicted (`chart_cache`, `quotes`; a few MB at most)
 *   2 dividends + earnings       — never evicted (tiny `settings` rows)
 *   3 news of holdings           ─┐
 *   4 analysis of holdings        │ saved article pages (`offline_pages.tier`), evicted
 *   5 watch list                  │ oldest-first inside a tier
 *   6 research section            │
 *   7 live feed                  ─┘
 * Everything that cannot be partially evicted (charts, parsed fundamentals, headlines, the
 * research blob, ledger-adjacent rows) is "pinned": it counts against the budget but is never
 * what gets deleted. The raw HTTP validator cache is NOT useful offline (a 304 needs a server),
 * so it is the very first thing to go.
 */
object OfflineBudget {

    /** "Around 50 MB". */
    const val BUDGET_BYTES = 50L * 1024 * 1024

    /** Evictable groups, LOWEST value first — the order is the eviction order. */
    enum class Bucket(val tier: Int, val label: String) {
        HTTP(9, "Request cache"),
        FEED(7, "Live feed pages"),
        RESEARCH(6, "Research pages"),
        WATCH(5, "Watch list pages"),
        ANALYSIS(4, "Analysis pages"),
        NEWS(3, "Holdings news pages");

        companion object {
            /** The bucket a saved page of [tier] lives in. Tiers outside 3..7 fall to the cheapest. */
            fun ofTier(tier: Int): Bucket = when (tier) {
                3 -> NEWS
                4 -> ANALYSIS
                5 -> WATCH
                6 -> RESEARCH
                7 -> FEED
                else -> FEED
            }
        }
    }

    /**
     * How many bytes to free from each bucket so that pinned + buckets <= [budget]. Walks the
     * buckets cheapest-first and takes only what is still needed, so a tier is touched only once
     * every cheaper tier is empty. Pure.
     */
    fun plan(pinned: Long, sizes: Map<Bucket, Long>, budget: Long = BUDGET_BYTES): Map<Bucket, Long> {
        var over = pinned + sizes.values.sumOf { it.coerceAtLeast(0L) } - budget
        val out = LinkedHashMap<Bucket, Long>()
        if (over <= 0L) return out
        for (b in Bucket.entries) {
            if (over <= 0L) break
            val have = (sizes[b] ?: 0L).coerceAtLeast(0L)
            if (have == 0L) continue
            val take = minOf(have, over)
            out[b] = take
            over -= take
        }
        return out
    }

    /** Room left for pages in [tier]'s bucket without displacing anything of higher priority. */
    fun roomFor(tier: Int, pinned: Long, sizes: Map<Bucket, Long>, budget: Long = BUDGET_BYTES): Long {
        val mine = Bucket.ofTier(tier)
        // Bytes held by buckets that outrank this one are not available to it.
        val higher = Bucket.entries.filter { it.tier < mine.tier }.sumOf { sizes[it] ?: 0L }
        val others = pinned + higher
        return (budget - others - (sizes[mine] ?: 0L)).coerceAtLeast(0L)
    }
}
