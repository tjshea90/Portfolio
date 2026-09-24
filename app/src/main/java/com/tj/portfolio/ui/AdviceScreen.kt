package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import com.tj.portfolio.data.StockRating
import com.tj.portfolio.util.Fmt
import com.tj.portfolio.util.Storage

@Composable
fun AdviceScreen(vm: PortfolioViewModel, state: UiState) {
    val advice by vm.advice.collectAsState()
    val loading by vm.adviceLoading.collectAsState()
    val error by vm.adviceError.collectAsState()
    // In the ViewModel, so a tab switch cannot reset it mid-preload (U-8).
    val preloading = vm.advicePreparing.collectAsState().value > 0
    // Watch-only rows are not a portfolio to advise on (U-8): with only a watchlist both
    // buttons sent Claude an empty portfolio, and "Add some holdings first." was hidden.
    val hasHoldings = state.rows.any { !it.watchOnly }
    val ctx = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // read off the main thread - see readPickedFile
            vm.readPickedFile(uri) { text ->
                vm.toast(if (text == null) "Couldn't read that file" else vm.importClaudeFile(text))
            }
        }
    }

    Refreshable(refreshing = state.pulling(PULL_PRICES), onRefresh = { vm.refresh(manual = true) }) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Advice", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sends your full portfolio - positions, cost basis, P/L, cash - plus recent " +
                        "headlines to Claude with your own API key, and asks for a candid read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            vm.preloadNewsForAdvice { vm.requestAdvice() }
                        },
                        enabled = !loading && !preloading && hasHoldings
                    ) {
                        Text(
                            when {
                                preloading -> "Gathering news..."
                                loading -> "Analyzing..."
                                advice == null -> "Analyze my portfolio"
                                else -> "Re-analyze"
                            }
                        )
                    }
                    if (loading || preloading) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (!hasHoldings) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add some holdings first.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                SectionHeader("No API key? Use the Claude app")
                Row {
                    OutlinedButton(
                        onClick = {
                            vm.preloadNewsForAdvice {
                                // building the prompt reads every transaction and writes a
                                // file, so it goes off the main thread rather than running
                                // here in the click handler
                                vm.writeAdvicePrompt { out ->
                                    launchPromptShare(ctx, out) { vm.toast(it) }
                                }
                            }
                        },
                        enabled = !preloading && hasHoldings,
                        modifier = Modifier.weight(1f)
                    ) { Text("Make prompt file") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Import reply") }
                }
                Text(
                    "Opens the share menu with your whole portfolio and recent headlines in one " +
                        "file - pick Claude to start a new chat with it. When Claude's answer file " +
                        "appears, tap it, then Share and pick Portfolio: the ratings below fill in " +
                        "on their own, with no API key used. A copy of the prompt is also saved to " +
                        "Downloads/Portfolio, and \"Import reply\" still takes a saved answer file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, color = redText, style = MaterialTheme.typography.bodyMedium)
                }
                val a = advice
                if (a != null) {
                    if (a.generated > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Generated ${Fmt.relative(a.generated)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (a.summary.isNotBlank()) {
                        SectionHeader("Overview")
                        StatCard { Text(a.summary, style = MaterialTheme.typography.bodyLarge) }
                    }
                    if (a.actions.isNotEmpty()) {
                        SectionHeader("Suggested actions")
                        StatCard {
                            a.actions.forEachIndexed { i, s ->
                                Row(Modifier.padding(vertical = 3.dp)) {
                                    Text(
                                        "${i + 1}.",
                                        fontWeight = FontWeight.Bold,
                                        color = accentText,
                                        // widthIn(min), NOT width. Compose's default overflow
                                        // is Clip, so a FIXED 22dp column silently cut the
                                        // number off from "10." upward - and sooner than that
                                        // at a large font scale. This is the v1.6 clipping
                                        // trap the project has a standing rule about; a
                                        // minimum keeps the list aligned without capping it.
                                        modifier = Modifier.widthIn(min = 22.dp)
                                    )
                                    Text(s, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    if (a.risks.isNotBlank()) {
                        SectionHeader("Risks")
                        StatCard { Text(a.risks, style = MaterialTheme.typography.bodyLarge) }
                    }
                    if (a.stocks.isNotEmpty()) SectionHeader("Ratings")
                }
            }
        }

        // DISTINCT BY SYMBOL BEFORE THE KEY, not just keyed. `advice` is Claude's free-text JSON
        // reply (Claude.kt's `advice()`), parsed with no dedup and no blank-symbol skip - unlike
        // ResearchScreen's own keyed lists, which document the same risk (nearby comment: "handed
        // one key twice it throws"). A repeated or blank ticker in Claude's answer would otherwise
        // crash this tab's LazyColumn outright.
        items(advice?.stocks.orEmpty().distinctBy { it.symbol }, key = { it.symbol }, contentType = { "rating" }) { s -> RatingCard(s) }
    }
    }
}

/**
 * [StockRating.rating]'s 4-tier colour, as TEXT (optimization pass). Same fill-vs-text split as
 * Theme.kt's greenText/redText/scoreColor: the two middle tiers were literally scoreColor's
 * dark-only values used unconditionally, which read fine in dark mode but failed contrast as
 * light-mode text - now theme-branched the same way. `internal`, not private, so [ContrastTest]
 * can measure it directly, the same reasoning this file's own sibling composables document.
 */
@Composable
internal fun ratingColor(rating: Int): Color {
    val dark = LocalDarkTheme.current
    return when {
        rating >= 8 -> greenText
        rating >= 6 -> if (dark) Color(0xFF3D9A5B) else Color(0xFF2F7A46)
        rating >= 4 -> if (dark) Color(0xFFD79A2B) else Color(0xFF8A6410)
        else -> redText
    }
}

@Composable
private fun RatingCard(s: StockRating) {
    val c = ratingColor(s.rating)
    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        StatCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(c.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${s.rating}", color = c, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (s.target.isNotBlank()) Text(
                        s.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (s.action.isNotBlank()) {
                    val ac = when (s.action.uppercase()) {
                        "BUY", "ADD" -> greenText
                        "SELL", "TRIM", "EXIT" -> redText
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        Modifier.background(ac.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(s.action.uppercase(), color = ac, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            if (s.reasoning.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(s.reasoning, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
