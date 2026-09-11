# BUILDLOG — every shipped release, in order

Written to by `ship.sh` on every release. `releases/` keeps only the newest
three APKs (older ones stay in git history, recoverable by SHA); this file
is the durable record of every version that ever shipped, including pruned
ones, so `ship.sh` can always compute the next versionCode correctly.

| v7.7 | code 64 | 2026-09-10T13:37Z | Migrated from the Cowork round-based checkpoint system to this GitHub repo. Round 66: ETF ranking reworked, Worst tab deleted, cache/refresh audit (A02/A05/A07), restore-merge data-loss fix (CRX2), same-day round-trip fix (CRX1), 46 further findings across 8 audit dimensions. 797 tests, 0 failures, signed with the same cert as v7.5/v7.6.
| v7.8 | code 65 | 2026-09-10T21:20Z | Chart axis labels now carry the year when a window spans calendar years - a panned 3yr window read 'Sep 9' to 'Sep 10' and hid the fact that both lines rebase to the new left edge. Fixed the ScrollableTabRow IndexOutOfBounds that killed the app when a stock turned out to be a fund while News was selected. FeedScreen list deduped by the id it is keyed by.
| v7.9 | code 66 | 2026-09-11T01:16Z | Per-holding BUY/HOLD/SELL recommendation tab, left of News: analyst-consensus-weighted scoring (ResearchScore.holding) blended with valuation, growth and a year of relative performance, computed once per trading day from data already fetched for the Overview tab (zero new network requests), popup with full reasoning and analyst price target. 838 tests, 0 failures.
| v7.10 | code 67 | 2026-09-11T01:51Z | Buy/Hold/Sell chip on the main Portfolio tab, next to each holding - same verdict and popup as the detail-screen tab, tinted and tappable, left of the News chip. Fundamentals for all held rows now prefetch staggered (3 at a time) when the Portfolio tab opens, so the badge doesn't require opening each stock's detail screen first. 845 tests, 0 failures.
