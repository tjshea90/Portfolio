package com.tj.portfolio.domain

import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import kotlin.math.abs

/** A computed position in one symbol, from either the FIFO or average-cost replay. */
data class Position(
    val symbol: String,
    val shares: Double,
    val costBasis: Double,
    val realized: Double,
    val firstBuy: Long,
    val overridden: Boolean = false,
    /** Shares bought during today's session, and what they actually cost. */
    val sharesToday: Double = 0.0,
    val costToday: Double = 0.0,
    /**
     * Shares sold that the transaction history never accounted for - the running total of
     * every sale that exceeded the shares on the books at the moment it was replayed.
     *
     * WHY THIS IS REPORTED RATHER THAN RESOLVED. Both replays already handle the arithmetic
     * the only way they honestly can: the proceeds are booked in full (the money really did
     * arrive) and cost comes off only for shares that were actually recorded. What neither
     * can do is decide WHY the sale was bigger than the position, and the two possible
     * answers need opposite treatments:
     *
     *   * a BUY IS MISSING from the records - an import that skipped a row, or a screenshot
     *     that brought the sell in first. This is overwhelmingly the likelier one in a
     *     ledger fed by screenshot imports, and here a later buy genuinely does open a new
     *     long position, which is what the app already does.
     *   * the position was SHORTED. Then the same later buy is a cover and should close to
     *     zero, not open a long.
     *
     * Guessing either way silently produces confident, wrong numbers in the other case, and
     * short selling is nowhere in this app's scope - no short transaction type, no margin
     * accounting, nothing in the brief. So the app reports the discrepancy where the
     * position is shown and leaves the correction - almost always "add the missing buy" -
     * to the one person who knows which happened.
     */
    val oversold: Double = 0.0
) {
    val avgCost: Double get() = if (shares > 1e-9) costBasis / shares else 0.0
    fun marketValue(price: Double) = shares * price
    fun unrealized(price: Double) = shares * price - costBasis
    fun unrealizedPct(price: Double) =
        if (costBasis > 1e-9) (shares * price - costBasis) / costBasis * 100.0 else 0.0
    /** Average fill price of the shares bought today. */
    val avgCostToday: Double get() = if (sharesToday > 1e-9) costToday / sharesToday else 0.0

    /**
     * What today actually did to THIS position.
     *
     * Shares held since before today are measured from the previous close, because that is
     * where their value stood when the session opened. Shares bought today never had a
     * previous close - they were bought at a fill price - so they are measured from what
     * was paid. Measuring a same-day purchase from the prior close credits or blames the
     * position for a move it was never exposed to.
     */
    fun dayPnl(q: Quote): Double {
        if (q.price <= 0) return 0.0
        val fresh = minOf(sharesToday, shares)
        val held = (shares - fresh).coerceAtLeast(0.0)
        val fromClose = if (q.prevClose > 0) held * (q.price - q.prevClose) else 0.0
        val fromFill = fresh * (q.price - avgCostToday)
        return fromClose + fromFill
    }

    /** What those shares were worth when the day started, for the percentage. */
    fun dayBasis(q: Quote): Double {
        val fresh = minOf(sharesToday, shares)
        val held = (shares - fresh).coerceAtLeast(0.0)
        val ref = if (q.prevClose > 0) q.prevClose else q.price
        return held * ref + fresh * avgCostToday
    }

    fun dayPnlPct(q: Quote): Double {
        val b = dayBasis(q)
        return if (b > 1e-9) dayPnl(q) / b * 100.0 else 0.0
    }
}

