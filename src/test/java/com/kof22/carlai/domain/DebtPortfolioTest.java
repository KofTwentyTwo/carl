
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


class DebtPortfolioTest
{
   private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);
   private static final List<FinancialPlanning.PaymentTarget> BUDGET = List.of(new FinancialPlanning.PaymentTarget(1, bd("60")));

   @Test
   void actualPaymentBaselineAndSnowballShowDatesAndCashReleased()
   {
      var debts = debts();
      var dates = Map.of("a", LocalDate.of(2026, 10, 15), "b", LocalDate.of(2026, 10, 20));
      var targets = Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("20"))),
         "b", List.of(new FinancialPlanning.PaymentTarget(1, bd("40"))));
      var current = DebtPortfolio.solve(debts, "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.CURRENT_PAYMENT,
         targets, FinancialPlanning.Strategy.MINIMUM_ONLY, 6);
      var snowball = DebtPortfolio.solve(debts, "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.SNOWBALL,
         Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 6);
      assertEquals(LocalDate.of(2027, 2, 15), current.payoffDate());
      assertEquals(LocalDate.of(2026, 12, 15), snowball.payoffDate());
      assertEquals(FinancialPlanning.Strategy.SNOWBALL, snowball.rollover());
      assertEquals(LocalDate.of(2026, 11, 20), current.debtPayoffDates().get("b"));
      var comparison = DebtPortfolio.compare(current, snowball);
      assertEquals(bd("-30.00"), comparison.cashDifferences().get(1).releasedCash());
      assertEquals(bd("20.00"), comparison.cashDifferences().get(3).releasedCash());
      assertEquals(bd("0.00"), comparison.cashDifferences().getLast().cumulativeReleasedCash());
      assertTrue(comparison.fullPayoffCostsCompared());
   }



   @Test
   void datedDebtPaymentsCannotSpendCashBelowTheSelectedReserve()
   {
      var dates = Map.of("a", LocalDate.of(2026, 10, 15), "b", LocalDate.of(2026, 10, 20));
      var plan = DebtPortfolio.solve(debts(), "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.AVALANCHE,
         Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 3);
      var cash = new DebtPortfolio.CashWindow(bd("100"), bd("50"), AS_OF, LocalDate.of(2026, 12, 31),
         List.of(new CashFlow.Event("salary", LocalDate.of(2026, 10, 30), "USD", bd("1000"))));
      var check = DebtPortfolio.checkLiquidity(plan, cash);
      assertTrue(check.coverageComplete());
      assertFalse(check.reservesPreserved());
      assertEquals(LocalDate.of(2026, 10, 20), check.cash().firstReserveShortfall());
      assertTrue(check.cash().closingCash().signum() > 0);
   }



   @Test
   void incompleteCashHorizonCannotProveFullPlanLiquidity()
   {
      var plan = DebtPortfolio.solve(debts(), "USD", AS_OF, Map.of("a", AS_OF.plusDays(14), "b", AS_OF.plusDays(19)),
         BUDGET, DebtPortfolio.Mode.MINIMUM_ONLY, Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 12);
      var cash = new DebtPortfolio.CashWindow(bd("1000"), bd("50"), AS_OF, LocalDate.of(2026, 10, 31), List.of());
      assertFalse(DebtPortfolio.checkLiquidity(plan, cash).coverageComplete());
      assertTrue(plan.payoff().paidOff());
   }



   @Test
   void inconsistentStartingInputsCannotBePresentedAsComparableStrategies()
   {
      var dates = Map.of("a", AS_OF.plusDays(14), "b", AS_OF.plusDays(19));
      var a = DebtPortfolio.solve(debts(), "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.AVALANCHE, Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 3);
      var b = DebtPortfolio.solve(debts(), "USD", AS_OF, dates, List.of(new FinancialPlanning.PaymentTarget(1, bd("70"))),
         DebtPortfolio.Mode.SNOWBALL, Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 3);
      assertThrows(IllegalArgumentException.class, () -> DebtPortfolio.compare(a, b));
      assertEquals(a, DebtPortfolio.solve(debts().reversed(), "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.AVALANCHE, Map.of(), FinancialPlanning.Strategy.MINIMUM_ONLY, 3));
   }



   @Test
   void userDirectedPolicyIsDistinctAndDoesNotHideMinimumFailures()
   {
      var dates = Map.of("a", AS_OF.plusDays(14), "b", AS_OF.plusDays(19));
      var targets = Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("20"))),
         "b", List.of(new FinancialPlanning.PaymentTarget(1, bd("40"))));
      var directed = DebtPortfolio.solve(debts(), "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.USER_DIRECTED,
         targets, FinancialPlanning.Strategy.AVALANCHE, 6);
      assertEquals(DebtPortfolio.Mode.USER_DIRECTED, directed.mode());
      assertEquals(3, directed.payoff().months());
      var invalid = Map.of("a", List.of(new FinancialPlanning.PaymentTarget(1, bd("0"))),
         "b", List.of(new FinancialPlanning.PaymentTarget(1, bd("40"))));
      var failed = DebtPortfolio.solve(debts(), "USD", AS_OF, dates, BUDGET, DebtPortfolio.Mode.USER_DIRECTED,
         invalid, FinancialPlanning.Strategy.MINIMUM_ONLY, 6);
      assertFalse(failed.payoff().feasible());
      assertTrue(failed.payments().isEmpty());
      assertEquals(null, failed.payoffDate());
      assertThrows(IllegalArgumentException.class, () -> DebtPortfolio.compare(directed, failed));
   }



   private static List<FinancialPlanning.Debt> debts()
   {
      return List.of(new FinancialPlanning.Debt("a", "USD", bd("100"), bd("0"), bd("10")),
         new FinancialPlanning.Debt("b", "USD", bd("50"), bd("0"), bd("10")));
   }



   private static BigDecimal bd(String value)
   {
      return new BigDecimal(value);
   }
}
