# CHECKPOINT 1757 — read me first, then TASKS.md

**Written:** 2026-10-31T18:24:36Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `ccr-ab1c4af9-vyzicn` · **builds on:** `b05bec15` (this checkpoint is the commit after it)

## Just done
page saver: retry UAs, softBlock, JSON-LD/meta fallback, card never null; Earnings 'Reported in the last month' with EPS beat/miss, reaction, offline-persisted; v8.5 bumped

## Do this next
ship.sh v8.5 (Robolectric tests cannot run in this sandbox - Maven blocked; CI runs them), trigger android.yml, record-release

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  60fb415c ckpt 1756: captured request in TASKS.md
  5fdf58dc ckpt 1755: context handoff saved (.handoff/, CLAUDE.md learnings block)
  c99eb5e5 ckpt 1754: v8.4 released and recorded (saved articles fix)
  eab0c01b ckpt 1753: gated v8.4 (code 103) and pushed it: checkinit, the full unit suite and the 
  179c8a75 ckpt 1752: pre-ship: v8.4: saved articles - Google News links resolved to the publisher
  52a33367 ckpt 1751: W1-W2 coded: GoogleNewsLink resolver, summary-card fallback, saved markers, 
  1c0c3509 ckpt 1750: captured saved-website request
  7a464da5 ckpt 1749: gated v8.3 (code 102) and pushed it: checkinit, the full unit suite and the 
  8a6945db ckpt 1748: v8.3 prepared: version bump, BRIEF row, tests (OfflineCacheTest 20, Research
  207e8fae ckpt 1747: offline sync: budget, page saver, db v11, OfflineSync orchestrator, VM wirin
```

(7 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
