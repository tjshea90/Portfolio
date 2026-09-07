package com.tj.portfolio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.tj.portfolio.domain.PortfolioTotals
import com.tj.portfolio.util.Fmt

@Composable
fun PortfolioScreen(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit,
    onOpenNews: (String) -> Unit,
    onSearch: () -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    val rows = state.rows.filter { !it.watchOnly }
    val dataMissing by vm.dataMissing.collectAsState()
    val recoverable by vm.recoverableBackup.collectAsState()
    var restoring by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("My Portfolio", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            BigIconButton(Icons.Filled.Search, "Search symbols") { onSearch() }
            BigIconButton(Icons.Filled.Refresh, "Refresh prices") { vm.refresh(manual = true) }
            Box {
                BigIconButton(Icons.Filled.Menu, "Sort") { sortMenu = true }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf(
                        SORT_VALUE to "Market value",
                        SORT_DAY to "Day % change",
                        SORT_TOTAL to "Total % gain",
                        SORT_PRICE to "Price",
                        SORT_SYMBOL to "Symbol"
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(if (vm.sortMode == mode) "$label  *" else label) },
                            onClick = { vm.setSort(mode); sortMenu = false }
                        )
                    }
                }
            }
        }

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        Refreshable(refreshing = state.pulling(PULL_PRICES), onRefresh = { vm.refresh(manual = true) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SummaryHeader(state.totals, state.lastRefresh, state.error) }

            item {
                Text(
                    "YOUR HOLDINGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            items(rows, key = { it.symbol }) { row ->
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
                    // An empty Portfolio tab means one of two completely different things,
                    // and the app used to show the friendly one for both. Telling someone
                    // whose data has just vanished "No holdings yet" is the worst possible
                    // answer, so when the ledger has been non-empty before, say so plainly
                    // and put recovery one tap away.
                    if (dataMissing) RecoveryCard(vm, restoring) { restoring = it }
                    // A first launch that finds someone's portfolio already in Downloads -
                    // the shape of an uninstall and reinstall. Offering it beats showing
                    // "No holdings yet" over a backup they have no reason to know is there.
                    else if (recoverable) FoundBackupCard(vm, restoring) { restoring = it }
                    else Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No holdings yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Import Ally screenshots from the Activity tab, or add a transaction by hand. " +
                                "Holdings appear here automatically as buys and sells are recorded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

@Composable
private fun SummaryHeader(t: PortfolioTotals?, lastRefresh: Long, error: String?) {
    // This header is item 0 of a LazyColumn, so scrolling it off screen DISPOSES it. With a
    // plain remember the expanded detail card silently collapsed itself every time you
    // scrolled down to your holdings and back. rememberSaveable is retained by the lazy
    // list's own saveable state holder, so the panel stays open until you close it.
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val day = t?.dayGain ?: 0.0
    val total = t?.totalGain ?: 0.0

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {

        Text(
            "Everything you have is worth",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            Fmt.usd(t?.totalEquity ?: 0.0),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---- the two numbers that answer "did I make money?"
        //
        // Both of these deliberately differ from what a brokerage app shows, for good
        // reasons documented below - but a reconciliation against TJ's live Ally account
        // showed the headline "Today" out by $16.54 with NOTHING on this card explaining
        // it. The per-row asterisk covered the rows; the summary said nothing. A figure the
        // user cannot reconcile against their broker reads as a figure that is wrong, so
        // each line now carries the broker's number and the one-sentence reason, and only
        // when the two actually differ.
        val broker = t?.brokerDayGain ?: 0.0
        val dayDiffers = t != null && kotlin.math.abs(broker - day) > 0.005
        BigLine(
            "Today",
            Fmt.usdSigned(day),
            Fmt.pctSigned(t?.dayGainPct ?: 0.0),
            signColor(day),
            note = if (dayDiffers) {
                val n = t.boughtTodayCount
                "Your broker will show ${Fmt.usdSigned(broker)}. It measures " +
                    (if (n == 1) "the holding you bought" else "the $n holdings you bought") +
                    " today from yesterday's close; this counts them from the price you " +
                    "actually paid."
            } else null
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        val openOnly = t?.unrealized ?: 0.0
        val totalDiffers = t != null && kotlin.math.abs(openOnly - total) > 0.005
        BigLine(
            "Since you started",
            Fmt.usdSigned(total),
            Fmt.pctSigned(t?.totalGainPct ?: 0.0),
            signColor(total),
            note = if (totalDiffers)
                "Your broker's Total G/L will show ${Fmt.usdSigned(openOnly)} - that is the " +
                    "gain on stocks you still own. This adds the profit you already banked."
            else null
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(10.dp))

        // plain-language breakdown of where the money sits
        PlainLine("Money you put in", Fmt.usd(t?.netDeposits ?: 0.0))
        PlainLine("Your stocks are worth", Fmt.usd(t?.marketValue ?: 0.0))
        PlainLine("Cash not invested", Fmt.usd(t?.cash ?: 0.0))

        Spacer(Modifier.height(6.dp))
        Text(
            if (showDetails) "Hide the detailed numbers" else "Show the detailed numbers",
            style = MaterialTheme.typography.bodyMedium,
            color = Accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                // hand-rolled tappable: Compose sizes it to the text unless told otherwise,
                // and 13sp of text plus 12dp of padding is nowhere near 48dp
                .minTapTarget()
                .clickable { showDetails = !showDetails }
                .padding(vertical = 6.dp)
                .wrapContentHeight()
        )

        if (showDetails) {
            StatCard {
                KeyValue("What your shares cost you", Fmt.usd(t?.costBasis ?: 0.0))
                KeyValue(
                    "Gain on stocks you still own",
                    "${Fmt.usdSigned(t?.unrealized ?: 0.0)}  (${Fmt.pctSigned(t?.unrealizedPct ?: 0.0)})",
                    signColor(t?.unrealized ?: 0.0)
                )
                KeyValue(
                    "Profit already banked from sales",
                    Fmt.usdSigned(t?.realized ?: 0.0),
                    signColor(t?.realized ?: 0.0)
                )
                if ((t?.dividends ?: 0.0) > 0.0) {
                    KeyValue("Dividends and interest", Fmt.usd(t!!.dividends), Green)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "\"Since you started\" adds the profit you already banked to the gain on " +
                        "what you still hold. A broker's \"Total G/L\" usually shows only the " +
                        "second line, which is why the two differ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Red, style = MaterialTheme.typography.bodyMedium)
        }
        if (lastRefresh > 0) {
            Text(
                "Prices updated ${Fmt.relative(lastRefresh)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** A headline gain/loss line: label on the left, dollars and percent on the right. */
@Composable
private fun BigLine(
    label: String,
    money: String,
    pct: String,
    color: androidx.compose.ui.graphics.Color,
    note: String? = null
) {
  Column {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(money, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.width(10.dp))
        Text(
            pct,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
    // The reconciliation line. Only rendered when the two figures actually differ, so on an
    // ordinary day with no same-day buys the card stays exactly as clean as it was.
    if (note != null) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
  }
}

@Composable
private fun PlainLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Shown on a first launch that finds a portfolio already sitting in Downloads.
 *
 * The uninstall-and-reinstall case. There is no high-water mark to compare against - the
 * database is brand new - so the missing-data banner cannot fire, and without this the app
 * would show "No holdings yet" over a complete backup and, until this round, delete it.
 */
@Composable
private fun FoundBackupCard(
    vm: PortfolioViewModel,
    restoring: Boolean,
    setRestoring: (Boolean) -> Unit
) {
    val savedAt = remember { vm.lastAutosave() }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        StatCard {
            Text(
                "There is a portfolio backup on this phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "This app has no transactions yet, but the automatic copy it keeps in " +
                    "Downloads is still here - which is what you would expect after " +
                    "reinstalling the app or clearing its storage. Restoring brings back " +
                    "every transaction, override and watchlist symbol.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (savedAt > 0) {
                Text(
                    "Written ${Fmt.relative(savedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    setRestoring(true)
                    vm.readAutosave { json ->
                        if (json == null) {
                            setRestoring(false)
                            vm.toast("Couldn't read the backup file")
                        } else {
                            // merge, so it can only ever add
                            vm.restoreAsync(json, replace = false) { r ->
                                setRestoring(false)
                                vm.dismissRecoverableBackup()
                                vm.toast(r.summary())
                            }
                        }
                    }
                },
                enabled = !restoring,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (restoring) "Restoring..." else "Restore that backup") }
            OutlinedButton(
                onClick = { vm.dismissRecoverableBackup() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Start fresh instead") }
        }
    }
}

/**
 * Shown in place of the empty state when the ledger has held transactions before and now
 * reads as empty. Recovery is one tap: the app keeps a copy of everything in Downloads that
 * survives even uninstalling it, and that is what this restores from.
 */
@Composable
private fun RecoveryCard(
    vm: PortfolioViewModel,
    restoring: Boolean,
    setRestoring: (Boolean) -> Unit
) {
    val known = remember { vm.lastKnownTxnCount() }
    val autosaveAt = remember { vm.lastAutosave() }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        StatCard {
            Text(
                "Your transactions are missing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Red
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "This app had $known transaction${if (known == 1) "" else "s"} on file and now " +
                    "finds none. Nothing has been overwritten - your backups are untouched.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    setRestoring(true)
                    vm.readAutosave { json ->
                        if (json == null) {
                            setRestoring(false)
                            vm.toast("No automatic copy found in Downloads")
                        } else {
                            // MERGE, never replace: merging can only add what is missing,
                            // so it is safe even if some rows did survive.
                            vm.restoreAsync(json, replace = false) { r ->
                                setRestoring(false)
                                vm.toast(r.summary())
                            }
                        }
                    }
                },
                enabled = !restoring,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (restoring) "Restoring..."
                    else "Restore from the automatic copy"
                )
            }
            if (autosaveAt > 0) {
                Text(
                    "Automatic copy last written ${Fmt.relative(autosaveAt)} " +
                        "(Downloads/Portfolio/portfolio-autosave.json)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You can also restore any portfolio-backup-*.json from Downloads, or the " +
                    "latest daily snapshot, in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { vm.dismissDataMissing() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("This was intentional - hide this") }
        }
    }
}
