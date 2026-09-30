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
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;


/** Scoped durable cash/card/store-financing purchase recommendations over the same source snapshot. */
public final class PurchaseAssessments
{
   /** Human reviewed existing-card grace terms; prompt text never supplies caller identity. */
   public record CardInput(long account, LocalDate payoffDate, boolean graceConfirmed, boolean balanceReviewed, String evidence)
   {
   }
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).registerModule(new JavaTimeModule());
   private final CarlService service;
   /** Shares current authoritative records and permissions with all Carl interfaces. */
   public PurchaseAssessments(CarlService service)
   {
      this.service = service;
   }



   /** Saves one request-bound comparison; no external purchase, credit application or payment occurs. */
   public long compare(CarlService.Scope scope, UUID request, long cashPlan, LocalDate purchaseDate, BigDecimal price,
      String purpose, boolean allInCostsKnown, CardInput card, List<Long> offerIds)
   {
      if(offerIds == null || offerIds.size() > 5 || offerIds.stream().distinct().count() != offerIds.size() || offerIds.stream().anyMatch(id -> id == null || id <= 0))
      {
         throw new IllegalArgumentException("Select up to five distinct actual offer records");
      }
      var input = new LinkedHashMap<String, Object>();
      input.put("operation", "PURCHASE_OPTIONS");
      input.put("cashPlan", cashPlan);
      input.put("purchaseDate", purchaseDate);
      input.put("price", price);
      input.put("purpose", purpose);
      input.put("allInCostsKnown", allInCostsKnown);
      input.put("card", card);
      input.put("offers", offerIds.stream().sorted().toList());
      String digest = BillCsv.hash(CarlService.json(input));
      long epoch = service.member(scope.principal()).permissionRevision();
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      UUID baselineRequest = UUID.nameUUIDFromBytes(("Carl purchase options cash baseline:" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      long baselineId = new CashPlans(service).assess(scope, baselineRequest, cashPlan, purchaseDate, price, purpose, allInCostsKnown);
      var baseline = service.artifact(scope.principal(), baselineId);
      if(Boolean.TRUE.equals(baseline.get("stale")))
      {
         throw new IllegalArgumentException("Cash baseline changed; reconcile the request and regenerate from current sources");
      }
      final com.fasterxml.jackson.databind.JsonNode facts;
      final PurchaseAffordability.Budget budget;
      try
      {
         facts = JSON.readTree(baseline.get("facts").toString());
         budget = JSON.treeToValue(facts.get("budget"), PurchaseAffordability.Budget.class);
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalStateException("Invalid stored cash baseline", invalid);
      }
      var sources = service.transaction(c ->
      {
         var result = new LinkedHashMap<Long, Long>();
         for(var row : CarlService.rows(c, "SELECT source_id,source_revision FROM carl_artifact_source WHERE artifact_id=?", baselineId))
         {
            result.put(CarlService.number(row, "source_id"), CarlService.number(row, "source_revision"));
         }
         return result;
      });
      PurchaseChoices.Card chosenCard = null;
      Map<String, Object> cardRecord = Map.of();
      if(card != null)
      {
         CarlService.bounded(card.evidence(), 4000, "Card evidence");
         long cardRevision = service.transaction(c ->
         {
            var rows = CarlService.rows(c, "SELECT r.revision FROM carl_account_view a JOIN carl_record r ON r.id=a.id WHERE a.principal=? AND a.id=?", scope.principal(), card.account());
            if(rows.size() != 1)
            {
               throw new SecurityException("Card unavailable");
            }
            return CarlService.number(rows.getFirst(), "revision");
         });
         cardRecord = service.view(scope, "accounts").stream().filter(row -> CarlService.number(row, "id") == card.account()).findFirst().orElseThrow(() -> new SecurityException("Card unavailable to the full audience"));
         if(!cardRecord.get("kind").equals("CREDIT_CARD") || !cardRecord.get("currency").equals(budget.currency()))
         {
            throw new IllegalArgumentException("Choose an existing card in the purchase currency");
         }
         var debt = service.view(scope, "debts").stream().filter(row -> CarlService.number(row, "id") == card.account()).findFirst().orElse(null);
         LocalDate dataAsOf = LocalDate.parse(facts.get("expenseAsOf").asText());
         boolean resolved = card.balanceReviewed() && debt != null && debt.get("principal_balance") instanceof BigDecimal balance && balance.signum() == 0 && debt.get("balance_as_of") != null && LocalDate.parse(debt.get("balance_as_of").toString()).equals(dataAsOf);
         if(cardRecord.get("as_of") != null && !LocalDate.parse(cardRecord.get("as_of").toString()).isBefore(dataAsOf) && cardRecord.get("balance") instanceof BigDecimal imported && imported.signum() != 0)
         {
            resolved = false;
         }
         chosenCard = new PurchaseChoices.Card("card:" + card.account(), card.payoffDate(), card.graceConfirmed(), resolved, card.evidence());
         sources.put(card.account(), cardRevision);
      }
      var offers = new ArrayList<PurchaseChoices.Offer>();
      var offerEvidence = new ArrayList<Map<String, Object>>();
      var available = service.view(scope, "financingOffers");
      for(long id : offerIds)
      {
         var row = available.stream().filter(value -> CarlService.number(value, "id") == id).findFirst().orElseThrow(() -> new SecurityException("Offer unavailable to the full audience"));
         if(!row.get("kind").equals("PURCHASE_FINANCE"))
         {
            throw new IllegalArgumentException("Choose a purchase-financing offer; debt transfers do not fund a new purchase");
         }
         var terms = FinancingOffers.terms(row);
         boolean current = !purchaseDate.isBefore(LocalDate.parse(row.get("as_of").toString())) && !purchaseDate.isAfter(LocalDate.parse(row.get("expires_on").toString()));
         offers.add(new PurchaseChoices.Offer(terms, LocalDate.parse(row.get("first_payment").toString()), current, row.get("evidence").toString()));
         offerEvidence.add(row);
         sources.put(id, CarlService.number(row, "revision"));
      }
      var result = PurchaseChoices.compare(budget, facts.get("protectedReserve").decimalValue(), facts.get("sourcePlan").get("discretionary_cap").decimalValue(), price, chosenCard, offers);
      var saved = new LinkedHashMap<String, Object>();
      saved.put("purpose", purpose);
      saved.put("cashBaselineArtifact", baselineId);
      saved.put("cashBaseline", facts);
      saved.put("cardRecord", cardRecord);
      saved.put("cardTerms", card);
      saved.put("offers", offerEvidence);
      saved.put("purchaseOptions", result);
      String encoded = CarlService.json(saved);
      if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_000_000)
      {
         throw new IllegalArgumentException("Narrow purchase options; preserved report limit is 2 MB");
      }
      boolean partial = result.preferredOption() == null || result.options().stream().anyMatch(option -> option.state() == PurchaseChoices.State.UNDETERMINED);
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", purchaseDate, LocalDate.parse(facts.get("sourcePlan").get("through_date").asText()), encoded, result.recommendation(), "NOT_REQUESTED", result.limitations(), sources, null, partial ? "Incomplete purchase options — review missing evidence" : "Conditional purchase recommendation — review current terms", digest, epoch);
   }
}
