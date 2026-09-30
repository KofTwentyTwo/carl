/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.ArtifactExports;
import com.kof22.carlai.domain.CarlService;


/** Human copy/download workflows over the same permission-controlled report artifacts. */
final class ArtifactExportProcesses
{
   private ArtifactExportProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var exports = new ArtifactExports(service);
      instance.addBackend(new ArtifactExportBackend(exports));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCopyReport", "Review and Copy Saved Report", List.of(report()), (in, out) -> out.addValue("result", exports.copy(CarlMetadata.principal(), Long.parseLong(in.getValueString("reportId"))))));
      for(var format : ArtifactExports.Format.values())
      {
         String table = format == ArtifactExports.Format.PDF ? ArtifactExportBackend.PDF_TABLE : ArtifactExportBackend.TEXT_TABLE;
         String name = format == ArtifactExports.Format.PDF ? "carlDownloadReportPdf" : "carlDownloadReportText";
         instance.addTable(new QTableMetaData().withName(table).withLabel("Protected Report " + format + " Downloads").withPermissionRules(OperatorPermissions.require(Role.OPERATOR)).withBackendName("carlArtifactExportStorage").withPrimaryKeyField("id").withField(new QFieldMetaData("id", QFieldType.STRING))
            .withoutCapabilities(Capability.TABLE_QUERY, Capability.TABLE_GET, Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT));
         var download = CarlMetadata.process(name, "Download Saved Report " + format, List.of(report()), (in, out) ->
         {
            long report = Long.parseLong(in.getValueString("reportId"));
            UUID reference = exports.generate(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), report, format);
            ProcessFileDownload.registerStorage(new StorageInput(table).withReference(reference.toString()));
            out.addValue("downloadFileName", "Carl-report-" + report + (format == ArtifactExports.Format.PDF ? ".pdf" : ".txt"));
            out.addValue("storageTableName", table);
            out.addValue("storageReference", reference.toString());
         });
         ((QFrontendStepMetaData) download.getStep("result")).withComponent(new QFrontendComponentMetaData().withType(QComponentType.DOWNLOAD_FORM));
         CarlMetadata.add(instance, app, download);
      }
   }



   private static QFieldMetaData report()
   {
      return new QFieldMetaData("reportId", QFieldType.LONG).withIsRequired(true).withPossibleValueSourceName("carlArtifacts").withLabel("Saved household, focused or financial report");
   }
}
