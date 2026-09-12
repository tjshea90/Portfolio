package com.tj.portfolio.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.AnalystRating
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.MetricCatalog
import com.tj.portfolio.data.MetricGroup
import com.tj.portfolio.util.Fmt
import kotlin.math.abs

/**
 * The three data tabs on a stock's detail screen: Stats, Analysts and Earnings.
 *
 * They share three rules, all of which exist because a stock page that lies quietly is
 * worse than one that says nothing:
 *
 *  1. A figure the provider did not report prints "not reported", never a zero.
 *  2. Every figure has an "i" that opens the beginner explanation in [Explain], including
 *     the ones that are not catalogue metrics - the consensus, the price target, the
 *     earnings date.
 *  3. Every tab names where its numbers came from and how old they are, because when two
 *     providers disagree the user is entitled to know which one they are reading.
 */

// ============================================================================ Stats

@Composable
fun StatsTab(
    f: Fundamentals?,
    price: Double,
    loading: Boolean,
    onInfo: (String) -> Unit
) {
    if (f == null || f.values.isEmpty()) {
        EmptyTab(
            loading = loading,
            message = "No fundamental data for this symbol yet.",
            hint = "Some symbols - most ETFs, funds and foreign listings - simply do not " +
                "have company fundamentals to report. Pull down to try again."
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SourceLine(f) }

        // A group is shown when ANY of its metrics has a value. Inside it every catalogue
        // metric is listed, including the missing ones: seeing that a company reports no
        // payout ratio - and being able to tap "i" to find out why that is - is worth more
        // than a shorter list.
        for (group in MetricGroup.ORDER) {
            val defs = MetricCatalog.group(group)
            if (defs.none { f.values.containsKey(it.key) }) continue
            item(key = "h_$group") {
                Column(Modifier.padding(horizontal = 12.dp)) { SectionHeader(group) }
            }
            items(defs, key = { "m_${it.key}" }) { def ->
                MetricRow(def, f, price, onInfo)
            }
        }

        item {
            Text(
                "Tap any row, or its \"i\", for what the number means in plain English, " +
                    "which values are good or bad, and how it tends to affect the price.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

// ========================================================================= Analysts

@Composable
fun AnalystsTab(
    f: Fundamentals?,
    price: Double,
    loading: Boolean,
    onInfo: (String) -> Unit
) {
    val consensus = f?.consensus
    val ratings = f?.ratings.orEmpty()

    if (f == null || (consensus == null && ratings.isEmpty())) {
        EmptyTab(
            loading = loading,
            message = "No analyst coverage found for this symbol.",
            hint = "Very small companies, ETFs and funds are often not covered by Wall " +
                "Street analysts at all. That is not a negative signal - it just means " +
                "there is no professional consensus to read."
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SourceLine(f) }

        if (consensus != null) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    ExplainedHeader("Consensus rating", Explain.TOPIC_CONSENSUS, onInfo)
                    StatCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                consensus.meanLabel.ifBlank { "No rating" },
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    consensus.mean in 0.01..2.5 -> greenText
                                    consensus.mean > 3.5 -> redText
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(Modifier.weight(1f))
                            if (consensus.analysts > 0) Text(
                                "${consensus.analysts} analysts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (consensus.hasVotes) {
                            Spacer(Modifier.height(10.dp))
                            VoteBar(
                                buy = consensus.strongBuy + consensus.buy,
                                hold = consensus.hold,
                                sell = consensus.sell + consensus.strongSell
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Chip("BUY ${consensus.strongBuy + consensus.buy}", greenText)
                                Chip("HOLD ${consensus.hold}", MaterialTheme.colorScheme.onSurfaceVariant)
                                Chip("SELL ${consensus.sell + consensus.strongSell}", redText)
                            }
                            if (consensus.strongBuy > 0 || consensus.strongSell > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Of those, ${consensus.strongBuy} are strong buy and " +
                                        "${consensus.strongSell} strong sell.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (consensus.mean > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Average score ${String.format(java.util.Locale.US, "%.2f", consensus.mean)} " +
                                    "on a scale where 1 is strong buy and 5 is strong sell.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (consensus.hasTarget) {
                item {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        ExplainedHeader("Price target", Explain.TOPIC_TARGET, onInfo)
                        StatCard {
                            val up = consensus.upsidePct(price)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    Fmt.price(consensus.targetMean),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                if (up != null) Text(
                                    (if (up >= 0) "+" else "") +
                                        String.format(java.util.Locale.US, "%.1f%%", up) +
                                        " vs today",
                                    fontWeight = FontWeight.SemiBold,
                                    color = signColor(up)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            // ---- GUARDED LIKE THE MEDIAN ABOVE (Round 66 audit, DET-1).
                            //
                            // THE BUG THIS FIXES, and this file's own rule 1 already said not
                            // to do it: "a figure the provider did not report prints 'not
                            // reported', never a zero". `Consensus.hasTarget` is
                            // `targetMean > 0` ALONE, and both parsers default the bounds to
                            // zero, so a Nasdaq answer carrying only `priceTarget` reached
                            // this card with `targetLow = 0.0` - and `Fundamentals.merge`
                            // takes the consensus whole, so Yahoo could not top it up.
                            //
                            // It printed "Lowest target $0.00", which is bad enough, and then
                            // fed that zero into the arithmetic below: with a high of $60 the
                            // spread came out at $60, "133% of the average", under a sentence
                            // asserting that "the professionals genuinely disagree about this
                            // company". No analyst targeted it at zero and there was no such
                            // disagreement - the whole claim was manufactured from a missing
                            // field, on a card people read to decide what a stock is worth.
                            if (consensus.targetLow > 0)
                                KeyValue("Lowest target", Fmt.price(consensus.targetLow))
                            KeyValue("Average target", Fmt.price(consensus.targetMean))
                            if (consensus.targetMedian > 0)
                                KeyValue("Median target", Fmt.price(consensus.targetMedian))
                            if (consensus.targetHigh > 0)
                                KeyValue("Highest target", Fmt.price(consensus.targetHigh))
                            if (price > 0) KeyValue("Today's price", Fmt.price(price))
                            val spread = consensus.targetHigh - consensus.targetLow
                            // BOTH ENDS REAL, and the high genuinely above the low - a spread
                            // is a statement about two numbers and needs two numbers.
                            if (consensus.targetLow > 0 && consensus.targetHigh > consensus.targetLow &&
                                consensus.targetMean > 0
                            ) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "The gap between the highest and lowest target is " +
                                        Fmt.price(spread) + " - " +
                                        String.format(
                                            java.util.Locale.US, "%.0f%%",
                                            spread / consensus.targetMean * 100.0
                                        ) +
                                        " of the average. A wide gap means the professionals " +
                                        "genuinely disagree about this company.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        val trend = f.trend.filter { it.total > 0 }
        if (trend.size > 1) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("How the ratings have moved")
                    StatCard {
                        trend.take(4).forEach { t ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    t.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.35f)
                                )
                                VoteBar(
                                    buy = t.strongBuy + t.buy,
                                    hold = t.hold,
                                    sell = t.sell + t.strongSell,
                                    modifier = Modifier.weight(0.45f)
                                )
                                Text(
                                    "  ${t.strongBuy + t.buy}/${t.hold}/${t.sell + t.strongSell}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(0.20f)
                                )
                            }
                        }
                        Text(
                            "Buy / hold / sell counts. What matters is the DIRECTION - " +
                                "analysts drifting from buy to hold over months usually " +
                                "means the business is deteriorating.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                ExplainedHeader(
                    if (ratings.isEmpty()) "Individual ratings"
                    else "Individual ratings (${ratings.size}) - newest first",
                    Explain.TOPIC_RATINGS, onInfo
                )
            }
        }

        if (loading && ratings.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }

        items(ratings, key = { it.id }) { r -> RatingRow(r, price) }

        if (!loading && ratings.isEmpty()) {
            item {
                Text(
                    "No individual analyst actions were found. The consensus above still " +
                        "applies - some providers publish the average without the " +
                        "underlying calls.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/** One analyst action: who, when, what they now say, and where they think it is going. */
@Composable
private fun RatingRow(r: AnalystRating, price: Double) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.firm,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (r.bucket != AnalystRating.NONE) Chip(r.bucket, bucketColor(r.bucket))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append(AnalystRating.actionLabel(r.action))
                if (r.toGrade.isNotBlank()) {
                    append(" - ")
                    if (r.fromGrade.isNotBlank() && !r.fromGrade.equals(r.toGrade, true)) {
                        append(r.fromGrade).append(" to ")
                    }
                    append(r.toGrade)
                }
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (r.target > 0) {
            Spacer(Modifier.height(2.dp))
            val moved = r.priorTarget > 0 && abs(r.priorTarget - r.target) > 0.005
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Target " + (if (moved) Fmt.price(r.priorTarget) + " to " else "") +
                        Fmt.price(r.target),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (moved) signColor(r.target - r.priorTarget)
                    else MaterialTheme.colorScheme.onSurface
                )
                if (price > 0) {
                    val up = (r.target - price) / price * 100.0
                    Text(
                        "   (" + (if (up >= 0) "+" else "") +
                            String.format(java.util.Locale.US, "%.0f%%", up) +
                            " from today)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                if (r.date > 0) append(Fmt.day(r.date)).append("  -  ")
                append(if (r.date > 0) Fmt.relative(r.date) else "date not reported")
                if (r.analyst.isNotBlank()) append("  -  ").append(r.analyst)
                if (r.source.isNotBlank()) append("  -  ").append(r.source)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

// ========================================================================= Earnings

@Composable
fun EarningsTab(
    f: Fundamentals?,
    loading: Boolean,
    onInfo: (String) -> Unit
) {
    if (f == null || (f.estimates.isEmpty() && f.history.isEmpty() && f.earningsDate <= 0)) {
        EmptyTab(
            loading = loading,
            message = "No earnings data for this symbol.",
            hint = "Funds and ETFs do not report earnings, and very small companies are " +
                "often not forecast by anyone."
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SourceLine(f) }

        if (f.earningsDate > 0) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Next report")
                    StatCard {
                        TopicRow(
                            "Expected earnings date",
                            Fmt.day(f.earningsDate),
                            Explain.TOPIC_EARNINGS_DATE,
                            startPad = 0,
                            onInfo = onInfo
                        )
                        val days = (f.earningsDate - System.currentTimeMillis()) / 86_400_000L
                        Text(
                            if (days >= 0)
                                "In $days days. Expect a bigger-than-usual price move that day."
                            else "That date has passed; the next one is not scheduled yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (f.estimates.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    ExplainedHeader("What analysts expect", Explain.TOPIC_ESTIMATES, onInfo)
                }
            }
            // DE-DUPLICATED BEFORE IT IS KEYED. A LazyColumn handed the same key twice
            // throws and takes the app down - the crash this project has already shipped
            // three times - and nothing upstream guarantees a provider will not repeat a
            // period. `Fundamentals.merge` takes estimates wholesale from one side with
            // `ifEmpty`, so it never gets the chance to dedupe them either.
            items(f.estimates.distinctBy { it.period }, key = { "e_${it.period}" }) { e ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    StatCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.label, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (e.endDate.isNotBlank()) Text(
                                "ends ${e.endDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (e.epsAvg != 0.0) {
                            KeyValue("Expected earnings per share", Fmt.price(e.epsAvg), bold = true)
                            if (e.epsLow != 0.0 || e.epsHigh != 0.0) KeyValue(
                                "Range across analysts",
                                "${Fmt.price(e.epsLow)} to ${Fmt.price(e.epsHigh)}"
                            )
                            if (e.epsYearAgo != 0.0) KeyValue(
                                "Same period last year", Fmt.price(e.epsYearAgo)
                            )
                            if (e.epsGrowth != 0.0) KeyValue(
                                "Expected growth",
                                String.format(java.util.Locale.US, "%+.1f%%", e.epsGrowth * 100.0),
                                signColor(e.epsGrowth)
                            )
                        }
                        if (e.revenueAvg > 0) {
                            KeyValue("Expected revenue", "$" + Fmt.compact(e.revenueAvg))
                            if (e.revenueGrowth != 0.0) KeyValue(
                                "Expected revenue growth",
                                String.format(java.util.Locale.US, "%+.1f%%", e.revenueGrowth * 100.0),
                                signColor(e.revenueGrowth)
                            )
                        }
                        if (e.analysts > 0) KeyValue("Analysts contributing", e.analysts.toString())
                    }
                }
            }
        }

        if (f.history.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Recent results - expected against actual")
                }
            }
            // Same rule, same reason, as the estimates above.
            items(
                f.history.distinctBy { it.quarter to it.date },
                key = { "h_${it.quarter}_${it.date}" }
            ) { h ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (h.date > 0) Fmt.day(h.date) else h.quarter,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Expected ${Fmt.price(h.epsEstimate)}  -  reported " +
                                Fmt.price(h.epsActual),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Chip(
                        if (h.beat) "BEAT" else "MISS",
                        if (h.beat) Green else Red
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            item {
                Text(
                    "Companies usually guide analysts low enough to beat, so a small beat " +
                        "is normal and often moves the price very little. A MISS is the " +
                        "unusual event, and is punished much harder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

// ========================================================================== shared

/** Where these numbers came from and how old they are. Never omitted. */
@Composable
fun SourceLine(f: Fundamentals) {
    if (f.sources.isEmpty() && f.fetched <= 0) return
    Text(
        buildString {
            if (f.sources.isNotEmpty()) append("Source: ").append(f.sources.joinToString(", "))
            if (f.fetched > 0) {
                if (isNotEmpty()) append("  -  ")
                append("updated ").append(Fmt.relative(f.fetched))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

/**
 * What a tab shows when it has nothing.
 *
 * It says WHY, which matters: "no analyst coverage" and "we could not reach the provider"
 * look identical to a user, and the difference decides whether pulling to refresh is worth
 * doing. The spinner is only shown while a fetch is genuinely in flight.
 */
@Composable
fun EmptyTab(loading: Boolean, message: String, hint: String) {
    // Scrollable even though it never overflows: PullToRefreshBox drives the gesture through
    // nested scroll, so a tab whose content cannot scroll cannot be pulled - and an empty
    // tab is precisely where the user most wants to pull down and try again.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(message, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
