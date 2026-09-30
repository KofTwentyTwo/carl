
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class InvestmentPlanningTest
{
   private static final YearMonth START = YearMonth.of(2027, 1);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static InvestmentPlanning.Context unknown()
   {
      return new InvestmentPlanning.Context(false, null, null, null, false, false, false, "synthetic-context");
   }



   private static InvestmentPlanning.Assumption assumption(String monthlyReturn)
   {
      return new InvestmentPlanning.Assumption("hypothesis", n(monthlyReturn), n("0"), n("0.00"), n("0.00"), "explicit-assumption");
   }



   @Test
   void zeroReturnIsOnlyCapitalPlusExplicitContributions()
   {
      var result = InvestmentPlanning.project("USD", START, 3, n("1000.00"),
         List.of(new InvestmentPlanning.Contribution("one", START, n("100.00")), new InvestmentPlanning.Contribution("two", START.plusMonths(1), n("50.00"))), assumption("0"), unknown());
      assertEquals(n("1150.00"), result.endingHypotheticalValue());
      assertEquals(n("150.00"), result.contributions());
      assertEquals(n("0.00"), result.assumedNetGrowth());
      assertFalse(result.ownerSelectedInvestmentStage());
      assertEquals("EDUCATIONAL_ONLY", result.recommendationStatus());
      assertTrue(result.gaps().stream().anyMatch(s -> s.contains("risk")));
   }



   @Test
   void endOfMonthContributionsAndSuppliedFeesHaveDeterministicOrder()
   {
      var terms = new InvestmentPlanning.Assumption("fee-case", n("0.01"), n("0.001"), n("10.00"), n("2.00"), "synthetic-fees");
      var result = InvestmentPlanning.project("USD", START, 1, n("1000.00"), List.of(new InvestmentPlanning.Contribution("saving", START, n("100.00"))), terms, unknown());
      assertEquals(n("999.90"), result.months().getFirst().afterReturnBeforeFees());
      assertEquals(n("1096.90"), result.endingHypotheticalValue());
      assertEquals(n("13.00"), result.fees());
      assertEquals(n("-3.10"), result.assumedNetGrowth());
   }



   @Test
   void negativeReturnsCanLoseMoneyAndDoNotBecomeKnownSavings()
   {
      var result = InvestmentPlanning.project("USD", START, 2, n("1000.00"), List.of(), assumption("-0.10"), unknown());
      assertEquals(n("810.00"), result.endingHypotheticalValue());
      assertEquals(n("-190.00"), result.assumedNetGrowth());
      assertTrue(result.limitations().stream().anyMatch(s -> s.contains("uncertain")));
      assertTrue(result.limitations().stream().anyMatch(s -> s.contains("tax")));
   }



   @Test
   void completeContextAndSelectedStageStillDoNotCreateAProductRecommendation()
   {
      var context = new InvestmentPlanning.Context(true, 12, "owner-stated risk preference", n("500.00"), true, true, true, "confirmed-context");
      var result = InvestmentPlanning.project("USD", START, 12, n("1000.00"), List.of(), assumption("0"), context);
      assertTrue(result.ownerSelectedInvestmentStage());
      assertTrue(result.gaps().isEmpty());
      assertEquals("EDUCATIONAL_ONLY", result.recommendationStatus());
   }



   @Test
   void feesCannotInventNegativeInvestmentsAndInputBoundsAreEnforced()
   {
      var tooCostly = new InvestmentPlanning.Assumption("fees", n("0"), n("0"), n("0.00"), n("20.00"), "fee-assumption");
      var result = InvestmentPlanning.project("USD", START, 12, n("10.00"), List.of(), tooCostly, unknown());
      assertEquals("INSUFFICIENT_FOR_ASSUMED_FEES", result.projectionStatus());
      assertEquals(12, result.requestedMonths());
      assertEquals(tooCostly, result.assumptions());
      assertTrue(result.months().isEmpty());
      assertEquals(n("10.00"), result.endingHypotheticalValue());
      assertThrows(IllegalArgumentException.class, () -> InvestmentPlanning.project("USD", START, 601, n("1000.00"), List.of(), assumption("0"), unknown()));
      var contribution = new InvestmentPlanning.Contribution("same", START, n("10.00"));
      assertThrows(IllegalArgumentException.class, () -> InvestmentPlanning.project("USD", START, 12, n("1000.00"), List.of(contribution, contribution), assumption("0"), unknown()));
   }
}