data class PortfolioTotals(
    val marketValue: Double,
    val cash: Double,
    val totalEquity: Double,
    val netDeposits: Double,
    val costBasis: Double,
    val realized: Double,
    val unrealized: Double,
    val unrealizedPct: Double,
    val totalGain: Double,
    val totalGainPct: Double,
    val dayGain: Double,
    val dayGainPct: Double,
    val dividends: Double,
    val fees: Double,
    /**
     * Today's move counted the way a BROKER counts it: every share measured from yesterday's
     * close, including shares bought this morning.
     *
     * [dayGain] is deliberately not that - it measures shares bought today from the price
     * actually paid, because a share bought at 255 this morning did not earn the move from
     * yesterday's 239.96 close. That is the more truthful answer to "what did I make today",
     * and it was a real fix (v2.2, caught on FIVE).
     *
     * But it means the headline disagrees with the brokerage app on exactly the days TJ is
     * most likely to be looking at both - and a reconciliation against a live Ally account
     * showed the whole $16.54 gap coming from two same-day buys, with nothing on screen
     * saying so. Carrying the broker's figure lets the summary explain the difference
     * instead of leaving the user to wonder which app is broken.
     */
    val brokerDayGain: Double,
    val brokerDayGainPct: Double,
    /** How many holdings have shares bought during the session the quotes describe. */
    val boughtTodayCount: Int
)

object Ledger {

    /**
     * Cost-basis method. Brokers (Ally included) report FIFO: the oldest shares are the
     * ones sold. Average cost pools every share into one price. Total lifetime P/L is the
     * same either way - only the split between realized and unrealized moves - but the
     * per-position cost basis differs whenever a symbol was sold and later re-bought.
     */
    const val FIFO = "FIFO"
    const val AVERAGE = "AVERAGE"

    /**
     * Replay the ledger in date order using average cost.
     * A SELL realizes (proceeds - fees) - (shares sold x average cost at that moment).
     */
    /**
     * @param sessionInstant any moment inside the trading SESSION the quotes describe -
     *        NOT necessarily "now". See the note below; getting this wrong is what made a
     *        stock bought mid-session report the session's entire move as the user's gain.
     */
    fun positions(
        txns: List<Txn>,
        overrides: Map<String, Override> = emptyMap(),
        method: String = FIFO,
        sessionInstant: Long = System.currentTimeMillis(),
        repairBelowId: Long = Long.MAX_VALUE
    ): List<Position> {
        val today = dayBounds(sessionInstant)
        return if (method == AVERAGE) averageCost(txns, overrides, today, repairBelowId)
        else fifo(txns, overrides, today, repairBelowId)
    }

