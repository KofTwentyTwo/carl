# Database provisioning and recovery

The packaged foundation supplies the database-role lifecycle. Run these commands offline;
they do not start the agent, contact a model or open an application listener. Production uses
one dedicated PostgreSQL database and its `public` schema. Core and domain migrations are
coordinated as described in [MIGRATIONS.md](MIGRATIONS.md).

| Identity | Authority |
| --- | --- |
| Bootstrap administrator | Creates fresh roles and restricts access to the dedicated database; rotates credentials explicitly |
| Migration role | Owns and migrates application tables; reapplies runtime and reader grants |
| Runtime role | Selects/inserts/updates/deletes application records and uses generated sequences; reads migration history |
| QQQ reader | Selects only explicitly declared operational and domain columns |

The three application roles cannot create roles or databases, replicate, bypass row security,
inherit privileges or belong to other roles. The running application must have neither
bootstrap nor migration credentials. Runtime access does not include schema ownership,
DDL, temporary tables, `TRUNCATE`, delegation or migration-history writes. Backend QQQ
authorization still decides which rows and operations each signed-in user may access.

## Fresh production database

This same separate-role setup can be used during local development. Create an empty
dedicated database using PostgreSQL's administrative tooling, then create a private file
outside the repository and restrict it to its operator (`chmod 600`). The foundation checkout
also supplies `config/database-bootstrap.properties.example`; generated apps can use this
complete example without copying scripts or bootstrap code:

```properties
db.url=jdbc:postgresql://127.0.0.1:5432/agent
db.username=agent_bootstrap
db.password=YOUR_BOOTSTRAP_ADMINISTRATOR_PASSWORD
migration.username=agent_migrator
migration.password=YOUR_MIGRATION_PASSWORD
runtime.username=agent_runtime
runtime.password=YOUR_RUNTIME_PASSWORD
qqq.reader.username=agent_reader
qqq.reader.password=YOUR_READER_PASSWORD
```

`db.*` identifies the existing bootstrap administrator. The three other usernames are new
application roles. Replace password placeholders privately; use Java-properties escaping,
not shell escaping. Keep this file separate from agent configuration and runtime containers.

From the generated application directory, define a shell helper for the packaged entry point.
Set `AGENT_JAR` to the distribution JAR you just built; keep its adjacent `lib/` intact:

```sh
AGENT_JAR=target/agent/app.jar
bootstrap_database() {
  java -Dqqq.logger.logSessionId.disabled=true \
    -cp "$AGENT_JAR" com.kof22.agentadmin.bootstrap.DatabaseBootstrap "$@"
}
bootstrap_database provision /private/path/bootstrap.properties
```

The foundation checkout has an equivalent wrapper,
`bash scripts/bootstrap-database.sh <mode> <jar> <properties-file>`; that script is not copied
into generated consumers. The Java entry point works anywhere the packaged jar is available.

`provision` refuses existing role names or application objects. It creates distinct roles,
revokes PUBLIC database connection/temporary-table and schema-creation privileges, applies
the packaged migrations as the migration role, and grants writer/reader access. Passwords
are converted to PostgreSQL SCRAM verifiers before role SQL is constructed. They never
become command-line arguments or application log messages.

Role creation and initial privileges form one database transaction. Migrations run after
that transaction commits. If later migration or grant setup fails, inspect the database and
roles; do not rerun `provision` to adopt them. Correct the issue and use the migration role
with `migrate`/`grant-access`. No command automatically drops existing objects or repairs
Flyway history.

At runtime set `KOF22_AGENT_DB_URL`, `KOF22_AGENT_DB_USERNAME=agent_runtime`,
`KOF22_AGENT_DB_PASSWORD`, `KOF22_AGENT_QQQ_DB_USERNAME=agent_reader` and
`KOF22_AGENT_QQQ_DB_PASSWORD` in the private environment. Native startup validates the
already migrated schema and role grants without DDL. Use
`KOF22_AGENT_DEPLOYMENT_MODE=PRODUCTION` only with the production authentication,
listener, policy and integration requirements configured.

## Command reference

| Mode | Connect as | Purpose |
| --- | --- | --- |
| `provision` | Bootstrap administrator | Create fresh roles, migrate the empty database and apply grants. |
| `initialize` | Local schema owner with role-creation authority | Migrate and create only the restricted QQQ reader; simpler local setup. |
| `migrate` | Migration role | Apply core then domain migrations; refresh grants when `runtime.username` is present. |
| `grant-access` | Migration role | Reapply runtime and explicit QQQ-reader permissions without migrating. |
| `prepare-restore` | Bootstrap administrator | Create fresh roles and schema access while leaving tables absent. |
| `rotate-credentials` | Bootstrap administrator | Change the three application passwords together. |

## Migrations and explicit domain access

For ongoing maintenance, use a private configuration whose `db.username`/`db.password`
identify the migration role. Retain `migration.username`, `runtime.username` and
`qqq.reader.username`. Additional domain columns use validated physical table/column names:

```properties
qqq.reader.columns.accounts=id,name,balance
```

These declarations must match the native QQQ metadata and application permission tests.
They cannot override foundation tables or expose migration history. Unlisted columns remain
inaccessible to the QQQ connection. Grants are explicitly reapplied after each migration;
there is no broad default grant that makes future private columns readable.

```sh
bootstrap_database migrate /private/path/migration.properties
bootstrap_database grant-access /private/path/migration.properties
```

`migrate` applies core before domain migrations and refreshes grants when `runtime.username`
is present. `grant-access` changes permissions only. It refuses unexpected public-object
ownership or roles with unsafe capabilities/memberships, then resets table, column, sequence
and function grants before applying the declared contract. Trusted domain migrations should
not rely on PUBLIC execute privileges on application functions.

## Restore and credential rotation

For a new isolated restore target, `prepare-restore` creates only the fresh roles and
database/schema grants. It leaves the schema empty for the foundation checkout's
`scripts/database-restore.sh` (not copied into generated projects).
Restore with the migration identity so restored objects have the correct owner, then run
`grant-access` using the matching artifact/configuration. Boot the matching application and
run its permission/data checks before switching traffic. Do not restore a backup over the
tables created by `provision`.

To rotate credentials, stop admission and shut down the runtime, supply the new three
passwords and the separate bootstrap administrator in a private file, then run:

```sh
bootstrap_database rotate-credentials /private/path/rotation.properties
```

All three password changes commit together. PostgreSQL password changes affect new
connections; this command does not terminate an already connected process. Update the
separately managed runtime/QQQ secrets and restart the application before verifying new
connections. The command neither rewrites deployment secrets nor changes role ownership.

`initialize` remains available for existing local development setups that use one schema
owner plus a restricted QQQ reader. Production requires the separate runtime role and the
production startup checks.
