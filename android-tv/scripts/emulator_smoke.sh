#!/usr/bin/env bash
set -euo pipefail
# Run inside android-tv/ while an Android TV AVD is online.
evidence="build/emulator-evidence"
mkdir -p "$evidence"
adb wait-for-device
adb shell wm size 1920x1080
adb shell wm density 240
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear tv.herrin.app
adb logcat -c || true
adb shell am start -W -n tv.herrin.app/.MainActivity | tee "$evidence/launch.txt"
sleep 1
adb exec-out screencap -p > "$evidence/launch.png"
sleep 4
adb exec-out screencap -p > "$evidence/home.png"

dump_ui() {
    local label="$1"
    adb shell uiautomator dump /sdcard/herrin-ui.xml > "$evidence/${label}-dump.log" 2>&1
    adb exec-out cat /sdcard/herrin-ui.xml > "$evidence/${label}.xml"
}
adb shell dumpsys activity activities > "$evidence/foreground.txt"
if ! grep -q "mResumedActivity.*tv.herrin.app/.MainActivity" "$evidence/foreground.txt"; then
    echo "FAIL: HERRIN MainActivity is not resumed"; exit 1
fi
dump_ui home
if ! grep -q 'HERRIN' "$evidence/home.xml"; then
    echo "FAIL: HERRIN not visible in emulator UI hierarchy"
    exit 1
fi
if ! grep -q '影片详情' "$evidence/home.xml"; then
    echo "FAIL: expected home detail action absent from emulator UI hierarchy"
    exit 1
fi

# Drive the actual Android TV interface using remote keycodes, not pointer taps.
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
adb exec-out screencap -p > "$evidence/remote-after-right-ok.png"
dump_ui after-dpad
if ! grep -q '精选电影' "$evidence/after-dpad.xml"; then
    echo "FAIL: D-pad RIGHT + OK did not select the Movies tab"
    exit 1
fi
adb shell input keyevent KEYCODE_BACK
sleep 1
adb exec-out screencap -p > "$evidence/after-back.png"
dump_ui after-back
if ! grep -q '精选首页' "$evidence/after-back.xml"; then
    echo "FAIL: Back did not return to home category"
    exit 1
fi
adb logcat -d -s AndroidRuntime:E > "$evidence/android-runtime.log" || true
if grep -q 'Process: tv.herrin.app' "$evidence/android-runtime.log"; then
    echo "FAIL: HERRIN uncaught Android exception"
    exit 1
fi
echo "PASS: Android TV emulator launch, home, remote category switch, Back, no fatal crash"
