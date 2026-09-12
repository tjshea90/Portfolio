package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.FeedItem
import com.tj.portfolio.data.Trending
import com.tj.portfolio.util.Fmt

private const val F_ALL = "All"
private const val F_MINE = "My stocks"
private const val F_INSIDER = "Insider"
private const val F_WSB = "WallStreetBets"

/**
 * The live feed: headlines for everything followed, SEC Form 4 insider filings for the
 * holdings, and what r/wallstreetbets is talking about right now.
 */
@Composable
fun FeedScreen(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    val items by vm.feed.collectAsState()
    val trending by vm.trending.collectAsState()
    val loading by vm.feedLoading.collectAsState()
    val at by vm.feedAt.collectAsState()
    val filings by vm.insiderFilings.collectAsState()
    val filingsLoading by vm.insiderLoading.collectAsState()
    var filter by remember { mutableStateOf(F_ALL) }
    var scope by remember { mutableStateOf(InsiderScope.OPEN_MARKET) }

    // Populate on first visit, and again whenever the tab is re-opened with nothing in it -
    // FeedScreen leaves composition on a tab switch, so this re-runs on the way back in.
    //
    // `at` MUST NOT be a key here. refreshFeed() stamps `at` even when it came back with
    // nothing, so keying on it meant: empty result -> `at` changes -> effect re-fires ->
    // empty result... a continuous refetch of every symbol's news, the SEC feed and both
    // social APIs for as long as the tab stayed open with no results - which is exactly
    // what happens offline, or on a watchlist-only portfolio with no headlines.
    LaunchedEffect(state.rows.size) {
        if (state.rows.isNotEmpty() && items.isEmpty() && !loading) vm.refreshFeed()
    }

    // The Insider tab has its own store and its own half-hour cadence, so the effect above
    // cannot fill it: a launch that restored cached headlines never refreshes at all, and
    // the tab would sit empty with nothing to say why. Opening it is the signal.
    LaunchedEffect(filter, state.rows.size) {
        if (filter == F_INSIDER) vm.refreshInsidersIfEmpty()
    }

    val owned = remember(state.rows) {
        state.rows.filter { !it.watchOnly }.map { it.symbol }.toSet()
    }
    // The Insider chip is no longer a filter over `items` - see [PortfolioViewModel]'s note
    // on `_insiderFilings` for why that could only ever show the single newest filing.
    // DEDUPED BY `id`, THE SAME EXPRESSION THIS LIST IS KEYED BY BELOW.
    //
    // A LazyColumn given the same key twice throws and takes the whole app down - this app
    // has been killed that way before, which is why util/CrashLog.kt exists at all. The news
    // list on a stock's page already guards itself this way; this one relied on the upstream
    // merge, which de-duplicates by `News.dedupeKey` (a NORMALISED title) rather than by
    // `FeedItem.id` (kind + symbol + published + the first 80 raw characters of the title).
    // Those are different questions, and they disagree on punctuation-heavy headlines: 80 raw
    // characters of "Q2 2026: U.S. Retailer's E.P.S. ..." carry far fewer than 70 alphanumeric
    // ones, so two stories can normalise differently - surviving the merge - while sharing an
    // id, and crash the app on arrival. Dedupe and key must be one expression.
    val shown = remember(items, filter) {
        when (filter) {
            F_MINE -> items.filter { it.owned }
            else -> items
        }.distinctBy { it.id }
    }
    val shownFilings = remember(filings, scope) {
        filings.filter { scope.accepts(it) }.sortedByDescending { it.filedAt }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Live feed", style = MaterialTheme.typography.titleLarge)
                // Says what is actually on screen. "Pull to load" beside a full screen of
                // saved headlines was simply untrue, and with the cache in place that is the
                // normal state on every launch.
                Text(
                    when {
                        loading && items.isEmpty() -> "Loading..."
                        at > 0 -> "Updated ${Fmt.relative(at)}"
                        items.isNotEmpty() -> "Showing saved headlines"
                        else -> "Pull to load"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            BigIconButton(Icons.Filled.Refresh, "Refresh feed") { vm.refreshFeed(manual = true) }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(F_ALL, F_MINE, F_INSIDER, F_WSB).forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent.copy(alpha = 0.18f),
                        selectedLabelColor = Accent
                    )
                )
            }
        }

        // The Insider tab has its own slow pass, so it gets its own progress bar - otherwise
        // it sat with no indication of anything happening while EDGAR was being read.
        if (loading || (filter == F_INSIDER && filingsLoading)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Refreshable(refreshing = state.pulling(PULL_FEED), onRefresh = { vm.refreshFeed(manual = true) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {

                if (filter == F_WSB) {
                    item {
                        Text(
                            "Tickers being talked about most on r/wallstreetbets in the last " +
                                "24 hours. Attention is not analysis - treat it as a crowd " +
                                "thermometer, not a signal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(trending, key = { it.symbol }) { t ->
                        TrendingRow(t, owned.contains(t.symbol)) { onOpen(t.symbol) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    if (trending.isEmpty()) {
                        item { Empty("No trending data right now.") }
                    } else {
                        // WHERE THE LIST CAME FROM, and why a column may be missing.
                        //
                        // This board is merged from two free aggregators, and only one of
                        // them - Tradestie - publishes a sentiment score. Tradestie has been
                        // serving an expired TLS certificate since January 2026, so the app
                        // has quietly been falling back to ApeWisdom alone ever since and the
                        // Bullish/Bearish column simply stopped appearing. Nothing was broken
                        // and nothing said anything, which is the worst combination. If they
                        // renew the certificate this fixes itself and the note disappears.
                        item {
                            val sources = trending.map { it.source }.filter { it.isNotBlank() }
                                .distinct().sorted()
                            Text(
                                buildString {
                                    append("Counted by ")
                                    append(if (sources.isEmpty()) "the free WSB aggregators"
                                    else sources.joinToString(" and "))
                                    append(". ")
                                    if (trending.none { it.sentiment.isNotBlank() }) {
                                        append("Bullish / bearish scores come from Tradestie, ")
                                        append("which the app cannot reach at the moment - the ")
                                        append("rankings and mention counts below are unaffected.")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else if (filter == F_INSIDER) {
                    item {
                        Column {
                            ScopeChips(scope) { scope = it }
                            val count = insiderSummary(shownFilings)
                            Text(
                                buildString {
                                    append("SEC Form 4 filings on everything you hold or ")
                                    append("watch, last ")
                                    append(com.tj.portfolio.net.Insider.WINDOW_DAYS)
                                    append(" days, newest first. ")
                                    append(scope.blurb)
                                    if (count.isNotBlank()) append("\n\n").append(count).append(".")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    items(shownFilings, key = { it.accession }) { f ->
                        InsiderRow(f, owned = owned.contains(f.symbol)) {
                            if (f.url.isNotBlank()) onOpenUrl(f.url, f.headline())
                            else onOpen(f.symbol)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    if (shownFilings.isEmpty()) {
                        item {
                            Empty(
                                when {
                                    filingsLoading -> "Reading SEC filings..."
                                    // Deliberately ambiguous, because the app genuinely
                                    // cannot tell these apart: an unreachable EDGAR and a
                                    // month with no filings both arrive as an empty list.
                                    // Claiming the first would be a guess presented as fact.
                                    filings.isEmpty() ->
                                        "Nothing to show. Either nobody has filed a Form 4 on " +
                                            "your stocks in the last " +
                                            "${com.tj.portfolio.net.Insider.WINDOW_DAYS} days, " +
                                            "or the SEC feed could not be reached - pull down " +
                                            "to try again."
                                    // The honest answer, and the useful one. An unplanned
                                    // insider purchase is genuinely rare, so an empty
                                    // Open-market view is usually a real finding rather
                                    // than a failure - but only if it says so.
                                    scope == InsiderScope.OPEN_MARKET ->
                                        "No unplanned open-market buying or selling in the " +
                                            "last ${com.tj.portfolio.net.Insider.WINDOW_DAYS} " +
                                            "days. There ${if (filings.size == 1) "is" else "are"} " +
                                            "${filings.size} other filing" +
                                            "${if (filings.size == 1) "" else "s"} - tap " +
                                            "All trades or Everything to see them."
                                    else ->
                                        "No purchases or sales in the last " +
                                            "${com.tj.portfolio.net.Insider.WINDOW_DAYS} days. " +
                                            "Tap Everything to see grants and option exercises."
                                }
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            when (filter) {
                                F_MINE -> "Only headlines about stocks you actually own, " +
                                    "including market news that names one of them."
                                else -> "Market-wide headlines plus news for everything you " +
                                    "hold or watch. Tap My stocks to narrow it to your holdings."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    items(shown, key = { it.id }) { f ->
                        FeedRow(f) {
                            // a market headline with no link and no ticker has nowhere to go
                            if (f.url.isNotBlank()) onOpenUrl(f.url, f.title)
                            else if (f.symbol.isNotBlank()) onOpen(f.symbol)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    if (shown.isEmpty()) {
                        item {
                            Empty(
                                if (loading) "Gathering headlines and filings..."
                                else "Nothing here yet. Pull down to refresh."
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp)
    )
}

@Composable
private fun FeedRow(f: FeedItem, onClick: () -> Unit) {
    val isInsider = f.kind == FeedItem.INSIDER
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Market headlines carry no ticker unless one of the holdings was named in the
            // title, so they get a MARKET badge rather than an empty one.
            if (f.symbol.isNotBlank()) Tag(f.symbol, if (f.owned) greenText else accentText)
            else Tag("MARKET", MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.kind == FeedItem.MARKET && f.symbol.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Tag("MARKET", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isInsider) {
                Spacer(Modifier.width(6.dp))
                Tag("INSIDER", Amber)
            }
            if (f.owned) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "you own this",
                    style = MaterialTheme.typography.labelSmall,
                    color = greenText
                )
            }
            Spacer(Modifier.weight(1f))
            if (f.published > 0) {
                Text(
                    Fmt.relative(f.published),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            f.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        // An insider row carries a second line worth reading - who traded, at what price,
        // and what they have left - where a headline carries only its publisher.
        val second = listOf(f.detail, f.source).filter { it.isNotBlank() }.joinToString(" · ")
        if (second.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                second,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrendingRow(t: Trending, owned: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#${t.rank}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // widthIn(min), NOT width - same clipping trap as the numbered actions on the
            // Advice tab. "#50" at a 2.0 font scale is wider than 34dp and was being cut off
            // with nothing on screen to hint at it.
            modifier = Modifier.widthIn(min = 34.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.symbol, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (owned) {
                    Spacer(Modifier.width(6.dp))
                    Tag("HELD", Green)
                }
            }
            val activityText = buildString {
                if (t.activity > 0) append(t.activity).append(" mentions")
                if (t.mentionDelta != 0) {
                    if (isNotEmpty()) append("  ")
                    append("(")
                    append(if (t.mentionDelta > 0) "+" else "")
                    append(t.mentionDelta).append(" vs 24h ago)")
                }
            }
            // an empty Text still reserves a line of leading, leaving a gap under the ticker
            if (activityText.isNotBlank()) {
                Text(
                    activityText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (t.sentiment.isNotBlank()) {
                Text(
                    t.sentiment,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (t.sentiment.equals("Bullish", true)) greenText
                    else if (t.sentiment.equals("Bearish", true)) redText
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (t.rankDelta != 0) {
                Text(
                    if (t.rankDelta > 0) "up ${t.rankDelta} places"
                    else "down ${-t.rankDelta} places",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (t.rankDelta > 0) greenText else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
