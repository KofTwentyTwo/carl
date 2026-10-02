/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.fields.AdornmentType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.middleware.javalin.QJavalinMetaData;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.MonarchImportWorkflow;


/** Native file upload, explicit preview and apply. The process transport owns uploaded references. */
final class MonarchUploadProcess
{
   private MonarchUploadProcess()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var workflow = new MonarchImportWorkflow(service);
      instance.addBackend(new CarlUploadBackend(workflow));
      instance.addTable(new QTableMetaData().withName(CarlUploadBackend.TABLE).withLabel("Private Upload Storage")
         .withBackendName("carlUploadStorage").withPrimaryKeyField("id").withField(new QFieldMetaData("id", QFieldType.STRING))
         .withPermissionRules(OperatorPermissions.require(Role.ADMIN))
         .withoutCapabilities(Capability.TABLE_QUERY, Capability.TABLE_GET, Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT));
      QJavalinMetaData.ofOrWithNew(instance).withUploadedFileArchiveTableName(CarlUploadBackend.TABLE);
      var process = new QProcessMetaData().withName("carlImportMonarch").withLabel("Import from Monarch")
         .withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withStep(new QFrontendStepMetaData().withName("upload").withLabel("Upload Settings Exports")
            .withComponent(component(QComponentType.EDIT_FORM)).withFormField(file("transactionsFile", "Transactions CSV (optional)"))
            .withFormField(file("balancesFile", "Balances CSV (optional)")))
         .withStep(step("preview", (in, out) ->
         {
            String principal = CarlMetadata.principal();
            var refs = new ArrayList<String>();
            references(in, "transactionsFile", refs);
            references(in, "balancesFile", refs);
            UUID review = workflow.preview(principal, refs);
            out.addValue("reviewId", review.toString());
            out.addValue("preview", workflow.describe(principal, review));
         }).withInputData(new QFunctionInputMetaData().withFieldList(List.of(field("reviewId", QFieldType.STRING, false).withIsEditable(false)))))
         .withStep(new QFrontendStepMetaData().withName("review").withLabel("Review Before Applying")
            .withComponent(component(QComponentType.VIEW_FORM)).withViewField(field("reviewId", QFieldType.STRING, false).withIsEditable(false)).withViewField(field("preview", QFieldType.TEXT, false))
            .withComponent(component(QComponentType.EDIT_FORM)).withFormField(field("confirm", QFieldType.BOOLEAN, true).withLabel("I reviewed these imports and account mappings"))
            .withFormField(field("acceptRevisions", QFieldType.BOOLEAN, false).withLabel("Accept listed source revisions and changed balance observations")))
         .withStep(step("apply", (in, out) ->
         {
            if(!Boolean.TRUE.equals(in.getValueBoolean("confirm")))
            {
               throw new QException("Review confirmation required");
            }
            workflow.apply(CarlMetadata.principal(), UUID.fromString(in.getValueString("reviewId")), Boolean.TRUE.equals(in.getValueBoolean("acceptRevisions")));
            out.addValue("result", com.kof22.carlai.domain.NativeMutationReceipt.MONARCH_MESSAGE);
         }))
         .withStep(result());
      instance.addProcess(process);
      app.withChild(process);
      simple(instance, app, "carlRegisterMonarchSources", "Register Source Accounts for Review", List.of(field("reviewId", QFieldType.STRING, true).withPossibleValueSourceName("carlImportReviews"), field("currency", QFieldType.STRING, true), field("confirm", QFieldType.BOOLEAN, true).withLabel("This currency applies to every source account in this review")), (in, out) ->
      {
         if(!Boolean.TRUE.equals(in.getValueBoolean("confirm")))
         {
            throw new QException("Explicit source currency confirmation required");
         }
         int created = workflow.registerSourceAccounts(CarlMetadata.principal(), UUID.fromString(in.getValueString("reviewId")), in.getValueString("currency"));
         out.addValue("result", created + " private source accounts registered for review. Ownership, account kind, liquidity and duplicate relationships remain unknown. Unreviewed balances are excluded from qualified financial totals. Resume the import to store source observations.");
      });
      simple(instance, app, "carlMapMonarch", "Map Monarch Account", List.of(field("sourceLabel", QFieldType.STRING, true), field("accountId", QFieldType.LONG, true).withPossibleValueSourceName("carlAccounts")), (in, out) ->
      {
         workflow.mapAccount(CarlMetadata.principal(), in.getValueString("sourceLabel"), Long.parseLong(in.getValueString("accountId")));
         out.addValue("result", "Mapping saved. Return to the import review to apply.");
      });
      simple(instance, app, "carlResolveMonarchBalance", "Resolve Balance Observation", List.of(field("reviewId", QFieldType.STRING, true).withPossibleValueSourceName("carlImportReviews"), field("logicalRow", QFieldType.INTEGER, true), field("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         UUID id = UUID.fromString(in.getValueString("reviewId"));
         workflow.resolveBalance(CarlMetadata.principal(), id, in.getValueInteger("logicalRow"), in.getValueString("reason"));
         out.addValue("result", workflow.describe(CarlMetadata.principal(), id));
      });
      simple(instance, app, "carlResumeMonarch", "Resume Monarch Review", List.of(field("reviewId", QFieldType.STRING, true).withPossibleValueSourceName("carlImportReviews"), field("confirm", QFieldType.BOOLEAN, true), field("acceptRevisions", QFieldType.BOOLEAN, false)), (in, out) ->
      {
         if(!Boolean.TRUE.equals(in.getValueBoolean("confirm")))
         {
            throw new QException("Review confirmation required");
         }
         workflow.apply(CarlMetadata.principal(), UUID.fromString(in.getValueString("reviewId")), Boolean.TRUE.equals(in.getValueBoolean("acceptRevisions")));
         out.addValue("result", com.kof22.carlai.domain.NativeMutationReceipt.MONARCH_MESSAGE);
      });
   }



