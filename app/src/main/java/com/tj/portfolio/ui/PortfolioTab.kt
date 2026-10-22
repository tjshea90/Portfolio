package com.tj.portfolio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The three halves of the Portfolio tab. */
const val PF_HOLDINGS = 0
const val PF_DIVIDENDS = 1
const val PF_EARNINGS = 2

/**
 * The Portfolio tab: your holdings, and the two screens that are about the same holdings -
 * the dividends they pay and the earnings they report (2026-10-22).
 *
 * A sub-tab strip rather than a seventh bottom-bar tab for the reason the Watch tab gives:
 * the bar is already six wide. The strip sits INSIDE the tab so Dividends and Earnings are one
 * tap from the portfolio they describe. Each sub-tab keeps its own scroll position and
 * expanded rows; Back from either returns to Holdings before it leaves the tab.
 *
 * Opening Dividends or Earnings - by tapping its sub-tab, or by arriving at this tab from
 * another one while it was the last open - composes the screen afresh, and each screen
 * refreshes on entering composition. That is the "refresh when I come to it" behaviour.
 */
@Composable
fun PortfolioTab(
    vm: PortfolioViewModel,
    state: UiState,
    onOpen: (String) -> Unit,
    onOpenNews: (String) -> Unit,
    onSearch: () -> Unit
) {
    var sub by rememberSaveable { mutableIntStateOf(PF_HOLDINGS) }
    BackHandler(enabled = sub != PF_HOLDINGS) { sub = PF_HOLDINGS }
    val saved = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = sub.coerceIn(0, 2),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            listOf("Holdings", "Dividends", "Earnings").forEachIndexed { i, label ->
                Tab(
                    selected = sub == i,
                    onClick = { sub = i },
                    text = {
                        Text(label, fontSize = 14.sp,
                            fontWeight = if (sub == i) FontWeight.Bold else FontWeight.Normal)
                    }
                )
            }
        }
        saved.SaveableStateProvider("pf:$sub") {
            when (sub) {
                PF_DIVIDENDS -> DividendsScreen(vm, state, onOpen)
                PF_EARNINGS -> EarningsScreen(vm, state, onOpen)
                else -> PortfolioScreen(vm, state, onOpen, onOpenNews, onSearch)
            }
        }
    }
}
