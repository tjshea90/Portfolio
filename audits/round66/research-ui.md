# Round 66 audit - Research screen and its words (READ-ONLY)

Scope: ResearchScreen.kt, AdviceScreen.kt, ResearchBridge.kt, ResearchScore.kt,
ResearchModels.kt, the Research section of PortfolioViewModel.kt, plus the strings
those files print (Research.SOURCES / Research.ETF_SOURCES).
Out of scope: ETF scoring arithmetic (audited separately).

Findings are appended as they are confirmed; the ranked list is at the end of the
session. Every claim below was checked against the code that contradicts it.

---

ID: RES-1
SEVERITY: HIGH
WHERE: app/src/main/java/com/tj/portfolio/data/ResearchModels.kt:222 (`score = o.optInt("score", 0)`), with app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:4664 (`loadCachedResearch`) and :489-507 (`carryEtfExplanations`)
BUG: The R1 fix ("a model's conviction is never written into `score`") was applied to the writer only - there is no migration for the research cache written by the previous build, so a Claude-added fund already on disk still deserializes with `score = conviction * 10` and is drawn as an app score, permanently.
FAILURE: A user who imported a Claude answer before this build has `{"symbol":"VTI","why":"...","score":100}` in `Keys.RESEARCH_CACHE` (a settings row that survives an app upgrade; `ResearchSet.toJson` writes `"version": 1` and `fromJson` never reads it, so nothing rejects the old shape). On launch `loadCachedResearch` parses it into `ResearchRow(score = 100, conviction = 0, etf = null)`. In `ResearchCard`, `val fromClaude = r.score <= 0 && r.conviction > 0` (ResearchScreen.kt:585) is FALSE, so the badge reads "SCORE", the circle reads "100", and the screen-reader text is "Score 100 out of 100" (ResearchScreen.kt:586-596) - over a row with no facts grid and no reason lines. `carryEtfExplanations` then re-adds that row on every six-hourly rebuild (`it.etf == null && it.why.isNotBlank()`) and sorts by `score` first, so it sits at row 1 above every fund the app actually measured, for ever: a later import cannot heal it either, because `ResearchBridge.merge` copies `why`/`catalyst`/`conviction` and leaves `score` untouched (ResearchBridge.kt:405-410). This is precisely the state ResearchModels.kt:148-161 says "must never happen, on the list TJ said he is going to buy from".
FIX: In `ResearchRow.fromJson`, do not trust a cached `score` on a row that carries no app arithmetic: for a row parsed with no `"etf"` object, read `score = 0` and `conviction = max(conviction, score/10).coerceIn(0,10)`. (Scope it to the `etfs` array if a Best row with zero reason lines must keep its score.) Bump `"version"` to 2 at the same time so the branch can be retired later.
CONFIDENCE: certain

---

ID: RES-2
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/net/Research.kt:360-363 (`SOURCES`), shown at app/src/main/java/com/tj/portfolio/ui/ResearchScreen.kt:512-524
BUG: The "Where these numbers come from" note on the Trending and Best tabs names four feeds and omits Nasdaq, which is the source of the analyst consensus and the price target that the Best rows print and that 30% of an enriched Best score is blended from.
FAILURE: On the Best tab the section blurb ends "...and analyst consensus" (ResearchScreen.kt:86-87) and a card prints reason lines written by `ResearchScore.withAnalyst` - "Strong Buy consensus - 16 buy / 9 hold / 4 sell across 29 analysts" and "Average price target $214.00 - 12.4% above today" (ResearchScore.kt:307-321). Those come from `https://api.nasdaq.com/api/analyst/<sym>/targetprice` (Research.kt:542-545) and are folded in at `base.score * 0.7 + a * 0.3` (ResearchScore.kt:324). The note directly underneath reads "Yahoo Finance predefined screeners and trending tickers; r/wallstreetbets mention counts via Tradestie and ApeWisdom; headline counts from the app's own market news feeds. Scores are computed on the phone from those numbers." - so the one number on the screen that is an outside party's opinion of what the stock is worth is presented with no provider named, under a heading that promises exactly that, and the closing sentence claims the score comes only from the numbers just listed.
FIX: Add the fifth source to `Research.SOURCES`, e.g. "; analyst consensus and price targets from Nasdaq's public API, for the rows on screen only". No code change.
CONFIDENCE: certain

---

