
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.kof22.carlai.calendar.CalendarPublicationService;
import com.kof22.carlai.calendar.PlanCalendarCodec;


/** Shared-calendar publication uses current Carl plan and source permissions under database locks. */
public final class PlanCalendarAuthority implements CalendarPublicationService.Authority
{
   @Override
   public CalendarPublicationService.SharedItem load(Connection c, String principal, long plan, UUID step, int expectedVersion, Set<Long> audience, CalendarPublicationService.Action action) throws SQLException
   {
      var actor = CarlService.member(c, principal);
      long lockedHousehold = actor.householdId();
      long epoch = CarlService.number(CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", actor.householdId()).getFirst(), "permission_revision");
      // Recheck after obtaining the epoch lock: a revocation may have committed while waiting.
      actor = CarlService.manager(c, principal, "CALENDAR");
      if(actor.householdId() != lockedHousehold)
      {
         throw new SecurityException("Household changed during calendar authorization");
      }
      CarlService.manager(c, principal, "FINANCE");
      var locked = CarlService.rows(c, "SELECT record_id FROM carl_plan WHERE record_id=? FOR SHARE", plan);
      if(locked.size() != 1 || audience == null || audience.isEmpty())
      {
         throw new SecurityException("Calendar plan unavailable");
      }
      var plans = CarlService.rows(c, "SELECT * FROM carl_plan_view WHERE principal=? AND id=?", principal, plan);
      if(plans.size() != 1)
      {
         throw new SecurityException("Calendar plan unavailable");
      }
      var current = plans.getFirst();
      if(CarlService.number(current, "version") != expectedVersion)
      {
         throw new IllegalArgumentException("Review the current plan revision before calendar publication");
      }
      if(action == CalendarPublicationService.Action.PUBLISH && (!"AGREED".equals(current.get("state")) || Boolean.TRUE.equals(current.get("source_stale"))))
      {
         throw new IllegalArgumentException("Only an agreed plan with current source assumptions can be published");
      }
      for(long memberId : audience)
      {
         var recipients = CarlService.rows(c, "SELECT m.principal FROM carl_member m JOIN carl_permission p ON p.member_id=m.id AND p.domain='CALENDAR' AND p.details WHERE m.id=? AND m.household_id=? AND m.active", memberId, actor.householdId());
         if(recipients.size() != 1 || CarlService.rows(c, "SELECT id FROM carl_plan_view WHERE id=? AND principal=?", plan, recipients.getFirst().get("principal")).size() != 1)
         {
            throw new SecurityException("Every calendar recipient must have current access to this plan and its sources");
         }
      }
      var tasks = CarlService.rows(c, "SELECT * FROM carl_plan_step WHERE id=? AND plan_id=?", step, plan);
      if(tasks.size() != 1)
      {
         throw new SecurityException("Calendar task unavailable");
      }
      var task = tasks.getFirst();
      Instant modified = Instant.parse(current.get("updated_at").toString());
      var item = new PlanCalendarCodec.Item(step, expectedVersion, task.get("title").toString(), "Carl plan task. Financial actions are performed by people. This deadline does not book a vendor appointment.", task.get("location").toString(), modified);
      Instant reported = Set.of("REPORTED_COMPLETE", "VERIFIED_COMPLETE").contains(task.get("status")) ? Instant.parse(task.get("updated_at").toString()) : null;
      return new CalendarPublicationService.SharedItem(item, LocalDate.parse(task.get("due_date").toString()), reported, Long.toString(epoch));
   }
}
