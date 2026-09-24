package com.tj.portfolio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tj.portfolio.net.DayTradingParams
import com.tj.portfolio.net.EngineTuning

/**
 * "IMPROVE THE ENGINE WITH CLAUDE" (2026-09-24c) - the Day Trading tab's learning loop, under the
 * success card whose graded trades it learns from. Shows which engine is running, how much the
 * sample allows (Tj's rule 1, in the app's own numbers), the two buttons of the round trip, the
 * full change history, and the two ways back: undo the last change, or revert to the original.
 */
@Composable
internal fun EngineTuningCard(
    state: EngineTuning.State,
    graded: Int,
    sinceLastChange: Int,
    onMakePrompt: () -> Unit,
    onImport: () -> Unit,
    onUndo: () -> Unit,
    onRevert: () -> Unit,
    busy: Boolean = false
) {
    var how by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf("") }   // "", "undo", "revert"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    StatCard {
        Text("IMPROVE THE ENGINE WITH CLAUDE", style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = muted)
        Spacer(Modifier.height(6.dp))
        val differs = state.params.diffFrom(DayTradingParams.DEFAULTS).size
        Text(
            if (state.isOriginal) "Running the original engine" + (if (state.version > 0) " (v${state.version})." else ".")
            else "Running tuned engine v${state.version} - $differs setting" +
                (if (differs == 1) " differs" else "s differ") + " from the original.",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        val tier = EngineTuning.Tier.of(graded)
        val next = EngineTuning.Tier.next(graded)
        Text(
            "$graded graded trade${if (graded == 1) "" else "s"} from the app's own plans. " +
                (if (tier == EngineTuning.Tier.NONE)
                    "Claude can review them now, but the engine is not changed until there are ${EngineTuning.Tier.SMALL.minTrades}."
                else tier.label + ".") +
                (next?.takeIf { tier != EngineTuning.Tier.NONE }?.let { " More unlocks at ${it.minTrades}." } ?: "") +
                (if (state.lastApplyAt > 0)
                    " $sinceLastChange since the last change" +
                        (if (sinceLastChange < EngineTuning.MIN_TRADES_BETWEEN_CHANGES)
                            " - the next change waits for ${EngineTuning.MIN_TRADES_BETWEEN_CHANGES}, so this one can be measured."
                        else ".")
                else ""),
            style = MaterialTheme.typography.bodySmall, color = muted
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = onMakePrompt, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Make tuning prompt")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Import answer")
            }
        }
        Text(
            if (how) "Hide how this works" else "How does this work?",
            style = MaterialTheme.typography.labelLarge, color = accentText,
            modifier = Modifier.minTapTarget().clickable { how = !how }.padding(vertical = 6.dp)
        )
        if (how) Text(
            "\"Make tuning prompt\" opens the share menu - pick Claude. The file holds every graded " +
                "trade, how the engine works with its current settings, every change already made and " +
                "how each has done since, so Claude needs no explanation. Claude writes an answer file; " +
                "share it back to Portfolio (or use Import answer). The app then checks every proposed " +
                "change itself - against the sample-size rules above, each setting's safe range, and the " +
                "engine as it is now - and shows you what would change. Nothing changes until you tap " +
                "Apply. New plans use the new settings from the next refresh; plans already recorded keep " +
                "the rules they were made with, so the success rate stays an honest record. The original " +
                "engine is built into the app: \"Revert to original\" always brings it back.",
            style = MaterialTheme.typography.bodySmall, color = muted
        )
        if (state.history.isNotEmpty()) {
            Text(
                (if (showHistory) "Hide engine history" else "Engine history") + " (${state.history.size})",
                style = MaterialTheme.typography.labelLarge, color = accentText,
                modifier = Modifier.minTapTarget().clickable { showHistory = !showHistory }.padding(vertical = 6.dp)
            )
            if (showHistory) state.history.asReversed().forEach { h -> HistoryLine(h) }
        }
        Row {
            TextButton(onClick = { confirm = "undo" }, enabled = !busy && state.undoable != null) { Text("Undo last change") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirm = "revert" }, enabled = !busy && !state.isOriginal) { Text("Revert to original") }
        }
    }
    if (confirm.isNotEmpty()) {
        val undo = confirm == "undo"
        AlertDialog(
            onDismissRequest = { confirm = "" },
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
            confirmButton = {
                TextButton(onClick = { confirm = ""; if (undo) onUndo() else onRevert() }) {
                    Text(if (undo) "Undo" else "Revert")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HistoryLine(h: EngineTuning.HistoryEntry) {
    val day = java.time.Instant.ofEpochMilli(h.at).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
    Spacer(Modifier.height(4.dp))
    Text(
        "v${h.version} - $day - " + when (h.kind) {
            EngineTuning.KIND_APPLY -> "Claude's changes" + (if (h.undoneAt > 0) " (later undone)" else "")
            EngineTuning.KIND_UNDO -> "undo"
            else -> "reverted to the original"
        } + " - on ${h.gradedTrades} graded trades",
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold
    )
    h.changes.forEach { c ->
        Text(
            "${c.key}: ${EngineTuning.describe(c.key, c.from)} -> ${EngineTuning.describe(c.key, c.to)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (h.kind == EngineTuning.KIND_APPLY && h.summary.isNotBlank()) Text(
        h.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * THE REVIEW SHEET - Claude's answer after the app's own checks, before anything applies. Every
 * proposed change is listed with what would really happen to it: applied, limited to a smaller
 * step, already set, or refused and why.
 */
@Composable
internal fun EngineReviewDialog(
    review: EngineTuning.Review,
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
                    "${review.trades} graded trades from the app's own plans - ${review.tier.label.replaceFirstChar { it.lowercase() }}.",
                    style = MaterialTheme.typography.labelSmall, color = muted
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
                review.items.forEach { item ->
                    Spacer(Modifier.height(8.dp))
                    val c = item.change
                    val cur = item.current?.let { EngineTuning.describe(c.key, it) } ?: "?"
                    val to = EngineTuning.describe(c.key, item.applied ?: c.to)
                    Text("${c.key}: $cur -> $to", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    DayTradingParams.SPEC_BY_KEY[c.key]?.let {
                        Text(it.doc, style = MaterialTheme.typography.labelSmall, color = muted)
                    }
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
                            EngineTuning.Status.LIMITED -> muted
                            else -> greenText
                        }
                    )
                    if (c.rationale.isNotBlank()) Text("Why (${c.basis}, ${c.evidenceTrades} trades): ${c.rationale}",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    if (c.expectedEffect.isNotBlank()) Text("Expected: ${c.expectedEffect}",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (p.keep.isNotBlank() || p.watchNext.isNotBlank() || p.codeIdeas.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (p.keep.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Keep as is: ${p.keep}", style = MaterialTheme.typography.bodySmall) }
                if (p.watchNext.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Watch next: ${p.watchNext}", style = MaterialTheme.typography.bodySmall) }
                if (p.nextReviewAfterTrades > 0) Text("Next review worth doing after about ${p.nextReviewAfterTrades} more graded trades.",
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
                    style = MaterialTheme.typography.labelSmall, color = muted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = review.canApply) {
                Text(if (review.canApply) "Apply ${review.applicable.size} change${if (review.applicable.size == 1) "" else "s"}" else "Nothing to apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (review.canApply) "Not now" else "Close") } }
    )
}
