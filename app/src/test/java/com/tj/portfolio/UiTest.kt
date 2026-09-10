package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Position
import com.tj.portfolio.ui.PortfolioTheme
import com.tj.portfolio.ui.PriceBlock
import com.tj.portfolio.ui.Row
import com.tj.portfolio.ui.StockRowItem
import com.tj.portfolio.ui.TxnEditorDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UI, rendered.
 *
 * Every previous round reasoned about these layouts by reading them. Two of the bugs that
 * shipped were things reading cannot catch - v1.6 clipped a percentage off the right edge of
 * a fixed-width column, and v5.3 reserved a labelled space for a number that could not exist.
 * These tests compose the real widgets and then MEASURE them, at the font scales and screen
 * widths a phone actually uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {

    @get:Rule val rule = createComposeRule()

    // ------------------------------------------------------------- fixtures

    private fun quote(
        price: Double = 268.26, prev: Double = 267.02, ext: Double? = null,
        state: String = "OPEN", name: String = "NVIDIA Corporation"
    ) = Quote(
        symbol = "NVDA", name = name, price = price, prevClose = prev,
        dayHigh = price + 2, dayLow = price - 2, extPrice = ext,
        // The label is what PriceBlock reads to tell pre-market from after-hours, so it has
        // to follow `state` here or a PRE test silently exercises the AFTER path.
        extLabel = if (ext == null) null else if (state == "PRE") "Pre-market" else "After hours",
        marketState = state,
        spark = listOf(266.0, 267.5, 268.0, 268.26), quoteTime = 1_756_000_000_000L,
        updated = System.currentTimeMillis()
    )

    private fun position(
        shares: Double = 12.0, basis: Double = 3100.0,
        sharesToday: Double = 0.0, costToday: Double = 0.0
    ) = Position("NVDA", shares, basis, 0.0, 1_750_000_000_000L, false, sharesToday, costToday)

    private fun row(
        q: Quote? = quote(), p: Position? = position(),
        watchOnly: Boolean = false, session: String = ""
    ) = Row("NVDA", q?.name ?: "", p, q, watchOnly, watched = watchOnly, sessionLabel = session)

    /** Renders content at a given font scale, inside the app's own theme. */
    private fun show(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale)
            ) {
                PortfolioTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    /** Width of the root node in pixels - the screen the widgets have to fit inside. */
    private fun rootWidth(): Float = rule.onRoot().fetchSemanticsNode().size.width.toFloat()

    /**
     * Fails if any rendered text runs past the right edge of the screen.
     *
     * This is the v1.6 clipping bug turned into an assertion. Compose's default overflow is
     * Clip, so an over-wide value is simply cut off with nothing on screen to hint at it -
     * which is exactly how "+0.1300  +0.15%" lost its percentage and nobody noticed until a
     * screenshot was compared by hand.
     */
    private fun assertNothingOverflows(label: String) {
        val w = rootWidth()
        val nodes = rule.onAllNodes(hasTextAny()).fetchSemanticsNodes()
        assertTrue("$label: nothing rendered at all", nodes.isNotEmpty())
        val spills = nodes.filter { it.boundsInRoot.right > w + 0.5f }.map {
            val t = it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            "\"$t\" ends at ${it.boundsInRoot.right} (screen is $w)"
        }
        assertTrue("$label: text runs off the right edge:\n  " + spills.joinToString("\n  "),
            spills.isEmpty())
    }

    private fun hasTextAny() = androidx.compose.ui.test.SemanticsMatcher(
        "has text"
    ) { it.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true }

    /**
     * Every tappable thing has to be big enough to hit. Material's own floor is 48dp; this
     * checks the smaller dimension so a wide-but-short chip is still caught.
     */
    private fun assertTouchTargets(label: String, minDp: Int = 44) {
        val density = 2.0f   // xhdpi
        val min = minDp * density
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val small = nodes.filter {
            val h = it.boundsInRoot.height
            val wdt = it.boundsInRoot.width
            h > 0 && wdt > 0 && (h < min - 0.5f)
        }.map {
            val t = it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "(no text)"
            "\"$t\" is ${"%.0f".format(it.boundsInRoot.height / density)}dp tall"
        }
        assertTrue("$label: tap targets under ${minDp}dp:\n  " + small.joinToString("\n  "),
            small.isEmpty())
    }

    // ------------------------------------------------------ the holding row

    // One test per scale: a ComposeTestRule allows exactly one setContent, so a loop over
    // scales inside a single test silently measures only the first one.
    @Test fun `holding row fits at normal text size`() {
        show(fontScale = 1.0f) { StockRowItem(row(), {}, {}) }
        assertNothingOverflows("holding row at fontScale 1.0")
    }

    @Test fun `holding row fits at large text size`() {
        show(fontScale = 1.3f) { StockRowItem(row(), {}, {}) }
        assertNothingOverflows("holding row at fontScale 1.3")
    }

    @Test fun `holding row fits at the largest accessibility text size`() {
        show(fontScale = 2.0f) { StockRowItem(row(), {}, {}) }
        assertNothingOverflows("holding row at fontScale 2.0")
    }

    @Test fun `holding row survives the widest realistic numbers`() {
        // a five-figure position on a four-figure share price, which is what a row has to
        // survive before anyone notices it cannot
        val q = quote(price = 4287.65, prev = 4100.12, name = "A Very Long Company Name Inc")
        val p = Position("NVDA", 9876.5432, 38_000_000.0, 0.0, 1L, false, 0.0, 0.0)
        show { StockRowItem(row(q, p), {}, {}) }
        assertNothingOverflows("holding row with extreme values")
    }

    @Test fun `a sub-dollar stock keeps its extra decimals on screen`() {
        val q = quote(price = 0.4231, prev = 0.4102, name = "Penny Co")
        show { StockRowItem(row(q, position(shares = 5000.0, basis = 2000.0)), {}, {}) }
        assertNothingOverflows("sub-dollar row")
        // three decimals is the documented rule for values under a dollar
        rule.onNodeWithText("$0.423", substring = true).assertIsDisplayed()
    }

    @Test fun `a row with no quote says so rather than showing a fake zero`() {
        show { StockRowItem(row(q = null), {}, {}) }
        rule.onNodeWithText("NO QUOTE YET").assertIsDisplayed()
        assertNothingOverflows("row with no quote")
    }

    @Test fun `a row whose provider gave no previous close reports it unavailable`() {
        // the Stooq path: a price but no prior close. v2.3's rule is that the app must never
        // invent one, and v5.9 extended that to Yahoo.
        val q = quote(prev = 0.0, state = "DELAYED")
        show { StockRowItem(row(q), {}, {}) }
        rule.onNodeWithText("not available").assertIsDisplayed()
    }

    @Test fun `the extended-hours column is absent while the market is open`() {
        show { PriceBlock(row(quote(ext = 269.10, state = "OPEN"))) }
        rule.onAllNodes(hasText("AFTER HOURS / OVERNIGHT")).fetchSemanticsNodes().let {
            assertTrue("a stale extended price appeared during the session", it.isEmpty())
        }
        rule.onNodeWithText("CURRENT PRICE  ·  MARKET OPEN").assertIsDisplayed()
    }

    @Test fun `the extended-hours column appears once it has printed`() {
        show { PriceBlock(row(quote(ext = 269.10, state = "AFTER"))) }
        rule.onNodeWithText("AFTER HOURS / OVERNIGHT").assertIsDisplayed()
        rule.onNodeWithText("DURING MARKET HOURS").assertIsDisplayed()
        assertNothingOverflows("two-column price block")
    }

    @Test fun `two price columns still fit at double font size`() {
        show(fontScale = 2.0f) { PriceBlock(row(quote(ext = 269.10, state = "AFTER")), big = true) }
        assertNothingOverflows("two-column price block, fontScale 2.0, big")
    }

    @Test fun `a same-day purchase is marked and explained`() {
        val r = row(p = position(shares = 2.0, basis = 495.48, sharesToday = 1.0, costToday = 255.0))
        show { StockRowItem(r, {}, {}) }
        rule.onNodeWithText("counted from the price you actually paid", substring = true)
            .assertIsDisplayed()
        assertNothingOverflows("row with a same-day buy")
    }

    @Test fun `an overnight row names the session it is describing`() {
        show { StockRowItem(row(session = "Sep 3"), {}, {}) }
        rule.onNodeWithText("YOU MADE SEP 3", substring = true).assertIsDisplayed()
    }

    @Test fun `row tap targets are big enough to hit`() {
        show { StockRowItem(row(), {}, {}) }
        assertTouchTargets("holding row")
    }

    @Test fun `row tap targets stay big enough at double text size`() {
        show(fontScale = 2.0f) { StockRowItem(row(), {}, {}) }
        assertTouchTargets("holding row at fontScale 2.0")
    }

    @Test fun `the row renders in dark mode`() {
        show(dark = true) { StockRowItem(row(), {}, {}) }
        rule.onNodeWithText("NVDA").assertIsDisplayed()
        assertNothingOverflows("dark mode row")
    }

    // --------------------------------------------------- the narrow phone

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-xhdpi")
    fun `the row fits a small phone`() {
        show { StockRowItem(row(), {}, {}) }
        assertNothingOverflows("320dp-wide screen")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-xhdpi")
    fun `the row fits a small phone at large text`() {
        show(fontScale = 1.5f) { StockRowItem(row(), {}, {}) }
        assertNothingOverflows("320dp-wide screen at fontScale 1.5")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-xhdpi")
    fun `tap targets survive a small screen`() {
        show { StockRowItem(row(), {}, {}) }
        assertTouchTargets("320dp-wide screen")
    }

    // ------------------------------------------------- the transaction editor
    //
    // NOT TESTED HERE, AND THE REASON IS WORTH RECORDING. Rendering TxnEditorDialog under
    // Robolectric never reaches idle, which looks exactly like an infinite composition loop
    // in the app - a real bug if it were one. It is not: a Material AlertDialog containing
    // nothing but plain OutlinedTextFields, with zero app code in it, fails the same way,
    // while a bare dialog settles immediately. The limitation is in the test harness's
    // handling of text fields inside a dialog window.
    //
    // The editor's own logic is covered where it can be executed rather than rendered:
    // Txn.unitPriceFromTotal and Txn.cashEffect in tests/ShippedTest.java drive the exact
    // arithmetic the dialog displays and saves.

    // ------------------------------------------- the request meter in Settings
    //
    // The provider-load work added a per-host request count to Settings. A host name is
    // long, unbounded and not something the app controls - "query1.finance.yahoo.com" beside
    // a four-figure number is exactly the shape that clipped a percentage off the row in
    // v1.6 - so it is rendered and measured here rather than assumed to fit.

    @Test
    @Config(sdk = [34])
    fun `the request meter fits at normal text`() {
        show { MeterRows() }
        assertNothingOverflows("request meter at fontScale 1.0")
    }

    @Test
    @Config(sdk = [34])
    fun `the request meter fits at large text`() {
        show(fontScale = 2.0f) { MeterRows() }
        assertNothingOverflows("request meter at fontScale 2.0")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-xhdpi")
    fun `the request meter fits a small phone at large text`() {
        show(fontScale = 1.5f) { MeterRows() }
        assertNothingOverflows("request meter, 320dp screen, fontScale 1.5")
    }

    // ------------------------------------------- every number in the price block is named
    //
    // TJ: "under the after hours/overnight there are two percentage numbers with no
    // explanation." The cell gained a tag per figure and a line naming the BASELINE each
    // change is measured against - which is three more lines of text inside a column roughly
    // half the screen wide. That is exactly the shape that clipped a percentage in v1.6, so
    // it is measured rather than eyeballed.

    @Test
    @Config(sdk = [34])
    fun `the labelled two-session block fits at normal text`() {
        show { PriceBlock(row(quote(ext = 269.10, state = "AFTER")), big = false) }
        assertNothingOverflows("labelled two-column price block, fontScale 1.0")
    }

    @Test
    @Config(sdk = [34])
    fun `the labelled two-session block fits at large text`() {
        show(fontScale = 2.0f) { PriceBlock(row(quote(ext = 269.10, state = "AFTER")), big = true) }
        assertNothingOverflows("labelled two-column price block, fontScale 2.0, big")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-xhdpi")
    fun `the labelled two-session block fits a small phone`() {
        show(fontScale = 1.5f) { PriceBlock(row(quote(ext = 269.10, state = "AFTER")), big = false) }
        assertNothingOverflows("labelled price block, 320dp screen, fontScale 1.5")
    }

    @Test
    @Config(sdk = [34])
    fun `both baselines are stated on screen`() {
        // The actual fix: two columns measured against DIFFERENT things must each say what.
        show { PriceBlock(row(quote(ext = 269.10, state = "AFTER")), big = false) }
        rule.onNodeWithText("vs previous close").assertIsDisplayed()
        rule.onNodeWithText("vs today's close").assertIsDisplayed()
        rule.onNodeWithText("price now").assertIsDisplayed()
        rule.onNodeWithText("change").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34])
    fun `pre-market names its own baseline`() {
        // Pre-market is measured from the LAST regular close, which is the previous day's -
        // same arithmetic as after hours, different day, so it must not say "today's close".
        show { PriceBlock(row(quote(ext = 265.40, state = "PRE")), big = false) }
        rule.onNodeWithText("vs last close").assertIsDisplayed()
    }

    /** The longest host names the app actually talks to, with four-figure counts. */
    @Composable
    private fun MeterRows() {
        androidx.compose.foundation.layout.Column {
            com.tj.portfolio.ui.KeyValue("query1.finance.yahoo.com", "4800")
            com.tj.portfolio.ui.KeyValue("feeds.content.dowjones.io", "1200")
            com.tj.portfolio.ui.KeyValue("news.google.com", "960")
            com.tj.portfolio.ui.KeyValue("www.nasdaq.com", "480")
            com.tj.portfolio.ui.KeyValue("api.anthropic.com", "12")
        }
    }
}
