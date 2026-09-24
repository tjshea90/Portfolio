# Full-test audit 2026-09-24 — SCORING / RECOMMENDATION ENGINE (S-)

Auditor: read-only subagent. Nothing was edited, Gradle was not run, no network. Every finding
was checked against the code at HEAD (`b2ef2ae0`). Scope: net/ResearchScore, Recommend, EtfScore,
EtfScreener, EtfExposure, Research, ResearchBridge, RatingRecency, Relevance, MarketRelevance,
Screener, Insider, Form4, ClaudeBridge, SharedAnswer; data/Research/Recommendation/Fundamentals/
FundamentalsJson/Insider/Etf models; the VM paths that build/carry research and recommendations
and import Claude answers. Yesterday's S-1..S-10 were re-read. S-1, S-3 (stock carry), S-4, S-6,
S-8 and S-10 hold. **S-2, S-7 and S-9 are only partly fixed** (see S-2, S-3 and S-1 below).

STATUS: COMPLETE. 0 H, 7 M, 5 L, plus 5 quality items. Summary table at the end.

---

### S-1 [M] The S-9 popup fix can never run. A dated panel with no readable votes still says "no publication dates" and shows the wrong weight
- where: data/RecommendationModels.kt:95-119 (`freshnessNote`), net/Recommend.kt:67-91, compared with net/ResearchScore.kt:1792-1828 (`holding`, S-9 branch).
- what's wrong: `Recommend.build` sets `ratingsDated = voting != null` and `currentRatings = voting?.firms ?: 0`, where `voting = panel?.takeIf { it.hasVotes }`. `RatingRecency.panel` returns null when `firms == 0`, so whenever `ratingsDated` is true, `currentRatings >= 1`. That makes the S-9 branch unreachable:
  ```kotlin
  ratingsDated && currentRatings > 0 -> { ... }          // always taken when ratingsDated
  allRatingsStale > 0 -> ...
  analystCount > 0 && ratingsDated -> "Analyst ratings are dated, but none carries ..."   // dead
  analystCount > 0 -> "Analyst ratings came back with no publication dates, so their age could not be checked. $cut."
  ```
  The case S-9 targeted (panel != null, !panel.hasVotes, consensus has votes) has `ratingsDated = false`, so it falls to the last branch and prints the false "no publication dates" sentence. `$cut` is also wrong there. `analystWeight = voting?.strength ?: undatedTrust(trend, allStale)`, and `allStale` is forced to 0 when a panel exists. The scorer used `minOf(undatedTrust(...), panel.currency)` (ResearchScore.kt:1800-1801), so the two numbers are not the same.
- failure scenario: the only firm within 240 days has a grade that `bucketOf` returns as NONE (a blank Finviz grade, "Not Rated", and so on), dated 200 days ago, and there are no trend snapshots. The scorer counts the consensus at min(0.60, max(0.35, weight(200) = 0.06)) = 35%, and its reason line correctly says "dated ... counted at 35%". The popup directly underneath says "Analyst ratings came back with no publication dates, so their age could not be checked. Counted at 60% of full weight." Both the age statement and the percentage are wrong.
- suggested fix: carry the scorer's branch explicitly, for example with `ratingsDated = panel != null`, `analystWeight = if (voting != null) voting.strength else if (c?.hasVotes == true) minOf(undatedTrust(trend, allStale), panel?.currency ?: 1.0) else 0.0`, and gate the first `freshnessNote` branch on `currentRatings > 0 && effectiveAnalysts > 0` so the S-9 branch can be reached. Better: return the trust actually used from `holding` (for example add `analystTrust` to `Scored` or a side result) so the two can never diverge again. Missing tests: (a) AnalystRecencyTest, a panel of one unbucketable 200-day rating plus a consensus, asserting the `holding` reason does not contain "no publication dates" and the trust is 35% or less; (b) a Recommend-level test that `freshnessNote()` for the same input contains "dated" and "35%". Neither test exists today (grep "none carries" in app/src/test finds nothing).
- confidence: high

