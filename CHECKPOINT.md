# CHECKPOINT 1635 — read me first, then TASKS.md

**Written:** 2026-09-23T15:21:14Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/work-scheduling-capability-6jeiez` · **builds on:** `e5eccf1` (this checkpoint is the commit after it)

## Just done
gated v7.37 (code 94) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.37 "Share flow + full-test audit: every 'Make prompt file' opens the share sheet (pick Claude); Claude's answer file shared to Portfolio imports itself and opens the right screen; Day Trading buttons moved to the top and its long paragraph removed. Full test: 5 parallel audits plus a diff review, ~60 findings fixed - Claude answers no longer wiped by a rebuild, uninstall-proof autosave never shrinks without keeping the larger copy, snapshot imports no longer show their whole gain as today's, news matching no longer tags AT&T/S&P Global on every headline, old Day Trading answers can't become today's plan, halved duplicate chart downloads, no double-billed Claude retries, and more."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  8f0449a ckpt 1634: Diff review R-1..R-11 fixed (R-2 documented tradeoff); suite 1325/0
  2c6d558 ckpt 1633: Diff review landed: R-1..R-11 listed in TASKS
  10ad7d1 ckpt 1632: All audit findings fixed (A-12 documented); suite 1324/0
  d6ae2ee ckpt 1631: N-1 (RecentBodies shared chart body; sweep publishes D1 from it) and N-2 (Cla
  effef6a ckpt 1630: D-6, D-10, U-2, U-6 fixed; all S/A/D/U findings done (A-12 documented no-chan
  3b00834 ckpt 1629: D-4 (whole DT list to Claude), D-5 (asOf check +test), D-8 (evening import ->
  b6e3aac ckpt 1628: D-2, D-3, D-7, D-9 fixed (+test)
  8c2915a ckpt 1627: S-2 (provisional verdict until core), S-7 (coreAt), S-10 (one exposure line),
  38f0490 ckpt 1626: S-4,S-5,S-6,S-8,S-9 fixed (+tests)
  3548dfe ckpt 1625: A-4 (undo snapshot pick/gate/prune), A-7 (wipe clears overrides) fixed; A-12 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
