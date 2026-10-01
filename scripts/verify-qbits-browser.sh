#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
: "${CARL_QUALIFIED_DISTRIBUTION:?Set the parent-reviewed matching packaged application distribution}"
distribution="$(cd -- "$CARL_QUALIFIED_DISTRIBUTION" && pwd -P)"
test -f "$distribution/app.jar"
report="$root/target/qbits-browser-evidence"
mkdir -p "$report/fixture-classes"
maven=(mvn -o -B -ntp -f "$root/pom.xml")
if [[ -n "${MAVEN_REPO:-}" ]]; then maven+=("-Dmaven.repo.local=$MAVEN_REPO"); fi
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then maven+=(--settings "$MAVEN_SETTINGS"); fi
"${maven[@]}" dependency:build-classpath -Dmdep.includeScope=test "-Dmdep.outputFile=$report/test-classpath.txt" >"$report/classpath.log" 2>&1
classpath="$distribution/app.jar:$distribution/lib/*:$(cat "$report/test-classpath.txt")"
javac -proc:none -cp "$classpath" -d "$report/fixture-classes" "$root"/scripts/e2e/*.java
node "$root/scripts/e2e/qbits-browser.cjs" "$distribution" "$report" "$report/fixture-classes:$classpath"
