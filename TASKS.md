# TASKS — the 2026-09-10 request, in Tj's words

> "make it so whenever Claude is ready to build a new release apk it
> automatically does the setup-android-sdk or whatever else it needs to do to
> make an APK. I thought GitHub makes the apk files outside of Claude? check
> this. whatever method is most efficient, just make it so it always
> automatically starts the tools it needs when it is ready to build the APK.
> this should be permanent"

## The GitHub question — Tj was right that it CAN; this repo just never had it

**Decision (Tj, 2026-09-10): add full signed release CI.** He is correct that
GitHub Actions builds APKs; the finding below was only ever about this
repo's configuration, not about what GitHub can do. Added
`.github/workflows/android.yml`.

## What was actually true of this repo

There is **no CI in this repo and there never has been**: no `.github/`
directory, and `git log --all -- .github` is empty across all 559+ commits.
Every APK so far, including the shipped v7.7, was built by `ship.sh` inside a
Claude container and committed to `releases/`. So nothing is being built
outside Claude today.

Verified this container CAN provision itself (so in-container is the
efficient answer, not CI):

- `dl.google.com` SDK zip → 200
- `services.gradle.org` (gradle 8.14.3) → 200
- `repo1.maven.org` → 200
- 30 GB free disk; SDK needs ~2-4 GB

The one real cost stands and Tj accepted it: the runner needs
`app/sideload.jks` as a GitHub Secret. Note it also applies to DEBUG builds
here — `app/build.gradle.kts` signs both build types with the sideload key,
so the usual "start with assembleDebug, no secrets needed" advice does not
work in this repo.

The argument FOR it, which the first pass under-weighted: with CI, Tj can get
an installable APK with **no Claude session at all** — which is the same goal
as the whole resume system.

## The work

- [x] 1. `tools/ensure-build-env.sh` — one idempotent entry point that brings
      the container to a buildable state: Android SDK (installs it if absent),
      `local.properties`, `ANDROID_HOME`, java, and the signing keystore
      (verified by fingerprint — this one cannot be auto-provisioned, so it
      must fail loudly and clearly). Near-instant when already ready.
- [x] 2. Wire it into `ship.sh`, replacing the current hard-fail that just
      tells a human to go run `tools/setup-android-sdk.sh` by hand.
- [x] 3. `tools/gradle.sh` — a wrapper so ANY gradle work auto-provisions, not
      just a full ship. This is what makes it "always", per the request.
- [x] 4. Make it permanent: committed to the repo, and named in `CLAUDE.md` +
      `bootstrap.sh` so every future session on any account uses it by default
      instead of the manual step.
- [x] 5. Cover it in `tools/test_resume.sh`.
- [x] 6. Prove it end to end by actually provisioning this container and
      running a real Gradle build — not by reading the script.
      SDK HALF DONE AND PROVEN: from a cold container, `ensure-build-env.sh`
      installed the SDK by itself in one step (776 MB, android-36,
      local.properties written), and a second run is a 0.26s no-op.
      SIGNING HALF ALSO DONE: `bash tools/gradle.sh :app:assembleRelease`
      succeeded in 4m49s and `apksigner` confirms the APK carries
      2e8c3847...f396a9f2 — the certificate the phone accepts.

## GitHub Actions (added on Tj's decision)

- [x] 7. `.github/workflows/android.yml` — tests, signed release APK, signature
      verified on the artifact, uploaded as an artifact and attached to a
      GitHub Release on `v*` tags. Triggers on TAGS AND MANUAL DISPATCH ONLY:
      autosave mirrors every commit to main (48 in one two-hour session,
      measured), so a push trigger would start a run every few seconds and
      burn the 2,000 free private-repo minutes almost immediately.
- [x] 8. `tools/verify-apk.sh` — checks the built ARTIFACT's certificate, not
      just the keystore that went in. `tools/checkkeystore.sh --expected`
      publishes the fingerprint so CI and local share one copy of it.
- [x] 9. **DONE — Tj added it.** (was: TJ'S ONE MANUAL STEP — nothing works until this is done.** Add the
      repository secret `SIGNING_KEYSTORE_BASE64` (Settings -> Secrets and
      variables -> Actions -> New repository secret) with the base64 of
      `app/sideload.jks`. Claude cannot create secrets. Until then the
      workflow fails at the "Restore the signing keystore" step by design,
      with a message saying exactly this.
- [x] 10. First CI run DONE — gates-only dispatch succeeded in 18 seconds for
      0 billable minutes. checkinit ok, keystore restored and fingerprint
      matched, versionCode correctly warned (64 vs 64 shipped), keystore wiped
      from the workspace. Bumped actions/checkout and actions/setup-java to v5
      after the run warned they were deprecated on Node 20.
- [ ] 11. The FULL build path is still unverified on a runner — only the gates
      have run there. It needs a versionCode bump past 64 first, so it will be
      proven by the next real release, not before. Expect the Android SDK
      package step to be the likeliest thing to need fixing.

Ticking a box means: written, tested (name the test) and committed.
