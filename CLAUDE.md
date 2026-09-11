# Portfolio — working agreement

Tj's personal Android stock/ETF portfolio tracker (Kotlin + Jetpack Compose,
package `com.tj.portfolio`), sideloaded on his phone. Worked on across Claude
Code sessions — a new session may pick this up on a different device, a
different account, or simply after a previous session's usage ran out
mid-task. Everything below exists to make that handoff lossless.

The handoff mechanism (autosave hook, deliberate checkpoint, secret scan,
milestone ship gate) is a Claude-Code-native pattern proven on another
private repo of Tj's with the same requirement — resumable across multiple
Claude accounts sharing one GitHub repo. `ship.sh`'s specific gates are this
project's own (Gradle build + the full Kotlin unit suite), not borrowed.

**The project's own standing rules** (the irreplaceable signing keystore,
the `tools/checkinit.py` invariant, the pinned toolchain, the build traps
that have cost real time before) are printed at session start by
`bootstrap.sh` and written in full in `BRIEF.md`. They are not repeated here.
Do not violate them.

This project moved here from a Cowork container, where it was worked for 66
rounds using a round-based checkpoint tarball system (`RESUME.md`,
`state.json`, `ck`/`ck.py`, `watchdog.sh`). That system is retired — its
mechanics are exactly what `tools/` below replaces — but its full history
(559 commits) was imported intact, so `git log` still carries every round.
`audits/round66/` holds the last round's detailed findings.

## FIRST ACTION OF EVERY SESSION — install the hooks, don't assume they exist

**CONFIRMED 2026-09-10: this repo's own `.claude/settings.json` does NOT get
loaded by Claude Code in this environment** (Claude Code on the web, a
multi-repo container under `/home/user`). `CLAUDE_PROJECT_DIR` is unset, and
the harness only checks ONE project root for hooks — this repo being in a
subdirectory of it isn't enough. Verified by direct test: an Edit-tool
change produced no autosave commit across several subsequent tool calls,
and writing the identical hook config to the session root instead made it
fire immediately.

**So, before anything else this session, run this once:**
```bash
bash tools/install-hooks.sh
```
It finds the session root itself, MERGES the hooks into whatever settings
file is already there, and says what it did. Idempotent — run it any time.

It used to be `cp tools/session-root-hooks.json /home/user/.claude/settings.json`.
Do not go back to that: it is an unconditional overwrite of the one file the
user's own permissions, env and other projects' hooks live in, run by every
session, so the first time anything else is written there the next session
deletes it without a word. `install-hooks.sh` only ever replaces entries that
are ours.

**If you forget, two things now catch it for you** — `tools/ckpt.sh` and
`tools/resume.sh` both repair the hooks before they do anything else. So a
session that never reads this section still gets the safety net back the
moment it checkpoints. That is a backstop, not a reason to skip the step:
until something calls one of them, nothing is being saved.

Verification is NOT instant — confirmed 2026-09-10 that firing can lag a tool
call or two behind the edit rather than commit synchronously with it. So check
over a few tool calls, not just the next one:
```bash
# after 2-3 real edits, in a LATER tool call:
git log --oneline -3   # expect fresh "auto-checkpoint:" commit(s) in there
```
If several edits go by with NONE appearing, the hooks aren't firing at all —
fall back to running `bash tools/ckpt.sh "did" "next"` after every step BY
HAND for the rest of the session, and say so plainly; that becomes the only
safety net. Don't conclude "broken" from one immediate check turning up
nothing — that was a false alarm once already. If Claude Code changes how
it scopes hooks in this environment, this whole section becomes
unnecessary — but don't assume that without re-running the check above.

**Whether it is currently installed, without changing anything:**
```bash
bash tools/install-hooks.sh --check && echo installed || echo MISSING
```

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

## `main` is the canonical branch — always work there unless told otherwise

Claude Code can assign a session a differently-named local branch (this repo
has seen `claude/<session-id>`-style names). That's fine *within* a session,
but it must never become the only place real work lives: a session or
account that opens this repo without rediscovering that exact name lands on
whatever `main` has, and if `main` is stale, that looks exactly like data
loss even though nothing was actually deleted. This happened once already —
565 commits sat on a feature branch while `main` still showed the original
README.

So: `tools/push.sh` fast-forwards `origin/main` to match on every successful
push, automatically, regardless of which branch is checked out — that's the
actual fix, and it needs no action from you. `tools/resume.sh` reports
`origin/main`'s sync status on every session start as a sanity check on that
automation; if it ever says main is behind, that's a bug in push.sh to fix,
not something to work around by hand. If you're starting genuinely fresh
(not continuing a specific session) and have a choice, check out `main`.

## When Tj asks for something new

**Write the request into `TASKS.md` in his own words, as unticked `[ ]`
boxes, and checkpoint it before writing any code.** Until it is on disk the
job exists only in a chat window that no future session can ever see. If
usage runs out before the first checkpoint, the next session inherits the
work but not the knowledge of what was asked — and it cannot ask him, because
from his side he already explained it.

Tick a box only when it is written, tested (name the test, if one applies)
and committed. The next session will not re-verify a ticked box.

## Model screener — flag before working, not after

