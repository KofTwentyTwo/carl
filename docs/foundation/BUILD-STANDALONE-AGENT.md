# Build a Standalone Agent

This is the canonical workflow for a person or LLM coding tool building a separate agent
with the KofTwentyTwo foundation. Complete the requested business scope, not just a scaffold.
The foundation owns runtime, governance, approvals, sessions, scheduling, MCP, QQQ hosting
and shared build machinery. The consumer owns domain data, deterministic services,
integrations, persona, configuration and domain tests. Never copy or patch foundation runtime
code into a consumer. Report a foundation gap and implement it upstream within authorized scope.

In a foundation checkout, begin at step 1 and generate a **new directory**. In an already
generated consumer, read its `AGENTS.md`, README and parent version, then resume at the first
incomplete step. Do not regenerate over an existing application. Supporting guides copied
into the consumer are reference snapshots: keep this application's brief and acceptance
record separate, and refresh references when upgrading its foundation version.

## 1. Define the agent and its acceptance cases

Read the user's request and existing project context. Resolve missing business decisions
with the user; do not invent vendor contracts, owner mappings or expected financial results.
Record the following in the consumer's `docs/AGENT-BRIEF.md` (draft outside the destination
until generation finishes):

| Input | Required detail |
| --- | --- |
| Purpose and scope | Intended users, every requested workflow, expected inputs/outputs, explicit exclusions |
| Domain data | Authoritative source, entities/relationships, ownership/tenant boundary, units, currency and time semantics |
| Actions | Read operations, proposed writes, human approvers, duplicate/uncertain-outcome behavior |
| Integrations | Provider, vendor APIs, inbound/outbound MCP, Slack, schedules and required credentials by name only |
| QQQ administration | Tables/navigation, record details, filters and domain processes users actually need |
| Runtime target | Local or production, identity provider, database/host owner, delivery and recovery expectations |
| Acceptance | Concrete synthetic examples, expected facts, permission denials and failure/recovery cases per workflow |

Keep a short implementation plan in `docs/TODO.md` and current state in `docs/SESSION-STATE.md`.
Create `docs/ACCEPTANCE.md` from the checklist below; attach commands/results and sanitized
screenshots or reports as work completes. Record the foundation version and source revision.
Mark a requirement not applicable only with a reason supported by the brief. Missing secrets
or deployment access are explicit blockers, not successful acceptance. Repository creation,
commits, pushes, publication, live calls and deployment require the applicable authorization;
a build guide is not that authorization.

## 2. Select a foundation and generate the consumer

Use JDK 21, Maven 3.9+, Python 3.12+, `ripgrep` and a running Docker daemon. The full foundation
build also needs Node.js 24/npm, `age`/`age-keygen` and network access for pinned dependencies.
The consumer needs Java/Maven at build time and Java/PostgreSQL at runtime; it does not run
its own Node frontend server. Use a trusted foundation checkout matching the selected
artifact version. Read [Artifacts](ARTIFACTS.md) before choosing a published version; a
snapshot POM or open PR does not establish remote publication.

**Local foundation development:** run these commands from the foundation checkout. They
install the current source into the local Maven cache, then verify and create the consumer:

```sh
bash scripts/verify-foundation.sh
bash scripts/new-agent.sh my-agent com.kof22.myagent ../my-agent
cd ../my-agent
```

Change the artifact ID/package/destination to the brief. The destination must not exist.
If using `MAVEN_REPO`, set the same absolute cache path on both scripts and pass
`-Dmaven.repo.local=/absolute/cache` to later Maven commands.

**Published foundation:** use the matching release source checkout and set
`FOUNDATION_VERSION` to an actually published stable, RC or snapshot version. Export
`GITHUB_ACTOR` and the package-read credential privately; never put a token value in a command.
From that foundation checkout:

```sh
: "${FOUNDATION_VERSION:?Set the exact published foundation version}"
FOUNDATION_VERSION="$FOUNDATION_VERSION" \
MAVEN_SETTINGS="$PWD/config/maven/settings.xml.example" \
  bash scripts/new-agent.sh my-agent com.kof22.myagent ../my-agent
cd ../my-agent
mvn --settings config/maven/settings.xml.example verify
```

