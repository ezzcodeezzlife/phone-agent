#!/usr/bin/env bash
# Runs one agent task on the connected device/emulator (debug build) and prints the run log.
# usage: tools/run-task.sh <auto|tree|screenshot|hybrid> "<task>" [model] [effort] [pkgs-to-force-stop,comma-separated]
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
MODE="$1"; TASK="$2"; MODEL="${3:-gpt-6.1-terra}"; EFFORT="${4:-low}"
BASE=/sdcard/Android/data/dev.phoneagent/files/runs
for p in ${5//,/ }; do $ADB shell am force-stop "$p"; done
before=$($ADB shell "ls $BASE 2>/dev/null" | tr -d '\r' | sort | tail -1)
$ADB shell am start -n dev.phoneagent/.MainActivity --es task "'${TASK//\'/}'" --es mode "$MODE" --es model "$MODEL" --es effort "$EFFORT" --ez run true >/dev/null
# wait for a new run folder with a summary.json (max 10 min)
for i in $(seq 1 300); do
  latest=$($ADB shell "ls $BASE 2>/dev/null" | tr -d '\r' | sort | tail -1)
  if [ -n "$latest" ] && [ "$latest" != "$before" ] && $ADB shell "test -f $BASE/$latest/summary.json" ; then break; fi
  sleep 2
done
echo "RUN $latest"
$ADB shell "cat $BASE/$latest/log.txt"
