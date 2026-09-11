package com.tj.portfolio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.data.Recommendation
import com.tj.portfolio.util.Fmt

/** What the long-press menu asked for. */
enum class RowAction { OPEN, EDIT_POSITION, ADD_TXN, NEWS, WATCH_TOGGLE, DELETE }

/** Test handle for the row's chart, so its size can be measured rather than assumed. */
internal const val SPARK_TEST_TAG = "rowSparkline"

/** Test handle for the row's BUY/HOLD/SELL chip, whose text varies with the verdict. */
internal const val RECOMMENDATION_CHIP_TEST_TAG = "rowRecommendationChip"

/** The breathing space between the holdings text and the chart beside it. */
private val ROW_CHART_GAP = 8.dp

/**
 * The share of the row the chart keeps EVEN WHEN THE TEXT WANTS EVERYTHING.
 *
 * 0.385 is exactly what round 64's `weight(1.6f)` / `weight(1f)` split gave it - 1/2.6 - so
 * this floor can only ever match that layout, never undercut it. That matters for the one row
 * whose text really is longer than the row: a watchlist entry, whose second line is a company
 * name and not a short holdings figure. Measuring alone would hand "Taiwan Semiconductor
 * Manufacturing Company Limited" the whole width and leave the chart a sliver - a chart made
 * SMALLER by the round whose entire purpose was to make it bigger.
 */
private const val ROW_CHART_MIN_FRACTION = 0.385f

/**
 * What the chart takes when the row is measured with no width to divide up.
 *
 * A row can be measured against `Constraints.Infinity` - an intrinsic-width pass from a
 * parent, or a horizontally scrolling one. There is no "what is left over" in that case:
 * subtracting from Infinity gives Infinity, and a Placeable that wide is a crash. Both halves
 * take a size of their own instead, which is the only sane reading of an unbounded row.
 */
private val ROW_CHART_FALLBACK = 140.dp

