# CHECKPOINT 617 — read me first, then TASKS.md

**Written:** 2026-09-11T01:04:43Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/portfolio-recommendations-tab-jc8y40` · **builds on:** `be7e9b4` (this checkpoint is the commit after it)

## Just done
gated v7.9 (code 66) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
send Tj the APK from the Release and then run:
  bash tools/record-release.sh v7.9 "Per-holding BUY/HOLD/SELL recommendation tab, left of News: analyst-consensus-weighted scoring (ResearchScore.holding) blended with valuation, growth and a year of relative performance, computed once per trading day from data already fetched for the Overview tab (zero new network requests), popup with full reasoning and analyst price target. 838 tests, 0 failures."

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5db1e35 ckpt 616: Finished the TASKS.md checklist for the buy/hold/sell feature - ticked every b
  866376c ckpt 615: Added Robolectric render tests for the actual UI (RecommendationDialog and Det
  876f950 ckpt 614: Full Gradle unit suite verified GREEN: BUILD SUCCESSFUL, 831 tests total acros
  81453dd ckpt 613: Built the per-holding BUY/HOLD/SELL feature end to end (proceeding on Sonnet p
  340d345 ckpt 612: Ticked off the screener TASKS.md boxes — all built, tested (11 new + 37 exis
  543813b ckpt 611: Built the model screener: SCREENER.md (protocol + Opus-escalation criteria), t
  2997e54 ckpt 610: Wrote Tj's request-screener ask into TASKS.md verbatim, with a design note (Us
  13ca548 ckpt 609: Wrote Tj's buy/hold/sell-per-holding request into TASKS.md verbatim with a fea
  a5254d3 ckpt 608: Final verification of the permanent flow. Chased the one RED that ckpt 607 rec
  2101672 ckpt 607: Verified origin/main is fully restored after my fixture contamination: version
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
