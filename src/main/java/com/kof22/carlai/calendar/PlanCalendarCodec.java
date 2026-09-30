
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
import net.fortuna.ical4j.model.TimeZoneUpdater;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VToDo;
import net.fortuna.ical4j.model.property.Completed;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Due;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.PercentComplete;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.property.XProperty;


/***************************************************************************
 ** Typed, reproducible plan events/tasks without attendees, scheduling
 ** messages, arbitrary attachments or outbound notification actions.
 ** The caller supplies text permitted for the shared calendar audience.
 ***************************************************************************/
public final class PlanCalendarCodec
{
   /** Validated shared-audience content and durable plan identity. */
   public record Item(UUID id, int sequence, String title, String description, String location, Instant modified)
   {
      /** Validates plan identity and bounds all human-supplied fields. */
      public Item
      {
         if(id == null || sequence < 0 || modified == null)
         {
            throw new IllegalArgumentException("A plan identity, nonnegative revision and modification time are required");
         }
         title = text(title, 500);
         if(title.isBlank())
         {
            throw new IllegalArgumentException("A plan item title is required");
         }
         description = text(description, 10_000);
         location = text(location, 500);
      }
   }

   private PlanCalendarCodec()
   {
   }



   /** Encodes a tentative timed plan event with an explicit offline time-zone definition. */
   public static String event(Item item, ZonedDateTime from, ZonedDateTime through)
   {
      if(from == null || through == null || !from.toInstant().isBefore(through.toInstant())
         || !from.getZone().equals(through.getZone()) || new TimeZoneUpdater().isEnabled())
      {
         throw new IllegalArgumentException("A positive event interval in one explicit zone and offline timezone resolution are required");
      }
      var calendar = calendar();
      var event = new VEvent(false);
      if(from.getZone() instanceof ZoneOffset)
      {
         event.add(new DtStart<>(from.toInstant()));
         event.add(new DtEnd<>(through.toInstant()));
      }
      else
      {
         var zone = new TimeZoneRegistryImpl().getTimeZone(from.getZone().getId());
         if(zone == null)
         {
            throw new IllegalArgumentException("The source time zone has no supported calendar definition");
         }
         var definition = zone.getVTimeZone().copy();
         definition.removeAll("TZURL");
         calendar.add(definition);
         event.add(new DtStart<>(from));
         event.add(new DtEnd<>(through));
      }
      event.add(new XProperty("X-CARL-SOURCE-TIMEZONE", from.getZone().getId()));
      common(event, item);
      event.add(new Status("TENTATIVE"));
      calendar.add(event);
      return finish(calendar);
   }



   /** Encodes a tentative date-only event with an exclusive end date. */
   public static String allDay(Item item, LocalDate from, LocalDate endExclusive)
   {
      if(from == null || endExclusive == null || !from.isBefore(endExclusive))
      {
         throw new IllegalArgumentException("An all-day interval requires an exclusive end after the start date");
      }
      var event = new VEvent(false);
      event.add(new DtStart<>(from));
      event.add(new DtEnd<>(endExclusive));
      event.add(new Status("TENTATIVE"));
      common(event, item);
      var calendar = calendar();
      calendar.add(event);
      return finish(calendar);
   }



   /** Encodes a date-only task; completion reflects the supplied plan revision. */
   public static String reminder(Item item, LocalDate due, Instant completed)
   {
      if(due == null)
      {
         throw new IllegalArgumentException("A date-only reminder needs an explicit due date");
      }
      var task = new VToDo(false);
      common(task, item);
      task.add(new Due<>(due));
      task.add(new Status(completed == null ? "NEEDS-ACTION" : "COMPLETED"));
      task.add(new PercentComplete(completed == null ? 0 : 100));
      if(completed != null)
      {
         if(completed.isAfter(item.modified()))
         {
            throw new IllegalArgumentException("Completion time cannot follow the recorded plan-item revision");
         }
         task.add(new Completed(completed));
      }
      var calendar = calendar();
      calendar.add(task);
      return finish(calendar);
   }



   private static Calendar calendar()
   {
      var calendar = new Calendar();
      var version = new Version();
      version.setMaxVersion("2.0");
      calendar.add(version);
      calendar.add(new ProdId("-//KofTwentyTwo//Carl AI plans//EN"));
      return calendar;
   }



   private static void common(CalendarComponent component, Item item)
   {
      Objects.requireNonNull(item, "plan item");
      component.add(new Uid(item.id().toString()));
      component.add(new Sequence(item.sequence()));
      component.add(new DtStamp(item.modified()));
      component.add(new Summary(item.title()));
      component.add(new Description(item.description()));
      component.add(new Location(item.location()));
   }



   private static String finish(Calendar calendar)
   {
      if(calendar.validate().hasErrors())
      {
         throw new IllegalArgumentException("Plan calendar item failed validation");
      }
      return calendar.toString();
   }



   private static String text(String value, int maximum)
   {
      if(value == null || value.length() > maximum
         || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t'))
      {
         throw new IllegalArgumentException("Plan calendar text is missing, oversized or contains control characters");
      }
      return value.replace("\r\n", "\n").replace('\r', '\n');
   }
}
