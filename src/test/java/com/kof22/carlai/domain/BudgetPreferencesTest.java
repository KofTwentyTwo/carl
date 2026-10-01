/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
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


class BudgetPreferencesTest
{
   static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   static CarlService service;
   static BudgetRecords budgets;
   static DomainPreferences preferences;
   static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);
   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var ds = new PGSimpleDataSource();
      ds.setURL(DATABASE.getJdbcUrl());
      ds.setUser(DATABASE.getUsername());
      ds.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(ds);
      service = new CarlService(ds, Clock.systemUTC());
      budgets = new BudgetRecords(service);
      preferences = new DomainPreferences(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Member',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(1,'SETTINGS',true),(2,'SETTINGS',true)");
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



   long account(String visibility)
   {
      return new FinancialRecords(service).createAccount("alice", "Synthetic account", "CASH", "USD", true, BigDecimal.ONE, visibility, "Manual evidence");
   }



   long entry(UUID request, long account, String amount, String classification)
   {
      return budgets.manualTransaction("alice", request, account, FROM, new BigDecimal(amount), classification, "Groceries", "Synthetic item", "Human receipt");
   }



   long budget()
   {
      return budgets.create("alice", UUID.randomUUID(), "Groceries", "FAMILY", "Groceries", FROM, THROUGH, new BigDecimal("200.00"), "USD", "Chosen household budget");
   }



   @Test
   void explicitMemberDashboardSelectionsAreTypedAndNeverSharedPrivateRecordDefaults()
   {
      preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_FROM", "2026-09-01", "Synthetic selected reporting period");
      preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_THROUGH", "2026-09-30", "Synthetic selected reporting period");
      preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_CURRENCY", "USD", "Explicit presentation currency");
      preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_PLAN", "42", "Explicit selected plan identity");
      assertEquals("2026-09-01", preferences.effective("alice").get("DASHBOARD_FROM"));
      assertFalse(preferences.effective("bob").containsKey("DASHBOARD_PLAN"));
      for(String[] invalid : new String[][]{{"DASHBOARD_FROM", "not-a-date"}, {"DASHBOARD_FROM", "1800-01-01"}, {"DASHBOARD_THROUGH", "2300-01-01"}, {"DASHBOARD_CURRENCY", "NONE"}, {"DASHBOARD_PLAN", "0"}, {"DASHBOARD_BALANCE", "-1"}, {"DASHBOARD_PLAN", "some-url"}})
      {
         assertThrows(IllegalArgumentException.class, () -> preferences.set("alice", UUID.randomUUID(), "MEMBER", invalid[0], invalid[1], "Invalid synthetic default"));
      }
      assertThrows(IllegalArgumentException.class, () -> preferences.set("alice", UUID.randomUUID(), "HOUSEHOLD", "DASHBOARD_PLAN", "42", "Private defaults cannot become household defaults"));
   }



   @Test
   void manualOriginsAreIdempotentAndNotMonarch()
   {
      long account = account("FAMILY");
      UUID request = UUID.randomUUID();
      long id = entry(request, account, "-125.25", "EXPENSE");
      assertEquals(id, entry(request, account, "-125.25", "EXPENSE"));
      assertThrows(IllegalArgumentException.class, () -> entry(request, account, "-125.26", "EXPENSE"));
      assertEquals(1, count("carl_manual_transaction_origin"));
      assertEquals(0, count("carl_transaction_source"));
      assertThrows(IllegalArgumentException.class, () -> entry(UUID.randomUUID(), account, "-1.001", "EXPENSE"));
   }



   @Test
   void exactVarianceExcludesPrincipalAndTransfersAndIncludesRefunds()
   {
      long a = account("FAMILY");
      long b = budget();
      entry(UUID.randomUUID(), a, "-125.25", "EXPENSE");
      entry(UUID.randomUUID(), a, "-74.75", "EXPENSE");
      entry(UUID.randomUUID(), a, "10.00", "EXPENSE");
      entry(UUID.randomUUID(), a, "-500.00", "DEBT_PRINCIPAL");
      entry(UUID.randomUUID(), a, "-500.00", "CAPITAL");
      long outgoing = entry(UUID.randomUUID(), a, "-999.00", "EXPENSE");
      long incoming = budgets.manualTransaction("alice", UUID.randomUUID(), account("FAMILY"), FROM, new BigDecimal("999.00"), "INCOME", "Account movement", "Transfer receipt", "Synthetic transfer evidence");
      new FinancialRecords(service).pairTransfer("alice", outgoing, incoming, "Explicit paired movement");
      var result = budgets.variance(new CarlService.Scope("alice", Set.of("alice", "bob")), b);
      assertEquals(new BigDecimal("190.00"), result.get("actualSpending"));
      assertEquals(new BigDecimal("10.00"), result.get("remainingBudget"));
      assertEquals("USD", result.get("currency"));
   }



   @Test
   void sharedVarianceCannotUsePrivateAccountOrUnclassifiedAsKnownExpense()
   {
      long b = budget();
      entry(UUID.randomUUID(), account("PRIVATE"), "-900.00", "EXPENSE");
      entry(UUID.randomUUID(), account("FAMILY"), "-30.00", "UNCLASSIFIED");
      var shared = budgets.variance(new CarlService.Scope("alice", Set.of("alice", "bob")), b);
      assertEquals(new BigDecimal("0.00"), shared.get("actualSpending"));
      assertEquals(1, shared.get("unclassifiedCount"));
      assertEquals("PARTIAL", shared.get("status"));
      assertFalse(shared.toString().contains("900"));
   }



   @Test
   void budgetCorrectionsAreAttributedOptimisticAndRetrySafe()
   {
      long b = budget();
      UUID request = UUID.randomUUID();
      budgets.correct("alice", request, b, 1, new BigDecimal("220.00"), "Reviewed target");
      budgets.correct("alice", request, b, 1, new BigDecimal("220.00"), "Reviewed target");
      assertEquals(new BigDecimal("220.00"), budgets.variance(CarlService.Scope.privateFor("alice"), b).get("budget"));
      assertEquals(1, count("carl_correction"));
      assertThrows(IllegalArgumentException.class, () -> budgets.correct("alice", UUID.randomUUID(), b, 1, new BigDecimal("230.00"), "Stale edit"));
      assertThrows(SecurityException.class, () -> budgets.correct("bob", UUID.randomUUID(), b, 2, new BigDecimal("230.00"), "No manager"));
   }



   @Test
   void preferencesAreExplicitScopedAndCannotOverrideAuthority()
   {
      UUID id = UUID.randomUUID();
      long personal = preferences.set("bob", id, "MEMBER", "REPORT_DETAIL", "BRIEF", "Explicit preference");
      assertEquals(personal, preferences.set("bob", id, "MEMBER", "REPORT_DETAIL", "BRIEF", "Explicit preference"));
      assertEquals("BRIEF", preferences.effective("bob").get("REPORT_DETAIL"));
      assertFalse(preferences.effective("alice").containsKey("REPORT_DETAIL"));
      assertThrows(IllegalArgumentException.class, () -> preferences.set("bob", UUID.randomUUID(), "MEMBER", "ALLOW_SEND", "true", "Not policy"));
      assertThrows(SecurityException.class, () -> preferences.set("bob", UUID.randomUUID(), "HOUSEHOLD", "REPORT_DETAIL", "DETAILED", "Not owner"));
      preferences.set("alice", UUID.randomUUID(), "HOUSEHOLD", "REPORT_DETAIL", "STANDARD", "Shared default");
      assertEquals("BRIEF", preferences.effective("bob").get("REPORT_DETAIL"));
      assertEquals("STANDARD", preferences.effective("alice").get("REPORT_DETAIL"));
      preferences.set("bob", UUID.randomUUID(), "MEMBER", "REPORT_DETAIL", "DETAILED", "Changed explicitly");
      assertEquals(3, count("carl_preference_history"));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='SETTINGS'");
      assertThrows(SecurityException.class, () -> preferences.effective("bob"));
   }



   @Test
   void currencyDatesAndPrivateIdentifiersStaySeparate()
   {
      long usd = account("FAMILY");
      long b = budget();
      long eur = new FinancialRecords(service).createAccount("alice", "EUR", "CASH", "EUR", true, BigDecimal.ONE, "FAMILY", "Synthetic currency");
      entry(UUID.randomUUID(), eur, "-500.00", "EXPENSE");
      budgets.manualTransaction("alice", UUID.randomUUID(), usd, FROM.minusDays(1), new BigDecimal("-300.00"), "EXPENSE", "Groceries", "Prior month", "Receipt");
      assertEquals(new BigDecimal("0.00"), budgets.variance(CarlService.Scope.privateFor("alice"), b).get("actualSpending"));
      long hidden = budgets.create("alice", UUID.randomUUID(), "Private budget", "PRIVATE", "Groceries", FROM, THROUGH, new BigDecimal("50.00"), "USD", "Private choice");
      assertThrows(SecurityException.class, () -> budgets.variance(CarlService.Scope.privateFor("bob"), hidden));
      assertThrows(IllegalArgumentException.class, () -> budgets.create("alice", UUID.randomUUID(), "Invalid", "PRIVATE", "Groceries", THROUGH, FROM, new BigDecimal("50.00"), "USD", "Bad interval"));
      assertThrows(IllegalArgumentException.class, () -> entry(UUID.randomUUID(), usd, "0.00", "EXPENSE"));
      assertThrows(IllegalArgumentException.class, () -> entry(UUID.randomUUID(), usd, "-10.00", "TRANSFER"));
   }



   @Test
   void concurrentManualRetryHasOneOriginAndOriginalEvidenceSurvivesClassification() throws Exception
   {
      long a = account("FAMILY");
      UUID request = UUID.randomUUID();
      long id;
      try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      {
         var first = workers.submit(() -> entry(request, a, "-25.00", "UNCLASSIFIED"));
         var second = workers.submit(() -> entry(request, a, "-25.00", "UNCLASSIFIED"));
         id = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
         assertEquals(id, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      }
      new FinancialRecords(service).classify("alice", id, "EXPENSE", "Groceries", "Human review");
      assertEquals(1, count("carl_manual_transaction_origin"));
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement(); var r = s.executeQuery("SELECT classification FROM carl_manual_transaction_origin"))
      {
         r.next();
         assertEquals("UNCLASSIFIED", r.getString(1));
      }
      sql("UPDATE carl_record SET visibility='PRIVATE' WHERE id=" + a);
      assertThrows(SecurityException.class, () -> budgets.manualTransaction("bob", UUID.randomUUID(), a, FROM, new BigDecimal("-1.00"), "EXPENSE", "Groceries", "Denied", "No access"));
   }



   static int count(String table)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table))
      {
         r.next();
         return r.getInt(1);
      }
      catch(Exception e)
      {
         throw new IllegalStateException(e);
      }
   }



   static void sql(String sql)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement())
      {
         s.execute(sql);
      }
      catch(Exception e)
      {
         throw new IllegalStateException(e);
      }
   }
}
