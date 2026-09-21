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
            color = recommendation?.let { verdictTextColor(it.verdict) } ?: accentText
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
                                color = verdictTextColor(r.verdict),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (r.hasTarget) {
                                    // NAME WHICH TARGET THIS IS. Since 2026-09-18 it is the
                                    // RECENCY-WEIGHTED target whenever enough firms published a
                                    // dated one - a different number from the feed's flat
                                    // all-ages mean, and calling both "average analyst target"
                                    // would make the two readings look like a data error.
                                    // AND THE RANGE ONLY BELONGS TO THE UNWEIGHTED MEAN.
                                    // `targetHigh`/`targetLow` are Yahoo `financialData`'s
                                    // all-ages min and max, while a weighted mean comes from
                                    // the DATED panel - two different populations. Printed
                                    // together they read as one figure and its own bounds, and
                                    // the weighted mean is not guaranteed to fall inside them,
                                    // so the card could show a "mean" outside its stated
                                    // range. Same fault the sentence above already avoids for
                                    // the number itself.
                                    (if (r.targetIsWeighted) "Analyst target (weighted toward the newest) "
                                    else "Average analyst target ") + Fmt.price(r.targetMean) +
                                        if (!r.targetIsWeighted &&
                                            r.targetHigh > 0.0 && r.targetLow > 0.0)
                                            " (range ${Fmt.price(r.targetLow)} - ${Fmt.price(r.targetHigh)})"
                                        else ""
                                } else {
                                    "No analyst price target is published for $symbol"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (r.hasTarget && !r.upsidePct.isNaN()) {
                                Text(
                                    // "TODAY'S" WAS OVERSTATING IT (2026-09-18). This verdict is
                                    // computed once per trading day and frozen, so `r.price` is
                                    // the price at THAT MOMENT - typically the first time the
                                    // stock was looked at today. On a name that has moved since,
                                    // "vs today's $104.10" next to a live header reading $112 is
                                    // the reader's first thought that the app is broken, and the
                                    // same class of unmarked-stale number Tj asked about on the
                                    // analyst side. Naming the time costs one clock read.
                                    //
                                    // A BARE TIME ISN'T ENOUGH (full-tests audit, round 79
                                    // sweep). `loadRecommendation` can show a cached verdict
                                    // immediately while a stale one recomputes in the
                                    // background, so `r.dayKey` can be OLDER than today - and
                                    // "vs $104.10 at 9:41 AM" with no date looks identical
                                    // whether that 9:41 was this morning or three days ago. The
                                    // date is only added when it is not today's, so the common
                                    // case stays as short as it always was.
                                    (if (r.upsidePct >= 0.0) "+" else "") + Fmt.pct(r.upsidePct) +
                                        " vs " + Fmt.price(r.price) +
                                        (if (r.computedAt > 0L) {
                                            val stamp = Fmt.clock(r.computedAt)
                                            " at " + if (r.dayKey.isNotBlank() &&
                                                r.dayKey != com.tj.portfolio.net.MarketClock.dayKey()
                                            ) "${Fmt.shortDay(r.computedAt)}, $stamp" else stamp
                                        } else "") +
                                        (if (r.targetIsWeighted && r.targetAgeDays >= 0)
                                            " · targets typically ${r.targetAgeDays} days old"
                                        else ""),
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
                    // ---- HOW FRESH THE ANALYST HALF ACTUALLY IS (2026-09-18).
                    //
                    // Tj asked that stale ratings stop driving these verdicts; the scoring fix
                    // is in `net/RatingRecency.kt`. This is the other half of keeping that
                    // honest - a discount the reader cannot see is one he has no way to argue
                    // with, and "worth 2.1 fresh analysts, newest 96 days old" is the single
                    // line that tells him whether the BUY above is backed by live coverage or
                    // by a panel that stopped paying attention last quarter.
                    val freshness = r.freshnessNote()
                    if (freshness.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "How current the analyst input is",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            freshness,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.analystDiscounted) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("recFreshness")
                        )
                    }
                    // ---- THE UNDERLYING DATA ITSELF CAN BE STALE, NOT JUST THE RATINGS
                    // (full-tests audit, round 79 sweep).
                    //
                    // `loadRecommendation` recomputes from whatever `Fundamentals` is
                    // currently cached, which has its own six-hour refresh clock - if that
                    // refresh has been failing (offline, a provider cooldown), this verdict is
                    // still stamped `computedAt = now` and looks freshly computed even though
                    // every number underneath it is however old the last successful fetch was.
                    // Shown only when it genuinely lags the verdict's own clock by a full day,
                    // so an ordinary same-day recompute never sees it.
                    if (r.fundamentalsAt > 0L && r.computedAt > 0L &&
                        com.tj.portfolio.net.MarketClock.dayKey(r.fundamentalsAt) !=
                            com.tj.portfolio.net.MarketClock.dayKey(r.computedAt)
                    ) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The underlying financial data was last refreshed " +
                                "${Fmt.shortDay(r.fundamentalsAt)}, not today - a stalled " +
                                "refresh (offline, or a provider pausing this app's requests) " +
                                "can leave this verdict scored from older numbers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("recFundamentalsStale")
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
                            "and published analyst views - each analyst rating weighted by how " +
                            "recently it was written, and dropped entirely past eight months - " +
                            "recomputed once each trading day, not a guarantee of what the " +
                            "stock will do next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}
