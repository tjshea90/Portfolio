# Full test 2026-09-24 - Day-trading audit (D-*)

IN PROGRESS - read-only audit of the day-trading subsystem at HEAD b6805c3e. Findings are added below as they are verified.

---

### D-1 [M] An evening Claude plan imported 16:00-20:00 is still replaced at the next pre-market (D-8 fix defeated by the post-close sweep)
- where: `net/DayTradingBridge.kt:542-545` (merge blanks `sessionDay`), `ui/PortfolioViewModel.kt:1034` (`sessionChanged`), `:1043` (`claudePlanStands`), `:1115` (`sessionDay = effective.sessionDay`), `:7555-7560` (loop still sweeps in post-close EXTENDED), `:840-847` (5-min cadence after the close)
- (draft - details being verified)
