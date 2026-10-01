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
import com.kof22.carlai.RentalReviewMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real QQQ saved allocation review and confirmed classification over PostgreSQL. */
class CarlRentalReviewNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeSavedReviewSharesPreviewAndApplyUseCurrentPermissions() throws Exception
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
            sql.execute("CREATE ROLE carl_rental_review_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_rental_review_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_rental_review_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_transaction_view,carl_account_view,carl_rental_property_view,carl_rental_review_view,carl_rental_review_component_view,carl_rental_review_share_view,carl_rental_source_view TO carl_rental_review_reader");
         }
         var rentals = new com.kof22.carlai.domain.RentalRecords(service);
         var propertyFacts = new com.kof22.carlai.domain.RentalRecords.PropertyValues("USD", "Synthetic Chester", java.math.BigDecimal.ONE, null, null, null, null, null, null, null, null, null, null);
         long first = rentals.createProperty("alice", UUID.randomUUID(), "Synthetic house A", "FAMILY", "Synthetic deed", propertyFacts);
         long second = rentals.createProperty("alice", UUID.randomUUID(), "Synthetic house B", "PRIVATE", "Synthetic deed", propertyFacts);
         var finances = new com.kof22.carlai.domain.FinancialRecords(service);
         long account = finances.createAccount("alice", "Synthetic checking", "CASH", "USD", true, java.math.BigDecimal.ONE, "FAMILY", "Synthetic statement");
         finances.importTransactions("alice", UUID.randomUUID(), "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-10,Synthetic,Repair,Checking,Synthetic,Invoice,-101.01,,,,repair-1\n", Map.of("Checking", account), false);
         long transaction = ((Number) service.view(CarlService.Scope.privateFor("alice"), "transactions").getFirst().get("id")).longValue();
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_rental_review_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new RentalReviewMetadataFixture(service)), identity, runtime);
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
            assertTrue(metadata.body().contains("carlStartRentalReview"));
            assertTrue(process(http, base, alice, "carlStartRentalReview", Map.of("transaction", "" + transaction, "title", "Synthetic repair review", "visibility", "FAMILY", "evidence", "Synthetic invoice")).contains("No transaction was changed"));
            long review = scalar(data, "SELECT record_id FROM carl_rental_review");
            process(http, base, alice, "carlEditRentalReviewComponent", Map.of("review", "" + review, "expectedVersion", "1", "component", "repair", "kind", "OPERATING_EXPENSE", "amount", "101.01", "outsideFraction", "0", "evidence", "Synthetic component"));
            long component = scalar(data, "SELECT id FROM carl_rental_review_component");
            assertTrue(process(http, base, alice, "carlPreviewRentalReview", Map.of("review", "" + review)).contains("Needs attention"));
            process(http, base, alice, "carlEditRentalReviewShare", Map.of("review", "" + review, "expectedVersion", "2", "component", "" + component, "property", "" + first, "fraction", "0.5", "evidence", "Evidenced equal split"));
            process(http, base, alice, "carlEditRentalReviewShare", Map.of("review", "" + review, "expectedVersion", "3", "component", "" + component, "property", "" + second, "fraction", "0.5", "evidence", "Evidenced equal split"));
            String preview = process(http, base, alice, "carlPreviewRentalReview", Map.of("review", "" + review));
            assertTrue(preview.contains("Ready for explicit application"), preview);
            assertTrue(preview.contains("50.51") && preview.contains("50.50"), preview);
            var denied = process(http, base, bob, "carlPreviewRentalReview", Map.of("review", "" + review));
            assertFalse(denied.contains("101.01"), denied);
            assertTrue(denied.contains("unavailable"), denied);
            assertFalse(request(http, base, "/data/carlRentalReviewShares", bob, null).body().contains("Synthetic house B"));
            String wrongActor = process(http, base, bob, "carlApplyRentalReview", Map.of("review", "" + review, "expectedVersion", "4", "evidence", "Unpermitted guessed review"));
            assertFalse(wrongActor.contains("Confirmed classification"), wrongActor);
            assertTrue(wrongActor.contains("unavailable"), wrongActor);
            assertEquals(0, scalar(data, "SELECT count(*) FROM carl_rental_split_version"));
            assertTrue(process(http, base, alice, "carlApplyRentalReview", Map.of("review", "" + review, "expectedVersion", "4", "evidence", "Confirmed invoice split")).contains("Confirmed classification"));
            assertEquals(1, scalar(data, "SELECT count(*) FROM carl_rental_split_version"));
            assertEquals(2, scalar(data, "SELECT count(*) FROM carl_rental_allocation"));
            process(http, base, alice, "carlStartRentalReview", Map.of("transaction", "" + transaction, "title", "Separate synthetic review", "visibility", "PRIVATE", "evidence", "Synthetic removal review"));
            long secondReview = scalar(data, "SELECT max(record_id) FROM carl_rental_review");
            process(http, base, alice, "carlEditRentalReviewComponent", Map.of("review", "" + secondReview, "expectedVersion", "1", "component", "temporary", "kind", "OPERATING_EXPENSE", "amount", "101.01", "outsideFraction", "0", "evidence", "Temporary human component"));
            long temporary = scalar(data, "SELECT id FROM carl_rental_review_component WHERE review_id=" + secondReview);
            process(http, base, alice, "carlRemoveRentalReviewComponent", Map.of("review", "" + secondReview, "expectedVersion", "2", "component", "" + temporary, "evidence", "Human removal reason"));
            assertEquals(0, scalar(data, "SELECT count(*) FROM carl_rental_review_component WHERE review_id=" + secondReview));
            assertTrue(service.view(CarlService.Scope.privateFor("bob"), "rentalSources").isEmpty());
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            var revoked = process(http, base, alice, "carlPreviewRentalReview", Map.of("review", "" + review));
            assertFalse(revoked.contains("101.01"), revoked);
            assertTrue(revoked.contains("unavailable"), revoked);

         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static long scalar(javax.sql.DataSource source, String sql) throws Exception
   {
      try(var c = source.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery(sql))
      {
         rows.next();
         return rows.getLong(1);
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
      var receipts = Map.of(
         "carlStartRentalReview", "Saved review [0-9]+, version 1\\. Add components and property shares, preview, then explicitly apply\\. No transaction was changed\\.",
         "carlEditRentalReviewComponent", "Draft component saved at review version [0-9]+\\. Source classification unchanged\\.",
         "carlEditRentalReviewShare", "Property share saved at review version [0-9]+\\. Fractions cannot exceed 100%; incomplete shares remain a draft\\.",
         "carlRemoveRentalReviewComponent", "Draft component and its shares removed with attribution; review version [0-9]+\\. Imported evidence retained\\.",
         "carlApplyRentalReview", "Confirmed classification [0-9]+ saved\\. Prior classification versions and original transaction evidence remain available\\. No payment or external tax action occurred\\.");
      if(receipts.containsKey(name) && JSON.readTree(result.body()).path("values").has("result"))
      {
         var cached = request(http, base, "/processes/" + name + "/" + id + "/step/result", token, Map.of());
         assertEquals(200, cached.statusCode(), cached.body());
         var values = JSON.readTree(cached.body()).path("values");
         assertTrue(values.path("result").asText().matches(receipts.get(name)), cached.body());
         assertEquals("<div style=\"white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6\">" + values.path("result").asText() + "</div>", values.path("result.html").asText());
         var keys = new java.util.HashSet<String>(fields.keySet());
         keys.addAll(List.of("requestId", "result", "result.html"));
         values.fieldNames().forEachRemaining(key -> assertTrue(keys.contains(key), "Unexpected cached source field: " + key));
         assertFalse(cached.body().contains("Synthetic house B"));
         assertFalse(cached.body().contains("50.51"));
      }
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
         fields.forEach((key, value) -> body.append("--carl-rental\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
         body.append("--carl-rental--\r\n");
         request.header("Content-Type", "multipart/form-data; boundary=carl-rental").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
