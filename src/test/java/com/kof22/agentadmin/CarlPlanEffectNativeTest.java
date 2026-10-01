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
import com.kof22.carlai.PlanEffectMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real authenticated QQQ report and recovery routes over PostgreSQL. */
class CarlPlanEffectNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeExpectationAndObservationComparisonRemainHumanInitiated() throws Exception
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
         var app = new AdminApplication(reader, List.of(new PlanEffectMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of("carlProtectedVendorDrafts", "carlDownloadVendorDraft"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            assertTrue(metadata.body().contains("carlPlanExpectation"));
            var finance = new com.kof22.carlai.domain.FinancialRecords(service);
            long account = finance.createAccount("alice", "Synthetic debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic");
            var from = java.time.LocalDate.of(2026, 9, 1);
            var through = java.time.LocalDate.of(2026, 9, 30);
            var debt = new com.kof22.carlai.domain.DebtPlans(service);
            debt.terms("alice", account, from, new java.math.BigDecimal("1000"), new java.math.BigDecimal("100"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Synthetic statement");
            long source = debt.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), from, "USD", new java.math.BigDecimal("100"), 12, "Human budget");
            var plans = new com.kof22.carlai.domain.PlanLifecycle(service);
            long plan = plans.create("alice", UUID.randomUUID(), source, "Synthetic plan", "Human selection");
            UUID step = UUID.randomUUID();
            plans.step("alice", plan, 1, step, "Make payment", 1, through, "Bank app", null, "Human task");
            plans.agree("alice", plan, 2, "Agreed");
            Map<String, String> fields = Map.of("plan", Long.toString(plan), "version", "3", "step", step.toString(), "kind", "CASH_PAYMENT", "account", Long.toString(account), "amount", "100.00", "from", from.toString(), "through", through.toString(), "reason", "Explicit synthetic expectation");
            assertFalse(process(http, base, bob, "carlPlanExpectation", fields).contains("Saved expectation"));
            String created = process(http, base, alice, "carlPlanExpectation", fields);
            assertTrue(created.contains("Saved expectation"), created);
            long expectation;
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT record_id FROM carl_plan_effect"))
            {
               rows.next();
               expectation = rows.getLong(1);
            }
            long observation = new com.kof22.carlai.domain.BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), account, from.plusDays(2), new java.math.BigDecimal("-100"), "UNCLASSIFIED", "Payment", "Synthetic payment", "Human observation");
            String result = process(http, base, alice, "carlComparePlanEffect", Map.of("expectation", Long.toString(expectation), "transactionIds", Long.toString(observation), "evidence", "Explicit selected payment"));
            assertTrue(result.contains("Outcome: MATCH"), result);
            assertTrue(result.contains("not independent verification"), result);
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            String denied = process(http, base, alice, "carlComparePlanEffect", Map.of("expectation", Long.toString(expectation), "transactionIds", Long.toString(observation), "evidence", "Revoked request"));
            assertFalse(denied.contains("Outcome: MATCH"), denied);

         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static String process(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      String selection = fields.get("transactionIds");
      String query = selection == null ? "" : "?recordsParam=recordIds&recordIds=" + selection;
      var start = request(http, base, "/processes/" + name + "/init" + query, token, Map.of());
      assertEquals(200, start.statusCode(), start.body());
      String id = JSON.readTree(start.body()).path("processUUID").asText();
      assertFalse(id.isBlank(), start.body());
      var form = new java.util.LinkedHashMap<>(fields);
      form.remove("transactionIds");
      var result = request(http, base, "/processes/" + name + "/" + id + "/step/input", token, form);
      assertEquals(200, result.statusCode(), result.body());
      return result.body();
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
