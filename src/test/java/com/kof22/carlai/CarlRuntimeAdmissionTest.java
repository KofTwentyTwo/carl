/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.runtime.AgentInvocation;
import com.kof22.agentcore.runtime.AgentRuntimeException;
import com.kof22.agentcore.runtime.ConversationTurn;
import com.kof22.agentcore.runtime.ModelSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** The composed runtime is shared by ordinary chat and explicit conversational workflows. */
class CarlRuntimeAdmissionTest
{
   @Test
   void oneConfiguredInferenceSlotCannotBeDoubledByAnotherEntryPoint() throws Exception
   {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var requests = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      try(var providerWorkers = Executors.newVirtualThreadPerTaskExecutor(); var calls = Executors.newVirtualThreadPerTaskExecutor())
      {
         server.setExecutor(providerWorkers);
         server.createContext("/v1/messages", exchange ->
         {
            exchange.getRequestBody().readAllBytes();
            if(requests.incrementAndGet() == 1)
            {
               started.countDown();
               try
               {
                  if(!release.await(10, TimeUnit.SECONDS))
                  {
                     throw new IllegalStateException("Fixture release missing");
                  }
               }
               catch(InterruptedException interrupted)
               {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
               }
            }
            byte[] bytes = "{\"id\":\"msg_synthetic\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":[{\"type\":\"text\",\"text\":\"Controlled reply\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
         });
         server.start();
         var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.anthropic-api-key=synthetic-key", "--kof22.agent.anthropic-base-url=http://127.0.0.1:" + server.getAddress().getPort(), "--kof22.agent.limits.max-concurrent-turns=1");
         try(var runtime = AgentApplication.components().runtime(configuration))
         {
            var first = calls.submit(() -> runtime.run(invocation()));
            try
            {
               assertTrue(started.await(5, TimeUnit.SECONDS));
               assertThrows(AgentRuntimeException.class, () -> runtime.run(invocation()));
               assertEquals(1, requests.get());
            }
            finally
            {
               release.countDown();
            }
            assertEquals("Controlled reply", first.get(5, TimeUnit.SECONDS).text());
            assertEquals("Controlled reply", runtime.run(invocation()).text());
            assertEquals(2, requests.get());
         }
         finally
         {
            server.stop(0);
         }
      }
   }



   @Test
   void unsupportedConversationalDeadlineFailsBeforeAnyRequestCanBecomePending() throws Exception
   {
      var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.limits.turn-timeout=PT1H");
      configuration.core().setPersonaPath(Path.of("prompts/PERSONA.md").toAbsolutePath().toString());
      var runtime = org.mockito.Mockito.mock(com.kof22.agentcore.runtime.AgentRuntime.class);
      var stores = org.mockito.Mockito.mock(com.kof22.agentadmin.bootstrap.NativeStores.class);
      assertThrows(IllegalArgumentException.class, () -> new com.kof22.carlai.domain.CarlConversation(null, runtime, configuration, stores));
      org.mockito.Mockito.verifyNoInteractions(runtime, stores);
   }



   @Test
   void interruptionDoesNotReleaseCapacityBeforeDelegateExitAndHostClosesOnce() throws Exception
   {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var closed = new AtomicInteger();
      var delegate = new com.kof22.agentcore.runtime.AgentRuntime()
      {
         @Override
         public com.kof22.agentcore.runtime.AgentReply run(AgentInvocation invocation)
         {
            entered.countDown();
            boolean waiting = true;
            while(waiting)
            {
               try
               {
                  waiting = !release.await(5, TimeUnit.SECONDS);
               }
               catch(InterruptedException deliberatelyUnresponsive)
               {
                  // Controlled provider remains active despite cancellation until explicitly released.
               }
            }
            return new com.kof22.agentcore.runtime.AgentReply("Finished", new com.kof22.agentcore.runtime.TokenUsage(1, 1));
         }



         @Override
         public void close()
         {
            closed.incrementAndGet();
         }
      };
      var runtime = com.kof22.carlai.domain.CarlConversation.admittedRuntime(delegate, 1);
      var worker = Thread.ofVirtual().start(() -> runtime.run(invocation()));
      try
      {
         assertTrue(entered.await(5, TimeUnit.SECONDS));
         worker.interrupt();
         assertThrows(AgentRuntimeException.class, () -> runtime.run(invocation()));
         assertTrue(worker.isAlive());
      }
      finally
      {
         release.countDown();
         worker.join(java.time.Duration.ofSeconds(5));
         runtime.close();
         runtime.close();
      }
      assertEquals(1, closed.get());
      assertThrows(AgentRuntimeException.class, () -> runtime.run(invocation()));
   }



   private static AgentInvocation invocation()
   {
      return new AgentInvocation("Controlled synthetic request; no external actions.", List.of(new ConversationTurn(ConversationTurn.Role.USER, "Prepare a report")), new ModelSettings("claude-sonnet-5", 100), List.of());
   }
}
