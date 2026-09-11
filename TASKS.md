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

- [ ] Add a small BUY/HOLD/SELL chip to `StockRowItem` (`ui/StockRow.kt`),
      matching the row's existing "News" chip pattern and placed to its
      left - same visual language as the detail screen's tab-left-of-News
      placement. Tapping it opens the same `RecommendationDialog` used on
      the detail screen.
- [ ] Trigger fundamentals + recommendation loading for every row shown on
      the Portfolio tab (not just watch-only rows), staggered rather than
      all at once, reusing `loadRecommendation`'s existing zero-extra-cost,
      once-per-trading-day cache - this is a new caller of an existing
      function, not a new fetch mechanism.
- [ ] Unit/UI tests for the new chip rendering and the staggered fetch
      trigger.
- [ ] Full Gradle unit suite green before shipping.
- [ ] Checkpoint after every completed step.
- [ ] Ship following CLAUDE.md's normal release flow.

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