### S-2 [M] S-2 is only fixed on the Portfolio tab. On the stock's own screen a ratings-only verdict is never recomputed
- where: ui/DetailScreen.kt:422-424; PortfolioViewModel.kt:4989-5005 (`recProvisional`); MainActivity.kt:505 (the DetailScreen replaces the tab host, so PortfolioScreen is not composed underneath it).
- what's wrong: the S-2 fix marks a verdict scored before the core numbers as provisional and relies on "recomputed when `_fundamentals` changes again". The DetailScreen trigger is keyed on a boolean, not on the data:
  ```kotlin
  LaunchedEffect(symbol, fundamentals != null, price > 0.0) {
      if (fundamentals != null && price > 0.0) vm.loadRecommendation(symbol, price)
  }
  ```
  When the ratings row lands first, `fundamentals != null` becomes true and a ratings-only verdict is computed. When the core row lands seconds later, the key does not change, so `loadRecommendation` is not called again. Only `PortfolioScreen`'s effect (keyed on `fundMap`) re-fires, and it is not composed while a DetailScreen is open.
- failure scenario: exactly S-2's original scenario. Tj opens a stock (the Analysts tab is `rememberSaveable`), `loadRatings` beats `loadFundamentals` (no fresh core row on disk), and the badge and popup show a verdict built from analysts and growth only, with no valuation or relative-performance terms and about 57% confidence, for as long as that screen is open. It corrects itself only after he leaves and the Portfolio tab recomposes. For a watch-only or Research-opened symbol that is not on the Portfolio tab, nothing recomputes it until the screen is reopened.
- suggested fix: key the effect on something that changes when the core row lands, for example `fundamentals?.values?.containsKey("marketCap")` or `fundamentals?.fetched`. Alternatively, have `loadFundamentals` call `loadRecommendation(sym, lastPrice)` itself after a successful core merge when `sym in recProvisional`. Missing test: a VM test that scores a verdict from a ratings-only row, merges a core row, and asserts that the verdict is recomputed with no UI trigger (or a DetailScreen UI test).
- confidence: high

### S-3 [L] S-7 fix incomplete: the stale-core warning is still hidden when the disk core row is old and the refresh fails
- where: PortfolioViewModel.kt:4989-4991 (`coreAt = coreFetchedAt[sym] ?: f.fetched`), :5364-5399 (`loadFundamentals`), Fundamentals.kt:440 (`fetched = maxOf(...)`).
- what's wrong: `coreFetchedAt` is set only when a disk core row is still inside `CORE_TTL_MS`, or after a successful network fetch. With a disk core row older than the TTL and a failed network fetch (the exact S-7 scenario, "core refresh has failed for 3 days"), the old row is merged into `_fundamentals` but `coreFetchedAt` stays unset. `coreAt` then falls back to `f.fetched`, which is the merged maximum and so equals today's ratings fetch. That is the same masking S-7 described. `fundRetry.blocked` then lets the verdict persist as settled for the day.
- failure scenario: Yahoo is in cooldown for 3 days, the ratings row (daily) refreshes from Yahoo or Finviz/Nasdaq, and the core row on disk is from 3 days ago. The popup never shows "The underlying financial data was last refreshed ...".
- suggested fix: record the core row's own `fetched` when it is merged from disk even if it is stale (for example a separate `coreDataAt[sym] = core.fetched` next to the throttle map), and pass that as `coreAt`. Missing test: a VM or pure test for stale disk core + failed network + fresh ratings, asserting `fundamentalsAt` equals the old core stamp.
- confidence: high

