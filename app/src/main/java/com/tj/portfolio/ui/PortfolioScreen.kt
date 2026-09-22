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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.PlMode
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
    var pending by rememberSaveable(stateSaver = PendingAction.Saver) { mutableStateOf<PendingAction?>(null) }
    val rows = state.rows.filter { !it.watchOnly }
    val dataMissing by vm.dataMissing.collectAsState()
    val recoverable by vm.recoverableBackup.collectAsState()
    var restoring by remember { mutableStateOf(false) }

    // ---- BUY/HOLD/SELL ON EVERY ROW, NOT JUST THE ONE OPENED IN DETAIL.
    //
    // TJ: *"include them in the main portfolio tab next to each stock."* `loadFundamentals`
    // used to have exactly one caller - `DetailScreen`, one symbol at a time - so fundamentals
    // (and the consensus a recommendation is scored from) simply did not exist yet for a
    // holding TJ had never opened. `loadPortfolioFundamentals` asks for the whole board,
    // staggered rather than all at once - see its KDoc in PortfolioViewModel.kt.
    //
    // KEYED ON THE SYMBOL LIST, NOT ON `rows` ITSELF. `rows` carries the live quote and
    // recomposes on every price tick (four times a minute); a `List<String>` of symbols only
    // actually changes when a position is added or removed, and two structurally-equal lists
    // compare equal as a Compose key even though `.map` allocates a new one on every
    // recomposition - so this does not re-fire on a price tick, only on a real change to what
    // is held. Same reasoning DetailScreen's own effects already document for `rememberUpdatedState`.
    val fundMap by vm.fundamentals.collectAsState()
    val recommendations by vm.recommendations.collectAsState()
    val heldSymbols = rows.map { it.symbol }

    LaunchedEffect(heldSymbols) {
        if (heldSymbols.isNotEmpty()) vm.loadPortfolioFundamentals(heldSymbols)
    }

    // Fires again once fundamentals for a symbol actually arrive (fundMap's CONTENT changes,
    // which is a separate, much less frequent StateFlow than the quote poll behind `rows`).
    // `loadRecommendation` is a local read-and-compute with no network cost of its own and a
    // once-a-trading-day cache, so calling it again for a row that already has today's answer
    // is a guard check, not a repeated fetch.
    // ...and when a row first gets a PRICE (U-L4): fundamentals that land before the first quote
    // were scored at price 0, refused, and never retried. The priced set changes only when a
    // symbol gains or loses a price - not on every tick.
    val pricedSymbols = rows.filter { it.price > 0.0 }.map { it.symbol }
    LaunchedEffect(heldSymbols, fundMap, pricedSymbols) {
        rows.forEach { r ->
            if (fundMap[r.symbol] != null && r.price > 0.0) vm.loadRecommendation(r.symbol, r.price)
        }
    }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // WEIGHTED, so the three buttons are measured first at their full 52dp.
            //
            // `Row` measures unweighted children in order against whatever width is left, and
            // a weighted `Spacer` after the title does not protect what follows it - so at a
            // large font scale "My Portfolio" ate the row and the LAST button, Sort, was
            // squeezed to about 37dp, under this app's own documented 48dp minimum. A title
            // that ellipsises is a small loss; a control too small to hit is a real one.
            Text(
                "My Portfolio",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
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
            item {
                SummaryHeader(
                    state.totals, state.lastRefresh, state.error,
                    plMode = state.plMode,
                    onTogglePl = { vm.togglePlMode() }
                )
            }

            item {
                Text(
                    "YOUR HOLDINGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            items(rows, key = { it.symbol }, contentType = { "stock" }) { row ->
                // PER-ROW DERIVED READ, NOT A DIRECT MAP INDEX. Reading `recommendations[symbol]`
                // straight from the captured map made every visible row recompose whenever ANY
                // symbol's recommendation resolved, because the map itself is a new instance on
                // every update. `derivedStateOf` still re-runs on every map change, but only
                // actually invalidates a row whose OWN entry compares unequal (Recommendation is
                // a data class) - so one symbol resolving no longer recomposes the whole list.
                val recommendation by remember(row.symbol) {
                    derivedStateOf { recommendations[row.symbol] }
                }
                StockRowItem(
                    row,
                    onClick = { onOpen(row.symbol) },
                    onNews = { onOpenNews(row.symbol) },
                    onAction = { a -> pending = PendingAction(row.symbol, a) },
                    plMode = state.plMode,
                    recommendation = recommendation
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
                    // ---- A "LOADING" BRANCH WAS TRIED HERE AND REMOVED AGAIN.
                    //
                    // The idea was that on the first frames of a launch neither `dataMissing`
                    // nor `recoverable` has resolved, so both fall through to the friendly
                    // copy. Measured, that is not what happens: `loading` defaults to false
                    // and `recompute()` runs synchronously in the ViewModel's `init`, before
                    // the first frame, so this screen never renders in the state the branch
                    // was written for.
                    //
                    // What it DID do was real and bad. `loading` is true during every quote
                    // pass, and a user with no holdings but a non-empty watchlist runs one on
                    // `init` and on every automatic tick - so the onboarding instructions,
                    // which are exactly what that user needs, flickered away to a spinner
                    // caption several times a minute. A fix that misses its case and breaks a
                    // real one is a net loss; the 2dp progress bar at the top of the screen
                    // already says a refresh is running.
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
internal fun SummaryHeader(
    t: PortfolioTotals?,
    lastRefresh: Long,
    error: String?,
    plMode: PlMode,
    onTogglePl: () -> Unit
) {
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
            plLead(plMode, day, t?.dayGainPct ?: 0.0),
            plSub(plMode, day, t?.dayGainPct ?: 0.0),
            signColor(day),
            onToggle = onTogglePl,
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
            plLead(plMode, total, t?.totalGainPct ?: 0.0),
            plSub(plMode, total, t?.totalGainPct ?: 0.0),
            signColor(total),
            onToggle = onTogglePl,
            note = if (totalDiffers)
                "Your broker's Total G/L will show ${Fmt.usdSigned(openOnly)} - that is the " +
                    "gain on stocks you still own. This adds the profit you already banked."
            else null
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // WHAT THE TWO FIGURES ARE, SPELLED OUT. The same rule the price block was corrected
        // for in Round 51: two numbers side by side with nothing saying which is which is a
        // guess, and once they can swap places it is a worse guess than before.
        Text(
            // SHORT ON PURPOSE. It has to say two things - which order is showing, and that
            // a tap changes it - and it sits in a card that is already dense. A sentence
            // wraps to three lines at a 2.0 font scale to say what this says in one.
            if (plMode == PlMode.DOLLAR) "$ first  -  tap a line for %"
            else "% first  -  tap a line for $",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(10.dp))

        // plain-language breakdown of where the money sits
        PlainLine("Money you put in", Fmt.usd(t?.netDeposits ?: 0.0))
        PlainLine("Your stocks are worth", Fmt.usd(t?.marketValue ?: 0.0))
        PlainLine("Cash not invested", Fmt.usd(t?.cash ?: 0.0))

        Spacer(Modifier.height(6.dp))
        Text(
            if (showDetails) "Hide the detailed numbers" else "Show the detailed numbers",
            style = MaterialTheme.typography.bodyMedium,
            color = accentText,
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
                    plInline(plMode, t?.unrealized ?: 0.0, t?.unrealizedPct ?: 0.0),
                    signColor(t?.unrealized ?: 0.0)
                )
                KeyValue(
                    "Profit already banked from sales",
                    Fmt.usdSigned(t?.realized ?: 0.0),
                    signColor(t?.realized ?: 0.0)
                )
                if ((t?.dividends ?: 0.0) > 0.0) {
                    // `greenText`, not `Green` (Round 66 audit, PUI-5). This was the one coloured
                    // figure on the card not going through `signColor`, and the brand green
                    // measures 2.02:1 on the light StatCard - see the rule at [greenText].
                    KeyValue("Dividends and interest", Fmt.usd(t!!.dividends), greenText)
                }
                Spacer(Modifier.height(6.dp))
                // ---- THE NOTE HAS TO NAME EVERY COMPONENT (Round 66 audit, PUI-3).
                //
                // It used to say "Since you started" was the banked profit plus the gain on
                // what you still hold - and then the card printed all three numbers, so the
                // arithmetic could be checked, and it did not add up. `totalGain` is
                // `equity - netDeposits` (Ledger.totals), and `equity` includes cash, which
                // carries dividends, interest and fees; `netDeposits` counts only deposits
                // and withdrawals. Deposit $10,000, buy $9,000 of stock now worth $9,500,
                // take a $50 dividend: the card says +$550, the note's two lines say $500 and
                // $0, and the missing $50 is sitting on its own row two lines above.
                //
                // A figure the user cannot reconcile reads as a figure that is wrong, which
                // is the whole reason this card shows its working.
                Text(
                    "\"Since you started\" is everything that happened to the money you put " +
                        "in: the gain on what you still hold, plus the profit you already " +
                        "banked, plus dividends and interest, less fees. A broker's " +
                        "\"Total G/L\" usually shows only the first of those, which is why " +
                        "the two differ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = redText, style = MaterialTheme.typography.bodyMedium)
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
    /**
     * The big figure, and the small one under it - NOT "money" and "pct".
     *
     * They were named that until Round 61, and then the toggle made the names lie: in percent
     * mode the parameter called `money` holds a percentage. A name that contradicts its value
     * is how two of this project's bugs shipped, so the names describe the ROLE, which is the
     * thing that does not change.
     */
    lead: String,
    sub: String,
    color: androidx.compose.ui.graphics.Color,
    note: String? = null,
    /**
     * Tap anywhere on the line to swap which figure is the big one (Round 61).
     *
     * THE WHOLE ROW IS THE TARGET, not the number itself. A P/L figure is a few characters
     * wide and this project has a standing 48dp rule with two rounds of scar tissue behind
     * it; a full-width row is the only version that is comfortably hittable, and it is also
     * what other stock apps do. Null leaves the line inert, which is what the note-only
     * variants want.
     */
    onToggle: (() -> Unit)? = null
) {
  Column {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onToggle == null) Modifier
                else Modifier.minTapTarget().clickable(onClick = onToggle)
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- EVERY CHILD IS WEIGHTED, SO NOTHING CAN BE MEASURED AT ZERO (Round 66 audit, PUI-2).
        //
        // THE HISTORY, BECAUSE THIS ROW HAS NOW BROKEN TWICE THE SAME WAY. `Row` measures
        // unweighted children in index order against whatever width is LEFT, then divides the
        // remainder among the weighted ones. Round 63 found `sub` - the percentage in dollar
        // mode, the DOLLARS in percent mode - measured at zero width and gone, because a
        // label that grew with the font scale had eaten the row ahead of it. The fix moved
        // the weight onto the LABEL, and its comment claimed "both figures are measured first
        // at full constraints".
        //
        // Only `lead` was. `sub` was still unweighted and still second, so it was measured
        // against what `lead` left; and the label, now the only weighted child, was the one
        // that could resolve to 0dp. On a 411dp phone at font scale 2.0 the two figures come
        // to about 376dp of the 379dp available, so "Today" and "Since you started" both
        // vanished - leaving two bare coloured numbers on a card whose own note says that two
        // numbers with nothing saying which is which is a guess. `sub` had `softWrap = false`
        // and no `overflow`, so on a 360dp phone it additionally CLIPPED - "+24." with no
        // ellipsis, the silent truncation [AutoFitNumber] exists to prevent.
        //
        // Weighting all three removes the ordering entirely: the split is 1:2, computed
        // before anything is measured, so no child can be starved by another's size. The
        // figures then shrink a point at a time inside their share rather than disappearing,
        // and only the label ellipsises - which is the right one to lose, because a label is
        // recoverable from context and a figure is not.
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(2f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AutoFitNumber(
                lead,
                color = color,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1.6f, fill = false)
            )
            Spacer(Modifier.width(10.dp))
            AutoFitNumber(
                sub,
                color = color,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
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
        // Same reversal as `BigLine` above, and for the same reason: the value is the point.
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false
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
                color = accentText
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
                // `redText`, not `Red` (Round 66 audit, REG-5). The brand red is a FILL colour
                // and measures 4.41:1 on the dark StatCard - under AA, on the most alarming
                // sentence in the app. AUD-1 moved the accessor; these call sites were painting
                // around it. See [redText].
                color = redText
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
