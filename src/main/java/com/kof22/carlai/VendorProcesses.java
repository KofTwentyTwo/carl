/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.interfaces.QStorageInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.VendorRecords;


/** Native human review/maintenance and protected plain-text draft downloads, never sends. */
public final class VendorProcesses
{
   static final String STORAGE = "carlProtectedVendorDrafts";
   static final String DOWNLOAD = "carlDownloadVendorDraft";
   private VendorProcesses()
   {
   }



   /** Registers narrow domain processes after Carl's base vendor/work/artifact metadata. */
   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var vendors = new VendorRecords(service);
      instance.getTable("carlVendors").withField(field("revision", QFieldType.LONG));
      instance.getTable("carlWork").withField(field("revision", QFieldType.LONG));
      for(String name : List.of("carlVendors", "carlWork"))
      {
         instance.getTable(name).withSection(new com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection().withName("maintenanceRevision").withTier(com.kingsrook.qqq.backend.core.model.metadata.tables.Tier.T2).withLabel("Review before correction").withFieldNames(List.of("revision")));
      }
      var history = CarlMetadata.table("carlDraftVersions", "Vendor Draft Versions — Not Sent", "carl_draft_revision_view", "kind:S,version:L,root_id:L,parent_id:L,edited_by:L,reason:T,human_edited:B,edited_at:I,status_label:S,stale:B", false);
      instance.addTable(history);
      app.withChild(history);
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCorrectVendor", "Correct Vendor and Contact", List.of(
         field("vendorId", QFieldType.LONG).withPossibleValueSourceName("carlVendors"), field("expectedRevision", QFieldType.LONG), field("title", QFieldType.STRING), field("category", QFieldType.STRING), field("contact", QFieldType.STRING).withIsRequired(false), field("verified", QFieldType.BOOLEAN), field("reason", QFieldType.TEXT)), (in, out) ->
         {
            vendors.correctVendor(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("vendorId")), Long.parseLong(in.getValueString("expectedRevision")), new VendorRecords.Vendor(in.getValueString("title"), in.getValueString("category"), in.getValueString("contact"), Boolean.TRUE.equals(in.getValueBoolean("verified"))), in.getValueString("reason"));
            out.addValue("result", "Vendor corrected with attribution. Existing drafts may be stale; no communication was sent.");
         }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlMaintainVendorWork", "Update Vendor Work and Follow-up", List.of(
         field("workId", QFieldType.LONG).withPossibleValueSourceName("carlWork"), field("expectedRevision", QFieldType.LONG), field("title", QFieldType.STRING), field("workStatus", QFieldType.STRING).withPossibleValueSourceName("carlWorkStatus"), field("assignee", QFieldType.LONG).withIsRequired(false).withPossibleValueSourceName("carlMembers"), field("followUp", QFieldType.DATE).withIsRequired(false), field("commitmentEvidence", QFieldType.TEXT).withIsRequired(false), field("reason", QFieldType.TEXT)), (in, out) ->
         {
            String assignee = in.getValueString("assignee");
            vendors.correctWork(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("workId")), Long.parseLong(in.getValueString("expectedRevision")), new VendorRecords.Work(in.getValueString("title"), in.getValueString("workStatus"), assignee == null || assignee.isBlank() ? null : Long.valueOf(assignee), in.getValueLocalDate("followUp"), in.getValueString("commitmentEvidence")), in.getValueString("reason"));
            out.addValue("result", "Work updated with history. Completion and commitments remain attributed human assertions.");
         }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlEditVendorDraft", "Edit Saved Vendor Draft", List.of(draft(), field("expectedVersion", QFieldType.INTEGER), field("draftText", QFieldType.TEXT).withLabel("Human-edited text — review every claim; not sent"), field("reason", QFieldType.TEXT)), (in, out) ->
      {
         long id = vendors.editDraft(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("draftId")), in.getValueInteger("expectedVersion"), in.getValueString("draftText"), in.getValueString("reason"));
         out.addValue("result", vendors.copy(CarlMetadata.principal(), id));
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCopyVendorDraft", "Review and Copy Vendor Draft", List.of(draft()), (in, out) -> out.addValue("result", vendors.copy(CarlMetadata.principal(), Long.parseLong(in.getValueString("draftId"))))));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlVendorDraftHistory", "Review Draft Version History", List.of(draft(), field("beforeVersion", QFieldType.INTEGER).withLabel("Versions before (1001 starts latest)"), field("pageSize", QFieldType.INTEGER).withLabel("Versions per page (1–100)")), (in, out) ->
      {
         var values = vendors.history(CarlMetadata.principal(), Long.parseLong(in.getValueString("draftId")), in.getValueInteger("beforeVersion"), in.getValueInteger("pageSize"));
         var text = new StringBuilder("Immutable versions, newest first. Use Review and Copy for a selected record.\n");
         for(var value : values)
         {
            text.append("Version ").append(value.get("version")).append(" / record ").append(value.get("id")).append(" / ").append(value.get("status_label")).append(" / source stale: ").append(value.get("stale")).append('\n');
         }
         out.addValue("result", text.toString());
      }));
      instance.addBackend(new DraftBackend(vendors));
      var storage = new com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData().withName(STORAGE).withLabel("Protected Vendor Draft Downloads").withPermissionRules(com.kof22.agentadmin.OperatorPermissions.require(com.kof22.agentcore.security.Role.OPERATOR))
         .withBackendName("carlVendorDraftStorage").withPrimaryKeyField("id").withField(field("id", QFieldType.STRING))
         .withoutCapabilities(Capability.TABLE_QUERY, Capability.TABLE_GET, Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT);
      instance.addTable(storage);
      var download = CarlMetadata.process(DOWNLOAD, "Download Vendor Draft — Not Sent", List.of(draft()), (in, out) ->
      {
         long draft = Long.parseLong(in.getValueString("draftId"));
         UUID exported = vendors.export(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), draft);
         com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload.registerStorage(new StorageInput(STORAGE).withReference(exported.toString()));
         out.addValue("downloadFileName", "Carl-vendor-draft-" + draft + ".txt");
         out.addValue("storageTableName", STORAGE);
         out.addValue("storageReference", exported.toString());
      });
      ((QFrontendStepMetaData) download.getStep("result")).withComponent(new QFrontendComponentMetaData().withType(QComponentType.DOWNLOAD_FORM));
      CarlMetadata.add(instance, app, download);
   }



   private static QFieldMetaData draft()
   {
      return field("draftId", QFieldType.LONG).withPossibleValueSourceName("carlArtifacts").withLabel("Saved vendor draft record");
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }

   /** Storage is read-only and rechecks current domain access on the actual native request. */
   public static final class DraftBackend extends QBackendMetaData
   {
      private final transient VendorRecords vendors;
      DraftBackend(VendorRecords vendors)
      {
         this.vendors = vendors;
         withName("carlVendorDraftStorage");
         withBackendType(DraftModule.class);
         QBackendModuleDispatcher.registerBackendModule(new DraftModule());
      }
   }



   /** Implements only storage reads; source text cannot choose a path or a backend. */
   public static final class DraftModule implements QBackendModuleInterface
   {
      @Override
      public String getBackendType()
      {
         return "carlVendorDrafts";
      }



      @Override
      public Class<? extends QBackendMetaData> getBackendMetaDataClass()
      {
         return DraftBackend.class;
      }



      @Override
      public QStorageInterface getStorageInterface()
      {
         return new QStorageInterface()
         {
            @Override
            public OutputStream createOutputStream(StorageInput input) throws QException
            {
               throw new QException("Use the authorized vendor draft export process");
            }



            @Override
            public InputStream getInputStream(StorageInput input) throws QException
            {
               if(!STORAGE.equals(input.getTableName()) || !(input.getBackend() instanceof DraftBackend backend))
               {
                  throw new QException("Vendor draft export unavailable");
               }
               try
               {
                  return new ByteArrayInputStream(backend.vendors.load(CarlMetadata.principal(), UUID.fromString(input.getReference())));
               }
               catch(IllegalArgumentException | SecurityException denied)
               {
                  throw new QException("Vendor draft export unavailable");
               }
            }
         };
      }
   }
}
