package com.tj.portfolio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The OFFLINE REFRESH strip (2026-10-27): what a pull-to-refresh is doing, what is saved, and the
 * refresh LEVEL Tj asked for - first a light refresh ("a button or toggle for a light refresh,
 * which only uses minimal data"), then, 2026-11-20, a level lighter still ("only refreshes current
 * prices of my holdings and watch list and their charts"). One line when idle, a progress bar
 * while a pass runs. The level applies to pulls on the list screens; a stock's own page always
 * refreshes just that stock ([PortfolioViewModel.refreshStock]).
 */
@Composable
fun OfflineStrip(vm: PortfolioViewModel, modifier: Modifier = Modifier) {
    val p by vm.offline.progress.collectAsState()
    val mode by vm.refreshMode.collectAsState()
    LaunchedEffect(Unit) { vm.offline.refreshCacheSize() }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            offlineStatusLine(p, mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        RefreshLevelPicker(mode, onChange = { vm.setRefreshMode(it) })
        if (p.running) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (p.steps == 0) 0f else (p.step.toFloat() / p.steps).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
    }
}

/**
 * The three refresh levels as a segmented choice: Full (everything, saved for offline), Light
 * (prices, charts, indices, headlines) and Prices (holdings + watch list prices and charts only).
 * Shared by the strip above the lists and by Settings.
 */
@Composable
fun RefreshLevelPicker(mode: OfflineSync.Mode, onChange: (OfflineSync.Mode) -> Unit, modifier: Modifier = Modifier) {
    val levels = OfflineSync.Mode.values()
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        levels.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = levels.size),
                label = { Text(refreshLevelName(m), maxLines = 1) }
            )
        }
    }
}

internal fun refreshLevelName(m: OfflineSync.Mode): String = when (m) {
    OfflineSync.Mode.FULL -> "Full"
    OfflineSync.Mode.LIGHT -> "Light"
    OfflineSync.Mode.PRICES -> "Prices"
}

internal fun offlineStatusLine(p: OfflineSync.Progress, mode: OfflineSync.Mode): String = when {
    p.running -> when (p.mode) {
        OfflineSync.Mode.LIGHT -> "Light refresh"
        OfflineSync.Mode.PRICES -> "Prices refresh"
        OfflineSync.Mode.FULL -> "Saving for offline"
    } + ": ${p.phase} (${p.step}/${p.steps})" + if (p.pagesSaved > 0) " - ${p.pagesSaved} pages" else ""
    p.lastSummary.isNotBlank() -> p.lastSummary
    p.cacheBytes > 0 -> "Offline data: " + megabytes(p.cacheBytes) + " saved. " + when (mode) {
        OfflineSync.Mode.LIGHT -> "Pull = light refresh."
        OfflineSync.Mode.PRICES -> "Pull = prices and charts only."
        OfflineSync.Mode.FULL -> "Pull to refresh saves everything."
    }
    else -> when (mode) {
        OfflineSync.Mode.LIGHT -> "Pull = light refresh (prices, charts, headlines)."
        OfflineSync.Mode.PRICES -> "Pull = holdings and watch list prices and charts only."
        OfflineSync.Mode.FULL -> "Pull down to save everything for offline."
    }
}

internal fun megabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
