/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Evidence-only tax preparation from scoped property facts and supplied documents. */
public final class TaxRecords
{
   private final CarlService service;
   /** Uses Carl's existing property and tax records, not another tax data store. */
   public TaxRecords(CarlService service)
   {
      this.service = service;
   }



   /** Records human supplied placement/financing facts with before/after provenance. */
   public void context(String principal, long property, LocalDate placed, Boolean financed, String evidence)
   {
      CarlService.bounded(evidence, 4000, "Property context evidence");
      if(placed != null && (placed.getYear() < 1900 || placed.getYear() > 2200))
      {
         throw new IllegalArgumentException("Bounded date required");
      }
      service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "TAX");
         var rows = CarlService.rows(c, "SELECT * FROM carl_tax_property_view WHERE principal=? AND id=?", principal, property);
         if(rows.size() != 1)
         {
            throw new SecurityException("Tax property unavailable");
         }
         CarlService.execute(c, "UPDATE carl_property SET placed_in_service=?,financed=?,tax_context_evidence=? WHERE record_id=?", placed, financed, evidence, property);
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", property);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", property, actor.id(), evidence, CarlService.json(rows), CarlService.json(CarlService.rows(c, "SELECT * FROM carl_tax_property_view WHERE principal=? AND id=?", principal, property)));
         CarlService.bump(c, actor.householdId());
         return null;
      });
   }



   /** Indexes supplied evidence; no filename or source text is used as fetch authority. */
   public long document(String principal, long property, String title, String visibility, int taxYear, TaxPreparation.Category category, TaxPreparation.Treatment treatment, String treatmentEvidence, String sourceEvidence)
   {
      if(taxYear < 1900 || taxYear > 2200 || category == null || treatment == null)
      {
         throw new IllegalArgumentException("Explicit tax year and evidence classifications required");
      }
      CarlService.bounded(sourceEvidence, 4000, "Document source evidence");
      if(treatmentEvidence != null && treatmentEvidence.length() > 4000)
      {
         throw new IllegalArgumentException("Classification determination evidence exceeds 4000 characters");
      }
      if(treatment != TaxPreparation.Treatment.UNKNOWN)
      {
         CarlService.bounded(treatmentEvidence, 4000, "Classification determination evidence");
      }
      return service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "TAX");
         if(CarlService.rows(c, "SELECT id FROM carl_tax_property_view WHERE principal=? AND id=?", principal, property).size() != 1)
         {
            throw new SecurityException("Tax property unavailable");
         }
         long id = CarlService.record(c, actor, "TAX", visibility, title, sourceEvidence);
         CarlService.execute(c, "INSERT INTO carl_tax_document(record_id,tax_year,jurisdiction,document_kind,property_id,classification_state,treatment_evidence) VALUES(?,?,'US / Illinois / Randolph County / Chester',?,?,?,?)", id, taxYear, category.name(), property, treatment.name(), treatmentEvidence);
         CarlService.bump(c, actor.householdId());
         return id;
      });
   }



   /** Saves a dated source-linked preparation packet; no qualified tax calculation is claimed. */
   public long packet(CarlService.Scope scope, UUID request, int taxYear, Instant asOf, List<Long> propertyIds)
   {
      if(propertyIds == null || propertyIds.isEmpty() || propertyIds.size() > 100 || propertyIds.stream().distinct().count() != propertyIds.size() || asOf == null || taxYear < 1900 || taxYear > 2200)
      {
         throw new IllegalArgumentException("Select a year, dated snapshot and bounded unique properties");
      }
      long epoch = service.member(scope.principal()).permissionRevision();
      String digest = BillCsv.hash(CarlService.json(Map.of("taxYear", taxYear, "asOf", asOf, "properties", propertyIds)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var properties = service.view(scope, "taxProperties");
      var facts = new ArrayList<TaxPreparation.PropertyFacts>();
      var sources = new LinkedHashMap<Long, Long>();
      for(long property : propertyIds)
      {
         var row = properties.stream().filter(r -> CarlService.number(r, "id") == property).findFirst().orElseThrow(() -> new SecurityException("Tax property unavailable to packet audience"));
         facts.add(new TaxPreparation.PropertyFacts("property:" + property, row.get("currency").toString(), (BigDecimal) row.get("ownership_share"), date(row.get("acquisition_date")), date(row.get("placed_in_service")), (BigDecimal) row.get("acquisition_basis"), (BigDecimal) row.get("land_basis"), (BigDecimal) row.get("building_basis"), (Boolean) row.get("financed"), Set.of("record:" + property)));
         sources.put(property, CarlService.number(row, "revision"));
      }
      var documents = new ArrayList<TaxPreparation.Document>();
      for(var row : service.view(scope, "tax"))
      {
         if(row.get("property_id") == null || !propertyIds.contains(CarlService.number(row, "property_id")))
         {
            continue;
         }
         long id = CarlService.number(row, "id");
         documents.add(new TaxPreparation.Document("document:" + id, "property:" + row.get("property_id"), TaxPreparation.Category.valueOf(row.get("document_kind").toString()), ((Number) row.get("tax_year")).intValue(), "record:" + id, TaxPreparation.Treatment.valueOf(row.get("classification_state").toString()), (String) row.get("treatment_evidence")));
         sources.put(id, CarlService.number(row, "revision"));
      }
      var packet = TaxPreparation.assemble(taxYear, asOf, facts, documents, List.of(), false);
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", LocalDate.of(taxYear, 1, 1), LocalDate.of(taxYear, 12, 31), CarlService.json(packet), "", "NOT_REQUESTED", "Preparation checklist only. Scoped source coverage and year-specific rules are incomplete; no liability, deduction, election, filing or entity recommendation is calculated.", sources, null, "Incomplete tax preparation — qualified rules and source review required", digest, epoch);
   }



   private static LocalDate date(Object value)
   {
      return value == null ? null : LocalDate.parse(value.toString());
   }
}
