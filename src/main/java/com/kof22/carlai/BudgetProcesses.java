/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kof22.carlai.domain.BudgetRecords;
import com.kof22.carlai.domain.CarlService;


/** Native human workflows use the same financial service as authorized read capabilities. */
final class BudgetProcesses
{
   private BudgetProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var budgets = new BudgetRecords(service);
      instance.getTable("carlBudgets").addField(new QFieldMetaData("revision", QFieldType.LONG));
      instance.getTable("carlBudgets").withSection(new QFieldSection().withName("budgetRevision").withLabel("Review before correction").withTier(Tier.T2).withFieldNames(List.of("revision")));
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("carlBudgets"));
      var origins = CarlMetadata.table("carlManualTransactions", "Human-entered Transactions", "carl_manual_transaction_view", "account_id:L,effective_date:D,amount:M,currency:S,classification:S,category:S,entered_by:L,entered_at:I,original_evidence:T,original_amount:M,original_classification:S");
      instance.addTable(origins);
      app.withChild(origins);
      CarlMetadata.add(instance, app, CarlMetadata.process("carlManualTransaction", "Record Human-entered Transaction", List.of(field("account", QFieldType.LONG).withPossibleValueSourceName("carlAccounts"), field("date", QFieldType.DATE), field("amount", QFieldType.DECIMAL).withLabel("Signed amount (outflow negative; refund positive)"), field("classification", QFieldType.STRING).withPossibleValueSourceName("carlClassification"), field("category", QFieldType.STRING), field("title", QFieldType.STRING), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = budgets.manualTransaction(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("account")), in.getValueLocalDate("date"), new BigDecimal(in.getValueString("amount")), in.getValueString("classification"), in.getValueString("category"), in.getValueString("title"), in.getValueString("evidence"));
         out.addValue("result", "Human-entered transaction " + id + " saved with original evidence; no external transaction occurred.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCreateBudget", "Create Category Budget", List.of(field("title", QFieldType.STRING), field("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), field("category", QFieldType.STRING), field("from", QFieldType.DATE), field("through", QFieldType.DATE), field("amount", QFieldType.DECIMAL), field("currency", QFieldType.STRING), field("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = budgets.create(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), in.getValueString("title"), in.getValueString("visibility"), in.getValueString("category"), in.getValueLocalDate("from"), in.getValueLocalDate("through"), new BigDecimal(in.getValueString("amount")), in.getValueString("currency"), in.getValueString("evidence"));
         out.addValue("result", "Category budget " + id + " saved; available cash remains unqualified.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCorrectBudget", "Correct Category Budget Amount", List.of(field("budget", QFieldType.LONG).withPossibleValueSourceName("carlBudgets"), field("expectedRevision", QFieldType.LONG), field("amount", QFieldType.DECIMAL), field("reason", QFieldType.TEXT)), (in, out) ->
      {
         budgets.correct(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("budget")), Long.parseLong(in.getValueString("expectedRevision")), new BigDecimal(in.getValueString("amount")), in.getValueString("reason"));
         out.addValue("result", "Budget correction saved with attribution.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlBudgetVariance", "Review Budget Versus Actual", List.of(field("budget", QFieldType.LONG).withPossibleValueSourceName("carlBudgets")), (in, out) ->
      {
         var result = budgets.variance(CarlService.Scope.privateFor(CarlMetadata.principal()), Long.parseLong(in.getValueString("budget")));
         out.addValue("result", "Budget versus actual — partial accessible scope\nCategory: " + result.get("category") + "\nCurrency: " + result.get("currency") + "\nPeriod: " + result.get("from") + " through " + result.get("through") + "\nBudget: " + money(result, "budget") + "\nActual expenses less refunds: " + money(result, "actualSpending") + "\nRemaining category budget: " + money(result, "remainingBudget") + "\nUnclassified records: " + result.get("unclassifiedCount") + "\nExcluded transfers/principal/other records: " + result.get("excludedNonExpenseCount") + "\n\nThis is not available cash or confirmed whole-household coverage. Source transaction records: " + result.get("sources"));
         out.addValue("result.html", presentation(result));
      }));
   }



   /** A scoped financial summary with independently aligned monetary figures. */
   static String presentation(java.util.Map<String, Object> result)
   {
      var html = new StringBuilder("<h2>Budget versus actual — partial accessible scope</h2><p>Category: ").append(escape(result.get("category")))
         .append(". Currency: ").append(escape(result.get("currency"))).append(". Period: ").append(escape(result.get("from"))).append(" through ").append(escape(result.get("through"))).append(".</p><table><tbody>");
      for(var row : List.of(List.of("Budget", "budget"), List.of("Actual expenses less refunds", "actualSpending"), List.of("Remaining category budget", "remainingBudget")))
      {
         html.append("<tr><th scope='row' style='text-align:left;padding:8px'>").append(row.get(0)).append("</th><td style='text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap;padding:8px'>").append(escape(money(result, row.get(1)))).append("</td></tr>");
      }
      return html.append("</tbody></table><p>Unclassified records: ").append(escape(result.get("unclassifiedCount"))).append(". Excluded transfers/principal/other records: ").append(escape(result.get("excludedNonExpenseCount")))
         .append(".</p><p>This is not available cash or confirmed whole-household coverage. Source transaction records: ").append(escape(result.get("sources"))).append(".</p>").toString();
   }



   private static String money(java.util.Map<String, Object> result, String field)
   {
      return com.kof22.carlai.report.MoneyPresentation.format((BigDecimal) result.get(field), (String) result.get("currency"));
   }



   private static String escape(Object value)
   {
      return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
   }



   private static QFieldMetaData field(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
