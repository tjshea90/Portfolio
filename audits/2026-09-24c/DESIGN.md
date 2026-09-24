# 2026-09-24c — accurate day-trading success tracking + Claude "learn" loop

Written BEFORE code, so a session that resumes mid-job knows every decision already made.
TASKS.md (section "Tj's request, 2026-09-24c") holds the checklist; this file holds the why.

## A. Audit of the success tracking (A1) — what is wrong today

Pipeline: `enrichDayTradingVisible` (live sweep) -> `captureDayTradingRecommendations`
(`loggableDayTradingRows`) -> `Db.day_trading_log` (first plan per symbol per day) ->
"Check success rate" -> `resolveOneDayTradingEntry` -> `DayTradingEval.evaluateResolved` ->
`DayTradingEval.stats` -> `DayTradingSuccessRate` card.

Already right (keep): only rows re-planned from a live, market-open fetch are logged; only the
shown window; not "too late to start"; not while a decline is being confirmed; bars before
`recordedAt` excluded (a straddled bar excluded whole); stop checked before target in a bar;
ambiguous bars re-judged on 1-minute bars; flat by close-10; costs modelled; 25% position cap.

Found wrong — each one can count a result Tj could NOT have had:

| id | problem | direction | fix |
|---|---|---|---|
| E1 | The straddled 5-minute bar is dropped whole, so up to ~5 min of real trading after the recommendation is invisible. A fill + stop-out inside it (a real LOSS) is missed, and the next bar can then credit a WIN. | optimistic | Grade on 1-minute bars whenever Yahoo still has them (<=29 days) — the window shrinks to <60 s. 5-minute only as a fallback, labelled. Evaluate automatically when the Day Trading tab opens so the 1-minute window is not missed. |
| E2 | Fill always at exactly `entry`. A buy-stop placed when price is ALREADY above it (a Claude plan made last night, a gap, stale data) fills at market, not at the entry. Same for a stop gapped through (halt, fast tape): the fill is the open, not the stop. | optimistic | Parse bar OPEN. Buy-stop fill = max(entry, open of the trigger bar); buy-limit fill = min(entry, open); stop fill = min(stop, open) when a bar opens through it; target fill = max(target, open) on a gap up. Then the existing bp costs. |
| E3 | A target TOUCHED counts as filled. A resting limit sell only fills for sure when price trades THROUGH it. | optimistic | Target (and buy-limit entry) needs a trade-through of one tick ($0.01). Stop-type orders still trigger on a touch (that is how they work). |
| E4 | An entry that first fills at 15:45 is graded as a trade — but the plan's own rule says no new trade with < 30 min left (the card turns "TOO LATE TO START TODAY"). Following the system, the order is cancelled at 15:30. | inconsistent | Entry deadline = close - lastEntryMinutes (stored per row at logging time); later triggers = NO_ENTRY ("expired"). |
| E5 | A Claude plan can be logged when price is already past its entry, target or stop (made hours earlier). The card itself says "too late / skip" for those. | optimistic | Log only a plan that is still WAITING at that moment: stop < price < target, and price on the correct side of the entry. The app's own plans always are. |
| E6 | A "live" plan can be built from a stale feed (halted stock, Yahoo lag): the newest intraday bar is many minutes old. | anomaly | Record the newest bar's time; do not log when it is > 10 min old during the session. |
| E7 | Single bad prints (spikes that revert inside one bar) can "hit" a target. | anomaly | A target touch made only by an isolated wick > 4x the day's median bar range and > 1% of price, with neither neighbour bar reaching it, is not credited. Unfavourable spikes still count (conservative). |
| E8 | The account figure sums every trade as if all could be held at once — ten simultaneous 25% positions is 250% of the account, impossible without margin. | optimistic | Chronological simulation: a trade whose fill would take open positions past 100% of equity is skipped ("no cash free"); the account figure counts only fundable trades. Per-trade stats still use every trade. |
| E9 | Rows already graded under the old rules keep their (possibly optimistic) verdicts. | stale | `eval_version` column. Every row graded by an older version is re-graded while its bars exist; a row whose bars are gone keeps its old verdict but is flagged "legacy" and left OUT of the headline numbers (shown as a count). The log only began 2026-09-16, so in practice every row is re-graded. |
| E10 | Hit rate + averages with no statement of how much data they rest on; 5 trades can read like a verdict. | presentation | Sample size tiers ("too few to judge" < 20, early < 50, ...), Wilson 95% interval on the win rate, 95% interval on expectancy (net R), profit factor, max drawdown in R. |

