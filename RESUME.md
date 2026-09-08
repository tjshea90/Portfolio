# RESUME — READ THIS FIRST  (round 63, saved 2026-09-08 07:49:08 UTC)

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

> Round 63: (1) swipe left/right to change tabs, (2) pinch-zoom charts continuously from All-time down to 5-minute intervals, (3) Best ETFs research tab - online research, periodically refreshed, cached between updates, with the Claude-app export the other sections have, (4) the last approved feature: SPY comparison overlay. Then a thorough sweep for UI/code/network improvements and bugs, repeated until confident the app is clean.

## 3. WHERE THE WORK STOPPED

- **In flight:** T8: SWEEP 1: adversarial bug hunt across the whole app; fix everything found
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 8/12 done

- [x] T0  Baseline: v7.3 tree builds and 443 tests green before any edit  — 443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)
- [x] T1  Swipe-to-change-tabs: horizontal gesture paging over the 6 top-level tabs  — gesture-based tab paging (not a pager - neighbours stay uncomposed so VisibleScope still describes one screen); pure swipeTarget decision + slide animation shared with the bar
- [x] T2  Pinch-zoom charts: continuous zoom across the range ladder, All-time <-> 5m  — pinch zoom walks ChartRange.ZOOM_LADDER (All->5Y->1Y->6M->1M->5D->1D/5-minute); one pointer loop shared with scrubbing, per-rung 1.55x accumulator, 380ms settle so a multi-rung spread fetches only the rung it lands on; chip row auto-scrolls to the selection
- [x] T3  Best ETFs data: ETF screener feed + scorer + cached section + periodic refresh  — EtfScreener (3 keyless Yahoo fund screens, 10 requests, ~850 funds - measured that top_performing_etfs duplicates top_etfs_us and dropped it), EtfRow/EtfFacts, EtfScore (returns weighted to 5y/3y, cost, size, liquidity, age, trend; leveraged+inverse excluded), Research.buildEtfs on its own 6h TTL, vm.loadEtfs/etfsStale with its own job
- [x] T4  Best ETFs UI: fourth research tab + Claude-app bridge export/import  — fourth research tab with its own scroll state, timestamp, refresh target, blurb, sources and warnings; ETF facts grid on the card; prompt/bundle/parse/merge carry the etfs array and name the universe gaps so Claude adds the funds Yahoo's screens omit; funds Claude adds survive a rebuild
- [x] T5  SPY comparison overlay: second series aligned to the first and drawn against it  — SPY overlay: percent mode with both lines rebased to the same moment (previous close intraday, the benchmark's value at the window start for longer ranges), zero line, segmented benchmark path across gaps, dual crosshair, legend, live edge on both tips, 'vs SPY' chip beside the range chips; shares loadChart's cache so it costs one fetch per range per TTL for the whole app
- [x] T6  Tests for T1-T5: pure + rendered  — SwipeTabTest 15, ChartZoomTest 12, CompareChartTest 10, EtfTest 23, GestureUiTest 11 (real multi-touch), CompareChartUiTest 8 - 79 new
- [x] T7  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke  — 522/522 green (443 baseline + 79 new, nothing pre-existing touched), lint vital clean, checkinit ok
- [>] T8  SWEEP 1: adversarial bug hunt across the whole app; fix everything found  — adversarial sweep
- [ ] T9  SWEEP 2: UI, code and network-efficiency pass; fix everything found
- [ ] T10  SWEEP 3: re-scan until clean - verify no fix introduced a new bug
- [ ] T11  Ship v7.4 (versionCode 61) + final checkpoint

**Resume at T8** (SWEEP 1: adversarial bug hunt across the whole app; fix everything found).

## 5. Open findings — 15 still open, 6 fixed

- [x] J01 (high) isLeveragedOrInverse excluded every short-duration bond fund: ' short ' and ' ultrashort ' matched 'iShares Short Treasury Bond ETF', 'Vanguard Short-Term Bond', 'PIMCO Enhanced Short Maturity' and 'iShares Ultra Short-Term Bond'. Short-duration bond funds are among the most widely held ETFs there are - the Best ETFs list could not have contained the safe half of a portfolio.  — the test is now what the fund is short OF: 'short' followed by a duration or credit word (term/duration/maturity/treasury/bond/...) is an ordinary bond fund; anything else is inverse. Explicit multiples and 'bear'/'inverse'/'ultrapro' still exclude outright. 9 real fund names asserted both ways.
- [x] F01 (high) loadEtfs shares _researchBusy with the stock pass, so opening the ETFs tab while the 18-request stock build is running silently does nothing - and nothing ever retries. The tab sits empty until the user switches away and back or pulls down.  — the section's build effect is keyed on the shared busy flag as well, so a request dropped while the other pass was in flight is re-made the moment it clears; neither call can loop because both return immediately inside their own TTL
- [x] F02 (med) A chip tap straight after a pinch is delayed 380ms: zoomSettling is only cleared inside the settle branch, so it is still true when the tap's LaunchedEffect runs. Contradicts the documented 'a tap is not a zoom' rule.  — tapping a range chip clears zoomSettling rather than merely not setting it - an explicit destination has no intermediate rungs to swallow
- [x] F03 (med) zoomFactor reads event.changes[0] and [1] positionally. A third finger landing, or one of two lifting, reshuffles that list and produces an impossible one-frame separation ratio - which the accumulator then spends as several real zoom rungs.  — the pinch now measures a NAMED pair of pointer ids chosen when the gesture begins; if either finger leaves, the pair is re-chosen and that frame yields no reading, so a third finger costs one frame instead of an arbitrary jump
- [x] F04 (low) showMoreResearch on the ETF section calls enrichVisible, which only has work for BEST and WORST - so revealing ten more funds starts an analyst pass and flips the busy indicator for nothing.  — showMoreResearch only enriches Best and Worst - a fund's numbers arrive with its screener row and have no second stage
- [x] F05 (high) The F01 fix reintroduced a worse bug: keying the build effect on the shared busy flag means a FAILED pass re-triggers itself the instant busy clears. An empty result leaves the set stale, so loadResearch/loadEtfs launch again immediately - an unbounded retry loop of 18 (or 10) requests against providers that are almost certainly rate-limiting, which is exactly what the backoff machinery elsewhere in the app exists to prevent.  — both auto-builds now sit behind RetryClock (30s/1m/2m/4m/5m per section), so an empty pass backs off instead of re-firing the moment busy clears; force still ignores it. RetryClock promoted to top-level internal and RetryBackoffTest now exercises the real class instead of a copy of its rule.
- [ ] F06 (high) CHART: PriceChart returns from the Column BEFORE the gesture surface whenever the series is null or empty. Pinching to a range that has never been fetched therefore destroys the gesture node mid-pinch: the zoom stops after exactly one rung, the badge vanishes, and the remaining fingers fall through to the list underneath. 'Zoom all the way down to 5 minute' is impossible in one gesture on any stock opened for the first time.
- [ ] F07 (high) CHART: onZoomStep = liveZoom.value is read ONCE inside pointerInput(Unit), so rememberUpdatedState is defeated and the captured lambda is whatever onZoom was on first composition. Opening a fund and tapping through to one of its holdings reuses the node, so the stale lambda writes zoomSettling into a dead MutableState - the 380ms settle never applies and a four-rung spread fires four chart fetches (eight with the overlay on), which is the exact traffic the feature was built to avoid.
- [ ] F08 (med) CHART: the benchmark's live edge is discarded whenever its last candle is later than the stock's - comparePercents looks every value up by the STOCK's timestamps, so valueAtOrBefore returns SPY's second-to-last point and the live price written into its tip is never read. The two ends being compared are then up to five minutes apart, which is precisely what compareLivePrice exists to prevent.
- [ ] F09 (med) CHART: in price mode the y-axis corner labels print the series high and low, but the axis is widened to include the dotted previous-close baseline. On a gap-down day - previous close 110, session 98-104 - the top of the axis is 110 while the label pinned to it reads 104.00. Comparison mode uses the real bounds for the same two labels, so the two modes give the same corners different meanings.
- [ ] F10 (low) CHART: the 'pts vs SPY' spread and the resting SPY readout take the benchmark's LAST FINITE value, which can be an earlier index than the stock's last point when the benchmark's tail is NaN. The printed out-performance is then a difference between two different moments.
- [ ] F11 (high) RESEARCH: every stock rebuild destroys the ETF list. carryExplanations returns the freshly built set, which Research.build never populates with etfs/etfGenerated/etfWarnings - so the 30-minute stock pass wipes the 6-hour fund pass, in memory and on disk. Ten Yahoo requests are then re-spent to rebuild it, repeatedly, which is exactly what TJ's 'keep the current list in cache until each update' rule forbids. notes is lost the same way while explained/explainedBy survive, so the screen claims an explanation whose text is gone.
- [ ] F12 (high) RESEARCH: fillResearchPrices writes the fetched quotes back into trending/best/worst but not into etfs. A fund Claude adds - which the prompt explicitly asks for - costs a real quote request whose answer is discarded, renders with no price forever, and re-spends the same request on every later import.
- [ ] F13 (med) RESEARCH: the org.json NULL trap, in the one place the architecture notes warn about it. optString on a JSON null returns the literal string 'null' on Android. A Claude reply with "catalyst": null paints 'null' under the card; "shortVehicle": null becomes a red 'NULL' inverse-ETF chip; "symbol": null inserts a fabricated NULL row. The desktop org.json used in tests returns the fallback, so no existing test can catch it.
- [ ] F14 (med) RESEARCH: the same NULL trap in EtfScreener.parse - a Yahoo row with a null longName yields the name 'null', and .ifBlank never fires because 'null' is not blank, so both fallbacks are skipped and the leverage filter runs its whole test against that string.
- [ ] F15 (low) RESEARCH: ResearchBridge's bundle computes dataAgeMinutes from set.generated only, so a user who has only opened the ETFs tab tells Claude the fund data is 0 minutes old when it may be six hours. etfGenerated is nowhere in the bundle.
- [ ] F16 (low) RESEARCH: a fund Claude returns with a category but no why survives the import and then vanishes at the next rebuild - carryEtfExplanations requires why to be non-blank - contradicting the screen's own promise that funds Claude adds are kept.
- [ ] F17 (low) RESEARCH: an imported reply with no notes blanks the previous notes; every other field in that copy merges rather than overwrites.
- [ ] F18 (low) RESEARCH: the FOLLOWING chip's watched/held set is remembered on set.generated and set.explained, neither of which changes when the ETF list rebuilds - so on the ETFs tab the chip can lag until the stock pass runs.
- [ ] F19 (low) RESEARCH: EtfScreener.fetch treats a valid 200 carrying zero quotes the same as a failure and retries the identical request against the other Yahoo host, so each list's terminal page costs two requests instead of one.
- [ ] F20 (low) RESEARCH: the ETF screener parse comment claims Yahoo publishes dividendYield as a fraction, copying the stock screener's rule for a DIFFERENT field name. Measured live: on ETF rows yieldTTM and dividendYield are both percentages (SPY 0.98). The code is right and its comment is wrong, which is how a later 'fix' introduces a 100x error.

## 6. Version

- Shipped: v7.3 (versionCode 60)
- This round ships: v7.4 (versionCode 61)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 07:49:07 UTC  finding F09: CHART: in price mode the y-axis corner labels print the series high and low, but
- 2026-09-08 07:49:07 UTC  finding F10: CHART: the 'pts vs SPY' spread and the resting SPY readout take the benchmark's 
- 2026-09-08 07:49:07 UTC  finding F11: RESEARCH: every stock rebuild destroys the ETF list. carryExplanations returns t
- 2026-09-08 07:49:07 UTC  finding F12: RESEARCH: fillResearchPrices writes the fetched quotes back into trending/best/w
- 2026-09-08 07:49:07 UTC  finding F13: RESEARCH: the org.json NULL trap, in the one place the architecture notes warn a
- 2026-09-08 07:49:07 UTC  finding F14: RESEARCH: the same NULL trap in EtfScreener.parse - a Yahoo row with a null long
- 2026-09-08 07:49:07 UTC  finding F15: RESEARCH: ResearchBridge's bundle computes dataAgeMinutes from set.generated onl
- 2026-09-08 07:49:08 UTC  finding F16: RESEARCH: a fund Claude returns with a category but no why survives the import a
- 2026-09-08 07:49:08 UTC  finding F17: RESEARCH: an imported reply with no notes blanks the previous notes; every other
- 2026-09-08 07:49:08 UTC  finding F18: RESEARCH: the FOLLOWING chip's watched/held set is remembered on set.generated a
- 2026-09-08 07:49:08 UTC  finding F19: RESEARCH: EtfScreener.fetch treats a valid 200 carrying zero quotes the same as 
- 2026-09-08 07:49:08 UTC  finding F20: RESEARCH: the ETF screener parse comment claims Yahoo publishes dividendYield as

