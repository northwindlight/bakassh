#!/usr/bin/env bash
# 在 android-emulator-runner 启动的模拟器里安装调试包、走界面截图，再运行仓库自带的 Android 回归。
# 用法：emulator.sh <输出目录> <app-debug.apk> <app-debug-androidTest.apk>
set -uo pipefail
out=$1 app_apk=$2 test_apk=$3
mkdir -p "$out"
adb wait-for-device
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0930
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile hide
demo notifications -e visible false
# 后台应用无响应时不弹窗，避免遮住被测界面。
adb shell settings put secure anr_show_background 0 || true
adb install -r -g "$app_apk"
status=0
python3 .github/ci/tour.py "$out" || status=1

adb install -r -g "$test_apk"
adb shell pm list instrumentation > "$out/instrumentation-list.txt" 2>&1
runner=dev.northwind.bakassh.test/dev.northwind.bakassh.ZoomInstrumentation
for mode in default security; do
    args=()
    [[ $mode != default ]] && args=(-e mode "$mode")
    timeout 600 adb shell am instrument -w "${args[@]}" "$runner" > "$out/instrument-$mode.txt" 2>&1
    # 自定义 Instrumentation 在 -w 模式下只输出 stream 文本：成功以 PASS 开头，失败为异常栈。
    if grep -q '^PASS ' "$out/instrument-$mode.txt"; then echo "instrument $mode: PASS"
    else echo "instrument $mode: FAIL"; status=1; fi
done
adb logcat -d -b crash > "$out/logcat-crash.txt" 2>&1 || true
exit $status
