
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/*******************************************************************************
 ** Source-reconciled rental cash reporting, without assuming tax treatment.
 ** Inputs and allocation details must be filtered by authorized domain services.
 ******************************************************************************/
public final class RentalEconomics
{
   /** Explicit rental cash classifications; tax treatment remains separate. */
   public enum Kind
   {
      RENT_RECEIPT, OPERATING_EXPENSE, DEBT_PRINCIPAL, DEBT_INTEREST, CAPITAL_EXPENDITURE, RESERVE_TRANSFER, DEPOSIT_RECEIPT, DEPOSIT_REFUND
   }



   /** Property currency and evidenced ownership fraction. */
   public record Property(String id, String currency, BigDecimal ownershipFraction)
   {
   }



   /** One reconciled cash component with explicit property and outside allocations. */
   public record Component(String id, Kind kind, BigDecimal amount,
      Map<String, BigDecimal> propertyFractions, BigDecimal outsideScopeFraction)
   {
      /** Retains immutable input collections. */
      public Component
      {
         propertyFractions = Map.copyOf(propertyFractions);
      }
   }



   /** Source cash movement whose components must conserve its signed amount. */
   public record Transaction(String id, LocalDate date, String currency, BigDecimal signedAmount,
      List<Component> components, String source)
   {
      /** Retains immutable input collections. */
      public Transaction
      {
         components = List.copyOf(components);
      }
   }



   /** Known scheduled rent for one property and due date. */
   public record RentDue(String id, String property, LocalDate due, BigDecimal amount, String source)
   {
   }



   /** Evidenced allocation of an actual receipt to scheduled rent. */
   public record RentApplication(String rentDue, String transaction, String component, BigDecimal amount)
   {
   }



   /** Separate scheduled, received, operating, financing, capital and reserve figures. */
   public record Metrics(BigDecimal knownScheduledRent, BigDecimal knownDueRent, BigDecimal rentCollected,
      BigDecimal appliedRent, BigDecimal unpaidRent, BigDecimal unappliedReceipts,
      BigDecimal operatingExpenses, BigDecimal debtPrincipal, BigDecimal debtInterest,
      BigDecimal capitalSpending, BigDecimal reserveTransfers, BigDecimal restrictedDepositCash,
      BigDecimal cashOperatingIncome, BigDecimal cashBeforeReserves, BigDecimal cashAfterReserveTransfers)
   {
   }



   /*******************************************************************************
    ** Ownership proportion is a reporting attribution, not a promised distribution.
    ******************************************************************************/
   /** Property currency and evidenced ownership fraction. */
   /** Whole-property economics and ownership-attributed figures with sources. */
   public record PropertyResult(String property, String currency, Metrics wholeProperty,
      Metrics ownershipProportion, Set<String> sourceReferences)
   {
      /** Retains immutable input collections. */
      public PropertyResult
      {
         sourceReferences = Set.copyOf(sourceReferences);
      }
   }



   /** Currency-separated property results and coverage limitations. */
   public record Report(List<PropertyResult> properties, Map<String, Metrics> wholePropertyPortfolioByCurrency,
      boolean completeForSuppliedScope, List<String> exceptions)
   {
      /** Retains immutable validated input collections. */
      public Report
      {
         properties = List.copyOf(properties);
         wholePropertyPortfolioByCurrency = Map.copyOf(wholePropertyPortfolioByCurrency);
         exceptions = List.copyOf(exceptions);
      }
   }



   private record Receipt(String transaction, String component, String property)
   {
   }



   private record Weighted(String property, BigDecimal units, BigDecimal fraction)
   {
   }
   private static final String OUTSIDE = "[outside-scope]";

   private static final class Accumulator
   {
      private final BigDecimal zero;
      private final EnumMap<Kind, BigDecimal> cash = new EnumMap<>(Kind.class);
      private final Set<String> sources = new HashSet<>();
      private BigDecimal scheduled;
      private BigDecimal due;
      private BigDecimal applied;
      private BigDecimal receiptApplications;
      private Accumulator(int scale)
      {
         zero = BigDecimal.ZERO.setScale(scale);
         scheduled = zero;
         due = zero;
         applied = zero;
         receiptApplications = zero;
      }



