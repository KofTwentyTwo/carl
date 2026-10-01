/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlDashboardHtmlTest
{
   @Test
   void everyFinancialTableHasKeyboardAccessibleHorizontalScrollingWithoutDroppingEvidence()
   {
      for(String name : List.of("carlCashFlow", "carlIncomeExpense", "carlBalanceSheet", "carlPlanProgress"))
      {
         String html = CarlDashboardHtml.render(name, name.equals("carlPlanProgress") ? Map.of("projected", Map.of("facts", Map.of())) : flow("2226.20", "550.90", List.of(Map.of("label", "A long category containing source evidence", "direction", "OUTFLOW", "amount", new BigDecimal("550.90"), "records", 1))));
         int tables = html.split("<table ", -1).length - 1;
         assertTrue(tables > 0);
         assertEquals(tables, html.split("role='region' aria-label='Financial table; scroll horizontally to read all columns' tabindex='0'", -1).length - 1, name);
         assertEquals(tables, html.split("max-width:100%;overflow-x:auto", -1).length - 1, name);
         assertEquals(tables, html.split("</table></div>", -1).length - 1, name);
         assertEquals(tables, html.split("<caption>", -1).length - 1, name);
         if(name.equals("carlCashFlow") || name.equals("carlIncomeExpense"))
         {
            assertTrue(html.contains("$2,226.20 USD"));
            assertTrue(html.contains("$550.90 USD"));
            assertTrue(html.contains("A long category containing source evidence"));
         }
      }
   }



   @Test
   void groupsMoneyAndRightAlignsAmountsWithoutChangingRecordCountsOrMissingFacts()
   {
      String html = CarlDashboardHtml.render("carlCashFlow", flow("8100.00", "12000.00", List.of(Map.of("label", "Expenses", "direction", "OUTFLOW", "amount", new BigDecimal("12000.00"), "records", 8100))));
      assertTrue(html.contains("$8,100.00 USD"), html);
      assertTrue(html.contains("-$3,900.00 USD"), html);
      assertTrue(html.contains("text-align:right"), html);
      assertTrue(html.contains("font-variant-numeric:tabular-nums"), html);
      assertTrue(html.contains(">8100</td>"), html);
      assertTrue(html.contains("Not supplied / excluded"), html);
   }



   private Map<String, Object> flow(String incoming, String outgoing, List<Map<String, Object>> rows)
   {
      return Map.of("from", "2026-09-01", "through", "2026-09-30", "currency", "USD", "inflows", new BigDecimal(incoming), "outflows", new BigDecimal(outgoing), "netMovement", new BigDecimal(incoming).subtract(new BigDecimal(outgoing)), "flows", rows, "gaps", List.of("Unknown coverage"));
   }



   @Test
   void svgAndExactTablePreserveRefundDirectionEscapeUntrustedLabelsAndUseOnlyInertMarkup()
   {
      var rows = List.of(Map.<String, Object>of("label", "Expense refund: <script>alert('x')</script>", "direction", "INFLOW", "amount", new BigDecimal("25.25"), "records", 1));
      String html = CarlDashboardHtml.render("carlIncomeExpense", flow("25.25", "0.00", rows));
      assertTrue(html.contains("$25.25 USD"), html);
      assertTrue(html.contains("&lt;script&gt;"), html);
      assertFalse(html.contains("<script"));
      assertFalse(html.contains("<form"));
      assertFalse(html.contains("<style"));
      assertTrue(html.contains("role='img'"));
      assertTrue(html.contains("<caption>Directional classified movements"));
      assertTrue(html.contains("Net inflow (not available cash)"));
      assertTrue(html.contains("href='/app/carlTransactions'"));
   }



   @Test
   void deficitEmptyAndManyCategoriesStayExplicitWithoutNegativeOrZeroInventedFlows()
   {
      String empty = CarlDashboardHtml.render("carlIncomeExpense", flow("0.00", "0.00", List.of()));
      assertTrue(empty.contains("No non-zero accessible movements"));
      var rows = new ArrayList<Map<String, Object>>();
      for(int index = 0; index < 12; index++)
      {
         rows.add(Map.of("label", "Expense " + index, "direction", "OUTFLOW", "amount", new BigDecimal("10.01"), "records", 1));
      }
      String deficit = CarlDashboardHtml.render("carlIncomeExpense", flow("0.00", "120.12", rows));
      assertTrue(deficit.contains("Net outflow funding gap"));
      assertTrue(deficit.contains("Other categories (see exact table)"));
      assertTrue(deficit.contains("$50.05 USD"));
      assertTrue(deficit.contains("Expense 11"));
      assertTrue(deficit.contains("-$120.12 USD"));
      assertFalse(deficit.contains("stroke-width='-"));
   }



   @Test
   void selectedBalanceSheetSeparatesCurrenciesAndUnknownValuations()
   {
      var facts = Map.of("accounts", List.of(Map.of("title", "Cash", "currency", "USD", "ownedSignedBalance", new BigDecimal("500.25")), Map.of("title", "Loan", "currency", "USD", "ownedSignedBalance", new BigDecimal("-200.10")), Map.of("title", "Unknown", "currency", "EUR")), "properties", List.of(), "knownSelectedNetWorthByCurrency", Map.of("USD", new BigDecimal("300.15")), "knownSelectedLiquidBalancesByCurrency", Map.of("USD", new BigDecimal("500.25")), "gaps", List.of("EUR balance missing; excluded, not zero."));
      String html = CarlDashboardHtml.render("carlBalanceSheet", Map.of("facts", facts, "stale", "true"));
      assertTrue(html.contains("500.25"));
      assertTrue(html.contains("200.10"));
      assertTrue(html.contains("300.15"));
      assertTrue(html.contains("Not supplied / excluded"));
      assertTrue(html.contains("Stale sources; refresh explicitly"));
      assertTrue(html.contains("EUR balance missing"));
      assertTrue(html.contains("currencies are never combined"));
   }



   @Test
   void planProjectionHumanReportAndSelectedObservationUseDistinctEvidenceLabels()
   {
      var values = Map.<String, Object>of("plan", Map.of("title", "Debt freedom", "version", 3, "state", "AGREED", "source_stale", true), "steps", List.of(Map.of("title", "Pay bill", "status", "REPORTED_DONE", "checkin", "Human assertion")), "currentGoals", List.of(Map.of("title", "Debt first", "goal_type", "DEBT_FREEDOM", "priority", 1, "investment_stage_selected", false)), "projected", Map.of("facts", Map.of(), "id", 7), "observations", List.of(Map.of("id", 9, "stale", true, "facts", Map.of("outcome", "MATCH", "currency", "USD", "observedAmount", new BigDecimal("125.25"), "differenceObservedMinusExpected", BigDecimal.ZERO, "planVersionMatches", false, "gaps", List.of("Partial selected observations")))));
      String html = CarlDashboardHtml.render("carlPlanProgress", values);
      assertTrue(html.contains("Source facts changed"));
      assertTrue(html.contains("REPORTED_DONE"));
      assertTrue(html.contains("not verified financial effects"));
      assertTrue(html.contains("$125.25 USD"));
      assertTrue(html.contains("Debt first"));
      assertTrue(html.contains("DEBT_FREEDOM"));
      assertTrue(html.contains("Partial selected observations"));
   }
}
