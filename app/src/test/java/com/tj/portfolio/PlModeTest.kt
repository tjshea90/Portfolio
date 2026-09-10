package com.tj.portfolio

import com.tj.portfolio.data.PlMode
import com.tj.portfolio.ui.plInline
import com.tj.portfolio.ui.plLead
import com.tj.portfolio.ui.plSub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ROUND 61: the percent/dollar toggle, as pure functions.
 *
 * The whole feature rests on three one-line helpers being used in every place a P/L figure is
 * drawn. That is deliberate: the figure appears on the holding row, on the summary card and
 * on the stock's own page, and **a toggle that reorders two of those three is worse than no
 * toggle at all** - the user would be comparing a dollar figure against a percentage without
 * being told. So the helpers are tested for the property that makes that impossible: whatever
 * the mode, the two halves are always the same two numbers, only swapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlModeTest {

    private val money = 1234.56
    private val pct = 7.89

    @Test
    fun `dollar mode leads with the dollar figure`() {
        assertEquals("+$1,234.56", plLead(PlMode.DOLLAR, money, pct))
        assertEquals("+7.89%", plSub(PlMode.DOLLAR, money, pct))
    }

    @Test
    fun `percent mode leads with the percentage`() {
        assertEquals("+7.89%", plLead(PlMode.PERCENT, money, pct))
        assertEquals("+$1,234.56", plSub(PlMode.PERCENT, money, pct))
    }

    /**
     * THE PROPERTY THAT MATTERS. Switching mode must never change WHICH numbers are shown,
     * only their order - otherwise the toggle is quietly hiding information.
     */
    @Test
    fun `the two modes show the same pair, only swapped`() {
        listOf(0.0, 1.0, -1.0, 1234.56, -98765.43, 0.004, -0.004).forEach { m ->
            listOf(0.0, 5.0, -5.0, 123.4, -0.01).forEach { p ->
                val d = setOf(plLead(PlMode.DOLLAR, m, p), plSub(PlMode.DOLLAR, m, p))
                val c = setOf(plLead(PlMode.PERCENT, m, p), plSub(PlMode.PERCENT, m, p))
                assertEquals("mode changed the CONTENT at money=$m pct=$p", d, c)
            }
        }
    }

    @Test
    fun `negatives keep their sign in both modes`() {
        assertTrue(plLead(PlMode.DOLLAR, -50.0, -2.5).startsWith("-"))
        assertTrue(plSub(PlMode.DOLLAR, -50.0, -2.5).startsWith("-"))
        assertTrue(plLead(PlMode.PERCENT, -50.0, -2.5).startsWith("-"))
        assertTrue(plSub(PlMode.PERCENT, -50.0, -2.5).startsWith("-"))
    }

    @Test
    fun `the inline form is lead then sub in parentheses`() {
        assertEquals("+$1,234.56  (+7.89%)", plInline(PlMode.DOLLAR, money, pct))
        assertEquals("+7.89%  (+$1,234.56)", plInline(PlMode.PERCENT, money, pct))
    }

    // ------------------------------------------------------------------ the enum

    @Test
    fun `flipping twice returns to where it started`() {
        PlMode.entries.forEach { assertEquals(it, it.flipped.flipped) }
        assertEquals(PlMode.PERCENT, PlMode.DOLLAR.flipped)
        assertEquals(PlMode.DOLLAR, PlMode.PERCENT.flipped)
    }

    /**
     * The stored value is read back from a settings row that a restore, a downgrade or a
     * corrupt write could leave as anything at all. It must fall back, never throw - and the
     * fallback must be DOLLAR, which is what the app has always shown.
     */
    @Test
    fun `an unreadable stored value falls back to the app's long-standing default`() {
        assertEquals(PlMode.DOLLAR, PlMode.byName(null))
        assertEquals(PlMode.DOLLAR, PlMode.byName(""))
        assertEquals(PlMode.DOLLAR, PlMode.byName("nonsense"))
        assertEquals(PlMode.DOLLAR, PlMode.byName("PERCENTAGE"))
        assertEquals(PlMode.PERCENT, PlMode.byName("percent"))
        assertEquals(PlMode.PERCENT, PlMode.byName("PERCENT"))
        assertEquals(PlMode.DOLLAR, PlMode.byName("dollar"))
    }

    @Test
    fun `every mode round trips through its stored name`() {
        PlMode.entries.forEach { assertEquals(it, PlMode.byName(it.name)) }
    }
}
