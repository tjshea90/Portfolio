# AGENTS.md

<!-- handoff:learnings:begin -->
## Session Learnings (auto-updated by handoff)

### Implicit Rules
- Follow CLAUDE.md: run tools/install-hooks.sh first, tools/ckpt.sh after each step, ship via ship.sh then actions_run_trigger android.yml then record-release.sh
- GitHub builds APKs; never send APK bytes; post Release page link
- Build only via bash tools/gradle.sh

### Key Decisions
- Decision: resolve Google News links to publisher URL before fetch — Reason: they are JS redirects, plain GET gets no article
- Decision: file saved page under list's original URL — Reason: reader looks up target.url, avoids key mismatch
- Decision: fall back to headline+summary card when full page unsavable — Reason: every linked story must open something offline
- Decision: onFinished callback on OfflineSync instead of VM init block — Reason: tools/checkinit.py forbids properties declared below init

<!-- handoff:learnings:end -->
