/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DebtPlans;
import com.kof22.carlai.domain.FinancialPlanning;
import com.kof22.carlai.domain.PortfolioPlans;
import com.kof22.carlai.domain.PortfolioPresentation;


/** Native reviewed portfolio assumptions, comparison and current dated-rate corrections. */
final class PortfolioProcesses
{
   private PortfolioProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var plans = new PortfolioPlans(service);
      var table = CarlMetadata.table("carlPortfolioMoves", "Reviewed Financing Allocations", "carl_portfolio_move_view", "offer_id:L,as_of:D,capacity:M,minimum_amount:M,minimum_fraction:R,monthly_fee:M,first_payment:D,proposed_payment:M,currency:S,source_stale:B");
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlDebtRateChange", "Record Future Debt Rate or Fee", List.of(pick("account", "carlAccounts", true), f("expectedRevision", QFieldType.LONG), f("effective", QFieldType.DATE), f("apr", QFieldType.DECIMAL).withLabel("Annual decimal rate (0.24 means 24%)"), f("monthlyFee", QFieldType.DECIMAL), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         new DebtPlans(service).rateChange(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("account")), Long.parseLong(in.getValueString("expectedRevision")), in.getValueLocalDate("effective"), new BigDecimal(in.getValueString("apr")), new BigDecimal(in.getValueString("monthlyFee")), in.getValueString("evidence"));
         out.addValue("result", "Effective-dated rate and fee recorded. The dated statement balance remains unchanged. Regenerate affected plans before using them.");
      }));
      var fields = new ArrayList<QFieldMetaData>(List.of(f("title", QFieldType.STRING), f("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), pick("offer", "carlFinancingOffers", true), f("asOf", QFieldType.DATE), f("capacity", QFieldType.DECIMAL), f("minimum", QFieldType.DECIMAL), f("minimumFraction", QFieldType.DECIMAL), f("monthlyFee", QFieldType.DECIMAL), f("firstPayment", QFieldType.DATE), f("proposedPayment", QFieldType.DECIMAL)));
      for(int n = 1; n <= 4; n++)
      {
         fields.add(pick("sourceAccount" + n, "carlAccounts", n == 1));
         fields.add(f("allocatedAmount" + n, QFieldType.DECIMAL).withIsRequired(n == 1));
      }
      fields.add(f("evidence", QFieldType.TEXT));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlReviewPortfolioMove", "Review Financing Allocation (up to four sources)", fields, (in, out) ->
      {
         var allocations = new LinkedHashMap<Long, BigDecimal>();
         for(int n = 1; n <= 4; n++)
         {
            String account = in.getValueString("sourceAccount" + n);
            String amount = in.getValueString("allocatedAmount" + n);
            if(account == null && amount == null)
            {
               continue;
            }
            if(account == null || amount == null || allocations.put(Long.parseLong(account), new BigDecimal(amount)) != null)
            {
               throw new IllegalArgumentException("Select each source once, with its explicit amount");
            }
         }
         var destination = new PortfolioPlans.Destination(Long.parseLong(in.getValueString("offer")), in.getValueLocalDate("asOf"), new BigDecimal(in.getValueString("capacity")), new BigDecimal(in.getValueString("minimum")), new BigDecimal(in.getValueString("minimumFraction")), new BigDecimal(in.getValueString("monthlyFee")), in.getValueLocalDate("firstPayment"), new BigDecimal(in.getValueString("proposedPayment")));
         long id = plans.createMove(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), in.getValueString("title"), in.getValueString("visibility"), destination, allocations, in.getValueString("evidence"));
         out.addValue("result", "Reviewed hypothetical allocation " + id + " saved. No loan application, transfer or acceptance occurred. Select this allocation in a portfolio comparison; corrections require a new review.");
      }));
      fields = new ArrayList<>(List.of(f("asOf", QFieldType.DATE), f("currency", QFieldType.STRING), f("monthlyBudget", QFieldType.DECIMAL), f("horizonMonths", QFieldType.INTEGER), f("rollover", QFieldType.STRING).withPossibleValueSourceName("carlRollover"), f("budgetEvidence", QFieldType.TEXT)));
      for(int n = 1; n <= 6; n++)
      {
         fields.add(pick("account" + n, "carlAccounts", n == 1));
      }
      for(int n = 1; n <= 4; n++)
      {
         fields.add(pick("move" + n, "carlPortfolioMoves", false));
      }
      var comparisonProcess = CarlMetadata.process("carlComparePortfolio", "Compare Selected Debt Portfolio", fields, (in, out) ->
      {
         var accounts = new LinkedHashSet<Long>();
         var moves = new LinkedHashSet<Long>();
         for(int n = 1; n <= 6; n++)
         {
            String value = in.getValueString("account" + n);
            if(value != null && !accounts.add(Long.parseLong(value)))
            {
               throw new IllegalArgumentException("Select each debt once");
            }
         }
         for(int n = 1; n <= 4; n++)
         {
            String value = in.getValueString("move" + n);
            if(value != null && !moves.add(Long.parseLong(value)))
            {
               throw new IllegalArgumentException("Select each allocation once");
            }
         }
         var scope = CarlService.Scope.privateFor(CarlMetadata.principal());
         long id = plans.compare(scope, UUID.fromString(in.getValueString("requestId")), accounts, moves, in.getValueLocalDate("asOf"), in.getValueString("currency"), new BigDecimal(in.getValueString("monthlyBudget")), in.getValueInteger("horizonMonths"), FinancialPlanning.Strategy.valueOf(in.getValueString("rollover")), in.getValueString("budgetEvidence"));
         out.addValue("result.html", render(new PortfolioPresentation(service).summary(scope, id)));
      });
      ((com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData) comparisonProcess.getStep("result")).withComponents(List.of(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData().withType(com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType.HTML)));
      CarlMetadata.add(instance, app, comparisonProcess);
   }



   private static String render(com.fasterxml.jackson.databind.JsonNode summary)
   {
      var html = new StringBuilder("<h2>Selected debt portfolio</h2><p>Budget and affordability are not yet qualified. These are monthly estimates over the selected accessible debts.</p><p>As of ").append(escape(summary.path("asOf").asText())).append("; currency ").append(escape(summary.path("currency").asText())).append(". Saved report ").append(summary.path("artifactId").asLong()).append(".</p><table><thead><tr><th>Strategy</th><th style='text-align:right;padding:8px'>Interest</th><th style='text-align:right;padding:8px'>Financed fees</th><th style='text-align:right;padding:8px'>Cash fees</th><th style='text-align:right;padding:8px'>Monthly fees</th><th>Payoff</th></tr></thead><tbody>");
      for(String strategy : List.of("CURRENT_PAYMENT", "MINIMUM_ONLY", "AVALANCHE", "SNOWBALL", "USER_DIRECTED"))
      {
         var values = summary.path("strategies").path(strategy);
         if(values.isMissingNode())
         {
            continue;
         }
         html.append("<tr><td>").append(strategy.replace('_', ' ')).append("</td>");
         for(String field : List.of("interest", "financedFees", "upfrontCashFees", "monthlyFees"))
         {
            html.append("<td style='text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap;padding:8px'>").append(escape(com.kof22.carlai.report.MoneyPresentation.format(values.path(field).isNumber() ? values.path(field).decimalValue() : null, summary.path("currency").asText()))).append("</td>");
         }
         html.append("<td>").append(escape(values.path("payoffDate").isNull() ? "Not established within horizon" : values.path("payoffDate").asText("Not established"))).append("</td></tr>");
      }
      html.append("</tbody></table><p>Source records: ").append(summary.path("sourceCount").asInt()).append(". Input or projection gaps: ").append(summary.path("gapCount").asInt()).append(". Review all assumptions and schedules in the protected Reports, Plans and Drafts record before deciding.</p><p>").append(escape(summary.path("limitations").asText())).append("</p>");
      return html.toString().replace("<table>", "<table style=\"width:100%;border-collapse:collapse;margin:16px 0\">").replace("<th>", "<th style=\"text-align:left;padding:8px;border-bottom:1px solid #cbd5e1\">").replace("<td>", "<td style=\"padding:8px;vertical-align:top;border-bottom:1px solid #e2e8f0\">").replace("<h2>", "<h2 style=\"font-size:20px;font-weight:600;margin-bottom:12px\">").replace("<h3>", "<h3 style=\"font-weight:600;margin-top:12px\">").replace("<p>", "<p style=\"margin:10px 0\">");
   }



   private static String escape(String value)
   {
      return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }



   private static QFieldMetaData pick(String name, String table, boolean required)
   {
      return f(name, QFieldType.LONG).withPossibleValueSourceName(table).withIsRequired(required);
   }
}
