#!/usr/bin/env bash
# Explicit, on-device-only live provider verification; not part of ordinary CI.
set -euo pipefail
APP_APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${2:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
SITE_KEY="${3:-YGP}"
if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found. Install Android SDK platform-tools first." >&2
  exit 2
fi
if [[ ! -s "$APP_APK" || ! -s "$TEST_APK" ]]; then
  echo "ERROR: app/test APK missing. Obtain both from the ARM E2E build artifact." >&2
  exit 2
fi
adb get-state >/dev/null
ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ "$ABI" != arm64* && "$ABI" != armeabi* ]]; then
  echo "ERROR: a physical ARM Android TV is required; got ABI=$ABI" >&2
  exit 2
fi
echo "Device: ARM TV ($ABI). Site key: $SITE_KEY"
echo "Security: this explicitly executes the selected third-party Spider on your TV."
echo "No TMDB token or raw media URL will be written to this diagnostic log."
adb install -r "$APP_APK" >/dev/null
adb install -r "$TEST_APK" >/dev/null
LOG="herrin-fantaiying-arm-e2e-$(date +%Y%m%d%H%M%S).txt"
adb shell am instrument -w \
  -e class tv.herrin.app.FanTaiYingArmLivePlaybackTest \
  -e siteKey "$SITE_KEY" \
  tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$LOG"
echo "--- Sanitized stage transitions ---"
adb logcat -d -s HERRIN-LIVE-E2E:I '*:S' | tail -n 25 | tee -a "$LOG"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS: stack=|INSTRUMENTATION_RESULT: shortMsg=' "$LOG" ||
   ! grep -q 'OK (1 test)' "$LOG"; then
  echo "LIVE PLAYBACK NOT VERIFIED. Check stage and error in $LOG" >&2
  exit 1
fi
echo "PASS: provider Spider -> details -> episode -> Media3 rendered video frame."
echo "Diagnostics saved to $LOG"
