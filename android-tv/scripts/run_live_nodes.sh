#!/usr/bin/env bash
set -euo pipefail
repo_root="$PWD"
mkdir -p evidence
collect() {
    result=$?
    trap - EXIT
    cd "$repo_root"
    report_names=(capabilities wanou cheese)
    if [[ -n "${LIVE_NODE:-}" ]]; then report_names=("$LIVE_NODE"); fi
    for name in "${report_names[@]}"; do
        adb exec-out run-as tv.herrin.app cat "files/live-node-$name.json" > "evidence/$name.json" || true
        cat "evidence/$name.json"
    done
    # Emit only fixed classifications from Android's crash buffer. Raw native
    # traces can contain provider credentials and must never become artifacts.
    crash_summary="evidence/native-crash-summary-${LIVE_NODE:-all}.json"
    python3 android-tv/scripts/live_native_crash.py > "$crash_summary" || true
    cat "$crash_summary"
    error_summary="evidence/spider-error-shapes-${LIVE_NODE:-all}.json"
    python3 android-tv/scripts/live_spider_error_shapes.py > "$error_summary" || true
    cat "$error_summary"
    # Retain sanitized logcat classifications, never private provider messages.
    cat "$crash_summary" "$error_summary" > "evidence/logcat-redacted-${LIVE_NODE:-all}.txt"
    cat evidence/system-abilist.txt evidence/native-bridge.txt evidence/bridge-files.txt evidence/package-abi.txt 2>/dev/null || true
    python3 - <<'SUMMARY'
import json, os
from pathlib import Path
lines = ['## Real provider outcomes (workflow success is evidence collection only)',
         '| Node | Verdict | Stage |', '| --- | --- | --- |']
for key in ([os.environ['LIVE_NODE']] if os.environ.get('LIVE_NODE') else ('wanou', 'cheese')):
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
    if [[ "${LIVE_METHOD_METADATA:-true}" == true ]]; then python3 android-tv/scripts/live_spider_methods.py || true; fi
    if [[ "${LIVE_REQUIRE_ACCEPTANCE:-false}" == true ]]; then
        if ! python3 android-tv/scripts/verify_live_node_acceptance.py --node "${LIVE_NODE:?selected node required}" --directory evidence; then result=1; fi
    fi
    # Never persist provider logcat, private QR graphics, tokens or URLs.
    # The classifier above reads logs only in memory and exports fixed shapes.
    exit "$result"
}
trap collect EXIT
# Mark only stages confirmed by actual Android commands on this invocation.
adb wait-for-device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
touch evidence/emulator-booted
adb shell getprop ro.product.cpu.abilist > evidence/system-abilist.txt
adb shell getprop ro.dalvik.vm.native.bridge > evidence/native-bridge.txt
adb shell getprop ro.dalvik.vm.isa.arm >> evidence/native-bridge.txt
adb shell getprop ro.dalvik.vm.isa.arm64 >> evidence/native-bridge.txt
adb shell ls /system/lib64/libndk_translation.so /system/lib/libndk_translation.so /system/lib64/libhoudini.so /system/lib/libhoudini.so > evidence/bridge-files.txt 2>&1 || true
if [[ -n "${LIVE_PREBUILT_APKS:-}" ]]; then
    if [[ "${ARM_PACKAGE:-false}" == true ]]; then variant="${LIVE_INSTALL_ABI:?ARM package ABI required}"; else variant=plain; fi
    apk_dir="$repo_root/$LIVE_PREBUILT_APKS/$variant"
else
    bash android-tv/scripts/build_live_arm_canary.sh
    (cd android-tv && gradle -PherrinLiveSourceTest=true -PherrinArmTranslationTest="${ARM_PACKAGE:-false}" -PherrinLiveAbi="${LIVE_INSTALL_ABI:-arm64-v8a}" :app:assembleDebug :app:assembleDebugAndroidTest) 2>&1 | tee evidence/build.log
    apk_dir="$repo_root/android-tv/app/build/outputs/apk"
fi
install_args=()
instrument_args=()
if [[ -n "${LIVE_INSTALL_ABI:-}" ]]; then
    install_args=(--abi "$LIVE_INSTALL_ABI")
    instrument_args=(--abi "$LIVE_INSTALL_ABI")
fi
if [[ "${LIVE_PRODUCTION_HTTP_FIRST:-false}" == true ]]; then
    instrument_args+=(-e p0ProductionHttpFirst true)
fi
if [[ "${LIVE_MAIN_UI:-false}" == true ]]; then
    [[ "${LIVE_DISPOSABLE_DEVICE:-false}" == true ]] || { echo 'DISPOSABLE_DEVICE_CONFIRMATION_REQUIRED'; exit 1; }
    instrument_args+=(-e herrinDisposableDevice true)
    selected_title="${LIVE_FILM_TITLE:-}"
    if [[ -z "$selected_title" && -f "$repo_root/.github/p0-playback-trigger.json" ]]; then
        selected_title="$(python3 - "$repo_root/.github/p0-playback-trigger.json" <<'PY'
import json, sys
from pathlib import Path
config = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
print(config.get('film_title', '小城良方'))
PY
)"
    fi
    selected_title="${selected_title:-小城良方}"
    case "$selected_title" in
        "小城良方"|"蜡笔小新") ;;
        *) echo "UNSUPPORTED_REAL_TEST_TITLE"; exit 1;;
    esac
    instrument_args+=(-e herrinFilmTitle "$selected_title")
    printf 'P0_REAL_TITLE=%s\n' "$selected_title"
fi
if [[ -n "${LIVE_PREBUILT_APKS:-}" ]]; then
    adb install "${install_args[@]}" -r "$apk_dir/app-debug.apk" | tee evidence/install.txt
    adb install "${install_args[@]}" -r "$apk_dir/app-debug-androidTest.apk"
else
    adb install -r "$apk_dir/debug/app-debug.apk" | tee evidence/install.txt
    adb install -r "$apk_dir/androidTest/debug/app-debug-androidTest.apk"
fi
touch evidence/apks-installed
adb shell dumpsys package tv.herrin.app | grep -E 'primaryCpuAbi|secondaryCpuAbi|nativeLibraryDir' > evidence/package-abi.txt || true
if [[ -n "${LIVE_INSTALL_ABI:-}" ]]; then
    grep -F "primaryCpuAbi=$LIVE_INSTALL_ABI" evidence/package-abi.txt
fi
test_selector=tv.herrin.app.FanTaiYingRealNodesTest
if [[ "${LIVE_MAIN_UI:-false}" == true ]]; then test_selector=tv.herrin.app.FanTaiYingRealMainUiTest; fi
if [[ "${LIVE_NODE:-}" == cheese ]]; then test_selector+='#cheeseRealNode'; fi
if [[ "${LIVE_NODE:-}" == wanou ]]; then test_selector+='#wanouGegeRealNode'; fi
# Disposable CI emulator only: scope fixed error classifications to this invocation.
adb logcat -c
touch evidence/instrumentation-started
adb shell am instrument "${instrument_args[@]}" -w -r -e class "$test_selector" tv.herrin.app.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/instrumentation.txt
python3 - <<'PY'
import json, os
from pathlib import Path
text=Path('evidence/instrumentation.txt').read_text()
expected = 'OK (1 test)' if os.environ.get('LIVE_NODE') else 'OK (3 tests)'
assert expected in text and 'INSTRUMENTATION_FAILED' not in text and 'FAILURES!!!' not in text, text
PY
