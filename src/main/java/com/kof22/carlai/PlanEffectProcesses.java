/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.PlanEffects;


/** Human expectations and explicitly selected evidence use Carl's authoritative services. */
final class PlanEffectProcesses
{
   private PlanEffectProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var effects = new PlanEffects(service);
      var table = CarlMetadata.table("carlPlanEffects", "Plan Effect Expectations", "carl_plan_effect_view", "plan_id:L,plan_version:L,step_title:S,effect_kind:S,account_id:L,currency:S,expected_amount:M,period_start:D,period_end:D,opening_principal:M,opening_date:D,opening_evidence:T,actor_id:L,rationale:T,current_plan_version:L,current_plan_state:S,source_stale:B");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("carlPlanEffects"));
      CarlMetadata.choices(instance, "carlPlanEffectKind", Arrays.stream(PlanEffects.Kind.values()).map(Enum::name).toList());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlPlanExpectation", "Record Agreed Plan Expectation", List.of(f("plan", QFieldType.LONG).withPossibleValueSourceName("carlPlans"), f("version", QFieldType.INTEGER), f("step", QFieldType.STRING).withPossibleValueSourceName("carlPlanSteps").withLabel("Agreed plan task"), f("kind", QFieldType.STRING).withPossibleValueSourceName("carlPlanEffectKind"), f("account", QFieldType.LONG).withPossibleValueSourceName("carlAccounts"), f("amount", QFieldType.DECIMAL), f("from", QFieldType.DATE), f("through", QFieldType.DATE), f("reason", QFieldType.TEXT)), (in, out) ->
      {
         long plan = Long.parseLong(in.getValueString("plan"));
         long id = effects.expect(effects.scope(CarlMetadata.principal(), plan), UUID.fromString(in.getValueString("requestId")), plan, Integer.parseInt(in.getValueString("version")), UUID.fromString(in.getValueString("step")), PlanEffects.Kind.valueOf(in.getValueString("kind")), Long.parseLong(in.getValueString("account")), new BigDecimal(in.getValueString("amount")), in.getValueLocalDate("from"), in.getValueLocalDate("through"), in.getValueString("reason"));
         out.addValue("result", "Saved expectation " + id + " for the agreed plan audience. No task completion or financial action was performed.");
      }));
      var selected = CarlMetadata.process("carlComparePlanEffect", "Compare Selected Transactions with Plan", List.of(f("expectation", QFieldType.LONG).withPossibleValueSourceName("carlPlanEffects"), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         List<Long> ids = in.getRecords().stream().map(record -> Long.valueOf(record.getValueString("id"))).toList();
         out.addValue("result", compare(service, effects, in, ids));
      }).withTableName("carlTransactions").withMinInputRecords(1).withMaxInputRecords(100)
         .withStep(0, com.kingsrook.qqq.backend.core.processes.implementations.general.LoadInitialRecordsStep.defineMetaData("carlTransactions"));
      CarlMetadata.add(instance, app, selected);
      CarlMetadata.add(instance, app, CarlMetadata.process("carlComparePrincipalEffect", "Compare Current Principal Statement with Plan", List.of(f("expectation", QFieldType.LONG).withPossibleValueSourceName("carlPlanEffects"), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         out.addValue("result", compare(service, effects, in, List.of()));
      }));
   }



   private static String compare(CarlService service, PlanEffects effects, com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput in, List<Long> ids) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      long id = effects.compare(effects.expectationScope(CarlMetadata.principal(), Long.parseLong(in.getValueString("expectation"))), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("expectation")), ids, in.getValueString("evidence"));
      var saved = service.artifact(CarlMetadata.principal(), id);
      var facts = parse(saved.get("facts").toString());
      return "Saved scoped comparison " + id + "\nOutcome: " + facts.path("outcome").asText() + "\nExpected: " + facts.path("expectation").path("expected_amount").asText() + " " + facts.path("currency").asText() + "\nObserved: " + (facts.path("observedAmount").isNull() ? "Undetermined" : facts.path("observedAmount").asText()) + "\nDifference: " + (facts.path("differenceObservedMinusExpected").isNull() ? "Undetermined" : facts.path("differenceObservedMinusExpected").asText()) + "\n" + facts.path("boundary").asText() + "\nReview complete saved source facts in Carl reports.";
   }



   private static com.fasterxml.jackson.databind.JsonNode parse(String value)
   {
      try
      {
         return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
      }
      catch(com.fasterxml.jackson.core.JsonProcessingException invalid)
      {
         throw new IllegalStateException("Stored report invalid", invalid);
      }
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
