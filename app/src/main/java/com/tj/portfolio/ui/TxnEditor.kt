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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Fees
import com.tj.portfolio.util.Fmt

// `toDoubleOrNull()` happily parses the literal text "NaN" or "Infinity" into a real,
// non-finite Double - every `<= 0` guard downstream (qNum, pNum, aNum) is an IEEE-754
// comparison that evaluates false against NaN/+Infinity, so a non-finite value used to sail
// straight past validation and into the ledger, corrupting every total derived from it. Collapse
// non-finite results to 0.0 here, at the one place text becomes a number, so every caller's
// existing "<= 0 means invalid" checks reject it for free.
fun String.toNum(): Double =
    (trim().replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0).let { if (it.isFinite()) it else 0.0 }

/**
 * WHAT THE EDITOR PUTS IN ITS BOXES, AND WHAT SAVE MAKES OF THEM (Round 66).
 *
 * ---- WHY THIS IS NOT INSIDE THE COMPOSABLE
 *
 * It used to be: four `mutableStateOf(Fmt.something(...))` seeds and, forty lines further
 * down, the arithmetic inside the Save button's `onClick`. Neither could be tested without
 * rendering an `AlertDialog` in Robolectric, which is slow enough here to be flaky - so the
 * single most consequential arithmetic in the app, the bit that decides what gets written to
 * the ledger, had no test at all.
 *
 * Pulling it out costs nothing at the call site and buys an exact, fast test of the property
 * that actually matters: **opening a transaction and pressing Save without touching anything
 * must produce the identical transaction.** The dialog and the test now run the same code, so
 * they cannot drift apart.
 *
 * ---- THE BUG THAT PROMPTED IT
 *
 * The boxes were seeded with the DISPLAY formatters - `Fmt.priceBare` (two or three decimals)
 * and `Fmt.shares` (four). Prices here routinely carry more, because `Txn.unitPriceFromTotal`
 * derives them from a net total: a 1,000-share buy for $1,559.50 is stored at 1.5595. Save
 * parses the boxes back and `Txn.cashEffect` recomputes the cash from quantity x price -
 * falling back to the stored total only when one of them is missing - so the rounded "1.560"
 * became the new truth and the row was rewritten as $1,560.00. Fifty cents of drift in the
 * cash balance, the cost basis and everything derived from them, from an edit nobody made.
 *
 * [Fmt.exact] is the fix: what is shown parses back to the identical double.
 */
object TxnFields {

    fun qty(t: Txn?): String = if ((t?.quantity ?: 0.0) > 0) Fmt.exact(t!!.quantity) else ""

    fun price(t: Txn?): String = if ((t?.price ?: 0.0) > 0) Fmt.exact(t!!.price) else ""

    fun amount(t: Txn?): String =
        if (t != null && t.amount != 0.0) Fmt.exact(kotlin.math.abs(t.amount)) else ""

    fun fees(t: Txn?): String = if ((t?.fees ?: 0.0) > 0) Fmt.exact(t!!.fees) else ""

    /**
     * The three numbers Save actually records, from the four boxes as typed.
     *
     * Shared with the live "this will record ..." preview above the buttons, so the figure
     * the user is shown before saving and the figure that is saved are the same computation
     * rather than two copies of it.
     */
    data class Resolved(
        val quantity: Double,
        val price: Double,
        val amount: Double,
        val fees: Double
    )

    fun resolve(type: String, qty: String, price: String, amount: String, fees: String): Resolved {
        // A SPLIT carries its RATIO in `quantity` and nothing else - see [TxnType.SPLIT].
        // Returned clean rather than derived, so that switching the type after typing a
        // price or a total cannot leave a stray figure on a row where those fields have no
        // meaning and are not even on screen.
        if (type == TxnType.SPLIT) return Resolved(qty.toNum(), 0.0, 0.0, 0.0)
        // SHARES AND A PER-SHARE PRICE MEAN NOTHING OUTSIDE A TRADE (Part 11 audit finding).
        // The dialog only shows the Shares/Price boxes when `isTrade`, but this function
        // used to parse `qty`/`price` regardless of `type` - so switching a half-typed BUY
        // (Shares "100") to DIVIDEND and saving with just an Amount silently carried the
        // "100" into the saved row's `quantity`, and the generic p<=0&&q>0&&a>0 branch
        // below then DERIVED a price from it (e.g. a $50 dividend became "100 @ $0.50").
        // The ledger itself ignores quantity/price on a non-trade type, so this never
        // touched a total - but it permanently corrupted what the row's own subtitle shows.
        // Zeroed at the source, the same way SPLIT already is just above.
        // The fee goes too, for the same reason the quantity and price do: the box is not on
        // screen for these types, `cashEffect` does not apply a fee to them, and a value left
        // behind it after switching type would be counted by `Ledger.fees()` while never
        // leaving the cash balance. See the Fees box's own note in the dialog.
        if (type != TxnType.BUY && type != TxnType.SELL) {
            return Resolved(0.0, 0.0, amount.toNum(), 0.0)
        }
        val q = qty.toNum()
        val f = fees.toNum()
        var p = price.toNum()
        var a = amount.toNum()
        // The total is the NET cash that moved and already has the fee inside it, so the fee
        // comes out before the division - otherwise cashEffect charges it again. See
        // Txn.unitPriceFromTotal; a no-op on a zero-fee trade.
        if (p <= 0 && q > 0 && a > 0) p = Txn.unitPriceFromTotal(type, q, a, f)
        if (a <= 0 && q > 0 && p > 0) a = q * p
        return Resolved(q, p, a, f)
    }

