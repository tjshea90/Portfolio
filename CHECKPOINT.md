# CHECKPOINT 593 — read me first, then TASKS.md

**Written:** 2026-09-10T21:12:31Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `75bb0c7` (this checkpoint is the commit after it)

## Just done
Investigated Tj's two reports from the screen recording and fixed both findings. CHART: the comparison is CORRECT - verified frame by frame from the video, every frame's 'pts vs SPY' equals stock minus SPY exactly (frames 4/10/16/26: -19.18, -73.08, -59.21, -68.28), and comparePercents and primaryPercents are passed the same baseIndex/baseT when zoomed so both lines share one zero at the window's left edge. Panning legitimately changes both figures because FIVE collapsed in 2024, so an earlier baseline means a much higher FIVE price and a lower SPY price. What was actually wrong is the X-AXIS: axisLabel used Fmt.shortDay ('MMM d') with no year, so a three-year window read 'Sep 9' to 'Sep 10' - indistinguishable from two consecutive days - and nothing on screen said the window had moved by years, which made honest rebasing look like the chart contradicting itself. Added Fmt.year, spansMoreThanAYear and a withYear flag on axisLabel (defaulted off so every existing caller and test is unchanged), applied to both axis ends and the scrub readout. NEWS CRASH: ruled out the stock page's news list, which is already deduped by the same newsKey it renders with. Found and fixed a REAL duplicate-key crash class in FeedScreen: 'shown' is keyed by FeedItem.id but was never deduped by it, relying instead on an upstream merge that de-duplicates by News.dedupeKey (normalised title, 70 alphanumerics) - a different question that disagrees on punctuation-heavy headlines, so two stories can survive the merge and share an id, which is exactly the crash CrashLog.kt exists for. Added ChartAxisYearTest with 7 cases including a fixture that actually collides on id. Bumped to versionCode 65 / v7.8.

## Do this next
Full unit suite is running in the background. When it is green, build and ship v7.8, then send the APK to Tj. ALSO ASK HIM FOR Settings -> Crash log: the FeedScreen fix is a real crash class but is NOT proven to be the crash he hit, and the device has the actual stack trace.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
```

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
