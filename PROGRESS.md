# ROUND 57 — LIVE PROGRESS LOG

This file is appended to by `checkpoint.sh` on every save, and edited by hand as work lands.
**If a session was cut off, read this file first — it says exactly where the work stopped.**

## The request (Round 57)

> do another sweep of the app for improvements and bug fixes and efficiency of functions and
> code. make sure when I'm not using the app it isn't using too much in the background. make
> sure data that won't change is cached and not reloaded from the internet. the app can use as
> much cache and storage as it needs. it can also use as much mobile data as it needs, but not
> wasteful

## Cold-resume instruction (what to say in a new chat)

> Extract this archive and read `portfolio/PROGRESS.md` then `portfolio/CHECKPOINT.md`.
> Continue from the first unchecked item.

## Task ledger

- [x] T0  Background audit: every coroutine, timer, listener and lifecycle path traced.
          Found 42 `viewModelScope.launch` sites of which only 3 stopped on background
- [x] T1  `fgScope` — a supervisor scope cancelled on ON_STOP and rebuilt on ON_START.
          Eleven on-demand fetch paths moved onto it
- [x] T2  `Http.get` disconnects the socket on cancellation, so cancelling actually stops
          the transfer instead of only stopping what was still queued
- [x] T3  Process-wide memory trims (`MemoryTrim.installProcessWide`) — the Activity-only
          callbacks were never delivered once the Activity was destroyed
- [x] T4  WebView handles ON_PAUSE / ON_RESUME as well as ON_STOP / ON_START
- [x] T5  Immutable-data caching: never-parseable SEC filings recorded permanently
          (`Keys.INSIDER_SKIP`); conditional GETs on all four fundamentals providers with a
          crumb-independent cache key; inverse-ETF mapping carried across research rebuilds;
          symbol-search memo
- [x] T6  Ratings/consensus/Finviz/Nasdaq now answer 304 instead of re-downloading ~195 KB
- [x] T7  Efficiency: `Relevance.Subject` hoists ~200k regex compilations out of the research
          loop; `parseUsDate` and two `ShortVehicle` regexes hoisted; `cachedQuotes` column
          indices; `IFNULL(symbol,'')` index fix; research cache and advice cache off the
          main thread
- [x] T8  Adversarial review of the round (10 findings) — all fixed
- [x] T9  Tests: `BackgroundTest` (7 new). Suite 276/276 green
- [x] T10 Full verification and ship v6.8 (versionCode 55) + checkpoint 57

## What landed

**New file**

```
app/src/test/java/com/tj/portfolio/BackgroundTest.kt
```

**Changed**

- `ui/PortfolioViewModel.kt` — `fgScope`, `newForegroundScope`, eleven launch sites rewired,
  `insiderSkip` + `saveInsiderSkip`, `carryExplanations` carries the short vehicle,
  `loadCachedResearch` / `loadCachedAdvice` / `cacheResearch` off the main thread,
  `searchSymbols` finally-block, `refreshSparklines` un-marks on cancellation,
  `loadInsider` guard moved inside the launch, `enrichVisible` persists in its finally
- `net/Http.kt` — socket disconnect on cancellation via an `AtomicReference`, a
  `CountingStream` so a truncated body can never be cached, cancellation no longer counted as
  an unreachable host, `cacheAs` cache-key override
- `net/Insider.kt` — `Unreadable` verdict + `skip` set so unparseable filings are never
  re-downloaded
- `net/Relevance.kt` — `Subject` / `squashed`, `WORD_SPLIT` compiled once
- `net/Research.kt` — the hot loop uses both
- `net/FundamentalsFeed.kt` — four conditional GETs, `US_DATE_FORMATS` ThreadLocal
- `net/ShortVehicle.kt` — two regexes replaced with a hand-rolled scan / a hoisted pattern
- `net/SymbolSearch.kt` — bounded result memo, cleared on memory trim
- `data/Db.kt` — `Keys.INSIDER_SKIP`, index-usable duplicate queries, `cachedQuotes` indices
- `util/MemoryTrim.kt` — `installProcessWide`
- `MainActivity.kt` — registers it; Activity trim overrides removed
- `ui/ReaderScreen.kt` — ON_PAUSE / ON_RESUME handling
- `ui/MetricUi.kt`, `ui/Explain.kt`, `ui/DetailScreen.kt` — recomposition fixes
- `app/build.gradle.kts` — versionCode 55, versionName 6.8

## What now happens when the app is not in use

| Mechanism | Before | After |
|---|---|---|
| Poll loop, feed pass, EDGAR pass | cancelled | cancelled |
| Stock detail loads (news, fundamentals, ~195 KB ratings, filings) | ran to completion | cancelled |
| Research build (~18 requests) and enrich (up to 6 passes of per-row lookups) | ran to completion; `enrichJob` had no cancellation path at all | cancelled |
| Sparklines (up to 20 chart requests) | ran to completion — a sibling of `refresh`, so it outlived even its own caller | cancelled |
| Advice news preload (one fetch per holding) | ran to completion | cancelled |
| In-flight sockets | kept transferring to completion or a 15s timeout | disconnected |
| WebView | paused on ON_STOP only | paused on ON_PAUSE too |
| Memory trims | not delivered once the Activity was destroyed | delivered for the life of the process |
| Writes, backups, Claude requests | completed | completed (deliberately unchanged) |

## Review findings from T8, all fixed

1. `Http` could cache a body truncated by its own cancellation, under a valid ETag — then
   serve it as a fresh 200 forever via the 304 path, with `touch` renewing the retention
2. Cancellation was counted as `noteUnreachable`, arming a 5-minute cooldown on a healthy
   host every time the user switched away mid-refresh
3. `searchSymbols` had no `finally` — `_searching` stuck true across a background cycle
4. `refreshSparklines` stamped `sparkAt` outside the launch with an unreachable un-mark —
   five minutes of blank charts after backgrounding
5. Insider skips were not persisted in exactly the case they exist for (`filings.isEmpty()`)
6. `conn` was captured in a non-volatile `Ref.ObjectRef`; the disconnect could silently not
   happen
7. `loadInsider`'s guard was outside the launch — an unrecoverable per-symbol lockout
8. `cacheResearch` was outside the `finally` in `enrichVisible`
9. `MetricUi`'s bucket `0L` collided between "no price" and $1.000–$1.002
10. `ReaderScreen` never called `onResume()` on ON_START — a visible-but-frozen WebView in
    split-screen

## Log

2026-09-05  checkpoint 57  Round 57 complete: background work stopped, immutable data cached,
            hot paths hoisted. Shipped as v6.8 (versionCode 55).