Prefer an immutable stable/RC for release qualification. The consumer's own version is
independent of its `com.kof22:kof22-agent-parent` version. Generation uses temporary staging,
runs the actual application's inherited tests, and only then creates the destination. It
creates a standalone application directory, not a remote Git repository or deployed service.
Retain its `AGENTS.md`, README, tests and CI; add the brief, plan and acceptance record.

**Upgrading an existing consumer:** this parent now runs Spotless and Checkstyle. Copy
`config/checkstyle/checkstyle.xml`, `config/formatter/qqq-eclipse.xml`, `.editorconfig` and
`.mvn/jvm.config` from the matching foundation source into the existing application.
Merge application-specific editor/JVM settings rather than discarding them. Run
`mvn spotless:apply`, review the formatting diff and run `mvn verify`. Do not regenerate
over the consumer. Newly generated applications already include these build files.

## 3. Configure identity and prove the scaffold locally

Edit `config/agent.properties` and `prompts/PERSONA.md`. Persona is voice only; security rules,
tool instructions and credentials belong elsewhere. Use [Configuration](CONFIGURATION.md)
for exact keys and precedence. For PERSONAL governance use `KOF22_AGENT_POLICY_PROFILE=PERSONAL`
and an explicit `KOF22_AGENT_POLICY_OWNER`; these labels grant no access. Map actual trusted
principals into RBAC and, separately, into the domain's ownership model.

Follow the generated README and [Database roles](DATABASE-ROLES.md) to create a dedicated
PostgreSQL 16 database and a private bootstrap properties file outside the repository, mode
0600. Use separate migration, runtime and QQQ-reader roles. From the built consumer:

```sh
java -cp target/agent/app.jar com.kof22.agentadmin.bootstrap.DatabaseBootstrap \
  provision /absolute/path/database.properties
```

`provision` is for fresh roles and an empty database. Use the documented `migrate` operation
for upgrades; runtime startup validates but never migrates. Supply private runtime settings
through a shell environment file, including `KOF22_AGENT_DB_PASSWORD`,
`KOF22_AGENT_QQQ_DB_PASSWORD`, `KOF22_AGENT_QQQ_PASSWORD` and the provider credential
`KOF22_AGENT_ANTHROPIC_API_KEY`. The default provider requires that key for ordinary packaged
startup even when chat is disabled; controlled tests require no live provider key.

```sh
set -a
. /absolute/path/agent.env
set +a
sh target/agent/bin/agent
```

Open `http://127.0.0.1:8090/` with the configured local administrator, and check
`/health/live` and `/health/ready`. Keep QQQ, health and enabled HTTP MCP on the inherited
Java listener. Local Basic mode proves shared read-only Operations access; it does not
establish a named user for domain permissions. Use verified operator authentication for
domain UI acceptance as described in [Personal server](PERSONAL-SERVER.md). Do not weaken
a domain permission just to make the local anonymous session pass.

## 4. Implement the complete domain through native extension points

Work in small end-to-end slices, then repeat for **every workflow in the brief**. Use the
[API](API.md), [QQQ contract](QQQ-APPLICATION-CONTRACT.md) and executable
[native domain example](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/examples/native-domain/README.md) as the reference. The example
shows a read workflow and owner isolation; it is not a completed write integration.

1. **Persist authoritative data.** Add versioned SQL under `src/main/resources/db/migration/`,
   starting with `V1__create_domain_tables.sql` for a new domain. Use typed columns, keys,
   relationships, constraints and indexes. Use exact decimals and explicit units where
   required. Domain history is `agent_domain_schema_history`; leave core migrations/history
   alone. See [Migrations](MIGRATIONS.md).
2. **Implement deterministic services.** Put rules/calculations in `domain/service/`, bounded
   vendor HTTP clients in `domain/client/`, and thin tools/jobs in their own packages.
   Validate inputs and trusted identity at the service boundary. Use explicit transactions
   for related writes. Decide how duplicate requests and unknown external outcomes reconcile;
   an LLM must not infer that a timed-out write did nothing.
3. **Register business tools.** Extend the generated `AgentApplication.components()` factory.
   Override `tools(NativeStores)` for in-process tools. Use `ToolBinding.forCaller` when
   identity matters; the caller is supplied by the runtime, never by model JSON. Name tools
   `{domain}_read_*` or `{domain}_write_*`; write schemas require `reason` and results describe
   before/after state. State units, bounds and failure behavior in descriptions. Let the shared
   gate audit reads and coordinate write approval. Do not call raw executors from user routes.
