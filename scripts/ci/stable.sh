#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
[[ "${CIRCLE_TAG:-}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ -n "${GITHUB_TOKEN:-}" && -n "${GHCR_TOKEN:-}" && -n "${GHCR_USERNAME:-}" ]]
export GH_TOKEN="$GITHUB_TOKEN"
[[ "$(gh api repos/KofTwentyTwo/carl-CD --jq .private)" == true ]]
python3 scripts/ci/plan.py
work="$(mktemp -d)"
trap 'rm -rf "$work"; docker logout ghcr.io >/dev/null 2>&1' EXIT
cat > "$work/askpass" <<'SH'
#!/usr/bin/env bash
case "$1" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "$GITHUB_TOKEN" ;;
  *) exit 1 ;;
esac
SH
chmod 700 "$work/askpass"
GIT_ASKPASS="$work/askpass" GIT_TERMINAL_PROMPT=0 git clone --quiet --branch main \
  https://github.com/KofTwentyTwo/carl-CD.git "$work/cd"
rc="$work/cd/evidence/staging/image-set.json"
bash scripts/ci/sign.sh verify "$rc"
python3 scripts/ci/image-set.py verify "$rc" --qualification "$work/cd/qualification/staging.json" --environment staging
mkdir -p target/publication
python3 - "$rc" <<'PY'
import json,os,runpy,sys
from pathlib import Path
policy=runpy.run_path('scripts/ci/image-set.py')
rc=policy['validate'](json.load(open(sys.argv[1])))
plan=json.loads(Path('target/circleci-plan.json').read_text())
if rc['source']!=plan['source'] or 'v'+rc['version']!=plan['rcTag']:
    raise ValueError('Stable must use the exact most recent qualified signed RC source/image set')
stable=rc | {'channel':'stable','version':plan['version'],'workflow':os.environ['CIRCLE_WORKFLOW_ID']}
policy['validate'](stable)
Path('target/publication/image-set.json').write_text(json.dumps(stable,indent=2,sort_keys=True)+'\n')
PY
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username "$GHCR_USERNAME" --password-stdin
bash scripts/ci/sign.sh sign target/publication/image-set.json
bash scripts/ci/promote.sh production
