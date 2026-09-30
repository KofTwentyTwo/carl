# Operations runbook

Run one active agent JVM/container per dedicated PostgreSQL database and use its own Slack
app and credentials. Consumers inherit the operational services and QQQ UI from the
foundation. Start with [GETTING-STARTED.md](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/GETTING-STARTED.md); for server configuration use
[PERSONAL-SERVER.md](PERSONAL-SERVER.md).

## Start / stop / restart

The bundled generator ships a Dockerfile and an ordinary-JAR distribution (`app.jar`, `lib/`, configuration and prompts). It does not ship
a Docker Compose stack. For local development, load the private runtime environment and
run from the generated app directory:

```sh
set -a
. /absolute/path/agent.env
set +a
sh target/agent/bin/agent
```

The two disabled integrations are for the initial local administration check. Normal agent
operation enables them with their configured credentials; production mode requires both.
Use Ctrl-C for a foreground stop and the same command/database for the next start. With the
adapted `deploy/agent.service.example` installed as `agent.service`, inspect logs with
`journalctl -u agent -f` and manage the process with `systemctl start|stop|restart agent`.
For your deployed container, use its container/service name with the chosen supervisor.

**Restart drill.** Sessions, transcripts, approvals, audit, memory, and job history all
live in Postgres. Completed turns are durable. An interrupted turn may have dispatched a
tool without persisting its reply; inspect approval execution state and external records
before retrying. Stop inbound work and drain active requests before a planned restart.
The conversation restart test alone does not prove external-action recovery.

**Startup validation.** Missing identity/persona, invalid persona content or incomplete
enabled-integration settings fail startup. Production additionally checks the database role,
identity, listener and migration settings described in [CONFIGURATION.md](CONFIGURATION.md).
The exception identifies the failing requirement; correct the configuration before restart.

## Inspect through QQQ

Open the configured administration URL. Local setup uses `http://127.0.0.1:8090/` and the
Basic credentials; server operation uses the configured HTTPS origin and verified operator
login. **Operations** provides approvals, audit history, job runs, token usage and session
metadata. New databases begin with empty history. Payloads and transcript contents are
intentionally absent from these shared views. Use the consumer's domain navigation for its
business records. Generic core edits/exports are denied; decisions use governed processes.

`/health/live` and `/health/ready` are on the same native QQQ listener; readiness reflects admitted runtime resources.
Operational state is available through QQQ and `OperationalStatusService.snapshot()`;
there is no separate public operations-status API implied by that service method.

## Approvals

- Writes park behind a Block Kit card in the requesting thread (Slack) or in
  `kof22.agent.mcp.approval-channel` (MCP-originated). No approval, no execution — ever.
- TTL (`kof22.agent.approval.ttl`, default PT4H): expiry equals denial. A Quartz sweep expires
  overdue approvals every 5 minutes; a late click gets "expired", never a late execution.
- Self-approval is invalid; `approver` role or above decides. Dispatch rechecks the requester,
  original approver, and current callback identity. A repeat approval can recover work that
  was approved but never claimed; it cannot replay a started execution.

Permission (`status`) and execution (`execution_status`) are separate. `APPROVED` never
means that a write succeeded. Execution states:

| State | Meaning and operator action |
| --- | --- |
| READY | No dispatch claim. If permission is APPROVED, an authorized repeat Approve callback can claim it. |
| RUNNING | Dispatch intent and audit committed. The external call may be in progress or interrupted; inspect and reconcile. Never replay. |
| UNKNOWN | A transport/tool failure or legacy approval leaves external effects uncertain. Reconcile; never replay. |
| SUCCEEDED | Tool reported success, or an administrator recorded external evidence of success. Terminal. |
| NOT_EXECUTED | Administrator verified no execution after stopping the executor. Terminal; a new action needs a fresh approval. |

The V5 migration marks historical APPROVED rows UNKNOWN conservatively. For uncertain
work, first stop/quiesce the relevant executor, then inspect the downstream system. In a
trusted application maintenance context call `ApprovalService.reconcile(id, adminId,
observed, evidence, rbac, audit)` using the authenticated administrator identity and the
configured services. Only ADMIN, a nonblank evidence reference, and observed SUCCEEDED or
NOT_EXECUTED are accepted. It records evidence/audit atomically and never calls the tool.
There is currently no HTTP/UI reconciliation process; generic QQQ CRUD must remain read-only.
Database row locks do not stop an external call already in progress, so executor quiescence
is required before reconciliation. This is one dispatch claim per approval, not an
exactly-once guarantee for external effects or separate duplicate requests.

Pending approvals right now:

```sql
select id, tool_name, requested_by, expires_at
from approvals where status = 'PENDING' order by expires_at;

-- Approved work requiring attention (avoid selecting raw financial arguments).
select id, tool_name, status, execution_status, execution_audit_id, execution_updated_at
from approvals
where status = 'APPROVED' and execution_status in ('READY', 'RUNNING', 'UNKNOWN')
order by id;
```

