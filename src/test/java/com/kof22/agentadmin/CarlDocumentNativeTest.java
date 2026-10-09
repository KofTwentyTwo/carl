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
import com.kof22.carlai.DocumentMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Synthetic real QQQ multipart upload, durable preview and protected original download over PostgreSQL. */
class CarlDocumentNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeOriginalUploadPreviewConfirmationAndProtectedDownload() throws Exception
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
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE carl_document_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_document_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_document_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_artifact_view TO carl_document_reader");
            sql.execute("GRANT SELECT ON carl_document_view TO carl_document_reader");
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_document_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new DocumentMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of("carlProtectedDocuments", "carlDownloadDocument"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            assertTrue(metadata.body().contains("carlRegisterDocument"));
            var started = request(http, base, "/qqq/v1/processes/carlRegisterDocument/init", alice, Map.of());
            assertEquals(200, started.statusCode(), started.body());
            String id = JSON.readTree(started.body()).path("processUUID").asText();
            String inputRoute = "/qqq/v1/processes/carlRegisterDocument/" + id + "/step/input";
            var protectedRequest = request(http, base, inputRoute, alice, Map.of("requestId", UUID.randomUUID().toString()));
            assertTrue(protectedRequest.statusCode() >= 400 || JSON.readTree(protectedRequest.body()).path("type").asText().equals("ERROR"), protectedRequest.body());
            var badName = upload(http, base, inputRoute, alice, "../../never-read.txt");
            assertTrue(badName.statusCode() >= 400, badName.body());
            var preview = upload(http, base, inputRoute, alice, "synthetic-history.txt");
            assertEquals(200, preview.statusCode(), preview.body());
            assertTrue(preview.body().contains("TEXT_EXTRACTED"), preview.body());
            var documents = new com.kof22.carlai.domain.DocumentRecords(service);
            assertTrue(documents.list("alice", 0, 10).isEmpty());
            String reviewRoute = "/qqq/v1/processes/carlRegisterDocument/" + id + "/step/review";
            var overlay = request(http, base, reviewRoute, alice, Map.of("confirm", "true", "reviewId", UUID.randomUUID().toString()));
            assertTrue(overlay.statusCode() >= 400 || JSON.readTree(overlay.body()).path("type").asText().equals("ERROR"), overlay.body());
            var confirmed = request(http, base, reviewRoute, alice, Map.of("confirm", "true"));
            assertEquals(200, confirmed.statusCode(), confirmed.body());
            assertTrue(confirmed.body().contains("registered"), confirmed.body());
            long document = ((Number) documents.list("alice", 0, 10).getFirst().get("id")).longValue();
            assertEquals("PRIVATE", documents.metadata("alice", document).get("visibility"));
            assertEquals("synthetic-history.txt", documents.metadata("alice", document).get("original_name"));
            assertEquals("text/plain;charset=UTF-8", documents.metadata("alice", document).get("media_type"));
            assertTrue(documents.list("bob", 0, 10).isEmpty());
            var record = request(http, base, "/qqq/v1/table/carlDocuments/" + document, alice, null);
            assertEquals(200, record.statusCode(), record.body());
            assertEquals(document, JSON.readTree(record.body()).path("record").path("values").path("id").asLong());
            assertTrue(record.body().contains("SUPPLIED_UNVERIFIED"), record.body());
            assertFalse(record.body().contains("extracted_text"), record.body());
            assertFalse(record.body().contains("original_content"), record.body());
            assertTrue(request(http, base, "/qqq/v1/table/carlDocuments/" + document, bob, null).statusCode() >= 400);
            var listed = jsonRequest(http, base, "/qqq/v1/table/carlDocuments/query", alice);
            assertEquals(200, listed.statusCode(), listed.body());
            assertEquals(1, JSON.readTree(listed.body()).path("records").size());
            var otherList = jsonRequest(http, base, "/qqq/v1/table/carlDocuments/query", bob);
            assertEquals(200, otherList.statusCode(), otherList.body());
            assertEquals(0, JSON.readTree(otherList.body()).path("records").size());
            var possible = jsonRequest(http, base, "/qqq/v1/possibleValues/carlDocuments", alice);
            assertEquals(200, possible.statusCode(), possible.body());
            assertTrue(possible.body().contains("Synthetic uploaded source"), possible.body());
            var otherPossible = jsonRequest(http, base, "/qqq/v1/possibleValues/carlDocuments", bob);
            assertEquals(200, otherPossible.statusCode(), otherPossible.body());
            assertFalse(otherPossible.body().contains("Synthetic uploaded source"), otherPossible.body());
            var download = JSON.readTree(process(http, base, alice, "carlDownloadDocument", Map.of("documentId", Long.toString(document))));
            String reference = download.path("values").path("storageReference").asText();
            assertFalse(reference.isBlank(), download.toString());
            assertEquals("Carl-document-" + document + ".txt", download.path("values").path("downloadFileName").asText());
            String route = "/qqq/v1/download/Carl-original.bin?storageTableName=carlProtectedDocuments&storageReference=" + reference;
            var accepted = request(http, base, route, alice, null);
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertEquals("Synthetic uploaded original. Untrusted historical evidence.", accepted.body());
            assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(request(http, base, route, bob, null).statusCode() >= 400);
            assertTrue(request(http, base, route + "&filePath=/etc/passwd", alice, null).statusCode() >= 400);
            var anonymous = http.send(HttpRequest.newBuilder(URI.create(base + route)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(anonymous.statusCode() >= 400);
            assertTrue(request(http, base, "/data/carlProtectedDocuments", alice, null).statusCode() >= 400);
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            var revoked = request(http, base, route, alice, null);
            assertTrue(revoked.statusCode() >= 400, revoked.body());
            assertFalse(revoked.body().contains("Synthetic uploaded original"));
            assertTrue(request(http, base, "/qqq/v1/table/carlDocuments/" + document, alice, null).statusCode() >= 400);
            var revokedPossible = jsonRequest(http, base, "/qqq/v1/possibleValues/carlDocuments", alice);
            assertFalse(revokedPossible.body().contains("Synthetic uploaded source"), revokedPossible.body());

         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static String process(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      var start = request(http, base, "/qqq/v1/processes/" + name + "/init", token, Map.of());
      assertEquals(200, start.statusCode(), start.body());
      String id = JSON.readTree(start.body()).path("processUUID").asText();
      assertFalse(id.isBlank(), start.body());
      var result = request(http, base, "/qqq/v1/processes/" + name + "/" + id + "/step/input", token, fields);
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
         Map.of("values", JSON.writeValueAsString(fields)).forEach((key, value) -> body.append("--carl-report\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
         body.append("--carl-report--\r\n");
         request.header("Content-Type", "multipart/form-data; boundary=carl-report").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static HttpResponse<String> upload(HttpClient http, String base, String route, String token, String filename) throws Exception
   {
      var body = new StringBuilder();
      Map.of("values", JSON.writeValueAsString(Map.of("title", "Synthetic uploaded source", "sourceIdentity", "https://never-fetch.invalid/source", "sourceType", "HISTORICAL_NOTE", "provenance", "Synthetic explicitly supplied original"))).forEach((key, value) -> body.append("--carl-report\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
      body.append("--carl-report\r\nContent-Disposition: form-data; name=\"documentFile\"; filename=\"").append(filename).append("\"\r\nContent-Type: text/plain\r\n\r\nSynthetic uploaded original. Untrusted historical evidence.\r\n--carl-report--\r\n");
      var request = HttpRequest.newBuilder(URI.create(base + route)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic").header("Content-Type", "multipart/form-data; boundary=carl-report").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static HttpResponse<String> jsonRequest(HttpClient http, String base, String route, String token) throws Exception
   {
      return http.send(HttpRequest.newBuilder(URI.create(base + route)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic")
         .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
