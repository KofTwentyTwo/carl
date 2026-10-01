/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Whole-portfolio estimates over explicit hypothetical transfers and supplied contract assumptions. */
public final class PortfolioRestructuring
{
   /** Original balances, dates and one comparable post-essential payment budget. */
   public record Inputs(String currency, LocalDate asOf, List<FinancialPlanning.Debt> debts,
      Map<String, LocalDate> firstPaymentDates, List<FinancialPlanning.PaymentTarget> budgets, int horizon,
      Map<String, List<FinancialPlanning.PaymentTarget>> monthlyFeeProfiles, String evidence)
   {
      /** Compatibility input for explicitly fixed monthly charges. */
      public Inputs(String currency, LocalDate asOf, List<FinancialPlanning.Debt> debts,
         Map<String, LocalDate> firstPaymentDates, List<FinancialPlanning.PaymentTarget> budgets, int horizon, String evidence)
      {
         this(currency, asOf, debts, firstPaymentDates, budgets, horizon, Map.of(), evidence);
      }



      /** Keeps supplied original inputs immutable. */
      public Inputs
      {
         debts = List.copyOf(debts);
         firstPaymentDates = Map.copyOf(firstPaymentDates);
         budgets = List.copyOf(budgets);
         var feeCopy = new HashMap<String, List<FinancialPlanning.PaymentTarget>>();
         monthlyFeeProfiles.forEach((id, values) -> feeCopy.put(id, List.copyOf(values)));
         monthlyFeeProfiles = Map.copyOf(feeCopy);
      }
   }



   /** Hypothetical destination and explicit source allocation; capacity is not spending budget. */
   public record Move(FinancingScenarios.Offer offer, Map<String, BigDecimal> allocations, BigDecimal capacity,
      BigDecimal minimum, BigDecimal minimumFraction, BigDecimal monthlyFee, LocalDate firstPayment, String evidence)
   {
      /** Keeps source allocations immutable. */
      public Move
      {
         allocations = Map.copyOf(allocations);
      }
   }



   /** Explicit payment priorities; current payments cannot acquire inferred rollover. */
   public record Policy(DebtPortfolio.Mode mode, Map<String, List<FinancialPlanning.PaymentTarget>> targets, FinancialPlanning.Strategy rollover)
   {
      /** Keeps all dated payment targets immutable. */
      public Policy
      {
         var copy = new HashMap<String, List<FinancialPlanning.PaymentTarget>>();
         targets.forEach((id, values) -> copy.put(id, List.copyOf(values)));
         targets = Map.copyOf(copy);
      }
   }



   /** One payment period with separate conditional interest and explicit payment date. */
   public record DebtMonth(String id, LocalDate paymentDate, BigDecimal openingBalance, BigDecimal interest,
      BigDecimal monthlyFee, BigDecimal requiredMinimum, BigDecimal payment, BigDecimal deferredInterestCharged,
      BigDecimal pendingDeferredInterest, BigDecimal remainingBalance, boolean negativeAmortization)
   {
   }



   /** Fully allocated period; no invented payment is stored for an infeasible period. */
   public record Month(int number, BigDecimal payment, BigDecimal interest, BigDecimal monthlyFees,
      BigDecimal remainingDebt, List<DebtMonth> debts)
   {
      /** Keeps contributing calculations immutable. */
      public Month
      {
         debts = List.copyOf(debts);
      }
   }



