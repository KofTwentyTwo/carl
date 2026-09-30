
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/*******************************************************************************
 ** Evidence organization for the confirmed US/Illinois/Randolph County context.
 ** No tax calculator or legal/entity-selection authority is implemented here.
 ******************************************************************************/
public final class TaxPreparation
{
   /** Evidence categories required for property tax preparation. */
   public enum Category
   {
      OWNERSHIP(false), ACQUISITION_BASIS(false), LAND_BUILDING_ALLOCATION(false), PLACED_IN_SERVICE(false), PRIOR_DEPRECIATION(false), IMPROVEMENT_RECORDS(false), RENT_RECORDS(true), EXPENSE_RECORDS(true), MORTGAGE_STATEMENT(true), PROPERTY_TAX_BILL(true), ILLINOIS_ADJUSTMENTS(true);

      private final boolean yearSpecific;
      Category(boolean yearSpecific)
      {
         this.yearSpecific = yearSpecific;
      }



      /** Indicates whether evidence must match the selected tax year. */
      public boolean yearSpecific()
      {
         return yearSpecific;
      }
   }



   /** Separates suggested classifications from confirmed treatments. */
   public enum Treatment
   {
      UNKNOWN, PROPOSED, CONFIRMED
   }



   /*******************************************************************************
    ** Acquisition basis is supplied evidence, not inferred assessment/market value.
    ******************************************************************************/
   public record PropertyFacts(String id, String currency, BigDecimal ownershipFraction,
      LocalDate acquired, LocalDate placedInService, BigDecimal acquisitionBasis,
      BigDecimal landBasis, BigDecimal buildingBasis, Boolean financed, Set<String> sourceReferences)
   {
      /** Retains immutable supplied evidence collections. */
      public PropertyFacts
      {
         sourceReferences = Set.copyOf(sourceReferences);
      }
   }



   /** Authorized source document and explicit tax-treatment evidence. */
   public record Document(String id, String property, Category category, Integer taxYear,
      String sourceReference, Treatment treatment, String treatmentEvidence)
   {
   }



   /*******************************************************************************
    ** Applicability metadata describes a source edition; it does not qualify a rule.
    ** A missing content hash identifies an unarchived reference and remains visible.
    ******************************************************************************/
   public record Reference(String id, URI uri, String edition, Set<Integer> applicableTaxYears,
      Instant retrieved, String archivedContentHash)
   {
      /** Retains immutable supplied evidence collections. */
      public Reference
      {
         applicableTaxYears = Set.copyOf(applicableTaxYears);
      }
   }



   /** Missing evidence or supporting document identifiers for one property category. */
   public record CheckItem(String property, Category category, boolean missing, List<String> documentIds)
   {
      /** Retains immutable supplied evidence collections. */
      public CheckItem
      {
         documentIds = List.copyOf(documentIds);
      }
   }



   /** Dated preparation packet with unresolved facts and no implied tax calculation. */
   public record Packet(Integer taxYear, String jurisdiction, Instant asOf, List<PropertyFacts> properties,
      List<Document> documents, List<Reference> references, List<CheckItem> checklist, List<String> gaps,
      boolean sourceCoverageComplete, String taxCalculationStatus, String calculationLimitation,
      List<String> professionalQuestions)
   {
      /** Retains immutable supplied evidence collections. */
      public Packet
      {
         properties = List.copyOf(properties);
         documents = List.copyOf(documents);
         references = List.copyOf(references);
         checklist = List.copyOf(checklist);
         gaps = List.copyOf(gaps);
         professionalQuestions = List.copyOf(professionalQuestions);
      }
   }

   private static final Set<String> GOVERNMENT_HOSTS = Set.of("www.irs.gov", "irs.gov", "tax.illinois.gov", "www.ilsos.gov", "ilsos.gov", "randolphcountyil.gov");
   private static final List<String> QUESTIONS = List.of(
      "Which taxpayer/entity reports each rental, given supplied title, ownership shares and actual elections?",
      "Which expenditures require basis/depreciation treatment rather than a proposed current deduction?",
      "What participation, at-risk, personal-use and carryover facts apply to the selected tax year?",
      "Which year-specific Illinois adjustments differ from federal treatment for these supplied assets?",
      "Before a title/entity change, what do counsel, lender and insurer require, and what transfer/administrative consequences apply?",
      "What source-backed facts and professional review are needed to compare current ownership with alternatives?");

