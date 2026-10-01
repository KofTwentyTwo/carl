# Carl operator runbook

This is a development handoff, not a production qualification. Use synthetic data until the owner confirms identities, access, model data handling, currency mappings, source accounts and hosting policy.

## Database and startup

Use one dedicated PostgreSQL database for Carl. Follow [foundation database roles](foundation/DATABASE-ROLES.md) and [migration lifecycle](foundation/MIGRATIONS.md) with the packaged application jar. The foundation `DatabaseBootstrap` CLI provisions separate migrator, runtime and QQQ reader roles. Runtime validates both histories and must not migrate. Carl migrations live in `db/migration` under `agent_domain_schema_history`; never modify a migration already applied to a retained database.

Grant the QQQ reader SELECT only on the permission-aware views used by current metadata; the exact view list is maintained in the synthetic packaged fixture `scripts/e2e/CarlPackagedVisualFixture.java`. Native UUID-key views must use `carl_native_plan_step_view` and `carl_native_calendar_operation_view` so QQQ key/label lookup uses text values while retaining the underlying current-permission projection. New workflow views include `carl_draft_revision_view`, `carl_manual_transaction_view`, `carl_preference_view`, `carl_reminder_observation_view` and the three `carl_rental_review*_view` projections. Do not grant it direct access to source documents, upload bytes, identity mappings or underlying domain tables. Current native HTTP/browser tests exercise a restricted reader. Exact bootstrap grants must match the selected metadata and foundation version.

An operator provisions the household display zone, real verified member mappings and explicit domain/record permissions from owner-confirmed decisions. PERSONAL owner labeling does not grant access. A fresh application intentionally rejects unmapped principals. The automated fixture provisions synthetic Alice/Bob solely inside disposable PostgreSQL.

After qualified migration and private configuration, launch `target/agent/bin/agent /absolute/path/to/agent.properties`. The script resolves its jar and default config independently of the working directory. Keep migration credentials out of runtime configuration; keep all secrets out of source and command-line history.

## Household workflows

- Create permitted accounts with explicit currency/type/share and evidence. Source account labels are not stable provider identities; map them explicitly using Map Monarch Account.
- Import from Monarch accepts the Settings transaction and balance CSV exports. Review mapping gaps, changed IDs and ambiguous balances, then explicitly apply. Each file's application is atomic; a transaction file can commit while balances remain PARTIAL/NEEDS_REVIEW. Resolve a documented balance observation and resume the same review. Original private uploaded bytes and selected-row evidence remain retained.
- Identical source observations do not change balances or invalidate plans. Revisions append source history, absent rows do not delete facts, and transfers need explicit classification/pairing. Owner/Reviewed CSV fields grant no permissions.
- Enter bill records/imports using [the documented template](BILL-IMPORT.md), correct local bills with attribution, maintain vendor work and generate stored household reports/local drafts.
- Vendor corrections and human-edited drafts retain attributed immutable versions. Copy/download rechecks source access and refuses stale drafts; no send capability exists.
- Human-entered transactions retain original evidence separately from Monarch. Category budgets show exact expenses less refunds over permitted records and explicitly partial coverage. Personal/household presentation preferences never change authorization.
- Record Debt Statement Terms requires explicit positive principal, date, minimum rule, APR, fees and evidence. Compare Baseline Debt Payoff saves avalanche/snowball/minimum-only estimates against an explicitly assumed monthly budget. It is not verified affordability, an issuer quote or a complete refinancing recommendation. Changes retain provenance and mark prior fact snapshots stale.

## Failures and recovery

A saved artifact is reauthorized on retrieval. Permission-epoch changes conservatively deny old reports/drafts, including data held in QQQ; regenerate under the current authorized scope. Source fact revisions mark still-permitted snapshots stale. UNKNOWN workflow outcomes must be reconciled by request ID; do not create another request to make the warning disappear.

Uploads and source history have no owner-approved production retention/deletion policy or expiry job. Report interruption reconciliation, native import review, selected-plan tracking and bounded provider synchronization are implemented and controlled-qualified; see [current acceptance](ACCEPTANCE.md). No external vendor sends or financial writes exist. The designated Synology calendar/reminder has separate standing write authority, but actual endpoint/collection/client compatibility and live synchronization remain unqualified.

Before any production deployment, complete the requirements and scans, confirm retention/provider data handling, qualify TLS and identity, monitor failures, and test encrypted backup restore plus a compatible upgrade/rollback procedure. Never restore stale permissions into an active internet-facing service: advance permission epochs and revoke sessions/tokens as part of a separately qualified restore.

## Designated Synology publication settings

The runtime reads only explicit operator-supplied environment settings for optional publication. `CARL_CALDAV_EVENTS_COLLECTION` and `CARL_CALDAV_REMINDERS_COLLECTION` are fixed HTTPS collection URIs, paired with `CARL_CALDAV_EVENTS_AUDIENCE_MEMBERS` and `CARL_CALDAV_REMINDERS_AUDIENCE_MEMBERS` (comma-separated provisioned member IDs). Credentials use `CARL_CALDAV_USERNAME` and `CARL_CALDAV_PASSWORD`; `CARL_CALDAV_STANDING_PRINCIPAL` must identify an active, explicitly permitted Carl member. No default collection, family audience or credential is invented. Keep values in protected runtime configuration, never source or public diagnostic output.

Each operation checks the initiating member and standing authority under the same household/plan locks, then checks every configured recipient against the current plan and source permissions. Native administration offers publication, retirement, synchronization and reconciliation plus permission-filtered operation history. Transport instances close after each operation; the application permits two concurrent operations. Without collection settings, the UI accurately reports that publication is not configured.

