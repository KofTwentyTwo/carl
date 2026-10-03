/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.atomic.AtomicBoolean;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.AdminApplication;
import com.kof22.agentadmin.AdminServer;
import com.kof22.agentadmin.OperatorSessions;
import com.kof22.agentadmin.SyntheticDashboardScopeIdentity;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.domain.BudgetRecords;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FinancialRecords;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** A real native response must discard already-read facts when a record grant changes in the same request. */
class CarlDashboardScopeEpochNativeTest
{
   @Test
   void recordGrantRevocationAfterCashFactsCommitDeniesEntireNativeResponseWhileFinanceRemainsEnabled() throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(source);
         try(var connection = source.getConnection(); var sql = connection.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic scope race','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic Alice',true),(2,1,'bob','Synthetic Bob',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE scope_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO scope_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO scope_reader");
               }
            }
            try(var views = connection.createStatement(); var rows = views.executeQuery("SELECT viewname FROM pg_views WHERE schemaname='public' AND viewname LIKE 'carl_%_view'"))
            {
               while(rows.next())
               {
                  sql.execute("GRANT SELECT ON " + rows.getString(1) + " TO scope_reader");
               }
            }
         }
         var original = new CarlService(source, Clock.systemUTC());
         long account = new FinancialRecords(original).createAccount("bob", "Synthetic private source account", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Synthetic evidence");
         long transaction = new BudgetRecords(original).manualTransaction("bob", UUID.randomUUID(), account, LocalDate.of(2026, 9, 1), new BigDecimal("125.25"), "INCOME", "Synthetic private category", "Synthetic private movement", "Synthetic reviewed source");
         try(var connection = source.getConnection(); var sql = connection.createStatement())
         {
            sql.execute("INSERT INTO carl_grant(record_id,member_id,details) VALUES(" + account + ",1,true),(" + transaction + ",1,true)");
         }
         long before = original.member("alice").permissionRevision();
         var armed = new AtomicBoolean();
         var revoked = new AtomicBoolean();
         var wrapped = new AfterCashCommitSource(source, armed, () ->
         {
            try(var connection = source.getConnection(); var sql = connection.createStatement())
            {
               assertEquals(1, sql.executeUpdate("DELETE FROM carl_grant WHERE record_id=" + transaction + " AND member_id=1"));
               revoked.set(true);
            }
            catch(java.sql.SQLException failure)
            {
               throw new IllegalStateException(failure);
            }
         });
         var service = new CarlService(wrapped, Clock.systemUTC());
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var keys = generator.generateKeyPair();
         var publicKey = (RSAPublicKey) keys.getPublic();
         var privateKey = (RSAPrivateKey) keys.getPrivate();
         Map<String, Object> jwkValues = Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray()));
         var issuerServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort() + "/";
         byte[] jwks = new ObjectMapper().writeValueAsBytes(Map.of("keys", List.of(jwkValues)));
         issuerServer.createContext("/.well-known/jwks.json", exchange ->
         {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try(var output = exchange.getResponseBody())
            {
               output.write(jwks);
            }
         });
         issuerServer.start();
         try
         {
            var identity = SyntheticDashboardScopeIdentity.create(issuer, new RbacService(Map.of("alice", Role.OPERATOR)), Jwk.fromValues(jwkValues));
            var configuration = NativeAgentConfiguration.load(java.nio.file.Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
            var reader = NativeDatabases.backend("agentOperations", configuration.database(), "scope_reader", "synthetic-reader");
            var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
            var application = new AdminApplication(reader, List.of(new CarlMetadata(service), new ZCarlSystemNavigation()), identity, runtime);
            try(var server = new AdminServer(application, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime)); var http = HttpClient.newHttpClient())
            {
               server.start();
               String bearer = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, privateKey));
               String url = "http://127.0.0.1:" + server.port() + "/widget/carlCashFlow?from=2026-09-01&through=2026-09-30&carlDashboardCurrency=USD";
               var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + bearer).GET().build();
               var initial = http.send(request, HttpResponse.BodyHandlers.ofString());
               assertEquals(200, initial.statusCode(), initial.body());
               assertTrue(initial.body().contains("125.25 USD"), initial.body());
               assertTrue(initial.body().contains("Synthetic private category"), initial.body());
               armed.set(true);
               var changed = http.send(request, HttpResponse.BodyHandlers.ofString());
               assertTrue(revoked.get(), "Real grant deletion occurs after cash-flow facts commit, before rendering returns");
               assertTrue(original.member("alice").permissionRevision() > before);
               try(var connection = source.getConnection(); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT details FROM carl_permission WHERE member_id=1 AND domain='FINANCE'"))
               {
                  assertTrue(rows.next());
                  assertTrue(rows.getBoolean(1), "General finance access remains enabled; only the selected record grant was revoked");
               }
               assertEquals(403, changed.statusCode(), changed.body());
               assertFalse(changed.body().contains("125.25"), changed.body());
               assertFalse(changed.body().contains("Synthetic private category"), changed.body());
               var current = http.send(request, HttpResponse.BodyHandlers.ofString());
               assertEquals(200, current.statusCode(), current.body());
               assertFalse(current.body().contains("125.25"), current.body());
               assertFalse(current.body().contains("Synthetic private category"), current.body());
            }
         }
         finally
         {
            issuerServer.stop(0);
         }
      }
      finally
      {
         com.kingsrook.qqq.backend.core.context.QContext.clear();
         com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      }
   }



   private static String unsigned(byte[] value)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value);
   }

   /** Only the real completed cash-flow transaction triggers the fixture edit; no production callback is introduced. */
   private record AfterCashCommitSource(PGSimpleDataSource source, AtomicBoolean armed, Runnable afterCommit) implements javax.sql.DataSource
   {
      @Override
      public java.sql.Connection getConnection() throws java.sql.SQLException
      {
         var connection = source.getConnection();
         var cashQuery = new AtomicBoolean();
         return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) ->
         {
            try
            {
               Object result = method.invoke(connection, args);
               if(method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.startsWith("SELECT t.*") && sql.contains("FROM carl_transaction_view t JOIN carl_account_view ac"))
               {
                  cashQuery.set(true);
               }
               if(method.getName().equals("commit") && cashQuery.get() && armed.compareAndSet(true, false))
               {
                  afterCommit.run();
               }
               return result;
            }
            catch(java.lang.reflect.InvocationTargetException failure)
            {
               throw failure.getCause();
            }
         });
      }



      @Override
      public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException
      {
         return getConnection();
      }



      @Override
      public java.io.PrintWriter getLogWriter() throws java.sql.SQLException
      {
         return source.getLogWriter();
      }



      @Override
      public void setLogWriter(java.io.PrintWriter writer) throws java.sql.SQLException
      {
         source.setLogWriter(writer);
      }



      @Override
      public void setLoginTimeout(int seconds) throws java.sql.SQLException
      {
         source.setLoginTimeout(seconds);
      }



      @Override
      public int getLoginTimeout() throws java.sql.SQLException
      {
         return source.getLoginTimeout();
      }



      @Override
      public java.util.logging.Logger getParentLogger()
      {
         return java.util.logging.Logger.getLogger("synthetic-dashboard-epoch");
      }



      @Override
      public <T> T unwrap(Class<T> type) throws java.sql.SQLException
      {
         return source.unwrap(type);
      }



      @Override
      public boolean isWrapperFor(Class<?> type) throws java.sql.SQLException
      {
         return source.isWrapperFor(type);
      }
   }
}
