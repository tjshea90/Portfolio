package com.tj.portfolio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tj.portfolio.net.DayTradingParams
import com.tj.portfolio.net.EngineTuning

/** Which confirmation [EngineConfirmDialog] asks - kept as a String so it can be `rememberSaveable`. */
const val ENGINE_UNDO = "undo"
const val ENGINE_REVERT = "revert"

/** "1 graded trade" / "12 graded trades". */
internal fun gradedTrades(n: Int): String = "$n graded trade" + if (n == 1) "" else "s"

/** A text link that expands something - with a button role and its open/closed state for TalkBack (UI-22). */
@Composable
internal fun ExpandLink(text: String, open: Boolean, onToggle: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge, color = accentText,
        modifier = Modifier.minTapTarget()
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button, onClickLabel = if (open) "collapse" else "expand") { onToggle() }
            .padding(vertical = 6.dp)
    )
}

/**
 * "IMPROVE THE ENGINE WITH CLAUDE" (2026-09-24c) - the Day Trading tab's learning loop, under the
 * success card whose graded trades it learns from. Shows which engine is running, how much the
 * sample allows (Tj's rule 1, in the app's own numbers), the two buttons of the round trip, the
 * change history, and the two ways back: undo the last change, or revert to the original. The two
 * confirmations are drawn by the screen ([EngineConfirmDialog]) - see its note.
 */
