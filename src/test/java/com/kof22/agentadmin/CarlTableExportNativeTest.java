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
import com.kof22.carlai.TableExportMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Controlled real QQQ v1 checked/full CSV export and protected download over PostgreSQL. */
class CarlTableExportNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeCheckedFullExportAndProtectedDownload() throws Exception
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
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true),(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE carl_export_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_export_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_export_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_artifact_view,carl_vendor_view,carl_table_export_view,carl_import_review_view TO carl_export_reader");
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_export_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new TableExportMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of("carlProtectedTableExports", "carlDownloadTableExport"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            assertTrue(metadata.body().contains("carlDownloadTableExport"));
            long own = service.createVendor("alice", "Private Évidence, \"quoted\"", "Synthetic", "=UNTRUSTED()", false, "PRIVATE", "Supplied source");
            long family = service.createVendor("alice", "Family supplied", "Synthetic", "Family contact", false, "FAMILY", "Supplied source");
            long hidden = service.createVendor("bob", "Other private", "Synthetic", "Contact", false, "PRIVATE", "Supplied source");
            for(String process : com.kof22.carlai.domain.TableExports.SOURCES.keySet())
            {
               assertTrue(metadata.body().contains("carlExportRecords" + process.substring(4)), process);
               var everySource = process(http, base, alice, "carlExportRecords" + process.substring(4), Map.of("scope", "ALL_AUTHORIZED"));
               assertFalse(everySource.path("values").path("exportId").asText().isBlank(), everySource.toString());
            }
            UUID uuidSource = UUID.randomUUID();
            try(var c = data.getConnection(); var statement = c.createStatement())
            {
               statement.execute("INSERT INTO carl_upload(reference,member_id,contents) VALUES('controlled-uuid-source',1,'Supplied original'::bytea)");
               statement.execute("INSERT INTO carl_import_review(id,member_id,transaction_reference,status,result) VALUES('" + uuidSource + "',1,'controlled-uuid-source','PREVIEW','Historical pending import')");
            }
            var uuidStart = request(http, base, "/qqq/v1/processes/carlExportRecordsImportReviews/init", alice, Map.of("recordsParam", "recordIds", "recordIds", uuidSource.toString()));
            assertEquals(200, uuidStart.statusCode(), uuidStart.body());
            String uuidProcess = JSON.readTree(uuidStart.body()).path("processUUID").asText();
            var uuidResult = request(http, base, "/qqq/v1/processes/carlExportRecordsImportReviews/" + uuidProcess + "/step/input", alice, Map.of("scope", "SELECTED"));
            assertFalse(JSON.readTree(uuidResult.body()).path("values").path("exportId").asText().isBlank(), uuidResult.body());
            var mixed = request(http, base, "/qqq/v1/processes/carlExportRecordsVendors/init", alice, Map.of("recordsParam", "recordIds", "recordIds", own + "," + hidden));
            denied(mixed);
            var started = request(http, base, "/qqq/v1/processes/carlExportRecordsVendors/init", alice, Map.of("recordsParam", "recordIds", "recordIds", own + "," + family));
            assertEquals(200, started.statusCode(), started.body());
            String uuid = JSON.readTree(started.body()).path("processUUID").asText();
            assertFalse(uuid.isBlank(), started.body());
            String route = "/qqq/v1/processes/carlExportRecordsVendors/" + uuid + "/step/input";
            denied(request(http, base, route, alice, Map.of("scope", "SELECTED", "requestId", UUID.randomUUID().toString())));
            denied(request(http, base, route, alice, Map.of("scope", "SELECTED", "selectedRecordIds", "" + hidden)));
            denied(request(http, base, route, alice, Map.of("scope", "SELECTED", "sourceTable", "carlMembers")));
            var rawExport = http.send(HttpRequest.newBuilder(URI.create(base + "/qqq/v1/table/carlVendors/export")).header("Authorization", "Bearer " + alice).header("Origin", "https://carl.synthetic").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("format", "csv", "filename", "normal.csv", "fieldNames", List.of("title"))))).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(rawExport.statusCode() >= 400, rawExport.body());

            var completed = request(http, base, route, alice, Map.of("scope", "SELECTED"));
            assertEquals(200, completed.statusCode(), completed.body());
            var result = JSON.readTree(completed.body());
            assertFalse(result.hasNonNull("error"), completed.body());
            String export = result.path("values").path("exportId").asText();
            assertFalse(export.isBlank(), completed.body());
            assertTrue(result.path("values").path("result").asText().contains("dated") || completed.body().contains("Dated"));
            var full = process(http, base, alice, "carlExportRecordsVendors", Map.of("scope", "ALL_AUTHORIZED"));
            assertFalse(full.path("values").path("exportId").asText().isBlank(), full.toString());
            var download = process(http, base, alice, "carlDownloadTableExport", Map.of("exportId", export));
            String reference = download.path("values").path("storageReference").asText();
            assertEquals(export, reference, download.toString());
            String downloadRoute = "/qqq/v1/download/Carl.csv?storageTableName=carlProtectedTableExports&storageReference=" + reference;
            var bytes = request(http, base, downloadRoute, alice, null);
            assertEquals(200, bytes.statusCode(), bytes.body());
            assertTrue(bytes.body().contains("SPREADSHEET_SAFE_TEXT_PREFIX_V1"));
            assertTrue(bytes.body().contains("Évidence"));
            assertTrue(bytes.body().contains("'=UNTRUSTED()"));
            assertFalse(bytes.body().contains("Other private"));
            assertFalse(bytes.body().contains("principal"));
            assertEquals(200, request(http, base, "/qqq/v1/table/carlTableExports/" + export, alice, null).statusCode());
            var otherHistory = request(http, base, "/qqq/v1/table/carlTableExports/" + export, bob, null);
            assertFalse(otherHistory.body().contains("source_table"), otherHistory.body());
            assertTrue(otherHistory.statusCode() >= 400 || JSON.readTree(otherHistory.body()).hasNonNull("error"), otherHistory.body());
            String freshSession = JWT.create().withJWTId(UUID.randomUUID().toString()).withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            assertTrue(request(http, base, downloadRoute, freshSession, null).statusCode() >= 400);

            assertTrue(request(http, base, downloadRoute, bob, null).statusCode() >= 400);
            var anonymous = http.send(HttpRequest.newBuilder(URI.create(base + downloadRoute)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(anonymous.statusCode() >= 400);
            denied(processResponse(http, base, bob, "carlDownloadTableExport", Map.of("exportId", export)));
            try(var c = data.getConnection(); var statement = c.createStatement())
            {
               statement.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
            }
            assertTrue(request(http, base, downloadRoute, alice, null).statusCode() >= 400);
            try(var c = data.getConnection(); var statement = c.createStatement())
            {
               statement.execute("UPDATE carl_permission SET details=true WHERE member_id=1 AND domain='VENDORS'");
            }
            assertTrue(request(http, base, downloadRoute, alice, null).statusCode() >= 400);
            denied(processResponse(http, base, alice, "carlDownloadTableExport", Map.of("exportId", export)));
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static void denied(HttpResponse<String> response) throws Exception
   {
      assertTrue(response.statusCode() >= 400 || JSON.readTree(response.body()).hasNonNull("error") || JSON.readTree(response.body()).path("type").asText().equals("ERROR"), response.body());
   }



   private static com.fasterxml.jackson.databind.JsonNode process(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      var response = processResponse(http, base, token, name, fields);
      assertEquals(200, response.statusCode(), response.body());
      var result = JSON.readTree(response.body());
      assertFalse(result.hasNonNull("error"), response.body());
      return result;
   }



   private static HttpResponse<String> processResponse(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      var started = request(http, base, "/qqq/v1/processes/" + name + "/init", token, Map.of());
      assertEquals(200, started.statusCode(), started.body());
      String id = JSON.readTree(started.body()).path("processUUID").asText();
      return request(http, base, "/qqq/v1/processes/" + name + "/" + id + "/step/input", token, fields);
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
         (route.endsWith("/init") ? fields : Map.of("values", JSON.writeValueAsString(fields))).forEach((key, value) -> body.append("--carl-report\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
         body.append("--carl-report--\r\n");
         request.header("Content-Type", "multipart/form-data; boundary=carl-report").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