   /** Comparable cost and residual facts; unknown terms never produce a fake payoff. */
   public record Projection(Inputs inputs, List<Move> moves, Policy policy, boolean determined, boolean feasible,
      boolean paidOff, BigDecimal originalPrincipal, BigDecimal debtAfterFinancedFees, BigDecimal financedFees,
      BigDecimal upfrontCashFees, BigDecimal interest, BigDecimal monthlyFees, Map<String, BigDecimal> remainingBalances,
      Map<String, BigDecimal> maturityResiduals, List<Month> months, List<String> gaps, String limitations)
   {
      /** Keeps the complete source/policy/result snapshot immutable. */
      public Projection
      {
         moves = List.copyOf(moves);
         remainingBalances = Map.copyOf(remainingBalances);
         maturityResiduals = Map.copyOf(maturityResiduals);
         months = List.copyOf(months);
         gaps = List.copyOf(gaps);
      }



      /** Returns only source/destination payoff dates established by calculated rows. */
      @com.fasterxml.jackson.annotation.JsonProperty
      public Map<String, LocalDate> payoffDates()
      {
         var result = new LinkedHashMap<String, LocalDate>();
         for(var month : months)
         {
            for(var debt : month.debts())
            {
               if(debt.openingBalance().signum() == 0)
               {
                  result.putIfAbsent(debt.id(), inputs.asOf());
               }
               else if(debt.remainingBalance().signum() == 0)
               {
                  result.putIfAbsent(debt.id(), debt.paymentDate());
               }
            }
         }
         inputs.debts().stream().filter(debt -> debt.balance().signum() == 0).forEach(debt -> result.putIfAbsent(debt.id(), inputs.asOf()));
         return Map.copyOf(result);
      }



      /** Never predicts a complete payoff date for a residual or missing-term result. */
      @com.fasterxml.jackson.annotation.JsonProperty
      public LocalDate payoffDate()
      {
         return paidOff ? payoffDates().values().stream().max(java.util.Comparator.naturalOrder()).orElse(inputs.asOf()) : null;
      }
   }



   /** Cost differences qualify lifetime costs only when both scenarios fully pay off. */
   public record Comparison(boolean fullPayoffCostsCompared, BigDecimal candidateMinusBaselineCost,
      BigDecimal candidateMinusBaselineUpfrontCash, List<DebtPortfolio.CashDifference> cashDifferences, Integer breakEvenMonth, String limitations)
   {
      /** Keeps the complete month-by-month change in cash commitments immutable. */
      public Comparison
      {
         cashDifferences = List.copyOf(cashDifferences);
      }
   }

   private static final String LIMITS = "Monthly estimates; interest = opening balance × decimal APR / 12, rounded HALF_UP. Transfers occur at the selected as-of date. Deferred accrual is noncompounding on opening promotional balance and all destination payments apply to it. Contract minimums/allocation/maturity are supplied assumptions, not issuer verification. No eligibility, approval, action or guaranteed savings. Dated essential spending, reserves, tax, collateral and other closing/prepayment conditions require separate review.";
   private PortfolioRestructuring()
   {
   }



