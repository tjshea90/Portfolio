# Full-test audit 2026-09-24: network efficiency and HTTP/chart caching (N-*)

Read-only audit. No source edits, no network access (reasoned from the code and from the
2026-09-23 live observations: Yahoo's chart endpoint sends no ETag/Last-Modified, only
`cache-control: max-age=10`; Google News RSS and Nasdaq send `no-cache, no-store`).
Checked against HEAD `0b3b8e77`. Yesterday's N-1..N-11 fixes were re-read; see "Checked and
fine" at the end. Two of them are incomplete and are reported below (N-6, N-10's 403 half).

IN PROGRESS - findings below are verified; more may follow.

---

### N-1 [M] Cancelling a request does NOT disconnect the socket: the handler fires on job completion, not on cancellation
- where: `net/Http.kt:537-539` (get), `net/Http.kt:667-669` (postJson)
- what's wrong:
  ```kotlin
  val cancelWatch = coroutineContext[Job]?.invokeOnCompletion { cause ->
      if (cause != null) runCatching { connRef.get()?.disconnect() }
  }
  ```
  The public `Job.invokeOnCompletion(handler)` registers a handler that runs when the job is
  COMPLETE (`onCancelling = false`). A job whose body is blocked in `HttpURLConnection.read()`
  moves to *Cancelling* when cancelled, but it cannot *complete* until that blocking call
  returns - which is exactly what the handler was supposed to force. So the handler only runs
  after the read has already finished on its own (EOF or the 15-20 s read timeout), and the
  `disconnect()` it performs is a no-op. BRIEF's locked decision "Cancelling a request:
  disconnect the socket, not just drop the queued read" is not actually implemented; the
  file's own comment ("`disconnect()` is the one thing that unblocks that read") describes
  intent, not behaviour. No test covers it: `BackgroundTest` cancels a `delay()`, which is
  suspending, never a blocking socket read.
- failure scenario: (1) Feed pass in flight (7 market RSS + per-symbol feeds), user switches
  apps. `fgScope`/`feedJob` are cancelled, but every in-flight read keeps downloading to the
  end. Because the job is now inactive, the complete body then fails the `looksComplete`
  test (`coroutineContext[Job]?.isActive != false`) and is thrown away uncached - so it is
  paid for twice (now, and again in full on resume). (2) Slow/black-hole network: the zombie
  reads keep their per-host permit (`MAX_PER_HOST = 4`) for up to 15-20 s, so the resume
  pass queues behind them. (3) `refresh()`'s `finally` (which clears `loading`) cannot run
  until the blocked read returns; the resume path's `refresh()` hits the `_ui.value.loading`
  guard and returns - the exact "no replacement pass, stale prices until the next tick"
  case the comment at `PortfolioViewModel.kt:2903-2920` says cannot happen. `quotePassInterrupted`
  does not save it: it makes `setForeground(true)` call `refresh()`, which is the call that
  returns at the guard.
- suggested fix: make the disconnect fire on *cancelling*. Minimal options: (a) a sibling
  watcher inside the IO block -
  `coroutineScope { val w = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { connRef.get()?.disconnect() } }; try { ...blocking work... } finally { w.cancel() } }`
  (children are cancelled immediately on parent cancellation, and the watcher's `finally`
  runs on another IO thread, unblocking the read); or (b)
  `(coroutineContext[Job] as? JobSupport)`-free variant via `@OptIn(InternalCoroutinesApi::class) job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { ... }`.
- test: add a transport seam (a `(URL) -> HttpURLConnection` factory) or a local
  `ServerSocket` that accepts and never writes; launch `Http.get` against it, cancel after
  50 ms, and assert the call returns (throws `CancellationException`) within ~500 ms rather
  than after `timeoutMs`.
- confidence: high (documented coroutines semantics; the handler is the only disconnect path).

### N-2 [H] One symbol Yahoo's v7 endpoint does not know disables the batched quote for the whole session
- where: `net/MarketData.kt:248-274` (`batchYahoo`), `:121-125` (`batchFailures`), `:95`
  (`if (!batchDisabled)`); triggers from `ui/PortfolioViewModel.kt:2806-2841` (Detail scope
  = one symbol) and `:7766` (`fillPricesNow`)
