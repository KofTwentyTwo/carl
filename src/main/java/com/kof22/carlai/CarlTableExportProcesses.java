/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLTableBackendDetails;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.TableExports;


/** Native contextual selected/all-authorized CSV generation and one protected download workspace. */
final class CarlTableExportProcesses
{
   private CarlTableExportProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var sources = columns(instance);
      var exports = new TableExports(service, sources);
      instance.addBackend(new CarlTableExportBackend(exports));
      instance.addTable(new QTableMetaData().withName(CarlTableExportBackend.TABLE).withLabel("Protected Table CSV Storage").withBackendName("carlTableExportStorage")
         .withPrimaryKeyField("id").withField(field("id", QFieldType.STRING, false)).withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withoutCapabilities(Capability.TABLE_QUERY, Capability.TABLE_GET, Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT));
      var history = CarlMetadata.table("carlTableExports", "Dated CSV Export Downloads", "carl_table_export_view", "source_table:S,selection_scope:S,as_of:I,row_count:L,byte_count:L,content_hash:S,text_policy:S,created_at:I,household_changed:B", false)
         .withField(field("id", QFieldType.STRING, false).withIsEditable(false).withBackendName("id"));
      instance.addTable(history);
      app.withChild(history);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("carlTableExports"));
      CarlMetadata.choices(instance, "carlTableExportScope", List.of("SELECTED", "ALL_AUTHORIZED"));
      for(String table : sources.keySet().stream().sorted().toList())
      {
         var process = new QProcessMetaData().withName("carlExportRecords" + table.substring(4)).withLabel("Export Records").withTableName(table)
            .withMinInputRecords(0).withMaxInputRecords(1000).withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
            .withStep(step("prepare", (in, out) ->
            {
               var ids = exports.selection(CarlMetadata.principal(), table, requested(in));
               out.addValue("selectedRecordIds", String.join(",", ids));
               out.addValue("requestId", UUID.randomUUID().toString());
               out.addValue("sourceTable", table);
               out.addValue("selectionSummary", ids.size() + " checked records. SELECTED exports exactly those rows; ALL_AUTHORIZED exports every currently permitted row in " + instance.getTable(table).getLabel() + ". Limits: 50000 rows and 20 MB. " + TableExports.LIMITATION);
            }))
            .withStep(new QFrontendStepMetaData().withName("input").withLabel("Choose Explicit Export Scope")
               .withComponent(component(QComponentType.VIEW_FORM)).withViewField(field("selectionSummary", QFieldType.TEXT, false).withIsEditable(false))
               .withComponent(component(QComponentType.EDIT_FORM)).withFormField(field("scope", QFieldType.STRING, true).withPossibleValueSourceName("carlTableExportScope")))
            .withStep(step("generate", (in, out) ->
            {
               UUID export = exports.generate(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), table, TableExports.Scope.valueOf(in.getValueString("scope")), selected(in));
               var manifest = exports.manifest(CarlMetadata.principal(), export);
               String result = "CSV snapshot " + export + " saved: " + manifest.get("rowCount") + " authorized rows from " + table + "; as-of " + manifest.get("asOf") + ". Open Dated CSV Export Downloads and select this export to download. " + TableExports.LIMITATION;
               out.addValue("exportId", export.toString());
               out.addValue("result", result);
               out.addValue("result.html", "<p>" + result + "</p><p><a href=\"/app/carlTableExports/" + export + "\">Open this dated CSV export</a></p>");
            }))
            .withStep(new QFrontendStepMetaData().withName("result").withComponent(component(QComponentType.HTML)));
         CarlMetadata.add(instance, app, process);
      }
      var download = CarlMetadata.process("carlDownloadTableExport", "Download Saved Table CSV", List.of(field("exportId", QFieldType.STRING, true).withPossibleValueSourceName("carlTableExports")), (in, out) ->
      {
         UUID export = UUID.fromString(in.getValueString("exportId"));
         var manifest = exports.manifest(CarlMetadata.principal(), export);
         ProcessFileDownload.registerStorage(new StorageInput(CarlTableExportBackend.TABLE).withReference(export.toString()));
         out.addValue("downloadFileName", "Carl-" + manifest.get("sourceTable") + "-" + export + ".csv");
         out.addValue("storageTableName", CarlTableExportBackend.TABLE);
         out.addValue("storageReference", export.toString());
         out.addValue("snapshotNotice", TableExports.LIMITATION);
      }).withTableName("carlTableExports");
      ((QFrontendStepMetaData) download.getStep("result")).withComponent(component(QComponentType.DOWNLOAD_FORM));
      CarlMetadata.add(instance, app, download);
   }



   static Map<String, List<TableExports.Column>> columns(QInstance instance)
   {
      var configured = new LinkedHashMap<String, List<TableExports.Column>>();
      for(var source : TableExports.SOURCES.entrySet())
      {
         var table = instance.getTable(source.getKey());
         if(table == null)
         {
            continue;
         }
         if(!"agentOperations".equals(table.getBackendName()) || !(table.getBackendDetails() instanceof PostgreSQLTableBackendDetails details) || !source.getValue().equals(details.getTableName()) || !table.getDisabledCapabilities().containsAll(Set.of(Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE, Capability.TABLE_EXPORT)) || table.getRecordSecurityLocks() == null || table.getRecordSecurityLocks().stream().noneMatch(lock -> "userId".equals(lock.getSecurityKeyType()) && "principal".equals(lock.getFieldName())))
         {
            throw new IllegalArgumentException("Carl export metadata does not match its fixed authoritative view");
         }
         var fields = table.getFields().values().stream().filter(field -> !Boolean.TRUE.equals(field.getIsHidden()) && !Boolean.TRUE.equals(field.getIsHeavy()) && field.getType() != QFieldType.BLOB && (!field.getIsEditable() || field.getName().equals("id")))
            .filter(field -> TableExports.exportable(field.getName())).map(field -> new TableExports.Column(field.getName(), field.getType() == QFieldType.STRING || field.getType() == QFieldType.TEXT)).toList();
         configured.put(source.getKey(), fields);
      }
      return Map.copyOf(configured);
   }



   private static List<String> requested(RunBackendStepInput input) throws QException
   {
      var filter = input.getCallback() == null ? null : input.getCallback().getQueryFilter();
      if(filter == null)
      {
         return List.of();
      }
      if(filter.getSubFilters() != null && !filter.getSubFilters().isEmpty())
      {
         throw new QException("Choose explicit checkbox IDs; implicit filters are not export selections");
      }
      if(filter.getCriteria() == null || filter.getCriteria().isEmpty())
      {
         return List.of();
      }
      if(filter.getCriteria().size() != 1)
      {
         throw new QException("Explicit checkbox ID selection required");
      }
      var criterion = filter.getCriteria().getFirst();
      if(!"id".equals(criterion.getFieldName()) || !Set.of(QCriteriaOperator.IN, QCriteriaOperator.EQUALS).contains(criterion.getOperator()) || criterion.getValues() == null)
      {
         throw new QException("Explicit checkbox ID selection required");
      }
      return criterion.getValues().stream().map(Object::toString).toList();
   }



   private static List<String> selected(RunBackendStepInput input)
   {
      String value = input.getValueString("selectedRecordIds");
      if(value == null)
      {
         throw new SecurityException("Server-owned export selection unavailable");
      }
      return value.isBlank() ? List.of() : List.of(value.split(","));
   }



   private static QBackendStepMetaData step(String name, BackendStep handler)
   {
      return new QBackendStepMetaData().withName(name).withInputData(new QFunctionInputMetaData().withFieldList(List.of(field("sourceTable", QFieldType.STRING, false).withIsEditable(false), field("requestId", QFieldType.STRING, false).withIsEditable(false), field("selectedRecordIds", QFieldType.STRING, false).withIsEditable(false), field("selectionSummary", QFieldType.TEXT, false).withIsEditable(false))))
         .withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
         {
            try
            {
               handler.run(in, out);
            }
            catch(SecurityException denied)
            {
               throw new QException("Table export unavailable");
            }
            catch(RuntimeException invalid)
            {
               throw new QException("Table export rejected: " + invalid.getMessage());
            }
         }));
   }



   private static QFieldMetaData field(String name, QFieldType type, boolean required)
   {
      return new QFieldMetaData(name, type).withIsRequired(required);
   }



   private static QFrontendComponentMetaData component(QComponentType type)
   {
      return new QFrontendComponentMetaData().withType(type);
   }
}
