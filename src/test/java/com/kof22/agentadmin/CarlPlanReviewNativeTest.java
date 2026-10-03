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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.PlanReviewMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Native review input cannot replace server-owned plan state over real PostgreSQL. */
class CarlPlanReviewNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void forgedFreshVersionCannotBypassConcurrentRevision() throws Exception
   {
      exercise(context ->
      {
         long plan = context.plan("Reviewed plan");
         UUID existing = context.task(plan);
         String process = context.review("carlPlanTask", plan);
         context.plans().checkIn("alice", plan, 2, existing, "BLOCKED", "Concurrent revision", null);
         var forged = new java.util.LinkedHashMap<>(taskFields());
         forged.put("expectedVersion", "3");
         assertRejected(context.submit("carlPlanTask", process, "review", forged));
         assertEquals(3, context.version(plan));
         assertEquals(1, context.steps(plan).size());
         var stale = context.submit("carlPlanTask", process, "review", taskFields());
         assertRejected(stale);
         assertTrue(stale.body().contains("Plan changed"), stale.body());
         assertEquals(3, context.version(plan));
      });
   }



   @Test
   void forgedTaskIdentityCannotReplaceAnExistingTask() throws Exception
   {
      exercise(context ->
      {
         long plan = context.plan("Reviewed task plan");
         UUID existing = context.task(plan);
         String process = context.review("carlPlanTask", plan);
         var forged = new java.util.LinkedHashMap<>(taskFields());
         forged.put("taskId", existing.toString());
         assertRejected(context.submit("carlPlanTask", process, "review", forged));
         assertEquals("Existing reviewed task", context.steps(plan).getFirst().get("title"));
         assertEquals(2, context.version(plan));
         assertSuccess(context.submit("carlPlanTask", process, "review", taskFields()));
         assertEquals(3, context.version(plan));
         assertEquals(2, context.steps(plan).size());
         assertTrue(context.steps(plan).stream().anyMatch(step -> !existing.toString().equals(step.get("id").toString()) && "Human new task".equals(step.get("title"))));
      });
   }



   @Test
   void reviewCannotRetargetAnotherReadablePlan() throws Exception
   {
      exercise(context ->
      {
         long reviewed = context.plan("Reviewed target");
         long other = context.plan("Other target");
         context.task(reviewed);
         context.task(other);
         String process = context.review("carlAgreePlan", reviewed);
         assertRejected(context.submit("carlAgreePlan", process, "review", Map.of("planId", Long.toString(other), "confirm", "true", "reason", "Forged target")));
         assertEquals(2, context.version(reviewed));
         assertEquals(2, context.version(other));
         String fresh = context.review("carlAgreePlan", reviewed);
         assertRejected(context.submit("carlAgreePlan", fresh, "review", Map.of("reviewedPlanId", Long.toString(other), "confirm", "true", "reason", "Forged protected target")));
         assertSuccess(context.submit("carlAgreePlan", fresh, "review", Map.of("confirm", "true", "reason", "Human explicit agreement")));
         assertEquals("AGREED", context.state(reviewed));
         assertEquals("DRAFT", context.state(other));
      });
   }



   @Test
   void normalReviewKeepsVerifiedCallerAndCurrentSourceAccess() throws Exception
   {
      exercise(context ->
      {
         long plan = context.plan("Private reviewed source");
         UUID task = context.task(plan);
         var started = context.request("/qqq/v1/processes/carlPlanCheckIn/init", context.bob(), Map.of());
         String deniedId = JSON.readTree(started.body()).path("processUUID").asText();
         var denied = context.submitAs("carlPlanCheckIn", deniedId, "choose", context.bob(), Map.of("planId", Long.toString(plan)));
         assertRejected(denied);
         assertFalse(denied.body().contains("Private reviewed source"), denied.body());
         String process = context.review("carlPlanCheckIn", plan);
         assertSuccess(context.submit("carlPlanCheckIn", process, "review", Map.of("stepId", task.toString(), "status", "BLOCKED", "note", "Human check-in")));
         assertEquals(3, context.version(plan));
         String revoked = context.review("carlPlanCheckIn", plan);
         try(var c = context.data().getConnection(); var sql = c.createStatement())
         {
            sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
         }
         assertRejected(context.submit("carlPlanCheckIn", revoked, "review", Map.of("stepId", task.toString(), "status", "TODO", "note", "Revoked request")));
         try(var c = context.data().getConnection();
            var sql = c.createStatement();
            var rows = sql.executeQuery("SELECT version FROM carl_plan WHERE record_id=" + plan))
         {
            assertTrue(rows.next());
            assertEquals(3, rows.getInt(1));
         }
      });
   }



   @Test
   void everySharedPlanReviewProtectsLoadedStateAndKeepsLocalCalendarStatus() throws Exception
   {
      exercise(context ->
      {
         for(String name : List.of("carlPlanTask", "carlAgreePlan", "carlPlanCheckIn", "carlReplan", "carlPublishCalendar"))
         {
            long plan = context.plan("Shared workflow " + name);
            UUID task = context.task(plan);
            String process = context.review(name, plan);
            Map<String, String> fields = switch(name)
            {
               case "carlPlanTask" -> taskFields();
               case "carlAgreePlan" -> Map.of("confirm", "true", "reason", "Human agreement");
               case "carlPlanCheckIn" -> Map.of("stepId", task.toString(), "status", "BLOCKED", "note", "Human check-in");
               case "carlReplan" -> Map.of("sourceArtifact", Long.toString(context.source()), "reason", "Human refreshed assumptions");
               default -> Map.of("collection", "reminders", "operation", "STATUS", "stepId", task.toString());
            };
            for(var entry : Map.of("expectedVersion", "2", "taskId", task.toString(), "reviewedPlanId", Long.toString(plan)).entrySet())
            {
               var forged = new java.util.LinkedHashMap<>(fields);
               forged.put(entry.getKey(), entry.getValue());
               assertRejected(context.submit(name, process, "review", forged));
               assertEquals(2, context.version(plan));
            }
            assertSuccess(context.submit(name, process, "review", fields));
            assertEquals(name.equals("carlPublishCalendar") ? 2 : 3, context.version(plan));
         }
      });
   }



   private static void exercise(Scenario scenario) throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         com.kof22.agentcore.store.AgentMigrations.migrate(data);
         var service = new CarlService(data, Clock.systemUTC());
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic vendor home','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true),(2,1,'bob','Synthetic other',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true),(1,'BILLS',true),(2,'BILLS',true),(1,'CALENDAR',true),(2,'CALENDAR',true),(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE carl_report_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_report_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_report_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_vendor_view,carl_work_view,carl_artifact_view,carl_draft_revision_view,carl_member_view,carl_native_plan_step_view,carl_transaction_view TO carl_report_reader");
         }
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var pair = generator.generateKeyPair();
         var publicKey = (RSAPublicKey) pair.getPublic();
         var key = Jwk.fromValues(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())));
         var keyServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
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
         var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> key);
         var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_report_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new PlanReviewMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of("carlProtectedVendorDrafts", "carlDownloadVendorDraft"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var finance = new com.kof22.carlai.domain.FinancialRecords(service);
            long account = finance.createAccount("alice", "Supplied test debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Supplied test evidence");
            var debt = new com.kof22.carlai.domain.DebtPlans(service);
            var from = java.time.LocalDate.of(2026, 9, 1);
            debt.terms("alice", account, from, new java.math.BigDecimal("1000"), new java.math.BigDecimal("100"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Supplied test statement");
            long source = debt.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), from, "USD", new java.math.BigDecimal("100"), 12, "Human test budget");
            scenario.run(new Context(http, base, alice, bob, data, new com.kof22.carlai.domain.PlanLifecycle(service), source));

         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static Map<String, String> taskFields()
   {
      return Map.of("title", "Human new task", "assignee", "1", "dueDate", "2026-09-30", "location", "Human action", "reason", "Human reviewed task");
   }



   private static void assertRejected(HttpResponse<String> response) throws Exception
   {
      assertTrue(response.statusCode() >= 400 || JSON.readTree(response.body()).hasNonNull("error"), response.body());
   }



   private static void assertSuccess(HttpResponse<String> response) throws Exception
   {
      assertEquals(200, response.statusCode(), response.body());
      assertFalse(JSON.readTree(response.body()).hasNonNull("error"), response.body());
   }

   @FunctionalInterface
   private interface Scenario
   {
      void run(Context context) throws Exception;
   }



   private record Context(HttpClient http, String base, String alice, String bob, javax.sql.DataSource data, com.kof22.carlai.domain.PlanLifecycle plans, long source)
   {
      long plan(String title)
      {
         return plans.create("alice", UUID.randomUUID(), source, title, "Human test selection");
      }



      UUID task(long plan)
      {
         UUID id = UUID.randomUUID();
         plans.step("alice", plan, 1, id, "Existing reviewed task", 1, java.time.LocalDate.of(2026, 9, 30), "Human action", null, "Original human task");
         return id;
      }



      int version(long plan)
      {
         return ((Number) ((Map<?, ?>) plans.get("alice", plan).get("plan")).get("version")).intValue();
      }



      String state(long plan)
      {
         return ((Map<?, ?>) plans.get("alice", plan).get("plan")).get("state").toString();
      }



      @SuppressWarnings("unchecked")
      List<Map<String, Object>> steps(long plan)
      {
         return (List<Map<String, Object>>) plans.get("alice", plan).get("steps");
      }



      String review(String name, long plan) throws Exception
      {
         var started = request("/qqq/v1/processes/" + name + "/init", alice, Map.of());
         assertSuccess(started);
         String id = JSON.readTree(started.body()).path("processUUID").asText();
         assertFalse(id.isBlank(), started.body());
         var loaded = submit(name, id, "choose", Map.of("planId", Long.toString(plan)));
         assertSuccess(loaded);
         assertTrue(loaded.body().contains("review"), loaded.body());
         return id;
      }



      HttpResponse<String> submit(String name, String id, String step, Map<String, String> fields) throws Exception
      {
         return submitAs(name, id, step, alice, fields);
      }



      HttpResponse<String> submitAs(String name, String id, String step, String token, Map<String, String> fields) throws Exception
      {
         return request("/qqq/v1/processes/" + name + "/" + id + "/step/" + step, token, fields);
      }



      HttpResponse<String> request(String route, String token, Map<String, String> fields) throws Exception
      {
         return CarlPlanReviewNativeTest.request(http, base, route, token, fields);
      }
   }

   private static HttpResponse<String> request(HttpClient http, String base, String route, String token, Map<String, String> fields) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + route)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic");
      if(fields == null)
      {
         request.GET();
      }
      else
      {
         if(route.contains("/step/"))
         {
            fields = Map.of("values", JSON.writeValueAsString(fields));
         }
         var body = new StringBuilder();
         fields.forEach((key, value) -> body.append("--carl-vendor\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
         body.append("--carl-vendor--\r\n");
         request.header("Content-Type", "multipart/form-data; boundary=carl-vendor").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
