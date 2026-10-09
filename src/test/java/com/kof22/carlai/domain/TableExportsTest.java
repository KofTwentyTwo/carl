/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Controlled PostgreSQL export fixtures, separate from private deployment acceptance. */
class TableExportsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static PGSimpleDataSource data;
   private static CarlService service;
   private static TableExports exports;
   private static final Instant AS_OF = Instant.parse("2026-10-01T01:02:03Z");

   @BeforeAll
   static void start()
   {
      DATABASE.start();
      data = new PGSimpleDataSource();
      data.setURL(DATABASE.getJdbcUrl());
      data.setUser(DATABASE.getUsername());
      data.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(data);
      service = new CarlService(data, Clock.systemUTC());
      exports = new TableExports(service, Map.of("carlVendors", List.of(column("id", false), column("title", true), column("contact", true), column("evidence", true)), "carlBills", List.of(column("id", false), column("title", true), column("amount", false), column("currency", true), column("due_date", false)), "carlImportReviews", List.of(column("id", true), column("status", true), column("result", true))), Clock.fixed(AS_OF, ZoneOffset.UTC));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled home','America/Chicago'),(2,'Other home','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Family',true),(3,2,'outsider','Other',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN (VALUES('VENDORS'),('FINANCE'),('BILLS')) AS domains(d)");
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void reset()
   {
      sql("TRUNCATE carl_record,carl_request,carl_table_export,carl_upload RESTART IDENTITY CASCADE");
      sql("UPDATE carl_member SET active=true,can_manage=true");
      sql("UPDATE carl_permission SET details=true");
   }



   private static TableExports.Column column(String name, boolean text)
   {
      return new TableExports.Column(name, text);
   }



   private static void sql(String text)
   {
      service.transaction(c ->
      {
         CarlService.execute(c, text);
         return null;
      });
   }



   private static long vendor(String principal, String visibility, String title)
   {
      return service.createVendor(principal, title, "Synthetic supplied", "=HYPERLINK(\"https://never-fetch.invalid\")", false, visibility, "Supplied historical source, not verification");
   }



   private static long count()
   {
      return service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM carl_table_export").getFirst(), "n"));
   }



   private static List<org.apache.commons.csv.CSVRecord> parsed(byte[] csv) throws Exception
   {
      try(var parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(new java.io.StringReader(new String(csv, StandardCharsets.UTF_8))))
      {
         return parser.getRecords();
      }
   }



   @Test
   void selectedFullPrivateFamilyAndImmutableCallerBoundRetry() throws Exception
   {
      long own = vendor("alice", "PRIVATE", "Évidence, \"quoted\"\n𠀀");
      long family = vendor("alice", "FAMILY", "Family evidence");
      long hidden = vendor("bob", "PRIVATE", "Other private");
      vendor("outsider", "FAMILY", "Other household");
      UUID id = UUID.randomUUID();
      assertEquals(id, exports.generate("alice", id, "carlVendors", TableExports.Scope.SELECTED, List.of("" + own)));
      byte[] original = exports.load("alice", id);
      var rows = parsed(original);
      assertEquals(2, rows.size());
      assertEquals("EXPORT_METADATA", rows.getFirst().get("_export_row_type"));
      assertEquals(AS_OF.toString(), rows.getFirst().get("_export_as_of"));
      assertEquals("Évidence, \"quoted\"\n𠀀", rows.get(1).get("title"));
      assertTrue(rows.get(1).get("contact").startsWith("'=HYPERLINK"));
      assertFalse(new String(original, StandardCharsets.UTF_8).contains("principal"));
      assertEquals(id, exports.generate("alice", id, "carlVendors", TableExports.Scope.SELECTED, List.of("" + own)));
      assertArrayEquals(original, exports.load("alice", id));
      assertArrayEquals(original, new TableExports(new CarlService(data, Clock.systemUTC()), Map.of("carlVendors", List.of(column("id", false), column("title", true), column("contact", true), column("evidence", true)))).load("alice", id));
      assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", id, "carlVendors", TableExports.Scope.SELECTED, List.of("" + family)));
      assertThrows(IllegalArgumentException.class, () -> exports.generate("bob", id, "carlVendors", TableExports.Scope.SELECTED, List.of("" + family)));
      assertThrows(SecurityException.class, () -> exports.load("bob", id));
      assertThrows(SecurityException.class, () -> exports.load("outsider", id));
      assertThrows(SecurityException.class, () -> exports.load(null, id));
      assertThrows(SecurityException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.SELECTED, List.of("" + own, "" + hidden)));
      UUID full = exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.ALL_AUTHORIZED, List.of());
      assertEquals(3, parsed(exports.load("alice", full)).size());
      UUID bob = exports.generate("bob", UUID.randomUUID(), "carlVendors", TableExports.Scope.ALL_AUTHORIZED, List.of());
      assertEquals(3, parsed(exports.load("bob", bob)).size());
      assertThrows(SQLException.class, () ->
      {
         try(var c = data.getConnection(); var s = c.createStatement())
         {
            s.execute("UPDATE carl_table_export SET content='new'::bytea");
         }
      });
   }



   @Test
   void exactDecimalCurrencyAndExplicitSourceDate() throws Exception
   {
      long id = service.enterBill("alice", UUID.randomUUID(), new BillCsv.Row(1, "synthetic-source", "Vendor", "Historical amount", new BigDecimal("123456789012.34"), "USD", LocalDate.parse("2020-02-29"), "UNPAID", "PRIVATE"));
      UUID export = exports.generate("alice", UUID.randomUUID(), "carlBills", TableExports.Scope.SELECTED, List.of("" + id));
      var row = parsed(exports.load("alice", export)).get(1);
      assertEquals("123456789012.3400", row.get("amount"));
      assertEquals("USD", row.get("currency"));
      assertEquals("2020-02-29", row.get("due_date"));
   }



   @Test
   void currentDetailsRevocationRestoreAndSourceChangeNeverReuseOldExport()
   {
      long id = vendor("alice", "FAMILY", "Original");
      UUID export = exports.generate("bob", UUID.randomUUID(), "carlVendors", TableExports.Scope.SELECTED, List.of("" + id));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='VENDORS'");
      assertThrows(SecurityException.class, () -> exports.load("bob", export));
      sql("UPDATE carl_permission SET details=true WHERE member_id=2 AND domain='VENDORS'");
      assertThrows(SecurityException.class, () -> exports.load("bob", export));
      UUID current = exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.SELECTED, List.of("" + id));
      sql("UPDATE carl_vendor SET contact='Changed supplied contact' WHERE record_id=" + id);
      assertThrows(SecurityException.class, () -> exports.load("alice", current));
      UUID newest = exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.SELECTED, List.of("" + id));
      sql("UPDATE carl_member SET active=false WHERE id=1");
      assertThrows(SecurityException.class, () -> exports.load("alice", newest));
   }



   @Test
   void uuidSourceIdsAndOwnerOnlyFinanceHistoryAreProtected() throws Exception
   {
      UUID review = UUID.randomUUID();
      sql("INSERT INTO carl_upload(reference,member_id,contents) VALUES('controlled-uuid-source',1,'Supplied original'::bytea)");
      sql("INSERT INTO carl_import_review(id,member_id,transaction_reference,status,result) VALUES('" + review + "',1,'controlled-uuid-source','PREVIEW','Historical pending import')");
      assertEquals(List.of(review.toString()), exports.selection("alice", "carlImportReviews", List.of(review.toString())));
      UUID export = exports.generate("alice", UUID.randomUUID(), "carlImportReviews", TableExports.Scope.SELECTED, List.of(review.toString()));
      assertEquals(review.toString(), parsed(exports.load("alice", export)).get(1).get("id"));
      assertThrows(SecurityException.class, () -> exports.selection("bob", "carlImportReviews", List.of(review.toString())));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
      assertThrows(SecurityException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlImportReviews", TableExports.Scope.ALL_AUTHORIZED, List.of()));
      assertThrows(SecurityException.class, () -> exports.load("alice", export));
   }



   @Test
   void rejectsSelectionsAndUnsafeColumnsBeforePublishing()
   {
      assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlMembers", TableExports.Scope.ALL_AUTHORIZED, List.of()));
      assertThrows(IllegalArgumentException.class, () -> exports.selection("alice", "carlVendors", List.of("1 OR 1=1")));
      assertThrows(IllegalArgumentException.class, () -> exports.selection("alice", "carlVendors", List.of("1", "1")));
      assertThrows(IllegalArgumentException.class, () -> exports.selection("alice", "carlVendors", java.util.stream.LongStream.rangeClosed(1, 1001).mapToObj(Long::toString).toList()));
      assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.SELECTED, List.of()));
      assertThrows(IllegalArgumentException.class, () -> new TableExports(service, Map.of("carlVendors", List.of(column("id", false), column("principal", true)))));
      assertEquals(0, count());
   }



   @Test
   void rowAndByteBoundsPublishNoPartialArtifact()
   {
      sql("INSERT INTO carl_record(household_id,owner_id,domain,visibility,title,evidence) SELECT 1,1,'VENDORS','PRIVATE','Controlled row '||n,'Source' FROM generate_series(1,50001) n");
      sql("INSERT INTO carl_vendor(record_id,category,contact,contact_verified) SELECT id,'Fixture','Contact',false FROM carl_record");
      assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.ALL_AUTHORIZED, List.of()));
      assertEquals(0, count());
      sql("DELETE FROM carl_vendor WHERE record_id=(SELECT max(id) FROM carl_record)");
      sql("UPDATE carl_record SET evidence=repeat('e',250)");
      var csvBound = assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.ALL_AUTHORIZED, List.of()));
      assertInstanceOf(java.io.IOException.class, csvBound.getCause());
      assertEquals(0, count());
      sql("TRUNCATE carl_record RESTART IDENTITY CASCADE");
      long id = vendor("alice", "PRIVATE", "Bounded");
      sql("UPDATE carl_vendor SET contact=repeat('𠀀',5100000) WHERE record_id=" + id);
      assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.SELECTED, List.of("" + id)));
      assertEquals(0, count());
   }



   @Test
   void permissionRaceDuringNativeSourceReadPreventsPublication() throws Exception
   {
      vendor("alice", "PRIVATE", "Controlled race");
      sql("ALTER VIEW carl_vendor_view RENAME TO fixture_vendor_base");
      sql("CREATE FUNCTION fixture_pause() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$ BEGIN PERFORM pg_advisory_xact_lock(713131); RETURN true; END $$");
      sql("CREATE VIEW carl_vendor_view AS SELECT v.* FROM fixture_vendor_base v WHERE fixture_pause()");
      try(var lock = data.getConnection(); var statement = lock.createStatement(); var pool = Executors.newSingleThreadExecutor())
      {
         statement.execute("SELECT pg_advisory_lock(713131)");
         var running = pool.submit(() -> exports.generate("alice", UUID.randomUUID(), "carlVendors", TableExports.Scope.ALL_AUTHORIZED, List.of()));
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
         boolean waiting = false;
         while(System.nanoTime() < deadline)
         {
            waiting = service.transaction(c -> !CarlService.rows(c, "SELECT pid FROM pg_stat_activity WHERE wait_event='advisory' AND query LIKE '%carl_vendor_view%' ").isEmpty());
            if(waiting)
            {
               break;
            }
            Thread.sleep(20);
         }
         assertTrue(waiting, "Actual source read must be blocked before revocation");
         sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
         statement.execute("SELECT pg_advisory_unlock(713131)");
         var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> running.get(10, TimeUnit.SECONDS));
         assertInstanceOf(SecurityException.class, failure.getCause());
         assertEquals(0, count());
      }
      finally
      {
         sql("DROP VIEW carl_vendor_view");
         sql("ALTER VIEW fixture_vendor_base RENAME TO carl_vendor_view");
         sql("DROP FUNCTION fixture_pause()");
      }
   }
}