      private BigDecimal get(Kind kind)
      {
         return cash.getOrDefault(kind, zero);
      }



      private Metrics metrics()
      {
         BigDecimal rent = get(Kind.RENT_RECEIPT);
         BigDecimal operating = rent.subtract(get(Kind.OPERATING_EXPENSE));
         BigDecimal before = operating.subtract(get(Kind.DEBT_PRINCIPAL)).subtract(get(Kind.DEBT_INTEREST)).subtract(get(Kind.CAPITAL_EXPENDITURE));
         return new Metrics(scheduled, due, rent, applied, due.subtract(applied), rent.subtract(receiptApplications),
            get(Kind.OPERATING_EXPENSE), get(Kind.DEBT_PRINCIPAL), get(Kind.DEBT_INTEREST), get(Kind.CAPITAL_EXPENDITURE),
            get(Kind.RESERVE_TRANSFER), get(Kind.DEPOSIT_RECEIPT).subtract(get(Kind.DEPOSIT_REFUND)),
            operating, before, before.subtract(get(Kind.RESERVE_TRANSFER)));
      }
   }

   private RentalEconomics()
   {
   }



   /*******************************************************************************
    ** Allocates the complete source component before any caller filters its scope.
    ** Results conserve original cents; ownership and selected report scope do not affect them.
    ******************************************************************************/
   public static Map<String, BigDecimal> allocateComponent(String currency, Component component)
   {
      if(currency == null || component == null || component.propertyFractions().size() > 100)
      {
         throw new IllegalArgumentException("Explicit currency and bounded component allocations are required");
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported allocation currency precision");
      }
      BigDecimal amount = money(component.amount(), scale, false);
      fraction(component.outsideScopeFraction());
      BigDecimal total = component.outsideScopeFraction();
      for(var share : component.propertyFractions().entrySet())
      {
         if(!id(share.getKey()) || OUTSIDE.equals(share.getKey()) || share.getValue().signum() <= 0)
         {
            throw new IllegalArgumentException("Allocation requires explicit property identities and positive shares");
         }
         fraction(share.getValue());
         total = total.add(share.getValue());
      }
      if(total.compareTo(BigDecimal.ONE) != 0)
      {
         throw new IllegalArgumentException("Allocation fractions must sum to one");
      }
      return Map.copyOf(allocate(amount, scale, component));
   }



   /*******************************************************************************
    ** One signed source row may have several evidenced components, whose signs must
    ** reconcile to that row. Principal, capital and reserves are not operating expenses.
    ******************************************************************************/
   public static Report report(LocalDate from, LocalDate through, LocalDate asOf,
      List<Property> properties, List<Transaction> transactions, List<RentDue> rents,
      List<RentApplication> applications, boolean sourceCoverageComplete)
   {
      return calculate(from, through, asOf, properties, transactions, rents, applications, sourceCoverageComplete, 100, 10_000_000);
   }



   // Only Carl's service-created fixed-cent projections use this bounded expanded representation.
   static Report reportProjected(LocalDate from, LocalDate through, LocalDate asOf,
      List<Property> properties, List<Transaction> transactions, List<RentDue> rents,
      List<RentApplication> applications, boolean sourceCoverageComplete)
   {
      return calculate(from, through, asOf, properties, transactions, rents, applications, sourceCoverageComplete, 10_100, 100_000);
   }



