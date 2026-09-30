/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


/***************************************************************************
 ** Dated cash movement over an explicitly scoped, reconciled set of accounts.
 ** These totals are cash inflows/outflows, not taxable income or expenses.
 ** Date-only inputs establish daily closing cash, not intraday availability.
 ***************************************************************************/
public final class CashFlow
{
   /*******************************************************************************
    * A dated signed cash movement whose currency and permission scope were verified by the caller.
    ******************************************************************************/
   public record Event(String id, LocalDate date, String currency, BigDecimal amount)
   {
   }



   /*******************************************************************************
    * Deterministic daily totals and ending cash, without inferred intraday ordering.
    ******************************************************************************/
   public record Day(LocalDate date, BigDecimal inflows, BigDecimal outflows,
      BigDecimal closingCash, boolean belowReserve)
   {
   }



   /*******************************************************************************
    * An immutable bounded cash timeline with explicit reserve shortfalls.
    ******************************************************************************/
   public record Projection(BigDecimal closingCash, BigDecimal minimumCash, BigDecimal inflows,
      BigDecimal outflows, LocalDate firstReserveShortfall, boolean intradayTimingUnknown, List<Day> days)
   {
      /*******************************************************************************
       * An immutable bounded cash timeline with explicit reserve shortfalls.
       ******************************************************************************/
      public Projection
      {
         days = List.copyOf(days);
      }
   }

   private CashFlow()
   {
   }



   /*******************************************************************************
    * Projects dated cash movements against an explicit reserve without treating available credit as cash.
    ******************************************************************************/
   public static Projection project(String currency, BigDecimal openingCash, BigDecimal reserve,
      LocalDate from, LocalDate through, List<Event> events)
   {
      if(currency == null || from == null || through == null || from.isAfter(through)
         || ChronoUnit.DAYS.between(from, through) >= 366 || events == null || events.size() > 100_000)
      {
         throw new IllegalArgumentException("Currency, an inclusive 1–366 day window and bounded cash events are required");
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported currency precision");
      }
      openingCash = money(openingCash, scale);
      reserve = money(reserve, scale);
      if(reserve.signum() < 0)
      {
         throw new IllegalArgumentException("Cash reserve must be nonnegative");
      }
      var ids = new HashSet<String>();
      var inflows = new HashMap<LocalDate, BigDecimal>();
      var outflows = new HashMap<LocalDate, BigDecimal>();
      for(var event : events)
      {
         if(event == null || event.id() == null || event.id().isBlank() || event.id().length() > 200
            || !ids.add(event.id()) || !currency.equals(event.currency()) || event.date() == null
            || event.date().isBefore(from) || event.date().isAfter(through))
         {
            throw new IllegalArgumentException("Cash events need unique IDs, one currency and dates within the requested window");
         }
         BigDecimal amount = money(event.amount(), scale);
         if(amount.signum() >= 0)
         {
            inflows.merge(event.date(), amount, BigDecimal::add);
         }
         else
         {
            outflows.merge(event.date(), amount.negate(), BigDecimal::add);
         }
      }
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      BigDecimal cash = openingCash;
      BigDecimal minimumCash = openingCash;
      BigDecimal totalIn = zero;
      BigDecimal totalOut = zero;
      LocalDate firstShortfall = cash.compareTo(reserve) < 0 ? from : null;
      var days = new ArrayList<Day>();
      long count = ChronoUnit.DAYS.between(from, through) + 1;
      for(int offset = 0; offset < count; offset++)
      {
         LocalDate day = from.plusDays(offset);
         BigDecimal in = inflows.getOrDefault(day, zero);
         BigDecimal out = outflows.getOrDefault(day, zero);
         cash = cash.add(in).subtract(out);
         minimumCash = minimumCash.min(cash);
         totalIn = totalIn.add(in);
         totalOut = totalOut.add(out);
         boolean below = cash.compareTo(reserve) < 0;
         if(below && firstShortfall == null)
         {
            firstShortfall = day;
         }
         days.add(new Day(day, in, out, cash, below));
      }
      return new Projection(cash, minimumCash, totalIn, totalOut, firstShortfall, true, days);
   }



   private static BigDecimal money(BigDecimal value, int scale)
   {
      if(value == null || value.precision() > 18 || Math.abs((long) value.scale()) > 18
         || value.abs().compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("An explicit bounded monetary value is required");
      }
      try
      {
         return value.setScale(scale, RoundingMode.UNNECESSARY);
      }
      catch(ArithmeticException invalidPrecision)
      {
         throw new IllegalArgumentException("Cash amount exceeds currency precision", invalidPrecision);
      }
   }
}