Not changed (and why): one plan per symbol per day (UNIQUE(symbol, trading_day), additive-only DB
rule); the first plan shown is "the recommendation" — a bracket order placed when first shown,
left to play out, is a real, fully specified way to follow the system. Position sizing (1% risk,
25% cap) is Tj's money management and is not tunable by Claude.

## B. The learning loop (B1-B5)

### Engine parameters — `net/DayTradingParams.kt`
Every constant that shapes a plan or the ranking becomes a field of one immutable
`DayTradingParams`, `DEFAULTS` = today's engine EXACTLY (proved by a golden fixture captured from
the pre-refactor code, `DayTradingGoldenTest`). Each field has a spec: hard bounds, kind
(number/bool), what it does — the spec drives validation AND the prompt text, so they cannot drift.
New knobs default to "off" so the default engine is unchanged: `minRewardRisk`, `targetCapR`,
`maxTriggerAtrs`, `earliestEntryMinutes`, `avoidMiddayLull`, `requireBullishOpeningBar`,
`minScoreForPlan`, setup on/off, trigger-level on/off, per-setup stop/target overrides, score weights.

`DayTradingEngine` (object) holds the ACTIVE params + version (volatile, immutable swap). The VM
loads them from settings at init; ResearchScore reads them via default arguments (tests unaffected).

### Storage (all in the `settings` table => automatically in backups, merge keeps the device's)
- `dt_engine` — active params JSON + version number.
- `dt_engine_history` — JSON array of every apply / undo / revert: version, time, changes
  (param, from, to), Claude's verdict + rationale, graded-trade count at the time.
- The ORIGINAL engine is `DayTradingParams.DEFAULTS` compiled into the APK — cannot be corrupted
  or lost; "Revert to original" writes it back and records the revert. A plain-JSON copy of the
  original + current engine is also written to app storage (`files/daytrading-engine/`) as the
  backup file Tj asked for, and exported with the prompt.

### Log additions (db v10, ALTER ADD only)
`engine` (version that made the plan, "claude" for Claude's), `features` (JSON: setup level,
R:R, risk/trigger distance in ATRs, ATR%, range used, VWAP distance, score halves, minutes since
open, entry deadline, flat time, data time ...), `eval_version`, `eval_detail` (JSON: fill price
+ time, exit time + reason, resolution 1m/5m, MFE/MAE in R, hold-to-flat R, and a stop x target
counterfactual grid computed from the same bars — what other stop/target multiples would have
done, the data Claude needs to tune them).

### Prompt ("Make tuning prompt" -> share sheet)
Start-now line; the goal (raise net expectancy per trade without overfitting, every round
building on the last); the rules the app enforces; how the engine works (algorithm spec with
current values); every param with bounds; full change history + performance per engine version;
how trades are graded (fills, costs, deadlines); aggregate stats (by setup, level, time, score
bucket, version, source) with sample sizes and intervals; the counterfactual grid; the trade
table (one line per graded trade); what to check (overfitting, small groups, regime, costs);
exact answer schema (`dayTradingEngine`), answer-as-file instructions + share-back line.

### Import (share-in or Import button) — `net/EngineTuning.kt`
`SharedAnswer.Kind.ENGINE_TUNING`. Parse -> validate against the CURRENT engine (answer must be
based on the current version; `from` must match) -> sample-size guard ENFORCED IN THE APP:
counted on graded trades of the app's own plans (Claude's own plans do not measure the engine):
  - < 30: no changes at all (analysis still shown)
  - 30-74: <= 3 changes, each <= 10% of its range, no on/off switches
  - 75-149: <= 5 changes, <= 20%, switches only with >= 30 trades in the group they cite
  - >= 150: <= 8 changes, <= 35%, same switch rule
  - and >= 20 new graded trades since the last applied change (each change gets measured)
  - an over-large step is LIMITED to the allowed step (said so), out-of-bounds refused
  - the group a change cites ("setup:Pullback") is re-counted by the app, not trusted
-> review sheet (what changes, from -> to, why, what was limited/refused) -> Apply -> history.

### Revert
"Undo last change" (previous state) and "Revert to original engine" (DEFAULTS), both confirmed,
both recorded in the history, available on the Day Trading tab and in Settings.

## Order of work (each step checkpointed)
1. golden fixture of today's engine  2. DayTradingParams + refactor (golden green)
3. db v10 + features/gates at logging  4. evaluator v2 (+tests)  5. re-grade + auto-eval + stats v2
6. success card UI  7. tuning: store, prompt, parse/validate/apply, undo/revert, share routing, UI
8. backup/restore of new columns  9. light pass  10. full tests (<=3 agents at once)  11. ship
