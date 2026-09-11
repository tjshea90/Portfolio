# TASKS — the current job

## Tj's request, 2026-09-11 (his own words)

> I like the buy sell hold symbols but also include them in the main
> portfolio tab next to each stock

## Design (before writing code)

Reuses everything the detail-screen feature already built and shipped in
v7.9 (`ResearchScore.holding`, `Recommend.build`, `PortfolioViewModel.
recommendations`/`loadRecommendation`, `RecommendationDialog`) - this is a
second place to SHOW the same already-computed, already-tested verdict, not
a new scoring system. Screened against SCREENER.md: no money-accuracy logic
changes, no locked-architecture change, an existing pattern to mirror (the
row's own "News" chip, `ui/StockRow.kt`) - stays on Sonnet.

The one genuinely new piece: `loadRecommendation` has only ever been fed by
`loadFundamentals`, which until now was called ONLY when a stock's detail
screen opened (`DetailScreen.kt`, the sole call site). Showing a verdict on
EVERY row of the Portfolio tab means fundamentals now need to be fetched for
every held position, not just the one being looked at - Tj's own "without
using so much internet pulls that providers ban or limit my requests" rule
from the previous request applies here just as much, so this fetch is
staggered (a few symbols at a time, not all ~16 in one instant) rather than
firing every holding's request in the same tick.

- [x] Added the BUY/HOLD/SELL chip to `StockRowItem` (`ui/StockRow.kt`):
      matches the row's "News" chip pattern (size, shape, tap target),
      placed to its left, tinted and worded with the verdict once known and
      "..." before that - never a blank space. Tapping it opens the same
      `RecommendationDialog` the detail screen uses, and does NOT open the
      stock (unlike the rest of the row). Not shown on a watch-only row -
      the verdict is about a position TJ holds, and a watched-but-unowned
      symbol has none.
- [x] `PortfolioViewModel.loadPortfolioFundamentals` triggers fundamentals
      for every held row (`PortfolioScreen.kt`), staggered 3-at-a-time with
      a 400ms gap rather than firing all ~16 in one instant; `loadRecommendation`
      then runs per row once its fundamentals arrive - reusing the existing
      zero-extra-network, once-per-trading-day cache exactly as built for
      the detail screen. Both effects are keyed on the symbol list and the
      fundamentals map, not on the price-ticking `Row` objects, so they do
      not refire on every quote tick (four times a minute).
- [x] Tests: 7 new UI-render tests (`StockRowRecommendationUiTest.kt` -
      placeholder, each verdict word, tap-opens-popup-not-detail, dismiss,
      no chip on a watch-only row, News chip unaffected). Deliberately no
      dedicated test for the stagger loop itself - it is glue over
      already-tested primitives (`fgScope`, `loadFundamentals`'s own
      guards), the same boundary `BackgroundTest.kt` already draws.
- [x] Full Gradle unit suite green: 845 tests, 0 failures, 0 errors -
      including `SparklineSizeUiTest`, confirming the new chip did not
      disturb the row's chart-sizing layout it sits beside.
- [x] Checkpointed after every completed step (ckpt 620-622).
- [x] Shipped: v7.10 (code 67), GitHub Actions run #9 built, signed,
      verified its own certificate, and published the Release; APK
      downloaded (sha256 verified against the Release's own digest), sent
      to Tj, and recorded in BUILDLOG.md.

## The flow, verified 2026-09-11 (details in CLAUDE.md)

Tj describes what he wants -> Claude codes, tests and checkpoints -> `ship.sh`
gates and pushes -> Claude triggers the workflow through the GitHub API ->
GitHub compiles, signs, verifies the certificate and publishes the Release ->
Claude sends Tj the APK -> `tools/record-release.sh` writes BUILDLOG.

The model screener (SCREENER.md) now runs on every message before that flow
starts: money-accuracy logic, irreversible actions, locked architecture,
ambiguous design, a previously-failed fix, security, or Tj's own words
flagging something as important all pause for an Opus check before any code
is touched, unless Tj has already said to proceed on Sonnet.
