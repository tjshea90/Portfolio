# CHECKPOINT 1768 — read me first, then TASKS.md

**Written:** 2026-11-01T02:48:54Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `ccr-ab1c4af9-vyzicn` · **builds on:** `90db2ee1` (this checkpoint is the commit after it)

## Just done
offline refresh survives backgrounding: dataSync foreground service + fgScope kept until pass ends; v8.8 bumped; full suite green (sdk36)

## Do this next
trigger android.yml full_build main for v8.8, record-release.sh v8.8

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  9ed0d423 ckpt 1767: captured background refresh request
  f7480db9 ckpt 1766: v8.7 released and recorded
  90385a30 ckpt 1765: Markets: US index charts (inline, fullscreen, pinch), watch+index charts fet
  605fdc42 ckpt 1764: captured request M1-M4
  a8170c81 ckpt 1763: v8.6 released and recorded (Markets tab)
  fd7e0b23 ckpt 1762: v8.5 recorded; bumped to v8.6 (Markets tab)
  89b93ad6 ckpt 1761: Markets sub-tab (Portfolio tab): 14 indices, offline-saved, IndicesTest
  b7c638d5 ckpt 1760: captured indices request
  b5cf7705 ckpt 1759: triggered CI run 41229396446 (full_build) for v8.5 on main
  fe3a561d ckpt 1758: RecentReportsTest uses sdk 36 (only android-all cached locally)
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
