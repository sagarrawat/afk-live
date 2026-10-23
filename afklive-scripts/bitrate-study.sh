#!/usr/bin/env bash
# FO-4: how low can a profile's bitrate go before quality drops? Encodes each input the way a live
# stream does (x264 veryfast, constant bitrate, keyframe every 2 s, fixed frame rate) at several
# bitrates and compares each to a near-lossless reference with SSIM (1.0 = identical).
#
#   afklive-scripts/bitrate-study.sh [-s 1280x720] [-r 30] [-t 60] [-b "2500 3000 3500 4000"] file...
#
# Run it on staging or a laptop with real creator footage (gameplay, a music still, a talking head),
# never on a server that is streaming: it uses all the CPU it can get. Prints one row per file and
# bitrate; pick the lowest bitrate whose SSIM is within 0.005 of the highest one, per kind of footage.
set -euo pipefail

size=1280x720
fps=30
seconds=60
rates="2500 3000 3500 4000"
while getopts "s:r:t:b:h" opt; do
  case $opt in
    s) size=$OPTARG ;;
    r) fps=$OPTARG ;;
    t) seconds=$OPTARG ;;
    b) rates=$OPTARG ;;
    *) sed -n '2,12p' "$0"; exit 0 ;;
  esac
done
shift $((OPTIND - 1))
[ $# -gt 0 ] || { echo "Give at least one video file." >&2; exit 1; }

w=${size%x*}
h=${size#*x}
gop=$((fps * 2))
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fit="fps=$fps,scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p"

printf '%-32s %8s %8s %10s\n' file kbps ssim "MB/hour"
for f in "$@"; do
  name=$(basename "$f")
  ref="$work/ref.mkv"
  # What the encoder sees: the source at the stream's size and rate, kept near-lossless.
  ffmpeg -nostdin -hide_banner -loglevel error -y -t "$seconds" -i "$f" -an -vf "$fit" -c:v libx264 -preset veryfast -crf 4 "$ref"
  for kbps in $rates; do
    out="$work/$kbps.ts"
    ffmpeg -nostdin -hide_banner -loglevel error -y -i "$ref" -an -c:v libx264 -preset veryfast \
      -b:v "${kbps}k" -minrate "${kbps}k" -maxrate "${kbps}k" -bufsize "$((kbps * 2))k" \
      -x264-params nal-hrd=cbr:force-cfr=1 -g "$gop" -keyint_min "$gop" -sc_threshold 0 -f mpegts "$out"
    ssim=$(ffmpeg -nostdin -hide_banner -i "$out" -i "$ref" -lavfi "[0:v][1:v]ssim" -f null - 2>&1 \
      | sed -n 's/.*All:\([0-9.]*\).*/\1/p' | tail -1)
    # Video plus 128 kbps audio, per hour of streaming.
    mb_hour=$(( (kbps + 128) * 3600 / 8 / 1000 ))
    printf '%-32s %8s %8s %10s\n' "${name:0:32}" "$kbps" "${ssim:-?}" "$mb_hour"
  done
done
