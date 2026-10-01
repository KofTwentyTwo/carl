/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExpenseRecordsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static ExpenseRecords expenses;
   private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);
   private static final LocalDate ASOF = LocalDate.of(2026, 9, 10);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static ExpenseRecords.Schedule schedule(String amount)
   {
      return new ExpenseRecords.Schedule("USD", ExpenseForecast.Cadence.MONTHLY, LocalDate.of(2026, 1, 15), null, amount == null ? null : n(amount), Map.of(9, n("400.00")), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.COMMITTED, null);
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
      expenses = new ExpenseRecords(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic expenses','America/Chicago')");
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



   @Test
   void seasonalObligationAndActualSettlementConstrainForecastWithoutDoubleCounting() throws Exception
   {
      UUID request = UUID.randomUUID();
      long expense = expenses.create("alice", request, "Power", "FAMILY", "Synthetic seasonal invoice", schedule("350.00"));
      assertEquals(expense, expenses.create("alice", request, "Power", "FAMILY", "Synthetic seasonal invoice", schedule("350.00")));
      long actual = expenses.manualActual("alice", UUID.randomUUID(), ASOF, "USD", n("100.00"), ExpenseForecast.Kind.EXPENSE, "FAMILY", "Synthetic payment evidence");
      expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("100.00"), "Explicit September allocation");
      long report = expenses.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(expense), Set.of(actual), FROM, THROUGH, ASOF);
      String facts = service.artifact("bob", report).get("facts").toString();
      assertEquals("USD", new com.fasterxml.jackson.databind.ObjectMapper().readTree(facts).path("currency").asText(), "Saved forecast retains explicit currency at its root");
      assertTrue(facts.contains("\"knownScheduledExpenses\":400.00"));
      assertTrue(facts.contains("\"actualExpenses\":100.00"));
      assertTrue(facts.contains("\"remainingExpenses\":300.00"));
      assertTrue(facts.contains("\"amount\":-300.00"));
      var cash = new CashPlans(service);
      long plan = cash.create("alice", "Synthetic cash", "FAMILY", "Reviewed synthetic cash", new CashPlans.Assumptions("USD", FROM, THROUGH, n("500.00"), n("100.00"), n("300.00"), true, true, true, true, true, true));
      expenses.attachCashPlan("alice", UUID.randomUUID(), plan, Set.of(expense), Set.of(actual), "Selected obligations and actuals; no duplicate cash events");
      var inputs = expenses.cashInputs(new CarlService.Scope("alice", Set.of("alice", "bob")), plan, ASOF);
      assertEquals(n("300.00"), inputs.projection().remainingExpenses());
      assertEquals(n("400.00"), inputs.projection().cashEvents().stream().map(event -> event.amount().negate()).reduce(n("0.00"), BigDecimal::add));
      assertTrue(inputs.completeForSelectedInputs());
   }



   @Test
   void capacityCorrectionsAndPermissionRevocationRemainExplicit()
   {
      long expense = expenses.create("alice", UUID.randomUUID(), "Gas", "FAMILY", "Synthetic invoice", schedule("350.00"));
      long actual = expenses.manualActual("alice", UUID.randomUUID(), ASOF, "USD", n("100.00"), ExpenseForecast.Kind.EXPENSE, "FAMILY", "Synthetic payment");
      long link = expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("80.00"), "Partial payment");
      var nativeRows = service.transaction(c -> CarlService.rows(c, "SELECT currency FROM carl_expense_settlement_view WHERE principal=?", "alice"));
      assertEquals("USD", nativeRows.getFirst().get("currency"));
      assertThrows(IllegalArgumentException.class, () -> expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("30.00"), "Over-capacity"));
      long revision = CarlService.number(expenses.records(CarlService.Scope.privateFor("alice"), "expenses").getFirst(), "revision");
      assertThrows(IllegalArgumentException.class, () -> expenses.correct("alice", UUID.randomUUID(), expense, revision, schedule("300.00"), "Must review linked occurrences first"));
      expenses.unsettle("alice", UUID.randomUUID(), link, "Correcting allocation");
      expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("100.00"), "Replacement allocation");
      long report = expenses.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(expense), Set.of(actual), FROM, THROUGH, ASOF);
      assertNotNull(service.artifact("bob", report));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2");
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));
      assertThrows(SecurityException.class, () -> expenses.records(CarlService.Scope.privateFor("unmapped"), "expenses"));
   }



   @Test
   void missingAmountReservesAndUnselectedPaymentsNeverBecomeExtraBudget()
   {
      var unknown = new ExpenseRecords.Schedule("USD", ExpenseForecast.Cadence.ONCE, LocalDate.of(2026, 9, 15), null, null, Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.ESTIMATED, null);
      long expense = expenses.create("alice", UUID.randomUUID(), "Maintenance", "FAMILY", "Amount pending", unknown);
      long report = expenses.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(expense), Set.of(), FROM, THROUGH, ASOF);
      String facts = service.artifact("alice", report).get("facts").toString();
      assertTrue(facts.contains("\"expectedAmount\":null"));
      assertTrue(facts.contains("\"cashCoverageComplete\":false"));
      long reserve = expenses.create("alice", UUID.randomUUID(), "Repair reserve", "FAMILY", "Reserve assumption", new ExpenseRecords.Schedule("USD", ExpenseForecast.Cadence.ONCE, LocalDate.of(2026, 9, 20), null, n("75.00"), Map.of(), ExpenseForecast.Kind.RESERVE_EARMARK, ExpenseForecast.Basis.COMMITTED, null));
      long reserved = expenses.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(reserve), Set.of(), FROM, THROUGH, ASOF);
      String reservedFacts = service.artifact("alice", reserved).get("facts").toString();
      assertTrue(reservedFacts.contains("\"remainingReserveEarmarks\":75.00"));
      assertTrue(reservedFacts.contains("\"cashEvents\":[]"));
   }



   @Test
   void privateSourcesAndStaleActualsAreNeverPresentedAsVerifiedPayment()
   {
      long expense = expenses.create("alice", UUID.randomUUID(), "Power", "FAMILY", "Synthetic bill", schedule("350.00"));
      long actual = expenses.manualActual("alice", UUID.randomUUID(), ASOF, "USD", n("100.00"), ExpenseForecast.Kind.EXPENSE, "PRIVATE", "Private payment");
      assertThrows(SecurityException.class, () -> expenses.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(expense), Set.of(actual), FROM, THROUGH, ASOF));
      assertThrows(SecurityException.class, () -> expenses.create("bob", UUID.randomUUID(), "Unauthorized", "FAMILY", "No manager permission", schedule("1.00")));
      assertFalse(expenses.records(new CarlService.Scope("alice", Set.of("alice", "bob")), "actuals").stream().anyMatch(row -> CarlService.number(row, "id") == actual));
   }



   @Test
   void importedActualSourceChangesMarkForecastStaleAndRequireReview()
   {
      var finances = new FinancialRecords(service);
      long account = finances.createAccount("alice", "Synthetic checking", "CASH", "USD", true, BigDecimal.ONE, "FAMILY", "Synthetic statement");
      finances.importTransactions("alice", UUID.randomUUID(), "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-10,Synthetic,Power,Checking,Synthetic,Source note,-100.00,,,,expense-source\n", Map.of("Checking", account), false);
      long transaction = service.view(CarlService.Scope.privateFor("alice"), "transactions").stream().mapToLong(row -> CarlService.number(row, "id")).findFirst().orElseThrow();
      long actual = expenses.classifyActual("alice", UUID.randomUUID(), transaction, ExpenseForecast.Kind.EXPENSE, "FAMILY", "Reviewed imported power payment");
      long expense = expenses.create("alice", UUID.randomUUID(), "Power", "FAMILY", "Synthetic bill", schedule("350.00"));
      expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("100.00"), "September payment");
      long report = expenses.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(expense), Set.of(actual), FROM, THROUGH, ASOF);
      sql("UPDATE carl_transaction SET amount=-120.00 WHERE record_id=" + transaction);
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + transaction);
      assertEquals(Boolean.TRUE, service.artifact("alice", report).get("stale"));
      long changed = expenses.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(expense), Set.of(actual), FROM, THROUGH, ASOF);
      assertTrue(service.artifact("alice", changed).get("facts").toString().contains("source changed"));
      assertTrue(service.artifact("alice", changed).get("status_label").toString().startsWith("Incomplete"));
      assertThrows(IllegalArgumentException.class, () -> expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("1.00"), "Stale source cannot add payment"));
      long revision = CarlService.number(expenses.records(CarlService.Scope.privateFor("alice"), "actuals").getFirst(), "revision");
      assertThrows(IllegalArgumentException.class, () -> expenses.refreshActual("alice", UUID.randomUUID(), actual, revision, "Must review active settlement first"));
      long link = expenses.records(CarlService.Scope.privateFor("alice"), "settlements").stream().filter(row -> Boolean.TRUE.equals(row.get("active"))).mapToLong(row -> CarlService.number(row, "id")).findFirst().orElseThrow();
      expenses.unsettle("alice", UUID.randomUUID(), link, "Source amount changed; reviewing settlement");
      long currentRevision = CarlService.number(expenses.records(CarlService.Scope.privateFor("alice"), "actuals").getFirst(), "revision");
      UUID refresh = UUID.randomUUID();
      expenses.refreshActual("alice", refresh, actual, currentRevision, "Reviewed updated imported amount");
      expenses.refreshActual("alice", refresh, actual, currentRevision, "Reviewed updated imported amount");
      expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("120.00"), "Reviewed replacement payment allocation");
      long recovered = expenses.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(expense), Set.of(actual), FROM, THROUGH, ASOF);
      assertTrue(service.artifact("alice", recovered).get("facts").toString().contains("\"remainingExpenses\":280.00"));
      assertFalse(service.artifact("alice", recovered).get("facts").toString().contains("source changed"));
      sql("UPDATE carl_record SET visibility='PRIVATE' WHERE id=" + account);
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));
      assertFalse(expenses.records(new CarlService.Scope("alice", Set.of("alice", "bob")), "actuals").stream().anyMatch(row -> CarlService.number(row, "id") == actual));
   }



   @Test
   void scheduleCorrectionsRemainAttributedAndSelectedCashPlanAccessIsRechecked()
   {
      long expense = expenses.create("alice", UUID.randomUUID(), "Gas", "FAMILY", "Original invoice", schedule("350.00"));
      long report = expenses.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(expense), Set.of(), FROM, THROUGH, ASOF);
      long revision = CarlService.number(expenses.records(CarlService.Scope.privateFor("alice"), "expenses").getFirst(), "revision");
      UUID correction = UUID.randomUUID();
      expenses.correct("alice", correction, expense, revision, schedule("300.00"), "Reviewed statement change");
      expenses.correct("alice", correction, expense, revision, schedule("300.00"), "Reviewed statement change");
      assertEquals(Boolean.TRUE, service.artifact("alice", report).get("stale"));
      assertThrows(IllegalArgumentException.class, () -> expenses.correct("alice", UUID.randomUUID(), expense, revision, schedule("200.00"), "Old revision"));
      long actual = expenses.manualActual("alice", UUID.randomUUID(), ASOF, "USD", n("100.00"), ExpenseForecast.Kind.EXPENSE, "PRIVATE", "Private payment");
      expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("100.00"), "Private receipt allocation");
      assertThrows(SecurityException.class, () -> expenses.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(expense), Set.of(), FROM, THROUGH, ASOF));
      long plan = new CashPlans(service).create("alice", "Synthetic cash", "FAMILY", "Reviewed inputs", new CashPlans.Assumptions("USD", FROM, THROUGH, n("500.00"), n("100.00"), n("300.00"), true, true, true, true, true, true));
      expenses.attachCashPlan("alice", UUID.randomUUID(), plan, Set.of(expense), Set.of(actual), "Selected private payment source");
      assertThrows(SecurityException.class, () -> expenses.cashInputs(new CarlService.Scope("alice", Set.of("alice", "bob")), plan, ASOF));
      assertThrows(IllegalArgumentException.class, () -> expenses.manualActual("alice", UUID.randomUUID(), ASOF, "USD", n("0.00"), ExpenseForecast.Kind.EXPENSE, "PRIVATE", "Zero is not a payment"));
      assertThrows(IllegalArgumentException.class, () -> expenses.create("alice", UUID.randomUUID(), "Invalid", "PRIVATE", "Unknown cadence", new ExpenseRecords.Schedule("USD", null, FROM, null, n("1.00"), Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.COMMITTED, null)));
   }



   @Test
   void extremeDecimalSettlementInputsAreRejectedBeforeRescaling()
   {
      long expense = expenses.create("alice", UUID.randomUUID(), "Power", "FAMILY", "Synthetic bill", schedule("350.00"));
      long actual = expenses.manualActual("alice", UUID.randomUUID(), ASOF, "USD", n("100.00"), ExpenseForecast.Kind.EXPENSE, "FAMILY", "Synthetic payment");
      assertThrows(IllegalArgumentException.class, () -> expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("1E-1000000000"), "Invalid extreme scale"));
      assertThrows(IllegalArgumentException.class, () -> expenses.settle("alice", UUID.randomUUID(), expense, LocalDate.of(2026, 9, 15), actual, n("1E+1000000000"), "Invalid extreme magnitude"));
      assertTrue(expenses.records(CarlService.Scope.privateFor("alice"), "settlements").isEmpty());
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
