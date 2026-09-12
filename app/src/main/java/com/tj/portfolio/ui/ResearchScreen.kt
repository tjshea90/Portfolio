package com.tj.portfolio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.util.Fmt

/**
 * One of the lists, as a tab.
 *
 * ROUND 56: they used to be stacked in one scroll, and reaching the last one meant scrolling
 * past a header and twenty tall cards - each carries its own reason lines and often a Claude
 * paragraph. Tabs make each list one gesture away and keep the scroll position of the one you
 * were reading.
 *
 * ROUND 66 REMOVED THE "WORST" TAB. Its premise was that a failing company could be paired
 * with something buyable that shorts it. Measured against Yahoo in September 2026: 16 of 20
 * high-momentum mega-caps have a US single-stock inverse fund, and 2 of 40 beaten-down names
 * do - one of those two only on London and Milan listings a US account cannot buy. Issuers
 * launch these products on whatever retail trades heavily, not on companies in trouble, so
 * the section could not be made actionable and is gone rather than misleading.
 *
 * The data layer is otherwise untouched: the remaining lists are still built by the same
 * single pass, and `PAGE` rows of each are still the only ones that cost per-row requests.
 */
private enum class Section(
    val key: String,
    val tab: String,
    val blurb: String
) {
    TRENDING(
        ResearchSet.SECTION_TRENDING, "Trending",
        "r/wallstreetbets mentions blended with how often each name appears in today's " +
            "market headlines."
    ),
    BEST(
        ResearchSet.SECTION_BEST, "Best",
        "Forward earnings growth, a forward multiple that has not priced it in, price above " +
            "the 50- and 200-day averages, and analyst consensus."
    ),
    /**
     * BEST ETFS (Round 63). The third list, and the only one built on its own clock.
     *
     * Its blurb leads with what it is RANKED ON rather than with what it contains, because
     * the honest headline for a fund list is the weighting: five- and three-year annualised
     * returns above anything recent, and cost counted against them.
     */
    ETFS(
        ResearchSet.SECTION_ETF, "ETFs",
        "Funds ranked on five- and three-year annualised returns, weighted above anything " +
            "recent, then expense ratio, fund size, dollar volume, how long it has existed " +
            "and its trend. Leveraged and inverse funds are left out, and only the " +
            "best-scoring fund of each exposure appears - the others are named on its card. " +
            "What this cannot see is what a fund actually holds or how closely it tracks its " +
            "index, so read the fund page before you buy."
    ),
    /**
     * DAY TRADING (Round 67) - today's stocks OBJECTIVELY IN PLAY, not a price prediction.
     * See `net/ResearchScore.kt`'s `dayTrading()` header for the feasibility finding this
     * blurb is written from, and why it says "in play" and never "will rise".
     */
    DAY_TRADING(
        ResearchSet.SECTION_DAY_TRADING, "Day Trading",
        "Stocks $2 a share or more that are objectively in play RIGHT NOW - unusually heavy " +
            "volume, a real move already under way, elevated wallstreetbets/news attention, a " +
            "breakout, or a short-squeeze-prone setup. The buy price is a TRIGGER, not the " +
            "current quote: a level price has to reach - above the opening range, the premarket " +
            "high or the prior session's high for a breakout, or back down at VWAP or support " +
            "when it has already run too far to chase. The stop sits under the structure that " +
            "would say the setup failed and the target at the next real resistance. Computed " +
            "from real intraday levels - not a forecast of where the price is going. No system " +
            "built on free public data can honestly promise which stocks will rise today; this " +
            "list says what is already happening, not what happens next."
    );

    fun rowsIn(set: ResearchSet): List<ResearchRow> = when (this) {
        TRENDING -> set.trending
        BEST -> set.best
        ETFS -> set.etfs
        DAY_TRADING -> set.dayTrading
    }
}

/**
 * THE RESEARCH SCREEN - the second half of the Watch tab (Round 54, retabbed in Round 56).
 *
 * Three tabs, ten rows each, and a button under each that reveals ten more. Nothing beyond
 * ten is loaded until that button is pressed: the list itself is already in memory, but the
 * per-row lookup that costs requests - analyst consensus, on the Best list only - is spent
 * on rows that are actually on screen. That was TJ's explicit rule and it is enforced in
 * [PortfolioViewModel.showMoreResearch], not just in this layout.
 *
 * Every row shows the app's own score AND the reason lines behind it, before any Claude
 * involvement. Claude's paragraph, when it arrives, is drawn UNDER those lines and labelled,
 * so it is always clear which half of the row is arithmetic and which half is a model's
 * opinion.
 *
 * WHAT IS PINNED AND WHAT SCROLLS. The title row and the tab row do not scroll, so the tabs
 * are reachable from anywhere in a long list. Everything else - the section blurb, the cards,
 * the Load-more button, the Claude tools and the sources note - is inside the LazyColumn, and
 * the Claude tools sit at the BOTTOM rather than the top: they were the tallest thing on the
 * screen and they stood between the user and the lists, which is most of what made the old
 * single-scroll layout feel long.
 */
