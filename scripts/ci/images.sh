#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$root"
[[ "${1:-}" == build || "${1:-}" == publish ]] || {
  echo 'Usage: images.sh <build|publish>' >&2
  exit 2
}
scanner='aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
application="carl-qualified:${CIRCLE_SHA1:?}"
migrator="carl-qualified-migrator:$CIRCLE_SHA1"
if [[ "$1" == build ]]; then
  docker build --label "org.opencontainers.image.revision=$CIRCLE_SHA1" --tag "$application" .
  docker build --build-arg "APPLICATION_IMAGE=$application" --label "org.opencontainers.image.revision=$CIRCLE_SHA1" \
    --tag "$migrator" --file docker/Dockerfile.migrator .
  mkdir -p target/security
  docker run --rm -v "$root/target/agent:/scan:ro" "$scanner" rootfs /scan --scanners vuln \
    --severity HIGH,CRITICAL --no-progress --exit-code 1 --list-all-pkgs --format json > target/security/dependencies.json
  for role in application migrator; do
    reference="$application"
    report=image
    [[ "$role" != migrator ]] || { reference="$migrator"; report=migrator; }
    docker image save --output "target/$role-image.tar" "$reference"
    docker run --rm -v "$root/target:/scan:ro" "$scanner" image --input "/scan/$role-image.tar" --scanners vuln \
      --severity HIGH,CRITICAL --no-progress --exit-code 1 --list-all-pkgs --format json > "target/security/$report.json"
    docker run --rm -v "$root/target:/scan:ro" "$scanner" image --input "/scan/$role-image.tar" \
      --format cyclonedx > "target/security/$role-sbom.json"
  done
  python3 scripts/verify-security.py --trivy target/security/dependencies.json --trivy target/security/image.json --trivy target/security/migrator.json
  python3 scripts/ci/foundation.py
  # Validate all evidence before publication, using synthetic digests only for this local boundary check.
  python3 - <<'PY'
import json, runpy
from pathlib import Path
module=runpy.run_path('scripts/ci/image-set.py')
images={role:{'name':'ghcr.io/koftwentytwo/carl'+('-migrator' if role=='migrator' else ''),'digest':'sha256:'+'0'*64} for role in ('application','migrator')}
module['seal'](Path.cwd(),images,json.loads(Path('target/circleci-plan.json').read_text()),'prepublication-validation')
PY
  exit
fi
[[ -n "${GHCR_TOKEN:-}" && -n "${GHCR_USERNAME:-}" && -n "${CIRCLE_WORKFLOW_ID:-}" ]]
# Qualify the actual signing service before publishing either image.
bash scripts/ci/sign.sh preflight target/circleci-plan.json
docker image load --input target/application-image.tar
docker image load --input target/migrator-image.tar
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username "$GHCR_USERNAME" --password-stdin
trap 'docker logout ghcr.io >/dev/null 2>&1' EXIT
tag="$CIRCLE_SHA1-$CIRCLE_WORKFLOW_ID"
mkdir -p target/publication
for role in application migrator; do
  reference="$application"
  name=ghcr.io/koftwentytwo/carl
  [[ "$role" != migrator ]] || { reference="$migrator"; name=ghcr.io/koftwentytwo/carl-migrator; }
  # Never overwrite a previously published build identity.
  if docker manifest inspect "$name:$tag" >/dev/null 2>&1; then
    echo 'Immutable build image already exists; inspect it instead of overwriting.' >&2
    exit 1
  fi
  docker tag "$reference" "$name:$tag"
  docker push "$name:$tag"
  docker image inspect "$name:$tag" --format '{{json .RepoDigests}}' > "target/publication/$role-digests.json"
done
python3 - <<'PY'
import json
from pathlib import Path
images={}
for role in ('application','migrator'):
    name='ghcr.io/koftwentytwo/carl'+('-migrator' if role=='migrator' else '')
    digests=json.loads(Path('target/publication/'+role+'-digests.json').read_text())
    matching=[value.split('@',1)[1] for value in digests if value.startswith(name+'@')]
    if len(set(matching)) != 1: raise ValueError('Exactly one matching pushed registry digest required')
    images[role]={'name':name,'digest':matching[0]}
Path('target/publication/images.json').write_text(json.dumps(images,indent=2)+'\n')
PY
python3 scripts/ci/image-set.py seal --images target/publication/images.json --plan target/circleci-plan.json \
  --workflow "$CIRCLE_WORKFLOW_ID" --output target/publication/image-set.json
bash scripts/ci/sign.sh sign target/publication/image-set.json
