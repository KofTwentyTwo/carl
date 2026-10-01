/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.kof22.agentadmin.NativeChat;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.CarlTalkService;


/** Browser presentation invokes Carl's existing host-owned conversation service and current permissions. */
final class CarlNativeChat implements NativeChat
{
   private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
   private final CarlService service;
   private final CarlTalkService talk;

   CarlNativeChat(CarlService service, CarlTalkService talk)
   {
      this.service = service;
      this.talk = talk;
   }



   @Override
   public String label()
   {
      return "Chat with Carl";
   }



   @Override
   public boolean sharingEnabled()
   {
      return true;
   }



   @Override
   public List<Choice> threads(String principal)
   {
      var threads = new LinkedHashMap<String, Choice>();
      for(var choice : talk.choices(principal))
      {
         threads.putIfAbsent(choice.id().split("/", 2)[0], new Choice(choice.id(), choice.label()));
      }
      return List.copyOf(threads.values());
   }



   @Override
   public List<Choice> members(String principal)
   {
      return talk.participantChoices(principal).stream().filter(choice -> !choice.id().contains(","))
         .map(choice -> new Choice(choice.id(), choice.label())).toList();
   }



   @Override
   public History history(String principal, String selected)
   {
      var turns = talk.history(principal, selected);
      var messages = new ArrayList<Message>();
      int bytes = 0;
      boolean truncated = turns.size() == 50;
      for(var turn : turns.reversed())
      {
         var reply = response(turn.response());
         var pair = List.of(new Message("user", turn.message(), null, turn.selected()), new Message("assistant", reply.text(), reply.status(), reply.selected()));
         int size = encodedSize(pair);
         if(bytes + size > 200000)
         {
            truncated = true;
            break;
         }
         messages.addAll(0, pair);
         bytes += size;
      }
      if(truncated)
      {
         messages.addFirst(new Message("assistant", "Earlier messages are omitted from this window to keep history bounded.", null, null));
      }
      String latest = turns.isEmpty() ? selected : turns.getLast().selected();
      return new History(latest, List.copyOf(messages));
   }



   @Override
   public Response start(String principal, UUID request, String message, boolean shared, Set<String> participants)
   {
      Set<String> audience = Set.of();
      if(shared)
      {
         var permitted = members(principal).stream().map(Choice::id).collect(java.util.stream.Collectors.toSet());
         if(participants.isEmpty() || !permitted.containsAll(participants))
         {
            throw new SecurityException("Shared audience unavailable");
         }
         var selected = new java.util.LinkedHashSet<>(participants);
         selected.add(Long.toString(service.member(principal).id()));
         audience = Set.copyOf(selected);
      }
      else if(!participants.isEmpty())
      {
         throw new IllegalArgumentException("Private conversation cannot contain shared participants");
      }
      return response(talk.start(principal, request, message, shared, audience));
   }



   @Override
   public Response reply(String principal, String selected, UUID request, String message)
   {
      return response(talk.reply(principal, selected, request, message));
   }



   @Override
   public Response read(String principal, String selected)
   {
      return response(talk.read(principal, selected));
   }



   private static int encodedSize(Object value)
   {
      try
      {
         return JSON.writeValueAsBytes(value).length;
      }
      catch(com.fasterxml.jackson.core.JsonProcessingException failed)
      {
         throw new IllegalStateException("Chat presentation serialization failed", failed);
      }
   }



   private static Response response(CarlTalkService.Message message)
   {
      var result = message.result();
      String text = switch(result.status())
      {
         case PENDING -> "Your message is saved. Waiting for Carl.";
         case UNKNOWN -> "The outcome is unknown. No completed answer is available; this message will not be sent again automatically.";
         case FAILED -> "Carl could not complete this response. No completed answer is available. You can start a new question when the connection is available.";
         case COMPLETE, PARTIAL -> result.summary() == null ? "Saved response" : result.summary();
      };
      if((result.status() == ClientWorkflow.Status.COMPLETE || result.status() == ClientWorkflow.Status.PARTIAL) && result.artifact() != null)
      {
         var report = result.artifact().path("savedReport");
         String narrative = report.path("narrative").asText("");
         if(!narrative.isBlank())
         {
            text = narrative;
         }
         if(report.path("stale").asBoolean(false))
         {
            text = "Saved answer — source records have changed. Ask Carl for an updated answer before using these figures.\n\n" + text;
         }
         String generated = report.path("created_at").asText("");
         if(!generated.isBlank())
         {
            text += "\n\nSaved at: " + generated;
         }
         String limitations = report.path("limitations").asText("");
         if(!limitations.isBlank())
         {
            text += "\n\nLimitations: " + limitations;
         }
      }
      return new Response(message.conversation() + "/" + message.request(), result.status(), text.substring(0, Math.min(16000, text.length())));
   }
}