    /**
     * The order both replays walk the history in. Every entry point stamps a trade with a DATE
     * only (local noon), so all of one day's trades tie on `date` and used to fall back to the
     * row id - i.e. the order they happened to be inserted in. Two things that gets wrong:
     *
     * 1. A SPLIT RECORDED ON THE SAME DATE AS TRADES (full-tests audit 2026-09-22, A-M3).
     *    A split takes effect at the open of its ex-date, so that day's fills are already in
     *    post-split shares. Replayed after them, the split multiplied shares that were bought
     *    at the new price - a 10-for-1 turned a same-day 50-share buy into 500. The split now
     *    always goes first within its day.
     *
     * 2. A SAME-DAY GROUP INSERTED BACKWARDS (A-H2). A brokerage activity screen lists newest
     *    first, and screenshot imports used to insert in screen order - so "bought 100 in the
     *    morning, sold 100 at lunch" landed as SELL-then-BUY: the sale found nothing to sell
     *    (reported as oversold, realized wrong) and the buy then opened 100 phantom shares.
     *    New imports are now inserted oldest-first ([ImportOrder.chronological]); this repairs
     *    rows already on file. For each symbol, a day's BUY/SELL rows are reversed ONLY when
     *    the order on file sells shares the books do not hold and the reverse order sells
     *    fewer - an order that is internally consistent is never touched, so a hand-entered
     *    history keeps exactly the order it was entered in.
     *
     * ONLY ROWS OLDER THAN [repairBelowId] (full test 2026-09-23, A-2). Rule 2 cannot tell
     * "stored backwards" from "the buy that covers this sale was never recorded": SELL 100
     * then BUY 100 on a day whose earlier lot is not on file oversells forward and not in
     * reverse - so it was reversed, the 100 shares actually held vanished, and the missing-buy
     * warning went with them. Rows inserted since imports became chronological are already in
     * the right order and need no repair, so the device records the first id that postdates
     * the fix (`PortfolioViewModel.replayRepairBelowId`) and only a group made entirely of
     * rows below it is a candidate. Default: every row, for callers with no watermark.
     */
    internal fun replayOrder(txns: List<Txn>, repairBelowId: Long = Long.MAX_VALUE): List<Txn> {
        // Both rules act only on an exact TIE: rows stamped with a real time of day keep that
        // order, because it is information; date-only rows (every entry point stamps local
        // noon) tie, and the id is not.
        val sorted = txns.sortedWith(
            compareBy<Txn>({ it.date }, { if (it.type == TxnType.SPLIT) 0 else 1 }, { it.id })
        ).toMutableList()
        // Slots of each symbol's rows, in replay order. Symbols never interact in a replay,
        // so a group is reordered within its own slots and every other row stays put.
        val bySym = LinkedHashMap<String, MutableList<Int>>()
        sorted.forEachIndexed { i, t -> t.symbol?.uppercase()?.let { bySym.getOrPut(it) { ArrayList() }.add(i) } }
        for (slots in bySym.values) {
            var held = 0.0
            var k = 0
            while (k < slots.size) {
                val t = sorted[slots[k]]
                if (t.type != TxnType.BUY && t.type != TxnType.SELL) {
                    if (t.type == TxnType.SPLIT) TxnType.splitRatio(t).takeIf { it > 0.0 }?.let { held *= it }
                    k++; continue
                }
                var end = k
                while (end + 1 < slots.size && sorted[slots[end + 1]].date == t.date &&
                    sorted[slots[end + 1]].type.let { it == TxnType.BUY || it == TxnType.SELL }
                ) end++
                val group = (k..end).map { sorted[slots[it]] }
                val forward = simulate(held, group)
                var chosen = group
                if (group.size > 1 && forward.second > 1e-9 && group.all { it.id < repairBelowId }) {
                    val reversed = group.asReversed()
                    if (simulate(held, reversed).second < forward.second - 1e-9) {
                        chosen = reversed.toList()
                        (k..end).forEachIndexed { j, slot -> sorted[slots[slot]] = chosen[j] }
                    }
                }
                held = simulate(held, chosen).first
                k = end + 1
            }
        }
        return sorted
    }

    /** Shares held after [group], starting from [held], and how many were sold without cover. */
    private fun simulate(held: Double, group: List<Txn>): Pair<Double, Double> {
        var h = held
        var over = 0.0
        for (t in group) {
            val q = abs(t.quantity)
            if (t.type == TxnType.BUY) h += q
            else { if (q > h + 1e-9) over += q - h; h = (h - q).coerceAtLeast(0.0) }
        }
        return h to over
    }

    /**
     * The day window that counts as "this session".
     *
     * THIS IS NOT THE DEVICE'S CALENDAR DAY, and the difference is a real bug that shipped.
     * A quote describes one trading session: `price` is the latest print in it and
     * `prevClose` is the close of the session before. Between one close and the next open -
     * every evening, every night, all weekend - the session the quote describes is
     * YESTERDAY while the calendar says today.
     *
     * Reported by TJ: he bought SMH mid-session on 3 Sep and looked at the app at 01:46 on
     * 4 Sep. The quote still described the 3 Sep session (up 2.12), but the ledger's window
     * was 4 Sep, so his 3 Sep buy fell outside it, counted as "held since the previous
     * close", and was credited with the whole 2.12 move - when he had only owned the shares
     * from his mid-day fill. Measured from that fill the real gain was 0.56.
     *
     * The caller therefore passes the instant of the session the QUOTES are describing
     * (see PortfolioViewModel.sessionInstant), not the wall clock.
     */
    private fun dayBounds(now: Long): LongRange {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = now
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        return start until (start + 86_400_000L)
    }

