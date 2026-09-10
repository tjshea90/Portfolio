# TASKS — the 2026-09-10 request, in Tj's words

> "make it so whenever Claude is ready to build a new release apk it
> automatically does the setup-android-sdk or whatever else it needs to do to
> make an APK. I thought GitHub makes the apk files outside of Claude? check
> this. whatever method is most efficient, just make it so it always
> automatically starts the tools it needs when it is ready to build the APK.
> this should be permanent"

## The GitHub question — checked, and the answer is no

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

GitHub Actions remains possible but is NOT more efficient here, and has one
real cost: it needs `app/sideload.jks` uploaded into GitHub Secrets, moving
the irreplaceable keystore out of Tj's sole custody. Left as a decision for
Tj, not built.

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
- [~] 6. Prove it end to end by actually provisioning this container and
      running a real Gradle build — not by reading the script.
      SDK HALF DONE AND PROVEN: from a cold container, `ensure-build-env.sh`
      installed the SDK by itself in one step (776 MB, android-36,
      local.properties written), and a second run is a 0.26s no-op.
      SIGNING HALF IN FLIGHT: `bash tools/gradle.sh :app:assembleRelease` is
      running. Tick this only once the APK exists AND `apksigner`/`keytool`
      confirms it carries certificate 2E:8C:38:47:...:F3:96:A9:F2.

Ticking a box means: written, tested (name the test) and committed.
