# PORTFOLIO APP — RESUMABLE CHECKPOINT 57

**How to resume:** upload `portfolio-checkpoint-57.tar.gz` to a new chat and say
"extract this and continue from CHECKPOINT.md". Everything needed is inside.

---

## 0. COLD-START RECOVERY (do this first in a new chat)

**READ `RESUME.md` FIRST, NOT THIS FILE.** From Round 58 the live state of the work
lives in `RESUME.md` (regenerated on every checkpoint, so it cannot go stale) and
`state.json` (the same thing machine-readable: every task, its status, the step that
was in flight, and every open finding). This file is 240 KB of round history — reading
it cold burns budget that should go on the work. Sections 1-5 below are the
architecture and are worth reading when you need them; section 6 is history.

```bash
cd /home/claude && tar xzf /path/to/portfolio-checkpoint-<N>.tar.gz
cat portfolio/RESUME.md                     # where the last session stopped
bash portfolio/setup-env.sh                 # reinstalls the Android SDK (~2 min, once)
cd portfolio && export ANDROID_HOME=/root/android-sdk
bash watchdog.sh &                          # restart the 3-minute autosave
./ck status                                 # the task ledger and open findings
git log --oneline | head -20                # what the last session actually changed
setsid nohup ./gradlew :app:assembleRelease > /home/claude/build.log 2>&1 < /dev/null & disown
```

The Android SDK and the Gradle distribution are NOT in the archive (too large);
`setup-env.sh` rebuilds the SDK and the wrapper re-downloads Gradle on first run.

### THE CHECKPOINT SYSTEM (Round 58 — read `ck.py`'s docstring for the full contract)

`./ck` is the only thing you need to touch. It keeps `state.json` and `RESUME.md` in
agreement, commits to git, writes a verified tarball to `/home/claude/checkpoints/`
and copies it to `/mnt/user-data/outputs/` so the newest checkpoint is always one tap
away in the chat even if the session dies mid-sentence.

```
./ck task T3 doing "starting the range selector"   flip a task (auto-saves)
./ck now  "step in flight" "next action"           breadcrumb between tasks
./ck find F12 "description" high                   record a bug the sweep found
./ck fixed F12 "what the fix was"                  close it (auto-saves)
./ck save "note"                                   full checkpoint
./ck status                                        where things stand
```

`watchdog.sh` commits the tree every 3 minutes if it changed, so a cut mid-edit loses
minutes, never a task. **Start it on every cold resume** — it does not survive a
container restart. `checkpoint.sh` is the pre-58 script and still works, but `ck save`
supersedes it and does strictly more.

### BUILD ENVIRONMENT TRAPS (cost real time last session — do not repeat)

1. **Never unset or blank `JAVA_TOOL_OPTIONS`.** The container's HTTPS proxy and
   truststore live in it. Clearing it makes Gradle fail with
   "Plugin [id: 'com.android.application'] was not found" — that is a *network*
   error, not a version error. `export ANDROID_HOME=/root/android-sdk` is the only
   env var you need to set.
2. **A Gradle build exceeds the 2-minute Bash timeout on a cold start.** Always run
   it backgrounded: `nohup ./gradlew ... > /home/claude/build.log 2>&1 &` then poll
   `tail /home/claude/build.log`. A timed-out foreground run still keeps its work,
   so the next run resumes from where it stopped.
3. **NEVER RUN TWO GRADLE BUILDS AT ONCE, and never kill one mid-flight.** Round 50 lost
   real time to both. Two concurrent builds corrupt `app/build/kotlin/.../caches-jvm` and
   `lint_vital_report_lint_model`; killing a build leaves the Kotlin incremental cache in a
   state where the next compile reports **nonsense**: `Unresolved reference 'BigIconButton'`
   for a symbol in the SAME PACKAGE that is plainly there, alongside a
   "Detected multiple Kotlin daemon sessions" warning. It is NOT a code error. Recover with:

   ```bash
   pkill -9 -f KotlinCompileDaemon
   rm -rf app/build/kotlin ~/.gradle/kotlin ~/.kotlin
   ```

   Launch builds so the harness's 2-minute Bash timeout cannot kill them:
   `setsid nohup ./gradlew ... > /home/claude/build.log 2>&1 < /dev/null & disown`

4. **`assembleRelease` needs `build-tools;35.0.0` as well as 36.0.0** on a cold container -
   the failure reads "Failed to install the following SDK components: build-tools;35.0.0".
   `setup-env.sh` now installs both; if you rebuild the SDK by hand, install both.

5. **Maven Central returns HTTP 429 (Too Many Requests) on a cold container.** It shows up
   as "Could not resolve <any dependency>" or a `lintVitalAnalyzeRelease` failure — it is
   RATE LIMITING, not a version problem, so do not "fix" a dependency because of it. Gradle
   keeps everything it did manage to download, so a plain retry makes progress every time.
   Use the retry loop rather than babysitting it:

   ```bash
   for i in 1 2 3 4 5 6; do ./gradlew :app:assembleRelease && break; sleep 30; done
   ```

---

## 1. WHAT THIS IS

Native Android app (Kotlin + Jetpack Compose) — a personal stock portfolio tracker
for TJ, sideloaded on a **Moto G 2026 running Android 16 (API 36)**.

Package `com.tj.portfolio`. Output: a signed release APK. **BUILT AND DELIVERED.**

## 2. LOCKED DECISIONS (do not revisit)

| Decision | Choice | Why |
|---|---|---|
| Ally Invest auto-sync | **Not possible** — Ally's developer API is retired | Confirmed via search; screenshot import is the path |
| Screenshot import | Claude vision via user's own API key | User approved |
| Market data | **Yahoo chart v8 (no key)** primary; Finnhub optional key; Stooq last resort | User chose this option |
| Quotes | **Yahoo v7 BATCHED** (one request for every symbol) -> per-symbol chart -> Finnhub -> Stooq | Round 56: the per-symbol chart poll was ~70% of all the app's traffic |
| Sparklines | Yahoo chart, own 5-minute clock, visible symbols only | Round 56: a 64dp line does not change in five minutes |
| Response caching | Validators AND bodies in SQLite (`http_cache`), not the heap | Round 56: TJ's rule - nothing already stored should be downloaded again |
| News | Yahoo Finance RSS -> Google News RSS -> Finnhub | All free / no key needed |
| Market-wide news | Finance desks only, plus a two-tier relevance filter | Round 55: MarketWatch's front page was serving personal-finance columns into the feed |
| Insider data | Parse the Form 4 DOCUMENT, not EDGAR's listing title | Round 55: the listing title is boilerplate identical on every Form 4 ever filed |
| Accounting | Full ledger from transactions **+ manual override** | User chose this option |
| Cost basis | **FIFO default**, average cost optional (Settings) | v1.5: FIFO reproduced Ally's basis to the cent on all 16 positions; average cost drifts on any symbol sold then re-bought |
| Build order | Build everything, then one APK at the end | User chose this option |
| Persistence | **Raw SQLiteOpenHelper, no Room/KSP** | Removes annotation-processor version risk |
| JSON | `org.json` (Android framework) | No kotlinx-serialization plugin needed |
| HTTP | `HttpURLConnection` | No OkHttp/Retrofit dependency |
| Navigation | Hand-rolled state stack in MainActivity | No navigation-compose dependency |
| On-demand fetches | **`fgScope`** — a supervisor scope cancelled on ON_STOP, rebuilt on ON_START | Round 57: 42 launch sites, only 3 stopped on background. DB writes stay on `viewModelScope` |
| Cancelling a request | **Disconnect the socket**, not just skip what is queued | Round 57: cancelling mid-transfer otherwise still paid for the whole body |
| Unparseable SEC filings | Recorded permanently in `Keys.INSIDER_SKIP` | Round 57: a filed Form 4 is immutable — one that cannot be read never can be |

## 3. TOOLCHAIN — VERIFIED BUILDING (do not "upgrade" these)

```
JDK              21 (preinstalled)          Gradle wrapper  8.14.3
AGP              8.13.2                     Kotlin          2.3.10
compileSdk/target 36 (Android 16)           minSdk          26
Compose BOM      2025.09.01                 activity-compose 1.10.1
lifecycle        2.9.4                      core-ktx        1.17.0
coroutines       1.10.2
Android SDK at /root/android-sdk  (platform-36, build-tools 36.0.0, platform-tools)
```

**Why these library versions and not newer ones:** the newest androidx releases
(compose BOM 2026.06.01, lifecycle 2.11.0, activity 1.12.4, core-ktx 1.19.0) now
require **compileSdk 37 and AGP 9.1+**, and AGP 9 changes the Kotlin plugin wiring.
Building against 36 for an Android 16 device is correct, so the libraries were pinned
back to the last releases that compile against 36. **If a build suddenly fails with
"requires compileSdk 37" or "requires AGP 9.1.0 or higher", something bumped a
version — put it back.**

Signing: `app/sideload.jks`, all passwords `portfolio`, alias `portfolio`,
DN `CN=Portfolio, OU=Personal, O=TJ`. Both debug and release use it so the APK sideloads.

### THE KEYSTORE IS IRREPLACEABLE — NEVER REGENERATE IT

Cert SHA-256 `2e8c3847 2d1657b7 d2562266 c6d7e1d8 e6f03d76 66bd10e1 cb505b1c f396a9f2`,
identical in every build shipped so far (verified with apksigner across v1.2/1.3/1.4).

Android only performs a **data-preserving in-place update** when the package name AND the
signing certificate both match. A new keystore would force TJ to uninstall first, which
**erases the portfolio**. So: `app/sideload.jks` is in every checkpoint archive; always
build with the archived one, never `keytool -genkey` a fresh key, and never change
`applicationId`. Also always bump `versionCode` - Android refuses to install a build whose
versionCode is lower than what is on the phone.

## 4. FILE MAP

```
portfolio/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, local.properties
  checkinit.py           build guard: no property may be declared below init (Round 34)
  checkpoint.sh          regenerates the checkpoint archive:  bash checkpoint.sh 3
  setup-env.sh           reinstalls the Android SDK on a cold start
  app/build.gradle.kts
  app/sideload.jks       signing key
  app/src/main/AndroidManifest.xml
  app/src/main/res/       themes (light + night), adaptive launcher icon, strings
  app/src/main/java/com/tj/portfolio/
    data/Models.kt       Txn, Quote, NewsItem, Advice, StockRating, Override, TxnType
    data/Db.kt           SQLiteOpenHelper: txns, settings, overrides, quotes, watchlist,
                         news_cache (v4: saved headlines, month retention),
                         fundamentals (v5: company numbers + analyst ratings, TTL cache)
                         + JSON export/import backup, dedupe on re-import; object Keys
    domain/Ledger.kt     average-cost replay -> Position, PortfolioTotals
    domain/Fees.kt       Ally's fee schedule + SEC/FINRA regulatory fees (Round 37)
    util/Format.kt       Fmt: money/pct/date formatting, date parsing
    net/Http.kt          GET/POST over HttpURLConnection
    net/MarketData.kt    Yahoo -> Finnhub -> Stooq quote chain, sparkline, ext hours
    net/News.kt          per symbol: Yahoo -> Nasdaq -> Google News -> Finnhub;
                         market-wide: 7 finance-desk feeds, filtered by MarketRelevance
    net/MarketRelevance.kt  is a market-wide headline about the market at all? (Round 55)
    net/Claude.kt        /v1/models, screenshot extraction, portfolio advice (API key path)
    net/SymbolSearch.kt  type-ahead ticker/company lookup: Yahoo search -> Finnhub search
    net/ClaudeBridge.kt  NO-API-KEY path: builds .md prompt files, parses Claude-app replies
    util/Storage.kt      MediaStore writes into Downloads + reading a picked file
    util/CrashLog.kt     uncaught-exception handler -> private crash-log.txt; read in Settings
    ui/Theme.kt              Green #16C784 / Red #EA4B5D / Accent, light+dark schemes
    ui/Widgets.kt            Avatar, Sparkline, PricePill, StatCard, KeyValue, SectionHeader
    ui/Dialogs.kt            TextPromptDialog, ConfirmDialog
    ui/PortfolioViewModel.kt Row/UiState, refresh loop, news, advice, import, backup
    ui/Common.kt             Refreshable (pull-to-refresh wrapper), BigIconButton
    net/YahooAuth.kt         the cookie+crumb handshake, shared by v7 quotes and v10 summary
    net/Insider.kt           EDGAR listing -> filing documents, one month, held + watched
    net/Form4.kt             parses a Form 4: who, which way, how much, 10b5-1? (Round 55)
    data/InsiderModels.kt    InsiderFiling / InsiderTrade + headline text + JSON cache
    ui/InsiderUi.kt          InsiderRow + the Open market / All trades / Everything chips
    net/Social.kt            r/wallstreetbets trending: Tradestie + ApeWisdom merge
    ui/FeedScreen.kt         Live feed tab: headlines + insider filings + WSB trending
    ui/ReaderScreen.kt       in-app WebView browser + reader view (tested in Chromium)
    net/MarketClock.kt       US market phase -> how often to poll (the ban-avoidance clock)
    net/Relevance.kt         IS THIS HEADLINE ABOUT THIS STOCK? the FIVE fix, shared by the
                             news filter and the Feed's "My stocks" filter (Round 50)
    net/AdBlock.kt           host-suffix blocklist for the reader's ad/tracker/pop-up
                             blocking (Round 51)
    util/MemoryTrim.kt       onTrimMemory fan-out; weak listeners (Round 50)
    ui/StockRow.kt           shared row + long-press menu; enum RowAction
    ui/RowActions.kt         PendingAction + RowActionHost: renders the menu's dialogs
    ui/TxnEditor.kt          TxnEditorDialog - one dialog for add AND edit of a txn
    ui/SearchSheet.kt        full-screen type-ahead search, add/remove watch anywhere
    ui/PortfolioScreen.kt    summary header + holdings list (the screenshot layout)
    ui/WatchlistScreen.kt    same live data for non-held symbols
    ui/DetailScreen.kt       FIVE TABS: Overview / Stats / Analysts / Earnings / News, with
                             the price block pinned above them (Round 53)
    ui/DetailTabs.kt         the Stats, Analysts and Earnings tab bodies
    ui/MetricUi.kt           MetricRow + the "i" dot + the explanation dialog + VoteBar
    ui/Explain.kt            THE EXPLAINER: for every metric - meaning, good/bad ranges,
                             short- and long-term price effect, and a live read of this
                             stock's own value with a positive/negative verdict
    data/Fundamentals.kt     51-metric catalogue, AnalystRating, Consensus, RatingTrend,
                             EarningsEstimate, EarningsResult, and Fundamentals.merge
    data/FundamentalsJson.kt the on-disk codec for the db v5 fundamentals cache
    net/FundamentalsFeed.kt  Yahoo quoteSummary (cookie+crumb) -> Nasdaq JSON -> Finviz parse
    data/ResearchModels.kt   ScreenRow (a screener quote), Consensus2 (buy/hold/sell +
                             target), ResearchRow (a scored row + its reasons + Claude's
                             why), ResearchSet (three lists + provenance) with a JSON codec
    net/Screener.kt          Yahoo PREDEFINED screeners (no key, no crumb): 9 lists, one
                             request each, ~450 fully-populated quotes; + trending tickers
    net/ResearchScore.kt     THE SCORING ENGINE - pure functions, no network/clock/model.
                             best(), worst(), trending(), withAnalyst() overlay. Every
                             scorer returns its reasons in plain English alongside the number
    net/Research.kt          Builds all three sections in one wide-shallow pass (~18
                             requests) + the two per-row enrichment stages
    net/ShortVehicle.kt      Resolves the inverse ETF on a stock LIVE from Yahoo's fund
                             search - no hard-coded table (the roster changes constantly)
    net/ResearchBridge.kt    The Research tab's offline bridge: prompt file with the live
                             data bundled in, reply parser, and merge()
    ui/ResearchScreen.kt     Trending / Best / Worst, ten rows each with a "Load more"
    tools_research_sim.py    Python port of the scorers, run against the live screeners, to
                             read the actual output with your own eyes (see Round 54)
    ui/ActivityScreen.kt     transaction list, add-txn dialog, screenshot import + review
    ui/AdviceScreen.kt       Claude analysis, action list, 1-10 rating cards
    ui/SettingsScreen.kt     API keys, model picker, refresh interval, cash override, backup
    MainActivity.kt          BigTabBar + detail/search stack + BackHandler (tabHistory)
```

## 5. KEY IMPLEMENTATION NOTES

- **Cash effect sign convention.** `Txn.amount` is the *signed* effect on buying power.
  `Txn.cashEffect()` derives it: BUY/WITHDRAWAL/FEE negative, SELL/DEPOSIT/DIVIDEND positive.
  Claude returns positive amounts; the app applies the sign at import.
- **Ledger replay** (`domain/Ledger.kt`) sorts by date then id and walks forward.
  SELL realizes `(shares x price - fees) - (shares x average cost at that moment)`
  and reduces cost basis proportionally. Overrides replace shares and/or avg cost afterward.
- **DAY P/L FOR A POSITION IS NOT shares x the stock's day change.** Shares held since
  before today are measured from the previous close; shares BOUGHT TODAY are measured from
  the price actually paid, because they were never exposed to the prior close. TJ caught
  this on FIVE: bought at 240.48, closed at 239.96, prior close 243.08 - the app showed
  -$3.12 when the real result was -$0.52. `Position` now carries `sharesToday`/`costToday`
  (populated from BUY rows falling inside the local calendar day), and `dayPnl(q)`,
  `dayBasis(q)`, `dayPnlPct(q)` implement the split. `Ledger.totals` uses the same methods,
  so the portfolio header agrees with the rows. The MARKET HOURS metric still shows the
  STOCK's own move - the two are deliberately different figures and the row labels the
  position line "TODAY (from your fill)" when they diverge.
- **THE POLLING LOOP MUST BE LIFECYCLE-AWARE.** `autoJob` lives in `viewModelScope`, which
  OUTLIVES the Activity being stopped. Before v3.3 the app therefore kept fetching every
  symbol every 15 seconds forever in the background - invisible work, real battery cost, and
  a good way to get the process killed by the system. `setForeground(active)` is driven by a
  `LifecycleEventObserver` on ON_START/ON_STOP in MainActivity: it cancels the loop on stop
  and, on resume, refreshes IMMEDIATELY before restarting the timer so you never look at a
  15-second-old screen. The loop also re-checks `foreground` after each delay and breaks.
  **Any future background work needs the same treatment.**
- **ROUND 57: THE LOOP WAS NEVER THE ONLY BACKGROUND WORK — USE `fgScope`.** Anything the user
  TRIGGERS (a fetch for a screen they are looking at) launches on `fgScope`, a `SupervisorJob`
  cancelled on ON_STOP and rebuilt on ON_START. Anything that must COMPLETE (a DB write, a
  backup, a Claude request) stays on `viewModelScope`. That is the whole rule, and it is the
  one to apply to any new fetch: if the answer is only useful while the screen is up, it is
  `fgScope`. Rebuilding on ON_START is not optional — a cancelled Job stays cancelled, and a
  child launched into one never runs.
- **Insider filings ride `INSIDER_REFRESH_SECS = 1800`**, not the 3-minute news cadence.
  Form 4 is legally up to two business days behind, so polling SEC every 3 minutes was
  ~320 requests/hour for data that cannot have changed. `refreshFeed(includeInsider=false)`
  keeps the previously fetched filings rather than dropping them from the list.
- **ROUND 55: filings live in their own store, `_insiderFilings`, NOT in `_feed`.** This is
  the fix for "the insider tab only shows one result". `_feed` is sorted newest-first and
  capped at `MAX_FEED_ITEMS = 400`; filings are days old by law and headlines are minutes
  old, so ordinary news sorted straight over the top of them and every filing but the very
  newest was evicted before the tab's filter ever ran. Anything time-ordered and slow that
  shares a capped list with the news feed will hit this - do not put it back.
- **ROUND 56: THE QUOTE POLL IS ONE REQUEST, NOT ONE PER SYMBOL** (`MarketData.quotes`).
  Yahoo's `v7/finance/quote` takes a comma-separated list. This was ~70% of everything the
  app sent anywhere - 3,840 requests an hour to query1 on a 16-holding portfolio. It needs the
  same cookie+crumb handshake `quoteSummary` uses, which is why `YahooAuth` is now its own
  file. It returns NO candle series, so the sparkline moved to `refreshSparklines` on a
  five-minute clock. **The per-symbol chain is still underneath as the fallback and must
  stay** - this path could not be verified from the build container, which Yahoo 429s.
- **ROUND 56: `Http` caches to DISK, not just to the heap.** `http_cache` (db v6) holds the
  ETag/Last-Modified validators and the last body per URL, so a cold start asks "has anything
  changed?" instead of re-downloading every feed. The heap map in front of it is now just a
  hot cache; `onLowMemory` dropping it costs one SQLite read rather than one download.
  **A 200 that arrives with no validators, or too big to store, DELETES the old entry** -
  leaving it behind is how a cache serves a month-old body forever.
- **ROUND 56: `Http` limits concurrency PER HOST** (`MAX_PER_HOST = 4`). Every gate in the app
  used to be a semaphore owned by its call site - six of them, most pointing at the same two
  Yahoo hosts, so a Research build overlapping a feed pass and a quote tick could have ~25
  sockets open. Only a limit at the host can be right, because the host is what throttles.
- **ROUND 56: per-symbol news only runs while a headline screen is visible**
  (`setNewsVisible`). It was ~480-1,000 requests an hour whether or not the Feed tab had ever
  been opened. Note the grace window is stamped when visibility goes FALSE, not true - stamping
  on the way in collapses it to zero for anyone who reads the tab for more than five minutes.
- **ROUND 56: `pricesAreFinal()` stops overnight polling.** The closed-market floor of 15
  minutes still meant ~96 requests a night for a number that cannot change until Monday. The
  test is about the DATA - "we already hold a price fetched after the close" - with a six-hour
  backstop so being wrong about a holiday costs one stale afternoon, not a frozen screen.
- **ROUND 55: the EDGAR pass runs ALONGSIDE the feed, not inside it** (`startInsiderRefresh`).
  Awaited inline, a cold start of ~120 filing documents held `_feedLoading` true for the
  whole minute, which also made `refreshFeed`'s re-entry guard swallow every pull-to-refresh
  in that window.
- **MAXIMUM-FRESHNESS MODE (v2.5).** TJ has unlimited data and wants everything as live as
  possible, so bandwidth is NOT a reason to hold back here:
  - Yahoo chart pulled at `interval=1m` (was 5m) with `includePrePost=true` - finest
    granularity Yahoo offers for a 1-day range; finer sparkline and fresher ext-hours print.
  - Extended-hours price prefers `meta.postMarketPrice` / `meta.preMarketPrice` (the live
    print) and only falls back to the last candle close, which can lag by a minute.
  - Default auto-refresh 60s -> 15s; Settings allows down to 5s.
  - `MAX_PARALLEL_REQUESTS` 6 -> 16, so every symbol is fetched simultaneously.
  - Headlines re-pull on their own cadence, `NEWS_REFRESH_SECS = 180`, via `refreshAllNews()`.
  - `Quote.quoteTime` carries `meta.regularMarketTime`; the detail screen shows
    "Last exchange print HH:MM:SS - pulled Xs ago". `Fmt.relative` now has second precision.
  - DB_VERSION 2 -> 3 adds `quotes.quote_time` via `addColumn()` (additive ALTER, idempotent).
- **Day P/L** uses `meta.previousClose` (RAW prior regular close), NOT `chartPreviousClose`.
  chartPreviousClose is adjusted for dividends/splits, so on an ex-dividend day the day
  change is wrong. This was a real bug found by reconciling against Ally: AVGG showed
  -1.36/-5.57% in the app vs -1.44/-5.88% at the broker, an 8c dividend adjustment, which
  threw the whole day's P/L off by $0.33.
- **COST BASIS IS FIFO BY DEFAULT** (`domain/Ledger.kt`). `Ledger.positions(txns, overrides,
  method)` dispatches to `fifo()` (lot deque, oldest consumed first) or `averageCost()`.
  Verified against TJ's real Ally statement: FIFO matched the broker's cost basis on all 16
  open positions exactly; average cost differed on CSCO and NVDA - precisely the two symbols
  that had been sold and later re-bought. Total lifetime P/L is identical under both methods;
  only the realized/unrealized split moves (FIFO realized +102.84 / unrealized -246.08;
  average +117.88 / -261.13; both sum to -143.25).
- **ROW LAYOUT (v1.8) - AND THE CLIPPING TRAP.** The v1.6 row put the session changes in a
  FIXED-WIDTH (132.dp) column with `maxLines = 1`. Compose's default overflow is Clip, so on
  values like "+0.1300  +0.15%" the percentage was silently cut off the right edge with no
  ellipsis to hint at it - TJ spotted it in a screenshot. Two fixes, keep both:
  1. `ui/StockRow.kt` now lays the four figures out as `Metric` composables in
     `Modifier.weight(1f)` columns across the full row width, with
     `overflow = TextOverflow.Ellipsis` so any future overflow is at least visible.
  2. `Fmt.changeFor(price, change)` sizes decimals by the STOCK PRICE, not the change:
     2 decimals at or above $1, 4 below. "+0.1300" was three characters of nothing.
  **Never put a price+percent pair back into a fixed-width column.**
- **BACKUP FORMAT v2** (`Db.exportJson` / `Db.restoreJson`). Self-describing:
  `format`/`version`/`exported`/`app{package,versionName,dbVersion}`, then transactions,
  overrides, watchlist, settings, **imports** (history was missing in v1), plus a `counts`
  manifest the restore cross-checks and warns on mismatch. API keys are filtered by
  `isSecret()` on BOTH export and restore. Everything is read live at call time.
  - `restoreJson` runs inside a single SQLite transaction and returns a `RestoreResult`
    with per-section counts and a `summary()` string the UI toasts.
  - `replace = true` clears txns, overrides, watchlist AND imports first (v1 only cleared
    txns, so a restore onto a used device left stale overrides and watchlist entries).
  - **THE org.json NULL TRAP** - fixed here, do not reintroduce: `optString(k)` on a JSON
    `null` returns the literal STRING `"null"`, not "". v1 restore therefore turned all 8
    of TJ's cash rows (DEPOSIT, symbol null) into a phantom symbol "NULL". Always guard
    with `if (o.isNull(k)) null else ...`. Verified by simulation: 8/8 rows corrupted
    before, 0 after, and a full export -> restore round trip is byte-identical.
- **Fmt FORMATTERS ARE ThreadLocal - DO NOT MAKE THEM PLAIN `val`s AGAIN.**
  DecimalFormat and SimpleDateFormat are mutable and not thread-safe. `Fmt` is called from
  composition (main) and from IO threads (backup file naming, the Claude portfolio JSON,
  news date parsing), so a shared instance can garble output or throw. Found in the v2.0
  sweep; every formatter now sits behind a ThreadLocal getter.
- **Settings writes do NOT recompute unless the key matters** (`computeAffecting` in the
  ViewModel: cash override, use-cash-override, cost method, sort). Before v2.0, typing an
  API key into Settings replayed the entire ledger once per keystroke.
- **Network fan-out is capped** at `MAX_PARALLEL_REQUESTS = 6` via a coroutine `Semaphore`,
  for both quote refresh and the advice news preload.
- **Quote cache writes are batched** (`Db.cacheQuotes`) - one IO hop and one SQLite
  transaction per refresh instead of one of each per symbol.
- **`backupToDownloads` verifies itself**: writes the file, reads it back off disk,
  re-parses it and compares transactions / overrides / watchlist / imports against the live
  database plus the embedded manifest. It only reports success if all of them agree, and it
  runs entirely on IO so a big export cannot block the UI.
- **MONEY DECIMALS**: 2 places normally, 3 for sub-$1 values (`Fmt` money3 / `changeFor`).
  Four decimals were dropped in v1.9 to save row width.
- **`scrollToNews` IS NOW WIRED UP.** It was passed into DetailScreen and never used for
  six versions - tapping the "News" chip on a row opened the detail screen at the TOP, which
  is not what the chip promises. DetailScreen now owns a `rememberLazyListState()` and a
  LaunchedEffect scrolls to `1 + txns.size + insiderBlock`. **If you add or remove a section
  above the News header, that index must change with it.** This bit immediately: v3.2 used
  `filings.size + 1` when the insider block is actually THREE kinds of item - section
  header, one per filing, and the trailing footnote - so `filings.size + 2` is correct.
  Count the `item {}` / `items()` calls literally when changing this.
- **Detail screen shows Form 4 filings** for held symbols via `vm.loadInsider(symbol)`,
  cached per symbol in `_insider`. Tapping one opens the filing on SEC.gov.
- **Advice gets `contextBlock()`** on top of headlines: recent insider filings on holdings
  and any holding currently trending on r/wallstreetbets, each labelled with what it is and
  is not, so the model does not read attention as a fundamental signal.
- **Pill = regular-session price** (the close, once the market shuts). The live
  extended-hours price appears as "now $X" under the AFTER HOURS / PRE-MARKET metric, so
  the two are never confused.