    /**
     * Price per share for a transaction that did not record one.
     *
     * `amount` is the signed cash effect and ALREADY includes fees: a BUY moves
     * -(gross + fees) and a SELL +(gross - fees). Deriving the price as abs(amount)/qty
     * therefore bakes the fee into the per-share price - and the callers then add `fees` a
     * second time. On a buy that inflated the cost basis by twice the commission; on a sell
     * it understated the proceeds by the same. Only visible on transactions imported without
     * a per-share price AND carrying a fee, which is why it went unnoticed.
     */
    private fun unitPrice(t: Txn, qty: Double): Double {
        if (t.price > 0) return t.price
        if (qty < 1e-9) return 0.0
        val gross = when (t.type) {
            // amount = -(gross + fees), and it is always negative, so |amount| - fees = gross.
            TxnType.BUY -> (abs(t.amount) - t.fees).coerceAtLeast(0.0)
            // amount = gross - fees. USE THE SIGNED AMOUNT, not its absolute value.
            //
            // A sale whose fees exceed its proceeds has a NEGATIVE cash effect, and
            // `abs(amount) + fees` then reads (fees - gross) + fees = 2*fees - gross, which
            // is not the gross at all - it is wrong by twice the shortfall. Found by property
            // testing: SELL 0.1458 shares for $5.09 with $5.31 of fees recovered a price
            // implying $5.53 of proceeds, and the ledger stopped reconciling with cash by
            // $0.44. `amount + fees` is exact whichever side of zero the amount lands on.
            //
            // Ally's own schedule caps the low-priced commission at 5% of trade value, so its
            // fees can never outrun the proceeds - but an imported row or a hand-entered
            // account charge can, and a silent arithmetic error is not worth leaving in for
            // the sake of a case that "should not happen".
            TxnType.SELL -> (t.amount + t.fees).coerceAtLeast(0.0)
            // UNREACHABLE TODAY (Part 9 audit finding) - both call sites already filter to
            // BUY/SELL before reaching here. `t.type` is a String, not an enum, so Kotlin
            // still requires an `else` to make the `when` exhaustive; a silent `abs(t.amount)`
            // fallback would produce a plausible-looking wrong price if a future caller ever
            // did pass something else, instead of surfacing the misuse.
            else -> throw IllegalArgumentException("unitPrice called with a non-BUY/SELL txn: ${t.type}")
        }
        return gross / qty
    }

    /** One purchase lot: shares remaining, and cost per share including that buy's fees. */
    /**
     * @param today whether the BUY that opened this lot was dated in the current session -
     *   carried ON THE LOT so that a sell removes it with the shares (Round 66 audit, CRX-1).
     */
    private data class Lot(var shares: Double, var unitCost: Double, val today: Boolean = false)

