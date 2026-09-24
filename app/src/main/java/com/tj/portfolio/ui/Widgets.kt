package com.tj.portfolio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.util.Fmt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE LINE BETWEEN ONE STOCK AND THE NEXT.
 *
 * TJ: *"for my portfolio or anywhere else that stocks are shown in a vertical list, make
 * the separation between stocks a little more pronounced. do this by either more space
 * between stocks or a thicker line between stocks or both."* Both, and there was a reason
 * the old one read as weak that is worth writing down:
 *
 * every row ALREADY contains a divider of its own - the one splitting "the stock" from
 * "your money" inside `StockRowItem` - and it was drawn at exactly the same 1dp in exactly
 * the same colour as the line between rows. So the strongest visual break on the screen was
 * indistinguishable from a break INSIDE a single row, and the eye had nothing to group by.
 * Making this line heavier without also making the inner one lighter would have left both
 * shouting; the hierarchy is what was missing, not the weight.
 *
 * So: [ROW_RULE] dp in [rowRule] here, with [ROW_GAP] dp of air either side, against 1dp of
 * `outline` at 45% opacity inside the row. Same two elements, now unambiguous about which is
 * which.
 *
 * THE COLOUR IS THE OTHER HALF OF IT (Round 66 audit, PUI-4). This bar was painted in
 * `colorScheme.outline` through both thickness bumps, and `outline` measures 1.24:1 against
 * the light background - so what changed each round was the WIDTH of a strip that was very
 * nearly invisible either way. See [rowRule] for the numbers.
 */
@Composable
fun RowSeparator(modifier: Modifier = Modifier) {
    Spacer(Modifier.height(ROW_GAP.dp))
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        thickness = ROW_RULE.dp,
        color = rowRule
    )
    Spacer(Modifier.height(ROW_GAP.dp))
}

/**
 * Breathing space either side of [RowSeparator].
 *
 * It grows with the rule (Round 66). A heavier bar pressed hard against the text above and
 * below it reads as crowding rather than as separation - the white space is half of what
 * makes a divider legible, and a 5dp line with 5dp of air is a line with no room.
 */
private const val ROW_GAP = 7

/**
 * How heavy the line between two stocks is.
 *
 * 5dp (Round 66). TJ has asked for this twice - it was 1dp, then 3dp, and then *"make the
 * separation bars between stocks even thicker"*. This is a list where each entry is itself a
 * two-part block with its own internal divider ([InRowDivider]), so the line between two
 * DIFFERENT stocks has to be unmistakably heavier than the line inside one of them or the
 * eye groups the wrong halves together. At 5dp against that 1dp hairline the ratio is five
 * to one in thickness - and, since PUI-4 gave this bar [rowRule] and left the inner one on
 * `outline` at 45%, about nine to one in contrast as well.
 */
private const val ROW_RULE = 5

/**
 * The divider used INSIDE a row, which must read as lighter than [RowSeparator].
 *
 * Hairline and half-strength: it is separating two halves of one thing, not two things.
 */
@Composable
fun InRowDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    )
}

/** Circular letter avatar standing in for a company logo. */
@Composable
fun Avatar(symbol: String, size: Int = 38) {
    val palette = listOf(
        Color(0xFF3D6BE5), Color(0xFF16A085), Color(0xFFE07B39), Color(0xFF8E44AD),
        Color(0xFF2C82C9), Color(0xFFC0392B), Color(0xFF13878A), Color(0xFF6D4AFF)
    )
    // mask rather than negate: -Int.MIN_VALUE is still negative, which would index out of bounds
    val c = palette[(symbol.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier = Modifier.size(size.dp).background(c.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol.take(1).uppercase(),
            color = c,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.42f).sp
        )
    }
}

/**
 * Intraday sparkline. Green when the last point is at or above the first, red otherwise,
 * with a faint fill under the line and a dashed baseline at the previous close.
 */
