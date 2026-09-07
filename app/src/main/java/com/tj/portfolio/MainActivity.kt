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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private data class TabDef(val index: Int, val label: String, val icon: ImageVector)

private val TABS = listOf(
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
    var tab by remember { mutableIntStateOf(vm.lastTab().coerceIn(0, TABS.lastIndex)) }
    var detail by remember { mutableStateOf<String?>(null) }
    var detailToNews by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    // Which half of the Watch tab is showing. Hoisted here - not held inside WatchTab -
    // because the visible-scope calculation and the back handler below both depend on it.
    var watchSubTab by remember { mutableIntStateOf(vm.watchSubTab()) }
    // The in-app article reader sits on top of whatever is showing, so closing it returns
    // to exactly the list the headline was tapped in.
    var reader by remember { mutableStateOf<ReaderTarget?>(null) }

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
    val tabHistory = remember { mutableStateListOf<Int>() }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val ctxActivity = remember(ctx) { ctx.findActivity() }

    fun goToTab(i: Int) {
        if (i != tab) {
            if (tabHistory.lastOrNull() != tab) tabHistory.add(tab)
            if (tabHistory.size > 16) tabHistory.removeAt(0)
        }
        tab = i
        vm.setLastTab(i)
        detail = null
        detailToNews = false
        searching = false
        reader = null
        lastBackAt = 0L
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
            searching -> searching = false
            detail != null -> { detail = null; detailToNews = false }
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
    LaunchedEffect(tab, detailNow) {
        vm.setNewsVisible(tab == TAB_FEED || detailNow != null)
    }

    Scaffold(
        bottomBar = {
            BigTabBar(selected = tab, onSelect = { i -> goToTab(i) })
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
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
                    onDismiss = { searching = false },
                    onOpen = { searching = false; detail = it; detailToNews = false }
                )

                open != null -> DetailScreen(
                    vm, state, open, detailToNews,
                    onBack = { detail = null; detailToNews = false },
                    onOpenUrl = { url, title -> openArticle(url, title) }
                )

                else -> when (tab) {
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
@Composable
private fun BigTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(t.index) }
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
                        Icon(t.icon, t.label, Modifier.size(26.dp), tint = tint)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label,
                        fontSize = 11.sp,
                        color = tint,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
