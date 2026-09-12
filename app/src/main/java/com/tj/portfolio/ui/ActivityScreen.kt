package com.tj.portfolio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.util.Fmt
import com.tj.portfolio.util.Storage

/**
 * A split ratio as a person says it: "10-for-1", "1-for-10". The ratio is stored in
 * `Txn.quantity` - see [TxnType.SPLIT] - so it needs its own phrasing wherever a row is
 * drawn, or it reads as a share count.
 */
internal fun splitLabel(ratio: Double): String {
    if (!ratio.isFinite() || ratio <= 0.0) return "split (no ratio set)"
    return if (ratio >= 1.0) "${Fmt.shares(ratio)}-for-1 split"
    else "1-for-${Fmt.shares(1.0 / ratio)} split"
}

/**
 * The line under a transaction row: when, what it was, fees, note.
 *
 * SHARED, because there are two of these rows - this tab's and the per-symbol list on
 * DetailScreen - and they were separate copies of the same `buildString`. The split support
 * added in Part 10 went into one of them and not the other, so a split read as
 * "10 @ $0.00" on the stock's own page: exactly the drift a second copy invites.
 */
internal fun txnSubtitle(t: Txn): String = buildString {
    append(Fmt.day(t.date))
    // A SPLIT's `quantity` is a RATIO, not a share count - see [TxnType.SPLIT].
    if (t.type == TxnType.SPLIT) {
        append("  -  ").append(splitLabel(t.quantity))
    } else {
        if (t.quantity > 0) append("  -  ").append(Fmt.shares(t.quantity))
            .append(" @ ").append(Fmt.price(t.price))
        if (t.fees > 0) append("  -  fees ").append(Fmt.usd(t.fees))
    }
    if (!t.note.isNullOrBlank()) append("  -  ").append(t.note)
}

