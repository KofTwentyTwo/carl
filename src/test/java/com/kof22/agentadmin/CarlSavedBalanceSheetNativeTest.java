/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import java.math.BigDecimal;
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
import java.time.LocalDate;
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
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import com.kof22.carlai.domain.BalanceSheets;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DebtPlans;
import com.kof22.carlai.domain.FinancialRecords;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Synthetic PostgreSQL and actual verified native HTTP widgets; no external providers. */
class CarlSavedBalanceSheetNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   @Test
   void nativeChoicesAndWidgetPreserveDuplicateIdsAndDenyWrongKindsPrivateGuessesAndRevocation() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(data);
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic dashboard','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Reader',false)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE carl_dashboard_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_dashboard_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_dashboard_reader");
               }
            }
            try(var views = c.createStatement(); var rows = views.executeQuery("SELECT viewname FROM pg_views WHERE schemaname='public' AND viewname LIKE 'carl_%_view'"))
            {
               while(rows.next())
               {
                  sql.execute("GRANT SELECT ON " + rows.getString(1) + " TO carl_dashboard_reader");
               }
            }
         }
         var service = new CarlService(data, Clock.systemUTC());
         long account = new FinancialRecords(service).createAccount("alice", "Private synthetic account", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Human source");
         var finance = new FinancialRecords(service);
         finance.importBalances("alice", UUID.randomUUID(), "Date,Balance,Account\n2026-09-30,500.25,Synthetic\n", Map.of("Synthetic", account), false);
         long debt = new DebtPlans(service).compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), LocalDate.of(2026, 9, 1), "USD", new BigDecimal("100.00"), 12, "Declared fixture budget");
         var sheets = new BalanceSheets(service);
         long first = sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), LocalDate.of(2026, 9, 30), 30, List.of(account), List.of());
         long second = sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), LocalDate.of(2026, 9, 30), 31, List.of(account), List.of());
         assertEquals("FINANCIAL PLAN", service.artifact("alice", first).get("title"));
         assertEquals(service.artifact("alice", first).get("title"), service.artifact("alice", debt).get("title"));
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var keys = generator.generateKeyPair();
         var publicKey = (RSAPublicKey) keys.getPublic();
         var privateKey = (RSAPrivateKey) keys.getPrivate();
         var jwk = Jwk.fromValues(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())));
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
         try
         {
            var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> jwk);
            var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
            var components = AgentApplication.components();
            components.validate(configuration);
            var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_dashboard_reader", "synthetic-reader");
            var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
            var application = new AdminApplication(reader, components.metadata(), identity, runtime);
            try(var server = new AdminServer(application, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime)); var http = HttpClient.newHttpClient())
            {
               server.start();
               String base = "http://127.0.0.1:" + server.port();
               String alice = token(issuer, "alice", publicKey, privateKey);
               String bob = token(issuer, "bob", publicKey, privateKey);
               var choices = get(http, base, "/possibleValues/carlSavedBalanceSheets", alice);
               assertEquals(200, choices.statusCode(), choices.body());
               var options = JSON.readTree(choices.body()).path("options");
               assertEquals(2, options.size(), choices.body());
               assertEquals(java.util.Set.of(first, second), java.util.stream.StreamSupport.stream(options.spliterator(), false).map(option -> option.path("id").asLong()).collect(java.util.stream.Collectors.toSet()));
               assertTrue(choices.body().contains("#" + first));
               assertTrue(choices.body().contains("#" + second));
               assertTrue(choices.body().contains("2026-09-30"));
               var exact = get(http, base, "/possibleValues/carlSavedBalanceSheets?ids=" + second, alice);
               assertEquals(200, exact.statusCode(), exact.body());
               assertEquals(1, JSON.readTree(exact.body()).path("options").size());
               var wrong = get(http, base, "/possibleValues/carlSavedBalanceSheets?ids=" + debt, alice);
               assertEquals(200, wrong.statusCode(), wrong.body());
               assertEquals(0, JSON.readTree(wrong.body()).path("options").size());
               var search = get(http, base, "/possibleValues/carlSavedBalanceSheets?searchTerm=%23" + first, alice);
               assertEquals(1, JSON.readTree(search.body()).path("options").size(), search.body());
               var missing = get(http, base, "/widget/carlBalanceSheet", alice);
               assertEquals(200, missing.statusCode(), missing.body());
               assertTrue(missing.body().contains("Please select"));
               assertTrue(missing.body().contains("#" + first));
               assertTrue(missing.body().contains("#" + second), "Native widget must retain both distinct option labels");
               String query = "?carlSavedBalanceSheets=" + second;
               var selected = get(http, base, "/widget/carlBalanceSheet" + query, alice);
               assertEquals(200, selected.statusCode(), selected.body());
               assertTrue(selected.body().contains("500.25"), selected.body());
               var invalid = get(http, base, "/widget/carlBalanceSheet?carlSavedBalanceSheets=" + debt, alice);
               assertTrue(invalid.statusCode() >= 400, invalid.body());
               assertFalse(invalid.body().contains("500.25"));
               var privateOptions = get(http, base, "/possibleValues/carlSavedBalanceSheets?ids=" + second, bob);
               assertEquals(200, privateOptions.statusCode(), privateOptions.body());
               assertEquals(0, JSON.readTree(privateOptions.body()).path("options").size());
               var privateGuess = get(http, base, "/widget/carlBalanceSheet" + query, bob);
               assertTrue(privateGuess.statusCode() >= 400, privateGuess.body());
               assertFalse(privateGuess.body().contains("500.25"));
               var anonymous = get(http, base, "/possibleValues/carlSavedBalanceSheets", null);
               assertTrue(anonymous.statusCode() >= 400);
               finance.importBalances("alice", UUID.randomUUID(), "Date,Balance,Account\n2026-09-30,600.25,Synthetic\n", Map.of("Synthetic", account), true);
               var stale = get(http, base, "/possibleValues/carlSavedBalanceSheets?ids=" + second, alice);
               assertEquals(200, stale.statusCode(), stale.body());
               assertTrue(stale.body().contains("Stale sources"), stale.body());
               assertFalse(stale.body().contains("Current sources"));
               var history = get(http, base, "/widget/carlBalanceSheet" + query, alice);
               assertEquals(200, history.statusCode(), history.body());
               assertTrue(history.body().contains("500.25"), history.body());
               assertTrue(history.body().contains("Stale"), history.body());
               try(var c = data.getConnection(); var sql = c.createStatement())
               {
                  sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
               }
               for(String route : List.of("/widget/carlBalanceSheet" + query, "/possibleValues/carlSavedBalanceSheets?ids=" + second))
               {
                  var denied = get(http, base, route, alice);
                  assertTrue(denied.statusCode() >= 400, denied.body());
                  assertFalse(denied.body().contains("500.25"));
                  assertFalse(denied.body().contains("Balance sheet #"));
                  assertFalse(denied.body().contains("Private synthetic account"));
               }
            }
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static String token(String issuer, String principal, RSAPublicKey publicKey, RSAPrivateKey privateKey)
   {
      return JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject(principal).withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, privateKey));
   }



   private static String unsigned(byte[] value)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value);
   }



   private static HttpResponse<String> get(HttpClient http, String base, String path, String token) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + path));
      if(token != null)
      {
         request.header("Authorization", "Bearer " + token);
      }
      return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
   }
}
