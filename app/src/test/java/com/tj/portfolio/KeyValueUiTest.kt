package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.ui.KeyValue
import com.tj.portfolio.ui.PortfolioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `KeyValue`, MEASURED AT EVERY FONT SCALE (Round 63, sweep 3).
 *
 * This composable backs the "Your position" card, the portfolio summary and half the stat
 * cards in the app, and it has now failed in BOTH directions - first losing the value, then,
 * after the fix, losing the label. Both failures were invisible to review and obvious to a
 * measurement, so this is the measurement.
 *
 * The property asserted is the one that matters and is not a matter of taste: **both halves
 * are on screen with a non-zero width, at every font scale Android offers, with the longest
 * label and value the app can actually produce.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyValueUiTest {

    @get:Rule val rule = createComposeRule()

    /** The real worst case: the widest label and the widest value the app produces. */
    private val LABEL = "Market value  (45.1234 x \$230.115)"
    private val VALUE = "+\$1,234,567.89  (+1234.56%)"

    /** The narrowest card KeyValue is used in: 411dp less the screen and card padding. */
    private val CARD = 351

    private fun show(scale: Float, label: String = LABEL, value: String = VALUE) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                PortfolioTheme(dark = false) {
                    Box(Modifier.width(CARD.dp)) {
                        Column(Modifier.fillMaxWidth()) { KeyValue(label, value) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun widthOf(text: String): Float {
        val b = rule.onNodeWithText(text, substring = true).getUnclippedBoundsInRoot()
        return (b.right - b.left).value
    }

    private fun assertBothSurvive(scale: Float) {
        show(scale)
        rule.onNodeWithText(LABEL, substring = true).assertIsDisplayed()
        rule.onNodeWithText(VALUE, substring = true).assertIsDisplayed()
        val l = widthOf(LABEL)
        val v = widthOf(VALUE)
        assertTrue("at ${scale}x the LABEL was measured at ${l}dp", l > 24f)
        assertTrue("at ${scale}x the VALUE was measured at ${v}dp", v > 24f)
        assertTrue("at ${scale}x nothing exceeded the card: label $l value $v", l <= CARD + 1)
    }

    @Test fun `both halves survive at the default scale`() = assertBothSurvive(1.0f)

    /** 1.15x is the first step above default - where the ORIGINAL bug bit. */
    @Test fun `both halves survive at 1_15x`() = assertBothSurvive(1.15f)

    @Test fun `both halves survive at 1_5x`() = assertBothSurvive(1.5f)

    /** 2.0x is Android's maximum - where the FIX bit, losing the label instead. */
    @Test fun `both halves survive at 2x`() = assertBothSurvive(2.0f)

    @Test fun `an ordinary short pair still sits on one line`() {
        // The stacking fallback must engage only when it has to: an ordinary row is still a
        // row, with the value flush right.
        show(1.0f, label = "Cash not invested", value = "\$1,204.77")
        val label = rule.onNodeWithText("Cash not invested").getUnclippedBoundsInRoot()
        val value = rule.onNodeWithText("\$1,204.77").getUnclippedBoundsInRoot()
        assertTrue(
            "an ordinary pair was stacked when it did not need to be",
            label.top.value <= value.bottom.value && value.top.value <= label.bottom.value
        )
        assertTrue(
            "the value is not flush right: ends at ${value.right.value} of $CARD",
            value.right.value > CARD - 2f
        )
    }
}