2026-09-07 20:00:12 UTC  checkpoint 58  commit e10a04f  T0 done
2026-09-07 20:00:33 UTC  checkpoint 58  commit 8a6c35e  T1 doing
2026-09-07 20:00:33 UTC  checkpoint 58  commit ee991e8  T2 doing
2026-09-07 20:08:21 UTC  checkpoint 58  commit 8ffea21  T1 done
2026-09-07 20:08:21 UTC  checkpoint 58  commit f613d79  T2 done
2026-09-07 20:08:21 UTC  checkpoint 58  commit 743ec24  T3 doing
2026-09-07 20:13:54 UTC  checkpoint 58  commit 642f7e7  fix F01
2026-09-07 20:13:54 UTC  checkpoint 58  commit 120d214  fix F02
2026-09-07 20:15:05 UTC  checkpoint 58  commit 73660ce  fix F03
2026-09-07 20:15:06 UTC  checkpoint 58  commit 7e8dc09  fix F04
2026-09-07 20:15:06 UTC  checkpoint 58  commit b50afdd  fix F05
2026-09-07 20:20:22 UTC  checkpoint 58  commit 7c2a7b4  T3 done
2026-09-07 20:20:22 UTC  checkpoint 58  commit 93f0275  T4 done
2026-09-07 20:20:23 UTC  checkpoint 58  commit ae83b49  T5 done
2026-09-07 20:20:23 UTC  checkpoint 58  commit 131fe02  T6 done
2026-09-07 20:20:23 UTC  checkpoint 58  commit e06f166  T7 done
2026-09-07 20:20:24 UTC  checkpoint 58  commit f4fdbd4  T8 doing
2026-09-07 20:25:27 UTC  checkpoint 58  commit 48bec4c  T8 done
2026-09-07 20:25:27 UTC  checkpoint 58  commit 17a6779  T9 doing
2026-09-07 20:28:21 UTC  checkpoint 58  commit 58fc2b2  fix F07
2026-09-07 20:36:05 UTC  checkpoint 58  commit 59cc31e  T9 done
2026-09-07 20:36:05 UTC  checkpoint 58  commit fd9edd4  fix F06
2026-09-07 20:36:06 UTC  checkpoint 58  commit 81d28f8  chart ranges, chart caching, ETF holdings, list separation, after-hours stacking; 36 new tests
2026-09-07 20:36:43 UTC  checkpoint 58  commit 0ac017c  T10 doing
2026-09-07 20:41:20 UTC  checkpoint 58  commit 71c3b78  fix F14
2026-09-07 20:44:12 UTC  checkpoint 58  commit 53ac095  fix F08
2026-09-07 20:44:12 UTC  checkpoint 58  commit 6293db4  fix F09
2026-09-07 20:44:12 UTC  checkpoint 58  commit 8efaa51  fix F10
2026-09-07 20:44:13 UTC  checkpoint 58  commit 2f7460a  fix F11
2026-09-07 20:44:13 UTC  checkpoint 58  commit 45ea428  fix F12
2026-09-07 20:44:14 UTC  checkpoint 58  commit 0eb3cdd  fix F13
2026-09-07 20:44:14 UTC  checkpoint 58  commit d69ff01  fix F15
2026-09-07 20:44:14 UTC  checkpoint 58  commit 71ce312  fix F16
2026-09-07 20:44:15 UTC  checkpoint 58  commit 208fceb  sweep pass 1 - nine findings on my own round-58 changes, all fixed
2026-09-07 20:44:22 UTC  checkpoint 58  commit da1804a  T10 doing
2026-09-07 20:48:01 UTC  checkpoint 58  commit 4296b9c  fix F17
2026-09-07 20:48:01 UTC  checkpoint 58  commit 353899d  fix F18
2026-09-07 20:48:01 UTC  checkpoint 58  commit e4dfb9c  fix F19
2026-09-07 20:48:02 UTC  checkpoint 58  commit 0f7a4b3  fix F20
2026-09-07 20:48:02 UTC  checkpoint 58  commit 209acd9  sweep pass 2 fixes
2026-09-07 20:55:00 UTC  checkpoint 58  commit d918ad0  fix F21
2026-09-07 20:56:56 UTC  checkpoint 58  no-change  sweep pass 2 complete; 15 rendered UI tests for the chart and holdings
2026-09-07 20:57:07 UTC  checkpoint 58  commit c617809  T10 doing
2026-09-07 21:02:47 UTC  checkpoint 58  commit 01a83d3  fix F22
2026-09-07 21:02:47 UTC  checkpoint 58  commit 021fad8  market-aware chart refresh gate
2026-09-08 00:47:25 UTC  checkpoint 58  commit af54081  T10 done
2026-09-08 00:47:25 UTC  checkpoint 58  commit 7b4dda0  T11 done
2026-09-08 00:49:43 UTC  checkpoint 58  commit 82e62f1  T12 done
2026-09-08 00:49:44 UTC  checkpoint 58  commit 7b83fad  T13 doing
2026-09-08 00:49:44 UTC  checkpoint 58  commit 8269bbc  v6.9 shipped: 358/358 green, lint clean, APK signed and verified
2026-09-08 00:49:54 UTC  checkpoint 58  commit db6995f  T13 done
2026-09-08 00:49:55 UTC  checkpoint 58  commit 5539c3d  round 58 complete
2026-09-08 00:50:15 UTC  checkpoint 58  commit 843a49d  round 58 closed; state reset for the next round
2026-09-08 01:02:18 UTC  checkpoint 59  commit 23cca2d  T0 done
2026-09-08 01:02:18 UTC  checkpoint 59  commit 0f25414  T1 doing
2026-09-08 01:04:48 UTC  checkpoint 59  commit 373356e  T1 done
2026-09-08 01:04:49 UTC  checkpoint 59  commit 36ea0b9  T2 doing
2026-09-08 01:05:42 UTC  checkpoint 59  commit fb6ac7e  T2 done
2026-09-08 01:05:42 UTC  checkpoint 59  commit 170e015  T3 doing
2026-09-08 01:06:15 UTC  checkpoint 59  commit 0c0d864  T3 done
2026-09-08 01:06:16 UTC  checkpoint 59  commit ff415fc  T4 done
2026-09-08 01:06:16 UTC  checkpoint 59  commit ca481ea  T5 doing
2026-09-08 01:15:06 UTC  checkpoint 59  commit 87ac153  fix G01
2026-09-08 01:15:07 UTC  checkpoint 59  commit 6f0716a  fix G02
2026-09-08 01:15:07 UTC  checkpoint 59  commit 3e44677  fix G03
2026-09-08 01:15:08 UTC  checkpoint 59  commit 5b3d35a  fix G04
2026-09-08 01:15:08 UTC  checkpoint 59  commit 2efa4fd  fix G05
2026-09-08 01:15:08 UTC  checkpoint 59  commit ff3b060  fix G06
2026-09-08 01:15:09 UTC  checkpoint 59  commit 090c814  fix G07
2026-09-08 01:15:09 UTC  checkpoint 59  commit 307f62c  G01-G07 fixed
2026-09-08 01:17:13 UTC  checkpoint 59  commit f55ed3a  T5 done
2026-09-08 01:17:13 UTC  checkpoint 59  commit e5f1522  T6 doing
2026-09-08 01:27:41 UTC  checkpoint 59  commit 83b17c0  T6 done
2026-09-08 01:27:42 UTC  checkpoint 59  commit fe79fb5  T7 doing
2026-09-08 01:27:42 UTC  checkpoint 59  commit da7a2a7  v7.0: 10 findings fixed, 371/371 green, lint clean
2026-09-08 01:27:54 UTC  checkpoint 59  commit e827def  T7 done
2026-09-08 01:27:54 UTC  checkpoint 59  commit ea93a0a  round 59 closed
2026-09-08 01:49:36 UTC  checkpoint 60  commit 224e0f8  T0 done
2026-09-08 01:49:37 UTC  checkpoint 60  commit 1793e58  T1 done
2026-09-08 01:49:37 UTC  checkpoint 60  commit 3af7f4c  T2 done
2026-09-08 01:49:38 UTC  checkpoint 60  commit 06844bf  T3 done
2026-09-08 01:49:38 UTC  checkpoint 60  commit 86eebee  T4 doing
2026-09-08 01:58:49 UTC  checkpoint 60  commit 2e1df9f  T4 done
2026-09-08 01:58:49 UTC  checkpoint 60  commit 36ad679  T5 doing
2026-09-08 02:01:30 UTC  checkpoint 60  commit 502e35c  T5 done
2026-09-08 02:01:30 UTC  checkpoint 60  commit f3afcdf  T6 doing
2026-09-08 02:08:32 UTC  checkpoint 60  commit 39fa777  fix H01
2026-09-08 02:08:33 UTC  checkpoint 60  commit 828765c  fix H02
2026-09-08 02:10:32 UTC  checkpoint 60  commit c0cccf9  T6 done
2026-09-08 02:10:33 UTC  checkpoint 60  commit 7cd5234  scrub feature complete, 390 tests green
2026-09-08 02:13:12 UTC  checkpoint 60  commit 1669feb  T7 doing
2026-09-08 02:13:12 UTC  checkpoint 60  commit 42d9999  v7.1 shipped: chart scrubbing
2026-09-08 02:13:22 UTC  checkpoint 60  commit 6e13a24  T7 done
2026-09-08 02:13:23 UTC  checkpoint 60  commit b27959c  round 60 closed
2026-09-08 02:24:42 UTC  checkpoint 61  commit d8a82ee  round 61 opened - percent/dollar toggle
2026-09-08 02:25:51 UTC  checkpoint 61  commit 3315f97  T0 done
2026-09-08 02:25:51 UTC  checkpoint 61  commit 077fae5  T1 done
2026-09-08 02:25:52 UTC  checkpoint 61  commit dca8c67  T2 doing
2026-09-08 02:27:32 UTC  checkpoint 61  commit b50e413  T2 done
2026-09-08 02:27:32 UTC  checkpoint 61  commit 8480d0c  T3 doing
2026-09-08 02:30:26 UTC  checkpoint 61  commit 46f37e4  T3 done
2026-09-08 02:30:27 UTC  checkpoint 61  commit e84bd89  T4 doing
2026-09-08 02:30:27 UTC  checkpoint 61  commit 13d0cec  percent/dollar toggle wired across all three surfaces
2026-09-08 02:34:52 UTC  checkpoint 61  commit 2e328e9  T4 done
2026-09-08 02:34:52 UTC  checkpoint 61  commit 5bcf4f3  T5 doing
2026-09-08 02:36:51 UTC  checkpoint 61  commit 4e80f52  T5 done
2026-09-08 02:36:51 UTC  checkpoint 61  commit 2ae806e  T6 doing
2026-09-08 02:36:52 UTC  checkpoint 61  commit 783cd18  regression clean at 405 tests
2026-09-08 02:44:44 UTC  checkpoint 61  commit 997d388  fix I01
2026-09-08 02:44:45 UTC  checkpoint 61  commit 8906d77  fix I02
2026-09-08 02:44:45 UTC  checkpoint 61  commit d5f7aed  T6 done
2026-09-08 02:46:49 UTC  checkpoint 61  commit 58f62a0  all green: 411 tests, lint clean
2026-09-08 02:49:05 UTC  checkpoint 61  commit 2b57fe4  T7 doing
2026-09-08 04:27:34 UTC  checkpoint 62  commit 5ecfe89  T0 doing
2026-09-08 04:32:05 UTC  checkpoint 62  commit 4138e06  T0 done
2026-09-08 04:32:05 UTC  checkpoint 62  commit 9e351ac  T1 done
2026-09-08 04:32:05 UTC  checkpoint 62  commit f80c36e  T2 doing
2026-09-08 04:34:26 UTC  checkpoint 62  commit 08f18ea  T2 done
2026-09-08 04:34:27 UTC  checkpoint 62  commit 2b0fa5a  T3 done
2026-09-08 04:34:27 UTC  checkpoint 62  commit 10ebe96  T4 done
2026-09-08 04:34:28 UTC  checkpoint 62  commit 7519323  T5 doing
2026-09-08 04:34:28 UTC  checkpoint 62  commit 958d4f4  chips carry each window's move; compiles
2026-09-08 04:43:56 UTC  checkpoint 62  commit 22e1734  T5 done
2026-09-08 04:43:57 UTC  checkpoint 62  commit b91823a  T6 done
2026-09-08 04:43:57 UTC  checkpoint 62  commit 2523984  T7 doing
2026-09-08 04:43:58 UTC  checkpoint 62  commit f73d0ea  chips shipped behind tests: 440 green, lint clean
2026-09-08 04:47:50 UTC  checkpoint 62  commit 260fe18  fix J01
2026-09-08 04:47:50 UTC  checkpoint 62  commit 8114bf5  fix J02
2026-09-08 04:48:06 UTC  checkpoint 62  commit 11ea344  T7 done
2026-09-08 04:48:06 UTC  checkpoint 62  commit ce76d56  T8 doing
2026-09-08 04:51:11 UTC  checkpoint 62  commit 4a784cd  T8 done
2026-09-08 04:51:11 UTC  checkpoint 62  no-change  round 62 complete: per-range performance chips shipped as v7.3
2026-09-08 04:51:25 UTC  checkpoint 62  commit 90cd23b  T5 done

## Round 62 ledger (closed 2026-09-08 05:57:17 UTC)

Request: Round 63: (1) swipe left/right to change tabs, (2) pinch-zoom charts continuously from All-time down to 5-minute, (3) Best ETFs research tab with periodic online research + cache + Claude-bridge export, (4) the last approved feature: SPY comparison overlay. Then a thorough optimization + bug sweep until confident the app is clean.

Tasks 9/12 done, findings 2/2 fixed

