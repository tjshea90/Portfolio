package com.tj.portfolio.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.MetricCatalog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tj.portfolio.data.Txn
import com.tj.portfolio.util.Fmt

/**
 * Identity of one headline. Deliberately the SAME expression used to de-duplicate the
 * list and to key it - if the two ever disagreed, the list could be handed a key it had
 * already seen, and Compose throws on that.
 */
private fun newsKey(n: com.tj.portfolio.data.NewsItem): String =
    n.title.lowercase().take(80) + "|" + n.published

/**
 * The five tabs on a stock's page.
 *
 * WHY TABS AT ALL. Before this the detail screen was one very long scroll: price, chart,
 * position, transactions, filings, news. Adding fifty fundamentals and a full analyst
 * history to the bottom of that would have made the useful parts unreachable. Splitting it
 * also means the expensive fetches can follow the user - the 200KB analyst history is only
 * requested when the Analysts tab is actually opened.
 */
enum class DetailTab(val label: String) {
    OVERVIEW("Overview"),
    /**
     * ETFs and funds only - see [visibleTabs].
     *
     * Placed second rather than last because for a fund it is the most interesting tab on
     * the screen: what it holds is what it IS. It sits between Overview and Stats, which is
     * also where the other apps TJ compared against put it.
     */
    HOLDINGS("Holdings"),
    STATS("Stats"),
    ANALYSTS("Analysts"),
    EARNINGS("Earnings"),
    NEWS("News")
}

/**
 * Which tabs this particular symbol gets.
 *
 * HOLDINGS IS HIDDEN UNTIL THE PROVIDER SAYS THIS IS A FUND, and hiding it is the right
 * default rather than showing an empty one: fifteen of TJ's sixteen positions are ordinary
 * shares, and a permanently blank tab on every one of them would be exactly the
 * "labelled space for a number that cannot exist" the price block was corrected for in
 * Round 51.
 *
 * The test is the provider's own `quoteType`, never the ticker - a four-letter symbol is not
 * a fund, and this project has a standing rule against inferring something a feed will state.
 * Until the lookup returns, the tab is absent; when it returns "fund", the tab appears.
 */
fun visibleTabs(isFund: Boolean): List<DetailTab> =
    if (isFund) DetailTab.entries
    else DetailTab.entries.filter { it != DetailTab.HOLDINGS }

/**
 * Everything about one symbol.
 *
 * The price header is PINNED above the tabs rather than scrolling with the Overview tab.
 * On a stock screen the price is the one number you always want visible, and having it
 * disappear when you switched to Stats made the numbers below it harder to reason about -
 * a P/E means nothing without knowing what the price currently is.
 */
