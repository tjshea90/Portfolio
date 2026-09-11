# SCREENER — model choice, decided before any work starts

Tj runs Claude Sonnet 5 by default. Some requests are worth the ten seconds
of friction to pause on *before* a single file is touched, so he can switch
to Opus and start the task under the right model — not so a session
second-guesses itself once it's already three edits in.

## How this actually runs

A `UserPromptSubmit` hook fires on every single message Tj sends — not only
at session start — and injects a short, fixed reminder into context before
that message is acted on:

- `tools/screener.sh` is the per-repo script (mirrors `resume.sh` /
  `autosave.sh` / `toobig.sh`: `--text` mode for the coordinator below, JSON
  mode for a direct call, always exits 0, never blocks the prompt).
- `tools/hooks/screen.sh` is the multi-repo coordinator, wired the same way
  `brief.sh` (SessionStart), `save.sh` (PostToolUse/Stop) and `big.sh`
  (PreCompact) already are — see `tools/hooks/lib.sh`. It forwards the raw
  prompt to every checkpoint-managed repo's own `tools/screener.sh` and
  combines the results into exactly one JSON object (`tools/hooks/emit.py`),
  for the same reason those three do.
- `tools/install-hooks.sh` installs it into the session root, merged in with
  everything already there — same mechanism, same safety, as the other
  three hooks. `tools/session-root-hooks.json` is the multi-repo template;
  this repo's own `.claude/settings.json` carries the direct, single-repo
  equivalent.

None of that infrastructure performs the actual judgment call — "is this
hard enough for Opus" is not something a hook script can grep for with any
confidence. What the hook mechanically guarantees is that the self-check
below actually happens, fresh, on every message, regardless of how long the
session has run or whether it has been compacted since. `tools/screener.sh`
also runs a cheap keyword grep as a hint (the list lives in the script
itself, not duplicated here, so it can't drift out of sync). That hint is
just a hint — a miss is not clearance. The criteria below, applied with
judgment, are what actually decide.

## The protocol — run this on every message, before touching anything

1. Read the request against the escalation criteria below.
2. No match → proceed normally on the current model. Nothing to announce.
3. Tj has already explicitly overridden the flag earlier in this same
   conversation for this same task ("proceed anyway", "use Sonnet", "go
   ahead") → respect that, proceed, don't re-ask every message.
4. Otherwise, a match → call `get_session` (the claude-code-remote MCP
   tool, `session_id` omitted) and read `session_context.model` and
   `external_metadata.last_served_model`.
   - Either already names an Opus-class model → proceed normally.
   - Still Sonnet, or unclear → **STOP.** No edits, no builds, no
     `ship.sh` / gradle / git push. Output the flag below in the chat and
     end the turn.

## The flag, when it fires

```
⚠️ SCREENER: this looks like it needs Opus, not Sonnet — <one line: which
criterion below, and why this specific request trips it>.

Nothing has been touched. Switch models, then resend the request (or say
"go") to start it under Opus.
```

Plain text and stopping there is enough — that is literally what Tj asked
for. `AskUserQuestion` with a real choice ("switch to Opus" vs. "proceed on
Sonnet anyway") is fine too when that choice is genuinely useful in the
moment, but it isn't required.

## Escalation criteria — flag these

- **Money-accuracy.** Cost basis, gains/losses, tax lots, portfolio
  valuation, the ledger, or any buy/hold/sell/recommendation scoring logic
  (`ResearchScore.kt`, `Research.consensus`, or any future recommendation
  feature) — anywhere a subtle math or logic error would misinform a real
  financial decision. This is the category Tj's own words point at directly
  ("it is very important that the advice... is well grounded").
- **Irreversible or hard to reverse.** The signing keystore
  (`app/sideload.jks`), `applicationId`, `versionCode` logic, the
  release/ship pipeline (`ship.sh`, `.github/workflows/android.yml`) —
  anything that could force a phone uninstall, which erases the portfolio.
- **Locked architecture.** Changing anything in `BRIEF.md`'s "Locked
  architecture decisions" table (market-data source order, caching policy,
  accounting method, and the rest) rather than working within it.
- **Ambiguous, high-judgment design.** A request that needs a real design
  decision before an approach can even be chosen — a new feature with no
  existing pattern in the app to mirror, or a tradeoff Tj hasn't already
  resolved for you.
- **A previous attempt already failed.** The same bug or task is being
  retried after an earlier session couldn't land it on Sonnet.
- **Security-sensitive.** Credential handling, `tools/secretscan.sh`'s own
  logic, anything touching what gets committed versus gitignored.
- **Tj says so.** His own words flag it — "important", "critical", "make
  sure this is right", "accuracy matters a lot", or similar — regardless of
  whether it also matches a category above.

## Stays on Sonnet — do not flag

- Routine UI work with an existing pattern already in the app to mirror
  (sizing, placement, colors, a tab or dialog like ones that already exist).
- Well-specified, narrow bug fixes and refactors scoped to one
  function/file.
- Checkpointing, housekeeping, docs, and tests — including this file and
  `tools/screener.sh` themselves.
- Implementing a design that has already been decided. The *design* step
  for a money-accuracy feature escalates (see above); wiring up the UI for
  an already-approved design usually does not.

## Why criteria plus judgment, not a hard gate

`tools/secretscan.sh` hard-blocks, because "does this look like a live API
key" is a shape a regex can decide with high confidence, and a false
positive there just costs a re-run. "Does this need Opus" has no such
shape — a hard bash gate here would either fire on every message (nearly
everything touches *some* keyword) or miss the genuinely hard, oddly-worded
request the keyword list never anticipated. So the hook's job is narrower
and more reliable than that: guarantee the check happens, every single
time, and hand the actual judgment to whichever model is running — the same
"app scores, Claude explains" split already used for `ResearchScore`'s own
reasoning (Round 66 decision).
