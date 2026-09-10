package com.tj.portfolio

import com.tj.portfolio.ui.swipeTarget
import com.tj.portfolio.ui.tabTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * SWIPE-TO-CHANGE-TABS, AS ARITHMETIC (Round 63).
 *
 * `SwipeTabUiTest` drives the real gesture. This covers the decision itself, where every
 * interesting case is an edge: the swipe that is really a scroll, the flick that barely
 * moved, the swipe at the end of the bar, and the swipe whose distance and velocity point
 * opposite ways because the finger came back before it lifted.
 */
class SwipeTabTest {

    private val width = 1000f
    private val tabs = 6

    // ------------------------------------------------------------ the direction

    @Test fun `a swipe left goes to the next tab`() {
        assertEquals(3, swipeTarget(2, tabs, dx = -400f, dy = 0f, velocityX = -1200f, widthPx = width))
    }

    @Test fun `a swipe right goes to the previous tab`() {
        assertEquals(1, swipeTarget(2, tabs, dx = 400f, dy = 0f, velocityX = 1200f, widthPx = width))
    }

    @Test fun `right-to-left layouts reverse the direction`() {
        assertEquals(
            3,
            swipeTarget(2, tabs, dx = 400f, dy = 0f, velocityX = 1200f, widthPx = width, rtl = true)
        )
    }

    // -------------------------------------------------------------- the ends

    @Test fun `the first tab does not wrap backwards`() {
        assertEquals(0, swipeTarget(0, tabs, dx = 500f, dy = 0f, velocityX = 1500f, widthPx = width))
    }

    @Test fun `the last tab does not wrap forwards`() {
        assertEquals(5, swipeTarget(5, tabs, dx = -500f, dy = 0f, velocityX = -1500f, widthPx = width))
    }

    // ------------------------------------------------- what is NOT a tab swipe

    @Test fun `a mostly vertical drag is somebody else's gesture`() {
        // A diagonal flick inside a list: 200px across, 400px down. Reading that as a tab
        // change is the single most likely way this feature makes the app worse.
        assertEquals(2, swipeTarget(2, tabs, dx = -200f, dy = -400f, velocityX = -1500f, widthPx = width))
    }

    @Test fun `a short slow drag does nothing`() {
        assertEquals(2, swipeTarget(2, tabs, dx = -60f, dy = 0f, velocityX = -50f, widthPx = width))
    }

    @Test fun `a short fast flick still counts`() {
        // The whole point of the velocity term: a quick flick across an eighth of the screen
        // is a deliberate gesture, and requiring it to travel a fifth of the width would make
        // the feature feel like it did not work.
        assertEquals(3, swipeTarget(2, tabs, dx = -120f, dy = 0f, velocityX = -1800f, widthPx = width))
    }

    @Test fun `a fast flick that barely moved does not count`() {
        // A tap with a jitter can register a high instantaneous velocity over three pixels.
        assertEquals(2, swipeTarget(2, tabs, dx = -6f, dy = 0f, velocityX = -3000f, widthPx = width))
    }

    @Test fun `distance decides the direction, not velocity`() {
        // Dragged well to the left, then flicked back right before lifting: the finger's net
        // travel is what the user did, and following velocity here would move the wrong way.
        assertEquals(3, swipeTarget(2, tabs, dx = -400f, dy = 0f, velocityX = 2000f, widthPx = width))
    }

    // ------------------------------------------------------------ degenerate input

    @Test fun `a zero width changes nothing`() {
        assertEquals(2, swipeTarget(2, tabs, dx = -400f, dy = 0f, velocityX = -1200f, widthPx = 0f))
    }

    @Test fun `a single tab changes nothing`() {
        assertEquals(0, swipeTarget(0, 1, dx = -900f, dy = 0f, velocityX = -2000f, widthPx = width))
    }

    @Test fun `non-finite input changes nothing rather than throwing`() {
        assertEquals(2, swipeTarget(2, tabs, dx = Float.NaN, dy = 0f, velocityX = -1200f, widthPx = width))
        assertEquals(2, swipeTarget(2, tabs, dx = -400f, dy = Float.NaN, velocityX = -1200f, widthPx = width))
        // A non-finite velocity is survivable, so the distance still decides.
        assertEquals(
            3,
            swipeTarget(2, tabs, dx = -400f, dy = 0f, velocityX = Float.POSITIVE_INFINITY, widthPx = width)
        )
    }

    @Test fun `an out-of-range current index still lands inside the bar`() {
        val t = swipeTarget(99, tabs, dx = -400f, dy = 0f, velocityX = -1200f, widthPx = width)
        assertEquals(5, t)
    }

    // ------------------------------------------------------------ the animation

    @Test fun `the transition is built for both directions`() {
        assertNotNull(tabTransition(0, 1))
        assertNotNull(tabTransition(1, 0))
        assertNotNull(tabTransition(3, 3))
    }
}
