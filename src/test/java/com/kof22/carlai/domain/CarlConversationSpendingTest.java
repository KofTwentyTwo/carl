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
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.AgentApplication;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;


/** CHAT-01: an ordinary spending question is answered from one deterministic aggregate read instead of paging raw rows. */
class CarlConversationSpendingTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   @Test
   void spendingQuestionUsesOneAggregateReadAndCompletesInTwoProviderCalls() throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      List<String> requests = new CopyOnWriteArrayList<>();
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         boolean first = requests.size() == 1;
         String content = first
            ? "[{\"type\":\"tool_use\",\"id\":\"tool_spending\",\"name\":\"carl_read_spending\",\"input\":{\"from\":\"2026-09-01\",\"through\":\"2026-09-30\"}}]"
            : "[{\"type\":\"text\",\"text\":\"In September 2026 permitted USD outflows were 125.50 (Groceries 100.00, UNCATEGORIZED 25.50) with 10.00 of separate inflows; one transfer is excluded.\"}]";
         byte[] body = ("{\"id\":\"msg_controlled\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":" + content + ",\"stop_reason\":\"" + (first ? "tool_use" : "end_turn") + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
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
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled spending family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Controlled A',true),(2,1,'bob','Controlled B',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            sql.execute("INSERT INTO carl_record(id,household_id,owner_id,domain,visibility,title,evidence) VALUES(1,1,1,'FINANCE','FAMILY','Shared source account','Controlled import evidence'),"
               + "(2,1,1,'FINANCE','FAMILY','Grocer','Controlled row'),(3,1,1,'FINANCE','FAMILY','Unlabelled','Controlled row'),(4,1,1,'FINANCE','FAMILY','Refund','Controlled row'),"
               + "(5,1,1,'FINANCE','FAMILY','Savings move','Controlled row'),(6,1,2,'FINANCE','PRIVATE','Bob private purchase','Controlled private row')");
            sql.execute("INSERT INTO carl_account(record_id,currency,kind,liquid,ownership_share,review_state) VALUES(1,'USD','UNCLASSIFIED',NULL,NULL,'NEEDS_REVIEW')");
            sql.execute("INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,classification,category,source_id,transfer_key) VALUES"
               + "(2,1,'2026-09-03',-100.00,'USD','UNCLASSIFIED','Groceries','s2',NULL),(3,1,'2026-09-04',-25.50,'USD','UNCLASSIFIED','','s3',NULL),"
               + "(4,1,'2026-09-05',10.00,'USD','UNCLASSIFIED','Groceries','s4',NULL),(5,1,'2026-09-06',-300.00,'USD','TRANSFER','Transfer','s5','pair'),"
               + "(6,1,'2026-09-07',-444.44,'USD','EXPENSE','Groceries','s6',NULL)");
         }
         var configuration = NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=controlled-reader", "--kof22.agent.qqq.password=controlled-unused-bootstrap", "--kof22.agent.anthropic-api-key=controlled-provider-only", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + provider.getAddress().getPort()).configuration();
         var components = AgentApplication.components();
         components.validate(configuration);
         var service = new CarlService(source, Clock.systemUTC());
         var original = service.member("alice");
         var scope = CarlService.Scope.privateFor("alice");
         var context = new ClientWorkflow.Context(new FamilyAccess.Member("1", "1", "alice", Long.toString(original.permissionRevision())), UUID.randomUUID(), false, Set.of("1"));
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
            var outcome = conversation.run(context, request, JSON.createObjectNode().put("message", "What did we spend this month?"), authorized);
            assertThat(outcome.status()).isEqualTo("COMPLETE");
            assertThat(outcome.output().path("kind").asText()).isEqualTo("ANSWER");
            assertThat(outcome.output().path("message").asText()).contains("125.50", "Groceries");
            assertThat(outcome.artifact()).isNull();
            assertThat(requests).hasSize(2);
            var loopStart = JSON.readTree(requests.getFirst());
            var spending = java.util.stream.StreamSupport.stream(loopStart.path("tools").spliterator(), false).filter(tool -> tool.path("name").asText().equals("carl_read_spending")).findFirst().orElseThrow();
            assertThat(spending.path("description").asText()).contains("spending", "category");
            String result = JSON.readTree(requests.get(1)).path("messages").toString();
            assertThat(result).contains("125.50", "Groceries", "UNCATEGORIZED", "10.00", "300.00").doesNotContain("444.44", "Bob private purchase");
            var audit = service.transaction(c -> CarlService.rows(c, "SELECT caller_id,tool_name,decision FROM audit_log WHERE session_key=?", "carl-conversation:" + request));
            assertThat(audit).hasSize(1);
            assertThat(audit.getFirst()).containsEntry("caller_id", "alice").containsEntry("tool_name", "carl_read_spending").containsEntry("decision", "EXECUTED_READ");
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
