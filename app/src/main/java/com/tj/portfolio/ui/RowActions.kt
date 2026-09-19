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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tj.portfolio.util.Fmt

/** A long-press action waiting to be handled. */
data class PendingAction(val symbol: String, val action: RowAction) {
    companion object {
        // So the three screens that own a `pending` flag can use rememberSaveable: without
        // it, a pending EDIT_POSITION/ADD_TXN survives a rotation but not a process death,
        // and the dialog it was about to open just never appears - no crash, no message.
        val Saver: Saver<PendingAction?, Any> = Saver(
            save = { it?.let { p -> listOf(p.symbol, p.action.name) } },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                (saved as? List<String>)?.takeIf { it.size == 2 }
                    ?.let { PendingAction(it[0], RowAction.valueOf(it[1])) }
            }
        )
    }
}

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

        // `row.watched`, NOT `row.watchOnly` (Round 66 audit, PUI-7). Reading `watchOnly`
        // here made the toggle one-way for a held-and-watched symbol: the branch always took
        // `addWatch`, which is a no-op write on a row already there, and then toasted that it
        // had been added. See [Row.watched].
        RowAction.WATCH_TOGGLE -> LaunchedEffect(pending) {
            if (row?.watched == true) {
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

/**
 * WHAT THE POSITION EDITOR PUTS IN ITS BOXES (Round 66 audit, PUI-1).
 *
 * Extracted for the same reason [TxnFields] was: these two strings decide what an untouched
 * Save writes to the ledger, and testing them through an `AlertDialog` in Robolectric is slow
 * enough here to be flaky. Pure functions, so the seeds can be checked directly.
 */
internal object PositionFields {

    /**
     * The share count, or "" when there is nothing to seed.
     *
     * [Fmt.exact], never [Fmt.shares]. `shares` is `#,##0.####`, which silently rounds a
     * fractional DRIP holding of 12.345678 to "12.3457" - and this box is saved back as an
     * override, so the display rounding would become the position.
     */
    fun shares(shares: Double): String =
        if (shares > 0) Fmt.exact(shares) else ""

    /**
     * The average cost, or "" when there is nothing to seed.
     *
     * [Fmt.exact], never [Fmt.priceBare]. `avgCost` is `costBasis / shares`, so it is
     * routinely a long decimal - 0.42355 from $1,270.65 over 3,000 shares - and `priceBare`
     * would seed "0.424", which saves as a $1.35 change to the cost basis.
     */
    fun cost(avgCost: Double): String =
        if (avgCost > 0) Fmt.exact(avgCost) else ""
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
    // ---- SEEDED WITH `Fmt.exact`, NOT A DISPLAY FORMATTER (Round 66 audit, PUI-1).
    //
    // THE BUG THIS FIXES. These two boxes are pre-filled with the position's current numbers
    // and saved straight back as an `Override`, so whatever rounding the seed applies becomes
    // a PERMANENT change to the ledger - written by pressing Save without typing anything.
    // `Fmt.shares` is `#,##0.####` and `Fmt.priceBare` gives two or three decimals, and
    // `avgCost` is `costBasis / shares`, which almost never lands that short: 3,000 shares
    // bought for $1,270.65 is an average of 0.42355, seeded as "0.424", and saving that writes
    // a cost basis of $1,272.00. A $1.35 change to the position, from an edit nobody made.
    //
    // This is the identical bug the transaction editor had and fixed in this same round -
    // `Fmt.exact` was written for it, and the KDoc at [Fmt.exact] tells the story. The other
    // editor that writes to the ledger was missed.
    var shares by remember(symbol) {
        mutableStateOf(PositionFields.shares(row?.shares ?: 0.0))
    }
    var cost by remember(symbol) {
        mutableStateOf(PositionFields.cost(row?.avgCost ?: 0.0))
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
                        color = accentText,
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
                    }) { Text("Clear", color = redText) }
                }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        }
    )
}
