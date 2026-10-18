#!/usr/bin/env bash
#
# 云端模拟器 UI 门禁（v1.3.1 起）。
#
# 1. 跑 instrumentation 测试：单标题、56dp 顶栏、浅色白底黑字 / 深色黑底白字。
# 2. 测试通过后再分别以浅色 / 深色启动应用，截两张图作为 Actions artifact 证据。
#
# 任何一步失败都会让脚本以非 0 退出，从而阻止 Release workflow 发布。
set -euxo pipefail

gradle --no-daemon --stacktrace connectedDebugAndroidTest

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
OUT="$ROOT/app/build/reports/ui"
APP_ID="moe.hellorename"
ACTIVITY="$APP_ID/.MainActivity"

mkdir -p "$OUT"

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

  # UI 层级也留一份文本证据，便于排查截图里看不清的结构
  if adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1; then
    adb pull /sdcard/window_dump.xml "$OUT/$name-ui.xml" >/dev/null 2>&1 || true
  fi
}

capture no light
capture yes dark

adb shell rm -f /sdcard/window_dump.xml || true
