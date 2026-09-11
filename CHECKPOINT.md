# CHECKPOINT 611 — read me first, then TASKS.md

**Written:** 2026-09-11T00:08:42Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/github-request-screener-kawulf` · **builds on:** `aa79423` (this checkpoint is the commit after it)

## Just done
Built the model screener: SCREENER.md (protocol + Opus-escalation criteria), tools/screener.sh (UserPromptSubmit hook script, JSON/--text dual mode, keyword hint), tools/hooks/screen.sh + lib.sh/emit.py support for the new event (mirrors brief.sh/save.sh/big.sh exactly), wired into both tools/session-root-hooks.json and .claude/settings.json, tools/test_screener.sh (11 hermetic checks, all green), CLAUDE.md points at it. test_resume.sh, checkinit.py, JSON sanity all still green — no regressions.

## Do this next
Run install-hooks.sh to activate it in this live session, smoke-test it end to end through the real installed command, then flag the pending BUY/HOLD/SELL scoring-design step in TASKS.md against the new criteria (money-accuracy + ambiguous design) before resuming that work.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  2997e54 ckpt 610: Wrote Tj's request-screener ask into TASKS.md verbatim, with a design note (Us
  13ca548 ckpt 609: Wrote Tj's buy/hold/sell-per-holding request into TASKS.md verbatim with a fea
  a5254d3 ckpt 608: Final verification of the permanent flow. Chased the one RED that ckpt 607 rec
  2101672 ckpt 607: Verified origin/main is fully restored after my fixture contamination: version
  47d403b ckpt 606: MY MISTAKE, and its cleanup: the end-to-end interruption simulation contaminat
  a3ea6f2 ckpt 605: gated v7.9 (code 66) and pushed it. NOT yet built - GitHub has not been asked.
  0e3effd ckpt 604: Closed the four gaps in the permanent release flow. (1) CLAUDE.md now describe
  1b38c0e ckpt 603: Wrote Tj's 'this is the flow I want forever' request into TASKS.md and found f
  fc37ee5 ckpt 602: GITHUB NOW BUILDS, SIGNS AND PUBLISHES THE APK, END TO END - run #7 green thro
  22103f3 ckpt 601: Fixed a false positive my own change caused in test_resume.sh: the 'CI commits
```

(14 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
