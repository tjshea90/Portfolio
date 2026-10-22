package com.tj.portfolio

import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.DivEvent
import com.tj.portfolio.domain.DivHeld
import com.tj.portfolio.domain.DivInput
import com.tj.portfolio.domain.DivStatus
import com.tj.portfolio.domain.Dividends
import com.tj.portfolio.net.DividendFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The dividend engine + feed: entitlement, matching, cadence, projection, parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DividendsTest {

    private val today = LocalDate.of(2026, 10, 22)
    private fun ms(d: LocalDate) = d.atTime(12, 0).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private var nextId = 1L
    private fun buy(sym: String, d: LocalDate, qty: Double, px: Double = 10.0) =
        Txn(id = nextId++, type = TxnType.BUY, symbol = sym, quantity = qty, price = px, amount = -qty * px, date = ms(d))
    private fun sell(sym: String, d: LocalDate, qty: Double) =
        Txn(id = nextId++, type = TxnType.SELL, symbol = sym, quantity = qty, price = 10.0, amount = qty * 10.0, date = ms(d))
    private fun div(sym: String, d: LocalDate, amt: Double) =
        Txn(id = nextId++, type = TxnType.DIVIDEND, symbol = sym, amount = amt, date = ms(d))
    private fun split(sym: String, d: LocalDate, ratio: Double) =
        Txn(id = nextId++, type = TxnType.SPLIT, symbol = sym, quantity = ratio, date = ms(d))

    /** A quarterly payer: ex-dates on the 10th of Mar/Jun/Sep/Dec for [years] years ending before today. */
    private fun quarterly(amount: Double, from: LocalDate = LocalDate.of(2024, 3, 8)): List<DivEvent> {
        val out = ArrayList<DivEvent>()
        var d = from
        while (d < today.minusDays(5)) { out.add(DivEvent(d, amount)); d = d.plusMonths(3) }
        return out
    }

    // ------------------------------------------------------------------ entitlement

    @Test fun entitlementIsHeldAtCloseBeforeExDate() {
        val ex = LocalDate.of(2026, 9, 10)
        val t = listOf(
            buy("A", ex.minusDays(1), 100.0),      // bought the day before: entitled
            buy("A", ex, 50.0),                    // bought ON the ex-date: not entitled
            buy("A", ex.plusDays(3), 25.0)
        )
        assertEquals(100.0, Dividends.sharesEntitled(t, "A", ex), 1e-9)
    }

    @Test fun sellingBeforeExDateLosesItSellingOnExDateKeepsIt() {
        val ex = LocalDate.of(2026, 9, 10)
        val keep = listOf(buy("A", ex.minusDays(30), 100.0), sell("A", ex, 100.0))
        val lose = listOf(buy("A", ex.minusDays(30), 100.0), sell("A", ex.minusDays(1), 100.0))
        assertEquals(100.0, Dividends.sharesEntitled(keep, "A", ex), 1e-9)
        assertEquals(0.0, Dividends.sharesEntitled(lose, "A", ex), 1e-9)
    }

    @Test fun laterSplitScalesEarlierShareCountToTodaysTerms() {
        val ex = LocalDate.of(2026, 3, 10)
        val t = listOf(buy("N", LocalDate.of(2026, 1, 5), 10.0), split("N", LocalDate.of(2026, 6, 1), 10.0))
        // Provider amounts are split-adjusted, so 10 shares then == 100 shares in today's terms.
        assertEquals(100.0, Dividends.sharesEntitled(t, "N", ex), 1e-9)
        // A split BEFORE the ex-date is already inside the replayed share count.
        assertEquals(100.0, Dividends.sharesEntitled(t, "N", LocalDate.of(2026, 7, 1)), 1e-9)
    }

    // ------------------------------------------------------------------ cadence

    @Test fun cadenceIsDetectedFromGaps() {
        fun ppy(months: Int): Int {
            val ev = (0 until 8).map { DivEvent(LocalDate.of(2024, 1, 10).plusMonths((it * months).toLong()), 1.0) }
            return Dividends.paymentsPerYear(ev)
        }
        assertEquals(12, ppy(1)); assertEquals(4, ppy(3)); assertEquals(2, ppy(6)); assertEquals(1, ppy(12))
    }

    @Test fun specialDividendIsExcludedFromTheRunRate() {
        val ev = quarterly(0.50).toMutableList()
        ev.add(DivEvent(LocalDate.of(2025, 11, 20), 4.00))     // off-cadence, 8x the regular
        val sorted = ev.sortedBy { it.exDate }
        val sp = Dividends.splitSpecials(sorted)
        assertEquals(setOf(DivEvent(LocalDate.of(2025, 11, 20), 4.00)), sp.special)
        assertEquals(4, Dividends.paymentsPerYear(sp.regular))
        assertEquals(0.50, Dividends.nextAmount(sp.regular, 4, null), 1e-9)
    }

    @Test fun stableUsesLatestVariableUsesMean() {
        val stable = (0 until 6).map { DivEvent(LocalDate.of(2025, 1, 10).plusMonths(it.toLong()), 0.30) }
        assertEquals(0.30, Dividends.nextAmount(stable, 12, null), 1e-9)
        val amts = listOf(0.50, 0.40, 0.60, 0.45, 0.55, 0.30)
        val wobbly = amts.mapIndexed { i, a -> DivEvent(LocalDate.of(2025, 1, 10).plusMonths(i.toLong()), a) }
        assertEquals(amts.average(), Dividends.nextAmount(wobbly, 12, null), 1e-9)
    }

    @Test fun declaredRaiseInProviderRateIsPickedUp() {
        val ev = (0 until 4).map { DivEvent(LocalDate.of(2025, 3, 10).plusMonths((it * 3).toLong()), 1.00) }
        assertEquals(1.10, Dividends.nextAmount(ev, 4, 4.40), 1e-9)       // 4.40/4 = 1.10 > 1.00
        assertEquals(1.00, Dividends.nextAmount(ev, 4, 4.00), 1e-9)
    }

    @Test fun rollForwardHasNoDayOfMonthDriftAndSkipsWeekends() {
        val anchor = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 27), Dividends.rollForward(anchor, 12, 1))   // Feb 28 is a Saturday
        assertEquals(LocalDate.of(2026, 3, 31), Dividends.rollForward(anchor, 12, 2))   // back to the 31st
    }

    // ------------------------------------------------------------------ the report

    @Test fun quarterlyPayerProjectsFourPaymentsAtTheLatestAmount() {
        val ev = quarterly(0.50)
        val txns = listOf(buy("KO", LocalDate.of(2023, 1, 5), 100.0, 60.0))
        val r = Dividends.build(today, txns, listOf(DivHeld("KO", 100.0, 6000.0)),
            listOf(DivInput("KO", price = 70.0, history = ev)))
        val ahead = r.upcoming.filter { it.symbol == "KO" }
        assertEquals(4, ahead.size)
        assertTrue(ahead.all { it.status == DivStatus.PROJECTED && it.perShare == 0.50 && it.total == 50.0 })
        assertTrue(ahead.all { it.payDate > it.exDate })
        val h = r.holdings.single()
        assertEquals("Quarterly", h.frequency)
        assertEquals(2.00, h.annualPerShare, 1e-9)
        assertEquals(200.0, h.annualIncome, 1e-9)
        assertEquals(2.00 / 70.0 * 100.0, h.yieldPct, 1e-9)
        assertEquals(200.0 / 6000.0 * 100.0, h.yieldOnCostPct, 1e-9)
        assertEquals(200.0, r.next12m, 1e-9)
    }

    @Test fun ledgerRowsAreReceivedAndTheRestIsCalculated() {
        val ev = quarterly(0.50, from = LocalDate.of(2025, 3, 10))
        val ledgerPay = ev[1].exDate.plusDays(14)                        // Jun ex-date, paid Jun 24
        val txns = listOf(buy("KO", LocalDate.of(2025, 1, 2), 100.0), div("KO", ledgerPay, 49.0))
        val r = Dividends.build(today, txns, listOf(DivHeld("KO", 100.0, 1000.0)),
            listOf(DivInput("KO", price = 70.0, history = ev)))
        val rec = r.past.filter { it.status == DivStatus.RECEIVED }
        assertEquals(1, rec.size)
        assertEquals(49.0, rec.single().total, 1e-9)                      // the LEDGER amount wins
        assertEquals(ledgerPay, rec.single().payDate)
        val calc = r.past.filter { it.status == DivStatus.CALCULATED }
        assertTrue(calc.isNotEmpty())
        assertEquals(49.0, r.totalRecorded, 1e-9)
        assertEquals(calc.sumOf { it.total }, r.totalCalculated, 1e-9)
        assertEquals(r.totalRecorded + r.totalCalculated, r.totalPaid, 1e-9)
    }

    @Test fun nothingIsCreditedForPaymentsBeforeYouOwnedIt() {
        val ev = quarterly(0.50)
        val bought = LocalDate.of(2026, 9, 1)                             // after every ex-date but...
        val txns = listOf(buy("KO", bought, 100.0))
        val r = Dividends.build(today, txns, listOf(DivHeld("KO", 100.0, 1000.0)),
            listOf(DivInput("KO", price = 70.0, history = ev)))
        // ...the Sep 10 ex-date is after the buy, so exactly that one is owed.
        assertEquals(1, r.past.size + r.upcoming.count { it.status == DivStatus.DECLARED })
    }

    @Test fun declaredPaymentUsesSharesHeldAtTheExDateNotToday() {
        val ex = today.minusDays(5)
        val ev = quarterly(0.50, from = LocalDate.of(2025, 6, 10)) + DivEvent(ex, 0.50)
        val txns = listOf(buy("KO", LocalDate.of(2025, 1, 2), 100.0), buy("KO", today.minusDays(2), 400.0))
        val r = Dividends.build(today, txns, listOf(DivHeld("KO", 500.0, 5000.0)),
            listOf(DivInput("KO", price = 70.0, history = ev, calExDate = ex, calPayDate = today.plusDays(9))))
        val declared = r.upcoming.single { it.status == DivStatus.DECLARED }
        assertEquals(100.0, declared.shares, 1e-9)                        // not the 500 held now
        assertEquals(50.0, declared.total, 1e-9)
        assertEquals(today.plusDays(9), declared.payDate)
        assertFalse(declared.payEstimated)
        // Projections after it are for the shares held NOW.
        assertTrue(r.upcoming.filter { it.status == DivStatus.PROJECTED }.all { it.shares == 500.0 })
    }

    @Test fun providerFutureExDateBecomesScheduledAndProjectionContinuesFromIt() {
        val ev = quarterly(0.50)
        val nextEx = LocalDate.of(2026, 12, 11)
        val r = Dividends.build(today, listOf(buy("KO", LocalDate.of(2023, 1, 5), 100.0)),
            listOf(DivHeld("KO", 100.0, 5000.0)),
            listOf(DivInput("KO", price = 70.0, history = ev, calExDate = nextEx, calPayDate = LocalDate.of(2026, 12, 31))))
        val sched = r.upcoming.filter { it.symbol == "KO" }.first()
        assertEquals(DivStatus.SCHEDULED, sched.status)
        assertEquals(nextEx, sched.exDate)
        assertEquals(LocalDate.of(2026, 12, 31), sched.payDate)
        assertFalse(sched.payEstimated)
        val projected = r.upcoming.filter { it.status == DivStatus.PROJECTED }
        assertTrue(projected.all { it.exDate!! > nextEx })
        assertEquals(1, r.upcoming.count { it.status == DivStatus.SCHEDULED })
    }

    @Test fun payLagIsLearnedFromTheLedger() {
        val ev = quarterly(0.50, from = LocalDate.of(2025, 3, 10))
        val txns = listOf(buy("KO", LocalDate.of(2025, 1, 2), 100.0)) +
            ev.take(3).map { div("KO", it.exDate.plusDays(30), 50.0) }
        val r = Dividends.build(today, txns, listOf(DivHeld("KO", 100.0, 5000.0)),
            listOf(DivInput("KO", price = 70.0, history = ev)))
        val p = r.upcoming.first { it.status == DivStatus.PROJECTED }
        val lagDays = java.time.temporal.ChronoUnit.DAYS.between(p.exDate, p.payDate)
        assertTrue("lag $lagDays", lagDays in 30..32)                    // 30, rolled off a weekend
    }

    @Test fun lapsedPayerGetsNoProjectionAndSaysWhy() {
        val ev = (0 until 4).map { DivEvent(LocalDate.of(2023, 3, 10).plusMonths((it * 3).toLong()), 0.5) }
        val r = Dividends.build(today, listOf(buy("OLD", LocalDate.of(2022, 1, 5), 10.0)),
            listOf(DivHeld("OLD", 10.0, 100.0)), listOf(DivInput("OLD", price = 5.0, history = ev)))
        assertTrue(r.upcoming.isEmpty())
        val h = r.holdings.single()
        assertFalse(h.pays)
        assertEquals("No recent dividend", h.caveat)
    }

    @Test fun nonPayerIsListedAsSuch() {
        val r = Dividends.build(today, listOf(buy("TSLA", LocalDate.of(2024, 1, 5), 5.0)),
            listOf(DivHeld("TSLA", 5.0, 1000.0)), listOf(DivInput("TSLA", price = 400.0)))
        assertFalse(r.holdings.single().pays)
        assertEquals("No dividend history", r.holdings.single().caveat.ifEmpty { "No dividend history" })
        assertEquals(0, r.payerCount); assertEquals(1, r.heldCount)
        assertEquals(0.0, r.totalPaid, 0.0)
    }

    @Test fun soldOutPositionStillCountsWhatItPaid() {
        val ev = quarterly(0.50, from = LocalDate.of(2025, 3, 10))
        val txns = listOf(buy("GONE", LocalDate.of(2025, 1, 2), 100.0), sell("GONE", LocalDate.of(2025, 9, 20), 100.0),
            div("GONE", LocalDate.of(2025, 3, 30), 50.0))
        val r = Dividends.build(today, txns, emptyList(), listOf(DivInput("GONE", history = ev)))
        assertTrue(r.holdings.isEmpty()); assertTrue(r.upcoming.isEmpty())
        assertTrue(r.totalPaid >= 50.0)                                   // received (+ calculated for Jun/Sep)
        assertTrue(r.past.any { it.status == DivStatus.RECEIVED && it.total == 50.0 })
    }

    @Test fun unexplainedLedgerDividendStillCounts() {
        val txns = listOf(buy("X", LocalDate.of(2024, 1, 5), 10.0), div("X", LocalDate.of(2026, 5, 5), 12.34))
        val r = Dividends.build(today, txns, listOf(DivHeld("X", 10.0, 100.0)), listOf(DivInput("X", price = 12.0)))
        assertEquals(12.34, r.totalRecorded, 1e-9)
        assertNull(r.past.single().exDate)
    }

    // ------------------------------------------------------------------ feed parsing

    @Test fun chartDividendEventsAreParsed() {
        val body = """{"chart":{"result":[{"meta":{"symbol":"KO"},"timestamp":[1],"events":{"dividends":{
            "1757511000":{"amount":0.51,"date":1757511000},
            "1749562200":{"amount":0.51,"date":1749562200},
            "1757511001":{"amount":0.0,"date":1757511001}}}}]}}"""
        val ev = DividendFeed.parseHistory(body)!!
        assertEquals(2, ev.size)
        assertEquals(LocalDate.of(2025, 6, 10), ev[0].exDate)
        assertEquals(LocalDate.of(2025, 9, 10), ev[1].exDate)
        assertEquals(0.51, ev[0].amount, 1e-9)
    }

    @Test fun noEventsMeansNoDividendsButGarbageMeansNoAnswer() {
        assertEquals(emptyList<DivEvent>(), DividendFeed.parseHistory("""{"chart":{"result":[{"meta":{"symbol":"TSLA"}}]}}"""))
        assertNull(DividendFeed.parseHistory("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""))
    }

    @Test fun calendarMidnightUtcStaysOnTheSameDay() {
        val res = org.json.JSONObject("""{"calendarEvents":{"exDividendDate":{"raw":1761868800},"dividendDate":{"raw":1763078400}},
            "summaryDetail":{"dividendRate":{"raw":2.04},"dividendYield":{"raw":3.1}}}""")
        val c = DividendFeed.parseCalendar(res)
        assertEquals(LocalDate.of(2025, 10, 31), c.exDate)               // 00:00 UTC, NOT Oct 30 in New York
        assertEquals(LocalDate.of(2025, 11, 14), c.payDate)
        assertEquals(2.04, c.annualRate!!, 1e-9)
        assertEquals(0.031, c.yieldFraction!!, 1e-9)                      // percent form normalised
    }

    @Test fun cacheRoundTrips() {
        val ev = listOf(DivEvent(LocalDate.of(2026, 3, 10), 0.5), DivEvent(LocalDate.of(2026, 6, 10), 0.52))
        val back = DividendFeed.decodeHistory(DividendFeed.encodeHistory(ev, 99L))!!
        assertEquals(99L, back.first); assertEquals(ev, back.second)
        val c = DividendFeed.Calendar(LocalDate.of(2026, 12, 1), null, 2.0, null, 5L)
        assertEquals(c, DividendFeed.decodeCalendar(DividendFeed.encodeCalendar(c)))
        assertNotNull(c)
    }
}
