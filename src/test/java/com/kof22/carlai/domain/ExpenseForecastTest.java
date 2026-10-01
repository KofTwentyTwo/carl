
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExpenseForecastTest
{
   private static final LocalDate JAN = LocalDate.of(2027, 1, 1);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static ExpenseForecast.Obligation monthly(String id, String amount, ExpenseForecast.Kind kind)
   {
      return new ExpenseForecast.Obligation(id, "USD", ExpenseForecast.Cadence.MONTHLY, JAN.withDayOfMonth(15), null,
         amount == null ? null : n(amount), Map.of(1, n("200.00"), 2, n("150.00")), kind, ExpenseForecast.Basis.ESTIMATED, "synthetic-source");
   }



   @Test
   void seasonalUtilitiesAndIrregularRepairKeepReserveSeparate()
   {
      var power = monthly("power", "100.00", ExpenseForecast.Kind.EXPENSE);
      var repair = new ExpenseForecast.Obligation("repair", "USD", ExpenseForecast.Cadence.ONCE, JAN.plusDays(20), null,
         n("300.00"), Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.HYPOTHETICAL, "repair-assumption");
      var reserve = new ExpenseForecast.Obligation("reserve", "USD", ExpenseForecast.Cadence.MONTHLY, JAN, null,
         n("50.00"), Map.of(), ExpenseForecast.Kind.RESERVE_EARMARK, ExpenseForecast.Basis.COMMITTED, "reserve-policy");
      var result = ExpenseForecast.project("USD", JAN, JAN.plusMonths(3).minusDays(1), JAN.minusDays(1), List.of(power, repair, reserve), List.of(), List.of());
      assertEquals(n("750.00"), result.knownScheduledExpenses());
      assertEquals(n("150.00"), result.remainingReserveEarmarks());
      assertEquals(n("750.00"), result.cashEvents().stream().map(e -> e.amount().negate()).reduce(n("0.00"), BigDecimal::add));
      assertEquals(ExpenseForecast.Basis.HYPOTHETICAL, result.occurrences().stream().filter(o -> o.id().startsWith("repair@")).findFirst().orElseThrow().basis());
   }



   @Test
   void explicitSettlementCountsBillAndPaymentOnceIncludingAdvancePayment()
   {
      var power = monthly("power", "100.00", ExpenseForecast.Kind.EXPENSE);
      var paid = new ExpenseForecast.Actual("payment", JAN.plusDays(9), "USD", n("120.00"), ExpenseForecast.Kind.EXPENSE, "bank-row");
      var result = ExpenseForecast.project("USD", JAN, JAN.plusMonths(1).minusDays(1), JAN.plusDays(10), List.of(power), List.of(paid),
         List.of(new ExpenseForecast.Settlement("power@2027-01-15", "payment", n("120.00"))));
      assertEquals(n("120.00"), result.actualExpenses());
      assertEquals(n("80.00"), result.remainingExpenses());
      assertEquals(n("200.00"), result.cashEvents().stream().map(e -> e.amount().negate()).reduce(n("0.00"), BigDecimal::add));
   }



   @Test
   void priorPeriodSettlementReducesFutureBillWithoutRepeatingOpeningCashPayment()
   {
      var power = monthly("power", "100.00", ExpenseForecast.Kind.EXPENSE);
      var paid = new ExpenseForecast.Actual("advance", JAN.minusDays(1), "USD", n("200.00"), ExpenseForecast.Kind.EXPENSE, "prior-bank-row");
      var result = ExpenseForecast.project("USD", JAN, JAN.plusMonths(1).minusDays(1), JAN, List.of(power), List.of(paid),
         List.of(new ExpenseForecast.Settlement("power@2027-01-15", "advance", n("200.00"))));
      assertEquals(n("0.00"), result.actualExpenses());
      assertEquals(n("0.00"), result.remainingExpenses());
      assertTrue(result.cashEvents().isEmpty());
   }



   @Test
   void missingAmountsAndOverdueTimingPreventCompleteCashForecast()
   {
      var missing = new ExpenseForecast.Obligation("gas", "USD", ExpenseForecast.Cadence.ONCE, JAN.plusDays(2), null,
         null, Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.COMMITTED, "missing-invoice");
      var overdue = new ExpenseForecast.Obligation("water", "USD", ExpenseForecast.Cadence.ONCE, JAN.plusDays(1), null,
         n("40.00"), Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.COMMITTED, "water-invoice");
      var result = ExpenseForecast.project("USD", JAN, JAN.plusDays(31), JAN.plusDays(10), List.of(missing, overdue), List.of(), List.of());
      assertFalse(result.cashCoverageComplete());
      assertNull(result.occurrences().get(1).expectedAmount());
      assertTrue(result.exceptions().stream().anyMatch(s -> s.contains("gas@2027-01-03")));
      assertTrue(result.exceptions().stream().anyMatch(s -> s.contains("water@2027-01-02")));
      assertTrue(result.cashEvents().isEmpty());
   }



   @Test
   void monthEndAnchorsDoNotDriftAndOldSchedulesAreBounded()
   {
      var item = new ExpenseForecast.Obligation("old", "USD", ExpenseForecast.Cadence.MONTHLY, LocalDate.of(1900, 1, 31), null,
         n("10.00"), Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.COMMITTED, "old-schedule");
      var result = ExpenseForecast.project("USD", JAN, LocalDate.of(2027, 3, 31), JAN.minusDays(1), List.of(item), List.of(), List.of());
      assertEquals(List.of(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)), result.occurrences().stream().map(ExpenseForecast.Occurrence::due).toList());
   }



   @Test
   void rejectsOverSettlementFutureActualDuplicateAndCrossCurrency()
   {
      var power = monthly("power", "100.00", ExpenseForecast.Kind.EXPENSE);
      var paid = new ExpenseForecast.Actual("payment", JAN, "USD", n("201.00"), ExpenseForecast.Kind.EXPENSE, "bank-row");
      assertThrows(IllegalArgumentException.class, () -> ExpenseForecast.project("USD", JAN, JAN.plusDays(31), JAN, List.of(power), List.of(paid),
         List.of(new ExpenseForecast.Settlement("power@2027-01-15", "payment", n("201.00")))));
      assertThrows(IllegalArgumentException.class, () -> ExpenseForecast.project("USD", JAN, JAN.plusDays(31), JAN.minusDays(1), List.of(power), List.of(paid), List.of()));
      assertThrows(IllegalArgumentException.class, () -> ExpenseForecast.project("USD", JAN, JAN.plusDays(31), JAN, List.of(power, power), List.of(), List.of()));
      assertThrows(IllegalArgumentException.class, () -> ExpenseForecast.project("EUR", JAN, JAN.plusDays(31), JAN, List.of(power), List.of(), List.of()));
   }



   @Test
   void settledReserveTransferIsAnEarmarkAndNotAnExpense()
   {
      var reserve = new ExpenseForecast.Obligation("reserve", "USD", ExpenseForecast.Cadence.ONCE, JAN, null,
         n("75.00"), Map.of(), ExpenseForecast.Kind.RESERVE_EARMARK, ExpenseForecast.Basis.COMMITTED, "reserve-policy");
      var actual = new ExpenseForecast.Actual("transfer", JAN, "USD", n("75.00"), ExpenseForecast.Kind.RESERVE_EARMARK, "transfer-pair");
      var result = ExpenseForecast.project("USD", JAN, JAN.plusDays(30), JAN, List.of(reserve), List.of(actual),
         List.of(new ExpenseForecast.Settlement("reserve@2027-01-01", "transfer", n("75.00"))));
      assertEquals(n("75.00"), result.actualReserveEarmarks());
      assertEquals(n("0.00"), result.remainingReserveEarmarks());
      assertEquals(n("0.00"), result.actualExpenses());
      assertEquals(n("0.00"), result.knownScheduledExpenses());
      assertTrue(result.cashEvents().isEmpty());
   }



   @Test
   void cashEventIdsRemainUsableByTheDatedCashCalculator()
   {
      String longestId = "x".repeat(150);
      var item = new ExpenseForecast.Obligation(longestId, "USD", ExpenseForecast.Cadence.ONCE, JAN, null,
         n("1.00"), Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.COMMITTED, "invoice");
      var result = ExpenseForecast.project("USD", JAN, JAN, JAN, List.of(item), List.of(), List.of());
      assertEquals(n("9.00"), CashFlow.project("USD", n("10.00"), n("0.00"), JAN, JAN, result.cashEvents()).closingCash());
   }
}
