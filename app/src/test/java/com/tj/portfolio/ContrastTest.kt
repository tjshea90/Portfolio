package com.tj.portfolio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.greenText
import com.tj.portfolio.ui.ratingColor
import com.tj.portfolio.ui.redText
import com.tj.portfolio.ui.rowRule
import com.tj.portfolio.ui.verdictTextColor
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * COLOURS THAT ARE ACTUALLY VISIBLE, MEASURED (Round 66 audit, PUI-4 / PUI-5).
 *
 * ---- WHY THIS TEST EXISTS
 *
 * TJ asked twice for the line between stocks to read as a stronger break - *"a little more
 * pronounced"*, then *"even thicker"* - and the app answered both times by making the bar
 * wider: 1dp, then 3dp, then 5dp. It never looked much different, because the thickness was
 * never the problem. The bar was painted in `colorScheme.outline`, which measures **1.24:1**
 * against the light background. Five device-independent pixels of something 1.24:1 is a
 * slightly-off-white strip, and the eye does not read it as a rule at any width.
 *
 * Nothing in the codebase could have caught that: contrast is arithmetic on two colours, and
 * neither a compile nor a screenshot diff does arithmetic. So this file does, against the
 * theme AS SHIPPED - the colours are read out of a real composition under `PortfolioTheme`,
 * not copied here as literals that could drift from it.
 *
 * ---- THE THRESHOLDS, AND WHY THEY DIFFER
 *
 * WCAG 2.1 asks **3:1** of a non-text graphic that carries meaning (1.4.11) and **4.5:1** of
 * body text (1.4.3). A divider is the former; a coloured money figure is the latter, and the
 * figure is the one place in this app where getting it wrong is expensive - a gain the user
 * cannot read is a gain they will go and look up somewhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContrastTest {

    @get:Rule val rule = createComposeRule()

    // ---- WCAG 2.1 relative luminance and contrast ratio, straight from the spec.

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** One reading of the live theme: the colours a screen would actually be painted with. */
    private data class Palette(
        val background: Color,
        val surface: Color,
        val surfaceVariant: Color,
        val rule: Color,
        val outline: Color,
        val green: Color,
        val red: Color,
        val buyText: Color,
        val holdText: Color,
        val sellText: Color,
        val rating9: Color,
        val rating7: Color,
        val rating5: Color,
        val rating2: Color
    )

    /**
     * BOTH THEMES READ IN ONE COMPOSITION. `createComposeRule` allows a single `setContent`
     * per test, so a per-theme helper that called it twice threw on the second - and the two
     * palettes are wanted together anyway, since half the assertions compare them.
     */
    private lateinit var captured: Map<Boolean, Palette>

    @Composable
    private fun capture(): Palette = Palette(
        background = MaterialTheme.colorScheme.background,
        surface = MaterialTheme.colorScheme.surface,
        surfaceVariant = MaterialTheme.colorScheme.surfaceVariant,
        rule = rowRule,
        outline = MaterialTheme.colorScheme.outline,
        green = greenText,
        red = redText,
        buyText = verdictTextColor(TradeVerdict.BUY),
        holdText = verdictTextColor(TradeVerdict.HOLD),
        sellText = verdictTextColor(TradeVerdict.SELL),
        rating9 = ratingColor(9),
        rating7 = ratingColor(7),
        rating5 = ratingColor(5),
        rating2 = ratingColor(2)
    )

    @Before fun readTheTheme() {
        var light: Palette? = null
        var dark: Palette? = null
        rule.setContent {
            PortfolioTheme(dark = false) { light = capture() }
            PortfolioTheme(dark = true) { dark = capture() }
        }
        rule.waitForIdle()
        captured = mapOf(false to light!!, true to dark!!)
    }

    private fun palette(dark: Boolean): Palette = captured.getValue(dark)

    private fun atLeast(min: Double, what: String, fg: Color, bg: Color) {
        val r = contrast(fg, bg)
        assertTrue(
            "$what measured %.2f:1, needs %.1f:1".format(r, min),
            r >= min
        )
    }

    /**
     * THE ONE THIS WAS WRITTEN FOR. The separator between two stocks is a meaningful graphic:
     * it is the only thing telling the eye where one holding ends and the next begins.
     */
    @Test fun theSeparatorIsVisibleInBothThemes() {
        for (dark in listOf(false, true)) {
            val p = palette(dark)
            atLeast(3.0, "row separator on the background (dark=$dark)", p.rule, p.background)
        }
    }

    /**
     * The colour it used to be, recorded so the regression is unmistakable if anyone puts it
     * back: `outline` is what two rounds of thickening were painting.
     */
    @Test fun theOldSeparatorColourWouldStillFail() {
        val p = palette(false)
        assertTrue(
            "outline is no longer the near-invisible colour this test documents",
            contrast(p.outline, p.background) < 1.5
        )
    }

    /**
     * And it must stay clearly HEAVIER than the divider inside a row, or the eye groups the
     * wrong halves together - the hierarchy `RowSeparator`'s KDoc is built on.
     */
    @Test fun theSeparatorOutContrastsTheInRowDivider() {
        for (dark in listOf(false, true)) {
            val p = palette(dark)
            val outer = contrast(p.rule, p.background)
            // `InRowDivider` draws `outline` at 45% opacity over the background.
            val inner = contrast(
                Color(
                    red = p.outline.red * 0.45f + p.background.red * 0.55f,
                    green = p.outline.green * 0.45f + p.background.green * 0.55f,
                    blue = p.outline.blue * 0.45f + p.background.blue * 0.55f
                ),
                p.background
            )
            assertTrue(
                "dark=$dark: outer rule %.2f:1 is not clearly heavier than the in-row %.2f:1"
                    .format(outer, inner),
                outer >= inner * 1.8
            )
        }
    }

    /**
     * Coloured money figures are TEXT and take the 4.5:1 rule. `greenText`/`redText` exist
     * precisely because the brand green fails it on white; PUI-5 found one figure on the
     * portfolio card still painted in the brand colour.
     */
    @Test fun signedFiguresAreLegibleOnEverySurfaceTheyAppearOn() {
        for (dark in listOf(false, true)) {
            val p = palette(dark)
            for ((name, bg) in listOf(
                "background" to p.background,
                "surface" to p.surface,
                "surfaceVariant (StatCard)" to p.surfaceVariant
            )) {
                atLeast(4.5, "greenText on $name (dark=$dark)", p.green, bg)
                atLeast(4.5, "redText on $name (dark=$dark)", p.red, bg)
            }
        }
    }

    /**
     * NO CALL SITE MAY PAINT TEXT WITH A FILL COLOUR (Round 66 audit, REG-5).
     *
     * The tests above measure the ACCESSORS - `greenText`, `redText`, `rowRule` - and they all
     * passed while eight `Text`s in the app were still hard-coded to `Red`, the brand fill,
     * including "Your transactions are missing" on a StatCard at the 4.41:1 that AUD-1 had
     * just moved the accessor to avoid. Fixing a colour in one place and leaving the call
     * sites painting around it is the failure mode of every palette change, and no
     * measurement of the palette can catch it - so this reads the source.
     *
     * `Red` and `Green` are for DRAWN SHAPES: the sparkline, the chart fill, the weight bars.
     * Anything with a `color =` beside it in a text call belongs to the theme accessor.
     */
    @Test fun `no text in the app is painted with a fill colour`() {
        val ui = java.io.File("src/main/java/com/tj/portfolio/ui")
        val offenders = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    val paintsFill = Regex("""color\s*=\s*(Red|Green)\s*[,)]?\s*$""")
                        .containsMatchIn(code.trimEnd()) ||
                        Regex("""Text\([^)]*color\s*=\s*(Red|Green)\s*[,)]""").containsMatchIn(code)
                    if (paintsFill) "${f.name}:${i + 1}  ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue(
            "text painted with a fill colour instead of the theme accessor " +
                "(use greenText / redText):\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The arithmetic itself, against two ratios anyone can check by hand. */
    @Test fun theContrastFormulaIsRight() {
        assertTrue(contrast(Color.Black, Color.White) > 20.9)
        assertTrue(contrast(Color.White, Color.White) < 1.01)
    }
}
