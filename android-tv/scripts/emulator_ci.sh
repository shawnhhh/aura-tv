#!/usr/bin/env bash
set -euo pipefail
cd android-tv
mkdir -p build/emulator-evidence
# Capture diagnostics even when a test fails; the emulator runner terminates ADB after this script.
collect() {
    code=$?
    trap - EXIT
    adb logcat -d > build/emulator-evidence/logcat.txt 2>&1 || true
    # Only sanitized measurements: the perf test never logs source addresses, film URLs or tokens.
    grep 'HerrinPerf' build/emulator-evidence/logcat.txt > build/emulator-evidence/performance-measurements.log || true
    adb shell dumpsys activity activities > build/emulator-evidence/activities-final.txt 2>&1 || true
    adb pull /data/local/tmp/herrin-test-evidence build/emulator-evidence/test-evidence || true
    adb exec-out screencap -p > build/emulator-evidence/final.png || true
    adb shell uiautomator dump /sdcard/herrin-final.xml || true
    adb pull /sdcard/herrin-final.xml build/emulator-evidence/final.xml >/dev/null 2>&1 || true
    adb logcat -d -s HerrinRemote:D > build/emulator-evidence/remote-trace.log 2>&1 || true
    cat build/emulator-evidence/remote-trace.log
    # Public fixture semantics only. Preserve the original failing assertion and
    # expose its exact tree even when artifact downloads are unavailable.
    adb logcat -d -s HerrinCachedHeroFailure:D > build/emulator-evidence/cached-hero-failure.log 2>&1 || true
    head -n 256 build/emulator-evidence/cached-hero-failure.log
    # Full PNGs remain in artifacts; keep logs bounded so connector log retrieval is reliable.
    python3 scripts/emulator_report.py "$code" || true
    exit "$code"
}
trap collect EXIT
# Parse/report reliability must be tested before claiming an emulator pass.
python3 -m unittest discover -s scripts -p 'test_remote_perf_report.py' -v
python3 -m unittest discover -s scripts -p 'test_verify_full_tv_suite.py' -v
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace 2>&1 | tee build/emulator-evidence/build.log
adb logcat -G 16M || true
adb shell setprop log.tag.HerrinRemote DEBUG
bash scripts/emulator_smoke.sh 2>&1 | tee build/emulator-evidence/adb-smoke.log
# Regression preflight for the failures discovered in run 42358864061. This
# supplements the unfiltered suite below; it never makes failed cases optional.
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class 'tv.herrin.app.HerrinSplashTest,tv.herrin.app.P0CachedHomeHeroTest,tv.herrin.app.P0CmsCandidateParserTest,tv.herrin.app.P0SpiderDeadlineRecoveryTest,tv.herrin.app.P0SourceSearchLifecycleTest' tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/emulator-evidence/p0-regression-preflight.txt
python3 - <<'PY'
from pathlib import Path
text=Path('build/emulator-evidence/p0-regression-preflight.txt').read_text()
assert 'OK (14 tests)' in text and 'INSTRUMENTATION_CODE: -1' in text,text
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text,text
PY
# Shell-owned evidence survives the package uninstall performed by AGP.
adb shell rm -rf /data/local/tmp/herrin-test-evidence
gradle :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee build/emulator-evidence/instrumentation.log
# Do not report success if instrumentation skipped essential remote/playback cases.
python3 scripts/verify_full_tv_suite.py 2>&1 | tee build/emulator-evidence/full-suite-gate.log
