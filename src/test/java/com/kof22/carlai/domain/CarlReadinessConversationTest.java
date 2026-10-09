/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeConfigurationFiles;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.bootstrap.NativeStores;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentadmin.client.FamilyAccess;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/** Actual SDK/PG readiness retrieval must finish inside the existing ten-iteration loop. */
class CarlReadinessConversationTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;

   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = NativeDatabases.source(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
      AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.systemUTC());
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled readiness chat','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','A',true),(2,1,'bob','B',true)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['FINANCE','TAX','BILLS','VENDORS','CALENDAR','SETTINGS']) d");
         return null;
      });
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void clear()
   {
      service.transaction(c ->
      {
         CarlService.execute(c, "TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
         CarlService.execute(c, "DELETE FROM carl_grant");
         CarlService.execute(c, "UPDATE carl_permission SET details=true");
         return null;
      });
   }



   private long document()
   {
      String file = "controlled-history-" + UUID.randomUUID() + ".txt";
      new MonarchImportWorkflow(service).storeUpload("alice", file, "Historical home note: roof inspection suggested in 2008. Present mortgage, condition and rents are unknown.".getBytes(StandardCharsets.UTF_8));
      var documents = new DocumentRecords(service);
      return documents.confirm("alice", documents.preview("alice", UUID.randomUUID(), file,
         new DocumentRecords.Source("Historical home note", "PRIVATE", "Controlled supplied history", "HISTORICAL_NOTE", LocalDate.of(2008, 5, 1), null, "Supplied history is not current financial qualification")), true);
   }



   @Test
   void actualToolResultsSupportDocumentToPlanAnswerWithoutExhaustingSdkIterations() throws Exception
   {
      long document = document();
      for(int i = 1; i <= 6; i++)
      {
         new FinancialGoals(service).create("alice", "Controlled priority " + i, "OTHER", i, "PRIVATE", "Human-selected controlled priority");
      }
      var scope = CarlService.Scope.privateFor("alice");
      var saved = new ReadinessPlans(service).generate(scope, UUID.randomUUID(), "Controlled readiness draft");
      var original = service.member("alice");
      var requests = new CopyOnWriteArrayList<String>();
      var results = new CopyOnWriteArrayList<JsonNode>();
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
         requests.add(request);
         var messages = JSON.readTree(request).path("messages");
         if(!results.isEmpty() || requests.size() > 1)
         {
            var blocks = messages.get(messages.size() - 1).path("content");
            for(var block : blocks)
            {
               if(block.path("type").asText().equals("tool_result"))
               {
                  assertThat(block.path("is_error").asBoolean()).isFalse();
                  JsonNode content = block.path("content");
                  String text = content.isTextual() ? content.asText() : content.get(0).path("text").asText();
                  results.add(JSON.readTree(text));
               }
            }
         }
         boolean inline = !results.isEmpty() && results.getFirst().path("readinessEvidence").path("facts").path("confirmedPriorities").size() == 6;
         String tool = "carl_read_report_section";
         var input = JSON.createObjectNode().put("id", saved.artifact()).put("pointer", "/facts/confirmedPriorities").put("offset", 0).put("limit", 25);
         boolean complete = false;
         if(results.isEmpty())
         {
            tool = "carl_read_plan";
            input = JSON.createObjectNode().put("id", saved.plan()).put("beforeVersion", Integer.MAX_VALUE).put("limit", 25);
         }
         else if((inline && results.size() == 1) || (!inline && results.size() == 9))
         {
            if(inline)
            {
               var evidence = results.getFirst().path("readinessEvidence");
               for(int i = 0; i < 6; i++)
               {
                  assertThat(evidence.path("facts").path("confirmedPriorities").get(i).path("title").asText()).isEqualTo("Controlled priority " + (i + 1));
                  assertThat(evidence.path("facts").path("confirmedPriorities").get(i).path("priority").asInt()).isEqualTo(i + 1);
               }
               assertThat(evidence.path("facts").path("gaps").toString()).contains("APR", "reserve", "rental", "unverified");
            }
            tool = "carl_read_document";
            input = JSON.createObjectNode().put("id", document).put("offset", 0).put("limit", 4000);
         }
         else if((inline && results.size() == 2) || (!inline && results.size() == 10))
         {
            assertThat(results.getLast().toString()).contains("2008-05-01", "SUPPLIED_UNVERIFIED", "roof inspection");
            complete = true;
         }
         else if(results.size() >= 2 && results.size() <= 7)
         {
            input.put("pointer", "/facts/confirmedPriorities/" + (results.size() - 2));
         }
         else if(results.size() == 8)
         {
            input.put("pointer", "/facts/gaps");
         }
         var response = JSON.createObjectNode().put("id", "msg_controlled").put("type", "message").put("role", "assistant").put("model", "claude-sonnet-5").put("stop_reason", complete ? "end_turn" : "tool_use").putNull("stop_sequence");
         var content = response.putArray("content").addObject();
         if(complete)
         {
            content.put("type", "text").put("text", "The saved readiness draft [record:" + saved.plan() + "] preserves six human priorities and missing current APRs, reserve, budget and rental facts. The supplied note [record:" + document + "] is historical (2008-05-01), SUPPLIED_UNVERIFIED, and does not establish current financial facts or an agreed payoff schedule.");
         }
         else
         {
            content.put("type", "tool_use").put("id", "read_" + requests.size()).put("name", tool).set("input", input);
         }
         response.putObject("usage").put("input_tokens", 1).put("output_tokens", 1);
         byte[] body = JSON.writeValueAsBytes(response);
         exchange.getResponseHeaders().set("Content-Type", "application/json");
         exchange.sendResponseHeaders(200, body.length);
         try(var output = exchange.getResponseBody())
         {
            output.write(body);
         }
      });
      provider.start();
      try
      {
         var configuration = NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + DATABASE.getJdbcUrl(), "--kof22.agent.db.username=" + DATABASE.getUsername(), "--kof22.agent.db.password=" + DATABASE.getPassword(), "--kof22.agent.qqq.db-password=controlled-reader", "--kof22.agent.qqq.password=controlled-bootstrap", "--kof22.agent.anthropic-api-key=controlled-provider", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + provider.getAddress().getPort()).configuration();
         var components = AgentApplication.components();
         components.validate(configuration);
         var context = new ClientWorkflow.Context(new FamilyAccess.Member("1", "1", "alice", Long.toString(original.permissionRevision())), UUID.randomUUID(), false, Set.of("1"));
         UUID request = UUID.randomUUID();
         var before = service.view(scope, "plans");
         try(var runtime = components.runtime(configuration); var conversation = new CarlConversation(service, runtime, configuration, NativeStores.create(configuration.database())))
         {
            var outcome = conversation.run(context, request, JSON.createObjectNode().put("message", "Now look at our saved financial readiness plan and explain how the supplied historical documents help and which current account, debt, budget and rental facts still need confirmation. Cite the plan and the documents you actually read. We have not agreed a payoff schedule or supplied current APRs."), () ->
            {
               assertThat(service.member("alice")).isEqualTo(original);
               return scope;
            });
            assertThat(outcome.status()).isEqualTo("COMPLETE");
            assertThat(outcome.output().path("kind").asText()).isEqualTo("ANSWER");
            assertThat(outcome.output().path("message").asText()).contains("[record:" + saved.plan() + "]", "[record:" + document + "]", "SUPPLIED_UNVERIFIED", "missing current APRs", "not establish current");
            assertThat(outcome.artifact()).isNull();
         }
         assertThat(requests).hasSize(3);
         assertThat(service.view(scope, "plans")).isEqualTo(before);
         var audits = service.transaction(c -> CarlService.rows(c, "SELECT caller_id,tool_name,decision FROM audit_log WHERE session_key=? ORDER BY id", "carl-conversation:" + request));
         assertThat(audits).hasSize(2);
         assertThat(audits).allSatisfy(row -> assertThat(row).containsEntry("caller_id", "alice").containsEntry("decision", "EXECUTED_READ"));
         assertThat(audits).extracting(row -> row.get("tool_name")).containsExactly("carl_read_plan", "carl_read_document");
      }
      finally
      {
         provider.stop(0);
      }
   }



   @Test
   void summaryBoundsTextAndCardinalityAndPreservesProtectedNavigation() throws Exception
   {
      long document = document();
      for(int i = 1; i <= 12; i++)
      {
         new FinancialGoals(service).create("alice", "Long priority ".repeat(25) + i, "OTHER", i, "PRIVATE", "PRIVATE_FULL_EVIDENCE".repeat(50));
      }
      var scope = CarlService.Scope.privateFor("alice");
      var saved = new ReadinessPlans(service).generate(scope, UUID.randomUUID(), "Bounded readiness");
      var result = JSON.valueToTree(new ConversationReads(service).plan(scope, saved.plan(), Integer.MAX_VALUE, 25));
      assertThat(result.toString().length()).isLessThanOrEqualTo(12000);
      var evidence = result.path("readinessEvidence");
      assertThat(evidence.toString().length()).isLessThanOrEqualTo(6000);
      assertThat(evidence.path("totalItems").path("confirmedPriorities").asInt()).isEqualTo(12);
      assertThat(evidence.path("facts").path("confirmedPriorities").size()).isEqualTo(8);
      assertThat(evidence.path("truncatedPaths").toString()).contains("/facts/confirmedPriorities", "/title");
      assertThat(evidence.toString()).doesNotContain("PRIVATE_FULL_EVIDENCE", "roof inspection");
      assertThat(evidence.path("facts").path("suppliedDocuments").get(0).path("id").asLong()).isEqualTo(document);
      assertThat(evidence.path("facts").path("suppliedDocuments").get(0).path("state").asText()).isEqualTo("SUPPLIED_UNVERIFIED");
      assertThat(evidence.path("detailTool").asText()).isEqualTo("carl_read_report_section");
      var full = new ConversationReads(service).report(scope, saved.artifact(), "/facts/confirmedPriorities/8/title", 0, 4096);
      assertThat(full.path("section").path("value").asText()).isEqualTo("Long priority ".repeat(25) + 9);
      assertThat(result.path("detailTool").asText()).isEqualTo("carl_read_detail_section");
   }



   @Test
   void savedSnapshotRemainsHistoricalAndCurrentSourceRevocationDeniesSummary() throws Exception
   {
      long document = document();
      var scope = CarlService.Scope.privateFor("alice");
      var saved = new ReadinessPlans(service).generate(scope, UUID.randomUUID(), "Protected readiness");
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", document);
         return null;
      });
      var reads = new ConversationReads(service);
      var evidence = JSON.valueToTree(reads.plan(scope, saved.plan(), Integer.MAX_VALUE, 25)).path("readinessEvidence");
      assertThat(evidence.path("stale").asBoolean()).isTrue();
      assertThat(evidence.path("qualification").asText()).contains("saved snapshot", "current");
      assertThatThrownBy(() -> reads.plan(CarlService.Scope.privateFor("bob"), saved.plan(), Integer.MAX_VALUE, 25)).isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> reads.plan(new CarlService.Scope("alice", Set.of("alice", "bob")), saved.plan(), Integer.MAX_VALUE, 25)).isInstanceOf(SecurityException.class);
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
         return null;
      });
      assertThatThrownBy(() -> reads.plan(scope, saved.plan(), Integer.MAX_VALUE, 25)).isInstanceOf(SecurityException.class);
   }
}
