#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
targets=$(paste -sd, scripts/ci440843_targets.txt)
test "$(wc -l < scripts/ci440843_targets.txt)" -eq 13
# Exact failed method selection; never calls emulator_ci.sh, its preflight, or ABBA.
"${HERRIN_GRADLE:-./gradlew}" :app:connectedDebugAndroidTest --no-daemon --max-workers=2 \
  "-Pandroid.testInstrumentationRunnerArguments.class=$targets" --console=plain
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
expected=set(Path('scripts/ci440843_targets.txt').read_text().splitlines())
seen=set()
for p in Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'):
    root=ET.parse(p).getroot()
    for case in root.iter('testcase'):
        key=case.attrib['classname']+'#'+case.attrib['name']
        assert key in expected, ('Unexpected test outside the 13 targets',key)
        assert case.find('skipped') is None and case.find('failure') is None and case.find('error') is None, key
        seen.add(key)
assert seen==expected, ('Missing targets',sorted(expected-seen))
print('All 13 selected methods passed without skips; no full suite or ABBA executed.')
PY
