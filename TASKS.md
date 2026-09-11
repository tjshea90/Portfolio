# TASKS — the current job

## Tj's request, 2026-09-11 (his own words, three parts)

> move the buy sell hold tab from where it currently is to somewhere around
> where the arrow points. don't change it's function, only the placement.

(with a screenshot: the current tab row - Stats / Analysts / Earnings /
Buy / News - circled at "Buy", with an arrow from there up to the top-right
of the price header, near the "AFTER HOURS / OVERNIGHT" price.)

> for some reason when I put a stock chart in full screen and scroll left
> to right, the stock chart stays accurate but the spy line jumps up and
> down with the dotted line. investigate this and fix it.

(with a screen recording of the bug in the full-screen chart's SPY overlay.)

> under the research tab, make a new tab called day trading. for this tab,
> list that days top stocks for day trading stocks expected to rise in
> value and include a target buy price and target sell price for each of
> the stocks. the goal is to get accurate signals on each day's hot stocks
> that are moving up or expected to move up due to things like short
> squeezes or news or catalysts. this section will need advanced analysis
> to be accurate. it must be accurate and give sound signals. for this
> project, do deep research on successful day traders and their methods,
> sources needed for successful day trading, technical information and
> news sources with accurate information to base the recommendations,
> wallstreetbets chatter if it is helpful, etc. do deep research on what
> information and sources the app will need on a daily basis to make the
> best recommendations on when to buy and sell which stocks and why. take
> as long as you need and use as much usage as needed. the advice must be
> well informed daily and it must be based on solid information and
> reasoning. include only stocks that are at least 2 dollars a share when
> searched. if Claude reasoning is needed or useful for this section,
> include a button to use Claude and also a button to make a file for
> Claude app that I can upload into the Claude app and it will understand
> the prompt with no explanation from me and output a file that I can
> import back into the stock app which fills in all the information for
> this section. the feature to do this is already in other parts of the
> app research and implement the same type of logic here

## Screening these three parts separately (SCREENER.md)

Not one request - three, at very different risk levels, so screened
separately rather than as a block:

1. **Tab placement.** Pure UI relocation, explicit "don't change its
   function." No existing-pattern ambiguity once the target spot is
   identified from the screenshot. Stays on Sonnet.
2. **SPY overlay pan bug.** A display bug in the chart's benchmark overlay,
   not the money-accuracy categories SCREENER.md lists (cost basis,
   gains/losses, tax lots, valuation, the ledger, recommendation scoring) -
   the SPY line is a visual comparison, not a number TJ trades on. Narrow,
   reproducible (screen recording provided), scoped to the chart-rendering
   files. Stays on Sonnet.
3. **Day Trading tab.** This is a NEW recommendation feature under
   SCREENER.md's own money-accuracy category, which names "any future
   recommendation feature" explicitly - and a materially riskier one than
   the buy/hold/sell feature already shipped: same-day price PREDICTIONS
   with specific target buy/sell prices, for volatile, short-horizon,
   news/momentum-driven moves ("short squeezes") rather than the
   fundamentals-and-analyst-consensus basis the existing feature stands
   on. No existing pattern in the app to mirror - real design and sourcing
   decisions have to be made from scratch. And Tj's own words flag it
   directly, repeatedly: "it must be accurate," "well informed daily,"
   "based on solid information and reasoning," "advanced analysis." This
   is the money-accuracy + ambiguous-design + Tj's-own-words trifecta the
   criteria describe. **Flagged in chat, not started.** See the flag for
   what "started" excludes (TASKS.md recording is not code).

## Part 1 + 2: proceeding on Sonnet

- [x] Moved the recommendation indicator out of the tab row entirely - it
      was never really a tab (tapping it never switched content), so
      `DetailTab.RECOMMENDATION` is gone and `DetailTabRow` is back to its
      original simple form. New `RecommendationBadge` composable sits
      beside `PriceBlock` in ordinary flow layout (not an overlay, so it
      cannot collide with the after-hours column at a large font scale) -
      same verdict word/color/tap-opens-`RecommendationDialog` as before,
      just relocated near the price where the screenshot's arrow pointed.
      The Portfolio list row's own chip (`StockRow.kt`) is untouched.
- [x] Root-caused and fixed the SPY/compare-line pan bug. Real cause: the
      comparison-mode rebase anchor was a CANDLE INDEX that only advances
      once a full day of drag crosses a real candle on a 1Y chart, so both
      percent lines re-rebased from a new anchor in discrete steps - hidden
      on the stock's own line by the shared, stock-dominated y-axis, a
      violent jump on the benchmark's line and the dashed zero-reference
      line that reads off the same axis. Fixed by freezing the anchor for
      the life of a live gesture and re-syncing once it ends - by
      TIMESTAMP, not by index (a first draft froze an index into a
      re-sliced view and was wrong in a way that would have been worse
      than the original bug; caught and corrected before shipping).
- [x] Tests: DetailTabCrashTest's counts reverted (RECOMMENDATION no
      longer inflates them), 3 new RecommendationBadge render tests
      replacing the 2 retired DetailTabRow-param ones, 2 new pure-logic
      cases for `primaryPercents`' `fromValue` override, and a full
      gesture-simulation regression test (`ComparePanAnchorUiTest`) that
      drives a real multi-step pan across many candles in one continuous
      gesture and proves the rendered readout matches the frozen-anchor
      expectation mid-drag and the live-anchor expectation after release -
      computed via the same production functions, fed the real window
      `PriceChart` reported, not hand-predicted.
