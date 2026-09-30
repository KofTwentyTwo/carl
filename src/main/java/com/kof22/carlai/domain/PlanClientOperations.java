/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.kof22.agentadmin.client.ClientWorkflow;


/** Explicit family intents share the same plan mutations as QQQ, in one durable transaction. */
final class PlanClientOperations
{
   private PlanClientOperations()
   {
   }



   static void validate(String kind, JsonNode input)
   {
      var keys = new LinkedHashSet<String>();
      input.fieldNames().forEachRemaining(keys::add);
      Set<String> expected = switch(kind)
      {
         case "plan-create" -> Set.of("sourceArtifact", "title", "reason");
         case "plan-step" -> Set.of("plan", "expectedVersion", "step", "title", "assignee", "due", "location", "dependency", "reason");
         case "plan-rebase" -> Set.of("plan", "expectedVersion", "sourceArtifact", "reason");
         case "plan-agree" -> Set.of("plan", "expectedVersion", "reason");
         case "plan-check-in" -> Set.of("plan", "expectedVersion", "step", "status", "note", "evidence");
         case "plan-export" -> Set.of("plan");
         default -> throw new IllegalArgumentException("Unsupported plan intent");
      };
      if(!keys.equals(expected))
      {
         throw new IllegalArgumentException("Exact plan fields required");
      }
      for(String name : Set.of("plan", "sourceArtifact", "assignee", "expectedVersion", "evidence"))
      {
         if(input.has(name) && !input.get(name).isNull())
         {
            var value = input.get(name);
            if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0 || (name.equals("expectedVersion") && !value.canConvertToInt()))
            {
               throw new IllegalArgumentException("Positive identifiers and versions required");
            }
         }
         else if(input.has(name) && !name.equals("evidence"))
         {
            throw new IllegalArgumentException("Identifier required");
         }
      }
      for(String name : Set.of("title", "reason", "location", "note"))
      {
         if(input.has(name))
         {
            if(!input.get(name).isTextual())
            {
               throw new IllegalArgumentException("Text required");
            }
            CarlService.bounded(input.get(name).asText(), name.equals("title") ? 500 : name.equals("note") ? 4000 : 2000, name);
         }
      }
      for(String name : Set.of("step", "dependency"))
      {
         if(input.has(name) && !input.get(name).isNull())
         {
            if(!input.get(name).isTextual() || !UUID.fromString(input.get(name).asText()).toString().equals(input.get(name).asText()))
            {
               throw new IllegalArgumentException("Canonical task UUID required");
            }
         }
         else if(name.equals("step") && input.has(name))
         {
            throw new IllegalArgumentException("Task required");
         }
      }
      if(input.has("due"))
      {
         if(!input.get("due").isTextual())
         {
            throw new IllegalArgumentException("Date required");
         }
         var date = LocalDate.parse(input.get("due").asText());
         if(date.getYear() < 1900 || date.getYear() > 2200)
         {
            throw new IllegalArgumentException("Bounded due date required");
         }
      }
      if(input.has("status") && (!input.get("status").isTextual() || !Set.of("TODO", "REPORTED_COMPLETE", "VERIFIED_COMPLETE", "BLOCKED").contains(input.get("status").asText())))
      {
         throw new IllegalArgumentException("Explicit task state required");
      }
   }



   static long mutate(Connection c, CarlService service, ClientWorkflow.Context context, CarlService.Scope scope, UUID request, String kind, JsonNode input) throws SQLException
   {
      var plans = new PlanLifecycle(service);
      long plan;
      if(kind.equals("plan-create"))
      {
         artifactAudience(c, context, scope, input.get("sourceArtifact").longValue());
         plan = plans.create(c, scope.principal(), request, input.get("sourceArtifact").longValue(), input.get("title").asText(), input.get("reason").asText());
      }
      else
      {
         plan = input.get("plan").longValue();
         authorize(c, context, scope, plan);
         switch(kind)
         {
            case "plan-step" -> plans.step(c, scope.principal(), plan, input.get("expectedVersion").intValue(), UUID.fromString(input.get("step").asText()), input.get("title").asText(), input.get("assignee").longValue(), LocalDate.parse(input.get("due").asText()), input.get("location").asText(), input.get("dependency").isNull() ? null : UUID.fromString(input.get("dependency").asText()), input.get("reason").asText());
            case "plan-rebase" -> plans.rebase(c, scope.principal(), plan, input.get("expectedVersion").intValue(), input.get("sourceArtifact").longValue(), input.get("reason").asText());
            case "plan-agree" -> plans.agree(c, scope.principal(), plan, input.get("expectedVersion").intValue(), input.get("reason").asText());
            case "plan-check-in" -> plans.checkIn(c, scope.principal(), plan, input.get("expectedVersion").intValue(), UUID.fromString(input.get("step").asText()), input.get("status").asText(), input.get("note").asText(), input.get("evidence").isNull() ? null : input.get("evidence").longValue());
            default -> throw new IllegalArgumentException("Unsupported plan mutation");
         }
      }
      authorize(c, context, scope, plan);
      return plan;
   }



   static Map<String, Object> snapshot(Connection c, ClientWorkflow.Context context, CarlService.Scope scope, long plan) throws SQLException
   {
      var current = authorize(c, context, scope, plan);
      return Map.of("plan", current, "steps", CarlService.rows(c, "SELECT id,title,assignee_id,due_date,location,dependency_id,status,checkin,evidence_record_id FROM carl_plan_step WHERE plan_id=? ORDER BY due_date,id LIMIT 101", plan), "boundary", "Human execution only; reported completion is not independent verification.");
   }



   static Map<String, Object> authorize(Connection c, ClientWorkflow.Context context, CarlService.Scope scope, long plan) throws SQLException
   {
      Map<String, Object> current = null;
      for(String principal : scope.audience())
      {
         var rows = CarlService.rows(c, "SELECT * FROM carl_plan_view WHERE principal=? AND id=?", principal, plan);
         if(rows.size() != 1)
         {
            throw new SecurityException("Plan unavailable");
         }
         if(principal.equals(scope.principal()))
         {
            current = rows.getFirst();
         }
      }
      if(current == null)
      {
         throw new SecurityException("Plan unavailable");
      }
      artifactAudience(c, context, scope, CarlService.number(current, "source_artifact_id"));
      current.remove("principal");
      return current;
   }



   private static void artifactAudience(Connection c, ClientWorkflow.Context context, CarlService.Scope scope, long artifact) throws SQLException
   {
      var audience = new LinkedHashSet<String>();
      for(var row : CarlService.rows(c, "SELECT member_id FROM carl_artifact_audience WHERE artifact_id=?", artifact))
      {
         audience.add(row.get("member_id").toString());
      }
      if(!audience.equals(context.participants()))
      {
         throw new SecurityException("Use the conversation matching the plan audience");
      }
      for(String principal : scope.audience())
      {
         if(CarlService.rows(c, "SELECT id FROM carl_artifact_view WHERE id=? AND principal=? AND kind='FINANCIAL_PLAN'", artifact, principal).size() != 1)
         {
            throw new SecurityException("Source comparison unavailable");
         }
      }
   }
}
