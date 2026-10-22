package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.EarningsResult
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.domain.Dividends
import com.tj.portfolio.net.EarningsFeed
import com.tj.portfolio.net.EarningsOutlook
import com.tj.portfolio.net.EarningsOutlook.Light
import com.tj.portfolio.util.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val AMBER = Color(0xFFF2B01E)

/** One company's report, as the screen lists it. */
internal data class EarnEntry(
    val symbol: String,
    val name: String,
    /** Held directly. */
    val owned: Boolean,
    /** Funds you hold that carry it among their biggest holdings, with the weight (0..1). */
    val viaFunds: List<Pair<String, Double>>,
    val date: EarningsFeed.Date?,
    val day: LocalDate?,
    val daysAway: Long,
    val outlook: EarningsOutlook.Result,
    /** The result, once reported. */
    val reported: EarningsResult?,
    val epsExpected: Double?,
    val headlines: List<NewsItem>,
    /** A held fund: its row stands for the fund, and [children] are its top holdings reporting. */
    val isFund: Boolean = false,
    val children: List<Pair<EarnEntry, Double>> = emptyList()
)

internal data class EarningsBoard(
    val upcoming: List<EarnEntry>,
    val recent: List<EarnEntry>,
    val undated: List<EarnEntry>
)

/** Reports this recent still show, with their result. */
private const val RECENT_DAYS = 14L