    /**
     * FIFO replay - matches what a broker statement shows. Sells consume the oldest lots
     * first; whatever is left is the cost basis of the open position.
     */
    private fun fifo(
        txns: List<Txn>,
        overrides: Map<String, Override>,
        today: LongRange,
        repairBelowId: Long = Long.MAX_VALUE
    ): List<Position> {
        val lots = LinkedHashMap<String, ArrayDeque<Lot>>()
        val realized = LinkedHashMap<String, Double>()
        val first = LinkedHashMap<String, Long>()
        /** Shares sold with nothing on the books to cover them - see [Position.oversold]. */
        val oversold = LinkedHashMap<String, Double>()

        for (t in replayOrder(txns, repairBelowId)) {
            val sym = t.symbol?.uppercase() ?: continue
            if (t.type == TxnType.DIVIDEND) { lots.getOrPut(sym) { ArrayDeque() }; continue }
            // A SPLIT rewrites every open lot in place: more shares, proportionally cheaper,
            // same total cost. See [TxnType.SPLIT]. Deliberately does NOT create a position
            // for a symbol that has none - a split on something never held is a no-op, not a
            // reason to invent an empty holding.
            if (t.type == TxnType.SPLIT) {
                val ratio = TxnType.splitRatio(t)
                if (ratio > 0.0) {
                    lots[sym]?.forEach {
                        it.shares *= ratio
                        it.unitCost /= ratio
                    }
                    // OVERSOLD SCALES TOO. It is denominated in real share units of this same
                    // symbol - "shares sold beyond what the recorded history could cover" - so a
                    // split has to rescale it exactly like every other share count on the
                    // position, or a presumed-missing 6 shares silently stays "6" after a
                    // 10-for-1 split instead of the 60 real shares that figure now represents.
                    oversold[sym]?.let { oversold[sym] = it * ratio }
                }
                continue
            }
            if (t.type != TxnType.BUY && t.type != TxnType.SELL) continue

            val q = lots.getOrPut(sym) { ArrayDeque() }
            realized.putIfAbsent(sym, 0.0)
            val qty = abs(t.quantity)
            if (qty < 1e-9) continue
            val px = unitPrice(t, qty)

            when (t.type) {
                TxnType.BUY -> {
                    if (first[sym] == null) first[sym] = t.date
                    // ---- "BOUGHT TODAY" IS A PROPERTY OF THE LOT (Round 66 audit, CRX-1).
                    //
                    // THE BUG THIS FIXES. It used to be two side maps that a BUY added to and
                    // NOTHING ever took from - a SELL consumed the lot but left the same
                    // shares sitting in `todayShares`, at their old cost, forever.
                    //
                    // A same-day round trip is all it took. Buy 100 SOFI at 10.00 in the
                    // morning, sell all 100 at 12.00 at lunch (+$200, correctly booked in
                    // `realized`), buy 100 back at 12.00 in the afternoon: `todayShares` is
                    // 200 and `todayCost` is 2,200, so the app believes the 100 shares held
                    // cost 11.00 apiece when they cost 12.00. With the price at 12.00 -
                    // exactly what was paid, so today's move on the open position is zero -
                    // `dayPnl` reported +$100.00 and +9.09%. The portfolio's "Today" headline
                    // carried the same fabricated hundred dollars, and `brokerDayGain`, which
                    // never touches these fields, disagreed with it by precisely that amount:
                    // the two figures whose whole purpose is to reconcile.
                    //
                    // On the lot, the arithmetic is exact and needs no separate bookkeeping at
                    // all - FIFO already removes the right lots, so whatever is still in the
                    // deque and flagged is genuinely what was bought today and still held.
                    // A guessed date is not a purchase today - see [Txn.dateEstimated].
                    q.addLast(Lot(qty, (qty * px + t.fees) / qty,
                        today = t.date in today && !t.dateEstimated))
                }
                TxnType.SELL -> {
                    var remaining = qty
                    var costOut = 0.0
                    while (remaining > 1e-9 && q.isNotEmpty()) {
                        val lot = q.first()
                        val take = minOf(remaining, lot.shares)
                        costOut += take * lot.unitCost
                        lot.shares -= take
                        remaining -= take
                        if (lot.shares < 1e-9) q.removeFirst()
                    }
                    // Selling more than the app knows about (a missing buy) still books the
                    // proceeds rather than silently dropping them. `remaining` is whatever
                    // the deque could not cover, which is precisely the discrepancy worth
                    // telling the user about - see [Position.oversold].
                    if (remaining > 1e-9) oversold[sym] = (oversold[sym] ?: 0.0) + remaining
                    realized[sym] = realized[sym]!! + (qty * px - t.fees) - costOut
                }
            }
        }

        return lots.map { (sym, q) ->
            val shares = q.sumOf { it.shares }
            val cost = q.sumOf { it.shares * it.unitCost }
            // Only lots opened today AND still open - see the note at `Lot(today = ...)`.
            val fresh = q.filter { it.today }
            applyOverride(
                sym, shares, cost, realized[sym] ?: 0.0, first[sym] ?: 0L, overrides,
                fresh.sumOf { it.shares }, fresh.sumOf { it.shares * it.unitCost },
                oversold[sym] ?: 0.0
            )
        }
    }

