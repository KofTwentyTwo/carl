/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLTableBackendDetails;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.BillCsv;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FinancialRecords;


/** Native administration over the same permission-filtered records and authoritative services as Carl tools. */
final class CarlMetadata implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;
   private final com.kof22.carlai.domain.CalendarWorkflows calendars;
   private final com.kof22.carlai.domain.CarlTalkService talk;
   CarlMetadata(CarlService service)
   {
      this(service, new com.kof22.carlai.domain.CalendarWorkflows(java.util.Map.of()));
   }



   CarlMetadata(CarlService service, com.kof22.carlai.domain.CalendarWorkflows calendars)
   {
      this(service, calendars, new com.kof22.carlai.domain.CarlTalkService(service));
   }



   CarlMetadata(CarlService service, com.kof22.carlai.domain.CalendarWorkflows calendars, com.kof22.carlai.domain.CarlTalkService talk)
   {
      this.service = service;
      this.calendars = calendars;
      this.talk = talk;
   }



   @Override
   public int getSortOrder()
   {
      return 600;
   }



   @Override
   public QAppMetaData produce(QInstance instance)
   {
      CarlProcessScope.register(instance, service);
      CarlNativeReadScope.register(instance, service);
      instance.addSecurityKeyType(new QSecurityKeyType().withName("userId"));
      var app = new QAppMetaData().withName("carlAI").withLabel("Carl AI");
      for(var table : List.of(
         table("carlBills", "Bills", "carl_bill_view", "vendor_label:S,amount:M,currency:S,due_date:D,status:S,payment_evidence:T,source_id:S,created_at:I,revision:L"),
         table("carlVendors", "Vendors", "carl_vendor_view", "category:S,contact:S,contact_verified:B"),
         table("carlWork", "Vendor Work", "carl_work_view", "vendor_id:L,status:S,assigned_member:L,follow_up:D,commitment_evidence:T"),
         table("carlAccounts", "Accounts and Debts", "carl_account_view", "kind:S,currency:S,institution:S,liquid:B,ownership_share:M,balance:M,as_of:D,basis:S"),
         table("carlTransactions", "Transactions", "carl_transaction_view", "account_id:L,effective_date:D,amount:M,currency:S,classification:S,category:S,transfer_key:S,source_id:S"),
         table("carlBudgets", "Budgets", "carl_budget_view", "category:S,period_start:D,period_end:D,amount:M,currency:S"),
         table("carlProperties", "Rental Properties", "carl_rental_property_view", "revision:L,locality:S,ownership_share:M,market_value:M,valuation_date:D,currency:S,legal_owner:S,asset_account_id:L,debt_account_id:L,acquisition_date:D,acquisition_basis:M,land_basis:M,building_basis:M,basis_evidence:T"),
         table("carlCalendarConnections", "Calendar Connections", "carl_calendar_connection_view", "provider:S,sync_state:S,last_success:I,last_attempt:I,failure_code:S,coverage_from:D,coverage_through:D"),
         table("carlCalendar", "Calendar", "carl_calendar_view", "start_at:I,end_at:I,source_zone:S,all_day_start:D,all_day_end_exclusive:D,cancelled:B,transparent:B,last_success:I,sync_state:S"),
         table("carlTax", "Tax Documents", "carl_tax_view", "tax_year:L,jurisdiction:S,document_kind:S,classification_state:S,treatment_evidence:T"),
         artifactTable()))
      {
         instance.addTable(table);
         app.withChild(table);
      }
      var reviews = table("carlImportReviews", "Monarch Import History", "carl_import_review_view", "status:S,result:T,created_at:I", false)
         .withField(field("id", QFieldType.STRING));
      instance.addTable(reviews);
      app.withChild(reviews);
      for(String name : List.of("carlAccounts", "carlVendors", "carlWork", "carlBills", "carlImportReviews", "carlArtifacts", "carlTransactions"))
      {
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(name));
      }
      choices(instance, "carlClassification", List.of("INCOME", "EXPENSE", "DEBT_PRINCIPAL", "DEBT_INTEREST", "CAPITAL", "UNCLASSIFIED"));
      var finances = new FinancialRecords(service);
      add(instance, app, process("carlClassifyTransaction", "Classify Reviewed Transaction", List.of(input("transaction", QFieldType.LONG, true).withPossibleValueSourceName("carlTransactions"), input("classification", QFieldType.STRING, true).withPossibleValueSourceName("carlClassification"), input("category", QFieldType.STRING, true), input("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         finances.classify(principal(), Long.parseLong(in.getValueString("transaction")), in.getValueString("classification"), in.getValueString("category"), in.getValueString("reason"));
         out.addValue("result", "Classification saved with attribution. Imports never infer income or transfers from amount sign.");
      }));
      add(instance, app, process("carlPairTransfer", "Match Internal Transfer", List.of(input("outgoing", QFieldType.LONG, true).withPossibleValueSourceName("carlTransactions"), input("incoming", QFieldType.LONG, true).withPossibleValueSourceName("carlTransactions"), input("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         finances.pairTransfer(principal(), Long.parseLong(in.getValueString("outgoing")), Long.parseLong(in.getValueString("incoming")), in.getValueString("reason"));
         out.addValue("result", "Both transfer legs matched with evidence. This local classification moves no money.");
      }));
      add(instance, app, process("carlUnpairTransfer", "Review and Unpair Transfer", List.of(input("transaction", QFieldType.LONG, true).withPossibleValueSourceName("carlTransactions"), input("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         finances.unpairTransfer(principal(), Long.parseLong(in.getValueString("transaction")), in.getValueString("reason"));
         out.addValue("result", "Both legs are now unclassified and require review. Source revisions may now be imported explicitly.");
      }));
      choices(instance, "carlVisibility", List.of("PRIVATE", "FAMILY"));
      choices(instance, "carlBillStatus", List.of("UNPAID", "PAID_ASSERTED", "DISPUTED", "UNKNOWN"));
      choices(instance, "carlAccountKind", List.of("CASH", "CREDIT_CARD", "LOAN", "INVESTMENT", "OTHER_ASSET"));
      choices(instance, "carlDraftPurpose", List.of("FOLLOW_UP", "QUOTE_REQUEST", "SCHEDULING_INQUIRY", "SERVICE_QUESTION"));
      add(instance, app, process("carlManualBill", "Add Bill", List.of(input("vendor", QFieldType.STRING, true), input("description", QFieldType.STRING, true), input("amount", QFieldType.DECIMAL, false), input("currency", QFieldType.STRING, true), input("dueDate", QFieldType.DATE, false), input("status", QFieldType.STRING, true), input("visibility", QFieldType.STRING, true)), (in, out) ->
      {
         String amount = in.getValueString("amount");
         var bill = new BillCsv.Row(1, in.getValueString("requestId"), in.getValueString("vendor"), in.getValueString("description"), amount == null || amount.isBlank() ? null : new BigDecimal(amount), in.getValueString("currency"), in.getValueLocalDate("dueDate"), in.getValueString("status"), in.getValueString("visibility"));
         out.addValue("result", "Bill saved in batch " + service.enterBill(principal(), UUID.fromString(in.getValueString("requestId")), bill));
      }));
      add(instance, app, process("carlAddVendor", "Add Vendor", List.of(input("title", QFieldType.STRING, true), input("category", QFieldType.STRING, true), input("contact", QFieldType.STRING, false), input("verified", QFieldType.BOOLEAN, false), input("visibility", QFieldType.STRING, true), input("evidence", QFieldType.TEXT, true)), (in, out) ->
      {
         out.addValue("result", "Vendor saved: " + service.createVendor(principal(), in.getValueString("title"), in.getValueString("category"), in.getValueString("contact"), in.getValuePrimitiveBoolean("verified"), in.getValueString("visibility"), in.getValueString("evidence")));
      }));
      choices(instance, "carlWorkStatus", List.of("FAMILY_RESPONSE", "VENDOR_RESPONSE", "INFORMATION_NEEDED", "COMPLETE"));
      add(instance, app, process("carlAddWork", "Add Vendor Work Item", List.of(input("vendorId", QFieldType.LONG, true), input("title", QFieldType.STRING, true), input("workStatus", QFieldType.STRING, true).withPossibleValueSourceName("carlWorkStatus"), input("followUp", QFieldType.DATE, false), input("evidence", QFieldType.TEXT, true), input("visibility", QFieldType.STRING, true)), (in, out) ->
      {
         out.addValue("result", "Work item saved: " + service.createWorkItem(principal(), Long.parseLong(in.getValueString("vendorId")), in.getValueString("title"), in.getValueString("workStatus"), in.getValueLocalDate("followUp"), in.getValueString("evidence"), in.getValueString("visibility")));
      }));
      add(instance, app, process("carlPreviewBills", "Preview Bill Import", List.of(input("csv", QFieldType.TEXT, true)), (in, out) ->
      {
         principal();
         out.addValue("result", BillCsv.preview(in.getValueString("csv")).toString());
      }));
      add(instance, app, process("carlImportBills", "Apply Reviewed Bill Import", List.of(input("requestId", QFieldType.STRING, true), input("sourceName", QFieldType.STRING, true), input("csv", QFieldType.TEXT, true)), (in, out) ->
      {
         long id = service.importBills(principal(), UUID.fromString(in.getValueString("requestId")), in.getValueString("sourceName"), in.getValueString("csv"));
         out.addValue("result", "Import committed: " + id);
      }));
      add(instance, app, process("carlCorrectBill", "Correct Local Bill", List.of(input("recordId", QFieldType.LONG, true), input("amount", QFieldType.DECIMAL, false), input("dueDate", QFieldType.DATE, false), input("status", QFieldType.STRING, true), input("paymentEvidence", QFieldType.TEXT, false), input("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         String amount = in.getValueString("amount");
         String due = in.getValueString("dueDate");
         service.correctBill(principal(), Long.parseLong(in.getValueString("recordId")), amount == null || amount.isBlank() ? null : new BigDecimal(amount), due == null || due.isBlank() ? null : LocalDate.parse(due), in.getValueString("status"), in.getValueString("paymentEvidence"), in.getValueString("reason"));
         out.addValue("result", "Local correction saved with attribution; imported evidence preserved.");
      }));
      add(instance, app, process("carlHouseholdReport", "Generate Household Report", List.of(input("requestId", QFieldType.STRING, true), input("from", QFieldType.DATE, true), input("through", QFieldType.DATE, true)), (in, out) ->
      {
         long id = service.generateReport(CarlService.Scope.privateFor(principal()), UUID.fromString(in.getValueString("requestId")), in.getValueLocalDate("from"), in.getValueLocalDate("through"), null);
         out.addValue("result", "Report saved: " + id + ". Verified facts; narration not requested.");
      }));
      add(instance, app, process("carlVendorDraft", "Prepare Vendor Draft", List.of(input("requestId", QFieldType.STRING, true), input("workId", QFieldType.LONG, true), input("purpose", QFieldType.STRING, true)), (in, out) ->
      {
         long id = service.generateDraft(CarlService.Scope.privateFor(principal()), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("workId")), in.getValueString("purpose"));
         out.addValue("result", service.artifact(principal(), id).get("narrative").toString() + "\nDraft — not sent. Record " + id);
      }));
      add(instance, app, process("carlCreateAccount", "Add Financial Account", List.of(input("title", QFieldType.STRING, true), input("kind", QFieldType.STRING, true), input("currency", QFieldType.STRING, true), input("liquid", QFieldType.BOOLEAN, true), input("ownershipShare", QFieldType.DECIMAL, true), input("visibility", QFieldType.STRING, true), input("evidence", QFieldType.TEXT, true)), (in, out) ->
      {
         long id = new FinancialRecords(service).createAccount(principal(), in.getValueString("title"), in.getValueString("kind"), in.getValueString("currency"), in.getValuePrimitiveBoolean("liquid"), new BigDecimal(in.getValueString("ownershipShare")), in.getValueString("visibility"), in.getValueString("evidence"));
         out.addValue("result", "Account created: " + id);
      }));
      add(instance, app, process("carlDebtTerms", "Record Debt Statement Terms", List.of(input("accountId", QFieldType.LONG, true).withPossibleValueSourceName("carlAccounts"), input("asOf", QFieldType.DATE, true), input("principalBalance", QFieldType.DECIMAL, true), input("minimum", QFieldType.DECIMAL, true), input("minimumFraction", QFieldType.DECIMAL, true), input("apr", QFieldType.DECIMAL, true).withLabel("Annual rate as decimal (0.24 means 24%)"), input("monthlyFee", QFieldType.DECIMAL, true), input("evidence", QFieldType.TEXT, true)), (in, out) ->
      {
         new com.kof22.carlai.domain.DebtPlans(service).terms(principal(), Long.parseLong(in.getValueString("accountId")), in.getValueLocalDate("asOf"), new BigDecimal(in.getValueString("principalBalance")), new BigDecimal(in.getValueString("minimum")), new BigDecimal(in.getValueString("minimumFraction")), new BigDecimal(in.getValueString("apr")), new BigDecimal(in.getValueString("monthlyFee")), in.getValueString("evidence"));
         out.addValue("result", "Explicit debt terms saved with attribution; previous plan snapshots become stale.");
      }));
      choices(instance, "carlRollover", List.of("AVALANCHE", "SNOWBALL", "MINIMUM_ONLY"));
      add(instance, app, process("carlDebtPayments", "Record Current and Proposed Debt Payments", List.of(input("accountId", QFieldType.LONG, true).withPossibleValueSourceName("carlAccounts"), input("asOf", QFieldType.DATE, true), input("firstPayment", QFieldType.DATE, true), input("currentPayment", QFieldType.DECIMAL, true), input("proposedPayment", QFieldType.DECIMAL, true), input("rollover", QFieldType.STRING, true).withPossibleValueSourceName("carlRollover"), input("evidence", QFieldType.TEXT, true)), (in, out) ->
      {
         new com.kof22.carlai.domain.DebtPlans(service).paymentProfile(principal(), Long.parseLong(in.getValueString("accountId")), in.getValueLocalDate("asOf"), in.getValueLocalDate("firstPayment"), new BigDecimal(in.getValueString("currentPayment")), new BigDecimal(in.getValueString("proposedPayment")), com.kof22.carlai.domain.FinancialPlanning.Strategy.valueOf(in.getValueString("rollover")), in.getValueString("evidence"));
         out.addValue("result", "Explicit payment assumptions saved. Comparisons retain current and proposed payment schedules; no payment is scheduled or sent.");
      }));
      add(instance, app, process("carlCompareDebt", "Compare Debt Payoff Plans", List.of(input("asOf", QFieldType.DATE, true), input("currency", QFieldType.STRING, true), input("monthlyBudget", QFieldType.DECIMAL, true).withLabel("Assumed monthly payment budget after essentials and reserves"), input("horizon", QFieldType.INTEGER, true).withLabel("Maximum projection months (1–600)"), input("budgetEvidence", QFieldType.TEXT, true)), (in, out) ->
      {
         long id = new com.kof22.carlai.domain.DebtPlans(service).compare(CarlService.Scope.privateFor(principal()), UUID.fromString(in.getValueString("requestId")), in.getValueLocalDate("asOf"), in.getValueString("currency"), new BigDecimal(in.getValueString("monthlyBudget")), in.getValueInteger("horizon"), in.getValueString("budgetEvidence"));
         out.addValue("result", service.artifact(principal(), id).get("facts").toString() + "\nSaved comparison " + id + ". Monthly estimates, not an issuer quote or verified affordability.");
      }));
      MonarchUploadProcess.register(instance, app, service);
      PlanProcesses.register(instance, app, service);
      PlanEffectProcesses.register(instance, app, service);
      VendorProcesses.register(instance, app, service);
      BudgetProcesses.register(instance, app, service);
      BalanceSheetProcesses.register(instance, app, service);
      PreferenceProcesses.register(instance, app, service);
      ReportProcesses.register(instance, app, service);
      ArtifactExportProcesses.register(instance, app, service);
      CashProcesses.register(instance, app, service);
      GoalProcesses.register(instance, app, service);
      OfferProcesses.register(instance, app, service);
      PortfolioProcesses.register(instance, app, service);
      PurchaseProcesses.register(instance, app, service);
      TaxProcesses.register(instance, app, service);
      TaxPlanningProcesses.register(instance, app, service);
      RentalProcesses.register(instance, app, service);
      RentalReviewProcesses.register(instance, app, service);
      ExpenseProcesses.register(instance, app, service);
      CalendarProcesses.register(instance, app, service, calendars);
      AvailabilityProcesses.register(instance, app, service);
      CarlTalkProcesses.register(instance, app, talk);
      CarlDashboards.register(instance, service);
      CarlNavigation.apply(instance, app);
      return app;
   }



   private static QTableMetaData artifactTable()
   {
      return table("carlArtifacts", "Reports, Plans and Drafts", "carl_artifact_view", "created_at:I,kind:S,version:L,period_start:D,period_end:D,facts:T,narrative:T,limitations:T,narration_state:S,status_label:S,stale:B", false);
   }



   static QTableMetaData table(String name, String label, String physical, String fields)
   {
      return table(name, label, physical, fields, true);
   }



   static QTableMetaData table(String name, String label, String physical, String fields, boolean evidence)
   {
      var table = new QTableMetaData().withFields(new java.util.LinkedHashMap<>()).withName(name).withLabel(label).withBackendName("agentOperations")
         .withBackendDetails(new PostgreSQLTableBackendDetails().withTableName(physical)).withPrimaryKeyField("id")
         .withRecordLabelFormat("%s").withRecordLabelFields("title")
         .withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withRecordSecurityLock(new RecordSecurityLock().withSecurityKeyType("userId").withFieldName("principal"))
         .withField(field("id", QFieldType.LONG)).withField(field("principal", QFieldType.STRING).withIsHidden(true)).withField(field("title", QFieldType.STRING))
         .withoutCapabilities(Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT);
      if(evidence)
      {
         table.withField(field("evidence", QFieldType.TEXT));
      }
      for(String definition : fields.split(","))
      {
         String[] parts = definition.split(":");
         QFieldType type = switch(parts[1])
         {
            case "M" -> QFieldType.DECIMAL;
            case "D" -> QFieldType.DATE;
            case "I" -> QFieldType.DATE_TIME;
            case "L" -> QFieldType.LONG;
            case "B" -> QFieldType.BOOLEAN;
            case "T" -> QFieldType.TEXT;
            default -> QFieldType.STRING;
         };
         table.withField(field(parts[0], type));
      }
      var orderedFields = new java.util.ArrayList<String>();

      for(String definition : fields.split(","))
      {
         orderedFields.add(definition.split(":")[0]);
      }
      table.withSection(new QFieldSection().withName("identity").withLabel("Record").withTier(com.kingsrook.qqq.backend.core.model.metadata.tables.Tier.T1).withFieldNames(List.of("title", "id")));
      table.withSection(new QFieldSection().withName("details").withLabel("Details").withTier(com.kingsrook.qqq.backend.core.model.metadata.tables.Tier.T2).withGridColumns(1).withFieldNames(orderedFields));
      if(evidence)
      {
         table.withSection(new QFieldSection().withName("sources").withLabel("Source evidence").withTier(com.kingsrook.qqq.backend.core.model.metadata.tables.Tier.T2).withGridColumns(1).withFieldNames(List.of("evidence")));
      }
      return table;
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withLabel(name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + name.substring(1).replace('_', ' ')).withBackendName(name).withIsEditable(false);
   }



   private static QFieldMetaData input(String name, QFieldType type, boolean required)
   {
      var field = new QFieldMetaData(name, type).withIsRequired(required);
      String source = switch(name)
      {
         case "visibility" -> "carlVisibility";
         case "status" -> "carlBillStatus";
         case "kind" -> "carlAccountKind";
         case "purpose" -> "carlDraftPurpose";
         case "workId" -> "carlWork";
         case "vendorId" -> "carlVendors";
         case "recordId" -> "carlBills";
         default -> null;
      };
      if(source != null)
      {
         field.withPossibleValueSourceName(source);
      }
      return field;
   }



   static QProcessMetaData process(String name, String label, List<QFieldMetaData> fields, BackendStep handler)
   {
      var form = new QFrontendStepMetaData().withName("input").withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM));
      fields.stream().filter(field -> !field.getName().equals("requestId")).forEach(form::withFormField);
      return new QProcessMetaData().withName(name).withLabel(label).withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withStep(new QBackendStepMetaData().withName("prepare").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
         {
            principal();
            out.addValue("requestId", UUID.randomUUID().toString());
         })))
         .withStep(form).withStep(new QBackendStepMetaData().withName("execute").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
         {
            try
            {
               handler.run(in, out);
               if(out.getValue("result") != null && out.getValue("result.html") == null)
               {
                  String text = out.getValueString("result").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
                  out.addValue("result.html", "<div style=\"white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6\">" + text + "</div>");
               }
            }
            catch(SecurityException denied)
            {
               throw new QException("Carl record or operation unavailable");
            }
            catch(RuntimeException invalid)
            {
               throw new QException("Carl request rejected: " + invalid.getMessage());
            }
         })))
         .withStep(new QFrontendStepMetaData().withName("result").withComponent(new QFrontendComponentMetaData().withType(QComponentType.HTML)));
   }



   static void choices(QInstance instance, String name, List<String> values)
   {
      var source = new QPossibleValueSource().withName(name).withType(QPossibleValueSourceType.ENUM);
      values.forEach(value -> source.addEnumValue(new QPossibleValue<>(value, value.replace('_', ' '))));
      instance.addPossibleValueSource(source);
   }



   static void add(QInstance instance, QAppMetaData app, QProcessMetaData process)
   {
      instance.addProcess(process);
      app.withChild(process);
   }



   static String principal() throws QException
   {
      OperatorPermissions.check(Role.OPERATOR);
      var session = QContext.getQSession();
      if(session == null || session.getUser() == null || session.getUser().getIdReference() == null || !session.hasSecurityKeyValue("userId", session.getUser().getIdReference()))
      {
         throw new QException("Verified household principal required");
      }
      return session.getUser().getIdReference();
   }
}
