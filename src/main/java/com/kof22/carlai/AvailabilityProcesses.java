/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.LocalTime;
import java.util.List;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CalendarAvailability;
import com.kof22.carlai.domain.CarlService;


/** Native possible-window review reads current authorized busy intervals and never books time. */
final class AvailabilityProcesses
{
   private AvailabilityProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      CarlMetadata.add(instance, app, CarlMetadata.process("carlSuggestAppointmentWindows", "Suggest Possible Appointment Windows", List.of(new QFieldMetaData("date", QFieldType.DATE).withIsRequired(true), new QFieldMetaData("startTime", QFieldType.STRING).withLabel("Start time in household zone (HH:MM)").withIsRequired(true), new QFieldMetaData("endTime", QFieldType.STRING).withLabel("End time in household zone (HH:MM)").withIsRequired(true), new QFieldMetaData("minimumMinutes", QFieldType.INTEGER).withIsRequired(true)), (in, out) ->
      {
         String principal = CarlMetadata.principal();
         var zone = service.member(principal).zone();
         var start = in.getValueLocalDate("date").atTime(LocalTime.parse(in.getValueString("startTime")));
         var end = in.getValueLocalDate("date").atTime(LocalTime.parse(in.getValueString("endTime")));
         if(zone.getRules().getValidOffsets(start).size() != 1 || zone.getRules().getValidOffsets(end).size() != 1)
         {
            throw new IllegalArgumentException("This local time is skipped or repeated by daylight saving. Choose an unambiguous time or supply exact instants through the client.");
         }
         var result = new CalendarAvailability(service).suggest(CarlService.Scope.privateFor(principal), start.atZone(zone).toInstant(), end.atZone(zone).toInstant(), in.getValueInteger("minimumMinutes"));
         var text = new StringBuilder("Suggestion — not scheduled\nHousehold time zone: ").append(zone).append("\nStatus: ").append(result.get("status"));
         @SuppressWarnings("unchecked")
         var windows = (List<CalendarAvailability.Window>) result.get("windows");
         for(var window : windows)
         {
            text.append("\n").append(window.from().atZone(zone)).append(" to ").append(window.through().atZone(zone));
         }
         if(windows.isEmpty())
         {
            text.append("\nNo qualifying window in the requested scoped snapshot.");
         }
         text.append("\n\n").append(result.get("limitation")).append("\nCoverage gaps: ").append(result.get("coverageGaps")).append("\nConnection freshness: ").append(result.get("connectionFreshness"));
         out.addValue("result", text.toString());
      }));
   }
}
