/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FocusedReports;
import com.kof22.carlai.domain.ReportRecovery;


/** Explicit native report creation and reconciliation use authenticated domain services. */
final class ReportProcesses
{
   private ReportProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var reports = new FocusedReports(service);
      var recovery = new ReportRecovery(service);
      CarlMetadata.choices(instance, "carlReportFocus", Arrays.stream(FocusedReports.Focus.values()).map(Enum::name).toList());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlFocusedReport", "Generate Focused Report", List.of(f("focus", QFieldType.STRING).withPossibleValueSourceName("carlReportFocus"), f("from", QFieldType.DATE), f("through", QFieldType.DATE)), (in, out) ->
      {
         var request = UUID.fromString(in.getValueString("requestId"));
         long id = reports.generate(CarlService.Scope.privateFor(CarlMetadata.principal()), request, FocusedReports.Focus.valueOf(in.getValueString("focus")), in.getValueLocalDate("from"), in.getValueLocalDate("through"), null);
         var artifact = service.artifact(CarlMetadata.principal(), id);
         out.addValue("result", "Saved report " + id + ". Request " + request + ". " + artifact.get("status_label") + ". " + artifact.get("limitations") + "\n" + reports.presentation(CarlMetadata.principal(), id));
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCompareBillPeriods", "Compare Bill Periods", List.of(f("beforeFrom", QFieldType.DATE), f("beforeThrough", QFieldType.DATE), f("afterFrom", QFieldType.DATE), f("afterThrough", QFieldType.DATE)), (in, out) ->
      {
         var request = UUID.fromString(in.getValueString("requestId"));
         long id = reports.compareBills(CarlService.Scope.privateFor(CarlMetadata.principal()), request, in.getValueLocalDate("beforeFrom"), in.getValueLocalDate("beforeThrough"), in.getValueLocalDate("afterFrom"), in.getValueLocalDate("afterThrough"), null);
         var artifact = service.artifact(CarlMetadata.principal(), id);
         out.addValue("result", "Saved comparison " + id + ". Request " + request + ". " + artifact.get("limitations") + "\n" + reports.presentation(CarlMetadata.principal(), id));
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlInspectReportRequest", "Inspect Report Request", List.of(f("reportRequest", QFieldType.STRING).withLabel("Original report request UUID")), (in, out) ->
      {
         UUID request = UUID.fromString(in.getValueString("reportRequest"));
         var scope = recovery.storedScope(CarlMetadata.principal(), request);
         out.addValue("result", recovery.inspect(scope, request).toString());
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlReconcileReportRequest", "Reconcile or Terminate Report Request", List.of(f("reportRequest", QFieldType.STRING).withLabel("Original report request UUID"), f("evidence", QFieldType.TEXT).withLabel("Reason for recovery or terminating this generation")), (in, out) ->
      {
         UUID request = UUID.fromString(in.getValueString("reportRequest"));
         var scope = recovery.storedScope(CarlMetadata.principal(), request);
         out.addValue("result", recovery.reconcile(scope, UUID.fromString(in.getValueString("requestId")), request, in.getValueString("evidence")).toString());
      }));
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
