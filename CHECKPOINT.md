# CHECKPOINT 1542 — read me first, then TASKS.md

**Written:** 2026-09-18T15:20:57Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-app-release-49yt07` · **builds on:** `5c7226f` (this checkpoint is the commit after it)

## Just done
gated v7.25 (code 82) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.25 "General audit pass: fixed a same-bar false-WIN credit for pullback trades in the Day Trading success-rate evaluator, a stock split not rescaling an existing oversold shortfall, the fee auto-fill silently using current-year SEC/TAF rates on backdated pre-4-Apr-2026 trades, an insider Form 4's footnote plan-detection overriding an explicit not-planned checkbox, plus network/UI fixes: chart history now uses conditional-GET caching, day-trading-log writes are now batched in one transaction, the Watchlist tab now surfaces a failed refresh, the chart's Reset-zoom chip meets the 48dp touch target, the Insider tab's filter/scope/source no longer reset on every tab switch, and a headline with no link or ticker is no longer a dead click target."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  7106821 ckpt 1541: Fixed 8 real bugs from a 4-way parallel audit (day-trading logic, UI, network
  4db7ea9 ckpt 1540: Confirmed v7.24 (code 81) build green and Release published; recorded it in B
  379455f ckpt 1538: gated v7.24 (code 81) and pushed it: checkinit, the full unit suite and the v
  59f8c2f ckpt 1536: Fixed both real issues the independent audit agent found in the new Day Tradi
  6632eb6 ckpt 1529: Full Gradle unit suite green after the watchlist bug fixes: 1125 tests, 0 fai
  a1162e3 ckpt 1528: Requested code-review pass surfaced 3 real bugs in the ALREADY-SHIPPED (v7.23
  a11832b ckpt 1520: Full Gradle unit suite green: 1121 tests, 0 failures, 0 errors (56.5s, verifi
  07e3d49 ckpt 1519: Added 9 new DbTest cases for day_trading_log: round-trip, the core append-onl
  0be47df ckpt 1515: Implemented Part 15's core: Db v8->9 (day_trading_log table, append-only via 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
