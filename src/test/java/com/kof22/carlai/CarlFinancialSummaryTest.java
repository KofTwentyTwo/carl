/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.kof22.carlai.domain.BillCsv;
import com.kof22.carlai.report.MoneyPresentation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlFinancialSummaryTest
{
   @Test
   void budgetSummaryAlignsExactMoneyAndEscapesSourceText()
   {
      var facts = new HashMap<String, Object>(Map.of("category", "<img src=x>", "currency", "USD", "from", "2026-09-01", "through", "2026-09-30", "budget", new BigDecimal("8100.25"), "actualSpending", new BigDecimal("2000.75"), "remainingBudget", new BigDecimal("6099.50"), "unclassifiedCount", 8100, "excludedNonExpenseCount", 2, "sources", java.util.List.of(17L)));
      String html = BudgetProcesses.presentation(facts);
      assertTrue(html.contains("$8,100.25 USD"));
      assertTrue(html.contains("$2,000.75 USD"));
      assertTrue(html.contains("$6,099.50 USD"));
      assertTrue(html.contains("text-align:right;font-variant-numeric:tabular-nums"));
      assertTrue(html.contains("&lt;img src=x&gt;"));
      assertFalse(html.contains("<img"));
      assertTrue(html.contains("Unclassified records: 8100"));
      facts.put("remainingBudget", null);
      assertTrue(BudgetProcesses.presentation(facts).contains("Not supplied"));
   }



   @Test
   void planEffectComparisonRetainsExactPrecisionAndUnknownObservations()
   {
      String facts = "{\"currency\":\"USD\",\"outcome\":\"DIFFERENT\",\"expectation\":{\"expected_amount\":8100.125},\"observedAmount\":8100.00,\"differenceObservedMinusExpected\":-0.125,\"boundary\":\"Source supplied; no financial action\"}";
      String text = PlanEffectProcesses.presentation(17L, facts);
      assertTrue(text.contains("Expected: $8,100.125 USD"));
      assertTrue(text.contains("Observed: $8,100.00 USD"));
      assertTrue(text.contains("Difference: -$0.125 USD"));
      assertFalse(text.contains("USD USD"));
      assertTrue(PlanEffectProcesses.presentation(17L, facts.replace("8100.00", "null")).contains("Observed: Undetermined"));
   }



   @Test
   void billImportPreviewFormatsDerivedRowsButPreservesOriginalAmounts()
   {
      String csv = "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic utility,Synthetic bill,8100.00,USD,2026-09-30,UNPAID,PRIVATE\n";
      var preview = BillCsv.preview(csv);
      String display = MoneyPresentation.humanFactsOf(preview).toPrettyString();
      assertTrue(display.contains("$8,100.00 USD"));
      assertTrue(display.contains("2026-09-30"));
      assertTrue(preview.toString().contains("amount=8100.00"));
      assertTrue(csv.contains(",8100.00,USD,"));
   }
}
