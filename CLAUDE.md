# Portfolio — working agreement

Tj's personal portfolio site. Worked on across Claude Code sessions — a new
session may pick this up on a different device, a different account, or
simply after a previous session's usage ran out mid-task. Everything below
exists to make that handoff lossless.

This handoff system is adapted from the fantasy-football tracker repo, which
worked the same problem across three Claude accounts. The mechanics
(autosave hook, deliberate checkpoint, secret scan) are identical; the
project-specific parts (the Android build gate, the scoring-engine rules)
were dropped because they don't apply here.

## Starting a session

A `SessionStart` hook has already run `tools/resume.sh`, which pulled the
latest from GitHub and printed `CHECKPOINT.md`, `TASKS.md` and the rules
below into your context. **Do not re-plan, do not re-read finished work.**
Continue from **Do this next** in `CHECKPOINT.md`, or the first unticked
`[ ]` in `TASKS.md`.

**If you did not see that briefing, run `bash tools/resume.sh` before doing
anything else, and tell Tj it did not fire** — it means the hook is not
running, and the automatic saving below almost certainly is not either, so
this session is working without a safety net.

Read the two warnings it can raise:

- **"INTERRUPTED MID-CHANGE"** — the last session was killed by a usage cap
  part-way through a step. The tree is clean, but only because a hook
  committed a half-written change. `CHECKPOINT.md` describes the state
  *before* that, so it is stale. Run the `git diff` it names, finish that
  change, checkpoint it — then start anything new.
- **"UNCOMMITTED WORK IS PRESENT"** — the same thing, one step worse: not even
  the hook got to it. `git diff` is what was in flight.

## When Tj asks for something new

**Write the request into `TASKS.md` in his own words, as unticked `[ ]`
boxes, and checkpoint it before writing any code.** Until it is on disk the
job exists only in a chat window that no future session can ever see. If
usage runs out before the first checkpoint, the next session inherits the
work but not the knowledge of what was asked — and it cannot ask him, because
from his side he already explained it.

Tick a box only when it is written, tested (name the test, if one applies)
and committed. The next session will not re-verify a ticked box.

## Saving work — two levels, and you are responsible for the second one

**1. Automatic (hooks — happens without you).** `tools/autosave.sh` commits
and pushes after every file edit and every bash command, and again on Stop.
It has no gate and runs no tests: a broken half-edit that is committed is
recoverable, the same edit uncommitted dies with the session. This is what
survives a usage cap landing mid-change. You do not call it.

**2. Deliberate — `bash tools/ckpt.sh "what I just did" "what comes next"`.**
**Run this after every completed step, not at the end of the session.** The
autosave hook can preserve your *files* but it cannot know your *intent* —
"what comes next" is the one thing no diff can reconstruct and the one thing
the next session most needs. It runs whatever test suite exists (or records
honestly that none does yet), rewrites `CHECKPOINT.md`, commits and pushes.
Skipping it is how a handoff loses a day even though every file was saved.

There is no milestone-level `ship.sh` here (the fantasy-football tracker's
is an Android release gate — APK dex checks, manifest agreement — none of
which applies to a portfolio site). If the project later grows a real
build/deploy process worth gating on, add one then; don't invent one before
there's anything to gate.

## Before your usage runs out

You will usually get no warning, which is why level 2 is per-step rather than
per-session. If you *do* notice you are running low, spend the remaining
budget on `tools/ckpt.sh` with an honest, specific "what comes next" — not on
one more edit.

## Large sessions

A `PreCompact` hook (`tools/toobig.sh`) fires when the conversation has grown
enough to auto-compact. Compaction rewrites earlier messages rather than
shrinking what a warm cache already covers, so the cheaper move is usually:
checkpoint, then start a fresh session — `tools/resume.sh` rebuilds
everything a session needs from GitHub in well under a hundred lines.

## Credentials — never commit one

`tools/secretscan.sh` blocks the autosave hook from committing anything
shaped like a live credential (API keys, GitHub tokens, private keys). If it
trips, remove the credential — do not bypass it. A key that reaches a commit
has to be rotated, not deleted: GitHub keeps commit objects reachable by SHA
even after history is rewritten. Keep real secrets in a local, gitignored
`.env` — see `.gitignore`.

## Project rules

*(none recorded yet — the stack, hosting/deploy target, and content
structure haven't been decided. Add them here once they are, and
`bootstrap.sh` will start printing them into every session's briefing
automatically.)*
