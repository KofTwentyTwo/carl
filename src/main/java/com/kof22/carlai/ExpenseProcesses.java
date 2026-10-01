/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.ExpenseForecast;
import com.kof22.carlai.domain.ExpenseRecords;


/** Reviewed expense schedules and payments share Carl's authoritative cash forecasts. */
final class ExpenseProcesses
{
   private ExpenseProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var expenses = new ExpenseRecords(service);
      for(var table : List.of(CarlMetadata.table("carlExpenses", "Expense Schedules", "carl_expense_view", "revision:L,currency:S,cadence:S,first_due:D,last_due:D,base_amount:M,kind:S,basis:S,property_id:L"), CarlMetadata.table("carlExpenseActuals", "Evidenced Expense Payments", "carl_expense_actual_view", "revision:L,transaction_id:L,paid_date:D,currency:S,amount:M,kind:S,source_stale:B"), CarlMetadata.table("carlExpenseSettlements", "Expense Payment Applications", "carl_expense_settlement_view", "expense_id:L,due_date:D,actual_id:L,amount:M,currency:S,active:B")))
      {
         instance.addTable(table);
         app.withChild(table);
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      }
      CarlMetadata.choices(instance, "carlExpenseCadence", Arrays.stream(ExpenseForecast.Cadence.values()).map(Enum::name).toList());
      CarlMetadata.choices(instance, "carlExpenseKind", Arrays.stream(ExpenseForecast.Kind.values()).map(Enum::name).toList());
      CarlMetadata.choices(instance, "carlExpenseBasis", Arrays.stream(ExpenseForecast.Basis.values()).map(Enum::name).toList());
      var fields = new ArrayList<QFieldMetaData>(List.of(f("title", QFieldType.STRING), visibility(), f("evidence", QFieldType.TEXT)));
      fields.addAll(scheduleFields());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCreateExpense", "Record Expense Schedule", fields, (in, out) ->
      {
         long id = expenses.create(CarlMetadata.principal(), request(in), in.getValueString("title"), in.getValueString("visibility"), in.getValueString("evidence"), schedule(in));
         out.addValue("result", "Expense schedule " + id + " recorded. Estimates and missing amounts remain uncertain; reserves are earmarks, not expenses.");
      }));
      var correction = new ArrayList<QFieldMetaData>(List.of(pick("expense", "carlExpenses"), f("expectedRevision", QFieldType.LONG), f("reason", QFieldType.TEXT)));
      correction.addAll(scheduleFields());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCorrectExpense", "Correct Expense Schedule", correction, (in, out) ->
      {
         expenses.correct(CarlMetadata.principal(), request(in), id(in, "expense"), id(in, "expectedRevision"), schedule(in), in.getValueString("reason"));
         out.addValue("result", "Schedule corrected with attribution; dependent payment applications must be reviewed before material changes.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRecordExpenseActual", "Record Evidenced Expense Payment", List.of(f("paid", QFieldType.DATE), f("currency", QFieldType.STRING), f("amount", QFieldType.DECIMAL), kind(), visibility(), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = expenses.manualActual(CarlMetadata.principal(), request(in), in.getValueLocalDate("paid"), in.getValueString("currency"), money(in, "amount"), ExpenseForecast.Kind.valueOf(in.getValueString("kind")), in.getValueString("visibility"), in.getValueString("evidence"));
         out.addValue("result", "Payment evidence " + id + " saved as a human assertion. Carl has not sent a payment.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlClassifyExpenseActual", "Use Imported Expense Payment", List.of(pick("transaction", "carlTransactions"), kind(), visibility(), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = expenses.classifyActual(CarlMetadata.principal(), request(in), id(in, "transaction"), ExpenseForecast.Kind.valueOf(in.getValueString("kind")), in.getValueString("visibility"), in.getValueString("evidence"));
         out.addValue("result", "Imported payment snapshot " + id + " saved with its source revision.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRefreshExpenseActual", "Review Changed Expense Payment", List.of(pick("actual", "carlExpenseActuals"), f("expectedRevision", QFieldType.LONG), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         expenses.refreshActual(CarlMetadata.principal(), request(in), id(in, "actual"), id(in, "expectedRevision"), in.getValueString("evidence"));
         out.addValue("result", "Payment snapshot refreshed with attribution. Original import evidence is retained.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlSettleExpense", "Apply Payment to Expense Due", List.of(pick("expense", "carlExpenses"), f("due", QFieldType.DATE), pick("actual", "carlExpenseActuals"), f("amount", QFieldType.DECIMAL), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = expenses.settle(CarlMetadata.principal(), request(in), id(in, "expense"), in.getValueLocalDate("due"), id(in, "actual"), money(in, "amount"), in.getValueString("evidence"));
         out.addValue("result", "Payment application " + id + " saved. This prevents counting the applied amount again as an unpaid obligation.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlUnsettleExpense", "Correct Expense Payment Application", List.of(pick("settlement", "carlExpenseSettlements"), f("reason", QFieldType.TEXT)), (in, out) ->
      {
         expenses.unsettle(CarlMetadata.principal(), request(in), id(in, "settlement"), in.getValueString("reason"));
         out.addValue("result", "Application retired with attribution; evidence retained.");
      }));
      var selection = new ArrayList<QFieldMetaData>(List.of(pick("plan", "carlCashPlans"), f("evidence", QFieldType.TEXT).withLabel("Reviewed complete selection; no duplicate manual events or reserves in base floor")));
      for(int i = 1; i <= 4; i++)
      {
         selection.add(pick("expense" + i, "carlExpenses").withIsRequired(false));
         selection.add(pick("actual" + i, "carlExpenseActuals").withIsRequired(false));
      }
      CarlMetadata.add(instance, app, CarlMetadata.process("carlSelectCashExpenses", "Replace Cash Forecast Expense Selection", selection, (in, out) ->
      {
         expenses.attachCashPlan(CarlMetadata.principal(), request(in), id(in, "plan"), selected(in, "expense"), selected(in, "actual"), in.getValueString("evidence"));
         out.addValue("result", "Complete selection replaced. Selected reserves are additional to the base floor and protected for the full interval. Remove overlapping manual events or reserve assumptions before relying on a purchase assessment. This guided form selects up to four schedules and four standalone payments.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlExpenseReport", "Prepare Expense Forecast", List.of(pick("expense", "carlExpenses"), f("from", QFieldType.DATE), f("through", QFieldType.DATE), f("asOf", QFieldType.DATE)), (in, out) ->
      {
         long id = expenses.report(CarlService.Scope.privateFor(CarlMetadata.principal()), request(in), Set.of(id(in, "expense")), Set.of(), in.getValueLocalDate("from"), in.getValueLocalDate("through"), in.getValueLocalDate("asOf"));
         out.addValue("result", "Forecast " + id + " saved in Reports, Plans and Drafts. It covers the selected schedule and linked payment evidence, not the entire household.");
      }));
   }



   private static List<QFieldMetaData> scheduleFields()
   {
      var fields = new ArrayList<QFieldMetaData>(List.of(f("currency", QFieldType.STRING), f("cadence", QFieldType.STRING).withPossibleValueSourceName("carlExpenseCadence"), f("firstDue", QFieldType.DATE), f("lastDue", QFieldType.DATE).withIsRequired(false), f("amount", QFieldType.DECIMAL).withIsRequired(false), kind(), f("basis", QFieldType.STRING).withPossibleValueSourceName("carlExpenseBasis"), pick("property", "carlProperties").withIsRequired(false)));
      for(int month = 1; month <= 12; month++)
      {
         fields.add(f("month" + month, QFieldType.DECIMAL).withIsRequired(false).withLabel(java.time.Month.of(month) + " amount override (optional)"));
      }
      return fields;
   }



   private static ExpenseRecords.Schedule schedule(RunBackendStepInput in)
   {
      var seasons = new LinkedHashMap<Integer, BigDecimal>();
      for(int month = 1; month <= 12; month++)
      {
         var amount = money(in, "month" + month);
         if(amount != null)
         {
            seasons.put(month, amount);
         }
      }
      return new ExpenseRecords.Schedule(in.getValueString("currency"), ExpenseForecast.Cadence.valueOf(in.getValueString("cadence")), in.getValueLocalDate("firstDue"), in.getValueLocalDate("lastDue"), money(in, "amount"), seasons, ExpenseForecast.Kind.valueOf(in.getValueString("kind")), ExpenseForecast.Basis.valueOf(in.getValueString("basis")), optionalId(in, "property"));
   }



   private static Set<Long> selected(RunBackendStepInput in, String prefix)
   {
      var ids = new LinkedHashSet<Long>();
      for(int i = 1; i <= 4; i++)
      {
         var id = optionalId(in, prefix + i);
         if(id != null && !ids.add(id))
         {
            throw new IllegalArgumentException("Select each input once");
         }
      }
      return ids;
   }



   private static BigDecimal money(RunBackendStepInput in, String name)
   {
      var value = in.getValueString(name);
      return value == null || value.isBlank() ? null : new BigDecimal(value);
   }



   private static Long optionalId(RunBackendStepInput in, String name)
   {
      var value = in.getValueString(name);
      return value == null || value.isBlank() ? null : Long.valueOf(value);
   }



   private static long id(RunBackendStepInput in, String name)
   {
      return Long.parseLong(in.getValueString(name));
   }



   private static UUID request(RunBackendStepInput in)
   {
      return UUID.fromString(in.getValueString("requestId"));
   }



   private static QFieldMetaData visibility()
   {
      return f("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility");
   }



   private static QFieldMetaData kind()
   {
      return f("kind", QFieldType.STRING).withPossibleValueSourceName("carlExpenseKind");
   }



   private static QFieldMetaData pick(String name, String table)
   {
      return f(name, QFieldType.LONG).withPossibleValueSourceName(table);
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