### S-4 [M] A Research answer's `asOf` is never read, so an old answer shared again is stamped as written now
- where: net/ResearchBridge.kt:329-395 (`parse`, `section`), :405-444 (`merge`: `whyAt = now`); PortfolioViewModel.kt:7283-7322 (`applyResearchAnswer`: `explained = System.currentTimeMillis()`); PortfolioViewModel.kt:6421-6428 (`importShared` RESEARCH branch). Advice has the same problem: net/ClaudeBridge.kt:386-392 (`generated = System.currentTimeMillis()`, and the ADVICE schema has no date at all).
- what's wrong: the Research schema asks for `"asOf": <today's date>` (ResearchBridge.kt:42), but no code reads it (grep `asOf` finds only the prompt writers and DayTradingBridge). `merge` stamps `whyAt = now` on every row with a paragraph, and `applyResearchAnswer` sets `explained = now`. D-5 fixed exactly this for Day Trading ("with the share flow, yesterday's answer file sits one tap away in the Claude chat"), but the Research path, which uses the same share flow, was not fixed.
- failure scenario: Tj re-shares last week's `portfolio-answer-research.md` from the Claude app, which lists previous chat files. Every Best/Trending/ETF paragraph ("why this is a good buy AT TODAY'S PRICE", "the reason this stock is being talked about TODAY") becomes current. The card shows "Explained just now via Claude app", and the 14-day eviction clock (`WHY_STALE_MS`) restarts, so week-old claims about "today" are shown as current for two more weeks. This defeats Tj's 2026-09-21 request ("Nothing should be recommended on ... stale Claude advice"). The Advice tab reads "Generated just now" over an old review for the same reason.
- suggested fix: parse `asOf` with the same `DayTradingBridge.answerIsCurrent`-style helper. Stamp `whyAt`/`explained` with the answer's own date (NY noon of `asOf`) when it is readable and in the past, so `stillCurrent` ages it correctly. Keep "missing or unreadable = now" for tolerance. Say "This answer is dated X" in `notes`. Add `"asOf"` to the Advice schema and use it for `Advice.generated`. Missing tests: ResearchBridge parse+merge with `asOf` 20 days old leaves `whyAt` about 20 days old and `evictStaleWhy` blanks it; `asOf` today stamps now.
- confidence: high

### S-5 [M] The rebuild carry freezes the app's own relative-date catalyst ("Earnings in 3 days", "Earnings today") as if it were Claude's, for up to 14 days
- where: PortfolioViewModel.kt:892-918 (`carryWhy`), net/ResearchBridge.kt:426 (`catalyst = c.catalyst.ifBlank { row.catalyst }`), net/DayTradingBridge.kt:486 (same pattern), consumer PortfolioViewModel.kt:1053-1055 (`earningsToday = row.catalyst.startsWith(CATALYST_EARNINGS_TODAY)`).
- what's wrong: on import, when Claude leaves `catalyst`/`target` (Best) or `risk` (Day Trading) blank, the merged row keeps the APP's screener string, `Research.catalystFor`, which is relative to the build day ("Earnings in 3 days - Sep 27", "Earnings tomorrow", "Earnings today"). `carryWhy` then decides the catalyst is Claude's purely on `conviction > 0`:
  ```kotlin
  val claude = p.conviction > 0
  catalyst = if (claude && p.catalyst.isNotBlank()) p.catalyst else r.catalyst,
  ```
  From then on, every 30-minute rebuild copies that frozen string over the fresh screener catalyst, from the carried row itself (`whyAt` unchanged), until the paragraph is 14 days old.
- failure scenario (precondition: Claude left `catalyst`+`target` / `risk` blank, which the schema allows): Best: on Mon Sep 21 Claude answers XYZ with a paragraph and conviction 7 and leaves `catalyst` empty, so the row keeps "Earnings in 6 days - Sep 27". On Friday the card still says "Earnings in 6 days", and after Sep 27 it still announces an upcoming print that has already happened. Day Trading: on Monday a pick with blank `risk` keeps "Earnings tomorrow". On Tuesday, the actual earnings day, the fresh row's "Earnings today" is overwritten with "Earnings tomorrow". `mergeDayTradingTech` passes `earningsToday = false`, so the plan loses its "Earnings are due today - cancel any unfilled buy order before the close" note (ResearchScore.kt:1086) on the one day that note is for. On Wednesday a stale "Earnings today" puts the warning on a day that has no earnings. **Common path, no precondition (flagged for the day-trading auditor):** when Claude DOES give a `risk` line, `DayTradingBridge.merge` replaces "Earnings today" with Claude's sentence. `startsWith(CATALYST_EARNINGS_TODAY)` then fails, and the same warning is lost for every Claude-answered row the app plans itself: rows whose Claude levels failed `levelsUsable`, and every carried row after the session changes, because `claudePlanStands` requires the same session.
- suggested fix: in `ResearchBridge.merge`/`DayTradingBridge.merge`, keep Claude's line in its own field (for example `claudeCatalyst`), or record whether `catalyst` came from Claude. `carryWhy` should carry only Claude's own text, never a string that `catalystFor` produced. Derive `earningsToday` from a structured earnings timestamp on the row, not from the text. Missing test (ResearchCarryTest): old best row {why, whyAt = now-2d, conviction 7, catalyst = "Earnings in 6 days - Sep 27"} plus fresh row {catalyst = "Earnings in 4 days - Sep 27"}; expect the fresh catalyst.
- confidence: high

### S-6 [M] A stale Claude-added fund becomes a permanent row after a cold launch
- where: PortfolioViewModel.kt:620-645 (`evictStaleWhy`), :647-706 (`carryEtfExplanations`), :6489 (called on cold launch), :6822 (next ETF rebuild).
- what's wrong: `evictStaleWhy` blanks only `why`/`whyAt` and keeps `catalyst` (the ETF `category`) and `conviction`. `carryEtfExplanations` then keeps any Claude-added fund whose `why` is blank if it has a catalyst, with no age check:
  ```kotlin
  if (it.why.isNotBlank()) stillCurrent(it.why, it.whyAt, now) else it.catalyst.isNotBlank()
  ```
  So a fund Claude added with a paragraph and a category (the normal schema shape) is dropped after 14 days only if a rebuild sees it with its stale `why` still attached. Once a cold launch has run `evictStaleWhy`, it becomes a "category-only" row and is kept on every later rebuild, indefinitely.
- failure scenario: Tj imports an ETF answer that adds VTI ("broad US equity index", conviction 9), then does not open the app for three weeks. On launch VTI's paragraph is blanked, and the ETF rebuild carries VTI forever with the "CLAUDE 9/10" badge. That is a three-week-old model opinion on the list Tj said he will buy from, which his 2026-09-21 request ("If Claude advice hasn't been used in a while, the app should remove it from cache") was meant to prevent. The existing test `a Claude-added fund is dropped outright once its content goes stale` passes only because its fixture has no category.
- suggested fix: give every Claude-touched row a content stamp (stamp `whyAt`, or a new `claudeAt`, whenever `merge` writes why OR catalyst OR conviction), and age category-only rows by it. Alternatively, in `evictStaleWhy`, drop a Claude-added row (`score <= 0 && etf == null`) outright, and clear `conviction` and the Claude catalyst on scored rows when their `why` is evicted. Missing test: `evictStaleWhy` then `carryEtfExplanations` on {VTI, why, whyAt = 20d ago, catalyst = "broad US equity", conviction 9}; expect VTI to be gone.
- confidence: high

### S-7 [M] Day Trading paragraphs ("worth trading TODAY") are carried onto later sessions' rows for 14 days
- where: PortfolioViewModel.kt:760-781 (`carry`, `dayTrading = true`), :892-918 (`carryWhy`); `evictStaleWhy` (14-day rule for `dayTrading` too). This overlaps the day-trading auditor's scope and is included because it is carry logic.
- what's wrong: for Day Trading, `carry` gates Claude-ADDED rows and Claude's PLAN on `sameTradingDay`, but the paragraph, the risk line (the catalyst) and the conviction of a surviving symbol are carried by `stillCurrent` alone, the same 14-day window as a long-term Best thesis. The DT schema defines `why` as "the SPECIFIC reason this stock is worth trading today" and `risk` as "what could invalidate this setup today". The existing test `a same-day Claude plan survives a rebuild, a previous day's does not` asserts that the plan is dropped for a week-old answer, but it leaves the week-old "In play." paragraph on the row.
- failure scenario: GME is on Monday's Claude list ("squeezing on the halt and reopen, buy the break over 25.40"). It is on Thursday's screener list again. Thursday's card carries Monday's paragraph, risk line and "CLAUDE"-sourced conviction, which read as today's reasoning.
- suggested fix: in `carryWhy`, when `dayTrading` is true, carry why/catalyst/conviction only when `sameTradingDay(p.whyAt, now)` (the same gate as the plan). `evictStaleWhy` should apply `planStillForSession` to `dayTrading` paragraphs. Missing test: extend the existing test to assert `stale.single().why == ""`.
- confidence: high

