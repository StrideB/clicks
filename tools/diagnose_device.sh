#!/usr/bin/env bash
# One-shot device diagnostic for Teclas: crashes, heat, wakelocks, CPU, and Space state.
# Run from a machine with the phone connected over adb:
#
#   bash tools/diagnose_device.sh              # capture now
#   bash tools/diagnose_device.sh --reset      # reset battery stats first, then capture after use
#
# Writes a timestamped folder under diagnostics/ with one file per probe, plus SUMMARY.txt.
# Nothing here modifies the phone except the optional --reset (which only clears batterystats).
set -uo pipefail   # deliberately NOT -e: a probe that fails must not abort the rest

PKG="com.fran.teclas"
STAMP="$(date +%Y%m%d-%H%M%S)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/diagnostics/$STAMP"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install platform-tools first." >&2; exit 1
fi
if [ -z "$(adb devices | sed -n '2p' | awk '{print $1}')" ]; then
  echo "No device attached. Plug the phone in, unlock it, and accept the USB-debugging prompt." >&2
  exit 1
fi

if [ "${1:-}" = "--reset" ]; then
  echo "Resetting battery stats. Use the phone normally for 15-30 min, then run this again without --reset."
  adb shell dumpsys batterystats --reset >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  echo "Done. Counters cleared."
  exit 0
fi

mkdir -p "$OUT"
echo "Capturing to $OUT"

cap() {  # cap <name> <shell command...>
  local name="$1"; shift
  echo "  - $name"
  { echo "\$ $*"; echo; adb shell "$@" 2>&1; } > "$OUT/$name.txt"
}

# --- Crashes: the single most important signal ---------------------------------------------
echo "  - crashes"
adb logcat -d -b crash 2>&1 > "$OUT/crash-buffer.txt"
adb logcat -d 2>&1 | grep -iE "FATAL EXCEPTION|ANR in|Force finishing|$PKG.*died|lowmemorykiller" \
  > "$OUT/crash-grep.txt" 2>&1

# --- Heat: who is actually burning CPU / holding wakelocks ---------------------------------
cap cpu-top          "top -n 1 -m 15 -o %CPU,RES,PID,ARGS"
cap battery-app      "dumpsys batterystats $PKG"
cap wakelocks        "dumpsys power | grep -A 40 'Wake Locks'"
cap alarms           "dumpsys alarm | grep -A 6 $PKG"
cap jobs             "dumpsys jobscheduler | grep -A 12 $PKG"
cap thermal          "dumpsys thermalservice"
cap procstats        "dumpsys procstats --hours 3 $PKG"

# --- The accessibility service: the prime suspect for system-wide event load ----------------
cap a11y             "settings get secure enabled_accessibility_services"
cap a11y-dump        "dumpsys accessibility"

# --- App state ------------------------------------------------------------------------------
cap pkg-version      "dumpsys package $PKG | grep -E 'versionName|versionCode|firstInstall|lastUpdate'"
cap meminfo          "dumpsys meminfo $PKG"
cap default-home     "cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME"

# --- Teclas' own logs: lexicon load, Spaces, prediction ------------------------------------
echo "  - teclas logs"
adb logcat -d 2>&1 | grep -iE "TypoLexicon|Teclas|SpaceManager|ContextSensors|WifiPlaces|GlideNeural" \
  | tail -400 > "$OUT/teclas-log.txt"

# --- Summary --------------------------------------------------------------------------------
{
  echo "Teclas device diagnostic - $STAMP"
  echo "================================================"
  echo
  echo "VERSION"
  grep -E "versionName|versionCode" "$OUT/pkg-version.txt" 2>/dev/null | sed 's/^/  /'
  echo
  echo "DEFAULT LAUNCHER"
  grep -i "packageName\|name=" "$OUT/default-home.txt" 2>/dev/null | head -3 | sed 's/^/  /'
  echo
  echo "CRASHES  (empty is good)"
  if [ -s "$OUT/crash-grep.txt" ]; then sed 's/^/  /' "$OUT/crash-grep.txt" | head -25
  else echo "  none found in the current log buffer"; fi
  echo
  echo "TOP CPU"
  sed -n '3,12p' "$OUT/cpu-top.txt" 2>/dev/null | sed 's/^/  /'
  echo
  echo "TYPO LEXICON"
  grep -i "TypoLexicon" "$OUT/teclas-log.txt" 2>/dev/null | tail -3 | sed 's/^/  /' \
    || echo "  no TypoLexicon line seen (keyboard may not have loaded its dictionary yet)"
  echo
  echo "ACCESSIBILITY SERVICES ENABLED"
  sed -n '2p' "$OUT/a11y.txt" 2>/dev/null | tr ':' '\n' | sed 's/^/  /'
  echo
  echo "Full probes in: $OUT"
} > "$OUT/SUMMARY.txt"

cat "$OUT/SUMMARY.txt"
echo
echo "Zip and share the whole folder if you want it analyzed:"
echo "  cd $ROOT && zip -r diagnostics-$STAMP.zip diagnostics/$STAMP"
