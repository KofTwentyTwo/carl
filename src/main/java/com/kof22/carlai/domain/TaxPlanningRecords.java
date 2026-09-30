/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Supplied primary-source metadata and conditional rental-structure discussions, never tax advice qualification. */
public final class TaxPlanningRecords
{
   /** A human-reviewed source identity; dates/hash describe evidence, not a qualified tax rule. */
   public record Reference(String title, String visibility, String jurisdiction, String topic, String url, String edition, Integer taxYear, LocalDate published, Instant retrieved, String contentHash, LocalDate reviewed, LocalDate reviewUntil, String reviewEvidence, String provenance)
   {
   }



   /** Human-proposed alternative; missing ownership/costs remain unknown, never inferred. */
   public record Alternative(String title, String visibility, long property, String structure, String titleEvidence, String assumptions, BigDecimal setupCost, BigDecimal annualCost, String professionalReview, String provenance, List<Long> references)
   {
      /** Retains immutable explicit source selection. */
      public Alternative
      {
         references = List.copyOf(references);
      }
   }
   private final CarlService service;
   private final Clock clock;
   /** Uses current UTC for dated evidence checks. */
   public TaxPlanningRecords(CarlService service)
   {
      this(service, Clock.systemUTC());
   }



   /** Supports controlled clocks without replacing the application's domain authority. */
   public TaxPlanningRecords(CarlService service, Clock clock)
   {
      this.service = service;
      this.clock = clock;
   }



