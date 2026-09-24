# CHECKPOINT 1688 — read me first, then TASKS.md

**Written:** 2026-09-24T17:18:49Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/complete-code-tests-crujka` · **builds on:** `4e28011d` (this checkpoint is the commit after it)

## Just done
gated v7.40 (code 97) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.40 "Full test of the whole app (7 audits + 2 independent reviews, ~100 findings fixed): Day Trading plans no longer flicker or get logged from stale pre-market data, evening/weekend Claude plans survive to the next session, Claude's added Research picks show on the page, cancelling/leaving mid-refresh no longer strands spinners or drops refreshes, safer backups/restores and duplicate checks, chart zoom no longer blanks and SPY comparisons line up, sub-cent moves show the right sign and colour, fewer wasted network requests."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  4e28011d ckpt 1687: Review findings R1-1..9, R2-1..7 all fixed + R1-2b (or5 carried) + R2-1 exte
  5db3a9ae ckpt 1686: Both independent reviews complete: 16 findings (R1-1..9, R2-1..7) recorded
  a620881c ckpt 1685: Independent review launched (2 agents -> review-hm.md, review-lq.md)
  60a20536 ckpt 1684: Full suite after Ls+Qs: 1411/0, checkinit ok
  99ba0189 ckpt 1683: Q items: 17 fixed (C-Q3/5/7, L-Q2, N-Q1..5, A-Q3, S-Q1/3, U-Q1..4), rest rec
  b4013ea0 ckpt 1682: U-4..U-8 fixed (+2 tests); all L findings done
  5c5835ab ckpt 1681: S-3, S-9..S-12 fixed (+5 tests)
```
