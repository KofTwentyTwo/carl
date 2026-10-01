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
import com.kof22.carlai.HomeMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Actual protected QQQ V1 profiles, staged local process, stale form and permission revocation. */
class CarlHomeNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeHomeTermsUseProtectedRecordIdentityAndCurrentPropertyRevision() throws Exception
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
            sql.execute("CREATE ROLE carl_home_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_home_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_home_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_home_view,carl_home_history_view,carl_rental_property_view,carl_account_view TO carl_home_reader");
         }
         long loan = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Fictional linked mortgage", "LOAN", "USD", false, java.math.BigDecimal.ONE, "FAMILY", "Fictional lender statement");
         long property = new com.kof22.carlai.domain.RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Fictional primary home", "FAMILY", "Supplied ownership and basis unknown", new com.kof22.carlai.domain.RentalRecords.PropertyValues("USD", "Fictional Meadow Borough", null, null, loan, null, null, null, null, null, null, null, null));
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_home_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new HomeMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of())); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var unknown = request(http, base, "/qqq/v1/table/carlHomes/" + property, alice, null);
            assertEquals(200, unknown.statusCode(), unknown.body());
            assertEquals("UNKNOWN", JSON.readTree(unknown.body()).path("record").path("values").path("property_use").asText());
            var start = request(http, base, "/processes/carlSaveHomeProfile/init", alice, Map.of());
            assertEquals(200, start.statusCode(), start.body());
            String id = JSON.readTree(start.body()).path("processUUID").asText();
            var loaded = request(http, base, "/processes/carlSaveHomeProfile/" + id + "/step/choose", alice, Map.of("property", Long.toString(property)));
            assertEquals(200, loaded.statusCode(), loaded.body());
            assertEquals(1, JSON.readTree(loaded.body()).path("values").path("expectedRevision").asInt());
            var saved = request(http, base, "/processes/carlSaveHomeProfile/" + id + "/step/review", alice, Map.ofEntries(Map.entry("propertyUse", "PRIMARY_RESIDENCE"), Map.entry("mortgageSupplied", "true"), Map.entry("asOf", "2026-09-30"), Map.entry("principal", "125000.00"), Map.entry("annualRate", "0.0425"), Map.entry("payment", "900.00"), Map.entry("firstPayment", "2025-01-10"), Map.entry("maturity", "2044-12-10"), Map.entry("amortizationMonths", "240"), Map.entry("rateKind", "FIXED"), Map.entry("assumptions", "Fictional supplied contract; escrow not resolved"), Map.entry("evidence", "Fictional homeowner contract review")));
            assertEquals(200, saved.statusCode(), saved.body());
            var record = request(http, base, "/qqq/v1/table/carlHomes/" + property, bob, null);
            assertEquals(200, record.statusCode(), record.body());
            var values = JSON.readTree(record.body()).path("record").path("values");
            assertEquals(property, values.path("id").asLong());
            assertEquals(2, values.path("revision").asInt());
            assertEquals("PRIMARY_RESIDENCE", values.path("property_use").asText());
            assertEquals(0, new java.math.BigDecimal("125000.00").compareTo(values.path("mortgage_principal").decimalValue()));
            assertEquals(0, new java.math.BigDecimal("0.0425").compareTo(values.path("annual_rate").decimalValue()));
            assertEquals("2026-09-30", values.path("mortgage_as_of").asText());
            assertEquals(loan, values.path("debt_account_id").asLong());
            assertTrue(values.path("escrow_amount").isNull());
            var homes = new com.kof22.carlai.domain.HomeRecords(service);
            long history = ((Number) homes.history("alice", property).getFirst().get("id")).longValue();
            var old = request(http, base, "/qqq/v1/table/carlHomeHistory/" + history, bob, null);
            assertEquals(200, old.statusCode(), old.body());
            assertEquals(1, JSON.readTree(old.body()).path("record").path("values").path("actor_id").asInt());
            var retry = request(http, base, "/processes/carlSaveHomeProfile/" + id + "/step/result", alice, Map.of());
            assertEquals(200, retry.statusCode(), retry.body());
            assertEquals(1, homes.history("alice", property).size());
            assertEquals(403, request(http, base, "/processes/carlSaveHomeProfile/" + id + "/step/result", bob, Map.of()).statusCode());
            var staleStart = request(http, base, "/processes/carlSaveHomeProfile/init", alice, Map.of());
            String stale = JSON.readTree(staleStart.body()).path("processUUID").asText();
            assertEquals(200, request(http, base, "/processes/carlSaveHomeProfile/" + stale + "/step/choose", alice, Map.of("property", Long.toString(property))).statusCode());
            homes.save("alice", UUID.randomUUID(), property, 2, new com.kof22.carlai.domain.HomeRecords.ProfileValues(com.kof22.carlai.domain.HomeRecords.Use.SECOND_HOME, "USD", null, "Fictional concurrent property review"));
            var staleResult = request(http, base, "/processes/carlSaveHomeProfile/" + stale + "/step/review", alice, Map.of("propertyUse", "RENTAL", "mortgageSupplied", "false", "evidence", "Fictional stale form"));
            assertEquals("Error message: Property changed; reload its current revision", JSON.readTree(staleResult.body()).path("error").asText());
            assertEquals("SECOND_HOME", homes.get("alice", property).get("property_use"));
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
            }
            assertEquals(404, request(http, base, "/qqq/v1/table/carlHomes/" + property, bob, null).statusCode());
            assertEquals(404, request(http, base, "/qqq/v1/table/carlHomeHistory/" + history, bob, null).statusCode());
            assertEquals(200, request(http, base, "/qqq/v1/table/carlHomeHistory/" + history, alice, null).statusCode());
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            var expiredReceipt = request(http, base, "/processes/carlSaveHomeProfile/" + id + "/step/result", alice, Map.of());
            assertEquals(403, expiredReceipt.statusCode(), expiredReceipt.body());
            assertFalse(expiredReceipt.body().contains("Fictional homeowner"));
            assertFalse(expiredReceipt.body().contains("125000"));

         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static HttpResponse<String> request(HttpClient http, String base, String route, String token, Map<String, String> fields) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + route)).timeout(java.time.Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic");
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
