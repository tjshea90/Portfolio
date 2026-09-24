# Full test 2026-09-24 - Day-trading audit (D-*)

Read-only audit of the day-trading subsystem. Line numbers are for the tree at 8bb0176c. The only
source change since the audit began is the U-1 spinner change, which touches no day-trading code.
Yesterday's D-1..D-10 and R-3/R-4/R-7/R-9 fixes were re-read. They are all present. D-2 and D-4
below show that the D-8/R-4 and D-3 fixes are incomplete; they are not regressions of those fixes.

Findings are listed most severe first.

---

### D-1 [H] Stale plans are written to the permanent log with a later timestamp and an old price
- where:
  - `ui/PortfolioViewModel.kt:6534-6543`: `cacheResearch` calls `captureDayTradingRecommendations(set.dayTrading)` on every publish, from every caller.
  - `:6603-6650`: capture.
  - `:1198-1205`: `loggableDayTradingRows`.
  - Callers that capture without re-planning the Day Trading rows first:
    - `:7090`: Best analyst pass, `if (did) cacheResearch(_research.value)`.
    - `:6837`: ETF rebuild.
    - `:7336`: Research answer import.
    - `:7874`: price fill.
    - `:7741`: the single-symbol detail-screen tick, where only `only` is re-planned.
  - `:7701`: `val tech = fetched[row.symbol] ?: return@map row`. When a row's fetch fails on the first OPEN tick, the row keeps its pre-market plan.
- what's wrong:
  - Capture logs every shown row that meets all of these: `entry/stop/target > 0`, `sessionDay == today`, `!tooLateToStart`, `planDeclineStreak == 0`.
  - The log entry gets `recordedAt = now` and `priceAtRecommendation = r.price`.
  - Guard 3 (`sessionDay == today`) cannot tell a live plan from this morning's pre-market plan. From 04:00, a pre-market sweep stamps `sessionDay = today` on a plan it built with `sessionLive = false`, from prior-session levels only.
  - So any capture after 09:30 that is not preceded by a live re-plan of that row logs the non-live plan. It is stamped with the capture time and paired with the pre-market price.
  - `INSERT OR IGNORE` on `UNIQUE(symbol, trading_day)` makes that row permanent, and it blocks the real live plan from ever being logged that day.
- failure scenario:
  - 09:20: Tj looks at the Day Trading list. Pre-market plans are shown with `sessionDay = today`, e.g. XYZ at $49.80 with a breakout entry of $50.20.
  - 09:26: he taps XYZ, so the loop runs in `only = "XYZ"` mode.
  - 09:30:10: the single-symbol tick calls `cacheResearch`. Capture now logs the other 9 shown rows' PRE-MARKET plans with `recordedAt = 09:30:10` and prices from 09:20.
  - If ABC gapped to $52 at the open, its row says entry 50.20 "rises" (above the stale 49.80). `evaluate` fills it on the first bar at 50.20, below where a 09:30 buy-stop could possibly have filled.
  - The same thing happens when he opens Best at 10:00 after checking Day Trading pre-market: the analyst pass's `cacheResearch` logs all ten pre-market plans at 10:00.
  - It also happens for any row whose intraday fetch fails on the busy first OPEN tick.
  - The success rate then measures plans that were never live and never shown at the recorded time, and the real plan for those symbols can never be recorded that day.
- suggested fix:
  - Stamp the plan itself. Add `planAt: Long` and `planLive: Boolean` to `ResearchRow`.
  - Set them in `mergeDayTradingTech` when `plan != null`: `planAt = now`, `planLive = tech.sessionLive`, and keep them unchanged for a standing Claude plan.
  - Set them in `DayTradingBridge.merge` on `takeLevels` (`planLive = phase(now) == OPEN`).
  - `loggableDayTradingRows` must require `planLive && now - planAt <= 2 * DAY_TRADING_LIVE_INTERVAL_MS` (Claude plans: imported or last confirmed during OPEN).
  - Log `recordedAt = planAt`, with the price the plan was built from.
  - Optionally, capture only from the Day Trading loop and the Day Trading import.
