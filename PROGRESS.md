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