- what's wrong: a chunk whose symbols Yahoo simply does not list comes back as a well-formed
  200 with `quoteResponse.result: []`. That parses to an empty list, so the loop tries the
  second host (same answer) and falls out as
  ```kotlin
  // Both hosts answered and neither gave anything usable. That IS a verdict.
  return Batch.FAILED to emptyList()
  ```
  and `batchFailures + 1`. The "partial success is a success" rule only protects multi-symbol
  batches; a ONE-symbol batch - which is what every Detail-scope tick sends - has no other
  symbol to succeed with. Three such ticks and `batchDisabled` is true process-wide until a
  manual pull (`resetBatchState`).
- failure scenario: Feed tab -> Trending -> tap a WSB "ticker" such as `SPX`/`VIX`/a junk
  token (`TrendingRow { onOpen(t.symbol) }`, `FeedScreen.kt:215`), or a stale/delisted
  watch entry, or a Finnhub-only search hit. The detail screen polls `[SYM]` every 15 s:
  2 requests per tick, FAILED x3 in 45 s. From then on every automatic tick on Portfolio or
  Watchlist skips the batch and runs the per-symbol chart chain for every holding - 20
  holdings at 15 s is ~80 query1 requests a minute, the "~70% of all traffic" shape the batch
  replaced - for the rest of the process, invisibly (only Settings' rate meter would show it).
  `fillPricesNow` for a rebuild whose priceless rows are all unknown tickers counts the same way.
- suggested fix: distinguish "the endpoint answered correctly and knows none of these
  symbols" from "the endpoint is broken". In `parseBatch`/`batchYahoo`, return
  `Batch.INCONCLUSIVE` when the body parses to a `quoteResponse` object with a `result` array
  (even empty) and no `error`; reserve `FAILED` for non-2xx (excluding held-off codes) and
  unparseable bodies. Optionally never count a verdict from a chunk of size 1.
- test: extract the verdict as a pure `batchVerdict(codes: List<Int>, bodies: List<String>)`
  and assert that `200 {"quoteResponse":{"result":[],"error":null}}` on both hosts is
  INCONCLUSIVE, while `500`/`200 <html>` on both hosts is FAILED.
- confidence: high on the mechanism; medium on how often Yahoo returns an empty 200 vs a 404
  for a given junk ticker (both paths reach FAILED anyway).

### N-3 [M] quoteSummary retries EVERY failure, not just a 401, and forces a crumb re-mint to do it
- where: `net/FundamentalsFeed.kt:199-243` (`yahooFetch`), used by `core`, `ratings`,
  `HoldingsFeed.holdings` (via `quoteSummary`); `net/YahooAuth.kt:69-101`
- what's wrong: the KDoc says "retrying once with a fresh crumb on a 401", but the loop is
  ```kotlin
  for (attempt in 0..1) {
      val crumb = YahooAuth.crumb(force = attempt > 0)
      ...
      for (host in listOf("query1", "query2")) { ...; if (r.code == 401) { invalidate(); break }; if (!r.ok) continue; ... }
  }
  ```
  When the host loop simply runs out (404, 5xx, `-1` timeout, a 200 that does not parse, or
  both hosts locally cooling), control still falls into `attempt = 1`, which calls
  `crumb(force = true)`. If the crumb is older than `MIN_INTERVAL_MS` (60 s) that is a full
  handshake - `fc.yahoo.com` plus `getcrumb` - replacing a perfectly good crumb, and then both
  hosts are asked again.
- failure scenario: a symbol quoteSummary 404s for (a crypto pair, an index, a delisted
  ticker, a fund lacking `earningsHistory`): `core()` = `yahooFetch(CORE_MODULES)` 2 + re-mint
  (1-3) + 2, then because 404 is `moduleRejected`, `yahooFetch(CORE_MODULES_PROVEN)` 2 + 2 =
  8 quoteSummary requests + a handshake, then Nasdaq x2 and Finviz. `loadHoldings` repeats the
  pattern (4 more). A black-hole network makes one `core()` call take ~60 s (four 15 s
  timeouts plus a 10 s mint). Under a Yahoo 429 on both hosts, attempt 1 still pings
  `fc.yahoo.com` once a minute.
- suggested fix: `break` out of the attempt loop unless the host loop saw a 401:
  track `var sawUnauthorized = false` and `if (!sawUnauthorized) break` after the host loop.
- test: make the fetch injectable (`yahooFetchWith(get: suspend (String) -> HttpResult, mint: suspend (Boolean) -> String)`)
  and assert: a 404 on both hosts makes exactly 2 requests and never calls `mint(true)`; a
  401 makes 2 + mint(true) + at most 2.
- confidence: high

### N-4 [M] SEC "10 requests/second" is enforced as 3 concurrent requests, not as a rate
- where: `ui/PortfolioViewModel.kt:231-232` (`MAX_PARALLEL_SEC = 3`), `:1650` (`secGate`),
  `net/Insider.kt:131-173` (`forSymbols`: N listings, then up to `MAX_DOCS_PER_PASS = 120`
  documents), `:421-451` (`marketWide`: 3 pages + up to 60 documents)
- what's wrong: the constant's own KDoc says "EDGAR publishes a hard 10 requests/second
  cap, so it gets its own smaller gate", but a `Semaphore(3)` bounds concurrency only. A
  Form 4 `.txt` is ~7 KB, so each request is latency-bound; at 100 ms per request three
  permits is ~30 requests/second, at 60 ms (Wi-Fi to Akamai) ~50/s.
- failure scenario: first Insider pass after install/restore/`INSIDER_CACHE` loss, or a
  vesting month, or "All companies" + "Load more": 20 listings + 120 documents are pushed
  through at 3x the published cap. SEC answers 403 ("Request Rate Threshold Exceeded") and
  blocks the IP for ~10 minutes; `Http` treats 403 as a 30 s cooldown (SEC sends no
  Retry-After), so the ladder re-probes at 30 s, 1 m, 2 m, 4 m during the block. The Insider
  tab shows nothing new for that window, and SEC.gov is the one host here with a published
  fair-access policy.
- suggested fix: put a minimum spacing in front of `secGate` - e.g. a `Mutex` + last-send
  timestamp that delays each SEC request to at least 125 ms after the previous one (8 req/s),
  shared by `listing`, `currentListing` and `fetch`. Keep the semaphore for concurrency.
- test: a pure pacing helper `nextSendAt(lastSendAt, now, minGapMs)`; assert 20 back-to-back
  calls span >= 19 x 125 ms.
- confidence: medium (the exceedance depends on real latency; the missing rate limit is certain)

### N-5 [L] YahooAuth.invalidate() can blank a crumb another caller JUST minted, then the 60 s guard keeps it blank
- where: `net/YahooAuth.kt:126-128` (`invalidate`), `:83`; callers `FundamentalsFeed.kt:229-232`,
  `MarketData.kt:258-263`
- what's wrong: `invalidate()` unconditionally sets `crumb = ""`. Several quoteSummary calls
  are in flight at once (portfolio fundamentals fan-out, enrich, holdings). If Yahoo rejects
  the old crumb, the first 401 re-mints; a second request that was sent with the OLD crumb
  and answers 401 a moment later calls `invalidate()` and wipes the NEW one. Its own
  `crumb(force = true)` then hits `n - mintedAt < MIN_INTERVAL_MS` and returns "", and so
  does every caller for the next 60 s.
- failure scenario: for up to a minute every batch quote returns INCONCLUSIVE ("no crumb"),
  so each 15 s tick falls back to per-symbol chart requests for every on-screen symbol (4
  ticks x N), and every quoteSummary in that window returns null (fundRetry backoff armed on
  healthy symbols).
- suggested fix: `fun invalidate(used: String) { if (crumb == used) crumb = "" }` (compare
  and clear under the same `gate`), passing the crumb the failed request was sent with.
- test: pure - mint A, mint B, `invalidate(used = A)` leaves B in place.
- confidence: medium (needs concurrent 401s; mechanism certain)

### N-6 [L] The N-9 "dead source" memo for Tradestie is probably inert on Android
- where: `net/Social.kt:99-103`; `net/Http.kt:626` (`HttpResult(-1, e.message ?: ...)`)
- what's wrong: the memo arms only when the failure body contains "certificate", "ssl" or
  "handshake":
  ```kotlin
  val sourceRefused = r.code >= 400 ||
      b.contains("certificate") || b.contains("ssl") || b.contains("handshake")
  ```
  but for a TLS failure `Http` returns `e.message`, not the exception class. On Android
  (Conscrypt) an expired server certificate surfaces as `SSLHandshakeException` whose message
  is typically "Chain validation failed" (cause chain: `CertPathValidatorException: timestamp
  check failed` -> `CertificateExpiredException`); an unknown CA gives "...Trust anchor for
  certification path not found." Neither contains any of the three words (the phrasing that
  does - "SSL certificate problem: certificate has expired" - is curl's, from yesterday's
  live check). No unit test exercises this path.
