# TASKS — the current job

**There is no active job right now.** v7.8 (versionCode 65) is shipped, and
GitHub now builds, signs and publishes every release — see CLAUDE.md's
"Releasing".

## Waiting on Tj

- [ ] Tell Claude what the app should do next.

## The flow, verified 2026-09-10 (details in CLAUDE.md)

Tj describes what he wants -> Claude codes, tests and checkpoints -> `ship.sh`
gates and pushes -> Claude triggers the workflow through the GitHub API ->
GitHub compiles, signs, verifies the certificate and publishes the Release ->
Claude sends Tj the APK -> `tools/record-release.sh` writes BUILDLOG.

Interruption at any of those points was SIMULATED, not assumed, and each one
recovers on the next session start. Works from any Claude account.
