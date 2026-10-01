/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kof22.carlai.calendar.CalendarPublicationService;
import com.kof22.carlai.calendar.ReminderStatus;


/** Durable remote-status review separate from verified human financial evidence. */
public final class ReminderObservations
{
   private final CarlService service;
   /** Shares plan permissions, current source restrictions and human check-in rules. */
   public ReminderObservations(CarlService service)
   {
      this.service = service;
   }



   /** Retains only a newly synchronized, application-owned mapping's typed status. */
   public long capture(String principal, long plan, UUID step, int version, CalendarPublicationService.Observation remote)
   {
      if(remote.calendar() == null || remote.etag() == null)
      {
         throw new IllegalArgumentException("A successful remote observation is required");
      }
      var decoded = ReminderStatus.decode(step, remote.calendar());
      String hash = BillCsv.hash(remote.calendar());
      return service.transaction(c ->
      {
         var actor = authorize(c, principal, plan);
         var mapping = CarlService.rows(c, "SELECT * FROM carl_calendar_mapping WHERE step_id=? AND plan_id=? AND component='VTODO' FOR UPDATE", step, plan);
         if(mapping.size() != 1 || !remote.calendar().equals(mapping.getFirst().get("observed_calendar")) || !Set.of("CHANGED", "UNCHANGED").contains(mapping.getFirst().get("read_state")))
         {
            throw new SecurityException("Reminder observation no longer matches the synchronized mapping");
         }
         if(CarlService.number(CarlService.rows(c, "SELECT version FROM carl_plan WHERE record_id=?", plan).getFirst(), "version") != version)
         {
            throw new IllegalArgumentException("Plan changed while synchronizing the reminder");
         }
         var existing = CarlService.rows(c, "SELECT id FROM carl_reminder_observation WHERE step_id=? AND remote_hash=? AND plan_version=?", step, hash, version);
         if(!existing.isEmpty())
         {
            return CarlService.number(existing.getFirst(), "id");
         }
         return CarlService.number(CarlService.rows(c, "INSERT INTO carl_reminder_observation(plan_id,step_id,plan_version,remote_hash,remote_etag,remote_state,reported_completed,observed_by,limitation) VALUES(?,?,?,?,?,?,?,?,?) RETURNING id", plan, step, version, hash, remote.etag(), decoded.state(), decoded.reportedCompleted() == null ? null : java.sql.Timestamp.from(decoded.reportedCompleted()), actor.id(), decoded.limitation()).getFirst(), "id");
      });
   }



   /** An explicit human review may record reported completion; it can never verify financial execution. */
   public int review(String principal, UUID request, long observation, int expectedVersion, String decision, String note)
   {
      return service.transaction(c -> review(c, principal, request, observation, expectedVersion, decision, note));
   }



   int review(Connection c, String principal, UUID request, long observation, int expectedVersion, String decision, String note) throws SQLException
   {
      if(!Set.of("ACCEPT_REPORTED_COMPLETE", "DISMISS").contains(decision))
      {
         throw new IllegalArgumentException("Choose reported completion or dismiss the observation");
      }
      CarlService.bounded(note, 3000, "Reminder review note");
      String digest = BillCsv.hash(CarlService.json(Map.of("observation", observation, "version", expectedVersion, "decision", decision, "note", note)));
      var rows = CarlService.rows(c, "SELECT * FROM carl_reminder_observation_view WHERE principal=? AND id=?", principal, observation);
      if(rows.size() != 1)
      {
         throw new SecurityException("Reminder observation unavailable");
      }
      long plan = CarlService.number(rows.getFirst(), "plan_id");
      var actor = authorize(c, principal, plan);
      var row = CarlService.rows(c, "SELECT * FROM carl_reminder_observation WHERE id=? FOR UPDATE", observation).getFirst();
      if(CarlService.request(c, actor, request, "REMINDER_REVIEW", digest) != null)
      {
         return ((Number) row.get("reviewed_version")).intValue();
      }
      if(!row.get("review_state").equals("PENDING") || CarlService.number(row, "plan_version") != expectedVersion || CarlService.number(CarlService.rows(c, "SELECT version FROM carl_plan WHERE record_id=?", plan).getFirst(), "version") != expectedVersion)
      {
         throw new IllegalArgumentException("Review the current plan and synchronize again before accepting this observation");
      }
      UUID step = UUID.fromString(row.get("step_id").toString());
      var mapping = CarlService.rows(c, "SELECT observed_calendar,read_state FROM carl_calendar_mapping WHERE step_id=? FOR SHARE", step).getFirst();
      if(mapping.get("observed_calendar") == null || !BillCsv.hash(mapping.get("observed_calendar").toString()).equals(row.get("remote_hash")) || !Set.of("CHANGED", "UNCHANGED").contains(mapping.get("read_state")))
      {
         throw new IllegalArgumentException("Remote reminder changed or is unavailable; synchronize and review again");
      }
      int version = expectedVersion;
      if(decision.equals("ACCEPT_REPORTED_COMPLETE"))
      {
         if(!row.get("remote_state").equals("REMOTE_REPORTED_COMPLETE"))
         {
            throw new IllegalArgumentException("The reminder did not report completion");
         }
         var task = CarlService.rows(c, "SELECT status FROM carl_plan_step WHERE id=?", step).getFirst();
         if(task.get("status").equals("VERIFIED_COMPLETE"))
         {
            throw new IllegalArgumentException("A remote observation cannot replace verified completion");
         }
         version = new PlanLifecycle(service).checkIn(c, principal, plan, expectedVersion, step, "REPORTED_COMPLETE", "Human reviewed shared reminder observation " + observation + "; remote editor identity and financial outcome unverified. " + note, null);
      }
      CarlService.execute(c, "UPDATE carl_reminder_observation SET review_state=?,reviewed_by=?,reviewed_at=now(),reviewed_version=?,review_note=? WHERE id=?", decision, actor.id(), version, note, observation);
      CarlService.complete(c, request, plan, "COMPLETE", "Remote source reviewed; financial execution remains unverified");
      return version;
   }



   private static CarlService.Member authorize(Connection c, String principal, long plan) throws SQLException
   {
      var before = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR UPDATE", before.householdId());
      var actor = CarlService.manager(c, principal, "CALENDAR");
      CarlService.manager(c, principal, "FINANCE");
      if(before.householdId() != actor.householdId() || CarlService.rows(c, "SELECT id FROM carl_plan_view WHERE principal=? AND id=?", principal, plan).size() != 1)
      {
         throw new SecurityException("Plan unavailable");
      }
      CarlService.rows(c, "SELECT record_id FROM carl_plan WHERE record_id=? FOR UPDATE", plan);
      return actor;
   }
}