/** How the pieces become one board. Pure given its arguments, so it is tested. */
internal fun buildEarningsBoard(
    state: UiState,
    fundamentals: Map<String, Fundamentals>,
    dates: Map<String, EarningsFeed.Date>,
    headlines: Map<String, List<NewsItem>>,
    holdings: Map<String, FundHoldings>,
    today: LocalDate,
    now: Long
): EarningsBoard {
    val held = state.rows.filter { !it.watchOnly && (it.position?.shares ?: 0.0) > 1e-9 }
    fun isFund(r: Row): Boolean = r.quote?.quoteType.orEmpty().uppercase().let { it == "ETF" || it == "MUTUALFUND" }
    val owned = held.filter { !isFund(it) }.associateBy { it.symbol.uppercase() }
    val via = LinkedHashMap<String, MutableList<Pair<String, Double>>>()
    val topNames = HashMap<String, String>()
    for (f in held.filter { isFund(it) }) {
        for (h in IncomeHub.topHoldings(holdings[f.symbol.uppercase()])) {
            val s = h.symbol.uppercase()
            via.getOrPut(s) { ArrayList() }.add(f.symbol.uppercase() to h.weight)
            topNames.putIfAbsent(s, h.name)
        }
    }
    val symbols = (owned.keys + via.keys).toSortedSet()

    val entries = symbols.map { sym ->
        val row = owned[sym]
        val f = fundamentals[sym]
        val date = dates[sym] ?: f?.earningsDate?.takeIf { it > 0 }?.let { EarningsFeed.Date(it) }
        val day = date?.day
        val price = row?.quote?.price?.takeIf { it > 0 } ?: 0.0
        val news = headlines[sym].orEmpty()
        val outlook = if (f == null && news.size < 2) EarningsOutlook.Result.UNKNOWN
        else EarningsOutlook.evaluate(
            EarningsOutlook.Input(
                price = price,
                estimates = f?.estimates.orEmpty(), history = f?.history.orEmpty(),
                consensus = f?.consensus, trend = f?.trend.orEmpty(),
                headlines = news, fiftyDayAverage = f?.value("fiftyDayAverage"), now = now
            )
        )
        val reported = day?.let { d ->
            f?.history?.firstOrNull { it.date > 0 && abs(ChronoUnit.DAYS.between(d, Dividends.etDate(it.date))) <= 4 }
        }
        EarnEntry(
            symbol = sym,
            name = row?.name?.takeIf { it.isNotBlank() } ?: topNames[sym].orEmpty(),
            owned = row != null,
            viaFunds = via[sym].orEmpty().sortedByDescending { it.second },
            date = date, day = day,
            daysAway = day?.let { ChronoUnit.DAYS.between(today, it) } ?: Long.MIN_VALUE,
            outlook = outlook, reported = reported,
            epsExpected = f?.estimates?.firstOrNull { it.period == "0q" }?.epsAvg?.takeIf { it != 0.0 },
            headlines = news.filter { it.published > 0 }.sortedByDescending { it.published }.take(3)
        )
    }
    // Stocks you own appear as themselves. A fund appears ONCE, as the fund: its date is the
    // soonest of its top holdings' reports and its light the weight-averaged read of them. The
    // holdings themselves are never listed as rows - only inside the fund's expanded detail.
    val byTop = entries.associateBy { it.symbol }
    val stockEntries = entries.filter { it.owned }
    val fundEntries = held.filter { isFund(it) }.map { f ->
        val kids = IncomeHub.topHoldings(holdings[f.symbol.uppercase()]).mapNotNull { h ->
            byTop[h.symbol.uppercase()]?.takeIf { it.day != null && it.daysAway >= 0 }?.let { it to h.weight }
        }.sortedBy { it.first.date!!.startMs }
        val known = kids.filter { it.first.outlook.known }
        val wSum = known.sumOf { it.second }
        val score = if (wSum > 0) known.sumOf { it.second * it.first.outlook.score } / wSum else 0.0
        val light = when {
            known.isEmpty() -> Light.YELLOW
            score >= EarningsOutlook.GREEN_AT -> Light.GREEN
            score <= EarningsOutlook.RED_AT -> Light.RED
            else -> Light.YELLOW
        }
        val reasons = kids.map { (k, w) ->
            EarningsOutlook.Reason(k.symbol,
                "reports ${k.day!!.format(SHORT_FMT)} · ${"%.1f".format(w * 100)}% of the fund · ${lightWord(k.outlook.light, k.outlook.known)}",
                when (k.outlook.light) { Light.GREEN -> 1; Light.RED -> -1; Light.YELLOW -> 0 }, w)
        }.ifEmpty { EarningsOutlook.Result.UNKNOWN.reasons }
        val first = kids.firstOrNull()?.first
        EarnEntry(
            symbol = f.symbol.uppercase(), name = f.name, owned = true, viaFunds = emptyList(),
            date = first?.date, day = first?.day,
            daysAway = first?.daysAway ?: Long.MIN_VALUE,
            outlook = EarningsOutlook.Result(score, light, known.isNotEmpty(), wSum.coerceAtMost(1.0), reasons),
            reported = null, epsExpected = null, headlines = emptyList(), isFund = true, children = kids
        )
    }
    val listed = stockEntries + fundEntries
    return EarningsBoard(
        upcoming = listed.filter { it.day != null && it.daysAway >= 0 }.sortedWith(
            compareBy<EarnEntry> { it.date!!.startMs }.thenByDescending { it.owned }.thenBy { it.symbol }),
        recent = stockEntries.filter { it.day != null && it.daysAway in -RECENT_DAYS..-1L }
            .sortedByDescending { it.date!!.startMs },
        undated = listed.filter { it.day == null || (it.daysAway < -RECENT_DAYS) }
            .sortedWith(compareByDescending<EarnEntry> { it.owned }.thenBy { it.symbol })
    )
}

/** The light for a report that has already happened: what it actually did, not a forecast. */
internal fun reportedLight(r: EarningsResult): Light = when {
    r.surprisePct >= 1.0 -> Light.GREEN
    r.surprisePct <= -1.0 -> Light.RED
    else -> Light.YELLOW
}

@Composable
private fun lightColor(l: Light): Color = if (l == Light.GREEN) Green else if (l == Light.RED) Red else AMBER

private fun lightWord(l: Light, known: Boolean): String = when {
    l == Light.GREEN -> "Looks good"
    l == Light.RED -> "Looks poor"
    known -> "Mixed"
    else -> "Unknown"
}

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val SHORT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