- **ROW LAYOUT (v1.6).** `ui/StockRow.kt` renders two lines per holding. Top line:
  avatar / symbol / "N sh @ avg" / "Value $X" on the left, sparkline, then the price pill
  with two explicitly LABELLED changes under it - "Market" (regular session, from the prior
  close) and "After hrs" or "Pre-mkt" (extended session, measured from today's close).
  Bottom line: TODAY and ALL TIME dollar P/L with percentages, coloured green/red.
  **Both dollar figures cover only shares still owned** (`Row.dayPnl` = shares x dayChange,
  `Row.totalPnl` = `Position.unrealized(price)`); profit on sold shares is the separate
  realized figure on the summary card. Do not "simplify" by merging the two session
  changes back into one unlabelled line - TJ specifically asked to tell them apart.
- **Two different "total gain" numbers, both correct.** A broker's "Total G/L" is unrealized
  on OPEN positions only. The app's headline "all time" is realized + unrealized = equity -
  net deposits, which is the more complete figure. The summary card now shows
  "Open positions G/L" (the line that ties out to the broker) plus a footnote.
- **Extended hours** come from the same chart call: points with
  `timestamp >= currentTradingPeriod.regular.end` are after-hours, `< regular.start` are
  pre-market. One HTTP call per symbol yields quote + sparkline + extended price.
- **`refresh()` MUST clear `loading` in a `finally`.** The guard `if (loading) return` means
  one stranded flag disables refresh for the rest of the session and leaves pull-to-refresh
  spinning forever. Before v2.3 an exception anywhere in the body (e.g. the quote cache
  write) did exactly that. Same rule for any future long-running flag.
- **Never fabricate a previous close.** The Stooq fallback has none; it used to report
  today's OPEN as `prevClose`, which produced a wrong-but-plausible day change. It now
  leaves `prevClose = 0` and the UI prints "unavailable" for MARKET HOURS rather than a
  made-up 0.00%. `hasDay` in `StockRow` is the guard.
- **NOTHING THAT TOUCHES DISK, THE DB OR CRYPTO MAY BE CALLED BARE IN COMPOSITION.**
  Compose re-runs a composable on every state change, so `vm.snapshotCount()` (lists a
  directory), `vm.lastAutoBackup()`/`vm.modelChosen()` (DB reads) and `signerShort(ctx)`
  (SHA-256 over the signing cert) were all running on EVERY keystroke in Settings. They are
  now behind `remember(...)`, with an `infoTick` counter bumped by the events that actually
  change them. Same rule applied in SearchSheet: one watchlist read for the whole result
  list rather than two DB reads per row.
- **Cold start seeds `lastRefresh` from the newest cached quote**, so an offline launch says
  "Prices updated 2h ago" instead of silently showing stale numbers with no timestamp.
- **Yahoo is 429-blocked from datacenter IPs** (this build container) but works from a
  phone's mobile/Wi-Fi IP. Do not "fix" this — it is why the fallback chain exists.
  It also means quotes cannot be smoke-tested from here; test on the phone.
- **Web search in advice** uses tool type `web_search_20250305` with `name: "web_search"`.
  RE-VERIFIED against the live docs in Sept 2026: still valid, NO anthropic-beta header
  needed. Newer versions (`web_search_20260209`, `web_search_20260318`) exist but add
  features this app does not use. On a non-2xx the request retries with `tools` removed -
  but NOT on 401/429, which are never the tool's fault.
- **MODEL IDS GO STALE - THIS BIT US.** The hard-coded fallback was `claude-sonnet-4-5`,
  which is retired; any user who never tapped "Load models" would have got a 404 on every
  Advice and screenshot import. Now: `Claude.DEFAULT_MODEL = "claude-sonnet-5"` (a bare
  alias, so it follows the family forward), `ensureModel()` fetches the live /v1/models
  list before the first request when no model has been chosen, and a 404 triggers one
  automatic re-fetch and retry. **If Advice ever 404s again, the fix is the same: let the
  live list decide, do not hard-code a newer id.**
- **API errors must never be cached as advice.** `Advice` now has an explicit `error`
  field. Before v2.1, `apiError()` was returned in `summary`, so "Invalid API key (401)"
  was stored as the portfolio Overview and persisted to `Keys.ADVICE_CACHE`.
- **Truncation is detected**: `stop_reason == "max_tokens"` produces a plain-language error
  suggesting web search be turned off. max_tokens raised 8000 -> 16000.
- **One JSON scanner, not two.** `Claude.firstJsonObject` used to take the first `{` in the
  string and broke on prose containing a brace; it now delegates to
  `ClaudeBridge.findObject`, which walks every balanced object and keeps the richest.
  Simulation-verified against a web-search response with decoy braces, escaped quotes and
  interleaved server_tool_use / web_search_tool_result blocks.
- **Advice HTTP timeout** is 180s read / 30s connect - web search needs the headroom.
- **Model list is fetched live** from `/v1/models` (Settings -> Load models) so the app
  survives model retirements. Default falls back to newest Sonnet.
- API keys are never written to the JSON backup export (keys filtered by name).
- **The no-API-key bridge** (`net/ClaudeBridge.kt`) is the second path through every
  Claude feature. The app writes a `.md` prompt to Downloads; TJ attaches it to the
  Claude *app*; Claude's reply is saved as a file and imported back. `parse()` finds the
  payload with a **balanced-brace scanner** that walks every `{...}` in the text, keeps
  the ones carrying a known key (`portfolioAppResponse`, `transactions`, `advice`,
  `stocks`, `summary`) and picks the richest — so it survives prose, decoy JSON, fenced
  blocks, and escaped quotes. That algorithm was port-tested in Python against realistic
  replies before shipping; if you change it, re-test the same way.
- **Duplicate handling.** `Db.txnExists()` matches type + symbol + date + qty + price +
  amount. The import review dialog marks matches **DUPLICATE** and unchecks them, then
  commits with `force = true` so the user's ticks are honoured exactly. Consequence worth
  knowing: two genuinely identical trades on the same day look like duplicates — the user
  can simply tick the second one.
- **Import history** lives in the `imports` table (db v2, `onUpgrade` creates it). The
  Activity tab shows last upload date, the date range it covered, added/skipped counts,
  and "you have transactions through <date>" from `MAX(txns.date)`.
- **Files go to Downloads via MediaStore** (`util/Storage.kt`), so no storage permission
  is needed on API 29+. Restore uses `ActivityResultContracts.OpenDocument`, which is why
  no read permission is needed either. Backup falls back to the clipboard if the write fails.
- **Holdings track the ledger automatically.** A SELL that zeroes a position drops it from
  the list (`shares > 1e-9` filter) and its realized P/L survives in `positions`; cash is
  `sum(txn.amount)` across deposits, withdrawals, buys, sells, dividends and fees.
- **Pull-to-refresh** is `ui/Common.kt` -> `Refreshable`, wrapping material3's
  `PullToRefreshBox` (experimental, needs `@OptIn(ExperimentalMaterial3Api::class)`).
  Every tab is wrapped in one, including Settings and Advice. It is confirmed present in
  compose BOM 2025.09.01 - verified by listing the material3 aar's classes.
- **Long-press menu.** `StockRowItem` uses `combinedClickable` (needs
  `@OptIn(ExperimentalFoundationApi::class)`) and emits a `RowAction`. Screens store a
  `PendingAction(symbol, action)` and hand it to `RowActionHost`, which renders the right
  dialog. Adding a new menu item means: add to the enum, add the `DropdownMenuItem`, add
  a branch in `RowActionHost` - three places, all in `ui/`.
- **EDGE-TO-EDGE INSETS — the bug that shipped in v1.2.** `enableEdgeToEdge()` in
  `MainActivity` makes the app draw behind the system bars. Scaffold passes the bottom
  inset to its *content* padding, but a CUSTOM `bottomBar` composable must consume the
  navigation-bar inset itself, or Android's gesture pill / 3-button nav sits on top of the
  tab labels. Fix in `BigTabBar`: the background goes on the outer `Column` and
  `.navigationBarsPadding()` goes on the inner `Row`, *before* `.height(74.dp)`, so the
  bar's surface still paints behind the nav area while the tappable content lifts clear.
  **Any future custom bar, FAB or bottom sheet needs the same treatment.**
- **Header buttons** are `BigIconButton` (52dp circle, 28dp glyph). Its `onClick` is the
  LAST parameter so trailing-lambda calls work; passing the handler positionally as the
  third argument binds to `tint` and fails to compile. That mistake cost one build cycle.
- **DATA PRESERVATION ACROSS UPDATES** (TJ asked for this explicitly). Three guarantees:
  1. Same package + same signing key + rising versionCode = Android keeps the app's data
     directory, so `portfolio.db` is untouched by an update.
  2. `Db.onUpgrade` is **additive only** - it never DROPs or recreates a table holding user
     data, and each step is idempotent. `onDowngrade` is overridden to a no-op because the
     SQLiteOpenHelper default THROWS and would crash on launch. `onOpen` re-runs the
     `CREATE TABLE IF NOT EXISTS` as a repair for an interrupted upgrade. The v1 -> v2 path
     was simulated in Python sqlite3 with real rows and verified lossless.
  3. Daily auto-snapshot (`autoBackupIfDue`) writes a dated JSON into the app's PRIVATE
     folder (`filesDir/backups`, rolling window of 14) once a day and after any import.
     **It must never write to Downloads** - TJ asked for the user's Downloads folder to stay
     clean, and only the Settings Backup button may create a visible file. A one-time
     `cleanOldPublicAutoBackups()` deletes the `portfolio-autobackup-*` files that builds
     before v1.7 left in Downloads (an app may delete its own MediaStore rows without a
     permission prompt); it is gated by `Keys.AUTOBAK_CLEANED` so it runs once.
     Caveat surfaced in the UI: private snapshots die with an uninstall, so the manual
     export is still the copy that matters off-device.
  Settings has a "Data safety" card showing package, app version, DB version and the live
  signing-cert prefix read via PackageManager, so continuity can be confirmed on-device.
- **Persistence on force-stop.** Every write is a synchronous SQLite write — there is no
  in-memory-only state and no explicit save step. The selected tab is persisted too
  (`Keys.LAST_TAB`), so a cold start reopens on the same tab.
- **ViewModel is the single source of truth.** Screens are stateless; `recompute()`
  re-reads the DB and rebuilds rows/totals, `refresh()` re-fetches quotes in parallel
  and caches them so the app opens with last-known prices offline.
- **THE RESEARCH TAB SCORES ON THE PHONE; CLAUDE ONLY EXPLAINS.** `net/ResearchScore.kt` is
  pure - no network, no clock, no model - and every scorer returns its reasons alongside the
  number so the UI can print them. A missing field scores ZERO for its component rather than
  being guessed at. If you change a scorer, change `tools_research_sim.py` with it and re-run
  it against the live screeners: the unit tests prove the arithmetic, only the harness shows
  whether the OUTPUT is sensible.
- **THE TEN-ROW RULE IS ENFORCED ON THE WIRE.** A section buffers up to 50 rows and shows 10.
  Analyst consensus (1 Nasdaq call/symbol) runs over a window of 20 - wider than the page, or
  it could not change which ten are shown - and the inverse-ETF search (up to 2 Yahoo calls/
  symbol) runs for the visible 10 only, AFTER the ranking settles. `analystDone`/`vehicleDone`
  stop anything being fetched twice. Do not merge the two stages: it quadruples the request
  count for rows that then fall off the page.
- **THE RESEARCH SUB-TAB MUST BE VISIBLE TO `VisibleScope`.** It lives inside the Watch tab
  but shows no watchlist row, so it polls NOTHING. That decision is in `VisibleScope.choose()`
  - a pure function with tests - not in `MainActivity`, and the sub-tab index is hoisted to
  `MainActivity` so both it and the back handler can see it.
- **NEVER HARD-CODE THE INVERSE-ETF TABLE.** Single-stock inverse funds launch, close,
  reverse-split and rename constantly. `net/ShortVehicle.kt` resolves them live from Yahoo's
  fund search by requiring BOTH the underlying's ticker and an inverse word in the fund name,
  and rejects the bull fund on the same stock, an inverse fund on something else, and
  option-income funds ("YieldMax Short NVDA Option Income Strategy" is not a short).
- Compose `Row` is the UI row model name **and** the layout function name — inside
  `ui/` the data class `Row` shadows nothing because layouts are called qualified by
  import order; if a new file uses both, alias the import.

## 6. STATUS — FEATURE COMPLETE, v6.9 SHIPPED

APK: `portfolio-v6.9.apk`, versionCode 56 / versionName 6.9.
Version history: v1.0 (core app), v1.1 (watchlist/search/backup/offline bridge),
v1.2 (gestures, editing, long-press), v1.3 (navigation-bar inset fix),
v1.4 (update-safety hardening + daily auto-backup),
v1.5 (FIFO cost basis + ex-dividend day-change fix, reconciled against the real Ally account),
v1.6 (session-labelled changes, per-holding market value, dollar P/L per row),
v1.7 (automatic snapshots made private; Downloads only written by the Backup button),
v1.8 (row relaid out in weighted columns so percentages cannot clip; live extended price),
v1.9 (backup format v2: complete, verified restore; 3-decimal money),
v2.0 (full code sweep: thread-safety, efficiency, verified backup),
v2.1 (Advice tab audit: retired-model fix, error handling, shared JSON scanner),
v2.2 (same-day purchases no longer measured from the prior close),
v2.3 (refresh cannot strand itself; no fabricated day changes; offline price age),
v2.4 (no disk/DB/crypto work inside composition; news date fixes),
v2.5 (maximum-freshness mode: 1m candles, 15s refresh, live ext-hours meta fields),
v3.0 (LIVE FEED tab, redesigned portfolio UI, three-layer import dedupe),
v3.1 (feed sweep: SEC rate cap, deflate bug, duplicate news fetch, LazyColumn key),
v3.2 (News button actually scrolls; insider filings per stock; richer advice context),
v3.3 (polling is lifecycle-aware; insider on a slow cadence),
v3.4 (scroll-index off-by-one; import review made lazy + bulk select),
v3.5 (transaction validation, auto-refresh floor, News tap target, text leading),
v3.6 (pull-spinner no longer self-triggers; share-count override keeps its avg cost),
v3.7 (feed retry loop stopped; WSB momentum compares like with like),
v3.8 (restore hardening: NaN overrides rejected, merge stops clobbering settings),
v3.9 (undated screenshot rows can no longer import twice; model auto-pick fixed),
v4.0 (offline import picks the real answer, not the echoed example; news retries),
v4.1 (feed paints in stages instead of all-or-nothing; Reddit calls parallelised),
v4.2 (All tab carries real market-wide news; My stocks is the holdings filter),
v4.3 (price display labelled: heading names the big number, two session columns),
v4.4 - v5.1 (see the round log below),
v5.2 (the offline bridge can no longer import its own prompt file or an echoed example),
v5.3 (the extended-hours column only exists once extended trading has actually printed),
v5.4 (full sweep: quote fallback no longer blanks the row, import is one transaction off
the main thread, oversized screenshots are resized instead of silently dropped),
v5.5 (second sweep, UI-side: reader view no longer deletes text containing "<"),
v5.6 (everything the app writes lives in Downloads/Portfolio; API keys excluded from
Android's cloud backup),
v5.7 (tools instead of re-reading: property tests found an average-cost bug, full lint
found a latent API error),
v5.8 (reconciled live against Ally: cost basis exact on all 11 positions; the summary now
names the broker-comparable figure instead of just disagreeing with it),
v5.9 (ran the real feeds and the real bytecode: a timezone-less feed date was read four
hours in the future, a fee-heavy sale recovered the wrong price, and an amount-only trade
charged its fee twice),
v6.0 (Robolectric: the database, the storage layer and the rendered UI executed for the
first time - a dead source with an expired certificate, a 33dp tap target, and a first
launch that deleted the backup it should have offered),
v6.1 (the news query that matched the word "five"; the WebView that never stopped running in
the background; Retry-After honoured; the quote tick now follows the eye, cutting the request
rate to Yahoo by ~89% with no loss of on-screen freshness),
v6.2 (the pull-to-refresh spinner that turned for the rest of the session; the feed paints as
it arrives instead of all at once; relevance filtering extended to every per-symbol source;
the reader blocks ads and trackers, clears overlays and can open articles as plain text),
v6.3 (headlines cached on disk and refreshes made additive; the memory trim that emptied the
feed on every app switch; the spinner that appeared on every tab at once; every number in the
price block named; 54.6ms of per-refresh main-thread work removed),
v6.4 (FUNDAMENTALS AND ANALYST RATINGS: the stock page became five tabs - Overview, Stats,
Analysts, Earnings, News - carrying 51 company metrics, professional buy/hold/sell ratings
with each firm's price target newest-first, and earnings estimates; every single number has
an "i" that explains in beginner's terms what it means, what counts as good or bad, how it
moves the price short and long term, and reads back this stock's own value),
v6.5 (THE RESEARCH TAB: the Watch tab gained a Research half with Trending / Best / Worst
sections built from nine Yahoo screeners, r/wallstreetbets mention counts and today's
headlines, scored on the phone by a pure, tested scoring engine that prints its own reasons;
analyst consensus folded in for the visible rows only; the inverse ETF on a failing stock
resolved live from Yahoo's fund index; and both Claude paths - API key and an offline prompt
file carrying the live data - asking the same question through the same parser),
v6.6 (THE INSIDER TAB, REBUILT ON THE FILINGS THEMSELVES, and market news filtered to market
news - see Round 55 below),
v6.7 (RESEARCH IN TABS, and the whole app's network traffic cut by about 80% - see Round 56),
v6.8 (THE APP NOW ACTUALLY STOPS WHEN IT IS PUT DOWN, and immutable data is never fetched
twice - see Round 57).

### Round 58 (v6.9) — THE CHARTS: A RANGE YOU CAN CHOOSE, AND ONE THAT STAYS ON SCREEN

**What TJ asked for.** Seven things, all of them about the charts and the lists:

> when I click on stocks there is a chart showing the price change but I cannot see how long
> the chart is tracking... make a menu or buttons near the charts that let me select time
> length... including 1 day, 5 day, 1 month, 6 months, 1 year, 5 year, all time, and after
> hours/overnight which charts only after hours and afternight movement
>
> for my portfolio or anywhere else that stocks are shown in a vertical list, make the
> separation between stocks a little more pronounced
>
> when I switched apps from the tracker then switched back, the stock charts disappeared...
> cache data that can be cached and only periodically refresh automatically, but refresh
> every time I gesture pull down to manually refresh
>
> for the after market/overnight section of stock prices, stack the dollar amount and percent
> change vertically to be uniform with the other sections
>
> double check how the stock graphs are being loaded/downloaded. sometimes they don't load
>
> when I click on etfs, add a tab called holdings and show all of the stocks that the etf
> currently holds and the percentage of each holding

#### A. "The charts disappeared when I switched back" — and it was one line

`onTrimMemory` released every sparkline from `TRIM_RUNNING_CRITICAL` (15) upward. Android
delivers `TRIM_MEMORY_UI_HIDDEN` (20) on **every single app switch**, with no memory pressure
implied at all — so 20 passed that guard and every switch away emptied every chart in the app.

What makes this worth writing down is not the constant. It is that the comment above it was a
correct argument that had **outlived the design it described**: the series "arrives with the
next quote, so dropping it costs nothing on the wire" was true when written, and stopped being
true in Round 56, when the batched quote endpoint — which returns no candles at all — replaced
the per-symbol chart call. From then on the series was owned by `refreshSparklines` on a
five-minute clock whose `sparkAt` mark was **already stamped**, so the charts did not come back
with the next quote. They stayed blank for up to five minutes.

Nothing is released below `TRIM_MODERATE` (60) now, which is the rule the news caches were
already corrected to in Round 53 for the identical reason. And everything released has a
recovery path that is a SQLite read rather than a download: `restoreSparklines()` puts
`quotes.spark` back on every resume, and `chart_cache` does the same for fetched ranges.

**Rule for the future: when a mechanism moves, re-read the comments that justified the code
around it.** This round found two stale ones and both had become bugs.

#### B. "Sometimes they don't load" — three independent causes

1. `refreshSparklines` un-marked its five-minute throttle only when **every** symbol failed.
   On a partial failure the symbols that failed kept a mark saying "just fetched", so their
   retry was suppressed for the full window while their neighbours drew fine. Which is exactly
   the shape of the report: not every chart, just some, and not for long enough to look like
   an outage.
2. The sparkline pass was the last line of `refresh()`, **below** the "no quotes came back,
   give up" early return. One failed batch — a tunnel, a lift, a cooldown — skipped the candle
   series entirely for that tick, including for symbols whose chart was blank on screen.
3. `MarketData.yahoo` returned `null` the moment query1 was in a **local** cooldown. Cooldowns
   are armed and held per host, so query1 being left alone says nothing about query2 — the
   request was abandoned with a usable host sitting unused. Skipping a cooling host still
   honours it completely: nothing is sent to it.

#### C. The range selector, and what a chart now says about itself

`data/ChartModels.kt` defines the eight ranges; `net/ChartFeed.kt` fetches them;
`ui/PriceChart.kt` draws one with `RangeChips` above it. The chart states its window in words
underneath ("Six months, daily closes"), prints the first and last date on the x-axis, the
high and low on the y-axis, and says what the price did over exactly that window and what it
is measured from. The chosen range is remembered across launches (`Keys.CHART_RANGE`).

Three decisions worth keeping:

- **X comes from the timestamp, not the index.** On a 1D line that is invisible; on the
  after-hours line the session has a real gap in it, and evenly spaced points would draw a
  continuous line across hours in which nothing traded.
- **1D is the REGULAR SESSION ONLY**, with a fallback to whatever exists before 09:30 so the
  tab the screen opens on is never blank. This is not a style choice: `adoptAsSparkline` feeds
  the 1D series straight into `Quote.spark` to avoid a duplicate request, and `spark` has
  always been regular-session-only. Let the two diverge and every row on the portfolio screen
  silently becomes a 24-hour line drawn against a previous-close baseline that no longer
  matches it.
- **After hours is ONE session, not five days of them.** The request has to span several days
  — before 09:30 the only extended points that exist are this morning's, and the after-hours
  they continue from belongs to yesterday — so the parse then keeps only the stretch after the
  most recent regular close, and measures it from that close rather than from
  `meta.previousClose`, which at 18:00 on a Monday would still be Friday's.

#### D. Caching, and the two clocks that decide when to fetch

`chart_cache` (db v7) holds one series per (symbol, range). `loadChart` reads **every** range
for a symbol off disk in one query before it considers the network, so a chart paints from
SQLite on a cold start and offline alike. Each range then has its own TTL, and the rule is the
project's existing one: **never ask more often than the provider can produce a new point.**
Five minutes on a five-minute candle, thirty on a thirty-minute one, a day on a monthly one.
Freshness at the right-hand edge is not bought with requests — `withLiveEdge` replaces the
final point with the live quote price the app already holds.

On top of the TTL sits `intradayChartIsFinal`, which is `pricesAreFinal` applied to the picture
instead of the number: a 1D line whose last point is a regular-session candle cannot grow once
the session has closed, so the automatic refresh stops. It is a test **on the data, not on the
clock alone**, and that matters — in pre-market the fallback chart is still filling in, so the
same function correctly keeps refreshing then.

A pull-to-refresh passes `force` and bypasses all of it, which is the other half of what TJ
asked for.

#### E. The Holdings tab, and the limit it states out loud

`net/HoldingsFeed.kt` asks `quoteSummary` for `topHoldings,fundProfile,quoteType` — its own
request, not more modules on `core`, because `topHoldings` is meaningless for an ordinary share
and Yahoo rejects a bad module set with a 4xx for the **whole** request.

The free feed publishes a fund's **top** holdings, typically ten, not its register. So the tab
says how many positions it is showing and what share of the fund they add up to, and puts the
sector split and asset mix under them — both of which do account for 100%. A limit stated is a
limit; a limit unstated is a lie. Tapping a holding opens that stock, which needed a real
detail stack in `MainActivity` (`detailStack`) so back returns to the fund.

The tab is **hidden** unless the provider's own `quoteType` says this is a fund. "Not a fund"
is cached like any other answer, so opening an ordinary share does not re-ask Yahoo the same
dead question every time.

#### F. The separation between stocks, and why it read as weak

Every row already contained a divider of its own — the one splitting "the stock" from "your
money" inside `StockRowItem` — drawn at exactly the same 1dp in exactly the same colour as the
line between rows. So the strongest visual break on the screen was indistinguishable from a
break **inside** a single row, and the eye had nothing to group by. Making the outer line
heavier alone would have left both shouting: the missing thing was hierarchy, not weight.
`RowSeparator` is 3dp with 5dp of air either side; `InRowDivider` is a 1dp hairline at 45%.

#### G. The sweep — 22 findings, all closed

Nine were in this round's own new code, found by re-reading it adversarially before shipping.
The ones worth remembering:

- The Holdings list was keyed on symbol-or-name. A fund listing two share classes under one
  name is a duplicate key, and a `LazyColumn` handed one **throws**. This project has shipped
  that exact crash three times (news, filings, research rows); it is now keyed by index, and
  `estimates`/`history` — which had the same exposure — are de-duplicated before being keyed.
- `chart_cache` originally had a column named `range`, a reserved word in SQLite's window-frame
  syntax. Whether an unquoted one parses is a property of whichever SQLite the device ships,
  not of this file. Renamed to `range_key` and verified against the real engine.
- `chartDiskRead` was marked **before** the disk read, so a cancellation between the two left a
  symbol marked as read with nothing loaded and the disk cache never consulted again. Same trap
  as the `sparkAt` marks, same fix.
- Round 57's `fgScope` cancellation had no counterpart on the way back in. A stock opened and
  then backgrounded a second later had its loads cancelled, and `LaunchedEffect(symbol)` does
  not re-fire on resume because the key has not changed — so news, numbers, filings and chart
  stayed empty until a manual pull. `DetailScreen` now re-requests them on ON_START; every call
  is cache-first, so an ordinary resume sends nothing.
- One finding was investigated and **dismissed**: `execSQL` binding an `Int` looked wrong, and
  a real-SQLite test proved `DatabaseUtils.bindObjectToProgram` binds any `Number` as a long.
  Written down because the fix would have been harmless and the reasoning would have been wrong.

#### H. Tests: 276 -> 341

`ChartTest` (26) covers the parser against a synthetic five-day body carrying a null candle and
the nested `tradingPeriods` shape, the codec, staleness, the live edge, and the market-aware
refresh gate including the pre-market case. `HoldingsTest` (19) covers Yahoo's three number
shapes and the empty-object trap. `ChartCacheDbTest` (13) runs the two new caches against real
SQLite — it is what proved the `range` column and disproved the bind-argument finding.
`ChartUiTest` (15) and `RowLayoutUiTest` (5) render the new widgets and **measure** them: that
the after-hours dollar and percent are geometrically stacked, that two rows have real space
between them, that every range chip clears 48dp, and that nothing overflows at 1.3x font scale
in either theme.

`RowLayoutUiTest` needs `useUnmergedTree = true`: `StockRowItem` is wrapped in
`combinedClickable`, which merges all descendant semantics into one node, and a merged tree
would let a layout test pass purely because both substrings appear somewhere in the row.

### Round 57 (v6.8) — WHAT THE APP DOES WHEN YOU ARE NOT LOOKING AT IT

**What TJ asked for.** *"do another sweep of the app for improvements and bug fixes and
efficiency of functions and code. make sure when I'm not using the app it isn't using too much
in the background. make sure data that won't change is cached and not reloaded from the
internet. the app can use as much cache and storage as it needs. it can also use as much
mobile data as it needs, but not wasteful"*

#### A. The background audit, and `fgScope`

Round 46 made the POLL LOOP lifecycle-aware and that was recorded as done. The audit for this
round traced every coroutine in the app and found the loop was only three of **forty-two**
`viewModelScope.launch` sites. `viewModelScope` outlives the Activity being stopped by design,
so every on-demand fetch the user had started ran to completion after they had left: a stock
detail load (news + fundamentals + ~195 KB of ratings + filings), a Research build (~18
requests) and its enrichment passes, up to twenty sparkline charts, the advice news preload.
`enrichJob` had no cancellation path at all.

The fix is one mechanism rather than 39 patches: **`fgScope`**, a `SupervisorJob` parented to
`viewModelScope`, cancelled wholesale in `setForeground(false)` and REBUILT in
`setForeground(true)` — a cancelled Job stays cancelled, so rebuilding first is what makes the
next foreground pass work at all. Eleven on-demand paths were moved onto it. **Writes,
backups and Claude requests deliberately stay on `viewModelScope`** — those must finish.

`Http.get` now holds its connection in an `AtomicReference` and disconnects it from
`invokeOnCompletion`, so cancelling stops the transfer instead of only stopping what had not
started yet. It must be an `AtomicReference` and not a captured local: the disconnect runs on
a different thread from the read.

| Not in use | Before | After |
|---|---|---|
| Poll loop, feed pass, EDGAR pass | cancelled | cancelled |
| Detail loads, Research build + enrich, sparklines, advice preload | ran to completion | cancelled |
| In-flight sockets | transferred to completion or a 15s timeout | disconnected |
| WebView | paused on ON_STOP only | paused on ON_PAUSE too |
| Memory trims | not delivered once the Activity was destroyed | delivered for the life of the process |

`MemoryTrim.installProcessWide` is the third row that matters: `Activity.onTrimMemory` is only
delivered while an Activity instance exists, and a backgrounded app whose Activity has been
destroyed but whose process survives — the normal state — was getting no trims at all.
Registered on the APPLICATION context, it is delivered for the life of the process.

#### B. Data that cannot change is never fetched again

- **Unparseable filings are skipped permanently** (`Keys.INSIDER_SKIP`, capped at 4,000). A
  filed Form 4 is immutable; one the parser cannot read today it can never read.
- **Conditional GETs on all four fundamentals providers.** Yahoo `quoteSummary` needed a
  crumb-independent cache key (`cacheAs`) or the rotating crumb made every request a cache
  miss by construction.
- **The inverse-ETF mapping is carried across research rebuilds**, and the symbol search keeps
  a bounded memo (48 entries, access-ordered, cleared on a memory trim; empty results are
  deliberately NOT memoised).

#### C. Efficiency

`Relevance.Subject` hoists the per-symbol pattern work out of the research loop — the old code
recompiled the same regexes inside a headlines x universe double loop, on the order of 200,000
compilations per build. `parseUsDate` moved to a ThreadLocal, two `ShortVehicle` regexes were
replaced or hoisted, `cachedQuotes` hoists its 13 column indices out of the row loop, the
duplicate-detection queries were rewritten so they can use their index, and the research and
advice caches moved off the main thread.

#### D. The adversarial review (10 findings, all fixed)

The two worth remembering:

- **`Http` could cache a body truncated by its own cancellation, under a valid ETag** — and
  then serve it as a fresh 200 forever through the 304 path, with each 304 renewing retention.
  A body is now counted as it is read and cached ONLY if it is complete.
- **Cancellation was counted as `noteUnreachable`**, arming a 5-minute cooldown on a perfectly
  healthy host every time the user switched away mid-refresh — the new cancellation path would
  have made the app slower, not faster, without this.

The rest: `searchSymbols` had no `finally` (`_searching` stuck true across a background cycle);
`refreshSparklines` stamped its 5-minute clock outside the launch with an unreachable un-mark
(blank charts for five minutes after backgrounding); insider skips were not persisted in
exactly the case they exist for; `loadInsider`'s guard was outside the launch (an unrecoverable
per-symbol lockout); `cacheResearch` was outside `enrichVisible`'s `finally`; `MetricUi`'s
price bucket collided between "no price" and $1.000-$1.002; `ReaderScreen` never called
`onResume()` on ON_START (a visible-but-frozen WebView in split-screen).

**A test trap, written down.** `BackgroundTest` hung the Gradle worker forever on the first
run: a `SupervisorJob()` parented to `runBlocking`'s own job never completes, so `runBlocking`
waits on it for eternity. The helper owns its own root `Job()` and each test calls `dispose()`.

### Round 56 (v6.7) — TABS, AND AN APP THAT STOPS ASKING FOR WHAT IT ALREADY HAS

**What TJ asked for.** Two things: move Trending / Best / Worst into tabs at the top of the
Research screen, and then *"make sure the online data pulls from different sources are
efficient and not pulling too many requests to get banned. any data pulled from online should
be cached when needed, storage is not a concern, nothing should need to be reloaded from the
Web if it's already stored in the app"*.

#### A. Research in tabs

`SecondaryTabRow` under a pinned title row; the title and tabs do not scroll, so the tabs are
reachable from anywhere in a long list. Each tab keeps its own scroll position - three
`rememberLazyListState()` calls, created unconditionally, because a hand-built `LazyListState`
in a map has no Saver and silently loses every position on a rotation. The selected tab is
persisted in `Keys.RESEARCH_TAB`, since the screen leaves composition on every visit to
another bottom-bar tab. The Claude tools moved to the BOTTOM of each list: they were the
tallest thing on the screen and they stood between the reader and the rows.

Nothing about the data changed. One pass still builds all three lists; ten rows of each is
still all that costs per-row requests.

#### B. The traffic, measured then cut

The audit counted the app at **~3,900-4,300 requests an hour** with 16 holdings and 8 watched
symbols, market open. Roughly 70% of it was one line: a Yahoo *chart* request per symbol every
15 seconds, to read a single price out of a response that also carried 78 candles.

| Source | Before | After |
|---|---|---|
| Yahoo chart quote poll | ~3,840/hr | 240/hr — one batched `v7/finance/quote` per tick |
| Sparklines | (part of the above) | ~192/hr — own 5-minute clock, visible symbols only |
| Per-symbol news | 480-1,000/hr | ~100/hr — only while a headline screen is up |
| Market-wide feeds | 140/hr | 140/hr, but now mostly bodyless 304s |
| EDGAR | ~50/hr | ~50/hr, listings now conditional |
| **Total** | **~3,900-4,300/hr** | **~750-800/hr** |

Overnight and at weekends the quote poll now stops entirely rather than running at the
15-minute floor.

#### C. "Nothing should be reloaded if it's already stored"

Three changes together:

1. **`http_cache` (db v6).** The conditional-GET cache moved from a 48-entry heap map to
   SQLite. The old one was sized for 48 against a working set of ~79 URLs, so it thrashed and
   most feeds never got to send a validator at all; `onLowMemory` dropped whatever survived;
   and a process death dropped everything, so every cold start re-downloaded every feed in
   full. On disk it holds validators AND bodies, bounded by age (30 days, renewed on each
   304) and total size (~24 MB).
2. **`conditionalKey = true` everywhere it is safe** - the two Reddit aggregators, all nine
   Yahoo screeners, Yahoo trending, EDGAR listings, Nasdaq analyst consensus, Yahoo symbol
   search, the short-vehicle search. Anything whose URL is stable across a poll.
3. **TTLs matched to how fast the data actually changes.** The worst was analyst ratings at
   90 minutes for a ~195 KB payload that gets a handful of new rows a YEAR; now 12 hours.

#### D. Bugs found and fixed on the way

Dead code with no callers (`refreshAllNews`, `MarketData.lookupName`); `Screener`,
Yahoo-trending and `ShortVehicle` falling through to the second Yahoo host while the first was
in *our own* cooldown, defeating the point of the backoff; `preloadNewsForAdvice` re-running
the full cascade on every tap with no freshness check; six independent call-site semaphores
that between them could open ~25 sockets to two hosts.

Then a full adversarial review of the round itself, which found thirteen more - all fixed, and
all listed in PROGRESS.md. The ones worth remembering:

- **A stale conditional-cache entry was never evicted.** If an origin later stopped sending
  validators, or its body grew past the disk limit, the old entry survived with its old ETag,
  the origin answered 304 against it, and the app served a month-old body as a fresh 200 -
  with every 304 renewing the retention. A 200 that cannot be stored must DELETE.
- **The batch could disable itself permanently.** `resetBatchState()` had no callers, and
  three failed passes - forty-five seconds with no signal at launch - turned the batch off for
  the whole session. Now a manual refresh clears it, and only an *attributable* failure counts:
  a missing crumb or one of the app's own cooldowns says nothing about the endpoint.
- **`newsVisibleAt` was stamped on the wrong transition.** Stamping when a screen becomes
  visible makes the grace window measure how long ago the user ARRIVED, so anyone who read the
  Feed for more than five minutes had no grace at all. Stamp on the way OUT.
- **An imported Claude answer naming a ticker twice crashed the Research tab** and kept
  crashing, because the bad set was written through to the cache. A keyed `LazyColumn` handed
  one key twice throws.

### Round 55 (v6.6) — THE INSIDER TAB SAYS WHAT ACTUALLY HAPPENED

**What TJ reported.** Two things, from one screenshot of the Live feed:

> the news shows a lot of news unrelated to the stock market. see if this can be fixed. the
> insider tab only shows one result, which is broken. make the insider tab find all insider
> trading from the last month, sorted from newest at the top. if possible, make the headlines
> in the insider section say, for example, CEO bought shares, or director sold shares, so I
> don't have to research each filing. but make sure that they are actually purchases and
> sales and not automatic transactions scheduled before hand.

#### A. The news that was not news

The All tab's top rows were "My wife and I are in our 70s. Should we move to California...",
"How these Gen Z workers managed to buy homes in their early 20s", and a piece about job-
recruiter scams. Cause: `News.market()` pulled MarketWatch's `mw_topstories`, which is
MarketWatch's FRONT PAGE - Moneyist letters, retirement columns, lifestyle. Fetched live on
5 Sept 2026: **9 of its 10 items were of that kind**, and every headline in the screenshot
came from it. Google News's BUSINESS TOPIC feed was the second offender - 61 items, 204 KB,
of which about half were general news (a duct-taped airline passenger, a Porsche review).

Fixed on both sides:

- **Sources rebuilt.** `mw_topstories` -> `mw_bulletins` (MarketWatch's markets desk); the
  Google BUSINESS topic -> a targeted markets query; **added** Yahoo Finance's newsroom index
  and Seeking Alpha's market currents, both verified same-day, free and keyless. Not added,
  and written down so it is not re-tried: CNBC's markets and earnings RSS both answer 403 to
  any non-browser client, and `mw_marketpulse` / `mw_realtimeheadlines` are still valid RSS
  that have published nothing since 2024/2025.
- **`net/MarketRelevance.kt`**, a two-tier filter. Tier A (a finance desk - Yahoo, Seeking
  Alpha, Investing.com, the query-scoped Google searches) is filtered ONLY for advice-column
  grammar, because everything such a desk carries is market copy by construction. Tier B (a
  broad source) must additionally show market vocabulary. Three rules, in order: advice-column
  GRAMMAR is fatal on its own; a private-life TOPIC is fatal unless overruled by a ticker or a
  strong market signal (so "UnitedHealth shares fall on Medicare Advantage rates" survives a
  rule written for retirement columns); otherwise a broad source needs some market vocabulary.
  **Measured against 386 headlines pulled live from all eight candidate feeds: 360 kept**, and
  every one of TJ's four is rejected. They are the first block of `MarketRelevanceTest`.

**The trap, written down.** Word boundaries are not optional. Matching "a raise" as a plain
substring rejects "Dow, S&P 500 and Nasdaq close lower as jobs dat[a raise] chances of Fed
rate hike". Everything is normalised to space-delimited tokens and every phrase is matched
with a space on each side. Same class of bug killed "Here's the strategy that [help]ed".

#### B. Why the Insider tab showed exactly one row

Three faults stacked under one screenshot, all fixed:

1. **The headline carried no information.** The old code used EDGAR's atom LISTING title,
   which is now the boilerplate form name - `4 - Statement of changes in beneficial ownership
   of securities` - identical on every Form 4 ever filed. EDGAR used to include the insider's
   name there and does not any more (re-verified live), so `cleanTitle` had nothing to clean.
   **`net/Form4.kt` now fetches and parses the filing itself.**
2. **Only one row survived.** Filings were pushed into `_feed`, which is sorted newest-first
   and capped at 400. Filings are up to two business days old BY LAW; headlines are minutes
   old. Ordinary news sorted over the top of them and evicted every filing but the newest,
   so the tab was filtering a list its rows had already fallen out of. **Own store, own cap.**
3. **It read five filings per holding, with no date bound, and skipped the watchlist.** Now
   `datea=` bounds a month server-side, and held + watched symbols are both covered.

**What a row says now**, e.g. from a live 12-symbol run on 5 Sept 2026:

```
INTC  [BUY]                                       Aug 14
CEO bought 105,263 shares — $10.00M
Lip Bu Tan · at $95.00 · 1.31M shares held after

AAPL  [SELL] [10b5-1 PLAN]                        Sep 03
SVP, GC and Government Affairs sold 1,439 shares — $456K
Jennifer Newstead · at $317.01 · 35,790 shares held after
```

**"Actually purchases and sales, not automatic transactions scheduled before hand"** is the
`InsiderScope` filter, defaulting to `OPEN_MARKET`:

- only transaction codes **P** and **S** count as trades. A (grant), M/X/C (option exercise),
  F (shares surrendered for tax on a vesting), G (gift) are compensation machinery and are
  behind the "Everything" chip;
- and the trade must not be pre-arranged. Since the SEC's December 2022 amendments every
  Form 4 carries a Rule 10b5-1 checkbox, which reaches the XML as `<aff10b5One>`. It is absent
  on older and some agent-prepared filings, so the footnotes are searched for the rule by name
  as well. **This element is the reason the feature is possible at all.**

On that live run the three chips gave 7 / 43 / 109 rows. Seven unplanned open-market trades in
a month across twelve megacaps is the honest number, and it is the signal - which is why the
empty state for that scope explains itself rather than looking broken.

**Cost.** One listing request per symbol (`datea` makes a month 8.6 KB instead of the 109 KB a
year costs) plus one request per filing not already cached. A filed Form 4 is immutable, so
the accession-keyed cache is exact: a cold start on 12 symbols measured 12 + 110 requests and
1.47 MB, and a steady-state pass is 12 requests. `MAX_DOCS_PER_PASS = 120` bounds a cold start
across ALL symbols, with listings gathered and sorted first so the budget always buys the
NEWEST filings rather than one busy symbol's back catalogue.

**Traps found by `tools_insider_sim.py` running against the live service, not by fixtures:**

- **`type=4` is a PREFIX match.** EDGAR returns 424B5 prospectuses too, and their full
  submissions are HALF A MEGABYTE. The exact `<filing-type>` is checked in the listing, before
  anything is fetched.
- **A leaf value may hold a footnote instead of a value** -
  `<transactionPricePerShare><footnoteId id="F1"/></transactionPricePerShare>` - in roughly
  one filing in ten. The naive fallback returns the footnote element AS the value; a number
  survives that by defaulting to zero, but a text field would have put raw XML on screen.
- **One decision, seven lines.** A broker filling a large sale across price bands files each
  band separately. Unmerged they read as seven separate sales. Merged at the weighted average,
  and `sharesAfter` is taken in DOCUMENT order, not by date - four of the seven NVDA lines
  share one date, and picking by date reported a holding several hundred thousand shares high.
- **Booleans arrive as both `1`/`0` and `true`/`false`**, from different filing agents.
- **EDGAR files people LAST FIRST MIDDLE** ("TAN LIP BU" is Lip-Bu Tan) but files entities as
  themselves - so the reorder is guarded against corporate-form words.
- **Do not blanket title-case a shouted job title**: it turns "CEO" into "Ceo" and
  "EVP, GBUL, SIPS" into "Evp, Gbul, Sips". Short all-capital words are abbreviations and stay.

### Round 54 (v6.5) — THE RESEARCH TAB: WHAT THE WHOLE MARKET IS DOING

TJ: "somewhere in the watchlist tab, make a tab for research. In research, make sections for
trending stocks, best stocks, and worst stocks. For trending, a blend of wallstreetbets most
frequently mentioned for the day and stocks mentioned in the news frequently for the day, and
next to each write a short summary why it is trending. For best stocks, a current list of
excellent buys based on blended strong buy analyst ratings, good fundamentals and growth,
catalysts, and anything else showing good reasons to rise from the current price. For worst
stocks, the opposite — failing companies likely to lose value — and if possible tell me the
ETF or stock that shorts that particular stock. For this entire section, Claude may be used
either with the API or an option to export a file I can put in the Claude app that Claude
will understand with no explanation from me, and Claude outputs an answer file I import back
that fills the research section with current data."

Answered up front (from the previous session's questions): **the app scores, Claude explains**;
**ten rows per section with a Load-more button, and nothing over ten loaded unless it is
pressed**; **the export file bundles the live data**.

#### The decision that shapes everything: the app scores, Claude explains

`net/ResearchScore.kt` is a set of PURE functions — no network, no clock, no model. It takes
numbers the app fetched and returns a 0-100 score, the reasons behind it in plain English, and
a confidence figure saying how much of the input was actually reported. Three consequences,
and they are the point:

1. **Testable.** `ResearchTest` feeds it hand-built rows and asserts the ordering, so a sign
   flip fails a build instead of surfacing as a confident, plausible, wrong recommendation.
2. **It shows its work.** Every row on screen prints the reason lines that produced its score.
3. **It degrades honestly.** A missing field scores zero for its component rather than being
   guessed at, so a thinly-covered small cap cannot outrank a covered one on absent data.

Claude never decides the ranking. It writes the paragraph under it, and the UI labels that
paragraph CLAUDE so it is always clear which half of a row is arithmetic and which is opinion.

#### Where the data comes from — 18 requests for the whole tab

The app's standing rule since Round 50 is that no provider may be hammered, so the pass is
deliberately WIDE AND SHALLOW:

| Source | Requests | What it gives |
|---|---|---|
| Yahoo predefined screeners × 9 | 9 | ~450 EQUITY quotes with price, market cap, forward P/E, book value, both EPS figures, 50/200-day averages, the 52-week range and the next earnings date |
| Tradestie + ApeWisdom | 2 | r/wallstreetbets mention counts, 24h-ago comparison, sentiment |
| Yahoo trending tickers | 1 | what people are LOOKING UP, a different signal from what they post about |
| `News.market()` (already existed) | ~6 | today's market-wide headlines |

**The news COUNT is computed locally.** Rather than one news request per candidate — 450
requests — today's headlines are fetched once and matched against every candidate with
`net/Relevance.kt`, the same matcher that fixed the FIVE bug. Zero extra requests, and the
matching headline becomes the row's "why it's trending" line before Claude is involved at all.

`v1/finance/screener/predefined/saved` answers a plain GET: no key, and **no cookie+crumb**,
unlike `v10/quoteSummary` (see `FundamentalsFeed`) and `v7/finance/quote`, which both refuse
without one. Verified live against all nine list ids on 5 September 2026.

#### TJ's ten-row rule is enforced ON THE WIRE, not just in the layout

A section holds a buffer of up to 50 rows and shows ten. The expensive per-row work is what is
actually gated:

* **Analyst consensus** (one Nasdaq call per symbol: buy/hold/sell counts + average price
  target) runs over a window of TWENTY — ten more than the page. It has to be wider than the
  page or the coverage could not change which ten are shown, and analyst ratings were half of
  what the Best list was asked to be built from. The window is then re-ranked and the top ten
  displayed.
* **The inverse-ETF lookup** (up to two Yahoo fund searches per symbol) runs for the visible
  TEN only, and only after the ranking has settled — never for the wider window, or it would
  be spent on rows that then fell off the page.

"Load more" reveals ten already in memory and pays for exactly those ten lookups.
`analystDone` / `vehicleDone` mean nothing is ever fetched twice.

#### The inverse ETF is resolved LIVE, not from a table

TJ asked the Worst section to name what shorts a given stock. There is no free API for that
mapping, and the obvious fix — hard-code a table — is wrong: single-stock inverse funds launch,
close, reverse-split and rename constantly, and an APK cannot know its table has gone stale.

So `net/ShortVehicle.kt` searches Yahoo's own fund index, which every issuer's naming formula
gives away:

```
Direxion Daily TSLA Bear 1X ETF      GraniteShares 2x Short NVDA Daily ETF
Tradr 1.5X Short NVDA Daily ETF      T-REX 2X Inverse CRWV Daily Target ETF
```

Every one contains the underlying's ticker AND an inverse word. Both halves are required and
that is the whole matcher — it keeps working as the roster changes, because the roster is
Yahoo's, not ours. Live: "TSLA bear" → TSLS, "short NVDA" → NVD / NVDS, "PLTR bear" → PLTD.

**Three traps, all covered by tests:**
* `Direxion Daily NVDA Bull 2X` contains the ticker and bets the OTHER WAY — rejected.
* `Direxion Daily Semiconductor Bear 3X` is inverse but not on this stock — rejected.
* `YieldMax Short NVDA Option Income Strategy ETF` (real, and the first hit for "short NVDA")
  sells calls; it does not profit from a decline. Presenting it as a short would have been the
  most misleading thing this screen could do. Rejected by name.

The least-leveraged fund wins the tie, because daily-reset leverage decays. Every row carries
`ShortVehicle.WARNING` — daily reset means these do not track a multi-day move and lose value
in a choppy market even when the direction is right, and heavily shorted names are precisely
the ones that squeeze. That is not boilerplate; it belongs next to the ticker.

#### Both Claude paths ask the SAME question through the SAME parser

`ResearchBridge.bundleJson()` builds one data bundle. `ResearchBridge.prompt()` wraps it for
the Claude app; `Claude.research()` wraps it for the Messages API. Both replies go through
`ResearchBridge.parse()`. One question, one parser — an answer that works on one path cannot
mysteriously fail on the other, and there is one place to fix a schema.

* The prompt file carries **every row on screen** — prices, scores, reason lines, WSB counts,
  top headline, analyst counts — so TJ has to explain nothing. Tested: the file must contain
  the numbers the screen shows.
* Reply parsing reuses `ClaudeBridge.findObject`, now taking a key set so the balanced-brace
  scanner that was port-tested in Python for v5.2 serves both bridges rather than being
  written twice.
* `merge()` keeps **the app's score and reasons** and takes only what Claude is uniquely good
  at — the explanation, the catalyst, the inverse ETF. A returned `conviction` never
  overwrites computed arithmetic; it only orders rows Claude ADDED that the app had no score
  for, and those are appended rather than dropped ("fills the research section with current
  data"). Rows Claude introduced then get a quote fetch so no row on screen lacks a price.
* One file chooser now accepts three kinds of answer, so `importClaudeFile` sniffs for the
  research payload key first: importing a research reply from the Advice tab fills the
  Research tab instead of reporting "nothing usable".
* The v5.2 scar tissue carries over: the prompt file is marked `portfolio-app-prompt-file`,
  its schema uses `<...>` placeholders that cannot parse as JSON, and its instruction wording
  is in `TEMPLATE_PHRASES` — so the prompt sitting in Downloads next to the answer cannot be
  imported as one.

#### Placement, and the two things that break if the sub-tab is local

Research is the second half of the **Watch** tab, not a seventh bottom-bar item — a seventh
would shrink every label below readable on a phone, and the two screens answer the same
question at different scopes. The sub-tab index is hoisted to `MainActivity` because:

1. `VisibleScope` has to know Research is showing. It displays **no watchlist row** — its
   prices come from the screener payload — so leaving it as "the Watch tab" would poll every
   watched symbol behind a screen showing none of them: exactly the waste the visible-scope
   mechanism exists to stop. The decision moved out of `MainActivity` into
   `VisibleScope.choose()` so it is a pure function with tests.
2. Android back has to step Research → Watchlist before leaving the tab, like every other
   layer here.

The tab builds **on first sight and only then** (never on launch, never on a timer), with a
30-minute TTL — ApeWisdom recomputes about every 30 minutes and Tradestie every 15, so a
shorter interval re-fetches the same numbers, and a "best stocks" list that reshuffled every
two minutes would be noise presented as signal.

#### VERIFICATION

* **27 new unit tests** (223 in the suite, all green) covering the parsers against responses
  **captured from the live providers on 5 September 2026** and checked into
  `src/test/resources` — a screener response that stops parsing is not an exception, it is an
  empty section that looks exactly like a quiet market day.
* **`tools_research_sim.py`** — a faithful Python port of the scorers, run against the same
  nine live screeners, printing the top ten of each section as the phone would show it. The
  unit tests prove the scorers are internally correct; they cannot prove the OUTPUT IS
  SENSIBLE. Same technique used for the brace scanner in v5.2: port it, run it on real inputs,
  read the result. Output on 5 Sept 2026 was exactly the right shape — Best led by memory and
  electronics names on single-digit forward multiples with real forward EPS growth and price
  above both averages; Worst led by cash-burning small caps down 50-85% on the year, near
  their lows and on the most-shorted list. **Re-run it if you change the scorer.**
* A finding from the tests, left alone deliberately: Android's `org.json` escapes `/` as `\/`,
  so a reason line reading "Forward P/E 14.00" reaches Claude as `Forward P\/E 14.00`. That is
  legal JSON and every parser reads it back correctly, so it is not post-processed — but the
  test asserts the shape so a future encoder change cannot silently alter what Claude is sent.

#### KNOWN LIMITS OF THE SCORER, WRITTEN DOWN

* The Best list rewards a low forward multiple against forward EPS growth, which **structurally
  favours cyclicals at peak earnings** — a memory or shipping company at the top of its cycle
  looks cheapest exactly when it is most dangerous. The score is a ranking of public numbers,
  not a judgement about where a cycle sits. This is precisely what Claude's paragraph is for.
* Yahoo owns the membership of the nine screens. If Yahoo changes a definition, the candidate
  universe changes with it and nothing here will say so.
* Heavily shorted names dominate the Worst list by construction, and those are the squeeze
  candidates. The warning under the section says so; the score does not adjust for it.
* The research payload is stored as ONE settings row (`Keys.RESEARCH_CACHE`), not a new table
  — it is a single document, replaced wholesale, never queried into. A table would have bought
  a schema migration (the one part of this app where a mistake destroys user data) for nothing.
  It is also **excluded from the JSON backup** on both export and restore: a couple of hundred
  KB of screener output that is stale within the hour has no business in the file that carries
  the transactions.

### Round 53 (v6.4) — EVERY NUMBER OTHER APPS SHOW, AND AN "i" THAT EXPLAINS IT

TJ: "for each stock, whether I own it or search for it, include tabs for that stock's current
financial information that other stock apps have - P/E, dividends, growth, and all other
relevant data. For each set of data let me press a small 'i' which explains in a complete
beginner's terms what that data means and how it may affect the stock value both long term and
short term, and whether it is positive or negative. Also a tab for analyst ratings - buy, hold
or sell, with each analyst's rating and target price, sorted newest first. Find a good source."

**THE SOURCE QUESTION, ANSWERED BY CALLING THEM.** Four candidates were probed live rather
than read about:

| Provider | Result |
|---|---|
| TipRanks unofficial API | 403 behind a Cloudflare interstitial. Unusable. |
| Nasdaq `api.nasdaq.com` | 200, keyless. Consensus target + buy/hold/sell split + a summary block. NO per-analyst detail: `upgradesDowngrades` is empty and `brokerNames` is a bare list of firms. |
| Finviz `quote.ashx` | 200. An 84-row snapshot table AND a dated analyst table with targets. robots.txt permits /quote.ashx. A scrape, so it is last. |
| **Yahoo quoteSummary v10** | **The answer.** `upgradeDowngradeHistory` carries firm, action, fromGrade, toGrade, epochGradeDate, priceTargetAction, currentPriceTarget AND priorPriceTarget - exactly what was asked for, keyless, from the provider the app already uses for quotes. |

**YAHOO v10 IS NO LONGER ANONYMOUS, AND THE ERROR IT RETURNS IS A LIE.** Verified against the
live endpoint, all four ways round:

```
no cookie, no crumb  ->  429 "Too Many Requests"   <-- NOT a rate limit. It is auth.
crumb only           ->  401 {"code":"Unauthorized","description":"Invalid Crumb"}
cookie only          ->  401 Invalid Crumb
cookie + crumb       ->  200
```

The handshake is: `GET https://fc.yahoo.com/` (answers **404**, and sets the `A3` cookie on
that response) then `GET /v1/test/getcrumb` with it. `FundamentalsFeed.YahooAuth` does this
once per launch, using a process-wide `java.net.CookieManager` installed as the default
`CookieHandler` so `HttpURLConnection` replays the cookie itself. The crumb is NOT persisted:
it is only valid against the cookie it was minted with, and the cookie lives in memory, so
saving it would restore half a pair and produce a confident 401.

**TRAP, and it is guarded in the code:** the crumb endpoint answers in PLAIN TEXT, so when
Yahoo is throttling, the response body is the literal string `Too Many Requests`. Sent as a
crumb, that produces a 401 that looks exactly like a parser bug. `looksLikeCrumb()` rejects
anything containing whitespace, `<` or `{`.

**WHAT WAS BUILT**

- `data/Fundamentals.kt` - 51 metrics in nine groups, declared once in `MetricCatalog`.
  Values are a `key -> Double` MAP, not fifty nullable fields, so three partial provider
  answers merge in one loop. An absent number stays absent and the screen prints
  "not reported" - never a zero, which is the same class of bug as the fabricated previous
  close fixed in v2.3.
- `net/FundamentalsFeed.kt` - the provider chain Yahoo -> Nasdaq -> Finviz.
  `Fundamentals.merge(base, fill)` only ever FILLS, so precedence is decided by chain order
  alone and a weaker provider can never overwrite a better number.
- **The ratings are a SEPARATE request from the numbers.** Yahoo's rating history is the
  whole history: 195KB for AAPL against ~15KB for every other module combined. It is fetched
  when the Analysts tab is opened, not when the screen is.
- `data/FundamentalsJson.kt` + db v5 `fundamentals(symbol, kind, json, fetched)` - additive,
  one table, JSON payload so adding a metric never needs a migration. Core rows live 6 hours,
  ratings 90 minutes, both painted from disk first so an opened stock is never blank.
- `ui/Explain.kt` - the explainer. Per metric: what it means, what counts as good or bad with
  real ranges, the short-term price effect, the long-term price effect, and a LIVE read of
  this stock's own number with a positive/negative/depends verdict that tints the row.
- `ui/MetricUi.kt`, `ui/DetailTabs.kt`, rebuilt `ui/DetailScreen.kt` - five tabs with the
  price block PINNED above them.

**FOUR PARSER BUGS THE CAPTURED PAYLOADS CAUGHT** (fixtures are real responses in
`app/src/test/resources`, and each of these is now a test):

1. Yahoo sends `{}` for "not reported". Read naively that becomes 0.0 - a wrong number that
   looks like a real one.
2. Finviz's snapshot is **six separate `<table>` elements** inside a CSS grid, so anything
   that walks "the table" finds a sixth of the numbers. The parser walks the
   `snapshot-td-label` / `snapshot-td-content` class markers in document order instead, which
   is layout-independent.
3. Finviz lists **"EPS next Y" twice** - once as a dollar estimate ($9.57) and once as a
   growth percentage (8.41%). Last-one-wins recorded 8.41 as the forward EPS.
4. Finviz prints debt/equity as a multiple (0.78) where Yahoo prints a percentage (78.45).
   Merging them unscaled would make a conservative balance sheet read as a leveraged one
   depending on which provider happened to answer.

**TWO CONTRACTS ARE ASSERTED, NOT SPOT-CHECKED.** Every key in `MetricCatalog` must reach
real explainer prose rather than the generic fallback, and every key any parser produces must
exist in the catalogue. A metric therefore cannot be added to the Stats tab with an empty "i".

**A GAP CLOSED ALONG THE WAY.** A stock opened from SEARCH that is neither held nor watched
has no `Row` - the lists are built from positions and the watchlist - so the price header
never rendered for it at all, even though the polling loop was already fetching its quote
(the visible scope while the detail screen is open is `Detail(symbol)`). The detail screen now
builds a transient row over that quote, and distinguishes "not tracked" from "on your
watchlist" so it cannot offer to remove something that was never added.

**RENDERED TEST FINDING.** `DetailTabsUiTest` measures the real widgets. Its first run failed
on a tap target of 23dp - which turned out to be a TEST bug worth writing down: `boundsInRoot`
is intersected with the clipping viewport, so in a LazyColumn the one row straddling the
bottom edge reports whatever fraction of itself is visible. `SemanticsNode.size` is the
unclipped layout size and is what a fingertip actually gets. Any future test that measures
tap targets inside a scrolling list has to use `size`, not `boundsInRoot`.

### Round 52 (v6.3) — HEADLINES THAT SURVIVE, AND 54ms OFF EVERY REFRESH

Five reports from the phone. One of them was a bug this project introduced two rounds ago.

#### 1. "SWITCH APPS, COME BACK, AND THE NEWS IS GONE" — MY BUG FROM v6.1

`onTrimMemory` released `_feed`, `_news`, `_insider` and `_trending` from
**TRIM_MEMORY_BACKGROUND (40)** upward, on the reading that 40 means "the system is actively
reclaiming memory". **It does not.** Android delivers TRIM_MEMORY_BACKGROUND when the process
simply JOINS the background LRU list - a routine state change that happens on EVERY app
switch, not a pressure signal. So leaving the app emptied the feed every single time, on a
phone with plenty of memory free.

The levels that actually mean memory is short are RUNNING_LOW / RUNNING_CRITICAL while in the
foreground, and MODERATE (60) / COMPLETE (80) once backgrounded. Threshold raised to 60.

**Worth remembering as a general lesson:** the trim levels are not a severity ladder, and two
rounds running I have read them as one. UI_HIDDEN (20) is a state change; BACKGROUND (40) is
a state change; only LOW/CRITICAL/MODERATE/COMPLETE are pressure.

#### 2. THE HEADLINE CACHE (db v4) — "only refresh new stories and keep the loaded ones"

Until now every headline the app had ever shown lived **only in memory**. New `news_cache`
table, keyed on `FeedItem.id` - the SAME expression the feed de-duplicates and keys its
LazyColumn by - so `INSERT OR IGNORE` is exact de-duplication against everything ever seen
rather than against the current page. That is what makes "only fetch what is new" true rather
than approximate.

Verified on the shipped release bytecode:

```
pass 1 stored            60
pass 2 fetched           64
pass 2 genuinely NEW      4     <- only these are written
pass 2 already known     60
cache after both passes  64
```

**Retention is measured on `first_seen`, not `published`,** and that is deliberate: publishers
emit wrong dates - v5.9 found Investing.com dating headlines four hours in the future - and a
story stamped 1970 would otherwise be purged the instant it arrived. Purge at 31 days, once
per launch (a DELETE over an indexed column that can only matter after a month; running it
four times an hour would be pure overhead).

Every publish now MERGES `+ _feed.value` instead of assigning, and writes through to SQLite.
`restoreFromCache()` runs on launch and on every return to the foreground. Bounded at 400
items in memory - load-bearing now the list merges rather than replaces. The detail screen
got the same treatment: it paints saved headlines instantly instead of sitting empty, and its
deep fetch merges rather than assigns (previously a stock's news list quietly got SHORTER
over time as stories fell out of the sources' current windows).

#### 3. THE SPINNER ON EVERY TAB

`manualRefresh` is one flag on one shared `UiState`, and every tab wraps itself in a
`Refreshable` bound to it. Pulling on the Feed - whose pass is 25+ requests and takes many
seconds - left a spinner turning on Portfolio, Watchlist, Activity, Advice AND Settings.
Those screens were reporting somebody else's work. `UiState.refreshSource` plus
`UiState.pulling(surface)`: a screen shows the indicator only for work it started.

#### 4. "TWO PERCENTAGE NUMBERS WITH NO EXPLANATION"

The two session columns are measured against **different baselines** -
`dayChange = price - prevClose` but `extChange = extPrice - price` - and nothing on screen
said so, which is precisely why two percentages side by side were unreadable. Every figure now
carries a tag ("price now", "change") and every column states its baseline: "vs previous
close", "vs today's close", and "vs last close" for pre-market, which is measured from the
PREVIOUS day's close. Render-measured at fontScale 1.0 / 1.5 / 2.0 and on a 320dp screen -
three extra lines in a half-width column is exactly the shape that clipped a percentage in
v1.6.

#### 5. A REAL PERFORMANCE BUG, FOUND BY MEASURING RATHER THAN READING

Benchmarked `News.dedupeKey` on the shipped release bytecode at the load one ordinary feed
refresh puts on it - 400 items x 20 repaints, because the feed is additive and repaints per
symbol:

```
BEFORE   8,000 calls   54.6 ms of MAIN-THREAD work per refresh
```

on a desktop JVM; a phone is several times slower. Two causes:

1. `normaliseTitle` and `stripHtml` constructed their `Regex(...)` objects **inside the
   function body**, so a fresh `Pattern.compile` ran on every call. Hoisted to compile-once
   vals (and the same three in `Insider.kt`) -> **36.0 ms measured, 34% off**.
2. `storyKey` is now memoised by `FeedItem.id`. A story's key never changes, so this is exact
   rather than a heuristic, and it turns 8,000 calls into ~400 plus 7,600 HashMap lookups.

Combined, the per-refresh main-thread cost falls to roughly **2 ms**.

#### 6. AND A PERFORMANCE BUG I INTRODUCED EARLIER IN THIS ROUND

Caught by reviewing my own diff, which is now the most productive habit in this project.
Making the feed additive meant every incremental repaint merged the whole 400-item list - and
the first version handed that entire list to `cacheNews` too. The per-symbol loop repaints
once per symbol, so a 20-symbol refresh ran twenty 400-row SQLite transactions, ~8,000
`INSERT OR IGNORE` statements, essentially all no-ops. `publishAndCache` now takes a separate
`toCache` list of just the new arrivals. The same edit removed an O(symbols^2) list rebuild.

#### Smaller things in the same sweep

- The Feed header said "Pull to load" beside a full screen of saved headlines. `feedAt` is now
  persisted, and the header distinguishes "Updated 12s ago" from "Showing saved headlines".
- A manual pull no longer discards the conditional-GET validators. That was there so a pull
  "really" went to the network, but it means re-downloading several hundred KB to learn what a
  bodyless 304 already says. With stories cached by id, a 304 IS the correct answer.
- Settings gained a "Saved headlines" card - count, age of the oldest, and a clear button.

#### Verification

150 JVM tests pass, including a **real v1 -> v4 database upgrade that keeps every
transaction** and eleven new tests on the cache's identity and retention rules. Lint 0 errors
/ the same 6 deliberately-left warnings. 0 Kotlin warnings. **DB_VERSION 3 -> 4** (additive
only: one CREATE TABLE IF NOT EXISTS, nothing dropped or renamed). BACKUP_VERSION still 2, so
backups stay interchangeable. Signing cert unchanged - v6.3 installs in place over v6.2.

### Round 51 (v6.2) — TJ'S FEEDBACK ON v6.0/v6.1

Four reports from the phone, with a screenshot. All four fixed.

#### 1. THE SPINNER THAT TURNED FOR THE REST OF THE SESSION

The screenshot showed the feed saying **"Updated 12s ago" with the pull-to-refresh spinner
still going.** `refreshFeed`'s guard deliberately attaches a user's pull to a pass already in
flight, so the gesture does not snap back having done nothing:

```
if (_feedLoading.value) {                        // a feed pass is ALREADY running
    if (manual) ...copy(manualRefresh = true)    // attach the user's spinner to it
    return
}
...
} finally {
    if (manual) ...copy(manualRefresh = false)   // but THAT call had manual = false
}
```

The call it attached to was the automatic one, so its `finally` never cleared the flag. The
feed pass is 25+ requests and takes many seconds, so the window is wide - which is why TJ hit
it. **v6.1 did not cause this but made it permanent**: `refresh()` clears the flag
unconditionally, so a later quote tick used to rescue the stranded spinner, and v6.1 stopped
polling quotes on screens that show no prices - the Feed tab being one. The rescue vanished.

Fixed by not clearing it by hand at all. `spinnerShouldShow(userAsked, quotesLoading,
feedLoading)` is a pure function; `syncManualIndicator()` derives the flag from it in both
`finally` blocks and on every polling tick. **The indicator can only be on while work is
actually happening**, so no future branch can strand it. This also fixes the mirror-image bug
where a finishing quote refresh snatched the spinner away from a feed pass the pull was
really waiting on. `SpinnerTest` pins all eight states.

#### 2. THE FEED TOOK A LONG TIME TO APPEAR

The first paint awaited `newsJobs.awaitAll()` - a per-symbol fetch for **every** holding and
watchlist entry, 20 of them behind a gate of 5, i.e. four serial network waves before one row
appeared. The five market-wide feeds finish in under a second and were sitting there
completed, waiting for the slowest per-symbol fetch. Market headlines now paint immediately
and per-symbol ones merge in as each SYMBOL lands. Same requests, same finish time, but the
screen stops being empty almost at once.

#### 3. NEWS UNDER NVDA THAT WAS NOT ABOUT NVDA — AND A ROUND 50 JUDGEMENT REVERSED

TJ's NVDA feed carried *"Why ASML Holding Stock Bumped 4% Higher Today"* and a
ChronoScale/CHRN piece. Both came from a PROVIDER-SCOPED feed, which **Round 50 deliberately
chose not to filter** - the reasoning was that a scoped feed reflects the provider's own
editorial judgement about what belongs on a symbol's page, and measurement showed filtering
Cisco's Nasdaq feed would drop 12 of 15 items. That reasoning was wrong, because the user is
the authority on whether his NVDA tab should contain ASML news. The filter now runs on
**every** per-symbol source.

Checked against TJ's exact items: the filter DROPS the ASML and ChronoScale headlines and
KEEPS the three that genuinely mention NVIDIA (AWS/2M GPUs, Coatue/beyond-NVIDIA, Cramer).

**The objection that stopped Round 50 was re-measured and does not hold.** Only RELEVANT
items count toward `ENOUGH`, so a thin source makes the cascade reach for the next one
instead of stopping early:

| symbol | Nasdaq kept | Google kept | total relevant |
|---|---|---|---|
| CSCO | 4/15 | 18/20 | **22** |
| NVDA | 10/15 | 18/20 | 28 |
| FIVE | 12/15 | 20/20 | 32 |
| AVGO | 11/15 | 20/20 | 31 |

Worst case is 22 relevant headlines against a target of 8. The never-empty valve moved from
per-source to once-per-cascade - **a valve per source would let an all-junk page re-admit its
own junk**, which is the bug being fixed.

#### 4. THE READER: ADS, POP-UPS AND "SIGN UP TO CONTINUE"

`net/AdBlock.kt` - 100 ad-exchange, analytics, session-recorder and push-notification
domains, matched by **host suffix, never by URL substring**. That distinction is the whole
design: a `contains("ads")` rule blocks `reuters.com/business/roadside-ads` and
`downloads.example.com`, and a page that fails to render is a worse outcome than an advert.
Wired into `shouldInterceptRequest`, returning an empty 200; the main frame is never blocked.
Blocking at the network layer is the only place that works - an ad that never loads cannot
render an interstitial, call `window.open`, or re-insert itself.

`DECLUTTER_JS` - restores document scroll (a modal sets `overflow:hidden` on body/html and
leaving it is why a page still will not scroll after the overlay goes), removes fixed/sticky
layers covering >50% of the viewport (or >25% at z-index >= 1000), strips
paywall/modal/overlay-classed blocks over 25% of the screen, and undoes the blur, opacity,
line-clamp and max-height a registration wall applies to the text underneath. **Anything
holding more than 150 words is never touched**, so the article itself is safe. `window.open`
is neutered. Runs on load and from a new "Clear pop-ups" menu item.

**THE HONEST LIMIT, and it is stated in Settings too.** This removes overlays from content the
server ALREADY SENT to the device. It does not defeat a real paywall: where the publisher only
sent a teaser, the rest of the article is not on the phone and no client-side work can conjure
it. No crawler-spoofing, no bypass proxy, nothing touching authentication. Soft registration
walls generally become readable; hard paywalls do not.

Three Settings switches: block ads (on), clear pop-ups (on), always open text-only (off -
opt-in, because silently changing how every article opens would be surprising).

#### VERIFIED IN A REAL BROWSER ENGINE

Chromium via Playwright, driving the JS **extracted from the Kotlin source and confirmed
byte-identical to what ships** (0 `$` characters, so raw-string templating cannot corrupt it).

Real article HTML, fetched live: a nasdaq.com article extracted correctly (12,045 -> 9,639
chars, right H1); a Globe and Mail quote page correctly returned `none`; the blocklist
identified `securepubads.g.doubleclick.net`, `c.amazon-adsystem.com`,
`fastlane.rubiconproject.com`, `sb.scorecardresearch.com` and `www.googletagmanager.com` in
the real markup.

Synthetic registration wall - a full-screen z-9999 modal, `body{overflow:hidden;position:fixed}`,
article blurred/faded/clamped, plus a sticky nav, cookie bar and share rail that MUST survive:

```
PASS regwall removed      PASS sticky nav survives   PASS cookie bar survives
PASS share rail survives  PASS body scrolls again    PASS body not fixed
PASS article unblurred    PASS article opaque        PASS article unclamped
PASS reader output contains no wall text
```

#### Verification

134 JVM tests pass (17 new: `AdBlockTest` including the substring-lookalike false-positive
set, `SpinnerTest` including an exhaustive eight-state truth table). Lint 0 errors / the same
6 deliberately-left warnings. 0 Kotlin warnings. DB_VERSION still 3, BACKUP_VERSION still 2.
Signing cert unchanged, so v6.2 installs in place over v6.1.

### Round 50 (v6.1) — IDLE WHEN IDLE, POLITE ON THE WIRE, AND THE FIVE BUG

Seventh "sweep the app" request, with three specific asks: **idle when not in use**, **do not
get throttled or banned by the data providers**, and **News on FIVE returns stories that just
contain the word "five"**.

A note on process first, because it cost TJ real usage: the previous attempt at this round
ran out of session and left NOTHING behind - all of its work was lost. This round therefore
writes `PROGRESS.md` on every save and ships the archive to the user after every milestone.
**Read `PROGRESS.md` before this file on a cold start; it says where the last session stopped.**

#### 1. THE FIVE BUG — 62% RELEVANT, MEASURED, NOT ESTIMATED

The app asked Google News for:

    "Five Below, Inc." OR FIVE stock

expecting an OR of two alternatives. Google does not evaluate it that way - it matched
documents containing "five" and "stock", which is a template simplywall.st and Yahoo publish
several times a day:

    Axsome Therapeutics (AXSM) Stock Looks Like A Bargain On Its 7x FIVE YEAR Run
    Charter (CHTR) Stock Still Looks Undervalued After Its 82% FIVE YEAR Fall
    Upstart (UPST) Stock Looks Expensive Following a 90% FIVE YEAR Slump

Reproduced against the live feed. The query was **also unstable**: an identical repeat two
minutes later returned ZERO items.

Five variants were fetched and scored against the live feed, 40 items each:

| query | relevant |
|---|---|
| `"Five Below, Inc." OR FIVE stock` (old) | unstable, 62% at best |
| `"Five Below"` | 95% |
| `"Five Below" OR "NASDAQ: FIVE"` | 95% |
| `"Five Below" OR "(FIVE)"` | **17%** - Google strips the parens, leaving bare "five" |
| `"Five Below" stock` | **97%** |

**Adding the ticker as an alternative makes it WORSE. Do not put it back.**

`net/Relevance.kt` is the second half, because a search engine is a black box that can change
its mind and the no-company-name path cannot be scoped by query at all. Three ways to
qualify: the full company phrase; the leading distinctive word when capitalised AND not
ordinary English; the ticker as a standalone UPPER-CASE token.

**THE TRAP, WRITTEN DOWN SO IT IS NOT REINTRODUCED.** The obvious fix - "match the first word
of the company name" - brings the bug straight back, because "Five" leads both "Five Below"
and "Five Year Run". `AMBIGUOUS_NAME_WORDS` is what stops that: numerals, plus the finance
vocabulary that doubles as a company name (`target` - "raises price target", `gap`, `block`,
`open`, `snow`). A holding whose leading word is on that list must be named in full or carry
its ticker. The opposite failure is guarded too: "Ford Motor Company" is almost never written
out, and NVIDIA is rarely capitalised as the exchange lists it, so `Ford recalls trucks` and
`Nvidia earnings beat` both match.

**THE SAME BUG EXISTED A SECOND TIME**, in the Feed's "My stocks" filter. `matchHolding` did
`title.uppercase().split(WORD_SPLIT)` and then looked for a held ticker - upper-casing the
headline first destroys the only thing separating a ticker from an ordinary word, so
"...FIVE YEAR RUN" was filed under the Five Below holding. `companyWordIndex` had it too, via
the first word of "Five Below, Inc." of length >= 4. Both now go through `Relevance`, so the
rule cannot drift apart in two places again.

**Verified end-to-end against the live feed driving the SHIPPED release bytecode** (reflection
into `app/build/tmp/kotlin-classes/release`), not a model of it:

    BEFORE  40 fetched, 40 shown, 25 about Five Below   62%
    AFTER   40 fetched, 40 shown, 40 about Five Below  100%

Same volume of headlines, all relevant - the filter dropped nothing from the new query.

**MEASURED DECISION: the filter runs on Google News ONLY.** Applying it to Nasdaq's
per-symbol feed was measured first: it would drop 4/15 on FIVE but **12/15 on CSCO**, and
those drops are Arista, AMD, Nokia, Broadcom, Datadog - Cisco's competitors and sector.
Nasdaq puts peer coverage on a symbol feed deliberately; that is editorial breadth, not
pollution. TJ's complaint was a LINGUISTIC false match, which only the unscoped source
produces. Do not "fix" this the other way in a later round.

#### 2. THE APP NOW IDLES WHEN IT IS NOT BEING USED

The inventory came back clean on everything most apps get wrong: no `GlobalScope`, no
`Thread`/`Timer`/`Handler`/`Executor`, no WorkManager, no services, no receivers, no wake
locks. All async work is in `viewModelScope` and the polling loop has been lifecycle-gated
since v3.3. Three real gaps remained.

**THE WEBVIEW WAS NEVER PAUSED — the largest background cost in the app, untreated.**
Composition is not lifecycle: leaving the app with an article open does not dispose the
reader, so the WebView kept its renderer process, JavaScript timers, CSS animations and
pending network alive with the screen off, for as long as Android left the process up. Now
ON_STOP -> `onPause()` + `pauseTimers()`, reversed on ON_START. **`pauseTimers()` is the one
that matters** - it is process-wide and is what actually stops the work - and it is therefore
also called on dispose, or leaving the reader while paused would leave every future WebView
in the process with dead timers.

**CANCELLING THE POLLING LOOP WAS NOT ENOUGH.** `setForeground(false)` cancelled `autoJob`,
but that job only STARTS work: `refresh()` and `refreshFeed()` each launch their own
coroutine in `viewModelScope`, which outlives the Activity being stopped. A tick that fired a
moment before the user left carried on - on a 20-symbol portfolio a feed pass is the
market-wide pull plus per-symbol news plus an EDGAR call per holding, dozens of requests
still in flight with nothing on screen to draw their answers. `refreshFeed` is now cancelled
outright; `refresh()` is one bounded wave whose results are worth keeping for the return.

**NOTHING WAS EVER GIVEN BACK UNDER MEMORY PRESSURE** - no `onTrimMemory` anywhere.
`util/MemoryTrim.kt` is a weak-listener registry (weak on purpose: this must never be the
reason a cleared ViewModel is pinned alive, which would make the memory fix a memory leak).

**THE TIERING IS THE WHOLE DESIGN, and the obvious implementation is wrong.** Dropping the
caches at `TRIM_MEMORY_UI_HIDDEN` - which fires every single time the app goes off screen -
means every return re-downloads every feed: MORE provider load, in exchange for memory the
system had not asked for. So only the sparklines go at RUNNING_CRITICAL (they ride the next
quote and cost no extra request), and the network-backed caches only from
TRIM_MEMORY_BACKGROUND upward. The quote PRICES are deliberately never dropped - they are the
offline fallback, and dropping them would turn a memory trim into a blank screen.

Also: the Activity forwards EVERY trim level. Gating on `level >= TRIM_MEMORY_UI_HIDDEN`
discards exactly the ones worth acting on - RUNNING_LOW (10) and RUNNING_CRITICAL (15) are
the system saying it is short WHILE THE APP IS IN FRONT, which is when releasing most likely
prevents a kill. The levels are not a severity ladder; UI_HIDDEN (20) is a state change.

#### 3. PROVIDER LOAD: MEASURED FIRST, THEN CUT 89%

From the app's own constants (11 holdings + 9 watched, 15s interval):

| source | load |
|---|---|
| **quotes (Yahoo)** | **4,800 requests/hour, SUSTAINED, while the app is open** |
| feed (Google/Nasdaq/MarketWatch/Investing) | 500/hour peak |
| SEC EDGAR | 528/day, gate of 3 against a published 10/s cap - fine |
| Reddit aggregators | 192/day - fine |

The daily total is not the risk; the loop already stops when the app leaves the screen. The
risk is the RATE - 80 requests a minute to one host for hours - and rate is what a provider
sees. A large share of it was for prices nobody could see: sitting on Activity, Advice or
Settings still cost the full 80 a minute, and one stock's detail screen cost 20 requests to
update one number.

**THE TICK NOW FOLLOWS THE EYE** (`VisibleScope`):

| screen | before | after |
|---|---|---|
| Portfolio | 4,800/h | 2,640/h |
| Watchlist | 4,800/h | 2,160/h |
| One stock's detail | 4,800/h | **240/h** |
| Feed / Activity / Advice / Settings / reader / search | 4,800/h | **0** |
| average | 4,800/h | **560/h (89% fewer)** |

**NOT a freshness compromise.** Everything ON SCREEN still updates at exactly the interval TJ
chose; what stops is fetching prices that are not displayed. Anything paused is refreshed
once the instant it comes into view.

**`Retry-After` WAS BEING IGNORED.** A 429 got the app's own 30-second backoff whatever the
server asked for, so a host answering `Retry-After: 3600` was retried about 120 times inside
the window it had explicitly asked to be left alone. That is not a throttle any more - from
the provider's side it is a client refusing an instruction, and it is the single signal a
provider can see that separates a well-behaved client from an abusive one. Now both header
forms are honoured, the server's value wins when larger, clamped to 6h so a bad header cannot
disable the app forever.

**THE HTTP CACHE BOUND WAS COUNTED WRONG** - 48 entries x 120,000 was documented as "well
under 6MB", read as bytes. A Kotlin String is UTF-16, so the real ceiling was **11.5MB**,
nearly double, in a process that also holds a Compose UI and a WebView. Replaced with a
measured total-size budget that evicts oldest-first, so the bound holds whatever mix of feed
sizes turns up.

**A RESUME DEBOUNCE.** `setForeground(true)` fired a full refresh unconditionally, so the
notification shade, a permission dialog or an incoming call each cost a complete round of
requests to every provider - burst shape, triggered by something the user did not think of as
using the app.

**AND A REQUEST METER**, per host over a rolling hour, surfaced in Settings. Every other
claim in `Http.kt` is an argument; this is the only part that can be checked. The budget has
been derived on paper twice in this project's history and been wrong both times.

#### 4. THREE DEFECTS FOUND BY REVIEWING THIS ROUND'S OWN CHANGES

Worth recording because all three were introduced by the fixes above.

1. **Pull-to-refresh broke on no-price screens.** Scoping the polling set also scoped the
   MANUAL refresh, so pulling down on Activity or Advice silently refreshed nothing. A
   user-initiated refresh now always covers every tracked symbol.
2. **The trim gate was backwards** - see the level note above.
3. **A detail screen must not be debounced.** The resume debounce applied to scope changes
   too, so opening a stock within 4s of a tab switch could show a stale price on a screen the
   user had just deliberately opened. Opening one stock costs exactly ONE request.

#### 5. THE VIEWMODEL SEAM ROUND 47 ASKED FOR

Round 47 closed saying `PortfolioViewModel` is the largest untested thing in the project
because its `init` starts a polling loop that never lets a test settle, and that extracting
the loop behind a seam would fix it. `VisibleScope.symbolsFor(scope, rows, allTracked)` is
that seam for the half that matters most - it alone decides how much the app asks of its
providers - and is a pure function with 10 tests, including both fallback traps: a cold start
with no rows must still fetch something, and an empty watchlist must NOT fall back to
everything (which would quietly undo the entire saving).

#### Live source check (4 Sept 2026)

Nasdaq, Google News (per-symbol and the BUSINESS topic), MarketWatch, Investing.com and
ApeWisdom all returned current, parseable data. Yahoo and SEC 429 from this container as
always documented - datacenter IP, not the app. **Tradestie is still dead** (expired
certificate, now 8 months); `Social.merge` still falls back to ApeWisdom.
**Stooq could not be checked**: it now refuses this container outright - even its root page
resets the connection - so the quote fallback's URL is UNVERIFIED from here. Per this
project's own rule it was NOT changed on an unverifiable signal. Worth a look from the phone.

#### Verification

117 JVM tests pass (30 new: 17 `RelevanceTest`, 13 `ProviderSafetyTest`, 10
`VisibleScopeTest`, 3 added to `UiTest`, minus overlap). Lint: 0 errors, the same 6
deliberately-left warnings. Kotlin: 0 warnings - the one introduced (a deprecated
`TRIM_MEMORY_COMPLETE`) was fixed by naming the severity in `MemoryTrim` rather than
suppressed. DB_VERSION still 3, BACKUP_VERSION still 2 - no migration, backups stay
interchangeable. Signing cert unchanged, so v6.1 installs in place over v6.0 with the
portfolio untouched.

### Round 47 (v6.0) — RAN THE ANDROID CODE, NOT A MODEL OF IT

Sixth "sweep the app" request. Rounds 44-46 all ended the same way: reading these files has
stopped paying, and the value is in RUNNING things. Round 46 ran the compiled Kotlin on a
plain JVM, which only reaches the classes with no Android in them. **This round stood up
Robolectric**, which runs the real Android framework on the JVM - and that finally reaches
the three biggest untested surfaces in the project:

- `data/Db.kt`, 741 lines holding every transaction the user owns, against REAL SQLite;
- `util/Storage.kt` and `util/CrashLog.kt`, the two things that exist to save the user;
- **the Compose UI itself**, rendered and then MEASURED.

`app/build.gradle.kts` gains a `test` source set for this. It changes nothing about the
shipped app - `assembleRelease` never compiles it, and the APK was checked afterwards to
confirm zero test classes are packaged.

**74 JVM tests now run on every `./gradlew :app:test`,** alongside the four JVM suites from
Round 46. Five real defects came out of it. Two of the biggest results, though, are
NEGATIVE: `Db` and `Storage` are correct, and that is worth knowing rather than assuming.

#### 1. THE WALLSTREETBETS SOURCE HAS BEEN DEAD SINCE JANUARY

`api.tradestie.com` presents a Let's Encrypt certificate that **expired on 3 January 2026 -
244 days ago.** Verified against the live host, and cross-checked against apewisdom.io and
sec.gov through the same path, both of which present valid certificates:

    api.tradestie.com   issuer=CN=R13,O=Let's Encrypt   valid 2025-10-05 .. 2026-01-03
    apewisdom.io        issuer=Google Trust Services    valid 2026-09-04 .. 2026-12-03
    www.sec.gov         issuer=DigiCert                 valid 2026-05-27 .. 2026-12-11

Android validates certificates with no opt-out, so this fails the handshake on any network,
every time. **It has never once succeeded on the phone since January.**

Nothing broke, and that is precisely why nobody noticed: `Social.merge` already falls back to
ApeWisdom, so the board still fills. But **Tradestie is the only source of the sentiment
score**, so the Bullish/Bearish column on the WSB tab quietly stopped appearing eight months
ago with nothing on screen saying why.

- [x] The source is KEPT. A certificate renewal is a five-minute job on their side and the
      merge already handles both cases, so this fixes itself if they ever do it.
- [x] `Trending.source` now names the AGGREGATOR ("Tradestie" / "ApeWisdom") rather than
      "r/wallstreetbets", which was the same string for both and told the UI nothing.
- [x] The WSB tab says where the list came from, and when no row carries sentiment it says
      that too, in one line. A feature that vanishes silently is the thing to avoid.

#### 2. WITH NO SIGNAL, THE APP HAMMERED EVERY HOST FOR EVER

Found while fixing the above, and it is the bigger of the two. `Http`'s cooldown only ever
watched for **429/503/403**. A failure with no status code at all - no signal, DNS not
resolving, a handshake that cannot complete - never armed it. So an offline phone went on
retrying every host on the normal schedule: for a twenty-symbol portfolio that is **twenty
doomed connection attempts every fifteen seconds, indefinitely**, each a DNS lookup and a TCP
connect going nowhere.

- [x] Three CONSECUTIVE failures to connect now arm a cooldown: 15s, 30s, 1m, 2m, 4m, held at
      5m. Three, not one, because a phone moving between wifi and cell produces single
      failures constantly.
- [x] **Escalation is per cooldown, not per request** - and getting this wrong was a real
      flaw in the first version of the fix. A refresh fires every symbol at once, so one
      offline tick produces twenty failures within a second; counting those individually took
      a momentary blip straight to the five-minute ceiling. There is a test for exactly this.
- [x] **A user pull-to-refresh clears every cooldown.** The backoff exists to stop the app
      hammering on its OWN schedule; it must never make a deliberate refresh do nothing, and
      coming back into signal and pulling down is when the user most wants it to try.
- [x] The counters are updated under a lock. `x += 1` on a `@Volatile` is not atomic and up
      to five of these run concurrently - true of the original rate-limit counter too.

#### 3. A TAP TARGET THAT A PREVIOUS ROUND'S COMMENT SAID WAS FIXED

Rendering the holding row and measuring every clickable node:

    holding row: tap targets under 44dp:
      "News" is 33dp tall

The comment at that call site reads *"24dp tall was under the 48dp minimum ... Taller now"*.
It went from 24dp to **33dp** and nothing checked, so it is still under Android's minimum and
still sits inside the row's own click area - meaning a near-miss opens the detail screen
instead of the news, which is the exact bug that change was written to fix.

Material3's own components (Button, IconButton, Switch) get 48dp automatically through
`minimumInteractiveComponentSize`. A **hand-rolled** tappable - a `Box` or `Text` with
`.clickable {}` on it - gets nothing and ends up exactly as big as its text plus padding.

- [x] `Modifier.minTapTarget()` in `ui/Common.kt`, applied to both hand-rolled targets in the
      app: the News chip and the summary card's "Show the detailed numbers" toggle.
- [x] Asserted by measurement, at normal text size, at double text size, and on a 320dp
      screen. **Any future hand-rolled tappable needs this modifier** - Compose will not do it.

#### 4. A FIRST LAUNCH DELETED THE BACKUP IT SHOULD HAVE OFFERED

`tidyDownloadsOnce()` runs when `DOWNLOADS_TIDIED` is unset - which is the state of a BRAND
NEW DATABASE. Its empty-ledger branch deleted the root `portfolio-autosave.json` outright,
reasoning that with nothing on file there was nothing to protect.

That is exactly backwards. "No transactions AND an autosave sitting in Downloads" is what a
phone looks like **immediately after the app has been uninstalled and reinstalled, or its
storage cleared**: the private snapshots went with the old install, the "you had N
transactions" high-water mark has reset to zero so the missing-data banner cannot fire, and
that file is the last copy of the portfolio. The app would run once and delete it before the
user had any chance to restore.

- [x] An empty ledger is now a reason NOT to delete a backup. The root copy is left alone.
- [x] `checkForRecoverableBackup()` looks for it on a first launch that has never held a
      transaction, confirms it really parses as a portfolio with rows in it, and the Portfolio
      tab offers **"There is a portfolio backup on this phone"** with a one-tap merge restore,
      instead of "No holdings yet". One MediaStore lookup on a first launch, none afterwards.

#### 5. THE SAME HEADLINE COULD APPEAR TWICE IN THE FEED

The All tab merges per-symbol headlines with market-wide ones, and the same wire story
genuinely arrives through both - Nasdaq carries it on the NVDA feed while Google's business
topic carries it too, with " - Reuters" bolted on the end. `FeedItem.id` differs only in
`kind` between those two, so de-duplicating by id kept both.

- [x] `publishFeed` now de-duplicates by STORY first, using `News.dedupeKey` - the same
      normalisation the per-symbol lists already use for exactly this - and by `id` second.
- [x] **Insider filings are deliberately excluded** from the story pass: EDGAR dates a filing
      to the day, two insiders can file identically-titled entries, and only the accession
      number separates them. Merging those would hide a filing.
- [x] The id pass is kept even though the story pass is stricter, so the v3.1 invariant
      (dedupe key and LazyColumn key must agree) holds by construction rather than argument.

#### What was executed and found CORRECT

This is the more valuable half of the round, because it retires whole files from suspicion.

| area | tests | result |
|---|---|---|
| `Db` against real SQLite | 20 | a REAL v1 database upgraded to v3 losing nothing; a downgrade kept the data instead of throwing; export/restore round trip exact; API keys filtered both ways; cash rows keep their null symbol; NaN overrides rejected; merge does not clobber live settings; Replace clears overrides/watchlist/imports; a corrupt file cannot destroy the ledger; fuzzy dedupe accepts a 1c and a 40c re-read and rejects a genuinely different quantity; `deleteTxnsForSymbol` spares NULL-symbol and NVDAX rows |
| `Storage` + `CrashLog` | 15 | the rolling 14-snapshot window holds and drops the OLDEST; snapshots newest-first; the crash log's 60KB cap keeps the NEWEST end; installing the handler twice does not chain it to itself; `Fmt` money/date/relative rules |
| `MarketClock`, news dedupe, WSB momentum | 15 | every phase boundary on the right side; the weekend closed; intervals floored outside the session; one wire story from three publishers collapses to one; a short "AAPL - Reuters" headline is not over-trimmed into a collision |
| the rendered UI | 18 | nothing clips at fontScale 1.0 / 1.3 / 2.0, on a 320dp screen, or with a 4-figure price and a 5-figure position; the extended-hours column is absent during the session and present after it; a missing previous close reads "not available"; dark mode renders |
| every live endpoint | - | Nasdaq, Google News (per-symbol, and the business topic through its 302), MarketWatch, Investing.com, SEC EDGAR and ApeWisdom all returned current, parseable data. Yahoo 429s from this container as always documented - that is the datacenter IP, not the app. **Only Tradestie is broken.** |

#### The editor dialog does NOT have an infinite composition loop

Worth recording, because it looked exactly like one and cost real time. Rendering
`TxnEditorDialog` under Robolectric never reaches idle - the error message literally reads
*"they may be causing an infinite composition loop"*, which in this app would be a genuine
battery bug. It is not: a Material `AlertDialog` containing nothing but plain
`OutlinedTextField`s, **with zero app code in it**, fails the same way, while a bare dialog
settles immediately. The limitation is the harness's handling of text fields inside a dialog
window. A guess along the way - that the editor's own `verticalScroll` was redundant because
Material3 scrolls its text slot - was checked against the decompiled `AlertDialogKt` and was
WRONG: it uses `ColumnScope.weight(1f, fill = false)` and does not scroll. The editor's
scroll is necessary and its structure is correct.

#### For the next round

The method that keeps paying is going outside the source. Three things are now cheap that
were not before: **the whole test suite runs with `./gradlew :app:test`**, Robolectric can
reach anything that does not need a real display, and the JVM suites in `tests/` run against
the compiled release classes. The obvious next targets, in order of expected value:

1. **A reconciliation against a fresh Ally statement** on a day with no same-day buys - still
   the highest-value check in the project, and still TJ-side.
2. **The Activity tab against a real statement.**
3. `PortfolioViewModel` itself is the largest thing still untested, because its `init` starts
   a polling loop that never lets a test settle. Extracting that loop behind a seam would
   make the whole ViewModel testable.

DB_VERSION still 3, BACKUP_VERSION still 2 - no migration, backups stay interchangeable.
Signing cert SHA-256 still `2e8c3847...`, so v6.0 installs in place over v5.9 with the
portfolio untouched. Lint: 0 errors, the same 6 deliberately-left warnings. 0 Kotlin
warnings. Verified that no test class is packaged into the APK.

### Round 46 (v5.9) — POINTED THE TOOLS AT THE THINGS THAT ARE NOT CODE

Fifth "sweep the app" request. Round 44 ended by saying static reading of these 34 files was
exhausted and that the value left was in RUNNING things; Round 45 said the same. So this
round read the sources once for orientation and then spent its effort on two things no
previous round had done:

  1. **Fetched every feed the app actually polls** and looked at what comes back.
  2. **Ran the compiled release classes on the JVM** - `Fmt`, `Txn`, `Ledger`, `Position`,
     `Fees`, `Claude`, `ClaudeBridge`, `News` all load outside Android, so the tests exercise
     the SHIPPED BYTECODE rather than a Python or Java port of it that can drift from it.

**Both found real, live defects. The read-through again found nothing the tools did not.**

#### 1. INVESTING.COM HEADLINES WERE DATED FOUR HOURS IN THE FUTURE

The single worst find, and it is on screen right now.

    $ curl https://www.investing.com/rss/news_25.rss
    <pubDate>2026-09-04 18:38:16</pubDate>      # and `date -u` says 18:45:59

That stamp is UTC and says so nowhere. `Fmt.parseRss` matched it with `"yyyy-MM-dd HH:mm:ss"`,
which SimpleDateFormat resolves in the DEVICE'S timezone - so on TJ's phone a headline
published seven minutes ago was recorded as 18:38 Eastern, **four hours and seven minutes in
the future.** Two consequences, both visible:

- `Fmt.relative` computes `now - published`, gets a negative number, falls into the
  `diff < 5_000` branch and prints **"just now"** - for four hours, on every Investing.com
  headline, however old it really is.
- `publishFeed` sorts `sortedByDescending { published }`, so the entire Investing.com feed
  floats above genuinely fresher stories. The Live feed's "All" tab was permanently led by
  one publisher for structural reasons nobody could see.

It also reaches Claude: `headlinesBlock()` prints the same relative time into the advice
prompt, so the model was told a stale story was breaking news.

- [x] **A feed date with no timezone is UTC**, not the phone's clock. Investing.com is the
      one in the app today; the rule is right for feeds generally.
- [x] **Fractional seconds get their own patterns** (`...ss.SSSXXX` and friends). EDGAR's
      atom `<updated>` is second-precision today - verified by fetching it - but a stamp
      carrying milliseconds matched NONE of the ISO patterns and fell through to bare
      `yyyy-MM-dd`, which is exactly what would flatten insider-filing ordering back to day
      resolution and undo Round 31's `<updated>` / `<filing-date>` split.
- [x] **A pattern must now consume the WHOLE string.** This is the reason (2) was a wrong
      answer instead of no answer: `SimpleDateFormat.parse` stops at the first character it
      cannot use and reports success on the prefix, so `yyyy-MM-dd` cheerfully "parsed" a full
      timestamp and threw the time away. Non-lenient parsing plus a `ParsePosition` check
      means a pattern either matches all of it or is skipped. **Any pattern added here must
      keep that property.**

Verified against the shipped `Fmt`, in `America/New_York`, on the real strings from all five
feeds: 4 of 7 formats were wrong before, 0 after.

#### 2. A SALE WHOSE FEES EXCEED ITS PROCEEDS RECOVERED THE WRONG PRICE

Found by property-testing the ledger's own reconciliation invariant, 5,000 randomised
histories, then delta-debugging the failures down to one row:

    SELL 0.1458 CSCO, no price recorded, proceeds $5.09, fees $5.31

`amount` is the signed cash effect, here **-$0.22** - a sale that cost money net.
`Ledger.unitPrice` recovered the gross as `abs(amount) + fees` = 0.22 + 5.31 = **$5.53**
instead of $5.09, wrong by twice the shortfall, and realized P/L stopped reconciling with
cash by $0.44. `amount + fees` - the signed value - is exact on either side of zero.

Ally's own schedule caps the low-priced commission at 5% of trade value, so its fees can
never outrun the proceeds; an imported row or a hand-entered account charge can. Narrow, but
it is silent arithmetic corruption in the one file everything else trusts.

**Re-run after the fix: 20,000 randomised histories against the SHIPPED `Ledger` bytecode,
0 violations** - lifetime P/L identical under both methods, share counts agreeing, no
negative shares or basis, no closed position keeping basis, no NaN in `totals()`, and
`equity - deposits == realized + unrealized + dividends - fee rows` to 1e-6.

#### 3. AN AMOUNT-ONLY TRADE CHARGED ITS FEE TWICE

The total on a statement is the NET cash that moved - the fee is already inside it. Three
places divided that net by the share count to get a price, and `cashEffect` then subtracted
the fee **again**:

| 100 shares of a sub-$2 stock | before | after |
|---|---|---|
| user enters a $155.95 net debit, $5.95 commission | recorded **-$161.90** | -$155.95 |
| cost basis | $161.90 | $155.95 |
| the same shape on a sale ($144.05 net credit) | recorded **+$138.10** | +$144.05 |

- [x] `Txn.unitPriceFromTotal` takes the fee out first, using the same convention
      `Ledger.unitPrice` already used to recover a price from a stored row - so a trade
      entered by total and one read back from the ledger finally agree.
- [x] Applied at all three sites that had the same line: the transaction editor,
      `Claude.extractTransactions`, and `ClaudeBridge.parse`. **They must change together.**
- [x] On a zero-fee trade it is byte-identical to the old `amount / quantity`, so every
      ordinary Ally trade is unaffected - verified as a control case.
- [x] The field is now labelled **"Total cash moved, fees included"**. "Total amount" left it
      ambiguous whether the fee was in or out, and the two readings give different bases.
- [x] The dialog now shows the cash effect it is about to record ("This takes $155.95 out of
      your cash"), and says so when a price and a total disagree and the price wins - three
      fields interact in ways no label was ever going to explain.

#### 4. A TRADE WITH NO SHARE COUNT MOVED MONEY NOBODY COULD ACCOUNT FOR

Also from the property tests. Both `fifo()` and `averageCost()` skip a BUY/SELL with no
quantity - correctly; there is no position to build from one - but `cash()` still counts its
amount. So the money moves and **nothing on any screen explains where it went**:

    DEPOSIT $1,000  +  BUY NVDA, 0 shares, $500
    -> cash $500, no holding, no realized figure
    -> "Since you started" reads -$500 with nothing behind it

The editor has required a share count since v3.5, but **both import paths accepted these** -
which is exactly what a misread screenshot line produces - and so does a restored backup.

- [x] Both importers now refuse a quantity-less BUY/SELL **and say so in the review dialog's
      notes**, with the count and the reason. Never dropped in silence: that is the v5.4 rule
      about oversized screenshots, applied to the same class of problem.
- [x] Settings gains a **"Transactions your totals cannot explain"** card listing any such row
      already on file, with the dollar amount it is distorting the headline by. **Deliberately
      no auto-fix** - deleting a user's transaction on the app's own initiative is not this
      app's job. It names them; the Activity tab is where they get corrected.

#### 5. The rest of the sweep

- [x] **`MarketData` fabricated a previous close.** When Yahoo supplied neither
      `previousClose` nor `chartPreviousClose` it fell back to today's price, which produces a
      day change of exactly +0.00 / +0.00% - a wrong number that looks like a real one. This
      is the same mistake the Stooq fallback was corrected for in v2.3; the rule is "never
      fabricate a previous close" and it now holds on both providers.
- [x] **`preferredModel` ranked "is it an alias?" above the version number.** A list holding
      `claude-sonnet-4-5` and `claude-sonnet-5-20260201` picked 4-5, because undated won
      before the versions were compared - and a new family usually ships as a dated snapshot
      before its bare alias exists, which is precisely when auto-pick matters. Version first,
      alias as the tie-break, which is what the comment always claimed it did.
- [x] **"Replace all" sat where Cancel belongs.** In the restore dialog the destructive option
      was the `dismissButton` - the left-hand button, where every other dialog in this app puts
      Cancel - and wiped every transaction, override, watchlist entry and import record on one
      tap. There was no Cancel at all. It now asks a second time, in red, and Cancel is back.
- [x] **Deleting a transaction from the detail screen took one tap**, while the identical
      control on the Activity tab has always confirmed. Confirmed now on both - it is a 20dp
      icon inside a row whose whole width opens the editor, so the near-miss went the
      destructive way.
- [x] **Settings was doing disk work per keystroke again.** The refresh-interval field bumped
      `infoTick`, which invalidates the remembers that list the snapshot directory and read
      the crash-log file - so typing "15" ran two directory listings and two file reads on the
      UI thread. This is the v2.4 rule leaking back in through a side door. The status line it
      was there to refresh is already keyed on the field's own text.
- [x] **The crash-log Save and Copy buttons did file IO and a MediaStore write on the main
      thread.** Moved to IO like every other file path in the ViewModel.
- [x] **The feed's two side requests were orphaned on failure.** `socialJob` and `marketJob`
      were launched into `viewModelScope` rather than as children of the coroutine that awaits
      them, so a throw in the headline stage left two HTTP requests running with nobody to
      read them - and one fresh pair per cycle on a refresh that keeps failing. As children
      they cancel with their parent.
- [x] **Headlines showed raw entity source.** `&rsquo;`, `&mdash;`, `&#8217;` and a
      double-escaped `&amp;#8217;` all reach `stripHtml` as literal text - XML parsing resolves
      neither HTML-only names nor a double escape. Decoded now, with an unknown entity left
      exactly as found.

#### What was checked and found correct (so the next round need not re-derive it)

The day-P/L split, over 200,000 randomised portfolios: `dayBasis` never negative, the
percentage never NaN or infinite, and - the one the summary card depends on - the app's
"Today" and the broker's figure **never differ while `boughtTodayCount` is 0**, so the
reconciliation note can never read "the 0 holdings you bought today". The Ally fee schedule,
8 cases including both 5%-cap boundaries: unchanged. The v5.2 template defences: a prompt
file is still refused, an echoed placeholder row still discarded, a real reply still loads.
The rebuild of v5.8 from this archive is **byte-for-byte the same package, version and
signing certificate as the APK on the phone**, so the source here is what is running.

#### For the next round

The method that paid this round was going OUTSIDE the source: reading the live feeds, and
running the compiled classes. Both are repeatable - `tests/ShippedTest.java`,
`tests/LedgerPropTest.java` and `tests/BridgeTest.java` are in the archive and run against
`app/build/tmp/kotlin-classes/release` with the Kotlin stdlib on the classpath. The obvious
next targets in the same spirit: **a reconciliation against a fresh Ally statement on a day
with no same-day buys** (which should now show the two "Today" figures agreeing and the note
absent), and **checking the Activity tab against a real statement**.

DB_VERSION still 3, BACKUP_VERSION still 2 - no migration, backups stay interchangeable.
Signing cert SHA-256 still `2e8c3847...`, so v5.9 installs in place over v5.8 with the
portfolio untouched. Lint: 0 errors, the same 6 deliberately-left warnings. 0 Kotlin warnings.

### Round 1 (v1.0)
- [x] Toolchain, scaffold, signing, themes, launcher icon
- [x] Models, Db, Ledger, Format, Http, MarketData, News, Claude
- [x] Theme, Widgets, Dialogs, ViewModel, five screens, MainActivity, signed APK

### Round 2 (v1.1)
- [x] Bigger 5-tab icon bar; new Watchlist tab
- [x] Type-ahead symbol search by ticker or company; add/remove watch anywhere
- [x] Continuous persistence + last tab remembered
- [x] Last-upload date, range covered, added/skipped; duplicate flags in review
- [x] Backup to Downloads + restore from file (Merge / Replace all)
- [x] No-API-key Claude bridge in Advice and Activity

### Round 3 (v1.2) — all requested items done
- [x] **Pull-down-to-refresh on every tab** (Portfolio, Watchlist, Activity, Advice,
      Settings, and the detail screen)
- [x] **Bigger header buttons with icons** — 52dp circular targets, 28dp glyphs, for
      back / refresh / sort / search / edit / add
- [x] **Tap any stock on any tab** for full detail: shares, average cost, market value,
      cost basis, total and day P/L with percentages, realized, portfolio weight, chart,
      its own transaction list, and news
- [x] **Edit and delete from the detail screen** — tap a transaction to edit every field
      or delete it; edit shares/average cost; add a buy or sell; delete the whole position
- [x] **Long-press any stock** for a menu: View details, News, Edit shares/average cost,
      Add a buy or sell, Watch toggle, Delete position
- [x] **Day AND after-hours percentages beside their own prices**, green when positive
      and red when negative, on every list row and on the detail header

### Round 4 (v1.3)
- [x] **Navigation-bar overlap fixed** — TJ reported Android's nav bar covering the tab
      labels; `BigTabBar` now consumes the navigation-bar inset (see KEY NOTES).

### Round 5 (v1.4)
- [x] Verified signing-cert continuity across every delivered APK
- [x] `Db.onUpgrade` documented additive-only; `onDowngrade` no longer throws;
      `onOpen` repairs an interrupted upgrade
- [x] v1 -> v2 migration simulated with real rows: lossless
- [x] Daily automatic JSON snapshot to Downloads (also after each import), toggleable
- [x] Settings "Data safety" card: package, app version, DB version, live signing prefix

### Round 6 (v1.5) — reconciliation against the live Ally account
- [x] Diagnosed every discrepancy between the app and Ally's own screens
- [x] FIFO cost basis implemented and made the default; average cost kept as a Settings toggle
- [x] `previousClose` replaces `chartPreviousClose` (ex-dividend day-change bug)
- [x] Summary card relabelled: "Open positions G/L" ties out to the broker's Total G/L
- [x] Market value, cash, and 14 of 16 per-stock day changes already matched exactly

### Round 7 (v1.6)
- [x] Market-hours vs after-hours/overnight changes labelled separately on every row
- [x] Per-holding market value (shares x current price) shown on the row and in detail
- [x] TODAY and ALL TIME dollar gain/loss per row, green/red, with percentages
- [x] Detail screen: market value shows the "N x price" arithmetic, extended-hours dollar
      P/L added, and a note that gains cover currently-owned shares only

### Round 8 (v1.7)
- [x] Automatic snapshots moved to private app storage, invisible in Downloads
- [x] One-time cleanup of the auto-backups older builds left in Downloads
- [x] "Restore the latest automatic snapshot" button in Settings (they are otherwise
      unreachable now that they are hidden)
- [x] Rolling window of 14 snapshots so the folder cannot grow without bound

### Round 9 (v1.8)
- [x] Fixed percentages being clipped off market/after-hours changes (fixed-width column)
- [x] Change amounts sized by stock price - no more "+0.1300" on an $89 ETF
- [x] Live extended-hours price shown as "now $X" beneath the after-hours change
- [x] Detail screen labels the big price as the regular-session close and marks the
      extended-hours price "(live)"

### Round 10 (v1.9)
- [x] Backup rewritten as format v2 - complete, self-describing, manifest-checked
- [x] Import history now included; replace-restore clears stale overrides/watchlist
- [x] Fixed org.json null-symbol corruption of cash rows on restore
- [x] Restore reports exactly what came back, per section, and runs atomically
- [x] Dollar values trimmed from 4 decimals to 3 (sub-$1) / 2 (normal)

### Round 11 (v2.0) — full sweep
Bugs fixed
- [x] Thread-unsafe shared DecimalFormat/SimpleDateFormat in `Fmt` (real corruption risk)
- [x] Settings pull-to-refresh indicator was hard-wired to `false` and never appeared
- [x] Price pill rendered green for symbols with no quote at all; now neutral grey
Efficiency
- [x] Full ledger replay on every Settings keystroke removed
- [x] Quote cache: one transaction per refresh, not one write + one IO hop per symbol
- [x] Concurrent HTTP capped at 6
- [x] `symbolsToQuote()` reuses computed rows instead of replaying the ledger a second time
Backup
- [x] Export + write moved off the main thread
- [x] Backup verifies itself by reading the file back and comparing against the database
- [x] Success message now names what was verified, not just "saved"

### Round 12 (v2.1) — Advice tab audit + second sweep
- [x] Retired default model id would have 404'd every Advice run for a fresh install
- [x] API errors were being stored and cached as the portfolio summary
- [x] Truncated (max_tokens) replies now explained instead of dumped as raw text
- [x] Weak first-brace JSON scanner replaced with the shared robust one
- [x] Pointless second API call on 401/429 removed
- [x] Web search tool spec re-verified against live docs (still `web_search_20250305`)
- [x] Confirmed 180s read timeout is adequate for web-search runs

### Round 13 (v2.2)
- [x] Same-day purchases measured from the fill price, not yesterday's close
- [x] Position day % now over the day's opening basis, not the stock's move
- [x] Portfolio total day gain uses the same rule so header and rows agree
- [x] Row/detail label the figure "from your fill" on days you bought

Answers given to TJ (keep consistent if asked again):
- Cost basis can exceed deposits because realized gains are recycled into new buys:
  deposits 5135.00 + realized 102.84 = 5237.84 = cost basis 5227.36 + cash 10.47.
- "Realized (closed trades)" is exact, not averaged: for each sale, (proceeds - fees)
  minus the actual cost of the specific shares sold (FIFO = oldest lots first).

### Round 14 (v2.3) — third sweep
- [x] refresh() could permanently strand `loading = true`, killing all later refreshes
- [x] Stooq fallback fabricated a day change from today's open; now reports unavailable
- [x] Offline launches now show how old the cached prices are
- [x] Reviewed MarketData/Http/News: gzip handling, extended-hours derivation and the
      Yahoo host failover all check out; no changes needed

### Round 15 (v2.4) — fourth sweep
- [x] Settings ran a directory listing, two DB reads and a SHA-256 hash per keystroke
- [x] SearchSheet did two DB reads per result row; now one per list
- [x] `autoBackupIfDue` materialised every transaction just to count them (now COUNT(*))
- [x] News items with an unparseable date rendered as "Jan 1" (epoch); date now omitted
- [x] Added ISO-8601-with-offset and other pubDate patterns the RSS parser was missing

Upgrade compatibility (asked 2026-09-04): v2.3/v2.4 installs cleanly over ANY older build
including v1.4 - same package, same signing cert (2e8c3847...), versionCode only ever
increases (5 -> 15), and DB_VERSION has been 2 since v1.1 so no migration even runs.
Android does not require sequential versions.

### Round 16 (v2.5) — maximum freshness
- [x] 1-minute chart resolution, pre/post included
- [x] Extended-hours uses the live meta price, not a candle close
- [x] 15s default refresh (5s minimum), all symbols fetched in parallel
- [x] Headlines auto-refresh every 3 minutes
- [x] Exchange print time + pull age surfaced on the detail screen
- [x] DB v3: quotes.quote_time (additive migration)

- **LIVE FEED (v3.0)** - the research above, built. All free, all polling, no backend:
  - Headlines: existing `News.forSymbol` per followed symbol.
  - Insider: `net/Insider.kt` hits
    `browse-edgar?action=getcompany&CIK=<TICKER>&type=4&output=atom` for HELD symbols only.
    MUST send a declared User-Agent with a contact address (SEC rejects anonymous bots) and
    stay under 10 req/s - both handled in `Insider.headers()`.
  - WSB: `net/Social.kt` merges Tradestie (15-min, sentiment) with ApeWisdom (30-min,
    rank_24h_ago/mentions_24h_ago momentum). Reddit's own API is NOT used - unauthenticated
    endpoints are 403 and all access needs approval since June 2026.
  - Refreshed on the same `NEWS_REFRESH_SECS` cadence, on pull, and on first visit.
  - **EDGAR has its own `Semaphore(4)`**, separate from `MAX_PARALLEL_REQUESTS = 16`. The
    SEC caps callers at 10 requests/second and the general gate would have breached it with
    a big portfolio. Do not route SEC calls through the shared gate.
  - **Never set `Accept-Encoding` by hand.** Insider.kt originally sent
    "gzip, deflate", which turns OFF HttpURLConnection's transparent decompression - and
    `Http.read` could not decode deflate, so a deflate response would have been garbage.
    The header is gone AND `Http.read` now handles deflate as a belt-and-braces fix.
  - **`refreshFeed()` writes into `_news` as well as `_feed`.** The auto loop used to call
    `refreshAllNews()` and then `refreshFeed()`, fetching every headline TWICE every three
    minutes. Only `refreshFeed()` runs on the timer now.
  - `FeedItem.id` is the single expression used for BOTH `distinctBy` and the LazyColumn
    key. When those two differed, dedupe could keep two rows Compose considered identical,
    which throws "key was already used". Keep them the same.
  - Deliberately NOT a websocket: Android Doze kills background sockets and there is no FCM
    path without a server. Polling is what makes this work at all. Do not "upgrade" it to a
    stream without also adding a foreground service.
- **IMPORT DEDUPE IS THREE LAYERS (v3.0)** - TJ asked that re-imported screenshots never
  double-count, "regardless of the rules already in place":
  1. The prompt now embeds `existingDigest()` - the last 120 recorded transactions - so
     Claude filters duplicates at the source, on both the API and the no-key file path.
  2. `Db.findDuplicateId()` matches FUZZILY: same type + symbol + calendar day + share
     count, with the amount within max(2c, 0.5%), or within $1 when the share count agrees.
     Exact matching alone failed because amount/quantity rounds differently between reads.
  3. `commitImport()` de-duplicates the batch against ITSELF, since overlapping screenshots
     each carry the shared rows.
  Verified by simulation: exact repeat, 1c-off repeat, 40c-off repeat and an intra-batch
  repeat all skipped, while a same-day same-symbol trade with a DIFFERENT quantity was
  correctly kept.
- **PORTFOLIO UI (v3.0)** - the row is split by a divider into "the stock" (symbol, holding,
  price, day move, after-hours) and "your money" (YOUR SHARES / YOU MADE TODAY / SINCE YOU
  BOUGHT). The header leads with plain language - "Everything you have is worth", "Today",
  "Since you started", then money in / stocks worth / cash - with the accounting detail
  behind a "Show the detailed numbers" toggle so nothing is lost.

### RESEARCH ON FILE (Sept 2026) — real-time news feed feasibility
Investigated at TJ's request; NOT built. Verdicts:
- Company news: MODERATE. Best value = Alpaca Algo Trader Plus $99/mo (Benzinga-sourced
  news WebSocket, unlimited symbols) - BUT verify whether news is on Alpaca's free tier
  first, docs impose no plan restriction. Finnhub /company-news is free (60 calls/min,
  personal-use licensed) and is the sane zero-cost option. Massive (ex-Polygon.io, renamed
  Oct 2025) news is "updated hourly" at EVERY tier including $199 - real-time needs their
  $99/mo Benzinga add-on, REST only, no news websocket. NewsFilter.io $35/mo claims
  sub-500ms indexing with a socket stream. Benzinga direct pricing UNVERIFIED (403s).
- SEC Form 4 insider: EASY and FREE. EDGAR atom feed
  `browse-edgar?action=getcurrent&type=4&output=atom` measured at ~1 MINUTE from filing
  acceptance to visibility. Needs a declared User-Agent, 10 req/s cap. Per-company variant
  accepts a TICKER directly. Ceiling is legal, not technical: Form 4 is due within 2
  business days of the trade, so the fact is already stale. Paid $55/mo sec-api.io buys
  300ms instead of 60s - not worth it.
- WSB trending: EASY but FRAGILE. Reddit's unauthenticated .json endpoints are DEAD
  (403 confirmed, blocked May 2026) and all API access now needs approval under the
  Responsible Builder Policy (June 2026); commercial tier starts ~$12,000/mo. Two free
  no-auth aggregators confirmed live and returning data: tradestie.com/apps/reddit/api
  (15-min refresh, 20 req/min, sentiment scores) and apewisdom.io/api (30-min, includes
  rank_24h_ago momentum fields). No SLA on either. Quiver's WSB dataset is NOT listed on
  their current API pricing tiers - do not pay $75/mo for it without confirming.
- Biggest obstacle for news: Android background execution, not the API. A no-backend app
  cannot hold a WebSocket through Doze; it needs a foreground service with a persistent
  notification, and there is no FCM path without a server.

### Round 17 (v3.0)
- [x] Live Feed tab built from the research: news + SEC Form 4 + WSB trending, all free
- [x] Portfolio row redesigned: stock vs your-money separated by a rule
- [x] Summary rewritten in plain language with an expandable detail card
- [x] Import dedupe hardened to three layers and simulation-verified
- [x] Six tabs now; icon 26dp / label 11sp to fit

### Round 18 (v3.1) — feed sweep
- [x] SEC requests moved to their own 4-way gate to respect the 10 req/s cap
- [x] Removed the hand-set Accept-Encoding that disabled transparent gzip; added deflate
- [x] Auto loop was fetching every headline twice every 3 minutes
- [x] LazyColumn key and dedupe key unified into `FeedItem.id` (crash risk)
- [x] Trending list de-duplicated by symbol (crash risk if both sources agreed)
- [x] Screenshot-prompt build moved off the UI thread (it reads every transaction)

### Round 19 (v3.2) — screens sweep
- [x] "News" chip never scrolled to news - dead `scrollToNews` parameter now implemented
- [x] Stock detail gains an Insider filings section (SEC Form 4, tap through to SEC.gov)
- [x] Advice request now includes insider filings + WSB attention on holdings
- [x] Watchlist rows: removed the redundant Remove/long-press strip left over from the
      pre-v3.0 row design; the hint moved into the column header
- [x] Feed retries a failed first load instead of staying blank until a manual pull

### Round 20 (v3.3) — background behaviour
- [x] App polled every 15s forever while backgrounded (battery); now tied to the lifecycle
- [x] Returning to the app refreshes instantly instead of waiting for the next tick
- [x] Insider filings moved to a 30-minute cadence - they are 2-day-lagged by law
- [x] Fixed a deprecated LocalLifecycleOwner import (lifecycle-runtime-compose)

### RESEARCH ON FILE (4 Sept 2026) — which free news RSS feeds actually work

Every candidate below was fetched and read before being wired in. Do NOT add a feed to the
app without doing the same: several well-known ones are still *valid RSS* but have not been
updated in over a year, so they look fine in code and silently contribute nothing.

VERIFIED WORKING AND CURRENT (items from the same day as the check):
- MarketWatch Top Stories  `https://feeds.content.dowjones.io/public/rss/mw_topstories`
- Investing.com Stock Market News  `https://www.investing.com/rss/news_25.rss`
- Nasdaq PER-SYMBOL  `https://www.nasdaq.com/feed/rssoutbound?symbol=AAPL`
- (already in use) Yahoo Finance per-symbol, Google News RSS, SEC EDGAR atom

VERIFIED DEAD OR STALE — do not re-add:
- MarketWatch MarketPulse `mw_marketpulse` - newest item Jul 2025, over a year stale
- MarketWatch Real-time Headlines `mw_realtimeheadlines` - newest item Jun 2025
- Nasdaq market-wide `rssoutbound?category=Stocks` - ~2 weeks behind; the per-symbol
  feed off the SAME host is current, so use only that one
- CNBC (`search.cnbc.com/rs/search/combinedcms/view.xml`, `cnbc.com/id/.../rss.html`)
  - 403s to a plain fetch. May work from the phone with a browser UA, but it could not be
  proven, and an unverifiable source is not worth a request per refresh.
- Reuters - public RSS was retired years ago.
- Seeking Alpha per-symbol `seekingalpha.com/api/sa/combined/<SYM>.xml` - could not be
  verified (bot-blocked); assume Cloudflare will block the phone too.

YAHOO QUOTE ENDPOINT: `/v8/finance/chart/{symbol}` still works with no key and no crumb.
Documented behaviour under load: "heavy or parallel requests trigger rate limits, and you
get 429 errors or empty responses". There is no verifiable unauthenticated BATCH endpoint
(`/v7/finance/quote` needs a crumb; `/v8/finance/spark` could not be checked), so the fix
for request volume is to ask LESS OFTEN and for SMALLER payloads, not to batch. See Round 32.

### RESEARCH ON FILE (5 Sept 2026) — Ally's COMPLETE fee schedule, from Ally's own page

Supersedes the 4 Sep version, which came from the marketing page and was INCOMPLETE and in
one place WRONG. Primary source: ally.com/invest/commissions-and-fees (the actual schedule,
not the product page). Cross-checked against FINRA NOF-IMM-EFF-FINRA-2024-019 for the TAF
phase-in and FINRA Information Notice 03/17/26 for the Section 31 rate.

TRADING
    Stocks & ETFs             $0 commission, both sides
    BUYING stock/ETF          free - no commission AND no regulatory fee
    Under $2/share            $4.95 + $0.01/share, BOTH BUYING AND SELLING,
                              **capped at 5% of trade value**  <- missed first time
    Options                   $0 + $0.50/contract
    No-load mutual funds      $0        Bonds  $1/bond, $10 min, $250 max
    CDs                       $24.95/transaction
    Phone / broker-assisted   $20 + regular commission
    Foreign stock             $50 + regular commission
    Margin sellout            $40 + regular commission

REGULATORY PASS-THROUGH (sales only)
    SEC Section 31            $20.60 per $1,000,000 principal. Was $0.00 until 4 Apr 2026.
    FINRA TAF (equity)        $0.000195/share, max $9.79/trade   <- 2026 rate
    FINRA TAF (options)       $0.00329/contract
    Options regulatory fee    $0.02000/contract

ACCOUNT / SERVICE
    Account, inactivity, IRA annual   $0        Dividends & DRIP  $0
    ACAT out $50 | DRS $115/position | DWAC $50/position + agent fees up to $150
    Outgoing domestic wire $30 | Returned ACH $30 | ACH deposit/withdrawal free
    Paper statement $4 | Paper confirmation $2 | 1099 request $50 | Vault $60/yr
    IRA transfer $50 | IRA closure $25 | Option position management $100
    ADR custody ~$0.02/share semi-annual (set by the depositary, not Ally)

**THE TAF TRAP - READ THIS.** v5.0 shipped $0.000166/share capped at $8.30. Those are the
OLD rates. FINRA is phasing in an increase across 2026-2029: **$0.000195 / $9.79 max for
2026**, reaching $0.000249 / $12.50 by 2029. The stale numbers came from FINRA's own
Schedule A page, whose footnote had not caught up; ALLY'S FEE PAGE HAD IT RIGHT. When these
rates are next reviewed, check Ally's page first and the FINRA rule filing second, and bump
`Fees.RATES_CHECKED`. The TAF rises again on the 2029 schedule.

ROUNDING: the SEC fee is rounded UP to the next cent; the TAF to the nearest cent.

### Round 45 (v5.8) — RECONCILED AGAINST THE LIVE ALLY ACCOUNT

TJ sent eight screenshots - the app and his Ally account, same minute, 1:53pm on 4 Sep -
and asked for a comparison plus another check. This is the reconciliation Rounds 43 and 44
both said would be worth more than another read-through. It was.

#### Cost basis: EXACT on all 11 positions

App "since you bought" vs Ally "Total G/L", with the gap predicted by shares x the price
drift between the two screenshots:

| | app | Ally | gap | shares x drift |
|---|---|---|---|---|
| SOXQ | -47.94 | -48.54 | 0.60 | 0.60 |
| NVDA | +46.10 | +45.88 | 0.22 | 0.22 |
| CSCO | -27.05 | -27.17 | 0.12 | 0.12 |
| SMH | +11.60 | +11.47 | 0.13 | 0.13 |
| SKHY | +47.01 | +46.68 | 0.33 | 0.33 |
| FIVE | +16.60 | +16.68 | -0.08 | -0.08 |
| ONDS | -81.04 | -81.04 | 0.00 | 0.00 |
| BA | -26.51 | -26.53 | 0.02 | 0.02 |
| DVN, NWG, NRGV | | | <0.10 | matches |

**Every line is explained to the cent by the seconds between the screenshots.** FIFO cost
basis is confirmed correct against the live account for a second time (first was v1.5).

#### The one real problem: the headline "Today" could not be reconciled

    Ally  Today's G/L   +$109.58
    App   Today          +$93.04     <- $16.54 apart, nothing on screen explaining it

The whole gap is two positions, and the app is RIGHT about both:

| | app | Ally | |
|---|---|---|---|
| FIVE | +17.12 | +32.24 | 1 of the 2 shares was bought TODAY at $255.00 |
| NVDA | +4.45 | +6.64 | same shape |
| everything else | | | agrees within price drift |

Solving the app's own figures back out gives FIVE = 1 share held at $240.48 + 1 bought today
at $255.00, which reproduces its "since you bought" of +$16.60 exactly - and $240.48 is the
same fill from the v2.2 note. The decomposition is self-consistent.

Ally credits the share TJ bought at $255 this morning with the full move from yesterday's
$239.96 close: +$16.12 of "gain" he never earned. The app measures it from what he actually
paid: +$1.04. **The app is the more truthful number** - that is the whole point of v2.2.

**But a figure the user cannot reconcile against their broker reads as a figure that is
wrong.** The per-row asterisk and footnote covered the rows; the summary card said nothing
at all. So:

- [x] `PortfolioTotals` now also carries `brokerDayGain` / `brokerDayGainPct` (every share
      from the previous close, the broker's way) and `boughtTodayCount`.
- [x] The summary's "Today" line names it when it differs: *"Your broker will show
      +$109.58. It measures the 2 holdings you bought today from yesterday's close; this
      counts them from the price you actually paid."*
- [x] "Since you started" gets the same treatment against the broker's Total G/L (open
      positions only) - that explanation existed but was hidden behind "Show the detailed
      numbers", which is no use when the number you are questioning is the one on top.
- [x] Both lines render ONLY when the figures actually differ, so an ordinary day with no
      same-day buys looks exactly as it did.

#### Second find from the same comparison: half-cent prices

Ally showed ONDS at **7.565** and NVDA at **230.115**. The app rounded every price above $1
to two places, so it displayed $7.56 - and on 45 shares that is a 22c difference between two
market values sitting side by side, with nothing to explain it. The arithmetic was never
affected (positions are computed from the full double); only the number being eyeballed was.

`Fmt` now formats prices with `#,##0.00#` - two decimals minimum, a third only when the
value really has one. A whole-cent price still prints as $7.56, **not** $7.560, so nothing
picks up a pointless trailing zero. Applies to `price`, `priceBare`, `changeFor`,
`changeMoney`; dollar TOTALS stay at two decimals.

#### For the next round

The two highest-value checks left are both TJ-side: **another reconciliation after a day
with no same-day buys** (which should now show the two "Today" figures agreeing and the note
absent), and a check of the Activity tab against a real Ally statement. Static analysis of
the code is exhausted - three read-throughs, a full lint run and 5,000 property-tested
histories.

DB_VERSION still 3, BACKUP_VERSION still 2. Signing cert unchanged. Lint: 0 errors.

### Round 44 (v5.7) — stopped reading, started running things

Fourth consecutive "sweep the app" request. Round 43 ended by saying a general read-through
had stopped paying and to point the next one somewhere specific. Rather than a fourth pass
with the same eyes, this round changed METHOD: run tools that had never been run.

Two of them, and **each found a real defect that three careful read-throughs had missed.**

#### 1. Property tests on the ledger -> AVERAGE COST LOST REAL MONEY

`domain/Ledger.kt` was ported to Python (`tools_ledger_port.py`, kept in the archive) and run
against 5,000 randomised transaction histories, asserting the invariants this project has
always CLAIMED but never checked. The headline one, from section 5: *"Total lifetime P/L is
identical under both methods; only the realized/unrealized split moves."*

**It was false in 558 of 3,000 histories.** Minimal case:

    BUY  4 NVDA @ $100
    SELL 10 NVDA @ $150      <- 6 shares the ledger has no buy for

    FIFO     realized = 1,100.00     (10 x 150 - 4 x 100)
    AVERAGE  realized =   200.00     <- $900 of proceeds simply gone
    cash (sum of txn.amount) = 1,100.00

`fifo()` books the proceeds of the WHOLE sale and consumes only the cost it actually holds -
there is a comment there saying so deliberately. `averageCost()` did not: it computed
`sold = min(qty, shares)` and then used `sold` for the PROCEEDS as well as for the cost. Money
the account genuinely received vanished from the realized figure while `cash()` still counted
all of it, so realized + unrealized stopped reconciling with equity.

**This is not a contrived input.** An uncovered sell happens whenever a sell is on file before
its buy - a screenshot import that starts with recent activity, or any buy not imported yet.
The FIFO branch has always had a comment for exactly this case; the average branch just got it
wrong. TJ runs FIFO (the default, reconciled to the cent against Ally), so this only bites on
the Settings toggle - but it is a real money error and it had survived every sweep.

Fixed: cost comes off only for shares that were on the books, proceeds are booked in full.
**Re-run after the fix: 0 mismatches in all 5,000 histories.**

Other properties now checked and holding across those runs: share counts agree between
methods; no negative shares or negative cost basis; a closed position never keeps cost basis;
`Txn.cashEffect` correct on 12 hand-built cases including negative inputs and the
amount-only fallback; and `(equity - net deposits) == realized + unrealized + dividends -
fees` to $0.000000 under both methods.

#### 2. A FULL lint run -> an API error that had been invisible since v4.7

Release builds only ever ran `lintVitalRelease`, which checks **FATAL**-severity issues only.
`./gradlew :app:lint` - never run before this round - found:

    Storage.kt:135: Error: Field requires API level 29 (current min is 26):
                    MediaStore.Downloads#EXTERNAL_CONTENT_URI  [NewApi]

`NewApi` is severity ERROR, not FATAL, so no build had ever mentioned it. Every caller does
guard on `Build.VERSION.SDK_INT`, but the guard is in the CALLER and nothing could verify it.
`@RequiresApi(Q)` now states the contract so the tooling enforces it on every future caller.
(No practical risk on TJ's Android 16 phone; it would have been a NoSuchFieldError on 26-28.)

Also fixed from the same report: five `mutableStateOf(0)` / `(0L)` that box on every write,
now `mutableIntStateOf` / `mutableLongStateOf`. The one that matters is `progress` in
ReaderScreen - it fires on every WebView progress callback, dozens per page load.

**`lint { }` is now in `app/build.gradle.kts` with `abortOnError = true`, so this class of
finding cannot go unnoticed again.** Three checks are disabled ON PURPOSE, with the reason in
the block: `GradleDependency`, `NewerVersionAvailable` and `AndroidGradlePluginVersion` are
the "a newer version is available" nags, and section 3 of this file pins those libraries to
the last releases that compile against compileSdk 36. Acting on them is exactly the mistake
section 3 says to undo. Leaving them on would train the next reader to ignore lint output.

Lint now reports **0 errors, 6 warnings**, all deliberately left:

| warning | why it stays |
|---|---|
| `ModifierParameter` x2 (Widgets.kt) | Compose convention only; reordering params for a lint rule buys nothing |
| `ObsoleteSdkInt` (mipmap-anydpi-v26) | renaming the launcher-icon folder has zero user benefit and a non-zero chance of breaking the icon on a sideloaded install |
| `UseKtx` x3 | suggests `db.transaction {}`; the hand-written `try/finally` is already correct |

#### Honest scope note

The Kotlin read-through in this round found **nothing** - as predicted. Both findings came
from tools. **For the next round: the remaining high-value checks are a real reconciliation
against a fresh Ally statement, and running the app on the phone.** Static reading of these
34 files is exhausted; `tools_ledger_port.py` is in the archive so the property tests can be
re-run and extended instead.

DB_VERSION still 3, BACKUP_VERSION still 2. Signing cert unchanged.

### Round 43 (v5.6) — "why is this autosave file in my Downloads?"

TJ sent a screenshot of his Downloads folder and asked why `portfolio-autosave.json` was in
it, plus another sweep.

**HE WAS RIGHT TO ASK, AND THE HISTORY IS THE ANSWER.** Two earlier decisions contradict
each other and nobody reconciled them:

- **v1.7**: TJ asked for Downloads to stay clean. Automatic backups moved to the app's
  private folder and `cleanOldPublicAutoBackups()` deleted the ones already there. The rule
  written down was "only the Settings Backup button may create a visible file".
- **v4.7**: after the "the update deleted all my data" scare, `portfolio-autosave.json` went
  back into the Downloads ROOT, because the private folder dies with the app and TJ had no
  off-device copy at all.

Both are right. The mistake was treating it as either/or. **A subfolder gives both**, and
that is now the rule: everything this app writes goes to `Downloads/Portfolio/`.

- [x] **`Storage` takes a `subDir`, defaulting to `Storage.APP_SUBDIR` ("Portfolio").**
      Writes, lookups and deletes are all folder-scoped.
      - `findOwnDownload` matches RELATIVE_PATH **in Kotlin, not in the SQL**: MediaStore
        stores it WITH a trailing slash ("Download/Portfolio/"), so a query written against
        the value without one matches nothing - which here would mean failing to find the
        autosave and writing a second copy beside it.
      - `deleteOwnDownloads` gained the same scoping, and that is load-bearing: the new
        prompt file is `claude-advice-prompt.md` and the old ones are
        `claude-advice-prompt-<stamp>.md`, so a name-prefix sweep that ignored the folder
        would have deleted the file the app had just written.
      - `readOwnDownload` looks in the app folder and **falls back to the root**, so the
        recovery card still finds a pre-v5.6 copy that has not been migrated yet.
- [x] **Prompt files are now ONE fixed name each**, replaced in place
      (`claude-advice-prompt.md`, `claude-screenshot-prompt.md`). Every tap of "Make prompt
      file" used to leave another timestamped file behind for ever - TJ's folder had
      collected several. It also means one fewer way to pick the wrong file in the chooser,
      which is the v5.2 import bug this app already has scar tissue for.
- [x] **A one-time tidy-up** (`tidyDownloadsOnce`, gated on `Keys.DOWNLOADS_TIDIED`) clears
      what earlier builds left loose in the root: the pre-v1.7 dated auto-backups, the old
      prompt files, and the v4.7 autosave.

      **THE ORDERING IS THE WHOLE POINT AND MUST NOT BE "SIMPLIFIED".** That autosave is the
      copy that survives an uninstall - for a stretch it was the only off-device copy of the
      portfolio. So it is deleted ONLY after a replacement has been written to the new folder
      AND read back AND confirmed to parse as JSON with a transactions array. If any step
      fails, the root copy stays and the flag is NOT set, so the next launch tries again.
      Manual `portfolio-backup-*.json` files and anything the app did not write are never
      touched. A tidier folder is not worth any chance of deleting the backup.

**Simulated against a model of MediaStore, five failure modes:**

| scenario | autosave safe? | flag set? |
|---|---|---|
| normal run, portfolio has rows | moved to Portfolio/ | yes |
| export fails | root copy kept | no - retries |
| write to the new folder fails | root copy kept | no - retries |
| write succeeds but read-back fails | root copy kept | no - retries |
| empty ledger, nothing to protect | n/a | yes |

plus: a failed attempt followed by a good one migrates correctly; the new fixed-name prompt
file survives the prefix sweep; manual backups and unrelated files are untouched in all cases.

#### The sweep: one real find, in the one file never read before

- [x] **THE API KEYS WERE GOING INTO GOOGLE'S CLOUD BACKUP.** `AndroidManifest.xml` had
      `allowBackup="true"` with no rules, so Android's automatic backup copied
      `portfolio.db` - keys and all - off the device. Meanwhile Settings told TJ the key was
      "stored only on this phone and never included in a backup export". The app's own export
      has always filtered secrets (`Db.isSecret`); the platform's backup did not, so the
      claim on screen was not true.

      Fixed with rules that treat the two cases differently, which is what Android 13+
      allows: `cloud-backup` EXCLUDES the database (the keys never leave the phone),
      `device-transfer` includes everything (a phone-to-phone move still carries the whole
      portfolio). Android 12 and older cannot tell them apart, so `fullBackupContent`
      excludes the database in both - no real loss, given the app keeps 14 private snapshots,
      the Downloads/Portfolio copy and the manual export. Verified in the built APK with
      `aapt2 dump xmltree`. The Settings copy now says what actually happens.

**Read and found clean this round** (so a later sweep need not re-derive it): `TxnEditor`
(`toNum()` already strips the thousands separators that `Fmt` inserts, so a 1,500-share edit
round-trips); `FeedScreen` (filters and the owned-set are both memoised, and the
`LaunchedEffect` deliberately does not key on `feedAt`); `Social` (both JSON parses are
unguarded but every call site wraps them in `runCatching`); `CrashLog`; `PortfolioScreen`'s
recovery card (goes through `readAutosave`, so it inherits the root fallback above).

**Honest note on sweep depth.** This is the third consecutive full pass. Rounds 41 and 42
covered every source file; this one re-read the ones they touched lightly and found nothing
new in the Kotlin. The one real defect came from the AndroidManifest and the res/xml folder -
the only files no previous sweep had opened. **The lesson for the next round: a general
"sweep the app" pass has stopped paying. Point the next one at a specific screen, a real
reconciliation against the Ally statement, or the build/manifest surface.**

DB_VERSION still 3, BACKUP_VERSION still 2. Signing cert unchanged.

### Round 42 (v5.5) — second sweep, aimed at what Round 41 only skimmed

TJ asked for the same overall sweep again, one round later. Round 41 went deepest on the
ViewModel, Db, Ledger and the import path, so this pass was aimed at what it covered
lightly: ReaderScreen, SettingsScreen, FeedScreen, DetailScreen, PortfolioScreen, TxnEditor,
News, Insider, Social. **It found much less, which is the honest and expected result** - the
structural problems went in v5.4. One real bug came out of it, and it is a good one.

- [x] **READER VIEW WAS DELETING THE SUBSTANCE OF SENTENCES.** `READER_JS` rebuilds the
      article by reading each paragraph's `innerText` and concatenating it into a string that
      goes back through `innerHTML`. `innerText` returns TEXT - a literal `<` is a character -
      but `innerHTML` re-parses it as markup, so the browser read `<` as the start of a tag
      and swallowed everything after it:

      | the article says | the reader showed |
      |---|---|
      | Analysts see <20% upside from here | "Analysts see " |
      | The P/E is < 15 and falling | "The P/E is " |
      | Margins improved 200bps <as expected> | "Margins improved 200bps " |

      Finance copy is full of `<` and `>` - price targets, growth rates, valuation
      comparisons - so this was silently truncating exactly the sentences worth reading, and
      leaving a plausible-looking half-sentence behind rather than an obvious error. An
      `esc()` over text, headings and the `<h1>` fixes it. **Any future edit to READER_JS
      that inserts page text into innerHTML must go through `esc()`.** Verified by extracting
      the shipped script and round-tripping seven strings through node: 7/7 exact.
- [x] **`canGoBack` in ReaderScreen was written and never read.** Set on every page start and
      finish, so each article load triggered a recomposition of the whole reader to store a
      value nothing consumed. Both back paths call `web.canGoBack()` directly. Removed.
- [x] **The reader ignored a change of target.** `AndroidView.factory` runs once, so a new
      `ReaderTarget` arriving while the reader was open would leave the old article on
      screen. **Not reachable today** - the reader covers the lists a headline is tapped in,
      so a second one cannot be requested without closing the first - but that is a layout
      detail holding a correctness property up, so a `LaunchedEffect(target.url)` now pins it.
- [x] **DetailScreen re-filtered the whole transaction history on every recomposition**, and
      that screen recomposes on every 15-second price tick. Keyed on the list identity now,
      so it re-runs only when the ledger actually changes.
- [x] **An undated headline was handed to Claude as 1970.** A feed date that will not parse
      comes back as 0, and `Fmt.relative(0)` renders "Jan 1". Every place in the UI that
      prints a headline date already guarded `published > 0`; `headlinesBlock()`, which
      builds the advice prompt, did not - so a current headline could reach the model looking
      56 years old, which is a reason for it to discount it. Guarded to match the UI.

**Checked and found correct** (recorded so the next sweep does not re-derive it): the
DetailScreen news scroll index still counts `1 + txns.size + (filings.size + 2)` against the
literal `item {}` calls; `SymbolSearch`, `publishFeed` and `Insider.parse` all de-duplicate
before anything is keyed by id; `Insider.parse` correctly separates `<updated>` from
`<filing-date>`; `News.normaliseTitle` strips the publisher suffix before punctuation; every
LazyColumn key in the app is unique by construction; MarketClock's phase boundaries are right.

**Deliberately left alone:** Settings writes an API key to SQLite per keystroke (one indexed
upsert, sub-millisecond - the v2.0 fix removed the expensive part, the ledger replay);
`Sparkline` recomputes min/max/filter per recomposition (~20 rows x 78 points, trivial);
`News.normaliseTitle` truncates its key at 70 characters, so two headlines sharing a 70-char
prefix collapse into one - which is usually the desired behaviour for near-duplicate wire
copy. None of these is worth the churn; noting them so they are not "found" again.

DB_VERSION still 3, BACKUP_VERSION still 2. Signing cert unchanged.

### Round 41 (v5.4) — full sweep: bugs, efficiency, UI

TJ asked for an overall sweep of the app for UI improvements, efficiency upgrades, bugs and
fixes. All 34 sources read end to end. What follows is everything that was actually wrong,
not a list of what was looked at.

#### Correctness

- [x] **A FALLBACK QUOTE BLANKED HALF THE ROW.** The quote chain is Yahoo -> Finnhub ->
      Stooq, and only Yahoo returns a company name or an intraday series. `refresh()` merged
      with `merged[symbol] = fresh`, replacing the quote wholesale - so the moment Yahoo
      rate-limited a single tick, every watchlist row's company name became "Watching" and
      every sparkline and detail chart went blank, then healed on the next tick Yahoo
      answered. Nothing was wrong with the numbers; the screen just lost pieces of itself at
      random, which reads as the app breaking. `carryDisplayFields()` now carries `name` and
      `spark` forward when the new quote has neither. **Only those two, and deliberately:**
      both are descriptive, so a slightly old sparkline is still that symbol's own recent
      shape. Nothing feeding a CALCULATION is carried - `prevClose` in particular stays
      exactly as the provider gave it, so v2.3's "never fabricate a previous close" holds.
- [x] **Average cost accepted a zero-quantity BUY.** `fifo()` has always skipped rows with no
      share count; `averageCost()` did not, so a BUY recorded with 0 shares and a fee on it
      added that fee to the cost basis of a position holding no shares - a basis no quantity
      justifies, and an average cost over a zero divisor. Same guard on both paths now.
- [x] **Headline-to-holding matching was non-deterministic.** `matchHolding` iterated the
      owned-symbols SET, so for a headline naming two holdings ("Apple supplier Broadcom
      lifts outlook") the winner depended on hash order - the same headline could file itself
      under a different stock on a different run. It now scans the TITLE in order, tickers
      first and company names second, which is deterministic and keeps the old precedence.
      A company word claimed by two holdings is dropped rather than assigned arbitrarily.

#### Efficiency

- [x] **A 111-row import was 111 SQLite commits on the UI thread.** `commitImport` inserted
      row by row from the dialog button, and every bare insert is its own implicit
      transaction with its own commit, plus a duplicate query each. `commitImportAsync` does
      the same work on IO inside ONE transaction. **The blocking version is deleted, not
      kept:** this runs from a button, and a main-thread version sitting in the file is an
      invitation to call it from one again.
- [x] **The import review ran its duplicate check inside composition.** One query per row on
      the UI thread before the dialog could draw its first row. Now `duplicateFlags()` on IO,
      with the rows visible immediately and "Checking these against what you already have..."
      until the answer lands. It calls the SAME `duplicateOf` the commit does - the badge and
      the skip must never disagree, so this is not a faster reimplementation of the rules.
- [x] **`matchHolding` re-split every holding's company name for every headline.** 60 market
      headlines against 20 holdings was 1,200 regex splits, on the main thread, per feed
      refresh. `companyWordIndex()` builds the lookup once per refresh; the match is now set
      lookups. The regex is a field rather than a literal, so it is compiled once.
- [x] **`deleteSymbol` read the whole transactions table** into memory to find one symbol's
      ids, then issued one DELETE per id. Now a single `deleteTxnsForSymbol` against
      `idx_txn_symbol`. Verified in sqlite that cash rows (symbol NULL) and a look-alike
      ticker (NVDAX) both survive deleting NVDA.
- [x] **"Copy JSON" serialised the whole database on the main thread** from a click handler.
      Only the clipboard write has to be on that thread; the export moved off it.
- [x] **Dead main-thread paths removed:** `vm.restore()` (the blocking twin of
      `restoreAsync`, no callers - it would ANR on a large backup if anything ever called
      it), `Db.importJson()` (a compatibility shim with nothing left to be compatible with),
      and `isDuplicate()`.

#### UI

- [x] **Oversized screenshots were dropped in silence.** Anything over 4.5MB returned null,
      and the error only appeared if EVERY image failed - so picking three screenshots where
      one was a large PNG extracted the other two and never mentioned the third. On an import
      that is a missing trade with nothing on screen to hint at it. Now the image is
      re-encoded (long edge to 2000px, JPEG 85 - a statement screenshot is text on a flat
      background, so this is visually identical and a fraction of the size), and if anything
      still cannot be read the import stops and says how many and why, rather than extracting
      from a partial set. `inSampleSize` decoding means the full-size bitmap is never held in
      memory, and `catch (Throwable)` covers OutOfMemoryError - a refusal beats a crash.
- [x] The Advice tab's instructions name the prompt file explicitly (carried from v5.2).

#### Notes for the next session

- `checkinit.py` caught both new ViewModel properties on the first build of this round. It
  is unconditional on purpose: "it happens not to be touched by init yet" is exactly the
  reasoning that shipped the v4.6 startup crash. Declare new properties above `init`.
- `recompute()` still reads the whole transactions table on the main thread. It is bounded
  by history size and runs on data edits rather than on a timer, so it is left alone -
  but it is the next thing to move if the ledger ever gets big enough to stutter.
- Short tickers (1-2 characters) are deliberately never matched in headlines, so AT&T ("T")
  and Ford ("F") only match via their company name. That is the intended trade.

**Regression checks (Python, against the shipped constants where they matter):**

| check | result |
|---|---|
| Quote fallback Yahoo -> Finnhub -> Stooq -> Yahoo keeps name + sparkline | PASS |
| ...and `prevClose` still comes only from the provider | PASS |
| Zero-quantity BUY with a fee no longer adds to cost basis | PASS |
| New headline matcher vs old, 12 headlines x 200 set orderings | 0 differences |
| Old matcher shown to be unstable on 3 of those headlines | confirmed |
| `DELETE FROM txns WHERE symbol=?` spares NULL-symbol and NVDAX rows | PASS |
| Build: 0 errors, 0 warnings; `checkinit` clean | PASS |

DB_VERSION still 3, BACKUP_VERSION still 2 - no migration. Signing cert unchanged.

### Round 40 (v5.3) — an empty column is worse than no column

TJ: "make it so the after hours/overnight only shows up after hours. right now it is market
hours and there is a section for after market but no number. do not show this at all until
after market hours. and during market hours it doesn't need to say 'during market hours'
because it is obvious. only make it say that during after market hours."

Both halves are the same point: **a caption is only worth its space when it distinguishes
something.** v4.3 added the two labelled session columns and rendered both unconditionally,
so through the whole regular session the right-hand column was a heading over "--" and "no
trading yet" - reserved space for a number that cannot exist while the market is open - and
the left-hand column was captioned "DURING MARKET HOURS" directly beneath a heading that
already said MARKET OPEN.

- [x] **The extended-hours column is built only when there is an extended-hours print.**
      `PriceBlock` computes `extCell` as a nullable triple; when it is null the column AND
      its divider are simply not composed, and the regular-session cell takes the full width.
- [x] **The left column is captioned only when the right one is present.** Alone it has no
      caption at all - the heading above it already names the session. `SessionCell.label` is
      now nullable and drops its spacer with it, so nothing sits where the text used to be.
- [x] **One rule, one place: `extendedPrice(q)` in `ui/Widgets.kt`.** It returns the extended
      price only when it belongs on screen, and the price block, the explanatory note under
      the detail chart and the position's "After hours gain/loss" row all now ask it rather
      than each testing `extPrice > 0` themselves. **Any future extended-hours UI must call
      it too** - three separate copies of this test is how they drift apart.
- [x] **The guard is `extPrice > 0` AND `marketState != "OPEN"`.** `MarketData` already
      leaves `extPrice` null during the session, so the state test looks redundant - it is
      not. Quotes are cached in SQLite and re-read at startup, so at 10:30am the app can be
      holding last night's extended print. Without the state test that stale figure would
      reappear beside a live one, which is the same class of bug as the fabricated previous
      close in v2.3: a wrong number that looks perfectly plausible.

**Regression check - the display truth table, simulated across every session state:**

| situation | heading | left column | right column |
|---|---|---|---|
| 10:30am, regular session | CURRENT PRICE · MARKET OPEN | no caption | absent |
| 10:30am, stale cached ext price | CURRENT PRICE · MARKET OPEN | no caption | absent |
| 4:00pm, closed, nothing printed yet | CLOSING PRICE · MARKET CLOSED | no caption | absent |
| 6:30pm, after-hours trading | CLOSING PRICE · MARKET CLOSED | DURING MARKET HOURS | AFTER HOURS / OVERNIGHT |
| 7:00am, pre-market trading | LAST CLOSING PRICE · PRE-MARKET | DURING MARKET HOURS | PRE-MARKET |
| Saturday, no ext data | CLOSING PRICE · MARKET CLOSED | no caption | absent |
| Stooq fallback, unknown state | LATEST PRICE | no caption | absent |
| no quote at all | NO QUOTE YET | no caption | absent |

The single-column layout is still weighted, not fixed-width, so the v1.8 clipping rule
("never put a price+percent pair back into a fixed-width column") is not reintroduced.
DB_VERSION still 3, BACKUP_VERSION still 2, signing cert unchanged.

### Round 39 (v5.2) — THE ADVICE TAB SHOWED THE PROMPT'S PLACEHOLDER TEXT

TJ: "when I used Claude app for the advice feature, when I went to import it back into the
app it displayed [the template] which is an error."

**What he saw.** The Advice tab rendered "3-5 sentences on the portfolio as a whole:
concentration, sector tilt, cash level..." as the Overview, "most important concrete step
first / next step / ..." as the actions, and a single NVDA card rated 8 HOLD with
"2-3 sentences: why this rating, what would change it." as the reasoning. Every one of those
strings is the worked example from `ClaudeBridge.ENVELOPE` - the block the prompt file shows
Claude so it knows the answer shape.

**Root cause - reproduced in Python before touching the Kotlin.** The prompt file the app
writes to Downloads contained a fully valid JSON object carrying `portfolioAppResponse` and
`advice`. `parse()` cannot tell a question from an answer, so whichever of these the user
picked in the file chooser produced the same result:

  1. importing `claude-advice-prompt-<stamp>.md` itself - it sits in Downloads directly
     beside the reply and is the file most likely to be tapped by mistake; or
  2. importing a reply in which Claude restated the template before answering.

Round 27 (v4.0) fixed the *ranking* half of this for transactions - the tie-break used to
return the FIRST maximum, so an echoed example beat the real answer. It did not stop the
example from being a candidate at all, and on the advice side there was no second block to
outrank it. **The example itself had to stop being parseable.**

**The fix, three layers - all three matter, do not remove one as redundant:**

- [x] **The example is now a SCHEMA, not a filled-in example.** `ADVICE_SHAPE` uses
      `<string - 3-5 sentences ...>` placeholders. Unquoted `<...>` is not valid JSON, so the
      block can never be a candidate no matter where it appears. Same change applied to the
      API path's prompt in `net/Claude.kt` - **the two prompts must always change together.**
- [x] **Prompt files carry a marker and are refused by name.** Every prompt file now opens
      with `<!-- portfolio-app-prompt-file: this file is the QUESTION for Claude, not the
      ANSWER ... -->`. `isPromptFile()` also recognises pre-v5.2 files by their headings,
      because the ones already in TJ's Downloads have no marker. Importing one now says so
      in plain language instead of filling the screen with placeholder text.
- [x] **Placeholder wording is fingerprinted.** `TEMPLATE_PHRASES` holds the instruction-voice
      fragments that only ever appear in a template ("sentences on the portfolio as a whole",
      "why this rating, what would change it", ...), drawn from BOTH prompt paths and from the
      pre-v5.2 wording. `findObject` drops any block scoring 2+ hits, so an echoed template
      cannot even enter the ranking. **Threshold is 2, not 1, on purpose:** a real review that
      leaves one stray placeholder in a single `target` field is still a real review, so
      `scrub()` blanks that one field rather than discarding the answer.
- [x] **The screenshot example is now marked fake.** Its rows use ticker `XXXX` and date
      `1900-01-01`; `isExampleRow()` throws away any row still carrying either, on BOTH the
      offline and the API path. The pre-v5.2 demo rows (NVDA 3 @ 227.44 plus a $500 deposit)
      are rejected only as the COMPLETE PAIR - a lone 3-share NVDA buy at 227.44 is a trade
      TJ could really have made, and it still imports.
- [x] **The bad advice already in the database is thrown out on load.** Any device that hit
      this bug has the placeholder text sitting in `Keys.ADVICE_CACHE`, so it would have
      survived the update. `loadCachedAdvice()` runs `isPlaceholderAdvice()` and clears the
      key instead of showing it.
- [x] The Advice tab's own instructions now say to import Claude's *answer*, not the prompt
      file, and name the prompt file so the two are told apart in the file chooser.

**Regression checks (Python port of the new parser, run against the shipped constants):**

| case | expected | result |
|---|---|---|
| v5.2 prompt file imported by mistake | refused, names the mistake | PASS |
| v5.1 prompt file still in Downloads | refused, names the mistake | PASS |
| real advice reply | advice loaded | PASS |
| template echoed THEN a real answer | the real answer loaded | PASS |
| real screenshot reply, genuine NVDA 3 @ 227.44 | 1 transaction | PASS |
| pre-v5.2 demo pair echoed | refused | PASS |
| new placeholder row beside one real row | 1 transaction, placeholder dropped | PASS |
| file with no JSON at all | "no JSON block found" | PASS |

DB_VERSION still 3, BACKUP_VERSION still 2 - no migration. Signing cert unchanged, so v5.2
installs in place over v5.1 with the portfolio untouched.

### Round 38 (v5.1) — the complete schedule, and two corrections to v5.0

TJ: "sometimes it says .02 fee when I sell stocks and I know there is a fee for buying and
selling cheap stocks, so make sure you have the entire Ally fee and commission schedule baked
into the app and that it is accurate."

He was right to push. Going to Ally's actual fee page rather than its product page turned up
**two errors in v5.0**:

- [x] **The FINRA TAF rate was out of date.** v5.0 used $0.000166/share capped at $8.30 -
      the pre-2026 figures - so every computed sell fee was slightly low. Corrected to the
      2026 rate, **$0.000195 capped at $9.79**. See the TAF trap note above.
- [x] **The under-$2 commission was missing its cap.** Ally caps it at **5% of trade value**,
      which bites hard on small orders: 10 shares at $1.00 is $0.50, not the $5.05 the base
      formula gives. Added, and confirmed it applies to BUYS AS WELL AS SELLS - which TJ
      already knew from his own account.

The ~$0.02 he sees on sells is real and correct: the SEC fee plus TAF on a modest sale. A
100-share sale at $291 comes to $0.62 ($0.60 + $0.02); a $400 sale comes to $0.01.

- [x] The whole schedule now lives in `Fees.TRADING` / `REGULATORY` / `ACCOUNT` as data, and
      Settings renders it rather than restating it in prose that can drift out of sync.
- [x] `forOptionTrade()` added for completeness (commission + TAF + ORF, and Section 31 on
      the premium of a sale). No options transaction type exists yet, so it is unused - but
      the rates live in one place instead of being scattered through UI copy.
- [x] The fee audit now correctly LEAVES ALONE a buy of a stock under $2 - that one really
      does carry a commission. It only flags a buy charged more than the schedule allows.

**Fee maths verified (ledgertest/Fee2.java), 20 cases, all passing:** free buys at every
size; the 2026 TAF on sells and its $9.79 cap; the SEC fee on a $5M sale ($103.00); the
under-$2 commission on both sides; the 5% cap biting on two different small orders; the
$2.00 boundary exactly; and the options commission, ORF, TAF and Section 31 components.

### Round 37 (v5.0) — Ally's real fee schedule, and the app no longer implying fees

TJ: "I don't think it has fees for buying stocks, and I think the app thinks there are fees."
He was right about the first part. Ally charges NOTHING to buy a stock or ETF - no
commission, and neither regulatory fee touches a purchase, because both are levied on sales.

- [x] **`domain/Fees.kt`** encodes the whole schedule above, with a `Breakdown` that keeps
      commission / SEC fee / TAF separate so the app can show its working.
- [x] **The Add-transaction dialog fills the Fees box in itself** from the trade type, share
      count and price, and prints what it did ("No fees - Ally charges no commission on
      stock and ETF trades"). It previously offered an empty box, which invites a guess -
      that is most likely where the impression of fees came from. Typing in the box stops
      the app touching it, and editing an existing transaction never auto-fills.
- [x] **Settings > Fees and commissions** lists the whole schedule with the rates and the
      date they were checked, and states the sell-side-only rule explicitly.
- [x] **A fee audit.** Settings totals the fees on file and flags any BUY carrying a
      commission - which Ally does not charge and which inflates cost basis - with one
      button to strip it and re-derive that row's cash effect. Deliberately says nothing
      about sell-side fees: the correct amount depends on the SEC rate in force that day,
      and it was $0.00 for the first part of 2026.
- [x] **Import prompts corrected** (both the API and the offline-bridge path): Ally charges
      no stock/ETF commission, never estimate or back-calculate a fee, and a fee that WAS
      charged is already inside the net amount - adding it again overstates the basis.

**IMPORTED ROWS ARE NEVER TOUCHED AUTOMATICALLY.** An imported amount is what Ally actually
settled and already contains anything it charged. Auto-applying the schedule on top would
double-count. The estimate is for trades entered by hand; everything else is opt-in through
the audit button.

**Fee maths verified (ledgertest/Fee.java), 12 cases, all passing:** buys free at every size;
SELL 1 @ 291.06 -> SEC $0.01 / TAF $0.00; SELL 100 -> $0.60 / $0.02; SELL 100k @ $50 ->
SEC $103.00 and TAF capped at $8.30; the under-$2 commission boundary at exactly $2.00; and
dividends/deposits carrying nothing.

### Round 36 (v4.9) — "the trading day" is not "the calendar day"

TJ: "it says I made $2.12 with SMH today, but I bought it in the middle of the trading day
so that number is wrong."

**ROOT CAUSE - read this before touching dayPnl again.** The ledger's same-session window was
the DEVICE'S CALENDAR DAY. A quote describes one trading SESSION: `price` is the latest print
in it, `prevClose` the close of the session before. Between one close and the next open -
every evening, every night, all weekend - those two disagree.

TJ bought SMH mid-session on 3 Sep and looked at 01:46 on 4 Sep. The quote still described
the 3 Sep session (+2.12 on the day). The ledger's window was 4 Sep, so his 3 Sep buy fell
OUTSIDE it, was treated as "held since the previous close", and was measured from the 2 Sep
close - crediting him with the entire 3 Sep move. He had only owned the shares from his
mid-day fill, where the real gain was +0.56. Reproduced exactly in a harness before fixing.

Note the same-session logic already existed (Round v2.2) and was correct. What was wrong was
WHICH DAY it was asked about.

- [x] `Ledger.positions` takes `sessionInstant`, not `now`.
- [x] `PortfolioViewModel.sessionInstant()` derives it from the newest `Quote.quoteTime` -
      the exchange timestamp of the last regular-session print, so its calendar day IS the
      session the rest of the quote is talking about. This also keeps the figures consistent
      with STALE quotes, which is what you want: the numbers agree with the price on screen.
      Falls back to the clock only if the newest print is over a week old.
- [x] `ledgerDay` now tracks the SESSION day, so the ledger is replayed at the opening bell
      rather than at local midnight.

**Second bug found while in here - fees counted twice.** `amount` is the signed cash effect
and ALREADY includes fees (a BUY moves -(gross+fees), a SELL +(gross-fees)). Deriving the
per-share price as `abs(amount)/qty` baked the fee in, and the caller then added `fees`
again: cost basis overstated by 2x commission on a buy, proceeds understated by the same on
a sell. Only bites transactions imported without a per-share price AND carrying a fee, which
is why it survived this long. Fixed in one `unitPrice()` helper used by both FIFO and average
cost.

**UI - say which session the numbers describe.** A row reading "YOU MADE TODAY +$2.12" at 2am
is describing yesterday, and that was never stated anywhere.
- [x] `Row.sessionLabel` is blank during market hours and "Sep 3" otherwise. The row reads
      "YOU MADE SEP 3"; the detail screen reads "Sep 3 session gain/loss" and "Bought Sep 3".

**Regression suite (ledgertest/F.java), 6 cases, all passing:** TJ's exact scenario; shares
held from a previous session; a mix of held and same-session shares; the same buy seen from
the NEXT session (it becomes an ordinary holding); and fee handling on amount-only BUY and
SELL rows.

### Round 35 (v4.8) — the crash log closed the case, and the invariant that ends it

TJ sent the crash log from the phone. It confirmed Round 34 exactly:

    NullPointerException: ... MutableStateFlow.setValue on a null object reference
      at PortfolioViewModel.recompute(PortfolioViewModel.kt:369)
      at PortfolioViewModel.<init>(PortfolioViewModel.kt:245)

In the v4.6 source, line 245 IS `recompute()` inside `init`, line 369 IS
`_dataMissing.value = false`, and `_dataMissing` was declared at line 351 - below init.

**TWO THINGS THE LOG SETTLED THAT REASONING COULD NOT:**

1. The crash is on the `txns.isNotEmpty()` BRANCH. That line only runs when transactions were
   read back from the database, so the log is independent proof that TJ's data was on the
   phone and readable the whole time. Nothing was ever lost.
2. All three recorded crashes are v4.6. There is NO v4.5 crash in the log, and the file was
   nowhere near its 60KB cap, so none was dropped. **v4.5 did not crash** - its empty
   portfolio was a display failure over intact data.

That second point matters: the v4.5 initialisation-order bug blanks the cache, but the next
refresh is supposed to heal it, and I could not construct the path that leaves it empty on
screen. Rather than keep arguing about which orderings are reachable, the invariant is now
enforced where it cannot be argued with:

- [x] **`publish()` will never draw an empty portfolio while the transactions table has rows.**
      If the cache is empty it asks the database for a COUNT, and re-reads if the count
      disagrees. A `reloadingFromDisk` re-entry flag means an inconsistent read publishes
      empty once rather than recursing.
      Simulated over four cases: blanked cache recovers (12 rows), genuinely empty stays
      empty, a warm cache issues ZERO database queries (no cost on the normal refresh path),
      and count>0/read-empty terminates instead of looping.

**THE CRASH LOG EARNED ITS KEEP.** Added in v4.4 on the argument that "the app closed" is not
a bug report. It turned a second bad release into a five-minute diagnosis with the exact file
and line, and it disproved the data-loss theory outright. Keep it.

### Round 34 (v4.7) — v4.6 would not start, and the rule that stops this recurring

v4.6 crashed on launch, every launch. Cause: `_dataMissing` - the StateFlow behind the new
recovery banner - was declared BELOW `init`. `recompute()` writes to it and `init` calls
`recompute()`, so the first thing the app did on startup was dereference a field that did
not exist yet. NullPointerException in the ViewModel constructor, no way into the app.

This is the SAME initialisation-order mistake documented one round earlier in Round 33, made
again in the very commit that documented it. Reading carefully did not prevent it and a
comment did not prevent it.

- [x] `_dataMissing` / `dataMissing` hoisted above `init`.
- [x] `computeAffecting` hoisted too - it was the last property left below init.
- [x] **`checkinit.py`, wired into the Gradle build** (`preBuild` depends on `checkInitOrder`),
      so it runs on every single build and cannot be forgotten.

**THE RULE, now mechanically enforced: in `PortfolioViewModel`, EVERY property is declared
above `init`. No exceptions.**

The first version of that checker tried to work out which properties `init` could actually
reach, transitively. It produced a false positive on its first run. A check that cries wolf
gets ignored - which is how the second bug shipped in the first place - so the rule was made
absolute instead. It needs no analysis to be correct, it cannot produce a false positive, and
the cost of obeying it is moving a line. Verified both ways: it passes on the fixed file and
fails on a reconstruction of the exact v4.6 ordering.

**CONFIRMED, from the user's own device:** Settings > Backup on v4.5 wrote a file that
CONTAINED the transactions. The database was never damaged at any point - Round 33's
conclusion was right, and the "deleted all my data" symptom was the portfolio failing to
DISPLAY intact data. Nothing was ever lost.

### Round 33 (v4.6) — "the update deleted all my data"

TJ reported after installing v4.5 that the app was empty. Treat this section as the record
of what was checked, because the same panic will look identical if it ever recurs.

**WHAT THE EVIDENCE SAID.** API keys and preferences had SURVIVED, and the install was a
normal in-place update with no uninstall prompt. Settings, transactions, the watchlist and
the private snapshots all live in the same app data directory - so that directory was never
wiped, and the SQLite file was still on the phone.

**WHAT WAS RULED OUT, by diff against the v4.4 checkpoint archive:**
- `data/Db.kt` identical apart from one added Keys constant. DB_VERSION still 3, no
  onUpgrade path taken, no table dropped.
- `AndroidManifest.xml` byte-identical (package, allowBackup unchanged).
- `domain/` and `util/` byte-identical - the entire ledger untouched.
- Signing: BOTH APKs verify as one signer, v2 scheme only, cert `2e8c3847...`, public key
  `bc5bc57f...`. An in-place update was possible, so nothing forced an uninstall.
- Every call that deletes anything (`deleteAllTxns`, `restoreJson(replace=true)`,
  `deleteTxn`, `deleteSymbol`, `clearOverride`, `removeWatch`) traced to a call site: all
  six are behind an explicit user tap. `Merge` is still `replace=false` and `Replace all`
  still `replace=true`.
- `cleanOldPublicAutoBackups()` matches the prefix `portfolio-autobackup-` only, so it has
  never been able to touch a manual `portfolio-backup-*.json`.

**THE ONE REAL BUG FOUND — Kotlin initialisation order.** The v4.5 ledger cache fields
(`cachedTxns`, `cachedTxnsDesc`, `cachedPositions`, `cachedWatch`, `ledgerDay`) were declared
BELOW the `init` block. Kotlin runs property initialisers and init blocks in source order, so
the real sequence was:

    init -> recompute() reads the DB and fills the cache -> publish() draws real rows
         -> THEN `= emptyList()` / `= 0L` ran and wiped every one of them

It self-heals (the next `reprice()` sees `ledgerDay == 0` and does a full replay), which is
why it passed testing, but it left the app one mistimed call away from drawing an empty
portfolio over good data. `deepNewsAt` and `insiderLoading` had the same problem and would
have been null if init had ever reached them.

**RULE: every property the init block can reach, directly or through a function it calls,
must be declared ABOVE `init`.** There is a comment saying so at the declaration site.

**HARDENING — the app should have made this impossible to be frightening.**
- [x] **An automatic copy that survives uninstalling the app.** `autoBackupIfDue` now writes
      a second copy to `Downloads/portfolio-autosave.json`, replaced in place each day via a
      new `Storage.saveOrReplaceInDownloads` (MediaStore "wt" truncating write, so a shorter
      export cannot leave trailing bytes of the previous one). Until now the ONLY copy that
      outlived the app was one the user had to remember to make by hand - and TJ had none.
- [x] **`Keys.LAST_TXN_COUNT`**, a high-water mark written on every successful replay. It is
      never lowered automatically; only an explicit wipe resets it.
- [x] **A recovery card** replaces "No holdings yet" whenever the ledger reads empty but that
      mark says it held rows. It names the count that is missing, states plainly that nothing
      has been overwritten, and offers one-tap restore from the Downloads copy - as a MERGE,
      never a replace, so it cannot make things worse.
- [x] Both automatic writes are still skipped when there is nothing to save, so an empty
      ledger can never overwrite a good backup with nothing.

### Round 32 (v4.5) — in-app reader, more news sources, and the request budget

Three asks: open articles inside the app, add about three top stock-news sources, and a
comprehensive bug/efficiency sweep with the online pulls made safe from bans.

---
#### A. THE IN-APP READER (`ui/ReaderScreen.kt`, new)

Tapping a headline fired an ACTION_VIEW intent and threw the user into Chrome. Articles now
open in the app's own WebView screen, inside its back stack.

- [x] Framework `WebView`, NOT a new dependency. `androidx.browser` (Custom Tabs) was the
      obvious alternative and was rejected: every library in this project is pinned to the
      last release that compiles against compileSdk 36 (section 3), and adding one is a real
      risk to the build for something the framework already does.
- [x] Toolbar: title + domain, progress bar, reload, and a menu with Reader view, Open in
      browser, Share, Copy link.
- [x] Its own `BackHandler`, composed inside the screen so it registers AFTER the app-level
      one and therefore runs first: back walks the ARTICLE's history, then closes the reader.
      MainActivity's handler carries a `reader != null` branch as a fallback.
- [x] The WebView is explicitly destroyed on dispose (stopLoading, about:blank, removeView,
      destroy). Without that each article read leaves a renderer process and its timers alive.
- [x] Security posture: no file:// or content:// access, NO JavaScript bridge registered at
      all, no auto-playing media, multiple windows off, and non-http(s) schemes (mailto:,
      tel:, intent:, market:) handed to the system instead of loaded. JavaScript itself is ON
      - news sites render nothing without it and Google News links are a JS redirect.
- [x] Settings > News > "Open articles inside the app" (default on) is the escape hatch for
      a site that will not render in a WebView.
- [x] NOTE: cleartext http is blocked by the platform default and deliberately left that way.
      A publisher that redirects to http shows the error banner and "Open in browser".

**Reader view** re-typesets the article. The extractor was DEVELOPED AGAINST TESTS - the
script is pulled out of the Kotlin source and run in real Chromium (Playwright) over seven
page shapes. Three genuine bugs came out of that and none of them would have been visible by
reading the code:
  1. Scoring only `:scope > p` found NOTHING on pages that wrap each paragraph in its own
     div - a very common CMS output - so reader view silently did nothing. Now every
     paragraph credits up to four ancestors with a decaying weight (1, .5, .3, .2).
  2. The "is there enough here" threshold was applied to the weighted SCORE, which rejected
     good pages whose paragraphs only ever earn half-weight credit. It is applied to the
     extracted TEXT now.
  3. A page whose promo rail carried 3x the text of the story returned the PROMO. Fixed by
     skipping anything inside aside/nav/footer/header, and weighting containers by what the
     page calls them (junk vs content class/id patterns).
Also: when there is no article to simplify the screen now SAYS so - it used to look like a
broken menu item.

---
#### B. NEWS SOURCES

Three added, each fetched and read before being wired in (research block above). Two
well-known feeds were rejected BECAUSE of that check - MarketWatch's MarketPulse and
Real-time Headlines are still valid RSS but have not published since 2025.

- [x] **MarketWatch Top Stories** and **Investing.com Stock Market News** - market-wide.
- [x] **Nasdaq per-symbol** (`rssoutbound?symbol=`) - carries Zacks/Motley Fool/Barchart
      wire copy that Yahoo's own feed misses. Nasdaq's market-wide feed was weeks stale and
      is deliberately NOT used.
- [x] Two fetch modes, so breadth does not multiply the load:
      `deep=true`  every source at once, merged - used when the user opens ONE stock.
      `deep=false` the cheap cascade, stopping at 8 headlines - used by the background
                   refresh that runs over every holding and watchlist entry.
- [x] `parseRss` now also understands Atom (`entry`, `published`, `link href`).
- [x] De-duplication across sources is on a NORMALISED title, and a test over six real title
      variants found the first version only stripped `" - Publisher"`. Yahoo and MarketWatch
      use `" | "`, wire copy uses em/en dashes. All five separators are handled, and the cut
      only applies past character 20 so "AAPL - Reuters" is not reduced to noise.
- [x] `mergeNews()`: the background refresh MERGES into the per-symbol map instead of
      replacing it. Assigning wiped out the wide multi-source list for any stock the user
      had opened, so the deep view was a one-time thing. Capped at 40 headlines per symbol.

---
#### C. REQUEST BUDGET — the ban risk

Measured, not guessed (`MarketClock` ported to Java and run over a full week, minute by
minute). With the app left open, 20 symbols, the user's 15s setting:

    requests/week   806,400  ->  220,040     (-72.7%)
    data/week        86.6 GB ->    5.2 GB    (-94.0%)
    peak concurrency     16  ->        5

- [x] **`net/MarketClock.kt` (new)** - US market phase from the device clock in New York
      time. The loop re-derives its interval EVERY pass: the user's interval while open, at
      least 60s in pre/post, at most once every 15 min when closed. Verified against DST in
      both directions and every session boundary.
- [x] **Holiday cover**: no free holiday calendar exists and hard-coding dates rots, so it is
      caught from the other end - if the newest exchange timestamp across the holdings stops
      advancing for 15 minutes during nominal market hours, the tape is treated as closed.
- [x] **Chart interval 1m -> 5m.** ~390 candles a symbol became ~78. The numbers that matter
      (price, previous close, day high/low, extended-hours print) all come from `meta` and
      are untouched; the live price is appended as the final spark point so the chart's
      right-hand edge stays current to the second.
- [x] **Parallelism 16 -> 5.** Yahoo's documented trigger is "heavy or PARALLEL requests" -
      the SHAPE of the traffic gets a client blocked before the daily total does.
- [x] **`Http` per-host cooldown**: 429/503/403 -> 30s, 1m, 2m, 4m, 8m, 10m, cleared by the
      first success. While a host is cooling down its requests are skipped locally without
      touching the network. 403 is in that list on purpose - Yahoo and Google answer a
      throttled client with 403, and pushing through it is what escalates to a real block.
- [x] **Conditional GET for feeds** (ETag / If-Modified-Since, 48 entries, bodies <=120KB).
      An unchanged feed costs a bodyless 304. A MANUAL refresh clears the cache first, so
      pulling down always really re-fetches.
- [x] Reddit aggregators throttled to their own cadence (15 min) - Tradestie recomputes
      every 15 min and ApeWisdom every 30, so the old 3-minute poll fetched the same rows
      four or five times over.
- [x] Response bodies are read with a 4M-char ceiling.

---
#### D. BUG SWEEP

- [x] **The 15-second ledger replay.** `refresh()` called `recompute()` on EVERY tick, which
      re-read every row of the transactions table and replayed the entire buy/sell history -
      on the main thread - to arrive at positions that had not changed. Split into
      `recompute()` (data changed: reads the DB, replays the ledger) and `reprice()`
      (prices changed: pure in-memory, no database at all). Crossing local midnight falls
      back to a full replay, because the ledger tags shares bought *today*.
- [x] **Side effects during composition.** `RowActionHost` ran navigation, database writes
      and the caller's state reset straight from the composable body for OPEN / NEWS /
      WATCH_TOGGLE. Compose makes no promise about when or how often a body runs. Moved into
      `LaunchedEffect(pending)`.
- [x] **Main-thread disk and database work**, all moved off: the advice prompt file (read
      every transaction + build the string + write to Downloads, inside an onClick),
      restoring a backup (parse + rewrite every table in one transaction, from a dialog
      button), and reading any user-picked file (up to 8MB through the content resolver).
- [x] **A stuck button.** `preloadNewsForAdvice` only called back on the success path, so one
      throw left the Advice tab on "Gathering news..." forever. `onDone` is in a `finally`.
- [x] `EditPositionDialog`'s fields are keyed on the symbol.
- [x] `SymbolSearch` de-duplicates by symbol - Finnhub returns one row per venue, which is
      the same duplicate-key crash class as Round 31.
- [x] A headline with no link is no longer a dead click target.

**Regression checks run before shipping:**
- Reader extractor: 7 page shapes in real Chromium, 0 failures, including "must DECLINE on a
  quote page" and "running it twice must not wreck the page".
- MarketClock: every session boundary plus both DST transitions, against real java.util
  TimeZone - the same classes Android uses.
- Backoff sequence and headline de-duplication both verified by direct port.
- `publish()` is fed the ASCENDING transaction list for totals and the DESCENDING one for
  the UI. Everything `Ledger.totals` does with it (cash, net deposits, dividends, fees) is an
  order-independent sum, so `reprice()` cannot produce different numbers from `recompute()`.
- Every dialog is a Material3 `AlertDialog` in its own window, so neither the app-level nor
  the reader's BackHandler can swallow a dialog dismissal.
- APK: versionCode 32 / 4.5, signing cert `2e8c3847...` unchanged -> installs in place over
  v4.4 with the portfolio intact. DB_VERSION 3, BACKUP_VERSION 2 untouched.

### Round 31 (v4.4) — THE NEWS CRASH, and the Android back button

TJ: "sometimes when I press news on one of my stocks the news will load then shortly
thereafter the app will crash and close." Also: back should not close the app.

**ROOT CAUSE OF THE CRASH — read this before touching the feed code again.**

`DetailScreen` renders its SEC Form 4 rows as `items(filings, key = { it.id })`, and
`FeedItem.id` was `kind|symbol|published|title`. Two facts collided:

1. EDGAR's atom entry carries `<filing-date>` (a bare `yyyy-MM-dd`, inside `<content>`)
   BEFORE `<updated>` (a full timestamp). `Insider.parse` matched both tags into one
   variable and kept whichever came first — always the DATE-ONLY one. So every filing
   accepted on the same day came back with an identical `published`.
2. `cleanTitle` strips the CIK, so two Form 4s from the same reporting person on the same
   day also share a title.

Same kind, same symbol, same timestamp, same title → **byte-identical `id`**, and a
LazyColumn handed the same key twice throws `IllegalArgumentException` and takes the
process down. It landed *after* the headlines because EDGAR is the slower of the two
requests the detail screen fires: news paints, filings arrive a few seconds later, crash.

The Feed tab never showed it because `publishFeed()` runs `.distinctBy { it.id }`. The
per-symbol `loadInsider()` path never had that, and it is the path the News chip uses.

- [x] **`FeedItem.uid`** added: a publisher-assigned unique id. `id` prefers it when set.
- [x] `Insider.parse` reads `<accession-number>` into `uid` — unique across all of EDGAR,
      so two same-day filings by one insider can never collide again.
- [x] `Insider.parse` reads `<updated>` and `<filing-date>` into SEPARATE variables and
      prefers the precise stamp. Fixes the collision at its source AND restores real
      newest-first ordering in the feed, which had been flattened to day resolution.
- [x] `Insider.parse` skips an entry whose id it has already emitted.
- [x] `loadInsider()` applies `.distinctBy { it.id }` — the same guarantee `publishFeed()`
      has always given the feed path.
- [x] `DetailScreen` de-duplicates filings AND headlines again at render time. Four layers,
      because this is a whole-app crash and any one future data source could reintroduce it.
- [x] Headlines are keyed now (`newsKey`, the same expression used to de-duplicate them),
      so a background refresh no longer loses scroll position in a long news list.

**Other duplicate-key hazards found in the same sweep (same crash class, not yet hit):**
- [x] `SymbolSearch.query` → `.distinctBy { it.symbol }`. Finnhub returns one row per venue,
      so a cross-listed name gave the search list the same key several times.
- [x] `recompute()` → `.distinctBy { it.symbol }` on rows. Should be impossible (the ledger
      groups by symbol, watchlist has symbol as PRIMARY KEY) — cheap insurance.

**Android back button** (MainActivity) — there was no `BackHandler` at all, so back closed
the app from anywhere.
- [x] `tabHistory`, a real stack of visited tabs. Back unwinds the actual path:
      Portfolio → Feed → Advice → back → Feed → back → Portfolio. Not always "go home".
- [x] Order: search sheet → detail screen → tab history → Portfolio → exit. One step per press.
- [x] Consecutive duplicates are not pushed; the stack is capped at 16.
- [x] At the Portfolio root, exiting takes TWO presses within 2.5s, with a toast in between.
      Any back press that actually navigates, and any tab tap, resets that timer.
- [x] `goToTab()` replaces the inline tab-select lambda, so the tab bar and the back button
      cannot drift apart. It also clears `detailToNews`, which the old lambda forgot.

**Crash log** (new `util/CrashLog.kt`) — this app is sideloaded: no Play Console, no crash
reporting, no practical way to read logcat. "The app closed" was the entire bug report, and
that is why the crash above took a full read of the feed path to find.
- [x] A `Thread.UncaughtExceptionHandler` installed first thing in `onCreate` writes the
      stack trace to the app's private folder, then chains to Android's own handler so the
      process still dies normally. Capped at 60KB, newest last.
- [x] Settings → "Crash log": the last crash in one line, Save to Downloads, Copy, Clear.
      Reads "No crashes recorded" when the file is empty.

**UI**
- [x] News rows show two lines of the article summary. It was being fetched on every
      headline and then thrown away.
- [x] A headline with no link is no longer a click target that silently does nothing (the
      empty URI threw inside the Intent and `runCatching` swallowed it). Linked ones say
      "tap to read"; a failure to open now toasts instead of failing in silence.
- [x] The News header carries its count.

**Regression checks run before shipping:**
- Every dialog in the app is a Material3 `AlertDialog`, which renders in its OWN window and
  consumes back there — the new `BackHandler` cannot swallow a dialog dismissal.
- Tapping the tab you are already on still clears any open detail/search, exactly as the
  old lambda did; only the history push is skipped.
- `FeedItem.id` changing shape for insider rows is safe: ids are never persisted, and both
  consumers (`publishFeed` dedupe, LazyColumn keys) derive them fresh.
- The DetailScreen scroll-to-news index math (`1 + txns + filings + 2`) is unchanged and
  still counts the list it actually renders.
- Back-stack logic was simulated over deep paths, repeat taps, and 40 tab switches.
- APK verified: versionCode 31 / 4.4, signing cert `2e8c3847...` unchanged → installs in
  place over v4.3 with the portfolio intact. DB_VERSION 3, BACKUP_VERSION 2 untouched.

### Round 30 (v4.3) — the price display (TJ couldn't tell what the numbers were)
Fair. The row showed an unlabelled big figure, then a bare "+0.13  +0.15%", then a cryptic
"AH $89.42  +0.22%", all squeezed into a right-hand column about 90dp wide. Nothing said
which number was live and which was the close, or which change belonged to which session.

- [x] **New shared `PriceBlock` composable** (Widgets.kt), used by BOTH the list rows and
      the detail screen so the two can never drift apart. `big = true` scales it up.
- [x] The heading NAMES the big number and the market state, driven by `Quote.marketState`:
      CURRENT PRICE · MARKET OPEN / CLOSING PRICE · MARKET CLOSED / LAST CLOSING PRICE ·
      PRE-MARKET / LAST PRICE · DELAYED FEED / NO QUOTE YET.
- [x] Two labelled columns split by a rule: DURING MARKET HOURS (dollars, then percent) and
      AFTER HOURS / OVERNIGHT (the live extended price, then its change and percent measured
      from the close). PRE-MARKET replaces the second label before the open.
- [x] The extended-hours PRICE is painted neutral, not green/red - it is a price, not a gain.
      Only changes carry colour.
- [x] Empty states read "no trading yet" and "not available" instead of blanks.
- [x] `Fmt.changeMoney(price, v)` added: same digit rules as `changeFor` but written as
      money ("+$1.24"), since the change now sits directly beside a price.
- [x] The price left the cramped right-hand column and takes the full row width; the
      sparkline grew from 48x30 to 64x34 with the space freed up.

**Regression checks run before shipping:**
- Text-width check at 360dp (the Moto G): each session cell gets ~150dp. The longest label,
  "AFTER HOURS / OVERNIGHT" at 10sp, is ~122dp, and the longest value line ~100dp - both
  fit without wrapping, and the label is allowed 2 lines anyway. This is the clipping class
  of bug TJ reported back in v1.7, so it was measured rather than eyeballed.
- The MONEY half of the row (YOUR SHARES / YOU MADE TODAY / SINCE YOU BOUGHT) is untouched,
  including `dayPnl`'s same-day-purchase handling from v2.2.
- `Fmt.changeFor` is kept, not renamed - `changeMoney` is additive.
- Watch-only rows render the same PriceBlock and skip the money half, exactly as before.
- DetailScreen scroll index untouched (the header is still ONE list item). DB_VERSION 3,
  BACKUP_VERSION 2, signing cert `2e8c3847...` - installs in place over every build.

### Round 29 (v4.2) — market-wide news (TJ: "All" only showed his own stocks)
TJ was right. `refreshFeed` only ever fetched `rows.map { it.symbol }` - holdings plus
watchlist - so "All" was "My stocks" with the watchlist added and nothing else. There was
no market-wide source in the app at all.

- [x] **`News.market()` added**: three Google News RSS feeds fetched at once - the BUSINESS
      desk, an earnings/guidance/results query, and a query for the corporate events that
      move a share price (acquisition, merger, upgrade, downgrade, price target, recall,
      lawsuit, FDA approval), all windowed to `when:2d`.
      Built on `news.google.com/rss` deliberately: same host, path shape and parser that
      `News.googleRss` has used since v1.0, so it is the one market source already proven
      to work from the phone. (The CNBC and Yahoo feed URLs could not be verified from this
      container - robots.txt blocks fetching them - so they were NOT used on a guess.)
- [x] The market fetch runs as its own job started at the top of the refresh, so it lands
      inside the existing wait and adds nothing to v4.1's load time.
- [x] **Cross-tagging**: a market headline that names one of the holdings is marked owned,
      so it appears under "My stocks" too - that is the market news most worth seeing. The
      match is whole-word against the ticker and the company's first long name word, and
      tickers under 3 characters are skipped, so "It all comes down to..." cannot match IT
      and "On Wednesday..." cannot match ON.
- [x] Feed rows with no ticker get a MARKET badge instead of an empty one, and a market row
      with no link and no ticker is no longer tappable (it used to call `onOpen("")`).
- [x] Each filter now explains itself in one line at the top of the list.

**Regression checks run before shipping:**
- Holding matcher run over 7 titles: 3 correct matches (ticker, and two by company name),
  4 correct non-matches including the two short-ticker traps. 0 failures.
- Tab filtering verified on a mixed feed: All shows everything, My stocks shows only owned
  rows (now including the cross-tagged market headline), Insider unchanged.
- `existingFilings` still filters on `kind == INSIDER`, so stale market rows are replaced
  by fresh ones each pass rather than accumulating; v4.1's stage-2 EDGAR fallback is intact.
- The SEC gate is still `Semaphore(4)`. `_feedAt` still stamped once, at stage 1, so v3.7's
  runaway-retry fix is untouched.
- DetailScreen scroll index untouched. DB_VERSION 3, BACKUP_VERSION 2, signing cert
  `2e8c3847...` - v4.2 still installs in place over every build back to v1.2.

### Round 28 (v4.1) — feed load time (TJ reported the Feed tab was slow)
Diagnosis: `refreshFeed` awaited EVERY source before drawing a single row - all the
headlines, then all the SEC filings, then the two Reddit endpoints one after the other -
and only then assigned `_feed.value`. The tab stayed empty for the sum of the slowest
paths. So: normal for how it was written, not normal for what it does.

- [x] **The feed now publishes in three stages.** Headlines land first and go straight on
      screen; SEC filings (two business days behind by law, on a 30-minute cadence) fill in
      after without holding them up; the Reddit call is started at the top of the refresh
      and awaited last, so it overlaps everything instead of queueing behind it.
- [x] **`Social.trending` fetched Tradestie and ApeWisdom sequentially.** They are
      independent; both now run at once, halving that leg.
- [x] `loadInsider` had the identical empty-cache bug fixed in `loadNews` last round -
      one unreachable-EDGAR moment marked a stock as having no filings for the session.
- [x] De-duplication, blank-title filtering and newest-first ordering moved into one
      `publishFeed()` helper, so all three stages order the list identically.

Modelled on a 20-symbol / 16-holding portfolio at typical latencies: time before the first
rows appear drops from ~7.8s to ~3.2s (about 60% less). Total work finished is unchanged
(~8s) - the wait was reshaped, not removed. Real numbers will vary with the network.

**Regression checks run before shipping:**
- Stage-2 fallback verified: fresh filings replace the old ones; an EMPTY filing result
  means EDGAR was unreachable, not that the filings are gone, so what is already on screen
  is kept; nothing anywhere yields an empty list. v4.0 would have wiped the section.
- The SEC gate is still `Semaphore(4)` - the 10 req/s cap is respected exactly as before.
  Nothing about request volume changed this round, only what waits for what.
- `_feedAt` is still stamped once per refresh, at stage 1, so v3.7's fix for the runaway
  auto-load retry is untouched.
- DetailScreen scroll index untouched. DB_VERSION 3, BACKUP_VERSION 2, signing cert
  `2e8c3847...` - v4.1 still installs in place over every build back to v1.2.

### Round 27 (v4.0) — the offline bridge
- [x] **The offline import could offer the prompt's own worked EXAMPLE as your
      transactions.** `findObject` scans every balanced JSON block in a Claude reply and
      scores them; its comment says it prefers later blocks, but `maxByOrNull` returns the
      FIRST maximum on a tie. The prompt file shows Claude an example block with the same
      keys and the same key count as a real answer, so any reply that echoed the template
      before answering scored a tie and the example won - offering a 3-share NVDA buy and a
      $500 deposit that never happened. Blocks are now ranked by payload keys, then by how
      many transactions they actually carry, with the remaining ties going to the LAST
      block, as the comment always claimed.
- [x] **A failed news fetch was cached permanently.** `loadNews` short-circuited on
      `containsKey`, and it writes the key even for an empty result - so one fetch while
      offline meant that stock showed "no news" for the rest of the session however many
      times its detail screen was reopened. Only a non-empty result short-circuits now.
      The loading flag also moved into a `finally`, so a throw can no longer lock a symbol
      out of every later attempt.
- [x] Removed `TextPromptDialog` - dead since the transaction editor replaced it.

**Regression checks run before shipping:**
- Block picking simulated on the real failure (echoed template + real answer): v3.9 picks
  the example, v4.0 picks the answer. Three no-change cases verified identical - a single
  block (the API path, which is every `Claude.firstJsonObject` call), an advice-only reply,
  and a real answer that already came first.
- News caching simulated over one offline open plus two online reopens: v3.9 stays empty
  for the session, v4.0 recovers on the next open. A symbol that genuinely has no headlines
  now costs one request per detail-screen open, which is the correct trade.
- DetailScreen scroll index untouched. DB_VERSION 3, BACKUP_VERSION 2, signing cert
  `2e8c3847...` - v4.0 still installs in place over every build back to v1.2.

### Round 26 (v3.9) — the import path
- [x] **An undated screenshot row could import twice and double the position.** The importer
      stamps a row it cannot date with TODAY, and every row from a HOLDINGS/POSITIONS screen
      is undated by design (the extraction prompt sets date = null for those). Duplicate
      detection is scoped to the calendar day, so re-importing the same screenshot on a
      later day produced a different "today", matched nothing, and inserted the whole
      position again. `Db.findDuplicateIdAnyDate` now runs as a second pass for exactly the
      rows whose date was guessed - stricter per row than the day check (symbol required,
      both share count AND money must line up, no one-dollar fallback) since it has no date
      to anchor on.
      The badge in the review dialog and the skip in `commitImport` now both go through one
      `duplicateOf()` helper, so they can never disagree about what is a duplicate.
- [x] **Model auto-pick used a string sort**, so "claude-sonnet-5" ranked above
      "claude-sonnet-10" and a pinned dated snapshot outranked its own rolling alias. Now
      version numbers are compared as numbers and the undated alias wins ties.
- [x] A screenshot batch that ran past `max_tokens` reported the same opaque "did not return
      usable JSON" as a genuinely malformed reply. It now says to import fewer at a time.

**Regression checks run before shipping:**
- Import dedupe simulated on the real failure (same undated holdings screenshot, two days
  apart): missed by v3.8, caught by v3.9. Four no-false-positive cases all still import or
  still flag exactly as they did in v3.8 - a new dated buy, the same stock at a different
  size, a dated duplicate, and an undated cash deposit (no symbol, so the date-free pass
  correctly declines to guess).
- The date-free pass runs ONLY when the note carries the importer's own "date estimated"
  marker, so every hand-entered and every dated imported row takes the identical v3.8 path.
- Model picking simulated over three list shapes; the only changes are the two where the
  string sort was wrong.
- DetailScreen scroll index untouched. DB_VERSION 3, BACKUP_VERSION 2, signing cert
  `2e8c3847...` - v3.9 still installs in place over every build back to v1.2.

### Round 25 (v3.8) — restore hardening
- [x] **A malformed override in a backup poisoned every portfolio total.** org.json's
      single-argument `optDouble` returns NaN for a non-numeric value, and restore fed that
      straight into `Override.avgCost`. NaN propagates through the cost basis into market
      value, total gain and day gain - the entire summary reads "$NaN" and no refresh clears
      it, because the bad value is now stored in the overrides table. Only finite values are
      accepted; a rejected one falls back to the calculated basis.
- [x] **Merge overwrote live settings.** The restore dialog promises merge "adds anything
      missing and skips duplicates", but the settings loop wrote every key from the file, so
      merging a month-old backup silently flipped the cost-basis method, reset the
      auto-refresh interval and rewrote the "Last backup" line to a stale date. Merge now
      fills in only keys not already stored. Replace is untouched - it still takes the file
      wholesale, which is the device-transfer path the dialog describes.
- [x] Opening a stock via the News chip scrolled to the news section again on **every**
      later feed refresh that brought a new headline, yanking the screen back from wherever
      the user had scrolled. It now fires once per stock opened.

**Regression checks run before shipping:**
- Override restore simulated with a good value, a NaN and an infinity: the good value is
  byte-identical to v3.7; the two bad ones now fall back instead of producing NaN totals.
- Settings restore simulated against a month-old backup. Merge writes only the genuinely
  new key and preserves all four local preferences; Replace writes all four file values,
  exactly as in v3.7.
- Scroll-to-news simulated over an open plus 4 headline arrivals: 5 jumps before, 1 after.
  The index expression `1 + txns.size + insiderBlock` is unchanged.
- `hasSetting()` is a new read-only helper; nothing else calls it. DB_VERSION still 3,
  BACKUP_VERSION still 2 - v3.8 backups and v3.7 backups are interchangeable in both
  directions. Signing cert `2e8c3847...` unchanged.

### Round 24 (v3.7) — the feed
- [x] **The Feed tab refetched continuously whenever it came back empty.** The auto-load
      `LaunchedEffect` was keyed on `feedAt`, and `refreshFeed()` stamps `feedAt` even when
      it returned nothing - so: empty result -> `at` changes -> effect re-fires -> empty
      result, forever, for as long as the tab stayed open. Each pass fetched every symbol's
      news, the SEC feed and both social APIs. It fired offline, and on a watchlist-only
      portfolio with no headlines. Now keyed on `rows.size` alone; the tab still retries on
      re-entry because FeedScreen leaves composition on a tab switch.
- [x] **WSB momentum compared two different leaderboards.** `rank` on a merged row is
      Tradestie's ordering while `rank24hAgo` is ApeWisdom's, so a ticker sitting still at
      #15 on ApeWisdom read as "up 12 places" purely because Tradestie ranked it #3.
      `Trending` gains `apeRank` and the delta is computed against that.
- [x] The Feed's Refresh button now marks itself a manual refresh, matching v3.6's other
      refresh buttons; "down N" gained its missing "places"; and an empty mentions line no
      longer reserves blank leading under a ticker.

**Regression checks run before shipping:**
- Feed effect simulated over 10 ticks with an empty feed: 10 refetches before, 1 after. With
  a feed that returns headlines: 1 call before, 1 after - the success path is untouched.
- rankDelta simulated across all 4 row shapes. Both merged shapes changed (both from wrong
  to right); the ApeWisdom-only and Tradestie-only paths are identical to v3.6.
- `apeRank` defaults to 0 and `rankDelta` falls back to `rank`, so any Trending row built
  without it behaves exactly as before.
- DetailScreen scroll index untouched. DB_VERSION 3, BACKUP_VERSION 2, signing cert
  `2e8c3847...` - v3.7 still installs in place over every build back to v1.2.

### Round 23 (v3.6) — refresh feedback and the override math
- [x] **The pull-to-refresh spinner dropped down and retracted by itself every 15 seconds**
      on every tab. `Refreshable` was fed `state.loading`, which is true for the automatic
      tick as well as a user pull. UiState gains `manualRefresh`, set only by a pull or the
      Refresh button; the thin 2dp progress bar still tracks `loading`. Pulling while an
      automatic refresh is already running attaches the indicator to that one instead of
      snapping back and doing nothing.
- [x] **Overriding a share count alone silently changed the average cost.** `applyOverride`
      kept the calculated cost basis and divided it by the new share count, so correcting
      100 shares down to 50 turned a $10 average into $20. The calculated average is now
      carried across; an explicit average-cost override still wins.
- [x] The Portfolio summary's "Show the detailed numbers" panel collapsed itself whenever
      you scrolled down to your holdings and back - it is item 0 of a LazyColumn, so it gets
      disposed, and a plain `remember` does not survive that. Now `rememberSaveable`.

**Regression checks run before shipping:**
- Override math simulated across all 6 override shapes (none / shares only up / shares only
  down / avg-cost only / both / no prior position). Only the two shares-only cases changed,
  and both changed from wrong to right; every other path is identical to v3.5.
- Refresh flag simulated over auto-tick-alone, pull-alone, and pull-during-auto-tick. The
  indicator shows for exactly the two user-initiated cases and never for the automatic one,
  and the flag always clears in the `finally`.
- The `manual` parameter defaults to false, so all 9 internal `refresh()` callers (startup,
  the auto loop, returning to foreground, every txn edit, restore) keep their old behaviour
  untouched. `refreshFeed` likewise.
- DetailScreen scroll index untouched. DB_VERSION 3, BACKUP_VERSION 2, signing cert
  `2e8c3847...` - v3.6 still installs in place over every build back to v1.2.

### Round 22 (v3.5) — input validation and safety
- [x] The transaction editor accepted anything. An unparseable date silently became TODAY,
      which re-orders the FIFO lots and changes the cost basis of a stock held for a year.
      Save is now gated on a per-type validity check with an inline reason.
- [x] Auto-refresh seconds had no floor. Settings writes on every keystroke, so typing "15"
      passed through "1" - a one-second poll of every holding, the fastest way to get
      rate-limited by Yahoo and end up with blank prices. Floored at 5s (0 still = off);
      the typed text is untouched, only the interval is clamped.
- [x] `Storage.saveToDownloads` stranded a zero-byte IS_PENDING MediaStore row when a write
      failed - invisible in the file manager and impossible for the user to clear. The row
      is now deleted on failure.
- [x] The per-row News chip was a 24dp target inside the row's own click area, so a near-miss
      opened the detail screen instead. Now ~42dp and it honours the same long-press menu.
- [x] Typography slots carried no lineHeight. Overriding a Material slot replaces its default
      outright, so every explanatory paragraph rendered at the font's cramped default leading.
- [x] `Avatar` negated a hash to index the palette; -Int.MIN_VALUE is still negative. Masked.
- [x] Removed dead `showExt()` / `SHOW_EXT` (no caller, no toggle) and three unused imports.

**Regression checks run before shipping** (the explicit ask this round):
- DetailScreen scroll index untouched - no list item was added or removed above the News
  header, so `1 + txns.size + insiderBlock` still lands correctly.
- Validation simulated against 10 legitimate transaction shapes (buy with price, buy with
  amount only, sell, deposit, withdrawal, dividend, interest, fee, sub-dollar price, US date
  format) and 6 junk shapes. 10/10 accepted, 6/6 blocked.
- DB_VERSION still 3, BACKUP_VERSION still 2 - no migration, backups stay interchangeable.
- Signing cert SHA-256 still `2e8c3847...`, identical to every build since v1.2, so v3.5
  installs in place over any older APK with the database untouched.

### Round 21 (v3.4)
- [x] Off-by-one in the News scroll index introduced by v3.2's insider section
- [x] Import review dialog rendered EVERY row eagerly - a 111-row import composed 111 rows
      at once inside an AlertDialog. Now a LazyColumn capped at 420dp.
- [x] Import review gains Select all / None / New only plus a live selected count
- [x] `refreshAllNews()` was orphaned when v3.1 simplified the auto loop; documented as the
      Advice tab's path rather than deleted

## 7. NEXT STEPS (only if the user asks)

Nothing outstanding. Candidates if TJ reports issues after installing:

- Quotes blank on the phone: add a free Finnhub key in Settings (Yahoo may rate-limit).
- Search returns nothing: Yahoo's `v1/finance/search` is the primary; the Finnhub
  fallback only runs when a key is set.
- Screenshot import misreads Ally's layout: tune `EXTRACT_PROMPT` in `net/Claude.kt`
  (API path) and the prompt text in `net/ClaudeBridge.kt` (no-key path) — they are
  separate strings and both should change together.
- Possible additions: home-screen widget, price alerts, FIFO cost basis as an
  alternative to average cost, CSV export, drag-to-reorder the watchlist,
  a candlestick/range-selectable chart (1D/1W/1M/1Y) in place of the sparkline.

To rebuild:
```bash
export ANDROID_HOME=/root/android-sdk
nohup ./gradlew :app:assembleRelease > /home/claude/build.log 2>&1 &
# APK: app/build/outputs/apk/release/app-release.apk
# bump versionCode/versionName in app/build.gradle.kts for each delivered build
```

Then regenerate the checkpoint: `bash checkpoint.sh <n>`
