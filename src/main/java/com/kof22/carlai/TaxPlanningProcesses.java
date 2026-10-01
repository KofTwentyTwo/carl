/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.TaxPlanningRecords;


/** Native human evidence review and conditional tax preparation; no tax actions or rule qualification. */
final class TaxPlanningProcesses
{
   private TaxPlanningProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var tax = new TaxPlanningRecords(service);
      for(var table : List.of(CarlMetadata.table("carlTaxReferences", "Dated Tax Sources", "carl_tax_reference_view", "jurisdiction:S,topic:S,source_url:S,edition:S,tax_year:L,published_on:D,retrieved_at:I,content_hash:S,reviewed_on:D,review_until:D,review_evidence:T,active:B,reviewed_by:L,revision:L"), CarlMetadata.table("carlTaxAlternatives", "Conditional Ownership Alternatives", "carl_tax_alternative_view", "property_id:L,structure_label:S,current_title_evidence:T,assumptions:T,setup_cost:M,annual_cost:M,currency:S,professional_review:T,created_by:L,revision:L")))
      {
         instance.addTable(table);
         app.withChild(table);
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      }
      CarlMetadata.choices(instance, "carlTaxJurisdiction", List.of("US", "ILLINOIS", "RANDOLPH_COUNTY"));
      CarlMetadata.choices(instance, "carlTaxReferenceTopic", List.of("RENTAL_RECORDS", "OWNERSHIP_STRUCTURE", "PROPERTY_ASSESSMENT"));
      CarlMetadata.add(instance, app,
         CarlMetadata.process("carlRecordTaxReference", "Record Dated Primary Source", List.of(field("title", QFieldType.STRING, true), field("visibility", QFieldType.STRING, true).withPossibleValueSourceName("carlVisibility"), field("jurisdiction", QFieldType.STRING, true).withPossibleValueSourceName("carlTaxJurisdiction"), field("topic", QFieldType.STRING, true).withPossibleValueSourceName("carlTaxReferenceTopic"), field("url", QFieldType.STRING, true), field("edition", QFieldType.STRING, true), field("taxYear", QFieldType.INTEGER, false), field("published", QFieldType.DATE, false), field("retrieved", QFieldType.STRING, true).withLabel("Retrieved at (UTC ISO instant)"), field("contentHash", QFieldType.STRING, false).withLabel("Supplied content SHA256 (archive custody unverified)"), field("reviewed", QFieldType.DATE, false), field("reviewUntil", QFieldType.DATE, false), field("reviewEvidence", QFieldType.TEXT, false), field("provenance", QFieldType.TEXT, true)), (in, out) ->
         {
            var value = new TaxPlanningRecords.Reference(in.getValueString("title"), in.getValueString("visibility"), in.getValueString("jurisdiction"), in.getValueString("topic"), in.getValueString("url"), in.getValueString("edition"), integer(in.getValueString("taxYear")), date(in.getValueString("published")), Instant.parse(in.getValueString("retrieved")), optional(in.getValueString("contentHash")), date(in.getValueString("reviewed")), date(in.getValueString("reviewUntil")), optional(in.getValueString("reviewEvidence")), in.getValueString("provenance"));
            long id = tax.reference(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), value);
            out.addValue("result", "Source record " + id + " saved. Its URL and review metadata do not establish a qualified tax rule.");
         }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlTaxReferenceStatus", "Activate or Deactivate Tax Source", List.of(field("reference", QFieldType.LONG, true).withPossibleValueSourceName("carlTaxReferences"), field("expectedRevision", QFieldType.LONG, true), field("active", QFieldType.BOOLEAN, true), field("reason", QFieldType.TEXT, true)), (in, out) ->
      {
         tax.active(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("reference")), Long.parseLong(in.getValueString("expectedRevision")), Boolean.TRUE.equals(in.getValueBoolean("active")), in.getValueString("reason"));
         out.addValue("result", "Source status updated with attribution. Old saved context is invalidated; generate a new packet.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRecordTaxAlternative", "Record Conditional Ownership Alternative", List.of(field("title", QFieldType.STRING, true), field("visibility", QFieldType.STRING, true).withPossibleValueSourceName("carlVisibility"), field("property", QFieldType.LONG, true).withPossibleValueSourceName("carlProperties"), field("structure", QFieldType.STRING, true).withLabel("Human-proposed structure (not a recommendation)"), field("titleEvidence", QFieldType.TEXT, false), field("assumptions", QFieldType.TEXT, true), field("setupCost", QFieldType.DECIMAL, false), field("annualCost", QFieldType.DECIMAL, false), field("professionalReview", QFieldType.TEXT, false), field("references", QFieldType.STRING, true).withLabel("Selected source record IDs, comma-separated"), field("provenance", QFieldType.TEXT, true)), (in, out) ->
      {
         var value = new TaxPlanningRecords.Alternative(in.getValueString("title"), in.getValueString("visibility"), Long.parseLong(in.getValueString("property")), in.getValueString("structure"), optional(in.getValueString("titleEvidence")), in.getValueString("assumptions"), amount(in.getValueString("setupCost")), amount(in.getValueString("annualCost")), optional(in.getValueString("professionalReview")), in.getValueString("provenance"), ids(in.getValueString("references")));
         long id = tax.alternative(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), value);
         out.addValue("result", "Conditional alternative " + id + " saved with source dependencies. No tax savings, suitability, election or title change is established.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlSourceGroundedTaxPacket", "Prepare Tax and Ownership Discussion", List.of(field("taxYear", QFieldType.INTEGER, false).withLabel("Tax year, if confirmed"), field("asOf", QFieldType.STRING, true).withLabel("As of (UTC ISO instant)"), field("properties", QFieldType.STRING, true).withLabel("Selected property record IDs, comma-separated"), field("alternatives", QFieldType.STRING, false), field("references", QFieldType.STRING, false)), (in, out) ->
      {
         long id = tax.packet(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), integer(in.getValueString("taxYear")), Instant.parse(in.getValueString("asOf")), ids(in.getValueString("properties")), ids(in.getValueString("alternatives")), ids(in.getValueString("references")));
         out.addValue("result", "Saved incomplete preparation packet " + id + ". Review its facts, dated references, conditional alternatives and gaps in Reports and Drafts. Tax calculations remain undetermined.");
      }));
   }



   private static QFieldMetaData field(String name, QFieldType type, boolean required)
   {
      return new QFieldMetaData(name, type).withIsRequired(required);
   }



   private static String optional(String value)
   {
      return value == null || value.isBlank() ? null : value;
   }



   private static Integer integer(String value)
   {
      return optional(value) == null ? null : Integer.valueOf(value);
   }



   private static LocalDate date(String value)
   {
      return optional(value) == null ? null : LocalDate.parse(value);
   }



   private static BigDecimal amount(String value)
   {
      return optional(value) == null ? null : new BigDecimal(value);
   }



   private static List<Long> ids(String value)
   {
      if(optional(value) == null)
      {
         return List.of();
      }
      if(value.length() > 2000)
      {
         throw new IllegalArgumentException("Bounded ID selection required");
      }
      return Arrays.stream(value.split(",")).map(String::strip).map(Long::valueOf).toList();
   }
}
