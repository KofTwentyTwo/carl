
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Dated human-supplied offers and scoped isolated cost comparisons; no application or acceptance. */
public final class FinancingOffers
{
   private final CarlService service;
   /** Shares authoritative permissions and immutable report provenance. */
   public FinancingOffers(CarlService service)
   {
      this.service = service;
   }



   /** Persists explicit bounded terms; evidence class is a human assertion, not issuer verification. */
   public long create(String principal, String title, String visibility, String kind, FinancingScenarios.Offer offer,
      LocalDate asOf, LocalDate expires, LocalDate firstPayment, String collateral, String evidence)
   {
      if(!Set.of("CONSOLIDATION", "REFINANCE", "BALANCE_TRANSFER", "PURCHASE_FINANCE").contains(kind)
         || asOf == null || expires == null || firstPayment == null || expires.isBefore(asOf)
         || firstPayment.isBefore(asOf) || asOf.getYear() < 1900 || expires.getYear() > 2200 || firstPayment.getYear() > 2200
         || offer.rates().size() > 2)
      {
         throw new IllegalArgumentException("Dated supported offer terms are required");
      }
      CarlService.bounded(collateral, 4000, "Collateral and security terms, including unknowns");
      CarlService.bounded(evidence, 4000, "Offer evidence");
      var promotion = offer.promotion();
      if(promotion.kind() == FinancingScenarios.PromotionKind.NONE && offer.rates().size() != 1
         || promotion.kind() != FinancingScenarios.PromotionKind.NONE && offer.rates().size() != 2)
      {
         throw new IllegalArgumentException("Use one fixed rate or explicit promotion plus post-promotion rate");
      }
      return service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_financing_offer(record_id,kind,currency,principal,financed_fee,cash_fee,monthly_payment,term_months,initial_apr,post_promo_apr,promotion,promo_months,deferred_apr,allocation_confirmed,evidence_class,as_of,expires_on,first_payment,collateral_terms,entered_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, kind, offer.currency(), offer.principal(), offer.financedFee(), offer.cashFee(), offer.monthlyPayment(), offer.termMonths(), offer.rates().getFirst().annualRate(), offer.rates().getLast().annualRate(), promotion.kind().name(), promotion.months(), promotion.deferredAnnualRate(), promotion.allocationTermsConfirmed(), offer.evidence().name(), asOf, expires, firstPayment, collateral, actor.id());
         CarlService.bump(c, actor.householdId());
         return id;
      });
   }



   /** Saves equal-principal/equal-currency comparisons with separate fees, residuals and source dates. */
   public long compare(CarlService.Scope scope, UUID request, List<Long> selected, LocalDate asOf)
   {
      if(selected == null || selected.isEmpty() || selected.size() > 10 || selected.stream().distinct().count() != selected.size() || asOf == null)
      {
         throw new IllegalArgumentException("Select one to ten distinct offers and an explicit comparison date");
      }
      long epoch = service.member(scope.principal()).permissionRevision();
      String digest = BillCsv.hash(CarlService.json(Map.of("offers", selected, "asOf", asOf)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var permitted = service.view(scope, "financingOffers");
      var results = new ArrayList<Map<String, Object>>();
      var sources = new LinkedHashMap<Long, Long>();
      BigDecimal principal = null;
      String currency = null;
      boolean partial = false;
      for(long id : selected)
      {
         var row = permitted.stream().filter(r -> CarlService.number(r, "id") == id).findFirst().orElseThrow(() -> new SecurityException("Offer unavailable to comparison audience"));
         var offer = terms(row);
         if(principal != null && (principal.compareTo(offer.principal()) != 0 || !currency.equals(offer.currency())))
         {
            throw new IllegalArgumentException("Compare offers funding the same principal in the same currency");
         }
         principal = offer.principal();
         currency = offer.currency();
         boolean current = !asOf.isBefore(LocalDate.parse(row.get("as_of").toString())) && !asOf.isAfter(LocalDate.parse(row.get("expires_on").toString()));
         var projection = FinancingScenarios.projectFromFirstPayment(offer, LocalDate.parse(row.get("first_payment").toString()));
         partial |= !current || !projection.determined();
         results.add(Map.of("terms", row, "projection", projection, "sourceCurrent", current));
         sources.put(id, CarlService.number(row, "revision"));
      }
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", asOf, asOf, CarlService.json(Map.of("asOf", asOf, "comparisons", results)), "", "NOT_REQUESTED", "Isolated equal-principal costs only. Lower monthly payment may increase lifetime cost. Remaining debt, payment allocation, closing/prepayment costs, eligibility, collateral risk and dated household affordability require review. Human-entered VERIFIED_TERMS does not mean Carl independently verified the issuer. No application, acceptance or money movement.", sources, null, partial ? "Incomplete or stale financing assumptions" : "Isolated financing comparison — affordability unqualified", digest, epoch);
   }



   static FinancingScenarios.Offer terms(Map<String, Object> row)
   {
      var promotion = new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.valueOf(row.get("promotion").toString()), ((Number) row.get("promo_months")).intValue(), (BigDecimal) row.get("deferred_apr"), (Boolean) row.get("allocation_confirmed"));
      var rates = new ArrayList<FinancialPlanning.Rate>();
      rates.add(new FinancialPlanning.Rate(1, (BigDecimal) row.get("initial_apr")));
      if(promotion.kind() != FinancingScenarios.PromotionKind.NONE)
      {
         rates.add(new FinancialPlanning.Rate(promotion.months() + 1, (BigDecimal) row.get("post_promo_apr")));
      }
      return new FinancingScenarios.Offer("offer:" + CarlService.number(row, "id"), row.get("currency").toString(), (BigDecimal) row.get("financed_principal"), (BigDecimal) row.get("financed_fee"), (BigDecimal) row.get("cash_fee"), (BigDecimal) row.get("monthly_payment"), ((Number) row.get("term_months")).intValue(), rates, promotion, FinancingScenarios.Evidence.valueOf(row.get("evidence_class").toString()));
   }
}
