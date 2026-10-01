/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.PurchaseAssessments;
import com.kof22.carlai.domain.PurchasePresentation;


/** Native human purchase comparison using dated reserves, selected offers and supported card terms. */
final class PurchaseProcesses
{
   private PurchaseProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var fields = new ArrayList<QFieldMetaData>(List.of(f("cashPlan", QFieldType.LONG).withPossibleValueSourceName("carlCashPlans"), f("purchaseDate", QFieldType.DATE), f("allInPrice", QFieldType.DECIMAL).withLabel("All-in price including taxes and delivery"), f("purpose", QFieldType.STRING), f("allInCostsKnown", QFieldType.BOOLEAN), f("card", QFieldType.LONG).withPossibleValueSourceName("carlAccounts").withIsRequired(false).withLabel("Optional existing card"), f("cardPayoffDate", QFieldType.DATE).withIsRequired(false), f("graceConfirmed", QFieldType.BOOLEAN).withIsRequired(false).withLabel("Reviewed terms establish interest-free grace for this purchase"), f("balanceReviewed", QFieldType.BOOLEAN).withIsRequired(false).withLabel("I reviewed the card balance and full repayment commitment"), f("cardEvidence", QFieldType.TEXT).withIsRequired(false)));
      for(int n = 1; n <= 5; n++)
      {
         fields.add(f("offer" + n, QFieldType.LONG).withPossibleValueSourceName("carlFinancingOffers").withIsRequired(false).withLabel("Optional purchase financing offer " + n));
      }
      var process = CarlMetadata.process("carlComparePurchaseOptions", "Compare Cash, Card and Store Financing", fields, (in, out) ->
      {
         PurchaseAssessments.CardInput card = null;
         if(in.getValueString("card") != null)
         {
            card = new PurchaseAssessments.CardInput(Long.parseLong(in.getValueString("card")), in.getValueLocalDate("cardPayoffDate"), Boolean.TRUE.equals(in.getValueBoolean("graceConfirmed")), Boolean.TRUE.equals(in.getValueBoolean("balanceReviewed")), in.getValueString("cardEvidence"));
         }
         var offers = new ArrayList<Long>();
         for(int n = 1; n <= 5; n++)
         {
            if(in.getValueString("offer" + n) != null)
            {
               offers.add(Long.parseLong(in.getValueString("offer" + n)));
            }
         }
         var scope = CarlService.Scope.privateFor(CarlMetadata.principal());
         long id = new PurchaseAssessments(service).compare(scope, UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("cashPlan")), in.getValueLocalDate("purchaseDate"), new BigDecimal(in.getValueString("allInPrice")), in.getValueString("purpose"), Boolean.TRUE.equals(in.getValueBoolean("allInCostsKnown")), card, offers);
         out.addValue("result.html", render(new PurchasePresentation(service).summary(scope, id)));
      });
      ((QFrontendStepMetaData) process.getStep("result")).withComponents(List.of(new QFrontendComponentMetaData().withType(QComponentType.HTML)));
      CarlMetadata.add(instance, app, process);
   }



   private static String render(com.fasterxml.jackson.databind.JsonNode result)
   {
      String currency = result.path("currency").asText();
      var html = new StringBuilder("<h2>Purchase payment choices</h2><p>").append(escape(result.path("recommendation").asText())).append("</p><p>Currency: ").append(escape(result.path("currency").asText())).append(". All-in price: ").append(money(result.path("allInPrice"), currency)).append(". Maximum supported cash budget: ").append(result.path("maximumCashBudget").isNull() ? "Not established" : money(result.path("maximumCashBudget"), currency)).append(".</p><table><thead><tr><th>Option</th><th>Assessment</th><th style='text-align:right;padding:8px'>Total cash outlay</th><th style='text-align:right;padding:8px'>Interest and fees</th><th>Last payment</th><th style='text-align:right;padding:8px'>Lowest forecast cash</th></tr></thead><tbody>");
      for(var option : result.path("options"))
      {
         html.append("<tr>");
         for(String field : List.of("id", "state", "totalCashOutlay", "financeCost", "lastPayment", "minimumCashAfterPayments"))
         {
            boolean monetary = List.of("totalCashOutlay", "financeCost", "minimumCashAfterPayments").contains(field);
            html.append(monetary ? "<td style='text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap;padding:8px'>" : "<td>").append(monetary ? money(option.path(field), currency) : (option.path(field).isNull() ? "Unknown" : escape(option.path(field).asText().replace('_', ' ')))).append("</td>");
         }
         html.append("</tr>");
      }
      html.append("</tbody></table><h3>Evidence and limitations</h3><ul>");
      for(var option : result.path("options"))
      {
         for(var gap : option.path("gaps"))
         {
            html.append("<li>").append(escape(option.path("id").asText())).append(": ").append(escape(gap.asText())).append("</li>");
         }
      }
      html.append("</ul><p>").append(escape(result.path("limitations").asText())).append("</p><p>Saved protected report ").append(result.path("artifactId").asLong()).append(" includes source evidence and complete repayment dates. This is a conditional planning comparison, not an order or financing application.</p>");
      return html.toString().replace("<table>", "<table style=\"width:100%;border-collapse:collapse;margin:16px 0\">").replace("<th>", "<th style=\"text-align:left;padding:8px;border-bottom:1px solid #cbd5e1\">").replace("<td>", "<td style=\"padding:8px;vertical-align:top;border-bottom:1px solid #e2e8f0\">").replace("<h2>", "<h2 style=\"font-size:20px;font-weight:600;margin-bottom:12px\">").replace("<h3>", "<h3 style=\"font-weight:600;margin-top:12px\">").replace("<p>", "<p style=\"margin:10px 0\">");
   }



   private static String money(com.fasterxml.jackson.databind.JsonNode value, String currency)
   {
      return escape(com.kof22.carlai.report.MoneyPresentation.format(value.isNumber() ? value.decimalValue() : null, currency));
   }



   private static String escape(String value)
   {
      return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