- [x] Full Gradle unit suite green: 849 tests, 0 failures, 0 errors -
      including all 176 pre-existing chart tests (no regressions) and the
      33 tests from the earlier tab-placement/StockRow work this session.
- [x] Checkpointed after every completed step (ckpt 625-627).
- [x] Shipped: v7.11 (code 68), GitHub Actions run #10 built, signed,
      verified its own certificate, and published the Release; APK
      downloaded (sha256 verified against the Release's own digest), sent
      to Tj, and recorded in BUILDLOG.md.

## Part 3: Day Trading tab

**Tj, 2026-09-11: "proceed with the day trading tab with sonnet"** - explicit
override of the SCREENER flag, per SCREENER.md's protocol step 3. Proceeding
without re-asking.

Research phase FIRST, before any scoring code - matching the "if this is not
possible do not make the feature" standard Tj set for the last recommendation
feature, and his own words this time: "it must be accurate and give sound
signals... do deep research... the advice must be well informed daily and it
must be based on solid information and reasoning."

- [x] Research day-trading methodology: what legitimately separates a
      well-grounded "stocks worth watching today and why" screen from a
      false promise of predicting which stocks will rise and exactly where
      to buy/sell them. The feasibility finding is written into
      `net/ResearchScore.kt`'s `dayTrading()` header and `net/DayTradingBridge.kt`'s
      class header: genuine same-day price prediction is not achievable from
      free public data (market efficiency), so the feature surfaces stocks
      OBJECTIVELY IN PLAY today (volume, a real move, attention, breakout,
      squeeze setup) with entry/stop/target reframed honestly as a computed
      risk-management plan, not a forecast.
- [x] Inventory what data this app can actually reach daily - done: the
      section is built from the SAME nine-screener universe and trending's
      own WSB/news signal `buildTrending` already pays for, zero new
      requests, exactly the constraint the feasibility finding above rests on.
- [x] Studied and mirrored the existing Claude-bridge pattern: `DayTradingBridge.kt`
      has the same prompt/bundleJson/apiPrompt/parse/merge shape as
      `ResearchBridge.kt`, and the ViewModel wiring (explain/import/prompt-file)
      mirrors the Research tab's functions one-for-one.
- [x] Designed, built, tested and shipped: Research.kt scoring/build,
      DayTradingBridge.kt + Claude.kt's dayTrading(), full ViewModel wiring
      (with its own dtExplained/dtNotes state, kept separate from Research's
      so a rebuild cannot silently wipe either one - a real bug found and
      fixed along the way), ResearchScreen.kt's Day Trading tab with an
      Entry/Stop/Target risk-plan grid and honest disclaimer copy, and 22 new
      tests (scorer, trade-levels arithmetic and its volatility clamp, bridge
      parse/merge/prompt, carry-forward-across-rebuild). Full suite: 871
      tests, 0 failures. Shipped as v7.12 (code 69), GitHub Actions run #11
      built, signed, verified its own certificate, and published the Release;
      recorded in BUILDLOG.md.
      **Not yet done:** the APK itself could not be relayed through this chat -
      this session's tool access has no way to download a PRIVATE repo's
      release-asset bytes (the GitHub MCP tools here cover metadata/API calls
      but not binary asset download, and raw curl/credential-based workarounds
      are correctly blocked). Tj needs to grab v7.12 himself from
      https://github.com/tjshea90/Portfolio/releases/tag/v7.12 (logged into
      GitHub in his own browser) - sha256 of Portfolio-v7.12.apk is
      19376b15dd94ac421f7175499e582a6de093236256ca1f09a4623fdcf8f89a7f, per
      the Release's own asset digest. A future session should not re-attempt
      raw curl/credential extraction for this - it is a session-tooling gap,
      not a fixable bug here.

