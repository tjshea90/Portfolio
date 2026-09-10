# Round 66 - cross-cutting audit: data + network layer

Scope: data/Db.kt, net/Http.kt, net/MarketData.kt, net/YahooAuth.kt, domain/Ledger.kt,
data/Models.kt, with net/FundamentalsFeed.kt, net/News.kt and PortfolioViewModel call paths
as supporting reading. Every number below was worked by hand against the code as written.

(Findings are appended as they are confirmed; the file is re-ordered by severity at the end.)

---

ID: CRX-1
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/domain/Ledger.kt:225-228 (FIFO) and :288-291 (AVERAGE)
BUG: `todayShares` / `todayCost` are only ever ADDED to by a BUY dated today and are never
reduced by a SELL, so a same-day round trip leaves shares in "bought today" that the user no
longer owns and prices the ones they do own at a blended average that includes the closed lot.
FAILURE: SOFI, all on the same session day.
  09:45 BUY  100 @ 10.00  -> lot(100 @ 10.00); todayShares=100, todayCost=1000
  11:00 SELL 100 @ 12.00  -> lot consumed, realized += 100*12.00 - 1000 = +200.00
                             todayShares STAYS 100, todayCost STAYS 1000  <-- the bug
  14:00 BUY  100 @ 12.00  -> lot(100 @ 12.00); todayShares=200, todayCost=2200
  Position: shares=100, costBasis=1200, realized=200, sharesToday=200, costToday=2200
  avgCostToday = 2200/200 = 11.00   (the shares actually held cost 12.00)
  Quote at 15:30: price = 12.00.
  Position.dayPnl:  fresh = min(200,100) = 100, held = 0
                    fromFill = 100 * (12.00 - 11.00) = +100.00
  TRUTH: the 100 shares held were bought at 12.00 and the price is 12.00 -> today's move on
  the open position is 0.00; the +200.00 from the round trip is already booked in `realized`.
  So the "Today" headline for SOFI reads +$100.00 that does not exist, and dayPnlPct reads
  100/(100*11.00) = +9.09% instead of 0.00%. `PortfolioTotals.dayGain` sums this, so the
  portfolio headline is wrong by the same $100. Identical arithmetic in `averageCost()`.
  (`brokerDayGain` is unaffected - it never touches sharesToday - so the two headline figures
  disagree by the fabricated amount, which is exactly the reconciliation the PortfolioTotals
  doc comment says it added `brokerDayGain` to explain.)
FIX: in `fifo()`, carry the flag on the lot instead of in a side map - add
`val today: Boolean` to `Lot`, set it as `t.date in today` at `q.addLast(...)` (line 224),
delete the `todayShares`/`todayCost` maps, and in the final `lots.map { }` compute
`sharesToday = q.filter { it.today }.sumOf { it.shares }` and
`costToday = q.filter { it.today }.sumOf { it.shares * it.unitCost }`. That is exactly right
under FIFO because the sell already removed the consumed lots. In `averageCost()`, in the
SELL branch after `covered` is computed, add
`val fromToday = minOf(covered, a.todayShares); if (fromToday > 0) { a.todayCost -= fromToday * (a.todayCost / a.todayShares); a.todayShares -= fromToday }`.
CONFIDENCE: certain

---

ID: CRX-2
SEVERITY: HIGH
WHERE: app/src/main/java/com/tj/portfolio/data/Db.kt:1402 (`if (!replace && txnExists(t))`), rule at :673-711
BUG: A MERGE restore matches each incoming row against the whole `txns` table INCLUDING the
rows this same restore has just inserted, so any set of N genuinely identical transactions in
the backup collapses to exactly one row - silently, in the one code path that exists to
recover from lost transactions.
FAILURE: Merge is not an edge case: it is the primary button of the restore dialog
(SettingsScreen.kt:880), the "Restore that backup" button on the data-loss recovery card
(PortfolioScreen.kt:546), the Downloads autosave recovery (PortfolioScreen.kt:607) and the
paste-JSON restore (SettingsScreen.kt:839). The dialog's own words are "Merge adds anything
missing ... it can never remove anything you already have."
  Backup file (exported by this same app) contains, on 2026-09-02:
    row A: DEPOSIT  amount 500.00
    row B: DEPOSIT  amount 500.00        (a second transfer the same day)
    row C: BUY ONDS 45 @ 7.565, amount -340.43     (partial fill 1 of 2)
    row D: BUY ONDS 45 @ 7.565, amount -340.43     (partial fill 2 of 2 - Ally lists partial
                                                    fills as separate rows)
  Restore this file with Merge onto a phone whose txns table is empty (which is the state the
  recovery card is shown in):
    A -> findDuplicateId: no rows -> INSERT (id 1)
    B -> findDuplicateId: type=DEPOSIT, symClause `(symbol=? OR symbol IS NULL)`, same day;
         finds id 1. quantity 0 vs 0 -> sameQty true. |500 - 500| = 0 <= max(0.02, 2.50)
         -> sameAmt true -> returns id 1 -> SKIPPED.
    C -> INSERT (id 2)
    D -> finds id 2: |45 - 45| < 0.0001 and |340.43 - 340.43| <= max(0.02, 1.70) -> SKIPPED.
  Result: $500 of deposits and 45 shares of ONDS are gone from the restored ledger. Cash is
  $500 + $340.43 = $840.43 too high... no: cash is short by 500 - 340.43 = $159.57, net
  deposits short by $500, the ONDS position short by 45 shares and $340.43 of cost basis,
  and `totalGain = equity - netDeposits` is wrong on both terms at once. The toast reads
  "Merged 2 transactions (2 duplicate skipped)", which looks like success.
  The same rule also makes export->restore-merge a non-lossless round trip for any ledger
  that contains a genuine repeat, so re-running the restore never recovers the missing rows.
FIX: match one file row to at most one stored row, and never to a row this restore inserted.
Add an exclusion set to the matcher - `fun findDuplicateId(t: Txn, exclude: Set<Long> = emptySet())`
with `if (id in exclude) continue` at the top of the cursor loop in Db.kt:699 - then in
`restoreJson` keep `val claimed = HashSet<Long>()` and replace lines 1402-1403 with:
  `val dup = if (replace) null else findDuplicateId(t, claimed)`
  `if (dup != null) { claimed.add(dup); skipped++; continue }`
  `claimed.add(insertTxn(t)); n++`
Inserting adds the new id to `claimed`, so a later identical file row can no longer match it,
and a stored row can only absorb one file row.
CONFIDENCE: certain
