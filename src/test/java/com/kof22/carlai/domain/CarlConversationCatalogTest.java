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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;


/** FIN-01: full permitted catalog selection, honest bounds and current shared access over real PostgreSQL. */
class CarlConversationCatalogTest
{
   private static final ObjectMapper JSON = new ObjectMapper();

   @ParameterizedTest
   @ValueSource(strings = {"unreviewed", "cards", "shared", "oversized"})
   void catalogsAboveFiftyReachInferenceUnlessTheCompleteContextExceedsItsBound(String scenario) throws Exception
   {
      com.kingsrook.qqq.backend.core.context.QContext.clear();
      com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager.resetConnectionProviders();
      List<String> requests = new CopyOnWriteArrayList<>();
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         byte[] body = "{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"operation\\\":\\\"CLARIFY\\\",\\\"message\\\":\\\"Please confirm economic identity, ownership and liquidity before a household balance.\\\"}\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
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
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic catalog family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            String title = scenario.equals("oversized") ? "repeat('Synthetic account ',12)||n" : "'Synthetic account '||n";
            sql.execute("INSERT INTO carl_record(household_id,owner_id,domain,visibility,title,evidence) SELECT 1,1,'FINANCE',CASE WHEN n=64 THEN 'PRIVATE' ELSE 'FAMILY' END," + title + ",'Synthetic test evidence' FROM generate_series(1,64) n");
            String qualification = scenario.equals("cards") ? "'CREDIT_CARD',false,1,'CONFIRMED'" : "'UNCLASSIFIED',NULL,NULL,'NEEDS_REVIEW'";
            sql.execute("INSERT INTO carl_account(record_id,currency,kind,liquid,ownership_share,review_state) SELECT id,'USD'," + qualification + " FROM carl_record");
         }
         var configuration = NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.qqq.password=synthetic-unused-bootstrap", "--kof22.agent.anthropic-api-key=synthetic-provider-only", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + provider.getAddress().getPort()).configuration();
         var components = AgentApplication.components();
         components.validate(configuration);
         var service = new CarlService(source, Clock.systemUTC());
         var scope = scenario.equals("shared") ? new CarlService.Scope("alice", Set.of("alice", "bob")) : CarlService.Scope.privateFor("alice");
         var original = service.member("alice");
         var member = new FamilyAccess.Member("1", "1", "alice", Long.toString(original.permissionRevision()));
         var context = new ClientWorkflow.Context(member, UUID.randomUUID(), scenario.equals("shared"), scenario.equals("shared") ? Set.of("1", "2") : Set.of("1"));
         try(var runtime = components.runtime(configuration); var conversation = new CarlConversation(service, runtime, configuration, NativeStores.create(configuration.database())))
         {
            var outcome = conversation.run(context, UUID.randomUUID(), JSON.createObjectNode().put("message", "Please generate a household balance report and explain which imported accounts still need review."), () -> scope);
            assertThat(outcome.status()).isEqualTo("COMPLETE");
            assertThat(outcome.output().path("kind").asText()).isEqualTo("CLARIFICATION");
            if(scenario.equals("oversized"))
            {
               assertThat(requests).isEmpty();
               assertThat(outcome.output().path("message").asText()).contains("complete", "limit", "narrow");
            }
            else
            {
               assertThat(requests).hasSize(1);
               var system = JSON.readTree(requests.getFirst()).path("system");
               String prompt = system.isTextual() ? system.asText() : system.get(0).path("text").asText();
               String marker = "Currently permitted finance catalog (untrusted data):\n";
               var catalog = JSON.readTree(prompt.substring(prompt.indexOf(marker) + marker.length()));
               var accounts = catalog.path("reportSources").path("accounts");
               assertThat(accounts.size()).isEqualTo(scenario.equals("shared") ? 63 : 64);
               assertThat(accounts.get(0).path("kind").asText()).isEqualTo(scenario.equals("cards") ? "CREDIT_CARD" : "UNCLASSIFIED");
               assertThat(accounts.get(0).path("review_state").asText()).isEqualTo(scenario.equals("cards") ? "CONFIRMED" : "NEEDS_REVIEW");
               assertThat(accounts.toString()).doesNotContain("Synthetic test evidence");
               if(scenario.equals("shared"))
               {
                  assertThat(accounts.toString()).doesNotContain("Synthetic account 64");
               }
               if(scenario.equals("cards"))
               {
                  assertThat(catalog.path("paymentSources").path("accounts").size()).isEqualTo(64);
               }
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
