/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.DatabaseBootstrap;
import com.kof22.agentcore.deployment.RuntimeDatabaseRole;
import com.kof22.agentcore.store.AgentLiquibase;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.TableExports;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disposable actual Carl PostgreSQL migration gate; never accepts an external database URL. */
public final class CarlLiquibaseQualification
{
   private static final String EXPORT_SQL = "V62__protected_table_exports.sql";
   private static final UUID EXPORT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
   private static final String EXPORT_READER_COLUMNS = "id,principal,title,source_table,selection_scope,as_of,row_count,byte_count,content_hash,text_policy,household_changed";
   private static final String DOCUMENT_SQL = "V61__household_documents.sql";
   private static final String ORIGINAL = "Synthetic retained original. Untrusted historical evidence.";
   private static final String AT = "2026-10-01T00:00:00Z";
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String HEADER = "<?xml version='1.0'?><databaseChangeLog xmlns='http://www.liquibase.org/xml/ns/dbchangelog' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:schemaLocation='http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.33.xsd'>";

   private CarlLiquibaseQualification() {}

   /** Qualifies real disposable PostgreSQL, canonical domain transitions, roles and explicit recovery. */
   public static void main(String[] args) throws Exception
   {
      if(args[0].equals("migrate-native"))
      {
         var properties = new Properties();
         try(var reader = Files.newBufferedReader(Path.of(args[1]))) { properties.load(reader); }
         try(var loader = new QualificationLoader(Path.of(args[2]), Thread.currentThread().getContextClassLoader()))
         {
            AgentLiquibase.migrate(source(properties, "db"), loader);
         }
         return;
      }
      Path report = Path.of(args[0]);
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         ClassLoader packaged = Thread.currentThread().getContextClassLoader();
         var domainSql = sqlHashes(packaged);
         boolean packagedMatch = verifyPackaged(domainSql);
         int latestVersion = domainSql.keySet().stream().mapToInt(CarlLiquibaseQualification::version).max().orElseThrow();
         int beforeDocuments = domainSql.keySet().stream().mapToInt(CarlLiquibaseQualification::version).filter(version -> version < version(DOCUMENT_SQL)).max().orElseThrow();
         int expectedHistory = domainSql.size() + 8;
         pendingDocuments(database, beforeDocuments, expectedHistory);
         pendingExports(database, version(DOCUMENT_SQL), expectedHistory);
         var retained = provision(database, "carl_retained", "flyway");
         var retainedSource = source(retained, "db");
         seed(retainedSource);
         seedDocument(retainedSource);
         seedExport(retainedSource);
         assertExport(retainedSource);
         assertDocument(retainedSource);
         Map<String, String> dataBefore = records(retainedSource);
         Map<String, String> legacyBefore = histories(retainedSource);
         execute(admin(database), "CREATE DATABASE carl_retained_clone TEMPLATE carl_retained OWNER carl_retained_migrator");
         // PostgreSQL clones table grants but creates fresh database ACLs. Restore isolated ACLs explicitly.
         execute(admin(database), "REVOKE ALL ON DATABASE carl_retained_clone FROM PUBLIC");
         execute(admin(database), "GRANT CONNECT ON DATABASE carl_retained_clone TO carl_retained_runtime,carl_retained_reader");
         var clone = copy(retained);
         clone.setProperty("db.url", url(database, "carl_retained_clone"));
         clone.setProperty("migration.engine", "liquibase");
         run("migrate", clone);
         var cloneSource = source(clone, "db");
         assertEquals(dataBefore, records(cloneSource), "Retained Monarch/privacy records must be unchanged");
         assertEquals(legacyBefore, histories(cloneSource), "Independent Flyway histories must stay byte-identical");
         assertEquals(expectedHistory, number(cloneSource, "SELECT count(*) FROM agent_databasechangelog"));
         assertEquals("MARK_RAN", rows(cloneSource, "SELECT exectype FROM agent_databasechangelog WHERE filename='db/migration/" + DOCUMENT_SQL + "'").trim(), "Already validated document SQL must be adopted without reexecution");
         assertEquals("MARK_RAN", rows(cloneSource, "SELECT exectype FROM agent_databasechangelog WHERE filename='db/migration/" + EXPORT_SQL + "'").trim(), "Already validated export SQL must be adopted without reexecution");
         assertEquals(domainSql.size(), number(cloneSource, "SELECT count(*) FROM agent_databasechangelog WHERE filename LIKE 'db/migration/%'"));
         Map<String, String> completeBefore = records(cloneSource);
         String engineBefore = rows(cloneSource, "SELECT row_to_json(t)::text FROM agent_databasechangelog t ORDER BY orderexecuted");
         run("migrate", clone);
         run("migrate", clone);
         assertEquals(completeBefore, records(cloneSource));
         assertEquals(engineBefore, rows(cloneSource, "SELECT row_to_json(t)::text FROM agent_databasechangelog t ORDER BY orderexecuted"));

         Properties fresh;
         try { fresh = provision(database, "carl_fresh", "liquibase"); }
         catch(IllegalStateException failure)
         {
            // Public SQL errors from this owned disposable server; never inspect a private/live server.
            database.getLogs().lines().filter(line -> line.contains("ERROR:")).forEach(System.err::println);
            System.err.println("Last applied public SQL: " + rows(source(url(database, "carl_fresh"), database.getUsername(), database.getPassword()),
               "SELECT filename FROM agent_databasechangelog ORDER BY orderexecuted DESC LIMIT 1").trim());
            throw failure;
         }
         var freshSource = source(fresh, "db");
         seed(freshSource);
         seedDocument(freshSource);
         seedExport(freshSource);
         assertExport(freshSource);
         assertExport(cloneSource);
         assertDocument(freshSource);
         assertDocument(cloneSource);
         assertEquals(schema(cloneSource), schema(freshSource), "Actual canonical fresh and retained domain schemas match");
         assertEquals(dataBefore, records(freshSource), "The same Monarch rows, document originals and privacy state persist on both paths");
         assertEquals(expectedHistory, number(freshSource, "SELECT count(*) FROM agent_databasechangelog"));
         assertEquals(0, number(freshSource, "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('flyway_schema_history','agent_domain_schema_history')"));
         triggerBehavior(cloneSource);
         triggerBehavior(freshSource);
         System.out.println("CARL_LIQUIBASE_STAGE=fresh-retained-repeat");

         checksumRejection(clone, cloneSource, dataBefore);
         permissions(clone);
         try { permissions(fresh); }
         catch(IllegalStateException failure)
         {
            database.getLogs().lines().filter(line -> line.contains("ERROR:")).forEach(System.err::println);
            throw failure;
         }
         try(var connection = source(clone, "runtime").getConnection(); var statement = connection.createStatement())
         {
            try(var rows = statement.executeQuery("SELECT has_database_privilege(current_user,'carl_fresh','CONNECT')"))
            {
               rows.next();
               assertFalse(rows.getBoolean(1), "Other environment roles cannot connect to this database");
            }
         }
         System.out.println("CARL_LIQUIBASE_STAGE=checksums-role-isolation");

         Path overrides = Files.createTempDirectory(report, "qualification-only-changelog-");
         Path nativeLog = overrides.resolve(AgentLiquibase.DOMAIN_CHANGELOG);
         Files.createDirectories(nativeLog.getParent());
         String upgrade = "<changeSet id='ci-new-carl-ddl-data' author='carl-ci'><preConditions onFail='HALT'><tableExists tableName='carl_account'/><not><columnExists tableName='carl_account' columnName='migration_probe'/></not></preConditions><sql>ALTER TABLE carl_account ADD COLUMN migration_probe NUMERIC(20,4); UPDATE carl_account SET migration_probe=12.34;</sql></changeSet>";
         Files.writeString(nativeLog, HEADER + upgrade + "</databaseChangeLog>");
         try(var loader = new QualificationLoader(overrides, packaged))
         {
            AgentLiquibase.migrate(cloneSource, loader);
            AgentLiquibase.migrate(freshSource, loader);
            AgentLiquibase.migrate(cloneSource, loader);
            assertEquals(schema(cloneSource), schema(freshSource));
            assertEquals(records(cloneSource), records(freshSource));
            assertEquals(new BigDecimal("12.3400"), decimal(cloneSource, "SELECT migration_probe FROM carl_account"));
            assertEquals(expectedHistory + 1, number(cloneSource, "SELECT count(*) FROM agent_databasechangelog"));
            interrupted(database, clone, cloneSource, nativeLog, upgrade, expectedHistory);
         }
         System.out.println("CARL_LIQUIBASE_STAGE=actual-interruption-explicit-recovery");
         var result = new LinkedHashMap<String, Object>();
         result.put("status", "PASS");
         for(String gate : List.of("fresh", "retained", "repeat", "checksumRejection", "permissionIsolation", "interruptionRetry")) result.put(gate, true);
         result.put("data", "DISPOSABLE_SYNTHETIC_ONLY");
         result.put("consumerDomainMigrationCount", domainSql.size());
         result.put("latestConsumerDomainVersion", latestVersion);
         result.put("preDocumentDomainVersion", beforeDocuments);
         result.put("pendingDocumentMigration", true);
         result.put("retainedDocumentOriginalAndMetadata", true);
         result.put("documentSqlSha256", domainSql.get(DOCUMENT_SQL));
         result.put("exportSqlSha256", domainSql.get(EXPORT_SQL));
         result.put("pendingDocumentAndExportMigrations", true);
         result.put("documentAppliedExportPendingTransition", true);
         result.put("retainedCanonicalCsvAndHistory", true);
         result.put("exportRawReaderDenied", true);
         result.put("coreVersions", 8);
         result.put("retainedClone", true);
         result.put("sourceSqlSha256", domainSql);
         result.put("packagedDistributionMatched", packagedMatch);
         result.put("foundationCoreSha256", hash(Files.readAllBytes(Path.of(AgentLiquibase.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
         result.put("retainedDataSha256", hash(JSON.writeValueAsBytes(dataBefore)));
         result.put("manualLockRecovery", "Worker finished and zero database sessions verified before explicit operator reset; no automatic unlock");
         result.put("qualificationOnlyChangesets", "New DDL/data and interrupted observation execute only in disposable test changelog overrides; not production schema");
         result.put("deployment", "NOT_QUALIFIED");
         Files.writeString(report.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
      }
   }

   private static Properties provision(PostgreSQLContainer<?> database, String name, String engine) throws Exception
   {
      return provision(database, name, engine, null);
   }

   private static Properties provision(PostgreSQLContainer<?> database, String name, String engine, Integer target) throws Exception
   {
      execute(admin(database), "CREATE DATABASE " + name);
      Properties properties = new Properties();
      properties.setProperty("db.url", url(database, name));
      properties.setProperty("db.username", database.getUsername());
      properties.setProperty("db.password", database.getPassword());
      properties.setProperty("migration.engine", engine);
      for(String role : List.of("migration", "runtime", "qqq.reader"))
      {
         properties.setProperty(role + ".username", name + "_" + (role.equals("migration") ? "migrator" : role.equals("runtime") ? "runtime" : "reader"));
         properties.setProperty(role + ".password", "synthetic-qualification-" + role);
      }
      properties.setProperty("qqq.reader.columns.carl_account_view", "id,principal,title,kind,currency,review_state");
      if(target == null)
      {
         properties.setProperty("qqq.reader.columns.carl_document_view", "id,principal,title,source_identity,source_type,document_date,as_of_date,state,content_hash,byte_count,extraction_status");
         properties.setProperty("qqq.reader.columns.carl_table_export_view", EXPORT_READER_COLUMNS);
         run("provision", properties);
      }
      else
      {
         run("prepare-restore", properties);
      }
      properties.setProperty("db.username", properties.getProperty("migration.username"));
      properties.setProperty("db.password", properties.getProperty("migration.password"));
      if(target != null)
      {
         var source = source(properties, "db");
         // Test-only explicit target preserves the two canonical SQL histories without runtime DDL.
         Flyway.configure().dataSource(source).locations(AgentMigrations.CORE_LOCATION).table(AgentMigrations.CORE_HISTORY)
            .createSchemas(false).validateMigrationNaming(true).cleanDisabled(true).load().migrate();
         Flyway.configure().dataSource(source).locations(AgentMigrations.DOMAIN_LOCATION).table(AgentMigrations.DOMAIN_HISTORY)
            .baselineOnMigrate(true).baselineVersion("0").target(target.toString())
            .createSchemas(false).validateMigrationNaming(true).cleanDisabled(true).load().migrate();
         assertEquals(target.intValue(), number(source, "SELECT max(version::integer) FROM agent_domain_schema_history"));
         run("grant-access", properties);
      }
      return properties;
   }

   private static void pendingDocuments(PostgreSQLContainer<?> database, int beforeDocuments, int expectedHistory) throws Exception
   {
      var properties = provision(database, "carl_pre_documents", "flyway", beforeDocuments);
      var source = source(properties, "db");
      seed(source);
      assertEquals("f", rows(source, "SELECT to_regclass('carl_document') IS NOT NULL").trim());
      var retained = records(source);
      var flyway = histories(source);
      properties.setProperty("migration.engine", "liquibase");
      run("migrate", properties);
      assertEquals("t", rows(source, "SELECT to_regclass('carl_document') IS NOT NULL").trim(), "Canonical pending document SQL must actually create its protected schema");
      assertEquals("t", rows(source, "SELECT to_regclass('carl_table_export') IS NOT NULL").trim(), "Canonical pending V62 SQL must actually create its protected CSV schema");
      properties.setProperty("qqq.reader.columns.carl_document_view", "id,principal,title,source_identity,source_type,document_date,as_of_date,state,content_hash,byte_count,extraction_status");
      properties.setProperty("qqq.reader.columns.carl_table_export_view", EXPORT_READER_COLUMNS);
      run("grant-access", properties);
      var after = records(source);
      for(var entry : retained.entrySet()) assertEquals(entry.getValue(), after.get(entry.getKey()), "Retained pre-document table " + entry.getKey());
      assertEquals(flyway, histories(source), "Pending SQL must preserve both independent Flyway histories");
      assertEquals(expectedHistory, number(source, "SELECT count(*) FROM agent_databasechangelog"));
      assertEquals("EXECUTED", rows(source, "SELECT exectype FROM agent_databasechangelog WHERE filename='db/migration/" + DOCUMENT_SQL + "'").trim());
      assertEquals(1, number(source, "SELECT count(*) FROM agent_databasechangelog WHERE filename='db/migration/" + DOCUMENT_SQL + "'"));
      assertEquals("EXECUTED", rows(source, "SELECT exectype FROM agent_databasechangelog WHERE filename='db/migration/" + EXPORT_SQL + "'").trim());
      seedDocument(source);
      seedExport(source);
      assertExport(source);
      var complete = records(source);
      String applied = rows(source, "SELECT row_to_json(t)::text FROM agent_databasechangelog t ORDER BY orderexecuted");
      run("migrate", properties);
      assertEquals(complete, records(source));
      assertEquals(applied, rows(source, "SELECT row_to_json(t)::text FROM agent_databasechangelog t ORDER BY orderexecuted"));
      assertEquals(flyway, histories(source));
      assertDocument(source);
      permissions(properties);
      System.out.println("CARL_LIQUIBASE_STAGE=pre-document-pending-original-retained");
   }

   private static void pendingExports(PostgreSQLContainer<?> database, int beforeExports, int expectedHistory) throws Exception
   {
      var properties=provision(database,"carl_pre_exports","flyway",beforeExports);
      var source=source(properties,"db");
      seed(source);
      seedDocument(source);
      assertDocument(source);
      assertEquals("f",rows(source,"SELECT to_regclass('carl_table_export') IS NOT NULL").trim());
      var retained=records(source);
      var flyway=histories(source);
      properties.setProperty("migration.engine","liquibase");
      run("migrate",properties);
      assertEquals("t",rows(source,"SELECT to_regclass('carl_table_export') IS NOT NULL").trim());
      assertEquals("MARK_RAN",rows(source,"SELECT exectype FROM agent_databasechangelog WHERE filename='db/migration/"+DOCUMENT_SQL+"'").trim());
      assertEquals("EXECUTED",rows(source,"SELECT exectype FROM agent_databasechangelog WHERE filename='db/migration/"+EXPORT_SQL+"'").trim());
      assertEquals(expectedHistory,number(source,"SELECT count(*) FROM agent_databasechangelog"));
      for(var entry:retained.entrySet()) assertEquals(entry.getValue(),records(source).get(entry.getKey()),"Retained document-era table "+entry.getKey());
      assertEquals(flyway,histories(source));
      seedExport(source);
      assertExport(source);
      assertDocument(source);
      properties.setProperty("qqq.reader.columns.carl_document_view","id,principal,title,source_identity,source_type,document_date,as_of_date,state,content_hash,byte_count,extraction_status");
      properties.setProperty("qqq.reader.columns.carl_table_export_view",EXPORT_READER_COLUMNS);
      run("grant-access",properties);
      var complete=records(source);
      String applied=rows(source,"SELECT row_to_json(t)::text FROM agent_databasechangelog t ORDER BY orderexecuted");
      run("migrate",properties);
      assertEquals(complete,records(source));
      assertEquals(applied,rows(source,"SELECT row_to_json(t)::text FROM agent_databasechangelog t ORDER BY orderexecuted"));
      assertEquals(flyway,histories(source));
      permissions(properties);
      System.out.println("CARL_LIQUIBASE_STAGE=document-applied-export-pending-retained");
   }

   private static TableExports exports(DataSource source)
   {
      var clock=Clock.fixed(Instant.parse(AT),ZoneOffset.UTC);
      return new TableExports(new CarlService(source,clock),Map.of("carlDocuments",List.of(new TableExports.Column("id",false),new TableExports.Column("title",true),new TableExports.Column("state",true),new TableExports.Column("document_date",false),new TableExports.Column("as_of_date",false))),clock);
   }

   private static void seedExport(DataSource source) throws Exception
   {
      // Canonical production generator supplies all CSV/digest/selection fields; only fixture creation time is fixed. //
      exports(source).generate("migration-owner",EXPORT_ID,"carlDocuments",TableExports.Scope.SELECTED,List.of("102"));
      var fixture=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(rows(source,"SELECT row_to_json(e)::text FROM carl_table_export e WHERE id='"+EXPORT_ID+"'").trim());
      fixture.put("created_at",AT);
      try(var connection=source.getConnection();var remove=connection.prepareStatement("DELETE FROM carl_table_export WHERE id=?");var insert=connection.prepareStatement("INSERT INTO carl_table_export SELECT * FROM json_populate_record(NULL::carl_table_export,?::json)"))
      {
         connection.setAutoCommit(false);
         remove.setObject(1,EXPORT_ID);assertEquals(1,remove.executeUpdate());
         insert.setString(1,JSON.writeValueAsString(fixture));assertEquals(1,insert.executeUpdate());
         connection.commit();
      }
   }

   private static void assertExport(DataSource source) throws Exception
   {
      byte[] bytes=exports(source).load("migration-owner",EXPORT_ID);
      assertEquals(hash(bytes),rows(source,"SELECT content_hash FROM carl_table_export WHERE id='"+EXPORT_ID+"'").trim());
      String csv=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(csv.contains("EXPORT_METADATA,"+AT+",carlDocuments,SELECTED,"+TableExports.TEXT_POLICY));
      assertTrue(csv.contains("102,Synthetic retained household document,SUPPLIED_UNVERIFIED,,2020-01-02"));
      assertFalse(csv.contains("principal"));
      assertThrows(SecurityException.class,()->exports(source).load("migration-denied",EXPORT_ID));
      assertEquals("carlDocuments:SELECTED:1:"+TableExports.TEXT_POLICY,rows(source,"SELECT source_table||':'||selection_scope||':'||row_count||':'||text_policy FROM carl_table_export_view WHERE principal='migration-owner' AND id='"+EXPORT_ID+"'").trim());
      assertEquals(0,number(source,"SELECT count(*) FROM carl_table_export_view WHERE principal='migration-denied'"));
      try(var connection=source.getConnection();var statement=connection.createStatement())
      {
         assertThrows(SQLException.class,()->statement.execute("UPDATE carl_table_export SET content='forged'::bytea"));
      }
   }

   private static void seedDocument(DataSource source) throws Exception
   {
      String contents = HexFormat.of().formatHex(ORIGINAL.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String digest = hash(ORIGINAL.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try(var connection = source.getConnection(); var statement = connection.createStatement())
      {
         connection.setAutoCommit(false);
         for(String sql : List.of(
            "INSERT INTO carl_record(id,household_id,owner_id,domain,visibility,title,evidence,created_at) VALUES(102,1,1,'FINANCE','PRIVATE','Synthetic retained household document','Synthetic supplied provenance','" + AT + "')",
            "INSERT INTO carl_upload(reference,member_id,contents,created_at) VALUES('synthetic-document-upload',1,decode('" + contents + "','hex'),'" + AT + "')",
            "INSERT INTO carl_document_review(id,member_id,permission_revision,upload_reference,input_digest,source_digest,original_name,content_hash,title,visibility,source_identity,source_type,document_date,as_of_date,provenance,extraction_status,extracted_text,created_at) SELECT '00000000-0000-0000-0000-000000000003',1,permission_revision,'synthetic-document-upload','" + digest + "','" + digest + "','synthetic-retained.txt','" + digest + "','Synthetic retained household document','PRIVATE','synthetic://supplied-retained-note','HISTORICAL_NOTE',NULL,'2020-01-02','Synthetic supplied provenance','TEXT_EXTRACTED','" + ORIGINAL + "','" + AT + "' FROM carl_household WHERE id=1",
            "INSERT INTO carl_document(record_id,source_identity,source_type,document_date,as_of_date,source_digest,original_name,media_type,content_hash,original_content,extraction_status,extracted_text,review_id) VALUES(102,'synthetic://supplied-retained-note','HISTORICAL_NOTE',NULL,'2020-01-02','" + digest + "','synthetic-retained.txt','text/plain','" + digest + "',decode('" + contents + "','hex'),'TEXT_EXTRACTED','" + ORIGINAL + "','00000000-0000-0000-0000-000000000003')",
            "INSERT INTO carl_document_download(id,document_id,requester_id,permission_revision) SELECT '00000000-0000-0000-0000-000000000004',102,1,permission_revision FROM carl_household WHERE id=1")) statement.execute(sql);
         connection.commit();
      }
   }

   private static void assertDocument(DataSource source) throws Exception
   {
      assertEquals(HexFormat.of().formatHex(ORIGINAL.getBytes(java.nio.charset.StandardCharsets.UTF_8)), rows(source, "SELECT encode(original_content,'hex') FROM carl_document WHERE record_id=102").trim());
      assertEquals(hash(ORIGINAL.getBytes(java.nio.charset.StandardCharsets.UTF_8)), rows(source, "SELECT content_hash FROM carl_document WHERE record_id=102").trim());
      assertEquals("synthetic://supplied-retained-note:HISTORICAL_NOTE:UNKNOWN:2020-01-02:SUPPLIED_UNVERIFIED:synthetic-retained.txt:text/plain", rows(source, "SELECT source_identity||':'||source_type||':'||coalesce(document_date::text,'UNKNOWN')||':'||as_of_date::text||':'||state||':'||original_name||':'||media_type FROM carl_document_view WHERE principal='migration-owner' AND id=102").trim());
      assertEquals(0, number(source, "SELECT count(*) FROM carl_document_view WHERE principal='migration-denied'"));
      try(var connection = source.getConnection(); var statement = connection.createStatement())
      {
         assertThrows(SQLException.class, () -> statement.execute("UPDATE carl_document SET extracted_text='forged' WHERE record_id=102"));
         assertThrows(SQLException.class, () -> statement.execute("UPDATE carl_document_review SET source_identity='forged' WHERE id='00000000-0000-0000-0000-000000000003'"));
      }
   }

   private static void seed(DataSource source) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.createStatement())
      {
         connection.setAutoCommit(false);
         for(String sql : List.of(
            "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic Migration Household','America/Chicago')",
            "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'migration-owner','Owner',true),(2,1,'migration-denied','Denied',false)",
            "INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',false)",
            "INSERT INTO carl_record(id,household_id,owner_id,domain,visibility,title,evidence,created_at) VALUES(100,1,1,'FINANCE','PRIVATE','Fictional unreviewed source','Synthetic migration evidence','" + AT + "'),(101,1,1,'FINANCE','PRIVATE','Fictional source transaction','Synthetic migration evidence','" + AT + "')",
            "INSERT INTO carl_account(record_id,kind,institution,currency,liquid,ownership_share,review_state) VALUES(100,'UNCLASSIFIED','Fictional institution','USD',null,null,'NEEDS_REVIEW')",
            "INSERT INTO carl_monarch_mapping(member_id,source_label,account_id,created_at) VALUES(1,'Fictional unreviewed source',100,'" + AT + "')",
            "INSERT INTO carl_request(id,member_id,kind,digest,status,created_at) VALUES('00000000-0000-0000-0000-000000000001',1,'MONARCH_IMPORT','" + "a".repeat(64) + "','COMPLETE','" + AT + "')",
            "INSERT INTO carl_import(id,household_id,member_id,source_name,content_digest,request_id,row_count,created_at) VALUES(1,1,1,'Synthetic Monarch export','" + "b".repeat(64) + "','00000000-0000-0000-0000-000000000001',1,'" + AT + "')",
            "INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,source_id,classification,category,import_id) VALUES(101,100,'2026-10-01',-123.4500,'USD','synthetic-monarch-row','UNCLASSIFIED','Unknown',1)",
            "INSERT INTO carl_transaction_source(household_id,external_id,transaction_id,version,payload_digest,import_id,logical_row,account_label,merchant,category,original_statement,notes,tags,owner_label,reviewed,amount,effective_date,created_at) VALUES(1,'synthetic-monarch-row',101,1,'" + "c".repeat(64) + "',1,2,'Fictional unreviewed source','Fictional merchant','Unknown','Fictional statement','Synthetic private note','', 'Unknown','false',-123.4500,'2026-10-01','" + AT + "')",
            "INSERT INTO carl_balance(id,account_id,as_of,amount,basis,evidence) VALUES(1,100,'2026-10-01',-456.7800,'STATEMENT','Synthetic migration observation')",
            "INSERT INTO carl_balance_source(account_id,as_of,amount,import_id,logical_row,account_label,created_at) VALUES(100,'2026-10-01',-456.7800,1,2,'Fictional unreviewed source','" + AT + "')",
            "INSERT INTO carl_upload(reference,member_id,contents,created_at) VALUES('synthetic-upload',1,decode('736f75726365','hex'),'" + AT + "')",
            "INSERT INTO carl_import_review(id,member_id,transaction_reference,status,result,created_at) VALUES('00000000-0000-0000-0000-000000000002',1,'synthetic-upload','COMPLETE','Synthetic retained receipt','" + AT + "')",
            "INSERT INTO transcript_entry(session_key,seq,role,content,created_at) VALUES('synthetic-retained-conversation',1,'user','Synthetic protected conversation','" + AT + "')")) statement.execute(sql);
         statement.execute("SELECT setval(pg_get_serial_sequence('carl_balance','id'),1,true)");
         connection.commit();
      }
   }

   private static void triggerBehavior(DataSource source) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.createStatement())
      {
         connection.setAutoCommit(false);
         int before = number(source, "SELECT permission_revision FROM carl_household WHERE id=1");
         statement.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(3,1,'migration-trigger-probe','Probe',false)");
         statement.execute("UPDATE carl_member SET label='Updated probe' WHERE id=3");
         statement.execute("DELETE FROM carl_member WHERE id=3");
         try(var rows = statement.executeQuery("SELECT permission_revision FROM carl_household WHERE id=1"))
         {
            rows.next();
            assertEquals(before + 3, rows.getInt(1), "Actual Carl PL/pgSQL trigger must handle insert, update and deletion");
         }
         connection.rollback();
         assertEquals(before, number(source, "SELECT permission_revision FROM carl_household WHERE id=1"));
      }
   }

   private static void checksumRejection(Properties properties, DataSource source, Map<String, String> records) throws Exception
   {
      int latest = number(source, "SELECT max(version::integer) FROM agent_domain_schema_history");
      int checksum = number(source, "SELECT checksum FROM agent_domain_schema_history WHERE version='" + latest + "'");
      execute(source, "UPDATE agent_domain_schema_history SET checksum=0 WHERE version='" + latest + "'");
      assertThrows(IllegalStateException.class, () -> run("migrate", properties));
      assertEquals(records, records(source));
      execute(source, "UPDATE agent_domain_schema_history SET checksum=" + checksum + " WHERE version='" + latest + "'");
      String checksumValue = rows(source, "SELECT md5sum FROM agent_databasechangelog WHERE filename LIKE '%V" + latest + "__%'");
      execute(source, "UPDATE agent_databasechangelog SET md5sum='9:00000000000000000000000000000000' WHERE filename LIKE '%V" + latest + "__%'");
      assertThrows(IllegalStateException.class, () -> run("migrate", properties));
      assertEquals(records, records(source));
      try(var connection = source.getConnection(); var statement = connection.prepareStatement("UPDATE agent_databasechangelog SET md5sum=? WHERE filename LIKE '%V" + latest + "__%'"))
      {
         statement.setString(1, checksumValue.trim());
         statement.executeUpdate();
      }
      run("migrate", properties);
   }

   private static void permissions(Properties properties) throws Exception
   {
      DataSource runtime = source(properties, "runtime");
      try(var connection = runtime.getConnection(); var statement = connection.createStatement())
      {
         statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name='agent_databasechangelog'").close();
      }
      assertTrue(AgentLiquibase.installed(runtime), "Runtime must recognize the installed migration engine");
      AgentMigrations.validate(runtime);
      try(var connection = runtime.getConnection(); var statement = connection.createStatement())
      {
         RuntimeDatabaseRole.verify(connection);
         assertEquals(1, statement.executeUpdate("UPDATE carl_transaction_source SET notes=notes WHERE external_id='synthetic-monarch-row'"));
         for(String sql : List.of("CREATE TABLE forbidden(id INTEGER)", "CREATE TEMP TABLE forbidden(id INTEGER)",
            "CREATE ROLE forbidden", "DELETE FROM agent_databasechangelog", "UPDATE agent_databasechangeloglock SET locked=true",
            "ALTER TABLE carl_account ADD COLUMN forbidden INTEGER", "SET ROLE " + properties.getProperty("migration.username")))
            assertThrows(SQLException.class, () -> statement.execute(sql), sql);
         assertThrows(IllegalStateException.class, () -> AgentLiquibase.migrate(runtime));
      }
      try(var connection = source(properties, "qqq.reader").getConnection(); var statement = connection.createStatement())
      {
         try(var rows = statement.executeQuery("SELECT kind,review_state FROM carl_account_view WHERE principal='migration-owner'"))
         {
            assertTrue(rows.next());
            assertEquals("UNCLASSIFIED", rows.getString(1));
            assertEquals("NEEDS_REVIEW", rows.getString(2));
         }
         try(var rows = statement.executeQuery("SELECT count(id) FROM carl_account_view WHERE principal='migration-denied'"))
         {
            rows.next();
            assertEquals(0, rows.getInt(1));
         }
         try(var rows = statement.executeQuery("SELECT source_identity,as_of_date FROM carl_document_view WHERE principal='migration-owner' AND id=102"))
         {
            assertTrue(rows.next());
            assertEquals("synthetic://supplied-retained-note", rows.getString(1));
            assertEquals("2020-01-02", rows.getDate(2).toString());
         }
         try(var rows = statement.executeQuery("SELECT count(id) FROM carl_document_view WHERE principal='migration-denied'"))
         {
            rows.next();
            assertEquals(0, rows.getInt(1));
         }
         try(var rows=statement.executeQuery("SELECT source_table,row_count,text_policy FROM carl_table_export_view WHERE principal='migration-owner'"))
         {
            assertTrue(rows.next());assertEquals("carlDocuments",rows.getString(1));assertEquals(1,rows.getInt(2));assertEquals(TableExports.TEXT_POLICY,rows.getString(3));
         }
         try(var rows=statement.executeQuery("SELECT count(id) FROM carl_table_export_view WHERE principal='migration-denied'"))
         {
            rows.next();assertEquals(0,rows.getInt(1));
         }
         for(String sql : List.of("SELECT content FROM carl_table_export", "SELECT requester_id FROM carl_table_export", "SELECT source_digest FROM carl_table_export_view", "UPDATE carl_table_export SET content='forged'::bytea", "SELECT original_content FROM carl_document", "SELECT extracted_text FROM carl_document", "SELECT * FROM carl_document_review", "SELECT * FROM carl_document_download", "UPDATE carl_document SET source_identity='forged'", "SELECT evidence FROM carl_account_view", "SELECT * FROM carl_transaction_source",
            "SELECT * FROM agent_databasechangelog", "SELECT * FROM agent_databasechangeloglock",
            "UPDATE carl_account SET review_state='CONFIRMED'", "CREATE TEMP TABLE forbidden(id INTEGER)"))
            assertThrows(SQLException.class, () -> statement.execute(sql), sql);
      }
   }

   private static void interrupted(PostgreSQLContainer<?> database, Properties properties, DataSource source, Path nativeLog, String upgrade, int expectedHistory) throws Exception
   {
      String insert = "<changeSet id='ci-interrupted-carl-data' author='carl-ci'><preConditions onFail='HALT'><tableExists tableName='carl_balance'/></preConditions><sql>INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(100,'2026-10-02',12.34,'HUMAN_ASSERTION','Synthetic retry evidence'); ";
      // A new immutable distribution has a new resource root, while retaining logical changeset identities.
      Path nextDistribution = Files.createTempDirectory(nativeLog.getParent().getParent().getParent().getParent(), "qualification-only-upgrade-");
      nativeLog = nextDistribution.resolve(AgentLiquibase.DOMAIN_CHANGELOG);
      Files.createDirectories(nativeLog.getParent());
      Files.writeString(nativeLog, HEADER + upgrade + insert + "SELECT pg_sleep(30);</sql></changeSet></databaseChangeLog>");
      Path credentials = Files.createTempFile("carl-synthetic-native-", ".properties");
      try
      {
         try(var output = Files.newBufferedWriter(credentials)) { properties.store(output, "Owned disposable credentials"); }
         var worker = nativeWorker(credentials, nextDistribution);
         Integer backend = null;
         long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
         while(backend == null && System.nanoTime() < deadline)
         {
            if(!worker.isAlive())
            {
               throw new IllegalStateException("Interrupted migration finished before transactional pause; see native-worker.log");
            }
            String active = rows(admin(database), "SELECT pid FROM pg_stat_activity WHERE datname='carl_retained_clone' AND query LIKE '%pg_sleep(30)%'").trim();
            if(!active.isEmpty()) backend = Integer.valueOf(active); else Thread.sleep(50);
         }
         assertTrue(backend != null, "Actual Carl data migration must reach transactional pause");
         assertEquals("t", rows(admin(database), "SELECT pg_terminate_backend(" + backend + ")").trim());
         assertTrue(worker.waitFor(15, TimeUnit.SECONDS), "Terminated migration JVM must finish");
         assertTrue(worker.exitValue() != 0, "Terminated migration must fail");
         assertEquals(0, number(source, "SELECT count(*) FROM carl_balance WHERE evidence='Synthetic retry evidence'"));
         assertEquals(expectedHistory + 1, number(source, "SELECT count(*) FROM agent_databasechangelog"));
         assertEquals("t", rows(source, "SELECT locked FROM agent_databasechangeloglock WHERE id=1").trim());
         assertEquals(0, number(admin(database), "SELECT count(*) FROM pg_stat_activity WHERE datname='carl_retained_clone'"));
         // The worker is done and no sessions remain: explicit operator recovery only.
         execute(source, "UPDATE agent_databasechangeloglock SET locked=false,lockgranted=null,lockedby=null WHERE id=1");
         Files.writeString(nativeLog, HEADER + upgrade + insert + "</sql></changeSet></databaseChangeLog>");
         for(int repeat = 0; repeat < 2; repeat++)
         {
            var retry = nativeWorker(credentials, nextDistribution);
            assertTrue(retry.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, retry.exitValue(), "Unapplied change retries in a fresh offline migration JVM");
         }
         assertEquals(1, number(source, "SELECT count(*) FROM carl_balance WHERE evidence='Synthetic retry evidence'"));
         assertEquals(new BigDecimal("12.3400"), decimal(source, "SELECT amount FROM carl_balance WHERE evidence='Synthetic retry evidence'"));
         assertEquals(expectedHistory + 2, number(source, "SELECT count(*) FROM agent_databasechangelog"));
         assertEquals("f", rows(source, "SELECT locked FROM agent_databasechangeloglock WHERE id=1").trim());
      }
      finally { Files.deleteIfExists(credentials); }
   }

   private static Process nativeWorker(Path credentials, Path resources) throws Exception
   {
      return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp", System.getProperty("java.class.path"),
         CarlLiquibaseQualification.class.getName(), "migrate-native", credentials.toString(), resources.toString())
         .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(resources.resolve("native-worker.log").toFile())).start();
   }