   /** Keeps all original debts in the comparison while applying explicit hypothetical allocations. */
   public static Projection project(Inputs input, List<Move> moves, Policy policy)
   {
      if(input == null || moves == null || moves.size() > 20 || policy == null || policy.mode() == null || policy.rollover() == null
         || input.horizon() < 1 || input.horizon() > 600 || input.evidence() == null || input.evidence().isBlank())
      {
         throw new IllegalArgumentException("Bounded original inputs, moves and explicit payment policy are required");
      }
      // The existing engine validates original currencies, profiles and supplied dates.
      DebtPortfolio.solve(input.debts(), input.currency(), input.asOf(), input.firstPaymentDates(), input.budgets(),
         DebtPortfolio.Mode.AVALANCHE, Map.of(), FinancialPlanning.Strategy.AVALANCHE, 1);
      int scale = Currency.getInstance(input.currency()).getDefaultFractionDigits();
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      BigDecimal original = input.debts().stream().map(FinancialPlanning.Debt::balance).reduce(zero, BigDecimal::add);
      var originalIds = input.debts().stream().map(FinancialPlanning.Debt::id).collect(java.util.stream.Collectors.toSet());
      if(!originalIds.containsAll(input.monthlyFeeProfiles().keySet()))
      {
         throw new IllegalArgumentException("Fee profiles must reference original debts only");
      }
      input.monthlyFeeProfiles().values().forEach(values -> targetAt(values, 1));
      var debts = new ArrayList<>(input.debts());
      var dates = new LinkedHashMap<>(input.firstPaymentDates());
      var destinations = new LinkedHashMap<String, Move>();
      var gaps = new ArrayList<String>();
      BigDecimal financed = zero;
      BigDecimal cash = zero;
      for(var move : moves)
      {
         if(move == null || move.offer() == null || !originalIds.containsAll(move.allocations().keySet())
            || move.evidence() == null || move.evidence().isBlank() || move.evidence().length() > 4000
            || move.firstPayment() == null || move.firstPayment().isBefore(input.asOf()) || move.firstPayment().isAfter(input.asOf().plusMonths(1)))
         {
            throw new IllegalArgumentException("Moves require original source identities and evidenced first-month dates");
         }
         var transfer = FinancingScenarios.transfer(debts, move.allocations(), move.offer(), move.capacity());
         if(destinations.putIfAbsent(move.offer().id(), move) != null)
         {
            throw new IllegalArgumentException("Destination identities must be unique");
         }
         debts = new ArrayList<>(transfer.retainedDebts());
         debts.add(new FinancialPlanning.Debt(move.offer().id(), input.currency(), move.offer().principal().add(move.offer().financedFee()),
            move.minimum(), move.minimumFraction(), move.offer().rates(), move.monthlyFee()));
         if(debts.size() > 100)
         {
            throw new IllegalArgumentException("Whole portfolio cannot exceed 100 original and destination debts");
         }
         dates.put(move.offer().id(), move.firstPayment());
         financed = financed.add(move.offer().financedFee());
         cash = cash.add(move.offer().cashFee());
         if(move.offer().promotion().kind() == FinancingScenarios.PromotionKind.DEFERRED_INTEREST && !move.offer().promotion().allocationTermsConfirmed())
         {
            gaps.add("Deferred accrual/payment allocation terms are unconfirmed for " + move.offer().id());
         }
      }
      boolean explicit = policy.mode() == DebtPortfolio.Mode.CURRENT_PAYMENT || policy.mode() == DebtPortfolio.Mode.USER_DIRECTED;
      Set<String> transformedIds = debts.stream().map(FinancialPlanning.Debt::id).collect(java.util.stream.Collectors.toSet());
      if(explicit && !policy.targets().keySet().equals(transformedIds) || !explicit && !policy.targets().isEmpty()
         || policy.mode() == DebtPortfolio.Mode.CURRENT_PAYMENT && policy.rollover() != FinancialPlanning.Strategy.MINIMUM_ONLY)
      {
         throw new IllegalArgumentException("Explicit targets must cover the resulting portfolio; current payments cannot infer rollover");
      }
      return solve(input, moves, policy, debts, dates, destinations, original, financed, cash, gaps, scale);
   }



