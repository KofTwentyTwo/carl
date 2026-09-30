/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CashFlowTest
{
   @Test
   void exposesEarlyCashShortfallDespitePositiveMonthEndCash()
   {
      var result = CashFlow.project("USD", bd("200"), bd("100"), date("2026-01-01"), date("2026-01-31"),
         List.of(new CashFlow.Event("bill", date("2026-01-03"), "USD", bd("-250")),
            new CashFlow.Event("pay", date("2026-01-15"), "USD", bd("1000"))));
      assertEquals(bd("950.00"), result.closingCash());
      assertEquals(bd("-50.00"), result.minimumCash());
      assertEquals(date("2026-01-03"), result.firstReserveShortfall());
      assertEquals(bd("1000.00"), result.inflows());
      assertEquals(bd("250.00"), result.outflows());
      assertEquals(31, result.days().size());
   }



   @Test
   void dailyTotalsAreOrderIndependentAndZeroIsDifferentFromMissing()
   {
      var events = List.of(new CashFlow.Event("a", date("2026-01-01"), "USD", bd("-50")),
         new CashFlow.Event("b", date("2026-01-01"), "USD", bd("50")));
      var result = CashFlow.project("USD", bd("0"), bd("0"), date("2026-01-01"), date("2026-01-01"), events);
      assertEquals(result, CashFlow.project("USD", bd("0"), bd("0"), date("2026-01-01"), date("2026-01-01"), events.reversed()));
      assertEquals(bd("0.00"), result.closingCash());
      assertTrue(result.intradayTimingUnknown());
      assertThrows(IllegalArgumentException.class, () -> CashFlow.project("USD", null, bd("0"), date("2026-01-01"), date("2026-01-01"), events));
   }



   @Test
   void rejectsMixedCurrenciesDuplicateEventsAndEventsOutsideExplicitWindow()
   {
      var event = new CashFlow.Event("same", date("2026-01-01"), "USD", bd("1"));
      assertThrows(IllegalArgumentException.class, () -> CashFlow.project("USD", bd("0"), bd("0"), date("2026-01-01"), date("2026-01-31"), List.of(event, event)));
      assertThrows(IllegalArgumentException.class, () -> CashFlow.project("EUR", bd("0"), bd("0"), date("2026-01-01"), date("2026-01-31"), List.of(event)));
      assertThrows(IllegalArgumentException.class, () -> CashFlow.project("USD", bd("0"), bd("0"), date("2026-01-02"), date("2026-01-31"), List.of(event)));
      assertThrows(IllegalArgumentException.class, () -> CashFlow.project("USD", bd("0"), bd("0"), date("2026-01-01"), date("2029-01-01"), List.of()));
   }



   private static BigDecimal bd(String value)
   {
      return new BigDecimal(value);
   }



   private static LocalDate date(String value)
   {
      return LocalDate.parse(value);
   }
}
