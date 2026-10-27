#!/usr/bin/env bash
# Browser end-to-end test of the creator journey (CreatorJourneyE2ETest): the real app (Spring Boot, an
# embedded PostgreSQL, the stream worker in the same process with real FFmpeg, the built React app) driven
# by Playwright in headless Chromium:
#   sign up -> setup -> upload a video -> add a destination (a local RTMP sink) -> Go live -> "On Test sink"
#   while the sink receives data -> stop -> the run under Recent runs; then at 375 px wide the main screens
#   fit the phone, and a paid plan's quote (rupees, GST included) reaches the PhonePe page (a test double;
#   nothing calls PhonePe).
#
#   afklive-scripts/e2e.sh [extra Maven arguments, e.g. -DskipUi to reuse an app already built]
#
# Needs Java 21, Maven, Node 20+ (builds the app) and FFmpeg on PATH, and Chromium for Playwright 1.56
# (npx playwright@1.56.0 install --with-deps chromium; or set PLAYWRIGHT_BROWSERS_PATH to where it is, or
# E2E_CHROMIUM to a chrome binary). Nothing leaves the machine. Takes about two minutes after the build.
# On failure a screenshot and a Playwright trace (npx playwright show-trace trace.zip) are in
# afklive-web/target/e2e/<test>/. Excluded from normal builds (e2e Maven profile, @Tag("e2e")).
# More: docs/operations.md, "End-to-end test".
set -euo pipefail

case ${1:-} in
  -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
esac

command -v ffmpeg >/dev/null || { echo "FFmpeg must be on PATH." >&2; exit 2; }
repo=$(cd "$(dirname "$0")/.." && pwd)
rm -rf "$repo/afklive-web/target/e2e"

status=0
(cd "$repo" && mvn -B -pl afklive-web -am -Pe2e test -DskipUiTests \
  -Dtest=CreatorJourneyE2ETest -Dsurefire.failIfNoSpecifiedTests=false "$@") || status=$?

if [ "$status" -ne 0 ] && [ -d "$repo/afklive-web/target/e2e" ]; then
  echo "Screenshots and traces of what failed:" >&2
  find "$repo/afklive-web/target/e2e" -type f >&2
fi
exit "$status"