@Composable
fun EarningsScreen(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit
) {
    val dates by vm.income.earningsDates.collectAsState()
    val loading by vm.income.earningsLoading.collectAsState()
    val headlines by vm.income.headlines.collectAsState()
    val fundamentals by vm.fundamentals.collectAsState()
    val holdings by vm.holdings.collectAsState()
    var pulled by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    val heldSig = state.rows.filter { !it.watchOnly && (it.position?.shares ?: 0.0) > 1e-9 }.map { it.symbol }.sorted()
    // Opening the screen refreshes it; so does a fund's holdings arriving (that is what names
    // the reports to follow for it), and the held set changing.
    LaunchedEffect(Unit) { vm.refreshEarnings(force = false) }
    LaunchedEffect(heldSig, holdings.keys) { if (heldSig.isNotEmpty()) vm.refreshEarnings(force = false) }
    LaunchedEffect(loading) { if (!loading) pulled = false }

    val today = Dividends.etDate(System.currentTimeMillis())
    val board = remember(state.rows.size, heldSig, fundamentals, dates, headlines, holdings, today) {
        buildEarningsBoard(state, fundamentals, dates, headlines, holdings, today, System.currentTimeMillis())
    }

    Refreshable(
        refreshing = pulled && loading,
        onRefresh = { pulled = true; vm.refreshEarnings(force = true) }
    ) {
        EarningsContent(board, today, heldSig.isEmpty(), loading, expanded, onOpen) { sym ->
            expanded = if (sym in expanded) expanded - sym else expanded + sym
        }
    }
}

