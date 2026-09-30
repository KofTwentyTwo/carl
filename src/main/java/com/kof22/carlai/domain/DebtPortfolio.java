
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/***************************************************************************
 ** Portfolio policies use one authoritative payoff engine. Payment dates are
 ** supplied planning assumptions, not inferred issuer billing/accrual rules.
 ** Dated cash checks remain separate from a monthly payment-budget constraint.
 ***************************************************************************/
public final class DebtPortfolio
{
   /** Payment policies compared using the same debt inputs. */
   public enum Mode
   {
      CURRENT_PAYMENT, MINIMUM_ONLY, AVALANCHE, SNOWBALL, USER_DIRECTED
   }



   /** Qualification of reserves across the supplied cash horizon. */
   public enum LiquidityStatus
   {
      PRESERVED, BREACHED, UNDETERMINED
   }



   /** One estimated payment on its explicitly supplied planning date. */
   public record DatedPayment(String debtId, LocalDate date, BigDecimal amount)
   {
   }



   /** Immutable input assumptions and dated projection for one payment policy. */
   public record Scenario(String currency, LocalDate asOf, List<FinancialPlanning.Debt> startingDebts,
      Map<String, LocalDate> firstPaymentDates, List<FinancialPlanning.PaymentTarget> budgets,
      Mode mode, Map<String, List<FinancialPlanning.PaymentTarget>> targets, FinancialPlanning.Strategy rollover,
      int horizonMonths, FinancialPlanning.Payoff payoff, List<FinancialPlanning.AllocationProblem> problems,
      List<DatedPayment> payments, Map<String, LocalDate> debtPayoffDates, LocalDate payoffDate)
   {
      /** Copies all policy and result collections. */
      public Scenario
      {
         startingDebts = List.copyOf(startingDebts);
         firstPaymentDates = Map.copyOf(firstPaymentDates);
         budgets = List.copyOf(budgets);
         var copied = new HashMap<String, List<FinancialPlanning.PaymentTarget>>();
         targets.forEach((id, values) -> copied.put(id, List.copyOf(values)));
         targets = Map.copyOf(copied);
         problems = List.copyOf(problems);
         payments = List.copyOf(payments);
         debtPayoffDates = Map.copyOf(debtPayoffDates);
      }
   }



   /** Dated cash evidence excluding payments already represented in the scenario. */
   public record CashWindow(BigDecimal openingCash, BigDecimal reserve, LocalDate from, LocalDate through, List<CashFlow.Event> nonDebtEvents)
   {
      /** Requires explicit commitments and protects the supplied evidence list. */
      public CashWindow
      {
         if(nonDebtEvents == null)
         {
            throw new IllegalArgumentException("Explicit non-debt cash commitments are required");
         }
         nonDebtEvents = List.copyOf(nonDebtEvents);
      }
   }



   /** Reserve test with explicit coverage and intraday limitations. */
   public record LiquidityCheck(CashFlow.Projection cash, boolean coverageComplete, LiquidityStatus status, String limitations)
   {
      /** Reports whether daily closing reserves were qualified for the full requested horizon. */
      public boolean reservesPreserved()
      {
         return status == LiquidityStatus.PRESERVED;
      }
   }



   /** Signed cash released relative to a comparable baseline payment. */
   public record CashDifference(int month, BigDecimal baselinePayment, BigDecimal candidatePayment,
      BigDecimal releasedCash, BigDecimal cumulativeReleasedCash)
   {
   }



   /** Comparable costs and monthly liquidity differences, with horizon limitations. */
   public record Comparison(BigDecimal candidateMinusBaselineInterestAndFees, boolean fullPayoffCostsCompared,
      List<CashDifference> cashDifferences, String limitations)
   {
      /** Protects the monthly comparison sequence. */
      public Comparison
      {
         cashDifferences = List.copyOf(cashDifferences);
      }
   }

   private DebtPortfolio()
   {
   }



