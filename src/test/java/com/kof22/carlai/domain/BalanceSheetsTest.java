/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BalanceSheetsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
   private static CarlService service;
   private static BalanceSheets sheets;
   private static FinancialRecords finance;
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
      sheets = new BalanceSheets(service, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
      finance = new FinancialRecords(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic balance household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Family',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['FINANCE','TAX']) d");
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
      sql("UPDATE carl_member SET principal='race-secondary' WHERE id=3");
      sql("UPDATE carl_member SET principal='alice' WHERE id=1");
      sql("UPDATE carl_member SET active=true,household_id=1");
      sql("UPDATE carl_permission SET details=true");
   }



   private long account(String kind, String currency, String value, String visibility)
   {
      long id = finance.createAccount("alice", kind, kind, currency, kind.equals("CASH"), BigDecimal.ONE, visibility, "Synthetic supplied ownership");
      if(value != null)
      {
         observation(id, DATE, value, "CURRENT");
      }
      return id;
   }



   private void observation(long account, LocalDate date, String value, String basis)
   {
      sql("INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(" + account + ",'" + date + "'," + value + ",'" + basis + "','Synthetic observation')");
   }



   private long property(String amount, LocalDate date, BigDecimal share, Long asset, Long debt, String visibility)
   {
      return new RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Synthetic property", visibility, "Synthetic valuation supplied by human", new RentalRecords.PropertyValues("USD", "Chester, Randolph County, Illinois", share, asset, debt, null, amount == null ? null : new BigDecimal(amount), date, null, null, null, null, null));
   }



   private long report(List<Long> accounts, List<BalanceSheets.PropertyChoice> properties)
   {
      return sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), DATE, 30, accounts, properties);
   }



   private com.fasterxml.jackson.databind.JsonNode facts(long id) throws Exception
   {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(service.artifact("alice", id).get("facts").toString());
   }



   private BalanceSheets.PropertyChoice estimated(long property)
   {
      return new BalanceSheets.PropertyChoice(property, BalanceSheets.Valuation.PROPERTY_ESTIMATE);
   }



   @Test
   void familyBalanceAndStressUseProtectedBoundedOutputs() throws Exception
   {
      long cash = account("CASH", "USD", "1000", "FAMILY");
      long property = property("10000", DATE, BigDecimal.ONE, null, null, "FAMILY");
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long baseline = baseline(property, scope);
      long scenario = new RentalStress(service).create("alice", UUID.randomUUID(), "Synthetic scenario", "FAMILY", new RentalStress.Assumptions(baseline, property, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, DATE, "Synthetic assumptions only"));
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var input = json.createObjectNode().put("asOf", DATE.toString()).put("maximumAgeDays", 30);
         input.putArray("accounts").add(cash);
         input.putArray("estimatedProperties").add(property);
         input.putArray("linkedProperties");
         var balance = workflows.handlers().get("balance-sheet");
         UUID request = UUID.randomUUID();
         balance.start(context, request, input);
         var result = await(balance, context, request);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, result.status());
         assertTrue(result.toString().contains("artifact-section"));
         var stress = workflows.handlers().get("rental-stress-report");
         UUID stressRequest = UUID.randomUUID();
         stress.start(context, stressRequest, json.createObjectNode().put("scenario", scenario));
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, await(stress, context, stressRequest).status());
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> balance.start(context, UUID.randomUUID(), input.deepCopy().put("caller", "bob")));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> balance.get(context, request));
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> stress.get(context, stressRequest));
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
   void propertyAssetsAndSharedMortgageAreCountedExactlyOnceWithSeparateCurrencies() throws Exception
   {
      long asset = account("OTHER_ASSET", "USD", "90000", "FAMILY");
      long debt = account("LOAN", "USD", "-80000", "FAMILY");
      long cash = account("CASH", "USD", "1000", "FAMILY");
      long euro = account("CASH", "EUR", "99", "FAMILY");
      long a = property("100000", DATE, BigDecimal.ONE, asset, debt, "FAMILY");
      long b = property("50000", DATE, new BigDecimal("0.5"), null, debt, "FAMILY");
      var result = facts(report(List.of(asset, debt, cash, euro), List.of(estimated(a), estimated(b))));
      assertEquals(0, new BigDecimal("46000").compareTo(result.path("knownSelectedNetWorthByCurrency").path("USD").decimalValue()));
      assertEquals(0, new BigDecimal("99").compareTo(result.path("knownSelectedNetWorthByCurrency").path("EUR").decimalValue()));
      assertEquals(4, result.path("accounts").size());
      assertTrue(result.toString().contains("EXCLUDED_DUPLICATE_PROPERTY_ASSET"));
      var linked = facts(report(List.of(), List.of(new BalanceSheets.PropertyChoice(a, BalanceSheets.Valuation.LINKED_ACCOUNT))));
      assertEquals(0, new BigDecimal("10000").compareTo(linked.path("knownSelectedNetWorthByCurrency").path("USD").decimalValue()));
   }



   @Test
   void missingConflictingFutureAndStaleValuesRemainExplicitGaps() throws Exception
   {
      long missing = account("CASH", "USD", null, "FAMILY");
      long conflict = account("CASH", "USD", "100", "FAMILY");
      long stale = account("CASH", "EUR", null, "FAMILY");
      observation(conflict, DATE, "200", "STATEMENT");
      observation(stale, DATE.minusDays(31), "50", "CURRENT");
      observation(stale, DATE.plusDays(1), "999", "CURRENT");
      long unknown = property("200", DATE, null, null, null, "FAMILY");
      long future = property("500", DATE.plusDays(1), BigDecimal.ONE, null, null, "FAMILY");
      var result = facts(report(List.of(missing, conflict, stale), List.of(estimated(unknown), estimated(future))));
      assertFalse(result.path("knownSelectedNetWorthByCurrency").has("USD"));
      assertEquals(0, new BigDecimal("50").compareTo(result.path("knownSelectedNetWorthByCurrency").path("EUR").decimalValue()));
      assertTrue(result.toString().contains("conflicting"));
      assertTrue(result.toString().contains("after asOf"));
      assertTrue(result.toString().contains("ownership is unknown"));
      assertTrue(result.toString().contains("stale"));
   }



   @Test
   void sharedTypedDependenciesRetryRevocationAndStaleness() throws Exception
   {
      long debt = account("LOAN", "USD", "-20", "PRIVATE");
      long p = property("100", DATE, BigDecimal.ONE, null, debt, "FAMILY");
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      assertThrows(SecurityException.class, () -> sheets.report(shared, UUID.randomUUID(), DATE, 30, List.of(), List.of(estimated(p))));
      var request = UUID.randomUUID();
      long id = sheets.report(CarlService.Scope.privateFor("alice"), request, DATE, 30, List.of(), List.of(estimated(p)));
      assertEquals(id, sheets.report(CarlService.Scope.privateFor("alice"), request, DATE, 30, List.of(), List.of(estimated(p))));
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + debt);
      assertEquals(true, service.artifact("alice", id).get("stale"));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
      assertThrows(SecurityException.class, () -> service.artifact("alice", id));
      assertThrows(SecurityException.class, () -> sheets.report(CarlService.Scope.privateFor("bob"), UUID.randomUUID(), DATE, 30, List.of(debt), List.of()));
   }



   @Test
   void ownershipRoundsOnceAtCurrencyPrecisionAndMissingValuationDoesNotFallback() throws Exception
   {
      long asset = account("OTHER_ASSET", "USD", "999", "FAMILY");
      long missing = property(null, null, BigDecimal.ONE, asset, null, "FAMILY");
      long penny = property("0.03", DATE, new BigDecimal("0.5"), null, null, "FAMILY");
      var result = facts(report(List.of(asset), List.of(estimated(missing), estimated(penny))));
      assertEquals(new BigDecimal("0.02"), result.path("knownSelectedNetWorthByCurrency").path("USD").decimalValue());
      assertTrue(result.toString().contains("valuation missing"));
   }



   private long baseline(long property, CarlService.Scope scope)
   {
      return new RentalRecords(service).report(scope, UUID.randomUUID(), Set.of(property), LocalDate.of(2026, 9, 1), DATE, DATE);
   }



   @Test
   void rentalStressComponentsConserveCashAndDoNotPretendToBeLoanPayments() throws Exception
   {
      long debt = account("LOAN", "USD", "-100000", "FAMILY");
      long p = property("150000", DATE, new BigDecimal("0.5"), null, debt, "FAMILY");
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long baseline = baseline(p, shared);
      var stress = new RentalStress(service);
      var value = new RentalStress.Assumptions(baseline, p, new BigDecimal("2000"), new BigDecimal("0.25"), new BigDecimal("100"), new BigDecimal("0.02"), new BigDecimal("50000"), DATE, "Synthetic adverse replay; constant allocated principal and expected rent for this interval");
      var request = UUID.randomUUID();
      long scenario = stress.create("alice", request, "Synthetic shock", "FAMILY", value);
      assertEquals(scenario, stress.create("alice", request, "Synthetic shock", "FAMILY", value));
      var reportRequest = UUID.randomUUID();
      long id = stress.report(shared, reportRequest, scenario);
      assertEquals(id, stress.report(shared, reportRequest, scenario));
      var result = facts(id);
      assertEquals(0, new BigDecimal("500").compareTo(result.path("hypotheticalVacancyLoss").decimalValue()));
      assertEquals(new BigDecimal("82.19"), result.path("additionalSimpleInterest").decimalValue());
      assertEquals(new BigDecimal("682.19"), result.path("totalAdverseCashImpact").decimalValue());
      assertEquals(new BigDecimal("-682.19"), result.path("hypotheticalCashAfterReserveTransfers").decimalValue());
      assertEquals(0, new BigDecimal("341.10").compareTo(result.path("ownershipAttributedImpact").decimalValue()));
      assertTrue(result.toString().contains("Not an amortizing loan payment"));
      assertEquals("2026-09-30", result.path("ratePrincipalObservations").get(0).path("as_of").asText());
      assertEquals(0, new BigDecimal("-100000").compareTo(result.path("ratePrincipalObservations").get(0).path("amount").decimalValue()));
      assertFalse(service.artifact("bob", id).isEmpty());
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + p);
      assertEquals(true, service.artifact("alice", id).get("stale"));
      assertThrows(IllegalArgumentException.class, () -> stress.report(shared, UUID.randomUUID(), scenario));
   }



   @Test
   void stressRejectsPrivateBaselineSharedAudienceAndUnboundedInputs()
   {
      long p = property("100", DATE, BigDecimal.ONE, null, null, "FAMILY");
      long base = baseline(p, CarlService.Scope.privateFor("alice"));
      var stress = new RentalStress(service);
      var value = new RentalStress.Assumptions(base, p, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, DATE, "Synthetic no-rate-shock");
      long scenario = stress.create("alice", UUID.randomUUID(), "Private baseline scenario", "FAMILY", value);
      assertThrows(SecurityException.class, () -> stress.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), scenario));
      assertThrows(IllegalArgumentException.class, () -> stress.create("alice", UUID.randomUUID(), "Invalid", "PRIVATE", new RentalStress.Assumptions(base, p, new BigDecimal("1E+1000000000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, DATE, "Bad amount")));
      assertThrows(IllegalArgumentException.class, () -> report(List.of(), List.of(estimated(p), estimated(p))));
   }



   @Test
   void pastBalancesUseLatestObservationWithoutInventingSourcePrecedence() throws Exception
   {
      long account = account("CASH", "USD", "100", "FAMILY");
      observation(account, DATE.minusDays(1), "90", "STATEMENT");
      observation(account, DATE.minusDays(1), "90", "HUMAN_ASSERTION");
      var request = UUID.randomUUID();
      long id = sheets.report(CarlService.Scope.privateFor("alice"), request, DATE.minusDays(1), 0, List.of(account), List.of());
      var result = facts(id);
      assertEquals(0, new BigDecimal("90").compareTo(result.path("knownSelectedNetWorthByCurrency").path("USD").decimalValue()));
      assertEquals(2, result.path("accounts").get(0).path("observations").size());
      assertThrows(IllegalArgumentException.class, () -> sheets.report(CarlService.Scope.privateFor("alice"), request, DATE, 0, List.of(account), List.of()));
      assertThrows(IllegalArgumentException.class, () -> sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), DATE.plusDays(1), 30, List.of(account), List.of()));
   }



   @Test
   void mortgageShockRequiresExactResolvedDatedPrincipalAndCannotExceedIt()
   {
      long debt = account("LOAN", "USD", "-100", "FAMILY");
      long p = property("200", DATE, BigDecimal.ONE, null, debt, "FAMILY");
      long base = baseline(p, CarlService.Scope.privateFor("alice"));
      var stress = new RentalStress(service, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
      assertThrows(IllegalArgumentException.class, () -> stress.create("alice", UUID.randomUUID(), "Too large", "PRIVATE", new RentalStress.Assumptions(base, p, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.1"), new BigDecimal("101"), DATE, "Synthetic oversized principal")));
      assertThrows(IllegalArgumentException.class, () -> stress.create("alice", UUID.randomUUID(), "Missing date", "PRIVATE", new RentalStress.Assumptions(base, p, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.1"), BigDecimal.TEN, DATE.minusDays(1), "Synthetic missing exact date")));
      assertThrows(IllegalArgumentException.class, () -> stress.create("alice", UUID.randomUUID(), "Future date", "PRIVATE", new RentalStress.Assumptions(base, p, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.1"), BigDecimal.TEN, DATE.plusDays(1), "Synthetic future principal")));
      observation(debt, DATE, "-90", "STATEMENT");
      assertThrows(IllegalArgumentException.class, () -> stress.create("alice", UUID.randomUUID(), "Conflicting", "PRIVATE", new RentalStress.Assumptions(base, p, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.1"), BigDecimal.TEN, DATE, "Synthetic conflicting principal")));
   }



   @Test
   void ownershipStressRoundsComponentsThenRecomputesDerivedCash() throws Exception
   {
      long p = property("1.00", DATE, new BigDecimal("0.5"), null, null, "FAMILY");
      long base = baseline(p, CarlService.Scope.privateFor("alice"));
      var stress = new RentalStress(service);
      long input = stress.create("alice", UUID.randomUUID(), "Penny stress", "PRIVATE", new RentalStress.Assumptions(base, p, new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("0.01"), BigDecimal.ZERO, BigDecimal.ZERO, DATE, "Explicit penny rounding fixture"));
      var result = facts(stress.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), input));
      assertEquals(new BigDecimal("0.02"), result.path("ownershipAttributedImpact").decimalValue());
      assertEquals(new BigDecimal("-0.02"), result.path("ownershipAttributedHypotheticalCash").decimalValue());
      assertEquals(0, result.path("ownershipAttributedBaselineCash").decimalValue().subtract(result.path("ownershipAttributedImpact").decimalValue()).compareTo(result.path("ownershipAttributedHypotheticalCash").decimalValue()));
   }



   @Test
   void oneAssetAccountCannotRepresentTwoProperties()
   {
      long asset = account("OTHER_ASSET", "USD", "100", "FAMILY");
      property("100", DATE, BigDecimal.ONE, asset, null, "FAMILY");
      assertThrows(IllegalStateException.class, () -> property("200", DATE, BigDecimal.ONE, asset, null, "FAMILY"));
   }



   @Test
   void remappedPrincipalFailsClosedAfterWaitingForHouseholdLock() throws Exception
   {
      long p = property("100", DATE, BigDecimal.ONE, null, null, "FAMILY");
      long base = baseline(p, CarlService.Scope.privateFor("alice"));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(2,'Synthetic second home','America/Chicago') ON CONFLICT(id) DO NOTHING");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(3,2,'race-secondary','Synthetic remapped owner',true) ON CONFLICT(id) DO UPDATE SET household_id=2");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(3,'FINANCE',true) ON CONFLICT(member_id,domain) DO UPDATE SET details=true");
      var value = new RentalStress.Assumptions(base, p, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, DATE, "Synthetic remapping race");
      try(var executor = java.util.concurrent.Executors.newSingleThreadExecutor(); var blocker = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()))
      {
         blocker.setAutoCommit(false);
         try(var statement = blocker.createStatement())
         {
            statement.execute("SELECT id FROM carl_household WHERE id=1 FOR UPDATE");
         }
         var pending = executor.submit(() -> new RentalStress(service).create("alice", UUID.randomUUID(), "Race", "PRIVATE", value));
         boolean waiting = false;
         long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
         while(!waiting && System.nanoTime() < until)
         {
            try(var check = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = check.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM carl_household WHERE id=%FOR UPDATE'"))
            {
               result.next();
               waiting = result.getInt(1) > 0;
            }
            if(!waiting)
            {
               Thread.sleep(10);
            }
         }
         assertTrue(waiting, "Scenario must be waiting after its first membership read");
         try(var statement = blocker.createStatement())
         {
            statement.execute("UPDATE carl_member SET principal='alice-old' WHERE id=1");
            statement.execute("UPDATE carl_member SET principal='alice' WHERE id=3");
         }
         blocker.commit();
         var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, java.util.concurrent.TimeUnit.SECONDS));
         assertTrue(failure.getCause() instanceof SecurityException);
         assertTrue(failure.getCause().getMessage().contains("Household changed"));
      }
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