4. **Expose real QQQ administration.** Return native metadata producers from `metadata()`.
   Add meaningful navigation, typed fields, labels, record details, filters/relationships and
   required processes. UI/processes and tools call the same domain services over the same data.
   Enforce role, tenant/owner and field access server-side; test direct API calls too. Grant
   the reader only named domain columns through `qqq.reader.columns.<table>` in bootstrap
   configuration and apply `migrate`/`grant-access`. Metadata alone grants no SQL privileges.
   Generic CRUD must not mutate core approvals/audit/history. Domain mutations use explicit
   authorized services/processes and a narrowly scoped domain writer, never a widened reader.
5. **Add privacy and integrations.** Supply domain redaction through `redactors()` where
   required. Keep credentials in private configuration. Prefer in-process tools for this
   agent's own services; a separate domain MCP server is needed only for an actual external
   transport boundary. Configure it with `kof22.agent.domain-servers.<index>.*` and provide
   controlled fixtures by the same name. These are plain Java components, not Spring beans.

Keep the production main and tests on the same component factory. The factory defers external
resources until configuration is validated; do not open vendor connections while declaring
components. If composition needs business settings, implement `validate(configuration)` and
retain the inherited validation. Inspect the [example factory](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/examples/native-domain/src/main/java/com/kof22/example/ExampleApplication.java)
and its tests rather than guessing method signatures.

## 5. Connect only the entry points required by the brief

The generated application disables Slack and inbound MCP initially. Configure Slack bot/app
credentials and enable it for chat or write approvals. Without Slack, read tools remain
available but write capabilities and approval delivery are excluded. Scheduled output needs
its Slack channel too; do not claim a write/scheduled workflow works with that path disabled.

For inbound MCP, declare caller names in the properties file before setting their private
`KOF22_AGENT_MCP_CALLERS_<NAME>` values, enable MCP and set its approval channel. Use an SDK
client that initializes the protocol and retains its session at `/mcp`; this is not a REST
chat endpoint. Prefer `{agent}_read_ask`, `{agent}_write_ask` and `{agent}_read_status`.
A write ask can propose an action; it does not mean that action was approved or executed.
See [API](API.md) for response/error semantics and caller-owned conversations.

For schedules, add prompts under `prompts/jobs/`, declare contiguous `kof22.agent.jobs.0.*`
entries and an explicit scheduler time zone. Compute facts in deterministic services/tools;
use the model to narrate them. Check missed/unknown work after restart. Operate one active
agent per dedicated database. See [Configuration](CONFIGURATION.md) and [Operations](OPERATIONS.md).

## 6. Prove the actual consumer, including failure cases

Retain `ConformanceTest` and `AgentApplicationTest`. The inherited synthetic checks prove
foundation contracts, not the agent's business correctness. Extend the actual application test:

| Hook / test | Required evidence |
| --- | --- |
| Plain service/client tests | Expected facts, rounding/time boundaries, validation, bounded vendor failures and duplicate handling |
| `databaseFixture()` | Real migrated domain rows and explicit reader grants through `ApplicationDatabaseFixture` |
| `mcpFixtures()` | One `ApplicationMcpFixtures` replacement per configured outbound server, actual catalog with controlled vendor clients |
| `conformanceRuntime()` | Controlled provider invoking actual gated tools; facts and prohibited actions asserted |
| `assertNativeApplication(host, stores, http)` | Domain data and permitted/denied behavior against real stores/host; explicit verified-operator tests for owner isolation |
| Packaged browser acceptance | Actual consumer login, navigation, records/deep links, processes, denied mutation, logout and restart/session behavior |

Run `mvn verify` in the consumer, with `--settings config/maven/settings.xml.example` when
using private published artifacts. The consumer parent enforces Java/Maven versions, dependency
convergence and banned dependencies, and supplies tests/packaging plus Spotless/Checkstyle.
Retain the generated `.mvn/` and `config/` style files. ErrorProne and 100% JaCoCo coverage
are not inherited; configure the consumer's business-code analysis/coverage requirements and retain the
foundation's own gates when changing shared code. Inspect reports, not just an exit code. [Testing](TESTING.md) distinguishes
foundation-only commands from consumer responsibilities. Use controlled PostgreSQL/vendor/model
fixtures for automation; replace every configured external boundary before invoking it.

