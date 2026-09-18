# CHECKPOINT 1541 — read me first, then TASKS.md

**Written:** 2026-09-18T14:11:34Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-app-release-49yt07` · **builds on:** `4a8ea8a` (this checkpoint is the commit after it)

## Just done
Fixed 8 real bugs from a 4-way parallel audit (day-trading logic, UI, network efficiency, ledger/parsing correctness): DayTradingEval same-bar pullback WIN false-credit, Ledger SPLIT not rescaling oversold shortfall, Fees.kt auto-filling current-year SEC/TAF rates on backdated pre-4-Apr-2026 trades, Form4 footnote plan-detection overriding an explicit false checkbox, ChartFeed missing conditional-GET caching, unbatched day-trading-log SQLite inserts, WatchlistScreen never surfacing a refresh error, Reset-zoom chip under 48dp, FeedScreen Insider filter/scope/source resetting on every tab switch, and a dead click target on a linkless/tickerless headline. All with regression tests.

## Do this next
Full Gradle unit suite is blocked on repeated Maven Central 429 rate-limiting in this container - retrying with backoff. Once green, run a /code-review pass over the diff, then ship.sh.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  4db7ea9 ckpt 1540: Confirmed v7.24 (code 81) build green and Release published; recorded it in B
  379455f ckpt 1538: gated v7.24 (code 81) and pushed it: checkinit, the full unit suite and the v
  59f8c2f ckpt 1536: Fixed both real issues the independent audit agent found in the new Day Tradi
  6632eb6 ckpt 1529: Full Gradle unit suite green after the watchlist bug fixes: 1125 tests, 0 fai
  a1162e3 ckpt 1528: Requested code-review pass surfaced 3 real bugs in the ALREADY-SHIPPED (v7.23
  a11832b ckpt 1520: Full Gradle unit suite green: 1121 tests, 0 failures, 0 errors (56.5s, verifi
  07e3d49 ckpt 1519: Added 9 new DbTest cases for day_trading_log: round-trip, the core append-onl
  0be47df ckpt 1515: Implemented Part 15's core: Db v8->9 (day_trading_log table, append-only via 
```

(25 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
