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
import com.kof22.carlai.TaxPlanningMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real QQQ source review and conditional tax packet generation over PostgreSQL. */
class CarlTaxPlanningNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeTaxSourcesAlternativesAndPacketRetainQualificationLimits() throws Exception
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
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'TAX',true),(2,'TAX',true),(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE carl_tax_source_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_tax_source_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_tax_source_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_tax_reference_view,carl_tax_alternative_view,carl_tax_property_view,carl_artifact_view TO carl_tax_source_reader");
         }
         long property = new com.kof22.carlai.domain.RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Synthetic rental", "PRIVATE", "Supplied ownership and basis unknown", new com.kof22.carlai.domain.RentalRecords.PropertyValues("USD", "Chester, Randolph County, Illinois", null, null, null, null, null, null, null, null, null, null, null));
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_tax_source_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new TaxPlanningMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of())); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            assertTrue(metadata.body().contains("carlRecordTaxReference"));
            String recorded = process(http, base, alice, "carlRecordTaxReference", Map.ofEntries(Map.entry("title", "Synthetic reviewed IRS edition"), Map.entry("visibility", "PRIVATE"), Map.entry("jurisdiction", "US"), Map.entry("topic", "OWNERSHIP_STRUCTURE"), Map.entry("url", "https://www.irs.gov/publications/p527"), Map.entry("edition", "Synthetic edition fixture"), Map.entry("taxYear", "2025"), Map.entry("retrieved", "2026-09-01T00:00:00Z"), Map.entry("reviewed", "2026-09-01"), Map.entry("reviewUntil", "2026-10-01"), Map.entry("contentHash", "a".repeat(64)), Map.entry("reviewEvidence", "Synthetic applicability evidence, not qualified tax rules"), Map.entry("provenance", "Synthetic supplied source")));
            long reference = resultId(recorded, "Source record ");
            String candidate = process(http, base, alice, "carlRecordTaxAlternative", Map.of("title", "Synthetic alternative", "visibility", "PRIVATE", "property", Long.toString(property), "structure", "Human proposed ownership option", "assumptions", "No actual title or election assumed", "references", Long.toString(reference), "provenance", "Synthetic supplied evidence"));
            long alternative = resultId(candidate, "Conditional alternative ");
            String generated = process(http, base, alice, "carlSourceGroundedTaxPacket", Map.of("taxYear", "2025", "asOf", "2026-09-15T00:00:00Z", "properties", Long.toString(property), "alternatives", Long.toString(alternative)));
            long packet = resultId(generated, "packet ");
            var saved = request(http, base, "/data/carlArtifacts/" + packet, alice, null);
            assertEquals(200, saved.statusCode(), saved.body());
            assertTrue(saved.body().contains("UNDETERMINED"), saved.body());
            assertTrue(saved.body().contains("CONDITIONAL_DISCUSSION_ONLY"), saved.body());
            assertTrue(saved.body().contains("www.irs.gov"), saved.body());
            var hidden = request(http, base, "/data/carlTaxReferences/" + reference, bob, null);
            assertTrue(hidden.statusCode() >= 400, hidden.body());
            assertFalse(hidden.body().contains("Synthetic reviewed IRS edition"));
            String changed = process(http, base, alice, "carlTaxReferenceStatus", Map.of("reference", Long.toString(reference), "expectedRevision", "1", "active", "false", "reason", "Source review withdrawn"));
            String changeProcess = JSON.readTree(changed).path("processUUID").asText();
            var cachedChange = request(http, base, "/processes/carlTaxReferenceStatus/" + changeProcess + "/step/result", alice, Map.of());
            assertEquals(200, cachedChange.statusCode(), cachedChange.body());
            var receipt = JSON.readTree(cachedChange.body()).path("values");
            assertEquals("Source status updated with attribution. Old saved context is invalidated; generate a new packet.", receipt.path("result").asText());
            assertEquals("<div style=\"white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6\">" + receipt.path("result").asText() + "</div>", receipt.path("result.html").asText());
            var receiptKeys = java.util.Set.of("reference", "expectedRevision", "active", "reason", "requestId", "result", "result.html");
            receipt.fieldNames().forEachRemaining(name -> assertTrue(receiptKeys.contains(name), "Unexpected cached source field: " + name));
            assertFalse(cachedChange.body().contains("www.irs.gov"));
            assertFalse(cachedChange.body().contains("Synthetic reviewed IRS edition"));
            assertEquals(403, request(http, base, "/processes/carlTaxReferenceStatus/" + changeProcess + "/step/result", bob, Map.of()).statusCode());
            var revoked = request(http, base, "/data/carlArtifacts/" + packet, alice, null);
            assertTrue(revoked.statusCode() >= 400, revoked.body());
            assertFalse(revoked.body().contains("Human proposed ownership option"));
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static long resultId(String response, String prefix)
   {
      var matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(prefix) + "([0-9]+)").matcher(response);
      assertTrue(matcher.find(), response);
      return Long.parseLong(matcher.group(1));
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
