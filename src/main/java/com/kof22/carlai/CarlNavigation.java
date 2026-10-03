/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.ArrayList;
import java.util.List;

import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QInputSource;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.branding.QBrandingMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/** Native financial groupings keep existing table/process IDs and direct URLs intact. */
final class CarlNavigation
{
   private static final List<String> LABELS = List.of("Overview", "Money", "Plans", "Properties and Tax", "Calendar and Reminders", "Vendors", "Documents and Data", "Settings", "System");
   private static final List<String> NAMES = List.of("carlOverview", "carlMoney", "carlPlanning", "carlPropertyTax", "carlCalendarReminders", "carlVendorWorkspace", "carlDocumentsData", "carlSettings", "carlSystem");
   private static final List<String> ICONS = List.of("dashboard", "account_balance_wallet", "route", "real_estate_agent", "event", "handshake", "folder_open", "settings", "dns");

   private CarlNavigation()
   {
   }



   static void apply(QInstance instance, QAppMetaData app)
   {
      String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'><rect width='64' height='64' rx='14' fill='#25282c'/><path d='M44 20H30a12 12 0 0 0 0 24h14' fill='none' stroke='#fff' stroke-width='7'/><path d='M44 31H31' stroke='#0066cc' stroke-width='7'/></svg>";
      String icon = "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      instance.setBranding(new QBrandingMetaData().withAppName("Carl AI").withCompanyName("Carl AI").withLogo(icon).withIcon(icon).withAccentColor("#0066cc").withAccentColorLight("#163e69"));
      instance.withSupplementalMetaData(new CarlTheme());
      app.withIcon(new QIcon().withName("dashboard"));
      var sections = new ArrayList<QAppSection>();
      for(int n = 0; n < LABELS.size(); n++)
      {
         sections.add(new QAppSection().withName(NAMES.get(n)).withLabel(LABELS.get(n)).withIcon(new QIcon().withName(ICONS.get(n))).withTables(new ArrayList<>()).withProcesses(new ArrayList<>()).withReports(new ArrayList<>()).withApps(new ArrayList<>()));
      }
      var originalChildren = List.copyOf(app.getChildren());
      for(var child : originalChildren)
      {
         var section = sections.get(group(child.getName()));
         if(child instanceof QTableMetaData)
         {
            section.withTable(child.getName());
         }
         else if(child instanceof QProcessMetaData process)
         {
            contextualAction(instance, process);
         }
      }
      app.getChildren().clear();
      for(int n = 0; n < LABELS.size(); n++)
      {
         var dashboard = new QAppMetaData().withName(NAMES.get(n)).withLabel(LABELS.get(n)).withIcon(new QIcon().withName(ICONS.get(n))).withChildren(new ArrayList<>()).withSortOrder(n + 1);
         if(n == 0)
         {
            dashboard.withWidgets(List.of("carlCashFlow", "carlPlanProgress"));
         }
         else if(n == 1)
         {
            dashboard.withWidgets(List.of("carlCashFlow", "carlIncomeExpense", "carlBalanceSheet"));
         }
         else if(n == 2)
         {
            dashboard.withWidgets(List.of("carlPlanProgress"));
         }
         for(var child : originalChildren)
         {
            if(child instanceof QTableMetaData && group(child.getName()) == n)
            {
               dashboard.withChild(child);
            }
         }
         var original = sections.get(n);
         if(!original.getTables().isEmpty())
         {
            dashboard.withSections(List.of(new QAppSection().withName(NAMES.get(n) + "Records").withLabel(LABELS.get(n)).withTables(new ArrayList<>(original.getTables())).withProcesses(new ArrayList<>()).withReports(new ArrayList<>()).withApps(new ArrayList<>())));
         }
         instance.addApp(dashboard);
         app.withChild(dashboard);
         sections.get(n).withApp(dashboard.getName());
      }
      app.withWidgets(List.of("carlCashFlow", "carlPlanProgress"));
      ZCarlSystemNavigation.attach(instance);
   }

