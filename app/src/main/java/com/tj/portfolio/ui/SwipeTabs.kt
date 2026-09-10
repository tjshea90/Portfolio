package com.tj.portfolio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/**
 * SWIPE LEFT AND RIGHT TO CHANGE TABS (Round 63).
 *
 * TJ's request, verbatim: *"make it so I can gesture swipe left and right to change tabs in
 * addition to the tab buttons on the bottom."* Both halves of that sentence matter - the bar
 * is not going anywhere, this is a second way to do the same thing, and the two have to agree
 * about what a tab change means (see the use of `goToTab` in `MainActivity`).
 *
 * ---- WHY THIS IS A GESTURE AND NOT A `HorizontalPager`
 *
 * A pager is the obvious answer and it is the wrong one here. A pager COMPOSES ITS
 * NEIGHBOURS: sitting on Portfolio would build the Watch tab and the Feed tab as well, and
 * those two are not free - each one subscribes to flows, each one reports its own
 * [VisibleScope] and would fight the real one over what the price poll should be fetching,
 * and Settings runs database reads on composition. The whole point of `VisibleScope` (Round
 * 56) is that only the screen you are LOOKING at costs anything, and a pager would quietly
 * undo it. So the tab stays a single composable and only the gesture is new.
 *
 * ---- WHY THE PARENT MAY SAFELY LISTEN
 *
 * This modifier goes on the container that holds the tab body, so every screen inside it sees
 * each pointer event FIRST - Compose dispatches the Main pass from the innermost hit node
 * outwards. A vertical list that has started scrolling has already consumed the drag by the
 * time it arrives here, and the loop below drops any gesture whose changes come back
 * consumed. That is what keeps a diagonal flick inside a list from being read as a tab swipe.
 *
 * Nothing is consumed until the horizontal slop has been passed AND the gesture is decisively
 * horizontal, so a tap, a long-press, a vertical scroll and a pull-to-refresh all behave
 * exactly as they did before.
 */

/** Distance, as a fraction of the width, that a slow drag must cover to count. */
private const val DISTANCE_FRACTION = 0.18f

/** Pixels per second past which a flick counts however short it was. */
private const val FLING_VELOCITY = 900f

/** How much more horizontal than vertical a swipe must be to be read as one. */
private const val HORIZONTAL_BIAS = 1.4f

/**
 * WHAT A COMPLETED SWIPE MEANS - pure, so it can be tested without a touchscreen.
 *
 * Returns the tab to move to, or [current] when the gesture does not qualify. Positive [dx]
 * is a drag to the RIGHT, which reveals what is to the LEFT, i.e. the PREVIOUS tab - the same
 * direction of travel as dragging a sheet of paper.
 *
 * Total by construction: a zero or negative width, a non-finite velocity and an out-of-range
 * current index all return [current] rather than throwing. This runs at the end of a gesture,
 * where an exception is a crash.
 */
internal fun swipeTarget(
    current: Int,
    tabCount: Int,
    dx: Float,
    dy: Float,
    velocityX: Float,
    widthPx: Float,
    rtl: Boolean = false
): Int {
    if (tabCount <= 1 || widthPx <= 0f) return current
    if (!dx.isFinite() || !dy.isFinite()) return current
    val vx = if (velocityX.isFinite()) velocityX else 0f
    // Decisively horizontal, or it is somebody else's gesture.
    if (abs(dx) < abs(dy) * HORIZONTAL_BIAS) return current
    val farEnough = abs(dx) >= widthPx * DISTANCE_FRACTION
    val fastEnough = abs(vx) >= FLING_VELOCITY && abs(dx) > widthPx * 0.04f
    if (!farEnough && !fastEnough) return current
    // Distance decides the direction, not velocity: at the end of a drag-back-and-release the
    // two disagree, and the finger's net travel is what the user actually did.
    val forward = if (rtl) dx > 0f else dx < 0f
    val next = if (forward) current + 1 else current - 1
    // CLAMPED, NOT WRAPPED. Wrapping turns one extra flick at the end of the bar into a jump
    // from Settings to Portfolio, which is six tabs away from where the user thought they
    // were. The bottom bar has no wrap either, and the two must not disagree.
    return next.coerceIn(0, tabCount - 1)
}

