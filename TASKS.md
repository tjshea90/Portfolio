# TASKS — the current job

## Tj's request, 2026-09-22 (his own words)

> Run full tests on this app. See what can improve.

(Resumed state: v7.33/code 90 was already built, published and recorded -
the "interrupted mid-change" warning at session start was only
record-release.sh's BUILDLOG.md line, confirmed against get_release_by_tag.)

### Full-test protocol (CLAUDE.md), 4 parallel subsystem audits + reconcile/fix
- [x] Floor: `checkinit.py` + full Gradle unit suite (2026-09-22: 1261 tests, 0 failures, after
      Maven 429s - container-local ~/.gradle/init.d/central-mirror.gradle swaps Central, and
      Robolectric's runtime-jar download, for Google's Central mirror)
- [x] Audit: recommendation/scoring (S-*)
- [x] Audit: day-trading (D-*)
- [x] Audit: network/caching (N-*)
- [x] Audit: UI / battery / persistence (U-*)
- [x] Audit: accounting / ledger / persistence (A-*) - the 5th agent
      (ticked 2026-09-22 by the resuming session: ckpt 1588 records "All 5 audit agents
      reported", and every area has findings below with contiguous IDs. The boxes simply
      weren't ticked before usage ran out. The agents' full write-ups were NOT saved - only
      these one-liners - so every finding is re-verified against the code before fixing.)
- [ ] Fix every verified finding, with regression tests (IDs below; each must be
      VERIFIED against the code before fixing - agents are research-only and can be wrong)
  - [x] U-M4 (=A-L8) TxnEditor's Delete deleted with no confirmation -> routed through ConfirmDialog
        (ActivityScreen + DetailScreen)
  - [x] U-M3 Settings held whole backup JSON in rememberSaveable -> TransactionTooLargeException
        once backup > ~500K chars; bounded saver `boundedText` (SettingsScreen.kt)
  - Accounting/persistence (A):
    - [x] A-H1 EditPositionDialog untouched Save writes a full override that freezes shares/basis
          against every later trade (RowActions.kt:222, Ledger.applyOverride)
          -> `PositionFields.toSave`: only an edited box becomes an override; untouched keeps the
          stored value; a BUY/SELL/SPLIT on an overridden symbol now toasts a warning (routed via
          VM `savedMsg` so the caller's "saved" toast can't overwrite it). Tests: 7 in
          PositionEditorTest (untouchedSaveWritesNothing ... whitespaceAround...)
    - [x] A-H2 same-day trades replayed in insert order; screenshot imports come newest-first ->
          phantom shares / wrong realized (Ledger sort date,id; commitImportAsync; prompts)
          DONE (suite green, 1261/0): `Ledger.replayOrder` (reverses a same-date group only when the
          stored order oversells and the reverse oversells less - repairs rows already on file),
          `domain/ImportOrder.chronological` (new imports inserted oldest-first), both prompts ask
          for screen order. Also covers A-M3 (SPLIT first on an exact date tie), A-M4
          (`ImportDupes.classify`: one-to-one ON_FILE, in-batch REPEAT shown unticked, force
          commit writes exactly what was ticked; prompts: "each line accounts for ONE row"),
          A-L7 (parseDate rejects years < 1900 -> "09/15/26" = 2026). Tests: ReplayOrderTest (14).
    - [x] A-M3 SPLIT on the same day as trades applied after them (sort SPLIT first within a day)
    - [x] A-M4 identical same-day rows (partial fills) collapsed on import even with force=true;
          duplicateFlags not one-to-one; prompts drop second fills
    - [x] A-M5 [DONE, suite 1281/0: sessionInstantFrom excludes tradesAroundTheClock (-XXX, =X, =F); NetLogicTest]
          sessionInstant() max quoteTime includes crypto -> "today" window follows calendar
    - [x] A-L6 import parse: $/comma strings -> 0 price BUY; negative SELL qty; unparseable date
          -> Claude.importNumber (lenient, unsigned) in both parsers; any unparsed date noted
          "date estimated". Test: LedgerTest "an imported row with currency strings..." (suite 1281/0)
    - [x] A-L7 Fmt.parseDate "09/15/26" -> year 26 AD
    - [ ] A-L9 [WRITTEN: VM.snapshotBefore() in deleteSymbol / wipeTransactions / Replace restore;
          dialogs say how to undo] no snapshot before Replace-all / wipe / delete symbol
    - [ ] A-L10 [WRITTEN: TxnType.ACCOUNT_LEVEL (DEPOSIT/WITHDRAWAL/INTEREST) never carry a symbol
          (editor + both import parsers); deleteTxnsForSymbol spares them; dialog names the
          watchlist removal] cash-type txn keeps a stock symbol; deleteSymbol silently drops watchlist entry
    - [ ] A-L11 Merge restore re-inserts import-history rows every time
  - Day trading (D):
    - [x] D-H1 day-trading row price frozen at screener-build time; live plans/log use stale price
          -> DayTechnicals.lastPrice (newest 5m bar today); mergeDayTradingTech plans against it,
          updates row.price (+ changePct while OPEN). Tests: 4 in ResearchPriceFillTest (D-H1 block)
    - [x] D-H2 "Your portfolio, trading this system" = totalR x 1% ignores the 25% position cap
          DONE (suite 1272/0): ResearchScore.dayTradeSharesPerEquity; stats sums per-trade
          account effect w/ cap; DayTradingStats.cappedTrades; card text. Tests: 3 in DayTradingEvalTest
    - [x] D-M3 too-late / pending-decline plans still logged as recommendations
          -> loggableDayTradingRows (pure) excludes tooLateToStart / planDeclineStreak>0.
          Test: ResearchPriceFillTest "a too-late or pending-decline plan is not logged"
    - [x] D-M4 early-close half days treated as open to 16:00 (and D-L9 holidays)
          -> MarketClock.isHoliday/closeMinute from NYSE RULES (not hard-coded dates): holidays
          CLOSED; 13:00 close + 17:00 after-hours on day-after-Thanksgiving, Jul 3, Dec 24
          (Mon-Thu). Tests: 3 in NetLogicTest (holidays, adjacent sessions, half days)
    - [ ] D-L5 rows past INTRADAY_RETENTION_DAYS stay "in progress" forever
    - [ ] D-L6 no grace after 16:00 before settling CLOSED_* from possibly incomplete bars
    - [ ] D-L7 evening plans use today's premarket high as next session's trigger
    - [ ] D-L8 research cache JSON rewritten every 30s tick
    - [ ] D-L10 profitableRate gross vs headline net; PositionSizeLine wording when price-capped
  - Scoring (S):
    - [x] S-H1 [DONE, suite 1272/0: RatingRecency.allStaleFirms; undatedTrust/Note(allStale);
          scorer skips 0-trust consensus+target lines; Recommendation.allRatingsStale + note.
          Tests: 4 in AnalystRecencyTest + updated "dated ratings take precedence" which had
          pinned the old false "no publication dates" line] all dated analyst ratings stale (>8mo) -> falls back to undated consensus at
          45-90% trust -> stale coverage UPGRADES a stock; false "no publication dates" line
    - [x] S-M2 [DONE, suite 1276/0: per-list carry; 2 ResearchCarryTest tests] carryExplanations uses one symbol map across trending/best/dayTrading
    - [x] S-M3 [DONE, suite 1276/0: busy flag only taken when empty; applyAnalystEnrichment merges only
          score/reasons/consensus onto the CURRENT row; 2 ResearchCarryTest tests] enrichVisible clobbers the shared busy flag; enrichPass write-back reverts rows
    - [x] S-M4 ResearchCard reasons.take(6) hides analyst lines / negative-book warning
          -> card opens on 6 with a "N more reasons" / "Show fewer" toggle; negative-book red flag
          now the FIRST reason in ResearchScore.best (UI change, compile-verified; suite 1277/0)
    - [ ] S-L5 Claude-added ETF rows keep import-day price/change
    - [ ] S-L6 Claude-added Best row gets analyst-only score ~22
    - [ ] S-L7 PEG / fwd P/E reason labels disagree with points sign
    - [ ] S-L8 "turning profitable ... trailing loss" wording for 0 <= eps <= 0.01
    - [ ] S-L9 Finviz whole value map overrides Yahoo core values via ratings()
  - Network (N):
    - [x] N-M1 blank crumb (both hosts cooling) skips COOLING -> per-symbol Finnhub/Stooq storm
          -> batchYahoo: blank crumb + both hosts cooling = Batch.COOLING (no fallback)
    - [x] N-M2 Yahoo 429/503 counted as batch failures -> batchDisabled for session
          -> MarketData.heldOff(code): local cooldown/429/503/403 = held off, not FAILED.
          Test: NetLogicTest "a throttle or rate-limit holds the batch off"
    - [x] N-M3 [DONE, suite green: sparkIsFinal + MarketClock.nextOpenAfter; cold start seeds sparkAt from
          cached rows saved outside the session; NetLogicTest test] sparklines re-downloaded after close / on night resume
    - [x] N-M4 [DONE, suite 1281/0: dayTradingLiveDelay - 30s OPEN/pre-market, 5min after close; NetLogicTest]
          day-trading live loop sweeps every 30s 16:00-20:00 for unchanging data
    - [ ] N-L5 manual refresh quotes allTracked (misses detail symbol), sparks all tracked
    - [ ] N-L6 insider freshness stamp misses followed symbols with no filings
    - [ ] N-L7 advice news freshness ignores feed pass
    - [ ] N-L8 social trending not gated on Feed tab visibility
  - UI (U):
    - [x] U-M1 [DONE (compile + suite 1281/0; UI, no emulator here): MainActivity rememberSaveableStateHolder, "tab:N" / "detail:depth:SYM"
          providers, states removed on pop/goToTab/search; DetailScreen tab + Overview list
          rememberSaveable] back from reader/detail loses list scroll + detail tab (no SaveableStateHolder)
    - [x] U-M2 [DONE (compile + suite 1281/0): tab/detail/detailToNews/searching/watchSubTab/reader/detailStack/
          tabHistory all rememberSaveable (readerSaver, list savers)] nav state (detail/reader/search) not saveable -> process death drops open editor
    - [x] U-M5 [DONE (compile + suite 1281/0): startDayTradingLive(only=symbol) from DetailScreen DisposableEffect while it
          is a pick; enrichDayTradingVisible sweeps only that row, no sort] detail screen day-trading plan frozen (live loop stopped when ResearchScreen leaves)
    - [ ] U-L1 search flashes "No matches / Add anyway" before first search runs
    - [ ] U-L2 held stock with no price shows $0.00 / +$0.00 instead of "--"
    - [ ] U-L3 earnings Today/Tomorrow counts 24h blocks not ET calendar days
    - [ ] U-L4 Buy/Hold/Sell badge stays "..." if fundamentals arrive before first quote
  - [x] (own review of v7.33's diff) Session rollover cleared a Claude day-trading
        plan's levels but left `planByClaude` set, so the row was never re-planned
        by the engine - blank and unlogged for the whole new session until a
        rebuild. Also: a Claude import made before the new session's first live
        tick (cold-launch rows still carry yesterday's `sessionDay`) would be
        thrown away by that first tick. Fixed in `mergeDayTradingTech`
        (`claudePlanStands`) + `DayTradingBridge.merge` (blank a stale
        `sessionDay` when importing levels). Tests: 5 in ResearchPriceFillTest
        (rollover block), 3 in DayTradingTest (import-session block). Suite run
        pending (Maven 429 on cold container, retrying).
- [ ] Re-run suite, re-check touched code
- [ ] Ship per the 2026-09-19 auto-ship rule if the fixes are release-worthy, post the link

## Tj's request, 2026-09-21e (his own words)

> This app day trading section success rate is claiming numbers that seem
> too good to be true. Make sure this success rate feature is working as
> designed. Make sure it is a true gauge of how much my portfolio would be
> up or down if I used only the buy and sell recommendations in the day
> trading system. Make sure it actually tracks the success if I bought the
> stock at the target price and the stock went up to the target sell
> price. It shouldn't be calculating based on anything illogical like the
> total change in value of the stock for the whole day because the target
> buy and sell points are at specific points within each day.

### Audit result: no bug found - already built exactly this way

Read `net/DayTradingEval.kt`, `data/DayTradingLog.kt`, the recording path
in `PortfolioViewModel.captureDayTradingRecommendations`/
`evaluateDayTradingLog`, and the card in `ResearchScreen.kt`, then ran the
36-test `DayTradingEvalTest` suite standalone to confirm the behavior the
code claims (all 36 green). Findings:

- It is NOT a whole-day-change calculation. `DayTradingEval.evaluate` reads
  real 5-minute intraday bars and only credits a WIN/LOSS at the exact
  point price crosses `entry`, then `stop`, then `target` - never the
  day's open/close delta. Bars before `recordedAt` (when the pick was
  actually made) are filtered out before anything else runs, so nothing
  is credited from price action that happened before the recommendation
  existed - a real bug of exactly that shape was already found and fixed
  by a prior session (`entryRises`, 2026-09-16) and has its own regression
  test (`priceAtRecommendationDecidesDirectionRegardlessOfWhatTheSetupIsCalled`).
- Same-bar ambiguity (a single 5-min bar's range covers both target and
  stop, or covers a falling entry's trigger and its target) is always
  resolved against the strategy (stop wins, or the win is deferred to a
  later bar) - never in its favor. Tested directly
  (`targetAndStopBothReachableInTheSameBarReadsAsTheStop`,
  `fallingEntryTargetInTheSameBarAsTheTriggerIsNotYetAWin`).
- `entriesTriggered` (the denominator for both rates on screen) only
  counts picks whose entry price was actually reached - a recommendation
  nobody could have traded is excluded, not counted as a loss or a win.
  `UNIQUE(symbol, trading_day)` plus `INSERT OR IGNORE` in `Db.kt` means
  each symbol is logged once per day at the moment its plan was first
  shown, so a later, more-favorable re-plan can never silently replace it.
- The headline "Your portfolio, trading this system" figure is already
  net of a realistic cost model (`DayTradingEval.Costs` - entry slippage,
  a wider stop-fill cost since a stop is a market order, close-out cost)
  that can only ever make the result worse, never better (proven by
  `costsAlwaysMakeTheResultWorse_neverBetter`), and the screen also shows
  the pre-cost figure alongside it so the size of that assumption is
  visible rather than hidden.
- The screenshot's numbers (60% target hit / 70.91% closed profitable /
  33 of 55 / 39 of 55, +19.30% account, +23.15% fixed-stake gross of
  costs down to +19.30% net, 104 recorded - 55 triggered - 35 never
  triggered - 14 pending) are internally consistent under this exact
  arithmetic (33+16+6=55 decided; 33/55=60.00%; 39/55=70.909%). No
  fabricated or inflated inputs found - if the real number still feels
  high, that is a claim about the underlying stock-picking system finding
  genuinely good setups, not about this tracker measuring them wrong.

No code changes were needed. Verified via `python3 tools/checkinit.py`
(clean) and `bash tools/gradle.sh testDebugUnitTest --tests
"com.tj.portfolio.DayTradingEvalTest"` (36/36 pass, 0 failures).

## Tj's request, 2026-09-21d (his own words)

> Do everything I mentioned in the last few messages, except, only do a
> full test of the app AFTER everything is finished. No light test is
> needed anymore. Just do the full test after all work is complete, then
> ship the apk using GitHub actions.

Overrode the earlier light-tests plan (2026-09-21c below had already run
light tests and shipped v7.30/code 87 before this arrived - that build
stands, recorded in BUILDLOG.md, but this full-test pass is the real
final word before the actual ship).

### Full-test audit, 4 parallel subsystem agents (research-only) + reconcile/fix myself

- [x] **Recommendation/scoring** — 1 HIGH, 2 MEDIUM, 1 disclosed-limitation
      note. Everything else (round66 ETF fixes, RatingRecency, this
      session's own v7.30 fixes) confirmed still correct.
- [x] **Day-trading** — 1 MEDIUM (trade-plan levels have no staleness
      check, unlike `why`), 1 LOW/note. Log persistence, battery gating,
      scoring all confirmed correct.
- [x] **Network/caching** — 1 MEDIUM (the batch-level "Explained via
      Claude" stamp has no staleness eviction, unlike per-row `why`).
      Everything else (Http retry/backoff/cancellation, RetryClock,
      source-order compliance, DayTradingTechnicals memoization) confirmed
      already correct from prior rounds.
- [x] **UI/battery/persistence** — clean, no findings. Confirmed the
      PriceChart.kt anchor fix didn't regress the no-compare zoom-readout
      feature.

### Findings fixed

1. **HIGH** — `ResearchBridge.merge`/`DayTradingBridge.merge` stamped
   `whyAt = now` unconditionally on any matched row, even when the reply
   only updated a catalyst/target/plan and left `why` itself blank
   (kept via `ifBlank`). That reset a weeks-old paragraph's clock to
   "now" every time ANYTHING else on the row changed - defeating the
   14-day eviction shipped in v7.30 for exactly this pattern. Fixed:
   `whyAt` now only refreshes when the incoming `why` is itself
   non-blank. Required a matching fix in `carryEtfExplanations`'s
   Claude-added-fund filter (a category-only fund's `whyAt` is now
   always 0, so it can't be gated on `whyAt` freshness the way a `why`
   paragraph is - kept unconditionally, matching pre-v7.30 behavior,
   since a category isn't time-sensitive advice the way a paragraph is).
   New tests in ResearchTest.kt, DayTradingTest.kt, ResearchCarryTest.kt.
2. **MEDIUM** — the batch-level `explained`/`explainedBy`/`notes` and
   `dtExplained`/`dtExplainedBy`/`dtNotes` fields (the "Explained via
   Claude" card and its relative-time line) carried forward across every
   rebuild and cold launch with no age check at all - the same pattern
   `why`/`whyAt` had before v7.30, one level up. Added
   `carryExplainedStamp`, gated by the same `WHY_STALE_MS`, wired into
   `carryExplanations` (both the early-exit and main paths) and
   `evictStaleWhy`. New tests in ResearchCarryTest.kt.
3. **MEDIUM** — Day Trading's trade-plan levels (entry/stop/target/setup/
   trigger/planNote/planExit) have no staleness check on cold launch,
   unlike `why`. A plan is only ever valid for the trading day it was
   computed on; if the app is reopened after a prior trading day AND the
   follow-up rebuild then fails (offline, a provider cooldown - the
   existing "keep whatever was on screen" rule for an empty rebuild),
   stale numeric buy/stop/sell levels could sit on screen with nothing
   marking them stale. Never mislogged - the permanent recommendation log
   already has its own `sessionDay == today` gate - only misdisplayed.
   Added `evictStaleDayTradingPlan` (clears the plan, not the row, when
   the cached set's `generated` day-key differs from today's), called
   from `loadCachedResearch`. New tests in ResearchCarryTest.kt.
4. **MEDIUM** — `RecommendationDialog`'s "vs $price at [time]" line used a
   bare time with no date, so a multi-day-old cached verdict shown while
   a fresh one recomputes in the background looked identical to one
   computed a minute ago. Fixed: the date is prepended whenever
   `dayKey` is not today's. New tests in DetailTabsUiTest.kt.
5. **MEDIUM/LOW** — `Recommend.build()` stamped `computedAt`/`dayKey` as
   "now"/"today" regardless of how stale the underlying `Fundamentals`
   actually was (if its own 6-hour refresh had been failing), with
   nothing on screen distinguishing a verdict freshly computed from
   fresh data from one freshly computed from stale data. Added
   `Recommendation.fundamentalsAt` (from `Fundamentals.fetched`) and a
   dialog note shown only when it lags the verdict's own day. New tests
   in DetailTabsUiTest.kt.
6. **note, not fixed** — `ResearchScore.withAnalyst` (the screener's
   30%-weighted consensus overlay) has no per-rating age check, unlike
   `holding()`'s panel - but this is an already-disclosed, deliberate
   2026-09-18 trade-off (the screener runs over hundreds of candidates;
   per-analyst dated history for all of them is too heavy to fetch), with
   its own on-screen reason line saying so. Not a new bug.

### Re-verification and ship

- [x] Full Gradle unit suite green after every fix batch: 1228 tests,
      0 failures (up from 1212 after the initial three fixes - 16 new
      tests this round). `checkinit.py` clean throughout.
- [x] v7.30 (code 87, the pre-full-test light-tests ship) confirmed green
      and recorded in BUILDLOG.md.
- [ ] Ship v7.31 (code 88) with the full-test audit fixes, trigger the
      GitHub Actions build, confirm green, record it, post the link.

## Tj's request, 2026-09-21c (his own words)

> When this work is complete, review the attached video and screenshot. In
> the screenshot, the spy baseline looks flat. Is this correct? Spy has
> been rising consistently. And in the video, if I move a stock chart by
> dragging left or right when it is zoomed in, the spy baseline sometimes
> jumps up and down, making it appear that sometimes the stock outperforms
> spy but when I move the chart, suddenly the stock at the same point of
> time drops below spy. Maybe I'm reading it wrong. Review the attached
> video for how the spy baseline jumps up and down when I move the
> timeline. See if this is correct. If it is not, fix it. Then run light
> tests because usage is running out.

Screenshot: TSXU detail screen, 6M range, "vs SPY" toggle on. SPY's line is
drawn nearly flat (dashed-looking, barely moving) despite SPY genuinely
having risen over the period (its own "+15.44%" / "+128.46%" annotations
disagree with how flat the drawn line looks) - this is a PERCENT-CHANGE
comparison chart (both lines rebased to the same start), so SPY's line
should show real day-to-day movement, just compressed by TSXU's much
larger swings on the same axis. Need to check whether that's simply axis
scale (expected, not a bug) or whether SPY's series itself is wrong
(flattened/wrong data).

Video: dragging/panning a zoomed-in chart left-right sometimes makes the
SPY baseline jump, and the stock-vs-SPY relationship at the SAME point in
time flips (stock above SPY, then below) depending on where the chart is
scrolled to - that part sounds like a real bug (the comparison base or
SPY's aligned value depends on the visible window, when it should be
anchored to the chart's fixed start date regardless of pan position).

### Progress
- [x] Watched the video (extracted frames with a bundled static ffmpeg,
      since the container has no system ffmpeg) and inspected the
      screenshot.
- [x] Read the chart's percent-change/rebasing code (PriceChart.kt).
- [x] Determined: TWO separate things, one per report -
      - **Screenshot's "flat SPY"**: NOT a bug. Axis-scale illusion - TSXU
        swung from -21.69% to +134.64% over the period, SPY only +15.44%;
        both share one y-axis sized to the wider line, so SPY's real
        movement is genuinely there but compressed near the bottom.
      - **Video's "baseline jumps, stock flips above/below SPY at the same
        date"**: a REAL bug, and a repeat of one already "fixed" once
        before - `ComparePanAnchorUiTest`'s round-67 header quotes Tj
        reporting almost this exact symptom previously. That fix only
        froze the comparison anchor for the life of one continuous drag,
        then deliberately re-synced it to the newly-settled window's own
        first candle the instant the finger lifted - so every pan-then-
        release re-measured both lines from a DIFFERENT calendar day.
        Two lines rebased from two different days can genuinely swap
        which is on top for the same date on screen; that is not a
        misreading, it's two different questions sharing one axis.
- [x] Fixed: the comparison anchor (`compareAnchorT` in PriceChart.kt) is
      now always the selected range's true start (read from the whole
      fetched series, never the panned/zoomed window's edge), for both
      the "vs SPY" overlay and the primary series' own summary readout
      (kept consistent with each other - same fix shape as the earlier
      "two moments" bug in this same file). Plain single-stock charts
      with no benchmark are untouched; intraday (1D/overnight) ranges are
      untouched (they already anchor to previous close). Removed the now-
      dead round-67 gesture-freeze machinery (`frozenBaseT`/`anchorT`)
      since the anchor no longer depends on gesture state at all.
      Rewrote `ComparePanAnchorUiTest` (its round-67 premise - "freeze
      during the drag, resync after" - is exactly what got removed) to
      instead prove the anchor never moves before, during, or after a
      drag.
- [x] Light tests: `checkinit.py` + full Gradle unit suite green - 1212
      tests, 0 failures (up from 1207: 5 new tests for the Claude-cache
      staleness eviction below, 1 rewritten for the chart-anchor fix).
      Diff reviewed throughout; grepped for other callers of `whyAt`,
      `ResearchRow.why` construction sites, and `zoomedIn` to confirm no
      other path needed the same fix.
- [x] Checkpoint

## Tj's request, 2026-09-21b (his own words)

> Make sure the best stocks and etfs and trending tabs in this app properly
> refresh data. It may be glitchy. When I refreshed the best stocks section
> a few times, each time a stock appeared at the top then disappeared after
> about 1 second. Also there is Claude analysis on some of the best stocks,
> but I haven't run the Claude analysis in weeks, so I'm afraid the
> recommendations for the top stocks may be stale or the Claude advice.
> These sections should be accurate every day and on every refresh using
> sound data and logic that is updated regularly. Nothing should be
> recommended on stale data or stale Claude advice or old cached
> recommendations. The entire recommendation engine and logic should
> refresh with the new best stocks whenever I refresh, or at least daily.
> If Claude advice hasn't been used in a while, the app should remove it
> from cache.

Two distinct bugs to chase:
1. **Flicker bug** — refreshing "Best Stocks" repeatedly makes a stock
   appear at the top of the list then vanish ~1s later. Likely a race
   between an optimistic/partial sort update and the final sorted result,
   or a stale list emission racing the fresh one.
2. **Stale Claude-analysis cache** — Claude analysis attached to some
   Best-Stocks/ETFs/Trending cards can be weeks old with no expiry, so it
   may be shown as current advice when it isn't. Needs: (a) confirm
   whether/how a cache-age check already gates display, (b) add or fix
   an expiry so stale Claude advice is never shown as current, and
   (c) actually evict/remove old unused Claude-analysis cache entries per
   Tj's explicit ask ("remove it from cache"), not just hide them.

### Progress - DONE

- [x] **Flicker bug, root cause found (via a background investigation
      agent's trace)**: `enrichJob` (the analyst-consensus pass) was a
      SIBLING of `researchJob`, not a child of it - cancelling
      `researchJob` on a new pull-to-refresh never touched a still-in-
      flight `enrichJob` from the PREVIOUS refresh. That stale pass
      (awaiting a ~1s Nasdaq round trip) could resolve AFTER a newer
      rebuild had already replaced the list, and its write matched the
      CURRENT list purely by symbol, splicing a stale analyst-boosted
      score onto whatever row now sat at that symbol and re-sorting the
      window on it - promoting a stock to #1 on data a newer rebuild had
      already superseded, until the next write reverted it. Fixed with
      two layers in PortfolioViewModel.kt: (1) `loadResearch(force=true)`
      now cancels `enrichJob` alongside `researchJob`; (2) `enrichPass()`
      captures the list's `generated` stamp before its one suspension
      point and skips its write entirely if a rebuild happened while it
      was in flight (belt-and-suspenders, for a cancellation that lands
      too late).
- [x] **Stale Claude-cache, root cause found (via a second background
      investigation agent's audit)**: `why` (Claude's paragraph) carried
      forward across every rebuild - and every cold app launch that
      restored the persisted cache - on nothing but a blank-string check,
      forever, with no per-row timestamp anywhere to check against. Fixed:
      - Added `ResearchRow.whyAt` (when Claude last said anything about
        this row), serialized in `toJson`/`fromJson`.
      - `ResearchBridge.merge`/`DayTradingBridge.merge` now stamp it
        whenever a row is touched by a fresh Claude reply.
      - Added `WHY_STALE_MS` (14 days) in PortfolioViewModel.kt.
        `carryExplanations`/`carryEtfExplanations` now only carry `why`
        forward while it is within that window - past it, the row's own
        (blank) `why` wins, which is the actual eviction: the next
        `cacheResearch` persists the row with `why` cleared. A
        Claude-added ETF fund (nothing else on its card) is dropped
        outright once stale rather than kept blank.
      - Added `evictStaleWhy`, run once on `loadCachedResearch`'s cold
        start - the one path the carry functions never see, since
        nothing has rebuilt yet.
      - New tests in ResearchCarryTest.kt covering both the eviction and
        that a recent explanation is unaffected.
- [x] Light tests: `checkinit.py` + full Gradle unit suite green - 1207
      tests at this point (before the 2026-09-21c chart fix added 5 more).
- [x] Checkpoint

### Progress

- [ ] Investigate the Best Stocks/Trending refresh flicker bug
- [ ] Investigate Claude-analysis cache staleness/expiry and eviction
- [ ] Fix findings
- [ ] Light/full test per CLAUDE.md protocol as appropriate
- [ ] Checkpoint / ship per policy

## Tj's request, 2026-09-21 (his own words)

> Run a full test of this app

The "full tests" protocol from CLAUDE.md's "Testing on request" section,
run against the latest shipped version, **v7.28 (versionCode 85)** — the
prior full-tests pass (below) shipped that version and is fully closed out.
This is a fresh standalone deep audit of the whole app, not a diff review.

### Full-test progress (2026-09-21)

- [x] Floor: `python3 tools/checkinit.py` + `bash tools/gradle.sh testDebugUnitTest`
      — green, 1204 tests / 0 failures / 0 skipped
- [x] Also ran the un-CI'd compiled-class harnesses per tests/README.md
      (ShippedTest, LedgerPropTest 20k, DayPnlTest 200k, BridgeTest — all
      green) and the standalone `tests/ledger_props.py` (20000 clean, 0
      violations). Found and fixed real bit-rot: `LedgerPropTest.java`
      no longer compiled against `Fees.forEquityTrade`'s new 4th
      `tradeDate` param (no `@JvmOverloads`, so Java couldn't see the
      Kotlin default) — fixed the stale call site to pass `null`
      explicitly (current-rate cases, correct behavior). Auto-committed.
- [x] Audit across subsystems (recommendation/scoring, day-trading,
      network/caching, UI/battery/persistence) — 4 parallel subagents,
      all reported back. Day-trading came back clean (only 3 LOW/
      non-bug notes, nothing changed). Real findings from the other
      three, all fixed below.
- [x] Fix everything found:
      - **HIGH (network)** — `socialAt` in PortfolioViewModel.kt's feed
        loop was re-stamped on EVERY feed pass regardless of whether
        `socialJob` actually attempted a fetch, so after the first pass
        `now - socialAt` could never exceed one feed interval and
        `socialDue` could never go true again — WSB/social "Trending"
        data fetched exactly once per app launch, then froze forever.
        Gated the stamp on `socialDue`.
      - **MEDIUM (scoring)** — `Consensus2.label()` (data/ResearchModels.kt)
        returned "Sell" whenever `sellShare >= 0.30`, with no check that
        sell actually outweighed buy: buy=10/hold=3/sell=7 (buyShare=0.50,
        sellShare=0.35) printed "Sell consensus - 10 buy / 3 hold / 7
        sell" as a reasoning line on the buy-screener's own card. Now
        requires `sellShare > buyShare` too. New test in ResearchTest.kt.
      - **MEDIUM (persistence)** — the Claude screenshot-import review
        (`_importResult`) lived only in the ViewModel's in-memory
        StateFlow; Android killing the process while the review dialog
        was open silently lost every extracted row with no warning,
        wasting a real billed API call. Added `Keys.PENDING_IMPORT`
        (excluded from backups - device-local in-flight state, not
        portable data), a `setImportResult()` wrapper that mirrors a
        real extraction to it, and `loadPendingImport()` on init to
        restore it.
      - **MEDIUM (persistence)** — `Db.restoreJson`'s Merge path called
        `setOverride()` unconditionally for every override in the file,
        silently clobbering a same-symbol override this device already
        had - the one table in that function not following the
        settings block's own "Merge only adds" pattern, and a direct
        contradiction of the restore dialog's stated promise. Added
        `hasOverride()` and gated it the same way settings already are.
        New test in RestoreSafetyTest.kt.
      - **LOW** — Settings' "Restore from JSON" paste dialog was missing
        `dismissOnClickOutside=false`, unlike every other data-entry
        dialog in the app; fixed for consistency.
      - **LOW** — BRIEF.md's locked-decisions table said News was
        "Yahoo → Google → Finnhub"; News.kt's own header already
        documented the real cascade (Yahoo → Nasdaq → Google →
        Finnhub) and has for a while. Doc-only fix.
      - **LOW** — day-trading-log restore had no manifest short-count
        check, unlike transactions, even though `exportJson` always
        wrote `counts.dayTradingLog`. Added the same comparison as a
        warning (never a refusal - the table is additive-only on both
        modes, so nothing on-device was ever actually at risk). New
        test in RestoreSafetyTest.kt.
- [x] Re-run the unit suite after fixes; re-check anything a fix touched
      — 1207 tests / 0 failures / 0 skipped (1204 + 3 new regression
      tests), checkinit ok.
- [x] Checkpoint as work completes
- [x] Ship per CLAUDE.md's auto-ship policy and post the link —
      v7.29 (code 86) shipped, GitHub run 35558277145 green, Release
      published and recorded in BUILDLOG.md:
      https://github.com/tjshea90/Portfolio/releases/tag/v7.29

## Tj's request, 2026-09-19 (his own words)

> Full test the latest version of this app

This is the "full tests" protocol from CLAUDE.md's "Testing on request"
section, run against the latest shipped version, **v7.27 (versionCode 84)**.
No budget or time limit; release-quality bar; whole-app deep audit, not
just the current diff. A previous session ran full tests on the v7.26→v7.27
diff; this is a fresh standalone pass over the whole app at v7.27.

### Full-test progress

- [x] Floor: `python3 tools/checkinit.py` + `bash tools/gradle.sh testDebugUnitTest`
      — green (1174 tests, 0 failures) in the first session; re-running now
      over the in-flight fixes below.
- [x] Own audit pass 1 (first session): day-boundary bug class in Explain.kt
      and Research.daysUntilEarnings — plain Long division truncates TOWARD
      ZERO, so a date 1-23h in the PAST divided to 0 and every `d >= 0`
      "is it still ahead?" test stayed true for a whole day after the date
      passed. Fixed with Math.floorDiv at all four sites; pinned by
      ExplainDayBoundaryTest (10 tests).
- [x] Own audit pass 2 (first session, interrupted mid-step — finished this
      session): three data-loss holes on the restore/backup path, all now
      pinned by the new RestoreSafetyTest:
      - `Db.restoreJson(replace = true)` deletes txns/overrides/watchlist/
        imports BEFORE reading the file, so a file carrying only a
        `watchlist` key wiped the whole ledger to restore a few symbols.
        Replace now requires the array it is about to replace.
      - The `counts` manifest cross-check ran AFTER
        `setTransactionSuccessful()`, so a truncated backup restored with
        "Replace all" wiped the ledger, committed whatever subset parsed,
        and reported success with a warning on the toast. The check moved
        ahead of the commit, so a short count on a Replace now rolls the
        whole transaction back. Merge keeps the advisory warning — it only
        ever adds.
      - `restoreAsync` forced an autobackup on any non-error result, so a
        short-read Merge immediately wrote the incomplete ledger over
        `portfolio-autosave.json` — the one copy that survives an
        uninstall. Now skipped when `r.warning != null`.
      - `Storage.saveToDownloads` used `"wt"` / `writeText`, which truncate
        BEFORE writing, so a failed write destroyed the old backup and
        returned a bare null. Both branches now keep the prior bytes and
        put them back if the write throws.
- [x] Parallel subsystem audits (recommendation/scoring, day-trading,
      network/caching, UI/battery/persistence) — all 4 reported back
- [x] Verified BRIEF.md's un-CI'd randomised ledger harness
      (`tests/ledger_props.py`): it was exiting 1 with 689/5000 violations.
      Diagnosed to completion — all of them quantity-less "ghost" rows,
      which the app already defends end to end (editor blocks since v3.5,
      BOTH import paths refuse in code and report the count, legacy rows
      surface on the Settings data-health card). The LEDGER was correct and
      the HARNESS was wrong; fixed the harness and it is now 40,000
      histories clean across both modes.
- [x] Reconcile findings and fix everything real — see the list below
- [x] Re-run the unit suite after fixes; re-check anything a fix touched
      — 1204 tests / 0 failures / 0 skipped, checkinit ok, randomised
      ledger harness clean. Two existing tests pinned behaviour this pass
      deliberately changed (a truncated backup "warning" on Replace, and a
      fee resolving on a non-trade row); both were rewritten to the new
      contract rather than worked around.
- [x] Checkpoint as work completes (ckpt 1562-1568)
- [x] Ship the result per CLAUDE.md's auto-ship policy and post the link
      — v7.28 (code 85) shipped, GitHub run 35457943340 green, Release
      published and recorded in BUILDLOG.md:
      https://github.com/tjshea90/Portfolio/releases/tag/v7.28

### What the audits found, and what was done

Every finding below was re-verified against the code before being acted on.

**Data loss / retention**
- [x] HIGH (found independently by TWO audits) — the entire day-trading
      recommendation log was absent from every backup this app has ever
      written. Not re-buildable: each row is a plan made against live
      screener state that no longer exists plus an outcome measured against
      intraday bars Yahoo serves ~55 days. Now in export/restore
      (BACKUP_VERSION 4), additive on both modes, never destructive.
- [x] `Storage.saveToAppFolder` truncated before writing, so a failed write
      left a TRUNCATED file carrying the NEWEST mtime — which is exactly
      what "restore latest snapshot" picks. Now writes to `.tmp` and
      renames (atomic).
- [x] The money dialogs and the Claude import-review dialog discarded
      everything on a stray tap outside; a FAILED import commit also threw
      the whole extraction away (it cost a real API call). Both fixed.

**Wrong numbers shown**
- [x] HIGH — `ResearchScore`'s 52-week term treated a MISSING S&P return as
      a flat market, so Finviz-filled symbols scored ABSOLUTE return as if
      it were RELATIVE. Up 15% in a market up 15% scored +11.25 instead of
      0, where BUY starts at 63.
- [x] HIGH — a dropped intraday request was indistinguishable from a
      genuine no-session-today, so `effectiveTechnicals` zeroed every
      intraday field — which changes which plan branch runs and swaps the
      stop's ruler, making entry/stop/target flicker between two different
      trade plans.
- [x] Finnhub fabricated `prevClose` from today's price, rendering a
      confident +0.00% — the exact thing the Yahoo and Stooq parsers refuse
      by name.
- [x] "Up -35.00%" for a stock that fell; `Recommend` reported panel
      freshness the score never used; the undated-consensus line took its
      WORD from Yahoo's 1-5 mean while its POINTS came from vote counts;
      earnings countdown said "In 0 days"/"In 1 days" where the popup for
      the same field said "today"/"in 1 day"; a weighted analyst target was
      printed beside the feed's all-ages range (two different populations).
- [x] A fee entered on a DEPOSIT/DIVIDEND/etc. counted as a fee paid but
      never left the cash balance. Fees are now offered only on trades.

**Battery / network**
- [x] HIGH — the day-trading sweep re-downloaded 3 months of DAILY candles
      per symbol every 30s (data computed only from CLOSED sessions, so it
      cannot change intraday), and polled identically at 3am and offline.
      Now memoised per (symbol, ET date, side of the close), both legs
      conditional, and the loop skips entirely when closed or offline.
- [x] Social clock stamped only on success → an outage meant re-asking
      every 3 min instead of 15. `RetryClock` leaked an entry per
      symbol-range forever. `DayTradingEval` re-fetched immutable closed
      sessions uncached and abandoned the second host on an empty parse.

**Day-trading log integrity**
- [x] A pre-open sweep could log YESTERDAY's levels under today's key, and
      market holidays logged as real sessions (permanently DATA_UNAVAILABLE
      and inflating the session count). One guard — the row's own
      `sessionDay` must be today — closes both, plus the priceless-row case.

**UI correctness**
- [x] `refresh()` reported coroutine CANCELLATION as "Refresh failed:
      StandaloneCoroutine was cancelled" and could pin it on screen for up
      to 15 minutes.
- [x] Deleting a position never navigated back (the lambda closed over a
      pre-delete `UiState`, so the check was always false).
- [x] The detail header offered "Edit position"/"Add transaction" for an
      unknown symbol, because `null != true`.

### Raised, deliberately NOT changed (needs Tj's call)

- `DayTradingEval` scores a rising entry whose trigger bar also dipped
  below the stop as a LOSS. On that one bar the order is genuinely
  unknowable; the current choice is PESSIMISTIC (it understates the
  system), and it is pinned by an existing test, so changing it is a
  deliberate change to how results are measured rather than a bug fix.
  The misleading comment beside it is the part worth correcting.
- `or5High`/`or5Low`/`openingRangeComplete`/`openingBarBullish` have no
  `ResearchRow` field, so they are lost on a failed intraday tick even
  within the same session. Carrying them would mean adding persisted
  fields to the research cache — a schema change with migration risk,
  worth doing on its own rather than at the end of an audit pass.

---

## Previous requests (history)

## Tj's follow-up, 2026-09-19 (his own words)

> The link gives me a 404. Fix this for all future ships

Investigated: `tjshea90/Portfolio` is a PRIVATE repo, confirmed via
`search_repositories` (`"visibility":"private"`). GitHub returns 404, not
403, to anyone viewing a private repo's Release page without access - so
the link itself was correct (verified: it exists, is published, has its
signed APK attached), but an unauthenticated fetch of the exact same URL
also 404s. This means Tj's browser wasn't logged into the `tjshea90`
GitHub account when he clicked it.

While investigating, found something more serious: **the signing keystore
(`app/sideload.jks`) is present in this repo's git HISTORY** - added in the
old Cowork-era commit `f00c950` (2026-09-07) and again in the `5304cd6`
"Import ... checkpoint 66" commit (2026-09-10), never scrubbed, just later
removed from the working tree. It is NOT in the current HEAD tree, but the
blob is fully fetchable from those commit SHAs today. This matters because
BRIEF.md's whole safety model for this file assumed the repo stayed
private - not that the key was actually absent from history.

Asked Tj how to proceed (AskUserQuestion, twice - once on repo visibility,
once specifically on the keystore-in-history risk after finding it). He
chose: **make the whole repo public, accepting the keystore exposure risk
knowingly.**

Then hit a hard capability wall: **there is no tool in this session that
can change a GitHub repo's visibility.** The GitHub MCP toolset here has no
`update_repository`/settings call, and there's no `gh` CLI or raw API
access in this environment (see the system prompt's GitHub Integration
section). This is not something `ship.sh`/`record-release.sh`/the
`android.yml` workflow can do either - it is a one-time manual step only
Tj can take, from github.com → Settings → General → Danger Zone → Change
repository visibility → Public.

### Status - blocked on Tj, not on more work here

- [x] Diagnosed the 404 (private repo, correct link, needs login OR public)
- [x] Found and disclosed the keystore-in-history exposure risk before
      acting on "make it public"
- [x] Got Tj's explicit, risk-informed decision (public, accepts the risk)
- [ ] **Tj**: flip visibility himself (I cannot do this from here) - OR
      ask a future session to purge the keystore blob from history first,
      which I offered and he declined for now
- [ ] Once the repo is confirmed public (a future session can check via
      `search_repositories`'s `visibility` field), the "log in to see the
      link" caveat can be dropped from how release links are announced

## Tj's earlier request, 2026-09-19 (his own words)

> Do what you need to do to ship it and make the new version APK, and for
> every future update, always push the apk and send me the link to the
> finished apk automatically, without me asking

Two parts:
1. Ship the full-tests fix session below as a real release (bump version,
   `ship.sh`, trigger the GitHub build, confirm green, record it).
2. A standing policy change for every future session: don't wait to be
   asked to ship - after a meaningful unit of work is done, ship it the
   same way and post the release link in chat automatically. This is
   written into CLAUDE.md's "Releasing" section so it survives into new
   sessions, the same way the light/full-test protocols were. It does NOT
   change the 2026-09-11 rule that Claude never sends the raw APK bytes -
   "the link" means the GitHub Release page/asset URL, which
   `get_release_by_tag` already provides; the technical block on
   downloading a private repo's release asset bytes is unrelated and
   still stands.

### Done

- [x] Bumped versionCode 83→84, versionName 7.26→7.27 in app/build.gradle.kts
- [x] `ship.sh` gated (checkinit, full unit suite, versionCode check) and pushed
- [x] Triggered `android.yml` on `main` with `full_build: true` (run 35419792350) - green
- [x] Confirmed Release v7.27 published with its signed APK asset
- [x] `record-release.sh` recorded it in BUILDLOG.md
- [x] Posted the release link in chat
- [x] Made auto-ship-and-notify the standing policy in CLAUDE.md (see above)

## Tj's earlier request, 2026-09-19 (his own words)

> Run full tests of the latest version of this app

This is the "full tests" protocol defined below in CLAUDE.md's "Testing on
request" section. Executing it now: no budget limit, full unit suite as the
floor, then a whole-app audit split across parallel subagents by subsystem
(recommendation/scoring, day-trading, network/caching, UI), fix everything
found, re-verify, checkpoint.

### Full-test progress — DONE

- [x] `checkinit.py` + full Gradle unit suite (floor) — green
- [x] Parallel subsystem audits (recommendation/scoring, day-trading,
      network/caching, UI/battery) — all 4 reported back
- [x] Reconciled findings, fixed everything found:
      - HIGH: `DayTradingTechnicals.sessionDay`/intraday fields were stamped
        from wall-clock time instead of the actual date of the fetched bars,
        so a weekend/holiday/pre-4am fetch that got back the last closed
        session's real numbers was labeled "today" and sailed through
        `effectiveTechnicals`'s non-zero direct pass-through unchallenged -
        `rangeUsed` read as a fully-spent day at market open on a stock
        that hadn't traded. Now gated on the bars' own date.
      - `Http.postJson` didn't disconnect the socket on cancellation
        (unlike `Http.get`), so a cancelled Claude API call paid for the
        whole body and held a per-host permit. Fixed to mirror `get()`.
      - Data-loss risk: every dialog holding typed-but-unsaved data
        (TxnEditorDialog, EditPositionDialog, the Settings backup/restore
        text) and every dialog-open flag used plain `remember`, which does
        not survive Android killing the process while backgrounded (only
        rotation). Promoted to `rememberSaveable` across TxnEditor.kt,
        RowActions.kt, DetailScreen.kt, ActivityScreen.kt,
        PortfolioScreen.kt, WatchlistScreen.kt, SettingsScreen.kt.
      - `EarningsTab` off-by-one: `Long` division truncation toward zero
        made a just-passed earnings date still read "In 0 days."
      - `ScreenRow.merge`'s `earningsEstimated` was ANDed across both
        sides regardless of which side's `earningsAt` actually survived.
      - An off-center analyst price-target term in `ResearchScore.withAnalyst`
        (-2 at zero upside instead of neutral) - offset corrected.
      - Three different "where issuers close funds" dollar figures across
        Research.kt/EtfScore.kt reconciled in wording (each threshold's
        actual use - admission floor/score ramp/UI warning - was already
        deliberately different; only the contradictory phrasing was fixed).
      - A stale `Position` KDoc claiming average-cost-only.
      - Tightened a comment in `DayTradingEval.Costs` that implied a
        buy-stop/buy-limit distinction the code doesn't make.
- [x] Re-ran the full unit suite after every batch — green throughout
- [x] Checkpointed as work completed (ckpt 1552-1554)

## Tj's earlier request, 2026-09-19 (his own words)

> From now on, I will be asking for "light tests" and "full tests" after
> Claude does work on this project. Make permanent knowledge for Claude so
> that when I tell it to run light tests (at any time I ask) or full
> tests, Claude knows exactly what to do with no further explanation from
> me. This must be permanently in Claude awareness so that if I ask, even
> in a brand new code session with no context, Claude knows what to do.
>
> If I ask for light tests (or any similar wording like light test or
> light testing): run low usage, light test on the last version of the
> app or the latest in progress work on the app after all work is
> complete and look for obvious bugs or ui issues and look for any ways
> other parts of the app may have broken or been corrupted by anything
> that was changed in the current session. Then fix any findings. If
> there were any major findings, fix them and when the fixes are
> complete, run another light test to ensure the fixes worked without
> breaking any other part of the app.
>
> If I ask for full tests (or anything similar like full test or full
> testing or comprehensive tests): do a full, comprehensive test suite of
> the entire app. Usage and amount of time spent on the testing is no
> concern, prioritize best effort at app testing and improvement. During
> a full test, look for any improvements in code or ui, improvements in
> network efficiency if Internet is needed, improvements in caching and
> data retention so important data is not lost from the app, improvements
> in logic for systems and engines within the app and make sure they work
> as designed, and search for and fix any bugs or parts of the app that
> have broken or been corrupted from changes. Search for waste of
> resources or battery usage and ensure that the app properly sleeps when
> it is not in use. The goal of this testing is to ensure the final
> release of the app is efficient and well coded and the features and UI
> work well and are intuitive with little to no bugs or data loss.

## Screening

Housekeeping/policy request — writing a permanent protocol into CLAUDE.md,
no app-logic change. No escalation.

## Done

- [x] Added a "Testing on request" section to CLAUDE.md (auto-loaded as
      project instructions every session, independent of hooks or
      `bootstrap.sh` — the most reliable place, so this works even in a
      session that never runs `tools/resume.sh`) spelling out the exact
      "light tests" and "full tests" protocols, worded so no further
      explanation from Tj is ever needed:
      - **Light tests**: run `checkinit.py` + the full unit suite, review
        the session's diff for bugs/UI-logic issues (noting this
        container has no emulator/device, so "UI issues" means reading
        the changed Compose code, not a live visual check), grep for
        other callers of anything changed to catch ripple effects, fix
        findings, and re-run the same light pass once if anything major
        was fixed.
      - **Full tests**: no budget/time limit, full unit suite as the
        floor, then a whole-app audit (bugs/breakage, code+UI quality,
        network efficiency and BRIEF.md source-order compliance, caching
        and data-retention gaps, scoring/engine logic vs. BRIEF.md's
        locked decisions, battery/background-lifecycle correctness via
        the `fgScope` pattern), suggesting parallel subagents by
        subsystem the way past sessions already did (see
        `audits/round66/`, the "4-way parallel audit" checkpoints), fix
        everything found, then re-verify.

## Do this next

Nothing pending — await Tj's next request.
