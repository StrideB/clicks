#!/usr/bin/env bash
# Why is the phone warm while nobody is touching it?
#
#   bash tools/idle_watch.sh [minutes]      # default 10
#
# Takes a CPU snapshot, waits while you leave the phone alone, snapshots again, and reports the
# DELTA. That is the whole point: a single `top` tells you what is running, which on an idle phone
# is everything and nothing. Only the change over a quiet window shows what is actually burning
# cycles, and it names the exact thread inside Teclas doing it.
#
# Nothing here modifies the phone except clearing batterystats and the log buffer at the start.
set -uo pipefail   # deliberately NOT -e: a probe that fails must not abort the rest

PKG="com.fran.teclas"
MINUTES="${1:-10}"
STAMP="$(date +%Y%m%d-%H%M%S)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/diagnostics/idle-$STAMP"

command -v adb >/dev/null 2>&1 || { echo "adb not found. Install platform-tools first." >&2; exit 1; }
if [ -z "$(adb devices | sed -n '2p' | awk '{print $1}')" ]; then
  echo "No device attached. Plug the phone in, unlock it, accept the USB-debugging prompt." >&2
  exit 1
fi

mkdir -p "$OUT"

# Per-process CPU jiffies straight from /proc. Parsed by stripping through the ") " that closes
# comm, because a process name can itself contain spaces and parentheses and would shift every
# field after it. utime/stime are overall fields 14/15, so 12/13 of what remains.
read -r -d '' PROC_CPU <<'SH'
for p in /proc/[0-9]*; do
  s=$(cat $p/stat 2>/dev/null) || continue
  pid=${s%% *}
  rest=${s#*) }
  set -- $rest
  [ $# -ge 13 ] || continue
  name=$(cat $p/cmdline 2>/dev/null | tr '\0' ' ' | cut -c1-60)
  [ -n "$name" ] || name=$(cat $p/comm 2>/dev/null)
  echo "$pid $((${12:-0}+${13:-0})) ${name:-?}"
done
SH

# Same, per THREAD of our process. Thread names are the payoff: "teclas-llm-idle-release" or a
# ggml worker pins the blame on something specific instead of on "the app".
read -r -d '' THREAD_CPU <<'SH'
pid=$(pidof PKGNAME | tr ' ' '\n' | head -1)
[ -n "$pid" ] || exit 0
for t in /proc/$pid/task/[0-9]*; do
  s=$(cat $t/stat 2>/dev/null) || continue
  tid=${s%% *}
  rest=${s#*) }
  set -- $rest
  [ $# -ge 13 ] || continue
  echo "$tid $((${12:-0}+${13:-0})) $(cat $t/comm 2>/dev/null)"
done
SH
THREAD_CPU="${THREAD_CPU//PKGNAME/$PKG}"

snap() {  # snap <label>
  adb shell "$PROC_CPU"   > "$OUT/proc-$1.txt"   2>/dev/null
  adb shell "$THREAD_CPU" > "$OUT/thread-$1.txt" 2>/dev/null
  adb shell 'for z in /sys/class/thermal/thermal_zone*; do
      t=$(cat $z/type 2>/dev/null); v=$(cat $z/temp 2>/dev/null);
      [ -n "$t" ] && [ -n "$v" ] && echo "$t $v"; done' > "$OUT/thermal-$1.txt" 2>/dev/null
}

# delta <before> <after> -> "cost name", biggest first. Jiffies are typically 10ms.
delta() {
  awk 'NR==FNR { was[$1]=$2; next }
       { d = $2 - (($1 in was) ? was[$1] : 0)
         if (d > 0) { name=""; for (i=3;i<=NF;i++) name = name (i>3?" ":"") $i; print d, name } }' \
      "$1" "$2" | sort -rn | head -15
}

echo "Resetting counters…"
adb shell dumpsys batterystats --reset >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1

snap before
echo
echo "  >>> Lock the phone and put it down. Do not touch it for $MINUTES minutes. <<<"
echo
for i in $(seq "$MINUTES" -1 1); do printf "\r  %2d min remaining…  " "$i"; sleep 60; done
printf "\r  capturing…            \n"

snap after

echo "  - wakelocks, doze, alarms, jobs"
{ echo "== WAKELOCKS =="; adb shell 'dumpsys power | grep -iA 30 "wake locks"';
  echo; echo "== DOZE =="; adb shell dumpsys deviceidle;
  echo; echo "== ALARMS ($PKG) =="; adb shell "dumpsys alarm | grep -A 8 $PKG";
  echo; echo "== JOBS ($PKG) =="; adb shell "dumpsys jobscheduler | grep -A 12 $PKG";
} > "$OUT/holds.txt" 2>&1
adb shell dumpsys batterystats --charged > "$OUT/power-ranked.txt" 2>&1
adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb shell "dumpsys package $PKG | grep -E 'versionName|versionCode'" > "$OUT/version.txt" 2>&1

{
  echo "Teclas IDLE diagnostic — $MINUTES minutes untouched — $STAMP"
  echo "==============================================================="
  echo
  sed 's/^/  /' "$OUT/version.txt" 2>/dev/null
  echo
  echo "CPU USED WHILE IDLE, BY PROCESS   <-- the decisive number"
  echo "  (jiffies, usually 10ms each. A truly idle phone shows almost nothing here."
  echo "   If $PKG is not near the top, the launcher is not what is heating the phone.)"
  delta "$OUT/proc-before.txt" "$OUT/proc-after.txt" | sed 's/^/  /'
  echo
  echo "CPU USED WHILE IDLE, BY THREAD INSIDE $PKG"
  echo "  (thread names say WHICH part: a ggml worker means the model was generating;"
  echo "   teclas-llm-idle-release should appear once and cost nothing)"
  delta "$OUT/thread-before.txt" "$OUT/thread-after.txt" | sed 's/^/  /'
  echo
  echo "TEMPERATURE CHANGE  (milli-degrees C: 45000 = 45C)"
  join "$OUT/thermal-before.txt" "$OUT/thermal-after.txt" 2>/dev/null \
    | awk '{ d=$3-$2; if (d>500 || d<-500) printf "%-28s %6.1fC -> %6.1fC  (%+.1f)\n", $1, $2/1000, $3/1000, d/1000 }' \
    | sort -k4 -rn | head -8 | sed 's/^/  /'
  echo
  echo "PARTIAL WAKELOCKS HELD  (anything here kept the CPU awake with the screen off)"
  sed -n '/== WAKELOCKS ==/,/== DOZE ==/p' "$OUT/holds.txt" | grep -i "partial\|$PKG" | head -12 | sed 's/^/  /'
  echo
  echo "ESTIMATED POWER USE, RANKED"
  sed -n '/Estimated power use (mAh)/,/^$/p' "$OUT/power-ranked.txt" 2>/dev/null | head -20 | sed 's/^/  /'
  echo
  echo "CRASHES  (empty is good)"
  grep -c . "$OUT/crash.txt" >/dev/null 2>&1 && head -5 "$OUT/crash.txt" | sed 's/^/  /'
  echo
  echo "Full probes in: $OUT"
} > "$OUT/SUMMARY.txt"

cat "$OUT/SUMMARY.txt"
echo
echo "Share the whole folder if you want it read:"
echo "  cd $ROOT && zip -r idle-$STAMP.zip diagnostics/idle-$STAMP"
