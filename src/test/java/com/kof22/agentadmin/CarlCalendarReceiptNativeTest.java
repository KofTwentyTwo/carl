/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.CalendarReceiptMetadataFixture;
import com.kof22.carlai.CalendarReceiptTestControl;
import com.kof22.carlai.calendar.CalDavClient;
import com.kof22.carlai.domain.CalendarAgendaService;
import com.kof22.carlai.domain.CalendarWorkflows;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Actual native routes and PostgreSQL over controlled read-only provider data, never live acceptance. */
class CarlCalendarReceiptNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
   private static final String RECEIPT = "Calendar refresh request recorded. Review Calendar Connections for the current refresh status and coverage. Private event details remain excluded; synchronization does not book or change an appointment.";
   private static final String PROCESS = "carlSyncAgenda";
   private static final AtomicReference<String> CALENDAR = new AtomicReference<>();
   private static final AtomicReference<Runnable> QUERY = new AtomicReference<>();
   private static final AtomicInteger READS = new AtomicInteger();
   private static final CalendarReceiptTestControl CONTROL = new CalendarReceiptTestControl();
   private static javax.sql.DataSource data;
   private static CarlService service;
   private static CalendarAgendaService agenda;
   private static AdminServer server;
   private static HttpClient http;
   private static com.sun.net.httpserver.HttpServer keyServer;
   private static String base;
   private static String alice;
   private static String bob;
   private static String outsider;
   private static final Map<String, UUID> REQUESTS = new java.util.HashMap<>();

   @BeforeAll
   static void start() throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      DATABASE.start();
      data = NativeDatabases.source(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(data);
      service = new CarlService(CONTROL.wrap(data), Clock.systemUTC());
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled calendar household','America/Chicago'),(2,'Other controlled household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Controlled owner',true),(2,1,'bob','Controlled family',false),(3,2,'outsider','Other caller',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['CALENDAR','FINANCE','VENDORS','BILLS']) d");
      sql("CREATE ROLE carl_calendar_reader LOGIN PASSWORD 'synthetic-reader'");
      sql("GRANT USAGE ON SCHEMA public TO carl_calendar_reader");
      for(var table : AdminApplication.READER_COLUMNS.entrySet())
      {
         if(!table.getValue().isEmpty())
         {
            sql("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_calendar_reader");
         }
      }
      sql("GRANT SELECT ON carl_artifact_view,carl_calendar_view,carl_calendar_connection_view TO carl_calendar_reader");
      agenda = new CalendarAgendaService(service, "events", "alice", "a".repeat(64), Set.of(1L, 2L), () -> new CalendarAgendaService.Provider()
      {
         @Override
         public Set<String> components()
         {
            return Set.of("VEVENT");
         }



         @Override
         public List<CalDavClient.Resource> query(Instant from, Instant through)
         {
            READS.incrementAndGet();
            Runnable hook = QUERY.getAndSet(null);
            if(hook != null)
            {
               hook.run();
            }
            return List.of(new CalDavClient.Resource(URI.create("https://calendar.synthetic/supplied.ics"), "\"controlled\"", CALENDAR.get()));
         }



         @Override
         public void close()
         {
         }
      });
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      var publicKey = (RSAPublicKey) pair.getPublic();
      var key = Jwk.fromValues(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())));
      keyServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      String issuer = "http://127.0.0.1:" + keyServer.getAddress().getPort() + "/";
      byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())))));
      keyServer.createContext("/.well-known/jwks.json", exchange ->
      {
         exchange.getResponseHeaders().set("Content-Type", "application/json");
         exchange.sendResponseHeaders(200, jwks.length);
         try(var output = exchange.getResponseBody())
         {
            output.write(jwks);
         }
      });
      keyServer.start();
      var identity = new BearerIdentity(issuer, "carl-admin", "controlled-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR, "outsider", Role.OPERATOR)), ignored -> key);
      var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + DATABASE.getJdbcUrl(), "--kof22.agent.db.username=" + DATABASE.getUsername(), "--kof22.agent.db.password=" + DATABASE.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
      var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_calendar_reader", "synthetic-reader");
      var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), DATABASE.getUsername(), DATABASE.getPassword());
      var workflows = new CalendarWorkflows(Map.of(), Map.of("events", agenda), service);
      var app = new AdminApplication(reader, List.of(new CalendarReceiptMetadataFixture(service, workflows)), identity, runtime);
      server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), config -> config.routes.beforeMatched(context ->
      {
         Runnable hook = CONTROL.beforeRequest.getAndSet(null);
         if(hook != null)
         {
            hook.run();
         }
      }));
      server.start();
      base = "http://127.0.0.1:" + server.port();
      http = HttpClient.newHttpClient();
      alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(600)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
      bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(600)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
      outsider = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("outsider").withExpiresAt(Instant.now().plusSeconds(600)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
   }



   @AfterAll
   static void stop()
   {
      if(http != null)
      {
         http.close();
      }
      if(server != null)
      {
         server.close();
      }
      if(keyServer != null)
      {
         keyServer.stop(0);
      }
      DATABASE.stop();
   }



   @BeforeEach
   void reset()
   {
      CONTROL.beforeRequest.set(null);
      CONTROL.beforeCommit.set(null);
      CONTROL.afterCommit.set(null);
      CONTROL.failCommit.set(false);
      QUERY.set(null);
      REQUESTS.clear();
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_member SET active=true,can_manage=(principal<>'bob')");
      sql("UPDATE carl_permission SET details=true");
      sql("UPDATE carl_household SET display_zone='America/Chicago'");
      CALENDAR.set(calendar("PUBLIC", "Controlled public event"));
      agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM);
      CALENDAR.set(calendar("PRIVATE", "Confidential provider title"));
      READS.set(0);
   }



   @Test
   void sameUidPrivateTransitionReturnsOnlyCommittedFixedReceipt() throws Exception
   {
      String process = begin(true);
      long epoch = service.member("alice").permissionRevision();
      var response = submit(process, true, false);
      assertEquals(1, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertTrue(service.member("alice").permissionRevision() > epoch);
      assertReceipt(response);
      var visible = service.view(CarlService.Scope.privateFor("bob"), "calendar");
      assertTrue(visible.toString().contains("Busy"));
      assertFalse(visible.toString().contains("Confidential"));
      assertExpired(process);
      int reads = READS.get();
      assertEquals("COMPLETE", agenda.synchronize("alice", REQUESTS.get(process), FROM, FROM).get("requestStatus"));
      assertEquals(reads, READS.get());
   }



   @Test
   void unchangedPublicRefreshAndLegacyPrivacyTransitionReturnFixedReceipts() throws Exception
   {
      CALENDAR.set(calendar("PUBLIC", "Current controlled public event"));
      assertReceipt(submit(begin(true), true, false));
      assertEquals(0, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      CALENDAR.set(calendar("PRIVATE", "Confidential legacy title"));
      String legacy = begin(false);
      assertReceipt(submit(legacy, false, false));
      assertEquals(1, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertExpired(legacy);
   }



   @Test
   void failedReadAcknowledgesOnlyRecordedPartialOutcomeAndRetainsProjection() throws Exception
   {
      QUERY.set(() ->
      {
         throw new CalDavClient.DavException(401);
      });
      String process = begin(true);
      assertReceipt(submit(process, true, false));
      assertEquals(0, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertEquals(1, count("SELECT count(*) FROM carl_request WHERE id='" + REQUESTS.get(process) + "' AND status='PARTIAL'"));
      assertEquals(1, count("SELECT count(*) FROM carl_calendar_connection WHERE sync_state='EXPIRED' AND failure_code='CREDENTIALS_EXPIRED'"));
      assertExpired(process);
   }



   @Test
   void failedCommitNeverAcknowledgesOrPersistsPrivateTransition() throws Exception
   {
      String process = begin(true);
      CONTROL.failCommit.set(true);
      var response = submit(process, true, false);
      assertDenied(response);
      assertFalse(response.body().contains(RECEIPT), response.body());
      assertEquals(0, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertEquals(1, count("SELECT count(*) FROM carl_request WHERE id='" + REQUESTS.get(process) + "' AND status='PENDING'"));
   }



   @Test
   void revocationDuringProviderReadRejectsBeforeProjectionCommit() throws Exception
   {
      String process = begin(true);
      QUERY.set(() -> sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='CALENDAR'"));
      var response = submit(process, true, false);
      assertEquals(403, response.statusCode(), response.body());
      assertFalse(response.body().contains("Confidential"), response.body());
      assertEquals(0, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertEquals(1, count("SELECT count(*) FROM carl_request WHERE id='" + REQUESTS.get(process) + "' AND status='PENDING'"));
   }



   @Test
   void membershipChangeAfterCommitRejectsEvenOwnCommittedAcknowledgement() throws Exception
   {
      String process = begin(true);
      CONTROL.afterCommit.set(() -> sql("UPDATE carl_household SET display_zone='UTC' WHERE id=1"));
      var response = submit(process, true, false);
      assertEquals(403, response.statusCode(), response.body());
      assertFalse(response.body().contains("Confidential"), response.body());
      assertFalse(response.body().contains(RECEIPT), response.body());
      assertEquals(1, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertExpired(process);
   }



   @Test
   void preSubmitRevocationAnonymousOtherHouseholdAndDirectRunDoNotReadProvider() throws Exception
   {
      String process = begin(true);
      CONTROL.beforeRequest.set(() -> sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='CALENDAR'"));
      assertEquals(403, submit(process, true, false).statusCode());
      assertEquals(0, READS.get());
      assertTrue(request("/qqq/v1/processes/" + PROCESS + "/init", null, Map.of()).statusCode() >= 400);
      var other = request("/qqq/v1/processes/" + PROCESS + "/init", outsider, Map.of());
      String uuid = JSON.readTree(other.body()).path("processUUID").asText();
      assertFalse(uuid.isBlank(), other.body());
      assertDenied(request("/qqq/v1/processes/" + PROCESS + "/" + uuid + "/step/input", outsider, fields()));
      sql("UPDATE carl_permission SET details=true WHERE member_id=1 AND domain='CALENDAR'");
      for(String prefix : List.of("/qqq/v1", ""))
      {
         assertTrue(request(prefix + "/processes/" + PROCESS + "/run", alice, fields()).statusCode() >= 400);
      }
      assertEquals(0, READS.get());
      assertEquals(0, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
   }



   @Test
   void injectedRequestUuidAndOtherCallerCannotBorrowAttestation() throws Exception
   {
      String process = begin(true);
      var injected = new java.util.LinkedHashMap<>(fields());
      injected.put("requestId", UUID.randomUUID().toString());
      assertDenied(request("/qqq/v1/processes/" + PROCESS + "/" + process + "/step/input", alice, injected));
      assertEquals(403, request("/qqq/v1/processes/" + PROCESS + "/" + process + "/step/input", bob, fields()).statusCode());
      assertEquals(0, READS.get());
      assertReceipt(submit(process, true, false));
      assertExpired(process);
   }



   @Test
   void asynchronousV1AndLegacyReceiptsRetainJobUntilActualCommit() throws Exception
   {
      asynchronous(true, false);
      CALENDAR.set(calendar("PUBLIC", "Controlled restored event"));
      agenda.synchronize("alice", UUID.randomUUID(), FROM, FROM);
      CALENDAR.set(calendar("PRIVATE", "Confidential delayed legacy title"));
      asynchronous(false, true);
   }



   private static void asynchronous(boolean v1, boolean immediate) throws Exception
   {
      String process = begin(v1);
      var reached = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      CONTROL.beforeCommit.set(() ->
      {
         reached.countDown();
         try
         {
            if(!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            {
               throw new IllegalStateException("Controlled calendar commit barrier timed out");
            }
         }
         catch(InterruptedException interrupted)
         {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Controlled calendar commit barrier interrupted", interrupted);
         }
      });
      String job;
      try(var executor = java.util.concurrent.Executors.newSingleThreadExecutor())
      {
         var submitted = executor.submit(() -> submit(process, v1, immediate));
         try
         {
            assertTrue(reached.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var started = submitted.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, started.statusCode(), started.body());
            var body = JSON.readTree(started.body());
            assertEquals("JOB_STARTED", body.path("type").asText(), started.body());
            job = body.path("jobUUID").asText();
            assertFalse(job.isBlank(), started.body());
            var pending = status(process, job, v1, alice);
            assertEquals(200, pending.statusCode(), pending.body());
            assertFalse(pending.body().contains("Confidential"), pending.body());
            assertFalse(pending.body().contains(RECEIPT), pending.body());
            assertEquals(403, status(process, job, v1, bob).statusCode());
            assertEquals(0, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
         }
         finally
         {
            release.countDown();
         }
      }
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      HttpResponse<String> complete;
      do
      {
         complete = status(process, job, v1, alice);
         var body = JSON.readTree(complete.body());
         if(complete.statusCode() != 200 || "COMPLETE".equals(body.path("type").asText()))
         {
            break;
         }
         Thread.sleep(10);
      }
      while(System.nanoTime() < deadline);
      assertReceipt(complete);
      assertEquals(1, count("SELECT count(*) FROM carl_calendar_event WHERE free_busy_only"));
      assertEquals(403, status(process, job, v1, alice).statusCode());
      assertExpired(process);
   }



   private static HttpResponse<String> status(String process, String job, boolean v1, String token) throws Exception
   {
      return request((v1 ? "/qqq/v1" : "") + "/processes/" + PROCESS + "/" + process + "/status/" + job, token, null);
   }



   private static Map<String, String> fields()
   {
      return Map.of("collection", "events", "from", FROM.toString(), "through", FROM.toString());
   }



   private static void assertDenied(HttpResponse<String> response) throws Exception
   {
      var body = JSON.readTree(response.body());
      assertTrue(response.statusCode() >= 400 || body.hasNonNull("error") || "ERROR".equals(body.path("type").asText()), response.body());
      assertFalse(response.body().contains("Confidential"), response.body());
   }



   private static String calendar(String visibility, String title)
   {
      return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Controlled read-only test//EN\r\nBEGIN:VEVENT\r\nUID:stable-controlled-uid\r\nDTSTAMP:20261001T000000Z\r\nDTSTART:20261101T150000Z\r\nDTEND:20261101T160000Z\r\nCLASS:" + visibility + "\r\nSUMMARY:" + title + "\r\nDESCRIPTION:Confidential supplied description\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
   }



   private static String begin(boolean v1) throws Exception
   {
      var response = request((v1 ? "/qqq/v1" : "") + "/processes/" + PROCESS + "/init", alice, Map.of());
      assertEquals(200, response.statusCode(), response.body());
      var body = JSON.readTree(response.body());
      String uuid = body.path("processUUID").asText();
      assertFalse(uuid.isBlank(), response.body());
      REQUESTS.put(uuid, UUID.fromString(body.path("values").path("requestId").asText()));
      return uuid;
   }



   private static HttpResponse<String> submit(String uuid, boolean v1, boolean immediate) throws Exception
   {
      String route = (v1 ? "/qqq/v1" : "") + "/processes/" + PROCESS + "/" + uuid + "/step/input";
      if(immediate)
      {
         route += v1 ? "?stepTimeoutMillis=0" : "?_qStepTimeoutMillis=0";
      }
      return request(route, alice, fields());
   }



   private static void assertReceipt(HttpResponse<String> response) throws Exception
   {
      assertEquals(200, response.statusCode(), response.body());
      var body = JSON.readTree(response.body());
      assertEquals("COMPLETE", body.path("type").asText(), response.body());
      assertEquals(RECEIPT, body.path("values").path("result").asText(), response.body());
      var fields = new java.util.HashSet<String>();
      body.path("values").fieldNames().forEachRemaining(fields::add);
      assertEquals(Set.of("result", "result.html"), fields);
      assertFalse(response.body().contains("Confidential"), response.body());
      assertFalse(response.body().contains("coverage_from"), response.body());
      assertFalse(response.body().contains("last_success"), response.body());
   }



   private static void assertExpired(String uuid) throws Exception
   {
      for(String prefix : List.of("/qqq/v1", ""))
      {
         for(String path : List.of("/step/result", "/status/" + UUID.randomUUID(), "/records?skip=0&limit=20"))
         {
            var response = request(prefix + "/processes/" + PROCESS + "/" + uuid + path, alice, path.startsWith("/step") ? Map.of() : null);
            assertEquals(403, response.statusCode(), response.body());
            assertFalse(response.body().contains("Confidential"), response.body());
         }
      }
   }



   private static void sql(String query)
   {
      try(var c = data.getConnection(); var statement = c.createStatement())
      {
         statement.execute(query);
      }
      catch(java.sql.SQLException failure)
      {
         throw new IllegalStateException("Controlled calendar SQL failed", failure);
      }
   }



   private static int count(String query) throws Exception
   {
      try(var c = data.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery(query))
      {
         assertTrue(rows.next());
         return rows.getInt(1);
      }
   }



   private static HttpResponse<String> request(String route, String token, Map<String, String> values) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + route)).header("Origin", "https://carl.synthetic");
      if(token != null)
      {
         request.header("Authorization", "Bearer " + token);
      }
      if(values == null)
      {
         request.GET();
      }
      else
      {
         if(route.startsWith("/qqq/v1/") && route.contains("/step/"))
         {
            values = Map.of("values", JSON.writeValueAsString(values));
         }
         var body = new StringBuilder();
         values.forEach((name, value) -> body.append("--carl-calendar\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n").append(value).append("\r\n"));
         body.append("--carl-calendar--\r\n");
         request.header("Content-Type", "multipart/form-data; boundary=carl-calendar").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
   }
}
