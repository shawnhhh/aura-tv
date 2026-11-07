#!/usr/bin/env bash
set -euo pipefail
# Disposable CI AVD only. Re-sign downloaded debug inputs with one throwaway key;
# these benchmark-only packages are never the delivered APK.
mkdir -p startup-ab/signed
keytool -genkeypair -keystore startup-ab/benchmark.p12 -storepass android -keypass android -alias benchmark -keyalg RSA -validity 1 -dname CN=DisposableBenchmark >/dev/null 2>&1
signer=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)
baseline_apk=$(find baseline-apk -name app-debug.apk -type f | head -n 1)
candidate_apk=$(find candidate-apk -name app-debug.apk -type f | head -n 1)
test_apk=$(find candidate-apk -name app-debug-androidTest.apk -type f | head -n 1)
for kind in baseline candidate test; do
  case "$kind" in baseline) input="$baseline_apk";; candidate) input="$candidate_apk";; test) input="$test_apk";; esac
  test -s "$input"
  "$signer" sign --ks startup-ab/benchmark.p12 --ks-key-alias benchmark --ks-pass pass:android --key-pass pass:android --out "startup-ab/signed/$kind.apk" "$input"
done
for name in M442RemoteBurstPerformanceTest M443RemoteBurstWithoutIdleTest M445FrameBaselineTest RemoteHoldReleaseTest; do
  cmp "baseline/android-tv/app/src/androidTest/java/tv/herrin/app/$name.kt" "android-tv/app/src/androidTest/java/tv/herrin/app/$name.kt"
done
adb wait-for-device
adb shell wm size 1920x1080
adb shell wm density 240
adb logcat -G 16M
adb shell dumpsys display > startup-ab/display.txt
classes='tv.herrin.app.P0StartupFrameProbeTest,tv.herrin.app.M442RemoteBurstPerformanceTest,tv.herrin.app.M443RemoteBurstWithoutIdleTest,tv.herrin.app.M445FrameBaselineTest,tv.herrin.app.RemoteHoldReleaseTest'
for leg in A1 B1 B2 A2; do
  kind=candidate
  if [[ "$leg" == A* ]]; then kind=baseline; fi
  adb uninstall tv.herrin.app.test >/dev/null 2>&1 || true
  adb uninstall tv.herrin.app >/dev/null 2>&1 || true
  adb install "startup-ab/signed/$kind.apk"
  adb install startup-ab/signed/test.apk
  adb logcat -c
  adb shell am start -W -n tv.herrin.app/.MainActivity > "startup-ab/$leg-cold-launch.txt"
  sleep 5
  adb logcat -d -s HerrinStartup:I HerrinStartupPhase:I HerrinStartupFrames:I HerrinDisplay:I AndroidRuntime:E > "startup-ab/$leg-cold-logcat.txt"
  adb shell am force-stop tv.herrin.app
  adb logcat -c
  adb shell setprop log.tag.HerrinRemote DEBUG
  adb shell am instrument -w -r -e class "$classes" tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "startup-ab/$leg-instrumentation.txt"
  adb logcat -d > "startup-ab/$leg-logcat.txt"
  python3 - "$leg" <<'PY'
import pathlib,sys
text=pathlib.Path('startup-ab/'+sys.argv[1]+'-instrumentation.txt').read_text()
assert 'OK (6 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PY
done
rm -rf startup-ab/signed startup-ab/benchmark.p12
python3 .github/scripts/p0_startup_source_ab.py
