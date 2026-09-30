/*
 * Copyright (C) 2026 KofTwentyTwo
 */
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
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.PlanLifecycle;


/** Native review/confirmation workflows retain the revision loaded before human input. */
final class PlanProcesses
{
   private PlanProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var plans = new PlanLifecycle(service);
      var table = CarlMetadata.table("carlPlans", "Agreed Plans", "carl_plan_view", "source_artifact_id:L,version:L,state:S,source_stale:B,updated_at:I");
      var steps = CarlMetadata.table("carlPlanSteps", "Plan Tasks", "carl_native_plan_step_view", "plan_id:L,assignee_id:L,due_date:D,location:S,status:S,checkin:T,updated_at:I", false).withField(new QFieldMetaData("id", QFieldType.STRING).withBackendName("id"));
      var members = CarlMetadata.table("carlMembers", "Family Members", "carl_member_view", "active:B", false);
      for(var current : List.of(table, steps, members))
      {
         instance.addTable(current);
         app.withChild(current);
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(current.getName()));
      }
      var exports = new com.kof22.carlai.domain.PlanExports(service);
      instance.addBackend(new CarlExportBackend(exports));
      instance.addTable(new com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData().withName(CarlExportBackend.TABLE).withLabel("Protected Plan Exports").withBackendName("carlExportStorage").withPrimaryKeyField("id").withField(field("id", QFieldType.STRING, false)).withPermissionRules(OperatorPermissions.require(Role.OPERATOR)).withoutCapabilities(com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_QUERY, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_GET, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_INSERT, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_UPDATE, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_DELETE, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_EXPORT));
      var exportProcess = CarlMetadata.process("carlExportPlan", "Download Plan PDF", List.of(field("planId", QFieldType.LONG, true).withPossibleValueSourceName("carlPlans")), (in, out) ->
      {
         long plan = Long.parseLong(in.getValueString("planId"));
         UUID export = exports.generate(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), plan);
         var storage = new com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput(CarlExportBackend.TABLE).withReference(export.toString());
         com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload.registerStorage(storage);
         out.addValue("downloadFileName", "Carl-plan-" + plan + ".pdf");
         out.addValue("storageTableName", CarlExportBackend.TABLE);
         out.addValue("storageReference", export.toString());
      });
      ((QFrontendStepMetaData) exportProcess.getStep("result")).withComponent(new QFrontendComponentMetaData().withType(QComponentType.DOWNLOAD_FORM));
      CarlMetadata.add(instance, app, exportProcess);
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCreatePlan", "Propose Plan from Comparison", List.of(field("sourceArtifact", QFieldType.LONG, true).withPossibleValueSourceName("carlArtifacts"), field("title", QFieldType.STRING, true), field("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         long id = plans.create(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("sourceArtifact")), in.getValueString("title"), in.getValueString("reason"));
         out.addValue("result", "Draft plan saved: " + id + ". Define tasks and review before agreement.");
      }));
      register(instance, app, plans, "carlPlanTask", "Assign Plan Task", List.of(field("title", QFieldType.STRING, true), field("assignee", QFieldType.LONG, true).withPossibleValueSourceName("carlMembers"), field("dueDate", QFieldType.DATE, true), field("location", QFieldType.STRING, true), field("dependency", QFieldType.STRING, false).withPossibleValueSourceName("carlPlanSteps"), field("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         String dependency = in.getValueString("dependency");
         int version = plans.step(CarlMetadata.principal(), Long.parseLong(in.getValueString("planId")), in.getValueInteger("expectedVersion"), UUID.fromString(in.getValueString("taskId")), in.getValueString("title"), Long.parseLong(in.getValueString("assignee")), in.getValueLocalDate("dueDate"), in.getValueString("location"), dependency == null || dependency.isBlank() ? null : UUID.fromString(dependency), in.getValueString("reason"));
         out.addValue("result", "Task saved. Plan revision " + version + " is a draft awaiting review.");
      });
      register(instance, app, plans, "carlReplan", "Replan with Current Comparison", List.of(field("sourceArtifact", QFieldType.LONG, true).withPossibleValueSourceName("carlArtifacts"), field("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         int version = plans.rebase(CarlMetadata.principal(), Long.parseLong(in.getValueString("planId")), in.getValueInteger("expectedVersion"), Long.parseLong(in.getValueString("sourceArtifact")), in.getValueString("reason"));
         out.addValue("result", "Plan revision " + version + " is a draft with refreshed assumptions. Prior versions and completed work remain in history. Review all retained tasks and evidence before renewed agreement.");
      });
      register(instance, app, plans, "carlAgreePlan", "Agree to Plan", List.of(field("confirm", QFieldType.BOOLEAN, true).withLabel("I agree to this plan and its assumptions"), field("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         if(!Boolean.TRUE.equals(in.getValueBoolean("confirm")))
         {
            throw new QException("Explicit agreement required");
         }
         int version = plans.agree(CarlMetadata.principal(), Long.parseLong(in.getValueString("planId")), in.getValueInteger("expectedVersion"), in.getValueString("reason"));
         out.addValue("result", "Plan revision " + version + " agreed. Family members execute its financial steps; Carl has not made any payment or commitment.");
      });
      CarlMetadata.choices(instance, "carlTaskStatus", List.of("TODO", "REPORTED_COMPLETE", "VERIFIED_COMPLETE", "BLOCKED"));
      register(instance, app, plans, "carlPlanCheckIn", "Record Plan Check-in", List.of(field("stepId", QFieldType.STRING, true).withPossibleValueSourceName("carlPlanSteps"), field("status", QFieldType.STRING, true).withPossibleValueSourceName("carlTaskStatus"), field("note", QFieldType.TEXT, true), field("evidenceRecord", QFieldType.LONG, false).withPossibleValueSourceName("carlAccounts")), (in, out) ->
      {
         String evidence = in.getValueString("evidenceRecord");
         int version = plans.checkIn(CarlMetadata.principal(), Long.parseLong(in.getValueString("planId")), in.getValueInteger("expectedVersion"), UUID.fromString(in.getValueString("stepId")), in.getValueString("status"), in.getValueString("note"), evidence == null || evidence.isBlank() ? null : Long.parseLong(evidence));
         out.addValue("result", "Check-in retained at plan revision " + version + ". Reported completion remains distinct from evidence-reviewed completion.");
      });
   }



   static void register(QInstance instance, QAppMetaData app, PlanLifecycle plans, String name, String label, List<QFieldMetaData> fields, BackendStep action)
   {
      var details = new QFrontendStepMetaData().withName("review").withLabel("Review and update")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)).withViewField(field("summary", QFieldType.TEXT, false))
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM));
      fields.forEach(details::withFormField);
      var process = new QProcessMetaData().withName(name).withLabel(label).withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withStep(new QFrontendStepMetaData().withName("choose").withLabel("Choose plan").withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)).withFormField(field("planId", QFieldType.LONG, true).withPossibleValueSourceName("carlPlans")))
         .withStep(new QBackendStepMetaData().withName("load").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
         {
            var loaded = plans.get(CarlMetadata.principal(), Long.parseLong(in.getValueString("planId")));
            var plan = (java.util.Map<?, ?>) loaded.get("plan");
            out.addValue("expectedVersion", ((Number) plan.get("version")).intValue());
            out.addValue("taskId", UUID.randomUUID().toString());
            out.addValue("summary", plan.get("title") + " — " + plan.get("state") + ". Source assumptions stale: " + plan.get("source_stale") + ". Current tasks: " + loaded.get("steps"));
         })))
         .withStep(details).withStep(new QBackendStepMetaData().withName("save").withCode(new QCodeReferenceLambda<BackendStep>(action)))
         .withStep(new QFrontendStepMetaData().withName("result").withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)).withViewField(field("result", QFieldType.TEXT, false)));
      instance.addProcess(process);
      app.withChild(process);
   }



   private static QFieldMetaData field(String name, QFieldType type, boolean required)
   {
      return new QFieldMetaData(name, type).withIsRequired(required);
   }
}
