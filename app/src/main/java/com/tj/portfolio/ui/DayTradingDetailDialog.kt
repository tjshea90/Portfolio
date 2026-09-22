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
 * ROUND 70: no longer the only way to see this. Tapping a Day Trading card now opens the
 * stock's own [DetailScreen] - same as every other list in the app - so this dialog's BODY is
 * [DayTradingPlanContent] below, shared with the section [DetailScreen.OverviewTab] draws at
 * the top of a Day Trading pick's Overview tab. This wrapper stays for whatever still wants a
 * standalone dialog (and for the tests that already exercise it), but nothing in the app opens
 * it from the Day Trading list any more.
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
                DayTradingPlanContent(r)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

/**
 * The risk plan, the levels behind it, why it's in play, and Claude's paragraph when there is
 * one - the whole body [DayTradingDetailDialog] used to own alone. Extracted so
 * [DetailScreen.OverviewTab] can draw the exact same explanation inline, in the tabbed screen
 * Tj asked for, instead of this content only existing behind a modal.
 *
 * SHOWS THE REAL LEVELS, NOT JUST THE PLAN THEY PRODUCED. Tj asked for research-backed signals
 * to be "incorporated... accurately" - a reader who wants to check the app's work needs to see
 * the VWAP, the opening range, the prior-session high and the ATRs themselves, not just trust
 * the entry/stop/target built from them. Each line is omitted entirely when its reading is not
 * available yet, rather than drawn as a false zero.
 */