   private static Map<String, String> records(DataSource source) throws Exception
   {
      var result = new LinkedHashMap<String, String>();
      String tables = rows(source, "SELECT tablename FROM pg_tables WHERE schemaname='public' AND (tablename LIKE 'carl_%' OR tablename='transcript_entry') ORDER BY tablename");
      for(String table : tables.lines().toList()) result.put(table, rows(source, "SELECT row_to_json(t)::text FROM " + table + " t ORDER BY row_to_json(t)::text"));
      return result;
   }

   private static Map<String, String> histories(DataSource source) throws Exception
   {
      return Map.of("core", rows(source, "SELECT row_to_json(t)::text FROM flyway_schema_history t ORDER BY installed_rank"),
         "domain", rows(source, "SELECT row_to_json(t)::text FROM agent_domain_schema_history t ORDER BY installed_rank"));
   }

   private static String schema(DataSource source) throws Exception
   {
      return rows(source, "SELECT table_name||':'||column_name||':'||data_type||':'||coalesce(column_default,'')||':'||is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name LIKE 'carl_%' ORDER BY table_name,ordinal_position")
         + rows(source, "SELECT viewname||':'||definition FROM pg_views WHERE schemaname='public' AND viewname LIKE 'carl_%' ORDER BY viewname");
   }

