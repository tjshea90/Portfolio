# Full-test audit 2026-09-23: scoring / recommendation engine (S-*)

Research only. Nothing was edited and gradle was not run. Every finding below comes from
reading the code at HEAD. Yesterday's S-H1..S-L9 fixes were re-read and hold, except where a
finding below says otherwise.

---

### S-1 [H] Company-name phrase match is a raw substring, so "S&P Global" and "AT&T" match almost every headline
- where: app/src/main/java/com/tj/portfolio/net/Relevance.kt, `Subject.of` (phrase build) and `matches` (phrase test). Used by `Research.buildTrending` (news counts), `Research.toRow` (Best headline) and `matchHolding` (news tagging).
- what's wrong:
  ```kotlin
  val phrase = if (core.size >= 2) core.joinToString(" ") { it.lowercase() } else null
  ...
  if (s.phrase != null) {
      if ((squashedHay ?: squash(hay)).contains(s.phrase)) return true
  }
  ```
  `coreWords("S&P Global Inc.")` removes "global" and "inc" (both in LEGAL_SUFFIXES), which leaves `["S","P"]` and the phrase `"s p"`. `"AT&T Inc."` becomes `"at t"`. The phrase test is `String.contains` on the squashed text, with no word boundary at either end, so `"s p"` matches "stock**s p**lunge" and "post**s p**rofit", and `"at t"` matches "**at t**he", "th**at t**ops". I checked this against a Python copy of `squash`/`coreWords`: "Stocks plunge as yields jump" matches S&P Global, "Fed holds rates at the June meeting" matches AT&T, and "Nvidia posts profit that tops estimates" matches both.
- failure scenario: T (AT&T is often in `most_actives`) or SPGI is in the screener universe. In `buildTrending`, `newsHits[T]` then collects a large share of the ~500 market headlines. T gets onto Trending with "N news stories today" and the full 25-point news term, plus an unrelated "top" headline. `maxNews` becomes that inflated count, and since every other stock's news term is `ramp(newsCount, 0, maxNews, 25)`, real news-driven names drop to almost 0 on that term. The whole Trending ranking is distorted, and so is the Day Trading ranking, which uses the same `maxNews` from `trending`. Knock-on outside scoring: with T held, `matchHolding` tags ordinary market headlines as AT&T news.
- suggested fix: test the phrase on token boundaries, for example `(" " + squashed + " ").contains(" " + phrase + " ")`. Also require at least one core word of length 3 or more before a phrase is built at all (all-short-word names fall back to the symbol/lead rules). Test: add `Triple("SPGI","S&P Global Inc.","Stocks plunge as yields jump")` and `Triple("T","AT&T Inc.","Fed holds rates at the June meeting")` expecting `false`. Keep "Five Below beats" and "Shares of (T) climbed" as `true`.

### S-2 [M] BUY/HOLD/SELL can be frozen for the whole day from a ratings-only Fundamentals (no valuation, no relative performance)
- where: app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:4773-4840 (`loadRecommendation`, `settledForToday`); DetailScreen.kt:422-424; FundamentalsFeed.kt:92,123-133 (`RATINGS_MODULES = "upgradeDowngradeHistory,financialData,recommendationTrend"`).
- what's wrong: the ratings fetch runs `parseYahoo` over `financialData`, so it produces a non-empty `Fundamentals` (consensus, ratings and a few values such as revenue/earnings growth and debt/equity) with no `peForward`, `pegRatio`, `change52Week`/`sp500Change52Week`, `priceToBook` or `shortPercentOfFloat`. `mergeFundamentals` puts it into `_fundamentals`, so `fundMap[symbol] != null` becomes true and `loadRecommendation` fires:
  ```kotlin
  val f = _fundamentals.value[sym] ?: return@launch
  val fresh = Recommend.build(sym, price, f) ?: return@launch   // f.isEmpty is false
  ...
  private fun settledForToday(...) = r.ratingsDated || recRatingsTried[sym] == today
  ```
  The dated ratings are present, so `ratingsDated = true` and the verdict is final for the day. Nothing recomputes it when the core numbers arrive a moment later.
- failure scenario: DetailScreen reopens on the Analysts tab (the tab is `rememberSaveable` since U-M1), or Tj taps Analysts quickly, for a symbol with no core row on disk. Alternatively the core chain fails (Yahoo 429, Nasdaq/Finviz thin) while the ratings call succeeds. `loadRatings` lands first, and the verdict is scored from analysts + target + growth only. Valuation (up to +/-20) and relative performance (+/-15) are missing, confidence reads about 57%, and that verdict and badge stay until tomorrow even though full fundamentals arrive seconds later.
- suggested fix: do not treat a verdict as settled unless it was built with core data. For example, add `coreBacked: Boolean` to `Recommendation` (true when `coreFetchedAt[sym] != null` or the Fundamentals carries a core-only key such as `marketCap`), and let `settledForToday` require it, with the same one-upgrade-pass rule used for ratings. Alternatively, skip `Recommend.build` until core has landed or has failed for today. Test: VM-level or pure helper. Given a Fundamentals with only consensus+ratings+financialData keys, `settledForToday` is false. After merging core, the recompute changes `confidence` and includes the valuation line.