- [x] T0  Baseline: v7.2 tree builds and 411 tests green before any edit  - 416/416 green on the untouched v7.2 tree (Gradle + SDK restored on a cold container)
- [x] T1  Design: chip figures mirror the drawn chart exactly, cached-only, zero new requests  - chips show the SAME figure the chart readout shows for that window - same series, same baseline, same live edge - and only for ranges already held. No chip ever starts a fetch: the disk read in loadChart already publishes every cached range for the symbol in one query, so this feature is free
- [x] T2  Pure model: a total function for what each chip shows  - rangePct + rangeFigure: total, no allocation, negative zero normalised
- [x] T3  RangeChips UI: two-line chip, sign colour, 48dp rule, contentDescription  - two-line chip: label over figure, sign colour off the selected chip, nbsp keeps every chip the same height
- [x] T4  Wire DetailScreen: per-range live edge, loading state, no extra fetches  - DetailScreen builds chartPerf/chartLoadingRanges from the map it already collects; compiles clean
- [x] T5  Tests: pure + rendered, incl. chip equals readout and no-new-request proof  - RangeChipTest (16 pure) + RangeChipUiTest (9 rendered, incl. chip-equals-readout with the live edge)
- [x] T6  REGRESSION: full suite, lint, checkinit; prove chart and the rest unchanged  - 440/440 green, lint vital clean, checkinit ok; the only pre-existing test touched is ChartUiTest's chip loop, which now scrolls as a finger would
- [x] T7  Adversarial review of the feature, then fix what it finds  - 2 findings (age, truncation), both fixed and both covered by tests
- [x] T8  Only if all clean: ship v7.3 (versionCode 60) + checkpoint  - v7.3 shipped: versionCode 60, signed with the archived key (cert SHA-256 unchanged), 443/443 green, lint clean
- [ ] T9  SWEEP 2: UI, code and network-efficiency pass; fix everything found
- [ ] T10  SWEEP 3: re-scan until clean - verify no fix introduced a new bug
- [ ] T11  Ship v7.4 (versionCode 61) + final checkpoint
- [x] J01 (med) A chip figure comes from whatever series is cached for that range, and a cached row can be days old - so a chip could label a month-old figure '1M' with nothing saying when it was measured. The chart has dates and a caption; a chip has neither.  - a figure is printed only from a series fetched within the last 24h; an unstamped row counts as unknown age, not as fresh
- [x] J02 (med) A truncated series - a stock that listed 18 months ago has no 5Y line - would put a figure under a '5Y' label that is really 18 months. The chart says so in words; the chip cannot, so it must not make the claim.  - a truncated series puts no figure on its chip - the chart captions that case in words and a chip cannot
2026-09-08 05:57:25 UTC  checkpoint 63  commit a8f5808  T0 doing
2026-09-08 06:04:58 UTC  checkpoint 63  commit d4a6b0a  T0 done
2026-09-08 06:05:05 UTC  checkpoint 63  commit ebe3b26  T1 doing
2026-09-08 06:09:11 UTC  checkpoint 63  commit 27042af  T1 done
2026-09-08 06:09:11 UTC  checkpoint 63  commit a899b47  T2 doing
2026-09-08 06:15:36 UTC  checkpoint 63  commit 9f3aa64  T2 done
2026-09-08 06:15:36 UTC  checkpoint 63  commit 479ac65  T3 doing
2026-09-08 06:26:51 UTC  checkpoint 63  commit 7050362  T3 done
2026-09-08 06:26:51 UTC  checkpoint 63  commit b3fc466  T4 doing
2026-09-08 06:33:47 UTC  checkpoint 63  commit b7e6630  T4: Best ETFs UI + Claude bridge wired
2026-09-08 06:33:56 UTC  checkpoint 63  commit 76e65b9  T4 done
2026-09-08 06:33:56 UTC  checkpoint 63  commit 79825ba  T5 doing
2026-09-08 06:43:45 UTC  checkpoint 63  commit e1e8c0e  T5 done
2026-09-08 06:43:45 UTC  checkpoint 63  commit 783bf19  T6 doing
2026-09-08 06:43:46 UTC  checkpoint 63  commit 449adbf  T1-T5 implemented and compiling
2026-09-08 06:53:59 UTC  checkpoint 63  commit d65f42e  fix J01
2026-09-08 07:00:39 UTC  checkpoint 63  commit 13b0ca3  T6 done
2026-09-08 07:00:40 UTC  checkpoint 63  commit 24719b9  T7 doing
2026-09-08 07:06:29 UTC  checkpoint 63  commit 4508d17  T7 done
2026-09-08 07:06:29 UTC  checkpoint 63  commit e50249c  T6-T7: 522 tests green, lint clean
2026-09-08 07:08:13 UTC  checkpoint 63  commit 419d7ec  T8 doing
2026-09-08 07:15:27 UTC  checkpoint 63  commit 835c966  fix F01
2026-09-08 07:15:28 UTC  checkpoint 63  commit 39b04d0  fix F02
2026-09-08 07:15:28 UTC  checkpoint 63  commit 067d0b1  fix F03
2026-09-08 07:15:29 UTC  checkpoint 63  commit 119b255  fix F04
2026-09-08 07:17:24 UTC  checkpoint 63  no-change  F01-F04 fixed, multi-finger regressions covered
2026-09-08 07:23:19 UTC  checkpoint 63  commit 2e8e158  fix F05
2026-09-08 07:37:51 UTC  checkpoint 63  no-change  sweep in progress: F01-F05 fixed, UI verified by screenshot
2026-09-08 08:05:35 UTC  checkpoint 63  commit 4d2d6df  fix F06
2026-09-08 08:05:35 UTC  checkpoint 63  commit 85a0868  fix F07
2026-09-08 08:05:36 UTC  checkpoint 63  commit 983c46b  fix F08
2026-09-08 08:05:37 UTC  checkpoint 63  commit abddf5b  fix F09
2026-09-08 08:05:37 UTC  checkpoint 63  commit 4f2b20c  fix F10
2026-09-08 08:05:40 UTC  checkpoint 63  commit 6b315bd  fix F11
2026-09-08 08:05:41 UTC  checkpoint 63  commit be6f222  fix F12
2026-09-08 08:05:41 UTC  checkpoint 63  commit c8cfe09  fix F13
2026-09-08 08:05:42 UTC  checkpoint 63  commit 582973d  fix F14
2026-09-08 08:05:42 UTC  checkpoint 63  commit 1221c80  fix F15
2026-09-08 08:05:43 UTC  checkpoint 63  commit 51f27bb  fix F16
2026-09-08 08:05:43 UTC  checkpoint 63  commit c378190  fix F17
2026-09-08 08:05:44 UTC  checkpoint 63  commit e8c9447  fix F18
2026-09-08 08:05:45 UTC  checkpoint 63  commit a32138a  fix F19
2026-09-08 08:05:45 UTC  checkpoint 63  commit 6f13699  fix F20
2026-09-08 08:10:23 UTC  checkpoint 63  commit 17fef1e  T8 done
2026-09-08 08:10:23 UTC  checkpoint 63  commit e78af29  sweep 1 complete: F06-F20 fixed, 557 tests
2026-09-08 08:10:42 UTC  checkpoint 63  commit 0cab8b4  T9 doing
2026-09-08 15:28:04 UTC  checkpoint 63  commit 30072ee  fix N01
2026-09-08 15:28:05 UTC  checkpoint 63  commit 79cb07e  fix N02
2026-09-08 15:28:06 UTC  checkpoint 63  commit b6bc30e  fix N03
2026-09-08 15:28:07 UTC  checkpoint 63  commit cc3152e  fix N04
2026-09-08 15:28:07 UTC  checkpoint 63  commit 15034c3  fix N05
2026-09-08 15:28:08 UTC  checkpoint 63  commit d0c219e  fix N06
2026-09-08 15:28:09 UTC  checkpoint 63  commit f5db870  fix N07
2026-09-08 15:28:09 UTC  checkpoint 63  commit 3c0bb3c  fix N08
2026-09-08 15:28:10 UTC  checkpoint 63  commit 38b4ae1  fix N09
2026-09-08 15:28:11 UTC  checkpoint 63  commit f27f11f  fix N10
2026-09-08 15:28:12 UTC  checkpoint 63  commit 4135970  fix N11
2026-09-08 15:28:12 UTC  checkpoint 63  commit 7cf9e56  N01-N11 fixed: network and main-thread waste, 556 tests green
2026-09-08 15:28:20 UTC  checkpoint 63  commit 29c8b38  T9 doing
2026-09-08 15:53:30 UTC  checkpoint 63  commit f38ab74  fix U01
2026-09-08 15:53:31 UTC  checkpoint 63  commit 374b014  fix U02
2026-09-08 15:53:31 UTC  checkpoint 63  commit f8955af  fix U03
2026-09-08 15:53:32 UTC  checkpoint 63  commit 093f62a  fix U04
2026-09-08 15:53:33 UTC  checkpoint 63  commit 738d641  fix U05
2026-09-08 15:53:34 UTC  checkpoint 63  commit 2cd0d24  fix U06
2026-09-08 15:53:34 UTC  checkpoint 63  commit c0e6f5e  fix U07
2026-09-08 15:53:35 UTC  checkpoint 63  commit 2a7a432  fix U08
2026-09-08 15:53:36 UTC  checkpoint 63  commit 2463c9d  fix U09
2026-09-08 15:53:36 UTC  checkpoint 63  commit d6edbd7  fix U10
2026-09-08 15:53:37 UTC  checkpoint 63  commit c640a95  fix U11
2026-09-08 15:53:38 UTC  checkpoint 63  commit 6e0abc3  fix U12
2026-09-08 15:53:39 UTC  checkpoint 63  commit 091c086  fix U13
2026-09-08 15:53:39 UTC  checkpoint 63  commit 5fed7d5  fix U14
2026-09-08 15:53:40 UTC  checkpoint 63  commit 154b025  fix U15
2026-09-08 15:53:41 UTC  checkpoint 63  commit 140118d  fix U16
2026-09-08 15:53:42 UTC  checkpoint 63  commit 5f2f85c  fix U17
2026-09-08 15:53:42 UTC  checkpoint 63  commit 60ed91d  T9 done
2026-09-08 15:53:43 UTC  checkpoint 63  commit 19df582  sweep 2 complete: N01-N11 and U01-U17 fixed, 557 green
2026-09-08 15:54:06 UTC  checkpoint 63  commit 8924f81  T10 doing
2026-09-08 16:36:45 UTC  checkpoint 63  commit e0ebd1f  fix R01
2026-09-08 16:36:46 UTC  checkpoint 63  commit 82e57c1  fix R02
2026-09-08 16:36:47 UTC  checkpoint 63  commit 4a9f544  fix R03
2026-09-08 16:36:47 UTC  checkpoint 63  commit bac0641  fix R04
2026-09-08 16:36:48 UTC  checkpoint 63  commit 49f2ca5  fix R05
2026-09-08 16:36:49 UTC  checkpoint 63  commit d395ad0  fix R06
2026-09-08 16:36:50 UTC  checkpoint 63  commit 901917b  fix R07
2026-09-08 16:36:51 UTC  checkpoint 63  commit f5f579d  fix R08
2026-09-08 16:36:52 UTC  checkpoint 63  commit 76cd35d  fix R09
2026-09-08 16:36:53 UTC  checkpoint 63  commit 19bce17  fix R10
2026-09-08 16:36:53 UTC  checkpoint 63  commit d579a21  fix R11
2026-09-08 16:36:54 UTC  checkpoint 63  commit c52c2ab  fix R12
2026-09-08 16:36:55 UTC  checkpoint 63  commit 2400ebf  fix R13
2026-09-08 16:36:56 UTC  checkpoint 63  commit 77ccccf  fix R14
2026-09-08 16:36:57 UTC  checkpoint 63  commit e8591df  sweep 3: 14 regressions from my own fixes found and fixed, incl. one that would have made the app render nothing
2026-09-08 16:37:18 UTC  checkpoint 63  commit 3471b0b  T10 done
2026-09-08 16:37:19 UTC  checkpoint 63  commit 8bc3f24  T12 doing
2026-09-08 17:06:34 UTC  checkpoint 63  commit 6bf7e20  fix S01
2026-09-08 17:06:34 UTC  checkpoint 63  commit 01f2775  fix S02
2026-09-08 17:06:35 UTC  checkpoint 63  commit 57738c6  fix S03
2026-09-08 17:06:36 UTC  checkpoint 63  commit 08d400a  fix S04
2026-09-08 17:06:37 UTC  checkpoint 63  commit 4ff9a87  fix S05
2026-09-08 17:06:38 UTC  checkpoint 63  commit bb9a94f  fix S06
2026-09-08 17:06:38 UTC  checkpoint 63  commit 54d76f0  fix S07
2026-09-08 17:06:39 UTC  checkpoint 63  commit 7be10a5  fix S08
2026-09-08 17:06:40 UTC  checkpoint 63  commit fd3e4a5  sweep 4: 8 more findings incl. a deadlock I introduced; 569 green
2026-09-08 17:06:58 UTC  checkpoint 63  commit 3b950eb  T12 done
2026-09-08 17:06:59 UTC  checkpoint 63  commit 2117670  T13 doing
2026-09-08 20:04:05 UTC  checkpoint 63  commit 9f12f01  T13 done
2026-09-08 20:04:34 UTC  checkpoint 63  commit 4f2f283  T11 doing
2026-09-08 20:15:13 UTC  checkpoint 63  commit 56f2ae6  T11 done
2026-09-08 20:15:14 UTC  checkpoint 63  no-change  v7.4 shipped
2026-09-08 20:15:42 UTC  checkpoint 63  commit 818606f  round 63 complete

## Round 63 ledger (closed 2026-09-08 20:24:41 UTC)

Request: Round 63: (1) swipe left/right to change tabs, (2) pinch-zoom charts continuously from All-time down to 5-minute intervals, (3) Best ETFs research tab - online research, periodically refreshed, cached between updates, with the Claude-app export the other sections have, (4) the last approved feature: SPY comparison overlay. Then a thorough sweep for UI/code/network improvements and bugs, repeated until confident the app is clean.

Tasks 14/14 done, findings 71/71 fixed

