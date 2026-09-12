package com.tj.portfolio.domain

import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import kotlin.math.abs

/** A computed position in one symbol, using the average-cost method. */
data class Position(
    val symbol: String,
    val shares: Double,
    val costBasis: Double,
    val realized: Double,
    val firstBuy: Long,
    val overridden: Boolean = false,
    /** Shares bought during today's session, and what they actually cost. */
    val sharesToday: Double = 0.0,
    val costToday: Double = 0.0
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
        sessionInstant: Long = System.currentTimeMillis()
    ): List<Position> {
        val today = dayBounds(sessionInstant)
        return if (method == AVERAGE) averageCost(txns, overrides, today)
        else fifo(txns, overrides, today)
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
    private data class Lot(var shares: Double, val unitCost: Double, val today: Boolean = false)

    /**
     * FIFO replay - matches what a broker statement shows. Sells consume the oldest lots
     * first; whatever is left is the cost basis of the open position.
     */
    private fun fifo(
        txns: List<Txn>,
        overrides: Map<String, Override>,
        today: LongRange
    ): List<Position> {
        val lots = LinkedHashMap<String, ArrayDeque<Lot>>()
        val realized = LinkedHashMap<String, Double>()
        val first = LinkedHashMap<String, Long>()

        for (t in txns.sortedWith(compareBy({ it.date }, { it.id }))) {
            val sym = t.symbol?.uppercase() ?: continue
            if (t.type == TxnType.DIVIDEND) { lots.getOrPut(sym) { ArrayDeque() }; continue }
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
                    q.addLast(Lot(qty, (qty * px + t.fees) / qty, today = t.date in today))
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
                    // proceeds rather than silently dropping them.
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
                fresh.sumOf { it.shares }, fresh.sumOf { it.shares * it.unitCost }
            )
        }
    }

    /** Average-cost replay - every share pooled at one blended price. */
    private fun averageCost(
        txns: List<Txn>,
        overrides: Map<String, Override>,
        today: LongRange
    ): List<Position> {
        data class Acc(var shares: Double = 0.0, var cost: Double = 0.0,
                       var realized: Double = 0.0, var first: Long = 0L,
                       var todayShares: Double = 0.0, var todayCost: Double = 0.0)

        val acc = LinkedHashMap<String, Acc>()
        for (t in txns.sortedWith(compareBy({ it.date }, { it.id }))) {
            val sym = t.symbol?.uppercase() ?: continue
            if (t.type !in setOf(TxnType.BUY, TxnType.SELL)) {
                if (t.type == TxnType.DIVIDEND) acc.getOrPut(sym) { Acc() }
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
                    if (t.date in today) {
                        a.todayShares += qty
                        a.todayCost += qty * px + t.fees
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
                    a.realized += (qty * px - t.fees) - covered * avg
                    a.cost -= covered * avg
                    a.shares -= covered
                    if (a.shares < 1e-9) { a.shares = 0.0; a.cost = 0.0 }
                    // ---- AND THE SAME-DAY POOL SHRINKS WITH IT (Round 66 audit, CRX-1).
                    //
                    // The FIFO replay carries "bought today" on the lot, which a sell removes
                    // for free. Average cost has no lots, so it has to do the arithmetic: the
                    // shares sold come out of today's pool at today's own blended price, in
                    // the same proportion. Without this a same-day round trip left shares in
                    // the pool that were no longer held, and the "Today" figure for the
                    // position was computed against a cost nobody paid.
                    //
                    // FIFO would take the OLDEST lots first, which for a same-session trade
                    // and a pool this size comes to the same shares; taking them pro-rata is
                    // the only thing an average-cost book can say, and it is consistent with
                    // how every other figure in this branch is derived.
                    val fromToday = minOf(covered, a.todayShares)
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
                a.todayShares, a.todayCost
            )
        }
    }

    private fun applyOverride(
        sym: String, sharesIn: Double, costIn: Double,
        realized: Double, first: Long, overrides: Map<String, Override>,
        sharesToday: Double = 0.0, costToday: Double = 0.0
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
        return Position(sym, shares, cost, realized, first, over, sharesToday, costToday)
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
