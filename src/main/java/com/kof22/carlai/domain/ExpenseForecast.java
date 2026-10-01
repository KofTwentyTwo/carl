
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** Recurring obligations and explicit settlements within an authorized input scope.
 ** Reserve earmarks are not expenses or net household cash outflows.
 ******************************************************************************/
public final class ExpenseForecast
{
   /** Separates actual cash expenses from reserve earmarks. */
   public enum Kind
   {
      EXPENSE, RESERVE_EARMARK
   }



   /** Explicit certainty of the supplied obligation evidence. */
   public enum Basis
   {
      COMMITTED, ESTIMATED, HYPOTHETICAL
   }



   /** Supported bounded recurrence intervals. */
   public enum Cadence
   {
      ONCE, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, ANNUAL
   }



   /** Evidenced recurring obligation with explicit seasonality and amount uncertainty. */
   public record Obligation(String id, String currency, Cadence cadence, LocalDate firstDue,
      LocalDate lastDue, BigDecimal baseAmount, Map<Integer, BigDecimal> seasonalAmounts,
      Kind kind, Basis basis, String source)
   {
      /** Retains immutable validated input collections. */
      public Obligation
      {
         seasonalAmounts = Map.copyOf(seasonalAmounts);
      }
   }



   /** One evidenced paid cash movement. */
   public record Actual(String id, LocalDate paid, String currency, BigDecimal amount, Kind kind, String source)
   {
   }



   /** Explicit allocation of an actual payment to an obligation occurrence. */
   public record Settlement(String occurrenceId, String actualId, BigDecimal amount)
   {
   }



   /** Expected and settled amounts for one scheduled due date. */
   public record Occurrence(String id, LocalDate due, Kind kind, Basis basis, BigDecimal expectedAmount,
      BigDecimal appliedAmount, BigDecimal remainingAmount, String source)
   {
   }



   /** Bounded cash events, reserve earmarks and explicit coverage gaps. */
   public record Projection(List<Occurrence> occurrences, List<CashFlow.Event> cashEvents,
      BigDecimal knownScheduledExpenses, BigDecimal actualExpenses, BigDecimal remainingExpenses,
      BigDecimal actualReserveEarmarks, BigDecimal remainingReserveEarmarks,
      boolean cashCoverageComplete, List<String> exceptions)
   {
      /** Retains immutable validated input collections. */
      public Projection
      {
         occurrences = List.copyOf(occurrences);
         cashEvents = List.copyOf(cashEvents);
         exceptions = List.copyOf(exceptions);
      }
   }

   private ExpenseForecast()
   {
   }



