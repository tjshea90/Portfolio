# RESUME — READ THIS FIRST  (round 66, saved 2026-09-09 14:21:00 UTC)

You are picking up a long-running Android project that was interrupted.
Everything you need is on disk. Do NOT re-read CHECKPOINT.md end to end —
it is 240 KB of round history. This file plus `state.json` is the live state;
CHECKPOINT.md sections 0-5 (lines 1-530) are the only part worth reading cold,
and only if you need the architecture.

## 1. Bring the container back up

```bash
cd /home/claude && tar xzf <the checkpoint tarball>   # if the tree is missing
bash /home/claude/portfolio/setup-env.sh              # Android SDK, ~2 min, once
export ANDROID_HOME=/root/android-sdk
bash /home/claude/portfolio/watchdog.sh &             # restart the 3-min autosave
./ck status                                           # where the work stopped
```

Build traps that have cost real time before are in CHECKPOINT.md lines 22-60.
The short version: never blank `JAVA_TOOL_OPTIONS`; never run two Gradle builds
at once or kill one mid-flight; always background the build with
`setsid nohup ./gradlew ... > /home/claude/build.log 2>&1 < /dev/null & disown`.

## 2. The request this round is answering

> Whole-app round: code efficiency, features working as designed, cache/refresh balance (cache big, refresh liberally where it helps), bug hunt. Thicker separator bars between stocks. Research accuracy. ETF section must rank genuinely healthy, strong-buy ETFs best-first using multiple sources. Worst section: keep only stocks with a buyable companion short vehicle, or delete the section entirely.

## 3. WHERE THE WORK STOPPED

- **In flight:** T3: Worst section: keep only stocks with a buyable companion short vehicle, or delete the section
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 2/9 done

- [x] T0  Baseline: v7.6 tree green in this container  — v7.6 tree green in this container
- [x] T1  Thicker separator bars between stocks  — separator 3dp -> 5dp with 7dp of air either side; RowLayoutUiTest floor raised 18dp -> 26dp so a revert is caught
- [ ] T2  ETF section: accurate, multi-source, healthy strong-buy funds ranked best-first
- [>] T3  Worst section: keep only stocks with a buyable companion short vehicle, or delete the section  — live probe of which stocks have a buyable single-stock inverse fund
- [ ] T4  Stock research accuracy audit
- [ ] T5  Cache and refresh policy: cache as big as needed, refresh liberally where it helps
- [ ] T6  Whole-app parallel review: bugs, efficiency, UI, features working as designed
- [ ] T7  Fix every confirmed finding
- [ ] T8  REGRESSION + ship v7.7

**Resume at T3** (Worst section: keep only stocks with a buyable companion short vehicle, or delete the section).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-09 05:27:12 UTC  N07 fixed: the caption names the pan and the hold only on a zoomed chart; an unzoomed one reads exactly as it did in v7.5
- 2026-09-09 05:28:04 UTC  finding N08: nearDown was recomputed each frame, so a drag that wandered past the slop and ca
- 2026-09-09 05:28:04 UTC  N08 fixed: nearDown is latched: it can only ever go false
- 2026-09-09 05:32:16 UTC  sweep 3: 2 findings (N06 low, N07 med), closed. sweep 4: 1 finding (N08 low), closed
- 2026-09-09 05:32:16 UTC  T5 -> done  4 sweeps: 8 findings (1 high, 3 med, 4 low), all closed; sweep 4 found one low
- 2026-09-09 05:32:17 UTC  T6 -> done  v7.6 (versionCode 63) built and signed with the same cert as v7.5; 679 tests green, lint vital clean, checkinit ok
- 2026-09-09 06:05:20 UTC  round 66 started
- 2026-09-09 06:05:20 UTC  T0 -> doing  surveying the app
- 2026-09-09 06:18:04 UTC  T0 -> done  v7.6 tree green in this container
- 2026-09-09 06:18:05 UTC  T1 -> done  separator 3dp -> 5dp with 7dp of air either side; RowLayoutUiTest floor raised 18dp -> 26dp so a revert is caught
- 2026-09-09 06:18:07 UTC  T3 -> doing  live probe of which stocks have a buyable single-stock inverse fund
- 2026-09-09 14:21:00 UTC  WORKFLOW INTERRUPTED: session usage limit at 09:40 UTC killed 12 of 14 audit agents. Only review:viewmodel and review:database completed; their verifiers died, so nothing was adversarially confirmed.

