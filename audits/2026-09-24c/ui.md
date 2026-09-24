# 2026-09-24c — UI audit (Day Trading success card, engine tuning, settings row)

Read-only audit. Scope: ui/ResearchScreen.kt (DayTradingSuccessRate, DayTradingBreakdown, fmtR,
DAY_TRADING_GRADING_RULES, TradeLevelsGrid, BeginnerSummaryCard), ui/EngineTuningUi.kt,
ui/SettingsScreen.kt (Day-trading engine row), ui/PortfolioViewModel.kt (engine/engineReview/
engineEvidence flows), shared widgets. Severity: H = wrong info / crash / unexpected action;
M = real usability or perf problem; L = polish.

Status: IN PROGRESS (findings appended as confirmed)

## Findings

