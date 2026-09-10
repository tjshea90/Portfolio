# TASKS — the 2026-09-10 request, in Tj's words

> "review the attached video. when I move the chart it looks like the
> performance of the stock is different when compared to the spy line. is this
> correct? also a couple times I pressed news on some stocks and it loaded for
> a second then the whole app crashed and closed. investigate then try to build
> the next version apk and send it to me here"

Screen recording attached (19 MB mp4, 2026-09-10 17:01). Two suspected bugs
plus a release.

- [ ] 1. **Chart vs SPY comparison.** When the chart is panned/scrubbed, the
      stock's performance appears inconsistent relative to the SPY comparison
      line. Determine whether the two series are being rebased to the same
      start point as the visible window changes, or whether SPY stays pinned
      to the original range while the stock re-normalises (or vice versa).
      Answer Tj's actual question: IS the displayed comparison correct?
- [ ] 2. **News crash.** Pressing News on some stocks loads for ~1s then the
      whole app dies. Find the crash. `app/src/main/java/com/tj/portfolio/
      net/News.kt` and whatever renders it. Check util/CrashLog.kt for a
      recorded stack trace first — this app logs its own crashes.
- [ ] 3. Fix whatever 1 and 2 turn out to be, with a regression test for each.
- [ ] 4. Bump versionCode past 64 (v7.7 shipped code 64) and versionName.
- [ ] 5. Ship: full unit suite green, signed release APK, and send the APK to
      Tj in the chat.

Ticking a box means: written, tested (name the test) and committed.

## Notes for whoever picks this up

- Do NOT tick 1 or 2 on a plausible-looking code reading alone. Both are
  reported from a real device; reproduce them in a test first.
- The video is NOT in the repo (uploads are per-session). Findings from it
  must be written down here or they are lost to the next session.
