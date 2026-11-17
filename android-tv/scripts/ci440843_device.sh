#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
collect() {
  status=$?
  trap - EXIT
  adb logcat -d -s HerrinScenarioLifecycle:I HerrinTmdbRequestBudget:I > build/ci440843-targeted-logcat.txt || true
  cat build/ci440843-targeted-logcat.txt
  python3 - <<'PY'
from pathlib import Path
for p in Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'):
    print('HERRIN_TARGETED_JUNIT_BEGIN '+str(p))
    print(p.read_text())
    print('HERRIN_TARGETED_JUNIT_END')
PY
  exit "$status"
}
trap collect EXIT
bash scripts/ci440843_targeted.sh
