#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
[[ "${1:-}" == dev || "${1:-}" == staging || "${1:-}" == production ]] || {
  echo 'Usage: promote.sh <dev|staging|production> [image-set.json]' >&2
  exit 2
}
environment="$1"
manifest="${2:-target/publication/image-set.json}"
root="$(pwd -P)"
manifest="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$manifest")"
[[ -n "${GITHUB_TOKEN:-}" ]]
export GH_TOKEN="$GITHUB_TOKEN"
[[ "$(gh api repos/KofTwentyTwo/carl-CD --jq .private)" == true ]] || {
  echo 'BLOCKED: deployment repository must exist and remain private.' >&2
  exit 1
}
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/askpass" <<'SH'
#!/usr/bin/env bash
case "$1" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "$GITHUB_TOKEN" ;;
  *) exit 1 ;;
esac
SH
chmod 700 "$work/askpass"
export GIT_ASKPASS="$work/askpass"
export GIT_TERMINAL_PROMPT=0
git clone --quiet --branch main https://github.com/KofTwentyTwo/carl-CD.git "$work/cd"
bash scripts/ci/sign.sh verify "$manifest"
qualification="$work/cd/qualification/$environment.json"
[[ -f "$qualification" ]] || {
  echo 'BLOCKED: private real-data/identity/recovery qualification is missing.' >&2
  exit 1
}
options=(--qualification "$qualification" --environment "$environment")
if [[ "$environment" == production ]]; then
  rc="$work/cd/evidence/staging/image-set.json"
  bash scripts/ci/sign.sh verify "$rc"
  options+=(--rc "$rc")
fi
python3 scripts/ci/image-set.py verify "$manifest" "${options[@]}"
cd "$work/cd/environments/$environment"
while IFS= read -r image; do
  kustomize edit set image "$image"
done < <(python3 - "$manifest" <<'PY'
import json,sys
for image in json.load(open(sys.argv[1]))['images'].values():
    print(image['name']+'='+image['name']+'@'+image['digest'])
PY
)
cd "$root"
python3 scripts/ci/validate-gitops.py --root "$work/cd" --require-digests "$environment" \
  --qualification "$qualification" --output "$work/validation.json"
mkdir -p "$work/cd/evidence/$environment"
cp "$manifest" "$manifest.sigstore.json" "$work/cd/evidence/$environment/"
cd "$work/cd"
git add "environments/$environment/kustomization.yaml" "evidence/$environment/"
if git diff --cached --quiet; then exit; fi
# Signing uses a separately authorized automation signer; absence fails closed.
git -c user.name='Carl CI' -c user.email='carl-ci@kof22.com' -c commit.gpgsign=true \
  commit -m "ci: promote qualified Carl $environment image set"
git push origin HEAD:main