    /** Average-cost replay - every share pooled at one blended price. */
    private fun averageCost(
        txns: List<Txn>,
        overrides: Map<String, Override>,
        today: LongRange,
        repairBelowId: Long = Long.MAX_VALUE
    ): List<Position> {
        /**
         * [beforeShares] is shares still held that were bought STRICTLY BEFORE the session
         * window - not merely "not today". The distinction is the whole reason it exists: a
         * transaction can also be dated AFTER the window, because the window is the session
         * the QUOTES describe and that lags the calendar every evening, every night and all
         * weekend (see [dayBounds]). Lumping those in with the genuinely older shares makes a
         * sale consume them ahead of today's, where FIFO - replaying in true date order -
         * correctly reaches today's shares first. See the consumption order in the SELL branch.
         */
        data class Acc(var shares: Double = 0.0, var cost: Double = 0.0,
                       var realized: Double = 0.0, var first: Long = 0L,
                       var todayShares: Double = 0.0, var todayCost: Double = 0.0,
                       var beforeShares: Double = 0.0, var oversold: Double = 0.0)

        val acc = LinkedHashMap<String, Acc>()
        for (t in replayOrder(txns, repairBelowId)) {
            val sym = t.symbol?.uppercase() ?: continue
            if (t.type !in setOf(TxnType.BUY, TxnType.SELL)) {
                if (t.type == TxnType.DIVIDEND) acc.getOrPut(sym) { Acc() }
                // The same split, on a pooled book: every share count scales, and the costs
                // do not move at all - which is exactly what leaves the average price
                // divided by the ratio. See [TxnType.SPLIT] and fifo()'s own branch.
                if (t.type == TxnType.SPLIT) {
                    val ratio = TxnType.splitRatio(t)
                    if (ratio > 0.0) acc[sym]?.let { a ->
                        a.shares *= ratio
                        a.todayShares *= ratio
                        a.beforeShares *= ratio
                        // Same reasoning as fifo()'s own SPLIT branch: oversold is real share
                        // units of this symbol and has to scale with everything else on it.
                        a.oversold *= ratio
                    }
                }
                continue
            }
            val a = acc.getOrPut(sym) { Acc() }
            val qty = abs(t.quantity)
            // Parity with fifo(), which has always skipped these. Without the guard a BUY
            // recorded with no share count but a fee on it added that fee to the cost basis
            // of a position holding no shares - an average cost computed from a zero divisor,
            // and a cost basis that no quantity justifies.
            if (qty < 1e-9) continue
            val px = unitPrice(t, qty)
            when (t.type) {
                TxnType.BUY -> {
                    if (a.first == 0L) a.first = t.date
                    a.shares += qty
                    a.cost += qty * px + t.fees
                    // Three buckets, because a sale consumes them in this order: shares
                    // bought before the window, then inside it, then after it. The third
                    // needs no counter - it is whatever `shares` has left over.
                    if (t.date in today && !t.dateEstimated) {
                        a.todayShares += qty
                        a.todayCost += qty * px + t.fees
                    } else if (t.date < today.first || t.dateEstimated) {
                        a.beforeShares += qty
                    }
                }
                TxnType.SELL -> {
                    val avg = if (a.shares > 1e-9) a.cost / a.shares else 0.0
                    // A sell can be bigger than the position the ledger knows about - a buy
                    // that has not been imported yet, or a screenshot that brought the sell
                    // in first. `fifo()` has always handled that by booking the proceeds for
                    // the WHOLE sale and consuming only the cost it actually has; this did
                    // not, and only counted the covered shares. On "bought 4 at 100, sold 10
                    // at 150" FIFO realized 1,100 and average realized 200 - $900 of money
                    // the account genuinely received simply vanished from the realized
                    // figure, while cash (which sums txn.amount) still showed all of it.
                    //
                    // Cost comes off only for shares that were on the books; proceeds are
                    // booked in full. That restores the invariant this project has always
                    // claimed and now tests: total lifetime P/L is identical under both
                    // methods, and only the realized/unrealized split moves.
                    val covered = minOf(qty, a.shares.coerceAtLeast(0.0))
                    // Whatever the books could not cover - see [Position.oversold]. FIFO
                    // records the same quantity from its own leftover `remaining`.
                    if (qty - covered > 1e-9) a.oversold += qty - covered
                    a.realized += (qty * px - t.fees) - covered * avg
                    a.cost -= covered * avg
                    a.shares -= covered
                    if (a.shares < 1e-9) { a.shares = 0.0; a.cost = 0.0 }
                    // ---- AND THE SAME-DAY POOL SHRINKS WITH IT (Round 66 audit, CRX-1),
                    //      OLDEST SHARES FIRST (Part 9 audit - the bug fixed below).
                    //
                    // The FIFO replay carries "bought today" on the lot, which a sell removes
                    // for free. Average cost has no lots, so it has to do the arithmetic
                    // explicitly. Without it a same-day round trip left shares in the pool
                    // that were no longer held, and the "Today" figure for the position was
                    // computed against a cost nobody paid.
                    //
                    // THE BUG THIS FIXES. It used to take `minOf(covered, todayShares)` -
                    // today's shares FIRST - on the reasoning (written into the comment it
                    // replaces) that "FIFO would take the oldest lots first, which for a
                    // same-session trade and a pool this size comes to the same shares". That
                    // is true only when the WHOLE position was bought today, which is the one
                    // case CRX-1 was written against and the only case any test covered. As
                    // soon as a position holds shares from before today as well, the two
                    // methods disagree about the same trades: buy 100 at 10 yesterday, buy 50
                    // at 12 today, sell 30 at 13 today, and FIFO correctly consumes the old
                    // lot and still reports 50 shares bought today, while this reported 20.
                    // `sharesToday` feeds `dayPnl`, so flipping the cost-basis preference in
                    // Settings silently changed the "Today" headline - a number that has
                    // nothing to do with cost basis and must not depend on the method.
                    //
                    // OLDEST FIRST MEANS THREE BUCKETS, NOT TWO. The first draft of this fix
                    // consumed "everything that is not today's" before today's, which is only
                    // the same thing when no transaction is dated AFTER the session window.
                    // One can be: the window is the session the QUOTES describe, and that lags
                    // the calendar every evening, every night and all weekend (see [dayBounds]).
                    // Found by the randomised cross-method property this round added - sell
                    // before the window, buy inside it, buy after it, then sell again, and
                    // FIFO correctly consumed the in-window lot first while this consumed the
                    // later one. A sale takes the pre-window shares, then the in-window ones,
                    // then whatever was bought after; only the middle bucket carries a cost.
                    var rest = covered
                    val fromBefore = minOf(rest, a.beforeShares)
                    a.beforeShares -= fromBefore
                    rest -= fromBefore
                    val fromToday = minOf(rest, a.todayShares)
                    if (fromToday > 1e-9 && a.todayShares > 1e-9) {
                        a.todayCost -= fromToday * (a.todayCost / a.todayShares)
                        a.todayShares -= fromToday
                        if (a.todayShares < 1e-9) { a.todayShares = 0.0; a.todayCost = 0.0 }
                    }
                }
            }
        }
        return acc.map { (sym, a) ->
            applyOverride(
                sym, a.shares, a.cost, a.realized, a.first, overrides,
                a.todayShares, a.todayCost, a.oversold
            )
        }
    }

