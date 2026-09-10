# TASKS — the 2026-09-10 request, in Tj's words

> "review the attached video. when I move the chart it looks like the
> performance of the stock is different when compared to the spy line. is this
> correct? also a couple times I pressed news on some stocks and it loaded for
> a second then the whole app crashed and closed. investigate then try to build
> the next version apk and send it to me here"

Screen recording attached (19 MB mp4, 2026-09-10 17:01). Two suspected bugs
plus a release.

- [x] 1. **Chart vs SPY comparison — ANSWER: the numbers are CORRECT.** When the chart is panned/scrubbed, the
      stock's performance appears inconsistent relative to the SPY comparison
      line. Determine whether the two series are being rebased to the same
      start point as the visible window changes, or whether SPY stays pinned
      to the original range while the stock re-normalises (or vice versa).
      Answer Tj's actual question: IS the displayed comparison correct?
- [~] 2. **News crash — one real crash class removed, root cause NOT confirmed.** Pressing News on some stocks loads for ~1s then the
      Ruled OUT: the stock page's News list is already deduped by `newsKey`,
      the same expression it is keyed by, so it cannot throw on a duplicate key.
      FOUND AND FIXED: `FeedScreen`'s `shown` list is keyed by `FeedItem.id`
      but was NOT deduped by it — it relied on the upstream merge, which
      de-duplicates by `News.dedupeKey` (normalised title, 70 alphanumerics)
      instead. Those disagree on punctuation-heavy headlines, so two stories can
      survive the merge and still share an id, which is the exact crash class
      `util/CrashLog.kt` was written for. Now `distinctBy { it.id }`, matching
      what the news list already does.
      NOT PROVEN to be Tj's crash. **Ask Tj for Settings → Crash log**, which
      records the real stack trace on the device. Do not tick this box on the
      strength of the fix above.
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
