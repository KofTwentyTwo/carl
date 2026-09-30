
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CalendarPublicationServiceTest
{
   static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
   static PGSimpleDataSource source;
   HttpServer server;
   URI collection;
   String remote;
   String etag;
   boolean failAfterCommit;
   boolean denied;
   int forcedWriteStatus;
   java.util.concurrent.CountDownLatch entered;
   java.util.concurrent.CountDownLatch release;
   int version = 1;
   final AtomicInteger writes = new AtomicInteger();
   final AtomicInteger reads = new AtomicInteger();
   UUID step;

   @BeforeAll
   static void database() throws Exception
   {
      DB.start();
      source = new PGSimpleDataSource();
      source.setURL(DB.getJdbcUrl());
      source.setUser(DB.getUsername());
      source.setPassword(DB.getPassword());
      try(var c = source.getConnection(); var s = c.createStatement())
      {
         s.execute("CREATE TABLE fixture_calendar_grant(step uuid, member_id bigint, allowed boolean, PRIMARY KEY(step,member_id))");
         s.execute(new String(CalendarPublicationServiceTest.class.getResourceAsStream("/db/migration/V20__calendar_publication_outbox.sql").readAllBytes(), StandardCharsets.UTF_8));
      }
   }



   @AfterAll
   static void stopDatabase()
   {
      DB.stop();
   }



   @BeforeEach
   void start() throws Exception
   {
      step = UUID.randomUUID();
      try(var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO fixture_calendar_grant VALUES(?,1,true),(?,2,true)"))
      {
         s.setObject(1, step);
         s.setObject(2, step);
         s.execute();
      }
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/calendar/");
      server.createContext("/calendar/", x ->
      {
         int status;
         String body = "";
         switch(x.getRequestMethod())
         {
            case "PROPFIND" ->
            {
               status = 207;
               body = "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>/calendar/</d:href><d:propstat><d:prop><c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
            }
            case "GET" ->
            {
               reads.incrementAndGet();
               if(entered != null)
               {
                  entered.countDown();
                  try
                  {
                     release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                  }
                  catch(InterruptedException interrupted)
                  {
                     Thread.currentThread().interrupt();
                  }
               }
               status = remote == null ? 404 : 200;
               body = remote == null ? "" : remote;
            }
            case "PUT" ->
            {
               writes.incrementAndGet();
               if(forcedWriteStatus != 0)
               {
                  status = forcedWriteStatus;
               }
               else if((remote != null && "*".equals(x.getRequestHeaders().getFirst("If-None-Match"))) || (x.getRequestHeaders().getFirst("If-Match") != null && !x.getRequestHeaders().getFirst("If-Match").equals(etag)))
               {
                  status = 412;
               }
               else
               {
                  remote = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                  etag = "\"v" + writes.get() + "\"";
                  status = failAfterCommit ? 503 : x.getRequestHeaders().containsKey("If-Match") ? 204 : 201;
                  failAfterCommit = false;
               }
            }
            case "DELETE" ->
            {
               writes.incrementAndGet();
               if(!java.util.Objects.equals(etag, x.getRequestHeaders().getFirst("If-Match")))
               {
                  status = 412;
               }
               else
               {
                  remote = null;
                  status = 204;
               }
            }
            default -> status = 405;
         }
         if(etag != null)
         {
            x.getResponseHeaders().add("ETag", etag);
         }
         byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
         x.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
         if(status != 204)
         {
            x.getResponseBody().write(bytes);
         }
         x.close();
      });
      server.start();
   }



   @AfterEach
   void stop()
   {
      server.stop(0);
   }



   CalendarPublicationService service()
   {
      return new CalendarPublicationService(source, collection, "VTODO", Set.of(1L, 2L), "alice", "fixture", new char[]{'x'}, true,
         (c, principal, plan, id, expected, audience, action) ->
         {
            if(denied || !principal.equals("alice") || plan != 7 || !id.equals(step) || !audience.equals(Set.of(1L, 2L)))
            {
               throw new SecurityException("Unavailable");
            }
            try(var s = c.prepareStatement("SELECT allowed FROM fixture_calendar_grant WHERE step=? ORDER BY member_id FOR SHARE"))
            {
               s.setObject(1, id);
               try(var r = s.executeQuery())
               {
                  int count = 0;
                  while(r.next())
                  {
                     count++;
                     if(!r.getBoolean(1))
                     {
                        throw new SecurityException("Shared audience denied");
                     }
                  }
                  if(count != 2)
                  {
                     throw new SecurityException("Audience not configured");
                  }
               }
            }
            if(expected != version)
            {
               throw new IllegalArgumentException("Version changed");
            }
            return new CalendarPublicationService.SharedItem(new PlanCalendarCodec.Item(id, version, "Shared step " + version, "Shared permitted description", "Home", Instant.parse("2026-09-15T12:00:00Z")), LocalDate.of(2026, 10, 1), null, "epoch1");
         });
   }



   @Test
   void durableIdempotenceAndRestartReconcilesCommitted503() throws Exception
   {
      UUID request = UUID.randomUUID();
      failAfterCommit = true;
      try(var service = service())
      {
         assertEquals("UNKNOWN", service.publish(request, 7, step, 1).status());
      }
      assertEquals(1, writes.get());
      try(var service = service())
      {
         assertEquals("COMPLETE", service.publish(request, 7, step, 1).status());
         assertEquals("COMPLETE", service.publish(request, 7, step, 1).status());
      }
      assertEquals(1, writes.get());
      assertTrue(reads.get() > 0);
   }



   @Test
   void humanEditConflictsAndMissingPublishedItemNeverResurrects() throws Exception
   {
      try(var service = service())
      {
         service.publish(UUID.randomUUID(), 7, step, 1);
         remote = remote.replace("Shared step 1", "Human edit");
         etag = "\"human\"";
         version = 2;
         assertEquals("CONFLICT", service.publish(UUID.randomUUID(), 7, step, 2).status());
         assertEquals(1, writes.get());
         remote = null;
         assertEquals("CONFLICT", service.publish(UUID.randomUUID(), 7, step, 2).status());
         assertEquals(1, writes.get());
      }
   }



   @Test
   void authorityAndVersionRecheckedEvenForIdempotentResults() throws Exception
   {
      UUID request = UUID.randomUUID();
      try(var service = service())
      {
         service.publish(request, 7, step, 1);
         denied = true;
         assertThrows(SecurityException.class, () -> service.publish(request, 7, step, 1));
         denied = false;
         version = 2;
         assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), 7, step, 1));
         assertThrows(IllegalArgumentException.class, () -> service.publish(request, 7, step, 2));
      }
      assertEquals(1, writes.get());
   }



   @Test
   void conditionalUpdateRetireAndTombstone() throws Exception
   {
      try(var service = service())
      {
         service.publish(UUID.randomUUID(), 7, step, 1);
         version = 2;
         assertEquals("COMPLETE", service.publish(UUID.randomUUID(), 7, step, 2).status());
         assertTrue(remote.contains("Shared step 2"));
         assertEquals("COMPLETE", service.retire(UUID.randomUUID(), 7, step, 2).status());
         assertNull(remote);
         assertThrows(IllegalArgumentException.class, () -> service.publish(UUID.randomUUID(), 7, step, 2));
      }
      assertEquals(3, writes.get());
   }



   @Test
   void restartPendingIntentCanReconcileAfterPlanRevisionWithoutWriting() throws Exception
   {
      UUID request = UUID.randomUUID();
      failAfterCommit = true;
      try(var service = service())
      {
         assertEquals("UNKNOWN", service.publish(request, 7, step, 1).status());
      }
      try(var c = source.getConnection(); var s = c.prepareStatement("UPDATE carl_calendar_operation SET status='PENDING' WHERE request_id=?"))
      {
         s.setObject(1, request);
         s.execute();
      }
      version = 2;
      try(var service = service())
      {
         assertEquals("COMPLETE", service.reconcile(request, 7, step, 2).status());
      }
      assertEquals(1, writes.get());
   }



   @Test
   void currentDatabaseAudienceGrantDeniesEvenPreviouslySuccessfulRequest() throws Exception
   {
      UUID request = UUID.randomUUID();
      try(var service = service())
      {
         service.publish(request, 7, step, 1);
         try(var c = source.getConnection(); var s = c.prepareStatement("UPDATE fixture_calendar_grant SET allowed=false WHERE step=? AND member_id=2"))
         {
            s.setObject(1, step);
            s.execute();
         }
         assertThrows(SecurityException.class, () -> service.publish(request, 7, step, 1));
         assertThrows(SecurityException.class, () -> service.reconcile(request, 7, step, 1));
      }
      assertEquals(1, writes.get());
   }



   @Test
   void provider409And412AreTerminalConflictsWithoutRetry() throws Exception
   {
      try(var service = service())
      {
         for(int status : new int[]{409, 412})
         {
            forcedWriteStatus = status;
            UUID request = UUID.randomUUID();
            assertEquals("CONFLICT", service.publish(request, 7, step, 1).status());
            assertEquals("CONFLICT", service.publish(request, 7, step, 1).status());
         }
      }
      assertEquals(2, writes.get());
      assertNull(remote);
   }



   @Test
   void databaseLockSerializesSeparateServiceInstances() throws Exception
   {
      entered = new java.util.concurrent.CountDownLatch(1);
      release = new java.util.concurrent.CountDownLatch(1);
      try(var first = service(); var second = service(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor())
      {
         var future = executor.submit(() -> first.publish(UUID.randomUUID(), 7, step, 1));
         assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
         assertThrows(IllegalStateException.class, () -> second.publish(UUID.randomUUID(), 7, step, 1));
         release.countDown();
         assertEquals("COMPLETE", future.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
      }
      assertEquals(1, writes.get());
   }



   @Test
   void synchronizationObservesHouseholdCompletionWithoutFinancialMutation() throws Exception
   {
      try(var service = service())
      {
         service.publish(UUID.randomUUID(), 7, step, 1);
         assertEquals("UNCHANGED", service.synchronize(7, step, 1).state());
         remote = remote.replace("STATUS:NEEDS-ACTION", "STATUS:COMPLETED").replace("PERCENT-COMPLETE:0", "PERCENT-COMPLETE:100");
         etag = "\"household\"";
         var observed = service.synchronize(7, step, 1);
         assertEquals("CHANGED", observed.state());
         assertTrue(observed.calendar().contains("STATUS:COMPLETED"));
         assertTrue(observed.diagnostic().contains("unverified"));
         remote = null;
         assertEquals("MISSING", service.synchronize(7, step, 1).state());
         denied = true;
         assertThrows(SecurityException.class, () -> service.synchronize(7, step, 1));
      }
      assertEquals(1, writes.get());
   }



   @Test
   void authorityLocksRemainHeldThroughProviderMutation() throws Exception
   {
      entered = new java.util.concurrent.CountDownLatch(1);
      release = new java.util.concurrent.CountDownLatch(1);
      try(var service = service(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2))
      {
         var publication = executor.submit(() -> service.publish(UUID.randomUUID(), 7, step, 1));
         assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
         var revoke = executor.submit(() ->
         {
            try(var c = source.getConnection(); var s = c.prepareStatement("UPDATE fixture_calendar_grant SET allowed=false WHERE step=? AND member_id=2"))
            {
               s.setObject(1, step);
               return s.executeUpdate();
            }
         });
         assertThrows(java.util.concurrent.TimeoutException.class, () -> revoke.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
         release.countDown();
         assertEquals("COMPLETE", publication.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
         assertEquals(1, revoke.get(5, java.util.concurrent.TimeUnit.SECONDS));
         assertThrows(SecurityException.class, () -> service.synchronize(7, step, 1));
      }
      assertEquals(1, writes.get());
   }

}