   /** Projects a bounded portfolio using explicit dates, budgets and payment policy. */
   public static Scenario solve(List<FinancialPlanning.Debt> debts, String currency, LocalDate asOf,
      Map<String, LocalDate> firstPaymentDates, List<FinancialPlanning.PaymentTarget> budgets,
      Mode mode, Map<String, List<FinancialPlanning.PaymentTarget>> targets,
      FinancialPlanning.Strategy rollover, int horizonMonths)
   {
      if(debts == null || debts.isEmpty() || firstPaymentDates == null || budgets == null || mode == null || targets == null
         || rollover == null || asOf == null || asOf.getYear() < 1900 || asOf.getYear() > 2200)
      {
         throw new IllegalArgumentException("A bounded portfolio requires explicit sources, dates, budgets and payment policy");
      }
      boolean explicit = mode == Mode.CURRENT_PAYMENT || mode == Mode.USER_DIRECTED;
      if(mode == Mode.CURRENT_PAYMENT && rollover != FinancialPlanning.Strategy.MINIMUM_ONLY || !explicit && !targets.isEmpty())
      {
         throw new IllegalArgumentException("Current payments cannot gain hypothetical rollover; automatic strategies cannot hide explicit targets");
      }
      FinancialPlanning.Payoff payoff;
      FinancialPlanning.Strategy effectiveRollover = rollover;
      List<FinancialPlanning.AllocationProblem> problems;
      if(explicit)
      {
         var result = FinancialPlanning.allocatedPayoff(debts, currency, budgets, targets, rollover, horizonMonths);
         payoff = result.payoff();
         problems = result.problems();
      }
      else
      {
         var priority = switch(mode)
         {
            case MINIMUM_ONLY -> FinancialPlanning.Strategy.MINIMUM_ONLY;
            case AVALANCHE -> FinancialPlanning.Strategy.AVALANCHE;
            case SNOWBALL -> FinancialPlanning.Strategy.SNOWBALL;
            default -> throw new IllegalArgumentException("Unsupported automatic payment policy");
         };
         effectiveRollover = priority;
         payoff = FinancialPlanning.payoff(debts, currency, budgets, priority, horizonMonths);
         problems = List.of();
      }
      debts = debts.stream().sorted(Comparator.comparing(FinancialPlanning.Debt::id)).toList();
      if(!firstPaymentDates.keySet().equals(debts.stream().map(FinancialPlanning.Debt::id).collect(java.util.stream.Collectors.toSet())))
      {
         throw new IllegalArgumentException("First payment dates must cover exactly the scenario debts");
      }
      for(var date : firstPaymentDates.values())
      {
         if(date == null || date.isBefore(asOf) || date.isAfter(asOf.plusMonths(1)))
         {
            throw new IllegalArgumentException("Each first payment date must be explicitly within the first projection month");
         }
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      budgets = canonical(budgets, scale);
      var normalizedTargets = new HashMap<String, List<FinancialPlanning.PaymentTarget>>();
      targets.forEach((id, values) -> normalizedTargets.put(id, canonical(values, scale)));
      var payments = new ArrayList<DatedPayment>();
      var payoffDates = new HashMap<String, LocalDate>();
      for(var debt : debts)
      {
         if(debt.balance().signum() == 0)
         {
            payoffDates.put(debt.id(), asOf);
         }
      }
      for(var month : payoff.schedule())
      {
         for(var debt : month.debts())
         {
            LocalDate date = firstPaymentDates.get(debt.id()).plusMonths(month.number() - 1L);
            if(debt.payment().signum() > 0)
            {
               payments.add(new DatedPayment(debt.id(), date, debt.payment()));
            }
            if(debt.closingBalance().signum() == 0)
            {
               payoffDates.putIfAbsent(debt.id(), date);
            }
         }
      }
      payments.sort(Comparator.comparing(DatedPayment::date).thenComparing(DatedPayment::debtId));
      LocalDate finalDate = payoff.paidOff() ? payoffDates.values().stream().max(Comparator.naturalOrder()).orElse(asOf) : null;
      return new Scenario(currency, asOf, debts, firstPaymentDates, budgets, mode, normalizedTargets, effectiveRollover,
         horizonMonths, payoff, problems, payments, payoffDates, finalDate);
   }



   /** Checks daily closing reserves only within the supplied evidence window. */
   public static LiquidityCheck checkLiquidity(Scenario scenario, CashWindow window)
   {
      if(scenario == null || window == null || !scenario.asOf().equals(window.from()) || window.through() == null)
      {
         throw new IllegalArgumentException("Cash evidence must start at the scenario as-of date");
      }
      var events = new ArrayList<>(window.nonDebtEvents());
      for(var payment : scenario.payments())
      {
         if(!payment.date().isBefore(window.from()) && !payment.date().isAfter(window.through()))
         {
            String id = "debt:" + UUID.nameUUIDFromBytes((payment.debtId() + ":" + payment.date()).getBytes(StandardCharsets.UTF_8));
            events.add(new CashFlow.Event(id, payment.date(), scenario.currency(), payment.amount().negate()));
         }
      }
      var cash = CashFlow.project(scenario.currency(), window.openingCash(), window.reserve(), window.from(), window.through(), events);
      boolean covered = scenario.payoff().feasible() && scenario.firstPaymentDates().values().stream()
         .allMatch(date -> !date.plusMonths(scenario.horizonMonths() - 1L).isAfter(window.through()));
      LiquidityStatus status = cash.firstReserveShortfall() != null
         ? LiquidityStatus.BREACHED
         : covered ? LiquidityStatus.PRESERVED : LiquidityStatus.UNDETERMINED;
      return new LiquidityCheck(cash, covered, status,
         "Dated debt payments are planning estimates; non-debt events must exclude duplicate debt-payment entries. "
            + "Preserved refers to daily closing cash in the covered interval; intraday ordering is unknown. "
            + "A shorter cash horizon cannot qualify the entire requested debt plan.");
   }



   /** Compares scenarios only when starting debts and planning assumptions match. */
   public static Comparison compare(Scenario baseline, Scenario candidate)
   {
      if(baseline == null || candidate == null || !baseline.currency().equals(candidate.currency()) || !baseline.asOf().equals(candidate.asOf())
         || !baseline.startingDebts().equals(candidate.startingDebts()) || !baseline.firstPaymentDates().equals(candidate.firstPaymentDates())
         || !baseline.budgets().equals(candidate.budgets()) || baseline.horizonMonths() != candidate.horizonMonths())
      {
         throw new IllegalArgumentException("Strategy comparison requires the same starting debts, dates, budgets and horizon");
      }
      if(!baseline.payoff().feasible() || !candidate.payoff().feasible())
      {
         throw new IllegalArgumentException("An infeasible allocation cannot be presented as a completed comparable projection");
      }
      int scale = Currency.getInstance(baseline.currency()).getDefaultFractionDigits();
      BigDecimal cumulative = BigDecimal.ZERO.setScale(scale);
      var differences = new ArrayList<CashDifference>();
      for(int month = 1; month <= baseline.horizonMonths(); month++)
      {
         BigDecimal before = paymentAt(baseline.payoff(), month, scale);
         BigDecimal after = paymentAt(candidate.payoff(), month, scale);
         BigDecimal released = before.subtract(after);
         cumulative = cumulative.add(released);
         differences.add(new CashDifference(month, before, after, released, cumulative));
      }
      BigDecimal extraCost = candidate.payoff().interest().add(candidate.payoff().fees())
         .subtract(baseline.payoff().interest().add(baseline.payoff().fees()));
      return new Comparison(extraCost, baseline.payoff().paidOff() && candidate.payoff().paidOff(), differences,
         "Released cash is baseline payment minus candidate payment; negative values require extra cash. "
            + "Interest/fee differences cover the stated horizon and are total payoff costs only when both projections pay off. "
            + "Cash reserves and actual daily issuer accrual require separate evidence.");
   }



   private static BigDecimal paymentAt(FinancialPlanning.Payoff payoff, int month, int scale)
   {
      return month <= payoff.schedule().size() ? payoff.schedule().get(month - 1).payment() : BigDecimal.ZERO.setScale(scale);
   }



   private static List<FinancialPlanning.PaymentTarget> canonical(List<FinancialPlanning.PaymentTarget> values, int scale)
   {
      return values.stream().sorted(Comparator.comparingInt(FinancialPlanning.PaymentTarget::firstMonth))
         .map(value -> new FinancialPlanning.PaymentTarget(value.firstMonth(), value.amount().setScale(scale, RoundingMode.UNNECESSARY))).toList();
   }
}
