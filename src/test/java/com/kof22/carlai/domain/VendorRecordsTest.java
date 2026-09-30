/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class VendorRecordsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static VendorRecords vendors;
   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.systemUTC());
      vendors = new VendorRecords(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic vendor household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Family',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true)");
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void clear()
   {
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_member SET active=true,can_manage=true");
      sql("UPDATE carl_permission SET details=true");
   }



   private long vendor(String visibility)
   {
      return service.createVendor("alice", "Synthetic repair", "REPAIR", "repair@example.invalid", true, visibility, "Manually supplied synthetic contact");
   }



   private long work(long vendor, String visibility)
   {
      return service.createWorkItem("alice", vendor, "Repair inquiry", "VENDOR_RESPONSE", LocalDate.of(2026, 10, 1), "No quote or commitment exists", visibility);
   }



   private long draft(long work, boolean shared)
   {
      return service.generateDraft(new CarlService.Scope("alice", shared ? Set.of("alice", "bob") : Set.of("alice")), UUID.randomUUID(), work, "FOLLOW_UP");
   }



   @Test
   void correctionsAreAttributedOptimisticAndIdempotent()
   {
      long vendor = vendor("FAMILY");
      long work = work(vendor, "FAMILY");
      UUID request = UUID.randomUUID();
      var value = new VendorRecords.Vendor("Updated repair", "PLUMBING", "new@example.invalid", true);
      vendors.correctVendor("alice", request, vendor, 1, value, "Confirmed contact correction");
      vendors.correctVendor("alice", request, vendor, 1, value, "Confirmed contact correction");
      assertEquals(2L, service.view(new CarlService.Scope("bob", Set.of("bob")), "vendors").getFirst().get("revision"));
      assertThrows(IllegalArgumentException.class, () -> vendors.correctVendor("alice", UUID.randomUUID(), vendor, 1, value, "Stale correction"));
      var update = new VendorRecords.Work("Updated inquiry", "FAMILY_RESPONSE", 2L, LocalDate.of(2026, 10, 3), "Vendor requested a photograph; owner supplied email excerpt");
      UUID workRequest = UUID.randomUUID();
      vendors.correctWork("alice", workRequest, work, 1, update, "Human reviewed correspondence");
      vendors.correctWork("alice", workRequest, work, 1, update, "Human reviewed correspondence");
      var row = service.view(new CarlService.Scope("bob", Set.of("bob")), "work").getFirst();
      assertEquals("FAMILY_RESPONSE", row.get("status"));
      assertEquals(2L, row.get("assigned_member"));
      assertEquals(2L, scalar("SELECT count(*) AS n FROM carl_correction"));
      assertTrue(text("SELECT before_value AS s FROM carl_correction WHERE record_id=" + vendor).contains("repair@example.invalid"));
      assertThrows(IllegalArgumentException.class, () -> vendors.correctVendor("alice", UUID.randomUUID(), vendor, 2, new VendorRecords.Vendor("Name", "REPAIR", null, true), "Invalid verification"));
   }



   @Test
   void sharedWorkNeverDisclosesItsPrivateVendorAndAssigneeMustSeeWork()
   {
      long vendor = vendor("PRIVATE");
      long work = work(vendor, "FAMILY");
      assertTrue(service.view(new CarlService.Scope("bob", Set.of("bob")), "work").isEmpty());
      var update = new VendorRecords.Work("Update", "FAMILY_RESPONSE", 2L, null, null);
      assertThrows(SecurityException.class, () -> vendors.correctWork("alice", UUID.randomUUID(), work, 1, update, "Cannot assign inaccessible work"));
      assertThrows(SecurityException.class, () -> vendors.correctWork("bob", UUID.randomUUID(), work, 1, update, "Guessed record"));
      assertThrows(SecurityException.class, () -> vendors.correctVendor("bob", UUID.randomUUID(), vendor, 1, new VendorRecords.Vendor("Leak", "X", null, false), "Guessed vendor"));
   }



   @Test
   void editsAppendImmutableVersionsRetainAudienceAndPermitBoundedHistory()
   {
      long original = draft(work(vendor("FAMILY"), "FAMILY"), true);
      String originalCopy = vendors.copy("alice", original);
      UUID request = UUID.randomUUID();
      String supplied = "Please clarify the service scope. <script>untrusted text</script>";
      long second = vendors.editDraft("alice", request, original, 1, supplied, "Human reviewed phrasing");
      assertEquals(second, vendors.editDraft("alice", request, original, 1, supplied, "Human reviewed phrasing"));
      assertEquals(originalCopy, vendors.copy("alice", original));
      assertTrue(vendors.copy("bob", second).contains(supplied));
      assertTrue(vendors.copy("bob", second).contains("human edited"));
      long third = vendors.editDraft("bob", UUID.randomUUID(), second, 2, "Please confirm what information is needed.", "Family edited wording");
      assertEquals(List.of(3, 2, 1), vendors.history("alice", third).stream().map(row -> ((Number) row.get("version")).intValue()).toList());
      assertEquals(1, vendors.history("alice", third, 3, 1).size());
      assertEquals(2, ((Number) vendors.history("alice", third, 3, 1).getFirst().get("version")).intValue());
      assertThrows(IllegalArgumentException.class, () -> vendors.editDraft("alice", UUID.randomUUID(), original, 1, "Obsolete edit", "Old version"));
      assertEquals(2L, scalar("SELECT count(*) AS n FROM carl_artifact_audience WHERE artifact_id=" + third));
      assertEquals(2L, scalar("SELECT count(*) AS n FROM carl_artifact_source WHERE artifact_id=" + third));
      assertEquals(0L, scalar("SELECT count(*) AS n FROM carl_correction"));
   }



   @Test
   void concurrentEditorsCannotOverwriteOrForkTheSameHead() throws Exception
   {
      long original = draft(work(vendor("FAMILY"), "FAMILY"), true);
      try(var pool = Executors.newFixedThreadPool(2))
      {
         var results = pool.invokeAll(List.of(() -> attempt(original, "alice"), () -> attempt(original, "bob")));
         assertEquals(1, results.stream().mapToInt(result ->
         {
            try
            {
               return ((Boolean) result.get()) ? 1 : 0;
            }
            catch(Exception error)
            {
               throw new IllegalStateException(error);
            }
         }).sum());
      }
      assertEquals(2L, scalar("SELECT count(*) AS n FROM carl_draft_revision"));
   }



   private boolean attempt(long original, String principal)
   {
      try
      {
         vendors.editDraft(principal, UUID.randomUUID(), original, 1, "Human wording " + principal, "Concurrent human review");
         return true;
      }
      catch(IllegalArgumentException changed)
      {
         return false;
      }
   }



   @Test
   void privateExportsAreVersionSpecificAndReauthorizeSourceRevocation()
   {
      long vendor = vendor("PRIVATE");
      long work = work(vendor, "PRIVATE");
      long original = draft(work, false);
      long edited = vendors.editDraft("alice", UUID.randomUUID(), original, 1, "Please explain the options.", "Human wording");
      UUID exported = vendors.export("alice", UUID.randomUUID(), edited);
      assertEquals(vendors.copy("alice", edited), new String(vendors.load("alice", exported), StandardCharsets.UTF_8));
      assertThrows(SecurityException.class, () -> vendors.copy("bob", edited));
      assertThrows(SecurityException.class, () -> vendors.history("bob", original));
      assertThrows(SecurityException.class, () -> vendors.load("bob", exported));
      assertThrows(SecurityException.class, () -> vendors.editDraft("bob", UUID.randomUUID(), edited, 2, "Guessed", "Guessed private draft"));
      assertThrows(IllegalArgumentException.class, () -> vendors.export("alice", exported, original));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
      assertThrows(SecurityException.class, () -> vendors.load("alice", exported));
      assertThrows(SecurityException.class, () -> vendors.copy("alice", original));
      assertThrows(SecurityException.class, () -> vendors.copy("alice", edited));
   }



   @Test
   void changedSourcesInvalidateExportAndRequireFreshGroundingBeforeEdit()
   {
      long vendor = vendor("FAMILY");
      long original = draft(work(vendor, "FAMILY"), true);
      UUID exported = vendors.export("alice", UUID.randomUUID(), original);
      vendors.correctVendor("alice", UUID.randomUUID(), vendor, 1, new VendorRecords.Vendor("Updated title", "REPAIR", "repair@example.invalid", true), "Corrected source");
      assertTrue(vendors.copy("alice", original).contains("STALE"));
      assertThrows(IllegalArgumentException.class, () -> vendors.load("alice", exported));
      assertThrows(IllegalArgumentException.class, () -> vendors.export("alice", UUID.randomUUID(), original));
      assertThrows(IllegalArgumentException.class, () -> vendors.editDraft("alice", UUID.randomUUID(), original, 1, "Unsupported new draft", "Stale source"));
      sql("UPDATE carl_member SET active=false WHERE id=1");
      assertThrows(SecurityException.class, () -> vendors.copy("alice", original));
   }



   private static void sql(String sql)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement())
      {
         s.execute(sql);
      }
      catch(Exception error)
      {
         throw new IllegalStateException(error);
      }
   }



   private static long scalar(String sql)
   {
      return service.transaction(c -> CarlService.number(CarlService.rows(c, sql).getFirst(), "n"));
   }



   private static String text(String sql)
   {
      return service.transaction(c -> CarlService.rows(c, sql).getFirst().get("s").toString());
   }
}
