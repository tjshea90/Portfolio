# Round 66 audit - ETF ranking correctness

Scope: `net/EtfScore.kt`, `net/EtfExposure.kt`, `net/EtfScreener.kt`, the ETF half of
`net/Research.kt`, `data/EtfModels.kt`, checked against `test/EtfTest.kt` and
`test/EtfExposureTest.kt`. Read-only; nothing under `app/` was modified. Every score below was
computed from the code exactly as written.

Findings are ranked most severe first.

---

## ETF-1
- **SEVERITY:** HIGH
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/EtfScore.kt:183` (also `:164`, `:167`)
- **BUG:** `0.0` is the sentinel for "Yahoo did not publish this horizon", so a horizon that is
  genuinely flat is dropped out of the weighted average instead of scoring near zero - and
  because the average is `earned * 34 / possible`, dropping a fund's weakest horizon *raises*
  its score.
- **FAILURE:** Two funds, identical in cost (0.10%), assets ($5bn), dollar volume ($100m), age
  (10y), trend (above both averages), and identical 5Y = +12%/yr and 3Y = +12%/yr.
  - **Fund A**, `ytdReturnPct = 3.0` (a real, disclosed +3% year to date):
    `earned = ramp(12,0,20,14)=8.4 + ramp(12,0,20,10)=6.0 + ramp(3,0,25,4)=0.48 = 14.88`,
    `possible = 28` -> return term `14.88 * 34/28 = 18.07`. **Total score 77.**
  - **Fund B**, `ytdReturnPct = 0.0` (dead flat all year - strictly the worse fund):
    `earned = 14.4`, `possible = 24` -> return term `14.4 * 34/24 = 20.40`. **Total score 79.**

  B is worse and outranks A. Sweeping YTD with 5Y = 3Y = +12 held fixed:

  | YTD | return points |
  |---|---|
  | -8.0 | 17.486 |
  | -0.5 | 17.486 |
  | **0.0** | **20.400** |
  | +0.5 | 17.583 |
  | +6.0 | 18.651 |
  | +12.5 | 19.914 |
  | +15.0 | 20.400 |
  | +25.0 | 22.343 |

  A flat or absent YTD is scored exactly as though the fund had returned **+15% year to date**.
  Every YTD from minus-infinity up to +14.99% scores *worse* than reporting nothing at all. It
  is non-monotonic at zero - the identical discontinuity the Round 66 comment at
  `EtfScore.kt:200-207` says it removed, moved from the `possible >= 20.0` test to the
  `!= 0.0` test. This is not a knife-edge case in live data: `EtfScreener.parse:194-202` builds
  every return field with `optDouble(key, 0.0)`, so every fund whose `ytdReturn` Yahoo omits
  collects the +15%-equivalent bonus. **The list systematically prefers funds whose data is
  less complete.** The same mechanism applies to a genuinely flat 3Y or 5Y (`:164`, `:167`).

  Not covered by the tests: `a missing short-run figure does not change a fund judged on its
  rates` (EtfTest.kt:277) only asserts equality for a fund **topping out every horizon**
  (3Y = 20, YTD = 25), which is the one input where the two paths agree.
- **FIX:** Take YTD out of the normalised average. Score the two annualised NAV horizons as the
  weighted average alone (`earned * 30 / possible` over 5Y = 14 / 3Y = 10, rescaled to 30) and
  add `ramp(r.ytdReturnPct, 0.0, 25.0, 4.0)` as a **separate additive term outside
  `earned`/`possible`**, exactly as cost and size are added. The presence or absence of YTD then
  cannot move the long-run points, and a fund with no YTD simply forfeits up to 4 points like
  every other missing input. (This also fixes ETF-5.) Fixing the sentinel itself instead would
  require the return fields in `EtfRow` to become nullable; `!= 0.0` cannot express "flat".
- **CONFIDENCE:** certain

---

## ETF-2
- **SEVERITY:** HIGH
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/EtfScreener.kt:154` (with `:105`, `:106`,
  `:115`, and `Research.kt:215`)
