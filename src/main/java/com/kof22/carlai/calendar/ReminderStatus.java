/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.io.StringReader;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.TimeZoneUpdater;
import net.fortuna.ical4j.model.component.VToDo;


/** Reads only typed task completion facts; remote prose never becomes execution authority. */
public final class ReminderStatus
{
   /** Reported completion is a provider observation, never proof of a payment or other financial result. */
   public record Observation(String state, Instant reportedCompleted, String limitation)
   {
   }
   private ReminderStatus()
   {
   }



   /** Validates one bounded, offline task with the expected application-owned UID. */
   public static Observation decode(UUID expected, String text)
   {
      if(expected == null || text == null || text.length() > 64000 || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64000 || new TimeZoneUpdater().isEnabled())
      {
         throw new IllegalArgumentException("A bounded offline reminder resource is required");
      }
      String unfolded = text.replaceAll("\\r?\\n[ \\t]", "");
      String[] lines = unfolded.split("\\r?\\n");
      if(lines.length > 500)
      {
         throw new IllegalArgumentException("Reminder property bound exceeded");
      }
      var components = new java.util.ArrayDeque<String>();
      for(String line : lines)
      {
         String key = line.split("[:;]", 2)[0].toUpperCase(Locale.ROOT);
         if(key.equals("BEGIN"))
         {
            String component = line.substring(line.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            if((components.isEmpty() && !component.equals("VCALENDAR")) || (components.size() == 1 && !component.equals("VTODO")) || components.size() >= 2)
            {
               throw new IllegalArgumentException("Unsupported or deeply nested reminder component");
            }
            components.push(component);
         }
         else if(key.equals("END"))
         {
            String component = line.substring(line.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            if(components.isEmpty() || !components.pop().equals(component))
            {
               throw new IllegalArgumentException("Unbalanced reminder component");
            }
         }
         if(Set.of("TZURL", "ATTACH", "ATTENDEE", "ORGANIZER", "METHOD", "URL", "RRULE", "RDATE", "EXDATE").contains(key))
         {
            throw new IllegalArgumentException("Unsupported reminder property");
         }
      }
      if(!components.isEmpty())
      {
         throw new IllegalArgumentException("Incomplete reminder component");
      }
      try
      {
         var calendar = new CalendarBuilder().build(new StringReader(text));
         if(calendar.getComponents().size() != 1 || !(calendar.getComponents().getFirst() instanceof VToDo task) || calendar.validate().hasErrors())
         {
            throw new IllegalArgumentException("Exactly one valid non-recurring task is required");
         }
         if(task.getProperties("UID").size() != 1 || task.getProperties("STATUS").size() != 1 || task.getProperties("COMPLETED").size() > 1 || !task.getProperty("UID").orElseThrow().getValue().equals(expected.toString()))
         {
            throw new IllegalArgumentException("Unexpected reminder identity or ambiguous status");
         }
         String status = task.getProperty("STATUS").orElseThrow().getValue().strip().toUpperCase(Locale.ROOT);
         if(!Set.of("NEEDS-ACTION", "IN-PROCESS", "COMPLETED", "CANCELLED").contains(status))
         {
            throw new IllegalArgumentException("Unsupported remote task state");
         }
         Instant completed = null;
         if(task.getProperty("COMPLETED").isPresent())
         {
            completed = Instant.from(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX", Locale.ROOT).parse(task.getProperty("COMPLETED").orElseThrow().getValue()));
         }
         return new Observation(status.equals("COMPLETED") ? "REMOTE_REPORTED_COMPLETE" : status.replace('-', '_'), completed, "Shared reminder observation only. The provider does not identify a verified household actor; financial completion remains unverified.");
      }
      catch(Exception invalid)
      {
         throw new IllegalArgumentException("Invalid or unsupported reminder status", invalid);
      }
   }
}