/** The stateless body, so it can be rendered in tests with a hand-built board. */
@Composable
internal fun EarningsContent(
    board: EarningsBoard,
    today: LocalDate,
    noHoldings: Boolean,
    loading: Boolean,
    expanded: Set<String>,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
        ) {
            if (noHoldings) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nothing held yet", style = MaterialTheme.typography.titleMedium)
                        Text("Upcoming earnings for what you own show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@LazyColumn
            }
            item { EarningsSummary(board, today) }

            item { SectionHeader("Upcoming reports") }
            if (board.upcoming.isEmpty()) {
                item {
                    Text(if (loading) "Checking dates…" else "No upcoming report dates found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
            items(board.upcoming, key = { "e:${it.symbol}" }) { e ->
                EarnRow(e, today, e.symbol in expanded, onOpen) { onToggle(e.symbol) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }

            if (board.recent.isNotEmpty()) {
                item { SectionHeader("Just reported") }
                items(board.recent, key = { "r:${it.symbol}" }) { e ->
                    EarnRow(e, today, e.symbol in expanded, onOpen) { onToggle(e.symbol) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }
            }

            if (board.undated.isNotEmpty()) {
                item { SectionHeader("No date announced") }
                item {
                    Text(
                        board.undated.joinToString(", ") { e ->
                            e.symbol + if (e.isFund) " (fund: no top holding reporting soon)" else ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
            item { EarningsHowItWorks() }
        }
    }
}

@Composable
private fun Dot(l: Light, sizeDp: Int = 16, known: Boolean = true) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(lightColor(l))
            .semantics { contentDescription = lightWord(l, known) }
    )
}

@Composable
private fun EarningsSummary(b: EarningsBoard, today: LocalDate) {
    val soon = b.upcoming.filter { it.daysAway <= 30 }
    fun n(l: Light) = soon.count { it.outlook.light == l }
    StatCard(Modifier.padding(top = 4.dp)) {
        Text("Next 30 days", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when (soon.size) { 0 -> "No reports"; 1 -> "1 report"; else -> "${soon.size} reports" },
            fontSize = 24.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(Light.GREEN, n(Light.GREEN), "good")
            LegendItem(Light.YELLOW, n(Light.YELLOW), "mixed / unknown")
            LegendItem(Light.RED, n(Light.RED), "poor")
        }
        b.upcoming.firstOrNull()?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "Next: ${it.symbol} " + when (it.daysAway) { 0L -> "today"; 1L -> "tomorrow"; else -> "in ${it.daysAway} days" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LegendItem(l: Light, count: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(l, 12)
        Text("$count $label", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dateLine(e: EarnEntry, today: LocalDate): String {
    val d = e.date ?: return ""
    val day = e.day ?: return ""
    val base = if (d.estimated && d.endDay != day) "${day.format(SHORT_FMT)} – ${d.endDay.format(SHORT_FMT)}"
    else day.format(DATE_FMT)
    val ext = when {
        d.estimated -> " (est.)"
        d.timing.isNotEmpty() -> " · ${d.timing}"
        else -> ""
    }
    return base + ext
}

private fun awayText(e: EarnEntry): String = when {
    e.daysAway == 0L -> "Today"
    e.daysAway == 1L -> "Tomorrow"
    e.daysAway > 1 -> "In ${e.daysAway} days"
    e.daysAway == -1L -> "Yesterday"
    else -> "${-e.daysAway} days ago"
}

@Composable
private fun EarnRow(e: EarnEntry, today: LocalDate, open: Boolean, onOpen: (String) -> Unit, onToggle: () -> Unit) {
    val reportedRes = e.reported
    val light = if (e.daysAway < 0 && reportedRes != null) reportedLight(reportedRes) else e.outlook.light
    val known = e.outlook.known || (e.daysAway < 0 && reportedRes != null)
    Column(Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 4.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(light, 18, known)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(dateLine(e, today), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.symbol, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (e.name.isNotBlank()) {
                        Text(e.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                if (e.isFund && e.children.isNotEmpty()) {
                    Text("Fund · top holdings reporting: " + e.children.take(3).joinToString(", ") { it.first.symbol } +
                        if (e.children.size > 3) " +${e.children.size - 3}" else "",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(awayText(e), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (e.daysAway < 0 && reportedRes != null) {
                        when (light) { Light.GREEN -> "Beat"; Light.RED -> "Missed"; Light.YELLOW -> "In line" }
                    } else lightWord(light, known),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = when (light) { Light.GREEN -> greenText; Light.RED -> redText; Light.YELLOW -> MaterialTheme.colorScheme.onSurface }
                )
            }
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(start = 30.dp)) {
                if (reportedRes != null && e.daysAway < 0) {
                    Text(
                        "EPS ${"%.2f".format(reportedRes.epsActual)} vs ${"%.2f".format(reportedRes.epsEstimate)} expected " +
                            "(${if (reportedRes.surprisePct >= 0) "+" else ""}${"%.1f".format(reportedRes.surprisePct)}%)",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (e.epsExpected != null) {
                    Text("Analysts expect EPS ${"%.2f".format(e.epsExpected)}", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
                if (e.daysAway >= 0 || reportedRes == null) {
                    Text(
                        if (e.outlook.known) "Why: score ${"%+.0f".format(e.outlook.score)} on a -100..+100 scale" else "Why it is unknown:",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    e.outlook.reasons.forEach { r ->
                        Row(Modifier.padding(top = 3.dp)) {
                            Text(when (r.tone) { 1 -> "▲ "; -1 -> "▼ "; else -> "• " },
                                fontSize = 12.sp,
                                color = when (r.tone) { 1 -> greenText; -1 -> redText; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                            Text("${r.label}: ${r.detail}", fontSize = 12.sp)
                        }
                    }
                }
                if (e.headlines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Recent headlines", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.headlines.forEach { h ->
                        val t = EarningsOutlook.headlineTone(h.title)
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                            Text(if (t > 0.05) "▲ " else if (t < -0.05) "▼ " else "• ", fontSize = 12.sp,
                                color = if (t > 0.05) greenText else if (t < -0.05) redText else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${h.title} — ${h.source}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Open ${e.symbol} →", fontSize = 13.sp, color = accentText, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onOpen(e.symbol) }.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun EarningsHowItWorks() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { open = !open }.padding(4.dp)) {
        Text(if (open) "How the lights work  ▲" else "How the lights work  ▼",
            style = MaterialTheme.typography.labelLarge, color = accentText)
        if (open) {
            Spacer(Modifier.height(6.dp))
            listOf(
                "Green = the signs point to a good report, yellow = mixed or not enough known, red = the signs point to a poor one. It is an estimate, not a prediction: how a stock reacts depends on what the market already expected, which nobody publishes.",
                "It averages: the company's track record against estimates over the last four quarters (30%), the growth analysts expect (20%), the tone of the last three weeks of headlines (20%), the analyst rating and whether it is improving (15%), the price against its 50-day average (10%, a big run-up counts against because good news is already in the price), and the average price target (5%).",
                "Tap a row to see exactly which signals pushed it which way.",
                "Funds have no earnings of their own, so a fund shows one row: the date is its soonest big holding's report, the light is the weighted read of those holdings, and tapping it lists them.",
                "Dates marked (est.) are the company's rough window, not a confirmed day."
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