   /*******************************************************************************
    ** Amounts and settlement identities must already be permission-filtered by the caller.
    ** Completeness describes these supplied facts, not coverage of the whole household.
    ******************************************************************************/
   public static Projection project(String currency, LocalDate from, LocalDate through, LocalDate asOf,
      List<Obligation> obligations, List<Actual> actuals, List<Settlement> settlements)
   {
      if(currency == null || from == null || through == null || asOf == null || from.isAfter(through)
         || ChronoUnit.DAYS.between(from, through) >= 366 || obligations == null || obligations.size() > 1000
         || actuals == null || actuals.size() > 100_000 || settlements == null || settlements.size() > 100_000)
      {
         throw new IllegalArgumentException("Explicit currency, dates, a 1–366 day window and bounded inputs are required");
      }
      validDate(from);
      validDate(through);
      validDate(asOf);
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported currency precision");
      }
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      var seeds = new HashMap<String, Occurrence>();
      var ids = new HashSet<String>();
      for(var item : obligations)
      {
         if(item == null || !id(item.id()) || item.id().length() > 150 || !ids.add(item.id()) || !currency.equals(item.currency())
            || item.cadence() == null || item.kind() == null || item.basis() == null || !id(item.source()))
         {
            throw new IllegalArgumentException("Schedules require unique IDs, one currency, type and source");
         }
         validDate(item.firstDue());
         if(item.lastDue() != null)
         {
            validDate(item.lastDue());
            if(item.lastDue().isBefore(item.firstDue()))
            {
               throw new IllegalArgumentException("Schedule end precedes first occurrence");
            }
         }
         if(item.baseAmount() != null)
         {
            money(item.baseAmount(), scale);
         }
         for(var seasonal : item.seasonalAmounts().entrySet())
         {
            if(seasonal.getKey() < 1 || seasonal.getKey() > 12)
            {
               throw new IllegalArgumentException("Seasonal amounts require calendar month numbers");
            }
            money(seasonal.getValue(), scale);
         }
         long index = initialIndex(item, from);
         for(int count = 0; count < 368; count++, index++)
         {
            LocalDate date = due(item, index);
            if(date == null || date.isAfter(through) || item.lastDue() != null && date.isAfter(item.lastDue()))
            {
               break;
            }
            if(date.isBefore(from))
            {
               continue;
            }
            BigDecimal amount = item.seasonalAmounts().getOrDefault(date.getMonthValue(), item.baseAmount());
            if(amount != null)
            {
               amount = money(amount, scale);
            }
            String occurrenceId = item.id() + "@" + date;
            seeds.put(occurrenceId, new Occurrence(occurrenceId, date, item.kind(), item.basis(), amount, zero, amount, item.source()));
            if(seeds.size() > 20_000)
            {
               throw new IllegalArgumentException("Too many expense occurrences");
            }
         }
      }
      var payments = new HashMap<String, Actual>();
      for(var actual : actuals)
      {
         if(actual == null || !id(actual.id()) || actual.id().length() > 150 || payments.putIfAbsent(actual.id(), actual) != null
            || !currency.equals(actual.currency()) || actual.kind() == null || !id(actual.source()))
         {
            throw new IllegalArgumentException("Actuals require unique IDs, one currency, type and source");
         }
         validDate(actual.paid());
         if(actual.paid().isAfter(asOf))
         {
            throw new IllegalArgumentException("Future payments are assumptions, not actuals");
         }
         money(actual.amount(), scale);
      }
      var appliedByActual = new HashMap<String, BigDecimal>();
      var appliedByOccurrence = new HashMap<String, BigDecimal>();
      var links = new HashSet<List<String>>();
      for(var link : settlements)
      {
         if(link == null || !id(link.occurrenceId()) || !id(link.actualId())
            || !links.add(List.of(link.occurrenceId(), link.actualId())))
         {
            throw new IllegalArgumentException("Settlement links must be explicit and unique");
         }
         Occurrence occurrence = seeds.get(link.occurrenceId());
         Actual actual = payments.get(link.actualId());
         BigDecimal amount = money(link.amount(), scale);
         if(occurrence == null || actual == null || occurrence.expectedAmount() == null
            || occurrence.kind() != actual.kind() || amount.signum() <= 0)
         {
            throw new IllegalArgumentException("Settlement requires a known in-window obligation and compatible actual");
         }
         BigDecimal byActual = appliedByActual.merge(link.actualId(), amount, BigDecimal::add);
         BigDecimal byOccurrence = appliedByOccurrence.merge(link.occurrenceId(), amount, BigDecimal::add);
         if(byActual.compareTo(actual.amount()) > 0 || byOccurrence.compareTo(occurrence.expectedAmount()) > 0)
         {
            throw new IllegalArgumentException("Settlement exceeds the actual payment or expected obligation");
         }
      }
      var occurrences = new ArrayList<Occurrence>();
      var cash = new ArrayList<CashFlow.Event>();
      var exceptions = new ArrayList<String>();
      BigDecimal scheduled = zero;
      BigDecimal remaining = zero;
      BigDecimal reserves = zero;
      for(var seed : seeds.values().stream().sorted(Comparator.comparing(Occurrence::due).thenComparing(Occurrence::id)).toList())
      {
         BigDecimal applied = appliedByOccurrence.getOrDefault(seed.id(), zero);
         BigDecimal unpaid = seed.expectedAmount() == null ? null : seed.expectedAmount().subtract(applied);
         occurrences.add(new Occurrence(seed.id(), seed.due(), seed.kind(), seed.basis(), seed.expectedAmount(), applied, unpaid, seed.source()));
         if(unpaid == null)
         {
            exceptions.add("Missing amount: " + seed.id());
            continue;
         }
         if(seed.kind() == Kind.RESERVE_EARMARK)
         {
            reserves = reserves.add(unpaid);
            continue;
         }
         scheduled = scheduled.add(seed.expectedAmount());
         remaining = remaining.add(unpaid);
         if(unpaid.signum() > 0)
         {
            if(seed.due().isBefore(asOf))
            {
               exceptions.add("Unpaid overdue expense; payment date unknown: " + seed.id());
            }
            else
            {
               cash.add(new CashFlow.Event("obligation:" + seed.id(), seed.due(), currency, unpaid.negate()));
            }
         }
      }
      BigDecimal actualExpenses = zero;
      BigDecimal actualReserves = zero;
      for(var actual : payments.values().stream().sorted(Comparator.comparing(Actual::paid).thenComparing(Actual::id)).toList())
      {
         if(actual.paid().isBefore(from) || actual.paid().isAfter(through))
         {
            continue;
         }
         if(actual.kind() == Kind.RESERVE_EARMARK)
         {
            actualReserves = actualReserves.add(actual.amount());
         }
         else
         {
            actualExpenses = actualExpenses.add(actual.amount());
            cash.add(new CashFlow.Event("actual:" + actual.id(), actual.paid(), currency, actual.amount().negate()));
         }
      }
      cash.sort(Comparator.comparing(CashFlow.Event::date).thenComparing(CashFlow.Event::id));
      return new Projection(occurrences, cash, scheduled, actualExpenses, remaining, actualReserves, reserves, exceptions.isEmpty(), exceptions);
   }



   private static long initialIndex(Obligation item, LocalDate from)
   {
      long days = ChronoUnit.DAYS.between(item.firstDue(), from);
      long months = ChronoUnit.MONTHS.between(item.firstDue().withDayOfMonth(1), from.withDayOfMonth(1));
      return Math.max(0, switch(item.cadence())
      {
         case ONCE -> 0;
         case WEEKLY -> days / 7;
         case BIWEEKLY -> days / 14;
         case MONTHLY -> months;
         case QUARTERLY -> months / 3;
         case ANNUAL -> months / 12;
      });
   }



   private static LocalDate due(Obligation item, long index)
   {
      return switch(item.cadence())
      {
         case ONCE -> index == 0 ? item.firstDue() : null;
         case WEEKLY -> item.firstDue().plusWeeks(index);
         case BIWEEKLY -> item.firstDue().plusWeeks(index * 2);
         case MONTHLY -> item.firstDue().plusMonths(index);
         case QUARTERLY -> item.firstDue().plusMonths(index * 3);
         case ANNUAL -> item.firstDue().plusYears(index);
      };
   }



   private static void validDate(LocalDate value)
   {
      if(value == null || value.getYear() < 1900 || value.getYear() > 2300)
      {
         throw new IllegalArgumentException("An explicit bounded date is required");
      }
   }



   private static boolean id(String value)
   {
      return value != null && !value.isBlank() && value.length() <= 200;
   }



   private static BigDecimal money(BigDecimal value, int scale)
   {
      if(value == null || value.signum() < 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18
         || value.compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("An explicit bounded nonnegative amount is required");
      }
      try
      {
         return value.setScale(scale, RoundingMode.UNNECESSARY);
      }
      catch(ArithmeticException invalid)
      {
         throw new IllegalArgumentException("Amount exceeds currency precision", invalid);
      }
   }
}
