# Carl build and release

Copyright (C) 2026 KofTwentyTwo

Carl releases package the standalone application. They do not deploy it or qualify live family accounts, provider integration, tax conclusions, or production operation. A local successful build is distinct from a hosted qualified release.

## Required setup and current limitation

Carl needs Java 21, Maven 3.9+, Node 22, Python 3, GnuPG, and Docker for the hosted application/browser/security gates. The workflows pin third-party actions and scanner images. Local application builds retain the documented CLI and IntelliJ setup.

The `Carl` workflow resolves foundation Maven packages with the workflow's own `GITHUB_TOKEN` (`packages: read`), exactly as the foundation repository does. No bespoke packages secret or reviewer-gated environment is used; the Carl repository only needs read access to the foundation packages in their package settings. Earlier `FOUNDATION_PACKAGES_TOKEN`/`foundation-packages` guidance below is historical and superseded on October 6, 2026.

RC/stable preparation requires a qualified immutable foundation version matching the implementation. `config/release/foundation.json` currently retains the signed remote0.4.1 baseline and hashes of all nine remotely published Maven files. The current0.5.0-SNAPSHOT development parent is not that immutable baseline; release promotion must remain blocked until a matching immutable version is published and qualified. A source POM, local install or alternate file repository is not remote publication evidence. Changing the pin requires a reviewed replacement baseline from verified foundation publication evidence.

Create the `foundation-packages`, `snapshots`, and `releases` GitHub environments with appropriate branch restrictions/review requirements; protect `main` and `develop` and require application verification. RC/stable dispatch is restricted by code to `main`. Repository rules and hosted environment setup must be checked in the target repository; their existence is not implied by these files.

Current status: release policy, synthetic signature/evidence tests and workflow static validation are locally verified. Environment branch policies and immutable tag controls are installed and independently reread, as recorded below. Carl source push, required-check branch protection, hosted package resolution and publication remain **BLOCKED pending working push/package credentials and hosted execution**. No Carl release is claimed here.

## Channels and exact candidates

| Trigger | Candidate | Delivery |
| --- | --- | --- |
| Feature push or pull request | Current consumer snapshot | Verification evidence only |
| Push to `develop` or `main` | `VERSION-SNAPSHOT` | Immutable prerelease named `snapshot-BRANCH-FULL_COMMIT` |
| Manual `release.yml` dispatch on `main`, signed `vX.Y.Z-rc.N` | Exact RC version | Prerelease |
| Manual dispatch on `main`, signed `vX.Y.Z` | Exact stable version | Stable release, after published qualified RC at the same source |

`VERSION` contains the next plain semantic version; the checked-in consumer POM retains that version plus `-SNAPSHOT`. Candidate preparation changes only the consumer project version in a disposable checkout and records original/candidate POM digests. The foundation version and dependency graph are unchanged. It may normalize XML formatting; this is recorded in the candidate evidence.

Each candidate independently runs Maven verification, real PostgreSQL/domain/authorization tests, packaged browser checks, source secret scanning, packaged dependency and container vulnerability scanning. Sealing rejects skipped/failing tests, coverage below 80% lines or 60% branches, non-PASS browser evidence, empty scanner results, detected secrets, and mismatched foundation artifact hashes. A stable candidate is rebuilt with its stable version; its source must exactly match the preceding RC.

The dated changelog classifies changes as Added, Changed, Fixed, Security, Breaking or Documentation. Added/Changed require a minor increment; fixes require at least patch. Breaking requires major once version 1 is reached and minor while version 0. Lower version components reset for major/minor increments. Advance `VERSION`, consumer snapshot version and changelog together after a stable release.

## Promotion procedure

Use a clean checkout of the verified current `main`. Do not modify released source during promotion. The OpenPGP public key in `config/release/trusted-signers.asc` is the established release identity; private signing material is never stored here. Key rotation requires reviewed policy changes.

```sh
git fetch origin main --tags
git tag -s v0.1.0-rc.1 origin/main -m 'Carl AI 0.1.0 release candidate 1'
git push origin v0.1.0-rc.1
gh workflow run release.yml --ref main -f tag=v0.1.0-rc.1
```

Wait for the complete successful run and independently inspect the published RC evidence. Preserve exact source while promoting:

```sh
git tag -s v0.1.0 origin/main -m 'Carl AI 0.1.0'
git push origin v0.1.0
gh workflow run release.yml --ref main -f tag=v0.1.0
```

