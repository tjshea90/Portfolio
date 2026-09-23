# Full test 2026-09-23 - Day-trading audit (D-*)

Research only; every finding below was verified by reading the code at HEAD (554675b).
Yesterday's D-H1..D-L10 fixes were re-read. Every one still holds, so none is re-reported
here. D-2 below is a gap next to the D-M3 fix, not a break in it.

Most severe first.

---

### D-1 [H] A Claude day-trading answer is wiped by the rebuild that the share flow itself triggers
- where: `ui/PortfolioViewModel.kt:6122-6153` (importSharedInbox / importShared), `:6549-6553`
  (researchStale), `:6634` + `:723` (rebuild publishes `carry(fresh.dayTrading, old.dayTrading)`),
  `:7104-7120` (applyDayTradingAnswer never touches `generated`), `MainActivity.kt:360-369`,
  `ui/ResearchScreen.kt:223-225`, `ui/ResearchScreen.kt:1483-1484` (the button path is gated on busy)
- what's wrong:
  - `applyDayTradingAnswer` publishes Claude's list with `cacheResearch(merged)`. It sets
    `dtExplained` but leaves `generated` as it was, so `researchStale()`
    (`now - s.generated > Research.TTL_MS`, 30 min) is still true for any list older than 30 min.
  - The share path then navigates to the list: `jumpToResearch(ShareDest.DAY_TRADING)`, then
    `goToTab(TAB_WATCHLIST); watchSubTab = WATCH_RESEARCH`. That composes ResearchScreen, and
    `LaunchedEffect(section, busy) { ... else vm.loadResearch() }` runs a full rebuild.
  - The rebuild publishes `dayTrading = carry(fresh.dayTrading, old.dayTrading)`, and `carryWhy`
    only moves the `why` text across. Claude's entry/stop/target, its order, and every symbol
    Claude added are all replaced by the app's own screener list.
  - `importShared` also has no busy gate. The button path has one
    (`importEnabled = busy.isEmpty()`, and its KDoc calls the unguarded case "a race the app
    should not ask the user to think about"). So a share that lands while a rebuild is in flight
    is overwritten when that rebuild finishes.
- failure: Tj makes the prompt at 10:00 from a list built at 09:40, and Claude answers at 10:15.
  He taps the file and picks Portfolio. The toast says "Day trading rebuilt by Claude - 8 picks".
  The app then opens Day Trading, sees `generated` 35 min old, and rebuilds. About 2 seconds
  later the list is the app's own 40 rows again, with no Claude levels. This is the normal path
  for today's new feature, not an edge case.
- fix (minimal):
  1. Treat a Claude answer as a fresh build of the Day Trading section. Either add
     `dtExplained` to the staleness check (`generated = max(generated, dtExplained)` for the
     stock-TTL decision), or have `carryExplanations` keep `old.dayTrading` when
     `old.dtExplained > old.generated` and it is the same ET day.
  2. In `importSharedInbox`, wait for `_researchBusy` to become empty (bounded) before applying
     the answer.
- test: in the VM or a pure `carryExplanations` test, apply a DT answer to a set whose
  `generated` is 40 min old, then run `carryExplanations(current, freshBuild)`. Assert that
  Claude's symbols and levels survive. A second test: `researchStale()` is false right after a
  DT import.

### D-2 [M] A Claude plan on a row the app had declined is never logged (planDeclineStreak is carried over)
- where: `net/DayTradingBridge.kt:440-492` (`app.copy(...)` never resets `planDeclineStreak` or
  `planReason`), `ui/PortfolioViewModel.kt:977-1017`, `:1094-1101`
- what's wrong:
  - `merge` builds a Claude-planned row from `app.copy(...)`. It sets `entryPrice`, `planByClaude`
    and the other plan fields, but leaves `planDeclineStreak` at whatever the app had.
  - On every later tick `claudePlanStands` is true, so
    `declined = plan == null && !claudePlanStands && ...` is false and
    `declineStreak = ... else -> row.planDeclineStreak`. The streak is frozen.
  - `loggableDayTradingRows` requires `it.planDeclineStreak == 0`.
- failure: the app declines a mover twice ("Already moved most of today's likely range", streak 2,
  levels cleared). Claude supplies a plan for it and the plan passes `levelsUsable`. The card
  shows CLAUDE'S PLAN, but the plan is never written to `day_trading_log`, not at import and not
  on any tick. A streak of 1 (a single-tick flicker, which is common) has the same effect.
  Claude's plans are therefore skipped exactly where they differ from the app's, and the success
  rate never measures them.
- fix: in `merge`, when `takeLevels`, set `planDeclineStreak = 0` and `planReason = ""`.
  Mirror this in `mergeDayTradingTech`: `claudePlanStands -> 0`.
- test: take an app row with `planDeclineStreak = 2` and `entryPrice = 0`, merge in a usable
  Claude pick, run one `mergeDayTradingTech` tick, and assert
  `loggableDayTradingRows(listOf(row), today)` contains it.

### D-3 [M] Overnight and weekend: after any rebuild the Day Trading tab has no plans at all
- where: `ui/PortfolioViewModel.kt:7215-7218`, `net/Research.kt:703-709` (no plan at build time),
  `net/ResearchScore.kt:727-734, 750-761` (the non-live "overnight homework" path)
- what's wrong:
  - Plans come from only one place: `mergeDayTradingTech` inside `enrichDayTradingVisible()`
    (grep confirms one call site).
  - The loop skips it whenever the market is CLOSED:
    `if (phase != Phase.CLOSED && online()) { enrichDayTradingVisible() }`.
  - `toDayTradingRow` deliberately builds rows with no plan.
  - The loop's comment argues that nothing can change while CLOSED. That assumes the rows already
    have plans, but a rebuild replaces them with plan-less rows.
- failure: Tj opens Day Trading at 21:00, or at any time on a weekend or holiday. The cached list
  is more than 30 min old, so `loadResearch()` rebuilds it. Every card then shows no
  entry/stop/target until 04:00 on the next trading day. The "Market closed - these are the last
  completed session's levels, to plan from before the open" planning path, and the D-L7 fix made
  for it yesterday, cannot be reached. The Claude prompt made then also carries no
  `appEntry/appStop/appTarget`. A cold start at night has the same effect:
  `evictStaleDayTradingPlan` clears yesterday's plans and nothing recomputes them.
- fix: in the CLOSED branch, still run one sweep per rebuild, for example
  `if (phase == CLOSED && !dayTradingSweepDone && online()) enrichDayTradingVisible()`. The
  daily leg is memoised and the intraday leg returns the last session, so a sweep costs one
  pass per rebuild, not a poll.
- test: `startDayTradingLive` with a fake CLOSED clock on a freshly built set. Assert that
  visible rows gain `entryPrice > 0` after one iteration (or extract the loop-decision function
  and test `shouldSweep(phase, sweepDone)`).

### D-4 [M] Claude only sees the first 10 rows, but its answer replaces all of them (up to 40)
- where: `ui/PortfolioViewModel.kt:6850-6859` (`visibleResearch` does
  `take(_researchShown ?: PAGE)`), `:7014-7024`; `net/DayTradingBridge.kt:172-176` (AUTHORITY:
  "anything you leave out is removed"), `:419-437` (merge returns only `incoming`);
  `ui/PortfolioViewModel.kt:7122-7138` (the dropped count); `net/Research.kt:692`
  (`take(DAY_TRADING_BUFFER)` = 40)
- what's wrong: the prompt bundle has only the on-screen page (PAGE = 10), and the prompt tells
  Claude "the array you return IS my new list". `merge` then returns only Claude's picks, so the
  other 30 rows, which Claude never saw, are deleted. The toast counts them as Claude's decision:
  `dropped = known.count { it !in kept }` reports "..., 30 dropped".
- failure: with 40 screened names, Claude keeps 8 of the 10 it was shown. The list shrinks to 8,
  and the toast says "8 picks, 32 dropped", although Claude judged only 10 names. The dropped
  rows return only on the next rebuild.
- fix: send the whole `dayTrading` list in the DT bundle (keeping the other sections trimmed), or
  have `merge` keep the unseen rows (those not in the bundle) after Claude's picks. Compute
  `dropped` against the rows actually sent.
- test: a set with 25 DT rows and shown = 10. Merge a 5-pick answer and assert the 15 unseen
  rows survive, or that `dayTradingBundle()` contains all 25 symbols.

### D-5 [M] An old answer file is applied as today's plan and logged as today's recommendation (`asOf` is ignored)
- where: `net/DayTradingBridge.kt:61` (SHAPE asks for `asOf`), `:307-369` (parse never reads it);
  `ui/PortfolioViewModel.kt:7104-7120` -> `cacheResearch` -> `captureDayTradingRecommendations`
  (`recordedAt = now`, `tradingDay = today`)
- what's wrong: nothing checks which session an answer was written for. grep finds `asOf` only
  in the SHAPE and the bundle writer. The only check on the levels is the half-to-double price
  band (`levelsUsable`), which a plan from a day or two ago easily passes.
- failure: today's share flow makes this easy. Tj taps yesterday's `portfolio-answer-*.md` in the
  Claude chat history, or re-shares it by mistake. Yesterday's entry/stop/target become today's
  CLAUDE'S PLAN. During market hours, any symbol not yet logged today is written permanently
  (INSERT OR IGNORE) with today's `recordedAt` and scored against today's bars. The day's slot is
  used up, and the success rate now includes a plan nobody made for today.
- fix: in `parse`, read `asOf` (YYYY-MM-DD). If it is present and is not today's ET date,
  refuse with a clear message, or accept the explanations and drop the levels
  (`planByClaude = false`). Treat a missing `asOf` as today, for tolerance.
- test: `DayTradingBridge.parse` with `asOf` set to yesterday returns an error, or returns picks
  with `entryPrice == 0`. Today's date parses as before.

### D-6 [M] "Check success rate" fires one request per unresolved row, with no cap, which can trip Yahoo's throttle for the whole app
- where: `ui/PortfolioViewModel.kt:6378-6411`, `:6424-6453`; `:7266-7267` (the first sweep
  plans all rows); `net/Research.kt:692` (40 rows per rebuild)
- what's wrong:
  - `needsEval` is every non-final row in the last 55 days, each resolved with its own
    `fetchDaySeries` request (one 5m session). Only `MAX_PARALLEL_REQUESTS` limits it.
  - The log grows fast. Each rebuild's first sweep plans every row (up to 40), and during market
    hours every planned row is logged. Rebuilds happen every 30 min while the tab is open, so a
    day can add 40-100+ rows.
- failure: Tj presses the button after two weeks away. The press fires several hundred
  query1/query2 chart requests in one go. Yahoo answers 429/403, and `Http` arms the host
  cooldown. That cooldown is shared with the main quote loop and the charts, so the rest of the
  app shows stale prices for minutes, and many rows are written DATA_UNAVAILABLE for this press.
- fix: resolve oldest-first with a per-press cap (for example 60) and show "N more to check - tap
  again". Or group rows by symbol and fetch one 5m window per symbol covering its first to last
  logged day (Yahoo serves 5m for up to 60 days), then slice it per `tradingDay` with
  `sessionBoundsMs`.
- test: pure selection helper `rowsToResolve(all, cap)` returns at most the cap, oldest first,
  and skips final rows.

### D-7 [L] Rows Tj never saw are logged as recommendations
- where: `ui/PortfolioViewModel.kt:7266-7267` (`sweeping` sets `head = rows`, i.e. all 40),
  `:6336` (`loggableDayTradingRows(rows, today)` over the whole section),
  `ui/ResearchScreen.kt:1347-1348` (card text: "this only records what it actually shows you")
- what's wrong: the one-time full sweep gives plans to rows below "Load more", and capture logs
  every planned row. The card promises the opposite.
- failure: 30 of the 40 logged rows per rebuild were never on screen. The success rate describes
  the screener's tail rather than the advice Tj was shown, and the log grows about 4x (this feeds
  D-6).
