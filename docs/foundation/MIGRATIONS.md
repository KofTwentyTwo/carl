# Core and domain migrations

The foundation runs two Flyway lifecycles in the configured database/schema. Core SQL lives
under `classpath:db/agent-core` and retains the existing `flyway_schema_history` table. Consumer
SQL lives under `classpath:db/migration` and uses `agent_domain_schema_history`. Both directories
and history names are reserved contracts, exposed by `AgentMigrations` constants. A consumer
starts with `V1__description.sql`; its version numbers are independent of core versions.
Domain repeatable migrations and explicitly configured Flyway Java migrations belong only to
the domain lifecycle. Consumer configuration must not place resources in `db/agent-core`.

The offline `DatabaseBootstrap` CLI calls `AgentMigrations.migrate(DataSource)` before
runtime startup. Native startup validates both histories without migrating. The Flyway
overload preserves configured datasource, schemas, classloader and domain settings; core
migration resolution uses a separate strict configuration. Locations, history names, latest
target, validation and baseline behavior are foundation-owned. Migration credentials belong
to the migrator; the runtime and QQQ reader must neither own nor mutate history tables.

## Add a consumer migration

1. Put the first application migration at
   `src/main/resources/db/migration/V1__create_domain_tables.sql` in the consumer repository.
   Add later changes as `V2__...sql`, `V3__...sql`, and so on; never edit a script already
   applied to a retained database.
2. Define typed fields, primary/foreign keys, constraints and indexes alongside the QQQ
   metadata described in [QQQ-APPLICATION-CONTRACT.md](QQQ-APPLICATION-CONTRACT.md).
3. Add any columns exposed through the shared reader to the private bootstrap configuration
   as `qqq.reader.columns.<physical_table>=<column>,<column>`. Test rows and grants in an
   `ApplicationDatabaseFixture` are supplied by the consumer's `databaseFixture()` override.
4. Run `mvn verify` in the consumer. Its inherited application suite migrates disposable
   PostgreSQL with the application's real resources; add assertions for the actual domain
   schema and data.
5. Package the jar, back up a retained database, stop its runtime and run the packaged
   `DatabaseBootstrap migrate` operation as the migration role. It refreshes grants when
   `runtime.username` is present. Start with the restricted runtime identity. See [DATABASE-ROLES.md](DATABASE-ROLES.md) for exact commands.

QQQ metadata describes access to the schema; it does not replace a database migration.
Keep SQL migrations and metadata changes together in the same application release.

## Upgrade and legacy handling

Core validation and migration finish before domain migration begins. Applied core scripts must
still resolve with their original checksums; unknown future/missing scripts and a core baseline
are rejected. Only then may Flyway create the domain history at baseline `0` in the nonempty
core schema, allowing domain `V1` to run. Domain versioned migrations must be greater than `0`;
an existing domain baseline above zero is rejected. Neither lifecycle silently ignores an
applied future version. A higher domain version, such as `1000`, does not suppress core `8`.

Existing core-only databases keep their original history rows and checksums: moving the SQL
resource directory does not rewrite history, repair checksums or rerun applied migrations.
The V1–V7 SQL bytes are unchanged. A database that previously mixed domain migrations into
`flyway_schema_history`, used another core history table, or contains untracked tables needs a
deliberate migration plan. Startup fails rather than adopting that history. Back up the database,
inventory applied scripts and checksums, and qualify a reviewed history/data transition on an
isolated restore before using the new lifecycles. Do not enable broad ignore patterns, baseline
the core schema, or run `repair` to make unknown history disappear.

Core and domain phases are separate native Flyway operations. Successful core migrations remain
committed if a later domain migration fails; fix the domain failure and rerun the helper. A
database commit already in progress cannot be cancelled by application intent. Binary rollback
after a schema upgrade requires an explicitly qualified compatible binary/schema pair or a
restore of the matching pre-upgrade backup; the helper does not invent down migrations.

Verification includes controlled H2 startup and real PostgreSQL checks for overlapping `V1`
versions, domain `1000` followed by core `8`, unchanged existing core history/data, and refusal
to adopt mixed legacy history. These fixtures establish the shared migration mechanism;
consumers must test their actual domain migrations and restore/rollback plan.
