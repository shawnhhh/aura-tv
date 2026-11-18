#!/usr/bin/env bash
# Only the thirteen failures recorded in the retained CI JUnit; never a full suite.
set -euo pipefail
cd "$(dirname "$0")/.."
selector=$(python3 - <<'PY'
import json
from pathlib import Path
failures = json.loads(Path('../docs/20261118/ci-44084378548-failures.json').read_text())
assert len(failures) == 13
print(','.join(t['classname'] + '#' + t['name'] for t in failures))
PY
)
command -v adb >/dev/null || { echo 'BLOCKED: adb is unavailable; no device tests executed.' >&2; exit 2; }
adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}' || {
    echo 'BLOCKED: no connected Android TV device; no device tests executed.' >&2; exit 2;
}
"${GRADLE_CMD:-./gradlew}" :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$selector" --console=plain "$@"
