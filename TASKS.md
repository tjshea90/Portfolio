# TASKS — the 2026-09-10 request, in Tj's words

> "This is the flow I want for all future updates to this app. I wanna be able
> to go on any Claude account and use Claude code to tell what updates I want
> the app to have. I want Claude to code and make the updates, and then I want
> GitHub to compile and send the APK file. Make sure that if Claude runs out of
> usage and gets interrupted in the middle of an update, that the progress is
> not lost, and I can log in to a different Claude account and pick up where the
> first account left off with no data loss or nothing breaking or corrupt.
> Verify that all of this works well and is optimized for Claude code and
> GitHub, and that it doesn't waste any usage either on Claude or on GitHub."

Gaps found by checking the current state, not by assuming:

- [ ] 1. **CLAUDE.md is STALE and would misdirect a fresh session.** It still
      says `ship.sh` "tags the commit and pushes the tag". It does not — a tag
      push is 403 from a Claude container, which is why GitHub creates the tag
      itself. A new session on another account would follow that line, watch it
      fail, and have to rediscover the whole thing.
- [ ] 2. **A release interrupted between the build and `record-release.sh`
      leaves BUILDLOG.md missing an entry, and nothing says so.** BUILDLOG is
      what the NEXT release's versionCode is gated against, so a missed entry
      lets the next version reuse a shipped code — an APK that cannot install.
      `tools/resume.sh` must detect it (versionCode released as a tag but not in
      BUILDLOG) and print the exact command.
- [ ] 3. **GitHub storage waste.** Every full build uploads an 8 MB artifact
      for 90 days AND publishes the same APK as a Release asset. The Release is
      the permanent copy; the artifact is a duplicate that accumulates against
      a limited free-tier storage allowance.
- [ ] 4. **Claude context waste.** A finished job left in TASKS.md is re-read
      into every turn of every future session. Reset it when this is done.
- [ ] 5. Cover 1-3 in `tools/test_resume.sh` so they cannot rot.
- [ ] 6. Verify end to end by SIMULATING the interruptions, not by reasoning:
      cut off at each stage of a release and confirm a cold session recovers.

Ticking a box means: written, tested (name the test) and committed.
