
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


/*******************************************************************************
 ** Educational scenarios under explicitly supplied uncertain monthly returns.
 ** No security selection, suitability decision, tax calculation or execution.
 ******************************************************************************/
public final class InvestmentPlanning
{
   /*******************************************************************************
    ** Values are confirmed owner context only when the trusted caller establishes it.
    ** Stage selection is explicit; balances or debt thresholds do not select it.
    ******************************************************************************/
   public record Context(boolean ownerSelectedInvestmentStage, Integer horizonMonths,
      String riskTolerance, BigDecimal cashReserve, boolean taxContextSupplied,
      boolean holdingsComplete, boolean costsComplete, String sourceReference)
   {
   }



   /** Explicit hypothetical contribution at the end of one month. */
   public record Contribution(String id, YearMonth month, BigDecimal amount)
   {
   }



   /*******************************************************************************
    ** A return is an assumption, never a promised yield. Contributions occur after
    ** monthly return and asset/fixed fees; initial fees reduce starting capital.
    ******************************************************************************/
   public record Assumption(String id, BigDecimal assumedMonthlyReturn, BigDecimal monthlyAssetFeeRate,
      BigDecimal initialFee, BigDecimal fixedMonthlyFee, String sourceReference)
   {
   }



   /** Monthly assumed return, costs, contribution and resulting hypothetical value. */
   public record Month(YearMonth month, BigDecimal openingValue, BigDecimal assumedReturn,
      BigDecimal afterReturnBeforeFees, BigDecimal fees, BigDecimal contribution, BigDecimal endingValue)
   {
   }



   /** Educational scenario with saved assumptions, requested horizon and unresolved context. */
   public record Projection(String currency, String assumptionId, YearMonth firstMonth, int requestedMonths,
      Assumption assumptions, Context ownerContext, BigDecimal endingHypotheticalValue,
      BigDecimal contributions, BigDecimal fees, BigDecimal assumedNetGrowth, List<Month> months,
      String projectionStatus, String recommendationStatus, boolean ownerSelectedInvestmentStage,
      List<String> gaps, List<String> limitations)
   {
      /** Retains immutable schedule, gaps and limitations. */
      public Projection
      {
         months = List.copyOf(months);
         gaps = List.copyOf(gaps);
         limitations = List.copyOf(limitations);
      }
   }

   private static final BigDecimal MAX_VALUE = new BigDecimal("100000000000000");
   private static final List<String> LIMITATIONS = List.of(
      "Returns are uncertain assumptions; real investments can lose value and the paths are not forecasts or promised savings.",
      "Monthly return is supplied directly; no annual-to-monthly conversion, inflation, tax or issuer-specific rules are inferred.",
      "Contributions are hypothetical and need a separate dated debt/expense/reserve cash-flow check before being called affordable.",
      "Ending value is not guaranteed liquid spending capacity; withdrawal, tax, market and account restrictions are not modeled.",
      "Educational discussion only: no product recommendation, professional approval, order or transfer.");

   private InvestmentPlanning()
   {
   }