   /** Compares only identical original balances, dates, budgets and horizons. */
   public static Comparison compare(Projection baseline, Projection candidate)
   {
      if(baseline == null || candidate == null || !baseline.inputs().equals(candidate.inputs()))
      {
         throw new IllegalArgumentException("Portfolio comparisons require identical original source and budget inputs");
      }
      boolean complete = baseline.determined() && candidate.determined() && baseline.paidOff() && candidate.paidOff();
      BigDecimal difference = baseline.determined() && candidate.determined() && baseline.feasible() && candidate.feasible()
         ? cost(candidate).subtract(cost(baseline))
         : null;
      var cashDifferences = new ArrayList<DebtPortfolio.CashDifference>();
      if(baseline.determined() && candidate.determined() && baseline.feasible() && candidate.feasible())
      {
         BigDecimal zero = BigDecimal.ZERO.setScale(Currency.getInstance(baseline.inputs().currency()).getDefaultFractionDigits());
         BigDecimal cumulative = zero;
         for(int number = 1; number <= baseline.inputs().horizon(); number++)
         {
            BigDecimal before = number <= baseline.months().size() ? baseline.months().get(number - 1).payment() : zero;
            BigDecimal after = number <= candidate.months().size() ? candidate.months().get(number - 1).payment() : zero;
            BigDecimal released = before.subtract(after);
            cumulative = cumulative.add(released);
            cashDifferences.add(new DebtPortfolio.CashDifference(number, before, after, released, cumulative));
         }
      }
      return new Comparison(complete, difference, candidate.upfrontCashFees().subtract(baseline.upfrontCashFees()), cashDifferences,
         complete && difference.signum() < 0 ? breakEven(baseline, candidate) : null,
         complete
            ? "Fully paid-off monthly estimates under supplied terms; affordability and eligibility remain unqualified."
            : "Horizon or missing-term costs are not lifetime savings; residual balances and pending charges remain material.");
   }



   /** Includes cash fees at the explicit hypothetical closing date, never as new spendable credit. */
   public static DebtPortfolio.LiquidityCheck checkLiquidity(Projection projection, DebtPortfolio.CashWindow window)
   {
      if(projection == null || window == null || !projection.inputs().asOf().equals(window.from()) || window.through() == null)
      {
         throw new IllegalArgumentException("Cash evidence must start at the hypothetical closing/as-of date");
      }
      var events = new ArrayList<>(window.nonDebtEvents());
      if(projection.upfrontCashFees().signum() > 0)
      {
         events.add(new CashFlow.Event("portfolio:closing-fees", window.from(), projection.inputs().currency(), projection.upfrontCashFees().negate()));
      }
      for(var month : projection.months())
      {
         for(var debt : month.debts())
         {
            if(debt.payment().signum() > 0 && !debt.paymentDate().isAfter(window.through()))
            {
               events.add(new CashFlow.Event("portfolio:" + month.number() + ":" + debt.id(), debt.paymentDate(), projection.inputs().currency(), debt.payment().negate()));
            }
         }
      }
      var cash = CashFlow.project(projection.inputs().currency(), window.openingCash(), window.reserve(), window.from(), window.through(), events);
      LocalDate requestedThrough = projection.inputs().firstPaymentDates().values().stream().max(java.util.Comparator.naturalOrder()).orElseThrow()
         .plusMonths(projection.inputs().horizon() - 1L);
      for(var move : projection.moves())
      {
         LocalDate last = move.firstPayment().plusMonths(projection.inputs().horizon() - 1L);
         if(last.isAfter(requestedThrough))
         {
            requestedThrough = last;
         }
      }
      boolean complete = projection.determined() && projection.feasible() && !requestedThrough.isAfter(window.through());
      var status = cash.firstReserveShortfall() != null
         ? DebtPortfolio.LiquidityStatus.BREACHED
         : complete ? DebtPortfolio.LiquidityStatus.PRESERVED : DebtPortfolio.LiquidityStatus.UNDETERMINED;
      return new DebtPortfolio.LiquidityCheck(cash, complete, status,
         "Supplied dated cash evidence must exclude duplicate debt payments and closing fees. Hypothetical closing fees occur on as-of. "
            + "Daily closing reserves are conditional on complete selected cash commitments; intraday timing, eligibility and actual issuer accrual remain unqualified.");
   }



   private static BigDecimal cost(Projection value)
   {
      return value.interest().add(value.monthlyFees()).add(value.financedFees()).add(value.upfrontCashFees());
   }



