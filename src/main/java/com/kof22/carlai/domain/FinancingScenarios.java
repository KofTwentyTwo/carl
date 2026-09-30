
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


/***************************************************************************
 ** Monthly financing estimates for an isolated balance with explicit terms.
 ** No eligibility, approval, contract acceptance or financial action is implied.
 ** Deferred-interest support uses noncompounding accrual on opening unpaid
 ** principal, with all payments allocated to this isolated promotional balance.
 ***************************************************************************/
public final class FinancingScenarios
{
   /** Distinguishes hypothetical assumptions from supplied verified contract terms. */
   public enum Evidence
   {
      HYPOTHETICAL, VERIFIED_TERMS
   }



   /** Separates true zero-interest offers from conditional deferred-interest offers. */
   public enum PromotionKind
   {
      NONE, TRUE_ZERO, DEFERRED_INTEREST
   }



   /** Explicit promotional duration, accrual rule and payment-allocation confirmation. */
   public record Promotion(PromotionKind kind, int months, BigDecimal deferredAnnualRate, boolean allocationTermsConfirmed)
   {
      /** Validates bounded promotional assumptions. */
      public Promotion
      {
         if(kind == null || months < 0 || months > 599 || deferredAnnualRate == null || deferredAnnualRate.signum() < 0
            || deferredAnnualRate.compareTo(BigDecimal.valueOf(5)) > 0 || deferredAnnualRate.precision() > 12
            || deferredAnnualRate.scale() < 0 || deferredAnnualRate.scale() > 12
            || kind == PromotionKind.NONE && months != 0 || kind != PromotionKind.NONE && months == 0)
         {
            throw new IllegalArgumentException("Promotion needs an explicit bounded duration and decimal accrual APR");
         }
      }



      /** Creates an explicitly nonpromotional rate structure. */
      public static Promotion none()
      {
         return new Promotion(PromotionKind.NONE, 0, BigDecimal.ZERO, true);
      }
   }



   /** Complete bounded financing assumptions with separately financed and upfront fees. */
   public record Offer(String id, String currency, BigDecimal principal, BigDecimal financedFee, BigDecimal cashFee,
      BigDecimal monthlyPayment, int termMonths, List<FinancialPlanning.Rate> rates, Promotion promotion, Evidence evidence)
   {
      /** Validates and canonicalizes term, payment, promotion and rate assumptions. */
      public Offer
      {
         int scale = scale(currency);
         principal = money(principal, scale);
         financedFee = money(financedFee, scale);
         cashFee = money(cashFee, scale);
         monthlyPayment = money(monthlyPayment, scale);
         if(id == null || id.isBlank() || id.length() > 200 || principal.signum() == 0 || monthlyPayment.signum() == 0
            || termMonths < 1 || termMonths > 600 || rates == null || rates.isEmpty() || rates.size() > 600 || rates.stream().anyMatch(rate -> rate == null) || promotion == null || evidence == null
            || promotion.months() > termMonths)
         {
            throw new IllegalArgumentException("Financing requires identity, principal, payment, term and complete rate/promotion assumptions");
         }
         rates = rates.stream().sorted(Comparator.comparingInt(FinancialPlanning.Rate::firstMonth)).toList();
         var starts = new HashSet<Integer>();
         if(rates.getFirst().firstMonth() != 1)
         {
            throw new IllegalArgumentException("Financing rate schedule must cover its first month");
         }
         for(var rate : rates)
         {
            if(!starts.add(rate.firstMonth()) || rate.firstMonth() > Math.max(termMonths, promotion.months() + 1))
            {
               throw new IllegalArgumentException("Financing rate effective months must be unique and within term");
            }
            if(promotion.kind() != PromotionKind.NONE && rate.firstMonth() <= promotion.months() && rate.annualRate().signum() != 0)
            {
               throw new IllegalArgumentException("The supplied zero/deferred promotion must have zero billed APR during its promotional period");
            }
         }
         if(promotion.kind() != PromotionKind.NONE && rates.stream().noneMatch(rate -> rate.firstMonth() == promotion.months() + 1))
         {
            throw new IllegalArgumentException("An explicit post-promotion rate is required");
         }
      }
   }