   /*******************************************************************************
    ** Produces deterministic arithmetic only. Applicable sources, owner facts and
    ** evaluation remain necessary for any personalized recommendation.
    ******************************************************************************/
   public static Projection project(String currency, YearMonth firstMonth, int count, BigDecimal initialCapital,
      List<Contribution> contributions, Assumption assumption, Context context)
   {
      if(currency == null || firstMonth == null || firstMonth.getYear() < 1900 || firstMonth.getYear() > 2200
         || count < 1 || count > 600 || contributions == null || contributions.size() > 20_000
         || assumption == null || !id(assumption.id()) || !id(assumption.sourceReference())
         || context == null || !id(context.sourceReference()))
      {
         throw new IllegalArgumentException("Explicit currency, bounded horizon, assumptions and owner context provenance are required");
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported currency precision");
      }
      initialCapital = money(initialCapital, scale);
      BigDecimal initialFee = money(assumption.initialFee(), scale);
      BigDecimal fixedFee = money(assumption.fixedMonthlyFee(), scale);
      rate(assumption.assumedMonthlyReturn(), BigDecimal.ONE.negate(), BigDecimal.ONE);
      rate(assumption.monthlyAssetFeeRate(), BigDecimal.ZERO, new BigDecimal("0.25"));
      if(context.horizonMonths() != null && (context.horizonMonths() < 1 || context.horizonMonths() > 600))
      {
         throw new IllegalArgumentException("Confirmed owner horizon must be bounded");
      }
      if(context.riskTolerance() != null && !id(context.riskTolerance()))
      {
         throw new IllegalArgumentException("Risk context is invalid");
      }
      if(context.cashReserve() != null)
      {
         money(context.cashReserve(), scale);
      }
      var gaps = new ArrayList<String>();
      if(!context.ownerSelectedInvestmentStage())
      {
         gaps.add("Owner has not selected investment-stage readiness; do not infer it from a balance or debt threshold");
      }
      if(context.horizonMonths() == null)
      {
         gaps.add("Owner investment horizon is unconfirmed");
      }
      else if(context.horizonMonths() != count)
      {
         gaps.add("Scenario horizon differs from the confirmed owner horizon");
      }
      if(context.riskTolerance() == null)
      {
         gaps.add("Owner risk tolerance is unconfirmed");
      }
      if(context.cashReserve() == null)
      {
         gaps.add("Owner liquidity/reserve needs are unconfirmed");
      }
      if(!context.taxContextSupplied())
      {
         gaps.add("Applicable owner tax context is incomplete");
      }
      if(!context.holdingsComplete())
      {
         gaps.add("Existing holdings and account restrictions are incomplete");
      }
      if(!context.costsComplete())
      {
         gaps.add("Applicable investment costs and terms are incomplete");
      }
      var ids = new HashSet<String>();
      var byMonth = new HashMap<YearMonth, BigDecimal>();
      YearMonth lastMonth = firstMonth.plusMonths(count - 1L);
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      for(var contribution : contributions)
      {
         if(contribution == null || !id(contribution.id()) || !ids.add(contribution.id()) || contribution.month() == null
            || contribution.month().isBefore(firstMonth) || contribution.month().isAfter(lastMonth))
         {
            throw new IllegalArgumentException("Contributions require unique IDs and an in-horizon month");
         }
         BigDecimal total = byMonth.merge(contribution.month(), money(contribution.amount(), scale), BigDecimal::add);
         if(total.compareTo(MAX_VALUE) > 0)
         {
            throw new IllegalArgumentException("Monthly contribution total exceeds the configured bound");
         }
      }
      if(initialFee.compareTo(initialCapital) > 0)
      {
         return new Projection(currency, assumption.id(), firstMonth, count, assumption, context, initialCapital, zero, zero, zero, List.of(),
            "INSUFFICIENT_FOR_ASSUMED_FEES", "EDUCATIONAL_ONLY", context.ownerSelectedInvestmentStage(), gaps, LIMITATIONS);
      }
      BigDecimal value = initialCapital.subtract(initialFee);
      BigDecimal totalFees = initialFee;
      BigDecimal totalContributions = zero;
      var months = new ArrayList<Month>();
      String status = "PROJECTED_UNDER_ASSUMPTIONS";
      for(int offset = 0; offset < count; offset++)
      {
         YearMonth month = firstMonth.plusMonths(offset);
         BigDecimal growth = value.multiply(assumption.assumedMonthlyReturn()).setScale(scale, RoundingMode.HALF_EVEN);
         BigDecimal afterReturn = value.add(growth);
         BigDecimal fee = afterReturn.multiply(assumption.monthlyAssetFeeRate()).setScale(scale, RoundingMode.HALF_EVEN).add(fixedFee);
         if(fee.compareTo(afterReturn) > 0)
         {
            status = "INSUFFICIENT_FOR_ASSUMED_FEES";
            break;
         }
         BigDecimal contribution = byMonth.getOrDefault(month, zero);
         BigDecimal ending = afterReturn.subtract(fee).add(contribution);
         if(ending.compareTo(MAX_VALUE) > 0)
         {
            status = "PROJECTION_VALUE_BOUND_REACHED";
            break;
         }
         months.add(new Month(month, value, growth, afterReturn, fee, contribution, ending));
         value = ending;
         totalFees = totalFees.add(fee);
         totalContributions = totalContributions.add(contribution);
      }
      return new Projection(currency, assumption.id(), firstMonth, count, assumption, context, value, totalContributions, totalFees,
         value.subtract(initialCapital).subtract(totalContributions), months, status, "EDUCATIONAL_ONLY", context.ownerSelectedInvestmentStage(), gaps, LIMITATIONS);
   }



   private static boolean id(String value)
   {
      return value != null && !value.isBlank() && value.length() <= 200;
   }



   private static void rate(BigDecimal rate, BigDecimal minimum, BigDecimal maximum)
   {
      if(rate == null || rate.precision() > 18 || Math.abs((long) rate.scale()) > 12 || rate.compareTo(minimum) < 0 || rate.compareTo(maximum) > 0)
      {
         throw new IllegalArgumentException("An explicit bounded monthly assumption rate is required");
      }
   }



   private static BigDecimal money(BigDecimal value, int scale)
   {
      if(value == null || value.signum() < 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18 || value.compareTo(MAX_VALUE) > 0)
      {
         throw new IllegalArgumentException("An explicit bounded nonnegative monetary amount is required");
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
