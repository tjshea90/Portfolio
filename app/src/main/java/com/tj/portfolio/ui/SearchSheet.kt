package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen ticker search. Predicts as you type, matching either the ticker or the
 * company name, and lets you add or remove a symbol from the watchlist from anywhere
 * in the app.
 */
@Composable
fun SearchSheet(
    vm: PortfolioViewModel,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val hits by vm.search.collectAsState()
    val busy by vm.searching.collectAsState()
    val focus = remember { FocusRequester() }
    // recomposition trigger after add/remove so the row's state flips immediately
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { vm.clearSearch() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; vm.searchSymbols(it) },
                    label = { Text("Search ticker or company") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.clearSearch() }) {
                            Icon(Icons.Filled.Close, "Clear")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            if (busy) {
                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            if (query.isBlank()) {
                Text(
                    "Start typing - \"nvid\", \"apple\", \"SPY\". Results come from Yahoo Finance " +
                        "and match both tickers and company names.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else if (hits.isEmpty() && !busy) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "No matches for \"$query\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        vm.addWatch(query); version++; onDismiss()
                    }) { Text("Add \"${query.trim().uppercase()}\" to watchlist anyway") }
                }
            }

            // one database read for the whole list instead of two per row
            val watchedSet = remember(hits, version) { vm.watchedSymbols() }
            val heldSet = remember(hits, version) { vm.heldSymbols() }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(hits, key = { it.symbol }) { hit ->
                    val watched = hit.symbol in watchedSet
                    val held = hit.symbol in heldSet
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(hit.symbol) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(hit.symbol, 36)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hit.symbol, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                listOfNotNull(
                                    hit.name.ifBlank { null },
                                    hit.exchange.ifBlank { null }
                                ).joinToString("  -  "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        when {
                            held -> Box(
                                Modifier
                                    .background(Green.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) { Text("HELD", color = greenText, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                            watched -> TextButton(onClick = { vm.removeWatch(hit.symbol); version++ }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = greenText)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remove", color = redText, fontSize = 13.sp)
                                }
                            }

                            else -> TextButton(onClick = { vm.addWatch(hit.symbol); version++ }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Watch", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    RowSeparator()
                }
            }
        }
    }
}
