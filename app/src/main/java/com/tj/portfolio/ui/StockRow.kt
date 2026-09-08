package com.tj.portfolio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.util.Fmt

/** What the long-press menu asked for. */
enum class RowAction { OPEN, EDIT_POSITION, ADD_TXN, NEWS, WATCH_TOGGLE, DELETE }

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
    plMode: PlMode = PlMode.DOLLAR
) {
    var menu by remember { mutableStateOf(false) }
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

                Column(Modifier.weight(1f)) {
                    Text(
                        row.symbol,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (row.watchOnly) row.name.ifBlank { "Watching" }
                        else "${Fmt.shares(row.shares)} shares - avg ${Fmt.price(row.avgCost)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Sparkline(
                    points = q?.spark ?: emptyList(),
                    baseline = q?.prevClose ?: 0.0,
                    modifier = Modifier.width(64.dp).height(34.dp)
                )
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
            DropdownMenuItem(
                text = { Text(if (row.watchOnly) "Remove from watchlist" else "Also watch this") },
                onClick = { menu = false; onAction(RowAction.WATCH_TOGGLE) }
            )
            if (!row.watchOnly) {
                DropdownMenuItem(
                    text = { Text("Delete this position", color = Red) },
                    onClick = { menu = false; onAction(RowAction.DELETE) }
                )
            }
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