### S-8 [M] ETF de-duplication merges different products under "Same exposure", and silently drops any past the third
- where: net/EtfExposure.kt:137-201 (`keyOf`), :240-257 (`dedupe`, `if (also.size < 3)`); the claim in Research.kt:394-403 (`ETF_SOURCES`: "only the best-scoring one takes a place - the rest are named on its card").
- what's wrong: the name patterns are substring tests with no exclusion for the many index-plus-strategy products whose names contain the index. I checked these with a Python port of `keyOf` (same normalisation and order):
  - "Invesco S&P 500 Top 50 ETF" (XLG), "NEOS S&P 500 High Income ETF" (SPYI, option income), "Invesco S&P 500 High Beta ETF", "Invesco S&P 500 Revenue ETF", "Invesco S&P 500 GARP ETF" and "SPDR S&P 500 Fossil Fuel Reserves Free ETF" all key to **US large cap - S&P 500**, the same group as VOO.
  - "NEOS Nasdaq-100 High Income ETF" keys to **Nasdaq-100**, the same group as QQQ.
  - "iShares MSCI Emerging Markets ex China ETF" keys to **Emerging markets**, the same group as IEMG/EEM.
  - "iShares California Muni Bond", "VanEck High Yield Muni", "iShares Short-Term National Muni Bond" and "JPMorgan Ultra-Short Municipal Income" all key to **Bonds - municipal**, the same group as MUB. That is a state-tax product, a junk-muni fund and a cash substitute treated as one decision. The Treasury/corporate ladders separate maturity; the muni and high-yield branches do not ("SPDR Bloomberg Short Term High Yield Bond" is grouped with HYG).
  - "ProShares Bitcoin Strategy ETF" (futures) keys to **Bitcoin** with the spot trusts.

  `dedupe` then keeps one survivor per group and names at most 3 others. Members from the 5th onward disappear with no mention anywhere, although the sources note says "the rest are named on its card". The S&P 500 group alone can now hold VOO, IVV, SPLG, SPY plus the six above.
