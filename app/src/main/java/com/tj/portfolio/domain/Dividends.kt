package com.tj.portfolio.domain

import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * THE DIVIDEND ENGINE (2026-10-22): what has been paid, what is coming, and why each figure is
 * what it is. Pure - no network, no clock, no Android - so every rule below is unit-tested.
 *
 * ACCURACY RULES, in the order they matter:
 *
 *  1. The ledger is the truth for money RECEIVED. A `DIVIDEND` transaction is cash that really
 *     arrived; the provider's history only tells us what we SHOULD have received.
 *  2. Entitlement is shares held at the close BEFORE the ex-date - replayed from the
 *     transactions (BUY/SELL/SPLIT), not read off today's position. Buy the day after the
 *     ex-date and that payment is not yours; sell on the ex-date and it still is.
 *  3. Provider per-share amounts are split-adjusted, so the replayed share count is carried
 *     forward through any LATER split before multiplying.
 *  4. A projection says what it is: DECLARED (ex-date passed, amount fixed), SCHEDULED (a
 *     provider-confirmed future ex-date), or PROJECTED (cadence roll-forward). Never shown as
 *     fact when it is a forecast.
 */

/** One historical payment as the provider reports it: the ex-date and the per-share amount. */
data class DivEvent(val exDate: LocalDate, val amount: Double)

/** What the outside world says about one symbol. All optional but [symbol]. */
data class DivInput(
    val symbol: String,
    val name: String = "",
    val price: Double = 0.0,
    val isEtf: Boolean = false,
    val history: List<DivEvent> = emptyList(),
    /** The provider's next ex-dividend date, when it states one. */
    val calExDate: LocalDate? = null,
    /** The provider's pay date for [calExDate]'s dividend, when it states one. */
    val calPayDate: LocalDate? = null,
    /** The provider's forward annual dividend per share. */
    val annualRate: Double? = null
)

/** A currently held position: what the engine multiplies by for anything still ahead. */
data class DivHeld(val symbol: String, val shares: Double, val costBasis: Double)

enum class DivStatus(val label: String) {
    /** Matched to a DIVIDEND transaction in the ledger - real money. */
    RECEIVED("Received"),
    /** Paid per the provider's history and your shares at the time, but not in your ledger. */
    CALCULATED("Calculated"),
    /** Ex-date has passed, amount is fixed, cash not yet landed. */
    DECLARED("Declared"),
    /** A future ex-date the provider has confirmed; the amount is an estimate. */
    SCHEDULED("Scheduled"),
    /** Rolled forward from the payment pattern. */
    PROJECTED("Projected")
}

data class DivItem(
    val symbol: String,
    val exDate: LocalDate?,
    val payDate: LocalDate,
    val payEstimated: Boolean,
    val perShare: Double,
    val shares: Double,
    val total: Double,
    val status: DivStatus,
    val special: Boolean = false,
    /** One line that says where the number came from. */
    val note: String = ""
) {
    val isPast: Boolean get() = status == DivStatus.RECEIVED || status == DivStatus.CALCULATED
}

/** One held symbol's dividend profile. */
data class DivHolding(
    val symbol: String,
    val name: String,
    val shares: Double,
    val price: Double,
    val pays: Boolean,
    val frequency: String,
    val perPayment: Double,
    val annualPerShare: Double,
    val yieldPct: Double,
    val yieldOnCostPct: Double,
    val annualIncome: Double,
    val lastExDate: LocalDate?,
    val nextExDate: LocalDate?,
    val nextPayDate: LocalDate?,
    val nextTotal: Double,
    val receivedTotal: Double,
    /** Why this symbol has no upcoming rows: "", "no dividend history", "no recent dividend". */
    val caveat: String = ""
)

data class DividendReport(
    val today: LocalDate,
    val past: List<DivItem>,
    val upcoming: List<DivItem>,
    val holdings: List<DivHolding>,
    val totalPaid: Double,
    val totalRecorded: Double,
    val totalCalculated: Double,
    val trailing12m: Double,
    val ytd: Double,
    val next30d: Double,
    val next12m: Double,
    val annualIncome: Double,
    /** Forward annual income over the market value of EVERYTHING held. */
    val portfolioYieldPct: Double,
    val payerCount: Int,
    val heldCount: Int
) {
    val nextPayout: DivItem? get() = upcoming.minByOrNull { it.payDate }
}

object Dividends {

