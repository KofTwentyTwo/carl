/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.kof22.agentcore.runtime.ToolResult;
import com.kof22.carlai.domain.BudgetRecords;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DomainPreferences;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlReadToolsTest
{
   @Test
   void budgetAndPreferenceReadsDeriveCallerAndRejectMutationIdentityArguments() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = new PGSimpleDataSource();
         source.setURL(database.getJdbcUrl());
         source.setUser(database.getUsername());
         source.setPassword(database.getPassword());
         com.kof22.agentcore.store.AgentMigrations.migrate(source);
         var service = new CarlService(source, Clock.systemUTC());
         try(var c = source.getConnection(); var statement = c.createStatement())
         {
            statement.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic tools','America/Chicago')");
            statement.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',false)");
            statement.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['FINANCE','SETTINGS','CALENDAR']) d");
         }
         long budget = new BudgetRecords(service).create("alice", UUID.randomUUID(), "Private budget", "PRIVATE", "Groceries", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), new BigDecimal("500.00"), "USD", "Synthetic scope");
         new DomainPreferences(service).set("alice", UUID.randomUUID(), "MEMBER", "REPORT_DETAIL", "BRIEF", "Explicit choice");
         var tools = CarlTools.bind(service);
         var budgetTool = tools.stream().filter(t -> t.definition().name().equals("carl_read_budget")).findFirst().orElseThrow();
         ToolResult result = budgetTool.executor().execute("{\"budget\":" + budget + "}", "alice");
         assertFalse(result.isError(), result.content());
         assertTrue(result.content().contains("500.00"));
         assertTrue(result.content().contains("PARTIAL"));
         assertTrue(budgetTool.executor().execute("{\"budget\":" + budget + "}", "bob").isError());
         assertTrue(budgetTool.executor().execute("{\"budget\":\"1\"}", "alice").isError());
         assertTrue(budgetTool.executor().execute("{\"budget\":" + budget + ",\"principal\":\"alice\"}", "bob").isError());
         var preferenceTool = tools.stream().filter(t -> t.definition().name().equals("carl_read_preferences")).findFirst().orElseThrow();
         assertEquals("{\"REPORT_DETAIL\":\"BRIEF\"}", preferenceTool.executor().execute("{}", "alice").content());
         assertEquals("{}", preferenceTool.executor().execute("{}", "bob").content());
         assertTrue(preferenceTool.executor().execute("{\"REPORT_DETAIL\":\"DETAILED\"}", "alice").isError());
         assertTrue(preferenceTool.executor().execute("{}", "unmapped").isError());
         var availability = tools.stream().filter(t -> t.definition().name().equals("carl_read_availability")).findFirst().orElseThrow();
         var suggested = availability.executor().execute("{\"from\":\"2026-09-30T12:00:00Z\",\"through\":\"2026-09-30T18:00:00Z\",\"minimumMinutes\":30}", "alice");
         assertFalse(suggested.isError(), suggested.content());
         assertTrue(suggested.content().contains("PARTIAL_SUGGESTIONS"));
         assertTrue(availability.executor().execute("{\"from\":\"2026-09-30T12:00:00Z\",\"through\":\"2026-09-30T18:00:00Z\",\"minimumMinutes\":\"30\"}", "alice").isError());
         assertTrue(tools.stream().allMatch(t -> t.definition().name().startsWith("carl_read_")));
      }
   }
}