@Composable
internal fun EngineTuningCard(
    state: EngineTuning.State,
    /** (graded trades of the app's own plans, since the change in force) - null until first counted. */
    evidence: Pair<Int, Int>?,
    onMakePrompt: () -> Unit,
    onImport: () -> Unit,
    onUndo: () -> Unit,
    onRevert: () -> Unit,
    /** The tab is grading new results - the prompt waits for them (UI-7); nothing else needs to. */
    grading: Boolean = false
) {
    var how by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    StatCard {
        Text("IMPROVE THE ENGINE WITH CLAUDE", style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = muted)
        Spacer(Modifier.height(6.dp))
        val differs = state.params.diffFrom(DayTradingParams.DEFAULTS).size
        val changes = state.history.size
        Text(
            when {
                !state.isOriginal -> "Running tuned engine v${state.version} - $differs setting" +
                    (if (differs == 1) " differs" else "s differ") + " from the original."
                changes > 0 -> "Running the original engine again (after $changes engine change" +
                    (if (changes == 1) "" else "s") + ", now v${state.version})."
                else -> "Running the original engine."
            },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(readiness(state, evidence), style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(8.dp))
        // EQUAL HEIGHTS AT ANY FONT SIZE (UI-23): the longer label wraps first.
        Row(Modifier.height(IntrinsicSize.Min)) {
            OutlinedButton(onClick = onMakePrompt, enabled = !grading,
                modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Make tuning prompt") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Import answer") }
        }
        if (grading) Text(
            "Grading new results... the prompt is ready as soon as that finishes.",
            style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp)
        )
        ExpandLink(if (how) "Hide how this works" else "How does this work?", how) { how = !how }
        if (how) Text(
            "\"Make tuning prompt\" opens the share menu - pick Claude. The file holds every graded " +
                "trade (a recommendation that filled and has finished), how the engine works with its " +
                "current settings, every change already made and how each has done since, so Claude needs " +
                "no explanation. Claude writes an answer file; share it back to Portfolio (or use Import " +
                "answer). The app then checks every proposed change itself - against the sample-size rules " +
                "above, each setting's safe range, and the engine as it is now - and shows you what would " +
                "change. Nothing changes until you tap Apply. New plans use the new settings from the next " +
                "refresh; plans already recorded keep the rules they were made with, so the success rate " +
                "stays an honest record. The original engine is built into the app: \"Revert to original\" " +
                "always brings it back exactly.",
            style = MaterialTheme.typography.bodySmall, color = muted
        )
        if (state.history.isNotEmpty()) {
            ExpandLink((if (showHistory) "Hide engine history" else "Engine history") + " (${state.history.size})",
                showHistory) { showHistory = !showHistory }
            if (showHistory) {
                // THE NEWEST TEN, THE REST ON REQUEST (UI-25) - up to 200 entries live in one lazy item.
                val newestFirst = state.history.asReversed()
                (if (showAll) newestFirst else newestFirst.take(HISTORY_SHOWN)).forEach { h -> HistoryLine(h) }
                if (newestFirst.size > HISTORY_SHOWN) ExpandLink(
                    if (showAll) "Show the newest $HISTORY_SHOWN only" else "Show all ${newestFirst.size}", showAll
                ) { showAll = !showAll }
            }
        }
        // EACH BUTTON KEEPS HALF THE ROW (UI-23), so neither is squeezed to a sliver at 2x text.
        Row(Modifier.height(IntrinsicSize.Min)) {
            TextButton(onClick = onUndo, enabled = state.undoable != null,
                modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Undo last change") }
            TextButton(onClick = onRevert, enabled = !state.isOriginal,
                modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Revert to original") }
        }
        // WHY BOTH ARE GREY, said (UI-22).
        if (state.undoable == null && state.isOriginal) Text(
            "Nothing to undo - this is the original engine.",
            style = MaterialTheme.typography.bodySmall, color = muted
        )
    }
}

private const val HISTORY_SHOWN = 10

/** The card's sample-size line - the app's own count, and what it allows (Tj's rule 1). */
internal fun readiness(state: EngineTuning.State, evidence: Pair<Int, Int>?): String {
    val (graded, since) = evidence ?: return "Counting graded trades..."
    val tier = EngineTuning.Tier.of(graded)
    val next = EngineTuning.Tier.next(graded)
    val count = if (graded == 0) "No graded trades from the app's own plans yet"
        else gradedTrades(graded).replaceFirstChar { it.uppercase() } + " from the app's own plans"
    val allows = if (tier == EngineTuning.Tier.NONE)
        (if (graded == 0) " - the engine is not changed until there are ${EngineTuning.Tier.SMALL.minTrades}."
        else ". Claude can review them, but the engine is not changed until there are ${EngineTuning.Tier.SMALL.minTrades}.")
    else ". " + tier.label + "." + (next?.let { " More unlocks at ${it.minTrades}." } ?: "")
    val wait = if (state.lastApplyAt > 0)
        " $since since the change now in force" +
            (if (since < EngineTuning.MIN_TRADES_BETWEEN_CHANGES)
                " - the next change waits for ${EngineTuning.MIN_TRADES_BETWEEN_CHANGES}, so this one can be measured first."
            else ".")
    else ""
    return count + allows + wait
}

@Composable
private fun HistoryLine(h: EngineTuning.HistoryEntry) {
    val day = java.time.Instant.ofEpochMilli(h.at).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Spacer(Modifier.height(4.dp))
    Text(
        "v${h.version} - $day - " + when (h.kind) {
            EngineTuning.KIND_APPLY -> "Claude's changes" + (if (h.undoneAt > 0) " (later taken back)" else "")
            EngineTuning.KIND_UNDO -> "undo"
            else -> "reverted to the original"
        } + " - on ${gradedTrades(h.gradedTrades)}",
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold
    )
    h.changes.forEach { c ->
        Text(
            "${c.key}: ${EngineTuning.describe(c.key, c.from)} -> ${EngineTuning.describe(c.key, c.to)}",
            style = MaterialTheme.typography.bodySmall, color = muted
        )
        // What the setting does, in words (UI-25) - the same line the review sheet shows.
        DayTradingParams.SPEC_BY_KEY[c.key]?.let { Text(it.doc, style = MaterialTheme.typography.labelSmall, color = muted) }
    }
    if (h.kind == EngineTuning.KIND_APPLY && h.summary.isNotBlank()) Text(
        h.summary, style = MaterialTheme.typography.bodySmall, color = muted
    )
}

/**
 * The undo / revert confirmation - drawn at SCREEN level by the Day Trading tab and by Settings
 * (UI-24), never inside a lazy list item where a saved dialog could reappear on its own after a
 * rotation scrolled its item out of view.
 */
@Composable
internal fun EngineConfirmDialog(kind: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val undo = kind == ENGINE_UNDO
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (undo) "Undo the last change?" else "Revert to the original engine?") },
        text = {
            Text(
                if (undo) "The engine goes back to exactly how it was before the most recent change " +
                    "Claude made. Recorded plans and their grades are not touched."
                else "Every change ever made to the day-trading engine is taken back, and it runs " +
                    "exactly as it was originally built. The history is kept, and recorded plans and " +
                    "their grades are not touched."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (undo) "Undo" else "Revert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * THE REVIEW SHEET - Claude's answer after the app's own checks, before anything applies. Every
 * proposed change is listed with what would really happen to it: applied, applied at a smaller
 * step, already set, or refused and why - and the evidence count is the APP's (UI-4).
 */
@Composable
internal fun EngineReviewDialog(
    review: EngineTuning.Review,
    applying: Boolean = false,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claude's engine review") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                val p = review.proposal
                if (p.verdict.isNotBlank()) Text(p.verdict, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (p.analysis.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(p.analysis, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Text(
                    gradedTrades(review.trades).replaceFirstChar { it.uppercase() } + " from the app's own plans. " +
                        review.tier.label + ".",
                    style = MaterialTheme.typography.bodySmall, color = muted
                )
                if (review.blocker.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(review.blocker, style = MaterialTheme.typography.bodySmall, color = redText)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (review.items.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("No changes proposed - Claude recommends keeping the engine as it is.",
                        style = MaterialTheme.typography.bodySmall)
                }
                review.items.forEach { item -> ReviewItem(item) }
                if (p.keep.isNotBlank() || p.watchNext.isNotBlank() || p.codeIdeas.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (p.keep.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Keep as is: ${p.keep}", style = MaterialTheme.typography.bodySmall) }
                if (p.watchNext.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Watch next: ${p.watchNext}", style = MaterialTheme.typography.bodySmall) }
                if (p.nextReviewAfterTrades > 0) Text("Next review worth doing after about ${gradedTrades(p.nextReviewAfterTrades)} more.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                if (p.codeIdeas.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Ideas that would need an app update (not applied):", style = MaterialTheme.typography.labelSmall, color = muted)
                    p.codeIdeas.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Nothing changes until you tap Apply. Plans already recorded keep the rules they were " +
                        "made with. You can undo this or revert to the original engine at any time.",
                    style = MaterialTheme.typography.bodySmall, color = muted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = review.canApply && !applying) {
                Text(when {
                    applying -> "Applying..."
                    review.canApply -> "Apply ${review.applicable.size} change${if (review.applicable.size == 1) "" else "s"}"
                    else -> "Nothing to apply"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !applying) { Text(if (review.canApply) "Not now" else "Close") } }
    )
}

@Composable
private fun ReviewItem(item: EngineTuning.Reviewed) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val c = item.change
    val cur = item.current?.let { EngineTuning.describe(c.key, it) } ?: "?"
    val will = item.status == EngineTuning.Status.ACCEPTED || item.status == EngineTuning.Status.LIMITED
    Spacer(Modifier.height(8.dp))
    // A REFUSED CHANGE DOES NOT LOOK LIKE ONE (UI-14): its heading keeps the current value.
    Text(
        if (will) "${c.key}: $cur -> ${EngineTuning.describe(c.key, item.applied ?: c.to)}"
        else "${c.key}: stays $cur" + (if (item.status == EngineTuning.Status.REFUSED) " (Claude proposed ${EngineTuning.describe(c.key, c.to)})" else ""),
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
    )
    DayTradingParams.SPEC_BY_KEY[c.key]?.let { Text(it.doc, style = MaterialTheme.typography.labelSmall, color = muted) }
    Text(
        when (item.status) {
            EngineTuning.Status.ACCEPTED -> "Will apply."
            EngineTuning.Status.LIMITED -> item.reason
            EngineTuning.Status.UNCHANGED -> item.reason
            EngineTuning.Status.REFUSED -> "Refused: " + item.reason
        },
        style = MaterialTheme.typography.bodySmall,
        color = when (item.status) {
            EngineTuning.Status.REFUSED -> redText
            EngineTuning.Status.UNCHANGED -> muted
            else -> greenText
        }
    )
    // THE APP'S COUNT, not Claude's claim (UI-4) - Claude's shown only when it differs.
    val evidence = item.groupCount?.let { n ->
        "Evidence (${c.basis}): ${gradedTrades(n)} by the app's count" +
            (if (c.evidenceTrades > 0 && c.evidenceTrades != n) " - Claude cited ${c.evidenceTrades}" else "")
    } ?: "Evidence (${c.basis}): not a group the app can count"
    Text(evidence, style = MaterialTheme.typography.bodySmall, color = muted)
    if (c.rationale.isNotBlank()) Text("Why: ${c.rationale}", style = MaterialTheme.typography.bodySmall, color = muted)
    if (c.expectedEffect.isNotBlank()) Text("Expected: ${c.expectedEffect}", style = MaterialTheme.typography.bodySmall, color = muted)
}