   private static Map<String, String> sqlHashes(ClassLoader loader) throws Exception
   {
      var result = new LinkedHashMap<String, String>();
      var migrations = Path.of(loader.getResource("db/migration").toURI());
      try(var paths = Files.list(migrations))
      {
         for(Path path : paths.sorted().toList()) if(path.getFileName().toString().matches("V[0-9]+__.*\\.sql")) result.put(path.getFileName().toString(), hash(Files.readAllBytes(path)));
      }
      assertTrue(result.containsKey("V60__unreviewed_source_accounts.sql"));
      return result;
   }

   private static boolean verifyPackaged(Map<String, String> sql) throws Exception
   {
      String distribution = System.getenv("CARL_QUALIFIED_DISTRIBUTION");
      if(distribution == null || distribution.isBlank()) return false;
      Path core = Path.of(AgentLiquibase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      assertEquals(hash(Files.readAllBytes(core)), hash(Files.readAllBytes(Path.of(distribution, "lib", core.getFileName().toString()))),
         "Tested migration engine must match the packaged runtime engine");
      try(var archive = new ZipFile(Path.of(distribution, "app.jar").toFile()))
      {
         for(var entry : sql.entrySet())
         {
            var resource = archive.getEntry("db/migration/" + entry.getKey());
            assertTrue(resource != null, "Every actual legacy SQL resource must be packaged");
            try(var input = archive.getInputStream(resource)) { assertEquals(entry.getValue(), hash(input.readAllBytes())); }
         }
         var nativeLog = archive.getEntry(AgentLiquibase.DOMAIN_CHANGELOG);
         assertTrue(nativeLog != null, "Conventional native consumer changelog must be packaged");
         try(var input = archive.getInputStream(nativeLog); var expected = Thread.currentThread().getContextClassLoader().getResourceAsStream(AgentLiquibase.DOMAIN_CHANGELOG))
         {
            assertEquals(hash(expected.readAllBytes()), hash(input.readAllBytes()));
         }
      }
      return true;
   }

   private static int version(String resource) { return Integer.parseInt(resource.substring(1, resource.indexOf("__"))); }
   private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
   private static Properties copy(Properties properties) { var result = new Properties(); result.putAll(properties); return result; }
   private static String url(PostgreSQLContainer<?> database, String name) { return database.getJdbcUrl().replace("/" + database.getDatabaseName(), "/" + name); }
   private static DataSource admin(PostgreSQLContainer<?> database) { return source(database.getJdbcUrl(), database.getUsername(), database.getPassword()); }
   private static DataSource source(Properties properties, String prefix) { return source(properties.getProperty("db.url"), properties.getProperty(prefix + ".username"), properties.getProperty(prefix + ".password")); }
   private static DataSource source(String url, String user, String password) { var source = new PGSimpleDataSource(); source.setURL(url); source.setUser(user); source.setPassword(password); return source; }
   private static void execute(DataSource source, String sql) throws Exception { try(var connection = source.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); } }
   private static int number(DataSource source, String sql) throws Exception { return Integer.parseInt(rows(source, sql).trim()); }
   private static BigDecimal decimal(DataSource source, String sql) throws Exception { return new BigDecimal(rows(source, sql).trim()); }
   private static String rows(DataSource source, String sql) throws Exception
   {
      var result = new StringBuilder();
      try(var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
      {
         while(rows.next()) result.append(rows.getString(1)).append('\n');
      }
      return result.toString();
   }

   private static void run(String operation, Properties properties) throws Exception
   {
      Path file = Files.createTempFile("carl-synthetic-migration-", ".properties");
      try
      {
         try(var output = Files.newBufferedWriter(file)) { properties.store(output, "Disposable synthetic migration credentials"); }
         DatabaseBootstrap.main(new String[]{operation, file.toString()});
      }
      finally { Files.deleteIfExists(file); }
   }

   private static final class QualificationLoader extends URLClassLoader
   {
      private final Path resources;
      private QualificationLoader(Path resources, ClassLoader parent) throws Exception { super(new URL[]{resources.toUri().toURL()}, parent); this.resources = resources; }
      @Override public URL getResource(String name)
      {
         try { if(name.equals(AgentLiquibase.DOMAIN_CHANGELOG)) return resources.resolve(name).toUri().toURL(); }
         catch(java.net.MalformedURLException failure) { throw new IllegalStateException(failure); }
         return super.getResource(name);
      }
      @Override public Enumeration<URL> getResources(String name) throws java.io.IOException
      {
         if(name.equals(AgentLiquibase.DOMAIN_CHANGELOG)) return Collections.enumeration(List.of(resources.resolve(name).toUri().toURL()));
         return super.getResources(name);
      }
   }
}