- **BUG:** `fetch` returns the *same* `emptyList()` for "this page is past the end of the list"
  and for "neither Yahoo host would answer", so `fetchAll`'s Round-66 empty-page stop cannot
  tell them apart and silently truncates the fund universe on a transient 429 - and `buildEtfs`
  only warns when a list returns nothing **at all**.
- **FAILURE:** `fetchAll("top_etfs_us", pages = 6)`. Pages 0 and 1 succeed (200 funds). On page
  2, `Http.get` returns `CODE_COOLDOWN` for query1 (a chart or quote batch elsewhere in the app
  already tripped that host's cooldown - `Http` arms them per host, `Http.kt:490-494`) and
  query2 returns a 429. Both loop iterations hit `continue` (`:105`, `:106`), the loop falls
  out to `return emptyList()` (`:115`), and `fetchAll` sees `page.isEmpty()` and **breaks**.
  - `universe` holds 200 funds from `top_etfs_us` instead of 523.
  - `Research.buildEtfs:215` tests `if (rows.isEmpty())`; `rows.size == 200`, so **no warning is
    added**. `ETF_SOURCES` still tells the user the ranking covers "about 850 funds".
  - The 323 funds on pages 3-6 are never scored. Yahoo returns `top_etfs_us` in its own order,
    so what is lost is an arbitrary two-thirds of the universe and the top of the list becomes
    "the best of Yahoo's first two pages".

  This is the identical failure the Round 66 comment at `:139-153` describes and claims to have
  fixed: *"Silently: the caller only warns when a list returns nothing at all... On the one list
  TJ is about to spend money from."* The `page.size < MAX_COUNT` test was removed, but the
  surviving `page.isEmpty()` test is fed by a function that returns empty for four different
  reasons. The header claim at `:37-38` - *"A page that does not answer costs nothing but its
  own rows"* - is false inside `fetchAll`: a page that does not answer costs every page after
  it. The other Round 66 change, `if (r.throttledLocally) continue` at `:105`, makes this
  **more** likely to fire, not less: a cooling host now falls through into the same empty
  return instead of surfacing as a failure.
- **FIX:** Make `fetch` distinguish the two. Change its return type to `List<EtfRow>?`, return
  `null` from the fall-through at `:115` (no host answered) and keep `emptyList()` for the
  well-formed-but-empty terminal page at `:113`. In `fetchAll`, `break` only on `emptyList()`;
  on `null`, break and record it so `buildEtfs` can add a warning such as "Yahoo's US ETF screen
  answered only N of 6 pages". Nothing else in either file needs to change.
- **CONFIDENCE:** certain

---

## ETF-3
- **SEVERITY:** HIGH
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/EtfExposure.kt:83-85` (and `:136-137`)
- **BUG:** The region test runs on the whole name **before** any asset-class test and has no
  bond guard, so a global/international **bond** fund is handed the equity key `"Global equity"`
  and de-duplicated against global **stock** funds.
- **FAILURE:** Running the real Yahoo `longName` of every large US fund through `keyOf`, the key
  `"Global equity"` collects **eight** funds spanning three unrelated decisions:

  | symbol | name | what it actually holds |
  |---|---|---|
  | VT | Vanguard Total World Stock Index Fund ETF Shares | global equity, ~60% US |
  | ACWI | iShares MSCI ACWI ETF | global equity, ~60% US |
  | VXUS | Vanguard Total International Stock Index Fund ETF Shares | equity, **0% US** |
  | IXUS | iShares Core MSCI Total International Stock ETF | equity, **0% US** |
  | VEU | Vanguard FTSE All-World ex-US Index Fund ETF Shares | equity, **0% US** |
  | ACWX | iShares MSCI ACWI ex U.S. ETF | equity, **0% US** |
  | **BNDX** | **Vanguard Total International Bond Index Fund ETF Shares** | **international bonds** |
  | **BNDW** | **Vanguard Total World Bond ETF** | **global bonds** |

  `" total international "` matches inside "Total **International** Bond" and `" total world "`
  matches inside "Total **World** Bond", both at `:83-84`, and `keyOf` `return`s at `:96` before
  the bond branches at `:128-137` are ever reached. `Research.buildEtfs` sorts by score first,
  so VT (5Y ~ +12%/yr) heads the group and `EtfExposure.dedupe` **deletes BNDX and BNDW from
  the list entirely**, printing on VT's card *"Same exposure as VXUS, IXUS, ACWI - this one
  scored highest of them"* (`Research.kt:307`). A global bond fund is removed from a best-ETF
  list on the grounds that a global stock fund holds the same thing. `bond_etfs` is one of the
  three screens fetched (3 pages, 377 funds), so both funds are in the universe.

  **Second instance, same class:** `" aggregate bond "` at `:136` carries no region qualifier,
  so `"iShares Core International Aggregate Bond ETF"` (IAGG - hedged **ex-US** bonds) is keyed
  `"Bonds - US aggregate"` alongside BND, AGG and SPAB, and is deleted the same way.

  This is exactly what the file's own header (`:30-34`) calls "the failure that matters:
  merging two genuinely different funds hides a real choice from someone about to spend money,
  and does it silently", and it leaves the list unable to hold the safe half of a portfolio -
  the same concern `EtfScore.kt:107` raises about short-duration bond funds.
- **FIX:** Put the asset-class test in front of the region test. Before the `region` block at
  `:78`, compute a bond flag and skip the region branch when it is set - e.g.
  `val isBond = listOf(" bond ", " bonds ", " treasury ", " treasuries ", " aggregate ",
  " credit ", " municipal ", " muni ", " tips ", " debt ").any { n.contains(it) }`, then
  `if (region != null && !isBond)`. Falling through to the existing bond branches is safe:
  BNDX and BNDW state no maturity band, so `maturityKey` returns null and they are left alone,
  which is this file's standing rule. For IAGG at `:136`, qualify the branch to require the
  name **not** to contain `" international "`, `" global "` or `" ex us "`.
- **CONFIDENCE:** certain

---

## ETF-4
- **SEVERITY:** MEDIUM
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/Research.kt:244` (`sortedByDescending`),
  with `EtfScore.kt:229`, `:241`, `:253`
- **BUG:** Every scoring term that could separate two trackers of the same index is **saturated**
  for large funds, so the winner of an exposure group is decided by third-decimal noise in the
  reported NAV returns - or, on the integer tie that usually follows, by Yahoo's screen order,
  because `sortedByDescending` is stable and `universe` is a `LinkedHashMap` in fetch order.
  The card then asserts *"this one scored highest of them"*.
- **FAILURE:** Three S&P 500 trackers, realistic figures, all above both moving averages and all
  older than five years:

  | fund | expense | 5Y / 3Y / YTD | cost pts | size pts | liq pts | raw | **score** |
  |---|---|---|---|---|---|---|---|
  | VOO | 0.03% | 14.80 / 18.20 / 12.00 | 20.000 | 16.00 | 12.00 | 91.961 | **91** |
  | IVV | 0.03% | 14.79 / 18.18 / 11.98 | 20.000 | 16.00 | 12.00 | 91.937 | **91** |
  | SPLG | 0.02% | 14.85 / 18.25 / 12.05 | 20.000 | 16.00 | 12.00 | 92.044 | **92** |

  VOO and IVV tie at **91**, so `dedupe` keeps whichever Yahoo listed first and deletes the
  other. Move SPLG's tracking by four hundredths of a percent - VOO 14.82/18.21/12.01 against
  SPLG 14.79/18.19/11.99 - and both land on **91**, with VOO winning purely on insertion order
  **despite costing 50% more** (0.03% vs 0.02%). The cost ramp (`EtfScore.kt:229`,
  `ramp(-e, -0.75, -0.05, 20.0)`) reaches full marks at or below 5bp and is therefore
  arithmetically **blind to the difference between 2bp and 5bp**; `logRamp` tops out at $50bn of
  assets and $100m of daily dollar volume, which every large tracker clears. Before Round 66
  this only mis-ordered two adjacent rows; now `EtfExposure.dedupe` **deletes the loser**, so an
  arbitrary pick becomes the only S&P 500 fund on the page, presented as the best of its group.
- **FIX:** Give the sort a deterministic, meaningful tiebreak. At `Research.kt:244` replace
  `.sortedByDescending { it.second.score }` with
  `.sortedWith(compareByDescending<Pair<EtfRow, ResearchScore.Scored>> { it.second.score }
  .thenBy { if (it.first.expenseRatio > 0.0) it.first.expenseRatio else Double.MAX_VALUE }
  .thenByDescending { it.first.netAssets })` - cheaper wins a tie, then larger. Widening the
  cost ramp alone does not fix it: at `-0.75 .. 0.0` the 1bp gap is worth 0.27 points, still
  inside the truncation to `Int` at `EtfScore.kt:295`.
- **CONFIDENCE:** certain

---

## ETF-5
- **SEVERITY:** MEDIUM
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/EtfScore.kt:209`
- **BUG:** `ramp` clamps a negative long-run return to zero points but `possible` still counts
  its full weight, so `earned * 34 / possible` amplifies whatever *did* score - which, for a
  fund with a losing long-run record, is only the trailing-year YTD term. The 4-point YTD term
  is scaled up to as much as 9.71 of the 34 return points (2.43x its nominal weight), precisely
  for the funds whose three- and five-year records are worst.
- **FAILURE:** Two funds, identical in cost (0.35%), assets ($2bn), dollar volume ($50m), age
  (8y), trend (above both averages), neither publishing a 5Y figure.
  - **Fund P**, `threeYearAnnualPct = +6.0`, `ytdReturnPct = +4.0`:
    `earned = ramp(6,0,20,10)=3.0 + ramp(4,0,25,4)=0.64 = 3.64`, `possible = 14`
    -> return term `3.64 * 34/14 = 8.84`. **Total score 57.**
  - **Fund Q**, `threeYearAnnualPct = -1.5`, `ytdReturnPct = +38.0`:
    `earned = 0.0 + 4.0 = 4.0`, `possible = 14` -> return term `4.0 * 34/14 = 9.71`.
    **Total score 58.**

  Fund Q has **lost** 1.5%/yr over three years and outranks Fund P, which made +6%/yr, on the
  strength of one hot trailing year. That is the failure the file's own header (`:24-26`) says
  the three-year floor exists to prevent - *"rank on a trailing year and the list fills with
  whatever sector happened to run"*. The floor only blocks funds with **no** long record; a fund
  with a **bad** long record passes it and has its hot year magnified. The test
  `a fund with only one year of record cannot normalise its way to the top` (EtfTest.kt:243)
  covers only `hasLongRecord == false`, so this case is untested.
- **FIX:** The same minimal change as ETF-1 - move the YTD term out of `earned`/`possible` and
  add it flat. With YTD outside the average, Q scores `0 + 4.0 = 4.0` return points against P's
  `3.0 * 30/10 + 0.64 = 9.64`, and the ordering is right.
- **CONFIDENCE:** certain

---

## ETF-6
- **SEVERITY:** MEDIUM
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/EtfScore.kt:224`
- **BUG:** `if (r.expenseRatio > 0.0)` treats a 0.00% expense ratio as "Yahoo did not say", so
  the cheapest funds in existence - the zero-fee ETFs - are scored as if their cost were
  unknown, forfeiting all 20 cost points and taking a confidence penalty as well.
- **FAILURE:** One fund, five expense ratios, everything else fixed (assets $3bn, dollar volume
  $20m, age 6y, 5Y = 14, 3Y = 17, YTD = 11, above both averages):

  | expense ratio | cost points | **score** | confidence |
  |---|---|---|---|
  | **0.00%** (e.g. BKLC, BKAG) | 0.000 | **61** | **83** |
  | 0.03% | 20.000 | **81** | 100 |
  | 0.20% | 15.714 | **76** | 100 |
  | 0.75% | 0.000 | **61** | 100 |
  | 0.85% | 0.000 | **61** | 100 |

  A genuinely free fund scores **20 points below** the same fund charging 3bp, and lands on
  exactly the same score as the same fund charging **0.85%** - while additionally reporting
  lower confidence, so it is nearer to being dropped by the `MIN_ETF_CONFIDENCE = 60` gate than
  the expensive one is. BNY Mellon's BKLC and BKAG have charged 0.00% since 2020 and are in
  Yahoo's US ETF screen. The file's own comment at `:31-33` - *"the expense ratio is the only
  number in investing known in advance... it is subtracted from the return above whether the
  fund goes up or down"* - is exactly inverted here.
- **FIX:** `EtfRow.expenseRatio` cannot express "unknown" as a `Double` with 0.0 also meaning
  free. The minimal change that does not touch the model: in `EtfScreener.parse:192`, read the
  field as `q.optDouble("netExpenseRatio", -1.0)` and store `-1.0` for absent, then in
  `EtfScore.kt:224` test `if (r.expenseRatio >= 0.0)` so a true 0.00% scores the full 20 points
  and only a genuinely absent field is skipped. `EtfFacts.toJson`/`isEmpty` already guard on
  `> 0`, so an absent value stays out of the card either way.

  **Related, same class, lower impact:** `EtfScore.kt:157` opens the return block (and does
  `have++`) when `r.oneYearPct != 0.0`, but Round 66 stopped scoring that figure - so a fund
  publishing only a 52-week price change is counted as having "carried" the return factor while
  contributing 0 of its 34 points, and can reach confidence 100 with no scored return data at
  all. Fix: drop `|| r.oneYearPct != 0.0` from the condition at `:157`.
- **CONFIDENCE:** certain

---

## ETF-7
- **SEVERITY:** MEDIUM
- **WHERE:** `app/src/main/java/com/tj/portfolio/net/EtfExposure.kt:83-85`
- **BUG:** The single key `"Global equity"` is returned for two spellings that mean opposite
  things - "total world" / "all world" / "acwi" (global equity **including** the US, ~60% US)
  and "total international" / "ex us" / "ex u s" (global equity holding **no** US at all).
- **FAILURE:** From the same run as ETF-3, ignoring the bond funds: VT and ACWI (with US) share
  the key `"Global equity"` with VXUS, IXUS, VEU and ACWX (without US). VT scores highest -
  it is US-heavy, so it carries a stronger five-year record - so every ex-US fund is dropped
  from the list and VT's card names them as the same exposure. Concretely: a reader who already
  holds VOO and wants the rest of the world is shown VT, which is 60% the S&P 500 they already
  own, and the four funds that actually answer their question are off the page and labelled as
  duplicates of it. Only three of the seven losers are even named (`:206`, `also.size < 3`).

  The test `a non-US fund is never merged with a US one` (`EtfExposureTest.kt:84`) compares
  all-world-ex-US against **US** and against **EAFE** and passes; nothing compares
  all-world-ex-US against all-world-including-US, which is why this survived.
- **FIX:** Split the branch at `:83-85` into two keys. Test the ex-US spellings first -
  `" total international "`, `" ex us "`, `" ex u s "` -> `"Global equity ex-US"` - and only
  then `" total world "`, `" all world "`, `" acwi "` -> `"Global equity incl US"`. Order
  matters: "FTSE All-World ex-US" contains both, and the ex-US reading is the correct one.
- **CONFIDENCE:** certain

---

## ETF-8
- **SEVERITY:** MEDIUM
- **WHERE:** `app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:5104`
  (`ResearchBridge.merge(cur.etfs, parsed.etfs)`) and `:505` (`carryEtfExplanations`)
- **BUG:** `EtfScore.isLeveragedOrInverse` is applied **only** on the screener path
  (`Research.kt:241`). Funds Claude adds through the import/API path are never tested, so the
  leverage and inverse exclusion is not enforced on every path into the list - and the screen's
  own `ETF_SOURCES` text tells the user that it is.
- **FAILURE:** Claude's reply contains `{"symbol":"TQQQ","why":"...","conviction":7}`.
  `ResearchBridge.section` admits it (any non-blank `why`), `merge` appends it to `added`, and
  `applyResearchAnswer` sorts by score then conviction - TQQQ has `score = 0`, so it lands at
  the **bottom** of the 40-row fund list rather than the top, but it **is on the list**, on a
  screen whose sources note (`Research.kt:275-276`) reads *"Leveraged and inverse funds are
  excluded"*. It then survives every six-hourly rebuild indefinitely via
  `carryEtfExplanations:489`, which keeps any row with `etf == null` and a non-blank `why`. The
  prompt does not close the hole either: `ResearchBridge.kt:274-278` states the exclusion as a
  **fact about the app's list** and then says *"Add any fund that belongs on a best-ETF list and
  is not there"*; it never tells the model to leave leveraged funds out. The same path also
  bypasses `MIN_FUND_ASSETS`, `MIN_FUND_DOLLAR_VOLUME` and `EtfExposure.dedupe`.
- **FIX:** The filter cannot run at merge time - `ResearchBridge.section` never reads a name, so
  an added row's `name` is blank and `isLeveragedOrInverse("", "TQQQ")` returns false. Apply it
  after the quote fill, where the name arrives: at `PortfolioViewModel.kt:5193` change
  `etfs = fill(s.etfs)` to
  `etfs = fill(s.etfs).filterNot { com.tj.portfolio.net.EtfScore.isLeveragedOrInverse(it.name, it.symbol) }`.
  Separately, add one sentence to the numbered ETF instructions at `ResearchBridge.kt:125-135`:
  "Do not add leveraged, inverse or single-stock funds - the app excludes them deliberately."
- **CONFIDENCE:** certain

---

## Questions that came back clean

Of the seven questions asked, **two came back clean: Q3 (no term is double-counted and no term
is applied with the wrong sign) and Q4 (the list is genuinely sorted best-first on every path
that can produce it)**.

- **Q3 - clean.** The cost term's negation (`ramp(-r.expenseRatio, -0.75, -0.05, 20.0)`) is
  oriented correctly: cheaper earns more. Nothing is counted twice - the return terms are
  normalised rather than summed with the record-length term, which is what Round 66's
  "youth is penalised once, not twice" change achieved; the two trend tests at `:277-278` are
  independent conditions, not a repeat; `offHighPct` and `yieldPct` produce reason lines only
  and are never added to `s` (confirmed by `yield is reported but never scored`,
  EtfTest.kt:325). ETF-5 is an effective re-weighting caused by normalisation, not a double
  count.
- **Q4 - clean.** Verified on all five paths: `buildEtfs` sorts by score, then `dedupe`
  preserves that order (it appends survivors in first-encounter order, so its output is a
  subsequence of a sorted list), then `take(40)`; `applyResearchAnswer:5104-5107` re-sorts by
  score then conviction after the Claude merge; `carryEtfExplanations:505-507` re-sorts the same
  way after a rebuild, so Claude's additions stay below every scored fund; `ResearchSet.toJson`
  writes a `JSONArray` and `fromJson` reads it back in order; `fillPricesNow`'s `fill` is a
  `map` and preserves order; and `ResearchScreen.kt` never re-sorts. What the ranking is *made
  of* is wrong in the ways above, but the ordering machinery itself is sound.

Q1, Q2, Q5, Q6 and Q7 each produced findings: Q1 -> ETF-1, ETF-4, ETF-5, ETF-6; Q2 -> ETF-1,
ETF-6; Q5 -> ETF-3, ETF-4, ETF-7; Q6 -> ETF-8; Q7 -> ETF-2 (the E1 comment), ETF-8
(`ETF_SOURCES`), ETF-6 (the "confidence is the share of factors carried" contract).
