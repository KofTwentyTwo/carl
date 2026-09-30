/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.calendar;


import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReminderStatusTest
{
   private static final UUID ID = UUID.fromString("dbe35761-dfd3-4ae9-abfe-c4d3c5843fd5");
   private static String task(Instant completed)
   {
      return PlanCalendarCodec.reminder(new PlanCalendarCodec.Item(ID, 1, "Review statement", "No financial action", "Home", Instant.parse("2026-09-30T12:00:00Z")), LocalDate.of(2026, 10, 1), completed);
   }



   @Test
   void remoteCompletionRemainsUnverifiedAndOpenStatesStayOpen()
   {
      var completed = ReminderStatus.decode(ID, task(Instant.parse("2026-09-30T12:00:00Z")));
      assertEquals("REMOTE_REPORTED_COMPLETE", completed.state());
      assertEquals(Instant.parse("2026-09-30T12:00:00Z"), completed.reportedCompleted());
      assertTrue(completed.limitation().contains("unverified"));
      String open = task(null);
      assertEquals("NEEDS_ACTION", ReminderStatus.decode(ID, open).state());
      assertEquals("IN_PROCESS", ReminderStatus.decode(ID, open.replace("NEEDS-ACTION", "IN-PROCESS")).state());
      assertEquals("CANCELLED", ReminderStatus.decode(ID, open.replace("NEEDS-ACTION", "CANCELLED")).state());
   }



   @Test
   void malformedOrInstructionBearingCalendarPropertiesCannotBecomeAuthority()
   {
      String open = task(null);
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(UUID.randomUUID(), open));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("END:VTODO", "URL:https://example.invalid\r\nEND:VTODO")));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("END:VTODO", "STATUS:COMPLETED\r\nEND:VTODO")));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("NEEDS-ACTION", "UNKNOWN")));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.repeat(300)));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(null, open));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, "bad"));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("END:VTODO", "BEGIN:VTODO\r\nEND:VTODO\r\nEND:VTODO")));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("END:VTODO", "END:VEVENT")));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("END:VCALENDAR", "")));
      assertThrows(IllegalArgumentException.class, () -> ReminderStatus.decode(ID, open.replace("END:VTODO", "COMPLETED:invalid\r\nEND:VTODO")));
   }
}