- [x] T0  Baseline: v7.3 tree builds and 443 tests green before any edit  - 443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)
- [x] T1  Swipe-to-change-tabs: horizontal gesture paging over the 6 top-level tabs  - gesture-based tab paging (not a pager - neighbours stay uncomposed so VisibleScope still describes one screen); pure swipeTarget decision + slide animation shared with the bar
- [x] T2  Pinch-zoom charts: continuous zoom across the range ladder, All-time <-> 5m  - pinch zoom walks ChartRange.ZOOM_LADDER (All->5Y->1Y->6M->1M->5D->1D/5-minute); one pointer loop shared with scrubbing, per-rung 1.55x accumulator, 380ms settle so a multi-rung spread fetches only the rung it lands on; chip row auto-scrolls to the selection
- [x] T3  Best ETFs data: ETF screener feed + scorer + cached section + periodic refresh  - EtfScreener (3 keyless Yahoo fund screens, 10 requests, ~850 funds - measured that top_performing_etfs duplicates top_etfs_us and dropped it), EtfRow/EtfFacts, EtfScore (returns weighted to 5y/3y, cost, size, liquidity, age, trend; leveraged+inverse excluded), Research.buildEtfs on its own 6h TTL, vm.loadEtfs/etfsStale with its own job
- [x] T4  Best ETFs UI: fourth research tab + Claude-app bridge export/import  - fourth research tab with its own scroll state, timestamp, refresh target, blurb, sources and warnings; ETF facts grid on the card; prompt/bundle/parse/merge carry the etfs array and name the universe gaps so Claude adds the funds Yahoo's screens omit; funds Claude adds survive a rebuild
- [x] T5  SPY comparison overlay: second series aligned to the first and drawn against it  - SPY overlay: percent mode with both lines rebased to the same moment (previous close intraday, the benchmark's value at the window start for longer ranges), zero line, segmented benchmark path across gaps, dual crosshair, legend, live edge on both tips, 'vs SPY' chip beside the range chips; shares loadChart's cache so it costs one fetch per range per TTL for the whole app
- [x] T6  Tests for T1-T5: pure + rendered  - SwipeTabTest 15, ChartZoomTest 12, CompareChartTest 10, EtfTest 23, GestureUiTest 11 (real multi-touch), CompareChartUiTest 8 - 79 new
- [x] T7  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke  - 522/522 green (443 baseline + 79 new, nothing pre-existing touched), lint vital clean, checkinit ok
- [x] T8  SWEEP 1: adversarial bug hunt across the whole app; fix everything found  - two independent reviewers over the chart/gesture and research/ETF code found 15 real bugs (F06-F20), including two severe ones I had shipped into this round: the pinch was destroyed mid-gesture on any uncached range, and every stock rebuild silently wiped the ETF list off disk. All fixed and regression-tested.
- [x] T9  SWEEP 2: UI, code and network-efficiency pass; fix everything found  - network: 11 findings (N01-N11) - the biggest were a quote fallback with no failure memory (~900 req/hr for one bad ticker), the market feeds pulled for an invisible screen (~140/hr) and one open stock sweeping the whole portfolio's headlines (~480/hr). UI: 17 findings (U01-U17) - the worst were three Row-starvation bugs that made dollar figures vanish or truncate into plausible wrong numbers, and Green measuring 2.20:1 as text on white.
- [x] T10  SWEEP 3: re-scan until clean - verify no fix introduced a new bug  - 14 regressions from my own fixes (R01-R14) found and fixed, including one that would have made the app render nothing. New tests measure the tab bar inside a real Scaffold and KeyValue at four font scales.
- [x] T11  Ship v7.4 (versionCode 61) + final checkpoint  - v7.4 shipped: versionCode 61, signed with the archived key (cert SHA-256 2e8c3847... unchanged, so it installs in place over 7.3), 569/569 tests green, lint vital clean, checkinit ok
- [x] T12  SWEEP 4: verify the sweep-3 fixes; stop only when a sweep finds nothing that matters  - 8 findings (S01-S08) incl. a deadlock between the new settings lock and restoreJson's transaction
- [x] T13  SWEEP 5: final verification pass; ship only if it finds nothing above cosmetic  - verdict SHIP - no new defect in any of the sweep-4 fixes; the settings lock has one acquisition order and no path holds the connection or the helper monitor and then wants it; the insider stamp is right in all six reachable combinations; the feed pass is correct in all four states. Remaining items are an unused import, a dead default parameter, a doc sentence and two pre-existing edge cases.
- [x] J01 (high) isLeveragedOrInverse excluded every short-duration bond fund: ' short ' and ' ultrashort ' matched 'iShares Short Treasury Bond ETF', 'Vanguard Short-Term Bond', 'PIMCO Enhanced Short Maturity' and 'iShares Ultra Short-Term Bond'. Short-duration bond funds are among the most widely held ETFs there are - the Best ETFs list could not have contained the safe half of a portfolio.  - the test is now what the fund is short OF: 'short' followed by a duration or credit word (term/duration/maturity/treasury/bond/...) is an ordinary bond fund; anything else is inverse. Explicit multiples and 'bear'/'inverse'/'ultrapro' still exclude outright. 9 real fund names asserted both ways.
- [x] F01 (high) loadEtfs shares _researchBusy with the stock pass, so opening the ETFs tab while the 18-request stock build is running silently does nothing - and nothing ever retries. The tab sits empty until the user switches away and back or pulls down.  - the section's build effect is keyed on the shared busy flag as well, so a request dropped while the other pass was in flight is re-made the moment it clears; neither call can loop because both return immediately inside their own TTL
- [x] F02 (med) A chip tap straight after a pinch is delayed 380ms: zoomSettling is only cleared inside the settle branch, so it is still true when the tap's LaunchedEffect runs. Contradicts the documented 'a tap is not a zoom' rule.  - tapping a range chip clears zoomSettling rather than merely not setting it - an explicit destination has no intermediate rungs to swallow
- [x] F03 (med) zoomFactor reads event.changes[0] and [1] positionally. A third finger landing, or one of two lifting, reshuffles that list and produces an impossible one-frame separation ratio - which the accumulator then spends as several real zoom rungs.  - the pinch now measures a NAMED pair of pointer ids chosen when the gesture begins; if either finger leaves, the pair is re-chosen and that frame yields no reading, so a third finger costs one frame instead of an arbitrary jump
- [x] F04 (low) showMoreResearch on the ETF section calls enrichVisible, which only has work for BEST and WORST - so revealing ten more funds starts an analyst pass and flips the busy indicator for nothing.  - showMoreResearch only enriches Best and Worst - a fund's numbers arrive with its screener row and have no second stage
- [x] F05 (high) The F01 fix reintroduced a worse bug: keying the build effect on the shared busy flag means a FAILED pass re-triggers itself the instant busy clears. An empty result leaves the set stale, so loadResearch/loadEtfs launch again immediately - an unbounded retry loop of 18 (or 10) requests against providers that are almost certainly rate-limiting, which is exactly what the backoff machinery elsewhere in the app exists to prevent.  - both auto-builds now sit behind RetryClock (30s/1m/2m/4m/5m per section), so an empty pass backs off instead of re-firing the moment busy clears; force still ignores it. RetryClock promoted to top-level internal and RetryBackoffTest now exercises the real class instead of a copy of its rule.
- [x] F06 (high) CHART: PriceChart returns from the Column BEFORE the gesture surface whenever the series is null or empty. Pinching to a range that has never been fetched therefore destroys the gesture node mid-pinch: the zoom stops after exactly one rung, the badge vanishes, and the remaining fingers fall through to the list underneath. 'Zoom all the way down to 5 minute' is impossible in one gesture on any stock opened for the first time.  - the gesture surface and its state are hoisted above the empty-state branch and the placeholder carries the same modifier, so a pinch continues across a window that has not been fetched yet
- [x] F07 (high) CHART: onZoomStep = liveZoom.value is read ONCE inside pointerInput(Unit), so rememberUpdatedState is defeated and the captured lambda is whatever onZoom was on first composition. Opening a fund and tapping through to one of its holdings reuses the node, so the stale lambda writes zoomSettling into a dead MutableState - the 380ms settle never applies and a four-rung spread fires four chart fetches (eight with the overlay on), which is the exact traffic the feature was built to avoid.  - chartGestures now takes the zoom callback as a provider read per gesture rather than a value captured once inside pointerInput(Unit)
- [x] F08 (med) CHART: the benchmark's live edge is discarded whenever its last candle is later than the stock's - comparePercents looks every value up by the STOCK's timestamps, so valueAtOrBefore returns SPY's second-to-last point and the live price written into its tip is never read. The two ends being compared are then up to five minutes apart, which is precisely what compareLivePrice exists to prevent.  - on an intraday range the two right-hand tips are paired explicitly - both mean 'now' - so a benchmark candle stamped later than the stock's no longer discards the live edge
- [x] F09 (med) CHART: in price mode the y-axis corner labels print the series high and low, but the axis is widened to include the dotted previous-close baseline. On a gap-down day - previous close 110, session 98-104 - the top of the axis is 110 while the label pinned to it reads 104.00. Comparison mode uses the real bounds for the same two labels, so the two modes give the same corners different meanings.  - priceBounds() is now the single source for both the canvas scale and the corner labels, so the number printed at the top of the axis is by construction the value drawn there
- [x] F10 (low) CHART: the 'pts vs SPY' spread and the resting SPY readout take the benchmark's LAST FINITE value, which can be an earlier index than the stock's last point when the benchmark's tail is NaN. The printed out-performance is then a difference between two different moments.  - ComparePair.pairedIndex() - the last index at which BOTH lines have a reading - now feeds the spread and the resting benchmark figure, and the spread normalises negative zero
- [x] F11 (high) RESEARCH: every stock rebuild destroys the ETF list. carryExplanations returns the freshly built set, which Research.build never populates with etfs/etfGenerated/etfWarnings - so the 30-minute stock pass wipes the 6-hour fund pass, in memory and on disk. Ten Yahoo requests are then re-spent to rebuild it, repeatedly, which is exactly what TJ's 'keep the current list in cache until each update' rule forbids. notes is lost the same way while explained/explainedBy survive, so the screen claims an explanation whose text is gone.  - carryExplanations carries etfs, etfGenerated, etfWarnings and notes on all three exit paths; regression-tested directly (ResearchCarryTest) including the ETF-only cache case that takes the early exit
- [x] F12 (high) RESEARCH: fillResearchPrices writes the fetched quotes back into trending/best/worst but not into etfs. A fund Claude adds - which the prompt explicitly asks for - costs a real quote request whose answer is discarded, renders with no price forever, and re-spends the same request on every later import.  - fillResearchPrices fills the etfs list too, so a quote fetched for a fund Claude added is kept
- [x] F13 (med) RESEARCH: the org.json NULL trap, in the one place the architecture notes warn about it. optString on a JSON null returns the literal string 'null' on Android. A Claude reply with "catalyst": null paints 'null' under the card; "shortVehicle": null becomes a red 'NULL' inverse-ETF chip; "symbol": null inserts a fabricated NULL row. The desktop org.json used in tests returns the fallback, so no existing test can catch it.  - a shared JSONObject.text()/JSONArray.text() guard replaces optString across the Claude reply reader and both screener parsers; tested against real JSONObject.NULL under Robolectric, which is Android's org.json
- [x] F14 (med) RESEARCH: the same NULL trap in EtfScreener.parse - a Yahoo row with a null longName yields the name 'null', and .ifBlank never fires because 'null' is not blank, so both fallbacks are skipped and the leverage filter runs its whole test against that string.  - same guard - a null longName now falls through to shortName instead of becoming the string 'null'
- [x] F15 (low) RESEARCH: ResearchBridge's bundle computes dataAgeMinutes from set.generated only, so a user who has only opened the ETFs tab tells Claude the fund data is 0 minutes old when it may be six hours. etfGenerated is nowhere in the bundle.  - the bundle carries etfDataAgeMinutes from the fund list's own clock
- [x] F16 (low) RESEARCH: a fund Claude returns with a category but no why survives the import and then vanishes at the next rebuild - carryEtfExplanations requires why to be non-blank - contradicting the screen's own promise that funds Claude adds are kept.  - a fund Claude returned with a category but no paragraph now survives a rebuild
- [x] F17 (low) RESEARCH: an imported reply with no notes blanks the previous notes; every other field in that copy merges rather than overwrites.  - notes merges with ifBlank rather than overwriting
- [x] F18 (low) RESEARCH: the FOLLOWING chip's watched/held set is remembered on set.generated and set.explained, neither of which changes when the ETF list rebuilds - so on the ETFs tab the chip can lag until the stock pass runs.  - the watched/held set is keyed on etfGenerated as well
- [x] F19 (low) RESEARCH: EtfScreener.fetch treats a valid 200 carrying zero quotes the same as a failure and retries the identical request against the other Yahoo host, so each list's terminal page costs two requests instead of one.  - a well-formed page carrying zero quotes stops the host loop instead of re-asking the other Yahoo host
- [x] F20 (low) RESEARCH: the ETF screener parse comment claims Yahoo publishes dividendYield as a fraction, copying the stock screener's rule for a DIFFERENT field name. Measured live: on ETF rows yieldTTM and dividendYield are both percentages (SPY 0.98). The code is right and its comment is wrong, which is how a later 'fix' introduces a 100x error.  - comment corrected against the live measurement, and it now names the different field the stock screener converts so nobody applies one rule to the other
- [x] N01 (high) The per-symbol quote fallback has no failure memory - RetryClock guards charts, sparklines, holdings and research, but not quotes. A symbol the batch endpoint never returns (a delisted ticker, a typo'd watchlist add, a foreign listing) is permanently 'missing', so the four-provider chain runs every tick forever: ~720-960 requests an hour, for one bad symbol, split across Yahoo, Finnhub and Stooq.  - MarketData now carries its own fallbackRetry (RetryClock) keyed by symbol; a pull-to-refresh clears it. A permanently unanswerable ticker costs one attempt every five minutes instead of four every fifteen seconds.
- [x] N02 (high) News.market() - seven RSS feeds - is pulled unconditionally inside refreshFeed, which runs on a three-minute timer whether or not a headline screen is visible. ~140 requests an hour spent while sitting on Portfolio, Activity, Advice, Settings or a stock. The newsDue flag that would gate it already exists and is computed 55 lines below, guarding only the per-symbol loop.  - the seven market feeds are gated on the same newsDue rule the per-symbol loop uses, and setNewsVisible(true) kicks a pass when what is held is already past its interval - so the tab is no less fresh, it just stops fetching for a screen nobody is on.
- [x] N03 (high) Opening ONE stock sets newsVisible, which makes the three-minute feed pass sweep headlines for every held AND watched symbol - ~480 requests an hour, 23 of every 24 for a symbol not on screen. The open stock's own headlines do not even come from there; DetailScreen reads what loadNews(symbol) fetched.  - newsVisible and newsSymbol are now two signals: the Feed tab asks for every followed symbol because that is the list it draws, a detail screen asks for its own one. Opening a stock went from ~480 requests an hour to ~20.
- [x] N04 (med) The 1D chart and the row sparkline are the same Yahoo URL on the same five-minute TTL. Round 58 closed one direction (adoptAsSparkline) but refreshSparklines' due filter never consults _charts, so on the tick where both lapse together the same ~30KB body is fetched twice - about 12 duplicated requests an hour per open detail screen.  - refreshSparklines skips any symbol whose cached 1D chart is regular-session and inside its TTL - exactly the condition adoptAsSparkline requires - so the shared URL is never fetched twice in one window.
- [x] N05 (med) loadInsider's guard is 'we already hold filings for this symbol', which never becomes true for a symbol with no Form 4 in the 31-day window - the ordinary case. So every detail-screen open and every resume sends a fresh EDGAR listing request, and the daily-rolling datea parameter means the conditional-GET cache cannot answer it either.  - insiderAt records that an EDGAR pass COMPLETED rather than that it found something, on a 30-minute TTL matching Form 4's own legal lag; a failed pass still never stamps it
- [x] N06 (med) The feed's per-headline loop rebuilds holdingNames() once PER HEADLINE and calls Relevance.matchHolding without a hoisted Subject or a pre-squashed haystack - roughly 4,000 Subject constructions and 4,000 squash calls per feed pass, every three minutes, ON THE MAIN THREAD. Relevance.Subject exists precisely to hoist this; the Feed path never adopted it.  - the feed's market pass hoists holdingNames and Relevance.Subject out of the per-headline loop, pre-squashes each headline once, and runs on Dispatchers.Default instead of the main thread
- [x] N07 (med) Ledger.totals re-scans the whole transaction list six times on every quote tick - cash, netDeposits, dividends, fees, realized - all pure functions of cachedTxns, which only changes in recompute(). 240 ticks an hour x six full passes, on the main thread, always producing the same five numbers.  - Ledger.sums computes the four ledger-only figures once in recompute(); totals takes them as an optional parameter so every existing call site and test is unchanged
- [x] N08 (med) publish() runs two synchronous SQLite queries per quote tick (useCashOverride + cashOverrideValue), plus refreshSecs() once per tick and finnhubKey() once per refresh - ~480+ main-thread rawQuery calls an hour against the settings table.  - Db keeps a write-through settings cache, with absence cached as its own sentinel so hasSetting still tells a missing key from a stored blank; restoreJson invalidates it on entry and on both exits
- [x] N09 (low) chartFetchedAt and holdingsFetchedAt are written and never read anywhere - chartFetchedAt is documented as the bug RetryClock replaced, and the field survived the fix. Dead state that grows unbounded and misleads the next reader.  - chartFetchedAt and holdingsFetchedAt deleted along with their writes; the KDoc that described the first as the bug RetryClock replaced now says it was removed
- [x] N10 (low) loadFundamentals and loadRatings stamp their TTL only on success, so a symbol whose quoteSummary is refused is re-requested on every detail open and every resume - and the analyst payload is the heaviest thing the app fetches.  - a shared fundRetry backs off refused quoteSummary and analyst fetches per symbol, cleared by a manual refresh
- [x] N11 (low) fillResearchPrices fetches up to 20 quotes one symbol at a time through MarketData.quote, bypassing the batched endpoint that would do it in one request - and calls finnhubKey(), a SQLite read, inside each async.  - fillResearchPrices uses the batched MarketData.quotes and reads the Finnhub key once
- [x] U01 (high) StockRow's money cells are three weight(1f) columns, ~118dp each on a 411dp phone. A six-figure holding at font scale 1.5, or a five-figure one at 2.0, ellipsizes to '$123,45...' - which is still a well-formed dollar amount and reads at a glance as either $123 thousand or $123 hundred. Nothing else on the row carries the magnitude.  - money figures now shrink to fit rather than truncate - a new AutoFitNumber steps the type down to a floor and only ellipsises below it, so a six-figure holding at 2x reads in full instead of as '$123,45...'
- [x] U02 (high) The sub-figure under each money cell has maxLines = 1 and NO overflow parameter, so it defaults to Clip. In percent-first P/L mode that is the dollar figure: '+$12,345.67' becomes '+$12,345.' with no ellipsis and nothing saying anything was removed. The two Texts directly above it both pass Ellipsis; this one was missed.  - same widget on the sub-figure, which had no overflow parameter at all and was clipping silently
- [x] U03 (high) KeyValue lays out label then value as two UNWEIGHTED children of a Row. Compose measures them in order, so a label long enough to wrap takes the full width and the value is measured at maxWidth = 0 and disappears entirely. Neither Text sets maxLines. Worst case is the portfolio summary card - 'Gain on stocks you still own' plus its figure has 13dp of headroom at scale 1.0, so it breaks at 1.15x, the first slider step above default.  - KeyValue weights the LABEL, so the value is the unweighted child measured first at full constraints and can never be starved to zero; the label wraps to two lines then ellipsises. Verified by screenshot at 2x.
- [x] U04 (med) The bottom tab bar has a hard-coded 74dp height and its labels are unbounded sp. At font scale ~1.45 'Portfolio', 'Activity' and 'Settings' wrap to two lines against a 23dp label budget and paint outside the bar, pushing the icons. It is the one chrome element visible on every screen.  - the tab bar uses heightIn(min = 74.dp) so it grows with the font, and every label is one line, centred, ellipsised
- [x] U05 (med) Green #16C784 on the light theme's white background is 2.20:1 - WCAG AA for 15sp bold needs 4.5:1. Red is 3.69:1. In dark they are 8.6:1 and 5.1:1, so the palette was tuned there and never re-checked against light. Worst instance: 'you own this' in green at 10sp on white. This is a contrast problem, not a colour-only-meaning one - the app is disciplined about always printing a sign.  - text and fill palettes separated: Green/Red are untouched as fills, and signColor - which every signed figure uses - is now theme-aware, resolving to #0A8055 (4.96:1) and #C62B3C (5.51:1) on light and to the brand colours on dark. Standalone green/red TEXT sites routed the same way.
- [x] U06 (med) The Research header Row measures four unweighted children in sequence, so at large font scales the Rebuild button - measured last - is squeezed under 48dp from ~1.75x and to zero width at 2.0x. It is the only non-gesture way to rebuild the list being viewed.  - the Research header's texts are weighted, so the Rebuild button is measured first and keeps its 52dp at every font scale
- [x] U07 (med) The portfolio summary's BigLine and PlainLine starve the same way: the weighted Spacer sits BETWEEN the label and the numbers, so it protects neither. BigLine breaks at ~1.3x and what vanishes is the sub-figure; PlainLine breaks at ~1.6x and what vanishes is the value.  - BigLine and PlainLine weight the label instead of putting a weighted Spacer between it and the numbers, so the figures are measured first
- [x] U08 (med) The Research card's score is a bare integer in a coloured circle. Nothing on the card, and nothing in any section blurb, says it is a score or what the scale is - the blurbs describe the inputs but never the output. The only mention is 300dp below, past ten cards.  - the score circle carries a SCORE cap-label and a contentDescription reading 'Score N out of 100'
- [x] U09 (med) The Research tab row is a fixed SecondaryTabRow: four tabs across 411dp is 102dp each, and 'Trending (20)' at 14sp needs ~104dp at scale 1.3, so it wraps into a fixed 48dp tab height and clips. DetailScreen's equivalent is scrollable and does not have this.  - the Research tab row is now SecondaryScrollableTabRow, like the detail screen's
- [x] U10 (med) Three counts on the Research screen can disagree: the tab label prints rows.size, the list renders rows.take(n).distinctBy { symbol } - which can remove rows - and the footer says 'that is all rows.size this pass found'. The code's own comment says an imported Claude answer can name a ticker twice, so this is reachable.  - the rows are de-duplicated once, where they are read, so the tab badge, the Load-more count and the footer all count what the list will actually draw
- [x] U11 (med) 'Portfolio weight' divides by totals.marketValue - stocks only - while the Portfolio screen's own headline is total equity and shows cash separately. The weights therefore sum to 100% of a number that is explicitly not 'what you have', and nothing says which.  - renamed to 'Share of your stocks' - the denominator is market value, not total equity
- [x] U12 (med) On first launch, before dataMissing or recoverable has resolved, the Portfolio screen renders the full 'No holdings yet - import Ally screenshots' copy under a 2dp progress bar. The file's own comment calls that the worst possible answer for the data-loss case; the loading case reaches it through a different door.  - a third branch: while the ledger is still loading the screen says so instead of showing the 'No holdings yet' copy
- [x] U13 (low) Accent #2E6BE6 on the dark surfaceVariant is 3.40:1 at 13sp on the News chip - the most-tapped control on the portfolio list - and white on the new Benchmark amber is 3.29:1 in both themes.  - BenchmarkFill (#7E5A22, 6.23:1 under white) for the vs-SPY chip, and accentText (#5B92F0 on dark, 5.30:1) for the News chip; the score card's three tiers are now theme-aware too - the middling amber was 2.46:1 on white while carrying the number itself
- [x] U14 (low) The Portfolio header's Sort button is measured last among unweighted children and is squeezed to ~37dp at font scale 2.0, under the app's own documented 48dp rule.  - the Portfolio title is weighted so all three header buttons keep 52dp
- [x] U15 (low) ResearchScreen's reason-line bullet uses a FIXED Modifier.width(12.dp) for its hyphen - the identical trap FeedScreen documents and fixes with widthIn(min = 34.dp). It survives at 2.0x today, but it is the same latent bug in the same codebase.  - widthIn(min) rather than a fixed width for the reason bullet
- [x] U16 (low) FactCell values are maxLines = 1 with Ellipsis in ~110dp cells; a three-digit annualised return at 2.0x ellipsizes to '+123...', which is not a number.  - FactCell values use AutoFitNumber, so a three-digit annualised return renders in full at 2x - verified by screenshot
- [x] U17 (low) A Research headline with a blank URL still renders a minTapTarget()-sized clickable(enabled = false) block that looks identical to a tappable one.  - a headline with no URL gets no clickable modifier at all rather than a disabled one that still looks and reports as a control
- [x] R01 (high) CRITICAL, and it would have shipped: changing the tab bar's height(74.dp) to heightIn(min = 74.dp) removed the maxHeight that each tab's Column.weight(1f).fillMaxSize() was being bounded by. Scaffold measures a bottomBar with loose constraints, so every child filled the SCREEN and the bar sized to its tallest child. Measured in a real Scaffold: bar 891dp, content 0dp. The app would render nothing at all, at any font scale.  - reverted to height(74.dp) - the children's fillMaxSize needs a bounded maxHeight, and Scaffold gives a bottomBar loose constraints. The label problem it was aimed at is fixed on the label. TabBarUiTest now measures the bar inside a real Scaffold at 1x and 2x, and was verified to FAIL against the broken version.
- [x] R02 (high) The Research header's title is ellipsised at the DEFAULT font scale. Weighting the title left the old weighted Spacer in place, so three weighted children split the space 1:1:2 and the title's share is 84dp against a 93.5dp natural width - 'Researc...' beside 140dp of blank.  - removed the leftover weighted Spacer; the status text carries the weight and the title is measured at what it needs
- [x] R03 (high) The new SCORE cap-label clips the score it labels from about 1.1x: both Texts inherit bodyLarge's 21sp lineHeight, so the Column needs 42sp of line box inside a fixed 46dp circle and Arrangement.Center gives the label its full box first. At 1.3x - Android's ordinary largest step - a third of the digits are cut.  - both texts in the score circle have explicit line heights, the number auto-fits, and the cap-label is dropped above 1.15x - the contentDescription says 'Score N out of 100' regardless
- [x] R04 (high) KeyValue no longer loses the value; it loses the LABEL instead. Measured with a long value in a 359dp card: label 48.5dp at 1.5x, 14.5dp at 1.8x, 0dp at 2.0x - an unlabelled signed figure. And the value sets softWrap=false with no overflow, so it clips with no ellipsis. The failure threshold moved from 1.15x to 1.5x; it was not removed.  - KeyValue is now a measuring Layout: it gives the value what it needs, and when the remainder for the label falls below 72dp it stacks them instead of dropping one. KeyValueUiTest asserts both halves have a non-zero width at 1.0x, 1.15x, 1.5x and 2.0x with the app's longest real label and value.
- [x] R05 (high) AutoFitNumber's floor is expressed in sp, so it scales with the user's font setting - at 2.0x the 11sp floor is 22dp of type in a 118dp cell and the number ellipsises anyway, which is the exact case the widget was written for. It also renders the three cells of one row at sizes up to 30 percent apart, in a list whose purpose is comparing them.  - the autosize floor is now dp-derived via Dp.toSp(), so it is a physical size that does not scale with the user's setting, and the step is a whole point so neighbouring cells stay on a short ladder
- [x] R06 (med) The new 'Loading your holdings...' branch replaces the onboarding copy for a user with zero holdings and a non-empty watchlist - every automatic tick runs a quote pass, so the instructions that matter most flicker away. And it misses its own target: recompute() runs synchronously in init before the first frame, so loading is false on the frames the branch was added for.  - reverted - it never fired on the frames it was written for (recompute runs synchronously in init) and it replaced the onboarding copy with a spinner caption on every tick for a watchlist-only user
- [x] R07 (med) greenText/redText/accentText/scoreColor read isSystemInDarkTheme() rather than the scheme actually in force, while PortfolioTheme takes a dark override. Production is unaffected, but seven UI tests pass dark = true and now render light-theme text colours on dark surfaces - so any dark-mode assertion is measuring the wrong pair.  - PortfolioTheme publishes LocalDarkTheme and every theme-aware colour reads that instead of isSystemInDarkTheme(), so the seven tests that render the dark scheme now get dark-scheme text
- [x] R08 (med) The chart now paints two different greens eight dp apart: the legend dot takes the LINE colour (#16C784) and the spread text beside it takes the TEXT colour (#0A8055). Same for the benchmark - the vs-SPY toggle is #7E5A22 while the line, its dot and both readouts stay #B4863B.  - one palette per feature: the chart line, its legend dot, the readout and the chips all take signColor, and benchmarkColor covers the line, the dot, the readouts and the toggle. The sparkline moved with them - a hairline at 2.20:1 on white was faint as a line too.
- [x] R09 (high) The Feed tab's new refresh-on-open never fires: stampFeedAt runs on EVERY pass including the gated no-op ones, so _feedAt is always younger than one interval. Worse, it also makes the 'Updated Xs ago' header read 'just now' over headlines last actually fetched hours ago.  - stampFeedAtIfFetched - a gated pass that fetched nothing no longer claims to have refreshed, which also stops the header reading 'just now' over hours-old headlines
- [x] R10 (high) And even when it does fire, the pass it triggers is gated off: setNewsVisible calls refreshFeed BEFORE assigning newsVisible = visible, and viewModelScope is Main.immediate, so feedDue is computed while the flag is still false. The pass that exists to fill the tab fetches nothing and then stamps _feedAt twice.  - newsVisible is assigned BEFORE the refresh it gates is kicked (viewModelScope is Main.immediate, so the body runs in place), and both transition edges are captured before the flag moves so the grace window still measures leaving
- [x] R11 (med) insiderAt is stamped on FAILED EDGAR passes. Insider.listFilings returns an empty list on any non-OK response - 403, 429, timeout, offline - which is indistinguishable from 'this company filed nothing', and the stamp is taken before the empty check. A stock opened while SEC is refusing now shows no filings for thirty minutes across every re-open, where before it retried at once.  - Insider.listing/forSymbolResult report whether EDGAR ANSWERED, taken from the HTTP response rather than inferred from an empty list, and insiderAt is only stamped when it did
- [x] R12 (med) The sparkline filter can suppress a sparkline that was never adopted. Two paths publish a D1 chart without ever writing Quote.spark - the fetch path when no quote exists yet, and the DISK RESTORE path, which never calls adoptAsSparkline at all - so a cold start into a detail screen leaves that row's sparkline stale for up to five minutes.  - the sparkline filter requires sparkAt to be set - proof the series was actually adopted - and loadChart's disk-restore path now adopts a restored 1D series, which also saves the request outright
- [x] R13 (med) The settings cache can be poisoned by a read racing a write: get() queries on a miss and stores what it read afterwards, so a read that starts before a concurrent set() commits and finishes after it leaves the cache holding the old value permanently. Also invalidateSettings() runs before endTransaction() in restoreJson's finally, so a reader in that gap can cache a value that is about to roll back.  - the settings cache's miss path and its writes are serialised on one lock with a re-check inside it; the hit path stays lock-free. restoreJson invalidates after endTransaction rather than before.
- [x] R14 (low) RetryClock failure counts never decay, so one five-minute outage drives every symbol to the five-minute tier for the rest of the process - and the next single dropped symbol starts at that tier instead of at thirty seconds.  - RetryClock forgets a key untouched for ten minutes - twice the maximum backoff - so a count describes consecutive RECENT failures rather than the life of the process
- [x] S01 (high) DEADLOCK, introduced by my own settings-cache lock. restoreJson holds an exclusive SQLite transaction and calls set() inside it, which then wants settingsLock; meanwhile any other thread doing db.set (stampFeedAt, setChartRange, setPlMode) takes settingsLock first and blocks on the connection the restore is holding. Both threads wedge permanently and the next main-thread settings read ANRs the app. A restore is exactly when the auto loop is still ticking, so this needs no exotic timing.  - restoreJson writes settings rows through a lock-free writeSetting against its own transaction handle instead of calling set(); the cache is dropped wholesale afterwards. The lock-order inversion is gone.
- [x] S02 (high) stampFeedAtIfFetched(feedDue || newsSymbols.isNotEmpty()) still lies and re-disables the refresh-on-open gate: newsSymbols is non-empty whenever a stock detail screen is open, so browsing stocks stamps _feedAt every tick without the market feeds having been fetched. Opening the Feed then sees age < interval, skips the refresh, and shows hours-old headlines captioned 'Updated 1 minute ago'.  - only feedDue stamps _feedAt - it governs the market-wide list and the refresh-on-open rule, and a per-symbol fetch is not that
- [x] S03 (high) adoptAsSparkline on the disk-restore path adopts an arbitrarily STALE D1 series and stamps sparkAt, which is the same bug it was added to fix, from the other side. A cold start onto a stock whose remembered range is not 1D restores yesterday's intraday line into Quote.spark, and the portfolio row then draws it against TODAY's previous close for five minutes - wrong shape and possibly wrong colour.  - the disk-restore path adopts only a series that is still inside its TTL, so a stale one can no longer stamp sparkAt and suppress the real refresh
- [x] S04 (med) Routing the vs-SPY chip through benchmarkColor re-broke the contrast BenchmarkFill was created to fix: in the dark theme it resolves to #B4863B and the chip's white 13sp and 11sp text is 3.1:1 against it.  - onBenchmark - near-black on the bright amber (5.42:1), white on the dark one (6.23:1) - so the chip can match the line and still carry legible text in both themes
- [x] S05 (low) Insider.forSymbol is now a verbatim copy of forSymbolResult's body and both it and listFilings have zero callers - dead duplicated logic that can only drift.  - forSymbol delegates to forSymbolResult instead of duplicating its body
- [x] S06 (low) insiderAt is stamped when the LISTING was answered even if every per-filing fetch then failed, so a partial failure caches an empty result for thirty minutes.  - SymbolResult carries how many filings the listing named, so a listing that was answered but whose fetches all failed is no longer cached as 'this company filed nothing'
- [x] S07 (low) KeyValue's Layout does not guard against an unbounded maxWidth - unreachable today, since no call site is inside a horizontal scroller or under IntrinsicSize, but it would place the value about 16 million pixels off screen.  - KeyValue falls back to its children's intrinsic width when maxWidth is unbounded
- [x] S08 (low) RetryClock's new ten-minute forgetting rule has no test, in the file whose whole purpose is testing that rule.  - two tests for the forgetting rule: a key untouched for ten minutes starts again at 30s, and one still failing steadily keeps its streak
2026-09-08 20:26:05 UTC  checkpoint 64  commit 4048a8f  T0 doing
2026-09-08 20:37:56 UTC  checkpoint 64  commit f6347ef  T0 done
2026-09-08 20:37:57 UTC  checkpoint 64  commit d3786d6  T2 doing
2026-09-08 21:13:54 UTC  checkpoint 64  commit 46b0abe  T2 done
2026-09-08 21:13:55 UTC  checkpoint 64  commit 273b1e9  T1 done
2026-09-08 21:13:56 UTC  checkpoint 64  commit 2ef7abe  T3 done
2026-09-08 21:13:57 UTC  checkpoint 64  commit aa088f7  T4 doing
2026-09-08 21:37:23 UTC  checkpoint 64  commit 2d7754e  T4 done
2026-09-08 21:37:24 UTC  checkpoint 64  commit c185d75  T5 doing
2026-09-08 21:45:41 UTC  checkpoint 64  commit eecd3e6  T5 done
2026-09-08 21:45:42 UTC  checkpoint 64  commit f313cb1  T6 doing
2026-09-08 22:15:20 UTC  checkpoint 64  commit 71833e1  fix F01
2026-09-08 22:15:21 UTC  checkpoint 64  commit 3c64eba  fix F02
2026-09-08 22:15:22 UTC  checkpoint 64  commit 6692202  fix F03
2026-09-08 22:15:23 UTC  checkpoint 64  commit 684114b  fix F04
2026-09-08 22:15:24 UTC  checkpoint 64  commit 68a1c7e  fix F05
2026-09-08 22:15:25 UTC  checkpoint 64  commit 979f94b  fix F06
2026-09-08 22:15:26 UTC  checkpoint 64  commit 62c92de  fix F07
2026-09-08 22:15:27 UTC  checkpoint 64  commit eaf314c  fix F08
2026-09-08 22:15:28 UTC  checkpoint 64  commit b372d66  fix F09
2026-09-08 22:15:37 UTC  checkpoint 64  commit ed14764  T6 done
2026-09-08 22:15:38 UTC  checkpoint 64  commit 71948aa  T7 doing
2026-09-08 22:38:20 UTC  checkpoint 64  commit c012915  fix G01
2026-09-08 22:38:21 UTC  checkpoint 64  commit b5498b9  fix G02
2026-09-08 22:38:22 UTC  checkpoint 64  commit 2bc2917  fix G03
2026-09-08 22:38:23 UTC  checkpoint 64  commit 9c4b0ee  fix G04
2026-09-08 22:38:24 UTC  checkpoint 64  commit b2411ff  fix G05
2026-09-08 22:38:25 UTC  checkpoint 64  commit e6aa8a4  fix G06
2026-09-08 22:38:26 UTC  checkpoint 64  commit 31e476c  fix G07
2026-09-08 22:38:27 UTC  checkpoint 64  commit 1d49075  fix G08
2026-09-08 22:38:28 UTC  checkpoint 64  commit b03b03a  fix G09
2026-09-08 22:46:28 UTC  checkpoint 64  commit 4c1161e  T7 doing
2026-09-09 01:27:25 UTC  checkpoint 64  commit 3181026  fix H01
2026-09-09 01:27:26 UTC  checkpoint 64  commit 949e62b  fix H02
2026-09-09 01:27:27 UTC  checkpoint 64  commit 7b689aa  fix H03
2026-09-09 01:27:28 UTC  checkpoint 64  commit 9dcd820  fix H04
2026-09-09 01:27:29 UTC  checkpoint 64  commit 8e55552  fix H05
2026-09-09 01:27:30 UTC  checkpoint 64  commit 0e97cdf  fix H06
2026-09-09 01:27:31 UTC  checkpoint 64  commit 3fa0f2a  fix H07
2026-09-09 01:27:32 UTC  checkpoint 64  commit d78c681  fix H08
2026-09-09 01:27:33 UTC  checkpoint 64  commit de1ade5  fix H09
2026-09-09 01:27:34 UTC  checkpoint 64  commit 208cad7  fix H10
2026-09-09 01:27:35 UTC  checkpoint 64  commit 6befed8  fix H11
2026-09-09 01:27:36 UTC  checkpoint 64  commit dd25739  fix H12
2026-09-09 01:27:37 UTC  checkpoint 64  commit f9cf085  fix H13
2026-09-09 02:01:25 UTC  checkpoint 64  commit 269536e  fix J01
2026-09-09 02:01:26 UTC  checkpoint 64  commit 33c5654  fix J02
2026-09-09 02:01:27 UTC  checkpoint 64  commit f352b81  fix J03
2026-09-09 02:01:29 UTC  checkpoint 64  commit 5d284bb  fix J04
2026-09-09 02:01:30 UTC  checkpoint 64  commit e7abe3b  fix J05
2026-09-09 02:01:32 UTC  checkpoint 64  commit 771e738  fix J06
2026-09-09 02:01:33 UTC  checkpoint 64  commit 1720dcc  fix J07
2026-09-09 02:01:35 UTC  checkpoint 64  commit 318d4ff  T7 doing
2026-09-09 02:22:08 UTC  checkpoint 64  commit aa3eeec  fix K01
2026-09-09 02:22:09 UTC  checkpoint 64  commit b727461  fix K02
2026-09-09 02:22:11 UTC  checkpoint 64  commit 39bdb06  fix K03
2026-09-09 02:22:12 UTC  checkpoint 64  commit fb0bf9e  fix K04
2026-09-09 02:22:14 UTC  checkpoint 64  commit b5849f0  fix K05
2026-09-09 02:37:30 UTC  checkpoint 64  commit c38a62c  fix L01
2026-09-09 02:37:31 UTC  checkpoint 64  commit 4793e0b  fix L02
2026-09-09 02:37:33 UTC  checkpoint 64  commit 3239c62  T7 done
2026-09-09 02:37:34 UTC  checkpoint 64  commit 3941b2b  T8 doing
2026-09-09 02:42:06 UTC  checkpoint 64  commit 3513e9b  T8 done

## Round 64 ledger (closed 2026-09-09 04:48:55 UTC)

Request: Round 64: (1) the portfolio row sparklines are small - make them fill the blank area they sit in, not necessarily square. (2) ANY chart in the app: tap to open full screen, rotating with the phone's sensors. (3) make pinch-to-zoom SMOOTH and continuous instead of chopping between fixed intervals. Then more sweeps for bugs, UI and code, verifying nothing else breaks.

Tasks 9/9 done, findings 45/45 fixed

- [x] T0  Baseline: v7.4 tree builds and 569 tests green before any edit  - v7.4 baseline green
- [x] T1  Row sparklines fill their space: measure what the row actually gives them and use it  - row sparkline 1.4:1 weights, 48dp tall
- [x] T2  Continuous pinch zoom: a real time window scaled smoothly, with the range ladder behind it as the data source  - continuous window zoom + pan, axis-scaled canvas, reset chip
- [x] T3  Full-screen chart: tap any chart to open it, sensor rotation, back to close  - FullScreenChart dialog + sensor orientation + manifest configChanges
- [x] T4  Tests for T1-T3: pure + rendered + measured  - ChartWindowTest 38, ContinuousZoomUiTest 12, SparklineSizeUiTest 6, FullScreenChartUiTest 7
- [x] T5  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke  - 632/632 green, lint vital clean, checkinit ok
- [x] T6  SWEEP 1: adversarial bug hunt over the new code and the app  - 9 findings, all fixed, 640 tests green
- [x] T7  SWEEP 2: verify sweep 1's own fixes; repeat until a pass finds nothing above cosmetic  - 6 sweeps: 45 findings, all closed; sweep 6 found one low + one stale comment
- [x] T8  Ship v7.5 (versionCode 62) + final checkpoint  - v7.5 (versionCode 62) built, signed with the same cert, 663 tests green
- [x] F01 (high) Pinch-out cannot widen past loaded series: windowBounds is the union of LOADED series, so on a first-open (only 1D cached) a pinch-out saturates instantly and 'zoom out to all time' is impossible
- [x] F02 (high) Pinching the After-hours chart blanks it permanently: windowBounds excludes OVERNIGHT, so the window is clamped into the regular session which the overnight series does not overlap
- [x] F03 (high) Window is never re-anchored when a new range's series arrives; lookback is measured from a stale coarse-candle timestamp, so a spread near the right edge of a 5Y chart can land on a blank chart
- [x] F04 (high) Readout, percent change and y-axis labels are computed from the two carried points OUTSIDE the window - a 7-day picture reports a 9-day change
- [x] F05 (high) Comparison overlay pastes SPY's live price onto a mid-window point once zoomed: tip-pairing tests drawn.lastIndex, not the series' true tip
- [x] F06 (med) Chip figure, caption and point count still describe the unzoomed range while the readout describes the window - two figures on one screen that disagree
- [x] F07 (med) Reset zoom chip appears on its own every ~5 min as windowBounds advances with the periodic refresh
- [x] F08 (low) Crosshair can land on a carried off-window point: readout updates but the dot and line are drawn off-canvas
- [x] F09 (low) Chart canvas is not clipped to bounds, so a zoomed line bleeds into the 16dp gutters
- [x] G01 (high) y-axis label unit chosen from cmp but value from cmpInside: a zoomed comparison chart could print a dollar price with a percent sign
- [x] G02 (high) Canvas scaled to the padded series while the corner labels read the strict one: on every zoomed chart the top label named a price the line never reaches
- [x] G03 (high) Wall-clock lookback coarsens the 1D chart every weekend: at noon Saturday the first pinch swapped 5-minute candles for 30-minute ones
- [x] G04 (high) Optimistic 40-year bounds made isWhole never true (stuck Reset chip), made Reset select 40 years, and let a two-finger drag on an unzoomed chart pan into pre-history
- [x] G05 (med) isWhole at 0.92 discarded genuine small zooms, which the re-anchor effect then silently reset
- [x] G06 (low) Caption point count came from the padded drawing list
- [x] G07 (low) Zoomed caption for the after-hours range read oddly (no comma to splice)
- [x] G08 (low) ChartWindow.clamped could throw IllegalArgumentException on degenerate bounds inside an effect
- [x] G09 (med) Re-anchoring the window did not re-evaluate which range should draw it
- [x] H01 (high) Comparison chart drawn with cmp (padded, rebased one candle earlier) against yBounds from cmpInside: both lines shifted vertically off their own axis on a zoomed 5Y/MAX compare chart
- [x] H02 (high) cmp non-null with cmpInside null draws percentages against a dollar axis: lines land far off-canvas and the plot area shows gridlines only
- [x] H03 (high) Full-screen viewer sets decorFitsSystemWindows=false but consumes no insets: title, chips and close button sit behind the status bar
- [x] H04 (high) RESERVED=150 in the full-screen viewer has zero slack: the SPY legend row and any font scale above 1.0 push the caption and x-axis off the bottom
- [x] H05 (med) Expand button is a 34dp target with no content description and overlaps the high-price label from ~1.15x font scale; the reset chip covers it from ~1.3x
- [x] H06 (med) uiMode in configChanges leaves the status bar icons stale after a dark-mode toggle (enableEdgeToEdge only runs in onCreate)
- [x] H07 (med) AutoFitNumber applied to the watchlist company name defeats the font-scale setting: a long name renders at 11dp at the Largest setting
- [x] H08 (med) configChanges omits fontWeightAdjustment, so toggling accessibility Bold text still destroys the nav stack
- [x] H09 (low) Re-anchor effect leaves a permanently-wider-than-data window on recently listed stocks (monthly candle lag exceeds the 1.03 guard)
- [x] H10 (low) Re-anchor effect can null the window mid-pinch while WindowHold still holds it: one-frame pop-out
- [x] H11 (low) Pan guard falls back to the optimistic 40-year bounds while the series is loading
- [x] H12 (low) Reset chip does not clear zoomSettling, unlike the range chip handler
- [x] H13 (low) StockRow comment still argues for weight 1.4 while the code is 1.6; SparklineSizeUiTest asserts !hasVisualOverflow on a widget whose job is to shrink until that is true
- [x] J01 (high) Zeroing the baseline keyed on window!=null while every explaining label keys on zoomedIn: pinch out on a 1D chart and the readout measures from the session open while still saying 'since yesterday's close'; no reset chip; the dotted previous-close line is clipped away
- [x] J02 (high) Readout colour comes from the whole series while its value comes from the window: a negative change printed in green on a zoomed chart
- [x] J03 (med) clipToWindow(pad=false)'s straddle fallback picks lo..lo+1 instead of the straddling pair, so 'inside' and insideIndices describe different points; a single in-window point is also widened to two
- [x] J04 (med) The legend's 'pts vs SPY' still uses pairedIndex() over the padded list, so it reads one candle past the window while the readout above reads inside it
- [x] J05 (med) baseIndex==0 short-circuit gives the comparison overlay the previous-close rule while the readout uses the first on-screen point
- [x] J06 (low) chartFillsHeight has no floor: when the fixed children out-measure a short landscape window the plot is given zero height and vanishes
- [x] J07 (low) Status-bar icon polarity in the full-screen dialog does not follow a dark-mode toggle while it is open
- [x] K01 (high) atRightEdge's slack is 2% of the window, smaller than one candle: a zoomed-in 1D window stops following new data and the chart freezes while the price above it keeps ticking
- [x] K02 (high) heightIn(min) after weight(1f) is inert - weight passes fixed constraints and heightIn enforces incoming - so the full-screen plot can still collapse to zero
- [x] K03 (med) The re-anchor's reset test is span-only while isDefaultView is start-sensitive: a slight pinch plus a sideways drag pans the chart, then jumps back on finger-lift
- [x] K04 (med) The full-screen dialog corrects the status-bar polarity but not the navigation bar
- [x] K05 (low) A window containing exactly one candle reports +0.00 over a visibly sloping line, with the same price in both y-axis corners
- [x] L01 (low) Full-screen empty/loading placeholder collapses to the 120dp floor instead of filling, shrinking the gesture surface a pinch relies on
- [x] L02 (low) atRightEdge's comment says the slack is one candle, not a percentage; the code correctly uses the max of both
2026-09-09 04:49:05 UTC  checkpoint 65  commit f5f78ce  T0 doing
2026-09-09 04:52:58 UTC  checkpoint 65  commit 5e21349  T0 done
2026-09-09 04:52:59 UTC  checkpoint 65  commit d7bb18b  T1 doing
2026-09-09 04:53:00 UTC  checkpoint 65  commit da6d90c  round 65 restarted from checkpoint 64; baseline 663 green in the new container
2026-09-09 04:57:51 UTC  checkpoint 65  commit 83eed82  T1 done
2026-09-09 04:57:52 UTC  checkpoint 65  commit 6e212ec  T2 doing
2026-09-09 04:57:54 UTC  checkpoint 65  commit c56153f  T1: row charts sized by measuring
2026-09-09 05:13:02 UTC  checkpoint 65  commit d1ce0e4  T2 done
2026-09-09 05:13:03 UTC  checkpoint 65  commit 971b144  T3 done
2026-09-09 05:13:04 UTC  checkpoint 65  commit 1fb3465  T1-T3 done: measured row charts + one-finger pan/hold-scrub, 676 green
2026-09-09 05:19:11 UTC  checkpoint 65  commit 87f0153  fix N01
2026-09-09 05:19:12 UTC  checkpoint 65  commit 5e64df9  fix N02
2026-09-09 05:19:14 UTC  checkpoint 65  commit e8f4813  fix N03
2026-09-09 05:19:15 UTC  checkpoint 65  commit 5eeb6e9  fix N04
2026-09-09 05:20:37 UTC  checkpoint 65  no-change  sweep 1 closed
2026-09-09 05:23:27 UTC  checkpoint 65  commit 7d6b868  fix N05
2026-09-09 05:26:34 UTC  checkpoint 65  commit faebf15  T4 done
2026-09-09 05:26:36 UTC  checkpoint 65  no-change  sweeps 1-2 closed, regression clean
2026-09-09 05:27:12 UTC  checkpoint 65  commit 93d3724  fix N06
2026-09-09 05:27:14 UTC  checkpoint 65  commit 98e31a6  fix N07
2026-09-09 05:28:05 UTC  checkpoint 65  commit 69c7de3  fix N08
2026-09-09 05:32:17 UTC  checkpoint 65  commit dc0f094  T5 done
2026-09-09 05:32:19 UTC  checkpoint 65  commit 08fd837  T6 done
2026-09-09 05:32:20 UTC  checkpoint 65  commit d6cca4f  round 65 complete: v7.6 shipped

## Round 65 ledger (closed 2026-09-09 06:05:20 UTC)

Request: Rebuild round 65 after container loss: (1) row charts sized by measuring, not weights; (2) one-finger pan on a zoomed chart + press-and-hold scrub; with the M01-M08 fixes designed in from the start and the 3 tests the old suite could not catch.

Tasks 7/7 done, findings 8/8 fixed

- [x] T0  Baseline: round-64 tree (29bd6bf) compiles and 663 tests green in the new container  - cold container rebuilt: SDK reinstalled, 29bd6bf compiles, 663/663 green - matches the handover
- [x] T1  Row charts sized by measuring the text, not by weight: ~215dp of chart, no gap  - TextThenChart measuring layout: TJ's row 125dp -> 186.5dp of chart, no gap; floor is round 64's own share so no row is ever worse; unbounded-width branch tested
- [x] T2  One-finger pan on a zoomed chart; press-and-hold always scrubs; caption names the live gestures  - one-finger pan on a zoomed chart, press-and-hold scrub with a haptic tick, vertical drags handed back to the page; M01-M04, M07, M08 designed in
- [x] T3  Tests: 350ms vertical rest still scrolls; slow drag pans not scrubs; caption never promises a dead pan  - PanGestureUiTest: 9 tests including the 3 the old suite could not catch (350ms rest still scrolls, slow drag pans, caption never promises a dead pan)
- [x] T4  REGRESSION: full suite, lint, checkinit  - 679 tests green, lint vital clean, checkinit ok
- [x] T5  SWEEPS: adversarial bug hunt, repeated until a pass finds nothing above cosmetic  - 4 sweeps: 8 findings (1 high, 3 med, 4 low), all closed; sweep 4 found one low
- [x] T6  Ship v7.6 (versionCode 63) + final checkpoint  - v7.6 (versionCode 63) built and signed with the same cert as v7.5; 679 tests green, lint vital clean, checkinit ok
- [x] N01 (high) The hold armed on UNZOOMED charts too, where a drag already scrubs: it bought nothing and made the chart start consuming before touch slop, so a press that paused then scrolled could stop the page - on the one chart shape that never needed the gesture  - hold is only armed where canPan is true, so an unzoomed chart behaves exactly as v7.5 did
- [x] N02 (low) chartPinching in DetailScreen and onZoomingChanged's KDoc both still say 'two fingers'; a one-finger pan raises them now  - chartPinching and onZoomingChanged both describe a window gesture now, not two fingers
- [x] N03 (med) The gesture caption grew by up to 27 characters, and the full-screen viewer divides a fixed height between the plot and the text under it - an extra wrapped line at a large font scale is exactly round 64's H04 coming back  - worst-case caption (zoomed + SPY overlay + 1.5x type) measured in the full-screen viewer
- [x] N04 (low) The event-path hold test reused holdPossible, which was computed from the previous frame's nearDown - stale by one event  - the event path now evaluates the full condition against the current frame
- [x] N05 (med) canPanNow did not require onWindow: a chart given a window but no onWindow callback (both are optional parameters) would caption 'drag to move' and arm the hold, while pan() returns null and the drag scrubs - M03's fault from the other direction, and the same needless early consume as N01  - canPanNow now requires onWindow, and a test covers a zoomed chart that has nowhere to report a window
- [x] N06 (low) canPanNow is remembered on the onWindow LAMBDA, and DetailScreen builds a fresh one every recomposition, so the remember never hits; keying on whether it is null says what is actually meant  - keyed on whether onWindow is null, not on the lambda instance
- [x] N07 (med) The caption gained ', drag to scrub' on EVERY chart, including the unzoomed one where nothing about the gesture changed - longer text on the screen TJ reads daily, to describe behaviour he has had since v7.4  - the caption names the pan and the hold only on a zoomed chart; an unzoomed one reads exactly as it did in v7.5
- [x] N08 (low) nearDown was recomputed each frame, so a drag that wandered past the slop and came back could arm a hold - the 'has not really left where it landed' guarantee should only ever be lost, never regained  - nearDown is latched: it can only ever go false
2026-09-09 06:05:24 UTC  checkpoint 66  commit 359ea19  T0 doing
2026-09-09 06:18:05 UTC  checkpoint 66  commit a10a50a  T0 done
2026-09-09 06:18:07 UTC  checkpoint 66  commit 522793f  T1 done
2026-09-09 06:18:08 UTC  checkpoint 66  commit fd74074  T3 doing
2026-09-09 14:21:08 UTC  checkpoint 66  no-change  round 66 in progress: separator thickened, Worst-section decision measured
2026-09-09 14:21:59 UTC  checkpoint 66  commit 08fa011  recovered 12 audit findings from the interrupted workflow
2026-09-09 14:39:46 UTC  checkpoint 66  commit 11d6742  fix A01
2026-09-09 14:39:47 UTC  checkpoint 66  commit dcc6938  fix A03
2026-09-09 14:39:49 UTC  checkpoint 66  commit 8772e7c  A01 + A03 fixed: txns indexes heal on upgrade, ledger no longer drifts on an untouched save
2026-09-09 14:44:15 UTC  checkpoint 66  commit 3af5ff7  fix A02
2026-09-09 14:44:16 UTC  checkpoint 66  commit 80bd81a  fix A07
2026-09-09 14:44:18 UTC  checkpoint 66  commit f7eaf62  A02 + A07 fixed: refresh cadences survive backgrounding, backups no longer carry another phone's clock
2026-09-09 14:44:19 UTC  checkpoint 66  commit 201ef2f  T5 done
2026-09-09 14:53:28 UTC  checkpoint 66  commit e95ade2  T3 done
2026-09-09 14:53:30 UTC  checkpoint 66  commit 1f6388d  Worst section removed; 682 tests green
2026-09-09 15:00:13 UTC  checkpoint 66  commit 51a54c1  T2 done
2026-09-09 15:00:15 UTC  checkpoint 66  commit 8abe977  T4 doing
2026-09-09 15:00:17 UTC  checkpoint 66  commit cddced8  ETF section reworked: exposure dedup + return normalisation, 706 green
2026-09-09 15:03:01 UTC  checkpoint 66  commit 0425a07  fix A04
2026-09-09 15:03:03 UTC  checkpoint 66  commit a9318d4  fix A05
2026-09-09 15:03:05 UTC  checkpoint 66  commit 05ee2be  fix A06
2026-09-09 15:03:07 UTC  checkpoint 66  commit 87b95c6  fix A11
2026-09-09 15:03:08 UTC  checkpoint 66  commit a4c6fe5  fix A12
2026-09-09 15:06:34 UTC  checkpoint 66  commit 4b95a1e  fix A08
2026-09-09 15:06:35 UTC  checkpoint 66  commit 09e6c07  fix A09
2026-09-09 15:06:37 UTC  checkpoint 66  commit e397224  fix A10
2026-09-09 15:06:41 UTC  checkpoint 66  commit f73d2c0  T4 done
2026-09-09 15:06:43 UTC  checkpoint 66  commit 4ac4b6f  all 12 recovered audit findings fixed
2026-09-09 15:11:36 UTC  checkpoint 66  commit b23e4a3  fix B01
2026-09-09 15:11:38 UTC  checkpoint 66  commit b0c5b8a  Worst-deletion leftovers cleaned, 709 green
2026-09-09 15:15:10 UTC  checkpoint 66  commit 8ce0441  fix B02
2026-09-09 15:15:17 UTC  checkpoint 66  commit 7b87699  self-review caught a high-severity bug in this round's own refresh fix
2026-09-09 19:25:37 UTC  checkpoint 66  commit 63eadb3  recovered 14 more findings from the second interrupted audit
2026-09-09 19:31:57 UTC  checkpoint 66  commit 8ece093  fix E1
2026-09-09 19:31:58 UTC  checkpoint 66  commit ff1ff4b  fix E2
2026-09-09 19:32:00 UTC  checkpoint 66  commit 8f520e9  fix E3
2026-09-09 19:32:02 UTC  checkpoint 66  commit d4b80b5  fix E4
2026-09-09 19:32:03 UTC  checkpoint 66  commit 1e0225a  fix E5
2026-09-09 19:32:05 UTC  checkpoint 66  commit afee174  ETF audit findings E1-E5 fixed
2026-09-09 19:35:37 UTC  checkpoint 66  commit f17119a  fix R1
2026-09-09 19:35:39 UTC  checkpoint 66  commit e228f06  fix R3
2026-09-09 19:35:41 UTC  checkpoint 66  commit 3617198  fix R4
2026-09-09 19:35:42 UTC  checkpoint 66  commit 5590543  R1, R3, R4 fixed
2026-09-09 19:38:38 UTC  checkpoint 66  commit 47fcb5d  fix H1
2026-09-09 19:38:40 UTC  checkpoint 66  commit ce63fe2  fix H2
2026-09-09 19:38:42 UTC  checkpoint 66  commit a3e891c  fix H3
2026-09-09 19:38:44 UTC  checkpoint 66  commit 148277a  fix H4
2026-09-09 19:42:37 UTC  checkpoint 66  commit f090f3d  all recovered audit findings fixed: E1-E5, R1/R3/R4, H1-H4
2026-09-09 19:48:12 UTC  checkpoint 66  commit e7f89e8  fix R2
2026-09-09 19:48:14 UTC  checkpoint 66  commit b7f16f6  R2 fixed: priceless research rows filled on rebuild (+8 tests)
2026-09-09 19:51:16 UTC  checkpoint 66  commit effec14  R2 verified: 732 tests 0 failures, lint + checkInitOrder clean
2026-09-09 19:51:26 UTC  checkpoint 66  commit b89ea4f  14 doing
2026-09-10 00:26:38 UTC  checkpoint 66  commit 642bf60  portfolio-ui audit returned 7 findings (1 HIGH); detail-ui and research-ui agents died on session limit
2026-09-10 00:37:22 UTC  checkpoint 66  commit 7cafd42  fix PUI1
2026-09-10 00:37:23 UTC  checkpoint 66  commit 6d79806  fix PUI2
2026-09-10 00:37:25 UTC  checkpoint 66  commit 89f7bd2  fix PUI3
2026-09-10 00:37:26 UTC  checkpoint 66  commit d268110  fix PUI4
2026-09-10 00:37:28 UTC  checkpoint 66  commit 2728aa0  fix PUI5
2026-09-10 00:37:29 UTC  checkpoint 66  commit 9d16685  fix PUI6
2026-09-10 00:37:31 UTC  checkpoint 66  commit 76af40a  fix PUI7
2026-09-10 00:37:33 UTC  checkpoint 66  commit 523336d  fix AUD1
2026-09-10 00:37:34 UTC  checkpoint 66  commit b3797ee  All 7 portfolio-ui findings fixed + 1 found by the new ContrastTest; 3 new test files
2026-09-10 00:40:10 UTC  checkpoint 66  commit 1049444  751 tests 0 failures after the portfolio-ui round
2026-09-10 00:41:11 UTC  checkpoint 66  commit 2ceb8b5  ck: gc git before archiving, exclude jars, slim fallback under the upload limit
2026-09-10 00:59:17 UTC  checkpoint 66  commit 9684a0b  Audit batch 2: 8 ETF-scoring findings (3 HIGH) + 4 chart-gesture findings, all written to audits/round66/
2026-09-10 01:03:35 UTC  checkpoint 66  commit 74b756f  fix ETF1
2026-09-10 01:03:36 UTC  checkpoint 66  commit 26d83a1  fix ETF5
2026-09-10 01:03:37 UTC  checkpoint 66  commit c5f4a37  fix ETF6
2026-09-10 01:03:37 UTC  checkpoint 66  commit 57eae8d  fix ETF3
2026-09-10 01:03:38 UTC  checkpoint 66  commit edf8746  fix ETF7
2026-09-10 01:03:38 UTC  checkpoint 66  commit d3eb1c1  ETF scoring: 5 of 8 findings fixed, tests rewritten around the real invariants
2026-09-10 01:08:12 UTC  checkpoint 66  commit 38f62b0  fix ETF2
2026-09-10 01:08:13 UTC  checkpoint 66  commit fc51d6d  fix ETF4
2026-09-10 01:08:13 UTC  checkpoint 66  commit 6654c0d  fix ETF8
2026-09-10 01:08:14 UTC  checkpoint 66  commit 50a6ffb  All 8 ETF findings fixed. 770 tests, 0 failures
2026-09-10 01:16:03 UTC  checkpoint 66  commit 8ec4edb  fix CHT1
2026-09-10 01:16:04 UTC  checkpoint 66  commit 940916a  fix CHT2
2026-09-10 01:16:05 UTC  checkpoint 66  commit 7d960c6  fix CHT3
2026-09-10 01:16:06 UTC  checkpoint 66  commit f7a356e  fix CHT4
2026-09-10 01:16:06 UTC  checkpoint 66  commit 2050372  All 4 chart-gesture findings fixed; 4 new tests, each verified to fail on the pre-fix code. 775 tests, 0 failures
2026-09-10 05:25:30 UTC  checkpoint 66  commit b945829  research-ui audit recovered from disk: 8 findings (1 HIGH). Subagents rate-limited until 05:20 UTC - continuing solo
2026-09-10 05:35:09 UTC  checkpoint 66  commit b4a3862  fix RES1
2026-09-10 05:35:10 UTC  checkpoint 66  commit a0d963f  fix RES2
2026-09-10 05:35:11 UTC  checkpoint 66  commit 61d3885  fix RES3
