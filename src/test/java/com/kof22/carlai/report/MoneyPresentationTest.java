/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.report;


import java.math.BigDecimal;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MoneyPresentationTest
{
   @Test
   void budgetVarianceDisplaysEveryMonetaryFactWithoutFormattingCounts() throws Exception
   {
      var facts = new ObjectMapper().readTree("{\"currency\":\"USD\",\"budget\":8100.25,\"actualSpending\":2000.75,\"remainingBudget\":6099.50,\"unclassifiedCount\":8100}");
      var display = MoneyPresentation.humanFacts(facts);
      assertEquals("$8,100.25 USD", display.path("budget").asText());
      assertEquals("$2,000.75 USD", display.path("actualSpending").asText());
      assertEquals("$6,099.50 USD", display.path("remainingBudget").asText());
      assertEquals(8100, display.path("unclassifiedCount").asInt());
      assertTrue(facts.path("budget").isNumber());
   }



   @Test
   void usesExplicitGroupingLocaleAndPreservesMeaningfulExtraPrecision()
   {
      Locale previous = Locale.getDefault();
      try
      {
         Locale.setDefault(Locale.GERMANY);
         assertEquals("$8,100.00 USD", MoneyPresentation.format(new BigDecimal("8100.0000"), "USD"));
         assertEquals("-$8,100.125 USD", MoneyPresentation.format(new BigDecimal("-8100.1250"), "USD"));
         assertEquals("£8,100.00 GBP", MoneyPresentation.format(new BigDecimal("8100"), "GBP"));
         assertEquals("8,100.00 — currency not supplied", MoneyPresentation.format(new BigDecimal("8100"), "BAD"));
         assertEquals("Not supplied", MoneyPresentation.format(null, "USD"));
      }
      finally
      {
         Locale.setDefault(previous);
      }
   }



   @Test
   void actualOfferCashStressAndPortfolioSchemasCarryTheirKnownCurrencyIntoEveryMonetaryFigure() throws Exception
   {
      String offer = "{\"comparisons\":[{\"terms\":{\"currency\":\"USD\",\"financed_principal\":8100},\"projection\":{\"totalInterest\":8100,\"schedule\":[{\"openingBalance\":8100,\"payment\":25}]}}]}";
      String cash = "{\"budget\":{\"currency\":\"USD\"},\"sourcePlan\":{\"currency\":\"USD\"},\"allInPrice\":8100,\"protectedReserve\":8100,\"openingExpenseReserveEarmarks\":8100}";
      String stress = "{\"currency\":\"USD\",\"hypotheticalVacancyLoss\":8100,\"additionalRepair\":8100,\"totalAdverseCashImpact\":8100,\"ownershipAttributedImpact\":8100,\"ownershipFraction\":0.5}";
      String portfolio = "{\"inputs\":{\"currency\":\"USD\"},\"moves\":[{\"offer\":{\"currency\":\"USD\",\"principal\":8100},\"allocations\":{\"debt-123\":8100}}],\"maturityResiduals\":{\"loan-123\":8100}}";
      var json = new ObjectMapper();
      var offerView = MoneyPresentation.humanFacts(json.readTree(offer)).path("comparisons").get(0);
      assertEquals("$8,100.00 USD", offerView.path("projection").path("totalInterest").asText());
      assertEquals("$8,100.00 USD", offerView.path("projection").path("schedule").get(0).path("openingBalance").asText());
      var cashView = MoneyPresentation.humanFacts(json.readTree(cash));
      for(String field : java.util.List.of("allInPrice", "protectedReserve", "openingExpenseReserveEarmarks"))
      {
         assertEquals("$8,100.00 USD", cashView.path(field).asText(), field);
      }
      var stressView = MoneyPresentation.humanFacts(json.readTree(stress));
      for(String field : java.util.List.of("hypotheticalVacancyLoss", "additionalRepair", "totalAdverseCashImpact", "ownershipAttributedImpact"))
      {
         assertEquals("$8,100.00 USD", stressView.path(field).asText(), field);
      }
      assertEquals("0.5", stressView.path("ownershipFraction").asText());
      var portfolioView = MoneyPresentation.humanFacts(json.readTree(portfolio));
      assertEquals("$8,100.00 USD", portfolioView.path("moves").get(0).path("offer").path("principal").asText());
      assertEquals("$8,100.00 USD", portfolioView.path("moves").get(0).path("allocations").path("debt-123").asText());
      assertEquals("$8,100.00 USD", portfolioView.path("maturityResiduals").path("loan-123").asText());
   }



   @Test
   void derivedHumanSnapshotKeepsExactStoredFactsRatesIdsAndCurrenciesSeparate() throws Exception
   {
      String source = "{\"inputs\":{\"currency\":\"USD\"},\"comparisons\":{\"cash\":{\"interest\":8100.25,\"annualRate\":0.24,\"id\":8100}},\"accounts\":[{\"currency\":\"EUR\",\"balance\":-12500.1250}],\"knownSelectedNetWorthByCurrency\":{\"USD\":8100.00,\"EUR\":-12500.1250}}";
      var facts = new ObjectMapper().readTree(source);
      var display = MoneyPresentation.humanFacts(facts);
      assertEquals("$8,100.25 USD", display.path("comparisons").path("cash").path("interest").asText());
      assertEquals("0.24", display.path("comparisons").path("cash").path("annualRate").asText());
      assertEquals(8100, display.path("comparisons").path("cash").path("id").asInt());
      assertEquals("-€12,500.125 EUR", display.path("accounts").get(0).path("balance").asText());
      assertEquals("$8,100.00 USD", display.path("knownSelectedNetWorthByCurrency").path("USD").asText());
      assertTrue(facts.path("accounts").get(0).path("balance").isNumber());
      assertEquals(new ObjectMapper().readTree(source), facts);
      assertTrue(MoneyPresentation.humanFacts(source).contains("$8,100.25 USD"));
      assertThrows(IllegalArgumentException.class, () -> MoneyPresentation.humanFacts("invalid"));
   }
}
