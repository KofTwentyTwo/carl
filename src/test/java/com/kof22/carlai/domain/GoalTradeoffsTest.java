/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** FIN-20 / FAT-23: actual source-grounded comparisons and attributed human revisions. */
class GoalTradeoffsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final LocalDate ASOF = LocalDate.of(2026, 9, 1);
   private static CarlService service;
   private static FinancialGoals goals;
   private final ObjectMapper json = new ObjectMapper();

   private record Inputs(long account, long debtGoal, long rentalGoal, long investmentGoal, long debt, long investment)
   {
      Set<Long> goals()
      {
         return Set.of(debtGoal, rentalGoal, investmentGoal);
      }
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
      goals = new FinancialGoals(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic goals','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Reader',false),(3,1,'charlie','Manager',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(3,'FINANCE',true)");
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



   private Inputs inputs(CarlService.Scope scope, boolean complete)
   {
      String visibility = scope.audience().size() > 1 ? "FAMILY" : "PRIVATE";
      long debtGoal = goals.create("alice", "Debt freedom", "DEBT_FREEDOM", 1, visibility, "Owner selected first");
      long rental = goals.create("alice", "Rental and tax structure", "RENTAL_TAX", 2, visibility, "Owner selected second");
      long investment = goals.create("alice", "Later investing", "INVESTMENT", 3, visibility, "Owner selected third");
      goals.context("alice", investment, "USD", new InvestmentPlanning.Context(complete, 1, complete ? "Owner supplied risk context" : null, n("500.00"), complete, complete, complete, "synthetic-context"), "Human supplied context");
      long account = new FinancialRecords(service).createAccount("alice", "Synthetic card", "CREDIT_CARD", "USD", false, BigDecimal.ONE, visibility, "Synthetic account");
      var debts = new DebtPlans(service);
      debts.terms("alice", account, ASOF, n("100.00"), n("10.00"), BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Synthetic statement");
      debts.paymentProfile("alice", account, ASOF, ASOF.plusDays(5), n("102.00"), n("102.00"), FinancialPlanning.Strategy.AVALANCHE, "Supplied current and proposed payments");
      long debt = debts.compare(scope, UUID.randomUUID(), ASOF, "USD", n("102.00"), 1, "Assumed post-essential budget");
      long invest = scenario(scope, investment, "USD", YearMonth.from(ASOF), 1, "102.00");
      return new Inputs(account, debtGoal, rental, investment, debt, invest);
   }



   private long scenario(CarlService.Scope scope, long goal, String currency, YearMonth start, int months, String budget)
   {
      return goals.scenario(scope, UUID.randomUUID(), goal, currency, start, months, n("100.00"), n(budget), new InvestmentPlanning.Assumption("hypothesis", n("0.01"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Supplied uncertain returns"), "Educational assumption");
   }



   private FinancialGoals.PriorityRevision revise(String principal, UUID request, long goal, long expected, int priority, String reason)
   {
      return goals.revisePriority(principal, request, goal, expected, priority, reason);
   }



   private long compare(CarlService.Scope scope, UUID request, long debt, long investment, String strategy, Set<Long> selected)
   {
      return new GoalTradeoffs(service).compare(scope, request, debt, investment, strategy, selected, "Explicit owner tradeoff request");
   }



   private JsonNode facts(long id) throws Exception
   {
      return json.readTree(service.artifact("alice", id).get("facts").toString());
   }



   @Test
   void prioritiesAreAttributedOptimisticAndImmutableAcrossRetries() throws Exception
   {
      var input = inputs(CarlService.Scope.privateFor("alice"), true);
      UUID request = UUID.randomUUID();
      Object receipt = revise("alice", request, input.debtGoal(), 1, 4, "Owner explicitly reprioritized");
      var saved = json.valueToTree(receipt);
      assertEquals(1, saved.path("beforePriority").asInt());
      assertEquals(4, saved.path("afterPriority").asInt());
      assertEquals(2, saved.path("revision").asInt());
      assertEquals("alice", saved.path("principal").asText());
      revise("alice", UUID.randomUUID(), input.debtGoal(), 2, 2, "Owner revised again");
      assertEquals(receipt, revise("alice", request, input.debtGoal(), 1, 4, "Owner explicitly reprioritized"));
      assertThrows(IllegalArgumentException.class, () -> revise("alice", request, input.debtGoal(), 1, 5, "Different input"));
      var corrections = service.transaction(c -> CarlService.rows(c, "SELECT * FROM carl_correction WHERE record_id=? ORDER BY id", input.debtGoal()));
      assertEquals(2, corrections.size());
      assertEquals(1, CarlService.number(corrections.getFirst(), "member_id"));
      assertTrue(corrections.getFirst().get("before_value").toString().contains("\"priority\":1"));
      assertTrue(corrections.getFirst().get("after_value").toString().contains("\"priority\":4"));
      assertFalse(service.view(CarlService.Scope.privateFor("alice"), "financialGoals").stream().filter(row -> CarlService.number(row, "id") == input.debtGoal()).findFirst().orElseThrow().get("investment_stage_selected").equals(true));
   }



   @Test
   void priorityRevisionsRequireCurrentManagerRecordAndSensitiveFieldAccess()
   {
      var input = inputs(CarlService.Scope.privateFor("alice"), true);
      assertThrows(SecurityException.class, () -> revise("bob", UUID.randomUUID(), input.debtGoal(), 1, 2, "Reader denied"));
      assertThrows(SecurityException.class, () -> revise("charlie", UUID.randomUUID(), input.debtGoal(), 1, 2, "Private goal denied"));
      assertThrows(SecurityException.class, () -> revise("unmapped", UUID.randomUUID(), input.debtGoal(), 1, 2, "Unknown identity"));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
      assertThrows(SecurityException.class, () -> revise("alice", UUID.randomUUID(), input.debtGoal(), 1, 2, "Revoked fields"));
   }



   @Test
   void concurrentPriorityRequestsCannotOverwriteTheSameRevision() throws Exception
   {
      var input = inputs(CarlService.Scope.privateFor("alice"), true);
      try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      {
         var first = workers.submit(() -> attemptRevision(input.debtGoal(), 2));
         var second = workers.submit(() -> attemptRevision(input.debtGoal(), 3));
         assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
      }
      assertEquals(1, service.transaction(c -> CarlService.rows(c, "SELECT id FROM carl_correction WHERE record_id=?", input.debtGoal())).size());
   }



   private int attemptRevision(long goal, int priority)
   {
      try
      {
         revise("alice", UUID.randomUUID(), goal, 1, priority, "Concurrent owner review");
         return 1;
      }
      catch(IllegalArgumentException conflict)
      {
         return 0;
      }
   }



   @Test
   void knownDebtCostsAndUncertainInvestmentGrowthRetainExactSeparateCents() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      for(String strategy : Set.of("CURRENT_PAYMENT", "MINIMUM_ONLY", "AVALANCHE", "SNOWBALL", "USER_DIRECTED"))
      {
         var facts = facts(compare(scope, UUID.randomUUID(), input.debt(), input.investment(), strategy, input.goals()));
         assertEquals("COMPARABLE_UNDER_ASSUMPTIONS", facts.path("comparisonStatus").asText());
         assertEquals(0, n("2.00").compareTo(facts.path("debtKnownCosts").path("interest").decimalValue()));
         assertEquals(0, n("1.00").compareTo(facts.path("investmentUncertainGrowth").path("assumedNetGrowth").decimalValue()));
         assertEquals("UNQUALIFIED_ASSUMPTION", facts.path("budgetQualification").asText());
         assertEquals("EDUCATIONAL_ONLY", facts.path("recommendationStatus").asText());
         assertEquals("DEBT_FREEDOM", facts.path("goals").get(0).path("goal_type").asText());
         assertFalse(facts.has("netReturn"));
         assertFalse(facts.has("recommendedStrategy"));
      }
   }



   @Test
   void identicalConcurrentTradeoffRequestsPersistOneArtifact() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      UUID request = UUID.randomUUID();
      try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      {
         var first = workers.submit(() -> compare(scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals()));
         var second = workers.submit(() -> compare(scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals()));
         assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      }
      assertEquals(3, service.view(scope, "artifacts").size());
      assertThrows(IllegalArgumentException.class, () -> compare(scope, request, input.debt(), input.investment(), "SNOWBALL", input.goals()));
   }



   @Test
   void sharedComparisonsRequireExactChildAudienceAndGoalIntersection()
   {
      var privateScope = CarlService.Scope.privateFor("alice");
      var input = inputs(privateScope, true);
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      assertThrows(SecurityException.class, () -> compare(shared, UUID.randomUUID(), input.debt(), input.investment(), "AVALANCHE", input.goals()));
      var family = inputs(shared, true);
      assertThrows(SecurityException.class, () -> compare(privateScope, UUID.randomUUID(), family.debt(), family.investment(), "AVALANCHE", family.goals()));
      long secret = goals.create("alice", "PRIVATE marker", "OTHER", 7, "PRIVATE", "Sensitive goal");
      assertThrows(SecurityException.class, () -> compare(shared, UUID.randomUUID(), family.debt(), family.investment(), "AVALANCHE", Set.of(secret)));
   }



   @Test
   void revocationBlocksSavedTradeoffsAndIdenticalRequestReplay()
   {
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      var input = inputs(scope, true);
      UUID request = UUID.randomUUID();
      long artifact = compare(scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals());
      assertNotNull(service.artifact("bob", artifact));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
      assertThrows(SecurityException.class, () -> service.artifact("bob", artifact));
      assertThrows(SecurityException.class, () -> compare(scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals()));
   }



   @Test
   void missingOwnerFactsStayIncomparableAndBlockPlanAgreement() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, false);
      long artifact = compare(scope, UUID.randomUUID(), input.debt(), input.investment(), "AVALANCHE", input.goals());
      assertEquals("INCOMPARABLE", facts(artifact).path("comparisonStatus").asText());
      assertTrue(facts(artifact).path("incomparableReasons").toString().contains("risk"));
      assertTrue(service.artifact("alice", artifact).get("status_label").toString().startsWith("Incomplete"));
      var plans = new PlanLifecycle(service);
      long plan = plans.create("alice", UUID.randomUUID(), artifact, "Review missing context", "Human draft");
      plans.step("alice", plan, 1, UUID.randomUUID(), "Confirm context", 1, ASOF.plusDays(10), "Local", null, "Review");
      assertThrows(IllegalArgumentException.class, () -> plans.agree("alice", plan, 2, "Cannot agree unknown assumptions"));
   }



   @Test
   void differingCurrencyHorizonStartBudgetAndSnapshotAreExplicitlyIncomparable() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      String[] reasons = {"currency", "horizon", "start", "budget", "snapshot"};
      for(String reason : reasons)
      {
         long investment;
         if(reason.equals("currency"))
         {
            long alternate = goals.create("alice", "EUR educational goal", "INVESTMENT", 4, "PRIVATE", "Different currency");
            investment = scenario(scope, alternate, "EUR", YearMonth.from(ASOF), 1, "102.00");
         }
         else
         {
            investment = scenario(scope, input.investmentGoal(), "USD", reason.equals("start") ? YearMonth.from(ASOF).plusMonths(1) : YearMonth.from(ASOF), reason.equals("horizon") ? 2 : 1, reason.equals("budget") ? "103.00" : "102.00");
         }
         long artifact = compare(scope, UUID.randomUUID(), input.debt(), investment, "AVALANCHE", input.goals());
         assertEquals("INCOMPARABLE", facts(artifact).path("comparisonStatus").asText());
         assertTrue(facts(artifact).path("incomparableReasons").toString().toLowerCase().contains(reason), reason);
      }
   }



   @Test
   void invalidMissingAndStaleChildrenAreDeniedBeforeOutput()
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      assertThrows(IllegalArgumentException.class, () -> compare(scope, UUID.randomUUID(), input.debt(), input.investment(), "BEST_RETURN", input.goals()));
      assertThrows(SecurityException.class, () -> compare(scope, UUID.randomUUID(), 999999, input.investment(), "AVALANCHE", input.goals()));
      assertThrows(IllegalArgumentException.class, () -> compare(scope, UUID.randomUUID(), input.investment(), input.debt(), "AVALANCHE", input.goals()));
      new DebtPlans(service).terms("alice", input.account(), ASOF, n("99.00"), n("10.00"), BigDecimal.ZERO, n("0.24"), BigDecimal.ZERO, "Changed source statement");
      assertThrows(IllegalArgumentException.class, () -> compare(scope, UUID.randomUUID(), input.debt(), input.investment(), "AVALANCHE", input.goals()));
      assertEquals(2, service.view(scope, "artifacts").size());
   }



   @Test
   void goalRevisionsStaleFlattenedTradeoffsAndPlansWhilePreservingHistory() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      long artifact = compare(scope, UUID.randomUUID(), input.debt(), input.investment(), "AVALANCHE", input.goals());
      var sources = service.transaction(c -> CarlService.rows(c, "SELECT source_id FROM carl_artifact_source WHERE artifact_id=?", artifact));
      assertTrue(sources.stream().anyMatch(row -> CarlService.number(row, "source_id") == input.account()));
      assertTrue(sources.stream().anyMatch(row -> CarlService.number(row, "source_id") == input.debt()));
      var plans = new PlanLifecycle(service);
      long plan = plans.create("alice", UUID.randomUUID(), artifact, "Human plan", "Owner review");
      plans.step("alice", plan, 1, UUID.randomUUID(), "Review payment", 1, ASOF.plusDays(10), "Local", null, "Human step");
      plans.agree("alice", plan, 2, "Owner explicitly agreed");
      revise("alice", UUID.randomUUID(), input.debtGoal(), 1, 4, "Owner explicitly selected rental focus");
      assertEquals(Boolean.TRUE, service.artifact("alice", artifact).get("stale"));
      assertEquals(Boolean.TRUE, ((Map<?, ?>) plans.get("alice", plan).get("plan")).get("source_stale"));
      assertThrows(IllegalArgumentException.class, () -> plans.agree("alice", plan, 3, "Stale priorities"));
      long next = compare(scope, UUID.randomUUID(), input.debt(), input.investment(), "AVALANCHE", input.goals());
      assertEquals("RENTAL_TAX", facts(next).path("goals").get(0).path("goal_type").asText());
      assertEquals(4, plans.rebase("alice", plan, 3, next, "Review current owner priorities"));
      assertEquals("DRAFT", ((Map<?, ?>) plans.get("alice", plan).get("plan")).get("state"));
      assertTrue(((java.util.List<?>) plans.get("alice", plan).get("history")).size() >= 4);
      assertFalse(facts(artifact).path("goals").get(0).path("priority").asInt() == 4);
   }



   @Test
   void revisionBoundsAndEmptySelectionsRejectWithoutPersistence()
   {
      var input = inputs(CarlService.Scope.privateFor("alice"), true);
      assertThrows(IllegalArgumentException.class, () -> revise("alice", UUID.randomUUID(), input.debtGoal(), 1, 0, "Invalid priority"));
      assertThrows(IllegalArgumentException.class, () -> revise("alice", UUID.randomUUID(), input.debtGoal(), 1, 2, " "));
      assertThrows(IllegalArgumentException.class, () -> revise("alice", UUID.randomUUID(), input.debtGoal(), 1, 2, "x".repeat(4001)));
      assertThrows(IllegalArgumentException.class, () -> compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), input.debt(), input.investment(), "AVALANCHE", Set.of()));
      assertTrue(service.transaction(c -> CarlService.rows(c, "SELECT id FROM carl_correction WHERE record_id=?", input.debtGoal())).isEmpty());
   }



   @Test
   void identicalConcurrentPriorityRequestsReturnTheSameOriginalReceipt() throws Exception
   {
      var input = inputs(CarlService.Scope.privateFor("alice"), true);
      UUID request = UUID.randomUUID();
      try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      {
         var first = workers.submit(() -> revise("alice", request, input.debtGoal(), 1, 3, "Identical human priority"));
         var second = workers.submit(() -> revise("alice", request, input.debtGoal(), 1, 3, "Identical human priority"));
         assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      }
      assertEquals(1, service.transaction(c -> CarlService.rows(c, "SELECT id FROM carl_correction WHERE record_id=?", input.debtGoal())).size());
      sql("UPDATE carl_member SET active=false WHERE id=1");
      assertThrows(SecurityException.class, () -> revise("alice", request, input.debtGoal(), 1, 3, "Identical human priority"));
   }



   @Test
   void portfolioChildRetainsSelectedCostsWithoutCopyingLongSchedules() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      long portfolio = new PortfolioPlans(service).compare(scope, UUID.randomUUID(), Set.of(input.account()), Set.of(), ASOF, "USD", n("102.00"), 1, FinancialPlanning.Strategy.AVALANCHE, "Supplied post-essential budget");
      long artifact = compare(scope, UUID.randomUUID(), portfolio, input.investment(), "CURRENT_PAYMENT", input.goals());
      var facts = facts(artifact);
      assertEquals(0, n("2.00").compareTo(facts.path("debtKnownCosts").path("interest").decimalValue()));
      assertFalse(facts.path("debtKnownCosts").has("months"));
      assertFalse(facts.path("investmentUncertainGrowth").has("months"));
      assertTrue(service.artifact("alice", artifact).get("facts").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 131072);
   }



   @Test
   void expiredCurrentOfferCannotBecomeComparableThroughAnOlderSavedSnapshot() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      var offer = new FinancingScenarios.Offer("synthetic", "USD", n("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, n("102.00"), 1,
         java.util.List.of(new FinancialPlanning.Rate(1, n("0.24"))), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL);
      long offerId = new FinancingOffers(service).create("alice", "Dated refinancing example", "PRIVATE", "REFINANCE", offer, ASOF, ASOF.plusDays(5), ASOF.plusDays(5), "Collateral unknown", "Supplied historical offer");
      var plans = new PortfolioPlans(service);
      long move = plans.createMove("alice", UUID.randomUUID(), "Hypothetical transfer", "PRIVATE",
         new PortfolioPlans.Destination(offerId, ASOF, n("100.00"), n("102.00"), BigDecimal.ZERO, BigDecimal.ZERO, ASOF.plusDays(5), n("102.00")), Map.of(input.account(), n("100.00")), "Human reviewed historical assumptions");
      long portfolio = plans.compare(scope, UUID.randomUUID(), Set.of(input.account()), Set.of(move), ASOF, "USD", n("102.00"), 1, FinancialPlanning.Strategy.AVALANCHE, "Assumed budget");
      long investment = scenario(scope, input.investmentGoal(), "USD", YearMonth.from(ASOF), 1, "102.00");
      long artifact = compare(scope, UUID.randomUUID(), portfolio, investment, "USER_DIRECTED", input.goals());
      assertEquals("INCOMPARABLE", facts(artifact).path("comparisonStatus").asText());
      assertTrue(facts(artifact).path("incomparableReasons").toString().contains("expired"));
   }



   @Test
   void missingCurrentPaymentEvidenceAndUnusableInvestmentFeesStayIncomplete() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      sql("DELETE FROM carl_debt_payment_profile WHERE account_id=" + input.account());
      long debt = new DebtPlans(service).compare(scope, UUID.randomUUID(), ASOF, "USD", n("102.00"), 1, "Assumed budget");
      long investment = goals.scenario(scope, UUID.randomUUID(), input.investmentGoal(), "USD", YearMonth.from(ASOF), 1, n("100.00"), n("102.00"),
         new InvestmentPlanning.Assumption("unusable-costs", BigDecimal.ZERO, BigDecimal.ZERO, n("101.00"), BigDecimal.ZERO, "Explicit fee assumption"), "Supplied initial fee exceeds capital");
      long artifact = compare(scope, UUID.randomUUID(), debt, investment, "CURRENT_PAYMENT", input.goals());
      assertEquals("INCOMPARABLE", facts(artifact).path("comparisonStatus").asText());
      assertTrue(facts(artifact).path("incomparableReasons").toString().contains("Payment strategy"));
      assertTrue(facts(artifact).path("incomparableReasons").toString().contains("Incomplete investment projection"));
   }



   @Test
   void priorityRevisionUsesTheExistingCompletionTransaction() throws Exception
   {
      var input = inputs(CarlService.Scope.privateFor("alice"), true);
      UUID request = UUID.randomUUID();
      var receipt = service.transaction(c ->
      {
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=1 FOR UPDATE");
         return goals.revisePriority(c, "alice", request, input.debtGoal(), 1, 4, "Atomic completion review");
      });
      assertEquals(4, json.valueToTree(receipt).path("afterPriority").asInt());
      assertEquals(receipt, revise("alice", request, input.debtGoal(), 1, 4, "Atomic completion review"));
      assertThrows(IllegalStateException.class, () -> service.transaction(c ->
      {
         goals.revisePriority(c, "alice", UUID.randomUUID(), input.debtGoal(), 2, 2, "Rollback together");
         throw new IllegalStateException("Completion failed before commit");
      }));
      assertEquals(1, service.transaction(c -> CarlService.rows(c, "SELECT id FROM carl_correction WHERE record_id=?", input.debtGoal())).size());
   }



   @Test
   void tradeoffUsesTheExistingCompletionTransactionAndRollsBackWithIt() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var input = inputs(scope, true);
      UUID request = UUID.randomUUID();
      assertThrows(IllegalStateException.class, () -> service.transaction(c ->
      {
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=1 FOR UPDATE");
         new GoalTradeoffs(service).compare(c, scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals(), "Explicit owner tradeoff request");
         throw new IllegalStateException("Completion failed before commit");
      }));
      assertEquals(2, service.view(scope, "artifacts").size());
      long artifact = service.transaction(c ->
      {
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=1 FOR UPDATE");
         return new GoalTradeoffs(service).compare(c, scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals(), "Explicit owner tradeoff request");
      });
      assertEquals(artifact, compare(scope, request, input.debt(), input.investment(), "AVALANCHE", input.goals()));
   }



   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
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
