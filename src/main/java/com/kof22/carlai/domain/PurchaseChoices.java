/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;


/** Exact conditional purchase comparisons against one dated protected cash forecast. */
public final class PurchaseChoices
{
   /** An existing card is eligible for an interest-free comparison only with explicit grace evidence. */
   public record Card(String id, LocalDate payoffDate, boolean graceConfirmed, boolean balanceResolved, String evidence)
   {
   }



   /** A supplied offer retains its dated scope and independent underwriting limitations. */
   public record Offer(FinancingScenarios.Offer terms, LocalDate firstPayment, boolean current, String evidence)
   {
   }



   /** A cash commitment is separate from a credit limit or projected loan proceeds. */
   public record Payment(LocalDate date, BigDecimal amount)
   {
   }



   /** Missing evidence must not become an affordable outcome. */
   public enum State
   {
      WITHIN_STATED_PLAN, EXCEEDS_PLAN, UNDETERMINED
   }



   /** Immutable alternative with total outlay, repayment timing and explicit forecast coverage. */
   public record Option(String id, State state, BigDecimal totalCashOutlay, BigDecimal financeCost,
      LocalDate lastPayment, BigDecimal minimumCashAfterPayments, LocalDate firstReserveShortfall,
      List<Payment> payments, List<String> gaps, String evidence)
   {
      /** Copies the complete retained payment/evidence projection. */
      public Option
      {
         payments = List.copyOf(payments);
         gaps = List.copyOf(gaps);
      }
   }



   /** The maximum cash budget remains independent of borrowing capacity. */
   public record Result(String currency, LocalDate purchaseDate, BigDecimal allInPrice, BigDecimal maximumCashBudget,
      BigDecimal agreedPurchaseCap, String preferredOption, List<Option> options, String recommendation, String limitations)
   {
      /** Copies alternatives without making a financial commitment. */
      public Result
      {
         options = List.copyOf(options);
      }
   }
   private PurchaseChoices()
   {
   }



   /** Compares cash, explicitly supported full card repayment and bounded actual offer schedules. */
   public static Result compare(PurchaseAffordability.Budget budget, BigDecimal reserve, BigDecimal cap,
      BigDecimal price, Card card, List<Offer> offers)
   {
      if(budget == null || reserve == null || reserve.signum() < 0 || cap == null || cap.signum() < 0 || offers == null || offers.size() > 5)
      {
         throw new IllegalArgumentException("Bounded offers and explicit cash/reserve/goal assumptions are required");
      }
      PurchaseAffordability.classify(budget, price);
      var options = new ArrayList<Option>();
      options.add(evaluate("CASH", budget, reserve, cap, price, List.of(new Payment(budget.purchaseDate(), price)), true, true, List.of(), "Immediate cash payment; no rewards assumed"));
      if(card != null)
      {
         CarlService.bounded(card.evidence(), 4000, "Card grace and payoff evidence");
         var gaps = new ArrayList<String>();
         if(!card.graceConfirmed() || !card.balanceResolved())
         {
            gaps.add("Existing balance/grace eligibility is unresolved; paying this purchase alone may not avoid interest.");
         }
         if(card.payoffDate() == null || card.payoffDate().isBefore(budget.purchaseDate()))
         {
            gaps.add("An explicit future full-repayment due date is required.");
         }
         var payments = card.payoffDate() == null ? List.<Payment>of() : List.of(new Payment(card.payoffDate(), price));
         options.add(evaluate(card.id(), budget, reserve, cap, price, payments, gaps.isEmpty(), gaps.isEmpty(), gaps, card.evidence()));
      }
      var ids = new java.util.HashSet<String>();
      ids.add("CASH");
      if(card != null && (card.id() == null || !ids.add(card.id())))
      {
         throw new IllegalArgumentException("Distinct payment option identities are required");
      }
      for(var offer : offers)
      {
         if(offer == null || offer.terms() == null || !ids.add(offer.terms().id()) || !offer.terms().currency().equals(budget.currency()) || offer.terms().principal().compareTo(price) != 0 || offer.firstPayment() == null || offer.firstPayment().isBefore(budget.purchaseDate()))
         {
            throw new IllegalArgumentException("Compare distinct offers funding this purchase in the same currency with explicit payment dates");
         }
         CarlService.bounded(offer.evidence(), 4000, "Offer evidence");
         boolean supportedFirstPeriod = !offer.firstPayment().isAfter(budget.purchaseDate().plusMonths(1));
         var projection = FinancingScenarios.projectFromFirstPayment(offer.terms(), offer.firstPayment());
         var gaps = new ArrayList<String>();
         if(!supportedFirstPeriod)
         {
            gaps.add("First payment is beyond one modeled month; extended initial interest or grace rules are not qualified.");
         }
         if(!offer.current())
         {
            gaps.add("Offer terms do not cover the selected purchase date.");
         }
         if(offer.terms().evidence() != FinancingScenarios.Evidence.VERIFIED_TERMS)
         {
            gaps.add("Terms are hypothetical; confirm actual costs, eligibility and approval before choosing.");
         }
         if(!projection.determined() || !projection.paidOff() || projection.balloonDue().signum() > 0 || projection.pendingDeferredInterest().signum() > 0)
         {
            gaps.add("Repayment does not establish full payoff without a balloon or unresolved deferred interest.");
         }
         if(offer.terms().promotion().kind() == FinancingScenarios.PromotionKind.DEFERRED_INTEREST)
         {
            gaps.add("Deferred interest can become payable if the promotional balance is not cleared under the recorded allocation rules; the schedule assumes every payment is made.");
         }
         var payments = new ArrayList<Payment>();
         payments.add(new Payment(budget.purchaseDate(), offer.terms().cashFee()));
         for(var month : projection.months())
         {
            payments.add(new Payment(month.paymentDate(), month.payment()));
         }
         boolean qualified = supportedFirstPeriod && offer.current() && offer.terms().evidence() == FinancingScenarios.Evidence.VERIFIED_TERMS && projection.determined() && projection.paidOff() && projection.balloonDue().signum() == 0 && projection.pendingDeferredInterest().signum() == 0;
         options.add(evaluate(offer.terms().id(), budget, reserve, cap, price, payments, qualified, supportedFirstPeriod && projection.determined() && projection.paidOff(), gaps, offer.evidence()));
      }
      var preferred = options.stream().filter(option -> option.state() == State.WITHIN_STATED_PLAN).min(Comparator.comparing(Option::totalCashOutlay));
      String recommendation = preferred.isPresent() ? "Conditionally prefer " + preferred.get().id() + " for the lowest projected cash outlay among supported options. Reconfirm source terms, intraday timing and unchanged commitments before the family acts." : "Do not commit based on these inputs. Resolve missing terms or reduce/defer the purchase until the dated plan supports it.";
      return new Result(budget.currency(), budget.purchaseDate(), price, budget.supportedCashBudget(), cap, preferred.map(Option::id).orElse(null), options, recommendation, "Daily/date-only estimates and human-supplied evidence; no rewards, issuer approval or intraday guarantee. Credit availability is not a budget. Financing does not increase the agreed purchase cap. No purchase, application or payment occurs.");
   }



