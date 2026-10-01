/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.client.ClientFailure;
import com.kof22.agentadmin.client.ClientService;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentadmin.client.FamilyAccess;


/** Native conversation bridge into the exact service owned by the running foundation host. */
public final class CarlTalkService
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private final CarlService service;
   private volatile ClientService clients;

   /** Does not construct a second session store, runtime, or workflow worker owner. */
   public CarlTalkService(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** The application host supplies its already-created client service. */
   public synchronized void ready(ClientService clientService)
   {
      if(clients != null && clients != clientService)
      {
         throw new IllegalStateException("Carl conversation service is already owned by another host");
      }
      clients = java.util.Objects.requireNonNull(clientService);
   }

   /** Immutable identifiers tie an explicit human message to its durable workflow. */
   public record Message(UUID conversation, UUID request, ClientWorkflow.Result result)
   {
   }



   /** Current authorized choices contain identifiers and status only, never another member's text. */
   public record Choice(String id, String label)
   {
   }

   private ClientService clients()
   {
      var current = clients;
      if(current == null)
      {
         throw new ClientFailure(503, "conversation_unavailable");
      }
      return current;
   }



   private FamilyAccess.Member member(String principal)
   {
      var member = service.member(principal);
      NativeReadScope.check(member);
      return new FamilyAccess.Member(Long.toString(member.id()), Long.toString(member.householdId()), member.principal(), Long.toString(member.permissionRevision()));
   }



   /** Starts private by default; shared participants are an explicit frozen current membership subset. */
   public Message start(String principal, UUID request, String text, boolean shared, Set<String> participants)
   {
      var member = member(principal);
      UUID conversation = UUID.nameUUIDFromBytes(("carl-native-conversation:" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var client = clients();
      client.create(member, conversation, shared, shared ? participants : Set.of(member.id()));
      var input = JSON.createObjectNode().put("message", text);
      return new Message(conversation, request, client.workflow(member, conversation, "conversation", request, input));
   }



   /** Follow-up uses only completed protected history; UNKNOWN and PENDING are never replayed. */
   public Message reply(String principal, String selected, UUID request, String text)
   {
      UUID[] ids = ids(selected);
      var member = member(principal);
      var client = clients();
      var previous = client.workflow(member, ids[0], "conversation", ids[1], null);
      if(previous.status() != ClientWorkflow.Status.COMPLETE && previous.status() != ClientWorkflow.Status.PARTIAL)
      {
         throw new ClientFailure(409, "history_unavailable");
      }
      return new Message(ids[0], request, client.workflow(member, ids[0], "conversation", request, JSON.createObjectNode().put("message", text).put("replyTo", ids[1].toString())));
   }



   /** Reading the same request rechecks current membership, conversation audience, and source access. */
   public Message read(String principal, String selected)
   {
      UUID[] ids = ids(selected);
      var member = member(principal);
      var client = clients();
      var result = client.workflow(member, ids[0], "conversation", ids[1], null);
      if(result.artifactId() != null && result.artifactId().matches("[1-9][0-9]{0,17}") && result.artifact() != null)
      {
         var conversation = client.conversation(member, ids[0]);
         var audience = service.transaction(c -> CarlService.rows(c, "SELECT id,principal FROM carl_member WHERE household_id=? AND active", Long.parseLong(member.household()))).stream()
            .filter(row -> conversation.participants().contains(row.get("id").toString())).map(row -> row.get("principal").toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());
         if(audience.size() != conversation.participants().size())
         {
            throw new SecurityException("Carl conversation audience changed");
         }
         long artifact = Long.parseLong(result.artifactId());
         audience.forEach(recipient -> service.artifact(recipient, artifact));
         var saved = service.artifact(principal, artifact);
         String facts = saved.get("facts").toString();
         if(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2097152)
         {
            throw new IllegalArgumentException("Saved facts exceed the protected report view bound");
         }
         try
         {
            var output = result.artifact().deepCopy();
            var report = ((com.fasterxml.jackson.databind.node.ObjectNode) output).putObject("savedReport");
            for(String key : List.of("kind", "narrative", "limitations", "status_label", "narration_state", "stale", "period_start", "period_end", "created_at"))
            {
               report.set(key, JSON.valueToTree(saved.get(key)));
            }
            report.set("facts", JSON.readTree(facts));
            requireUnchanged(member, principal);
            result = new ClientWorkflow.Result(result.status(), result.artifactId(), result.summary(), output);
         }
         catch(java.io.IOException invalid)
         {
            throw new IllegalStateException("Stored report facts unavailable", invalid);
         }
      }
      requireUnchanged(member, principal);
      return new Message(ids[0], ids[1], result);
   }



   /** Bounded current conversations and protected workflow checks power native saved-message selection. */
   public List<Choice> choices(String principal)
   {
      var member = member(principal);
      var client = clients();
      var result = new ArrayList<Choice>();
      for(var conversation : client.conversations(member, 0, 250))
      {
         var rows = service.transaction(c -> CarlService.rows(c, "SELECT request_id,status,conversation_message,created_at FROM carl_client_workflow WHERE conversation_id=? AND kind='conversation' AND permission_revision=? ORDER BY created_at DESC LIMIT 250", conversation.id(), Long.parseLong(member.permissionRevision())));
         for(var row : rows)
         {
            UUID request = UUID.fromString(row.get("request_id").toString());
            try
            {
               var outcome = client.workflow(member, conversation.id(), "conversation", request, null);
               result.add(new Choice(conversation.id() + "/" + request, (conversation.shared() ? "Shared family conversation" : "Private conversation") + " | " + outcome.status() + " | " + row.get("created_at") + " | " + preview(row.get("conversation_message"))));
            }
            catch(ClientFailure denied)
            {
               if(!Set.of("workflow_unavailable", "not_found").contains(denied.getMessage()))
               {
                  throw denied;
               }
            }
            if(result.size() == 250)
            {
               requireUnchanged(member, principal);
               return List.copyOf(result);
            }
         }
      }
      requireUnchanged(member, principal);
      return List.copyOf(result);
   }



   /** Sharing choices reveal only active people in the caller's current household. */
   public List<Choice> participantChoices(String principal)
   {
      var member = member(principal);
      var rows = service.transaction(c -> CarlService.rows(c, "SELECT id,label FROM carl_member WHERE household_id=? AND active AND id<>? ORDER BY label,id LIMIT 16", Long.parseLong(member.household()), Long.parseLong(member.id())));
      if(rows.size() > 15)
      {
         throw new IllegalArgumentException("Choose an explicitly bounded family audience");
      }
      var result = new ArrayList<Choice>();
      rows.forEach(row -> result.add(new Choice(row.get("id").toString(), row.get("label").toString())));
      if(rows.size() > 1)
      {
         result.add(new Choice(String.join(",", rows.stream().map(row -> row.get("id").toString()).toList()), "Everyone listed: " + String.join(", ", rows.stream().map(row -> row.get("label").toString()).toList())));
      }
      requireUnchanged(member, principal);
      return List.copyOf(result);
   }



   /** UI selections fix the audience server-side, including the verified requester. */
   public Set<String> participants(String principal, String selected)
   {
      if(participantChoices(principal).stream().noneMatch(choice -> choice.id().equals(selected)))
      {
         throw new IllegalArgumentException("Select current family members to share with");
      }
      var result = new java.util.LinkedHashSet<String>(List.of(selected.split(",")));
      result.add(member(principal).id());
      return Set.copyOf(result);
   }



   private void requireUnchanged(FamilyAccess.Member initial, String principal)
   {
      if(!initial.equals(member(principal)))
      {
         throw new SecurityException("Carl conversation access changed during selection");
      }
   }



   private static String preview(Object value)
   {
      String text = value == null ? "Message" : value.toString().replaceAll("\\s+", " ").strip();
      return text.substring(0, Math.min(80, text.length()));
   }



   private static UUID[] ids(String selected)
   {
      if(selected == null || !selected.matches("[0-9a-f-]{36}/[0-9a-f-]{36}"))
      {
         throw new IllegalArgumentException("Select an existing conversation message");
      }
      String[] parts = selected.split("/");
      UUID conversation = UUID.fromString(parts[0]);
      UUID request = UUID.fromString(parts[1]);
      if(!selected.equals(conversation + "/" + request))
      {
         throw new IllegalArgumentException("Canonical message identifiers required");
      }
      return new UUID[]{conversation, request};
   }
}
