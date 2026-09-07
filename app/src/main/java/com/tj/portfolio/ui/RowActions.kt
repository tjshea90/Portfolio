package com.tj.portfolio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tj.portfolio.util.Fmt

/** A long-press action waiting to be handled. */
data class PendingAction(val symbol: String, val action: RowAction)

/**
 * Renders whatever dialog the long-press menu asked for. Shared by the Portfolio tab,
 * the Watchlist tab and the detail screen so the menu behaves identically everywhere.
 */
@Composable
fun RowActionHost(
    vm: PortfolioViewModel,
    state: UiState,
    pending: PendingAction?,
    onOpen: (String) -> Unit,
    onNews: (String) -> Unit,
    onDone: () -> Unit
) {
    if (pending == null) return
    val symbol = pending.symbol
    val row = state.rows.firstOrNull { it.symbol == symbol }

    when (pending.action) {
        // These three do their work and close immediately - there is no dialog to show.
        //
        // They used to run straight from the composable body, which meant navigating,
        // writing to the database and clearing the caller's state IN THE MIDDLE OF
        // COMPOSITION. Compose makes no promise about when or how often a composable body
        // runs, so that is a side effect on a schedule nobody controls: it can run twice,
        // and the state it writes can be read by the same composition pass that wrote it.
        // A LaunchedEffect keyed on the action runs it exactly once, after the frame.
        RowAction.OPEN -> LaunchedEffect(pending) { onOpen(symbol); onDone() }
        RowAction.NEWS -> LaunchedEffect(pending) { onNews(symbol); onDone() }

        RowAction.WATCH_TOGGLE -> LaunchedEffect(pending) {
            if (row?.watchOnly == true) {
                vm.removeWatch(symbol)
                vm.toast("$symbol removed from watchlist")
            } else {
                vm.addWatch(symbol)
                vm.toast("$symbol added to watchlist")
            }
            onDone()
        }

        RowAction.ADD_TXN -> TxnEditorDialog(
            presetSymbol = symbol,
            onDismiss = onDone,
            onSave = { t ->
                vm.addTxnRecord(t)
                vm.toast("${t.type} $symbol saved")
                onDone()
            }
        )

        RowAction.EDIT_POSITION -> EditPositionDialog(vm, symbol, row, onDone)

        RowAction.DELETE -> ConfirmDialog(
            title = "Delete $symbol?",
            message = "Removes every transaction for $symbol and any manual override. " +
                "Cash and realized P/L are recalculated. This cannot be undone.",
            confirmText = "Delete $symbol",
            onDismiss = onDone,
            onConfirm = {
                val n = vm.deleteSymbol(symbol)
                vm.toast("Deleted $symbol ($n transaction(s))")
                onDone()
            }
        )
    }
}

/** Direct edit of the computed position: share count and average cost. */
@Composable
private fun EditPositionDialog(
    vm: PortfolioViewModel,
    symbol: String,
    row: Row?,
    onDone: () -> Unit
) {
    // keyed on the symbol: without it, opening the dialog for a second stock without the
    // host passing through null in between would show the first stock's numbers
    var shares by remember(symbol) {
        mutableStateOf(if ((row?.shares ?: 0.0) > 0) Fmt.shares(row!!.shares) else "")
    }
    var cost by remember(symbol) {
        mutableStateOf(if ((row?.avgCost ?: 0.0) > 0) Fmt.priceBare(row!!.avgCost) else "")
    }
    val hasOverride = remember(symbol) { vm.overrideFor(symbol) != null }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Edit $symbol") },
        text = {
            Column {
                Text(
                    "These override what the transaction ledger worked out. Leave a field blank " +
                        "to go back to the calculated value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = shares,
                    onValueChange = { shares = it },
                    label = { Text("Shares owned") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = cost,
                    onValueChange = { cost = it },
                    label = { Text("Average cost per share") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                if (hasOverride) {
                    Text(
                        "An override is currently active for $symbol.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setOverride(symbol, cost.toNum().takeIf { it > 0 }, shares.toNum().takeIf { it > 0 })
                vm.toast("$symbol updated")
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (hasOverride) {
                    TextButton(onClick = {
                        vm.setOverride(symbol, null, null)
                        vm.toast("$symbol back to calculated values")
                        onDone()
                    }) { Text("Clear", color = Red) }
                }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        }
    )
}
