package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Pull-down-to-refresh wrapper. Every tab is wrapped in one of these, so the gesture
 * works everywhere and always re-fetches live prices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) { content() }
}

/**
 * Android's minimum touch target, applied to a control Compose will not size for us.
 *
 * Material3's own components (Button, IconButton, Checkbox, Switch) get 48dp automatically
 * through `minimumInteractiveComponentSize`. A HAND-ROLLED tappable - a `Box` or a `Text`
 * with `.clickable {}` on it - gets nothing, and ends up exactly as big as its text plus
 * padding. Measured under test, the "News" chip on every holding row came out **33dp tall**,
 * and it sits inside the row's own click area, so a near-miss opens the detail screen instead
 * of the news. The comment at that call site says a previous round raised it "for the 48dp
 * minimum"; it went from 24dp to 33dp and nothing checked.
 *
 * Put this OUTERMOST in the chain, before any background, so the painted surface fills the
 * whole target rather than leaving an invisible margin that still swallows taps.
 */
fun Modifier.minTapTarget(min: Int = 48): Modifier = this.defaultMinSize(min.dp, min.dp)

/**
 * Larger tap target for the header actions (back, refresh, sort, search).
 * 30dp glyph inside a 52dp circle - comfortably bigger than the Material default.
 */
@Composable
fun BigIconButton(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    filled: Boolean = true,
    size: Int = 52,
    iconSize: Int = 28,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) {
        Box(
            Modifier
                .size((size - 6).dp)
                .background(
                    if (filled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Icon(
                icon,
                label,
                Modifier.size(iconSize.dp),
                tint = tint ?: MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
