#!/usr/bin/env bash
# checkkeystore.sh — is app/sideload.jks present, and is it the RIGHT one?
#
# WHY THE FINGERPRINT AND NOT JUST THE FILE
# -----------------------------------------
# A missing keystore is the harmless case: the build fails immediately and
# says so. The dangerous case is a WRONG one — a different key builds and
# installs perfectly and only fails to update the phone in place, which
# Android reports at install time, long after the build looked green. An
# in-place update is the only thing that preserves the portfolio (see
# BRIEF.md, "the keystore is irreplaceable"), so the certificate is checked,
# not just the file's existence. A regenerated key with an IDENTICAL DN is
# still caught — verified.
#
# Single source of truth for that fingerprint: bootstrap.sh and
# tools/ensure-build-env.sh both call this rather than each carrying a copy.
#
#   bash tools/checkkeystore.sh           # prints one status line
#   bash tools/checkkeystore.sh --quiet   # silent when correct
#
# exit 0 = correct (or present but unverifiable), 1 = missing, 2 = WRONG
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

# The certificate every release has shipped with. This must never change —
# changing it is the "erases the portfolio" failure. Recorded in BRIEF.md too.
KS_FP="2E:8C:38:47:2D:16:57:B7:D2:56:22:66:C6:D7:E1:D8:E6:F0:3D:76:66:BD:10:E1:CB:50:5B:1C:F3:96:A9:F2"

QUIET=0
for a in "$@"; do
  case "$a" in
    --quiet) QUIET=1 ;;
    # Lets tools/verify-apk.sh and CI check an APK against the same value
    # without copying it. A second copy of this string is a second thing that
    # can be edited, and it is the one string in the project that must never
    # change.
    --expected) printf '%s\n' "$KS_FP"; exit 0 ;;
  esac
done
say_ok(){ [ "$QUIET" -eq 1 ] || echo "$1"; }

if [ ! -f app/sideload.jks ]; then
  echo "  note  no signing keystore — app/sideload.jks is not in git and must be"
  echo "        supplied by Tj before any APK build. Never generate a replacement."
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  say_ok "  OK    signing keystore present (no keytool here to verify its fingerprint)"
  exit 0
fi

GOT="$(keytool -list -keystore app/sideload.jks -storepass portfolio 2>/dev/null \
       | sed -n 's/.*SHA-256): *//p' | tr -d ' \r' | head -1)"

if [ "$GOT" = "$KS_FP" ]; then
  say_ok "  OK    signing keystore present and matches the shipped certificate"
  exit 0
elif [ -z "$GOT" ]; then
  echo "  WARN  app/sideload.jks is present but unreadable with the documented"
  echo "        password — treat it as the wrong file; do NOT ship with it."
  exit 2
else
  echo "  !!    app/sideload.jks is the WRONG KEYSTORE. Signing with it produces"
  echo "        an APK that CANNOT update the phone in place — installing it"
  echo "        erases the portfolio. Ask Tj for the right file; never generate"
  echo "        a replacement."
  echo "          expected $KS_FP"
  echo "          got      $GOT"
  exit 2
fi