## Audit queries

Every tool call has a row in `audit_log` — reads, writes, refusals, MCP calls, rejected
tokens. Useful cuts:

```sql
-- What did the agent do today?
select created_at, caller_id, tool_name, decision
from audit_log where created_at > current_date order by id;

-- Every write and its outcome
select a.created_at, a.tool_name, a.caller_id, a.decision,
       p.status, p.execution_status, p.decided_by
from audit_log a left join approvals p on p.id = a.approval_ref
where a.tool_name like '%\_write\_%' escape '\' order by a.id desc;

-- Denials (RBAC refusals, bad tool names, unknown MCP tokens)
select created_at, caller_id, tool_name, decision
from audit_log where decision like 'DENIED%' order by id desc limit 50;

-- One conversation, end to end
select created_at, caller_id, tool_name, decision
from audit_log where session_key = :session_key order by id;
```

## Cost accounting

Every received provider response writes observed usage to `token_usage`, including intermediate
responses before a later failure. A custom runtime may report once per completed turn.
Missing responses have unknown billed usage; an `EXECUTION_UNKNOWN` runtime audit marks
incomplete evidence. Rows count responses/accounting events, not conversations. The report:

```sql
-- Tokens by month and model
select date_trunc('month', created_at) as month, model_id,
       sum(input_tokens) as input_tokens, sum(output_tokens) as output_tokens,
       count(*) as accounting_events
from token_usage group by 1, 2 order by 1 desc, 2;

-- Who is spending it (Slack users, mcp:* machine callers, scheduler:* jobs)
select date_trunc('month', created_at) as month, caller_id,
       sum(input_tokens + output_tokens) as tokens
from token_usage group by 1, 2 order by 1 desc, 3 desc;
```

Multiply by the current per-model rates to get dollars; rates change, the SQL doesn't.
The implemented provider path is Anthropic; OpenRouter fallback and cheaper-tier routing
are deferred design items, not deployable configuration in this version.

## Scheduled jobs and operational signals

The native host configures persistent QQQ/Quartz scheduling directly; core V6 owns its
PostgreSQL tables and the offline bootstrap installs them. Run **one active application per database/schema**.
A dedicated PostgreSQL advisory lock refuses a competing scheduler before recovery changes
history. Loss of that database session refuses later dispatch. It is not a distributed
fencing token and cannot cancel an already-dispatched external effect.

Stable job/trigger identities reconcile changed cron configuration. Set
`kof22.agent.scheduler.time-zone` explicitly (default UTC). Quartz skips misfires with
`DO_NOTHING`, records observed `MISSED` work and audit attention, and does not replay jobs
after an outage. Restart marks old `RUNNING` records `UNKNOWN` under the exclusive lease.
The next cron occurrence is new work, not a retry of an uncertain prior effect. Same-job
triggers cannot overlap through the native Quartz execution path. A trusted direct call to
`ScheduledJobRunner` is a separate maintenance operation and must respect quiescence.

`OperationalStatusService.snapshot()` supplies timestamped counts/ages for approvals and
jobs, observed token totals, JVM resources, conversation reservations and approved-write
reservations. It returns no payloads and fails if observations cannot be read; failures are
not fabricated zeroes. QQQ exposes safe operational rows and attention counts. Connect the
snapshot to the chosen authenticated monitoring system; define alert thresholds and owner
with the deployment operator. No outbound alert delivery or unattended paging is configured.

## Memory and conversation history

Memory changes use the approval gate. Model replay is bounded independently from durable
retention. Histories use `SessionManager.ownedKey(logicalKey, authenticatedCaller)`; callers
do not share a transcript simply because they supply the same logical key. Existing unowned
history remains stored but is not automatically adopted. Any legacy migration needs an
explicit verified ownership map. No destructive caller reset or automated memory/transcript
purge is implemented. Perform authorized maintenance after quiescing affected sessions.

User and assistant entries commit as one exchange. If the caller times out during commit,
inspect the caller-owned history before repeating the request: the whole exchange may have
committed even though no response reached the caller. A failed transaction stores neither
entry. Capacity remains reserved until the original work actually exits.

## MCP endpoint

- Keep the listener on loopback or an isolated container network behind qualified TLS and
  ingress request/rate limits. A container-internal wildcard bind does not authorize a public
  host port. Require bearer authentication for every HTTP call.
- Rotate a machine caller: change its token in the secret store and restart; the RBAC
  mapping for `mcp:<name>` is unchanged. Properties files escape the colon in the key,
  for example `kof22.agent.rbac.users.mcp\:local=OPERATOR`. Remove a caller by deleting both.
