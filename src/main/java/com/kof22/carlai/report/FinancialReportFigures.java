/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.report;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;


/** Presents recognized immutable calculation schemas; never mines amounts from model prose. */
public final class FinancialReportFigures
{
   private FinancialReportFigures()
   {
   }



   /** Missing amounts stay unavailable, currencies remain separate, oversized summaries fail explicitly. */
   public static List<PlanPdfRenderer.Figure> from(JsonNode facts)
   {
      if(facts == null || !facts.isObject())
      {
         throw new IllegalArgumentException("Stored structured report facts are required");
      }
      var figures = new ArrayList<PlanPdfRenderer.Figure>();
      for(String period : List.of("before", "after"))
      {
         if(facts.path(period).isObject())
         {
            for(var figure : from(facts.path(period)))
            {
               figures.add(new PlanPdfRenderer.Figure(period + " period: " + figure.label(), figure.amount(), figure.currency(), figure.evidence()));
            }
         }
      }
      facts.path("selectedCurrencyStatusDifferences").fields().forEachRemaining(entry ->
      {
         String[] identity = entry.getKey().split(":", -1);
         if(identity.length != 2)
         {
            throw new IllegalArgumentException("Unrecognized comparison total identity");
         }
         add(figures, "After minus before: " + identity[1] + " bills", entry.getValue(), currency(JsonNodeFactory.instance.textNode(identity[0])), "Comparable accessible records only; causes and complete household coverage are not established.");
      });
      if(facts.has("monthlyBudget"))
      {
         add(figures, "Assumed monthly debt-payment budget", facts.get("monthlyBudget"), currency(facts.path("currency")), facts.path("budgetEvidence").asText("Unqualified budget assumption"));
      }
      if(facts.path("inputs").path("budgets").isArray() && facts.path("inputs").path("budgets").size() == 1)
      {
         add(figures, "Assumed monthly debt-payment budget", facts.path("inputs").path("budgets").get(0).path("amount"), currency(facts.path("inputs").path("currency")), facts.path("budgetEvidence").asText("Unqualified budget assumption"));
      }
      facts.path("totals").fields().forEachRemaining(entry ->
      {
         String[] identity = entry.getKey().split(":", -1);
         if(identity.length != 2 || !List.of("PAID_ASSERTED", "UNPAID", "DISPUTED", "UNKNOWN").contains(identity[1]))
         {
            throw new IllegalArgumentException("Unrecognized bill total identity");
         }
         add(figures, identity[1] + " bills in accessible scope", entry.getValue(), currency(JsonNodeFactory.instance.textNode(identity[0])), (identity[1].equals("PAID_ASSERTED") ? "Human-asserted payment; not independently verified. " : "Recorded payment status. ") + "Coverage may be partial. Missing amounts are excluded and must be reviewed.");
      });
      var comparisons = facts.path("comparisons");
      comparisons.fields().forEachRemaining(entry ->
      {
         String label = entry.getKey();
         var value = entry.getValue();
         if(value.has("candidateMinusBaselineCost"))
         {
            String scope = value.path("fullPayoffCostsCompared").asBoolean(false) ? "Both scenarios paid off; modeled lifetime cost comparison" : "Horizon costs only; not lifetime savings";
            String code = currency(facts.path("inputs").path("currency"));
            add(figures, label + " candidate minus baseline cost", value.get("candidateMinusBaselineCost"), code, scope + ". " + value.path("limitations").asText());
            return;
         }
         if(value.has("originalPrincipal"))
         {
            String code = currency(value.path("inputs").path("currency"));
            String evidence = "Determined: " + value.path("determined").asBoolean(false) + "; feasible: " + value.path("feasible").asBoolean(false) + "; paid off: " + value.path("paidOff").asBoolean(false) + "; payoff date: " + value.path("payoffDate").asText("Not determined") + ". Horizon estimates unless paid off; affordability unqualified.";
            for(var field : Map.of("originalPrincipal", "original principal", "interest", "modeled interest", "financedFees", "financed fees", "upfrontCashFees", "upfront cash fees", "monthlyFees", "modeled monthly fees").entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            {
               add(figures, label + " " + field.getValue(), value.get(field.getKey()), code, evidence);
            }
            BigDecimal remaining = value.path("remainingBalances").isObject() ? BigDecimal.ZERO : null;
            for(var amounts = value.path("remainingBalances").elements(); amounts.hasNext();)
            {
               remaining = remaining.add(number(amounts.next()));
            }
            add(figures, label + " remaining debt at horizon", remaining == null ? null : JsonNodeFactory.instance.numberNode(remaining), code, evidence);
         }
         else if(value.has("payoff"))
         {
            String code = currency(value.path("currency"));
            String evidence = "Feasible: " + value.path("payoff").path("feasible").asBoolean(false) + "; paid off: " + value.path("payoff").path("paidOff").asBoolean(false) + "; payoff date: " + value.path("payoffDate").asText("Not determined") + ". Monthly estimates; affordability unqualified.";
            add(figures, label + " modeled interest", value.path("payoff").get("interest"), code, evidence);
            add(figures, label + " modeled fees", value.path("payoff").get("fees"), code, evidence);
         }
         else if(value.has("interest") && value.has("feasible"))
         {
            String code = currency(facts.path("currency"));
            String evidence = "Feasible: " + value.path("feasible").asBoolean(false) + "; paid off: " + value.path("paidOff").asBoolean(false) + ". Monthly horizon estimate unless fully paid off; affordability unqualified.";
            add(figures, label + " modeled interest", value.get("interest"), code, evidence);
            add(figures, label + " modeled fees", value.get("fees"), code, evidence);
         }
      });
      if(facts.path("calculationVersion").asText().equals("plan-effects-v1"))
      {
         String code = currency(facts.path("currency"));
         String evidence = "Selected evidence only; outcome: " + facts.path("outcome").asText() + ". No task completion or external financial action is established.";
         add(figures, "Agreed expected effect", facts.path("expectation").get("expected_amount"), code, evidence);
         add(figures, "Observed selected effect", facts.get("observedAmount"), code, evidence);
         add(figures, "Observed minus expected effect", facts.get("differenceObservedMinusExpected"), code, evidence);
      }
      var budget = facts.path("budget");
      if(budget.has("currency"))
      {
         String code = currency(budget.path("currency"));
         add(figures, "Supported cash purchase budget", budget.get("supportedCashBudget"), code, "Conditional cash/reserve forecast. Credit availability is not a spending budget.");
         add(figures, "All-in purchase price", facts.get("allInPrice"), code, "Supplied planning price; no purchase was made.");
         add(figures, "Protected reserve", facts.get("protectedReserve"), code, "Includes selected additional reserve earmarks.");
      }
      var options = facts.path("purchaseOptions");
      if(options.has("currency"))
      {
         String code = currency(options.path("currency"));
         for(var option : options.path("options"))
         {
            String evidence = "State: " + option.path("state").asText() + "; last payment: " + option.path("lastPayment").asText("Unknown") + "; first reserve shortfall: " + option.path("firstReserveShortfall").asText("None in supplied horizon") + ". " + option.path("evidence").asText();
            add(figures, option.path("id").asText() + " total cash outlay", option.get("totalCashOutlay"), code, evidence);
            add(figures, option.path("id").asText() + " finance cost", option.get("financeCost"), code, evidence);
         }
      }
      facts.path("calculation").path("wholePropertyPortfolioByCurrency").fields().forEachRemaining(entry ->
      {
         String code = currency(JsonNodeFactory.instance.textNode(entry.getKey()));
         for(var field : Map.of("rentCollected", "Rental receipts", "operatingExpenses", "Rental operating expenses", "debtPrincipal", "Rental loan principal", "debtInterest", "Rental loan interest", "capitalSpending", "Rental capital spending", "cashAfterReserveTransfers", "Rental cash after reserve transfers").entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
         {
            add(figures, field.getValue(), entry.getValue().get(field.getKey()), code, "Accessible selected properties only. Whole-property cash attribution; not a tax deduction or ownership distribution.");
         }
      });
      if(figures.size() > 50)
      {
         throw new IllegalArgumentException("Narrow the report; maximum 50 summarized financial figures");
      }
      return List.copyOf(figures);
   }



   private static void add(List<PlanPdfRenderer.Figure> figures, String label, JsonNode value, String currency, String evidence)
   {
      figures.add(new PlanPdfRenderer.Figure(label, value == null || value.isNull() || value.isMissingNode() ? null : number(value), currency, evidence));
   }



   private static String currency(JsonNode value)
   {
      String code = value.asText();
      int scale = Currency.getInstance(code).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Explicit supported report currency is required");
      }
      return code;
   }



   private static BigDecimal number(JsonNode value)
   {
      if(value == null || !value.isNumber() && !value.isTextual() || value.asText().length() > 64)
      {
         throw new IllegalArgumentException("Stored figure must be an exact bounded decimal");
      }
      BigDecimal result = new BigDecimal(value.asText());
      if(result.precision() > 25 || Math.abs((long) result.scale()) > 18)
      {
         throw new IllegalArgumentException("Stored figure exceeds the exact decimal bound");
      }
      return result;
   }
}
