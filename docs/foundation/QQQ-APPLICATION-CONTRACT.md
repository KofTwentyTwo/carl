# QQQ contract for every agent application

The foundation owns the reusable QQQ server, authentication, core operational metadata,
configuration, and packaging in `kof22-agent-qqq`. Agent repos inherit that artifact and supply
their business metadata through standard QQQ `MetaDataProducerInterface` producers. Generic
administration code must not be copied into consumers. The 99% goal is an ownership rule,
not a measured completion percentage.

## Data and migration contract

Every agent ships a QQQ backend UI with navigation, table queries, record details, and appropriate domain processes. Build its `QInstance` from metadata producers; use typed `QTableMetaData`/`QFieldMetaData`, meaningful labels/sections, primary keys, explicit SQL-column mappings, joins and possible-value sources. Enrich and validate the instance before serving the inherited Next UI. Use a pinned compatible backend/frontend set; verify against the matching QQQ sources. Domain records belong to the agent's database/schema and use versioned migrations, foreign keys, uniqueness/check constraints, and indexes for actual filters. Use `BigDecimal`/SQL `numeric` with explicit currency/scale for money; distinguish dates from UTC timestamps. Avoid opaque JSON for core searchable business entities. Runtime tables remain core-owned. Domain migrations use `db/migration` and the separate `agent_domain_schema_history`; core migrations use the reserved `db/agent-core` path and retain `flyway_schema_history`. Both domains can start at V1. Use the shared offline migration bootstrap described in [MIGRATIONS.md](MIGRATIONS.md).

## Authorization and shared services

