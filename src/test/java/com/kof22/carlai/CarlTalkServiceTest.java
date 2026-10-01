/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.client.ClientIdentity;
import com.kof22.agentadmin.client.ClientService;
import com.kof22.agentadmin.client.ClientStore;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentcore.policy.DataProtection;
import com.kof22.agentcore.session.SessionManager;
import com.kof22.agentcore.store.AgentMigrations;
import com.kof22.carlai.domain.CarlFamilyAccess;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.CarlTalkService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;


/** Actual durable foundation conversations exercise native bridge identity and sharing boundaries. */
class CarlTalkServiceTest
{
   @Test
   void nativeAndClientUseOneHostStoreWithPrivateDefaultExplicitSharedAndCurrentRevocation() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         AgentMigrations.migrate(source);
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic family','America/Chicago'),(2,'Other family','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',false),(3,2,'eve','Eve',false)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
         }
         var service = new CarlService(source, Clock.systemUTC());
         service.importBills("alice", UUID.randomUUID(), "Synthetic bills", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic utility,Power,125.25,USD,2026-09-30,UNPAID,PRIVATE\nb,Synthetic utility,Gas,74.75,USD,2026-09-30,UNPAID,PRIVATE\n");
         var access = new CarlFamilyAccess(service);
         var store = new ClientStore(source);
         var starts = new AtomicInteger();
         var revokeChoice = new java.util.concurrent.atomic.AtomicBoolean();
         var outcomes = new java.util.HashMap<UUID, ClientWorkflow.Result>();
         var contexts = new java.util.HashMap<UUID, ClientWorkflow.Context>();
         var inputs = new java.util.HashMap<UUID, JsonNode>();
         var workflow = new ClientWorkflow()
         {
            @Override
            public Result start(Context context, UUID request, JsonNode input)
            {
               starts.incrementAndGet();
               contexts.put(request, context);
               inputs.put(request, input);
               var result = new Result(Status.PENDING, null, "Saved human request", null);
               outcomes.put(request, result);
               return result;
            }



            @Override
            public Result get(Context context, UUID request)
            {
               var result = outcomes.get(request);
               if(revokeChoice.compareAndSet(true, false))
               {
                  try(var c = source.getConnection(); var sql = c.createStatement())
                  {
                     sql.execute("DELETE FROM carl_permission WHERE member_id=1 AND domain='BILLS'");
                  }
                  catch(java.sql.SQLException failed)
                  {
                     throw new IllegalStateException(failed);
                  }
               }
               return result;
            }
         };
         try(var clients = new ClientService(store, mock(SessionManager.class), new ClientIdentity("https://synthetic.identity.example/", "carl-family", access), access, DataProtection.defaults(), Duration.ofSeconds(10), Map.of("conversation", workflow)))
         {
            var talk = new CarlTalkService(service);
            talk.ready(clients);
            talk.ready(clients);
            assertThatThrownBy(() -> talk.ready(mock(ClientService.class))).isInstanceOf(IllegalStateException.class);
            UUID request = UUID.randomUUID();
            var message = talk.start("alice", request, "What is due?", false, Set.of());
            var alice = service.member("alice");
            var member = new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(alice.permissionRevision()));
            assertThat(clients.conversation(member, message.conversation()).participants()).containsExactly("1");
            assertThat(contexts.get(request).shared()).isFalse();
            assertThat(inputs.get(request).path("message").asText()).isEqualTo("What is due?");
            String selected = message.conversation() + "/" + request;
            assertThat(talk.read("alice", selected).result().status()).isEqualTo(ClientWorkflow.Status.PENDING);
            assertThat(starts.get()).isEqualTo(1);
            assertThatThrownBy(() -> talk.read("bob", selected)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> talk.read("eve", selected)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> talk.reply("alice", selected, UUID.randomUUID(), "Again")).isInstanceOf(RuntimeException.class);
            long report = service.generateReport(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), java.time.LocalDate.parse("2026-09-01"), java.time.LocalDate.parse("2026-09-30"), facts -> "The verified unpaid USD bills total 200.00. Calendar coverage is incomplete.");
            outcomes.put(request, new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, Long.toString(report), "Verified facts", new ObjectMapper().createObjectNode().put("message", "Verified facts")));
            var saved = talk.read("alice", selected).result().artifact().path("savedReport");
            assertThat(saved.path("narrative").asText()).contains("200.00", "Calendar coverage is incomplete");
            assertThat(saved.path("facts").toString()).contains("125.25", "74.75");
            var reply = talk.reply("alice", selected, UUID.randomUUID(), "Explain the sources");
            assertThat(reply.conversation()).isEqualTo(message.conversation());
            assertThat(inputs.get(reply.request()).path("replyTo").asText()).isEqualTo(request.toString());
            assertThat(talk.participantChoices("alice")).containsExactly(new CarlTalkService.Choice("2", "Bob"));
            assertThat(talk.participants("alice", "2")).containsExactlyInAnyOrder("1", "2");
            assertThatThrownBy(() -> talk.participants("alice", "3")).isInstanceOf(IllegalArgumentException.class);
            var shared = talk.start("alice", UUID.randomUUID(), "Shared family brief", true, talk.participants("alice", "2"));
            assertThat(talk.read("bob", shared.conversation() + "/" + shared.request()).result().status()).isEqualTo(ClientWorkflow.Status.PENDING);
            assertThat(contexts.get(shared.request()).participants()).containsExactlyInAnyOrder("1", "2");
            assertThatThrownBy(() -> talk.start("alice", UUID.randomUUID(), "Forged scope", true, Set.of("1", "3"))).isInstanceOf(RuntimeException.class);
            try(var c = source.getConnection(); var sql = c.prepareStatement("INSERT INTO carl_client_workflow(request_id,conversation_id,requester_id,kind,audience,permission_revision,input_digest,status,conversation_message) VALUES(?,?,1,'conversation','1',?,?,'COMPLETE','Private saved-message preview')"))
            {
               sql.setObject(1, request);
               sql.setObject(2, message.conversation());
               sql.setLong(3, service.member("alice").permissionRevision());
               sql.setString(4, "0".repeat(64));
               sql.executeUpdate();
            }
            assertThat(talk.choices("alice")).hasSize(1);
            revokeChoice.set(true);
            assertThatThrownBy(() -> talk.choices("alice")).isInstanceOf(SecurityException.class);
            try(var c = source.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_member SET active=false WHERE principal='alice'");
            }
            assertThatThrownBy(() -> talk.read("alice", selected)).isInstanceOf(RuntimeException.class);
         }
      }
   }



   @Test
   void noHostNeverCreatesAnotherConversationService()
   {
      var talk = new CarlTalkService(mock(CarlService.class));
      assertThatThrownBy(() -> talk.ready(null)).isInstanceOf(NullPointerException.class);
   }
}