    private fun applyOverride(
        sym: String, sharesIn: Double, costIn: Double,
        realized: Double, first: Long, overrides: Map<String, Override>,
        sharesToday: Double = 0.0, costToday: Double = 0.0,
        oversold: Double = 0.0
    ): Position {
        val ov = overrides[sym]
        var shares = sharesIn
        var cost = costIn
        var over = false
        // Overriding the SHARE COUNT alone used to leave the old cost basis in place, so the
        // average cost silently changed: 100 shares at $10 corrected down to 50 shares kept
        // the $1,000 basis and started reporting $20/share. Carry the calculated average
        // across instead. An explicit avgCost override still wins, exactly as before.
        val calcAvg = if (sharesIn > 1e-9) costIn / sharesIn else 0.0
        if (ov?.shares != null) { shares = ov.shares; cost = shares * calcAvg; over = true }
        if (ov?.avgCost != null) { cost = shares * ov.avgCost; over = true }
        // `oversold` is a fact about the transaction history, so an override - which corrects
        // the POSITION - does not clear it. The records still disagree with themselves.
        return Position(sym, shares, cost, realized, first, over, sharesToday, costToday, oversold)
    }

    /** Cash / buying power derived from every transaction's signed cash effect. */
    fun cash(txns: List<Txn>): Double = txns.sumOf { it.amount }

