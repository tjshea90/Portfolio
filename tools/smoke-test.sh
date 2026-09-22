#!/usr/bin/env bash
# smoke-test.sh — install the RELEASE APK on a running emulator, launch it, fuzz it, and fail
# on any crash. Run by .github/workflows/android.yml before a release is published.
#
#   bash tools/smoke-test.sh app/build/outputs/apk/release/app-release.apk
#
# WHY IT EXISTS (2026-09-22b). The release build is now shrunk and optimised by R8, which is
# what a Compose app needs to be fast on a budget phone - and it is also the one build step
# that can break an app that the debug build and every unit test say is fine (anything R8
# decides is unused is gone). This app has no reflection, which is how R8 normally breaks
# things, but "should be fine" is not the bar for Tj's only copy of his portfolio app: the
# minified APK itself is launched and put through a few thousand random taps and swipes, and
# a single crash stops the release from being published.
#
# A CRASH FAILS THE RUN. An ANR is reported but does not: an emulator on a shared CI runner is
# slow enough to trip the 5-second main-thread watchdog on work a real phone finishes at once.
set -uo pipefail
APK="${1:?usage: smoke-test.sh <apk>}"
PKG=com.tj.portfolio
OUT="${SMOKE_OUT:-smoke}"
mkdir -p "$OUT"

fail() {
  echo "::error::$1"
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  grep -n -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 || true
  exit 1
}

adb wait-for-device
adb install -r "$APK" > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; fail "install failed"; }
adb logcat -c || true

# ---- 1. It starts, and it is still alive after its first network wave.
adb shell am start -W -n "$PKG/.MainActivity" > "$OUT/start.txt" 2>&1 || fail "launch failed"
cat "$OUT/start.txt"
sleep 20
adb shell pidof "$PKG" > /dev/null || fail "the app is not running 20s after launch"

# ---- 2. Random input across every screen it can reach. Fixed seed, so a failure reproduces.
# No system keys (home/volume/power would leave the app); app switching off for the same reason.
adb shell monkey -p "$PKG" -s 20260922 --throttle 60 \
  --pct-syskeys 0 --pct-appswitch 0 --ignore-security-exceptions \
  -v 3000 > "$OUT/monkey.txt" 2>&1
MONKEY_RC=$?
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
tail -5 "$OUT/monkey.txt"

if grep -q "// CRASH: $PKG" "$OUT/monkey.txt"; then
  grep -n -A25 "// CRASH" "$OUT/monkey.txt" | head -60
  fail "the release APK crashed under the monkey"
fi
# A FATAL EXCEPTION in OUR process, whatever the monkey says.
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PKG"; then
  fail "the release APK threw a fatal exception"
fi
if grep -q "// NOT RESPONDING: $PKG" "$OUT/monkey.txt"; then
  echo "::warning::the monkey reported an ANR - see the smoke-logs artifact (emulator timing is not a phone's)"
fi
if [ "$MONKEY_RC" -ne 0 ]; then
  echo "::warning::monkey exited $MONKEY_RC without a crash of this app - see the smoke-logs artifact"
fi
adb shell pidof "$PKG" > /dev/null && echo "still running after the monkey" || echo "note: not running at the end (the monkey may have backed out of it)"
echo "SMOKE TEST PASSED"
