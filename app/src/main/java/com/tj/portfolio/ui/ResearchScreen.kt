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
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.net.ShortVehicle
import com.tj.portfolio.util.Fmt

/**
 * One of the three lists, as a tab.
 *
 * ROUND 56: they used to be three sections stacked in one scroll. Reaching Worst meant
 * scrolling past a header, ten Trending cards and ten Best cards - and each card is tall,
 * because it carries its own reason lines and often a Claude paragraph. Tabs make each list
 * one gesture away and keep the scroll position of the one you were reading.
 *
 * The data layer is untouched: all three lists are still built by the same single pass, and
 * `PAGE` rows of each are still the only ones that cost per-row requests. Switching tabs
 * costs nothing.
 */
private enum class Section(
    val key: String,
    val tab: String,
    val blurb: String,
    val bullish: Boolean
) {
    TRENDING(
        ResearchSet.SECTION_TRENDING, "Trending",
        "r/wallstreetbets mentions blended with how often each name appears in today's " +
            "market headlines.",
        true
    ),
    BEST(
        ResearchSet.SECTION_BEST, "Best",
        "Forward earnings growth, a forward multiple that has not priced it in, price above " +
            "the 50- and 200-day averages, and analyst consensus.",
        true
    ),
    WORST(
        ResearchSet.SECTION_WORST, "Worst",
        "Losing money with no forward turn, below both moving averages, deep into a 52-week " +
            "decline, heavily shorted, small.",
        false
    );

    fun rowsIn(set: ResearchSet): List<ResearchRow> = when (this) {
        TRENDING -> set.trending
        BEST -> set.best
        WORST -> set.worst
    }
}

