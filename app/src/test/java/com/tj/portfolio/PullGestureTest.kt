package com.tj.portfolio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.tj.portfolio.ui.PullGesture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PULL GESTURE, EVENT BY EVENT (Tj, 2026-09-23d - "still getting stuck", with a recording of
 * the circle parked at the threshold while he scrolled the holdings list up and down).
 *
 * Material3's pull logic re-derived the circle on EVERY user scroll, mid-list included, from a
 * private distance its own animations could leave at the full threshold - so after one unlucky
 * refresh every ordinary scroll put the circle back. [PullGesture] replaces it; the first test
 * is that recording, in numbers.
 */
class PullGestureTest {

    private val threshold = 200f          // px; the circle reaches it at 400 px of finger travel
    private val user = NestedScrollSource.UserInput
    private var refreshing = false
    private var refreshes = 0
    private var shown = 0f
    private var hides = 0

    private val g = PullGesture(
        thresholdPx = threshold,
        refreshing = { refreshing },
        onRefresh = { refreshes++ },
        show = { current -> shown = current() },
        hide = { hides++; shown = 0f }
    )

    /** A list that scrolled everything it was offered - the middle of the holdings list. */
    private fun midListScroll(dy: Float) {
        g.onPreScroll(Offset(0f, dy), user)
        g.onPostScroll(consumed = Offset(0f, dy), available = Offset.Zero, source = user)
    }

    /** A list already at its top, handing the whole downward drag back. */
    private fun overscrollAtTop(dy: Float) {
        g.onPreScroll(Offset(0f, dy), user)
        g.onPostScroll(consumed = Offset.Zero, available = Offset(0f, dy), source = user)
    }

    private fun release(vy: Float = 0f) = runBlocking { g.onPreFling(Velocity(0f, vy)) }

    @Test fun `scrolling the middle of the list never moves the circle - the recording`() {
        repeat(40) { i -> midListScroll(if (i % 2 == 0) 60f else -60f) }
        assertEquals(0f, g.distance, 0f)
        assertEquals(0f, shown, 0f)
        assertEquals(0, refreshes)
    }

    @Test fun `a real pull past the top refreshes once, and the release puts the circle away`() {
        repeat(10) { overscrollAtTop(50f) }          // 500 px of finger = 250 px of circle
        assertTrue("circle past the threshold while pulling", shown > 1f)
        release()
        assertEquals(1, refreshes)
        assertEquals("the distance is zeroed on release, not inside an animation", 0f, g.distance, 0f)
        assertEquals(1, hides)
        // And the list scrolled afterwards leaves it alone.
        repeat(20) { midListScroll(-80f) }
        assertEquals(0f, shown, 0f)
    }

    @Test fun `a short pull goes away without refreshing`() {
        repeat(4) { overscrollAtTop(50f) }           // 200 px of finger = half the threshold
        assertEquals(0.5f, shown, 1e-4f)
        release()
        assertEquals(0, refreshes)
        assertEquals(0f, g.distance, 0f)
    }

    @Test fun `scrolling back up takes the pull back before the list moves`() {
        repeat(4) { overscrollAtTop(50f) }
        val taken = g.onPreScroll(Offset(0f, -150f), user)
        assertEquals(-150f, taken.y, 0f)
        assertEquals(50f, g.distance, 0f)
        // Only what is left of the pull is taken; the rest goes to the list.
        assertEquals(-50f, g.onPreScroll(Offset(0f, -300f), user).y, 0f)
        assertEquals(0f, g.distance, 0f)
    }

    @Test fun `nothing moves while a refresh is running`() {
        refreshing = true
        repeat(10) { overscrollAtTop(50f) }
        assertEquals(0f, g.distance, 0f)
        release()
        assertEquals(0, refreshes)
    }

    @Test fun `the circle stretches past the threshold at Material3's tapering rate`() {
        repeat(8) { overscrollAtTop(50f) }           // exactly the threshold
        assertEquals(1f, g.fraction(), 1e-4f)
        repeat(8) { overscrollAtTop(50f) }           // double: 100% overshoot -> 1 + 1 - 1/4
        assertEquals(1.75f, g.fraction(), 1e-4f)
    }
}