## Part 4: Day Trading — research-backed signals, per-stock explanations, tab-gated live data

**Tj, 2026-09-11: "go ahead with sonnet"** - explicit override of the
SCREENER flag, per SCREENER.md's protocol step 3. Proceeding without
re-asking.

Tj's request, 2026-09-11 (his own words):

> for the day trading section of this app. review the logic behind the buy
> and sell signals and their price targets. do comprehensive, deep research
> on proven day trading algorithms, timing, volatility, and how to calculate
> good buy and sell price targets. use many legitimate and professional
> sources for the research. incorporate the research and algorithms into the
> app in the day trading section. make sure it works well and follows the
> research accurately. each day should name new, updated stocks and their
> price targets to buy and sell. right now the app doesn't have buy and sell
> target numbers. also, the market is currently closed and yet the stocks
> claim to be "already up today" which makes no sense. include a feature
> where I can click on each stock and there is an explanation for the buy
> and sell points and why the stock is recommended. this feature may pull
> from as many Internet sources as it needs each refresh. but if it is data
> intensive, make sure it only refreshes this section of the app if I am
> actively using it (the tab is open). time and Claude usage to make this is
> no concern, accuracy is important. when this is complete, run tests for ui
> and code improvements to the app and bug fixes

Two clarifying questions were asked before screening, and Tj's answers
**lock in the design** (his own words, verbatim):

> [Should entry/stop/target stay honest risk-management math, or attempt
> real price predictions?] Keep it honest and improve the algorithm and
> input quality but do deep research on how to project good buy and sell
> target prices. This can use Claude if needed in the app or with the
> export to Claude app system

> [Should live research run automatically on tab open, or only on a button
> tap?] Do not use Claude api at all in the app unless I explicitly press a
> button, or alternatively export it so the Claude app can respond and
> import back to the portfolio app. When I said time and usage are no
> concern, I meant right now as you are building this app. The app should do
> as much as possible without Claude in making the best day trading
> recommendations, and it can use as much mobile Internet data as needed,
> and it can do this all automatically, but only when I have that tab open.
> The feature should be asleep when I'm not using it

So, decided (do not re-litigate; implement to this):

- **No automatic Claude calls, anywhere in this feature.** Claude only runs
  when Tj taps Explain/Re-explain (API key path) or through the existing
  export-prompt-file / import-answer round trip - exactly like today. The
  scoring/level algorithm itself must be Claude-free.
- **The app's own data pipeline may be as aggressive as it needs while the
  Day Trading tab is open** - more screeners, more technicals, more
  news/volatility inputs, refreshed live - and must go fully idle (no
  network activity from this section) the moment the tab is not visible.
  This is an app-side live-refresh behavior, separate from the "no
  automatic Claude" rule above.
- **Stay in the "risk plan, not a forecast" honest framing** - see
  `ResearchScore.dayTrading()`'s existing header for the feasibility finding
  this already rests on - but do real, sourced research (legitimate,
  professional sources - not guessing) on proven day-trading methodology,
  volatility modeling and target-price calculation, and use it to make the
  actual algorithm meaningfully better than today's simple 2:1/volatility
  clamp.