- missing test: `loggableDayTradingRows` returns nothing for a row whose plan was built pre-market (`sessionDay` = today, `planLive = false`) or more than 60 s ago.
- confidence: high

### D-2 [M] An evening Claude plan imported 16:00-20:00 is still replaced at the next pre-market (D-8 fix defeated by the post-close sweep)
- where:
  - `net/DayTradingBridge.kt:542-545`: merge blanks `sessionDay` after the close.
  - `ui/PortfolioViewModel.kt:1052`: `sessionChanged = row.sessionDay.isNotBlank() && row.sessionDay != effective.sessionDay`.
  - `:1061`: `claudePlanStands = row.planByClaude && !sessionChanged`.
  - `:1140`: `sessionDay = effective.sessionDay`.
  - `:7578-7579`: the loop still sweeps in post-close EXTENDED.
  - `:858-865`: 5-minute cadence after the close.
  - `net/DayTradingTechnicals.kt:322`: `sessionDay = dayKey(now)` whenever today's bars exist.
- what's wrong:
  - D-8 blanks the row's `sessionDay` on an after-close import, so the 04:00 tick sees no rollover.
  - But from 16:00 to 20:00 the phase is EXTENDED, not CLOSED. The loop sweeps every 5 minutes, and immediately on the tab opening, which the share flow does.
  - `fetch` at 18:00 finds bars dated today and returns `sessionDay = today`. `effectiveTechnicals` passes it through, since the row is blank, so the merged row is stamped with TODAY again.
  - The next tick with a different day then reads `sessionChanged`, drops `planByClaude`, and replans with the app's engine. That is 04:00, or any sweep after midnight, where `tech.sessionDay` is blank.
  - `carryWhy` (`:931`) has the same effect after an evening rebuild: it puts Claude's plan on a fresh row with blank `sessionDay`, and the next evening tick stamps today.
- failure scenario: Mon 17:10 Tj shares Claude's answer ("plan for tomorrow"). The app navigates to Day Trading, the loop's first iteration sweeps, and every Claude row gets `sessionDay = 20260928`. Tue 04:00 the first pre-market tick sees `sessionDay = 20260929`. Claude's entries, stops and targets silently become the app's, labelled "RISK PLAN", and Claude's plans are never logged. The D-8 scenario (import at 22:00) only survives because no sweep runs after 20:00.
- suggested fix: a standing Claude plan should decide "which session am I for" from when it was made, not from `sessionDay`.
  - Add a `planAt` (see D-1) or reuse `whyAt`.
  - In `mergeDayTradingTech`: `claudePlanStands = row.planByClaude && (!sessionChanged || planStillForSession(row.planAt, now))`, using the corrected rule from D-3.
  - Or keep `sessionDay` blank on a Claude row while `!tech.sessionLive` and the row's plan was made after today's close.
- missing test: `merge(...)` at 18:00 ET, then a `mergeDayTradingTech` tick at 18:05 with today's bars (`sessionDay` today), then a tick at 04:05 the next day with `sessionDay` = the next day. Assert that `planByClaude` and Claude's entry survive.
- confidence: high

### D-3 [M] Weekend and holiday Claude plans are thrown away: `planStillForSession` and `answerIsCurrent` only understand weekday evenings
- where:
  - `ui/PortfolioViewModel.kt:901-908` (`planStillForSession`), used by `sameTradingDay` in `carry` (`:784`), `carryWhy` (`:931`) and `evictStaleDayTradingPlan` (`:988`, run on a cold start).
  - `net/DayTradingBridge.kt:393-409` (`answerIsCurrent`).
