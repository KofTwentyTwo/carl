/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.Set;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlNavigationTest
{
   @Test
   void nativeNavigationHasNamedGroupsAndRetainsEveryOriginalRecordAndAction()
   {
      var instance = new QInstance();
      var app = new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      assertEquals(List.of("Overview", "Money", "Plans", "Properties and Tax", "Calendar and Reminders", "Vendors", "Documents and Data", "Settings"), app.getChildren().stream().map(child -> child.getLabel()).toList());
      assertNotNull(instance.getTable("carlTransactions"));
      assertNotNull(instance.getProcess("carlClassifyTransaction"));
      assertNotNull(instance.getProcess("carlCreatePlan"));
      var grouped = app.getChildren().stream().map(child -> (com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData) child).filter(child -> child.getSections() != null).flatMap(child -> child.getSections().stream()).flatMap(section -> java.util.stream.Stream.concat(section.getTables().stream(), section.getProcesses().stream())).collect(java.util.stream.Collectors.toSet());
      var nested = app.getChildren().stream().map(child -> (com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData) child).filter(child -> child.getChildren() != null).flatMap(child -> child.getChildren().stream()).map(child -> child.getName()).collect(java.util.stream.Collectors.toSet());
      assertEquals(grouped, nested, "Every table and action belongs to one actual nested native application");
      assertTrue(nested.size() > 100);
      assertTrue(instance.getApp("carlMoney").getChildren().stream().anyMatch(child -> child.getName().equals("carlTransactions")));
      assertTrue(instance.getApp("carlPlanning").getChildren().stream().anyMatch(child -> child.getName().equals("carlCreatePlan")));
   }



   @Test
   void brandingAndDashboardWidgetsBelongToTheCarlApplication()
   {
      var instance = new QInstance();
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      assertNotNull(instance.getBranding());
      assertEquals("Carl AI", instance.getBranding().getAppName());
      assertEquals("Carl AI", instance.getBranding().getCompanyName());
      assertNotNull(instance.getBranding().getIcon());
      for(String name : Set.of("carlCashFlow", "carlBalanceSheet", "carlPlanProgress", "carlIncomeExpense"))
      {
         assertNotNull(instance.getWidget(name), name);
         assertNotNull(instance.getWidget(name).getCodeReference(), name);
      }
   }
}
