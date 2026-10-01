/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


/** FAT-18 exact persisted plan/task fields through authenticated native QQQ V1. */
public final class CarlFamilyPlanQqqAssertions
{
   private static final ObjectMapper JSON = new ObjectMapper();

   private CarlFamilyPlanQqqAssertions()
   {
   }



   /** Proves permitted reads before all-recipient revocation on the same supported record route. */
   public static void assertCurrent(NativeAgentConfiguration configuration, com.kof22.agentadmin.bootstrap.NativeAgentRuntime.Components components, javax.sql.DataSource data, Jwk jwk, RSAPublicKey publicKey, RSAPrivateKey privateKey, com.fasterxml.jackson.databind.JsonNode expected, boolean bobRevoked) throws Exception
   {
      try(var c = data.getConnection(); var sql = c.createStatement())
      {
         sql.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='carl_fat18_reader') THEN CREATE ROLE carl_fat18_reader LOGIN PASSWORD 'synthetic-fat18-reader'; END IF; END $$");
         sql.execute("GRANT USAGE ON SCHEMA public TO carl_fat18_reader");
         for(var table : AdminApplication.READER_COLUMNS.entrySet())
         {
            if(!table.getValue().isEmpty())
            {
               sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_fat18_reader");
            }
         }
         sql.execute("GRANT SELECT ON carl_artifact_view,carl_plan_view,carl_native_plan_step_view TO carl_fat18_reader");
      }
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
      var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-admin", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> jwk);
      var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_fat18_reader", "synthetic-fat18-reader");
      var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), configuration.database().username(), configuration.database().password());
      var application = new AdminApplication(reader, components.metadata(), identity, runtime);
      try(var server = new AdminServer(application, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime)); var http = HttpClient.newHttpClient())
      {
         server.start();
         String base = "http://127.0.0.1:" + server.port();
         String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, privateKey));
         String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, privateKey));
         long plan = expected.path("plan").path("id").asLong();
         var task = expected.path("steps").get(0);
         for(String token : List.of(alice, bob))
         {
            var planResponse = request(http, base, "/qqq/v1/table/carlPlans/" + plan, token);
            var taskResponse = request(http, base, "/qqq/v1/table/carlPlanSteps/" + task.path("id").asText(), token);
            if(bobRevoked)
            {
               assertEquals(404, planResponse.statusCode(), planResponse.body());
               assertEquals(404, taskResponse.statusCode(), taskResponse.body());
               assertFalse(planResponse.body().contains("Family review plan"));
               assertFalse(taskResponse.body().contains("Review family checklist"));
               continue;
            }
            assertEquals(200, planResponse.statusCode(), planResponse.body());
            assertEquals(200, taskResponse.statusCode(), taskResponse.body());
            var savedPlan = JSON.readTree(planResponse.body()).path("record").path("values");
            var savedTask = JSON.readTree(taskResponse.body()).path("record").path("values");
            assertEquals(plan, savedPlan.path("id").asLong(), planResponse.body());
            assertEquals(expected.path("plan").path("version").asInt(), savedPlan.path("version").asInt());
            assertEquals(expected.path("plan").path("source_artifact_id").asLong(), savedPlan.path("source_artifact_id").asLong());
            assertEquals(expected.path("plan").path("state").asText(), savedPlan.path("state").asText());
            assertEquals(task.path("id").asText(), savedTask.path("id").asText(), taskResponse.body());
            assertEquals(plan, savedTask.path("plan_id").asLong());
            assertEquals(task.path("assignee_id").asLong(), savedTask.path("assignee_id").asLong());
            for(String field : List.of("title", "due_date", "location", "status", "checkin"))
            {
               assertEquals(task.path(field).asText(), savedTask.path(field).asText(), "Same permission-protected PostgreSQL task field: " + field);
            }
         }
      }
      finally
      {
         keyServer.stop(0);
      }
   }



   private static HttpResponse<String> request(HttpClient http, String base, String path, String token) throws Exception
   {
      return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic").GET().build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
   }
}