- fix: log only rows within `_researchShown[SECTION_DAY_TRADING]` (pass the shown count into
  capture), or change the card text. The first option matches Tj's wording, "record ... its
  recommendations".
- test: `loggableDayTradingRows(rows, today, shown = 10)` returns at most the first 10.

### D-8 [L] A Claude plan imported between the last evening tick and midnight is discarded at 04:00; one imported after midnight is kept
- where: `net/DayTradingBridge.kt:490-491`
  (`sessionDay = if (takeLevels && app.sessionDay.isNotBlank() && app.sessionDay != today) "" else app.sessionDay`),
  `ui/PortfolioViewModel.kt:951, 960` (`sessionChanged` then `claudePlanStands = false`)
- what's wrong: at 22:00 the row's `sessionDay` equals today's ET date, so it is kept. At the
  first 04:00 pre-market tick, `sessionChanged` is true and Claude's plan is replaced by the
  app's. The same import at 00:05 blanks `sessionDay` and survives. Claude-added rows (blank
  `sessionDay`) always survive.
- failure: Tj does his "overnight homework" import at 23:00 for the next session. When the
  pre-market starts, Claude's levels silently turn back into the app's, and they are never
  logged. An import an hour later behaves differently.
- fix: when the market is not OPEN at import time (after the close), also blank `sessionDay` on
  `takeLevels` so the plan belongs to the next session. For example, use
  `MarketClock.phase(now) != OPEN` in addition to the date test.
