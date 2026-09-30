/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Explicit human reconciliation fences late workers; it never replays model work. */
public final class ReportRecovery
{
   /** Protected operational state contains no source documents or model prompt. */
   public record Status(UUID request, String kind, String state, Long artifact, String instruction)
   {
   }
   private final CarlService service;
   /** Uses the application's authoritative database and current access rules. */
   public ReportRecovery(CarlService service)
   {
      this.service = service;
   }



   /** Inspects only the original actor's exact, currently authorized audience. */
   public Status inspect(CarlService.Scope scope, UUID request)
   {
      return bounded(c -> status(c, scope, request, claim(c, scope, request)));
   }



   /** Native callers use the stored audience, never a form-supplied identity list. */
   public CarlService.Scope storedScope(String principal, UUID request)
   {
      return bounded(c ->
      {
         var actor = CarlService.member(c, principal);
         var rows = CarlService.rows(c, "SELECT member_id FROM carl_request WHERE id=?", request);
         if(rows.size() != 1 || CarlService.number(rows.getFirst(), "member_id") != actor.id())
         {
            throw denied();
         }
         var members = CarlService.rows(c, "SELECT m.principal FROM carl_report_claim_audience a JOIN carl_member m ON m.id=a.member_id WHERE a.request_id=? ORDER BY m.id", request);
         if(members.isEmpty() || members.size() > 20)
         {
            throw denied();
         }
         var audience = new HashSet<String>();
         members.forEach(row -> audience.add(row.get("principal").toString()));
         return new CarlService.Scope(principal, Set.copyOf(audience));
      });
   }



   /** Reconciles one explicit human intent without replaying provider work. */
   public Status reconcile(CarlService.Scope scope, UUID operation, UUID request, String evidence)
   {
      CarlService.bounded(evidence, 4000, "reconciliation evidence");
      return bounded(c ->
      {
         var row = claim(c, scope, request);
         var actor = CarlService.member(c, scope.principal());
         var prior = CarlService.rows(c, "SELECT * FROM carl_report_recovery WHERE operation_id=?", operation);
         if(!prior.isEmpty())
         {
            var saved = prior.getFirst();
            if(!saved.get("request_id").equals(request.toString()) || CarlService.number(saved, "actor_id") != actor.id() || !saved.get("evidence").equals(evidence))
            {
               throw new IllegalArgumentException("Recovery request conflicts with prior input");
            }
            return status(c, scope, request, row);
         }
         var artifacts = CarlService.rows(c, "SELECT record_id,narration_state,status_label FROM carl_artifact WHERE request_id=?", request);
         Long artifact = null;
         String state;
         if(artifacts.size() == 1)
         {
            var saved = artifacts.getFirst();
            artifact = CarlService.number(saved, "record_id");
            requireArtifact(c, scope, artifact);
            state = "FAILED".equals(saved.get("narration_state")) || saved.get("status_label").toString().startsWith("Incomplete") ? "PARTIAL" : "COMPLETE";
            CarlService.complete(c, request, artifact, state, "Recovered committed protected artifact; no narration was replayed");
         }
         else
         {
            state = Set.of("COMPLETE", "PARTIAL").contains(row.get("status")) ? "UNKNOWN" : "FAILED";
            CarlService.execute(c, "UPDATE carl_request SET status=?,result_id=NULL,finished_at=now(),detail=? WHERE id=?", state,
               state.equals("UNKNOWN") ? "Recorded completion has no provable artifact; operator investigation required" : "Human terminated pending or unknown generation; late persistence is fenced. Start a new request ID to regenerate.", request);
         }
         CarlService.execute(c, "INSERT INTO carl_report_recovery(operation_id,request_id,actor_id,evidence,outcome,artifact_id) VALUES(?,?,?,?,?,?)", operation, request, actor.id(), evidence, state, artifact);
         return new Status(request, row.get("kind").toString(), state, artifact, instruction(state));
      });
   }



   private <T> T bounded(CarlService.Transaction<T> operation)
   {
      Runnable restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         return service.transaction(operation);
      }
      finally
      {
         restore.run();
      }
   }



   private static Map<String, Object> claim(Connection c, CarlService.Scope scope, UUID request) throws SQLException
   {
      var actor = CarlService.member(c, scope.principal());
      long epoch = CarlService.number(CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", actor.householdId()).getFirst(), "permission_revision");
      var rows = CarlService.rows(c, "SELECT r.*,s.household_id,s.permission_revision FROM carl_request r JOIN carl_report_claim s ON s.request_id=r.id WHERE r.id=? FOR UPDATE OF r", request);
      if(rows.size() != 1)
      {
         throw denied();
      }
      var row = rows.getFirst();
      if(CarlService.number(row, "member_id") != actor.id() || CarlService.number(row, "household_id") != actor.householdId() || CarlService.number(row, "permission_revision") != epoch)
      {
         throw denied();
      }
      var expected = new HashSet<Long>();
      for(String principal : scope.audience())
      {
         var member = CarlService.member(c, principal);
         if(member.householdId() != actor.householdId())
         {
            throw denied();
         }
         expected.add(member.id());
      }
      var actual = new HashSet<Long>();
      CarlService.rows(c, "SELECT member_id FROM carl_report_claim_audience WHERE request_id=?", request).forEach(member -> actual.add(CarlService.number(member, "member_id")));
      if(!actual.equals(expected))
      {
         throw denied();
      }
      return row;
   }



   private static Status status(Connection c, CarlService.Scope scope, UUID request, Map<String, Object> row) throws SQLException
   {
      Long artifact = row.get("result_id") == null ? null : CarlService.number(row, "result_id");
      if(artifact != null)
      {
         requireArtifact(c, scope, artifact);
      }
      String state = row.get("status").toString();
      return new Status(request, row.get("kind").toString(), state, artifact, instruction(state));
   }



   private static void requireArtifact(Connection c, CarlService.Scope scope, long artifact) throws SQLException
   {
      for(String principal : scope.audience())
      {
         if(CarlService.rows(c, "SELECT id FROM carl_artifact_view WHERE principal=? AND id=?", principal, artifact).size() != 1)
         {
            throw denied();
         }
      }
   }



   private static String instruction(String state)
   {
      return switch(state)
      {
         case "COMPLETE", "PARTIAL" -> "Retrieve the saved result through the authenticated artifact view; partial results retain their limitations.";
         case "FAILED" -> "Generation terminated. A new explicit generation request with a new ID is required; no provider work was replayed.";
         default -> "Worker activity or outcome is not proven. Explicit reconciliation may terminate this attempt; it never repeats narration.";
      };
   }



   private static SecurityException denied()
   {
      return new SecurityException("Report request or current audience unavailable");
   }
}
