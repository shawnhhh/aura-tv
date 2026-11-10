#!/usr/bin/env bash
set -euo pipefail
repo_root="$PWD"
mkdir -p evidence
collect() {
    result=$?
    cd "$repo_root"
    trap - EXIT
    adb exec-out run-as tv.herrin.app cat files/fantaiying-import.json > evidence/fantaiying-import.json || true
    adb logcat -d -s HERRIN-REAL-IMPORT:I > evidence/import-logcat.txt || true
    adb exec-out screencap -p > evidence/final.png || true
    cat evidence/fantaiying-import.json
    exit "$result"
}
trap collect EXIT
cd android-tv
gradle -PherrinLiveSourceTest=true :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace > ../evidence/build.log 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
cd ..
adb shell am instrument -w -r -e class tv.herrin.app.FanTaiYingRealImportTest tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/instrumentation.txt
python3 - <<'PY'
import json
from pathlib import Path
text=Path('evidence/instrumentation.txt').read_text()
assert 'OK (1 test)' in text and 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text, text
PY
