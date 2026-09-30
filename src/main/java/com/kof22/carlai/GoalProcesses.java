
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FinancialGoals;
import com.kof22.carlai.domain.InvestmentPlanning;


/** Native human goal selection and educational investment assumptions. */
final class GoalProcesses
{
   private GoalProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var goals = new FinancialGoals(service);
      var table = CarlMetadata.table("carlFinancialGoals", "Financial Goals and Priorities", "carl_financial_goal_view", "goal_type:S,priority:L,investment_stage_selected:B,horizon_months:L,risk_context:S,currency:S,reserve_need:M,tax_context_supplied:B,holdings_complete:B,costs_complete:B,context_evidence:T,updated_at:I");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      CarlMetadata.choices(instance, "carlGoalKind", List.of("DEBT_FREEDOM", "RENTAL_TAX", "INVESTMENT", "OTHER"));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCreateGoal", "Record Financial Priority", List.of(field("title", QFieldType.STRING), field("kind", QFieldType.STRING).withPossibleValueSourceName("carlGoalKind"), field("priority", QFieldType.INTEGER), field("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), field("evidence", QFieldType.TEXT)), (in, out) -> out.addValue("result", "Owner-selected financial goal saved: " + goals.create(CarlMetadata.principal(), in.getValueString("title"), in.getValueString("kind"), in.getValueInteger("priority"), in.getValueString("visibility"), in.getValueString("evidence")) + ". No balance threshold automatically selects investment readiness.")));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlInvestmentContext", "Record Investment Context", List.of(field("goal", QFieldType.LONG).withPossibleValueSourceName("carlFinancialGoals"), field("currency", QFieldType.STRING), field("stageSelected", QFieldType.BOOLEAN), new QFieldMetaData("horizon", QFieldType.INTEGER), new QFieldMetaData("riskContext", QFieldType.STRING), new QFieldMetaData("reserve", QFieldType.DECIMAL), field("taxContextSupplied", QFieldType.BOOLEAN), field("holdingsComplete", QFieldType.BOOLEAN), field("costsComplete", QFieldType.BOOLEAN), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         String reserve = in.getValueString("reserve");
         String risk = in.getValueString("riskContext");
         var context = new InvestmentPlanning.Context(Boolean.TRUE.equals(in.getValueBoolean("stageSelected")), in.getValueInteger("horizon"), risk == null || risk.isBlank() ? null : risk, reserve == null || reserve.isBlank() ? null : new BigDecimal(reserve), Boolean.TRUE.equals(in.getValueBoolean("taxContextSupplied")), Boolean.TRUE.equals(in.getValueBoolean("holdingsComplete")), Boolean.TRUE.equals(in.getValueBoolean("costsComplete")), "human:" + in.getValueString("requestId"));
         goals.context(CarlMetadata.principal(), Long.parseLong(in.getValueString("goal")), in.getValueString("currency"), context, in.getValueString("evidence"));
         out.addValue("result", "Human context recorded with provenance. This does not approve an investment product or contribution.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlInvestmentScenario", "Explore Investment Assumptions", List.of(field("goal", QFieldType.LONG).withPossibleValueSourceName("carlFinancialGoals"), field("currency", QFieldType.STRING), field("firstMonth", QFieldType.STRING).withLabel("First month (YYYY-MM)"), field("months", QFieldType.INTEGER), field("initialCapital", QFieldType.DECIMAL), field("monthlyContribution", QFieldType.DECIMAL), field("monthlyReturn", QFieldType.DECIMAL).withLabel("Uncertain assumed monthly return as decimal"), field("assetFeeRate", QFieldType.DECIMAL), field("initialFee", QFieldType.DECIMAL), field("monthlyFee", QFieldType.DECIMAL), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         UUID request = UUID.fromString(in.getValueString("requestId"));
         var assumption = new InvestmentPlanning.Assumption(request.toString(), new BigDecimal(in.getValueString("monthlyReturn")), new BigDecimal(in.getValueString("assetFeeRate")), new BigDecimal(in.getValueString("initialFee")), new BigDecimal(in.getValueString("monthlyFee")), "human:" + request);
         long id = goals.scenario(CarlService.Scope.privateFor(CarlMetadata.principal()), request, Long.parseLong(in.getValueString("goal")), in.getValueString("currency"), YearMonth.parse(in.getValueString("firstMonth")), in.getValueInteger("months"), new BigDecimal(in.getValueString("initialCapital")), new BigDecimal(in.getValueString("monthlyContribution")), assumption, in.getValueString("evidence"));
         out.addValue("result", service.artifact(CarlMetadata.principal(), id).get("facts").toString() + "\nEducational scenario " + id + ". No security selected or order placed; debt and cash reserve priorities remain explicit.");
      }));
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