   private static Report calculate(LocalDate from, LocalDate through, LocalDate asOf,
      List<Property> properties, List<Transaction> transactions, List<RentDue> rents,
      List<RentApplication> applications, boolean sourceCoverageComplete, int componentLimit, int totalComponentLimit)
   {
      validDate(from);
      validDate(through);
      validDate(asOf);
      if(from.isAfter(through) || ChronoUnit.DAYS.between(from, through) >= 366
         || properties == null || properties.isEmpty() || properties.size() > 100
         || transactions == null || transactions.size() > 100_000 || rents == null || rents.size() > 20_000
         || applications == null || applications.size() > 100_000)
      {
         throw new IllegalArgumentException("A 1–366 day interval and bounded rental inputs are required");
      }
      var propertyMap = new HashMap<String, Property>();
      var totals = new HashMap<String, Accumulator>();
      var scales = new HashMap<String, Integer>();
      for(var property : properties)
      {
         if(property == null || !id(property.id()) || OUTSIDE.equals(property.id()) || propertyMap.putIfAbsent(property.id(), property) != null
            || property.currency() == null)
         {
            throw new IllegalArgumentException("Rental properties require unique IDs and explicit currencies");
         }
         fraction(property.ownershipFraction());
         int scale = Currency.getInstance(property.currency()).getDefaultFractionDigits();
         if(scale < 0 || scale > 4)
         {
            throw new IllegalArgumentException("Unsupported rental currency precision");
         }
         scales.put(property.currency(), scale);
         totals.put(property.id(), new Accumulator(scale));
      }
      var rows = new HashMap<String, Transaction>();
      var receipts = new HashMap<Receipt, BigDecimal>();
      long totalComponents = 0;
      for(var row : transactions)
      {
         if(row == null || !id(row.id()) || !id(row.source()) || rows.putIfAbsent(row.id(), row) != null
            || !scales.containsKey(row.currency()) || row.components().isEmpty() || row.components().size() > componentLimit)
         {
            throw new IllegalArgumentException("Source rows require unique identities, evidence and bounded components");
         }
         totalComponents += row.components().size();
         if(totalComponents > totalComponentLimit)
         {
            throw new IllegalArgumentException("Total rental component bound exceeded; narrow the report interval or scope");
         }
         validDate(row.date());
         if(row.date().isAfter(asOf))
         {
            throw new IllegalArgumentException("Future receipts/costs are scenarios, not actuals");
         }
         int scale = scales.get(row.currency());
         BigDecimal original = money(row.signedAmount(), scale, true);
         BigDecimal sum = BigDecimal.ZERO.setScale(scale);
         var componentIds = new HashSet<String>();
         for(var part : row.components())
         {
            if(part == null || !id(part.id()) || !componentIds.add(part.id()) || part.kind() == null
               || part.propertyFractions().size() > 100)
            {
               throw new IllegalArgumentException("Source components require unique IDs and explicit types");
            }
            BigDecimal amount = money(part.amount(), scale, false);
            if(amount.signum() <= 0)
            {
               throw new IllegalArgumentException("Source components must be positive magnitudes");
            }
            sum = sum.add(incoming(part.kind()) ? amount : amount.negate());
            fraction(part.outsideScopeFraction());
            BigDecimal fractionSum = part.outsideScopeFraction();
            for(var share : part.propertyFractions().entrySet())
            {
               Property property = propertyMap.get(share.getKey());
               fraction(share.getValue());
               if(property == null || !property.currency().equals(row.currency()) || share.getValue().signum() <= 0)
               {
                  throw new IllegalArgumentException("Allocations require authorized properties in the source currency");
               }
               fractionSum = fractionSum.add(share.getValue());
            }
            if(fractionSum.compareTo(BigDecimal.ONE) != 0)
            {
               throw new IllegalArgumentException("Property and outside-scope fractions must sum to one");
            }
            Map<String, BigDecimal> allocated = allocate(amount, scale, part);
            for(var entry : allocated.entrySet())
            {
               Accumulator acc = totals.get(entry.getKey());
               if(part.kind() == Kind.RENT_RECEIPT)
               {
                  receipts.put(new Receipt(row.id(), part.id(), entry.getKey()), entry.getValue());
               }
               if(inWindow(row.date(), from, through))
               {
                  acc.cash.merge(part.kind(), entry.getValue(), BigDecimal::add);
                  acc.sources.add(row.source());
               }
            }
         }
         if(original.compareTo(sum) != 0)
         {
            throw new IllegalArgumentException("Source components do not reconcile to the signed transaction");
         }
      }
      var dueMap = new HashMap<String, RentDue>();
      var exceptions = new ArrayList<String>();
      if(!sourceCoverageComplete)
      {
         exceptions.add("Source coverage is partial; totals cover only supplied authorized properties and records");
      }
      for(var rent : rents)
      {
         if(rent == null || !id(rent.id()) || !id(rent.source()) || !propertyMap.containsKey(rent.property()) || dueMap.putIfAbsent(rent.id(), rent) != null)
         {
            throw new IllegalArgumentException("Rent schedules require unique IDs, authorized property and evidence");
         }
         validDate(rent.due());
         int scale = scales.get(propertyMap.get(rent.property()).currency());
         BigDecimal amount = rent.amount() == null ? null : money(rent.amount(), scale, false);
         if(inWindow(rent.due(), from, through))
         {
            Accumulator acc = totals.get(rent.property());
            acc.sources.add(rent.source());
            if(amount == null)
            {
               exceptions.add("Missing scheduled rent amount: " + rent.id());
            }
            else
            {
               acc.scheduled = acc.scheduled.add(amount);
               if(!rent.due().isAfter(asOf))
               {
                  acc.due = acc.due.add(amount);
               }
            }
         }
      }
      var appliedReceipt = new HashMap<Receipt, BigDecimal>();
      var appliedDue = new HashMap<String, BigDecimal>();
      var links = new HashSet<List<String>>();
      for(var link : applications)
      {
         if(link == null || !id(link.rentDue()) || !id(link.transaction()) || !id(link.component())
            || !links.add(List.of(link.rentDue(), link.transaction(), link.component())))
         {
            throw new IllegalArgumentException("Rent applications require explicit unique links");
         }
         RentDue rent = dueMap.get(link.rentDue());
         Transaction row = rows.get(link.transaction());
         if(rent == null || rent.amount() == null || row == null)
         {
            throw new IllegalArgumentException("Applications require known rent and source receipt");
         }
         int scale = scales.get(propertyMap.get(rent.property()).currency());
         BigDecimal amount = money(link.amount(), scale, false);
         Receipt ref = new Receipt(link.transaction(), link.component(), rent.property());
         BigDecimal capacity = receipts.get(ref);
         if(amount.signum() <= 0 || capacity == null
            || appliedReceipt.merge(ref, amount, BigDecimal::add).compareTo(capacity) > 0
            || appliedDue.merge(rent.id(), amount, BigDecimal::add).compareTo(rent.amount()) > 0)
         {
            throw new IllegalArgumentException("Rent application exceeds the allocated receipt or scheduled rent");
         }
         Accumulator acc = totals.get(rent.property());
         if(inWindow(row.date(), from, through))
         {
            acc.receiptApplications = acc.receiptApplications.add(amount);
         }
         if(inWindow(rent.due(), from, through) && !rent.due().isAfter(asOf))
         {
            acc.applied = acc.applied.add(amount);
            acc.sources.add(row.source());
         }
      }
      var results = new ArrayList<PropertyResult>();
      var portfolio = new HashMap<String, Metrics>();
      for(var property : properties.stream().sorted(Comparator.comparing(Property::id)).toList())
      {
         Accumulator acc = totals.get(property.id());
         Metrics metrics = acc.metrics();
         results.add(new PropertyResult(property.id(), property.currency(), metrics,
            scale(metrics, property.ownershipFraction(), scales.get(property.currency())), acc.sources));
         portfolio.merge(property.currency(), metrics, RentalEconomics::add);
      }
      return new Report(results, portfolio, exceptions.isEmpty(), exceptions);
   }