- what's wrong:
  - `planStillForSession` keeps a plan only if it was made on the same ET date, or if it was made "after the close" (`hour*60+min >= closeMinuteAt(madeAt)`) AND `dayKey(nextOpenAfter(madeAt)) == dayKey(now)`. A Python port confirms the results for Fri 25 Sep 2026 onward:
    - Fri 22:00 -> Sat 10:00: false.
    - Fri 22:00 -> Sun 10:00: false.
    - Sat 10:00 -> Sun or Mon 08:00: false.
    - Sun 10:00 -> Mon 08:00: false.
    - Sun 20:00 -> Mon 08:00: true.
  - So a plan made on a non-trading day before 16:00 never counts. A Friday-evening plan is dropped by any rebuild or cold start on Saturday or Sunday.
  - `answerIsCurrent` accepts today, or "yesterday before today's 09:30". Friday's answer (`asOf` = Friday) imported on Saturday after 09:30, on Sunday, or on Monday pre-market is therefore "an earlier session", and its levels are stripped at parse time. The day after a holiday behaves the same way.
- failure scenario: Tj does his Monday homework on Sunday at 11:00 and imports Claude's answer. Monday 08:00 he opens the tab. The set is more than 30 minutes old, so the tab rebuilds, `carry` drops every Claude level and every Claude-added row, and the same happens on a cold start via `evictStaleDayTradingPlan`. Alternatively, he makes the prompt Friday evening, Claude answers, and he imports it Saturday morning: the levels are refused, with the note "This answer is dated ..., an earlier session".
- suggested fix: one helper `sessionFor(t)`:
  - If `t` is on a trading day before that day's close, return `dayKey(t)`.
  - Otherwise return `dayKey(nextOpenAfter(t))`.
  - `planStillForSession(a, b) = sessionFor(a) == sessionFor(b)`.
  - `answerIsCurrent`: `asOf == today` OR `sessionFor(<asOf date> 16:00 ET) == sessionFor(now)`.
- missing test: extend `FullTest0923Test` R-4 with Fri 22:00 -> Sat 10:00, Sat 10:00 -> Mon 08:00 and Sun 11:00 -> Mon 08:00 (all kept), plus `answerIsCurrent("<Friday>", Saturday 11:00)`.
- confidence: high

### D-4 [M] After a rebuild the list shows no plans until the loop's next tick (up to 5 min after the close, overnight and at weekends); a sweep already running marks the new list "done"
- where:
  - `ui/PortfolioViewModel.kt:6960`: rebuild resets `dayTradingSweepDone = false`.
  - `:6974`: the rebuild publishes rows with no plan; `carryWhy` carries only Claude plans.
  - `:7578-7583`: the loop sleeps `dayTradingLiveDelay(...)`.
  - `:858-865`: 300 s when CLOSED or after the close.
  - `:7629` and `:7735`: `sweeping` is read before the fetch and `dayTradingSweepDone = true` is set after it.
- what's wrong: D-3 (09-23) added "one sweep per rebuild while CLOSED", but nothing wakes the loop when a rebuild lands.
  - Normal evening case:
    - Opening the tab runs `startDayTradingLive`. Its first iteration skips, because the previous list's sweep is already done, and then sleeps for 5 minutes.
    - The stale-TTL rebuild started by `LaunchedEffect(section, busy)` publishes about 5 s later.
    - Every card then has no entry, stop or target for about 5 minutes.
  - Race case:
    - When the first iteration IS a sweep (a cold start at night: 40 rows, 2-3 requests each, several seconds), the concurrent rebuild resets the flag while the sweep is suspended in `withContext(IO)`.
    - The sweep then sets `dayTradingSweepDone = true` and sorts the NEW list, of which it fetched only the symbols the old list shared.
    - The new symbols stay plan-less and sort to the bottom until 04:00.
  - During OPEN the same gap is up to 30 s of blank levels (plus a re-sort) on every rebuild.
- failure scenario: 21:00 Tj opens Day Trading to plan tomorrow. The list rebuilds (TTL) and shows 40 cards with no levels. He reads for three minutes and leaves, without ever seeing the overnight plan path D-3 was fixed to reach. On a cold start the rows that are new since the last build never get a plan that night.
- suggested fix:
  - At the rebuild's reset site, restart the loop when it is wanted: `if (dayTradingLiveWanted) startDayTradingLive(dayTradingLiveOnly)`. That cancels the delay and sweeps now.
  - In `enrichDayTradingVisible`, keep a rebuild generation counter read at sweep start. Set `dayTradingSweepDone = true` (and sort) only if it is unchanged, or only if every `current` row was in `fetched`.
  - Optionally, `carryWhy` could also carry the app's own same-session plan so levels do not blank for the gap.
