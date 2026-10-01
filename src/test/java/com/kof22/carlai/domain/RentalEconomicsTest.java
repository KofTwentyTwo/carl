
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RentalEconomicsTest
{
   private static final LocalDate DAY = LocalDate.of(2027, 1, 1);
   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static RentalEconomics.Property property(String id, String currency, String ownership)
   {
      return new RentalEconomics.Property(id, currency, n(ownership));
   }



   private static RentalEconomics.Component part(String id, RentalEconomics.Kind kind, String amount, Map<String, BigDecimal> shares)
   {
      return new RentalEconomics.Component(id, kind, n(amount), shares, BigDecimal.ZERO);
   }



   private static RentalEconomics.Transaction tx(String id, String signed, RentalEconomics.Component... parts)
   {
      return new RentalEconomics.Transaction(id, DAY, "USD", n(signed), List.of(parts), "synthetic-row:" + id);
   }



   @Test
   void fullSourceAllocationIsStableAndValidatesConservation()
   {
      var component = part("rent", RentalEconomics.Kind.RENT_RECEIPT, "0.01", Map.of("1", n("0.5"), "2", n("0.5")));
      assertEquals(Map.of("1", n("0.01"), "2", n("0.00")), RentalEconomics.allocateComponent("USD", component));
      var outside = new RentalEconomics.Component("part", RentalEconomics.Kind.OPERATING_EXPENSE, n("1.01"), Map.of("property", n("0.5")), n("0.5"));
      assertEquals(n("0.50"), RentalEconomics.allocateComponent("USD", outside).get("property"));
      assertThrows(IllegalArgumentException.class, () -> RentalEconomics.allocateComponent("USD", part("invalid", RentalEconomics.Kind.RENT_RECEIPT, "1.00", Map.of("p", n("0.4")))));
      assertThrows(IllegalArgumentException.class, () -> RentalEconomics.allocateComponent("USD", part("precision", RentalEconomics.Kind.RENT_RECEIPT, "0.001", Map.of("p", BigDecimal.ONE))));
   }



   @Test
   void rentalReportSeparatesCollectedRentDebtCapitalReservesAndDeposits()
   {
      var shares = Map.of("house", BigDecimal.ONE);
      var transactions = List.of(
         tx("rent", "900.00", part("rent", RentalEconomics.Kind.RENT_RECEIPT, "900.00", shares)),
         tx("operations", "-200.00", part("repair", RentalEconomics.Kind.OPERATING_EXPENSE, "200.00", shares)),
         tx("mortgage", "-400.00", part("principal", RentalEconomics.Kind.DEBT_PRINCIPAL, "300.00", shares), part("interest", RentalEconomics.Kind.DEBT_INTEREST, "100.00", shares)),
         tx("capital", "-50.00", part("improvement", RentalEconomics.Kind.CAPITAL_EXPENDITURE, "50.00", shares)),
         tx("reserve", "-75.00", part("earmark", RentalEconomics.Kind.RESERVE_TRANSFER, "75.00", shares)),
         tx("deposit", "400.00", part("deposit", RentalEconomics.Kind.DEPOSIT_RECEIPT, "400.00", shares)));
      var due = new RentalEconomics.RentDue("jan", "house", DAY, n("1000.00"), "lease-row");
      var result = RentalEconomics.report(DAY, DAY.plusDays(30), DAY.plusDays(30), List.of(property("house", "USD", "0.5")), transactions,
         List.of(due), List.of(new RentalEconomics.RentApplication("jan", "rent", "rent", n("900.00"))), true);
      var actual = result.properties().getFirst().wholeProperty();
      assertEquals(n("100.00"), actual.unpaidRent());
      assertEquals(n("900.00"), actual.rentCollected());
      assertEquals(n("700.00"), actual.cashOperatingIncome());
      assertEquals(n("250.00"), actual.cashBeforeReserves());
      assertEquals(n("175.00"), actual.cashAfterReserveTransfers());
      assertEquals(n("400.00"), actual.restrictedDepositCash());
      assertEquals(n("87.50"), result.properties().getFirst().ownershipProportion().cashAfterReserveTransfers());
      assertEquals(n("175.00"), result.wholePropertyPortfolioByCurrency().get("USD").cashAfterReserveTransfers());
      assertTrue(result.completeForSuppliedScope());
   }



   @Test
   void allocationsConservePenniesAndSplitSourceNetRentPayout()
   {
      var half = Map.of("a", n("0.5"), "b", n("0.5"));
      var transactions = List.of(tx("shared-cost", "-0.01", part("cost", RentalEconomics.Kind.OPERATING_EXPENSE, "0.01", half)),
         tx("net-payout", "97.00", part("gross-rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", Map.of("a", BigDecimal.ONE)),
            part("fee", RentalEconomics.Kind.OPERATING_EXPENSE, "3.00", Map.of("a", BigDecimal.ONE))));
      var result = RentalEconomics.report(DAY, DAY.plusDays(30), DAY, List.of(property("a", "USD", "1"), property("b", "USD", "1")), transactions, List.of(), List.of(), true);
      assertEquals(n("3.01"), result.wholePropertyPortfolioByCurrency().get("USD").operatingExpenses());
      assertEquals(n("96.99"), result.wholePropertyPortfolioByCurrency().get("USD").cashOperatingIncome());
      assertEquals(n("3.01"), result.properties().get(0).wholeProperty().operatingExpenses());
      assertEquals(n("0.00"), result.properties().get(1).wholeProperty().operatingExpenses());
   }



   @Test
   void priorReceiptCanSettleCurrentRentWithoutAppearingAsCurrentCashCollected()
   {
      var receipt = new RentalEconomics.Transaction("advance", DAY.minusDays(1), "USD", n("500.00"),
         List.of(part("rent", RentalEconomics.Kind.RENT_RECEIPT, "500.00", Map.of("house", BigDecimal.ONE))), "prior-row");
      var result = RentalEconomics.report(DAY, DAY.plusDays(30), DAY, List.of(property("house", "USD", "1")), List.of(receipt),
         List.of(new RentalEconomics.RentDue("jan", "house", DAY, n("500.00"), "lease")),
         List.of(new RentalEconomics.RentApplication("jan", "advance", "rent", n("500.00"))), true);
      assertEquals(n("0.00"), result.properties().getFirst().wholeProperty().unpaidRent());
      assertEquals(n("0.00"), result.properties().getFirst().wholeProperty().rentCollected());
   }



   @Test
   void missingRentAndIncompleteCoverageAreNotZeroOrWholePortfolioClaims()
   {
      var result = RentalEconomics.report(DAY, DAY.plusDays(30), DAY, List.of(property("house", "USD", "1"), property("other", "EUR", "1")), List.of(),
         List.of(new RentalEconomics.RentDue("unknown", "house", DAY, null, "missing-lease")), List.of(), false);
      assertFalse(result.completeForSuppliedScope());
      assertTrue(result.exceptions().stream().anyMatch(s -> s.contains("unknown")));
      assertEquals(2, result.wholePropertyPortfolioByCurrency().size());
   }



   @Test
   void rejectsInventedSplitsDuplicateSourceCrossCurrencyAndOverAppliedRent()
   {
      var properties = List.of(property("house", "USD", "1"));
      var receipt = tx("rent", "100.00", part("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", Map.of("house", BigDecimal.ONE)));
      assertThrows(IllegalArgumentException.class, () -> RentalEconomics.report(DAY, DAY, DAY, properties,
         List.of(tx("bad", "-400.00", part("interest", RentalEconomics.Kind.DEBT_INTEREST, "400.00", Map.of("house", BigDecimal.ONE)), part("principal", RentalEconomics.Kind.DEBT_PRINCIPAL, "400.00", Map.of("house", BigDecimal.ONE)))), List.of(), List.of(), true));
      assertThrows(IllegalArgumentException.class, () -> RentalEconomics.report(DAY, DAY, DAY, properties, List.of(receipt, receipt), List.of(), List.of(), true));
      assertThrows(IllegalArgumentException.class, () -> RentalEconomics.report(DAY, DAY, DAY, List.of(property("house", "EUR", "1")), List.of(receipt), List.of(), List.of(), true));
      assertThrows(IllegalArgumentException.class, () -> RentalEconomics.report(DAY, DAY, DAY, properties, List.of(receipt),
         List.of(new RentalEconomics.RentDue("due", "house", DAY, n("150.00"), "lease")), List.of(new RentalEconomics.RentApplication("due", "rent", "rent", n("101.00"))), true));
   }



   @Test
   void outsideScopeCostsStayOutsidePortfolioAndDepositsNeverBecomeRent()
   {
      var partial = new RentalEconomics.Component("allocated", RentalEconomics.Kind.OPERATING_EXPENSE, n("100.00"),
         Map.of("house", n("0.25")), n("0.75"));
      var transactions = List.of(tx("cost", "-100.00", partial),
         tx("deposit", "50.00", part("deposit", RentalEconomics.Kind.DEPOSIT_RECEIPT, "50.00", Map.of("house", BigDecimal.ONE))),
         tx("return", "-20.00", part("refund", RentalEconomics.Kind.DEPOSIT_REFUND, "20.00", Map.of("house", BigDecimal.ONE))));
      var result = RentalEconomics.report(DAY, DAY, DAY, List.of(property("house", "USD", "1")), transactions, List.of(), List.of(), true);
      var metrics = result.properties().getFirst().wholeProperty();
      assertEquals(n("25.00"), metrics.operatingExpenses());
      assertEquals(n("30.00"), metrics.restrictedDepositCash());
      assertEquals(n("0.00"), metrics.rentCollected());
      assertEquals(n("-25.00"), metrics.cashBeforeReserves());
      assertEquals(3, result.properties().getFirst().sourceReferences().size());
   }



   @Test
   void roundedOwnershipComponentsReconcileToDerivedCashFigures()
   {
      var shares = Map.of("house", BigDecimal.ONE);
      var result = RentalEconomics.report(DAY, DAY, DAY, List.of(property("house", "USD", "0.5")),
         List.of(tx("rent", "0.03", part("rent", RentalEconomics.Kind.RENT_RECEIPT, "0.03", shares)),
            tx("cost", "-0.01", part("cost", RentalEconomics.Kind.OPERATING_EXPENSE, "0.01", shares))),
         List.of(), List.of(), true);
      var owned = result.properties().getFirst().ownershipProportion();
      assertEquals(n("0.02"), owned.rentCollected());
      assertEquals(n("0.00"), owned.operatingExpenses());
      assertEquals(owned.rentCollected().subtract(owned.operatingExpenses()), owned.cashOperatingIncome());
   }
}
