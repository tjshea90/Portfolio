#!/usr/bin/env bash
# gradle.sh — run Gradle, provisioning the container first if it needs it.
#
# USE THIS INSTEAD OF ./gradlew. It is the same Gradle with one difference:
# a container that has never built before installs the Android SDK by itself
# rather than failing and asking a human to run a second command. Once the SDK
# is there this adds ~0.3s.
#
#   bash tools/gradle.sh testDebugUnitTest
#   bash tools/gradle.sh :app:assembleRelease
#
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

# A release task must not proceed on a missing or wrong keystore; anything
# else (tests, lint, debug) may. Decided from the task names actually passed.
MODE=""
case " $* " in *" :app:assembleRelease "*|*" assembleRelease "*|*" bundleRelease "*) MODE="--release" ;; esac

if ! bash tools/ensure-build-env.sh $MODE; then
  echo "  FAIL  build environment is not ready — not running Gradle."
  exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
exec ./gradlew "$@"
