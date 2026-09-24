# Lifecycle, battery/resource use and concurrency audit — 2026-09-24

Auditor: L- (lifecycle / battery / concurrency), read-only pass. First dedicated pass on this
area. Line numbers are as of the tree at audit time (the parent session was editing
`net/Http.kt` concurrently — T-1 offline gate — so Http.kt lines are post-T-1).

Status: complete (resumed after the 07:43 usage cap; L-1..L-6 were written before it, L-7 and
the sections after it were added on resume).

## Findings

### L-1 [H] Cancelling a request never reaches the socket: the disconnect handler only fires after the blocked read has already finished
- where: `net/Http.kt:555` (`get`) and `net/Http.kt:686` (`postJson`)
- what's wrong:
  ```kotlin
  val cancelWatch = coroutineContext[Job]?.invokeOnCompletion { cause ->
      if (cause != null) runCatching { connRef.get()?.disconnect() }
  }
  ```
  The public `Job.invokeOnCompletion(handler)` registers an `InvokeOnCompletion` node whose
  `onCancelling` is `false` (verified in the shipped kotlinx-coroutines-core-jvm 1.10.2 bytecode:
  `InvokeOnCompletion.getOnCancelling()` returns `0`). Such a handler runs when the job reaches
  its FINAL state, not when it is cancelled. The job here is `Http.get`'s own
  `withContext(Dispatchers.IO)` coroutine, and it cannot complete while its body is blocked in
  `conn.responseCode` / `read()` — so on `fgScope.cancel()` the job sits in "cancelling" until
  the blocking read returns on its own (whole body downloaded, or the 15 s read timeout per
  stalled read), and only THEN does the handler call `disconnect()` on a connection the
  `finally` is already closing. The locked BRIEF decision "Cancelling a request: disconnect the
  socket" is therefore not implemented at all, although both the code comment (lines ~545-555)
  and the BRIEF describe it as working. `BackgroundTest` only cancels a `delay()`, which is
  cooperative, so nothing tests the blocking-read case.
- failure scenario: open a stock and tap its Analysts tab (≈195 KB `quoteSummary` body), or be
  mid feed pass (up to 4 sockets per host across ~8 hosts), then press Home. `setForeground(false)`
  cancels `fgScope`, but every in-flight request keeps downloading to the end (or stalls up to
  its timeout on a bad cell link) with the radio held up, and the result is then thrown away
  (withContext resumes into a cancelled parent). Secondary effect: every in-flight guard
  (`_ui.loading`, `_chartLoading`, `_newsLoading`, `_fundLoading`, `_holdingsLoading`,
  `_feedLoading`) stays set for the whole of that time — see L-2 for what that does on return.
  Blast radius is bounded per request (a request does end), but it is exactly the waste the
  locked decision exists to prevent, and on Android 14+ the cached-app freezer can freeze the
  process mid-read, stretching it until the next unfreeze.
- suggested fix: register for the CANCELLING transition, e.g.
  `@OptIn(InternalCoroutinesApi::class) coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { ... }`,
  or without the internal API, a watcher child inside the `withContext` block:
  `val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { connRef.get()?.disconnect() } }`
  and `watcher.cancel()` in the existing `finally`. Same change in `postJson`.
- missing test: `HttpCancelTest.cancellingAStalledReadReturnsPromptly` — a loopback
  `ServerSocket` (loopback is still allowed by the new T-1 gate) that sends headers plus part of
  a body and then stalls; launch `Http.get(url, timeoutMs = 10_000)`, cancel after ~200 ms,
  assert the job completes in well under 1 s. Fails today (completes only at the 10 s timeout).
- confidence: high

