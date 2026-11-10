#!/usr/bin/env bash
set -euo pipefail
repo_root="$PWD"
mkdir -p evidence
collect() {
    result=$?
    trap - EXIT
    cd "$repo_root"
    for name in capabilities wanou cheese; do
        adb exec-out run-as tv.herrin.app cat "files/live-node-$name.json" > "evidence/$name.json" || true
        cat "evidence/$name.json"
    done
    # Do not collect provider logcat, private QR graphics, tokens or URLs.
    exit "$result"
}
trap collect EXIT
adb shell getprop ro.product.cpu.abilist > evidence/system-abilist.txt
adb shell getprop ro.dalvik.vm.native.bridge > evidence/native-bridge.txt
adb shell getprop ro.dalvik.vm.isa.arm >> evidence/native-bridge.txt
adb shell getprop ro.dalvik.vm.isa.arm64 >> evidence/native-bridge.txt
adb shell ls /system/lib64/libndk_translation.so /system/lib/libndk_translation.so /system/lib64/libhoudini.so /system/lib/libhoudini.so > evidence/bridge-files.txt 2>&1 || true
bash android-tv/scripts/build_live_arm_canary.sh
cd android-tv
gradle -PherrinLiveSourceTest=true -PherrinArmTranslationTest="${ARM_PACKAGE:-false}" :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace > ../evidence/build.log 2>&1 || { tail -n 120 ../evidence/build.log; exit 1; }
adb install -r app/build/outputs/apk/debug/app-debug.apk | tee ../evidence/install.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
cd ..
adb shell dumpsys package tv.herrin.app | rg 'primaryCpuAbi|secondaryCpuAbi|nativeLibraryDir' > evidence/package-abi.txt || true
adb shell am instrument -w -r -e class tv.herrin.app.FanTaiYingRealNodesTest tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/instrumentation.txt
python3 - <<'PY'
import json
from pathlib import Path
text=Path('evidence/instrumentation.txt').read_text()
assert 'OK (3 tests)' in text and 'INSTRUMENTATION_FAILED' not in text and 'FAILURES!!!' not in text, text
PY
