/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReportRecoveryTest
{
   private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static final CarlService.Scope PRIVATE = CarlService.Scope.privateFor("alice");
   private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);
   @BeforeAll
   static void start()
   {
      DB.start();
      var ds = new PGSimpleDataSource();
      ds.setURL(DB.getJdbcUrl());
      ds.setUser(DB.getUsername());
      ds.setPassword(DB.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(ds);
      service = new CarlService(ds, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic recovery','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m,d,true FROM generate_series(1,2)m CROSS JOIN unnest(ARRAY['BILLS','CALENDAR','VENDORS'])d");
   }



   @AfterAll
   static void stop()
   {
      DB.stop();
   }



   @BeforeEach
   void reset()
   {
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_permission SET details=true");
   }



   static void sql(String statement)
   {
      service.transaction(c ->
      {
         CarlService.execute(c, statement);
         return null;
      });
   }



   @Test
   void familyFocusedReportsAndRecoveryRetainExactAudience() throws Exception
   {
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var focused = workflows.handlers().get("focused-report");
         UUID request = UUID.randomUUID();
         var input = json.readTree("{\"focus\":\"BILLS\",\"from\":\"2026-09-01\",\"through\":\"2026-09-30\"}");
         focused.start(context, request, input);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, await(focused, context, request).status());
         var status = workflows.handlers().get("report-status");
         UUID statusId = UUID.randomUUID();
         var original = json.createObjectNode().put("reportRequest", request.toString());
         status.start(context, statusId, original);
         assertEquals("PARTIAL", await(status, context, statusId).artifact().get("state").asText());
         var compare = workflows.handlers().get("bill-period-comparison");
         UUID comparison = UUID.randomUUID();
         compare.start(context, comparison, json.readTree("{\"beforeFrom\":\"2026-08-01\",\"beforeThrough\":\"2026-08-30\",\"afterFrom\":\"2026-09-01\",\"afterThrough\":\"2026-09-30\"}"));
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, await(compare, context, comparison).status());
         UUID abandoned = UUID.randomUUID();
         service.claimArtifact(new CarlService.Scope("alice", Set.of("alice", "bob")), abandoned, "REPORT", "fixture");
         var reconcile = workflows.handlers().get("report-reconciliation");
         UUID operation = UUID.randomUUID();
         reconcile.start(context, operation, json.createObjectNode().put("reportRequest", abandoned.toString()).put("reason", "Human reviewed interrupted fixture"));
         assertEquals("FAILED", await(reconcile, context, operation).artifact().get("state").asText());
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> status.start(context, UUID.randomUUID(), original.deepCopy().put("caller", "bob")));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='BILLS'");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> status.get(context, statusId));
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> focused.get(context, request));
      }
   }



   private static com.kof22.agentadmin.client.ClientWorkflow.Result await(com.kof22.agentadmin.client.ClientWorkflow handler, com.kof22.agentadmin.client.ClientWorkflow.Context context, UUID request) throws Exception
   {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      com.kof22.agentadmin.client.ClientWorkflow.Result result;
      do
      {
         result = handler.get(context, request);
         if(result.status() != com.kof22.agentadmin.client.ClientWorkflow.Status.PENDING)
         {
            return result;
         }
         Thread.sleep(10);
      }
      while(System.nanoTime() < until);
      throw new AssertionError("Workflow did not finish");
   }



   @Test
   void interruptedWorkerIsFencedWithoutRepeatingNarration() throws Exception
   {
      UUID request = UUID.randomUUID();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try(var pool = Executors.newSingleThreadExecutor())
      {
         var worker = pool.submit(() -> service.generateReport(PRIVATE, request, FROM, THROUGH, facts ->
         {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return "Synthetic narration";
         }));
         assertTrue(entered.await(10, TimeUnit.SECONDS));
         var recovery = new ReportRecovery(service);
         assertEquals("PENDING", recovery.inspect(PRIVATE, request).state());
         UUID operation = UUID.randomUUID();
         assertEquals("FAILED", recovery.reconcile(PRIVATE, operation, request, "Human stopped synthetic request").state());
         assertEquals("FAILED", recovery.reconcile(PRIVATE, operation, request, "Human stopped synthetic request").state());
         assertThrows(IllegalArgumentException.class, () -> recovery.reconcile(PRIVATE, operation, request, "Changed reason"));
         release.countDown();
         assertThrows(java.util.concurrent.ExecutionException.class, () -> worker.get(10, TimeUnit.SECONDS));
         assertEquals(0, service.view(PRIVATE, "artifacts").size());
         assertThrows(IllegalStateException.class, () -> service.generateReport(PRIVATE, request, FROM, THROUGH, null));
         assertTrue(service.generateReport(PRIVATE, UUID.randomUUID(), FROM, THROUGH, null) > 0);
      }
      finally
      {
         release.countDown();
      }
   }



   @Test
   void recoversOnlyProvableCurrentArtifactAndExactAudience()
   {
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      UUID request = UUID.randomUUID();
      long artifact = service.generateReport(shared, request, FROM, THROUGH, null);
      sql("UPDATE carl_request SET status='UNKNOWN',result_id=NULL WHERE id='" + request + "'");
      var recovery = new ReportRecovery(service);
      assertThrows(SecurityException.class, () -> recovery.inspect(PRIVATE, request));
      assertThrows(SecurityException.class, () -> recovery.inspect(new CarlService.Scope("bob", Set.of("alice", "bob")), request));
      assertEquals(shared, recovery.storedScope("alice", request));
      var result = recovery.reconcile(shared, UUID.randomUUID(), request, "Recover persisted output");
      assertEquals(artifact, result.artifact());
      assertEquals("PARTIAL", result.state());
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='BILLS'");
      assertThrows(SecurityException.class, () -> recovery.inspect(shared, request));
   }



   @Test
   void legacyUnknownScopeCannotBeInventedAndMissingCompletedArtifactIsUnknown()
   {
      UUID legacy = UUID.randomUUID();
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_request(id,member_id,kind,digest,status) VALUES(?,1,'HOUSEHOLD_REPORT',?,'PENDING')", legacy, "0".repeat(64));
         return null;
      });
      var recovery = new ReportRecovery(service);
      assertThrows(SecurityException.class, () -> recovery.storedScope("alice", legacy));
      UUID request = UUID.randomUUID();
      service.claimArtifact(PRIVATE, request, "HOUSEHOLD_REPORT", "synthetic");
      sql("UPDATE carl_request SET status='COMPLETE' WHERE id='" + request + "'");
      assertEquals("UNKNOWN", recovery.reconcile(PRIVATE, UUID.randomUUID(), request, "Investigate missing output").state());
      assertEquals("UNKNOWN", recovery.inspect(PRIVATE, request).state());
   }
}
