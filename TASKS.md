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

- [ ] Move the recommendation tab out of the tab row, to the price header
      area near the after-hours price (per the screenshot's arrow) -
      same tap behaviour (opens `RecommendationDialog`), same verdict
      word/color, just relocated. `DetailTab.RECOMMENDATION` stops being a
      tab in `visibleTabs()`; a standalone badge composable takes its
      place near `PriceBlock`.
- [ ] Root-cause the SPY/compare-line pan bug in the full-screen chart
      (`PriceChart.kt`, `FullScreenChart.kt`) and fix it - the stock's own
      line pans correctly; the SPY overlay and a dashed reference line do
      not.
- [ ] Unit/UI tests for both.
- [ ] Full Gradle unit suite green before shipping.
- [ ] Checkpoint after every completed step.
- [ ] Ship following CLAUDE.md's normal release flow.

## Part 3: Day Trading tab - AWAITING TJ

Not started. If Tj confirms Opus for this one (or says proceed on Sonnet
anyway), pick it up as its own job: the research phase alone (successful
day-trading methodology, what data sources exist and are actually
reachable from a free/keyless-friendly stack this app already uses,
whether same-day price-target accuracy is honestly achievable at all
before committing to building it - matching the "if this is not possible,
do not make the feature" standard TJ set for the last recommendation
feature) needs to happen and be written up BEFORE any scoring code, per
the same discipline the buy/hold/sell feature was built under.

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
