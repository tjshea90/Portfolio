# CHECKPOINT 1561 — read me first, then TASKS.md

**Written:** 2026-09-19T16:16:56Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/test-latest-app-version-4a21fb` · **builds on:** `49cfc6c` (this checkpoint is the commit after it)

## Just done
Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 failures / 0 skipped. Own audit found and fixed a day-boundary bug class in Explain.kt: daysFromNow and the earnings countdown used plain Long division, which truncates TOWARD ZERO, so a date 1-23h in the PAST divided to 0 and every 'is it still ahead?' test written as d >= 0 stayed true for the whole day after the date passed - an already-missed ex-dividend cut-off read 'essentially now' (act on it and you buy without getting the dividend), a dividend paid yesterday read 'still due', and a report released last night still promised a coming price move. Research.daysUntilEarnings hit the identical trap and fixed it with Math.floorDiv; applied the same fix to all three Explain sites plus the now-reachable copy edges (1 day vs 1 days, 'today'/'tomorrow' instead of 'in 0 days'). New ExplainDayBoundaryTest pins all three, 10 tests green.

## Do this next
Reconcile the four parallel subsystem audit reports as they come in, fix everything real, re-run the full suite, then ship per the auto-ship policy.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
  58bc3c8 ckpt 1556: Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.m
  7b20d97 ckpt 1555: Full tests complete: fixed the DayTradingEval.Costs comment tightening (LOW f
  fc23115 ckpt 1554: Full-tests fixes batch 2: (1) HIGH - DayTradingTechnicals.sessionDay/intraday
  889707a ckpt 1553: Full-tests fixes batch 1: (1) Http.postJson now disconnects the socket on can
  f90d691 ckpt 1552: Full-tests: floor checks green (checkinit + full Gradle unit suite, BUILD SUC
  a22f2c0 ckpt 1551: Made 'light tests' and 'full tests' permanent knowledge: added a 'Testing on 
```

(5 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
