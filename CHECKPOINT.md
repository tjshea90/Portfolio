# CHECKPOINT 1697 — read me first, then TASKS.md

**Written:** 2026-09-24T18:29:19Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/complete-code-tests-crujka` · **builds on:** `41dc978e` (this checkpoint is the commit after it)

## Just done
gated v7.41 (code 98) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.41 "Everything from the recommendations list, plus the Claude round trip. Prompt files now tell Claude to start at once with no questions, and several shared answer files import together. Charts: volume bars, a labelled previous close, ET times, touch to see a price, double-tap to reset zoom, vs SPY in full screen, no false lines across nights, a 'last updated' note on old charts, pinching no longer changes your saved range. Day Trading: an app vs Claude / setup / time-of-day breakdown on the success card, 1-minute bars settle unclear results, the logged plan shown on each pick, optional alerts when a buy, stop or target is hit. Research: how old each Claude note is, an 'old ratings' mark, other funds with the same exposure (fee + 5Y), swipe between sections. Updated-times keep ticking, Back from a searched stock returns to the results, a warning if transactions vanish, restore from Android's backup, memory freed properly on Android 16, fewer repeat downloads. 1452 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  41dc978e ckpt 1696: All of 09-24b implemented or skipped-with-reason; full suite 1452/0; version
  51baee47 ckpt 1695: Research+Screens batch: Claude age labels, old-ratings mark, ETF alternative
  7608fbed ckpt 1694: Data batch: restored-snapshot recovery, silent-shrink warning (MAX_TXN_COUNT
  17451054 ckpt 1693: Day Trading batch done: 1-minute resolution of unknowable bars, What-worked 
  1d12cca5 ckpt 1692: Charts batch done: C-7, C-9 gap breaks, touch-down value, double-tap reset, 
  67ef6641 ckpt 1691: Part 2 done (start-now line in all prompts, SEND_MULTIPLE, chat-link message
  f3fbea81 ckpt 1690: Recorded Tj's 09-24b request (all recommended changes + Claude app share rou
  12d0989a ckpt 1689: v7.40 shipped: run #42 green, Release published, BUILDLOG recorded; full-tes
  dc9ffd11 ckpt 1688: gated v7.40 (code 97) and pushed it: checkinit, the full unit suite and the 
  4e28011d ckpt 1687: Review findings R1-1..9, R2-1..7 all fixed + R1-2b (or5 carried) + R2-1 exte
```
