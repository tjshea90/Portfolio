# Round 66 — SECOND adversarial review (the fixes for the first adversarial pass)

Read-only review of `b9f463a..HEAD` — i.e. the fixes for REG-1..REG-6, DET-1..DET-8,
CRX-1/CRX-2 and EXP-1..EXP-3. Findings are regressions INTRODUCED BY those fixes, not the
original bugs. Ranked most severe first. Nothing already in `round66-diff.md` is repeated.

---

## ID: REG2-1
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/domain/Ledger.kt:348` (`averageCost`, the CRX-1 same-day drain)
**BUG:** The new drain takes the sold shares out of today's pool FIRST (`minOf(covered, a.todayShares)`) instead of pro-rata as its own comment claims — the exact opposite of the FIFO branch it is supposed to mirror — so a same-day sell on a position that also holds older shares wipes the whole "bought today" pool and re-measures shares bought this morning from yesterday's close.

**FAILURE:**
Cost method AVERAGE. SOFI:
* 30 days ago: BUY 100 @ 10.00
* today 09:45: BUY 100 @ 12.00
* today 11:00: SELL 100 @ 12.50
* quote: `price = 13.00`, `prevClose = 12.50`

`averageCost` reaches the SELL with `shares = 200`, `cost = 2200`, `todayShares = 100`,
`todayCost = 1200`. `covered = 100`, so `fromToday = minOf(100, 100) = 100` → **`todayShares = 0`,
`todayCost = 0`**.

`fifo()` on the identical ledger consumes the OLDEST lot (the 100 bought 30 days ago) and
leaves `Lot(100, 12.00, today = true)` → `sharesToday = 100`.

So for the same three transactions:

| | shares | sharesToday | `dayPnl` | `dayPnlPct` | `boughtTodayCount` |
|---|---|---|---|---|---|
| FIFO | 100 | 100 | **+$100.00** | +8.33% | 1 |
| AVERAGE (after this fix) | 100 | **0** | **+$50.00** | +4.00% | **0** |
| AVERAGE (before this fix) | 100 | 100 | +$100.00 | +8.33% | 1 |

The fix therefore *introduced* a $50 disagreement between the two cost methods on the
portfolio's "Today" headline, on a ledger where they previously agreed, and it removed the
`boughtTodayCount` badge that is the only thing on `PortfolioScreen.kt:245` explaining why
`dayGain` differs from `brokerDayGain`. `sell 150` instead of 100 gives the same shape:
FIFO keeps `sharesToday = 50`, AVERAGE reports 0.

The comment above the code asserts two things that the code does not do: it is not pro-rata
(pro-rata of a 100/100 pool for a 100-share sale is 50, not 100), and "FIFO would take the
OLDEST lots first, which ... comes to the same shares" is precisely the property this
implementation breaks. Lifetime P/L parity is unaffected (the drain touches only
`todayShares`/`todayCost`), which is why the new `SameDayRoundTripTest` — which never mixes
older shares with a same-day round trip — passes.

**FIX:** Drain the OLD shares first, matching FIFO, which is one expression and needs no
extra state (`a.shares` has already been decremented at this point):
```kotlin
val fromToday = (a.todayShares - a.shares).coerceAtLeast(0.0)
```
Round trip (buy 100 today, sell 100 today): `shares = 0`, `todayShares = 100` → drains 100 ✓.
Partial (buy 100 today, sell 40): `shares = 60`, `todayShares = 100` → drains 40 ✓.
Mixed (100 old + 100 today, sell 100): `shares = 100`, `todayShares = 100` → drains 0, matching
FIFO ✓. It also keeps the `todayShares <= shares` invariant by construction.

**CONFIDENCE:** certain

---

## ID: REG2-2
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:3454-3464` (`publishInsiders`, DET-4)
**BUG:** The new `insiderAt` stamp is applied to `bySymbol.keys`, and `bySymbol` is built from `merged` — the *entire accumulated filing store*, including rows restored from disk — not from the symbols this pass actually asked EDGAR about, so every symbol that has ever had a filing cached is permanently marked "just refreshed".

**FAILURE:**
`merged = (filings + _insiderFilings.value).distinctBy{accession}.sortedByDescending{filedAt}.take(MAX_INSIDER_FILINGS)`
(line 3441). `_insiderFilings.value` is repopulated on every launch from `Keys.INSIDER_CACHE`
by `loadCachedInsider()` (line 3524) and holds up to 30 days of filings for symbols that are
in neither `rows` nor the watchlist.

Concrete: TJ opens PLTR from the Research tab on Monday; its Form 4s land in
`_insiderFilings` and are written to `INSIDER_CACHE`. Tuesday the app launches,
`loadCachedInsider` restores them, and the half-hourly `refreshInsiders(rows, owned)` runs —
`rows` does not contain PLTR, so `Insider.forSymbols` never asks EDGAR about it. But
`publishInsiders` merges the new filings with the restored store, `bySymbol.keys` contains
`"PLTR"`, and line 3464 sets `insiderAt["PLTR"] = now`. TJ opens PLTR: `loadInsider` computes
`since = 0 < INSIDER_SYMBOL_TTL_MS` and returns without fetching. Every subsequent feed pass
re-stamps it, so the TTL can never expire.

That is *the exact symptom DET-4 was written to remove* — "a stock TJ neither holds nor
watches - a search, opened from the Research tab - froze at whatever the first fetch
returned" — reinstated through the stamp, and now surviving a process restart, which the old
`_insider` short-circuit did not (it needed the disk cache to repopulate first, which it also
did, but at least a symbol with no cached filings recovered). It also breaks the documented
meaning of `insiderAt` stated four lines above it and at line 3266: "`insiderAt` records that
the pass COMPLETED" — for these symbols no pass ran at all.

**FIX:** Stamp only what this pass covered:
```kotlin
val bySymbol = merged.groupBy { it.symbol }
_insider.value = _insider.value + bySymbol
val now = System.currentTimeMillis()
filings.mapTo(HashSet()) { it.symbol }.forEach { insiderAt[it] = now }
```
(Better still, have `refreshInsiders` stamp the `symbols` list it was handed, so a covered
symbol that filed nothing is stamped too — `filings` alone misses those.)

**CONFIDENCE:** certain

---
