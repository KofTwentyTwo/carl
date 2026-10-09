/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlUnreviewedDashboardHtmlTest
{
   @Test
   void unknownCashTotalsNeverRenderAZeroChartAndKeepUnreviewedSourceActivityVisible()
   {
      var facts = new LinkedHashMap<String, Object>();
      facts.put("currency", "USD");
      facts.put("inflows", null);
      facts.put("outflows", null);
      facts.put("netMovement", null);
      facts.put("flows", List.of());
      facts.put("unreviewedAccountMovements", List.of(Map.of("label", "Unreviewed: <source>", "direction", "OUTFLOW", "amount", new BigDecimal("25.00"), "records", 1)));
      String html = CarlDashboardHtml.render("carlIncomeExpense", facts);
      assertTrue(html.contains("Unknown until account review"), html);
      assertTrue(html.contains("Unreviewed account movements"), html);
      assertTrue(html.contains("$25.00 USD"), html);
      assertTrue(html.contains("&lt;source&gt;"), html);
      assertFalse(html.contains("<svg"), html);
      assertFalse(html.contains("No non-zero accessible movements"), html);
   }
}
