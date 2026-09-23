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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Pull-down-to-refresh wrapper. Every tab is wrapped in one of these, so the gesture
 * works everywhere and always re-fetches live prices.
 *
 * ---- OUR OWN GESTURE, MATERIAL3'S DRAWING (Tj, 2026-09-23c and d).
 *
 * The first report was a pull circle parked next to the portfolio total; v7.38 added a
 * "put away an abandoned circle" watchdog, and the second report - a screen recording - showed
 * why that could never be enough: the circle sits at the threshold WHILE the holdings list is
 * scrolled up and down under it, nowhere near the top.
 *
 * That is Material3 1.4.0's own pull logic (`PullToRefreshModifierNode`). Its `onPostScroll`
 * runs for EVERY user scroll event, not just an overscroll at the top of the list, and re-snaps
 * the circle to `verticalOffset / threshold` each time - with `verticalOffset` taken from a
 * private "distance pulled" that its own animations leave at the full threshold when a hide and
 * a show race each other around a quick refresh. From then on every ordinary scroll puts the
 * circle back, and nothing outside the library can reset that private value. Hiding it from
 * outside (v7.38) lasted only until the next scroll.
 *
 * So the gesture is ours now ([PullGesture]): it moves the circle ONLY for a real overscroll
 * past the top of the list, and the hide after a release runs on this composable's own scope,
 * where no touch or fling can cancel it. Material3 still draws the circle
 * ([PullToRefreshDefaults.Indicator]) from a plain [PullToRefreshState], so it looks and behaves
 * exactly as before - same 80dp threshold, same half-speed drag, same stretch past the threshold.
 *
 * The v7.38 watchdog stays as a backstop: showing, not refreshing, not animating and no finger
 * down for a moment means abandoned, whatever the cause - and it now also clears the gesture's
 * own distance, which is precisely what Material3 never let us do.
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
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    val latestRefreshing by rememberUpdatedState(refreshing)
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    // True while one of OUR animations of the circle is in flight - see `drawn` below.
    var settling by remember { mutableStateOf(false) }
    val gesture = remember(state, scope, thresholdPx) {
        PullGesture(
            thresholdPx = thresholdPx,
            refreshing = { latestRefreshing },
            onRefresh = { latestOnRefresh() },
            // The value is read when the snap RUNS, not when it was queued, and never while a
            // refresh owns the circle: a snap queued by the last scroll event before a release
            // must not undo the release's hide - or, worse, knock down a spinning refresh
            // circle. That kind of stale write is exactly the race that broke Material3's own.
            show = { current ->
                scope.launch { if (!latestRefreshing) state.snapTo(current()) }
            },
            hide = {
                scope.launch {
                    settling = true
                    try { state.animateToHidden() } finally { settling = false }
                }
            }
        )
    }
    var fingerDown by remember { mutableStateOf(false) }

    // A refresh starting parks the circle at the threshold and spins it; one ending puts it
    // away. Keyed on `refreshing`, so a change cancels whichever animation the last one began.
    LaunchedEffect(state, refreshing) {
        settling = true
        try {
            if (refreshing) {
                gesture.reset()
                state.animateToThreshold()
            } else if (!fingerDown || gesture.fraction() == 0f) {
                state.animateToHidden()
            }
        } finally {
            settling = false
        }
    }

    // ---- THE BACKSTOP (v7.38, kept). See the header.
    LaunchedEffect(state, refreshing) {
        if (refreshing) return@LaunchedEffect
        val effect = this
        snapshotFlow { state.distanceFraction > 0f && !state.isAnimating && !fingerDown }
            .collectLatest { stranded ->
                if (stranded) {
                    // collectLatest cancels this wait the moment anything changes - a finger
                    // coming down, an animation starting - so only a circle that stayed
                    // abandoned for the whole grace is touched. Timed on the frame clock, which
                    // is what every animation here runs on (and what a UI test can drive).
                    val start = withFrameMillis { it }
                    while (withFrameMillis { it } - start < STRANDED_INDICATOR_GRACE_MS) Unit
                    gesture.reset()
                    // LAUNCHED OUTSIDE collectLatest: the hide starting makes "stranded" false,
                    // and collectLatest would cancel the very block running it.
                    effect.launch {
                        settling = true
                        try { state.animateToHidden() } finally { settling = false }
                    }
                }
            }
    }

    // ---- THE INVARIANT, ENFORCED WHERE IT IS DRAWN (2026-09-23d). The recording showed the
    // circle frozen at the threshold, idle arrow, unmoved by any scroll - and the only way
    // Material3's own logic ignores every scroll is while it believes an animation is RUNNING,
    // which also blocked v7.38's watchdog ("not animating"). Whatever the animation state
    // claims, the circle may only be seen while a refresh is running, a finger is actually
    // pulling past the top, or one of our own show/hide animations is in flight. Everything
    // else draws nothing. Read inside the indicator's graphics layer, so it costs a redraw of
    // the circle, never a recomposition of the screen.
    val drawn = remember(state, gesture) {
        object : PullToRefreshState by state {
            override val distanceFraction: Float
                get() = if (latestRefreshing || gesture.distance > 0f || settling)
                    state.distanceFraction else 0f
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(gesture)
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
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = drawn,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/** How long a pull indicator may sit abandoned before [Refreshable] puts it away. */
internal const val STRANDED_INDICATOR_GRACE_MS = 300L

/**
 * The pull gesture behind [Refreshable], as plain nested-scroll arithmetic - no Compose state of
 * its own, so a unit test can drive it event by event.
 *
 * The rules, and the one that differs from Material3's:
 *  - it GROWS only from what the list could not scroll - `available.y > 0` after the list has
 *    had its turn, i.e. a pull past the top. Material3 re-derived its circle on every user
 *    scroll, including mid-list ones with nothing left over, which is how a stale internal
 *    distance kept putting the circle back (see [Refreshable]'s header);
 *  - scrolling back up SHRINKS it first, before the list moves, so a pull can be taken back;
 *  - releasing past the threshold refreshes; releasing anywhere puts the circle away, and the
 *    distance is zeroed on the spot rather than inside an animation that could be cancelled;
 *  - while a refresh is running it neither grows nor shrinks: the circle is parked, spinning.
 * Distances are Material3's: half-speed drag, 80dp threshold, and a tapering stretch past it.
 */
internal class PullGesture(
    private val thresholdPx: Float,
    private val refreshing: () -> Boolean,
    private val onRefresh: () -> Unit,
    private val show: (current: () -> Float) -> Unit,
    private val hide: () -> Unit
) : NestedScrollConnection {

    /**
     * Raw finger travel past the top, in px. Snapshot state, because [Refreshable] reads it
     * where the circle is drawn: a circle may only show while this is above zero (or a refresh,
     * or one of its own animations, is running).
     */
    var distance by mutableFloatStateOf(0f)
        private set

    /** Where the circle belongs: 0 hidden, 1 at the threshold, above 1 stretched past it. */
    fun fraction(): Float {
        val adjusted = distance * DRAG_MULTIPLIER
        if (adjusted <= thresholdPx) return adjusted / thresholdPx
        val linear = (adjusted / thresholdPx - 1f).coerceIn(0f, 2f)
        return 1f + linear - linear * linear / 4f
    }

    fun reset() { distance = 0f }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || refreshing()) return Offset.Zero
        if (available.y >= 0f || distance <= 0f) return Offset.Zero
        val taken = maxOf(available.y, -distance)
        distance += taken
        show(::fraction)
        return Offset(0f, taken)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || refreshing()) return Offset.Zero
        if (available.y <= 0f) return Offset.Zero
        distance += available.y
        show(::fraction)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (distance <= 0f) return Velocity.Zero
        val trigger = !refreshing() && distance * DRAG_MULTIPLIER > thresholdPx
        distance = 0f
        if (trigger) onRefresh()
        // Not awaited here: the fling is cancelled by the next touch, and a hide tied to it was
        // cancelled with it - Material3's original bug. The refresh starting takes over from
        // here (it parks the circle at the threshold); if it never starts, this finishes.
        hide()
        // A downward fling that ends a pull is the pull's, not the list's.
        return if (available.y > 0f) available else Velocity.Zero
    }

    companion object {
        /** Material3's own: the circle travels half as far as the finger. */
        const val DRAG_MULTIPLIER = 0.5f
    }
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
