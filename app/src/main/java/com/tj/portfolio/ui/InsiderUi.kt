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
 * "My stocks" (the portfolio-scoped feed this tab has always had) vs. "All companies" (every
 * public company, via EDGAR's own market-wide feed - see `Insider.marketWide`).
 *
 * Defaults to ALL_COMPANIES: Tj's own request was that the tab show market-wide activity by
 * default, not that "My stocks" be removed - it stays one tap away.
 */
enum class InsiderSource(val label: String) {
    MY_STOCKS("My stocks"),
    ALL_COMPANIES("All companies")
}

/**
 * The "major" floor applied ONLY in [InsiderSource.ALL_COMPANIES] - a dollar amount on the
 * headline trade's value, not a role filter, because [com.tj.portfolio.data.InsiderFiling.role]
 * is already on every row and Tj's own example ("CEO or director") is a role, not a size.
 *
 * $50,000 is a plain engineering default, the same way `Insider.WINDOW_DAYS`/`MAX_PER_SYMBOL`
 * were always set directly in this codebase rather than derived from anything - easy to change
 * in the one place it is named. Not applied to "My stocks": a user who is specifically
 * following a symbol is presumably fine seeing a smaller trade on it.
 */
const val MIN_MARKET_TRADE_VALUE = 50_000.0

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
                InsiderTag("10b5-1 PLAN", amberText)
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
        // `filedAt` is 0 when the feed's date did not parse - "filed Dec 31, 1969" (C-10).
        if (f.tradeDate > 0 && f.filedAt > 0 && !sameDay(f.tradeDate, f.filedAt)) {
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

/**
 * A one-line count of what the current filter is showing, for the section header.
 *
 * [suffix] is NOT the same claim for both sources - "My stocks" is a real 31-day window
 * ([Insider.WINDOW_DAYS]), but "All companies" is an unbounded, newest-first feed paginated
 * by count rather than by date, so "in the last month" would be a claim this app cannot back
 * for it (a busy "Load more" session can reach back further than a month in one sitting).
 */
fun insiderSummary(shown: List<InsiderFiling>, suffix: String = " in the last month"): String {
    if (shown.isEmpty()) return ""
    val buys = shown.count { it.action == Form4.BUY }
    val sells = shown.count { it.action == Form4.SELL }
    val parts = ArrayList<String>(3)
    if (buys > 0) parts.add(if (buys == 1) "1 purchase" else "$buys purchases")
    if (sells > 0) parts.add(if (sells == 1) "1 sale" else "$sells sales")
    val other = shown.size - buys - sells
    if (other > 0) parts.add("$other other filing" + if (other == 1) "" else "s")
    return parts.joinToString(", ") + suffix
}

/** Used by the feed's badges and here. Kept in one place so the amber matches. */
val Amber = Color(0xFFE0A030)

/**
 * Amber as TEXT, on the same rule as [greenText]/[redText] (Part 9 audit). `Amber` painted
 * directly as the "10b5-1 PLAN"/"INSIDER" tag text measures 2.27:1 on a light background -
 * both tags use it as text, not just as a fill, the same bug those two accessors exist to fix.
 * `0x8A6410` is the light-theme value already measured (Theme.kt's `scoreColor` low tier) to
 * clear 4.5:1 against a light surface; the dark theme keeps the brand amber, which already
 * measures 8.45:1 there.
 */
val amberText: Color
    @Composable get() = if (LocalDarkTheme.current) Amber else Color(0xFF8A6410)

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
                    color = if (on) accentText else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "My stocks" / "All companies" - same look as [ScopeChips], one row up, for [InsiderSource]. */
@Composable
fun SourceChips(
    selected: InsiderSource,
    onSelect: (InsiderSource) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InsiderSource.entries.forEach { s ->
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
                    color = if (on) accentText else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
