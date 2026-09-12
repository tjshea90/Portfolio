package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.InsiderFiling
import com.tj.portfolio.net.Form4
import com.tj.portfolio.util.Fmt

/**
 * How much of the insider list is on screen. Single-select, one row of chips, defaulting to
 * the narrowest - which is what TJ asked for in so many words: "make sure that they are
 * actually purchases and sales and not automatic transactions scheduled before hand".
 *
 * The two wider settings exist because hiding data outright is how a filter becomes a lie.
 * If OPEN_MARKET is empty for a portfolio - and it legitimately can be, since an unplanned
 * insider purchase is a genuinely rare event - the reader needs to be able to see that the
 * filings are there and what they are, rather than conclude the tab is broken again.
 */
enum class InsiderScope(val label: String, val blurb: String) {
    OPEN_MARKET(
        "Open market",
        "Purchases and sales an insider chose to make. Trades scheduled in advance under a " +
            "Rule 10b5-1 plan are excluded - those are set months ahead and say nothing " +
            "about what the insider thinks today."
    ),
    ALL_TRADES(
        "All trades",
        "Every open-market purchase and sale, including ones executed under a pre-arranged " +
            "Rule 10b5-1 plan. Planned trades are badged."
    ),
    EVERYTHING(
        "Everything",
        "Every Form 4 filed: share grants, option exercises, shares surrendered to cover tax " +
            "on a vesting, and gifts, as well as real trades. Most of this is compensation " +
            "machinery rather than a decision to buy or sell."
    );

    fun accepts(f: InsiderFiling): Boolean = when (this) {
        OPEN_MARKET -> f.isDiscretionary
        ALL_TRADES -> f.isTrade
        EVERYTHING -> true
    }
}

/**
 * Green for a purchase, red for a sale, neutral for the machinery.
 *
 * THEME-BRANCHED, NOT THE RAW FILL (Part 9 audit) - painted directly as [InsiderTag]'s text,
 * the same fill-as-text bug the rest of this sweep found and fixed elsewhere. Missed by the
 * source-scan lint until it was widened to catch a `when` branch, not just a `color = ` line.
 */
@Composable
fun insiderColor(action: String): Color = when (action) {
    Form4.BUY -> greenText
    Form4.SELL -> redText
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun actionLabel(f: InsiderFiling): String = when (f.action) {
    Form4.BUY -> "BUY"
    Form4.SELL -> "SELL"
    Form4.GRANT -> "GRANT"
    Form4.EXERCISE -> "EXERCISE"
    Form4.TAX -> "TAX"
    Form4.GIFT -> "GIFT"
    else -> f.headlineTrade?.code.orEmpty().ifBlank { "FORM 4" }
}

/**
 * One filing.
 *
 * Shared by the Feed's Insider tab and by a stock's own detail screen so the two can never
 * drift apart - they showed different things before, and the detail screen's version was the
 * one still printing EDGAR's boilerplate form name.
 *
 * [showSymbol] is off on the detail screen, where every row is the same stock and a repeated
 * ticker badge is just noise.
 */
@Composable
fun InsiderRow(
    f: InsiderFiling,
    showSymbol: Boolean = true,
    owned: Boolean = false,
    onClick: () -> Unit
) {
    val colour = insiderColor(f.action)
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSymbol) {
                InsiderTag(f.symbol, if (owned) greenText else accentText)
                Spacer(Modifier.width(6.dp))
            }
            InsiderTag(actionLabel(f), colour)
            if (f.planned) {
                Spacer(Modifier.width(6.dp))
                // The single most useful badge on the screen: it is the difference between
                // "the CFO decided to sell" and "a schedule set last March fired again".
                InsiderTag("10b5-1 PLAN", Amber)
            }
            if (f.amended) {
                Spacer(Modifier.width(6.dp))
                InsiderTag("AMENDED", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (f.filedAt > 0) {
                Text(
                    Fmt.relative(f.filedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            f.headline(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        val sub = f.subtitle()
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Said in words rather than left to a badge, because it is the most commonly
        // misread thing on a Form 4 - shares that arrived through an option exercise and
        // went straight back out are compensation being cashed, not a change of heart.
        if (f.exerciseAndSell) {
            Spacer(Modifier.height(3.dp))
            Text(
                "Shares came from an option exercise in the same filing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (f.tradeDate > 0 && !sameDay(f.tradeDate, f.filedAt)) {
            Spacer(Modifier.height(3.dp))
            Text(
                "Traded ${Fmt.day(f.tradeDate)}, filed ${Fmt.day(f.filedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Cheap enough to run per row, and only used to decide whether to print a second date. */
private fun sameDay(a: Long, b: Long): Boolean =
    Fmt.iso(a) == Fmt.iso(b)

@Composable
private fun InsiderTag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** A one-line count of what the current filter is showing, for the section header. */
fun insiderSummary(shown: List<InsiderFiling>): String {
    if (shown.isEmpty()) return ""
    val buys = shown.count { it.action == Form4.BUY }
    val sells = shown.count { it.action == Form4.SELL }
    val parts = ArrayList<String>(3)
    if (buys > 0) parts.add(if (buys == 1) "1 purchase" else "$buys purchases")
    if (sells > 0) parts.add(if (sells == 1) "1 sale" else "$sells sales")
    val other = shown.size - buys - sells
    if (other > 0) parts.add("$other other filing" + if (other == 1) "" else "s")
    return parts.joinToString(", ") + " in the last month"
}

/** Used by the feed's badges and here. Kept in one place so the amber matches. */
val Amber = Color(0xFFE0A030)

/** Small row of evenly spaced chips, so the three scopes fit one line on a phone. */
@Composable
fun ScopeChips(
    selected: InsiderScope,
    onSelect: (InsiderScope) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InsiderScope.entries.forEach { s ->
            val on = s == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (on) Accent.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(s) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
