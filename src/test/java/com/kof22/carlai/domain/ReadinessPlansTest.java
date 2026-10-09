/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReadinessPlansTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static final ObjectMapper JSON = new ObjectMapper();

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
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled readiness regression','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Member',true)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(1,'TAX',true),(2,'TAX',true)");
         return null;
      });
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void clear()
   {
      service.transaction(c ->
      {
         CarlService.execute(c, "TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
         return null;
      });
   }



   private long unreviewed(String principal)
   {
      long id = new FinancialRecords(service).createAccount(principal, "Observed source label", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Controlled source evidence");
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_account SET kind='UNCLASSIFIED',review_state='NEEDS_REVIEW',liquid=NULL,ownership_share=NULL WHERE record_id=?", id);
         return null;
      });
      return id;
   }



   @Test
   void historicalDocumentsSupportReadinessWithoutBecomingCurrentFinancialTerms() throws Exception
   {
      var documents = new DocumentRecords(service);
      String upload = "historical-" + UUID.randomUUID() + ".txt";
      new MonarchImportWorkflow(service).storeUpload("bob", upload, "Historical supplied terms, current agreement unknown".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      long id = documents.confirm("bob", documents.preview("bob", UUID.randomUUID(), upload,
         new DocumentRecords.Source("Historical evidence", "PRIVATE", "Supplied old note", "HISTORICAL_NOTE", java.time.LocalDate.of(2020, 1, 1), null, "Historical note only")), true);
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,1,true)", id);
         return null;
      });
      var result = new ReadinessPlans(service).generate(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), "Documented financial preparation");
      var facts = JSON.readTree(service.artifact("alice", result.artifact()).get("facts").toString());
      assertEquals(1, facts.path("inventory").path("documents").asInt());
      assertEquals(0, facts.path("inventory").path("debts").asInt());
      assertTrue(facts.path("gaps").toString().contains("historical"));
      assertEquals("DRAFT", ((java.util.Map<?, ?>) new PlanLifecycle(service).get("alice", result.plan()).get("plan")).get("state"));
      service.transaction(c ->
      {
         CarlService.execute(c, "DELETE FROM carl_grant WHERE record_id=? AND member_id=1", id);
         return null;
      });
      assertThrows(SecurityException.class, () -> new PlanLifecycle(service).get("alice", result.plan()));
   }



   @Test
   void savesRealReadinessGapsAsDraftWithoutInventedTermsOrExecution() throws Exception
   {
      long account = unreviewed("alice");
      new FinancialGoals(service).create("alice", "Debt freedom", "DEBT_FREEDOM", 1, "PRIVATE", "Explicit owner priority");
      var result = new ReadinessPlans(service).generate(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), "Financial readiness");
      var artifact = service.artifact("alice", result.artifact());
      var facts = JSON.readTree(artifact.get("facts").toString());
      assertEquals("FINANCIAL_READINESS", facts.path("planType").asText());
      assertEquals(1, facts.path("inventory").path("accounts").asInt());
      assertEquals(account, facts.path("accountReview").get(0).path("recordId").asLong());
      assertTrue(facts.path("gaps").toString().contains("debt"));
      assertFalse(facts.has("payoffDate"));
      assertFalse(facts.has("monthlyBudget"));
      var plan = new PlanLifecycle(service).get("alice", result.plan());
      assertEquals("DRAFT", ((java.util.Map<?, ?>) plan.get("plan")).get("state"));
      assertTrue(((List<?>) plan.get("steps")).isEmpty());
      assertThrows(SecurityException.class, () -> new PlanLifecycle(service).get("bob", result.plan()));
   }



   @Test
   void retriesReturnSameArtifactAndPlanWithoutDuplicates()
   {
      unreviewed("alice");
      UUID request = UUID.randomUUID();
      var plans = new ReadinessPlans(service);
      var first = plans.generate(CarlService.Scope.privateFor("alice"), request, "Readiness");
      assertEquals(first, plans.generate(CarlService.Scope.privateFor("alice"), request, "Readiness"));
      assertEquals(1, service.view(CarlService.Scope.privateFor("alice"), "plans").size());
      assertThrows(IllegalArgumentException.class, () -> plans.generate(CarlService.Scope.privateFor("alice"), request, "Changed request"));
   }



   @Test
   void completedRetryAfterSourceChangeReturnsOriginalStalePlan()
   {
      long account = unreviewed("alice");
      var plans = new ReadinessPlans(service);
      UUID request = UUID.randomUUID();
      var first = plans.generate(CarlService.Scope.privateFor("alice"), request, "Readiness");
      new FinancialRecords(service).reviewAccount("alice", account, "CASH", true, BigDecimal.ONE, "Confirmed source identity");
      assertEquals(first, plans.generate(CarlService.Scope.privateFor("alice"), request, "Readiness"));
      var saved = (java.util.Map<?, ?>) new PlanLifecycle(service).get("alice", first.plan()).get("plan");
      assertEquals(true, saved.get("source_stale"));
   }



   @Test
   void sourceRevocationDeniesPreviouslySavedPlan()
   {
      long account = unreviewed("bob");
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,1,true)", account);
         return null;
      });
      var result = new ReadinessPlans(service).generate(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), "Readiness");
      service.transaction(c ->
      {
         CarlService.execute(c, "DELETE FROM carl_grant WHERE record_id=? AND member_id=1", account);
         return null;
      });
      assertThrows(SecurityException.class, () -> new PlanLifecycle(service).get("alice", result.plan()));
   }



   @Test
   void requesterOnlyGenerationRejectsInventedSharedAudience()
   {
      assertThrows(IllegalArgumentException.class, () -> new ReadinessPlans(service).generate(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), "Readiness"));
      assertThrows(SecurityException.class, () -> new ReadinessPlans(service).generate(CarlService.Scope.privateFor("unmapped"), UUID.randomUUID(), "Readiness"));
   }
}
