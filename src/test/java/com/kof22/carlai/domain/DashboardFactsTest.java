/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class DashboardFactsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);
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
      service = new CarlService(source, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic dashboard','America/Chicago'),(2,'Other home','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Reader',false),(3,2,'other','Other',true)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(3,'FINANCE',true)");
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
         CarlService.execute(c, "UPDATE carl_permission SET details=true");
         return null;
      });
   }



   private long account(String visibility)
   {
      return new FinancialRecords(service).createAccount("alice", "Synthetic cash", "CASH", "USD", true, BigDecimal.ONE, visibility, "Supplied account");
   }



   private long movement(long account, String amount, String classification, String category, String visibility)
   {
      return service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "FINANCE", visibility, "Synthetic movement", "Supplied classification");
         CarlService.execute(c, "INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,classification,category,source_id) VALUES(?,?,?,?,'USD',?,?,?)", id, account, FROM, new BigDecimal(amount), classification, category, UUID.randomUUID().toString());
         return id;
      });
   }



   private JsonNode call(String method, Class<?>[] types, Object... args)
   {
      var dashboard = new DashboardFacts(service);
      return JSON.valueToTree(switch(method)
      {
         case "cashFlow" -> dashboard.cashFlow((CarlService.Scope) args[0], (LocalDate) args[1], (LocalDate) args[2], (String) args[3]);
         case "balanceSheet" -> dashboard.balanceSheet((CarlService.Scope) args[0], (Long) args[1]);
         default -> throw new IllegalArgumentException("Unknown tested dashboard");
      });
   }



   private JsonNode flow(CarlService.Scope scope, String currency)
   {
      return call("cashFlow", new Class<?>[]{CarlService.Scope.class, LocalDate.class, LocalDate.class, String.class}, scope, FROM, THROUGH, currency);
   }



   @Test
   void exactCashTotalsKeepRefundsPrincipalInterestCapitalAndUnknownDirectionsSeparate()
   {
      long account = account("FAMILY");
      movement(account, "1000.00", "INCOME", "Salary", "FAMILY");
      movement(account, "-125.25", "EXPENSE", "Essentials", "FAMILY");
      movement(account, "25.25", "EXPENSE", "Essentials", "FAMILY");
      movement(account, "-200.00", "DEBT_PRINCIPAL", "Principal", "FAMILY");
      movement(account, "-10.00", "DEBT_INTEREST", "Interest", "FAMILY");
      movement(account, "400.00", "CAPITAL", "Asset sale", "FAMILY");
      movement(account, "50.00", "UNCLASSIFIED", "Needs review", "FAMILY");
      var facts = flow(CarlService.Scope.privateFor("alice"), "USD");
      assertEquals(0, new BigDecimal("1475.25").compareTo(facts.path("inflows").decimalValue()));
      assertEquals(0, new BigDecimal("335.25").compareTo(facts.path("outflows").decimalValue()));
      assertEquals(0, new BigDecimal("1140.00").compareTo(facts.path("netMovement").decimalValue()));
      assertEquals(7, facts.path("records").asInt());
      assertTrue(facts.path("gaps").toString().contains("Unclassified"));
      assertTrue(facts.path("flows").toString().contains("Expense refund"));
      assertTrue(facts.path("flows").toString().contains("Asset sale"));
   }



   @Test
   void matchedInternalTransfersAreExcludedButPartialTransferVisibilityIsAnException()
   {
      long account = account("FAMILY");
      long out = movement(account, "-75.25", "UNCLASSIFIED", "Move", "FAMILY");
      long in = movement(account("FAMILY"), "75.25", "UNCLASSIFIED", "Move", "FAMILY");
      new FinancialRecords(service).pairTransfer("alice", out, in, "Human reviewed equal transfer");
      var facts = flow(CarlService.Scope.privateFor("alice"), "USD");
      assertEquals(2, facts.path("excludedTransferLegs").asInt());
      assertEquals(0, facts.path("inflows").decimalValue().signum());
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_record SET visibility='PRIVATE' WHERE id=?", in);
         return null;
      });
      var shared = flow(new CarlService.Scope("alice", Set.of("alice", "bob")), "USD");
      assertTrue(shared.path("gaps").toString().contains("Transfer"));
      assertEquals(0, shared.path("excludedTransferLegs").asInt());
      assertEquals(0, new BigDecimal("75.25").compareTo(shared.path("outflows").decimalValue()));
   }



   @Test
   void scopeIsTheCurrentIntersectionAndRevocationDoesNotReturnCachedFigures()
   {
      long publicAccount = account("FAMILY");
      long privateAccount = account("PRIVATE");
      movement(publicAccount, "100.00", "INCOME", "Shared", "FAMILY");
      movement(privateAccount, "999.00", "INCOME", "PRIVATE marker", "FAMILY");
      var shared = flow(new CarlService.Scope("alice", Set.of("alice", "bob")), "USD");
      assertEquals(0, new BigDecimal("100.00").compareTo(shared.path("inflows").decimalValue()));
      assertFalse(shared.toString().contains("PRIVATE marker"));
      assertThrows(SecurityException.class, () -> flow(new CarlService.Scope("alice", Set.of("alice", "other")), "USD"));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_permission SET details=false WHERE member_id=2");
         return null;
      });
      assertThrows(SecurityException.class, () -> flow(new CarlService.Scope("alice", Set.of("alice", "bob")), "USD"));
   }



   @Test
   void emptyAndMixedCurrencyDataNeverBecomeConfirmedHouseholdCash()
   {
      long account = account("FAMILY");
      movement(account, "200.00", "INCOME", "Salary", "FAMILY");
      var empty = flow(CarlService.Scope.privateFor("alice"), "EUR");
      assertEquals(0, empty.path("records").asInt());
      assertTrue(empty.path("gaps").toString().contains("No"));
      assertTrue(empty.path("scope").asText().contains("Accessible"));
      assertThrows(IllegalArgumentException.class, () -> flow(CarlService.Scope.privateFor("alice"), null));
   }



   @Test
   void balanceDashboardUsesTheChosenSavedSourcesAndCurrentAccess()
   {
      long account = account("PRIVATE");
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(?,? ,500.00,'HUMAN_ASSERTION','Synthetic dated balance')", account, THROUGH);
         return null;
      });
      long artifact = new BalanceSheets(service, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)).report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), THROUGH, 30, List.of(account), List.of());
      var facts = call("balanceSheet", new Class<?>[]{CarlService.Scope.class, long.class}, CarlService.Scope.privateFor("alice"), artifact);
      assertEquals("2026-09-30", facts.path("facts").path("asOf").asText());
      assertEquals(0, new BigDecimal("500.00").compareTo(facts.path("facts").path("knownSelectedNetWorthByCurrency").path("USD").decimalValue()));
      assertThrows(SecurityException.class, () -> call("balanceSheet", new Class<?>[]{CarlService.Scope.class, long.class}, CarlService.Scope.privateFor("bob"), artifact));
      assertThrows(SecurityException.class, () -> call("balanceSheet", new Class<?>[]{CarlService.Scope.class, long.class}, CarlService.Scope.privateFor("alice"), 999999L));
      assertEquals(1, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size(), "Dashboard reads do not persist new artifacts");
   }



   @Test
   void planDashboardKeepsHumanCheckInsAndSelectedObservedEffectsSeparateAndLabelsChangedSources()
   {
      long account = new FinancialRecords(service).createAccount("alice", "Synthetic debt", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "FAMILY", "Supplied statement");
      var debts = new DebtPlans(service);
      debts.terms("alice", account, FROM, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Dated human statement");
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long source = debts.compare(scope, UUID.randomUUID(), FROM, "USD", new BigDecimal("100.00"), 12, "Human budget");
      var plans = new PlanLifecycle(service);
      long plan = plans.create("alice", UUID.randomUUID(), source, "Debt freedom", "Human selection");
      UUID task = UUID.randomUUID();
      plans.step("alice", plan, 1, task, "Make selected payment", 1, THROUGH, "Bank app", null, "Human task");
      plans.agree("alice", plan, 2, "Explicit agreement");
      var effects = new PlanEffects(service);
      long expectation = effects.expect(scope, UUID.randomUUID(), plan, 3, task, PlanEffects.Kind.CASH_PAYMENT, account, new BigDecimal("100.00"), FROM, THROUGH, "Agreed expected effect");
      long transaction = movement(account, "-100.00", "DEBT_PRINCIPAL", "Selected payment", "FAMILY");
      long observed = effects.compare(scope, UUID.randomUUID(), expectation, List.of(transaction), "Explicit selected observation");
      plans.checkIn("alice", plan, 3, task, "REPORTED_COMPLETE", "Human assertion only", null);
      var dashboard = new DashboardFacts(service);
      JsonNode selected = JSON.valueToTree(dashboard.plan(scope, plan));
      assertEquals(4, selected.path("plan").path("version").asInt());
      assertEquals("REPORTED_COMPLETE", selected.path("steps").get(0).path("status").asText());
      assertEquals(expectation, selected.path("expectations").get(0).path("id").asLong());
      assertEquals(Long.toString(observed), selected.path("observations").get(0).path("id").asText());
      assertEquals("MATCH", selected.path("observations").get(0).path("facts").path("outcome").asText());
      assertTrue(selected.path("boundary").asText().contains("does not verify"));
      assertTrue(selected.path("observations").get(0).path("facts").path("planVersionMatches").asBoolean());
      assertFalse(selected.path("observations").get(0).path("currentPlanVersionMatches").asBoolean());
      assertEquals(4, selected.path("observations").get(0).path("currentPlanVersion").asInt());
      int artifacts = service.view(CarlService.Scope.privateFor("alice"), "artifacts").size();
      assertThrows(SecurityException.class, () -> dashboard.plan(CarlService.Scope.privateFor("other"), plan));
      debts.terms("alice", account, FROM.plusDays(1), new BigDecimal("900.00"), new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Changed supplied statement");
      JsonNode changed = JSON.valueToTree(dashboard.plan(scope, plan));
      assertTrue(changed.path("plan").path("source_stale").asBoolean());
      assertEquals(4, changed.path("plan").path("version").asInt());
      assertEquals(artifacts, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size());
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
         return null;
      });
      assertThrows(SecurityException.class, () -> dashboard.plan(scope, plan));
   }



   @Test
   void creditCardAndInvestmentLedgersAreNotCashOrAvailableCredit()
   {
      long cash = account("PRIVATE");
      long credit = new FinancialRecords(service).createAccount("alice", "Synthetic credit", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "PRIVATE", "Supplied statement");
      long investment = new FinancialRecords(service).createAccount("alice", "Synthetic investment", "INVESTMENT", "USD", false, BigDecimal.ONE, "PRIVATE", "Supplied statement");
      movement(cash, "100.25", "INCOME", "Salary", "PRIVATE");
      movement(credit, "-999.25", "EXPENSE", "Card purchase", "PRIVATE");
      movement(investment, "2500.25", "CAPITAL", "Investment ledger", "PRIVATE");
      var result = flow(CarlService.Scope.privateFor("alice"), "USD");
      assertEquals(0, new BigDecimal("100.25").compareTo(result.path("inflows").decimalValue()));
      assertEquals(0, result.path("outflows").decimalValue().signum());
      assertEquals(2, result.path("nonCashAccountMovements").size());
      assertTrue(result.path("gaps").toString().contains("Non-cash"));
   }



   @Test
   void bankToCardLoanAndInvestmentPairsRetainCashLegsAndReconcileAllKindsAndRefunds()
   {
      long cash = account("PRIVATE");
      long card = new FinancialRecords(service).createAccount("alice", "Synthetic card", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "PRIVATE", "Supplied statement");
      long loan = new FinancialRecords(service).createAccount("alice", "Synthetic loan", "LOAN", "USD", false, BigDecimal.ONE, "PRIVATE", "Supplied statement");
      long investment = new FinancialRecords(service).createAccount("alice", "Synthetic investment", "INVESTMENT", "USD", false, BigDecimal.ONE, "PRIVATE", "Supplied statement");
      var records = new FinancialRecords(service);
      records.pairTransfer("alice", movement(cash, "-200.25", "UNCLASSIFIED", "Card payment", "PRIVATE"), movement(card, "200.25", "UNCLASSIFIED", "Card payment", "PRIVATE"), "Matched supplied bank/card pair");
      records.pairTransfer("alice", movement(cash, "-100.10", "UNCLASSIFIED", "Loan payment", "PRIVATE"), movement(loan, "100.10", "UNCLASSIFIED", "Loan payment", "PRIVATE"), "Matched supplied bank/loan pair");
      records.pairTransfer("alice", movement(cash, "-50.05", "UNCLASSIFIED", "Investment contribution", "PRIVATE"), movement(investment, "50.05", "UNCLASSIFIED", "Investment contribution", "PRIVATE"), "Matched supplied bank/investment pair");
      movement(card, "-1000.25", "EXPENSE", "Card purchase", "PRIVATE");
      movement(card, "25.25", "EXPENSE", "Card refund", "PRIVATE");
      movement(cash, "20.10", "EXPENSE", "Cash refund", "PRIVATE");
      var result = flow(CarlService.Scope.privateFor("alice"), "USD");
      assertEquals(0, new BigDecimal("350.40").compareTo(result.path("outflows").decimalValue()));
      assertEquals(0, new BigDecimal("20.10").compareTo(result.path("inflows").decimalValue()));
      assertEquals(0, new BigDecimal("-330.30").compareTo(result.path("netMovement").decimalValue()));
      assertEquals(0, result.path("excludedTransferLegs").asInt());
      assertEquals(0, new BigDecimal("1000.25").compareTo(result.path("classifiedSpendingAllAccountKinds").decimalValue()));
      assertEquals(0, new BigDecimal("45.35").compareTo(result.path("classifiedExpenseRefundsAllAccountKinds").decimalValue()));
      assertEquals(0, new BigDecimal("954.90").compareTo(result.path("classifiedNetSpendingAllAccountKinds").decimalValue()));
      assertEquals(5, result.path("nonCashAccountMovements").size());
      assertTrue(result.path("gaps").toString().contains("principal/interest allocation"));
   }

}
