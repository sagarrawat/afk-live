#!/usr/bin/env bash
# OPS-6 soak test: runs K streams for N minutes through the worker's own streaming code (studio, copy
# mode, relays, stitcher) into local sinks on 127.0.0.1, then writes summary.json and report.md with
# pass/fail checks (leaks, file descriptors, threads, timestamps, A/V drift, reconnects, encoder speed,
# CPU per stream). Nothing leaves the machine; no database or cloud account is needed.
#
#   afklive-scripts/soak.sh [--minutes N] [--copy-mode] [--streams K] [--destinations D]
#                           [--height 720] [--fps 30] [--switch-minutes M] [--out DIR]
#
#   --minutes N         how long to stream (default 360 = 6 h; 10 is the smoke run)
#   --copy-mode         start in copy mode (FO-2) and switch studio<->copy every M minutes
#   --streams K         streams at once (default 2); --destinations D sinks per stream (default 2)
#   --switch-minutes M  default 10, or a sixth of a run shorter than an hour
#
# Needs Java 21, Maven and FFmpeg on PATH. Run it on staging or a spare machine, never on a server that is
# streaming for customers: it uses as much CPU as K real streams. Exit code 0 means every check passed.
# What "pass" means and how to run the 6-hour soak: docs/operations.md, "Soak test (OPS-6)".
set -euo pipefail

minutes=360
copy=false
streams=2
dests=2
height=720
fps=30
switch=""
out=""
while [ $# -gt 0 ]; do
  case $1 in
    --minutes) minutes=$2; shift 2 ;;
    --copy-mode) copy=true; shift ;;
    --streams) streams=$2; shift 2 ;;
    --destinations) dests=$2; shift 2 ;;
    --height) height=$2; shift 2 ;;
    --fps) fps=$2; shift 2 ;;
    --switch-minutes) switch=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "Unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
done

command -v ffmpeg >/dev/null || { echo "FFmpeg must be on PATH." >&2; exit 2; }
repo=$(cd "$(dirname "$0")/.." && pwd)
mode=$([ "$copy" = true ] && echo copy || echo studio)
out=${out:-$repo/afklive-web/target/soak/$(date -u +%Y%m%dT%H%M%SZ)-$mode-${minutes}m}
mkdir -p "$out"
echo "Soak: $minutes min, $streams stream(s) x $dests destination(s), ${height}p$fps, $mode mode -> $out"

status=0
# -Xmx512m as the worker's systemd unit. The soak test is excluded from normal builds (soak.excludedGroups).
(cd "$repo" && mvn -B -pl afklive-web -am test -DskipUi -DskipUiTests \
  -Dtest=SoakTest -Dsurefire.failIfNoSpecifiedTests=false -Dsoak.excludedGroups=none \
  -DargLine="-Xmx512m" \
  -Dsoak.minutes="$minutes" -Dsoak.copyMode="$copy" -Dsoak.streams="$streams" -Dsoak.destinations="$dests" \
  -Dsoak.height="$height" -Dsoak.fps="$fps" -Dsoak.switchMinutes="$switch" -Dsoak.out="$out") \
  >"$out/maven.log" 2>&1 || status=$?

if [ -f "$out/report.md" ]; then
  cat "$out/report.md"
  echo
  echo "Details: $out/summary.json"
else
  echo "The soak didn't produce a report; see $out/maven.log" >&2
  tail -40 "$out/maven.log" >&2
  [ "$status" -ne 0 ] || status=1
fi
exit "$status"
