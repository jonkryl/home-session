#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts
adb wait-for-device
device_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
font_scale_changed=false

collect_device_proof() {
    local status=$?
    trap - EXIT
    set +e
    if [ "$status" -ne 0 ]; then
        timeout 10s adb shell dumpsys power > ci-artifacts/failure-power.txt
        timeout 10s adb shell dumpsys window > ci-artifacts/failure-window.txt
        timeout 10s adb shell dumpsys activity top > ci-artifacts/failure-activity.txt
        timeout 10s adb exec-out screencap -p > ci-artifacts/failure-screen.png
    fi
    timeout 15s adb pull /sdcard/Android/data/com.jonkryl.homesession/files/screenshots ci-artifacts/screenshots
    timeout 10s adb shell dumpsys package com.jonkryl.homesession > ci-artifacts/package.txt
    timeout 10s adb logcat -d -v threadtime > ci-artifacts/logcat.txt
    if [ "$font_scale_changed" = true ]; then
        timeout 10s adb shell settings put system font_scale 1.0 >/dev/null 2>&1
    fi
    exit "$status"
}
trap collect_device_proof EXIT

prepare_display() {
    adb shell settings put system screen_off_timeout 1800000
    adb shell svc power stayon true
    adb shell input keyevent 224
    if [ "$device_api" -ge 26 ]; then
        adb shell wm dismiss-keyguard || adb shell input keyevent 82
    else
        adb shell input keyevent 82
    fi
}
prepare_display
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$(find ci-apks -name 'app-debug.apk' -print -quit)"
adb install -r "$(find ci-apks -name 'app-debug-androidTest.apk' -print -quit)"
adb shell pm clear com.jonkryl.homesession

run_instrumentation() {
    local report="$1"
    local class="$2"
    prepare_display
    adb shell dumpsys power > "${report%.txt}-power-before.txt"
    adb shell am instrument -w -r -e class "$class" com.jonkryl.homesession.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
    python3 scripts/check-instrumentation.py "$report"
}

# Every invocation launches a fresh real application process; state is written through the UI.
run_instrumentation ci-artifacts/01-rooms-tasks-session.txt com.jonkryl.homesession.HomeSessionJourneyTest
adb shell am force-stop com.jonkryl.homesession
run_instrumentation ci-artifacts/02-process-restart-history.txt com.jonkryl.homesession.RestartAndHistoryTest
font_scale_changed=true
adb shell settings put system font_scale 2.0
adb shell am force-stop com.jonkryl.homesession
run_instrumentation ci-artifacts/03-font-200-percent.txt com.jonkryl.homesession.LargeFontAccessibilityTest
adb shell settings put system font_scale 1.0
font_scale_changed=false
adb pull /sdcard/Android/data/com.jonkryl.homesession/files/screenshots ci-artifacts/screenshots
python3 scripts/verify-device-proof.py "$device_api"