- test: `merge(..., now = 22:00 ET)` then a `mergeDayTradingTech` tick at 04:05 next day with
  premarket bars. Assert `planByClaude` and Claude's entry survive.

### D-9 [L] On NYSE half days the exit instruction still says "flat by 15:50 ET"
- where: `net/ResearchScore.kt:1000-1001` (`exitPlan`), `:970-971`
- what's wrong: `"Day trade: be flat by 15:50 ET at the latest..."` is hard-coded. Yesterday's
  D-M4 fix made the clock, `minutesLeft` and the plan aware of the 13:00 close, but this text was
  not updated.
- failure: at 11:00 on the day after Thanksgiving, the card tells Tj to be flat by 15:50. The
  market shuts at 13:00. (The `minutesLeft` clause only appears inside the last 60 minutes.)
- fix: pass the close minute (or `closeMinuteAt(now)`) into `exitPlan` and print close minus
  10 min (12:50 on a half day). Keep 15:50 when there is no clock (the Claude import path passes
  `live = false`).
- test: `exitPlan(target, minutesLeft = 120, live = true, closeMinute = 13*60)` contains "12:50".

### D-10 [L] The daily memo can freeze a not-yet-final bar for the rest of the evening
- where: `net/DayTradingTechnicals.kt:269-272` (key `symbol|date|afterClose`), `:537-543`
  (`completedSessions` counts today as complete from the closing minute)
