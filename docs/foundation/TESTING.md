# Testing and quality gates

For a generated consumer, follow [Build a Standalone Agent](BUILD-STANDALONE-AGENT.md)
and run `mvn verify` in that application. Commands referencing foundation scripts/modules
below run in the foundation checkout. The consumer parent supplies conformance dependencies,
Java/Maven/convergence checks, Spotless/Checkstyle executions and packaging. The generator
copies their configuration. ErrorProne and JaCoCo are not inherited through dependency
management; configure those business-code analysis and coverage requirements explicitly.


## Policy

Core and the foundation QQQ adapter enforce **100% unit line and branch coverage** with
JaCoCo. Their `mvn verify` gates use unit execution data and have no coverage exclusions.
The coverage gate does not depend on an integration-test run; full application verification
still requires Docker. When code is difficult to cover, expose a testable boundary rather
than lowering the gate (see `PromptStack.read`).

| Module | Coverage contract |
| --- | --- |
| `kof22-agent-core` | 100% unit lines and branches |
| `kof22-agent-qqq` | 100% unit lines and branches |
| Pinned Next UI | Frontend type/lint, 925 upstream and integration unit tests, audit and static export; packaged browser acceptance |
| Generated consumer | Inherited conformance/application tests plus consumer-owned business tests; no inherited 100% coverage plugin |

The historical owned middleware's SpotBugs/PMD baseline remains with its source but is not
part of current official QQQ middleware builds. See [security policy](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/SECURITY.md).

## Framework and setup

Tests use JUnit Jupiter 5.12.2, AssertJ and Mockito through explicit native dependency management, plus
Testcontainers 1.21.4 for PostgreSQL 16, ArchUnit 1.4.1 for SDK boundaries, and MockWebServer
for provider HTTP stubs. The QQQ adapter pins JUnit 5.12.2. Use JDK 21 and Maven 3.9+;
keep Docker running for the complete suite. The full packaged verification also needs Python 3.12+
and `age`. See [Development](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/docs/DEVELOPMENT.md) for the build order and [Configuration](CONFIGURATION.md)
for isolated Maven cache/settings controls.

## The pyramid

| Layer | Runner | Trigger | What it proves |
|---|---|---|---|
| **Unit** | Surefire (`*Test`) | every `mvn verify`; native adapter tests require Docker | Contracts and branches of every class: prompt-stack ordering, persona lint rules, session serialization/rehydration, the Anthropic adapter against a mocked SDK, record validation, explicit startup admission, native QQQ store round trips and failure atomicity, ArchUnit SDK isolation |
| **Integration** | Failsafe (`*IntegrationTest`) | `mvn verify` with Docker; inspect any legacy suite skips explicitly | Flyway schema + transcript persistence against **real Postgres 16** (Testcontainers), including the conformance restart drill |
| **E2E** | Failsafe | same | The whole native agent — explicit composition, governance stack, session manager, real SDK HTTP client, Postgres — with only the Anthropic SaaS stubbed at the HTTP boundary (MockWebServer). Asserts governance travels on the wire, replies flow back, and a "restarted" process resumes the conversation |
| **Live smoke** | Failsafe, gated | `KOF22_LIVE_SMOKE=true` + real `ANTHROPIC_API_KEY` | One real model call through the full governed stack. Deliberately excluded from default CI verification (no provider credentials needed) |

Everything through E2E is fully automated and secret-free.

The `qqq-admin/` module verifies inherited administration separately: real metadata and UI
wiring, a restricted PostgreSQL reader, authorization and mutation-denial checks, and native
domain metadata extensions. Run `bash scripts/verify-foundation.sh` to verify/install core, the pinned Next UI,
the QQQ adapter and the shared application parent. The `verify` CI job runs that path on a Docker-capable machine.
It also runs `scripts/new-agent.sh` to generate and verify a fresh consumer. That consumer
inherits `AbstractConformanceSuite` from core's `tests` artifact and
`AbstractAgentApplicationSuite` from the QQQ adapter's `tests` artifact. The latter calls
the consumer's actual component factory and loads its properties file, replacing provider
and chat boundaries while retaining real PostgreSQL, QQQ HTTP and MCP persistence.
QQQ administration is always part of the native host. Docker absence fails acceptance.
This CI path uses local installations; remote publication is verified separately.
The foundation script and candidate build run pinned Next type, lint, unit, audit and export checks.
The separate full-browser gate installs locked Playwright/Chromium test dependencies and runs
against packaged resources. Node.js is a build dependency, not an application runtime dependency.
See [Session state](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/docs/SESSION-STATE.md) for current qualification and [the historical QQQ RC report](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/docs/verification/qqq4-qualification.md) for the original native migration evidence.

Each application test context owns its PostgreSQL container and QQQ listener. Run contexts
serially because native QQQ has JVM-global registries. `ApplicationMcpFixtures` must replace
every configured domain server by name; a mismatch fails before live connections are made.
Use the shared `ControlledMcpServer` with the real domain tool catalog and a synthetic vendor
client. `ApplicationDatabaseFixture` provisions domain rows and narrow reader grants.
Core reader columns come from the administration dependency's `AdministrationSchema` SPI;
consumers do not duplicate the core grant list. Add direct business-data and permission tests.
The native suite applies synthetic configuration overrides before any deferred resource
factory runs. Its controlled model can inspect and invoke the actual gated local/outbound
tool catalog; `assertNativeApplication` runs with live stores and host. Fixtures own their
literal-loopback MCP servers. Use the executable [domain example](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/examples/native-domain/README.md)
for a shipped component factory, owner-aware tool and redactor checks.

