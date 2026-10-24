#!/usr/bin/env bash
# Regenerates the homepage's example frames (site/public/img/frame-*.webp) and the example QR code.
# The frames are drawn by the real studio (SiteFrames in afklive-web's tests): the niche templates'
# layers on brand illustrations. Needs Maven and FFmpeg with libwebp. Run from anywhere:
#   afklive-ui/site/frames.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mvn -B -q -f "$repo/pom.xml" -pl afklive-web -am test -DskipUi -DskipUiTests \
  -Dtest=SiteFramesTest -Dsurefire.failIfNoSpecifiedTests=false -Dsite.frames.out="$tmp"

out="$here/public/img"
for png in "$tmp"/frame-*.png; do
  name="$(basename "$png" .png)"
  ffmpeg -loglevel error -y -i "$png" -c:v libwebp -quality 82 -compression_level 6 "$out/$name.webp"
  ffmpeg -loglevel error -y -i "$png" -vf scale=640:-2:flags=lanczos -c:v libwebp -quality 80 -compression_level 6 "$out/$name-640.webp"
done
cp "$tmp/qr-example.svg" "$out/qr-example.svg"
ls -la "$out"
