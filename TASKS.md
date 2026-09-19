# TASKS — the current job

## Tj's request, 2026-09-19 (his own words)

> Permanently Get rid of the opus screener and just use whatever model I'm
> using at the time. Make sure you can get rid of the screener without
> breaking anything

## Screening

Explicit tooling-removal request, scoped to housekeeping/tests — the class
SCREENER.md itself carved out as "stays on Sonnet." No escalation.

## What "the screener" was

A `UserPromptSubmit` hook (`tools/screener.sh`, coordinated across repos by
`tools/hooks/screen.sh`) fired on every message and told the session to
weigh the request against `SCREENER.md`'s Opus-escalation criteria, stopping
before any edit if a flagged request wasn't running on an Opus-class model.

## Done

- [x] Deleted `tools/screener.sh`, `tools/hooks/screen.sh`,
      `tools/test_screener.sh`, `SCREENER.md`.
- [x] Removed the `UserPromptSubmit` block from both hook templates:
      `tools/session-root-hooks.json` (multi-repo) and this repo's own
      `.claude/settings.json` (direct). Both re-validated as parseable
      JSON.
- [x] Cleaned the stray reference to `screen.sh`/`tools/screener.sh` in
      `tools/hooks/lib.sh`'s comment.
- [x] Replaced CLAUDE.md's "Model screener — flag before working, not
      after" section with a one-line "Model" note: no screener, work under
      whichever model the session is actually running.
- [x] Confirmed `install-hooks.sh` (unchanged — it strips any hook entry
      whose command matches `tools/hooks/` or is tagged
      `portfolio-checkpoint-hooks` when the template no longer defines that
      event) actually removes the *already-installed* `UserPromptSubmit`
      entry from the live session-root settings file, not just from the
      templates in git. Verified: ran it, `UserPromptSubmit` key is gone
      from `/home/user/.claude/settings.json`'s `hooks`.
- [x] Ran `python3 tools/checkinit.py` and all of `tools/test_*.sh`
      (now just `test_resume.sh`, since `test_screener.sh` is deleted) —
      all green, nothing else referenced the screener (`BRIEF.md`,
      `bootstrap.sh`, `resume.sh`, `ckpt.sh` were all clean already).
- [x] `audits/` and this file's own prior (now-superseded) sections were
      left untouched — they're historical record, not live wiring.

## Do this next

Nothing pending — await Tj's next request.
