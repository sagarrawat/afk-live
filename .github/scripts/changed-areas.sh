#!/bin/bash
# Which PR checks a change needs. Reads changed file paths (one per line) on stdin and prints
#   java=true|false   the full build: Java + FFmpeg tests (mvn -B verify, which also runs the UI tests)
#   ui=true|false     UI typecheck, tests and build only (afklive-ui/ changed and nothing that needs Java)
# Docs (docs/**, any *.md) need nothing. Anything this script doesn't recognise runs the full build.
# Used by .github/workflows/pr-checks.yml; tested by PrChecksWorkflowTest.
set -euo pipefail

java=false
ui=false
while IFS= read -r path || [ -n "$path" ]; do
  [ -n "$path" ] || continue
  case "$path" in
    docs/* | *.md) ;;
    afklive-ui/*) ui=true ;;
    *) java=true ;;
  esac
done

if [ "$java" = true ]; then
  ui=false # the full build runs the UI tests too
fi
echo "java=$java"
echo "ui=$ui"
