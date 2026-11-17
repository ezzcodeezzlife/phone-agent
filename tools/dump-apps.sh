#!/usr/bin/env bash
# Opens each app and saves what the agent would see (tree + marked screenshot) without an AI call.
# usage: tools/dump-apps.sh pkg1 pkg2 ...   → files/dumps/<pkg>/
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
for p in "$@"; do
  $ADB shell monkey -p "$p" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  $ADB shell am broadcast -a dev.phoneagent.DUMP -p dev.phoneagent --es name "$p" >/dev/null
  sleep 3
  echo "$p: $($ADB shell "head -2 /sdcard/Android/data/dev.phoneagent/files/dumps/$p/tree.txt" | tr '\r\n' '  ')"
done
