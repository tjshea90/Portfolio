# Dividends + Earnings — design (2026-10-22)

## Placement
Portfolio tab gets a sub-tab strip: **Holdings | Dividends | Earnings** (same pattern as the Watch tab's
Watchlist | Research). Bottom bar is already six wide, so no seventh tab. Dividends/Earnings refresh when
entered (sub-tab shown, or Portfolio tab entered from another tab) and on pull-to-refresh.

## Dividend data (accuracy plan)
- **History + splits**: Yahoo chart v8 `range=5y&interval=1mo&events=div|split` (no key; locked source #1). Cached in
  `settings` as `divhist:SYM` JSON so a cold start / offline still shows data.
- **Next ex-date / pay date / forward rate / yield**: Yahoo quoteSummary `calendarEvents,summaryDetail` via
  `FundamentalsFeed.quoteSummary` (shared crumb plumbing). Falls back to the already-loaded `Fundamentals` values.
- **Entitlement**: shares held at the CLOSE BEFORE the ex-date, replayed from the ledger (BUY/SELL/SPLIT in date order),
  then multiplied by later splits (Yahoo amounts are split-adjusted). "When I bought them" = this replay.
- **Received**: actual ledger DIVIDEND txns are the truth. Each Yahoo event is matched to a txn (window ex-5d..ex+90d,
  amount within 25% of expected, greedy in date order). Matched -> RECEIVED (ledger amount). Unmatched + pay date passed
  -> CALCULATED (shares x per-share, clearly labelled "not in your ledger"). Unmatched txns still count (OTHER).
- **Pay date**: matched txn date; else ex + learned lag (median of matched lags per symbol); else Yahoo's dividendDate lag;
  else default (ETF 4d, stock 21d); weekend -> next business day. Flag `payEstimated`.
- **Frequency**: median gap of last <=6 regular ex-dates -> weekly/monthly/quarterly/semi/annual; extra off-cadence
  payments = special (shown in history, excluded from run-rate). Stale (> 1.6 cadence since last) -> "no recent dividend".
- **Upcoming amount**: DECLARED (ex passed, pay pending) = actual per-share x shares held at ex. SCHEDULED (Yahoo future
  ex-date) / PROJECTED (cadence roll-forward): stable payers (CV<=4%) = latest amount (or Yahoo rate/ppy if it shows a
  declared raise); variable payers (funds/REITs) = mean of last 4 (6 if monthly). Horizon 12 months.
- **Yield**: forward annual per share / price; yield on cost = annual income / cost basis.

## Earnings (outlook plan)
- **Date**: `Fundamentals.earningsDate` (Yahoo calendarEvents), light refresh via quoteSummary `calendarEvents` on entry.
  A 2-date Yahoo range = estimated window, labelled "est.".
- **ETFs**: no earnings; shown by their top holdings' reports (weight-ranked, from HoldingsFeed top holdings) so you see
  what moves the fund.
- **Light**: composite score in [-100,+100] from available components, weights renormalised over what exists:
  beat history 30% (surprise %, beat rate, 4 qtrs) · estimate growth 20% (EPS & revenue y/y) · analyst stance 15%
  (consensus mean, upside, rating trend) · news tone 20% (lexicon over last 14d headlines, recency weighted) ·
  momentum 10% (1-mo / vs 50d, run-up >25% penalised: priced in) · target upside 5%.
  >= +20 green, <= -20 red, else yellow; < 2 components or < 40% weight = unknown = yellow ("unknown").
  Always shows the reasons + "estimate, not a prediction".
- Headlines come from the existing `News` cascade (disk cache first), no new sources.
