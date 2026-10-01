/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/** Deterministic candidate windows over the intersection of permitted busy projections. */
public final class CalendarAvailability
{
   private final CarlService service;
   /** Reads the same current views and membership as Carl's reports. */
   public CalendarAvailability(CarlService service)
   {
      this.service = service;
   }
   /** A possible interval is not reserved time or proof of complete household availability. */
   public record Window(Instant from, Instant through)
   {
   }

   /** Reads at most seven days, 1000 events/collections and 100 resulting windows. */
   public Map<String, Object> suggest(CarlService.Scope scope, Instant from, Instant through, int minimumMinutes)
   {
      if(from == null || through == null || !through.isAfter(from) || Duration.between(from, through).compareTo(Duration.ofDays(7)) > 0 || minimumMinutes < 1 || minimumMinutes > 1440)
      {
         throw new IllegalArgumentException("One to seven days and a duration of 1–1440 minutes are required");
      }
      var restore = CarlService.nestedDeadline(Duration.ofSeconds(30));
      try
      {
         return service.transaction(c ->
         {
            var before = CarlService.member(c, scope.principal());
            CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", before.householdId());
            var actor = CarlService.member(c, scope.principal());
            if(actor.householdId() != before.householdId())
            {
               throw new SecurityException("Household changed");
            }
            var zone = actor.zone();
            var first = from.atZone(zone).toLocalDate();
            var last = through.minusNanos(1).atZone(zone).toLocalDate();
            Map<Long, Map<String, Object>> events = null;
            Map<Long, Map<String, Object>> connections = null;
            for(String principal : scope.audience())
            {
               var member = CarlService.member(c, principal);
               if(member.householdId() != actor.householdId() || CarlService.rows(c, "SELECT member_id FROM carl_permission WHERE member_id=? AND domain='CALENDAR'", member.id()).isEmpty())
               {
                  throw new SecurityException("Calendar access unavailable");
               }
               var eventRows = CarlService.rows(c, "SELECT * FROM carl_calendar_view WHERE principal=? AND ((start_at<? AND end_at>?) OR (all_day_start<=? AND all_day_end_exclusive>?)) ORDER BY id LIMIT 1001", principal, java.sql.Timestamp.from(through), java.sql.Timestamp.from(from), last, first);
               var connectionRows = CarlService.rows(c, "SELECT * FROM carl_calendar_connection_view WHERE principal=? ORDER BY id LIMIT 1001", principal);
               events = intersect(events, eventRows);
               connections = intersect(connections, connectionRows);
            }
            if(events == null || connections == null)
            {
               throw new SecurityException("Explicit audience required");
            }
            var limitations = new ArrayList<String>();
            var freshness = new ArrayList<Map<String, Object>>();
            if(connections.isEmpty())
            {
               limitations.add("No common authorized calendar connection establishes coverage; an empty agenda is not confirmed availability.");
            }
            for(var connection : connections.values())
            {
               var fields = new LinkedHashMap<String, Object>();
               for(String key : List.of("id", "sync_state", "last_success", "coverage_from", "coverage_through"))
               {
                  fields.put(key, connection.get(key));
               }
               freshness.add(fields);
               if(!"CURRENT".equals(connection.get("sync_state")) || connection.get("last_success") == null || connection.get("coverage_from") == null || connection.get("coverage_through") == null || LocalDate.parse(connection.get("coverage_from").toString()).isAfter(first) || LocalDate.parse(connection.get("coverage_through").toString()).isBefore(last))
               {
                  limitations.add("Connection " + connection.get("id") + " is missing, stale or does not cover the requested interval.");
               }
            }
            var busy = new ArrayList<Window>();
            var sources = new ArrayList<Long>();
            for(var event : events.values())
            {
               if(!"CURRENT".equals(event.get("sync_state")) || event.get("last_success") == null)
               {
                  limitations.add("Event " + event.get("id") + " has an older or unverified snapshot.");
               }
               if(Boolean.TRUE.equals(event.get("cancelled")) || Boolean.TRUE.equals(event.get("transparent")))
               {
                  continue;
               }
               Instant start = event.get("start_at") == null ? LocalDate.parse(event.get("all_day_start").toString()).atStartOfDay(zone).toInstant() : Instant.parse(event.get("start_at").toString());
               Instant end = event.get("end_at") == null ? LocalDate.parse(event.get("all_day_end_exclusive").toString()).atStartOfDay(zone).toInstant() : Instant.parse(event.get("end_at").toString());
               if(end.isAfter(start))
               {
                  busy.add(new Window(start.isBefore(from) ? from : start, end.isAfter(through) ? through : end));
                  sources.add(CarlService.number(event, "id"));
               }
            }
            busy.sort(Comparator.comparing(Window::from).thenComparing(Window::through));
            var windows = new ArrayList<Window>();
            Instant cursor = from;
            for(var interval : busy)
            {
               add(windows, cursor, interval.from(), minimumMinutes);
               if(interval.through().isAfter(cursor))
               {
                  cursor = interval.through();
               }
            }
            add(windows, cursor, through, minimumMinutes);
            var result = new LinkedHashMap<String, Object>();
            result.put("status", limitations.isEmpty() ? "SUGGESTIONS_FROM_SCOPED_SNAPSHOT" : "PARTIAL_SUGGESTIONS");
            result.put("from", from);
            result.put("through", through);
            result.put("displayZone", zone.toString());
            result.put("minimumMinutes", minimumMinutes);
            result.put("windows", List.copyOf(windows));
            result.put("sourceRecords", sources);
            result.put("connectionFreshness", freshness);
            result.put("coverageGaps", limitations);
            result.put("limitation", "Suggestion — not scheduled. Only records visible to every stated recipient were considered; private or missing calendars may conflict. Last synchronization is not real-time availability, and these windows reserve nothing.");
            return result;
         });
      }
      finally
      {
         restore.run();
      }
   }



   private static Map<Long, Map<String, Object>> intersect(Map<Long, Map<String, Object>> existing, List<Map<String, Object>> rows)
   {
      if(rows.size() > 1000)
      {
         throw new IllegalArgumentException("Narrow the interval or calendar selection; more than 1000 rows");
      }
      var visible = new LinkedHashMap<Long, Map<String, Object>>();
      rows.forEach(row -> visible.put(CarlService.number(row, "id"), row));
      if(existing == null)
      {
         return visible;
      }
      existing.keySet().retainAll(visible.keySet());
      return existing;
   }



   private static void add(List<Window> windows, Instant from, Instant through, int minimum)
   {
      if(through.isAfter(from) && Duration.between(from, through).compareTo(Duration.ofMinutes(minimum)) >= 0)
      {
         if(windows.size() >= 100)
         {
            throw new IllegalArgumentException("More than 100 candidate windows; narrow the request");
         }
         windows.add(new Window(from, through));
      }
   }
}
