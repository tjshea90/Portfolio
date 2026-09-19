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

## Model

There is no model screener. Work every request under whichever model this
session is actually running — don't stop to ask Tj to switch.

## Testing on request — "light tests" and "full tests"

Tj triggers these two protocols by saying the phrase, at any point, in any
session — including a brand-new session with zero other context. Recognize
any wording close to "light test(s)"/"light testing" or "full test(s)"/
"full testing"/"comprehensive tests" and run the matching protocol below.
No further explanation from him is needed or expected.

### Light tests

Low Claude-effort. Run once whatever Tj most recently asked for is
otherwise complete.

1. Run the existing automated floor: `python3 tools/checkinit.py` and
   `bash tools/gradle.sh testDebugUnitTest` (the full Kotlin unit suite —
   it's fast; always run it, it is not optional).
2. Read the diff since this session's work started (against
   `CHECKPOINT.md`'s "builds on" commit, or the last ship) for obvious
   bugs and UI-logic mistakes — state handling, null/empty cases,
   off-by-one, a Compose recomposition or layout error. There is no
   emulator or device in this container, so "UI issues" means reading the
   changed Compose code carefully for logic errors, not a live visual
   check — say so if asked why nothing was screenshotted.
3. Grep for other callers/usages of anything this session changed (a
   function signature, a data-class field, a scoring input, a cache key)
   to confirm nothing else in the app now reads stale or mismatched data —
   this is the "did it corrupt some other part of the app" check.
4. Fix anything found.
5. If there were any major findings, after fixing them, repeat this same
   light pass once more (steps 1-3) to confirm the fix didn't break
   anything else. Don't loop beyond that second pass.
6. Checkpoint the result (`bash tools/ckpt.sh "did" "next"`), same as any
   other completed step.

### Full tests

No budget or time limit — best effort, release-quality bar. This is a
standalone deep audit of the whole app, not just the current diff.

1. Run `python3 tools/checkinit.py` and the full Gradle unit suite
   (`bash tools/gradle.sh testDebugUnitTest`) first, as the floor.
2. Audit the whole app for:
   - **Bugs / breakage** — anything broken or corrupted by recent changes,
     anywhere in the app, not only files touched this session.
   - **Code and UI quality** — simplification, consistency, intuitiveness.
   - **Network efficiency** — redundant fetches, anything that could be
     served from the existing `http_cache` layer instead of a fresh
     request, and violations of BRIEF.md's "Locked architecture
     decisions" source order (Yahoo primary → Finnhub → Stooq, batched
     quotes, etc).
   - **Caching and data retention** — nothing the user entered or the app
     already fetched should be silently lost; check the persistence paths
     (`SQLiteOpenHelper`, the ledger, the day-trading log) for gaps.
   - **Scoring/engine logic** — `ResearchScore`, `Recommend`, the
     day-trading system, cost-basis/accounting (FIFO default) — confirm
     they still work as designed and still match BRIEF.md's locked
     decisions.
   - **Battery / resource use** — background work actually stops on
     `ON_STOP` and resumes on `ON_START` (the `fgScope` pattern), no
     runaway polling, nothing left running (timers, repeated fetches)
     when the app isn't in use.
3. For a pass this size, prefer splitting the audit across parallel
   subagents by subsystem (recommendation/scoring, day-trading,
   network/caching, UI) — see `audits/round66/` and the checkpoint history
   ("4-way parallel audit") for the pattern already used successfully on
   this project — then reconcile and fix the findings yourself.
4. Fix everything found. This is an improvement pass, not just a report.
5. Once fixes are in, re-run the unit suite and re-check anything a fix
   touched.
6. Checkpoint as work completes. This is exactly the kind of session that
   should end with `bash ship.sh "note"` once Tj confirms he wants the
   result released, per "Releasing" below.

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
creates the `v*` tag server-side and publishes it under Releases.

**Tj's rule, 2026-09-11: Claude does NOT send the APK.** He downloads it
himself from the Release page — he already has GitHub access and does not
need it relayed through chat. Do not attempt to: this session's GitHub MCP
tools cover API/metadata calls but not downloading a private repo's release
ASSET BYTES, and a raw-`curl`/git-credential workaround for that gap was
tried once (2026-09-11) and correctly blocked by the harness as credential
exploration — treat that as settled, not a bug to keep poking at. Just
confirm the Release is published (`get_release_by_tag` is enough) and move
on.

**Tj's rule, 2026-09-19: ship every future update automatically, and always
post the link — don't wait to be asked.** Once a session finishes a
meaningful unit of work (a fix, a feature, an audit-and-fix pass — the same
granularity that used to wait for Tj to say "ship it"), run the full
release flow above on its own: bump versionCode/versionName, `ship.sh`,
trigger the build, confirm green, `record-release.sh`. Then post the
Release page link in chat (`https://github.com/tjshea90/Portfolio/releases/tag/vX.Y`,
or `get_release_by_tag`'s `html_url`) without being asked. This does NOT
reopen the 2026-09-11 rule above — "the link" is the Release page/asset
URL, never the raw APK bytes, and that technical block is unrelated and
still stands. Do not auto-ship a trivial or purely internal change (a
checkpoint-worthy fix mid-task, a doc/comment tweak, a TASKS.md update) —
use the same judgment that previously decided when Tj would have said
"ship it"; when genuinely unsure whether a change is release-worthy, ask
rather than either spamming small releases or silently skipping a real one.

**2026-09-19: the Release link can 404 for Tj — that's expected while the
repo is private, not a broken link.** `tjshea90/Portfolio` is a PRIVATE
repo (confirmed via `search_repositories`'s `visibility` field). GitHub
returns 404, not 403, to anyone viewing a private repo's Release page
without access — verified by fetching the exact same URL unauthenticated
and getting the same 404 a correctly-published release gets. If Tj reports
a 404 on a link Claude posted, the fix is not to regenerate the link (it's
already right — confirm with `get_release_by_tag` if unsure) but to check
whether the repo is still private: if so, he needs to be logged into the
`tjshea90` GitHub account in the browser he's opening it from. **Claude has
no tool that can change a GitHub repo's visibility** — no `update_repository`
call in the GitHub MCP toolset here, no `gh` CLI, no raw API access. That
switch is only in GitHub's own Settings → General → Danger Zone page, and
only Tj can flip it.

**Before Tj (or anyone) makes this repo public: the signing keystore is in
old git history.** `app/sideload.jks` was committed in two pre-2026-09-10
commits (`f00c950`, `5304cd6` — the Cowork-import era) and later dropped
from the working tree, but the blob is still fully fetchable from those
SHAs. BRIEF.md's "keystore is irreplaceable, never in git" model assumed
the repo stayed private; it does NOT account for the key already being in
history. Making the repo public exposes it immediately and permanently
(public GitHub repos get scraped for secrets within minutes) — and it
can't be rotated afterward without forcing Tj to uninstall the app and
lose his portfolio data (BRIEF.md). If a future session is asked to make
this repo public, or if this comes up again: surface this risk before
acting, the same way this session did (`git log --all --diff-filter=A
--name-only -- '*.jks'` finds it in seconds) — do not just proceed because
a CLAUDE.md line here says "ship automatically," since that policy is
about releases, not repository visibility. Tj was told this on 2026-09-19
and chose to accept the risk for going public; that decision is recorded
in TASKS.md, but a session should still mention it again rather than treat
consent given once, for a different request, as blanket permission. The
keystore blob can be purged from history with a `git filter-repo`-style
rewrite (force-push required) if asked; nobody has done this yet.

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
