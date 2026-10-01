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
import com.kof22.carlai.VendorMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real QQQ maintenance, immutable edit and process-bound text-download routes over PostgreSQL. */
class CarlVendorNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeVendorDraftFormsAndProtectedDownloadUseCurrentPermissions() throws Exception
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
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true)");
            sql.execute("CREATE ROLE carl_vendor_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_vendor_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_vendor_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_vendor_view,carl_work_view,carl_artifact_view,carl_draft_revision_view,carl_member_view TO carl_vendor_reader");
         }
         long vendor = service.createVendor("alice", "Private synthetic vendor", "REPAIR", "vendor@example.invalid", true, "PRIVATE", "Synthetic supplied contact");
         long work = service.createWorkItem("alice", vendor, "Synthetic repair issue", "VENDOR_RESPONSE", null, "No price or commitment", "PRIVATE");
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
         }, new NativeDownloadPolicy(Map.of("carlProtectedVendorDrafts", "carlDownloadVendorDraft"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            assertTrue(metadata.body().contains("carlEditVendorDraft"));
            process(http, base, alice, "carlCorrectVendor", Map.of("vendorId", "" + vendor, "expectedRevision", "1", "title", "Corrected vendor", "category", "PLUMBING", "contact", "verified@example.invalid", "verified", "true", "reason", "Verified supplied record"));
            process(http, base, alice, "carlMaintainVendorWork", Map.of("workId", "" + work, "expectedRevision", "1", "title", "Corrected repair issue", "workStatus", "FAMILY_RESPONSE", "reason", "Reviewed supplied correspondence"));
            long draft = service.generateDraft(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), work, "FOLLOW_UP");
            var edit = process(http, base, alice, "carlEditVendorDraft", Map.of("draftId", "" + draft, "expectedVersion", "1", "draftText", "Please clarify the repair scope.", "reason", "Human reviewed wording"));
            assertTrue(edit.contains("human edited"), edit);
            long edited = service.view(CarlService.Scope.privateFor("alice"), "artifacts").stream().filter(row -> ((Number) row.get("version")).intValue() == 2).mapToLong(row -> ((Number) row.get("id")).longValue()).findFirst().orElseThrow();
            assertTrue(process(http, base, alice, "carlCopyVendorDraft", Map.of("draftId", "" + edited)).contains("Please clarify the repair scope."));
            assertTrue(process(http, base, alice, "carlVendorDraftHistory", Map.of("draftId", "" + edited, "beforeVersion", "1001", "pageSize", "10")).contains("Version 1"));
            var download = JSON.readTree(process(http, base, alice, "carlDownloadVendorDraft", Map.of("draftId", "" + edited)));
            String reference = download.path("values").path("storageReference").asText();
            assertFalse(reference.isBlank(), download.toString());
            String route = "/download/Carl-draft.txt?storageTableName=carlProtectedVendorDrafts&storageReference=" + reference;
            var accepted = request(http, base, route, alice, null);
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertTrue(accepted.body().contains("Draft — not sent"));
            assertTrue(accepted.body().contains("Version: 2"));
            assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(request(http, base, route, bob, null).statusCode() >= 400);
            assertTrue(request(http, base, route + "&filePath=/etc/passwd", alice, null).statusCode() >= 400);
            assertTrue(request(http, base, "/data/carlProtectedVendorDrafts", alice, null).statusCode() >= 400);
            assertFalse(request(http, base, "/data/carlDraftVersions", bob, null).body().contains("Please clarify"));
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
            }
            var revoked = request(http, base, route, alice, null);
            assertTrue(revoked.statusCode() >= 400, revoked.body());
            assertFalse(revoked.body().contains("Please clarify"));
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static String process(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      var start = request(http, base, "/processes/" + name + "/init", token, Map.of());
      assertEquals(200, start.statusCode(), start.body());
      String id = JSON.readTree(start.body()).path("processUUID").asText();
      assertFalse(id.isBlank(), start.body());
      var result = request(http, base, "/processes/" + name + "/" + id + "/step/input", token, fields);
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
