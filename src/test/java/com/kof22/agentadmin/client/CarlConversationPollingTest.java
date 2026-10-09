/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin.client;


import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/** Controlled test transport reproduces the real servlet quota; it is not real owner/provider acceptance. */
class CarlConversationPollingTest
{
   @org.junit.jupiter.params.ParameterizedTest
   @org.junit.jupiter.params.provider.ValueSource(strings = {"capacity", "missing", "zero", "negative", "invalid", "too-long"})
   void pollingRejectsOtherErrorsAndInvalidRetryDelays(String scenario) throws Exception
   {
      var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange ->
      {
         String delay = switch(scenario)
         {
            case "zero" -> "0";
            case "negative" -> "-1";
            case "invalid" -> "later";
            case "too-long" -> "61";
            default -> "2";
         };
         if(!scenario.equals("missing"))
         {
            exchange.getResponseHeaders().set("Retry-After", delay);
         }
         byte[] body = ("{\"error\":{\"code\":\"" + (scenario.equals("capacity") ? "capacity" : "rate_limit") + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
         exchange.sendResponseHeaders(429, body.length);
         try(var out = exchange.getResponseBody())
         {
            out.write(body);
         }
      });
      server.start();
      try(var http = HttpClient.newHttpClient())
      {
         var method = CarlConversationHttpTest.class.getDeclaredMethod("await", HttpClient.class, String.class, String.class);
         method.setAccessible(true);
         assertThatThrownBy(() -> method.invoke(null, http, "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic")).isInstanceOf(java.lang.reflect.InvocationTargetException.class).hasCauseInstanceOf(AssertionError.class);
      }
      finally
      {
         server.stop(0);
      }
   }



   @Test
   void pollingHonorsTheActualSixtyRequestQuotaAndRecoversWithoutRestartingWork() throws Exception
   {
      var identity = mock(ClientIdentity.class);
      var clients = mock(ClientService.class);
      var member = new FamilyAccess.Member("synthetic-member", "synthetic-family", "synthetic-caller", "1");
      when(identity.verify(any())).thenReturn(member);
      when(clients.workflow(any(), any(), any(), any(), any())).thenReturn(new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, null, "Controlled completion", new ObjectMapper().createObjectNode()));
      int port;
      try(var socket = new ServerSocket(0))
      {
         port = socket.getLocalPort();
      }
      String origin = "http://127.0.0.1:" + port;
      var servlet = new ClientServlet(identity, mock(ClientStore.class), clients, origin, () -> true);
      var server = Javalin.create(config -> config.jetty.modifyServletContextHandler(handler -> handler.addServlet(new ServletHolder(servlet), "/api/agent/v1/*"))).start("127.0.0.1", port);
      try(var http = HttpClient.newHttpClient())
      {
         var request = HttpRequest.newBuilder(URI.create(origin + "/api/agent/v1/me")).header("Authorization", "Bearer synthetic").GET().build();
         for(int index = 0; index < 60; index++)
         {
            assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
         }
         var rejected = http.send(request, HttpResponse.BodyHandlers.ofString());
         assertEquals(429, rejected.statusCode());
         assertThat(rejected.headers().firstValue("Retry-After")).contains("2");
         assertThat(rejected.body()).contains("rate_limit");
         var method = CarlConversationHttpTest.class.getDeclaredMethod("await", HttpClient.class, String.class, String.class);
         method.setAccessible(true);
         var result = (com.fasterxml.jackson.databind.JsonNode) method.invoke(null, http, origin + "/api/agent/v1/conversations/" + UUID.randomUUID() + "/workflows/conversation/" + UUID.randomUUID(), "synthetic");
         assertEquals("COMPLETE", result.path("status").asText());
         org.mockito.Mockito.verify(clients, org.mockito.Mockito.times(1)).workflow(any(), any(), any(), any(), org.mockito.ArgumentMatchers.isNull());
      }
      finally
      {
         server.stop();
      }
   }
}
