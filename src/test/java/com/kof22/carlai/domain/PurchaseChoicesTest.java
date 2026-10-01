/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PurchaseChoicesTest
{
   private static final LocalDate DATE = LocalDate.of(2026, 9, 1);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static PurchaseAffordability.Budget budget(boolean complete)
   {
      return PurchaseAffordability.budget("USD", n("1000"), n("200"), DATE, DATE.plusMonths(6), DATE.plusDays(2), n("1000"), List.of(), new PurchaseAffordability.Conditions(complete, true, true, true, true, true, true));
   }



   private static PurchaseChoices.Offer offer(String id, String payment, int term, FinancingScenarios.Promotion promotion)
   {
      var rates = promotion.kind() == FinancingScenarios.PromotionKind.NONE ? List.of(new FinancialPlanning.Rate(1, n("0.24"))) : List.of(new FinancialPlanning.Rate(1, n("0")), new FinancialPlanning.Rate(promotion.months() + 1, n("0.24")));
      return new PurchaseChoices.Offer(new FinancingScenarios.Offer(id, "USD", n("600"), n("0"), n("0"), n(payment), term, rates, promotion, FinancingScenarios.Evidence.VERIFIED_TERMS), DATE.plusMonths(1), true, "Synthetic reviewed terms");
   }



   @Test
   void extendedInitialInterestPeriodCannotBeRankedUsingOneMonthAccrual()
   {
      var regular = offer("late-first-payment", "200", 6, FinancingScenarios.Promotion.none());
      var delayed = new PurchaseChoices.Offer(regular.terms(), DATE.plusMonths(3), true, "First interest period unsupported");
      var result = PurchaseChoices.compare(budget(true), n("200"), n("1000"), n("600"), null, List.of(delayed));
      assertEquals(PurchaseChoices.State.UNDETERMINED, result.options().get(1).state());
      assertNull(result.options().get(1).totalCashOutlay());
      assertTrue(result.options().get(1).gaps().toString().contains("extended initial interest"));
   }



   @Test
   void comparesCashFullCardAndTrueZeroWithExplicitConditionalChoice()
   {
      var result = PurchaseChoices.compare(budget(true), n("200"), n("1000"), n("600"), new PurchaseChoices.Card("card:1", DATE.plusMonths(1), true, true, "Synthetic grace and full repayment evidence"), List.of(offer("store:1", "100", 6, new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 6, n("0"), true))));
      assertEquals("CASH", result.preferredOption());
      assertEquals(3, result.options().size());
      assertEquals(n("800.00"), result.maximumCashBudget());
      assertTrue(result.options().stream().allMatch(option -> option.state() == PurchaseChoices.State.WITHIN_STATED_PLAN));
      assertEquals(0, n("600").compareTo(result.options().get(2).totalCashOutlay()));
      assertTrue(result.recommendation().contains("Conditionally"));
   }



   @Test
   void missingEvidenceNeverTreatsAvailableCreditAsBudget()
   {
      var result = PurchaseChoices.compare(budget(false), n("200"), n("1000"), n("600"), null, List.of());
      assertNull(result.preferredOption());
      assertNull(result.maximumCashBudget());
      assertEquals(PurchaseChoices.State.UNDETERMINED, result.options().getFirst().state());
      var card = PurchaseChoices.compare(budget(true), n("200"), n("1000"), n("600"), new PurchaseChoices.Card("card:1", DATE.plusMonths(1), false, false, "Grace not established"), List.of());
      assertEquals(PurchaseChoices.State.UNDETERMINED, card.options().get(1).state());
      assertNull(card.options().get(1).financeCost());
      assertNull(card.options().get(1).totalCashOutlay());
   }



   @Test
   void financingOutsideCashWindowIsUnqualifiedAndDeferredInterestIsVisible()
   {
      var result = PurchaseChoices.compare(budget(true), n("200"), n("1000"), n("600"), null, List.of(offer("loan", "60", 18, FinancingScenarios.Promotion.none()), offer("deferred", "100", 12, new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 3, n("0.24"), true))));
      assertEquals(PurchaseChoices.State.UNDETERMINED, result.options().get(1).state());
      assertTrue(result.options().get(1).gaps().toString().contains("outside"));
      assertTrue(result.options().get(2).financeCost().signum() > 0);
      assertTrue(result.options().get(2).gaps().toString().contains("Deferred interest"));
   }



   @Test
   void capAndDatedReserveConstrainFinancedPurchase()
   {
      var result = PurchaseChoices.compare(budget(true), n("200"), n("500"), n("600"), null, List.of(offer("store", "100", 6, new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 6, n("0"), true))));
      assertNull(result.preferredOption());
      assertTrue(result.options().stream().allMatch(option -> option.state() == PurchaseChoices.State.EXCEEDS_PLAN));
      var baseline = PurchaseAffordability.budget("USD", n("700"), n("200"), DATE, DATE.plusMonths(6), DATE.plusDays(2), n("1000"), List.of(), new PurchaseAffordability.Conditions(true, true, true, true, true, true, true));
      var reserve = PurchaseChoices.compare(baseline, n("200"), n("1000"), n("600"), null, List.of());
      assertEquals(DATE.plusDays(2), reserve.options().getFirst().firstReserveShortfall());
   }



   @Test
   void rejectsDifferentPrincipalAndInvalidRepaymentIdentity()
   {
      assertThrows(IllegalArgumentException.class, () -> PurchaseChoices.compare(budget(true), n("200"), n("1000"), n("500"), null, List.of(offer("loan", "100", 12, FinancingScenarios.Promotion.none()))));
      assertThrows(IllegalArgumentException.class, () -> PurchaseChoices.compare(budget(true), n("200"), n("1000"), n("600"), new PurchaseChoices.Card("CASH", DATE.plusMonths(1), true, true, "Synthetic"), List.of()));
   }
}
