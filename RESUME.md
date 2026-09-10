# RESUME — READ THIS FIRST  (round 66, saved 2026-09-10 05:35:22 UTC)

You are picking up a long-running Android project that was interrupted.
Everything you need is on disk. Do NOT re-read CHECKPOINT.md end to end —
it is 240 KB of round history. This file plus `state.json` is the live state;
CHECKPOINT.md sections 0-5 (lines 1-530) are the only part worth reading cold,
and only if you need the architecture.

## 1. Bring the container back up

```bash
cd /home/claude && tar xzf <the checkpoint tarball>   # if the tree is missing
bash /home/claude/portfolio/setup-env.sh              # Android SDK, ~2 min, once
export ANDROID_HOME=/root/android-sdk
bash /home/claude/portfolio/watchdog.sh &             # restart the 3-min autosave
./ck status                                           # where the work stopped
```

Build traps that have cost real time before are in CHECKPOINT.md lines 22-60.
The short version: never blank `JAVA_TOOL_OPTIONS`; never run two Gradle builds
at once or kill one mid-flight; always background the build with
`setsid nohup ./gradlew ... > /home/claude/build.log 2>&1 < /dev/null & disown`.

## 2. The request this round is answering

> Whole-app round: code efficiency, features working as designed, cache/refresh balance (cache big, refresh liberally where it helps), bug hunt. Thicker separator bars between stocks. Research accuracy. ETF section must rank genuinely healthy, strong-buy ETFs best-first using multiple sources. Worst section: keep only stocks with a buyable companion short vehicle, or delete the section entirely.

## 3. WHERE THE WORK STOPPED

- **In flight:** Remaining audits: detail-ui, settings/Explain, cross-cutting, round66-diff
- **Next action:** Then bump to v7.7, full regression, signed APK

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 6/10 done

- [x] T0  Baseline: v7.6 tree green in this container  — v7.6 tree green in this container
- [x] T1  Thicker separator bars between stocks  — separator 3dp -> 5dp with 7dp of air either side; RowLayoutUiTest floor raised 18dp -> 26dp so a revert is caught
- [x] T2  ETF section: accurate, multi-source, healthy strong-buy funds ranked best-first  — ETF ranking: one fund per exposure (Schwab/Saxo both say comparison is only meaningful within an exposure group), youth no longer penalised twice with a three-year floor against performance-chasing, and the blurb now says what the feed cannot see
- [x] T3  Worst section: keep only stocks with a buyable companion short vehicle, or delete the section  — Worst section deleted: tab, scorer, ShortVehicle, the inverse-ETF enrichment and its Claude prompt sections. Measured 16/20 momentum mega-caps have a US single-stock inverse fund vs 2/40 beaten-down names, one of those foreign-listed only
- [x] T4  Stock research accuracy audit  — research accuracy: the ETF ranking reworked and grounded in Schwab's and Saxo's own selection guidance; the Worst list removed rather than left inactionable; A06 restored the news blurbs the cache was discarding
- [x] T5  Cache and refresh policy: cache as big as needed, refresh liberally where it helps  — cache/refresh audit produced A02 (cadences never fired), A05 (quotes never pruned) and A07 (marks travelling in backups)
- [ ] T6  Whole-app parallel review: bugs, efficiency, UI, features working as designed
- [ ] T7  Fix every confirmed finding
- [ ] T8  REGRESSION + ship v7.7
- [>] 14  Audit the six subsystems the two interrupted workflow runs never reached  — portfolio-ui, detail-ui, research-ui, settings-explain, cross-cutting, round66-diff

**Resume at 14** (Audit the six subsystems the two interrupted workflow runs never reached).

## 5. Open findings — 1 still open, 55 fixed

