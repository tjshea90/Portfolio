# CHECKPOINT 1579 — read me first, then TASKS.md

**Written:** 2026-09-21T07:44:42Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stock-etf-refresh-stale-data-mohnpm` · **builds on:** `dcb7339` (this checkpoint is the commit after it)

## Just done
gated v7.31 (code 88) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.31 "Full-tests audit and fix pass across the whole app (4 parallel subsystem audits: recommendation/scoring, day-trading, network/caching, UI/battery/persistence). Fixed a HIGH bug where the previous release's own Claude-advice staleness fix could be defeated: whyAt was stamped on every matched merge regardless of whether why itself was refreshed, letting a weeks-old paragraph's clock reset to now whenever a reply only touched a catalyst or trade level. Extended the same 14-day staleness eviction to the batch-level Explained-via-Claude summary (notes/explainedBy and the day-trading counterparts), which had no age check at all. Added staleness eviction for cached day-trading trade-plan levels on a cold launch, so a stale buy/stop/sell price from a previous trading day can no longer sit on screen with nothing marking it stale if the follow-up rebuild then fails. Fixed a recommendation-dialog timestamp that showed a bare time with no date, making a multi-day-old cached verdict look freshly computed. Added a note surfacing when a recommendation's underlying fundamentals data is itself stale, independent of when the verdict was computed. 1228 tests, 0 failures, 16 new regression tests."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  7fc4b13 ckpt 1578: Full-tests audit (4 parallel subsystem agents) reconciled and fixed: HIGH bug
  9694006 ckpt 1577: gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the v
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
  2c54651 ckpt 1574: Logged Tj's new request (Best Stocks/ETFs/Trending refresh flicker + stale Cl
  6e0ce02 ckpt 1573: v7.29 (code 86) shipped: GitHub build green, Release published and recorded i
  30a8a51 ckpt 1572: gated v7.29 (code 86) and pushed it: checkinit, the full unit suite and the v
  204a04b ckpt 1571: Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH 
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