- **Fix: "already up today" while the market is closed.** `changePct` is
  last-close-vs-current, which is correct data but mislabeled - it needs to
  read honestly depending on market state (e.g. "up X% - last session" when
  closed vs "up X% today" while open), and the day-trading list needs a real
  day-boundary so it names picks for the CURRENT session rather than stale
  ones from a closed market.
- **New: tap a stock for a full explanation** of its buy/sell points and why
  it's recommended - a detail view/dialog, mirroring the existing
  buy/hold/sell `RecommendationDialog` pattern already in the app rather
  than inventing a new one.
- **After the feature lands:** a UI/code-improvement and bug-fix pass, per
  Tj's own words closing out the request.

## The flow, verified 2026-09-11 (details in CLAUDE.md)

Tj describes what he wants -> Claude codes, tests and checkpoints -> `ship.sh`
gates and pushes -> Claude triggers the workflow through the GitHub API ->
GitHub compiles, signs, verifies the certificate and publishes the Release ->
Claude sends Tj the APK -> `tools/record-release.sh` writes BUILDLOG.

The model screener (SCREENER.md) runs on every message before that flow
starts: money-accuracy logic, irreversible actions, locked architecture,
ambiguous design, a previously-failed fix, security, or Tj's own words
flagging something as important all pause for an Opus check before any code
is touched, unless Tj has already said to proceed on Sonnet.

## Part 5: Day Trading — real entry triggers, not spot price; Claude may rewrite the whole section

**Screened 2026-09-11: money-accuracy (buy/sell target logic). Flagged, Tj
switched to Opus, said "opus is on, continue." Running on Opus.**

Tj's request, 2026-09-11 (his own words):

> in the day trade section, the target buy price just matches the current
> market price. I don't think this is how day traders operate. do more deep
> research on day trading and algorithms and timing buys and sells based on
> charts and real time stock data. keep researching how day trading signals
> are calculated or make your own method based off real algorithms and
> methods. take as much time and usage as you need researching this. the day
> trading section must have good advice with accurate buy and sell price
> targets. for the Claude prompt, allow Claude to change the entire section
> as needed using real time information from the market. for example, the
> Claude prompt can change the stocks in the list if it finds better ones and
> it can give advice and buy and sell targets for all the stocks

He is right about the defect. `ResearchScore.tradeLevels`/`upgradeLevels`
both hardcode `entry = price` — the app's "target buy" is literally the last
trade. That is a stop/target plan bolted onto a non-existent entry decision,
not a day-trading signal.

- [x] Deep research (professional/legitimate sources) on how real day traders
      derive ENTRY triggers — not just stops/targets: opening-range breakout,
      VWAP reclaim/pullback, prior-day high/low, floor-trader pivots,
      measured-move and ATR-based target projection, R-multiples.
- [x] Replace `entry = price` with a real, setup-specific TRIGGER level, with
      the setup named, plus its invalidation (stop) and target derived from
      that setup's own structure — not a blanket 2:1 off spot.
- [x] Targets that come from real structure (pivot resistance, measured move,
      ATR projection, prior-day high) instead of only a fixed R multiple.
- [x] Show the setup + trigger condition in the UI and the tap-to-explain
      dialog ("buy the break above X", "buy the pullback to Y"), so a level
      that is not the current price reads as deliberate.
- [x] Rewrite the Claude prompt so Claude MAY change the whole section: swap
      out stocks it judges worse, add better ones, and set its own
      entry/stop/target per stock with its own reasoning, using live web
      data. Reverse the current "do not second-guess these numbers" rule and
      the merge path that discards Claude's levels.
- [x] Tests for every new level/trigger path, then the full gradle suite.

All six done and verified: `ResearchScore.tradePlan` (setup + trigger + structural
stop + structural target), `DayTradingTechnicals` extended with intraday ATR,
ADR(14), prior-session H/L/C, floor-trader pivots, premarket high and session
H/L, `DayTradingBridge` reversed so Claude owns the section, the card and dialog
showing the setup/trigger/warnings, and 929 tests green (23 new). A high-effort
`/code-review` pass over the whole diff found 9 issues, all fixed before ship.

