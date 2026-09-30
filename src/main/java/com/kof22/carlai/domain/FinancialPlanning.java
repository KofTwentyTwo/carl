/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/***************************************************************************
 ** Deterministic monthly estimates. Callers supply authorized, dated inputs
 ** and a payment budget after essential spending and the chosen cash reserve.
 ** This model does not calculate an issuer payoff quote or execute payments.
 ***************************************************************************/
public final class FinancialPlanning
{
   /*******************************************************************************
    * Supported payment priorities under the same stated scenario constraints.
    ******************************************************************************/
   public enum Strategy
   {
      AVALANCHE, SNOWBALL, MINIMUM_ONLY
   }



   /***************************************************************************
    ** Decimal APR effective from a one-based projection month, inclusive.
    ***************************************************************************/
   public record Rate(int firstMonth, BigDecimal annualRate)
   {
      /*******************************************************************************
       * An effective-dated decimal APR segment with an explicit monthly fee.
       ******************************************************************************/
      public Rate
      {
         if(firstMonth < 1 || firstMonth > 600 || annualRate == null
            || annualRate.signum() < 0 || annualRate.compareTo(BigDecimal.valueOf(5)) > 0
            || annualRate.precision() > 12 || annualRate.scale() < 0 || annualRate.scale() > 12)
         {
            throw new IllegalArgumentException("APR must be a bounded decimal from 0 to 5, effective in month 1–600");
         }
      }
   }



   /***************************************************************************
    ** Minimum is max(fixed floor, fraction of balance after monthly charges),
    ** capped at the amount owed. These explicit scenario terms are assumptions
    ** until matched to evidence of the actual contract.
    ***************************************************************************/
   public record Debt(String id, String currency, BigDecimal balance, BigDecimal minimum,
      BigDecimal minimumFraction, List<Rate> rates, BigDecimal monthlyFee)
   {
      /*******************************************************************************
       * Validates supplied debt terms or constructs the documented fixed-rate compatibility scenario.
       ******************************************************************************/
      public Debt(String id, String currency, BigDecimal balance, BigDecimal annualRate, BigDecimal minimum)
      {
         this(id, currency, balance, minimum, BigDecimal.ZERO, List.of(new Rate(1, annualRate)), BigDecimal.ZERO);
      }



      /*******************************************************************************
       * Validates supplied debt terms or constructs the documented fixed-rate compatibility scenario.
       ******************************************************************************/
      public Debt
      {
         int scale = scale(currency);
         if(id == null || id.isBlank() || id.length() > 200 || minimumFraction == null
            || minimumFraction.signum() < 0 || minimumFraction.compareTo(BigDecimal.ONE) > 0
            || minimumFraction.precision() > 12 || Math.abs((long) minimumFraction.scale()) > 12)
         {
            throw new IllegalArgumentException("Debt needs an identity and a minimum fraction between 0 and 1");
         }
         balance = money(balance, scale);
         minimum = money(minimum, scale);
         monthlyFee = money(monthlyFee, scale);
         if(minimum.signum() == 0 && minimumFraction.signum() == 0)
         {
            throw new IllegalArgumentException("An explicit positive minimum floor or fraction is required");
         }
         if(rates == null || rates.isEmpty() || rates.size() > 600 || rates.stream().anyMatch(rate -> rate == null))
         {
            throw new IllegalArgumentException("An explicit bounded APR schedule is required");
         }
         rates = rates.stream().sorted(Comparator.comparingInt(Rate::firstMonth)).toList();
         if(rates.getFirst().firstMonth() != 1)
         {
            throw new IllegalArgumentException("APR schedule must cover the first projection month");
         }
         var months = new HashSet<Integer>();
         for(var rate : rates)
         {
            if(!months.add(rate.firstMonth()))
            {
               throw new IllegalArgumentException("APR effective months must be unique");
            }
         }
      }



      /*******************************************************************************
       * Returns the initial explicit APR; later rate segments remain part of the scenario.
       ******************************************************************************/
      public BigDecimal annualRate()
      {
         return rates.getFirst().annualRate();
      }



      private BigDecimal rateAt(int month)
      {
         BigDecimal result = annualRate();
         for(var rate : rates)
         {
            if(rate.firstMonth() > month)
            {
               break;
            }
            result = rate.annualRate();
         }
         return result;
      }
   }



