/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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


class PortfolioPlansTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static PortfolioPlans plans;
   private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);
   private static final LocalDate ASOF = LocalDate.of(2026, 9, 10);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
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
      plans = new PortfolioPlans(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic portfolio','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Reader',false)");
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
      sql("UPDATE carl_member SET active=true");
      sql("UPDATE carl_permission SET details=true");
   }



   private long account(String title, String visibility)
   {
      long id = new FinancialRecords(service).createAccount("alice", title, "CREDIT_CARD", "USD", false, BigDecimal.ONE, visibility, "Synthetic balance evidence");
      var debts = new DebtPlans(service);
      debts.terms("alice", id, ASOF, n("1000.00"), n("25.00"), BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Synthetic contract");
      debts.paymentProfile("alice", id, ASOF, ASOF.plusDays(5), n("100.00"), n("50.00"), FinancialPlanning.Strategy.AVALANCHE, "Reviewed dates/targets");
      return id;
   }



   private long offer()
   {
      var value = new FinancingScenarios.Offer("synthetic", "USD", n("500.00"), n("10.00"), n("5.00"), n("100.00"), 24, List.of(new FinancialPlanning.Rate(1, n("0.10"))), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL);
      return new FinancingOffers(service).create("alice", "Synthetic partial refinance", "FAMILY", "REFINANCE", value, ASOF, ASOF.plusMonths(1), ASOF.plusDays(8), "Collateral unknown; review required", "Synthetic offer, eligibility unknown");
   }



   private PortfolioPlans.Destination destination(long offer)
   {
      return new PortfolioPlans.Destination(offer, ASOF, n("510.00"), n("100.00"), BigDecimal.ZERO, n("1.00"), ASOF.plusDays(8), n("100.00"));
   }



   private long compare(UUID request, Set<Long> accounts, Set<Long> moves, CarlService.Scope scope)
   {
      return plans.compare(scope, request, accounts, moves, ASOF, "USD", n("200.00"), 24, FinancialPlanning.Strategy.AVALANCHE, "Assumed post-essential budget; cash evidence not qualified");
   }



   @Test
   void familyPortfolioSummaryAndDetailPagesStayBoundedAndReauthorize() throws Exception
   {
      long account = account("Shared card", "FAMILY");
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var handler = workflows.handlers().get("portfolio-comparison");
         var input = json.readTree("{\"accounts\":[" + account + "],\"moves\":[],\"asOf\":\"2026-09-10\",\"currency\":\"USD\",\"monthlyBudget\":\"200.00\",\"horizonMonths\":24,\"rollover\":\"AVALANCHE\",\"budgetEvidence\":\"Synthetic budget assumption\"}");
         UUID request = UUID.randomUUID();
         handler.start(context, request, input);
         var result = await(handler, context, request);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, result.status());
         long artifact = Long.parseLong(result.artifactId());
         var summary = new PortfolioPresentation(service).summary(new CarlService.Scope("alice", Set.of("alice", "bob")), artifact);
         assertTrue(summary.toString().length() < 10000);
         assertEquals("UNQUALIFIED_ASSUMPTION", summary.get("budgetQualification").asText());
         assertEquals(5, summary.get("strategies").size());
         assertEquals("USD", summary.get("currency").asText());
         assertEquals(4, summary.get("comparedWithCurrent").size());
         assertTrue(summary.get("strategies").get("AVALANCHE").has("payoffDate"));
         assertTrue(!summary.toString().contains("remainingBalances"));
         assertEquals(result.artifactId(), handler.start(context, request, input).artifactId());
         var detail = workflows.handlers().get("portfolio-detail");
         var page = json.readTree("{\"artifact\":" + artifact + ",\"section\":\"months\",\"strategy\":\"CURRENT_PAYMENT\",\"offset\":0,\"limit\":2}");
         UUID pageId = UUID.randomUUID();
         detail.start(context, pageId, page);
         var paged = await(detail, context, pageId);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.COMPLETE, paged.status());
         assertTrue(CarlService.json(paged).contains("nextOffset"));
         var presentation = new PortfolioPresentation(service);
         assertEquals(2, presentation.detail(CarlService.Scope.privateFor("alice"), artifact, "months", "CURRENT_PAYMENT", 0, 2).get("items").size());
         assertThrows(IllegalArgumentException.class, () -> presentation.detail(CarlService.Scope.privateFor("alice"), artifact, "months", "CURRENT_PAYMENT", 0, 11));
         assertEquals(1, presentation.detail(CarlService.Scope.privateFor("alice"), artifact, "sources", null, 0, 1).get("items").size());
         assertTrue(presentation.detail(CarlService.Scope.privateFor("alice"), artifact, "assumptions", null, 0, 1).has("items"));
         assertEquals(2, presentation.detail(CarlService.Scope.privateFor("alice"), artifact, "cashDifferences", "AVALANCHE", 0, 2).get("items").size());
         assertEquals(1, presentation.detail(CarlService.Scope.privateFor("alice"), artifact, "payoffDates", "AVALANCHE", 0, 2).get("items").size());
         var bad = page.deepCopy();
         ((com.fasterxml.jackson.databind.node.ObjectNode) bad).put("limit", 11);
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> detail.start(context, UUID.randomUUID(), bad));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> detail.get(context, pageId));
         assertThrows(SecurityException.class, () -> presentation.summary(new CarlService.Scope("alice", Set.of("alice", "bob")), artifact));
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
   void conservationWholePortfolioAndImmutableRetries()
   {
      long a = account("Card A", "FAMILY");
      long b = account("Card B", "FAMILY");
      long offer = offer();
      UUID create = UUID.randomUUID();
      long move = plans.createMove("alice", create, "Reviewed transfer", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Reviewed hypothetical transfer");
      assertEquals(move, plans.createMove("alice", create, "Reviewed transfer", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Reviewed hypothetical transfer"));
      UUID request = UUID.randomUUID();
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long report = compare(request, Set.of(a, b), Set.of(move), scope);
      assertEquals(report, compare(request, Set.of(b, a), Set.of(move), scope));
      String facts = service.artifact("bob", report).get("facts").toString();
      assertTrue(facts.contains("\"originalPrincipal\":2000.00"));
      assertTrue(facts.contains("\"debtAfterFinancedFees\":2010.00"));
      assertTrue(facts.contains("\"upfrontCashFees\":5.00"));
      assertTrue(facts.contains("CURRENT_PAYMENT"));
      assertTrue(facts.contains("USER_DIRECTED"));
      assertTrue(facts.contains("UNQUALIFIED_ASSUMPTION"));
      assertTrue(facts.contains("Collateral unknown"));
      assertThrows(IllegalArgumentException.class, () -> plans.createMove("alice", create, "Different", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Reviewed hypothetical transfer"));
   }



   @Test
   void concurrentIdenticalMoveRequestsPersistOnlyOneReviewedRecord() throws Exception
   {
      long source = account("Concurrent source", "FAMILY");
      long offer = offer();
      UUID request = UUID.randomUUID();
      try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      {
         var first = workers.submit(() -> plans.createMove("alice", request, "Concurrent move", "FAMILY", destination(offer), Map.of(source, n("500.00")), "Same reviewed assumptions"));
         var second = workers.submit(() -> plans.createMove("alice", request, "Concurrent move", "FAMILY", destination(offer), Map.of(source, n("500.00")), "Same reviewed assumptions"));
         assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
         assertEquals(1, plans.records(CarlService.Scope.privateFor("alice")).size());
      }
   }



   @Test
   void privateDependenciesAndCurrentRevocationCannotLeakThroughFamilyMove()
   {
      long a = account("Private source", "PRIVATE");
      long offer = offer();
      long move = plans.createMove("alice", UUID.randomUUID(), "Family label", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Reviewed private allocation");
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      assertTrue(plans.records(scope).isEmpty());
      assertThrows(SecurityException.class, () -> compare(UUID.randomUUID(), Set.of(a), Set.of(move), scope));
      assertThrows(SecurityException.class, () -> plans.createMove("bob", UUID.randomUUID(), "Denied", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Not a manager"));
      long report = compare(UUID.randomUUID(), Set.of(a), Set.of(move), CarlService.Scope.privateFor("alice"));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1");
      assertThrows(SecurityException.class, () -> service.artifact("alice", report));
   }



   @Test
   void changedSourceMakesOldArtifactsStaleAndMoveRequiresNewReview()
   {
      long a = account("Source", "FAMILY");
      long move = plans.createMove("alice", UUID.randomUUID(), "Transfer", "FAMILY", destination(offer()), Map.of(a, n("500.00")), "Reviewed allocation");
      long report = compare(UUID.randomUUID(), Set.of(a), Set.of(move), CarlService.Scope.privateFor("alice"));
      new DebtPlans(service).terms("alice", a, ASOF, n("900.00"), n("25.00"), BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Corrected statement");
      assertEquals(Boolean.TRUE, service.artifact("alice", report).get("stale"));
      long changed = compare(UUID.randomUUID(), Set.of(a), Set.of(move), CarlService.Scope.privateFor("alice"));
      assertTrue(service.artifact("alice", changed).get("status_label").toString().startsWith("Incomplete"));
      assertTrue(service.artifact("alice", changed).get("facts").toString().contains("new human review"));
   }



   @Test
   void everyFutureRateAndFeeIsRetainedAndUnsupportedTimingBlocksComparison()
   {
      long a = account("Source", "FAMILY");
      sql("INSERT INTO carl_debt_rate(account_id,effective_date,annual_rate,monthly_fee,evidence) VALUES(" + a + ",'2026-10-10',0.30,7,'Future contract change')");
      long report = compare(UUID.randomUUID(), Set.of(a), Set.of(), CarlService.Scope.privateFor("alice"));
      String facts = service.artifact("alice", report).get("facts").toString();
      assertTrue(facts.contains("Future contract change"));
      assertTrue(facts.contains("\"firstMonth\":2"));
      assertTrue(facts.contains("\"monthlyFees\":7.00"));
      sql("INSERT INTO carl_debt_rate(account_id,effective_date,annual_rate,monthly_fee,evidence) VALUES(" + a + ",'2026-10-11',0.40,9,'Intra period change')");
      long partial = compare(UUID.randomUUID(), Set.of(a), Set.of(), CarlService.Scope.privateFor("alice"));
      assertTrue(service.artifact("alice", partial).get("facts").toString().contains("intra-period"));
      assertTrue(service.artifact("alice", partial).get("facts").toString().contains("\"comparisons\":{}"));
   }



   @Test
   void capacityAndSourceSelectionRejectUnsupportedAllocations()
   {
      long a = account("Source", "FAMILY");
      long offer = offer();
      var lowMinimum = new PortfolioPlans.Destination(offer, ASOF, n("510.00"), n("25.00"), BigDecimal.ZERO, BigDecimal.ZERO, ASOF.plusDays(8), n("100.00"));
      assertThrows(IllegalArgumentException.class, () -> plans.createMove("alice", UUID.randomUUID(), "False relief", "FAMILY", lowMinimum, Map.of(a, n("500.00")), "Must preserve fixed loan payment"));
      var wrongDate = new PortfolioPlans.Destination(offer, ASOF, n("510.00"), n("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, ASOF.plusDays(9), n("100.00"));
      assertThrows(IllegalArgumentException.class, () -> plans.createMove("alice", UUID.randomUUID(), "Wrong date", "FAMILY", wrongDate, Map.of(a, n("500.00")), "Must preserve contract date"));
      var insufficient = new PortfolioPlans.Destination(offer, ASOF, n("500.00"), n("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, ASOF.plusDays(8), n("100.00"));
      assertThrows(IllegalArgumentException.class, () -> plans.createMove("alice", UUID.randomUUID(), "Too small", "FAMILY", insufficient, Map.of(a, n("500.00")), "Capacity must include fee"));
      assertThrows(IllegalArgumentException.class, () -> plans.createMove("alice", UUID.randomUUID(), "Mismatch", "FAMILY", destination(offer), Map.of(a, n("600.00")), "Offer/source mismatch"));
      long move = plans.createMove("alice", UUID.randomUUID(), "Valid", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Reviewed");
      long b = account("Other", "FAMILY");
      assertThrows(IllegalArgumentException.class, () -> compare(UUID.randomUUID(), Set.of(b), Set.of(move), CarlService.Scope.privateFor("alice")));
   }



   @Test
   void expiredOfferAndDifferentStatementDatesProduceTruthfulGaps()
   {
      long a = account("Source", "FAMILY");
      long offer = offer();
      long move = plans.createMove("alice", UUID.randomUUID(), "Transfer", "FAMILY", destination(offer), Map.of(a, n("500.00")), "Reviewed");
      sql("UPDATE carl_financing_offer SET expires_on='2026-09-09' WHERE record_id=" + offer);
      long expired = compare(UUID.randomUUID(), Set.of(a), Set.of(move), CarlService.Scope.privateFor("alice"));
      assertTrue(service.artifact("alice", expired).get("facts").toString().contains("not current"));
      new DebtPlans(service).terms("alice", a, ASOF.minusDays(1), n("1000.00"), n("25.00"), BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Different statement date");
      long mismatch = compare(UUID.randomUUID(), Set.of(a), Set.of(), CarlService.Scope.privateFor("alice"));
      assertTrue(service.artifact("alice", mismatch).get("facts").toString().contains("same-date balance"));
   }



   @Test
   void excessiveProjectionAndEvidenceFailExplicitlyWithoutTruncatedArtifacts()
   {
      var tooMany = java.util.stream.LongStream.rangeClosed(1, 100).boxed().collect(java.util.stream.Collectors.toSet());
      assertThrows(IllegalArgumentException.class, () -> plans.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), tooMany, Set.of(), ASOF, "USD", n("100.00"), 600, FinancialPlanning.Strategy.AVALANCHE, "Maximum input must fail before record access"));
      long source = account("Large evidence history", "FAMILY");
      sql("INSERT INTO carl_debt_rate(account_id,effective_date,annual_rate,monthly_fee,evidence) SELECT " + source + ",DATE '2020-01-01'+g,0.24,0,repeat('x',4000) FROM generate_series(0,500) g");
      assertThrows(IllegalArgumentException.class, () -> compare(UUID.randomUUID(), Set.of(source), Set.of(), CarlService.Scope.privateFor("alice")));
      assertTrue(service.view(CarlService.Scope.privateFor("alice"), "artifacts").isEmpty());
   }



   private static void sql(String statement)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement())
      {
         s.execute(statement);
      }
      catch(Exception failure)
      {
         throw new IllegalStateException(failure);
      }
   }
}
