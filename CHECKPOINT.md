# CHECKPOINT 691 — read me first, then TASKS.md

**Written:** 2026-09-14T16:52:29Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-bugs-import-7wrhcc` · **builds on:** `4b98834` (this checkpoint is the commit after it)

## Just done
Fixed Day Trading tab: (1) root-caused the red-text flicker and the missing buy/sell targets to the same bug - mergeDayTradingTech's live 30s loop cleared entry/stop/target the FIRST time tradePlan declined, and a price wobbling near a decision boundary flipped that verdict every tick, so the TradeLevelsGrid/BeginnerSummaryCard (gated on entryPrice>0) loaded, vanished and reappeared, or stayed hidden for picks that sat near the boundary. Fixed with hysteresis (ResearchRow.planDeclineStreak, a decline must repeat on 2 straight ticks before clearing) rather than reverting the Round 73 stale-plan fix. (2) Fixed the Claude file round-trip: every prompt file (Research, Day Trading, Advice, screenshot) only told Claude to reply in chat and asked the USER to manually save that reply as a file - added ClaudeBridge.FILE_DELIVERY_INSTRUCTIONS asking Claude to hand back an actual downloadable file first, with the old manual copy-paste kept only as a fallback. Also corrected a stale in-app help string that still said Claude never sets entry/stop/target (false since the Round 69 reversal). 2 new/rewritten regression tests, full Gradle suite green: 1065 tests, 0 failures.

## Do this next
Awaiting Tj's review/ship decision - no further work queued unless he reports something new or asks to ship v7.21.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  9480abe ckpt 690: v7.20 (code 77) shipped end to end: GitHub Actions run #20 built, signed, veri
  948287b ckpt 689: gated v7.20 (code 77) and pushed it: checkinit, the full unit suite and the ve
  0b9d76f ckpt 688: Part 11 regression sweep: 2 parallel agents plus my own direct trace found and
  2eef663 ckpt 687: Recorded Part 10 in TASKS.md: what was fixed, how each fix was verified, why o
  c9b335e ckpt 686: Part 10 code review (high effort) over the whole money-accuracy diff found 4 r
  11727e0 ckpt 685: Part 10 (Opus) - overselling and stock splits. Overselling: both replays now r
```

(14 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
