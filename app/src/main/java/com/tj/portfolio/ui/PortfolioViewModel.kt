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
import com.tj.portfolio.data.Recommendation
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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
     * IS THIS SYMBOL ON THE WATCHLIST - separately from whether it is HELD (Round 66 audit,
     * PUI-7).
     *
     * [watchOnly] answers "which list does this row belong to", and the row menu was reading
     * it as if it answered "is this watched". Those come apart exactly when a symbol is both:
     * `recompute` emits a held-and-watched symbol ONCE, from the positions loop, with
     * `watchOnly = false`, and the watch loop then skips it. So on the Portfolio tab the menu
     * offered "Also watch this" for a stock already on the watchlist, adding it again and
     * toasting that it had - and there was no way to take it off without selling out of the
     * position first.
     */
    val watched: Boolean = false,
    /**
     * Names the trading session the day figures describe, but ONLY when that is not the
     * current calendar day - blank during market hours. Overnight and at weekends the
     * quote still reports the previous session, so a row saying "you made today" at 2am is
     * describing yesterday. Now it says so.
     */
    val sessionLabel: String = "",
    /** When this symbol was added to the watchlist. 0 when it was never watched. */
    val watchedAt: Long = 0L,
    /**
     * The %-since-added baseline: the close on the trading day [watchedAt] falls on, resolved
     * once by [PortfolioViewModel] and never recomputed. 0.0 before it has been resolved -
     * see [sinceWatchedPct] for what that means on screen.
     */
    val watchedBasePrice: Double = 0.0
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

    /**
     * How much the price has moved since [watchedAt], EXCLUDING the day it was added -
     * Tj's own requirement, and [watchedBasePrice] is what makes it true rather than a
     * disclaimer: it is the CLOSE of the day added, not the price at the moment of the tap,
     * so that whole day's move (before or after adding, mid-session) is already baked into
     * the reference point and only the next session onward shows up here.
     *
     * Null, not 0.0, while the baseline is not resolved yet - added today, before that
     * session's close exists, or a resolution pass has not run. 0.0 would be indistinguishable
     * from "unchanged since added," which is a claim this has no data to support yet.
     */
    val sinceWatchedPct: Double?
        get() = if (watchedBasePrice > 0.0 && price > 0.0)
            (price - watchedBasePrice) / watchedBasePrice * 100.0 else null
}

