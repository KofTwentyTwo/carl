
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CalDavClientTest
{
   private HttpServer server;
   private URI collection;
   private CalDavClient client;
   private int status;
   private String response;
   private final List<String> methods = new ArrayList<>();
   private final List<String> paths = new ArrayList<>();
   private final List<String> ifMatches = new ArrayList<>();
   private final List<String> ifNoneMatches = new ArrayList<>();
   private final List<String> preconditions = new ArrayList<>();
   private final List<String> requestBodies = new ArrayList<>();
   private static final UUID ID = UUID.fromString("6e6a8d50-f95d-4d99-8fe8-7ef446690bb2");
   private static final String EVENT = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Carl AI//EN\r\nBEGIN:VEVENT\r\nUID:6e6a8d50-f95d-4d99-8fe8-7ef446690bb2\r\nDTSTAMP:20260929T120000Z\r\nDTSTART:20261001T120000Z\r\nDTEND:20261001T130000Z\r\nSUMMARY:Review synthetic plan\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

   @BeforeEach
   void start() throws Exception
   {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange ->
      {
         methods.add(exchange.getRequestMethod());
         paths.add(exchange.getRequestURI().getPath());
         ifMatches.add(exchange.getRequestHeaders().getFirst("If-Match"));
         ifNoneMatches.add(exchange.getRequestHeaders().getFirst("If-None-Match"));
         preconditions.add(exchange.getRequestHeaders().getFirst("If-Match") == null
            ? exchange.getRequestHeaders().getFirst("If-None-Match")
            : exchange.getRequestHeaders().getFirst("If-Match"));
         requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         exchange.getResponseHeaders().add("ETag", "\"revision-2\"");
         exchange.getResponseHeaders().add("Location", collection.resolve("../outside").toString());
         byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
         if(status == 204)
         {
            exchange.sendResponseHeaders(status, -1);
         }
         else
         {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
         }
         exchange.close();
      });
      server.start();
      collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/caldav/shared/");
      client = new CalDavClient(collection, "fixture", "synthetic-only".toCharArray(), true);
      status = 200;
      response = "";
   }



   @AfterEach
   void stop()
   {
      if(client != null)
      {
         client.close();
      }
      if(server != null)
      {
         server.stop(0);
      }
   }



   @Test
   void conditionalWritesUseOneStableResourceAndExposeConflicts()
   {
      status = 201;
      var created = client.create(ID, EVENT);
      assertEquals(collection.resolve(ID + ".ics"), created.href());
      assertEquals("\"revision-2\"", created.etag());
      assertEquals("*", ifNoneMatches.getFirst());
      assertNull(ifMatches.getFirst());
      assertEquals("/caldav/shared/" + ID + ".ics", paths.getFirst());
      status = 204;
      client.update(ID, created.etag(), EVENT);
      assertEquals("\"revision-2\"", ifMatches.get(1));
      assertNull(ifNoneMatches.get(1));
      status = 412;
      assertEquals(412, assertThrows(CalDavClient.DavException.class,
         () -> client.update(ID, created.etag(), EVENT)).status());
      assertEquals(List.of("PUT", "PUT", "PUT"), methods);
   }



   @Test
   void discoversComponentsAndReadsBoundedCollectionRecords()
   {
      status = 207;
      response = multistatus("<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>", "/caldav/shared/");
      assertTrue(client.componentTypes().containsAll(List.of("VEVENT", "VTODO")));
      response = multistatus("<d:getetag>\"rev-1\"</d:getetag><c:calendar-data><![CDATA[" + EVENT + "]]></c:calendar-data>", "/caldav/shared/" + ID + ".ics");
      var result = client.query(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-08T00:00:00Z"), "VEVENT");
      assertEquals(1, result.size());
      assertTrue(result.getFirst().calendar().contains("Review synthetic plan"));
      assertEquals(List.of("PROPFIND", "REPORT"), methods);
      assertTrue(requestBodies.get(1).contains("20261001T000000Z"));
   }



   @Test
   void neverFollowsRedirectsOrCrossCollectionReferences()
   {
      status = 302;
      assertEquals(302, assertThrows(CalDavClient.DavException.class, () -> client.read(ID, "VEVENT")).status());
      assertEquals(1, methods.size());
      status = 207;
      response = multistatus("<d:getetag>\"rev\"</d:getetag><c:calendar-data>fake</c:calendar-data>", "https://unrelated.invalid/private");
      assertThrows(CalDavClient.DavException.class, () -> client.query(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"), "VEVENT"));
      assertEquals(2, methods.size());
   }



   @Test
   void rejectsUnsafeXmlAndBoundlessQueries()
   {
      status = 207;
      response = "<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><x>&leak;</x>";
      assertThrows(CalDavClient.DavException.class, () -> client.componentTypes());
      assertThrows(IllegalArgumentException.class, () -> client.query(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), "VEVENT"));
      assertThrows(IllegalArgumentException.class, () -> client.query(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), "VEVENT\"/><evil"));
      assertEquals(1, methods.size());
   }



   @Test
   void rejectsNetworkScopeAndCalendarSideChannelsBeforeSending()
   {
      assertThrows(IllegalArgumentException.class, () -> new CalDavClient(URI.create("http://remote.invalid/shared/"), "a", "b".toCharArray(), true));
      assertThrows(IllegalArgumentException.class, () -> new CalDavClient(collection, "a", "b".toCharArray(), false));
      assertThrows(IllegalArgumentException.class, () -> client.create(ID, EVENT.replace("END:VEVENT", "ATTENDEE:mailto:vendor@example.invalid\r\nEND:VEVENT")));
      assertThrows(IllegalArgumentException.class, () -> client.update(ID, "*", EVENT));
      assertTrue(methods.isEmpty());
   }



   @Test
   void readsAndDeletesOnlyWithVersionAndRejectsOversizedResponses()
   {
      response = EVENT;
      assertEquals(EVENT, client.read(ID, "VEVENT").calendar());
      status = 204;
      client.remove(ID, "\"revision-2\"");
      assertEquals(List.of("GET", "DELETE"), methods);
      status = 200;
      response = "x".repeat(2_000_001);
      assertThrows(CalDavClient.DavException.class, () -> client.read(ID, "VEVENT"));
   }



   @Test
   void deepXmlAndUnauthorizedResponsesNeverBecomeEmptySuccess()
   {
      status = 207;
      response = "<d:multistatus xmlns:d=\"DAV:\">" + "<nested>".repeat(100) + "</nested>".repeat(100) + "</d:multistatus>";
      assertThrows(CalDavClient.DavException.class, () -> client.componentTypes());
      status = 401;
      assertEquals(401, assertThrows(CalDavClient.DavException.class, () -> client.componentTypes()).status());
      assertEquals(2, methods.size());
   }



   @Test
   void rejectsNullComponentsAndOpaqueEndpointsWithoutMakingRequests()
   {
      assertThrows(IllegalArgumentException.class, () -> client.query(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"), null));
      assertThrows(IllegalArgumentException.class, () -> new CalDavClient(URI.create("http:opaque"), "a", "b".toCharArray(), true));
      assertTrue(methods.isEmpty());
   }



   @Test
   void uncertainWritesRequireReconciliationEvenWhenServerReturnsAStatus()
   {
      status = 503;
      assertTrue(assertThrows(CalDavClient.DavException.class, () -> client.create(ID, EVENT)).mayHaveCommitted());
      status = 201;
      assertTrue(assertThrows(CalDavClient.DavException.class, () -> client.update(ID, "\"old\"", EVENT)).mayHaveCommitted());
      status = 502;
      assertTrue(assertThrows(CalDavClient.DavException.class, () -> client.remove(ID, "\"old\"")).mayHaveCommitted());
      assertEquals(3, methods.size());
   }



   @Test
   void managedReadRejectsDifferentIdentityTypeAndUnsafeContents()
   {
      response = EVENT.replace(ID.toString(), UUID.randomUUID().toString());
      assertThrows(CalDavClient.DavException.class, () -> client.read(ID, "VEVENT"));
      response = EVENT;
      assertThrows(CalDavClient.DavException.class, () -> client.read(ID, "VTODO"));
      response = EVENT.replace("END:VEVENT", "ATTENDEE:mailto:outside@example.invalid\r\nEND:VEVENT");
      assertThrows(CalDavClient.DavException.class, () -> client.read(ID, "VEVENT"));
      assertEquals(3, methods.size());
   }



   @Test
   void excessiveComponentStructuresAreRejectedBeforeParsingOrSending()
   {
      String nested = EVENT.replace("END:VEVENT", "BEGIN:X-NEST\r\n".repeat(80) + "END:X-NEST\r\n".repeat(80) + "END:VEVENT");
      assertThrows(IllegalArgumentException.class, () -> client.create(ID, nested));
      String repeated = EVENT.replace("END:VCALENDAR", "BEGIN:VTIMEZONE\r\nTZID:X\r\nEND:VTIMEZONE\r\n".repeat(100) + "END:VCALENDAR");
      assertThrows(IllegalArgumentException.class, () -> client.create(ID, repeated));
      assertTrue(methods.isEmpty());
   }



   private static String multistatus(String properties, String href)
   {
      return "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>" + href
         + "</d:href><d:propstat><d:prop>" + properties + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
   }
}
