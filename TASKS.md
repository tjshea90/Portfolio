# TASKS — the 2026-09-10 request, in Tj's words

> "review the resume function of this repository. I want to be able to use
> Claude code to make updates to this app, and when the Claude usage runs out
> and interrupts Claude, no data or progress should be lost. and I can resume
> hours later from a different Claude account and that Claude code session will
> be able to understand and know exactly where the last Claude session left off
> and continue to finish the project. any Claude interruption should not break
> the project. make sure this resume logic is well written and optimized for
> Claude code and GitHub and this project"

Findings from the review are below as fixes. Each was confirmed by running the
code in this container, not by reading it. Severity is about Tj's actual
requirement: survive an interruption, hand off cleanly to another account.

## HIGH — a cold session can lose the safety net or the briefing

- [x] 1. Hook install clobbers user settings. CLAUDE.md's FIRST ACTION is
      `cp tools/session-root-hooks.json /home/user/.claude/settings.json`,
      which unconditionally overwrites that file — the exact place a user's
      own permissions/env/hooks live. Replace with a merging, idempotent
      `tools/install-hooks.sh` that preserves everything else and backs up.
- [x] 2. Multi-repo hooks emit invalid JSON. session-root-hooks.json loops over
      every repo under /home/user and each `resume.sh` prints its own JSON
      object. CONFIRMED: two objects concatenated fail to parse
      ("Extra data: line 2 column 1"), so with a second repo present the whole
      SessionStart briefing is lost. Same for autosave's systemMessage. Emit
      exactly one JSON object per hook event.
- [x] 3. Checkpoint numbers run BACKWARDS across containers. `ckpt.sh` numbers
      from `git rev-list --count HEAD`, but this container's clone is SHALLOW
      (52 commits local vs 559 in history). CONFIRMED: CHECKPOINT.md says 580,
      the next ckpt would be numbered 53. A resuming session cannot tell which
      checkpoint is newer. Make the number monotonic and shallow-proof.

## MEDIUM — silent degradation

- [x] 4. The hook install is a manual step a session can skip, and if it skips
      it there is no autosave for the whole session. Make the tools/ entry
      points self-install the hooks, so the first hand-run of ckpt.sh or
      resume.sh repairs the safety net instead of just assuming it exists.
- [x] 5. `resume.sh`'s "is this checkout current with GitHub?" check silently
      no-ops when the branch has no upstream — which is the normal state of a
      Claude-Code-assigned `claude/<id>` branch. CONFIRMED: `@{u}` fails on
      this session's own branch, so behind/ahead both read 0. Fall back to
      `origin/<branch>` the way push.sh already correctly does.
- [x] 6. `toobig.sh` reports "? commit(s) not pushed" for the same reason.
      Reuse the robust check.
- [x] 7. CHECKPOINT.md never records which branch or commit it describes. A
      different account resuming cold should not have to guess where the work
      lives. Record branch + short SHA.

## LOW — context cost, and making the system verify itself

- [x] 8. TASKS.md carried a large "how to write a task" template that is
      re-printed into every session's context forever and duplicates CLAUDE.md.
      Keep this file to the job only. (Done by this rewrite — tick when the
      finished-job reset text is trimmed too.)
- [x] 9. Nothing tests the resume system itself. `ckpt.sh` already auto-runs
      `tools/test_*.sh`, so add `tools/test_resume.sh` — then every checkpoint
      from now on proves the handoff still works.

All nine are written, committed, and covered by `bash tools/test_resume.sh`
(13 checks, hermetic, ~1.7s) which `tools/ckpt.sh` now runs on every
checkpoint. Verified live in this container, not just by the tests:

- the SessionStart hook produced ONE parseable JSON briefing (10.4 KB)
- the PostToolUse hook stayed silent while healthy, and auto-checkpoint
  commits appeared in `git log` seconds after each edit (23 and counting)
- the PreCompact hook produced one valid systemMessage
- `bash tools/install-hooks.sh` run twice in a row changed nothing the
  second time, and stripped the duplicate legacy entry the old `cp` had left
  behind — which was live in the settings file and would have re-broken the
  briefing the moment a second repo appeared
- this session's own checkpoint numbered 581, not 55

**This job is finished.** Reset this file to "no active job" once Tj confirms,
so it stops costing context on every cold start (see CLAUDE.md).
