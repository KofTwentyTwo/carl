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
import com.kof22.carlai.domain.BudgetRecords;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DomainPreferences;
import com.kof22.carlai.domain.FinancialRecords;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Synthetic PostgreSQL and actual verified native HTTP widgets; no external providers. */
class CarlDashboardNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   @Test
   void authenticatedNativeWidgetsRenderRealExactFactsAndDenyPrivateGuessesAndRevokedCallers() throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(data);
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic dashboard','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Reader',false),(3,1,'admin-owner','Synthetic admin owner',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(1,'SETTINGS',true),(2,'SETTINGS',true),(3,'SETTINGS',true)");
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
         new BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), account, LocalDate.of(2026, 9, 1), new BigDecimal("125.25"), "INCOME", "<img src=x onerror=alert(1)>", "Synthetic salary", "Human classification");
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
            var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR, "admin-owner", Role.ADMIN, "unmapped-admin", Role.ADMIN)), ignored -> jwk);
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
               var metadata = get(http, base, "/metaData", alice);
               assertEquals(200, metadata.statusCode(), metadata.body());
               assertTrue(metadata.body().contains("Carl AI"));
               assertTrue(metadata.body().contains("carlMoney"));
               assertTrue(metadata.body().contains("carlCashFlow"));
               String adminOwner = token(issuer, "admin-owner", publicKey, privateKey);
               var storage = get(http, base, "/widget/carlDatabaseDiagnostics", adminOwner);
               assertEquals(200, storage.statusCode(), storage.body());
               assertTrue(storage.body().contains("Carl PostgreSQL"), storage.body());
               assertTrue(storage.body().contains("carl_household"), storage.body());
               assertTrue(storage.body().contains("Table bytes"), storage.body());
               assertFalse(storage.body().contains(database.getJdbcUrl()));
               assertFalse(storage.body().contains("synthetic-reader"));
               assertTrue(metadata.body().contains("carlSystem"));
               for(String deniedToken : List.of(alice, bob, token(issuer, "unmapped-admin", publicKey, privateKey)))
               {
                  var deniedStorage = get(http, base, "/widget/carlDatabaseDiagnostics", deniedToken);
                  assertTrue(deniedStorage.statusCode() >= 400, deniedStorage.body());
                  assertFalse(deniedStorage.body().contains("carl_household"), deniedStorage.body());
                  assertFalse(deniedStorage.body().contains("Table bytes"), deniedStorage.body());
               }
               try(var c = data.getConnection(); var sql = c.createStatement())
               {
                  sql.execute("UPDATE carl_permission SET details=false WHERE member_id=3 AND domain='SETTINGS'");
               }
               var revokedStorage = get(http, base, "/widget/carlDatabaseDiagnostics", adminOwner);
               assertTrue(revokedStorage.statusCode() >= 400, revokedStorage.body());
               assertFalse(revokedStorage.body().contains("carl_household"), revokedStorage.body());
               assertFalse(revokedStorage.body().contains("Table bytes"), revokedStorage.body());
               var missing = get(http, base, "/widget/carlCashFlow", alice);
               assertEquals(200, missing.statusCode(), missing.body());
               assertTrue(missing.body().contains("Please select"), missing.body());
               assertFalse(missing.body().contains("125.25"));
               var preferences = new DomainPreferences(service);
               preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_FROM", "2026-09-01", "Synthetic selected reporting period");
               preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_THROUGH", "2026-09-30", "Synthetic selected reporting period");
               preferences.set("alice", UUID.randomUUID(), "MEMBER", "DASHBOARD_CURRENCY", "USD", "Synthetic selected presentation currency");
               var savedDefaults = get(http, base, "/widget/carlCashFlow", alice);
               assertEquals(200, savedDefaults.statusCode(), savedDefaults.body());
               assertTrue(savedDefaults.body().contains("125.25 USD"), savedDefaults.body());
               assertFalse(savedDefaults.body().contains("Please select"), savedDefaults.body());
               assertTrue(savedDefaults.body().contains("2026-09-01"), savedDefaults.body());
               var otherDefaults = get(http, base, "/widget/carlCashFlow", bob);
               assertTrue(otherDefaults.body().contains("Please select"), otherDefaults.body());
               String query = "?from=2026-09-01&through=2026-09-30&carlDashboardCurrency=USD";
               var cash = get(http, base, "/widget/carlCashFlow" + query, alice);
               assertEquals(200, cash.statusCode(), cash.body());
               assertTrue(cash.body().contains("125.25 USD"), cash.body());
               assertTrue(cash.body().contains("&lt;img"), cash.body());
               assertFalse(cash.body().contains("<img"), cash.body());
               var sankey = get(http, base, "/widget/carlIncomeExpense" + query, alice);
               assertEquals(200, sankey.statusCode(), sankey.body());
               assertTrue(sankey.body().contains("<svg"));
               assertTrue(sankey.body().contains("125.25 USD"));
               var privateGuess = get(http, base, "/widget/carlCashFlow" + query, bob);
               assertEquals(200, privateGuess.statusCode(), privateGuess.body());
               assertFalse(privateGuess.body().contains("125.25"));
               assertFalse(privateGuess.body().contains("onerror"));
               var invalid = get(http, base, "/widget/carlCashFlow?from=2026-09-01&through=2026-09-30&carlDashboardCurrency=NONE", alice);
               assertTrue(invalid.statusCode() >= 400, invalid.body());
               assertFalse(invalid.body().contains("125.25"));
               var anonymous = get(http, base, "/widget/carlCashFlow" + query, null);
               assertTrue(anonymous.statusCode() >= 400);
               try(var c = data.getConnection(); var sql = c.createStatement())
               {
                  sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
               }
               for(String widget : List.of("carlCashFlow", "carlIncomeExpense", "carlBalanceSheet", "carlPlanProgress"))
               {
                  var denied = get(http, base, "/widget/" + widget + query, alice);
                  assertTrue(denied.statusCode() >= 400, denied.body());
                  assertFalse(denied.body().contains("125.25"));
                  assertFalse(denied.body().contains("Private synthetic account"));
               }
            }
         }
         finally
         {
            keyServer.stop(0);
         }
      }
      finally
      {
         com.kingsrook.qqq.backend.core.context.QContext.clear();
         com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
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