@Composable
fun ActivityScreen(vm: PortfolioViewModel, state: UiState) {
    val ctx = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    val importing by vm.importing.collectAsState()
    val result by vm.importResult.collectAsState()
    val lastImport by vm.lastImport.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.importScreenshots(uris) }

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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Activity", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(3.dp))
                Text("Add")
            }
        }

        Refreshable(refreshing = state.pulling(PULL_PRICES), onRefresh = { vm.refresh(manual = true) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {

            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    // ---- where to resume from
                    StatCard {
                        val latest = remember(state.txns) { vm.latestTxnDate() }
                        Text("Import status", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        if (lastImport == null && latest == 0L) {
                            Text(
                                "No screenshots imported yet. Start with your oldest Ally activity " +
                                    "and work forward.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            lastImport?.let { li ->
                                KeyValue("Last upload", Fmt.day(li.at))
                                KeyValue(
                                    "That upload covered",
                                    if (li.maxDate > 0)
                                        "${Fmt.day(li.minDate)} - ${Fmt.day(li.maxDate)}"
                                    else "no dated rows"
                                )
                                KeyValue("Added / skipped as duplicate", "${li.count} / ${li.skipped}")
                            }
                            if (latest > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "You have transactions through ${Fmt.day(latest)} - " +
                                        "upload screenshots from that date forward.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = accentText,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("image/*")) },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (importing) "Reading screenshots..." else "Import screenshots (uses API key)") }

                    Text(
                        "Pick one or more Ally Invest screenshots. Claude reads them and proposes " +
                            "transactions for you to review. Rows you already have are flagged and " +
                            "skipped, so overlapping screenshots are safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    if (importing) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    SectionHeader("No API key? Use the Claude app")
                    Row {
                        OutlinedButton(
                            onClick = {
                                vm.writeScreenshotPrompt { msg -> vm.toast(msg) }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Make prompt file") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Import reply") }
                    }
                    Text(
                        "\"Make prompt file\" writes claude-screenshot-prompt.md into Downloads/Portfolio " +
                            "(replacing the previous one). Attach it to a chat in " +
                            "the Claude app along with your screenshots, save Claude's reply as a .txt " +
                            "or .md file, then tap \"Import reply\". No API key is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    SectionHeader("Transactions (${state.txns.size})")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            if (state.txns.isEmpty()) {
                item {
                    Text(
                        "No transactions yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
            items(state.txns, key = { it.id }) { t ->
                TxnRow(t, onEdit = { editing = t }, onDelete = { confirmDelete = t.id })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        }
    }

    if (showAdd) {
        TxnEditorDialog(
            onDismiss = { showAdd = false },
            onSave = { t -> vm.addTxnRecord(t); showAdd = false }
        )
    }

    editing?.let { t ->
        TxnEditorDialog(
            existing = t,
            onDismiss = { editing = null },
            onDelete = { vm.deleteTxn(t.id); editing = null; vm.toast("Transaction deleted") },
            onSave = { updated -> vm.updateTxn(updated); editing = null; vm.toast("Transaction updated") }
        )
    }

    confirmDelete?.let { id ->
        ConfirmDialog(
            title = "Delete transaction?",
            message = "This recalculates your positions and cash.",
            confirmText = "Delete",
            onDismiss = { confirmDelete = null },
            onConfirm = { vm.deleteTxn(id); confirmDelete = null }
        )
    }

    result?.let { r -> ImportReviewDialog(vm, r) }
}

@Composable
private fun TxnRow(t: Txn, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(t.type)
                    if (!t.symbol.isNullOrBlank()) append("  ").append(t.symbol)
                },
                fontWeight = FontWeight.SemiBold
            )
            Text(
                txnSubtitle(t),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // A split moves no cash, so it gets no cash column - a "$0.00" in the money slot
        // reads as a trade that cost nothing rather than as an event that is not a trade.
        if (t.type != TxnType.SPLIT) {
            Text(
                Fmt.usdSigned(t.amount),
                color = signColor(t.amount),
                fontWeight = FontWeight.SemiBold
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, "Delete", tint = redText, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Review step. Rows the app already has are marked DUPLICATE and unchecked by default,
 * so re-uploading an overlapping screenshot never double-counts.
 */
@Composable
private fun ImportReviewDialog(vm: PortfolioViewModel, r: com.tj.portfolio.net.ExtractResult) {
    // The duplicate check is one query per row. Running it inside composition, as this used
    // to, froze the UI thread for the whole scan before the dialog could draw - on a 111-row
    // import that is a visibly hung app. It now runs on IO and the rows appear immediately,
    // with everything ticked until the answer lands.
    val dupes = remember(r) { mutableStateListOf<Boolean>() }
    var checked by remember(r) { mutableStateOf(false) }
    val checks = remember(r) {
        mutableStateListOf<Boolean>().apply { r.transactions.forEach { add(true) } }
    }
    LaunchedEffect(r) {
        val flags = vm.duplicateFlags(r.transactions)
        dupes.clear(); dupes.addAll(flags)
        // Default the selection to "new rows only", which is what the user wants nine times
        // out of ten - they can still tick a genuine repeat trade back on.
        flags.forEachIndexed { i, d -> if (i < checks.size) checks[i] = !d }
        checked = true
    }
    val dupCount = dupes.count { it }

    AlertDialog(
        onDismissRequest = { vm.clearImport() },
        title = {
            Text(
                if (r.error != null) "Import failed"
                else "Review ${r.transactions.size} transaction(s)"
            )
        },
        text = {
            if (r.error != null) {
                Text(r.error, color = redText)
            } else {
                Column {
                    if (!checked) {
                        Text(
                            "Checking these against what you already have...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (dupCount > 0) {
                        Text(
                            "$dupCount already in your records - unchecked so nothing is " +
                                "counted twice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = accentText
                        )
                    }
                    if (r.notes.isNotBlank()) {
                        Text(
                            r.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // bulk selection matters here: an import can be a hundred rows
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { checks.indices.forEach { checks[it] = true } }) {
                            Text("Select all")
                        }
                        TextButton(onClick = { checks.indices.forEach { checks[it] = false } }) {
                            Text("None")
                        }
                        TextButton(onClick = {
                            dupes.forEachIndexed { i, d -> checks[i] = !d }
                        }) { Text("New only") }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${checks.count { it }} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (r.transactions.isEmpty()) {
                        Text("Claude didn't find any transactions in those images.")
                    } else {
                        // LazyColumn, not a scrolling Column: a 111-row import used to
                        // compose every row at once inside the dialog.
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            itemsIndexed(r.transactions) { i, t ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = checks.getOrElse(i) { true },
                                        onCheckedChange = { checks[i] = it }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "${t.type} ${t.symbol ?: ""}".trim(),
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (dupes.getOrElse(i) { false }) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "ALREADY HAVE",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = accentText
                                                )
                                            }
                                        }
                                        Text(
                                            buildString {
                                                append(Fmt.day(t.date))
                                                if (t.quantity > 0) append("  ")
                                                    .append(Fmt.shares(t.quantity))
                                                    .append(" @ ").append(Fmt.price(t.price))
                                                append("  ").append(Fmt.usdSigned(t.amount))
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (r.error == null && r.transactions.isNotEmpty()) {
                var saving by remember(r) { mutableStateOf(false) }
                TextButton(
                    enabled = checked && !saving,
                    onClick = {
                        saving = true
                        val keep =
                            r.transactions.filterIndexed { i, _ -> checks.getOrElse(i) { true } }
                        // the user has explicitly ticked these, so honour the selection exactly
                        vm.commitImportAsync(keep, force = true) { n ->
                            vm.toast("Imported $n transaction(s)")
                        }
                    }
                ) { Text(if (saving) "Importing..." else "Import selected") }
            } else {
                TextButton(onClick = { vm.clearImport() }) { Text("Close") }
            }
        },
        dismissButton = {
            if (r.error == null && r.transactions.isNotEmpty()) {
                TextButton(onClick = { vm.clearImport() }) { Text("Cancel") }
            }
        }
    )
}
