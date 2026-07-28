#!/usr/bin/env bash
# What is actually burning CPU on this phone?
#
#   bash tools/idle_watch.sh [minutes]            # idle: leave the phone alone
#   bash tools/idle_watch.sh --use [minutes]      # load: use the phone normally
#
# Takes a CPU snapshot, waits out a window, snapshots again, and reports the DELTA. That is the
# whole point: a single `top` tells you what is running, which is everything and nothing. Only the
# change over a window shows what actually spent cycles, and it names the exact thread doing it.
#
# Idle mode answers "why is it warm in my pocket". Load mode answers "why does it get warm while I
# use it" -- same measurement, so the two runs are directly comparable.
#
# Nothing here modifies the phone except clearing batterystats and the log buffer at the start.
set -uo pipefail   # deliberately NOT -e: a probe that fails must not abort the rest

PKG="com.fran.teclas"
MODE="idle"
if [ "${1:-}" = "--use" ] || [ "${1:-}" = "--load" ]; then MODE="use"; shift; fi
MINUTES="${1:-10}"
STAMP="$(date +%Y%m%d-%H%M%S)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/diagnostics/$MODE-$STAMP"

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
# The probe's own shell is filtered out: reading /proc for every process costs real CPU, and the
# first run of this script ranked the measurement above most of the apps it was measuring.
delta() {
  awk 'NR==FNR { was[$1]=$2; next }
       { d = $2 - (($1 in was) ? was[$1] : 0)
         if (d <= 0) next
         name=""; for (i=3;i<=NF;i++) name = name (i>3?" ":"") $i
         if (name ~ /^\/system\/bin\/sh/) next
         print d, name }' \
      "$1" "$2" | sort -rn | head -15
}

# Same delta, but summed by NAME instead of listed per pid/tid.
# A coroutine pool is N threads all called "DefaultDispatcher-worker-K", truncated to the same 15
# characters in comm. Listed individually they each look modest and the top-15 cut hides the rest;
# summed, the pool's real cost appears. delta_by_name <before> <after> [limit]
delta_by_name() {
  awk 'NR==FNR { was[$1]=$2; next }
       { d = $2 - (($1 in was) ? was[$1] : 0)
         if (d <= 0) next
         name=""; for (i=3;i<=NF;i++) name = name (i>3?" ":"") $i
         if (name ~ /^\/system\/bin\/sh/) next
         tot[name] += d }
       END { for (n in tot) print tot[n], n }' "$1" "$2" | sort -rn | head -"${3:-15}"
}

# Total delta across every row — used to show how much of the process is NOT accounted for by the
# threads we sampled. Threads created and destroyed inside the window appear in neither snapshot,
# so a large remainder means the work is in short-lived threads and the per-thread list is
# misleading on its own. Saying so beats quietly under-reporting.
delta_sum() {
  awk 'NR==FNR { was[$1]=$2; next }
       { d = $2 - (($1 in was) ? was[$1] : 0); if (d > 0) s += d } END { print s+0 }' "$1" "$2"
}

# Delta for the rows whose name is exactly $3.
delta_for() {
  awk -v want="$3" 'NR==FNR { was[$1]=$2; next }
       { d = $2 - (($1 in was) ? was[$1] : 0)
         if (d <= 0) next
         name=""; for (i=3;i<=NF;i++) name = name (i>3?" ":"") $i
         if (name == want) s += d } END { print s+0 }' "$1" "$2"
}

# Screen state decides what this measurement even means: with the display on, the panel is the
# biggest draw on the phone and is attributed to no app at all, so a "screen on" run cannot settle
# an idle-drain question. Worth saying up front rather than discovering it in the numbers.
screen_state() { adb shell dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*'; }

echo "Resetting counters…"
adb shell dumpsys batterystats --reset >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1

snap before
SCREEN_BEFORE="$(screen_state)"
echo
if [ "$MODE" = "use" ]; then
  echo "  >>> Use the phone NORMALLY for $MINUTES minutes. <<<"
  echo "      Type a lot — that is the part under suspicion. Open the launcher, search,"
  echo "      swipe between Spaces, send messages. The screen being on is expected here;"
  echo "      we are attributing load, not settling an idle question."
else
  echo "  >>> Lock the phone and put it down. Do not touch it for $MINUTES minutes. <<<"
  echo "      Press the side key to turn the screen OFF — with it on, the display is the"
  echo "      biggest draw on the phone and belongs to no app, which buries everything else."
fi
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
  if [ "$MODE" = "use" ]; then echo "Teclas LOAD diagnostic — $MINUTES minutes of normal use — $STAMP"; else echo "Teclas IDLE diagnostic — $MINUTES minutes untouched — $STAMP"; fi
  echo "==============================================================="
  echo
  sed 's/^/  /' "$OUT/version.txt" 2>/dev/null
  echo
  if [ "$MODE" = "use" ]; then echo "SCREEN  (Awake is expected for a load run)"; else echo "SCREEN  (an 'Awake' run cannot settle an idle question — the panel dominates and"; echo "         is attributed to no app)"; fi
  echo "  at start: ${SCREEN_BEFORE:-unknown}    at end: $(screen_state)"
  echo
  echo "CPU USED, BY PROCESS   <-- the decisive number"
  echo "  (jiffies, usually 10ms each. A truly idle phone shows almost nothing here."
  echo "   If $PKG is not near the top, the launcher is not what is heating the phone.)"
  delta "$OUT/proc-before.txt" "$OUT/proc-after.txt" | sed 's/^/  /'
  echo
  echo "CPU USED, BY THREAD INSIDE $PKG   (summed per thread NAME)"
  echo "  (a ggml worker means the model was generating; DefaultDispatch is the Kotlin"
  echo "   coroutine pool, so it is CPU-bound background work, not rendering)"
  delta_by_name "$OUT/thread-before.txt" "$OUT/thread-after.txt" 20 | sed 's/^/  /'
  PKG_TOTAL=$(delta_for "$OUT/proc-before.txt" "$OUT/proc-after.txt" "$PKG")
  THREAD_SUM=$(delta_sum "$OUT/thread-before.txt" "$OUT/thread-after.txt")
  echo
  echo "  process total: $PKG_TOTAL    accounted by sampled threads: $THREAD_SUM"
  echo "  unaccounted:   $((PKG_TOTAL - THREAD_SUM))"
  echo "  (A large remainder means the work happened in SHORT-LIVED threads — created and"
  echo "   destroyed inside the window, so present in neither snapshot. That is a finding in"
  echo "   itself: it points at churn, not at any thread named above.)"
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
echo "  cd $ROOT && zip -r $MODE-$STAMP.zip diagnostics/$MODE-$STAMP"
