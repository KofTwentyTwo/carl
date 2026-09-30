/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PlanEffectsTest
{
   static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
   static CarlService service;
   static PlanEffects effects;
   static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   static final LocalDate TO = LocalDate.of(2026, 9, 30);
   static final CarlService.Scope SHARED = new CarlService.Scope("alice", Set.of("alice", "bob"));
   long account;
   long plan;
   UUID task;
   @BeforeAll
   static void start()
   {
      DB.start();
      var ds = new PGSimpleDataSource();
      ds.setURL(DB.getJdbcUrl());
      ds.setUser(DB.getUsername());
      ds.setPassword(DB.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(ds);
      service = new CarlService(ds, Clock.systemUTC());
      effects = new PlanEffects(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Member',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
   }



   @AfterAll
   static void stop()
   {
      DB.stop();
   }



   @BeforeEach
   void clear()
   {
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_permission SET details=true");
      account = account("FAMILY", "USD");
      terms(FROM, "1000.00");
      long source = new DebtPlans(service).compare(SHARED, UUID.randomUUID(), FROM, "USD", new BigDecimal("100.00"), 12, "Human budget");
      var plans = new PlanLifecycle(service);
      plan = plans.create("alice", UUID.randomUUID(), source, "Debt plan", "Human selection");
      task = UUID.randomUUID();
      plans.step("alice", plan, 1, task, "Make selected payment", 1, TO, "Bank app", null, "Human task");
      plans.agree("alice", plan, 2, "Explicit agreement");
   }



   long account(String visibility, String currency)
   {
      return new FinancialRecords(service).createAccount("alice", "Synthetic account", "CREDIT_CARD", currency, false, BigDecimal.ONE, visibility, "Human statement");
   }



   void terms(LocalDate date, String principal)
   {
      new DebtPlans(service).terms("alice", account, date, new BigDecimal(principal), new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Dated human statement " + date);
   }



   long expect(PlanEffects.Kind kind)
   {
      return effects.expect(SHARED, UUID.randomUUID(), plan, 3, task, kind, account, new BigDecimal("100.00"), FROM, TO, "Explicit expected effect");
   }



   long tx(long a, String amount)
   {
      return new BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), a, FROM.plusDays(2), new BigDecimal(amount), "UNCLASSIFIED", "Payment", "Selected observation", "Human evidence");
   }



   JsonNode report(long expectation, List<Long> ids) throws Exception
   {
      return facts(effects.compare(SHARED, UUID.randomUUID(), expectation, ids, "Explicit selected evidence"));
   }



   JsonNode facts(long id) throws Exception
   {
      return new ObjectMapper().readTree(service.artifact("alice", id).get("facts").toString());
   }



   @Test
   void familyExpectedAndObservedEffectsRetainAudienceAndNeverCompleteTasks() throws Exception
   {
      long transaction = tx(account, "-100.00");
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var json = new ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var input = json.createObjectNode().put("plan", plan).put("version", 3).put("step", task.toString()).put("kind", "CASH_PAYMENT").put("account", account).put("amount", "100.00").put("from", FROM.toString()).put("through", TO.toString()).put("reason", "Explicit shared expected payment");
         var expected = workflows.handlers().get("plan-expectation");
         UUID request = UUID.randomUUID();
         expected.start(context, request, input);
         var result = await(expected, context, request);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.COMPLETE, result.status());
         long expectation = Long.parseLong(result.artifactId());
         var comparison = json.createObjectNode().put("expectation", expectation).put("evidence", "Selected posted observation");
         comparison.putArray("transactions").add(transaction);
         var observed = workflows.handlers().get("plan-effect-comparison");
         UUID observedRequest = UUID.randomUUID();
         observed.start(context, observedRequest, comparison);
         var actual = await(observed, context, observedRequest);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, actual.status());
         assertEquals("MATCH", facts(Long.parseLong(actual.artifactId())).path("outcome").asText());
         assertEquals("TODO", service.transaction(c -> CarlService.rows(c, "SELECT status FROM carl_plan_step WHERE id=?", task).getFirst().get("status")));
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> expected.start(context, UUID.randomUUID(), input.deepCopy().put("caller", "bob")));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> expected.get(context, request));
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> observed.get(context, observedRequest));
      }
   }



   private static com.kof22.agentadmin.client.ClientWorkflow.Result await(com.kof22.agentadmin.client.ClientWorkflow handler, com.kof22.agentadmin.client.ClientWorkflow.Context context, UUID request) throws Exception
   {
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      do
      {
         var result = handler.get(context, request);
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
   void cashMatchAndDifferenceDoNotVerifyTasks() throws Exception
   {
      long e = expect(PlanEffects.Kind.CASH_PAYMENT);
      long t = tx(account, "-100.00");
      var report = report(e, List.of(t));
      assertEquals("MATCH", report.path("outcome").asText());
      assertEquals(0, report.path("differenceObservedMinusExpected").decimalValue().compareTo(BigDecimal.ZERO));
      assertEquals("TODO", service.transaction(c -> CarlService.rows(c, "SELECT status FROM carl_plan_step WHERE id=?", task).getFirst().get("status")));
      assertEquals("DIFFERENCE", report(e, List.of(tx(account, "-90.00"))).path("outcome").asText());
      assertEquals("UNDETERMINED", report(e, List.of()).path("outcome").asText());
   }



   @Test
   void expectedAndReportRequestsArePayloadBound() throws Exception
   {
      UUID id = UUID.randomUUID();
      long e = effects.expect(SHARED, id, plan, 3, task, PlanEffects.Kind.CASH_PAYMENT, account, new BigDecimal("100.00"), FROM, TO, "Chosen");
      assertEquals(e, effects.expect(SHARED, id, plan, 3, task, PlanEffects.Kind.CASH_PAYMENT, account, new BigDecimal("100.00"), FROM, TO, "Chosen"));
      assertThrows(IllegalArgumentException.class, () -> effects.expect(SHARED, id, plan, 3, task, PlanEffects.Kind.CASH_PAYMENT, account, new BigDecimal("101.00"), FROM, TO, "Chosen"));
      long t = tx(account, "-100");
      UUID report = UUID.randomUUID();
      long saved = effects.compare(SHARED, report, e, List.of(t), "Selected");
      assertEquals(saved, effects.compare(SHARED, report, e, List.of(t), "Selected"));
      assertThrows(IllegalArgumentException.class, () -> effects.compare(SHARED, report, e, List.of(), "Selected"));
   }



   @Test
   void principalNeedsLaterDatedStatementNotImportedBalanceOrPayment() throws Exception
   {
      long e = expect(PlanEffects.Kind.PRINCIPAL_REDUCTION);
      long t = tx(account, "-100");
      sql("INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(" + account + ",'2026-09-30',-900,'STATEMENT','Imported total balance only')");
      assertEquals("UNDETERMINED", report(e, List.of(t)).path("outcome").asText());
      terms(TO, "900.00");
      var result = report(e, List.of(t));
      assertEquals("MATCH", result.path("outcome").asText());
      assertTrue(result.path("boundary").asText().contains("adjustments or advances"));
      assertTrue(result.path("closingPrincipalTerms").path("terms_evidence").asText().contains("2026-09-30"));
   }



   @Test
   void pairsCountOnceAndRejectDuplicatesMissingLegAndPrivateAccount() throws Exception
   {
      long destination = account("FAMILY", "USD");
      long e = expect(PlanEffects.Kind.TRANSFER);
      long out = tx(account, "-100");
      long in = tx(destination, "100");
      new FinancialRecords(service).pairTransfer("alice", out, in, "Human matched pair");
      assertEquals("MATCH", report(e, List.of(out, in)).path("outcome").asText());
      assertThrows(IllegalArgumentException.class, () -> report(e, List.of(out, out)));
      assertThrows(IllegalArgumentException.class, () -> report(e, List.of(out)));
      long privateAccount = account("PRIVATE", "USD");
      long hidden = tx(privateAccount, "100");
      assertThrows(SecurityException.class, () -> report(e, List.of(hidden)));
   }



   @Test
   void mismatchedCurrencyDatesAndAudienceFailClosed()
   {
      long e = expect(PlanEffects.Kind.CASH_PAYMENT);
      long eur = tx(account("FAMILY", "EUR"), "-100");
      assertThrows(IllegalArgumentException.class, () -> report(e, List.of(eur)));
      long t = tx(account, "-100");
      sql("UPDATE carl_transaction SET effective_date='2026-10-01' WHERE record_id=" + t);
      assertThrows(IllegalArgumentException.class, () -> report(e, List.of(t)));
      assertThrows(SecurityException.class, () -> effects.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), e, List.of(), "Narrow audience"));
      assertThrows(SecurityException.class, () -> effects.expect(SHARED, UUID.randomUUID(), plan, 3, task, PlanEffects.Kind.CASH_PAYMENT, account("PRIVATE", "USD"), new BigDecimal("100"), FROM, TO, "Private"));
   }



   @Test
   void laterPlanVersionPreservesOldExpectationAndRevocationBlocksSavedResults() throws Exception
   {
      long e = expect(PlanEffects.Kind.CASH_PAYMENT);
      long t = tx(account, "-100");
      long saved = effects.compare(SHARED, UUID.randomUUID(), e, List.of(t), "Selected");
      new PlanLifecycle(service).step("alice", plan, 3, task, "Changed plan", 1, TO, "Home", null, "New plan");
      assertEquals("UNDETERMINED", report(e, List.of(t)).path("outcome").asText());
      assertEquals(3L, service.<Long>transaction(c -> CarlService.number(CarlService.rows(c, "SELECT plan_version FROM carl_plan_effect WHERE record_id=?", e).getFirst(), "plan_version")).longValue());
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
      assertThrows(SecurityException.class, () -> service.artifact("bob", saved));
      assertThrows(SecurityException.class, () -> report(e, List.of(t)));
   }



   @Test
   void changedImportRevisionMakesSavedObservationStaleWithoutDuplicateCounting() throws Exception
   {
      long e = expect(PlanEffects.Kind.CASH_PAYMENT);
      String csv = "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-03,Payment,Transfer,Card,Payment,Synthetic,-100.00,,Alice,true,synthetic-payment-1\n";
      var finances = new FinancialRecords(service);
      finances.importTransactions("alice", UUID.randomUUID(), csv, java.util.Map.of("Card", account), false);
      long t = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT record_id FROM carl_transaction WHERE account_id=?", account).getFirst(), "record_id"));
      long saved = effects.compare(SHARED, UUID.randomUUID(), e, List.of(t), "Selected imported row");
      finances.importTransactions("alice", UUID.randomUUID(), csv, java.util.Map.of("Card", account), false);
      assertEquals("MATCH", facts(saved).path("outcome").asText());
      assertEquals(1, service.<Integer>transaction(c -> CarlService.rows(c, "SELECT record_id FROM carl_transaction WHERE account_id=?", account).size()).intValue());
      finances.classify("alice", t, "DEBT_INTEREST", "Interest", "Human corrected classification");
      assertEquals(true, service.artifact("alice", saved).get("stale"));
   }



   @Test
   void futureDatedPaymentsAndTransfersCannotCountAsObserved()
   {
      LocalDate future = LocalDate.now(java.time.Clock.systemUTC()).plusDays(1);
      long payment = effects.expect(SHARED, UUID.randomUUID(), plan, 3, task, PlanEffects.Kind.CASH_PAYMENT, account, new BigDecimal("100.00"), future, future.plusDays(30), "Future expectation");
      long outgoing = new BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), account, future, new BigDecimal("-100.00"), "UNCLASSIFIED", "Payment", "Future dated", "Supplied future entry");
      assertThrows(IllegalArgumentException.class, () -> report(payment, List.of(outgoing)));
      long transfer = effects.expect(SHARED, UUID.randomUUID(), plan, 3, task, PlanEffects.Kind.TRANSFER, account, new BigDecimal("100.00"), future, future.plusDays(30), "Future transfer expectation");
      long destination = account("FAMILY", "USD");
      long incoming = new BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), destination, future, new BigDecimal("100.00"), "UNCLASSIFIED", "Transfer", "Future incoming", "Supplied future entry");
      new FinancialRecords(service).pairTransfer("alice", outgoing, incoming, "Human paired future rows");
      assertThrows(IllegalArgumentException.class, () -> report(transfer, List.of(outgoing, incoming)));
   }



   @Test
   void observationCutoffUsesHouseholdDateRatherThanUtcMidnight()
   {
      long expectation = expect(PlanEffects.Kind.CASH_PAYMENT);
      long observation = new BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), account, TO, new BigDecimal("-100.00"), "UNCLASSIFIED", "Payment", "Dated September30", "Synthetic dated evidence");
      var beforeMidnight = new PlanEffects(service, Clock.fixed(java.time.Instant.parse("2026-09-30T02:00:00Z"), java.time.ZoneOffset.UTC));
      assertThrows(IllegalArgumentException.class, () -> beforeMidnight.compare(SHARED, UUID.randomUUID(), expectation, List.of(observation), "Selected observation"));
      var afterMidnight = new PlanEffects(service, Clock.fixed(java.time.Instant.parse("2026-09-30T07:00:00Z"), java.time.ZoneOffset.UTC));
      assertTrue(afterMidnight.compare(SHARED, UUID.randomUUID(), expectation, List.of(observation), "Selected observation") > 0);
   }



   static void sql(String text)
   {
      try(var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); var s = c.createStatement())
      {
         s.execute(text);
      }
      catch(Exception e)
      {
         throw new IllegalStateException(e);
      }
   }
}
