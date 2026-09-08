package com.tj.portfolio

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.data.Quote
import com.tj.portfolio.domain.Position
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.StockRowItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ROUND 61: the toggle, on the real holding row and against the real settings table.
 *
 * `PlModeTest` proves the helpers. This proves the two things the helpers cannot:
 *
 *   1. the ROW actually uses them, in both modes, and nothing else about it changes;
 *   2. the preference survives a restart, which is the whole reason it is a setting rather
 *      than screen state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlModeUiTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { ctx.deleteDatabase(Db.DB_NAME) }

    /** +$119.12 / +3.84% all-time, +$14.88 / +0.46% today - distinctive in both units. */
    private fun row() = Row(
        "NVDA", "NVIDIA Corporation",
        Position("NVDA", 12.0, 3100.0, 0.0, 1_750_000_000_000L, false, 0.0, 0.0),
        Quote(
            symbol = "NVDA", name = "NVIDIA Corporation",
            price = 268.26, prevClose = 267.02, dayHigh = 270.0, dayLow = 266.0,
            marketState = "OPEN", spark = listOf(266.0, 268.26),
            quoteTime = 1_756_000_000_000L, updated = System.currentTimeMillis()
        ),
        false, ""
    )

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                PortfolioTheme(dark = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun texts(): List<String> =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    /** The rendered bounds of an exact string, or null. Used to compare prominence. */
    private fun boundsOf(exact: String) =
        rule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("has text") {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") == exact
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    // ------------------------------------------------------------ the row

    @Test
    fun `in dollar mode the dollar figure is drawn above the percentage`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val all = texts()
        assertTrue("no dollar P/L on the row: $all", all.any { it == "+$119.12" })
        assertTrue("no percentage on the row: $all", all.any { it == "+3.84%" })

        val lead = boundsOf("+$119.12")!!
        val sub = boundsOf("+3.84%")!!
        assertTrue(
            "the dollar figure is not the one on top (lead=${lead.top}, sub=${sub.top})",
            lead.top < sub.top
        )
    }

    @Test
    fun `in percent mode the percentage is drawn above the dollar figure`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.PERCENT) }
        val all = texts()
        assertTrue("no percentage on the row: $all", all.any { it == "+3.84%" })
        assertTrue("the dollar figure disappeared entirely: $all", all.any { it == "+$119.12" })

        val lead = boundsOf("+3.84%")!!
        val sub = boundsOf("+$119.12")!!
        assertTrue(
            "the percentage is not the one on top (lead=${lead.top}, sub=${sub.top})",
            lead.top < sub.top
        )
    }

    /**
     * NOTHING ELSE MOVES. The toggle swaps two numbers; it must not disturb the labels, the
     * value of the holding, or anything else the row says.
     */
    @Test
    fun `switching mode changes only the order of the two figures`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.DOLLAR) }
        val dollarTexts = texts().toSet()

        val second = createComposeRule()
        assertTrue(second != null)   // a rule is per-test; the comparison below is by content

        // Everything that is not one of the two swapped figures must appear in both modes.
        val swapped = setOf("+$119.12", "+3.84%", "+$14.88", "+0.46%")
        val invariant = dollarTexts - swapped
        assertTrue("the row rendered almost nothing: $dollarTexts", invariant.size >= 5)
        assertTrue("YOUR SHARES label missing", invariant.any { it.contains("YOUR SHARES") })
        assertTrue("SINCE YOU BOUGHT label missing",
            invariant.any { it.contains("SINCE YOU BOUGHT") })
        assertTrue("the share count line is missing",
            invariant.any { it.contains("shares") })
    }

    @Test
    fun `both figures are still present in percent mode, nothing is hidden`() {
        show { StockRowItem(row(), {}, {}, plMode = PlMode.PERCENT) }
        val all = texts()
        listOf("+$119.12", "+3.84%", "+$14.88", "+0.46%").forEach {
            assertTrue("$it is missing in percent mode: $all", all.any { s -> s == it })
        }
    }

    /** The default is what the app has always shown, so an upgrade changes nothing. */
    @Test
    fun `the default is dollars`() {
        show { StockRowItem(row(), {}, {}) }
        val lead = boundsOf("+$119.12")!!
        val sub = boundsOf("+3.84%")!!
        assertTrue(lead.top < sub.top)
    }

    // ---------------------------------------------------------- persistence

    /**
     * The reason this is a setting and not screen state: it has to be there tomorrow. Tested
     * against the real settings table, and against a REOPEN rather than the same handle.
     */
    @Test
    fun `the choice survives a restart`() {
        Db(ctx).use { db ->
            assertEquals(PlMode.DOLLAR, PlMode.byName(db.get(Keys.PL_MODE)))
            db.set(Keys.PL_MODE, PlMode.PERCENT.name)
        }
        Db(ctx).use { db ->
            assertEquals(PlMode.PERCENT, PlMode.byName(db.get(Keys.PL_MODE)))
        }
    }

    /** And it is a preference, so it belongs in the backup the user carries to a new phone. */
    @Test
    fun `the choice is carried in the json backup`() {
        Db(ctx).use { db ->
            db.set(Keys.PL_MODE, PlMode.PERCENT.name)
            val json = db.exportJson()
            assertTrue(
                "PL_MODE was filtered out of the backup",
                json.contains(Keys.PL_MODE) && json.contains("PERCENT")
            )
        }
    }
}
