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
    cat evidence/system-abilist.txt evidence/native-bridge.txt evidence/bridge-files.txt evidence/package-abi.txt 2>/dev/null || true
    python3 - <<'SUMMARY'
import json, os
from pathlib import Path
lines = ['## Real provider outcomes (workflow success is evidence collection only)',
         '| Node | Verdict | Stage |', '| --- | --- | --- |']
for key in ('wanou', 'cheese'):
    try:
        data = json.loads(Path(f'evidence/{key}.json').read_text())
        lines.append(f"| {data['node']} | {data['verdict']} | {data.get('failure_stage', 'completed')} |")
    except (OSError, ValueError, KeyError):
        lines.append(f'| {key} | TEST_INFRASTRUCTURE_FAILED | no complete node report |')
print('\n'.join(lines))
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
        out.write('\n'.join(lines) + '\n')
SUMMARY
    # Do not collect provider logcat, private QR graphics, tokens or URLs.
    exit "$result"
}
trap collect EXIT
adb shell getprop ro.product.cpu.abilist > evidence/system-abilist.txt
adb shell getprop ro.dalvik.vm.native.bridge > evidence/native-bridge.txt
adb shell getprop ro.dalvik.vm.isa.arm >> evidence/native-bridge.txt
adb shell getprop ro.dalvik.vm.isa.arm64 >> evidence/native-bridge.txt
adb shell ls /system/lib64/libndk_translation.so /system/lib/libndk_translation.so /system/lib64/libhoudini.so /system/lib/libhoudini.so > evidence/bridge-files.txt 2>&1 || true
if [[ -n "${LIVE_PREBUILT_APKS:-}" ]]; then
    if [[ "${ARM_PACKAGE:-false}" == true ]]; then variant=arm; else variant=plain; fi
    apk_dir="$repo_root/$LIVE_PREBUILT_APKS/$variant"
else
    bash android-tv/scripts/build_live_arm_canary.sh
    (cd android-tv && gradle -PherrinLiveSourceTest=true -PherrinArmTranslationTest="${ARM_PACKAGE:-false}" :app:assembleDebug :app:assembleDebugAndroidTest) 2>&1 | tee evidence/build.log
    apk_dir="$repo_root/android-tv/app/build/outputs/apk"
fi
if [[ -n "${LIVE_PREBUILT_APKS:-}" ]]; then
    adb install -r "$apk_dir/app-debug.apk" | tee evidence/install.txt
    adb install -r "$apk_dir/app-debug-androidTest.apk"
else
    adb install -r "$apk_dir/debug/app-debug.apk" | tee evidence/install.txt
    adb install -r "$apk_dir/androidTest/debug/app-debug-androidTest.apk"
fi
adb shell dumpsys package tv.herrin.app | grep -E 'primaryCpuAbi|secondaryCpuAbi|nativeLibraryDir' > evidence/package-abi.txt || true
adb shell am instrument -w -r -e class tv.herrin.app.FanTaiYingRealNodesTest tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/instrumentation.txt
python3 - <<'PY'
import json
from pathlib import Path
text=Path('evidence/instrumentation.txt').read_text()
assert 'OK (3 tests)' in text and 'INSTRUMENTATION_FAILED' not in text and 'FAILURES!!!' not in text, text
PY