### L-2 [M] Coming back while a cancelled quote pass is still unwinding drops the replacement refresh (Round 66's `quotePassInterrupted` fix is defeated)
- where: `ui/PortfolioViewModel.kt:2746` (`refresh()` guard) with `setForeground` `:3309-3311`
- what's wrong: `setForeground(false)` records `quotePassInterrupted = _ui.value.loading` and
  cancels `fgScope`; `setForeground(true)` then calls `refresh()` because the pass was
  interrupted. But `refresh()` opens with
  ```kotlin
  if (_ui.value.loading) { if (manual) ...; return }
  ```
  and `loading` is only cleared in the cancelled pass's `finally`, which cannot run until that
  pass's blocking read returns (L-1: seconds, up to the 15 s timeout). The return-path
  `refresh()` therefore returns at the guard, `quotePassInterrupted` has already been reset to
  false, and the cancelled pass's result is discarded when it finally unwinds. The code's own
  comment at `:2912-2918` describes this exact window ("the resume-path `refresh()` can hit the
  `loading` guard and return BEFORE the cancelled pass's `finally` clears the flag. No
  replacement pass then runs") but the fix there only addressed the error text.
- failure scenario: market in extended hours, quote request slow (2-5 s on cell). Pull down the
  shade / glance at a message for 2 s and come back → resume `refresh()` no-ops, old pass
  unwinds and is thrown away → prices stay as of before the glance for a full interval (≥1 min
  extended, 15 min closed-but-not-final). Same shape for `loadChart`/`loadNews`/
  `loadFundamentals`/`loadHoldings` via DetailScreen's ON_START observer: each hits its
  still-set in-flight marker and returns, so a detail screen reopened quickly after leaving it
  keeps whatever was on screen until the next tick/open.
- suggested fix: gate re-entry on the JOB, not the flag — keep `private var quoteJob: Job?` and
  test `quoteJob?.isActive == true` (a cancelled job reports `isActive == false` immediately),
  or clear `loading` (and the per-symbol in-flight sets) synchronously in `setForeground(false)`
  right after the cancel. Fixing L-1 shrinks the window to milliseconds but does not close it.
- missing test: a ViewModel-level test that backgrounds with `loading = true`, foregrounds
  before the pass unwinds, and asserts a new quote pass was started.
- confidence: high (window size depends on L-1)

### L-3 [M] A Claude answer that lands while the app is in the background never gets its added rows priced (or its leveraged-ETF filter applied)
- where: `ui/PortfolioViewModel.kt:7743` `fillResearchPrices` (`fgScope.launch { fillPricesNow(symbols) }`),
  called from `applyResearchAnswer` `:7324` and `applyDayTradingAnswer` `:7462`; the callers
  run inside `explainResearch` `:7225` / `explainDayTrading` `:7387` on `viewModelScope`.
- what's wrong: the Claude call deliberately runs on `viewModelScope` (read timeout 180 s) so it
  survives backgrounding. When it returns, `applyResearchAnswer` → `fillResearchPrices` launches
  into `fgScope` — which is the CANCELLED scope if the user has left the app while waiting. A
  launch into a cancelled scope silently never runs. Nothing re-issues the fill on return:
  `setForeground(true)` does not, and the Research screen's `LaunchedEffect(section, busy)` →
  `loadResearch()` sees a fresh list (`explained` counts as fresh) and only runs `enrichVisible`
  (Best analyst pass, no price fill); `loadEtfs()` returns on TTL. `fillPricesNow` is also where
  `dropLeveraged`/`EtfExposure.dedupe` are applied to Claude-added funds (`:7784-7810`, "a prompt
  is a request and this is the guarantee"), so that guarantee is skipped too.
- failure scenario: tap "Explain with Claude" on Research, switch to another app while it thinks
  (it takes tens of seconds to minutes), come back → Claude's added Trending/Best rows show no
  price / 0% change until the next 30-minute rebuild; Claude-added ETFs (including a 3x fund,
  if Claude named one) sit priceless and unfiltered until the 6-hour ETF rebuild. Day Trading
  picks heal only if their tab is open (the live loop supplies a price).
- suggested fix: make the fill survive: e.g. in `fillResearchPrices`, when `!foreground`, add
  the symbols to a `pendingPriceFill` set and flush it from `setForeground(true)`; or have the
  non-stale branch of `loadResearch()` call `pricelessRows(...)` + fill (it is one batched
  request). Do not move the fill to `viewModelScope` (that would fetch in the background).
- missing test: apply a research answer with a new symbol while the VM is backgrounded, then
  foreground, assert a fill is requested / the row is priced.
- confidence: high

### L-4 [M] The memory-trim handler is unreachable on the target phone (Android 16): Android 14+ only delivers levels 20 and 40, both below the locked threshold of 60
- where: `ui/PortfolioViewModel.kt:1983-2005` (`onTrimMemory`, `if (level < TRIM_MODERATE) return`),
  `util/MemoryTrim.kt` (`installProcessWide`, `onLowMemory` → `SEVERE`), `net/Http.onLowMemory`.
- what's wrong: in `android-36/android.jar` (and its stub sources) `TRIM_MEMORY_MODERATE`,
  `TRIM_MEMORY_COMPLETE` and all three `TRIM_MEMORY_RUNNING_*` are `@Deprecated`, as is
  `ComponentCallbacks.onLowMemory()`; the platform docs state apps are not notified of those
  levels since API 34 — only `TRIM_MEMORY_UI_HIDDEN` (20) and `TRIM_MEMORY_BACKGROUND` (40) are
  delivered. The handler returns for anything below 60, so on Tj's Moto G (API 36) it never
  releases anything: sparklines, `_charts`, `_news`, `_fundamentals`, `_holdings`, `_feed`, the
  ~3 MB `Http` heap cache, the symbol-search memo are all held for the life of the process. The
  whole `MemoryTrim` mechanism is dead code on the device it ships to.
- failure scenario: use the app (open a dozen stocks and a few chart ranges), press Home → the
  process keeps its full heap while cached; nothing is ever given back, which is the opposite of
  Tj's "don't hold RAM while not in use" goal and makes the process a likelier kill candidate
  (cold start next time).
- suggested fix: NEEDS TJ'S APPROVAL — the threshold is a locked BRIEF decision. See Ideas #1
  for a version that releases only what is invisible on return at level 40 (heap-only speed
  caches with disk backing), which respects the reason the lock exists (no blank charts/feed on
  every app switch).
- confidence: high (SDK 36 deprecates exactly these levels; the "not notified since API 34"
  behaviour is the documented reason)

### L-5 [L] Every return to the app re-reads and JSON-parses the whole `quotes` table whenever any quote lacks a sparkline
- where: `ui/PortfolioViewModel.kt:2095` (`restoreFromCache` → `restoreSparklines()` on every
  `setForeground(true)`), `:2123-2141`
- what's wrong: `restoreSparklines` exists to repair a memory trim, but it runs on every resume
  and its only early-out is "no quote is missing a spark". A tracked symbol Yahoo never supplies
  a series for (thin ticker, fund, freshly added watch entry, or any row after the closed-market
  spark skip) defeats that check permanently — the init-path comment at `:2087-2094` already
  documents this — so each resume does `db.cachedQuotes()` (every row, every spark blob parsed)
  and then finds nothing to change. And since L-4 means the trim never runs on the phone, there
  is never anything for it to restore.
- failure scenario: one watchlist symbol without a series → every app switch costs a full
  quotes-table read + JSON parse on IO, for nothing.
- suggested fix: set a `sparksTrimmed = true` flag in `onTrimMemory` and only call
  `restoreSparklines()` when it is set (clear it after).
- confidence: high

### L-6 [L] `onCleared` can detach the NEXT ViewModel's HTTP disk cache
- where: `ui/PortfolioViewModel.kt:5747-5749`
- what's wrong: `Http.attachDiskCache(null)` unconditionally clears the process-wide slot. When
  the activity is finished (double back at the root) and the app is reopened before the old
  activity's `onDestroy` has run — `onDestroy` of a finishing activity is deferred until the
  next activity is idle — the new ViewModel's `init` attaches its cache first and the old one's
  `onCleared` then nulls it.
- failure scenario: back-back to exit, immediately re-tap the icon → for the rest of that
  process's life `Http` is memory-only: validators/bodies are neither read from nor written to
  `http_cache`, so the 195 KB analyst payload and every RSS feed re-download in full.
- suggested fix: keep the adapter in a field and detach only if it is still the attached one
  (`Http.detachDiskCache(this.cacheAdapter)` doing a compare-and-clear).
- confidence: medium (depends on the platform's destroy ordering; plausible, not reproduced)

### L-7 [L] Leaving the app with a symbol search in flight leaves the search spinner stuck on
- where: `ui/PortfolioViewModel.kt:6157-6184` (`searchSymbols`) with `setForeground(false)` (`searchJob = null`, `:3352` at time of writing)
- what's wrong: the `finally` clears the spinner only `if (searchJob === me)`. `setForeground(false)`
  cancels `fgScope` and then sets `searchJob = null`. If the search was suspended in `delay(220)`,
  the cancellation resumes it inline on Main before the null assignment, so it is fine — but if
  it was in `withContext(Dispatchers.IO) { SymbolSearch.query(...) }` (a real request, which L-1
  keeps blocked for its full duration), the `finally` runs later, sees `searchJob == null`, and
  leaves `_searching == true`. The comment in the same `finally` describes exactly this stuck
  spinner as the bug it fixes (`SpinnerTest`).
- failure scenario: type a ticker in Search, press Home while results load, return → the sheet
  (still open, `rememberSaveable`) shows a spinner that never stops, no results and no "No
  matches" text, until another character is typed or the sheet is closed.
- suggested fix: don't null `searchJob` in `setForeground(false)` (its identity is what the
  `finally` compares), or clear with `if (searchJob === me || searchJob == null)`.
- confidence: high

## Code quality (L-Q)

### L-Q1 The code comment at `net/Http.kt:~545-555` and BRIEF's "Cancelling a request: disconnect the socket" row describe behaviour that does not happen (L-1). Update both with the fix, and name the new test in the comment.

### L-Q2 `CrashLog.install` runs only from `MainActivity.onCreate` (`MainActivity.kt:96`). A process started by a share goes through `ShareImportActivity` (raw `Thread`, `ShareImportActivity.kt:34`) first, so a crash there — or in anything before `MainActivity` exists — leaves no trace. Installing it from both activities (it is idempotent) closes the gap.

### L-Q3 `refresh()` / `loadChart` / `loadNews` / `loadFundamentals` / `loadHoldings` all use "is my marker set" as the re-entry guard for work that runs on a scope which can be cancelled out from under it; L-2 and L-7 are two instances of the same shape. A small helper that keys re-entry on `Job.isActive` would remove the class of bug rather than one instance.

## Verified clean (checked, nothing to fix)
- Lifecycle wiring: ONE `LifecycleEventObserver` in `App()` (`MainActivity.kt:381-392`), removed on dispose; `setForeground` has an equality guard, so repeated ON_START cannot duplicate loops; `startAuto` cancels the old `autoJob` before relaunching; the day-trading loop is restarted from `setForeground(true)` via `dayTradingLiveWanted` and stopped by `DisposableEffect.onDispose` (forget-before-remember ordering makes Research↔Detail handoffs correct). `LifecycleRegistry` dispatches ON_START in registration order, so `App`'s `setForeground(true)` (new `fgScope`) always runs before `DetailScreen`'s ON_START reloads.
- `autoJob` (viewModelScope) and `feedJob` are cancelled on ON_STOP and the loop also breaks on `!foreground`; the loop idles (no network) on `VisibleScope.None`, offline, or `pricesAreFinal()`. No other `while`/`delay` polling exists in the VM besides the day-trading loop (clock- and network-gated, on `fgScope`).
- Compose side: no ticker/clock loops, no infinite transitions; `Refreshable`'s frame-clock watchdog runs only while the circle is stranded and for at most 300 ms; Compose's frame clock is paused on ON_STOP anyway. Indeterminate progress indicators are all driven by flags cleared in `finally`. WebView: `onPause`/`pauseTimers` on stop, `destroy` on dispose.
- Manifest: no services, receivers, alarms, wake locks or WorkManager; only `MainActivity` (launcher) and the `ShareImportActivity` trampoline are exported; `FileProvider` not exported. `onNewIntent` + CLEAR_TOP|SINGLE_TOP reuse the one ViewModel; import runs after `onStart` because its first suspension posts back to Main.
- Concurrency: every `_x.value = _x.value...` read-modify-write in the VM runs on Main (scripted scan of all IO/Default blocks found only plain assignments); cross-thread maps are `ConcurrentHashMap`/synchronized; `Http`'s counters are `@Synchronized`; `Db` uses WAL, the settings cache has the documented lock-order fix. `Connectivity` registers no callback (nothing to leak). `MemoryTrim` holds listeners weakly on the application context.
- Init order: `tools/checkinit.py` passes; an additional scripted check found no pre-`init` property initialiser that reads a later-declared property, and no `by lazy`/delegates in the class.

## Ideas — need Tj's approval, do NOT implement
1. **Give memory back at TRIM_MEMORY_BACKGROUND (40) without blanking anything** (follows from L-4). Release only heap copies that are invisible on return because they are speed caches in front of disk: `Http`'s in-memory conditional cache (~3 MB), `SymbolSearch` memo, `storyKeys`, `insiderDocs`. Leave sparklines, charts, feed and news alone (the reason for the locked 60 threshold). This changes a locked BRIEF row, hence approval.
2. **Move `init`'s synchronous SQLite work off the first frame** (`db.cachedQuotes()` parsing every spark blob, `recompute()` replaying the ledger, `loadPendingImport`). Startup-latency only; not a battery issue.

## Summary

| Severity | Count | IDs |
|---|---|---|
| H | 1 | L-1 |
| M | 3 | L-2, L-3, L-4 |
| L | 3 | L-5, L-6, L-7 |
| Code quality | 3 | L-Q1, L-Q2, L-Q3 |
| Ideas (approval) | 2 | — |

Top 3: L-1 (socket never disconnected on cancel — locked decision not actually implemented),
L-3 (Claude answer landing while backgrounded never gets its rows priced / leveraged-ETF filter),
L-4 (trim handler dead on Android 14+, so nothing is ever released).

## END OF REPORT (complete)
