# CHECKPOINT 1563 — read me first, then TASKS.md

**Written:** 2026-09-19T16:55:10Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/finish-started-test-vxwq2e` · **builds on:** `99900a2` (this checkpoint is the commit after it)

## Just done
Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD restore behaviour (a truncated backup 'warns' on Replace), which is exactly the data-loss path this session's fix closes. Rewrote it as two tests: Replace is now REFUSED and rolled back, Merge still warns. Also ran BRIEF.md's un-CI'd randomised ledger harness: it exited 1 with 689/5000 violations. Diagnosed to completion - every one was a quantity-less BUY/SELL (a 'ghost row'), which moves cash but builds no position, so it sits outside the reconciliation identity BY CONSTRUCTION. Verified the app already defends this end to end (editor blocks it since v3.5; BOTH import paths refuse it in code and report the count; legacy rows surface on the Settings data-health card as FeeAudit.quantityless) - so the LEDGER is correct and the HARNESS was wrong. A harness that is red on a correct ledger is one nobody reads, and BRIEF.md tells sessions to run it by hand when touching ledger code, so a real regression had 689 false positives to hide in. Carried ghost-row cash as an explicit term and added a coverage guard so the term can never go silently untested. Now 40,000 randomised histories clean across both modes.

## Do this next
Reconcile the four subsystem audit reports as they land, fix everything real, re-run the full suite, then ship per the auto-ship policy.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
  58bc3c8 ckpt 1556: Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.m
  7b20d97 ckpt 1555: Full tests complete: fixed the DayTradingEval.Costs comment tightening (LOW f
  fc23115 ckpt 1554: Full-tests fixes batch 2: (1) HIGH - DayTradingTechnicals.sessionDay/intraday
  889707a ckpt 1553: Full-tests fixes batch 1: (1) Http.postJson now disconnects the socket on can
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