   /*******************************************************************************
    * Separates each debt opening balance, accrued charges, payment and ending balance.
    ******************************************************************************/
   public record DebtMonth(String id, BigDecimal openingBalance, BigDecimal interest, BigDecimal fee,
      BigDecimal requiredMinimum, BigDecimal payment, BigDecimal closingBalance, boolean negativeAmortization)
   {
   }



   /*******************************************************************************
    * An immutable monthly result with the contributing debt-level calculations.
    ******************************************************************************/
   public record Month(int number, BigDecimal payment, BigDecimal interest, BigDecimal balance,
      BigDecimal fees, BigDecimal unusedBudget, List<String> priority, List<DebtMonth> debts)
   {
      /*******************************************************************************
       * An immutable monthly result with the contributing debt-level calculations.
       ******************************************************************************/
      public Month
      {
         priority = List.copyOf(priority);
         debts = List.copyOf(debts);
      }
   }



   /***************************************************************************
    ** No payment allocation is proposed for this infeasible month. Schedule
    ** and totals contain only preceding, fully allocated projection months.
    ***************************************************************************/
   public record Shortfall(int month, BigDecimal availableBudget, BigDecimal requiredMinimum)
   {
   }



   /*******************************************************************************
    * An immutable scenario result retaining infeasibility and nonconvergence limitations.
    ******************************************************************************/
   public record Payoff(boolean feasible, boolean paidOff, int months, BigDecimal interest,
      List<String> priority, List<Month> schedule, String assumptions, BigDecimal fees,
      Shortfall shortfall, boolean negativeAmortization)
   {
      /*******************************************************************************
       * An immutable scenario result retaining infeasibility and nonconvergence limitations.
       ******************************************************************************/
      public Payoff
      {
         priority = List.copyOf(priority);
         schedule = List.copyOf(schedule);
      }
   }



   /***************************************************************************
    ** Projected rental cash, not actual receipts or taxable income.
    ***************************************************************************/
   public record Rental(BigDecimal netOperatingIncome, BigDecimal cashAfterDebtAndReserves)
   {
   }



   /***************************************************************************
    ** Explicit end-of-month payment target effective from an inclusive month.
    ***************************************************************************/
   public record PaymentTarget(int firstMonth, BigDecimal amount)
   {
      /** Validates a finite, nonnegative monthly target. */
      public PaymentTarget
      {
         if(firstMonth < 1 || firstMonth > 600 || amount == null || amount.signum() < 0 || amount.precision() > 18
            || Math.abs((long) amount.scale()) > 18 || amount.compareTo(new BigDecimal("100000000000000")) > 0)
         {
            throw new IllegalArgumentException("Payment target requires a bounded month and nonnegative amount");
         }
      }
   }



   /***************************************************************************
    ** A proposed allocation is infeasible, without silently replacing it.
    ***************************************************************************/
   public record AllocationProblem(int month, String debtId, String reason, BigDecimal proposed, BigDecimal required)
   {
   }



   /***************************************************************************
    ** The shared payoff engine plus explicit payment-policy violations.
    ***************************************************************************/
   public record AllocationResult(Payoff payoff, List<AllocationProblem> problems)
   {
      /** Copies policy violations so the result cannot be changed by its caller. */
      public AllocationResult
      {
         problems = List.copyOf(problems);
      }
   }

   private FinancialPlanning()
   {
   }



   /*******************************************************************************
    * Runs a bounded monthly estimate with explicit rounding, rate schedules and payment policy.
    ******************************************************************************/
   public static Payoff payoff(List<Debt> debts, String currency, BigDecimal monthlyBudget,
      Strategy strategy, int maximumMonths)
   {
      return project(debts, currency, monthlyBudget, strategy, maximumMonths, null, null).payoff();
   }



