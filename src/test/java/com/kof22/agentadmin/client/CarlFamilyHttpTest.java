/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin.client;


import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.policy.DataProtection;
import com.kof22.agentcore.session.SessionManager;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import com.kof22.carlai.domain.CarlService;
import io.javalin.Javalin;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real family HTTP identity and explicit workflows over the production consumer factory and PostgreSQL. */
class CarlFamilyHttpTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String ISSUER = "https://synthetic.identity.example/";

   @Test
   void familyConversationRequestsPersistSourceGroundedReportsAndDraftsWithCurrentAccess() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(data);
         try(var connection = data.getConnection(); var statement = connection.createStatement())
         {
            statement.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic HTTP family','America/Chicago')");
            statement.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',true)");
            statement.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            statement.execute("INSERT INTO carl_identity(issuer,subject,member_id) VALUES('" + ISSUER + "','alice-subject',1),('" + ISSUER + "','bob-subject',2)");
         }
         var service = new CarlService(data, Clock.systemUTC());
         service.importBills("alice", UUID.randomUUID(), "Synthetic HTTP bills", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic utility,Power,125.25,USD,2026-09-30,UNPAID,PRIVATE\nb,Synthetic utility,Gas,74.75,USD,2026-09-30,UNPAID,PRIVATE\n");
         long vendor = service.createVendor("alice", "Synthetic repair vendor", "Maintenance", "vendor@example.invalid", true, "PRIVATE", "Supplied synthetic contact");
         long work = service.createWorkItem("alice", vendor, "Review repair question", "VENDOR_RESPONSE", LocalDate.of(2026, 9, 30), "A question was supplied; no price or commitment exists", "PRIVATE");
         var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
         var components = AgentApplication.components();
         components.validate(configuration);
         var access = components.familyAccess();
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var pair = generator.generateKeyPair();
         var key = (RSAPublicKey) pair.getPublic();
         var algorithm = Algorithm.RSA256(key, (RSAPrivateKey) pair.getPrivate());
         var jwk = Jwk.fromValues(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(key.getModulus().toByteArray()), "e", unsigned(key.getPublicExponent().toByteArray())));
         var identity = new ClientIdentity(ISSUER, "carl-family", access, ignored -> jwk);
         var sessions = org.mockito.Mockito.mock(SessionManager.class);
         var store = new ClientStore(data);
         try(var clients = new ClientService(store, sessions, identity, access, DataProtection.defaults(), Duration.ofSeconds(10), components.clientWorkflows(null)); var http = HttpClient.newHttpClient())
         {
            int port;
            try(var socket = new ServerSocket(0))
            {
               port = socket.getLocalPort();
            }
            String origin = "http://127.0.0.1:" + port;
            var servlet = new ClientServlet(identity, store, clients, origin, () -> true);
            var server = Javalin.create(config -> config.jetty.modifyServletContextHandler(handler -> handler.addServlet(new ServletHolder(servlet), "/api/agent/v1/*"))).start("127.0.0.1", port);
            try
            {
               String base = origin + "/api/agent/v1";
               String alice = token(algorithm, "alice-subject");
               String bob = token(algorithm, "bob-subject");
               String conversation = "/conversations/" + UUID.randomUUID();
               assertEquals(401, send(http, base + "/me", "GET", "invalid", null).statusCode());
               assertEquals(200, send(http, base + conversation, "PUT", alice, "{}").statusCode());
               assertEquals(404, send(http, base + conversation, "GET", bob, null).statusCode());
               String reportPath = base + conversation + "/workflows/report/" + UUID.randomUUID();
               String reportInput = "{\"input\":{\"from\":\"2026-09-01\",\"through\":\"2026-09-30\"}}";
               assertEquals(200, send(http, reportPath, "PUT", alice, reportInput).statusCode());
               var report = await(http, reportPath, alice);
               assertTrue(List.of("COMPLETE", "PARTIAL").contains(report.path("status").asText()), report.toString());
               long reportId = Long.parseLong(report.path("artifactId").asText());
               String facts = service.artifact("alice", reportId).get("facts").toString();
               assertTrue(facts.contains("200.00"), facts);
               assertEquals(report, JSON.readTree(send(http, reportPath, "PUT", alice, reportInput).body()));
               String draftPath = base + conversation + "/workflows/draft/" + UUID.randomUUID();
               assertEquals(200, send(http, draftPath, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("workId", work, "purpose", "FOLLOW_UP")))).statusCode());
               var draft = await(http, draftPath, alice);
               assertEquals("COMPLETE", draft.path("status").asText(), draft.toString());
               long draftId = Long.parseLong(draft.path("artifactId").asText());
               var savedDraft = service.artifact("alice", draftId);
               assertTrue(savedDraft.get("status_label").toString().contains("not sent"));
               assertTrue(service.view(CarlService.Scope.privateFor("alice"), "artifacts").stream().anyMatch(row -> ((Number) row.get("id")).longValue() == draftId));
               assertTrue(service.view(CarlService.Scope.privateFor("bob"), "artifacts").isEmpty());
               assertEquals(404, send(http, draftPath, "GET", bob, null).statusCode());
               com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), reportId, draftId);
               assertEquals(400, send(http, reportPath, "PUT", alice, "{\"input\":{},\"principal\":\"bob\"}").statusCode());
               try(var connection = data.getConnection(); var statement = connection.createStatement())
               {
                  statement.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
               }
               assertEquals(404, send(http, draftPath, "GET", alice, null).statusCode());
               org.mockito.Mockito.verifyNoInteractions(sessions);
            }
            finally
            {
               server.stop();
            }
         }
      }
   }



   private static JsonNode await(HttpClient http, String path, String token) throws Exception
   {
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      JsonNode result;
      do
      {
         var response = send(http, path, "GET", token, null);
         assertEquals(200, response.statusCode(), response.body());
         result = JSON.readTree(response.body());
         if(!result.path("status").asText().equals("PENDING"))
         {
            return result;
         }
         Thread.sleep(25);
      }
      while(System.nanoTime() < deadline);
      throw new AssertionError("Workflow exceeded controlled test deadline");
   }



   private static HttpResponse<String> send(HttpClient http, String url, String method, String token, String body) throws Exception
   {
      return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token).header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String token(Algorithm algorithm, String subject)
   {
      return JWT.create().withHeader(Map.of("typ", "at+jwt")).withKeyId("fixture").withIssuer(ISSUER).withAudience("carl-family").withSubject(subject).withIssuedAt(Instant.now().minusSeconds(5)).withExpiresAt(Instant.now().plusSeconds(300)).withClaim("scope", "agent:chat").withClaim("client_id", "synthetic-app").withJWTId(UUID.randomUUID().toString()).sign(algorithm);
   }



   private static String unsigned(byte[] value)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value);
   }
}