   private TaxPreparation()
   {
   }



   /*******************************************************************************
    ** Trusted services must authenticate callers and prefilter every fact/document.
    ** Source text and proposed classifications are data, never authority or tax rules.
    ******************************************************************************/
   public static Packet assemble(Integer taxYear, Instant asOf, List<PropertyFacts> properties,
      List<Document> documents, List<Reference> references, boolean sourceCoverageComplete)
   {
      if(asOf == null || properties == null || properties.size() > 100 || documents == null || documents.size() > 20_000
         || references == null || references.size() > 100)
      {
         throw new IllegalArgumentException("An explicit snapshot and bounded authorized tax inputs are required");
      }
      if(taxYear != null)
      {
         year(taxYear);
      }
      var gaps = new ArrayList<String>();
      LocalDate snapshotUtcDate = asOf.atOffset(ZoneOffset.UTC).toLocalDate();
      if(taxYear == null)
      {
         gaps.add("Select the tax year before annual documents or rules can be matched");
      }
      if(!sourceCoverageComplete)
      {
         gaps.add("Source coverage is partial; this packet is not the complete household tax position");
      }
      var propertyMap = new HashMap<String, PropertyFacts>();
      for(var property : properties)
      {
         if(property == null || !id(property.id()) || propertyMap.putIfAbsent(property.id(), property) != null || property.currency() == null
            || property.sourceReferences().isEmpty() || property.sourceReferences().size() > 100 || property.sourceReferences().stream().anyMatch(source -> !id(source)))
         {
            throw new IllegalArgumentException("Property facts require unique IDs, explicit currency and protected source identities");
         }
         int scale = Currency.getInstance(property.currency()).getDefaultFractionDigits();
         if(scale < 0 || scale > 4)
         {
            throw new IllegalArgumentException("Unsupported basis currency precision");
         }
         if(property.ownershipFraction() == null)
         {
            gaps.add(property.id() + ": ownership shares and legal/tax ownership evidence unresolved");
         }
         else if(property.ownershipFraction().precision() > 18 || Math.abs((long) property.ownershipFraction().scale()) > 12
            || property.ownershipFraction().signum() < 0 || property.ownershipFraction().compareTo(BigDecimal.ONE) > 0)
         {
            throw new IllegalArgumentException("Ownership fractions must lie from zero through one");
         }
         if(property.acquired() == null)
         {
            gaps.add(property.id() + ": acquisition date and method unresolved");
         }
         else
         {
            date(property.acquired());
            if(property.acquired().isAfter(snapshotUtcDate))
            {
               gaps.add(property.id() + ": acquisition date is after the packet UTC as-of date; planned/unverified, not an established acquisition");
            }
         }
         if(property.placedInService() == null)
         {
            gaps.add(property.id() + ": placed-in-service date unresolved");
         }
         else
         {
            date(property.placedInService());
            if(property.placedInService().isAfter(snapshotUtcDate))
            {
               gaps.add(property.id() + ": placed-in-service date is after the packet UTC as-of date; planned/unverified, not established service");
            }
         }
         if(property.acquired() != null && property.placedInService() != null && property.placedInService().isBefore(property.acquired()))
         {
            throw new IllegalArgumentException("Placed-in-service date precedes supplied acquisition date");
         }
         if(property.acquisitionBasis() == null || property.landBasis() == null || property.buildingBasis() == null)
         {
            gaps.add(property.id() + ": acquisition basis and land/building allocation unresolved; no depreciation estimate established");
         }
         if(property.acquisitionBasis() != null)
         {
            money(property.acquisitionBasis(), scale);
         }
         if(property.landBasis() != null)
         {
            money(property.landBasis(), scale);
         }
         if(property.buildingBasis() != null)
         {
            money(property.buildingBasis(), scale);
         }
         if(property.acquisitionBasis() != null && property.landBasis() != null && property.buildingBasis() != null
            && property.landBasis().add(property.buildingBasis()).compareTo(property.acquisitionBasis()) != 0)
         {
            throw new IllegalArgumentException("Supplied initial land/building basis does not reconcile to acquisition basis");
         }
         if(property.financed() == null)
         {
            gaps.add(property.id() + ": financing status unresolved; confirm applicable mortgage documents");
         }
      }
      var docIds = new HashSet<String>();
      for(var document : documents)
      {
         if(document == null || !id(document.id()) || !docIds.add(document.id()) || !propertyMap.containsKey(document.property())
            || document.category() == null || !id(document.sourceReference()) || document.treatment() == null)
         {
            throw new IllegalArgumentException("Documents require unique IDs, an authorized property, category, provenance and explicit treatment state");
         }
         if(document.taxYear() != null)
         {
            year(document.taxYear());
         }
         if(document.treatment() == Treatment.CONFIRMED && !id(document.treatmentEvidence()))
         {
            throw new IllegalArgumentException("Confirmed tax treatment requires separate supporting determination evidence");
         }
         if(document.treatmentEvidence() != null && !id(document.treatmentEvidence()))
         {
            throw new IllegalArgumentException("Treatment evidence identity is invalid");
         }
      }
      var referenceIds = new HashSet<String>();
      boolean yearMatchedSource = false;
      for(var reference : references)
      {
         if(reference == null || !id(reference.id()) || !referenceIds.add(reference.id()) || !id(reference.edition())
            || reference.uri() == null || !"https".equals(reference.uri().getScheme())
            || !GOVERNMENT_HOSTS.contains(reference.uri().getHost()) || reference.uri().getRawUserInfo() != null
            || reference.uri().getPort() != -1 || reference.uri().getRawQuery() != null || reference.uri().getRawFragment() != null
            || reference.uri().toASCIIString().length() > 2000 || reference.retrieved() == null || reference.retrieved().isAfter(asOf)
            || reference.applicableTaxYears().size() > 100 || reference.archivedContentHash() != null && !reference.archivedContentHash().matches("[0-9a-f]{64}"))
         {
            throw new IllegalArgumentException("Primary references require dated government HTTPS URLs and valid edition/hash metadata");
         }
         for(Integer applicableYear : reference.applicableTaxYears())
         {
            year(applicableYear);
         }
         if(taxYear != null && reference.applicableTaxYears().contains(taxYear))
         {
            yearMatchedSource = true;
         }
      }
      if(taxYear != null && !yearMatchedSource)
      {
         gaps.add("No year-matched government source reference established for " + taxYear);
      }
      var checklist = new ArrayList<CheckItem>();
      for(var property : properties.stream().sorted(Comparator.comparing(PropertyFacts::id)).toList())
      {
         for(Category category : Category.values())
         {
            if(category == Category.MORTGAGE_STATEMENT && Boolean.FALSE.equals(property.financed()))
            {
               continue;
            }
            List<String> matches = documents.stream().filter(document -> document.property().equals(property.id()) && document.category() == category
               && (!category.yearSpecific() || taxYear != null && taxYear.equals(document.taxYear()))).map(Document::id).sorted().toList();
            checklist.add(new CheckItem(property.id(), category, matches.isEmpty(), matches));
         }
      }
      return new Packet(taxYear, "US / Illinois / Randolph County / Chester", asOf,
         properties.stream().sorted(Comparator.comparing(PropertyFacts::id)).toList(), documents.stream().sorted(Comparator.comparing(Document::id)).toList(),
         references.stream().sorted(Comparator.comparing(Reference::id)).toList(), checklist, gaps, sourceCoverageComplete,
         "UNDETERMINED", "No implemented, qualified tax-rule package or tax calculator. Documents and references do not establish deductions, tax owed, entity suitability or professional approval. Unarchived references are research only.", QUESTIONS);
   }



   private static boolean id(String value)
   {
      return value != null && !value.isBlank() && value.length() <= 200;
   }



   private static void year(Integer value)
   {
      if(value == null || value < 1900 || value > 2200)
      {
         throw new IllegalArgumentException("Tax years must be explicit and bounded");
      }
   }



   private static void date(LocalDate value)
   {
      if(value.getYear() < 1900 || value.getYear() > 2300)
      {
         throw new IllegalArgumentException("Property dates must be bounded");
      }
   }



   private static void money(BigDecimal value, int scale)
   {
      if(value.signum() < 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18
         || value.compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("Supplied basis must be an exact bounded nonnegative amount");
      }
      if(value.stripTrailingZeros().scale() > scale)
      {
         throw new IllegalArgumentException("Basis exceeds currency precision");
      }
   }
}