- failure scenario: the ETF list ranks VOO first and deletes XLG, SPYI, SPHB and others. XLG/SPYI are not named at all, because the survivor already names SPLG, IVV and SPY. So the one option-income fund in Tj's universe vanishes and nothing says so. In the muni group, whichever of MUB/HYD/CMF/JMST scores highest is presented as "the same exposure" as a high-yield or state-specific fund, a claim that is false for someone about to buy.
- suggested fix: add strategy words to `tilted` (" top 50 ", " high income ", " premium income ", " high beta ", " revenue ", " garp ", " fossil fuel ", " ex china ", " option ", " strategy ", " buy write "); key munis by state and quality (null when a state name, " high yield " or a maturity word is present); give high-yield the maturity ladder; leave " strategy " / futures bitcoin funds ungrouped. Either name every dropped member (the VM's S-10 merge already builds an unbounded list, EtfExposure's is capped at 3, which is inconsistent) or change the sources text. Missing tests: EtfExposureTest asserting `keyOf` is null or distinct for each name above; `dedupe` with 5 group members names all 4 losers (or asserts the stated cap).
- confidence: high (keyOf behaviour reproduced); medium on which of these funds are in Yahoo's 523/377-fund screens today (XLG and SPYI very likely).

### S-9 [L] Ratings from two providers can vote twice for one firm when the names are spelled differently
- where: net/FundamentalsFeed.kt:111-113 (`core()` merges the full Finviz result, INCLUDING `parseFinvizRatings`, when Yahoo+Nasdaq are thin) and :123-126 (`ratings()` = Yahoo); PortfolioViewModel.kt:4854-4863 (`mergeFundamentals` unions them); Fundamentals.kt:425-427 (`distinctBy { it.id }`, id = raw firm | UTC day | grade); RatingRecency.kt:223 (panel key = `firm.lowercase().trim()`).
- what's wrong: S-L9 kept Finviz's VALUES out of the ratings path, but the reverse path is open. A core row filled from Finviz carries Finviz's rating rows, the daily ratings row carries Yahoo's, and `_fundamentals` holds the union. The panel's one-vote-per-firm rule only works when both providers spell the firm identically.
- failure scenario: in a Yahoo-cooldown window the core row falls through to Finviz. The panel then contains, for example, "B of A Securities" (Yahoo) and "BofA Securities" (Finviz), or "Goldman Sachs" and "Goldman", each voting separately. That inflates `effectiveAnalysts` (so breadth reaches full weight on thin coverage), skews the lean toward the duplicated firms, and can push `targetFirms` over `MIN_TARGET_FIRMS`, switching which target is used. The Analysts tab lists the same note twice.
- suggested fix: in `core()`, merge Finviz as `valuesOnly` (drop `ratings`/`consensus`/`trend`; the ratings path already has its own Finviz fallback), or normalise the firm key (strip non-alphanumerics and words like securities/capital/markets/group). Missing test: panel over [Yahoo "B of A Securities" Buy d-5, Finviz "BofA Securities" Buy d-5] has `firms == 1`.
- confidence: medium (the mechanism is certain; the exact spelling differences between the two feeds were not verifiable offline)

