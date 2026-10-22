package com.tj.portfolio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.domain.DivHeld
import com.tj.portfolio.domain.DivHolding
import com.tj.portfolio.domain.DivInput
import com.tj.portfolio.domain.DivItem
import com.tj.portfolio.domain.DivStatus
import com.tj.portfolio.domain.DividendReport
import com.tj.portfolio.domain.Dividends
import com.tj.portfolio.util.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val DAY_YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

internal fun divDay(d: LocalDate, today: LocalDate): String =
    if (d.year == today.year) d.format(DAY_FMT) else d.format(DAY_YEAR_FMT)

/** Builds the report from live state. Pure given its arguments; cheap enough for composition. */
internal fun buildDividendReport(
    state: UiState,
    data: IncomeHub.DividendData,
    today: LocalDate
): DividendReport {
    val held = state.rows.filter { !it.watchOnly }.mapNotNull { r ->
        r.position?.takeIf { it.shares > 1e-9 }?.let { DivHeld(r.symbol, it.shares, it.costBasis) }
    }
    val heldSyms = held.map { it.symbol.uppercase() }.toSet()
    val symbols = (heldSyms + data.history.keys).toSortedSet()
    val inputs = symbols.map { s ->
        val row = state.rows.firstOrNull { it.symbol.equals(s, true) }
        val cal = data.calendar[s]
        val qt = row?.quote?.quoteType.orEmpty().uppercase()
        DivInput(
            symbol = s,
            name = row?.name.orEmpty(),
            price = row?.quote?.price ?: 0.0,
            isEtf = qt == "ETF" || qt == "MUTUALFUND",
            history = data.history[s].orEmpty(),
            calExDate = cal?.exDate,
            calPayDate = cal?.payDate,
            annualRate = cal?.annualRate
        )
    }
    return Dividends.build(today, state.txns, held, inputs)
}

/**
 * THE DIVIDENDS SCREEN (2026-10-22): a sub-tab of Portfolio.
 *
 * Refreshes when it is OPENED (the tab being entered from elsewhere, or this sub-tab being
 * selected - both are this composable entering composition) and on a pull. The first paints
 * from the disk cache at once; the numbers then update in place as each symbol answers.
 */
