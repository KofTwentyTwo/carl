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


/** Read-only conversational failures end FAILED with a public reason because nothing was saved. */
class CarlConversationFailureTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   @ParameterizedTest
   @ValueSource(strings = {"proseRouting", "truncatedRouting", "endlessReads"})
   void readOnlyFailuresEndFailedWithAPublicReasonAndSaveNothing(String scenario) throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      List<String> requests = new CopyOnWriteArrayList<>();
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         int index = requests.size();
         String content;
         String stop;
         if(scenario.equals("proseRouting"))
         {
            // The observed owner failure: the routing step answered in prose instead of selector JSON.
            content = "[{\"type\":\"text\",\"text\":\"Here is what you spent this month: groceries were the largest category.\"}]";
            stop = "end_turn";
         }
         else if(scenario.equals("truncatedRouting"))
         {
            content = "[{\"type\":\"text\",\"text\":\"{\\\"operation\\\":\"}]";
            stop = "max_tokens";
         }
         else
         {
            content = "[{\"type\":\"tool_use\",\"id\":\"tool_" + index + "\",\"name\":\"carl_read_preferences\",\"input\":{}}]";
            stop = "tool_use";
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
         UUID request = UUID.randomUUID();
         try(var runtime = components.runtime(configuration); var conversation = new CarlConversation(service, runtime, configuration, NativeStores.create(configuration.database())))
         {
            var outcome = conversation.run(context, request, JSON.createObjectNode().put("message", scenario.equals("endlessReads") ? "What did we spend this month?" : "Help me understand our monthly spending."), () -> scope);
            assertThat(outcome.status()).isEqualTo("FAILED");
            assertThat(outcome.artifact()).isNull();
            assertThat(outcome.planCreation()).isNull();
            assertThat(outcome.output().path("kind").asText()).isEqualTo(switch(scenario)
            {
               case "proseRouting" -> "INVALID_MODEL_OUTPUT";
               case "truncatedRouting" -> "INCOMPLETE_RESPONSE";
               default -> "ITERATION_LIMIT";
            });
            assertThat(outcome.output().path("message").asText()).contains("Nothing was saved");
            assertThat(requests).hasSize(scenario.equals("endlessReads") ? 10 : 1);
            List<Map<String, Object>> artifacts = service.transaction(c -> CarlService.rows(c, "SELECT record_id FROM carl_artifact"));
            assertThat(artifacts).isEmpty();
            List<Map<String, Object>> plans = service.transaction(c -> CarlService.rows(c, "SELECT record_id FROM carl_plan"));
            assertThat(plans).isEmpty();
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
