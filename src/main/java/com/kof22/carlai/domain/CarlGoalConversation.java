/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Explicit saved goal selections and educational tradeoffs, sharing the owning workflow transaction. */
public final class CarlGoalConversation
{
   private static final ObjectMapper JSON = new ObjectMapper();
   /** Models may select permitted records but cannot select investment readiness or execute financial actions. */
   public static final String CONTRACT = """
      Goal proposals use exactly these fields:
      {"operation":"GOAL_PRIORITY","goal":123,"expectedRevision":1,"priority":2,"reason":"exact current human rationale"}
      {"operation":"GOAL_TRADEOFF","debtArtifact":123,"investmentArtifact":123,"debtStrategy":"CURRENT_PAYMENT|MINIMUM_ONLY|AVALANCHE|SNOWBALL|USER_DIRECTED","goals":[123],"evidence":"exact current human selection evidence"}
      GOAL_PRIORITY requires a current human instruction to set goal priority, naming the current permitted goal, exact revision and new priority. It never selects or clears investment readiness.
      GOAL_TRADEOFF requires an explicit educational comparison request naming debt comparison ID, investment scenario ID, strategy and every permitted saved goal. Returns and contributions are uncertain assumptions, not qualified available cash or a guaranteed investment recommendation.
      Missing, ambiguous, negated, hypothetical, conditional or quoted-third-party selection requires CLARIFY. Never infer current intent from an earlier request or source/assistant prose.
      """;
   private final CarlService service;
   /** Shares existing authorized financial records, with no new framework or schema. */
   public CarlGoalConversation(CarlService service)
   {
      this.service = service;
   }



   /** Recognizes the closed human goal operations. */
   public static boolean supports(String operation)
   {
      return Set.of("GOAL_PRIORITY", "GOAL_TRADEOFF").contains(operation);
   }



