package com.tj.portfolio

import com.tj.portfolio.data.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PINCH-ZOOM LADDER (Round 63).
 *
 * TJ asked for a pinch that goes "all the way down to 5 minute" and back out "all the way to
 * the stock's all time chart", so the two things worth proving are that the ends of the
 * ladder ARE those two charts, and that walking off either end is a no-op rather than a
 * wrap-around or a crash.
 */
class ChartZoomTest {

    @Test fun `the ladder runs from all-time down to the five-minute chart`() {
        val l = ChartRange.ZOOM_LADDER
        assertEquals("the widest rung must be the all-time chart", ChartRange.MAX, l.first())
        assertEquals("the narrowest rung must be the 1D chart", ChartRange.D1, l.last())
        // The request was for five-minute candles at full zoom, and that is a property of D1
        // rather than of this list - so assert it here, where a change to either would fail.
        assertEquals("5m", ChartRange.D1.interval)
    }

    @Test fun `the ladder is every range except the after-hours view`() {
        val onLadder = ChartRange.ZOOM_LADDER.toSet()
        val expected = ChartRange.entries.toSet() - ChartRange.OVERNIGHT
        assertEquals(expected, onLadder)
        assertEquals("no rung may appear twice", ChartRange.ZOOM_LADDER.size, onLadder.size)
    }

    @Test fun `the ladder is ordered widest to narrowest by the window it covers`() {
        // A crude but sufficient proxy for "how much time": the ranges are named after their
        // windows, so assert the intended order literally rather than deriving it.
        assertEquals(
            listOf(
                ChartRange.MAX, ChartRange.Y5, ChartRange.Y1, ChartRange.M6,
                ChartRange.M1, ChartRange.D5, ChartRange.D1
            ),
            ChartRange.ZOOM_LADDER
        )
    }

    // ----------------------------------------------------------------- stepping

    @Test fun `spreading one rung zooms in`() {
        assertEquals(ChartRange.M1, ChartRange.zoomed(ChartRange.M6, 1))
    }

    @Test fun `pinching one rung zooms out`() {
        assertEquals(ChartRange.Y1, ChartRange.zoomed(ChartRange.M6, -1))
    }

    @Test fun `a fast spread can cross several rungs at once`() {
        assertEquals(ChartRange.D5, ChartRange.zoomed(ChartRange.Y1, 3))
    }

    @Test fun `zooming past the bottom stops at the five-minute chart`() {
        assertEquals(ChartRange.D1, ChartRange.zoomed(ChartRange.M1, 99))
    }

    @Test fun `zooming past the top stops at the all-time chart`() {
        assertEquals(ChartRange.MAX, ChartRange.zoomed(ChartRange.M1, -99))
    }

    @Test fun `already at the end returns null rather than the same range`() {
        // Null is "nothing happened", and the caller uses it to avoid writing the setting and
        // restarting the chart's effects on every frame of a spread held at the bottom.
        assertNull(ChartRange.zoomed(ChartRange.D1, 1))
        assertNull(ChartRange.zoomed(ChartRange.MAX, -1))
    }

    @Test fun `a zero step is not a zoom`() {
        assertNull(ChartRange.zoomed(ChartRange.M1, 0))
    }

    @Test fun `the after-hours view is not on the ladder and does not guess`() {
        assertNull(ChartRange.zoomed(ChartRange.OVERNIGHT, 1))
        assertNull(ChartRange.zoomed(ChartRange.OVERNIGHT, -1))
    }

    @Test fun `walking the whole ladder in single steps reaches both ends`() {
        var r = ChartRange.MAX
        var steps = 0
        while (true) {
            r = ChartRange.zoomed(r, 1) ?: break
            steps++
            assertTrue("the ladder does not terminate", steps < 50)
        }
        assertEquals(ChartRange.D1, r)
        assertEquals(ChartRange.ZOOM_LADDER.size - 1, steps)

        var back = 0
        while (true) {
            r = ChartRange.zoomed(r, -1) ?: break
            back++
            assertTrue("the ladder does not terminate", back < 50)
        }
        assertEquals(ChartRange.MAX, r)
        assertEquals(steps, back)
    }
}
