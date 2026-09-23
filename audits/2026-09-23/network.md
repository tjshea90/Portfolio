# Full-test audit 2026-09-23: network efficiency and caching (N-*)

Research only. No source edits. Every finding below was checked against the code at HEAD
`b44f94f`. Yesterday's N-M1..N-L8 fixes were re-read and hold up. N-L6 is the one exception
and it is covered in N-7. Live header checks were made from this container on 2026-09-23.
Yahoo `query1/2` quoteSummary, getcrumb and feeds.finance.yahoo.com all answered 429, so
those three endpoints could not be checked.

---

### N-1 [M] Day-trading sweep downloads the same Yahoo 1d/5m body twice per row, and relies on a 304 that Yahoo never sends
- where: `ui/PortfolioViewModel.kt:7307-7313` (sweep), `net/DayTradingTechnicals.kt:277` + `:340-348`
  (intraday leg), `net/ChartFeed.kt:37-42` (D1 chart), `net/MarketData.kt:387-388` (sparkline).
  The false premise is also in `net/DayTradingEval.kt:59-63`.
- what's wrong: for each row, on every 30-second tick, the sweep does this:
  ```kotlin
  val chartJob = withContext(Dispatchers.Main) {
      loadChart(row.symbol, com.tj.portfolio.data.ChartRange.D1)
  }
  chartJob?.join()
  row.symbol to runCatching { com.tj.portfolio.net.DayTradingTechnicals.fetch(row.symbol) }
  ```
  and `fetch` always does this:
  ```kotlin
  val intradayAll = fetchBars(symbol, range = "1d", interval = "5m", prePost = true)
  ```
  That builds `.../v8/finance/chart/SYM?range=1d&interval=5m&includePrePost=true`. The URL is
  byte-for-byte the same as `ChartFeed.series(sym, D1)`
  (`D1("1D", "1d", "5m", true, ...)`) and the same as `MarketData.yahoo()`. The sweep
  therefore asks for one body through two paths. The intraday leg's comment says the repeat
  is cheap:
  ```kotlin
  // CONDITIONAL, like every other repeat request in the app. Without a validator
  // key an unchanged series is re-sent in full on every tick; with one it is a
  // bodyless 304 that `Http` answers from `http_cache`.
  ```
  That is not what happens. Checked live on 2026-09-23: Yahoo's chart endpoint sends
  **no `ETag` and no `Last-Modified`**. It sends only `cache-control: public, max-age=10`
  (`range=1d/5m`, `range=3mo/1d` on query1 and query2). `Http.get` stores an entry only when
  `(!etag.isNullOrBlank() || !lm.isNullOrBlank())`. So `conditionalKey = true` does nothing on
  every chart URL, and each tick is a full download. `DayTradingEval.fetchDaySeries` makes the
  same claim ("the answer is immutable - the one case where a validator can never be wrong").
  It is equally inert there, so every "Check" press downloads every non-final row's session
  again. DATA_UNAVAILABLE rows are included, and they are retried on every press for 55 days.
- concrete scenario: Day Trading tab open during the session, default page of 10 rows. The
  intraday leg makes 20 full requests a minute (1,200 an hour) to query1/2. At every
  5-minute TTL lapse, `loadChart` adds 10 more requests for the same bytes: 120 duplicates an
  hour. The card's chart also runs up to 5 minutes behind a plan that was computed from a
  30-second-old copy of the same series. Bytes are small (about 10 KB raw, about 2.6 KB gzip
  per body, measured). The cost is request **rate** against Yahoo, which is the thing the app
  is built to protect.
- suggested fix (minimal): the tech fetch already has the body. Build the D1 `ChartSeries`
  from it with `ChartFeed.parse(sym, ChartRange.D1, body)` and publish/cache it like
  `loadChart` does, including `adoptAsSparkline`. Call `loadChart` in the sweep only when the
  intraday leg failed. Correct the three "304" comments so nobody leans on them again. Also
  consider a short memo of about 25s, keyed by URL, for the intraday body, so the three
  consumers of this URL share it.
- test: make `fetchBars` return the raw body as well, then add a pure test that the D1 series
  built from a fixture body equals `ChartFeed.parse` of the same fixture. In the VM, a seam
  counting `ChartFeed.series` calls per sweep should read 0 when the tech fetch succeeded.

