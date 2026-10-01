# API and extension points

This is a Java library with MCP and native QQQ interfaces. The shared runtime does not define a REST `/chat` or `/approvals` controller. Applications normally inherit [`kof22-agent-parent`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/agent-parent/pom.xml), supply explicit native business components, and use the existing interfaces below. Dependency coordinates and versions are in [Artifacts](ARTIFACTS.md).

## Authentication and caller identity

| Interface | Authentication | Trusted identity |
| --- | --- | --- |
| Slack | Configured bot/app credentials and Slack events | Slack user ID |
| MCP over HTTP | `Authorization: Bearer <configured-token>` | `mcp:<caller-name>` from `kof22.agent.mcp.callers` |
| MCP over stdio | Fixed configured `stdio-caller` | The same machine principal |
| QQQ local administration | HTTP Basic on `127.0.0.1` only | Local read-only administration |
| QQQ server administration | Verified OIDC bearer or native browser session | Verified JWT subject, mapped into shared RBAC and native permissions |
| Direct Java invocation | Application's authenticated boundary | Explicit caller ID passed by trusted code |

Roles are `VIEWER`, `OPERATOR`, `APPROVER`, and `ADMIN`. Unassigned callers resolve to `VIEWER`; invalid configured role names fail startup. An unknown MCP token remains invalid. Machine tokens and human subject IDs are distinct. Business record/field permissions must be implemented in the business services and QQQ metadata; a core role alone does not define household or account access.

## Java conversation API

Inject [`SessionManager`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/src/main/java/com/kof22/agentcore/session/SessionManager.java). Use the overload carrying identity and approval routing:

```java
String reply = sessions.askReadOnly(
      new SessionKey("statement-review-2026-09"),
      "Summarize the records available to me.",
      authenticatedCallerId,
      approvalChannelId,
      threadId);
```

The corresponding `ask(SessionKey, String, String, String, String)` permits governed write proposals. Both return reply text and persist successful exchanges. The two-argument `ask(SessionKey, String)` uses the internal caller `system`; it is not an authentication adapter for user traffic. Never derive an authoritative caller from model-supplied arguments.

The caller's raw session key is scoped by the authenticated principal. `askReadOnly` excludes write bindings regardless of role. A returned reply is conversational completion, not evidence that a write was approved or executed.

## Register a business tool

[`ToolRegistry.register(ToolBinding)`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/src/main/java/com/kof22/agentcore/security/ToolRegistry.java) accepts a provider-neutral definition plus a JSON-string executor. Names must match `{domain}_read_*` or `{domain}_write_*`, and duplicate names are rejected. Use a description that states units, scope, and the result shape; validate arguments in the handler before calling a deterministic business service.

Return business bindings from the same component factory used by the application main:

```java
public static NativeAgentApplication.Components components()
{
   return new NativeAgentApplication.Components()
   {
      @Override
      public List<ToolBinding> tools(NativeStores stores)
      {
         return List.of(new ToolBinding(
               new ToolDefinition("example_read_ping", "Returns synthetic availability.",
                     "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
               arguments -> ToolResult.ok("{\"available\":true}")));
      }
   };
}
```

Imports are `com.kof22.agentadmin.bootstrap.NativeAgentApplication`, `NativeStores` from
the same package, `ToolBinding`, `ToolDefinition`, `ToolResult` from
`com.kof22.agentcore.runtime`, and `java.util.List`. For business records, use
`ToolBinding.forCaller(definition, (arguments, caller) -> ...)`: the authenticated requester
is separate from untrusted model arguments. The [native domain example](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/examples/native-domain/README.md)
shares one authorized service between its QQQ process and caller-aware tool.

The normal session path wraps registered tools with `ToolGate`. Reads execute with audit; authorized writes create a pending approval and execute only through the approval coordinator. Do not invoke a raw registered executor from a user-facing adapter. When Slack is disabled, audited read tools remain available; write capabilities are excluded because no approval delivery path exists.

## MCP interface

The native QQQ listener serves streamable HTTP at `kof22.agent.mcp.endpoint` (default `/mcp`). Use an MCP client that initializes the protocol and retains its transport session; the snippets below are tool-call payloads, not standalone unauthenticated HTTP requests.

For an agent named `My Agent`, the canonical exported tools are:

| Tool | Arguments | Result |
| --- | --- | --- |
| `my_agent_read_ask` | Required `question`; optional `conversationId` | Read-only conversational result |
| `my_agent_write_ask` | Required `question`; optional `conversationId` | Governed conversational result; separate downstream approval still applies |
| `my_agent_read_status` | Empty object | Agent name, model ID, registered tool names, and machine session identity |

Legacy `my_agent_ask` and `my_agent_status` remain for direct clients. Use canonical names when composing agents through `domain-servers`. The slug lowercases the name and replaces non-alphanumeric runs with underscores.

Example request arguments:

```json
{
  "name": "my_agent_read_ask",
  "arguments": {
    "question": "Summarize the records available to me.",
    "conversationId": "review_2026_09"
  }
}
```

`conversationId` accepts 1–64 letters, digits, underscores, or hyphens. Omitting it creates a fresh conversation; supplied IDs are scoped to caller and read/governed mode. The MCP tool result contains one text content item. For canonical asks, that text contains JSON:

```json
{
  "reply": "The agent reply.",
  "conversationId": "review_2026_09",
  "completion": "conversation_only",
  "effects": "read_only"
}
```