- [x] A01 (high) Db.kt:38 txns indexes are created only in onCreate and are not IF NOT EXISTS, so any upgraded database has none - findDuplicateId then full-scans txns once per imported row  — createTxnIndexes with IF NOT EXISTS, called from onCreate and the onOpen repair block, so every upgraded install heals on next launch; DbTest proves the legacy fixture gains both indexes
- [x] A02 (high) PortfolioViewModel.kt:2283 feed and Form-4 cadences are counters local to the poll coroutine, restarted by every setForeground(true), so they measure uninterrupted foreground seconds - the 30-minute insider refresh effectively never fires, and the feed pass can run twice within seconds  — feed and filings cadences are now wall-clock marks (_feedAt, Keys.FILINGS_AT) that survive startAuto being relaunched and the process dying; the decision is the pure passDue() with six tests, and filings can come due independently of the feed
- [x] A03 (high) TxnEditor.kt:55 seeds price and quantity from display formatters, so opening a transaction and pressing Save with no edit re-rounds it and silently changes the recorded cash  — TxnFields extracted from the dialog: seeds use Fmt.exact (round-trips through the editor's own parser) and Save shares one computation with the preview. Pure tests, no flaky dialog rendering
- [x] A04 (med) PortfolioViewModel.kt:1085 onTrimMemory clears _insider but not insiderAt, so the Form 4 section is blank for up to 30 minutes after a memory trim even though the filings are still in memory and on disk  — insiderAt is cleared with _insider on a memory trim, matching coreFetchedAt and ratingsFetchedAt beside it
- [x] A05 (med) Db.kt:1007 the quotes table is never pruned and is read whole, parsing every spark blob, synchronously on the main thread at launch  — Db.purgeQuotes, called with the other purges - the quotes table was the one unbounded cache, and it is parsed whole on the launch path
- [x] A06 (med) PortfolioViewModel.kt:2455 a headline's summary is dropped when the story is cached, so reopening a stock loses every blurb  — loadNews writes the blurbs through with the stories, and cacheNews's conflict path updates the summary without ever erasing one; three tests
- [x] A07 (med) Db.kt:1429 a restore imports the old phone's AUTOSAVE_AT/AUTO_BACKUP_AT/DOWNLOADS_TIDIED, so a new phone skips its first safety copy for 24 hours  — AUTOSAVE_AT, AUTO_BACKUP_AT, DOWNLOADS_TIDIED and the new FILINGS_AT are excluded from backups, and restoreAsync forces a safety copy of what it just restored
- [x] A08 (med) PortfolioViewModel.kt:304 spinnerShouldShow does not know about the research/ETF build, so the poll loop retracts the pull indicator mid-build  — spinnerShouldShow gained a researchLoading term fed from _researchBusy, so the poll loop no longer retracts the pull indicator mid-build
- [x] A09 (low) PortfolioViewModel.kt:762 two KDocs claim the quote wave survives backgrounding; it runs on fgScope and is cancelled  — both KDocs corrected, and setForeground now starts a replacement wave when one was cancelled - so a flick away and back inside the grace window no longer leaves stale prices
- [x] A10 (low) PortfolioViewModel.kt:1117 restoreSparklines runs a second full quote-cache read on the launch path that provably cannot change anything  — restoreFromCache(fromInit = true) skips the duplicate quote-cache read on the launch path
- [x] A11 (low) PortfolioViewModel.kt:2385 two different caps for the same per-symbol news list - the feed pass truncates 60 headlines to 40, removing stories the user is scrolling  — one cap for the per-symbol news list - the merge pass was silently deleting the bottom 20 of a 60-story list
- [x] A12 (low) Format.kt:69 changeFor/changeMoney document four decimals for sub-dollar stocks and give three  — money4 for a sub-dollar price CHANGE, which is what both KDocs always claimed
- [x] B01 (med) The Worst deletion left the offline Claude prompt still asking for a 'worst' list the app can no longer parse, plus a template phrase about inverse ETFs and several stale 'three lists' comments  — both prompts, the template phrase and every stale comment cleaned; the stored-tab coercion note now explains both directions
- [x] B02 (high) SELF-REVIEW: my own A02 fix stamped lastFilingsAt after refreshInsiders' symbols.isEmpty() guard, so an empty portfolio or an already-running pass never advanced the mark and passDue reported filings due on EVERY tick - a full feed pass every 15 seconds  — stamped in startInsiderRefresh before both early returns, with a regression test asserting a stamped mark is not due one tick later
- [x] E1 (high) EtfScreener.fetchAll stops paging on the PARSED row count, so one non-fund row on a page truncates the whole universe to 100 - the ETF list TJ is buying from can be ~130 funds while the screen claims 850  — fetchAll stops only on an empty page - one non-fund row no longer truncates the universe from 523 funds to 100
- [x] E2 (high) EtfExposure checks the US size ladder before the region checks, so iShares MSCI EAFE Small-Cap merges with a US small-cap fund; gold bullion merges with gold miners; SGOV merges with TLT. My own new code, and exactly the over-grouping its KDoc says is the failure that matters  — region is tested before the US size ladder, regional size bands stay separate, miners never group with bullion, and a Treasury fund is only grouped when its name states a maturity band; five new tests
- [x] R1 (high) Claude's conviction is written into the displayed SCORE and re-sorts the ETF list, so a fund a model asserted can sit at row 1 showing SCORE 100 above every fund the app actually screened  — conviction is its own field; score stays the app's arithmetic; the card badges a suggested fund as CLAUDE n/10 and it can never outrank a fund the app scored
- [x] H1 (high) A Yahoo cooldown makes MarketData fall back to per-symbol quotes for EVERY symbol, so a 20-stock portfolio sends ~80 requests a minute to Finnhub and then Stooq for the whole cooldown - the exact traffic shape the batch endpoint exists to remove  — a batch that sent nothing because both Yahoo hosts were cooling no longer triggers the per-symbol fallback - that was ~80 requests a minute to Finnhub and then Stooq for the length of every cooldown
- [x] H2 (high) Screener, EtfScreener and FundamentalsFeed abandon the whole call when query1 alone is cooling, so one 429 anywhere empties the Research and ETF tabs while query2 sits idle  — Screener, EtfScreener and FundamentalsFeed skip a cooling host instead of abandoning the call, matching what ChartFeed already documented; one 429 on query1 no longer empties the Research and ETF tabs
- [x] H3 (high) YahooAuth.invalidate zeroes mintedAt, so the MIN_INTERVAL guard that exists to stop a handshake loop is dead on every path that follows a 401  — YahooAuth.invalidate keeps the clock, so the MIN_INTERVAL guard that exists to stop a handshake loop is live again after a 401
- [x] H4 (med) Http.noteRateLimited escalates per 429 RESPONSE rather than per cooldown, so a burst of four concurrent requests jumps straight to a 4-minute backoff on the first rate-limit event  — the rate-limit ladder escalates once per cooldown, extracted as the pure nextRateLimit with three tests
- [x] E3 (med) The 'Same exposure as ...' line is appended last and cut off by the card's six-reason limit, so on VOO - the case the feature was written for - it never renders  — the 'same exposure' line is prepended, so it survives the card's six-reason limit on exactly the funds the feature was written for
- [x] E4 (med) The ETF return normalisation gates on the sum of available weights, so a fund with a full three-year record but no YTD figure is capped at 16 of 34 points - and reporting a worthless YTD gains it eleven  — the return normalisation gates on the record rather than the weight sum; two tests pin the invariant
- [x] E5 (med) The fund card's 1Y cell is a price-only 52-week change shown and scored beside 3Y and 5Y NAV TOTAL returns, so every income fund is marked down by its own yield  — the 52-week figure is shown as '1Y price' and earns nothing - every other horizon in the score is a NAV total return, and averaging a price change with them marked income funds down by their own yield
- [x] R2 (med) Trending rows for symbols outside the nine equity screeners carry no price, name or day change, and nothing ever fills them  — Trending rows outside the nine screeners had no price/name/change - loadResearch now awaits one batched fill
- [x] R3 (med) 'cheap for that growth' is printed for a company whose forward EPS is BELOW trailing, when the growth term scored zero  — the valuation line only claims growth when the growth term scored, and says plainly when earnings are not growing
- [x] R4 (med) 'most shorted' and 'day losers' are printed among the reasons a stock is rated a good BUY, though neither screen scores anything  — only the four screens that actually score are named among the reasons to buy
- [ ] R6 (low) Stale comments across Research, ResearchModels, PortfolioViewModel, Http, Db and EtfScreener still describe the Worst list, the short-vehicle lookup, a two-list ETF plan and a RESEARCH_TAB index that has moved
- [x] PUI1 (high) RowActions.EditPositionDialog seeds shares/avgCost with display formatters (Fmt.shares/Fmt.priceBare) so Save with no edit writes a ROUNDED override - same bug TxnEditor fixed with Fmt.exact  — PositionFields extracted + Fmt.exact seeds; Fmt.exact now rounds to 12 sig digits so a quotient does not seed 17 digits
- [x] PUI2 (med) PortfolioScreen.BigLine weights only the label and leaves two unweighted figures after it - at large font scale the label measures to 0dp and vanishes; sub has no overflow so it clips with no ellipsis  — BigLine: all three children weighted 1:2, figures via AutoFitNumber so nothing measures to zero and nothing clips
- [x] PUI3 (med) PortfolioScreen reconciliation note claims Since-you-started = broker Total G/L + banked profit, but totalGain also contains dividends/interest/fees so the stated arithmetic does not add up  — Reconciliation note now names dividends, interest and fees so the card's own arithmetic adds up
- [x] PUI4 (med) RowSeparator thickened to 5dp but still painted in colorScheme.outline at 1.24:1 (light) / 1.37:1 (dark) - TJ's 'even thicker' request is blocked by the COLOUR, not the thickness  — New rowRule colour: 3.14:1 light / 3.07:1 dark, replacing outline at 1.24:1 - the separator is finally visible
- [x] PUI5 (med) PortfolioScreen dividends value uses fill green Green not greenText - 2.02:1 on the StatCard surface, the only coloured value on that card not going through signColor  — Dividends figure now greenText not Green
- [x] PUI6 (low) WatchlistScreen PRICE column header is an unweighted child after a weighted Spacer (measures to ~8dp and renders empty at large font scale) and labels a right-hand price column that no longer exists  — Watchlist header: one weighted label, dead PRICE column removed
- [x] PUI7 (low) StockRow watch menu reads row.watchOnly, never true for a held row, so a held+watched symbol always says 'Also watch this' and the toggle is one-way with an untrue toast  — Row.watched added and used by the menu and the toggle - held+watched symbols can now be un-watched
- [x] AUD1 (med) redText in dark theme was brand Red at 4.41:1 on the StatCard surfaceVariant - under AA. Found by the new ContrastTest  — New RedTextDark 0xFFF2606F: 5.20:1 on surfaceVariant, 6.06:1 on background
- [x] ETF1 (high) EtfScore: 0.0 is the sentinel for 'not published', so a flat/absent YTD is DROPPED from the weighted average - and because the average rescales, dropping the weakest horizon RAISES the score. A missing YTD scores like +15pct YTD. The list systematically prefers funds with less complete data  — YTD moved out of the normalised average (now 5Y+3Y rescaled to 30, YTD a separate 0-4 term). Monotonic; hiding a figure can never gain points
- [x] ETF2 (high) EtfScreener.fetch returns emptyList() for both 'past the end' and 'no host answered', so fetchAll's empty-page stop silently truncates 523 funds to 200 on a transient 429 - and buildEtfs only warns when a list returns nothing at all  — fetch returns null for 'nobody answered' vs emptyList for 'end of list'; fetchAll reports completeness and buildEtfs warns when a screen answered only part of its pages
- [x] ETF3 (high) EtfExposure.keyOf tests region BEFORE asset class with no bond guard, so BNDX/BNDW (international/world BONDS) get key 'Global equity' and are DELETED as duplicates of VT. Same for IAGG vs BND  — Asset class tested before region, with a bond-word guard; 'aggregate bond' now requires the name not to be international/global/world/ex-US
- [x] ETF4 (med) Every term that could separate two S&P500 trackers is saturated for large funds, so the exposure-group winner is decided by third-decimal noise or, on the integer tie, by Yahoo's screen order - and dedupe now DELETES the loser  — Explicit Research.ETF_ORDER: score, then cost, then size, then ticker - fully deterministic, unknown fee sorts last
- [x] ETF5 (med) ramp clamps a negative long-run return to 0 points but 'possible' still counts its weight, so the 4pt YTD term scales to 9.71 of 34 exactly for funds with the worst long-run records  — Same fix: a losing 3Y record can no longer have its hot year amplified 2.43x
- [x] ETF6 (med) expenseRatio > 0.0 treats a genuinely free fund (BKLC, BKAG at 0.00pct) as 'unknown': 20 cost points forfeited and a confidence penalty, scoring identically to a 0.85pct fund  — expenseRatio sentinel is -1.0 through EtfRow, EtfFacts, parse, toJson/fromJson, the card and the Claude prompt; oneYearPct no longer opens the return block
- [x] ETF7 (med) One key 'Global equity' covers both all-world INCLUDING the US (VT, ACWI) and all-world EX-US (VXUS, IXUS, VEU, ACWX) - opposite answers to the same question, and the ex-US half is deleted  — Global equity split into 'incl US' and 'ex-US', ex-US tested first so 'All-World ex-US' reads correctly
- [x] ETF8 (med) isLeveragedOrInverse is applied only on the screener path, so a leveraged fund Claude adds (TQQQ) survives on a list whose own sources note says leveraged and inverse funds are excluded  — Leveraged/inverse funds dropped on the Claude path too, once the quote fill supplies a name; prompt now says not to add them
- [x] CHT1 (med) Two-finger pan compares centroidX across frames even when the POINTER COUNT changed, so a third finger landing reports a quarter-span pan that never happened  — centroidN tracks how many pointers the pan reference was measured over; a frame with a different count re-seeds instead of reporting a phantom pan
- [x] CHT2 (med) GestureMode.ZOOM latches until every finger lifts: one finger of a pinch leaving leaves the chart inert and consuming, so neither the chart nor the page beneath it can move  — A pinch down to one finger becomes a PAN (never a scrub) instead of latching in ZOOM and consuming forever
- [x] CHT3 (med) nearestIndex's off-screen clamp is skipped when the window falls between two candles, so the readout prints a price and date from weeks outside the window with no crosshair anywhere  — pointAt returns NO_SCRUB when the nearest point falls outside the axis, so the readout and the crosshair never disagree
- [x] CHT4 (low) canPanNow omits windowBounds != null while pan() requires it, so the caption promises a pan that cannot exist and the 350ms hold still arms and consumes  — canPanNow includes windowBounds != null, so the caption and the 350ms hold match what pan() can actually do
- [x] RES1 (high) No migration for the research cache: a Claude-added fund written by the previous build still deserializes with score=conviction*10, draws as an app SCORE badge, and carryEtfExplanations pins it at row 1 above every fund the app measured, forever  — Cache format version is finally read; a version-1 row with a score but no reasons and no facts has its number moved back to conviction. Writer bumped to 2
- [x] RES2 (med) The 'where these numbers come from' note omits Nasdaq - the source of the analyst consensus and price target that 30pct of an enriched Best score is blended from  — Nasdaq named in SOURCES, and the closing sentence now says the analyst view is blended at 30pct
- [x] RES3 (med) One-fund-per-exposure is applied only on the screener path, but the tab blurb states it as an unconditional fact - and the app asks Claude for AGG and BND by name, which are the same exposure  — One fund per exposure now applies on the Claude path too, beside dropLeveraged; gaps hint no longer names both AGG and BND; prompt says one fund per exposure
- [x] RES4 (low) ResearchRow.followed is dead: nothing writes or reads it and it is not in the JSON codec, but its KDoc claims it drives the FOLLOWING chip  — Dead ResearchRow.followed deleted, with a note saying where the chip really comes from
- [x] RES5 (med) resetResearchPaging() has zero callers, so a page count of 40 survives a rebuild and the next TTL rebuild fires up to 50 Nasdaq requests with no user action  — resetResearchPaging now takes the sections to reset and is called from both rebuilds - stocks and funds keep their own page counts
- [x] RES6 (low) The persisted tab index is bounded against ResearchSet.SECTIONS but indexes the Section enum - two lists in two files, so the guard does not protect the array access it was written for  — Section.entries.getOrElse at the one use site, so a drift between SECTIONS and the enum cannot crash on every launch
- [x] RES7 (med) enrichPass reads Best, suspends for seconds of Nasdaq calls, then writes back the pre-suspension snapshot - a Claude import landing in that window is silently discarded AND persisted as lost  — enrichPass projects enriched rows onto the list as it is at write time, so a Claude import landing mid-pass survives; Import button gated on busy
- [x] RES8 (low) Seven comment sites still count three stock lists or four sections after the Worst deletion, including the data model header a maintainer reads first  — Section counts corrected in all seven comment sites

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-10 05:25:24 UTC  finding RES5: resetResearchPaging() has zero callers, so a page count of 40 survives a rebuild
- 2026-09-10 05:25:24 UTC  finding RES6: The persisted tab index is bounded against ResearchSet.SECTIONS but indexes the 
- 2026-09-10 05:25:25 UTC  finding RES7: enrichPass reads Best, suspends for seconds of Nasdaq calls, then writes back th
- 2026-09-10 05:25:25 UTC  finding RES8: Seven comment sites still count three stock lists or four sections after the Wor
- 2026-09-10 05:35:08 UTC  RES1 fixed: Cache format version is finally read; a version-1 row with a score but no reasons and no facts has its number moved back to conviction. Writer bumped to 2
- 2026-09-10 05:35:09 UTC  RES2 fixed: Nasdaq named in SOURCES, and the closing sentence now says the analyst view is blended at 30pct
- 2026-09-10 05:35:10 UTC  RES3 fixed: One fund per exposure now applies on the Claude path too, beside dropLeveraged; gaps hint no longer names both AGG and BND; prompt says one fund per exposure
- 2026-09-10 05:35:11 UTC  RES4 fixed: Dead ResearchRow.followed deleted, with a note saying where the chip really comes from
- 2026-09-10 05:35:12 UTC  RES5 fixed: resetResearchPaging now takes the sections to reset and is called from both rebuilds - stocks and funds keep their own page counts
- 2026-09-10 05:35:12 UTC  RES6 fixed: Section.entries.getOrElse at the one use site, so a drift between SECTIONS and the enum cannot crash on every launch
- 2026-09-10 05:35:13 UTC  RES7 fixed: enrichPass projects enriched rows onto the list as it is at write time, so a Claude import landing mid-pass survives; Import button gated on busy
- 2026-09-10 05:35:14 UTC  RES8 fixed: Section counts corrected in all seven comment sites