### S-3 [M] A 30-minute rebuild drops every stock row Claude added, and Claude's catalyst/risk/conviction on surviving rows
- where: PortfolioViewModel.kt:674-724 (`carryExplanations` / `carry`) and :822-834 (`carryWhy`); compare `carryEtfExplanations` :612-660.
- what's wrong:
  ```kotlin
  fun carry(list, from) { val prior = from.associateBy { it.symbol }
      return list.map { r -> carryWhy(r, prior[r.symbol], now) } }
  ...
  return r.copy(why = p.why, whyAt = p.whyAt)
  ```
  Only rows in `fresh` survive, and only `why`/`whyAt` carry over. `ResearchBridge.merge` also writes `catalyst` (Claude's "catalyst - target - risk" line) and `conviction`, and it appends rows Claude added. The ETF list carries added rows forward on purpose, with a comment explaining why ("A fund that is not in Yahoo's screens can never come back in a fresh pass"). The stock lists get neither treatment, and nothing documents that as a decision.
- failure scenario: Tj imports a Claude answer. The toast says "Research updated - 10 explained, 2 added". Within 30 minutes the TTL rebuild runs and both added Best/Trending rows (and any Day Trading additions) disappear. Every surviving explained row keeps Claude's paragraph but loses its risk/target line: the catalyst falls back to the screener's "Earnings in N days", so the paragraph can refer to a risk the card no longer shows. This is the "nothing already paid for is silently lost" category. The paragraph cost an API call or a file round trip.
- suggested fix: in `carryWhy`, also carry Claude's `catalyst` and `conviction` while `stillCurrent` (or carry whenever Claude's catalyst differs from the app's own). Append added rows (`p` not in fresh, `score <= 0`, with a current `why` or a catalyst) below the scored rows, as `carryEtfExplanations` does. If dropping added stock rows is actually intended, write that down and change the toast. Test (ResearchCarryTest): old best = [XYZ(why, catalyst="risk: dilution", conviction 7), ADD(score 0, why)], fresh best = [XYZ]. Expect XYZ to keep its catalyst and conviction, and ADD to still be present, last.

### S-4 [M] Common neutral grades ("Sector Weight", "Market Weight") and "Top Pick" are unbucketable, so the dated panel exaggerates its lean
- where: app/src/main/java/com/tj/portfolio/data/Fundamentals.kt, `AnalystRating.bucketOf`; used by `RatingRecency.panel`.
- what's wrong: HOLD matches only `hold|neutral|equal|in-line|in line|...perform|mixed`, and BUY does not include "pick":
  ```kotlin
  if (g.contains("hold") || g.contains("neutral") || g.contains("equal") || ... || g.contains("perform") || g.contains("mixed")) return HOLD
  return NONE
  ```
  KeyBanc's standard neutral is "Sector Weight" and Wells Fargo has used "Market Weight". Both contain neither "equal" nor "over"/"under", so they return NONE. "Top Pick" also returns NONE. `panel` then keeps the firm in `firms` but gives it no vote.
- failure scenario: the panel is 2 Buy plus KeyBanc "Sector Weight", all fresh. Lean is (2-0)/2 = 1.0, reading "Strong buy consensus" and scoring +30 x strength. The true lean is 0.67, "Buy", about +20. The verdict can cross the BUY line (63) on this alone. Second case: when the only surviving grades are unbucketable, `panel.hasVotes` is false and the scorer falls back to the undated consensus with the note "Analyst ratings have no publication dates from the feed", which is false because the dates exist (see S-9).
- suggested fix: add `"sector weight"`, `"market weight"`, `"peer weight"` to HOLD, placed after the over/underweight checks (which are already earlier), and `"top pick"` to BUY. Test: extend FundamentalsTest `every common grade wording lands in the right bucket` with "Sector Weight"/"Market Weight" -> HOLD and "Top Pick" -> BUY.

### S-5 [L] The verdict band is asymmetric: `toInt()` truncation makes SELL 1 point easier to reach than BUY
- where: ResearchScore.kt:1960 (`Scored(s.coerceIn(0.0, 100.0).toInt(), ...)`) with `verdictFor` :2030-2034 (`>= 63` BUY, `<= 37` SELL).
- what's wrong: `holding` is documented as centred on 50 with a symmetric dead band. Truncation toward zero means s = 37.9 (50 - 12.1) becomes 37 -> SELL, while s = 62.9 (50 + 12.9) becomes 62 -> HOLD. In practice SELL fires below 38.0 and BUY needs 63.0 or more: -12 against +13.
- failure scenario: two mirror-image stocks, one +12.5 and one -12.5 from neutral, get HOLD and SELL respectively.
- suggested fix: `s.coerceIn(0.0, 100.0).roundToInt()` in `holding` (or compare the unrounded double in `verdictFor`). Test: RecommendationScoreTest, where inputs giving s = 50 +/- 12.5 get the same verdict class (both HOLD) and 50 +/- 13 give BUY/SELL.

### S-6 [L] Research-list analyst blend scores an all-HOLD consensus exactly like an all-SELL one
- where: ResearchScore.kt:1587-1589 (`withAnalyst`).
- what's wrong: `a = 20.0 + share.coerceIn(0.0, 1.0) * 60.0` with `share = c.buyShare` (buys / total). Sell votes never enter the arithmetic. 0/10/0 and 0/0/10 both give a = 20. Meanwhile `Consensus2.label()` prints "Hold" for one and "Sell" for the other, and `holding` uses (buy - sell)/votes, treating hold as neutral.
- failure scenario: in Best, a stock with 10 holds and one with 10 sells get the identical -9 blended adjustment ((20-50) x 0.3), and a 5-buy/0-hold/5-sell name ties with 5-buy/5-hold/0-sell. The reason line beside it says "Sell consensus", which the arithmetic does not distinguish.
- suggested fix: base it on the same net lean `holding` uses, for example `a = 50 + ((buy - sell)/total) * 30`, keeping 20..80. Or, to keep the current curve for buy-heavy panels, subtract a sell-share term: `a = 20 + buyShare*60 - sellShare*10`. Test: `withAnalyst(base, Consensus2(0,10,0), p).score > withAnalyst(base, Consensus2(0,0,10), p).score`.

### S-7 [L] `Recommendation.fundamentalsAt` reports the newer of the core and ratings fetches, which hides the stale-core warning it exists for
- where: Fundamentals.kt:434 (`fetched = maxOf(base.fetched, fill.fetched)`); Recommend.kt:90; RecommendationDialog.kt:262-270.
- what's wrong: `_fundamentals[sym]` merges the core row and the ratings row, so `fetched` is the later of the two. `ensureRatingsForRecommendation` refreshes ratings at least daily for every held symbol, so the merged `fetched` is nearly always today, even when P/E, PEG and the 52-week figures come from a core row days old because its refresh keeps failing. That is exactly the case the field's KDoc says the dialog must flag ("recomputed just now, from data that is days old").
- failure scenario: core refresh has failed for 3 days (Yahoo cooldown), and today's ratings fetch succeeds. The dialog does not show "numbers last fetched <date>", and the verdict reads as fully fresh.
- suggested fix: keep the core fetch time separately, for example the VM's `coreFetchedAt[sym]` or a `coreFetched` field kept through `merge` from whichever side has core values, and pass that as `fundamentalsAt`. Test: merge core(fetched = 3 days ago) with ratings(fetched = now), build the Recommendation, and assert `fundamentalsAt` is the 3-days-ago stamp.

### S-8 [L] Best's "Trend is mixed - above one moving average, below the other" can be false
- where: ResearchScore.kt, `best` trend block (about lines 138-148).
- what's wrong:
  ```kotlin
  if (r.price > r.fiftyDayAvg) t += 10.0
  if (r.fiftyDayAvg > r.twoHundredDayAvg) t += 10.0
  ...
  else if (t > 0) why.add("Trend is mixed - above one moving average, below the other")
  ```
  The second test compares the two averages with each other, not the price with the 200-day.
- failure scenario: price 90, 50d 100, 200d 95. The price is below both averages, but the 50d > 200d test earns 10 and the card says "above one moving average". The mirror case: price 110, 50d 100, 200d 105 is above both and gets the same "below the other" line. This is the kind of reason line the arithmetic does not support that the file header warns against.
- suggested fix: word it from what was tested, for example "Above its 50-day, but the 50-day is below the 200-day" or "Below its 50-day, though the 50-day is above the 200-day". Test: the two inputs above assert the reason text.

### S-9 [L] A dated panel with no bucketable votes falls back to the undated consensus while saying "no publication dates"
- where: ResearchScore.kt:1755-1793 (`holding`), RatingRecency.kt `undatedNote`, RecommendationModels.kt `freshnessNote` (the `analystCount > 0` branch).
- what's wrong: when `panel != null && !panel.hasVotes` (firms dated, grades unbucketable; much more common because of S-4), the `else if (c != null && c.hasVotes)` branch runs with `allStale = 0`. It scores at `undatedTrust` (up to 90%) and prints "Analyst ratings have no publication dates from the feed ...". The popup then says "Analyst ratings came back with no publication dates, so their age could not be checked". Both statements are false: the ages are known, and they may be months old. The panel's `currency` is ignored for the vote term.
- failure scenario: the only current firms are KeyBanc "Sector Weight" (200 days) and one Finviz target-only row. The consensus counts at 90% (if `-1m` differs) with a "no dates" note.
- suggested fix: in that branch, cap trust with the panel: `trust = min(undatedTrust(...), panel?.currency ?: 1.0)`, as the target term already does. Pass a flag to `undatedNote` so it says "ratings are dated but none carries a buy/hold/sell grade we can read". Test: AnalystRecencyTest with a panel of one unbucketable 200-day rating plus a consensus. Assert trust is 0.35 or less and that the note does not contain "no publication dates".

### S-10 [L] An imported fund joining a screener group gives the survivor a second, incomplete "Same exposure as" line
- where: PortfolioViewModel.kt:7465-7488 (`oneFundPerExposure` in `fillPricesNow`); Research.kt `toEtfRow`.
- what's wrong: the screener survivor already starts with "Same exposure as IVV, SPLG - this one scored highest of them". When Claude adds VOO (same key, "US large cap - S&P 500"), the VM dedupe prepends another line, "Same exposure as VOO - this one scored highest of them". `.distinct()` does not merge them. The card now has two such lines, and the first, which is the one shown, names only VOO.
- failure scenario: import an ETF answer that names VOO while SPY is on the list: the SPY card opens with "Same exposure as VOO ..." and IVV/SPLG are pushed to the second line.
- suggested fix: when prepending, remove an existing "Same exposure as ..." reason and rebuild one line from the union of symbols (keep up to 3). Test: rows = [SPY(reasons = ["Same exposure as IVV - ...", ...]), VOO(name = "Vanguard S&P 500 ETF", score 0)]. After `oneFundPerExposure`, SPY has exactly one "Same exposure as" line and it names both IVV and VOO.

---

## Checked and fine
- `Recommend.build`: the target shown and the votes reported follow the same branch the score came from (weighted target only when there are 3 or more dated firms; `voting` only when `hasVotes`). The S-H1 fix is correct: `allStaleFirms` mirrors `panel`'s latest-per-firm and cutoff logic, `undatedTrust(trend, allStale)` gives 0.35 or 0, the target term takes `min(currency, trust)`, and the popup's `allRatingsStale` note matches.
- `RatingRecency.weight`: continuous, 1.0 at 30 days or less, 0 at 240 days or more, NaN goes to 0, and a negative age counts as fresh. `panel` uses the latest per firm by date, not list order; `now <= 0` gives null; undated rows are excluded.
- `holding` NaN safety: every value-map key comes from `put(...)` guarded by `isFinite`, as do the Finviz `loose`, the Nasdaq parse and `RecommendationJson.decode`. No NaN can reach `s`.
- The S-L7 PEG/forward-P/E labels now change exactly where the points change sign; S-L8 breakeven wording; S-M4 negative-book red flag is `why.add(0, ...)`.
- S-M2 per-list carry and S-M3 `applyAnalystEnrichment` (analyst fields only; window re-derived from the current list; `generatedAtStart` guard) are correct. `enrichAnalyst` never double-blends, because rows with `consensus != null` are skipped. S-L6 guard sits before any request.
- S-L9 `ratingsOnly` keeps Finviz/Nasdaq value maps out of the ratings path.
- `withAnalyst` upside term is centred at 0 upside (`ramp(0,-20,40,30) = 10`).
- Duplicate keys: `Research.build` lists come from map keys, `ResearchBridge.merge` does `distinctBy` on added rows, and `carryEtfExplanations` adds only symbols not already present. No duplicate-key path found in the S lists.
- ETF: `EtfScore.best` units (expense ratio and returns in percent, -1 as the no-fee-data marker), the leveraged/inverse filter (short-duration bonds kept), `EtfExposure.keyOf` maturity bands and tilt exclusion, dedupe before `take(ETF_BUFFER)`, and `ETF_ORDER` tie-breaks.
- `ScreenRow.epsGrowth`/best growth branches cover the whole EPS range with no gap. `rangePos` and `volumeRatio` guard their division-by-zero cases.
- `Social.merge`: momentum fields all come from ApeWisdom, so `mentions` vs `mentions24hAgo` compare like with like. `rankDelta` uses `apeRank`.
- Noted, not reported: `parseYahooRatings` keeps only the newest 120 actions. For mega caps this cuts firms whose latest note is about 6-8 months old, which carry at most about 10-20% weight, so `droppedStale` undercounts slightly. The score impact is negligible.