data class UiState(
    val rows: List<Row> = emptyList(),
    val totals: PortfolioTotals? = null,
    val loading: Boolean = false,
    /**
     * Whether P/L figures lead with dollars or with a percentage (Round 61).
     *
     * ON `UiState` RATHER THAN A PLAIN `var` LIKE `sortMode`. That one works as a bare
     * property only because changing it also changes the ROWS, which republishes this object
     * and recomposes everything by itself. Switching this changes no data at all - it changes
     * which of two numbers is bold - so a bare property would be written, persisted, and
     * simply not redraw until something else happened to.
     */
    val plMode: com.tj.portfolio.data.PlMode = com.tj.portfolio.data.PlMode.DOLLAR,
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

/** The ETF pass. Its own state, because it is a different ten requests on its own clock. */
const val BUSY_ETFS = "etfs"

/** Backoff keys for the two Research builds. See `PortfolioViewModel.researchRetry`. */
private const val RETRY_STOCKS = "research"
private const val RETRY_ETFS = "etfs"

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
/**
 * Whether the Feed's headline LIST is on screen (full test 2026-09-24, U-3). The tab being
 * selected is not enough: an article in the reader or a stock opened from a Feed row covers
 * the list, and while it did, every feed tick still pulled every followed symbol's headlines
 * plus the seven market-wide feeds - about 25 requests every three minutes for a list nobody
 * could see. Coming back uncovers it, and `setNewsVisible`'s arriving edge refreshes it if due.
 */
internal fun feedListVisible(onFeedTab: Boolean, detail: String?, readerOpen: Boolean): Boolean =
    onFeedTab && detail == null && !readerOpen

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
fun spinnerShouldShow(
    userAsked: Boolean,
    quotesLoading: Boolean,
    feedLoading: Boolean,
    /**
     * The Research and ETF builds (Round 66).
     *
     * THE BUG THIS FIXES. This function knew about two of the three kinds of work that set
     * `manualRefresh`. Pulling down on the Research tab sets it with `PULL_RESEARCH` and
     * starts an ~18-request build - but with quotes and feed both idle, `want` came out false,
     * and the polling loop calls `syncManualIndicator()` on every tick. So the indicator was
     * retracted after one quote tick (fifteen seconds with the market open) while the build
     * was still running, and the screen showed no pull feedback for the rest of it.
     */
    researchLoading: Boolean = false,
    /**
     * WHICH SURFACE WAS PULLED (full test 2026-09-24, U-1). Null keeps the old surface-blind
     * rule, for callers that have no surface.
     *
     * THE BUG THIS FIXES. With no surface the rule was "asked AND anything at all running",
     * so a pull on Portfolio kept spinning after its own quote pass had landed ("Prices updated
     * just now" beside a circle that would not go away - the symptom in Tj's 09-23c screenshot)
     * for as long as ANY feed or research job ran: an "Explain with Claude" answer, the
     * analyst pass behind a detail screen, an automatic ETF build. And those research jobs end
     * without re-deriving the flag, so it then waited for the poll loop's next tick - up to
     * fifteen minutes with the market shut. Each surface now waits only on its OWN work.
     */
    source: String? = null
): Boolean = userAsked && when (source) {
    PULL_PRICES -> quotesLoading
    PULL_FEED -> feedLoading
    PULL_RESEARCH -> researchLoading
    else -> quotesLoading || feedLoading || researchLoading
}

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

/**
 * Per-stock news list cap, for the same reason [MAX_FEED_ITEMS] exists.
 *
 * ONE CONSTANT, NOT TWO (Round 66). There used to be a second one - `MAX_NEWS_PER_SYMBOL`,
 * 40 - applied by `mergeNews` to the same `_news[symbol]` entry, with a KDoc describing the
 * same thing in the same words. The smaller number therefore won by accident: open a stock,
 * get up to 60 headlines from the deep multi-source pull, and one feed interval later the
 * merge pass silently removed the bottom 20 from the list being scrolled. A pass whose whole
 * purpose is to ADD stories was deleting them.
 */
private const val MAX_SYMBOL_NEWS = 60

/**
 * How many priceless research rows one fill pass will ask about.
 *
 * It is a cap on ONE batched `MarketData.quotes` call, not on requests: twenty symbols go up
 * in a single URL. The number exists so a research list that came back mostly unresolvable -
 * a burst of typo'd Reddit tickers, say - cannot turn the fill into a chunked sweep, and
 * because the rows are handed over already sorted by score, so the twenty that get a price
 * are the twenty nearest the top of the list anyone is actually looking at.
 */
private const val MAX_PRICE_FILL = 20

/** How often [PortfolioViewModel.enrichDayTradingVisible] re-fetches technicals for the
 *  visible Day Trading window, while [PortfolioViewModel.startDayTradingLive]'s loop runs at all. */
private const val DAY_TRADING_LIVE_INTERVAL_MS = 30_000L

/**
 * The same loop's heartbeat once the market is shut.
 *
 * Not zero, because the tab can be left open across the bell and the loop has to notice the
 * open without being restarted. Five minutes is well inside the slack that matters for that
 * and is 10x cheaper than pretending it is a trading session all night.
 */
private const val DAY_TRADING_LIVE_CLOSED_INTERVAL_MS = 300_000L

/** The live Day Trading loop writes the research cache at most this often - see cacheResearch. */
private const val DAY_TRADING_PERSIST_MS = 5 * 60_000L

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
 * EDGAR's own pagination step (100 entries/page) times [Insider.MAX_MARKET_PAGES_PER_PASS]-
 * worth of pages, advanced only once a window is fully drained (see
 * [PortfolioViewModel.loadMoreMarketInsiders] and [Insider.MarketResult.remaining]) rather
 * than on every "Load more" tap. Kept in sync with that constant by naming rather than
 * importing it - `Insider`'s is `private`, on purpose, since it is a fetch-budget detail the
 * ViewModel should not need to know the number to use this correctly, only to advance past
 * whatever window it just finished.
 */
private const val MARKET_INSIDER_PAGE_ADVANCE = 300

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
 * How long a completed per-symbol EDGAR pass stands for.
 *
 * Thirty minutes, matching `INSIDER_REFRESH_SECS` - and for the same reason it was chosen:
 * Form 4 is legally up to two business days behind, so asking more often cannot return
 * anything that did not already exist.
 */
private const val INSIDER_SYMBOL_TTL_MS = 30 * 60 * 1000L

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

/** The one fixed file in Downloads that survives the app being uninstalled. */
private const val AUTOSAVE_FILE = "portfolio-autosave.json"

/**
 * WHICH SNAPSHOT "RESTORE THE LATEST AUTOMATIC SNAPSHOT" MEANS (full test 2026-09-23, A-4).
 * [files] newest first. The newest file, EXCEPT right after a destructive action: a Replace
 * restore (and any import) forces a fresh daily snapshot seconds after the "before" copy was
 * taken, and that newer file holds the state the undo is meant to undo - so the before-copy
 * shadowed by a daily snapshot written within a MINUTE of it is the one returned. (Ten minutes
 * was too wide - diff review R-10: an import a few minutes after a delete is real work, and the
 * undo would have skipped it.)
 */
internal fun pickUndoSnapshot(files: List<java.io.File>, windowMs: Long = 60_000L): java.io.File? {
    val newest = files.firstOrNull() ?: return null
    if (newest.name.startsWith(com.tj.portfolio.util.Storage.BEFORE_PREFIX)) return newest
    val before = files.firstOrNull { it.name.startsWith(com.tj.portfolio.util.Storage.BEFORE_PREFIX) }
        ?: return newest
    return if (newest.lastModified() - before.lastModified() <= windowMs) before else newest
}

/** The larger autosave, kept when a new one would have fewer transactions - see A-1. */
private const val AUTOSAVE_PREVIOUS_FILE = "portfolio-autosave-previous.json"

/**
 * How many transactions a backup/autosave file holds: its manifest's count, else the length
 * of its `transactions` array; -1 when [json] is missing or unreadable.
 */
internal fun backupTxnCount(json: String?): Int {
    if (json.isNullOrBlank()) return -1
    return runCatching {
        val root = JSONObject(json)
        root.optJSONObject("counts")?.optInt("transactions", -1)?.takeIf { it >= 0 }
            ?: root.optJSONArray("transactions")?.length() ?: -1
    }.getOrDefault(-1)
}

/**
 * The prompt files, at FIXED names so they are replaced rather than accumulated. One tap of
 * "Make prompt file" used to leave one more `claude-advice-prompt-<stamp>.md` in Downloads
 * for ever; TJ's folder had collected several. A single file is also one fewer way to pick
 * the wrong one in the chooser - which is the v5.2 bug this app already has scar tissue for.
 */
private const val ADVICE_PROMPT_FILE = "claude-advice-prompt.md"
private const val SCREENSHOT_PROMPT_FILE = "claude-screenshot-prompt.md"

/**
 * What a "Make prompt file" tap produced (2026-09-23b): the URI the share sheet hands to the
 * Claude app, and the message to show if the share sheet cannot open - by then the Downloads
 * copy, written as before, is the fallback, and [message] says where it is.
 */
data class PromptOut(val message: String, val share: Uri?)

/** Which screen a shared-in Claude answer belongs on - see [PortfolioViewModel.importShared]. */
enum class ShareDest { ADVICE, ACTIVITY, RESEARCH, DAY_TRADING }

/** The outcome of one shared-in answer: the toast, and where to go (null = stay put). */
data class ShareImport(val message: String, val dest: ShareDest?)


// ==================================================================== research carry-over
//
// TOP-LEVEL AND PURE. Both functions are functions of their arguments alone - no ViewModel
// state, no clock, no database - and they hold the two rules that decide what survives a
// rebuild. Reaching them from a test through the ViewModel would mean constructing an
// `AndroidViewModel`, which opens the database in `init`; as free functions they can simply
// be called. That matters because one of them is the fix for the worst bug of this round:
// the thirty-minute stock rebuild was silently wiping the six-hour fund list.

/**
 * HOW LONG CLAUDE'S PARAGRAPH COUNTS AS CURRENT ADVICE, NOT JUST A NON-BLANK STRING.
 *
 * Tj: "I haven't run the Claude analysis in weeks... nothing should be recommended on stale
 * data or stale Claude advice or old cached recommendations... if Claude advice hasn't been
 * used in a while, the app should remove it from cache." Until this fix, `why`/`catalyst`
 * carried forward across every rebuild on nothing but a blank check - a paragraph written
 * once rode along by symbol match for as long as that symbol kept reappearing, with nothing
 * on screen distinguishing it from one written five minutes ago.
 *
 * 14 days is long enough that a normal multi-day gap between sessions never throws away
 * advice that is still perfectly usable, and short enough that a company's story cannot ride
 * unchallenged for a month. [com.tj.portfolio.data.ResearchRow.whyAt] of 0 - blank `why`, or a
 * row cached before this field existed - fails this check unconditionally: unknown age is
 * never treated as current.
 */
internal const val WHY_STALE_MS = 14L * 24 * 3_600_000L

/** True while [why] is both present and recent enough to still count as current advice. */
private fun stillCurrent(why: String, whyAt: Long, now: Long) =
    why.isNotBlank() && whyAt > 0 && now - whyAt <= WHY_STALE_MS

/**
 * THE BATCH-LEVEL COUNTERPART TO [stillCurrent] (full-tests audit, round 79 sweep).
 *
 * `explained`/`dtExplained` are single timestamps for the WHOLE last "Explain with Claude"
 * pass - `notes`/`dtNotes` is Claude's own paragraph about the data, `explainedBy`/
 * `dtExplainedBy` says "API" or "Claude app". Until this fix these three carried forward
 * across every rebuild and every cold launch exactly the way the per-row `why` text used to:
 * unconditionally, forever, by nothing but "was it ever set". A pass run months ago left a
 * "Claude's note on the app's data" card and an "Explained ~X ago via API" line on screen
 * for the rest of the app's life. Past [WHY_STALE_MS] this drops all three back to blank/0 -
 * the same eviction `stillCurrent` gives a single row's `why`.
 */
private fun carryExplainedStamp(
    explained: Long,
    explainedBy: String,
    notes: String,
    now: Long
): Triple<Long, String, String> =
    if (explained > 0 && now - explained <= WHY_STALE_MS) Triple(explained, explainedBy, notes)
    else Triple(0L, "", "")

/**
 * BLANK OUT ANY [com.tj.portfolio.data.ResearchRow.why] THAT HAS ALREADY GONE STALE.
 *
 * `carryExplanations`/`carryEtfExplanations` only ever run when a REBUILD happens, which is
 * every 30 minutes for stocks and 6 hours for funds while the app is in active use - but the
 * whole reason this fix exists is a gap that long between rebuilds: Tj hadn't opened the app
 * (or at least not this tab) in weeks, so a cold launch restores the persisted cache straight
 * from disk with no rebuild in between at all, per [loadCachedResearch]. Without this, a
 * paragraph that was already weeks stale before this feature existed would sit on screen,
 * looking exactly like current advice, until the next TTL-driven rebuild happened to run.
 */
internal fun evictStaleWhy(
    set: com.tj.portfolio.data.ResearchSet,
    now: Long = System.currentTimeMillis()
): com.tj.portfolio.data.ResearchSet {
    // A PARAGRAPH THAT WENT STALE TAKES CLAUDE'S OTHER WORDS WITH IT (full test 2026-09-24,
    // S-6): its conviction badge and its catalyst text are the same answer, just as old. And a
    // Day Trading paragraph is about ONE session (S-7) - "worth trading TODAY" - so it expires
    // with that session, not after fourteen days.
    fun expired(r: com.tj.portfolio.data.ResearchRow, dayTrading: Boolean) = r.why.isNotBlank() &&
        (!stillCurrent(r.why, r.whyAt, now) || (dayTrading && !sameTradingDay(r.whyAt, now)))
    fun scrub(list: List<com.tj.portfolio.data.ResearchRow>, dayTrading: Boolean = false) = list.map { r ->
        if (!expired(r, dayTrading)) r
        else r.copy(why = "", whyAt = 0L, conviction = 0,
            catalyst = com.tj.portfolio.net.Research.appEarningsPart(r.catalyst) ?: "")
    }
    // A FUND CLAUDE ADDED IS DROPPED OUTRIGHT once its paragraph is stale (S-6). Blanked to a
    // category-only row, `carryEtfExplanations` kept it on every later rebuild - a three-week-old
    // "CLAUDE 9/10" on the list Tj buys from, forever. The app's own funds only lose Claude's
    // paragraph and badge; their category is the app's and stays.
    fun scrubEtfs(list: List<com.tj.portfolio.data.ResearchRow>) = list.mapNotNull { r ->
        when {
            !expired(r, false) -> r
            r.etf == null -> null
            else -> r.copy(why = "", whyAt = 0L, conviction = 0)
        }
    }
    // THE BATCH-LEVEL "Explained via Claude" STAMP GOES STALE ON A COLD LAUNCH TOO - see
    // [carryExplainedStamp]'s own header.
    val (explainedAt, explainedVia, note) = carryExplainedStamp(set.explained, set.explainedBy, set.notes, now)
    val (dtExplainedAt, dtExplainedVia, dtNote) =
        carryExplainedStamp(set.dtExplained, set.dtExplainedBy, set.dtNotes, now)
    return set.copy(
        trending = scrub(set.trending),
        best = scrub(set.best),
        dayTrading = scrub(set.dayTrading, dayTrading = true),
        etfs = scrubEtfs(set.etfs),
        notes = note,
        explained = explainedAt,
        explainedBy = explainedVia,
        dtNotes = dtNote,
        dtExplained = dtExplainedAt,
        dtExplainedBy = dtExplainedVia
    )
}

/** Keep the imported explanation for any fund that survived into a fresh ranking. */
internal fun carryEtfExplanations(
    old: List<com.tj.portfolio.data.ResearchRow>,
    fresh: List<com.tj.portfolio.data.ResearchRow>,
    now: Long = System.currentTimeMillis()
): List<com.tj.portfolio.data.ResearchRow> {
    if (old.isEmpty()) return fresh
    val prior = old.associateBy { it.symbol }
    val carried = fresh.map { r ->
        val p = prior[r.symbol] ?: return@map r
        if (!stillCurrent(p.why, p.whyAt, now)) return@map r
        r.copy(why = p.why, whyAt = p.whyAt)
    }
    // FUNDS CLAUDE ADDED SURVIVE A REBUILD TOO.
    //
    // A fund that is not in Yahoo's screens can never come back in a fresh pass - that is
    // the whole reason the online research exists - so dropping the ones Claude found
    // would silently undo the import six hours later, and the user would have to redo the
    // file round trip to get them back. They are kept, still carrying the app's "not in
    // the app's own screen" marker, and re-ranked into place by their existing score.
    //
    // BUT NOT PAST THE STALENESS WINDOW - ONLY WHEN THERE IS A `why` PARAGRAPH TO GO STALE.
    // `whyAt` now tracks specifically when `why` was last written (full-tests audit, round
    // 79 sweep - see [ResearchBridge.merge]'s note), not "any Claude content", so a
    // category-only row's `whyAt` is always 0 and would otherwise fail `it.whyAt > 0`
    // immediately - dropping it the moment it was added, not once it went stale. A category
    // ("US dividend equity") isn't time-sensitive advice the way a paragraph explaining why
    // to buy AT TODAY'S PRICE is, so it is kept unconditionally, same as before this session.
    val known = carried.map { it.symbol }.toSet()
    // A CATEGORY COUNTS AS SOMETHING CLAUDE SAID. `ResearchBridge.section` admits a row
    // on any of why / catalyst / risk / vehicle, so requiring `why` here meant a fund
    // returned with a category and no paragraph appeared in the list and then vanished six
    // hours later - contradicting what the screen tells the user happens to added funds.
    val addedByClaude = old.filter {
        it.symbol !in known && it.etf == null &&
            if (it.why.isNotBlank()) stillCurrent(it.why, it.whyAt, now) else it.catalyst.isNotBlank()
    }
    if (addedByClaude.isEmpty()) return carried
    // ---- HOW AN ADDED ROW IS PLACED (Round 66 audit, R1).
    //
    // The app's own `score` first, then Claude's `conviction` among the rows the app never
    // scored. So a fund the app measured always outranks one it did not, and a suggestion
    // can never take the top of a list TJ is buying from on the strength of a number a
    // language model chose for itself. Within the suggestions, conviction is the only
    // ordering there is, and it is the honest one.
    //
    // This is a deliberate step back from what the code did before, which was to write
    // `conviction * 10` into `score` and sort on that - putting a fund with no facts grid,
    // no reason lines and no numbers at row 1 showing "SCORE 100".
    return (carried + addedByClaude)
        .sortedWith(compareByDescending<com.tj.portfolio.data.ResearchRow> { it.score }
            .thenByDescending { it.conviction })
}


/**
 * Keep [old]'s `why` text for any symbol that survived into [fresh], AND keep the whole
 * fund list, which [fresh] never contains.
 *
 * `internal` rather than private: this is the rule that stopped the thirty-minute stock
 * rebuild from wiping the six-hour fund list, and a rule that severe is worth a test that
 * calls it directly rather than one that stands up a ViewModel to reach it.
 */
internal fun carryExplanations(
    old: com.tj.portfolio.data.ResearchSet,
    fresh: com.tj.portfolio.data.ResearchSet,
    now: Long = System.currentTimeMillis()
): com.tj.portfolio.data.ResearchSet {
    // THE EARLY EXITS CARRY THE FUND LIST TOO. `old.isEmpty` asks only about the three
    // STOCK lists, so someone who has opened the ETFs tab and nothing else takes this
    // path - and returning `fresh` bare here is exactly how their fund list was thrown
    // away by the first stock build that ran behind it.
    val keepEtfs = { f: com.tj.portfolio.data.ResearchSet ->
        // BOTH "EXPLAINED VIA CLAUDE" STAMPS ARE GATED THE SAME WAY THE PER-ROW `why` IS -
        // see [carryExplainedStamp]'s own header (full-tests audit, round 79 sweep).
        val (explainedAt, explainedVia, note) = carryExplainedStamp(old.explained, old.explainedBy, old.notes, now)
        val (dtExplainedAt, dtExplainedVia, dtNote) =
            carryExplainedStamp(old.dtExplained, old.dtExplainedBy, old.dtNotes, now)
        f.copy(
            etfs = old.etfs,
            etfGenerated = old.etfGenerated,
            etfWarnings = old.etfWarnings,
            notes = note,
            explained = explainedAt,
            explainedBy = explainedVia,
            dtNotes = dtNote,
            dtExplained = dtExplainedAt,
            dtExplainedBy = dtExplainedVia
        )
    }
    // `isEmpty` asks only about Trending and Best; a set holding nothing but Day Trading rows
    // (a Day Trading answer shared into an app that had not built research yet) still has
    // Claude's work in it to carry.
    if (old.isEmpty && old.dayTrading.isEmpty()) return keepEtfs(fresh)
    // ---- EACH LIST CARRIES FROM ITS OWN OLD LIST (full-tests audit 2026-09-22, S-M2).
    //
    // This was one map across all three lists, and `associateBy` keeps the LAST row per symbol
    // - so a stock in both Best and Day Trading had Best's rebuilt row handed the DAY-TRADE
    // paragraph ("breaking out over the premarket high...") as its long-term case. Claude
    // explains each list separately (`ResearchBridge.merge` and `DayTradingBridge.merge` are
    // applied per list), so a paragraph belongs to the list it was written for.
    // ---- AND IT CARRIES ALL OF CLAUDE'S WORK, NOT JUST THE PARAGRAPH (full test 2026-09-23,
    // S-3 / D-1 / U-1). Only `why` used to survive: Claude's catalyst/target/risk line, its
    // conviction, its Day Trading plan and every symbol it ADDED were dropped by the next
    // rebuild - which the share-in flow could trigger seconds after "Research updated - 2
    // added". The ETF list already kept added funds (see [carryEtfExplanations]); the stock
    // lists now follow the same rule: a Claude-added row (score 0 - the screener never scored
    // it) whose paragraph is still current is kept, BELOW every scored row. Day Trading keeps
    // an added row, and a Claude plan, only for the same trading day - a plan is not a claim
    // about any other session (see [evictStaleDayTradingPlan]).
    fun carry(
        list: List<com.tj.portfolio.data.ResearchRow>,
        from: List<com.tj.portfolio.data.ResearchRow>,
        dayTrading: Boolean = false
    ): List<com.tj.portfolio.data.ResearchRow> {
        val prior = from.associateBy { it.symbol }
        val carried = list.map { r -> carryWhy(r, prior[r.symbol], now, dayTrading) }
        val present = list.mapTo(HashSet()) { it.symbol }
        val added = from.filter { p ->
            p.symbol !in present && p.score <= 0 && (
                (stillCurrent(p.why, p.whyAt, now) && (!dayTrading || sameTradingDay(p.whyAt, now))) ||
                    // A levels-only Claude pick is kept by its plan's own clock (R1-7).
                    (dayTrading && p.planByClaude && sameTradingDay(claudePlanTime(p), now))
                )
        }
        return carried + added
    }
    // `keepEtfs` also carries the fund list and its clock: `fresh` comes from `Research.build`,
    // which never touches `etfs`, and returning it as-is would wipe the six-hour fund list on
    // every thirty-minute stock rebuild - TJ's rule for it is "keep the current list in cache
    // until each update".
    return fresh.copy(
        trending = carry(fresh.trending, old.trending),
        best = carry(fresh.best, old.best),
        dayTrading = carry(fresh.dayTrading, old.dayTrading, dayTrading = true)
    ).let(keepEtfs)
}

/**
 * An analyst pass's results, projected onto the list AS IT IS NOW - see `enrichPass`'s RES-7
 * note for why the list is re-read at write time at all.
 *
 * ONLY THE ANALYST FIELDS ARE TAKEN FROM [enriched] (full-tests audit 2026-09-22, S-M3). The
 * enriched copies were made from the rows as they stood BEFORE the pass suspended for seconds
 * of Nasdaq requests. Swapping them in whole - as this used to - put back the OLD row: a Claude
 * import that landed meanwhile had its `why` and catalyst reverted on every Best row this pass
 * touched, and a price fill its price. Score, reasons and consensus are what the pass computed;
 * everything else stays as the current row has it.
 *
 * The window is re-derived from the CURRENT list, so an import that inserted rows cannot smear
 * the enriched ones across the wrong boundary; only it is re-ranked, and the tail keeps its
 * screener ordering, so nothing can jump from an un-enriched region into a ranked one.
 */
internal fun applyAnalystEnrichment(
    fresh: List<com.tj.portfolio.data.ResearchRow>,
    enriched: List<com.tj.portfolio.data.ResearchRow>,
    window: Int
): List<com.tj.portfolio.data.ResearchRow> {
    val enrichedBy = enriched.associateBy { it.symbol }
    val head = fresh.take(window.coerceAtMost(fresh.size))
    val tail = fresh.drop(head.size)
    val ranked = head.map { f ->
        val e = enrichedBy[f.symbol]
        if (e?.consensus == null || f.consensus != null) f
        else f.copy(score = e.score, reasons = e.reasons, consensus = e.consensus)
    }.sortedByDescending { it.score }
    return ranked + tail
}

/**
 * The rule behind [PortfolioViewModel]'s private `intradayChartIsFinal(held)` - see its KDoc.
 * Top-level and pure so ChartTest tests THIS, not a restated copy that had gone stale (full test
 * 2026-09-24, C-Q3: the copy predated the N-6 `sparkIsFinal` clause).
 */
internal fun intradayChartIsFinal(
    range: com.tj.portfolio.data.ChartRange,
    endMs: Long,
    fetched: Long,
    now: Long
): Boolean {
    val MC = com.tj.portfolio.net.MarketClock
    val open = com.tj.portfolio.net.MarketClock.Phase.OPEN
    val closed = com.tj.portfolio.net.MarketClock.Phase.CLOSED
    val phase = MC.phase(now)
    if (phase == open) return false
    if (endMs <= 0L) return false
    return when (range) {
        // AND FROM THE LATEST SESSION (full test 2026-09-23, N-6): a series that ended in ANY
        // past regular session used to count - so after one failed fetch on a Saturday,
        // Wednesday's chart stayed on screen as "1D" until Monday's open. [sparkIsFinal] is
        // the same question already answered for the row line: fetched outside the session,
        // and no session has opened since.
        com.tj.portfolio.data.ChartRange.D1 ->
            MC.phase(endMs) == open && sparkIsFinal(fetched, now)
        com.tj.portfolio.data.ChartRange.OVERNIGHT ->
            phase == closed && MC.phase(fetched) == closed
        else -> false
    }
}

/**
 * A row sparkline pulled at [fetchedAt] cannot have changed by [now] (full-tests audit
 * 2026-09-22, N-M3). `Quote.spark` is the REGULAR session only, so a series fetched outside it
 * already holds that whole session, and stays the latest one until the next opening bell. The
 * five-minute clock used to ignore that: every visible holding re-downloaded its line every five
 * minutes from 16:00 to 20:00, and again on every cold start overnight or at the weekend.
 */
internal fun sparkIsFinal(fetchedAt: Long, now: Long): Boolean {
    if (fetchedAt <= 0L) return false
    val open = com.tj.portfolio.net.MarketClock.Phase.OPEN
    if (com.tj.portfolio.net.MarketClock.phase(fetchedAt) == open) return false
    if (com.tj.portfolio.net.MarketClock.phase(now) == open) return false
    return now < com.tj.portfolio.net.MarketClock.nextOpenAfter(fetchedAt)
}

/**
 * How long the Day Trading live loop waits before its next sweep.
 *
 * AFTER THE CLOSE IS NOT PRE-MARKET (full-tests audit 2026-09-22, N-M4). Both are EXTENDED, and
 * both used to sweep every 30 seconds. Before the open that is right: pre-market prints move the
 * premarket high, a real trigger level. After 16:00 nothing a plan is built from can move - the
 * session high/low, VWAP and opening range are regular-session figures, and the daily bars have
 * settled - so four hours of 30-second sweeps re-fetched every visible row's intraday bars about
 * 480 times for the same answer. After the close it runs at the closed-market pace instead.
 *
 * @param elapsed [com.tj.portfolio.net.MarketClock.sessionElapsedFraction] - 1.0 once the
 *   regular session has finished, 0.0 before it starts.
 */
internal fun dayTradingLiveDelay(
    phase: com.tj.portfolio.net.MarketClock.Phase,
    elapsed: Double
): Long = when {
    phase == com.tj.portfolio.net.MarketClock.Phase.OPEN -> DAY_TRADING_LIVE_INTERVAL_MS
    phase == com.tj.portfolio.net.MarketClock.Phase.EXTENDED && elapsed < 1.0 -> DAY_TRADING_LIVE_INTERVAL_MS
    else -> DAY_TRADING_LIVE_CLOSED_INTERVAL_MS
}

/**
 * Yahoo symbols that print around the clock: crypto pairs ("BTC-USD" - a dash and a three-letter
 * currency, where a share class is one letter: "BRK-B"), currencies ("EURUSD=X"), futures ("ES=F").
 */
internal fun tradesAroundTheClock(symbol: String): Boolean {
    val s = symbol.uppercase()
    return s.endsWith("=X") || s.endsWith("=F") || Regex("-[A-Z]{3}$").containsMatchIn(s)
}

/**
 * The instant of the SESSION the quotes describe - PortfolioViewModel.sessionInstant's rule,
 * pure for the test: the newest exchange print, unless nothing printed in a week.
 *
 * ONLY EXCHANGE-HOURS PRINTS COUNT (full-tests audit 2026-09-22, A-M5). A crypto pair on the
 * watchlist prints every minute of every day, so its time WAS the newest print - at 1am, and all
 * weekend - and the ledger's "today" followed the calendar again: exactly the bug this rule was
 * written to fix (a mid-session buy looked at after midnight credited with the whole session's
 * move). Round-the-clock symbols are left out; a watchlist of nothing else falls back to `now`.
 */
internal fun sessionInstantFrom(quotes: Collection<Quote>, now: Long): Long {
    val newestPrint = quotes.filterNot { tradesAroundTheClock(it.symbol) }
        .maxOfOrNull { it.quoteTime } ?: 0L
    val aWeek = 7L * 86_400_000L
    return if (newestPrint > 0 && now - newestPrint < aWeek) newestPrint else now
}

private fun sameTradingDay(a: Long, b: Long) = a > 0 && planStillForSession(a, b)

/**
 * Does a plan made at [madeAt] still belong to the session [now] is in? The same New York day
 * does - and so does the NEXT session, for a plan made after a day's close (diff review
 * 2026-09-23, R-4): the evening's "plan for tomorrow" is exactly what D-8 keeps through the
 * morning's first tick, and the rebuild carry and the cold-start eviction must agree with it.
 */
internal fun planStillForSession(madeAt: Long, now: Long): Boolean {
    val mc = com.tj.portfolio.net.MarketClock
    if (mc.dayKey(madeAt) == mc.dayKey(now)) return true
    val et = java.time.Instant.ofEpochMilli(madeAt).atZone(java.time.ZoneId.of("America/New_York"))
    val afterClose = et.hour * 60 + et.minute >= mc.closeMinuteAt(madeAt)
    if (afterClose && mc.dayKey(mc.nextOpenAfter(madeAt)) == mc.dayKey(now)) return true
    // AND A WEEKEND OR HOLIDAY PLAN IS FOR THE NEXT SESSION (full test 2026-09-24, D-3). The two
    // rules above only knew weekday evenings: Friday 22:00 -> Saturday, Sunday 11:00 -> Monday
    // 08:00 were both "a different session", so a rebuild or cold start threw Tj's weekend
    // homework away before Monday's open.
    return mc.sessionFor(madeAt) == mc.sessionFor(now)
}

/** One row of [carryExplanations]: [p]'s Claude work onto [r], while it is still current. */
private fun carryWhy(
    r: com.tj.portfolio.data.ResearchRow,
    p: com.tj.portfolio.data.ResearchRow?,
    now: Long,
    dayTrading: Boolean = false
): com.tj.portfolio.data.ResearchRow {
    // Claude's explanation survives a rebuild; the app's own score and reasons - and, for day
    // trading, the entry/stop/target risk levels - are recomputed from fresh screener data
    // every time, which is the point of a rebuild. BUT ONLY WHILE IT IS STILL RECENT - see
    // [WHY_STALE_MS]. Past that window this stops carrying `why` forward at all, which is the
    // actual eviction: the next `cacheResearch` persists this row with `why` blank again.
    if (p == null) return r
    // CLAUDE'S PLAN IS CARRIED ON ITS OWN CLOCK (review 2026-09-24, R1-7) - a levels-only answer
    // has no current paragraph, and used to lose its plan at the first rebuild.
    val carryPlan = dayTrading && p.planByClaude && sameTradingDay(claudePlanTime(p), now)
    fun withPlan(to: com.tj.portfolio.data.ResearchRow) = if (!carryPlan) to else to.copy(
        entryPrice = p.entryPrice, stopPrice = p.stopPrice, targetPrice = p.targetPrice,
        setup = p.setup, trigger = p.trigger, planNote = p.planNote, planExit = p.planExit,
        planByClaude = true, planPrice = p.planPrice, planAt = p.planAt
    )
    if (!stillCurrent(p.why, p.whyAt, now)) return withPlan(r)
    // A DAY TRADING PARAGRAPH IS ABOUT ITS OWN SESSION (full test 2026-09-24, S-7) - the same
    // gate as the plan below. Monday's "squeezing on the halt, buy the break" must not sit on
    // Thursday's card as today's reasoning.
    if (dayTrading && !sameTradingDay(p.whyAt, now)) return withPlan(r)
    // `conviction > 0` is the mark of a row Claude answered for - the app never sets it - and
    // only then is a catalyst that differs from the fresh screener's Claude's own line.
    val claude = p.conviction > 0
    val base = r.copy(
        why = p.why,
        whyAt = p.whyAt,
        // CLAUDE'S OWN WORDS ONLY, on top of TODAY's earnings phrase (full test 2026-09-24,
        // S-5). A row Claude answered without a catalyst kept the app's "Earnings in 6 days -
        // Sep 27", and this carried that frozen, relative-date string over every fresh one
        // for up to 14 days - announcing a print that had already happened.
        catalyst = if (claude && com.tj.portfolio.net.Research.claudeCatalystPart(p.catalyst).isNotBlank())
            com.tj.portfolio.net.Research.combineCatalyst(r.catalyst, p.catalyst, fromCarry = true)
        else r.catalyst,
        conviction = if (claude) p.conviction else r.conviction
    )
    return withPlan(base)
}

/** When a Claude plan was made: its own stamp, or (older caches) its paragraph's (R1-7). */
internal fun claudePlanTime(r: com.tj.portfolio.data.ResearchRow): Long =
    if (r.planAt > 0L) r.planAt else r.whyAt

/**
 * The other half of [com.tj.portfolio.net.DayTradingBridge]'s level check, run once a price
 * finally exists to check against.
 *
 * WHY IT CANNOT ALL HAPPEN AT MERGE TIME. A pick Claude ADDED is a symbol the app has never
 * seen, so at merge there is no price to sanity-check its entry/stop/target against and they
 * are taken on trust. The price fill is the first moment that check becomes possible, and a
 * decimal slip on a name the app never screened is exactly the case most likely to go
 * unnoticed - the reader has nothing else on the card to compare it with. A plan that fails
 * here is cleared rather than corrected: the live technicals sweep computes the app's own plan
 * for that row on its next tick.
 *
 * Rows whose plan is the app's own are returned untouched - it was computed from this same
 * price and has nothing to disagree with.
 */
internal fun dropUnusableClaudeLevels(
    rows: List<com.tj.portfolio.data.ResearchRow>
): List<com.tj.portfolio.data.ResearchRow> = rows.map { r ->
    if (!r.planByClaude || r.price <= 0.0) r
    else if (com.tj.portfolio.net.DayTradingBridge
            .levelsUsable(r.price, r.entryPrice, r.stopPrice, r.targetPrice)
    ) r
    else r.copy(
        entryPrice = 0.0, stopPrice = 0.0, targetPrice = 0.0,
        setup = "", trigger = "", planByClaude = false, planPrice = 0.0, planAt = 0L
    )
}

/**
 * CLEAR A DAY-TRADING PLAN THAT BELONGS TO A DIFFERENT TRADING DAY (full-tests audit, round
 * 79 sweep).
 *
 * THE BUG THIS FIXES. A day-trading plan - "buy at $12.40, stop $11.90, target $13.50" - is
 * computed against TODAY's intraday structure and is not a claim about any other day.
 * [loadCachedResearch] restores whatever was last persisted verbatim, and if the app has been
 * closed since a previous trading day AND the follow-up rebuild then fails (offline, a Yahoo
 * cooldown - `loadResearch`'s own "keep whatever was on screen" rule for an empty rebuild),
 * those stale numeric levels would sit on screen with nothing marking them as stale - a more
 * direct "recommendation" than the explanatory `why` paragraph the rest of this sweep fixes.
 *
 * NEVER MISLOGGED, ONLY MISDISPLAYED WITHOUT THIS. `captureDayTradingRecommendations` only
 * logs rows the live sweep re-planned this tick (D-1, `loggableDayTradingRows`), so a stale row
 * like this stays out of the PERMANENT recommendation log regardless of this fix - this closes
 * the narrower, display-only gap.
 */
internal fun evictStaleDayTradingPlan(
    rows: List<com.tj.portfolio.data.ResearchRow>,
    generated: Long,
    now: Long = System.currentTimeMillis()
): List<com.tj.portfolio.data.ResearchRow> {
    if (planStillForSession(generated, now)) return rows
    return rows.map { r ->
        if (r.entryPrice <= 0.0 && r.stopPrice <= 0.0 && r.targetPrice <= 0.0) r
        else r.copy(
            entryPrice = 0.0, stopPrice = 0.0, targetPrice = 0.0,
            setup = "", trigger = "", planNote = "", planExit = "",
            tooLateToStart = false, planByClaude = false, planDeclineStreak = 0, planReason = "",
            planPrice = 0.0, planAt = 0L
        )
    }
}

/**
 * Merges one fresh [DayTradingTechnicals.DayTechnicals] reading into a row - TOP-LEVEL AND
 * PURE, same reason the price fill is, so a test can check it with no network and no
 * ViewModel.
 *
 * EACH FIELD KEPT SEPARATELY ON A PARTIAL FETCH (a real bug caught by code review before
 * shipping, fixed here). The daily-bar request (ATR) and the intraday-bar request (VWAP,
 * opening range) fail independently - see [DayTradingTechnicals.DayTechnicals.isEmpty]'s own
 * note - so a `tech` that is not empty only means AT LEAST ONE of the two answered this tick,
 * not that all four fields did. Blindly copying `tech`'s zeros over the row's own previously-
 * good reading would regress a real ATR the user is already looking at back to "not yet
 * available" the moment only the VWAP half of the next fetch succeeds - each field here falls
 * back to what the row already had, never to a blanket zero.
 */
internal fun mergeDayTradingTech(
    row: com.tj.portfolio.data.ResearchRow,
    tech: com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals,
    /**
     * THE CLOCK ARRIVES AS AN ARGUMENT, NOT A READ (Round 73). [com.tj.portfolio.net.ResearchScore]
     * owns no clock by design, and neither does this function - both are pure so a test can
     * drive any moment of the session without waiting for one. The live sweep supplies the real
     * values; the defaults describe "no session information", which is how every existing caller
     * and test behaved before the time rules existed.
     */
    minutesLeft: Int = 0,
    middayLull: Boolean = false,
    /** The moment of this tick, for the D-2 rule below; 0 (tests' default) = not known. */
    now: Long = 0L
): com.tj.portfolio.data.ResearchRow {
    val effective = effectiveTechnicals(row, tech)
    // ---- PLAN AGAINST THE PRICE NOW, NOT THE SCREENER'S (full-tests audit 2026-09-22, D-H1).
    //
    // `row.price` is whatever the screener saw when the list was BUILT - up to a rebuild
    // interval old - and nothing on this 30-second tick used to move it. So the "live" plan
    // was computed around a stale price (a stock that had since run up still read as sitting
    // under its entry; one that had faded still read as extended), the card showed that stale
    // price beside live levels, and the recommendation log stored it as the price at the
    // moment of the recommendation - the number `DayTradingEval.entryRises` uses to decide
    // whether an entry is a breakout or a pullback. A reading from TODAY's bars carries the
    // latest print; with none (fetch failed, pre-4am, a holiday) the row keeps its own.
    val livePrice = tech.lastPrice.takeIf { it > 0.0 && tech.sessionDay.isNotBlank() }
    val price = livePrice ?: row.price
    // A PLAN CLAUDE SET IS NOT RECOMPUTED OVER. The live sweep ticks every 30 seconds, so
    // computing the app's own plan here unconditionally would have silently replaced an
    // imported Claude plan within half a minute of the import - the user taps Import, reads
    // Claude's entry, and watches it turn back into the app's while looking at it. That
    // defeats the round trip Tj asked for ("it can give advice and buy and sell targets for
    // all the stocks"). The row's LEVELS still refresh underneath it, so the dialog shows live
    // VWAP and session structure beside Claude's plan; the plan itself stands until the next
    // full screener rebuild, which clears the whole section anyway.
    // PLAN AND REASON, FROM ONE CALL (Round 75) - `planInternal` is what `tradePlan` and
    // `tradePlanDeclineReason` each individually wrap; calling it directly here computes the
    // decision exactly once per tick instead of twice.
    // Computed up here, ahead of the plan, because a rollover also decides WHOSE plan this tick
    // is - see `claudePlanStands` below and `sessionChanged`'s own note further down.
    val sessionChanged = row.sessionDay.isNotBlank() && row.sessionDay != effective.sessionDay
    // A CLAUDE PLAN IS FOR THE SESSION IT WAS IMPORTED INTO, NOT FOREVER. Before this, a session
    // rollover cleared a Claude plan's levels (below) but left `planByClaude` set - and since a
    // `planByClaude` row is never re-planned, the row then sat blank for the whole new session,
    // with neither Claude's levels nor the app's, and nothing logged for it, until the next full
    // rebuild. `dropUnusableClaudeLevels` and `evictStaleDayTradingPlan` both already hand the
    // row back to the engine when they clear a Claude plan; this is the same rule. (An import
    // made BEFORE the new session's first tick is not caught by this - `DayTradingBridge.merge`
    // starts such a row's session fresh, so there is no rollover left here to see.)
    //
    // A ROLLOVER THAT IS ONLY A STAMP DOES NOT COUNT (full test 2026-09-24, D-2). An evening
    // import (16:00-20:00) is swept every five minutes by the post-close loop, and each sweep
    // stamps today's date on the row - so the 04:00 tick read "new session" and replaced
    // Claude's plan for tomorrow with the app's. The plan's own time (`whyAt`, written with it)
    // decides which session it is for.
    //
    // AND ONLY WHILE ITS LEVELS STILL MAKE SENSE AGAINST A REAL PRICE (full test 2026-09-24,
    // D-13). The import-time check needs a quote, and a night import whose quote fill failed
    // left a decimal-slipped Claude level on screen - and logged - unchecked. The live price
    // is the check this tick can always make.
    val claudeLevelsUnusable = row.planByClaude && livePrice != null &&
        !com.tj.portfolio.net.DayTradingBridge.levelsUsable(
            livePrice, row.entryPrice, row.stopPrice, row.targetPrice)
    val planTime = claudePlanTime(row)   // R1-7: the plan's own import time
    val claudePlanStands = row.planByClaude && !claudeLevelsUnusable && (!sessionChanged ||
        (now > 0L && planTime > 0L && planStillForSession(planTime, now)))
    val (plan, declineReason) = if (claudePlanStands) null to ""
    else com.tj.portfolio.net.ResearchScore.planInternal(
        price,
        effective,
        minutesLeft = minutesLeft,
        middayLull = middayLull,
        // The row carries no structured earnings timestamp - `catalyst` is the free text built
        // by `Research.catalystFor`, and the constant is shared with it precisely so this match
        // cannot drift out of step with the copy it is matching. See its own header.
        earningsToday = row.catalyst.startsWith(
            com.tj.portfolio.net.Research.CATALYST_EARNINGS_TODAY
        )
    )
    // THE ENGINE LOOKED AND SAID NO, as opposed to not being able to look at all - the
    // distinction the level fields below turn on. `tradePlan` bails early only on a missing
    // price or a missing volatility reading, so with both present a null is a decision.
    val declined = plan == null && !claudePlanStands && price > 0.0 &&
        (effective.atrIntraday > 0.0 || effective.atr14 > 0.0)
    // ---- A REAL SESSION ROLLOVER BYPASSES THE DEBOUNCE BELOW ENTIRELY (full-tests audit).
    //
    // THE BUG. The debounce a few lines down exists for boundary noise WITHIN one session - a
    // price sitting right on a "no room left today" line, flickering the verdict tick to tick.
    // It has no special case for the session itself changing underneath it, which it can:
    // `fgScope` is only cancelled on `ON_STOP`, so a phone left on the Day Trading tab (plugged
    // in, screen never locked) keeps ticking straight through a close and into the next
    // session's pre-market. `effective.sessionDay` (from `effectiveTechnicals`, above) already
    // correctly flips to the new day - but if the FIRST tick of that new day also happens to
    // decline (unsurprising pre-market, before anything has printed), the streak requirement
    // meant up to one extra tick (30s) of showing YESTERDAY'S numeric entry/stop/target under
    // TODAY'S date, since `sessionDay` itself updates immediately below while the levels lagged
    // behind it. A stale dollar figure with today's date on it is worse than the flicker this
    // debounce was built to prevent. (`sessionChanged` itself is computed above, before the plan.)
    // ---- A DECLINE HAS TO REPEAT BEFORE IT CLEARS THE SCREEN (Round 74).
    //
    // THE BUG. Several of `tradePlan`'s "no trade" verdicts - no room left in the day, the
    // target already passed - are decided against a boundary the LIVE PRICE sits right next
    // to for exactly the kind of stock this section screens for: one already moving hard. A
    // price wobbling a few cents either side of that line flipped the verdict every 30-second
    // tick, and clearing the whole plan on the FIRST decline meant the grid - and the red
    // `planNote`/beginner-summary text drawn from it - would load, vanish and reappear on a
    // clock the reader could never see, describing a trade whose real state had not changed.
    //
    // [ResearchRow.planDeclineStreak] counts consecutive declined ticks; a level is only
    // actually withdrawn once the streak reaches [DAY_TRADING_DECLINE_CONFIRM_TICKS]. A real
    // plan returning resets the streak to zero immediately - there is no debounce on GOOD
    // news, only on clearing what is already on screen. This does not reintroduce the stale-
    // plan bug Round 73 fixed: a genuinely dead plan still clears within one extra tick, not
    // for the rest of the afternoon.
    val declineStreak = when {
        // A standing Claude plan IS a plan (full test 2026-09-23, D-2): freezing the app's
        // streak under it kept `loggableDayTradingRows` (streak must be 0) from ever logging
        // it - so Claude's plans were skipped exactly where they differed from the app's.
        plan != null || claudePlanStands -> 0
        // A fresh session starts its OWN streak, not a carry-over from yesterday's close - see
        // `sessionChanged`'s note above. `confirmedDecline` below forces the clear on this exact
        // tick regardless, so this only governs how the NEXT tick's debounce behaves.
        sessionChanged -> if (declined) 1 else 0
        declined -> row.planDeclineStreak + 1
        else -> row.planDeclineStreak
    }
    val confirmedDecline = (declined && declineStreak >= DAY_TRADING_DECLINE_CONFIRM_TICKS) ||
        (sessionChanged && plan == null && !claudePlanStands) ||   // D-2: a standing plan is kept
        (claudeLevelsUnusable && plan == null)                     // D-13: never keep bad levels
    return row.copy(
        price = price,
        // Only while the regular session is open is "vs the previous close" today's move;
        // outside it, the screener's figure stands.
        changePct = if (livePrice != null && tech.sessionLive && tech.prevClose > 0.0)
            (livePrice / tech.prevClose - 1.0) * 100.0 else row.changePct,
        atr = effective.atr14,
        vwap = effective.vwap,
        openingRangeHigh = effective.openingRangeHigh,
        openingRangeLow = effective.openingRangeLow,
        openingRangeComplete = effective.openingRangeComplete,   // R1-2
        atrIntraday = effective.atrIntraday,
        adr = effective.adr,
        prevHigh = effective.prevHigh,
        premarketHigh = effective.premarketHigh,
        sessionHigh = effective.sessionHigh,
        sessionLow = effective.sessionLow,
        sessionDay = effective.sessionDay,
        // THE PLAN MOVES AS A UNIT, or not at all. Entry, stop, target, setup, trigger and note
        // are six views of ONE decision: a stop from this tick's structure under an entry from
        // the last tick's would describe a trade nobody planned.
        //
        // ---- BUT "NO PLAN" NOW HAS TWO MEANINGS, AND THEY NEED OPPOSITE HANDLING (Round 73,
        // second code-review pass).
        //
        // Keeping the previous plan is right when this tick simply could not SEE anything - a
        // failed fetch, no volatility reading yet. It is wrong when the engine looked at good
        // inputs and DECIDED there is no trade, which Round 73 made reachable mid-session: once
        // a stock has spent its average daily range, `tradePlan` returns null on every tick
        // afterwards, and the morning's entry, stop and target would then sit frozen on screen
        // all afternoon, describing a trade the app no longer thinks exists.
        //
        // `tradePlan`'s own contract separates the two cleanly: it returns null early ONLY for a
        // missing price or a missing ATR, so with both of those present any null is a judgment,
        // not a gap - and a judgment of "no trade here" is published as a blank, which is what
        // this file's own rule ("a fabricated level is worse than a blank") already requires.
        entryPrice = plan?.entry ?: keepOrClear(row.entryPrice, confirmedDecline),
        stopPrice = plan?.stop ?: keepOrClear(row.stopPrice, confirmedDecline),
        targetPrice = plan?.target ?: keepOrClear(row.targetPrice, confirmedDecline),
        setup = plan?.setup ?: keepOrClear(row.setup, confirmedDecline),
        trigger = plan?.trigger ?: keepOrClear(row.trigger, confirmedDecline),
        planNote = plan?.note ?: keepOrClear(row.planNote, confirmedDecline),
        planExit = plan?.exit ?: keepOrClear(row.planExit, confirmedDecline),
        // D-9: the price THIS plan was made at - a standing Claude plan keeps its own.
        planPrice = if (plan != null) price else keepOrClear(row.planPrice, confirmedDecline),
        planDeclineStreak = declineStreak,
        planByClaude = claudePlanStands,
        // SAME RULE AS THE LEVELS ABOVE, one tick later than `declined` alone. A real plan
        // clears it immediately; an UNCONFIRMED decline keeps whatever was already there
        // (blank, on a row that has never shown a reason yet) so the reason cannot flash in
        // before the levels it explains have actually gone.
        planReason = when {
            plan != null || claudePlanStands -> ""
            confirmedDecline -> declineReason
            else -> row.planReason
        },
        // CLOCK-DERIVED, NOT PLAN-DERIVED - and so it keeps updating even on a tick that
        // produced no plan at all, and on a Claude-authored row this function never re-plans.
        // See `ResearchScore.tooLateToStart` for the two ways the old plan-bundled version
        // silently stuck.
        tooLateToStart = com.tj.portfolio.net.ResearchScore.tooLateToStart(minutesLeft)
    )
}

/** "1-5;9-12" -> [1..5, 9..12]; junk parts are skipped. See Keys.REPLAY_REPAIR_RANGES (A-2). */
internal fun parseRepairRanges(raw: String?): List<LongRange> =
    raw.orEmpty().split(';').mapNotNull { part ->
        val bits = part.trim().split('-')
        val a = bits.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val b = bits.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        if (b >= a) a..b else null
    }

internal fun formatRepairRanges(ranges: List<LongRange>): String =
    ranges.joinToString(";") { "${it.first}-${it.last}" }

/**
 * The Day Trading rows that count as a RECOMMENDATION right now - what
 * `captureDayTradingRecommendations` may write to the permanent log. Pure, for the test.
 *
 * Levels on screen are not always an instruction (full-tests audit 2026-09-22, D-M3):
 *  - [ResearchRow.tooLateToStart] - under 30 minutes of session left, the card keeps the levels
 *    but badges them "too late to start today". Logging one of those - the first time a plan was
 *    ever seen, say, at 15:45 - scored the system on a trade it explicitly told Tj not to take.
 *  - [ResearchRow.planDeclineStreak] > 0 - the engine has already said "no trade" and the levels
 *    are only still drawn to ride out a possible one-tick wobble (see [mergeDayTradingTech]).
 * The log keeps a symbol's FIRST recommendation of the day (`INSERT OR IGNORE`), so this only
 * decides whether a plan's first appearance is logged, never removes one already recorded.
 */
internal fun loggableDayTradingRows(
    rows: List<com.tj.portfolio.data.ResearchRow>,
    today: String,
    /**
     * The symbols re-planned THIS TICK from a live (market-open) intraday fetch (full test
     * 2026-09-24, D-1). `sessionDay == today` cannot tell a live plan from this morning's
     * pre-market one - a 04:00 sweep stamps today on a plan built from prior-session levels -
     * and capture used to run on every `cacheResearch` from any caller (the Best analyst pass,
     * an ETF rebuild, an import, the price fill, a detail screen's single-symbol tick, a row
     * whose fetch failed on the busy first open tick). Each logged that stale plan with
     * `recordedAt = now` and the pre-market price, and `INSERT OR IGNORE` then locked the real
     * live plan out of the log for the day.
     */
    liveNow: Set<String>
): List<com.tj.portfolio.data.ResearchRow> = rows.filter {
    it.symbol in liveNow &&
        it.entryPrice > 0.0 && it.stopPrice > 0.0 && it.targetPrice > 0.0 &&
        it.price > 0.0 && it.sessionDay == today &&
        !it.tooLateToStart && it.planDeclineStreak == 0
}

/** At most this many log rows are resolved per "Check" press - see D-6 in evaluateDayTradingLog. */
internal const val DAY_TRADING_EVAL_CAP = 60

/** The rows one press resolves: the oldest [cap] of them, oldest first. */
internal fun dayTradingRowsToResolve(
    rows: List<com.tj.portfolio.data.DayTradingLogEntry>,
    cap: Int = DAY_TRADING_EVAL_CAP
): List<com.tj.portfolio.data.DayTradingLogEntry> =
    rows.sortedWith(compareBy({ it.tradingDay }, { it.recordedAt })).take(cap)

/** How many consecutive live ticks [ResearchScore.tradePlan] must decline before
 *  [mergeDayTradingTech] actually withdraws a level - see its own note on why. */
private const val DAY_TRADING_DECLINE_CONFIRM_TICKS = 2

/**
 * What an import review shows once [incoming] lands (A-7): a review already waiting keeps its
 * rows and gains [incoming]'s; an [incoming] with no rows (an error) leaves it untouched - the
 * caller reports the error separately. With nothing waiting, [incoming] as is.
 */
internal fun mergeIntoPendingReview(
    pending: com.tj.portfolio.net.ExtractResult?,
    incoming: com.tj.portfolio.net.ExtractResult
): com.tj.portfolio.net.ExtractResult {
    val waiting = pending?.takeIf { it.transactions.isNotEmpty() } ?: return incoming
    if (incoming.transactions.isEmpty()) return waiting
    return waiting.copy(
        transactions = waiting.transactions + incoming.transactions,
        notes = listOf(waiting.notes, incoming.notes).filter { it.isNotBlank() }.joinToString("\n\n")
    )
}

/**
 * The undo snapshot's file name (full test 2026-09-24, A-6). NEVER AN EXISTING FILE: names have
 * minute resolution and the write replaces, so a second Replace-all in the same minute (a
 * hurried retry after the wrong file) overwrote the only copy of the original ledger with the
 * already-replaced one. And [what] is SANITISED: "delete-BRK/B" named a missing subfolder, the
 * write threw into a `runCatching`, and the delete went ahead without the undo it promised.
 */
internal fun snapshotFileName(what: String, stamp: String, exists: (String) -> Boolean): String {
    val base = com.tj.portfolio.util.Storage.BEFORE_PREFIX +
        what.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + stamp
    return (sequenceOf("$base.json") + (2..99).asSequence().map { "$base-$it.json" })
        .firstOrNull { !exists(it) } ?: "$base-${System.nanoTime()}.json"
}

/**
 * Whether `[dayStart, todayStart)` - both local midnights - holds a weekday, i.e. a session
 * whose close the %-since-added baseline could be (N-7). Holidays are not modelled: one costs a
 * single request that finds nothing, and the next weekday covers it.
 */
internal fun baselineWindowHasSession(dayStart: Long, todayStart: Long): Boolean {
    if (dayStart >= todayStart) return false
    val c = java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
    repeat(7) {
        if (c.timeInMillis >= todayStart) return false
        val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
        if (dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY) return true
        c.add(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    return true
}

/**
 * The LAST point in [points] whose timestamp falls in `[dayStart, todayStart)` - the
 * %-since-added baseline's own "which point is the close" rule, pulled out of
 * [PortfolioViewModel.closeOnOrAfter] so it can be tested without a live network fetch.
 *
 * `maxByOrNull`, not `minByOrNull` - a sub-daily series (this app's own [ChartRange.D5]/
 * [ChartRange.D1]) holds several points for one calendar day, and the LAST one is the day's
 * close; the first is close to the opening bell. Picking the wrong end of that ordering was a
 * real bug that shipped - see [PortfolioViewModel.closeOnOrAfter]'s own note.
 */
internal fun lastCloseInWindow(
    points: List<com.tj.portfolio.data.ChartPoint>,
    dayStart: Long,
    todayStart: Long
): Double? = points
    .filter { it.t * 1000L in dayStart until todayStart }
    .maxByOrNull { it.t }
    ?.close

/**
 * The one-time reorder a completed Day Trading full sweep applies (Round 75) - see
 * [PortfolioViewModel.enrichDayTradingVisible]'s own header for when this runs and why it is
 * NOT the ordinary 30-second refresh. Tj: "try to find and show the actual stocks that I can
 * act on currently at the top of the list."
 *
 * ACTIONABLE ROWS FIRST (a real, current [ResearchRow.entryPrice] - the app's own plan or
 * Claude's), RANKED BY [ResearchScore.blendedScore] WITHIN EACH GROUP. `sortedWith` is a STABLE
 * sort, so two rows that tie on both keys keep whatever relative order they already had - the
 * screener's own build-time ranking, never an arbitrary reshuffle. PURE, so this can be tested
 * without a ViewModel or a network call, the same reason [mergeDayTradingTech] is its own
 * top-level function.
 */
internal fun sortDayTradingForActionability(
    rows: List<com.tj.portfolio.data.ResearchRow>
): List<com.tj.portfolio.data.ResearchRow> = rows.sortedWith(
    compareByDescending<com.tj.portfolio.data.ResearchRow> { it.entryPrice > 0.0 }
        .thenByDescending {
            com.tj.portfolio.net.ResearchScore.blendedScore(it.dtLikelihood, it.dtConfidence)
        }
)

/** @see mergeDayTradingTech - a level the engine declined (twice running) is cleared, one it
 *  could not see is kept. */
private fun keepOrClear(previous: Double, confirmedDecline: Boolean): Double =
    if (confirmedDecline) 0.0 else previous

private fun keepOrClear(previous: String, confirmedDecline: Boolean): String =
    if (confirmedDecline) "" else previous

/**
 * The likelihood/confidence half of a Day Trading row's live enrichment (Round 72) - split out
 * from [PortfolioViewModel.enrichDayTradingVisible] for the same reason [mergeDayTradingTech] is
 * its own top-level function: PURE, so a test can check it with no network and no ViewModel.
 *
 * [withLevels] must already carry the row's build-time [ResearchRow.dtLikelihood] and
 * [ResearchRow.dtConfidence] (i.e. be [mergeDayTradingTech]'s own output) and [effective] must be
 * the SAME reading the levels above it were computed from - see [effectiveTechnicals]'s own
 * header for why. The caller is responsible for guarding this against a Claude-authored row -
 * see the call site.
 *
 * IDEMPOTENT - SAFE ON EVERY TICK (full test 2026-09-24, D-10). The technicals half is added to
 * the row's BUILD-TIME halves ([ResearchRow.dtBaseLikelihood] and siblings), never to its last
 * output, so a second call cannot compound the bonus ([ResearchScore.technicalConfirmationBonus]'s
 * header) and a signal that stops confirming stops counting. It used to run once per rebuild,
 * which froze whatever the first sweep saw - often a pre-market one, with no VWAP at all. A row
 * with no recorded base (a cache from an older build) takes its current values as the base,
 * once, and carries them from then on.
 */
internal fun scoreDayTradingRow(
    withLevels: com.tj.portfolio.data.ResearchRow,
    effective: com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals
): com.tj.portfolio.data.ResearchRow {
    val recorded = withLevels.dtBaseLikelihood > 0
    val baseLikelihood = if (recorded) withLevels.dtBaseLikelihood else withLevels.dtLikelihood
    val baseConfidence = if (recorded) withLevels.dtBaseConfidence else withLevels.dtConfidence
    val baseReasons = if (recorded) withLevels.reasons.take(withLevels.dtBaseReasonCount)
    else withLevels.reasons
    val scored = com.tj.portfolio.net.ResearchScore.withTechnicals(
        com.tj.portfolio.net.ResearchScore.Scored(baseLikelihood, baseReasons, 100),
        effective,
        withLevels.price
    )
    val confidence = (baseConfidence +
        com.tj.portfolio.net.ResearchScore.technicalConfirmationBonus(withLevels.price, effective)
    ).coerceIn(0, 100)
    return withLevels.copy(
        score = com.tj.portfolio.net.ResearchScore.blendedScore(scored.score, confidence),
        reasons = scored.reasons,
        dtLikelihood = scored.score,
        dtConfidence = confidence,
        dtBaseLikelihood = baseLikelihood,
        dtBaseConfidence = baseConfidence,
        dtBaseReasonCount = baseReasons.size
    )
}

/**
 * One reading combining THIS tick's technicals with whatever the row already had, per field.
 *
 * WHY THE PLAN MUST BE COMPUTED FROM THIS AND NOT FROM `tech` DIRECTLY. The daily-bar request
 * (ATR, ADR, prior session) and the intraday-bar request (VWAP, opening range, session high and
 * low) fail independently - the reason this per-field fallback exists at all. Computing the
 * plan from the raw reading meant that on a tick where only the intraday half failed, the card
 * went on DISPLAYING the VWAP it already had while the plan behind it was quietly built as
 * though this stock had no VWAP - a different setup, a different entry, from levels the user
 * could still see on screen. The displayed levels and the plan derived from them now come from
 * one and the same reading, always.
 *
 * The prior-session fields travel together (one request), so a failed daily half leaves
 * `prevLow`/`prevClose` at zero and the floor-trader pivots simply unavailable that tick,
 * rather than half-computed from a stale high.
 *
 * AND ONLY WITHIN THE SAME TRADING DAY, for the intraday half. VWAP, the opening range, the
 * premarket high and the session high/low are defined PER SESSION; reusing yesterday's across
 * the open is not stale data, it is wrong data. It would also be quietly consequential: a
 * finished session has spent essentially all of its average daily range, so a row carrying
 * yesterday's high and low into this morning reads as `rangeUsed` near 1.0 and every plan
 * built from it comes out "already extended - do not chase", at 09:31, on a stock that has
 * barely traded. Only the daily-bar fields (ATR, ADR, the prior session, which are about
 * COMPLETED days by construction) survive a change of session.
 */
internal fun effectiveTechnicals(
    row: com.tj.portfolio.data.ResearchRow,
    tech: com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals,
    // A parameter with a default rather than a bare clock call, so the carry-forward rule
    // below can be exercised on a timeline a test controls. Every caller in the app omits it.
    now: Long = System.currentTimeMillis()
): com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals {
    val sameSession = row.sessionDay.isNotBlank() && row.sessionDay == tech.sessionDay
    // ---- A FAILED REQUEST IS NOT A CHANGE OF SESSION.
    //
    // `tech.sessionDay` is blank both when the intraday request FAILED and when it succeeded
    // with no bars dated today, and this function used to treat the two identically: every
    // intraday field fell to 0. On a 30-symbol sweep a transient failure of one host is
    // routine, so that happened often - and it was not a cosmetic blank. With `vwap` and
    // `sessionLow` at 0, `ResearchScore.planInternal` can no longer take the VWAP-reclaim or
    // pullback branch, `rangeUsed` reads 0, the room cap disappears entirely, and `vol` swaps
    // from the real 5-minute ATR to `atr14 * 0.10`. Since the stop is sized at 1.5-2.5x `vol`,
    // the entry, stop and target the user is looking at changed by a large factor on that
    // tick and changed back on the next - three money levels flickering between two entirely
    // different trade plans, for no reason but a dropped request.
    //
    // So: when the request never came back, keep what the row already had - but only while
    // the row's own reading really is THIS session's. That last condition is what stops the
    // carry-forward from resurrecting yesterday's high and low across the open, which is the
    // failure mode the `sameSession` rule exists to prevent in the first place.
    val fetchFailed = !tech.intradayFetched &&
        row.sessionDay.isNotBlank() &&
        row.sessionDay == com.tj.portfolio.net.MarketClock.dayKey(now)
    val keepIntraday = sameSession || fetchFailed
    return tech.copy(
        atr14 = if (tech.atr14 > 0) tech.atr14 else row.atr,
        adr = if (tech.adr > 0) tech.adr else row.adr,
        prevHigh = if (tech.prevHigh > 0) tech.prevHigh else row.prevHigh,
        vwap = if (tech.vwap > 0) tech.vwap else if (keepIntraday) row.vwap else 0.0,
        openingRangeHigh = if (tech.openingRangeHigh > 0) tech.openingRangeHigh
        else if (keepIntraday) row.openingRangeHigh else 0.0,
        openingRangeLow = if (tech.openingRangeLow > 0) tech.openingRangeLow
        else if (keepIntraday) row.openingRangeLow else 0.0,
        // THE FLAG TRAVELS WITH THE RANGE IT DESCRIBES (review 2026-09-24, R1-2). The plan uses
        // the range only once it is complete (D-5); carried without its flag, every failed
        // intraday tick after 10:00 dropped the range from the plan and the levels jumped.
        openingRangeComplete = if (tech.openingRangeHigh > 0) tech.openingRangeComplete
        else keepIntraday && row.openingRangeComplete,
        atrIntraday = if (tech.atrIntraday > 0) tech.atrIntraday
        else if (keepIntraday) row.atrIntraday else 0.0,
        premarketHigh = if (tech.premarketHigh > 0) tech.premarketHigh
        else if (keepIntraday) row.premarketHigh else 0.0,
        sessionHigh = if (tech.sessionHigh > 0) tech.sessionHigh
        else if (keepIntraday) row.sessionHigh else 0.0,
        sessionLow = if (tech.sessionLow > 0) tech.sessionLow
        else if (keepIntraday) row.sessionLow else 0.0,
        // The reading now describes the row's session again, so say so - otherwise the NEXT
        // tick sees a blank `sessionDay` on the merged row and the carry-forward unravels one
        // tick later than it used to.
        sessionDay = if (keepIntraday && tech.sessionDay.isBlank()) row.sessionDay
        else tech.sessionDay
    )
}

/**
 * A PER-KEY FAILURE BACKOFF: ask again, but less and less often.
 *
 * TOP-LEVEL AND `internal` RATHER THAN PRIVATE TO THE VIEWMODEL (Round 63). It used to be a
 * private nested class, which meant `RetryBackoffTest` could only RESTATE its rule in a copy -
 * so a change to the real one could not fail that test, which is the opposite of what a test
 * is for. It now guards four separate request storms (charts, sparklines, fund holdings and,
 * since Round 63, the two Research builds), and the last of those can loop unboundedly if the
 * rule is wrong. It is worth testing the real thing.
 */
internal class RetryClock {
    private val attemptedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * How long since the last attempt before the count is forgotten entirely.
     *
     * Twice the maximum backoff. Anything that has sat untouched for ten minutes is being
     * asked about again from scratch, not continuing a streak.
     */
    private val FORGET_MS = 600_000L

    /** 30s, 1m, 2m, 4m, then held at 5m. Matches `Http`'s unreachable backoff. */
    internal fun backoffMs(fails: Int): Long =
        minOf(30_000L shl (fails - 1).coerceIn(0, 4), 300_000L)

    /**
     * True when this key failed recently enough that asking again would be noise.
     *
     * A key that has never failed is never blocked, so this can be added to an existing
     * guard without changing the behaviour of anything that works.
     */
    fun blocked(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val fails = failures[key] ?: return false
        if (fails <= 0) return false
        val last = attemptedAt[key] ?: 0L
        // ---- COUNTS AGE OUT (sweep 3).
        //
        // Only a success or a manual pull used to clear them, so one five-minute outage drove
        // every symbol to the top tier and left it there for the life of the process - and the
        // next single dropped symbol, long after the network came back, started at a
        // five-minute backoff instead of thirty seconds. The count is meant to describe
        // CONSECUTIVE RECENT failures; something that has not been attempted for well past its
        // own window is not that.
        if (now - last > FORGET_MS) {
            failures.remove(key)
            attemptedAt.remove(key)
            return false
        }
        return now - last < backoffMs(fails)
    }

    // `now` is a parameter with a default rather than a bare call to the clock, so the rule
    // can be exercised on a timeline a test controls. Every caller in the app omits it.
    fun success(key: String, now: Long = System.currentTimeMillis()) {
        // BOTH MAPS, not just the failure count. `attemptedAt` is only ever READ alongside a
        // failure count (the age-out branch in `blocked()` runs for keys that have one), so a
        // timestamp left behind after a success is unreachable state that is never collected:
        // `chartRetry` is keyed "SYMBOL|RANGE", so a long session browsing search results and
        // Research lists accumulated a permanent entry per symbol-range pair across all five
        // RetryClocks. Small each, unbounded in total, and useful to nobody.
        attemptedAt.remove(key)
        failures.remove(key)
    }

    fun failure(key: String, now: Long = System.currentTimeMillis()) {
        attemptedAt[key] = now
        failures[key] = (failures[key] ?: 0) + 1
    }

    /** Called by a manual refresh: the user asking counts as "try it now, whatever". */
    fun clear() {
        attemptedAt.clear()
        failures.clear()
    }
}


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

    /**
     * How many advice preparations (news preload, prompt file) are running - HERE, not in the
     * screen's `remember` (full test 2026-09-24, U-8): a tab switch reset that flag mid-preload,
     * the button came back live, and a second tap started a second preload and share sheet.
     */
    private val _advicePreparing = MutableStateFlow(0)
    val advicePreparing: StateFlow<Int> = _advicePreparing.asStateFlow()
    private val _adviceError = MutableStateFlow<String?>(null)
    val adviceError: StateFlow<String?> = _adviceError.asStateFlow()

    private val _importResult = MutableStateFlow<ExtractResult?>(null)
    val importResult: StateFlow<ExtractResult?> = _importResult.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ---- SHARE -> PORTFOLIO (2026-09-23b). One-shot navigation requests raised by an import
    // that arrived through the share sheet rather than a button, so no screen asked for it.
    // `App` performs the tab change and clears [shareNav]; the Research screen switches its own
    // section and clears [researchJump]. Both are plain state rather than events so a request
    // raised before the screen is composed (a cold start straight from a share) still lands.
    private val _shareNav = MutableStateFlow<ShareDest?>(null)
    val shareNav: StateFlow<ShareDest?> = _shareNav.asStateFlow()
    fun shareNavHandled() { _shareNav.value = null }

    private val _researchJump = MutableStateFlow<Int?>(null)
    val researchJump: StateFlow<Int?> = _researchJump.asStateFlow()
    fun researchJumpHandled() { _researchJump.value = null }

    /**
     * Completed once [loadCachedResearch] has finished, whatever it found. A share can cold-start
     * the app, and the research cache is parsed off the main thread - an answer merged into the
     * still-empty lists first would make the cache load see a non-empty set and DROP the cached
     * Trending/Best/ETF lists (it only fills an empty screen). [importSharedInbox] waits on this.
     */
    private val researchCacheReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Serialises [importSharedInbox] - see its ONE DRAIN AT A TIME note. */
    private val shareDrain = kotlinx.coroutines.sync.Mutex()

    /**
     * ONE WRITER OF THE DOWNLOADS AUTOSAVE AT A TIME (full test 2026-09-24, A-9). Each
     * [autoBackupIfDue] was its own IO launch opening the same file with "wt", so an import
     * committed while the launch autosave was writing could interleave two truncate-and-writes
     * in the one uninstall-proof copy. The export runs inside the lock too, so the run that
     * writes last also exported last.
     */
    private val autosaveWrite = kotlinx.coroutines.sync.Mutex()

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
     * When SEC filings were last pulled on this device (Round 66).
     *
     * A FIELD, not a counter inside the polling coroutine - see the note in [startAuto] for
     * what that cost. Restored from [Keys.FILINGS_AT] at launch and stamped whenever a pass
     * actually runs, so it survives both a return to the foreground and a process death.
     *
     * Not a StateFlow: nothing on screen shows it, and a flow would cost a recomposition for
     * a value no composable reads.
     */
    private var lastFilingsAt = 0L

    /**
     * A quote wave was still running when the app went to the background (Round 66).
     *
     * `refresh()` runs on `fgScope`, which `setForeground(false)` cancels - so a wave in
     * flight is killed. Without this flag, a flick away and straight back inside
     * [RESUME_REFRESH_GRACE_MS] also skipped the replacement refresh, and the screen kept
     * prices from before the cancelled pass until the next automatic tick.
     */
    private var quotePassInterrupted = false

    /**
     * The Day Trading "success rate" button's own state - null until it has been pressed at
     * least once this session, then the last computed [com.tj.portfolio.data.DayTradingStats].
     * Not disk-cached: the underlying log is, so re-pressing the button after a restart costs
     * nothing but re-reading it and re-running [DayTradingEval.stats] over what disk already
     * has - cheap, pure arithmetic, no network unless something is still unresolved.
     */
    private val _dayTradingStats = MutableStateFlow<com.tj.portfolio.data.DayTradingStats?>(null)
    val dayTradingStats: StateFlow<com.tj.portfolio.data.DayTradingStats?> = _dayTradingStats.asStateFlow()

    private val _dayTradingStatsLoading = MutableStateFlow(false)
    val dayTradingStatsLoading: StateFlow<Boolean> = _dayTradingStatsLoading.asStateFlow()

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
     * EVERY Form 4 filed by anyone, anywhere - the "All companies" half of the Insider tab.
     *
     * A separate store from [_insiderFilings] on purpose: that one is disk-cached and capped
     * for a PORTFOLIO's handful of symbols, and merging an unbounded market-wide feed into it
     * would compete with a held stock's own filings for the same cap. This one is memory-only
     * and re-fetched from page 1 on every cold start - cheap, since it only ever runs while
     * "All companies" is the selected view and the Insider tab is open, the same "asleep
     * unless open" rule the rest of this app's live-refresh features follow.
     */
    private val _marketInsiders = MutableStateFlow<List<com.tj.portfolio.data.InsiderFiling>>(emptyList())
    val marketInsiders: StateFlow<List<com.tj.portfolio.data.InsiderFiling>> =
        _marketInsiders.asStateFlow()

    private val _marketInsiderLoading = MutableStateFlow(false)
    val marketInsiderLoading: StateFlow<Boolean> = _marketInsiderLoading.asStateFlow()

    /** EDGAR's own pagination offset the next "Load more" continues from. */
    private var marketInsiderPageStart = 0

    private var marketInsiderJob: Job? = null

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

    /**
     * The quote pass `refresh()` launched most recently (full test 2026-09-24, L-2). Re-entry
     * is judged on THIS - a cancelled job reports inactive at once - not on `_ui.loading`,
     * which a cancelled pass only clears in its `finally`, after its blocking read returns.
     * Coming back to the app inside that window used to hit the `loading` guard and start no
     * replacement pass, leaving prices as of before the glance for a whole interval.
     */
    private var quoteJob: Job? = null

    /**
     * Which `refresh()` pass is the current one - a counter, not a Job comparison, because a
     * pass with nothing to fetch finishes (and runs its `finally`) synchronously inside
     * `launch`, before [quoteJob] has even been assigned.
     */
    private var quoteGen = 0L

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
     *     (The batched quote wave in `refresh()` used to be listed here as another exception.
     *     It never was one: `refresh()` launches into THIS scope, so leaving the app has
     *     cancelled it since Round 59. The note was wrong for several rounds and is corrected
     *     rather than acted on - the wave is cheap and the return starts a fresh one, which
     *     `setForeground` now does even inside the resume grace window.)
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

    /**
     * The Feed pass's raw market headlines and full (60-row) Reddit list, with when they were
     * fetched - handed to `Research.build` while recent, so a rebuild does not download them a
     * second time (N-8). Null until the Feed has fetched them.
     */
    @Volatile private var feedMarketRaw: Pair<Long, List<com.tj.portfolio.data.NewsItem>>? = null
    @Volatile private var feedSocialRaw: Pair<Long, List<com.tj.portfolio.data.Trending>>? = null

    /** Whether the on-disk headline cache has been read into memory this foreground spell. */
    private var feedRestored: Boolean = false

    /**
     * Set when [onTrimMemory] dropped the sparklines, so only the next return re-reads them
     * (full test 2026-09-24, L-5). The old "any quote missing a series" test is defeated for
     * good by one symbol Yahoo never sends a series for - every resume then read and parsed
     * the whole quotes table to change nothing.
     */
    private var sparksTrimmed: Boolean = false

    /**
     * THIS ViewModel's [com.tj.portfolio.net.Http] disk cache, kept so [onCleared] detaches only
     * its own (full test 2026-09-24, L-6): a finishing activity's `onDestroy` can run after the
     * NEXT ViewModel's `init` attached, and an unconditional detach left that process on a
     * memory-only HTTP cache for the rest of its life.
     */
    private val httpDiskCache = object : com.tj.portfolio.net.Http.DiskCache {
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
    }

    /** Memoised feed de-duplication keys - see [storyKey] for why this exists. */
    private val storyKeys = HashMap<String, String>(MAX_FEED_ITEMS * 2)

    /**
     * Whether the app is actually on screen. The polling loop lives in viewModelScope,
     * which OUTLIVES the Activity being stopped - so before this existed the app kept
     * fetching every symbol every 15 seconds while sitting in the background, draining the
     * battery and doing work nobody could see.
     */
    private var foreground = true

    /**
     * Symbols a research price fill was asked for and has not yet completed (full test
     * 2026-09-24, L-3). A Claude answer runs on `viewModelScope` so it survives the app being
     * left; when it lands in the background its fill used to launch into the CANCELLED
     * `fgScope` and silently never run - and nothing asked again on return, so Claude's added
     * rows sat priceless (and its funds unfiltered for leverage) until the next rebuild.
     * Flushed by `setForeground(true)`; an entry leaves only when its fill has finished.
     */
    private val pendingPriceFill: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())

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
    private var cachedWatch: List<com.tj.portfolio.data.WatchEntry> = emptyList()
    /** Symbols with a %-since-added baseline lookup in flight, so opening the tab twice fast
     *  cannot fire the same chart fetch twice. */
    private val watchBaselineInFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Transactions in ledger order (ascending), straight from the database. */
    private var cachedTxns: List<Txn> = emptyList()
    /** The same rows in the newest-first order the Activity tab shows, sorted once. */
    private var cachedTxnsDesc: List<Txn> = emptyList()
    /** Positions from the last ledger replay. */
    private var cachedPositions: List<Position> = emptyList()

    /**
     * The four ledger-only figures - cash, net deposits, dividends, fees - computed where the
     * ledger changes rather than on every quote tick (Round 63 sweep).
     *
     * They are pure functions of [cachedTxns], and `Ledger.totals` was recomputing all four
     * on every `publish()`: four full scans of the whole transaction table, 240 times an hour,
     * on the main thread, always producing the same four numbers. `recompute()` is where the
     * ledger actually changes, and `reprice()` - which is what the tick calls - only re-values
     * it, so this is the natural home for them.
     */
    private var cachedSums: Ledger.LedgerSums = Ledger.sums(emptyList())

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
     * The ONE stock whose detail screen is open, or null (Round 63 sweep).
     *
     * ---- WHY THIS IS A SEPARATE SIGNAL FROM [newsVisible]
     *
     * It used to be the same one: the UI reported `newsVisible = (Feed tab || a stock open)`.
     * The consequence was that opening a single stock made the three-minute feed pass sweep
     * headlines for every held AND watched symbol - about 480 requests an hour on a
     * 24-symbol portfolio, twenty-three of every twenty-four for a symbol not on screen.
     *
     * And it bought nothing, which is the part that makes it a bug rather than a trade-off:
     * the open stock's own headlines do not come from that loop at all. `DetailScreen` reads
     * what `loadNews(symbol)` fetched for it directly, on its own TTL, when the screen
     * opened. The sweep was pure overhead attached to the wrong signal.
     *
     * So the two questions are now asked separately: the Feed tab wants EVERY symbol's
     * headlines, because that is the list it draws; a detail screen wants ITS symbol's, and
     * the feed pass keeps that one warm for free while it is open.
     */
    @Volatile private var newsSymbol: String? = null

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

    /**
     * How old each symbol's CORE numbers actually are - the disk row's own stamp, even when it
     * is past its TTL (full test 2026-09-24, S-3). [coreFetchedAt] is only set for a disk row
     * still inside its life or a network success, so with a 3-day-old core on disk and the
     * refresh failing, the verdict's `coreAt` fell back to `Fundamentals.fetched` - the merged
     * maximum, i.e. today's ratings fetch - and the "underlying data last refreshed ..." warning
     * never showed: the exact masking the 09-23 S-7 fix was for.
     */
    private val coreDataAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Symbols whose on-screen verdict was scored before its core numbers - see S-2. */
    private val recProvisional: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val ratingsFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Symbols whose dated-ratings fetch has already been attempted for a given day key, so the
     * one "try again with dates" recompute [settledForToday] allows cannot become a loop.
     *
     * DECLARED HERE, WITH THE OTHER FETCH MARKS, NOT NEXT TO THE FUNCTION THAT USES IT -
     * `tools/checkinit.py` rejects a property declared below the `init` block, because one that
     * is does not exist yet while `init` runs. That is not a style rule: it is what crashed
     * v4.6 on startup.
     *
     * DELIBERATELY NOT CLEARED BY THE MEMORY-TRIM HANDLER, unlike [ratingsFetchedAt] beside it.
     * A trim drops `_fundamentals` so the next screen open refetches, which is right for a tab
     * being looked at - but this mark exists to stop the app re-asking for the heaviest payload
     * it fetches on behalf of a symbol that simply has no rating history. Clearing it would
     * make every trim re-open that question for every such symbol.
     */
    private val recRatingsTried = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        sparksTrimmed = true

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
        // ---- AND ITS FETCH MARKS, for exactly the reason the two below say (Round 66).
        //
        // THE BUG THIS FIXES. `insiderAt` was added after this block and was not added to it.
        // `loadInsider`'s guard is "the map already has this symbol, or it was fetched inside
        // the TTL" - so after a trim the first test failed (map cleared) and the second one
        // fired, returning BEFORE the launch that would have repainted the section from
        // `_insiderFilings`, which this handler does not clear. Background the app, let the
        // system trim it, come back to an open stock, and its Form 4 section was blank for up
        // to half an hour with the filings sitting in memory the whole time.
        insiderAt.clear()
        // Same reasoning as the headlines: these are on disk as of v5, so dropping them
        // frees the heap without losing anything - loadFundamentals paints them straight
        // back from SQLite when the user reopens a stock. The fetch marks are cleared with
        // them, or the next open would find nothing in memory and still refuse to refetch.
        _fundamentals.value = emptyMap()
        coreFetchedAt.clear()
        coreDataAt.clear()
        ratingsFetchedAt.clear()
        // Same reasoning again: on disk since Round 58, so dropping them frees the heap and
        // costs one SQLite read when the tab is reopened.
        _holdings.value = emptyMap()
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
    private fun restoreFromCache(force: Boolean = false, fromInit: Boolean = false) {
        // Charts come back on EVERY resume, not just the first: a trim that ran while the
        // app was away is exactly when they need putting back.
        //
        // EXCEPT ON THE LAUNCH PATH (Round 66). `init` assigns `_quotes` from
        // `db.cachedQuotes()` three lines before calling this, so on that path
        // `restoreSparklines` would re-run the SAME query - reading every quote row and
        // JSON-parsing every spark blob a second time, before the first frame - to fill
        // sparks from rows the map already came from. Its early-out cannot save it either:
        // any tracked symbol Yahoo never supplied a series for (a freshly added watch entry,
        // a thin ticker) defeats the "every quote already has one" check, and then `changed`
        // can never become true, because the map and the disk rows are the same data.
        // AND ONLY AFTER A TRIM ACTUALLY DROPPED THEM (L-5) - see [sparksTrimmed].
        if (!fromInit && sparksTrimmed) {
            sparksTrimmed = false
            restoreSparklines()
        }
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
    private var researchJob: Job? = null

    /**
     * The ETF pass has its OWN job handle, not `researchJob`.
     *
     * Sharing one would mean opening the ETFs tab cancelled a stock rebuild that was halfway
     * through its eighteen requests - and then the stock tab would show its old data with no
     * sign anything had been interrupted. Two independent passes on two different clocks need
     * two handles.
     */
    private var etfJob: Job? = null
    private var enrichJob: Job? = null

    /** [startDayTradingLive]/[stopDayTradingLive]'s job handle. */
    private var dayTradingLiveJob: Job? = null
    /** True while the Day Trading tab wants the live loop running - see [setForeground]'s
     *  note on why the job handle alone is not enough to know whether to restart it. */
    private var dayTradingLiveWanted = false
    /** When set, the live loop sweeps ONLY this symbol - a detail screen showing its plan,
     *  not the Day Trading list. See [startDayTradingLive]'s `only`. */
    private var dayTradingLiveOnly: String? = null
    /** When [cacheResearch] last wrote the research set to disk, and whether a throttled
     *  write is still owed - see its `throttleMs`. */
    /** When a Claude Research answer was last imported (R1-4) - see [researchStale]. In memory:
     *  after a process death the list simply follows its own clocks again. */
    private var researchImportedAt = 0L
    private var researchPersistedAt = 0L
    @Volatile private var researchPersistOwed = false
    /** Publish order of research-cache writes, and the newest one on disk (A-11) - see
     *  [persistResearch]. */
    private val researchPersistSeq = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var researchWrittenSeq = 0L
    private val researchPersistLock = kotlinx.coroutines.sync.Mutex()
    /** True once [enrichDayTradingVisible]'s one-time full-section sweep has run for the
     *  current rebuild - see its own header. Reset on every rebuild. */
    private var dayTradingSweepDone = false

    /**
     * Bumped by every rebuild of the Day Trading list (full test 2026-09-24, D-4). A one-time
     * sweep reads it when it starts and only marks the list swept - and sorts it - if no
     * rebuild landed while it was fetching: otherwise it would declare the NEW list done having
     * fetched only the symbols the old one shared, and the rest sat plan-less until 04:00.
     */
    private var dayTradingRebuildGen = 0L

    /** When the live loop last put fresh numbers on the Day Trading rows (D-14). */
    @Volatile private var dayTradingLiveAt = 0L

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
    /**
     * A PER-KEY RETRY CLOCK FOR THINGS THAT CAN SIMPLY FAIL.
     *
     * ROUND 59. Two of the app's fetches recorded when they last SUCCEEDED and nothing at
     * all about when they last FAILED, and both turned into request storms as a result:
     *
     *  - `loadChart` stamped a "last fetched" field and then never read it. A chart that
     *    cannot be
     *    had - a delisted ticker, a 404, a range the provider refuses - left no entry in
     *    `_charts`, so the freshness guard fell straight through and the detail screen asked
     *    again on EVERY quote tick. Roughly 240 requests an hour for a picture that is never
     *    going to arrive, which is the exact traffic shape Round 56 spent a whole round
     *    removing and the fastest way to be rate-limited.
     *  - `refreshSparklines` was worse, and it was a regression from Round 58's own fix. That
     *    fix un-marked every symbol whose series failed so a TRANSIENT failure would retry on
     *    the next tick instead of sitting out five minutes - which is what put TJ's missing
     *    charts right - but it made a PERMANENT failure retry every fifteen seconds forever.
     *
     * (Round 63 removed that write-only field, and the holdings one beside it, once this
     * clock had made both of them dead: a value written on every fetch and read by nobody is
     * state that grows without bound and tells the next reader something untrue.)
     *
     * Both need the same thing and it is not "a longer TTL": a fetch that failed should be
     * retried SOON in case the failure was a passing one, and then progressively less often
     * as the evidence mounts that it is not. That is a backoff, and the app already has one
     * whose shape has been reasoned about - `Http.unreachableBackoffMs` - so this uses the
     * same curve rather than inventing a second answer to the same question.
     *
     * A SUCCESS CLEARS THE COUNT. The window is about consecutive failures; one good answer
     * means the next failure starts again at thirty seconds, not at five minutes.
     */
    /** Failure backoff for chart fetches, keyed by [chartKey]. */
    private val chartRetry = RetryClock()

    /** Failure backoff for the intraday candle series, keyed by symbol. */
    private val sparkRetry = RetryClock()

    /** Failure backoff for the Day Trading technicals, keyed by symbol (N-8): a ticker Yahoo
     *  cannot chart cost four requests every 30 s for as long as the tab was open. */
    private val dayTradingTechRetry = RetryClock()

    /** Failure backoff for fund-holdings lookups, keyed by symbol. */
    private val holdingsRetry = RetryClock()

    /**
     * When a per-symbol EDGAR pass last COMPLETED, keyed by symbol (Round 63 sweep).
     *
     * Records the pass, not its result: a company that filed nothing this month is an answer,
     * and the previous guard - "do we hold filings for it" - could never be satisfied by one.
     */
    private val insiderAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Failure backoff for the two Research builds, keyed by section (Round 63).
     *
     * THE PROBLEM IT SOLVES. Both builds are triggered by a `LaunchedEffect` that has to
     * re-evaluate when the shared busy flag clears - otherwise a request made while the other
     * pass was in flight is dropped and never retried, and the tab sits empty. But a FAILED
     * pass leaves its list stale, so re-evaluating on that same signal would start another
     * build immediately: eighteen requests, fail, eighteen more, indefinitely, against
     * providers that were almost certainly rate-limiting in the first place.
     *
     * The backoff is what tells the two apart. A pass that succeeded clears the key; a pass
     * that came back empty sits out 30s, then a minute, then two, then four, capped at five.
     * A pull-to-refresh or the refresh button passes `force`, which ignores it entirely - the
     * user asking is always "try it now, whatever".
     */
    private val researchRetry = RetryClock()

    /**
     * Failure backoff for the two per-symbol fundamentals fetches, keyed "$symbol|core" and
     * "$symbol|ratings" (Round 63 sweep). See the note in `loadFundamentals`.
     */
    private val fundRetry = RetryClock()

    /**
     * Symbols whose cached chart rows have already been read off disk this session.
     *
     * The disk read pulls EVERY range for the symbol in one query, so it is worth doing once
     * and never again: switching between 1D and 1Y then back must not re-query SQLite.
     */
    private val chartDiskRead = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The disk read [chartDiskRead] has started but not finished, per symbol (full test
     * 2026-09-24, C-11). A second range loading inside that window saw the symbol marked as
     * read, found nothing in memory yet and went to the network for a series SQLite was about
     * to hand over. It now waits for the read instead.
     */
    private val chartDiskInFlight =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    // ================================================================ FUND HOLDINGS

    /** What each fund holds, by symbol. Absent means "not looked up yet". */
    private val _holdings = MutableStateFlow<Map<String, FundHoldings>>(emptyMap())
    val holdings: StateFlow<Map<String, FundHoldings>> = _holdings.asStateFlow()

    /** Symbols with a holdings fetch in flight. */
    private val _holdingsLoading = MutableStateFlow<Set<String>>(emptySet())
    val holdingsLoading: StateFlow<Set<String>> = _holdingsLoading.asStateFlow()

    /** When each symbol's holdings were last pulled from the NETWORK - see [coreFetchedAt]. */

    // ============================================================ RECOMMENDATION

    /** Today's BUY/HOLD/SELL for each symbol. Absent means "not computed yet today". */
    private val _recommendations = MutableStateFlow<Map<String, Recommendation>>(emptyMap())
    val recommendations: StateFlow<Map<String, Recommendation>> = _recommendations.asStateFlow()

    /** Symbols with a recommendation compute in flight. */
    private val _recLoading = MutableStateFlow<Set<String>>(emptySet())
    val recLoading: StateFlow<Set<String>> = _recLoading.asStateFlow()

    init {
        com.tj.portfolio.util.MemoryTrim.register(trimListener)
        // FIRST, before anything can make a request: a cache attached after the first feed
        // pass has already run misses exactly the requests it was meant to save.
        attachHttpDiskCache()
        val cached = db.cachedQuotes()
        _quotes.value = cached
        // A cached series written after the last close is the whole session - see
        // [sparkIsFinal]. Seeding its clock from the row's own stamp is what stops a night-time
        // cold start re-downloading every holding's unchanged line (full-tests audit, N-M3).
        // A row saved mid-session is not seeded, so its series is refreshed as before.
        cached.values.forEach { q ->
            if (q.spark.isNotEmpty() && q.updated > 0L &&
                com.tj.portfolio.net.MarketClock.phase(q.updated) != com.tj.portfolio.net.MarketClock.Phase.OPEN
            ) sparkAt[q.symbol] = q.updated
        }
        // so the header reads "Prices updated 2h ago" offline instead of showing nothing
        cached.values.maxOfOrNull { it.updated }?.takeIf { it > 0 }?.let {
            _ui.value = _ui.value.copy(lastRefresh = it)
        }
        _lastImport.value = db.lastImport()
        // Seeded BEFORE recompute(), which copies the existing state - set it after and the
        // first publish would overwrite it with the default.
        _ui.value = _ui.value.copy(plMode = com.tj.portfolio.data.PlMode.byName(db.get(Keys.PL_MODE)))
        recompute()
        tidyDownloadsOnce()
        checkForRecoverableBackup()
        autoBackupIfDue()
        loadCachedAdvice()
        loadPendingImport()
        loadCachedResearch()
        loadCachedInsider()
        restoreFromCache(fromInit = true)
        db.get(Keys.FEED_AT).toLongOrNull()?.takeIf { it > 0 }?.let { _feedAt.value = it }
        db.get(Keys.FILINGS_AT).toLongOrNull()?.takeIf { it > 0 }?.let { lastFilingsAt = it }
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
        // The failure backoff goes with them, or a chart that had been backing off would sit
        // out its window after the user explicitly asked for a clean slate.
        chartRetry.clear()
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

    /**
     * Switch which half of a P/L figure is the big one.
     *
     * The write is on `viewModelScope`, not `fgScope`: a preference the user has just changed
     * must reach disk even if they leave the app in the same second. It deliberately does NOT
     * go through `computeAffecting` / `recompute()` - nothing about the ledger changes, only
     * which of two already-computed numbers is drawn larger, and replaying every transaction
     * for that would be the kind of waste this project has removed twice.
     */
    fun setPlMode(mode: com.tj.portfolio.data.PlMode) {
        if (_ui.value.plMode == mode) return
        _ui.value = _ui.value.copy(plMode = mode)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.set(Keys.PL_MODE, mode.name) }
        }
    }

    /** Flip it. What the tappable figures on the summary card call. */
    fun togglePlMode() = setPlMode(_ui.value.plMode.flipped)

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
    private fun sessionInstant(): Long =
        sessionInstantFrom(_quotes.value.values, System.currentTimeMillis())

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
        cachedPositions = Ledger.positions(txns, overrides, costMethod(), session, replayRepairBelowId(), replayRepairRanges())
        cachedTxns = txns
        cachedSums = Ledger.sums(txns)
        cachedTxnsDesc = txns.sortedWith(
            compareByDescending<Txn> { it.date }.thenByDescending { it.id }
        )
        cachedWatch = db.watchlistEntries()
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
        // A MAP, not `watch.any { }` inside the loop - `watch` is a List and the positions
        // loop would otherwise be quadratic in the number of tracked symbols. Keyed by symbol
        // so a held-and-watched row can also carry its %-since-added anchor below.
        val watchBySymbol = watch.associateBy { it.symbol }
        for (p in open) {
            val q = quotes[p.symbol]
            val w = watchBySymbol[p.symbol]
            rows.add(
                Row(
                    p.symbol, q?.name ?: "", p, q, false, w != null, label,
                    watchedAt = w?.addedAt ?: 0L, watchedBasePrice = w?.addedPrice ?: 0.0
                )
            )
        }
        for (w in watch) {
            if (open.any { it.symbol == w.symbol }) continue
            val q = quotes[w.symbol]
            // `watched = true` by construction: this loop IS the watchlist.
            rows.add(
                Row(
                    w.symbol, q?.name ?: "", null, q, true, true, label,
                    watchedAt = w.addedAt, watchedBasePrice = w.addedPrice
                )
            )
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
            totals = Ledger.totals(
                cachedTxns, positions, quotes, cashOverrideOrNull(), cachedSums
            )
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
        if (_ui.value.loading && quoteJob?.isActive != false) {
            if (manual) _ui.value =
                _ui.value.copy(manualRefresh = true, refreshSource = PULL_PRICES)
            return
        }
        // A pull-to-refresh is the user saying "try again NOW", so it clears any host the app
        // had decided to leave alone. Without this, coming back into signal and pulling down
        // would silently do nothing while an invisible backoff timer ran down.
        if (manual) {
            com.tj.portfolio.net.Http.clearCooldowns()
            // The failure backoffs go with them. A deliberate pull is the user saying "try
            // it now", and a chart that has been quietly backing off for five minutes is
            // precisely what they are pulling down to fix.
            chartRetry.clear()
            sparkRetry.clear()
            holdingsRetry.clear()
            fundRetry.clear()
            dayTradingTechRetry.clear()
            // Same reasoning as the cooldowns: a deliberate pull is exactly when the batched
            // quote endpoint should be tried again, and an invisible "we gave up on that
            // three hours ago" flag would make the gesture quietly do less than it appears to.
            MarketData.resetBatchState()
            // And the per-symbol fallback's own backoff, for the same reason: a ticker the
            // app has been quietly skipping is precisely what a deliberate pull is for.
            MarketData.resetFallbackState()
        }
        // ---------------------------------------------------------------------------------
        // ROUND 59: `fgScope`, NOT `viewModelScope`. This was the last on-demand network path
        // still outliving the app going away.
        //
        // Round 57 moved eleven fetches onto `fgScope` and recorded the poll loop as
        // "cancelled" - and the LOOP is: `autoJob.cancel()` stops it. But the loop does not
        // do the work, it calls `refresh()`, which launched a coroutine of its OWN into
        // `viewModelScope`. Cancelling the loop therefore left an in-flight pass running to
        // completion or to its fifteen-second timeout, with the socket never disconnected -
        // exactly the behaviour `Http`'s cancel-and-disconnect exists to prevent.
        //
        // It also stranded `loading = true` for the length of that request, and the guard at
        // the top of this function returns early while it is set - so a user who switched
        // away and straight back got no refresh at all and looked at stale prices.
        //
        // THE DATABASE WRITE STAYS ON `viewModelScope` (see below). That is the whole rule
        // this project follows: if the answer is only useful while the screen is up, it is
        // cancellable; if it must not be lost, it is not.
        val gen = ++quoteGen
        quoteJob = fgScope.launch {
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
                // ...AND WHAT IS ON SCREEN, which "everything tracked" is not (full-tests audit
                // 2026-09-22, N-L5): a stock opened from Search or Research is neither held nor
                // watched, so pulling down on its own detail screen refreshed every price in the
                // app except the one being looked at.
                val onScreen = symbolsToQuote()
                val symbols = if (manual) (allTrackedSymbols() + onScreen).distinct() else onScreen
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
                // VISIBLE rows only, on a pull too (N-L5) - see [refreshSparklines]: a sparkline
                // that is not on screen is not worth a request, and a pull on the Portfolio tab
                // used to fetch every watched symbol's line as well.
                //
                // AND ONLY WHERE A ROW LINE IS DRAWN (full test 2026-09-23, N-4): the Portfolio
                // and Watch lists. A detail screen polled its symbol's row sparkline every five
                // minutes for a line no screen draws there - the chart replaced it in Round 58.
                // Returning to a list re-scopes and refreshes it then.
                if (visibleScope == VisibleScope.Portfolio || visibleScope == VisibleScope.Watchlist) {
                    refreshSparklines(onScreen)
                }

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
                // REBUILT FROM THE CURRENT STATE, NOT FROM `previous`. `refreshSparklines`
                // writes into `_quotes` from its own coroutine, and this function suspended
                // twice on the way here (the fetch, and - before Round 59 moved it off the
                // critical path - the cache write), so a sparkline that landed in between
                // would be overwritten by a map snapshotted before it existed. Because
                // `sparkAt` is marked before the fetch, that lost write also cost a
                // five-minute wait for the retry, with `carryDisplayFields` keeping the old
                // series alive so nothing looked wrong. Reading `_quotes.value` here rather
                // than reusing `previous` is what makes that impossible.
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

                // ---- persist, AFTER the merge and on a scope that survives backgrounding.
                //
                // ON `viewModelScope`, so a price already paid for reaches disk even if the
                // user walks away a millisecond later - the fetch above is cancellable, this
                // deliberately is not.
                //
                // AND IT WRITES `merged`, NOT `stamped`, which is the difference between a
                // correct write and a racy one. `stamped` is the pre-merge snapshot: it does
                // not carry a sparkline that `refreshSparklines` or `adoptAsSparkline` landed
                // while the fetch was in flight. Written after them, it would put a
                // series-less row over a good one, and the next cold start would paint a
                // blank chart from it. `merged` is what is actually on screen.
                val toPersist = stamped.mapNotNull { merged[it.symbol] }
                if (toPersist.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheQuotes(toPersist) }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // ---- BACKGROUNDING THE APP IS NOT A REFRESH FAILURE.
                //
                // `refresh()` runs on `fgScope`, and `setForeground(false)` cancels that scope
                // by design. `CancellationException` IS an `Exception`, so it fell into the
                // branch below and wrote "Refresh failed: StandaloneCoroutine was cancelled"
                // into the UI state - a message about the app's own lifecycle, phrased as if
                // the network had broken.
                //
                // It was usually invisible because coming back calls `refresh()` again, which
                // clears `error`. But cancellation unwinds asynchronously: flick away and
                // straight back, and the resume-path `refresh()` can hit the `loading` guard
                // and return BEFORE the cancelled pass's `finally` clears the flag. No
                // replacement pass then runs, and the Portfolio header and Watchlist show that
                // sentence until the next tick - 15 seconds with the market open, 15 minutes
                // with it shut. Rethrowing also restores correct structured concurrency: a
                // cancelled child must not report itself as completed-with-error.
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = "Refresh failed: ${e.message}")
            } finally {
                // must always clear `loading`, or every later refresh() returns at the guard
                // above and pull-to-refresh is dead for the session - UNLESS a newer pass has
                // already replaced this cancelled one (L-2): its flag is not ours to clear.
                if (gen == quoteGen) _ui.value = _ui.value.copy(loading = false)
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
        // A symbol that has been failing is asked for less and less often - see [RetryClock].
        // Without this, Round 58's fix for TJ's missing charts (un-mark a failed symbol so it
        // retries promptly) turned a PERMANENTLY dead ticker into a fetch every fifteen
        // seconds for the life of the session.
        // ---- AND SKIP WHAT THE 1D CHART IS ALREADY HOLDING (Round 63 sweep).
        //
        // The 1D chart and the row sparkline are the SAME Yahoo URL on the SAME five-minute
        // clock. Round 58 closed one direction of that - `adoptAsSparkline` feeds a fetched
        // 1D series straight into the quote and stamps `sparkAt` as this pass would have -
        // but this filter never looked the other way. So on the tick where both TTLs lapse
        // together, with a detail screen open, the identical ~30 KB body was pulled twice:
        // about a dozen duplicated requests an hour, for nothing.
        //
        // The conditions are the ones `adoptAsSparkline` itself insists on - a regular-session
        // series inside its TTL - so this skips only what that function would have adopted
        // anyway. Anything else still fetches normally.
        val chartsNow = _charts.value
        fun heldByChart(sym: String): Boolean {
            // `sparkAt` IS THE PROOF. Holding a fresh D1 chart is not the same as having
            // adopted it: `adoptAsSparkline` returns early when no quote exists yet, and the
            // DISK-RESTORE path in `loadChart` publishes a cached series without calling it at
            // all. Skipping on the chart alone therefore left a cold start into a detail
            // screen showing a stale sparkline for up to five minutes - a gap this filter's
            // own comment claimed could not exist. The mark is only ever set where the series
            // has actually been written into the quote, so requiring it makes the claim true.
            if (sparkAt[sym] == null) return false
            val c = chartsNow[chartKey(sym, ChartRange.D1)] ?: return false
            return c.regularOnly && !c.isEmpty && !c.stale(now)
        }
        val due = symbols.filter {
            now - (sparkAt[it] ?: 0L) > SPARK_REFRESH_MS &&
                !sparkIsFinal(sparkAt[it] ?: 0L, now) &&
                !sparkRetry.blocked(it, now) && !heldByChart(it)
        }
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
            fresh.forEach { (sym, v) ->
                if (v == null) {
                    // Un-marked so the ordinary five-minute clock does not also hold it back,
                    // and recorded as a failure so the backoff decides when it is next tried.
                    sparkAt.remove(sym)
                    sparkRetry.failure(sym)
                } else {
                    sparkRetry.success(sym)
                }
            }
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
        else (Ledger.positions(db.allTxns(), db.overrides(), costMethod(), repairBelowId = replayRepairBelowId(), repairRanges = replayRepairRanges())
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
    /** See [com.tj.portfolio.util.Connectivity] - optimistic, so it only ever skips a pass
     *  when Android is certain there is no active network. */
    private fun online(): Boolean =
        com.tj.portfolio.util.Connectivity.isOnline(getApplication())

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
    fun setNewsVisible(visible: Boolean, detailSymbol: String? = null) {
        newsSymbol = detailSymbol?.uppercase()
        val arriving = visible && !newsVisible
        // Both transitions are captured BEFORE the flag moves, because both are about the
        // EDGE. `leaving` in particular: stamping on every call with `visible == false` would
        // push the grace window forward continuously while the user sits on another tab, so
        // the pass would believe it was always inside it and the gate would never close.
        val leaving = !visible && newsVisible
        // ---- ARRIVING ON THE FEED TAB REFRESHES IT (Round 63 sweep).
        //
        // The other half of gating the seven market-wide feeds on visibility. With the timer
        // no longer pulling them for a screen nobody is looking at, the tab has to fetch when
        // it is OPENED, or its list would be as old as the last time it happened to be open
        // rather than at most one interval old. Which would be a worse app in exchange for
        // saving requests, and that trade is never the right one here.
        //
        // THE FLAG IS SET BEFORE THE CALL, and that ordering is load-bearing.
        // `viewModelScope` is `Main.immediate` and this runs on the main thread, so
        // `refreshFeed` executes its body IN PLACE up to its first suspension point - which is
        // well after it computes `feedDue`. Called with `newsVisible` still false, the pass
        // that exists to fill the tab computed "nobody is looking at headlines", fetched
        // nothing, and stamped the timestamp that suppresses the next attempt.
        newsVisible = visible
        // Guarded three ways so this cannot become its own request generator: only on the
        // transition INTO visible, only when what is held is already past the interval it
        // would have been refreshed on anyway, and `refreshFeed` has its own re-entry guard.
        if (arriving) {
            val age = System.currentTimeMillis() - _feedAt.value
            if (age > MarketClock.feedIntervalSecs() * 1000L) refreshFeed(includeInsider = false)
        }
        // STAMPED WHEN IT STOPS BEING VISIBLE, not when it starts. Stamping on the way IN
        // makes the grace window measure how long ago the user ARRIVED, so someone who reads
        // the Feed for ten minutes and then switches tabs has no grace at all - the window
        // has already expired at the exact moment it is needed, and a pass in flight abandons
        // its per-symbol loop half-done. Stamping on the way OUT measures what the window is
        // actually about: how long ago the user stopped looking.
        if (leaving) newsVisibleAt = System.currentTimeMillis()
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
     * `refreshFeed` is the expensive one and is cancelled outright. So is `refresh()` - it
     * runs on `fgScope` like everything else the screen asked for, and the whole point of
     * that scope is that leaving the app cancels it. An earlier version of this note claimed
     * the quote wave was "left to finish", which it has not been since Round 59; a resume
     * that finds a cancelled pass now simply starts a new one, which is the paragraph below.
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
            //
            // ---- UNLESS THE PASS THAT WAS RUNNING GOT CANCELLED (Round 66).
            //
            // Leaving the app cancels `fgScope`, and `refresh()` runs on it. Flick away and
            // back inside the grace window while a wave is in flight and BOTH things happen:
            // the wave is killed, and the grace check then skips the replacement. Prices
            // stayed as of before the cancelled pass until the next tick - fifteen seconds
            // with the market open, fifteen MINUTES with it shut. One flag closes it, and it
            // costs one wave that the user's return had already justified.
            val interrupted = quotePassInterrupted
            quotePassInterrupted = false
            if (wentBackgroundAt == 0L || away > RESUME_REFRESH_GRACE_MS || interrupted) refresh()
            startAuto()
            // See [startDayTradingLive]'s note: the old job died with the old `fgScope` above,
            // and only relaunching it here (never unconditionally - only when the tab was
            // actually left running) brings it back for a Day Trading tab still on screen.
            if (dayTradingLiveWanted) startDayTradingLive(dayTradingLiveOnly)
            // A research answer that landed while the app was away gets its prices now (L-3).
            val owed = synchronized(pendingPriceFill) { pendingPriceFill.toList() }
            if (owed.isNotEmpty()) fillResearchPrices(owed.take(MAX_PRICE_FILL))
        } else {
            wentBackgroundAt = System.currentTimeMillis()
            // Read BEFORE the cancellation below, which is what makes it true.
            quotePassInterrupted = _ui.value.loading
            autoJob?.cancel()
            autoJob = null
            feedJob?.cancel()
            feedJob = null
            insiderJob?.cancel()
            insiderJob = null
            marketInsiderJob?.cancel()
            marketInsiderJob = null
            // EVERYTHING ELSE THE SCREEN ASKED FOR, IN ONE CANCELLATION. See [fgScope] for
            // the list of what this reaches and what it deliberately does not.
            fgScope.coroutineContext[Job]?.cancel()
            researchJob = null
            enrichJob = null
            searchJob = null
            // The live loop's last few levels, if a throttled write was skipped - see D-L8.
            flushResearchCache()
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
    /** What the automatic pass should do this tick. See [passDue]. */
    internal data class DuePass(val feed: Boolean, val filings: Boolean) {
        val anything: Boolean get() = feed || filings
    }

    /**
     * IS EITHER SLOW PASS DUE? Pure, so it can be tested (Round 66).
     *
     * ---- WHY IT IS "OR", NOT "AND"
     *
     * SEC filings ride INSIDE the feed pass, which reads as though filings should only be
     * considered when the feed is already going. They must not be, and the reason is that the
     * feed's mark is stamped from more than one place: opening the Feed tab fires its own
     * pass through `setNewsVisible(true)`. Gate filings on "the feed happens to be due" and a
     * user who checks the Feed regularly keeps `_feedAt` permanently fresh out of band, so
     * the filings branch never gets a turn - which is exactly the bug this function exists to
     * fix, arriving through a different door.
     *
     * Both arms are wall-clock comparisons against persisted marks rather than counters, so
     * neither is reset by the app being backgrounded, by the polling coroutine being
     * relaunched, or by the process being killed.
     */
    internal fun passDue(
        now: Long,
        feedAt: Long,
        filingsAt: Long,
        feedIntervalSecs: Int
    ): DuePass = DuePass(
        feed = feedIntervalSecs > 0 && now - feedAt >= feedIntervalSecs * 1000L,
        filings = now - filingsAt >= INSIDER_REFRESH_SECS * 1000L
    )

    private fun startAuto() {
        autoJob?.cancel()
        if (refreshSecs() <= 0 || !foreground) return
        // ---- NO COUNTERS IN HERE (Round 66).
        //
        // THE BUG THIS FIXES. The feed and filings cadences used to be `var sinceFeedRefresh`
        // and `var sinceFilings` declared INSIDE this coroutine. `startAuto()` cancels and
        // relaunches it, and `setForeground(true)` calls `startAuto()` - so both counters
        // restarted at zero every single time the user came back to the app. They were not
        // measuring elapsed time; they were measuring UNINTERRUPTED FOREGROUND SECONDS.
        //
        // For the feed (three minutes with the market open) that mostly still fired. For SEC
        // filings it did not: `INSIDER_REFRESH_SECS` is half an hour, and half an hour of one
        // continuous foreground session is not how a phone is used. On TJ's phone the Insider
        // tab therefore never refreshed itself at all - it kept whatever the cache restored,
        // aged rows out of its own 30-day window on each launch, and only ever gained a filing
        // if he happened to open the tab while it was completely empty (`refreshInsidersIfEmpty`
        // fires only on empty). Data that was free to refresh, and never was.
        //
        // Both cadences are now wall-clock marks that survive the relaunch, and both are
        // persisted, so they also survive the process. See [Keys.FEED_AT], [Keys.FILINGS_AT].
        autoJob = viewModelScope.launch {
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
                // OFFLINE MEANS SKIP, NOT "TRY AND FAIL". Firing a pass with no network
                // wakes the radio, fails every socket, and counts three of those into a
                // per-host cooldown that escalates towards five minutes - so a tunnel cost
                // battery going in and a stale screen for minutes coming out. Only the
                // AUTOMATIC path consults this; a deliberate pull-to-refresh always tries,
                // because the user may know something the connectivity manager does not.
                // ONE ANSWER PER TICK, used by both passes below. Asked once because it is a
                // binder call and because the two passes should agree with each other.
                val haveNetwork = online()
                if (visibleScope != VisibleScope.None && !pricesAreFinal() && haveNetwork) {
                    refresh()
                }

                // The feed is gated on the network for the same reason the quotes are: with
                // no signal every feed request fails, and three failures to the same host arm
                // a cooldown that outlives the dead spot by minutes. Because the marks below
                // are wall-clock, a dead spot costs nothing - the pass runs on the first tick
                // with a signal rather than restarting a countdown.
                if (haveNetwork) {
                    val due = passDue(
                        now = System.currentTimeMillis(),
                        feedAt = _feedAt.value,
                        filingsAt = lastFilingsAt,
                        feedIntervalSecs = MarketClock.feedIntervalSecs()
                    )
                    if (due.anything) {
                        // refreshFeed() pulls the same headlines and writes them into the
                        // per-symbol news map too, so calling refreshAllNews() here as well
                        // would fetch everything twice. A filings-only pass does cost a feed
                        // pass that was not otherwise due - but at most one every half hour,
                        // which is the cadence filings ride anyway because they are legally
                        // up to two business days behind.
                        refreshFeed(includeInsider = due.filings)
                    }
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
        // ---- THE TWO REASONS NOTHING IS BEING FETCHED (Round 66 audit, EXP-2).
        //
        // THE BUG THIS FIXES. This line's own comment calls it "what the app is ACTUALLY doing
        // right now", and it consulted only the market clock and the user's interval - never
        // the two conditions that actually gate a pass. So on the Settings tab, which is
        // `VisibleScope.None` by construction and where the poll loop skips the quote pass
        // entirely, it read "Open - updating every 15s" while making exactly zero requests.
        // The paragraph six lines below it on the same screen says the opposite, correctly:
        // "the Activity, Advice and Settings tabs show no prices, so the app stops asking for
        // them entirely while you are on those." A screen that contradicts itself teaches the
        // reader to trust neither half.
        //
        // The same gap swallowed `pricesAreFinal()`: at 3am on a Sunday with the post-close
        // quotes already held, nothing is fetched at all and the line still promised a
        // fifteen-minute tick - which is precisely the "why are prices not moving?" confusion
        // this line was written to prevent.
        val phaseNow = MarketClock.label()
        if (visibleScope == VisibleScope.None) {
            return "$phaseNow - paused while you are on this tab. Prices resume the moment " +
                "one is on screen."
        }
        if (pricesAreFinal()) {
            return "$phaseNow - prices are final for this session, so nothing is being " +
                "requested until the next one opens."
        }
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
                .take(MAX_SYMBOL_NEWS)
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
        // ---- A RECENT ATTEMPT IS AN ANSWER, EVEN AN EMPTY ONE (full test 2026-09-23, N-11). The
        // stamp used to be set only when headlines came back, so a thinly covered symbol - or
        // any outage - re-ran all three or four sources on every open and every resume; and
        // after a memory trim cleared `_news`, a symbol fetched seconds earlier went back to
        // the network although its headlines were on disk. Now: inside the window, repaint from
        // disk if memory lost them, and ask nobody.
        if (!force && fresh) {
            if (_news.value[symbol].isNullOrEmpty() && !_newsLoading.value.contains(symbol)) {
                fgScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { db.cachedNewsFor(symbol) }.getOrDefault(emptyList())
                    }
                    if (saved.isNotEmpty() && _news.value[symbol].isNullOrEmpty()) {
                        _news.value = _news.value + (symbol to saved)
                    }
                }
            }
            return
        }
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
                // Stamped whatever came back - see the N-11 note at the top of this function.
                if (items.isEmpty()) deepNewsAt[symbol] = System.currentTimeMillis()
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
                    // ---- THE BLURB IS WRITTEN THROUGH WITH THE STORY (Round 66).
                    //
                    // THE BUG THIS FIXES. `summaries = emptyMap()` threw away the
                    // `NewsItem.summary` this pass had just fetched, and this is the only
                    // place one could ever be supplied - so the `summary` column added for it
                    // was written as '' for every row ever stored. `DetailScreen` only draws
                    // the blurb when it is non-blank, so: open a stock and see headlines with
                    // their one-line summaries; leave, come back, and the list repaints from
                    // cache with every blurb gone until a fetch lands - and stories that have
                    // since fallen out of the source's window never get one back. The comment
                    // below says the write-through exists "so the next open starts from
                    // these"; it was starting from a degraded copy.
                    //
                    // Built ONCE and zipped by id, rather than mapping twice: the FeedItem's
                    // id is derived, so a second pass to build the map would have to
                    // reproduce that derivation and could drift from it.
                    val rows = items.map {
                        com.tj.portfolio.data.FeedItem(
                            kind = com.tj.portfolio.data.FeedItem.NEWS,
                            symbol = symbol, title = it.title, url = it.url,
                            source = it.source, published = it.published,
                            owned = owned
                        )
                    }
                    val blurbs = rows.indices
                        .filter { items[it].summary.isNotBlank() }
                        .associate { rows[it].id to items[it].summary }
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { db.cacheNews(rows, summaries = blurbs) }
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
        val saved = runCatching {
            db.insertTxn(
                Txn(
                    type = type, symbol = symbol?.uppercase()?.ifBlank { null },
                    quantity = qty, price = price, amount = signed,
                    fees = fees, date = date, note = note, source = "MANUAL"
                )
            )
        }
        // A failed write says so (A-5) - it used to be counted as saved.
        saved.exceptionOrNull()?.let { toast("Couldn't save that transaction: ${it.message}"); return }
        recompute()
        refresh()
        warnIfOverridden(type, symbol)
    }

    /**
     * Insert an already-built Txn (its amount is already signed by the editor). [savedMsg] is
     * the caller's "saved" toast - passed in rather than shown after this returns, because a
     * toast set afterwards would overwrite the override warning below before anyone saw it.
     */
    fun addTxnRecord(t: Txn, savedMsg: String? = null) {
        // A failed write says so (A-5), instead of the caller's "saved".
        runCatching { db.insertTxn(t) }.exceptionOrNull()?.let {
            toast("Couldn't save that transaction: ${it.message}"); return
        }
        recompute()
        refresh()
        warnIfOverridden(t.type, t.symbol, savedMsg)
    }

    fun updateTxn(t: Txn, savedMsg: String? = null) {
        // A DATE THE USER HAS SET IS NO LONGER A GUESS (diff review 2026-09-23, R-8). The
        // "date estimated" marker keeps a row out of "bought today" (A-3); left on after Tj
        // corrects the date, a trade really made today would never show in the Today column.
        val old = if (t.dateEstimated) db.allTxns().firstOrNull { it.id == t.id } else null
        val fixed = if (old != null && old.date != t.date) t.copy(
            note = t.note?.replace(Regex("\\s*-?\\s*" + Txn.DATE_ESTIMATED, RegexOption.IGNORE_CASE), "")
                ?.trim()?.trim('-')?.trim()?.ifBlank { null }
        ) else t
        db.updateTxn(fixed); recompute(); refresh(); warnIfOverridden(fixed.type, fixed.symbol, savedMsg)
    }

    /**
     * SAY SO WHEN A NEW TRADE CANNOT MOVE THE POSITION (full-tests audit, 2026-09-22). A manual
     * override replaces what the ledger works out - that is its job - so a BUY, SELL or SPLIT
     * entered for an overridden symbol changes the cash but not the shares or cost the app
     * shows. That is easy to have forgotten by the time the next trade is entered, and nothing
     * on the Portfolio list marks an overridden row, so the trade looks like it was ignored.
     */
    private fun warnIfOverridden(type: String, symbol: String?, otherwise: String? = null) {
        val ov = symbol?.takeIf { it.isNotBlank() }
            ?.takeIf { type == TxnType.BUY || type == TxnType.SELL || type == TxnType.SPLIT }
            ?.let { overrideFor(it) }
        if (ov == null) { if (otherwise != null) toast(otherwise); return }
        val what = when {
            ov.shares != null && ov.avgCost != null -> "shares and average cost"
            ov.shares != null -> "share count"
            else -> "average cost"
        }
        toast("${ov.symbol.uppercase()} has a manual $what override - clear it (Edit shares / cost) for this trade to count")
    }

    /**
     * See [Keys.REPLAY_REPAIR_BELOW_ID]. Written once, the first time this build replays the
     * ledger: every row on file then may predate chronological imports; every later insert
     * gets a higher id and is left in the order it was stored.
     */
    private fun replayRepairBelowId(): Long {
        db.get(Keys.REPLAY_REPAIR_BELOW_ID).toLongOrNull()?.let { return it }
        val mark = db.maxTxnId() + 1
        db.set(Keys.REPLAY_REPAIR_BELOW_ID, mark.toString())
        return mark
    }

    /** See [Keys.REPLAY_REPAIR_RANGES]. */
    private fun replayRepairRanges(): List<LongRange> = parseRepairRanges(db.get(Keys.REPLAY_REPAIR_RANGES))

    fun deleteTxn(id: Long) { db.deleteTxn(id); resetMarkIfEmptied(); recompute() }

    /**
     * A ledger emptied BY HAND is not a ledger that vanished (full test 2026-09-23, A-11).
     * The "transactions are missing" alarm fires when the table is empty but the high-water
     * mark says rows existed - so deleting the last row, or the only symbol, raised it, and its
     * restore button merged the rows just deleted straight back. The same reset
     * [wipeTransactions] already does, for the other two ways of emptying the table.
     */
    private fun resetMarkIfEmptied() {
        if (db.txnCount() == 0) {
            db.set(Keys.LAST_TXN_COUNT, "0")
            // Ids can be reused once the table is empty; a stale range must not claim them (A-2).
            db.set(Keys.REPLAY_REPAIR_RANGES, "")
            _dataMissing.value = false
        }
    }

    /** Wipe a symbol entirely: every transaction plus any manual override. */
    fun deleteSymbol(symbol: String): Int {
        val sym = symbol.uppercase()
        snapshotBefore("delete-$sym")
        val n = db.deleteTxnsForSymbol(sym)
        db.clearOverride(sym)
        db.removeWatch(sym)
        resetMarkIfEmptied()
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
        val quantitylessCash: Double = 0.0,
        /**
         * Symbols where more shares have been sold than were ever bought, and by how many.
         *
         * WHY IT IS REPORTED HERE AND NOT ONLY ON THE STOCK'S OWN PAGE. The detail screen
         * shows this too, but it can only show it for a symbol that still HAS a position -
         * rows are built from `positions.filter { it.shares > 1e-9 }`, and an uncovered sale
         * drives the holding to zero by definition. So the plainest case of all, "sold 10,
         * only ever bought 4, position now closed", had nowhere to appear. This card sees
         * every symbol the ledger knows about, open or closed.
         *
         * See [com.tj.portfolio.domain.Position.oversold] for why the ledger reports the
         * discrepancy rather than resolving it.
         */
        val oversold: List<Pair<String, Double>> = emptyList()
    ) {
        val hasProblem: Boolean get() = buysWithFees.isNotEmpty()
        val hasGhostRows: Boolean get() = quantityless.isNotEmpty()
        val hasOversold: Boolean get() = oversold.isNotEmpty()
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
        // EVERY position, not the open ones - a symbol that was oversold into a closed
        // position is exactly the case with nowhere else to surface. See FeeAudit.oversold.
        val short = Ledger.positions(txns, db.overrides(), costMethod(), repairBelowId = replayRepairBelowId(), repairRanges = replayRepairRanges())
            .filter { it.oversold > 1e-9 }
            .map { it.symbol to it.oversold }
        return FeeAudit(
            totalRecorded = txns.sumOf { it.fees },
            buysWithFees = bad,
            buyFeeTotal = bad.sumOf { it.fees },
            quantityless = ghosts,
            quantitylessCash = ghosts.sumOf { it.amount },
            oversold = short
        )
    }

    /**
     * Clear those buy-side commissions and re-derive each row's cash effect from its own
     * price and quantity, so removing the fee also corrects the amount it was baked into.
     */
    fun clearIncorrectBuyFees(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            // See commitImportAsync's note: an uncaught exception on this thread crashes the
            // app (Part 9 audit finding) - runCatching, same as every other DB write here.
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val bad = auditFees().buysWithFees
                    bad.forEach { t ->
                        val fixed = t.copy(
                            fees = 0.0,
                            amount = Txn.cashEffect(t.type, t.quantity, t.price, t.amount, 0.0)
                        )
                        db.updateTxn(fixed)
                    }
                    bad.size
                }.getOrElse {
                    toast("Couldn't clear those fees: ${it.message}")
                    0
                }
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

    /**
     * Resolves the %-since-added baseline for every watched symbol that does not have one
     * yet. Call when the Watchlist tab is opened - cheap to call again later, since a symbol
     * already resolved (or already in flight) costs nothing.
     *
     * WHY THIS RESOLVES AT MOST ONCE PER SYMBOL, EVER. [Db.setWatchBaseline] never overwrites
     * a value once written, so the anchor a running percentage is measured from cannot drift
     * between screen opens - the same "resolved once, cached forever" rule the chart and
     * insider caches already follow.
     */
    fun resolveWatchBaselines() {
        val pending = cachedWatch.filter { it.addedPrice <= 0.0 }
        if (pending.isEmpty()) return
        fgScope.launch {
            // BOUNDED CONCURRENCY, not one at a time - the same gate every other per-symbol
            // fetch loop in this file already uses. A restore or a multi-add can leave several
            // symbols pending at once, and there is no reason a slow chart fetch for the first
            // one should hold up starting the rest.
            val gate = Semaphore(MAX_PARALLEL_REQUESTS)
            pending.map { w ->
                async {
                    gate.withPermit {
                        if (!watchBaselineInFlight.add(w.symbol)) return@withPermit
                        try {
                            val close = withContext(Dispatchers.IO) {
                                runCatching { closeOnOrAfter(w.symbol, w.addedAt) }.getOrNull()
                            }
                            if (close != null && close > 0) {
                                withContext(Dispatchers.IO) { db.setWatchBaseline(w.symbol, close) }
                                recompute()
                            }
                            // null means either the network failed or the add-day's session has
                            // not closed yet (see [closeOnOrAfter]) - either way, try again the
                            // next time this is called rather than guessing.
                        } finally {
                            watchBaselineInFlight.remove(w.symbol)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * The first REGULAR-session close on or after [dateMs] - the day-zero reference
     * [Row.sinceWatchedPct] is measured from, and deliberately not "the newest close": TODAY'S
     * daily candle updates live before the market closes, and accepting it would let a
     * mid-session price masquerade as the day's final close, defeating the entire point of
     * anchoring to a CLOSE rather than a tap-time price. Excluded by date, not by a market-
     * hours check, so it behaves the same on a weekday afternoon and a Saturday alike.
     *
     * [ChartRange.rangeForLookback] already exists to answer "which cached series reaches far
     * enough back" for the chart screen's own pinch-zoom - reused here for the same question.
     * A symbol added over a year ago resolves from the weekly/monthly series instead of daily,
     * which is a real but minor loss of precision for an old watchlist entry, not a new
     * lookup path.
     *
     * TWO REAL BUGS FIXED HERE AFTER SHIPPING (caught by a requested post-release review),
     * neither one visible in the original tests because nothing in them was added within the
     * last week and exercised against real network data:
     *
     * 1. THE LOOKBACK HAS TO BE MEASURED FROM THE START OF THE ADD-DAY, NOT FROM [dateMs]
     * ITSELF. A symbol added at 11pm has a lookback of minutes if measured from [dateMs] and
     * "now" is a few minutes into the next calendar day - comfortably inside
     * [ChartRange.D1]'s span - but [ChartRange.D1] only ever contains TODAY's candles (every
     * range this provider serves "ends at now", per that class's own header), so a request for
     * it returns nothing from YESTERDAY at all and this returned null forever, not just until
     * enough time passed. Measuring from `dayStart` instead guarantees at least one full extra
     * day of lookback whenever the add-day is not today, which is enough to promote the choice
     * off [ChartRange.D1] and onto a range whose series can actually contain that day.
     *
     * 2. WHICH POINT COUNTS AS "THE CLOSE" - see [lastCloseInWindow]'s own note, which is
     * where that half of the fix now actually lives.
     */
    private suspend fun closeOnOrAfter(symbol: String, dateMs: Long): Double? {
        val dayStart = startOfDay(dateMs)
        val todayStart = startOfDay(System.currentTimeMillis())
        // NO REQUEST FOR AN ANSWER THAT CANNOT EXIST YET, AND A BACKOFF FOR ONE THAT FAILS
        // (full test 2026-09-24, N-7). A symbol added today (or on a weekend, until the next
        // session has closed) has an empty window, yet every Watch-tab visit downloaded a chart
        // to find that out; and this path bypasses `_charts`/`chartRetry`, so a symbol Yahoo
        // cannot chart was re-requested on both hosts on every visit, forever.
        if (!baselineWindowHasSession(dayStart, todayStart)) return null
        val key = "$symbol|baseline"
        if (chartRetry.blocked(key)) return null
        val lookback = (System.currentTimeMillis() - dayStart).coerceAtLeast(0L)
        val range = com.tj.portfolio.data.ChartRange.rangeForLookback(lookback)
        val series = com.tj.portfolio.net.ChartFeed.series(symbol, range)
        if (series == null) { chartRetry.failure(key); return null }
        chartRetry.success(key)
        return lastCloseInWindow(series.points, dayStart, todayStart)
    }

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
            setImportResult(ExtractResult(emptyList(), "", "Add your Claude API key in Settings first."))
            return
        }
        // NEVER OVER A REVIEW ALREADY WAITING (full test 2026-09-24, A-7) - the same rule a
        // share follows (A-8, 09-23). A 30-60 s extraction that lands after a shared answer's rows
        // arrived replaced them, and the share's inbox file was already gone.
        fun land(res: ExtractResult) {
            val waiting = _importResult.value?.takeIf { it.transactions.isNotEmpty() }
            // THE PAID CALL'S OUTCOME IS ALWAYS SAID (review 2026-09-24, R2-5): folded into a
            // waiting review, a result with no rows (and no error) used to vanish unannounced.
            if (waiting != null) toast(
                if (res.transactions.isEmpty())
                    res.error ?: res.notes.ifBlank { "No transactions found in those screenshots" }
                else "${res.transactions.size} transactions added to the review already waiting" +
                    (res.error?.let { " ($it)" } ?: "")
            )
            setImportResult(mergeIntoPendingReview(_importResult.value, res))
        }
        viewModelScope.launch {
            _importing.value = true
            if (_importResult.value?.transactions.isNullOrEmpty()) setImportResult(null)
            val images = withContext(Dispatchers.IO) { uris.mapNotNull { readImage(it) } }
            if (images.isEmpty()) {
                _importing.value = false
                land(ExtractResult(emptyList(), "", "Couldn't read the selected images."))
                return@launch
            }
            // Never extract from a partial set in silence: a screenshot that was dropped is a
            // trade that will not appear, and nothing else on screen would ever say so.
            val dropped = uris.size - images.size
            if (dropped > 0) {
                _importing.value = false
                land(ExtractResult(
                    emptyList(), "",
                    "$dropped of ${uris.size} image(s) couldn't be read - most likely too " +
                        "large even after resizing. Import the rest on their own, or retake " +
                        "those screenshots, so nothing is missed."
                ))
                return@launch
            }
            val chosen = ensureModel()
            val res = withContext(Dispatchers.IO) {
                runCatching { Claude.extractTransactions(key, chosen, images, existingDigest()) }
                    .getOrElse { ExtractResult(emptyList(), "", it.message ?: "Extraction failed") }
            }
            land(res)
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
    private fun duplicateOf(t: Txn, claimed: Set<Long> = emptySet()): Long? {
        db.findDuplicateId(t, claimed)?.let { return it }
        val dateWasGuessed = t.note?.contains("date estimated", true) == true
        return if (dateWasGuessed) db.findDuplicateIdAnyDate(t, claimed) else null
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
    suspend fun duplicateFlags(list: List<Txn>): List<com.tj.portfolio.domain.ImportDup> =
        withContext(Dispatchers.IO) {
            com.tj.portfolio.domain.ImportDupes.classify(list) { t, claimed -> duplicateOf(t, claimed) }
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
        // NULL WHEN THE WRITE FAILED (full test 2026-09-23, A-10) - not 0, which is the
        // legitimate "every row was already on file". The review dialog needs the difference:
        // on a failure it must come back to life so the kept extraction can be retried.
        onDone: (Int?) -> Unit
    ) {
        viewModelScope.launch {
            // RUNCATCHING, LIKE EVERY OTHER DB WRITE IN THIS FILE (Part 9 audit finding).
            // This one committed data parsed by an LLM from a screenshot - the least-trusted
            // input in the app - inside a plain `launch {}` with no handler, so any exception
            // (disk full, a malformed row) crashed the whole app mid-import, `onDone` never
            // called, the review dialog stuck open. The transaction itself already rolls back
            // correctly on an exception (`endTransaction()` without a prior
            // `setTransactionSuccessful()` is a rollback) - what was missing is catching the
            // exception so the coroutine, and the app, survive to report it.
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    var n = 0
                    var minD = Long.MAX_VALUE
                    var maxD = 0L
                    // Without [force], exactly what the review dialog would have left unticked
                    // is skipped - the same [ImportDupes.classify], so the two cannot disagree.
                    // WITH it (the dialog's own commit), the user ticked every row being passed,
                    // identical partial fills included, and every one of them is written - see
                    // [ImportDupes] for the second fill this used to drop silently.
                    val flags = if (force) null
                    else com.tj.portfolio.domain.ImportDupes.classify(list) { t, c -> duplicateOf(t, c) }
                    val keep = if (flags == null) list
                    else list.filterIndexed { i, _ -> flags[i] == com.tj.portfolio.domain.ImportDup.NEW }
                    val skipped = list.size - keep.size
                    val database = db.writableDatabase
                    database.beginTransaction()
                    try {
                        // OLDEST FIRST - see [com.tj.portfolio.domain.ImportOrder].
                        for (t in com.tj.portfolio.domain.ImportOrder.chronological(keep)) {
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
                }.getOrElse {
                    toast("Import failed: ${it.message}")
                    // NULL, NOT 0. A commit that THREW and a commit where every row turned out
                    // to be a duplicate both used to come back as 0, and the caller could not
                    // tell them apart - see the `_importResult` note below, which depends on
                    // exactly that distinction.
                    null
                }
            }
            val n = res ?: 0
            if (n > 0) autoBackupIfDue(force = true)
            _lastImport.value = withContext(Dispatchers.IO) { db.lastImport() }
            // ---- KEEP THE EXTRACTION IF THE WRITE FAILED.
            //
            // This cleared unconditionally, including on the failure path above - so a disk
            // error threw away rows that only a deliberate Claude API call can produce, left
            // a "Import failed" toast, and then immediately toasted "Imported 0
            // transaction(s)" on top of it. Nothing remained to retry from.
            //
            // A successful commit clears as before, INCLUDING the legitimate zero where every
            // row was already on file - that is a finished job, not a failure.
            if (res != null) setImportResult(null)
            recompute()
            refresh()
            onDone(res)
        }
    }

    fun clearImport() = setImportResult(null)

    /**
     * The one place [_importResult] is written (full-tests audit, 2026-09-21). Also mirrors
     * a real extraction - one with rows to review - into [Keys.PENDING_IMPORT], so Android
     * killing the process while the review dialog is open doesn't silently throw away a
     * billed Claude API call along with it. [loadPendingImport] restores it on the next
     * launch; every other outcome (no rows, an error message) clears the persisted copy,
     * since there is nothing there worth a paid call to reproduce.
     */
    private fun setImportResult(r: ExtractResult?) {
        _importResult.value = r
        db.set(Keys.PENDING_IMPORT, if (r != null && r.transactions.isNotEmpty())
            pendingImportToJson(r).toString() else "")
    }

    private fun loadPendingImport() {
        val cached = runCatching { db.get(Keys.PENDING_IMPORT) }.getOrDefault("")
        if (cached.isBlank()) return
        runCatching { pendingImportFromJson(JSONObject(cached)) }
            .getOrNull()
            ?.takeIf { it.transactions.isNotEmpty() }
            ?.let { _importResult.value = it }
    }

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
                // ONLY ONTO AN EMPTY SCREEN (full test 2026-09-23, U-3): this read is async, and
                // an answer shared in on a cold start can land first - the cache it would
                // replace it with is the OLDER advice, already superseded in the database.
                withContext(Dispatchers.Main) { if (_advice.value == null) _advice.value = a }
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
        _advicePreparing.update { it + 1 }
        fgScope.launch {
            try {
                // ONLY THE SYMBOLS WHOSE HEADLINES ARE ACTUALLY STALE (Round 56). This ran the
                // full cascade over every holding on EVERY tap of the advice button, with no
                // freshness check of any kind - 16-48 requests, repeated, for headlines the
                // feed pass had usually fetched minutes earlier. `loadNews` has had a TTL for
                // versions; this path never got one.
                val cutoff = System.currentTimeMillis() - ADVICE_NEWS_TTL_MS
                // Fresh from ANY pass counts (N-L7): this one's own, the feed pass (which stamps
                // `adviceNewsAt` too), or a detail screen's deep pull. The note above said the
                // feed pass had "usually fetched minutes earlier" - and then ignored it.
                val syms = _ui.value.rows.filter { !it.watchOnly }.map { it.symbol }
                    .filter { maxOf(adviceNewsAt[it] ?: 0L, deepNewsAt[it] ?: 0L) < cutoff }
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
                try { onDone() } finally { _advicePreparing.update { (it - 1).coerceAtLeast(0) } }
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
    fun writeAdvicePrompt(onDone: (PromptOut) -> Unit) {
        _advicePreparing.update { it + 1 }   // U-8 - counted until the share sheet is handed over
        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    runCatching {
                        // ONE file, replaced each time. A new timestamped file per tap meant TJ's
                        // Downloads filled with near-identical prompts - and picking the wrong one
                        // in the file chooser is precisely the v5.2 import bug. One file cannot be
                        // confused with an older one.
                        deliverPrompt(ADVICE_PROMPT_FILE, advicePromptFile())
                    }.getOrElse { PromptOut("Couldn't build the prompt file: ${it.message}", null) }
                }
                onDone(out)
            } finally {
                _advicePreparing.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    /**
     * EVERY PROMPT BUTTON ENDS HERE (2026-09-23b). Writes the Downloads copy exactly as before
     * - the fallback, and the file the "How does this work?" notes still name - AND stages the
     * same text for the share sheet, which the screen opens as soon as this returns. Call off
     * the main thread: both are disk writes.
     */
    private fun deliverPrompt(fileName: String, body: String): PromptOut {
        val app = getApplication<Application>()
        val saved = runCatching {
            com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(app, fileName, body, "text/markdown")
        }.getOrNull()
        val share = com.tj.portfolio.util.PromptShare.stage(app, fileName, body)
        return PromptOut(
            if (saved != null) "Saved to ${saved.display} - attach it to a Claude chat"
            else "Couldn't write the prompt file",
            share
        )
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
        //
        // ---- AND ITS MIRROR IMAGE, FOUND IN ROUND 63'S SWEEP.
        //
        // Guarding on "we hold filings for this symbol" is right for a symbol that HAS
        // filings and wrong for the ordinary one that does not. Most companies file no Form 4
        // in a given month, so `_insider[symbol]` stayed empty for them forever and the guard
        // never fired: every detail-screen open and every resume sent a fresh EDGAR listing
        // request for a company whose answer had already been fetched and was "nothing". The
        // conditional-GET cache could not absorb it either, because the `datea` parameter
        // rolls over daily and makes each day's URL a new one.
        //
        // `insiderAt` records that the pass COMPLETED, which is the thing the guard actually
        // wanted to know - the same shape `deepNewsAt` already uses for headlines. A failed
        // or cancelled pass never stamps it, so an unreachable EDGAR still retries at once.
        // ---- THE TTL BELOW WAS UNREACHABLE (Round 66 audit, DET-4).
        //
        // THE BUG THIS FIXES. There was a line above the clock reading
        // `if (_insider.value[symbol]?.isNotEmpty() == true) return` - "we already have some
        // filings, stop" - and it short-circuited the half-hour check on the very next line
        // for precisely the symbols that check was written for. Every symbol WITH filings
        // returned before its age was ever considered, so a stock TJ neither holds nor
        // watches - a search, opened from the Research tab - froze at whatever the first
        // fetch returned for the life of the process. Re-opening it, backgrounding and
        // resuming, switching tabs: all of them stopped on that line. The half-hourly
        // `refreshInsiders` pass could not cover it either, because it is handed the rows,
        // and a searched symbol is in neither list. A CEO buying stock this afternoon would
        // not appear until the app was killed and relaunched.
        //
        // The clock is the right guard and it always was: `insiderAt` stamps only a pass that
        // RESOLVED, so an unreachable EDGAR still retries at once, and the request count for
        // followed symbols is unchanged because `refreshInsiders` stamps every symbol the feed
        // pass covered (full-tests audit 2026-09-22, N-L6 - `publishInsiders` alone stamped
        // only the ones that had filings, which this note used to claim covered them all).
        if (!force) {
            val since = System.currentTimeMillis() - (insiderAt[symbol] ?: 0L)
            if (since < INSIDER_SYMBOL_TTL_MS) return
        }
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
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        Insider.forSymbolResult(symbol, known, secGate, since, insiderSkip)
                    }.getOrNull()
                }
                val items = result?.filings.orEmpty()
                // ---- STAMPED ONLY WHEN EDGAR ACTUALLY ANSWERED.
                //
                // "EDGAR answered and this company filed nothing this month" is a real answer
                // with a real cost, and it is the common case - which is why the old guard
                // ("do we hold filings for this symbol") never fired for most symbols.
                //
                // But `listFilings` returned an empty list for a 403, a 429, a timeout and an
                // offline device too, so stamping on emptiness alone cached a FAILURE for
                // half an hour: a stock opened while the SEC was refusing showed no filings
                // across every re-open until the TTL ran out. `answered` is the distinction,
                // and it comes from the HTTP response rather than from the shape of the list.
                // ANSWERED, AND ACTUALLY RESOLVED. A listing that came back but whose every
                // per-filing fetch then failed is a partial failure, not "this company filed
                // nothing" - caching it would hide the section for half an hour on the
                // strength of a request that did not work.
                val resolved = result != null && result.answered &&
                    (result.filings.isNotEmpty() || result.listed == 0)
                if (resolved) insiderAt[symbol] = System.currentTimeMillis()
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
        // STAMPED AT THE START OF A REAL ATTEMPT, not on success (Round 66). The mark means
        // "this device tried EDGAR at this time", which is what the cadence in `startAuto`
        // needs to know. Stamping only on success would turn an EDGAR outage into a request
        // on every single tick for as long as it lasted - the exact burst shape that gets a
        // free provider to start refusing, and SEC.gov is the one host in this app with a
        // published fair-use policy.
        stampFilingsAt()
        try {
            val known = snapshotInsiderDocs()
            val answered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            val filings = withContext(Dispatchers.IO) {
                runCatching {
                    Insider.forSymbols(symbols, known, secGate, insiderSkip, answered = answered)
                }.getOrDefault(emptyList())
            }
            // SAVED FIRST, WHATEVER ELSE HAPPENED. The pass may have discovered that several
            // documents will never parse - and the case where it discovers ONLY that is
            // exactly the case `filings` comes back empty, which used to return before the
            // save and throw the discovery away. That is the precise scenario the skip set
            // exists for, surviving in its worst form.
            saveInsiderSkip()
            // EVERY SYMBOL THIS PASS COVERED IS FRESH, filings or not (full-tests audit
            // 2026-09-22, N-L6). `publishInsiders` stamps only symbols that HAD filings, so a
            // followed stock with a quiet month looked unchecked, and opening it spent an EDGAR
            // listing request this pass had just made. SEC.gov is the one host here with a
            // published fair-use policy.
            //
            // ONLY THE ONES EDGAR ANSWERED (full test 2026-09-23, N-7): a 403/429 part-way
            // through arms the host cooldown, and every later listing in the pass was never
            // made - stamping those as checked hid them for half an hour.
            //
            // AND BEFORE THE EMPTY-RESULT RETURN BELOW (full test 2026-09-24, N-11): a quiet
            // month for every symbol is an empty result too, and returning first stamped
            // nothing - each detail open then re-listed its symbol. An outage leaves
            // `answered` empty, so it still stamps nothing.
            val now = System.currentTimeMillis()
            answered.forEach { insiderAt[it.uppercase()] = now }
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

    /** Fill the "All companies" view on demand, the same on-open rule [refreshInsidersIfEmpty]
     *  follows for the portfolio-scoped one. */
    fun refreshMarketInsidersIfEmpty() {
        if (_marketInsiderLoading.value || _marketInsiders.value.isNotEmpty()) return
        loadMoreMarketInsiders()
    }

    /**
     * One more page of the market-wide feed, newest-first from wherever the last page left
     * off. The "Load more" button on the All-companies list calls this directly; opening the
     * tab calls it once through [refreshMarketInsidersIfEmpty] above.
     */
    fun loadMoreMarketInsiders() {
        if (_marketInsiderLoading.value) return
        val startAt = marketInsiderPageStart
        marketInsiderJob = fgScope.launch {
            _marketInsiderLoading.value = true
            try {
                // NOT `snapshotInsiderDocs()` - that is the bounded portfolio-scoped cache,
                // and an unbounded "All companies" browsing session must never be the thing
                // that fills or evicts it (a real bug an earlier draft had: see
                // [Insider.marketWide]'s own note). What is already showing on screen is its
                // own complete "already have this" answer for the market-wide side.
                val known = _marketInsiders.value.associateBy { it.accession }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        Insider.marketWide(known, secGate, insiderSkip, pageStart = startAt)
                    }.getOrDefault(Insider.MarketResult(emptyList(), 0))
                }
                // The never-parseable set is shared with the portfolio-scoped path (Insider.kt's
                // own note on [marketWide]) - a document that showed up in both sweeps is only
                // ever downloaded and judged unparseable once.
                saveInsiderSkip()
                if (result.filings.isNotEmpty()) {
                    _marketInsiders.value = (_marketInsiders.value + result.filings)
                        .distinctBy { it.accession }
                        .sortedByDescending { it.filedAt }
                }
                // Advance ONLY once this window is fully drained - a busy window can hold more
                // distinct accessions than one pass fetches, and skipping ahead while refs
                // remain in it would lose them for the rest of the session. Advances on a
                // window with nothing left in it too (all known, all skip-worthy, or genuinely
                // empty) - "Load more" must still move forward rather than spin forever on a
                // page that will never produce anything new.
                if (result.remaining <= 0) marketInsiderPageStart = startAt + MARKET_INSIDER_PAGE_ADVANCE
            } finally {
                _marketInsiderLoading.value = false
            }
        }
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
        val bySymbol = merged.groupBy { it.symbol }
        _insider.value = _insider.value + bySymbol
        // STAMPED, so the per-symbol loader knows this data is fresh (Round 66 audit, DET-4).
        //
        // `loadInsider` used to short-circuit on "the map already has rows for this symbol",
        // which made its half-hour clock unreachable. Removing that line put the clock back in
        // charge - and the clock has to know that THIS pass just covered these symbols, or
        // opening a followed stock would spend a listing request the feed pass had already
        // paid for. Same number of requests as before, now for the right reason.
        val now = System.currentTimeMillis()
        bySymbol.keys.forEach { insiderAt[it] = now }
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
        // Same rule as the chart: a lookup that failed must not be retried on every screen
        // open and every ON_START with no throttle at all.
        if (!force && holdingsRetry.blocked(sym)) return
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
                            return@launch
                        }
                    }
                }

                val fresh = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.HoldingsFeed.holdings(sym) }.getOrNull()
                }
                if (fresh != null) holdingsRetry.success(sym) else holdingsRetry.failure(sym)
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

    // ============================================================ RECOMMENDATION

    /**
     * BUY/HOLD/SELL for one symbol, computed once per TRADING DAY from data the app already
     * holds - see `net/Recommend.kt` and `data/RecommendationModels.kt`'s header for why a
     * day-key gates this rather than a rolling TTL like every other cache in this app.
     *
     * NO NETWORK CALL OF ITS OWN. It reads [_fundamentals], which [loadFundamentals] already
     * fetches unconditionally for every symbol a detail screen opens - so this costs nothing
     * on the wire beyond what the Overview/Stats tabs were already asking for. If fundamentals
     * have not arrived yet this simply computes nothing THIS call; the caller (the tab's own
     * `LaunchedEffect(symbol, fundamentals)`) re-invokes once they do, and that re-invocation
     * is cheap because the guards below are the first thing that runs.
     *
     * DELIBERATELY HAS NO `force` PARAMETER. TJ asked explicitly that this "only needs to
     * refresh for each new day session" - not on pull-to-refresh, not on resume - so the day
     * check is the ONLY thing that can trigger a recompute, by construction rather than by a
     * flag some caller could pass wrong. `refreshEverything` in DetailScreen does not call this
     * at all, on purpose.
     */
    fun loadRecommendation(symbol: String, price: Double) {
        val sym = symbol.uppercase()
        if (_recLoading.value.contains(sym)) return
        val today = MarketClock.dayKey()
        val have = _recommendations.value[sym]
        if (have != null && have.dayKey == today && settledForToday(sym, have, today)) return

        fgScope.launch {
            _recLoading.value = _recLoading.value + sym
            try {
                if (have == null) {
                    val disk = withContext(Dispatchers.IO) {
                        runCatching { db.cachedRecommendation(sym) }.getOrNull()
                    }
                    if (disk != null) {
                        _recommendations.value = _recommendations.value + (sym to disk)
                        // Already today's answer - nothing to recompute.
                        if (disk.dayKey == today && settledForToday(sym, disk, today)) return@launch
                    }
                }

                // THE DATED RATINGS COME FIRST, AND THE ORDER IS THE POINT (2026-09-18). The
                // verdict this computes is frozen for the trading day, so a verdict scored
                // before the publication dates land would carry the undated-consensus fallback
                // until tomorrow - the exact stale-rating verdict Tj asked to stop seeing. See
                // [ensureRatingsForRecommendation] for why this is not a new per-day cost.
                // Only mark the symbol as tried when the fetch actually CONCLUDED. If another
                // pass (the Analysts tab on the same stock) held the guard, the dates are still
                // on their way and `_fundamentals` updating will bring this function straight
                // back - marking it here would close that door for the rest of the day.
                if (ensureRatingsForRecommendation(sym)) recRatingsTried[sym] = today

                val f = _fundamentals.value[sym] ?: return@launch
                val fresh = com.tj.portfolio.net.Recommend.build(
                    sym, price, f, coreAt = coreDataAt[sym] ?: coreFetchedAt[sym] ?: f.fetched
                ) ?: return@launch
                _recommendations.value = _recommendations.value + (sym to fresh)
                // ---- NOT FROZEN UNTIL THE CORE NUMBERS ARE IN (full test 2026-09-23, S-2). The
                // ratings fetch alone yields a non-empty Fundamentals (it carries
                // `financialData`), so a verdict could be scored from analysts and growth only -
                // no valuation, no relative performance - and, its ratings being dated, count as
                // settled for the whole day. Until the core fetch has either landed or failed for
                // now, the verdict is shown but provisional: not persisted, not settled, and
                // recomputed when `_fundamentals` changes again.
                if (!coreFetchedAt.containsKey(sym) && !fundRetry.blocked("$sym|core")) {
                    recProvisional.add(sym)
                    return@launch
                }
                recProvisional.remove(sym)
                // On viewModelScope, not fgScope: a write already computed for today should
                // land even if the screen is backgrounded a moment later - same reasoning as
                // every other cache write in this file.
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { db.cacheRecommendation(fresh) }
                }
            } finally {
                _recLoading.value = _recLoading.value - sym
            }
        }
    }

    /**
     * IS TODAY'S CACHED VERDICT ACTUALLY FINISHED, or was it scored before the analyst
     * publication dates were available?
     *
     * The day-key gate on its own used to be the whole test, and after 2026-09-18 that is one
     * case short. A verdict can now be computed from either the DATED ratings (the real answer -
     * see [com.tj.portfolio.net.RatingRecency]) or, when they have not landed, the undated
     * consensus at a discount. Freezing the second for the rest of the day would mean a symbol
     * whose first look happened before its rating history arrived keeps the weaker scoring until
     * tomorrow.
     *
     * So exactly one upgrade pass is allowed: a today-row with no dates recomputes once, after a
     * ratings fetch has actually been attempted for this symbol today. [recRatingsTried] is what
     * makes it once - a symbol genuinely without rating history (a small ADR, a new listing)
     * would otherwise re-enter this every time a screen recomposed, forever.
     */
    private fun settledForToday(
        sym: String,
        r: com.tj.portfolio.data.Recommendation,
        today: String
    ): Boolean = sym !in recProvisional && (r.ratingsDated || recRatingsTried[sym] == today)

    /**
     * Fundamentals - and through them, the BUY/HOLD/SELL badge - for every row on the
     * Portfolio tab, not just whichever one the user has opened in detail.
     *
     * TJ: *"include them in the main portfolio tab next to each stock."* Until this,
     * [loadFundamentals] had exactly one caller, `DetailScreen`, asking for one symbol at a
     * time. Showing a verdict on the WHOLE list is what makes this the first place in the app
     * that can ask for several holdings' fundamentals within the same few seconds, so
     * dispatch is staggered - a few symbols, a short pause, the next few - rather than firing
     * all of them in one instant. Same "do not hammer a provider" rule TJ asked for when this
     * scoring feature was built in the first place, now applied to fan-out rather than to a
     * single symbol.
     *
     * [loadFundamentals] is itself fire-and-forget - it launches its own coroutine and
     * returns immediately - so what is staggered here is DISPATCH, not completion. Each call
     * still goes through that function's own disk-first, TTL-gated, per-symbol-guarded path;
     * on an ordinary day most of the ~16 are already fresh and this loop costs nothing but the
     * guard checks themselves.
     */
    fun loadPortfolioFundamentals(symbols: List<String>) {
        fgScope.launch {
            symbols.distinct().forEachIndexed { i, sym ->
                // Three symbols, then a short pause, for the rest - not a hard concurrency
                // cap (loadFundamentals does not return a handle to gate one), just enough
                // spacing that a cold portfolio of sixteen holdings does not read as a burst.
                if (i > 0 && i % 3 == 0) delay(400L)
                loadFundamentals(sym)
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
    private fun intradayChartIsFinal(held: ChartSeries): Boolean =
        intradayChartIsFinal(held.range, held.endMs, held.fetched, System.currentTimeMillis())

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
        // The same line again is not a change - no repaint, no database write (N-9).
        if (q.spark == closes) return
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

    /**
     * Is the benchmark overlay switched on? Remembered across launches, like the range.
     *
     * DEFAULTS OFF. It is a second chart request per range and per symbol, and a feature the
     * user has not asked for should not spend requests on their behalf.
     */
    fun chartCompare(): Boolean = db.get(Keys.CHART_COMPARE, "0") == "1"

    fun setChartCompare(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.set(Keys.CHART_COMPARE, if (on) "1" else "0") }
        }
    }

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
    /**
     * Returns the launched fetch as a [Job] so a caller that needs to BOUND how many run at
     * once - [enrichDayTradingVisible], across up to a screenful of symbols - can await it
     * inside its own semaphore instead of firing every symbol's disk-read-plus-HTTP-fetch at
     * the same instant. Null when nothing was launched at all (already fresh, already
     * in-flight, or backing off) - there is then nothing to wait for.
     */
    fun loadChart(symbol: String, range: ChartRange, force: Boolean = false): Job? {
        val sym = symbol.uppercase()
        val key = chartKey(sym, range)
        // The in-flight guard is INSIDE nothing - it is checked here and cleared in a
        // `finally` below. A guard set here and cleared only on the happy path is how
        // loadInsider once locked a symbol out for the rest of the session.
        if (_chartLoading.value.contains(key)) return null

        // THE CHEAP WAY OUT, TAKEN BEFORE A COROUTINE IS EVEN STARTED.
        //
        // The detail screen calls this on every quote tick so the chart refreshes itself the
        // moment its range goes stale - see the note there. That is four calls a minute, and
        // all but one in twenty of them have nothing to do. Answering those here costs a map
        // lookup; answering them inside a launched coroutine would cost a coroutine each.
        if (!force) {
            // A RECENT FAILURE IS AN ANSWER TOO. Without this the detail screen re-requested
            // a chart that cannot be had on every quote tick, because a failed fetch leaves
            // no entry in `_charts` for the freshness test below to find. See [RetryClock].
            if (chartRetry.blocked(key)) return null
            if (sym in chartDiskRead) {
                val cached = _charts.value[key]
                if (cached != null && !cached.isEmpty &&
                    (!cached.stale() || intradayChartIsFinal(cached))
                ) return null
            }
        }

        return fgScope.launch {
            _chartLoading.value = _chartLoading.value + key
            try {
                // ---- 1. disk first, every range for this symbol in one query
                if (!chartDiskRead.add(sym)) {
                    chartDiskInFlight[sym]?.await()   // C-11: another range is reading it now
                } else {
                    val diskGate = kotlinx.coroutines.CompletableDeferred<Unit>()
                    chartDiskInFlight[sym] = diskGate
                    try {
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
                            // ONE REQUEST, TWO CONSUMERS - on this path as well as the fetch one.
                            // A restored 1D series is the same thing `refreshSparklines` would go
                            // and fetch, so adopting it here saves that request outright, and it
                            // is what lets that pass safely skip a symbol whose chart is fresh.
                            // `adoptAsSparkline` checks its own preconditions and does nothing if
                            // there is no quote to attach it to yet.
                            // ONLY IF IT IS STILL FRESH. `adoptAsSparkline` checks that the
                            // series is regular-session and that a quote exists to attach it to,
                            // but not how OLD it is - which is fine on the fetch path, where it
                            // is fresh by construction, and wrong here, where it is whatever
                            // SQLite held. Adopting a stale one stamps `sparkAt` and so
                            // suppresses the real refresh for five minutes, drawing yesterday's
                            // intraday line against today's previous close: the very bug this
                            // adoption was added to prevent, arriving from the other side.
                            m[chartKey(sym, ChartRange.D1)]
                                ?.takeIf { !it.stale() }
                                ?.let { adoptAsSparkline(sym, it) }
                        }
                    } finally {
                        // Released only once the rows are IN MEMORY, so a waiter finds them (C-11).
                        chartDiskInFlight.remove(sym, diskGate)
                        diskGate.complete(Unit)
                    }
                }

                // ---- 2. is what we hold still good?
                val held = _charts.value[key]
                // Finality counts here too (N-6): a final 1D series restored from disk at night
                // is as good as a fresh one, and re-downloading it on every cold start was waste.
                if (!force && held != null && !held.isEmpty &&
                    (!held.stale() || intradayChartIsFinal(held))
                ) return@launch

                // ---- 3. fetch
                val fresh = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.ChartFeed.series(sym, range) }.getOrNull()
                }
                // Recorded either way, so a failure backs the next attempt off instead of
                // letting the tick clock ask again in fifteen seconds.
                if (fresh != null && !fresh.isEmpty) chartRetry.success(key)
                else chartRetry.failure(key)
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

    /**
     * Publish the 1D chart from a body fetched in the last few seconds by another consumer of
     * the same url (N-1), with no request of its own. True when there was one to publish. Written
     * to disk only when the held copy was already due for a refresh, so a 30-second sweep does
     * not turn into a database write per row every 30 seconds. Main thread.
     */
    private fun adoptRecentD1(symbol: String): Boolean {
        val sym = symbol.uppercase()
        val fresh = com.tj.portfolio.net.ChartFeed.recentSeries(sym, ChartRange.D1) ?: return false
        val key = chartKey(sym, ChartRange.D1)
        val held = _charts.value[key]
        if (held != null && held.fetched >= fresh.fetched) return true
        _charts.value = _charts.value + (key to fresh)
        chartRetry.success(key)
        adoptAsSparkline(sym, fresh)
        if (held == null || held.stale()) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { db.cacheChart(fresh) } }
        }
        return true
    }

    fun loadFundamentals(symbol: String, force: Boolean = false) {
        val sym = symbol.uppercase()
        val since = System.currentTimeMillis() - (coreFetchedAt[sym] ?: 0L)
        if (!force && since < com.tj.portfolio.net.FundamentalsFeed.CORE_TTL_MS &&
            _fundamentals.value[sym]?.values?.isNotEmpty() == true
        ) return
        // A REFUSAL IS AN ANSWER TOO (Round 63 sweep). The TTL above is stamped only on
        // SUCCESS, so a symbol whose `quoteSummary` cannot be had - an ETF, a delisted
        // ticker, Yahoo 403ing - fell straight through it and was re-requested on every
        // detail-screen open and every resume. Bounded by navigation rather than by a timer,
        // which is why it never showed up as a storm, but it is the same gap `loadChart` had.
        if (!force && fundRetry.blocked("$sym|core")) return
        if (_fundLoading.value.contains(sym)) return

        fgScope.launch {
            _fundLoading.value = _fundLoading.value + sym
            try {
                if (_fundamentals.value[sym] == null) {
                    // BOTH rows, not just the core one: the Analysts tab is one swipe away
                    // and should not be empty while its own fetch runs.
                    val (coreRow, ratingsRow) = withContext(Dispatchers.IO) {
                        runCatching { db.cachedFundamentals(sym, Keys.KIND_CORE) }.getOrNull() to
                            runCatching { db.cachedFundamentals(sym, Keys.KIND_RATINGS) }.getOrNull()
                    }
                    listOfNotNull(coreRow, ratingsRow).forEach { mergeFundamentals(sym, it) }
                    // A disk row that is still inside its life is a complete answer - going
                    // to the network for it would be a request that could only return the
                    // same numbers. Recording its age as the fetch mark makes the guard at
                    // the top of this function agree for the rest of the session.
                    //
                    // THE CORE ROW, BY KIND (full test 2026-09-23, N-10). This took the first row
                    // with any values - and the RATINGS row has values too (its `financialData`),
                    // so with no core row on disk it was accepted as the core answer, the core
                    // fetch was skipped for six hours and the Stats tab showed only a subset.
                    val core = coreRow?.takeIf { it.values.isNotEmpty() }
                    if (core != null) coreDataAt[sym] = core.fetched   // S-3: its real age
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
                if (fresh == null || fresh.isEmpty) fundRetry.failure("$sym|core")
                else fundRetry.success("$sym|core")
                if (fresh != null && !fresh.isEmpty) {
                    coreFetchedAt[sym] = System.currentTimeMillis()
                    coreDataAt[sym] = coreFetchedAt[sym]!!
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
        if (!ratingsWanted(sym, force)) return
        if (_ratingsLoading.value.contains(sym)) return
        // Fire and forget: this caller is the Analysts tab, which reads the result off
        // [_fundamentals] rather than off a return value.
        fgScope.launch { fetchRatings(sym, force) }
    }

    /**
     * Is a ratings fetch for [sym] actually worth making right now - the TTL and failure-backoff
     * half of [loadRatings], split out so [ensureRatingsForRecommendation] asks the SAME question
     * rather than a second, drifting copy of it.
     */
    private fun ratingsWanted(sym: String, force: Boolean): Boolean {
        val since = System.currentTimeMillis() - (ratingsFetchedAt[sym] ?: 0L)
        if (!force && since < com.tj.portfolio.net.FundamentalsFeed.RATINGS_TTL_MS &&
            _fundamentals.value[sym]?.ratings?.isNotEmpty() == true
        ) return false
        // Same guard, and it matters more here: the analyst payload is the heaviest thing
        // this app fetches, and a stock nobody covers returns nothing every single time.
        if (!force && fundRetry.blocked("$sym|ratings")) return false
        return true
    }

    /**
     * The body of [loadRatings], as a SUSPEND function that can be awaited.
     *
     * Split out on 2026-09-18 so the BUY/HOLD/SELL path can wait for the dated ratings instead
     * of racing them. [com.tj.portfolio.net.RatingRecency] needs each analyst's publication
     * date, and those live only in this payload - a verdict computed before it lands would be
     * scored from the undated consensus and then FROZEN FOR THE TRADING DAY by the day-key
     * gate, which is exactly the stale-rating verdict Tj asked to stop seeing.
     *
     * Returns true when [_fundamentals] now holds dated ratings for [sym] - from disk or the
     * wire - false when it genuinely has none, and NULL when this call did nothing because
     * another fetch for the same symbol already owns the in-flight guard. The caller has to be
     * able to tell those apart: "nobody covers this stock" is an answer, "the Analysts tab is
     * fetching it right now" is not, and treating the second as the first is how a verdict gets
     * frozen for the day without the dates that were about to arrive.
     */
    private suspend fun fetchRatings(sym: String, force: Boolean): Boolean? {
        if (_ratingsLoading.value.contains(sym)) return null
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
                        return true
                    }
                }
            }

            val fresh = withContext(Dispatchers.IO) {
                runCatching { com.tj.portfolio.net.FundamentalsFeed.ratings(sym) }.getOrNull()
            }
            val got = fresh != null && (fresh.ratings.isNotEmpty() || fresh.consensus != null)
            if (got) fundRetry.success("$sym|ratings") else fundRetry.failure("$sym|ratings")
            if (fresh != null && got) {
                ratingsFetchedAt[sym] = System.currentTimeMillis()
                mergeFundamentals(sym, fresh)
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { db.cacheFundamentals(sym, Keys.KIND_RATINGS, fresh) }
                }
            }
        } finally {
            _ratingsLoading.value = _ratingsLoading.value - sym
        }
        return _fundamentals.value[sym]?.ratings?.isNotEmpty() == true
    }

    /**
     * Make sure [sym]'s DATED analyst ratings are in hand before a BUY/HOLD/SELL verdict is
     * computed and frozen for the day.
     *
     * ---- WHY THIS IS NOT A NEW NETWORK COST WORTH WORRYING ABOUT
     *
     * The payload is the heaviest thing this app fetches (~195 KB of complete rating history
     * for a large cap), which is why it has always been deferred to the Analysts tab. Three
     * things bound it here: the verdict is computed at most ONCE PER TRADING DAY per symbol, so
     * this runs at most once a day; [FundamentalsFeed] fetches it through
     * `Http.get(conditionalKey = true)` against a crumb-independent cache key, so every request
     * after the first for a given symbol is a bodyless 304; and [ratingsWanted] still applies
     * the 12-hour TTL and the failure backoff, so a symbol nobody covers is not re-asked for
     * all day. The first fetch per symbol is the real cost, and it buys the one thing the
     * verdict cannot be honest without.
     *
     * Reads from disk first, exactly like every other cache in this file - a symbol whose
     * Analysts tab was opened yesterday costs nothing at all.
     *
     * Returns whether the question is SETTLED for now, which is what [settledForToday] keys its
     * one-upgrade-pass rule off. False means only "another fetch owns this symbol right now" -
     * the Analysts tab being open on the same stock is enough - and the caller must then leave
     * the retry door open rather than freeze a dateless verdict for the rest of the day. That
     * race is narrow and it is exactly the case this whole change exists to prevent.
     */
    private suspend fun ensureRatingsForRecommendation(sym: String): Boolean {
        if (_fundamentals.value[sym]?.ratings?.isNotEmpty() == true) return true
        // Disk before the wire. `fetchRatings` does this too, but doing it here first means a
        // symbol already cached does not have to take the in-flight guard at all.
        val disk = withContext(Dispatchers.IO) {
            runCatching { db.cachedFundamentals(sym, Keys.KIND_RATINGS) }.getOrNull()
        }
        if (disk != null && disk.ratings.isNotEmpty()) {
            mergeFundamentals(sym, disk)
            if (System.currentTimeMillis() - disk.fetched <
                com.tj.portfolio.net.FundamentalsFeed.RATINGS_TTL_MS
            ) {
                ratingsFetchedAt[sym] = disk.fetched
                return true
            }
        }
        // The TTL or the failure backoff saying "do not ask" IS a settled answer - this symbol
        // is not going to be asked about again for a while either way, so holding the door open
        // would just re-enter this on every recomposition for nothing.
        if (!ratingsWanted(sym, force = false)) return true
        // Null means the guard was already held. Anything else - ratings found, or a real fetch
        // that came back empty - is an answer.
        return fetchRatings(sym, force = false) != null
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

    /** The same, for the SEC filings cadence. See [lastFilingsAt]. */
    private fun stampFilingsAt() {
        val now = System.currentTimeMillis()
        lastFilingsAt = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.set(Keys.FILINGS_AT, now.toString()) }
        }
    }

    /**
     * Stamp only if this pass actually went to the network (sweep 3).
     *
     * Once the feed pass could be GATED - fetching nothing for a screen nobody is on - an
     * unconditional stamp made `_feedAt` a lie twice over. It made the header say "Updated
     * just now" over headlines last really fetched hours ago, and it defeated the whole
     * refresh-on-open rule that gating depends on: that rule fires when the held data is older
     * than one interval, and a no-op pass every interval kept it permanently younger than one.
     */
    private fun stampFeedAtIfFetched(fetched: Boolean) {
        if (fetched) stampFeedAt()
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
            // ROUND 66: and the quotes table, which was the one cache in the app that grew
            // without bound. See [Db.purgeQuotes] - every stock ever opened from search, the
            // Research lists or a headline leaves a row behind, with an intraday spark series
            // in it, and `cachedQuotes()` parses every one of them on the launch path.
            runCatching { db.purgeQuotes() }
            // Part 9 audit: the import-history log never had a purge either. See [Db.purgeImports].
            runCatching { db.purgeImports() }
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
        com.tj.portfolio.net.Http.detachDiskCache(httpDiskCache)   // only if still ours (L-6)
        super.onCleared()
    }

    private fun attachHttpDiskCache() {
        com.tj.portfolio.net.Http.attachDiskCache(httpDiskCache)
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
                // ---- WHAT THIS PASS IS ALLOWED TO FETCH.
                //
                // Two questions, not one. `feedDue` is "is a headline screen being looked
                // at" and governs the seven market-wide feeds; `newsSymbols` is "whose
                // per-symbol headlines are worth a request right now" - everything when the
                // Feed tab is up, and just the open stock otherwise.
                //
                // The grace window matters as much as the flags: a pass that STARTED while
                // the Feed was on screen must finish its work even if the user navigates away
                // mid-flight, or leaving the tab at the wrong moment leaves the list
                // half-updated. A manual pull always does the full pass - a gesture the user
                // made must never quietly do less than it used to.
                val inGrace = System.currentTimeMillis() - newsVisibleAt < NEWS_VISIBLE_GRACE_MS
                val feedDue = manual || newsVisible || inGrace
                val openSymbol = newsSymbol
                val newsSymbols = when {
                    feedDue -> symbols
                    openSymbol != null && openSymbol in symbols -> listOf(openSymbol)
                    else -> emptyList()
                }
                // AND ONLY WHEN A HEADLINE SCREEN IS UP (full-tests audit 2026-09-22, N-L8) - the
                // same `feedDue` the seven market-wide feeds below are gated on. Trending
                // chatter is drawn on the Feed tab and nowhere else, and its own 15-minute clock
                // alone kept both Reddit aggregators polled from Portfolio, Activity or Settings.
                val socialDue = feedDue && System.currentTimeMillis() - socialAt > SOCIAL_REFRESH_MS
                val socialJob = async(Dispatchers.IO) {
                    // 60, not 25: the research rebuild wants 60 and can now reuse this one (N-8);
                    // the Feed draws the top 25 as before.
                    if (!socialDue) emptyList()
                    else runCatching { Social.trending(60) }.getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) feedSocialRaw = System.currentTimeMillis() to it }
                }
                // Market-wide headlines, fetched alongside the per-symbol ones so they add
                // nothing to the wait. Before this the "All" tab was only the user's own
                // symbols, which made it "My stocks" plus the watchlist and nothing more.
                //
                // GATED, since Round 63's sweep. `newsDue` is computed above rather than
                // fifty lines below precisely so this can share it. Seven RSS feeds were
                // being pulled every three minutes whether or not a headline screen had ever
                // been opened - roughly 140 requests an hour spent while sitting on
                // Portfolio, Activity, Advice, Settings or a stock. Only `FeedScreen` ever
                // renders a market-wide item.
                //
                // Nothing is lost by waiting: `setNewsVisible(true)` triggers a pass of its
                // own, so opening the tab fills it at once rather than at the next tick.
                val marketJob = async(Dispatchers.IO) {
                    if (!feedDue) emptyList()
                    else runCatching { News.market() }.getOrDefault(emptyList())
                        .also { if (it.isNotEmpty()) feedMarketRaw = System.currentTimeMillis() to it }
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
                // ---- HOISTED, AND OFF THE MAIN THREAD (Round 63 sweep).
                //
                // `holdingNames(owned)` was being rebuilt once PER HEADLINE - its own KDoc
                // says it should be read once per feed refresh - and `matchHolding` was
                // deriving a fresh `Relevance.Subject` and re-squashing the headline once per
                // symbol inside that. About 6,000 regex splits and 6,000 string rebuilds per
                // pass, every three minutes, on the UI thread, all producing answers that
                // cannot change between headlines.
                //
                // `Relevance.Subject` exists precisely to hoist this and `Research.build`
                // already used it; the Feed never adopted it. Same answers, same order, the
                // work done once.
                val marketRaw = marketJob.await()
                val marketFirst = withContext(Dispatchers.Default) {
                    val names = holdingNames(owned)
                    val subjects = owned.map {
                        com.tj.portfolio.net.Relevance.Subject.of(it, names[it].orEmpty())
                    }
                    marketRaw.map { n ->
                    val hit = com.tj.portfolio.net.Relevance.matchHolding(
                        n.title, subjects, com.tj.portfolio.net.Relevance.squashed(n.title, "")
                    )
                    com.tj.portfolio.data.FeedItem(
                        kind = com.tj.portfolio.data.FeedItem.MARKET,
                        symbol = hit ?: "",
                        title = n.title, url = n.url, source = n.source,
                        published = n.published, owned = hit != null
                    )
                    }
                }
                // MERGED with what is already on screen, not assigned over it. Assigning
                // here would blank the restored cache the instant a refresh started - the
                // very "my news disappeared" symptom this round is fixing, reintroduced from
                // the other end.
                publishAndCache(marketFirst + existingFilings + _feed.value,
                    toCache = marketFirst)
                stampFeedAtIfFetched(feedDue)

                val freshNews = HashMap<String, List<NewsItem>>()
                val perSymbol = java.util.Collections.synchronizedList(
                    ArrayList<com.tj.portfolio.data.FeedItem>()
                )

                // ---- PER-SYMBOL HEADLINES, FOR WHOEVER IS ACTUALLY BEING LOOKED AT.
                //
                // One request per symbol in `newsSymbols`, every three minutes. On the Feed
                // tab that is everything followed - about 480 an hour on a 24-symbol
                // portfolio, which is what the tab draws and so what it is worth. With only a
                // stock open it is ONE, because that is the only symbol whose headlines
                // anything on screen will show. See [newsSymbol] for why those used to be the
                // same number.
                if (newsSymbols.isNotEmpty()) withContext(Dispatchers.IO) {
                    val gate = Semaphore(MAX_PARALLEL_REQUESTS)
                    newsSymbols.map { sym ->
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
                // These ARE fresh headlines for those holdings - the advice preload may use
                // them rather than fetch its own (full-tests audit 2026-09-22, N-L7).
                val newsNow = System.currentTimeMillis()
                freshNews.forEach { (sym, items) -> if (items.isNotEmpty()) adviceNewsAt[sym] = newsNow }
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
                // `feedDue`, NOT "did this pass fetch anything". `_feedAt` governs the
                // market-wide list and the refresh-on-open rule that depends on it, and
                // `newsSymbols` is non-empty whenever a stock detail screen is open - so
                // stamping on that meant browsing stocks kept `_feedAt` permanently fresh
                // while the seven market feeds were never pulled. Opening the Feed then saw
                // "refreshed a minute ago" over headlines hours old.
                stampFeedAtIfFetched(feedDue)

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
                // STAMPED ON THE ATTEMPT, NOT ON SUCCESS - the rule `stampFilingsAt()` already
                // follows 200 lines up, and for the same reason: "stamping only on success
                // would turn an outage into a request on every single tick". `socialAt`
                // advanced only when rows came back, and `Social.merge` returns an empty list
                // when BOTH sources are empty - and the primary one (api.tradestie.com) has
                // served an expired certificate since 3 Jan 2026. So one ApeWisdom outage
                // pinned `socialAt` at zero and re-fired both endpoints on every feed pass
                // (3 minutes) instead of every 15, which is the opposite of the clock's job.
                //
                // GATED ON `socialDue` (full-tests audit, 2026-09-21): this line ran
                // unconditionally on every pass regardless of whether `socialJob` actually
                // attempted a fetch, so the first pass stamped `socialAt`, every later pass
                // saw `socialDue == false` and skipped the fetch but still re-stamped -
                // `now - socialAt` could then never exceed one feed interval, `socialDue`
                // could never become true again, and Social.trending() fired exactly once
                // per process. Only advance the clock when this pass actually attempted it.
                if (socialDue) socialAt = System.currentTimeMillis()
                if (trend.isNotEmpty()) {
                    _trending.value = trend.distinctBy { it.symbol }.take(25)
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
            feedLoading = _feedLoading.value,
            researchLoading = _researchBusy.value.isNotEmpty(),
            source = _ui.value.refreshSource
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
            // BUSY FROM THE KEYSTROKE, NOT FROM THE END OF THE DEBOUNCE (full-tests audit
            // 2026-09-22, U-L1). For the 220ms before the request, the sheet saw "not busy, no
            // hits" and drew "No matches for ... - Add anyway" under every letter typed. Set
            // inside the launch - which runs to the `delay` at once on Main.immediate - so a
            // cancelled scope that never runs the body cannot leave a spinner stuck on.
            _searching.value = true
            val me = coroutineContext[Job]
            try {
                delay(220)
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
                // BUT ONLY FOR THE CURRENT SEARCH: one replaced by the next keystroke must not
                // switch off the new one's spinner on its way out (U-L1). `null` counts as
                // current (full test 2026-09-24, L-7): leaving the app nulls `searchJob` right
                // after cancelling, and a search blocked in its request only reaches this line
                // after that - so the spinner stayed on with no results and no "No matches".
                if (searchJob === me || searchJob == null) _searching.value = false
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
    // COERCED TO THE NUMBER OF SECTIONS THERE ACTUALLY ARE. It has been a literal twice and
    // been wrong twice - once when the ETF tab was added and again when the Worst tab was
    // removed in Round 66, which left a stored index pointing past the end of the list.
    // Derived from `SECTIONS` so neither adding nor removing a section can do it again.
    // A FUNCTION, NOT A PROPERTY, and `checkInitOrder` is the reason: this sits below the
    // init block, and the build guard - which exists because a property declared after init
    // does not exist yet while init runs - rightly refuses one there.
    private fun researchTabMax(): Int = com.tj.portfolio.data.ResearchSet.SECTIONS.lastIndex

    fun researchTab(): Int =
        db.get(Keys.RESEARCH_TAB, "0").toIntOrNull()?.coerceIn(0, researchTabMax()) ?: 0

    fun setResearchTab(i: Int) =
        db.set(Keys.RESEARCH_TAB, i.coerceIn(0, researchTabMax()).toString())

    fun lastTab(): Int = db.get(Keys.LAST_TAB, "0").toIntOrNull() ?: 0
    fun setLastTab(i: Int) = db.set(Keys.LAST_TAB, i.toString())

    /**
     * The Feed tab's own filter/scope/source chips, persisted for the same reason
     * [watchSubTab]/[researchTab] are: `FeedScreen` leaves composition on every tab switch (its
     * own comment on the refresh `LaunchedEffect` says so), so a plain `remember` for these was
     * silently discarded and reset to the defaults every time the tab was reopened. Validating
     * a stored value against the live enum/const list is FeedScreen's own job, same as
     * `researchTab`'s index-clamping is its caller's - this is a plain pass-through.
     */
    fun feedFilter(): String = db.get(Keys.FEED_FILTER, "")
    fun setFeedFilter(v: String) = db.set(Keys.FEED_FILTER, v)
    fun feedInsiderScope(): String = db.get(Keys.FEED_INSIDER_SCOPE, "")
    fun setFeedInsiderScope(v: String) = db.set(Keys.FEED_INSIDER_SCOPE, v)
    fun feedInsiderSource(): String = db.get(Keys.FEED_INSIDER_SOURCE, "")
    fun setFeedInsiderSource(v: String) = db.set(Keys.FEED_INSIDER_SOURCE, v)

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
                // A SPLIT's `quantity` is a RATIO, not a share count - see [TxnType.SPLIT].
                // Printed by the ordinary rule this read "SPLIT NVDA  qty 10  $0.00", which
                // Claude cannot mistake for a duplicate (screenshots never produce a SPLIT
                // row - TxnType.IMPORTABLE excludes it) but reads as a strange zero-dollar
                // trade rather than the split it actually is.
                if (t.type == TxnType.SPLIT) append("  ").append(splitLabel(t.quantity))
                else {
                    if (t.quantity > 0) append("  qty ").append(Fmt.shares(t.quantity))
                    append("  $").append(Fmt.priceBare(kotlin.math.abs(t.amount)))
                }
            }
        }
    }

    /** Builds and writes the prompt file off the UI thread - it reads every transaction. */
    fun writeScreenshotPrompt(onDone: (PromptOut) -> Unit) {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching { deliverPrompt(SCREENSHOT_PROMPT_FILE, screenshotPromptFile()) }
                    .getOrElse { PromptOut("Couldn't build the prompt file: ${it.message}", null) }
            }
            onDone(out)
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
        // A backup picked by mistake is not an answer (A-1) - same check the share path makes.
        if (com.tj.portfolio.net.SharedAnswer.isBackup(text)) return com.tj.portfolio.net.SharedAnswer.BACKUP_MESSAGE
        // Round 54: one file chooser, three kinds of answer. A research reply is recognised
        // by its own payload key and handled first, so importing it from the Advice tab by
        // mistake fills the Research tab instead of reporting "nothing usable".
        if (com.tj.portfolio.net.ResearchBridge.looksLikeResearch(text) ||
            com.tj.portfolio.net.DayTradingBridge.looksLikeDayTrading(text)
        ) {
            return importResearchFile(text)
        }
        return importAdviceOrTransactions(text).message
    }

    /**
     * The advice/transactions half of [importClaudeFile], shared with [importShared] so the
     * share path cannot drift from the button path. [ShareImport.dest] says which screen now
     * has something to show: the Activity tab when there are transactions to review (they sit
     * in a review sheet until confirmed, so that is the screen that needs Tj), else Advice.
     */
    private fun importAdviceOrTransactions(text: String): ShareImport {
        val r = runCatching { com.tj.portfolio.net.ClaudeBridge.parse(text) }
            .getOrElse { return ShareImport("Couldn't read that file: ${it.message}", null) }
        if (r.error != null) return ShareImport(r.error, null)
        var msg = ""
        var dest: ShareDest? = null
        if (r.advice != null) {
            _advice.value = r.advice
            _adviceError.value = null
            db.set(Keys.ADVICE_CACHE, adviceToJson(r.advice).toString())
            msg = "Advice loaded (${r.advice.stocks.size} ratings)"
            dest = ShareDest.ADVICE
        }
        if (r.transactions.isNotEmpty()) {
            // ADDED TO A REVIEW ALREADY WAITING, never replacing it (full test 2026-09-23,
            // A-8 / U-5). A share can arrive from any screen - and a pending review can be a
            // billed API extraction restored from disk after process death - so overwriting it
            // threw away rows Tj never saw. The review's own duplicate check marks rows that
            // appear in both (the same answer shared twice) as repeats, unticked.
            val pending = _importResult.value?.takeIf { it.transactions.isNotEmpty() }
            if (pending == null) {
                setImportResult(ExtractResult(r.transactions, r.notes, null, ""))
                msg = if (msg.isBlank()) "Found ${r.transactions.size} transactions - review them"
                else "$msg; ${r.transactions.size} transactions to review"
            } else {
                setImportResult(
                    mergeIntoPendingReview(pending, ExtractResult(r.transactions, r.notes, null, ""))
                )
                val added = "${r.transactions.size} transactions added to the review already waiting"
                msg = if (msg.isBlank()) added else "$msg; $added"
            }
            dest = ShareDest.ACTIVITY
        }
        return ShareImport(msg.ifBlank { "Nothing usable in that file" }, dest)
    }

    // ------------------------------------------------ SHARE -> PORTFOLIO (2026-09-23b)

    /**
     * Imports whatever `ShareImportActivity` left in the inbox, then asks `App` to open the
     * screen it filled. Called by `MainActivity` for every [com.tj.portfolio.util.ShareInbox.ACTION_IMPORT]
     * intent. Each file is read with [com.tj.portfolio.util.ShareInbox.next] and deleted with
     * [com.tj.portfolio.util.ShareInbox.done] once imported, so a re-delivered intent imports
     * nothing twice.
     */
    fun importSharedInbox() {
        viewModelScope.launch {
            // ONE DRAIN AT A TIME: a second share arriving mid-import re-calls this, and two
            // drains would read the same file twice.
            shareDrain.withLock {
                // See [researchCacheReady]. Bounded, so a wedged cache read can never swallow
                // the import - the worst case is the pre-existing cold-start race.
                kotlinx.coroutines.withTimeoutOrNull(5_000) { researchCacheReady.await() }
                val app = getApplication<Application>()
                // Oldest first, and each one removed only AFTER its import has been applied
                // (A-9 / U-7): a process death in between re-imports it on the next start
                // rather than losing it, and every importer is idempotent on a repeat.
                while (true) {
                    val item = withContext(Dispatchers.IO) {
                        com.tj.portfolio.util.ShareInbox.next(app)
                    } ?: break
                    // NOT WHILE A RESEARCH BUILD IS RUNNING (U-1): the build publishes when it
                    // lands and would overwrite a research answer applied underneath it - the
                    // reason the "Import answer" button is disabled while one runs. Only for
                    // answers that touch research (diff review R-11): advice and transactions
                    // never waited on it. Bounded for the same reason as the cache wait.
                    val kind = com.tj.portfolio.net.SharedAnswer.classify(item.text)
                    if (kind == com.tj.portfolio.net.SharedAnswer.Kind.RESEARCH ||
                        kind == com.tj.portfolio.net.SharedAnswer.Kind.DAY_TRADING
                    ) kotlinx.coroutines.withTimeoutOrNull(90_000) { _researchBusy.first { it.isEmpty() } }
                    val r = runCatching { importShared(item.text) }
                        .getOrElse { ShareImport("Couldn't import that share: ${it.message}", null) }
                    _toast.value = r.message
                    r.dest?.let { _shareNav.value = it }
                    val removed = withContext(Dispatchers.IO) {
                        com.tj.portfolio.util.ShareInbox.done(item)
                    }
                    if (!removed) break
                }
            }
        }
    }

    /**
     * Routes one shared answer by its CONTENT ([com.tj.portfolio.net.SharedAnswer.classify]) to
     * the importer its screen's own button uses, and says where it went. Anything that is not
     * an answer - an empty share, an image, the prompt file itself - is refused before any
     * importer sees it, and nothing is navigated to.
     */
    fun importShared(text: String): ShareImport {
        val kind = com.tj.portfolio.net.SharedAnswer.classify(text)
        com.tj.portfolio.net.SharedAnswer.rejection(kind)?.let { return ShareImport(it, null) }
        return when (kind) {
            com.tj.portfolio.net.SharedAnswer.Kind.DAY_TRADING -> {
                val parsed = runCatching { com.tj.portfolio.net.DayTradingBridge.parse(text) }
                    .getOrElse { return ShareImport("Couldn't read that file: ${it.message}", null) }
                val msg = applyDayTradingAnswer(parsed, "Claude app")
                if (parsed.error != null) return ShareImport(msg, null)
                jumpToResearch(ShareDest.DAY_TRADING)
                ShareImport(msg, ShareDest.DAY_TRADING)
            }
            com.tj.portfolio.net.SharedAnswer.Kind.RESEARCH -> {
                val parsed = runCatching { com.tj.portfolio.net.ResearchBridge.parse(text) }
                    .getOrElse { return ShareImport("Couldn't read that file: ${it.message}", null) }
                val msg = applyResearchAnswer(parsed, "Claude app")
                if (parsed.error != null) return ShareImport(msg, null)
                jumpToResearch(ShareDest.RESEARCH)
                ShareImport(msg, ShareDest.RESEARCH)
            }
            else -> importAdviceOrTransactions(text)
        }
    }

    /**
     * Point the Research screen at the section an answer filled. A Day Trading answer opens
     * Day Trading; a Research answer covers Trending, Best and ETFs at once, so it keeps
     * whichever of those was last open - unless that was Day Trading, which it did not touch.
     * Persisted as well as published, so a Research screen composed AFTER this (the usual case:
     * the Watch tab is opened by the same share) starts on the right section by itself.
     */
    private fun jumpToResearch(dest: ShareDest) {
        val sections = com.tj.portfolio.data.ResearchSet.SECTIONS
        val dt = sections.indexOf(com.tj.portfolio.data.ResearchSet.SECTION_DAY_TRADING)
        val target = if (dest == ShareDest.DAY_TRADING) dt
        else researchTab().takeIf { it != dt } ?: 0
        setResearchTab(target)
        _researchJump.value = target
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
            try { loadCachedResearchNow() } finally { researchCacheReady.complete(Unit) }
        }
    }

    private suspend fun loadCachedResearchNow() {
        val raw = runCatching { db.get(Keys.RESEARCH_CACHE) }.getOrDefault("")
        if (raw.isBlank()) return
        val loaded = runCatching {
            com.tj.portfolio.data.ResearchSet.fromJson(JSONObject(raw))
        }.getOrNull() ?: return
        // `isFullyEmpty`, not `isEmpty`: a cache holding only the ETF list - which is
        // exactly what a user who has opened the ETFs tab and nothing else has - would
        // otherwise be discarded on every launch and rebuilt from ten requests.
        if (loaded.isFullyEmpty) return
        // A COLD LAUNCH IS THE ONE PATH `carryExplanations`/`carryEtfExplanations` NEVER
        // SEE - they only run when a rebuild happens, and a cache this old is exactly the
        // case where none has for weeks. Evict here too, or a paragraph already stale
        // before the app was ever reopened would sit on screen unchanged until the next
        // TTL rebuild happened to run.
        val whyEvicted = evictStaleWhy(loaded)
        // SAME REASONING, FOR THE DAY-TRADING PLAN ITSELF - see [evictStaleDayTradingPlan].
        val set = whyEvicted.copy(
            // The newer of the build and the last Claude answer (diff review 2026-09-23, R-3): an
            // answer now counts as a refresh, so `generated` can be yesterday's while today's
            // Claude plan sits on the rows - and keying on it alone stripped that plan on the
            // next cold start, the D-1 loss moved from the rebuild to the relaunch.
            dayTrading = evictStaleDayTradingPlan(
                whyEvicted.dayTrading, maxOf(loaded.generated, loaded.dtExplained)
            )
        )
        withContext(Dispatchers.Main) {
            // Merged, not assigned: a live build may have landed while this was parsing,
            // and a cache read must never overwrite something newer.
            if (_research.value.isFullyEmpty) _research.value = set
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
    private fun cacheResearch(set: com.tj.portfolio.data.ResearchSet, throttleMs: Long = 0L) {
        _research.value = set
        // EVERY PATH THAT CAN CHANGE THE DAY TRADING SECTION GOES THROUGH HERE - the live
        // 30-second enrich loop, a Claude import, a full rebuild - so this is the one place
        // that can capture "here is a real recommendation" without hooking each of those
        // individually and risking a future one being missed. See
        // [captureDayTradingRecommendations]'s own header for why this is safe to call on
        // every publish rather than only on a change: the DB's own uniqueness constraint is
        // what actually decides whether anything gets written.
        // (Recommendations are NOT captured here any more - see D-1 on [loggableDayTradingRows].
        // Only the Day Trading sweep knows which rows it just re-planned from live bars.)
        // ---- THE LIVE TICK DOES NOT REWRITE THE WHOLE CACHE EVERY 30 SECONDS (full-tests
        // audit 2026-09-22, D-L8). This serialises every list - up to a few hundred KB - and
        // writes it to SQLite; the Day Trading loop called it on every tick that moved a level,
        // i.e. constantly, for figures the next tick recomputes anyway. A throttled caller
        // writes at most once per [throttleMs]; what it skips is owed, and paid when the app
        // goes to the background ([flushResearchCache]) or by the next unthrottled write.
        val now = System.currentTimeMillis()
        if (throttleMs > 0L && now - researchPersistedAt < throttleMs) {
            researchPersistOwed = true
            return
        }
        persistResearch(set, now)
    }

    private fun persistResearch(set: com.tj.portfolio.data.ResearchSet, now: Long) {
        researchPersistedAt = now
        researchPersistOwed = false
        // NEWEST PUBLISH WINS, NOT LAST WRITER (full test 2026-09-24, A-11). Each publish is its
        // own IO job; two a few milliseconds apart (a Claude import and a sweeping tick) ran in
        // parallel, and an older set - without the just-imported answer - could land last and
        // be what the next cold start loaded. Numbered in publish order; one writer at a time;
        // a set older than the one already written is dropped.
        val seq = researchPersistSeq.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            researchPersistLock.withLock {
                if (seq < researchWrittenSeq) return@withLock
                runCatching { db.set(Keys.RESEARCH_CACHE, set.toJson().toString()) }
                researchWrittenSeq = seq
            }
        }
    }

    /** Writes a throttled research change that is still owed - see [cacheResearch]. */
    private fun flushResearchCache() {
        if (researchPersistOwed) persistResearch(_research.value, System.currentTimeMillis())
    }

    /**
     * Tj, 2026-09-16: *"record and track each and every one of its day trading
     * recommendations... make sure it doesn't delete or modify any of the data if I refresh
     * the day trading section and it says the plan already fell apart."*
     *
     * Reads whatever the Day Trading section currently shows and asks [Db] to record each
     * priced row. Called on EVERY [cacheResearch] publish, not just when something is new -
     * `Db.logDayTradingRecommendation`'s own `INSERT OR IGNORE` against `UNIQUE(symbol,
     * trading_day)` is what actually makes this a no-op for a symbol already recorded today,
     * so calling it redundantly costs nothing but a conflict-ignored statement. The
     * alternative - tracking "have I already logged this one" in memory - would have to be
     * kept in step with every place a row's plan can change, and a single missed spot would
     * silently under-count the log. Letting the database's own constraint decide cannot drift.
     *
     * TWO GUARDS ADDED AFTER A REQUESTED PRE-SHIP REVIEW FOUND A REAL GAP:
     *
     * 1. ONLY WHILE THE REGULAR SESSION IS ACTUALLY OPEN. [cacheResearch] runs from paths that
     * have nothing to do with a live Day Trading tick - restoring yesterday's cached Research
     * from disk on a cold start, an ETF-only rebuild - and any of them can carry Day Trading
     * rows whose entry/stop/target were computed hours or days earlier, still sitting in
     * memory. Capturing one of those for the first time after the close (or the next morning)
     * would stamp `recordedAt` at that LATE moment, filtering out the entire intraday move the
     * plan was actually live for and reading a real win as [DayTradingOutcome.NO_ENTRY]. There
     * is no reliable way to recover when a stale row's numbers were actually first shown, so
     * the honest answer is not to guess - skip it, the same "a fabricated level is worse than
     * a blank" rule this file already follows for the plan itself.
     *
     * 2. [recordedAt] IS READ HERE, NOT INSIDE [Db]. Stamping it inside the IO-dispatched write
     * meant the timestamp was whenever the coroutine happened to run, not the moment this
     * function actually observed the row - usually milliseconds apart, but a real gap all the
     * same or a wrong lesson for the next thing that copies this pattern.
     */
    private fun captureDayTradingRecommendations(
        rows: List<com.tj.portfolio.data.ResearchRow>,
        liveNow: Set<String>
    ) {
        if (liveNow.isEmpty()) return
        if (com.tj.portfolio.net.MarketClock.phase() != com.tj.portfolio.net.MarketClock.Phase.OPEN) return
        val today = com.tj.portfolio.net.MarketClock.dayKey()
        // ---- GUARD 3: THE ROW'S OWN SESSION HAS TO BE TODAY'S.
        //
        // Guard 1 above asks the CLOCK whether the market is open. That is not the same
        // question as "were these numbers computed from today's session", and three separate
        // ways of logging a plan that was never today's got through on the difference. The row
        // carries the answer itself - `sessionDay` is stamped from the intraday bars' own
        // dates - so ask the row.
        //
        //  * THE MORNING REBUILD. `ResearchScreen` restores yesterday's cached rows and starts
        //    the live loop at the same moment; the first sweep runs against those restored
        //    rows, and any symbol whose technicals fetch fails or returns empty passes through
        //    unchanged - still holding YESTERDAY's entry/stop/target. `sweeping` then forces a
        //    `cacheResearch`, which wrote yesterday's levels under today's key with
        //    `recordedAt = now`. `INSERT OR IGNORE` made that first wrong row permanent for
        //    the day and blocked the correct plan from ever replacing it.
        //
        //  * MARKET HOLIDAYS. `MarketClock` deliberately does not model them, so `phase()`
        //    returns OPEN at 10am on Thanksgiving. No bars are dated today, so every plan is
        //    built from prior-session structure and then logged under a `tradingDay` for which
        //    `DayTradingEval.fetchDaySeries` can only ever return nothing: every row becomes
        //    DATA_UNAVAILABLE, is re-requested on every press for 55 days, and inflates the
        //    "N trades across M sessions" figure on the stats card. A holiday has no intraday
        //    bars, so this guard recognises it without needing a calendar.
        //
        //  * A PRICELESS ROW. `cacheResearch` runs BEFORE `fillResearchPrices`, so a symbol
        //    Claude has just added has no price yet. `priceAtRecommendation` is what
        //    `DayTradingEval.entryRises` prefers when deciding whether an entry is a buy-stop
        //    or a buy-limit; at 0 it falls back to matching the setup string against the
        //    literal word "Pullback", and Claude's setup text is free-form ("Support bounce"),
        //    so a genuine limit entry is read as a stop entry and "triggers" on the first bar
        //    that trades anywhere near it - crediting or blaming a fill that never happened.
        //
        //  * A ROW NOBODY WAS SHOWN (full test 2026-09-23, D-7). The one-time sweep plans every
        //    row, including the thirty below "Load more", and all of them were logged - while
        //    the stats card promises "this only records what it actually shows you". Only the
        //    rows inside the shown window count as a recommendation made to Tj.
        val shown = _researchShown.value[com.tj.portfolio.data.ResearchSet.SECTION_DAY_TRADING]
            ?: com.tj.portfolio.data.ResearchSet.PAGE
        val priced = loggableDayTradingRows(rows.take(shown), today, liveNow)
        if (priced.isEmpty()) return
        val recordedAt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.logDayTradingRecommendations(priced.map { r ->
                    com.tj.portfolio.data.Db.PendingDayTradingLog(
                        symbol = r.symbol,
                        tradingDay = today,
                        recordedAt = recordedAt,
                        setup = r.setup,
                        entry = r.entryPrice,
                        stop = r.stopPrice,
                        target = r.targetPrice,
                        // THE PRICE THE PLAN WAS MADE AT, for a Claude plan (review 2026-09-24,
                        // R2-3): the evaluator reads the plan's direction off this, and a Claude
                        // pullback logged after the stock traded under its entry was scored as a
                        // breakout - the opposite of what the card (D-9) told Tj.
                        priceAtRecommendation = r.planPrice.takeIf { r.planByClaude && it > 0.0 }
                            ?: r.price,
                        source = if (r.planByClaude) com.tj.portfolio.data.DayTradingLogEntry.SOURCE_CLAUDE
                        else com.tj.portfolio.data.DayTradingLogEntry.SOURCE_APP
                    )
                })
            }
        }
    }

    /**
     * The "success rate" button. Tj: *"On a separate button that I can press, the app will
     * show the success rate... based on... whether the actual stocks that day actually hit
     * those targets or not."* No automatic call anywhere near this - it runs ONLY when pressed,
     * the same "asleep unless asked for" rule the rest of the Day Trading feature already
     * follows for its own live network activity.
     *
     * Re-fetches intraday history only for rows that still need it - never-evaluated, or
     * [com.tj.portfolio.data.DayTradingOutcome.PENDING]/[com.tj.portfolio.data.DayTradingOutcome.DATA_UNAVAILABLE]
     * from an earlier press (see [com.tj.portfolio.data.DayTradingOutcome.isFinal]) - so
     * pressing it again after the log has grown costs requests only for what actually changed,
     * not the whole history every time.
     */
    fun evaluateDayTradingLog() {
        if (_dayTradingStatsLoading.value) return
        fgScope.launch {
            _dayTradingStatsLoading.value = true
            try {
                val all = withContext(Dispatchers.IO) { db.dayTradingLog() }
                val needsEval = all.filter {
                    !com.tj.portfolio.data.DayTradingOutcome.isFinal(it.outcome) &&
                        // AND ITS BARS ARE STILL FETCHABLE. `DATA_UNAVAILABLE` is not "final" on
                        // purpose - a one-off failure deserves another try - but once a session
                        // has aged out of Yahoo's ~60-day minute-level window the answer will be
                        // empty every single time. Without this, every press re-requested one
                        // session of intraday history for every recommendation ever recorded
                        // past that window, forever, for nothing. See
                        // [com.tj.portfolio.net.DayTradingEval.INTRADAY_RETENTION_DAYS].
                        com.tj.portfolio.net.DayTradingEval.intradayStillAvailable(it.tradingDay)
                }
                // ---- AND A ROW THAT AGED OUT BEFORE IT WAS EVER DECIDED IS SAID TO BE SO
                // (full-tests audit 2026-09-22, D-L5). The gate above rightly stops fetching it,
                // but it was left null/PENDING - so the card counted it "still in progress" for
                // ever, a recommendation from two months ago described as live. Its bars are gone
                // for good, which is exactly what DATA_UNAVAILABLE ("no price history") means.
                val expired = all.filter {
                    (it.outcome == null || it.outcome == com.tj.portfolio.data.DayTradingOutcome.PENDING) &&
                        !com.tj.portfolio.net.DayTradingEval.intradayStillAvailable(it.tradingDay)
                }
                if (expired.isNotEmpty()) withContext(Dispatchers.IO) {
                    expired.forEach {
                        db.setDayTradingOutcome(it.id, com.tj.portfolio.data.DayTradingOutcome.DATA_UNAVAILABLE, null)
                    }
                }
                // ---- CAPPED PER PRESS, OLDEST FIRST (full test 2026-09-23, D-6). One request per
                // unresolved row with no cap meant a press after two weeks away fired hundreds
                // of Yahoo chart requests at once - enough to arm the host cooldown the quote
                // loop and every chart share, leaving the whole app on stale prices for minutes.
                val batch = dayTradingRowsToResolve(needsEval)
                val left = needsEval.size - batch.size
                if (batch.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val gate = Semaphore(MAX_PARALLEL_REQUESTS)
                        batch.map { entry ->
                            async {
                                gate.withPermit { resolveOneDayTradingEntry(entry) }
                            }
                        }.awaitAll()
                    }
                }
                val refreshed = withContext(Dispatchers.IO) { db.dayTradingLog() }
                _dayTradingStats.value = com.tj.portfolio.net.DayTradingEval.stats(refreshed)
                if (left > 0) toast("Checked ${batch.size} - $left more to check, tap again")
            } finally {
                _dayTradingStatsLoading.value = false
            }
        }
    }

    /** One log row's own fetch-and-decide step, split out of [evaluateDayTradingLog] so the
     *  gate above bounds it the same way every other per-symbol network loop in this file is
     *  bounded. */
    private suspend fun resolveOneDayTradingEntry(entry: com.tj.portfolio.data.DayTradingLogEntry) {
        val bounds = com.tj.portfolio.net.DayTradingEval.sessionBoundsMs(entry.tradingDay)
        // An unparseable trading_day can only mean a corrupt row - nothing to evaluate, and
        // retrying it every press would be pointless. Marked unavailable rather than left null
        // forever, which would otherwise look identical to "never tried yet".
        if (bounds == null) {
            db.setDayTradingOutcome(entry.id, com.tj.portfolio.data.DayTradingOutcome.DATA_UNAVAILABLE, null)
            return
        }
        // Open until the bars are trustworthy, not merely until the bell - see SETTLE_GRACE_MS.
        val stillOpen = !com.tj.portfolio.net.DayTradingEval.sessionSettled(entry.tradingDay)
        val bars = runCatching {
            com.tj.portfolio.net.DayTradingEval.fetchDaySeries(entry.symbol, entry.tradingDay)
        }.getOrNull()
        // A REQUEST THAT FAILED says nothing about the day (D-12) - the row stays as it was and
        // the next press asks again. Only an ANSWER with no bars marks the data unavailable.
        if (bars == null) return
        if (bars.isEmpty()) {
            // A day still in progress with no bars yet is simply too early to say anything -
            // left as whatever it already was (null or PENDING) rather than written over, so
            // the NEXT press tries again instead of settling for "unavailable" prematurely.
            // Once the day has closed, an empty result really does mean the data is gone.
            if (!stillOpen) {
                db.setDayTradingOutcome(entry.id, com.tj.portfolio.data.DayTradingOutcome.DATA_UNAVAILABLE, null)
            }
            return
        }
        // NOTHING FROM THE PLAN'S LAST TEN MINUTES (full test 2026-09-24, D-11): it says "be flat by
        // 15:50" (12:50 on a half day), so a stop or target touched at 15:52 is not this trade's,
        // and an unresolved trade closes at the 15:50 print, not the 16:00 one.
        val tradable = bars.filter { com.tj.portfolio.net.DayTradingEval.beforeFlatTime(it.t) }
        val (outcome, exitPrice) = com.tj.portfolio.net.DayTradingEval.evaluate(
            entry.setup, entry.entry, entry.stop, entry.target, entry.priceAtRecommendation,
            entry.recordedAt, tradable, stillOpen
        )
        db.setDayTradingOutcome(entry.id, outcome, exitPrice)
    }

    /**
     * IS THE ETF LIST DUE A REBUILD?
     *
     * Its own clock, deliberately - [com.tj.portfolio.net.Research.ETF_TTL_MS] is six hours
     * against the thirty minutes the stock lists run on. TJ's rule for this list, verbatim:
     * *"keep the current list in cache until each update so it doesn't load on every
     * refresh."* So a stale list is still DRAWN; staleness only decides whether a rebuild is
     * allowed to start behind it.
     */
    fun etfsStale(): Boolean {
        val s = _research.value
        return s.etfs.isEmpty() || s.etfGenerated <= 0L ||
            System.currentTimeMillis() - s.etfGenerated >
            com.tj.portfolio.net.Research.ETF_TTL_MS
    }

    /**
     * Build (or rebuild) the Best ETFs list.
     *
     * Called when the ETFs sub-tab becomes visible and the cache is stale, and on a pull to
     * refresh there. NEVER on a timer, and never from the stock pass: this is ten requests,
     * and a list nobody has opened must not cost any.
     *
     * ---- THE ONE RULE THAT MATTERS HERE
     *
     * A failed or empty rebuild NEVER blanks the list on screen. The previous ETF list stays
     * exactly where it is, with its own timestamp still saying how old it is, because an
     * empty pass is almost always a provider cooldown - and replacing forty good rows with an
     * error because Yahoo rate-limited one burst would be the app punishing the user for
     * Yahoo's throttle. Same rule `loadResearch` already follows for the stock lists.
     */
    /**
     * A pull (the only forced caller) that lands during a Claude pass or an analyst pass used to
     * retract and do nothing, silently (full test 2026-09-24, U-4) - say why.
     */
    private fun toastResearchBusy() =
        toast("Research is busy with another update - pull again when it finishes")

    fun loadEtfs(force: Boolean = false) {
        // Same cold-start rule as [loadResearch] (U-2): the fund list is in that same cache.
        if (!researchCacheReady.isCompleted) {
            viewModelScope.launch { researchCacheReady.await(); loadEtfs(force) }
            return
        }
        if (_researchBusy.value.isNotEmpty()) { if (force) toastResearchBusy(); return }
        if (!force && !etfsStale()) return
        if (!force && researchRetry.blocked(RETRY_ETFS)) return
        if (force) researchRetry.clear()
        etfJob?.cancel()
        etfJob = fgScope.launch {
            _researchBusy.value = BUSY_ETFS
            _researchError.value = null
            if (force) _ui.value = _ui.value.copy(manualRefresh = true, refreshSource = PULL_RESEARCH)
            try {
                val built = withContext(Dispatchers.IO) {
                    runCatching { com.tj.portfolio.net.Research.buildEtfs() }
                        .getOrElse {
                            com.tj.portfolio.data.ResearchSet(
                                error = "Couldn't build the ETF list: ${it.message}"
                            )
                        }
                }
                if (built.etfs.isEmpty()) {
                    researchRetry.failure(RETRY_ETFS)
                    _researchError.value = built.error
                        ?: "No fund data came back this time. Try again in a few minutes."
                    // The warnings still land, so the footnote can say WHICH screen was quiet.
                    if (built.etfWarnings.isNotEmpty()) {
                        cacheResearch(_research.value.copy(etfWarnings = built.etfWarnings))
                    }
                } else {
                    researchRetry.success(RETRY_ETFS)
                    resetResearchPaging(listOf(com.tj.portfolio.data.ResearchSet.SECTION_ETF))
                    val cur = _research.value
                    cacheResearch(
                        cur.copy(
                            // Claude's paragraph about VOO does not go stale in six hours, and
                            // re-earning it costs another API call or another file round trip.
                            etfs = carryEtfExplanations(cur.etfs, built.etfs),
                            etfGenerated = built.etfGenerated,
                            etfWarnings = built.etfWarnings
                        )
                    )
                    // ---- AND THE FUNDS CLAUDE ADDED GET TODAY'S PRICE (full-tests audit
                    // 2026-09-22, S-L5). The screener re-prices every fund it finds; the ones
                    // only Claude found are carried across by `carryEtfExplanations` with the
                    // price and day change filled in on the day they were imported - so a
                    // three-week-old "+2.1% today" sat on the card as if it were live. One
                    // batched quote per six-hour rebuild, only for those rows.
                    val added = _research.value.etfs.filter { it.etf == null }.map { it.symbol }
                    if (added.isNotEmpty()) fillResearchPrices(added.take(MAX_PRICE_FILL))
                }
            } finally {
                _researchBusy.value = ""
                // DERIVED, NOT HAND-CLEARED - see syncManualIndicator. This used to blank
                // `manualRefresh` unconditionally whenever this call was the one that set it,
                // which is exactly the stranded/stolen-spinner bug documented there: a pull on
                // another tab that borrowed this same shared flag while the ETF build was still
                // running got its own indicator ripped away the moment THIS build finished, and
                // a pull that started here while quotes or feed were still loading got the
                // spinner stopped early instead of handed off to them.
                syncManualIndicator()
            }
        }
    }

    fun researchStale(): Boolean {
        val s = _research.value
        // A CLAUDE ANSWER COUNTS AS A REFRESH (full test 2026-09-23, U-1/D-1). An import never
        // moved `generated` - rightly, Claude did not re-screen anything - so a list older than
        // the TTL was rebuilt the moment the Research screen next looked at it, which after a
        // SHARE is immediately: the app navigates there to show the answer. Tj then watched the
        // answer he had just imported be replaced. The list Claude just read and reworked is as
        // current as a rebuild; the TTL runs from whichever happened last.
        // AND THE MOMENT OF AN IMPORT, not the answer's own date (review 2026-09-24, R1-4): S-4
        // stamps `explained` with an older answer's `asOf`, which made the list read as stale
        // - and rebuild - the moment the share navigated to it.
        val freshAt = maxOf(s.generated, s.explained, s.dtExplained, researchImportedAt)
        return s.isEmpty ||
            System.currentTimeMillis() - freshAt > com.tj.portfolio.net.Research.TTL_MS
    }

    /**
     * Build (or rebuild) the stock sections.
     *
     * Called when the Research sub-tab becomes visible and the cache is stale, and on a pull
     * to refresh. NEVER on a timer: this is ~18 requests across four providers, and the app's
     * standing rule is that nothing polls a provider for data the user is not looking at.
     */
    fun loadResearch(force: Boolean = false) {
        // NOT BEFORE THE CACHE HAS LOADED (full test 2026-09-23, U-2). The cache is parsed off
        // the main thread, and a cold start onto the Research tab asked first - saw an empty set,
        // which reads as stale - and paid a full ~18-request rebuild for a list sitting on disk.
        if (!researchCacheReady.isCompleted) {
            viewModelScope.launch { researchCacheReady.await(); loadResearch(force) }
            return
        }
        if (_researchBusy.value.isNotEmpty()) { if (force) toastResearchBusy(); return }
        if (!force && !researchStale()) {
            // Cached rows can still be missing their per-row lookups - finish those instead.
            enrichVisible()
            return
        }
        // A RECENT FAILURE IS AN ANSWER TOO - the same rule `loadChart` follows. Without it
        // the visibility effect and an empty result form a loop: stale list, rebuild, fail,
        // busy clears, effect re-runs, rebuild. See [researchRetry].
        if (!force && researchRetry.blocked(RETRY_STOCKS)) return
        if (force) researchRetry.clear()
        researchJob?.cancel()
        // THE FLICKER TJ REPORTED: "refreshed the best stocks section a few times... a stock
        // appeared at the top then disappeared after about 1 second."
        //
        // `enrichJob` (the analyst-consensus pass `enrichVisible` launches, below) used to be
        // left running here. It is a SIBLING of `researchJob`, not a child of it, so cancelling
        // `researchJob` alone never touched it - and it can genuinely still be mid-flight,
        // awaiting a Nasdaq round trip (about a second), when a second pull-to-refresh starts a
        // brand new rebuild. That stale pass then resolves and writes its OWN enriched (score-
        // boosted) copy of whatever row it fetched onto the CURRENT list purely by symbol
        // match (`enrichPass`, below) - promoting a stock to the top of Best for as long as
        // that stray write survives, until the next write (this rebuild's own enrich pass, or
        // another refresh) puts it back. Cancelling it here, alongside `researchJob`, is what
        // stops a superseded pass from ever getting to write. [See also the `generatedAtStart`
        // guard in `enrichPass` - belt and suspenders, for the case cancellation lands too late
        // to stop a write already in flight.]
        enrichJob?.cancel()
        researchJob = fgScope.launch {
            _researchBusy.value = BUSY_BUILDING
            _researchError.value = null
            if (force) _ui.value = _ui.value.copy(manualRefresh = true, refreshSource = PULL_RESEARCH)
            try {
                val built = withContext(Dispatchers.IO) {
                    // What the Feed fetched recently is the same data - see N-8 on `build`.
                    val now = System.currentTimeMillis()
                    val headlines = feedMarketRaw
                        ?.takeIf { now - it.first < MarketClock.feedIntervalSecs().coerceAtLeast(180) * 1000L }
                        ?.second
                    val social = feedSocialRaw?.takeIf { now - it.first < SOCIAL_REFRESH_MS }?.second
                    runCatching { com.tj.portfolio.net.Research.build(headlines, social) }
                        .getOrElse {
                            com.tj.portfolio.data.ResearchSet(
                                error = "Couldn't build research: ${it.message}"
                            )
                        }
                }
                if (built.isEmpty) {
                    // Keep whatever was on screen. An empty rebuild is almost always a
                    // provider cooldown, and blanking good lists to show an error
                    // would be the app punishing the user for Yahoo's rate limiter.
                    researchRetry.failure(RETRY_STOCKS)
                    _researchError.value = built.error
                        ?: "No research data came back this time. Try again in a few minutes."
                } else {
                    researchRetry.success(RETRY_STOCKS)
                    analystDone.clear()
                    // A fresh rebuild deserves a fresh top-of-list sweep, not the previous
                    // rebuild's sort order carried over onto a wholly different list.
                    dayTradingSweepDone = false
                    dayTradingRebuildGen++
                    // These rows are gone and fifty different ones have taken their place, so
                    // "show me ten more of the old list" cannot carry over - see
                    // [resetResearchPaging] for what it cost when it did.
                    resetResearchPaging(
                        listOf(
                            com.tj.portfolio.data.ResearchSet.SECTION_TRENDING,
                            com.tj.portfolio.data.ResearchSet.SECTION_BEST,
                            com.tj.portfolio.data.ResearchSet.SECTION_DAY_TRADING
                        )
                    )
                    // A rebuild carries forward the explanations already on file for the same
                    // symbols - Claude's paragraph about NVDA does not go stale in 30 minutes,
                    // and re-earning it would mean another API call or another file round trip.
                    cacheResearch(carryExplanations(_research.value, built))
                    // PLAN THE NEW LIST NOW, NOT AT THE LOOP'S NEXT TICK (D-4). With the market
                    // shut the loop sleeps five minutes, so every card sat without an entry, stop
                    // or target for that long after a rebuild. Restarting it cancels the sleep
                    // and sweeps the list just published.
                    if (dayTradingLiveWanted) startDayTradingLive(dayTradingLiveOnly)
                    // ---- ROWS THAT ARRIVED WITHOUT A PRICE (Round 66 audit, R2).
                    //
                    // THE BUG THIS FIXES. Trending's candidate set is the UNION of three
                    // sources - Reddit chatter, Yahoo's trending list, and the market-wide
                    // headlines - but every number on a trending row is read out of
                    // `universe`, which is only what the nine equity screeners returned. A
                    // symbol that is trending WITHOUT being a day gainer, a day loser, most
                    // active, most shorted or any of the other six is not in that map, so
                    // `Research.buildTrending` writes it out with an empty name, a price of
                    // zero and a day change of zero, and nothing downstream ever went back
                    // for them: `enrichPass` walks Best only, and the fill below had exactly
                    // one caller, the Claude import path. Those rows sat in the list with a
                    // blank price for as long as they trended - which is precisely the set of
                    // stocks the section exists to surface, because a name the screeners
                    // already carry is not news.
                    //
                    // ONE BATCHED REQUEST for up to [MAX_PRICE_FILL] symbols, awaited here so
                    // it cannot race the enrich pass below - see [fillPricesNow].
                    val priceless = pricelessRows(_research.value)
                    if (priceless.isNotEmpty()) fillPricesNow(priceless)
                }
            } finally {
                _researchBusy.value = ""
                // DERIVED, NOT HAND-CLEARED - see the note in loadEtfs and syncManualIndicator
                // itself. Same bug, same fix: this must not blindly turn the indicator off out
                // from under a pull that is attached to it from a different tab.
                syncManualIndicator()
            }
            enrichVisible()
        }
    }

    /** Reveal ten more rows of one section, and pay for their lookups - only then. */
    fun showMoreResearch(section: String) {
        val cur = _researchShown.value[section] ?: com.tj.portfolio.data.ResearchSet.PAGE
        val total = _research.value.section(section).size
        if (cur >= total) return
        _researchShown.value = _researchShown.value +
            (section to (cur + com.tj.portfolio.data.ResearchSet.PAGE).coerceAtMost(total))
        // ONLY WHERE THERE IS A PER-ROW LOOKUP TO PAY FOR. `enrichPass` walks Best and
        // nothing else - a fund's expense ratio and its returns arrived with the screener row,
        // so an ETF page has no second stage at all, and Trending has no analyst pass. Calling
        // it anyway flipped the busy indicator and ran an analyst sweep over an unrelated list
        // every time ten more funds were revealed.
        if (section == com.tj.portfolio.data.ResearchSet.SECTION_BEST) enrichVisible()
    }

    /**
     * PUT THE NAMED SECTIONS BACK TO ONE PAGE (Round 66 audit, RES-5).
     *
     * ---- THE BUG THIS FIXES
     *
     * This function existed and had NO CALLERS, so a page count never went back down. Press
     * "Load more" three times on Best and `_researchShown["BEST"]` is 40; thirty minutes later
     * the TTL rebuild replaces those fifty rows with fifty entirely different symbols, clears
     * `analystDone`, and calls `enrichVisible` - which reads the surviving 40, adds a page,
     * and enriches FIFTY rows. Up to fifty Nasdaq consensus requests, fired by a background
     * clock, for a list nobody asked to see more of, against the ten the screen's own KDoc
     * calls TJ's explicit rule. The same stale count also decided how much of each list went
     * into the Claude prompt.
     *
     * ---- WHY IT TAKES AN ARGUMENT
     *
     * The ETF list runs on a six-hour clock of its own and rebuilds independently, so a stock
     * rebuild must not collapse the ETF page the user is part-way down, and vice versa. Each
     * rebuild resets exactly the sections it replaced.
     */
    fun resetResearchPaging(
        sections: List<String> = com.tj.portfolio.data.ResearchSet.SECTIONS
    ) {
        _researchShown.value = _researchShown.value +
            sections.associateWith { com.tj.portfolio.data.ResearchSet.PAGE }
    }

    /**
     * The per-row lookups, for what is on screen and nothing else.
     *
     * Two stages, in this order and for the reason spelled out in [com.tj.portfolio.net.Research]:
     * analyst coverage over a window WIDER than the page (so it can change the ranking), then
     * and nothing else. There was a second stage - an inverse-ETF search over the page -
     * until round 66 removed the section it served.
     */
    private fun enrichVisible() {
        if (enrichJob?.isActive == true) return
        val set = _research.value
        if (set.isEmpty) return
        enrichJob = fgScope.launch {
            // ONLY WHEN NOTHING ELSE HOLDS THE FLAG (full-tests audit 2026-09-22, S-M3). It is
            // shared: "Explain with Claude" sets BUSY_EXPLAINING and its button is enabled on
            // `busy.isEmpty()`. Taking it unconditionally overwrote a running explain's state,
            // and the `finally` below then cleared it to "" while Claude was still answering -
            // re-enabling the button mid-call, a second billed request one tap away.
            if (_researchBusy.value.isEmpty()) _researchBusy.value = BUSY_DETAIL
            // Whether this pass fetched anything at all. The `finally` below re-serialises
            // the WHOLE ResearchSet - a few hundred rows - and writes it to SQLite, and this
            // function is now called whenever the Research screen re-evaluates its build
            // effect. Without this flag a pass with nothing to do still paid for that.
            var did = false
            try {
                // Loop until a pass finds nothing left to do. "Load more" pressed WHILE this
                // is running would otherwise be swallowed by the guard above and the new ten
                // rows would sit there with no analyst data until something else happened to
                // trigger a pass - which on a screen the user is already looking at is never.
                var passes = 0
                while (enrichPass() && passes++ < 6) { did = true }
            } finally {
                // PERSISTED IN THE `finally`, not after the loop. The pass may have paid for
                // up to twenty Nasdaq consensus lookups and twenty Yahoo searches; leaving
                // the app mid-enrich cancels the coroutine, and with the write after the loop
                // none of that reached disk. It survived in memory only until the process was
                // killed, and then the requests had to be spent all over again.
                //
                // ONLY WHEN THERE WAS SOMETHING TO PERSIST. A cancelled pass still counts -
                // `did` is set as soon as a window is enriched, before the loop can be cut -
                // but a pass that found nothing to do writes nothing.
                if (did) cacheResearch(_research.value)
                if (_researchBusy.value == BUSY_DETAIL) _researchBusy.value = ""
                syncManualIndicator()   // U-1: every clear of a busy flag re-derives the spinner
            }
        }
    }

    /** One sweep over the visible windows. Returns true if it fetched anything. */
    private suspend fun enrichPass(): Boolean {
        var did = false
        // ONE SECTION HAS A SECOND STAGE. Trending is ranked on chatter, not on
        // fundamentals, and an ETF's numbers all arrived with its screener row.
        for (name in listOf(com.tj.portfolio.data.ResearchSet.SECTION_BEST)) {
            val rows = _research.value.section(name)
            if (rows.isEmpty()) continue
            val shown = (_researchShown.value[name] ?: com.tj.portfolio.data.ResearchSet.PAGE)
                .coerceAtMost(rows.size)
            val window = (shown + com.tj.portfolio.data.ResearchSet.PAGE).coerceAtMost(rows.size)
            val head = rows.take(window)
            val done = analystDone.toSet()
            if (head.any { "$name:${it.symbol}" !in done }) {
                did = true
                // THIS GENERATION'S STAMP, TAKEN BEFORE THE ONLY SUSPENSION POINT BELOW.
                // `enrichAnalyst` is a Nasdaq round trip of about a second; if a full rebuild
                // replaces `_research.value` while this is in flight, `generated` moves on -
                // see the check right after the suspend, below.
                val generatedAtStart = _research.value.generated
                val enriched = withContext(Dispatchers.IO) {
                    com.tj.portfolio.net.Research.enrichAnalyst(
                        head,
                        alreadyDone = head.map { it.symbol }
                            .filter { "$name:$it" in done }.toSet()
                    )
                }
                // A REBUILD SUPERSEDED THIS PASS WHILE IT WAS SUSPENDED (the flicker Tj
                // reported - see the note by `enrichJob?.cancel()` in `loadResearch`).
                //
                // `head`/`window` above describe a list that no longer exists once `generated`
                // has moved on - `Research.build()` produced an entirely new candidate set, so
                // matching `enriched` back onto the CURRENT list by symbol (below) would splice
                // this pass's stale, analyst-boosted score onto whatever row now sits at that
                // symbol, and re-sort the window on it - which is exactly how a stock could
                // jump to the top of Best on data a newer rebuild had already replaced.
                // Cancelling `enrichJob` on every rebuild should stop this pass before it gets
                // here at all; this is the second line of defence for the case cancellation
                // lands after the suspend point above has already returned.
                if (_research.value.generated != generatedAtStart) {
                    did = false
                    continue
                }
                // THE WHOLE WINDOW IS MARKED, AND THAT IS DELIBERATE - see below.
                //
                // An earlier version of this round marked only rows that came back WITH a
                // consensus, reasoning that a failed lookup should be retried. It made things
                // much worse, because `Research.consensus` also returns null for a stock
                // nobody covers - which is most small caps, and the screener universe is
                // full of them. An uncovered symbol could then never be marked,
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
                // ---- PROJECTED ONTO THE LIST AS IT IS NOW, NOT AS IT WAS (Round 66 audit,
                // RES-7).
                //
                // THE BUG THIS FIXES. This read `rows` at the top of the pass, suspended for
                // seconds of Nasdaq requests, and then wrote back `ranked + tail` - a list
                // assembled entirely from the pre-suspension snapshot. Anything that landed in
                // `_research` during those seconds was silently thrown away, and the pass's
                // own `finally` then PERSISTED the loss to SQLite.
                //
                // It is reachable by one tap. "Import answer" carries no busy guard - unlike
                // "Explain with Claude", which is `enabled = busy.isEmpty()` - so the user can
                // pick Claude's reply file while an enrich pass is in flight. The import
                // writes its merged set, the enrich pass overwrites `best` a moment later with
                // the list it read before the import, and every `why` Claude just wrote for a
                // Best row, plus every Best row it added, is gone - while the toast on screen
                // still says "Research updated - 10 explained, 1 added".
                //
                // The fix is to treat this pass as what it is: an update to specific ROWS, not
                // a replacement of the list. Read the section again at write time and swap in
                // the enriched copy of each symbol that is still there. Rows added while the
                // pass ran survive; rows removed while it ran stay removed; nothing this pass
                // did not fetch is touched.
                _research.value = _research.value.withSection(
                    name, applyAnalystEnrichment(_research.value.section(name), enriched, window)
                )
            }

        }
        return did
    }

    fun dismissResearchError() { _researchError.value = null }

    /** The rows currently on screen - what every Claude path (Research and Day Trading) is asked about. */
    private fun visibleResearch(): com.tj.portfolio.data.ResearchSet {
        val s = _research.value
        fun cut(name: String) = s.section(name)
            .take(_researchShown.value[name] ?: com.tj.portfolio.data.ResearchSet.PAGE)
        return s.copy(
            trending = cut(com.tj.portfolio.data.ResearchSet.SECTION_TRENDING),
            best = cut(com.tj.portfolio.data.ResearchSet.SECTION_BEST),
            etfs = cut(com.tj.portfolio.data.ResearchSet.SECTION_ETF),
            dayTrading = cut(com.tj.portfolio.data.ResearchSet.SECTION_DAY_TRADING)
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
    fun writeResearchPrompt(onDone: (PromptOut) -> Unit) {
        if (_research.value.isFullyEmpty) {
            onDone(PromptOut("Load the research lists first - there is nothing to ask about yet.", null))
            return
        }
        viewModelScope.launch {
            val body = researchPromptFile()
            val out = withContext(Dispatchers.IO) {
                runCatching { deliverPrompt(com.tj.portfolio.net.ResearchBridge.PROMPT_FILE, body) }
                    .getOrElse { PromptOut("Couldn't build the prompt file: ${it.message}", null) }
            }
            onDone(out)
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
        if (_research.value.isFullyEmpty) {
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
                syncManualIndicator()   // U-1: every clear of a busy flag re-derives the spinner
            }
        }
    }

    /**
     * The offline path's other half: an answer file picked in the Research section.
     *
     * ONE FILE PICKER SERVES BOTH TABS (Round 67) - the Research screen's "Import answer"
     * button is shared by Trending/Best/ETFs and Day Trading, and a Day Trading reply carries
     * its own payload key, recognised and routed first, the same rule [importClaudeFile]
     * already follows for a Research reply picked from the Advice tab by mistake.
     */
    fun importResearchFile(text: String): String {
        if (com.tj.portfolio.net.DayTradingBridge.looksLikeDayTrading(text)) {
            return importDayTradingFile(text)
        }
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
        // The answer's own date when it names an earlier day (S-4) - see Parsed.answeredAt.
        val writtenAt = parsed.answeredAt ?: System.currentTimeMillis()
        // Rows Claude added go at the end of the page on screen, not after the buffer (S-11).
        fun pageEnd(section: String) =
            _researchShown.value[section] ?: com.tj.portfolio.data.ResearchSet.PAGE
        val merged = cur.copy(
            trending = com.tj.portfolio.net.ResearchBridge.merge(cur.trending, parsed.trending,
                writtenAt, insertAt = pageEnd(com.tj.portfolio.data.ResearchSet.SECTION_TRENDING)),
            best = com.tj.portfolio.net.ResearchBridge.merge(cur.best, parsed.best,
                writtenAt, insertAt = pageEnd(com.tj.portfolio.data.ResearchSet.SECTION_BEST)),
            // RE-SORTED, unlike the stock lists. Claude is asked to ADD funds the app's
            // screener universe cannot see - which is the whole point of researching this
            // list online - and `merge` puts additions last.
            //
            // The app's own score orders the rows it screened; conviction orders the ones it
            // did not, BELOW them. See [ResearchRow.conviction] for why a model's number is
            // no longer allowed to sort itself into the top of this list.
            etfs = com.tj.portfolio.net.ResearchBridge
                .merge(cur.etfs, parsed.etfs, writtenAt)
                .sortedWith(compareByDescending<com.tj.portfolio.data.ResearchRow> { it.score }
                    .thenByDescending { it.conviction }),
            explained = writtenAt.also { researchImportedAt = System.currentTimeMillis() },
            explainedBy = via,
            // MERGED, not overwritten - every other field in this copy is. A second reply
            // that simply omits `notes` used to erase the first one's paragraph.
            notes = parsed.notes.ifBlank { cur.notes },
            // The rebuild clock is NOT advanced by an explanation pass: Claude wrote prose
            // about rows the screener produced, it did not re-screen anything.
            generated = cur.generated
        )
        // AND THE PAGE GROWS BY WHAT WAS ADDED (review 2026-09-24, R2-2): inserted at the page
        // end they were the first HIDDEN rows - still behind "Load more", and still left out of
        // the next prompt, which is built from the rows on screen.
        for ((section, before, after) in listOf(
            Triple(com.tj.portfolio.data.ResearchSet.SECTION_TRENDING, cur.trending.size, merged.trending.size),
            Triple(com.tj.portfolio.data.ResearchSet.SECTION_BEST, cur.best.size, merged.best.size)
        )) {
            val grew = after - before
            if (grew > 0) _researchShown.value = _researchShown.value + (section to pageEnd(section) + grew)
        }
        cacheResearch(merged)
        _researchError.value = null
        val known = (cur.trending + cur.best + cur.etfs).map { r -> r.symbol }.toSet()
        val added = (parsed.trending + parsed.best + parsed.etfs)
            .map { it.symbol }.distinct().count { it !in known }
        // Anything Claude ADDED has no price yet - fetch those quotes so the new rows are
        // not the only ones on screen without a number beside them.
        val newSymbols = (merged.trending + merged.best + merged.etfs)
            .filter { it.price <= 0.0 }.map { it.symbol }.distinct().take(MAX_PRICE_FILL)
        if (newSymbols.isNotEmpty()) fillResearchPrices(newSymbols)
        val n = parsed.trending.size + parsed.best.size + parsed.etfs.size
        return "Research updated - $n explained" + (if (added > 0) ", $added added" else "")
    }

    // ------------------------------------------------ DAY TRADING Claude bridge (Round 67)
    //
    // Same shape as the block above - one bundle/prompt/explain/import/apply per Claude path -
    // for a section with its own bridge, its own prompt and its own button. See
    // [ResearchSet.dtExplained] for why it does NOT share state with [applyResearchAnswer].

    /**
     * THE WHOLE DAY TRADING LIST, NOT THE PAGE ON SCREEN (full test 2026-09-23, D-4). The
     * prompt tells Claude "the array you return IS my new list", and [applyDayTradingAnswer]
     * replaces the list with it - but only the first ten rows were sent, so the other thirty
     * were deleted by a model that never saw them, and the toast called them "dropped". Sending
     * every row costs no request (it is data already on the phone) and makes "dropped" true.
     */
    private fun dayTradingSet(): com.tj.portfolio.data.ResearchSet =
        visibleResearch().copy(dayTrading = _research.value.dayTrading)

    fun dayTradingBundle(): String = com.tj.portfolio.net.DayTradingBridge.bundleJson(
        dayTradingSet(),
        heldSymbols().toList().sorted(),
        watchedSymbols().toList().sorted(),
        liveAt = dayTradingLiveAt
    )

    fun dayTradingPromptFile(): String = com.tj.portfolio.net.DayTradingBridge.prompt(
        dayTradingSet(),
        heldSymbols().toList().sorted(),
        watchedSymbols().toList().sorted(),
        liveAt = dayTradingLiveAt
    )

    /** Write the offline prompt file - one fixed name, replaced, same as [writeResearchPrompt]. */
    fun writeDayTradingPrompt(onDone: (PromptOut) -> Unit) {
        if (_research.value.dayTrading.isEmpty()) {
            onDone(PromptOut("Load the research lists first - there is nothing to ask about yet.", null))
            return
        }
        viewModelScope.launch {
            val body = dayTradingPromptFile()
            val out = withContext(Dispatchers.IO) {
                runCatching { deliverPrompt(com.tj.portfolio.net.DayTradingBridge.PROMPT_FILE, body) }
                    .getOrElse { PromptOut("Couldn't build the prompt file: ${it.message}", null) }
            }
            onDone(out)
        }
    }

    /** The API-key path: same question, same parser, no file round trip. */
    fun explainDayTrading() {
        val key = claudeKey()
        if (key.isBlank()) {
            _researchError.value =
                "Add your Claude API key in Settings, or use \"Make prompt file\" to do this " +
                    "through the Claude app instead - both give the same answer."
            return
        }
        if (_research.value.dayTrading.isEmpty()) {
            _researchError.value = "Load the research lists first."
            return
        }
        if (_researchBusy.value.isNotEmpty()) return
        viewModelScope.launch {
            _researchBusy.value = BUSY_EXPLAINING
            _researchError.value = null
            try {
                val bundle = dayTradingBundle()
                val chosen = ensureModel()
                var out = withContext(Dispatchers.IO) {
                    runCatching { Claude.dayTrading(key, chosen, bundle, webSearch()) }
                        .getOrElse {
                            com.tj.portfolio.net.DayTradingBridge.Parsed(
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
                            runCatching { Claude.dayTrading(key, fresh, bundle, webSearch()) }
                                .getOrElse {
                                    com.tj.portfolio.net.DayTradingBridge.Parsed(
                                        error = it.message ?: "Request failed"
                                    )
                                }
                        }
                    }
                }
                applyDayTradingAnswer(out, "API")
            } finally {
                _researchBusy.value = ""
                syncManualIndicator()   // U-1: every clear of a busy flag re-derives the spinner
            }
        }
    }

    /** The offline path's other half: an answer file recognised and routed by [importResearchFile]. */
    fun importDayTradingFile(text: String): String {
        val parsed = runCatching { com.tj.portfolio.net.DayTradingBridge.parse(text) }
            .getOrElse {
                return "Couldn't read that file: ${it.message}"
            }
        return applyDayTradingAnswer(parsed, "Claude app")
    }

    private fun applyDayTradingAnswer(
        parsed: com.tj.portfolio.net.DayTradingBridge.Parsed,
        via: String
    ): String {
        if (parsed.error != null) {
            _researchError.value = parsed.error
            return parsed.error
        }
        val cur = _research.value
        val merged = cur.copy(
            dayTrading = com.tj.portfolio.net.DayTradingBridge.merge(cur.dayTrading, parsed.picks),
            dtExplained = System.currentTimeMillis(),
            dtExplainedBy = via,
            // MERGED, not overwritten - same reason [applyResearchAnswer] does this for `notes`.
            dtNotes = parsed.notes.ifBlank { cur.dtNotes }
        )
        cacheResearch(merged)
        _researchError.value = null
        val known = cur.dayTrading.map { it.symbol }.toSet()
        val kept = parsed.picks.map { it.symbol }.distinct()
        val added = kept.count { it !in known }
        val dropped = known.count { it !in kept.toSet() }
        // Anything Claude ADDED has no price yet - the fill gives it one, and the live
        // technicals sweep computes the app's own plan for it on the next tick if Claude did
        // not supply usable levels of its own.
        val newSymbols = merged.dayTrading
            .filter { it.price <= 0.0 }.map { it.symbol }.distinct().take(MAX_PRICE_FILL)
        if (newSymbols.isNotEmpty()) fillResearchPrices(newSymbols)
        // A NEW LIST DESERVES ITS ONE SWEEP (D-13): the rows Claude added get the app's plan
        // (where Claude gave none) and a live price check now, not at 04:00. D-8 keeps the
        // order Claude chose.
        dayTradingSweepDone = false
        dayTradingRebuildGen++
        if (dayTradingLiveWanted) startDayTradingLive(dayTradingLiveOnly)
        // SAYS WHAT ACTUALLY HAPPENED, INCLUDING THE REMOVALS (Round 69). Claude's list now
        // REPLACES the section rather than annotating it, so a run that quietly deleted six
        // rows the user had been reading must not report itself as "6 explained".
        return "Day trading rebuilt by Claude - ${parsed.picks.size} " +
            (if (parsed.picks.size == 1) "pick" else "picks") +
            (if (added > 0) ", $added new" else "") +
            (if (dropped > 0) ", $dropped dropped" else "")
    }

    // ==================================================== DAY TRADING LIVE TECHNICALS (Round 68)
    //
    // Tj, 2026-09-11, explicit design constraints given when this was screened - implemented
    // to the letter, not paraphrased:
    //
    //   "Do not use Claude api at all in the app unless I explicitly press a button" - this
    //   pass never touches Claude. It is a plain market-data fetch
    //   ([com.tj.portfolio.net.DayTradingTechnicals]), the same family as [enrichPass]'s
    //   analyst-consensus lookups for Best.
    //
    //   "it can use as much mobile Internet data as needed, and it can do this all
    //   automatically, but only when I have that tab open... The feature should be asleep
    //   when I'm not using it" - [startDayTradingLive]/[stopDayTradingLive] are called from
    //   ResearchScreen's own lifecycle wiring, tied to BOTH which Research sub-tab is selected
    //   AND the app's foreground state: this runs on [fgScope], which `setForeground(false)`
    //   cancels outright, and `stopDayTradingLive` additionally cancels it the moment the
    //   Day Trading tab is no longer the one on screen, which backgrounding alone would not.

    // dayTradingLiveJob and dayTradingSweepDone are declared above `init` (checkinit.py) -
    // see the note there. Cleared on every fresh stock rebuild in [loadResearch], the same
    // cadence [analystDone] uses. DAY_TRADING_LIVE_INTERVAL_MS is top-level, near
    // MAX_PRICE_FILL - a `const val` cannot live inside the class itself.

    /**
     * Starts (or restarts) the live technicals loop. Idempotent - cancels any prior job first,
     * so `ResearchScreen` can call this every time its `LaunchedEffect` re-evaluates without
     * tracking whether one is already running.
     *
     * ALSO CALLED FROM [setForeground] ON THE WAY BACK IN (a real bug, caught by review before
     * shipping). This runs on `fgScope`, and backgrounding the app cancels that scope outright
     * - correctly, per its own header, so a wave of requests does not run with nothing on
     * screen to draw it. But `setForeground(true)` replaces `fgScope` with a fresh one and
     * nothing was re-launching what the OLD one had been running: the Day Trading tab could
     * still be the one on screen, `dayTradingLiveJob` would still hold a reference to the now-
     * dead job, and the loop would silently never tick again for the rest of that screen
     * instance's life. [dayTradingLiveWanted] is what lets `setForeground` tell "the tab is
     * still open, please restart" from "the tab was closed, leave it alone".
     */
    fun startDayTradingLive(only: String? = null) {
        dayTradingLiveWanted = true
        // ---- ONE SYMBOL, FOR A DETAIL SCREEN (full-tests audit 2026-09-22, U-M5). Opening a
        // pick from the Day Trading list takes the Research screen out of composition, which
        // stopped this loop - and the detail screen's "today's plan" section, which reads the
        // same row, froze at the moment of the tap. The detail screen now keeps it running for
        // its own symbol only, so a holding that happens to be a pick costs one row's fetch,
        // not the whole list's, and only while that screen is open.
        dayTradingLiveOnly = only?.uppercase()
        dayTradingLiveJob?.cancel()
        dayTradingLiveJob = fgScope.launch {
            while (isActive) {
                // ---- ASK THE CLOCK AND THE RADIO FIRST, THE WAY `startAuto` ALREADY DOES.
                //
                // This loop used to sweep unconditionally. Two cases made that expensive for
                // nothing:
                //
                // CLOSED MARKET. Neither leg of `DayTradingTechnicals.fetch` can produce a
                // different answer at 11pm or on a Sunday - the daily bars are settled and
                // there are no new intraday bars - yet the cadence was identical to the
                // open-market one. The main quote loop has consulted the clock for this
                // reason since Round 56 (`currentQuoteIntervalSecs`), and
                // `captureDayTradingRecommendations` gates itself on `phase() == OPEN`; this
                // loop simply never asked. Tj's "use as much data as it needs while the tab
                // is open" was about the trading session, not about 3am.
                //
                // OFFLINE. `startAuto` skips its pass when `online()` is false, with a note
                // that a tunnel otherwise "cost battery going in and a stale screen for
                // minutes coming out", because three failures arm an escalating per-host
                // cooldown in `Http`. This loop had no such check, so a dead spot fired a
                // failing socket per visible row every 30 seconds and armed the very
                // cooldowns the main loop is careful to avoid - leaving the tab stale for
                // minutes after signal returned.
                //
                // EXTENDED hours still sweep: pre-market and after-hours prints move the
                // premarket high and the session high/low, which are real trigger levels.
                val phase = com.tj.portfolio.net.MarketClock.phase()
                // AND ONCE PER REBUILD WHILE CLOSED (full test 2026-09-23, D-3). "Nothing can
                // move while closed" assumed the rows already had plans - but a rebuild (or a
                // cold start, whose stale plans are evicted) hands this loop plan-less rows, and
                // with no sweep the tab showed no entry/stop/target all night and all weekend:
                // the "levels to plan from before the open" path was unreachable. The one sweep
                // per rebuild ([dayTradingSweepDone]) costs one pass, not a poll.
                // Only the LIST's sweep counts (diff review, R-7): a single-symbol run for an open
                // detail screen never marks the sweep done, so it would have fetched every tick.
                val closed = phase == com.tj.portfolio.net.MarketClock.Phase.CLOSED
                val listSweepOwed = dayTradingLiveOnly == null && !dayTradingSweepDone
                if (online() && (!closed || listSweepOwed)) {
                    enrichDayTradingVisible()
                }
                delay(dayTradingLiveDelay(phase, com.tj.portfolio.net.MarketClock.sessionElapsedFraction()))
            }
        }
    }

    /** Stops it - called the moment the Day Trading tab is no longer the one on screen. */
    fun stopDayTradingLive() {
        dayTradingLiveWanted = false
        dayTradingLiveOnly = null
        dayTradingLiveJob?.cancel()
        dayTradingLiveJob = null
    }

    /**
     * One sweep over the Day Trading section: fetches real technicals for each symbol and
     * upgrades its risk plan and score IN PLACE. The ORDINARY tick - every call after the
     * first one following a rebuild - never re-sorts; see below for the one call that does.
     *
     * NEVER RE-SORTED ON AN ORDINARY TICK, ON PURPOSE. Best's analyst enrichment re-ranks its
     * window because that list is read once and left; a live view refreshing every 30 seconds
     * that also reshuffled every 30 seconds would be worse to watch than a ranking that
     * occasionally under-ranks a stock that just broke out - the screener's own ordering holds
     * until the next full rebuild, or the one-time sweep-completion sort described below.
     *
     * THE SCORE BONUS IS RECOMPUTED ON EVERY TICK FROM THE BUILD-TIME HALVES (D-10, full test
     * 2026-09-24) - see [scoreDayTradingRow]. [ResearchScore.withTechnicals] ADDS to whatever
     * score it is handed, so it is handed the row's build-time base, never its own last output:
     * nothing compounds, and a VWAP or opening-range signal that appears (or stops confirming)
     * mid-session moves the score and its reason line with it. The risk plan
     * ([ResearchScore.tradePlan]) is likewise computed fresh from the current price every tick.
     *
     * THE FIRST CALL AFTER A REBUILD SWEEPS THE WHOLE SECTION, NOT JUST THE VISIBLE WINDOW
     * (Round 75). Tj: "make this section try to find and show the actual stocks that I can act
     * on currently at the top of the list... it can use as much mobile Internet data as needed
     * ... but only when I have that tab open" (his own words, Part 4, already covering exactly
     * this cost). [dayTradingSweepDone] gates it to once per rebuild - every call after the
     * first goes back to the paged `shown` window, unchanged from before this round. The sort
     * that follows a completed sweep is the ONLY place this section's order ever changes after
     * build time; it does not reopen the "never re-sorted" rule the header above states for the
     * ordinary tick-to-tick refresh - see the sort call at the end of this function for why.
     */
    private suspend fun enrichDayTradingVisible() {
        val name = com.tj.portfolio.data.ResearchSet.SECTION_DAY_TRADING
        val rows = _research.value.section(name)
        if (rows.isEmpty()) return
        val only = dayTradingLiveOnly
        // A single-symbol run never counts as the list's one-time full sweep, and never sorts.
        val sweeping = only == null && !dayTradingSweepDone
        val sweepGen = dayTradingRebuildGen
        val head = if (only != null) rows.filter { it.symbol == only } else if (sweeping) rows else {
            val shown = (_researchShown.value[name] ?: com.tj.portfolio.data.ResearchSet.PAGE)
                .coerceAtMost(rows.size)
            rows.take(shown)
        }
        // CONCURRENT, GATED - the same `Semaphore(MAX_PARALLEL_REQUESTS)` + `async`/`awaitAll`
        // shape this file already uses for every other visible-window fetch (sparkline
        // refresh, insider refresh, fundamentals prefetch). Fetching this sequentially - a
        // real inefficiency caught by review before shipping - meant a window past the
        // default ten rows (any "Load more" tap) could take several times the nominal
        // 30-second cadence to complete a single sweep, since each symbol pays up to two
        // chained HTTP round trips.
        val fetched = withContext(Dispatchers.IO) {
            val gate = Semaphore(MAX_PARALLEL_REQUESTS)
            head.map { row ->
                async {
                    gate.withPermit {
                        // THE CARD'S OWN 1-DAY CHART (Round 70). Tj: "put a stock chart next
                        // to each of the stocks in the day trading section... only that
                        // current day's chart". [loadChart] is the exact function every other
                        // chart in the app already calls - cache-first and a no-op within its
                        // own freshness window - but it is fire-and-forget on its own, and a
                        // bare call per row here would fire every symbol's disk-read-plus-HTTP
                        // fetch at once past the default ten rows, exactly the unbounded-fetch
                        // bug this same permit exists to prevent for the technicals call right
                        // below it. `?.join()` waits for it INSIDE the permit instead, so at
                        // most [MAX_PARALLEL_REQUESTS] symbols are ever fetching their chart at
                        // once - null (already fresh, already in flight, or backing off) joins
                        // nothing and falls through immediately.
                        // CALLED ON MAIN, NOT HERE ON IO (Part 9 audit finding). [loadChart]'s
                        // in-flight guard is check-then-launch: it reads `_chartLoading`
                        // synchronously, then sets it inside `fgScope.launch {}`. Every OTHER
                        // call site is already on Main, where `Dispatchers.Main.immediate` runs
                        // that launched body inline - closing the window instantly. This whole
                        // function runs under `withContext(Dispatchers.IO)`, so calling
                        // [loadChart] directly here dispatches the launch rather than running
                        // it inline, leaving a real gap where a concurrent Main-thread call for
                        // the same (symbol, D1) key could also pass the guard - two fetches for
                        // the same chart. Getting the Job back on Main closes that gap; the
                        // wait itself can still happen on any dispatcher.
                        // TECHNICALS FIRST, THEN THE CHART FROM THE SAME BODY (full test
                        // 2026-09-23, N-1). The intraday leg and the 1D chart are one Yahoo url;
                        // fetched in the other order they were two full downloads per row
                        // whenever the chart's clock lapsed, and the card's chart ran up to five
                        // minutes behind a plan computed from a 30-second-old copy of the same
                        // series. `adoptRecentD1` publishes the body the technicals just fetched;
                        // `loadChart` is left to fetch only when there was none (a failed leg).
                        // BACKED OFF WHEN NEITHER LEG ANSWERS (full test 2026-09-24, N-8).
                        val tech = if (dayTradingTechRetry.blocked(row.symbol)) null
                        else runCatching {
                            com.tj.portfolio.net.DayTradingTechnicals.fetch(row.symbol)
                        }.getOrNull().also { t ->
                            // A sweep cancelled by leaving the tab is not the symbol failing (R2-4).
                            if (coroutineContext[Job]?.isActive == false) Unit
                            else if (t == null || t.isEmpty) dayTradingTechRetry.failure(row.symbol)
                            else dayTradingTechRetry.success(row.symbol)
                        }
                        val chartJob = withContext(Dispatchers.Main) {
                            if (adoptRecentD1(row.symbol)) null
                            else loadChart(row.symbol, com.tj.portfolio.data.ChartRange.D1)
                        }
                        chartJob?.join()
                        row.symbol to tech
                    }
                }
            }.awaitAll().toMap()
        }
        var changed = false
        // RE-READ, NOT THE `head` SNAPSHOT - same reason enrichPass does this (Round 66 audit,
        // RES-7): a suspended fetch must not let whatever changed underneath it (an import, a
        // rebuild) be silently overwritten on write-back.
        val current = _research.value.section(name)
        // READ ONCE FOR THE WHOLE SWEEP, not per row - the same rule `buildDayTrading` follows
        // for `sessionWord`. Rows enriched in one pass must all describe the same moment, or two
        // cards a centimetre apart could disagree about how much of the session is left.
        val minutesLeft = com.tj.portfolio.net.MarketClock.minutesLeftInSession()
        val middayLull = com.tj.portfolio.net.MarketClock.inMiddayLull()
        val updated = current.map { row ->
            val tech = fetched[row.symbol] ?: return@map row
            if (tech.isEmpty) return@map row
            changed = true
            val withLevels = mergeDayTradingTech(row, tech, minutesLeft, middayLull,
                now = System.currentTimeMillis())
            // A ROW WITH NO APP-COMPUTED LIKELIHOOD HAS NOTHING FOR THIS TO BUILD ON (Round 72
            // fix, and a correction to this guard's own first draft - caught by code review
            // before shipping). A pick Claude added from scratch never runs through
            // [com.tj.portfolio.net.Research.buildDayTrading]/`toDayTradingRow`, so its
            // `dtLikelihood` stays exactly 0 forever, the same way its `score` stays 0 - the
            // rule [ResearchRow.conviction]'s own header states for a Claude-added ETF row.
            // `dtLikelihood <= 0` is what this actually needs to check, NOT `row.planByClaude`:
            // the first draft guarded on `planByClaude`, but [dropUnusableClaudeLevels] can flip
            // that flag back to false (a bad price-check clears Claude's levels) WITHOUT
            // resetting `dtLikelihood`, which would have reopened the exact bug this guard
            // exists to close for exactly that row - a small nonzero score built from nothing,
            // turning "CLAUDE 8/10" into a fabricated "SCORE 12" the moment `fromClaude =
            // r.score <= 0 && r.conviction > 0` in `ResearchCard` saw a nonzero score. A row the
            // app DID screen keeps updating live even if Claude later relabels its price plan -
            // whose entry/stop/target this shows is a separate question from how likely the app
            // itself thinks the stock is to rise, and [TradeLevelsGrid] already labels that half
            // on its own.
            if (row.dtLikelihood <= 0) return@map withLevels
            // Every tick, from the build-time halves (D-10) - see scoreDayTradingRow.
            scoreDayTradingRow(withLevels, effectiveTechnicals(row, tech))
        }
        // THE ONE-TIME SORT (Round 75). A completed sweep knows, for the first time, which rows
        // actually have a plan - so this is the one moment "put the actionable ones at the top"
        // can be answered honestly rather than guessed at build time (before any technicals
        // exist). ACTIONABLE FIRST (a real, current entry/stop/target - the app's own or
        // Claude's), RANKED BY SCORE WITHIN EACH GROUP - `sortedWith` is stable, so two rows
        // that tie on both keys keep the screener's own relative order. Every ordinary tick
        // after this one goes back to updating levels IN PLACE with no re-sort at all, exactly
        // as the header above still requires - this fires once per rebuild, not every 30 seconds.
        // ONLY IF THE LIST IT FETCHED FOR IS STILL THE LIST (D-4) - see [dayTradingRebuildGen].
        val sweptThisList = sweeping && sweepGen == dayTradingRebuildGen
        // CLAUDE'S ORDER IS KEPT (full test 2026-09-24, D-8): when the list was last written by
        // a Claude answer, its "best first" order is the ranking - re-sorting by the app's own
        // score dropped Claude-added names (scored 0) below "Load more", never shown or logged.
        val claudeOrdered = _research.value.let { it.dtExplained > it.generated }
        val finalRows = if (sweptThisList) {
            dayTradingSweepDone = true
            if (claudeOrdered) updated else sortDayTradingForActionability(updated)
        } else updated
        // ONLY A WHOLE-LIST TICK THAT GOT TODAY'S BARS (review 2026-09-24, R1-8): a detail
        // screen's one-symbol run, or a tick whose intraday requests all failed, refreshed no
        // list prices - and the prompt then called hours-old rows "live".
        if (changed && only == null && fetched.values.any { it != null && it.sessionDay.isNotBlank() })
            dayTradingLiveAt = System.currentTimeMillis()
        if (changed || sweeping) {
            _research.value = _research.value.withSection(name, finalRows)
            // A completed sweep re-sorted the list - worth writing now; an ordinary tick is not.
            cacheResearch(_research.value, throttleMs = if (sweeping) 0L else DAY_TRADING_PERSIST_MS)
            // THE ONE PLACE A RECOMMENDATION IS LOGGED (D-1): the rows this tick re-planned from
            // a live, market-open fetch - never a row whose fetch failed and kept an older plan.
            // TODAY'S INTRADAY BARS, not merely a non-empty reading (review 2026-09-24, R1-1):
            // the daily leg is memoised, so a row whose intraday request failed still read as
            // "not empty" - and on a failed first 09:30 tick its pre-market plan and price were
            // logged, and the one-row-per-day rule then locked the real plan out.
            captureDayTradingRecommendations(
                finalRows,
                fetched.filterValues {
                    it != null && it.sessionLive && it.sessionDay.isNotBlank() && it.lastPrice > 0.0
                }.keys
            )
        }
    }

    /**
     * Which rows of a freshly built set still have no price, highest-scoring first.
     *
     * Pure, so the selection can be checked without a network: the lists arrive already
     * sorted by score, so `take` keeps the ones nearest the top of the screen. ETFs are NOT
     * included - they are built by [loadEtfs] on a six-hour clock of their own and are not
     * part of what this rebuild just produced, and a fund Claude added that Yahoo cannot
     * resolve would otherwise be re-requested on every half-hourly stock rebuild forever.
     */
    internal fun pricelessRows(
        set: com.tj.portfolio.data.ResearchSet,
        cap: Int = MAX_PRICE_FILL
    ): List<String> = (set.trending + set.best)
        .filter { it.price <= 0.0 }
        .map { it.symbol }
        .filter { it.isNotBlank() }
        .distinct()
        .take(cap)

    /** Fire-and-forget wrapper for [fillPricesNow], for the callers that cannot suspend. */
    private fun fillResearchPrices(symbols: List<String>) {
        if (symbols.isEmpty()) return
        pendingPriceFill.addAll(symbols)
        // In the background the fill waits for `setForeground(true)` (L-3): `fgScope` is
        // cancelled, and fetching prices nobody can see is what that scope exists to prevent.
        if (!foreground) return
        fgScope.launch {
            fillPricesNow(symbols)
            pendingPriceFill.removeAll(symbols.toSet())
        }
    }

    /**
     * Quotes for rows that reached a list with no price, so every row on screen carries one.
     *
     * TWO CALLERS, AND THEY NEED DIFFERENT THINGS FROM IT (Round 66 audit, R2). The Claude
     * import path is not a coroutine and cannot wait, so it goes through the launcher above.
     * [loadResearch] calls this one directly and AWAITS it, which is what keeps it out of a
     * race with `enrichVisible`: that pass re-ranks a window of Best around an IO call and
     * writes the result back wholesale, so a fill landing mid-flight would be overwritten by
     * a list the enrich pass had already read. Awaiting orders the two.
     */
    private suspend fun fillPricesNow(symbols: List<String>) {
        // ONE BATCHED REQUEST, not one per symbol (Round 63 sweep). `MarketData.quotes`
        // takes the whole list and asks Yahoo once - the same endpoint the price poll has
        // used since Round 56, complete with its per-symbol fallback for anything the
        // batch cannot answer. This path was still doing it the old way: up to twenty
        // symbols x four providers, and a SQLite read for the Finnhub key inside every
        // one of them.
        val key = finnhubKey()
        val fetched = withContext(Dispatchers.IO) {
            runCatching { MarketData.quotes(symbols, key) }.getOrDefault(emptyList())
                .associateBy { it.symbol }
        }
        if (fetched.isEmpty()) return
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
        // `etfs` INCLUDED. Claude is explicitly asked to add funds the app's screener
        // universe cannot see, and those rows arrive with no price - so leaving them out
        // spent a real quote request per added fund and discarded the answer, then
        // re-spent it on the next import because the row still had no price.
        // ---- LEVERAGED AND INVERSE FUNDS ARE DROPPED HERE TOO (Round 66 audit, ETF-8).
        //
        // THE BUG THIS FIXES. `EtfScore.isLeveragedOrInverse` was applied on the SCREENER path
        // only, and the ETF list has a second entrance: Claude is explicitly asked to add
        // funds the app's screener universe cannot see. Ask it for the best ETFs and TQQQ is a
        // reasonable thing for a model to name - so a 3x fund could land on a list whose own
        // sources note reads "Leveraged and inverse funds are excluded", and then survive
        // every six-hourly rebuild because `carryEtfExplanations` carries it forward.
        //
        // IT HAS TO BE HERE AND NOT AT MERGE TIME. The matcher reads the fund's NAME, and a
        // row Claude added arrives with a symbol and a paragraph but no name - that is exactly
        // why it is in this function's list of rows to fill. So the test runs on the filled
        // copy, one line after the name exists. The prompt now says so as well, but a prompt
        // is a request and this is the guarantee.
        fun dropLeveraged(list: List<com.tj.portfolio.data.ResearchRow>) =
            list.filterNot {
                it.name.isNotBlank() &&
                    com.tj.portfolio.net.EtfScore.isLeveragedOrInverse(it.name, it.symbol)
            }
        // ---- AND THE ONE-FUND-PER-EXPOSURE RULE APPLIES HERE TOO (Round 66 audit, RES-3).
        //
        // THE BUG THIS FIXES. `EtfExposure.dedupe` ran on the screener path only, while the
        // ETF tab's blurb states the rule as a flat fact about the list: "where several funds
        // track the same thing only the best-scoring one takes a place - the rest are named on
        // its card". The app then hands Claude a gaps list that named AGG and BND - which are
        // the same exposure, "Bonds - US aggregate" - and asked for both by name, so the most
        // likely import in the world put two identical decisions on a list promising it would
        // not, with neither card naming the other.
        //
        // Same placement and same reason as `dropLeveraged`: the matcher reads the fund's
        // NAME, and a row Claude added has one only after the fill above.
        //
        // WHICH FUND SURVIVES A GROUP IS ALREADY RIGHT. The list is sorted score-then-
        // conviction before this runs, and `dedupe` keeps the first of each group, so a fund
        // the app actually measured beats an unscored one - and between two unscored ones, the
        // model's own conviction decides. A dropped fund is not lost: it is named on the
        // survivor's card, which is the whole contract.
        fun oneFundPerExposure(list: List<com.tj.portfolio.data.ResearchRow>) =
            com.tj.portfolio.net.EtfExposure
                .dedupe(list, name = { it.name }, symbol = { it.symbol })
                .map { (row, also) ->
                    if (also.isEmpty()) row
                    else row.copy(
                        // FIRST, NOT LAST (Round 66 audit, REG-4) - the same mistake E3 fixed
                        // on the screener path, repeated here the moment the rule gained a
                        // second call site. The card renders `reasons.take(6)` and a scored
                        // fund already carries six lines, so an appended line is the seventh
                        // and is never drawn: the imported fund would vanish from the list a
                        // second after the toast said it had been added, with nothing on the
                        // survivor's card saying where it went.
                        // ONE such line, naming every fund it stands for (full test 2026-09-23,
                        // S-10): the screener's survivor already said "Same exposure as IVV,
                        // SPLG ...", and prepending a second line for the imported fund pushed
                        // that one to line two and left the shown line naming only VOO.
                        reasons = run {
                            val prefix = "Same exposure as "
                            val earlier = row.reasons.filter { it.startsWith(prefix) }
                                .flatMap { line ->
                                    line.removePrefix(prefix).substringBefore(" - ")
                                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                }
                            val names = (earlier + also).distinct()
                            // Only a SCORED survivor "scored highest" (S-Q3): between two funds
                            // Claude added, nothing here scored either one.
                            val why = if (row.score > 0) " - this one scored highest of them"
                            else " - one shown for all of them"
                            listOf(prefix + names.joinToString(", ") + why) +
                                row.reasons.filterNot { it.startsWith(prefix) }
                        }
                    )
                }

        cacheResearch(
            s.copy(
                trending = fill(s.trending),
                best = fill(s.best),
                etfs = oneFundPerExposure(dropLeveraged(fill(s.etfs))),
                // A day-trading row Claude added arrives with a symbol and no levels. Round 69
                // no longer computes a plan here: a plan needs real intraday structure (see
                // [com.tj.portfolio.net.ResearchScore.tradePlan]), which a price fill does not
                // have, and the live technicals sweep covers added rows anyway - it enriches
                // whatever is in the visible window, not just rows the screener produced.
                dayTrading = dropUnusableClaudeLevels(fill(s.dayTrading))
            )
        )
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
                // Verified like the other four, now that it is in the file at all. The whole
                // point of this section is that it cannot be rebuilt from anywhere else, so
                // "the backup ran" must not be reported over a copy that silently lost it.
                val fileDt = got.optJSONArray("dayTradingLog")?.length() ?: -1
                val liveDt = db.dayTradingLog().size
                val liveWatch = db.watchlist().size
                val liveOv = db.overrides().size
                val liveImp = db.importCount()

                val problems = buildList {
                    if (fileTxns != liveTxns) add("transactions $fileTxns/$liveTxns")
                    if (fileWatch != liveWatch) add("watchlist $fileWatch/$liveWatch")
                    if (fileOv != liveOv) add("overrides $fileOv/$liveOv")
                    if (fileImp != liveImp) add("imports $fileImp/$liveImp")
                    if (fileDt != liveDt) add("day-trading log $fileDt/$liveDt")
                    if (expected?.optInt("transactions", -1) != fileTxns) add("manifest mismatch")
                }
                if (problems.isNotEmpty()) {
                    BackupOutcome(false, "Backup incomplete - " + problems.joinToString(", "))
                } else {
                    noteBackup(saved.display)
                    BackupOutcome(
                        true,
                        "Verified: $fileTxns transactions, $fileOv override(s), " +
                            "$liveWatch watchlist, $fileImp import record(s)" +
                            (if (liveDt > 0) ", $fileDt day-trading record(s)" else "") +
                            " -> ${saved.display}"
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
        viewModelScope.launch(Dispatchers.IO) { autosaveWrite.withLock {   // A-9
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
        } }
    }

    fun snapshotCount(): Int =
        com.tj.portfolio.util.Storage.appBackups(getApplication()).size

    /** Newest private snapshot, for the Settings restore button. */
    fun latestSnapshotJson(): String? =
        pickUndoSnapshot(com.tj.portfolio.util.Storage.appBackups(getApplication()))
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
        viewModelScope.launch(Dispatchers.IO) { autosaveWrite.withLock {
            val json = runCatching { db.exportJson() }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()

            val name = "portfolio-autobackup-" + com.tj.portfolio.util.Storage.stamp() + ".json"
            val saved = runCatching {
                com.tj.portfolio.util.Storage.saveToAppFolder(getApplication(), name, json)
            }.getOrNull()
            if (saved != null) db.set(Keys.AUTO_BACKUP_AT, now.toString())

            // ---- NEVER SHRINK THE ONLY UNINSTALL-PROOF COPY WITHOUT KEEPING THE BIGGER ONE
            // (full test 2026-09-23, A-1). After a storage wipe the ledger is empty but the
            // Downloads autosave still holds everything - and one trade entered or one screenshot
            // imported before restoring made the next autosave replace a 200-row file with a
            // 1-row one, with the private snapshots already gone. Before an overwrite that would
            // shrink it, the larger file is kept as AUTOSAVE_PREVIOUS_FILE - unless the copy
            // already kept there is larger still, which is the one worth keeping.
            runCatching {
                val app = getApplication<Application>()
                val oldJson = com.tj.portfolio.util.Storage.readOwnDownload(app, AUTOSAVE_FILE)
                val oldCount = backupTxnCount(oldJson)
                val newCount = backupTxnCount(json)
                if (oldJson != null && oldCount > newCount) {
                    val prevCount = backupTxnCount(
                        com.tj.portfolio.util.Storage.readOwnDownload(app, AUTOSAVE_PREVIOUS_FILE)
                    )
                    if (oldCount > prevCount) {
                        com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(
                            app, AUTOSAVE_PREVIOUS_FILE, oldJson
                        )
                    }
                }
            }
            val pub = runCatching {
                com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(
                    getApplication(), AUTOSAVE_FILE, json
                )
            }.getOrNull()
            if (pub != null) db.set(Keys.AUTOSAVE_AT, now.toString())
        } }
    }

    fun lastAutosave(): Long = db.get(Keys.AUTOSAVE_AT).toLongOrNull() ?: 0L

    /**
     * A PRIVATE SNAPSHOT OF THE LEDGER JUST BEFORE SOMETHING DESTROYS PART OF IT (full-tests
     * audit 2026-09-22, A-L9). Deleting a symbol, deleting every transaction and a Replace-all
     * restore all said "cannot be undone" - and the newest snapshot could be a day old, so a
     * mistap there lost everything entered since. Written synchronously, BEFORE the change, into
     * the same private folder the daily snapshots use, so Settings' "Restore the latest
     * automatic snapshot" is the undo. Best-effort: a failed write never blocks the action the
     * user confirmed. Nothing to save means nothing written.
     */
    private fun snapshotBefore(what: String) {
        runCatching {
            if (db.txnCount() == 0) return
            val json = db.exportJson()
            val dir = com.tj.portfolio.util.Storage.appBackupDir(getApplication())
            com.tj.portfolio.util.Storage.saveToAppFolder(
                getApplication(),
                snapshotFileName(what, com.tj.portfolio.util.Storage.stamp()) {
                    java.io.File(dir, it).exists()
                },
                json
            )
        }
    }

    /** Read the uninstall-proof copy back, for the recovery banner's one-tap restore. */
    fun readAutosave(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    // THE CURRENT AUTOSAVE, ALWAYS (diff review 2026-09-23, R-1). Preferring the
                    // larger of it and the kept "-previous" copy looked safe, but a deliberate
                    // shrink (a symbol deleted, a wipe and clean re-import) also leaves a larger,
                    // OLDER previous copy - and one-tap recovery then brought the deleted rows
                    // back and dropped everything entered since. The previous copy stays on disk
                    // for a deliberate restore through Settings' file picker, which is the only
                    // place a person can judge which of the two they mean.
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
            var preMax = 0L
            val r = withContext(Dispatchers.IO) {
                // A Replace throws the current ledger away wholesale - see [snapshotBefore].
                if (replace) snapshotBefore("replace")
                preMax = db.maxTxnId()
                runCatching { db.restoreJson(json, replace) }
                    .getOrElse { Db.RestoreResult(error = "Restore failed: ${it.message}") }
            }
            if (r.error == null) {
                _lastImport.value = withContext(Dispatchers.IO) {
                    // Restored rows are re-inserted with NEW ids, and may be a pre-fix device's
                    // screen-ordered imports - make them repair candidates again (A-2). ONLY
                    // THEM (full test 2026-09-24, A-2): a Replace leaves nothing else, so the
                    // mark moves past everything; a Merge adds just the id range it inserted,
                    // leaving every row already here - chronologically imported since the fix -
                    // exactly as it was. A merge that inserted nothing changes nothing.
                    val postMax = db.maxTxnId()
                    if (replace) {
                        db.set(Keys.REPLAY_REPAIR_BELOW_ID, (postMax + 1).toString())
                        db.set(Keys.REPLAY_REPAIR_RANGES, "")
                    } else if (postMax > preMax) {
                        replayRepairBelowId()   // make sure the base mark exists first
                        db.set(Keys.REPLAY_REPAIR_RANGES, formatRepairRanges(
                            replayRepairRanges() + listOf((preMax + 1)..postMax)))
                    }
                    db.lastImport()
                }
                recompute()
                refresh()
                // ---- AND TAKE A SAFETY COPY OF WHAT WAS JUST RESTORED (Round 66).
                //
                // A restore is the moment the ledger is both most valuable and least
                // protected: this device has a full set of transactions and no snapshot of
                // its own. `force = true` because the ordinary 24-hour gate reads
                // `AUTOSAVE_AT` / `AUTO_BACKUP_AT`, and before this round a restore imported
                // the SOURCE phone's copies of those - so a transfer at 9am, restoring a file
                // written at 8am, left the new phone with no backup for the rest of the day.
                // Those keys are excluded from backups now, but forcing here is what makes
                // the guarantee unconditional rather than a consequence of another fix.
                //
                // NOT ON A SHORT READ, THOUGH. `r.warning` means the file's own manifest
                // disagreed with what parsed out of it, so this ledger is knowingly less
                // than the backup described. Copying it over `portfolio-autosave.json` -
                // the one copy that survives an uninstall - would overwrite a good backup
                // with a worse one at exactly the moment we have evidence something is
                // wrong. `restoreJson` already refuses this outright on a Replace; this
                // covers the Merge case, where the result is additive but still incomplete.
                if (r.warning == null) autoBackupIfDue(force = true)
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
        snapshotBefore("wipe")
        db.deleteAllTxns()
        // THE OVERRIDES GO WITH THEM (full test 2026-09-23, A-7). They pin a symbol's shares
        // and basis against its history; with no history they did nothing - until the usual
        // reason for a wipe, re-importing everything cleanly, brought them silently back into
        // force against the new rows. They are in the snapshot taken just above.
        db.clearAllOverrides()
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

    /** Same field set [Db.exportJson] writes for a transaction, minus [Txn.id] - this row isn't inserted yet. */
    private fun pendingImportToJson(r: ExtractResult): JSONObject {
        val arr = JSONArray()
        r.transactions.forEach { t ->
            arr.put(JSONObject().apply {
                put("type", t.type)
                if (t.symbol.isNullOrBlank()) put("symbol", JSONObject.NULL) else put("symbol", t.symbol)
                put("quantity", t.quantity); put("price", t.price); put("amount", t.amount)
                put("fees", t.fees); put("date", t.date); put("note", t.note ?: "")
                put("source", t.source)
            })
        }
        return JSONObject().apply {
            put("transactions", arr)
            put("notes", r.notes)
            put("error", r.error ?: JSONObject.NULL)
            put("raw", r.raw)
        }
    }

    private fun pendingImportFromJson(o: JSONObject): ExtractResult {
        val list = ArrayList<Txn>()
        val arr = o.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            list.add(
                Txn(
                    type = t.optString("type"),
                    symbol = t.optString("symbol").takeIf { !t.isNull("symbol") && it.isNotBlank() },
                    quantity = t.optDouble("quantity", 0.0),
                    price = t.optDouble("price", 0.0),
                    amount = t.optDouble("amount", 0.0),
                    fees = t.optDouble("fees", 0.0),
                    date = t.optLong("date", 0L),
                    note = t.optString("note").takeIf { it.isNotBlank() },
                    source = t.optString("source", "SCREENSHOT")
                )
            )
        }
        return ExtractResult(
            transactions = list,
            notes = o.optString("notes"),
            error = if (o.isNull("error")) null else o.optString("error"),
            raw = o.optString("raw")
        )
    }
}