   private static final java.util.Map<String, String> ACTION_TABLES = java.util.Map.ofEntries(
      java.util.Map.entry("carlCreateAccount", "carlAccounts"),
      java.util.Map.entry("carlReviewAccount", "carlAccounts"),
      java.util.Map.entry("carlDebtTerms", "carlAccounts"),
      java.util.Map.entry("carlDebtPayments", "carlAccounts"),
      java.util.Map.entry("carlCompareDebt", "carlAccounts"),
      java.util.Map.entry("carlDebtRateChange", "carlAccounts"),
      java.util.Map.entry("carlInvestmentContext", "carlFinancialGoals"),
      java.util.Map.entry("carlClassifyTransaction", "carlTransactions"),
      java.util.Map.entry("carlClassifyTransactions", "carlTransactions"),
      java.util.Map.entry("carlPairTransfer", "carlTransactions"),
      java.util.Map.entry("carlUnpairTransfer", "carlTransactions"),
      java.util.Map.entry("carlPreviewBills", "carlBills"),
      java.util.Map.entry("carlImportBills", "carlBills"),
      java.util.Map.entry("carlManualBill", "carlBills"),
      java.util.Map.entry("carlCorrectBill", "carlBills"),
      java.util.Map.entry("carlCompareBillPeriods", "carlBills"),
      java.util.Map.entry("carlAddVendor", "carlVendors"),
      java.util.Map.entry("carlCorrectVendor", "carlVendors"),
      java.util.Map.entry("carlAddWork", "carlWork"),
      java.util.Map.entry("carlMaintainVendorWork", "carlWork"),
      java.util.Map.entry("carlVendorDraft", "carlWork"),
      java.util.Map.entry("carlImportMonarch", "carlImportReviews"),
      java.util.Map.entry("carlRegisterDocument", "carlDocuments"),
      java.util.Map.entry("carlDownloadDocument", "carlDocuments"),
      java.util.Map.entry("carlMapMonarch", "carlImportReviews"),
      java.util.Map.entry("carlRegisterMonarchSources", "carlImportReviews"),
      java.util.Map.entry("carlResolveMonarchBalance", "carlImportReviews"),
      java.util.Map.entry("carlResumeMonarch", "carlImportReviews"),
      java.util.Map.entry("carlHouseholdReport", "carlArtifacts"),
      java.util.Map.entry("carlFocusedReport", "carlArtifacts"),
      java.util.Map.entry("carlCopyReport", "carlArtifacts"),
      java.util.Map.entry("carlDownloadReportPdf", "carlArtifacts"),
      java.util.Map.entry("carlDownloadReportText", "carlArtifacts"),
      java.util.Map.entry("carlInspectReportRequest", "carlArtifacts"),
      java.util.Map.entry("carlReconcileReportRequest", "carlArtifacts"),
      java.util.Map.entry("carlCopyVendorDraft", "carlArtifacts"),
      java.util.Map.entry("carlDownloadVendorDraft", "carlArtifacts"),
      java.util.Map.entry("carlEditVendorDraft", "carlArtifacts"),
      java.util.Map.entry("carlVendorDraftHistory", "carlArtifacts"),
      java.util.Map.entry("carlTalkRead", "carlArtifacts"),
      java.util.Map.entry("carlTalkReply", "carlArtifacts"),
      java.util.Map.entry("carlTalkStart", "carlArtifacts"),
      java.util.Map.entry("carlCreatePlan", "carlPlans"),
      java.util.Map.entry("carlAgreePlan", "carlPlans"),
      java.util.Map.entry("carlReplan", "carlPlans"),
      java.util.Map.entry("carlExportPlan", "carlPlans"),
      java.util.Map.entry("carlPlanTask", "carlPlanSteps"),
      java.util.Map.entry("carlPlanCheckIn", "carlPlanSteps"),
      java.util.Map.entry("carlPlanExpectation", "carlPlanEffects"),
      java.util.Map.entry("carlComparePrincipalEffect", "carlPlanEffects"),
      java.util.Map.entry("carlCreateGoal", "carlFinancialGoals"),
      java.util.Map.entry("carlInvestmentScenario", "carlFinancialGoals"),
      java.util.Map.entry("carlComparePortfolio", "carlAccounts"),
      java.util.Map.entry("carlReviewPortfolioMove", "carlPortfolioMoves"),
      java.util.Map.entry("carlCompareOffers", "carlFinancingOffers"),
      java.util.Map.entry("carlRecordOffer", "carlFinancingOffers"),
      java.util.Map.entry("carlComparePurchaseOptions", "carlCashPlans"),
      java.util.Map.entry("carlCalendarSetupStatus", "carlCalendarConnections"),
      java.util.Map.entry("carlSyncAgenda", "carlCalendarConnections"),
      java.util.Map.entry("carlPublishCalendar", "carlCalendarOperations"),
      java.util.Map.entry("carlReviewReminder", "carlReminderObservations"),
      java.util.Map.entry("carlSuggestAppointmentWindows", "carlCalendar"),
      java.util.Map.entry("carlSetReportDetail", "carlPreferences"),
      java.util.Map.entry("carlSetReportPeriod", "carlPreferences"),
      java.util.Map.entry("carlCreateBudget", "carlBudgets"),
      java.util.Map.entry("carlCorrectBudget", "carlBudgets"),
      java.util.Map.entry("carlBudgetVariance", "carlBudgets"),
      java.util.Map.entry("carlManualTransaction", "carlManualTransactions"),
      java.util.Map.entry("carlCreateCashPlan", "carlCashPlans"),
      java.util.Map.entry("carlCashMovement", "carlCashPlans"),
      java.util.Map.entry("carlAssessPurchase", "carlCashPlans"),
      java.util.Map.entry("carlSelectCashExpenses", "carlCashPlans"),
      java.util.Map.entry("carlCreateExpense", "carlExpenses"),
      java.util.Map.entry("carlCorrectExpense", "carlExpenses"),
      java.util.Map.entry("carlExpenseReport", "carlExpenses"),
      java.util.Map.entry("carlRecordExpenseActual", "carlExpenseActuals"),
      java.util.Map.entry("carlRefreshExpenseActual", "carlExpenseActuals"),
      java.util.Map.entry("carlClassifyExpenseActual", "carlExpenseActuals"),
      java.util.Map.entry("carlSettleExpense", "carlExpenseSettlements"),
      java.util.Map.entry("carlUnsettleExpense", "carlExpenseSettlements"),
      java.util.Map.entry("carlAddRentalProperty", "carlProperties"),
      java.util.Map.entry("carlCorrectRentalProperty", "carlProperties"),
      java.util.Map.entry("carlAddRentalUnit", "carlRentalUnits"),
      java.util.Map.entry("carlClassifyRentalSource", "carlRentalSources"),
      java.util.Map.entry("carlRecordRentDue", "carlRentDues"),
      java.util.Map.entry("carlApplyRentReceipt", "carlRentApplications"),
      java.util.Map.entry("carlUnapplyRentReceipt", "carlRentApplications"),
      java.util.Map.entry("carlRentalReport", "carlRentalBaselines"),
      java.util.Map.entry("carlStartRentalReview", "carlRentalReviews"),
      java.util.Map.entry("carlPreviewRentalReview", "carlRentalReviews"),
      java.util.Map.entry("carlApplyRentalReview", "carlRentalReviews"),
      java.util.Map.entry("carlEditRentalReviewComponent", "carlRentalReviewComponents"),
      java.util.Map.entry("carlRemoveRentalReviewComponent", "carlRentalReviewComponents"),
      java.util.Map.entry("carlEditRentalReviewShare", "carlRentalReviewShares"),
      java.util.Map.entry("carlRecordRentalStress", "carlRentalStress"),
      java.util.Map.entry("carlRentalStressReport", "carlRentalStress"),
      java.util.Map.entry("carlTaxDocument", "carlTax"),
      java.util.Map.entry("carlTaxPacket", "carlTax"),
      java.util.Map.entry("carlTaxPropertyContext", "carlTaxProperties"),
      java.util.Map.entry("carlRecordTaxReference", "carlTaxReferences"),
      java.util.Map.entry("carlTaxReferenceStatus", "carlTaxReferences"),
      java.util.Map.entry("carlSourceGroundedTaxPacket", "carlTaxReferences"),
      java.util.Map.entry("carlRecordTaxAlternative", "carlTaxAlternatives"));