@Composable
fun Sparkline(
    points: List<Double>,
    baseline: Double = 0.0,
    modifier: Modifier = Modifier,
    color: Color? = null
) {
    val pts = points.filter { it > 0.0 }
    if (pts.size < 2) {
        Box(modifier)
        return
    }
    val first = if (baseline > 0.0) baseline else pts.first()
    val up = pts.last() >= first
    // The text-legible pair, not the fill one: this is a hairline on a white row, where
    // #16C784 measures 2.20:1 and reads as a smudge. The P/L figures beside it use the same
    // values, so a row is one colour rather than two. See the note on `signColor`.
    val lineColor = color ?: if (up) greenText else redText
    val min = minOf(pts.min(), if (baseline > 0) baseline else pts.min())
    val max = maxOf(pts.max(), if (baseline > 0) baseline else pts.max())
    val span = (max - min).let { if (it < 1e-9) 1.0 else it }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val pad = h * 0.12f
        fun y(v: Double) = (h - pad) - ((v - min) / span).toFloat() * (h - pad * 2)
        fun x(i: Int) = w * i / (pts.size - 1).toFloat()

        // ROUND 58: DP, NOT RAW PIXELS. `DrawScope` measures in physical pixels, so the
        // literals here were about 1.1dp of line on an xhdpi screen and roughly 0.8dp on the
        // ~400dpi panel this app actually runs on - a hairline, and the same figure produced
        // a visibly different weight on every different phone. `DrawScope` is a `Density`,
        // so converting costs nothing and is right everywhere.
        val lineStroke = 1.6.dp.toPx()
        val dash = 2.5.dp.toPx()
        val gap = 2.5.dp.toPx()

        if (baseline > 0.0) {
            val by = y(baseline)
            var sx = 0f
            while (sx < w) {
                drawLine(
                    Color.Gray.copy(alpha = 0.45f),
                    Offset(sx, by), Offset(minOf(sx + dash, w), by),
                    strokeWidth = 1.dp.toPx()
                )
                sx += dash + gap
            }
        }

        val path = Path().apply {
            moveTo(x(0), y(pts[0]))
            for (i in 1 until pts.size) lineTo(x(i), y(pts[i]))
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.20f), lineColor.copy(alpha = 0f)))
        )
        drawPath(
            path,
            lineColor,
            style = Stroke(width = lineStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * The extended-hours price, but only when it belongs on screen - the single rule every
 * caller shares, so the price block, the explanatory note and the position's extended
 * gain/loss can never disagree about whether extended trading is happening.
 *
 * Null during the regular session. `MarketData` already leaves `extPrice` null while the
 * market is open, but a CACHED quote read at startup can still be carrying last night's
 * print, and that stale number must not be shown next to a live one.
 */
fun extendedPrice(q: com.tj.portfolio.data.Quote?): Double? {
    val p = q?.extPrice ?: return null
    if (p <= 0.0 || q.marketState == "OPEN") return null
    return p
}

/**
 * The price display, labelled.
 *
 * Every number here used to be unlabelled: a big figure, then a bare "+0.13 +0.15%", then a
 * cryptic "AH $89.42 +0.22%". Nothing said which number was the live price and which was the
 * close, or which change belonged to which session. Now:
 *
 * DURING THE REGULAR SESSION - one column, no caption. The heading already says the market
 * is open, so naming the session again adds nothing:
 *
 *   CURRENT PRICE  ·  MARKET OPEN
 *   $268.26
 *   +$1.24
 *   +0.46%
 *
 * ONCE EXTENDED TRADING HAS PRINTED - two columns, and NOW the captions earn their place,
 * because there are two different sessions on screen at the same time:
 *
 *   CLOSING PRICE  ·  MARKET CLOSED
 *   $268.26
 *   ┌ DURING MARKET HOURS ─┬ AFTER HOURS / OVERNIGHT ─┐
 *   │ +$1.24  +0.46%       │ $269.10                  │
 *   │                      │ +$0.84  +0.31%           │
 *
 * Shared by the list rows and the stock detail screen so the two can never drift apart;
 * [big] just scales it up for the detail screen.
 */
@Composable
fun PriceBlock(row: Row, big: Boolean = false, modifier: Modifier = Modifier) {
    val q = row.quote
    val hasPrice = row.price > 0
    val hasDay = hasPrice && (q?.prevClose ?: 0.0) > 0.0
    val ext = extendedPrice(q)
    val isPre = q?.extLabel?.startsWith("Pre", true) == true

    /**
     * The extended-hours column exists ONLY when there is a real extended-hours print.
     *
     * It used to render unconditionally, so all through the regular session there was an
     * "AFTER HOURS / OVERNIGHT" heading with "--" and "no trading yet" under it - a labelled
     * space for a number that cannot exist yet. TJ asked for it gone until it has something
     * to say. `marketState != "OPEN"` is the second half of the guard: a CACHED quote can
     * still be carrying last night's extended print when the market reopens, and that stale
     * figure must not reappear beside a live one.
     *
     * Built as a nullable triple rather than a boolean so the values are computed once,
     * inside a single explicit null check - which is also what keeps the smart cast on `q`
     * and `ext` and avoids reintroducing `!!` here.
     */
    /**
     * (price, dollar change, percent change, percent as a number for the colour).
     *
     * ROUND 58 - TJ: *"for the after market/overnight section of stock prices, stack the
     * dollar amount and percent change vertically to be uniform with the other sections.
     * right now they are side by side horizontally."*
     *
     * They were one pre-joined string - `"+0.42   +0.19%"` - which is why they sat on a
     * line together while the market-hours column beside them had its two figures stacked.
     * Kept apart now so [SessionCell] can lay them out the same way as every other cell, and
     * so neither can be silently clipped off the end of a shared line.
     */
    val extCell: ExtCell? =
        if (q != null && ext != null)
            ExtCell(
                price = Fmt.price(ext),
                change = Fmt.changeMoney(ext, q.extChange),
                percent = Fmt.pctSignedBeside(q.extChangePct, q.extChange, ext),   // R2-1
                pct = q.extChangePct
            )
        else null
    val hasExt = extCell != null

    val heading = when {
        !hasPrice -> "NO QUOTE YET"
        q?.marketState == "OPEN" -> "CURRENT PRICE  ·  MARKET OPEN"
        q?.marketState == "DELAYED" -> "LAST PRICE  ·  DELAYED FEED"
        q?.marketState == "PRE" -> "LAST CLOSING PRICE  ·  PRE-MARKET"
        q?.marketState == "AFTER" -> "CLOSING PRICE  ·  MARKET CLOSED"
        hasExt -> "CLOSING PRICE  ·  MARKET CLOSED"
        else -> "LATEST PRICE"
    }

    Column(modifier) {
        Text(
            heading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(if (big) 4.dp else 2.dp))
        Text(
            if (hasPrice) Fmt.price(row.price) else "--",
            fontSize = if (big) 34.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(if (big) 12.dp else 9.dp))
        Row(Modifier.fillMaxWidth()) {
            SessionCell(
                modifier = if (extCell != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                // Labelled only when there is a second column to be told apart from. On its
                // own it needs no caption: the heading directly above already says whether
                // the market is open, so "DURING MARKET HOURS" was stating the obvious.
                label = if (extCell != null) "DURING MARKET HOURS" else null,
                first = if (hasDay) Fmt.changeMoney(row.price, row.dayChange) else "--",
                second = if (hasDay) Fmt.pctSignedBeside(row.dayPct, row.dayChange, row.price)
                else "not available",
                // THE BASELINE, SPELLED OUT. This is the piece TJ said was missing: the two
                // columns are measured against DIFFERENT things - `dayChange` is
                // `price - prevClose` while `extChange` is `extPrice - price` - and until now
                // nothing on screen said so, leaving two percentages side by side with no way
                // to know what either was a percentage OF.
                basis = if (hasDay) "vs previous close" else null,
                // By the dollar figure, at its own precision (R2-1).
                color = if (hasDay) signColor(row.dayChange, Fmt.changeEps(row.price))
                else MaterialTheme.colorScheme.onSurfaceVariant,
                big = big
            )
            if (extCell != null) {
                VerticalRule()
                SessionCell(
                    modifier = Modifier.weight(1f),
                    label = if (isPre) "PRE-MARKET" else "AFTER HOURS / OVERNIGHT",
                    // the extended-hours PRICE is the number people actually want here
                    firstTag = "price now",
                    first = extCell.price,
                    secondTag = "change",
                    second = extCell.change,
                    // The percentage now sits UNDER the dollar figure rather than beside it,
                    // which is what makes this column read the same way as the market-hours
                    // one to its left.
                    third = extCell.percent,
                    // Pre-market is measured from the last regular close, which is the
                    // previous day's; after hours it is measured from today's close. Same
                    // arithmetic, different day, so the wording has to differ too.
                    basis = if (isPre) "vs last close" else "vs today's close",
                    color = extCell.color,
                    firstIsNeutral = true,
                    big = big
                )
            }
        }
    }
}

/** The three strings the extended-hours column shows, plus the number that colours them. */
private data class ExtCell(
    val price: String,
    val change: String,
    val percent: String,
    val pct: Double
)

/**
 * One labelled session column inside [PriceBlock].
 *
 * EVERY NUMBER IN HERE IS NAMED, and that is the point of the tags and the basis line. TJ's
 * report was "under the after hours/overnight there are two percentage numbers with no
 * explanation" - the cell showed a price, then a money change and a percentage on one line,
 * with nothing saying which was which or what they were measured from.
 *
 * @param firstTag  what the first figure IS ("price now"). Null where the heading already
 *                  says it - the market-hours column's figure is plainly the day's change.
 * @param secondTag likewise for the second figure.
 * @param basis     what the change is measured AGAINST. The most important line here: the
 *                  two columns use different baselines and the numbers are meaningless
 *                  without it.
 */
@Composable
private fun SessionCell(
    label: String?,
    first: String,
    second: String,
    color: Color,
    modifier: Modifier = Modifier,
    firstTag: String? = null,
    secondTag: String? = null,
    /**
     * A third figure stacked under [second], in the same colour and at the same size.
     *
     * Exists so the extended-hours column can show its dollar change and its percentage the
     * way the market-hours column already did - one above the other - instead of joined into
     * a single string. Null everywhere else, and a null takes no vertical space with it.
     */
    third: String? = null,
    basis: String? = null,
    firstIsNeutral: Boolean = false,
    big: Boolean = false
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.padding(end = 8.dp)) {
        // A null label takes its spacer with it - an empty Text would leave the gap behind
        // and the numbers would still sit as though something were written above them.
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
        }
        if (firstTag != null) {
            Text(firstTag, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
        }
        Text(
            first,
            fontSize = if (big) 19.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            // the after-hours PRICE is a price, not a gain, so it is not painted green/red
            color = if (firstIsNeutral) MaterialTheme.colorScheme.onSurface else color
        )
        if (secondTag != null) {
            Spacer(Modifier.height(2.dp))
            Text(secondTag, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
        }
        Text(
            second,
            fontSize = if (big) 14.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        if (third != null) {
            Text(
                third,
                fontSize = if (big) 14.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        if (basis != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                basis,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        Modifier
            .padding(end = 10.dp)
            .width(1.dp)
            .height(46.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

/**
 * The label / value row used throughout the stat cards.
 *
 * ---- IT MEASURES, BECAUSE NEITHER HALF IS EXPENDABLE.
 *
 * This has now failed twice in opposite directions, which is what makes it worth a real
 * layout rather than a third guess at weights:
 *
 *  1. **Originally** it was two UNWEIGHTED children of a `Row` with `SpaceBetween`. Compose
 *     measures unweighted children in order against whatever width is left, and `SpaceBetween`
 *     is an arrangement, not a measurement policy - so a label long enough to wrap took the
 *     whole row and **the value was measured at zero width and vanished**. On the portfolio
 *     summary card that bit at 1.15x, one step above the default font scale.
 *  2. **Then the label was weighted**, which reversed the order and reversed the failure: at
 *     2.0x, with a long value like "+$1,234,567.89  (+1234.56%)" in a 359dp card, the LABEL
 *     was measured at zero and an unlabelled signed figure was left on the row.
 *
 * There is no split of one line that holds both at 2.0x, because at 2.0x they genuinely do not
 * fit on one line. So this measures: it gives the value what it needs, and if what remains for
 * the label falls below [MIN_LABEL_DP] it **stacks them instead** - label above, value below,
 * right-aligned - which is what every other app does when a row runs out of room. Both halves
 * survive at every font scale, and the fallback only engages when the alternative was losing
 * one of them.
 */
private const val MIN_LABEL_DP = 72

@Composable
fun KeyValue(label: String, value: String, valueColor: Color? = null, bold: Boolean = false) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val minLabel = with(LocalDensity.current) { MIN_LABEL_DP.dp.roundToPx() }
    Layout(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        content = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // The value shrinks before it is ever cut - it is a number, and a shortened number
            // is a different number. See [AutoFitNumber].
            AutoFitNumber(
                value,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
        }
    ) { measurables, constraints ->
        // BOUNDED, because `layout()` places children against this number. An unbounded
        // `maxWidth` - a horizontal scroller, or an intrinsic-measurement pass - would put the
        // value about sixteen million pixels off screen. No call site does that today; this is
        // one line so that none ever can.
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else measurables.sumOf { it.maxIntrinsicWidth(constraints.maxHeight) }
        val loose = Constraints(maxWidth = width)
        val valuePlaceable = measurables[1].measure(loose)
        val forLabel = width - valuePlaceable.width - gap

        if (forLabel >= minLabel) {
            // ---- one line: value at its natural width, label in the remainder.
            val labelPlaceable = measurables[0].measure(Constraints(maxWidth = forLabel))
            val h = maxOf(labelPlaceable.height, valuePlaceable.height)
            layout(width, h) {
                labelPlaceable.placeRelative(0, (h - labelPlaceable.height) / 2)
                valuePlaceable.placeRelative(
                    width - valuePlaceable.width, (h - valuePlaceable.height) / 2
                )
            }
        } else {
            // ---- two lines: neither half is dropped, and the value stays right-aligned so
            // a column of them still reads down the card.
            val labelPlaceable = measurables[0].measure(loose)
            val h = labelPlaceable.height + valuePlaceable.height
            layout(width, h) {
                labelPlaceable.placeRelative(0, 0)
                valuePlaceable.placeRelative(width - valuePlaceable.width, labelPlaceable.height)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
    )
}

/**
 * THE TWO HALVES OF A PROFIT-AND-LOSS FIGURE, IN THE ORDER THE USER ASKED FOR.
 *
 * Round 61. Every P/L on screen already showed both a dollar amount and a percentage; these
 * decide which one leads. Kept as two tiny pure functions, in one place, for a specific
 * reason: the figure appears on the holding row, the summary card and the stock's own page,
 * and a toggle that reorders two of those three is worse than no toggle at all. One helper
 * means the three cannot drift apart.
 *
 * `plLead` is the big, bold number; `plSub` is the smaller one under or beside it.
 */
fun plLead(mode: PlMode, money: Double, pct: Double): String =
    if (mode == PlMode.PERCENT) Fmt.pctSigned(pct) else Fmt.usdSigned(money)

fun plSub(mode: PlMode, money: Double, pct: Double): String =
    if (mode == PlMode.PERCENT) Fmt.usdSigned(money) else Fmt.pctSigned(pct)

/** Both on one line, as the stock page shows them: "lead  (sub)". */
fun plInline(mode: PlMode, money: Double, pct: Double): String =
    plLead(mode, money, pct) + "  (" + plSub(mode, money, pct) + ")"


/**
 * A NUMBER THAT SHRINKS RATHER THAN TRUNCATES (Round 63 sweep).
 *
 * ---- WHY THIS EXISTS
 *
 * A truncated money figure is still a well-formed money figure, and that is what makes it
 * dangerous. The holding rows lay their four figures out as three `weight(1f)` columns - about
 * 118dp each on a 411dp phone - and at a large font scale a six-figure holding rendered as
 * "$123,45…", which reads at a glance as either $123 thousand or $123 hundred. Nothing else on
 * the row carries the magnitude, so there is nothing to check it against. One of the same
 * cells was worse still: it had `maxLines = 1` and no `overflow`, so it defaulted to Clip and
 * shortened the number with no ellipsis and no cue at all that anything had been removed.
 *
 * ---- WHAT IT DOES INSTEAD
 *
 * `autoSize` steps the type down until the whole value fits, to a floor below which it stops.
 * A slightly smaller number is completely readable; a shortened one is a different number. The
 * floor is what keeps this honest - if even the smallest size will not fit, it ellipsises, and
 * an ellipsis at least says "there is more".
 *
 * The row's other text is untouched: labels may shorten freely, because a label is recoverable
 * from context and a figure is not.
 */
@Composable
fun AutoFitNumber(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight = FontWeight.Bold,
    /**
     * The floor, in **dp** rather than sp - see below. 11 is about the smallest a figure can
     * be and still be read at arm's length on a phone.
     */
    minSp: Int = 11,
    textAlign: TextAlign? = null
) {
    val density = LocalDensity.current
    val max = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 15.sp
    // ---- THE FLOOR IS A PHYSICAL SIZE, NOT A SCALED ONE.
    //
    // Written as `minSp.sp` this scaled with the user's font setting, which defeats the whole
    // widget: at 2.0x an "11sp" floor is 22dp of type in a 118dp cell, so the number stopped
    // shrinking long before it fitted and ellipsised anyway - the exact case this was written
    // for. `Dp.toSp()` divides by the font scale, so what comes out renders at a fixed
    // physical size however the slider is set.
    val floor = with(density) { minSp.dp.toSp() }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(
            TextStyle(color = color, fontWeight = fontWeight, textAlign = textAlign ?: TextAlign.Start)
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            // Guarded, because at a small font scale the floor can exceed the ceiling and
            // `StepBased` requires min <= max.
            minFontSize = minOf(floor.value, max.value).sp,
            // Never LARGER than the style asks for - this is a fallback for tight rows, not a
            // licence to grow into whatever space happens to be free.
            maxFontSize = max,
            // A WHOLE POINT PER STEP, not a half. Neighbouring cells in one row size
            // themselves independently, so a fine step means three comparable figures render
            // at three visibly different sizes on the same line. A coarse one keeps them on a
            // short ladder, which reads as deliberate rather than as a rendering fault.
            stepSize = 1.sp
        )
    )
}