Governed asks use `effects: "proposals_only_separate_downstream_approval_required"`. Status text contains `agent`, `ok`, `model`, `tools`, and `session`. Status is a capability response; it does not run an external provider health check.

MCP authentication failures, invalid questions, and failed turns return tool results with `isError=true`; do not interpret them as successful business results just because the protocol transport returned successfully. Reads/writes denied by tool governance also produce errors or pending-approval content for the model.

Outbound `domain-servers` are MCP **clients** that mount another server's tools into this registry. Choose HTTP `url` or stdio `command`, not both. See [Configuration](CONFIGURATION.md) for exact properties. An upstream user ID is not automatically delegated identity at the downstream agent.

## Native QQQ HTTP interface

QQQ, health and enabled HTTP MCP share the native listener on `kof22.agent.qqq.port` (default `8090`). The default Next Dashboard uses native metadata and data routes. These are the principal inherited entry points, not custom application controllers:

| Method | Path | Purpose | Authentication |
| --- | --- | --- | --- |
| GET | `/` | Next Dashboard | Basic in local mode; public static shell in bearer mode |
| GET | `/metaData/authentication` | Native login configuration | Basic in local mode; public in bearer mode |
| GET | `/metaData` | Permitted application metadata | Required |
| GET | `/metaData/table/{table}` | Permitted table definition | Required; table authorization applies |
| GET | `/data/{table}` | Query permitted records | Required |
| GET | `/data/{table}/{id}` | Read a permitted record | Required |
| POST | `/data/{table}/query` | Native structured query | Required |
| POST | `/manageSession` | Exchange an access token for a native browser session | Bearer-mode flow; exact-origin checks apply |

The versioned API uses `/qqq/v1/` and its own route names: for example, `POST /qqq/v1/table/{tableName}/query`. Metadata/session routes retain their names under that prefix; table routes are not a mechanical prefix of legacy `/data/...` URLs. Additional query/count/export/process routes and their request formats come from the `com.kingsrook.qqq:qqq-middleware-javalin:4.0.0` dependency, not a foundation-specific response envelope. Inspect the matching official dependency sources when adding API calls; the historical foundation-owned middleware is excluded from the active build.

To inspect safe operational data locally, this prompts for the Basic password rather than putting it in the command:

```bash
curl --fail --user "$KOF22_AGENT_QQQ_USERNAME" http://127.0.0.1:8090/metaData
curl --fail --user "$KOF22_AGENT_QQQ_USERNAME" http://127.0.0.1:8090/data/approvals
```

The inherited operational table names are `approvals`, `auditLog`, `jobRuns`, `tokenUsage`, and `sessionMetadata`. Their generic CRUD is read-only; private arguments/results/transcripts are not exposed by those views. Server-mode named operators can use the governed denial process. Add business tables and processes as native `MetaDataProducerInterface<?>` producers; follow the [QQQ application contract](QQQ-APPLICATION-CONTRACT.md) for complete metadata, database grants, and authorization requirements.

QQQ returns HTTP 401 for invalid authentication and 403 for denied operations. Local mode denies process execution and mutations. Server-mode session/process requests require the configured same origin; generic CRUD still cannot edit operational approval/audit state. Disabled native download paths return 403.

## Other shared Java APIs

| API | Purpose |
| --- | --- |
| `AgentRuntime.run(AgentInvocation)` | Provider adapter/test substitution; honor the invocation's budget and usage reporting |
| `TranscriptStore.appendExchange(...)` | Atomic successful exchange persistence; custom stores must implement this contract |
| `DataProtectionExtension.redact(String)` | Additional domain-sensitive text protection without replacing baseline filters |
| `OperationalStatusService.snapshot()` | Aggregate counts/resource observations for trusted operational adapters |
| `OperationalRetentionService.run(String callerId, boolean dryRun)` | Explicit administrator preview/purge of eligible completed history |
| `AgentMigrations.migrate(DataSource)` | Core-first and then consumer migration lifecycle |

The operational services are explicitly assembled Java objects; they are not automatically exposed REST endpoints. Do not export maintenance methods to a model as unrestricted functions.

## Limits and errors

The Anthropic adapter returns only nonblank terminal public text (`end_turn` or `refusal`).
Truncation, paused/unrecognized stops and empty terminal responses raise `AgentRuntimeException`
after recording received usage; there is no automatic retry or model fallback. Signed assistant
content survives only inside the current tool loop, never as transcript content. See the
[completion and continuation contract](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/docs/RUNTIME-CONTRACT.md#anthropic-completion-and-continuation).

Runtime admission, context, tool-call, token, and deadline limits are documented in [Runtime contract](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/docs/RUNTIME-CONTRACT.md). They are per-process controls. There is no configurable shared HTTP requests-per-minute limiter in these modules; configure body/rate limits at the deployment ingress when exposing the server. A timeout cannot prove an external write stopped, so inspect durable approval state before retrying an uncertain operation. See [Operations](OPERATIONS.md).


## Native family clients

The opt-in [family client API](CLIENT-API.md) exposes verified household identity, private and
explicit shared conversations, durable turns, and explicit consumer artifact workflows on the
same native listener. See its [OpenAPI contract](client-api.openapi.json) and permission-revision
requirements before implementing a mobile client. It does not grant external write authority.
