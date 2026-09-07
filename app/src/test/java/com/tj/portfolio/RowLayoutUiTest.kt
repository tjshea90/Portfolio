package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.tj.portfolio.data.Quote
import com.tj.portfolio.domain.Position
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.RowSeparator
import com.tj.portfolio.ui.StockRowItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ROUND 58: the two layout changes TJ asked for, turned into assertions.
 *
 * Both are the kind of thing that reads as done in a diff and is wrong on the phone, which
 * is the entire reason this project renders its widgets in tests:
 *
 *   1. *"stack the dollar amount and percent change vertically to be uniform with the other
 *      sections. right now they are side by side horizontally."* Measured as a real vertical
 *      relationship between the two nodes, not as "the code no longer joins two strings" -
 *      a future change that puts them back on one line would pass a source-level check.
 *   2. *"make the separation between stocks a little more pronounced... more space between
 *      stocks or a thicker line between stocks or both."* Measured as the gap two adjacent
 *      rows actually end up with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowLayoutUiTest {

    @get:Rule val rule = createComposeRule()

    private val density = 2.0f

    private fun show(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PortfolioTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    /** After-hours quote: the regular session has closed and an extended print exists. */
    private fun afterHoursRow() = Row(
        "NVDA", "NVIDIA Corporation",
        Position("NVDA", 12.0, 3100.0, 0.0, 1_750_000_000_000L, false, 0.0, 0.0),
        Quote(
            symbol = "NVDA", name = "NVIDIA Corporation",
            price = 268.26, prevClose = 267.02,
            dayHigh = 270.0, dayLow = 266.0,
            extPrice = 269.51, extLabel = "After hours", marketState = "AFTER",
            spark = listOf(266.0, 267.5, 268.0, 268.26),
            quoteTime = 1_756_000_000_000L, updated = System.currentTimeMillis()
        ),
        false, ""
    )

    private fun nodesWithText(): List<Pair<String, androidx.compose.ui.geometry.Rect>> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            // THE UNMERGED TREE, AND THIS IS THE WHOLE REASON THE HELPER EXISTS.
            //
            // `StockRowItem` wraps its content in `combinedClickable` for the long-press
            // menu, and a clickable MERGES every descendant's semantics into one node. Asked
            // for the merged tree, the entire row comes back as a single string - which makes
            // it impossible to say anything about where two figures sit relative to each
            // other, and would let this test "pass" purely because both substrings appear
            // somewhere in the row. Unmerged gives the individual Text nodes with their own
            // bounds, which is what a question about layout actually needs.
            useUnmergedTree = true
        ).fetchSemanticsNodes().map {
            (it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "") to
                it.boundsInRoot
        }

    private fun find(predicate: (String) -> Boolean) =
        nodesWithText().firstOrNull { predicate(it.first) }

    // ------------------------------------------- the extended-hours column

    /**
     * The dollar change and the percentage must be two nodes, one BELOW the other.
     *
     * They used to be a single pre-joined string - `"+1.25   +0.47%"` - which is why they
     * sat side by side while the market-hours column beside them had its two figures
     * stacked. Asserting on the geometry is what makes this a real regression test: joining
     * them again would still contain both substrings.
     */
    @Test
    fun `the after-hours dollar change and percentage are stacked, not side by side`() {
        show { StockRowItem(afterHoursRow(), {}, {}) }

        // Extended: 269.51 - 268.26 = +1.25 / +0.47%. Matched exactly, because the market
        // column's +1.24 / +0.46% sits right beside them and a prefix would find that too.
        val dollar = find { it == "+$1.25" }
        val percent = find { it == "+0.47%" }

        assertTrue(
            "the extended-hours dollar change was not rendered on its own:\n" +
                nodesWithText().joinToString("\n") { "  \"${it.first}\"" },
            dollar != null
        )
        assertTrue(
            "the extended-hours percentage was not rendered on its own:\n" +
                nodesWithText().joinToString("\n") { "  \"${it.first}\"" },
            percent != null
        )

        val d = dollar!!.second
        val p = percent!!.second
        assertTrue(
            "they are on the same line: dollar bottom=${d.bottom}, percent top=${p.top}",
            p.top >= d.bottom - 0.5f
        )
        assertTrue(
            "they are not left-aligned in one column: dollar left=${d.left}, " +
                "percent left=${p.left}",
            kotlin.math.abs(p.left - d.left) < 2f
        )
    }

    /**
     * "Uniform with the other sections" is the actual request, so the market-hours column is
     * measured the same way: its own dollar figure above its own percentage, and the two
     * columns side by side as columns rather than run together on one line.
     */
    @Test
    fun `the market-hours column is stacked the same way and sits beside the other`() {
        show { StockRowItem(afterHoursRow(), {}, {}) }
        // Market hours: 268.26 - 267.02 = +1.24 / +0.46%
        val mDollar = find { it == "+$1.24" }
        val mPct = find { it == "+0.46%" }
        assertTrue("market-hours dollar change missing", mDollar != null)
        assertTrue("market-hours percentage missing", mPct != null)
        assertTrue(
            "the market-hours figures are on one line",
            mPct!!.second.top >= mDollar!!.second.bottom - 0.5f
        )

        // ...and the extended column is to the RIGHT of it, not underneath.
        val ext = find { it == "+$1.25" }
        assertTrue("extended-hours dollar change missing", ext != null)
        assertTrue(
            "the two session columns are not side by side",
            ext!!.second.left > mDollar.second.left
        )
    }

    @Test
    fun `the after-hours column still fits at a large font scale`() {
        show(fontScale = 1.3f) { StockRowItem(afterHoursRow(), {}, {}) }
        val w = rule.onRoot().fetchSemanticsNode().size.width.toFloat()
        val spills = nodesWithText().filter { it.second.right > w + 0.5f }
        assertTrue(
            "text runs off the right edge:\n" +
                spills.joinToString("\n") { "  \"${it.first}\" ends at ${it.second.right}" },
            spills.isEmpty()
        )
    }

    // ------------------------------------------------- separation between rows

    /**
     * TWO ROWS, MEASURED. The gap between the bottom of one row's last text and the top of
     * the next row's first text has to be visibly bigger than the gap between two lines
     * inside a row - otherwise "more pronounced" is a claim rather than a fact.
     */
    @Test
    fun `two stacked rows are separated by real space`() {
        show {
            Column {
                StockRowItem(afterHoursRow(), {}, {})
                RowSeparator()
                StockRowItem(afterHoursRow(), {}, {})
            }
        }
        val symbols = nodesWithText().filter { it.first == "NVDA" }.sortedBy { it.second.top }
        assertTrue("expected two rows, found ${symbols.size}", symbols.size >= 2)

        // Everything belonging to the first row sits above the second row's symbol.
        val secondTop = symbols[1].second.top
        val firstRowBottom = nodesWithText()
            .filter { it.second.top < symbols[1].second.top }
            .maxOf { it.second.bottom }
        val gapDp = (secondTop - firstRowBottom) / density
        assertTrue(
            "the gap between two stocks is only ${"%.1f".format(gapDp)}dp - " +
                "TJ asked for this to be more pronounced",
            gapDp >= 18f
        )
    }

    @Test
    fun `the separation survives dark mode and a large font scale`() {
        show(fontScale = 1.3f, dark = true) {
            Column {
                StockRowItem(afterHoursRow(), {}, {})
                RowSeparator()
                StockRowItem(afterHoursRow(), {}, {})
            }
        }
        val symbols = nodesWithText().filter { it.first == "NVDA" }.sortedBy { it.second.top }
        assertTrue("expected two rows", symbols.size >= 2)
        val secondTop = symbols[1].second.top
        val firstRowBottom = nodesWithText()
            .filter { it.second.top < secondTop }.maxOf { it.second.bottom }
        assertTrue(
            "rows overlap or touch at 1.3x in dark mode",
            (secondTop - firstRowBottom) / density >= 18f
        )
    }
}
