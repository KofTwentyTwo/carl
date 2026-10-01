/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.report;


import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FinancialReportFiguresTest
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   @Test
   void expectedEffectFiguresKeepUnknownObservationUnavailable() throws Exception
   {
      var figures = FinancialReportFigures.from(JSON.readTree("""
         {"calculationVersion":"plan-effects-v1","currency":"USD","expectation":{"expected_amount":100.25},"observedAmount":null,"differenceObservedMinusExpected":null,"outcome":"UNDETERMINED"}
         """));
      assertEquals(3, figures.size());
      assertEquals(new BigDecimal("100.25"), figures.getFirst().amount());
      assertNull(figures.get(1).amount());
      assertNull(figures.get(2).amount());
      assertTrue(figures.stream().allMatch(f -> f.currency().equals("USD") && f.evidence().contains("No task completion")));
   }



   @Test
   void portfolioPdfRetainsExactFiguresAndHorizonQualification() throws Exception
   {
      var facts = JSON.readTree("""
         {"inputs":{"currency":"USD","budgets":[{"firstMonth":1,"amount":200}]},"budgetEvidence":"Synthetic agreed payment budget",
         "comparisons":{"AVALANCHE":{"inputs":{"currency":"USD"},"determined":true,"feasible":true,"paidOff":false,
         "originalPrincipal":1000.25,"interest":85.12,"financedFees":10.00,"upfrontCashFees":5.00,"monthlyFees":3.00,
         "remainingBalances":{"a":600.00,"b":125.25}},
         "AVALANCHE_VS_CURRENT":{"fullPayoffCostsCompared":false,"candidateMinusBaselineCost":-15.75,"limitations":"Same starting inputs; residual debt remains"}}}
         """);
      var figures = FinancialReportFigures.from(facts);
      assertEquals(new BigDecimal("725.25"), figures.stream().filter(figure -> figure.label().endsWith("remaining debt at horizon")).findFirst().orElseThrow().amount());
      byte[] bytes = new PlanPdfRenderer().render(new PlanPdfRenderer.Report("Carl AI debt planning", "plan-7", 3, Instant.parse("2026-09-30T12:00:00Z"), "Synthetic authorized household scope", "2026-09-30", "DRAFT; no external financial action", figures, List.of(), List.of(new PlanPdfRenderer.Source("record:7", "Synthetic statement", "Exact immutable snapshot")), List.of("Debt reduction comes first. Credit capacity is not spending budget.")));
      try(var pdf = PDDocument.load(bytes))
      {
         String text = new PDFTextStripper().getText(pdf);
         for(String value : List.of("$1,000.25 USD", "$85.12 USD", "$10.00 USD", "$5.00 USD", "$725.25 USD", "-$15.75 USD", "Horizon costs only; not lifetime savings", "Credit capacity is not spending budget"))
         {
            assertTrue(text.contains(value), value + " missing from actual PDF");
         }
         assertNull(pdf.getDocumentCatalog().getOpenAction());
      }
      Path output = Path.of("target/visual-fixtures/carl-portfolio-figures.pdf");
      Files.createDirectories(output.getParent());
      Files.write(output, bytes);
   }



   @Test
   void currencyAndPaymentStatusStaySeparateAndMissingNeverBecomesZero() throws Exception
   {
      var figures = FinancialReportFigures.from(JSON.readTree("{\"totals\":{\"USD:UNPAID\":200.00,\"EUR:PAID_ASSERTED\":75.50}}"));
      assertEquals(2, figures.size());
      assertTrue(figures.stream().anyMatch(figure -> figure.currency().equals("USD") && figure.amount().compareTo(new BigDecimal("200")) == 0));
      assertTrue(figures.stream().anyMatch(figure -> figure.currency().equals("EUR") && figure.amount().compareTo(new BigDecimal("75.5")) == 0));
      var missing = FinancialReportFigures.from(JSON.readTree("{\"monthlyBudget\":null,\"currency\":\"USD\"}"));
      assertNull(missing.getFirst().amount());
      assertThrows(IllegalArgumentException.class, () -> FinancialReportFigures.from(JSON.readTree("{\"monthlyBudget\":200}")));
      assertThrows(IllegalArgumentException.class, () -> FinancialReportFigures.from(JSON.readTree("{\"monthlyBudget\":\"1e9999\",\"currency\":\"USD\"}")));
   }



   @Test
   void periodDifferencesRetainTheirSeparateCurrencyStatusAndCoverageMeaning() throws Exception
   {
      var figures = FinancialReportFigures.from(JSON.readTree("""
         {"before":{"totals":{"USD:UNPAID":125.25}},"after":{"totals":{"USD:UNPAID":140.75}},"selectedCurrencyStatusDifferences":{"USD:UNPAID":15.50}}
         """));
      assertEquals(3, figures.size());
      assertEquals(0, figures.getLast().amount().compareTo(new BigDecimal("15.50")));
      assertTrue(figures.getLast().evidence().contains("causes and complete household coverage are not established"));
   }



   @Test
   void purchaseAndRentalFiguresPreserveCashVersusCapitalAndFinancingCosts() throws Exception
   {
      var figures = FinancialReportFigures.from(JSON.readTree("""
         {"purchaseOptions":{"currency":"USD","options":[{"id":"offer:2","state":"EXCEEDS_PLAN","totalCashOutlay":225.50,"financeCost":25.50,"evidence":"Supplied store terms"}]},
         "calculation":{"wholePropertyPortfolioByCurrency":{"USD":{"rentCollected":1000,"operatingExpenses":125.25,"debtPrincipal":300,"debtInterest":85.50,"capitalSpending":200,"cashAfterReserveTransfers":214.25}}}}
         """));
      assertEquals(0, new BigDecimal("25.50").compareTo(figures.stream().filter(figure -> figure.label().endsWith("finance cost")).findFirst().orElseThrow().amount()));
      assertTrue(figures.stream().anyMatch(figure -> figure.label().equals("Rental loan principal") && figure.amount().compareTo(new BigDecimal("300")) == 0));
      assertTrue(figures.stream().anyMatch(figure -> figure.label().equals("Rental capital spending") && figure.amount().compareTo(new BigDecimal("200")) == 0));
      assertTrue(figures.stream().filter(figure -> figure.label().startsWith("Rental")).allMatch(figure -> figure.evidence().contains("not a tax deduction")));
   }
}
