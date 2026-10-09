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
import com.kof22.carlai.DocumentMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real PostgreSQL post-commit revocation on all five native protected download paths, including both report formats. */
class CarlNativeDownloadRaceTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private enum PathKind
   {
      CSV, DOCUMENT, PLAN, REPORT_PDF, REPORT_TEXT, VENDOR
   }
   @ParameterizedTest
   @EnumSource(PathKind.class)
   void committedDownloadReauthorizationPreventsResponseBytes(PathKind path) throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         com.kof22.agentcore.store.AgentMigrations.migrate(data);
         var barrier = new DownloadCommitBarrier(data);
         var service = new CarlService(barrier, Clock.systemUTC());
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic vendor home','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true),(2,1,'bob','Synthetic other',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true),(1,'FINANCE',true),(2,'FINANCE',true),(1,'BILLS',true),(2,'BILLS',true),(1,'CALENDAR',true),(2,'CALENDAR',true),(1,'TAX',true),(2,'TAX',true)");
            sql.execute("CREATE ROLE carl_export_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_export_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_export_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_artifact_view,carl_vendor_view,carl_table_export_view,carl_import_review_view,carl_document_view,carl_plan_view,carl_work_view,carl_draft_revision_view TO carl_export_reader");
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
         var app = new AdminApplication(reader, List.of(new DocumentMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
         {
         }, new NativeDownloadPolicy(Map.of("carlProtectedTableExports", "carlDownloadTableExport", "carlProtectedDocuments", "carlDownloadDocument", "carlProtectedPlanExports", "carlExportPlan", "carlProtectedReportPdfs", "carlDownloadReportPdf", "carlProtectedReportTexts", "carlDownloadReportText", "carlProtectedVendorDrafts", "carlDownloadVendorDraft"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String route;
            String forbidden;
            String domain;
            String loadClass;
            switch(path)
            {
               case CSV ->
               {
                  service.createVendor("alice", "RACE_PRIVATE_CSV", "Supplied", "Contact", false, "PRIVATE", "Supplied source");
                  var generated = process(http, base, alice, "carlExportRecordsVendors", Map.of("scope", "ALL_AUTHORIZED"));
                  var download = process(http, base, alice, "carlDownloadTableExport", Map.of("exportId", generated.path("values").path("exportId").asText()));
                  route = downloadRoute(download);
                  forbidden = "RACE_PRIVATE_CSV";
                  domain = "VENDORS";
                  loadClass = "TableExports";
               }
               case DOCUMENT ->
               {
                  var uploads = new com.kof22.carlai.domain.MonarchImportWorkflow(service);
                  uploads.storeUpload("alice", "race-original.txt", "RACE_PRIVATE_ORIGINAL".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  var documents = new com.kof22.carlai.domain.DocumentRecords(service);
                  UUID review = documents.preview("alice", UUID.randomUUID(), "race-original.txt", new com.kof22.carlai.domain.DocumentRecords.Source("Supplied race source", "PRIVATE", "synthetic://supplied", "HISTORICAL_NOTE", null, null, "Controlled supplied provenance"));
                  long doc = documents.confirm("alice", review, true);
                  route = downloadRoute(process(http, base, alice, "carlDownloadDocument", Map.of("documentId", "" + doc)));
                  forbidden = "RACE_PRIVATE_ORIGINAL";
                  domain = "FINANCE";
                  loadClass = "DocumentRecords";
               }
               case PLAN ->
               {
                  var readiness = new com.kof22.carlai.domain.ReadinessPlans(service).generate(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), "RACE_PRIVATE_PLAN");
                  route = downloadRoute(process(http, base, alice, "carlExportPlan", Map.of("planId", "" + readiness.plan())));
                  forbidden = "%PDF";
                  domain = "FINANCE";
                  loadClass = "PlanExports";
               }
               case REPORT_PDF, REPORT_TEXT ->
               {
                  service.importBills("alice", UUID.randomUUID(), "Controlled race bills", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic gas,RACE_PRIVATE_REPORT,125.25,USD,2026-09-10,UNPAID,PRIVATE\n");
                  long report = service.generateReport(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), java.time.LocalDate.parse("2026-09-01"), java.time.LocalDate.parse("2026-09-30"), verified -> "Controlled supplied narrative");
                  route = downloadRoute(process(http, base, alice, path == PathKind.REPORT_PDF ? "carlDownloadReportPdf" : "carlDownloadReportText", Map.of("reportId", "" + report)));
                  forbidden = path == PathKind.REPORT_PDF ? "%PDF" : "125.25";
                  domain = "BILLS";
                  loadClass = "ArtifactExports";
               }
               case VENDOR ->
               {
                  long vendor = service.createVendor("alice", "RACE_PRIVATE_VENDOR", "Supplied", "Contact", false, "PRIVATE", "Supplied source");
                  long work = service.createWorkItem("alice", vendor, "RACE_PRIVATE_VENDOR", "VENDOR_RESPONSE", null, "Supplied request", "PRIVATE");
                  long draft = service.generateDraft(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), work, "FOLLOW_UP");
                  route = downloadRoute(process(http, base, alice, "carlDownloadVendorDraft", Map.of("draftId", "" + draft)));
                  forbidden = "RACE_PRIVATE_VENDOR";
                  domain = "VENDORS";
                  loadClass = "VendorRecords";
               }
               default -> throw new AssertionError(path);
            }
            var normal = request(http, base, route, alice, null);
            assertEquals(200, normal.statusCode(), normal.body());
            assertTrue(normal.body().contains(forbidden), normal.body());
            assertEquals("no-store", normal.headers().firstValue("Cache-Control").orElseThrow());
            barrier.arm("com.kof22.carlai.domain." + loadClass);
            try(var pool = java.util.concurrent.Executors.newSingleThreadExecutor())
            {
               var response = pool.submit(() -> request(http, base, route, alice, null));
               try
               {
                  assertTrue(barrier.awaitCommit(), "Actual protected load must commit before concurrent revocation");
                  try(var c = data.getConnection(); var statement = c.createStatement())
                  {
                     assertEquals(1, statement.executeUpdate("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='" + domain + "'"));
                  }
               }
               finally
               {
                  barrier.release();
               }
               var denied = response.get(15, java.util.concurrent.TimeUnit.SECONDS);
               assertEquals(403, denied.statusCode(), denied.body());
               assertFalse(denied.body().contains(forbidden), denied.body());
               assertFalse(denied.body().contains("RACE_PRIVATE"), denied.body());
               assertTrue(denied.body().contains("access changed"), denied.body());
            }
            var beforeLoad = request(http, base, route, alice, null);
            assertTrue(beforeLoad.statusCode() >= 400, beforeLoad.body());
            assertFalse(beforeLoad.body().contains(forbidden));
            assertTrue(request(http, base, route, bob, null).statusCode() >= 400);
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static String downloadRoute(com.fasterxml.jackson.databind.JsonNode download)
   {
      String table = download.path("values").path("storageTableName").asText();
      String reference = download.path("values").path("storageReference").asText();
      assertFalse(table.isBlank(), download.toString());
      assertFalse(reference.isBlank(), download.toString());
      return "/qqq/v1/download/Controlled-file?storageTableName=" + table + "&storageReference=" + reference;
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
