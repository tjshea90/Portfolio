package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.candleMs
import com.tj.portfolio.data.MetricCatalog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
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
 * The tab strip, extracted so the crash below can be reproduced in a test.
 *
 * ============================================================================
 * THE CRASH THIS FIXES - it killed the app from TJ's phone, twice in a minute.
 * ============================================================================
 *
 *     java.lang.IndexOutOfBoundsException: Index 5 out of bounds for length 5
 *         at androidx.compose.material3.TabRowKt$ScrollableTabRow$1.invoke(TabRow.kt:1409)
 *
 * Material3's DEFAULT indicator is `tabPositions[selectedTabIndex]`, and those two values
 * come from different places: `selectedTabIndex` is read during composition, while
 * `tabPositions` is produced by the strip's own measure pass. They are equal in length only
 * while the number of tabs is constant.
 *
 * It is not constant here. [visibleTabs] hides Holdings on anything that is not a fund, and
 * `isFund` is not known when the screen opens - it arrives with the holdings lookup, about a
 * second later. So:
 *
 *   1. open a stock: fund status unknown, FIVE tabs, indices 0..4
 *   2. tap News - the last one - index 4, and its headlines start loading
 *   3. the lookup returns "fund": `tabs` grows to SIX, so `indexOf(NEWS)` is now 5
 *   4. the strip recomposes with `selectedTabIndex = 5` while `tabPositions` still holds
 *      the five entries the previous measure pass produced - and the default indicator
 *      reads `tabPositions[5]`. Length 5. Dead.
 *
 * Which is exactly what TJ reported: pressing News "on some stocks" - the ones that turn
 * out to be funds - "loaded for a second then the whole app crashed and closed".
 *
 * `coerceAtLeast(0)` did not help: that guards `indexOf` returning -1 when the list SHRINKS
 * under a selection. This is the list GROWING, where `indexOf` is perfectly valid and it is
 * the position list that is behind.
 *
 * THE FIX IS TO INDEX THE LIST WE WERE ACTUALLY HANDED. A custom indicator clamps to
 * `positions.lastIndex`, so a one-frame disagreement paints the indicator under the wrong
 * tab for a single frame instead of taking the process down. Never trust `selectedTabIndex`
 * as an index into `tabPositions`.
 */