/**
 * THE RESEARCH SCREEN - the second half of the Watch tab (Round 54, retabbed in Round 56).
 *
 * Three lists, ten rows each, and a button under each that reveals ten more. Nothing beyond
 * ten is loaded until that button is pressed: the list itself is already in memory, but the
 * per-row lookups that cost requests - analyst consensus, the inverse-ETF search - are only
 * spent on rows that are actually on screen. That was TJ's explicit rule and it is enforced
 * in [PortfolioViewModel.showMoreResearch], not just in this layout.
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
    var section by remember { mutableStateOf(Section.entries[vm.researchTab()]) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.readPickedFile(uri) { text ->
            vm.toast(if (text == null) "Couldn't read that file" else vm.importResearchFile(text))
        }
    }

    val watched = remember(set.generated, set.explained) { vm.watchedSymbols() + vm.heldSymbols() }
    val rows = section.rowsIn(set)
    val visibleCount = shown[section.key] ?: ResearchSet.PAGE

    Column(Modifier.fillMaxSize()) {

        // ------------------------------------------------ pinned title + status
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Research", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (busy.isNotEmpty()) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when (busy) {
                    BUSY_BUILDING -> "Scanning the market..."
                    BUSY_DETAIL -> "Analyst data..."
                    BUSY_EXPLAINING -> "Claude is reading..."
                    else -> if (set.generated > 0) "Updated ${Fmt.relative(set.generated)}" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BigIconButton(Icons.Filled.Refresh, "Rebuild research") {
                vm.loadResearch(force = true)
            }
        }

        // ------------------------------------------------------ pinned tab row
        SecondaryTabRow(
            selectedTabIndex = section.ordinal,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Section.entries.forEach { s ->
                val count = s.rowsIn(set).size
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
                Text(it, color = Red, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.dismissResearchError() }) { Text("Dismiss") }
            }
        }

        Refreshable(
            refreshing = state.pulling(PULL_RESEARCH),
            onRefresh = { vm.loadResearch(force = true) }
        ) {
            // ONE SCROLL STATE PER SECTION, all three created unconditionally so each keeps
            // its own position. Sharing a single state means switching from row 40 of Best
            // into a Worst list of 12 rows lands somewhere arbitrary, and switching back
            // loses the place entirely.
            //
            // `rememberLazyListState` rather than a hand-built `LazyListState` in a map:
            // only the remembered form carries a Saver, so these are the positions that
            // actually survive a rotation or a process-death restore. A map of raw
            // `LazyListState()` objects looks equivalent and silently loses all three.
            val trendingState = androidx.compose.foundation.lazy.rememberLazyListState()
            val bestState = androidx.compose.foundation.lazy.rememberLazyListState()
            val worstState = androidx.compose.foundation.lazy.rememberLazyListState()
            val listState = when (section) {
                Section.TRENDING -> trendingState
                Section.BEST -> bestState
                Section.WORST -> worstState
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {

                item(key = "blurb") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            section.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (section == Section.WORST) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                ShortVehicle.WARNING,
                                style = MaterialTheme.typography.bodySmall,
                                color = Red
                            )
                        }
                    }
                }

                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            when {
                                busy.isNotEmpty() -> "Scanning..."
                                set.isEmpty ->
                                    "Nothing loaded yet. Pull down, or tap the refresh " +
                                        "button, to scan the market."
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
                        rows.take(visibleCount).distinctBy { it.symbol },
                        key = { "${section.key}_${it.symbol}" }
                    ) { r ->
                        ResearchCard(r, section.bullish, r.symbol in watched, onOpen, onOpenUrl)
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
                            "Whole-market lists built on this phone from free feeds, and " +
                                "scored here - not recommendations. Every score shows its " +
                                "reasons.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.explainResearch() },
                            enabled = busy.isEmpty() && !set.isEmpty,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (set.explained > 0) "Re-explain with Claude"
                                else "Explain with Claude"
                            )
                        }

                        SectionHeader("No API key? Use the Claude app")
                        Row {
                            OutlinedButton(
                                onClick = { vm.writeResearchPrompt { msg -> vm.toast(msg) } },
                                enabled = !set.isEmpty,
                                modifier = Modifier.weight(1f)
                            ) { Text("Make prompt file") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Import answer") }
                        }
                        TextButton(onClick = { howTo = !howTo }) {
                            Text(if (howTo) "Hide how this works" else "How does this work?")
                        }
                        if (howTo) {
                            StatCard {
                                Text(
                                    "The prompt file carries every row on this screen - the " +
                                        "prices, the scores and the reasons - so Claude needs " +
                                        "no explanation from you. Attach " +
                                        "Downloads/Portfolio/" +
                                        com.tj.portfolio.net.ResearchBridge.PROMPT_FILE +
                                        " to a chat in the Claude app, save the reply as a " +
                                        ".txt or .md file, then tap \"Import answer\" and pick " +
                                        "THAT file - not the prompt file. The explanations " +
                                        "fill in and no API key is used. Anything Claude adds " +
                                        "that the app missed is added to the list.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        if (set.explained > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Explained ${Fmt.relative(set.explained)} via ${set.explainedBy}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (set.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            StatCard {
                                Text(
                                    "Claude's note on the app's data",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(set.notes, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (set.warnings.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Partial data this pass: " + set.warnings.joinToString("; "),
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
                            set.sources.ifBlank { com.tj.portfolio.net.Research.SOURCES },
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

@Composable
private fun ResearchCard(
    r: ResearchRow,
    bullish: Boolean,
    followed: Boolean,
    onOpen: (String) -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    // Colour is by score AND direction: a 90 on the Worst list is a strong finding about a
    // bad company, so painting it green because the number is high would be exactly wrong.
    val c = when {
        r.score >= 70 -> if (bullish) Green else Red
        r.score >= 50 -> if (bullish) Color(0xFF3D9A5B) else Color(0xFFD0554A)
        else -> Color(0xFFD79A2B)
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        StatCard(modifier = Modifier.clickable { onOpen(r.symbol) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(c.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${r.score}", color = c, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (followed) {
                            Spacer(Modifier.width(6.dp))
                            Chip("FOLLOWING", Accent)
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
                            Fmt.pctSigned(r.changePct),
                            color = if (r.changePct >= 0) Green else Red,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // --- the app's own reasons
            if (r.reasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                r.reasons.take(6).forEach { line ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "-",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(12.dp)
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
                    color = Accent,
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
                        color = Accent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(r.why, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // --- the way to bet against it
            if (!bullish && (r.shortVehicle.isNotBlank() || r.shortVehicleNote.isNotBlank())) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.shortVehicle.isNotBlank()) {
                        Chip(r.shortVehicle, Red)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        r.shortVehicleNote.ifBlank { "inverse ETF on this stock" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- the headline behind it
            if (r.headline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .minTapTarget()
                        .clickable(enabled = r.headlineUrl.isNotBlank()) {
                            onOpenUrl(r.headlineUrl, r.headline)
                        }
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
