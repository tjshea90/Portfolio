# BUILDLOG — every shipped release, in order

Written to by `ship.sh` on every release. `releases/` keeps only the newest
three APKs (older ones stay in git history, recoverable by SHA); this file
is the durable record of every version that ever shipped, including pruned
ones, so `ship.sh` can always compute the next versionCode correctly.

| v7.7 | code 64 | 2026-09-10T13:37Z | Migrated from the Cowork round-based checkpoint system to this GitHub repo. Round 66: ETF ranking reworked, Worst tab deleted, cache/refresh audit (A02/A05/A07), restore-merge data-loss fix (CRX2), same-day round-trip fix (CRX1), 46 further findings across 8 audit dimensions. 797 tests, 0 failures, signed with the same cert as v7.5/v7.6.
