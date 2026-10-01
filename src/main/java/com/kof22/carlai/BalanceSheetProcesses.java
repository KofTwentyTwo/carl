/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.RentalStress;


/** Native explicit balance-source selection and immutable rental stress assumptions. */
final class BalanceSheetProcesses
{
   private BalanceSheetProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      BalanceSelectionProcesses.register(instance, app, service);
      var stress = new RentalStress(service);
      var table = CarlMetadata.table("carlRentalStress", "Rental Stress Assumptions", "carl_rental_shock_view", "baseline_artifact_id:L,property_id:L,expected_rent:M,vacancy_fraction:R,repair_amount:M,additional_annual_rate:R,allocated_principal:M,balance_as_of:D,assumptions:T,created_by:L,currency:S,revision:L");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      CarlMetadata.add(instance, app,
         CarlMetadata.process("carlRecordRentalStress", "Record Hypothetical Rental Stress",
            List.of(field("title", QFieldType.STRING), field("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), field("baselineReport", QFieldType.LONG).withPossibleValueSourceName("carlRentalBaselines").withLabel("Current source-linked rental report"), field("property", QFieldType.LONG).withPossibleValueSourceName("carlProperties"), field("expectedRent", QFieldType.DECIMAL).withLabel("Expected whole-property rent for selected baseline interval"), field("vacancyFraction", QFieldType.DECIMAL).withLabel("Vacancy fraction (0–1)"), field("repair", QFieldType.DECIMAL), field("additionalAnnualRate", QFieldType.DECIMAL).withLabel("Additional APR as fraction (0–5), simple interest only"), field("allocatedPrincipal", QFieldType.DECIMAL).withLabel("Whole-property allocated constant mortgage principal"), field("balanceAsOf", QFieldType.DATE), field("evidence", QFieldType.TEXT).withLabel("Human assumptions and evidence; explicit zero disables a shock")), (in, out) ->
            {
               var value = new RentalStress.Assumptions(Long.parseLong(in.getValueString("baselineReport")), Long.parseLong(in.getValueString("property")), amount(in.getValueString("expectedRent")), amount(in.getValueString("vacancyFraction")), amount(in.getValueString("repair")), amount(in.getValueString("additionalAnnualRate")), amount(in.getValueString("allocatedPrincipal")), LocalDate.parse(in.getValueString("balanceAsOf")), in.getValueString("evidence"));
               long id = stress.create(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), in.getValueString("title"), in.getValueString("visibility"), value);
               out.addValue("result", "Saved immutable rental stress assumptions " + id + ". These do not change loans, repairs, occupancy or financial records.");
            }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRentalStressReport", "Calculate Conditional Rental Stress", List.of(field("scenario", QFieldType.LONG).withPossibleValueSourceName("carlRentalStress")), (in, out) ->
      {
         long id = stress.report(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("scenario")));
         out.addValue("result", "Saved conditional rental stress report " + id + ". Review component arithmetic and source limitations in Reports and Drafts. This is not a loan payment quote or tax calculation.");
      }));
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }



   private static BigDecimal amount(String value)
   {
      return new BigDecimal(value);
   }
}
