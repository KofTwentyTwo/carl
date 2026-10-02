#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$root"
[[ -n "${GITHUB_TOKEN:-}" && -n "${GITHUB_ACTOR:-}" ]] || {
  echo 'BLOCKED: github context must grant private foundation Maven read access.' >&2
  exit 1
}
[[ -n "${CIRCLE_SHA1:-}" && "$(git rev-parse HEAD)" == "$CIRCLE_SHA1" ]]
export MAVEN_SETTINGS="$root/config/maven/settings.xml.example"
export MAVEN_REPO="${TMPDIR:-/tmp}/carl-fresh-maven-${CIRCLE_WORKFLOW_ID:?}"
[[ ! -e "$MAVEN_REPO" ]] || { echo 'Fresh Maven resolution requires an unused repository.' >&2; exit 1; }
export CARL_QUALIFIED_DISTRIBUTION="$root/target/agent"
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
node --test scripts/tests/test_live_evaluation.cjs
mkdir -p target/security
docker run --rm -v "$root:/src" zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  dir /src --redact --report-format json --report-path /src/target/security/gitleaks.json
python3 scripts/ci/plan.py
mvn -B -ntp --settings "$MAVEN_SETTINGS" "-Dmaven.repo.local=$MAVEN_REPO" clean verify
# Maven clean removes target; re-record the successful scan and source policy afterward.
mkdir -p target/security
docker run --rm -v "$root:/src" zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  dir /src --redact --report-format json --report-path /src/target/security/gitleaks.json
python3 scripts/ci/plan.py --record-only
bash scripts/verify-public-household.sh
npm ci --prefix scripts/e2e
npm exec --prefix scripts/e2e -- playwright install --with-deps chromium
bash scripts/verify-browser.sh
bash scripts/verify-dashboard-browser.sh
bash scripts/verify-talk-browser.sh
CARL_DOCKED_QBITS=false CARL_DOCKED_REQUIRE_ESB=false bash scripts/verify-docked-chat-browser.sh
bash scripts/verify-qbits-browser.sh
[[ -x scripts/verify-liquibase.sh ]] || {
  echo 'BLOCKED: dedicated fresh/retained Liquibase qualification has not been integrated.' >&2
  exit 1
}
bash scripts/verify-liquibase.sh
python3 scripts/ci/validate-gitops.py --output target/gitops-validation.json
