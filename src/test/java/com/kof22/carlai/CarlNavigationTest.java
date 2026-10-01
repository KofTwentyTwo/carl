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
      assertEquals(List.of("Overview", "Money", "Plans", "Properties and Tax", "Calendar and Reminders", "Vendors", "Documents and Data", "Settings", "System"), app.getChildren().stream().map(child -> child.getLabel()).toList());
      assertNotNull(instance.getTable("carlTransactions"));
      assertNotNull(instance.getProcess("carlClassifyTransaction"));
      assertNotNull(instance.getProcess("carlCreatePlan"));
      var grouped = app.getChildren().stream().map(child -> (com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData) child).filter(child -> child.getSections() != null).flatMap(child -> child.getSections().stream()).flatMap(section -> java.util.stream.Stream.concat(section.getTables().stream(), section.getProcesses().stream())).collect(java.util.stream.Collectors.toSet());
      var nested = app.getChildren().stream().map(child -> (com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData) child).filter(child -> child.getChildren() != null).flatMap(child -> child.getChildren().stream()).filter(child -> child instanceof com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData).map(child -> child.getName()).collect(java.util.stream.Collectors.toSet());
      assertEquals(grouped, nested, "Every table belongs to one actual nested native application");
      assertTrue(nested.size() > 35);
      assertTrue(app.getChildren().stream().map(child -> (com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData) child).filter(child -> child.getChildren() != null).flatMap(child -> child.getChildren().stream()).noneMatch(child -> child instanceof com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData), "Sidebar contains applications and tables only");
      for(var process : instance.getProcesses().values())
      {
         assertNotNull(process.getTableName(), process.getName() + " requires a contextual table");
         assertNotNull(instance.getTable(process.getTableName()), process.getName());
      }
      assertTrue(instance.getApp("carlMoney").getChildren().stream().anyMatch(child -> child.getName().equals("carlTransactions")));
      assertEquals("carlPlans", instance.getProcess("carlCreatePlan").getTableName());
      assertEquals("carlTransactions", instance.getProcess("carlClassifyTransaction").getTableName());
      assertEquals("carlVendors", instance.getProcess("carlAddVendor").getTableName());
      assertEquals("carlImportReviews", instance.getProcess("carlImportMonarch").getTableName());
      assertEquals("carlFinancialGoals", instance.getProcess("carlInvestmentContext").getTableName());
      assertEquals("carlCashPlans", instance.getProcess("carlComparePurchaseOptions").getTableName());
      assertEquals("carlAccounts", instance.getProcess("carlComparePortfolio").getTableName());
   }



   @Test
   void vendorDraftVersionsBelongToTheVendorWorkspace()
   {
      var instance = new QInstance();
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      var versions = instance.getTable("carlDraftVersions");
      assertNotNull(versions);
      assertEquals("Vendor Draft Versions — Not Sent", versions.getLabel());
      var workspace = instance.getApp("carlVendorWorkspace");
      assertTrue(workspace.getChildren().contains(versions), "Vendor draft history belongs under Vendors");
      assertTrue(workspace.getSections().stream().anyMatch(section -> section.getTables().contains("carlDraftVersions")));
      assertTrue(instance.getApp("carlMoney").getChildren().stream().noneMatch(child -> child.getName().equals("carlDraftVersions")));
   }



   @Test
   void systemUsesRegisteredOperationalAppsAndRestrictedDatabaseWidget()
   {
      var instance = new QInstance();
      var operations = new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName("operations").withLabel("Operations");
      var esb = new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName("esb").withLabel("ESB");
      instance.addApp(operations);
      instance.addApp(esb);
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      assertNotNull(instance.getWidget("carlDatabaseDiagnostics"));
      assertNotNull(instance.getApp("carlSystem"));
      assertEquals("carlSystem", operations.getParentAppName());
      assertEquals("carlSystem", esb.getParentAppName());
      assertEquals("agent.role.ADMIN", instance.getWidget("carlDatabaseDiagnostics").getPermissionRules().getPermissionBaseName());
   }



   @Test
   void actualNativeProducerSortingNestsLateEsbOnceAndPreservesItsPermissions() throws Exception
   {
      var instance = new QInstance();
      var operations = new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName("operations").withLabel("Operations")
         .withPermissionRules(com.kof22.agentadmin.OperatorPermissions.require(com.kof22.agentcore.security.Role.OPERATOR));
      instance.addApp(operations);
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      var refresh = new com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData().withName("syntheticIndexRefresh")
         .withPermissionRules(com.kof22.agentadmin.qbits.NativeQBits.indexerPermission());
      com.kingsrook.qqq.esb.model.EsbProcessMetaData.ofOrWithNew(refresh).withTrigger(new com.kingsrook.qqq.esb.model.EsbTrigger().withDestinationName("syntheticChanges")
         .withRunAsSessionSupplier(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda<java.util.function.Supplier<com.kingsrook.qqq.backend.core.model.session.QSession>>(() -> new com.kingsrook.qqq.backend.core.model.session.QSession())));
      instance.addProcess(refresh);
      try(var qbits = new com.kof22.agentadmin.qbits.NativeQBits(java.util.Optional.empty(), Set.of(refresh.getName())))
      {
         var finalizer = new ZCarlSystemNavigation();
         var producers = new java.util.ArrayList<com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface<?>>(List.of(finalizer, qbits));
         com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerHelper.sortMetaDataProducers(producers);
         assertEquals(List.of(qbits, finalizer), producers);
         String legacy = System.getProperty("qqq.MetaDataProducerHelper.disableNameTiebreaker");
         try
         {
            System.setProperty("qqq.MetaDataProducerHelper.disableNameTiebreaker", "true");
            producers = new java.util.ArrayList<>(List.of(finalizer, qbits));
            com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerHelper.sortMetaDataProducers(producers);
            assertEquals(List.of(qbits, finalizer), producers, "Native app output sorts after QBits even with legacy ordering");
         }
         finally
         {
            if(legacy == null)
            {
               System.clearProperty("qqq.MetaDataProducerHelper.disableNameTiebreaker");
            }
            else
            {
               System.setProperty("qqq.MetaDataProducerHelper.disableNameTiebreaker", legacy);
            }
         }
         for(var producer : producers)
         {
            var output = producer.produce(instance);
            if(output != null)
            {
               output.addSelfToInstance(instance);
            }
         }
         var esb = instance.getApp("esb");
         assertNotNull(esb);
         var permissions = esb.getPermissionRules();
         finalizer.produce(instance);
         assertEquals("carlSystem", esb.getParentAppName());
         assertEquals("carlSystem", operations.getParentAppName());
         assertEquals(1, instance.getApp("carlSystem").getChildren().stream().filter(child -> child.getName().equals("esb")).count());
         org.junit.jupiter.api.Assertions.assertSame(permissions, esb.getPermissionRules());
         assertEquals("agent.role.OPERATOR", permissions.getPermissionBaseName());
         assertEquals("agent.role.OPERATOR", operations.getPermissionRules().getPermissionBaseName());
         assertEquals("carlTransactions", instance.getProcess("carlComparePlanEffect").getTableName());
         assertEquals(1, instance.getProcess("carlComparePlanEffect").getMinInputRecords());
         assertEquals(100, instance.getProcess("carlComparePlanEffect").getMaxInputRecords());
         assertEquals(0, instance.getProcess("carlCreatePlan").getMinInputRecords());
         assertEquals(0, instance.getProcess("carlCreatePlan").getMaxInputRecords());
      }
   }



   @Test
   void inheritedApprovalActionLivesOnItsTableAndNeverInSystemSidebar()
   {
      var instance = new QInstance();
      var operations = new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName("operations").withLabel("Operations");
      instance.addApp(operations);
      instance.addTable(new com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData().withName("approvals"));
      var approval = new com.kof22.agentadmin.ApprovalDenialProcess(org.mockito.Mockito.mock(com.kof22.agentcore.security.ApprovalService.class), new com.kof22.agentcore.security.RbacService(java.util.Map.of("approver", com.kof22.agentcore.security.Role.APPROVER)), org.mockito.Mockito.mock(com.kof22.agentcore.security.AuditService.class)).produce(instance);
      instance.addProcess(approval);
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      assertEquals("approvals", approval.getTableName());
      assertEquals("agent.role.APPROVER", approval.getPermissionRules().getPermissionBaseName());
      assertTrue(operations.getChildren().stream().noneMatch(child -> child instanceof com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData));
      assertNotNull(instance.getProcess("denyApproval"));
   }



   @Test
   void databaseHtmlEscapesIdentifiersAndExplainsEmptyAndBoundedResults()
   {
      String unsafe = "<img src=x onerror=alert(1)>";
      String rendered = CarlSystemMetadata.html(new com.kof22.carlai.domain.CarlDatabaseDiagnostics.Snapshot("Carl PostgreSQL", unsafe, "public", List.of(new com.kof22.carlai.domain.CarlDatabaseDiagnostics.TableSize("public", unsafe, 8192, 16384, 24576)), true));
      assertTrue(rendered.contains("&lt;img"));
      org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("<img"));
      assertTrue(rendered.contains("24576"));
      assertTrue(rendered.contains("first 200 tables"));
      assertTrue(CarlSystemMetadata.html(new com.kof22.carlai.domain.CarlDatabaseDiagnostics.Snapshot("Carl PostgreSQL", "Synthetic", "public", List.of(), false)).contains("No tables were found"));
   }



   @Test
   void configuredCalendarActionsHaveContextWithoutOpeningAnyTransport()
   {
      var instance = new QInstance();
      var source = new org.postgresql.ds.PGSimpleDataSource();
      var calendars = com.kof22.carlai.domain.CalendarWorkflows.configured(source, java.util.Map.of(
         "CARL_CALDAV_EVENTS_COLLECTION", "https://calendar.synthetic.test/carl/",
         "CARL_CALDAV_STANDING_PRINCIPAL", "synthetic-owner",
         "CARL_CALDAV_USERNAME", "synthetic-user",
         "CARL_CALDAV_PASSWORD", "synthetic-password",
         "CARL_CALDAV_EVENTS_AUDIENCE_MEMBERS", "1,2"));
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(source, java.time.Clock.systemUTC()), calendars).produce(instance);
      assertEquals("carlCalendarConnections", instance.getProcess("carlSyncAgenda").getTableName());
      assertEquals("carlCalendarOperations", instance.getProcess("carlPublishCalendar").getTableName());
      assertEquals(0, instance.getProcess("carlPublishCalendar").getMinInputRecords());
   }



   @Test
   void homeProfilesAndHistoryBelongToPropertiesAndTax()
   {
      var instance = new QInstance();
      var app = new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName("carlAI").withLabel("Carl AI");
      for(String name : List.of("carlHomes", "carlHomeHistory"))
      {
         var table = new com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData().withName(name).withLabel(name);
         instance.addTable(table);
         app.withChild(table);
      }
      CarlNavigation.apply(instance, app);
      assertEquals(Set.of("carlHomes", "carlHomeHistory"), instance.getApp("carlPropertyTax").getChildren().stream().map(child -> child.getName()).collect(java.util.stream.Collectors.toSet()));
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
