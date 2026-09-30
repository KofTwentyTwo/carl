# Shared artifacts, versions and consumer builds

The foundation is consumed through **`com.kof22:kof22-agent-parent`**. One exact version
pin supplies the core runtime, native QQQ administration, shared tests, Java/Maven build
rules and tested dependency management. Consumer repositories add their business model,
QQQ metadata, migrations, services, integrations, persona and configuration.

Artifacts publish to the Maven repository:

```text
https://maven.pkg.github.com/koftwentytwo/kof22-agent-foundation
```

The [GitHub Releases page](https://github.com/KofTwentyTwo/kof22-agent-foundation/releases)
and [package versions](https://github.com/KofTwentyTwo/kof22-agent-foundation/packages)
show what has actually been published. Example versions in this guide are not publication
claims. The current source line delivers the following matching coordinates; older releases
retain their historical artifact inventory.

| Coordinate | Main packaging | Attached artifacts / role |
| --- | --- | --- |
| `com.kof22:kof22-agent-core:X.Y.Z` | Jar and POM | `tests`, `sources`, `javadoc`; runtime and shared conformance/evaluation kit |
| `com.kof22:kof22-agent-ui:X.Y.Z` | Jar and POM | `sources`; pinned QQQ Next export, integration patch and source provenance |
| `com.kof22:kof22-agent-qqq:X.Y.Z` | Jar and POM | `tests`, `sources`, `javadoc`; administration, database provisioning and shared conformance tests |
| `com.kof22:kof22-agent-parent:X.Y.Z` | POM | Consumer build/dependency/test/packaging defaults; signed `evidence` ZIP |

The 0.3.0 line consumes official `com.kingsrook.qqq:qqq-middleware-javalin:4.0.0` and
packages a separate Kof22 integration build of [QQQ Next](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/frontend/README.md).
Earlier releases retain the owned compatibility artifact and its
[provenance](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/compat/qqq-middleware-javalin/UPSTREAM.md); those versions remain immutable.
Keep all foundation versions aligned; normally the parent handles this for you.

## Use a published release

Prerequisites: Java 21, Maven 3.9+, Python 3.12+, Bash, `ripgrep`, Docker and access to the published GitHub Packages
repository. Generating an application executes real application tests, including
Testcontainers. You do not have to rebuild the foundation to consume a published version.

Supply `GITHUB_ACTOR` (GitHub username) and `GITHUB_TOKEN` through your private process
or CI secret environment. Outside the foundation's own workflow, use a personal access token
(classic) with `read:packages` and access to this repository. A normal `gh auth login` OAuth
token with only `repo` does not provide package access. The foundation's own workflow uses
its repository-scoped `GITHUB_TOKEN` with explicit package permissions. See
[GitHub's Maven authentication requirements](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).
Never put its value in a POM, committed settings, image layer or command-line argument.
[`config/maven/settings.xml.example`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/config/maven/settings.xml.example) is usable as-is:
it reads these environment variables and registers repository/server id `github`.

Use the generator from the source tag matching the selected release. The native generator
requires the native contract introduced in `0.1.1`; it cannot target the historical `0.1.0`
application host. Stable `0.2.0` is published and independently verified in
[the delivery record](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/verification/2026-09-24-full-cicd-hosted.json):

```sh
# Select the published native release and its matching source checkout.
export GITHUB_ACTOR=your-github-login
# Set GITHUB_TOKEN through your private secret environment.
FOUNDATION_VERSION=0.2.0 \
MAVEN_SETTINGS="$PWD/config/maven/settings.xml.example" \
  bash scripts/new-agent.sh my-agent com.kof22.myagent ../my-agent
cd ../my-agent
mvn --settings config/maven/settings.xml.example verify
```

The generator writes the selected stable, RC or snapshot version into the consumer's parent, copies the
unexpanded Maven settings example and verifies the application before exposing its final
directory. `MAVEN_REPO=/path/to/cache` optionally chooses a cache. With no
`FOUNDATION_VERSION`, it uses the bundled template's version, normally the locally installed
development snapshot. A release-tag source checkout alone does not rewrite snapshot POMs;
use `FOUNDATION_VERSION` when consuming published artifacts.

The generated POM's essential part is:

```xml
<parent>
  <groupId>com.kof22</groupId>
  <artifactId>kof22-agent-parent</artifactId>
  <version>0.2.0</version>
  <relativePath/>
</parent>
```

The parent inherits core, QQQ and their shared test-kit classifiers, plus shared checks and
ordinary-JAR packaging. You do not need to redeclare them individually. See
[GETTING-STARTED.md](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/GETTING-STARTED.md) for the database, environment and launch commands.
Every consumer must satisfy [QQQ-APPLICATION-CONTRACT.md](QQQ-APPLICATION-CONTRACT.md).

Generated applications use their GitHub Actions repository token for package resolution.
For a separate consumer of this private Maven repository, provide `FOUNDATION_PACKAGES_TOKEN`
as a personal access token (classic) with `read:packages` and foundation-repository access.
Credentials never enter the runtime image.

## Version policy

Versions follow **Semantic Versioning**: `MAJOR.MINOR.PATCH`. The publication
channels are protected-main `X.Y.Z-SNAPSHOT`, signed `vX.Y.Z-rc.N` prereleases, and signed
`vX.Y.Z` stable tags supplied to the protected-main release action. Tag pushes, feature branches
and PRs do not publish. See [the enforced lifecycle](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/CI-CD.md).

- **Patch:** compatible fixes to the existing public API, behavior and dependency set.
- **Minor:** compatible additions. During the initial `0.x` series, a change requiring
  consumer source/configuration changes also advances the minor version and is called out
  in release notes.
- **Major:** incompatible public API, configuration or behavior changes after `1.0.0`.

Public contracts include shared Java interfaces, inherited build/dependency behavior,
configuration properties, database upgrade behavior and QQQ integration requirements.
Explain consumer action and migration impact in release notes. A migration must advance
through a new versioned file; do not edit an already released migration checksum.

Published RC/stable artifact versions are immutable. Development snapshots are mutable and Maven resolves timestamped files. Consumers pin exact parent versions; avoid
version ranges, `LATEST`, release snapshots or separately overriding individual foundation
modules. Upgrade by changing the parent version, reviewing release/migration notes, then
running the actual application's verification and packaged runtime checks. The application's
own version remains independent of the foundation version.

Canonical prereleases use lowercase `-rc.N`, starting at 1 without leading zeros.
The generator accepts `FOUNDATION_VERSION=0.2.0-rc.1` or `0.2.0-SNAPSHOT` once published;
use exact stable/RC pins for repeatable consumers. Required changelog classifications,
API comparisons, signed tags and promotion are described in [CI-CD.md](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/CI-CD.md).

## Work on the foundation from source

```sh
bash scripts/verify-foundation.sh
bash scripts/new-agent.sh my-agent com.kof22.myagent ../my-agent
```

The verifier installs core, the Next resource JAR, QQQ administration and the parent into the
chosen Maven cache, verifies a new consumer, boots its packaged jar/container and runs the
synthetic encrypted restore drill. Set `MAVEN_REPO` on both commands for an isolated cache;
optional `MAVEN_SETTINGS` supplies a private repository configuration. These commands do not
publish anything remotely. The generator uses bundled templates and refuses an existing
target; no separately installed archetype is required.

## Prepare a local candidate

```sh
python3 scripts/prepare-release.py 0.3.0-rc.1 /tmp/agent-candidate-001 \
  --build-cache /tmp/agent-build-m2
```

The destination must be new and outside the foundation source tree. The script snapshots
nonignored source, versions only that isolated copy, runs library gates and stages all four
artifact sets into a local file repository. A generated consumer resolves them with an empty
Maven cache, verifies and completes packaged jar/container/restore checks. `candidate.json`
records the source commit, source/artifact hashes, explicit version and successful fresh
consumer verification. It explicitly states that remote publication did not occur.

All three Maven alternate-deployment properties are pinned to the local repository.
Inherited Maven/JVM skip flags and a caller's `FOUNDATION_VERSION` are cleared; unit and
integration tests are explicitly enabled. The build cache cannot overlap the reserved empty
consumer cache. The source commit identifies the starting revision; source hashes also
capture local changes when you intentionally stage an uncommitted working tree.

The protected-main release action adds successful secret/dependency/image gates before upload.
[`publish-release.py`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/scripts/publish-release.py) then publishes these exact verified
files to this repository's GitHub Packages, preserving all classifiers. It requires the
authorized channel/version, source commit and artifact hashes to match. A separate empty-cache consumer
must resolve the actual remotely published bytes before `publication.json` reports success.
The script is CI delivery plumbing, not the command developers need to consume the library.

## Dependency management matters

An application's direct dependency entries can override an imported library BOM or narrow a
transitive runtime dependency to test scope. The shared parent therefore carries the tested dependency management needed by the
packaged application; importing QQQ alone is insufficient. Applications with a separately
mandated parent must reconcile dependency management deliberately and prove their resolved
jar/image with scans and application tests.

A successful local staging drill proves artifact completeness and clean-cache consumption.
Only an observed successful release action proves remote publishing and remote consumption.
Neither result qualifies a future personal-server environment or the consumer's financial
business behavior. Follow [PERSONAL-SERVER.md](PERSONAL-SERVER.md) for that separate setup.

The generated workflow uses the `foundation-packages` GitHub environment for package-read
credentials. Put any `FOUNDATION_PACKAGES_TOKEN` there, scoped only to the foundation's
required read access; restrict the environment to reviewed consumer branches and use
reviewers where available. PRs without package access cannot complete private dependency
verification. Do not provide repository-wide or publishing tokens to application tests.
