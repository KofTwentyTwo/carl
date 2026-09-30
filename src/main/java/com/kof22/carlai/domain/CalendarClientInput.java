/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;


/** Exact explicit family calendar intents; provider addresses and actor identities are never arguments. */
final class CalendarClientInput
{
   private CalendarClientInput()
   {
   }



   static void validate(String kind, JsonNode input)
   {
      var keys = new java.util.HashSet<String>();
      input.fieldNames().forEachRemaining(keys::add);
      Set<String> expected = kind.equals("calendar-plan") ? Set.of("plan", "step", "expectedVersion", "collection", "operation", "priorRequest") : Set.of("plan", "observation", "expectedVersion", "decision", "note");
      if(!keys.equals(expected))
      {
         throw new IllegalArgumentException("Exact calendar intent fields required");
      }
      for(String key : Set.of("plan", "expectedVersion", "observation"))
      {
         if(input.has(key) && (!input.get(key).isIntegralNumber() || !input.get(key).canConvertToLong() || input.get(key).longValue() < 1 || (key.equals("expectedVersion") && !input.get(key).canConvertToInt())))
         {
            throw new IllegalArgumentException("Positive identifiers and bounded version required");
         }
      }
      if(kind.equals("calendar-plan"))
      {
         for(String key : Set.of("step", "priorRequest"))
         {
            var value = input.get(key);
            if(key.equals("priorRequest") && value.isNull())
            {
               continue;
            }
            if(!value.isTextual() || !UUID.fromString(value.asText()).toString().equals(value.asText()))
            {
               throw new IllegalArgumentException("Canonical operation/task UUID required");
            }
         }
         if(!input.get("collection").isTextual() || !Set.of("events", "reminders").contains(input.get("collection").asText()) || !input.get("operation").isTextual() || !Set.of("PUBLISH", "RETIRE", "RECONCILE", "SYNCHRONIZE", "STATUS").contains(input.get("operation").asText()) || input.get("operation").asText().equals("RECONCILE") == input.get("priorRequest").isNull())
         {
            throw new IllegalArgumentException("Fixed collection and exact operation required");
         }
      }
      else
      {
         if(!input.get("decision").isTextual() || !Set.of("ACCEPT_REPORTED_COMPLETE", "DISMISS").contains(input.get("decision").asText()) || !input.get("note").isTextual())
         {
            throw new IllegalArgumentException("Explicit review decision required");
         }
         CarlService.bounded(input.get("note").asText(), 3000, "Review note");
      }
   }
}
