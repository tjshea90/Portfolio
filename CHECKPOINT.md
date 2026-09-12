# CHECKPOINT 689 — read me first, then TASKS.md

**Written:** 2026-09-12T21:53:14Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `4bb49e9` (this checkpoint is the commit after it)

## Just done
gated v7.20 (code 77) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.20 "Part 10: fixed a real AVERAGE-cost-method same-day P&L bug (a sell drained today's shares in the wrong order, later found to need a three-bucket fix once transactions can also be dated after the session window); closed the ledger property-test harnesses that hid it for an unknown number of rounds; overselling is now reported (Position.oversold) rather than silently mis-booked; added a manual-entry SPLIT transaction type with editor support, guarded out of both Claude-fed import paths since its quantity field is a ratio, not a share count. Part 11: a regression sweep on that diff found and fixed 3 more bugs before they shipped - a stale share count could be adopted as a split ratio on a type switch, the same leak could bake a phantom quantity into a non-trade row's saved data, and the oversold warning was invisible on a stock's own page in exactly the case it matters most (sold down to zero). 1063 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  0b9d76f ckpt 688: Part 11 regression sweep: 2 parallel agents plus my own direct trace found and
  2eef663 ckpt 687: Recorded Part 10 in TASKS.md: what was fixed, how each fix was verified, why o
  c9b335e ckpt 686: Part 10 code review (high effort) over the whole money-accuracy diff found 4 r
  11727e0 ckpt 685: Part 10 (Opus) - overselling and stock splits. Overselling: both replays now r
  10df498 ckpt 684: Part 10 continued: the randomised cross-method property added with the first f
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
