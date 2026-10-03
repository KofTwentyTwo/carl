#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
[[ "${1:-}" == sign || "${1:-}" == verify || "${1:-}" == preflight ]] || {
  echo 'Usage: sign.sh <sign|verify|preflight> <image-set-or-public-plan.json>' >&2
  exit 2
}
manifest="${2:?Image-set path required}"
tool_dir="${TMPDIR:-/tmp}/carl-cosign"
mkdir -p "$tool_dir"
if [[ ! -x "$tool_dir/cosign" ]]; then
  curl --fail --location --max-time 120 --retry 3 \
    https://github.com/sigstore/cosign/releases/download/v3.1.3/cosign-linux-amd64 --output "$tool_dir/cosign"
  printf '%s  %s\n' 4629c757b7618056f8ddd7e2625ae9fdd94c0372a65049520bc7d9df9efc7f71 "$tool_dir/cosign" | sha256sum --check --strict
  chmod 755 "$tool_dir/cosign"
fi
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
issuer='https://oidc.circleci.com'
identity='https://circleci.com/api/v2/projects/ab70c257-7c86-45e2-b988-86003f49734d/pipeline-definitions/70b80fd1-b928-5fbf-817d-75ab416ccc87'
if [[ "$1" != verify ]]; then
  command -v circleci >/dev/null || { echo 'BLOCKED: CircleCI environment CLI is required for Sigstore audience/root issuer.' >&2; exit 1; }
  SIGSTORE_ID_TOKEN="$(circleci run oidc get --claims '{"aud":"sigstore"}' --root-issuer)"
  export SIGSTORE_ID_TOKEN
  python3 "$root/scripts/ci/oidc.py"
fi
if [[ "$1" == preflight ]]; then
  bundle="$(mktemp)"
  trap 'rm -f "$bundle"' EXIT
  "$tool_dir/cosign" sign-blob --yes --use-signing-config=false --oidc-issuer "$issuer" --bundle "$bundle" "$manifest"
  "$tool_dir/cosign" verify-blob --bundle "$bundle" --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$manifest"
  exit
fi
if [[ "$1" == sign ]]; then
  "$tool_dir/cosign" sign-blob --yes --use-signing-config=false --oidc-issuer "$issuer" \
    --bundle "$manifest.sigstore.json" "$manifest"
  while IFS= read -r reference; do
    "$tool_dir/cosign" sign --yes --use-signing-config=false --oidc-issuer "$issuer" "$reference"
    "$tool_dir/cosign" attest --yes --use-signing-config=false --oidc-issuer "$issuer" \
      --predicate "$manifest" --type https://kof22.com/attestations/carl-image-set/v1 "$reference"
    "$tool_dir/cosign" verify --certificate-identity "$identity" \
      --certificate-oidc-issuer "$issuer" "$reference" >/dev/null
    "$tool_dir/cosign" verify-attestation --type https://kof22.com/attestations/carl-image-set/v1 \
      --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$reference" >/dev/null
  done < <(python3 - "$manifest" <<'PY'
import json,sys
for image in json.load(open(sys.argv[1]))['images'].values():
    print(image['name']+'@'+image['digest'])
PY
)
fi
"$tool_dir/cosign" verify-blob --bundle "$manifest.sigstore.json" \
  --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$manifest"
