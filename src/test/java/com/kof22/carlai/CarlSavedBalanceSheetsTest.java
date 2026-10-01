/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.actions.values.QCustomPossibleValueProvider;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kof22.carlai.domain.BalanceSheets;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DebtPlans;
import com.kof22.carlai.domain.FinancialRecords;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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


/** Declared synthetic native sessions for PVS action tests; real transport is qualified separately. */
class CarlSavedBalanceSheetsTest
{
   private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private QInstance instance;
   private long cash;
   private long first;
   private long second;
   private long debt;

   @BeforeAll
   static void start() throws Exception
   {
      DB.start();
      var source = new PGSimpleDataSource();
      source.setURL(DB.getJdbcUrl());
      source.setUser(DB.getUsername());
      source.setPassword(DB.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.systemUTC());
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic choices','America/Chicago'),(2,'Other','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Other reader',true),(3,2,'other','Other household',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(3,'FINANCE',true)");
   }



   @AfterAll
   static void stop()
   {
      DB.stop();
   }



   @AfterEach
   void clearContext()
   {
      QContext.clear();
   }



   @BeforeEach
   void seed() throws Exception
   {
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_permission SET details=true");
      var finance = new FinancialRecords(service);
      cash = finance.createAccount("alice", "Private synthetic cash", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Supplied source");
      finance.importBalances("alice", UUID.randomUUID(), "Date,Balance,Account\n2026-09-30,500.25,Synthetic\n", java.util.Map.of("Synthetic", cash), false);
      debt = new DebtPlans(service).compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), LocalDate.of(2026, 9, 1), "USD", new BigDecimal("100.00"), 12, "Declared fixture budget");
      var sheets = new BalanceSheets(service);
      first = sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), LocalDate.of(2026, 9, 30), 30, List.of(cash), List.of());
      second = sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), LocalDate.of(2026, 9, 30), 31, List.of(cash), List.of());
      assertEquals("FINANCIAL PLAN", service.artifact("alice", first).get("title"));
      assertEquals(service.artifact("alice", first).get("title"), service.artifact("alice", debt).get("title"));
      instance = new QInstance();
      instance.addBackend(new com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData().withName("agentOperations").withBackendType(com.kingsrook.qqq.backend.module.rdbms.RDBMSBackendModule.class));
      instance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData().withName("declaredSyntheticSession").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.FULLY_ANONYMOUS));
      instance.addApp(new CarlMetadata(service).produce(instance));
      session("alice");
   }



   private void session(String principal)
   {
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference(principal)).withSecurityKeyValue("userId", principal).withPermissions(Set.of("agent.role.OPERATOR.hasAccess")));
   }



   private SearchPossibleValueSourceInput input()
   {
      assertNotNull(instance.getPossibleValueSource("carlSavedBalanceSheets"), "The native balance selector needs a dedicated current-permission PVS");
      return new SearchPossibleValueSourceInput().withPossibleValueSourceName("carlSavedBalanceSheets");
   }



   private List<com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue<?>> choices(SearchPossibleValueSourceInput input) throws Exception
   {
      return new SearchPossibleValueSourceAction().execute(input).getResults();
   }



   @Test
   void duplicateFinancialTitlesYieldTwoDistinctEligibleBalanceSheetsOnly() throws Exception
   {
      var choices = choices(input());
      assertEquals(2, choices.size());
      assertEquals(Set.of(first, second), choices.stream().map(value -> Long.parseLong(value.getId().toString())).collect(java.util.stream.Collectors.toSet()));
      assertEquals(2, choices.stream().map(value -> value.getLabel()).distinct().count());
      for(var choice : choices)
      {
         assertTrue(choice.getLabel().contains("#" + choice.getId()));
         assertTrue(choice.getLabel().contains("2026-09-30"));
         assertTrue(choice.getLabel().contains("Current"));
      }
      assertEquals("carlSavedBalanceSheets", instance.getWidget("carlBalanceSheet").getDropdowns().getFirst().getPossibleValueSourceName());
      assertEquals("FINANCIAL PLAN", service.artifact("alice", first).get("title"), "No artifact title rewriting");
   }



   @Test
   void exactIdsSearchAndLabelsCannotWidenTheEligibleProtectedSet() throws Exception
   {
      assertEquals(1, choices(input().withIdList(List.of(Long.toString(first)))).size());
      assertEquals(0, choices(input().withIdList(List.of(Long.toString(debt)))).size());
      assertEquals(1, choices(input().withSearchTerm("#" + second)).size());
      String label = choices(input().withIdList(List.of(first))).getFirst().getLabel();
      assertEquals(1, choices(input().withLabelList(List.of(label))).size());
      assertEquals(0, choices(input().withLabelList(List.of("FINANCIAL PLAN"))).size());
      assertThrows(Exception.class, () -> choices(input().withIdList(List.of("1.0"))));
      assertThrows(Exception.class, () -> choices(input().withSearchTerm("x".repeat(501))));
      assertEquals(1, choices(input().withSkip(1).withLimit(1)).size());
   }



   @Test
   void savedStaleHistoryIsLabeledAndGetPossibleValueRepeatsCurrentAuthorization() throws Exception
   {
      new FinancialRecords(service).importBalances("alice", UUID.randomUUID(), "Date,Balance,Account\n2026-09-30,600.25,Synthetic\n", java.util.Map.of("Synthetic", cash), true);
      var source = instance.getPossibleValueSource(input().getPossibleValueSourceName());
      var provider = QCodeLoader.getAdHoc(QCustomPossibleValueProvider.class, source.getCustomCodeReference());
      var selected = provider.getPossibleValue(first);
      assertTrue(selected.getLabel().contains("Stale"));
      assertFalse(selected.getLabel().contains("Current"));
      assertThrows(Exception.class, () -> provider.getPossibleValue(debt));
      session("bob");
      assertTrue(choices(input()).isEmpty());
      assertThrows(Exception.class, () -> provider.getPossibleValue(first));
      session("other");
      assertTrue(choices(input()).isEmpty());
      session("alice");
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
      assertThrows(Exception.class, () -> choices(input()));
      assertThrows(Exception.class, () -> provider.getPossibleValue(first));
   }



   @Test
   void audienceIntersectionAndCurrentSourceAccessCannotLeakSavedLabels() throws Exception
   {
      var facts = new com.kof22.carlai.domain.DashboardFacts(service);
      assertTrue(facts.savedBalanceSheets(new CarlService.Scope("alice", Set.of("alice", "bob")), null).isEmpty());
      var source = instance.getPossibleValueSource(input().getPossibleValueSourceName());
      var provider = QCodeLoader.getAdHoc(QCustomPossibleValueProvider.class, source.getCustomCodeReference());
      assertNotNull(provider.getPossibleValue(first));
      sql("UPDATE carl_record SET owner_id=2 WHERE id=" + cash);
      assertTrue(choices(input()).isEmpty(), "An accessible artifact cannot expose labels after a saved source becomes inaccessible");
      assertThrows(Exception.class, () -> provider.getPossibleValue(first));
      try(var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM carl_artifact WHERE record_id IN (" + first + "," + second + ")"))
      {
         assertTrue(rows.next());
         assertEquals(2, rows.getInt(1), "Revocation preserves history without exposing it");
      }
   }



   private static void sql(String sql) throws Exception
   {
      try(var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); var statement = connection.createStatement())
      {
         statement.execute(sql);
      }
   }
}