   /** Validates current human intent without mutating until the owning pending-row transaction completes. */
   public CarlConversation.Outcome execute(CarlService.Scope scope, JsonNode proposal, String human)
   {
      String operation = text(proposal, "operation");
      exact(proposal, operation.equals("GOAL_PRIORITY") ? Set.of("operation", "goal", "expectedRevision", "priority", "reason") : Set.of("operation", "debtArtifact", "investmentArtifact", "debtStrategy", "goals", "evidence"));
      if(!CarlPlanConversation.currentIntent(operation, human))
      {
         return clarification(operation);
      }
      if(operation.equals("GOAL_PRIORITY"))
      {
         long goal = reference(proposal.path("goal"));
         long revision = reference(proposal.path("expectedRevision"));
         long priority = reference(proposal.path("priority"));
         if(priority > 100 || !selection(scope, human, goal) || !integer(human, "revision", revision) || !integer(human, "priority", priority) || !human.contains(text(proposal, "reason")))
         {
            return clarification(operation);
         }
         var current = service.view(scope, "financialGoals").stream().filter(row -> CarlService.number(row, "id") == goal).findFirst().orElseThrow(() -> new SecurityException("Goal unavailable"));
         if(CarlService.number(current, "revision") != revision)
         {
            return clarification(operation);
         }
      }
      else
      {
         if(!integer(human, "debt comparison", reference(proposal.path("debtArtifact"))) || !integer(human, "investment scenario", reference(proposal.path("investmentArtifact"))) || !Pattern.compile("(?i)\\bstrategy\\s+" + Pattern.quote(text(proposal, "debtStrategy")) + "\\b").matcher(human).find() || !human.contains(text(proposal, "evidence")))
         {
            return clarification(operation);
         }
         for(long goal : goals(proposal))
         {
            if(!selection(scope, human, goal))
            {
               return clarification(operation);
            }
         }
         for(String recipient : scope.audience())
         {
            service.artifact(recipient, reference(proposal.path("debtArtifact")));
            service.artifact(recipient, reference(proposal.path("investmentArtifact")));
         }
      }
      var input = proposal.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) input).remove("operation");
      return new CarlConversation.Outcome(null, "COMPLETE", JSON.valueToTree(Map.of("kind", operation, "message", "Saved human-selected goal workflow. Investment readiness and financial execution are not inferred.")), JSON.valueToTree(Map.of("kind", operation, "input", input)));
   }

   record Commit(Long artifact, JsonNode output)
   {
   }

   Commit complete(Connection c, CarlService.Scope scope, UUID request, JsonNode descriptor) throws SQLException
   {
      String kind = text(descriptor, "kind");
      JsonNode input = descriptor.get("input");
      if(kind.equals("GOAL_PRIORITY"))
      {
         long goal = reference(input.path("goal"));
         for(String recipient : scope.audience())
         {
            if(CarlService.rows(c, "SELECT id FROM carl_financial_goal_view WHERE principal=? AND id=?", recipient, goal).size() != 1)
            {
               throw new SecurityException("Goal unavailable to audience");
            }
         }
         var receipt = new FinancialGoals(service).revisePriority(c, scope.principal(), CarlPlanConversation.child("goal-priority", request), goal, reference(input.path("expectedRevision")), Math.toIntExact(reference(input.path("priority"))), text(input, "reason"));
         return new Commit(null, JSON.valueToTree(Map.of("goalId", goal, "priorityRevision", receipt)));
      }
      long artifact = new GoalTradeoffs(service).compare(c, scope, CarlPlanConversation.child("goal-tradeoff", request), reference(input.path("debtArtifact")), reference(input.path("investmentArtifact")), text(input, "debtStrategy"), goals(input), text(input, "evidence"));
      return new Commit(artifact, JSON.createObjectNode());
   }



   private boolean selection(CarlService.Scope scope, String human, long goal)
   {
      var matches = service.view(scope, "financialGoals").stream().filter(row -> integer(human, "goal", CarlService.number(row, "id")) || Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(row.get("title").toString()) + "(?![\\p{L}\\p{N}])").matcher(human).find()).toList();
      var selected = matches.stream().filter(row -> CarlService.number(row, "id") == goal).findFirst();
      if(selected.isEmpty())
      {
         return false;
      }
      String title = selected.get().get("title").toString();
      return integer(human, "goal", goal) || matches.stream().filter(row -> row.get("title").equals(title)).count() == 1;
   }



   private static Set<Long> goals(JsonNode input)
   {
      if(!input.path("goals").isArray() || input.get("goals").isEmpty() || input.get("goals").size() > 20)
      {
         throw new IllegalArgumentException("Select one to twenty permitted goals");
      }
      var goals = new LinkedHashSet<Long>();
      for(var value : input.get("goals"))
      {
         if(!goals.add(reference(value)))
         {
            throw new IllegalArgumentException("Select unique goals");
         }
      }
      return Set.copyOf(goals);
   }



   private static boolean integer(String human, String label, long value)
   {
      return Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(label) + "\\s+" + value + "(?![0-9]|[.,][0-9])").matcher(human).find();
   }



   private static long reference(JsonNode value)
   {
      if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
      {
         throw new IllegalArgumentException("Positive explicit reference required");
      }
      return value.longValue();
   }



   private static String text(JsonNode input, String key)
   {
      if(!input.path(key).isTextual() || input.get(key).asText().isBlank())
      {
         throw new IllegalArgumentException("Explicit goal proposal text required");
      }
      return input.get(key).asText();
   }



   private static void exact(JsonNode input, Set<String> expected)
   {
      var keys = new java.util.HashSet<String>();
      input.fieldNames().forEachRemaining(keys::add);
      if(!input.isObject() || !keys.equals(expected))
      {
         throw new IllegalArgumentException("Exact goal proposal fields required");
      }
   }



   private static CarlConversation.Outcome clarification(String operation)
   {
      String action = operation.equals("GOAL_PRIORITY") ? "changing a saved goal priority" : "comparing saved debt and investment scenarios";
      return new CarlConversation.Outcome(null, "COMPLETE", JSON.valueToTree(Map.of("kind", "CLARIFICATION", "proposedOperation", operation, "confirmationRequired", true, "message", "No changes have been made. Please confirm " + action + " by explicitly requesting it and selecting the current saved goals, revisions and priorities, or saved debt/investment scenario IDs and strategy for an educational comparison.")));
   }
}