   private static Integer breakEven(Projection baseline, Projection candidate)
   {
      BigDecimal before = baseline.financedFees().add(baseline.upfrontCashFees());
      BigDecimal after = candidate.financedFees().add(candidate.upfrontCashFees());
      Integer crossing = after.compareTo(before) <= 0 ? 0 : null;
      for(int number = 1; number <= baseline.inputs().horizon(); number++)
      {
         if(number <= baseline.months().size())
         {
            var month = baseline.months().get(number - 1);
            before = before.add(month.interest()).add(month.monthlyFees());
         }
         if(number <= candidate.months().size())
         {
            var month = candidate.months().get(number - 1);
            after = after.add(month.interest()).add(month.monthlyFees());
         }
         if(after.compareTo(before) > 0)
         {
            crossing = null;
         }
         else if(crossing == null)
         {
            crossing = number;
         }
      }
      return crossing;
   }



   private static FinancialPlanning.Payoff period(List<FinancialPlanning.Debt> debts, String currency, BigDecimal budget, Policy policy, int month)
   {
      if(policy.mode() == DebtPortfolio.Mode.CURRENT_PAYMENT || policy.mode() == DebtPortfolio.Mode.USER_DIRECTED)
      {
         var targets = new LinkedHashMap<String, List<FinancialPlanning.PaymentTarget>>();
         for(var debt : debts)
         {
            targets.put(debt.id(), List.of(new FinancialPlanning.PaymentTarget(1, targetAt(policy.targets().get(debt.id()), month))));
         }
         return FinancialPlanning.allocatedPayoff(debts, currency, List.of(new FinancialPlanning.PaymentTarget(1, budget)), targets, policy.rollover(), 1).payoff();
      }
      var strategy = switch(policy.mode())
      {
         case MINIMUM_ONLY -> FinancialPlanning.Strategy.MINIMUM_ONLY;
         case AVALANCHE -> FinancialPlanning.Strategy.AVALANCHE;
         case SNOWBALL -> FinancialPlanning.Strategy.SNOWBALL;
         default -> throw new IllegalArgumentException("Unsupported portfolio payment policy");
      };
      return FinancialPlanning.payoff(debts, currency, budget, strategy, 1);
   }



   private static BigDecimal targetAt(List<FinancialPlanning.PaymentTarget> values, int month)
   {
      if(values == null || values.isEmpty() || values.size() > 600)
      {
         throw new IllegalArgumentException("Explicit bounded payment profiles must start in month one");
      }
      var ordered = values.stream().sorted(java.util.Comparator.comparingInt(FinancialPlanning.PaymentTarget::firstMonth)).toList();
      if(ordered.getFirst().firstMonth() != 1 || ordered.stream().map(FinancialPlanning.PaymentTarget::firstMonth).distinct().count() != ordered.size())
      {
         throw new IllegalArgumentException("Payment profile effective months must be unique and cover month one");
      }
      BigDecimal amount = ordered.getFirst().amount();
      for(var value : ordered)
      {
         if(value.firstMonth() > month)
         {
            break;
         }
         amount = value.amount();
      }
      return amount;
   }



   private static BigDecimal rateAt(List<FinancialPlanning.Rate> rates, int month)
   {
      BigDecimal result = rates.getFirst().annualRate();
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



   private static BigDecimal total(Map<String, BigDecimal> values, int scale)
   {
      return values.values().stream().reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add);
   }



