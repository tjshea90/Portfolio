# AGENTS.md

<!-- handoff:learnings:begin -->
## Session Learnings (auto-updated by handoff)

### Implicit Rules
- Build only via bash tools/gradle.sh; run tools/install-hooks.sh first; tools/ckpt.sh after each step
- Release: bump versionCode/Name, ckpt, trigger android.yml full_build on main, record-release.sh after green, post Release page link; never send APK
- Settings-key prefixes for derived caches go in IncomeHub.PREFIXES (excluded from backups)
- tools/checkinit.py: properties must be declared above VM init

### Key Decisions
- Decision: GitHub Actions builds/signs (android.yml full_build on main) — Reason: CLAUDE.md rule; keystore in GitHub Secrets; record-release.sh only after green
- Decision: Nasdaq earnings-surprise as report-date source fallback — Reason: Yahoo chart events=earn unverified, earningsHistory has quarter-end not report date
- Decision: US index charts reuse PriceChart+FullScreenChart with local window state — Reason: user asked to adopt existing chart code
- Decision: foreground service + defer fgScope cancel while offline pass runs — Reason: ON_STOP cancel caused 'Refresh interrupted'; frozen process loses network
- Decision: new Robolectric tests use sdk 36 — Reason: only android-all 36 cached in sandbox

<!-- handoff:learnings:end -->
