/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BudgetPreferenceMetadataTest
{
   @Test
   void registersScopedHumanProcessesAndReviewTables()
   {
      var instance = new QInstance();
      var app = new QAppMetaData().withName("carlAI");
      instance.addTable(CarlMetadata.table("carlBudgets", "Budgets", "carl_budget_view", "category:S,amount:M,currency:S"));
      BudgetProcesses.register(instance, app, null);
      PreferenceProcesses.register(instance, app, null);
      for(String name : java.util.List.of("carlManualTransaction", "carlCreateBudget", "carlCorrectBudget", "carlBudgetVariance", "carlSetReportDetail", "carlSetReportPeriod"))
      {
         assertNotNull(instance.getProcess(name).getPermissionRules());
      }
      assertNotNull(instance.getTable("carlBudgets").getField("revision"));
      var visibleFields = instance.getTable("carlBudgets").getSections().stream().flatMap(section -> section.getFieldNames().stream()).collect(java.util.stream.Collectors.toSet());
      var requiredFields = instance.getTable("carlBudgets").getFields().values().stream().filter(field -> !Boolean.TRUE.equals(field.getIsHidden())).map(field -> field.getName()).toList();
      assertTrue(visibleFields.containsAll(requiredFields), "Every visible native budget field must belong to a section");
      assertNotNull(instance.getTable("carlManualTransactions"));
      assertNotNull(instance.getTable("carlPreferences"));
   }
}
