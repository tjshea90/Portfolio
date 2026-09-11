# CHECKPOINT 612 — read me first, then TASKS.md

**Written:** 2026-09-11T00:10:00Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/github-request-screener-kawulf` · **builds on:** `c328ea3` (this checkpoint is the commit after it)

## Just done
Ticked off the screener TASKS.md boxes — all built, tested (11 new + 37 existing checks green, no regressions), installed live in this session, and smoke-tested end to end through the real installed hook command. Applied it to the one pending item that matches: confirmed via get_session this session is on claude-sonnet-5, and the queued BUY/HOLD/SELL scoring-design step trips money-accuracy + ambiguous-design + Tj's own 'very important' wording, so it was flagged in chat rather than started.

## Do this next
AWAITING TJ: if he says switch to Opus (or just resends/confirms), pick up the BUY/HOLD/SELL scoring-rule design from TASKS.md under Opus. If he says proceed on Sonnet anyway, honor that and don't re-ask. Nothing on that feature has been touched yet — Research.consensus/ResearchScore are still the plan.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  543813b ckpt 611: Built the model screener: SCREENER.md (protocol + Opus-escalation criteria), t
  2997e54 ckpt 610: Wrote Tj's request-screener ask into TASKS.md verbatim, with a design note (Us
  13ca548 ckpt 609: Wrote Tj's buy/hold/sell-per-holding request into TASKS.md verbatim with a fea
  a5254d3 ckpt 608: Final verification of the permanent flow. Chased the one RED that ckpt 607 rec
  2101672 ckpt 607: Verified origin/main is fully restored after my fixture contamination: version
  47d403b ckpt 606: MY MISTAKE, and its cleanup: the end-to-end interruption simulation contaminat
  a3ea6f2 ckpt 605: gated v7.9 (code 66) and pushed it. NOT yet built - GitHub has not been asked.
  0e3effd ckpt 604: Closed the four gaps in the permanent release flow. (1) CLAUDE.md now describe
  1b38c0e ckpt 603: Wrote Tj's 'this is the flow I want forever' request into TASKS.md and found f
  fc37ee5 ckpt 602: GITHUB NOW BUILDS, SIGNS AND PUBLISHES THE APK, END TO END - run #7 green thro
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