    private val ET: ZoneId = ZoneId.of("America/New_York")

    /** A held symbol must reach the ex-date with at least this many shares to be entitled. */
    private const val EPS = 1e-9

    /** Window around an ex-date inside which a ledger DIVIDEND row is that payment. */
    private const val MATCH_BEFORE_DAYS = 5L
    private const val MATCH_AFTER_DAYS = 90L
    private const val MATCH_TOLERANCE = 0.25

    /** A payment this long overdue against its ESTIMATED pay date has probably just landed. */
    private const val DUE_GRACE_DAYS = 7L

    const val HORIZON_DAYS = 365L

    fun etDate(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(ET).toLocalDate()

    // ===================================================================== entitlement

    /**
     * Shares of [symbol] entitled to a dividend with ex-date [exDate], in TODAY'S split terms.
     *
     * Held at the close before the ex-date means: every trade dated strictly BEFORE it counts.
     * (T+1 settlement: a buy the day before the ex-date settles on it, and is entitled.)
     */
    fun sharesEntitled(txns: List<Txn>, symbol: String, exDate: LocalDate): Double {
        val sym = symbol.uppercase()
        var shares = 0.0
        var laterSplits = 1.0
        val ordered = txns.filter { it.symbol?.uppercase() == sym }
            .sortedWith(compareBy({ it.date }, { it.id }))
        for (t in ordered) {
            val d = etDate(t.date)
            when (t.type) {
                TxnType.BUY -> if (d < exDate) shares += abs(t.quantity)
                TxnType.SELL -> if (d < exDate) shares = (shares - abs(t.quantity)).coerceAtLeast(0.0)
                TxnType.SPLIT -> {
                    val r = TxnType.splitRatio(t)
                    if (r > 0.0) {
                        if (d < exDate) shares *= r else laterSplits *= r
                    }
                }
            }
        }
        return shares * laterSplits
    }

    // ===================================================================== cadence

    /** Regular payments only; [special] are extra, off-pattern payments. */
    internal data class Split(val regular: List<DivEvent>, val special: Set<DivEvent>)

    internal fun medianGapDays(events: List<DivEvent>): Double {
        if (events.size < 2) return 0.0
        val tail = events.takeLast(8)
        val gaps = tail.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.exDate, b.exDate).toDouble() }
            .filter { it > 0 }.sorted()
        if (gaps.isEmpty()) return 0.0
        val m = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[m] else (gaps[m - 1] + gaps[m]) / 2.0
    }

    internal fun splitSpecials(sorted: List<DivEvent>): Split {
        val m = medianGapDays(sorted)
        if (sorted.size < 3 || m <= 0.0) return Split(sorted, emptySet())
        val amounts = sorted.takeLast(8).map { it.amount }.sorted()
        val medAmt = amounts[amounts.size / 2]
        val special = LinkedHashSet<DivEvent>()
        val regular = ArrayList<DivEvent>()
        for ((i, e) in sorted.withIndex()) {
            if (regular.isEmpty()) { regular.add(e); continue }
            val prev = regular.last()
            val gap = ChronoUnit.DAYS.between(prev.exDate, e.exDate).toDouble()
            val next = sorted.getOrNull(i + 1)
            val bigAmount = medAmt > 0 && e.amount >= 2.5 * medAmt
            val offCadence = gap < 0.8 * m
            val removingRestores = next != null &&
                abs(ChronoUnit.DAYS.between(prev.exDate, next.exDate) - m) <= 0.25 * m
            val isSpecial = when {
                m < 12.0 -> false                                   // weekly payers: every gap is short
                offCadence && next != null -> removingRestores || bigAmount
                offCadence -> gap < 0.6 * m || bigAmount            // the newest row has no "next"
                else -> bigAmount && gap < 0.9 * m
            }
            if (isSpecial) special.add(e) else regular.add(e)
        }
        return Split(regular, special)
    }

    /** Payments per year from the typical gap; 0 when it cannot be told. */
    internal fun paymentsPerYear(regular: List<DivEvent>): Int {
        val m = medianGapDays(regular)
        return when {
            m <= 0.0 -> 0
            m < 10.0 -> 52
            m < 45.0 -> 12
            m < 75.0 -> 6
            m < 135.0 -> 4
            m < 270.0 -> 2
            else -> 1
        }
    }

    fun frequencyLabel(ppy: Int): String = when (ppy) {
        52 -> "Weekly"
        12 -> "Monthly"
        6 -> "Every 2 months"
        4 -> "Quarterly"
        2 -> "Semi-annual"
        1 -> "Annual"
        else -> "Irregular"
    }

    private fun stepMonths(ppy: Int): Int = when (ppy) { 12 -> 1; 6 -> 2; 4 -> 3; 2 -> 6; 1 -> 12; else -> 0 }

    /** Ex-dates fall on trading days; a computed one that lands on a weekend moves back. */
    internal fun tradingDayOnOrBefore(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.minusDays(2)
        else -> d
    }

    internal fun businessDayOnOrAfter(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.plusDays(2)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    /** The ex-date [k] cadence steps after [anchor]. Computed from the anchor, so no drift. */
    internal fun rollForward(anchor: LocalDate, ppy: Int, k: Int): LocalDate =
        if (ppy == 52) anchor.plusWeeks(k.toLong())
        else tradingDayOnOrBefore(anchor.plusMonths((stepMonths(ppy) * k).toLong()))

    // ===================================================================== amounts

    /**
     * The per-share amount to expect for the NEXT regular payment.
     *
     * A stable payer (amounts within ~4% of each other) pays what it paid last - unless the
     * provider's forward rate shows a raise it has already declared. A variable payer (funds,
     * REITs, anything whose recent amounts wander) is better served by an average than by
     * whichever month happened to be last.
     */
    internal fun nextAmount(regular: List<DivEvent>, ppy: Int, annualRate: Double?): Double {
        if (regular.isEmpty()) return 0.0
        val latest = regular.last().amount
        val window = regular.takeLast(if (ppy >= 12) 6 else 4).map { it.amount }
        val mean = window.average()
        val cv = if (window.size >= 2 && mean > 0) {
            sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size) / mean
        } else 0.0
        if (cv > 0.04) return mean
        val rateAmt = if (annualRate != null && annualRate > 0 && ppy > 0) annualRate / ppy else 0.0
        return if (rateAmt > latest * 1.005 && rateAmt < latest * 1.5) rateAmt else latest
    }

    // ===================================================================== build

    fun build(
        today: LocalDate,
        txns: List<Txn>,
        held: List<DivHeld>,
        inputs: List<DivInput>
    ): DividendReport {
        val bySym = inputs.associateBy { it.symbol.uppercase() }
        val heldBySym = held.filter { it.shares > EPS }.associateBy { it.symbol.uppercase() }
        val divTxns = txns.filter { it.type == TxnType.DIVIDEND && !it.symbol.isNullOrBlank() }
            .groupBy { it.symbol!!.uppercase() }

        val past = ArrayList<DivItem>()
        val upcoming = ArrayList<DivItem>()
        val holdings = ArrayList<DivHolding>()

        // Every symbol that ever paid you OR that you hold now: a sold-out position's
        // dividends are still dividends you earned.
        val symbols = (heldBySym.keys + divTxns.keys + bySym.keys).toSortedSet()

        for (sym in symbols) {
            val input = bySym[sym] ?: DivInput(sym)
            val h = heldBySym[sym]
            val sorted = input.history.filter { it.amount > 0 }.sortedBy { it.exDate }
            val sp = splitSpecials(sorted)
            val ppy = paymentsPerYear(sp.regular)

            // ---- 1. history -> past rows, matched against the ledger
            val unmatched = (divTxns[sym] ?: emptyList()).sortedBy { it.date }.toMutableList()
            val lags = ArrayList<Long>()
            val pastRows = ArrayList<DivItem>()
            val pending = ArrayList<DivItem>()
            val yahooLag = if (input.calExDate != null && input.calPayDate != null) {
                ChronoUnit.DAYS.between(input.calExDate, input.calPayDate).takeIf { it in 0..120 }
            } else null

            // First pass over events only to learn the typical pay lag from matched rows.
            data class Pre(val e: DivEvent, val shares: Double, val txn: Txn?)
            val pre = ArrayList<Pre>()
            for (e in sorted) {
                val shares = sharesEntitled(txns, sym, e.exDate)
                if (shares <= EPS) continue
                val expected = shares * e.amount
                val hit = unmatched.filter {
                    val d = etDate(it.date)
                    d >= e.exDate.minusDays(MATCH_BEFORE_DAYS) && d <= e.exDate.plusDays(MATCH_AFTER_DAYS) &&
                        abs(abs(it.amount) - expected) <= MATCH_TOLERANCE * expected
                }.minByOrNull { abs(abs(it.amount) - expected) }
                if (hit != null) {
                    unmatched.remove(hit)
                    lags.add(ChronoUnit.DAYS.between(e.exDate, etDate(hit.date)))
                }
                pre.add(Pre(e, shares, hit))
            }
            val learnedLag = lags.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val lag = learnedLag ?: yahooLag ?: if (input.isEtf) 4L else 21L

            for (p in pre) {
                val e = p.e
                val isSpecial = e in sp.special
                if (p.txn != null) {
                    pastRows.add(DivItem(sym, e.exDate, etDate(p.txn.date), false, abs(p.txn.amount) / p.shares,
                        p.shares, abs(p.txn.amount), DivStatus.RECEIVED, isSpecial,
                        "In your ledger"))
                    continue
                }
                val confirmedPay = input.calExDate == e.exDate && input.calPayDate != null
                val pay = if (confirmedPay) input.calPayDate!!
                else businessDayOnOrAfter(e.exDate.plusDays(lag))
                val status = when {
                    e.exDate > today -> DivStatus.SCHEDULED
                    pay > today -> DivStatus.DECLARED
                    // An ESTIMATED pay date a few days gone with nothing in the ledger yet is
                    // far more likely a guess that was a little early than a payment that
                    // never came: keep it in "upcoming" for a week rather than book it.
                    !confirmedPay && pay.plusDays(DUE_GRACE_DAYS) >= today -> DivStatus.DECLARED
                    else -> DivStatus.CALCULATED
                }
                val row = DivItem(sym, e.exDate, pay, !confirmedPay, e.amount, p.shares, p.shares * e.amount,
                    status, isSpecial,
                    when (status) {
                        DivStatus.CALCULATED -> "Per-share amount x your shares at the ex-date; not in your ledger"
                        DivStatus.DECLARED ->
                            if (pay > today) "Ex-date passed - amount is fixed"
                            else "Should land any day - pay date is estimated"
                        else -> "Ex-date confirmed"
                    })
                if (status == DivStatus.CALCULATED) pastRows.add(row) else pending.add(row)
            }
            // Ledger rows no provider event explains (sold-out holdings, older than the
            // provider's history window, a differently-timed record): still money received.
            for (t in unmatched) {
                val d = etDate(t.date)
                past.add(DivItem(sym, null, d, false, 0.0, 0.0, abs(t.amount), DivStatus.RECEIVED, false,
                    "In your ledger"))
            }
            past.addAll(pastRows)
            upcoming.addAll(pending)

            // ---- 2. what is still ahead
            val lastRegular = sp.regular.lastOrNull()
            val recent = lastRegular != null && ppy > 0 &&
                ChronoUnit.DAYS.between(lastRegular.exDate, today) <= (365.0 / ppy * 1.6).toLong() + 14
            val calFuture = input.calExDate?.takeIf { it >= today && (lastRegular == null || it > lastRegular.exDate.plusDays(3)) }
            val perPay = nextAmount(sp.regular, ppy, input.annualRate)
            // One lone payment is all the pattern there is; with none, a quarter of the forward
            // rate (quarterly is by far the commonest US schedule) is the least-bad guess.
            val fallbackAmt = when {
                perPay > 0 -> perPay
                sorted.isNotEmpty() -> sorted.last().amount
                input.annualRate != null && input.annualRate > 0 -> input.annualRate / 4.0
                else -> 0.0
            }
            val nowShares = h?.shares ?: 0.0

            var caveat = ""
            val ahead = ArrayList<DivItem>()
            if (h != null && nowShares > EPS) {
                var anchor: LocalDate? = null
                if (calFuture != null && fallbackAmt > 0) {
                    val pay = input.calPayDate?.takeIf { it >= calFuture } ?: businessDayOnOrAfter(calFuture.plusDays(lag))
                    ahead.add(DivItem(sym, calFuture, pay, input.calPayDate == null, fallbackAmt, nowShares,
                        nowShares * fallbackAmt, DivStatus.SCHEDULED, false,
                        if (input.calPayDate != null) "Dates confirmed; amount estimated from recent payments"
                        else "Ex-date confirmed; pay date and amount estimated"))
                    anchor = calFuture
                }
                if (ppy > 0 && recent && perPay > 0) {
                    val base = anchor ?: lastRegular!!.exDate
                    var k = 1
                    // Steps from the last paid event (or the confirmed one) until the horizon;
                    // slots already in the past are skipped, not shown.
                    while (k < 400) {
                        val ex = rollForward(base, ppy, k)
                        k++
                        if (ex < today) continue
                        if (ChronoUnit.DAYS.between(today, ex) > HORIZON_DAYS) break
                        // A provider-confirmed row already covers this slot.
                        if (ahead.any { abs(ChronoUnit.DAYS.between(it.exDate, ex)) <= 10 } ) continue
                        // Already declared (in `pending`) - do not duplicate it as a forecast.
                        if (pending.any { abs(ChronoUnit.DAYS.between(it.exDate, ex)) <= 10 }) continue
                        val pay = businessDayOnOrAfter(ex.plusDays(lag))
                        ahead.add(DivItem(sym, ex, pay, true, perPay, nowShares, nowShares * perPay,
                            DivStatus.PROJECTED, false,
                            "Projected from your ${frequencyLabel(ppy).lowercase()} payment pattern"))
                    }
                } else if (sorted.isEmpty() && calFuture == null) {
                    caveat = "No dividend history"
                } else if (!recent && calFuture == null) {
                    caveat = "No recent dividend"
                }
            }
            upcoming.addAll(ahead)

            // ---- 3. the holding's profile
            if (h != null && nowShares > EPS) {
                val annualPerShare = when {
                    ppy > 0 && perPay > 0 && recent -> perPay * ppy
                    input.annualRate != null && input.annualRate > 0 && calFuture != null -> input.annualRate
                    input.annualRate != null && input.annualRate > 0 && recent -> input.annualRate
                    else -> 0.0
                }
                val income = annualPerShare * nowShares
                val mine = (pending + ahead).sortedBy { it.payDate }
                val next = mine.firstOrNull()
                val recvd = past.filter { it.symbol == sym }.sumOf { it.total }
                val pays = annualPerShare > 0
                holdings.add(DivHolding(
                    symbol = sym, name = input.name, shares = nowShares, price = input.price, pays = pays,
                    frequency = if (ppy > 0) frequencyLabel(ppy) else if (pays) "Irregular" else "-",
                    perPayment = if (pays) perPay else 0.0,
                    annualPerShare = annualPerShare,
                    yieldPct = if (input.price > 0) annualPerShare / input.price * 100.0 else 0.0,
                    yieldOnCostPct = if (h.costBasis > EPS) income / h.costBasis * 100.0 else 0.0,
                    annualIncome = income,
                    lastExDate = sorted.lastOrNull()?.exDate,
                    nextExDate = next?.exDate,
                    nextPayDate = next?.payDate,
                    nextTotal = next?.total ?: 0.0,
                    receivedTotal = recvd,
                    caveat = if (pays) "" else caveat.ifEmpty { "Does not pay a dividend" }
                ))
            }
        }

        val pastSorted = past.sortedByDescending { it.payDate }
        val upSorted = upcoming.sortedWith(compareBy({ it.payDate }, { it.symbol }))
        val recorded = pastSorted.filter { it.status == DivStatus.RECEIVED }.sumOf { it.total }
        val calculated = pastSorted.filter { it.status == DivStatus.CALCULATED }.sumOf { it.total }
        val yearStart = LocalDate.of(today.year, 1, 1)
        val ttmStart = today.minusDays(365)
        val horizon30 = today.plusDays(30)
        val value = held.sumOf { hd -> (bySym[hd.symbol.uppercase()]?.price ?: 0.0) * hd.shares }
        val income = holdings.sumOf { it.annualIncome }
        return DividendReport(
            today = today,
            past = pastSorted,
            upcoming = upSorted,
            holdings = holdings.sortedWith(compareByDescending<DivHolding> { it.annualIncome }.thenBy { it.symbol }),
            totalPaid = recorded + calculated,
            totalRecorded = recorded,
            totalCalculated = calculated,
            trailing12m = pastSorted.filter { it.payDate > ttmStart }.sumOf { it.total },
            ytd = pastSorted.filter { it.payDate >= yearStart }.sumOf { it.total },
            next30d = upSorted.filter { it.payDate <= horizon30 }.sumOf { it.total },
            next12m = upSorted.sumOf { it.total },
            annualIncome = income,
            portfolioYieldPct = if (value > EPS) income / value * 100.0 else 0.0,
            payerCount = holdings.count { it.pays },
            heldCount = holdings.size
        )
    }
}
