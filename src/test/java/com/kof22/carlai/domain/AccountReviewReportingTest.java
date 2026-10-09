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


class AccountReviewReportingTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
   private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
   private static final ObjectMapper JSON = new ObjectMapper();
   private static CarlService service;

   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, CLOCK);
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic review regression','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(1,'TAX',true)");
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



   private long unreviewed()
   {
      long id = new FinancialRecords(service).createAccount("alice", "Synthetic unknown account", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Synthetic supplied observation");
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_account SET kind='UNCLASSIFIED',review_state='NEEDS_REVIEW',liquid=NULL,ownership_share=NULL WHERE record_id=?", id);
         CarlService.execute(c, "INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(?,?,100.00,'CURRENT','Synthetic observed amount')", id, DATE);
         long movement = CarlService.record(c, CarlService.member(c, "alice"), "FINANCE", "PRIVATE", "Synthetic movement", "Synthetic observation");
         CarlService.execute(c, "INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,classification,category,source_id) VALUES(?,?,?,-25.00,'USD','UNCLASSIFIED','Unreviewed','synthetic-review-row')", movement, id, DATE);
         return null;
      });
      return id;
   }



   @Test
   void cashActivityWithUnknownAccountFactsHasUnknownTotalsAndIsNeverCalledNonCash()
   {
      unreviewed();
      JsonNode facts = JSON.valueToTree(new DashboardFacts(service).cashFlow(CarlService.Scope.privateFor("alice"), DATE, DATE, "USD"));
      assertTrue(facts.path("inflows").isNull());
      assertTrue(facts.path("outflows").isNull());
      assertTrue(facts.path("netMovement").isNull());
      assertTrue(facts.path("classifiedSpendingAllAccountKinds").isNull());
      assertEquals(0, facts.path("nonCashAccountMovements").size());
      assertEquals(1, facts.path("unreviewedAccountMovements").size());
      assertTrue(facts.path("gaps").toString().contains("Account review"));
   }



   @Test
   void unknownOwnershipAndLiquidityAreExcludedFromOverviewWithoutLosingObservedFacts()
   {
      unreviewed();
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_transaction SET classification='EXPENSE'");
         return null;
      });
      JsonNode facts = JSON.valueToTree(new FinancialRecords(service).overview(CarlService.Scope.privateFor("alice"), DATE, DATE));
      assertTrue(facts.path("signedBalancesByCurrency").isEmpty());
      assertTrue(facts.path("liquidBalancesByCurrency").isEmpty());
      assertTrue(facts.path("classifiedFlows").isEmpty());
      assertEquals(1, facts.path("accounts").size());
      assertTrue(facts.path("gaps").toString().contains("review"));
   }



   @Test
   void selectedBalanceRetainsUnknownObservationWithoutInventingAnOwnedAmount() throws Exception
   {
      long account = unreviewed();
      long report = new BalanceSheets(service, CLOCK).report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), DATE, 30, List.of(account), List.of());
      JsonNode facts = JSON.readTree(service.artifact("alice", report).get("facts").toString());
      assertTrue(facts.path("knownSelectedNetWorthByCurrency").isEmpty());
      assertTrue(facts.path("knownSelectedLiquidBalancesByCurrency").isEmpty());
      assertTrue(facts.path("accounts").get(0).path("ownedSignedBalance").isNull());
      assertEquals(1, facts.path("accounts").get(0).path("observations").size());
      assertTrue(facts.path("gaps").toString().contains("review"));
   }



   @Test
   void linkedPropertyUnknownAccountOwnershipProducesAGapInsteadOfNullFailure() throws Exception
   {
      long account = unreviewed();
      long property = new RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Synthetic property", "PRIVATE", "Synthetic supplied facts", new RentalRecords.PropertyValues("USD", "Synthetic location", BigDecimal.ONE, null, null, null, null, null, null, null, null, null, null));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_property SET asset_account_id=? WHERE record_id=?", account, property);
         return null;
      });
      long report = new BalanceSheets(service, CLOCK).report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), DATE, 30, List.of(), List.of(new BalanceSheets.PropertyChoice(property, BalanceSheets.Valuation.LINKED_ACCOUNT)));
      JsonNode facts = JSON.readTree(service.artifact("alice", report).get("facts").toString());
      assertTrue(facts.path("gaps").toString().contains("unknown"));
      assertFalse(facts.path("knownSelectedNetWorthByCurrency").has("USD"));
   }



   @Test
   void mortgageRateShockRequiresReviewedAccountFacts()
   {
      long account = unreviewed();
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_balance SET amount=-100.00 WHERE account_id=?", account);
         return null;
      });
      var rentals = new RentalRecords(service);
      long property = rentals.createProperty("alice", UUID.randomUUID(), "Synthetic property", "PRIVATE", "Synthetic supplied facts", new RentalRecords.PropertyValues("USD", "Synthetic location", BigDecimal.ONE, null, null, null, null, null, null, null, null, null, null));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_property SET debt_account_id=? WHERE record_id=?", account, property);
         return null;
      });
      var scope = CarlService.Scope.privateFor("alice");
      long baseline = rentals.report(scope, UUID.randomUUID(), Set.of(property), DATE, DATE, DATE);
      assertThrows(IllegalArgumentException.class, () -> new RentalStress(service).create("alice", UUID.randomUUID(), "Synthetic scenario", "PRIVATE", new RentalStress.Assumptions(baseline, property, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.01"), BigDecimal.TEN, DATE, "Synthetic conditional assumptions")));
   }



   @Test
   void unknownMortgageOwnershipKeepsZeroRateReplayConditionalWithAnExplicitGap() throws Exception
   {
      long account = unreviewed();
      var rentals = new RentalRecords(service);
      long property = rentals.createProperty("alice", UUID.randomUUID(), "Synthetic property", "PRIVATE", "Synthetic supplied facts", new RentalRecords.PropertyValues("USD", "Synthetic location", BigDecimal.ONE, null, null, null, null, null, null, null, null, null, null));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_property SET debt_account_id=? WHERE record_id=?", account, property);
         return null;
      });
      var scope = CarlService.Scope.privateFor("alice");
      long baseline = rentals.report(scope, UUID.randomUUID(), Set.of(property), DATE, DATE, DATE);
      var stress = new RentalStress(service);
      long scenario = stress.create("alice", UUID.randomUUID(), "Synthetic scenario", "PRIVATE", new RentalStress.Assumptions(baseline, property, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, DATE, "Synthetic conditional assumptions"));
      long report = stress.report(scope, UUID.randomUUID(), scenario);
      JsonNode facts = JSON.readTree(service.artifact("alice", report).get("facts").toString());
      assertTrue(facts.path("coverageGaps").toString().contains("unresolved"));
   }
}