@Composable
fun DetailScreen(
    vm: PortfolioViewModel,
    state: UiState,
    symbol: String,
    scrollToNews: Boolean,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    /**
     * Open another stock's detail screen. Used by the Holdings tab, where tapping a fund's
     * position is the obvious next move. Defaults to doing nothing so a caller that has no
     * navigation stack to push onto is not forced to invent one.
     */
    onOpenSymbol: (String) -> Unit = {}
) {
    val quotes by vm.quotes.collectAsState()
    /**
     * A stock opened from SEARCH that is neither held nor watched has no [Row] - the lists
     * are built from positions and the watchlist, and it is in neither. Before this the
     * whole price header simply did not render for those symbols, which is precisely the
     * case TJ asked about ("no matter if I own it or search for it"). The polling loop IS
     * fetching the quote already, because the visible scope while this screen is open is
     * `Detail(symbol)`, so a transient row over that quote is all that was missing.
     */
    val row = state.rows.firstOrNull { it.symbol == symbol }
        ?: quotes[symbol]?.let { q ->
            Row(
                symbol = symbol,
                name = q.name,
                position = null,
                quote = q,
                watchOnly = true
            )
        }
    /**
     * Whether this symbol is one the app is following at all. A searched symbol that is
     * neither held nor watched gets the transient row above, and that row has to say
     * `watchOnly` for the position maths - but it must NOT then offer to "remove from
     * watchlist" something that was never on it.
     */
    val tracked = state.rows.any { it.symbol == symbol }
    val newsMap by vm.news.collectAsState()
    val loadingSet by vm.newsLoading.collectAsState()
    val fundMap by vm.fundamentals.collectAsState()
    val fundLoadingSet by vm.fundLoading.collectAsState()
    val ratingsLoadingSet by vm.ratingsLoading.collectAsState()

    // Same reasoning as the filings list below: two feeds can carry the same story under
    // slightly different URLs, and a keyed list must never see the same key twice.
    val items = remember(newsMap[symbol]) {
        newsMap[symbol].orEmpty().distinctBy { newsKey(it) }
    }
    val loadingNews = loadingSet.contains(symbol)
    val fundamentals = fundMap[symbol]
    val loadingFund = fundLoadingSet.contains(symbol)
    val loadingRatings = ratingsLoadingSet.contains(symbol)
    val price = row?.price ?: 0.0

    val insiderMap by vm.insider.collectAsState()
    // Belt and braces against the crash that used to hit a few seconds after opening a
    // stock's news: these rows are keyed by id, and a LazyColumn given the same key twice
    // throws and takes the whole app down.
    val filings = remember(insiderMap[symbol]) {
        insiderMap[symbol].orEmpty().distinctBy { it.accession }
            .sortedByDescending { it.filedAt }
    }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var editingTxn by remember { mutableStateOf<Txn?>(null) }
    var addingTxn by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    /**
     * Which explanation sheet is open, by metric key or topic id. Null is closed.
     * One piece of state for all fifty-odd "i" buttons - they differ only in what they
     * pass here.
     */
    var infoKey by remember { mutableStateOf<String?>(null) }

    // ---- the price chart's range.
    //
    // Seeded from the stored preference and then owned by this screen, so switching range
    // here changes it for the next stock too - the choice is about how you read charts, not
    // about this one symbol. `remember(Unit)` rather than `remember(symbol)`: re-reading the
    // setting on every symbol change would be a DB read from composition, which is the exact
    // rule this project already fixed in Settings and SearchSheet.
    val holdingsMap by vm.holdings.collectAsState()
    val holdingsLoadingSet by vm.holdingsLoading.collectAsState()
    val fundHoldings = holdingsMap[symbol]
    val loadingHoldings = holdingsLoadingSet.contains(symbol)
    val tabs = remember(fundHoldings?.isFund) { visibleTabs(fundHoldings?.isFund == true) }

    var chartRange by remember { mutableStateOf(vm.chartRange()) }
    val chartMap by vm.charts.collectAsState()
    val chartLoadingSet by vm.chartLoading.collectAsState()
    val chartKey = vm.chartKey(symbol, chartRange)
    val chart = chartMap[chartKey]
    val chartLoading = chartLoadingSet.contains(chartKey)

    // The "News" chip on a holding row used to open this screen and then try to SCROLL to
    // the news section, which was fragile arithmetic over a list whose length changed as
    // headlines arrived. With tabs it just opens the right tab.
    var tab by remember(symbol) {
        mutableStateOf(if (scrollToNews) DetailTab.NEWS else DetailTab.OVERVIEW)
    }

    // state.txns is the full history and this screen recomposes on every price tick, so
    // the filter is keyed on the list identity - it only re-runs when the ledger changes.
    val txns = remember(state.txns, symbol) { state.txns.filter { it.symbol == symbol } }

    LaunchedEffect(symbol) {
        vm.loadNews(symbol)
        vm.loadFundamentals(symbol)
        // Form 4 filings are loaded for ANY stock opened here, not just holdings. They used
        // to be held-only to keep SEC requests down, but the request is per opened stock and
        // cached - and "who inside the company has been buying" is exactly the kind of thing
        // you want when researching a stock you do not own yet.
        vm.loadInsider(symbol)
        // Cheap and cached for twelve hours, and it has to run for every symbol because its
        // answer is what decides whether the Holdings tab exists at all. For an ordinary
        // share the "not a fund" verdict is cached too, so this is one request per symbol
        // per half-day, not one per screen open.
        vm.loadHoldings(symbol)
    }

    // The analyst history is an order of magnitude bigger than everything else, so it is
    // fetched when the tab is opened rather than when the screen is.
    LaunchedEffect(symbol, tab) {
        if (tab == DetailTab.ANALYSTS) vm.loadRatings(symbol)
    }

    /**
     * ASK AGAIN WHEN THE APP COMES BACK. The other half of Round 57's `fgScope`.
     *
     * Round 57 made every on-demand fetch cancellable and cancelled them all on ON_STOP,
     * which was right - but nothing restarted the ones belonging to a screen that is STILL
     * OPEN. `LaunchedEffect(symbol)` above does not re-fire on resume, because `symbol` has
     * not changed; the composition was never torn down. So a stock opened and then
     * backgrounded a second later came back with its news, numbers, filings and chart
     * permanently empty, and the only way out was a manual pull-to-refresh.
     *
     * Every call here is idempotent and cache-first: each one returns immediately if it
     * already holds a fresh answer, and none passes `force`, so an ordinary resume with
     * everything cached sends nothing at all. It costs a request only in exactly the case
     * this exists for - something the user is looking at is genuinely missing.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, symbol, chartRange, tab) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.loadNews(symbol)
                vm.loadFundamentals(symbol)
                vm.loadInsider(symbol)
                vm.loadHoldings(symbol)
                vm.loadChart(symbol, chartRange)
                if (tab == DetailTab.ANALYSTS) vm.loadRatings(symbol)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // The chart follows the range the user picked. `loadChart` reads its disk cache first
    // and only reaches for the network when what it holds is past that range's TTL, so
    // flicking back and forth between 1D and 1Y costs nothing after the first look.
    LaunchedEffect(symbol, chartRange) { vm.loadChart(symbol, chartRange) }

    // REMEMBERED (Round 57). An unremembered lambda is a new object on every recomposition,
    // and this screen recomposes on every quote tick - so `Refreshable` and the header button
    // were being invalidated four times a minute for a callback that had not changed. Keyed
    // on what it actually captures.
    val refreshEverything = remember(symbol, tab, chartRange) {
        {
            vm.refresh(manual = true)
            vm.loadNews(symbol, force = true)
            vm.loadInsider(symbol, force = true)
            vm.loadFundamentals(symbol, force = true)
            // TJ's rule for this round, exactly: cache and refresh periodically on its own,
            // but a pull-down always re-fetches. `force` is what bypasses the TTL.
            vm.loadChart(symbol, chartRange, force = true)
            if (tab == DetailTab.ANALYSTS) vm.loadRatings(symbol, force = true)
            if (tab == DetailTab.HOLDINGS) vm.loadHoldings(symbol, force = true)
        }
    }

    Column(Modifier.fillMaxSize()) {

        // ---- header actions
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BigIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onBack() }
            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                Text(symbol, style = MaterialTheme.typography.titleLarge)
                val company = row?.name.orEmpty()
                if (company.isNotBlank()) Text(
                    company,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row?.watchOnly != true) {
                BigIconButton(Icons.Filled.Edit, "Edit position") {
                    pending = PendingAction(symbol, RowAction.EDIT_POSITION)
                }
                BigIconButton(Icons.Filled.Add, "Add transaction") { addingTxn = true }
            } else if (tracked) {
                BigIconButton(Icons.Filled.Star, "Remove from watchlist") {
                    pending = PendingAction(symbol, RowAction.WATCH_TOGGLE)
                }
            } else {
                BigIconButton(Icons.Filled.Star, "Add to watchlist", filled = false) {
                    vm.addWatch(symbol)
                    vm.toast("$symbol added to your watchlist")
                }
            }
            BigIconButton(Icons.Filled.Refresh, "Refresh") { refreshEverything() }
        }

        // ---- pinned price
        if (row != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                PriceBlock(row, big = true)
            }
        }

        Spacer(Modifier.height(6.dp))

        ScrollableTabRow(
            // INDEXED INTO THE VISIBLE LIST, NOT THE ENUM. `tab.ordinal` was fine while every
            // tab was always shown; with Holdings hidden on ordinary shares the ordinals no
            // longer match the tabs on screen, and the indicator would sit under the wrong
            // one. `coerceAtLeast(0)` covers the instant after a fund's data arrives, when
            // the list grows underneath a selection that is briefly not in it.
            selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0),
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            tabs.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(
                            t.label,
                            fontSize = 14.sp,
                            fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Refreshable(
            refreshing = state.pulling(PULL_PRICES),
            onRefresh = refreshEverything
        ) {
            when (tab) {
                DetailTab.OVERVIEW -> OverviewTab(
                    state = state,
                    symbol = symbol,
                    row = row,
                    tracked = tracked,
                    fundamentals = fundamentals,
                    txns = txns,
                    chart = chart,
                    chartRange = chartRange,
                    chartLoading = chartLoading,
                    onChartRange = { r ->
                        chartRange = r
                        vm.setChartRange(r)
                    },
                    onInfo = { infoKey = it },
                    onEditPosition = { pending = PendingAction(symbol, RowAction.EDIT_POSITION) },
                    onAddTxn = { addingTxn = true },
                    onWatchToggle = { pending = PendingAction(symbol, RowAction.WATCH_TOGGLE) },
                    onDeletePosition = { pending = PendingAction(symbol, RowAction.DELETE) },
                    onEditTxn = { editingTxn = it },
                    onDeleteTxn = { confirmDelete = it },
                    onSeeAllStats = { tab = DetailTab.STATS },
                    onSeeAnalysts = { tab = DetailTab.ANALYSTS },
                    onRemoveWatch = { vm.removeWatch(symbol); onBack() },
                    onAddWatch = { vm.addWatch(symbol); vm.toast("$symbol added to your watchlist") }
                )

                DetailTab.STATS -> StatsTab(
                    f = fundamentals,
                    price = price,
                    loading = loadingFund,
                    onInfo = { infoKey = it }
                )

                DetailTab.ANALYSTS -> AnalystsTab(
                    f = fundamentals,
                    price = price,
                    loading = loadingRatings || loadingFund,
                    onInfo = { infoKey = it }
                )

                DetailTab.EARNINGS -> EarningsTab(
                    f = fundamentals,
                    loading = loadingFund,
                    onInfo = { infoKey = it }
                )

                DetailTab.HOLDINGS -> HoldingsTab(
                    h = fundHoldings,
                    loading = loadingHoldings,
                    onOpenSymbol = onOpenSymbol
                )

                DetailTab.NEWS -> NewsTab(
                    items = items,
                    filings = filings,
                    loadingNews = loadingNews,
                    symbol = symbol,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }

    // ---- the "i" sheet. One dialog for every number on every tab.
    infoKey?.let { key ->
        val f = fundamentals ?: com.tj.portfolio.data.Fundamentals(symbol = symbol)
        val explanation =
            if (MetricCatalog.byKey.containsKey(key)) Explain.of(key, f, price)
            else Explain.analystTopic(key, f, price)
        ExplainDialog(explanation) { infoKey = null }
    }

    if (addingTxn) {
        TxnEditorDialog(
            presetSymbol = symbol,
            onDismiss = { addingTxn = false },
            onSave = { t -> vm.addTxnRecord(t); addingTxn = false; vm.toast("${t.type} $symbol saved") }
        )
    }

    confirmDelete?.let { id ->
        ConfirmDialog(
            title = "Delete transaction?",
            message = "This recalculates your positions and cash.",
            confirmText = "Delete",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                vm.deleteTxn(id); confirmDelete = null; vm.toast("Transaction deleted")
            }
        )
    }

    editingTxn?.let { t ->
        TxnEditorDialog(
            existing = t,
            onDismiss = { editingTxn = null },
            onDelete = { vm.deleteTxn(t.id); editingTxn = null; vm.toast("Transaction deleted") },
            onSave = { u -> vm.updateTxn(u); editingTxn = null; vm.toast("Transaction updated") }
        )
    }

    RowActionHost(
        vm, state, pending,
        onOpen = { },
        onNews = { },
        onDone = {
            val wasDelete = pending?.action == RowAction.DELETE
            pending = null
            if (wasDelete && state.rows.none { it.symbol == symbol }) onBack()
        }
    )
}

// ========================================================================== Overview

/**
 * Chart, what the price block's numbers mean, the position, a short summary of the
 * headline fundamentals, the company itself, and this stock's transactions.
 *
 * The "key numbers" card is a deliberate duplicate of six rows from the Stats tab. It is
 * there because the first question about a stock is nearly always one of those six, and
 * making the user find the right tab and scroll to it for "what's the P/E" would be a
 * worse screen than the one this replaced.
 */
@Composable
private fun OverviewTab(
    state: UiState,
    symbol: String,
    row: Row?,
    tracked: Boolean,
    fundamentals: com.tj.portfolio.data.Fundamentals?,
    txns: List<Txn>,
    chart: com.tj.portfolio.data.ChartSeries?,
    chartRange: com.tj.portfolio.data.ChartRange,
    chartLoading: Boolean,
    onChartRange: (com.tj.portfolio.data.ChartRange) -> Unit,
    onInfo: (String) -> Unit,
    onEditPosition: () -> Unit,
    onAddTxn: () -> Unit,
    onWatchToggle: () -> Unit,
    onDeletePosition: () -> Unit,
    onEditTxn: (Txn) -> Unit,
    onDeleteTxn: (Long) -> Unit,
    onSeeAllStats: () -> Unit,
    onSeeAnalysts: () -> Unit,
    onRemoveWatch: () -> Unit,
    onAddWatch: () -> Unit
) {
    val listState = rememberLazyListState()
    val q = row?.quote
    val price = row?.price ?: 0.0

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                // The price block lives in the pinned header now; these are the notes that
                // explain what its two columns actually mean.
                if (extendedPrice(q) != null) {
                    Text(
                        "The big price is the regular session. The after-hours / overnight " +
                            "column is the live price outside those hours, and its change is " +
                            "measured from that closing price.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (q != null && q.quoteTime > 0) {
                    Text(
                        "Last exchange print ${Fmt.clock(q.quoteTime)}  -  " +
                            "pulled ${Fmt.relative(q.updated)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (q != null && q.prevClose > 0) {
                    Text(
                        "Previous close ${Fmt.price(q.prevClose)}" +
                            (if (q.marketState.isNotBlank())
                                "  -  market ${q.marketState.lowercase()}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---- THE CHART, AND THE BUTTONS THAT SAY WHAT IT COVERS (Round 58).
                //
                // This was a bare 130dp `Sparkline` of `q.spark` - one fixed day, at five
                // minutes, with nothing anywhere on screen saying so. TJ: "I cannot see how
                // long the chart is tracking."
                RangeChips(selected = chartRange, onSelect = onChartRange)
                Spacer(Modifier.height(10.dp))
                PriceChart(
                    series = chart,
                    range = chartRange,
                    loading = chartLoading,
                    livePrice = liveEdgePrice(q, chartRange),
                    // The live tip belongs to whichever session the chart is drawing: the
                    // regular price on the 1D line while the market is open, the extended
                    // print on the after-hours line once it has closed. See withLiveEdge.
                    liveEdge = liveEdgePrice(q, chartRange) > 0.0
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        // ---- key numbers, with their own "i" buttons
        if (fundamentals != null && !fundamentals.isEmpty) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Key numbers")
                    StatCard {
                        KEY_NUMBER_KEYS.forEach { key ->
                            MetricCatalog.byKey[key]?.let { def ->
                                if (fundamentals.values.containsKey(key)) {
                                    MetricRow(def, fundamentals, price, onInfo, startPad = 0)
                                }
                            }
                        }
                        fundamentals.consensus?.let { c ->
                            if (c.meanLabel.isNotBlank()) TopicRow(
                                "Analyst consensus",
                                c.meanLabel + (if (c.analysts > 0) "  (${c.analysts})" else ""),
                                Explain.TOPIC_CONSENSUS,
                                startPad = 0,
                                onInfo = onInfo
                            )
                            if (c.hasTarget) {
                                val up = c.upsidePct(price)
                                TopicRow(
                                    "Average price target",
                                    Fmt.price(c.targetMean) + (
                                        if (up != null) "  (" + (if (up >= 0) "+" else "") +
                                            String.format(java.util.Locale.US, "%.0f%%", up) + ")"
                                        else ""),
                                    Explain.TOPIC_TARGET,
                                    valueColor = up?.let { signColor(it) },
                                    startPad = 0,
                                    onInfo = onInfo
                                )
                            }
                        }
                        Row {
                            TextButton(onClick = onSeeAllStats) { Text("All statistics") }
                            TextButton(onClick = onSeeAnalysts) { Text("Analyst ratings") }
                        }
                    }
                    SourceLine(fundamentals)
                }
            }
        }

        // ---- the position
        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (row != null && !row.watchOnly) {
                    SectionHeader("Your position")
                    StatCard {
                        KeyValue("Shares owned", Fmt.shares(row.shares), bold = true)
                        KeyValue("Average cost", Fmt.price(row.avgCost), bold = true)
                        KeyValue(
                            "Market value  (${Fmt.shares(row.shares)} x ${Fmt.price(row.price)})",
                            Fmt.usd(row.value), bold = true
                        )
                        KeyValue("Cost basis", Fmt.usd(row.position?.costBasis ?: 0.0))
                        KeyValue(
                            "All-time gain/loss",
                            "${Fmt.usdSigned(row.totalPnl)}  (${Fmt.pctSigned(row.totalPct)})",
                            signColor(row.totalPnl), bold = true
                        )
                        KeyValue(
                            buildString {
                                append(
                                    if (row.sessionLabel.isBlank()) "Today's gain/loss"
                                    else "${row.sessionLabel} session gain/loss"
                                )
                                if (row.boughtToday) append("  (from your fill)")
                            },
                            "${Fmt.usdSigned(row.dayPnl)}  (${Fmt.pctSigned(row.dayPnlPct)})",
                            signColor(row.dayPnl), bold = true
                        )
                        if (row.boughtToday) {
                            KeyValue(
                                if (row.sessionLabel.isBlank()) "Bought today"
                                else "Bought ${row.sessionLabel}",
                                "${Fmt.shares(row.position?.sharesToday ?: 0.0)} @ " +
                                    Fmt.price(row.position?.avgCostToday ?: 0.0)
                            )
                            Text(
                                "These shares were bought during this trading session, so " +
                                    "their gain is measured from the price you actually paid " +
                                    "rather than from the previous close - which is why it " +
                                    "differs from the stock's own day change above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Accent
                            )
                        }
                        val eq = row.quote
                        if (eq != null && extendedPrice(eq) != null) {
                            KeyValue(
                                "${eq.extLabel ?: "Extended"} gain/loss",
                                "${Fmt.usdSigned(row.shares * eq.extChange)}  " +
                                    "(${Fmt.pctSigned(eq.extChangePct)})",
                                signColor(eq.extChangePct)
                            )
                        }
                        val realized =
                            state.positions.firstOrNull { it.symbol == symbol }?.realized ?: 0.0
                        if (kotlin.math.abs(realized) > 0.005) {
                            KeyValue(
                                "Realized (closed lots)",
                                Fmt.usdSigned(realized),
                                signColor(realized)
                            )
                        }
                        val weight = state.totals?.marketValue?.takeIf { it > 0 }
                            ?.let { row.value / it * 100.0 }
                        if (weight != null) KeyValue("Portfolio weight", Fmt.pct(weight))
                        Text(
                            "Gain figures cover the shares you still own. Profit from shares " +
                                "already sold is the realized figure below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (row.position?.overridden == true) {
                            Text(
                                "Manual override in effect",
                                style = MaterialTheme.typography.bodySmall,
                                color = Accent
                            )
                        }
                    }

                    Row {
                        TextButton(onClick = onEditPosition) { Text("Edit shares / cost") }
                        TextButton(onClick = onAddTxn) { Text("Add buy or sell") }
                    }
                    Row {
                        TextButton(onClick = onWatchToggle) { Text("Also watch") }
                        TextButton(onClick = onDeletePosition) { Text("Delete position", color = Red) }
                    }
                } else {
                    Text(
                        if (tracked)
                            "On your watchlist - no shares held, so nothing here counts " +
                                "toward your totals."
                        else
                            "You do not hold this stock and it is not on your watchlist. " +
                                "Every tab above still works - this is a full research " +
                                "view of any symbol you search for.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row {
                        TextButton(onClick = onAddTxn) { Text("Record a buy") }
                        if (tracked) TextButton(onClick = onRemoveWatch) {
                            Text("Remove from watchlist", color = Red)
                        } else TextButton(onClick = onAddWatch) { Text("Add to watchlist") }
                    }
                }
            }
        }

        // ---- the company itself
        if (fundamentals != null &&
            (fundamentals.profile.isNotBlank() || fundamentals.texts.isNotEmpty())
        ) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("About the company")
                    StatCard {
                        fundamentals.text("sector").takeIf { it.isNotBlank() }
                            ?.let { KeyValue("Sector", it) }
                        fundamentals.text("industry").takeIf { it.isNotBlank() }
                            ?.let { KeyValue("Industry", it) }
                        fundamentals.text("country").takeIf { it.isNotBlank() }
                            ?.let { KeyValue("Country", it) }
                        fundamentals.text("exchange").takeIf { it.isNotBlank() }
                            ?.let { KeyValue("Exchange", it) }
                        MetricCatalog.byKey["employees"]?.let { def ->
                            if (fundamentals.values.containsKey("employees"))
                                MetricRow(def, fundamentals, price, onInfo, startPad = 0)
                        }
                        if (fundamentals.profile.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                fundamentals.profile,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        if (txns.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Transactions (${txns.size}) - tap to edit")
                }
            }
        }

        items(txns, key = { it.id }) { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEditTxn(t) }
                    .padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.type, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(Fmt.day(t.date))
                            if (t.quantity > 0) append("  -  ")
                                .append(Fmt.shares(t.quantity))
                                .append(" @ ").append(Fmt.price(t.price))
                            if (t.fees > 0) append("  -  fees ").append(Fmt.usd(t.fees))
                            if (!t.note.isNullOrBlank()) append("  -  ").append(t.note)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    Fmt.usdSigned(t.amount),
                    color = signColor(t.amount),
                    fontWeight = FontWeight.SemiBold
                )
                // Confirmed, like the identical control on the Activity tab. This one
                // deleted on a single tap - a 20dp icon inside a row whose whole width
                // opens the editor, so the near-miss went the destructive way.
                IconButton(onClick = { onDeleteTxn(t.id) }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = Red, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** The six figures people actually look up first, duplicated onto the Overview tab. */
private val KEY_NUMBER_KEYS = listOf(
    "marketCap", "peTrailing", "peForward", "dividendYield", "beta", "epsTrailing"
)

// ============================================================================== News

@Composable
private fun NewsTab(
    items: List<com.tj.portfolio.data.NewsItem>,
    filings: List<com.tj.portfolio.data.InsiderFiling>,
    loadingNews: Boolean,
    symbol: String,
    onOpenUrl: (String, String) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {

        if (filings.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Insider filings (SEC Form 4)")
                }
            }
            // Same row as the Feed's Insider tab, deliberately - the two used to render
            // filings differently and this one showed only EDGAR's boilerplate form name.
            // The symbol badge is dropped because every row here is this stock.
            items(filings, key = { it.accession }) { f ->
                InsiderRow(f, showSymbol = false) {
                    if (f.url.isNotBlank()) onOpenUrl(f.url, f.headline())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            item {
                Text(
                    "Insiders have up to two business days to report a trade, so these are " +
                        "recent filings rather than live activity. Trades badged 10b5-1 were " +
                        "scheduled in advance under a trading plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                SectionHeader(if (items.isEmpty()) "News" else "News (${items.size})")
            }
        }

        if (loadingNews && items.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }

        items(items, key = { newsKey(it) }) { n ->
            // A headline with no link had a click target that silently did nothing: an
            // empty URI throws inside the Intent and runCatching swallowed it, so the row
            // just felt broken. Only linked headlines are tappable now.
            val hasLink = n.url.isNotBlank()
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (!hasLink) Modifier
                        else Modifier.clickable { onOpenUrl(n.url, n.title) }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    n.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (n.summary.isNotBlank() && !n.summary.equals(n.title, true)) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        n.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(n.source)
                        if (n.published > 0) append("  -  ").append(Fmt.relative(n.published))
                        if (hasLink) append("  -  tap to read")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        if (!loadingNews && items.isEmpty()) {
            item {
                Text(
                    "No news found for $symbol right now.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * The price that belongs at the right-hand tip of an intraday chart, or 0 when none does.
 *
 * The 1D line ends at the last regular print, so it is only extended by the live regular
 * price and only while the market is open. The after-hours line ends at the last extended
 * print, so it is extended by `extPrice` and only once the regular session has closed.
 * Outside those two cases the chart's own last point is already the truth and moving it
 * would paint one session's price onto another session's line.
 */
private fun liveEdgePrice(
    q: com.tj.portfolio.data.Quote?,
    range: com.tj.portfolio.data.ChartRange
): Double {
    if (q == null) return 0.0
    return when (range) {
        com.tj.portfolio.data.ChartRange.D1 ->
            if (q.marketState == "OPEN" && q.price > 0.0) q.price else 0.0
        com.tj.portfolio.data.ChartRange.OVERNIGHT ->
            if (q.marketState != "OPEN") (q.extPrice ?: 0.0).coerceAtLeast(0.0) else 0.0
        else -> 0.0
    }
}
