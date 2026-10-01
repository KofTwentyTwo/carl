#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
report="$root/target/public-household-evidence"
mkdir -p "$report/fixture-classes"
maven=(mvn -o -B -ntp -f "$root/pom.xml")
if [[ -n "${MAVEN_REPO:-}" ]]; then maven+=("-Dmaven.repo.local=$MAVEN_REPO"); fi
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then maven+=(--settings "$MAVEN_SETTINGS"); fi
python3 -m unittest discover -s "$root/scripts/tests" -p test_public_household.py >"$report/generator-tests.log" 2>&1
python3 - "$root" <<'PY'
import importlib.util
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location('fictional', root / 'scripts/generate-public-household.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
dataset = module.build()
module.validate(dataset)
for name, contents in module.serialize(dataset).items():
    assert (root / 'fixtures/public-household' / name).read_bytes() == contents.encode('utf-8'), name
print('Checked public fixture bytes exactly match deterministic generator')
PY
"${maven[@]}" -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test "-Dmdep.outputFile=$report/test-classpath.txt" >"$report/compile.log" 2>&1
classpath="$root/target/classes:$(cat "$report/test-classpath.txt")"
javac -proc:none -cp "$classpath" -d "$report/fixture-classes" "$root/scripts/e2e/CarlPublicHouseholdSeed.java" "$root/scripts/e2e/CarlPublicHouseholdFixtureTest.java"
python3 - "$report" "$classpath" "$root/fixtures/public-household" <<'PY'
import pathlib
import subprocess
import sys
report, classpath, fixtures = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
with (report / 'postgresql-service-test.log').open('w') as output:
    # A timeout terminates only this owned JVM; its Testcontainers cleanup handles its own disposable DB.
    subprocess.run(['java', '-Xmx768m', '-cp', str(report / 'fixture-classes') + ':' + classpath,
                    'com.kof22.carlai.domain.CarlPublicHouseholdFixtureTest', fixtures],
                   check=True, timeout=900, stdout=output, stderr=subprocess.STDOUT, cwd=str(pathlib.Path(fixtures).parent.parent))
print('Public synthetic PostgreSQL/service fixture acceptance PASS; evidence=' + str(report))
PY
