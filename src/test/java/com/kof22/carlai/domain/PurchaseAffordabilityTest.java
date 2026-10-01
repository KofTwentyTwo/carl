
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PurchaseAffordabilityTest
{
   private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 10, 31);
   private static final PurchaseAffordability.Conditions COMPLETE = new PurchaseAffordability.Conditions(true, true, true, true, true, true, true);

   @Test
   void billsDebtPlansAndReserveConstrainKitchenFurnitureBudget()
   {
      var events = List.of(event("rent", 2, "-500"), event("debt-plan", 4, "-200"), event("income", 20, "1200"));
      var result = PurchaseAffordability.budget("USD", decimal("1200"), decimal("300"), FROM, THROUGH, FROM.plusDays(5),
         decimal("600"), events, COMPLETE);
      assertEquals(decimal("200.00"), result.supportedCashBudget());
      assertEquals(PurchaseAffordability.Classification.WITHIN_CASH_PLAN, PurchaseAffordability.classify(result, decimal("180")));
      assertEquals(PurchaseAffordability.Classification.EXCEEDS_CASH_PLAN, PurchaseAffordability.classify(result, decimal("250")));
   }



   @Test
   void positiveMonthEndCashDoesNotHideAnEarlierReserveShortfall()
   {
      var result = PurchaseAffordability.budget("USD", decimal("100"), decimal("50"), FROM, THROUGH, FROM,
         decimal("5000"), List.of(event("bill", 2, "-200"), event("income", 20, "2000")), COMPLETE);
      assertTrue(result.baseline().closingCash().signum() > 0);
      assertEquals(decimal("0.00"), result.supportedCashBudget());
      assertEquals(PurchaseAffordability.Classification.EXCEEDS_CASH_PLAN, PurchaseAffordability.classify(result, decimal("1")));
   }



   @Test
   void unresolvedOrPartialScopeFactsYieldUndeterminedBudget()
   {
      var incomplete = new PurchaseAffordability.Conditions(false, true, false, true, true, true, true);
      var result = PurchaseAffordability.budget("USD", decimal("5000"), decimal("300"), FROM, THROUGH, FROM,
         decimal("1000"), List.of(), incomplete);
      assertEquals(null, result.supportedCashBudget());
      assertEquals(PurchaseAffordability.Classification.UNDETERMINED, PurchaseAffordability.classify(result, decimal("100")));
      assertTrue(result.limitations().stream().anyMatch(x -> x.contains("scope")));
   }



   @Test
   void sameDayPaydayOrderingIsNotAssumedAndCreditDoesNotEnterCapacity()
   {
      var result = PurchaseAffordability.budget("USD", decimal("100"), decimal("100"), FROM, THROUGH, FROM,
         decimal("10000"), List.of(event("income", 1, "2000")), COMPLETE);
      assertEquals(decimal("0.00"), result.supportedCashBudget());
      var nextDay = PurchaseAffordability.budget("USD", decimal("100"), decimal("100"), FROM, THROUGH, FROM.plusDays(1),
         decimal("500"), List.of(event("income", 1, "2000")), COMPLETE);
      assertEquals(decimal("500.00"), nextDay.supportedCashBudget());
      assertTrue(nextDay.limitations().stream().anyMatch(x -> x.contains("Credit")));
   }



   private static CashFlow.Event event(String id, int day, String amount)
   {
      return new CashFlow.Event(id, LocalDate.of(2026, 10, day), "USD", decimal(amount));
   }



   private static BigDecimal decimal(String value)
   {
      return new BigDecimal(value);
   }
}
