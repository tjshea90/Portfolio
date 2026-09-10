package com.tj.portfolio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.MetricDef
import com.tj.portfolio.data.MetricUnit
import com.tj.portfolio.util.Fmt

/**
 * The metric row and the "i" sheet behind it.
 *
 * ONE SHAPE FOR EVERY NUMBER. Every figure on the Stats, Analysts and Earnings tabs is
 * drawn by [MetricRow]: label on the left, value on the right, and a tappable "i" between
 * them that opens the full beginner explanation from [Explain]. That uniformity is the
 * point - a user learns the gesture once and it works on all fifty-odd numbers, and a
 * metric physically cannot be added to a tab without an explanation behind it, because the
 * row is what draws it.
 *
 * The "i" is a 40dp touch target around a 20dp circle. It looked absurd at 20dp on a
 * fingertip; the app's own [minTapTarget] rule exists for exactly this and is applied here.
 */

/** Format a raw value the way its unit says it should read. */
fun formatMetric(unit: MetricUnit, v: Double): String = when (unit) {
    MetricUnit.MONEY -> (if (v < 0) "-$" else "$") + Fmt.compact(kotlin.math.abs(v))
    MetricUnit.PRICE -> Fmt.price(v)
    MetricUnit.RATIO -> String.format(java.util.Locale.US, "%.2f", v)
    MetricUnit.PERCENT -> String.format(java.util.Locale.US, "%.2f%%", v)
    MetricUnit.FRACTION -> String.format(java.util.Locale.US, "%.2f%%", v * 100.0)
    MetricUnit.COUNT -> Fmt.compact(v)
    MetricUnit.DATE -> if (v > 0) Fmt.day(v.toLong()) else "-"
    MetricUnit.TEXT -> v.toString()
}

/** Green for good, red for bad, amber for "it depends", plain text otherwise. */
@Composable
fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.GOOD -> Green
    Verdict.BAD -> Red
    Verdict.MIXED -> Accent
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * The small circled "i". Sized for a fingertip rather than for the glyph.
 *
 * AN EXPLICIT `size`, not the app's [minTapTarget] helper. `defaultMinSize` only raises a
 * minimum constraint, which leaves the final height at the mercy of whatever the parent
 * hands down; a fixed 40dp cannot be argued with, and the rendered test asserts it. 40
 * rather than Material's 48 because this target sits INSIDE a row that is itself tappable
 * and opens the same explanation, so a near-miss costs the user nothing.
 */
@Composable
fun InfoDot(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clickable(onClickLabel = "What does $label mean?") { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "i",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
        }
    }
}

/**
 * One metric: label, "i", value.
 *
 * A NULL VALUE IS NOT A ZERO. When a provider does not report a figure the row prints
 * "not reported" in muted text rather than "0.00" - a company with no dividend has no
 * payout ratio, and printing 0% there would be a wrong number that looks like a real one.
 * The "i" still works, and explains exactly that.
 */
@Composable
fun MetricRow(
    def: MetricDef,
    f: Fundamentals,
    price: Double,
    onInfo: (String) -> Unit,
    /**
     * Left inset. 16dp on a bare list; 0 inside a [StatCard], which supplies its own 14dp -
     * without this the key-numbers card on the Overview tab sat 30dp in from the labels
     * beside it.
     */
    startPad: Int = 16
) {
    val value = f.value(def.key)
    // KEYED ON A COARSE PRICE, NOT THE EXACT ONE (Round 57).
    //
    // `price` as a key meant every visible metric row rebuilt its whole explanation object on
    // every 15-second tick, because the price almost always moves by a cent. Only a couple of
    // these metrics depend on price at all - 52-week position, target upside - and none of
    // them changes its verdict over a tenth of a percent.
    //
    // The bucket is LOGARITHMIC so the granularity is relative (about 0.2%) and is the same
    // for a $3 stock and a $900 one; a fixed cent-or-dollar step cannot do that. And the
    // "no price yet" sentinel is `Long.MIN_VALUE` rather than 0, because `ln(1.0)/0.002` is
    // itself 0 - sharing that value would leave a sub-$1.002 stock whose quote arrived after
    // the first composition stuck with the verdict computed against a price of zero.
    val priceBucket = if (price > 0.0)
        kotlin.math.floor(kotlin.math.ln(price) / 0.002).toLong() else Long.MIN_VALUE
    val verdict = remember(f, def.key, priceBucket) {
        if (value == null) Verdict.UNKNOWN else Explain.of(def.key, f, price).verdict
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onInfo(def.key) }
            .padding(start = startPad.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            def.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        InfoDot(def.label) { onInfo(def.key) }
        Spacer(Modifier.width(2.dp))
        Text(
            if (value == null) "not reported" else formatMetric(def.unit, value),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (value == null) FontWeight.Normal else FontWeight.SemiBold,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
            else verdictColor(verdict)
        )
    }
}