### N-2 [M] Claude calls: 180 s read timeout against 16,000-token non-streaming replies, and a timeout triggers a second billed request
- where: `net/Http.kt:644` (`timeoutMs: Int = 180000`), `:665`; `net/Claude.kt:302/316-320`,
  `:342/356-360`, `:418/432-438`
- what's wrong:
  ```kotlin
  put("max_tokens", 16000)            // + web_search max_uses 8-10
  var r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
  if (!r.ok && useWebSearch && r.code != 401 && r.code != 429) {
      msg.remove("tools")
      r = Http.postJson("$BASE/messages", msg.toString(), headers(key))
  }
  ```
  The request is not streamed, so no bytes arrive until the whole reply is done. The
  `readTimeout` of 180 s is therefore a cap on total generation time. A 16k-token answer plus
  up to ten searches can run past 180 s. When it does, `postJson` returns `-1` and also calls
  `noteUnreachable`. `-1` passes the retry test, so a second full request goes out without
  tools, while the first has most likely finished and been billed on the server. 5xx and 529
  (overloaded) also get an immediate second request. A 400 caused by something other than the
  tool (prompt too long, bad model) is re-sent and fails again. A 403 is recorded in
  `noteRateLimited` as a throttle, so a permission error comes back later as "rate limited,
  backing off".
- concrete scenario: "Explain with Claude" on a long Research or Day Trading bundle with web
  search on. The first call times out at 180 s, the retry is sent, and the user pays for two
  runs, waits up to 6 minutes, and quite possibly gets an error at the end.
- suggested fix: retry without tools only when `r.code == 400` and the error body names the
  tool or web search. Never retry on `-1`, 5xx or 529. Pass a larger `timeoutMs` (for example
  600 000) for the 16k calls, or stream.
- test: extract the retry decision as a pure `shouldRetryWithoutTools(code, body)`. Assert
  false for -1, 500, 529 and 403, and true only for a 400 whose body mentions the tool.

### N-3 [L] Cooldown is checked before the per-host permit, not after, so queued requests still go out after a 429
- where: `net/Http.kt:489-495`, `:517`
- what's wrong: the host-state check runs once, before `gateFor(host).withPermit { ... }`:
  ```kotlin
  if (st != null && System.currentTimeMillis() < st.until) { return@withContext HttpResult(CODE_COOLDOWN, ...) }
  noteRequest(host)
  ...
  gateFor(host).withPermit { conn = URL(url).openConnection() ... }
  ```
  `MAX_PER_HOST = 4`, but the call-site gates stack on top of each other against query1:
  sparklines (5), day-trading sweep (5, chart plus tech), research screeners (4), fill-prices,
  and the recommendation/ratings fan-out. A request waiting on the permit when one of the four
  in-flight requests receives a 429 or 403 still opens its connection. The file's own comment
  says the permit is taken after the check "so a request that is never going to be sent does
  not queue behind four that are". That is true, but it misses the requests that queued before
  the 429 arrived.
- concrete scenario: a research rebuild overlaps the day-trading first sweep, which covers
  all 40 rows. Query1 answers 429 to one request. Every request already queued on the query1
  permit, which can be 5-10, is still sent into the throttle. The file's header says this is
  exactly how a throttle turns into a block.
- suggested fix: repeat the `hosts[host]?.until` check as the first line inside `withPermit`
  and return `CODE_COOLDOWN` from there.
- test: `Http` has no transport seam. Extract `shouldSend(hostUntil, now)` and assert it is
  consulted after the permit is acquired. Or use a test that arms a cooldown while a caller is
  blocked on the semaphore and asserts it returns `-429`.

### N-4 [L] A detail screen polls a row sparkline that no screen draws
- where: `ui/PortfolioViewModel.kt:2716` (`refreshSparklines(onScreen)`), `:2896-2913`
  (`heldByChart` looks at D1 only), `:286` (`is Detail -> listOf(scope.symbol)`). The only
  drawer of `q.spark` is `StockRowItem` (`StockRow.kt:199`), used only by Portfolio and
  Watchlist.
