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

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.BulkMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Actual native action selection and authorization against real PostgreSQL. */
class CarlBulkTransactionNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeBulkPreviewAndApplyAreAtomicAndServerStateCannotBeOverwritten() throws Exception
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
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true),(1,'FINANCE',true),(2,'FINANCE',true),(1,'TAX',true)");
            sql.execute("CREATE ROLE carl_vendor_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_vendor_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_vendor_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_vendor_view,carl_work_view,carl_artifact_view,carl_draft_revision_view,carl_member_view,carl_plan_view,carl_transaction_view,carl_account_view TO carl_vendor_reader");
         }
         var finance = new com.kof22.carlai.domain.FinancialRecords(service);
         long account = finance.createAccount("alice", "Supplied test account", "CASH", "USD", true, java.math.BigDecimal.ONE, "PRIVATE", "Supplied test evidence");
         long other = finance.createAccount("bob", "Other private account", "CASH", "USD", true, java.math.BigDecimal.ONE, "PRIVATE", "Other test evidence");
         var manual = new com.kof22.carlai.domain.BudgetRecords(service);
         long first = manual.manualTransaction("alice", java.util.UUID.randomUUID(), account, java.time.LocalDate.parse("2026-09-01"), new java.math.BigDecimal("-1.25"), "UNCLASSIFIED", "Source category", "Test first", "Human entered test record");
         long second = manual.manualTransaction("alice", java.util.UUID.randomUUID(), account, java.time.LocalDate.parse("2026-09-01"), new java.math.BigDecimal("-2.50"), "UNCLASSIFIED", "Source category", "Test second", "Human entered test record");
         long hidden = manual.manualTransaction("bob", java.util.UUID.randomUUID(), other, java.time.LocalDate.parse("2026-09-01"), new java.math.BigDecimal("-9.00"), "UNCLASSIFIED", "Other source category", "Hidden test merchant", "Other private record");
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_vendor_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new BulkMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of("carlProtectedVendorDrafts", "carlDownloadVendorDraft", "carlProtectedReportPdfs", "carlDownloadReportPdf"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            var schema = JSON.readTree(metadata.body()).path("processes").path("carlClassifyTransactions");
            assertEquals(100, schema.path("maxInputRecords").asInt());
            var mixed = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/init", alice, Map.of("recordsParam", "recordIds", "recordIds", first + "," + hidden));
            assertTrue(mixed.statusCode() >= 400 || JSON.readTree(mixed.body()).hasNonNull("error"), mixed.body());
            assertFalse(mixed.body().contains("Hidden test merchant"), mixed.body());
            assertEquals("UNCLASSIFIED", service.view(CarlService.Scope.privateFor("alice"), "transactions").getFirst().get("classification"));
            long beforeRequests;
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_request"))
            {
               assertTrue(rows.next());
               beforeRequests = rows.getLong(1);
            }
            var init = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/init", alice, Map.of("recordsParam", "recordIds", "recordIds", first + "," + second));
            assertEquals(200, init.statusCode(), init.body());
            var initialized = JSON.readTree(init.body());
            assertFalse(initialized.hasNonNull("error"), init.body());
            String id = initialized.path("processUUID").asText();
            assertFalse(id.isBlank(), init.body());
            var injected = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + id + "/step/input", alice, Map.of("requestId", java.util.UUID.randomUUID().toString(), "classification", "EXPENSE", "category", "Reviewed category", "reason", "Actual human test review"));
            assertTrue(injected.statusCode() >= 400 || JSON.readTree(injected.body()).hasNonNull("error"), injected.body());
            var preview = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + id + "/step/input", alice, Map.of("classification", "EXPENSE", "category", "Reviewed category", "reason", "Actual human test review"));
            assertEquals(200, preview.statusCode(), preview.body());
            var previewed = JSON.readTree(preview.body());
            assertFalse(previewed.hasNonNull("error"), preview.body());
            assertTrue(previewed.path("values").path("preview").asText().contains("Selected transactions: 2"), preview.body());
            assertEquals("UNCLASSIFIED", service.view(CarlService.Scope.privateFor("alice"), "transactions").getFirst().get("classification"));
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_request"))
            {
               assertTrue(rows.next());
               assertEquals(beforeRequests, rows.getLong(1));
            }
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_correction"))
            {
               assertTrue(rows.next());
               assertEquals(0, rows.getInt(1));
            }

            var overwrite = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + id + "/step/review", alice, Map.of("confirm", "true", "previewToken", "a".repeat(64), "selectedRecordIds", Long.toString(first)));
            assertTrue(overwrite.statusCode() >= 400 || JSON.readTree(overwrite.body()).hasNonNull("error"), overwrite.body());
            var applied = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + id + "/step/review", alice, Map.of("confirm", "true"));
            assertEquals(200, applied.statusCode(), applied.body());
            var result = JSON.readTree(applied.body());
            assertFalse(result.hasNonNull("error"), applied.body());
            assertTrue(result.path("values").path("result").asText().contains("2 selected transaction corrections committed"), applied.body());
            assertTrue(service.view(CarlService.Scope.privateFor("alice"), "transactions").stream().allMatch(row -> "EXPENSE".equals(row.get("classification"))));
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_correction"))
            {
               assertTrue(rows.next());
               assertEquals(2, rows.getInt(1));
            }
            var replay = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + id + "/step/review", alice, Map.of("confirm", "true"));
            assertEquals(200, replay.statusCode(), replay.body());
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_correction"))
            {
               assertTrue(rows.next());
               assertEquals(2, rows.getInt(1));
            }

            var declineStart = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/init", alice, Map.of("recordsParam", "recordIds", "recordIds", Long.toString(first)));
            String declineId = JSON.readTree(declineStart.body()).path("processUUID").asText();
            var declinePreview = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + declineId + "/step/input", alice, Map.of("classification", "INCOME", "category", "Declined category", "reason", "Must not apply without confirmation"));
            assertEquals(200, declinePreview.statusCode(), declinePreview.body());
            var notConfirmed = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + declineId + "/step/review", alice, Map.of("confirm", "false"));
            assertTrue(notConfirmed.statusCode() >= 400 || JSON.readTree(notConfirmed.body()).hasNonNull("error"), notConfirmed.body());
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_correction"))
            {
               assertTrue(rows.next());
               assertEquals(2, rows.getInt(1));
            }
            var restart = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/init", alice, Map.of("recordsParam", "recordIds", "recordIds", Long.toString(first)));
            assertEquals(200, restart.statusCode(), restart.body());
            String revokedId = JSON.readTree(restart.body()).path("processUUID").asText();
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            var revoked = request(http, base, "/qqq/v1/processes/carlClassifyTransactions/" + revokedId + "/step/input", alice, Map.of("classification", "INCOME", "category", "Forbidden", "reason", "Must not run after revoke"));
            assertTrue(revoked.statusCode() >= 400 || JSON.readTree(revoked.body()).hasNonNull("error"), revoked.body());
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_correction"))
            {
               assertTrue(rows.next());
               assertEquals(2, rows.getInt(1));
            }
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static com.fasterxml.jackson.databind.JsonNode process(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      var started = request(http, base, "/processes/" + name + "/init", token, Map.of());
      assertEquals(200, started.statusCode(), started.body());
      String id = JSON.readTree(started.body()).path("processUUID").asText();
      assertFalse(id.isBlank(), started.body());
      var completed = request(http, base, "/processes/" + name + "/" + id + "/step/input", token, fields);
      assertEquals(200, completed.statusCode(), completed.body());
      var result = JSON.readTree(completed.body());
      assertFalse(result.hasNonNull("error"), completed.body());
      return result;
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
         if(route.startsWith("/qqq/v1/") && route.contains("/step/"))
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