- missing test: extract the loop decision and assert that a rebuild while `dayTradingLiveWanted` triggers an immediate sweep. For the race: a sweep whose `current` contains symbols not in `fetched` must not set `dayTradingSweepDone`.
- confidence: high on the delay; high on the race mechanics (timing-dependent in practice).

### D-5 [M] The first 5-minute bar and the 30-minute opening range are used as trigger levels while they are still printing
- where:
  - `net/DayTradingTechnicals.kt:612-614`: `openingBar` returns the 09:30 bar whether or not it has closed.
  - `:296` and `:305-307`: `or5High`, `or5Low` and `openingBarBullish` are read from it.
  - `:575-579`: `openingRange` has no completeness check.
  - `net/ResearchScore.kt:751-752`: both are overhead breakout levels when live.
  - `:772`: support.
  - `:1108-1110`: the planNote "The opening 5-minute bar did not close up".
- what's wrong:
  - Between 09:30 and 09:35 the 09:30 bar is the one Yahoo is still updating. Its high is "the high so far", and its close is the current price.
  - `planInternal` picks the nearest overhead level at or above the price, and that is almost always this growing high. The trigger is therefore `price + a few cents` labelled "the first 5-minute bar's high".
  - The planNote declares the bar "did not close up" whenever the price sits below the open mid-bar.
  - The 30-minute range is used as "the opening-range high" before 10:00, which is just the high of day so far. The score side (`withTechnicals` and `technicalConfirmationBonus`) correctly requires `openingRangeComplete`, but the plan side does not.
  - The source setup this code cites (Zarattini/Barbon/Aziz) enters only after the opening bar completes.
- failure scenario: the first OPEN tick at 09:30:15 plans "Buy the break above $20.07 (the first 5-minute bar's high)" off 15 seconds of trading. This is the plan `captureDayTradingRecommendations` writes permanently for the day. The success-rate log therefore measures "buy a few cents above the first 15 seconds' high", not the published opening-range breakout. At 09:32 a stock whose opening bar later closes up can still show the red "did not close up - the published version skips longs on that alone".
- suggested fix: in `fetch`, set `or5High`, `or5Low` and `openingBarBullish` only once a regular bar with `t >= 09:35` exists (mirroring `openingRangeComplete`). In `planInternal`, include `openingRangeHigh`/`Low` as levels only when `tech.openingRangeComplete`.
- missing test (DayTradingTechnicalsTest): with only a 09:30 bar and `now = 09:32`, `openingBar`-derived fields are 0 and `planInternal` does not use them. The existing test "the opening bar is the 0930 to 0935 one" only covers a completed bar.
- confidence: high

### D-6 [L] 16:00-16:20: an evening plan's "last session" is the day BEFORE today (side effect of the D-10 fix)
- where: `net/DayTradingTechnicals.kt:500-502` and `:554-560` (`completedSessions` excludes today until close + 20 min); `net/ResearchScore.kt:754-760` (the non-live branch uses `prevHigh`, pivots and `prevClose` only).
- what's wrong / scenario: at 16:05 `sessionLive` is false, so the non-live overnight plan runs. But `prevHigh`, `prevClose` and R1/R2/S1 are still yesterday's. The trigger reads "Buy the break above $X (the last session's high)" using yesterday's high, and a prompt made in that window exports the same stale `prevHigh`. It corrects itself at the first tick after 16:20.
- fix: let `completedSessions` count today from the close, and keep only the memo key waiting for the grace. A fetch before the grace is then just not memoised under the post-close key.