Tag push alone does not publish. RC numbers are consecutive. A changed source needs a new RC; never retag, force-push a release tag, or overwrite delivery assets. Stable publication checks that current remote main still matches the candidate and that the latest RC at that source is published, signed and hash-valid.

## Delivery and independent verification

Each release contains the native distribution, OCI image archive, qualification evidence ZIP, delivery manifest and Sigstore bundle. The manifest records exact source/version/channel, candidate transformation, qualified foundation identities, tests/browser/security summaries and each artifact's size/SHA-256. GitHub Actions signs the manifest with OIDC using checksum-pinned Cosign; no long-lived signing secret is required. Publication first creates a draft, downloads it, verifies the signature and all artifact bytes, and only then publishes it.

Download all assets into a fresh directory. With the pinned verifier installed, use:

```sh
gh release download v0.1.0 --dir /tmp/carl-release-review
cosign verify-blob \
  --bundle /tmp/carl-release-review/delivery-manifest.sigstore.json \
  --certificate-identity https://github.com/KofTwentyTwo/carl/.github/workflows/release.yml@refs/heads/main \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  /tmp/carl-release-review/delivery-manifest.json
python3 scripts/release-evidence.py verify /tmp/carl-release-review \
  --source FULL_EXPECTED_COMMIT --version 0.1.0 --channel stable
```

For a develop snapshot, use `refs/heads/develop` in certificate identity. The standalone verifier checks the signed manifest's identity and file hashes; it does not independently reinterpret every XML/scanner result inside the ZIP. An independent release audit should additionally inspect the archived test, browser, coverage and security results, verify expected workflows actually ran, and record whether OCI execution/signature checks were hosted or local.

A failed upload/verification may leave a draft. The workflow refuses to overwrite any existing release, including a draft. Inspect and preserve the failed-run evidence; an operator may explicitly resolve/delete an unpublished failed draft after confirming no qualified release is replaced, or create a new immutable RC/version where appropriate. A rerun is not an authorization to mutate a published release. Rollback means selecting a previously qualified package; deployment and database rollback require their separate operator runbooks.

## Local release-tool checks

```sh
python3 -m unittest discover -s scripts/tests -p test_release.py
actionlint .github/workflows/ci.yml .github/workflows/release.yml
ruff check scripts/release-policy.py scripts/release-evidence.py scripts/tests/test_release.py
ruff format --check scripts/release-policy.py scripts/release-evidence.py scripts/tests/test_release.py
```

The signature regression creates an isolated synthetic GPG identity and local agent socket. A sandbox that denies local IPC must permit this narrow test operation; it does not use the operator's real signing keys. These tests use synthetic artifacts and do not replace the hosted application gates.

## Repository control setup

Protect `main` and `develop` with the **Required application verification** check from the GitHub Actions App (ID `15368`), strict up-to-date checks, pull requests, resolved conversations, and no force-push/deletion; apply controls to administrators. The final job runs with `always()` and fails unless the application job actually succeeded. Do not require only the conditionally skipped Application verification job: GitHub counts skipped checks as successful. A fork contribution requires a reviewed trusted-branch build before merging. See [GitHub status checks](https://docs.github.com/en/pull-requests/reference/status-checks).

Create `develop` at the intended reviewed source, checking current remote main immediately beforehand. Restrict the `snapshots` environment to `main`/`develop`, and `releases` to `main`. The credentialed `foundation-packages` environment needs reviewed feature branches and `refs/pull/*/merge` as well as `main`/`develop`; a feature-branch rule alone does not permit the synthetic PR merge ref. Use an owner review boundary before granting that environment's package credential to changed source. These policies are environment access controls, not production deployments. See [GitHub environment branch rules](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

Protect `v*` and `snapshot-*` tags against updates/deletion while allowing creation; signed-tag identity remains enforced by release policy. Re-read actual controls and verify a successful hosted trusted-PR check before claiming this setup complete. Remote inspection on September30,2026 found public default `main`, no `develop`, no protections/rulesets/environments, no registered workflows at the bootstrap source, and no required package-secret name. This is an inspection record, not proof of the subsequently applied configuration.


Current verified setup (September30,2026): the `foundation-packages`, `snapshots` and `releases` environments and their explicit branch policies are installed and reread; the active immutable `v*`/`snapshot-*` tag update/deletion rules are installed and verified. These controls are **PASS**. No credential value or package access was changed. Creating `develop`, branch protections with the published required-check identity, privately supplying authorized package access and successful hosted execution remain pending the reviewed source push. Environment/tag setup alone is not successful CI or a release.