- what's wrong: in Detail scope, `refresh()` calls `refreshSparklines([detailSymbol])` on every
  tick. The skip covers only the case where a fresh D1 chart already holds the series:
  ```kotlin
  val c = chartsNow[chartKey(sym, ChartRange.D1)] ?: return false
  ```
  With the remembered range set to anything other than 1D, the 1d/5m series is downloaded
  every 5 minutes for a line the Detail screen does not draw. Round 58 replaced the detail
  sparkline with `PriceChart`, and the KDoc at `:2873-2875` still says the detail chart
  "is covered because symbolsToQuote returns that symbol". For a stock opened from Search
  (not held or watched), the line is never drawn anywhere. On a manual pull with range 1D, the
  sparkline pass and `loadChart(D1, force=true)` fetch the same URL together.
- concrete scenario: a stock's detail screen on 1Y during market hours sends 12 requests an
  hour for nothing, per open screen.
- suggested fix: only on Portfolio or Watchlist scope, call `refreshSparklines(onScreen)`.
  The row line is refreshed when the user returns (`setVisibleScope` then fires `refresh()`).
- test: extract the spark target list as a pure function of `VisibleScope`, then assert it is
  empty for `Detail`.

### N-5 [L] The compare benchmark (SPY) series never refreshes while the chart is open
- where: `ui/DetailScreen.kt:667-671` vs `:685-691`
- what's wrong: only the stock follows the quote tick:
  ```kotlin
  LaunchedEffect(state.lastRefresh, symbol, chartRange, zoomSettling) {
      if (!zoomSettling) vm.loadChart(symbol, chartRange)
  }
  ```
  The benchmark effect is keyed on `(compareOn, isBenchmark, chartRange, zoomSettling)`, so SPY
  is fetched once per range, plus on ON_START, and is never re-checked against its TTL.
- concrete scenario: 1D compare, stock left open from 10:00 to 14:00. The stock line updates
  every 5 minutes while SPY stays frozen at 10:00. The "vs SPY" readout then compares against
  a stale benchmark. This is a request that should run and doesn't; `loadChart`'s TTL keeps it
  at one request per 5 minutes.
- suggested fix: add `if (compareOn && !isBenchmark) vm.loadChart(BENCHMARK_SYMBOL, chartRange)`
  inside the `lastRefresh` effect.
- test: UI-only. Compile it and read the effect. There is no VM seam.

### N-6 [L] `intradayChartIsFinal` never checks that the chart it holds is from the latest session
- where: `ui/PortfolioViewModel.kt:4900-4910`, `:5022-5032` (fast path), `:5084-5085`
  (in-coroutine check, which has no finality test)
- what's wrong:
  ```kotlin
  ChartRange.D1 -> MarketClock.phase(held.endMs) == MarketClock.Phase.OPEN
  ```
  This returns true for any series whose last point fell in some past regular session, not
  only the most recent one. It also appears only on the fast path. Inside the coroutine the
  check is just `!held.stale()`. Two effects follow. (a) Every cold start at night or over a
  weekend re-downloads each opened stock's 1D chart once, even though the disk copy is final.
  (b) Suppose the one fetch that follows the disk read fails (offline, cooldown). The fast path
  then treats an older session's disk copy as final and never retries until the next open.
- concrete scenario: the disk holds Wednesday's 1D chart for XYZ. On Saturday the app is
  opened offline, the fetch fails, and signal returns. The chart shows Wednesday's session,
  labelled 1D, until Monday 09:30, because `chartRetry` clears but the fast path returns null.
- suggested fix: reuse the rule `sparkIsFinal` already has. D1 is final only when
  `sparkIsFinal(held.fetched, now) && phase(held.endMs) == OPEN`, which gives
  `now < nextOpenAfter(held.fetched)`. Apply the same test at `:5085` too.
- test: in `NetLogicTest`, a D1 series fetched Wed 20:05 is not final on Sat 12:00, and one
  fetched Fri 20:05 is final on Sat 12:00.

