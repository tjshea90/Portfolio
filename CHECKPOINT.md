# CHECKPOINT 1585 — read me first, then TASKS.md

**Written:** 2026-09-21T21:27:40Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stuck-refresh-loop-3bixfe` · **builds on:** `d3598f0` (this checkpoint is the commit after it)

## Just done
gated v7.33 (code 90) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.33 "APK-report UI fixes (bottom-tab-bar TalkBack semantics, an InfoDot/ExplainedHeader touch-target gap, Settings API-key field keyboard type) plus a full-tests 4-way parallel subsystem audit: fixed a scoring gap where trailing EPS in the $0.00-0.01 band scored zero growth credit (ResearchScore.kt), and a day-trading debounce gap that could show yesterday's numeric plan under today's date for one tick after an overnight session rollover (PortfolioViewModel.kt)."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  fba8e12 ckpt 1584: Ran the CLAUDE.md 'Full tests' protocol Tj asked for after the APK-report UI 
  7916349 ckpt 1583: Reviewed the attached third-party APK static-analysis report (v7.31) per Tj's
  8b5a8fc ckpt 1582: gated v7.32 (code 89) and pushed it: checkinit, the full unit suite and the v
  0f6e081 ckpt 1581: Diagnosed and fixed the stuck pull-to-refresh spinner Tj reported (screenshot
  ba64fb7 ckpt 1580: Audited the day-trading success-rate feature per Tj's request (numbers 'seem 
  65750c2 ckpt 1579: gated v7.31 (code 88) and pushed it: checkinit, the full unit suite and the v
  7fc4b13 ckpt 1578: Full-tests audit (4 parallel subsystem agents) reconciled and fixed: HIGH bug
  9694006 ckpt 1577: gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the v
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