   private static void contextualAction(QInstance instance, QProcessMetaData process)
   {
      if(process.getTableName() == null)
      {
         String table = ACTION_TABLES.get(process.getName());
         if(table == null || instance.getTable(table) == null)
         {
            throw new IllegalStateException("Carl action requires a registered contextual table: " + process.getName());
         }
         process.withTableName(table).withMinInputRecords(0).withMaxInputRecords(0);
      }
      selectedRecordAction(instance, process);
   }



   static void selectedRecordAction(QInstance instance, QProcessMetaData process)
   {
      if(process.getStep("selectedRecord") != null || process.getMinInputRecords() != null && process.getMinInputRecords() > 0 || process.getMaxInputRecords() != null && process.getMaxInputRecords() > 1)
      {
         return;
      }
      var fields = List.copyOf(process.getInputFields());
      var targets = fields.stream().filter(field -> process.getTableName().equals(field.getPossibleValueSourceName())).toList();
      if(targets.size() != 1)
      {
         return;
      }
      String target = targets.getFirst().getName();
      process.withMinInputRecords(0).withMaxInputRecords(1);
      process.withStep(0, new QBackendStepMetaData().withName("selectedRecord").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
      {
         if(in.getCallback() == null || in.getCallback().getQueryFilter() == null)
         {
            return;
         }
         var filter = new QQueryFilter().withSubFilters(List.of(in.getCallback().getQueryFilter())).withLimit(2);
         var query = new QueryInput().withTableName(process.getTableName()).withFilter(filter).withInputSource(QInputSource.USER);
         PermissionsHelper.checkTablePermissionThrowing(query, TablePermissionSubType.READ);
         var records = new QueryAction().execute(query).getRecords();
         if(records == null || records.size() != 1)
         {
            throw new QException("Choose exactly one currently readable record for this action");
         }
         var row = records.getFirst();
         out.addValue(target, row.getValue(instance.getTable(process.getTableName()).getPrimaryKeyField()));
         for(var field : fields)
         {
            String column = switch(field.getName())
            {
               case "expectedRevision" -> "revision";
               case "expectedVersion" -> "version";
               default -> field.getName();
            };
            if(!field.getName().equals(target) && row.getValue(column) != null)
            {
               out.addValue(field.getName(), row.getValue(column));
            }
         }
      })));
   }



   private static int group(String name)
   {
      if(name.startsWith("carlTalk"))
      {
         return 0;
      }
      if(name.contains("Preference") || name.contains("Member") || name.startsWith("carlSetReport") || name.contains("Connection"))
      {
         return 7;
      }
      if(name.contains("Home") || name.contains("Rental") || name.contains("Rent") || name.contains("Tax") || name.contains("Propert"))
      {
         return 3;
      }
      if(name.contains("Calendar") || name.contains("Agenda") || name.contains("Reminder") || name.contains("Appointment") || name.contains("Availability"))
      {
         return 4;
      }
      if(name.contains("Vendor") || name.contains("Work") || name.equals("carlDraftVersions"))
      {
         return 5;
      }
      if(name.contains("Document") || name.contains("Import") || name.contains("Monarch") || name.contains("Artifact") || name.contains("Report") || name.contains("Copy") || name.contains("Export") || name.contains("Download"))
      {
         return 6;
      }
      if(name.contains("Plan") || name.contains("Goal") || name.contains("Compare") || name.contains("Scenario") || name.contains("Investment") || name.contains("Purchase") || name.contains("Effect") || name.contains("Financing"))
      {
         return 2;
      }
      return 1;
   }
}
