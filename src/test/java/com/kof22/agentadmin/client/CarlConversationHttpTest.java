/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin.client;


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
import com.kof22.agentcore.policy.DataProtection;
import com.kof22.agentcore.session.SessionManager;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import com.kof22.carlai.domain.CarlService;
import io.javalin.Javalin;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real family HTTP identity and explicit workflows over the production consumer factory and PostgreSQL. */
class CarlConversationHttpTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String ISSUER = "https://synthetic.identity.example/";

   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   @org.junit.jupiter.api.BeforeAll
   static void startDatabase()
   {
      DATABASE.start();
   }



   @org.junit.jupiter.api.AfterAll
   static void stopDatabase()
   {
      DATABASE.stop();
   }



   @org.junit.jupiter.params.ParameterizedTest
   @org.junit.jupiter.params.provider.ValueSource(strings = {"plan-quoted", "goal-rationale"})
   void quotedArgumentsAndReasonsRetainCurrentHumanAction(String scenario) throws Exception
   {
      naturalLanguageWorkflowPersistsTheSameAuthorizedReportAndDraftShownByQqq(scenario);
   }



   @org.junit.jupiter.params.ParameterizedTest
   @org.junit.jupiter.params.provider.ValueSource(strings = {"plan-lifecycle", "plan-negative", "plan-calendar", "goal-priority", "goal-negative", "goal-tradeoff", "financial-intent-negative", "plan-guards"})
   void conversationalPlanLifecycleUsesHumanIntentAndCurrentProtectedData(String scenario) throws Exception
   {
      naturalLanguageWorkflowPersistsTheSameAuthorizedReportAndDraftShownByQqq(scenario);
   }



   @org.junit.jupiter.params.ParameterizedTest
   @org.junit.jupiter.params.provider.ValueSource(strings = {"failure", "history", "shared", "deadline", "purchase", "purchase-negative", "purchase-budget", "financial", "financial-negative", "rental", "tax", "investment", "balance", "expense", "extended-negative", "investment-return-units", "investment-fee-units"})
   void naturalLanguageWorkflowPersistsTheSameAuthorizedReportAndDraftShownByQqq(String scenario) throws Exception
   {
      try(var provider = new Provider(); var calendar = new SharedCalendar())
      {
         var database = DATABASE;
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(data);
         try(var connection = data.getConnection(); var statement = connection.createStatement())
         {
            statement.execute("TRUNCATE carl_household,carl_request,agent_client_conversation,token_usage RESTART IDENTITY CASCADE");
            statement.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic HTTP family','America/Chicago')");
            statement.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',true)");
            statement.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            statement.execute("INSERT INTO carl_identity(issuer,subject,member_id) VALUES('" + ISSUER + "','alice-subject',1),('" + ISSUER + "','bob-subject',2)");
         }
         var service = new CarlService(data, Clock.systemUTC());
         service.importBills("alice", UUID.randomUUID(), "Synthetic HTTP bills", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic utility,Power,125.25,USD,2026-09-30,UNPAID,PRIVATE\nb,Synthetic utility,Gas,74.75,USD,2026-09-30,UNPAID,PRIVATE\n");
         long vendor = service.createVendor("alice", "Synthetic repair vendor", "Maintenance", "vendor@example.invalid", true, "PRIVATE", "Supplied synthetic contact");
         long work = service.createWorkItem("alice", vendor, "Review repair question", "VENDOR_RESPONSE", LocalDate.of(2026, 9, 30), "A question was supplied; no price or commitment exists", "PRIVATE");
         var configuration = com.kof22.agentadmin.bootstrap.NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.qqq.password=synthetic-unused-bootstrap", "--kof22.agent.anthropic-api-key=synthetic-no-provider", "--kof22.agent.anthropic-base-url=" + provider.url(), "--kof22.agent.model.id=claude-sonnet-5", "--kof22.agent.limits.max-output-tokens=40", "--kof22.agent.limits.turn-timeout=" + (scenario.equals("deadline") ? "PT2S" : "PT10S")).configuration();
         var components = scenario.equals("plan-calendar") ? AgentApplication.components((source, domain) -> calendar.workflows(source, domain)) : AgentApplication.components();
         components.validate(configuration);
         var inference = components.runtime(configuration);
         var nativeStores = com.kof22.agentadmin.bootstrap.NativeStores.create(configuration.database());
         provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "REPORT", "focus", "HOUSEHOLD", "from", "2026-09-01", "through", "2026-09-30")));
         provider.enqueue("end_turn", "The permitted unpaid USD bills total 200.00. Calendar coverage is incomplete.");
         provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "DRAFT", "workId", work, "purpose", "FOLLOW_UP")));
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
         try(inference; var clients = new ClientService(store, sessions, identity, access, DataProtection.defaults(), Duration.ofSeconds(10), components.clientWorkflows(nativeStores)); var http = HttpClient.newHttpClient())
         {
            var server = startClientServer(identity, store, clients);
            String origin = "http://127.0.0.1:" + server.port();
            try
            {
               String base = origin + "/api/agent/v1";
               String alice = token(algorithm, "alice-subject");
               String bob = token(algorithm, "bob-subject");
               String conversation = "/conversations/" + UUID.randomUUID();
               assertEquals(401, send(http, base + "/me", "GET", "invalid", null).statusCode());
               assertEquals(200, send(http, base + conversation, "PUT", alice, "{}").statusCode());
               assertEquals(404, send(http, base + conversation, "GET", bob, null).statusCode());
               String reportPath = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
               String reportInput = "{\"input\":{\"message\":\"Please prepare our household brief for September 2026.\"}}";
               assertEquals(200, send(http, reportPath, "PUT", alice, reportInput).statusCode());
               var report = await(http, reportPath, alice);
               assertTrue(List.of("COMPLETE", "PARTIAL").contains(report.path("status").asText()), report.toString());
               long reportId = Long.parseLong(report.path("artifactId").asText());
               String facts = service.artifact("alice", reportId).get("facts").toString();
               assertTrue(facts.contains("200.00"), facts);
               assertEquals(report, JSON.readTree(send(http, reportPath, "PUT", alice, reportInput).body()));
               String draftPath = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
               assertEquals(200, send(http, draftPath, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Please prepare a follow-up draft for Synthetic repair vendor", "replyTo", reportPath.substring(reportPath.lastIndexOf('/') + 1))))).statusCode());
               var draft = await(http, draftPath, alice);
               assertEquals("COMPLETE", draft.path("status").asText(), draft.toString());
               long draftId = Long.parseLong(draft.path("artifactId").asText());
               var savedDraft = service.artifact("alice", draftId);
               assertTrue(savedDraft.get("status_label").toString().contains("not sent"));
               assertTrue(service.view(CarlService.Scope.privateFor("alice"), "artifacts").stream().anyMatch(row -> ((Number) row.get("id")).longValue() == draftId));
               assertTrue(service.view(CarlService.Scope.privateFor("bob"), "artifacts").isEmpty());
               assertEquals(404, send(http, draftPath, "GET", bob, null).statusCode());
               if(scenario.equals("failure"))
               {
                  com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), reportId, draftId);
               }
               assertEquals(400, send(http, reportPath, "PUT", alice, "{\"input\":{},\"principal\":\"bob\"}").statusCode());
               org.mockito.Mockito.verifyNoInteractions(sessions);
               assertEquals(3, provider.requests.size());
               assertTrue(provider.requests.getFirst().contains("Synthetic repair vendor"), "Natural-language vendor selection requires the currently authorized vendor label, not an unexplained numeric work ID");
               assertTrue(provider.requests.stream().allMatch(body -> body.contains("claude-sonnet-5")));
               assertTrue(provider.requests.stream().noneMatch(body -> body.contains("tool_choice")));
               assertTrue(provider.requests.get(1).contains("125.25"));
               try(var connection = data.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT sum(input_tokens) FROM token_usage"))
               {
                  assertTrue(rows.next());
                  assertEquals(30, rows.getLong(1));
               }
               assertEquals(40, JSON.readTree(provider.requests.getFirst()).get("max_tokens").intValue());
               assertEquals(20, JSON.readTree(provider.requests.get(1)).get("max_tokens").intValue());
               switch(scenario)
               {
                  case "plan-quoted" -> assertQuotedPlan(provider, http, base + conversation, alice, service);
                  case "plan-guards" -> assertPlanGuards(provider, http, base + conversation, alice, service);
                  case "financial-intent-negative" -> assertFinancialIntent(provider, http, base + conversation, alice, service);
                  case "goal-priority", "goal-negative", "goal-tradeoff", "goal-rationale" ->
                  {
                     long goalArtifact = assertGoalConversation(provider, http, base + conversation, alice, service, scenario);
                     if(goalArtifact > 0)
                     {
                        com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), goalArtifact, draftId, "FINANCIAL_PLAN");
                     }
                  }
                  case "plan-calendar" -> assertPlanCalendar(provider, calendar, http, base + conversation, alice, service);
                  case "plan-lifecycle", "plan-negative" ->
                  {
                     long[] plan = assertPlanLifecycle(provider, http, base, conversation, alice, service, scenario.equals("plan-negative"));
                     if(!scenario.equals("plan-negative"))
                     {
                        com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), plan[0], draftId, "FINANCIAL_PLAN", plan[1]);
                     }
                  }
                  case "native-documents" -> assertNativeDocuments(provider, configuration, components, data, service, clients, pair, jwk);
                  case "failure" -> assertFailureAndBoundaries(provider, http, base, conversation, alice, service);
                  case "history" -> assertSerializedClarifications(provider, http, base, conversation, alice);
                  case "shared" -> assertSharedScope(provider, http, base, alice, work, service);
                  case "deadline" -> assertDelayedNarration(provider, http, base, conversation, alice, service);
                  case "purchase", "purchase-negative", "purchase-budget" ->
                  {
                     long purchase = assertKitchenPurchase(provider, http, base, alice, bob, service, scenario.equals("purchase-negative"), scenario.equals("purchase-budget"));
                     if(purchase > 0)
                     {
                        com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), purchase, draftId, "FINANCIAL_PLAN");
                     }
                  }
                  case "financial", "financial-negative" ->
                  {
                     long[] financial = assertFinancialPlan(provider, http, base, alice, bob, service, scenario.equals("financial-negative"));
                     if(scenario.equals("financial"))
                     {
                        com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), financial[0], draftId, "FINANCIAL_PLAN", financial[1]);
                     }
                  }
                  case "rental", "tax", "investment", "balance", "expense", "extended-negative", "investment-return-units", "investment-fee-units" ->
                  {
                     long extended = assertFinancialReports(provider, http, base, alice, bob, service, scenario);
                     if(extended > 0)
                     {
                        com.kof22.agentadmin.CarlNativeHttpTest.assertFamilyArtifacts(configuration, components, data, jwk, key, (RSAPrivateKey) pair.getPrivate(), extended, draftId, "FINANCIAL_PLAN");
                     }
                  }
                  default -> throw new AssertionError("Unknown fixture scenario");
               }
               try(var connection = data.getConnection(); var statement = connection.createStatement())
               {
                  statement.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='VENDORS'");
               }
               assertEquals(404, send(http, draftPath, "GET", alice, null).statusCode());
               int afterRevocation = provider.requests.size();
               assertEquals(404, send(http, base + conversation + "/workflows/conversation/" + UUID.randomUUID(), "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Tell me more about that draft", "replyTo", draftPath.substring(draftPath.lastIndexOf('/') + 1))))).statusCode());
               assertEquals(afterRevocation, provider.requests.size());
            }
            finally
            {
               server.stop();
            }
         }
      }
   }



   @org.junit.jupiter.api.Test
   void nativeImperativeDocumentQuestionReadsHistoricalEvidenceWithoutCreatingReports() throws Exception
   {
      naturalLanguageWorkflowPersistsTheSameAuthorizedReportAndDraftShownByQqq("native-documents");
   }



   private static void assertNativeDocuments(Provider provider, com.kof22.agentadmin.configuration.NativeAgentConfiguration configuration, com.kof22.agentadmin.bootstrap.NativeAgentRuntime.Components components, javax.sql.DataSource data, CarlService service, ClientService clients, java.security.KeyPair pair, Jwk jwk) throws Exception
   {
      var documents = new com.kof22.carlai.domain.DocumentRecords(service);
      var uploads = new com.kof22.carlai.domain.MonarchImportWorkflow(service);
      uploads.storeUpload("alice", "native-history.txt", "Historical supplied home note: roof inspection was recommended in 2008. Current condition and loan facts are unverified.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      long document = documents.confirm("alice", documents.preview("alice", UUID.randomUUID(), "native-history.txt", new com.kof22.carlai.domain.DocumentRecords.Source("Historical home note", "FAMILY", "Controlled supplied note", "HISTORICAL_NOTE", LocalDate.of(2008, 5, 1), null, "Supplied historical evidence; no current qualification")), true);
      int calls = provider.requests.size();
      String answer = "The historical home note [record:" + document + "] dated 2008-05-01 is SUPPLIED_UNVERIFIED. It recommends a roof inspection; it does not confirm current condition or financial facts.";
      provider.replies.add(JSON.writeValueAsString(Map.of("id", "msg_document", "type", "message", "role", "assistant", "model", "claude-sonnet-5", "content", List.of(Map.of("type", "tool_use", "id", "tool_document", "name", "carl_read_document", "input", Map.of("id", document, "offset", 0, "limit", 4000))), "stop_reason", "tool_use", "usage", Map.of("input_tokens", 1, "output_tokens", 1))));
      provider.enqueue("end_turn", answer, 1);
      components.clientServiceReady(clients);
      try(var c = data.getConnection(); var sql = c.createStatement())
      {
         sql.execute("CREATE ROLE carl_document_chat_reader LOGIN PASSWORD 'controlled-reader'");
         sql.execute("GRANT USAGE ON SCHEMA public TO carl_document_chat_reader");
         for(var table : com.kof22.agentadmin.AdminApplication.READER_COLUMNS.entrySet())
         {
            if(!table.getValue().isEmpty())
            {
               sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_document_chat_reader");
            }
         }
         sql.execute("GRANT SELECT ON carl_artifact_view,carl_document_view TO carl_document_chat_reader");
      }
      var keyServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      String issuer = "http://127.0.0.1:" + keyServer.getAddress().getPort() + "/";
      byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(((RSAPublicKey) pair.getPublic()).getModulus().toByteArray()), "e", unsigned(((RSAPublicKey) pair.getPublic()).getPublicExponent().toByteArray())))));
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
      var identity = com.kof22.agentadmin.CarlDocumentConversationIdentityFixture.identity(issuer, jwk);
      var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_document_chat_reader", "controlled-reader");
      var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), configuration.database().username(), configuration.database().password());
      var app = new com.kof22.agentadmin.AdminApplication(reader, components.metadata(), identity, runtime);
      try(var server = new com.kof22.agentadmin.AdminServer(app, 0, "127.0.0.1", "https://carl.controlled", identity, com.kof22.agentadmin.OperatorSessions.usingBackend(runtime)); var http = HttpClient.newHttpClient())
      {
         server.start();
         String base = "http://127.0.0.1:" + server.port();
         var algorithm = Algorithm.RSA256((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
         String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(algorithm);
         String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(algorithm);
         var initial = nativeDocumentStep(http, base, alice, "carlTalkStart", null, Map.of());
         assertEquals(200, initial.statusCode(), initial.body());
         var prepared = JSON.readTree(initial.body());
         String process = prepared.path("processUUID").asText();
         UUID request = UUID.fromString(prepared.path("values").path("requestId").asText());
         UUID conversation = UUID.nameUUIDFromBytes(("carl-native-conversation:" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
         String selected = conversation + "/" + request;
         String message = "Look at the household documents we have supplied and read a note relevant to our homes or rental-property history. Explain what it says, cite the accessible source records, and distinguish its historical date and supplied/unverified status from current confirmed financial facts. Do not change financial records or create a new report in response to this question.";
         long recordsBefore = ((Number) nativeDocumentRows(data, "SELECT count(*) AS n FROM carl_record", null).getFirst().get("n")).longValue();
         var submitted = nativeDocumentStep(http, base, alice, "carlTalkStart", process, Map.of("message", message, "visibility", "PRIVATE"));
         assertEquals(200, submitted.statusCode(), submitted.body());
         long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
         String status;
         do
         {
            status = nativeDocumentRows(data, "SELECT status FROM carl_client_workflow WHERE request_id=?", request).getFirst().get("status").toString();
            if(!status.equals("PENDING"))
            {
               break;
            }
            Thread.sleep(25);
         }
         while(System.nanoTime() < deadline);
         assertEquals("COMPLETE", status);
         var readInit = nativeDocumentStep(http, base, alice, "carlTalkRead", null, Map.of());
         var read = nativeDocumentStep(http, base, alice, "carlTalkRead", JSON.readTree(readInit.body()).path("processUUID").asText(), Map.of("selected", selected));
         assertEquals(200, read.statusCode(), read.body());
         assertTrue(read.body().contains("2008-05-01"), read.body());
         assertTrue(read.body().contains("SUPPLIED_UNVERIFIED"), read.body());
         assertTrue(read.body().contains("[record:" + document + "]"), read.body());
         var otherInit = nativeDocumentStep(http, base, bob, "carlTalkRead", null, Map.of());
         var otherRead = nativeDocumentStep(http, base, bob, "carlTalkRead", JSON.readTree(otherInit.body()).path("processUUID").asText(), Map.of("selected", selected));
         assertTrue(otherRead.statusCode() >= 400 || JSON.readTree(otherRead.body()).path("type").asText().equals("ERROR"), otherRead.body());
         assertTrue(!otherRead.body().contains("roof inspection"), otherRead.body());
         assertEquals(recordsBefore, ((Number) nativeDocumentRows(data, "SELECT count(*) AS n FROM carl_record", null).getFirst().get("n")).longValue());
         assertEquals(calls + 2, provider.requests.size());
         assertTrue(JSON.readTree(provider.requests.get(calls)).path("tools").size() > 0);
         String toolResult = provider.requests.get(calls + 1);
         assertTrue(toolResult.contains("Historical supplied home note"), toolResult);
         assertTrue(toolResult.contains("SUPPLIED_UNVERIFIED"), toolResult);
         var audit = nativeDocumentRows(data, "SELECT tool_name,caller_id,decision,completed_at FROM audit_log WHERE session_key=?", "carl-conversation:" + request);
         assertEquals(1, audit.size());
         assertEquals("carl_read_document", audit.getFirst().get("tool_name"));
         assertEquals("alice", audit.getFirst().get("caller_id"));
         assertEquals("EXECUTED_READ", audit.getFirst().get("decision"));
         assertTrue(audit.getFirst().get("completed_at") != null);
      }
      finally
      {
         keyServer.stop(0);
      }
   }



   private static java.util.List<Map<String, Object>> nativeDocumentRows(javax.sql.DataSource data, String sql, Object argument) throws Exception
   {
      try(var c = data.getConnection(); var statement = c.prepareStatement(sql))
      {
         if(argument != null)
         {
            statement.setObject(1, argument);
         }
         try(var rows = statement.executeQuery())
         {
            var result = new java.util.ArrayList<Map<String, Object>>();
            while(rows.next())
            {
               var row = new java.util.LinkedHashMap<String, Object>();
               for(int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
               {
                  row.put(rows.getMetaData().getColumnLabel(i), rows.getObject(i));
               }
               result.add(row);
            }
            return result;
         }
      }
   }



   private static HttpResponse<String> nativeDocumentStep(HttpClient http, String base, String token, String process, String uuid, Map<String, String> fields) throws Exception
   {
      String boundary = "controlled-document-boundary";
      String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"values\"\r\n\r\n" + JSON.writeValueAsString(fields) + "\r\n--" + boundary + "--\r\n";
      String route = "/qqq/v1/processes/" + process + (uuid == null ? "/init" : "/" + uuid + "/step/input");
      return http.send(HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.controlled").header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
   }



   private static long assertFinancialReports(Provider provider, HttpClient http, String base, String alice, String bob, CarlService service, String scenario) throws Exception
   {
      var day = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
      var from = day.withDayOfMonth(1);
      var through = from.plusMonths(1).minusDays(1);
      var rentals = new com.kof22.carlai.domain.RentalRecords(service);
      var propertyValues = new com.kof22.carlai.domain.RentalRecords.PropertyValues("USD", "Chester", java.math.BigDecimal.ONE, null, null, "Supplied owner", new java.math.BigDecimal("100000"), day, null, null, null, null, null);
      long property = rentals.createProperty("alice", UUID.randomUUID(), "Shared rental", "FAMILY", "Synthetic property evidence", propertyValues);
      long ambiguous = rentals.createProperty("alice", UUID.randomUUID(), "Ambiguous rental", "FAMILY", "Synthetic separate property", propertyValues);
      rentals.createProperty("alice", UUID.randomUUID(), "Ambiguous rental", "FAMILY", "Synthetic similarly named property", propertyValues);
      long hidden = rentals.createProperty("alice", UUID.randomUUID(), "PRIVATE_PROPERTY_NEVER_IN_CONTEXT", "PRIVATE", "Private property evidence", propertyValues);
      rentals.rentDue("alice", UUID.randomUUID(), property, null, from, new java.math.BigDecimal("1000"), "FAMILY", "Synthetic scheduled rent, no receipt");
      var tax = new com.kof22.carlai.domain.TaxPlanningRecords(service);
      long reference = tax.reference("alice", UUID.randomUUID(), new com.kof22.carlai.domain.TaxPlanningRecords.Reference("Supplied IRS reference", "FAMILY", "US", "OWNERSHIP_STRUCTURE", "https://www.irs.gov/publications/p527", "Synthetic edition", day.getYear(), from.minusDays(1), from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), null, null, null, null, "Supplied source identity, no fetch"));
      long alternative = tax.alternative("alice", UUID.randomUUID(), new com.kof22.carlai.domain.TaxPlanningRecords.Alternative("Supplied ownership alternative", "FAMILY", property, "Proposed structure only", "Supplied title evidence", "CPA review required", null, null, null, "Synthetic conditional alternative", List.of(reference)));
      var goals = new com.kof22.carlai.domain.FinancialGoals(service);
      long goal = goals.create("alice", "Later investing education", "INVESTMENT", 3, "FAMILY", "Debt then rental then investing; no investment stage selected");
      var expenses = new com.kof22.carlai.domain.ExpenseRecords(service);
      long expense = expenses.create("alice", UUID.randomUUID(), "Power forecast", "FAMILY", "Synthetic committed power", new com.kof22.carlai.domain.ExpenseRecords.Schedule("USD", com.kof22.carlai.domain.ExpenseForecast.Cadence.MONTHLY, from, null, new java.math.BigDecimal("150"), Map.of(), com.kof22.carlai.domain.ExpenseForecast.Kind.EXPENSE, com.kof22.carlai.domain.ExpenseForecast.Basis.COMMITTED, property));
      long baseline = rentals.report(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), UUID.randomUUID(), java.util.Set.of(property), from, through, day);
      long stress = new com.kof22.carlai.domain.RentalStress(service).create("alice", UUID.randomUUID(), "Reviewed vacancy scenario", "FAMILY", new com.kof22.carlai.domain.RentalStress.Assumptions(baseline, property, new java.math.BigDecimal("1000"), new java.math.BigDecimal("0.1"), new java.math.BigDecimal("200"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, day, "Synthetic vacancy and repair assumptions"));
      String conversation = base + "/conversations/" + UUID.randomUUID();
      assertEquals(200, send(http, conversation, "PUT", alice, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
      var proposal = JSON.createObjectNode();
      String message;
      switch(scenario)
      {
         case "rental" ->
         {
            proposal.put("operation", "RENTAL_REPORT").put("from", from.toString()).put("through", through.toString()).put("asOf", day.toString());
            proposal.putArray("properties").add(property);
            message = "Please report Shared rental from " + from + " through " + through + " as of " + day;
         }
         case "tax" ->
         {
            proposal.put("operation", "TAX_PACKET").put("taxYear", day.getYear()).put("asOf", day.toString());
            proposal.putArray("properties").add(property);
            proposal.putArray("alternatives").add(alternative);
            proposal.putArray("references").add(reference);
            message = "Prepare tax year " + day.getYear() + " packet as of " + day + " for Shared rental with Supplied ownership alternative and Supplied IRS reference";
         }
         case "investment", "extended-negative", "investment-return-units", "investment-fee-units" ->
         {
            proposal.put("operation", "INVESTMENT_SCENARIO").put("goal", goal).put("currency", "USD").put("firstMonth", java.time.YearMonth.from(from).toString()).put("months", 2).put("initialCapital", "1000.00").put("monthlyContribution", "100.00").put("monthlyReturn", "0.00").put("assetFeeRate", "0.00").put("initialFee", "0.00").put("monthlyFee", "0.00");
            message = "Explore Later investing education from " + java.time.YearMonth.from(from) + " for 2 months; initial capital USD 1000.00; monthly contribution USD 100.00; monthly return decimal fraction 0.00; asset fee rate decimal fraction 0.00; initial fee USD 0.00; monthly fee USD 0.00. Assumptions only, not approved contributions.";
         }
         case "balance" ->
         {
            proposal.put("operation", "BALANCE_SHEET").put("asOf", day.toString()).put("maximumAgeDays", 30).putArray("accounts");
            proposal.putArray("properties").addObject().put("property", property).put("valuation", "PROPERTY_ESTIMATE");
            message = "Show selected balance sheet as of " + day + " with maximum age 30 days for Shared rental using PROPERTY_ESTIMATE";
         }
         case "expense" ->
         {
            proposal.put("operation", "EXPENSE_FORECAST").put("from", from.toString()).put("through", through.toString()).put("asOf", from.toString()).put("currency", "USD");
            proposal.putArray("expenses").add(expense);
            proposal.putArray("actuals");
            message = "Forecast Power forecast in USD from " + from + " through " + through + " as of " + from;
         }
         default -> throw new AssertionError("Unknown extended scenario");
      }
      proposal.put("evidence", message);
      provider.enqueue("end_turn", proposal.toString());
      String path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      String input = JSON.writeValueAsString(Map.of("input", Map.of("message", message)));
      assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
      var response = await(http, path, alice);
      assertEquals("PARTIAL", response.path("status").asText(), response.toString());
      long artifact = response.path("artifactId").asLong();
      var saved = service.artifact("bob", artifact);
      var facts = JSON.readTree(saved.get("facts").toString());
      assertEquals("NOT_REQUESTED", saved.get("narration_state"));
      assertTrue(response.path("artifact").path("calculation").isObject(), response.toString());
      switch(scenario)
      {
         case "rental" -> assertEquals(0, new java.math.BigDecimal("1000").compareTo(facts.path("calculation").path("wholePropertyPortfolioByCurrency").path("USD").path("knownScheduledRent").decimalValue()));
         case "tax" ->
         {
            assertEquals("UNDETERMINED", facts.path("taxCalculationStatus").asText());
            assertEquals("NONE", facts.path("ruleQualification").asText());
            assertEquals(1, facts.path("conditionalAlternatives").size());
            assertEquals(1, response.path("artifact").path("calculation").path("conditionalAlternatives").size());
            assertTrue(response.path("artifact").path("calculation").path("sourceReviews").toString().contains("https://www.irs.gov/publications/p527"));
            assertTrue(facts.path("sourceReviews").toString().contains("UNQUALIFIED_REFERENCE"));
         }
         case "investment", "extended-negative", "investment-return-units", "investment-fee-units" ->
         {
            assertEquals("EDUCATIONAL_ONLY", facts.path("projection").path("recommendationStatus").asText());
            assertTrue(facts.toString().contains("Owner has not selected investment-stage readiness"));
            assertEquals(0, new java.math.BigDecimal("1200").compareTo(facts.path("projection").path("endingHypotheticalValue").decimalValue()));
         }
         case "balance" -> assertEquals(0, new java.math.BigDecimal("100000").compareTo(facts.path("knownSelectedNetWorthByCurrency").path("USD").decimalValue()));
         case "expense" ->
         {
            assertEquals("USD", facts.path("currency").asText());
            assertEquals("USD", response.path("artifact").path("calculation").path("currency").asText());
            assertEquals(0, new java.math.BigDecimal("150").compareTo(facts.path("projection").path("knownScheduledExpenses").decimalValue()));
         }
         default -> throw new AssertionError("Unknown extended scenario");
      }
      int calls = provider.requests.size();
      assertEquals(response, JSON.readTree(send(http, path, "PUT", alice, input).body()));
      assertEquals(response, JSON.readTree(send(http, path, "GET", bob, null).body()));
      assertEquals(calls, provider.requests.size());
      assertTrue(provider.requests.stream().noneMatch(body -> body.contains("PRIVATE_PROPERTY_NEVER_IN_CONTEXT")));
      if(scenario.equals("rental"))
      {
         String stressMessage = "Replay Reviewed vacancy scenario using its saved reviewed assumptions";
         provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "RENTAL_STRESS", "scenario", stress, "evidence", stressMessage)));
         String stressPath = conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, stressPath, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", stressMessage)))).statusCode());
         var replay = await(http, stressPath, alice);
         assertEquals("PARTIAL", replay.path("status").asText(), replay.toString());
         var replayFacts = JSON.readTree(service.artifact("alice", replay.path("artifactId").asLong()).get("facts").toString());
         assertEquals(0, new java.math.BigDecimal("100").compareTo(replayFacts.path("hypotheticalVacancyLoss").decimalValue()));
         assertEquals(0, new java.math.BigDecimal("200").compareTo(replayFacts.path("additionalRepair").decimalValue()));
      }
      if(scenario.equals("investment-return-units") || scenario.equals("investment-fee-units"))
      {
         String field = scenario.equals("investment-return-units") ? "monthlyReturn" : "assetFeeRate";
         String label = scenario.equals("investment-return-units") ? "monthly return" : "asset fee rate";
         long before = storedArtifactCount();
         for(String suffix : List.of("%", " %", " percent", " basis points"))
         {
            String evidence = message.replace(label + " decimal fraction 0.00", label + " 0.05" + suffix);
            proposal.put(field, "0.05").put("evidence", evidence);
            provider.enqueue("end_turn", proposal.toString());
            String bad = conversation + "/workflows/conversation/" + UUID.randomUUID();
            send(http, bad, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", evidence))));
            assertEquals("CLARIFICATION", await(http, bad, alice).path("artifact").path("kind").asText(), "A percent/basis-point rate is not the same decimal fraction");
            evidence = message.replace(label + " decimal fraction 0.00", label + " decimal fraction 0.05" + suffix);
            proposal.put("evidence", evidence);
            provider.enqueue("end_turn", proposal.toString());
            String conflictingUnit = conversation + "/workflows/conversation/" + UUID.randomUUID();
            send(http, conflictingUnit, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", evidence))));
            assertEquals("CLARIFICATION", await(http, conflictingUnit, alice).path("artifact").path("kind").asText(), "Contradictory explicit fraction/percentage units remain ambiguous");
         }
         assertEquals(before, storedArtifactCount());
         String explicit = message.replace(label + " decimal fraction 0.00", label + " decimal fraction 0.05");
         proposal.put(field, "0.05").put("evidence", explicit);
         provider.enqueue("end_turn", proposal.toString());
         String valid = conversation + "/workflows/conversation/" + UUID.randomUUID();
         send(http, valid, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", explicit))));
         var calculated = await(http, valid, alice);
         assertEquals("PARTIAL", calculated.path("status").asText(), calculated.toString());
         var calculatedFacts = JSON.readTree(service.artifact("alice", calculated.path("artifactId").asLong()).get("facts").toString());
         String expected = field.equals("monthlyReturn") ? "1307.50" : "1097.50";
         assertEquals(0, new java.math.BigDecimal(expected).compareTo(calculatedFacts.path("projection").path("endingHypotheticalValue").decimalValue()));
      }
      if(scenario.equals("extended-negative"))
      {
         long before = storedArtifactCount();
         for(String invalid : List.of("1,000.00", "-1000.00", "21000.00"))
         {
            String evidence = message.replace("1000.00", invalid);
            proposal.put("evidence", evidence);
            provider.enqueue("end_turn", proposal.toString());
            String bad = conversation + "/workflows/conversation/" + UUID.randomUUID();
            send(http, bad, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", evidence))));
            assertEquals("CLARIFICATION", await(http, bad, alice).path("artifact").path("kind").asText());
         }
         proposal.put("evidence", message).put("months", 12);
         provider.enqueue("end_turn", proposal.toString());
         String wrongHorizon = conversation + "/workflows/conversation/" + UUID.randomUUID();
         send(http, wrongHorizon, "PUT", alice, input);
         assertEquals("CLARIFICATION", await(http, wrongHorizon, alice).path("artifact").path("kind").asText());
         proposal.put("months", 2);
         proposal.put("evidence", message).putNull("monthlyReturn");
         provider.enqueue("end_turn", proposal.toString());
         String missing = conversation + "/workflows/conversation/" + UUID.randomUUID();
         send(http, missing, "PUT", alice, input);
         assertEquals("CLARIFICATION", await(http, missing, alice).path("artifact").path("kind").asText());
         String ambiguousMessage = "Report Ambiguous rental from " + from + " through " + through + " as of " + day;
         var ambiguousProposal = JSON.createObjectNode().put("operation", "RENTAL_REPORT").put("from", from.toString()).put("through", through.toString()).put("asOf", day.toString()).put("evidence", ambiguousMessage);
         ambiguousProposal.putArray("properties").add(ambiguous);
         provider.enqueue("end_turn", ambiguousProposal.toString());
         String ambiguousPath = conversation + "/workflows/conversation/" + UUID.randomUUID();
         send(http, ambiguousPath, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", ambiguousMessage))));
         assertEquals("CLARIFICATION", await(http, ambiguousPath, alice).path("artifact").path("kind").asText());
         ambiguousProposal.putNull("asOf");
         provider.enqueue("end_turn", ambiguousProposal.toString());
         String missingDate = conversation + "/workflows/conversation/" + UUID.randomUUID();
         send(http, missingDate, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", ambiguousMessage))));
         assertEquals("CLARIFICATION", await(http, missingDate, alice).path("artifact").path("kind").asText());
         String privateMessage = "Rental report record " + hidden + " from " + from + " through " + through + " as of " + day;
         var privateProposal = JSON.createObjectNode().put("operation", "RENTAL_REPORT").put("from", from.toString()).put("through", through.toString()).put("asOf", day.toString()).put("evidence", privateMessage);
         privateProposal.putArray("properties").add(hidden);
         provider.enqueue("end_turn", privateProposal.toString());
         String denied = conversation + "/workflows/conversation/" + UUID.randomUUID();
         send(http, denied, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", privateMessage))));
         assertEquals("UNKNOWN", await(http, denied, alice).path("status").asText());
         assertEquals(before, storedArtifactCount());
         goals.context("alice", goal, "USD", new com.kof22.carlai.domain.InvestmentPlanning.Context(false, 2, null, null, false, false, false, "Changed synthetic context"), "New owner context, no investment readiness");
         assertEquals(true, service.artifact("alice", artifact).get("stale"));
         try(var connection = java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement())
         {
            statement.execute("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
         }
         calls = provider.requests.size();
         assertEquals(404, send(http, path, "GET", alice, null).statusCode());
         assertEquals(404, send(http, conversation + "/workflows/conversation/" + UUID.randomUUID(), "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Explain that scenario", "replyTo", path.substring(path.lastIndexOf('/') + 1))))).statusCode());
         assertEquals(calls, provider.requests.size());
         return 0;
      }
      return artifact;
   }



   private static long storedArtifactCount() throws Exception
   {
      try(var connection = java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM carl_artifact"))
      {
         assertTrue(result.next());
         return result.getLong(1);
      }
   }



   /** FAT-18: controlled SDK proposals cross verified Alice/Bob transport, durable restart and native QQQ. */
   @org.junit.jupiter.api.Test
   void sharedFamilyConversationRetainsTasksHistoryAndContendedRevisionsAcrossRestart() throws Exception
   {
      try(var provider = new Provider(); var http = HttpClient.newHttpClient())
      {
         var data = NativeDatabases.source(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
         AgentMigrations.migrate(data);
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("TRUNCATE carl_household,carl_request,agent_client_conversation,token_usage RESTART IDENTITY CASCADE");
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic FAT-18 family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',false)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            sql.execute("INSERT INTO carl_identity(issuer,subject,member_id) VALUES('" + ISSUER + "','alice-subject',1),('" + ISSUER + "','bob-subject',2)");
         }
         var configuration = com.kof22.agentadmin.bootstrap.NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + DATABASE.getJdbcUrl(), "--kof22.agent.db.username=" + DATABASE.getUsername(), "--kof22.agent.db.password=" + DATABASE.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.qqq.password=synthetic-unused-bootstrap", "--kof22.agent.anthropic-api-key=synthetic-no-provider", "--kof22.agent.anthropic-base-url=" + provider.url(), "--kof22.agent.model.id=claude-sonnet-5", "--kof22.agent.limits.max-output-tokens=40", "--kof22.agent.limits.turn-timeout=PT10S").configuration();
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var pair = generator.generateKeyPair();
         var key = (RSAPublicKey) pair.getPublic();
         var privateKey = (RSAPrivateKey) pair.getPrivate();
         var algorithm = Algorithm.RSA256(key, privateKey);
         var jwk = Jwk.fromValues(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(key.getModulus().toByteArray()), "e", unsigned(key.getPublicExponent().toByteArray())));
         String alice = token(algorithm, "alice-subject");
         String bob = token(algorithm, "bob-subject");
         var service = new CarlService(data, Clock.systemUTC());
         var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
         var scope = new CarlService.Scope("alice", java.util.Set.of("alice", "bob"));
         long debt = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Family review debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "FAMILY", "Synthetic statement");
         var debts = new com.kof22.carlai.domain.DebtPlans(service);
         debts.terms("alice", debt, date, new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Dated synthetic statement");
         long source = debts.compare(scope, UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human reviewed total monthly budget");
         assertTrue(!service.artifact("alice", source).get("status_label").toString().startsWith("Incomplete"));
         var lifecycle = new com.kof22.carlai.domain.PlanLifecycle(service);
         String conversation = "/conversations/" + UUID.randomUUID();
         String progressRequest = "/workflows/conversation/" + UUID.randomUUID();
         long plan;
         String step;
         JsonNode beforeRestart;
         JsonNode progressBeforeRestart;
         try(var host = new FamilyHost(configuration, jwk, data))
         {
            String url = host.base + conversation;
            assertEquals(200, send(http, url, "PUT", alice, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
            assertEquals(200, send(http, url, "GET", bob, null).statusCode());
            var proposal = JSON.createObjectNode().put("operation", "PLAN_CREATE").put("sourceArtifact", source).put("title", "Family review plan").put("reason", "Human selection");
            var result = planTurn(provider, http, url, alice, proposal, "Create a shared draft plan titled Family review plan from comparison " + source + ".");
            assertEquals("PLAN_CREATE", result.path("artifact").path("kind").asText(), result.toString());
            plan = result.path("artifact").path("plan").path("plan").path("id").asLong();
            assertTrue(plan > 0);
            proposal.removeAll();
            proposal.put("operation", "PLAN_STEP").put("plan", plan).put("expectedVersion", 1).putNull("step").put("title", "Review family checklist").put("assignee", 2).put("due", date.plusDays(2).toString()).put("location", "Home").putNull("dependency").put("reason", "Human task");
            result = planTurn(provider, http, url, alice, proposal, "Add a step to plan Family review plan version 1: Review family checklist; assign to Synthetic B; due " + date.plusDays(2) + "; location Home.");
            assertEquals(2, result.path("artifact").path("plan").path("plan").path("version").asInt(), result.toString());
            step = result.path("artifact").path("plan").path("steps").get(0).path("id").asText();
            proposal.removeAll();
            proposal.put("operation", "PLAN_CHECK_IN").put("plan", plan).put("expectedVersion", 2).put("step", step).put("status", "BLOCKED").put("note", "I am blocked waiting for the checklist").putNull("evidence");
            result = planTurn(provider, http, url, bob, proposal, "Check in on plan Family review plan version 2 step Review family checklist: I am blocked waiting for the checklist.");
            assertEquals("BLOCKED", result.path("artifact").path("plan").path("steps").get(0).path("status").asText(), result.toString());
            proposal.put("expectedVersion", 3).put("status", "REPORTED_COMPLETE").put("note", "I completed the checklist review");
            result = planTurn(provider, http, url, bob, proposal, "Check in on plan Family review plan version 3 step Review family checklist: I completed the checklist review.");
            assertEquals("REPORTED_COMPLETE", result.path("artifact").path("plan").path("steps").get(0).path("status").asText(), result.toString());
            proposal.removeAll();
            proposal.put("operation", "PLAN_AGREE").put("plan", plan).put("expectedVersion", 4).put("reason", "Human agreement");
            result = planTurn(provider, http, url, alice, proposal, "I agree to plan Family review plan version 4.");
            assertEquals("AGREED", result.path("artifact").path("plan").path("plan").path("state").asText(), result.toString());
            proposal.removeAll();
            proposal.put("operation", "PLAN_PROGRESS").put("plan", plan).put("expectedVersion", 5).put("pdf", false);
            provider.enqueue("end_turn", proposal.toString());
            assertEquals(200, send(http, url + progressRequest, "PUT", bob, JSON.writeValueAsString(Map.of("input", Map.of("message", "Show progress for plan Family review plan version 5.")))).statusCode());
            progressBeforeRestart = await(http, url + progressRequest, bob);
            assertEquals("PLAN_PROGRESS", progressBeforeRestart.path("artifact").path("kind").asText(), progressBeforeRestart.toString());
            beforeRestart = JSON.valueToTree(lifecycle.get("bob", plan));
            assertFamilyHistory(beforeRestart, source, step, date.plusDays(2));
            com.kof22.agentadmin.CarlFamilyPlanQqqAssertions.assertCurrent(configuration, host.components, data, jwk, key, privateKey, beforeRestart, false);
         }
         int restartCalls = provider.requests.size();
         // New factory, runtime, workers, client store and HTTP listener; the PostgreSQL container is retained.
         try(var host = new FamilyHost(configuration, jwk, data))
         {
            String url = host.base + conversation;
            assertEquals(200, send(http, url, "GET", bob, null).statusCode());
            assertEquals(progressBeforeRestart, JSON.readTree(send(http, url + progressRequest, "GET", bob, null).body()));
            var restartedService = new CarlService(data, Clock.systemUTC());
            var restartedPlans = new com.kof22.carlai.domain.PlanLifecycle(restartedService);
            assertEquals(beforeRestart, JSON.valueToTree(restartedPlans.get("bob", plan)));
            assertEquals(restartCalls, provider.requests.size(), "Durable reads must not replay inference");
            assertConcurrentFamilyRevision(provider, http, host.base, conversation, alice, plan, step, date);
            debts.terms("alice", debt, date, new java.math.BigDecimal("900.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Updated dated human statement");
            assertEquals(Boolean.TRUE, service.artifact("alice", source).get("stale"));
            long refreshed = debts.compare(scope, UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human reviewed updated budget");
            var proposal = JSON.createObjectNode().put("operation", "PLAN_REBASE").put("plan", plan).put("expectedVersion", 6).put("sourceArtifact", refreshed).put("reason", "Updated statement");
            var result = planTurn(provider, http, url, alice, proposal, "Rebase plan Family review plan version 6 using comparison " + refreshed + ".");
            assertEquals(7, result.path("artifact").path("plan").path("plan").path("version").asInt(), result.toString());
            assertEquals(refreshed, result.path("artifact").path("plan").path("plan").path("source_artifact_id").asLong());
            assertEquals("DRAFT", result.path("artifact").path("plan").path("plan").path("state").asText());
            var current = JSON.valueToTree(restartedPlans.get("bob", plan));
            assertEquals(7, current.path("history").size());
            for(int i = 0; i < 5; i++)
            {
               assertEquals(beforeRestart.path("history").get(i), current.path("history").get(i), "Old revisions are immutable");
            }
            com.kof22.agentadmin.CarlFamilyPlanQqqAssertions.assertCurrent(configuration, host.components, data, jwk, key, privateKey, current, false);
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT version,actor_id FROM carl_plan_version WHERE plan_id=" + plan + " ORDER BY version"))
            {
               long[] actors = {1, 1, 2, 2, 1, 1, 1};
               for(int version = 1; version <= actors.length; version++)
               {
                  assertTrue(rows.next());
                  assertEquals(version, rows.getInt(1));
                  assertEquals(actors[version - 1], rows.getLong(2), "Verified transport actor owns the durable revision");
               }
               assertTrue(!rows.next());
            }
            try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("SELECT (SELECT count(*) FROM carl_transaction)+(SELECT count(*) FROM carl_work_item)"))
            {
               assertTrue(rows.next());
               assertEquals(0, rows.getLong(1), "Conversation task completion must not create a financial transaction or vendor operation");
            }
            try(var c = data.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
            }
            int revokedCalls = provider.requests.size();
            assertEquals(404, send(http, url + progressRequest, "GET", bob, null).statusCode());
            assertEquals(404, send(http, url + "/workflows/conversation/" + UUID.randomUUID(), "PUT", bob, JSON.writeValueAsString(Map.of("input", Map.of("message", "Show progress for plan Family review plan version 7.")))).statusCode());
            org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class, () -> restartedPlans.get("bob", plan));
            org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class, () -> restartedPlans.get("alice", plan));
            com.kof22.agentadmin.CarlFamilyPlanQqqAssertions.assertCurrent(configuration, host.components, data, jwk, key, privateKey, current, true);
            assertEquals(revokedCalls, provider.requests.size(), "Revoked member cannot retrieve old versions or invoke inference");
         }
         assertEquals(9, provider.requests.size(), "Bounded synthetic SDK requests, not live model acceptance");
         assertTrue(provider.replies.isEmpty());
      }
   }



   private static void assertFamilyHistory(JsonNode saved, long source, String step, LocalDate due) throws Exception
   {
      assertEquals(5, saved.path("plan").path("version").asInt());
      assertEquals(source, saved.path("plan").path("source_artifact_id").asLong());
      assertEquals(5, saved.path("history").size());
      var task = saved.path("steps").get(0);
      assertEquals(step, task.path("id").asText());
      assertEquals(2, task.path("assignee_id").asInt());
      assertEquals(due.toString(), task.path("due_date").asText());
      assertEquals("Home", task.path("location").asText());
      assertEquals("REPORTED_COMPLETE", task.path("status").asText());
      assertTrue(task.path("evidence_record_id").isNull());
      var blocked = JSON.readTree(saved.path("history").get(2).path("snapshot").asText()).path("steps").get(0);
      assertEquals("BLOCKED", blocked.path("status").asText());
      assertEquals("I am blocked waiting for the checklist", blocked.path("checkin").asText());
      var completed = JSON.readTree(saved.path("history").get(3).path("snapshot").asText()).path("steps").get(0);
      assertEquals("REPORTED_COMPLETE", completed.path("status").asText());
      assertEquals("I completed the checklist review", completed.path("checkin").asText());
   }



   private static void assertConcurrentFamilyRevision(Provider provider, HttpClient http, String base, String conversation, String alice, long plan, String step, LocalDate date) throws Exception
   {
      String secondConversation = base + "/conversations/" + UUID.randomUUID();
      assertEquals(200, send(http, secondConversation, "PUT", alice, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      provider.beforeResponse = () ->
      {
         entered.countDown();
         try
         {
            if(!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            {
               throw new AssertionError("Contending request did not start within the SDK bound");
            }
         }
         catch(InterruptedException e)
         {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
         }
      };
      var proposal = JSON.createObjectNode().put("operation", "PLAN_STEP").put("plan", plan).put("expectedVersion", 5).put("step", step).put("title", "Review family checklist").put("assignee", 2).put("due", date.plusDays(3).toString()).put("location", "Kitchen").putNull("dependency").put("reason", "Human revised task");
      provider.enqueue("end_turn", proposal.toString());
      String first = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      String firstInput = JSON.writeValueAsString(Map.of("input", Map.of("message", "Revise step Review family checklist in plan Family review plan version 5; assign to Synthetic B; due " + date.plusDays(3) + "; location Kitchen.")));
      assertEquals(200, send(http, first, "PUT", alice, firstInput).statusCode());
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      proposal.put("due", date.plusDays(4).toString()).put("location", "Office");
      provider.enqueue("end_turn", proposal.toString());
      String second = secondConversation + "/workflows/conversation/" + UUID.randomUUID();
      try
      {
         assertEquals(200, send(http, second, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Revise step Review family checklist in plan Family review plan version 5; assign to Synthetic B; due " + date.plusDays(4) + "; location Office.")))).statusCode());
      }
      finally
      {
         release.countDown();
      }
      var firstResult = await(http, first, alice);
      var secondResult = await(http, second, alice);
      boolean firstWon = firstResult.path("artifact").path("kind").asText().equals("PLAN_STEP");
      var winner = firstWon ? firstResult : secondResult;
      var loser = firstWon ? secondResult : firstResult;
      assertEquals("PLAN_STEP", winner.path("artifact").path("kind").asText(), winner.toString());
      assertEquals(6, winner.path("artifact").path("plan").path("plan").path("version").asInt());
      var task = winner.path("artifact").path("plan").path("steps").get(0);
      assertEquals(firstWon ? "Kitchen" : "Office", task.path("location").asText());
      assertEquals(date.plusDays(firstWon ? 3 : 4).toString(), task.path("due_date").asText());
      assertTrue(loser.path("artifact").path("kind").asText().equals("CLARIFICATION") || loser.path("status").asText().equals("UNKNOWN"), loser.toString());
      int calls = provider.requests.size();
      assertEquals(firstResult, JSON.readTree(send(http, first, "PUT", alice, firstInput).body()));
      assertEquals(calls, provider.requests.size());
   }

   private static final class FamilyHost implements AutoCloseable
   {
      private final com.kof22.agentadmin.bootstrap.NativeAgentRuntime.Components components;
      private final com.kof22.agentcore.runtime.AgentRuntime inference;
      private final ClientService clients;
      private final Javalin server;
      private final String base;

      private FamilyHost(com.kof22.agentadmin.configuration.NativeAgentConfiguration configuration, Jwk jwk, javax.sql.DataSource data) throws Exception
      {
         components = AgentApplication.components();
         components.validate(configuration);
         inference = components.runtime(configuration);
         var access = components.familyAccess();
         var identity = new ClientIdentity(ISSUER, "carl-family", access, ignored -> jwk);
         var store = new ClientStore(data);
         clients = new ClientService(store, org.mockito.Mockito.mock(SessionManager.class), identity, access, DataProtection.defaults(), Duration.ofSeconds(10), components.clientWorkflows(com.kof22.agentadmin.bootstrap.NativeStores.create(configuration.database())));
         server = startClientServer(identity, store, clients);
         base = "http://127.0.0.1:" + server.port() + "/api/agent/v1";
      }



      @Override
      public void close() throws Exception
      {
         server.stop();
         clients.close();
         inference.close();
      }
   }

   /** Bind once through Jetty before configuring the real servlet's exact authority. */
   private static Javalin startClientServer(ClientIdentity identity, ClientStore store, ClientService clients)
   {
      var delegate = new java.util.concurrent.atomic.AtomicReference<ClientServlet>();
      var transport = new jakarta.servlet.http.HttpServlet()
      {
         private static final long serialVersionUID = 1L;

         @Override
         public void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws jakarta.servlet.ServletException, java.io.IOException
         {
            var current = delegate.get();
            if(current == null)
            {
               ((jakarta.servlet.http.HttpServletResponse) response).sendError(jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE);
               return;
            }
            current.service(request, response);
         }
      };
      var server = Javalin.create(config -> config.jetty.modifyServletContextHandler(handler -> handler.addServlet(new ServletHolder(transport), "/api/agent/v1/*"))).start("127.0.0.1", 0);
      delegate.set(new ClientServlet(identity, store, clients, "http://127.0.0.1:" + server.port(), () -> true));
      return server;
   }



   private static long[] assertPlanLifecycle(Provider provider, HttpClient http, String base, String conversation, String alice, CarlService service, boolean negative) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      var scope = CarlService.Scope.privateFor("alice");
      long debt = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Lifecycle debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", debt, date, new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Dated synthetic statement");
      long source = debts.compare(scope, UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human reviewed total monthly budget");
      assertTrue(!service.artifact("alice", source).get("status_label").toString().startsWith("Incomplete"), "Use the actual complete DebtPlans result, never relabel a source");
      var proposal = JSON.createObjectNode().put("operation", "PLAN_CREATE").put("sourceArtifact", source).put("title", "Lifecycle plan").put("reason", "Human selection");
      String create = "Create a draft plan titled Lifecycle plan from comparison " + source + ".";
      var result = planTurn(provider, http, base + conversation, alice, proposal, create);
      assertEquals("PLAN_CREATE", result.path("artifact").path("kind").asText(), result.toString());
      long plan = result.path("artifact").path("plan").path("plan").path("id").asLong();
      assertTrue(plan > 0);
      proposal.removeAll();
      proposal.put("operation", "PLAN_STEP").put("plan", plan).put("expectedVersion", 1).putNull("step");
      proposal.put("title", "Review payment checklist").put("assignee", 1).put("due", date.toString()).put("location", "Home").putNull("dependency").put("reason", "Human task");
      String task = "Add a step to plan Lifecycle plan version 1: Review payment checklist; assign to Synthetic A; due " + date + "; location Home.";
      if(negative)
      {
         for(String denied : List.of("Do not " + task, "What if I " + task, "Bob said: \"" + task + "\"", "Explain the plan", task.replace("assign to Synthetic A", "assign to Synthetic B"), task.replace("location Home", "consider Home"), task.replace("version 1", "version 11"), task + " Because this is still under discussion, but don't add the step."))
         {
            var rejected = planTurn(provider, http, base + conversation, alice, proposal, denied);
            if(denied.equals("Explain the plan"))
            {
               assertEquals("ANSWER", rejected.path("artifact").path("kind").asText(), rejected.toString());
               assertTrue(!rejected.path("artifact").has("proposedOperation"));
               assertTrue(!rejected.path("artifact").has("confirmationRequired"));
            }
            else
            {
               assertEquals("CLARIFICATION", rejected.path("artifact").path("kind").asText(), rejected.toString());
               assertEquals("PLAN_STEP", rejected.path("artifact").path("proposedOperation").asText());
               assertTrue(rejected.path("artifact").path("confirmationRequired").asBoolean());
            }
            assertEquals(1, ((Number) ((Map<?, ?>) new com.kof22.carlai.domain.PlanLifecycle(service).get("alice", plan).get("plan")).get("version")).intValue());
         }
         return new long[]{source, plan};
      }
      result = planTurn(provider, http, base + conversation, alice, proposal, task);
      assertEquals(2, result.path("artifact").path("plan").path("plan").path("version").asInt(), result.toString());
      String step = result.path("artifact").path("plan").path("steps").get(0).path("id").asText();
      assertEquals(UUID.nameUUIDFromBytes(("carl-conversation-step:" + result.path("artifact").path("logicalRequest").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), step);
      proposal.removeAll();
      proposal.put("operation", "PLAN_AGREE").put("plan", plan).put("expectedVersion", 2).put("reason", "Explicit human agreement");
      result = planTurn(provider, http, base + conversation, alice, proposal, "I agree to plan Lifecycle plan version 2.");
      assertEquals("AGREED", result.path("artifact").path("plan").path("plan").path("state").asText(), result.toString());
      proposal.removeAll();
      proposal.put("operation", "PLAN_EXPECTATION").put("plan", plan).put("expectedVersion", 3).put("step", step).put("kind", "CASH_PAYMENT").put("account", debt).put("amount", "100.00").put("from", date.toString()).put("through", date.toString()).put("reason", "Human expectation");
      result = planTurn(provider, http, base + conversation, alice, proposal, "Record an expectation for plan Lifecycle plan version 3 step Review payment checklist: CASH_PAYMENT from Lifecycle debt USD 100.00 from " + date + " through " + date + ".");
      long expectation = result.path("artifact").path("expectation").path("id").asLong();
      assertTrue(expectation > 0, result.toString());
      long transaction = new com.kof22.carlai.domain.BudgetRecords(service).manualTransaction("alice", UUID.randomUUID(), debt, date, new java.math.BigDecimal("-100.00"), "UNCLASSIFIED", "Synthetic payment", "Selected observed cash movement", "Human evidence");
      proposal.removeAll();
      proposal.put("operation", "PLAN_EFFECTS").put("plan", plan).put("expectedVersion", 3).put("expectation", expectation).put("evidence", "Selected observed cash movement").putArray("transactions").add(transaction);
      result = planTurn(provider, http, base + conversation, alice, proposal, "Compare observed effects for plan Lifecycle plan version 3 expectation " + expectation + " using transaction " + transaction + ".");
      long effectArtifact = result.path("artifactId").asLong();
      assertEquals("MATCH", JSON.readTree(service.artifact("alice", effectArtifact).get("facts").toString()).path("outcome").asText());
      assertEquals("TODO", JSON.valueToTree(new com.kof22.carlai.domain.PlanLifecycle(service).get("alice", plan)).path("steps").get(0).path("status").asText());
      proposal.removeAll();
      proposal.put("operation", "PLAN_CHECK_IN").put("plan", plan).put("expectedVersion", 3).put("step", step).put("status", "REPORTED_COMPLETE").put("note", "I completed the review").putNull("evidence");
      result = planTurn(provider, http, base + conversation, alice, proposal, "Check in on plan Lifecycle plan version 3: I completed the review for step Review payment checklist.");
      assertEquals("REPORTED_COMPLETE", result.path("artifact").path("plan").path("steps").get(0).path("status").asText(), result.toString());
      proposal.removeAll();
      proposal.put("operation", "PLAN_PROGRESS").put("plan", plan).put("expectedVersion", 4).put("pdf", true);
      result = planTurn(provider, http, base + conversation, alice, proposal, "Show progress and PDF for plan Lifecycle plan version 4.");
      assertEquals("application/pdf", result.path("artifact").path("export").path("mediaType").asText(), result.toString());
      byte[] pdf = Base64.getDecoder().decode(result.path("artifact").path("export").path("contentBase64").asText());
      assertTrue(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-"));
      debts.terms("alice", debt, date, new java.math.BigDecimal("900.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Updated dated human statement");
      long refreshed = debts.compare(scope, UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human reviewed updated total monthly budget");
      proposal.removeAll();
      proposal.put("operation", "PLAN_REBASE").put("plan", plan).put("expectedVersion", 4).put("sourceArtifact", refreshed).put("reason", "Updated statement");
      result = planTurn(provider, http, base + conversation, alice, proposal, "Rebase plan Lifecycle plan version 4 using comparison " + refreshed + ".");
      assertEquals(5, result.path("artifact").path("plan").path("plan").path("version").asInt(), result.toString());
      assertEquals("DRAFT", result.path("artifact").path("plan").path("plan").path("state").asText());
      assertEquals(refreshed, result.path("artifact").path("plan").path("plan").path("source_artifact_id").asLong());
      proposal.removeAll();
      proposal.put("operation", "PLAN_AGREE").put("plan", plan).put("expectedVersion", 4).put("reason", "Old agreement cannot select new version");
      result = planTurn(provider, http, base + conversation, alice, proposal, "I agree to plan Lifecycle plan version 4.");
      assertEquals("CLARIFICATION", result.path("artifact").path("kind").asText());
      return new long[]{refreshed, plan};
   }



   private static void assertPlanCalendar(Provider provider, SharedCalendar calendar, HttpClient http, String conversation, String alice, CarlService service) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      long debt = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Calendar debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", debt, date, new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Dated synthetic statement");
      long source = debts.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human budget");
      var proposal = JSON.createObjectNode().put("operation", "PLAN_CREATE").put("sourceArtifact", source).put("title", "Calendar plan").put("reason", "Human selection");
      var result = planTurn(provider, http, conversation, alice, proposal, "Create a draft plan titled Calendar plan from comparison " + source + ".");
      long plan = result.path("artifact").path("createdPlanId").asLong();
      proposal.removeAll();
      proposal.put("operation", "PLAN_STEP").put("plan", plan).put("expectedVersion", 1).putNull("step").put("title", "Review shared checklist").put("assignee", 1).put("due", date.toString()).put("location", "Home").putNull("dependency").put("reason", "Human task");
      result = planTurn(provider, http, conversation, alice, proposal, "Add a step to plan Calendar plan version 1: Review shared checklist; assign to Synthetic A; due " + date + "; location Home.");
      String step = result.path("artifact").path("plan").path("steps").get(0).path("id").asText();
      proposal.removeAll();
      proposal.put("operation", "PLAN_AGREE").put("plan", plan).put("expectedVersion", 2).put("reason", "Human agreement");
      result = planTurn(provider, http, conversation, alice, proposal, "I agree to plan Calendar plan version 2.");
      assertEquals("AGREED", result.path("artifact").path("plan").path("plan").path("state").asText());
      proposal.removeAll();
      proposal.put("operation", "PLAN_CALENDAR").put("plan", plan).put("expectedVersion", 3).put("step", step).put("collection", "reminders").put("action", "PUBLISH").putNull("priorRequest");
      result = planTurn(provider, http, conversation, alice, proposal, "What if I publish reminders for plan Calendar plan version 3 step Review shared checklist?");
      assertEquals("CLARIFICATION", result.path("artifact").path("kind").asText());
      assertEquals(0, calendar.writes.get());
      result = planTurn(provider, http, conversation, alice, proposal, "Publish reminders for plan Calendar plan version 3 step Review shared checklist.");
      assertEquals("COMPLETE", result.path("artifact").path("calendar").path("state").asText(), result.toString());
      assertEquals(1, calendar.writes.get());
      Instant completed = Instant.now();
      calendar.remote.set(com.kof22.carlai.calendar.PlanCalendarCodec.reminder(new com.kof22.carlai.calendar.PlanCalendarCodec.Item(UUID.fromString(step), 3, "Review shared checklist", "Synthetic human changed shared checkbox", "Home", completed), date, completed));
      proposal.put("action", "SYNCHRONIZE");
      result = planTurn(provider, http, conversation, alice, proposal, "Synchronize reminders for plan Calendar plan version 3 step Review shared checklist.");
      assertEquals("CHANGED", result.path("artifact").path("calendar").path("state").asText(), result.toString());
      long observation = result.path("artifact").path("calendar").path("reminderObservation").asLong();
      assertTrue(observation > 0);
      assertEquals("TODO", result.path("artifact").path("plan").path("steps").get(0).path("status").asText(), "Remote checkbox is pending human review");
      proposal.removeAll();
      proposal.put("operation", "REMINDER_REVIEW").put("plan", plan).put("expectedVersion", 3).put("observation", observation).put("decision", "ACCEPT_REPORTED_COMPLETE").put("note", "Human review of shared checkbox");
      result = planTurn(provider, http, conversation, alice, proposal, "Review plan Calendar plan version 3 observation " + observation + ": accept reported completion. Human review of shared checkbox.");
      assertEquals(4, result.path("artifact").path("plan").path("plan").path("version").asInt());
      assertEquals("REPORTED_COMPLETE", result.path("artifact").path("plan").path("steps").get(0).path("status").asText(), result.toString());
      assertEquals(1, calendar.writes.get(), "Human review never causes a financial action or another calendar write");
   }



   private static long assertGoalConversation(Provider provider, HttpClient http, String conversation, String alice, CarlService service, String scenario) throws Exception
   {
      var goals = new com.kof22.carlai.domain.FinancialGoals(service);
      long debtGoal = goals.create("alice", "Debt freedom", "DEBT_FREEDOM", 1, "PRIVATE", "Human goal");
      long investmentGoal = goals.create("alice", "Later investing", "INVESTMENT", 3, "PRIVATE", "Human stage later");
      var proposal = JSON.createObjectNode().put("operation", "GOAL_PRIORITY").put("goal", debtGoal).put("expectedRevision", 1).put("priority", 2).put("reason", "Human priority choice");
      String message = "Set priority for goal Debt freedom revision 1 to priority 2. Human priority choice.";
      if(scenario.equals("goal-rationale"))
      {
         proposal.put("reason", "I don't want more debt");
         message = "Please set priority for goal Debt freedom revision 1 to priority 2 because I don't want more debt.";
      }
      if(scenario.equals("goal-negative"))
      {
         for(String rejected : List.of("Do not " + message, "What if I " + message, "Bob said: \"" + message + "\"", message.replace("priority 2", "priority 12")))
         {
            var result = planTurn(provider, http, conversation, alice, proposal, rejected);
            assertEquals("CLARIFICATION", result.path("artifact").path("kind").asText());
            var row = service.view(CarlService.Scope.privateFor("alice"), "financialGoals").stream().filter(r -> ((Number) r.get("id")).longValue() == debtGoal).findFirst().orElseThrow();
            assertEquals(1, ((Number) row.get("priority")).intValue());
            assertEquals(1, ((Number) row.get("revision")).intValue());
         }
         return 0;
      }
      var result = planTurn(provider, http, conversation, alice, proposal, message);
      assertEquals("GOAL_PRIORITY", result.path("artifact").path("kind").asText(), result.toString());
      assertEquals(2, result.path("artifact").path("priorityRevision").path("revision").asInt());
      assertEquals(2, result.path("artifact").path("goal").path("priority").asInt());
      assertTrue(!result.path("artifact").path("goal").path("investment_stage_selected").asBoolean());
      if(!scenario.equals("goal-tradeoff"))
      {
         return 0;
      }
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      goals.context("alice", investmentGoal, "USD", new com.kof22.carlai.domain.InvestmentPlanning.Context(false, 1, null, new java.math.BigDecimal("500.00"), false, false, false, "Synthetic context"), "Human educational context");
      long account = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Tradeoff debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", account, date, new java.math.BigDecimal("100.00"), new java.math.BigDecimal("10.00"), java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.24"), java.math.BigDecimal.ZERO, "Synthetic statement");
      debts.paymentProfile("alice", account, date, date.plusDays(5), new java.math.BigDecimal("102.00"), new java.math.BigDecimal("102.00"), com.kof22.carlai.domain.FinancialPlanning.Strategy.AVALANCHE, "Human targets");
      var scope = CarlService.Scope.privateFor("alice");
      long debt = debts.compare(scope, UUID.randomUUID(), date, "USD", new java.math.BigDecimal("102.00"), 1, "Human budget");
      long investment = goals.scenario(scope, UUID.randomUUID(), investmentGoal, "USD", java.time.YearMonth.from(date), 1, new java.math.BigDecimal("100.00"), new java.math.BigDecimal("102.00"), new com.kof22.carlai.domain.InvestmentPlanning.Assumption("synthetic", new java.math.BigDecimal("0.01"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Explicit uncertain rate"), "Educational assumption");
      proposal.removeAll();
      proposal.put("operation", "GOAL_TRADEOFF").put("debtArtifact", debt).put("investmentArtifact", investment).put("debtStrategy", "AVALANCHE").put("evidence", "Human selected educational comparison").putArray("goals").add(debtGoal).add(investmentGoal);
      message = "Compare debt and investment tradeoffs using debt comparison " + debt + ", investment scenario " + investment + ", strategy AVALANCHE and goal Debt freedom and goal Later investing. Human selected educational comparison.";
      result = planTurn(provider, http, conversation, alice, proposal, message);
      assertEquals("GOAL_TRADEOFF", result.path("artifact").path("kind").asText(), result.toString());
      long artifact = result.path("artifactId").asLong();
      assertTrue(artifact > 0);
      JsonNode facts = JSON.readTree(service.artifact("alice", artifact).get("facts").toString());
      assertEquals("EDUCATIONAL_ONLY", facts.path("recommendationStatus").asText());
      assertEquals("UNQUALIFIED_ASSUMPTION", facts.path("budgetQualification").asText());
      assertEquals("INCOMPARABLE", facts.path("comparisonStatus").asText(), "Missing owner investment context remains incomplete");
      assertEquals(debtGoal, facts.path("goals").get(0).path("id").asLong());
      return artifact;
   }



   private static void assertFinancialIntent(Provider provider, HttpClient http, String conversation, String alice, CarlService service) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      long account = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Intent debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", account, date, new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Synthetic statement");
      debts.paymentProfile("alice", account, date, date.plusDays(5), new java.math.BigDecimal("100.00"), new java.math.BigDecimal("100.00"), com.kof22.carlai.domain.FinancialPlanning.Strategy.AVALANCHE, "Human payment targets");
      String evidence = "USD 200.00 total monthly debt-service budget as of " + date + " for 24 months, AVALANCHE rollover";
      var proposal = JSON.createObjectNode().put("operation", "FINANCIAL_PLAN").put("asOf", date.toString()).put("currency", "USD").put("monthlyBudget", "200.00").put("horizonMonths", 24).put("rollover", "AVALANCHE").put("budgetEvidence", evidence).put("title", "Unselected plan");
      proposal.putArray("accounts").add(account);
      proposal.putArray("moves");
      String command = "Create a draft financial plan titled Unselected plan for Intent debt using " + evidence + ".";
      int before = service.view(CarlService.Scope.privateFor("alice"), "artifacts").size();
      provider.enqueue("end_turn", proposal.toString());
      provider.enqueue("end_turn", "Unauthorized narration must never run");
      String path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Do not " + command)))).statusCode());
      var result = await(http, path, alice);
      assertEquals("CLARIFICATION", result.path("artifact").path("kind").asText(), result.toString());
      assertEquals(before, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size());
      assertTrue(service.view(CarlService.Scope.privateFor("alice"), "plans").isEmpty());
   }



   private static void assertPlanGuards(Provider provider, HttpClient http, String conversation, String alice, CarlService service) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      var records = new com.kof22.carlai.domain.FinancialRecords(service);
      long account = records.createAccount("alice", "Guard debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", account, date, new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Synthetic dated statement");
      long source = debts.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human budget");
      var plans = new com.kof22.carlai.domain.PlanLifecycle(service);
      long plan = plans.create("alice", UUID.randomUUID(), source, "Guard plan", "Human fixture selection through actual service");
      plans.step("alice", plan, 1, UUID.randomUUID(), "Review guarded statement", 1, date, "Home", null, "Human fixture task");
      debts.terms("alice", account, date, new java.math.BigDecimal("900.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Updated statement");
      var proposal = JSON.createObjectNode().put("operation", "PLAN_AGREE").put("plan", plan).put("expectedVersion", 2).put("reason", "Current explicit agreement");
      var result = planTurn(provider, http, conversation, alice, proposal, "I agree to plan Guard plan version 2.");
      assertEquals("UNKNOWN", result.path("status").asText(), "Stale sources must never be silently agreed");
      assertEquals(2, JSON.valueToTree(plans.get("alice", plan)).path("plan").path("version").asInt());
      records.createAccount("alice", "Missing statement debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Unknown terms");
      long incomplete = debts.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human budget with explicitly missing terms");
      assertTrue(service.artifact("alice", incomplete).get("status_label").toString().startsWith("Incomplete"));
      proposal.removeAll();
      proposal.put("operation", "PLAN_REBASE").put("plan", plan).put("expectedVersion", 2).put("sourceArtifact", incomplete).put("reason", "Review missing assumptions");
      result = planTurn(provider, http, conversation, alice, proposal, "Rebase plan Guard plan version 2 using comparison " + incomplete + ".");
      assertEquals(3, result.path("artifact").path("plan").path("plan").path("version").asInt());
      proposal.removeAll();
      proposal.put("operation", "PLAN_AGREE").put("plan", plan).put("expectedVersion", 3).put("reason", "Current explicit agreement");
      result = planTurn(provider, http, conversation, alice, proposal, "I agree to plan Guard plan version 3.");
      assertEquals("UNKNOWN", result.path("status").asText(), "Incomplete actual service results must never be relabeled or silently agreed");
      var current = JSON.valueToTree(plans.get("alice", plan));
      assertEquals(3, current.path("plan").path("version").asInt());
      assertEquals("DRAFT", current.path("plan").path("state").asText());
   }



   private static void assertQuotedPlan(Provider provider, HttpClient http, String conversation, String alice, CarlService service) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      long account = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Quoted-plan debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Synthetic statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", account, date, new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("100.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "Synthetic dated statement");
      long source = debts.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), date, "USD", new java.math.BigDecimal("100.00"), 12, "Human budget");
      var proposal = JSON.createObjectNode().put("operation", "PLAN_CREATE").put("sourceArtifact", source).put("title", "Quoted family plan").put("reason", "Human selection");
      var result = planTurn(provider, http, conversation, alice, proposal, "Could you please create a draft plan titled \"Quoted family plan\" from comparison " + source + "?");
      assertEquals("PLAN_CREATE", result.path("artifact").path("kind").asText(), result.toString());
      long plan = result.path("artifact").path("createdPlanId").asLong();
      proposal.removeAll();
      proposal.put("operation", "PLAN_STEP").put("plan", plan).put("expectedVersion", 1).putNull("step").put("title", "Review quoted checklist").put("assignee", 1).put("due", date.toString()).put("location", "Home").putNull("dependency").put("reason", "Human task");
      result = planTurn(provider, http, conversation, alice, proposal, "Please add a task to plan \"Quoted family plan\" version 1: \"Review quoted checklist\"; assign to \"Synthetic A\"; due " + date + "; location \"Home\".");
      assertEquals(2, result.path("artifact").path("plan").path("plan").path("version").asInt(), result.toString());
      assertEquals("Home", result.path("artifact").path("plan").path("steps").get(0).path("location").asText());
   }



   private static JsonNode planTurn(Provider provider, HttpClient http, String conversation, String token, JsonNode proposal, String message) throws Exception
   {
      provider.enqueue("end_turn", proposal.toString());
      String path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      String input = JSON.writeValueAsString(Map.of("input", Map.of("message", message)));
      assertEquals(200, send(http, path, "PUT", token, input).statusCode());
      Thread.sleep(500);
      var result = await(http, path, token);
      if(java.util.Set.of("PLAN_CREATE", "GOAL_PRIORITY").contains(proposal.path("operation").asText()) || proposal.path("action").asText().equals("PUBLISH"))
      {
         int calls = provider.requests.size();
         assertEquals(result, JSON.readTree(send(http, path, "PUT", token, input).body()));
         assertEquals(calls, provider.requests.size(), "Retry must not run model or mutation again");
      }
      return result;
   }



   private static long[] assertFinancialPlan(Provider provider, HttpClient http, String base, String alice, String bob, CarlService service, boolean failedNarration) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      var records = new com.kof22.carlai.domain.FinancialRecords(service);
      long debt = records.createAccount("alice", "Reviewed shared debt", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "FAMILY", "Synthetic statement");
      long privateDebt = records.createAccount("alice", "PRIVATE_DEBT_NEVER_IN_MODEL_CONTEXT", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Private statement");
      var debts = new com.kof22.carlai.domain.DebtPlans(service);
      debts.terms("alice", debt, date, new java.math.BigDecimal("1000"), new java.math.BigDecimal("25"), java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.24"), java.math.BigDecimal.ZERO, "Synthetic 24 percent statement");
      debts.paymentProfile("alice", debt, date, date.plusDays(5), new java.math.BigDecimal("100"), new java.math.BigDecimal("100"), com.kof22.carlai.domain.FinancialPlanning.Strategy.AVALANCHE, "Synthetic reviewed dates and monthly targets");
      String conversation = base + "/conversations/" + UUID.randomUUID();
      assertEquals(200, send(http, conversation, "PUT", alice, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
      String evidence = "USD 200.00 total monthly debt-service budget as of " + date + " for 24 months, AVALANCHE rollover";
      String message = "Create a draft financial plan titled Debt freedom draft for Reviewed shared debt using " + evidence + ". Compare current payments, minimum, avalanche and snowball. No payments or agreements.";
      var proposal = JSON.createObjectNode().put("operation", "FINANCIAL_PLAN").put("asOf", date.toString()).put("currency", "USD").put("monthlyBudget", "200.00").put("horizonMonths", 24).put("rollover", "AVALANCHE").put("budgetEvidence", evidence).put("title", "Debt freedom draft");
      proposal.putArray("accounts").add(debt);
      proposal.putArray("moves");
      provider.enqueue("end_turn", proposal.toString());
      provider.enqueue(failedNarration ? "max_tokens" : "end_turn", failedNarration ? "Incomplete financial narration must not be successful" : "Selected debt principal is USD 1000.00. The monthly budget is a human assumption, not qualified cash affordability.");
      String path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      String input = JSON.writeValueAsString(Map.of("input", Map.of("message", message)));
      assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
      var response = await(http, path, alice);
      assertEquals("PARTIAL", response.path("status").asText(), response.toString());
      long artifact = response.path("artifactId").asLong();
      long plan = response.path("artifact").path("createdPlanId").asLong();
      assertTrue(plan > 0, response.toString());
      var portfolio = response.path("artifact").path("portfolio");
      assertEquals("UNQUALIFIED_ASSUMPTION", portfolio.path("budgetQualification").asText());
      assertEquals("USD", portfolio.path("currency").asText());
      assertEquals(0, new java.math.BigDecimal("1000").compareTo(portfolio.path("strategies").path("AVALANCHE").path("originalPrincipal").decimalValue()));
      assertEquals(0, new java.math.BigDecimal("64.54").compareTo(portfolio.path("strategies").path("AVALANCHE").path("interest").decimalValue()));
      assertEquals(6, portfolio.path("strategies").path("AVALANCHE").path("calculatedMonths").asInt());
      assertEquals("DRAFT", response.path("artifact").path("plan").path("plan").path("state").asText());
      assertEquals(1, response.path("artifact").path("createdPlanVersion").asInt());
      assertEquals(1, response.path("artifact").path("plan").path("plan").path("version").asInt());
      assertEquals(artifact, response.path("artifact").path("plan").path("plan").path("source_artifact_id").asLong());
      var saved = service.artifact("bob", artifact);
      assertEquals(failedNarration ? "FAILED" : "COMPLETE", saved.get("narration_state"));
      assertTrue(saved.get("facts").toString().contains("1000.00"));
      assertTrue(!saved.get("narrative").toString().contains("Incomplete financial narration"));
      assertTrue(provider.requests.stream().noneMatch(body -> body.contains("PRIVATE_DEBT_NEVER_IN_MODEL_CONTEXT")));
      assertTrue(!provider.requests.getLast().contains("remainingBalances"), "Large raw schedules must not enter model narration");
      assertEquals(response, JSON.readTree(send(http, path, "PUT", alice, input).body()));
      assertEquals(response, JSON.readTree(send(http, path, "GET", bob, null).body()));
      if(failedNarration)
      {
         int before = service.view(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), "artifacts").size();
         proposal.putNull("monthlyBudget");
         provider.enqueue("end_turn", proposal.toString());
         String missing = conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, missing, "PUT", alice, "{\"input\":{\"message\":\"Create a debt freedom plan without guessing my payment budget\"}}").statusCode());
         assertEquals("CLARIFICATION", await(http, missing, alice).path("artifact").path("kind").asText());
         proposal.put("monthlyBudget", "200.00");
         for(String unsupported : List.of("1,200.00", "-200.00"))
         {
            String wrongEvidence = evidence.replace("200.00", unsupported);
            proposal.put("budgetEvidence", wrongEvidence);
            provider.enqueue("end_turn", proposal.toString());
            String wrongToken = conversation + "/workflows/conversation/" + UUID.randomUUID();
            assertEquals(200, send(http, wrongToken, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Create a draft financial plan titled Debt freedom draft using " + wrongEvidence)))).statusCode());
            assertEquals("CLARIFICATION", await(http, wrongToken, alice).path("artifact").path("kind").asText(), "Grouped or signed budget evidence cannot support a different positive budget");
         }
         proposal.put("budgetEvidence", evidence).put("horizonMonths", 4);
         provider.enqueue("end_turn", proposal.toString());
         String wrongHorizon = conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, wrongHorizon, "PUT", alice, input).statusCode());
         assertEquals("CLARIFICATION", await(http, wrongHorizon, alice).path("artifact").path("kind").asText(), "24 months must never authorize a four-month projection");
         proposal.put("horizonMonths", 24);
         proposal.withArray("accounts").removeAll().add(privateDebt);
         provider.enqueue("end_turn", proposal.toString());
         String denied = conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, denied, "PUT", alice, input).statusCode());
         assertEquals("UNKNOWN", await(http, denied, alice).path("status").asText());
         assertEquals(before, service.view(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), "artifacts").size());
         proposal.withArray("accounts").removeAll().add(debt);
         try(var connection = java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement())
         {
            statement.execute("UPDATE carl_member SET can_manage=false WHERE id=2");
         }
         long storedBefore = storedArtifactCount();
         String bobConversation = base + "/conversations/" + UUID.randomUUID();
         assertEquals(200, send(http, bobConversation, "PUT", bob, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
         provider.enqueue("end_turn", proposal.toString());
         String notManager = bobConversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, notManager, "PUT", bob, input).statusCode());
         assertEquals("UNKNOWN", await(http, notManager, bob).path("status").asText());
         assertEquals(storedBefore, storedArtifactCount());
         int calls = provider.requests.size();
         assertEquals(404, send(http, path, "GET", alice, null).statusCode());
         assertEquals(404, send(http, conversation + "/workflows/conversation/" + UUID.randomUUID(), "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Tell me more about that plan", "replyTo", path.substring(path.lastIndexOf('/') + 1))))).statusCode());
         assertEquals(calls, provider.requests.size());
      }
      else
      {
         proposal.put("operation", "FINANCIAL_COMPARISON").putNull("title");
         provider.enqueue("end_turn", proposal.toString());
         provider.enqueue("end_turn", "Comparison of selected USD 1000 principal; no new plan or payment.");
         String comparison = conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, comparison, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Compare Reviewed shared debt using " + evidence)))).statusCode());
         var comparisonOnly = await(http, comparison, alice);
         assertEquals("PARTIAL", comparisonOnly.path("status").asText());
         assertTrue(!comparisonOnly.path("artifact").has("createdPlanId"));
         assertEquals("FINANCIAL_COMPARISON", comparisonOnly.path("artifact").path("kind").asText());
         // Reconciliation records a changed statement without silently recalculating or agreeing the draft.
         debts.terms("alice", debt, date, new java.math.BigDecimal("1100"), new java.math.BigDecimal("25"), java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.24"), java.math.BigDecimal.ZERO, "Changed synthetic statement");
         assertEquals(Boolean.TRUE, service.artifact("alice", artifact).get("stale"));
         assertEquals(Boolean.TRUE, ((Map<?, ?>) new com.kof22.carlai.domain.PlanLifecycle(service).get("alice", plan).get("plan")).get("source_stale"));
      }
      return new long[]{artifact, plan};
   }



   private static long assertKitchenPurchase(Provider provider, HttpClient http, String base, String alice, String bob, CarlService service, boolean negatives, boolean budgetOnly) throws Exception
   {
      var date = LocalDate.now(java.time.ZoneId.of("America/Chicago"));
      var cash = new com.kof22.carlai.domain.CashPlans(service);
      long plan = cash.create("alice", "Reviewed kitchen cash plan", "FAMILY", "Synthetic reviewed balances, income, essential bills and goals", new com.kof22.carlai.domain.CashPlans.Assumptions("USD", date, date.plusMonths(6), new java.math.BigDecimal(negatives ? "200" : "1000"), new java.math.BigDecimal("200"), new java.math.BigDecimal("700"), true, true, true, true, true, true));
      long card = new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Synthetic high-credit card", "CREDIT_CARD", "USD", false, java.math.BigDecimal.ONE, "FAMILY", "Synthetic unused credit capacity 50000; not spendable cash");
      var terms = new com.kof22.carlai.domain.FinancingScenarios.Offer("store", "USD", new java.math.BigDecimal("600"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, new java.math.BigDecimal("100"), 6, List.of(new com.kof22.carlai.domain.FinancialPlanning.Rate(1, java.math.BigDecimal.ZERO)), new com.kof22.carlai.domain.FinancingScenarios.Promotion(com.kof22.carlai.domain.FinancingScenarios.PromotionKind.NONE, 0, java.math.BigDecimal.ZERO, true), com.kof22.carlai.domain.FinancingScenarios.Evidence.VERIFIED_TERMS);
      long offer = new com.kof22.carlai.domain.FinancingOffers(service).create("alice", "Supplied store offer", "FAMILY", "PURCHASE_FINANCE", terms, date, date.plusDays(30), date.plusMonths(1), "Synthetic unsecured terms; approval not established", "Synthetic actual supplied offer");
      long privatePlan = cash.create("alice", "PRIVATE_FINANCE_PLAN_DO_NOT_DISCLOSE", "PRIVATE", "Private source", new com.kof22.carlai.domain.CashPlans.Assumptions("USD", date, date.plusMonths(6), new java.math.BigDecimal("99999"), java.math.BigDecimal.ZERO, new java.math.BigDecimal("99999"), true, true, true, true, true, true));
      String conversation = base + "/conversations/" + UUID.randomUUID();
      assertEquals(200, send(http, conversation, "PUT", alice, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
      String evidence = "all-in USD 600.00 on " + date;
      String message = "Can we afford a kitchen table and chairs, " + evidence + ", using our Reviewed kitchen cash plan? Compare cash, the Synthetic high-credit card and Supplied store offer. Do not buy anything.";
      var proposal = JSON.createObjectNode().put("operation", "PURCHASE").put("cashPlanId", plan).put("purchaseDate", date.toString()).put("price", "600.00").put("currency", "USD").put("costEvidence", evidence).put("allInCostsKnown", true).put("purpose", "kitchen table and chairs");
      proposal.putObject("card").put("accountId", card);
      proposal.putArray("offers").add(offer);
      if(budgetOnly)
      {
         proposal.putNull("price").put("allInCostsKnown", false).put("costEvidence", "USD on " + date);
         proposal.putNull("card");
         proposal.withArray("offers").removeAll();
         message = "What budget do we have for a kitchen table and chairs in USD on " + date + " using our Reviewed kitchen cash plan? No price or offer chosen yet.";
      }
      provider.enqueue("end_turn", proposal.toString());
      String path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      String input = JSON.writeValueAsString(Map.of("input", Map.of("message", message)));
      assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
      var response = await(http, path, alice);
      assertTrue(List.of("COMPLETE", "PARTIAL").contains(response.path("status").asText()), response.toString());
      long artifact = response.path("artifactId").asLong();
      var decision = response.path("artifact").path("purchase");
      assertEquals("USD", decision.path("currency").asText());
      assertEquals(0, new java.math.BigDecimal(negatives ? "0" : "700").compareTo(decision.path("maximumCashBudget").decimalValue()));
      if(budgetOnly)
      {
         assertEquals("PARTIAL", response.path("status").asText());
         assertTrue(decision.path("allInPrice").isNull(), "Unknown purchase price must never become zero");
         assertTrue(decision.path("preferredOption").isNull());
         assertTrue(response.path("artifact").path("message").asText().contains("price"));
         return artifact;
      }
      assertEquals(negatives ? "" : "CASH", decision.path("preferredOption").asText(""));
      assertEquals("UNDETERMINED", decision.path("options").get(1).path("state").asText(), "A model-selected existing card cannot invent reviewed grace or repayment terms");
      assertEquals(3, decision.path("options").size());
      assertEquals(0, new java.math.BigDecimal("600").compareTo(decision.path("options").get(2).path("totalCashOutlay").decimalValue()));
      assertTrue(decision.path("limitations").asText().contains("Credit availability is not a budget"));
      assertTrue(!provider.requests.getLast().contains("PRIVATE_FINANCE_PLAN_DO_NOT_DISCLOSE"));
      assertTrue(provider.requests.getLast().contains("Reviewed kitchen cash plan"));
      assertTrue(service.artifact("bob", artifact).get("facts").toString().contains("600.00"));
      assertEquals(response, JSON.readTree(send(http, path, "PUT", alice, input).body()));
      int calls = provider.requests.size();
      assertEquals(response, JSON.readTree(send(http, path, "GET", bob, null).body()));
      assertEquals(calls, provider.requests.size());
      if(!negatives)
      {
         return artifact;
      }
      int before = service.view(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), "artifacts").size();
      proposal.putNull("price");
      provider.enqueue("end_turn", proposal.toString());
      String missing = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, missing, "PUT", alice, "{\"input\":{\"message\":\"What budget is safe for a kitchen table and chairs?\"}}").statusCode());
      assertEquals("CLARIFICATION", await(http, missing, alice).path("artifact").path("kind").asText());
      assertEquals(before, service.view(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), "artifacts").size());
      proposal.put("price", "600.00").put("costEvidence", "all-in USD 1600.00 on " + date);
      provider.enqueue("end_turn", proposal.toString());
      String inventedAmount = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, inventedAmount, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Can we afford kitchen table and chairs, all-in USD 1600.00 on " + date + "?")))).statusCode());
      assertEquals("CLARIFICATION", await(http, inventedAmount, alice).path("artifact").path("kind").asText(), "600 is not evidence-supported by a quoted 1600 price");
      assertEquals(before, service.view(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), "artifacts").size());
      for(String unsupported : List.of("1,600.00", "-600.00"))
      {
         proposal.put("costEvidence", "all-in USD " + unsupported + " on " + date);
         provider.enqueue("end_turn", proposal.toString());
         String wrongToken = conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, wrongToken, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Can we afford kitchen table and chairs, all-in USD " + unsupported + " on " + date + "?")))).statusCode());
         assertEquals("CLARIFICATION", await(http, wrongToken, alice).path("artifact").path("kind").asText(), "A grouped or signed amount cannot support a different positive amount");
      }
      proposal.put("costEvidence", evidence);
      proposal.put("price", "600.00").put("cashPlanId", privatePlan);
      provider.enqueue("end_turn", proposal.toString());
      String denied = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, denied, "PUT", alice, input).statusCode());
      assertEquals("UNKNOWN", await(http, denied, alice).path("status").asText());
      assertEquals(before, service.view(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")), "artifacts").size());
      proposal.put("cashPlanId", plan);
      proposal.withArray("offers").removeAll();
      provider.enqueue("end_turn", proposal.toString());
      String absentOffers = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, absentOffers, "PUT", alice, input).statusCode());
      var missingOfferResult = await(http, absentOffers, alice);
      assertEquals("PARTIAL", missingOfferResult.path("status").asText());
      assertTrue(missingOfferResult.path("artifact").path("message").asText().contains("No actual financing offer"));
      try(var connection = java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement())
      {
         statement.execute("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
      }
      calls = provider.requests.size();
      assertEquals(404, send(http, path, "GET", alice, null).statusCode());
      assertEquals(404, send(http, conversation + "/workflows/conversation/" + UUID.randomUUID(), "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Explain that purchase", "replyTo", path.substring(path.lastIndexOf('/') + 1))))).statusCode());
      assertEquals(calls, provider.requests.size());
      return 0;
   }



   private static void assertFailureAndBoundaries(Provider provider, HttpClient http, String base, String conversation, String alice, CarlService service) throws Exception
   {
      int artifacts = service.view(CarlService.Scope.privateFor("alice"), "artifacts").size();
      String path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      String input = JSON.writeValueAsString(Map.of("input", Map.of("message", "Please prepare my bill report for September 2026.")));
      provider.enqueue("max_tokens", JSON.writeValueAsString(Map.of("operation", "CLARIFY", "message", "An incomplete response")));
      assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
      assertEquals("UNKNOWN", await(http, path, alice).path("status").asText(), "A truncated model response must not be a complete conversational result");
      int calls = provider.requests.size();
      assertEquals("UNKNOWN", JSON.readTree(send(http, path, "PUT", alice, input).body()).path("status").asText());
      assertEquals(calls, provider.requests.size());
      assertEquals(artifacts, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size());
      path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "REPORT", "focus", "BILLS", "from", "2026-09-01", "through", "2026-09-30")));
      provider.enqueue("max_tokens", "An incomplete narration must not become successful.");
      assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
      var partial = await(http, path, alice);
      assertEquals("PARTIAL", partial.path("status").asText(), partial.toString());
      var artifact = service.artifact("alice", partial.path("artifactId").asLong());
      assertEquals("FAILED", artifact.get("narration_state"));
      assertTrue(artifact.get("facts").toString().contains("200.00"));
      assertTrue(!artifact.get("narrative").toString().contains("An incomplete narration"));
      artifacts++;
      int beforeBudget = provider.requests.size();
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "REPORT", "focus", "BILLS", "from", "2026-09-01", "through", "2026-09-30")), 40);
      path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
      var exhausted = await(http, path, alice);
      assertEquals("PARTIAL", exhausted.path("status").asText());
      assertEquals("FAILED", service.artifact("alice", exhausted.path("artifactId").asLong()).get("narration_state"));
      assertEquals(beforeBudget + 1, provider.requests.size(), "Narration must not reset the aggregate output allowance");
      artifacts++;
      for(var proposal : List.of(Map.of("operation", "SEND"), Map.of("operation", "REFUSE", "principal", "bob"), Map.of("operation", "DRAFT", "workId", 999999, "purpose", "FOLLOW_UP")))
      {
         provider.enqueue("end_turn", JSON.writeValueAsString(proposal));
         path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
         assertEquals("UNKNOWN", await(http, path, alice).path("status").asText());
         assertEquals(artifacts, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size());
      }
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "REFUSE")));
      path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Pay the utility bill and send the draft.")))).statusCode());
      var refused = await(http, path, alice);
      assertEquals("BOUNDARY", refused.path("artifact").path("kind").asText());
      assertEquals(artifacts, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size());
      assertTrue(!refused.toString().contains("PRIVATE_SYNTHETIC_REASONING"));
      assertTrue(provider.requests.stream().noneMatch(body -> body.contains("PRIVATE_SYNTHETIC_REASONING") || body.contains("synthetic-signature")));
      for(String stop : List.of("model_context_window_exceeded", "pause_turn", "stop_sequence"))
      {
         provider.enqueue(stop, JSON.writeValueAsString(Map.of("operation", "CLARIFY", "message", "Incomplete")));
         path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
         assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
         assertEquals("UNKNOWN", await(http, path, alice).path("status").asText());
      }
      assertEquals(artifacts, service.view(CarlService.Scope.privateFor("alice"), "artifacts").size());
   }



   private static void assertSerializedClarifications(Provider provider, HttpClient http, String base, String conversation, String alice) throws Exception
   {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      provider.beforeResponse = () ->
      {
         entered.countDown();
         try
         {
            if(!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            {
               throw new IllegalStateException("Fixture release missing");
            }
         }
         catch(InterruptedException interrupted)
         {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
         }
      };
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "CLARIFY", "message", "Which reporting period should I use?")));
      String id = UUID.randomUUID().toString();
      String path = base + conversation + "/workflows/conversation/" + id;
      String input = JSON.writeValueAsString(Map.of("input", Map.of("message", "Please prepare a report.")));
      try
      {
         assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
         assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
         assertEquals(409, send(http, base + conversation + "/workflows/conversation/" + UUID.randomUUID(), "PUT", alice, input).statusCode());
         assertEquals(200, send(http, path, "PUT", alice, input).statusCode());
         assertEquals(409, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Different request")))).statusCode());
      }
      finally
      {
         release.countDown();
      }
      assertEquals("CLARIFICATION", await(http, path, alice).path("artifact").path("kind").asText());
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "CLARIFY", "message", "Should the report cover bills only?")));
      path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "September 2026", "replyTo", id)))).statusCode());
      assertEquals("COMPLETE", await(http, path, alice).path("status").asText());
      assertTrue(provider.requests.getLast().contains("Which reporting period"));
      assertTrue(provider.requests.getLast().contains("September 2026"));
      assertEquals(400, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Read another person", "principal", "bob")))).statusCode());
   }



   private static void assertSharedScope(Provider provider, HttpClient http, String base, String alice, long privateWork, CarlService service) throws Exception
   {
      String conversation = base + "/conversations/" + UUID.randomUUID();
      assertEquals(200, send(http, conversation, "PUT", alice, "{\"shared\":true,\"participants\":[\"1\",\"2\"]}").statusCode());
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "REPORT", "focus", "BILLS", "from", "2026-09-01", "through", "2026-09-30")));
      provider.enqueue("end_turn", "The shared scope has no permitted bill records. This is not a whole-household total.");
      String path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Prepare our shared bill report for September 2026.")))).statusCode());
      var result = await(http, path, alice);
      long id = result.path("artifactId").asLong();
      assertTrue(!service.artifact("bob", id).get("facts").toString().contains("125.25"));
      assertTrue(!provider.requests.getLast().contains("125.25"));
      assertTrue(!provider.requests.get(provider.requests.size() - 2).contains("Review repair question"));
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "DRAFT", "workId", privateWork, "purpose", "FOLLOW_UP")));
      path = conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Prepare a vendor follow-up draft.")))).statusCode());
      assertEquals("UNKNOWN", await(http, path, alice).path("status").asText());
   }



   private static void assertDelayedNarration(Provider provider, HttpClient http, String base, String conversation, String alice, CarlService service) throws Exception
   {
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "REPORT", "focus", "BILLS", "from", "2026-09-01", "through", "2026-09-30")));
      provider.enqueue("end_turn", "A delayed narration must not become successful.");
      var providerFinished = new java.util.concurrent.CountDownLatch(1);
      provider.beforeResponse = () -> provider.beforeResponse = () ->
      {
         try
         {
            Thread.sleep(5000);
         }
         catch(InterruptedException interrupted)
         {
            Thread.currentThread().interrupt();
         }
         finally
         {
            providerFinished.countDown();
         }
      };
      String path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      long started = System.nanoTime();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Please prepare my bills for September 2026.")))).statusCode());
      var partial = await(http, path, alice);
      long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertEquals("PARTIAL", partial.path("status").asText(), partial.toString());
      assertEquals("FAILED", service.artifact("alice", partial.path("artifactId").asLong()).get("narration_state"));
      assertTrue(elapsed < 3500, "Configured two-second timeout must interrupt blocked SDK I/O; observed " + elapsed + "ms");
      assertTrue(providerFinished.await(6, java.util.concurrent.TimeUnit.SECONDS));
      provider.enqueue("end_turn", JSON.writeValueAsString(Map.of("operation", "CLARIFY", "message", "Which period should I use?")));
      path = base + conversation + "/workflows/conversation/" + UUID.randomUUID();
      assertEquals(200, send(http, path, "PUT", alice, JSON.writeValueAsString(Map.of("input", Map.of("message", "Another report please")))).statusCode());
      assertEquals("COMPLETE", await(http, path, alice).path("status").asText(), "A timed-out worker must clear its own timer interrupt before reuse");
   }



   private static JsonNode await(HttpClient http, String path, String token) throws Exception
   {
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      long quotaWaitMillis = 0;
      long pollMillis = 250;
      JsonNode result;
      do
      {
         Thread.sleep(pollMillis);
         var response = send(http, path, "GET", token, null);
         if(response.statusCode() == 429)
         {
            assertEquals("rate_limit", JSON.readTree(response.body()).path("error").path("code").asText(), response.body());
            String retry = response.headers().firstValue("Retry-After").orElse("");
            assertTrue(retry.matches("[1-9][0-9]{0,2}"), "Rate-limit polling requires a positive Retry-After in seconds");
            long waitMillis = Long.parseLong(retry) * 1000;
            assertTrue(quotaWaitMillis + waitMillis <= 60000, "Workflow exceeded its single sixty-second quota recovery budget");
            quotaWaitMillis += waitMillis;
            long started = System.nanoTime();
            Thread.sleep(waitMillis);
            // Only an explicitly signaled rate-limit wait is excluded from the operation deadline.
            deadline += System.nanoTime() - started;
            continue;
         }
         assertEquals(200, response.statusCode(), response.body());
         result = JSON.readTree(response.body());
         if(!result.path("status").asText().equals("PENDING"))
         {
            return result;
         }
         pollMillis = Math.min(1000, pollMillis * 2);

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
   private static final class SharedCalendar implements AutoCloseable
   {
      private final com.sun.net.httpserver.HttpServer server;
      private final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
      private final java.util.concurrent.atomic.AtomicReference<String> remote = new java.util.concurrent.atomic.AtomicReference<>();
      private SharedCalendar() throws Exception
      {
         server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         server.createContext("/calendar/", exchange ->
         {
            String body = "";
            int status = 404;
            if(exchange.getRequestMethod().equals("PROPFIND"))
            {
               status = 207;
               body = "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>/calendar/</d:href><d:propstat><d:prop><c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
            }
            else if(exchange.getRequestMethod().equals("PUT"))
            {
               assertEquals("*", exchange.getRequestHeaders().getFirst("If-None-Match"));
               writes.incrementAndGet();
               remote.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
               status = 201;
            }
            else if(exchange.getRequestMethod().equals("GET") && remote.get() != null)
            {
               status = 200;
               body = remote.get();
            }
            exchange.getResponseHeaders().set("ETag", "\"synthetic-v1\"");
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try(var out = exchange.getResponseBody())
            {
               out.write(bytes);
            }
         });
         server.start();
      }



      private com.kof22.carlai.domain.CalendarWorkflows workflows(javax.sql.DataSource source, CarlService service)
      {
         var authority = new com.kof22.carlai.domain.PlanCalendarAuthority();
         URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/calendar/");
         return new com.kof22.carlai.domain.CalendarWorkflows(Map.of("reminders", caller -> new com.kof22.carlai.calendar.CalendarPublicationService(source, collection, "VTODO", java.util.Set.of(1L), "alice", "synthetic", "synthetic".toCharArray(), true, (c, standing, plan, task, version, audience, action) ->
         {
            authority.load(c, caller, plan, task, version, audience, action);
            return authority.load(c, standing, plan, task, version, audience, action);
         })), Map.of(), service);
      }



      @Override
      public void close()
      {
         server.stop(0);
      }
   }



   private static final class Provider implements AutoCloseable
   {
      private final com.sun.net.httpserver.HttpServer server;
      private volatile Runnable beforeResponse;
      private final java.util.concurrent.BlockingQueue<String> replies = new java.util.concurrent.LinkedBlockingQueue<>();
      private final java.util.List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
      private Provider() throws Exception
      {
         server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         server.createContext("/v1/messages", exchange ->
         {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            Runnable callback = beforeResponse;
            beforeResponse = null;
            if(callback != null)
            {
               callback.run();
            }
            String reply = replies.poll();
            byte[] bytes = (reply == null ? "{}" : reply).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply == null ? 500 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
         });
         server.start();
      }



      private String url()
      {
         return "http://127.0.0.1:" + server.getAddress().getPort();
      }



      private void enqueue(String stop, String text) throws Exception
      {
         enqueue(stop, text, 20);
      }



      private void enqueue(String stop, String text, int outputTokens) throws Exception
      {
         replies.add(JSON.writeValueAsString(Map.of("id", "msg_synthetic", "type", "message", "role", "assistant", "model", "claude-sonnet-5", "content", List.of(Map.of("type", "thinking", "thinking", "PRIVATE_SYNTHETIC_REASONING", "signature", "synthetic-signature"), Map.of("type", "text", "text", text)), "stop_reason", stop, "usage", Map.of("input_tokens", 10, "output_tokens", outputTokens))));
      }



      @Override
      public void close()
      {
         server.stop(0);
      }
   }

}