/**
 * Attach the swipe.
 *
 * [enabled] is false whenever something is layered over the tab body - a detail screen, the
 * search sheet, the article reader. Those have their own back semantics and their own
 * horizontal gestures (the chart scrubs; the reader's WebView pans), and a swipe there must
 * not tear the layer out from under the user.
 */
@Composable
fun Modifier.swipeBetweenTabs(
    enabled: Boolean,
    current: Int,
    tabCount: Int,
    onSwitch: (Int) -> Unit
): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Read through `rememberUpdatedState` so a tab change does not cancel a gesture that is
    // already in progress - `pointerInput`'s key restarting the handler mid-drag is the same
    // trap the chart's scrub handler documents.
    val liveCurrent by rememberUpdatedState(current)
    val liveCount by rememberUpdatedState(tabCount)
    val liveSwitch by rememberUpdatedState(onSwitch)
    val liveEnabled by rememberUpdatedState(enabled)

    return this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (!liveEnabled) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var claimed = false
            var abandoned = false
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var pointerId = down.id

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                // A SECOND FINGER IS NEVER A TAB SWIPE. It is a pinch on a chart, or the
                // start of one, and reading it as a swipe would change tabs while the user
                // is zooming.
                if (event.changes.count { it.pressed } > 1) { abandoned = true }
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: event.changes.firstOrNull { it.pressed }?.also { pointerId = it.id }
                    ?: break
                if (!change.pressed) break
                if (abandoned) continue
                // Somebody inside took it - a list scrolling, the chart scrubbing, a
                // horizontally scrollable row of chips. Their gesture, not ours.
                if (change.isConsumed) { abandoned = true; continue }

                val d = change.positionChange()
                dx += d.x
                dy += d.y
                tracker.addPosition(change.uptimeMillis, change.position)

                if (!claimed && abs(dx) > slop && abs(dx) > abs(dy) * HORIZONTAL_BIAS) {
                    claimed = true
                }
                // Consumed only once this is definitely a tab swipe, so everything that is
                // not one keeps working untouched.
                if (claimed) change.consume()
            }

            if (abandoned || !claimed || !liveEnabled) return@awaitEachGesture
            val v = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
            val target = swipeTarget(
                current = liveCurrent,
                tabCount = liveCount,
                dx = dx, dy = dy,
                velocityX = v,
                widthPx = size.width.toFloat(),
                rtl = rtl
            )
            if (target != liveCurrent) liveSwitch(target)
        }
    }
}

/**
 * The transition a tab change is drawn with: the new tab slides in from the side the swipe
 * came from, the old one slides out the other way.
 *
 * Applied to BOTH routes on purpose. A tap on the bar and a swipe end in the same place, so
 * showing two different animations for them would make the bar and the gesture feel like
 * different features rather than two ways into one.
 *
 * [SLIDE_MS] is short deliberately - this is navigation, not decoration, and the tab
 * underneath is often a list the user is about to scroll.
 */
private const val SLIDE_MS = 190

@Composable
fun TabSlide(
    tab: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit
) {
    AnimatedContent(
        targetState = tab,
        modifier = modifier,
        transitionSpec = { tabTransition(initialState, targetState) },
        label = "tab"
    ) { t -> content(t) }
}

/** Split out so the direction rule can be read - and tested - on its own. */
internal fun tabTransition(from: Int, to: Int): ContentTransform {
    val forward = to > from
    val enter = slideInHorizontally(tween(SLIDE_MS)) { w -> if (forward) w else -w } +
        fadeIn(tween(SLIDE_MS))
    val exit = slideOutHorizontally(tween(SLIDE_MS)) { w -> if (forward) -w else w } +
        fadeOut(tween(SLIDE_MS))
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        // The two screens are different heights for a frame in the middle of the animation,
        // and letting the container resize to match makes the bottom of the outgoing list
        // visibly jump.
        sizeTransform = SizeTransform(clip = false)
    )
}
