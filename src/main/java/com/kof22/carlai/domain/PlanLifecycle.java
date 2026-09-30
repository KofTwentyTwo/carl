/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Human-agreed plans with optimistic revisions and explicit reported-versus-verified execution. */
public final class PlanLifecycle
{
   private final CarlService service;

   /** Shares source authorization and persistent records with reports and administration. */
   public PlanLifecycle(CarlService service)
   {
      this.service = service;
   }



   /** Creates a plan from a currently accessible saved financial comparison, retaining its audience. */
   public long create(String principal, UUID requestId, long sourceArtifact, String title, String reason)
   {
      return service.transaction(c -> create(c, principal, requestId, sourceArtifact, title, reason));
   }



   long create(Connection c, String principal, UUID requestId, long sourceArtifact, String title, String reason) throws SQLException
   {
      CarlService.bounded(title, 500, "plan title");
      CarlService.bounded(reason, 2000, "plan reason");

      var member = CarlService.manager(c, principal, "FINANCE");
      var artifact = CarlService.rows(c, "SELECT * FROM carl_artifact_view WHERE principal=? AND id=? AND kind='FINANCIAL_PLAN'", principal, sourceArtifact);
      if(artifact.size() != 1)
      {
         throw new SecurityException("Financial comparison unavailable");
      }
      if(Boolean.TRUE.equals(artifact.getFirst().get("stale")))
      {
         throw new IllegalArgumentException("Regenerate stale inputs before proposing a plan");
      }
      String digest = BillCsv.hash(sourceArtifact + ":" + title + ":" + reason);
      Long prior = CarlService.request(c, member, requestId, "CREATE_PLAN", digest);
      if(prior != null)
      {
         require(c, principal, prior);
         return prior;
      }
      long id = CarlService.record(c, member, "FINANCE", "PRIVATE", title, "Human proposed plan from saved comparison " + sourceArtifact);
      CarlService.execute(c, "INSERT INTO carl_plan(record_id,source_artifact_id,state,created_by) VALUES(?,?,'DRAFT',?)", id, sourceArtifact, member.id());
      for(var audience : CarlService.rows(c, "SELECT member_id FROM carl_artifact_audience WHERE artifact_id=?", sourceArtifact))
      {
         long recipient = CarlService.number(audience, "member_id");
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,?,true)", id, recipient);
      }
      snapshot(c, id, 1, member.id(), reason);
      CarlService.complete(c, requestId, id, "COMPLETE", "Draft plan created; no external execution");
      return id;
   }



   /** Adds or revises a task only under the expected plan revision and an authorized active assignee. */
   public int step(String principal, long plan, int expectedVersion, UUID step, String title, long assignee, LocalDate due, String location, UUID dependency, String reason)
   {
      return service.transaction(c -> step(c, principal, plan, expectedVersion, step, title, assignee, due, location, dependency, reason));
   }



   int step(Connection c, String principal, long plan, int expectedVersion, UUID step, String title, long assignee, LocalDate due, String location, UUID dependency, String reason) throws SQLException
   {
      CarlService.bounded(title, 500, "task title");
      CarlService.bounded(location, 2000, "task location");
      CarlService.bounded(reason, 2000, "revision reason");
      if(step == null || due == null || due.getYear() < 1900 || due.getYear() > 2200 || step.equals(dependency))
      {
         throw new IllegalArgumentException("A stable task ID, valid due date and distinct dependency are required");
      }

      var actor = CarlService.manager(c, principal, "FINANCE");
      require(c, principal, plan);
      if(CarlService.rows(c, "SELECT p.id FROM carl_plan_view p JOIN carl_member m ON m.principal=p.principal WHERE p.id=? AND m.id=? AND m.active", plan, assignee).size() != 1)
      {
         throw new SecurityException("Assignee must be in this plan's authorized audience");
      }
      var prior = CarlService.rows(c, "SELECT plan_id FROM carl_plan_step WHERE id=?", step);
      if(!prior.isEmpty() && CarlService.number(prior.getFirst(), "plan_id") != plan)
      {
         throw new SecurityException("Task unavailable");
      }
      if(dependency != null)
      {
         if(CarlService.rows(c, "SELECT id FROM carl_plan_step WHERE id=? AND plan_id=?", dependency, plan).size() != 1)
         {
            throw new IllegalArgumentException("Dependency must belong to this plan");
         }
         if(!CarlService.rows(c, "WITH RECURSIVE chain AS (SELECT id,dependency_id FROM carl_plan_step WHERE id=? UNION SELECT s.id,s.dependency_id FROM carl_plan_step s JOIN chain ON s.id=chain.dependency_id) SELECT id FROM chain WHERE id=?", dependency, step).isEmpty())
         {
            throw new IllegalArgumentException("Cyclic task dependency");
         }
      }
      int version = advance(c, plan, expectedVersion);
      if(prior.isEmpty() && CarlService.rows(c, "SELECT id FROM carl_plan_step WHERE plan_id=? LIMIT 100", plan).size() >= 100)
      {
         throw new IllegalArgumentException("A plan supports at most 100 current tasks; split this work into another plan");
      }
      CarlService.execute(c, "INSERT INTO carl_plan_step(id,plan_id,title,assignee_id,due_date,location,dependency_id,status) VALUES(?,?,?,?,?,?,?,'TODO') ON CONFLICT(id) DO UPDATE SET title=EXCLUDED.title,assignee_id=EXCLUDED.assignee_id,due_date=EXCLUDED.due_date,location=EXCLUDED.location,dependency_id=EXCLUDED.dependency_id,status='TODO',checkin='',evidence_record_id=NULL,updated_at=now()", step, plan, title, assignee, due, location, dependency);
      CarlService.execute(c, "UPDATE carl_plan SET state='DRAFT' WHERE record_id=?", plan);
      snapshot(c, plan, version, actor.id(), reason);
      return version;
   }



   /** Replaces stale assumptions with a current comparison, preserving history and requiring renewed agreement. */
   public int rebase(String principal, long plan, int expectedVersion, long sourceArtifact, String reason)
   {
      return service.transaction(c -> rebase(c, principal, plan, expectedVersion, sourceArtifact, reason));
   }



   int rebase(Connection c, String principal, long plan, int expectedVersion, long sourceArtifact, String reason) throws SQLException
   {
      CarlService.bounded(reason, 2000, "replanning reason");
      var actor = CarlService.manager(c, principal, "FINANCE");
      CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", actor.householdId());
      var current = require(c, principal, plan);
      var oldAudience = CarlService.rows(c, "SELECT member_id FROM carl_artifact_audience WHERE artifact_id=? ORDER BY member_id", CarlService.number(current, "source_artifact_id"));
      var newAudience = CarlService.rows(c, "SELECT member_id FROM carl_artifact_audience WHERE artifact_id=? ORDER BY member_id", sourceArtifact);
      if(!oldAudience.equals(newAudience))
      {
         throw new SecurityException("Replanning requires the same explicit audience");
      }
      for(var recipient : newAudience)
      {
         var usable = CarlService.rows(c, "SELECT a.id FROM carl_artifact_view a JOIN carl_member m ON m.principal=a.principal WHERE m.id=? AND a.id=? AND a.kind='FINANCIAL_PLAN' AND NOT a.stale", recipient.get("member_id"), sourceArtifact);
         if(usable.size() != 1)
         {
            throw new SecurityException("Every recipient requires current comparison access");
         }
      }
      int version = advance(c, plan, expectedVersion);
      CarlService.execute(c, "UPDATE carl_plan SET source_artifact_id=?,state='DRAFT' WHERE record_id=?", sourceArtifact, plan);
      snapshot(c, plan, version, actor.id(), "Replanned assumptions; review retained tasks and completion evidence: " + reason);
      return version;
   }



   /** Explicit human agreement selects the current revision without sending or paying anything. */
   public int agree(String principal, long plan, int expectedVersion, String reason)
   {
      return service.transaction(c -> agree(c, principal, plan, expectedVersion, reason));
   }



   int agree(Connection c, String principal, long plan, int expectedVersion, String reason) throws SQLException
   {
      CarlService.bounded(reason, 2000, "agreement evidence");

      var actor = CarlService.manager(c, principal, "FINANCE");
      var current = require(c, principal, plan);
      var source = CarlService.rows(c, "SELECT status_label FROM carl_artifact_view WHERE principal=? AND id=?", principal, CarlService.number(current, "source_artifact_id")).getFirst();
      if(source.get("status_label").toString().startsWith("Incomplete"))
      {
         throw new IllegalArgumentException("Resolve incomplete source assumptions before agreeing to a plan");
      }
      if(Boolean.TRUE.equals(current.get("source_stale")))
      {
         throw new IllegalArgumentException("Review and regenerate stale financial assumptions before agreement");
      }
      if(CarlService.rows(c, "SELECT id FROM carl_plan_step WHERE plan_id=? LIMIT 1", plan).isEmpty())
      {
         throw new IllegalArgumentException("Define assigned steps before agreement");
      }
      int version = advance(c, plan, expectedVersion);
      CarlService.execute(c, "UPDATE carl_plan SET state='AGREED' WHERE record_id=?", plan);
      snapshot(c, plan, version, actor.id(), reason);
      return version;
   }



   /** Stores a member check-in; verified completion requires accessible documentary evidence. */
   public int checkIn(String principal, long plan, int expectedVersion, UUID step, String status, String note, Long evidence)
   {
      return service.transaction(c -> checkIn(c, principal, plan, expectedVersion, step, status, note, evidence));
   }



   int checkIn(Connection c, String principal, long plan, int expectedVersion, UUID step, String status, String note, Long evidence) throws SQLException
   {
      CarlService.bounded(note, 4000, "check-in note");
      if(!Set.of("TODO", "REPORTED_COMPLETE", "VERIFIED_COMPLETE", "BLOCKED").contains(status))
      {
         throw new IllegalArgumentException("Unsupported task status");
      }

      var actor = CarlService.member(c, principal);
      require(c, principal, plan);
      var task = CarlService.rows(c, "SELECT * FROM carl_plan_step WHERE id=? AND plan_id=?", step, plan);
      if(task.size() != 1 || (!actor.manager() && CarlService.number(task.getFirst(), "assignee_id") != actor.id()))
      {
         throw new SecurityException("Task unavailable");
      }
      if(status.equals("VERIFIED_COMPLETE"))
      {
         if(!actor.manager() || evidence == null)
         {
            throw new IllegalArgumentException("An authorized human must select completion evidence");
         }
      }
      if(evidence != null)
      {
         for(var audience : CarlService.rows(c, "SELECT m.principal FROM carl_plan p JOIN carl_artifact_audience a ON a.artifact_id=p.source_artifact_id JOIN carl_member m ON m.id=a.member_id WHERE p.record_id=?", plan))
         {
            CarlService.requireEvidence(c, audience.get("principal").toString(), evidence);
         }
      }
      if(status.endsWith("COMPLETE") && task.getFirst().get("dependency_id") != null)
      {
         var blocked = CarlService.rows(c, "SELECT status FROM carl_plan_step WHERE id=?", UUID.fromString(task.getFirst().get("dependency_id").toString())).getFirst();
         if(!blocked.get("status").equals("VERIFIED_COMPLETE"))
         {
            throw new IllegalArgumentException("Dependent work requires verified completion of its prerequisite");
         }
      }
      int version = advance(c, plan, expectedVersion);
      CarlService.execute(c, "UPDATE carl_plan_step SET status=?,checkin=?,evidence_record_id=?,updated_at=now() WHERE id=?", status, note, evidence, step);
      snapshot(c, plan, version, actor.id(), "Human check-in: " + note);
      return version;
   }



   /** Rechecks current source and audience access for the plan and all retained versions. */
   public Map<String, Object> get(String principal, long plan)
   {
      return service.transaction(c ->
      {
         var current = require(c, principal, plan);
         return Map.of("plan", current, "steps", CarlService.rows(c, "SELECT * FROM carl_plan_step WHERE plan_id=? ORDER BY due_date,id LIMIT 101", plan), "history", CarlService.rows(c, "SELECT version,reason,snapshot,created_at FROM (SELECT version,reason,snapshot,created_at FROM carl_plan_version WHERE plan_id=? ORDER BY version DESC LIMIT 100) recent ORDER BY version", plan), "historyLimit", 100);
      });
   }



   /** Reads only the bounded current projection needed by export, never historical snapshots. */
   public Map<String, Object> exportSnapshot(String principal, long plan)
   {
      return service.transaction(c ->
      {
         var current = require(c, principal, plan);
         var steps = CarlService.rows(c, "SELECT s.*,coalesce(m.title,'Assignee unavailable - review assignment') AS assignee_label FROM carl_plan_step s LEFT JOIN carl_member_view m ON m.id=s.assignee_id AND m.principal=? WHERE s.plan_id=? ORDER BY s.due_date,s.id LIMIT 101", principal, plan);
         if(steps.size() > 100)
         {
            throw new IllegalArgumentException("Plan export exceeds the 100-task limit");
         }
         return Map.of("plan", current, "steps", steps);
      });
   }



   private static Map<String, Object> require(Connection c, String principal, long plan) throws SQLException
   {
      var rows = CarlService.rows(c, "SELECT * FROM carl_plan_view WHERE principal=? AND id=?", principal, plan);
      if(rows.size() != 1)
      {
         throw new SecurityException("Plan unavailable");
      }
      return rows.getFirst();
   }



   private static int advance(Connection c, long plan, int expected) throws SQLException
   {
      var rows = CarlService.rows(c, "UPDATE carl_plan SET version=version+1,updated_at=now() WHERE record_id=? AND version=? AND state<>'RETIRED' RETURNING version", plan, expected);
      if(rows.size() != 1)
      {
         throw new IllegalArgumentException("Plan changed; reload its current revision before editing");
      }
      CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", plan);
      return ((Number) rows.getFirst().get("version")).intValue();
   }



   private static void snapshot(Connection c, long plan, int version, long actor, String reason) throws SQLException
   {
      var state = CarlService.rows(c, "SELECT p.*,r.title FROM carl_plan p JOIN carl_record r ON r.id=p.record_id WHERE p.record_id=?", plan).getFirst();
      var steps = CarlService.rows(c, "SELECT * FROM carl_plan_step WHERE plan_id=? ORDER BY due_date,id", plan);
      CarlService.execute(c, "INSERT INTO carl_plan_version(plan_id,version,actor_id,reason,snapshot) VALUES(?,?,?,?,?)", plan, version, actor, reason, CarlService.json(Map.of("plan", state, "steps", steps)));
   }
}