   private static void references(RunBackendStepInput input, String key, List<String> refs) throws QException
   {
      Object value = input.getValue(key);
      if(value == null || value.equals(""))
      {
         return;
      }
      if(!(value instanceof List<?> files) || files.size() != 1 || !(files.getFirst() instanceof StorageInput file) || !CarlUploadBackend.TABLE.equals(file.getTableName()))
      {
         throw new QException("Use the native CSV file upload control");
      }
      refs.add(file.getReference());
   }



   private static QFieldMetaData file(String name, String label)
   {
      return field(name, QFieldType.BLOB, false).withLabel(label).withFieldAdornment(AdornmentType.FileUploadAdornment.newFieldAdornment().withValue(AdornmentType.FileUploadAdornment.formatDragAndDrop()));
   }



   private static QFieldMetaData field(String name, QFieldType type, boolean required)
   {
      return new QFieldMetaData(name, type).withIsRequired(required);
   }



   private static QFrontendComponentMetaData component(QComponentType type)
   {
      return new QFrontendComponentMetaData().withType(type);
   }



   private static QBackendStepMetaData step(String name, BackendStep handler)
   {
      return new QBackendStepMetaData().withName(name).withCode(new QCodeReferenceLambda<BackendStep>(handler));
   }



   private static QFrontendStepMetaData result()
   {
      return new QFrontendStepMetaData().withName("result").withComponent(component(QComponentType.VIEW_FORM)).withViewField(field("result", QFieldType.TEXT, false));
   }



   private static void simple(QInstance instance, QAppMetaData app, String name, String label, List<QFieldMetaData> fields, BackendStep handler)
   {
      var form = new QFrontendStepMetaData().withName("input").withComponent(component(QComponentType.EDIT_FORM));
      fields.forEach(form::withFormField);
      var process = new QProcessMetaData().withName(name).withLabel(label).withPermissionRules(OperatorPermissions.require(Role.OPERATOR)).withStep(form).withStep(step("execute", handler)).withStep(result());
      instance.addProcess(process);
      app.withChild(process);
   }
}
