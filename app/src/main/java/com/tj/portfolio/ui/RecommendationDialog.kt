package com.tj.portfolio.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.Recommendation
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.util.Fmt

/** "Buy" / "Hold" / "Sell" - the exact word TJ asked to see on the tab itself. */
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
