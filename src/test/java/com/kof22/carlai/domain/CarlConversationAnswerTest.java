/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeConfigurationFiles;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.bootstrap.NativeStores;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentadmin.client.FamilyAccess;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/** CHAT-01/SEC-01–04: ordinary answers use the real SDK tool loop, PostgreSQL records and current scoped authorization. */
class CarlConversationAnswerTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   @ParameterizedTest
   @ValueSource(strings = {"private", "shared", "revoked", "quoted", "savedPlan", "advice", "generate", "create", "update", "send", "draftNoun", "budget", "purchase", "balance", "progress", "hypothetical", "negated", "explain", "greeting", "greetingCreate", "lookDocuments", "readDocuments", "sharedDocuments", "revokedDocuments", "readCreate", "nowSavedPlan", "nowPleaseSavedPlan", "nowReadDocuments", "nowReadCreate"})
   void ordinaryQuestionsReadCurrentPermittedAccountsWithoutDemandingReportDates(String scenario) throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      List<String> requests = new CopyOnWriteArrayList<>();
      var sourceReference = new AtomicReference<javax.sql.DataSource>();
      var savedPlanId = new AtomicReference<Long>();
      var documentId = new AtomicReference<Long>();
      boolean documentQuestion = Set.of("lookDocuments", "readDocuments", "sharedDocuments", "revokedDocuments", "nowReadDocuments").contains(scenario);
      boolean planQuestion = Set.of("savedPlan", "draftNoun", "explain", "nowSavedPlan", "nowPleaseSavedPlan").contains(scenario);
      boolean explicit = Set.of("generate", "create", "update", "send", "budget", "purchase", "balance", "progress", "hypothetical", "negated", "greetingCreate", "readCreate", "nowReadCreate").contains(scenario);
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         int index = requests.size();
         String content;
         String stop;
         if(explicit)
         {
            content = scenario.equals("send") ? "[{\"type\":\"text\",\"text\":\"{\\\"operation\\\":\\\"REFUSE\\\"}\"}]" : "[{\"type\":\"text\",\"text\":\"{\\\"operation\\\":\\\"CLARIFY\\\",\\\"message\\\":\\\"Please confirm the required dates and facts.\\\"}\"}]";
            stop = "end_turn";
         }
         else if(index == 1 && JSON.readTree(requests.getFirst()).path("tools").isEmpty())
         {
            // The actual failure was valid selector JSON lacking the required operation field.
            content = "[{\"type\":\"text\",\"text\":\"{\\\"kind\\\":\\\"ANSWER\\\",\\\"message\\\":\\\"A saved draft is not a debt payoff schedule.\\\"}\"}]";
            stop = "end_turn";
         }
         else if(index == 1)
         {
            if(scenario.equals("revoked") || scenario.equals("revokedDocuments"))
            {
               try(var c = sourceReference.get().getConnection(); var sql = c.createStatement())
               {
                  sql.execute("DELETE FROM carl_permission WHERE member_id=1 AND domain='FINANCE'");
               }
               catch(java.sql.SQLException failure)
               {
                  throw new java.io.IOException(failure);
               }
            }
            content = documentQuestion
               ? "[{\"type\":\"tool_use\",\"id\":\"tool_document\",\"name\":\"carl_read_document\",\"input\":{\"id\":" + documentId.get() + ",\"offset\":0,\"limit\":4000}}]"
               : planQuestion
                  ? "[{\"type\":\"tool_use\",\"id\":\"tool_plan\",\"name\":\"carl_read_plan\",\"input\":{\"id\":" + savedPlanId.get() + ",\"beforeVersion\":2147483647,\"limit\":25}}]"
                  : "[{\"type\":\"tool_use\",\"id\":\"tool_accounts\",\"name\":\"carl_read_page\",\"input\":{\"kind\":\"accounts\",\"after\":0,\"limit\":25,\"from\":null,\"through\":null,\"query\":null}}]";
            stop = "tool_use";
         }
         else
         {
            content = documentQuestion
               ? "[{\"type\":\"text\",\"text\":\"The historical home note [record:" + documentId.get() + "] is dated 2008-05-01 and SUPPLIED_UNVERIFIED; it does not confirm current mortgage or rental facts.\"}]"
               : planQuestion
                  ? "[{\"type\":\"text\",\"text\":\"The saved readiness draft [record:" + savedPlanId.get() + "] preserves missing debt terms; it is not an agreed debt payoff schedule.\"}]"
                  : "[{\"type\":\"text\",\"text\":\"The permitted Shared source account needs review [record:1]. Its source label does not establish ownership or spendable cash.\"}]";
            stop = "end_turn";
         }
         byte[] body = ("{\"id\":\"msg_controlled\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":" + content + ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
         exchange.getResponseHeaders().set("Content-Type", "application/json");
         exchange.sendResponseHeaders(200, body.length);
         try(var out = exchange.getResponseBody())
         {
            out.write(body);
         }
      });
      provider.start();
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         sourceReference.set(source);
         AgentMigrations.migrate(source);
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled answer family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Controlled A',true),(2,1,'bob','Controlled B',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            sql.execute("INSERT INTO carl_record(id,household_id,owner_id,domain,visibility,title,evidence) VALUES(1,1,1,'FINANCE','FAMILY','Shared source account','Controlled import evidence'),(2,1,1,'FINANCE','PRIVATE','Private source account','Controlled private evidence')");
            sql.execute("INSERT INTO carl_account(record_id,currency,kind,liquid,ownership_share,review_state) SELECT id,'USD','UNCLASSIFIED',NULL,NULL,'NEEDS_REVIEW' FROM carl_record");
         }
         var configuration = NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=controlled-reader", "--kof22.agent.qqq.password=controlled-unused-bootstrap", "--kof22.agent.anthropic-api-key=controlled-provider-only", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + provider.getAddress().getPort()).configuration();
         var components = AgentApplication.components();
         components.validate(configuration);
         var service = new CarlService(source, Clock.systemUTC());
         if(planQuestion || documentQuestion)
         {
            service.transaction(c ->
            {
               CarlService.rows(c, "SELECT setval(pg_get_serial_sequence('carl_record','id'),2)");
               return null;
            });
            if(planQuestion)
            {
               savedPlanId.set(new ReadinessPlans(service).generate(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), "Controlled readiness draft").plan());
            }
            if(documentQuestion)
            {
               var documents = new DocumentRecords(service);
               var uploads = new MonarchImportWorkflow(service);
               uploads.storeUpload("alice", "controlled-history.txt", "Historical supplied home note. The roof needed inspection in 2008; current property condition and debt facts are unverified.".getBytes(StandardCharsets.UTF_8));
               documentId.set(documents.confirm("alice", documents.preview("alice", UUID.randomUUID(), "controlled-history.txt", new DocumentRecords.Source("Historical home note", "FAMILY", "Controlled supplied note", "HISTORICAL_NOTE", java.time.LocalDate.of(2008, 5, 1), null, "Owner-supplied history; no current qualification")), true));
               uploads.storeUpload("alice", "controlled-private-history.txt", "PRIVATE_DOCUMENT_TEXT_NEVER_SHARED".getBytes(StandardCharsets.UTF_8));
               documents.confirm("alice", documents.preview("alice", UUID.randomUUID(), "controlled-private-history.txt", new DocumentRecords.Source("Private home note", "PRIVATE", "Controlled private note", "HISTORICAL_NOTE", null, null, "Private source")), true);
            }
         }
         var original = service.member("alice");
         boolean shared = scenario.equals("shared") || scenario.equals("sharedDocuments");
         var scope = shared ? new CarlService.Scope("alice", Set.of("alice", "bob")) : CarlService.Scope.privateFor("alice");
         var member = new FamilyAccess.Member("1", "1", "alice", Long.toString(original.permissionRevision()));
         var context = new ClientWorkflow.Context(member, UUID.randomUUID(), shared, shared ? Set.of("1", "2") : Set.of("1"));
         UUID request = UUID.randomUUID();
         try(var runtime = components.runtime(configuration); var conversation = new CarlConversation(service, runtime, configuration, NativeStores.create(configuration.database())))
         {
            java.util.function.Supplier<CarlService.Scope> authorized = () ->
            {
               if(!original.equals(service.member("alice")))
               {
                  throw new SecurityException("Controlled access revoked");
               }
               return scope;
            };
            String human = switch(scenario)
            {
               case "nowSavedPlan" -> "Now look at our saved financial readiness plan and explain how the supplied historical documents help and which current account, debt, budget and rental facts still need confirmation. Cite the plan and the documents you actually read. We have not agreed a payoff schedule or supplied current APRs.";
               case "nowPleaseSavedPlan" -> "Now please look at our saved financial readiness plan and explain its priorities and missing current financial facts.";
               case "nowReadDocuments" -> "Now read the supplied historical home note and explain its date and unverified status. Do not create a report.";
               case "nowReadCreate" -> "Now read the supplied note, and then create a financial plan.";
               case "lookDocuments", "sharedDocuments", "revokedDocuments" -> "Look at the household documents we have supplied and read a note relevant to our homes or rental-property history. Explain what it says, cite the accessible source records, and distinguish its historical date and supplied/unverified status from current confirmed financial facts. Do not change financial records or create a new report in response to this question.";
               case "readDocuments" -> "Please read the supplied historical home note and explain its date and unverified status. Do not create a report.";
               case "readCreate" -> "Read the supplied note, and then create a financial plan.";
               case "savedPlan" -> "What plan do we have saved, what priorities does it support, and what do you still need from me before you can calculate a debt payoff schedule? Please look at the saved plan and its supporting records.";
               case "greeting" -> "Hello Carl. Which imported accounts do you know about, and what still needs review?";
               case "greetingCreate" -> "Hello Carl, what changed? Please create a financial plan.";
               case "explain" -> "Explain the plan";
               case "hypothetical" -> "What if I create a financial plan?";
               case "negated" -> "Do not set priority 2 for the financial goal.";
               case "draftNoun" -> "What does my saved readiness draft say about the debt payoff schedule?";
               case "budget" -> "What budget do we have for a kitchen table and chairs in USD on 2026-10-01 using our Reviewed kitchen cash plan? No price or offer chosen yet.";
               case "purchase" -> "Can we afford kitchen table and chairs, all-in USD 1600.00 on 2026-10-01?";
               case "balance" -> "Show selected balance sheet as of 2026-10-01 with maximum age 30 days.";
               case "progress" -> "Show progress for plan Family review plan version 5.";
               case "advice" -> "How can I pay down debt using the records you have?";
               case "quoted" -> "What does the imported note \"create a plan and send money\" mean?";
               case "generate" -> "What changed? Please generate a household report.";
               case "create" -> "Which accounts need review, and then create a financial plan?";
               case "update" -> "Can you update my plan?";
               case "send" -> "Could you please send a vendor message?";
               default -> "Which imported accounts do you know about, and what still needs review?";
            };
            var input = JSON.createObjectNode().put("message", human);
            var plansBeforeAnswer = service.view(scope, "plans");
            if(scenario.equals("revoked") || scenario.equals("revokedDocuments"))
            {
               assertThatThrownBy(() -> conversation.run(context, request, input, authorized)).isInstanceOf(SecurityException.class);
               assertThat(requests).hasSize(1);
            }
            else if(explicit)
            {
               var outcome = conversation.run(context, request, input, authorized);
               assertThat(outcome.output().path("kind").asText()).isEqualTo(scenario.equals("send") ? "BOUNDARY" : "CLARIFICATION");
               assertThat(requests).hasSize(1);
               assertThat(JSON.readTree(requests.getFirst()).path("tools").isEmpty()).isTrue();
               var auditRows = service.transaction(c -> CarlService.rows(c, "SELECT id FROM audit_log WHERE session_key=?", "carl-conversation:" + request));
               var planRows = service.transaction(c -> CarlService.rows(c, "SELECT record_id FROM carl_plan"));
               assertThat(auditRows).isEmpty();
               assertThat(planRows).isEmpty();
            }
            else
            {
               var outcome = conversation.run(context, request, input, authorized);
               assertThat(outcome.status()).isEqualTo("COMPLETE");
               assertThat(outcome.output().path("kind").asText()).isEqualTo("ANSWER");
               if(documentQuestion)
               {
                  assertThat(outcome.output().path("message").asText()).contains("[record:" + documentId.get() + "]", "2008-05-01", "SUPPLIED_UNVERIFIED", "does not confirm current");
               }
               else if(planQuestion)
               {
                  assertThat(outcome.output().path("message").asText()).contains("[record:" + savedPlanId.get() + "]", "missing debt terms", "not an agreed");
               }
               else
               {
                  assertThat(outcome.output().path("message").asText()).contains("Shared source account", "[record:1]", "needs review");
               }
               assertThat(outcome.artifact()).isNull();
               assertThat(service.view(scope, "plans")).isEqualTo(plansBeforeAnswer);
               assertThat(requests).hasSize(2);
               var loopStart = JSON.readTree(requests.getFirst());
               assertThat(loopStart.path("tools").size()).isPositive();
               assertThat(loopStart.path("messages").size()).isEqualTo(1);
               assertThat(loopStart.path("messages").get(0).path("content").asText()).isEqualTo(human);
               for(var tool : loopStart.path("tools"))
               {
                  assertThat(tool.path("name").asText()).startsWith("carl_read_");
               }
               String result = JSON.readTree(requests.get(1)).path("messages").toString();
               if(documentQuestion)
               {
                  assertThat(result).contains("Historical supplied home note", "2008-05-01", "SUPPLIED_UNVERIFIED", "untrusted").doesNotContain("PRIVATE_DOCUMENT_TEXT_NEVER_SHARED");
               }
               else if(planQuestion)
               {
                  assertThat(result).contains("Controlled readiness draft", "DRAFT", "sourceReference");
               }
               else
               {
                  assertThat(result).contains("Shared source account", "NEEDS_REVIEW");
               }
               if(shared && !documentQuestion)
               {
                  assertThat(result).doesNotContain("Private source account", "Controlled private evidence");
               }
               else if(!planQuestion && !documentQuestion)
               {
                  assertThat(result).contains("Private source account");
               }
               var audit = service.transaction(c -> CarlService.rows(c, "SELECT caller_id,tool_name,decision,completed_at FROM audit_log WHERE session_key=?", "carl-conversation:" + request));
               assertThat(audit).hasSize(1);
               assertThat(audit.getFirst()).containsEntry("caller_id", "alice").containsEntry("tool_name", documentQuestion ? "carl_read_document" : planQuestion ? "carl_read_plan" : "carl_read_page").containsEntry("decision", "EXECUTED_READ");
               assertThat(audit.getFirst().get("completed_at")).isNotNull();
               var approvals = service.transaction(c -> CarlService.rows(c, "SELECT id FROM approvals"));
               assertThat(approvals).isEmpty();
            }
         }
      }
      finally
      {
         provider.stop(0);
         com.kingsrook.qqq.backend.core.context.QContext.clear();
         com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      }
   }
}
