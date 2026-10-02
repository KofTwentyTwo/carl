/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload;
import com.kingsrook.qqq.backend.core.exceptions.QException;
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
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DocumentRecords;


/** Human native upload, immutable preview, explicit confirmation and protected original download. */
final class DocumentProcesses
{
   private DocumentProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var documents = new DocumentRecords(service);
      instance.addBackend(new DocumentDownloadBackend(documents));
      instance.addTable(new QTableMetaData().withName(DocumentDownloadBackend.TABLE).withLabel("Protected Original Documents").withBackendName("carlDocumentStorage")
         .withPrimaryKeyField("id").withField(field("id", QFieldType.STRING, false)).withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withoutCapabilities(Capability.TABLE_QUERY, Capability.TABLE_GET, Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT));
      CarlMetadata.choices(instance, "carlDocumentSourceType", List.of("HISTORICAL_NOTE", "SOURCE_DOCUMENT"));
      var form = new QFrontendStepMetaData().withName("input").withLabel("Supply Household Finance or History Evidence").withComponent(component(QComponentType.EDIT_FORM))
         .withFormField(field("documentFile", QFieldType.BLOB, true).withLabel("Original UTF-8 text or PDF;20 MB maximum").withFieldAdornment(AdornmentType.FileUploadAdornment.newFieldAdornment().withValue(AdornmentType.FileUploadAdornment.formatDragAndDrop())))
         .withFormField(field("title", QFieldType.STRING, true))
         .withFormField(field("sourceIdentity", QFieldType.STRING, true).withLabel("Supplied source identity; labels and URLs are never fetched"))
         .withFormField(field("sourceType", QFieldType.STRING, true).withPossibleValueSourceName("carlDocumentSourceType"))
         .withFormField(field("documentDate", QFieldType.DATE, false).withLabel("Document date (leave unknown blank)"))
         .withFormField(field("asOfDate", QFieldType.DATE, false).withLabel("As-of date (leave unknown blank)"))
         .withFormField(field("visibility", QFieldType.STRING, false).withLabel("PRIVATE by default; choose FAMILY explicitly").withPossibleValueSourceName("carlVisibility"))
         .withFormField(field("provenance", QFieldType.TEXT, true).withLabel("How this supplied historical evidence was obtained"));
      var process = new QProcessMetaData().withName("carlRegisterDocument").withLabel("Upload and Review Household Document").withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withStep(step("prepare", (in, out) ->
         {
            CarlMetadata.principal();
            out.addValue("requestId", UUID.randomUUID().toString());
         }))
         .withStep(form)
         .withStep(step("preview", (in, out) ->
         {
            Object uploaded = in.getValue("documentFile");
            if(!(uploaded instanceof List<?> files) || files.size() != 1 || !(files.getFirst() instanceof StorageInput file) || !CarlUploadBackend.TABLE.equals(file.getTableName()))
            {
               throw new QException("Use the native document file upload control");
            }
            UUID request = UUID.fromString(in.getValueString("requestId"));
            var source = new DocumentRecords.Source(in.getValueString("title"), in.getValueString("visibility"), in.getValueString("sourceIdentity"), in.getValueString("sourceType"), date(in.getValueString("documentDate")), date(in.getValueString("asOfDate")), in.getValueString("provenance"));
            UUID review = documents.preview(CarlMetadata.principal(), request, file.getReference(), source);
            out.addValue("reviewId", review.toString());
            out.addValue("preview", documents.describe(CarlMetadata.principal(), review));
         }))
         .withStep(new QFrontendStepMetaData().withName("review").withLabel("Review Exact Original and Source Metadata")
            .withComponent(component(QComponentType.VIEW_FORM)).withViewField(field("preview", QFieldType.TEXT, false).withIsEditable(false))
            .withComponent(component(QComponentType.EDIT_FORM)).withFormField(field("confirm", QFieldType.BOOLEAN, true).withLabel("I reviewed this source, audience and original; content remains supplied and unverified")))
         .withStep(step("confirm", (in, out) -> out.addValue("result", "Household document " + documents.confirm(CarlMetadata.principal(), UUID.fromString(in.getValueString("reviewId")), Boolean.TRUE.equals(in.getValueBoolean("confirm"))) + " registered. " + DocumentRecords.LIMITATION)))
         .withStep(new QFrontendStepMetaData().withName("result").withComponent(component(QComponentType.VIEW_FORM)).withViewField(field("result", QFieldType.TEXT, false)));
      CarlMetadata.add(instance, app, process);
      var download = CarlMetadata.process("carlDownloadDocument", "Download Household Document Original", List.of(field("documentId", QFieldType.LONG, true).withPossibleValueSourceName("carlDocuments")), (in, out) ->
      {
         long document = Long.parseLong(in.getValueString("documentId"));
         UUID token = documents.download(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), document);
         ProcessFileDownload.registerStorage(new StorageInput(DocumentDownloadBackend.TABLE).withReference(token.toString()));
         var metadata = documents.metadata(CarlMetadata.principal(), document);
         String media = metadata.get("media_type").toString();
         String extension = media.equals("application/pdf") ? ".pdf" : media.startsWith("text/plain") ? ".txt" : ".bin";
         out.addValue("downloadFileName", "Carl-document-" + document + extension);
         out.addValue("storageTableName", DocumentDownloadBackend.TABLE);
         out.addValue("storageReference", token.toString());
      });
      ((QFrontendStepMetaData) download.getStep("result")).withComponent(component(QComponentType.DOWNLOAD_FORM));
      CarlMetadata.add(instance, app, download);
   }



   private static LocalDate date(String value)
   {
      return value == null || value.isBlank() ? null : LocalDate.parse(value);
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
      var step = new QBackendStepMetaData().withName(name).withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
      {
         try
         {
            handler.run(in, out);
         }
         catch(SecurityException denied)
         {
            throw new QException("Document operation unavailable");
         }
         catch(RuntimeException invalid)
         {
            throw new QException("Document request rejected: " + invalid.getMessage());
         }
      }));
      step.withInputData(new QFunctionInputMetaData().withFieldList(List.of(field("requestId", QFieldType.STRING, false).withIsEditable(false), field("reviewId", QFieldType.STRING, false).withIsEditable(false))));
      return step;
   }
}
