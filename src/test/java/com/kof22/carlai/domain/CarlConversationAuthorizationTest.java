/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;


/** Revocation after context assembly must prevent SDK transmission, not merely reject the answer later. */
class CarlConversationAuthorizationTest
{
   @Test
   void contextRevocationBeforeInferenceMakesZeroRealProviderRequests() throws Exception
   {
      var calls = new AtomicInteger();
      var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext("/", exchange ->
      {
         calls.incrementAndGet();
         byte[] body = "{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"operation\\\":\\\"CLARIFY\\\",\\\"message\\\":\\\"Select a period\\\"}\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic authorization family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT 1,d,true FROM unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
         }
         var configuration = NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.qqq.password=synthetic-unused-bootstrap", "--kof22.agent.anthropic-api-key=synthetic-provider-only", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + provider.getAddress().getPort()).configuration();
         var components = AgentApplication.components();
         components.validate(configuration);
         try(var runtime = components.runtime(configuration))
         {
            var service = spy(new CarlService(source, Clock.systemUTC()));
            var original = service.member("alice");
            doAnswer(invocation ->
            {
               Object result = invocation.callRealMethod();
               try(var c = source.getConnection(); var sql = c.createStatement())
               {
                  sql.execute("DELETE FROM carl_permission WHERE member_id=1 AND domain='BILLS'");
               }
               return result;
            }).when(service).view(any(CarlService.Scope.class), eq("reminderObservations"));
            var member = new FamilyAccess.Member("1", "1", "alice", Long.toString(original.permissionRevision()));
            var context = new ClientWorkflow.Context(member, UUID.randomUUID(), false, Set.of("1"));
            try(var conversation = new CarlConversation(service, runtime, configuration, NativeStores.create(configuration.database())))
            {
               assertThatThrownBy(() -> conversation.run(context, UUID.randomUUID(), new ObjectMapper().createObjectNode().put("message", "Please make a household report for September 2026"), () ->
               {
                  if(!original.equals(service.member("alice")))
                  {
                     throw new SecurityException("Synthetic access revoked");
                  }
                  return CarlService.Scope.privateFor("alice");
               })).isInstanceOf(SecurityException.class);
            }
            assertThat(calls.get()).isZero();
         }
      }
      finally
      {
         provider.stop(0);
      }
   }
}
