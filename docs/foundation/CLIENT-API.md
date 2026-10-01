# Family client API

The optional `/api/agent/v1` JSON API lets a native client talk to the same agent runtime
and database used by QQQ. It provides authenticated household membership, private
conversations, explicitly shared conversations, durable asynchronous turns, polling and
best-effort cancellation. [The OpenAPI contract](client-api.openapi.json) describes the wire
format. This feature is under qualification on the family-client API branch; a published
foundation version supporting it has not yet been qualified.

## Operator configuration

The listener is the existing native host. Keep it on literal loopback behind an authenticated
HTTPS deployment as described in [PERSONAL-SERVER.md](PERSONAL-SERVER.md). The proxy must
preserve the configured public Host, strip untrusted forwarding headers, enforce request
body/time/connection limits, and avoid logging Authorization or request/response bodies.
The API uses the public origin configured for QQQ but a **different token audience**.

```properties
kof22.agent.client-api.enabled=true
kof22.agent.client-api.issuer=https://identity.example/
kof22.agent.client-api.audience=carl-native-api
kof22.agent.qqq.public-origin=https://carl.example
```

These values are examples, not selected identity providers or production settings. With this
API enabled, production startup can omit Slack and MCP; scheduled Slack delivery remains
unavailable. Production still requires QQQ `oidc-bearer` mode and its own operator audience.
No machine credentials, model API keys, database passwords or client secrets belong in the
phone app. Run normal offline migrations and grant-access before startup. The new V8 ledger
is foundation-owned; do not duplicate it in a consumer migration.

The access-token verifier supports this explicit profile:

- RS256 signed JWT access tokens with header `typ=at+jwt` and a bounded nonblank `kid`.
- Exact configured HTTPS issuer and expected audience, valid `exp`, `iat` and optional `nbf`.
- Textual nonblank `sub`, `client_id`, `jti`, and space-delimited `scope` containing `agent:chat`.
- Signing keys fetched from the configured issuer's `/.well-known/jwks.json` endpoint using
  the existing Auth0 JWKS provider, bounded timeouts, cache and fetch rate limiter. Token
  header URLs do not select an endpoint.

An ID token, QQQ session cookie, MCP bearer token, generic `typ=JWT` token or a token for the
operator audience is not accepted. Configure the chosen identity provider to emit this
profile and qualify its key rotation/revocation behavior before internet deployment. There
is no automatic OIDC discovery, arbitrary JWKS override or built-in login/refresh endpoint.
The native app should use its provider's Authorization Code flow with PKCE in the system
browser, an approved native redirect, and Keychain storage. Refresh and logout belong to the
identity provider. Clearing tokens alone does not revoke server membership.

## Consumer integration

Override `NativeAgentRuntime.Components.familyAccess()` in the same explicit component
factory used by application main and tests. `FamilyAccess.find(issuer, subject)` must query
current verified principal mappings and return a stable `Member(id, household, caller, permissionRevision)` only
for active authorized members. `caller` is the existing domain/RBAC identity, not a role
assigned from JWT claims. `members(household)` returns current active stable member IDs.
Never reuse a removed member ID for another person.

`permissionRevision` is a persisted, trusted **household-wide** version token shared by all
member mappings in that household. Consumers with mutable protected records (including Carl)
MUST atomically advance it with every relevant record, field, membership or sharing permission
change. Conversations snapshot it; a different current revision denies old conversation,
turn and workflow access and excludes them from lists. A new conversation starts fresh, without
copying or silently reopening old context. Every response and model completion rechecks identity
before publishing. Source-level authorization remains mandatory. The three-argument Member
constructor's fixed `initial` revision is only suitable for immutable permissions or consumers
without protected domain records; it does not qualify Carl's revocation requirements.

Return the same durable revision across application restarts. After restoring an older backup,
advance the revision and revoke affected sessions/tokens before admission so previously revoked
permissions cannot be reintroduced. `/me` exposes the current revision. A native client must
clear cached protected content on revision change, logout or revoked authentication. It must
not display offline cached protected content unless a separately approved offline access policy
exists; cache invalidation cannot retract information already seen by a human.

`FamilyAccess.tools(member)` defaults to an empty set. Explicitly return only caller-aware
read-tool names whose services enforce household, record and field access. The runtime also
filters out writes. Shared conversations expose **no tools**, because invoking one member's
private tools into a shared transcript would leak their data. Do not allow global operational
memory tools as family profile storage. A tool allowlist does not replace service permissions.

Every HTTP request verifies token signature and current membership. Turn execution rechecks
that mapping before provider dispatch. Revoked members cannot retrieve existing transcripts
with an otherwise unexpired token. In-flight provider work may already have seen its context;
revocation cannot retract data already processed. Permission changes must therefore be enforced
again by every domain tool. Deployment needs an explicit retention/deletion and model-provider
handling policy; transcript compaction is not deletion of the durable client ledger.

## Client request sequence

1. Obtain the dedicated access token. Call `GET /me`, then `GET /members` when the human
   explicitly chooses participants for a new shared conversation.
2. Generate a UUID locally. `PUT /conversations/{id}` with `{}` creates a private conversation.
   To share, send `{"shared":true,"participants":["member-a","member-b"]}` including yourself.
   Visibility and participants are immutable. Creating another conversation never copies
   private history. There is no automatic sharing with future household members.
3. Generate a new turn UUID. `PUT /conversations/{id}/turns/{turnId}` with
   `{"message":"Which bills are due this week?"}` returns its durable state promptly.
4. Poll the individual turn with a moderate interval (for example two seconds and backoff).
   Render `COMPLETED` reply, `RUNNING`, `CANCEL_REQUESTED` or `UNKNOWN` explicitly. Do not turn
   `UNKNOWN` into a claimed failure or automatically replay it with another UUID.
