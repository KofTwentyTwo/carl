/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PortfolioRestructuringTest
{
   private static final LocalDate ASOF = LocalDate.of(2026, 9, 1);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static FinancialPlanning.Debt debt(String id, String balance, String rate)
   {
      return new FinancialPlanning.Debt(id, "USD", n(balance), n(rate), n("20.00"));
   }



   private static PortfolioRestructuring.Policy policy(DebtPortfolio.Mode mode)
   {
      return new PortfolioRestructuring.Policy(mode, Map.of(), FinancialPlanning.Strategy.AVALANCHE);
   }



   private static PortfolioRestructuring.Move move(String id, String principal, String fee, String cashFee, int term, FinancingScenarios.Promotion promotion, List<FinancialPlanning.Rate> rates, Map<String, BigDecimal> allocations)
   {
      var offer = new FinancingScenarios.Offer(id, "USD", n(principal), n(fee), n(cashFee), n("25.00"), term, rates, promotion, FinancingScenarios.Evidence.HYPOTHETICAL);
      return new PortfolioRestructuring.Move(offer, allocations, n("10000.00"), n("25.00"), BigDecimal.ZERO, BigDecimal.ZERO, ASOF.plusDays(14), "Synthetic minimum and allocation assumptions");
   }



   private static PortfolioRestructuring.Inputs inputs(List<FinancialPlanning.Debt> debts, String budget, int horizon)
   {
      var dates = new java.util.HashMap<String, LocalDate>();
      for(var debt : debts)
      {
         dates.put(debt.id(), ASOF.plusDays(14));
      }
      return new PortfolioRestructuring.Inputs("USD", ASOF, debts, dates, List.of(new FinancialPlanning.PaymentTarget(1, n(budget))), horizon, "Synthetic post-essential/reserve budget");
   }



   @Test
   void partialTransferKeepsRetainedDebtsAndFeesInWholePortfolio()
   {
      var input = inputs(List.of(debt("card", "500.00", "0.24"), debt("other", "500.00", "0.12")), "300.00", 12);
      var transfer = move("transfer", "200.00", "6.00", "10.00", 12, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("200.00")));
      var result = PortfolioRestructuring.project(input, List.of(transfer), policy(DebtPortfolio.Mode.AVALANCHE));
      assertEquals(n("1000.00"), result.originalPrincipal());
      assertEquals(n("1006.00"), result.debtAfterFinancedFees());
      assertEquals(n("6.00"), result.financedFees());
      assertEquals(n("10.00"), result.upfrontCashFees());
      assertEquals(n("11.00"), result.months().getFirst().interest());
      assertEquals(n("717.00"), result.months().getFirst().remainingDebt());
      assertTrue(result.paidOff());
      assertEquals(result, PortfolioRestructuring.project(input, List.of(transfer), policy(DebtPortfolio.Mode.AVALANCHE)));
   }



   @Test
   void multipleTransfersCannotDuplicateSourcePrincipal()
   {
      var input = inputs(List.of(debt("card", "500.00", "0.24")), "100.00", 12);
      var first = move("first", "300.00", "0.00", "0.00", 12, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("300.00")));
      var over = move("second", "300.00", "0.00", "0.00", 12, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("300.00")));
      assertThrows(IllegalArgumentException.class, () -> PortfolioRestructuring.project(input, List.of(first, over), policy(DebtPortfolio.Mode.SNOWBALL)));
      var valid = move("second", "200.00", "0.00", "0.00", 12, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("200.00")));
      assertEquals(n("500.00"), PortfolioRestructuring.project(input, List.of(first, valid), policy(DebtPortfolio.Mode.SNOWBALL)).debtAfterFinancedFees());
   }



   @Test
   void deferredInterestIsChargedOnlyForUnclearedPromotionalBalance()
   {
      var rates = List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(3, n("0.24")));
      var transfer = move("deferred", "100.00", "0.00", "0.00", 4, new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 2, n("0.24"), true), rates, Map.of("card", n("100.00")));
      var unpaid = PortfolioRestructuring.project(inputs(List.of(debt("card", "100.00", "0.24")), "25.00", 2), List.of(transfer), policy(DebtPortfolio.Mode.MINIMUM_ONLY));
      assertEquals(n("3.50"), unpaid.interest());
      assertEquals(n("53.50"), unpaid.remainingBalances().get("deferred"));
      assertFalse(unpaid.paidOff());
      var cleared = PortfolioRestructuring.project(inputs(List.of(debt("card", "100.00", "0.24")), "50.00", 2), List.of(transfer), policy(DebtPortfolio.Mode.AVALANCHE));
      assertTrue(cleared.paidOff());
      assertEquals(n("0.00"), cleared.interest());
   }



   @Test
   void unknownDeferredTermsAndMaturityResidualRemainIncomplete()
   {
      var input = inputs(List.of(debt("card", "100.00", "0.24")), "25.00", 6);
      var unknown = move("unknown", "100.00", "0.00", "0.00", 4, new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.DEFERRED_INTEREST, 1, n("0.24"), false), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(2, n("0.24"))), Map.of("card", n("100.00")));
      var incomplete = PortfolioRestructuring.project(input, List.of(unknown), policy(DebtPortfolio.Mode.MINIMUM_ONLY));
      assertFalse(incomplete.determined());
      assertTrue(incomplete.months().isEmpty());
      var maturity = move("maturity", "100.00", "0.00", "0.00", 2, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("100.00")));
      var residual = PortfolioRestructuring.project(input, List.of(maturity), policy(DebtPortfolio.Mode.MINIMUM_ONLY));
      assertFalse(residual.feasible());
      assertEquals(n("50.00"), residual.maturityResiduals().get("maturity"));
      assertEquals(2, residual.months().size());
   }



   @Test
   void datedRatesFeesAndBudgetsChangeTogether()
   {
      var original = new FinancialPlanning.Debt("card", "USD", n("100.00"), n("20.00"), BigDecimal.ZERO,
         List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(2, n("0.24"))), BigDecimal.ZERO);
      var input = new PortfolioRestructuring.Inputs("USD", ASOF, List.of(original), Map.of("card", LocalDate.of(2026, 9, 30)),
         List.of(new FinancialPlanning.PaymentTarget(1, n("25.00")), new FinancialPlanning.PaymentTarget(2, n("50.00"))), 3,
         Map.of("card", List.of(new FinancialPlanning.PaymentTarget(1, BigDecimal.ZERO), new FinancialPlanning.PaymentTarget(2, n("5.00")))), "Synthetic changing terms");
      var result = PortfolioRestructuring.project(input, List.of(), policy(DebtPortfolio.Mode.AVALANCHE));
      assertEquals(n("75.00"), result.months().getFirst().remainingDebt());
      assertEquals(n("1.50"), result.months().get(1).interest());
      assertEquals(n("5.00"), result.months().get(1).monthlyFees());
      assertEquals(n("31.50"), result.months().get(1).remainingDebt());
      assertEquals(LocalDate.of(2026, 11, 30), result.months().get(2).debts().getFirst().paymentDate());
      assertTrue(result.paidOff());
   }



   @Test
   void currentPaymentsKeepUnusedBudgetUntilExplicitReallocation()
   {
      var input = inputs(List.of(debt("card", "100.00", "0.00")), "100.00", 2);
      var targets = Map.of("card", List.of(new FinancialPlanning.PaymentTarget(1, n("25.00"))));
      var current = new PortfolioRestructuring.Policy(DebtPortfolio.Mode.CURRENT_PAYMENT, targets, FinancialPlanning.Strategy.MINIMUM_ONLY);
      var proposed = new PortfolioRestructuring.Policy(DebtPortfolio.Mode.USER_DIRECTED, targets, FinancialPlanning.Strategy.AVALANCHE);
      var unchanged = PortfolioRestructuring.project(input, List.of(), current);
      assertFalse(unchanged.paidOff());
      assertEquals(n("50.00"), unchanged.remainingBalances().get("card"));
      assertTrue(PortfolioRestructuring.project(input, List.of(), proposed).paidOff());
      var invalid = new PortfolioRestructuring.Policy(DebtPortfolio.Mode.CURRENT_PAYMENT, targets, FinancialPlanning.Strategy.AVALANCHE);
      assertThrows(IllegalArgumentException.class, () -> PortfolioRestructuring.project(input, List.of(), invalid));
   }



   @Test
   void upfrontFeesCanBreachReservesBeforeMonthlyPayments()
   {
      var input = inputs(List.of(debt("card", "100.00", "0.00")), "50.00", 2);
      var transfer = move("transfer", "100.00", "0.00", "30.00", 4, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("100.00")));
      var result = PortfolioRestructuring.project(input, List.of(transfer), policy(DebtPortfolio.Mode.AVALANCHE));
      var window = new DebtPortfolio.CashWindow(n("120.00"), n("100.00"), ASOF, ASOF.plusMonths(2), List.of());
      var check = PortfolioRestructuring.checkLiquidity(result, window);
      assertEquals(DebtPortfolio.LiquidityStatus.BREACHED, check.status());
      assertEquals(ASOF, check.cash().firstReserveShortfall());
      var shortWindow = new DebtPortfolio.CashWindow(n("500.00"), n("100.00"), ASOF, ASOF.plusDays(1), List.of());
      assertEquals(DebtPortfolio.LiquidityStatus.UNDETERMINED, PortfolioRestructuring.checkLiquidity(result, shortWindow).status());
   }



   @Test
   void comparisonShowsCashReliefAndActualEstimatedPayoffDates()
   {
      var input = inputs(List.of(debt("card", "100.00", "0.00")), "100.00", 4);
      var unchanged = new PortfolioRestructuring.Policy(DebtPortfolio.Mode.CURRENT_PAYMENT,
         Map.of("card", List.of(new FinancialPlanning.PaymentTarget(1, n("25.00")))), FinancialPlanning.Strategy.MINIMUM_ONLY);
      var baseline = PortfolioRestructuring.project(input, List.of(), unchanged);
      var candidate = PortfolioRestructuring.project(input, List.of(), policy(DebtPortfolio.Mode.AVALANCHE));
      assertEquals(ASOF.plusDays(14).plusMonths(3), baseline.payoffDate());
      assertEquals(ASOF.plusDays(14), candidate.payoffDates().get("card"));
      var differences = PortfolioRestructuring.compare(baseline, candidate).cashDifferences();
      assertEquals(n("-75.00"), differences.getFirst().releasedCash());
      assertEquals(n("25.00"), differences.get(1).releasedCash());
      assertEquals(n("0.00"), differences.getLast().cumulativeReleasedCash());
   }



   @Test
   void trueZeroExpiryDoesNotInventDeferredChargesOrLifetimeSavings()
   {
      var rates = List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(3, n("0.24")));
      var transfer = move("zero", "100.00", "0.00", "0.00", 6,
         new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 2, BigDecimal.ZERO, true), rates, Map.of("card", n("100.00")));
      var input = inputs(List.of(debt("card", "100.00", "0.24")), "25.00", 3);
      var candidate = PortfolioRestructuring.project(input, List.of(transfer), policy(DebtPortfolio.Mode.MINIMUM_ONLY));
      assertEquals(n("1.00"), candidate.interest());
      assertEquals(n("26.00"), candidate.remainingBalances().get("zero"));
      assertEquals(n("0.00"), candidate.months().get(1).debts().stream().filter(d -> d.id().equals("zero")).findFirst().orElseThrow().deferredInterestCharged());
      assertNull(candidate.payoffDate());
      var baseline = PortfolioRestructuring.project(input, List.of(), policy(DebtPortfolio.Mode.MINIMUM_ONLY));
      assertFalse(PortfolioRestructuring.compare(baseline, candidate).fullPayoffCostsCompared());
      var maturity = move("short", "100.00", "0.00", "0.00", 2, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("100.00")));
      var stopped = PortfolioRestructuring.project(input, List.of(maturity), policy(DebtPortfolio.Mode.MINIMUM_ONLY));
      assertNull(PortfolioRestructuring.compare(baseline, stopped).candidateMinusBaselineCost());
      assertTrue(PortfolioRestructuring.compare(baseline, stopped).cashDifferences().isEmpty());
   }



   @Test
   void shortfallAndComparableCostsRetainStartingInputs()
   {
      var bad = PortfolioRestructuring.project(inputs(List.of(debt("card", "100.00", "0.24")), "10.00", 6), List.of(), policy(DebtPortfolio.Mode.AVALANCHE));
      assertFalse(bad.feasible());
      assertTrue(bad.months().isEmpty());
      var input = inputs(List.of(debt("card", "100.00", "0.24")), "50.00", 6);
      var baseline = PortfolioRestructuring.project(input, List.of(), policy(DebtPortfolio.Mode.AVALANCHE));
      var transfer = move("zero", "100.00", "0.00", "2.00", 6, FinancingScenarios.Promotion.none(), List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), Map.of("card", n("100.00")));
      var candidate = PortfolioRestructuring.project(input, List.of(transfer), policy(DebtPortfolio.Mode.AVALANCHE));
      var comparison = PortfolioRestructuring.compare(baseline, candidate);
      assertTrue(comparison.fullPayoffCostsCompared());
      assertTrue(comparison.candidateMinusBaselineCost().signum() < 0);
      assertEquals(1, comparison.breakEvenMonth());
      var different = PortfolioRestructuring.project(inputs(List.of(debt("card", "100.00", "0.24")), "60.00", 6), List.of(transfer), policy(DebtPortfolio.Mode.AVALANCHE));
      assertThrows(IllegalArgumentException.class, () -> PortfolioRestructuring.compare(baseline, different));
   }
}
