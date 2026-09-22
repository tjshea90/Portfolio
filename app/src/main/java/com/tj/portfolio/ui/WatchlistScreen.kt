package com.tj.portfolio.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.util.Fmt

/** The two halves of the Watch tab. Persisted, so the app reopens on the one you left. */
const val WATCH_LIST = 0
const val WATCH_RESEARCH = 1

/**
 * The Watch tab: your own watchlist, and the market-wide Research screen beside it.
 *
 * Research lives HERE rather than as a seventh bottom-bar tab because the bottom bar is
 * already six items wide on a phone - a seventh would shrink every label below the point
 * where it can be read, and the two screens answer the same question at different scopes
 * ("what am I watching" / "what should I be watching").
 *
 * The sub-tab is hoisted to [MainActivity] rather than kept here, for two reasons that both
 * bite if it is local: the polling loop's [VisibleScope] has to know that no watchlist prices
 * are on screen while Research is showing, and the Android back gesture has to step back to
 * the watchlist before it leaves the tab.
 */
@Composable
fun WatchTab(
    vm: PortfolioViewModel,
    state: UiState,
    subTab: Int,
    onSubTab: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onOpenNews: (String) -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onSearch: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = subTab.coerceIn(0, 1),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Tab(
                selected = subTab == WATCH_LIST,
                onClick = { onSubTab(WATCH_LIST) },
                text = {
                    Text(
                        "Watchlist",
                        fontSize = 14.sp,
                        fontWeight = if (subTab == WATCH_LIST) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
            Tab(
                selected = subTab == WATCH_RESEARCH,
                onClick = { onSubTab(WATCH_RESEARCH) },
                text = {
                    Text(
                        "Research",
                        fontSize = 14.sp,
                        fontWeight = if (subTab == WATCH_RESEARCH) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }

        if (subTab == WATCH_RESEARCH) {
            // Build on first sight, and only then - but WHICH list is built is decided inside
            // `ResearchScreen`, by which of its sections is showing (Round 63). The ETF list
            // is ten requests on a six-hour clock and the stock lists are eighteen on a
            // thirty-minute one; firing the stock pass here regardless meant someone sitting
            // on the ETFs tab paid for lists they were not looking at. The rule itself
            // is unchanged and is the Round 50 one: nothing is fetched for a screen nobody
            // has opened.
            ResearchScreen(vm, state, onOpen = onOpen, onOpenUrl = onOpenUrl)
        } else {
            WatchlistScreen(vm, state, onOpen, onOpenNews, onSearch)
        }
    }
}

/**
 * Same live data as the portfolio, for symbols you are only watching.
 * Nothing here counts toward positions, cash, or P/L.
 */
@Composable
fun WatchlistScreen(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit,
    onOpenNews: (String) -> Unit,
    onSearch: () -> Unit
) {
    var pending by rememberSaveable(stateSaver = PendingAction.Saver) { mutableStateOf<PendingAction?>(null) }
    val rows = state.rows.filter { it.watchOnly }

    // The one place %-since-added baselines get resolved - opening the tab is the signal,
    // the same rule the Insider tab's own on-open refresh follows. Cheap to call every time
    // this composable enters composition: a symbol already resolved costs nothing.
    LaunchedEffect(Unit) { vm.resolveWatchBaselines() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Watchlist", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            BigIconButton(Icons.Filled.Search, "Search symbols") { onSearch() }
            BigIconButton(Icons.Filled.Refresh, "Refresh prices") { vm.refresh(manual = true) }
        }

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        // PortfolioScreen already surfaces state.error for the same refresh path
        // (vm.refresh()) - this screen was reading the same UiState without ever showing it,
        // so a failed refresh here left stale prices on screen with no warning at all.
        if (state.error != null) {
            Text(
                state.error,
                color = redText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- ONE LABEL, WEIGHTED (Round 66 audit, PUI-6).
            //
            // There was a "PRICE" heading here, right-aligned behind a weighted `Spacer`. Two
            // things were wrong with it. It labelled a column that no longer exists: since
            // `StockRowItem` gained the full-width `PriceBlock`, the price sits under its own
            // "CURRENT PRICE" heading inside each row and nothing is right-aligned under this
            // one. And it was an UNWEIGHTED child placed after a weighted one, which is the
            // measurement trap fixed three separate times elsewhere in this file's
            // neighbours - `Row` measures unweighted children in index order against what is
            // left, so at a large font scale the count label took the row and "PRICE" was
            // measured at a few dp and rendered empty anyway.
            Text(
                "${rows.size} SYMBOL${if (rows.size == 1) "" else "S"} - LONG-PRESS FOR OPTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Refreshable(refreshing = state.pulling(PULL_PRICES), onRefresh = { vm.refresh(manual = true) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.symbol }, contentType = { "stock" }) { row ->
                StockRowItem(
                    row,
                    onClick = { onOpen(row.symbol) },
                    onNews = { onOpenNews(row.symbol) },
                    onAction = { a -> pending = PendingAction(row.symbol, a) }
                )
                RowSeparator()
            }

            if (rows.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Nothing on your watchlist yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Search for a ticker or company name and tap Watch. " +
                                "Watchlist symbols get the same live prices, charts and news as your holdings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onSearch) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Find a symbol")
                        }
                    }
                }
            }

            if (state.lastRefresh > 0 && rows.isNotEmpty()) {
                item {
                    Text(
                        "Prices updated ${Fmt.relative(state.lastRefresh)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        }
    }

    RowActionHost(
        vm, state, pending,
        onOpen = onOpen,
        onNews = onOpenNews,
        onDone = { pending = null }
    )
}
