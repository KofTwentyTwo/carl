/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.TaxPreparation;
import com.kof22.carlai.domain.TaxRecords;


/** Source organization and preparation packets, with no filings or unqualified tax calculations. */
final class TaxProcesses
{
   private TaxProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var tax = new TaxRecords(service);
      var table = CarlMetadata.table("carlTaxProperties", "Tax Preparation Properties", "carl_tax_property_view", "currency:S,ownership_share:R,acquisition_date:D,acquisition_basis:M,land_basis:M,building_basis:M,placed_in_service:D,financed:B,basis_evidence:T,tax_context_evidence:T");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      CarlMetadata.choices(instance, "carlTaxCategory", Arrays.stream(TaxPreparation.Category.values()).map(Enum::name).toList());
      CarlMetadata.choices(instance, "carlTaxTreatment", Arrays.stream(TaxPreparation.Treatment.values()).map(Enum::name).toList());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlTaxPropertyContext", "Record Tax Preparation Context", List.of(property(), field("placed", QFieldType.DATE).withLabel("Placed in service - leave unknown if not evidenced").withIsRequired(false), field("financed", QFieldType.BOOLEAN).withIsRequired(false), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         tax.context(CarlMetadata.principal(), Long.parseLong(in.getValueString("property")), in.getValueLocalDate("placed"), in.getValueBoolean("financed"), in.getValueString("evidence"));
         out.addValue("result", "Property context saved with attribution. Missing dates and financing status remain unknown; no tax treatment was inferred.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlTaxDocument", "Record Supplied Tax Evidence", List.of(property(), field("title", QFieldType.STRING), field("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), field("taxYear", QFieldType.INTEGER), field("category", QFieldType.STRING).withPossibleValueSourceName("carlTaxCategory"), field("treatment", QFieldType.STRING).withPossibleValueSourceName("carlTaxTreatment"), field("treatmentEvidence", QFieldType.TEXT).withIsRequired(false), field("sourceEvidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = tax.document(CarlMetadata.principal(), Long.parseLong(in.getValueString("property")), in.getValueString("title"), in.getValueString("visibility"), Integer.parseInt(in.getValueString("taxYear")), TaxPreparation.Category.valueOf(in.getValueString("category")), TaxPreparation.Treatment.valueOf(in.getValueString("treatment")), in.getValueString("treatmentEvidence"), in.getValueString("sourceEvidence"));
         out.addValue("result", "Evidence record " + id + " saved. This indexes supplied facts; it does not fetch arbitrary documents or confirm a tax deduction.");
      }));
      var packetProcess = CarlMetadata.process("carlTaxPacket", "Prepare Property Tax Checklist", List.of(property(), field("taxYear", QFieldType.INTEGER), field("asOf", QFieldType.DATE)), (in, out) ->
      {
         long id = tax.packet(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), Integer.parseInt(in.getValueString("taxYear")), in.getValueLocalDate("asOf").atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), List.of(Long.parseLong(in.getValueString("property"))));
         var facts = facts(service.artifact(CarlMetadata.principal(), id).get("facts").toString());
         out.addValue("result", "Tax preparation packet " + id + " saved in Reports, Plans and Drafts. No tax liability, filing or entity choice has been calculated.");
         out.addValue("period", facts.path("taxYear").asText() + " / " + facts.path("asOf").asText());
         out.addValue("jurisdiction", facts.path("jurisdiction").asText());
         for(var item : facts.path("checklist"))
         {
            out.addValue("check_" + item.path("category").asText(), item.path("missing").asBoolean() ? "Missing — supply evidenced records" : "Supplied references: " + item.path("documentIds").toString());
         }
         var gaps = new java.util.ArrayList<String>();
         for(var gap : facts.path("gaps"))
         {
            gaps.add(gap.asText().replaceAll("property:[0-9]+:", "This property:"));
         }
         out.addValue("limitations", String.join("; ", gaps));
         for(int i = 0; i < facts.path("professionalQuestions").size(); i++)
         {
            out.addValue("question" + (i + 1), facts.path("professionalQuestions").get(i).asText());
         }
      });
      var result = packetProcess.getFrontendStep("result");
      result.withViewField(field("period", QFieldType.STRING).withLabel("Tax year / snapshot"));
      result.withViewField(field("jurisdiction", QFieldType.STRING).withLabel("Jurisdiction"));
      for(var category : TaxPreparation.Category.values())
      {
         result.withViewField(field("check_" + category.name(), QFieldType.STRING).withLabel(category.name().replace('_', ' ')));
      }
      result.withViewField(field("limitations", QFieldType.TEXT).withLabel("Unresolved facts and coverage"));
      for(int i = 1; i <= 6; i++)
      {
         result.withViewField(field("question" + i, QFieldType.TEXT).withLabel("Professional review question " + i));
      }
      CarlMetadata.add(instance, app, packetProcess);
   }



   private static com.fasterxml.jackson.databind.JsonNode facts(String text)
   {
      try
      {
         return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
      }
      catch(com.fasterxml.jackson.core.JsonProcessingException e)
      {
         throw new IllegalStateException("Stored tax packet is invalid", e);
      }
   }



   private static QFieldMetaData property()
   {
      return field("property", QFieldType.LONG).withPossibleValueSourceName("carlTaxProperties");
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
