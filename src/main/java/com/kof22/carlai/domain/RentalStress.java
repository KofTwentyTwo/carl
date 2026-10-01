/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


/** Human-reviewed stress replay of an evidenced rental interval, never predicted payments. */
public final class RentalStress
{
   /** Whole-property hypothetical amounts; explicit zero means no selected shock. */
   public record Assumptions(long baselineReport, long property, BigDecimal expectedRent, BigDecimal vacancyFraction,
      BigDecimal repair, BigDecimal additionalAnnualRate, BigDecimal allocatedPrincipal, LocalDate balanceAsOf, String evidence)
   {
   }
   private final CarlService service;
   private final BalanceSheets sheets;
   private final java.time.Clock clock;
   /** Shares authoritative Carl records and authorization. */
   public RentalStress(CarlService service)
   {
      this(service, java.time.Clock.systemUTC());
   }



   /** Explicit clock permits fixed synthetic observation boundaries. */
   public RentalStress(CarlService service, java.time.Clock clock)
   {
      this.service = service;
      this.clock = java.util.Objects.requireNonNull(clock);
      this.sheets = new BalanceSheets(service, clock);
   }



   /** Saves immutable attributed assumptions through an explicit human process. */
   public long create(String principal, UUID request, String title, String visibility, Assumptions value)
   {
      if(value == null || value.baselineReport() <= 0 || value.property() <= 0)
      {
         throw new IllegalArgumentException("Rental report and property required");
      }
      BalanceSheets.date(value.balanceAsOf());
      if(value.balanceAsOf().isAfter(LocalDate.now(clock)))
      {
         throw new IllegalArgumentException("Future dated principal is not an observed balance");
      }
      CarlService.bounded(value.evidence(), 4000, "Human scenario assumptions");
      fraction(value.vacancyFraction(), BigDecimal.ONE);
      fraction(value.additionalAnnualRate(), new BigDecimal("5"));
      String digest = BillCsv.hash(CarlService.json(Map.of("operation", "RENTAL_SHOCK_CREATE", "title", title, "visibility", visibility, "assumptions", value)));
      return service.transaction(c ->
      {
         var before = CarlService.manager(c, principal, "FINANCE");
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", before.householdId());
         var actor = CarlService.manager(c, principal, "FINANCE");
         if(actor.householdId() != before.householdId())
         {
            throw new SecurityException("Household changed while acquiring the scenario lock");
         }
         var sources = new LinkedHashMap<Long, Long>();
         var scope = CarlService.Scope.privateFor(principal);
         var property = BalanceSheets.permitted(c, scope, "carl_rental_property_view", value.property(), sources);
         var baseline = BalanceSheets.permitted(c, scope, "carl_artifact_view", value.baselineReport(), sources);
         baseline(baseline, value.property());
         String currency = property.get("currency").toString();
         money(value.expectedRent(), currency);
         money(value.repair(), currency);
         money(value.allocatedPrincipal(), currency);
         validatePrincipal(c, scope, property, value, sources);
         Long previous = CarlService.request(c, actor, request, "RENTAL_SHOCK", digest);
         if(previous != null)
         {
            BalanceSheets.permitted(c, scope, "carl_rental_shock_view", previous, sources);
            return previous;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, value.evidence());
         CarlService.execute(c, "INSERT INTO carl_rental_shock(record_id,baseline_artifact_id,property_id,expected_rent,vacancy_fraction,repair_amount,additional_annual_rate,allocated_principal,balance_as_of,assumptions,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id, value.baselineReport(), value.property(), value.expectedRent(), value.vacancyFraction(), value.repair(), value.additionalAnnualRate(), value.allocatedPrincipal(), value.balanceAsOf(), value.evidence(), actor.id());
         CarlService.complete(c, request, id, "COMPLETE", "Human-reviewed hypothetical stress inputs; no action or forecast assurance");
         CarlService.bump(c, actor.householdId());
         return id;
      });
   }



   /** Computes and saves a conditional stress replay under the exact current audience. */
   public long report(CarlService.Scope scope, UUID request, long scenario)
   {
      String digest = BillCsv.hash("RENTAL_STRESS:" + scenario);
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         sheets.requireArtifact(scope, prior);
         return prior;
      }
      var restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         var snapshot = service.transaction(c ->
         {
            long epoch = BalanceSheets.lock(c, scope);
            var sources = new LinkedHashMap<Long, Long>();
            var row = BalanceSheets.permitted(c, scope, "carl_rental_shock_view", scenario, sources);
            long propertyId = CarlService.number(row, "property_id");
            var property = BalanceSheets.permitted(c, scope, "carl_rental_property_view", propertyId, sources);
            var baselineRow = BalanceSheets.permitted(c, scope, "carl_artifact_view", CarlService.number(row, "baseline_artifact_id"), sources);
            var metrics = baseline(baselineRow, propertyId);
            for(var dependency : CarlService.rows(c, "SELECT source_id,source_revision FROM carl_artifact_source WHERE artifact_id=?", row.get("baseline_artifact_id")))
            {
               sources.put(CarlService.number(dependency, "source_id"), CarlService.number(dependency, "source_revision"));
            }
            if(sources.size() > 2000)
            {
               throw new IllegalArgumentException("Source dependency count exceeds 2000; select a narrower baseline");
            }
            String currency = row.get("currency").toString();
            var value = new Assumptions(CarlService.number(row, "baseline_artifact_id"), propertyId, decimal(row, "expected_rent"), decimal(row, "vacancy_fraction"), decimal(row, "repair_amount"), decimal(row, "additional_annual_rate"), decimal(row, "allocated_principal"), LocalDate.parse(row.get("balance_as_of").toString()), row.get("assumptions").toString());
            var principalObservations = validatePrincipal(c, scope, property, value, sources);
            LocalDate from = LocalDate.parse(baselineRow.get("period_start").toString());
            LocalDate through = LocalDate.parse(baselineRow.get("period_end").toString());
            long days = ChronoUnit.DAYS.between(from, through) + 1;
            if(days < 1 || days > 366)
            {
               throw new IllegalArgumentException("Rental baseline interval exceeds 366 days");
            }
            BigDecimal vacancy = BalanceSheets.rounded(value.expectedRent().multiply(value.vacancyFraction()), currency);
            BigDecimal interest = BalanceSheets.rounded(value.allocatedPrincipal().multiply(value.additionalAnnualRate()).multiply(BigDecimal.valueOf(days)).divide(new BigDecimal("365"), 12, RoundingMode.HALF_UP), currency);
            BigDecimal impact = vacancy.add(value.repair()).add(interest);
            BigDecimal baselineCash = metrics.path("wholeProperty").path("cashAfterReserveTransfers").decimalValue();
            BigDecimal hypothetical = baselineCash.subtract(impact);
            var owned = (BigDecimal) property.get("ownership_share");
            var gaps = new java.util.ArrayList<String>();
            gaps.add("Baseline source coverage is partial. This stress replay is conditional, not future rental income, required loan payment, affordability or tax treatment.");
            if(owned == null)
            {
               gaps.add("Ownership is unknown; no ownership-attributed result computed.");
            }
            if(property.get("debt_account_id") != null)
            {
               var debt = BalanceSheets.permitted(c, scope, "carl_account_view", CarlService.number(property, "debt_account_id"), sources);
               if(owned == null || owned.compareTo((BigDecimal) debt.get("ownership_share")) != 0)
               {
                  gaps.add("Property and mortgage account ownership differ or are unresolved; stress attribution is not a statement of legal debt liability.");
               }
            }
            var facts = new LinkedHashMap<String, Object>();
            facts.put("calculationVersion", "rental-stress-replay-v1");
            facts.put("scenario", scenario);
            facts.put("property", propertyId);
            facts.put("baselineReport", value.baselineReport());
            facts.put("from", from);
            facts.put("through", through);
            facts.put("currency", currency);
            facts.put("assumptions", value);
            facts.put("ratePrincipalObservations", principalObservations);
            facts.put("baselineKnownScheduledRent", metrics.path("wholeProperty").path("knownScheduledRent"));
            facts.put("baselineCashAfterReserveTransfers", baselineCash);
            facts.put("hypotheticalVacancyLoss", vacancy);
            facts.put("additionalRepair", value.repair());
            facts.put("additionalSimpleInterest", interest);
            facts.put("totalAdverseCashImpact", impact);
            facts.put("hypotheticalCashAfterReserveTransfers", hypothetical);
            facts.put("ownershipFraction", owned);
            BigDecimal ownedVacancy = owned == null ? null : BalanceSheets.rounded(vacancy.multiply(owned), currency);
            BigDecimal ownedRepair = owned == null ? null : BalanceSheets.rounded(value.repair().multiply(owned), currency);
            BigDecimal ownedInterest = owned == null ? null : BalanceSheets.rounded(interest.multiply(owned), currency);
            BigDecimal ownedImpact = owned == null ? null : ownedVacancy.add(ownedRepair).add(ownedInterest);
            BigDecimal ownedBaseline = owned == null ? null : metrics.path("ownershipProportion").path("cashAfterReserveTransfers").decimalValue();
            facts.put("ownershipAttributedBaselineCash", ownedBaseline);
            facts.put("ownershipAttributedVacancy", ownedVacancy);
            facts.put("ownershipAttributedRepair", ownedRepair);
            facts.put("ownershipAttributedInterest", ownedInterest);
            facts.put("ownershipAttributedImpact", ownedImpact);
            facts.put("ownershipAttributedHypotheticalCash", owned == null ? null : ownedBaseline.subtract(ownedImpact));
            facts.put("ownershipRounding", "Each adverse component rounded to currency precision at the supplied property ownership fraction, then summed; hypothetical owned cash subtracts this sum from the existing source-calculated ownership baseline. Attribution is not a distribution or verified debt obligation.");
            facts.put("rateMethod", "Allocated constant whole-property principal × additional annual rate × actual interval days / 365, rounded to currency precision. Not an amortizing loan payment or a lender quote; no principal reduction, compounding, refinancing fee or rate reset inferred.");
            facts.put("vacancyMethod", "Explicit supplied expected whole-property rent for the baseline interval × assumed vacancy fraction. Compared with the same historical interval once, without extrapolation; not proof of lost actual receipts.");
            facts.put("coverageGaps", gaps);
            facts.put("status", "CONDITIONAL_HYPOTHETICAL_REPLAY");
            return new BalanceSheets.Snapshot(epoch, facts, sources);
         });
         return sheets.save(scope, request, (LocalDate) snapshot.facts().get("from"), (LocalDate) snapshot.facts().get("through"), snapshot, digest, "Incomplete rental stress replay — explicit human assumptions");
      }
      finally
      {
         restore.run();
      }
   }



   private static com.fasterxml.jackson.databind.JsonNode baseline(Map<String, Object> artifact, long property)
   {
      if(Boolean.TRUE.equals(artifact.get("stale")))
      {
         throw new IllegalArgumentException("Rental baseline is stale; generate a current source snapshot");
      }
      try
      {
         var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(artifact.get("facts").toString());
         if(!"rental-cash-v1".equals(facts.path("calculationVersion").asText()))
         {
            throw new IllegalArgumentException("Select a Carl rental cash report");
         }
         for(var result : facts.path("calculation").path("properties"))
         {
            if(Long.toString(property).equals(result.path("property").asText()) && result.path("wholeProperty").path("cashAfterReserveTransfers").isNumber())
            {
               return result;
            }
         }
         throw new IllegalArgumentException("Property has no calculated baseline; resolve its ownership and source facts first");
      }
      catch(com.fasterxml.jackson.core.JsonProcessingException invalid)
      {
         throw new IllegalArgumentException("Invalid stored rental facts", invalid);
      }
   }



   private static java.util.List<Map<String, Object>> validatePrincipal(java.sql.Connection c, CarlService.Scope scope, Map<String, Object> property, Assumptions value, Map<Long, Long> sources) throws java.sql.SQLException
   {
      if(value.additionalAnnualRate().signum() == 0 && value.allocatedPrincipal().signum() == 0)
      {
         return java.util.List.of();
      }
      if(value.additionalAnnualRate().signum() == 0 || value.allocatedPrincipal().signum() == 0 || property.get("debt_account_id") == null)
      {
         throw new IllegalArgumentException("Rate shock requires both explicit additional APR and allocated mortgage principal");
      }
      long id = CarlService.number(property, "debt_account_id");
      var debt = BalanceSheets.permitted(c, scope, "carl_account_view", id, sources);
      var observations = BalanceSheets.balance(c, id, value.balanceAsOf());
      BigDecimal balance = BalanceSheets.resolved(observations);
      if(balance == null || balance.signum() >= 0 || !value.balanceAsOf().toString().equals(observations.getFirst().get("as_of").toString()) || value.allocatedPrincipal().compareTo(balance.negate()) > 0 || !property.get("currency").equals(debt.get("currency")))
      {
         throw new IllegalArgumentException("Exact-date resolved negative mortgage balance and bounded same-currency principal allocation required");
      }
      return observations;
   }



   private static BigDecimal decimal(Map<String, Object> row, String key)
   {
      return (BigDecimal) row.get(key);
   }



   private static void fraction(BigDecimal value, BigDecimal maximum)
   {
      if(value == null || value.precision() > 18 || Math.abs((long) value.scale()) > 10 || value.signum() < 0 || value.compareTo(maximum) > 0)
      {
         throw new IllegalArgumentException("Bounded explicit fraction or rate required");
      }
   }



   private static BigDecimal money(BigDecimal value, String currency)
   {
      if(value == null || value.precision() > 18 || Math.abs((long) value.scale()) > 18 || value.signum() < 0 || value.compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("Bounded nonnegative money required");
      }
      int scale = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Currency precision unsupported");
      }
      return value.setScale(scale, RoundingMode.UNNECESSARY);
   }
}