Tj runs Claude Sonnet 5 by default. A `UserPromptSubmit` hook
(`tools/screener.sh`, wired the same way as the other three hooks — see
`tools/hooks/screen.sh`) fires on every single message he sends, not just
at session start, and reminds you to weigh the request against
`SCREENER.md`'s Opus-escalation criteria before touching anything. That
reminder is mechanical and cheap; the actual judgment call is yours, every
time, freshly — don't rely on having "already decided" earlier in a long
session, since this is exactly the kind of check compaction and time can
erode.

If a request matches — money-accuracy logic, anything irreversible (the
keystore, the release pipeline), a locked architecture change, a genuinely
ambiguous design decision, a fix already tried and failed, or Tj's own
words flagging it as important — confirm the current model with
`get_session` (session_id omitted) and, if it isn't already Opus-class,
**stop before any edit, build, or `ship.sh`/git push** and say so, so he can
switch models and restart the same request. Don't do a little work "just to
be safe" first — the whole point is to flag before, not after.

Full protocol and criteria: `SCREENER.md`.

## Saving work — three levels (see "FIRST ACTION" above before trusting level 1)

**1. Automatic (hooks — when they fire).** `tools/autosave.sh` commits and
pushes after every file edit and every bash command, and again on Stop. It
has no gate and runs no tests: a broken half-edit that is committed is
recoverable, the same edit uncommitted dies with the session. This is what's
*supposed to* survive a usage cap landing mid-change, when it's running.

**2. Deliberate — `bash tools/ckpt.sh "what I just did" "what comes next"`.**
**Run this after every completed step, not at the end of the session** —
right now, this is doing the job level 1 was supposed to do, not just
supplementing it. It runs the FAST checks only (`tools/checkinit.py`
and anything under `tools/test_*` — which now includes
`tools/test_resume.sh`, 13 hermetic checks that prove the handoff system
itself still works, in about a second), rewrites `CHECKPOINT.md`, commits and
pushes. It also repairs the hooks if they are missing. Skipping it is how a handoff loses everything since the last run,
not just intent.

**3. Milestone — `bash ship.sh "note"`.** Cuts a release. It runs the full gate
HERE (`tools/checkinit.py`, the whole Gradle unit suite, and a versionCode
strictly higher than every code in `BUILDLOG.md`), then pushes the commit.
**GitHub builds and signs the APK**, not this container — see below. It does
NOT push a tag: that is 403 from a Claude container, so GitHub creates the tag
itself. A red suite must never reach a runner, which is why the suite runs
locally first even though the workflow runs it again.

`bash ship.sh --local "note"` is the fallback for when GitHub is unavailable:
it builds and signs here instead, and needs the keystore present.

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

## Releasing — GitHub builds every APK, Claude writes the code

Tj's rule, 2026-09-10: **GitHub makes all future APKs; Claude codes them.**

```bash
# 1. bump versionCode AND versionName in app/build.gradle.kts
bash ship.sh "what changed this release"      # gates HERE, pushes the commit
# 2. watch https://github.com/tjshea90/Portfolio/actions until the run is GREEN
bash tools/record-release.sh v7.9 "what changed this release"
```

Then **Claude triggers the build through the GitHub API** — not git:
`mcp__github__actions_run_trigger`, `run_workflow`, `android.yml`, on `main`,
with `full_build: "true"`. The run builds, signs with the keystore held in
**GitHub Secrets**, verifies the certificate on the APK it just produced,
creates the `v*` tag server-side and publishes it under Releases. Claude then
downloads that APK and sends it to Tj in the chat, so he never has to go
looking for it.

**Why Claude triggers it instead of pushing a tag.** `git push origin v7.9`
returns `RPC failed; HTTP 403` from a Claude container: the session's egress
policy allows `refs/heads/*` and refuses `refs/tags/*`. Measured, not guessed,
and the proxy's own docs say not to retry or route around a 403. A tag push
still works for Tj from his own machine and triggers the same workflow.

**Why the two steps.** `BUILDLOG.md` is load-bearing: both `ship.sh` and the
workflow gate the next versionCode against it, so a line in it is a claim that
a release EXISTS. `ship.sh` finishes before the build does, so recording it
there would make the file lie whenever a run failed. `tools/record-release.sh`
writes the line only once the run is green. **Never write that line by hand,
and never before the run is green.** If a session is interrupted between the
build and the recording, `tools/resume.sh` says so on the next start and prints
the command — that gap is detected, not remembered.

**The APK is no longer committed.** `releases/` held ~8 MB per version that
every future session cloned before reading a line of code; GitHub Releases hold
the binaries now. `BUILDLOG.md` is the record.

**The keystore is no longer needed to release.** GitHub signs, so a fresh
container on any Claude account can cut a full signed release without
`app/sideload.jks` ever being present. It is still required for
`ship.sh --local`, for `:app:assembleRelease` here, and it is still the one
thing that must never be regenerated (BRIEF.md).

**Building locally at all** (tests, a debug APK, `--local`): use
`bash tools/gradle.sh <task>` and never `./gradlew` directly — it installs the
Android SDK if this container has none (~5 min, once) and verifies the
keystore. Read BRIEF.md's build traps before fighting a build failure; several
that look like code problems are not.

## Project rules

See `BRIEF.md` for the full list: the irreplaceable signing keystore, the
`tools/checkinit.py` invariant, the pinned toolchain versions, the build
traps, and the locked architecture decisions (market-data source order,
caching policy, accounting method, and why). `bootstrap.sh` prints the
short version at every session start.