/**
 * One holding, split into two clearly separated halves by a divider:
 *
 *   THE STOCK (top)      symbol, what you hold, the live price and how the price moved
 *   YOUR MONEY (bottom)  what your shares are worth and what you have made or lost
 *
 * Keeping "the stock went up 1.8%" visually apart from "you made $12.12" is the whole
 * point - they are different numbers and were easy to confuse when stacked in one grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StockRowItem(
    row: Row,
    onClick: () -> Unit,
    onNews: () -> Unit,
    onAction: (RowAction) -> Unit = {},
    /**
     * Which half of a P/L figure leads (Round 61).
     *
     * DEFAULTED, so every existing caller and every existing test keeps the behaviour the app
     * has always had. Only the Portfolio screen passes anything else, because it is the only
     * screen with P/L on it - a watchlist row is `watchOnly` and its money half is not drawn
     * at all.
     */
    plMode: PlMode = PlMode.DOLLAR,
    /**
     * TJ: *"I like the buy sell hold symbols but also include them in the main portfolio tab
     * next to each stock."* Null before the day's verdict has been computed for this symbol
     * (or for a symbol nothing could be computed for) - the chip then shows "..." rather than
     * a blank space, the same placeholder the detail screen's tab uses while it waits.
     */
    recommendation: Recommendation? = null
) {
    var menu by remember { mutableStateOf(false) }
    var showRecommendation by remember { mutableStateOf(false) }
    val q = row.quote
    // the price/session numbers all live in PriceBlock now; this is only for the money half
    val hasDay = row.price > 0 && (q?.prevClose ?: 0.0) > 0.0

    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
        ) {
            // ================= THE STOCK =================
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(row.symbol, 36)
                Spacer(Modifier.width(10.dp))

                // ---- HOW THE ROW'S TOP LINE IS DIVIDED (Round 65).
                //
                // TJ, with a screenshot: *"in the portfolio section notice the charts are
                // small. can you make them fill that blank area they are inside? they do not
                // need to be squares."* And then, with a second screenshot, that the gap was
                // still there.
                //
                // ROUND 64 SPLIT THE ROW BY WEIGHT, 1.6 to 1. That was the wrong instrument,
                // and the second screenshot is what it looks like: a weight RESERVES its share
                // whether the child uses it or not. "5 shares - avg $220.105" is nowhere near
                // 200dp wide, so the text column was handed 200 and painted about 140 - and
                // the 60dp it did not use stayed blank, at the very spot TJ was pointing at.
                // Changing the ratio only moves that gap; it cannot remove it, because the
                // gap is the difference between what the text was GIVEN and what it WANTED.
                //
                // SO ASK IT WHAT IT WANTS. [TextThenChart] measures the text at its intrinsic
                // width and hands everything left to the chart, which is the only split with
                // no unclaimed space in it by construction. On TJ's phone that is about 215dp
                // of chart where the weights gave 125dp, and it adapts to the actual string:
                // a bigger position takes its width back out of the chart instead of leaving
                // a hole. `SparklineSizeUiTest` renders the row and MEASURES both halves
                // rather than trusting any of this arithmetic.
                //
                // AND TALLER: 34dp -> 48dp. The row is not made taller by it - the avatar and
                // two text lines already stand 46dp - so the height was free all along.
                TextThenChart(text = {
                  Column {
                    Text(
                        row.symbol,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // ---- FIGURES SHRINK; NAMES ELLIPSISE.
                    //
                    // The chart beside this took real width in this round, and a big position
                    // ("1,234.5678 shares - avg $1,234.56") is a third longer than TJ's own -
                    // so at a fixed 14sp the widest lines would have started cutting off where
                    // they used to fit. For FIGURES the auto-fit the money cells already use
                    // is the right answer: a truncated average cost is not obviously truncated
                    // and reads as a real, wrong number, so the line steps down a point at a
                    // time instead.
                    //
                    // A WATCHLIST NAME IS NOT A FIGURE, and the same widget is wrong for it
                    // (Round 64 sweep 3). Its floor is a PHYSICAL size that deliberately
                    // ignores the font-scale setting - which is the point for a dollar amount
                    // in a fixed cell and an accessibility regression for prose: at the
                    // Largest setting "Taiwan Semiconductor Manufacturing Company Limited"
                    // would have rendered SMALLER than at the default and still been cut. A
                    // clipped company name is harmless; an illegible one is not.
                    if (row.watchOnly) {
                        Text(
                            row.name.ifBlank { "Watching" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        AutoFitNumber(
                            text = "${Fmt.shares(row.shares)} shares - " +
                                "avg ${Fmt.price(row.avgCost)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal,
                            minSp = 11
                        )
                    }
                  }
                }) {
                    Sparkline(
                        points = q?.spark ?: emptyList(),
                        baseline = q?.prevClose ?: 0.0,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag(SPARK_TEST_TAG)
                    )
                }
            }

            // The price used to live in a squeezed right-hand column with no labels at all.
            // It gets the full width and proper headings now.
            Spacer(Modifier.height(10.dp))
            PriceBlock(row)

            if (!row.watchOnly) {
                // ================= YOUR MONEY =================
                //
                // A HAIRLINE, deliberately lighter than the line between two rows. The two
                // used to be identical, which made the break inside a single holding look
                // exactly as strong as the break between two different ones - see the note
                // on `RowSeparator`.
                Spacer(Modifier.height(10.dp))
                InRowDivider()
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    MoneyCell(
                        modifier = Modifier.weight(1f),
                        label = "YOUR SHARES",
                        value = Fmt.usd(row.value),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    MoneyCell(
                        modifier = Modifier.weight(1f),
                        label = (
                            if (row.sessionLabel.isBlank()) "YOU MADE TODAY"
                            else "YOU MADE ${row.sessionLabel.uppercase()}"
                        ) + if (row.boughtToday) "*" else "",
                        value = if (hasDay || row.boughtToday)
                            plLead(plMode, row.dayPnl, row.dayPnlPct) else "--",
                        sub = if (hasDay || row.boughtToday)
                            plSub(plMode, row.dayPnl, row.dayPnlPct) else null,
                        color = if (hasDay || row.boughtToday) signColor(row.dayPnl)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MoneyCell(
                        modifier = Modifier.weight(1f),
                        label = "SINCE YOU BOUGHT",
                        value = if (row.price > 0)
                            plLead(plMode, row.totalPnl, row.totalPct) else "--",
                        sub = if (row.price > 0)
                            plSub(plMode, row.totalPnl, row.totalPct) else null,
                        color = if (row.price > 0) signColor(row.totalPnl)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.boughtToday) {
                    Text(
                        "* counted from the price you actually paid, not the previous close",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Spacer(Modifier.weight(1f))
                // LEFT OF NEWS. The detail screen's own indicator moved to sit beside the
                // price instead (see `RecommendationBadge`, `DetailScreen.kt`'s price-header
                // block) - this row's chip is unaffected, Tj only asked to move the other one.
                // Not offered on a watch-only row: the verdict is about whether to change a
                // position TJ already holds, and a watched-but-unheld symbol has none.
                if (!row.watchOnly) {
                    val tint = recommendation?.let { verdictTint(it.verdict) }
                    Box(
                        Modifier
                            .minTapTarget()
                            .testTag(RECOMMENDATION_CHIP_TEST_TAG)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .then(
                                if (tint != null) Modifier.border(1.dp, tint, RoundedCornerShape(6.dp))
                                else Modifier
                            )
                            .combinedClickable(
                                onClick = { showRecommendation = true },
                                onLongClick = { menu = true }
                            )
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            recommendation?.let { verdictWord(it.verdict) } ?: "...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = tint ?: accentText
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    Modifier
                        // 48dp, MEASURED. The previous attempt at this raised the padding and
                        // landed at 33dp - still under the minimum, and still inside the row's
                        // own click area, so a near-miss still opened the detail screen.
                        .minTapTarget()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = onNews, onLongClick = { menu = true })
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "News",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // The text-legible accent - the brand blue on light, brighter on dark,
                        // where it was 3.39:1 on this chip's own background. See `accentText`.
                        color = accentText
                    )
                }
            }
        }

        if (showRecommendation) {
            RecommendationDialog(recommendation, row.symbol) { showRecommendation = false }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                row.symbol,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            DropdownMenuItem(
                text = { Text("View details") },
                onClick = { menu = false; onAction(RowAction.OPEN) }
            )
            DropdownMenuItem(
                text = { Text("News") },
                onClick = { menu = false; onAction(RowAction.NEWS) }
            )
            if (!row.watchOnly) {
                DropdownMenuItem(
                    text = { Text("Edit shares / average cost") },
                    onClick = { menu = false; onAction(RowAction.EDIT_POSITION) }
                )
                DropdownMenuItem(
                    text = { Text("Add a buy or sell") },
                    onClick = { menu = false; onAction(RowAction.ADD_TXN) }
                )
            }
            // `row.watched`, NOT `row.watchOnly` (Round 66 audit, PUI-7) - the two differ for
            // a symbol that is both held and watched. See [Row.watched].
            DropdownMenuItem(
                text = { Text(if (row.watched) "Remove from watchlist" else "Also watch this") },
                onClick = { menu = false; onAction(RowAction.WATCH_TOGGLE) }
            )
            if (!row.watchOnly) {
                DropdownMenuItem(
                    // `redText`, matching the identical item on the detail screen - the two
                    // drifted apart when only the accessor was fixed (Round 66 audit, REG-5).
                    text = { Text("Delete this position", color = redText) },
                    onClick = { menu = false; onAction(RowAction.DELETE) }
                )
            }
        }
    }
}

/**
 * THE ROW'S TOP LINE: THE TEXT AT THE WIDTH IT ASKS FOR, THE CHART FOR EVERYTHING LEFT.
 *
 * ---- WHY THIS IS A LAYOUT AND NOT TWO WEIGHTS
 *
 * A weight RESERVES its share. `weight(1.6f)` against `weight(1f)` hands the text column 200dp
 * of a 325dp row whether the string in it is 200dp wide or 140dp wide, and when it is 140 the
 * other 60 stay blank - inside the text column, where nothing can reach them. That blank strip
 * is the exact thing TJ photographed twice. No ratio removes it, because the gap is not the
 * ratio: it is the difference between what the text was GIVEN and what it WANTED.
 *
 * Measuring closes it by construction. The text is asked, through its intrinsic width, how
 * wide it would like to be; it is given that, and the chart is given the rest. There is no
 * third share left over to be blank, and the split follows the actual string - a four-figure
 * holdings line takes its width back out of the chart rather than leaving a hole.
 *
 * ---- THE FLOOR, AND WHY IT IS A FRACTION AND NOT A DP
 *
 * One row's text really can want more than the row: a watchlist entry's second line is a
 * company name. Pure measuring would hand it everything and leave the chart a sliver - a chart
 * made SMALLER by the round that exists to make it bigger. [ROW_CHART_MIN_FRACTION] is round
 * 64's own share, so the floor case reproduces round 64 exactly and every other case is wider.
 * A floor in dp could not say that: it would be right on one screen width and wrong on the
 * rest.
 *
 * ---- TOTAL BY CONSTRUCTION
 *
 * This runs during layout, where a thrown exception is a blank screen on a list the user is
 * scrolling. An unbounded width, a missing child and a zero-width row are all handled by
 * producing some layout rather than by throwing.
 */
@Composable
private fun TextThenChart(
    text: @Composable () -> Unit,
    chart: @Composable () -> Unit
) {
    Layout(content = { text(); chart() }) { measurables, constraints ->
        // Exactly two by construction - one node from each slot. Read defensively anyway:
        // see the note above about what an exception costs here.
        val textM = measurables.getOrNull(0)
        val chartM = measurables.getOrNull(1)
        if (textM == null || chartM == null) {
            return@Layout layout(0, 0) {}
        }

        val gap = ROW_CHART_GAP.roundToPx()
        val avail = constraints.maxWidth

        // ---- NOTHING TO DIVIDE UP.
        //
        // An intrinsic-width pass measures with `Constraints.Infinity`, and so does a
        // horizontally scrolling parent. "Everything left over" is meaningless there -
        // Infinity minus the text is still Infinity, and a Placeable that wide crashes. Both
        // halves take a size of their own instead, and the row reports their sum.
        if (avail == Constraints.Infinity) {
            val t = textM.measure(Constraints())
            val cw = ROW_CHART_FALLBACK.roundToPx()
            val c = chartM.measure(Constraints(minWidth = cw, maxWidth = cw))
            val h = maxOf(t.height, c.height)
            return@Layout layout(t.width + gap + c.width, h) {
                t.place(0, (h - t.height) / 2)
                c.place(t.width + gap, (h - c.height) / 2)
            }
        }

        val content = (avail - gap).coerceAtLeast(0)
        val chartFloor = (content * ROW_CHART_MIN_FRACTION).toInt().coerceIn(0, content)
        // THE WHOLE POINT: what the text would take if nothing constrained it. `AutoFitNumber`
        // reports this at its full size rather than at whatever size it last shrank to, so the
        // answer does not depend on the previous frame.
        val wanted = textM.maxIntrinsicWidth(constraints.maxHeight)
        val textW = wanted.coerceIn(0, content - chartFloor)
        val chartW = content - textW

        val t = textM.measure(constraints.copy(minWidth = 0, maxWidth = textW, minHeight = 0))
        val c = chartM.measure(
            constraints.copy(minWidth = chartW, maxWidth = chartW, minHeight = 0)
        )
        val h = maxOf(t.height, c.height)
        layout(avail, h) {
            t.place(0, (h - t.height) / 2)
            // MEASURED FROM THE RIGHT EDGE rather than from the text's width. The two are the
            // same number when the chart takes the width it was given, and this way the chart
            // is still flush with the row's content edge if it ever does not - which is the
            // property the screenshot was about.
            c.place(avail - c.width, (h - c.height) / 2)
        }
    }
}

/** One of the three "your money" figures: a quiet label over a bold number. */
@Composable
private fun MoneyCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String? = null
) {
    Column(modifier.padding(end = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        // ---- THE FIGURES SHRINK, THEY DO NOT GET CUT (Round 63 sweep).
        //
        // These are three `weight(1f)` columns, about 118dp each on a 411dp phone. A
        // six-figure holding at a large font scale used to render as "$123,45..." - still a
        // well-formed dollar amount, and nothing else on the row says which order of magnitude
        // is right. The `sub` line below was worse: `maxLines = 1` with no `overflow` defaults
        // to Clip, so in percent-first mode a five-figure dollar P/L was shortened with no
        // ellipsis and no cue at all. See [AutoFitNumber].
        AutoFitNumber(
            value,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        if (sub != null) {
            AutoFitNumber(
                sub,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                minSp = 9
            )
        }
    }
}
