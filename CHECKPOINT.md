# CHECKPOINT 1599 — read me first, then TASKS.md

**Written:** 2026-09-22T19:06:43Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `eabf90b` (this checkpoint is the commit after it)

## Just done
gated v7.34 (code 91) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.34 "Full-tests audit (5 parallel subsystem audits) and fix pass: ~45 findings fixed. HIGH: position editor's untouched Save no longer pins shares/cost against later trades; same-day trades imported newest-first no longer replay backwards (phantom shares/oversold) - repaired for rows on file too; day-trading plans now use the live price, not the screener-build price; the 'Your portfolio, trading this system' figure now applies the 25% position cap (it overstated several-fold); analyst ratings that are all over 8 months old no longer fall back to 45-90% undated weight. Plus NYSE holiday/half-day calendar, partial fills kept on import, split-before-trades on the same date, saveable navigation (scroll/tab/editor survive back and process death), network waste fixes (crumb-cooling storm, 429s disabling the batch, after-close sparklines and day-trading sweeps, Reddit polling off-tab), pre-delete snapshots, net-of-costs profitable rate, and more. 1292 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  a95bcbb ckpt 1598: All ~45 audit findings fixed and verified: suite 1292/0. Review found + fixed
  d9b8453 ckpt 1597: Every audit finding now written. LOWs done: D-L8, D-L10, S-L5, S-L6, S-L7 (su
  57d9cc1 ckpt 1596: Suite 1286/0: A-L9/A-L10/A-L11, D-L5/D-L6/D-L7 done. Wrote D-L8 (throttled re
  61de483 ckpt 1595: All MEDIUMs done (suite 1281/0). LOWs: A-L6 done; A-L9 (pre-destructive snaps
  14c0bdb ckpt 1594: N-M3 done (suite green). Wrote N-M4 (post-close live loop at 5min), U-M1/U-M2
  05a7ab9 ckpt 1593: Suite 1277/0: S-M4 (reasons toggle + red flag first), N-M1 (blank crumb while
  dc799f8 ckpt 1592: Suite green 1272/0 incl D-H2, S-H1, D-M3, D-M4(+D-L9 NYSE rules calendar). Wr
  4561126 ckpt 1591: Floor green (1261/0). A-H2/A-M3/A-M4/A-L7/D-H1 ticked. Wrote D-H2 (account fi
  c56eca8 ckpt 1590: Wrote A-H2/A-M3/A-M4/A-L7: Ledger.replayOrder (split-first on date ties + ove
  a071192 ckpt 1589: Resumed full-tests fix pass. Finished A-H1 (EditPositionDialog only overrides
```

(4 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