Two defects Tj had reported earlier were also closed along the way:

- The stop was `1.5x the DAILY ATR` on a same-session trade - about 1.5 whole
  average sessions of risk, which is why the old numbers looked implausible.
  Practitioner sources are unanimous that an ATR stop uses the ATR OF THE
  TIMEFRAME TRADED, so it is now sized from a 5-minute ATR.
- "Up X% already today" over a closed market - Round 68 fixed the price line on
  the card and left the two REASON lines saying it, a centimetre lower.

## Part 6: Day Trading — per-stock charts, tabbed detail (mirroring the app's
existing pattern), watchlist button; and a rescored, confidence-blended score

Tj's request, 2026-09-11 (his own words, two parts):

> for this app, for the day trading section include stock charts just like
> the other sections of the app put a stock chart next to each of the stocks
> in the day trading section. the stock charts should show only that current
> day's chart, good for day trading. the card for each stock in this section
> can be as big as needed for the visually appealing UI and so all
> information is easy to see and read. make it so that when I press on any
> of the stocks in the section, in addition to what it already shows, there
> are tabs for more information similar to other parts of the app, for
> example charts and financial information. you can probably adopt this
> function from other parts of the app so you don't have to recode it. also
> add a button to add the stock to my watchlist.

> for the scores in the day trading section next to each stock make the
> scores reflect a blend of how likely the stock is to rise in value from
> its target buy price and how confident this prediction is. for example a
> score of 100 means the stock is very likely to raise in value from its
> current or Target buy price and that the model is extremely confident that
> this will happen.

## Screening these two parts separately (SCREENER.md)

1. **Charts, bigger cards, tabbed detail, watchlist button.** UI work with
   an explicit existing pattern to mirror - Tj names it himself
   ("adopt this function from other parts of the app"): `DetailScreen.kt`'s
   `DetailTabRow`/tab-content pattern (Stats/Analysts/Earnings/News) and its
   full-screen 1D-capable chart, and `StockRow`'s existing add-to-watchlist
   action. No new design decision - reuse, not invention. **Stays on
   Sonnet.**
2. **Confidence-blended score.** This redefines what the Day Trading score
   MEANS (currently a stocks-in-play screener score) into a probability-like
   "likely to rise x confident it will" blend - squarely
   SCREENER.md's money-accuracy category ("any future recommendation
   feature," any buy/sell scoring logic) AND ambiguous/high-judgment design
   (no existing pattern in the app defines "confidence" as a number, and
   nothing here can honestly measure a real probability of a stock rising -
   the definition has to be built from scratch and it is exactly the kind of
   subtle-logic-error-misinforms-a-real-decision case the category exists
   for). **Flagged in chat 2026-09-11, not started** (TASKS.md recording is
   not code).

## Part 6.1: charts + tabbed detail + watchlist button — proceeding on Sonnet

- [ ] Add a compact 1-day-only chart to each Day Trading card, reusing the
      app's existing chart component rather than a new one.
- [ ] Let each card grow to fit its content (chart + trigger/stop/target +
      reasons) instead of a fixed compact height.
- [ ] Tapping a card opens a tabbed detail view mirroring `DetailScreen`'s
      pattern (Chart / Stats / financial info), reusing those tabs/composables
      for the symbol rather than re-deriving them, with the existing
      trigger/stop/target/why-in-play content from `DayTradingDetailDialog`
      folded in as its own tab or section rather than dropped.
- [ ] Add a button in that detail view to add the stock to the watchlist,
      reusing the existing add-to-watchlist action/path.
- [ ] Tests for the new UI paths, then the full Gradle unit suite.

## Part 6.2: confidence-blended score — FLAGGED, not started

**Money-accuracy + ambiguous-design match per SCREENER.md. Session is on
Sonnet (`claude-sonnet-5`, confirmed via get_session). Not started - waiting
on Tj to switch to Opus (or explicitly say to proceed on Sonnet, as he did
for Parts 3-5).**