- what's wrong: from 16:00:00 today's daily bar counts as a completed session, and the first
  post-close fetch is memoised for the rest of the ET day. Yahoo's daily candle takes a few
  minutes to absorb the closing-auction print. This is the same reason D-L6 added
  `SETTLE_GRACE_MS` for evaluation.
- failure: a tick at 16:00:40 memoises a `prevClose/prevHigh/prevLow` that is off by the auction
  print. Evening plans (pivot, R1/R2, S1, and "the last session's high") are computed from it
  until midnight, and the Claude bundle exports them as well.
- fix: count today as completed only once `now >= close + DayTradingEval.SETTLE_GRACE_MS`, in
  both the memo key's `afterClose` and `completedSessions`.
- test: `completedSessions(daily, 16:05 ET)` excludes today's bar and `completedSessions(daily,
  16:25 ET)` includes it.

---

## Checked and fine

- `DayTradingEval.evaluate`:
  - Bars before `recordedAt` are dropped, and a bar straddling `recordedAt` is dropped whole.
  - Entry direction comes from `priceAtRecommendation`, falling back to the setup text.
  - The stop is checked before the target in the same bar, and a pullback's target in its own
    trigger bar is deferred to the next bar.
  - The trade closes at the last bar once settled, and PENDING vs NO_ENTRY is decided by
    `sessionSettled`.
  - No double counting: the WIN/LOSS/CLOSED_*/NO_ENTRY/DATA_UNAVAILABLE/null buckets are
    mutually exclusive in `stats()`, unresolved rows go to `pending` (never counted as losses),
    and the rates use `decided` as the denominator.
- `Costs`: entry +5bp, stop -15bp, close -5bp, target 0. `profitableRate` is net (D-L10 fix
  intact). `accountReturnPct` uses `dayTradeSharesPerEquity`, which matches `positionSize`'s
  risk/25% cap (D-H2 fix intact).
- Plan maths in `planInternal`:
  - VWAP reclaim entry sits above price and a pullback entry below it.
  - The stop is always entry minus a risk clamped to 1.5-2.5 intraday ATR, and is never above
    the entry.
  - The target is always above `max(entry, price)`; there are three bail-outs, and the room
    ceiling is measured from `above`.
  - Non-live levels drop today's premarket high after the session (D-L7 intact).
- `MarketClock`:
  - The holiday rules are correct: observed Sat->Fri and Sun->Mon; New Year's on a Saturday is
    not observed; MLK, Presidents' Day, Good Friday (Easter algorithm), Memorial Day, Juneteenth
    from 2022, Labor Day and Thanksgiving are right.
  - Early closes (Black Friday, Jul 3 and Dec 24 when Mon-Thu) are right, as are after-hours
    ending at close + 4h, `minutesLeftInSession`, `sessionElapsedFraction`, and `dayKey` in ET.
- `DayTradingTechnicals`:
  - `latestDay` stops two days from merging, `intradayToday` gates stale sessions, and
    `regularSession` uses the day's own close.
  - `completedSessions` excludes the session in progress (but see D-10 at the close).
  - Wilder ATR is right, and VWAP resets by construction.
- `effectiveTechnicals` / `mergeDayTradingTech`:
  - A failed fetch keeps the same-day reading.
  - A session rollover clears the intraday fields and bypasses the decline debounce.
  - `lastPrice` is used only when `sessionDay` is set (D-H1 intact).
  - `changePct` is recomputed only while live.
- The live loop:
  - It runs on `fgScope`, which is cancelled on `ON_STOP`; `setForeground(true)` restarts it
    only when `dayTradingLiveWanted`.
  - `DisposableEffect(section)` stops it on a tab change.
  - DetailScreen runs `only = symbol` for a pick. Compose calls a disposal before the next
    screen's remember callbacks, so the stop/start order is correct for Research to Detail,
    Detail to Detail, and back.
  - The cadence is 30s while open or pre-market and 5 min after the close (N-M4 intact).
  - A sweep re-reads the section before writing back.
- Capture:
  - It logs only while OPEN, when `sessionDay == today`, when the row has a price, and never a
    too-late or pending-decline plan (D-M3 intact).
  - `recordedAt` is read on the caller's side.
  - INSERT OR IGNORE keeps the first plan per (symbol, day). This is by design: later intraday
    re-plans and later Claude imports for an already-logged symbol are not recorded.
- `evaluateDayTradingLog`:
  - It re-checks only non-final rows inside the 55-day window, marks aged-out rows
    DATA_UNAVAILABLE (D-L5 intact), and resets the loading flag in `finally`.
  - The double-tap guard works because `viewModelScope` uses Main.immediate.
- Today's UI change: the Day Trading blurb is empty and guarded by `isNotBlank`, and
  `ClaudeAppButtons` is drawn once at the top with the same enabled rules as before. The footer
  no longer duplicates the buttons, and the "How this works" copy matches the new placement.
  No regression was found in the section beyond D-1, which is the share path's navigation, not
  the layout.
- `DayTradingPlanContent` / `PositionSizeLine`: the "stop too wide" test matches `positionSize`'s
  `byRisk < 1` exactly, and `rewardToRisk` is guarded.
