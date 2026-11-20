# CHECKPOINT 1772 — read me first, then TASKS.md

**Written:** 2026-11-20T19:54:16Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `ccr-3afdf19c-9h8n14` · **builds on:** `b40e4c43` (this checkpoint is the commit after it)

## Just done
R1+R2 implemented: OfflineSync.Mode.PRICES (holdings+watch prices/charts only), 3-way RefreshLevelPicker (strip+Settings), Keys.REFRESH_MODE w/ legacy migration, vm.refreshStock() used by DetailScreen button+pull, applyFetchedQuotes extracted; RefreshLevelTest added (all green in isolation)

## Do this next
run full gradle unit suite; light-test diff review (callers of changed fns); bump versionCode/Name; ship.sh; trigger android.yml; record-release; post Release link

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  e9c417ff ckpt 1771: Recorded Tj's 2026-11-20 request (Prices-only refresh level + stock-page ref
  209cecef ckpt 1770: handoff saved (.handoff/, CLAUDE.md/AGENTS.md learnings)
  bbf0dcbf ckpt 1769: v8.8 released and recorded
  87d5ebab ckpt 1768: offline refresh survives backgrounding: dataSync foreground service + fgScop
  9ed0d423 ckpt 1767: captured background refresh request
  f7480db9 ckpt 1766: v8.7 released and recorded
  90385a30 ckpt 1765: Markets: US index charts (inline, fullscreen, pinch), watch+index charts fet
  605fdc42 ckpt 1764: captured request M1-M4
  a8170c81 ckpt 1763: v8.6 released and recorded (Markets tab)
  fd7e0b23 ckpt 1762: v8.5 recorded; bumped to v8.6 (Markets tab)
```

(27 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
