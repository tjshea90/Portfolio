# CHECKPOINT 583 — read me first, then TASKS.md

**Written:** 2026-09-10T20:10:31Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `b51da53` (this checkpoint is the commit after it)

## Just done
Independent re-verification pass of the whole resume system, adversarial rather than re-running my own tests. 15 probes against throwaway fixtures: corrupt/truncated settings.json (rebuilt + backed up); no python3 with an existing settings file (REFUSES to clobber, exit 1) and without one (plain install, placeholders substituted, briefing still delivered as plain text); a stale git lock (cleared) vs a fresh one from a live git process (correctly left alone); CHECKPOINT.md deleted (recreated, number recovered from git log); a repo with zero commits (works); a live credential alongside real work through the actual hook path (blocked, unstaged, ONE valid JSON warning, secret left on disk for the human - and confirmed the documented trade-off that it blocks ALL autosaving until removed, then everything commits immediately once it is gone); push failure (loud, and the work is still committed locally so it is recoverable); dirty tree at session start (warned with file list); a genuinely diverged branch (warned, NOT silently merged); the full usage-cap scenario (ckpt, then two autosaves, then death: the next session gets the INTERRUPTED MID-CHANGE banner, the exact git diff range, the changed-file list, and an intact 'Do this next'); non-git dirs and git dirs without tools/ under the container root (correctly skipped); and a fresh depth-20 clone of the DEFAULT branch from GitHub, which carries ckpt 582, all the new files, and passes its own self-test in the clone. All 14 self-test checks green. No defects found and no files changed by this pass.

## Do this next
Nothing outstanding. One optional cleanup Tj should decide on: TASKS.md still holds the finished review and costs ~1070 tokens of context on every single turn of every future session (briefing is 10.3KB now vs 6.1KB with TASKS.md reset). Reset it to 'no active job' when he has read the findings.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
  a36dba0 ckpt 581: Fixed the four highest-severity resume defects and made the system self-verify
  cbed3ee ckpt 54: Reviewed the whole resume/checkpoint system end-to-end and confirmed 6 real def
  eb593ff ckpt 580: Completeness re-check of the resume system in a genuinely fresh session (new c
  c6b6dbf ckpt 576: Fixed a gap in the handoff itself: CHECKPOINT.md's own pointer told a resuming
  9b2c169 ckpt 574: CRITICAL FIX, live-verified: found and fixed the root cause of why autosave ne
  4e814ae ckpt 569: Added auto-merge-from-main to resume.sh (when origin/main has newer commits th
  a30bb1f ckpt 568: Second efficiency/robustness pass on the resume logic: added a stale-git-lock 
  f2b8bc6 ckpt 566: Audited the resume/checkpoint system for Claude-Code (not Cowork) fitness and 
  424ad4b ckpt 565: Sent the signing keystore (app/sideload.jks) to Tj directly since it can't be 
```