@Composable
internal fun DetailTabRow(
    tabs: List<DetailTab>,
    selected: DetailTab,
    onSelect: (DetailTab) -> Unit
) {
    // INDEXED INTO THE VISIBLE LIST, NOT THE ENUM. `tab.ordinal` was fine while every tab was
    // always shown; with Holdings hidden on ordinary shares the ordinals no longer match the
    // tabs on screen, and the indicator would sit under the wrong one.
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = index,
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.background,
        indicator = { positions ->
            // The whole point: bound by what THIS list actually contains.
            if (positions.isNotEmpty()) {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[index.coerceAtMost(positions.lastIndex)])
                )
            }
        }
    ) {
        tabs.forEach { t ->
            Tab(
                selected = selected == t,
                onClick = { onSelect(t) },
                text = {
                    Text(
                        t.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected == t) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}

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
     * Today's Day Trading pick for this symbol, or null for every stock that is not one right
     * now (Round 70). Re-derived from the live `research` state on every recomposition rather
     * than captured once - the same discipline [ResearchScreen] used to apply to its own
     * now-removed explanation dialog: while the Day Trading tab is open its live loop keeps
     * refining this row, and a snapshot taken at navigation time would freeze on whatever the
     * plan looked like at that instant.
     */
    val researchSet by vm.research.collectAsState()
    val dayTradingRow = researchSet.dayTrading.firstOrNull { it.symbol == symbol }
    // KEEP THAT ROW LIVE WHILE THIS SCREEN SHOWS IT (full-tests audit 2026-09-22, U-M5). The
    // comment above assumed the Day Trading tab's loop keeps refining the row - but opening this
    // screen from that tab disposes it, and its `onDispose` stopped the loop. For this symbol
    // only, and only while it is a pick: a stock that is not one fetches nothing extra here.
    val isDayTradingPick = dayTradingRow != null
    androidx.compose.runtime.DisposableEffect(symbol, isDayTradingPick) {
        if (isDayTradingPick) vm.startDayTradingLive(only = symbol)
        onDispose { if (isDayTradingPick) vm.stopDayTradingLive() }
    }
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
    val recommendationMap by vm.recommendations.collectAsState()
    val recommendation = recommendationMap[symbol]

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
    var pending by rememberSaveable(stateSaver = PendingAction.Saver) { mutableStateOf<PendingAction?>(null) }
    // Stored as an id, not the Txn itself - Txn isn't Saveable, and this is looked up against
    // `txns` below once it's in scope, so it survives a process death the same way the dialog's
    // own fields now do (see TxnEditor.kt).
    var editingTxnId by rememberSaveable { mutableStateOf<Long?>(null) }
    var addingTxn by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    var showRecommendation by remember(symbol) { mutableStateOf(false) }

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
    val quotesMap by vm.quotes.collectAsState()
    val chartLoadingSet by vm.chartLoading.collectAsState()
    val chartKey = vm.chartKey(symbol, chartRange)
    val chart = chartMap[chartKey]
    val chartLoading = chartLoadingSet.contains(chartKey)

    // ---- WHAT EACH RANGE DID, for the figures on the range chips (Round 62).
    //
    // Built HERE rather than inside `RangeChips`, because this is the only place that holds
    // all three things the answer needs: the cached series for every range, the quote, and
    // `liveEdgePrice`'s per-range rule for which live price belongs to which window. The chip
    // for the selected range therefore prints exactly what the readout under the chart prints
    // - see `rangePct`.
    //
    // COSTS NOTHING ON THE WIRE. `loadChart` already reads every cached range for a symbol
    // off disk in one query, so these are ranges the app is holding anyway; a window that has
    // never been fetched simply has no figure, and no request is made to give it one.
    //
    // Keyed on the chart map and the quote: the quote moves four times a minute and the live
    // edge moves with it, so the intraday chips stay honest, and eight map lookups is a great
    // deal cheaper than the allocation `withLiveEdge` would do per chip per tick.
    val chartPerf = remember(chartMap, row?.quote, symbol) {
        val out = HashMap<com.tj.portfolio.data.ChartRange, Double>(16)
        com.tj.portfolio.data.ChartRange.entries.forEach { r ->
            val live = liveEdgePrice(row?.quote, r, chartMap[vm.chartKey(symbol, r)])
            rangePct(chartMap[vm.chartKey(symbol, r)], live, live > 0.0)?.let { out[r] = it }
        }
        out
    }
    val chartLoadingRanges = remember(chartLoadingSet, symbol) {
        com.tj.portfolio.data.ChartRange.entries
            .filterTo(HashSet()) { chartLoadingSet.contains(vm.chartKey(symbol, it)) }
    }

    // ---- THE BENCHMARK OVERLAY (Round 63).
    //
    // Off by default and remembered once switched on, because whether you read a chart
    // against the market is a habit rather than a per-symbol decision.
    //
    // NEVER OFFERED ON THE BENCHMARK ITSELF. Drawing SPY against SPY is a flat line at zero
    // with a second flat line on top of it, and a toggle that produces that is a toggle that
    // looks broken.
    val isBenchmark = symbol.equals(BENCHMARK_SYMBOL, true)
    var compareOn by remember { mutableStateOf(vm.chartCompare()) }
    val compareKey = vm.chartKey(BENCHMARK_SYMBOL, chartRange)
    val compareSeries = if (compareOn && !isBenchmark) chartMap[compareKey] else null
    // The benchmark's own live price (`quotesMap[BENCHMARK_SYMBOL]`, below with `drawnCompare`),
    // when the app happens to hold a quote for it - it does whenever SPY is held or watched,
    // and after any research pass that touched it. See the note on `PriceChart.compareLivePrice`
    // for why the two tips have to be the same moment.


    // The "News" chip on a holding row used to open this screen and then try to SCROLL to
    // the news section, which was fragile arithmetic over a list whose length changed as
    // headlines arrived. With tabs it just opens the right tab.
    // SAVEABLE (full-tests audit 2026-09-22, U-M1): the app keeps this screen's saved state
    // while an article opened from it is on top, so Back returns to the tab it was opened from.
    var tab by rememberSaveable(symbol) {
        mutableStateOf(if (scrollToNews) DetailTab.NEWS else DetailTab.OVERVIEW)
    }

    // A SELECTION THAT IS NO LONGER ON SCREEN GOES BACK TO OVERVIEW.
    //
    // `tabs` shrinks when the Holdings data goes away - a memory trim at MODERATE drops
    // `_holdings`, and `isFund` reverts to unknown. Without this the tab row's indicator
    // jumps to Overview (because `indexOf` returns -1 and is coerced to 0) while the body
    // below still renders the Holdings tab: two different tabs claiming to be selected.
    LaunchedEffect(tabs) { if (tab !in tabs) tab = DetailTab.OVERVIEW }

    // state.txns is the full history and this screen recomposes on every price tick, so
    // the filter is keyed on the list identity - it only re-runs when the ledger changes.
    val txns = remember(state.txns, symbol) { state.txns.filter { it.symbol == symbol } }
    val editingTxn = remember(txns, editingTxnId) { txns.firstOrNull { it.id == editingTxnId } }

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

    // BUY/HOLD/SELL: no request of its own - `loadRecommendation` reads whatever
    // `loadFundamentals` above just fetched or is fetching. Keyed on whether fundamentals have
    // ARRIVED (not on the object itself, which would re-fire this every 6-hour refresh) so it
    // fires once they show up; `loadRecommendation`'s own once-a-trading-day gate makes that
    // firing a no-op on every day but the first.
    // AND ON WHETHER A PRICE HAS ARRIVED (full-tests audit 2026-09-22, U-L4). A verdict needs
    // both; when the fundamentals landed first this fired at price 0, `Recommend.build` refused
    // it, and nothing ever fired again - the badge sat on "..." for the rest of the session.
    // KEYED ON THE DATA, NOT ON ITS PRESENCE (full test 2026-09-24, S-2). `fundamentals != null`
    // flipped once - when the RATINGS row landed - so a verdict scored from analysts alone was
    // never recomputed when the core numbers arrived seconds later: yesterday's S-2 fix
    // ("provisional, recomputed when the fundamentals change") only worked on the Portfolio
    // tab, which is not composed under this screen. `loadRecommendation` returns at once when
    // today's verdict is already settled, so a re-fire per merge costs nothing.
    LaunchedEffect(symbol, fundamentals, price > 0.0) {
        if (fundamentals != null && price > 0.0) vm.loadRecommendation(symbol, price)
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
    // READ THROUGH `rememberUpdatedState`, AND KEYED ONLY ON THE OWNER.
    //
    // Keying the effect on symbol/range/tab looks harmless and is not: `LifecycleRegistry`
    // brings a newly added observer up to the current state, which means it replays ON_START
    // immediately. Re-registering on every tab tap and every range chip would therefore have
    // fired all six loads again each time. The calls are cache-first and guarded, so nothing
    // would have gone on the wire - but relying on that is how a cheap call becomes an
    // expensive one two rounds later. This way the observer is registered once per screen.
    val currentSymbol by rememberUpdatedState(symbol)
    val currentRange by rememberUpdatedState(chartRange)
    val currentTab by rememberUpdatedState(tab)
    val currentCompare by rememberUpdatedState(compareOn && !isBenchmark)
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.loadNews(currentSymbol)
                vm.loadFundamentals(currentSymbol)
                vm.loadInsider(currentSymbol)
                vm.loadHoldings(currentSymbol)
                vm.loadChart(currentSymbol, currentRange)
                if (currentCompare) vm.loadChart(BENCHMARK_SYMBOL, currentRange)
                if (currentTab == DetailTab.ANALYSTS) vm.loadRatings(currentSymbol)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- PINCH TO ZOOM (Round 63).
    //
    // TJ: *"I can pinch gesture out on a stock chart and it will zoom in gradually all the
    // way down to 5 minute time graph, or I can pinch gesture in to zoom back out, all the
    // way to the stock's all time chart."*
    //
    // A zoom step is exactly a range change - the same state the chips write - so the two
    // routes cannot disagree about what is on screen, and everything already built on
    // `chartRange` (the per-window figures, the caption, the live edge rule, the disk cache)
    // works unchanged.
    //
    // ONE THING HAD TO BE ADDED, AND IT IS THE WHOLE COST OF THIS FEATURE: a spread that
    // crosses four rungs in half a second would otherwise start four chart fetches, three of
    // them for windows the user was passing THROUGH and never looked at. `zoomSettling`
    // marks the range change as gestural, and the loader below waits [CHART_SETTLE_MS] before
    // going near the network - and because a `LaunchedEffect` keyed on the range is
    // CANCELLED when the range changes again, every intermediate rung cancels its own
    // pending fetch. Only the rung the fingers stop on ever sends anything.
    //
    // Windows already on disk are unaffected: `loadChart`'s cache read publishes every cached
    // range for the symbol in one query, so zooming across ground already covered is instant
    // and free either way.
    var zoomSettling by remember(symbol) { mutableStateOf(false) }
    val onChartZoom: (Int) -> Unit = remember(symbol) {
        { steps ->
            val next = com.tj.portfolio.data.ChartRange.zoomed(chartRange, steps)
            if (next != null && next != chartRange) {
                zoomSettling = true
                chartRange = next
                vm.setChartRange(next)
            }
        }
    }

    // ---- THE CONTINUOUS ZOOM (Round 64).
    //
    // TJ: *"make the pinch to zoom smooth instead of chopping between intervals."*
    //
    // Round 63's pinch WAS the range change, so the smallest thing it could do was jump a
    // whole rung. Here the window and the range are separated: the fingers move
    // [chartWindow], which is any two moments and scales as smoothly as they do, and the
    // range underneath it is re-chosen only when the window has moved far enough that the
    // current one's candles can no longer draw it. Most frames change nothing but the window,
    // which costs a redraw and no network at all.
    //
    // NULL MEANS "THE WHOLE SERIES", which is what a range chip selects and what the chart
    // draws when nothing has been pinched. Keyed on the symbol so opening another stock does
    // not inherit the last one's window.
    var chartWindow by remember(symbol) {
        mutableStateOf<com.tj.portfolio.data.ChartWindow?>(null)
    }

    // ---- THE FULL-SCREEN VIEWER (Round 64). See [FullScreenChart] for what it does and why
    // it forces the sensor orientation while it is open.
    var chartExpanded by remember(symbol) { mutableStateOf(false) }

    // True while a gesture owns the chart's window: a pinch, or - since round 65 - a one-finger
    // pan on a zoomed chart. The re-anchor below stands back until the fingers lift, because a
    // fetch landing mid-gesture would otherwise reset the window under them for a frame before
    // the gesture wrote it again.
    var chartPinching by remember(symbol) { mutableStateOf(false) }


    // How far a zoom may go, from EVERY series the app is holding for this symbol rather than
    // from the one on screen - so pinching out past the end of a one-month chart continues
    // into the five-year one instead of stopping at a boundary the user cannot see.
    val chartBounds = remember(chartMap, symbol, chart) {
        windowBounds(
            chart,
            com.tj.portfolio.data.ChartRange.entries.mapNotNull {
                chartMap[vm.chartKey(symbol, it)]
            }
        )
    }

    // ---- KEEP DRAWING WHILE A ZOOM CROSSES INTO A RANGE NOT FETCHED YET (full test 2026-09-24,
    // C-1).
    //
    // A pinch or pan whose window needs a range this symbol has no series for (the first 5D
    // after opening a stock, a finer range zooming in, a 1D window panned back past today's
    // open) used to hand PriceChart a null series mid-gesture. PriceChart then swaps its chart
    // Box for the placeholder Box - a NEW layout node with a NEW pointer-input node, and
    // Compose only hit-tests fingers on DOWN - so the fingers already on the glass were
    // orphaned: the gesture died mid-pinch, and for the 380 ms settle the screen said "No 5D
    // chart available". Round 63's "the placeholder carries the same surface" only holds for a
    // gesture that STARTS on the placeholder.
    //
    // So while a zoom window is live and its series is still coming (the fingers are down, the
    // settle is running or the fetch is in flight), the chart keeps drawing the last series it
    // drew, WITH ITS OWN RANGE, so labels and baselines stay consistent with the line. The
    // window itself moves on regardless, and the new series takes over the moment it lands. A
    // real failure (nothing in flight) still shows the placeholder.
    val lastDrawn = remember(symbol) {
        arrayOfNulls<Pair<com.tj.portfolio.data.ChartSeries, com.tj.portfolio.data.ChartRange>>(1)
    }
    if (chart != null && !chart.isEmpty) lastDrawn[0] = chart to chartRange
    val (drawnChart, drawnRange) = bridgedChart(
        chart, chartRange, lastDrawn[0],
        zoomed = chartWindow != null,
        awaiting = chartPinching || zoomSettling || chartLoading
    )
    // THE BENCHMARK FOR THE RANGE ACTUALLY DRAWN (review 2026-09-24, R1-5). While a zoom's
    // series is still coming the chart bridges with the OLD range - and pairing that with SPY's
    // NEW range compared "the stock since yesterday's close" with "SPY over five days". No
    // matching benchmark held: no overlay, rather than a wrong one.
    val drawnCompare = if (drawnRange == chartRange) compareSeries
    else if (compareOn && !isBenchmark) chartMap[vm.chartKey(BENCHMARK_SYMBOL, drawnRange)] else null

    val onChartWindow: (com.tj.portfolio.data.ChartWindow) -> Unit = { w ->
        chartWindow = w
        // WHICH SERIES TO DRAW IT FROM. `rangeForLookback`, not the window's span: every
        // range this provider serves ends at now, so a window panned into the past needs a
        // rung that reaches back to it however narrow it is. See the note on that function.
        //
        // NEWEST CANDLE PLUS ONE CANDLE (Round 64 sweep). A candle is stamped at its OPEN,
        // so the last point of a five-year weekly series can be six days behind today and a
        // monthly one a month behind - and a lookback measured from there came out that much
        // short, which is how zooming into the right-hand edge of an all-time chart asked for
        // a series that does not cover the window and drew a blank chart.
        //
        // NOT `System.currentTimeMillis()`, which was the first attempt and is wrong outside
        // market hours: at noon on a Saturday the 1D chart's newest point is Friday afternoon,
        // so a clock-based lookback is fifty hours and the first pinch of the weekend would
        // swap the five-minute chart for 30-minute candles - on the one view whose entire
        // purpose is five-minute detail. One candle of lag is the real error, and it is what
        // is corrected for.
        val newest = (chartBounds?.endMs ?: w.endMs) + chartRange.candleMs
        val next = com.tj.portfolio.data.ChartRange.rangeForLookback(
            newest - w.startMs, chartRange
        )
        if (next != chartRange) {
            // The same settle the rung ladder used, and for the same reason: a spread that
            // crosses three rungs in half a second must not start three fetches for windows
            // the fingers were only passing through.
            zoomSettling = true
            chartRange = next
            vm.setChartRange(next)
        }
    }

    // ---- RE-ANCHORING THE WINDOW WHEN THE DATA MOVES UNDER IT (Round 64 sweep).
    //
    // A zoom is what MAKES the bounds change: the window chooses a finer or wider range, the
    // fetch lands, and the app is suddenly holding data it did not have when the fingers left
    // the glass. Two things go wrong if nothing re-anchors the window, and both end with a
    // chart the user cannot read:
    //
    //   * the optimistic pinch-out reach (see [windowBounds]) lets a window be wider than the
    //     all-time series turns out to be, which would draw years of nothing before the IPO;
    //   * a window pinned to the right-hand edge of a coarse chart can land in a week the
    //     finer series does not cover, and the chart goes blank.
    //
    // `ChartWindow.clamped` pulls it back in, keeping it at the newest data if that is where
    // it already was. And a window that now covers everything stops being a zoom at all - it
    // is set back to null, which is what the chart draws when nothing has been pinched, and
    // is what stops the "Reset zoom" chip appearing over an unzoomed chart.
    LaunchedEffect(chartBounds, chart, chartPinching) {
        if (chartPinching) return@LaunchedEffect
        val b = chartBounds ?: return@LaunchedEffect
        val w = chartWindow ?: return@LaunchedEffect
        // ONE CANDLE OF SLACK: this effect holds the NEW bounds and a window still at the old
        // edge, so what it must tolerate is the drift the new data introduced.
        val pinned = com.tj.portfolio.data.ChartWindow.atRightEdge(w, b, chartRange.candleMs)
        val next = com.tj.portfolio.data.ChartWindow.clamped(w, b, pinned)
            ?: return@LaunchedEffect

        // ---- HAS IT STOPPED BEING A ZOOM?
        //
        // Measured against the SERIES ON SCREEN, not against `chartBounds`. The bounds are how
        // far a pinch may reach and are deliberately optimistic - up to forty years, so that a
        // pinch-out can ask for a series nobody has fetched yet. Judged against those, a window
        // could never be "the whole chart" and the screen stayed permanently in its zoomed
        // state: caption, span label and a "Reset zoom" chip over a chart that looked exactly
        // as it started.
        // ONE CANDLE OF SLACK ON TOP, not a flat three percent (Round 64 sweep 3). A candle
        // is stamped at its open, so a monthly series' last point can be a month behind the
        // newest data - and on a stock listed only a couple of years ago that excess is more
        // than three percent of the whole chart. The window was then never recognised as the
        // whole of it, and the screen kept an axis a few percent wider than the line for good:
        // an empty gutter at the right and an end-date label naming a date past the last point.
        // THE SAME PREDICATE THE CHART USES to decide whether it has been moved (sweep 5),
        // with one candle of extra tolerance for a coarse series stamped at its open. When the
        // two differed - span-only here, start-sensitive there - a slight pinch plus a
        // sideways drag panned the chart and then had the pan discarded on finger-lift.
        val series = com.tj.portfolio.data.ChartWindow.of(chart)
        if (com.tj.portfolio.data.ChartWindow.isDefaultView(
                next, series, chartRange.candleMs
            ) && series != null
        ) {
            chartWindow = null
            return@LaunchedEffect
        }
        chartWindow = next

        // AND IS THE RANGE UNDER IT STILL THE RIGHT ONE? Clamping can change the window by a
        // lot - a forty-year request landing on a stock that listed in 2019 - and leaving the
        // range where the un-clamped window put it would draw the new window from candles
        // chosen for a different one.
        val newest = b.endMs + chartRange.candleMs
        val want = com.tj.portfolio.data.ChartRange.rangeForLookback(
            newest - next.startMs, chartRange
        )
        if (want != chartRange) {
            zoomSettling = true
            chartRange = want
            vm.setChartRange(want)
        }
    }

    // The chart follows the range the user picked. `loadChart` reads its disk cache first
    // and only reaches for the network when what it holds is past that range's TTL, so
    // flicking back and forth between 1D and 1Y costs nothing after the first look.
    LaunchedEffect(symbol, chartRange) {
        if (zoomSettling) {
            kotlinx.coroutines.delay(CHART_SETTLE_MS)
            // Cleared only once a fetch actually goes out for this rung, so a pinch that
            // stops here leaves the screen back in its ordinary, immediate state.
            zoomSettling = false
        }
        vm.loadChart(symbol, chartRange)
    }

    // THE BENCHMARK IS FETCHED THROUGH THE SAME `loadChart`, which is the entire reason this
    // costs almost nothing. It shares the disk cache, the per-range TTL, the in-flight guard
    // and the failure backoff - so the second, third and tenth stock opened on the same range
    // all draw the same cached SPY series with no request at all, and the overlay costs one
    // fetch per range per TTL for the whole app rather than one per stock.
    //
    // Its own effect rather than a second call inside the one above: the toggle can be turned
    // on without the range changing, and that must fetch.
    LaunchedEffect(compareOn, isBenchmark, chartRange, zoomSettling) {
        if (compareOn && !isBenchmark && !zoomSettling) {
            vm.loadChart(BENCHMARK_SYMBOL, chartRange)
        }
    }

    // AND IT KEEPS ITSELF CURRENT WHILE YOU WATCH IT.
    //
    // The effect above fires on a change of symbol or range and never again, so a stock left
    // open for an hour showed the candles it had at load time for the whole hour - only its
    // right-hand tip moved, because that is drawn from the live quote. TJ asked for the
    // charts to "only periodically refresh automatically, but refresh every time I gesture
    // pull down", and this is the first half of that sentence.
    //
    // Keyed on `lastRefresh`, which the quote poll stamps, so it is re-evaluated about four
    // times a minute - and `loadChart` answers all but one in twenty of those from memory
    // without starting a coroutine. The cadence that actually reaches the network is the
    // range's own TTL: five minutes on a five-minute candle, a day on a monthly one.
    LaunchedEffect(state.lastRefresh, symbol, chartRange, zoomSettling) {
        // `zoomSettling` is in the key list so a quote tick landing mid-pinch cannot fire the
        // very fetch the settle window exists to defer - the tick clock runs four times a
        // minute and a pinch takes about a second, so without this the two would collide
        // often enough to notice.
        if (!zoomSettling) {
            vm.loadChart(symbol, chartRange)
            // THE BENCHMARK TOO (full test 2026-09-23, N-5): its own effect above fires on a
            // change of toggle or range only, so SPY froze at load time while the stock line kept
            // refreshing - and "vs SPY" compared a live stock with a stale benchmark. Same TTL,
            // same in-flight guard: at most one request per range per five minutes.
            if (compareOn && !isBenchmark) vm.loadChart(BENCHMARK_SYMBOL, chartRange)
        }
    }

    // REMEMBERED (Round 57). An unremembered lambda is a new object on every recomposition,
    // and this screen recomposes on every quote tick - so `Refreshable` and the header button
    // were being invalidated four times a minute for a callback that had not changed. Keyed
    // on what it actually captures.
    val refreshEverything = remember(symbol, tab, chartRange, compareOn, isBenchmark) {
        {
            vm.refresh(manual = true)
            vm.loadNews(symbol, force = true)
            vm.loadInsider(symbol, force = true)
            vm.loadFundamentals(symbol, force = true)
            // TJ's rule for this round, exactly: cache and refresh periodically on its own,
            // but a pull-down always re-fetches. `force` is what bypasses the TTL.
            vm.loadChart(symbol, chartRange, force = true)
            if (compareOn && !isBenchmark) {
                vm.loadChart(BENCHMARK_SYMBOL, chartRange, force = true)
            }
            if (tab == DetailTab.ANALYSTS) vm.loadRatings(symbol, force = true)
            // ---- UNCONDITIONAL (Round 66 audit, DET-7).
            //
            // THE BUG THIS FIXES. This was gated on `tab == HOLDINGS`, and the Holdings tab
            // only EXISTS once the register has already loaded - `tabs` includes it when
            // `fundHoldings?.isFund == true`, and a selection outside `tabs` is forced back to
            // Overview. So the condition was unreachable in exactly the situation a pull-down
            // is for: the lookup failed, there is no tab, and `holdingsRetry` is now refusing
            // every un-forced route back in for up to five minutes. Open SPY on a flaky
            // connection and the fund's own holdings never appeared, and the one gesture TJ
            // said should always re-fetch - "refresh every time I gesture pull down" - was
            // quietly skipping it. One request per deliberate pull, against a register cached
            // for twelve hours.
            vm.loadHoldings(symbol, force = true)
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
            // ---- `row == null` IS "WE KNOW NOTHING ABOUT THIS SYMBOL", NOT "YOU OWN IT".
            //
            // `row` is the held/watched row, or a transient watch-only row built from a cached
            // quote - so it is NULL for a symbol the user searched into view that is neither
            // held nor watched and has no quote yet. The test was `row?.watchOnly != true`,
            // and `null != true` is true, so that symbol got the OWNER's controls: "Edit
            // position" (opening the override dialog seeded from `row?.shares ?: 0.0`) and
            // "Add transaction", with no way to add it to the watchlist from the very screen
            // the user had just searched it into.
            //
            // Usually it lasted a second, until the quote landed. Offline, or when every
            // provider fails for that ticker, it was the permanent state. Ownership now has to
            // be positively known.
            if (row != null && !row.watchOnly) {
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
        //
        // TJ, with a screenshot of this exact header: *"move the buy sell hold tab from where
        // it currently is to somewhere around where the arrow points. don't change it's
        // function, only the placement."* The arrow pointed here - next to the price, not
        // down in the tab strip. `DetailTab.RECOMMENDATION` is gone; [RecommendationBadge] is
        // a plain flow element beside `PriceBlock` now, not an overlay, so it can never
        // collide with the after-hours column growing at a large font scale the way an
        // absolutely-positioned badge could.
        if (row != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    PriceBlock(row, big = true)
                }
                Spacer(Modifier.width(8.dp))
                RecommendationBadge(recommendation) { showRecommendation = true }
            }
        }

        Spacer(Modifier.height(6.dp))

        DetailTabRow(tabs, tab) { tab = it }

        Refreshable(
            refreshing = state.pulling(PULL_PRICES),
            onRefresh = refreshEverything
        ) {
            when (tab) {
                DetailTab.OVERVIEW -> OverviewTab(
                    state = state,
                    symbol = symbol,
                    row = row,
                    dayTradingRow = dayTradingRow,
                    tracked = tracked,
                    watched = row?.watched == true,
                    fundamentals = fundamentals,
                    txns = txns,
                    chart = drawnChart,
                    chartRange = drawnRange,
                    chartLoading = chartLoading,
                    chartPerf = chartPerf,
                    chartLoadingRanges = chartLoadingRanges,
                    onChartRange = { r ->
                        // A TAP IS NOT A ZOOM, and it CLEARS the settle window rather than
                        // merely not setting it. A pinch leaves `zoomSettling` true until its
                        // own deferred fetch runs; a chip tapped inside that window would
                        // otherwise inherit the 380ms pause and feel unresponsive for no
                        // reason. Tapping a range is an explicit choice of destination - there
                        // are no intermediate rungs to swallow.
                        zoomSettling = false
                        // A CHIP IS THE WHOLE OF ITS RANGE. Leaving a zoom window in place
                        // would show a slice of the newly selected series and label it "1Y",
                        // which is the chart lying about what it is showing.
                        chartWindow = null
                        chartRange = r
                        vm.setChartRange(r)
                    },
                    onChartZoom = onChartZoom,
                    chartWindow = chartWindow,
                    chartBounds = chartBounds,
                    onChartWindow = onChartWindow,
                    onResetChartWindow = { zoomSettling = false; chartWindow = null },
                    onExpandChart = { chartExpanded = true },
                    onChartPinching = { chartPinching = it },
                    compare = drawnCompare,
                    compareLive = liveEdgePrice(
                        drawnCompare?.let { quotesMap[BENCHMARK_SYMBOL] }, drawnRange, drawnCompare),
                    compareOn = compareOn && !isBenchmark,
                    compareOffered = !isBenchmark,
                    compareLoading = chartLoadingSet.contains(compareKey),
                    onToggleCompare = {
                        val next = !compareOn
                        compareOn = next
                        vm.setChartCompare(next)
                    },
                    onInfo = { infoKey = it },
                    onEditPosition = { pending = PendingAction(symbol, RowAction.EDIT_POSITION) },
                    onAddTxn = { addingTxn = true },
                    onWatchToggle = { pending = PendingAction(symbol, RowAction.WATCH_TOGGLE) },
                    onDeletePosition = { pending = PendingAction(symbol, RowAction.DELETE) },
                    onEditTxn = { editingTxnId = it.id },
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

    // ---- THE FULL-SCREEN CHART.
    //
    // Outside the `Scaffold` because it is a window of its own, and the same state feeds it as
    // feeds the inline chart - so the zoom window, the range and the comparison are shared:
    // expand a zoomed chart and it opens zoomed, close it and the screen behind is where the
    // fingers left it.
    if (chartExpanded) {
        FullScreenChart(
            symbol = symbol,
            series = drawnChart,       // C-1: bridged across an unfetched range, as inline
            range = drawnRange,
            loading = chartLoading,
            onRange = { r ->
                zoomSettling = false
                chartWindow = null
                chartRange = r
                vm.setChartRange(r)
            },
            perf = chartPerf,
            loadingRanges = chartLoadingRanges,
            livePrice = liveEdgePrice(row?.quote, drawnRange, drawnChart),
            liveEdge = liveEdgePrice(row?.quote, drawnRange, drawnChart) > 0.0,
            window = chartWindow,
            windowBounds = chartBounds,
            onWindow = onChartWindow,
            onResetWindow = { zoomSettling = false; chartWindow = null },
            onZoomingChanged = { chartPinching = it },
            compare = if (compareOn && !isBenchmark) drawnCompare else null,
            compareLabel = BENCHMARK_SYMBOL,
            compareLivePrice = liveEdgePrice(
                drawnCompare?.let { quotesMap[BENCHMARK_SYMBOL] }, drawnRange, drawnCompare),
            onClose = { chartExpanded = false }
        )
    }

    // ---- the "i" sheet. One dialog for every number on every tab.
    infoKey?.let { key ->
        val f = fundamentals ?: com.tj.portfolio.data.Fundamentals(symbol = symbol)
        val explanation =
            if (MetricCatalog.byKey.containsKey(key)) Explain.of(key, f, price)
            else Explain.analystTopic(key, f, price)
        ExplainDialog(explanation) { infoKey = null }
    }

    if (showRecommendation) {
        RecommendationDialog(recommendation, symbol) { showRecommendation = false }
    }

    if (addingTxn) {
        TxnEditorDialog(
            presetSymbol = symbol,
            onDismiss = { addingTxn = false },
            onSave = { t -> vm.addTxnRecord(t, "${t.type} $symbol saved"); addingTxn = false }
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
            onDismiss = { editingTxnId = null },
            // Confirmed first, same as the list's trash icon - see ActivityScreen's matching note.
            onDelete = { editingTxnId = null; confirmDelete = t.id },
            onSave = { u -> vm.updateTxn(u, "Transaction updated"); editingTxnId = null }
        )
    }

    RowActionHost(
        vm, state, pending,
        onOpen = { },
        onNews = { },
        onDone = {
            // ---- READ THE LIVE STATE, NOT THE ONE THIS LAMBDA CLOSED OVER.
            //
            // `state` is a plain `UiState` PARAMETER, captured by value when this lambda was
            // created. The DELETE confirm handler calls `vm.deleteSymbol(symbol)` - which
            // recomputes and publishes synchronously - and then `onDone()`, all inside the
            // same click. Compose does not recompose in the middle of a click handler, so the
            // executing lambda still held the PRE-DELETE `state`, whose `rows` still contained
            // the symbol. `none { }` was therefore always false and `onBack()` never ran,
            // leaving the user on a detail screen for a position that no longer exists.
            //
            // `vm.ui.value` is the published state as of right now, which is what the question
            // "is this symbol still held?" actually means here.
            val wasDelete = pending?.action == RowAction.DELETE
            pending = null
            if (wasDelete && vm.ui.value.rows.none { it.symbol == symbol }) onBack()
        }
    )
}

// ========================================================================== Overview

/**
 * THE RECORDS DISAGREE WITH THEMSELVES - see [com.tj.portfolio.domain.Position.oversold].
 * More shares have been sold for this symbol than were ever bought, which the ledger cannot
 * resolve on its own: it books the proceeds in full and takes cost off only for shares it
 * actually had, so the realized figure is overstated by whatever the missing buy cost.
 *
 * ONE COMPOSABLE, CALLED FROM BOTH BRANCHES of the position card below - the "still holds
 * shares" one and the "no shares held" one - because the far likelier shape of an uncovered
 * sale (sell out entirely) lands in the second, and a single copy of this warning is what
 * keeps the two from silently drifting the way `txnSubtitle` (ActivityScreen.kt) once did.
 */
@Composable
private fun OversoldWarning(shares: Double) {
    if (shares <= 1e-9) return
    Text(
        "${Fmt.shares(shares)} more shares have been sold than bought. A buy is probably " +
            "missing from your records - until it is added, the realized gain here is " +
            "overstated by whatever those shares cost.",
        style = MaterialTheme.typography.bodySmall,
        color = redText,
        modifier = Modifier.padding(top = 6.dp)
    )
}

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
    /** Today's Day Trading pick for this symbol, or null - see the note at its call site. */
    dayTradingRow: com.tj.portfolio.data.ResearchRow?,
    tracked: Boolean,
    /**
     * Whether the symbol is on the WATCHLIST, which for a held stock is a different question
     * from [tracked] and from `row.watchOnly` - see [Row.watched] (Round 66 audit, REG-3).
     */
    watched: Boolean,
    fundamentals: com.tj.portfolio.data.Fundamentals?,
    txns: List<Txn>,
    chart: com.tj.portfolio.data.ChartSeries?,
    chartRange: com.tj.portfolio.data.ChartRange,
    chartLoading: Boolean,
    /** What each window did, for the figures on the range chips. See `rangePct`. */
    chartPerf: Map<com.tj.portfolio.data.ChartRange, Double>,
    /** Ranges with a fetch in flight, so a blank chip can say which kind of blank it is. */
    chartLoadingRanges: Set<com.tj.portfolio.data.ChartRange>,
    onChartRange: (com.tj.portfolio.data.ChartRange) -> Unit,
    /** One rung of pinch zoom: +1 zooms in, -1 zooms out. See `chartGestures`. */
    onChartZoom: (Int) -> Unit,
    /** The stretch of time on screen, or null for the whole series. See [ChartWindow]. */
    chartWindow: com.tj.portfolio.data.ChartWindow?,
    /** How far a zoom may go, from every series the app holds for this symbol. */
    chartBounds: com.tj.portfolio.data.ChartWindow?,
    onChartWindow: (com.tj.portfolio.data.ChartWindow) -> Unit,
    /** Undo the zoom: draw the whole of the fetched series again. */
    onResetChartWindow: () -> Unit,
    /** Opens the chart full screen. See [FullScreenChart]. */
    onExpandChart: () -> Unit,
    /** True while a pinch is in progress, so the screen can stop re-anchoring the window. */
    onChartPinching: (Boolean) -> Unit,
    /** The benchmark series for this range, or null when the overlay is off or unloaded. */
    compare: com.tj.portfolio.data.ChartSeries?,
    /** The benchmark's live price, so both lines end at the same instant. */
    compareLive: Double,
    compareOn: Boolean,
    /** False on the benchmark's own screen, where the toggle would draw two flat lines. */
    compareOffered: Boolean,
    compareLoading: Boolean,
    onToggleCompare: () -> Unit,
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
    // KEYED ON THE SYMBOL (Round 66 audit, DET-8). The detail screen is one composition slot
    // in a `when`, so changing stock in place does not tear this tab down - and an unkeyed
    // scroll state therefore survived the change. Open SPY, tap through to NVDA, scroll, press
    // Back, and SPY's Overview opened part-way down its own page, past its chart and price
    // header, at NVDA's offset.
    // Saveable for the same reason as the tab above (U-M1), still keyed on the symbol.
    val listState = rememberSaveable(symbol, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    val q = row?.quote
    val price = row?.price ?: 0.0

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // ---- TODAY'S DAY TRADING PLAN (Round 70), FIRST - Tj: "in addition to what it
        // already shows, there are tabs for more information". This is "what it already
        // shows": the exact explanation the old `DayTradingDetailDialog` gave when a Day
        // Trading card was tapped, now inline at the top of the real detail screen instead of
        // behind a modal, with the rest of the screen - chart, Stats, Analysts, Earnings,
        // News, and the watchlist star already in the header above - available underneath it.
        if (dayTradingRow != null) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "TODAY'S DAY TRADING PLAN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    // EQUITY IS PASSED SO THE PLAN CAN SAY HOW MANY SHARES (Round 73). The
                    // sizing is only ever as real as the portfolio behind it, so an empty
                    // ledger passes 0.0 and `ResearchScore.positionSize` returns null rather
                    // than sizing a trade against an account that does not exist.
                    DayTradingPlanContent(dayTradingRow, state.totals?.totalEquity ?: 0.0)
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RangeChips(
                        selected = chartRange,
                        onSelect = onChartRange,
                        perf = chartPerf,
                        loading = chartLoadingRanges,
                        modifier = Modifier.weight(1f)
                    )
                    if (compareOffered) {
                        Spacer(Modifier.width(6.dp))
                        // OUTSIDE the chips' horizontal scroll, so it is always reachable.
                        // Put inside, it would be the ninth item in a row that already does
                        // not fit and would need a thumb-flick to find.
                        CompareToggle(
                            on = compareOn,
                            loading = compareLoading,
                            onClick = onToggleCompare
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                PriceChart(
                    series = chart,
                    range = chartRange,
                    loading = chartLoading,
                    livePrice = liveEdgePrice(q, chartRange, chart),
                    // The live tip belongs to whichever session the chart is drawing: the
                    // regular price on the 1D line while the market is open, the extended
                    // print on the after-hours line once it has closed. See withLiveEdge.
                    liveEdge = liveEdgePrice(q, chartRange, chart) > 0.0,
                    onZoom = onChartZoom,
                    window = chartWindow,
                    windowBounds = chartBounds,
                    onWindow = onChartWindow,
                    onResetWindow = onResetChartWindow,
                    onExpand = onExpandChart,
                    onZoomingChanged = onChartPinching,
                    compare = compare,
                    compareLabel = BENCHMARK_SYMBOL,
                    compareLivePrice = compareLive
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
                            plInline(state.plMode, row.totalPnl, row.totalPct),
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
                            plInline(state.plMode, row.dayPnl, row.dayPnlPct),
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
                                color = accentText
                            )
                        }
                        val eq = row.quote
                        if (eq != null && extendedPrice(eq) != null) {
                            KeyValue(
                                "${eq.extLabel ?: "Extended"} gain/loss",
                                plInline(
                                    state.plMode, row.shares * eq.extChange, eq.extChangePct
                                ),
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
                        // NAMES ITS DENOMINATOR. This divides by `marketValue`, which is
                        // stocks only - while the Portfolio screen's own headline is total
                        // equity and lists cash separately. So these weights sum to 100% of a
                        // number that is explicitly not "everything you have", and "Portfolio
                        // weight" said the opposite. Round 51 fixed exactly this class of
                        // omission on the price block: a percentage with nothing saying what
                        // it is a percentage OF.
                        if (weight != null) KeyValue("Share of your stocks", Fmt.pct(weight))
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
                                color = accentText
                            )
                        }
                        // THE RECORDS DISAGREE WITH THEMSELVES - see [OversoldWarning] and
                        // [Position.oversold]. Read from the UNFILTERED `state.positions`,
                        // not `row.position` (which is only ever non-null in THIS branch,
                        // "still holds shares") - the far likelier case, selling out
                        // entirely, closes the position and needs the other branch below.
                        OversoldWarning(state.positions.firstOrNull { it.symbol == symbol }?.oversold ?: 0.0)
                    }

                    Row {
                        TextButton(onClick = onEditPosition) { Text("Edit shares / cost") }
                        TextButton(onClick = onAddTxn) { Text("Add buy or sell") }
                    }
                    // ---- THE LABEL HAS TO SAY WHAT THE TAP WILL DO (Round 66 audit, REG-3).
                    //
                    // A REGRESSION FROM PUI-7'S OWN FIX. That fix made `WATCH_TOGGLE` two-way
                    // for a symbol that is both held and watched - it reads `row.watched` now,
                    // not `row.watchOnly` - and updated the row menu's label to match. This
                    // button was missed. It sits inside the "you hold shares" branch, where no
                    // star is drawn in the header, so on a held-and-watched stock it was the
                    // only watch control on the screen, it read "Also watch", and tapping it
                    // REMOVED the symbol from the watchlist. Before PUI-7 the same tap was a
                    // harmless repeat add; the fix turned a no-op into the opposite action
                    // while leaving the word on the button unchanged.
                    Row {
                        TextButton(onClick = onWatchToggle) {
                            Text(if (watched) "Remove from watchlist" else "Also watch")
                        }
                        TextButton(onClick = onDeletePosition) { Text("Delete position", color = redText) }
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
                    // ---- A CLOSED POSITION STILL MADE WHAT IT MADE (Round 66 audit, DET-2).
                    //
                    // THE BUG THIS FIXES. Realized P/L was rendered only inside the "you still
                    // hold shares" branch above, so the moment a position was fully sold it
                    // vanished from its own detail screen. `Ledger.fifo` keeps the symbol with
                    // `shares = 0.0, realized = 500.0`, but `publish()` builds rows only from
                    // positions with shares, so a closed symbol arrives here as a watchlist or
                    // search row and takes THIS branch. The screen then said "no shares held,
                    // so nothing here counts toward your totals" - while listing both fills a
                    // few rows below, and while the sentence in the branch that was skipped
                    // promised "profit from shares already sold is the realized figure below".
                    // The one screen that names the number was the one screen that hid it.
                    val closedGain =
                        state.positions.firstOrNull { it.symbol == symbol }?.realized ?: 0.0
                    // THE SAME DET-2 FIX, FOR OVERSOLD (Part 10 audit). The first version of
                    // this warning only ever rendered in the "still holds shares" branch
                    // above via `row.position` - which is null exactly when a position has
                    // been sold down to zero, the single most common shape of an uncovered
                    // sale and the case this warning most needs to catch. `state.positions`
                    // is the unfiltered list this file already reads `closedGain` from, one
                    // line up, for precisely the same reason.
                    OversoldWarning(state.positions.firstOrNull { it.symbol == symbol }?.oversold ?: 0.0)
                    if (kotlin.math.abs(closedGain) > 0.005) {
                        Spacer(Modifier.height(8.dp))
                        StatCard {
                            KeyValue(
                                "Profit from shares you sold",
                                Fmt.usdSigned(closedGain),
                                signColor(closedGain)
                            )
                            Text(
                                "You have closed this position, so it no longer counts toward " +
                                    "your holdings - but this is what it made while you held " +
                                    "it, and it is already inside \"Since you started\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = onAddTxn) { Text("Record a buy") }
                        if (tracked) TextButton(onClick = onRemoveWatch) {
                            Text("Remove from watchlist", color = redText)
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

        items(txns, key = { it.id }, contentType = { "txn" }) { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEditTxn(t) }
                    .padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.type, fontWeight = FontWeight.SemiBold)
                    // Shared with the Activity tab's row - see [txnSubtitle] for why.
                    Text(
                        txnSubtitle(t),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // A split moves no cash, so it gets no cash column - same rule as the
                // Activity tab's row.
                if (t.type != TxnType.SPLIT) {
                    Text(
                        Fmt.usdSigned(t.amount),
                        color = signColor(t.amount),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // Confirmed, like the identical control on the Activity tab. This one
                // deleted on a single tap - a 20dp icon inside a row whose whole width
                // opens the editor, so the near-miss went the destructive way.
                IconButton(onClick = { onDeleteTxn(t.id) }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = redText, modifier = Modifier.size(20.dp))
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
            items(filings, key = { it.accession }, contentType = { "filing" }) { f ->
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

        items(items, key = { newsKey(it) }, contentType = { "news" }) { n ->
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
 * The "vs SPY" switch that sits at the end of the range-chip row.
 *
 * A CHIP, NOT A CHECKBOX, so it matches the eight buttons beside it - the same height, the
 * same corner radius, the same selected-state fill. It reads as the ninth member of that row
 * rather than as a control bolted on next to it, which is what it is: another thing that
 * changes what the chart is showing.
 *
 * The 48dp rule applies here as much as it does to the chips, and for the same reason - the
 * nearest thing to mis-tap is the range button next to it.
 */
@Composable
private fun CompareToggle(on: Boolean, loading: Boolean, onClick: () -> Unit) {
    // The same amber the line is painted in, so the control and what it turns on are visibly
    // one thing - and the text on it follows the theme, because white does not carry on the
    // bright value. See `benchmarkColor` and `onBenchmark`.
    val fill = benchmarkColor
    val onFill = onBenchmark
    Box(
        Modifier
            .minTapTarget()
            .background(
                if (on) fill else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics {
                // Spelled out for a screen reader: "vs SPY" alone does not say whether it is
                // currently on, and the colour is the only other thing that does.
                contentDescription =
                    if (on) "Hide the $BENCHMARK_SYMBOL comparison"
                    else "Compare against $BENCHMARK_SYMBOL"
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "vs",
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                color = if (on) onFill else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                // The same non-breaking-space trick the range chips use, so this chip is
                // exactly as tall as they are whether or not it is loading.
                if (loading) "..." else BENCHMARK_SYMBOL,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (on) onFill else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * How long a pinch has to settle before the chart it landed on is fetched.
 *
 * 380ms is a pause, not a delay: it is longer than the gap between two rungs of a continuous
 * spread (which is one or two frames) and shorter than the time it takes to look at what
 * appeared. A window already in the cache is drawn instantly regardless - this timer only
 * gates the request.
 */
private const val CHART_SETTLE_MS = 380L

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
    range: com.tj.portfolio.data.ChartRange,
    /** The series the tip goes on, when the caller has it - see the D1 after-close rule. */
    series: com.tj.portfolio.data.ChartSeries? = null
): Double {
    if (q == null) return 0.0
    return when (range) {
        // AFTER THE CLOSE THE 1D TIP IS THE OFFICIAL CLOSE (full test 2026-09-24, C-8), when the
        // line is that same session: its last candle is the 15:55 bar, and the header's day
        // change is measured from the closing-auction print - so the readout and the 1D chip
        // disagreed with the header by the auction gap every evening and weekend.
        com.tj.portfolio.data.ChartRange.D1 -> when {
            !(q.price > 0.0) -> 0.0
            q.marketState == "OPEN" -> q.price
            q.marketState == "AFTER" && series != null && !series.isEmpty && q.quoteTime > 0L &&
                com.tj.portfolio.net.MarketClock.dayKey(series.endMs) ==
                com.tj.portfolio.net.MarketClock.dayKey(q.quoteTime) -> q.price
            else -> 0.0
        }
        // 5D is intraday too (30-minute candles on a 30-minute TTL), and without this its tip and
        // chip lagged the header by up to half an hour through the session (C-12).
        com.tj.portfolio.data.ChartRange.D5 ->
            if (q.marketState == "OPEN" && q.price > 0.0) q.price else 0.0
        com.tj.portfolio.data.ChartRange.OVERNIGHT ->
            if (q.marketState != "OPEN") (q.extPrice ?: 0.0).coerceAtLeast(0.0) else 0.0
        else -> 0.0
    }
}

/**
 * What the chart draws while a zoom crosses into a range not fetched yet (full test 2026-09-24,
 * C-1): the [last] series drawn, with its own range, when [chart] has nothing to draw, a zoom
 * window is live and its series is still [awaiting] (fingers down, settle running or fetch in
 * flight). Otherwise exactly [chart] and [range]. A null series would swap PriceChart's chart
 * node for its placeholder node and orphan the fingers already on the glass.
 */
internal fun bridgedChart(
    chart: com.tj.portfolio.data.ChartSeries?,
    range: com.tj.portfolio.data.ChartRange,
    last: Pair<com.tj.portfolio.data.ChartSeries, com.tj.portfolio.data.ChartRange>?,
    zoomed: Boolean,
    awaiting: Boolean
): Pair<com.tj.portfolio.data.ChartSeries?, com.tj.portfolio.data.ChartRange> =
    if ((chart == null || chart.isEmpty) && zoomed && awaiting && last != null) last
    else chart to range
