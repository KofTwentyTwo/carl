
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FinancingScenariosTest
{
   private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

   @Test
   void explicitFirstPaymentRetainsMonthEndDayWithoutOneMonthShift()
   {
      var terms = new FinancingScenarios.Offer("date-check", "USD", new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"), 12, List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL);
      var result = FinancingScenarios.projectFromFirstPayment(terms, LocalDate.of(2027, 1, 31));
      assertEquals(LocalDate.of(2027, 1, 31), result.months().get(0).paymentDate());
      assertEquals(LocalDate.of(2027, 2, 28), result.months().get(1).paymentDate());
      assertEquals(LocalDate.of(2027, 3, 31), result.months().get(2).paymentDate());
   }



   @Test
   void trueZeroAndDeferredInterestHaveDifferentExpiryCosts()
   {
      var trueZero = FinancingScenarios.project(offer("zero", "1000", "0", "0", "100", 24,
         new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 6, decimal("0.24"), true)), DATE);
      var deferred = FinancingScenarios.project(offer("deferred", "1000", "0", "0", "100", 24,
         new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 6, decimal("0.24"), true)), DATE);
      assertEquals(decimal("400.00"), trueZero.months().get(5).closingBalance());
      assertEquals(decimal("490.00"), deferred.months().get(5).closingBalance());
      assertEquals(decimal("90.00"), deferred.months().get(5).deferredInterestCharged());
      assertEquals(decimal("399.80"), deferred.months().get(6).closingBalance());
      assertTrue(deferred.totalInterest().compareTo(trueZero.totalInterest()) > 0);
   }



   @Test
   void deferredInterestIsWaivedOnlyWhenPaidWithinExplicitPromotion()
   {
      var result = FinancingScenarios.project(offer("paid", "1000", "0", "0", "200", 24,
         new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 6, decimal("0.24"), true)), DATE);
      assertTrue(result.paidOff());
      assertEquals(5, result.months().size());
      assertEquals(decimal("0.00"), result.totalInterest());
      assertEquals(decimal("0.00"), result.pendingDeferredInterest());
      assertEquals(LocalDate.of(2027, 3, 1), result.payoffDate());
   }



   @Test
   void unknownAllocationTermsPreventPreciseDeferredInterestProjection()
   {
      var result = FinancingScenarios.project(offer("unknown", "1000", "0", "0", "100", 24,
         new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 6, decimal("0.24"), false)), DATE);
      assertFalse(result.determined());
      assertTrue(result.months().isEmpty());
      assertEquals(null, result.totalInterest());
   }



   @Test
   void feesAndBalloonAreSeparateFromBorrowedPrincipalAndApproval()
   {
      var result = FinancingScenarios.project(offer("fees", "1000", "30", "10", "100", 6, FinancingScenarios.Promotion.none()), DATE);
      assertFalse(result.paidOff());
      assertEquals(decimal("40.00"), result.totalFees());
      assertEquals(decimal("430.00"), result.balloonDue());
      assertEquals(decimal("610.00"), result.cashPaid());
      assertEquals(FinancingScenarios.Evidence.HYPOTHETICAL, result.evidence());
   }



   @Test
   void partialTransfersConservePrincipalAndRespectCapacityIncludingFees()
   {
      var original = List.of(new FinancialPlanning.Debt("card", "USD", decimal("2000"), decimal("0.24"), decimal("50")));
      var offer = offer("transfer", "1000", "30", "0", "100", 24, FinancingScenarios.Promotion.none());
      var plan = FinancingScenarios.transfer(original, Map.of("card", decimal("1000")), offer, decimal("1030"));
      assertEquals(decimal("1000.00"), plan.retainedDebts().getFirst().balance());
      assertEquals(decimal("2000.00"), plan.originalPrincipal());
      assertEquals(decimal("2030.00"), plan.totalDebtAfterFees());
      assertThrows(IllegalArgumentException.class, () -> FinancingScenarios.transfer(original, Map.of("card", decimal("1000")), offer, decimal("1000")));
      assertThrows(IllegalArgumentException.class, () -> FinancingScenarios.transfer(original, Map.of("card", decimal("2100")), offer, decimal("3000")));
   }



   @Test
   void estimateMayEndExactlyAtPromotionExpiryWithTruthfulResidual()
   {
      var result = FinancingScenarios.project(offer("expiry", "1000", "0", "0", "100", 6,
         new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 6, decimal("0.24"), true)), DATE);
      assertEquals(decimal("490.00"), result.balloonDue());
      assertFalse(result.paidOff());
   }



   @Test
   void invalidTermsProduceControlledValidationErrors()
   {
      assertThrows(IllegalArgumentException.class, () -> new FinancingScenarios.Offer("bad", null, decimal("1000"), BigDecimal.ZERO,
         BigDecimal.ZERO, decimal("100"), 6, List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL));
      assertThrows(IllegalArgumentException.class, () -> new FinancingScenarios.Offer("bad", "USD", decimal("1000"), BigDecimal.ZERO,
         BigDecimal.ZERO, decimal("100"), 6, java.util.Arrays.asList((FinancialPlanning.Rate) null), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL));
   }



   @Test
   void lowerPaymentAndLowerAprCanStillCostMoreOverLongerTerm()
   {
      var shortOffer = new FinancingScenarios.Offer("current", "USD", decimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
         decimal("100"), 24, List.of(new FinancialPlanning.Rate(1, decimal("0.24"))), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.VERIFIED_TERMS);
      var longOffer = new FinancingScenarios.Offer("refi", "USD", decimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
         decimal("40"), 60, List.of(new FinancialPlanning.Rate(1, decimal("0.12"))), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL);
      var current = FinancingScenarios.project(shortOffer, DATE);
      var refi = FinancingScenarios.project(longOffer, DATE);
      assertEquals(decimal("127.04"), current.totalInterest());
      assertTrue(refi.totalInterest().compareTo(current.totalInterest()) > 0);
      assertTrue(refi.payoffDate().isAfter(current.payoffDate()));
   }



   private static FinancingScenarios.Offer offer(String id, String principal, String financedFee, String cashFee, String payment,
      int term, FinancingScenarios.Promotion promotion)
   {
      var rates = promotion.kind() == FinancingScenarios.PromotionKind.NONE
         ? List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO))
         : List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(promotion.months() + 1, decimal("0.24")));
      return new FinancingScenarios.Offer(id, "USD", decimal(principal), decimal(financedFee), decimal(cashFee), decimal(payment),
         term, rates, promotion, FinancingScenarios.Evidence.HYPOTHETICAL);
   }



   private static BigDecimal decimal(String value)
   {
      return new BigDecimal(value);
   }
}