/**
 * A label/value row that is not a catalogue metric but still deserves an "i" - the analyst
 * consensus, a price target, the next earnings date.
 */
@Composable
fun TopicRow(
    label: String,
    value: String,
    topic: String,
    valueColor: Color? = null,
    startPad: Int = 16,
    onInfo: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onInfo(topic) }
            .padding(start = startPad.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        InfoDot(label) { onInfo(topic) }
        Spacer(Modifier.width(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The explanation sheet.
 *
 * Ordered the way a beginner actually asks: what IS this, then what counts as good or bad,
 * then what it does to the price soon and later, and finally what this particular stock's
 * number says. The live read is put in a tinted card at the top of that last section so it
 * is findable without reading the theory again on a second visit.
 *
 * Scrollable and height-capped: several of these run to a few hundred words, and an
 * AlertDialog that overflows the screen simply clips its buttons off the bottom.
 */
@Composable
fun ExplainDialog(e: Explanation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (e.read.isNotBlank()) {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, verdictColor(e.verdict))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                verdictLabel(e.verdict),
                                style = MaterialTheme.typography.labelSmall,
                                color = verdictColor(e.verdict),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(e.read, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Para("What it means", e.plain)
                Para("What counts as good or bad", e.scale)
                Para("Effect on the price - short term", e.shortTerm)
                Para("Effect on the price - long term", e.longTerm)
                Spacer(Modifier.height(10.dp))
                Text(
                    Explain.DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

@Composable
private fun Para(heading: String, text: String) {
    if (text.isBlank()) return
    Text(
        heading.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Accent,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 3.dp)
    )
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun verdictLabel(v: Verdict): String = when (v) {
    Verdict.GOOD -> "READING THIS STOCK - POSITIVE"
    Verdict.BAD -> "READING THIS STOCK - NEGATIVE"
    Verdict.MIXED -> "READING THIS STOCK - DEPENDS"
    Verdict.NEUTRAL -> "READING THIS STOCK"
    Verdict.UNKNOWN -> "READING THIS STOCK"
}

/**
 * A horizontal bar showing how the buy / hold / sell votes split.
 *
 * Drawn with weighted boxes rather than a canvas so it inherits the theme's colours and
 * costs nothing to lay out. A zero-width segment is skipped entirely - Compose gives a
 * `weight(0f)` box a real minimum size on some versions, which drew a sliver of the wrong
 * colour for a rating nobody had given.
 */
@Composable
fun VoteBar(buy: Int, hold: Int, sell: Int, modifier: Modifier = Modifier) {
    val total = buy + hold + sell
    if (total <= 0) return
    Row(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
    ) {
        if (buy > 0) Box(Modifier.weight(buy.toFloat()).fillMaxHeight().background(Green))
        if (hold > 0) Box(
            Modifier.weight(hold.toFloat()).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline)
        )
        if (sell > 0) Box(Modifier.weight(sell.toFloat()).fillMaxHeight().background(Red))
    }
}

/** Small coloured chip: BUY / HOLD / SELL, or an action word. */
@Composable
fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun bucketColor(bucket: String): Color = when (bucket) {
    com.tj.portfolio.data.AnalystRating.BUY -> Green
    com.tj.portfolio.data.AnalystRating.SELL -> Red
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Section heading with its own "i", used for whole groups on the analyst tab. */
@Composable
fun ExplainedHeader(text: String, topic: String, onInfo: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        InfoDot(text) { onInfo(topic) }
    }
}
