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
import com.kof22.carlai.VendorMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Actual native action selection and authorization against real PostgreSQL. */
class CarlContextualActionNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void selectedRecordActionsUseAuthoritativeRowsAndDenyGuessedOrBulkTargets() throws Exception
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
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true),(1,'FINANCE',true),(1,'TAX',true)");
            sql.execute("CREATE ROLE carl_vendor_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_vendor_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_vendor_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_vendor_view,carl_work_view,carl_artifact_view,carl_draft_revision_view,carl_member_view,carl_plan_view TO carl_vendor_reader");
         }
         long vendor = service.createVendor("alice", "Private synthetic vendor", "REPAIR", "vendor@example.invalid", true, "PRIVATE", "Synthetic supplied contact");
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
         var app = new AdminApplication(reader, List.of(new VendorMetadataFixture(service)), identity, runtime);
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
            var root = JSON.readTree(metadata.body());
            assertEquals(1, root.path("processes").path("carlCorrectVendor").path("maxInputRecords").asInt());
            var selected = request(http, base, "/processes/carlCorrectVendor/init", alice, Map.of("recordsParam", "recordIds", "recordIds", "" + vendor));
            assertEquals(200, selected.statusCode(), selected.body());
            var initial = JSON.readTree(selected.body());
            assertEquals(vendor, initial.path("values").path("vendorId").asLong());
            assertEquals(1, initial.path("values").path("expectedRevision").asLong());
            assertEquals("Private synthetic vendor", initial.path("values").path("title").asText());
            var corrected = request(http, base, "/processes/carlCorrectVendor/" + initial.path("processUUID").asText() + "/step/input", alice, Map.of("vendorId", "" + vendor, "expectedRevision", "1", "title", "Reviewed supplied vendor", "category", "PLUMBING", "contact", "verified@example.invalid", "verified", "true", "reason", "Confirmed source details"));
            assertEquals(200, corrected.statusCode(), corrected.body());
            assertTrue(corrected.body().contains("Vendor corrected with attribution"), corrected.body());
            var stored = service.view(CarlService.Scope.privateFor("alice"), "vendors").stream().filter(row -> ((Number) row.get("id")).longValue() == vendor).findFirst().orElseThrow();
            assertEquals("Reviewed supplied vendor", stored.get("title"));
            assertEquals(2L, ((Number) stored.get("revision")).longValue());
            try(var c = data.getConnection(); var statement = c.prepareStatement("SELECT member_id,reason FROM carl_correction WHERE record_id=?"))
            {
               statement.setLong(1, vendor);
               try(var audit = statement.executeQuery())
               {
                  assertTrue(audit.next());
                  assertEquals(1, audit.getLong("member_id"));
                  assertEquals("Confirmed source details", audit.getString("reason"));
               }
            }
            var denied = request(http, base, "/processes/carlCorrectVendor/init", bob, Map.of("recordsParam", "recordIds", "recordIds", "" + vendor));
            assertTrue(denied.statusCode() >= 400 || JSON.readTree(denied.body()).hasNonNull("error"), denied.body());
            assertFalse(denied.body().contains("Reviewed supplied vendor"), denied.body());
            long second = service.createVendor("alice", "Second private vendor", "REPAIR", null, false, "PRIVATE", "Second supplied source");
            var bulk = request(http, base, "/processes/carlCorrectVendor/init", alice, Map.of("recordsParam", "recordIds", "recordIds", vendor + "," + second));
            assertTrue(bulk.statusCode() >= 400 || JSON.readTree(bulk.body()).hasNonNull("error"), bulk.body());
            var blank = request(http, base, "/processes/carlCorrectVendor/init", alice, Map.of());
            assertEquals(200, blank.statusCode(), blank.body());
            assertFalse(JSON.readTree(blank.body()).path("values").has("vendorId"));
            var preparedReadiness = request(http, base, "/processes/carlGenerateReadinessPlan/init", alice, Map.of());
            assertEquals(200, preparedReadiness.statusCode(), preparedReadiness.body());
            var prepared = JSON.readTree(preparedReadiness.body());
            String readinessUuid = prepared.path("processUUID").asText();
            String generatedRequest = prepared.path("values").path("requestId").asText();
            assertFalse(generatedRequest.isBlank(), preparedReadiness.body());
            String substitutedRequest = java.util.UUID.randomUUID().toString();
            var forgedReadiness = request(http, base, "/processes/carlGenerateReadinessPlan/" + readinessUuid + "/step/input", alice, Map.of("title", "Private financial preparation", "requestId", substitutedRequest));
            assertTrue(forgedReadiness.statusCode() >= 400 || JSON.readTree(forgedReadiness.body()).hasNonNull("error"), "A client must not replace the generated logical request: " + forgedReadiness.body());
            assertTrue(service.view(CarlService.Scope.privateFor("alice"), "plans").isEmpty(), "Rejected request substitution must not create a plan");
            var readinessResponse = request(http, base, "/processes/carlGenerateReadinessPlan/" + readinessUuid + "/step/input", alice, Map.of("title", "Private financial preparation"));
            assertEquals(200, readinessResponse.statusCode(), readinessResponse.body());
            var readiness = JSON.readTree(readinessResponse.body());
            assertEquals(generatedRequest, readiness.path("values").path("requestId").asText());
            assertTrue(readiness.path("values").path("result").asText().contains("Saved private draft plan"), readiness.toString());
            var sameRequestRetry = request(http, base, "/processes/carlGenerateReadinessPlan/" + readinessUuid + "/step/input", alice, Map.of("title", "Private financial preparation"));
            assertEquals(200, sameRequestRetry.statusCode(), sameRequestRetry.body());
            assertEquals(1, service.view(CarlService.Scope.privateFor("alice"), "plans").size(), "Exact process retry must not duplicate the plan");
            var report = service.view(CarlService.Scope.privateFor("alice"), "artifacts").stream().filter(row -> "FINANCIAL_PLAN".equals(row.get("kind"))).findFirst().orElseThrow();
            var plan = service.view(CarlService.Scope.privateFor("alice"), "plans").stream().filter(row -> "Private financial preparation".equals(row.get("title"))).findFirst().orElseThrow();
            assertEquals("DRAFT", plan.get("state"));
            long reportId = ((Number) report.get("id")).longValue();
            var nativePlan = request(http, base, "/data/carlPlans/" + plan.get("id"), alice, null);
            assertEquals(200, nativePlan.statusCode(), nativePlan.body());
            assertTrue(nativePlan.body().contains("DRAFT"), nativePlan.body());
            var nativeReport = request(http, base, "/data/carlArtifacts/" + reportId, alice, null);
            assertEquals(200, nativeReport.statusCode(), nativeReport.body());
            assertTrue(nativeReport.body().contains("FINANCIAL_READINESS"), nativeReport.body());
            var inaccessible = request(http, base, "/data/carlPlans/" + plan.get("id"), bob, null);
            assertTrue(inaccessible.statusCode() >= 400, inaccessible.body());
            assertFalse(inaccessible.body().contains("Private financial preparation"), inaccessible.body());
            var deniedReadiness = request(http, base, "/processes/carlGenerateReadinessPlan/init", bob, Map.of());
            assertEquals(200, deniedReadiness.statusCode(), deniedReadiness.body());
            var deniedResult = request(http, base, "/processes/carlGenerateReadinessPlan/" + JSON.readTree(deniedReadiness.body()).path("processUUID").asText() + "/step/input", bob, Map.of("title", "Unauthorized preparation"));
            assertTrue(deniedResult.statusCode() >= 400 || JSON.readTree(deniedResult.body()).hasNonNull("error"), deniedResult.body());
            var download = process(http, base, alice, "carlDownloadReportPdf", Map.of("reportId", "" + reportId));
            String reference = download.path("values").path("storageReference").asText();
            assertFalse(reference.isBlank(), download.toString());
            String route = "/download/Carl-readiness.pdf?storageTableName=carlProtectedReportPdfs&storageReference=" + reference;
            var pdf = request(http, base, route, alice, null);
            assertEquals(200, pdf.statusCode(), pdf.body());
            assertTrue(pdf.body().startsWith("%PDF-"));
            assertEquals("no-store", pdf.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(request(http, base, route, bob, null).statusCode() >= 400);
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            var revoked = request(http, base, "/processes/carlCorrectVendor/init", alice, Map.of("recordsParam", "recordIds", "recordIds", "" + vendor));
            assertTrue(revoked.statusCode() >= 400 || JSON.readTree(revoked.body()).hasNonNull("error"), revoked.body());
            assertFalse(revoked.body().contains("Reviewed supplied vendor"), revoked.body());
            assertTrue(request(http, base, route, alice, null).statusCode() >= 400);
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