ID: RES-3
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/ResearchScreen.kt:100-102 (blurb) and app/src/main/java/com/tj/portfolio/net/Research.kt:316-318 (ETF_SOURCES), against app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:5103-5106 (`applyResearchAnswer`)
BUG: Round 66's one-fund-per-exposure rule is applied only on the screener path (`Research.buildEtfs` line 296-298); the ETF list's second entrance - funds Claude adds on import - bypasses `EtfExposure.dedupe` entirely, while the tab's blurb states the rule as an unconditional fact about the list.
FAILURE: The bundle the app hands Claude ends with `"etfUniverseGaps": ["VTI","SCHD","AGG","BND","TLT","IWM","VXUS","VYM"]` (ResearchBridge.kt:247-250) and the prompt says "Add the funds my app could not see... If a major, obviously better fund is missing from my list, that is the most useful thing you can hand back" (ResearchBridge.kt:130-132). AGG and BND are the same exposure - `EtfExposure` keys both as "Bonds - US aggregate" (EtfExposure.kt:179-182) - and the app has just asked for both by name. On import, `applyResearchAnswer` runs `merge` then a plain score/conviction sort with no exposure pass, and `carryEtfExplanations` then keeps both for ever. The user is looking at AGG and BND (and VTI beside a screener-found ITOT/SCHB) under a blurb that reads "only the best-scoring fund of each exposure appears - the others are named on its card", and neither card names the other. Nothing anywhere de-duplicates rows that arrive by import.
FIX: Apply the exposure pass on the same filled copy where the leveraged/inverse guard already runs, one line after `dropLeveraged` in `fillPricesNow` (PortfolioViewModel.kt:5205-5214) - the name the matcher needs exists only there, which is why `dropLeveraged` is there. If a Claude-added fund must never be dropped, instead add the rule to both prompts beside the existing "Do NOT add leveraged, inverse or single-stock funds" sentence (ResearchBridge.kt:278-280) and soften the blurb to say the rule applies to the funds the app screened.
CONFIDENCE: certain

---

ID: RES-4
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/data/ResearchModels.kt:164-165
BUG: `ResearchRow.followed` is a dead field whose KDoc claims it drives the FOLLOWING chip; nothing writes it, nothing reads it, and it is not in the JSON codec.
FAILURE: The KDoc reads "True when the user already holds or watches this symbol - shown as a chip." A reader trusting it would set `followed = true` when building a row and see no chip: the chip comes from the `followed` PARAMETER of `ResearchCard` (ResearchScreen.kt:544, 625), which is computed in the screen as `r.symbol in watched` from `vm.watchedSymbols() + vm.heldSymbols()` (ResearchScreen.kt:187-189, 387). `followed` is also absent from `ResearchRow.toJson`/`fromJson`, so it would not survive the cache either. This is the same species of defect as the `grade()` removal note in ResearchScore.kt:336-340 - "a claim about the UI that stopped being true without anybody noticing".
FIX: Delete the `followed` field from `ResearchRow`.
CONFIDENCE: certain

---

ID: RES-5
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:4873-4877 (`resetResearchPaging`, zero callers)
BUG: `resetResearchPaging()` is dead - nothing in `app/src/main` or `app/src/test` calls it - so the per-section page count survives a full rebuild, and the next rebuild silently re-spends the per-row analyst budget on a list the button was never pressed for.
FAILURE: The user presses "Load more" three times on Best; `showMoreResearch` raises `_researchShown["BEST"]` to 40. Thirty minutes later the visibility effect (ResearchScreen.kt:179-181) sees a stale set and rebuilds: `loadResearch` clears `analystDone` (line 4824), replaces `best` with 50 entirely different symbols, and then calls `enrichVisible()` (line 4854). `enrichPass` reads the surviving `shown = 40` and enriches `window = shown + PAGE` = 50 rows (lines 4929-4932), i.e. up to fifty Nasdaq consensus requests fired by a background TTL rebuild with no user action - against the ten the screen's own KDoc calls "TJ's explicit rule... enforced in showMoreResearch" (ResearchScreen.kt:117-120). The same stale count also decides how much of each list is written into the Claude prompt (`visibleResearch`, line 4975-4984).
FIX: Call `resetResearchPaging()` in `loadResearch`'s success branch, next to `analystDone.clear()` at line 4824 (and in `loadEtfs`'s success branch at line 4757 for the ETF page count) - or delete the function if paging is meant to persist, so it stops advertising a reset that does not happen.
CONFIDENCE: certain

---

