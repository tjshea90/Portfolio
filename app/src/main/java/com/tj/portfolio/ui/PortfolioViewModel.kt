package com.tj.portfolio.ui

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tj.portfolio.data.Advice
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.StockRating
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import com.tj.portfolio.domain.PortfolioTotals
import com.tj.portfolio.domain.Position
import com.tj.portfolio.net.Claude
import com.tj.portfolio.net.ExtractResult
import com.tj.portfolio.net.Insider
import com.tj.portfolio.net.MarketClock
import com.tj.portfolio.net.MarketData
import com.tj.portfolio.net.News
import com.tj.portfolio.net.Social
import com.tj.portfolio.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One row on the portfolio list: position + live quote. */
data class Row(
    val symbol: String,
    val name: String,
    val position: Position?,
    val quote: Quote?,
    val watchOnly: Boolean,
    /**
     * Names the trading session the day figures describe, but ONLY when that is not the
     * current calendar day - blank during market hours. Overnight and at weekends the
     * quote still reports the previous session, so a row saying "you made today" at 2am is
     * describing yesterday. Now it says so.
     */
    val sessionLabel: String = ""
) {
    val price: Double get() = quote?.price ?: 0.0
    val shares: Double get() = position?.shares ?: 0.0
    val value: Double get() = shares * price
    val dayPct: Double get() = quote?.dayChangePct ?: 0.0
    val dayChange: Double get() = quote?.dayChange ?: 0.0
    /**
     * Today's gain on the shares actually owned. Shares bought today are measured from the
     * price paid, not from the previous close - so this can differ from the stock's own
     * day change, which is exactly right.
     */
    val dayPnl: Double
        get() = if (position != null && quote != null) position.dayPnl(quote) else 0.0

    /** Percentage matching dayPnl, over what the position was worth when the day began. */
    val dayPnlPct: Double
        get() = if (position != null && quote != null) position.dayPnlPct(quote) else 0.0

    /** True when the position's day move differs from the stock's, i.e. bought today. */
    val boughtToday: Boolean get() = (position?.sharesToday ?: 0.0) > 1e-9
    val avgCost: Double get() = position?.avgCost ?: 0.0
    val totalPnl: Double get() = if (position != null && price > 0) position.unrealized(price) else 0.0
    val totalPct: Double
        get() = if (position != null && position.costBasis > 1e-9 && price > 0)
            position.unrealizedPct(price) else 0.0
}

data class UiState(
    val rows: List<Row> = emptyList(),
    val totals: PortfolioTotals? = null,
    val loading: Boolean = false,
    /**
     * True only while a refresh the USER started is running. `loading` covers the automatic
     * 15-second tick too, and feeding that to the pull-to-refresh box made the spinner drop
     * down and retract by itself every 15 seconds on every tab. The thin progress bar still
     * tracks `loading`; the pull indicator tracks this.
     */
    val manualRefresh: Boolean = false,

    /**
     * WHICH surface the user pulled on.
     *
     * TJ: "the loading circle persists for a long time AND ACROSS TABS." Both halves had the
     * same cause. `manualRefresh` is one flag on one shared UiState, and every tab wraps
     * itself in a `Refreshable` bound to it - so pulling down on the Feed, whose pass takes
     * many seconds because it is 25+ requests, left a spinner turning on the Portfolio,
     * Watchlist, Activity, Advice AND Settings tabs at the same time. Nothing was wrong on
     * those screens; they were reporting somebody else's work.
     *
     * A screen now shows the indicator only when this names it. [PULL_NONE] means the
     * refresh was not started by a pull, so it shows nowhere - which is right, because
     * nobody asked for it.
     */
    val refreshSource: String = PULL_NONE,
    val lastRefresh: Long = 0L,
    val error: String? = null,
    val txns: List<Txn> = emptyList(),
    val positions: List<Position> = emptyList()
) {
    /**
     * Should THIS screen show the pull-to-refresh indicator?
     *
     * The pair of flags is not enough on its own: `manualRefresh` says a pull is outstanding
     * and `refreshSource` says where. A screen that shows the indicator for work it did not
     * start is what put a spinner on every tab while the Feed was loading.
     */
    fun pulling(surface: String): Boolean = manualRefresh && refreshSource == surface
}

/** Nothing was pulled - no screen shows an indicator. */
const val PULL_NONE = ""

/** The quote-driven tabs: Portfolio, Watchlist, Activity, Advice, Settings, a detail screen. */
const val PULL_PRICES = "prices"

/** The Feed tab. Its pass is much slower, which is exactly why it must not spin elsewhere. */
const val PULL_FEED = "feed"

/** The Research sub-tab's own pull-to-refresh, so it does not spin the price indicator. */
const val PULL_RESEARCH = "research"

/**
 * What the Research tab is waiting on. Three different waits with three different lengths -
 * a rebuild is ~18 requests, the per-row detail pass is a handful, and an explain call can
 * take a minute with web search on - so the screen names the one it is in rather than showing
 * one anonymous spinner for all three.
 */
const val BUSY_BUILDING = "building"
const val BUSY_DETAIL = "detail"
const val BUSY_EXPLAINING = "explaining"

const val SORT_VALUE = "value"
const val SORT_SYMBOL = "symbol"
const val SORT_DAY = "day"
const val SORT_TOTAL = "total"
const val SORT_PRICE = "price"

/**
 * How many requests may be in flight at once.
 *
 * This was 16, i.e. essentially "all of them at once". Yahoo's documented behaviour is that
 * "heavy or PARALLEL requests trigger rate limits" - a burst of sixteen simultaneous
 * connections from one phone looks exactly like something worth throttling, and it is the
 * shape of the traffic rather than the daily total that gets a client blocked first.
 *
 * Five is still four waves for a twenty-symbol portfolio, which at ~300ms a request is
 * comfortably under two seconds - imperceptible on a 15-second refresh, and a far quieter
 * neighbour.
 */
private const val MAX_PARALLEL_REQUESTS = 5

/** EDGAR publishes a hard 10 requests/second cap, so it gets its own smaller gate. */
private const val MAX_PARALLEL_SEC = 3

/** Form 4 filings are up to two business days behind by law - polling fast buys nothing. */
private const val INSIDER_REFRESH_SECS = 1800

/**
 * During market hours, if the newest exchange timestamp across every holding has not moved
 * in this long, the market is not really trading - a public holiday, or a feed that has
 * stopped updating. Polling every 15 seconds through that is pure waste.
 */
private const val STALE_TAPE_MS = 15 * 60 * 1000L

/** Both Reddit aggregators recompute every 15-30 minutes; polling faster returns the same rows. */
private const val SOCIAL_REFRESH_MS = 15 * 60 * 1000L

/**
 * What is on screen, as far as live prices are concerned.
 *
 * Deliberately a small sealed type rather than a tab index: the polling loop should depend on
 * "which prices are being looked at", not on the navigation layout, so re-ordering or adding
 * a tab cannot silently change how much the app asks of its data providers.
 */
sealed interface VisibleScope {
    /** The holdings list - the portfolio header and rows. */
    data object Portfolio : VisibleScope

    /** The watchlist tab. */
    data object Watchlist : VisibleScope

    /** One stock's detail screen: the only price on screen is this one. */
    data class Detail(val symbol: String) : VisibleScope

    /**
     * No live price is displayed at all - Activity, Advice, Settings, the search sheet, the
     * in-app reader. The polling loop idles completely.
     */
    data object None : VisibleScope

    companion object {
        /**
         * Which symbols an automatic tick should fetch.
         *
         * PULLED OUT OF THE VIEWMODEL ON PURPOSE. Round 47 closed by noting that
         * `PortfolioViewModel` is the largest untested thing in the project, because its
         * `init` starts a polling loop that never lets a test settle, and that "extracting
         * that loop behind a seam would make it testable". This is that seam for the half
         * that matters most: this function alone decides how much the app asks of its data
         * providers, and it is now a pure function of the scope and the rows.
         *
         * @param allTracked only consulted for the empty-portfolio fallback, so a first
         *        launch with nothing computed yet still fetches something rather than
         *        sitting silent. Passed as a lambda because the fallback reads the database.
         */
        fun symbolsFor(
            scope: VisibleScope,
            rows: List<Row>,
            allTracked: () -> List<String>
        ): List<String> = when (scope) {
            is Detail -> listOf(scope.symbol)
            // The portfolio header and rows are drawn from the holdings; watched symbols are
            // not on this screen. The fallback covers a cold start where rows are not built
            // yet - without it a first launch would fetch nothing at all.
            Portfolio -> rows.filter { !it.watchOnly }.map { it.symbol }.ifEmpty { allTracked() }
            // No fallback here: an empty watchlist genuinely has nothing to fetch, and
            // falling back to everything would quietly undo the whole saving.
            Watchlist -> rows.filter { it.watchOnly }.map { it.symbol }
            None -> emptyList()
        }

        /**
         * Which scope the current navigation state means - the OTHER half of the same seam.
         *
         * This used to be a `when` inside `MainActivity`, where nothing could test it, and
         * Round 54 gave it a new way to be wrong: the Research screen lives inside the Watch
         * tab and shows no watchlist row at all, so leaving it as "on the Watch tab" would
         * poll every watched symbol behind a screen displaying none of them - the exact
         * waste the visible-scope mechanism exists to stop. The order of the branches is the
         * meaning: the deepest layer on screen wins.
         */
        fun choose(
            readerOpen: Boolean,
            searching: Boolean,
            detail: String?,
            onPortfolioTab: Boolean,
            onWatchTab: Boolean,
            researchShowing: Boolean
        ): VisibleScope = when {
            readerOpen -> None
            searching -> None
            detail != null -> Detail(detail)
            onPortfolioTab -> Portfolio
            onWatchTab && researchShowing -> None
            onWatchTab -> Watchlist
            // Feed shows headlines, not prices; Activity, Advice and Settings show none.
            else -> None
        }
    }
}

/**
 * `ComponentCallbacks2.TRIM_MEMORY_BACKGROUND`, spelled out rather than imported so the
 * ViewModel keeps no dependency on an Activity-side type. At this level and above the system
 * is actively reclaiming memory and the process is a candidate to be killed.
 */
/**
 * Whether the pull-to-refresh spinner may be on, given who asked and what is running.
 *
 * A one-line rule with its own function because getting it wrong shipped a spinner that
 * turned for the rest of the session (see `syncManualIndicator`): **the indicator may only be
 * on while work is actually happening.** Pure, so the invariant can be tested without
 * standing up a ViewModel - which `init`'s polling loop otherwise makes impractical.
 */
fun spinnerShouldShow(userAsked: Boolean, quotesLoading: Boolean, feedLoading: Boolean): Boolean =
    userAsked && (quotesLoading || feedLoading)

/**
 * `ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL`. The app is still in the foreground but
 * the device is critically short - releasing here is what avoids being killed outright.
 */
private const val TRIM_RUNNING_CRITICAL = 15

private const val TRIM_BACKGROUND = 40

/**
 * `ComponentCallbacks2.TRIM_MEMORY_MODERATE`. The process is backgrounded AND the system is
 * short enough that it is working down the LRU list. Distinct from TRIM_MEMORY_BACKGROUND
 * (40), which only means "you are now ON that list" and fires on every app switch - see the
 * long note in `onTrimMemory`.
 */
private const val TRIM_MODERATE = 60

/**
 * How many headlines the feed holds in memory at once.
 *
 * Load-bearing since the feed became additive in v6.3: it used to be bounded by whatever a
 * single refresh returned, and now it merges into the cache instead of replacing it.
 */
private const val MAX_FEED_ITEMS = 400

/** Per-stock news list cap, for the same reason [MAX_FEED_ITEMS] exists. */
private const val MAX_SYMBOL_NEWS = 60

/**
 * How many Form 4 filings the Insider tab holds.
 *
 * Its OWN cap, and that is the point. Sharing [MAX_FEED_ITEMS] with the news feed is what
 * reduced the tab to a single row: filings are days old by law, headlines are minutes old,
 * and one sorted-by-time list with one cap means news always wins. A month of filings across
 * a 20-symbol portfolio is a couple of hundred at the outside - Ford alone filed 11 in the
 * sample month - so this is generous enough never to bind in normal use while still bounding
 * a pathological month.
 */
private const val MAX_INSIDER_FILINGS = 500

/**
 * How often the intraday candle series is re-pulled. See [PortfolioViewModel.refreshSparklines].
 *
 * Matched to the candle interval itself (`MarketData.INTERVAL` is 5m): asking more often than
 * the provider produces a new point cannot return anything new.
 */
private const val SPARK_REFRESH_MS = 5 * 60 * 1000L

/**
 * How long after leaving a headline screen the per-symbol news pull keeps running.
 *
 * Long enough that a pass which started while the Feed was on screen completes, and that
 * flicking to another tab and back does not skip an update. Short enough that walking away
 * from the Feed genuinely stops the traffic.
 */
private const val NEWS_VISIBLE_GRACE_MS = 5 * 60 * 1000L

/**
 * How long headlines already pulled count as fresh enough for an advice request.
 *
 * Generous on purpose: the model is being asked what it makes of a portfolio, and a headline
 * twenty minutes old is exactly as useful for that as one from thirty seconds ago.
 */
private const val ADVICE_NEWS_TTL_MS = 20 * 60 * 1000L

/**
 * The longest a closed-market price may go without a refresh, however final it looks.
 *
 * A backstop rather than a cadence. [PortfolioViewModel.pricesAreFinal] reasons that a quote
 * fetched after the close cannot change before the next session, which is true - but it is
 * reasoning from a US market calendar that knows nothing about holidays, and being wrong about
 * that should cost one stale afternoon, not an indefinitely frozen screen. Six hours is long
 * enough to sleep through a weekend night in a handful of requests.
 */
private const val CLOSED_MARKET_MAX_AGE_MS = 6 * 3_600_000L

/** Bound on the never-parseable-filing set, so a decade of Form 3s cannot grow without limit. */
private const val MAX_INSIDER_SKIP = 4000

/** How long a full multi-source pull for one stock counts as current. */
private const val DEEP_NEWS_TTL_MS = 5 * 60 * 1000L

/** Headlines kept per symbol. Enough to scroll, bounded so a long session cannot grow. */
private const val MAX_NEWS_PER_SYMBOL = 40

/** The one fixed file in Downloads that survives the app being uninstalled. */
private const val AUTOSAVE_FILE = "portfolio-autosave.json"

/**
 * The prompt files, at FIXED names so they are replaced rather than accumulated. One tap of
 * "Make prompt file" used to leave one more `claude-advice-prompt-<stamp>.md` in Downloads
 * for ever; TJ's folder had collected several. A single file is also one fewer way to pick
 * the wrong one in the chooser - which is the v5.2 bug this app already has scar tissue for.
 */
private const val ADVICE_PROMPT_FILE = "claude-advice-prompt.md"
private const val SCREENSHOT_PROMPT_FILE = "claude-screenshot-prompt.md"

class PortfolioViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Db(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())

    /**
     * The raw quote cache, exposed for ONE case: a symbol the user opened from search that
     * they neither hold nor watch.
     *
     * [publish] builds [Row]s from positions and the watchlist, which is correct for the
     * lists - a stock you have no relationship with does not belong on your portfolio or
     * your watchlist. But the detail screen can be opened for exactly such a symbol, and
     * before this it drew no price at all: the polling loop was fetching the quote (the
     * visible scope is `Detail(symbol)`, so it is the only thing being fetched) and the
     * screen had no way to reach it. The detail screen builds a transient row from this.
     */
    val quotes: StateFlow<Map<String, Quote>> = _quotes.asStateFlow()

    private val _news = MutableStateFlow<Map<String, List<NewsItem>>>(emptyMap())
    val news: StateFlow<Map<String, List<NewsItem>>> = _news.asStateFlow()
    private val _newsLoading = MutableStateFlow<Set<String>>(emptySet())
    val newsLoading: StateFlow<Set<String>> = _newsLoading.asStateFlow()

    private val _advice = MutableStateFlow<Advice?>(null)
    val advice: StateFlow<Advice?> = _advice.asStateFlow()
    private val _adviceLoading = MutableStateFlow(false)
    val adviceLoading: StateFlow<Boolean> = _adviceLoading.asStateFlow()
    private val _adviceError = MutableStateFlow<String?>(null)
    val adviceError: StateFlow<String?> = _adviceError.asStateFlow()

    private val _importResult = MutableStateFlow<ExtractResult?>(null)
    val importResult: StateFlow<ExtractResult?> = _importResult.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _search = MutableStateFlow<List<com.tj.portfolio.net.SearchHit>>(emptyList())
    val search: StateFlow<List<com.tj.portfolio.net.SearchHit>> = _search.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var searchJob: Job? = null

    private val _lastImport = MutableStateFlow<Db.ImportLog?>(null)
    val lastImport: StateFlow<Db.ImportLog?> = _lastImport.asStateFlow()

    private val _feed = MutableStateFlow<List<com.tj.portfolio.data.FeedItem>>(emptyList())
    val feed: StateFlow<List<com.tj.portfolio.data.FeedItem>> = _feed.asStateFlow()
    private val _trending = MutableStateFlow<List<com.tj.portfolio.data.Trending>>(emptyList())
    val trending: StateFlow<List<com.tj.portfolio.data.Trending>> = _trending.asStateFlow()
    private val _feedLoading = MutableStateFlow(false)
    val feedLoading: StateFlow<Boolean> = _feedLoading.asStateFlow()
    private val _feedAt = MutableStateFlow(0L)
    val feedAt: StateFlow<Long> = _feedAt.asStateFlow()

    /**
     * Form 4 filings for ONE stock, for its own detail screen.
     *
     * Now holds parsed [InsiderFiling]s rather than the generic feed row it used to. The old
     * type was a `FeedItem` whose title was EDGAR's boilerplate form name - identical on every
     * filing ever made - so the detail screen's insider section could only ever say
     * "Statement of changes in beneficial ownership of securities", six times over.
     */
    private val _insider =
        MutableStateFlow<Map<String, List<com.tj.portfolio.data.InsiderFiling>>>(emptyMap())
    val insider: StateFlow<Map<String, List<com.tj.portfolio.data.InsiderFiling>>> =
        _insider.asStateFlow()

    /**
     * EVERY Form 4 filed on anything the user follows in the last month, newest first - the
     * Insider tab's own store.
     *
     * A SEPARATE STORE, AND THAT IS THE HEADLINE FIX OF THIS ROUND. Filings used to live in
     * the shared news list, which is sorted newest-first and capped at [MAX_FEED_ITEMS]. A
     * filing is up to two business days old BY LAW while headlines are minutes old, so on a
     * normal refresh several hundred fresh headlines sorted straight over the top of them and
     * every filing but the very newest fell off the end of the list. The Insider chip was
     * filtering a list its rows had already been evicted from - which is exactly the "only
     * shows one result" TJ photographed. Nothing about the tab's filter was wrong; the data
     * was gone before the filter ran.
     */
    private val _insiderFilings =
        MutableStateFlow<List<com.tj.portfolio.data.InsiderFiling>>(emptyList())
    val insiderFilings: StateFlow<List<com.tj.portfolio.data.InsiderFiling>> =
        _insiderFilings.asStateFlow()

    private val _insiderLoading = MutableStateFlow(false)
    val insiderLoading: StateFlow<Boolean> = _insiderLoading.asStateFlow()

    /**
     * Accession number -> filing already parsed this session.
     *
     * A filed Form 4 is immutable, so this cache is exact rather than a guess: once a filing
     * has been read it is never fetched again, and a symbol with no new filings costs exactly
     * one listing request per pass no matter how often the tab is opened. Without it, every
     * insider refresh would re-download every filing in the month-long window - roughly a
     * hundred requests against a service that publishes a 10-per-second cap.
     */
    private val insiderDocs =
        HashMap<String, com.tj.portfolio.data.InsiderFiling>(256)

    /**
     * Accessions fetched once and found to hold nothing this app can use.
     *
     * See `Insider.Unreadable`. A filed document never changes, so this answer is permanent -
     * and it is PERSISTED (`Keys.INSIDER_SKIP`), because otherwise every launch starts by
     * re-downloading the same handful of Form 3s and holdings-only filings for every symbol.
     * Bounded so a decade of them cannot grow without limit.
     */
    private val insiderSkip = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * ONE rate limiter for everything this app asks of EDGAR, for the life of the ViewModel.
     *
     * It used to be created per call site, which quietly defeats the purpose: a feed pass and
     * two stock screens opened in quick succession each made their own three-permit gate, so
     * nine requests could be in flight against a service that publishes a hard cap and blocks
     * on it. A semaphore only limits what shares it.
     */
    private val secGate = Semaphore(MAX_PARALLEL_SEC)

    var sortMode: String = db.get(Keys.SORT_MODE, SORT_VALUE).ifBlank { SORT_VALUE }
        private set

    private var autoJob: Job? = null

    /** The feed pass currently in flight, so leaving the app can stop it. */
    private var feedJob: Job? = null

    /** The EDGAR pass, which runs alongside the feed rather than inside it. */
    private var insiderJob: Job? = null

    /**
     * A COROUTINE SCOPE THAT ENDS WHEN THE USER LEAVES THE APP (Round 57).
     *
     * THE PROBLEM IT SOLVES. `viewModelScope` is scoped to the ViewModel, not to the screen -
     * it survives the Activity being stopped and only ends when the ViewModel is cleared. An
     * audit of this file found **forty-two** `viewModelScope.launch` sites, of which exactly
     * three (`autoJob`, `feedJob`, `insiderJob`) were cancelled when the app went to the
     * background. Everything else carried on. Concretely, before this scope existed:
     *
     *   - opening a stock's Analysts tab and immediately pressing home downloaded the whole
     *     ~195 KB analyst history on mobile data, with nothing on screen to draw it;
     *   - tapping the Research tab and leaving fired all ~18 screener/social/RSS requests and
     *     then up to six passes of per-row Nasdaq lookups - and `enrichJob` had no
     *     cancellation path anywhere in the file, ever;
     *   - `refreshSparklines` was launched from inside `refresh()` but into `viewModelScope`,
     *     making it a SIBLING rather than a child, so it outlived even the cancellation of the
     *     thing that started it: up to twenty Yahoo chart requests firing after the user left.
     *
     * WHAT BELONGS IN HERE: work whose result is only worth having while someone is looking
     * at it. Headlines for a stock's page, fundamentals, analyst history, filings for one
     * symbol, sparklines, the whole Research build, symbol search.
     *
     * WHAT DOES NOT, and why each one is deliberate:
     *   - anything that WRITES (`cacheNews`, `cacheQuotes`, backups, exports, an import being
     *     committed). Cancelling a write loses data; these are short and must land.
     *   - the batched quote wave in `refresh()`. It is now ONE request for the whole
     *     portfolio, and its answer is what the user sees the instant they come back.
     *   - Claude requests. They are user-initiated, rare, and already paid for the moment they
     *     are sent - cancelling one wastes the tokens without saving the bytes.
     *
     * Rebuilt on every return to the foreground, because a cancelled scope stays cancelled.
     */
    private var fgScope: CoroutineScope = viewModelScope + SupervisorJob(viewModelScope.coroutineContext[Job])

    /**
     * What the user can actually see right now. Drives [symbolsToQuote] - see the long note
     * there for why this is the largest remaining cut to the app's request rate.
     */
    private var visibleScope: VisibleScope = VisibleScope.Portfolio


    /**
     * When the app last went to the background. A quick flick away and back - the
     * notification shade, an incoming call, a permission dialog - must not be charged the
     * price of a full refresh every time.
     */
    private var wentBackgroundAt: Long = 0L

    /** Last time a scope change triggered a catch-up refresh, for the same debounce. */
    private var lastScopeRefresh: Long = 0L

    /**
     * Coming back within this long reuses what is already on screen instead of re-fetching.
     * Shorter than any polling interval the user can choose, so it can never delay a genuine
     * update; long enough to absorb the flick-away-and-back that happens constantly in
     * normal use.
     */
    private val RESUME_REFRESH_GRACE_MS = 4_000L


    /** When the Reddit aggregators were last successfully read. */
    private var socialAt: Long = 0L

    /** Whether the on-disk headline cache has been read into memory this foreground spell. */
    private var feedRestored: Boolean = false

    /** Memoised feed de-duplication keys - see [storyKey] for why this exists. */
    private val storyKeys = HashMap<String, String>(MAX_FEED_ITEMS * 2)

    /**
     * Whether the app is actually on screen. The polling loop lives in viewModelScope,
     * which OUTLIVES the Activity being stopped - so before this existed the app kept
     * fetching every symbol every 15 seconds while sitting in the background, draining the
     * battery and doing work nobody could see.
     */
    private var foreground = true

    // ---- what a full replay produced, reused by every price-only rebuild
    //
    // THESE MUST BE DECLARED ABOVE `init`. Kotlin runs property initialisers and init
    // blocks in the order they appear in the class body, so when these sat below init the
    // sequence was: init -> recompute() fills them from the database -> and THEN
    // `= emptyList()` ran and wiped every one of them, plus reset ledgerDay to 0.
    // The next refresh healed it (reprice() sees ledgerDay == 0 and does a full replay),
    // which is why it did not show up in testing - but it left the app one mistimed call
    // away from drawing an empty portfolio over perfectly good data. Do not move them.
    /** Watchlist as of the last full [recompute]. */
    private var cachedWatch: List<String> = emptyList()
    /** Transactions in ledger order (ascending), straight from the database. */
    private var cachedTxns: List<Txn> = emptyList()
    /** The same rows in the newest-first order the Activity tab shows, sorted once. */
    private var cachedTxnsDesc: List<Txn> = emptyList()
    /** Positions from the last ledger replay. */
    private var cachedPositions: List<Position> = emptyList()

    /** Local midnight of the day the ledger was last replayed. */
    private var ledgerDay: Long = 0L

    // Also above init, for the same reason as the cache fields: a property declared below
    // an init block does not exist yet while that block runs, and anything init reached
    // would see null through a non-null type.
    /** Symbols whose news came from a full multi-source pull, and when. */
    private val deepNewsAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Symbols with an EDGAR fetch already in flight. */
    private val insiderLoadingSet = java.util.Collections.synchronizedSet(HashSet<String>())

    /** When each symbol's intraday candle series was last pulled. See [refreshSparklines]. */
    private val sparkAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** When the advice preload last got headlines for a symbol. See [preloadNewsForAdvice]. */
    private val adviceNewsAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Whether a screen that reads per-symbol HEADLINES is showing - the Feed tab, or a stock's
     * own page.
     *
     * The visible-scope mechanism cannot answer this: the Feed tab shows no prices, so its
     * scope is `None`, which is the same answer Settings gives. And the per-symbol news pull
     * is the app's second-biggest source of traffic after the quote poll - one request per
     * followed symbol every three minutes, ~480 an hour on a 24-symbol portfolio, running
     * whether or not the tab had ever been opened.
     *
     * `true` until the UI says otherwise, so a refresh that happens before the first
     * composition still does the full pass rather than silently doing less.
     */
    @Volatile private var newsVisible: Boolean = true

    /** The last moment a headline screen was on show, so leaving one does not strand a pass. */
    @Volatile private var newsVisibleAt: Long = System.currentTimeMillis()

    /**
     * Set when the transactions table is empty but the app has recorded holding rows before.
     * Drives the recovery banner: an empty Portfolio tab is ambiguous on its own, and
     * "No holdings yet" is a terrible thing to show someone whose data has just vanished.
     *
     * ABOVE init, and it must stay there - recompute() writes to it and init calls
     * recompute(), so declared below init this field is still null when that runs and the
     * app dies on launch with a NullPointerException. See the note on the cache fields.
     */
    private val _dataMissing = MutableStateFlow(false)
    val dataMissing: StateFlow<Boolean> = _dataMissing.asStateFlow()

    /**
     * Set when this install has NEVER held a transaction, but a copy of a portfolio is
     * sitting in Downloads from a previous one.
     *
     * That is what the phone looks like straight after the app has been uninstalled and put
     * back, or its storage cleared: the database is new, the private snapshots went with the
     * old install, the "you had N transactions" high-water mark has reset to zero - so the
     * missing-data banner cannot fire - and the app would cheerfully show "No holdings yet"
     * on top of a perfectly good backup the user has no reason to know is there.
     *
     * ABOVE init, like every property in this class - see the note on the cache fields.
     */
    private val _recoverableBackup = MutableStateFlow(false)
    val recoverableBackup: StateFlow<Boolean> = _recoverableBackup.asStateFlow()

    /**
     * FUNDAMENTALS AND ANALYST RATINGS, per symbol.
     *
     * ABOVE init like every property in this class - see the note on the cache fields and
     * checkinit.py. Nothing in init reaches these today; the rule is unconditional because
     * "it happens not to be touched yet" is exactly the reasoning that shipped the v4.6
     * startup crash.
     *
     * One map, not two. The analyst history arrives in a second request (it is an order of
     * magnitude bigger than everything else) but it belongs to the same stock, so it is
     * merged into the same [com.tj.portfolio.data.Fundamentals] object rather than living
     * in a parallel map the UI would have to stitch back together on every recomposition.
     */
    private val _fundamentals =
        MutableStateFlow<Map<String, com.tj.portfolio.data.Fundamentals>>(emptyMap())
    val fundamentals: StateFlow<Map<String, com.tj.portfolio.data.Fundamentals>> =
        _fundamentals.asStateFlow()

    /** Symbols with a core fundamentals fetch in flight, for the spinner. */
    private val _fundLoading = MutableStateFlow<Set<String>>(emptySet())
    val fundLoading: StateFlow<Set<String>> = _fundLoading.asStateFlow()

    /** Symbols with an analyst-ratings fetch in flight. */
    private val _ratingsLoading = MutableStateFlow<Set<String>>(emptySet())
    val ratingsLoading: StateFlow<Set<String>> = _ratingsLoading.asStateFlow()

    /**
     * When each kind was last pulled from the NETWORK for a symbol.
     *
     * Deliberately not derived from [com.tj.portfolio.data.Fundamentals.fetched]: that field
     * carries the age of the DATA, which is also set when a row is read back from the disk
     * cache. Using it as the throttle would mean a cached row from two hours ago immediately
     * triggered another network pull on every screen open. This records what the app did,
     * not how old the answer is.
     */
    private val coreFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val ratingsFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val computeAffecting = setOf(
        Keys.CASH_OVERRIDE, Keys.USE_CASH_OVERRIDE, Keys.COST_METHOD, Keys.SORT_MODE
    )

    /** Re-entry guard for the empty-publish check in publish(). */
    private var reloadingFromDisk = false

    // Also above init, per checkinit.py - see the note on the cache fields. Neither is
    // reached from init today, but the guard is deliberately unconditional: "it happens not
    // to be touched yet" is exactly the reasoning that shipped the v4.6 startup crash.
    /** The API's hard per-image ceiling, with room left for base64's 4/3 expansion. */
    private val MAX_IMAGE_BYTES = 4_500_000

    /**
     * Released when Android asks for memory back. Held as a field so it is not collected
     * while the ViewModel is alive - [com.tj.portfolio.util.MemoryTrim] holds listeners
     * weakly on purpose, so something has to own this.
     */
    private val trimListener = com.tj.portfolio.util.MemoryTrim.Listener { level ->
        onTrimMemory(level)
    }

    /**
     * Give back everything that can simply be fetched or recomputed.
     *
     * WHAT IS DROPPED, AND WHY EACH IS SAFE.
     *  - the conditional-HTTP feed cache: costs one re-download, nothing else;
     *  - sparklines: the per-symbol intraday series, easily the largest thing the ViewModel
     *    holds (a 1-minute chart over a full session is ~400 floats per symbol, and the app
     *    holds one per holding AND per watchlist entry). Redrawn on the next quote;
     *  - headlines for symbols the user is not looking at, insider filings, and the feed
     *    list: all re-fetched on the next feed refresh.
     *
     * WHAT IS NOT DROPPED, deliberately: the quote PRICES. They are small, they are what the
     * portfolio is drawn from, and they are the app's offline fallback - dropping them would
     * turn a memory trim into a blank screen with no way to refill it while offline. The
     * cached-quote table in SQLite is the durable copy either way.
     *
     * Nothing here can lose user data: every transaction is a synchronous SQLite write.
     *
     * THE LEVELS ARE TIERED, AND THAT IS THE WHOLE DESIGN.
     *
     * The obvious implementation - drop everything as soon as the UI is hidden - is WRONG
     * here, and would have worked directly against the other half of what TJ asked for.
     * UI_HIDDEN fires every single time the app goes off screen, so throwing the caches away
     * at that level means every return to the app re-downloads every feed and every
     * headline. That is more requests to the providers, not fewer, in exchange for memory
     * the system had not actually asked for.
     *
     * ROUND 58 FINISHED THAT ARGUMENT. Nothing at all is released below MODERATE (60) now,
     * sparklines included. The old code made an exception for the series on the grounds that
     * it "rides along with the next quote", which stopped being true in Round 56 when the
     * batched quote endpoint - which carries no candles - took over the poll. UI_HIDDEN is
     * delivered on every app switch, so that exception was blanking every chart every time
     * the user looked at another app, for up to five minutes. Everything released here is
     * now on disk, so MODERATE and above frees the heap and costs a SQLite read on return,
     * never a download and never a blank screen.
     */
    private fun onTrimMemory(level: Int) {
        // ---------------------------------------------------------------------------------
        // ROUND 58: THIS TEST USED TO BE `level < TRIM_RUNNING_CRITICAL`, AND IT IS WHAT TJ
        // REPORTED AS "I switched apps and came back and the stock charts had disappeared."
        //
        // TRIM_MEMORY_UI_HIDDEN is 20 and TRIM_RUNNING_CRITICAL is 15, so UI_HIDDEN passed
        // that guard - and Android delivers UI_HIDDEN on EVERY single app switch, with no
        // memory pressure implied at all. Every switch away therefore emptied every
        // sparkline and every detail chart.
        //
        // The comment that justified it said the series "arrives with the next quote, so
        // dropping it costs nothing on the wire". That was TRUE when it was written and
        // stopped being true in Round 56, when the batched quote endpoint - which returns no
        // candles at all - replaced the per-symbol chart call. Since then the series has been
        // owned by `refreshSparklines` on a five-minute clock whose `sparkAt` mark was
        // already stamped, so the charts did not come back with the next quote: they stayed
        // blank for up to five minutes. A stale comment outlived the design it described.
        //
        // The rule now matches the one the news caches already follow, and for the same
        // reason: only MODERATE (60) and above mean the system is actually short of memory.
        // A routine app switch is not a reason to throw away anything the user will see the
        // instant they come back.
        if (level < TRIM_MODERATE) return

        // The intraday series is comfortably the largest thing held - a full session at
        // five-minute candles is a few hundred Doubles per symbol, held for every holding AND
        // every watchlist entry. It is also the cheapest to get back: `restoreFromCache` on
        // the next resume reads it straight out of SQLite with no request at all.
        _quotes.value = _quotes.value.mapValues { (_, q) ->
            if (q.spark.isEmpty()) q else q.copy(spark = emptyList())
        }

        // The fetched chart series, for the same reason and with the same recovery: every
        // one of them is on disk in `chart_cache`, and `loadChart` reads disk before it ever
        // reaches for the network.
        if (_charts.value.isNotEmpty()) {
            _charts.value = emptyMap()
            chartDiskRead.clear()
        }

        // ---------------------------------------------------------------------------------
        // THIS THRESHOLD WAS WRONG AND TJ FELT IT: "I go to the news feed tab, switch apps,
        // come back, and the news is gone."
        //
        // v6.1 released these caches from TRIM_MEMORY_BACKGROUND (40) upward, on the reading
        // that 40 means "the system is actively reclaiming". It does not. Android delivers
        // TRIM_MEMORY_BACKGROUND when the process simply JOINS the background LRU list - it
        // is a routine state change that happens EVERY time the user switches away, not a
        // signal of pressure. So switching apps emptied the feed, every time, on a device
        // with plenty of memory free.
        //
        // The levels that actually mean "memory is short and you may be killed" are
        // RUNNING_LOW / RUNNING_CRITICAL while in the foreground - handled above - and
        // MODERATE (60) / COMPLETE (80) once backgrounded. So the threshold is 60.
        //
        // Being wrong here is now much less costly anyway: the headlines are in SQLite as of
        // v6.3, so releasing them frees the heap without losing anything. `restoreFromCache`
        // puts them straight back when the user returns.
        // ---------------------------------------------------------------------------------
        com.tj.portfolio.net.Http.onLowMemory()
        com.tj.portfolio.net.SymbolSearch.clearMemo()
        _news.value = emptyMap()
        _insider.value = emptyMap()
        // Same reasoning as the headlines: these are on disk as of v5, so dropping them
        // frees the heap without losing anything - loadFundamentals paints them straight
        // back from SQLite when the user reopens a stock. The fetch marks are cleared with
        // them, or the next open would find nothing in memory and still refuse to refetch.
        _fundamentals.value = emptyMap()
        coreFetchedAt.clear()
        ratingsFetchedAt.clear()
        // Same reasoning again: on disk since Round 58, so dropping them frees the heap and
        // costs one SQLite read when the tab is reopened.
        _holdings.value = emptyMap()
        holdingsFetchedAt.clear()
        _feed.value = emptyList()
        _trending.value = emptyList()
        storyKeys.clear()
        feedRestored = false
        // so the next feed refresh actually re-fetches rather than trusting a stale mark
        socialAt = 0L
    }

    /**
     * Put the feed back from the database.
     *
     * Called on launch and every time the app returns to the foreground, so a trim, a process
     * death, or simply switching apps never leaves the Feed tab blank. Reading ~600 rows out
     * of SQLite is a few milliseconds and happens on IO, against a network refresh that takes
     * seconds - so the user sees the headlines they had immediately, and the network merely
     * adds whatever is new on top.
     */
    private fun restoreFromCache(force: Boolean = false) {
        // Charts come back on EVERY resume, not just the first: a trim that ran while the
        // app was away is exactly when they need putting back, and unlike the feed this
        // costs nothing when there is nothing to do.
        restoreSparklines()
        if (feedRestored && !force) return
        feedRestored = true
        viewModelScope.launch(Dispatchers.IO) {
            val cached = runCatching { db.cachedNews() }.getOrDefault(emptyList())
            if (cached.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                // Merge rather than assign: a refresh may already have landed while this was
                // reading, and the cache must never overwrite something newer.
                if (_feed.value.isEmpty()) publishFeed(cached)
                else publishFeed(_feed.value + cached)
            }
        }
    }

    /**
     * Put the intraday series back from SQLite, for any quote that has lost it.
     *
     * THE RECOVERY PATH THAT DID NOT EXIST. `quotes.spark` has been persisted since v1, so
     * after a memory trim the series was still sitting on disk - but nothing ever read it
     * back, and the only route to a chart was a network fetch that `sparkAt` was already
     * suppressing. Charts therefore stayed blank for five minutes with the data they needed
     * a single query away.
     *
     * Costs nothing in the ordinary case: it returns immediately unless something on screen
     * is actually missing its series, and it never overwrites a series that is already
     * there - a fetch that landed first is by definition newer than the disk row.
     */
    private fun restoreSparklines() {
        if (_quotes.value.isEmpty()) return
        if (_quotes.value.none { it.value.spark.isEmpty() }) return
        viewModelScope.launch(Dispatchers.IO) {
            val disk = runCatching { db.cachedQuotes() }.getOrDefault(emptyMap())
            if (disk.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                var changed = false
                val m = HashMap(_quotes.value)
                for ((sym, q) in m) {
                    if (q.spark.isNotEmpty()) continue
                    val series = disk[sym]?.spark ?: continue
                    if (series.isEmpty()) continue
                    m[sym] = q.copy(spark = series)
                    changed = true
                }
                if (changed) _quotes.value = m
            }
        }
    }

    // ---- Research tab state.
    //
    // Declared HERE, above init, and not beside the research functions at the bottom of the
    // file, because of `checkinit.py`: Kotlin runs property initialisers in source order, so
    // anything declared below `init` is still null while init runs. init calls
    // loadCachedResearch(), which touches _research. This is the exact shape of the v4.6
    // startup crash, and the guard script fails the build rather than let it recur.

    private val _research = MutableStateFlow(com.tj.portfolio.data.ResearchSet())
    val research: StateFlow<com.tj.portfolio.data.ResearchSet> = _research.asStateFlow()

    /** "" when idle, else what is happening, so the screen can say which wait this is. */
    private val _researchBusy = MutableStateFlow("")
    val researchBusy: StateFlow<String> = _researchBusy.asStateFlow()

    private val _researchError = MutableStateFlow<String?>(null)
    val researchError: StateFlow<String?> = _researchError.asStateFlow()

    /**
     * How many rows of each section are on screen. TJ's rule: ten, and nothing more until he
     * presses the button - so this is the ONLY thing "Load more" changes, and every network
     * cost downstream is derived from it.
     */
    private val _researchShown = MutableStateFlow(
        com.tj.portfolio.data.ResearchSet.SECTIONS.associateWith {
            com.tj.portfolio.data.ResearchSet.PAGE
        }
    )
    val researchShown: StateFlow<Map<String, Int>> = _researchShown.asStateFlow()

    /** "BEST:NVDA" - so a second pass over the same row never re-fetches it. */
    private val analystDone = java.util.Collections.synchronizedSet(HashSet<String>())
    private val vehicleDone = java.util.Collections.synchronizedSet(HashSet<String>())
    private var researchJob: Job? = null
    private var enrichJob: Job? = null

    // ================================================================ PRICE CHARTS
    //
    // ALL OF THESE ARE ABOVE `init` DELIBERATELY - see checkinit.py and the note on the
    // ledger cache fields. Nothing here is reached from init today; the rule is absolute
    // because "it happens not to be touched yet" is exactly the reasoning that shipped the
    // v4.6 startup crash.

    /**
     * Every fetched series, keyed by [chartKey] - one entry per (symbol, range) pair.
     *
     * ONE FLAT MAP RATHER THAN A MAP OF MAPS. The UI only ever asks for a single pair at a
     * time, and a nested map would make every write allocate an inner map as well as an
     * outer one - on a StateFlow that is read from composition.
     */
    private val _charts = MutableStateFlow<Map<String, ChartSeries>>(emptyMap())
    val charts: StateFlow<Map<String, ChartSeries>> = _charts.asStateFlow()

    /** Chart keys with a fetch in flight, so the range chips can show which one is loading. */
    private val _chartLoading = MutableStateFlow<Set<String>>(emptySet())
    val chartLoading: StateFlow<Set<String>> = _chartLoading.asStateFlow()

    /**
     * When each chart key was last pulled FROM THE NETWORK.
     *
     * Deliberately not derived from `ChartSeries.fetched`, for the same reason
     * [coreFetchedAt] is not derived from `Fundamentals.fetched`: that field carries the age
     * of the DATA and is preserved when a row is read back off disk, so using it as the
     * throttle would send a request on every screen open for any cache older than its TTL,
     * even one that was just refused.
     */
    private val chartFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Symbols whose cached chart rows have already been read off disk this session.
     *
     * The disk read pulls EVERY range for the symbol in one query, so it is worth doing once
     * and never again: switching between 1D and 1Y then back must not re-query SQLite.
     */
    private val chartDiskRead = java.util.Collections.synchronizedSet(HashSet<String>())

    // ================================================================ FUND HOLDINGS

    /** What each fund holds, by symbol. Absent means "not looked up yet". */
    private val _holdings = MutableStateFlow<Map<String, FundHoldings>>(emptyMap())
    val holdings: StateFlow<Map<String, FundHoldings>> = _holdings.asStateFlow()

    /** Symbols with a holdings fetch in flight. */
    private val _holdingsLoading = MutableStateFlow<Set<String>>(emptySet())
    val holdingsLoading: StateFlow<Set<String>> = _holdingsLoading.asStateFlow()

    /** When each symbol's holdings were last pulled from the NETWORK - see [coreFetchedAt]. */
    private val holdingsFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        com.tj.portfolio.util.MemoryTrim.register(trimListener)
        // FIRST, before anything can make a request: a cache attached after the first feed
        // pass has already run misses exactly the requests it was meant to save.
        attachHttpDiskCache()
        val cached = db.cachedQuotes()
        _quotes.value = cached
        // so the header reads "Prices updated 2h ago" offline instead of showing nothing
        cached.values.maxOfOrNull { it.updated }?.takeIf { it > 0 }?.let {
            _ui.value = _ui.value.copy(lastRefresh = it)
        }
        _lastImport.value = db.lastImport()
        recompute()
        tidyDownloadsOnce()
        checkForRecoverableBackup()
        autoBackupIfDue()
        loadCachedAdvice()
        loadCachedResearch()
        loadCachedInsider()
        restoreFromCache()
        db.get(Keys.FEED_AT).toLongOrNull()?.takeIf { it > 0 }?.let { _feedAt.value = it }
        purgeOldNewsOnce()
        refresh()
        startAuto()
    }

    /**
     * On a install that has never held anything, look for a backup left by a previous one.
     *
     * Only runs when the ledger is empty AND the high-water mark says it has always been -
     * so it costs one MediaStore lookup on a first launch and nothing at all afterwards.
     */
    private fun checkForRecoverableBackup() {
        if (db.txnCount() > 0 || lastKnownTxnCount() > 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching {
                val text = com.tj.portfolio.util.Storage.readOwnDownload(
                    getApplication(), AUTOSAVE_FILE
                )
                // it has to actually be a portfolio, not just a file with the right name
                !text.isNullOrBlank() &&
                    (JSONObject(text).optJSONArray("transactions")?.length() ?: 0) > 0
            }.getOrDefault(false)
            if (found) _recoverableBackup.value = true
        }
    }

    fun dismissRecoverableBackup() { _recoverableBackup.value = false }

    // -------------------------------------------------- settings passthrough

    fun claudeKey() = db.get(Keys.CLAUDE_KEY)
    fun finnhubKey() = db.get(Keys.FINNHUB_KEY)
    fun model() = db.get(Keys.CLAUDE_MODEL, "").ifBlank { Claude.DEFAULT_MODEL }

    fun modelChosen() = db.get(Keys.CLAUDE_MODEL).isNotBlank()

    /**
     * Model names get retired, so a hard-coded default eventually 404s. Before the first
     * request, ask the API which models this key can actually use and pick the best one.
     */
    private suspend fun ensureModel(): String {
        if (modelChosen()) return model()
        val list = withContext(Dispatchers.IO) {
            runCatching { Claude.models(claudeKey()) }.getOrDefault(emptyList())
        }
        if (list.isEmpty()) return model()
        val chosen = Claude.preferredModel(list)
        db.set(Keys.CLAUDE_MODEL, chosen)
        _models.value = list
        return chosen
    }
    fun webSearch() = db.getB(Keys.WEB_SEARCH, true)
    /**
     * 0 means off; anything else is floored at 5 seconds. The Settings field writes on every
     * keystroke, so typing "15" passed through "1" on the way - a one-second poll of every
     * holding, which is the fastest way to get rate-limited by Yahoo and end up with blank
     * prices. The typed text is left exactly as entered; only the poll interval is clamped.
     */
    fun refreshSecs(): Int {
        val v = db.get(Keys.REFRESH_SECS, "15").toIntOrNull() ?: 15
        return if (v <= 0) 0 else v.coerceAtLeast(5)
    }
    fun useCashOverride() = db.getB(Keys.USE_CASH_OVERRIDE, false)

    /**
     * Whether headlines open in the app's own reader. On by default; the switch exists so a
     * site that will not render in a WebView can still be read in a real browser.
     */
    fun inAppReader() = db.getB(Keys.IN_APP_READER, true)

    /**
     * Reader behaviour. Ads and decluttering default ON - they are what TJ asked for and the
     * failure mode is a rare site that misbehaves, which the switches exist to rescue.
     * Auto-reader defaults OFF: jumping straight to text-only would be a surprising change
     * to how every article opens, so it is opt-in.
     */
    /** Rows held and the age of the oldest, for the Settings card. */
    fun newsCacheStats(): Pair<Int, Long> = db.newsCacheStats()

    /** Rows held by the price-chart cache and the newest fetch time (Round 58). */
    fun chartCacheStats(): Pair<Int, Long> = runCatching { db.chartCacheStats() }
        .getOrDefault(0 to 0L)

    /**
     * Empty the price-chart cache from Settings.
     *
     * BOTH LAYERS, in that order, for the same reason `clearHttpCache` does it: clearing the
     * table alone would leave the in-memory copy answering every read, so nothing visibly
     * happens and the next disk write puts the same rows straight back. `chartDiskRead` goes
     * with them, or the next `loadChart` would skip the disk read that is now the only way
     * to discover the table is empty.
     */
    fun clearChartCache() {
        _charts.value = emptyMap()
        chartDiskRead.clear()
        chartFetchedAt.clear()
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.clearChartCache() } }
    }

    /** Row count and total characters held by the HTTP response cache (Round 56). */
    fun httpCacheStats(): Pair<Int, Long> = runCatching { db.httpCacheStats() }
        .getOrDefault(0 to 0L)

    /**
     * Empty the downloaded-page cache from Settings.
     *
     * BOTH LAYERS, and in that order. Clearing only the table would leave the heap map
     * holding validators for rows that no longer exist, so the next request would send an
     * `If-None-Match` the app can no longer honour a 304 for - and a 304 with nothing to
     * return is an empty feed.
     */
    fun clearHttpCache() {
        com.tj.portfolio.net.Http.clearConditionalCache()
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.clearHttpCache() } }
    }

    /** Empty the headline cache from Settings. The next refresh refills it. */
    fun clearNewsCache() {
        db.clearNewsCache()
        _feed.value = emptyList()
        _news.value = emptyMap()
        feedRestored = true    // nothing to restore; do not immediately re-read an empty table
        refreshFeed(manual = true)
    }

    fun blockAds() = db.getB(Keys.BLOCK_ADS, true)
    fun declutter() = db.getB(Keys.DECLUTTER, true)
    fun autoReader() = db.getB(Keys.AUTO_READER, false)

    /** FIFO by default so the numbers line up with the brokerage statement. */
    fun costMethod(): String = db.get(Keys.COST_METHOD, Ledger.FIFO).ifBlank { Ledger.FIFO }

    fun setCostMethod(m: String) { db.set(Keys.COST_METHOD, m); recompute() }
    fun cashOverrideValue() = db.getD(Keys.CASH_OVERRIDE, 0.0)

    /**
     * Only these settings change what the ledger computes. Everything else - API keys,
     * the model name, the refresh interval - used to trigger a full ledger replay on every
     * single keystroke while typing a key into Settings.
     */

    fun setSetting(key: String, value: String) {
        db.set(key, value)
        if (key == Keys.REFRESH_SECS) startAuto()
        if (key in computeAffecting) recompute()
    }

    fun setSettingB(key: String, value: Boolean) {
        db.setB(key, value)
        if (key in computeAffecting) recompute()
    }

    fun setSort(mode: String) {
        sortMode = mode
        db.set(Keys.SORT_MODE, mode)
        recompute()
    }

    fun toast(msg: String?) { _toast.value = msg }

    // ---------------------------------------------------------- computation

    private fun cashOverrideOrNull(): Double? =
        if (useCashOverride()) cashOverrideValue() else null

    private fun startOfDay(ms: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * FULL rebuild: reads every transaction back out of the database and replays the ledger.
     * Call this when the DATA changes - a transaction added, an override edited, the cost
     * method switched.
     */
    /** Transactions on file at the last successful replay, for the check above. */
    fun lastKnownTxnCount(): Int = db.get(Keys.LAST_TXN_COUNT).toIntOrNull() ?: 0

    fun dismissDataMissing() { _dataMissing.value = false }

    /**
     * A moment inside the trading SESSION the current quotes describe.
     *
     * The ledger needs this to decide which purchases count as "bought during this
     * session", and the wall clock is the wrong answer for most of the day. A quote runs
     * from one close to the next: overnight, at the weekend, and before the opening bell,
     * `price` and `prevClose` still describe YESTERDAY's session even though the calendar
     * has moved on. Using today's date there made a mid-session purchase look like it had
     * been held since the previous close, and credited it with the session's whole move.
     *
     * `quoteTime` is the exchange timestamp of the last regular-session print, so its
     * calendar day IS the session the rest of the quote is talking about. Using it keeps
     * the gain figures consistent with the very price they are computed from - including
     * when the quotes are stale, where being consistent with what is on screen is exactly
     * what is wanted. Only a cache old enough to be meaningless falls back to the clock.
     */
    private fun sessionInstant(): Long {
        val now = System.currentTimeMillis()
        val newestPrint = _quotes.value.values.maxOfOrNull { it.quoteTime } ?: 0L
        val aWeek = 7L * 86_400_000L
        return if (newestPrint > 0 && now - newestPrint < aWeek) newestPrint else now
    }

    fun recompute() {
        val txns = db.allTxns()
        val overrides = db.overrides()

        // Remember a good count; raise the alarm when a previously non-empty ledger reads
        // as empty. Deliberately never LOWERS the remembered figure to zero on its own -
        // only an explicit wipe does that - so one bad read cannot quietly clear the mark.
        val known = lastKnownTxnCount()
        if (txns.isNotEmpty()) {
            if (txns.size != known) db.set(Keys.LAST_TXN_COUNT, txns.size.toString())
            _dataMissing.value = false
        } else if (known > 0) {
            _dataMissing.value = true
        }

        // The SESSION the quotes describe, not the wall clock - see sessionInstant().
        val session = sessionInstant()
        cachedPositions = Ledger.positions(txns, overrides, costMethod(), session)
        cachedTxns = txns
        cachedTxnsDesc = txns.sortedWith(
            compareByDescending<Txn> { it.date }.thenByDescending { it.id }
        )
        cachedWatch = db.watchlist()
        // Tracks the SESSION day, so the ledger is replayed when the session rolls over -
        // at the opening bell, not at local midnight.
        ledgerDay = startOfDay(session)
        publish()
    }

    /**
     * PRICE-ONLY rebuild. Rows and totals are a pure function of (positions, transactions,
     * quotes, watchlist, sort, cash override) - and a price refresh only changes the quotes.
     *
     * WHY THIS EXISTS: the refresh loop used to call [recompute] on every tick, so every 15
     * seconds the app re-read every row of the transactions table, re-sorted it and replayed
     * the entire buy/sell history from the beginning - on the main thread, to arrive at
     * positions that had not changed. This does the same arithmetic on what is already in
     * memory and touches the database not at all.
     *
     * The one thing that can go stale without the data changing is which day it is, because
     * the ledger tags shares bought TODAY. Crossing local midnight falls back to a full replay.
     */
    private fun reprice() {
        if (startOfDay(sessionInstant()) != ledgerDay) { recompute(); return }
        if (ledgerDay == 0L) { recompute(); return }
        publish()
    }

    /**
     * Guard against publishing an empty portfolio over data that is actually on disk.
     *
     * WHY, and why it is not just belt-and-braces. The v4.5 initialisation-order bug blanked
     * this cache after init had filled it. Reasoning says the next refresh heals it, and the
     * crash log confirms v4.5 never crashed - yet the portfolio came up empty on the phone
     * anyway, and I could not reproduce the path that gets there. So rather than keep
     * arguing about which call ordering is possible, the invariant is enforced directly:
     *
     *   the app will never draw an empty portfolio while the transactions table has rows
     *   in it, without going back to the database first.
     *
     * That closes the whole class of failure, including whichever path I did not find. It
     * costs one COUNT query, and only when the cache is empty - never on a normal refresh.
     */
    /** Builds rows and totals from the cached ledger and the current quotes. */
    private fun publish() {
        if (!reloadingFromDisk && cachedTxns.isEmpty() && cachedPositions.isEmpty()) {
            val onDisk = runCatching { db.txnCount() }.getOrDefault(0)
            if (onDisk > 0) {
                // recompute() calls publish() again; the flag stops that recursing, so a
                // genuinely inconsistent read publishes empty once instead of looping.
                reloadingFromDisk = true
                try { recompute() } finally { reloadingFromDisk = false }
                return
            }
        }

        val quotes = _quotes.value
        val watch = cachedWatch
        val positions = cachedPositions
        val open = positions.filter { it.shares > 1e-9 }

        // Blank while the session and the calendar day agree; otherwise "Sep 3".
        val label =
            if (ledgerDay > 0 && ledgerDay != startOfDay(System.currentTimeMillis()))
                Fmt.shortDay(ledgerDay)
            else ""

        val rows = ArrayList<Row>()
        for (p in open) {
            val q = quotes[p.symbol]
            rows.add(Row(p.symbol, q?.name ?: "", p, q, false, label))
        }
        for (w in watch) {
            if (open.any { it.symbol == w }) continue
            val q = quotes[w]
            rows.add(Row(w, q?.name ?: "", null, q, true, label))
        }

        // Both list screens key their rows by symbol, and a keyed list given the same key
        // twice throws. The ledger groups by symbol and the watchlist table has symbol as
        // its primary key, so a duplicate should be impossible - this makes sure a future
        // change to either cannot turn into a crash.
        val unique = rows.distinctBy { it.symbol }

        val sorted = when (sortMode) {
            SORT_SYMBOL -> unique.sortedBy { it.symbol }
            SORT_DAY -> unique.sortedByDescending { it.dayPct }
            SORT_TOTAL -> unique.sortedByDescending { it.totalPct }
            SORT_PRICE -> unique.sortedByDescending { it.price }
            else -> unique.sortedByDescending { it.value }
        }

        _ui.value = _ui.value.copy(
            rows = sorted,
            positions = positions,
            txns = cachedTxnsDesc,
            totals = Ledger.totals(cachedTxns, positions, quotes, cashOverrideOrNull())
        )
    }

    // ------------------------------------------------------------- refresh

    /**
     * [manual] marks a refresh the user asked for by pulling down or tapping Refresh, so the
     * pull-to-refresh indicator can show for those and stay out of the way on the automatic
     * tick. If a refresh is already in flight, a user pull attaches its indicator to that one
     * rather than snapping back with nothing happening.
     */
    fun refresh(manual: Boolean = false) {
        if (_ui.value.loading) {
            if (manual) _ui.value =
                _ui.value.copy(manualRefresh = true, refreshSource = PULL_PRICES)
            return
        }
        // A pull-to-refresh is the user saying "try again NOW", so it clears any host the app
        // had decided to leave alone. Without this, coming back into signal and pulling down
        // would silently do nothing while an invisible backoff timer ran down.
        if (manual) {
            com.tj.portfolio.net.Http.clearCooldowns()
            // Same reasoning as the cooldowns: a deliberate pull is exactly when the batched
            // quote endpoint should be tried again, and an invisible "we gave up on that
            // three hours ago" flag would make the gesture quietly do less than it appears to.
            MarketData.resetBatchState()
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                loading = true,
                manualRefresh = manual || _ui.value.manualRefresh,
                refreshSource = if (manual) PULL_PRICES else _ui.value.refreshSource,
                error = null
            )
            try {
                // A refresh the USER asked for covers EVERYTHING the app tracks, not just
                // what is on screen. The visibility scoping below exists to hold down the
                // AUTOMATIC rate; it must never make a deliberate pull-to-refresh do less
                // than it used to. Without this, pulling down on the Activity or Advice tab
                // - where the scope is None - would silently refresh nothing at all.
                val symbols = if (manual) allTrackedSymbols() else symbolsToQuote()
                if (symbols.isEmpty()) return@launch

                // ROUND 58: STARTED HERE, NOT AFTER THE QUOTE PASS.
                //
                // This used to be the last line of the try block, which put it AFTER the
                // "no quotes came back, give up" early return below. One failed batch - a
                // tunnel, a lift, a cooldown - therefore skipped the candle series entirely
                // for that tick, including for symbols whose chart was blank on screen and
                // whose only route back to one is this call.
                //
                // The two are independent: the series comes from a different endpoint and is
                // merged by symbol, and the quote merge below is deliberately rebuilt from
                // the CURRENT `_quotes` rather than a snapshot, precisely so a sparkline
                // landing mid-pass is not overwritten. Starting them together also takes the
                // series off the end of the critical path.
                refreshSparklines(symbols)

                val fk = finnhubKey()
                // ONE REQUEST FOR THE WHOLE PORTFOLIO, not one per symbol - see the long note
                // on MarketData.quotes. This single line is ~85% of the app's outbound
                // traffic removed; the per-symbol chain is still there underneath it as the
                // fallback for anything the batch could not supply.
                val fetched = withContext(Dispatchers.IO) {
                    runCatching { MarketData.quotes(symbols, fk) }.getOrDefault(emptyList())
                }

                if (fetched.isEmpty()) {
                    _ui.value = _ui.value.copy(
                        error = "Couldn't reach the market data service. Showing last known prices."
                    )
                    return@launch
                }

                val now = System.currentTimeMillis()
                val previous = _quotes.value
                val stamped = fetched.map {
                    carryDisplayFields(previous[it.symbol], it.copy(updated = now, stale = false))
                }
                // single hop to IO, single transaction - previously one of each per symbol
                withContext(Dispatchers.IO) { runCatching { db.cacheQuotes(stamped) } }
                // REBUILT FROM THE CURRENT STATE, NOT FROM `previous`. The line above
                // suspends, and `refreshSparklines` writes into `_quotes` from its own
                // coroutine - so a sparkline that landed during that suspension would be
                // overwritten by a map snapshotted before it existed. Because `sparkAt` is
                // marked before the fetch, that lost write also cost a five-minute wait for
                // the retry, with `carryDisplayFields` keeping the old series alive so
                // nothing looked wrong.
                val merged = HashMap(_quotes.value)
                stamped.forEach { fresh ->
                    // The series is the one field the sparkline pass owns; never let a quote
                    // update stamp an older one back over it.
                    val live = merged[fresh.symbol]
                    merged[fresh.symbol] =
                        if (fresh.spark.isEmpty() && live != null && live.spark.isNotEmpty())
                            fresh.copy(spark = live.spark) else fresh
                }
                _quotes.value = merged

                // Coerced: a provider that answers for a symbol under a normalised ticker
                // could otherwise make this negative and print "-1 symbol(s) failed".
                val missing = (symbols.size - fetched.size).coerceAtLeast(0)
                _ui.value = _ui.value.copy(
                    lastRefresh = now,
                    error = if (missing > 0) "$missing symbol(s) failed to update" else null
                )

            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = "Refresh failed: ${e.message}")
            } finally {
                // must always clear `loading`, or every later refresh() returns at the guard
                // above and pull-to-refresh is dead for the session
                _ui.value = _ui.value.copy(loading = false)
                // The spinner is DERIVED, not cleared by hand - see syncManualIndicator. This
                // used to blank `manualRefresh` unconditionally, which is the mirror image of
                // the bug fixed there: a quote refresh finishing would snatch the spinner away
                // from a feed pass the user's pull was actually waiting on.
                syncManualIndicator()
                // Only the PRICES changed. This used to call recompute(), which re-read
                // every transaction from SQLite and replayed the whole buy/sell history on
                // the main thread - four times a minute, to arrive at the same positions.
                reprice()
            }
        }
    }

    /**
     * Keep the DISPLAY-ONLY fields a fallback provider does not supply.
     *
     * The quote chain is Yahoo -> Finnhub -> Stooq, and only Yahoo returns a company name or
     * an intraday series. A new quote replaced the old one wholesale, so the moment Yahoo
     * rate-limited a single refresh, every watchlist row's company name was replaced by
     * "Watching" and every sparkline and detail chart went blank - then came back on the next
     * tick that Yahoo answered. Nothing was wrong with the numbers; the screen just lost
     * chunks of itself at random, which reads as the app breaking.
     *
     * Only `name` and `spark` are carried across, and only when the new quote has neither.
     * Both are descriptive: a slightly old sparkline is still this symbol's own recent shape,
     * and a company name does not change. Nothing that feeds a CALCULATION is carried -
     * `prevClose` in particular stays exactly as the provider gave it, so the v2.3 rule
     * (never fabricate a previous close) is untouched.
     */
    private fun carryDisplayFields(old: Quote?, fresh: Quote): Quote {
        if (old == null) return fresh
        val name = fresh.name.ifBlank { old.name }
        // THE SERIES IS NOW ALWAYS CARRIED, because the batched quote endpoint does not
        // return one at all - see MarketData.quotes. `refreshSparklines` is what replaces it,
        // on a five-minute clock rather than a fifteen-second one.
        //
        // The right-hand edge is kept current by replacing the LAST point with the live price
        // while the market is open. Without that the chart visibly disagrees with the big
        // number above it for up to five minutes. Replacing rather than appending is what
        // keeps the series from growing by a point every fifteen seconds - the last point is
        // either a candle at most five minutes old or a live price written by an earlier tick,
        // and in both cases the live price is the better value for it.
        val carried = fresh.spark.ifEmpty { old.spark }
        val spark = if (fresh.spark.isEmpty() && carried.isNotEmpty() &&
            fresh.marketState == "OPEN" && fresh.price > 0 && carried.last() != fresh.price
        ) carried.dropLast(1) + fresh.price else carried
        return if (name == fresh.name && spark === fresh.spark) fresh
        else fresh.copy(name = name, spark = spark)
    }

    /**
     * Refresh the intraday candle series, on its own much slower clock.
     *
     * WHY IT IS SEPARATE NOW. The old quote request was a chart request: it returned the price
     * AND ~78 candles, so the series was re-downloaded for every symbol four times a minute to
     * redraw a 64dp line that had not visibly changed. Since the price comes from the batched
     * endpoint (one request for the whole portfolio) the series is the only reason left to
     * call the chart endpoint at all, and it does not need anything like that cadence.
     *
     * Five minutes matches the candle interval itself - asking more often than Yahoo produces
     * a new point cannot return anything new. On a 16-symbol portfolio this is 16 requests
     * every 5 minutes rather than 16 every 15 seconds: 192 an hour against 3,840.
     *
     * Only for symbols the user can actually SEE. A sparkline that is not on screen is not
     * worth a request, and the chart on a detail screen is covered because
     * [symbolsToQuote] returns that symbol while it is open.
     */
    private fun refreshSparklines(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val now = System.currentTimeMillis()
        val due = symbols.filter { now - (sparkAt[it] ?: 0L) > SPARK_REFRESH_MS }
        if (due.isEmpty()) return
        // Marked BEFORE the fetch, not after. Marking on completion lets the next tick - 15
        // seconds later - queue the same symbols again while the first pass is still in
        // flight, which is how a five-minute cadence quietly becomes a fifteen-second one.
        due.forEach { sparkAt[it] = now }
        fgScope.launch(Dispatchers.IO) {
            // UNMARKED IF THIS PASS DOES NOT FINISH. The marks above are deliberately set
            // BEFORE the fetch, so a tick fifteen seconds later cannot queue the same symbols
            // again - but that also means a cancelled pass leaves every symbol stamped "just
            // fetched" with no series to show for it, and suppresses the retry for a full
            // five minutes. Backgrounding a second after launch would have left the charts
            // blank on the screen the user came back to.
            var completed = false
            try {
            val gate = Semaphore(MAX_PARALLEL_REQUESTS)
            val fresh = due.map { sym ->
                async {
                    gate.withPermit {
                        sym to runCatching { MarketData.sparkline(sym) }.getOrNull()
                    }
                }
            }.awaitAll()
            // ROUND 58: UN-MARK EVERY SYMBOL THAT FAILED, NOT ONLY THE ALL-FAILED CASE.
            //
            // This used to un-mark only when the WHOLE pass came back empty. On a PARTIAL
            // failure - one symbol 404s, one host is mid-cooldown, one socket times out -
            // the symbols that failed kept a mark saying "just fetched", so their retry was
            // suppressed for the full five minutes while their neighbours drew fine. That is
            // TJ's "sometimes they don't load": not every chart, just some of them, and not
            // for long enough to look like an outage.
            //
            // A mark means "we hold a series this recent". A symbol with no series has no
            // business holding one.
            fresh.forEach { (sym, v) -> if (v == null) sparkAt.remove(sym) }
            val landed = fresh.mapNotNull { (s, v) -> if (v == null) null else s to v }
            if (landed.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val updated = HashMap(_quotes.value)
                val written = ArrayList<Quote>(landed.size)
                for ((sym, series) in landed) {
                    val q = updated[sym]
                    if (q == null) {
                        // The quote pass failed for this symbol but the series arrived. Do
                        // not throw the work away and then sit out the five-minute window:
                        // un-mark it so the next tick, which will probably have a quote,
                        // picks the series up again.
                        sparkAt.remove(sym)
                        continue
                    }
                    val merged = q.copy(spark = series)
                    updated[sym] = merged
                    written.add(merged)
                }
                _quotes.value = updated
                if (written.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheQuotes(written) }
                    }
                }
            }
            completed = true
            } finally {
                if (!completed) due.forEach { sparkAt.remove(it) }
            }
        }
    }

    /** Every symbol the app tracks - held or watched. The old polling set. */
    private fun allTrackedSymbols(): List<String> {
        val fromRows = _ui.value.rows.map { it.symbol }
        return if (fromRows.isNotEmpty()) fromRows
        else (Ledger.positions(db.allTxns(), db.overrides(), costMethod())
            .filter { it.shares > 1e-9 }.map { it.symbol } + db.watchlist()).distinct()
    }

    /**
     * WHICH SYMBOLS THE AUTOMATIC TICK ACTUALLY NEEDS - the largest remaining cut to the
     * app's request rate, and the one that costs the user nothing.
     *
     * THE PROBLEM, MEASURED. Every tick fetched EVERY tracked symbol regardless of what was
     * on screen: one Yahoo chart request per symbol, so a 20-symbol portfolio at the default
     * 15 seconds is 80 requests a minute - 4,800 an hour - sustained for as long as the app
     * is open. Yahoo's own guidance is that heavy or parallel request patterns trigger rate
     * limiting, and it is the RATE that gets a client throttled, not the daily total. The
     * v3.3 lifecycle gate already stops all of this when the app is not on screen; this is
     * about what happens while it is.
     *
     * And a large share of it was for prices nobody could see. Sitting on the Activity tab
     * reading transactions, or on Settings, or on Advice, still cost the full 80 a minute -
     * none of those screens shows a live price. Looking at one stock's detail screen cost 20
     * requests to update the one number on screen.
     *
     * SO THE TICK NOW FOLLOWS THE EYE:
     *   detail screen  -> that one symbol
     *   portfolio      -> the holdings (what the header and the rows are drawn from)
     *   watchlist      -> the watched symbols
     *   search         -> nothing; results carry their own quotes
     *   activity / advice / settings -> nothing at all, the loop idles
     *
     * Anything not being polled is refreshed ONCE the moment it comes into view
     * ([setVisibleScope] below), so nothing is ever stale on arrival - a single small burst
     * on a deliberate navigation, in place of a permanent one.
     *
     * NOT A FRESHNESS COMPROMISE. Every symbol on screen still updates at exactly the
     * interval TJ chose. What stops is fetching prices that are not being displayed.
     */
    private fun symbolsToQuote(): List<String> =
        VisibleScope.symbolsFor(visibleScope, _ui.value.rows, ::allTrackedSymbols)

    /**
     * True when the market is shut AND the app already holds a price from after it shut.
     *
     * THE 3 A.M. SUNDAY PROBLEM. `MarketClock` floors the closed-market interval at 15
     * minutes, which was framed as the polite setting - but 15 minutes is still 96 requests
     * overnight, every night, for a number that provably cannot change until Monday. The
     * floor answers "how often", when the right answer at 3 a.m. on a Sunday is "not at all".
     *
     * The test is deliberately about the DATA rather than the clock: the app is holding a
     * quote that was fetched after the session ended, so the next fetch would return the same
     * figure. The moment that stops being true - a new session opens, extended hours begin,
     * the user pulls to refresh (which does not come through here), or a symbol is added and
     * has no quote at all - polling resumes on its own with no special case anywhere.
     *
     * Only the AUTOMATIC path consults this. Every deliberate user action still refreshes.
     */
    private fun pricesAreFinal(): Boolean {
        if (com.tj.portfolio.net.MarketClock.phase() != com.tj.portfolio.net.MarketClock.Phase.CLOSED) {
            return false
        }
        val wanted = symbolsToQuote()
        if (wanted.isEmpty()) return false
        val quotes = _quotes.value
        // Any symbol with no quote at all is a reason to poll - that is the case where the
        // user has just added something and is waiting to see a price appear.
        val held = wanted.map { quotes[it] ?: return false }
        // `updated` is when THIS APP last fetched, which is the only stamp that answers
        // "would another request tell us anything new". A provider's own exchange timestamp
        // would not: it is old by definition once the session has ended.
        val oldest = held.minOfOrNull { it.updated } ?: return false
        if (oldest <= 0L) return false
        return com.tj.portfolio.net.MarketClock.phase(oldest) ==
            com.tj.portfolio.net.MarketClock.Phase.CLOSED &&
            System.currentTimeMillis() - oldest < CLOSED_MARKET_MAX_AGE_MS
    }

    /**
     * The UI reporting which prices are on screen.
     *
     * Called from composition, so it must be cheap and idempotent - it is invoked on every
     * recomposition that changes the navigation state, not only on real changes, and the
     * equality check at the top is what makes that free.
     *
     * The catch-up refresh is the other half of the bargain made in [symbolsToQuote]: symbols
     * that were not being polled are brought up to date the instant they come into view, so
     * arriving at a screen never shows a stale price. That is ONE small request set on a
     * deliberate navigation, replacing a permanent background rate.
     *
     * The same grace window used on resume applies here, so flicking between the portfolio
     * and watchlist tabs cannot be turned into a request generator by tapping.
     */
    /**
     * The UI reporting whether a headline screen is on show. See [newsVisible].
     *
     * Cheap and idempotent, because it is called from composition on every navigation change.
     */
    fun setNewsVisible(visible: Boolean) {
        // STAMPED WHEN IT STOPS BEING VISIBLE, not when it starts. Stamping on the way IN
        // makes the grace window measure how long ago the user ARRIVED, so someone who reads
        // the Feed for ten minutes and then switches tabs has no grace at all - the window
        // has already expired at the exact moment it is needed, and a pass in flight abandons
        // its per-symbol loop half-done. Stamping on the way OUT measures what the window is
        // actually about: how long ago the user stopped looking.
        if (!visible && newsVisible) newsVisibleAt = System.currentTimeMillis()
        newsVisible = visible
    }

    fun setVisibleScope(scope: VisibleScope) {
        if (visibleScope == scope) return
        visibleScope = scope
        if (!foreground || scope == VisibleScope.None) return
        // Opening one stock costs exactly ONE request, and a stale price on a screen the user
        // just deliberately opened is precisely what this whole change must not cause - so a
        // detail screen is never debounced. Only the list scopes, which are many requests and
        // are what rapid tab-tapping could otherwise turn into a request generator.
        if (scope !is VisibleScope.Detail) {
            val now = System.currentTimeMillis()
            if (now - lastScopeRefresh < RESUME_REFRESH_GRACE_MS) return
            lastScopeRefresh = now
        }
        refresh()
    }
    /**
     * Called from the Activity's lifecycle: poll only while the app is visible.
     *
     * CANCELLING THE LOOP WAS NOT ENOUGH. `autoJob` was the only thing stopped here, but the
     * loop's job is only to *start* work - `refresh()` and `refreshFeed()` each launch their
     * own coroutine in `viewModelScope`, which outlives the Activity being stopped. So a tick
     * that fired a moment before the user left the app carried on: on a twenty-symbol
     * portfolio a feed pass is the market-wide pull plus a per-symbol news fetch for every
     * holding plus an EDGAR call for each one - dozens of requests still in flight, with
     * their answers parsed and written into state that nothing is on screen to draw.
     * `refreshFeed` is the expensive one and is now cancelled outright; `refresh()` is a
     * single bounded wave of quote requests whose results are worth keeping for the return,
     * so it is left to finish.
     *
     * The DEBOUNCE on the way back in matters as much. `setForeground(true)` fired a full
     * refresh unconditionally, so the notification shade, a permission dialog or an incoming
     * call each cost a complete round of quote requests to every provider. That is a burst
     * shape - many parallel requests, repeatedly, triggered by something the user did not
     * think of as using the app - and burst shape is what gets a client throttled.
     */
    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (active) {
            // A NEW SCOPE FIRST. The old one was cancelled on the way out and a cancelled Job
            // rejects every launch silently - without this, coming back to the app would find
            // every on-demand fetch a no-op and the screens would sit empty forever.
            fgScope = newForegroundScope()
            // Put the headlines back before anything else - a trim while backgrounded may
            // have released them, and the user should never see an empty Feed tab on return.
            restoreFromCache()
            val away = System.currentTimeMillis() - wentBackgroundAt
            // Coming back to the app should show current prices immediately, not in 15s -
            // but a two-second glance at the notification shade is not "coming back".
            if (wentBackgroundAt == 0L || away > RESUME_REFRESH_GRACE_MS) refresh()
            startAuto()
        } else {
            wentBackgroundAt = System.currentTimeMillis()
            autoJob?.cancel()
            autoJob = null
            feedJob?.cancel()
            feedJob = null
            insiderJob?.cancel()
            insiderJob = null
            // EVERYTHING ELSE THE SCREEN ASKED FOR, IN ONE CANCELLATION. See [fgScope] for
            // the list of what this reaches and what it deliberately does not.
            fgScope.coroutineContext[Job]?.cancel()
            researchJob = null
            enrichJob = null
            searchJob = null
        }
    }

    /**
     * A fresh foreground scope, because a cancelled `Job` stays cancelled forever.
     *
     * `SupervisorJob` parented to `viewModelScope`'s job, so clearing the ViewModel still
     * takes it down, and one failed fetch cannot cancel its siblings.
     */
    private fun newForegroundScope(): CoroutineScope =
        viewModelScope + SupervisorJob(viewModelScope.coroutineContext[Job])

    /**
     * The polling loop, which now decides its own pace on every tick instead of running at
     * one fixed speed forever.
     *
     * BEFORE: the user's interval (15s by default) applied around the clock. Roughly 115,000
     * quote requests a day for a twenty-symbol portfolio, the large majority of them
     * overnight and at weekends, asking repeatedly for prices that cannot change until the
     * next session. That is the traffic pattern that gets a free feed to start refusing you.
     *
     * NOW the interval is re-derived each pass from [MarketClock]: the chosen interval during
     * market hours, at least a minute in pre/post, and at most once every fifteen minutes
     * when the market is shut. Holidays are covered from the other side - if the exchange
     * timestamps stop advancing during nominal market hours, the tape is treated as closed.
     * On a normal weekday that is around a 70% cut, and over a weekend closer to 98%.
     */
    private fun startAuto() {
        autoJob?.cancel()
        if (refreshSecs() <= 0 || !foreground) return
        autoJob = viewModelScope.launch {
            var sinceFeedRefresh = 0
            var sinceFilings = 0
            while (true) {
                val secs = currentQuoteIntervalSecs()
                if (secs <= 0) break                       // auto-refresh switched off
                delay(secs * 1000L)
                if (!foreground) break
                // Belt and braces. The spinner is derived from the two loading flags and the
                // invariant holds by construction now, but a stranded one is invisible to
                // every test and infuriating in the hand - it shipped once - so the loop also
                // re-derives it on every tick. Costs one comparison.
                syncManualIndicator()
                // Nothing with a live price is on screen (Activity, Advice, Settings, the
                // reader). Skip the whole pass rather than fetching, repricing and throwing
                // the result away - the feed cadence below still runs, because headlines are
                // read on those screens even when prices are not.
                if (visibleScope != VisibleScope.None && !pricesAreFinal()) refresh()

                sinceFeedRefresh += secs
                sinceFilings += secs
                if (sinceFeedRefresh >= MarketClock.feedIntervalSecs()) {
                    sinceFeedRefresh = 0
                    // refreshFeed() pulls the same headlines and writes them into the
                    // per-symbol news map too, so calling refreshAllNews() here as well
                    // would fetch everything twice. Insider filings are legally up to two
                    // business days behind, so they ride a much slower cadence.
                    val withFilings = sinceFilings >= INSIDER_REFRESH_SECS
                    if (withFilings) sinceFilings = 0
                    refreshFeed(includeInsider = withFilings)
                }
            }
        }
    }

    /**
     * The interval to use right now: the market phase, plus a holiday check the calendar
     * cannot do. If every quote's exchange timestamp is more than [STALE_TAPE_MS] old while
     * the clock says the market is open, nothing is trading - so poll at the closed rate.
     */
    private fun currentQuoteIntervalSecs(): Int {
        val user = refreshSecs()
        val base = MarketClock.quoteIntervalSecs(user)
        if (base <= 0) return 0
        if (MarketClock.phase() != MarketClock.Phase.OPEN) return base
        val newestPrint = _quotes.value.values.maxOfOrNull { it.quoteTime } ?: 0L
        val tapeIsDead = newestPrint > 0 &&
            System.currentTimeMillis() - newestPrint > STALE_TAPE_MS
        return if (tapeIsDead) maxOf(user, 900) else base
    }

    /** What the app is currently doing, in words, for the Settings screen. */
    fun refreshStatus(): String {
        val user = refreshSecs()
        if (user <= 0) return "Auto-refresh is off - pull down to update prices."
        val secs = currentQuoteIntervalSecs()
        val every = if (secs >= 60) "${secs / 60} min" else "${secs}s"
        val phase = MarketClock.label()
        return if (secs == user) "$phase - updating every $every."
        else "$phase - updating every $every instead of ${user}s to save data and " +
            "stay well inside what the free feeds allow."
    }

    // `refreshAllNews()` used to live here: a full per-symbol news pass with no freshness
    // check at all. It had NO CALLERS anywhere in the tree - the comment in `startAuto` says
    // it was taken out of the polling loop because `refreshFeed` already pulls the same
    // headlines, and the function itself was left behind. Deleted in Round 56; a public
    // duplicate-fetch path with no TTL is a trap for whoever wires it up next.

    // ---------------------------------------------------------------- news


    /**
     * Fold newly fetched headlines into what is already held, per symbol, newest first and
     * de-duplicated with News's own identity so the same story from two sources collapses.
     * Capped per symbol so a stock in the news all day cannot grow without bound.
     */
    private fun mergeNews(
        current: Map<String, List<NewsItem>>,
        incoming: Map<String, List<NewsItem>>
    ): Map<String, List<NewsItem>> {
        val out = HashMap(current)
        for ((sym, fresh) in incoming) {
            if (fresh.isEmpty()) continue
            val existing = out[sym].orEmpty()
            out[sym] = (fresh + existing)
                .distinctBy { News.dedupeKey(it) }
                .sortedByDescending { it.published }
                .take(MAX_NEWS_PER_SYMBOL)
        }
        return out
    }

    fun loadNews(symbol: String, force: Boolean = false) {
        // Two traps this guard has to avoid:
        //
        // 1. An EMPTY cached entry must not count as "already loaded". The original
        //    containsKey() check cached failure permanently: one fetch while offline and
        //    that stock showed "no news" for the rest of the session.
        // 2. A non-empty entry is not necessarily a GOOD one. The background feed refresh
        //    writes cheap single-source results into the same map, so simply checking for
        //    "not empty" meant that after the first background pass, reopening a stock never
        //    pulled the other sources again - the multi-source view was a one-time thing.
        //
        // So the short-circuit is on a recent DEEP fetch specifically, not on the map.
        val lastDeep = deepNewsAt[symbol] ?: 0L
        val fresh = System.currentTimeMillis() - lastDeep < DEEP_NEWS_TTL_MS
        if (!force && fresh && _news.value[symbol]?.isNotEmpty() == true) return
        if (_newsLoading.value.contains(symbol)) return
        fgScope.launch {
            _newsLoading.value = _newsLoading.value + symbol
            try {
                // SHOW THE SAVED HEADLINES FIRST. Opening a stock used to sit on an empty
                // list for as long as three or four feeds took to answer, and after a memory
                // trim there was nothing to show at all. The cache paints instantly and the
                // network result merges over it, so the screen is never blank and a refresh
                // adds rather than replaces - the same rule the Feed tab now follows.
                if (_news.value[symbol].isNullOrEmpty()) {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { db.cachedNewsFor(symbol) }.getOrDefault(emptyList())
                    }
                    if (saved.isNotEmpty() && _news.value[symbol].isNullOrEmpty()) {
                        _news.value = _news.value + (symbol to saved)
                    }
                }
                val name = _quotes.value[symbol]?.name ?: ""
                // deep = every source at once, merged. This is one stock the user has
                // actually opened, so three or four requests is the right trade; the bulk
                // feed refresh still uses the cheap cascade over every symbol.
                val items = withContext(Dispatchers.IO) {
                    runCatching { News.forSymbol(symbol, name, finnhubKey(), deep = true) }
                        .getOrDefault(emptyList())
                }
                if (items.isNotEmpty()) {
                    // MERGED with what is already shown, not assigned over it - the same
                    // additive rule as the Feed tab. Assigning here threw away every story
                    // that had scrolled out of the sources' current windows, so a stock's
                    // news list quietly got shorter the longer the app was used.
                    val merged = (items + _news.value[symbol].orEmpty())
                        .distinctBy { News.dedupeKey(it) }
                        .sortedByDescending { it.published }
                        .take(MAX_SYMBOL_NEWS)
                    _news.value = _news.value + (symbol to merged)
                    deepNewsAt[symbol] = System.currentTimeMillis()
                    // Written through so the next open starts from these rather than from
                    // nothing, and so the month's retention applies to them too.
                    val owned = _ui.value.rows.any { it.symbol == symbol && !it.watchOnly }
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            db.cacheNews(
                                items.map {
                                    com.tj.portfolio.data.FeedItem(
                                        kind = com.tj.portfolio.data.FeedItem.NEWS,
                                        symbol = symbol, title = it.title, url = it.url,
                                        source = it.source, published = it.published,
                                        owned = owned
                                    )
                                },
                                summaries = emptyMap()
                            )
                        }
                    }
                }
            } finally {
                // must always clear, or this symbol is locked out of every later attempt
                _newsLoading.value = _newsLoading.value - symbol
            }
        }
    }

    // ------------------------------------------------------- transactions

    fun addTxn(
        type: String, symbol: String?, qty: Double, price: Double,
        amount: Double, fees: Double, date: Long, note: String?
    ) {
        val signed = Txn.cashEffect(type, qty, price, amount, fees)
        db.insertTxn(
            Txn(
                type = type, symbol = symbol?.uppercase()?.ifBlank { null },
                quantity = qty, price = price, amount = signed,
                fees = fees, date = date, note = note, source = "MANUAL"
            )
        )
        recompute()
        refresh()
    }

    /** Insert an already-built Txn (its amount is already signed by the editor). */
    fun addTxnRecord(t: Txn) {
        db.insertTxn(t)
        recompute()
        refresh()
    }

    fun updateTxn(t: Txn) { db.updateTxn(t); recompute(); refresh() }

    fun deleteTxn(id: Long) { db.deleteTxn(id); recompute() }

    /** Wipe a symbol entirely: every transaction plus any manual override. */
    fun deleteSymbol(symbol: String): Int {
        val sym = symbol.uppercase()
        val n = db.deleteTxnsForSymbol(sym)
        db.clearOverride(sym)
        db.removeWatch(sym)
        recompute()
        return n
    }

    // ------------------------------------------------------------------ fees

    data class FeeAudit(
        val totalRecorded: Double,
        val buysWithFees: List<Txn>,
        val buyFeeTotal: Double,
        /**
         * BUY/SELL rows on file with no share count.
         *
         * The ledger skips a trade with no quantity - there is no position to build from it -
         * but `cash()` still counts its amount, so the money moves and nothing on any screen
         * accounts for it. It lands silently in "Since you started" as an unexplained gain or
         * loss. Found by property testing: a single 0-share $500 BUY turns a flat portfolio
         * into a $500 loss with no holding, no realized figure and no row to point at.
         *
         * Both import paths now refuse these, and the transaction editor has blocked them
         * since v3.5 - but a row from an older build or a restored backup is still on file,
         * so the audit says so rather than leaving the total quietly wrong.
         */
        val quantityless: List<Txn> = emptyList(),
        val quantitylessCash: Double = 0.0
    ) {
        val hasProblem: Boolean get() = buysWithFees.isNotEmpty()
        val hasGhostRows: Boolean get() = quantityless.isNotEmpty()
    }

    /**
     * Compare the fees on file against Ally's published schedule.
     *
     * Reports only what it is sure about: a commission recorded against a stock or ETF BUY,
     * which Ally does not charge. Sell-side amounts are deliberately left alone - the exact
     * cents depend on the SEC rate in force on the day, and that rate was $0.00 for the
     * first part of 2026, so a "wrong-looking" sell fee may be perfectly correct.
     */
    fun auditFees(): FeeAudit {
        val txns = cachedTxns.ifEmpty { db.allTxns() }
        val bad = txns.filter {
            com.tj.portfolio.domain.Fees.buyFeeLooksWrong(it.type, it.quantity, it.price, it.fees)
        }
        val ghosts = txns.filter {
            (it.type == TxnType.BUY || it.type == TxnType.SELL) &&
                kotlin.math.abs(it.quantity) < 1e-9 &&
                kotlin.math.abs(it.amount) > 0.005
        }
        return FeeAudit(
            totalRecorded = txns.sumOf { it.fees },
            buysWithFees = bad,
            buyFeeTotal = bad.sumOf { it.fees },
            quantityless = ghosts,
            quantitylessCash = ghosts.sumOf { it.amount }
        )
    }

    /**
     * Clear those buy-side commissions and re-derive each row's cash effect from its own
     * price and quantity, so removing the fee also corrects the amount it was baked into.
     */
    fun clearIncorrectBuyFees(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                val bad = auditFees().buysWithFees
                bad.forEach { t ->
                    val fixed = t.copy(
                        fees = 0.0,
                        amount = Txn.cashEffect(t.type, t.quantity, t.price, t.amount, 0.0)
                    )
                    db.updateTxn(fixed)
                }
                bad.size
            }
            if (n > 0) { recompute(); refresh() }
            onDone(n)
        }
    }

    fun txnsFor(symbol: String): List<Txn> =
        db.allTxns().filter { it.symbol == symbol.uppercase() }
            .sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.id })

    fun addWatch(symbol: String) {
        val s = symbol.trim().uppercase()
        if (s.isBlank()) return
        db.addWatch(s)
        recompute()
        refresh()
    }

    fun removeWatch(symbol: String) { db.removeWatch(symbol); recompute() }

    fun setOverride(symbol: String, avgCost: Double?, shares: Double?) {
        if (avgCost == null && shares == null) db.clearOverride(symbol)
        else db.setOverride(Override(symbol, avgCost, shares))
        recompute()
    }

    fun overrideFor(symbol: String): Override? = db.overrides()[symbol.uppercase()]

    // ------------------------------------------------ screenshot import

    fun importScreenshots(uris: List<Uri>) {
        val key = claudeKey()
        if (key.isBlank()) {
            _importResult.value = ExtractResult(emptyList(), "", "Add your Claude API key in Settings first.")
            return
        }
        viewModelScope.launch {
            _importing.value = true
            _importResult.value = null
            val images = withContext(Dispatchers.IO) { uris.mapNotNull { readImage(it) } }
            if (images.isEmpty()) {
                _importing.value = false
                _importResult.value = ExtractResult(emptyList(), "", "Couldn't read the selected images.")
                return@launch
            }
            // Never extract from a partial set in silence: a screenshot that was dropped is a
            // trade that will not appear, and nothing else on screen would ever say so.
            val dropped = uris.size - images.size
            if (dropped > 0) {
                _importing.value = false
                _importResult.value = ExtractResult(
                    emptyList(), "",
                    "$dropped of ${uris.size} image(s) couldn't be read - most likely too " +
                        "large even after resizing. Import the rest on their own, or retake " +
                        "those screenshots, so nothing is missed."
                )
                return@launch
            }
            val chosen = ensureModel()
            val res = withContext(Dispatchers.IO) {
                runCatching { Claude.extractTransactions(key, chosen, images, existingDigest()) }
                    .getOrElse { ExtractResult(emptyList(), "", it.message ?: "Extraction failed") }
            }
            _importResult.value = res
            _importing.value = false
        }
    }

    /**
     * Read one screenshot, RE-ENCODING it rather than giving up when it is too big.
     *
     * This used to `return null` on anything over the size limit, and the caller could only
     * say "Couldn't read the selected images" - and only when EVERY image failed. Pick three
     * screenshots where one is a high-resolution PNG and the app would quietly extract the
     * other two and never mention the one it dropped, which on an import is a missing trade
     * the user has no reason to look for.
     *
     * A phone screenshot of a brokerage statement is text on a flat background, so JPEG at
     * 85 is visually indistinguishable and a fraction of the size; downscaling the long edge
     * to 2000px keeps the digits legible for OCR. Only a file that survives all that and is
     * still too large is refused - and now the caller says so by name.
     */
    private fun readImage(uri: Uri): Pair<String, String>? {
        val ctx = getApplication<Application>()
        return try {
            val type = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val media = if (type in setOf("image/png", "image/jpeg", "image/gif", "image/webp"))
                type else "image/jpeg"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.size <= MAX_IMAGE_BYTES) {
                return media to Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            val shrunk = recompress(bytes) ?: return null
            "image/jpeg" to Base64.encodeToString(shrunk, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    /** Decode, scale the long edge down to 2000px, re-encode as JPEG. Null if still too big. */
    private fun recompress(bytes: ByteArray): ByteArray? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val longEdge = maxOf(opts.outWidth, opts.outHeight)
        // inSampleSize must be a power of two; anything else is rounded down by the decoder,
        // and decoding at a sample size avoids ever holding the full-size bitmap in memory.
        var sample = 1
        while (longEdge / sample > 2000) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
        if (bmp == null) null else {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray().takeIf { it.size <= MAX_IMAGE_BYTES }
        }
    } catch (e: Throwable) { null }   // OutOfMemoryError included - a refusal beats a crash

    /** True if this exact transaction is already on file (same type, symbol, date, size). */
    /**
     * The importer stamps a row it could not date with TODAY and flags it in the note. Those
     * rows need the date-independent check as well, or the same undated screenshot imported
     * on two different days lands twice. Both the "ALREADY HAVE" badge in the review dialog
     * and the skip in [commitImportAsync] go through here, so they can never disagree.
     */
    private fun duplicateOf(t: Txn): Long? {
        db.findDuplicateId(t)?.let { return it }
        val dateWasGuessed = t.note?.contains("date estimated", true) == true
        return if (dateWasGuessed) db.findDuplicateIdAnyDate(t) else null
    }

    /**
     * Duplicate flags for a whole import batch, off the main thread.
     *
     * The review dialog used to run this check per row from inside composition, so opening
     * the dialog on a 111-row import ran 111 SQLite queries on the UI thread before the first
     * row could be drawn. It calls the SAME per-row [duplicateOf] the commit does - the badge
     * in the dialog and the skip in [commitImportAsync] must never disagree about what counts
     * as a duplicate, which is why this is not a faster reimplementation of the rules.
     */
    suspend fun duplicateFlags(list: List<Txn>): List<Boolean> = withContext(Dispatchers.IO) {
        list.map { duplicateOf(it) != null }
    }

    /**
     * Commit the reviewed set: off the main thread, as ONE database transaction.
     *
     * Exact duplicates are skipped unless [force] is set, and the import is logged so the
     * Activity tab can say where to resume from.
     *
     * The previous version inserted row by row straight from the dialog button, and every
     * bare insert is its own implicit SQLite transaction with its own commit - so a 111-row
     * import was 111 commits plus a duplicate query each, all on the UI thread. The app
     * simply stopped responding for as long as it took. Same rules, same result, one commit.
     *
     * There is deliberately no blocking variant left: this runs from a button, and a
     * main-thread version sitting here is an invitation to call it from one again.
     */
    fun commitImportAsync(
        list: List<Txn>,
        source: String = "SCREENSHOT",
        force: Boolean = false,
        onDone: (Int) -> Unit
    ) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                var n = 0
                var skipped = 0
                var minD = Long.MAX_VALUE
                var maxD = 0L
                val seen = HashSet<String>()
                val database = db.writableDatabase
                database.beginTransaction()
                try {
                    for (t in list) {
                        val fingerprint = listOf(
                            t.type, (t.symbol ?: ""), Fmt.iso(t.date),
                            Math.round(kotlin.math.abs(t.quantity) * 10000),
                            Math.round(kotlin.math.abs(t.amount) * 100)
                        ).joinToString("|")
                        if (!seen.add(fingerprint)) { skipped++; continue }
                        if (!force && duplicateOf(t) != null) { skipped++; continue }
                        db.insertTxn(t); n++
                        if (t.date in 1 until minD) minD = t.date
                        if (t.date > maxD) maxD = t.date
                    }
                    db.recordImport(
                        n, skipped, if (minD == Long.MAX_VALUE) 0L else minD, maxD, source
                    )
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                n
            }
            if (res > 0) autoBackupIfDue(force = true)
            _lastImport.value = withContext(Dispatchers.IO) { db.lastImport() }
            _importResult.value = null
            recompute()
            refresh()
            onDone(res)
        }
    }

    fun clearImport() { _importResult.value = null }

    // -------------------------------------------------------------- advice

    private fun loadCachedAdvice() = viewModelScope.launch(Dispatchers.IO) {
        val cached = runCatching { db.get(Keys.ADVICE_CACHE) }.getOrDefault("")
        if (cached.isBlank()) return@launch
        runCatching {
            val a = adviceFromJson(JSONObject(cached))
            // v5.2: a build before this one could import the prompt file's own example and
            // store the placeholder text as the portfolio review. That is already sitting in
            // the database on any device that hit the bug, so it is thrown out on load
            // rather than left on screen until the next successful analysis.
            if (com.tj.portfolio.net.ClaudeBridge.isPlaceholderAdvice(
                    a.summary, a.risks, a.stocks.map { it.reasoning }
                )
            ) {
                db.set(Keys.ADVICE_CACHE, "")
            } else {
                withContext(Dispatchers.Main) { _advice.value = a }
            }
        }
    }

    fun requestAdvice() {
        val key = claudeKey()
        if (key.isBlank()) {
            _adviceError.value = "Add your Claude API key in Settings first."
            return
        }
        if (_adviceLoading.value) return
        viewModelScope.launch {
            _adviceLoading.value = true
            _adviceError.value = null
            val portfolioJson = portfolioJson()
            val headlines = headlinesBlock() + contextBlock()
            val chosen = ensureModel()
            var a = withContext(Dispatchers.IO) {
                runCatching { Claude.advice(key, chosen, portfolioJson, headlines, webSearch()) }
                    .getOrElse { Advice(error = it.message ?: "Request failed") }
            }
            // a retired model id is the one failure worth fixing automatically
            if (a.error?.contains("404") == true) {
                val list = withContext(Dispatchers.IO) {
                    runCatching { Claude.models(key) }.getOrDefault(emptyList())
                }
                if (list.isNotEmpty()) {
                    val fresh = Claude.preferredModel(list)
                    db.set(Keys.CLAUDE_MODEL, fresh)
                    _models.value = list
                    a = withContext(Dispatchers.IO) {
                        runCatching { Claude.advice(key, fresh, portfolioJson, headlines, webSearch()) }
                            .getOrElse { Advice(error = it.message ?: "Request failed") }
                    }
                }
            }
            if (a.error != null || (a.summary.isBlank() && a.stocks.isEmpty())) {
                _adviceError.value = a.error ?: "Claude returned nothing usable."
            } else {
                _advice.value = a
                _adviceError.value = null
                db.set(Keys.ADVICE_CACHE, adviceToJson(a).toString())
            }
            _adviceLoading.value = false
        }
    }

    /** Every number Claude needs, as compact JSON. */
    fun portfolioJson(): String {
        val s = _ui.value
        val t = s.totals
        val root = JSONObject()
        root.put("asOf", Fmt.day(System.currentTimeMillis()))
        if (t != null) {
            root.put("totals", JSONObject().apply {
                put("marketValue", r2(t.marketValue))
                put("cash", r2(t.cash))
                put("totalEquity", r2(t.totalEquity))
                put("netDeposits", r2(t.netDeposits))
                put("costBasis", r2(t.costBasis))
                put("realizedPL", r2(t.realized))
                put("unrealizedPL", r2(t.unrealized))
                put("totalPL", r2(t.totalGain))
                put("totalPLPct", r2(t.totalGainPct))
                put("dayPL", r2(t.dayGain))
                put("dayPLPct", r2(t.dayGainPct))
                put("dividends", r2(t.dividends))
            })
        }
        val arr = JSONArray()
        for (row in s.rows.filter { !it.watchOnly }) {
            arr.put(JSONObject().apply {
                put("symbol", row.symbol)
                if (row.name.isNotBlank()) put("name", row.name)
                put("shares", r2(row.shares))
                put("avgCost", r2(row.avgCost))
                put("price", r2(row.price))
                put("marketValue", r2(row.value))
                put("unrealizedPL", r2(row.totalPnl))
                put("unrealizedPct", r2(row.totalPct))
                put("dayPct", r2(row.dayPct))
                put("weightPct", if (t != null && t.marketValue > 0)
                    r2(row.value / t.marketValue * 100.0) else 0.0)
                val realized = s.positions.firstOrNull { it.symbol == row.symbol }?.realized ?: 0.0
                if (kotlin.math.abs(realized) > 0.01) put("realizedPL", r2(realized))
            })
        }
        root.put("holdings", arr)
        val closed = s.positions.filter { it.shares <= 1e-9 && kotlin.math.abs(it.realized) > 0.01 }
        if (closed.isNotEmpty()) {
            val c = JSONArray()
            closed.forEach { c.put(JSONObject().apply { put("symbol", it.symbol); put("realizedPL", r2(it.realized)) }) }
            root.put("closedPositions", c)
        }
        val watch = s.rows.filter { it.watchOnly }.map { it.symbol }
        if (watch.isNotEmpty()) root.put("watchlist", JSONArray(watch))
        return root.toString(1)
    }

    /**
     * Insider filings and social attention, folded into the advice request. An analyst
     * looking at a portfolio would want to know an executive just filed a sale, or that a
     * holding is suddenly the most-discussed ticker on r/wallstreetbets.
     */
    private fun contextBlock(): String {
        val sb = StringBuilder()
        val owned = _ui.value.rows.filter { !it.watchOnly }.map { it.symbol }.toSet()

        // REAL TRADES ONLY, and the plan status spelled out. This block used to be EDGAR's
        // boilerplate form name repeated twenty times - "Statement of changes in beneficial
        // ownership of securities" - which told the model precisely nothing while costing
        // real tokens. It now says who, which way, how much, and whether the trade was
        // pre-arranged, because a sale executing under a plan adopted months ago carries no
        // opinion about today and a model that cannot tell the difference will read one as
        // the other.
        val filings = _insiderFilings.value
            .filter { it.symbol in owned && it.isTrade }
            .take(25)
        if (filings.isNotEmpty()) {
            sb.append("\nRECENT SEC FORM 4 INSIDER TRADES on holdings - open-market purchases ")
            sb.append("and sales only, grants and option exercises excluded. Trades marked ")
            sb.append("[10b5-1 plan] were scheduled in advance and say nothing about the ")
            sb.append("insider's current view:\n")
            filings.forEach {
                sb.append("  - ").append(it.symbol).append(": ").append(it.headline())
                if (it.person.isNotBlank()) sb.append(" (").append(it.person).append(")")
                if (it.tradeDate > 0) sb.append(" on ").append(Fmt.day(it.tradeDate))
                if (it.planned) sb.append(" [10b5-1 plan]")
                if (it.exerciseAndSell) sb.append(" [shares came from an option exercise]")
                sb.append('\n')
            }
        }

        val trend = _trending.value.filter { it.symbol in owned }
        if (trend.isNotEmpty()) {
            sb.append("\nHOLDINGS CURRENTLY TRENDING ON r/wallstreetbets ")
            sb.append("(retail attention, not a fundamental signal):\n")
            trend.forEach {
                sb.append("  - ").append(it.symbol).append(": rank #").append(it.rank)
                if (it.activity > 0) sb.append(", ").append(it.activity).append(" mentions")
                if (it.sentiment.isNotBlank()) sb.append(", ").append(it.sentiment)
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun headlinesBlock(): String {
        val sb = StringBuilder()
        for ((sym, items) in _news.value) {
            if (items.isEmpty()) continue
            sb.append(sym).append(":\n")
            items.take(5).forEach {
                // A feed date that would not parse is 0, and Fmt.relative(0) renders as
                // "Jan 1" - so an unparseable-but-current headline was being handed to
                // Claude looking 56 years old, which is a reason to discount it. The UI
                // already guards this everywhere it prints a date; the prompt did not.
                sb.append("  - ")
                if (it.published > 0) sb.append(Fmt.relative(it.published)).append(" - ")
                sb.append(it.title).append('\n')
            }
        }
        return if (sb.isEmpty()) "(none loaded)" else sb.toString()
    }

    /**
     * Pull news for every holding so the advice request has fresh headlines.
     *
     * [onDone] MUST run whatever happens. The caller disables its button until it fires, so
     * a single throw on the way in - a database read, an empty portfolio - used to leave the
     * Advice tab stuck on "Gathering news..." with no way back except restarting the app.
     */
    fun preloadNewsForAdvice(onDone: () -> Unit) {
        fgScope.launch {
            try {
                // ONLY THE SYMBOLS WHOSE HEADLINES ARE ACTUALLY STALE (Round 56). This ran the
                // full cascade over every holding on EVERY tap of the advice button, with no
                // freshness check of any kind - 16-48 requests, repeated, for headlines the
                // feed pass had usually fetched minutes earlier. `loadNews` has had a TTL for
                // versions; this path never got one.
                val cutoff = System.currentTimeMillis() - ADVICE_NEWS_TTL_MS
                val syms = _ui.value.rows.filter { !it.watchOnly }.map { it.symbol }
                    .filter { (adviceNewsAt[it] ?: 0L) < cutoff }
                if (syms.isNotEmpty()) {
                    val fk = finnhubKey()
                    withContext(Dispatchers.IO) {
                        val gate = Semaphore(MAX_PARALLEL_REQUESTS)
                        syms.map { sym ->
                            async {
                                gate.withPermit {
                                    val name = _quotes.value[sym]?.name ?: ""
                                    sym to runCatching { News.forSymbol(sym, name, fk) }
                                        .getOrDefault(emptyList())
                                }
                            }
                        }.awaitAll()
                    }.let { fetched ->
                        val now = System.currentTimeMillis()
                        fetched.forEach { (sym, items) ->
                            // Marked only when something actually came back, so a failed
                            // fetch is retried rather than sitting out the whole window.
                            if (items.isNotEmpty()) adviceNewsAt[sym] = now
                        }
                        _news.value = mergeNews(_news.value, fetched.toMap())
                    }
                }
            } catch (e: Exception) {
                // headlines are a nice-to-have for the request, not a precondition
            } finally {
                onDone()
            }
        }
    }

    /**
     * Builds and writes the advice prompt file off the UI thread.
     *
     * This used to happen inside the button's own onClick: reading every transaction,
     * building the whole prompt string and writing it to Downloads, all on the main thread.
     * On a long history that is exactly the shape of an ANR.
     */
    fun writeAdvicePrompt(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    // ONE file, replaced each time. A new timestamped file per tap meant TJ's
                    // Downloads filled with near-identical prompts - and picking the wrong one
                    // in the file chooser is precisely the v5.2 import bug. One file cannot be
                    // confused with an older one.
                    val saved = com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(
                        getApplication(), ADVICE_PROMPT_FILE, advicePromptFile(), "text/markdown"
                    )
                    if (saved != null) "Saved to ${saved.display}"
                    else "Couldn't write to Downloads"
                }.getOrElse { "Couldn't build the prompt file: ${it.message}" }
            }
            onDone(msg)
        }
    }

    // ------------------------------------------------------------ live feed

    /**
     * Builds the Feed tab: headlines for everything followed, SEC Form 4 insider filings
     * for the holdings, and what r/wallstreetbets is talking about. All free sources,
     * polled in parallel - no backend and no websocket, which is what keeps this workable
     * inside Android's background limits.
     */
    /** Form 4 filings for one symbol, for the stock's own detail screen. */
    fun loadInsider(symbol: String, force: Boolean = false) {
        // Same trap loadNews had: an empty cached entry is a FAILED fetch, not an answer.
        // containsKey() treated one unreachable-EDGAR moment as final for the session.
        if (!force && _insider.value[symbol]?.isNotEmpty() == true) return
        // Two concurrent loads for the same symbol (open the screen, pull to refresh) used
        // to fetch EDGAR twice and race each other into the map.
        fgScope.launch {
            // ADDED INSIDE THE LAUNCH, not before it. Set outside, a call that lands while
            // the foreground scope is cancelled marks the symbol and never reaches the
            // `finally` that clears it - locking that symbol out of Form 4 loading for the
            // life of the ViewModel, with no recovery path. `loadNews` and `loadFundamentals`
            // both carry a comment about this exact trap; this one still had it.
            if (!insiderLoadingSet.add(symbol)) return@launch
            try {
                // Anything the month-long feed pass has already read is on screen instantly;
                // only what is missing goes to the network.
                val known = snapshotInsiderDocs()
                val fromStore = _insiderFilings.value.filter { it.symbol == symbol }
                if (fromStore.isNotEmpty() && !force) {
                    _insider.value = _insider.value + (symbol to fromStore)
                }
                val since = Fmt.iso(
                    System.currentTimeMillis() - Insider.WINDOW_DAYS * 86_400_000L
                )
                val items = withContext(Dispatchers.IO) {
                    runCatching {
                        Insider.forSymbol(symbol, known, secGate, since, insiderSkip)
                    }.getOrDefault(emptyList())
                }
                if (items.isEmpty()) return@launch
                rememberInsiderDocs(items)
                // De-duplicated by accession: EDGAR occasionally repeats an entry, and the
                // detail screen keys its rows by that number - a LazyColumn handed the same
                // key twice throws and takes the app down with it.
                _insider.value = _insider.value +
                    (symbol to items.distinctBy { it.accession }
                        .sortedByDescending { it.filedAt })
            } finally {
                insiderLoadingSet.remove(symbol)
                // This path can add to the never-parseable set too; without this its
                // discoveries were memory-only and died with the process.
                saveInsiderSkip()
            }
        }
    }

    /**
     * Every Form 4 on everything the user follows, for the last month, newest first.
     *
     * HELD AND WATCHED, not just held. TJ asked for "all insider trading from the last
     * month", and the stock you are thinking about buying is exactly the one where knowing
     * the CFO has been buying matters most. The old pass covered holdings only.
     *
     * COST. One listing request per symbol - EDGAR's own `datea` parameter does the date
     * filtering server-side, so a month comes back in about 8 KB instead of the 109 KB a
     * year takes - plus one request per filing not already in [insiderDocs]. On the second
     * pass that second number is usually zero. Everything shares one [MAX_PARALLEL_SEC]
     * semaphore so the listing and document requests cannot between them exceed the SEC's
     * published rate cap.
     */
    /**
     * Start the EDGAR pass ALONGSIDE the feed rather than inside it.
     *
     * It used to be the feed's third stage, awaited before `refreshFeed` returned. That was
     * fine when the stage was five filings per holding; it is not fine now that a cold start
     * can be a hundred documents. Awaited inline, `_feedLoading` would stay true for the
     * whole minute that takes - the progress bar never stopping, and, worse, `refreshFeed`'s
     * own re-entry guard turning every pull-to-refresh in that minute into a no-op. The two
     * passes have nothing to say to each other, so they no longer wait for each other.
     */
    private fun startInsiderRefresh(symbols: List<String>, owned: Set<String>) {
        if (_insiderLoading.value) return
        insiderJob = fgScope.launch { refreshInsiders(symbols, owned) }
    }

    private suspend fun refreshInsiders(symbols: List<String>, owned: Set<String>) {
        if (symbols.isEmpty()) return
        _insiderLoading.value = true
        try {
            val known = snapshotInsiderDocs()
            val filings = withContext(Dispatchers.IO) {
                runCatching {
                    Insider.forSymbols(symbols, known, secGate, insiderSkip)
                }.getOrDefault(emptyList())
            }
            // SAVED FIRST, WHATEVER ELSE HAPPENED. The pass may have discovered that several
            // documents will never parse - and the case where it discovers ONLY that is
            // exactly the case `filings` comes back empty, which used to return before the
            // save and throw the discovery away. That is the precise scenario the skip set
            // exists for, surviving in its worst form.
            saveInsiderSkip()
            // An empty result means EDGAR was unreachable, not that a month of filings
            // vanished overnight - keep what is on screen rather than blanking the tab.
            if (filings.isEmpty()) return
            rememberInsiderDocs(filings)
            publishInsiders(filings)
            // Real open-market trades also belong in the All and My-stocks lists, where a
            // headline-shaped row saying "CEO bought 40,000 shares" reads as news - because
            // it is. The machinery (grants, tax withholding, option exercises) stays in the
            // Insider tab, which is where someone has gone looking for it.
            val rows = filings.filter { it.isTrade }.map { it.asFeedItem(it.symbol in owned) }
            if (rows.isNotEmpty()) publishAndCache(rows + _feed.value, toCache = rows)
        } finally {
            _insiderLoading.value = false
        }
    }

    /**
     * Fill the Insider tab on demand, when the user opens it and it has nothing to show.
     *
     * NEEDED BECAUSE THE FEED'S OWN TRIGGER CANNOT COVER THIS. `FeedScreen` refreshes when
     * the headline list is empty, and filings ride a half-hour cadence inside that refresh -
     * so a launch that restored a screenful of cached headlines never calls it, and the
     * Insider tab could sit empty for up to half an hour with nothing explaining why. This is
     * the one path where the user's attention is the signal.
     */
    fun refreshInsidersIfEmpty() {
        if (_insiderLoading.value || _insiderFilings.value.isNotEmpty()) return
        val rows = _ui.value.rows
        if (rows.isEmpty()) return
        startInsiderRefresh(
            rows.map { it.symbol },
            rows.filter { !it.watchOnly }.map { it.symbol }.toSet()
        )
    }

    /** Sort, de-duplicate, bound, publish, and write through to the cache. */
    private fun publishInsiders(filings: List<com.tj.portfolio.data.InsiderFiling>) {
        val merged = (filings + _insiderFilings.value)
            .distinctBy { it.accession }
            .sortedByDescending { it.filedAt }
            .take(MAX_INSIDER_FILINGS)
        _insiderFilings.value = merged
        // Also fill the per-symbol map the detail screens read, so opening a stock that the
        // feed pass has already covered costs no request at all.
        //
        // MERGED, NOT ASSIGNED. `groupBy` alone would drop every symbol this pass did not
        // cover - which is exactly the stock the user searched for and opened without
        // following, the one case where the map was the only copy of that data. It would
        // have gone blank, silently, the moment a background refresh landed underneath the
        // screen showing it.
        _insider.value = _insider.value + merged.groupBy { it.symbol }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.set(Keys.INSIDER_CACHE, com.tj.portfolio.data.InsiderFiling.toJson(merged))
            }
        }
    }

    /**
     * Copied under lock before being handed to a coroutine that reads it from several
     * threads at once. [insiderDocs] is a plain HashMap on purpose - it is written only from
     * the two functions above - but a concurrent read of a HashMap being resized returns
     * nonsense rather than throwing, which is the worst kind of bug to go looking for.
     */
    private fun snapshotInsiderDocs(): Map<String, com.tj.portfolio.data.InsiderFiling> =
        synchronized(insiderDocs) { HashMap(insiderDocs) }

    private fun rememberInsiderDocs(items: List<com.tj.portfolio.data.InsiderFiling>) {
        synchronized(insiderDocs) {
            // Bounded and cleared wholesale on overflow, the same cheap policy `storyKeys`
            // uses: the working set is one month of one portfolio, and a filing that falls
            // out costs one re-download the next time it is asked for.
            if (insiderDocs.size > MAX_INSIDER_FILINGS * 2) insiderDocs.clear()
            for (f in items) insiderDocs[f.accession] = f
        }
    }

    /** Write the never-parseable accessions through, so a relaunch does not re-fetch them. */
    private fun saveInsiderSkip() {
        val snapshot = synchronized(insiderSkip) {
            if (insiderSkip.size > MAX_INSIDER_SKIP) {
                // Cleared wholesale rather than trimmed: there is no useful recency order in a
                // set of accession numbers, and the cost of rebuilding it is one pass.
                insiderSkip.clear()
            }
            insiderSkip.toList()
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.set(Keys.INSIDER_SKIP, snapshot.joinToString(",")) }
        }
    }

    /** Put the Insider tab back from disk on launch, before any network pass runs. */
    private fun loadCachedInsider() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.get(Keys.INSIDER_SKIP).split(',').filter { it.isNotBlank() }
            }.getOrDefault(emptyList()).let { skips ->
                if (skips.isNotEmpty()) synchronized(insiderSkip) { insiderSkip.addAll(skips) }
            }
            val raw = runCatching { db.get(Keys.INSIDER_CACHE) }.getOrDefault("")
            if (raw.isBlank()) return@launch
            val cutoff = System.currentTimeMillis() - Insider.WINDOW_DAYS * 86_400_000L
            val list = com.tj.portfolio.data.InsiderFiling.fromJson(raw)
                .filter { it.filedAt >= cutoff }
                .sortedByDescending { it.filedAt }
            if (list.isEmpty()) return@launch
            rememberInsiderDocs(list)
            withContext(Dispatchers.Main) {
                if (_insiderFilings.value.isEmpty()) {
                    _insiderFilings.value = list
                    _insider.value = list.groupBy { it.symbol }
                }
            }
        }
    }

    // ------------------------------------------- fundamentals / analyst ratings

    /**
     * Merge a provider or cache result into whatever is already on screen for a symbol.
     *
     * NEVER ASSIGNS. The two fetches (numbers, and analyst history) land at different times
     * and carry disjoint parts of the same object, so assigning the second over the first
     * would blank the Stats tab the moment the Analysts tab finished loading. The newer of
     * the two takes precedence on any key they share; everything else is filled in.
     */
    private fun mergeFundamentals(symbol: String, incoming: com.tj.portfolio.data.Fundamentals) {
        val existing = _fundamentals.value[symbol]
        val merged = when {
            existing == null -> incoming
            incoming.fetched >= existing.fetched ->
                com.tj.portfolio.data.Fundamentals.merge(incoming, existing)
            else -> com.tj.portfolio.data.Fundamentals.merge(existing, incoming)
        }
        _fundamentals.value = _fundamentals.value + (symbol to merged)
    }

    /**
     * The valuation, dividend, growth, margin and balance-sheet numbers for one stock.
     *
     * Ordering matters here and is the same rule the headline cache follows: paint from
     * disk FIRST so the screen is never blank, then go to the network, then merge. Opening
     * a stock you looked at this morning shows every number instantly and quietly updates.
     *
     * Deliberately NOT part of the 15-second price loop. These figures change once a
     * quarter; polling them on the quote cadence would be roughly a thousand pointless
     * requests an hour to a provider that has no idea this is one person's phone.
     */
    // ================================================================ FUND HOLDINGS

    /**
     * What this fund holds. Cheap and idempotent - safe to call on every screen open.
     *
     * Disk first, exactly like [loadChart] and [loadFundamentals]: the tab paints from
     * SQLite before any request, so reopening an ETF costs nothing, and a fund's register is
     * republished daily at most so the 12-hour TTL is already more often than the data moves.
     *
     * On `fgScope`, because the answer is only useful while the tab is up; the disk write
     * goes on `viewModelScope` so a series already paid for survives the user leaving.
     */
    fun loadHoldings(symbol: String, force: Boolean = false) {
        val sym = symbol.uppercase()
        if (_holdingsLoading.value.contains(sym)) return
        val held = _holdings.value[sym]
        if (!force && held != null && !held.stale()) return

        fgScope.launch {
            _holdingsLoading.value = _holdingsLoading.value + sym
            try {
                if (held == null) {
                    val disk = withContext(Dispatchers.IO) {
                        runCatching { db.cachedHoldings(sym) }.getOrNull()
                    }
                    if (disk != null) {
                        _holdings.value = _holdings.value + (sym to disk)
                        // A disk row inside its life is a complete answer; going to the
                        // network for it could only return the same register.
                        if (!force && !disk.stale()) {
                            holdingsFetchedAt[sym] = disk.fetched
                            return@launch
                        }
                    }
                }

                val fresh = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.HoldingsFeed.holdings(sym) }.getOrNull()
                }
                holdingsFetchedAt[sym] = System.currentTimeMillis()
                if (fresh != null) {
                    // WRITTEN EVEN WHEN EMPTY, and that is deliberate. "This symbol is an
                    // ordinary share and has no holdings" is a real answer with a real
                    // lifetime, and caching it is what stops the app asking Yahoo the same
                    // dead question every time TJ opens one of his sixteen stocks.
                    _holdings.value = _holdings.value + (sym to fresh)
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheHoldings(fresh) }
                    }
                }
            } finally {
                // Always cleared, or this symbol is locked out of every later attempt - the
                // trap loadNews, loadInsider and loadFundamentals each had once.
                _holdingsLoading.value = _holdingsLoading.value - sym
            }
        }
    }

    /**
     * True when an intraday chart cannot gain another point until the next session, so the
     * automatic refresh should stop even though its TTL has lapsed.
     *
     * THE SAME ARGUMENT AS [pricesAreFinal], APPLIED TO THE PICTURE RATHER THAN THE NUMBER.
     * A 1D line is the regular session, and after 16:00 ET the regular session has no more
     * candles to give - but the quote poll keeps ticking through the whole extended window,
     * and the chart rides that clock. Left on a stock through an evening that is roughly
     * forty-eight requests for an image that cannot change.
     *
     * THE TEST IS ON THE DATA, NOT ON THE CLOCK ALONE, because "the market is shut" is not
     * the same statement as "what we hold is final", and the difference is exactly the case
     * that would break if this were sloppier:
     *
     *  - after hours, a 1D series whose LAST POINT is a regular-session candle is complete -
     *    stop;
     *  - in PRE-MARKET, before the open has produced anything, `ChartFeed` falls back to
     *    plotting the pre-market itself. Those points are still arriving, so the last point
     *    is an EXTENDED one and this correctly returns false - the chart keeps updating;
     *  - the after-hours line is live all through the extended window and only finishes when
     *    that window does. Requiring the held series to have been FETCHED during the closed
     *    stretch guarantees one last pull after 20:00 ET, so the final candles are not
     *    missed by a few minutes of timing.
     *
     * Only the AUTOMATIC path consults this. `force` - which is what pull-to-refresh passes -
     * never reaches it, so a deliberate pull always re-fetches.
     */
    private fun intradayChartIsFinal(held: ChartSeries): Boolean {
        val now = MarketClock.phase()
        if (now == MarketClock.Phase.OPEN) return false
        if (held.endMs <= 0L) return false
        return when (held.range) {
            ChartRange.D1 -> MarketClock.phase(held.endMs) == MarketClock.Phase.OPEN
            ChartRange.OVERNIGHT -> now == MarketClock.Phase.CLOSED &&
                MarketClock.phase(held.fetched) == MarketClock.Phase.CLOSED
            else -> false
        }
    }

    /**
     * Use a freshly fetched 1D chart as this symbol's sparkline as well.
     *
     * Both the quote's `spark` and `sparkAt` are updated, because the mark is what stops
     * `refreshSparklines` going and asking for the identical body five minutes from now. The
     * quote row is written back to SQLite for the same reason it is anywhere else: so the
     * chart is on screen instantly on the next cold start.
     *
     * Silently does nothing when there is no quote yet for the symbol - and deliberately
     * does NOT stamp `sparkAt` in that case, so the ordinary pass still picks the series up
     * once a quote exists.
     */
    private fun adoptAsSparkline(symbol: String, series: ChartSeries) {
        // THE PRECONDITION, CHECKED RATHER THAN ASSUMED. `Quote.spark` has always been the
        // regular session only, and the row sparkline's dotted baseline is the previous
        // regular close. A 1D series that fell back to plotting pre-market - which is what
        // happens before 09:30, and what happens if Yahoo ever returns a chart with no
        // trading-period metadata - is a different thing wearing the same name, and adopting
        // it would silently turn every row on the portfolio screen into a 24-hour line
        // measured against a baseline that no longer matches it. Not adopting simply costs
        // one sparkline request that would have been saved.
        if (!series.regularOnly) return
        val closes = series.points.map { it.close }
        if (closes.size < 2) return
        val q = _quotes.value[symbol] ?: return
        sparkAt[symbol] = System.currentTimeMillis()
        val merged = q.copy(spark = closes)
        _quotes.value = _quotes.value + (symbol to merged)
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.cacheQuotes(listOf(merged)) } }
    }

    // ================================================================ PRICE CHARTS

    /** The identity of one (symbol, range) pair inside [_charts]. */
    fun chartKey(symbol: String, range: ChartRange): String =
        symbol.uppercase() + "|" + range.name

    /** Whatever is currently held for this pair, cached or fresh. Null when nothing is. */
    fun chartOf(symbol: String, range: ChartRange): ChartSeries? =
        _charts.value[chartKey(symbol, range)]

    /**
     * The range the user last chose, remembered across launches.
     *
     * A stored preference rather than screen state: someone who reads their charts on a
     * one-year view should not have to re-select it on every stock they open, and it is the
     * kind of choice that is annoying to make twice.
     */
    fun chartRange(): ChartRange = ChartRange.byName(db.get(Keys.CHART_RANGE))

    fun setChartRange(range: ChartRange) {
        // Settings writes are synchronous SQLite; this one does not affect the ledger, so it
        // deliberately does not go through the recompute path - see `computeAffecting`.
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.set(Keys.CHART_RANGE, range.name) } }
    }

    /**
     * Make sure a chart is on screen, fetching it only if what we hold is genuinely old.
     *
     * THE ORDER HERE IS THE WHOLE ANSWER TO "the charts disappeared when I switched back".
     *
     *   1. read every cached range for this symbol off disk, once per session, and publish
     *      it - so the chart is drawn from SQLite before any request is made, on a cold
     *      start and offline alike;
     *   2. if what came back is still inside its range's TTL, stop. No request at all;
     *   3. otherwise fetch, and write the result back to disk.
     *
     * [force] skips steps 2 and 3's guard entirely and is what pull-to-refresh passes -
     * TJ's rule was "only periodically refresh automatically, but refresh every time I
     * gesture pull down", and those are exactly these two paths.
     *
     * Runs on [fgScope]: a chart is only useful while its screen is up, so backgrounding
     * cancels it and disconnects the socket. The DISK WRITE goes on `viewModelScope`,
     * because a series already paid for should be kept even if the user leaves mid-write.
     */
    fun loadChart(symbol: String, range: ChartRange, force: Boolean = false) {
        val sym = symbol.uppercase()
        val key = chartKey(sym, range)
        // The in-flight guard is INSIDE nothing - it is checked here and cleared in a
        // `finally` below. A guard set here and cleared only on the happy path is how
        // loadInsider once locked a symbol out for the rest of the session.
        if (_chartLoading.value.contains(key)) return

        // THE CHEAP WAY OUT, TAKEN BEFORE A COROUTINE IS EVEN STARTED.
        //
        // The detail screen calls this on every quote tick so the chart refreshes itself the
        // moment its range goes stale - see the note there. That is four calls a minute, and
        // all but one in twenty of them have nothing to do. Answering those here costs a map
        // lookup; answering them inside a launched coroutine would cost a coroutine each.
        if (!force && sym in chartDiskRead) {
            val cached = _charts.value[key]
            if (cached != null && !cached.isEmpty &&
                (!cached.stale() || intradayChartIsFinal(cached))
            ) return
        }

        fgScope.launch {
            _chartLoading.value = _chartLoading.value + key
            try {
                // ---- 1. disk first, every range for this symbol in one query
                if (chartDiskRead.add(sym)) {
                    // UN-MARKED IF THE READ DOES NOT FINISH. The mark is set before the
                    // query so two ranges loading at once do not both run it - but that also
                    // means a cancellation between the two leaves the symbol marked as read
                    // with nothing loaded, and the disk cache is then never consulted again
                    // for it this session. Same trap, same fix, as the `sparkAt` marks.
                    var read = false
                    val disk = try {
                        withContext(Dispatchers.IO) {
                            runCatching { db.cachedCharts(sym) }.getOrDefault(emptyMap())
                        }.also { read = true }
                    } finally {
                        if (!read) chartDiskRead.remove(sym)
                    }
                    if (disk.isNotEmpty()) {
                        val m = HashMap(_charts.value)
                        // Never let a disk row overwrite something newer already in memory:
                        // a fetch for another range can land while this query is suspended.
                        disk.forEach { (r, s) ->
                            val k = chartKey(sym, r)
                            val live = m[k]
                            if (live == null || live.fetched < s.fetched) m[k] = s
                        }
                        _charts.value = m
                    }
                }

                // ---- 2. is what we hold still good?
                val held = _charts.value[key]
                if (!force && held != null && !held.isEmpty && !held.stale()) return@launch

                // ---- 3. fetch
                val fresh = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.ChartFeed.series(sym, range) }.getOrNull()
                }
                chartFetchedAt[key] = System.currentTimeMillis()
                if (fresh != null && !fresh.isEmpty) {
                    _charts.value = _charts.value + (key to fresh)
                    // ONE REQUEST, TWO CONSUMERS (Round 58).
                    //
                    // The 1D chart and the row sparkline are the SAME Yahoo URL -
                    // `range=1d&interval=5m&includePrePost=true` - fetched by two different
                    // paths on two independent five-minute clocks. With a stock's detail
                    // screen open, that is the same body pulled twice per window for no
                    // benefit at all. Feeding this series straight into the quote, and
                    // stamping `sparkAt` as `refreshSparklines` would have, makes the second
                    // request simply not happen.
                    if (range == ChartRange.D1) adoptAsSparkline(sym, fresh)
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheChart(fresh) }
                    }
                }
                // A null result deliberately changes NOTHING. Whatever was on screen stays
                // there: replacing a real chart with an empty one because a single request
                // was refused is the failure this cache exists to prevent.
            } finally {
                _chartLoading.value = _chartLoading.value - key
            }
        }
    }

    fun loadFundamentals(symbol: String, force: Boolean = false) {
        val sym = symbol.uppercase()
        val since = System.currentTimeMillis() - (coreFetchedAt[sym] ?: 0L)
        if (!force && since < com.tj.portfolio.net.FundamentalsFeed.CORE_TTL_MS &&
            _fundamentals.value[sym]?.values?.isNotEmpty() == true
        ) return
        if (_fundLoading.value.contains(sym)) return

        fgScope.launch {
            _fundLoading.value = _fundLoading.value + sym
            try {
                if (_fundamentals.value[sym] == null) {
                    // BOTH rows, not just the core one: the Analysts tab is one swipe away
                    // and should not be empty while its own fetch runs.
                    val disk = withContext(Dispatchers.IO) {
                        listOfNotNull(
                            runCatching { db.cachedFundamentals(sym, Keys.KIND_CORE) }.getOrNull(),
                            runCatching { db.cachedFundamentals(sym, Keys.KIND_RATINGS) }.getOrNull()
                        )
                    }
                    disk.forEach { mergeFundamentals(sym, it) }
                    // A disk row that is still inside its life is a complete answer - going
                    // to the network for it would be a request that could only return the
                    // same numbers. Recording its age as the fetch mark makes the guard at
                    // the top of this function agree for the rest of the session.
                    val core = disk.firstOrNull { it.values.isNotEmpty() }
                    if (!force && core != null &&
                        System.currentTimeMillis() - core.fetched <
                        com.tj.portfolio.net.FundamentalsFeed.CORE_TTL_MS
                    ) {
                        coreFetchedAt[sym] = core.fetched
                        return@launch
                    }
                }

                val fresh = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.FundamentalsFeed.core(sym) }.getOrNull()
                }
                if (fresh != null && !fresh.isEmpty) {
                    coreFetchedAt[sym] = System.currentTimeMillis()
                    mergeFundamentals(sym, fresh)
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheFundamentals(sym, Keys.KIND_CORE, fresh) }
                    }
                }
            } finally {
                // Must always clear, or this symbol is locked out of every later attempt -
                // the same trap loadNews and loadInsider each had once.
                _fundLoading.value = _fundLoading.value - sym
            }
        }
    }

    /**
     * The analyst actions: who rated it, when, what to, and at what price target.
     *
     * A SEPARATE REQUEST ON PURPOSE. Yahoo's rating history is the whole history - for a
     * large cap that is around 195KB against roughly 15KB for every other module put
     * together. Pulling it on every detail-screen open, for a tab the user may never look
     * at, would be the heaviest thing this app does. So the Analysts tab asks for it when
     * it is actually shown.
     */
    fun loadRatings(symbol: String, force: Boolean = false) {
        val sym = symbol.uppercase()
        val since = System.currentTimeMillis() - (ratingsFetchedAt[sym] ?: 0L)
        if (!force && since < com.tj.portfolio.net.FundamentalsFeed.RATINGS_TTL_MS &&
            _fundamentals.value[sym]?.ratings?.isNotEmpty() == true
        ) return
        if (_ratingsLoading.value.contains(sym)) return

        fgScope.launch {
            _ratingsLoading.value = _ratingsLoading.value + sym
            try {
                if (_fundamentals.value[sym]?.ratings.isNullOrEmpty()) {
                    val disk = withContext(Dispatchers.IO) {
                        runCatching { db.cachedFundamentals(sym, Keys.KIND_RATINGS) }.getOrNull()
                    }
                    if (disk != null) {
                        mergeFundamentals(sym, disk)
                        if (!force && disk.ratings.isNotEmpty() &&
                            System.currentTimeMillis() - disk.fetched <
                            com.tj.portfolio.net.FundamentalsFeed.RATINGS_TTL_MS
                        ) {
                            ratingsFetchedAt[sym] = disk.fetched
                            return@launch
                        }
                    }
                }

                val fresh = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.FundamentalsFeed.ratings(sym) }.getOrNull()
                }
                if (fresh != null && (fresh.ratings.isNotEmpty() || fresh.consensus != null)) {
                    ratingsFetchedAt[sym] = System.currentTimeMillis()
                    mergeFundamentals(sym, fresh)
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheFundamentals(sym, Keys.KIND_RATINGS, fresh) }
                    }
                }
            } finally {
                _ratingsLoading.value = _ratingsLoading.value - sym
            }
        }
    }

    /** Rows held and total payload size, for the Settings diagnostics card. */
    fun fundamentalsCacheStats(): Pair<Int, Long> = db.fundamentalsCacheStats()


    /**
     * Does this market headline name one of the user's holdings? Returns the ticker if so.
     *
     * Matched as a whole word against the ticker and against the company's first name word,
     * because a substring match turns any headline containing "on", "it" or "all" into a
     * holding. Two-letter tickers are skipped entirely for the same reason - the cost of a
     * miss is one headline that stays under "All", the cost of a false hit is junk sitting
     * in "My stocks".
     */
    /**
     * Symbol -> company name for the held symbols, read ONCE per feed refresh rather than
     * once per headline. [Relevance.matchHolding] does the rest.
     */
    private fun holdingNames(owned: Set<String>): Map<String, String> =
        owned.associateWith { _quotes.value[it]?.name.orEmpty() }

    /**
     * Which holding a MARKET-WIDE headline is about, for the Feed's "My stocks" filter.
     *
     * THIS HAD THE SAME BUG TJ REPORTED ON THE NEWS TAB, in a second place. The old
     * implementation did `title.uppercase().split(WORD_SPLIT)` and then asked whether any
     * resulting word was one of the held tickers. Upper-casing the headline first destroys
     * the only thing that separates a ticker from an ordinary word, so
     *
     *     "Axsome Therapeutics (AXSM) Stock Looks Like A Bargain On Its 7x Five Year Run"
     *
     * became "...FIVE YEAR RUN" and was filed under TJ's Five Below holding. The company-name
     * path had it too: `companyWordIndex` took the first word of "Five Below, Inc." of length
     * >= 4, upper-cased, giving the same "FIVE".
     *
     * Both paths now go through [Relevance], which matches against the headline AS WRITTEN
     * and knows that a company whose leading word is ordinary English has to be named in full.
     * One implementation, shared with the news filter, so the two cannot drift apart again.
     */
    private fun matchHolding(
        title: String,
        owned: Set<String>,
        names: Map<String, String>
    ): String? = com.tj.portfolio.net.Relevance.matchHolding(title, owned, names)

    /**
     * One place that de-duplicates, drops blanks and orders the feed newest first.
     *
     * TWO PASSES, AND BOTH ARE NEEDED.
     *
     * 1. BY STORY. The All tab merges per-symbol headlines with market-wide ones, and the
     *    same wire story genuinely arrives through both - Nasdaq carries it on the NVDA feed
     *    while Google's business topic carries it too, with the publisher's name bolted onto
     *    the end. `FeedItem.id` differs only in `kind` between those two, so de-duplicating
     *    by id alone kept both and the reader saw the headline twice. `News.dedupeKey` is the
     *    same normalisation the per-symbol lists already use to collapse exactly this, so the
     *    feed now uses it rather than a second, weaker rule. Insider filings are deliberately
     *    excluded: EDGAR dates a filing to the day, two insiders can file identically-titled
     *    entries, and only the accession number tells them apart - merging those would hide a
     *    filing. Per-symbol rows come first in the input, so the copy attributed to a holding
     *    is the one kept.
     *
     * 2. BY ID. The LazyColumn is keyed by `id`, and a keyed list handed the same key twice
     *    throws - the crash that used to land seconds after a stock's news appeared. Pass 1
     *    is stricter than the key, so this can only ever be a no-op; it is here so that the
     *    guarantee holds by construction rather than by argument.
     */
    private fun publishFeed(items: List<com.tj.portfolio.data.FeedItem>) {
        _feed.value = items
            .filter { it.title.isNotBlank() }
            .distinctBy { storyKey(it) }
            .distinctBy { it.id }
            .sortedByDescending { it.published }
            // BOUNDED, and it has to be now that the feed is additive. Before v6.3 each
            // refresh replaced the list, so its size was whatever one pass fetched. It now
            // merges, which is the point - but without a cap a month of cached headlines
            // would accumulate into one LazyColumn and one ever-growing list in memory.
            // The newest few hundred is far more than anyone scrolls; the rest stays on disk
            // and is still searchable from a stock's own screen.
            .take(MAX_FEED_ITEMS)
    }

    /**
     * Publish AND remember. Everything the feed shows is written through to the headline
     * cache, so the next launch, the next app switch and the next memory trim all start from
     * what the user already had rather than from nothing.
     *
     * The write is INSERT OR IGNORE keyed on the same id the list is keyed by, so it is
     * cheap, exact and idempotent - re-publishing the same list costs one no-op statement
     * per row and adds nothing.
     *
     * Fire-and-forget on IO: the screen must never wait on SQLite to draw a headline.
     */
    private fun publishAndCache(
        items: List<com.tj.portfolio.data.FeedItem>,
        /**
         * ONLY the rows this pass actually fetched. Defaults to everything for the callers
         * that publish a whole list at once.
         *
         * THIS PARAMETER IS A PERFORMANCE FIX FOR A PROBLEM I INTRODUCED EARLIER IN THIS
         * ROUND. Making the feed additive meant every incremental repaint merged in the
         * whole 400-item list - and the first version then handed that entire list to
         * `cacheNews` as well. The per-symbol loop repaints once per symbol, so a 20-symbol
         * refresh was running twenty 400-row SQLite transactions, ~8,000 INSERT OR IGNORE
         * statements, essentially all of them no-ops against rows already stored. Passing
         * just the new arrivals makes it twenty small writes instead.
         */
        toCache: List<com.tj.portfolio.data.FeedItem> = items
    ) {
        publishFeed(items)
        if (toCache.isEmpty()) return
        // Copied before leaving the main thread: `toCache` may be a view over a list the
        // caller keeps appending to.
        val write = toCache.filter { it.title.isNotBlank() }
        if (write.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.cacheNews(write) } }
    }

    /**
     * Drop cached headlines older than a month, once per launch.
     *
     * TJ asked for old news to be deleted. Doing it once at startup rather than on every
     * refresh is deliberate: it is a DELETE over an indexed column, it can only matter after
     * a month has passed, and running it four times an hour would be pure overhead.
     */
    /**
     * Record when the feed last completed a network pass, in memory AND on disk.
     *
     * The header reads "Updated 12s ago" from this. It used to live only in a StateFlow, so
     * after a restart - or after the cache restored a full screen of headlines - it read
     * "Pull to load" while showing a screenful of news, which is simply untrue.
     */
    private fun stampFeedAt() {
        val now = System.currentTimeMillis()
        _feedAt.value = now
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.set(Keys.FEED_AT, now.toString()) } }
    }

    private fun purgeOldNewsOnce() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.purgeOldNews() }
            // Same launch, same retention idea: a stock the user looked at once and never
            // returned to should not keep a 200KB analyst history on the phone forever.
            runCatching { db.purgeFundamentals() }
            // The HTTP cache is bounded by age AND by total size - see Db.createHttpCache.
            runCatching { db.purgeHttpCache() }
            // ONCE A SESSION, NOT ONCE PER CHART. This is a DELETE with an ordered subquery
            // over the whole table; running it on every fetch put that sort in the path of
            // every chart the user looked at, to enforce a bound that cannot be reached in
            // one session anyway. Every other cache in this app purges here; so does this one.
            runCatching { db.purgeChartCache() }
        }
    }

    /**
     * Give [Http] somewhere durable to keep its conditional-GET validators and bodies.
     *
     * Done here rather than in `Http` itself because `net/` deliberately knows nothing about
     * `data/` or about Android - that is what lets every class in it be unit-tested on a plain
     * JVM. Until this runs the cache is memory-only, which is exactly what a test wants.
     *
     * Every method is wrapped: a failure to read or write a CACHE must never be able to take
     * down a network request. The worst case is that the request is not cached.
     */
    /**
     * `Http.disk` is a process-wide field, so the cache adapter below holds this ViewModel -
     * and through it an open `Db` - alive for as long as the process runs. Detaching here is
     * what keeps that from being a leak; the next ViewModel attaches its own in `init`.
     */
    override fun onCleared() {
        com.tj.portfolio.net.Http.attachDiskCache(null)
        super.onCleared()
    }

    private fun attachHttpDiskCache() {
        com.tj.portfolio.net.Http.attachDiskCache(object : com.tj.portfolio.net.Http.DiskCache {
            override fun load(url: String): Triple<String, String, String>? =
                runCatching {
                    db.httpCached(url)?.let { Triple(it.etag, it.lastModified, it.body) }
                }.getOrNull()

            override fun save(url: String, etag: String, lastModified: String, body: String) {
                runCatching { db.httpStore(url, etag, lastModified, body) }
            }

            override fun touch(url: String) {
                runCatching { db.httpTouch(url) }
            }

            override fun forget(url: String) {
                runCatching { db.httpForget(url) }
            }
        })
    }

    /**
     * What counts as "the same story" for the feed. See [publishFeed].
     *
     * MEMOISED, because this is the app's hottest string operation and the feed is now
     * additive. `publishFeed` keys the ENTIRE list on every repaint, and an incremental
     * refresh repaints once per symbol - so a 400-item feed over a 20-symbol refresh was
     * 8,000 calls, each normalising a headline with two regexes. Benchmarked on the shipped
     * release bytecode that was 54.6 ms of main-thread work per refresh on a desktop JVM,
     * and a phone is several times slower.
     *
     * Two fixes together: the regexes in [News] are compiled once instead of per call, and
     * this map means a headline is normalised ONCE no matter how many times it is
     * re-published. A story's key never changes, so caching it is exact rather than a
     * heuristic. Bounded to twice the feed cap and cleared wholesale when it overflows - a
     * cheap policy that is right because the working set is the current feed.
     */
    private fun storyKey(f: com.tj.portfolio.data.FeedItem): String {
        val id = f.id
        storyKeys[id]?.let { return it }
        if (storyKeys.size > MAX_FEED_ITEMS * 2) storyKeys.clear()
        val k = if (f.kind == com.tj.portfolio.data.FeedItem.INSIDER) id
        else "STORY|" + News.dedupeKey(
            NewsItem(f.symbol, f.title, f.url, f.source, f.published)
        )
        storyKeys[id] = k
        return k
    }

    /** [manual] behaves exactly as it does in [refresh] - see the note on UiState.manualRefresh. */
    fun refreshFeed(includeInsider: Boolean = true, manual: Boolean = false) {
        if (_feedLoading.value) {
            if (manual) _ui.value =
                _ui.value.copy(manualRefresh = true, refreshSource = PULL_FEED)
            return
        }
        // A refresh the USER asked for should go to the network even if the feed has not
        // changed its ETag - otherwise pulling down appears to do nothing at all - and it
        // should reach a host the app had backed off from, for the same reason.
        if (manual) {
            // Cooldowns ARE cleared: coming back into signal and pulling down is exactly when
            // the user wants a host retried, and an invisible backoff timer would make the
            // gesture appear to do nothing.
            //
            // The conditional-GET validators are NOT cleared any more. That was here so a
            // pull "really" went to the network, but it means re-downloading several hundred
            // KB of feed to discover what a bodyless 304 already tells us. Since v6.3 the
            // stories are cached by id, so a 304 is not a wasted trip - it is the correct
            // answer "nothing new", arrived at for free. Dropping the validators only bought
            // bandwidth spent to reach the same conclusion.
            com.tj.portfolio.net.Http.clearCooldowns()
        }
        feedJob = viewModelScope.launch {
            _feedLoading.value = true
            if (manual) _ui.value =
                _ui.value.copy(manualRefresh = true, refreshSource = PULL_FEED)
            try {
                val rows = _ui.value.rows
                val owned = rows.filter { !it.watchOnly }.map { it.symbol }.toSet()
                val symbols = rows.map { it.symbol }
                val fk = finnhubKey()

                // The feed used to await EVERY source before drawing a single row: all the
                // headlines, then all the SEC filings, then - only after those - the two
                // Reddit endpoints, one after the other. Nothing appeared until the slowest
                // of them finished, which on a 20-symbol portfolio is a long stare at an
                // empty tab. Now the three stages publish as they land, newest first, and
                // the Reddit call runs alongside the rest instead of queueing behind it.
                // Both Reddit aggregators recompute on their own schedule - Tradestie every
                // 15 minutes, ApeWisdom about every 30 - so asking every 3 minutes fetched
                // the same answer four or five times over. Tradestie publishes a 20/min cap
                // and neither owes us anything, so this rides their cadence, not ours.
                //
                // CHILDREN OF THIS COROUTINE, not of viewModelScope. Launched into
                // viewModelScope they outlived the block that awaits them, so a throw in the
                // headline stage below left two HTTP requests running with nobody to read
                // their answers - and on a refresh that keeps failing, a fresh pair of
                // orphans every cycle. As children they are cancelled with their parent,
                // which is what makes the failure path cost the same as the happy one.
                val socialDue = System.currentTimeMillis() - socialAt > SOCIAL_REFRESH_MS
                val socialJob = async(Dispatchers.IO) {
                    if (!socialDue) emptyList()
                    else runCatching { Social.trending(25) }.getOrDefault(emptyList())
                }
                // Market-wide headlines, fetched alongside the per-symbol ones so they add
                // nothing to the wait. Before this the "All" tab was only the user's own
                // symbols, which made it "My stocks" plus the watchlist and nothing more.
                val marketJob = async(Dispatchers.IO) {
                    runCatching { News.market() }.getOrDefault(emptyList())
                }
                // filings already on screen; kept if this pass skips or fails to refresh them
                val existingFilings = _feed.value
                    .filter { it.kind == com.tj.portfolio.data.FeedItem.INSIDER }

                // THE MARKET-WIDE PULL LANDS FIRST, AND IS DRAWN FIRST.
                //
                // TJ reported the feed "takes a long time to load". This was most of it: the
                // first paint waited for `newsJobs.awaitAll()` - a per-symbol news fetch for
                // EVERY holding and watchlist entry, 20 of them behind a gate of 5, so four
                // serial waves of network before a single row appeared. The five market-wide
                // feeds finish in well under a second and were sitting there completed,
                // waiting for the slowest per-symbol fetch to catch up.
                //
                // Now the market headlines paint as soon as they arrive, and the per-symbol
                // ones are merged in as each SYMBOL completes rather than after all of them.
                // Same requests, same total time to finish - but the screen stops being empty
                // almost immediately, which is the thing that was actually being complained
                // about.
                val marketFirst = marketJob.await().map { n ->
                    val hit = matchHolding(n.title, owned, holdingNames(owned))
                    com.tj.portfolio.data.FeedItem(
                        kind = com.tj.portfolio.data.FeedItem.MARKET,
                        symbol = hit ?: "",
                        title = n.title, url = n.url, source = n.source,
                        published = n.published, owned = hit != null
                    )
                }
                // MERGED with what is already on screen, not assigned over it. Assigning
                // here would blank the restored cache the instant a refresh started - the
                // very "my news disappeared" symptom this round is fixing, reintroduced from
                // the other end.
                publishAndCache(marketFirst + existingFilings + _feed.value,
                    toCache = marketFirst)
                stampFeedAt()

                val freshNews = HashMap<String, List<NewsItem>>()
                val perSymbol = java.util.Collections.synchronizedList(
                    ArrayList<com.tj.portfolio.data.FeedItem>()
                )

                // ---- PER-SYMBOL HEADLINES, ONLY WHEN SOMEONE IS LOOKING AT HEADLINES.
                //
                // This loop is one request per followed symbol, every three minutes - about
                // 480 an hour on a 24-symbol portfolio, and it was the app's second-largest
                // source of traffic after the quote poll. It ran on the timer whether or not
                // the Feed tab had ever been opened, which is exactly the waste the
                // visible-scope mechanism removed from quotes in v4.5 and never got applied
                // to news.
                //
                // The grace window matters as much as the flag: a pass that STARTED while the
                // Feed was on screen must finish its work even if the user navigates away
                // mid-flight, or leaving the tab at the wrong moment leaves the list
                // half-updated. A manual pull always does the full pass - a gesture the user
                // made must never quietly do less than it used to.
                val newsDue = manual || newsVisible ||
                    System.currentTimeMillis() - newsVisibleAt < NEWS_VISIBLE_GRACE_MS
                if (newsDue) withContext(Dispatchers.IO) {
                    val gate = Semaphore(MAX_PARALLEL_REQUESTS)
                    symbols.map { sym ->
                        async {
                            gate.withPermit {
                                val name = _quotes.value[sym]?.name ?: ""
                                val head = runCatching { News.forSymbol(sym, name, fk) }
                                    .getOrDefault(emptyList())
                                // reuse the same fetch for the per-symbol news screens
                                if (head.isNotEmpty()) synchronized(freshNews) { freshNews[sym] = head }
                                val rows = head.take(6).map {
                                    com.tj.portfolio.data.FeedItem(
                                        kind = com.tj.portfolio.data.FeedItem.NEWS,
                                        symbol = sym,
                                        title = it.title,
                                        url = it.url,
                                        source = it.source,
                                        published = it.published,
                                        owned = sym in owned
                                    )
                                }
                                if (rows.isNotEmpty()) {
                                    perSymbol.addAll(rows)
                                    // Repaint on the main thread as this symbol lands. The
                                    // list is de-duplicated and re-sorted by publishFeed, so
                                    // an incremental publish is the same list the all-at-once
                                    // one produced, just sooner.
                                    withContext(Dispatchers.Main) {
                                        // Merge THIS symbol's rows into what is on screen.
                                        // The full `perSymbol` snapshot is not needed - every
                                        // earlier symbol is already in `_feed` - and taking
                                        // it turned an O(symbols) loop into O(symbols^2)
                                        // list-building for no benefit.
                                        publishAndCache(rows + _feed.value, toCache = rows)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
                val newsItems = synchronized(perSymbol) { perSymbol.toList() }

                // ---- headlines are already on screen by here; this only settles the
                // per-symbol news map the DETAIL screens read.
                //
                // MERGED, not replaced. These came from the cheap single-source cascade, and
                // simply assigning them would throw away the wider multi-source list the
                // detail screen had already built for any stock the user had opened.
                if (freshNews.isNotEmpty()) _news.value = mergeNews(_news.value, freshNews)
                val marketItems = marketFirst
                // `+ _feed.value` is what makes a refresh ADDITIVE. Before v6.3 this
                // assigned only what this pass fetched, so everything the user already had -
                // including everything restored from the cache - vanished and was rebuilt
                // from scratch every three minutes. Now the pass merges into what is there;
                // publishFeed de-duplicates by story and by id, so nothing doubles up.
                publishAndCache(
                    newsItems + marketItems + existingFilings + _feed.value,
                    toCache = newsItems + marketItems
                )
                stampFeedAt()

                // ---- stage 2: SEC filings, which are two business days behind by law and
                // ride their own slow cadence, so they must never hold the headlines up
                //
                // Covers WATCHED symbols as well as held ones now, reads a month rather than
                // the last five filings of each, and publishes into the Insider tab's own
                // store - see [refreshInsiders] and [_insiderFilings] for why all three had
                // to change together.
                if (includeInsider) startInsiderRefresh(symbols, owned)

                // ---- stage 3: whatever the Reddit call has by now
                val trend = socialJob.await()
                if (trend.isNotEmpty()) {
                    _trending.value = trend.distinctBy { it.symbol }
                    socialAt = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                // a feed failure must never take the app down; last good data stays on screen
            } finally {
                _feedLoading.value = false
                syncManualIndicator()
            }
        }
    }

    /**
     * THE STUCK PULL-TO-REFRESH SPINNER. Reported by TJ with a screenshot showing the feed
     * saying "Updated 12s ago" while the spinner kept turning.
     *
     * The flag was cleared by hand in two `finally` blocks, and one of them got it wrong:
     *
     *     if (_feedLoading.value) {              // a feed pass is ALREADY running
     *         if (manual) _ui.value = ...copy(manualRefresh = true)
     *         return                             // ... so attach the user's spinner to it
     *     }
     *     ...
     *     } finally {
     *         if (manual) _ui.value = ...copy(manualRefresh = false)   // <- only if THIS
     *     }                                                           //    call was manual
     *
     * The guard deliberately attaches a user's pull to a refresh already in flight, so the
     * gesture does not snap back having done nothing. But the call it attached to had
     * `manual = false`, so ITS finally did not clear the flag. Pull down while the automatic
     * 3-minute feed tick happens to be running and the spinner turns **for the rest of the
     * session**. That is exactly the window TJ hit: the feed pass is 25+ requests and takes
     * many seconds, so it is running a good share of the time.
     *
     * v6.1 made it worse rather than causing it. `refresh()` clears the flag unconditionally,
     * so a later quote tick used to rescue the stranded spinner - but v6.1 stopped polling
     * quotes on screens that show no prices, and the Feed tab is one of them. The rescue
     * disappeared and the bug became permanent.
     *
     * THE FIX IS TO STOP CLEARING IT BY HAND. The indicator is now DERIVED: it can only be on
     * while something is actually loading. Whichever operation finishes last turns it off,
     * no matter which of them the user's gesture was attached to, and no future code path can
     * strand it by forgetting a branch.
     */
    private fun syncManualIndicator() {
        val want = spinnerShouldShow(
            userAsked = _ui.value.manualRefresh,
            quotesLoading = _ui.value.loading,
            feedLoading = _feedLoading.value
        )
        if (_ui.value.manualRefresh != want) {
            _ui.value = _ui.value.copy(
                manualRefresh = want,
                refreshSource = if (want) _ui.value.refreshSource else PULL_NONE
            )
        }
    }



    fun loadModels() {
        val key = claudeKey()
        if (key.isBlank()) { _toast.value = "Enter an API key first"; return }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { Claude.models(key) }.getOrDefault(emptyList())
            }
            _models.value = list
            if (list.isEmpty()) _toast.value = "Couldn't reach the Claude API - check the key"
            else {
                _toast.value = "${list.size} models available"
                if (db.get(Keys.CLAUDE_MODEL).isBlank()) db.set(Keys.CLAUDE_MODEL, Claude.preferredModel(list))
            }
        }
    }

    // -------------------------------------------------- symbol type-ahead

    /** Debounced type-ahead over Yahoo (no key) with a Finnhub fallback. */
    fun searchSymbols(q: String) {
        searchJob?.cancel()
        val term = q.trim()
        if (term.isBlank()) {
            _search.value = emptyList()
            _searching.value = false
            return
        }
        searchJob = fgScope.launch {
            delay(220)
            _searching.value = true
            try {
                val hits = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.SymbolSearch.query(term, finnhubKey()) }
                        .getOrDefault(emptyList())
                }
                _search.value = hits
            } finally {
                // A `finally`, not a trailing statement. Every other loading flag in this
                // file has one; this was the exception, and it is the one attached to a
                // spinner the user is watching. Leaving the app with a search in flight
                // cancels the coroutine, and without this `_searching` stayed true - so the
                // sheet showed a spinner that never stopped and suppressed its own "no
                // results" text, until the user typed another character. Exactly the
                // invariant `SpinnerTest` exists to pin.
                _searching.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = emptyList()
        _searching.value = false
    }

    fun isWatched(symbol: String): Boolean = symbol.uppercase() in watchedSymbols()

    fun watchedSymbols(): Set<String> = db.watchlist().toSet()

    fun heldSymbols(): Set<String> =
        _ui.value.rows.filter { !it.watchOnly }.map { it.symbol }.toSet()

    fun isHeld(symbol: String): Boolean =
        _ui.value.rows.any { it.symbol == symbol.uppercase() && !it.watchOnly }

    /** Which half of the Watch tab to reopen on: 0 watchlist, 1 research. */
    fun watchSubTab(): Int = db.get(Keys.WATCH_SUBTAB, "0").toIntOrNull()?.coerceIn(0, 1) ?: 0
    fun setWatchSubTab(i: Int) = db.set(Keys.WATCH_SUBTAB, i.coerceIn(0, 1).toString())

    /** Which Research list was open last. Coerced, so a bad stored value cannot throw. */
    fun researchTab(): Int = db.get(Keys.RESEARCH_TAB, "0").toIntOrNull()?.coerceIn(0, 2) ?: 0
    fun setResearchTab(i: Int) = db.set(Keys.RESEARCH_TAB, i.coerceIn(0, 2).toString())

    fun lastTab(): Int = db.get(Keys.LAST_TAB, "0").toIntOrNull() ?: 0
    fun setLastTab(i: Int) = db.set(Keys.LAST_TAB, i.toString())

    fun latestTxnDate(): Long = db.latestTxnDate()
    fun importCount(): Int = db.importCount()
    fun lastBackup(): String = db.get(Keys.LAST_BACKUP)

    // --------------------------------------- offline Claude bridge (no key)

    fun advicePromptFile(): String {
        val headlines = headlinesBlock() + contextBlock()
        return com.tj.portfolio.net.ClaudeBridge.advicePrompt(portfolioJson(), headlines)
    }

    /**
     * A compact list of what is already recorded, handed to Claude so duplicates are
     * filtered at the source rather than only being caught afterwards. Capped so a long
     * history cannot blow up the prompt.
     */
    fun existingDigest(limit: Int = 120): String {
        val rows = db.allTxns().sortedByDescending { it.date }.take(limit)
        if (rows.isEmpty()) return "(nothing recorded yet)"
        return rows.joinToString("\n") { t ->
            buildString {
                append(Fmt.iso(t.date)).append("  ").append(t.type)
                if (!t.symbol.isNullOrBlank()) append(" ").append(t.symbol)
                if (t.quantity > 0) append("  qty ").append(Fmt.shares(t.quantity))
                append("  $").append(Fmt.priceBare(kotlin.math.abs(t.amount)))
            }
        }
    }

    /** Builds and writes the prompt file off the UI thread - it reads every transaction. */
    fun writeScreenshotPrompt(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val saved = com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(
                    getApplication(), SCREENSHOT_PROMPT_FILE, screenshotPromptFile(),
                    "text/markdown"
                )
                if (saved != null) "Saved to ${saved.display}" else "Couldn't write to Downloads"
            }
            onDone(msg)
        }
    }

    fun screenshotPromptFile(): String {
        val last = db.latestTxnDate()
        val li = db.lastImport()
        val ctx = buildString {
            if (last > 0) {
                append("My app already has transactions through **")
                append(Fmt.day(last))
                append("**. Only include activity on or after that date, and it is fine to ")
                append("repeat rows from that date - the app removes exact duplicates.\n")
            } else {
                append("This is my first import, so include everything visible.\n")
            }
            if (li != null) append("\nLast import: ").append(Fmt.day(li.at))
                .append(" (").append(li.count).append(" transactions added).\n")
        }
        val syms = _ui.value.rows.filter { !it.watchOnly }.map { it.symbol }
        return com.tj.portfolio.net.ClaudeBridge.screenshotPrompt(ctx, syms, existingDigest())
    }

    /** Parse a Claude-app reply file and route it to advice and/or the import review. */
    fun importClaudeFile(text: String): String {
        // Round 54: one file chooser, three kinds of answer. A research reply is recognised
        // by its own payload key and handled first, so importing it from the Advice tab by
        // mistake fills the Research tab instead of reporting "nothing usable".
        if (com.tj.portfolio.net.ResearchBridge.looksLikeResearch(text)) {
            return importResearchFile(text)
        }
        val r = runCatching { com.tj.portfolio.net.ClaudeBridge.parse(text) }
            .getOrElse { return "Couldn't read that file: ${it.message}" }
        if (r.error != null) return r.error
        var msg = ""
        if (r.advice != null) {
            _advice.value = r.advice
            _adviceError.value = null
            db.set(Keys.ADVICE_CACHE, adviceToJson(r.advice).toString())
            msg = "Advice loaded (${r.advice.stocks.size} ratings)"
        }
        if (r.transactions.isNotEmpty()) {
            _importResult.value = ExtractResult(r.transactions, r.notes, null, "")
            msg = if (msg.isBlank()) "Found ${r.transactions.size} transactions - review them"
            else "$msg; ${r.transactions.size} transactions to review"
        }
        return msg.ifBlank { "Nothing usable in that file" }
    }

    // ====================================================== RESEARCH (Round 54)

    /**
     * OFF THE MAIN THREAD (Round 57). This reads `Keys.RESEARCH_CACHE`, which the schema note
     * on that key describes as "up to a couple of hundred KB of screener output", and parses
     * the whole thing with `JSONObject`. It ran synchronously inside `init`, i.e. before the
     * first frame - a few hundred milliseconds of JSON parsing between launching the app and
     * seeing anything.
     */
    private fun loadCachedResearch() {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = runCatching { db.get(Keys.RESEARCH_CACHE) }.getOrDefault("")
            if (raw.isBlank()) return@launch
            val set = runCatching {
                com.tj.portfolio.data.ResearchSet.fromJson(JSONObject(raw))
            }.getOrNull() ?: return@launch
            if (set.isEmpty) return@launch
            withContext(Dispatchers.Main) {
                // Merged, not assigned: a live build may have landed while this was parsing,
                // and a cache read must never overwrite something newer.
                if (_research.value.isEmpty) _research.value = set
            }
        }
    }

    /**
     * Publish immediately, serialise in the background.
     *
     * `set.toJson().toString()` over ~150 rows plus a SQLite write ran on the MAIN THREAD, and
     * not only on a rebuild - `enrichVisible` calls this after every enrich loop, so it fired
     * on every "Load more" tap. The screen gets the new state at once; the disk copy catches
     * up a few milliseconds later, and losing it would cost one rebuild.
     */
    private fun cacheResearch(set: com.tj.portfolio.data.ResearchSet) {
        _research.value = set
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.set(Keys.RESEARCH_CACHE, set.toJson().toString()) }
        }
    }

    fun researchStale(): Boolean {
        val s = _research.value
        return s.isEmpty ||
            System.currentTimeMillis() - s.generated > com.tj.portfolio.net.Research.TTL_MS
    }

    /**
     * Build (or rebuild) all three sections.
     *
     * Called when the Research sub-tab becomes visible and the cache is stale, and on a pull
     * to refresh. NEVER on a timer: this is ~18 requests across four providers, and the app's
     * standing rule is that nothing polls a provider for data the user is not looking at.
     */
    fun loadResearch(force: Boolean = false) {
        if (_researchBusy.value.isNotEmpty()) return
        if (!force && !researchStale()) {
            // Cached rows can still be missing their per-row lookups - finish those instead.
            enrichVisible()
            return
        }
        researchJob?.cancel()
        researchJob = fgScope.launch {
            _researchBusy.value = BUSY_BUILDING
            _researchError.value = null
            if (force) _ui.value = _ui.value.copy(manualRefresh = true, refreshSource = PULL_RESEARCH)
            try {
                val built = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.Research.build() }
                        .getOrElse {
                            com.tj.portfolio.data.ResearchSet(
                                error = "Couldn't build research: ${it.message}"
                            )
                        }
                }
                if (built.isEmpty) {
                    // Keep whatever was on screen. An empty rebuild is almost always a
                    // provider cooldown, and blanking three good lists to show an error
                    // would be the app punishing the user for Yahoo's rate limiter.
                    _researchError.value = built.error
                        ?: "No research data came back this time. Try again in a few minutes."
                } else {
                    analystDone.clear()
                    vehicleDone.clear()
                    // A rebuild carries forward the explanations already on file for the same
                    // symbols - Claude's paragraph about NVDA does not go stale in 30 minutes,
                    // and re-earning it would mean another API call or another file round trip.
                    cacheResearch(carryExplanations(_research.value, built))
                }
            } finally {
                _researchBusy.value = ""
                if (force) _ui.value = _ui.value.copy(manualRefresh = false, refreshSource = PULL_NONE)
            }
            enrichVisible()
        }
    }

    /** Keep [old]'s `why` text for any symbol that survived into [fresh]. */
    private fun carryExplanations(
        old: com.tj.portfolio.data.ResearchSet,
        fresh: com.tj.portfolio.data.ResearchSet
    ): com.tj.portfolio.data.ResearchSet {
        if (old.isEmpty) return fresh
        val prior = (old.trending + old.best + old.worst).associateBy { it.symbol }
        if (prior.isEmpty()) return fresh
        fun carry(list: List<com.tj.portfolio.data.ResearchRow>) = list.map { r ->
            val p = prior[r.symbol] ?: return@map r
            // THE INVERSE-ETF MAPPING IS CARRIED TOO, SINCE ROUND 57.
            //
            // Only `why` used to survive a rebuild. `shortVehicle` did not, so every fresh
            // row came back blank, `vehicleDone` was cleared at the same moment, and the
            // early-out in `Research.enrichShortVehicles` never fired - meaning up to twenty
            // Yahoo search requests every thirty minutes, indefinitely, to re-derive that
            // TSLA's inverse ETF is TSLS. That mapping changes perhaps twice a year.
            r.copy(
                why = if (p.why.isNotBlank()) p.why else r.why,
                shortVehicle = if (r.shortVehicle.isBlank()) p.shortVehicle else r.shortVehicle,
                shortVehicleNote =
                    if (r.shortVehicleNote.isBlank()) p.shortVehicleNote else r.shortVehicleNote
            )
        }
        return fresh.copy(
            trending = carry(fresh.trending),
            best = carry(fresh.best),
            worst = carry(fresh.worst),
            explained = old.explained,
            explainedBy = old.explainedBy
        )
    }

    /** Reveal ten more rows of one section, and pay for their lookups - only then. */
    fun showMoreResearch(section: String) {
        val cur = _researchShown.value[section] ?: com.tj.portfolio.data.ResearchSet.PAGE
        val total = _research.value.section(section).size
        if (cur >= total) return
        _researchShown.value = _researchShown.value +
            (section to (cur + com.tj.portfolio.data.ResearchSet.PAGE).coerceAtMost(total))
        enrichVisible()
    }

    fun resetResearchPaging() {
        _researchShown.value = com.tj.portfolio.data.ResearchSet.SECTIONS.associateWith {
            com.tj.portfolio.data.ResearchSet.PAGE
        }
    }

    /**
     * The per-row lookups, for what is on screen and nothing else.
     *
     * Two stages, in this order and for the reason spelled out in [com.tj.portfolio.net.Research]:
     * analyst coverage over a window WIDER than the page (so it can change the ranking), then
     * the inverse-ETF search over the page only (so it is never spent on a row that then
     * dropped off it).
     */
    private fun enrichVisible() {
        if (enrichJob?.isActive == true) return
        val set = _research.value
        if (set.isEmpty) return
        enrichJob = fgScope.launch {
            _researchBusy.value = BUSY_DETAIL
            try {
                // Loop until a pass finds nothing left to do. "Load more" pressed WHILE this
                // is running would otherwise be swallowed by the guard above and the new ten
                // rows would sit there with no analyst data until something else happened to
                // trigger a pass - which on a screen the user is already looking at is never.
                var passes = 0
                while (enrichPass() && passes++ < 6) { /* another window opened up */ }
            } finally {
                // PERSISTED IN THE `finally`, not after the loop. The pass may have paid for
                // up to twenty Nasdaq consensus lookups and twenty Yahoo searches; leaving
                // the app mid-enrich cancels the coroutine, and with the write after the loop
                // none of that reached disk. It survived in memory only until the process was
                // killed, and then the requests had to be spent all over again.
                cacheResearch(_research.value)
                if (_researchBusy.value == BUSY_DETAIL) _researchBusy.value = ""
            }
        }
    }

    /** One sweep over the visible windows. Returns true if it fetched anything. */
    private suspend fun enrichPass(): Boolean {
        var did = false
        for (name in listOf(
            com.tj.portfolio.data.ResearchSet.SECTION_BEST,
            com.tj.portfolio.data.ResearchSet.SECTION_WORST
        )) {
            val bullish = name == com.tj.portfolio.data.ResearchSet.SECTION_BEST
            val rows = _research.value.section(name)
            if (rows.isEmpty()) continue
            val shown = (_researchShown.value[name] ?: com.tj.portfolio.data.ResearchSet.PAGE)
                .coerceAtMost(rows.size)
            val window = (shown + com.tj.portfolio.data.ResearchSet.PAGE).coerceAtMost(rows.size)
            val head = rows.take(window)
            val tail = rows.drop(window)
            val done = analystDone.toSet()
            if (head.any { "$name:${it.symbol}" !in done }) {
                did = true
                val enriched = withContext(Dispatchers.IO) {
                    com.tj.portfolio.net.Research.enrichAnalyst(
                        head, bullish,
                        alreadyDone = head.map { it.symbol }
                            .filter { "$name:$it" in done }.toSet()
                    )
                }
                // THE WHOLE WINDOW IS MARKED, AND THAT IS DELIBERATE - see below.
                //
                // An earlier version of this round marked only rows that came back WITH a
                // consensus, reasoning that a failed lookup should be retried. It made things
                // much worse, because `Research.consensus` also returns null for a stock
                // nobody covers - which is most small caps, and small caps are exactly what
                // the Worst list is full of. An uncovered symbol could then never be marked,
                // so `enrichPass` kept reporting work left to do and `enrichVisible`'s loop
                // ran its full seven passes, firing a fresh Nasdaq request for every
                // uncovered symbol on each one. Twenty-one requests where three were
                // intended, repeated on every "Load more" and every rebuild.
                //
                // A genuinely failed lookup is still retried: `analystDone` is cleared on
                // every research rebuild, which happens on the 30-minute TTL and on any pull
                // to refresh. That is the right cadence for it - unlike a loop that cannot
                // tell "no analyst covers this" from "Nasdaq timed out".
                head.forEach { analystDone.add("$name:${it.symbol}") }
                // Re-rank the enriched window only. The tail keeps its screener ordering,
                // which is what it was ranked on, so nothing can jump from an un-enriched
                // region into a ranked one and look like the list reshuffling itself.
                val ranked = enriched.sortedByDescending { it.score }
                _research.value = _research.value.withSection(name, ranked + tail)
            }

            // Stage two: the short vehicle, page only, after the ranking has settled.
            if (!bullish) {
                val page = _research.value.section(name).take(shown)
                val vdone = vehicleDone.toSet()
                if (page.any { it.symbol !in vdone }) {
                    did = true
                    val withVehicles = withContext(Dispatchers.IO) {
                        com.tj.portfolio.net.Research.enrichShortVehicles(page, alreadyDone = vdone)
                    }
                    page.forEach { vehicleDone.add(it.symbol) }
                    val rest = _research.value.section(name).drop(shown)
                    _research.value = _research.value.withSection(name, withVehicles + rest)
                }
            }
        }
        return did
    }

    fun dismissResearchError() { _researchError.value = null }

    /** The rows currently on screen - what both Claude paths are asked about. */
    private fun visibleResearch(): com.tj.portfolio.data.ResearchSet {
        val s = _research.value
        fun cut(name: String) = s.section(name)
            .take(_researchShown.value[name] ?: com.tj.portfolio.data.ResearchSet.PAGE)
        return s.copy(
            trending = cut(com.tj.portfolio.data.ResearchSet.SECTION_TRENDING),
            best = cut(com.tj.portfolio.data.ResearchSet.SECTION_BEST),
            worst = cut(com.tj.portfolio.data.ResearchSet.SECTION_WORST)
        )
    }

    fun researchBundle(): String = com.tj.portfolio.net.ResearchBridge.bundleJson(
        visibleResearch(),
        heldSymbols().toList().sorted(),
        watchedSymbols().toList().sorted()
    )

    fun researchPromptFile(): String = com.tj.portfolio.net.ResearchBridge.prompt(
        visibleResearch(),
        heldSymbols().toList().sorted(),
        watchedSymbols().toList().sorted()
    )

    /** Write the offline prompt file - one fixed name, replaced, same as the other two. */
    fun writeResearchPrompt(onDone: (String) -> Unit) {
        if (_research.value.isEmpty) {
            onDone("Load the research lists first - there is nothing to ask about yet.")
            return
        }
        viewModelScope.launch {
            val body = researchPromptFile()
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(
                        getApplication(),
                        com.tj.portfolio.net.ResearchBridge.PROMPT_FILE,
                        body,
                        "text/markdown"
                    )
                    if (saved != null) "Saved to ${saved.display}"
                    else "Couldn't write to Downloads"
                }.getOrElse { "Couldn't build the prompt file: ${it.message}" }
            }
            onDone(msg)
        }
    }

    /** The API-key path: same question, same parser, no file round trip. */
    fun explainResearch() {
        val key = claudeKey()
        if (key.isBlank()) {
            _researchError.value =
                "Add your Claude API key in Settings, or use \"Make prompt file\" to do this " +
                    "through the Claude app instead - both give the same answer."
            return
        }
        if (_research.value.isEmpty) {
            _researchError.value = "Load the research lists first."
            return
        }
        if (_researchBusy.value.isNotEmpty()) return
        viewModelScope.launch {
            _researchBusy.value = BUSY_EXPLAINING
            _researchError.value = null
            try {
                val bundle = researchBundle()
                val chosen = ensureModel()
                var out = withContext(Dispatchers.IO) {
                    runCatching { Claude.research(key, chosen, bundle, webSearch()) }
                        .getOrElse {
                            com.tj.portfolio.net.ResearchBridge.Parsed(
                                error = it.message ?: "Request failed"
                            )
                        }
                }
                if (out.error?.contains("404") == true) {
                    val list = withContext(Dispatchers.IO) {
                        runCatching { Claude.models(key) }.getOrDefault(emptyList())
                    }
                    if (list.isNotEmpty()) {
                        val fresh = Claude.preferredModel(list)
                        db.set(Keys.CLAUDE_MODEL, fresh)
                        _models.value = list
                        out = withContext(Dispatchers.IO) {
                            runCatching { Claude.research(key, fresh, bundle, webSearch()) }
                                .getOrElse {
                                    com.tj.portfolio.net.ResearchBridge.Parsed(
                                        error = it.message ?: "Request failed"
                                    )
                                }
                        }
                    }
                }
                applyResearchAnswer(out, "API")
            } finally {
                _researchBusy.value = ""
            }
        }
    }

    /** The offline path's other half: an answer file picked in the Research section. */
    fun importResearchFile(text: String): String {
        val parsed = runCatching { com.tj.portfolio.net.ResearchBridge.parse(text) }
            .getOrElse {
                return "Couldn't read that file: ${it.message}"
            }
        return applyResearchAnswer(parsed, "Claude app")
    }

    private fun applyResearchAnswer(
        parsed: com.tj.portfolio.net.ResearchBridge.Parsed,
        via: String
    ): String {
        if (parsed.error != null) {
            _researchError.value = parsed.error
            return parsed.error
        }
        val cur = _research.value
        val merged = cur.copy(
            trending = com.tj.portfolio.net.ResearchBridge.merge(cur.trending, parsed.trending),
            best = com.tj.portfolio.net.ResearchBridge.merge(cur.best, parsed.best),
            worst = com.tj.portfolio.net.ResearchBridge.merge(cur.worst, parsed.worst),
            explained = System.currentTimeMillis(),
            explainedBy = via,
            notes = parsed.notes,
            // Claude may have named the inverse ETF the app could not find, and that answer
            // must not be overwritten by a later "no listed fund shorts this" lookup.
            generated = cur.generated
        )
        cacheResearch(merged)
        parsed.worst.filter { it.shortVehicle.isNotBlank() }.forEach { vehicleDone.add(it.symbol) }
        _researchError.value = null
        val added = (parsed.trending + parsed.best + parsed.worst)
            .count { it.symbol !in (cur.trending + cur.best + cur.worst).map { r -> r.symbol } }
        // Anything Claude ADDED has no price yet - fetch those quotes so the new rows are
        // not the only ones on screen without a number beside them.
        val newSymbols = (merged.trending + merged.best + merged.worst)
            .filter { it.price <= 0.0 }.map { it.symbol }.distinct().take(20)
        if (newSymbols.isNotEmpty()) fillResearchPrices(newSymbols)
        val n = parsed.trending.size + parsed.best.size + parsed.worst.size
        return "Research updated - $n explained" + (if (added > 0) ", $added added" else "")
    }

    /** Quotes for rows Claude introduced, so every row on screen carries a live price. */
    private fun fillResearchPrices(symbols: List<String>) {
        fgScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                val gate = Semaphore(4)
                symbols.map { s ->
                    async {
                        gate.withPermit {
                            s to runCatching { MarketData.quote(s, finnhubKey()) }.getOrNull()
                        }
                    }
                }.awaitAll().mapNotNull { (s, q) -> if (q == null) null else s to q }.toMap()
            }
            if (fetched.isEmpty()) return@launch
            fun fill(list: List<com.tj.portfolio.data.ResearchRow>) = list.map { r ->
                val q = fetched[r.symbol]
                if (q == null || q.price <= 0.0) r
                else r.copy(
                    name = r.name.ifBlank { q.name },
                    price = q.price,
                    changePct = q.dayChangePct
                )
            }
            val s = _research.value
            cacheResearch(
                s.copy(trending = fill(s.trending), best = fill(s.best), worst = fill(s.worst))
            )
        }
    }

    // -------------------------------------------------------------- backup

    fun exportJson(): String = db.exportJson()

    /** The same export, read off the main thread; the callback runs back on it. */
    fun exportJsonAsync(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { db.exportJson() }.getOrDefault("")
            }
            onDone(json)
        }
    }

    data class BackupOutcome(val ok: Boolean, val message: String)

    /**
     * Writes a backup to Downloads on a background thread, then READS THE FILE BACK and
     * re-parses it, comparing every section against what is currently in the database.
     * The success message is only shown once the file on disk has been proven complete.
     */
    fun backupToDownloads(onDone: (BackupOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val json = runCatching { db.exportJson() }.getOrNull()
                    ?: return@withContext BackupOutcome(false, "Couldn't read your data")

                val expected = runCatching { JSONObject(json).optJSONObject("counts") }.getOrNull()
                val name = "portfolio-backup-" + com.tj.portfolio.util.Storage.stamp() + ".json"
                val saved = com.tj.portfolio.util.Storage.saveToDownloads(
                    getApplication(), name, json, "application/json"
                ) ?: return@withContext BackupOutcome(false, "Couldn't write to Downloads")

                // read the file back off disk and check it against the live database
                val readBack = saved.uri?.let {
                    com.tj.portfolio.util.Storage.readText(getApplication(), it)
                }
                if (readBack.isNullOrBlank()) {
                    return@withContext BackupOutcome(
                        false, "Wrote ${saved.display} but couldn't read it back to verify"
                    )
                }
                val got = runCatching { JSONObject(readBack) }.getOrNull()
                    ?: return@withContext BackupOutcome(
                        false, "The file written to ${saved.display} isn't readable"
                    )

                val liveTxns = db.allTxns().size
                val fileTxns = got.optJSONArray("transactions")?.length() ?: -1
                val fileWatch = got.optJSONArray("watchlist")?.length() ?: -1
                val fileOv = got.optJSONArray("overrides")?.length() ?: -1
                val fileImp = got.optJSONArray("imports")?.length() ?: -1
                val liveWatch = db.watchlist().size
                val liveOv = db.overrides().size
                val liveImp = db.importCount()

                val problems = buildList {
                    if (fileTxns != liveTxns) add("transactions $fileTxns/$liveTxns")
                    if (fileWatch != liveWatch) add("watchlist $fileWatch/$liveWatch")
                    if (fileOv != liveOv) add("overrides $fileOv/$liveOv")
                    if (fileImp != liveImp) add("imports $fileImp/$liveImp")
                    if (expected?.optInt("transactions", -1) != fileTxns) add("manifest mismatch")
                }
                if (problems.isNotEmpty()) {
                    BackupOutcome(false, "Backup incomplete - " + problems.joinToString(", "))
                } else {
                    noteBackup(saved.display)
                    BackupOutcome(
                        true,
                        "Verified: $fileTxns transactions, $fileOv override(s), " +
                            "$liveWatch watchlist, $fileImp import record(s) -> ${saved.display}"
                    )
                }
            }
            onDone(outcome)
        }
    }

    fun noteBackup(where: String) = db.set(Keys.LAST_BACKUP, "${Fmt.day(System.currentTimeMillis())} - $where")

    // ---------------------------------------------------------- crash log IO
    //
    // The crash log can be 60KB, and writing it to Downloads goes through MediaStore. Both
    // ran on the main thread straight out of the Settings buttons; they belong on IO like
    // every other file path in this class.

    /** Writes the crash log to Downloads off the main thread; the callback runs back on it. */
    fun saveCrashLog(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val text = com.tj.portfolio.util.CrashLog.read(app)
                    if (text.isBlank()) return@runCatching "Nothing in the crash log yet"
                    val name = "portfolio-crash-" + com.tj.portfolio.util.Storage.stamp() + ".txt"
                    val saved = com.tj.portfolio.util.Storage.saveToDownloads(
                        app, name, text, "text/plain"
                    )
                    if (saved != null) "Saved to ${saved.display}" else "Couldn't write to Downloads"
                }.getOrElse { "Couldn't save the crash log: ${it.message}" }
            }
            onDone(msg)
        }
    }

    /** Reads the crash log off the main thread, for the clipboard button. */
    fun readCrashLog(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    com.tj.portfolio.util.CrashLog.read(getApplication())
                }.getOrDefault("")
            }
            onDone(text)
        }
    }

    /**
     * ONE-TIME TIDY-UP of the Downloads root (v5.6).
     *
     * Everything the app writes now lives in Downloads/Portfolio. This clears what earlier
     * builds left loose in the root: the pre-v1.7 dated auto-backups, one prompt file per
     * time the button was ever tapped, and the fixed `portfolio-autosave.json` from v4.7.
     *
     * THE ORDERING IS THE POINT. That autosave is the copy that survives the app being
     * uninstalled - for a stretch it was the only off-device copy of TJ's portfolio. So it
     * is deleted ONLY after a replacement has been written to the new folder AND that
     * replacement has been read back and confirmed to parse. If anything fails, the root
     * copy is left exactly where it is and the flag is not set, so the next launch tries
     * again. A tidier folder is never worth even a small chance of deleting the backup.
     */
    private fun tidyDownloadsOnce() {
        if (db.getB(Keys.DOWNLOADS_TIDIED, false)) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val store = com.tj.portfolio.util.Storage
            var removed = 0

            // Prompt files and the ancient dated auto-backups are regenerable, so they go
            // regardless - but only from the ROOT, never from the app's own folder.
            removed += runCatching {
                store.deleteOwnDownloads(app, "portfolio-autobackup-", null)
            }.getOrDefault(0)
            removed += runCatching {
                store.deleteOwnDownloads(app, "claude-advice-prompt-", null)
            }.getOrDefault(0)
            removed += runCatching {
                store.deleteOwnDownloads(app, "claude-screenshot-prompt-", null)
            }.getOrDefault(0)

            // The autosave: replace first, verify, and only then remove the old one.
            var movedAutosave = false
            if (db.txnCount() > 0) {
                val json = runCatching { db.exportJson() }.getOrNull()
                if (json != null) {
                    val written = runCatching {
                        store.saveOrReplaceInDownloads(app, AUTOSAVE_FILE, json)
                    }.getOrNull()
                    // Read it back from the NEW location and make sure it is really there and
                    // really parses before touching the old one.
                    val verified = written != null &&
                        store.ownDownloadExists(app, AUTOSAVE_FILE) &&
                        runCatching {
                            val back = store.readOwnDownload(app, AUTOSAVE_FILE)
                            !back.isNullOrBlank() &&
                                JSONObject(back).optJSONArray("transactions") != null
                        }.getOrDefault(false)
                    if (verified) {
                        db.set(Keys.AUTOSAVE_AT, System.currentTimeMillis().toString())
                        movedAutosave = runCatching {
                            store.deleteOwnDownloadFile(app, AUTOSAVE_FILE, null)
                        }.getOrDefault(false)
                        if (movedAutosave) removed++
                    }
                }
            } else {
                // AN EMPTY LEDGER IS NOT PERMISSION TO DELETE A BACKUP - IT IS THE REASON NOT TO.
                //
                // This branch used to delete the root autosave outright, on the reasoning that
                // with nothing on file there was nothing to protect. That is exactly backwards.
                // The state "no transactions AND an autosave sitting in Downloads" is what a
                // phone looks like immediately after the app has been uninstalled and put back,
                // or its storage cleared - the private snapshots are gone with it, the tidy flag
                // has reset with the new database, and that file is the last copy of the
                // portfolio. The app would have run on first launch and deleted it before the
                // user had any chance to restore from it.
                //
                // So it is left alone, and `checkForRecoverableBackup()` offers it instead.
                movedAutosave = false
            }

            // Only mark it done once the autosave question is settled one way or the other,
            // so a failure here is retried on the next launch instead of being forgotten.
            // An empty ledger settles it too: the root copy is being kept deliberately, and
            // re-running this every launch would just repeat a MediaStore query for nothing.
            val autosaveSettled = movedAutosave || db.txnCount() == 0 ||
                !store.ownDownloadExists(app, AUTOSAVE_FILE, null)
            if (autosaveSettled) {
                db.setB(Keys.DOWNLOADS_TIDIED, true)
                db.setB(Keys.AUTOBAK_CLEANED, true)
            }
            if (removed > 0) _toast.value =
                "Tidied $removed file(s) out of Downloads - the app's files are in " +
                    "Downloads/Portfolio now"
        }
    }

    fun snapshotCount(): Int =
        com.tj.portfolio.util.Storage.appBackups(getApplication()).size

    /** Newest private snapshot, for the Settings restore button. */
    fun latestSnapshotJson(): String? =
        com.tj.portfolio.util.Storage.appBackups(getApplication())
            .firstOrNull()
            ?.let { runCatching { it.readText() }.getOrNull() }

    fun autoBackupOn() = db.getB(Keys.AUTO_BACKUP, true)
    fun setAutoBackup(on: Boolean) {
        db.setB(Keys.AUTO_BACKUP, on)
        if (on) autoBackupIfDue(force = true)
    }

    fun lastAutoBackup(): Long = db.get(Keys.AUTO_BACKUP_AT).toLongOrNull() ?: 0L

    /**
     * Writes a dated snapshot at most once a day into the app's own private folder -
     * NOT Downloads, so it never clutters the user's files. Silent, best-effort, never
     * blocks the UI. Only the Backup button in Settings writes a visible file.
     */
    /**
     * The automatic safety net, written to TWO places.
     *
     * 1. A dated snapshot in the app's private folder - a rolling history, but that folder
     *    is deleted along with the app, so it protects against a mistake and nothing else.
     * 2. ONE fixed file in Downloads, replaced each time. This is the copy that survives
     *    uninstalling the app, wiping its storage, or moving to another phone - and until
     *    now the ONLY thing that ever wrote such a copy was the user remembering to press
     *    the Backup button.
     *
     * Both are skipped when there is nothing to save, so an empty ledger can never
     * overwrite a good backup with nothing.
     */
    fun autoBackupIfDue(force: Boolean = false) {
        if (!autoBackupOn()) return
        if (db.txnCount() == 0) return
        val last = maxOf(lastAutoBackup(), db.get(Keys.AUTOSAVE_AT).toLongOrNull() ?: 0L)
        val dayMs = 24 * 60 * 60 * 1000L
        if (!force && System.currentTimeMillis() - last < dayMs) return
        viewModelScope.launch(Dispatchers.IO) {
            val json = runCatching { db.exportJson() }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()

            val name = "portfolio-autobackup-" + com.tj.portfolio.util.Storage.stamp() + ".json"
            val saved = runCatching {
                com.tj.portfolio.util.Storage.saveToAppFolder(getApplication(), name, json)
            }.getOrNull()
            if (saved != null) db.set(Keys.AUTO_BACKUP_AT, now.toString())

            val pub = runCatching {
                com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(
                    getApplication(), AUTOSAVE_FILE, json
                )
            }.getOrNull()
            if (pub != null) db.set(Keys.AUTOSAVE_AT, now.toString())
        }
    }

    fun lastAutosave(): Long = db.get(Keys.AUTOSAVE_AT).toLongOrNull() ?: 0L

    /** Read the uninstall-proof copy back, for the recovery banner's one-tap restore. */
    fun readAutosave(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    com.tj.portfolio.util.Storage.readOwnDownload(getApplication(), AUTOSAVE_FILE)
                }.getOrNull()
            }
            onDone(text)
        }
    }

    /**
     * Restore, off the main thread.
     *
     * The blocking [restore] parses the whole backup and rewrites every table inside one
     * SQLite transaction. Called straight from a dialog button, as it was, that is all
     * happening on the UI thread - a big backup freezes the app for as long as it takes,
     * which on Android is how a restore turns into an "app isn't responding" dialog.
     */
    fun restoreAsync(json: String, replace: Boolean, onDone: (Db.RestoreResult) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { db.restoreJson(json, replace) }
                    .getOrElse { Db.RestoreResult(error = "Restore failed: ${it.message}") }
            }
            if (r.error == null) {
                _lastImport.value = withContext(Dispatchers.IO) { db.lastImport() }
                recompute()
                refresh()
            }
            onDone(r)
        }
    }

    /**
     * Read a file the user picked, off the main thread. [Storage.readText] will happily pull
     * several megabytes through the content resolver, which is not something to do on the
     * UI thread just because it happens inside a picker callback.
     */
    fun readPickedFile(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    com.tj.portfolio.util.Storage.readText(getApplication(), uri)
                }.getOrNull()
            }
            onDone(text)
        }
    }

    /** The newest private snapshot, read off the main thread. */
    fun latestSnapshotAsync(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { latestSnapshotJson() }.getOrNull()
            }
            onDone(json)
        }
    }

    fun wipeTransactions() {
        db.deleteAllTxns()
        // An intentional wipe resets the high-water mark, otherwise the app would then
        // insist for ever that data had gone missing.
        db.set(Keys.LAST_TXN_COUNT, "0")
        _dataMissing.value = false
        recompute()
    }

    private fun r2(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0 else Math.round(v * 100.0) / 100.0

    // ------------------------------------------------------ advice caching

    private fun adviceToJson(a: Advice): JSONObject {
        val o = JSONObject()
        o.put("summary", a.summary)
        o.put("risks", a.risks)
        o.put("generated", a.generated)
        o.put("actions", JSONArray(a.actions))
        val arr = JSONArray()
        a.stocks.forEach {
            arr.put(JSONObject().apply {
                put("symbol", it.symbol); put("rating", it.rating); put("action", it.action)
                put("reasoning", it.reasoning); put("target", it.target)
            })
        }
        o.put("stocks", arr)
        return o
    }

    private fun adviceFromJson(o: JSONObject): Advice {
        val acts = ArrayList<String>()
        val aa = o.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until aa.length()) acts.add(aa.optString(i))
        val stocks = ArrayList<StockRating>()
        val sa = o.optJSONArray("stocks") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val s = sa.optJSONObject(i) ?: continue
            stocks.add(
                StockRating(
                    s.optString("symbol"), s.optInt("rating"), s.optString("action"),
                    s.optString("reasoning"), s.optString("target")
                )
            )
        }
        return Advice(
            summary = o.optString("summary"),
            actions = acts,
            stocks = stocks,
            risks = o.optString("risks"),
            generated = o.optLong("generated")
        )
    }
}
