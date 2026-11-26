#!/usr/bin/env bash
# S4-11: paired capture of the outbox event-admission burst (docs/SPRINT4_MICROOPT_DANIEL.md).
#
#   scripts/profiling/s4-11-burst.sh <output-dir> <representative|stress> <runs>
#
# Expects the profile APK already installed and compiled (`cmd package compile -m speed -f`) and exactly one device.
# Per run: start the Perfetto trace (campusmeal.pbtxt, unchanged), launch OutboxBurstActivity in a fresh process,
# wait for its result line, stop the trace and pull it. Then extracts the sections inside the burst window.
# Nothing reaches the backend: the burst uses a disposable database, a synthetic account and no network.
set -euo pipefail

out=${1:?output dir}; workload=${2:?representative or stress}; runs=${3:?number of runs}
here=$(cd "$(dirname "$0")" && pwd)
pkg=com.campusmeal.android.profile
activity=$pkg/com.campusmeal.android.profiling.OutboxBurstActivity
config=/data/misc/perfetto-configs/campusmeal.pbtxt
remote=/data/misc/perfetto-traces/s4-11-run.perfetto-trace

mkdir -p "$out"
adb push "$here/campusmeal.pbtxt" "$config" >/dev/null

for n in $(seq 1 "$runs"); do
  i=$(printf %02d "$n")
  adb logcat -c
  adb shell rm -f "$remote"
  pid=$(adb shell perfetto --background --txt -c "$config" -o "$remote" | tr -d '\r' | tail -n 1)
  sleep 2
  adb shell am start -W -S -n "$activity" --es cm.outbox.burst "$workload" >/dev/null
  line=""
  for _ in $(seq 1 120); do
    line=$(adb logcat -d -s CampusMealBurst:I | grep -m 1 'S4-11 burst' || true)
    [ -n "$line" ] && break
    sleep 1
  done
  [ -n "$line" ] || { echo "run $i: no result line after 120 s" >&2; exit 1; }
  sleep 1
  adb shell kill -TERM "$pid" || true
  while adb shell "kill -0 $pid 2>/dev/null"; do sleep 1; done
  # The trace file is finalized just after the process exits: wait until its size is non-zero and stable.
  size=0
  for _ in $(seq 1 30); do
    sleep 1
    now=$(adb shell stat -c %s "$remote" 2>/dev/null | tr -d '\r' || echo 0)
    [ "$now" != 0 ] && [ "$now" = "$size" ] && break
    size=$now
  done
  adb pull "$remote" "$out/run-$i.perfetto-trace" >/dev/null
  echo "run-$i ${line#*: }" | tee -a "$out/burst-log.txt"
  case $line in *FAILED*|*admitted=0*) echo "run $i failed" >&2; exit 1 ;; esac
done

node "$here/sections.js" "$out"/run-*.perfetto-trace > "$out/sections-all.csv"
node "$here/s4-11-window.js" "$out/sections-all.csv" "$out/sections.csv" "$out/totals.csv"
node "$here/summary.js" "$out/sections.csv" > "$out/summary.txt"
cat "$out/summary.txt" "$out/totals.csv"
