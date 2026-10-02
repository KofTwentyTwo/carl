#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
report="$root/target/migration-evidence"
mkdir -p "$report/fixture-classes"
printf '{"status":"RUNNING"}\n' > "$report/report.json"
trap 'printf '\''{"status":"FAIL"}\n'\'' > "$report/report.json"' ERR
maven=(mvn -B -ntp -f "$root/pom.xml")
if [[ -n "${MAVEN_REPO:-}" ]]; then maven+=("-Dmaven.repo.local=$MAVEN_REPO"); fi
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then maven+=(--settings "$MAVEN_SETTINGS"); fi
if [[ "${MAVEN_OFFLINE:-false}" == true ]]; then maven+=(-o); fi
"${maven[@]}" -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test \
  "-Dmdep.outputFile=$report/test-classpath.txt" >"$report/compile.log" 2>&1
classpath="$root/target/classes:$(cat "$report/test-classpath.txt")"
javac -proc:none -cp "$classpath" -d "$report/fixture-classes" "$root/scripts/e2e/CarlLiquibaseQualification.java"
python3 - "$report" "$classpath" <<'PY'
import pathlib,subprocess,sys
report,classpath=pathlib.Path(sys.argv[1]),sys.argv[2]
with (report/'postgresql-migration.log').open('w') as output:
    try:
        result=subprocess.run(['java','-Xmx768m','-Dqqq.logger.logSessionId.disabled=true',
                    '-cp',str(report/'fixture-classes')+':'+classpath,
                    'com.kof22.carlai.CarlLiquibaseQualification',str(report)],
                   timeout=360,stdout=output,stderr=subprocess.STDOUT)
    except subprocess.TimeoutExpired:
        (report/'report.json').write_text('{"status":"FAIL","reason":"TIMEOUT"}\n')
        raise SystemExit('Carl migration qualification timed out; inspect '+str(report/'postgresql-migration.log'))
if result.returncode:
    (report/'report.json').write_text('{"status":"FAIL"}\n')
    raise SystemExit('Carl migration qualification failed; inspect '+str(report/'postgresql-migration.log'))
print('Carl disposable PostgreSQL Liquibase transition PASS; evidence='+str(report/'report.json'))
PY
