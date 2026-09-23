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
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Pull-down-to-refresh wrapper. Every tab is wrapped in one of these, so the gesture
 * works everywhere and always re-fetches live prices.
 *
 * ---- THE STRANDED CIRCLE (Tj, 2026-09-23c, with a screenshot: the pull circle parked next to
 * the portfolio total, "Prices updated just now", nothing refreshing). Material3 1.4.0 hides the
 * indicator after a release in exactly one place: an `animateToHidden()` run INSIDE the list's
 * fling coroutine (`PullToRefreshModifierNode.onRelease`). Anything that cancels that fling - a
 * finger landing on the screen again within the ~300 ms of the hide, the tab-swipe gesture
 * taking the pointer - cancels the hide with it and leaves the circle wherever it had got to.
 * The only other thing that ever moves it is `isRefreshing` CHANGING; a price refresh that had
 * already finished by then (they are quick, which is why it only happened "sometimes") never
 * changes it again, so the circle sat there for good.
 *
 * So this watches for that state from outside - showing, not refreshing, not animating, and no
 * finger on the screen - and after a short grace animates it away. Only a genuinely abandoned
 * indicator ever meets all four: under a finger it is a pull in progress, while animating it is
 * already on its way somewhere, and while refreshing it is meant to be there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable () -> Unit
) {
    var fingerDown by remember { mutableStateOf(false) }
    LaunchedEffect(state, refreshing) {
        System.out.println("DBG effect refreshing=" + refreshing)
        if (refreshing) return@LaunchedEffect
        snapshotFlow { state.distanceFraction > 0f && !state.isAnimating && !fingerDown }
            .collectLatest { stranded ->
                System.out.println("DBG stranded=" + stranded + " finger=" + fingerDown + " anim=" + state.isAnimating)
                if (stranded) {
                    // collectLatest cancels this wait the moment anything changes - a finger
                    // coming down, an animation starting - so only a circle that stayed
                    // abandoned for the whole grace is touched.
                    // Timed on the FRAME clock, not `delay`: the indicator is a drawing, and
                    // the frame clock is what every animation it takes part in runs on (and
                    // what a UI test can drive). A handful of frames, only while stranded.
                    val start = withFrameMillis { it }
                    while (withFrameMillis { it } - start < STRANDED_INDICATOR_GRACE_MS) Unit
                    System.out.println("DBG hiding")
                    state.animateToHidden()
                    System.out.println("DBG hidden " + state.distanceFraction)
                }
            }
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier
            .fillMaxSize()
            // Observed on the Initial pass and never consumed: this only needs to KNOW whether
            // a finger is down, and must not take a single event from the list or the gesture.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        fingerDown = e.changes.any { it.pressed }
                    }
                }
            }
    ) { content() }
}

/** How long a pull indicator may sit abandoned before [Refreshable] puts it away. */
internal const val STRANDED_INDICATOR_GRACE_MS = 300L

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