A plan task currently maps to one fixed collection/component: publish it as a calendar event **or** a reminder. Moving the same task to another collection or publishing it to both is rejected. Remote edits are observations; they do not verify financial completion. UNKNOWN/PENDING operations must be reconciled using the saved operation ID. Do not clear outbox rows or replay uncertain operations under a new ID. Actual Synology capability discovery, endpoints, recipient ACLs and device behavior still require authorized live qualification.


## Native table preferences

The current packaged frontend is Next UI1.0.0-RC.7. Table column preferences are separate from record-detail section ordering. For a practical narrow review, use **Configure columns** to select Title, Amount, Currency and Effective date, then move Title first with the native drag handle or arrow keys. This is an authenticated user's browser preference, not an application-wide default. Record details use Carl's native sections and source-evidence grouping. Universal default grid ordering remains an upstream UI qualification gap; Carl does not carry a separate frontend fork.

Consumer CLI verification now also enforces ErrorProne, existing KofTwentyTwo copyright headers and a whole-application coverage regression floor of80%lines/60%branches without exclusions. The foundation retains its separate100%core/adapter gate. `mvn verify` is authoritative; IntelliJ's native compilation omits the ErrorProne profile because its embedded compiler lacks the Maven JVM export flags.

## Expense inputs and protected reserves

Use Expense Schedules for committed, estimated or hypothetical recurring amounts; optional month overrides preserve seasonality. Record a manual payment assertion or classify an imported outflow, then apply evidenced amounts to their actual due occurrences. An import revision makes the payment snapshot stale; remove affected applications, review the revised source and use Review Changed Expense Payment before applying it again. Original imports and correction history remain preserved.

Replace Cash Forecast Expense Selection replaces the full selection, with up to four schedules and four standalone payment records in the current guided form. The service supports larger explicit selections, but broader native selection UI is not yet qualified. Linked payments are included once; do not also enter the same source as a manual cash event. Selected reserve earmarks are **additional to the cash plan's base reserve floor**, protected for the entire interval, and never counted as expenses. Do not select reserve amounts already included in the base floor. Purchase assessments save the selected expense projection, source revisions, coverage gaps and this policy. Their expense as-of date comes from Carl’s clock in the verified household display zone, separately from the requested purchase date. Retries reuse the original snapshot. Daily closing cash remains a conditional estimate rather than an intraday guarantee. Unknown amounts, stale source snapshots or incomplete selected inputs prevent a precise supported purchase budget.

The QQQ reader additionally needs the permission-aware `carl_expense_view`, `carl_expense_actual_view`, `carl_expense_settlement_view`, rental dependency views and tax property view used by current metadata. Never grant the underlying expense, selection, identity or source tables to the administrative reader.

## Shared calendar agenda refresh

With the designated `CARL_CALDAV_EVENTS_COLLECTION`, standing principal, credentials and explicit audience configured, native **Refresh Shared Calendar Agenda** reads a requested window of at most ninety days. It uses PROPFIND/REPORT only; this operation does not create an appointment. The persisted connection binds the configured endpoint fingerprint and exact audience. A changed endpoint/audience requires an explicit operator migration rather than silently reusing the old projection. Public event details are available only to that authorized audience; PRIVATE/CONFIDENTIAL series and exceptions remain Busy unless separately qualified occurrence grants are added. No such private grants are inferred here.

Review request outcome separately from connection state. Failed or expired reads preserve previous data and last success. A successful bounded refresh hides absent occurrences only inside its requested window and retains provenance; it does not establish availability outside that window. Reports mark incomplete coverage, distinguish each event's observed time and include deterministic apparent overlaps. Current ingestion supports qualified VEVENT recurrence forms only; shared VTODO plan publication/reconciliation remains a separate process. Actual Synology version, credentials, collection capability and client compatibility still require live qualification.


Native account/property selection uses `carl_balance_selection_view` and `carl_rental_baseline_selection_view`; grant the restricted QQQ reader only these protected views in addition to the existing required list. Start balance review from **Accounts and Properties for Review**, check the authorized rows, then choose **Actions → Review Selected Accounts and Properties**. Select a property valuation method explicitly. No raw record-ID entry is required.

Native process context expires after24hours or a permission epoch change. Reopening stale results is denied; start from current authorized records. A scope change during a submitted operation may occur after its commit, so inspect/reconcile the current durable outcome before resubmitting. The receipt-only rental review edits and tax-reference status change are explicitly tested exceptions containing no retrieved private figures/evidence; adding sensitive receipt output requires changing that flow, not weakening the guard.

## Native conversations and current delivery

Use [Talk to Carl](TALK-TO-CARL.md) for private or explicitly shared conversations over the same saved workflow state used by family clients. The synthetic preview at https://localhost:64503 runs the qualified RC7 development package. Its model is offline until the already-authorized personal1Password CLAUDE_API credential can be read securely after desktop unlock/CLI approval. An offline or UNKNOWN receipt is not a completed model answer; do not repeat inference under a new request ID.

Current controlled source/package gates pass377 tests and59 browser checks with fresh source/artifact scans. Foundation PR15 is merged; main0.5.0-SNAPSHOT publication remains pending in run36811134831. Carl push/package credentials remain blocked, and timestamped QQQ/Quick Search dependencies prevent RC/stable promotion. Keep immutable0.4.1 release evidence separate from the current development parent. Production deployment and real-data/model/provider acceptance remain separate milestones.
