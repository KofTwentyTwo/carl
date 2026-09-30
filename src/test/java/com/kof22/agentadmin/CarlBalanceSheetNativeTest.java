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
import com.kof22.carlai.BalanceSheetMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real QQQ selected balance and conditional rental stress workflows over PostgreSQL. */
class CarlBalanceSheetNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   @Test
   void nativeSelectedBalanceAndRentalStressRemainScoped() throws Exception
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
            sql.execute("CREATE ROLE carl_balance_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_balance_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_balance_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_rental_shock_view,carl_rental_property_view,carl_account_view,carl_artifact_view,carl_balance_selection_view,carl_rental_baseline_selection_view,carl_budget_view TO carl_balance_reader");
         }
         var finance = new com.kof22.carlai.domain.FinancialRecords(service);
         long cash = finance.createAccount("alice", "Synthetic selected cash", "CASH", "USD", true, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic ownership");
         long privateCash = finance.createAccount("bob", "Private other-member account", "CASH", "USD", true, java.math.BigDecimal.ONE, "PRIVATE", "Never disclosed private evidence");
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(" + cash + ",'2026-09-30',1000,'CURRENT','Synthetic supplied balance')");
         }
         long property = new com.kof22.carlai.domain.RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Synthetic rental", "PRIVATE", "Human supplied property estimate", new com.kof22.carlai.domain.RentalRecords.PropertyValues("USD", "Chester, Randolph County, Illinois", java.math.BigDecimal.ONE, null, null, null, new java.math.BigDecimal("100000"), java.time.LocalDate.of(2026, 9, 30), null, null, null, null, null));
         long baseline = new com.kof22.carlai.domain.RentalRecords(service).report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), java.util.Set.of(property), java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30), java.time.LocalDate.of(2026, 9, 30));
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
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_balance_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new BalanceSheetMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), config ->
         {
            config.routes.beforeMatched(context ->
            {
               if("true".equals(context.queryParam("syntheticAdvanceEpoch")))
               {
                  try(var c = data.getConnection(); var sql = c.createStatement())
                  {
                     sql.execute("UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=1");
                  }
               }
            });
         }, new NativeDownloadPolicy(Map.of())); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            var metadata = request(http, base, "/metaData", alice, null);
            assertEquals(200, metadata.statusCode(), metadata.body());
            assertTrue(metadata.body().contains("carlConsolidatedBalanceSheet"));
            assertFalse(metadata.body().contains("Selected account IDs, comma-separated"));
            assertTrue(metadata.body().contains("carlRentalBaselines"));
            var missingSelection = request(http, base, "/processes/carlConsolidatedBalanceSheet/init", alice, Map.of());
            assertTrue(missingSelection.statusCode() >= 400 || missingSelection.body().contains("Select readable accounts"), missingSelection.body());
            var privateSelection = request(http, base, "/processes/carlConsolidatedBalanceSheet/init?recordsParam=recordIds&recordIds=" + privateCash, alice, Map.of());
            assertFalse(privateSelection.body().contains("Private other-member account"), privateSelection.body());
            assertFalse(privateSelection.body().contains("Never disclosed"));
            assertTrue(privateSelection.statusCode() >= 400 || !JSON.readTree(privateSelection.body()).path("nextStep").asText().equals("input"), privateSelection.body());
            var missingPolicy = processSelected(http, base, alice, "carlConsolidatedBalanceSheet", Long.toString(property), Map.of("asOf", "2026-09-30", "maximumAgeDays", "30"));
            assertTrue(missingPolicy.contains("Choose the valuation method"), missingPolicy);
            long sheet = resultId(processSelected(http, base, alice, "carlConsolidatedBalanceSheet", cash + "," + property + "," + privateCash, Map.of("asOf", "2026-09-30", "maximumAgeDays", "30", "propertyPolicy", "PROPERTY_ESTIMATE")), "balance sheet ");
            var saved = request(http, base, "/data/carlArtifacts/" + sheet, alice, null);
            assertEquals(200, saved.statusCode(), saved.body());
            assertTrue(saved.body().contains("100000"));
            assertTrue(saved.body().contains("101000"));
            assertFalse(saved.body().contains("Private other-member account"));
            assertTrue(saved.body().contains("PROPERTY_ESTIMATE"));
            long scenario = resultId(process(http, base, alice, "carlRecordRentalStress", Map.ofEntries(Map.entry("title", "Synthetic rental stress"), Map.entry("visibility", "PRIVATE"), Map.entry("baselineReport", Long.toString(baseline)), Map.entry("property", Long.toString(property)), Map.entry("expectedRent", "2000"), Map.entry("vacancyFraction", "0.5"), Map.entry("repair", "250"), Map.entry("additionalAnnualRate", "0"), Map.entry("allocatedPrincipal", "0"), Map.entry("balanceAsOf", "2026-09-30"), Map.entry("evidence", "Synthetic vacancy and repair replay assumptions"))), "assumptions ");
            long report = resultId(process(http, base, alice, "carlRentalStressReport", Map.of("scenario", Long.toString(scenario))), "stress report ");
            var replay = request(http, base, "/data/carlArtifacts/" + report, alice, null);
            assertEquals(200, replay.statusCode(), replay.body());
            assertTrue(replay.body().contains("1250"));
            assertTrue(replay.body().contains("CONDITIONAL_HYPOTHETICAL_REPLAY"));
            var hidden = request(http, base, "/data/carlRentalStress/" + scenario, bob, null);
            assertTrue(hidden.statusCode() >= 400, hidden.body());
            assertFalse(hidden.body().contains("Synthetic vacancy"));
            long budget = new com.kof22.carlai.domain.BudgetRecords(service).create("alice", UUID.randomUUID(), "Private budget", "PRIVATE", "Private medical category", java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30), new java.math.BigDecimal("9876.54"), "USD", "Synthetic private budget evidence");
            var correctedBudget = process(http, base, alice, "carlCorrectBudget", Map.of("budget", Long.toString(budget), "expectedRevision", "1", "amount", "9876.54", "reason", "Human reviewed synthetic amount"));
            assertTrue(correctedBudget.contains("Budget correction saved with attribution"), correctedBudget);
            var budgetResponse = process(http, base, alice, "carlBudgetVariance", Map.of("budget", Long.toString(budget)));
            assertTrue(budgetResponse.contains("9876.54"), budgetResponse);
            String budgetProcess = JSON.readTree(budgetResponse).path("processUUID").asText();
            var started = request(http, base, "/processes/carlConsolidatedBalanceSheet/init?recordsParam=recordIds&recordIds=" + cash + "," + property, alice, Map.of());
            assertEquals(200, started.statusCode(), started.body());
            String startedId = JSON.readTree(started.body()).path("processUUID").asText();
            var preview = request(http, base, "/processes/carlConsolidatedBalanceSheet/" + startedId + "/records?skip=0&limit=20", alice, null);
            assertEquals(200, preview.statusCode(), preview.body());
            var otherOwner = request(http, base, "/processes/carlConsolidatedBalanceSheet/" + startedId + "/records?skip=0&limit=20", bob, null);
            assertEquals(403, otherOwner.statusCode(), otherOwner.body());
            assertFalse(otherOwner.body().contains("Synthetic selected cash"));
            assertTrue(preview.body().contains("Synthetic selected cash"), preview.body());
            assertTrue(preview.body().contains("Synthetic rental"), preview.body());
            assertFalse(preview.body().contains("Private other-member account"));
            var selectedIds = new java.util.ArrayList<String>();
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("WITH added AS (INSERT INTO carl_record(household_id,owner_id,domain,visibility,title,evidence) SELECT 1,1,'FINANCE','PRIVATE','Bounded synthetic account '||i,'Synthetic ownership' FROM generate_series(1,201) i RETURNING id) INSERT INTO carl_account(record_id,kind,currency,liquid,ownership_share) SELECT id,'CASH','USD',true,1 FROM added RETURNING record_id"))
            {
               while(rows.next())
               {
                  selectedIds.add(rows.getString(1));
               }
            }
            var oversized = request(http, base, "/processes/carlConsolidatedBalanceSheet/init?recordsParam=recordIds&recordIds=" + String.join(",", selectedIds), alice, Map.of());
            assertTrue(oversized.statusCode() >= 400 || !JSON.readTree(oversized.body()).path("nextStep").asText().equals("input"), oversized.body());
            long artifactCount;
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_artifact"))
            {
               rows.next();
               artifactCount = rows.getLong(1);
            }
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
            }
            var cachedBudget = request(http, base, "/processes/carlBudgetVariance/" + budgetProcess + "/step/result", alice, Map.of());
            assertEquals(403, cachedBudget.statusCode(), cachedBudget.body());
            assertFalse(cachedBudget.body().contains("9876.54"), cachedBudget.body());
            var revokedPreview = request(http, base, "/processes/carlConsolidatedBalanceSheet/" + startedId + "/records?skip=0&limit=20", alice, null);
            assertEquals(403, revokedPreview.statusCode(), revokedPreview.body());
            assertFalse(revokedPreview.body().contains("Synthetic selected cash"), revokedPreview.body());
            var deniedExecution = request(http, base, "/processes/carlConsolidatedBalanceSheet/" + startedId + "/step/input", alice, Map.of("asOf", "2026-09-30", "maximumAgeDays", "30", "propertyPolicy", "PROPERTY_ESTIMATE"));
            assertEquals(403, deniedExecution.statusCode(), deniedExecution.body());
            assertFalse(deniedExecution.body().contains("Saved selected balance sheet"), deniedExecution.body());
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM carl_artifact"))
            {
               rows.next();
               assertEquals(artifactCount, rows.getLong(1));
            }
            var revoked = request(http, base, "/data/carlArtifacts/" + report, alice, null);
            assertTrue(revoked.statusCode() >= 400, revoked.body());
            assertFalse(revoked.body().contains("Synthetic vacancy"));
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=true WHERE member_id=1 AND domain='FINANCE'");
            }
            var stillExpired = request(http, base, "/processes/carlConsolidatedBalanceSheet/" + startedId + "/records?skip=0&limit=20", alice, null);
            assertEquals(403, stillExpired.statusCode());
            var changedDuringInit = request(http, base, "/processes/carlConsolidatedBalanceSheet/init?recordsParam=recordIds&recordIds=" + cash + "&syntheticAdvanceEpoch=true", alice, Map.of());
            assertEquals(403, changedDuringInit.statusCode(), changedDuringInit.body());
            assertFalse(changedDuringInit.body().contains("processUUID"));
            var expiredStatus = request(http, base, "/processes/carlConsolidatedBalanceSheet/" + startedId + "/status/" + UUID.randomUUID(), alice, null);
            assertEquals(403, expiredStatus.statusCode());
            var freshSelection = request(http, base, "/processes/carlConsolidatedBalanceSheet/init?recordsParam=recordIds&recordIds=" + cash, alice, Map.of());
            assertEquals(200, freshSelection.statusCode(), freshSelection.body());
            assertEquals("input", JSON.readTree(freshSelection.body()).path("nextStep").asText());
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
      return processSelected(http, base, token, name, "", fields);
   }



   private static String processSelected(HttpClient http, String base, String token, String name, String selected, Map<String, String> fields) throws Exception
   {
      var start = request(http, base, "/processes/" + name + "/init" + (selected.isBlank() ? "" : "?recordsParam=recordIds&recordIds=" + selected), token, Map.of());
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
