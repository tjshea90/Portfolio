#!/bin/bash
# Cold-start: install the Android SDK. Takes ~5 minutes. Run once per container.
#
#   bash tools/setup-android-sdk.sh
#
# Adapted from the Cowork-container version of this script (it wrote
# local.properties to a hardcoded /home/claude/portfolio path; this one is
# portable to wherever the repo is checked out).
set -e
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
if [ -d "$ANDROID_HOME/platforms/android-36" ]; then echo "SDK already present"; exit 0; fi
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME"
curl -sSL -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q -o cmdline.zip -d cmdline-tools
mv cmdline-tools/cmdline-tools cmdline-tools/latest 2>/dev/null || true
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-36" "build-tools;36.0.0" "build-tools;35.0.0" >/dev/null
echo "sdk.dir=$ANDROID_HOME" > "$D/local.properties"
echo "SDK ready"
