#!/usr/bin/env bash
set -euo pipefail
# Only the disposable CI emulator is cleared. Each app uses its matching test APK.
mkdir -p tmdb-performance-ab
adb wait-for-device
adb shell wm size 1920x1080
adb shell wm density 240
adb logcat -G 16M
adb shell dumpsys display > tmdb-performance-ab/display.txt
classes='tv.herrin.app.M442RemoteBurstPerformanceTest,tv.herrin.app.M443RemoteBurstWithoutIdleTest,tv.herrin.app.M445FrameBaselineTest,tv.herrin.app.RemoteHoldReleaseTest'
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
  adb install "$project/android-tv/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  adb logcat -c
  adb shell setprop log.tag.HerrinRemote DEBUG
  adb shell am instrument -w -r -e class tv.herrin.app.P0CachedHomeHeroTest tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "tmdb-performance-ab/$leg-home.txt"
  adb logcat -d -v epoch > "tmdb-performance-ab/$leg-home-logcat.txt"
  adb logcat -c
  adb shell am instrument -w -r -e class "$classes" tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "tmdb-performance-ab/$leg-remote.txt"
  adb logcat -d > "tmdb-performance-ab/$leg-remote-logcat.txt"
done
python3 .github/scripts/tmdb_performance_ab.py
