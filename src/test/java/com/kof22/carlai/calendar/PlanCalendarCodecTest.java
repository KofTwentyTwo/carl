
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VToDo;
import net.fortuna.ical4j.model.property.DateProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PlanCalendarCodecTest
{
   private static final UUID ID = UUID.fromString("9c4d7c99-6b6b-4372-ae89-f9d2644d14f0");
   private static final Instant STAMP = Instant.parse("2026-09-29T12:00:00Z");

   @Test
   void timedPlanPreservesSourceZoneAndSpringDaylightSavingInstants() throws Exception
   {
      var zone = ZoneId.of("America/Chicago");
      var text = PlanCalendarCodec.event(item("Review plan"), ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, zone),
         ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, zone));
      var calendar = new CalendarBuilder().build(new StringReader(text));
      VEvent event = calendar.<VEvent>getComponent("VEVENT").orElseThrow();
      DateProperty<?> start = event.getRequiredProperty("DTSTART");
      DateProperty<?> end = event.getRequiredProperty("DTEND");
      assertEquals(Instant.parse("2026-03-08T07:30:00Z"), Instant.from(start.getDate()));
      assertEquals(Instant.parse("2026-03-08T08:30:00Z"), Instant.from(end.getDate()));
      assertTrue(text.contains("TZID=America/Chicago"));
      assertEquals("TENTATIVE", event.getRequiredProperty("STATUS").getValue());
      assertEquals(ID.toString(), event.getRequiredProperty("UID").getValue());
      assertFalse(calendar.validate().hasErrors());
   }



   @Test
   void allDayUsesExclusiveDateEndAndDoesNotPromoteTextIntoInvitations() throws Exception
   {
      String title = "Review budget, family\nATTENDEE:mailto:untrusted@example.invalid";
      String text = PlanCalendarCodec.allDay(item(title), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
      var calendar = new CalendarBuilder().build(new StringReader(text));
      VEvent event = calendar.<VEvent>getComponent("VEVENT").orElseThrow();
      assertEquals(title, event.getRequiredProperty("SUMMARY").getValue());
      assertTrue(event.getProperty(Property.ATTENDEE).isEmpty());
      assertEquals(LocalDate.of(2026, 10, 1), ((DateProperty<?>) event.getRequiredProperty("DTSTART")).getDate());
      assertEquals(LocalDate.of(2026, 10, 2), ((DateProperty<?>) event.getRequiredProperty("DTEND")).getDate());
      assertFalse(calendar.validate().hasErrors());
      assertThrows(IllegalArgumentException.class, () -> PlanCalendarCodec.allDay(item("x"), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)));
   }



   @Test
   void reminderRoundTripsDueDateAndReportedCompletionWithoutSchedulingMessages() throws Exception
   {
      String text = PlanCalendarCodec.reminder(item("Collect statement"), LocalDate.of(2026, 10, 1), STAMP);
      var calendar = new CalendarBuilder().build(new StringReader(text));
      VToDo task = calendar.<VToDo>getComponent("VTODO").orElseThrow();
      assertEquals("COMPLETED", task.getRequiredProperty("STATUS").getValue());
      assertEquals("100", task.getRequiredProperty("PERCENT-COMPLETE").getValue());
      assertEquals(STAMP, ((DateProperty<?>) task.getRequiredProperty("COMPLETED")).getDate());
      assertTrue(calendar.getProperty("METHOD").isEmpty());
      assertTrue(task.getProperty("ATTENDEE").isEmpty());
      assertFalse(calendar.validate().hasErrors());
      assertEquals(text, PlanCalendarCodec.reminder(item("Collect statement"), LocalDate.of(2026, 10, 1), STAMP));
   }



   private static PlanCalendarCodec.Item item(String title)
   {
      return new PlanCalendarCodec.Item(ID, 2, title, "Synthetic plan step; completion is reported, not proof of payment", "Home", STAMP);
   }
}
