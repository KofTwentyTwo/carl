
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FinancialPlanning;
import com.kof22.carlai.domain.FinancingOffers;
import com.kof22.carlai.domain.FinancingScenarios;


/** Human-supplied financing terms and isolated cost comparisons in native administration. */
final class OfferProcesses
{
   private OfferProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var offers = new FinancingOffers(service);
      var table = CarlMetadata.table("carlFinancingOffers", "Financing Offers", "carl_financing_offer_view", "kind:S,currency:S,financed_principal:M,monthly_payment:M,term_months:L,initial_apr:M,post_promo_apr:M,financed_fee:M,cash_fee:M,promotion:S,promo_months:L,deferred_apr:M,allocation_confirmed:B,evidence_class:S,as_of:D,expires_on:D,first_payment:D,collateral_terms:T");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      CarlMetadata.choices(instance, "carlOfferKind", List.of("CONSOLIDATION", "REFINANCE", "BALANCE_TRANSFER", "PURCHASE_FINANCE"));
      CarlMetadata.choices(instance, "carlOfferPromotion", List.of("NONE", "TRUE_ZERO", "DEFERRED_INTEREST"));
      CarlMetadata.choices(instance, "carlOfferEvidence", List.of("HYPOTHETICAL", "VERIFIED_TERMS"));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRecordOffer", "Record Financing Offer Assumptions", List.of(
         field("title", QFieldType.STRING), field("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), field("kind", QFieldType.STRING).withPossibleValueSourceName("carlOfferKind"), field("currency", QFieldType.STRING), field("principal", QFieldType.DECIMAL), field("monthlyPayment", QFieldType.DECIMAL), field("termMonths", QFieldType.INTEGER), field("financedFee", QFieldType.DECIMAL), field("cashFee", QFieldType.DECIMAL), field("initialApr", QFieldType.DECIMAL).withLabel("Initial decimal APR (0.24 means 24%)"), field("postPromoApr", QFieldType.DECIMAL), field("promotion", QFieldType.STRING).withPossibleValueSourceName("carlOfferPromotion"), field("promoMonths", QFieldType.INTEGER), field("deferredApr", QFieldType.DECIMAL), field("allocationConfirmed", QFieldType.BOOLEAN), field("evidenceClass", QFieldType.STRING).withPossibleValueSourceName("carlOfferEvidence"), field("asOf", QFieldType.DATE), field("expires", QFieldType.DATE),
         field("firstPayment", QFieldType.DATE), field("collateral", QFieldType.TEXT), field("evidence", QFieldType.TEXT)), (in, out) ->
         {
            var promotion = new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.valueOf(in.getValueString("promotion")), Integer.parseInt(in.getValueString("promoMonths")), new BigDecimal(in.getValueString("deferredApr")), Boolean.TRUE.equals(in.getValueBoolean("allocationConfirmed")));
            var rates = new ArrayList<FinancialPlanning.Rate>();
            rates.add(new FinancialPlanning.Rate(1, new BigDecimal(in.getValueString("initialApr"))));
            if(promotion.kind() != FinancingScenarios.PromotionKind.NONE)
            {
               rates.add(new FinancialPlanning.Rate(promotion.months() + 1, new BigDecimal(in.getValueString("postPromoApr"))));
            }
            var terms = new FinancingScenarios.Offer("human-terms", in.getValueString("currency"), new BigDecimal(in.getValueString("principal")), new BigDecimal(in.getValueString("financedFee")), new BigDecimal(in.getValueString("cashFee")), new BigDecimal(in.getValueString("monthlyPayment")), Integer.parseInt(in.getValueString("termMonths")), rates, promotion, FinancingScenarios.Evidence.valueOf(in.getValueString("evidenceClass")));
            long id = offers.create(CarlMetadata.principal(), in.getValueString("title"), in.getValueString("visibility"), in.getValueString("kind"), terms, in.getValueLocalDate("asOf"), in.getValueLocalDate("expires"), in.getValueLocalDate("firstPayment"), in.getValueString("collateral"), in.getValueString("evidence"));
            out.addValue("result", "Offer assumptions " + id + " saved. No application or acceptance occurred; human-supplied verified terms are not independent issuer verification.");
         }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCompareOffers", "Compare Financing Offer Costs", List.of(field("firstOffer", QFieldType.LONG).withPossibleValueSourceName("carlFinancingOffers"), field("secondOffer", QFieldType.LONG).withPossibleValueSourceName("carlFinancingOffers"), field("asOf", QFieldType.DATE)), (in, out) ->
      {
         long id = offers.compare(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), List.of(Long.parseLong(in.getValueString("firstOffer")), Long.parseLong(in.getValueString("secondOffer"))), in.getValueLocalDate("asOf"));
         out.addValue("result", service.artifact(CarlMetadata.principal(), id).get("facts").toString() + "\nSaved comparison " + id + ". Household affordability, retained debts and collateral risk require separate review.");
      }));
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
