#!/usr/bin/env bash
# AI-1 / GR-10: runs the title writer on 30 realistic streams (lofi, devotional, gaming, podcasts, kids rhymes in
# English, Hindi, Tamil, Telugu, Bengali and Marathi; afklive-web/src/test/resources/ai-eval/inputs.json) against
# the real model, checks every draft like the offline test does, and writes a report to read:
#   afklive-web/target/ai-eval/report.md
#
#   ANTHROPIC_API_KEY=... afklive-scripts/ai-eval.sh            # Claude (ANTHROPIC_MODEL, default claude-haiku-5-5)
#   GEMINI_API_KEY=... afklive-scripts/ai-eval.sh               # Gemini, when no Anthropic key is set
#
# 30 calls; with Claude Haiku about one US cent. Sends only the fixture text (no user data). Not part of any normal
# build (Maven profile ai-eval, @Tag("ai-eval")). The checks failing doesn't fail the script: read the report.
set -euo pipefail

case ${1:-} in
  -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
esac

if [[ -z "${ANTHROPIC_API_KEY:-}" && -z "${GEMINI_API_KEY:-}" ]]; then
  echo "Set ANTHROPIC_API_KEY (or GEMINI_API_KEY) first." >&2
  exit 2
fi
repo=$(cd "$(dirname "$0")/.." && pwd)
(cd "$repo" && mvn -B -q -pl afklive-web -Pai-eval test -DskipUi -DskipUiTests \
  -Dtest=TitleWriterLiveEval -Dsurefire.failIfNoSpecifiedTests=false "$@")
report="$repo/afklive-web/target/ai-eval/report.md"
[[ -f "$report" ]] && { head -3 "$report"; echo "Report: $report"; }
