package com.tj.portfolio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tj.portfolio.data.FundHolding
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.util.Fmt

/**
 * WHAT THIS FUND HOLDS.
 *
 * TJ asked for "a tab called holdings [showing] all of the stocks that the etf currently
 * holds and the percentage of each holding of the entire etf". This is that tab, and it is
 * careful about one thing above all:
 *
 * **IT NEVER IMPLIES THE LIST IS THE WHOLE FUND.** The free feed publishes the top holdings,
 * not the register. So the header states how many positions are listed and what share of the
 * fund they add up to - "10 positions, 34.1% of the fund" - and the two blocks under it, the
 * sector split and the asset mix, are the ones that DO account for all of it. Someone reading
 * this page can tell exactly what they are and are not being shown, which is the difference
 * between a limit and a lie.
 *
 * Each holding is tappable and opens that company's own detail screen, because "what is in
 * this ETF" is almost always followed by "and what is that one doing".
 */
@Composable
fun HoldingsTab(
    h: FundHoldings?,
    loading: Boolean,
    onOpenSymbol: (String) -> Unit
) {
    if (h == null || h.isEmpty) {
        EmptyTab(
            loading = loading,
            message =
                if (h != null && !h.isFund) "This is not a fund, so it has no holdings."
                else "No holdings reported for this symbol yet.",
            hint =
                if (h != null && !h.isFund)
                    "The Holdings tab lists what an ETF or mutual fund owns. An ordinary " +
                        "share owns nothing on your behalf, so there is nothing to list. " +
                        "Its own numbers are on the Stats tab."
                else
                    "The data provider has not published a holdings breakdown for this " +
                        "fund. Some newly launched and non-US funds never do. Pull down to " +
                        "try again."
        )
        return
    }

    // Hoisted out of the item lambda: every bar is drawn against the LARGEST position, and
    // computing that inside the loop would re-scan the list once per visible row on every
    // recomposition of a screen that recomposes on every quote tick.
    val topWeight = h.holdings.firstOrNull()?.weight ?: 0.0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (h.category.isNotBlank() || h.family.isNotBlank()) {
                    Text(
                        listOf(h.family, h.category).filter { it.isNotBlank() }
                            .joinToString("  -  "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (h.expenseRatio > 0.0) {
                    Text(
                        "Expense ratio ${Fmt.pct(h.expenseRatio * 100.0)} a year",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Updated ${Fmt.relative(h.fetched)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (h.holdings.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Top holdings")
                    Text(
                        // THE HONEST HEADER. See the note on this file: the number of
                        // positions and the share of the fund they cover are stated so the
                        // list can never be mistaken for the complete register.
                        "${h.holdings.size} " +
                            (if (h.holdings.size == 1) "position" else "positions") +
                            " listed, ${Fmt.pct(h.coverage * 100.0)} of the fund between them",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                }
            }
            // KEYED ON THE INDEX AS WELL AS THE SYMBOL. A LazyColumn handed the same key
            // twice THROWS and takes the whole app down, and this project has shipped that
            // exact crash three times already - the news list, the filings list and the
            // research rows. A fund can genuinely list two share classes under one name, and
            // a provider can simply repeat a row; neither is worth a crash. The index makes
            // the key unique by construction, and the symbol keeps it meaningful for a list
            // that is only ever replaced wholesale.
            itemsIndexed(h.holdings, key = { i, it -> "$i:${it.symbol}:${it.name}" }, contentType = { _, _ -> "holding" }) { _, row ->
                HoldingRow(row, topWeight, onOpenSymbol)
                RowSeparator()
            }
            item {
                Text(
                    "Providers publish a fund's largest positions rather than its full " +
                        "register, so this is the top of the book, not all of it. The " +
                        "sector split and asset mix below cover the whole fund.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        if (h.sectors.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("Sector split")
                    StatCard {
                        // Hoisted, for the same reason `topWeight` is above: every bar is
                        // drawn against the largest sector, and re-reading it inside the loop
                        // re-scans the list once per row on every recomposition.
                        val topSector = h.sectors.first().weight
                        h.sectors.forEach { s -> WeightRow(s.sector, s.weight, topSector) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        if (h.hasAssetMix) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    SectionHeader("What it is invested in")
                    StatCard {
                        val mix = listOf(
                            "Stocks" to h.stockPct,
                            "Bonds" to h.bondPct,
                            "Cash" to h.cashPct,
                            "Other" to h.otherPct
                        ).filter { it.second > 0.0 }
                        val top = mix.maxOfOrNull { it.second } ?: 1.0
                        mix.forEach { (label, w) -> WeightRow(label, w, top) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/**
 * One holding: ticker, company, weight, and a bar showing that weight against the largest
 * position in the fund.
 *
 * The bar is scaled to the BIGGEST HOLDING, not to 100%. Against 100% every bar in a
 * diversified fund is a sliver two pixels wide and the picture says nothing; against the
 * largest position it shows what it is meant to - how concentrated the top of the book is.
 */
@Composable
private fun HoldingRow(h: FundHolding, maxWeight: Double, onOpen: (String) -> Unit) {
    val clickable = h.symbol.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpen(h.symbol) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (clickable) {
            Avatar(h.symbol, 34)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                h.symbol.ifBlank { h.name },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (h.name.isNotBlank() && h.symbol.isNotBlank()) {
                Text(
                    h.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(5.dp))
            WeightBar(h.weight, maxWeight)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            Fmt.pct(h.weight * 100.0),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

/** A labelled weight with its own bar, used for sectors and the asset mix. */
@Composable
private fun WeightRow(label: String, weight: Double, maxWeight: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            WeightBar(weight, maxWeight)
        }
        Spacer(Modifier.width(10.dp))
        Text(Fmt.pct(weight * 100.0), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun WeightBar(weight: Double, maxWeight: Double) {
    // Guarded against a zero or absent maximum: `fillMaxWidth(NaN)` throws, and a fund whose
    // provider reported no weights at all would otherwise take the screen down with it.
    val denom = if (maxWeight > 1e-9) maxWeight else 1.0
    val frac = (weight / denom).coerceIn(0.0, 1.0).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(3.dp)
            )
    ) {
        if (frac > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(6.dp)
                    .background(Accent.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
        }
    }
}
