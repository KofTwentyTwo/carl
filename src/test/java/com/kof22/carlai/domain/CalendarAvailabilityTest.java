/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CalendarAvailabilityTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private long connection;
   private static Instant time(String value)
   {
      return Instant.parse(value);
   }



   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.systemUTC());
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic appointment windows','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',false)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'CALENDAR',true),(2,'CALENDAR',true)");
         return null;
      });
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void setup()
   {
      connection = service.transaction(c ->
      {
         CarlService.execute(c, "TRUNCATE carl_record RESTART IDENTITY CASCADE");
         CarlService.execute(c, "UPDATE carl_member SET active=true");
         CarlService.execute(c, "UPDATE carl_permission SET details=true");
         long id = CarlService.record(c, CarlService.member(c, "alice"), "CALENDAR", "FAMILY", "Synthetic calendar", "Synthetic source");
         CarlService.execute(c, "INSERT INTO carl_calendar_connection(record_id,provider,calendar_identity,sync_state,last_success,coverage_from,coverage_through) VALUES(?,'CALDAV','synthetic','CURRENT','2026-11-01T00:00:00Z','2026-11-01','2026-11-03')", id);
         return id;
      });
   }



   private long event(String visibility, String start, String end, boolean cancelled, boolean transparent)
   {
      return service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "CALENDAR", visibility, "Sensitive private title", "Sensitive source detail");
         CarlService.execute(c, "INSERT INTO carl_calendar_event(record_id,connection_id,provider_event_id,occurrence_id,start_at,end_at,source_zone,cancelled,transparent,observed_at) VALUES(?,?,?,'one',?,?,'America/Chicago',?,?,'2026-11-01T00:00:00Z')", id, connection, UUID.randomUUID().toString(), java.sql.Timestamp.from(time(start)), java.sql.Timestamp.from(time(end)), cancelled, transparent);
         return id;
      });
   }



   @SuppressWarnings("unchecked")
   private static List<CalendarAvailability.Window> windows(java.util.Map<String, Object> result)
   {
      return (List<CalendarAvailability.Window>) result.get("windows");
   }



   @Test
   void exactInstantsAcrossFallBackMergeBusyOverlapAndIgnoreCancelledOrFree()
   {
      event("FAMILY", "2026-11-01T06:00:00Z", "2026-11-01T07:00:00Z", false, false);
      event("FAMILY", "2026-11-01T06:30:00Z", "2026-11-01T07:30:00Z", false, false);
      event("FAMILY", "2026-11-01T05:00:00Z", "2026-11-01T09:00:00Z", true, false);
      event("FAMILY", "2026-11-01T05:00:00Z", "2026-11-01T09:00:00Z", false, true);
      var result = new CalendarAvailability(service).suggest(CarlService.Scope.privateFor("alice"), time("2026-11-01T05:00:00Z"), time("2026-11-01T09:00:00Z"), 60);
      assertEquals(List.of(new CalendarAvailability.Window(time("2026-11-01T05:00:00Z"), time("2026-11-01T06:00:00Z")), new CalendarAvailability.Window(time("2026-11-01T07:30:00Z"), time("2026-11-01T09:00:00Z"))), windows(result));
      assertEquals("SUGGESTIONS_FROM_SCOPED_SNAPSHOT", result.get("status"));
      assertFalse(result.toString().contains("Sensitive"));
      assertTrue(result.get("limitation").toString().contains("reserve nothing"));
   }



   @Test
   void privateAndSharedProjectionsNeverExposeAnotherMembersBusyRecordOrDetails()
   {
      long hidden = event("PRIVATE", "2026-11-01T05:00:00Z", "2026-11-01T09:00:00Z", false, false);
      var read = new CalendarAvailability(service);
      var from = time("2026-11-01T05:00:00Z");
      var through = time("2026-11-01T09:00:00Z");
      assertTrue(windows(read.suggest(CarlService.Scope.privateFor("alice"), from, through, 60)).isEmpty());
      var shared = read.suggest(new CarlService.Scope("alice", Set.of("alice", "bob")), from, through, 60);
      assertEquals(1, windows(shared).size());
      assertFalse(((List<?>) shared.get("sourceRecords")).contains(hidden));
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,2,false)", hidden);
         return null;
      });
      var busy = read.suggest(CarlService.Scope.privateFor("bob"), from, through, 60);
      assertTrue(windows(busy).isEmpty());
      assertFalse(busy.toString().contains("Sensitive"));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_member SET active=false WHERE id=2");
         return null;
      });
      assertThrows(SecurityException.class, () -> read.suggest(CarlService.Scope.privateFor("bob"), from, through, 60));
   }



   @Test
   void allDayMissingWindowAndExpiredCoverageRemainTruthfullyPartial()
   {
      service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "CALENDAR", "FAMILY", "Private all-day", "Do not disclose");
         CarlService.execute(c, "INSERT INTO carl_calendar_event(record_id,connection_id,provider_event_id,occurrence_id,all_day_start,all_day_end_exclusive,source_zone) VALUES(?,?,'all-day','one','2026-11-02','2026-11-03','America/Chicago')", id, connection);
         return null;
      });
      var read = new CalendarAvailability(service);
      var blocked = read.suggest(CarlService.Scope.privateFor("alice"), time("2026-11-02T06:00:00Z"), time("2026-11-03T06:00:00Z"), 30);
      assertTrue(windows(blocked).isEmpty());
      assertEquals("PARTIAL_SUGGESTIONS", blocked.get("status"));
      var outside = read.suggest(CarlService.Scope.privateFor("alice"), time("2026-11-04T06:00:00Z"), time("2026-11-04T09:00:00Z"), 30);
      assertEquals("PARTIAL_SUGGESTIONS", outside.get("status"));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_calendar_connection SET sync_state='EXPIRED' WHERE record_id=?", connection);
         return null;
      });
      assertEquals("PARTIAL_SUGGESTIONS", read.suggest(CarlService.Scope.privateFor("alice"), time("2026-11-01T06:00:00Z"), time("2026-11-01T09:00:00Z"), 30).get("status"));
      assertThrows(IllegalArgumentException.class, () -> read.suggest(CarlService.Scope.privateFor("alice"), time("2026-11-01T00:00:00Z"), time("2026-11-10T00:00:00Z"), 30));
      assertThrows(IllegalArgumentException.class, () -> read.suggest(CarlService.Scope.privateFor("alice"), time("2026-11-01T00:00:00Z"), time("2026-11-01T01:00:00Z"), 0));
   }
}