### D-7 [L] On NYSE half days, Claude plans (and pre-market app plans) still say "be flat by 15:50" (D-9 incomplete)
- where: `net/DayTradingBridge.kt:513-514` (`exitPlan(c.targetPrice, minutesLeft = 0, live = false)`, so `closeMinute` defaults to 16:00); `net/ResearchScore.kt:933` (`closeMinute = if (live) ... else 16 * 60`).
- scenario: a Claude plan imported at 10:00 the day after Thanksgiving shows "be flat by 15:50 ET". The market shuts at 13:00. Claude plans are never re-planned, so this stays all day. The app's own plan says 15:50 until the open on the pre-market of a half day.
- fix: pass `closeMinute = MarketClock.closeMinuteAt(sessionMoment)` in both places, where `sessionMoment` is `now` during a session and otherwise `nextOpenAfter(now)`.

### D-8 [L] Claude's "best first" order is replaced by the app's score on the first sweep after a cold start or rebuild
- where: `ui/PortfolioViewModel.kt:7735-7736` (`sortDayTradingForActionability` on `sweeping`); `DayTradingBridge.merge` returns Claude's order; `dayTradingSweepDone` is not touched by an import.
- scenario: a share usually lands in a cold process, where `dayTradingSweepDone = false`. The first sweep re-ranks Claude's list by `blendedScore(dtLikelihood, dtConfidence)`. Claude-added names (both halves 0) drop to the bottom of the actionable group, and past row 10 they fall below "Load more" and are never logged (D-7 window). In a warm process the same import keeps Claude's order.
- fix: skip the actionability sort, or sort only the app-scored rows, when `dtExplained > generated`.

### D-9 [L] The beginner card reverses a standing plan once the price trades through its entry
- where: `net/ResearchScore.kt:1459` (`val climbing = entry > price`).
- scenario: Claude's pullback plan is entry 100 / stop 97 / target 106, made with the stock at 105. The stock dips to 99.20, so the limit would already have filled. The card now says "Buy if it climbs to $100.00 ... It hasn't proven the move is real yet". Claude plans are never re-planned, so this persists. The app's own plans only show it during decline hysteresis or a failed fetch.
- fix: take the direction from the plan's own setup and price at planning time, not the live price. When the price is on the far side of the entry, show "the buy price has already been reached" rather than flipping the instruction.

### D-10 [L] The technicals score bonus and reason line are fixed at the first sweep after a rebuild, even if that sweep was pre-market
- where: `ui/PortfolioViewModel.kt:7722-7724` (`dayTradingTechScored`, once per rebuild), `ResearchScore.withTechnicals`.
- scenario:
  - Opened at 09:00, the pre-market sweep scores with `vwap = 0`, so no VWAP or opening-range bonus. With the tab left open there is no rebuild, so the session's ranking never gains them.
  - The reverse case: a 09:40 sweep writes "Trading above its session VWAP ($50.10) - buyers in control today" into `reasons`. The line stays after the stock falls below VWAP, while the detail screen's live line says "trading BELOW it, sellers in control".
- fix: keep the build-time likelihood, confidence and reasons in separate fields, and recompute the technicals half from them on every tick (idempotent), instead of adding it once.

### D-11 [L] Evaluation keeps a trade open through the 15:50 and 15:55 bars, while the plan says to be flat by 15:50
- where: `net/DayTradingEval.kt:230-260` (all `after` bars up to 16:00); `exitPlan` says flat by `close - 10`.
- effect: stops or targets hit after 15:50, and the close at the 16:00 print rather than at 15:50, are credited to a trade the plan told Tj to have closed. This is small but systematic.
- fix: cut `after` at `closeMinuteAt(day) - 10` and close at that bar's close.

### D-12 [L] A failed request after settlement is recorded as "no price history available"
- where: `ui/PortfolioViewModel.kt:6749-6760`; `net/DayTradingEval.kt:47-51` (the doc promises null = failed and empty = no data, but `fetchDaySeries` returns null for both).
- scenario: a press that trips Yahoo's host cooldown makes every remaining settled row `DATA_UNAVAILABLE`. The card then says "N with no price history available". Those rows are retried on the next press, so nothing is lost, but the label is wrong. And because the batch is oldest-first, rows that keep failing are re-picked ahead of newer ones.
- fix: return `emptyList()` only for a real 200 with no bars, leave the row untouched on `null`, and order unresolved rows `outcome == null` first.

