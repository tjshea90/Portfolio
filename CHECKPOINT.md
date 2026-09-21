# CHECKPOINT 1577 — read me first, then TASKS.md

**Written:** 2026-09-21T07:18:32Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stock-etf-refresh-stale-data-mohnpm` · **builds on:** `bebe4ec` (this checkpoint is the commit after it)

## Just done
gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.30 "Fixed Best-Stocks refresh flicker (stray analyst-enrich job could splice stale, score-boosted data onto a newer rebuilt list); Claude-analysis cache now expires and evicts why-text past 14 days instead of carrying it forward forever; fixed the SPY-comparison chart's pan/zoom baseline bug (anchor now fixed to the selected range's true start instead of the panned window's edge, closing a repeat of the round-67 'spy line jumps' report)"
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
  2c54651 ckpt 1574: Logged Tj's new request (Best Stocks/ETFs/Trending refresh flicker + stale Cl
  6e0ce02 ckpt 1573: v7.29 (code 86) shipped: GitHub build green, Release published and recorded i
  30a8a51 ckpt 1572: gated v7.29 (code 86) and pushed it: checkinit, the full unit suite and the v
  204a04b ckpt 1571: Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH 
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
  934d899 ckpt 1568: gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the v
  9b9c71c ckpt 1567: Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, che
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
