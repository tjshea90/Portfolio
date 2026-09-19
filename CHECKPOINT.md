# CHECKPOINT 1556 — read me first, then TASKS.md

**Written:** 2026-09-19T03:49:51Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-3ajf43` · **builds on:** `b8adde3` (this checkpoint is the commit after it)

## Just done
Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.md's Releasing section: from now on, ship every future meaningful unit of work automatically (bump version, ship.sh, trigger build, confirm green, record-release.sh) and post the Release link in chat without being asked - not a change to the existing 'never send raw APK bytes' rule, just no longer waiting for Tj to say 'ship it' first.

## Do this next
Bump versionCode/versionName in app/build.gradle.kts for the full-tests fix session, run ship.sh, trigger the GitHub Actions build, confirm green, record-release.sh, then post the release link.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  7b20d97 ckpt 1555: Full tests complete: fixed the DayTradingEval.Costs comment tightening (LOW f
  fc23115 ckpt 1554: Full-tests fixes batch 2: (1) HIGH - DayTradingTechnicals.sessionDay/intraday
  889707a ckpt 1553: Full-tests fixes batch 1: (1) Http.postJson now disconnects the socket on can
  f90d691 ckpt 1552: Full-tests: floor checks green (checkinit + full Gradle unit suite, BUILD SUC
  a22f2c0 ckpt 1551: Made 'light tests' and 'full tests' permanent knowledge: added a 'Testing on 
  f69cc64 ckpt 1550: Permanently removed the Opus screener: deleted tools/screener.sh, tools/hooks
  2175dcc ckpt 1549: Shipped v7.26 (code 83): analyst-rating recency scoring, day-trading tracker 
  188cc8a ckpt 1548: v7.26 (code 83) gated and pushed; GitHub build run 35375660271 triggered and 
  82a0e39 ckpt 1547: gated v7.26 (code 83) and pushed it: checkinit, the full unit suite and the v
  67ca002 ckpt 1546: Recency scoring + day-trading cost/cumulative stats + retention-gate bug fix 
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