- Unknown tokens are rejected AND audited — watch for repeated
  `DENIED_MCP_AUTH` rows as a probe signal (query above).
- Local-dev stdio: `kof22.agent.mcp.stdio=true` + `kof22.agent.mcp.stdio-caller=<name>`; stdio
  owns stdout, so it is never enabled in the container.

## Backup and retention

From the foundation checkout, use `bash scripts/database-backup.sh new-file.age` with explicit `PGHOST`, `PGDATABASE`,
`PGUSER` and `AGE_RECIPIENTS_FILE`. PostgreSQL credentials come from a private passfile or
secret environment. It streams a custom-format dump directly into age encryption and
refuses to overwrite an existing backup. Keep the private age identity away from the backup
host. Database volume encryption, host access, backup scheduling/off-host storage and chosen
recovery objectives are deployment responsibilities.

Restore only into a newly created isolated database: set `PGDATABASE` and
`RESTORE_DATABASE_NAME` to the same target, set `AGE_IDENTITY_FILE`, and run
`bash scripts/database-restore.sh backup.age` from the foundation checkout. These scripts
are not copied into generated apps; PostgreSQL clients and age must be available where they
run. The restore refuses nonempty targets and restores in one
transaction. Reapply roles/grants (dumps omit ACLs/owners), verify row counts and application
behavior, and only then switch traffic. Never run the restored agent alongside the old one
against live integrations. `scripts/verify-packaged-agent.py` exercises this drill with
synthetic data and matching PostgreSQL clients.

`kof22.agent.policy.retention.max-age` enables **manual** retention through
`OperationalRetentionService.run(authenticatedAdminId, dryRun)`. No retention period or
purge schedule is assumed. Preview first. The service checks ADMIN, audits the operation,
and deletes only eligible completed history in a transaction while preserving unresolved
approvals, unknown/running work and their evidence. It uses bounded lock/transaction timeouts
but can block writers; schedule a maintenance window. Transcripts and memory remain outside
this cleanup because no durable inactivity/supersession marker exists. See
[RUNTIME-CONTRACT.md](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/docs/RUNTIME-CONTRACT.md) for redaction and sensitive-data boundaries.

## Incident quick paths

| Symptom | First moves |
|---|---|
| Agent silent in Slack | Read the configured supervisor/container logs; check Slack is enabled and inspect token validity without printing credentials. |
| Container restart loop | Startup validation message in the first 30 log lines names the missing config. |
| Writes never execute | Check `approvals` for PENDING past `expires_at` (nobody clicked), and that the approver's Slack id has `approver` role in RBAC. |
| Domain tools missing | Startup logs show each `Mounted domain tool …`; a naming-contract violation aborts boot with the offending tool name. Check the domain server subprocess/URL is reachable. |
| Model errors | Check provider health and credentials without printing them. Inspect approvals and audit before retrying: earlier tool calls in the failed turn may already have executed. |
| Cost spike | Second cost query above — look for a runaway `scheduler:*` or `mcp:*` caller, then its audit trail. |

## Live-smoke checklist (first deploy of any agent)

Use authorized synthetic/sandbox records for these checks. Run only the business actions
needed to qualify the consumer's intended workflow; this checklist does not grant access
to real customer or family data.

1. Boot with the intended model and Slack configuration; DM the agent → reply in thread.
2. Ask for a write → card appears; approve → executes; check the audit pair.
3. Let one approval expire (or set `kof22.agent.approval.ttl=PT1M`) → expiry = denial.
4. Fire the digest job manually (temporary near-term cron) → posts to the channel.
5. Configure an MCP client with the endpoint and bearer token using its private credential
   facility. Verify `{agent}_read_ask` answers and a wrong token is rejected; check both audit rows.
6. After a completed turn, stop/start the agent through its supervisor → conversation resumes
   using the same authenticated caller. This checks persisted conversation history.
7. Sign into the actual QQQ UI, open a stored domain row and confirm an unauthorized direct
   API mutation is refused. In server mode, verify the intended operator identity and logout.

## Upgrade and rollback

Back up the database and preserve the old immutable jar/image, prompt/config versions and
secret references. Quiesce ingress, scheduled work and approved-write executors. Inspect
RUNNING/UNKNOWN records and record external reconciliation evidence where needed. Apply
packaged migrations with the separate migration credential, review reader grants, then
start the new **single** instance and run the local readiness/authorization checks before
restoring ingress. Native QQQ operator sessions are private writer data, never reader data.

Flyway migrations are forward-only. Do not point an older binary at a newer schema unless
that pair has an explicit compatibility test. The conservative rollback path is a prior
qualified image plus a restored matching database in an isolated target. Reconcile external
effects that occurred after the backup before reopening writes. This runbook is not evidence
that arbitrary historical binary/schema rollback or a live personal-server upgrade passed.
