
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AgendaDecoderTest
{
   private static final ZoneId ZONE = ZoneId.of("America/Chicago");
   private static final Instant FROM = Instant.parse("2026-03-07T00:00:00Z");
   private static final Instant THROUGH = Instant.parse("2026-03-10T00:00:00Z");

   @Test
   void recurringOverridesAndCancellationRetainOccurrenceIdentity()
   {
      String master = event("family", "DTSTART;TZID=America/Chicago:20260307T013000\r\nDTEND;TZID=America/Chicago:20260307T023000\r\nRRULE:FREQ=DAILY;COUNT=3\r\n", "Review budget");
      String moved = event("family", "RECURRENCE-ID;TZID=America/Chicago:20260308T013000\r\nDTSTART:20260308T150000Z\r\nDTEND:20260308T160000Z\r\n", "Moved review");
      String canceled = event("family", "RECURRENCE-ID;TZID=America/Chicago:20260309T013000\r\nDTSTART;TZID=America/Chicago:20260309T013000\r\nDTEND;TZID=America/Chicago:20260309T023000\r\nSTATUS:CANCELLED\r\n", "Canceled review");
      var rows = decode(master + moved + canceled, true);
      assertEquals(3, rows.size());
      assertEquals(Instant.parse("2026-03-07T07:30:00Z"), rows.get(0).start());
      assertEquals(Instant.parse("2026-03-08T15:00:00Z"), rows.get(1).start());
      assertEquals("2026-03-08T07:30:00Z", rows.get(1).occurrenceId());
      assertTrue(rows.get(2).canceled());
      assertEquals("America/Chicago", rows.get(0).sourceZone());
   }



   @Test
   void daylightSavingAndAllDayExclusiveEndAreDeterministic()
   {
      String daily = event("daily", "DTSTART;TZID=America/Chicago:20260307T013000\r\nDTEND;TZID=America/Chicago:20260307T023000\r\nRRULE:FREQ=DAILY;COUNT=3\r\n", "Daily");
      String day = event("all-day", "DTSTART;VALUE=DATE:20260308\r\nDTEND;VALUE=DATE:20260309\r\n", "All day");
      var rows = decode(daily + day, true);
      var spring = rows.stream().filter(x -> x.title().equals("Daily") && x.start().equals(Instant.parse("2026-03-08T07:30:00Z"))).findFirst().orElseThrow();
      assertEquals(Instant.parse("2026-03-08T08:30:00Z"), spring.end());
      var allDay = rows.stream().filter(AgendaDecoder.Occurrence::allDay).findFirst().orElseThrow();
      assertEquals(Instant.parse("2026-03-08T06:00:00Z"), allDay.start());
      assertEquals(Instant.parse("2026-03-09T05:00:00Z"), allDay.end());
      assertEquals(1, AgendaDecoder.overlaps(rows).size());
   }



   @Test
   void freeBusyProjectionNeverContainsPrivateTextOrSourceLinks()
   {
      var rows = decode(event("private", "DTSTART:20260308T150000Z\r\nDTEND:20260308T160000Z\r\nCLASS:PRIVATE\r\nDESCRIPTION:Private details\r\n", "Secret appointment"), false);
      assertEquals(1, rows.size());
      assertEquals("Busy", rows.getFirst().title());
      assertEquals(null, rows.getFirst().description());
      assertEquals(null, rows.getFirst().source());
      assertFalse(rows.toString().contains("Secret appointment"));
      assertFalse(rows.toString().contains("Private details"));
   }



   @Test
   void exclusionsAndTransparentEventsDoNotProduceFalseConflicts()
   {
      String excluded = event("excluded", "DTSTART:20260307T150000Z\r\nDTEND:20260307T160000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nEXDATE:20260308T150000Z\r\n", "Excluded");
      String free = event("free", "DTSTART:20260307T150000Z\r\nDTEND:20260307T160000Z\r\nTRANSP:TRANSPARENT\r\n", "Free");
      var rows = decode(excluded + free, true);
      assertEquals(3, rows.size());
      assertTrue(AgendaDecoder.overlaps(rows).isEmpty());
   }



   @Test
   void unsupportedUnboundedRulesAndAmbiguousOverridesFailTruthfully()
   {
      assertThrows(IllegalArgumentException.class, () -> decode(event("dense", "DTSTART:20260307T150000Z\r\nDTEND:20260307T160000Z\r\nRRULE:FREQ=SECONDLY\r\n", "Dense"), true));
      String master = event("duplicate", "DTSTART:20260307T150000Z\r\nDTEND:20260307T160000Z\r\n", "One");
      assertThrows(IllegalArgumentException.class, () -> decode(master + master, true));
      String range = event("range", "RECURRENCE-ID;RANGE=THISANDFUTURE:20260307T150000Z\r\nDTSTART:20260307T160000Z\r\nDTEND:20260307T170000Z\r\n", "Range");
      assertThrows(IllegalArgumentException.class, () -> decode(range, true));
   }



   @Test
   void dateOnlyDefaultIsOneDayAndPartialDayWindowsStillIncludeIt()
   {
      String text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl fixture//EN\r\n"
         + event("default-day", "DTSTART;VALUE=DATE:20260308\r\n", "Day") + "END:VCALENDAR\r\n";
      var rows = AgendaDecoder.decode(text, URI.create("https://calendar.example.invalid/shared/day.ics"),
         Instant.parse("2026-03-08T16:00:00Z"), Instant.parse("2026-03-08T18:00:00Z"), ZONE, access(text, true));
      assertEquals(1, rows.size());
      assertEquals(Instant.parse("2026-03-08T06:00:00Z"), rows.getFirst().start());
      assertEquals(Instant.parse("2026-03-09T05:00:00Z"), rows.getFirst().end());
   }



   @Test
   void ambiguousFloatingTimeAndExcessivelyLongEventsAreRejected()
   {
      assertThrows(IllegalArgumentException.class, () -> decode(event("floating", "DTSTART:20260308T023000\r\nDTEND:20260308T033000\r\n", "Gap"), true));
      assertThrows(IllegalArgumentException.class, () -> decode(event("long", "DTSTART:20260307T150000Z\r\nDTEND:20360307T160000Z\r\n", "Long"), true));
   }



   @Test
   void countedOldSeriesIsRejectedBeforeExpansion()
   {
      String text = event("old", "DTSTART:19000307T150000Z\r\nDTEND:19000307T160000Z\r\nRRULE:FREQ=DAILY;COUNT=10000000\r\n", "Old");
      assertThrows(IllegalArgumentException.class, () -> decode(text, true));
   }



   @Test
   void privateEventInReadableCalendarRequiresItsOwnDetailGrant()
   {
      String events = event("private-person@example.invalid", "DTSTART:20260308T150000Z\r\nDTEND:20260308T160000Z\r\nCLASS:PRIVATE\r\nDESCRIPTION:Private details\r\n", "Secret appointment")
         + event("public", "DTSTART:20260308T170000Z\r\nDTEND:20260308T180000Z\r\n", "Permitted appointment");
      String text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl fixture//EN\r\n" + events + "END:VCALENDAR\r\n";
      var ids = access(text, false).seriesIds();
      var rows = AgendaDecoder.decode(text, URI.create("https://calendar.example.invalid/shared/item.ics"), FROM, THROUGH, ZONE,
         new AgendaDecoder.Access(ids, Set.of("public")));
      assertEquals("Busy", rows.getFirst().title());
      assertEquals("Permitted appointment", rows.get(1).title());
      assertFalse(rows.toString().contains("private-person"));
      assertFalse(rows.toString().contains("Secret appointment"));
   }



   @Test
   void privateOverrideDoesNotInheritPublicSeriesDetails()
   {
      String events = event("mixed", "DTSTART:20260307T150000Z\r\nDTEND:20260307T160000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\n", "Public review")
         + event("mixed", "RECURRENCE-ID:20260308T150000Z\r\nDTSTART:20260308T170000Z\r\nDTEND:20260308T180000Z\r\nCLASS:PRIVATE\r\nDESCRIPTION:Sensitive exception\r\n", "Private meeting");
      String text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl fixture//EN\r\n" + events + "END:VCALENDAR\r\n";
      var access = access(text, true);
      var source = URI.create("https://calendar.example.invalid/shared/item.ics");
      var rows = AgendaDecoder.decode(text, source, FROM, THROUGH, ZONE, access);
      assertEquals("Public review", rows.getFirst().title());
      assertEquals("Busy", rows.get(1).title());
      assertFalse(rows.toString().contains("Sensitive exception"));
      var granted = new AgendaDecoder.Access(access.seriesIds(), access.detailUids(),
         Set.of(new AgendaDecoder.EventKey("mixed", "2026-03-08T15:00:00Z")));
      rows = AgendaDecoder.decode(text, source, FROM, THROUGH, ZONE, granted);
      assertEquals("Private meeting", rows.get(1).title());
      assertEquals("Sensitive exception", rows.get(1).description());
   }



   @Test
   void privateMasterKeepsExceptionsPrivateWithoutExplicitOccurrenceGrant()
   {
      for(String classification : List.of("PRIVATE", "private", "CONFIDENTIAL", "confidential"))
      {
         String events = event("hidden-series", "DTSTART:20260307T150000Z\r\nDTEND:20260307T160000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nCLASS:" + classification + "\r\n", "Sensitive master")
            + event("hidden-series", "RECURRENCE-ID:20260308T150000Z\r\nDTSTART:20260308T170000Z\r\nDTEND:20260308T180000Z\r\nDESCRIPTION:Sensitive exception\r\n", "Hidden override");
         var rows = decode(events, true);
         assertEquals(3, rows.size());
         assertTrue(rows.stream().allMatch(row -> row.title().equals("Busy")), classification);
         assertFalse(rows.toString().contains("Sensitive"));
         assertFalse(rows.toString().contains("Hidden override"));
      }
   }



   private static List<AgendaDecoder.Occurrence> decode(String events, boolean details)
   {
      String text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl fixture//EN\r\n" + events + "END:VCALENDAR\r\n";
      return AgendaDecoder.decode(text, URI.create("https://calendar.example.invalid/shared/item.ics"), FROM, THROUGH, ZONE, access(text, details));
   }



   private static AgendaDecoder.Access access(String text, boolean details)
   {
      Map<String, UUID> ids = new java.util.HashMap<>();
      for(String line : text.split("\r\n"))
      {
         if(line.startsWith("UID:"))
         {
            ids.computeIfAbsent(line.substring(4), ignored -> UUID.randomUUID());
         }
      }
      return new AgendaDecoder.Access(ids, details ? ids.keySet() : Set.of());
   }



   private static String event(String uid, String times, String title)
   {
      return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260929T120000Z\r\n" + times + "SUMMARY:" + title + "\r\nEND:VEVENT\r\n";
   }
}