   private static Projection solve(Inputs input, List<Move> moves, Policy policy, List<FinancialPlanning.Debt> debts,
      Map<String, LocalDate> dates, Map<String, Move> destinations, BigDecimal original, BigDecimal financed,
      BigDecimal cash, List<String> gaps, int scale)
   {
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      var balances = new LinkedHashMap<String, BigDecimal>();
      debts.stream().sorted(java.util.Comparator.comparing(FinancialPlanning.Debt::id)).forEach(debt -> balances.put(debt.id(), debt.balance()));
      var pending = new HashMap<String, BigDecimal>();
      var residuals = new LinkedHashMap<String, BigDecimal>();
      var months = new ArrayList<Month>();
      if(!gaps.isEmpty())
      {
         return new Projection(input, moves, policy, false, false, false, original, original.add(financed), financed, cash,
            null, null, balances, residuals, months, gaps, LIMITS);
      }
      BigDecimal interest = zero;
      BigDecimal fees = zero;
      boolean feasible = true;
      for(int number = 1; number <= input.horizon() && total(balances, scale).signum() > 0; number++)
      {
         var current = new ArrayList<FinancialPlanning.Debt>();
         for(var debt : debts)
         {
            BigDecimal monthlyFee = input.monthlyFeeProfiles().containsKey(debt.id())
               ? targetAt(input.monthlyFeeProfiles().get(debt.id()), number)
               : debt.monthlyFee();
            current.add(new FinancialPlanning.Debt(debt.id(), debt.currency(), balances.get(debt.id()), debt.minimum(), debt.minimumFraction(),
               List.of(new FinancialPlanning.Rate(1, rateAt(debt.rates(), number))), monthlyFee));
         }
         var period = period(current, input.currency(), targetAt(input.budgets(), number), policy, number);
         if(!period.feasible())
         {
            gaps.add("Payment policy/minimum budget is infeasible in month " + number + "; no payment allocated");
            feasible = false;
            break;
         }
         if(period.schedule().isEmpty())
         {
            break;
         }
         var allocated = period.schedule().getFirst();
         BigDecimal periodInterest = allocated.interest();
         var rows = new ArrayList<DebtMonth>();
         for(var debt : allocated.debts())
         {
            BigDecimal closing = debt.closingBalance();
            BigDecimal charged = zero;
            Move move = destinations.get(debt.id());
            if(move != null && move.offer().promotion().kind() == FinancingScenarios.PromotionKind.DEFERRED_INTEREST)
            {
               var promotion = move.offer().promotion();
               if(number <= promotion.months())
               {
                  BigDecimal accrual = debt.openingBalance().multiply(promotion.deferredAnnualRate()).divide(BigDecimal.valueOf(12), scale, RoundingMode.HALF_UP);
                  pending.merge(debt.id(), accrual, BigDecimal::add);
               }
               if(closing.signum() == 0)
               {
                  pending.put(debt.id(), zero);
               }
               else if(number == promotion.months())
               {
                  charged = pending.getOrDefault(debt.id(), zero);
                  closing = closing.add(charged);
                  pending.put(debt.id(), zero);
                  periodInterest = periodInterest.add(charged);
               }
            }
            balances.put(debt.id(), closing);
            if(closing.compareTo(new BigDecimal("100000000000000")) > 0)
            {
               gaps.add("Model balance bound exceeded for " + debt.id() + "; projection stops without a payoff claim");
               feasible = false;
            }
            rows.add(new DebtMonth(debt.id(), dates.get(debt.id()).plusMonths(number - 1L), debt.openingBalance(), debt.interest(), debt.fee(),
               debt.requiredMinimum(), debt.payment(), charged, pending.getOrDefault(debt.id(), zero), closing, closing.compareTo(debt.openingBalance()) > 0));
            if(move != null && number == move.offer().termMonths() && closing.signum() > 0)
            {
               residuals.put(debt.id(), closing);
               gaps.add("Maturity balance remains for " + debt.id() + "; no extension or balloon funding assumed");
               feasible = false;
            }
         }
         months.add(new Month(number, allocated.payment(), periodInterest, allocated.fees(), total(balances, scale), rows));
         interest = interest.add(periodInterest);
         fees = fees.add(allocated.fees());
         if(!feasible)
         {
            break;
         }
      }
      boolean paid = feasible && total(balances, scale).signum() == 0;
      if(feasible && !paid)
      {
         gaps.add("Debt remains at the bounded horizon; no complete payoff date or lifetime savings established");
      }
      return new Projection(input, moves, policy, true, feasible, paid, original, original.add(financed), financed, cash,
         interest, fees, balances, residuals, months, gaps, LIMITS);
   }
}
