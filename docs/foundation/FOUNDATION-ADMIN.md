# Shared QQQ administration

`com.kof22:kof22-agent-qqq` supplies native QQQ 4.0.0 hosting, metadata,
authentication, governed operations and lifecycle. The default QQQ Next UI is packaged
as `com.kof22:kof22-agent-ui`, built from pinned upstream `v0.2.0` source with the
foundation's session integration. The adapter consumes official QQQ Javalin middleware.
Consumers supply business metadata and services.

New agents inherit `com.kof22:kof22-agent-parent`; the parent includes this module and
the tested build/dependency defaults. Pin one foundation version for parent, core, QQQ
adapter and UI. A consumer that cannot use the parent must import this
artifact's POM in dependency management and add its jar as a dependency. The consumer parent also
includes native conformance test artifacts and a standard JAR distribution with adjacent libraries.

In the foundation checkout, use `bash scripts/verify-foundation.sh` to install and verify
all artifacts in dependency order; `scripts/new-agent.sh` generates the consumer configuration.
The generated app's README gives the packaged bootstrap and startup commands. This document
is also copied into generated apps as `docs/FOUNDATION-ADMIN.md`.

## Database and safe operations

Core Flyway migrations own the schema, including the private operator session table. The authoritative PostgreSQL location is `kof22.agent.db.url`; unsupported URL options fail startup. The normal core writer and the QQQ operations reader must use separate database roles.

Use the packaged `com.kof22.agentadmin.bootstrap.DatabaseBootstrap` CLI to provision a fresh production database with separate migration, runtime and QQQ reader roles. It also supplies migration, grant refresh, restore preparation and credential rotation operations; see [DATABASE-ROLES.md](DATABASE-ROLES.md). `initialize` retains the simpler local-development owner/reader setup. The CLI and startup guard use `AdminApplication.READER_COLUMNS` as the canonical column allowlist. Domain reader columns are explicitly declared through `qqq.reader.columns.<table>` in the private bootstrap configuration. Do not grant table-wide SELECT on sensitive operational tables.

| Native table | Visible data |
| --- | --- |
| `approvals` | Request identity, tool, decision/execution state, timestamps and audit reference |
| `auditLog` | Actor, tool, decision, approval reference and timestamps |
| `jobRuns` | Job name, state and timestamps |
| `tokenUsage` | Caller, model, token counts and timestamps |
| `sessionMetadata` | Session key, sequence, message role and timestamp |

The Operations widget counts pending approvals, uncertain executions and failed/running jobs. Counts describe stored state; inspect timestamps before treating a run as stalled. Raw arguments, result summaries, execution/job detail, transcript content and memory text are inaccessible to the reader. `memory_notes` and `qqq_operator_session` have no reader SELECT grants. Startup rejects missing required columns, extra readable columns and table/column mutation privileges, including inherited grants. Generic insert, edit, delete and export operations on core views are denied by native permissions as well as database privileges. The pinned native unrestricted file-download endpoint is disabled.

## Local mode

```properties
kof22.agent.qqq.auth-mode=local
kof22.agent.qqq.host=127.0.0.1
kof22.agent.qqq.port=8090
kof22.agent.qqq.username=local-admin
kof22.agent.qqq.db-username=agent_reader
```

Supply `KOF22_AGENT_QQQ_PASSWORD` and `KOF22_AGENT_QQQ_DB_PASSWORD` privately.
The database URL/writer identity use `KOF22_AGENT_DB_URL` and
`KOF22_AGENT_DB_USERNAME`, with `KOF22_AGENT_DB_PASSWORD` kept private.

Open `http://127.0.0.1:8090/`. Basic authentication protects HTML, JavaScript and API routes; credentials use constant-time comparison. This single-account mode binds only `127.0.0.1`. Its internal QQQ session is anonymous, so it is a local read-only boundary, unsuitable for per-person domain authorization. Processes and reports are blocked. Do not expose it through a public proxy.

## Verified operator mode

Start the JVM with `-Dqqq.logger.logSessionId.disabled=true` before QQQ classes initialize. QQQ otherwise logs its session UUID credential. Keep `qqq.rdbms.logSQL` false, its default; SQL parameter logging can expose tokens. Server mode rejects unsafe values for these settings. The shared server also disables QQQ's `JoinsContext` logger, whose pinned Log4j-to-SLF4J behavior would otherwise emit query filters containing session UUIDs. Database/proxy diagnostic logging must also exclude credentials and authorization headers.

```properties
kof22.agent.rbac.users.issuer-subject-for-operator=OPERATOR
kof22.agent.rbac.users.issuer-subject-for-approver=APPROVER
kof22.agent.qqq.auth-mode=oidc-bearer
kof22.agent.qqq.host=127.0.0.1
kof22.agent.qqq.public-origin=https://admin.example.test
kof22.agent.qqq.oidc.issuer=https://configured-issuer.example.test/
kof22.agent.qqq.oidc.audience=agent-administration
kof22.agent.qqq.oidc.client-id=configured-browser-client
kof22.agent.qqq.db-username=agent_reader
```

