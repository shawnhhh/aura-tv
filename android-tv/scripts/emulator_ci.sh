#!/usr/bin/env bash
set -euo pipefail
cd android-tv
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
bash scripts/emulator_smoke.sh
gradle :app:connectedDebugAndroidTest --stacktrace
