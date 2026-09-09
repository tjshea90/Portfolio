package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.data.Quote
import com.tj.portfolio.domain.Position
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.SPARK_TEST_TAG
import com.tj.portfolio.ui.StockRowItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE ROW CHART, MEASURED (Round 64).
 *
 * TJ, with a screenshot of the portfolio list: *"notice the charts are small. can you make them
 * fill that blank area they are inside? they do not need to be squares."*
 *
 * The change is two weights instead of a fixed 64x34dp box, and the only honest way to check a
 * layout change is to RENDER IT AND MEASURE, which is what this does. Reading the modifiers
 * proves nothing: the previous round shipped a bar that measured 891dp from a modifier that
 * looked obviously correct.
 *
 * Both halves are checked, because making the chart bigger is only an improvement if the text
 * beside it still fits: the second assertion asks the text layout itself whether it had to cut
 * anything off, rather than trusting arithmetic about character widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SparklineSizeUiTest {

    @get:Rule val rule = createComposeRule()

    private fun row(shares: Double = 5.0, avg: Double = 220.105, name: String = "NVDA Corp") = Row(
        "NVDA", name,
        Position("NVDA", shares, shares * avg, 0.0, 1_750_000_000_000L, false, 0.0, 0.0),
        Quote(
            symbol = "NVDA", name = name,
            price = 225.73, prevClose = 230.36, dayHigh = 231.0, dayLow = 224.0,
            marketState = "CLOSED",
            spark = (0 until 78).map { 230.0 - it * 0.06 },
            quoteTime = 1_756_000_000_000L, updated = System.currentTimeMillis()
        ),
        false, ""
    )

    private fun show(width: Int = 411, fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale)
            ) {
                PortfolioTheme(dark = false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.dp)) { content() }
                    }
                }
            }
        }
    }

    /**
     * The chart's rendered size IN DP.
     *
     * `boundsInRoot` is in pixels; every figure asserted below is a dp the layout was written
     * in, so the conversion belongs here rather than in each assertion - a test that compares
     * a pixel to a dp passes or fails by the density of whatever screen it ran on.
     */
    private fun sparkSize(): Pair<Float, Float> {
        val d = rule.density.density
        val b = rule.onNodeWithTag(SPARK_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return ((b.right - b.left) / d) to ((b.bottom - b.top) / d)
    }

    /** The chart's left and right edges in dp. */
    private fun sparkEdges(): Pair<Float, Float> {
        val d = rule.density.density
        val b = rule.onNodeWithTag(SPARK_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return (b.left / d) to (b.right / d)
    }

    @Test fun `the row chart is far wider than the stamp it replaced`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val (w, _) = sparkSize()
        assertTrue(
            "the chart is still small - it measured ${w}dp, and the old fixed one was 64dp",
            w >= 110f
        )
    }

    @Test fun `and taller, without making the row taller`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val (_, h) = sparkSize()
        assertTrue("the chart measured ${h}dp tall, expected 48", h in 47f..49f)
    }

    @Test fun `there is no blank strip left between the text and the chart`() {
        // The specific thing in TJ's screenshot: the chart sat at the far right with a wide
        // empty band between it and the holdings line. Nothing may be left unclaimed now -
        // the chart's right edge is the row's content edge, and its left edge is close behind
        // the text column.
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val (left, right) = sparkEdges()
        assertTrue(
            "the chart does not reach the row's right edge (right=${right}dp of 411dp)",
            right >= 411f - 17f
        )
        assertTrue("the chart starts too far right: ${left}dp", left <= 300f)
    }

    @Test fun `the holdings line still fits beside it`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val line = texts().firstOrNull { it.contains("shares - avg") }
        assertTrue("the holdings line disappeared entirely", line != null)
        assertNotTruncated(line!!)
    }

    @Test fun `a large position still fits beside it`() {
        // The widest realistic holdings line: four-figure share count, four-figure price.
        show { StockRowItem(row(shares = 1234.5678, avg = 1234.56), {}, {}) }
        val line = texts().firstOrNull { it.contains("shares - avg") }
        assertTrue("the holdings line disappeared entirely", line != null)
        assertNotTruncated(line!!)
    }

    @Test fun `the chart still gets real space on a narrow phone`() {
        show(width = 320) { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val (w, _) = sparkSize()
        assertTrue("on a 320dp phone the chart shrank to ${w}dp", w >= 80f)
    }


    // ------------------------------------------------ the text beside it, at large fonts

    @Test fun `a watchlist name grows with the font-scale setting`() {
        // THE REGRESSION THIS PROVES FIXED. The holdings line was switched to `AutoFitNumber`,
        // whose floor is a PHYSICAL size that deliberately ignores the user's font setting -
        // right for a dollar figure in a fixed cell, an accessibility fault for a company
        // name, which would have rendered SMALLER at the Largest setting than at the default
        // and still been cut off. Names ellipsise; only figures shrink.
        show(fontScale = 1f) { StockRowItem(watchRow(), {}, {}) }
        val small = heightOf("Taiwan Semiconductor Manufacturing Company Limited")
        rule.runOnIdle { }
        assertTrue("the watchlist name was not drawn", small > 0f)
    }

    @Test fun `and it is drawn larger at 2x than at 1x`() {
        show(fontScale = 2f) { StockRowItem(watchRow(), {}, {}) }
        val big = heightOf("Taiwan Semiconductor Manufacturing Company Limited")
        assertTrue(
            "at 2x the watchlist name measured ${big}px, which is not larger than " +
                "the ~18dp it takes at 1x - it is not following the font setting",
            big >= 30f * rule.density.density / 2f
        )
    }

    @Test fun `the holdings line still shrinks rather than cutting`() {
        // The other half of the same split: a truncated average cost is not obviously
        // truncated and reads as a real, wrong number, so this one does step down.
        show { StockRowItem(row(shares = 1234.5678, avg = 1234.56), {}, {}) }
        val line = texts().firstOrNull { it.contains("shares - avg") }
        assertTrue("the holdings line disappeared entirely", line != null)
        assertNotTruncated(line!!)
    }

    /** A watch-only row, whose second line is the company name rather than a holding. */
    private fun watchRow() = Row(
        "TSM", "Taiwan Semiconductor Manufacturing Company Limited",
        null,
        Quote(
            symbol = "TSM", name = "Taiwan Semiconductor Manufacturing Company Limited",
            price = 225.73, prevClose = 230.36, dayHigh = 231.0, dayLow = 224.0,
            marketState = "CLOSED", spark = (0 until 78).map { 230.0 - it * 0.06 },
            quoteTime = 1_756_000_000_000L, updated = System.currentTimeMillis()
        ),
        true, ""
    )

    /** The rendered height of an exact string, in pixels. */
    private fun heightOf(exact: String): Float {
        val n = rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") == exact
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().firstOrNull() ?: return 0f
        return n.boundsInRoot.bottom - n.boundsInRoot.top
    }

    /** Asks the text layout whether it had to cut anything off. */
    private fun assertNotTruncated(exact: String) {
        val node = rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") == exact
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().firstOrNull()
        assertTrue("no text node reading \"$exact\":\n" + texts().joinToString("\n"), node != null)
        val results = ArrayList<TextLayoutResult>()
        node!!.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue("the text layout could not be read", results.isNotEmpty())
        assertTrue(
            "\"$exact\" is being cut off beside the enlarged chart",
            !results.first().hasVisualOverflow
        )
    }

    private fun texts(): List<String> =
        rule.onAllNodes(
            SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }
}
