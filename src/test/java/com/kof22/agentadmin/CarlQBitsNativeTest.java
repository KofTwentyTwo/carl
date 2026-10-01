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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;


/** Actual native HTTP, PostgreSQL, OpenSearch and controlled mid-response permission revocation. */
class CarlQBitsNativeTest
{
   @Test
   void actualConsumerSearchIndexesCurrentRecordsAndDiscardsAnAssembledResponseOnRevocation() throws Exception
   {
      QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine");
         var search = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.19.1"))
            .withEnv("discovery.type", "single-node").withEnv("DISABLE_SECURITY_PLUGIN", "true").withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200);
         var proxyClient = HttpClient.newHttpClient())
      {
         database.start();
         search.start();
         var source = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(source);
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic index home','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
         }
         var domain = new CarlService(source, Clock.systemUTC());
         domain.importBills("alice", UUID.randomUUID(), "Synthetic needle invoices", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Needle utility,Needle bill,125.25,USD,2026-09-30,UNPAID,PRIVATE\n");
         domain.createVendor("alice", "Needle vendor", "Maintenance", null, false, "PRIVATE", "Synthetic supplied evidence");
         var armed = new AtomicBoolean();
         var searches = new AtomicInteger();
         var proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         String target = "http://" + search.getHost() + ":" + search.getMappedPort(9200);
         proxy.createContext("/", exchange ->
         {
            try
            {
               if(armed.get() && exchange.getRequestURI().getPath().endsWith("/_search") && searches.incrementAndGet() == 2)
               {
                  try(var c = source.getConnection(); var sql = c.createStatement())
                  {
                     sql.execute("DELETE FROM carl_permission WHERE member_id=1 AND domain='BILLS'");
                  }
               }
               var forwarded = HttpRequest.newBuilder(URI.create(target + exchange.getRequestURI())).timeout(java.time.Duration.ofSeconds(10))
                  .header("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type") == null ? "application/json" : exchange.getRequestHeaders().getFirst("Content-Type"))
                  .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
               var response = proxyClient.send(forwarded, HttpResponse.BodyHandlers.ofByteArray());
               exchange.getResponseHeaders().set("Content-Type", "application/json");
               exchange.sendResponseHeaders(response.statusCode(), response.body().length);
               try(var out = exchange.getResponseBody())
               {
                  out.write(response.body());
               }
            }
            catch(Exception unavailable)
            {
               exchange.sendResponseHeaders(503, -1);
               exchange.close();
            }
         });
         proxy.start();
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var pair = generator.generateKeyPair();
         var key = (RSAPublicKey) pair.getPublic();
         var jwk = Jwk.fromValues(Map.of("kty", "RSA", "kid", "synthetic", "alg", "RS256", "n", unsigned(key.getModulus().toByteArray()), "e", unsigned(key.getPublicExponent().toByteArray())));
         var keys = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         String issuer = "http://127.0.0.1:" + keys.getAddress().getPort() + "/";
         byte[] jwks = new ObjectMapper().writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "synthetic", "alg", "RS256", "n", unsigned(key.getModulus().toByteArray()), "e", unsigned(key.getPublicExponent().toByteArray())))));
         keys.createContext("/.well-known/jwks.json", exchange ->
         {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try(var out = exchange.getResponseBody())
            {
               out.write(jwks);
            }
         });
         keys.start();
         try
         {
            var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
            var components = AgentApplication.components((data, service) -> new com.kof22.carlai.domain.CalendarWorkflows(Map.of()), Map.of("CARL_QBITS_SEARCH_ENABLED", "true", "CARL_QBITS_OPENSEARCH_HOST", "127.0.0.1", "CARL_QBITS_OPENSEARCH_PORT", Integer.toString(proxy.getAddress().getPort()), "CARL_QBITS_OPENSEARCH_INDEX", "carl-synthetic-test", "CARL_QBITS_OPENSEARCH_SSL", "false"));
            components.validate(configuration);
            var qbits = components.administrationExtensions(null);
            var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> jwk);
            var backend = NativeDatabases.backend("agentOperations", configuration.database(), database.getUsername(), database.getPassword());
            var sessions = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
            var producers = new java.util.ArrayList<com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface<?>>(components.metadata());
            producers.add(qbits);
            var application = new AdminApplication(backend, producers, identity, sessions);
            try(var server = new AdminServer(application, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(sessions), qbits::configure).withQBits(qbits); var http = HttpClient.newHttpClient())
            {
               server.start();
               String alice = JWT.create().withKeyId("synthetic").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(key, (RSAPrivateKey) pair.getPrivate()));
               String bob = JWT.create().withKeyId("synthetic").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(key, (RSAPrivateKey) pair.getPrivate()));
               String url = "http://127.0.0.1:" + server.port() + "/qqq/v1/search";
               HttpResponse<String> success = null;
               long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
               do
               {
                  success = search(http, url, alice);
                  if(success.body().contains("Needle bill") && success.body().contains("Needle vendor"))
                  {
                     break;
                  }
                  Thread.sleep(100);
               }
               while(System.nanoTime() < deadline);
               assertThat(success.statusCode()).as(success.body()).isEqualTo(200);
               assertThat(success.body()).contains("Needle bill", "Needle vendor");
               assertThat(search(http, url, bob).body()).doesNotContain("Needle bill", "Needle vendor");
               armed.set(true);
               var revoked = search(http, url, alice);
               assertThat(searches.get()).isGreaterThanOrEqualTo(2);
               assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(403);
               assertThat(revoked.body()).contains("Carl access changed during this request").doesNotContain("Needle bill", "Needle vendor", "recordLabel", "highlightSnippet");
            }
         }
         finally
         {
            keys.stop(0);
            proxy.stop(0);
         }
      }
      finally
      {
         QContext.clear();
         com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      }
   }



   private static HttpResponse<String> search(HttpClient http, String url, String token) throws Exception
   {
      return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(25)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic").header("Content-Type", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString("{\"searchTerm\":\"Needle\",\"tableNames\":[\"carlBills\",\"carlVendors\"],\"limitPerTable\":25}")).build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
   }
}
