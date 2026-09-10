package com.tj.portfolio

import com.tj.portfolio.ui.spinnerShouldShow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull-to-refresh spinner that turned for the rest of the session.
 *
 * TJ reported it with a screenshot: the feed said "Updated 12s ago" while the spinner kept
 * going. The cause was a flag cleared by hand in two `finally` blocks, one of which only
 * cleared it when ITS OWN call had been the manual one:
 *
 *     if (_feedLoading.value) {                       // a feed pass is already running
 *         if (manual) ...copy(manualRefresh = true)   // attach the user's spinner to it
 *         return
 *     }
 *     ...
 *     } finally {
 *         if (manual) ...copy(manualRefresh = false)  // but THAT call had manual = false
 *     }
 *
 * so the spinner was never turned off again. The fix was to stop clearing it by hand and
 * derive it instead, which is the rule this file pins down: **the indicator may only be on
 * while work is actually happening.**
 */
class SpinnerTest {

    @Test
    fun `the spinner shows while the user asked and work is running`() {
        assertTrue(spinnerShouldShow(userAsked = true, quotesLoading = true, feedLoading = false))
        assertTrue(spinnerShouldShow(userAsked = true, quotesLoading = false, feedLoading = true))
        assertTrue(spinnerShouldShow(userAsked = true, quotesLoading = true, feedLoading = true))
    }

    @Test
    fun `THE BUG - the spinner cannot outlive the work that was running`() {
        // This is the exact state TJ's screenshot was in: the user had pulled, the feed pass
        // it attached to had finished, and nothing was loading any more. The old code left
        // the flag true here forever.
        assertFalse(spinnerShouldShow(userAsked = true, quotesLoading = false, feedLoading = false))
        // ROUND 66: a third kind of work. Pulling down on the Research tab starts an
        // ~18-request build with quotes and feed both idle, and the polling loop re-derives
        // this on every tick - so without this term the indicator was retracted after fifteen
        // seconds while the build it was reporting still had a minute to run.
        assertTrue(spinnerShouldShow(
            userAsked = true, quotesLoading = false, feedLoading = false, researchLoading = true
        ))
        assertFalse("still nothing to show for a build nobody asked for", spinnerShouldShow(
            userAsked = false, quotesLoading = false, feedLoading = false, researchLoading = true
        ))
    }

    @Test
    fun `the spinner does not show for automatic work the user did not ask for`() {
        // The 3-minute feed tick and the quote poll must not flash a spinner at somebody who
        // is just reading the screen.
        assertFalse(spinnerShouldShow(userAsked = false, quotesLoading = true, feedLoading = true))
        assertFalse(spinnerShouldShow(userAsked = false, quotesLoading = false, feedLoading = true))
    }

    @Test
    fun `the spinner survives one of two operations finishing`() {
        // The mirror-image bug, also fixed: `refresh()` used to blank the flag
        // unconditionally, so a quote refresh finishing snatched the spinner away from a feed
        // pass the user's pull was actually waiting on. The pull covers BOTH.
        assertTrue(spinnerShouldShow(userAsked = true, quotesLoading = false, feedLoading = true))
        assertTrue(spinnerShouldShow(userAsked = true, quotesLoading = true, feedLoading = false))
    }

    @Test
    fun `nothing asked and nothing running means no spinner`() {
        assertFalse(spinnerShouldShow(userAsked = false, quotesLoading = false, feedLoading = false))
    }

    @Test
    fun `the rule is exactly asked AND busy`() {
        // Exhaustive - there are only eight states and none of them is worth guessing at.
        for (asked in listOf(true, false)) {
            for (q in listOf(true, false)) {
                for (f in listOf(true, false)) {
                    val expected = asked && (q || f)
                    org.junit.Assert.assertEquals(
                        "asked=$asked quotes=$q feed=$f",
                        expected, spinnerShouldShow(asked, q, f)
                    )
                }
            }
        }
    }
}
