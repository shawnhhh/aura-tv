#!/usr/bin/env bash
set -euo pipefail
result=0
if [[ "${EMPTY_HOME_UI_TEST:-false}" == true ]]; then
    # Controlled original empty-home fixture only, separate from real node verdicts.
    mkdir -p evidence
    adb install --abi "${LIVE_INSTALL_ABI:?}" -r "$LIVE_PREBUILT_APKS/$LIVE_INSTALL_ABI/app-debug.apk"
    adb install --abi "$LIVE_INSTALL_ABI" -r "$LIVE_PREBUILT_APKS/$LIVE_INSTALL_ABI/app-debug-androidTest.apk"
    adb shell am instrument --abi "$LIVE_INSTALL_ABI" -w -r -e class tv.herrin.app.EmptySpiderHomeSelectionTest tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/empty-home-ui.txt
    if ! python3 - <<'PY'
from pathlib import Path
output=Path('evidence/empty-home-ui.txt').read_text()
assert 'OK (2 tests)' in output and 'FAILURES!!!' not in output and 'INSTRUMENTATION_FAILED' not in output, output
PY
    then result=1; fi
fi
if [[ "${LIVE_NODES:-}" != both ]]; then
    bash android-tv/scripts/run_live_nodes.sh || result=1
    exit "$result"
fi
# One diagnostic APK build and emulator, two independent genuine node tests.
# Preserve a failed node verdict while allowing the other node to finish.
LIVE_NODE=cheese bash android-tv/scripts/run_live_nodes.sh || result=1
LIVE_NODE=wanou bash android-tv/scripts/run_live_nodes.sh || result=1
exit "$result"
