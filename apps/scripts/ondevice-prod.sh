#!/usr/bin/env bash
# Build + install the Dayfold Android app on a PHYSICAL device, pointed at the
# PRODUCTION API (https://family-ai-dashboard.vercel.app), and launch it.
#
# Use this to smoke-test real behavior against prod on a real phone (e.g. the
# loading states: sign-in, sign-out, feed/hub/member/device load + per-row
# busy). Unlike ondevice-demo.sh, this runs NO local API and NO seed — it talks
# straight to prod over the device's own network. Real Google sign-in needs the
# real Firebase config: apps/androidApp/google-services.json must be present
# (it is gitignored — copy it from your main workspace if missing).
#
# The debug build's DAYFOLD_API already defaults to prod; this script sets it
# explicitly so the target is unambiguous, and (by default) clears app data
# first so a stale debug-drawer Backend override or dead session can't shadow
# prod — which also gives a fresh sign-in to exercise the auth loading states.
#
# Usage:
#   apps/scripts/ondevice-prod.sh                 # first physical (non-emulator) device
#   DEVICE=57091FDCH01331 apps/scripts/ondevice-prod.sh
#   NO_CLEAR=1 apps/scripts/ondevice-prod.sh      # keep existing app data/session
#   DAYFOLD_API=https://staging... apps/scripts/ondevice-prod.sh   # override target
#   apps/scripts/ondevice-prod.sh --uninstall     # remove the app from the device
set -euo pipefail
cd "$(dirname "$0")/.."                      # apps/

ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
JAVA17="${JAVA17:-$(/usr/libexec/java_home -v 17)}"
APP_ID="com.sloopworks.dayfold"
ACTIVITY="$APP_ID/com.sloopworks.dayfold.android.MainActivity"
PROD_URL="https://family-ai-dashboard.vercel.app"
API="${DAYFOLD_API:-$PROD_URL}"

# Pick the target device: explicit DEVICE/ANDROID_SERIAL, else the first
# non-emulator. Emulators can't be the prod test target here.
SERIAL="${DEVICE:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk '/\tdevice$/{print $1}' | grep -v '^emulator-' | head -1 || true)"
fi
[ -n "$SERIAL" ] || { echo "ERROR: no physical device found. Plug in the Pixel and enable USB debugging, or set DEVICE=<serial>."; "$ADB" devices -l; exit 1; }
MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo '?')"

if [ "${1:-}" = "--uninstall" ]; then
  echo "Uninstalling $APP_ID from $MODEL ($SERIAL)…"
  "$ADB" -s "$SERIAL" uninstall "$APP_ID" || true
  exit 0
fi

# Preflight: real Google sign-in on prod needs the Firebase config.
if [ ! -f androidApp/google-services.json ]; then
  echo "WARNING: apps/androidApp/google-services.json missing — the build may fail or 'Continue with Google' won't work on prod. Copy it from your main workspace."
fi

echo "Target : $MODEL ($SERIAL)"
echo "API    : $API"
echo "Clear  : ${NO_CLEAR:+no}${NO_CLEAR:-yes (fresh session)}"

if [ -z "${NO_CLEAR:-}" ]; then
  # Wipe prior app data so no stale drawer override / dead session shadows prod.
  "$ADB" -s "$SERIAL" shell pm clear "$APP_ID" >/dev/null 2>&1 || true
fi

# Build + install the debug variant pointed at prod. ANDROID_SERIAL makes the
# AGP install task target this device despite the attached emulators.
echo "Building + installing (this takes a minute on a cold build)…"
DAYFOLD_API="$API" ANDROID_SERIAL="$SERIAL" JAVA_HOME="$JAVA17" ./gradlew :androidApp:installDebug

# Launch.
"$ADB" -s "$SERIAL" shell am start -n "$ACTIVITY" >/dev/null
echo
echo "Launched on $MODEL → $API"
echo "Verify the loading states:"
echo "  • Sign in (Continue with Google) — tapped button shows a spinner, others disable"
echo "  • Feed first load — skeleton cards, then content; pull-to-refresh spins"
echo "  • Hubs list/detail — skeletons; Members/Devices — list skeletons + per-row spinner on approve/revoke"
echo "  • Account → Sign out — button shows a spinner during teardown"
echo "Logs: $ADB -s $SERIAL logcat -s System.out   (redux action log)"
