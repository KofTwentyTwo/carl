#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
usage() {
  echo 'Usage: verify-live-evaluation.sh controlled|live [--ordinary-talk]'
  echo 'Requires CARL_QUALIFIED_DISTRIBUTION and CARL_EVALUATION_TEST_CLASSPATH (existing file).'
  echo 'Live additionally requires explicit synthetic authorization, model and private fixture startup credential.'
}
if [[ $# -lt 1 || "$1" == '--help' ]]; then
  usage
  exit 0
fi
mode="$1"
if [[ "$mode" != controlled && "$mode" != live ]]; then
  usage >&2
  exit 1
fi
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
: "${CARL_QUALIFIED_DISTRIBUTION:?Set the exact parent-reviewed frozen distribution}"
: "${CARL_EVALUATION_TEST_CLASSPATH:?Set the existing frozen package test-classpath.txt file}"
distribution="$(cd -- "$CARL_QUALIFIED_DISTRIBUTION" && pwd -P)"
test -f "$distribution/app.jar"
test -f "$CARL_EVALUATION_TEST_CLASSPATH"
report="${CARL_EVALUATION_REPORT:-$HOME/.codex/work/carl-live-evaluation-evidence-$(date +%Y%m%dT%H%M%S)}"
case "$report" in "$HOME"/*) ;; *)
  echo 'Evidence must remain in the durable HOME workspace' >&2
  exit 1
  ;;
esac
mkdir -p "$report/fixture-classes" "$report/tmp"
node --test "$root/scripts/tests/test_live_evaluation.cjs" >"$report/assertion-tests.log" 2>&1
classpath="$distribution/app.jar:$distribution/lib/*:$(cat "$CARL_EVALUATION_TEST_CLASSPATH")"
javac -proc:none -cp "$classpath" -d "$report/fixture-classes" "$root"/scripts/e2e/*.java
if [[ "${2:-}" == --ordinary-talk ]]; then
  if [[ "$mode" != controlled ]]; then
    echo 'Ordinary preservation requires controlled mode' >&2
    exit 1
  fi
  env -u ANTHROPIC_API_KEY CARL_PREVIEW_LIVE_MODEL=false JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$report/tmp" node "$root/scripts/e2e/talk-browser.cjs" "$distribution" "$report/ordinary-talk" "$report/fixture-classes:$classpath" >"$report/ordinary-talk.log" 2>&1
elif [[ $# -gt 1 ]]; then
  usage >&2
  exit 1
fi
node "$root/scripts/e2e/live-evaluation.cjs" "$mode" "$distribution" "$report" "$report/fixture-classes:$classpath"
printf 'Evaluation evidence: %s\n' "$report"
