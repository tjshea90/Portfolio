package com.tj.portfolio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.Recommendation
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.util.Fmt

/** "Buy" / "Hold" / "Sell" - the exact word the badge and popup title show. */
fun verdictWord(v: TradeVerdict): String = when (v) {
    TradeVerdict.BUY -> "Buy"
    TradeVerdict.HOLD -> "Hold"
    TradeVerdict.SELL -> "Sell"
}

/** Green/amber/red - the same traffic-light convention the rest of the app uses for gains and losses. */
fun verdictTint(v: TradeVerdict): Color = when (v) {
    TradeVerdict.BUY -> Color(0xFF2E7D32)
    TradeVerdict.HOLD -> Color(0xFFF9A825)
    TradeVerdict.SELL -> Color(0xFFC62828)
}

/**
 * [verdictTint] as TEXT - the same fill-vs-text split every other coloured text in this app
 * follows (see Theme.kt's `greenText`/`redText`/`scoreColor`). Measured on `surfaceVariant`,
 * the badge/chip/dialog's own background: BUY's green passes light (4.70:1) but not dark
 * (3.18:1); HOLD's amber is the opposite - 8.28:1 dark, 1.81:1 light; SELL's red passes light
 * (5.15:1) but not dark (2.91:1). [verdictTint] itself is untouched - it is right as a FILL/
 * border, same as the fill colours in Theme.kt - only the text pulls a per-theme value, reusing
 * the same greenText/redText/scoreColor tier-2-amber pairs already measured elsewhere.
 */
@Composable
fun verdictTextColor(v: TradeVerdict): Color {
    val dark = LocalDarkTheme.current
    return when (v) {
        TradeVerdict.BUY -> if (dark) greenText else verdictTint(v)
        TradeVerdict.HOLD -> if (dark) Color(0xFFD79A2B) else Color(0xFF8A6410)
        TradeVerdict.SELL -> if (dark) redText else verdictTint(v)
    }
}

/** Test handle for the badge, whose text varies with the verdict. */
internal const val RECOMMENDATION_BADGE_TEST_TAG = "recommendationBadge"

/**
 * The BUY/HOLD/SELL indicator on a stock's detail screen. TJ, with a screenshot: *"move the buy
 * sell hold tab from where it currently is to somewhere around where the arrow points. don't
 * change it's function, only the placement."* It used to be a tab in the strip below the price
 * (`DetailTab.RECOMMENDATION`, since removed - see `DetailScreen.kt`'s price-header block);
 * this is the same verdict, same color, same tap-opens-`RecommendationDialog` behaviour, just
 * living beside the price instead of in the tab row. Ordinary flow layout, not an overlay - it
 * cannot collide with `PriceBlock`'s own content the way an absolutely-positioned badge could
 * at a large font scale.
 */
@Composable
fun RecommendationBadge(recommendation: Recommendation?, onClick: () -> Unit) {
    val tint = recommendation?.let { verdictTint(it.verdict) }
    Box(
        Modifier
            .minTapTarget()
            .testTag(RECOMMENDATION_BADGE_TEST_TAG)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .then(if (tint != null) Modifier.border(1.dp, tint, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            recommendation?.let { verdictWord(it.verdict) } ?: "...",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = recommendation?.let { verdictTextColor(it.verdict) } ?: MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The popup behind the BUY/HOLD/SELL tab. TJ: *"if I click on the buy hold or sell tab for any
 * stock, a pop up should appear with the reasoning behind the recommendation and a target
 * price for any transaction of the stock."*
 *
 * [r] is null in the brief window before the first compute lands for a symbol, or when there
 * simply is not enough published data to compute one at all (`Recommend.build` returns an
 * honest null rather than a guess in that case) - shown as a plain "still gathering" state
 * rather than a blank or broken-looking dialog.
 */
@Composable
fun RecommendationDialog(r: Recommendation?, symbol: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "$symbol - ${r?.let { verdictWord(it.verdict) } ?: "Recommendation"}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (r == null) {
                    Text(
                        "Still gathering data for $symbol - check back in a moment.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, verdictTint(r.verdict))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                verdictWord(r.verdict).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = verdictTint(r.verdict),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (r.hasTarget) {
                                    "Average analyst target " + Fmt.price(r.targetMean) +
                                        if (r.targetHigh > 0.0 && r.targetLow > 0.0)
                                            " (range ${Fmt.price(r.targetLow)} - ${Fmt.price(r.targetHigh)})"
                                        else ""
                                } else {
                                    "No analyst price target is published for $symbol"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (r.hasTarget && !r.upsidePct.isNaN()) {
                                Text(
                                    (if (r.upsidePct >= 0.0) "+" else "") + Fmt.pct(r.upsidePct) +
                                        " vs today's " + Fmt.price(r.price),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Why",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    r.reasons.forEach { line ->
                        Text(
                            "• $line",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    if (r.analystCount == 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "No analyst coverage found for $symbol - this reads on price, " +
                                "valuation and growth alone, so treat it with extra caution.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (r.confidence < 60) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Only part of the usual picture was published for $symbol - treat " +
                                "this with extra caution.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Not financial advice. A rule-based read of price, valuation, growth " +
                            "and published analyst views, recomputed once each trading day - " +
                            "not a guarantee of what the stock will do next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}
