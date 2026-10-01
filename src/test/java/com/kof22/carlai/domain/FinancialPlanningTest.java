/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FinancialPlanningTest
{
   @Test
   void payoffUsesExactInterestAndMakesBudgetShortfallExplicit()
   {
      var debts = List.of(new FinancialPlanning.Debt("card", "USD", new BigDecimal("1000.00"),
         new BigDecimal("0.24"), new BigDecimal("50.00")));
      var plan = FinancialPlanning.payoff(debts, "USD", new BigDecimal("100.00"), FinancialPlanning.Strategy.AVALANCHE, 120);
      assertTrue(plan.paidOff());
      assertEquals(12, plan.months());
      assertEquals(new BigDecimal("127.04"), plan.interest());
      assertEquals(new BigDecimal("27.04"), plan.schedule().getLast().payment());
      assertFalse(FinancialPlanning.payoff(debts, "USD", new BigDecimal("10.00"), FinancialPlanning.Strategy.AVALANCHE, 120).feasible());
   }



   @Test
   void mixedCurrencyIsRejectedAndStrategiesHaveStableOrdering()
   {
      var debts = List.of(new FinancialPlanning.Debt("large", "USD", new BigDecimal("2000"), new BigDecimal("0.3"), new BigDecimal("50")),
         new FinancialPlanning.Debt("small", "USD", new BigDecimal("500"), new BigDecimal("0.1"), new BigDecimal("20")));
      assertEquals("large", FinancialPlanning.payoff(debts, "USD", new BigDecimal("200"), FinancialPlanning.Strategy.AVALANCHE, 120).priority().getFirst());
      assertEquals("small", FinancialPlanning.payoff(debts, "USD", new BigDecimal("200"), FinancialPlanning.Strategy.SNOWBALL, 120).priority().getFirst());
      assertThrows(IllegalArgumentException.class, () -> FinancialPlanning.payoff(debts, "EUR", new BigDecimal("200"), FinancialPlanning.Strategy.AVALANCHE, 120));
   }



   @Test
   void rentalDistinguishesOperatingIncomeFromFinancingAndReserves()
   {
      var result = FinancialPlanning.rental("USD", new BigDecimal("2000"), new BigDecimal("100"), new BigDecimal("500"),
         new BigDecimal("900"), new BigDecimal("200"));
      assertEquals(new BigDecimal("1400.00"), result.netOperatingIncome());
      assertEquals(new BigDecimal("300.00"), result.cashAfterDebtAndReserves());
   }



   @Test
   void rejectsFractionalCurrencyMinimumInsteadOfProducingFractionalPayments()
   {
      assertThrows(IllegalArgumentException.class, () -> new FinancialPlanning.Debt("card", "USD",
         new BigDecimal("100"), new BigDecimal("0.24"), new BigDecimal("0.001")));
   }



   @Test
   void boundsRatePrecisionToAvoidUnboundedArithmetic()
   {
      assertThrows(IllegalArgumentException.class, () -> new FinancialPlanning.Debt("card", "USD",
         new BigDecimal("100"), new BigDecimal("0.123456789012345678901"), new BigDecimal("10")));
   }



   @Test
   void rentalCannotTreatVacancyGreaterThanScheduledRentAsValidIncome()
   {
      assertThrows(IllegalArgumentException.class, () -> FinancialPlanning.rental("USD", new BigDecimal("100"),
         new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
   }



   @Test
   void promotionalExpirationChangesInterestAndAvalanchePriority()
   {
      var promo = new FinancialPlanning.Debt("promo", "USD", bd("100"), bd("10"), bd("0"),
         List.of(new FinancialPlanning.Rate(1, bd("0")), new FinancialPlanning.Rate(2, bd("0.36"))), bd("0"));
      var fixed = new FinancialPlanning.Debt("fixed", "USD", bd("100"), bd("0.12"), bd("10"));
      var result = FinancialPlanning.payoff(List.of(promo, fixed), "USD", bd("50"), FinancialPlanning.Strategy.AVALANCHE, 2);
      assertEquals(List.of("fixed", "promo"), result.schedule().get(0).priority());
      assertEquals(List.of("promo", "fixed"), result.schedule().get(1).priority());
      assertEquals(bd("4.31"), result.interest());
      assertEquals(bd("104.31"), result.schedule().getLast().balance());
      assertFalse(result.paidOff());
   }



   @Test
   void minimumOnlyRetainsUnusedBudgetAndReportsNegativeAmortization()
   {
      var debt = new FinancialPlanning.Debt("card", "USD", bd("1000"), bd("0.24"), bd("10"));
      var result = FinancialPlanning.payoff(List.of(debt), "USD", bd("100"), FinancialPlanning.Strategy.MINIMUM_ONLY, 1);
      assertTrue(result.feasible());
      assertFalse(result.paidOff());
      assertTrue(result.negativeAmortization());
      assertEquals(bd("1010.00"), result.schedule().getFirst().balance());
      assertEquals(bd("90.00"), result.schedule().getFirst().unusedBudget());
   }



   @Test
   void feesAndPercentageMinimumsCanProduceAnExplicitUnexecutedShortfall()
   {
      var debt = new FinancialPlanning.Debt("card", "USD", bd("100"), bd("5"), bd("0.10"),
         List.of(new FinancialPlanning.Rate(1, bd("0"))), bd("10"));
      var result = FinancialPlanning.payoff(List.of(debt), "USD", bd("10"), FinancialPlanning.Strategy.AVALANCHE, 12);
      assertFalse(result.feasible());
      assertEquals(1, result.shortfall().month());
      assertEquals(bd("11.00"), result.shortfall().requiredMinimum());
      assertTrue(result.schedule().isEmpty());
      assertEquals(bd("0.00"), result.interest());
      var paid = FinancialPlanning.payoff(List.of(debt), "USD", bd("150"), FinancialPlanning.Strategy.AVALANCHE, 12);
      assertTrue(paid.paidOff());
      assertEquals(bd("110.00"), paid.schedule().getFirst().payment());
      assertEquals(bd("10.00"), paid.fees());
      assertEquals(bd("40.00"), paid.schedule().getFirst().unusedBudget());
   }



   @Test
   void rateScheduleMustStartImmediatelyAndCannotHaveAmbiguousDates()
   {
      assertThrows(IllegalArgumentException.class, () -> new FinancialPlanning.Debt("card", "USD", bd("100"), bd("10"), bd("0"),
         List.of(new FinancialPlanning.Rate(2, bd("0.2"))), bd("0")));
      assertThrows(IllegalArgumentException.class, () -> new FinancialPlanning.Debt("card", "USD", bd("100"), bd("10"), bd("0"),
         List.of(new FinancialPlanning.Rate(1, bd("0.2")), new FinancialPlanning.Rate(1, bd("0.3"))), bd("0")));
   }



   @Test
   void zeroInterestPayoffRollsPaymentsForwardAndNeverOverpays()
   {
      var debts = List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("10")),
         new FinancialPlanning.Debt("b", "USD", bd("50"), bd("0"), bd("10")));
      var result = FinancialPlanning.payoff(debts, "USD", bd("60"), FinancialPlanning.Strategy.SNOWBALL, 12);
      assertTrue(result.paidOff());
      assertEquals(3, result.months());
      assertEquals(bd("30.00"), result.schedule().getLast().payment());
      assertEquals(bd("0.00"), result.interest());
      assertEquals(bd("0.00"), result.schedule().getLast().balance());
      assertEquals(result, FinancialPlanning.payoff(debts.reversed(), "USD", bd("60"), FinancialPlanning.Strategy.SNOWBALL, 12));
   }



   @Test
   void explicitPaymentsStayOnTheirDebtAndReleaseCashWhenItCloses()
   {
      var debts = List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("10")),
         new FinancialPlanning.Debt("b", "USD", bd("50"), bd("0"), bd("10")));
      var targets = java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("20"))),
         "b", List.of(new FinancialPlanning.PaymentTarget(1, bd("40"))));
      var result = FinancialPlanning.allocatedPayoff(debts, "USD", bd("60"), targets, FinancialPlanning.Strategy.MINIMUM_ONLY, 12);
      assertTrue(result.problems().isEmpty());
      assertTrue(result.payoff().paidOff());
      assertEquals(5, result.payoff().months());
      assertEquals(bd("30.00"), result.payoff().schedule().get(1).unusedBudget());
      assertEquals(bd("40.00"), result.payoff().schedule().get(2).unusedBudget());
      assertEquals(bd("20.00"), result.payoff().schedule().get(2).debts().getFirst().payment());
   }



   @Test
   void explicitBelowMinimumAllocationIsRejectedWithoutSilentlyChangingIt()
   {
      var debts = List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("30")),
         new FinancialPlanning.Debt("b", "USD", bd("50"), bd("0"), bd("10")));
      var targets = java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("20"))),
         "b", List.of(new FinancialPlanning.PaymentTarget(1, bd("40"))));
      var result = FinancialPlanning.allocatedPayoff(debts, "USD", bd("60"), targets, FinancialPlanning.Strategy.MINIMUM_ONLY, 12);
      assertFalse(result.payoff().feasible());
      assertTrue(result.payoff().schedule().isEmpty());
      assertEquals("a", result.problems().getFirst().debtId());
      assertEquals(bd("30.00"), result.problems().getFirst().required());
   }



   @Test
   void explicitPaymentScheduleCanChangeAndRolloverRequiresSelectedPolicy()
   {
      var debts = List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("10")),
         new FinancialPlanning.Debt("b", "USD", bd("50"), bd("0"), bd("10")));
      var targets = java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("20")), new FinancialPlanning.PaymentTarget(3, bd("40"))),
         "b", List.of(new FinancialPlanning.PaymentTarget(1, bd("40"))));
      var fixed = FinancialPlanning.allocatedPayoff(debts, "USD", bd("60"), targets, FinancialPlanning.Strategy.MINIMUM_ONLY, 12);
      assertEquals(4, fixed.payoff().months());
      var rolling = FinancialPlanning.allocatedPayoff(debts, "USD", bd("60"), targets, FinancialPlanning.Strategy.AVALANCHE, 12);
      assertEquals(3, rolling.payoff().months());
      var excessive = FinancialPlanning.allocatedPayoff(debts, "USD", bd("50"), targets, FinancialPlanning.Strategy.MINIMUM_ONLY, 12);
      assertFalse(excessive.payoff().feasible());
      assertTrue(excessive.problems().stream().anyMatch(x -> x.reason().contains("budget")));
   }



   @Test
   void explicitMonthlyBudgetChangesAreAppliedInsteadOfFlattened()
   {
      var debts = List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("10")));
      var budgets = List.of(new FinancialPlanning.PaymentTarget(1, bd("20")), new FinancialPlanning.PaymentTarget(2, bd("60")));
      var result = FinancialPlanning.payoff(debts, "USD", budgets, FinancialPlanning.Strategy.AVALANCHE, 12);
      assertTrue(result.paidOff());
      assertEquals(List.of(bd("20.00"), bd("60.00"), bd("20.00")), result.schedule().stream().map(FinancialPlanning.Month::payment).toList());
      var targets = java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("30"))));
      var explicit = FinancialPlanning.allocatedPayoff(debts, "USD", budgets, targets, FinancialPlanning.Strategy.MINIMUM_ONLY, 12);
      assertFalse(explicit.payoff().feasible());
      assertTrue(explicit.problems().getFirst().reason().contains("budget"));
      assertEquals(bd("20.00"), explicit.payoff().shortfall().availableBudget());
   }



   @Test
   void incompleteOrAmbiguousAllocationSchedulesCannotInventPayments()
   {
      var debts = List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("10")));
      assertThrows(IllegalArgumentException.class, () -> FinancialPlanning.allocatedPayoff(debts, "USD", bd("20"), java.util.Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 12));
      assertThrows(IllegalArgumentException.class, () -> FinancialPlanning.allocatedPayoff(debts, "USD", bd("20"),
         java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(2, bd("20")))), FinancialPlanning.Strategy.MINIMUM_ONLY, 12));
      assertThrows(IllegalArgumentException.class, () -> FinancialPlanning.allocatedPayoff(debts, "USD", bd("20"),
         java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("20")), new FinancialPlanning.PaymentTarget(1, bd("30")))), FinancialPlanning.Strategy.MINIMUM_ONLY, 12));
      assertThrows(IllegalArgumentException.class, () -> FinancialPlanning.allocatedPayoff(debts, "USD", bd("20"),
         java.util.Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("0.001")))), FinancialPlanning.Strategy.MINIMUM_ONLY, 12));
      var zeroBudget = FinancialPlanning.payoff(debts, "USD", List.of(new FinancialPlanning.PaymentTarget(1, bd("0"))), FinancialPlanning.Strategy.AVALANCHE, 12);
      assertFalse(zeroBudget.feasible());
      assertTrue(zeroBudget.schedule().isEmpty());
   }



   private static BigDecimal bd(String value)
   {
      return new BigDecimal(value);
   }
}