    /** Money you actually put in: deposits minus withdrawals. */
    fun netDeposits(txns: List<Txn>): Double = txns.sumOf {
        when (it.type) {
            TxnType.DEPOSIT -> abs(it.amount)
            TxnType.WITHDRAWAL -> -abs(it.amount)
            else -> 0.0
        }
    }

    fun dividends(txns: List<Txn>): Double =
        txns.filter { it.type == TxnType.DIVIDEND || it.type == TxnType.INTEREST }.sumOf { abs(it.amount) }

    fun fees(txns: List<Txn>): Double =
        txns.sumOf { it.fees } + txns.filter { it.type == TxnType.FEE }.sumOf { abs(it.amount) }

    /**
     * THE FOUR NUMBERS THAT DEPEND ONLY ON THE LEDGER (Round 63 sweep).
     *
     * `cash`, `netDeposits`, `dividends` and `fees` are pure functions of the transaction
     * list and nothing else - no price, no clock. [totals] ran all four on every call, and
     * [totals] is called on every quote tick: four full scans of the whole table (two of them
     * allocating a filtered copy first), 240 times an hour, on the main thread, always
     * producing the same four numbers until a transaction is added.
     *
     * Computing them once where the ledger actually changes and passing them in is the whole
     * fix. The ViewModel already has exactly that boundary - `recompute()` replays the ledger,
     * `reprice()` only re-values it - so this is simply the ledger half moving to the ledger
     * side of it.
     */
    data class LedgerSums(
        val cash: Double,
        val netDeposits: Double,
        val dividends: Double,
        val fees: Double
    )

    fun sums(txns: List<Txn>): LedgerSums = LedgerSums(
        cash = cash(txns),
        netDeposits = netDeposits(txns),
        dividends = dividends(txns),
        fees = fees(txns)
    )

    fun totals(
        txns: List<Txn>,
        positions: List<Position>,
        quotes: Map<String, Quote>,
        cashOverride: Double? = null,
        /**
         * The ledger-only figures, when the caller already holds them. Null recomputes them,
         * which keeps every existing call site and every test working unchanged.
         */
        sums: LedgerSums? = null
    ): PortfolioTotals {
        val led = sums ?: sums(txns)
        val open = positions.filter { it.shares > 1e-9 }
        var mv = 0.0; var cb = 0.0; var day = 0.0; var dayBase = 0.0
        var brokerDay = 0.0; var brokerBase = 0.0; var freshCount = 0
        for (p in open) {
            val q = quotes[p.symbol]
            val px = q?.price?.takeIf { it > 0 } ?: p.avgCost
            mv += p.shares * px
            cb += p.costBasis
            if (q != null && q.price > 0) {
                day += p.dayPnl(q)
                dayBase += p.dayBasis(q)
                // The broker's version: every share from the previous close, no exceptions.
                if (q.prevClose > 0) {
                    brokerDay += p.shares * q.dayChange
                    brokerBase += p.shares * q.prevClose
                }
                if (p.sharesToday > 1e-9) freshCount++
            }
        }
        val cash = cashOverride ?: led.cash
        val realized = positions.sumOf { it.realized }
        val unrealized = mv - cb
        val equity = mv + cash
        val net = led.netDeposits
        val totalGain = equity - net
        return PortfolioTotals(
            marketValue = mv,
            cash = cash,
            totalEquity = equity,
            netDeposits = net,
            costBasis = cb,
            realized = realized,
            unrealized = unrealized,
            unrealizedPct = if (cb > 1e-9) unrealized / cb * 100.0 else 0.0,
            totalGain = totalGain,
            totalGainPct = if (net > 1e-9) totalGain / net * 100.0 else 0.0,
            dayGain = day,
            dayGainPct = if (dayBase > 1e-9) day / dayBase * 100.0 else 0.0,
            dividends = led.dividends,
            fees = led.fees,
            brokerDayGain = brokerDay,
            brokerDayGainPct = if (brokerBase > 1e-9) brokerDay / brokerBase * 100.0 else 0.0,
            boughtTodayCount = freshCount
        )
    }
}
