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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var preloading by remember { mutableStateOf(false) }
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
                            preloading = true
                            vm.preloadNewsForAdvice {
                                preloading = false
                                vm.requestAdvice()
                            }
                        },
                        enabled = !loading && !preloading && state.rows.isNotEmpty()
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
                if (state.rows.isEmpty()) {
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
                            preloading = true
                            vm.preloadNewsForAdvice {
                                // building the prompt reads every transaction and writes a
                                // file, so it goes off the main thread rather than running
                                // here in the click handler
                                vm.writeAdvicePrompt { msg ->
                                    preloading = false
                                    vm.toast(msg)
                                }
                            }
                        },
                        enabled = !preloading && state.rows.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Make prompt file") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Import reply") }
                }
                Text(
                    "Writes Downloads/Portfolio/claude-advice-prompt.md with your whole portfolio and " +
                        "recent headlines in it - the same file each time. Attach THAT file to a chat " +
                        "in the Claude app, then " +
                        "save Claude's answer as a separate .txt or .md file and tap \"Import reply\" on " +
                        "the answer - not on the prompt file. The ratings below fill in with no API key used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, color = Red, style = MaterialTheme.typography.bodyMedium)
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
                                        color = Accent,
                                        modifier = Modifier.width(22.dp)
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

        items(advice?.stocks.orEmpty()) { s -> RatingCard(s) }
    }
    }
}

@Composable
private fun RatingCard(s: StockRating) {
    val c = when {
        s.rating >= 8 -> Green
        s.rating >= 6 -> Color(0xFF3D9A5B)
        s.rating >= 4 -> Color(0xFFD79A2B)
        else -> Red
    }
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
                        "BUY", "ADD" -> Green
                        "SELL", "TRIM", "EXIT" -> Red
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
