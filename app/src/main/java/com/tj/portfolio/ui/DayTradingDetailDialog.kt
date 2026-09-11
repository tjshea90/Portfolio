package com.tj.portfolio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.util.Fmt

/**
 * "Click on each stock and there is an explanation for the buy and sell points and why the
 * stock is recommended" - Tj, 2026-09-11. Same shape as [RecommendationDialog] (title, a
 * summary card, a "Why" list, a disclaimer) applied to a day-trading row instead of a
 * buy/hold/sell verdict.
 *
 * SHOWS THE REAL TECHNICALS, NOT JUST THE LEVELS THEY PRODUCED. Tj asked for research-backed
 * signals to be "incorporated... accurately" - a reader who wants to check the app's work
 * needs to see the ATR, VWAP and opening range themselves, not just trust the entry/stop/target
 * they were built from. Zero for a row that has not been enriched with real technicals yet
 * (still using [com.tj.portfolio.net.ResearchScore.tradeLevels]'s pre-existing estimate) -
 * shown as "still gathering" for those three lines rather than a false zero.
 */
@Composable
fun DayTradingDetailDialog(r: ResearchRow, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.symbol} - Day Trading", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (r.entryPrice > 0) {
                    TradeLevelsGrid(r)
                    Spacer(Modifier.height(10.dp))
                    val rr = rewardToRisk(r)
                    Text(
                        "Risking ${Fmt.price(r.entryPrice - r.stopPrice)} a share to make " +
                            "${Fmt.price(r.targetPrice - r.entryPrice)}" +
                            (if (rr > 0) " - ${Fmt.oneDp(rr)} to 1" else "") + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (r.planByClaude)
                            "These three prices are CLAUDE'S, not the app's arithmetic - set " +
                                "from its own reading of what is happening in this stock right " +
                                "now. The app checked only that they describe a real trade " +
                                "(stop below entry, target above it, all near the live price); " +
                                "the judgment behind them is Claude's."
                        else
                            "The buy price is a TRIGGER, not the current price - a level the " +
                                "market has to reach before anything is bought, which is how a " +
                                "day trade is actually placed. The stop sits under the " +
                                "structure that would say the setup failed, sized from this " +
                                "stock's own 5-minute ATR so it is a same-session stop; the " +
                                "target is the next real resistance above the entry. Computed " +
                                "from real levels - not a forecast of where the price is going.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (r.atr > 0 || r.vwap > 0 || r.prevHigh > 0) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "The levels behind it",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    if (r.vwap > 0) Text(
                        if (r.price > r.vwap)
                            "VWAP: ${Fmt.price(r.vwap)} - trading ABOVE it, buyers in control today."
                        else
                            "VWAP: ${Fmt.price(r.vwap)} - trading BELOW it, sellers in control.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (r.openingRangeHigh > 0) Text(
                        "Opening range (9:30-10:00 ET): ${Fmt.price(r.openingRangeLow)} - " +
                            "${Fmt.price(r.openingRangeHigh)}" +
                            if (r.price > r.openingRangeHigh) " - broken above" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (r.premarketHigh > 0) Text(
                        "Premarket high: ${Fmt.price(r.premarketHigh)}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (r.prevHigh > 0) Text(
                        "Prior session's high: ${Fmt.price(r.prevHigh)}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (r.sessionHigh > 0 && r.sessionLow > 0) Text(
                        "Session range so far: ${Fmt.price(r.sessionLow)} - " +
                            "${Fmt.price(r.sessionHigh)}" +
                            if (r.adr > 0)
                                " - ${((r.sessionHigh - r.sessionLow) / r.adr * 100).toInt()}% " +
                                    "of a normal day's ${Fmt.price(r.adr)} range"
                            else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (r.atrIntraday > 0) Text(
                        "5-minute ATR: ${Fmt.price(r.atrIntraday)} - the volatility the stop is " +
                            "sized from.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (r.atr > 0) Text(
                        "Daily ATR(14): ${Fmt.price(r.atr)} - this stock's average daily range.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (r.reasons.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Why it's in play",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    r.reasons.forEach { line ->
                        Text(
                            "- $line",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                if (r.why.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "CLAUDE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(r.why, style = MaterialTheme.typography.bodyMedium)
                }

                if (r.catalyst.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "The specific risk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(r.catalyst, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Not financial advice. This stock is objectively in play right now by the " +
                        "app's own measure of volume, movement and attention - not a " +
                        "prediction that it keeps moving. No system built on free public data " +
                        "can honestly promise which stocks rise today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}
