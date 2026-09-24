# Full-test audit 2026-09-24 — SCORING / RECOMMENDATION ENGINE (S-)

Auditor: read-only subagent. Scope: net/ResearchScore, Recommend, EtfScore, EtfScreener,
EtfExposure, Research, ResearchBridge, RatingRecency, Relevance, MarketRelevance, Screener,
Insider, Form4, ClaudeBridge, SharedAnswer; data/Research/Recommendation/Fundamentals/
FundamentalsJson/Insider/Etf models; the VM paths that build/carry research & recommendations.

STATUS: IN PROGRESS (findings S-1..S-6 drafted; ETF/Relevance/Insider/VM passes still running)

### S-1 [M] S-9's popup fix is dead code: a dated-but-vote-less panel still says "no publication dates" and reports the wrong weight
- where: data/RecommendationModels.kt:95-119 (`freshnessNote`), net/Recommend.kt:67-91
- (draft - full text below when complete)

### S-2 [L] S-7 fix incomplete: `coreAt` falls back to the merged (ratings) stamp when the disk core row is stale and the core fetch failed
### S-3 [M] Research answers' `asOf` is never read: an old answer re-shared is stamped as written now
### S-4 [M] Rebuild carry freezes the app's own relative-date catalyst ("Earnings in 3 days") as "Claude's" for up to 14 days
### S-5 [M] A stale Claude-added fund becomes a permanent category-only row after a cold launch
### S-6 [M] Day-trading paragraphs ("worth trading today") are carried onto later sessions for 14 days