   private static Map<String, BigDecimal> allocate(BigDecimal amount, int scale, Component part)
   {
      var fractions = new HashMap<>(part.propertyFractions());
      fractions.put(OUTSIDE, part.outsideScopeFraction());
      var weights = new ArrayList<Weighted>();
      BigDecimal units = amount.movePointRight(scale);
      BigDecimal floorTotal = BigDecimal.ZERO;
      for(var entry : fractions.entrySet())
      {
         BigDecimal exact = units.multiply(entry.getValue());
         BigDecimal floor = exact.setScale(0, RoundingMode.DOWN);
         floorTotal = floorTotal.add(floor);
         weights.add(new Weighted(entry.getKey(), floor, exact.subtract(floor)));
      }
      // Largest-remainder allocation conserves source minor units; stable IDs settle ties.
      weights.sort(Comparator.comparing(Weighted::fraction).reversed().thenComparing(Weighted::property));
      int remainder = units.subtract(floorTotal).intValueExact();
      var allocated = new HashMap<String, BigDecimal>();
      for(int i = 0; i < weights.size(); i++)
      {
         Weighted weight = weights.get(i);
         if(!OUTSIDE.equals(weight.property()))
         {
            allocated.put(weight.property(), weight.units().add(i < remainder ? BigDecimal.ONE : BigDecimal.ZERO).movePointLeft(scale).setScale(scale));
         }
      }
      return allocated;
   }



