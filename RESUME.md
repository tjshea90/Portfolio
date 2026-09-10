# RESUME — READ THIS FIRST  (round 66, saved 2026-09-10 00:37:25 UTC)

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

- **In flight:** Batch 1 of 3 review agents: portfolio-ui, detail-ui, research-ui
- **Next action:** Batch 2: settings-explain, cross-cutting, round66-diff

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

## 5. Open findings — 4 still open, 31 fixed

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
- [ ] PUI5 (med) PortfolioScreen dividends value uses fill green Green not greenText - 2.02:1 on the StatCard surface, the only coloured value on that card not going through signColor
- [ ] PUI6 (low) WatchlistScreen PRICE column header is an unweighted child after a weighted Spacer (measures to ~8dp and renders empty at large font scale) and labels a right-hand price column that no longer exists
- [ ] PUI7 (low) StockRow watch menu reads row.watchOnly, never true for a held row, so a held+watched symbol always says 'Also watch this' and the toggle is one-way with an untrue toast

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-09 19:51:24 UTC  14 -> doing  portfolio-ui, detail-ui, research-ui, settings-explain, cross-cutting, round66-diff
- 2026-09-10 00:26:29 UTC  finding PUI1: RowActions.EditPositionDialog seeds shares/avgCost with display formatters (Fmt.
- 2026-09-10 00:26:29 UTC  finding PUI2: PortfolioScreen.BigLine weights only the label and leaves two unweighted figures
- 2026-09-10 00:26:29 UTC  finding PUI3: PortfolioScreen reconciliation note claims Since-you-started = broker Total G/L 
- 2026-09-10 00:26:29 UTC  finding PUI4: RowSeparator thickened to 5dp but still painted in colorScheme.outline at 1.24:1
- 2026-09-10 00:26:29 UTC  finding PUI5: PortfolioScreen dividends value uses fill green Green not greenText - 2.02:1 on 
- 2026-09-10 00:26:29 UTC  finding PUI6: WatchlistScreen PRICE column header is an unweighted child after a weighted Spac
- 2026-09-10 00:26:29 UTC  finding PUI7: StockRow watch menu reads row.watchOnly, never true for a held row, so a held+wa
- 2026-09-10 00:37:20 UTC  PUI1 fixed: PositionFields extracted + Fmt.exact seeds; Fmt.exact now rounds to 12 sig digits so a quotient does not seed 17 digits
- 2026-09-10 00:37:22 UTC  PUI2 fixed: BigLine: all three children weighted 1:2, figures via AutoFitNumber so nothing measures to zero and nothing clips
- 2026-09-10 00:37:23 UTC  PUI3 fixed: Reconciliation note now names dividends, interest and fees so the card's own arithmetic adds up
- 2026-09-10 00:37:25 UTC  PUI4 fixed: New rowRule colour: 3.14:1 light / 3.07:1 dark, replacing outline at 1.24:1 - the separator is finally visible