### D-13 [L] Claude-added rows imported at night never get the app's plan or a price check
- where:
  - `applyDayTradingAnswer` (`:7457-7486`) does not reset `dayTradingSweepDone`, and the loop does not sweep while CLOSED (`:7579`).
  - `fillPricesNow` returns early when the quote batch is empty (`:7791`), so `dropUnusableClaudeLevels` never runs.
  - `mergeDayTradingTech` sets the price from `tech.lastPrice` but never calls `levelsUsable`.
- scenario: a 22:00 import adds names with bad or no levels. They show nothing until 04:00. If the quote fill fails (offline, or a cooldown on v7 while v8 chart works), a decimal-slipped Claude level on an added name is shown and logged unchecked.
- fix: reset `dayTradingSweepDone` on a Day Trading import. Run the `dropUnusableClaudeLevels` check inside `mergeDayTradingTech` when `row.planByClaude` and a price exists.

### D-14 [L] The prompt tells Claude the data is hours old when the Day Trading rows are live
- where: `net/DayTradingBridge.kt:239-240` (`dataAgeMinutes` is measured from `set.generated`).
- scenario: a list built at 10:00 and live-updated every 30 s is sent at 14:00 as `"dataAgeMinutes": 240`. Claude is asked to "correct the app where its data is stale" and may discount fresh prices and levels.
- fix: send the age of the newest Day Trading tick, or send both ages.

---

## Cross-checks requested by the coordinator (scoring auditor's S-5 / S-7), from the DT side

- **S-5 common path: confirmed.** `DayTradingBridge.parse` stores Claude's `risk` in `catalyst` (`:361`). `merge` then does `catalyst = c.catalyst.ifBlank { app.catalyst }` (`:486`), which replaces "Earnings today". `mergeDayTradingTech` reads `earningsToday = row.catalyst.startsWith(CATALYST_EARNINGS_TODAY)` (`PortfolioViewModel.kt:1071`). That read is false for every Claude-answered row the app plans itself: rows whose Claude levels failed `levelsUsable`, rows Claude answered with words but no levels, and every row after `claudePlanStands` lapses at a session change. Those rows lose the "cancel any unfilled buy order before the close" note on earnings day. `carryWhy` (`:924-929`) then carries the replaced text across rebuilds. Counted under S-5, not again here. The fix is shared: a structured earnings flag or timestamp on the row, with Claude's risk line in its own field.
- **S-7: confirmed.** `carryWhy` (`:910-931`) copies `why`, `whyAt`, `catalyst` and `conviction` on `stillCurrent` alone (14 days). Only the PLAN and Claude-ADDED rows are gated on `sameTradingDay`. A Day Trading "why worth trading today" paragraph and its "risk today" line therefore re-appear on later sessions' cards. When S-7 is fixed, gate on the corrected session rule from **D-3**. Otherwise a weekend import also loses its paragraphs by Monday.

## Checked and fine (not re-reported)

- MarketClock:
  - The holiday rules are correct: observed Sat->Fri and Sun->Mon, New Year's on a Saturday not observed, Juneteenth from 2022, Good Friday.
  - Early closes are correct, as are after-hours to close + 4 h and the DST handling (all through `Calendar(ET)`).
- The ATR is Wilder's (bootstrap SMA of 14, then smoothed), with a floor of 5 true ranges. VWAP is the typical price weighted by volume, reset per session.
- `latestDay`/`intradayToday` gate stale sessions, and `regularSession` uses the day's own close.
- Evaluation:
  - Bars before `recordedAt`, and the bar that straddles it, are excluded.
  - Direction comes from `priceAtRecommendation`.
  - The stop is checked before the target in the same bar, and a pullback's target in its own trigger bar is deferred.
  - PENDING vs NO_ENTRY is decided after the settle grace.
  - `stats()` buckets are exclusive, the denominator counts only decided rows, costs are applied to the net figures, and `accountReturnPct` matches `positionSize`'s risk and cap.
