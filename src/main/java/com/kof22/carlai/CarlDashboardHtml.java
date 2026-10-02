/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Escaped native HTML/SVG with an exact accessible table beside every graphical view. */
final class CarlDashboardHtml
{
   private static final ObjectMapper JSON = new ObjectMapper().setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
   private static final String TABLE = "<div role='region' aria-label='Financial table; scroll horizontally to read all columns' tabindex='0' style='max-width:100%;overflow-x:auto'><table style='width:100%;border-collapse:collapse;text-align:left;margin:16px 0'>";

   private CarlDashboardHtml()
   {
   }



   static String selection(String name)
   {
      return "<p>Select the explicit sources above to view current accessible financial information.</p>" + links(name);
   }



   static String render(String name, Map<String, Object> values)
   {
      JsonNode facts = JSON.valueToTree(values);
      StringBuilder html = new StringBuilder("<section style='color:#f5f5f5;background:#25282c;font-family:inherit;padding:8px'>");
      switch(name)
      {
         case "carlCashFlow", "carlIncomeExpense" -> flow(html, facts, name.equals("carlIncomeExpense"));
         case "carlBalanceSheet" -> balance(html, facts);
         case "carlPlanProgress" -> plan(html, facts);
         default -> throw new IllegalArgumentException("Unknown native dashboard");
      }
      html.append(links(name)).append("</section>");
      if(html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 524288)
      {
         throw new IllegalArgumentException("Dashboard display exceeds 512 KiB; narrow selected sources");
      }
      return html.toString();
   }



   private static void flow(StringBuilder html, JsonNode facts, boolean chart)
   {
      html.append("<p style='color:#b8bdc5'>").append(text(facts.path("from"))).append(" through ").append(text(facts.path("through"))).append(" · ").append(text(facts.path("currency"))).append(" · ").append(text(facts.path("displayZone"))).append("</p>");
      html.append(TABLE).append("<caption>Exact accessible account movement totals</caption><thead><tr><th scope='col' style='text-align:right'>Inflows</th><th scope='col' style='text-align:right'>Outflows</th><th scope='col' style='text-align:right'>Net movement</th></tr></thead><tbody><tr>");
      for(String field : List.of("inflows", "outflows", "netMovement"))
      {
         html.append("<td style='font-size:24px;font-weight:600;padding:10px 0;text-align:right;font-variant-numeric:tabular-nums'>").append(amount(facts.path(field), facts.path("currency"))).append("</td>");
      }
      html.append("</tr></tbody></table></div>");
      html.append(TABLE).append("<caption>Classified spending across all account kinds · distinct from cash movement</caption><thead><tr><th scope='col' style='text-align:right'>Expense movements</th><th scope='col' style='text-align:right'>Expense refunds</th><th scope='col' style='text-align:right'>Net classified spending</th></tr></thead><tbody><tr>");
      for(String field : List.of("classifiedSpendingAllAccountKinds", "classifiedExpenseRefundsAllAccountKinds", "classifiedNetSpendingAllAccountKinds"))
      {
         html.append("<td style='text-align:right;font-variant-numeric:tabular-nums'>").append(amount(facts.path(field), facts.path("currency"))).append("</td>");
      }
      html.append("</tr></tbody></table></div><p>Spending uses explicit EXPENSE classification, including credit-card purchases and their refunds; transfers/debt service/capital/unclassified movements are separate. This is partial record coverage and no tax treatment is inferred.</p>");
      if(facts.path("inflows").isNull() || facts.path("outflows").isNull())
      {
         html.append("<p><strong>Unknown until account review.</strong> Imported source activity is present, but its account kind, ownership and liquidity are unqualified. Review the accounts before calculating cash totals or drawing the cash-flow chart.</p>");
      }
      else if(chart)
      {
         sankey(html, facts);
      }
      html.append(TABLE).append("<caption>Directional classified movements · exact amounts used in the chart</caption><thead><tr><th scope='col'>Category and treatment</th><th scope='col'>Direction</th><th scope='col' style='text-align:right'>Amount</th><th scope='col'>Records</th></tr></thead><tbody>");
      for(JsonNode row : facts.path("flows"))
      {
         row(html, text(row.path("label")), text(row.path("direction")), amount(row.path("amount"), facts.path("currency")), text(row.path("records")));
      }
      html.append("</tbody></table></div>");
      if(!facts.path("nonCashAccountMovements").isEmpty())
      {
         html.append(TABLE).append("<caption>Other account movements · excluded from cash totals and chart</caption><thead><tr><th scope='col'>Account kind</th><th scope='col'>Treatment</th><th scope='col'>Direction</th><th scope='col' style='text-align:right'>Amount</th></tr></thead><tbody>");
         for(JsonNode row : facts.path("nonCashAccountMovements"))
         {
            row(html, text(row.path("accountKind")), text(row.path("label")), text(row.path("direction")), amount(row.path("amount"), facts.path("currency")));
         }
         html.append("</tbody></table></div>");
      }
      if(!facts.path("unreviewedAccountMovements").isMissingNode() && !facts.path("unreviewedAccountMovements").isEmpty())
      {
         html.append(TABLE).append("<caption>Unreviewed account movements · cash treatment unknown</caption><thead><tr><th scope='col'>Source movement</th><th scope='col'>Direction</th><th scope='col' style='text-align:right'>Observed amount</th><th scope='col'>Records</th></tr></thead><tbody>");
         for(JsonNode row : facts.path("unreviewedAccountMovements"))
         {
            row(html, text(row.path("label")), text(row.path("direction")), amount(row.path("amount"), facts.path("currency")), text(row.path("records")));
         }
         html.append("</tbody></table></div>");
      }
      html.append("<p>").append(text(facts.path("records"))).append(" accessible records; ").append(text(facts.path("excludedTransferLegs"))).append(" matched internal transfer legs excluded. Latest selected movement: ").append(text(facts.path("latestMovementDate"))).append(".</p><p>").append(text(facts.path("definition"))).append("</p>");
      gaps(html, facts.path("gaps"));
      footer(html, facts);
   }

