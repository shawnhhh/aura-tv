#!/usr/bin/env bash
set -euo pipefail
apk="$1"
evidence="$2"
mkdir -p "$evidence"
collect_startup() {
    code=$?
    trap - EXIT
    adb logcat -d -s HerrinStartup:I HerrinStartupPhase:I HerrinStartupFrames:I HerrinDisplay:I HerrinRemote:D AndroidRuntime:E > "$evidence/final-diagnostics.txt" 2>&1 || true
    # This job starts only the original offline demo, with no private services configured.
    cat "$evidence/final-diagnostics.txt"
    exit "$code"
}
trap collect_startup EXIT
adb wait-for-device
adb shell wm size 1920x1080
adb shell wm density 240
adb install -r "$apk"
# Disposable CI emulator only. Never run this data reset on a user's television.
adb shell pm clear tv.herrin.app
adb logcat -G 16M
adb shell setprop log.tag.HerrinRemote DEBUG
adb logcat -c
adb shell dumpsys display > "$evidence/display.txt"
adb shell atrace --list_categories > "$evidence/atrace-categories.txt"
tracing=0
if adb shell atrace --async_start -b 16384 gfx view wm am sched freq dalvik binder_driver > "$evidence/atrace-start.txt" 2>&1; then tracing=1; fi
adb shell am start -W -n tv.herrin.app/.MainActivity > "$evidence/cold-launch.txt"
sleep 5
adb logcat -d -s HerrinStartup:I HerrinStartupPhase:I HerrinStartupFrames:I HerrinDisplay:I AndroidRuntime:E > "$evidence/cold-diagnostics.txt"
adb shell uiautomator dump /sdcard/herrin-startup.xml >/dev/null
adb exec-out cat /sdcard/herrin-startup.xml > "$evidence/home.xml"
python3 - "$evidence/home.xml" <<'PY'
import pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text()
assert 'HERRIN' in text and '影片详情' in text, 'Interactive offline home was not reached'
PY
if [[ "$tracing" == 1 ]]; then adb shell atrace --async_stop -z > "$evidence/startup.atrace"; fi
adb shell input keyevent KEYCODE_HOME
# The HOME key is asynchronous. Starting Herrin before the launcher actually
# owns the focused window returns "Activity not started, intent delivered to
# top-most instance"; HOME then wins and leaves Launcher focused. That measures
# a shell race, not an application hot-resume regression.
home_focused=0
for attempt in $(seq 1 60); do
    adb shell dumpsys window > "$evidence/home-before-hot-window.txt"
    if grep -Eiq 'mCurrentFocus=.*launcher' "$evidence/home-before-hot-window.txt"; then
        home_focused=1
        break
    fi
    sleep 0.1
done
if [[ "$home_focused" != 1 ]]; then
    cat "$evidence/home-before-hot-window.txt"
    echo 'FAIL: Android launcher never acquired window focus after HOME'
    exit 1
fi
adb shell am start -W -n tv.herrin.app/.MainActivity > "$evidence/hot-launch.txt"
# "its current task has been brought to the front" is a VALID hot resume.
# Only the explicit top-most intent-delivery warning proves HOME never won.
if grep -q 'intent has been delivered to currently running top-most instance' "$evidence/hot-launch.txt"; then
    cat "$evidence/hot-launch.txt"
    echo 'FAIL: Hot launch was sent to an already top-most app; probe setup invalid'
    exit 1
fi
# am start -W can finish before WindowManager grants input to this window.
# Require the real input target, then send exactly one Right and one OK.
focused=0
for attempt in $(seq 1 20); do
    # API31's `windows` section omits display-level mCurrentFocus.
    adb shell dumpsys window > "$evidence/hot-window.txt"
    if grep -Eq 'mCurrentFocus=.*tv[.]herrin[.]app/.*MainActivity' "$evidence/hot-window.txt"; then focused=1; break; fi
    sleep 0.1
done
if [[ "$focused" != 1 ]]; then
    cat "$evidence/hot-window.txt"
    echo 'FAIL: WindowManager did not grant input to Herrin after resume'
    exit 1
fi
adb shell dumpsys input > "$evidence/hot-input-before.txt"
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
adb shell uiautomator dump /sdcard/herrin-startup.xml >/dev/null
adb exec-out cat /sdcard/herrin-startup.xml > "$evidence/hot-remote.xml"
python3 - "$evidence/hot-remote.xml" <<'PY'
import pathlib,sys
assert '精选电影' in pathlib.Path(sys.argv[1]).read_text(), 'Remote lost after startup/hot resume'
PY
if grep -q 'FATAL EXCEPTION' "$evidence/cold-diagnostics.txt"; then exit 1; fi
# Separate visual pass: screen recording perturbs timing, so it is never the perf source.
adb shell am force-stop tv.herrin.app
adb shell screenrecord --time-limit 6 /sdcard/herrin-startup.mp4 > "$evidence/recording.log" 2>&1 &
recording_pid=$!
adb shell am start -W -n tv.herrin.app/.MainActivity > "$evidence/visual-launch.txt"
wait "$recording_pid" || true
adb pull /sdcard/herrin-startup.mp4 "$evidence/visual-only-startup.mp4" || true
cat "$evidence/cold-launch.txt" "$evidence/hot-launch.txt" "$evidence/cold-diagnostics.txt"
