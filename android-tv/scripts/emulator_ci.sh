#!/usr/bin/env bash
set -euo pipefail
cd android-tv
mkdir -p build/emulator-evidence
# Capture diagnostics even when a test fails; the emulator runner terminates ADB after this script.
collect() {
    code=$?
    trap - EXIT
    adb logcat -d > build/emulator-evidence/logcat.txt 2>&1 || true
    adb shell dumpsys activity activities > build/emulator-evidence/activities-final.txt 2>&1 || true
    adb pull /data/local/tmp/herrin-test-evidence build/emulator-evidence/test-evidence || true
    adb exec-out screencap -p > build/emulator-evidence/final.png || true
    adb shell uiautomator dump /sdcard/herrin-final.xml || true
    adb pull /sdcard/herrin-final.xml build/emulator-evidence/final.xml >/dev/null 2>&1 || true
    python3 scripts/emulator_report.py "$code" || true
    # Small genuine PNG previews in logs allow review even if artifact download access is restricted.
    python3 - <<'PY'
import base64
from pathlib import Path
for name in ['home.png', 'test-evidence/splash-HERRIN.png', 'test-evidence/splash-H.png', 'test-evidence/search.png', 'test-evidence/detail.png', 'test-evidence/favorites-persisted.png', 'test-evidence/remote-focused-poster.png']:
    p = Path('build/emulator-evidence') / name
    if p.exists():
        print('HERRIN_PNG ' + name + ' ' + base64.b64encode(p.read_bytes()).decode())
for p in Path('build/emulator-evidence/test-evidence').glob('FAIL-*-semantics.txt'):
    print('HERRIN_FAILURE_TREE', p.name, p.read_text()[:30000])
PY
    exit "$code"
}
trap collect EXIT
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace 2>&1 | tee build/emulator-evidence/build.log
bash scripts/emulator_smoke.sh 2>&1 | tee build/emulator-evidence/adb-smoke.log
# Shell-owned evidence survives the package uninstall performed by AGP.
adb shell rm -rf /data/local/tmp/herrin-test-evidence
gradle :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee build/emulator-evidence/instrumentation.log
