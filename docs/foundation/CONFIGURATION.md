# Configuration

Native applications load one Java properties file, followed by optional `--key=value`
overrides. The generator writes [`config/agent.properties`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/scripts/templates/agent.properties).
File values are overridden by matching environment variables, then command-line arguments.
Never put secrets in arguments or committed files. Complete `${ENV_NAME}` values resolve an
explicit environment alias; embedded/default-value expressions are not supported.

A property maps to an uppercase environment name with punctuation replaced by underscores:
`kof22.agent.db.password` becomes `KOF22_AGENT_DB_PASSWORD`. Maps and indexed lists must
first declare each exact key in the properties file or command line. Use contiguous numeric
indices such as `kof22.agent.domain-servers.0.name`; preserve principal punctuation in Java
properties with escaping, for example `kof22.agent.rbac.users.mcp\:local=OPERATOR`.
Unknown properties, malformed values and environment-name collisions fail configuration.

| Environment variable | Purpose |
| --- | --- |
| `KOF22_AGENT_ANTHROPIC_API_KEY` | Default provider credential; legacy `ANTHROPIC_API_KEY` is a fallback |
| `KOF22_AGENT_ANTHROPIC_BASE_URL` | Optional provider URL; legacy `ANTHROPIC_BASE_URL` is a fallback |
| `KOF22_AGENT_DB_URL`, `KOF22_AGENT_DB_USERNAME`, `KOF22_AGENT_DB_PASSWORD` | Runtime PostgreSQL connection |
| `KOF22_AGENT_QQQ_DB_USERNAME`, `KOF22_AGENT_QQQ_DB_PASSWORD` | Restricted operations reader |
| `KOF22_AGENT_QQQ_USERNAME`, `KOF22_AGENT_QQQ_PASSWORD` | Loopback-only local browser authentication |
| `KOF22_AGENT_SLACK_ENABLED`, `KOF22_AGENT_SLACK_BOT_TOKEN`, `KOF22_AGENT_SLACK_APP_TOKEN` | Optional Slack connector |
| `KOF22_AGENT_MCP_ENABLED`, `KOF22_AGENT_MCP_CALLERS_LOCAL`, `KOF22_AGENT_MCP_APPROVAL_CHANNEL` | Authenticated MCP using declared caller `local` |
| `KOF22_AGENT_DEPLOYMENT_MODE` | DEVELOPMENT, TEST or PRODUCTION |
| `KOF22_AGENT_POLICY_PROFILE`, `KOF22_AGENT_POLICY_OWNER` | COMPANY or PERSONAL governance and owner label |
| `KOF22_AGENT_SCHEDULER_TIME_ZONE` | Explicit schedule zone; default UTC |

An empty string does not satisfy a required setting. Java does not automatically source a
shell environment file. The launcher resolves its default properties file from its own
distribution; persona and job paths resolve relative to that properties file.

## Core settings

All keys in this table are under `kof22.agent`.

| Property | Default | Purpose and validation |
| --- | --- | --- |
| `name` | None; generator uses the artifact ID | Required nonblank display name; also derives the MCP tool prefix |
| `persona-path` | None; generator uses `../prompts/PERSONA.md` | Required readable, lint-clean voice/persona file; resolved from the properties file directory |
| `anthropic-api-key` | Provider environment | Optional explicit provider credential |
| `anthropic-base-url` | Provider environment/default | Optional endpoint override |
| `model.id` | `claude-sonnet-5` | Source default; select a model available to your provider account before a live call |
| `model.max-tokens` | `16000` | Maximum output tokens per provider request |
| `slack.enabled` | `true` | Missing bot/app tokens fail startup; disabling is supported for local development |
| `slack.bot-token`, `slack.app-token` | None | Required when the default Slack connector is active |
| `rbac.users` | Empty map | Principal to `VIEWER`, `OPERATOR`, `APPROVER`, or `ADMIN`; unassigned principals are `VIEWER` |
| `approval.ttl` | `4h` | Pending approval lifetime |
| `approval.max-concurrent-executions` | `2` | Independent limit for approved-write execution |
| `memory.enabled` | `true` | Register shared read/write memory tools |
| `memory.max-note-chars` | `1000` | Stored-note length bound |
| `memory.recall-limit` | `20` | Maximum recalled notes |
| `jobs` | Empty list | Named jobs with `name`, Quartz `cron`, filesystem `prompt-path`, and Slack `channel` |
| `scheduler.time-zone` | `UTC` | Schedule time zone |
| `reject-domain-connections` | `false` | Controlled packaged-test guard; true rejects nonempty `domain-servers` before connecting |

When Slack is disabled, audited read tools remain available. Write tools and approval
delivery are excluded. Scheduled jobs need their configured Slack delivery path. The
starter disables Slack and inbound MCP until their credentials are supplied.

## Runtime limits

These properties are under `kof22.agent.limits`.

| Property | Default |
| --- | ---: |
| `max-input-chars` | 16000 |
| `max-history-turns` | 40 |
| `max-history-chars` | 64000 |
| `max-context-chars` | 128000 |
| `max-turn-context-chars` | 512000 |
| `max-tool-result-chars` | 16000 |
| `max-output-tokens` | 32000 |
| `max-tool-calls` | 32 |
| `max-concurrent-turns` | 8 |
| `turn-timeout` | `2m` |

The limits are validated by [RuntimeLimits](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/src/main/java/com/kof22/agentcore/runtime/RuntimeLimits.java). Character limits are not token or currency estimates. The output-token allowance shrinks over provider requests, and the current provider loop also has a ten-iteration bound. See [Runtime contract](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/RUNTIME-CONTRACT.md).

## Governance and privacy