@Composable
fun ResearchScreen(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    val set by vm.research.collectAsState()
    val busy by vm.researchBusy.collectAsState()
    val error by vm.researchError.collectAsState()
    val shown by vm.researchShown.collectAsState()
    var howTo by remember { mutableStateOf(false) }

    // Persisted rather than remembered: this screen leaves composition every time the user
    // visits another bottom-bar tab, so a plain `remember` would drop them back on Trending
    // constantly. Same treatment the Watchlist / Research sub-tab already gets.
    // `getOrElse`, not `[...]` (Round 66 audit, RES-6). `researchTab()` clamps against
    // `ResearchSet.SECTIONS` and this indexes the `Section` ENUM - two lists declared in two
    // files with nothing binding their order or length. The guard added after the last tab
    // accident therefore does not protect the array access it was written for: the next edit
    // that touches one list and not the other returns an index the enum does not have, and
    // the crash comes from a value in the DATABASE, so it repeats on every launch. Reading it
    // defensively at the one use site costs nothing and cannot be got wrong twice.
    var section by remember {
        mutableStateOf(Section.entries.getOrElse(vm.researchTab()) { Section.TRENDING })
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.readPickedFile(uri) { text ->
            vm.toast(if (text == null) "Couldn't read that file" else vm.importResearchFile(text))
        }
    }

    // ---- BUILD WHAT IS BEING LOOKED AT, AND NOTHING ELSE (Round 63).
    //
    // This used to live in `WatchTab` as a single `loadResearch()` on first sight, which was
    // right when every list came from one pass. It is not any more: the ETF list is its own
    // ten requests on its own six-hour clock, and someone who left the app on the ETFs tab
    // would have paid eighteen stock requests on every launch to fill lists they were not
    // looking at - and still had no fund data.
    //
    // Both calls are cheap when there is nothing to do: each returns immediately if its own
    // cache is inside its own TTL, so switching between tabs costs nothing at all.
    // KEYED ON `busy` AS WELL AS ON THE SECTION, and that is not decoration.
    //
    // Both passes share one busy flag - they are two builds of the same screen and only one
    // may run at a time - so a request made while the other is in flight is DROPPED, not
    // queued. Opening the ETFs tab a second after the stock pass started therefore did
    // nothing at all, and nothing ever came back to it: the tab sat empty until the user
    // switched away and returned, or pulled down. Re-evaluating when the flag clears is the
    // retry. It cannot loop: both calls return immediately when their own cache is inside
    // its own TTL, and immediately again while anything is building.
    LaunchedEffect(section, busy) {
        if (section == Section.ETFS) vm.loadEtfs() else vm.loadResearch()
    }

    // ---- LIVE DAY-TRADING TECHNICALS, ONLY WHILE THAT TAB IS THE ONE ON SCREEN (Round 68).
    //
    // Tj: "it can do this all automatically, but only when I have that tab open... The
    // feature should be asleep when I'm not using it." `DisposableEffect`'s `onDispose` covers
    // BOTH ways that stops being true: switching to another Research sub-tab (a new `section`
    // value disposes the old effect before running the new one) and leaving the Research
    // screen entirely (composition tears down, `onDispose` still runs). The app backgrounding
    // is covered separately - `startDayTradingLive` runs on `fgScope`, which
    // `setForeground(false)` cancels outright regardless of which tab was open.
    DisposableEffect(section) {
        if (section == Section.DAY_TRADING) vm.startDayTradingLive()
        onDispose { if (section == Section.DAY_TRADING) vm.stopDayTradingLive() }
    }

    // ---- "UP TODAY" IS ONLY TRUE WHEN THE MARKET ACTUALLY HAD A TODAY (Round 68 bug fix).
    //
    // Tj: "the market is currently closed and yet the stocks claim to be 'already up today'
    // which makes no sense." The NUMBER was always right - `changePct` is last-close-vs-
    // current, which is what it has to be at any hour - the wording around it was not. A
    // pure, cheap read of the wall clock, so it is current on every recomposition (which the
    // live loop above now drives every 30 seconds anyway) without needing its own state.
    val marketPhase = com.tj.portfolio.net.MarketClock.phase()
    val sessionSuffix = when (marketPhase) {
        com.tj.portfolio.net.MarketClock.Phase.OPEN -> ""
        com.tj.portfolio.net.MarketClock.Phase.EXTENDED -> " - extended hours"
        com.tj.portfolio.net.MarketClock.Phase.CLOSED -> " - last session"
    }

    // ROUND 70: "click on each stock and there is an explanation" no longer opens a dialog of
    // its own. Tapping a Day Trading card now goes through the SAME `onOpen` every other list
    // uses, to the stock's own [DetailScreen] - which draws this exact explanation (via
    // [DayTradingPlanContent], re-derived live from `vm.research` the same way this dialog used
    // to be) at the top of its Overview tab, plus the tabs Tj asked for. See TASKS.md Part 6.
    //
    // COLLECTED ONLY ON THE DAY TRADING TAB, not unconditionally. `vm.charts`/`vm.chartLoading`
    // are app-wide maps that change on every chart fetch anywhere in the app - a detail screen
    // finishing its own fetch after the user navigated back, a sparkline adoption - and
    // subscribing to them while looking at Trending, Best or ETFs would recompose this whole
    // screen, LazyColumn and all, for a card three sections has no chart on. The `if` is a
    // conditional COMPOSABLE CALL, which Compose supports directly: leaving the Day Trading tab
    // tears the subscription down instead of merely ignoring what it delivers.
    // STATE OBJECTS KEPT, NOT UNWRAPPED HERE (optimization pass). Reading `.value` at this
    // point used to bind the invalidation to THIS function's own scope, so a single chart
    // landing anywhere recomposed every visible Day Trading card - the whole list, for one
    // row's data. Handing each item the `State` itself lets its own `derivedStateOf` below read
    // `.value` from inside its own per-row scope, so only the row whose OWN chart actually
    // changed recomposes. Still a conditional composable call, so the subscription still tears
    // down the moment the tab is not Day Trading, exactly as before.
    val chartMapState = if (section == Section.DAY_TRADING) vm.charts.collectAsState() else null
    val chartLoadingState = if (section == Section.DAY_TRADING) vm.chartLoading.collectAsState() else null

    // KEYED ON ALL THREE CLOCKS. `etfGenerated` was missing, so on the ETFs tab the
    // watched/held set - two SQLite reads, which is why it is remembered at all - was only
    // recomputed when the STOCK pass ran, and a fund just added to the watchlist could show no
    // FOLLOWING chip until then.
    val watched = remember(set.generated, set.etfGenerated, set.explained) {
        vm.watchedSymbols() + vm.heldSymbols()
    }
    // ---- DE-DUPLICATED ONCE, HERE, SO EVERY COUNT ON THE SCREEN AGREES.
    //
    // It used to happen inside `items(...)`, which meant the tab badge, the "Load N more
    // (M left)" button and the "that is all N this pass found" footer were all counting rows
    // that the list would then drop. The keyed `LazyColumn` below is the reason the
    // de-duplication has to exist at all - handed one key twice it throws, and the bad set is
    // written straight to the cache, so the tab would keep crashing on every launch.
    val rows = remember(set, section) { section.rowsIn(set).distinctBy { it.symbol } }
    val visibleCount = shown[section.key] ?: ResearchSet.PAGE

    Column(Modifier.fillMaxSize()) {

        // ------------------------------------------------ pinned title + status
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- THE BUTTON IS MEASURED FIRST (Round 63 sweep).
            //
            // Four unweighted children in a `Row` are measured in order against the remaining
            // width, so the Rebuild button - last in the list - got whatever the title, the
            // spinner and the status line left it. At a large font scale that fell under the
            // 48dp minimum, and at 2x it reached zero: the button rendered but could not be
            // tapped. It is the only way to rebuild the list being viewed other than a
            // pull-down, so losing it is losing the screen's main control.
            //
            // Both texts are weighted now, so the button takes its 52dp first and the words
            // shrink around it.
            Text(
                "Research",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                // NO WEIGHTED SPACER AFTER THIS. Leaving the old one in place made three
                // weighted children split the row 1:1:2, which pinned the title to an 84dp
                // share against a 93.5dp natural width - so "Research" rendered as
                // "Researc..." at the DEFAULT font scale, beside 140dp of blank. Unused
                // share is not redistributed once every child is weighted.
                //
                // The status text carries the weight instead: it is the only thing here that
                // genuinely has to give, it already ellipsises, and both the title and the
                // button are then measured at what they need.
                modifier = Modifier.padding(end = 8.dp)
            )
            if (busy.isNotEmpty()) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when (busy) {
                    BUSY_BUILDING -> "Scanning the market..."
                    BUSY_ETFS -> "Screening funds..."
                    BUSY_DETAIL -> "Analyst data..."
                    BUSY_EXPLAINING -> "Claude is reading..."
                    else -> {
                        // ITS OWN STAMP. The ETF list is rebuilt on a six-hour clock and the
                        // stock lists on a thirty-minute one, so showing the stock timestamp
                        // over a fund list would claim a freshness the fund list does not have
                        // - and, more often, deny one it does.
                        val at = if (section == Section.ETFS) set.etfGenerated else set.generated
                        if (at > 0) "Updated ${Fmt.relative(at)}" else ""
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f)
            )
            // WHICHEVER LIST IS ON SCREEN. The ETF pass and the stock pass are ten and
            // eighteen requests on six-hour and thirty-minute clocks; a refresh button that
            // always rebuilt the stock lists would leave the ETF tab with no way to be
            // refreshed at all, and one that rebuilt both would spend twenty-eight requests
            // to update the ten rows being looked at.
            BigIconButton(
                Icons.Filled.Refresh,
                if (section == Section.ETFS) "Rebuild the fund list" else "Rebuild research"
            ) {
                if (section == Section.ETFS) vm.loadEtfs(force = true)
                else vm.loadResearch(force = true)
            }
        }

        // ------------------------------------------------------ pinned tab row
        // SCROLLABLE SINCE ROUND 63'S SWEEP. A fixed row divides 411dp across FOUR tabs -
        // 102dp each - and "Trending (20)" at 14sp needs more than that from about 1.3x, so it
        // wrapped into Material's fixed 48dp tab height and clipped. The detail screen's tab
        // row is scrollable for the same reason; this one was fixed when it had three tabs and
        // stayed fixed when the fourth arrived.
        SecondaryScrollableTabRow(
            selectedTabIndex = section.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            edgePadding = 0.dp
        ) {
            Section.entries.forEach { s ->
                // THE SAME COUNT THE LIST WILL DRAW. The tab printed `rows.size` while the
                // list rendered `rows.distinctBy { symbol }` - and the code's own comment
                // says an imported Claude answer can name a ticker twice - so the tab could
                // say "Best (20)" over nineteen cards, with a footer insisting on twenty.
                val count = s.rowsIn(set).distinctBy { it.symbol }.size
                Tab(
                    selected = section == s,
                    onClick = { section = s; vm.setResearchTab(s.ordinal) },
                    text = {
                        Text(
                            if (count > 0) "${s.tab} ($count)" else s.tab,
                            fontSize = 14.sp,
                            fontWeight = if (section == s) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        if (busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // An error belongs at the top, not buried under twenty cards.
        error?.let {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(it, color = redText, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.dismissResearchError() }) { Text("Dismiss") }
            }
        }

        Refreshable(
            refreshing = state.pulling(PULL_RESEARCH),
            onRefresh = {
                if (section == Section.ETFS) vm.loadEtfs(force = true)
                else vm.loadResearch(force = true)
            }
        ) {
            // ONE SCROLL STATE PER SECTION, all created unconditionally so each keeps its
            // own position. Sharing a single state means switching from row 40 of Best into
            // a Trending list of 12 rows lands somewhere arbitrary, and switching back loses
            // the place entirely.
            //
            // `rememberLazyListState` rather than a hand-built `LazyListState` in a map:
            // only the remembered form carries a Saver, so these are the positions that
            // actually survive a rotation or a process-death restore. A map of raw
            // `LazyListState()` objects looks equivalent and silently loses all three.
            val trendingState = androidx.compose.foundation.lazy.rememberLazyListState()
            val bestState = androidx.compose.foundation.lazy.rememberLazyListState()
            val etfState = androidx.compose.foundation.lazy.rememberLazyListState()
            val dayTradingState = androidx.compose.foundation.lazy.rememberLazyListState()
            val listState = when (section) {
                Section.TRENDING -> trendingState
                Section.BEST -> bestState
                Section.ETFS -> etfState
                Section.DAY_TRADING -> dayTradingState
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {

                item(key = "blurb") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // THE FIX TJ ASKED FOR, IN ONE VISIBLE LINE: which session these picks
                        // describe, stated plainly rather than left to be inferred (wrongly)
                        // from an "up X% today" that used to read the same at 3am Sunday as
                        // at 10am Tuesday.
                        if (section == Section.DAY_TRADING) {
                            Text(
                                when (marketPhase) {
                                    com.tj.portfolio.net.MarketClock.Phase.OPEN ->
                                        "Market open - today's picks, live"
                                    com.tj.portfolio.net.MarketClock.Phase.EXTENDED ->
                                        "Extended hours - today's picks so far"
                                    com.tj.portfolio.net.MarketClock.Phase.CLOSED ->
                                        "Market closed - showing the last session's picks"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (marketPhase == com.tj.portfolio.net.MarketClock.Phase.OPEN)
                                    greenText else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            section.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            when {
                                busy.isNotEmpty() -> "Scanning..."
                                section == Section.ETFS ->
                                    "No fund list yet. Pull down, or tap the refresh button, " +
                                        "to screen about 850 funds. It updates itself every " +
                                        "six hours after that."
                                set.isEmpty ->
                                    "Nothing loaded yet. Pull down, or tap the refresh " +
                                        "button, to scan the market."
                                section == Section.DAY_TRADING ->
                                    "Nothing at $2 a share or more looked objectively in play " +
                                        "on the last pass."
                                else -> "Nothing scored into this list on the last pass."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                        )
                    }
                } else {
                    // Keyed by section as well as symbol: the same ticker can legitimately
                    // appear in two lists, and a key reused across them would throw. The
                    // `distinctBy` is the second line of defence for the same rule - an
                    // imported Claude answer can name a ticker twice, and a keyed list handed
                    // one key twice takes the whole screen down. `ResearchBridge.merge` also
                    // de-duplicates; this is here so no future path into the list can
                    // reintroduce the crash.
                    items(
                        rows.take(visibleCount),
                        key = { "${section.key}_${it.symbol}" }
                    ) { r ->
                        val chartKey = vm.chartKey(r.symbol, com.tj.portfolio.data.ChartRange.D1)
                        // Per-row derived reads (optimization pass): each keeps its own cached
                        // value and only recomposes this ONE card when its own chart/loading
                        // entry actually changes, instead of every card re-reading the whole map
                        // straight out of the outer scope's closure.
                        val dayChart by remember(chartKey) {
                            derivedStateOf { chartMapState?.value?.get(chartKey) }
                        }
                        val dayChartLoading by remember(chartKey) {
                            derivedStateOf { chartLoadingState?.value?.contains(chartKey) == true }
                        }
                        ResearchCard(
                            r, r.symbol in watched,
                            onOpen = onOpen,
                            onOpenUrl,
                            sessionSuffix = if (section == Section.DAY_TRADING) sessionSuffix else "",
                            // THE CARD'S OWN 1-DAY CHART (Round 70) - Day Trading only, and null
                            // everywhere else so no other card changes shape. `enrichDayTradingVisible`
                            // is what actually fetches this while the tab is open; the card only draws
                            // whatever has already arrived.
                            dayChart = dayChart,
                            dayChartLoading = dayChartLoading
                        )
                    }
                    item(key = "more") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            if (visibleCount < rows.size) {
                                OutlinedButton(
                                    onClick = { vm.showMoreResearch(section.key) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Load ${minOf(ResearchSet.PAGE, rows.size - visibleCount)}" +
                                            " more (${rows.size - visibleCount} left)"
                                    )
                                }
                            } else {
                                Text(
                                    "That is all ${rows.size} this pass found.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ------------------------------------- Claude tools and footnotes
                item(key = "tools") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            when (section) {
                                Section.ETFS ->
                                    "About 850 funds screened on this phone from free feeds and " +
                                        "scored here - not recommendations. Yahoo's fund screens " +
                                        "do not cover every US ETF, so ask Claude below: it can " +
                                        "search the web and add the funds this list cannot see."
                                Section.DAY_TRADING ->
                                    "Screened on this phone from the same free feeds, right " +
                                        "now - not a prediction of what rises next, and not " +
                                        "recommendations. Ask Claude below for the specific " +
                                        "reason each name is in play today, and the specific " +
                                        "risk that could invalidate it - it can search the web."
                                else ->
                                    "Whole-market lists built on this phone from free feeds, and " +
                                        "scored here - not recommendations. Every score shows its " +
                                        "reasons."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (section == Section.DAY_TRADING) vm.explainDayTrading()
                                else vm.explainResearch()
                            },
                            enabled = busy.isEmpty() &&
                                if (section == Section.DAY_TRADING) set.dayTrading.isNotEmpty()
                                else !set.isFullyEmpty,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val explainedBefore = if (section == Section.DAY_TRADING)
                                set.dtExplained > 0 else set.explained > 0
                            Text(if (explainedBefore) "Re-explain with Claude" else "Explain with Claude")
                        }

                        SectionHeader("No API key? Use the Claude app")
                        Row {
                            OutlinedButton(
                                onClick = {
                                    if (section == Section.DAY_TRADING) {
                                        vm.writeDayTradingPrompt { msg -> vm.toast(msg) }
                                    } else {
                                        vm.writeResearchPrompt { msg -> vm.toast(msg) }
                                    }
                                },
                                enabled = if (section == Section.DAY_TRADING)
                                    set.dayTrading.isNotEmpty() else !set.isFullyEmpty,
                                modifier = Modifier.weight(1f)
                            ) { Text("Make prompt file") }
                            Spacer(Modifier.width(8.dp))
                            // `enabled = busy.isEmpty()`, like the API button above it
                            // (Round 66 audit, RES-7). Importing while a research pass is in
                            // flight is a race the app should not ask the user to think about
                            // - `enrichPass` no longer LOSES the import, but the two writing
                            // to the same list a second apart still makes the screen jump for
                            // no reason anyone can see.
                            //
                            // ALWAYS `importResearchFile` (Round 67), regardless of which tab
                            // is open: it recognises a Day Trading answer file by its own
                            // payload key and routes it correctly, so picking the reply file
                            // works the same from either tab.
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                enabled = busy.isEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Import answer") }
                        }
                        TextButton(onClick = { howTo = !howTo }) {
                            Text(if (howTo) "Hide how this works" else "How does this work?")
                        }
                        if (howTo) {
                            StatCard {
                                Text(
                                    if (section == Section.DAY_TRADING)
                                        "The prompt file carries every row on this screen - " +
                                            "the price, the app's own score and reasons, and " +
                                            "the entry/stop/target it computed - so Claude " +
                                            "needs no explanation from you. Attach " +
                                            "Downloads/Portfolio/" +
                                            com.tj.portfolio.net.DayTradingBridge.PROMPT_FILE +
                                            " to a chat in the Claude app, save the reply as a " +
                                            ".txt or .md file, then tap \"Import answer\" and " +
                                            "pick THAT file - not the prompt file. Claude " +
                                            "explains WHY each name is in play and names the " +
                                            "risk; it never sets entry/stop/target, and its " +
                                            "own conviction never overwrites the app's score. " +
                                            "A stock Claude adds is priced and given its own " +
                                            "risk levels the moment it is imported."
                                    else
                                        "The prompt file carries every row on this screen - the " +
                                            "prices, the scores and the reasons - so Claude needs " +
                                            "no explanation from you. Attach " +
                                            "Downloads/Portfolio/" +
                                            com.tj.portfolio.net.ResearchBridge.PROMPT_FILE +
                                            " to a chat in the Claude app, save the reply as a " +
                                            ".txt or .md file, then tap \"Import answer\" and pick " +
                                            "THAT file - not the prompt file. The explanations " +
                                            "fill in and no API key is used. Anything Claude adds " +
                                            "that the app missed is added to the list.\n\n" +
                                            "The ETF list is the one that asks Claude to go and " +
                                            "research rather than just explain: the prompt names " +
                                            "the funds Yahoo's screens leave out and asks for the " +
                                            "ones that belong on a best-ETF list. Funds Claude " +
                                            "adds are kept when the list rebuilds itself, because " +
                                            "the app's own screen can never find them again.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        val explainedAt = if (section == Section.DAY_TRADING) set.dtExplained else set.explained
                        val explainedVia = if (section == Section.DAY_TRADING) set.dtExplainedBy else set.explainedBy
                        if (explainedAt > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Explained ${Fmt.relative(explainedAt)} via $explainedVia",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val claudeNotes = if (section == Section.DAY_TRADING) set.dtNotes else set.notes
                        if (claudeNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            StatCard {
                                Text(
                                    "Claude's note on the app's data",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(claudeNotes, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        val warnings =
                            if (section == Section.ETFS) set.etfWarnings else set.warnings
                        if (warnings.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Partial data this pass: " + warnings.joinToString("; "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Where these numbers come from",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (section == Section.ETFS)
                                com.tj.portfolio.net.Research.ETF_SOURCES
                            else set.sources.ifBlank { com.tj.portfolio.net.Research.SOURCES },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "None of this is advice. A score is a ranking of public numbers, " +
                                "and the feeds behind it are free ones that can be wrong or " +
                                "late. Check anything here on the stock's own page before " +
                                "acting on it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// `internal`, not private, so `DayTradingUiTest` can render it directly - the same reasoning
// [EtfFactsGrid] and [TradeLevelsGrid] already document for this file.
@Composable
internal fun ResearchCard(
    r: ResearchRow,
    followed: Boolean,
    onOpen: (String) -> Unit,
    onOpenUrl: (String, String) -> Unit,
    /** " - last session" etc., computed from [com.tj.portfolio.net.MarketClock.phase] for the
     *  Day Trading tab only - see the fix note in [ResearchScreen]. Blank everywhere else. */
    sessionSuffix: String = "",
    /**
     * Today's 5-minute series for this row, Day Trading only - see [ResearchScreen]'s
     * `chartMapState` lookup. Null everywhere else, and null here too until the live loop's
     * [PortfolioViewModel.enrichDayTradingVisible] has actually fetched it.
     */
    dayChart: com.tj.portfolio.data.ChartSeries? = null,
    dayChartLoading: Boolean = false
) {
    // Colour is by score. See `scoreColor`, and the note there about why it no longer takes
    // a direction. This colour is printed as the score itself, on a 16%-alpha tint that
    // is nearly the card's own background - so it is read against white in the light theme,
    // where the old literals measured as low as 2.46:1.
    val c = scoreColor(r.score)
    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        StatCard(modifier = Modifier.clickable { onOpen(r.symbol) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ---- THE ONE NUMBER ON THIS SCREEN THAT WAS NEVER NAMED (Round 63 sweep).
                //
                // A bare integer in a coloured circle, with nothing on the card and nothing in
                // any section blurb saying it was a score or what the scale was - the blurbs
                // describe the INPUTS and never the output. The only mention lived three
                // hundred dp below, past ten cards. One word above the number closes it, and
                // the semantics say the whole thing out loud for a screen reader.
                // ---- THE NUMBER FIRST, THE WORD ONLY IF IT FITS.
                //
                // The first attempt at naming this score stacked a 7sp "SCORE" cap-label above
                // the figure inside the same fixed circle - and both Texts inherited
                // `bodyLarge`'s 21sp LINE HEIGHT, so the column wanted 42sp of line box in a
                // 46dp circle and `Arrangement.Center` gave the label its full box first. From
                // about 1.1x the digits were being clipped: the label was eating the number it
                // was there to explain.
                //
                // Both line heights are now pinned to the text size, and the label is dropped
                // entirely once the font scale makes it a threat. The `contentDescription`
                // says the whole thing regardless, which is the part that a screen reader -
                // and the accessibility case - actually needed.
                val labelFits = LocalDensity.current.fontScale <= 1.15f
                // ---- WHOSE NUMBER IS THIS? (Round 66 audit, R1.)
                //
                // A row the app screened carries its own score out of 100. A row Claude ADDED
                // has no app arithmetic at all - no facts grid, no reason lines - and used to
                // borrow the same circle, drawn as "SCORE 100" from a conviction of 10. Two
                // completely different kinds of claim, in identical type, on the list TJ is
                // buying from. The badge now says which it is, and the scale says it too:
                // out of 100 is measured, out of 10 is asserted.
                val fromClaude = r.score <= 0 && r.conviction > 0
                val badge = if (fromClaude) "CLAUDE" else "SCORE"
                val shown = if (fromClaude) "${r.conviction}/10" else "${r.score}"
                Column(
                    Modifier
                        .size(46.dp)
                        .background(c.copy(alpha = 0.16f), CircleShape)
                        .semantics(mergeDescendants = true) {
                            contentDescription = when {
                                fromClaude -> "Claude's conviction ${r.conviction} out of 10; " +
                                    "this fund was not scored by the app"
                                // A DAY-TRADING SCORE IS A BLEND, NOT A RAW SIGNAL (Round 72) -
                                // Tj: "make the scores reflect a blend of how likely the stock
                                // is to rise... and how confident this prediction is." Said once
                                // here rather than only in visible text, so the same distinction
                                // reaches a screen reader.
                                r.dtLikelihood > 0 -> "Score ${r.score} out of 100 - a blend of " +
                                    "how likely this stock is to keep rising " +
                                    "(${r.dtLikelihood} out of 100) and how confident that call " +
                                    "is (${r.dtConfidence} percent)"
                                else -> "Score ${r.score} out of 100"
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (labelFits) {
                        Text(
                            badge,
                            color = c,
                            fontWeight = FontWeight.Bold,
                            fontSize = 7.sp,
                            lineHeight = 8.sp,
                            maxLines = 1
                        )
                    }
                    AutoFitNumber(
                        shown,
                        color = c,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp, lineHeight = 18.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        minSp = 10
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (followed) {
                            Spacer(Modifier.width(6.dp))
                            Chip("FOLLOWING", accentText)
                        }
                    }
                    if (r.name.isNotBlank()) Text(
                        r.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (r.price > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.price(r.price), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (r.changePct != 0.0) Text(
                            Fmt.pctSigned(r.changePct) + sessionSuffix,
                            color = signColor(r.changePct),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // --- HOW THE SCORE WAS BUILT (Round 72) - "app shows its work," the same rule
            // every other score in this file follows. Only for a row the app actually scored;
            // a Claude-added pick has no likelihood/confidence to show (see
            // [ResearchRow.dtLikelihood]'s header) and draws the "CLAUDE n/10" badge instead.
            if (r.dtLikelihood > 0) Text(
                "Score = ${r.dtLikelihood} likelihood × ${r.dtConfidence}% confidence",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )

            // --- TODAY'S CHART (Round 70). Tj: "put a stock chart next to each of the stocks
            // in the day trading section... only that current day's chart, good for day
            // trading." [PriceChart] is the SAME composable [DetailScreen]'s Overview tab
            // draws - not a re-implementation - fed `ChartRange.D1` and nothing that lets it
            // zoom or expand, because a list card is a preview, not where you'd pinch a chart;
            // tapping the card already opens the full one. `dayChart` is null for every row
            // outside the Day Trading tab, so this never draws elsewhere, and null here too
            // until the live loop has actually fetched it - in which case the loading spinner
            // [PriceChart] already knows how to draw covers the gap.
            if (dayChart != null || dayChartLoading) {
                Spacer(Modifier.height(9.dp))
                PriceChart(
                    series = dayChart,
                    range = com.tj.portfolio.data.ChartRange.D1,
                    loading = dayChartLoading,
                    chartHeight = 130.dp
                )
            }

            // --- THE PLAIN-ENGLISH SUMMARY (Round 71) - drawn BEFORE the technical grid below
            // it, not instead of it. Tj: "keep all the advice it already shows for each stock,
            // but add a summary of what to do and why that is simple to read for complete
            // beginners." No-ops (draws nothing) for every row outside the day-trading section,
            // same as [TradeLevelsGrid] just below it.
            if (r.entryPrice > 0) BeginnerSummaryCard(r)

            // --- THE DAY-TRADING RISK PLAN (Round 67) - a computed levels grid, same
            // treatment [EtfFactsGrid] gets for the same reason: three numbers a reader
            // compares row to row want the same place on every card. Zero for every row
            // outside the day-trading section, so this never draws elsewhere.
            if (r.entryPrice > 0) TradeLevelsGrid(r)

            // --- THE FUND NUMBERS, as a grid rather than as prose (Round 63).
            //
            // Choosing between two funds is a COMPARISON, and a comparison wants the same
            // figure in the same place on every card - which reason lines, written in
            // sentences and ordered by what happened to score, cannot give. The six here are
            // the ones every fund fact sheet leads with; the reason lines below still explain
            // what the score made of them.
            r.etf?.let { f -> EtfFactsGrid(f) }

            // --- the app's own reasons
            if (r.reasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                r.reasons.take(6).forEach { line ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "-",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // `widthIn`, not `width` - the same correction FeedScreen already
                            // documents. A FIXED dp width for a glyph measured in sp clips it
                            // as soon as the font scale outgrows the box.
                            modifier = Modifier.widthIn(min = 12.dp)
                        )
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (r.catalyst.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    r.catalyst,
                    style = MaterialTheme.typography.bodySmall,
                    color = accentText,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // --- Claude's paragraph, always labelled as such
            if (r.why.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "CLAUDE",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(r.why, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // --- the headline behind it
            if (r.headline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        // A HEADLINE WITH NO LINK IS NOT A CONTROL. `clickable(enabled=false)`
                        // still leaves a 48dp block that looks exactly like a tappable one and
                        // reports itself to a screen reader as one; omitting the modifier
                        // entirely is what the detail screen already does.
                        .then(
                            if (r.headlineUrl.isBlank()) Modifier
                            else Modifier.minTapTarget().clickable {
                                onOpenUrl(r.headlineUrl, r.headline)
                            }
                        )
                ) {
                    Text(
                        r.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (r.headlineSource.isNotBlank()) Text(
                        r.headlineSource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


/**
 * The six numbers a person actually compares two funds on, in a fixed layout.
 *
 * A FIXED LAYOUT IS THE FEATURE. Every card puts the expense ratio in the same place, so
 * scrolling the list compares like with like instead of making the reader re-find each figure
 * in a differently-worded sentence.
 *
 * A cell whose number is missing shows an em dash, not a zero. "0.00%" in the cost cell would
 * read as a free fund, which is a claim; "-" reads as "the fund did not publish this", which
 * is the truth. Same rule the rest of this app follows for an absent price.
 */
// `internal`, not private, so `EtfCardUiTest` can render the grid on its own and assert what
// an absent figure prints. The alternative is standing up a whole ViewModel to reach it
// through `ResearchScreen`, which tests the plumbing rather than the layout.
@Composable
internal fun EtfFactsGrid(f: com.tj.portfolio.data.EtfFacts) {
    Spacer(Modifier.height(9.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            FactCell("5Y / yr", pctOrDash(f.fiveYearAnnualPct), signed = true, weight = 1f)
            FactCell("3Y / yr", pctOrDash(f.threeYearAnnualPct), signed = true, weight = 1f)
            // "1Y price", not "1Y" (Round 66 audit, E5). The two cells beside it are NAV
            // TOTAL returns; this one is a 52-week price change with dividends excluded, and
            // three identical-looking cells on one row implied three comparable numbers. For a
            // 4.5%-yielding bond fund the gap between them is the entire yield.
            FactCell("1Y price", pctOrDash(f.oneYearPct), signed = true, weight = 1f)
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth()) {
            FactCell(
                "Expense",
                // `>= 0`: a 0.00% fee is a fee, and the cheapest funds on the market have one
                // (Round 66 audit, ETF-6). -1.0 is the unknown sentinel - see [EtfFacts].
                if (f.expenseRatio >= 0) Fmt.pct(f.expenseRatio) else DASH,
                weight = 1f
            )
            FactCell(
                "Assets",
                if (f.netAssets > 0) Fmt.compactMoney(f.netAssets) else DASH,
                weight = 1f
            )
            FactCell(
                "Yield",
                if (f.yieldPct > 0) Fmt.pct(f.yieldPct) else DASH,
                weight = 1f
            )
        }
    }
}

/**
 * "Buy if it climbs to $12.40, then sell at $13.10" - the same [ResearchRow.entryPrice]/
 * [ResearchRow.stopPrice]/[ResearchRow.targetPrice] [TradeLevelsGrid] draws, restated in plain
 * language for a reader who does not know what a trigger, VWAP or a pivot is (Round 71). See
 * [com.tj.portfolio.net.ResearchScore.beginnerSummary]'s header for why this can never
 * contradict the technical grid it sits above: it reads the same numbers, not a second opinion.
 *
 * `internal`, not private, for the same reason [TradeLevelsGrid] is - `DayTradingUiTest` renders
 * it directly.
 */
@Composable
internal fun BeginnerSummaryCard(r: ResearchRow) {
    val s = com.tj.portfolio.net.ResearchScore.beginnerSummary(
        r.symbol, r.price, r.entryPrice, r.stopPrice, r.targetPrice, r.tooLateToStart
    ) ?: return
    Spacer(Modifier.height(9.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (s.skip) MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            "IN PLAIN ENGLISH",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Text(
            s.headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (s.skip) redText
            else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(3.dp))
        Text(
            s.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Entry, stop and target for a day-trading row. NOT a forecast, so this draws in neutral
 * colour rather than the green/red a prediction would earn.
 *
 * THE TRIGGER LINE IS THE POINT OF THIS BLOCK, NOT DECORATION (Round 69). "Buy at $12.40" sat
 * here on its own for two rounds while $12.40 was simply the last traded price, and Tj read it
 * exactly as it was written: a buy price that never moved off the quote. Now that the entry is
 * a real trigger it will often be visibly ABOVE the price on the card (a breakout) or BELOW it
 * (a pullback) - and a number that disagrees with the price two lines up reads as stale data
 * unless something says why. [ResearchRow.trigger] is that something, so it is not optional
 * chrome and must not be dropped to save a line.
 */
@Composable
internal fun TradeLevelsGrid(r: ResearchRow) {
    val byClaude = r.planByClaude
    Spacer(Modifier.height(9.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (byClaude) Accent.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                // WHOSE PLAN THIS IS, ALWAYS ON SCREEN. Round 69 lets Claude set these three
                // numbers; the app's rule that a model's figure is never shown as the app's
                // own arithmetic survives only if the label is unmissable.
                if (byClaude) "CLAUDE'S PLAN" else "RISK PLAN - computed, not a forecast",
                style = MaterialTheme.typography.labelSmall,
                // `accentText`, not raw `Accent` - see Theme.kt. The brand blue measures
                // 3.39:1 on the dark background, and this label is the one thing standing
                // between a model's prices and the reader assuming the app computed them.
                color = if (byClaude) accentText else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (byClaude) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (r.setup.isNotBlank()) Text(
                r.setup.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // ---- THE CLOCK CAN INVALIDATE EVERYTHING UNDER IT, so it goes above it (Round 73).
        //
        // A day trade is closed the same session. With too little of the session left, the
        // levels below are still correct and the instruction built on them is not: there is no
        // longer time for the move the target assumes. Shown on the CARD as well as in the
        // detail view, because the card is where the number is read at a glance.
        if (r.tooLateToStart) {
            Spacer(Modifier.height(6.dp))
            Text(
                "TOO LATE TO START TODAY - not enough of the session left",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = redText
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            FactCell("Buy at", if (r.entryPrice > 0) Fmt.price(r.entryPrice) else DASH, weight = 1f)
            FactCell("Stop", if (r.stopPrice > 0) Fmt.price(r.stopPrice) else DASH, weight = 1f)
            FactCell("Target", if (r.targetPrice > 0) Fmt.price(r.targetPrice) else DASH, weight = 1f)
        }
        if (r.trigger.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                r.trigger,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (r.planNote.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                r.planNote,
                style = MaterialTheme.typography.bodySmall,
                color = redText
            )
        }
    }
}

/**
 * Reward-to-risk for a row's plan, or 0.0 when it has no usable plan.
 *
 * ALL THREE PRICES CHECKED, not just the arithmetic. A missing stop is stored as 0.0 - the
 * same sentinel the grid above draws as an em dash - and `entry - 0.0` is a perfectly
 * well-formed subtraction that yields the entire share price as the "risk". Without the
 * explicit `stopPrice > 0`, a row whose stop never arrived would report a confident
 * "risking $22.50 a share" on a trade that has no stop at all.
 */
internal fun rewardToRisk(r: ResearchRow): Double {
    if (r.entryPrice <= 0.0 || r.stopPrice <= 0.0 || r.targetPrice <= 0.0) return 0.0
    return com.tj.portfolio.net.ResearchScore.rewardRisk(
        r.entryPrice, r.entryPrice - r.stopPrice, r.targetPrice
    )
}

private const val DASH = "\u2014"

/** A percentage, or an em dash when the fund published none. Never a fabricated zero. */
private fun pctOrDash(v: Double): String =
    if (v == 0.0 || !v.isFinite()) DASH else Fmt.pctSigned(v)

@Composable
private fun androidx.compose.foundation.layout.RowScope.FactCell(
    label: String,
    value: String,
    weight: Float,
    signed: Boolean = false
) {
    Column(Modifier.weight(weight)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        // SHRINKS RATHER THAN TRUNCATES, like the holding rows. These are three cells across
        // a card, about 110dp each, and a three-digit annualised return ellipsised to "+123..."
        // is not a number at all - which on a list whose whole purpose is comparing returns
        // would be the one figure nobody could compare.
        AutoFitNumber(
            value,
            // The TEXT palette, not the fill one - these are 14sp figures on a card, and
            // #16C784 on white is 2.20:1. See the note on `signColor`.
            color = when {
                !signed || value == DASH -> MaterialTheme.colorScheme.onSurface
                value.startsWith("-") -> redText
                else -> greenText
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.SemiBold,
            minSp = 10
        )
    }
}