   /** Records a bounded official URL as inert evidence; never fetches a supplied network target. */
   public long reference(String principal, UUID request, Reference value)
   {
      validate(value);
      String digest = BillCsv.hash(CarlService.json(value));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         Long prior = CarlService.request(c, actor, request, "TAX_REFERENCE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_tax_reference_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "TAX", value.visibility(), value.title(), value.provenance());
         CarlService.execute(c, "INSERT INTO carl_tax_reference(record_id,jurisdiction,topic,source_url,edition,tax_year,published_on,retrieved_at,content_hash,reviewed_on,review_until,review_evidence,reviewed_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", id, value.jurisdiction(), value.topic(), value.url(), value.edition(), value.taxYear(), value.published(), java.sql.Timestamp.from(value.retrieved()), value.contentHash(), value.reviewed(), value.reviewUntil(), value.reviewEvidence(), actor.id());
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Source metadata recorded; no rule qualification");
         return id;
      });
   }



   /** Explicit deactivation is attributed and revokes saved packets which could contain old reference context. */
   public void active(String principal, UUID request, long id, long expectedRevision, boolean active, String reason)
   {
      CarlService.bounded(reason, 4000, "Reference status evidence");
      String digest = BillCsv.hash(CarlService.json(Map.of("reference", id, "revision", expectedRevision, "active", active, "reason", reason)));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var before = require(c, principal, "carl_tax_reference_view", id);
         if(CarlService.request(c, actor, request, "TAX_REFERENCE_STATUS", digest) != null)
         {
            return null;
         }
         if(CarlService.number(before, "revision") != expectedRevision)
         {
            throw new IllegalArgumentException("Reference changed; review current revision");
         }
         CarlService.execute(c, "UPDATE carl_tax_reference SET active=?,reviewed_by=? WHERE record_id=?", active, actor.id(), id);
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", id, actor.id(), reason, CarlService.json(before), CarlService.json(Map.of("active", active)));
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Reference status changed; no tax treatment asserted");
         return null;
      });
   }



   /** Stores an immutable human-proposed comparison input with explicit property/source dependencies. */
   public long alternative(String principal, UUID request, Alternative value)
   {
      if(value == null || value.references().isEmpty() || value.references().size() > 20 || value.references().stream().distinct().count() != value.references().size())
      {
         throw new IllegalArgumentException("Select 1–20 distinct source references");
      }
      CarlService.bounded(value.structure(), 200, "Proposed structure");
      CarlService.bounded(value.assumptions(), 4000, "Supplied assumptions");
      CarlService.bounded(value.provenance(), 4000, "Alternative provenance");
      optional(value.titleEvidence(), 4000);
      optional(value.professionalReview(), 4000);
      String digest = BillCsv.hash(CarlService.json(value));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var property = require(c, principal, "carl_tax_property_view", value.property());
         String currency = property.get("currency").toString();
         money(value.setupCost(), currency);
         money(value.annualCost(), currency);
         for(long id : value.references())
         {
            require(c, principal, "carl_tax_reference_view", id);
         }
         Long prior = CarlService.request(c, actor, request, "TAX_ALTERNATIVE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_tax_alternative_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "TAX", value.visibility(), value.title(), value.provenance());
         CarlService.execute(c, "INSERT INTO carl_tax_alternative(record_id,property_id,structure_label,current_title_evidence,assumptions,setup_cost,annual_cost,currency,professional_review,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)", id, value.property(), value.structure(), value.titleEvidence(), value.assumptions(), value.setupCost(), value.annualCost(), currency, value.professionalReview(), actor.id());
         for(long reference : value.references())
         {
            CarlService.execute(c, "INSERT INTO carl_tax_alternative_reference(alternative_id,reference_id,reference_revision) VALUES(?,?,?)", id, reference, CarlService.number(require(c, principal, "carl_tax_reference_view", reference), "revision"));
         }
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Hypothetical structure input; no election or title change");
         return id;
      });
   }



   /** Builds a permission-intersected preparation and conditional alternatives packet with dated references. */
   public long packet(CarlService.Scope scope, UUID request, Integer taxYear, Instant asOf, List<Long> propertyIds, List<Long> alternativeIds, List<Long> referenceIds)
   {
      var restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         return generate(scope, request, taxYear, asOf, propertyIds, alternativeIds, referenceIds);
      }
      finally
      {
         restore.run();
      }
   }



   private long generate(CarlService.Scope scope, UUID request, Integer taxYear, Instant asOf, List<Long> propertyIds, List<Long> alternativeIds, List<Long> referenceIds)
   {
      if(asOf == null || asOf.isAfter(clock.instant()) || asOf.atOffset(ZoneOffset.UTC).getYear() < 1900 || asOf.atOffset(ZoneOffset.UTC).getYear() > 2200)
      {
         throw new IllegalArgumentException("Explicit nonfuture bounded as-of time required");
      }
      if(taxYear != null && (taxYear < 1900 || taxYear > 2200))
      {
         throw new IllegalArgumentException("Explicit supported tax year required");
      }
      ids(propertyIds, 20, true);
      ids(alternativeIds, 40, false);
      ids(referenceIds, 100, false);
      long epoch = service.member(scope.principal()).permissionRevision();
      var input = new LinkedHashMap<String, Object>();
      input.put("year", taxYear);
      input.put("asOf", asOf);
      input.put("properties", propertyIds);
      input.put("alternatives", alternativeIds);
      input.put("references", referenceIds);
      String digest = BillCsv.hash(CarlService.json(input));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var snapshot = service.transaction(c ->
      {
         var actor = CarlService.member(c, scope.principal());
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", actor.householdId());
         var sources = new LinkedHashMap<Long, Long>();
         var properties = new ArrayList<TaxPreparation.PropertyFacts>();
         var documents = new ArrayList<TaxPreparation.Document>();
         var references = new ArrayList<TaxPreparation.Reference>();
         var alternatives = new ArrayList<Map<String, Object>>();
         var gaps = new ArrayList<String>();
         var selectedReferences = new java.util.TreeSet<Long>(referenceIds);
         for(long id : propertyIds)
         {
            var row = shared(c, scope, "carl_tax_property_view", id, sources);
            properties.add(new TaxPreparation.PropertyFacts("property:" + id, row.get("currency").toString(), (BigDecimal) row.get("ownership_share"), date(row.get("acquisition_date")), date(row.get("placed_in_service")), (BigDecimal) row.get("acquisition_basis"), (BigDecimal) row.get("land_basis"), (BigDecimal) row.get("building_basis"), (Boolean) row.get("financed"), Set.of("record:" + id)));
            String query = "SELECT d.id FROM carl_tax_view d WHERE d.principal=? AND d.property_id=?";
            var parameters = new ArrayList<Object>();
            parameters.add(scope.principal());
            parameters.add(id);
            for(String principal : scope.audience())
            {
               query += " AND EXISTS(SELECT 1 FROM carl_tax_view permitted WHERE permitted.id=d.id AND permitted.principal=?)";
               parameters.add(principal);
            }
            var visibleDocuments = CarlService.rows(c, query + " ORDER BY d.id LIMIT 1001", parameters.toArray());
            if(visibleDocuments.size() > 1000)
            {
               throw new IllegalArgumentException("Narrow selected properties; packet document capacity exceeded");
            }
            for(var document : visibleDocuments)
            {
               if(documents.size() >= 1000)
               {
                  throw new IllegalArgumentException("Select fewer tax documents; packet limit is 1000");
               }
               long documentId = CarlService.number(document, "id");
               var d = shared(c, scope, "carl_tax_view", documentId, sources);
               documents.add(new TaxPreparation.Document("document:" + documentId, "property:" + id, TaxPreparation.Category.valueOf(d.get("document_kind").toString()), ((Number) d.get("tax_year")).intValue(), "record:" + documentId, TaxPreparation.Treatment.valueOf(d.get("classification_state").toString()), d.get("treatment_evidence") == null ? null : "record:" + documentId + ":treatment-evidence"));
            }
         }
         for(long id : alternativeIds)
         {
            var row = shared(c, scope, "carl_tax_alternative_view", id, sources);
            if(!propertyIds.contains(CarlService.number(row, "property_id")))
            {
               throw new IllegalArgumentException("Alternative property must be selected");
            }
            var refs = CarlService.rows(c, "SELECT reference_id,reference_revision FROM carl_tax_alternative_reference WHERE alternative_id=? ORDER BY reference_id", id);
            var sourceIds = new ArrayList<Long>();
            for(var ref : refs)
            {
               long refId = CarlService.number(ref, "reference_id");
               var current = shared(c, scope, "carl_tax_reference_view", refId, sources);
               selectedReferences.add(refId);
               sourceIds.add(refId);
               if(CarlService.number(current, "revision") != CarlService.number(ref, "reference_revision"))
               {
                  gaps.add("Alternative " + id + " reference " + refId + " changed; human re-review required");
               }
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("property", row.get("property_id"));
            result.put("proposedStructure", row.get("structure_label"));
            result.put("suppliedTitleEvidence", row.get("current_title_evidence"));
            result.put("suppliedAssumptions", row.get("assumptions"));
            result.put("setupCost", row.get("setup_cost"));
            result.put("annualAdministrativeCost", row.get("annual_cost"));
            result.put("currency", row.get("currency"));
            result.put("professionalReviewEvidence", row.get("professional_review"));
            result.put("referenceIds", sourceIds);
            result.put("status", "CONDITIONAL_DISCUSSION_ONLY");
            result.put("questions", List.of("If this proposed structure is pursued, which supplied title, ownership and election facts must a professional verify?", "Given the selected source editions, what federal and Illinois treatment applies to the supplied tax year and taxpayer?", "Before any change, what lender, insurer, county recording and legal review is required?", "Do the supplied administrative costs omit filing, accounting, legal, transfer or financing costs? No tax savings or suitability has been established."));
            alternatives.add(result);
            if(row.get("current_title_evidence") == null)
            {
               gaps.add("Alternative " + id + ": current legal/tax ownership not supplied");
            }
            if(row.get("setup_cost") == null || row.get("annual_cost") == null)
            {
               gaps.add("Alternative " + id + ": cost evidence incomplete; no cost comparison or savings conclusion");
            }
         }
         if(selectedReferences.size() > 100)
         {
            throw new IllegalArgumentException("At most 100 source references per packet");
         }
         var sourceReviews = new ArrayList<Map<String, Object>>();
         LocalDate asOfDate = asOf.atOffset(ZoneOffset.UTC).toLocalDate();
         for(long id : selectedReferences)
         {
            var row = shared(c, scope, "carl_tax_reference_view", id, sources);
            var reasons = new ArrayList<String>();
            Instant retrieved = Instant.parse(row.get("retrieved_at").toString());
            if(!Boolean.TRUE.equals(row.get("active")))
            {
               reasons.add("Reference deactivated");
            }
            if(retrieved.isAfter(asOf))
            {
               reasons.add("Reference retrieved after packet as-of");
            }
            if(row.get("published_on") != null && date(row.get("published_on")).isAfter(asOfDate))
            {
               reasons.add("Reference published after packet as-of");
            }
            if(taxYear == null || row.get("tax_year") == null || ((Number) row.get("tax_year")).intValue() != taxYear)
            {
               reasons.add("Tax-year applicability unestablished or mismatched");
            }
            if(row.get("content_hash") == null)
            {
               reasons.add("No supplied content identity; archival availability unverified");
            }
            if(row.get("reviewed_on") == null || date(row.get("reviewed_on")).isAfter(asOfDate) || date(row.get("review_until")).isBefore(asOfDate))
            {
               reasons.add("Dated human applicability review absent, future or expired");
            }
            var review = new LinkedHashMap<String, Object>();
            review.put("id", id);
            review.put("url", row.get("source_url"));
            review.put("edition", row.get("edition"));
            review.put("taxYear", row.get("tax_year"));
            review.put("publishedOn", row.get("published_on"));
            review.put("retrievedAt", row.get("retrieved_at"));
            review.put("reviewedOn", row.get("reviewed_on"));
            review.put("reviewUntil", row.get("review_until"));
            review.put("active", row.get("active"));
            review.put("suppliedContentHash", row.get("content_hash"));
            review.put("archiveAvailability", "UNVERIFIED — supplied content identity does not prove Carl holds a retrievable archive");
            review.put("jurisdiction", row.get("jurisdiction"));
            review.put("topic", row.get("topic"));
            review.put("reviewEvidence", row.get("review_evidence"));
            review.put("limitations", reasons);
            review.put("status", reasons.isEmpty() ? "HUMAN_REVIEWED_REFERENCE_NOT_RULE_QUALIFICATION" : "UNQUALIFIED_REFERENCE");
            sourceReviews.add(review);
            for(String reason : reasons)
            {
               gaps.add("Reference " + id + ": " + reason);
            }
            if(reasons.isEmpty())
            {
               references.add(new TaxPreparation.Reference("reference:" + id, URI.create(row.get("source_url").toString()), row.get("edition").toString(), Set.of(taxYear), retrieved, row.get("content_hash").toString()));
            }
         }
         var preparation = TaxPreparation.assemble(taxYear, asOf, properties, documents, references, false);
         var facts = new LinkedHashMap<String, Object>();
         facts.put("kind", "TAX_STRUCTURE_PREPARATION");
         facts.put("schemaVersion", 1);
         facts.put("domainFactsCollectedAt", clock.instant());
         facts.put("referenceApplicabilityAsOf", asOf);
         facts.put("temporalScope", "CURRENT_SUPPLIED_RECORDS — reference applicability is evaluated at the selected as-of time; property, document and alternative facts are current supplied records, not reconstructed historical state");
         facts.put("preparation", preparation);
         facts.put("sourceReviews", sourceReviews);
         facts.put("conditionalAlternatives", alternatives);
         facts.put("gaps", gaps.stream().distinct().toList());
         facts.put("taxCalculationStatus", "UNDETERMINED");
         facts.put("recommendedStructure", null);
         facts.put("ruleQualification", "NONE");
         return new Snapshot(CarlService.json(facts), sources);
      });
      if(snapshot.facts().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_000_000)
      {
         throw new IllegalArgumentException("Tax packet exceeds bounded output; narrow selected records");
      }
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", taxYear == null ? null : LocalDate.of(taxYear, 1, 1), taxYear == null ? null : LocalDate.of(taxYear, 12, 31), snapshot.facts(), "", "NOT_REQUESTED", "Conditional preparation from current supplied records, not reconstructed historical state; selected as-of evaluates reference applicability. Archive custody is unverified. Human-supplied evidence and official URLs do not qualify tax rules, deductions, liability, elections or an optimal ownership structure. No filing, title change or external action occurs.", snapshot.sources(), null, "Incomplete tax/structure preparation — professional and year-specific rule qualification required", digest, epoch);
   }
   private record Snapshot(String facts, Map<Long, Long> sources)
   {
   }
   private static Map<String, Object> shared(Connection c, CarlService.Scope scope, String view, long id, Map<Long, Long> sources) throws SQLException
   {
      Map<String, Object> value = require(c, scope.principal(), view, id);
      for(String member : scope.audience())
      {
         require(c, member, view, id);
      }
      sources.put(id, CarlService.number(value, "revision"));
      return value;
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      CarlService.member(c, principal);
      var values = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=? AND id=?", principal, id);
      if(values.size() != 1)
      {
         throw new SecurityException("Tax source unavailable");
      }
      return values.getFirst();
   }



   private static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var before = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", before.householdId());
      var actor = CarlService.manager(c, principal, "TAX");
      if(actor.householdId() != before.householdId())
      {
         throw new SecurityException("Household changed");
      }
      return actor;
   }



   private void validate(Reference value)
   {
      if(value == null)
      {
         throw new IllegalArgumentException("Explicit source metadata required");
      }
      CarlService.bounded(value.title(), 200, "Source title");
      CarlService.bounded(value.edition(), 200, "Source edition");
      CarlService.bounded(value.provenance(), 4000, "Source provenance");
      CarlService.bounded(value.url(), 2000, "Source URL");
      if(value.jurisdiction() == null || value.topic() == null)
      {
         throw new IllegalArgumentException("Explicit source jurisdiction and topic required");
      }
      for(LocalDate date : new LocalDate[]{value.published(), value.reviewed(), value.reviewUntil()})
      {
         if(date != null && (date.getYear() < 1900 || date.getYear() > 2200))
         {
            throw new IllegalArgumentException("Source dates must be between 1900 and 2200");
         }
      }
      Map<String, Set<String>> hosts = Map.of("US", Set.of("irs.gov", "www.irs.gov"), "ILLINOIS", Set.of("tax.illinois.gov", "ilsos.gov", "www.ilsos.gov"), "RANDOLPH_COUNTY", Set.of("randolphcountyil.gov"));
      URI uri = URI.create(value.url());
      if(!"https".equals(uri.getScheme()) || !hosts.getOrDefault(value.jurisdiction(), Set.of()).contains(uri.getHost()) || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.toASCIIString().length() > 2000)
      {
         throw new IllegalArgumentException("Select a bounded official primary-source HTTPS identity matching jurisdiction");
      }
      if(!Set.of("RENTAL_RECORDS", "OWNERSHIP_STRUCTURE", "PROPERTY_ASSESSMENT").contains(value.topic()) || value.retrieved() == null || value.retrieved().isAfter(clock.instant()))
      {
         throw new IllegalArgumentException("Explicit supported topic and nonfuture retrieval required");
      }
      if(value.taxYear() != null && (value.taxYear() < 1900 || value.taxYear() > 2200))
      {
         throw new IllegalArgumentException("Invalid tax year");
      }
      LocalDate retrieved = value.retrieved().atOffset(ZoneOffset.UTC).toLocalDate();
      if(retrieved.getYear() < 1900 || retrieved.getYear() > 2200)
      {
         throw new IllegalArgumentException("Source retrieval year must be between 1900 and 2200");
      }
      if(value.published() != null && value.published().isAfter(retrieved))
      {
         throw new IllegalArgumentException("Publication cannot follow retrieval");
      }
      if(value.contentHash() != null && !value.contentHash().matches("[0-9a-f]{64}"))
      {
         throw new IllegalArgumentException("Archive content identity must be SHA256");
      }
      if(value.reviewed() != null)
      {
         if(value.reviewUntil() == null || value.reviewed().isBefore(retrieved) || value.reviewed().isAfter(LocalDate.now(clock)) || value.reviewUntil().isBefore(value.reviewed()) || value.reviewUntil().isAfter(value.reviewed().plusYears(1)))
         {
            throw new IllegalArgumentException("Explicit dated review with at most one-year review horizon required");
         }
         CarlService.bounded(value.reviewEvidence(), 4000, "Review evidence");
      }
      else if(value.reviewUntil() != null || value.reviewEvidence() != null)
      {
         throw new IllegalArgumentException("Review date required for review assertions");
      }
   }



   private static void ids(List<Long> ids, int maximum, boolean required)
   {
      if(ids == null || (required && ids.isEmpty()) || ids.size() > maximum || ids.stream().anyMatch(x -> x == null || x < 1) || ids.stream().distinct().count() != ids.size())
      {
         throw new IllegalArgumentException("Bounded distinct source identifiers required");
      }
   }



   private static LocalDate date(Object value)
   {
      return value == null ? null : LocalDate.parse(value.toString());
   }



   private static void optional(String value, int max)
   {
      if(value != null && (value.isBlank() || value.length() > max))
      {
         throw new IllegalArgumentException("Optional supplied evidence must be nonblank and bounded");
      }
   }



   private static void money(BigDecimal value, String currency)
   {
      if(value != null && (value.precision() > 18 || Math.abs((long) value.scale()) > 18 || value.signum() < 0 || value.compareTo(new BigDecimal("100000000000000")) > 0 || value.stripTrailingZeros().scale() > java.util.Currency.getInstance(currency).getDefaultFractionDigits()))
      {
         throw new IllegalArgumentException("Exact bounded nonnegative supplied cost required");
      }
   }
}
