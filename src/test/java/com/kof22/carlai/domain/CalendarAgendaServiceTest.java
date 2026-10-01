/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.calendar.CalDavClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CalendarAgendaServiceTest
{
   static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   static CarlService service;
   static final LocalDate FROM = LocalDate.of(2026, 11, 1);

   @BeforeAll
   static void start() throws Exception
   {
      DATABASE.start();
      var data = NativeDatabases.source(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
      AgentMigrations.migrate(data);
      service = new CarlService(data, Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic calendar family','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',false)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE']) d");
         return null;
      });
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void clear()
   {
      service.transaction(c ->
      {
         CarlService.execute(c, "TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
         CarlService.execute(c, "UPDATE carl_permission SET details=true");
         return null;
      });
   }



   @Test
   void actualReadOnlyHttpSyncPersistsScopedRecurrencePrivateBusyAndAtomicMissingWindow() throws Exception
   {
      try(var provider = new Fixture())
      {
         var agenda = provider.agenda(Set.of(1L, 2L));
         UUID request = UUID.randomUUID();
         var result = agenda.synchronize("alice", request, FROM, FROM.plusDays(1));
         assertEquals("CURRENT", result.get("sync_state"));
         assertEquals("2026-11-01", result.get("coverage_from").toString());
         var bob = service.view(CarlService.Scope.privateFor("bob"), "calendar");
         assertEquals(3, bob.size());
         assertTrue(bob.stream().anyMatch(row -> "Public family event".equals(row.get("title"))));
         var busy = bob.stream().filter(row -> "Busy".equals(row.get("title"))).findFirst().orElseThrow();
         assertNull(busy.get("source_zone"));
         assertFalse(CarlService.json(bob).contains("Sensitive title"));
         assertFalse(CarlService.json(bob).contains("private-provider-id"));
         assertEquals(1, bob.stream().filter(row -> row.get("all_day_start") != null).count());
         int reads = provider.reads.get();
         assertEquals(result, agenda.synchronize("alice", request, FROM, FROM.plusDays(1)));
         assertEquals(reads, provider.reads.get());
         long report = service.generateReport(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), FROM, FROM.plusDays(1), null);
         assertFalse(service.artifact("alice", report).get("facts").toString().contains("Sensitive title"));
         assertEquals(3, new com.fasterxml.jackson.databind.ObjectMapper().readTree(service.artifact("alice", report).get("facts").toString()).path("calendarOverlaps").path("pairs").size());
         provider.calendar.set("");
         agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM.plusDays(1));
         assertTrue(service.view(CarlService.Scope.privateFor("bob"), "calendar").isEmpty());
         assertEquals(true, service.artifact("alice", report).get("stale"));
         assertEquals(0, provider.writes.get());
      }
   }



   @Test
   void expiredReadRetainsRecordsLastSuccessAndTruthfulCoverage() throws Exception
   {
      try(var provider = new Fixture())
      {
         var agenda = provider.agenda(Set.of(1L, 2L));
         var first = agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM.plusDays(1));
         provider.status.set(401);
         var failed = agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM.plusDays(1));
         assertEquals("EXPIRED", failed.get("sync_state"));
         assertEquals(first.get("last_success"), failed.get("last_success"));
         assertEquals(3, service.view(CarlService.Scope.privateFor("alice"), "calendar").size());
         long report = service.generateReport(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), FROM, FROM.plusDays(3), null);
         var artifact = service.artifact("alice", report);
         assertTrue(artifact.get("status_label").toString().startsWith("Incomplete"));
         assertTrue(artifact.get("facts").toString().contains("entire requested interval"));
         assertTrue(artifact.get("facts").toString().contains("last success"));
      }
   }



   @Test
   void changedAudienceRevocationAndMalformedProviderDataCannotWidenProjection() throws Exception
   {
      try(var provider = new Fixture())
      {
         var agenda = provider.agenda(Set.of(1L, 2L));
         assertThrows(SecurityException.class, () -> agenda.synchronize("bob", UUID.randomUUID(), FROM, FROM));
         assertEquals(0, provider.reads.get());
         agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM.plusDays(1));
         assertThrows(SecurityException.class, () -> provider.agenda(Set.of(1L)).synchronize("alice", UUID.randomUUID(), FROM, FROM));
         provider.calendar.set("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nINVALID\r\nEND:VCALENDAR\r\n");
         var failure = agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM.plusDays(1));
         assertEquals("FAILED", failure.get("sync_state"));
         assertEquals(3, service.view(CarlService.Scope.privateFor("alice"), "calendar").size());
         service.transaction(c ->
         {
            CarlService.execute(c, "UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='CALENDAR'");
            return null;
         });
         int reads = provider.reads.get();
         assertThrows(SecurityException.class, () -> agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM));
         assertEquals(reads, provider.reads.get());
      }
   }



   @Test
   void revocationDuringProviderReadPreventsPublishingFetchedDetails() throws Exception
   {
      try(var provider = new Fixture())
      {
         provider.onQuery.set(() -> service.transaction(c ->
         {
            CarlService.execute(c, "UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='CALENDAR'");
            return null;
         }));
         assertThrows(SecurityException.class, () -> provider.agenda(Set.of(1L, 2L)).synchronize("alice", UUID.randomUUID(), FROM, FROM.plusDays(1)));
         assertTrue(service.view(CarlService.Scope.privateFor("alice"), "calendar").isEmpty());
      }
   }

   static final class Fixture implements AutoCloseable
   {
      final HttpServer server;
      final URI collection;
      final AtomicInteger status = new AtomicInteger(207);
      final AtomicInteger reads = new AtomicInteger();
      final AtomicInteger writes = new AtomicInteger();
      final AtomicReference<Runnable> onQuery = new AtomicReference<>(() ->
      {
      });
      final AtomicReference<String> calendar = new AtomicReference<>("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl synthetic//EN\r\nBEGIN:VEVENT\r\nUID:public-provider-id\r\nDTSTAMP:20261001T000000Z\r\nDTSTART:20261101T150000Z\r\nDTEND:20261101T160000Z\r\nRRULE:FREQ=DAILY;COUNT=2\r\nEXDATE:20261102T150000Z\r\nSUMMARY:Public family event\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:private-provider-id\r\nDTSTAMP:20261001T000000Z\r\nDTSTART:20261101T153000Z\r\nDTEND:20261101T163000Z\r\nCLASS:PRIVATE\r\nSUMMARY:Sensitive title\r\nDESCRIPTION:Sensitive details\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:all-day-provider-id\r\nDTSTAMP:20261001T000000Z\r\nDTSTART;VALUE=DATE:20261101\r\nDTEND;VALUE=DATE:20261102\r\nSUMMARY:Family day\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");

      Fixture() throws Exception
      {
         server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
         collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/calendar/");
         server.createContext("/calendar/", exchange ->
         {
            reads.incrementAndGet();
            String body;
            if(exchange.getRequestMethod().equals("PROPFIND"))
            {
               body = "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>/calendar/</d:href><d:propstat><d:prop><c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
            }
            else if(exchange.getRequestMethod().equals("REPORT"))
            {
               onQuery.get().run();
               String resource = calendar.get().isEmpty() ? "" : "<d:response><d:href>/calendar/shared.ics</d:href><d:propstat><d:prop><d:getetag>\"synthetic-v1\"</d:getetag><c:calendar-data><![CDATA[" + calendar.get() + "]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
               body = "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" + resource + "</d:multistatus>";
            }
            else
            {
               writes.incrementAndGet();
               body = "Unexpected method";
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try(var output = exchange.getResponseBody())
            {
               output.write(bytes);
            }
         });
         server.start();
      }



      CalendarAgendaService agenda(Set<Long> audience)
      {
         return new CalendarAgendaService(service, "events", "alice", BillCsv.hash(collection.toString()), audience, () ->
         {
            var client = new CalDavClient(collection, "synthetic", "synthetic-password".toCharArray(), true);
            return new CalendarAgendaService.Provider()
            {
               @Override
               public Set<String> components()
               {
                  return client.componentTypes();
               }



               @Override
               public List<CalDavClient.Resource> query(Instant from, Instant through)
               {
                  return client.query(from, through, "VEVENT");
               }



               @Override
               public void close()
               {
                  client.close();
               }
            };
         });
      }



      @Override
      public void close()
      {
         server.stop(0);
      }
   }
}
