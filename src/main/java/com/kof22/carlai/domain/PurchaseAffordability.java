
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;


/***************************************************************************
 ** Cash-funded discretionary capacity under an explicitly agreed plan.
 ** Callers must supply authorized, reconciled sources and all selected cash
 ** commitments, including required debt progress, essential costs and reserves.
 ** Credit limits and unapproved loan proceeds are deliberately not inputs.
 ***************************************************************************/
public final class PurchaseAffordability
{
   /** Required evidence conditions for a supported cash-purchase budget. */
   public record Conditions(boolean balancesFreshAndResolved, boolean obligationsCovered, boolean scopeComplete,
      boolean allInCostsKnown, boolean reserveConfirmed, boolean selectedPlansIncluded, boolean incomeEvidenceSufficient)
   {
   }



   /** Distinguishes supported cash affordability from insufficient evidence. */
   public enum Classification
   {
      WITHIN_CASH_PLAN, EXCEEDS_CASH_PLAN, UNDETERMINED
   }



   /** Dated cash limit and baseline projection with explicit coverage limitations. */
   public record Budget(String currency, LocalDate purchaseDate, BigDecimal supportedCashBudget, CashFlow.Projection baseline, List<String> limitations)
   {
      /** Copies limitations retained with the budget assessment. */
      public Budget
      {
         limitations = List.copyOf(limitations);
      }
   }

   private PurchaseAffordability()
   {
   }



   /** Bounds spending by dated cash, reserves, selected commitments and the agreed goal cap. */
   public static Budget budget(String currency, BigDecimal openingCash, BigDecimal reserve, LocalDate from, LocalDate through,
      LocalDate purchaseDate, BigDecimal agreedDiscretionaryCap, List<CashFlow.Event> commitments, Conditions conditions)
   {
      if(currency == null || from == null || through == null || purchaseDate == null || conditions == null
         || purchaseDate.isBefore(from) || purchaseDate.isAfter(through))
      {
         throw new IllegalArgumentException("Explicit currency, forecast dates, purchase date and evidence conditions are required");
      }
      var limitations = new ArrayList<String>();
      if(!conditions.balancesFreshAndResolved())
      {
         limitations.add("Balances are stale, unresolved or unverified.");
      }
      if(!conditions.obligationsCovered())
      {
         limitations.add("Essential and debt obligation coverage is incomplete.");
      }
      if(!conditions.scopeComplete())
      {
         limitations.add("Accessible scope does not establish the requested household budget.");
      }
      if(!conditions.allInCostsKnown())
      {
         limitations.add("All-in purchase costs, including applicable fees, are incomplete.");
      }
      if(!conditions.reserveConfirmed())
      {
         limitations.add("The household reserve floor has not been explicitly selected.");
      }
      if(!conditions.selectedPlansIncluded())
      {
         limitations.add("Selected plans or existing purchase commitments are missing.");
      }
      if(!conditions.incomeEvidenceSufficient())
      {
         limitations.add("Forecast income lacks sufficient explicit evidence.");
      }
      if(!limitations.isEmpty())
      {
         return new Budget(currency, purchaseDate, null, null, limitations);
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported currency precision");
      }
      BigDecimal cap = money(agreedDiscretionaryCap, scale);
      var baseline = CashFlow.project(currency, openingCash, reserve, from, through, commitments);
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      BigDecimal capacity = zero;
      if(baseline.firstReserveShortfall() == null)
      {
         BigDecimal minimumAfterPurchase = null;
         BigDecimal previousCash = openingCash;
         for(var day : baseline.days())
         {
            if(day.date().equals(purchaseDate))
            {
               // Date-only evidence cannot promise that same-day income arrives first.
               minimumAfterPurchase = previousCash.min(day.closingCash());
            }
            else if(day.date().isAfter(purchaseDate))
            {
               minimumAfterPurchase = minimumAfterPurchase.min(day.closingCash());
            }
            previousCash = day.closingCash();
         }
         capacity = minimumAfterPurchase.subtract(reserve).max(zero).min(cap).setScale(scale, RoundingMode.UNNECESSARY);
      }
      else
      {
         limitations.add("The baseline plan already breaches its reserve floor; resolve the shortfall before adding discretionary spending.");
      }
      limitations.add("Credit availability is borrowing capacity and cannot increase this cash budget.");
      limitations.add("Capacity covers the stated forecast window only; later obligations and intraday ordering require separate evidence.");
      return new Budget(currency, purchaseDate, capacity, baseline, limitations);
   }



   /** Compares the known all-in purchase price against a supported cash budget. */
   public static Classification classify(Budget budget, BigDecimal allInPrice)
   {
      if(budget == null)
      {
         throw new IllegalArgumentException("A current scoped purchase budget is required");
      }
      BigDecimal price = money(allInPrice, Currency.getInstance(budget.currency()).getDefaultFractionDigits());
      if(budget.supportedCashBudget() == null)
      {
         return Classification.UNDETERMINED;
      }
      return price.compareTo(budget.supportedCashBudget()) <= 0 ? Classification.WITHIN_CASH_PLAN : Classification.EXCEEDS_CASH_PLAN;
   }



   private static BigDecimal money(BigDecimal value, int scale)
   {
      if(value == null || value.signum() < 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18
         || value.compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("A bounded nonnegative monetary value is required");
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
