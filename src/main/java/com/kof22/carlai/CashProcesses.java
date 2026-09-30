
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.CashPlans;


/** Human review of dated cash assumptions and purchase decisions in native administration. */
final class CashProcesses
{
   private CashProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var plans = new CashPlans(service);
      var table = CarlMetadata.table("carlCashPlans", "Cash Forecasts", "carl_cash_plan_view", "currency:S,from_date:D,through_date:D,opening_cash:M,reserve_floor:M,discretionary_cap:M,balances_resolved:B,obligations_covered:B,scope_complete:B,reserve_confirmed:B,selected_plans_included:B,income_supported:B");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCreateCashPlan", "Review Cash Forecast Assumptions", List.of(field("title", QFieldType.STRING), field("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), field("currency", QFieldType.STRING), field("from", QFieldType.DATE), field("through", QFieldType.DATE), field("openingCash", QFieldType.DECIMAL), field("reserve", QFieldType.DECIMAL), field("discretionaryCap", QFieldType.DECIMAL).withLabel("Agreed discretionary limit after debt goals"), field("balancesResolved", QFieldType.BOOLEAN), field("obligationsCovered", QFieldType.BOOLEAN), field("scopeComplete", QFieldType.BOOLEAN), field("reserveConfirmed", QFieldType.BOOLEAN), field("selectedPlansIncluded", QFieldType.BOOLEAN), field("incomeSupported", QFieldType.BOOLEAN), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         var assumptions = new CashPlans.Assumptions(in.getValueString("currency"), in.getValueLocalDate("from"), in.getValueLocalDate("through"), new BigDecimal(in.getValueString("openingCash")), new BigDecimal(in.getValueString("reserve")), new BigDecimal(in.getValueString("discretionaryCap")), Boolean.TRUE.equals(in.getValueBoolean("balancesResolved")), Boolean.TRUE.equals(in.getValueBoolean("obligationsCovered")), Boolean.TRUE.equals(in.getValueBoolean("scopeComplete")), Boolean.TRUE.equals(in.getValueBoolean("reserveConfirmed")), Boolean.TRUE.equals(in.getValueBoolean("selectedPlansIncluded")), Boolean.TRUE.equals(in.getValueBoolean("incomeSupported")));
         out.addValue("result", "Cash forecast " + plans.create(CarlMetadata.principal(), in.getValueString("title"), in.getValueString("visibility"), in.getValueString("evidence"), assumptions) + " saved with human attestations. Add all dated essential, income and debt movements; these flags are not independent verification.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCashMovement", "Add Dated Cash Commitment", List.of(field("cashPlan", QFieldType.LONG).withPossibleValueSourceName("carlCashPlans"), field("date", QFieldType.DATE), field("signedAmount", QFieldType.DECIMAL).withLabel("Signed cash movement: income positive, spending negative"), field("title", QFieldType.STRING), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         plans.event(CarlMetadata.principal(), Long.parseLong(in.getValueString("cashPlan")), UUID.fromString(in.getValueString("requestId")), in.getValueLocalDate("date"), new BigDecimal(in.getValueString("signedAmount")), in.getValueString("title"), in.getValueString("evidence"));
         out.addValue("result", "Dated movement saved. Reserve earmarks do not belong here as duplicate expenses; each actual cash movement is entered once.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlAssessPurchase", "Assess Household Purchase", List.of(field("cashPlan", QFieldType.LONG).withPossibleValueSourceName("carlCashPlans"), field("purchaseDate", QFieldType.DATE), field("allInPrice", QFieldType.DECIMAL).withLabel("All-in price including tax, delivery and fees"), field("purpose", QFieldType.STRING), field("allInCostsKnown", QFieldType.BOOLEAN)), (in, out) ->
      {
         long id = plans.assess(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("cashPlan")), in.getValueLocalDate("purchaseDate"), new BigDecimal(in.getValueString("allInPrice")), in.getValueString("purpose"), Boolean.TRUE.equals(in.getValueBoolean("allInCostsKnown")));
         out.addValue("result", service.artifact(CarlMetadata.principal(), id).get("facts").toString() + "\nSaved conditional assessment " + id + ". No purchase, application or payment occurred.");
      }));
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