- Plan maths: long only throughout (stop < entry < target is guaranteed by the engine and by `levelsSane`); the stop is clamped to 1.5-2.5 intraday ATR; the target is always above `max(entry, price)`.
- D-2 (09-23) is intact: `merge` and `mergeDayTradingTech` reset `planDeclineStreak` for a Claude plan.
- D-4 (09-23) is intact: the whole list goes into the prompt.
- D-5/R-9 are intact: ISO `asOf` is parsed, and the legacy "MMM d, yyyy" form too. D-3 above fixes only the non-trading-day gap.
- D-6/D-7 are intact: capped oldest-first press; only rows in the shown window are captured.
- R-3 is intact: eviction is keyed on `max(generated, dtExplained)`.
- R-7 is intact: a detail-only run does not poll while closed.
- Loop lifecycle:
  - It runs on `fgScope`, which is cancelled on `ON_STOP`, and restarts on `ON_START` only when `dayTradingLiveWanted`.
  - `DisposableEffect` stops it when the tab changes, and `TabSlide` drops the outgoing tab after the animation (no pager keeps it composed).
  - The detail screen switches it to single-symbol mode.
- Day-trading log DB: `INSERT OR IGNORE` on `UNIQUE(symbol, trading_day)`, written in one transaction. Restore is additive in both modes, and the manifest count is checked.

## Code / UI quality (D-Q)

- **D-Q1** `applyDayTradingAnswer` (`:7486-7489`) reports `parsed.picks.size`, which counts duplicate tickers. `merge` de-duplicates them with `distinctBy`, so a repeated ticker inflates "N picks". Use `kept.size`.
- **D-Q2** A Claude-ADDED row keeps `planExit = ""` (`DayTradingBridge.kt:480` returns `c` as is), so its detail screen has no "Getting out / flat by the close" line, while an existing row gets one. Build `exitPlan` there too.
- **D-Q3** Two KDocs overstate what the code does. `DayTradingEval.IntradayBar` says bars are "never the current, still forming one", but today's evaluation does include it (harmless, since those prices traded). `fetchDaySeries` claims a null/empty distinction it does not implement (D-12).
- **D-Q4** The app-plan explanation in `DayTradingPlanContent` (`DayTradingDetailDialog.kt:117`) always says "the target is the next real resistance above the entry". When the target came from the day's range (`targetFromRoom`), that is not true. The planNote already distinguishes the two cases, so reuse its wording.
- **D-Q5** The `DayTradingStats.avgR` doc says it is divided by `entriesTriggered`, but the code divides by `rMultiples.size`, which excludes stopless rows. Fix the doc.

## Ideas - need Tj's approval, do NOT implement

- Record a Claude plan in the log even when the app already logged a plan for that symbol and day, for example with `UNIQUE(symbol, trading_day, source)`. The success card could then compare "the app's plans" with "Claude's plans" side by side. Today, INSERT OR IGNORE means Claude is measured only on names the app never planned.
- Break the success rate down by setup (Breakout / Pullback / VWAP reclaim) and by time of day, the way trading journals such as TraderSync and Tradervue do, so Tj can see which kind of plan actually works.
- On the detail screen, show "Logged plan today: entry/stop/target at 09:31" next to the current plan, so the recorded recommendation is visible rather than silent.
- An optional local notification when a logged entry triggers or a stop is hit while the app is open (brokerage apps' price alerts). This needs a battery and permission decision.

## Summary

| Severity | Count | IDs |
|---|---|---|
| H | 1 | D-1 |
| M | 4 | D-2, D-3, D-4, D-5 |
| L | 9 | D-6 .. D-14 |
| Quality | 5 | D-Q1 .. D-Q5 |
| Cross-checks | 2 confirmed (S-5 common path, S-7), counted in scoring.md | - |

## END OF REPORT (complete)
