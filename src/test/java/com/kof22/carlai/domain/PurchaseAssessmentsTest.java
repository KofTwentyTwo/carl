/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PurchaseAssessmentsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static final LocalDate DATE = LocalDate.of(2026, 9, 10);
   private static BigDecimal n(String text)
   {
      return new BigDecimal(text);
   }



   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic purchase household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
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
      sql("UPDATE carl_permission SET details=true");
   }



   private static void sql(String statement)
   {
      service.transaction(c ->
      {
         CarlService.execute(c, statement);
         return null;
      });
   }



   private long cash(boolean complete)
   {
      return new CashPlans(service).create("alice", "Synthetic six-month cash plan", "FAMILY", "Reviewed full scope", new CashPlans.Assumptions("USD", DATE, DATE.plusMonths(6), n("1000"), n("200"), n("1000"), complete, true, true, true, true, true));
   }



   private long offer(String visibility)
   {
      var terms = new FinancingScenarios.Offer("store", "USD", n("600"), n("0"), n("0"), n("100"), 6, List.of(new FinancialPlanning.Rate(1, n("0")), new FinancialPlanning.Rate(7, n("0.24"))), new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 6, n("0"), true), FinancingScenarios.Evidence.VERIFIED_TERMS);
      return new FinancingOffers(service).create("alice", "Synthetic store terms", visibility, "PURCHASE_FINANCE", terms, DATE, DATE.plusDays(30), DATE.plusMonths(1), "Unsecured supplied terms; approval unverified", "Synthetic reviewed price and offer");
   }



   @Test
   void oldZeroStatementAndNewerImportedDebtCannotEstablishGrace() throws Exception
   {
      long cash = cash(true);
      long card = new FinancialRecords(service).createAccount("alice", "Synthetic contradictory card", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "FAMILY", "Synthetic evidence");
      var debt = new DebtPlans(service);
      debt.terms("alice", card, DATE.minusMonths(2), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Old zero statement");
      var cardTerms = new PurchaseAssessments.CardInput(card, DATE.plusDays(20), true, true, "Grace reviewed but source outdated");
      var workflow = new PurchaseAssessments(service);
      long old = workflow.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), cash, DATE.plusDays(2), n("600"), "Table", true, cardTerms, List.of());
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      assertEquals("UNDETERMINED", json.readTree(service.artifact("alice", old).get("facts").toString()).path("purchaseOptions").path("options").get(1).path("state").asText());
      debt.terms("alice", card, DATE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Current zero assertion");
      new FinancialRecords(service).importBalances("alice", UUID.randomUUID(), "Date,Balance,Account\n2026-09-11,-100.00,Synthetic Card\n", java.util.Map.of("Synthetic Card", card), false);
      long conflict = workflow.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), cash, DATE.plusDays(2), n("600"), "Table", true, cardTerms, List.of());
      assertEquals("UNDETERMINED", json.readTree(service.artifact("alice", conflict).get("facts").toString()).path("purchaseOptions").path("options").get(1).path("state").asText());
   }



   @Test
   void familyPurchaseOptionsReturnBoundedFactsAndExplicitRepaymentPages() throws Exception
   {
      long plan = cash(true);
      long offer = offer("FAMILY");
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var handler = workflows.handlers().get("purchase-options");
         var input = json.readTree("{\"cashPlan\":" + plan + ",\"purchaseDate\":\"2026-09-12\",\"allInPrice\":\"600.00\",\"purpose\":\"Kitchen table and chairs\",\"allInCostsKnown\":true,\"card\":null,\"offers\":[" + offer + "]}");
         UUID request = UUID.randomUUID();
         handler.start(context, request, input);
         var result = await(handler, context, request);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.COMPLETE, result.status());
         assertEquals("CASH", result.artifact().get("preferredOption").asText());
         assertEquals("USD", result.artifact().get("currency").asText());
         assertTrue(!result.artifact().get("options").get(0).has("payments"));
         assertEquals(result.artifactId(), handler.start(context, request, input).artifactId());
         var detail = workflows.handlers().get("purchase-detail");
         var page = json.readTree("{\"artifact\":" + result.artifactId() + ",\"option\":\"offer:" + offer + "\",\"offset\":0,\"limit\":2}");
         UUID pageId = UUID.randomUUID();
         detail.start(context, pageId, page);
         var response = await(detail, context, pageId);
         assertEquals(2, response.artifact().get("payments").size());
         assertEquals(7, response.artifact().get("totalPayments").asInt());
         assertEquals(2, response.artifact().get("nextOffset").asInt());
         var bad = input.deepCopy();
         ((com.fasterxml.jackson.databind.node.ObjectNode) bad).put("caller", "bob");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> handler.start(context, UUID.randomUUID(), bad));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> detail.get(context, pageId));
      }
   }



   private static com.kof22.agentadmin.client.ClientWorkflow.Result await(com.kof22.agentadmin.client.ClientWorkflow handler, com.kof22.agentadmin.client.ClientWorkflow.Context context, UUID request) throws InterruptedException
   {
      for(int n = 0; n < 200; n++)
      {
         var result = handler.get(context, request);
         if(result.status() != com.kof22.agentadmin.client.ClientWorkflow.Status.PENDING)
         {
            return result;
         }
         Thread.sleep(10);
      }
      throw new AssertionError("Workflow did not finish");
   }



   @Test
   void persistedCashCardAndStoreComparisonSharesSourcesAndRetries() throws Exception
   {
      long cash = cash(true);
      long offer = offer("FAMILY");
      long card = new FinancialRecords(service).createAccount("alice", "Synthetic zero-balance card", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "FAMILY", "Synthetic statement");
      new DebtPlans(service).terms("alice", card, DATE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Synthetic zero balance; human supplied");
      var input = new PurchaseAssessments.CardInput(card, DATE.plusMonths(1), true, true, "Synthetic confirmed grace applies and full payoff planned");
      var workflow = new PurchaseAssessments(service);
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      UUID request = UUID.randomUUID();
      long report = workflow.compare(scope, request, cash, DATE.plusDays(2), n("600"), "Kitchen table and chairs", true, input, List.of(offer));
      var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(service.artifact("bob", report).get("facts").toString());
      assertEquals("CASH", facts.path("purchaseOptions").path("preferredOption").asText());
      assertEquals(3, facts.path("purchaseOptions").path("options").size());
      assertEquals(0, n("800").compareTo(facts.path("purchaseOptions").path("maximumCashBudget").decimalValue()));
      assertEquals(report, workflow.compare(scope, request, cash, DATE.plusDays(2), n("600"), "Kitchen table and chairs", true, input, List.of(offer)));
      assertThrows(IllegalArgumentException.class, () -> workflow.compare(scope, request, cash, DATE.plusDays(2), n("601"), "Kitchen table and chairs", true, input, List.of(offer)));
      new CashPlans(service).event("alice", cash, UUID.randomUUID(), DATE.plusDays(1), n("-100"), "New repair", "Synthetic changed commitment");
      assertEquals(Boolean.TRUE, service.artifact("alice", report).get("stale"));
   }



   @Test
   void partialEvidenceAndPrivateOffersCannotBecomeSharedAdvice()
   {
      long cash = cash(false);
      long privateOffer = offer("PRIVATE");
      var workflow = new PurchaseAssessments(service);
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      assertThrows(SecurityException.class, () -> workflow.compare(shared, UUID.randomUUID(), cash, DATE.plusDays(2), n("600"), "Kitchen table", true, null, List.of(privateOffer)));
      long report = workflow.compare(shared, UUID.randomUUID(), cash, DATE.plusDays(2), n("600"), "Kitchen table", true, null, List.of());
      assertTrue(service.artifact("bob", report).get("status_label").toString().startsWith("Incomplete"));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2");
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));
   }



   @Test
   void existingCardDebtDoesNotInheritInterestFreePurchaseGrace() throws Exception
   {
      long cash = cash(true);
      long card = new FinancialRecords(service).createAccount("alice", "Synthetic revolving card", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "FAMILY", "Synthetic balance");
      new DebtPlans(service).terms("alice", card, DATE, n("100"), n("25"), BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Existing debt");
      long report = new PurchaseAssessments(service).compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), cash, DATE.plusDays(2), n("600"), "Table", true, new PurchaseAssessments.CardInput(card, DATE.plusMonths(1), true, true, "User says interest-free, but balance contradicts"), List.of());
      var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(service.artifact("alice", report).get("facts").toString());
      assertEquals("UNDETERMINED", facts.path("purchaseOptions").path("options").get(1).path("state").asText());
   }
}
