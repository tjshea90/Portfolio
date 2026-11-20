# CHECKPOINT 1774 — read me first, then TASKS.md

**Written:** 2026-11-20T19:58:46Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `ccr-3afdf19c-9h8n14` · **builds on:** `80b28c49` (this checkpoint is the commit after it)

## Just done
gated v8.9 (code 108) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v8.9 "v8.9: new 'Prices' refresh level (holdings + watch list prices and charts only); refresh/pull on a stock page now refreshes only that stock"
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  80b28c49 ckpt 1773: Full suite green (1650 tests); TASKS R1-R3 ticked; versionCode 108 / v8.9
  3ab4f861 ckpt 1772: R1+R2 implemented: OfflineSync.Mode.PRICES (holdings+watch prices/charts onl
  e9c417ff ckpt 1771: Recorded Tj's 2026-11-20 request (Prices-only refresh level + stock-page ref
  209cecef ckpt 1770: handoff saved (.handoff/, CLAUDE.md/AGENTS.md learnings)
  bbf0dcbf ckpt 1769: v8.8 released and recorded
  87d5ebab ckpt 1768: offline refresh survives backgrounding: dataSync foreground service + fgScop
  9ed0d423 ckpt 1767: captured background refresh request
  f7480db9 ckpt 1766: v8.7 released and recorded
  90385a30 ckpt 1765: Markets: US index charts (inline, fullscreen, pinch), watch+index charts fet
  605fdc42 ckpt 1764: captured request M1-M4
```
