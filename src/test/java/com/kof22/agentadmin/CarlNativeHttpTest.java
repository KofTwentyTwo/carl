/*
 * Copyright (C) 2026 KofTwentyTwo
 */
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FinancialRecords;
import com.kof22.carlai.domain.MonarchImportWorkflow;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Actual native HTTP metadata, owned multipart processes and PostgreSQL records. */
public class CarlNativeHttpTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   /** Reads the exact family-workflow outputs through authenticated native QQQ with a restricted reader. */
   public static void assertFamilyArtifacts(NativeAgentConfiguration configuration, com.kof22.agentadmin.bootstrap.NativeAgentRuntime.Components components, javax.sql.DataSource data, Jwk jwk, RSAPublicKey publicKey, RSAPrivateKey privateKey, long report, long draft) throws Exception
   {
      try(var c = data.getConnection(); var sql = c.createStatement())
      {
         sql.execute("CREATE ROLE carl_family_reader LOGIN PASSWORD 'synthetic-family-reader'");
         sql.execute("GRANT USAGE ON SCHEMA public TO carl_family_reader");
         for(var table : AdminApplication.READER_COLUMNS.entrySet())
         {
            if(!table.getValue().isEmpty())
            {
               sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_family_reader");
            }
         }
         sql.execute("GRANT SELECT ON carl_artifact_view TO carl_family_reader");
      }
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
      var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-admin", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> jwk);
      var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_family_reader", "synthetic-family-reader");
      var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), configuration.database().username(), configuration.database().password());
      var application = new AdminApplication(reader, components.metadata(), identity, runtime);
      try(var server = new AdminServer(application, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime)); var http = HttpClient.newHttpClient())
      {
         server.start();
         String base = "http://127.0.0.1:" + server.port();
         String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, privateKey));
         String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, privateKey));
         var metadata = request(http, base, "/metaData", alice, "GET", null, null);
         assertEquals(200, metadata.statusCode(), metadata.body());
         var savedReport = request(http, base, "/data/carlArtifacts/" + report, alice, "GET", null, null);
         assertEquals(200, savedReport.statusCode(), savedReport.body());
         assertTrue(savedReport.body().contains("HOUSEHOLD_REPORT"), savedReport.body());
         var savedDraft = request(http, base, "/data/carlArtifacts/" + draft, alice, "GET", null, null);
         assertEquals(200, savedDraft.statusCode(), savedDraft.body());
         assertTrue(savedDraft.body().contains("not sent"), savedDraft.body());
         var inaccessible = request(http, base, "/data/carlArtifacts/" + draft, bob, "GET", null, null);
         assertFalse(inaccessible.body().contains("Synthetic repair vendor"), inaccessible.body());
         assertTrue(inaccessible.statusCode() >= 400, inaccessible.body());
      }
      finally
      {
         keyServer.stop(0);
      }
   }



   @Test
   void nativeUploadPreviewApplyAndPrivateRecordsUseRealConsumerMetadata() throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var dataSource = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(dataSource);
         try(var c = dataSource.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic home','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            sql.execute("CREATE ROLE carl_test_reader LOGIN PASSWORD 'synthetic-reader-only'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_test_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_test_reader");
               }
            }
            for(String view : List.of("bill", "vendor", "work", "account", "property", "artifact", "calendar", "transaction", "budget", "tax", "calendar_connection", "import_review", "plan", "plan_step", "native_plan_step", "native_calendar_operation", "member", "debt", "cash_plan", "calendar_operation", "financial_goal", "financing_offer", "tax_property", "rental_property", "rental_unit", "rental_source", "rent_due", "rent_application", "expense", "expense_actual", "expense_settlement"))
            {
               sql.execute("GRANT SELECT ON carl_" + view + "_view TO carl_test_reader");
            }
         }
         var service = new CarlService(dataSource, Clock.systemUTC());
         long account = new FinancialRecords(service).createAccount("alice", "Private synthetic account", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Synthetic local fixture");
         new MonarchImportWorkflow(service).mapAccount("alice", "Checking", account);
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var key = generator.generateKeyPair();
         var publicKey = (RSAPublicKey) key.getPublic();
         var jwk = Jwk.fromValues(Map.of("kty", "RSA", "kid", "synthetic", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())));
         var keyServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         String issuer = "http://127.0.0.1:" + keyServer.getAddress().getPort() + "/";
         byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "synthetic", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())))));
         keyServer.createContext("/.well-known/jwks.json", exchange ->
         {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try(var body = exchange.getResponseBody())
            {
               body.write(jwks);
            }
         });
         keyServer.start();
         try
         {
            var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), kid -> jwk);
            var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader-only", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
            var components = AgentApplication.components();
            components.validate(configuration);
            var tools = components.tools(null);
            var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_test_reader", "synthetic-reader-only");
            var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
            var application = new AdminApplication(reader, components.metadata(), identity, runtime);
            try(var server = new AdminServer(application, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime)); var http = HttpClient.newHttpClient())
            {
               server.start();
               String base = "http://127.0.0.1:" + server.port();
               String alice = JWT.create().withKeyId("synthetic").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) key.getPrivate()));
               String bob = JWT.create().withKeyId("synthetic").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) key.getPrivate()));
               var metadata = request(http, base, "/metaData", alice, "GET", null, null);
               assertEquals(200, metadata.statusCode(), metadata.body());
               assertTrue(metadata.body().contains("Import from Monarch"));
               var accounts = request(http, base, "/data/carlAccounts", alice, "GET", null, null);
               assertEquals(200, accounts.statusCode(), accounts.body());
               assertTrue(accounts.body().contains("Private synthetic account"));
               assertFalse(request(http, base, "/data/carlAccounts", bob, "GET", null, null).body().contains("Private synthetic account"));
               String init = multipart(Map.of(), Map.of());
               var start = request(http, base, "/processes/carlImportMonarch/init", alice, "POST", init, "multipart/form-data; boundary=carl-boundary");
               assertEquals(200, start.statusCode(), start.body());
               String processId = JSON.readTree(start.body()).path("processUUID").asText();
               assertFalse(processId.isBlank(), start.body());
               String transactions = "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-01,Synthetic shop,Food,Checking,Synthetic,,-12.30,,,Reviewed,100000000000000001\n";
               String upload = multipart(Map.of(), Map.of("transactionsFile", transactions));
               var preview = request(http, base, "/processes/carlImportMonarch/" + processId + "/step/upload", alice, "POST", upload, "multipart/form-data; boundary=carl-boundary");
               assertEquals(200, preview.statusCode(), preview.body());
               assertTrue(preview.body().contains("Transactions: 1"), preview.body());
               String reviewId = JSON.readTree(preview.body()).path("values").path("reviewId").asText();
               assertFalse(reviewId.isBlank(), preview.body());
               var wrong = request(http, base, "/processes/carlImportMonarch/" + processId + "/step/review", bob, "POST", multipart(Map.of("reviewId", reviewId, "confirm", "true"), Map.of()), "multipart/form-data; boundary=carl-boundary");
               assertTrue(wrong.statusCode() >= 400, wrong.body());
               var apply = request(http, base, "/processes/carlImportMonarch/" + processId + "/step/review", alice, "POST", multipart(Map.of("reviewId", reviewId, "confirm", "true"), Map.of()), "multipart/form-data; boundary=carl-boundary");
               assertEquals(200, apply.statusCode(), apply.body());
               assertTrue(apply.body().contains("COMPLETE"), apply.body());
               assertEquals(1, service.view(CarlService.Scope.privateFor("alice"), "transactions").size());
               var created = nativeProcess(http, base, alice, "carlAddRentalProperty", Map.of("title", "Synthetic HTTP rental", "visibility", "PRIVATE", "evidence", "Synthetic supplied deed", "currency", "USD", "locality", "Chester, Illinois"));
               assertTrue(created.contains("Unknown ownership and basis remain unknown"), created);
               long property = ((Number) service.view(CarlService.Scope.privateFor("alice"), "properties").getFirst().get("id")).longValue();
               assertFalse(request(http, base, "/data/carlProperties", bob, "GET", null, null).body().contains("Synthetic HTTP rental"));
               var unit = nativeProcess(http, base, alice, "carlAddRentalUnit", Map.of("property", Long.toString(property), "title", "Synthetic HTTP unit", "visibility", "PRIVATE", "evidence", "Supplied synthetic lease", "occupancy", "UNKNOWN"));
               assertTrue(unit.contains("saved with supplied occupancy"), unit);
               var due = nativeProcess(http, base, alice, "carlRecordRentDue", Map.of("property", Long.toString(property), "due", "2026-09-01", "amount", "900.00", "visibility", "PRIVATE", "evidence", "Synthetic scheduled rent"));
               assertTrue(due.contains("does not establish receipt"), due);
               var report = nativeProcess(http, base, alice, "carlRentalReport", Map.of("property", Long.toString(property), "from", "2026-09-01", "through", "2026-09-30", "asOf", "2026-09-30"));
               assertTrue(report.contains("Missing ownership"), report);
               var packet = nativeProcess(http, base, alice, "carlTaxPacket", Map.of("property", Long.toString(property), "taxYear", "2026", "asOf", "2026-09-30"));
               assertTrue(packet.contains("No tax liability, filing or entity choice has been calculated"), packet);
               var taxContext = nativeProcess(http, base, alice, "carlTaxPropertyContext", Map.of("property", Long.toString(property), "placed", "2025-01-01", "financed", "true", "evidence", "Synthetic placement document"));
               assertTrue(taxContext.contains("saved with attribution"), taxContext);
               var taxSource = nativeProcess(http, base, alice, "carlTaxDocument", Map.of("property", Long.toString(property), "title", "Synthetic tax document", "visibility", "PRIVATE", "taxYear", "2026", "category", "RENT_RECORDS", "treatment", "UNKNOWN", "sourceEvidence", "Synthetic supplied record"));
               assertTrue(taxSource.contains("does not fetch arbitrary documents"), taxSource);
               var schedule = nativeProcess(http, base, alice, "carlCreateExpense", Map.of("title", "Synthetic HTTP expense", "visibility", "PRIVATE", "evidence", "Synthetic commitment", "currency", "USD", "cadence", "MONTHLY", "firstDue", "2026-09-30", "amount", "300.00", "kind", "EXPENSE", "basis", "COMMITTED"));
               assertTrue(schedule.contains("recorded. Estimates"), schedule);
               long expenseId = ((Number) service.view(CarlService.Scope.privateFor("alice"), "expenses").getFirst().get("id")).longValue();
               var payment = nativeProcess(http, base, alice, "carlRecordExpenseActual", Map.of("paid", "2026-09-01", "currency", "USD", "amount", "100.00", "kind", "EXPENSE", "visibility", "PRIVATE", "evidence", "Synthetic payment assertion"));
               assertTrue(payment.contains("Carl has not sent a payment"), payment);
               long paymentId = ((Number) service.view(CarlService.Scope.privateFor("alice"), "expenseActuals").getFirst().get("id")).longValue();
               var settled = nativeProcess(http, base, alice, "carlSettleExpense", Map.of("expense", Long.toString(expenseId), "due", "2026-09-30", "actual", Long.toString(paymentId), "amount", "100.00", "evidence", "Synthetic payment application"));
               assertTrue(settled.contains("prevents counting the applied amount again"), settled);
               var projected = nativeProcess(http, base, alice, "carlExpenseReport", Map.of("expense", Long.toString(expenseId), "from", "2026-09-01", "through", "2026-09-30", "asOf", "2026-09-15"));
               assertTrue(projected.contains("not the entire household"), projected);
               assertFalse(request(http, base, "/data/carlExpenses", bob, "GET", null, null).body().contains("Synthetic HTTP expense"));
               assertEquals(java.util.Set.of("carl_read_bills", "carl_read_finances", "carl_read_records", "carl_read_budget", "carl_read_preferences", "carl_read_availability"), tools.stream().map(tool -> tool.definition().name()).collect(java.util.stream.Collectors.toSet()));
               var recordsTool = tools.stream().filter(tool -> tool.definition().name().equals("carl_read_records")).findFirst().orElseThrow();
               for(String kind : List.of("properties", "taxProperties", "tax", "rentalUnits", "rentalSources", "rentDues", "rentApplications", "artifacts", "calendar", "cashPlans", "financialGoals", "financingOffers", "expenses", "expenseActuals", "expenseSettlements"))
               {
                  var facts = recordsTool.executor().execute(JSON.writeValueAsString(Map.of("kind", kind)), "alice");
                  assertFalse(facts.isError(), kind + ": " + facts.content());
               }
               assertEquals("[]", recordsTool.executor().execute("{\"kind\":\"properties\"}", "bob").content());
               assertTrue(recordsTool.executor().execute("{\"kind\":\"properties\",\"principal\":\"alice\"}", "bob").isError());
               assertTrue(recordsTool.executor().execute("{\"kind\":\"properties\"}", "unmapped").isError());
               service.importBills("alice", java.util.UUID.randomUUID(), "Synthetic tool evidence", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic utility,Electric,125.25,USD,2026-09-30,UNPAID,PRIVATE\nb,Synthetic utility,Gas,74.75,USD,2026-09-30,UNPAID,PRIVATE\n");
               var billsTool = tools.stream().filter(tool -> tool.definition().name().equals("carl_read_bills")).findFirst().orElseThrow();
               var bills = billsTool.executor().execute("{\"from\":\"2026-09-01\",\"through\":\"2026-09-30\"}", "alice");
               assertFalse(bills.isError(), bills.content());
               assertEquals("2026-09-30", JSON.readTree(bills.content()).path("bills").get(0).path("due_date").asText());
               assertEquals(0, new BigDecimal("200.00").compareTo(JSON.readTree(bills.content()).path("totals").path("USD:UNPAID").decimalValue()));
               var financesTool = tools.stream().filter(tool -> tool.definition().name().equals("carl_read_finances")).findFirst().orElseThrow();
               assertFalse(financesTool.executor().execute("{\"from\":\"2026-09-01\",\"through\":\"2026-09-30\"}", "alice").isError());

            }
         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static String nativeProcess(HttpClient http, String base, String token, String name, Map<String, String> fields) throws Exception
   {
      var start = request(http, base, "/processes/" + name + "/init", token, "POST", multipart(Map.of(), Map.of()), "multipart/form-data; boundary=carl-boundary");
      assertEquals(200, start.statusCode(), start.body());
      String id = JSON.readTree(start.body()).path("processUUID").asText();
      assertFalse(id.isBlank(), start.body());
      var result = request(http, base, "/processes/" + name + "/" + id + "/step/input", token, "POST", multipart(fields, Map.of()), "multipart/form-data; boundary=carl-boundary");
      assertEquals(200, result.statusCode(), result.body());
      return result.body();
   }



   private static HttpResponse<String> request(HttpClient http, String base, String path, String token, String method, String body, String type) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic");
      if(type != null)
      {
         request.header("Content-Type", type);
      }
      return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String multipart(Map<String, String> fields, Map<String, String> files)
   {
      var body = new StringBuilder();
      fields.forEach((key, value) -> body.append("--carl-boundary\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
      files.forEach((key, value) -> body.append("--carl-boundary\r\nContent-Disposition: form-data; name=\"").append(key).append("\"; filename=\"synthetic.csv\"\r\nContent-Type: text/csv\r\n\r\n").append(value).append("\r\n"));
      return body.append("--carl-boundary--\r\n").toString();
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
