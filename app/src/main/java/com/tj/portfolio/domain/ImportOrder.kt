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
