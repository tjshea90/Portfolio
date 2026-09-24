package com.tj.portfolio

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tj.portfolio.ui.Accent
import com.tj.portfolio.ui.ActivityScreen
import com.tj.portfolio.ui.AdviceScreen
import com.tj.portfolio.ui.DetailScreen
import com.tj.portfolio.ui.FeedScreen
import com.tj.portfolio.ui.PortfolioScreen
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PortfolioViewModel
import com.tj.portfolio.ui.VisibleScope
import com.tj.portfolio.ui.ReaderScreen
import com.tj.portfolio.ui.ReaderTarget
import com.tj.portfolio.ui.SearchSheet
import com.tj.portfolio.ui.SettingsScreen
import com.tj.portfolio.ui.TabSlide
import com.tj.portfolio.ui.swipeBetweenTabs
import com.tj.portfolio.ui.WATCH_LIST
import com.tj.portfolio.ui.WATCH_RESEARCH
import com.tj.portfolio.ui.WatchTab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before any of the app's own code can throw: a sideloaded app has no
        // crash reporting, so without this a crash leaves nothing behind to diagnose.
        com.tj.portfolio.util.CrashLog.install(this)
        // Trim callbacks delivered to the APPLICATION, not only to this Activity.
        //
        // `Activity.onTrimMemory` below only fires while an Activity instance exists. A
        // process whose Activity has been destroyed but which Android has kept alive - the
        // normal state for a backgrounded app - therefore received no trims at all, and held
        // every cache it had built until it was killed outright. Which is the wrong outcome
        // twice over: the memory is not given back, and a process that gives nothing back is
        // the one the system chooses to kill, costing a cold start on the way in.
        //
        // Registered on the application context so it lives as long as the process does;
        // there is deliberately no unregister, because the callback IS the process.
        com.tj.portfolio.util.MemoryTrim.installProcessWide(applicationContext)
        enableEdgeToEdge()
        setContent {
            PortfolioTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { App() }
            }
        }
        importIfShared(intent)
    }

    /**
     * SHARE -> PORTFOLIO (2026-09-23b). `ShareImportActivity` brings this activity forward with
     * [com.tj.portfolio.util.ShareInbox.ACTION_IMPORT] after queueing the shared answer; a
     * running app gets it here, a cold start in [onCreate]. Either way the import is the
     * ViewModel's, and so is the navigation it asks `App` for afterwards.
     *
     * The same ViewModel instance `App` gets from `viewModel()`: both resolve the default key
     * for this class in this activity's store. Safe to call on a re-delivered intent - the
     * inbox is deleted as it is read, so a second call finds nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importIfShared(intent)
    }

    private fun importIfShared(i: Intent?) {
        // ON EVERY START, NOT ONLY A SHARE'S (diff review 2026-09-23, R-5): a share left queued
        // by a process death mid-import is picked up the next time the app opens at all,
        // instead of waiting - stale - behind the next share. An empty queue costs one
        // directory listing; `ShareInbox.next` drops anything older than a day.
        if (i?.action != com.tj.portfolio.util.ShareInbox.ACTION_IMPORT &&
            !com.tj.portfolio.util.ShareInbox.hasQueued(this)
        ) return
        androidx.lifecycle.ViewModelProvider(this)[PortfolioViewModel::class.java].importSharedInbox()
    }

    /**
     * THE STATUS BAR AFTER A DARK-MODE TOGGLE (Round 64 sweep 3).
     *
     * Round 64 added `uiMode` to the activity's `configChanges` so that turning dark mode on
     * would stop destroying the navigation stack - and that is right, but it also stopped the
     * one thing the recreation was quietly doing: re-running `enableEdgeToEdge`, which decides
     * whether the status-bar clock and icons are drawn dark-on-light or light-on-dark from the
     * configuration IN FORCE WHEN IT IS CALLED.
     *
     * Without this the app turned dark around a status bar still painted for a light one -
     * dark icons on a dark bar, unreadable until the app was restarted. Compose re-reads the
     * configuration for everything else on its own; the system bars are a window property and
     * have to be told.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    // `onTrimMemory` / `onLowMemory` used to be overridden here and forwarded to
    // `MemoryTrim`. They are not any more: `installProcessWide` in `onCreate` registers the
    // same callbacks on the application context, which receives them for the whole life of
    // the process rather than only while an Activity exists. Keeping both would simply
    // deliver every trim twice.
}

/** The Activity behind a Compose context, for the one place that has to call finish(). */
private fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private const val TAB_PORTFOLIO = 0
private const val TAB_WATCHLIST = 1
private const val TAB_FEED = 2
private const val TAB_ACTIVITY = 3
private const val TAB_ADVICE = 4
private const val TAB_SETTINGS = 5

