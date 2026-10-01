/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin.bootstrap;


import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DebtPlans;
import com.kof22.carlai.domain.FinancialPlanning;
import com.kof22.carlai.domain.FinancialRecords;
import org.testcontainers.containers.PostgreSQLContainer;


/** Executes optional visual seeds against real disposable PostgreSQL without claiming browser acceptance. */
public final class CarlDashboardSeedCheck
{
   private CarlDashboardSeedCheck()
   {
   }



   public static void main(String[] arguments) throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(source);
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            c.setAutoCommit(false);
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic visual seed','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Reader',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
            c.commit();
         }
         var service = new CarlService(source, Clock.systemUTC());
         long existingCard = new FinancialRecords(service).createAccount("alice", "Synthetic Card", "CREDIT_CARD", "USD", false, BigDecimal.ONE, "PRIVATE", "Synthetic statement");
         var debt = new DebtPlans(service);
         LocalDate date = LocalDate.of(2026, 9, 1);
         debt.terms("alice", existingCard, date, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("0.24"), BigDecimal.ZERO, "Synthetic terms");
         debt.paymentProfile("alice", existingCard, date, date.plusDays(14), new BigDecimal("100.00"), new BigDecimal("100.00"), FinancialPlanning.Strategy.AVALANCHE, "Synthetic schedule");
         var facts = CarlDashboardVisualSeed.seed(service);
         var json = new ObjectMapper();
         var root = json.valueToTree(facts);
         var observation = root.path("planFacts").path("observations").get(0);
         if(observation == null || !"MATCH".equals(observation.path("facts").path("outcome").asText()) || observation.path("currentPlanVersionMatches").asBoolean(true))
         {
            throw new IllegalStateException("Synthetic matched transfer must remain distinct from current plan agreement");
         }
         Files.writeString(Path.of(arguments[0]), json.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
         System.out.println("SYNTHETIC_DASHBOARD_SEED_PASS");
      }
   }
}