The verification script also boots the packaged jar and its non-root, read-only container,
checks authenticated QQQ metadata and drills encrypted backup/restore into an empty target.
Evidence is written under `target/packaged-evidence`. A local staged release can be verified
from an empty consumer cache with `scripts/prepare-release.py`; see [ARTIFACTS.md](ARTIFACTS.md).
Neither controlled fixtures nor code coverage establish live model/business correctness.

## Foundation commands & lifecycle wiring

```bash
bash scripts/verify-foundation.sh # complete modules + generated app + packaged checks
mvn verify                       # core units/gates + IT/E2E; Docker required
mvn verify -DskipITs=true         # core units/gates only; partial evidence
mvn -Dtest=RuntimeContractsTest test # one core unit class
mvn clean install                # core + attached test/source/Javadoc artifacts into ~/.m2
open target/site/jacoco/index.html # core unit coverage report
open qqq-admin/target/site/jacoco/index.html # QQQ adapter unit report
```

Core and adapter gates are phase-bound. Normal lifecycle commands reaching `test` or
`package` run their earlier format/style gates; `verify` also runs coverage and integration
verification. Invoking a standalone plugin goal is not equivalent to the complete lifecycle:

| Phase | Bound goals |
|---|---|
| `validate` | Copyright check (root); Spotless check; **Checkstyle using the repository-local rules**; enforcer (JDK 21+, dependency convergence) |
| `initialize` | JaCoCo agent |
| `compile` | javac + ErrorProne (`errorprone` profile — CLI/CI always; inactive only in IDEA's importer) |
| `test` | Surefire (`*Test`), JaCoCo + Mockito agents attached |
| `package` | main jar + `-sources` + `-javadoc` jars |
| `integration-test`/`verify` | Failsafe (`*IntegrationTest`); JaCoCo report + **100% line/branch check** |
| `install` / `deploy` | Local installation / configured Maven repository; coordinated publication uses the release tooling in [Artifacts](ARTIFACTS.md) |

Housekeeping goals: `mvn spotless:apply` (fix formatting), `mvn checkstyle:check` (style
only), `mvn jacoco:report` (report from existing test data).

Notes:
- In the core POM, `-DskipTests` skips units but **still runs the IT/E2E suite**;
  Failsafe's skip is bound to `skipITs`, not `skipTests`. Do not assume the same skip
  wiring for another module's POM.
- The core unit suite runs with a placeholder `ANTHROPIC_API_KEY` injected by Surefire so
  environment-resolution code paths execute offline. Failsafe does not override your env.
- The core live smoke is opt-in: `KOF22_LIVE_SMOKE=true mvn verify` uses a real
  `ANTHROPIC_API_KEY` and makes a real provider call. Run it only when explicitly authorized;
  it is not part of the default deterministic CI run or automatically inherited by a consumer.
- The complete verification/generation/release scripts explicitly enable tests; use direct
  Maven commands for an intentionally partial run. Partial runs are not release evidence.

## When the coverage gate fails

1. `open target/site/jacoco/index.html` — red lines/diamonds are the misses.
2. Write the missing test. Validation branches need both sides of every `||` exercised.
3. If a branch is genuinely unreachable (e.g. defensive `default` on an exhaustive enum
   switch), the fix is to delete or restructure the code, not to lower the gate.

## CI

The [GitHub Actions workflow](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/.github/workflows/ci.yml) runs the same local gates,
source secrets scanning, and actual JAR/image scans. Main snapshots and signed RC/stable tags stage and verify
all four artifacts before publication. `bash scripts/verify-browser.sh` exercises the ordinary
packaged domain app, scoped UI/process, record deep-link refresh, denied generic mutation,
failed/successful logout, cookie replay across JVM restart
and fresh login against disposable PostgreSQL and local HTTPS OIDC. The browser fixture never
uses a personal profile or live provider, and only sanitized screenshots/results are uploaded. See [CI/CD](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/docs/CI-CD.md).

## Writing tests and consumer responsibilities

Core/adapter unit classes end in `Test`; integration classes end in `IntegrationTest` and
are selected by Failsafe. Use existing focused tests beside the changed package as examples.
Stub the provider or vendor at the boundary while testing the actual runtime/service flow.
Do not replace a database-permission test with a mock of the permission decision.

Every agent should retain the inherited contract tests and add domain tests:
- Unit-test your `service/` layer as plain Java; supply a controlled model/provider through
  the native component factory and shared conformance fixtures.
- Extend `AbstractAgentApplicationSuite` for actual application/QQQ startup; the generator
  pre-wires it. Add domain data and access assertions in the consumer.
- The shared `AbstractConformanceSuite` retains the shared contract checks (audit rows, approval
  gating, RBAC, restart drill, scheduled runs and MCP identity).

## Behavioral evaluation fixtures

The core test jar includes `conformance.eval.EvaluationFixture` and `EvaluationRunner`.
Supply synthetic/business fixtures and an adapter observing actual structured facts,
tool selection/execution and approval evidence. The runner checks exact facts, forbidden
or unapproved writes, declared prompt/model/tool versions and optional latency/token limits.
Safe reports omit prompts, replies, arguments and fact values. See the concrete format and
adapter example in [EvaluationRunnerTest](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/d46cf4e1902db52229abccbaa6707346e94e1c23/src/test/java/com/kof22/agentcore/conformance/eval/EvaluationRunnerTest.java).
Controlled fixtures prove the harness, not model correctness; live consumer evaluations
remain explicitly authorized and use synthetic data.

Packaged verification supports the generated scaffold. It strips inherited integration
credentials and sets `kof22.agent.reject-domain-connections=true`, which refuses configured
outbound MCP servers before transport construction. Applications with business startup
clients need their own controlled packaged fixture; do not point the scaffold drill at
arbitrary production configuration. The actual-application test instead requires one
controlled `ApplicationMcpFixtures` replacement for every configured domain server.