### S-10 [L] Trending still says "Price up X% today" when the market is closed
- where: net/ResearchScore.kt:299-301 (`trending`).
- what's wrong: `"Price ${up/down} ${pct} today"` is unconditional. Round 68 fixed the same wording in `dayTrading` with `sessionWord` after Tj reported "the market is currently closed and yet the stocks claim to be 'already up today'". Trending was not given the fix.
- failure scenario: on Saturday the Trending card for a Friday gainer reads "Price up 9.00% today".
- suggested fix: pass the same `sessionWord` (from `MarketClock.phase()` in `buildTrending`) into `trending()`. Test: `trending(..., sessionWord = "in the last session")` produces no "today" in the price line.
- confidence: high

### S-11 [L] Rows Claude adds to Trending/Best are placed after the 50-row buffer, and Trending additions show "SCORE 0"
- where: net/ResearchBridge.kt:441-443 (`merged + added`), PortfolioViewModel.kt:7293-7294 (stock lists not re-sorted) and :7172-7176 (`visibleResearch` = first `shown` rows); ui/ResearchScreen.kt:810-812 (`fromClaude = r.score <= 0 && r.conviction > 0`).
- what's wrong: added stock rows go to index 50 or later, so the screen (10 per page) shows them only after four or five "Load more" taps. The next prompt (`visibleResearch`) never sends them back to Claude either. The Trending schema has no `conviction`, so an added Trending row has score 0 and conviction 0. It is not `fromClaude`, and the card draws "SCORE 0" (screen reader: "Score 0 out of 100"), a number the app never computed. That is the R1 "whose number is this" problem again.
- failure scenario: the toast says "Research updated - 10 explained, 2 added" and neither added row is visible. One of them shows "SCORE 0" when he scrolls to it.
- suggested fix: treat `score <= 0 && reasons.isEmpty()` as Claude-sourced for the badge (show "CLAUDE" with no number when conviction is 0). Place added rows directly after the visible page, or say in the toast where they are. Test: ResearchBridge.merge + badge predicate for a trending row with no conviction.
- confidence: high