### N-7 [L] The N-L6 fix stamps symbols as fresh even when their own EDGAR listing failed
- where: `ui/PortfolioViewModel.kt:4477-4487`; `net/Insider.kt:124-131`
- what's wrong: `forSymbols` throws away whether each symbol's listing was answered:
  ```kotlin
  runCatching { gate.withPermit { listFilings(sym, since) } }.getOrDefault(emptyList())
  ```
  The VM then stamps every symbol whenever the pass returned anything:
  ```kotlin
  if (filings.isEmpty()) return
  ...
  symbols.forEach { insiderAt[it.uppercase()] = now }
  ```
  A 403 or 429 from www.sec.gov part-way through a pass arms the host cooldown, and every
  later listing in that pass comes back `CODE_COOLDOWN`, so those symbols were never checked.
  They are still marked checked for `INSIDER_SYMBOL_TTL_MS` (30 min). `loadInsider` exists to
  avoid exactly this "failure cached as answer", keyed on `result.answered`.
- suggested fix: have `forSymbols` also return the set of symbols whose listing had `ok`
  (use `listing()` instead of `listFilings()`), and stamp only those.
- test: `InsiderTest`. With a fake listing function that fails one of three symbols, the
  answered set holds the other two.

### N-8 [L] A research rebuild fetches the market feeds and Reddit data that the feed pass already holds
- where: `net/Research.kt:148-151`
  ```kotlin
  val socialJob = async { runCatching { Social.trending(60) }... }
  val newsJob = async { runCatching { News.market() }... }
  ```
- what's wrong: every stock rebuild (30-minute TTL, and on every pull) makes 7 market RSS
  requests and 2 Reddit-aggregator requests. The Feed pass fetches the same URLs every 3
  minutes (`_feed` MARKET items) and every 15 minutes (`_trending`); `trending(25)` and
  `trending(60)` differ only in a local `take`. Checked live: Google News RSS and Nasdaq send
  no validators (`cache-control: no-cache, no-store`), so these are full downloads.
- suggested fix: `build()` should take optional `headlines` and `social` inputs. The VM passes
  its in-memory copies when they are younger than `feedIntervalSecs` / `SOCIAL_REFRESH_MS`,
  and fetches only when they are missing or stale. Keep 60 trending rows in `_trending` and
  let the Feed take 25.
- test: a pure test that `build(headlines = x, social = y)` makes no `News.market` call. This
  needs an injectable fetcher lambda.

