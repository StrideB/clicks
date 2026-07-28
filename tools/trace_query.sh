#!/usr/bin/env bash
# Read a Perfetto trace and say where the CPU went, per thread NAME.
#
#   adb shell perfetto -o /data/misc/perfetto-traces/t.pftrace -t 30s sched freq idle
#   adb pull /data/misc/perfetto-traces/t.pftrace
#   bash tools/trace_query.sh t.pftrace
#
# Why this and not /proc sampling: kernel scheduling records every thread that ever ran, including
# ones created and destroyed between two snapshots. In the load capture that was about 73% of the
# cost — invisible to sampling, and the reason the per-thread list looked like it explained the
# number when it accounted for under a fifth of it.
set -uo pipefail

TRACE="${1:-t.pftrace}"
PKG="${2:-com.fran.teclas}"
[ -f "$TRACE" ] || { echo "No such trace: $TRACE" >&2; exit 1; }

TP="./trace_processor"
if [ ! -x "$TP" ]; then
  echo "Fetching trace_processor…"
  curl -fsSL -o "$TP" https://get.perfetto.dev/trace_processor || {
    echo "Download failed. Open $TRACE at https://ui.perfetto.dev instead." >&2; exit 1; }
  chmod +x "$TP"
fi

# One query per invocation: trace_processor allows only the FINAL statement to be a SELECT, so a
# file with three of them errors out and prints nothing at all.
Q="$(mktemp)"; trap 'rm -f "$Q"' EXIT
run() {  # run <heading> <sql>
  echo; echo "== $1 =="
  printf '%s\n' "$2" > "$Q"
  "$TP" "$TRACE" -q "$Q" 2>/dev/null
}

run "WHOLE DEVICE, TOP PROCESSES (cpu seconds)" "
select p.name as process, round(sum(s.dur)/1e9, 1) as cpu_s
from sched_slice s join thread t using(utid) join process p using(upid)
group by p.name order by cpu_s desc limit 12;"

run "$PKG, BY THREAD NAME  (threads = how many distinct threads shared that name)" "
select t.name as thread, round(sum(s.dur)/1e9, 2) as cpu_s,
       count(distinct t.utid) as threads, count(*) as slices
from sched_slice s join thread t using(utid) join process p using(upid)
where p.name = '$PKG'
group by t.name order by cpu_s desc limit 25;"

run "$PKG, TOTAL" "
select round(sum(s.dur)/1e9, 1) as cpu_s, count(distinct t.utid) as distinct_threads
from sched_slice s join thread t using(utid) join process p using(upid)
where p.name = '$PKG';"
