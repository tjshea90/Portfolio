# CHECKPOINT 1481 — read me first, then TASKS.md

**Written:** 2026-09-16T04:57:13Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `1724f891` (this checkpoint is the commit after it)

## Just done
gated v7.23 (code 80) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.23 "Insider tab now shows major insider activity for all publicly traded companies, not just held/watched stocks, with a My stocks/All companies toggle and existing 10b5-1-plan legitimacy badges carried over; Watchlist rows show a running percent change since the date added, anchored to that day's close so the add-day's own move is excluded"
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  1724f891 ckpt 1480: Second code-review pass (requested by Tj as a pre-ship sanity check) plus an
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
  5b7d9bf2 ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cove
  5ae3b3d9 ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccession
  f02ab54f ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by access
  0759fb83 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagin
  5fd0c5f4 ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all publi
  29d5afa5 ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, ver
  f2c635d8 ckpt 696: Shipped v7.22 (code 79): ship.sh gate passed (checkinit, full unit suite, ver
  8de9083e ckpt 695: gated v7.22 (code 79) and pushed it: checkinit, the full unit suite and the v
```