- failure scenario: exactly the pre-fix behaviour: one doomed DNS+TCP+TLS handshake per
  social pass (every 15 min while the Feed is visible), indefinitely.
- suggested fix: classify in `Http` by type - catch `javax.net.ssl.SSLException` (and
  `java.security.cert.CertificateException` in the cause chain) and return a distinct code
  such as `CODE_TLS = -2`; test `r.code == HttpResult.CODE_TLS` in `Social.tradestie`.
- test: pure classifier `failureCode(e: Throwable)`; assert an `SSLHandshakeException("Chain validation failed")` maps to `CODE_TLS`.
- confidence: medium (exact Android message not verifiable offline; the substring test
  demonstrably misses the JVM's own "PKIX path validation failed" phrasing too)

### N-7 [L] Watchlist baselines re-fetch a chart on every Watch-tab visit when the answer cannot exist yet
- where: `ui/WatchlistScreen.kt:132` (`LaunchedEffect(Unit) { vm.resolveWatchBaselines() }`),
  `ui/PortfolioViewModel.kt:3896-3967`, `:1213-1220` (`lastCloseInWindow`)
- what's wrong: for a symbol added TODAY, `dayStart == todayStart`, so
  `lastCloseInWindow(points, dayStart, todayStart)` filters on an empty window and must return
  null - yet `closeOnOrAfter` first calls `ChartFeed.series(symbol, D1)` every time. For an add
  on Saturday the window holds no session until Tuesday. It also goes straight to
  `ChartFeed.series`, bypassing `_charts`, `chart_cache`, the range TTL and `chartRetry`, so a
  symbol Yahoo cannot chart is re-requested (two hosts) on every visit forever.
- failure scenario: add three stocks in the morning; every switch to the Watch tab that day
  costs three 1d/5m downloads (two hosts each when they 404), none of which can resolve.
- suggested fix: in `closeOnOrAfter`, return null without fetching when
  `startOfDay(dateMs) >= todayStart` (or when no weekday session lies in `[dayStart, todayStart)`);
  route the fetch through a `RetryClock` keyed `"$sym|baseline"`.
- test: pure `baselineFetchWorthwhile(addedAt, now)`; false for same-day and Sat->Sun/Mon adds.
- confidence: high

### N-8 [L] Day Trading technicals have no failure memory: a symbol Yahoo cannot chart costs 4 requests every 30 s
- where: `net/DayTradingTechnicals.kt:269-273` (daily leg memo stores successes only),
  `:277`, `:340-363` (`fetchBars` tries both hosts); sweep at `ui/PortfolioViewModel.kt:7655-7662`
- what's wrong: `cachedDaily(dailyKey) ?: fetchBars(...)?.also { cacheDaily(...) }` only
  memoises a non-null result, and the intraday leg has no memo or backoff at all. For a
  symbol v8 answers 404 ("symbol may be delisted") each sweep sends daily q1+q2 and intraday
  q1+q2. `loadChart` is then correctly held back by `chartRetry`, but the technicals are not.
- failure scenario: a Claude-added Day Trading row with a ticker Yahoo does not carry is kept
  for the whole trading day (`carryExplanations`, `dayTrading = true`); with the tab open that
  is ~480 wasted query1/query2 requests an hour, all counting toward Yahoo's rate limit.
- suggested fix: a `RetryClock` keyed on the symbol around `DayTradingTechnicals.fetch` in
  `enrichDayTradingVisible` (skip the fetch while `blocked`, `failure` when both legs are null).
- test: VM-free - the gate expression as a pure function of (legs null?, retry state).
- confidence: high

### N-9 [L] `adoptRecentD1` republishes the chart and rewrites the quote row on every 30 s sweep
- where: `ui/PortfolioViewModel.kt:5333-5346`, `net/ChartFeed.kt:47-50` + `:191`
  (`fetched = System.currentTimeMillis()`), `:5127-5144` (`adoptAsSparkline`)
- what's wrong: `recentSeries` re-parses a body up to 25 s old and stamps `fetched` with the
  PARSE time, so `if (held != null && held.fetched >= fresh.fetched) return true` can never
  be true. Every sweep therefore replaces `_charts` (a new map, recomposing every chart
  observer), calls `chartRetry.success`, and `adoptAsSparkline`, which - when a quote exists
  for the symbol - replaces `_quotes` and runs `db.cacheQuotes(listOf(merged))` unconditionally.
  The KDoc promises "a 30-second sweep does not turn into a database write per row every 30
  seconds"; that holds for `cacheChart` only.
- failure scenario: Day Trading tab open with picks that are also held/watched (or whose
  detail was opened, so they are in `_quotes`): one SQLite quote write per such row per 30 s
  and a whole-app `_quotes`/`_charts` recomposition each sweep. Not network, but on the
  network path and contrary to the stated intent.
- suggested fix: have `RecentBodies.get` return the entry's `at` and stamp the parsed series'
  `fetched` with it; skip republishing when `held.fetched >= at`. Gate the quote write in
  `adoptAsSparkline` on the series actually differing from `q.spark`.
- confidence: high

### N-10 [L] Claude POST: a 403 is still treated as a throttle and its error body discarded (the untouched half of yesterday's N-2)
- where: `net/Http.kt:682-685`; `net/Claude.kt:523-535` (`apiError`)
- what's wrong: `postJson` returns `HttpResult(code, "")` for 403 and arms `noteRateLimited`.
  Anthropic's 403 is `permission_error` (key/workspace cannot use that model or feature), not
  a rate limit. `apiError(403, "")` prints "API error 403: " with no message, and a retry
  within 30 s gets `-429` "rate limited, backing off". Separately, `extractTransactions`
  (`Claude.kt:204`) still uses the 180 s default read timeout for a non-streamed 8,000-token
  vision reply (the N-2 fix raised only the three 16k calls to 600 s).
- failure scenario: user picks a model their key cannot use -> sees an empty "API error 403",
  then "rate limited" on the next tap; the real cause is never shown. A large screenshot batch
  near 8k output tokens can time out client-side while the server finishes and bills.
- suggested fix: in `postJson`, arm the cooldown only for 429/503 (and 529) and always read
  `errorStream` into the body; pass `timeoutMs = LONG_REPLY_TIMEOUT_MS` from `extractTransactions`.
- confidence: high (403 path); medium (timeout risk depends on model speed)

### N-11 [L] `refreshInsiders` returns before stamping the symbols EDGAR answered when the pass found no filings
- where: `ui/PortfolioViewModel.kt:4656` (`if (filings.isEmpty()) return`) vs `:4669-4670`
- what's wrong: since N-7 the pass knows exactly which listings were answered, but the early
  return for an empty result still runs first, so a pass where every listing answered "no
  Form 4 this month" stamps nothing.
- failure scenario: a small or quiet portfolio/watchlist: the 30-minute pass lists every
  symbol, finds nothing, and then each detail-screen open re-lists that symbol (one EDGAR
  request per open) because `insiderAt` was never set.
- suggested fix: move `answered.forEach { insiderAt[it.uppercase()] = now }` above the
  `filings.isEmpty()` return (an outage leaves `answered` empty, so nothing is stamped for it).
- confidence: high

### N-12 [L] `DayTradingEval.fetchDaySeries` turns a real "no bars" answer into a second request and a null
- where: `net/DayTradingEval.kt:62-71`
- what's wrong: KDoc: "Null means the request itself failed; an EMPTY list is a real answer".
  Code: `if (bars.isNotEmpty()) return bars` - an empty 200 falls through to the other host,
  then `return null`. Every session with no bars (aged out, thin ticker, halted) costs two
  requests per press and is indistinguishable from a network failure for the caller.
- suggested fix: `if (r.ok) return parseBars(r.body)` after a body that parsed to a
  `chart.result` (even with no bars); keep `continue` for a truncated/unparseable body.
- confidence: high

---

## Code quality (N-Q)

### N-Q1 `conditionalKey = true` on Yahoo chart URLs costs a SQLite read per request that can never hit
- `net/ChartFeed.kt:61`, `net/DayTradingTechnicals.kt:351`, `net/DayTradingEval.kt:62`. Yahoo
  sends no validators, so nothing is ever stored, but `Http.get` still calls `cachedFor(url)`
  -> `disk.load(url)` (a `SELECT` on `http_cache`) before every chart/tech request - ~20 a
  minute with the Day Trading tab open. Drop the flag on these three (the comments already
  say it is inert) or skip the disk read when the in-memory map has no entry and a
  per-host "never sends validators" flag is set.

### N-Q2 `RecentBodies` claims the row sparkline as a consumer; `MarketData.yahoo` never reads or writes it
- `net/RecentBodies.kt` KDoc lists three consumers; `MarketData.yahoo` (used by `sparkline()`
  and the quote fallback) builds the identical URL but bypasses the memo. Either route it
  through `RecentBodies.get/put` (cheap, same key) or correct the KDoc.

### N-Q3 The rate meter counts requests the in-permit cooldown re-check (N-3 fix) then declines to send
- `net/Http.kt:499` counts before the permit; `:522-526` can then return `CODE_COOLDOWN`
  without sending. Move `noteRequest(host)` inside the permit after the re-check. `postJson`
  also lacks the in-permit re-check (`:661`).

### N-Q4 `batchFailures` is a non-atomic read-modify-write shared by concurrent `quotes()` calls
- `net/MarketData.kt:121-125`; `refresh()` and `fillPricesNow` can run together. Use an
  `AtomicInteger` (cosmetic today, but it is the counter that disables the batch - see N-2).

### N-Q5 The Yahoo cookie jar is installed process-wide with `ACCEPT_ALL`
- `net/YahooAuth.kt:45-54`. Every `HttpURLConnection` in the app now stores and replays
  cookies for every host (Google News `NID`, SEC, Nasdaq, Finviz...), which makes those
  requests linkable and slightly larger, for no benefit - only Yahoo needs the A3 cookie. A
  `CookiePolicy { uri, _ -> uri.host.endsWith("yahoo.com") }` keeps the handshake working.

---

## Ideas - need Tj's approval, do NOT implement

- **Carry analyst consensus across Research rebuilds.** `analystDone.clear()` plus fresh
  rows with `consensus = null` mean every 30-minute rebuild re-asks Nasdaq for ~20 symbols,
  most of them the same as last time (`PortfolioViewModel.kt:6934`, `enrichPass`). A
  per-symbol consensus memo with a ~12 h life, re-blended via `ResearchScore.withAnalyst`,
  would make most rebuilds cost zero Nasdaq requests.
- **One quoteSummary per stock instead of two on first open.** `loadHoldings` asks
  `topHoldings,fundProfile,quoteType` for every symbol opened, mostly to learn "not a fund";
  adding `quoteType` to the core module set would answer that from the request `loadFundamentals`
  already makes, and holdings could be fetched only for ETFs/funds.
- **Finality for non-intraday charts while closed.** `intradayChartIsFinal` covers D1 and
  OVERNIGHT only; D5 and M1 (30-minute TTL) are re-downloaded on every resume after 30 min
  over a weekend although no candle can change until Monday. Same rule as `sparkIsFinal`
  (fetched after the last close and no session opened since) would stop that.
- **Persist `insiderAt` and `deepNewsAt`.** Both are memory-only, so every cold start
  re-lists EDGAR and re-runs the 3-4 source deep news pull for every stock opened, even if it
  was opened five minutes before the process died.
- **A transport seam in `Http`** (injectable connection factory) so cancellation (N-1),
  the in-permit cooldown re-check and 304/validator handling can be unit-tested on the JVM.

---
IN PROGRESS