internal data class TabDef(val index: Int, val label: String, val icon: ImageVector)

internal val TABS = listOf(
    TabDef(TAB_PORTFOLIO, "Portfolio", Icons.Filled.Home),
    TabDef(TAB_WATCHLIST, "Watch", Icons.Filled.Star),
    TabDef(TAB_FEED, "Feed", Icons.Filled.Notifications),
    TabDef(TAB_ACTIVITY, "Activity", Icons.Filled.DateRange),
    TabDef(TAB_ADVICE, "Advice", Icons.Filled.ThumbUp),
    TabDef(TAB_SETTINGS, "Settings", Icons.Filled.Settings)
)

@Composable
fun App() {
    val vm: PortfolioViewModel = viewModel()
    val state by vm.ui.collectAsState()
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current

    // the last tab is persisted, so a force-stop reopens where you were
    //
    // ---- EVERY PIECE OF NAVIGATION STATE IS SAVEABLE (full-tests audit 2026-09-22, U-M2).
    // These were plain `remember`, so when Android reclaimed the process in the background -
    // routine on a phone, with no warning - the app came back on the tab list with the stock
    // Tj had open, the article he was reading and any half-typed trade in that stock's editor
    // all gone. The editors themselves were already `rememberSaveable`; the screen they live on
    // was not, so there was nothing for them to be restored into.
    var tab by rememberSaveable { mutableIntStateOf(vm.lastTab().coerceIn(0, TABS.lastIndex)) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var detailToNews by rememberSaveable { mutableStateOf(false) }
    // ---- AND EACH LAYER KEEPS ITS OWN STATE WHILE SOMETHING COVERS IT (U-M1). The layers are
    // branches of one `when` below, so opening a stock or an article takes the list underneath
    // OUT of composition - and its scroll position, and a detail screen's selected tab, went
    // with it: back from an article read from halfway down the Feed landed at the top. The
    // holder keeps each layer's saveable state under its own key until that layer is gone for
    // good (see `forgetDetails`).
    val layers = rememberSaveableStateHolder()

    /**
     * Detail screens opened FROM another detail screen, oldest first (Round 58).
     *
     * There is exactly one way to get here: tapping a position on a fund's Holdings tab.
     * That single hop needs a real stack, not a boolean - open SPY, tap NVDA, tap back, and
     * back has to return you to SPY rather than to the list you came from three taps ago.
     *
     * Capped for the same reason `tabHistory` is: a determined user hopping between funds
     * and their holdings should not be able to grow it without bound. Cleared by
     * `goToTab`, which is the one action that means "I am done with this screen entirely".
     */
    val detailStack = rememberSaveable(saver = stringListSaver) { mutableStateListOf<String>() }
    var searching by rememberSaveable { mutableStateOf(false) }
    // U-Q5 (2026-09-24b): the last query, and whether the open stock came from the results -
    // so Back returns to them rather than to the tab underneath.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var detailFromSearch by rememberSaveable { mutableStateOf(false) }
    // Which half of the Watch tab is showing. Hoisted here - not held inside WatchTab -
    // because the visible-scope calculation and the back handler below both depend on it.
    var watchSubTab by rememberSaveable { mutableIntStateOf(vm.watchSubTab()) }
    // The in-app article reader sits on top of whatever is showing, so closing it returns
    // to exactly the list the headline was tapped in.
    var reader by rememberSaveable(stateSaver = readerSaver) { mutableStateOf<ReaderTarget?>(null) }

    /**
     * Open an article. Honours the Settings switch: with the in-app reader turned off this
     * hands the link to whatever browser the phone uses, which is the escape hatch for a
     * site that will not render properly in a WebView.
     */
    fun openArticle(url: String, title: String) {
        if (url.isBlank()) return
        if (vm.inAppReader()) {
            reader = ReaderTarget(url, title)
        } else {
            val ok = runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }.isSuccess
            if (!ok) vm.toast("Couldn't open that link")
        }
    }

    // ---- Android back button
    //
    // Navigation here is a hand-rolled state stack rather than navigation-compose (a locked
    // decision - it keeps a dependency out), and nothing was listening for the system back
    // gesture, so back closed the app from anywhere: three taps into a stock's news, one
    // swipe and the whole app was gone.
    //
    // `tabHistory` is the tabs visited before the current one, oldest first. Back unwinds
    // the real path - Portfolio -> Feed -> Advice walks back to Feed, then to Portfolio -
    // rather than always jumping home. Consecutive duplicates are never pushed, and the
    // stack is capped so a long session cannot grow it without bound.
    val tabHistory = rememberSaveable(saver = intListSaver) { mutableStateListOf<Int>() }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val ctxActivity = remember(ctx) { ctx.findActivity() }

    // The key a detail screen's saved state lives under: its depth in the stack as well as its
    // symbol, so SPY -> NVDA -> SPY keeps two separate SPY screens, as the stack does.
    fun detailKey(depth: Int, sym: String) = "detail:$depth:$sym"

    // A detail screen that is gone for good takes its saved state with it - otherwise
    // reopening that stock next week would land on whatever tab and scroll it was left at.
    fun forgetDetails() {
        detailStack.forEachIndexed { i, sym -> layers.removeState(detailKey(i, sym)) }
        detail?.let { layers.removeState(detailKey(detailStack.size, it)) }
    }

    fun goToTab(i: Int) {
        forgetDetails()
        if (i != tab) {
            if (tabHistory.lastOrNull() != tab) tabHistory.add(tab)
            if (tabHistory.size > 16) tabHistory.removeAt(0)
        }
        tab = i
        vm.setLastTab(i)
        detail = null
        detailStack.clear()
        detailToNews = false
        searching = false
        detailFromSearch = false
        searchQuery = ""
        reader = null
        lastBackAt = 0L
    }

    // Pop one detail screen at a time - shared by the system BackHandler and DetailScreen's
    // own onBack, which used to restate this identically in two places (Part 9 audit finding).
    // A fix to one path but not the other is exactly the class of back-stack bug this file's
    // own comments say has already been fixed more than once (Round 58/63).
    fun popDetail() {
        detail?.let { layers.removeState(detailKey(detailStack.size, it)) }
        detailToNews = false
        val backToSearch = detailStack.isEmpty() && detailFromSearch
        detail = if (detailStack.isEmpty()) null else detailStack.removeAt(detailStack.lastIndex)
        if (backToSearch) { detailFromSearch = false; searching = true }
    }

    BackHandler {
        // Any back press that actually navigates cancels a pending "press again to exit" -
        // otherwise a press at the root, a detour into another tab and a press back could
        // add up to closing the app without the second warning the user was promised.
        if (reader != null || searching || detail != null ||
            tabHistory.isNotEmpty() || tab != TAB_PORTFOLIO ||
            (tab == TAB_WATCHLIST && watchSubTab != WATCH_LIST)
        ) {
            lastBackAt = 0L
        }
        when {
            // deepest layer first, so back always undoes exactly one step.
            // ReaderScreen registers its own handler, which runs before this one and walks
            // the article's history first; this branch is the fallback that closes it.
            reader != null -> reader = null
            searching -> { searching = false; searchQuery = "" }
            // Pop one detail screen at a time. `detailStack` is only ever non-empty when a
            // fund's holding was tapped, so for every other route this is the old behaviour
            // exactly: one back press closes the stock.
            detail != null -> popDetail()
            // Research is a layer inside the Watch tab, so back undoes it before it leaves
            // the tab - the same "one step at a time" rule every other layer here follows.
            tab == TAB_WATCHLIST && watchSubTab != WATCH_LIST -> {
                watchSubTab = WATCH_LIST
                vm.setWatchSubTab(WATCH_LIST)
            }
            tabHistory.isNotEmpty() -> {
                val prev = tabHistory.removeAt(tabHistory.lastIndex)
                tab = prev
                vm.setLastTab(prev)
            }
            tab != TAB_PORTFOLIO -> { tab = TAB_PORTFOLIO; vm.setLastTab(TAB_PORTFOLIO) }
            else -> {
                // Nothing left to go back to. Leaving is a real choice, so it takes two
                // presses - a stray swipe at the edge of the screen no longer closes the app.
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2500) ctxActivity?.finish()
                else {
                    lastBackAt = now
                    vm.toast("Press back again to exit")
                }
            }
        }
    }

    // ---- A CLAUDE ANSWER SHARED INTO THE APP OPENS THE SCREEN IT FILLED (2026-09-23b).
    // Through `goToTab`, so it leaves the same back-stack and persisted-tab state a tap on the
    // bottom bar would, and closes anything (a stock, an article) that was open on top.
    val shareNav by vm.shareNav.collectAsState()
    LaunchedEffect(shareNav) {
        val d = shareNav ?: return@LaunchedEffect
        when (d) {
            com.tj.portfolio.ui.ShareDest.ADVICE -> goToTab(TAB_ADVICE)
            com.tj.portfolio.ui.ShareDest.ACTIVITY -> goToTab(TAB_ACTIVITY)
            com.tj.portfolio.ui.ShareDest.RESEARCH, com.tj.portfolio.ui.ShareDest.DAY_TRADING -> {
                goToTab(TAB_WATCHLIST)
                watchSubTab = WATCH_RESEARCH
                vm.setWatchSubTab(WATCH_RESEARCH)
            }
        }
        vm.shareNavHandled()
    }

    // Stop polling when the app leaves the screen, and refresh the moment it comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setForeground(true)
                Lifecycle.Event.ON_STOP -> vm.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.toast(null)
        }
    }

    /**
     * Tell the ViewModel which prices are actually on screen, so the polling loop can fetch
     * those and stop fetching the rest.
     *
     * Derived here rather than inside the ViewModel because this is the only place that knows
     * the navigation state, and computed with `remember` so it is one cheap comparison per
     * recomposition rather than work. See `symbolsToQuote` for the measurement that motivated
     * it - the tick was fetching every tracked symbol regardless of what was displayed, which
     * on a 20-symbol portfolio is 80 Yahoo requests a minute, sustained, including while
     * sitting on screens that show no price at all.
     *
     * The reader and the search sheet count as None: neither displays a live quote, and the
     * search sheet's own results carry theirs.
     */
    val detailNow = detail
    val readerNow = reader
    val scope: VisibleScope = remember(tab, detailNow, readerNow, searching, watchSubTab) {
        // The decision itself lives in VisibleScope.choose so it can be tested; this call
        // site only knows the navigation state. Research draws its prices from the screener
        // payload and shows no watchlist row, so it polls nothing.
        VisibleScope.choose(
            readerOpen = readerNow != null,
            searching = searching,
            detail = detailNow,
            onPortfolioTab = tab == TAB_PORTFOLIO,
            onWatchTab = tab == TAB_WATCHLIST,
            researchShowing = watchSubTab == WATCH_RESEARCH
        )
    }
    LaunchedEffect(scope) { vm.setVisibleScope(scope) }

    // Headlines are not prices, so they do not fit VisibleScope - the Feed tab shows no
    // quotes at all and its scope is None, indistinguishable from Settings. But the
    // per-symbol news pull is one request per followed symbol every three minutes, which is
    // the app's second-largest source of traffic, and it was running whether or not anyone
    // had ever opened the tab. This is the signal that lets it stop. See
    // PortfolioViewModel.refreshFeed.
    // TWO SIGNALS, NOT ONE (Round 63 sweep). The Feed tab wants every followed symbol's
    // headlines because that is the list it draws; a stock's detail screen wants exactly its
    // own, and passing "a stock is open" as "a headline screen is visible" made opening one
    // stock sweep the whole portfolio every three minutes for nothing. See
    // `PortfolioViewModel.newsSymbol`.
    LaunchedEffect(tab, detailNow, readerNow) {
        vm.setNewsVisible(
            visible = com.tj.portfolio.ui.feedListVisible(
                onFeedTab = tab == TAB_FEED, detail = detailNow, readerOpen = readerNow != null
            ),
            detailSymbol = detailNow
        )
    }

    Scaffold(
        bottomBar = {
            BigTabBar(selected = tab, onSelect = { i -> goToTab(i) })
        }
    ) { pad ->
        // ---- SWIPE LEFT AND RIGHT TO CHANGE TABS (Round 63).
        //
        // The gesture is only live at the ROOT of a tab. With a stock's detail screen, the
        // search sheet or the article reader on top, a horizontal swipe belongs to that layer
        // - the chart scrubs, the reader pans - and yanking the layer away underneath would be
        // the opposite of what the finger was doing. It routes through the SAME `goToTab` the
        // bottom bar uses, so a swipe and a tap leave the app in identical states: same tab
        // history for the back button, same persisted last tab, same cleared detail stack.
        //
        // See `SwipeTabs.kt` for why this is a gesture rather than a `HorizontalPager`.
        val rootLayer = detail == null && !searching && reader == null
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .swipeBetweenTabs(
                    enabled = rootLayer,
                    current = tab,
                    tabCount = TABS.size,
                    onSwitch = { i -> goToTab(i) }
                )
        ) {
            val open = detail
            val article = reader
            when {
                // The reader sits above everything, so dismissing it reveals the list the
                // headline was tapped in, still scrolled where it was.
                article != null -> ReaderScreen(
                    article,
                    onBack = { reader = null },
                    // Read once per opened article rather than on every recomposition -
                    // these are SQLite reads, and the v2.4 rule is that nothing touching the
                    // database runs bare inside composition.
                    blockAds = remember(article) { vm.blockAds() },
                    declutter = remember(article) { vm.declutter() },
                    autoReader = remember(article) { vm.autoReader() }
                )

                searching -> SearchSheet(
                    vm,
                    onDismiss = { searching = false; searchQuery = "" },
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onOpen = {
                        searching = false
                        detailFromSearch = true
                        // A search result is a fresh destination, not a step deeper into a
                        // fund - so it starts a new stack rather than pushing onto one that
                        // a Holdings tap may have left behind.
                        forgetDetails()
                        detailStack.clear()
                        detail = it
                        detailToNews = false
                    }
                )

                open != null -> layers.SaveableStateProvider(detailKey(detailStack.size, open)) { DetailScreen(
                    vm, state, open, detailToNews,
                    onBack = { popDetail() },
                    onOpenUrl = { url, title -> openArticle(url, title) },
                    onOpenSymbol = { sym ->
                        // Guarded against opening the screen that is already showing - a
                        // fund that lists itself, or a double tap - which would otherwise
                        // push a duplicate and cost two back presses to undo one action.
                        if (!sym.equals(open, true)) {
                            // Dropping the oldest screen shifts every depth by one, so the
                            // saved states' keys would no longer match - forget them all in
                            // this (rare, twelve-hops-deep) case rather than orphan them.
                            if (detailStack.size >= 12) forgetDetails()
                            detailStack.add(open)
                            if (detailStack.size > 12) detailStack.removeAt(0)
                            detail = sym.uppercase()
                            detailToNews = false
                        }
                    }
                ) }

                // ONE TAB AT A TIME, still - `TabSlide` renders the outgoing screen for
                // the length of the animation and then drops it, rather than keeping
                // neighbours composed the way a pager would. `VisibleScope` therefore still
                // describes exactly one screen. See `SwipeTabs.kt`.
                else -> TabSlide(tab, Modifier.fillMaxSize()) { t ->
                    layers.SaveableStateProvider("tab:$t") { when (t) {
                        TAB_PORTFOLIO -> PortfolioScreen(
                            vm, state,
                            onOpen = { detail = it; detailToNews = false },
                            onOpenNews = { detail = it; detailToNews = true },
                            onSearch = { searching = true }
                        )

                        TAB_WATCHLIST -> WatchTab(
                            vm, state,
                            subTab = watchSubTab,
                            onSubTab = { i -> watchSubTab = i; vm.setWatchSubTab(i) },
                            onOpen = { detail = it; detailToNews = false },
                            onOpenNews = { detail = it; detailToNews = true },
                            onOpenUrl = { url, title -> openArticle(url, title) },
                            onSearch = { searching = true }
                        )

                        TAB_FEED -> FeedScreen(
                            vm, state,
                            onOpen = { detail = it; detailToNews = false },
                            onOpenUrl = { url, title -> openArticle(url, title) }
                        )
                        TAB_ACTIVITY -> ActivityScreen(vm, state)
                        TAB_ADVICE -> AdviceScreen(vm, state)
                        else -> SettingsScreen(vm)
                    } }
                }
            }
        }
    }
}

