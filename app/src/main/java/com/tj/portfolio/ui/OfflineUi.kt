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
import androidx.compose.material3.Switch
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
 * LIGHT REFRESH switch Tj asked for ("a button or toggle for a light refresh, which only uses
 * minimal data"). One line when idle, a progress bar while a pass runs.
 */
@Composable
fun OfflineStrip(vm: PortfolioViewModel, modifier: Modifier = Modifier) {
    val p by vm.offline.progress.collectAsState()
    val light by vm.lightRefresh.collectAsState()
    LaunchedEffect(Unit) { vm.offline.refreshCacheSize() }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    offlineStatusLine(p, light),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("Light", style = MaterialTheme.typography.labelMedium)
            Switch(checked = light, onCheckedChange = { vm.setLightRefresh(it) })
        }
        if (p.running) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (p.steps == 0) 0f else (p.step.toFloat() / p.steps).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
    }
}

internal fun offlineStatusLine(p: OfflineSync.Progress, light: Boolean): String = when {
    p.running -> (if (p.mode == OfflineSync.Mode.LIGHT) "Light refresh" else "Saving for offline") +
        ": ${p.phase} (${p.step}/${p.steps})" + if (p.pagesSaved > 0) " - ${p.pagesSaved} pages" else ""
    p.lastSummary.isNotBlank() -> p.lastSummary
    p.cacheBytes > 0 -> "Offline data: " + megabytes(p.cacheBytes) + " saved. " +
        if (light) "Pull = light refresh." else "Pull to refresh saves everything."
    else -> if (light) "Pull = light refresh (prices, charts, headlines)." else "Pull down to save everything for offline."
}

internal fun megabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