@Composable
fun DividendsScreen(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit
) {
    val data by vm.income.dividends.collectAsState()
    val loading by vm.income.dividendsLoading.collectAsState()
    var pulled by remember { mutableStateOf(false) }

    val allTraded = remember(state.txns) {
        state.txns.mapNotNull { it.symbol?.uppercase() }.distinct()
    }
    val heldSymbols = state.rows.filter { !it.watchOnly && (it.position?.shares ?: 0.0) > 1e-9 }.map { it.symbol }
    val others = allTraded - heldSymbols.map { it.uppercase() }.toSet()

    LaunchedEffect(Unit) { vm.refreshDividends(force = false) }
    // The held set changing (a buy, a sell, first load of positions) should pull its history.
    LaunchedEffect(heldSymbols.sorted()) { if (heldSymbols.isNotEmpty()) vm.refreshDividends(force = false) }
    LaunchedEffect(loading) { if (!loading) pulled = false }

    val today = Dividends.etDate(System.currentTimeMillis())
    val priceSig = state.rows.map { ((it.quote?.price ?: 0.0) * 100).roundToLong() }
    val report = remember(state.txns, state.rows.size, data, priceSig, today) {
        buildDividendReport(state, data, today)
    }

    Refreshable(
        refreshing = pulled && loading,
        onRefresh = { pulled = true; vm.refreshDividends(force = true) }
    ) {
        Column(Modifier.fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
            ) {
                if (heldSymbols.isEmpty()) {
                    item { EmptyNote("Nothing held yet", "Dividends appear here once you own stocks or ETFs.") }
                    return@LazyColumn
                }
                item { SummaryCard(report, loading, data.completedAt) }
                report.nextPayout?.let { next -> item { NextPayoutCard(next, report.today, onOpen) } }

                item { SectionHeader("Upcoming dividends") }
                if (report.upcoming.isEmpty()) {
                    item {
                        Text(
                            if (loading || data.completedAt == 0L) "Checking your holdings…"
                            else "No upcoming dividends found for what you hold.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    val byMonth = report.upcoming.groupBy { it.payDate.withDayOfMonth(1) }
                    byMonth.forEach { (month, rows) ->
                        item(key = "m:$month") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(month.format(MONTH_FMT), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(Fmt.usd(rows.sumOf { it.total }), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                        items(rows, key = { "u:${it.symbol}:${it.exDate}:${it.status}" }) { r ->
                            DivRow(r, report.today, onOpen)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }

                val payers = report.holdings.filter { it.pays }
                item { SectionHeader("Your dividend payers") }
                if (payers.isEmpty()) {
                    item {
                        Text("None of what you hold pays a dividend right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    }
                }
                items(payers, key = { "h:${it.symbol}" }) { h ->
                    HoldingRow(h, report.today, onOpen)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }
                val non = report.holdings.filter { !it.pays }
                if (non.isNotEmpty()) {
                    item {
                        Text(
                            "No dividend: " + non.joinToString(", ") { h ->
                                if (h.caveat == "No recent dividend") "${h.symbol} (stopped)" else h.symbol
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }

                item { SectionHeader("Paid so far") }
                if (report.past.isEmpty()) {
                    item {
                        Text("No dividends found yet for the shares you have held.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    }
                }
                val byYear = report.past.groupBy { it.payDate.year }
                byYear.forEach { (year, rows) ->
                    item(key = "y:$year") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(year.toString(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(Fmt.usd(rows.sumOf { it.total }), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                    items(rows, key = { "p:${it.symbol}:${it.payDate}:${it.exDate}:${it.total}" }) { r ->
                        DivRow(r, report.today, onOpen)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                }

                item { HowItWorks(others.size) }
            }
        }
    }
}

@Composable
private fun EmptyNote(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryCard(r: DividendReport, loading: Boolean, completedAt: Long) {
    StatCard(Modifier.padding(top = 4.dp)) {
        Text("Total dividends paid", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.usd(r.totalPaid), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (r.totalCalculated > 0.005) {
            Text(
                "${Fmt.usd(r.totalRecorded)} in your ledger + ${Fmt.usd(r.totalCalculated)} calculated from the shares " +
                    "you held on each ex-date",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("All of it recorded in your ledger", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mini("This year", Fmt.usd(r.ytd), Modifier.weight(1f))
            Mini("Last 12 months", Fmt.usd(r.trailing12m), Modifier.weight(1f))
            Mini("Next 12 months", Fmt.usd(r.next12m), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mini("Next 30 days", Fmt.usd(r.next30d), Modifier.weight(1f))
            Mini("Yearly income", Fmt.usd(r.annualIncome), Modifier.weight(1f))
            Mini("Portfolio yield", Fmt.pct(r.portfolioYieldPct), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${r.payerCount} of ${r.heldCount} holdings pay a dividend" +
                when {
                    loading -> " · updating…"
                    completedAt > 0 -> " · updated ${Fmt.relative(completedAt)}"
                    else -> ""
                },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NextPayoutCard(n: DivItem, today: LocalDate, onOpen: (String) -> Unit) {
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, n.payDate)
    StatCard(Modifier.padding(top = 8.dp).clickable { onOpen(n.symbol) }) {
        Text("Next payout", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    divDay(n.payDate, today) + (if (n.payEstimated) " (est.)" else "") +
                        when {
                            days <= 0L -> " · today"
                            days == 1L -> " · tomorrow"
                            else -> " · in $days days"
                        },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(Fmt.usd(n.total), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = greenText)
        }
    }
}

@Composable
private fun statusColor(s: DivStatus): Color = when (s) {
    DivStatus.RECEIVED -> greenText
    DivStatus.DECLARED, DivStatus.SCHEDULED -> accentText
    DivStatus.CALCULATED, DivStatus.PROJECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DivRow(r: DivItem, today: LocalDate, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(r.symbol) }.padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.symbol, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Chip(if (r.special) "${r.status.label} · special" else r.status.label, statusColor(r.status))
            }
            val ex = r.exDate?.let { "Ex ${divDay(it, today)} · " }.orEmpty()
            Text(
                ex + (if (r.isPast) "Paid " else "Pays ") + divDay(r.payDate, today) + if (r.payEstimated) " (est.)" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.usd(r.total), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (r.shares > 0 && r.perShare > 0) {
                Text("${Fmt.shares(r.shares)} × ${perShare(r.perShare)}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun perShare(v: Double): String = "$" + String.format(Locale.US, if (v < 1.0) "%.4f" else "%.3f", v)

@Composable
private fun HoldingRow(h: DivHolding, today: LocalDate, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(h.symbol) }.padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.symbol, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(Fmt.pct(h.yieldPct), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = greenText)
            }
            Text(
                "${h.frequency} · ${perShare(h.perPayment)}/share · yield on cost ${Fmt.pct(h.yieldOnCostPct)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            h.nextPayDate?.let {
                Text("Next: ${divDay(it, today)} · ${Fmt.usd(h.nextTotal)}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.usd(h.annualIncome), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("per year", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HowItWorks(otherCount: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { open = !open }.padding(4.dp)) {
        Text(if (open) "How these numbers are worked out  ▲" else "How these numbers are worked out  ▼",
            style = MaterialTheme.typography.labelLarge, color = accentText)
        if (open) {
            Spacer(Modifier.height(6.dp))
            val body = listOf(
                "Received = a Dividend entry in your Activity ledger. That is real money and always wins.",
                "Calculated = the provider paid it, you owned the shares at the close before the ex-date, " +
                    "but it is not in your ledger. Shares × the per-share amount.",
                "You only earn a payment if you bought before the ex-date. Buy on or after it and that payment is not yours; sell on the ex-date and it still is.",
                "Declared = the ex-date has passed, so the amount is fixed. Scheduled = the company has announced the next ex-date; the amount is estimated from recent payments. Projected = rolled forward from the payment pattern.",
                "Pay dates marked (est.) come from your own past ledger dividends when there are any, else the company's last declared gap between ex-date and pay date.",
                "Special one-off dividends are shown when paid but never projected forward.",
                "Variable payers (many ETFs and REITs) use the average of their last few payments; steady payers use their latest payment, or a raise the company has already declared.",
                if (otherCount > 0) "History is also read for $otherCount symbol(s) you no longer hold, so their payments still count." else ""
            ).filter { it.isNotBlank() }
            body.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