/**
 * Taller, wider-target bottom bar: 30dp icons and a 74dp touch height per tab.
 *
 * The app is edge-to-edge, so this bar MUST consume the navigation-bar inset itself -
 * `navigationBarsPadding()` sits below the background so the bar's surface still paints
 * behind the gesture pill / 3-button nav, while the tappable content is lifted clear of it.
 */
// `internal`, not private, so `TabBarUiTest` can put it inside a real `Scaffold` and measure
// it. That test exists because the alternative - reasoning about the height modifier - is
// exactly what got this wrong once: see the note on `.height(74.dp)` below.
@Composable
internal fun BigTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // ---- A FIXED HEIGHT, AND IT HAS TO STAY ONE.
                //
                // The sweep changed this to `heightIn(min = 74.dp)` so the bar could grow with
                // the font scale, and that broke the app outright. Each tab's `Column` below
                // carries `.weight(1f).fillMaxSize()`; `height` was what pinned their
                // maxHeight to 74dp. `heightIn(min=)` sets only the MINIMUM and lets the
                // maximum through - and `Scaffold` measures a `bottomBar` with loose
                // constraints, i.e. maxHeight = the whole screen. So every child filled the
                // screen, the Row sized to its tallest child, and the bar took everything:
                // measured inside a real Scaffold, bar 891dp and content 0dp. No screen
                // rendered at all, at any font scale.
                //
                // The real problem the change was aimed at is the LABEL, not the bar, and it
                // is fixed where it belongs - one line, ellipsised, below.
                .height(74.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEach { t ->
                val active = selected == t.index
                val tint = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        // `selectable`, not `clickable` - a TalkBack user needs to hear this is
                        // one of a set of tabs and which one is current, not just "button". The
                        // icon below is marked decorative (contentDescription = null) so its own
                        // description - identical to the label text - doesn't get read out
                        // alongside it; `mergeDescendants` folds the Text label into this node's
                        // single announcement instead.
                        .selectable(
                            selected = active,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab
                        ) { onSelect(t.index) }
                        .semantics(mergeDescendants = true) {}
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .background(
                                if (active) Accent.copy(alpha = 0.14f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(t.icon, contentDescription = null, Modifier.size(26.dp), tint = tint)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label,
                        fontSize = 11.sp,
                        color = tint,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        // ONE LINE, ELLIPSISED. Six labels across a phone give each about
                        // 68dp; at a large font scale "Portfolio" needs more than that and
                        // would otherwise wrap into a second line the bar has no room for.
                        // A shortened word under a distinct icon is still identifiable - a
                        // label spilling over the bar's edge is not.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** [ReaderTarget] as two strings, for `rememberSaveable` - see App's U-M2 note. */
private val readerSaver = androidx.compose.runtime.saveable.Saver<ReaderTarget?, ArrayList<String>>(
    save = { it?.let { r -> arrayListOf(r.url, r.title) } },
    restore = { ReaderTarget(it[0], it.getOrElse(1) { "" }) }
)

private val stringListSaver = androidx.compose.runtime.saveable.listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() }
)

private val intListSaver = androidx.compose.runtime.saveable.listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateList<Int>, Int>(
    save = { it.toList() },
    restore = { it.toMutableStateList() }
)
