/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
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
import com.kingsrook.qqq.backend.core.processes.implementations.general.LoadInitialRecordsStep;
import com.kof22.carlai.domain.BalanceSheets;
import com.kof22.carlai.domain.CarlService;


/** Native table selection uses current protected views and repeats service authorization at execution. */
final class BalanceSelectionProcesses
{
   private BalanceSelectionProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var sources = CarlMetadata.table("carlBalanceSources", "Accounts and Properties for Review", "carl_balance_selection_view", "source_type:S,currency:S,observed_on:D");
      sources.getField("source_type").withLabel("Record type");
      sources.getField("observed_on").withLabel("Latest supplied observation");
      instance.addTable(sources);
      app.withChild(sources);
      var baselines = CarlMetadata.table("carlRentalBaselines", "Current Rental Reports", "carl_rental_baseline_selection_view", "period_start:D,period_end:D,created_at:I,status_label:S", false);
      instance.addTable(baselines);
      app.withChild(baselines);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("carlRentalBaselines"));
      var policies = new QPossibleValueSource().withName("carlSelectedPropertyValuation").withType(QPossibleValueSourceType.ENUM);
      policies.addEnumValue(new QPossibleValue<>("PROPERTY_ESTIMATE", "Use each property's supplied valuation"));
      policies.addEnumValue(new QPossibleValue<>("LINKED_ACCOUNT", "Use each property's linked asset-account balance"));
      instance.addPossibleValueSource(policies);
      var process = CarlMetadata.process("carlConsolidatedBalanceSheet", "Review Selected Accounts and Properties", List.of(
         new QFieldMetaData("asOf", QFieldType.DATE).withLabel("Balance sheet date").withIsRequired(true),
         new QFieldMetaData("maximumAgeDays", QFieldType.INTEGER).withLabel("Flag observations older than this many days").withIsRequired(true),
         new QFieldMetaData("propertyPolicy", QFieldType.STRING).withLabel("Valuation for all selected properties (leave empty for accounts only)").withPossibleValueSourceName("carlSelectedPropertyValuation")), (in, out) ->
         {
            var accounts = new ArrayList<Long>();
            var properties = new ArrayList<BalanceSheets.PropertyChoice>();
            if(in.getRecords() == null || in.getRecords().isEmpty() || in.getRecords().size() > 200)
            {
               throw new IllegalArgumentException("Choose one to 200 account/property rows first");
            }
            for(var row : in.getRecords())
            {
               long id = Long.parseLong(row.getValue("id").toString());
               switch(row.getValueString("source_type"))
               {
                  case "ACCOUNT" -> accounts.add(id);
                  case "PROPERTY" ->
                  {
                     String policy = in.getValueString("propertyPolicy");
                     if(policy == null || policy.isBlank())
                     {
                        throw new IllegalArgumentException("Choose the valuation method for the selected properties");
                     }
                     properties.add(new BalanceSheets.PropertyChoice(id, BalanceSheets.Valuation.valueOf(policy)));
                  }
                  default -> throw new IllegalArgumentException("Unsupported selected record type");
               }
            }
            long report = new BalanceSheets(service).report(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), LocalDate.parse(in.getValueString("asOf")), Integer.parseInt(in.getValueString("maximumAgeDays")), accounts, properties);
            out.addValue("result", "Saved selected balance sheet " + report + ". Review source dates, valuation precedence, separate currencies and missing information in Reports, Plans and Drafts. This covers only the selected authorized records.");
         }).withTableName("carlBalanceSources").withMinInputRecords(1).withMaxInputRecords(200);
      process.withStep(0, new QBackendStepMetaData().withName("requireSelection").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
      {
         CarlMetadata.principal();
         if(in.getCallback() == null || in.getCallback().getQueryFilter() == null)
         {
            throw new QException("Select readable accounts and properties from their review table before starting this process");
         }
      })));
      process.withStep(1, LoadInitialRecordsStep.defineMetaData("carlBalanceSources"));
      var form = process.getFrontendStep("input");
      form.withComponent(new QFrontendComponentMetaData().withType(QComponentType.RECORD_LIST));
      form.withRecordListFields(List.of(new QFieldMetaData("title", QFieldType.STRING).withLabel("Selected source"), new QFieldMetaData("source_type", QFieldType.STRING).withLabel("Record type"), new QFieldMetaData("currency", QFieldType.STRING).withLabel("Currency")));
      CarlMetadata.add(instance, app, process);
   }
}
