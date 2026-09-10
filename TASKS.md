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
- [x] 2. **News crash — ROOT CAUSE FOUND from Tj's crash log, and fixed.** Pressing News on some stocks loads for ~1s then the
      Ruled OUT: the stock page's News list is already deduped by `newsKey`,
      the same expression it is keyed by, so it cannot throw on a duplicate key.
      FOUND AND FIXED: `FeedScreen`'s `shown` list is keyed by `FeedItem.id`
      but was NOT deduped by it — it relied on the upstream merge, which
      de-duplicates by `News.dedupeKey` (normalised title, 70 alphanumerics)
      instead. Those disagree on punctuation-heavy headlines, so two stories can
      survive the merge and still share an id, which is the exact crash class
      `util/CrashLog.kt` was written for. Now `distinctBy { it.id }`, matching
      what the news list already does.
      THE ACTUAL CRASH, from the device log Tj sent (4 times, 9:42-9:43 AM):
      `IndexOutOfBoundsException: Index 5 out of bounds for length 5` at
      `TabRowKt$ScrollableTabRow$1.invoke(TabRow.kt:1409)` — Material3's DEFAULT
      indicator doing `tabPositions[selectedTabIndex]`. Nothing to do with news
      data at all. `visibleTabs` hides Holdings until the fund lookup returns
      about a second after the screen opens: open a stock (5 tabs), tap News
      (index 4), the lookup returns "fund", `tabs` grows to 6 so `indexOf(NEWS)`
      becomes 5 — while `tabPositions` still holds the 5 entries the previous
      measure pass produced. `coerceAtLeast(0)` did not help; it guards the list
      SHRINKING, and this is the list GROWING.
      FIXED by extracting `DetailTabRow` with a custom indicator that clamps to
      `positions.lastIndex`, so a one-frame disagreement misplaces the indicator
      for one frame instead of killing the process.
      NOTE: the `FeedScreen` dedupe above is a real crash class but was NOT this
      crash. It is kept as a separate, defensible fix — not credited with this.
- [x] 3. Fixed, with regression tests: `DetailTabCrashTest` (reproduces the real
      crash through the actual composable) and `ChartAxisYearTest`.
- [x] 4. versionCode 65 / versionName 7.8.
- [x] 5. SHIPPED v7.8 (code 65): 806 tests 0 failures, signed with the same
      certificate as every previous release (verified on the artifact itself
      with `tools/verify-apk.sh`), committed to `releases/` and pushed. APK
      sent to Tj in the chat.

Ticking a box means: written, tested (name the test) and committed.

## Notes for whoever picks this up

- Do NOT tick 1 or 2 on a plausible-looking code reading alone. Both are
  reported from a real device; reproduce them in a test first.
- The video is NOT in the repo (uploads are per-session). Findings from it
  must be written down here or they are lost to the next session.