5. After a connection timeout, GET the same ID and retry PUT with the **same ID and original
   payload** when appropriate. Identical requests are idempotent; a changed payload or different
   author returns `409 idempotency_conflict`. Stable local IDs survive app restarts.
6. `POST .../turns/{turnId}/cancel` is available only to the author. It requests interruption;
   it does not undo provider work. Cancelled or interrupted outcomes become `UNKNOWN` unless
   already completed. Capacity remains held until the actual worker exits.

Lists use ascending `sequence` keyset pagination: `?after=0&limit=25`, maximum 50. Advance
`after` to the final returned sequence and continue until an empty page. Sort by sequence,
not UUID. Conversation and turn IDs are canonical lowercase UUID strings. Inaccessible and
absent records both return 404. Errors contain a safe code; never show stack traces or infer
record existence from them. Responses are `Cache-Control: no-store`.

Limits: 64 KiB JSON bodies, at most 16 participants, 32 simultaneous HTTP requests,
60 requests/member/minute, eight client workers, one worker/member and one active
turn/conversation. Core model/tool/context/concurrency limits also apply. `429` includes
`Retry-After`; retry the same logical request with backoff. The per-process member-rate map
holds at most 4096 member identities; this foundation targets a bounded household deployment.
The database lease admits one host per dedicated database. Restart marks interrupted turns
UNKNOWN and never replays them automatically.

## Explicit reports and drafts

Ordinary conversation uses the governed read-only invocation. A read tool must not hide
report/draft/domain mutations. Consumers can separately register named `ClientWorkflow`
handlers through `Components.clientWorkflows(stores)` for explicit user requests:

```http
PUT /api/agent/v1/conversations/{conversationId}/workflows/report/{requestId}
Content-Type: application/json
Authorization: Bearer <dedicated access token>

{"input":{"period":"week"}}
```

Workflow names and input are consumer-specific, not a promise that every agent has a `report`
handler. The handler receives verified member, conversation, immutable participant snapshot
and shared/private visibility outside the input JSON. It validates a narrow typed schema,
rechecks the authorized **shared intersection** of records, binds a durable request to kind,
conversation, audience and payload, and atomically deduplicates artifact persistence. No
model-supplied caller ID, SQL, destination URL or generic mutation command is permitted.
Return PENDING promptly if work continues in the background; the consumer owns its bounded
worker lifecycle. The host calls each registered handler's idempotent `close()` before releasing
its stores/providers; handlers must stop/join their workers or throw while cleanup remains
incomplete. GET the same path retrieves the current result after authorization is
rechecked. Results use PENDING, COMPLETE, PARTIAL, FAILED or UNKNOWN with authenticated
artifact content, never an unauthenticated download URL. Consumer tests must prove retry,
restart, revocation and source-access behavior; the foundation cannot infer business policy.

## iPhone implementation handoff and qualification

Use Codable wire types from OpenAPI, URLSession with system TLS validation, and the chosen
identity provider's supported native PKCE flow. Keep auth tokens out of logs, analytics,
crash attachments and shared preferences. Clear in-memory and local cached private content
on logout/account change. Present a clear participant list before shared conversation creation;
never offer a control that silently converts private history into a shared chat. UI must
identify stale/unknown outcomes and allow human-controlled retries with preserved IDs.

No streaming, APNs, background notifications, attachments, generic write approvals, external
send/calendar/payment routes or native UI are supplied by this first contract. The consumer
must separately qualify identity mapping, allowed tools, explicit workflows, TLS/proxy,
provider data handling and production operation. The automated API tests use synthetic JWTs,
controlled runtimes and actual HTTP/PostgreSQL; they do not qualify a real identity provider
or authorize internet deployment.


## Maintainer evidence map

| Contract | Automated evidence |
| --- | --- |
| Dedicated JWT profile and current principal mapping | `ClientIdentityTest` |
| Actual HTTP/PostgreSQL private defaults, sharing, retries and revoked membership | `ClientHttpTest` |
| Permission changes hide old history and suppress in-flight replies | `ClientStoreTest.permissionRevisionChangesHideEarlierTranscriptsAndRequireANewConversation`, `ClientServiceTest.permissionChangeDuringInferenceDiscardsTheReplyBeforePublication`, `ClientServletBoundaryTest.refusesResponseWhenPermissionRevisionChangesDuringRequest` |
| Durable singleton lease, cancellation, restart UNKNOWN and SQL failures | `ClientStoreTest`, `ClientServiceTest` |
| Caller-owned private history, empty shared tools and retained capacity | `ClientSessionTest` |
| Native configured listener, JWKS, migrations, startup cleanup and no Slack/MCP | `NativeClientHostTest` |
| HTTP admission, body/parser bounds, malformed routes and rate limits | `ClientServletBoundaryTest`, `ClientHttpTest` |
| Actual multipart file/request/part/file-count bounds before storage | `NativeUploadPolicyTest` |
| Explicit workflow scope and owned background shutdown | `ClientHttpTest.explicitWorkflowReceivesVerifiedScopeAndCannotBeReachedThroughAnotherMembersPrivateChat`, `ClientServiceTest.registeredWorkflowWorkersAreClosedBeforeTheDatabaseLeaseIsReleased` |

Run `bash scripts/verify-foundation.sh` for the full local acceptance gate and generated
consumer/package checks. Run `bash scripts/verify-source.sh` for source, workflow and secret
checks. The ordinary full core and QQQ adapter verification retains 100% unit line and branch
coverage. These tests establish the shared boundary; consumer business acceptance and a real
provider deployment require the additional evidence described above.