    /** The cash effect a Save would record, for the preview and for the write. */
    fun cashOf(type: String, r: Resolved): Double =
        Txn.cashEffect(type, r.quantity, r.price, r.amount, r.fees)
}

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
    // rememberSaveable, not remember: this dialog holds typed-but-unsaved money figures, and
    // Android can kill the process while it's backgrounded (not just rotate it). Without this,
    // that typed entry vanished with no warning - see the "full tests" audit that found it.
    var type by rememberSaveable { mutableStateOf(existing?.type ?: presetType ?: TxnType.BUY) }
    var typeMenu by rememberSaveable { mutableStateOf(false) }
    var symbol by rememberSaveable { mutableStateOf(existing?.symbol ?: presetSymbol ?: "") }
    // ---- SEEDED FROM THE STORED VALUES, NOT FROM A DISPLAY FORMATTER (Round 66).
    //
    // THE BUG THIS FIXES. These four boxes used to be filled by `Fmt.shares` and
    // `Fmt.priceBare`, which round to four and to two-or-three decimals. Save then parses the
    // boxes back and `Txn.cashEffect` recomputes the cash from quantity x price whenever both
    // are present - it only falls back to the stored total when one of them is missing. So a
    // 1,000-share buy stored at 1.5595 (derived from a $1,559.50 net total, which is how every
    // imported trade is priced) displayed as "1.560", and opening it and pressing Save with
    // nothing changed rewrote the row as $1,560.00.
    //
    // Fifty cents, silently, on a screen whose whole job is to be the record. `Fmt.exact`
    // round-trips: what is shown parses back to the identical double. See its note.
    var qty by rememberSaveable { mutableStateOf(TxnFields.qty(existing)) }
    var price by rememberSaveable { mutableStateOf(TxnFields.price(existing)) }
    var amount by rememberSaveable { mutableStateOf(TxnFields.amount(existing)) }
    var fees by rememberSaveable { mutableStateOf(TxnFields.fees(existing)) }
    // Once the user types in the Fees box themselves, the app stops filling it in. Editing
    // an existing transaction counts as already-decided, so an import is never overwritten.
    var feesTouched by rememberSaveable { mutableStateOf(existing != null) }
    var date by rememberSaveable { mutableStateOf(Fmt.iso(existing?.date ?: Fmt.todayMs())) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }

    val isTrade = type == TxnType.BUY || type == TxnType.SELL
    /** A split has a symbol and a ratio, and nothing else - see [TxnType.SPLIT]. */
    val isSplit = type == TxnType.SPLIT

    // Parsed here, ahead of `expected` below, because the fee auto-fill needs it too - a
    // backfilled trade dated before Fees.RATES_EFFECTIVE_MS must not be auto-filled at 2026's
    // current rates (see that constant's own note). Save's validation further down reuses the
    // same value.
    val parsedDate = remember(date) { Fmt.parseDate(date) }

    // What Ally would actually charge for this trade, recomputed as the fields change.
    val expected = remember(type, qty, price, parsedDate) {
        Fees.forEquityTrade(type, qty.toNum(), price.toNum(), parsedDate)
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
    val previewCash = TxnFields.cashOf(type, TxnFields.resolve(type, qty, price, amount, fees))

    val problem: String? = when {
        parsedDate == null -> "Enter the date as yyyy-MM-dd (e.g. ${Fmt.iso(Fmt.todayMs())})"
        (isTrade || type == TxnType.DIVIDEND || isSplit) && symbol.isBlank() ->
            "Enter a ticker symbol"
        isSplit && qNum <= 0 ->
            "Enter the split ratio - 10 for a 10-for-1 split, 0.1 for a 1-for-10 reverse split"
        isSplit && kotlin.math.abs(qNum - 1.0) < 1e-9 -> "A ratio of 1 would change nothing"
        isTrade && qNum <= 0 -> "Enter how many shares"
        isTrade && pNum <= 0 && aNum <= 0 -> "Enter a price per share, or the total amount"
        // A split moves no money, so it must not be asked for an amount - and its fee box
        // is not on screen, so a stale value behind it must not block saving either.
        !isTrade && !isSplit && aNum <= 0 -> "Enter an amount"
        // `isTrade`, not `!isSplit`: the fee box is only on screen for a trade now, so a
        // stale negative left behind it on any other type must not block a save it cannot
        // affect - exactly the reasoning the amount check above already applies to SPLIT.
        isTrade && feeNum < 0 -> "Fees can't be negative"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // ---- A TAP OUTSIDE MUST NOT THROW AWAY WHAT WAS TYPED.
        //
        // Material3's default `DialogProperties` dismisses on an outside tap, and this
        // dialog's dismiss path keeps nothing: everything entered is gone, with no
        // confirmation and no undo. Making the fields `rememberSaveable` protected them
        // against PROCESS DEATH; the far more likely loss is a mistap while entering a
        // trade. Back still dismisses, so there is always a deliberate way out.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (existing == null) "Add transaction" else "Edit transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(type)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        TxnType.ALL.forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = {
                                // THE SHARES BOX AND THE SPLIT-RATIO BOX ARE THE SAME `qty`
                                // STATE. Every type already shares its text fields this way
                                // (an "amount" meant for a deposit is still just cash), but a
                                // share count and a ratio are different enough in scale and
                                // meaning that carrying one over as the other invites a real
                                // mistake: switch a "100 shares" BUY to SPLIT and the ratio
                                // box pre-fills with "100" - a proposed hundredfold split.
                                // The live preview text warns before Save either way, but
                                // clearing it here removes the mistake rather than relying on
                                // the user to notice it.
                                if ((type == TxnType.SPLIT) != (it == TxnType.SPLIT)) qty = ""
                                type = it
                                typeMenu = false
                            })
                        }
                    }
                }
                if (isTrade || type == TxnType.DIVIDEND || isSplit) {
                    EditField("Symbol", symbol) { symbol = it.uppercase() }
                }
                if (isSplit) {
                    // The ratio lives in `quantity` - see [TxnType.SPLIT]. No price, no
                    // amount and no fee: a split moves no money and costs nothing.
                    EditField("Split ratio", qty, numeric = true) { qty = it }
                    Text(
                        if (qNum > 0 && kotlin.math.abs(qNum - 1.0) > 1e-9)
                            "Every 1 share you held becomes ${Fmt.shares(qNum)}. Your total " +
                                "cost basis does not change, so the cost per share is divided " +
                                "by the same ratio."
                        else
                            "10 for a 10-for-1 split, 0.1 for a 1-for-10 reverse split.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
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
                    // ---- ONLY A TRADE HAS A FEE THAT THIS APP CAN ACCOUNT FOR.
                    //
                    // The box used to be shown for every non-SPLIT type, but `Txn.cashEffect`
                    // returns a bare +-abs(amount) for DEPOSIT / WITHDRAWAL / DIVIDEND /
                    // INTEREST / FEE and never subtracts `fees` - while `Ledger.fees()` sums
                    // `fees` across ALL rows. So a $50 dividend entered with a $2 fee reported
                    // $2 of fees paid, showed "fees $2.00" on the Activity row, counted into
                    // the fee audit - and left cash overstated by exactly $2, permanently. A
                    // FEE row carrying both `amount` and `fees` double-counted itself.
                    //
                    // Hidden rather than silently dropped at save: a fee on a cash movement
                    // belongs in its own FEE row, which is already a type in this app, and a
                    // box that is not offered cannot lose what someone typed into it.
                    if (isTrade) {
                        EditField("Fees", fees, numeric = true) { fees = it; feesTouched = true }
                    }
                }
                if (isTrade) {
                    Text(
                        expected.explain(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expected.isZero) accentText
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
                            // ---- COMPARED AGAINST THE ANSWER, NOT THE GROSS (Round 66 audit,
                            // DET-6).
                            //
                            // THE BUG THIS FIXES. The test was `qty * price != total`, which
                            // compares a GROSS figure against the total box - and that box is
                            // the NET, fees included, exactly as its own label says. So every
                            // fee-bearing trade tripped it: 100 shares at $1.50 with $5.95 of
                            // commission is a net $155.95, `cashEffect` reproduces $155.95 to
                            // the cent, and the dialog still announced that the total had been
                            // discarded. On sub-$2 holdings every trade carries a fee, so the
                            // warning was on permanently - and a warning that is always on is
                            // one nobody reads when the total really does disagree.
                            (if (isTrade && pNum > 0 && aNum > 0 &&
                                    kotlin.math.abs(kotlin.math.abs(previewCash) - aNum) > 0.005)
                                " - worked out from the price per share, not the total you typed"
                            else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = accentText,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (problem != null) {
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        // Text takes `redText`, never the fill `Red` - Round 66 audit, REG-5.
                        color = redText,
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
                // ONE COMPUTATION, shared with the preview above - see [TxnFields].
                val r = TxnFields.resolve(type, qty, price, amount, fees)
                onSave(
                    Txn(
                        id = existing?.id ?: 0L,
                        type = type,
                        symbol = if (type in TxnType.ACCOUNT_LEVEL) null
                        else symbol.trim().uppercase().ifBlank { null },
                        quantity = r.quantity,
                        price = r.price,
                        amount = TxnFields.cashOf(type, r),
                        fees = r.fees,
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
                    TextButton(onClick = onDelete) { Text("Delete", color = redText) }
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