### S-12 [L] An insider sale's headline price is diluted by lines with no stated price
- where: data/InsiderModels.kt:98-114 (`headlineTrade`), net/Form4.kt:189 (`price = ... ?: 0.0`).
- what's wrong: same-code lines are merged at `paid / shares`, where `shares` includes lines whose price parsed to 0. Form4.kt's own note says a price given only as a footnote (`<transactionPricePerShare><footnoteId .../></transactionPricePerShare>`) is a real shape.
- failure scenario: two S lines, 1,000 at $50 and 1,000 with a footnoted price. The headline becomes "sold 2,000 shares — $50K ... at $25.00", when the real figures are about $100K at about $50.
- suggested fix: average over priced lines only (`sumOf { shares*price } / sumOf { if (price > 0) shares else 0.0 }`) and compute value from that average times total shares. Missing test in InsiderTest.
- confidence: medium (the arithmetic is certain; how often S/P lines carry footnote-only prices in live data is unmeasured)

---

## Code / UI quality (S-Q)

### S-Q1 The S-5 fix has no symmetry test, and it leaves a half-point asymmetry
`holding` now uses `roundToInt()` (half-up). s = 62.5 rounds to 63, which is BUY, and s = 37.5 rounds to 38, which is HOLD. Round-number inputs can land on exact halves (growth 10% gives 7.5, PEG 1.0 gives 5.0). The suggested test from yesterday (50 +/- 12.5 both HOLD) was not added (RecommendationScoreTest only tests `verdictFor` on ints). Either compare the unrounded double in the verdict, or add the test and accept half-up.

### S-Q2 `freshnessNote` and `Recommend.build` duplicate the scorer's branch logic
Both S-1 and yesterday's S-9 come from the popup re-deriving which analyst branch the scorer took. Returning the used trust and branch from `holding` (for example a small `AnalystBasis` enum plus the weight) would remove the whole class.

### S-Q3 `oneFundPerExposure` (VM) vs `EtfExposure.dedupe`
The VM path names every merged fund, while the screener path caps at 3. The "this one scored highest of them" wording is also used when the survivor is an unscored Claude-added fund (score 0 vs score 0), where nothing was scored. Pick one rule, and word the unscored case differently.

### S-Q4 The Trending "mentions" scale mixes two units
`Trending.activity = if (mentions > 0) mentions else comments`, and `maxMentions = social.maxOf { activity }` (Research.kt:517). A Tradestie-only ticker contributes its COMMENT count to the normaliser that ApeWisdom MENTION counts are ramped against, and its reason line calls comments "mentions". Low impact, but the two should be normalised separately or the Tradestie-only rows marked.

### S-Q5 `EtfRow.ageYears` reads the clock
EtfScore's header says it is "pure ... no clock", but `EtfRow.ageYears` calls `System.currentTimeMillis()`, so the record-length term cannot be pinned in a test without real time. Pass `now` in, the way `ResearchScore.HoldingInput` does.

---

## Checked and fine