   private static boolean incoming(Kind kind)
   {
      return kind == Kind.RENT_RECEIPT || kind == Kind.DEPOSIT_RECEIPT;
   }



   private static boolean inWindow(LocalDate date, LocalDate from, LocalDate through)
   {
      return !date.isBefore(from) && !date.isAfter(through);
   }



   private static boolean id(String value)
   {
      return value != null && !value.isBlank() && value.length() <= 200;
   }



   private static void validDate(LocalDate date)
   {
      if(date == null || date.getYear() < 1900 || date.getYear() > 2300)
      {
         throw new IllegalArgumentException("An explicit bounded date is required");
      }
   }



   private static void fraction(BigDecimal fraction)
   {
      if(fraction == null || fraction.precision() > 18 || Math.abs((long) fraction.scale()) > 12
         || fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0)
      {
         throw new IllegalArgumentException("An explicit allocation/ownership fraction from zero through one is required");
      }
   }



   private static BigDecimal money(BigDecimal value, int scale, boolean signed)
   {
      if(value == null || !signed && value.signum() < 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18
         || value.abs().compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("An explicit bounded monetary amount is required");
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



   private static Metrics scale(Metrics m, BigDecimal fraction, int scale)
   {
      BigDecimal scheduled = proportion(m.knownScheduledRent(), fraction, scale);
      BigDecimal due = proportion(m.knownDueRent(), fraction, scale);
      BigDecimal rent = proportion(m.rentCollected(), fraction, scale);
      BigDecimal applied = proportion(m.appliedRent(), fraction, scale);
      BigDecimal expense = proportion(m.operatingExpenses(), fraction, scale);
      BigDecimal principal = proportion(m.debtPrincipal(), fraction, scale);
      BigDecimal interest = proportion(m.debtInterest(), fraction, scale);
      BigDecimal capital = proportion(m.capitalSpending(), fraction, scale);
      BigDecimal reserve = proportion(m.reserveTransfers(), fraction, scale);
      BigDecimal appliedReceipts = proportion(m.rentCollected().subtract(m.unappliedReceipts()), fraction, scale);
      BigDecimal operating = rent.subtract(expense);
      BigDecimal before = operating.subtract(principal).subtract(interest).subtract(capital);
      // Rounding primitive amounts first keeps every displayed derived identity consistent.
      return new Metrics(scheduled, due, rent, applied, due.subtract(applied), rent.subtract(appliedReceipts),
         expense, principal, interest, capital, reserve, proportion(m.restrictedDepositCash(), fraction, scale),
         operating, before, before.subtract(reserve));
   }



   private static BigDecimal proportion(BigDecimal value, BigDecimal fraction, int scale)
   {
      return value.multiply(fraction).setScale(scale, RoundingMode.HALF_EVEN);
   }



   private static Metrics add(Metrics a, Metrics b)
   {
      return new Metrics(a.knownScheduledRent().add(b.knownScheduledRent()), a.knownDueRent().add(b.knownDueRent()), a.rentCollected().add(b.rentCollected()),
         a.appliedRent().add(b.appliedRent()), a.unpaidRent().add(b.unpaidRent()), a.unappliedReceipts().add(b.unappliedReceipts()),
         a.operatingExpenses().add(b.operatingExpenses()), a.debtPrincipal().add(b.debtPrincipal()), a.debtInterest().add(b.debtInterest()),
         a.capitalSpending().add(b.capitalSpending()), a.reserveTransfers().add(b.reserveTransfers()), a.restrictedDepositCash().add(b.restrictedDepositCash()),
         a.cashOperatingIncome().add(b.cashOperatingIncome()), a.cashBeforeReserves().add(b.cashBeforeReserves()), a.cashAfterReserveTransfers().add(b.cashAfterReserveTransfers()));
   }
}
