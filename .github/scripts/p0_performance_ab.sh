#!/usr/bin/env bash
set -euo pipefail
# This script clears only its disposable CI emulator. Never run on a user's TV.
mkdir -p performance-ab
adb wait-for-device
adb shell wm size 1920x1080
adb shell wm density 240
adb logcat -G 16M
adb shell dumpsys display > performance-ab/display.txt
adb shell dumpsys SurfaceFlinger > performance-ab/surface-flinger.txt
classes='tv.herrin.app.M442RemoteBurstPerformanceTest,tv.herrin.app.M443RemoteBurstWithoutIdleTest,tv.herrin.app.M445FrameBaselineTest,tv.herrin.app.RemoteHoldReleaseTest'
# Test source is identical between the two pinned app versions.
for name in M442RemoteBurstPerformanceTest M443RemoteBurstWithoutIdleTest M445FrameBaselineTest RemoteHoldReleaseTest; do
  cmp "baseline/android-tv/app/src/androidTest/java/tv/herrin/app/$name.kt" "candidate/android-tv/app/src/androidTest/java/tv/herrin/app/$name.kt"
done
for leg in A1 B1 B2 A2; do
  project=candidate
  if [[ "$leg" == A* ]]; then project=baseline; fi
  adb uninstall tv.herrin.app.test >/dev/null 2>&1 || true
  adb uninstall tv.herrin.app >/dev/null 2>&1 || true
  adb shell rm -rf /data/local/tmp/herrin-test-evidence
  adb install "$project/android-tv/app/build/outputs/apk/debug/app-debug.apk"
  adb install candidate/android-tv/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb logcat -c
  adb shell setprop log.tag.HerrinRemote DEBUG
  adb shell am instrument -w -r -e class "$classes" tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "performance-ab/$leg-instrumentation.txt"
  adb logcat -d > "performance-ab/$leg-logcat.txt"
  python3 - "$leg" <<'PY'
import pathlib, sys
text=pathlib.Path('performance-ab/'+sys.argv[1]+'-instrumentation.txt').read_text()
assert 'OK (5 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text, text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text, text
PY
done
python3 .github/scripts/p0_performance_ab.py