@Composable
internal fun DayTradingPlanContent(
    r: ResearchRow,
    /**
     * Total portfolio equity, for the share count (Round 73). 0.0 - the default - means "no
     * portfolio to size against", and [com.tj.portfolio.net.ResearchScore.positionSize] then
     * returns null and nothing is drawn, rather than a share count computed from nothing.
     */
    equity: Double = 0.0
) {
    Column {
        if (r.entryPrice > 0) {
                    BeginnerSummaryCard(r)
                    TradeLevelsGrid(r)
                    Spacer(Modifier.height(10.dp))
                    PositionSizeLine(r, equity)
                    ExitPlanLine(r)
                    val rr = rewardToRisk(r)
                    // GUARDED THE SAME WAY THE GRID ABOVE GUARDS ITS CELLS. A row whose stop or
                    // target is missing draws an em dash up there; this line, unguarded, would
                    // have turned the same row into "Risking $22.50 a share to make -$22.50".
                    if (rr > 0.0) {
                        Text(
                            "Risking ${Fmt.price(r.entryPrice - r.stopPrice)} a share to make " +
                                "${Fmt.price(r.targetPrice - r.entryPrice)} - " +
                                "${Fmt.oneDp(rr)} to 1.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                    }
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
                                "structure that would say the setup failed, sized from " +
                                // ONLY CLAIM THE READING THAT WAS ACTUALLY USED. Before the
                                // opening bell, and for the first half hour of a session, there
                                // are too few 5-minute bars to measure one and the plan falls
                                // back to a fraction of the daily ATR - which is most of the
                                // hours this tab is open.
                                (if (r.atrIntraday > 0)
                                    "this stock's own 5-minute ATR so it is a same-session stop"
                                else
                                    "a fraction of its daily ATR, until enough 5-minute bars " +
                                        "have printed to measure the intraday one directly") +
                                "; the target is the next real resistance above the entry. " +
                                "Computed from real levels - not a forecast of where the price " +
                                "is going.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
        } else if (r.planReason.isNotBlank()) {
                    // --- WHY NOT (Round 75) - same rule and same text as the list card's own
                    // version of this note; see its header in ResearchScreen.kt.
                    Text(
                        "Not a candidate right now: ${r.planReason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // --- HOW THE SCORE IS BUILT (Round 72). Tj: "make the scores reflect a blend
                // of how likely the stock is to rise... and how confident this prediction is."
                // Only for a row the app actually scored - see [ResearchRow.dtLikelihood]'s
                // header for why a Claude-added pick has neither number to show.
                if (r.dtLikelihood > 0) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "How the score is built",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Likelihood ${r.dtLikelihood}/100 (how strong today's bullish signals " +
                            "are - volume, the move already under way, breakout structure) " +
                            "× confidence ${r.dtConfidence}% (how many of a fixed checklist " +
                            "of five independent signals actually confirm it) = score " +
                            "${r.score}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This is not a probability the stock will rise - no system built on " +
                            "free public data can honestly compute one. It is a measure of how " +
                            "much real evidence backs today's signals, and how much of it " +
                            "agrees, reduced to one number that only reaches 100 when both do.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // ---- THE APP'S OWN SCORE, CRITICISED BY THE APP (Round 73).
                        //
                        // This score is a weighted sum with a dozen hand-chosen thresholds,
                        // none of which was ever tested against out-of-sample data - which is
                        // the textbook shape of an overfitted rule. The score is still the best
                        // ranking available here and is worth showing; what is not defensible
                        // is showing it without saying that nobody has demonstrated it works.
                        // Naming that is the same discipline as labelling Claude's prices as
                        // Claude's, applied to the app's own arithmetic.
                        "Worth knowing how this number was arrived at: its weights and cut-offs " +
                            "were chosen by reasoning from published research, not fitted to " +
                            "data and never tested against a period held back to check them. " +
                            "That is exactly the recipe that produces scores which look " +
                            "convincing and predict nothing. Treat it as an ordering of what " +
                            "is busy today, not as a measured edge.",
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
                        color = accentText,
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
                Spacer(Modifier.height(8.dp))
                Text(
                    // ---- THE MEASURED BASE RATES, NOT A SOFTENED VERSION OF THEM (Round 73).
                    //
                    // The disclaimer above is honest about what the SCORE is. It says nothing
                    // about how this activity goes for the people who do it, and the published
                    // numbers on that are not ambiguous or contested - they are some of the
                    // most replicated findings in retail finance, across three countries and
                    // two decades. A section that computes entry triggers to the cent while
                    // leaving the reader to assume the average outcome is positive is
                    // technically accurate and substantively misleading.
                    DAY_TRADING_BASE_RATES,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
    }
}

/**
 * WHAT ACTUALLY HAPPENS TO PEOPLE WHO DO THIS, measured rather than asserted.
 *
 * Barber, Lee, Liu & Odean's work on the complete Taiwanese market and Chague, De-Losso &
 * Giovannetti's on Brazilian equity-futures day traders are the two best-identified studies of
 * the question, and both used whole-population account data rather than surveys or a broker's
 * marketing sample. The Brazilian paper's headline - of nearly twenty thousand people who day
 * traded for at least 300 sessions, 97% lost money and 0.4% earned more than a bank teller,
 * with no evidence of improvement through experience - is the single most relevant number
 * available for the reader of this exact screen.
 */
internal const val DAY_TRADING_BASE_RATES =
    "The base rates, measured: of people who day traded for 300+ sessions in one whole-market " +
        "study, 97% lost money and 0.4% made more than a bank teller - with no sign of getting " +
        "better with experience. A separate study of an entire national market found over 80% " +
        "of day traders losing money after costs, and about 1% persistently profitable. This " +
        "section is a way to plan a trade carefully; it is not evidence that the activity pays."

/**
 * HOW MANY SHARES, FROM THE PORTFOLIO THAT ACTUALLY EXISTS (Round 73).
 *
 * Draws nothing at all when there is no equity to size against or no usable plan - see
 * [com.tj.portfolio.net.ResearchScore.positionSize], which returns null rather than a number
 * built from a zero balance. A Claude-authored plan is sized exactly the same way: the
 * arithmetic is the app's either way, and it is the only part of this screen that is not a
 * judgment call.
 */
@Composable
private fun PositionSizeLine(r: ResearchRow, equity: Double) {
    val size = com.tj.portfolio.net.ResearchScore.positionSize(
        equity, r.entryPrice, r.stopPrice
    ) ?: return
    if (size.shares > 0) {
        Text(
            "Size: ${Fmt.shares(size.shares.toDouble())} shares " +
                "(${Fmt.usd(size.notional)}), risking ${Fmt.usd(size.riskDollars)} if the stop fills.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    } else {
        // WHICH LIMIT SAID NO (full-tests audit 2026-09-22, D-L10). A share can be unaffordable
        // under the 25% position cap with a perfectly tight stop - "too wide" blamed the stop
        // for what was the share price. The note below still gives the numbers either way.
        val stopTooWide = r.entryPrice - r.stopPrice >
            equity * com.tj.portfolio.net.ResearchScore.dayTradeRiskFraction()
        Text(
            if (stopTooWide) "Size: the stop is too wide to take at this account size."
            else "Size: one share costs more than a single position may at this account size.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
    if (size.note.isNotBlank()) {
        Spacer(Modifier.height(3.dp))
        Text(
            size.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (size.shares > 0) {
        Spacer(Modifier.height(3.dp))
        Text(
            // SIZED FROM TOTAL EQUITY, WHICH IS NOT BUYING POWER - see `positionSize`'s header.
            // The app is a tracker and cannot know what is settled or marginable, so the share
            // count answers "how much risk is this" and not "can this order actually be placed".
            "Sized against the whole portfolio, not against available cash - the app can't see " +
                "what's settled or what your broker will allow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** The exit half of the plan - see [com.tj.portfolio.net.ResearchScore.TradePlan.exit]. */
@Composable
private fun ExitPlanLine(r: ResearchRow) {
    if (r.planExit.isBlank()) return
    Text(
        "Getting out",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(3.dp))
    Text(r.planExit, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
}
