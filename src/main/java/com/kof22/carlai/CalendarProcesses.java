
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CalendarWorkflows;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.PlanLifecycle;


/** Native plan publication exposes durable outcomes without arbitrary calendar URLs or imported commands. */
final class CalendarProcesses
{
   private CalendarProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service, CalendarWorkflows calendars)
   {
      var table = CarlMetadata.table("carlCalendarOperations", "Calendar Publication History", "carl_native_calendar_operation_view", "plan_id:L,step_id:S,component:S,plan_version:L,action:S,status:S,diagnostic:S,updated_at:I,last_success:I,read_state:S,last_read_success:I,retired:B", false).withField(new QFieldMetaData("id", QFieldType.STRING));
      instance.addTable(table);
      app.withChild(table);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      var reminders = CarlMetadata.table("carlReminderObservations", "Reminder Completion Review", "carl_reminder_observation_view", "plan_id:L,step_id:S,plan_version:L,remote_state:S,reported_completed:I,observed_at:I,limitation:S,review_state:S,reviewed_version:L,review_note:S", false);
      instance.addTable(reminders);
      app.withChild(reminders);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable(reminders.getName()));
      CarlMetadata.choices(instance, "carlReminderDecision", List.of("ACCEPT_REPORTED_COMPLETE", "DISMISS"));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlReviewReminder", "Review Remote Reminder Completion", List.of(new QFieldMetaData("observation", QFieldType.LONG).withIsRequired(true).withPossibleValueSourceName("carlReminderObservations"), new QFieldMetaData("expectedVersion", QFieldType.INTEGER).withIsRequired(true), field("decision").withPossibleValueSourceName("carlReminderDecision"), field("note")), (in, out) ->
      {
         int version = new com.kof22.carlai.domain.ReminderObservations(service).review(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("observation")), in.getValueInteger("expectedVersion"), in.getValueString("decision"), in.getValueString("note"));
         out.addValue("result", "Reminder reviewed at plan version " + version + ". Reported completion is not verified financial execution. No payment, purchase or vendor commitment was performed.");
      }));
      if(!calendars.agendaCollections().isEmpty())
      {
         CarlMetadata.choices(instance, "carlAgendaCollection", calendars.agendaCollections().stream().sorted().toList());
         CarlMetadata.add(instance, app, CarlMetadata.process("carlSyncAgenda", "Refresh Shared Calendar Agenda", List.of(field("collection").withPossibleValueSourceName("carlAgendaCollection"), new QFieldMetaData("from", QFieldType.DATE).withIsRequired(true), new QFieldMetaData("through", QFieldType.DATE).withIsRequired(true)), (in, out) ->
         {
            var result = calendars.synchronizeAgenda(CarlMetadata.principal(), in.getValueString("collection"), UUID.fromString(in.getValueString("requestId")), in.getValueLocalDate("from"), in.getValueLocalDate("through"));
            out.addValue("result", "Calendar refresh: " + result.get("requestStatus") + "\nConnection: " + result.get("sync_state") + "\nCovered dates: " + result.get("coverage_from") + " through " + result.get("coverage_through") + "\nLast successful refresh: " + result.get("last_success") + "\nIssue: " + (result.get("failure_code") == null ? "None reported" : result.get("failure_code")) + "\n\nOnly this date window and configured calendar were synchronized. Private event details remain excluded; synchronization does not book or change an appointment.");
         }));
      }
      if(calendars.collections().isEmpty())
      {
         CarlMetadata.add(instance, app, CarlMetadata.process("carlCalendarSetupStatus", "Shared Calendar Connection Status", List.of(), (in, out) -> out.addValue("result", "Shared calendar and reminders are not configured. The operator must qualify the designated Synology collections and their exact family audience before publication.")));
         return;
      }
      CarlMetadata.choices(instance, "carlCalendarCollection", calendars.collections().stream().sorted().toList());
      CarlMetadata.choices(instance, "carlCalendarOperation", List.of("PUBLISH", "RETIRE", "RECONCILE", "SYNCHRONIZE", "STATUS"));
      PlanProcesses.register(instance, app, new PlanLifecycle(service), "carlPublishCalendar", "Update Shared Calendar or Reminder", List.of(field("collection").withPossibleValueSourceName("carlCalendarCollection"), field("operation").withPossibleValueSourceName("carlCalendarOperation"), field("stepId").withPossibleValueSourceName("carlPlanSteps"), new QFieldMetaData("priorRequest", QFieldType.STRING).withPossibleValueSourceName("carlCalendarOperations").withLabel("Prior operation to reconcile (only for RECONCILE)")), (in, out) ->
      {
         String action = in.getValueString("operation");
         UUID request = action.equals("RECONCILE") ? UUID.fromString(in.getValueString("priorRequest")) : UUID.fromString(in.getValueString("taskId"));
         var result = calendars.execute(CarlMetadata.principal(), in.getValueString("collection"), action, request, Long.parseLong(in.getValueString("planId")), UUID.fromString(in.getValueString("stepId")), in.getValueInteger("expectedVersion"));
         out.addValue("result", result.toString() + "\nCalendar state does not confirm payment, purchase or a booked vendor appointment. UNKNOWN requires reconciliation of the same operation.");
      });
   }



   private static QFieldMetaData field(String name)
   {
      return new QFieldMetaData(name, QFieldType.STRING).withIsRequired(true);
   }
}