   /** One dated payment period with separate deferred-interest charges and residual balance. */
   public record Month(int number, LocalDate paymentDate, BigDecimal openingBalance, BigDecimal interest,
      BigDecimal deferredInterestCharged, BigDecimal payment, BigDecimal closingBalance, BigDecimal pendingDeferredInterest)
   {
   }



   /** Cost, payoff and residual outcome with evidence classification and limitations. */
   public record Projection(boolean determined, boolean paidOff, LocalDate payoffDate, BigDecimal totalInterest, BigDecimal totalFees,
      BigDecimal cashPaid, BigDecimal balloonDue, BigDecimal pendingDeferredInterest, Evidence evidence,
      boolean negativeAmortization, List<Month> months, String limitations)
   {
      /** Copies the immutable payment schedule. */
      public Projection
      {
         months = List.copyOf(months);
      }
   }



   /** Conserves source principal across retained debts and the financed destination. */
   public record TransferPlan(List<FinancialPlanning.Debt> retainedDebts, Offer destination, BigDecimal originalPrincipal,
      BigDecimal totalDebtAfterFees, BigDecimal upfrontCashFee)
   {
      /** Copies retained source balances after explicit allocations. */
      public TransferPlan
      {
         retainedDebts = List.copyOf(retainedDebts);
      }
   }

   private FinancingScenarios()
   {
   }



   /** Calculates a bounded offer projection; incomplete allocation terms remain undetermined. */
   public static Projection project(Offer offer, LocalDate effectiveDate)
   {
      return projection(offer, effectiveDate, false);
   }



   /** Uses an explicit first payment date, preserving its day-of-month across later months. */
   public static Projection projectFromFirstPayment(Offer offer, LocalDate firstPayment)
   {
      return projection(offer, firstPayment, true);
   }



   private static Projection projection(Offer offer, LocalDate effectiveDate, boolean firstPayment)
   {
      if(offer == null || effectiveDate == null || effectiveDate.getYear() < 1900 || effectiveDate.getYear() > 2200)
      {
         throw new IllegalArgumentException("Financing requires explicit terms and a bounded effective date");
      }
      if(offer.promotion().kind() == PromotionKind.DEFERRED_INTEREST && !offer.promotion().allocationTermsConfirmed())
      {
         return new Projection(false, false, null, null, null, null, null, null, offer.evidence(), false, List.of(),
            "Precise deferred-interest comparison unavailable: allocation/accrual terms for the isolated promotional balance are unconfirmed.");
      }
      int scale = scale(offer.currency());
      BigDecimal zero = BigDecimal.ZERO.setScale(scale);
      BigDecimal balance = offer.principal().add(offer.financedFee());
      BigDecimal totalInterest = zero;
      BigDecimal cashPaid = offer.cashFee();
      BigDecimal pending = zero;
      var months = new ArrayList<Month>();
      boolean negative = false;
      for(int number = 1; number <= offer.termMonths() && balance.signum() > 0; number++)
      {
         BigDecimal opening = balance;
         BigDecimal interest = monthlyInterest(balance, rateAt(offer.rates(), number), scale);
         boolean deferredPeriod = offer.promotion().kind() == PromotionKind.DEFERRED_INTEREST && number <= offer.promotion().months();
         if(deferredPeriod)
         {
            pending = pending.add(monthlyInterest(opening, offer.promotion().deferredAnnualRate(), scale));
         }
         balance = balance.add(interest);
         BigDecimal payment = offer.monthlyPayment().min(balance);
         balance = balance.subtract(payment);
         BigDecimal deferredCharged = zero;
         if(balance.signum() == 0)
         {
            pending = zero;
         }
         else if(deferredPeriod && number == offer.promotion().months())
         {
            deferredCharged = pending;
            balance = balance.add(pending);
            pending = zero;
         }
         totalInterest = totalInterest.add(interest).add(deferredCharged);
         cashPaid = cashPaid.add(payment);
         negative |= balance.compareTo(opening) > 0;
         months.add(new Month(number, effectiveDate.plusMonths(firstPayment ? number - 1L : number), opening, interest, deferredCharged, payment, balance, pending));
      }
      boolean paid = balance.signum() == 0;
      return new Projection(true, paid, paid ? months.getLast().paymentDate() : null, totalInterest,
         offer.financedFee().add(offer.cashFee()), cashPaid, balance, pending, offer.evidence(), negative, months,
         "Monthly rounded estimate; payments one month after effective date. No daily issuer accrual/grace/rewards/eligibility assumed. "
            + "Deferred interest, where selected, accrues without compounding on the isolated unpaid opening balance including financed fees. "
            + "Residual at term is due, not paid off. Collateral, affordability and actual offer approval require separate evidence.");
   }