   private static Option evaluate(String id, PurchaseAffordability.Budget budget, BigDecimal reserve, BigDecimal cap,
      BigDecimal price, List<Payment> payments, boolean termsQualified, boolean completeCost, List<String> inputGaps, String evidence)
   {
      var gaps = new ArrayList<>(inputGaps);
      BigDecimal total = payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      LocalDate last = payments.stream().filter(payment -> payment.amount().signum() > 0).map(Payment::date).max(Comparator.naturalOrder()).orElse(budget.purchaseDate());
      if(budget.baseline() == null || budget.supportedCashBudget() == null)
      {
         gaps.addAll(budget.limitations());
         return new Option(id, State.UNDETERMINED, completeCost ? total : null, completeCost ? total.subtract(price) : null, last, null, null, payments, gaps, evidence);
      }
      var days = budget.baseline().days();
      boolean complete = !last.isAfter(days.getLast().date()) && payments.stream().allMatch(payment -> !payment.date().isBefore(days.getFirst().date()));
      if(!complete)
      {
         gaps.add("Repayment extends outside the dated cash forecast; later essentials, reserves and debt goals are not covered.");
      }
      var due = new TreeMap<LocalDate, BigDecimal>();
      for(var payment : payments)
      {
         due.merge(payment.date(), payment.amount(), BigDecimal::add);
      }
      BigDecimal previous = days.getFirst().closingCash().subtract(days.getFirst().inflows()).add(days.getFirst().outflows());
      BigDecimal spent = BigDecimal.ZERO;
      BigDecimal minimum = previous;
      LocalDate shortfall = null;
      for(var day : days)
      {
         BigDecimal today = due.getOrDefault(day.date(), BigDecimal.ZERO);
         BigDecimal beforeIncome = previous.subtract(spent).subtract(today);
         spent = spent.add(today);
         BigDecimal after = day.closingCash().subtract(spent);
         BigDecimal conservative = beforeIncome.min(after);
         minimum = minimum.min(conservative);
         if(conservative.compareTo(reserve) < 0 && shortfall == null)
         {
            shortfall = day.date();
         }
         previous = day.closingCash();
      }
      State state = !termsQualified || !complete ? State.UNDETERMINED : shortfall != null || total.compareTo(cap) > 0 ? State.EXCEEDS_PLAN : State.WITHIN_STATED_PLAN;
      if(total.compareTo(cap) > 0)
      {
         gaps.add("Total purchase and financing cost exceeds the agreed discretionary cap.");
      }
      if(shortfall != null)
      {
         gaps.add("Payments breach the protected reserve under conservative same-day income ordering.");
      }
      if(id.equals("CASH") && price.compareTo(budget.supportedCashBudget()) > 0)
      {
         state = State.EXCEEDS_PLAN;
      }
      return new Option(id, state, completeCost ? total : null, completeCost ? total.subtract(price) : null, last, minimum, shortfall, payments, gaps, evidence);
   }
}