This uses QQQ's native Auth0 provider and QQQ Next browser flow. Configure an Auth0-compatible provider/client and the exact HTTPS callback/origin; arbitrary OIDC providers are not automatically compatible with the bundled browser SDK. The verifier accepts RS256 tokens only and checks the configured issuer, audience, expiry, not-before time and nonblank subject of at most 64 characters. Keys come from the configured issuer, never a token-selected URL. Native permissions come exclusively from core `RbacService` assignments for the verified subject. Token email/permission claims and arbitrary identity headers cannot assign roles.

The browser loads the public shell/authentication metadata, obtains a provider token, and posts it to native `/manageSession`. The gateway validates that token before native persistence. Subsequent `sessionUUID` requests recheck the stored token, signature/claims, current RBAC and database session existence. The cookie is Secure, HttpOnly and SameSite=Strict. Each browser reauthentication replaces its prior session. Cookie sessions expire at token expiry or 24 hours, whichever comes first; login prunes rows older than 24 hours. An idle deployment retains expired rows until the next login or an administrator schedules equivalent cleanup using the private writer.

The Next UI resumes through same-origin `GET /kof22/session`, which verifies the HttpOnly cookie and returns only the operator identity. Its logout action calls native `/qqq/v1/logout` before clearing local state; a failed revocation leaves the operator signed in and reports the failure. Confirmed logout revokes the database session and clears the cookie. Revocation takes effect despite QQQ's native token lookup cache. This ends the local administration session, not the provider's global SSO session or an independently held bearer token.

Only `APPROVER` or `ADMIN` can run **Deny Approval**. It requires explicit confirmation, derives the actor from the authenticated QQQ session and calls the core service. The decision and audit commit atomically; retries/concurrent calls add no duplicate decision audit. Approval execution remains with the existing approval coordinator. Reconciliation is deliberately unavailable: first stop every executor, verify quiescence and the external outcome, then use the core administrator reconciliation service with evidence. A UI check alone cannot make an in-flight side effect safe to reconcile.

Writes require the exact configured Origin. Native process GET navigation also requires that origin or a same-origin Referer; GET initialization, execution and cancellation are rejected. The bundled legacy GET cancellation action remains unavailable. API clients may send verified bearer tokens and must send Origin for process/write requests. Competing cookies, API-key/basic fallbacks, and authentication query parameters are rejected at protected routes.

Every matched process route checks native permissions. Process records, continuation, status and process-backed possible values require the verified creator's subject and original process definition; status also requires that process's issued async job ID. A different administrator cannot adopt another operator's process UUID. Ownership is private to the running server, bounded to 10,000 process instances and 24 hours after the last init/step response. Expiry, eviction or restart denies continuation; native process state is not an application recovery record. Governed services retain their own durable approval/audit evidence. Route checks normalize encoded and repeated slashes, and trailing-slash aliases are not accepted by the router.

In development/test mode, `host` may be changed explicitly for a private container network
with verified operator authentication. `kof22.agent.deployment.mode=PRODUCTION` requires literal
loopback for the QQQ listener behind its HTTPS proxy. A separate proxy container therefore
needs a deployment topology that can reach that loopback listener; simply publishing port
8090 from a bridge-network container does not satisfy this requirement. TLS termination,
network isolation and real provider/browser redirect verification remain deployment checks.
Local controlled-provider HTTP tests establish the server protocol; they do not certify a
live SSO deployment or production infrastructure.

## Consumer metadata

Return standard `MetaDataProducerInterface<?>` instances from `Components.metadata()` for tables, apps, backends, joins, processes and possible-value sources. Native producer ordering, enrichment and validation run before startup. Duplicate metadata and producer exceptions fail startup. Shared names, including `operations`, the five core tables, `userSession`, `agentOperations` and `agentAuthentication`, are reserved.

The `agentOperations` backend is the restricted read connection to `kof22.agent.db.url`.
Register table producers before the app/navigation producer that refers to them; the latter
can override `getSortOrder()` with `600`, as the metadata extension test does. See
[the QQQ application contract](QQQ-APPLICATION-CONTRACT.md) for data, permission and
consumer-test requirements, and [migrations](MIGRATIONS.md) for the domain SQL path.

Use `OperatorPermissions.require(Role.OPERATOR)` or a stricter role on domain metadata. Add native record-security locks for household/tenant scope; the verified subject is the `userId` security key. Governed domain processes should recheck permissions and invoke the same authoritative service as domain tools. Domain writes need a separate narrowly scoped backend. Native extensions are trusted application code, not a sandbox; do not modify private authentication metadata or route around service permissions. Verify the actual consumer's records, grants and isolation using the shared application conformance suite.

## Verification

From the foundation checkout, after core and the Next resource JAR are installed, run:

```sh
mvn -f qqq-admin/pom.xml verify
```

Use `bash scripts/verify-foundation.sh` for the complete ordered build. These are foundation
commands; in a generated app run `mvn verify` instead. Test JVMs include the session-log
suppression flag. Adapter unit line and branch gates remain 100%, without exclusions. The
Docker PostgreSQL suites do not silently skip and exercise migrations, safe views, native
login/session revocation, identity/role/record restrictions, concurrent denial, browser-process
HTTP initialization/confirmation/denial, and generic mutation/export denial with synthetic data.
These are automated HTTP/data checks, not a claim of visual browser or live SSO qualification.
QQQ has JVM-global registries; run one administration instance per application JVM. The shared
server lifecycle clears native connection-provider caches on close so subsequent native application instances
can use another database.