Build and run the entire `target/agent/` distribution from another directory and verify health,
configuration/persona resolution and domain behavior. Build its Dockerfile, exercise its actual
network topology and runtime restrictions, and scan the consumer JAR/image through its CI.
Adapt the foundation's [browser fixture](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/scripts/e2e/browser.cjs) to this domain with
synthetic data; its example selectors do not certify a different consumer. Add automated
business UI tests to the consumer workflow and record a visual inspection separately.
Live model/Slack/vendor evaluation and host restore checks require their own authorized
synthetic/sandbox run; neither shared coverage nor an inherited smoke test substitutes for them.

## 7. Prepare application delivery and operational handoff

The generated CI runs Maven application tests and secrets/JAR/image scans. For private Maven
packages, provision `FOUNDATION_PACKAGES_TOKEN` with package-read/foundation access in the
consumer's `foundation-packages` environment, restricted to reviewed branches. Do not expose
credentials to untrusted PR code. Verify CI on the actual consumer commit and retain reports.
The template does not publish artifacts, create GitHub rules, configure SSO or deploy a host.

Document the consumer's own versioned release, immutable artifact/image identity, required
checks/branch protection, migration sequence, secret configuration and deployment procedure.
Implement the delivery automation required by the brief within authorized scope. Pin the
foundation parent; rebuilding the library locally is not remote dependency-resolution proof.
Use [Artifacts](ARTIFACTS.md) for foundation consumption, not as a claim that the consumer
has been released.

Follow [Personal server](PERSONAL-SERVER.md) and [Operations](OPERATIONS.md) for production
identity/TLS, separate database roles, startup/shutdown, encrypted backups and restore,
upgrade/rollback and reconciliation. Qualify the actual host, integration credentials and
recovery target before claiming production readiness. Hand off commands, owner/alerts,
artifact identities, evidence and remaining blockers. Building a consumer and deploying it
are distinct acceptance milestones.

## Completion checklist

Copy this checklist into the consumer's `docs/ACCEPTANCE.md`. For every checked item attach
the command/test name, result, source commit/artifact version and sanitized evidence path.
Use `PASS`, `FAIL`, `BLOCKED`, or `NOT APPLICABLE — reason` for each requirement in the brief.
Do not mark the whole agent complete while a requested workflow remains blocked.

### Built and verified

- [ ] Brief covers every requested workflow, data source, identity boundary and expected result.
- [ ] Standalone consumer pins the intended available foundation; a clean build can resolve it.
- [ ] Only business code/configuration/tests live here; foundation runtime is inherited.
- [ ] Domain migrations, constraints, reader grants and upgrade behavior pass against PostgreSQL.
- [ ] Every workflow has deterministic service/tool tests with expected facts and failure cases.
- [ ] Trusted caller mapping, roles, tenant/owner/field isolation and direct-API denials are tested.
- [ ] QQQ UI exposes actual authoritative domain data and every required process; browser evidence exists.
- [ ] UI and tools produce consistent results from the same services/data with equivalent permissions.
- [ ] Writes require the intended approval; unauthorized/unapproved/duplicate/unknown outcomes are tested.
- [ ] Requested Slack/MCP/schedules are configured and tested with controlled boundaries.
- [ ] Consumer quality/coverage requirements are configured; full `mvn verify` and required CI checks pass with no skips concealing requested behavior.
- [ ] Packaged startup, relocation, container behavior and consumer JAR/image scans pass.
- [ ] Persona/configuration are valid; secrets and sensitive test data are absent from source/reports.

### Live delivery, when requested

- [ ] Authorized synthetic live model/chat/vendor cases verify the intended workflows and approval path.
- [ ] Consumer release is published through its defined pipeline; artifact identity and fresh consumption are verified.
- [ ] Production host has verified operator identity/TLS, least-privilege roles and private secret configuration.
- [ ] Backup/restore and the chosen upgrade/rollback pair pass on the intended environment.
- [ ] Monitoring/reconciliation ownership, launch/stop commands and recovery procedures are handed off.
- [ ] Acceptance record names remaining limits; `docs/SESSION-STATE.md` and `docs/TODO.md` match reality.

Report **built and verified** only when the first section and all requested build requirements
pass. Report **deployed and qualified** only when the requested live-delivery section also
passes. For a build-only request, record live delivery as not requested; for a deployment
request awaiting access, record it as blocked. A passing scaffold alone earns neither claim.
