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
