/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.general.LoadInitialRecordsStep;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.BulkTransactionClassification;
import com.kof22.carlai.domain.CarlService;


/** Selected transaction corrections require an actual preview and explicit human confirmation. */
final class CarlBulkTransactionProcesses
{
   private CarlBulkTransactionProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var bulk = new BulkTransactionClassification(service);
      CarlMetadata.choices(instance, "carlBulkClassification", List.of("INCOME", "EXPENSE", "UNCLASSIFIED"));
      var process = new QProcessMetaData().withName("carlClassifyTransactions").withLabel("Classify Selected Transactions")
         .withTableName("carlTransactions").withMinInputRecords(1).withMaxInputRecords(100)
         .withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withStep(step("requireSelection", (in, out) ->
         {
            var ids = bulk.selection(CarlMetadata.principal(), requested(in));
            out.addValue("selectedRecordIds", ids.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")));
         }))
         .withStep(LoadInitialRecordsStep.defineMetaData("carlTransactions"))
         .withStep(step("prepare", (in, out) ->
         {
            selected(in);
            if(in.getValueString("requestId") == null)
            {
               out.addValue("requestId", UUID.randomUUID().toString());
            }
         }))
         .withStep(new QFrontendStepMetaData().withName("input").withLabel("Choose Explicit Classification")
            .withComponent(component(QComponentType.RECORD_LIST))
            .withRecordListFields(recordFields(instance))
            .withComponent(component(QComponentType.EDIT_FORM))
            .withFormField(field("classification", QFieldType.STRING, "Classification for all selected rows", true).withPossibleValueSourceName("carlBulkClassification"))
            .withFormField(field("category", QFieldType.STRING, "Reviewed category for all selected rows", true))
            .withFormField(field("reason", QFieldType.TEXT, "Evidence and reason for this correction", true)))
         .withStep(step("preview", (in, out) ->
         {
            var preview = bulk.preview(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), selected(in), in.getValueString("classification"), in.getValueString("category"), in.getValueString("reason"));
            out.setRecords(preview.records().stream().map(values ->
            {
               var record = new com.kingsrook.qqq.backend.core.model.data.QRecord();
               for(String name : List.of("id", "effective_date", "account_id", "title", "amount", "currency", "classification", "category"))
               {
                  record.setValue(name, (java.io.Serializable) values.get(name));
               }
               return record;
            }).toList());
            out.addValue("previewToken", preview.token());
            out.addValue("preview", preview.summary());
            out.addValue("confirm", false);
         }))
         .withStep(new QFrontendStepMetaData().withName("review").withLabel("Review All Corrections Before Applying")
            .withComponent(component(QComponentType.RECORD_LIST)).withRecordListFields(recordFields(instance))
            .withComponent(component(QComponentType.VIEW_FORM)).withViewField(field("preview", QFieldType.TEXT, "Proposed corrections — not yet applied", false))
            .withComponent(component(QComponentType.EDIT_FORM)).withFormField(field("confirm", QFieldType.BOOLEAN, "I reviewed these selected records, categories and classification changes", true)))
         .withStep(step("apply", (in, out) ->
         {
            int count = bulk.apply(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), selected(in), in.getValueString("classification"), in.getValueString("category"), in.getValueString("reason"), in.getValueString("previewToken"), Boolean.TRUE.equals(in.getValueBoolean("confirm")));
            String result = count + " selected transaction corrections committed together with your attribution. Original imported source evidence is unchanged. No money moved.";
            out.addValue("result", result);
            out.addValue("result.html", "<div style=\"white-space:pre-wrap\">" + result + "</div>");
         }))
         .withStep(new QFrontendStepMetaData().withName("result").withComponent(component(QComponentType.HTML)));
      CarlMetadata.add(instance, app, process);
   }



   private static List<QFieldMetaData> recordFields(QInstance instance)
   {
      return List.of("effective_date", "account_id", "title", "amount", "currency", "classification", "category").stream().map(name -> instance.getTable("carlTransactions").getField(name).clone()).toList();
   }



   private static List<Long> requested(com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput input) throws QException
   {
      var filter = input.getCallback() == null ? null : input.getCallback().getQueryFilter();
      if(filter == null || filter.getCriteria() == null || filter.getCriteria().size() != 1 || (filter.getSubFilters() != null && !filter.getSubFilters().isEmpty()))
      {
         throw new QException("Select explicit transaction rows by their checkboxes; a general filter is not a reviewed bounded selection");
      }
      var criterion = filter.getCriteria().getFirst();
      if(!"id".equals(criterion.getFieldName()) || !java.util.Set.of(com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.IN, com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.EQUALS).contains(criterion.getOperator()) || criterion.getValues() == null)
      {
         throw new QException("Explicit selected transaction IDs required");
      }
      return criterion.getValues().stream().map(value -> Long.valueOf(value.toString())).toList();
   }



   private static List<Long> selected(com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput input) throws QException
   {
      if(input.getRecords() == null || input.getRecords().isEmpty() || input.getRecords().size() > 100)
      {
         throw new QException("Select one to 100 readable transaction records");
      }
      var actual = input.getRecords().stream().map(row -> Long.valueOf(row.getValueString("id"))).sorted().toList();
      String original = input.getValueString("selectedRecordIds");
      if(original == null || !actual.equals(java.util.Arrays.stream(original.split(",")).map(Long::valueOf).sorted().toList()))
      {
         throw new QException("Selected records changed or are unavailable; no subset can be classified");
      }
      return actual;
   }



   private static QBackendStepMetaData step(String name, BackendStep handler)
   {
      var metadata = new QBackendStepMetaData().withName(name).withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
      {
         try
         {
            handler.run(in, out);
         }
         catch(SecurityException denied)
         {
            throw new QException("Carl record or operation unavailable");
         }
         catch(RuntimeException invalid)
         {
            throw new QException("Carl request rejected: " + invalid.getMessage());
         }
      }));
      if(name.equals("requireSelection"))
      {
         metadata.withInputData(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData().withFieldList(List.of(new QFieldMetaData("requestId", QFieldType.STRING).withIsEditable(false), new QFieldMetaData("previewToken", QFieldType.STRING).withIsEditable(false), new QFieldMetaData("selectedRecordIds", QFieldType.STRING).withIsEditable(false))));
      }
      return metadata;
   }



   private static QFrontendComponentMetaData component(QComponentType type)
   {
      return new QFrontendComponentMetaData().withType(type);
   }



   private static QFieldMetaData field(String name, QFieldType type, String label, boolean required)
   {
      return new QFieldMetaData(name, type).withLabel(label).withIsRequired(required);
   }
}
