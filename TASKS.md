# TASKS — the current job

**There is no active job right now.** v7.9 (versionCode 66) is shipped — adds
the per-holding BUY/HOLD/SELL recommendation tab (left of News), and the
model screener (SCREENER.md) that now runs on every message. See
`BUILDLOG.md` for the full release history.

## Waiting on Tj

- [ ] Tell Claude what the app should do next.

## The flow, verified 2026-09-11 (details in CLAUDE.md)

Tj describes what he wants -> Claude codes, tests and checkpoints -> `ship.sh`
gates and pushes -> Claude triggers the workflow through the GitHub API ->
GitHub compiles, signs, verifies the certificate and publishes the Release ->
Claude sends Tj the APK -> `tools/record-release.sh` writes BUILDLOG.

The model screener (SCREENER.md) now runs on every message before that flow
starts: money-accuracy logic, irreversible actions, locked architecture,
ambiguous design, a previously-failed fix, security, or Tj's own words
flagging something as important all pause for an Opus check before any code
is touched, unless Tj has already said to proceed on Sonnet.
