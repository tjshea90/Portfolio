# TASKS — the current job, in Tj's words

**There is no active job right now.** v7.7 (versionCode 64) is shipped —
797 tests, 0 failures. The last request (round 66, an audit/fix sweep) is
complete; its full findings are in `audits/round66/` and `git log`.

This round also migrated the project off the old Cowork round-based
checkpoint system (`ck`/`ck.py`/`RESUME.md`/`state.json`/`watchdog.sh`) and
onto this GitHub repo with the Claude-Code hook-based checkpoint system in
`tools/` — see `CLAUDE.md` and `BRIEF.md`.

## When Tj asks for something new

Write it HERE FIRST, in his own words, as unticked boxes — before writing any
code. Until it is on disk the job exists only in a chat window that no future
session can see, and a usage cap landing before the first checkpoint loses
not just the work but the knowledge of what was asked.

```
# TASKS — the <date> request, in Tj's words

> "<paste what he actually said, verbatim>"

- [ ] 1a. <first step>
- [ ] 1b. <second step>
```

Ticking a box means: written, tested (name the test — `tools/checkinit.py`,
`./gradlew testDebugUnitTest`, or a specific new test) and committed. **Never
tick a box you have not verified** — the next session will not re-check it.

When a job is finished, it's already archived by nature of `git log` and
(for a real audit round) `audits/`; just reset this file. This file is
printed into every session briefing, so a finished job left here is re-read
at cost on every cold start, forever.

## Waiting on Tj

- [ ] Tell Claude what to work on next.
