#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
collect() {
    code=$?
    trap - EXIT
    adb exec-out run-as tv.herrin.app cat files/tmdb-refresh-diagnostic.json > evidence/tmdb-refresh-diagnostic.json || true
    cat evidence/tmdb-refresh-diagnostic.json
    exit "$code"
}
trap collect EXIT
adb install -r android-tv/app/build/outputs/apk/debug/app-debug.apk
adb install -r android-tv/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e tmdbHoldRefresh "${TMDB_HOLD_REFRESH:-false}" -e class tv.herrin.app.TmdbBrowseRefreshTest tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/tmdb-refresh-instrumentation.txt
python3 - <<'PY'
from pathlib import Path
output=Path('evidence/tmdb-refresh-instrumentation.txt').read_text()
assert 'OK (1 test)' in output and 'FAILURES!!!' not in output, output
PY