- Yesterday's S-1 (Relevance phrase on word boundaries, needs a 3+ letter word): fixed in both the prepared and single-shot paths (`squashed` pads, `Subject.of` pads).
- S-3 stock carry: added rows and Claude's catalyst/conviction are carried (but see S-5 and S-7 for what else rides along). S-4 buckets: "Sector/Market/Peer Weight" to HOLD, "Top Pick" to BUY, and SELL is checked before BUY before the weight words. S-6 `withAnalyst` sell-share term. S-8 trend wording. S-10 single "Same exposure" line.
- `holding` NaN safety, the PEG and forward-P/E label thresholds, the relative-performance "both or neither" rule, the target term's `min(currency, trust)`, and `RatingRecency.weight` continuity and cutoff. `allStaleFirms` mirrors `panel`. Finviz units match Yahoo's (Debt/Eq x100, `pctFrac` fractions).
- `Research.enrichAnalyst` skips unscored rows. `applyAnalystEnrichment` takes only the analyst fields. The `generatedAtStart` guard and the `enrichJob` cancel are in place.
- `EtfScore.best`: return normalisation (5y/3y only, YTD added and capped), the -1 fee sentinel is respected in merge, fromJson and toJson, and the leveraged/inverse name test handles short-duration bond names. `EtfScreener.fetchAllWith` null vs empty.
- `ClaudeBridge.findObject` ranks the last block on ties and drops template echoes. `SharedAnswer.classify` checks the prompt marker first, then DT, then Research.
- `ResearchBridge.merge` de-duplicates incoming rows and stamps `whyAt` only for a fresh `why`. `ResearchRow.fromJson` repairs model scores only on the fund list, and `dropPreTriggerPlan` drops pre-trigger plans.

## Ideas — need Tj's approval, do NOT implement
- Show on each Best/Trending card when its Claude paragraph was written ("Claude, 3 days ago"), as the Day Trading card effectively does with session wording. It makes S-4/S-6-type staleness visible even when the stamps are right.
- A "stale analyst coverage" chip on the Portfolio-tab BUY/HOLD/SELL badge when `analystDiscounted` is true, so a discounted verdict is visible without opening the popup (similar to Morningstar's "rating last updated" line).
- An "Other ways to hold this exposure" expander under a de-duplicated ETF card listing every grouped fund with its fee and 5y return, instead of a three-ticker sentence. This also resolves the S-8 cap.

## Test coverage gaps (risky logic with no test today)
- `Recommendation.freshnessNote()`: no test for any branch other than through UI. The S-9 branch is unreachable (S-1). Needed: one test per branch, built through `Recommend.build`.
- `Recommend.build` `analystWeight` equals the trust `holding` actually applied, for all four analyst paths (dated votes / dated no-votes / all-stale / undated) (S-1, S-Q2).
- DetailScreen or VM: a ratings-first then core-second arrival recomputes the verdict (S-2).
- Stale disk core + failed network + fresh ratings gives `fundamentalsAt` = the old core stamp (S-3).
- `ResearchBridge` with an old `asOf` (S-4). `carryWhy` with a screener-derived catalyst and conviction > 0 (S-5). `evictStaleWhy` then `carryEtfExplanations` on a categorised Claude-added fund (S-6). A week-old Day Trading paragraph evicted on rebuild (S-7).
- `EtfExposure.keyOf` negative cases for strategy/sub-index names and munis; the `dedupe` cap (S-8).
- A panel with Yahoo and Finviz spellings of the same firm (S-9). `trending()` session wording (S-10). `holding` symmetry at +/-12.5 (S-Q1). `headlineTrade` with a zero-price line (S-12).

## Summary

| Severity | Count | IDs |
|---|---|---|
| H | 0 | - |
| M | 7 | S-1 (popup freshness note dead branch / wrong weight), S-2 (DetailScreen never recomputes provisional verdict), S-4 (research/advice `asOf` ignored), S-5 (screener catalyst frozen as Claude's; DT earnings-today warning lost), S-6 (stale Claude-added fund becomes permanent), S-7 (DT paragraphs carried across sessions), S-8 (ETF over-grouping + silent drop past 3) |
| L | 5 | S-3 (S-7 fix incomplete), S-9 (Yahoo+Finviz firm double-vote), S-10 (Trending "today" when closed), S-11 (Claude-added rows buried / "SCORE 0"), S-12 (insider price diluted by unpriced lines) |
| Quality | 5 | S-Q1..S-Q5 |

Yesterday's fixes: S-2, S-7 and S-9 are incomplete (reported here as S-2, S-3 and S-1). All others hold.

## END OF REPORT (complete)
