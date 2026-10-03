/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.Clock;
import java.util.List;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlContextualActionTest
{
   @Test
   void recordActionsAcceptOneSelectedRecordWhileCreatesAndBulkKeepTheirBounds()
   {
      var instance = new QInstance();
      new CarlMetadata(new CarlService(new PGSimpleDataSource(), Clock.systemUTC())).produce(instance);
      for(String name : List.of("carlReviewAccount", "carlDebtTerms", "carlDebtPayments", "carlCorrectVendor", "carlMaintainVendorWork", "carlCorrectBill", "carlClassifyTransaction", "carlCorrectBudget", "carlBudgetVariance", "carlCopyReport", "carlExportPlan"))
      {
         assertEquals(0, instance.getProcess(name).getMinInputRecords(), name);
         assertEquals(1, instance.getProcess(name).getMaxInputRecords(), name);
         assertEquals("selectedRecord", instance.getProcess(name).getStepList().getFirst().getName(), name);
      }
      assertEquals(0, instance.getProcess("carlCreateAccount").getMaxInputRecords());
      assertEquals(0, instance.getProcess("carlCreateBudget").getMaxInputRecords());
      assertEquals(100, instance.getProcess("carlComparePlanEffect").getMaxInputRecords());
      assertEquals(200, instance.getProcess("carlConsolidatedBalanceSheet").getMaxInputRecords());
      for(var table : instance.getTables().values())
      {
         if(table.getName().startsWith("carl") && table.getBackendName().equals("agentOperations"))
         {
            for(var capability : List.of(Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT))
            {
               assertTrue(table.getDisabledCapabilities().contains(capability), table.getName() + " " + capability);
            }
         }
      }
   }



   @Test
   void readinessPlanHasAnAuditedNativeEntryWithoutInventedFinancialInputs()
   {
      var instance = new QInstance();
      new CarlMetadata(new CarlService(new PGSimpleDataSource(), Clock.systemUTC())).produce(instance);
      var process = instance.getProcess("carlGenerateReadinessPlan");
      assertNotNull(process);
      assertEquals("carlPlans", process.getTableName());
      assertEquals(0, process.getMaxInputRecords());
      assertEquals(List.of("title"), process.getFrontendStep("input").getFormFields().stream().map(field -> field.getName()).toList());
      assertFalse(process.getFrontendStep("input").getFormFields().stream().anyMatch(field -> field.getName().equals("principal")));
   }



   @Test
   void inheritedApprovalDenialRetainsApproverPermissionAndAcceptsOneSelectedApproval()
   {
      var instance = new QInstance();
      instance.addApp(new com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData().withName("operations"));
      instance.addTable(new com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData().withName("approvals"));
      var process = new com.kof22.agentadmin.ApprovalDenialProcess(org.mockito.Mockito.mock(com.kof22.agentcore.security.ApprovalService.class), new com.kof22.agentcore.security.RbacService(java.util.Map.of()), org.mockito.Mockito.mock(com.kof22.agentcore.security.AuditService.class)).produce(instance);
      instance.addProcess(process);
      new CarlMetadata(new CarlService(new PGSimpleDataSource(), Clock.systemUTC())).produce(instance);
      assertEquals("approvals", process.getTableName());
      assertEquals("agent.role.APPROVER", process.getPermissionRules().getPermissionBaseName());
      assertEquals(1, process.getMaxInputRecords());
      assertEquals("selectedRecord", process.getStepList().getFirst().getName());
      assertEquals("approvals", process.getFrontendStep("confirm").getFormFields().getFirst().getPossibleValueSourceName());
   }
}
