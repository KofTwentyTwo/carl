#!/usr/bin/env bash
# Copyright (C) 2026 KofTwentyTwo
set -euo pipefail
tool_dir="${TMPDIR:-/tmp}/carl-maven-tools"
mkdir -p "$tool_dir"
curl --fail --silent --show-error --location --max-time 120 --retry 3 \
  https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz \
  --output "$tool_dir/maven.tar.gz"
python3 - "$tool_dir/maven.tar.gz" <<'PY'
import hashlib,sys
with open(sys.argv[1],'rb') as archive:
    actual=hashlib.file_digest(archive,'sha512').hexdigest()
expected='bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978'
if actual != expected: raise ValueError('Maven distribution SHA512 mismatch')
print('Maven distribution SHA512 verified')
PY
tar -xzf "$tool_dir/maven.tar.gz" -C "$tool_dir"
echo "export PATH=\"$tool_dir/apache-maven-3.9.11/bin:\$PATH\"" >> "${BASH_ENV:?}"
