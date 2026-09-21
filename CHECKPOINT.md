# CHECKPOINT 1572 — read me first, then TASKS.md

**Written:** 2026-09-21T03:39:40Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-test-26vbpc` · **builds on:** `1472b99` (this checkpoint is the commit after it)

## Just done
gated v7.29 (code 86) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.29 "Full-tests audit and fix pass: 4 parallel subsystem audits over the whole app (scoring, day-trading, network/caching, UI/battery/persistence). Fixed a HIGH bug where WSB/social Trending data silently froze after the app's first feed pass and never updated again (the cadence stamp was re-armed on every pass instead of only on an actual fetch attempt). Fixed a MEDIUM scoring bug where the analyst-consensus label could read 'Sell' on a panel where buy votes actually outnumbered sell votes (e.g. 10 buy / 7 sell), printed straight into a buy-screener card's own reasoning text. Fixed a MEDIUM data-loss gap where a Claude screenshot import's review dialog - a real, billed API call - was held only in memory and silently vanished if Android killed the app while it was open; it now survives via a persisted pending-import record. Fixed a MEDIUM restore bug where Merge could silently overwrite a manual cost-basis override already on the device with an older backup's value, contradicting the restore dialog's own 'Merge never removes what you have' promise. Plus three LOW fixes: a missing dismissOnClickOutside guard on the Restore-from-JSON dialog (now consistent with every other data-entry dialog), a stale BRIEF.md doc line on the news-source order, and a day-trading-log restore now warning on a truncated file the same way the transactions path already does. Also repaired bit-rot in the un-CI'd tests/LedgerPropTest.java harness, which had stopped compiling against a signature change. 1207 tests, 0 failures, 3 new regression tests."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  204a04b ckpt 1571: Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH 
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
  934d899 ckpt 1568: gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the v
  9b9c71c ckpt 1567: Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, che
  10b3209 ckpt 1566: Network/UI/day-trading audit fixes, all verified in code first. NETWORK: day-
  a110aa1 ckpt 1565: HIGH (found independently by TWO audits): the day-trading recommendation log 
  8f0e38d ckpt 1564: Scoring-audit fixes in (all verified against the code first, not taken on tru
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