   /***************************************************************************
    ** Explicit targets share the same charges/minimum rules. Selected extra
    ** priority may roll unused cash forward; MINIMUM_ONLY preserves it instead.
    ***************************************************************************/
   public static AllocationResult allocatedPayoff(List<Debt> debts, String currency, BigDecimal monthlyBudget,
      Map<String, List<PaymentTarget>> targets, Strategy extraPriority, int maximumMonths)
   {
      if(targets == null)
      {
         throw new IllegalArgumentException("Explicit per-debt payment targets are required");
      }
      return project(debts, currency, monthlyBudget, extraPriority, maximumMonths, targets, null);
   }



   /***************************************************************************
    ** Uses an explicitly changing payment-budget schedule in the shared engine.
    ***************************************************************************/
   public static Payoff payoff(List<Debt> debts, String currency, List<PaymentTarget> budgets,
      Strategy strategy, int maximumMonths)
   {
      return project(debts, currency, firstBudget(budgets), strategy, maximumMonths, null, budgets).payoff();
   }



   /***************************************************************************
    ** Explicit debt allocations and changing budgets with selected rollover.
    ***************************************************************************/
   public static AllocationResult allocatedPayoff(List<Debt> debts, String currency, List<PaymentTarget> budgets,
      Map<String, List<PaymentTarget>> targets, Strategy extraPriority, int maximumMonths)
   {
      if(targets == null)
      {
         throw new IllegalArgumentException("Explicit per-debt payment targets are required");
      }
      return project(debts, currency, firstBudget(budgets), extraPriority, maximumMonths, targets, budgets);
   }



   private static BigDecimal firstBudget(List<PaymentTarget> budgets)
   {
      if(budgets == null || budgets.isEmpty() || budgets.getFirst() == null)
      {
         throw new IllegalArgumentException("An explicit bounded budget schedule is required");
      }
      return budgets.getFirst().amount();
   }



