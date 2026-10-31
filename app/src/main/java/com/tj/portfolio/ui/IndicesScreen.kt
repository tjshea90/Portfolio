package com.tj.portfolio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.Quote
import com.tj.portfolio.net.Indices
import java.util.Locale

/** The Markets sub-tab: major index levels and today's move, saved for offline reading. */
@Composable
fun IndicesScreen(vm: PortfolioViewModel) {
    val charts by vm.charts.collectAsState()
    val chartLoading by vm.chartLoading.collectAsState()
    val quotes by vm.income.indices.collectAsState()
    val loading by vm.income.indicesLoading.collectAsState()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.income.refreshIndices() }
    LaunchedEffect(loading) { if (!loading) pulled = false }
    Refreshable(refreshing = pulled && loading, onRefresh = { pulled = true; vm.income.refreshIndices() }) {
        IndicesContent(quotes, loading) { d, expanded, setExpanded -> IndexChart(vm, d, quotes[d.symbol], charts, chartLoading, expanded, setExpanded) }
    }
}

internal fun indexLevel(symbol: String, price: Double): String =
    String.format(Locale.US, if (symbol == "^TNX" || symbol == "^VIX") "%.2f" else "%,.2f", price)

@Composable
internal fun IndicesContent(
    quotes: Map<String, Quote>,
    loading: Boolean,
    chart: @Composable (Indices.Def, Boolean, (Boolean) -> Unit) -> Unit = { _, _, _ -> }
) {
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)) {
            val asOf = quotes.values.maxOfOrNull { it.updated } ?: 0L
            item {
                Text(
                    if (quotes.isEmpty()) (if (loading) "Loading index prices…" else "No index prices saved yet. Connect and pull down to load them.")
                    else "As of " + java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(java.util.Date(asOf)) +
                        " · pull down to refresh",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp)
                )
            }
            listOf(Indices.US, Indices.GLOBAL, Indices.GAUGES).forEach { g ->
                val defs = Indices.ALL.filter { it.group == g }
                item(key = "h:$g") { SectionHeader(g) }
                defs.forEach { d ->
                    item(key = d.symbol) {
                        IndexRow(d, quotes[d.symbol])
                        // US indices carry a chart (tap to maximize, pinch to zoom); the rest are a level.
                        if (g == Indices.US) chart(d, d.symbol in open) { on -> open = if (on) open + d.symbol else open - d.symbol }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexRow(d: Indices.Def, q: Quote?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(d.symbol, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (q == null) {
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val up = q.dayChange >= 0
            Column(horizontalAlignment = Alignment.End) {
                Text(indexLevel(d.symbol, q.price), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    (if (up) "+" else "") + String.format(Locale.US, "%.2f (%.2f%%)", q.dayChange, q.dayChangePct),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (q.prevClose <= 0) MaterialTheme.colorScheme.onSurfaceVariant else if (up) greenText else redText
                )
            }
        }
    }
}

/**
 * A US index chart: the app's own [PriceChart] (inline, pinch to zoom, tap to maximize) with the
 * range chips, and the same [FullScreenChart] the detail screen uses. Charts are fetched through
 * the shared loader, which writes to SQLite - so they also open offline.
 */
@Composable
private fun IndexChart(
    vm: PortfolioViewModel, d: Indices.Def, q: Quote?,
    charts: Map<String, com.tj.portfolio.data.ChartSeries>, loadingKeys: Set<String>,
    expanded: Boolean, setExpanded: (Boolean) -> Unit
) {
    var range by rememberSaveable { mutableStateOf(com.tj.portfolio.data.ChartRange.D1.name) }
    val r = com.tj.portfolio.data.ChartRange.valueOf(range)
    var window by remember { mutableStateOf<com.tj.portfolio.data.ChartWindow?>(null) }
    var pinching by remember { mutableStateOf(false) }
    LaunchedEffect(d.symbol, r) { vm.loadChart(d.symbol, r) }
    val series = charts[vm.chartKey(d.symbol, r)]
    val bounds = remember(series) { series?.let { windowBounds(it, listOf(it)) } }
    val loading = vm.chartKey(d.symbol, r) in loadingKeys
    val live = q?.price ?: 0.0
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        RangeChips(selected = r, onSelect = { window = null; range = it.name },
            loading = emptySet())
        PriceChart(
            series = series, range = r, loading = loading, chartHeight = 150.dp,
            livePrice = live, liveEdge = live > 0 && r == com.tj.portfolio.data.ChartRange.D1,
            window = window, windowBounds = bounds,
            onWindow = { window = it }, onResetWindow = { window = null },
            onExpand = { setExpanded(true) }, onZoomingChanged = { pinching = it }
        )
    }
    if (expanded) {
        FullScreenChart(
            symbol = d.symbol, series = series, range = r, loading = loading,
            onRange = { window = null; range = it.name },
            perf = emptyMap(), loadingRanges = emptySet(),
            livePrice = live, liveEdge = live > 0 && r == com.tj.portfolio.data.ChartRange.D1,
            window = window, windowBounds = bounds,
            onWindow = { window = it }, onResetWindow = { window = null },
            onZoomingChanged = { pinching = it },
            compare = null, compareLabel = "", compareLivePrice = 0.0,
            onClose = { setExpanded(false) }
        )
    }
}