   private record Flow(String label, BigDecimal amount)
   {
   }

   private static List<Flow> chartFlows(JsonNode facts, String direction)
   {
      var result = new ArrayList<Flow>();
      for(JsonNode row : facts.path("flows"))
      {
         if(direction.equals(row.path("direction").asText()))
         {
            result.add(new Flow(row.path("label").asText(), row.path("amount").decimalValue()));
         }
      }
      result.sort(Comparator.comparing(Flow::amount).reversed().thenComparing(Flow::label));
      if(result.size() > 8)
      {
         BigDecimal other = result.subList(7, result.size()).stream().map(Flow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
         result = new ArrayList<>(result.subList(0, 7));
         result.add(new Flow("Other categories (see exact table)", other));
      }
      return result;
   }



   private static void sankey(StringBuilder html, JsonNode facts)
   {
      var incoming = chartFlows(facts, "INFLOW");
      var outgoing = chartFlows(facts, "OUTFLOW");
      BigDecimal inflows = facts.path("inflows").decimalValue();
      BigDecimal outflows = facts.path("outflows").decimalValue();
      BigDecimal net = inflows.subtract(outflows);
      if(net.signum() > 0)
      {
         outgoing.add(new Flow("Net inflow (not available cash)", net));
      }
      else if(net.signum() < 0)
      {
         incoming.add(new Flow("Net outflow funding gap", net.negate()));
      }
      BigDecimal denominator = inflows.max(outflows);
      html.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 960 760' role='img' aria-label='Accessible account movements; exact currency amounts and classifications in the following table' style='width:100%;height:auto'><title>Income and expense flow</title><desc>Directional cash movements, not complete household income or available cash. Positive net movement and funding gaps balance the chart; they are not additional transactions. Widths are proportional to exact amounts, with small flows visible at a minimum one pixel.</desc>");
      if(denominator.signum() == 0)
      {
         html.append("<text x='24' y='64' fill='#b8bdc5'>No non-zero accessible movements in this selection.</text>");
      }
      else
      {
         html.append("<rect x='465' y='100' width='30' height='400' rx='3' fill='#0066cc'/><text x='480' y='35' text-anchor='middle' fill='#f5f5f5'>Account movements</text>");
         ribbons(html, incoming, denominator, true, facts.path("currency").asText());
         ribbons(html, outgoing, denominator, false, facts.path("currency").asText());
      }
      html.append("</svg><p>Up to eight categories per side are drawn; all directional categories remain in the exact table. Borrowing, capital proceeds and refunds keep their own treatment.</p>");
   }



   private static void ribbons(StringBuilder html, List<Flow> rows, BigDecimal denominator, boolean incoming, String currency)
   {
      double middle = 100;
      double outsideStart = 80;
      for(int index = 0; index < rows.size(); index++)
      {
         Flow row = rows.get(index);
         double width = row.amount().divide(denominator, 10, RoundingMode.HALF_UP).doubleValue() * 400;
         double center = middle + width / 2;
         double outside = outsideStart + width / 2;
         outsideStart += Math.max(1, width) + 28;
         String color = incoming ? "#62c99e" : "#69b0ff";
         html.append("<rect x='").append(incoming ? "280" : "670").append("' y='").append(outside - width / 2).append("' width='10' height='").append(Math.max(1, width)).append("' fill='").append(color).append("'/>");
         html.append("<path d='M ").append(incoming ? "290 " : "495 ").append(incoming ? outside : center).append(incoming ? " C 365 " : " C 540 ").append(incoming ? outside : center).append(incoming ? " 415 " : " 615 ").append(incoming ? center : outside).append(incoming ? " 465 " : " 670 ").append(incoming ? center : outside).append("' fill='none' stroke='").append(color).append("' stroke-opacity='0.35' stroke-width='").append(Math.max(1, width)).append("'><title>").append(escape(row.label())).append(": ").append(escape(com.kof22.carlai.report.MoneyPresentation.format(row.amount(), currency))).append("</title></path>");
         html.append("<text x='").append(incoming ? "12" : "690").append("' y='").append(outside - 5).append("' font-size='12' fill='#f5f5f5'>").append(escape(shortLabel(row.label()))).append("</text><text x='").append(incoming ? "270" : "948").append("' text-anchor='end' y='").append(outside + 12).append("' font-size='12' fill='#b8bdc5'>").append(escape(com.kof22.carlai.report.MoneyPresentation.format(row.amount(), currency))).append("</text>");
         middle += width;
      }
   }



   private static String shortLabel(String value)
   {
      return value.length() <= 36 ? value : value.substring(0, 33) + "…";
   }



   private static void balance(StringBuilder html, JsonNode selected)
   {
      JsonNode facts = selected.path("facts");
      saved(html, selected);
      html.append("<p>As of ").append(text(facts.path("asOf"))).append(" · age limit ").append(text(facts.path("maximumAgeDays"))).append(" days. ").append(text(facts.path("temporalScope"))).append("</p>");
      var assets = new LinkedHashMap<String, BigDecimal>();
      var liabilities = new LinkedHashMap<String, BigDecimal>();
      for(String list : List.of("accounts", "properties"))
      {
         for(JsonNode row : facts.path(list))
         {
            JsonNode value = row.path(list.equals("accounts") ? "ownedSignedBalance" : "ownedPropertyValue");
            if(value.isNumber())
            {
               BigDecimal amount = value.decimalValue();
               (amount.signum() >= 0 ? assets : liabilities).merge(row.path("currency").asText(), amount.abs(), BigDecimal::add);
            }
         }
      }
      html.append(TABLE).append("<caption>Known selected balances · currencies are never combined</caption><thead><tr><th scope='col'>Currency</th><th scope='col' style='text-align:right'>Assets</th><th scope='col' style='text-align:right'>Liabilities</th><th scope='col' style='text-align:right'>Net worth</th><th scope='col' style='text-align:right'>Liquid signed balances</th></tr></thead><tbody>");
      facts.path("knownSelectedNetWorthByCurrency").fields().forEachRemaining(entry -> row(html, escape(entry.getKey()), money(assets.getOrDefault(entry.getKey(), BigDecimal.ZERO), entry.getKey()), money(liabilities.getOrDefault(entry.getKey(), BigDecimal.ZERO), entry.getKey()), amount(entry.getValue(), JSON.valueToTree(entry.getKey())), amount(facts.path("knownSelectedLiquidBalancesByCurrency").path(entry.getKey()), JSON.valueToTree(entry.getKey()))));
      html.append("</tbody></table></div>");
      for(String list : List.of("accounts", "properties"))
      {
         html.append(TABLE).append("<caption>Selected ").append(list).append(" and valuation treatment</caption><thead><tr><th scope='col'>Source</th><th scope='col'>Currency</th><th scope='col' style='text-align:right'>Owned signed value</th><th scope='col'>Treatment</th><th scope='col'>Dated evidence</th></tr></thead><tbody>");
         for(JsonNode row : facts.path(list))
         {
            JsonNode observations = row.path("observations");
            String observed = observations.isArray() && !observations.isEmpty() ? observations.get(0).path("as_of").asText() : row.path("valuationDate").asText("Not supplied");
            row(html, text(row.path("title")), text(row.path("currency")), amount(row.path(list.equals("accounts") ? "ownedSignedBalance" : "ownedPropertyValue"), row.path("currency")), text(row.path("treatment")), escape(observed));
         }
         html.append("</tbody></table></div>");
      }
      gaps(html, facts.path("gaps"));
      html.append("<p>Known selected net worth and signed liquid balances are not available credit or spending authorization. Missing or conflicting values are excluded, not zero.</p><p>").append(text(facts.path("scope"))).append("</p>");
   }



   private static void plan(StringBuilder html, JsonNode selected)
   {
      JsonNode plan = selected.path("plan");
      html.append("<h3>").append(text(plan.path("title"))).append("</h3><p>Version ").append(text(plan.path("version"))).append(" · ").append(text(plan.path("state"))).append(" · updated ").append(text(plan.path("updated_at"))).append("</p>");
      if(plan.path("source_stale").asBoolean())
      {
         html.append("<p style='color:#ffc857;font-weight:600'>Source facts changed. Regenerate the comparison and explicitly rebase this plan before relying on its projections.</p>");
      }
      html.append(TABLE).append("<caption>Current explicit goal priorities (lower number first)</caption><thead><tr><th scope='col'>Goal</th><th scope='col'>Stage</th><th scope='col'>Priority</th><th scope='col'>Investment stage selected</th></tr></thead><tbody>");
      for(JsonNode goal : selected.path("currentGoals"))
      {
         row(html, text(goal.path("title")), text(goal.path("goal_type")), text(goal.path("priority")), text(goal.path("investment_stage_selected")));
      }
      html.append("</tbody></table></div>").append(TABLE).append("<caption>Assigned human tasks · check-ins are reports, not verified financial effects</caption><thead><tr><th scope='col'>Task</th><th scope='col'>Assignee member ID</th><th scope='col'>Due</th><th scope='col'>State</th><th scope='col'>Reported evidence</th></tr></thead><tbody>");
      for(JsonNode step : selected.path("steps"))
      {
         row(html, text(step.path("title")), text(step.path("assignee_id")), text(step.path("due_date")), text(step.path("status")), text(step.path("checkin")));
      }
      html.append("</tbody></table></div><h3>Saved projections</h3>");
      saved(html, selected.path("projected"));
      var figures = com.kof22.carlai.report.FinancialReportFigures.from(selected.path("projected").path("facts"));
      if(!figures.isEmpty())
      {
         html.append(TABLE).append("<caption>Saved modeled amounts · assumptions are not observations</caption><thead><tr><th scope='col'>Figure</th><th scope='col' style='text-align:right'>Amount</th><th scope='col'>Qualification</th></tr></thead><tbody>");
         for(var figure : figures)
         {
            row(html, escape(figure.label()), money(figure.amount(), figure.currency()), escape(figure.evidence()));
         }
         html.append("</tbody></table></div>");
      }
      html.append("<p>Open the saved financial output for its exact debt costs, investment assumptions, horizon and scenario evidence. Projected investment growth remains uncertain and separate from debt costs.</p>");
      html.append(TABLE).append("<caption>Explicit agreed expectations</caption><thead><tr><th scope='col'>Task</th><th scope='col'>Kind</th><th scope='col' style='text-align:right'>Expected amount</th><th scope='col'>Period</th></tr></thead><tbody>");
      for(JsonNode expectation : selected.path("expectations"))
      {
         row(html, text(expectation.path("step_title")), text(expectation.path("effect_kind")), amount(expectation.path("expected_amount"), expectation.path("currency")), text(expectation.path("period_start")) + " through " + text(expectation.path("period_end")));
      }
      html.append("</tbody></table></div>").append(TABLE).append("<caption>Saved selected observations · numerical matches do not verify causal effect or task completion</caption><thead><tr><th scope='col'>Observation</th><th scope='col'>Outcome</th><th scope='col' style='text-align:right'>Observed amount</th><th scope='col' style='text-align:right'>Observed minus expected</th><th scope='col'>Version matched when calculated</th><th scope='col'>Current plan version matches</th><th scope='col'>Source status</th></tr></thead><tbody>");
      for(JsonNode observation : selected.path("observations"))
      {
         JsonNode facts = observation.path("facts");
         row(html, text(observation.path("id")), text(facts.path("outcome")), amount(facts.path("observedAmount"), facts.path("currency")), amount(facts.path("differenceObservedMinusExpected"), facts.path("currency")), text(facts.path("planVersionMatches")), text(observation.path("currentPlanVersionMatches")), observation.path("stale").asBoolean() ? "Stale" : "Current");
      }
      html.append("</tbody></table></div>");
      for(JsonNode observation : selected.path("observations"))
      {
         gaps(html, observation.path("facts").path("gaps"));
      }
      html.append("<p>").append(text(selected.path("boundary"))).append("</p>");
      footer(html, selected);
   }



   private static void saved(StringBuilder html, JsonNode selected)
   {
      html.append("<p>Saved output ").append(text(selected.path("id"))).append(" · ").append(text(selected.path("created_at"))).append(" · ").append(text(selected.path("status_label"))).append(" · ").append(selected.path("stale").asBoolean() ? "<strong style='color:#ffc857'>Stale sources; refresh explicitly</strong>" : "Current sources").append("</p><p>").append(text(selected.path("limitations"))).append("</p>");
   }



   private static void footer(StringBuilder html, JsonNode facts)
   {
      html.append("<p style='font-size:12px;color:#b8bdc5'>").append(text(facts.path("scope"))).append(" · Generated ").append(text(facts.path("generatedAt"))).append(".</p>");
   }



   private static void gaps(StringBuilder html, JsonNode gaps)
   {
      if(gaps.isArray() && !gaps.isEmpty())
      {
         html.append("<div style='border-left:3px solid #ffc857;padding:8px 12px;margin:16px 0'><strong>Information gaps</strong><ul>");
         gaps.forEach(gap -> html.append("<li>").append(text(gap)).append("</li>"));
         html.append("</ul></div>");
      }
   }



   private static void row(StringBuilder html, String... columns)
   {
      html.append("<tr>");
      for(String column : columns)
      {
         html.append("<td style='padding:8px 6px;border-bottom:1px solid #41464d").append(column.startsWith("<span data-carl-money") ? ";text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap" : "").append("'>").append(column).append("</td>");
      }
      html.append("</tr>");
   }



   private static String links(String name)
   {
      return switch(name)
      {
         case "carlBalanceSheet" -> "<p><a href='/app/carlBalanceSources'>Choose readable accounts and properties</a> · <a href='/app/carlArtifacts'>Open saved outputs and evidence</a></p>";
         case "carlPlanProgress" -> "<p><a href='/app/carlPlans'>Review and rebase plans</a> · <a href='/app/carlPlanSteps'>Assign tasks and record check-ins</a> · <a href='/app/carlPlanEffects'>Compare selected observations</a> · <a href='/app/carlArtifacts'>Open saved projections</a></p>";
         default -> "<p><a href='/app/carlTransactions'>Review and classify transactions</a> · <a href='/app/carlAccounts'>Review account sources</a></p>";
      };
   }



   private static String amount(JsonNode value, JsonNode currency)
   {
      return value.isNumber() ? money(value.decimalValue(), currency.asText()) : "<span data-carl-money='true'>Not supplied / excluded</span>";
   }



   private static String money(BigDecimal value, String currency)
   {
      return "<span data-carl-money='true'>" + escape(com.kof22.carlai.report.MoneyPresentation.format(value, currency)) + "</span>";
   }



   private static String text(JsonNode value)
   {
      return value.isMissingNode() || value.isNull() ? "Not supplied" : escape(value.asText());
   }



   private static String escape(String value)
   {
      return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
   }
}