ID: RES-6
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/ui/ResearchScreen.kt:150, against app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:4556-4562
BUG: The persisted tab index is bounded against `ResearchSet.SECTIONS` but used to index the `Section` enum - two independently declared lists in two files - so the guard added after Round 66 does not actually protect the array access it was written for.
FAILURE: `researchTab()` returns `db.get(RESEARCH_TAB).coerceIn(0, ResearchSet.SECTIONS.lastIndex)`; the screen then evaluates `Section.entries[vm.researchTab()]` in composition. `setResearchTab(s.ordinal)` writes an enum ordinal, `Db.Keys.RESEARCH_TAB`'s KDoc (Db.kt:1635) calls the value "an index into ResearchSet.SECTIONS", and nothing binds the two orders or lengths. The next edit that adds a section name to `SECTIONS` without adding an enum constant (or removes an enum constant without touching `SECTIONS` - the exact shape of the Round 66 deletion) makes `researchTab()` return 3 and `Section.entries[3]` throw IndexOutOfBoundsException while the Watch tab composes, from a value stored in the database, so it repeats on every launch. The comment at PortfolioViewModel.kt:4552 - "Derived from SECTIONS so neither adding nor removing a section can do it again" - is only true if the two lists are edited together, which is what failed twice already.
FIX: Read it defensively at the one use site: `Section.entries.getOrElse(vm.researchTab()) { Section.TRENDING }` in ResearchScreen.kt:150.
CONFIDENCE: certain

---

ID: RES-7
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:4927-4965 (`enrichPass`), with app/src/main/java/com/tj/portfolio/ui/ResearchScreen.kt:448-451 (the un-guarded "Import answer" button)
BUG: `enrichPass` reads the Best list, suspends for a network call, then writes back the whole section from that pre-suspension snapshot, so anything that lands in `_research` during the call is silently discarded - and the import path is not ordered against it the way `loadResearch` is.
FAILURE: `val rows = _research.value.section(name)` (line 4927) -> `head`/`tail` -> `withContext(Dispatchers.IO) { Research.enrichAnalyst(head, ...) }` (line 4937), which is seconds of Nasdaq requests, three at a time -> `_research.value = _research.value.withSection(name, ranked + tail)` (line 4965). "Import answer" carries no `enabled` guard (unlike "Explain with Claude", which is `enabled = busy.isEmpty()` at ResearchScreen.kt:431), so the user can pick Claude's reply file while that pass is in flight: `applyResearchAnswer` writes `cacheResearch(merged)` at line 5116, then the enrich pass's write replaces `best` with the list it read before the import. Every `why` Claude just wrote for a Best row, and every Best row Claude added, is gone - and the pass's own `finally { if (did) cacheResearch(_research.value) }` (line 4915) persists the loss to `Keys.RESEARCH_CACHE` - while the toast the user is reading says "Research updated - 10 explained, 1 added". The same window swallows the `fillResearchPrices` fire-and-forget the import fires at line 5125; the KDoc at lines 5157-5162 identifies exactly this hazard ("a fill landing mid-flight would be overwritten by a list the enrich pass had already read") and orders only the `loadResearch` caller against it.
FIX: Do not write back a stale list: at line 4964-4965 project the enriched rows onto the list as it is at write time - `val fresh = _research.value.section(name); val byEnriched = enriched.associateBy { it.symbol }` then rebuild from `fresh` - so a concurrent import survives. Also give "Import answer" the `enabled = busy.isEmpty()` that "Explain with Claude" already has.
CONFIDENCE: certain

---

ID: RES-8
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/data/ResearchModels.kt:10-11, :267, :288, :293; app/src/main/java/com/tj/portfolio/net/ResearchBridge.kt:368; app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:523, :551, :4817
BUG: Comment debris from the deleted Worst section: seven KDoc/comment sites still count the sections as three stock lists or four sections, and the data model's own header still describes the removed list as if it were shipping.
FAILURE: ResearchModels.kt:10-11 opens the file a maintainer reads first with "Three sections - what the market is TALKING about, what the numbers say is worth BUYING, and what the numbers say is BREAKING" - "BREAKING" was the Worst list, and the third section is now ETFs, which is not a stock list at all. ResearchModels.kt:288 documents `isEmpty` as "True when the three MARKET sections are empty" over `trending.isEmpty() && best.isEmpty()` - two - and :293 and :267 repeat "the other three" for the same pair; PortfolioViewModel.kt:523 and :551 say "the three STOCK lists" over the same two, and :4817 says an empty rebuild would blank "three good lists". ResearchBridge.kt:368 says `catalyst` is shared "so one card layout serves all four sections". Every one of these is a count a future reader would use to decide whether a `when` or a carry-over rule covers everything, which is how the tab index at PortfolioViewModel.kt:4549-4551 went wrong twice.
FIX: Correct the counts (two stock lists, three sections) and rewrite ResearchModels.kt:10-11 as "what the market is TALKING about, what the numbers say is worth BUYING, and the funds worth holding".
CONFIDENCE: certain