   /** Applies explicit partial allocations without inventing available credit or duplicating principal. */
   public static TransferPlan transfer(List<FinancialPlanning.Debt> debts, Map<String, BigDecimal> allocations, Offer destination, BigDecimal capacity)
   {
      if(debts == null || debts.isEmpty() || debts.size() > 100 || allocations == null || allocations.isEmpty() || destination == null)
      {
         throw new IllegalArgumentException("A bounded authorized debt set and explicit transfer allocations are required");
      }
      int scale = scale(destination.currency());
      capacity = money(capacity, scale);
      var identities = new HashSet<String>();
      var retained = new ArrayList<FinancialPlanning.Debt>();
      BigDecimal total = BigDecimal.ZERO.setScale(scale);
      BigDecimal transferred = BigDecimal.ZERO.setScale(scale);
      for(var debt : debts)
      {
         if(debt == null || !identities.add(debt.id()) || !destination.currency().equals(debt.currency()) || destination.id().equals(debt.id()))
         {
            throw new IllegalArgumentException("Transfer sources require unique identities and the destination currency");
         }
         BigDecimal amount = money(allocations.getOrDefault(debt.id(), BigDecimal.ZERO), scale);
         if(amount.compareTo(debt.balance()) > 0)
         {
            throw new IllegalArgumentException("Transfer allocation exceeds a source balance");
         }
         total = total.add(debt.balance());
         transferred = transferred.add(amount);
         retained.add(new FinancialPlanning.Debt(debt.id(), debt.currency(), debt.balance().subtract(amount), debt.minimum(),
            debt.minimumFraction(), debt.rates(), debt.monthlyFee()));
      }
      if(!identities.containsAll(allocations.keySet()) || transferred.compareTo(destination.principal()) != 0
         || transferred.add(destination.financedFee()).compareTo(capacity) > 0)
      {
         throw new IllegalArgumentException("Transfers must conserve principal and fit the explicit destination capacity including financed fees");
      }
      retained.sort(Comparator.comparing(FinancialPlanning.Debt::id));
      return new TransferPlan(retained, destination, total, total.add(destination.financedFee()), destination.cashFee());
   }



   private static BigDecimal rateAt(List<FinancialPlanning.Rate> rates, int month)
   {
      BigDecimal rate = rates.getFirst().annualRate();
      for(var segment : rates)
      {
         if(segment.firstMonth() > month)
         {
            break;
         }
         rate = segment.annualRate();
      }
      return rate;
   }



   private static BigDecimal monthlyInterest(BigDecimal principal, BigDecimal rate, int scale)
   {
      return principal.multiply(rate).divide(BigDecimal.valueOf(12), scale, RoundingMode.HALF_UP);
   }



   private static int scale(String currency)
   {
      if(currency == null)
      {
         throw new IllegalArgumentException("An explicit supported currency is required");
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
         throw new IllegalArgumentException("A bounded nonnegative monetary amount is required");
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
