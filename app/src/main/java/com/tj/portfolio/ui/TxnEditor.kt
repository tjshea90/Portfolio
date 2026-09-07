package com.tj.portfolio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Fees
import com.tj.portfolio.util.Fmt

fun String.toNum(): Double =
    trim().replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0

/**
 * One dialog for both adding and editing a transaction. Pass [existing] to edit;
 * pass null (with an optional [presetSymbol]) to add.
 */
@Composable
fun TxnEditorDialog(
    existing: Txn? = null,
    presetSymbol: String? = null,
    presetType: String? = null,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (Txn) -> Unit
) {
    var type by remember { mutableStateOf(existing?.type ?: presetType ?: TxnType.BUY) }
    var typeMenu by remember { mutableStateOf(false) }
    var symbol by remember { mutableStateOf(existing?.symbol ?: presetSymbol ?: "") }
    var qty by remember {
        mutableStateOf(if ((existing?.quantity ?: 0.0) > 0) Fmt.shares(existing!!.quantity) else "")
    }
    var price by remember {
        mutableStateOf(if ((existing?.price ?: 0.0) > 0) Fmt.priceBare(existing!!.price) else "")
    }
    var amount by remember {
        mutableStateOf(
            if (existing != null && existing.amount != 0.0)
                Fmt.priceBare(kotlin.math.abs(existing.amount)) else ""
        )
    }
    var fees by remember {
        mutableStateOf(if ((existing?.fees ?: 0.0) > 0) Fmt.priceBare(existing!!.fees) else "")
    }
    // Once the user types in the Fees box themselves, the app stops filling it in. Editing
    // an existing transaction counts as already-decided, so an import is never overwritten.
    var feesTouched by remember { mutableStateOf(existing != null) }
    var date by remember { mutableStateOf(Fmt.iso(existing?.date ?: Fmt.todayMs())) }
    var note by remember { mutableStateOf(existing?.note ?: "") }

    val isTrade = type == TxnType.BUY || type == TxnType.SELL

    // What Ally would actually charge for this trade, recomputed as the fields change.
    val expected = remember(type, qty, price) {
        Fees.forEquityTrade(type, qty.toNum(), price.toNum())
    }
    // Fill the Fees box in for the user. A stock or ETF BUY at Ally costs nothing, so this
    // normally puts a hard 0 there instead of leaving an empty box that invites a guess.
    LaunchedEffect(expected, feesTouched, isTrade) {
        if (!feesTouched) {
            fees = if (!isTrade) "" else Fmt.priceBare(expected.total)
        }
    }

    // ---- validation -------------------------------------------------------
    // Save used to accept anything: an unparseable date silently became TODAY, which
    // quietly re-orders the FIFO lots and changes the cost basis of a stock you have
    // held for a year. A blank symbol or zero shares wrote a row the ledger ignores
    // but the Activity tab still shows. Both are now blocked before they reach the db.
    val parsedDate = remember(date) { Fmt.parseDate(date) }
    val qNum = qty.toNum()
    val pNum = price.toNum()
    val aNum = amount.toNum()
    val feeNum = fees.toNum()

    /**
     * The cash effect this transaction will actually record, shown before it is saved.
     *
     * Three of the fields interact in ways the labels alone do not explain: a price and a
     * total that disagree (the price wins, and the total is discarded), and a fee that is
     * added on top of a price but is already inside a total. Rather than describe those
     * rules in prose nobody reads, the dialog just shows the answer, so a wrong entry is
     * visible while it can still be corrected.
     */
    val previewCash = run {
        var p = pNum
        var a = aNum
        if (p <= 0 && qNum > 0 && a > 0) p = Txn.unitPriceFromTotal(type, qNum, a, feeNum)
        if (a <= 0 && qNum > 0 && p > 0) a = qNum * p
        Txn.cashEffect(type, qNum, p, a, feeNum)
    }

    val problem: String? = when {
        parsedDate == null -> "Enter the date as yyyy-MM-dd (e.g. ${Fmt.iso(Fmt.todayMs())})"
        (isTrade || type == TxnType.DIVIDEND) && symbol.isBlank() -> "Enter a ticker symbol"
        isTrade && qNum <= 0 -> "Enter how many shares"
        isTrade && pNum <= 0 && aNum <= 0 -> "Enter a price per share, or the total amount"
        !isTrade && aNum <= 0 -> "Enter an amount"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add transaction" else "Edit transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(type)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        TxnType.ALL.forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { type = it; typeMenu = false })
                        }
                    }
                }
                if (isTrade || type == TxnType.DIVIDEND) {
                    EditField("Symbol", symbol) { symbol = it.uppercase() }
                }
                if (isTrade) {
                    EditField("Shares", qty, numeric = true) { qty = it }
                    EditField("Price per share", price, numeric = true) { price = it }
                }
                EditField(
                    // Named for what it is: the net figure off a confirmation, fee included.
                    // "Total amount" left it ambiguous whether the fee was in or out, and the
                    // two readings give different cost bases.
                    if (isTrade) "Total cash moved, fees included (optional)" else "Amount",
                    amount, numeric = true
                ) { amount = it }
                EditField("Fees", fees, numeric = true) { fees = it; feesTouched = true }
                if (isTrade) {
                    Text(
                        expected.explain(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expected.isZero) Accent
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!expected.isZero) {
                        Text(
                            "Filled in from Ally's published schedule. Change it if your " +
                                "confirmation says something different.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                EditField("Date (yyyy-MM-dd)", date) { date = it }
                EditField("Note", note) { note = it }
                if (problem == null && kotlin.math.abs(previewCash) > 0.0001) {
                    Text(
                        (if (previewCash < 0) "This takes " else "This adds ") +
                            Fmt.usd(kotlin.math.abs(previewCash)) +
                            (if (previewCash < 0) " out of your cash" else " to your cash") +
                            (if (isTrade && pNum > 0 && aNum > 0 &&
                                    kotlin.math.abs(qNum * pNum - aNum) > 0.005)
                                " - worked out from the price per share, not the total you typed"
                            else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (problem != null) {
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = Red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (existing != null) {
                    Text(
                        "Source: ${existing.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = {
                val q = qNum
                var p = pNum
                var a = aNum
                // The total is the NET cash that moved and already has the fee inside it,
                // so the fee comes out before the division - otherwise cashEffect charges
                // it again. See Txn.unitPriceFromTotal; a no-op on a zero-fee trade.
                if (p <= 0 && q > 0 && a > 0) p = Txn.unitPriceFromTotal(type, q, a, feeNum)
                if (a <= 0 && q > 0 && p > 0) a = q * p
                val f = feeNum
                onSave(
                    Txn(
                        id = existing?.id ?: 0L,
                        type = type,
                        symbol = symbol.trim().uppercase().ifBlank { null },
                        quantity = q,
                        price = p,
                        amount = Txn.cashEffect(type, q, p, a, f),
                        fees = f,
                        date = parsedDate ?: Fmt.todayMs(),
                        note = note.trim().ifBlank { null },
                        source = existing?.source ?: "MANUAL"
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row2 {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = Red) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun Row2(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.Row(content = content)
}

@Composable
private fun EditField(
    label: String,
    value: String,
    numeric: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
}