### N-9 [L] `api.tradestie.com` is still dead and is retried on every social pass
- where: `net/Social.kt:69-72`; `net/Http.kt:70-71` (the unreachable backoff caps at 5 min)
- what's wrong: re-verified 2026-09-23: `curl: (60) SSL certificate problem: certificate has
  expired`. The unreachable backoff tops out at 300 s, which is below the 15-minute social
  cadence, so every pass pays a DNS lookup, TCP connect and TLS handshake that cannot succeed.
  `Social.merge` then always falls back to ApeWisdom.
- suggested fix: remove the source. Or give an `SSLHandshakeException`/`CertificateExpired`
  failure its own long per-host memo (for example 24 h) instead of the 5-minute cap.
- test: pure test on `Social.merge` that ApeWisdom alone produces the same shape. Plus, if a
  memo is added, a `nextUnreachable` rule test.

### N-10 [L] `loadFundamentals` can take the cached analyst row for the core row, which skips the core fetch
- where: `ui/PortfolioViewModel.kt:5140-5158`; `net/FundamentalsFeed.kt:92`
  (`RATINGS_MODULES = "upgradeDowngradeHistory,financialData,recommendationTrend"`)
- what's wrong:
  ```kotlin
  val disk = listOfNotNull(cachedFundamentals(sym, KIND_CORE), cachedFundamentals(sym, KIND_RATINGS))
  val core = disk.firstOrNull { it.values.isNotEmpty() }
  if (!force && core != null && now - core.fetched < CORE_TTL_MS) { coreFetchedAt[sym] = core.fetched; return@launch }
  ```
  The ratings row is parsed by `parseYahoo` with `financialData` included, so its `values` are
  not empty. When the core row is missing (its fetch failed, or it was never stored), a
  ratings row less than 6 hours old satisfies the check. The core network fetch is then
  skipped for up to 6 hours. The ratings row is now fetched for every holding by the
  Portfolio recommendation path (`ensureRatingsForRecommendation`), so this state is common.
  The Stats tab and the scorer see only the financialData subset.
- suggested fix: pick `core` only from the KIND_CORE row.
- test: VM-level test with a fake Db (a ratings row present, the core row absent) asserting
  `FundamentalsFeed.core` is called. Alternatively, extract the disk-selection rule as a pure
  function.

### N-11 [L] An empty deep-news result is never remembered, and a memory trim forces a network refetch
- where: `ui/PortfolioViewModel.kt:3449-3452`, `:3477-3487`, `:1934`
- what's wrong: `deepNewsAt` is stamped only when `items.isNotEmpty()`, and nothing records a
  failure. A thinly covered symbol, or an outage, therefore re-runs all 3-4 sources on every
  `LaunchedEffect(symbol)` and every ON_START of that detail screen. `onTrimMemory` clears
  `_news` but not `deepNewsAt`. Because the guard needs `_news[symbol]` to be non-empty, a
  symbol fetched 30 s before the trim is fetched again from the network, even though
  `db.cachedNewsFor` repainted it.
- suggested fix: record an attempt in a `RetryClock` (like `fundRetry`) when the result is
  empty. In the guard, also accept the disk repaint as "held" while `deepNewsAt` is fresh.
- test: a pure guard function `newsWanted(lastDeep, held, failedRecently, force)`.

---

## Checked and fine
- Yesterday's fixes: N-M1 (blank crumb with both hosts cooling gives COOLING), N-M2 (`heldOff`),
  N-M3 (`sparkIsFinal` plus the cold-start seed), N-M4 (`dayTradingLiveDelay`), N-L5 (manual
  pull quotes allTracked plus on-screen; sparks only on-screen), N-L7 (feed pass stamps
  `adviceNewsAt`; preload honours `deepNewsAt`), N-L8 (`socialDue` requires `feedDue`,
  `socialAt` stamped only on attempt). All present and correct. N-L6 is covered in N-7.
- Source order: `MarketData.quotes` sends one v7 request per 50 symbols, then per-symbol
  Yahoo chart, then Finnhub, then Stooq, with a `fallbackRetry` backoff per symbol. Cooling
  suppresses the fallback. Symbols Yahoo returns under a normalised ticker are mapped back to
  the one asked for. Sparklines run on their own 5-minute clock for visible symbols only.
- Lifecycle: `refresh`, charts, fundamentals, news, insider, research, ETF and the day-trading
  loop all run on `fgScope` or have explicit job cancels in `setForeground(false)`. Http
  cancellation disconnects the socket, and a cancelled request is not counted as unreachable.
  `quotePassInterrupted` re-runs a pass that was cancelled. The auto loop skips when offline,
  on `VisibleScope.None`, and on `pricesAreFinal()`. Holidays and half days go through
  `MarketClock.isHoliday` / `closeMinuteAt`.
- Caches: `http_cache` has 30-day retention, a 24M-char bound with a looped purge, touch on
  304, drop on stale validators, and no write while truncated or cancelled. `chart_cache` has
  400 rows and never writes an empty series. `quotes` are purged after 30 days, `news_cache`
  after 31 days. The in-memory conditional LRU is bounded by count and chars, and promotion
  from disk respects the heap limit. Nothing that uses `conditionalKey` stores a credential:
  the quoteSummary crumb is stripped with `cacheAs`, and the Finnhub URLs are unconditional.
- Thread safety: shared maps are `ConcurrentHashMap` or synchronized sets. `insiderDocs` is
  snapshotted under its lock. The `dailyCache` LinkedHashMap is synchronized. Http counters
  are `@Synchronized` and `connRef` is an AtomicReference.
- YahooAuth: the `MIN_INTERVAL_MS` guard holds even with no crumb and after `invalidate()`.
  A 401 path in quoteSummary cannot loop.
- The daily-bars memo (`DayTradingTechnicals.dailyCache`) is correct. `loadHoldings`,
  `loadFundamentals` and `fetchRatings` are disk-first with a TTL and failure backoff.
  `enrichAnalyst` skips rows that already hold a consensus. `SymbolSearch` is memoised with a
  220 ms debounce. EDGAR shares one `secGate(3)`.
- Not verified (Yahoo 429 from this IP): whether quoteSummary sends validators. If it does
  not, the claim in `ensureRatingsForRecommendation` ("every request after the first ... is a
  bodyless 304") is as false as the chart one in N-1. That would make the Portfolio-tab
  ratings fan-out about 195 KB × holdings, once a day. The Settings diagnostics card's
  `http_cache` row count for `quoteSummary` URLs on the device would answer it.