Properties under `kof22.agent.policy` are strict: unknown fields fail binding.

| Property | Default | Meaning |
| --- | --- | --- |
| `profile` | `COMPANY` | Choose a core-owned policy profile |
| `organization` | `KofTwentyTwo` | Company identity label; unused for personal governance |
| `owner` | Company: `configured administrators`; personal: required | Plain identity label, not a permission grant |
| `escalation-contact` | Company: `configured approval channel`; personal: owner | Plain contact label |
| `redaction.fields` | Empty additional set | Add domain-sensitive JSON field names; fixed secret filters remain |
| `retention.max-age` | Unset; cleanup disabled | Explicit duration from 1 second to 36500 days for manual completed-history cleanup |

Personal configuration example:

```properties
kof22.agent.policy.profile=PERSONAL
kof22.agent.policy.owner=Family administrators
kof22.agent.policy.escalation-contact=Family administrators
kof22.agent.rbac.users.mcp\:local=VIEWER
kof22.agent.rbac.users.verified-operator-subject=ADMIN
```

Replace the example subject with the verified identity used by your operator provider. The profile and labels do not grant RBAC access. Retention configuration does not start a timer or delete transcripts/memory; see [Operations](OPERATIONS.md).

## MCP server and outbound clients

Inbound properties are under `kof22.agent.mcp`.

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Expose the agent as an MCP server |
| `endpoint` | `/mcp` | Streamable HTTP servlet path on the native QQQ listener |
| `callers` | Empty map | Caller name to nonempty, unique bearer token |
| `approval-channel` | None | Required when enabled |
| `stdio` | `false` | Also expose the server on stdin/stdout |
| `stdio-caller` | None | Required configured caller name when stdio is enabled |

Outbound entries in `kof22.agent.domain-servers` each require `name` and exactly one of `url` or `command`. Other fields are `endpoint` (default `/mcp`), `args` (empty), `request-timeout` (`20s`), and optional HTTP `bearer-token`. Put a server's base URL in `url` and path in `endpoint`. Authenticated HTTP uses HTTPS, with loopback HTTP allowed for local testing.

```properties
kof22.agent.domain-servers.0.name=business
kof22.agent.domain-servers.0.url=${BUSINESS_MCP_URL}
kof22.agent.domain-servers.0.endpoint=/mcp
kof22.agent.domain-servers.0.bearer-token=${BUSINESS_MCP_TOKEN}
kof22.agent.domain-servers.0.request-timeout=20s
```

`BUSINESS_MCP_URL` and `BUSINESS_MCP_TOKEN` are consumer-defined aliases in this example, not built-in foundation variables. Read [API](API.md) for exported tool names, schemas, and authorization behavior.

## Native QQQ administration

| Property under `kof22.agent.qqq` | Default | Meaning |
| --- | --- | --- |
| `host` | `127.0.0.1` | Local mode requires this exact bind address |
| `port` | `8090` | One shared QQQ/health/MCP listener |
| `auth-mode` | `local` | `local` or `oidc-bearer` |
| `username`, `password` | None | Required Basic credentials in local mode |
| `db-username`, `db-password` | None | Required safe-column SELECT-only operations reader |
| `public-origin` | None | Required HTTPS origin without path for bearer mode |
| `oidc.issuer` | None | Required HTTPS issuer in bearer mode |
| `oidc.audience`, `oidc.client-id` | None | Required audience/client in bearer mode |

Bearer mode verifies RSA256 tokens, issuer, audience, expiry, and subject, then binds the subject to core RBAC and native QQQ permissions. It requires JVM option `-Dqqq.logger.logSessionId.disabled=true` and SQL logging disabled. Local mode is an administration read-only path. [Personal server](PERSONAL-SERVER.md) describes named operator access and TLS; [QQQ application contract](QQQ-APPLICATION-CONTRACT.md) describes domain permissions.

## Database and environment overrides

The native host owns the persistent QQQ/Quartz scheduler and one HTTP listener.
`kof22.agent.qqq.port` controls that listener. Runtime startup validates core/domain schema
history and database grants; it never runs migrations or creates schemas. Use the separate
bootstrap with privileged maintenance credentials, then start with runtime/reader identities.

A complete external properties file can replace the bundled file:

```sh
sh target/agent/bin/agent /absolute/path/agent.properties
```

Use absolute persona/job paths when the external file is outside the distribution.
Set `kof22.agent.deployment.mode=PRODUCTION` for the personal server: startup requires
enabled Slack/authenticated MCP, operator-mode QQQ behind HTTPS, distinct restricted runtime
and reader identities, and safe provider/domain destinations. The listener binds literal
loopback behind the proxy. See [Personal server](PERSONAL-SERVER.md),
[Database roles](DATABASE-ROLES.md) and [Migrations](MIGRATIONS.md).

## Build and verification variables

| Variable | Used by | Purpose |
| --- | --- | --- |
| `FOUNDATION_VERSION` | Generator | Select `X.Y.Z`, `X.Y.Z-rc.N` or mutable `X.Y.Z-SNAPSHOT`; otherwise use the bundled template version |
| `GITHUB_ACTOR`, `GITHUB_TOKEN` | The shipped Maven settings example | GitHub username and private package-access credential; needed when resolving published artifacts |
| `MAVEN_REPO` | Foundation verification and generator | Select one shared/isolated local Maven repository |
| `MAVEN_SETTINGS` | Foundation verification and generator | Maven settings file, including private package authentication |
| `KOF22_LIVE_SMOKE` | Core live-smoke test | Only literal `true` enables the separately authorized real-provider test |

These are tooling controls, not runtime agent properties. See [Testing](TESTING.md) and [Artifacts](ARTIFACTS.md).