QQQ UI and tools must share authoritative domain services and enforce permissions server-side, including household/tenant record scope and sensitive fields. Deny generic mutation of approvals, audit, execution outcomes, transcripts, and accounting history; use explicit audited processes for changes. Never expose credentials, unrestricted exports, or raw sensitive payloads merely because a table exists. Validate required/defaulted fields in Java as well as the database: QQQ may insert explicit nulls. Check action/record errors; use explicit transactions for multi-record financial changes and deterministic ordering for pagination. Security-lock join paths must use non-null ownership links; `QInputSource.SYSTEM` is not a permission bypass. Acceptance requires booting the real app, loading the UI and its metadata/data endpoints, migration round trips against Postgres, denied direct API mutations, and isolation tests. A local-only starter is not production authentication or deployment evidence. Reference: [QQQ source](https://github.com/QRun-IO/qqq) and [Next UI source](https://github.com/QRun-IO/qqq-frontend-next); test the exact pinned artifacts rather than assuming documentation matches.

## Register a domain table and navigation

Return native metadata producers from the application's explicit `Components.metadata()`
method. The shared host applies native ordering, then enriches and validates the instance.
Do not define another QQQ server or replace the foundation's authentication metadata.

For example, after a consumer migration creates a `domain_record` table with a `bigint`
primary key `id` and required `text` column `name`, a read-only operator view can use:

```java
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLTableBackendDetails;

final class DomainMetadata
{
   static MetaDataProducerInterface<QTableMetaData> domainRecords()
   {
      return instance -> new QTableMetaData()
            .withName("domainRecords")
            .withLabel("Domain Records")
            .withBackendName("agentOperations")
            .withBackendDetails(new PostgreSQLTableBackendDetails().withTableName("domain_record"))
            .withPrimaryKeyField("id")
            .withRecordLabelFormat("%s")
            .withRecordLabelFields("name")
            .withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
            .withField(new QFieldMetaData("id", QFieldType.LONG)
                  .withBackendName("id").withIsEditable(false))
            .withField(new QFieldMetaData("name", QFieldType.STRING)
                  .withBackendName("name").withIsEditable(false))
            .withoutCapabilities(Capability.TABLE_INSERT, Capability.TABLE_UPDATE,
                  Capability.TABLE_DELETE, Capability.TABLE_EXPORT);
   }

   static MetaDataProducerInterface<QAppMetaData> domainApp()
   {
      return new MetaDataProducerInterface<>()
      {
         @Override
         public QAppMetaData produce(QInstance instance)
         {
            return new QAppMetaData().withName("domainApp").withLabel("Domain")
                  .withChild(instance.getTable("domainRecords"));
         }

         @Override
         public int getSortOrder()
         {
            return 600;
         }
      };
   }
}
```

Register both producers in the same factory called by the generated main and application test:

```java
public static NativeAgentApplication.Components components()
{
   return new NativeAgentApplication.Components()
   {
      @Override
      public java.util.List<MetaDataProducerInterface<?>> metadata()
      {
         return java.util.List.of(DomainMetadata.domainRecords(), DomainMetadata.domainApp());
      }
   };
}
```

Use `tools(NativeStores)` to construct domain tools after validated stores exist. For a tool
that needs identity, use `ToolBinding.forCaller(definition, (arguments, caller) -> ...)`.
`caller` is authenticated runtime identity, independent of model JSON. Approved writes retain
the requester's scope; the approver is recorded separately in audit. Explicitly map machine
principals to domain identities and reject unmapped callers. Native processes should derive
identity from verified QQQ context inside the shared business service. See the foundation's
executable `examples/native-domain` for both paths, owner locks and real PostgreSQL tests.

Grant only these physical columns through the private migration/bootstrap configuration:

```properties
qqq.reader.columns.domain_record=id,name
```

Run the packaged `migrate`/`grant-access` operation from [DATABASE-ROLES.md](DATABASE-ROLES.md)
after changing migrations or grants. The `agentOperations` backend uses the same database
as the runtime with its restricted reader identity; metadata itself does not grant SQL access.

This example deliberately requires a verified `OPERATOR` identity. Local Basic mode has
an anonymous internal QQQ session and therefore cannot satisfy this role. Use verified
operator mode to exercise named-user domain permissions; the generated local Operations
views remain accessible for the initial boot check. A multi-household or tenant table also
needs record-security locks and tests for its actual ownership relationships; a table role
alone does not provide row isolation.

## Complete a consumer's QQQ workflow

1. Model the business records and relationships in domain migrations, then expose appropriate
   table fields, sections, joins, possible values and navigation through explicit producers.
2. Keep business calculations and changes in deterministic services. QQQ processes and MCP
   tool handlers call those same services and recheck role, record ownership and allowed action.
   Domain writes use a separate narrowly scoped backend; never give the shared operations
   reader write authority or expose the private authentication backend.
3. Override `databaseFixture()` with an `ApplicationDatabaseFixture` for synthetic records and explicit reader
   grants. Extend the generated application tests to read those actual records through native
   QQQ, assert denied mutations and verify household/tenant isolation in operator mode.
4. For each configured domain MCP server, override `mcpFixtures()` with an `ApplicationMcpFixtures` replacement
   using its real tool catalog with controlled vendor clients. Verify the UI and tools return
   the same stored data and enforce the same business permission rules.
5. Run `mvn verify`, start the actual packaged consumer, sign in and open its domain navigation
   and record details. Record separately what automated HTTP/data tests and visual inspection
   established; shared synthetic conformance does not certify the new business domain.

Core operational tables remain read-only in generic CRUD. Approval denial uses the shared
governed process in operator mode; executing an approved action uses the core coordinator.
Neither editing a status column nor hiding an edit button is an authorization design.


## Bounded native file uploads

A consumer that needs uploads larger than the default host settings can override
`Components.uploadPolicy()` and return an explicit `Optional<NativeUploadPolicy>`. Leaving it
empty preserves the native host defaults. For two files up to 20 MiB each plus bounded scalar
form fields, a policy can be:

```java
@Override
public java.util.Optional<com.kof22.agentadmin.bootstrap.NativeUploadPolicy> uploadPolicy()
{
   return java.util.Optional.of(new com.kof22.agentadmin.bootstrap.NativeUploadPolicy(
      20L * 1024 * 1024, 40L * 1024 * 1024, 34, 2, 64 * 1024));
}
```

Arguments are maximum file bytes, total request bytes (including framing), total multipart
parts, file parts, and per-part memory threshold. Jetty enforces size and total part limits
while parsing; the host rejects excess file parts before QQQ storage. Scalar fields count
against total parts. The memory threshold spills larger parts to the servlet's temporary
storage; operators must protect and clean that storage as sensitive data. This policy does
not authorize uploads or select a storage backend. The consumer must provide a principal-bound
QQQ storage backend, reject unapproved fields/types, enforce its own bounded streams and
retention, and prove denied direct requests do not persist files. Test the actual QQQ process
upload path as well as domain import validation.

## Protected native downloads

Downloads remain denied by default, and filesystem `filePath` downloads are always denied.
An OIDC consumer can opt in through `Components.downloadPolicy()` returning
`new NativeDownloadPolicy(Map.of("protectedExportStorage", "exportProcess"))`.
Only the exact configured process's successful native response may issue an opaque UUID
`storageReference` paired with that table. The trusted process must call
`ProcessFileDownload.registerStorage` for the same reference and return `storageTableName`
and `storageReference` in its output values. Never register caller-supplied storage targets.

The host requires GET, same-origin browser context, exactly those two query parameters,
current process permission, and QQQ's application/session-bound storage capability. Output
capabilities expire after 30 minutes and are bounded; restart or a new authenticated session
requires generating the export again. Arbitrary tables, raw paths, unregistered references,
other process outputs and cross-session links cannot grant access. The storage backend must
recheck current domain record/source/field permissions and permission epoch on every read.
Protected download responses use `Cache-Control: no-store`; client code must not persist
protected bytes in an offline cache. Basic local administration cannot enable this policy.