   private static AllocationResult project(List<Debt> debts, String currency, BigDecimal monthlyBudget,
      Strategy strategy, int maximumMonths, Map<String, List<PaymentTarget>> targets, List<PaymentTarget> budgetProfile)
   {
      int scale = scale(currency);
      if(debts == null || debts.isEmpty() || debts.size() > 100 || maximumMonths < 1 || maximumMonths > 600
         || strategy == null)
      {
         throw new IllegalArgumentException("A bounded debt list, strategy and 1–600 month horizon are required");
      }
      monthlyBudget = money(monthlyBudget, scale);
      if(monthlyBudget.signum() == 0 && budgetProfile == null)
      {
         throw new IllegalArgumentException("Payment budget must be positive");
      }
      var ids = new HashSet<String>();
      for(var debt : debts)
      {
         if(debt == null || !currency.equals(debt.currency()) || !ids.add(debt.id()))
         {
            throw new IllegalArgumentException("Scenario debts must have unique IDs and one currency");
         }
      }
      Map<String, List<PaymentTarget>> allocations = targets == null ? null : validateTargets(ids, targets, scale);
      List<PaymentTarget> monthlyBudgets = budgetProfile == null
         ? null
         : validateTargets(java.util.Set.of("budget"), Map.of("budget", budgetProfile), scale).get("budget");
      // Canonical record ordering makes equal scenarios independent of query order.
      debts = debts.stream().sorted(Comparator.comparing(Debt::id)).toList();
      var balances = new LinkedHashMap<String, BigDecimal>();
      debts.forEach(debt -> balances.put(debt.id(), debt.balance()));
      var initialPriority = ordered(debts, balances, strategy, 1).stream().map(Debt::id).toList();
      String assumptions = "Illustrative monthly model: opening balance × decimal APR / 12; end-of-month payments; "
         + "explicit month-based APR schedule and fees; minimum = max(floor, fraction × balance after charges), capped at amount owed; "
         + "no new borrowing; interest and percentage minimum rounded HALF_UP to currency precision. "
         + "AVALANCHE and SNOWBALL maintain the stated total monthly budget after debts close; MINIMUM_ONLY leaves unused cash. "
         + "Actual daily accrual, billing dates and lender formulas may differ. Budget must already exclude essential spending and chosen reserves. "
         + "Unpaid balance at horizon is an incomplete projection, not a payoff date.";
      var schedule = new ArrayList<Month>();
      var problems = new ArrayList<AllocationProblem>();
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      BigDecimal totalInterest = zero;
      BigDecimal totalFees = zero;
      boolean negativeAmortization = false;
      Shortfall shortfall = null;
      for(int month = 1; month <= maximumMonths && total(balances, scale).signum() > 0; month++)
      {
         if(monthlyBudgets != null)
         {
            monthlyBudget = targetAt(monthlyBudgets, month);
         }
         var opening = new LinkedHashMap<>(balances);
         var interest = new LinkedHashMap<String, BigDecimal>();
         var fees = new LinkedHashMap<String, BigDecimal>();
         var minimums = new LinkedHashMap<String, BigDecimal>();
         var due = new LinkedHashMap<String, BigDecimal>();
         for(var debt : debts)
         {
            BigDecimal balance = balances.get(debt.id());
            BigDecimal charge = balance.multiply(debt.rateAt(month)).divide(BigDecimal.valueOf(12), scale, RoundingMode.HALF_UP);
            BigDecimal fee = balance.signum() == 0 ? zero : debt.monthlyFee();
            BigDecimal owed = balance.add(charge).add(fee);
            interest.put(debt.id(), charge);
            fees.put(debt.id(), fee);
            due.put(debt.id(), owed);
            minimums.put(debt.id(), debt.minimum().max(owed.multiply(debt.minimumFraction()).setScale(scale, RoundingMode.HALF_UP)).min(owed));
         }
         BigDecimal required = total(minimums, scale);
         if(required.compareTo(monthlyBudget) > 0)
         {
            shortfall = new Shortfall(month, monthlyBudget, required);
            if(allocations != null)
            {
               problems.add(new AllocationProblem(month, null, "Required minimums exceed the budget", monthlyBudget, required));
            }
            break;
         }
         var priority = ordered(debts, opening, strategy, month);
         var payments = new LinkedHashMap<>(minimums);
         if(allocations != null)
         {
            for(var debt : debts)
            {
               String id = debt.id();
               BigDecimal proposed = targetAt(allocations.get(id), month).min(due.get(id));
               payments.put(id, proposed);
               if(proposed.compareTo(minimums.get(id)) < 0)
               {
                  problems.add(new AllocationProblem(month, id, "Explicit payment is below the required minimum", proposed, minimums.get(id)));
               }
            }
            BigDecimal proposedTotal = total(payments, scale);
            if(proposedTotal.compareTo(monthlyBudget) > 0)
            {
               problems.add(new AllocationProblem(month, null, "Explicit payments exceed the budget", proposedTotal, monthlyBudget));
            }
            if(!problems.isEmpty())
            {
               shortfall = new Shortfall(month, monthlyBudget, proposedTotal.max(required));
               break;
            }
         }
         BigDecimal remaining = monthlyBudget.subtract(total(payments, scale));
         if(strategy != Strategy.MINIMUM_ONLY)
         {
            for(var debt : priority)
            {
               BigDecimal extra = remaining.min(due.get(debt.id()).subtract(payments.get(debt.id())));
               payments.put(debt.id(), payments.get(debt.id()).add(extra));
               remaining = remaining.subtract(extra);
            }
         }
         var debtRows = new ArrayList<DebtMonth>();
         for(var debt : debts)
         {
            String id = debt.id();
            BigDecimal closing = due.get(id).subtract(payments.get(id));
            balances.put(id, closing);
            boolean growing = closing.compareTo(opening.get(id)) > 0;
            negativeAmortization |= growing;
            debtRows.add(new DebtMonth(id, opening.get(id), interest.get(id), fees.get(id), minimums.get(id), payments.get(id), closing, growing));
         }
         BigDecimal monthInterest = total(interest, scale);
         BigDecimal monthFees = total(fees, scale);
         totalInterest = totalInterest.add(monthInterest);
         totalFees = totalFees.add(monthFees);
         schedule.add(new Month(month, monthlyBudget.subtract(remaining), monthInterest, total(balances, scale),
            monthFees, remaining, priority.stream().map(Debt::id).toList(), debtRows));
      }
      if(monthlyBudgets != null)
      {
         assumptions += " Available payment budget changes only according to the explicit effective-month schedule.";
      }
      if(allocations != null)
      {
         assumptions += " Explicit per-debt payment schedules are preserved and must meet minimums and total budget; "
            + "extra allocation follows the selected priority, with MINIMUM_ONLY retaining unused cash.";
      }
      return new AllocationResult(new Payoff(shortfall == null, shortfall == null && total(balances, scale).signum() == 0,
         schedule.size(), totalInterest, initialPriority, schedule, assumptions, totalFees, shortfall, negativeAmortization), problems);
   }



