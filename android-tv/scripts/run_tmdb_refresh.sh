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
run_target() {
    local label="$1" classes="$2" hold="$3" expected="$4"
    adb shell am instrument -w -r -e timeout_msec 90000 -e tmdbHoldRefresh "$hold" -e class "$classes" tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee "evidence/tmdb-$label-instrumentation.txt"
    adb exec-out run-as tv.herrin.app cat files/tmdb-refresh-diagnostic.json > "evidence/tmdb-$label-diagnostic.json"
    cat "evidence/tmdb-$label-diagnostic.json"
    python3 - "$label" "$expected" <<'PY'
import sys
from pathlib import Path
label,expected=sys.argv[1:]
output=Path(f'evidence/tmdb-{label}-instrumentation.txt').read_text()
assert f'OK ({expected} test' in output and 'FAILURES!!!' not in output, output
PY
}
# Reproduce the neighboring classes in the original suite, then exercise the
# exact same assertions with a provider response deliberately held >15 seconds.
result=0
run_target neighbors tv.herrin.app.TmdbBrowsePageTest,tv.herrin.app.TmdbBrowsePaginationTest,tv.herrin.app.TmdbBrowseRefreshTest false 5 || result=1
run_target slow-focus tv.herrin.app.TmdbBrowseRefreshTest true 1 || result=1
exit "$result"
