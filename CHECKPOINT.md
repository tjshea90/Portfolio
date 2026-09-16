# CHECKPOINT 1536 — read me first, then TASKS.md

**Written:** 2026-09-16T06:38:30Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `b9d35d78` (this checkpoint is the commit after it)

## Just done
Fixed both real issues the independent audit agent found in the new Day Trading log feature: (1) entryRises() inferred trigger direction purely from the setup string, defaulting to 'rising' for anything not literally 'Pullback' - broken for a Claude-authored plan's free-text setup name describing a real falling-entry trade. Fixed: priceAtRecommendation vs entry is now the authoritative signal (setup-string is only a fallback when price data is missing/degenerate), threaded through evaluate()'s signature and the ViewModel call site. (2) recordedAt was stamped inside Db.logDayTradingRecommendation's delayed IO coroutine rather than when captureDayTradingRecommendations actually read the row, and nothing gated capture to market hours - a stale/after-hours-observed row could get a misleading late recordedAt and silently read as NO_ENTRY for a plan that actually triggered and won hours earlier. Fixed: recordedAt is now read synchronously at capture time and passed through explicitly; capture only runs while MarketClock.phase() == OPEN. Added regression tests for both (including the exact Claude free-text-setup scenario end to end through evaluate()). TASKS.md Part 15 checklist fully ticked. Full suite green: 1127 tests, 0 failures, genuinely verified.

## Do this next
Bump versionCode/versionName in app/build.gradle.kts, run ship.sh, trigger the GitHub Actions build, confirm green, record the release, report to Tj.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  6632eb6a ckpt 1529: Full Gradle unit suite green after the watchlist bug fixes: 1125 tests, 0 fa
  a1162e35 ckpt 1528: Requested code-review pass surfaced 3 real bugs in the ALREADY-SHIPPED (v7.2
  a11832bb ckpt 1520: Full Gradle unit suite green: 1121 tests, 0 failures, 0 errors (56.5s, verif
  07e3d492 ckpt 1519: Added 9 new DbTest cases for day_trading_log: round-trip, the core append-on
  0be47dfc ckpt 1515: Implemented Part 15's core: Db v8->9 (day_trading_log table, append-only via
  dd922831 ckpt 1486: Recorded Part 15 in TASKS.md: Tj wants every Day Trading recommendation (buy
  542a9db3 ckpt 1483: v7.23 (code 80) shipped end to end: GitHub Actions run #23 built, signed, ve
  83cdf773 ckpt 1481: gated v7.23 (code 80) and pushed it: checkinit, the full unit suite and the 
  1724f891 ckpt 1480: Second code-review pass (requested by Tj as a pre-ship sanity check) plus an
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
```

(6 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