   /*******************************************************************************
    * Separates operating income from debt service and capital reserves in the explicit currency.
    ******************************************************************************/
   public static Rental rental(String currency, BigDecimal scheduledRent, BigDecimal vacancyAllowance,
      BigDecimal operatingExpenses, BigDecimal debtService, BigDecimal capitalReserve)
   {
      int scale = scale(currency);
      scheduledRent = money(scheduledRent, scale);
      vacancyAllowance = money(vacancyAllowance, scale);
      operatingExpenses = money(operatingExpenses, scale);
      debtService = money(debtService, scale);
      capitalReserve = money(capitalReserve, scale);
      if(vacancyAllowance.compareTo(scheduledRent) > 0)
      {
         throw new IllegalArgumentException("Vacancy allowance cannot exceed scheduled rent");
      }
      BigDecimal noi = scheduledRent.subtract(vacancyAllowance).subtract(operatingExpenses);
      return new Rental(noi, noi.subtract(debtService).subtract(capitalReserve));
   }



   private static Map<String, List<PaymentTarget>> validateTargets(java.util.Set<String> debtIds, Map<String, List<PaymentTarget>> targets, int scale)
   {
      if(!debtIds.equals(targets.keySet()))
      {
         throw new IllegalArgumentException("Payment targets must cover exactly the scenario debts");
      }
      var result = new LinkedHashMap<String, List<PaymentTarget>>();
      for(var entry : targets.entrySet())
      {
         List<PaymentTarget> values = entry.getValue();
         if(values == null || values.isEmpty() || values.size() > 600 || values.stream().anyMatch(value -> value == null))
         {
            throw new IllegalArgumentException("Every debt requires a bounded explicit payment schedule");
         }
         values = values.stream().sorted(Comparator.comparingInt(PaymentTarget::firstMonth)).toList();
         if(values.getFirst().firstMonth() != 1)
         {
            throw new IllegalArgumentException("Payment targets must cover the first projection month");
         }
         var months = new HashSet<Integer>();
         var normalized = new ArrayList<PaymentTarget>();
         for(var value : values)
         {
            if(!months.add(value.firstMonth()))
            {
               throw new IllegalArgumentException("Payment effective months must be unique");
            }
            normalized.add(new PaymentTarget(value.firstMonth(), money(value.amount(), scale)));
         }
         result.put(entry.getKey(), List.copyOf(normalized));
      }
      return Map.copyOf(result);
   }



   private static BigDecimal targetAt(List<PaymentTarget> targets, int month)
   {
      BigDecimal amount = targets.getFirst().amount();
      for(var target : targets)
      {
         if(target.firstMonth() > month)
         {
            break;
         }
         amount = target.amount();
      }
      return amount;
   }



   private static List<Debt> ordered(List<Debt> debts, Map<String, BigDecimal> balances, Strategy strategy, int month)
   {
      Comparator<Debt> rateOrder = Comparator.comparing((Debt debt) -> debt.rateAt(month)).reversed();
      Comparator<Debt> balanceOrder = Comparator.comparing(debt -> balances.get(debt.id()));
      Comparator<Debt> order = strategy == Strategy.AVALANCHE
         ? rateOrder.thenComparing(balanceOrder)
         : balanceOrder.thenComparing(rateOrder);
      return debts.stream().filter(debt -> balances.get(debt.id()).signum() > 0).sorted(order.thenComparing(Debt::id)).toList();
   }



   private static int scale(String currency)
   {
      if(currency == null)
      {
         throw new IllegalArgumentException("Currency is required");
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported currency precision");
      }
      return scale;
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
      catch(ArithmeticException invalidPrecision)
      {
         throw new IllegalArgumentException("Amount exceeds currency precision", invalidPrecision);
      }
   }



   private static BigDecimal total(Map<String, BigDecimal> balances, int scale)
   {
      return balances.values().stream().reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add);
   }
}
