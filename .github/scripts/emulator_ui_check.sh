#!/usr/bin/env bash
#
# 云端模拟器 UI 门禁（v1.3.1 起）。
#
# 1. 跑 instrumentation 测试：单标题、56dp 顶栏、浅色白底黑字 / 深色黑底白字。
# 2. 测试通过后重新安装 debug APK（connectedAndroidTest 结束时会卸掉它），
#    再分别以浅色 / 深色启动应用，截两张图作为 Actions artifact 证据。
#
# 任何一步失败都会让脚本以非 0 退出，从而阻止 Release workflow 发布。
set -euxo pipefail

gradle --no-daemon --stacktrace connectedDebugAndroidTest

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
OUT="$ROOT/app/build/reports/ui"
APP_ID="moe.hellorename"
ACTIVITY="$APP_ID/.MainActivity"
DEBUG_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

mkdir -p "$OUT"

# connectedDebugAndroidTest 结束后 AGP 会卸载被测应用；不重新安装的话，
# 下面 am start 会失败，截图只会拍到桌面（v1.3.1 第一次跑 CI 就踩到了）。
test -f "$DEBUG_APK"
adb install -r "$DEBUG_APK"
if ! adb shell pm path "$APP_ID" | grep -q '^package:'; then
  echo "debug APK 安装后仍找不到包 $APP_ID" >&2
  exit 1
fi

capture() {
  local mode="$1"
  local name="$2"

  adb shell cmd uimode night "$mode"
  sleep 2
  adb shell am force-stop "$APP_ID"
  adb shell am start -W -n "$ACTIVITY"
  sleep 3

  adb exec-out screencap -p > "$OUT/$name.png"
  test -s "$OUT/$name.png"

  # UI 层级既留作证据，也用来确认前台确实是本应用而不是桌面。
  adb shell uiautomator dump /sdcard/window_dump.xml
  adb pull /sdcard/window_dump.xml "$OUT/$name-ui.xml" >/dev/null
  grep -q "package=\"$APP_ID\"" "$OUT/$name-ui.xml"
}

capture no light
capture yes dark

adb shell rm -f /sdcard/window_dump.xml || true
