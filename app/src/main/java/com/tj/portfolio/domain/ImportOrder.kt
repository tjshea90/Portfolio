package com.tj.portfolio.domain

import com.tj.portfolio.data.Txn

/**
 * The order an import batch is INSERTED in (full-tests audit 2026-09-22, A-H2).
 *
 * Every imported trade carries a date and no time, so the ledger can only tell two same-day
 * trades apart by row id - the insertion order. A brokerage activity screen (Ally's included)
 * lists NEWEST first, and both import prompts ask for rows in screen order, so inserting the
 * batch as it arrived stored every day's trades backwards: "bought in the morning, sold at
 * lunch" replayed as sell-then-buy, which reads as a sale of shares never held followed by a
 * phantom open position. [Ledger.replayOrder] repairs the worst of that for rows already on
 * file; this stops new imports creating it.
 */
object ImportOrder {

    /**
     * [batch] oldest first, with each day's rows in the order they happened.
     *
     * The batch's own dates decide which way it runs: more steps backwards in time than
     * forwards means newest-first, and it is reversed. When the dates say nothing - one day's
     * trades, or an even split - it is taken to be newest-first, because that is how the
     * activity screens these come from are laid out. The final sort is by day only and stable,
     * so it never reorders trades within a day; it only straightens out a batch assembled
     * from screenshots picked out of order.
     */
    fun chronological(batch: List<Txn>): List<Txn> {
        if (batch.size < 2) return batch
        var forwards = 0
        var backwards = 0
        for (i in 1 until batch.size) {
            val a = day(batch[i - 1].date)
            val b = day(batch[i].date)
            if (b > a) forwards++ else if (b < a) backwards++
        }
        val oriented = if (forwards > backwards) batch else batch.asReversed()
        return oriented.sortedBy { day(it.date) }
    }

    private fun day(ms: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
    }
}

/** What the import review dialog says about one extracted row. */
enum class ImportDup {
    NEW,
    /** Matches a transaction already on file - one stored row per extracted row. */
    ON_FILE,
    /**
     * Identical to an earlier row in the same batch: the same row seen on two overlapping
     * screenshots, or two genuine fills of the same size at the same price. Only the user can
     * tell which, so it is shown unticked, never dropped.
     */
    REPEAT
}

/**
 * Duplicate classification for an import batch (full-tests audit 2026-09-22, A-M4).
 *
 * Two fills of 50 shares at the same price on the same day are two identical rows, and both
 * are real. The import used to (a) flag BOTH as "already have" when ONE was on file - the
 * stored row matched every copy - and (b) silently drop the second at commit even after the
 * user ticked it, through a within-batch fingerprint check that ignored `force`. Now each
 * stored row can absorb exactly one extracted row, and a within-batch repeat is shown for the
 * user to decide, then imported exactly as ticked.
 */
object ImportDupes {

    /** The within-batch identity: same type, symbol, day, share count and cash amount. */
    fun fingerprint(t: Txn): String = listOf(
        t.type, (t.symbol ?: ""), com.tj.portfolio.util.Fmt.iso(t.date),
        Math.round(kotlin.math.abs(t.quantity) * 10000),
        Math.round(kotlin.math.abs(t.amount) * 100)
    ).joinToString("|")

    /**
     * @param match finds a stored transaction matching the row, skipping the ids in its second
     *   argument (already claimed by an earlier row of this batch), or null.
     */
    fun classify(batch: List<Txn>, match: (Txn, Set<Long>) -> Long?): List<ImportDup> {
        val claimed = HashSet<Long>()
        val seen = HashSet<String>()
        return batch.map { t ->
            val fp = fingerprint(t)
            val onFile = match(t, claimed)
            val firstInBatch = seen.add(fp)
            when {
                onFile != null -> { claimed.add(onFile); ImportDup.ON_FILE }
                !firstInBatch -> ImportDup.REPEAT
                else -> ImportDup.NEW
            }
        }
    }
}
