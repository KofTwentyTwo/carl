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
import java.util.function.IntFunction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeConfigurationFiles;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.bootstrap.NativeStores;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentadmin.client.FamilyAccess;
import com.kof22.agentcore.runtime.AgentRuntimeException;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;


/** Read-only conversational failures end FAILED with a public reason because nothing was saved; unparseable routing gets one corrective retry first. */
class CarlConversationFailureTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   private static final String SPENDING = "Help me understand our monthly spending.";
   private static final String CORRECTION = "Reply with only the JSON object required by the contract, with no other text.";
   private static final String PROSE = "Here is what you spent this month: groceries were the largest category.";
   private static final String CLARIFY = "{\"operation\":\"CLARIFY\",\"message\":\"Which month should I review?\"}";

   @ParameterizedTest
   @ValueSource(strings = {"proseRouting", "truncatedRouting", "endlessReads"})
   void readOnlyFailuresEndFailedWithAPublicReasonAndSaveNothing(String scenario) throws Exception
   {
      var run = converse(scenario.equals("endlessReads") ? "What did we spend this month?" : SPENDING, index -> switch(scenario)
      {
         // The observed owner failure: the routing step answered in prose instead of selector JSON.
         case "proseRouting" -> text(PROSE);
         case "truncatedRouting" -> new String[]{"[{\"type\":\"text\",\"text\":\"{\\\"operation\\\":\"}]", "max_tokens"};
         default -> new String[]{"[{\"type\":\"tool_use\",\"id\":\"tool_" + index + "\",\"name\":\"carl_read_preferences\",\"input\":{}}]", "tool_use"};
      });
      assertThat(run.outcome().status()).isEqualTo("FAILED");
      assertThat(run.outcome().artifact()).isNull();
      assertThat(run.outcome().planCreation()).isNull();
      assertThat(run.outcome().output().path("kind").asText()).isEqualTo(switch(scenario)
      {
         case "proseRouting" -> "INVALID_MODEL_OUTPUT";
         case "truncatedRouting" -> "INCOMPLETE_RESPONSE";
         default -> "ITERATION_LIMIT";
      });
      assertThat(run.outcome().output().path("message").asText()).contains("Nothing was saved");
      // Prose routing gets exactly one corrective retry before failing; truncation is a runtime failure and is never retried.
      assertThat(run.requests()).hasSize(switch(scenario)
      {
         case "proseRouting" -> 2;
         case "truncatedRouting" -> 1;
         default -> 10;
      });
      assertThat(run.saved()).isZero();
   }



   @ParameterizedTest
   @ValueSource(strings = {"```json\n" + CLARIFY + "\n```", "```\n" + CLARIFY + "\n```", "  ```JSON\n" + CLARIFY + "\n```\n"})
   void aFencedRoutingObjectIsAcceptedWithoutARetry(String reply) throws Exception
   {
      var run = converse(SPENDING, index -> text(reply));
      assertThat(run.outcome().status()).isEqualTo("COMPLETE");
      assertThat(run.outcome().output().path("kind").asText()).isEqualTo("CLARIFICATION");
      assertThat(run.outcome().output().path("message").asText()).isEqualTo("Which month should I review?");
      assertThat(run.requests()).hasSize(1);
      assertThat(run.saved()).isZero();
   }



   @Test
   void unparseableRoutingGetsOneReadOnlyCorrectiveRetry() throws Exception
   {
      var run = converse(SPENDING, index -> text(index == 1 ? PROSE : CLARIFY));
      assertThat(run.outcome().status()).isEqualTo("COMPLETE");
      assertThat(run.outcome().output().path("kind").asText()).isEqualTo("CLARIFICATION");
      assertThat(run.outcome().output().path("message").asText()).isEqualTo("Which month should I review?");
      assertThat(run.requests()).hasSize(2);
      assertThat(run.requests().get(0)).doesNotContain(CORRECTION).contains("Currently permitted finance catalog", SPENDING);
      assertThat(run.requests().get(1)).contains(CORRECTION, "Currently permitted finance catalog", SPENDING).doesNotContain("carl_read_", PROSE);
      assertThat(run.saved()).isZero();
   }



   @Test
   void aParsedButInvalidRoutingProposalIsNotRetried() throws Exception
   {
      var run = converse(SPENDING, index -> text("{\"operation\":\"CLARIFY\"}"));
      assertThat(run.outcome().status()).isEqualTo("FAILED");
      assertThat(run.outcome().output().path("kind").asText()).isEqualTo("INVALID_MODEL_OUTPUT");
      assertThat(run.requests()).hasSize(1);
      assertThat(run.saved()).isZero();
   }

   private record Run(CarlConversation.Outcome outcome, List<String> requests, int saved)
   {
   }

   /** A text reply that ends its turn normally: {content JSON, stop reason}. */
   private static String[] text(String value)
   {
      return new String[]{"[{\"type\":\"text\",\"text\":" + JSON.getNodeFactory().textNode(value) + "}]", "end_turn"};
   }



   /** Runs one owner turn against a fake provider and real PostgreSQL; replies are chosen by 1-based request index. */
   private static Run converse(String message, IntFunction<String[]> replies) throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      List<String> requests = new CopyOnWriteArrayList<>();
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         String[] reply = replies.apply(requests.size());
         byte[] body = ("{\"id\":\"msg_controlled\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":" + reply[0] + ",\"stop_reason\":\"" + reply[1] + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
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
         AgentMigrations.migrate(source);
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled failure family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Controlled A',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT 1,d,true FROM unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
         }
         var configuration = NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=controlled-reader", "--kof22.agent.qqq.password=controlled-unused-bootstrap", "--kof22.agent.anthropic-api-key=controlled-provider-only", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + provider.getAddress().getPort()).configuration();
         var components = AgentApplication.components();
         components.validate(configuration);
         var service = new CarlService(source, Clock.systemUTC());
         var original = service.member("alice");
         var scope = CarlService.Scope.privateFor("alice");
         var member = new FamilyAccess.Member("1", "1", "alice", Long.toString(original.permissionRevision()));
         var context = new ClientWorkflow.Context(member, UUID.randomUUID(), false, Set.of("1"));
         try(var runtime = components.runtime(configuration); var conversation = new CarlConversation(service, runtime, configuration, NativeStores.create(configuration.database())))
         {
            var outcome = conversation.run(context, UUID.randomUUID(), JSON.createObjectNode().put("message", message), () -> scope);
            List<Map<String, Object>> artifacts = service.transaction(c -> CarlService.rows(c, "SELECT record_id FROM carl_artifact"));
            List<Map<String, Object>> plans = service.transaction(c -> CarlService.rows(c, "SELECT record_id FROM carl_plan"));
            return new Run(outcome, List.copyOf(requests), artifacts.size() + plans.size());
         }
      }
      finally
      {
         provider.stop(0);
         com.kingsrook.qqq.backend.core.context.QContext.clear();
         com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      }
   }



   @Test
   void failuresAfterAPossibleWriteAreNeverConvertedToFailed()
   {
      var bounded = new AgentRuntimeException(AgentRuntimeException.Reason.DEADLINE, "deadline", null);
      assertThat(CarlConversation.failedBeforeWrite(bounded, true)).isNull();
      var failed = CarlConversation.failedBeforeWrite(bounded, false);
      assertThat(failed.status()).isEqualTo("FAILED");
      assertThat(failed.output().path("kind").asText()).isEqualTo("DEADLINE");
      assertThat(failed.output().path("message").asText()).contains("ran out of time", "Nothing was saved");
   }



   @Test
   void everyFailureMapsToAPublicSafeCode()
   {
      assertThat(WorkflowFailures.code(new AgentRuntimeException(AgentRuntimeException.Reason.CONTEXT_BUDGET, "secret detail", null))).isEqualTo("context_budget");
      assertThat(WorkflowFailures.code(new InvalidModelOutput("Invalid conversational output"))).isEqualTo("invalid_model_output");
      assertThat(WorkflowFailures.code(new SecurityException("revoked"))).isEqualTo("access_changed");
      assertThat(WorkflowFailures.code(new IllegalArgumentException("bad"))).isEqualTo("invalid_request");
      assertThat(WorkflowFailures.code(new IllegalStateException("database"))).isEqualTo("unavailable");
      assertThat(WorkflowFailures.code(new UnsupportedOperationException("other"))).isEqualTo("execution");
      for(String code : List.of("context_budget", "output_budget", "tool_budget", "iteration_limit", "deadline", "cancelled", "provider", "incomplete_response", "capacity", "invalid_model_output", "execution"))
      {
         assertThat(WorkflowFailures.message(code)).contains("Nothing was saved").doesNotContain("secret");
      }
   }
}
