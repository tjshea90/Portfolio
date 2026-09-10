#!/usr/bin/env bash
# verify-apk.sh — does this APK carry the certificate that can update the phone?
#
# Building an APK proves it compiles. It does NOT prove it is installable over
# what is already on the phone — that depends entirely on the signing
# certificate, and a wrong one produces a perfectly valid APK that Android
# refuses as an update (or, if the user uninstalls first to force it, one that
# erases the portfolio). So the signature is checked on the ARTIFACT, not just
# on the keystore that went in: this is what catches a build that somehow
# picked up a different signing config.
#
#   bash tools/verify-apk.sh [path/to.apk]
#
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
[ -f "$APK" ] || { echo "  FAIL  no APK at $APK"; exit 1; }

# Canonical form: lowercase, no colons — apksigner and keytool disagree on both.
norm(){ tr -d ': \r' | tr 'A-Z' 'a-z'; }
WANT="$(bash tools/checkkeystore.sh --expected | norm)"

GOT=""
APKSIGNER="$(ls "${ANDROID_HOME:-/root/android-sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
if [ -n "$APKSIGNER" ]; then
  GOT="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
        | grep -i 'certificate SHA-256 digest' | head -1 | sed 's/.*digest: *//' | norm)"
fi
if [ -z "$GOT" ] && command -v keytool >/dev/null 2>&1; then
  # Fallback for a container with no build-tools on PATH.
  CERT="$(unzip -l "$APK" 2>/dev/null | grep -oE 'META-INF/[^ ]*\.(RSA|EC|DSA)' | head -1)"
  [ -n "$CERT" ] && GOT="$(unzip -p "$APK" "$CERT" 2>/dev/null | keytool -printcert 2>/dev/null \
        | sed -n 's/.*SHA256: *//p' | head -1 | norm)"
fi

[ -z "$GOT" ] && { echo "  FAIL  could not read a signature from $APK — treat it as unsigned."; exit 1; }

if [ "$GOT" = "$WANT" ]; then
  echo "  OK    $(basename "$APK") is signed with the certificate the phone accepts"
  exit 0
fi
echo "  !!    $(basename "$APK") is signed with the WRONG CERTIFICATE."
echo "        It will NOT update the phone in place. Do not publish or install it."
echo "          expected $WANT"
echo "          got      $GOT"
exit 1
