/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.kof22.carlai.calendar.AgendaDecoder;
import com.kof22.carlai.calendar.CalDavClient;


/** Explicit bounded reads synchronize one configured shared calendar into Carl's authoritative projection. */
public final class CalendarAgendaService
{
   /** Trusted fixed-collection read boundary; no provider URL comes from a request. */
   public interface Provider extends AutoCloseable
   {
      /** Probes component support without changing provider state. */
      Set<String> components();



      /** Returns the entire bounded event query or throws on incomplete transport results. */
      List<CalDavClient.Resource> query(Instant from, Instant through);



      @Override
      void close();
   }



   private record Frame(long connection, long revision, long epoch, CarlService.Member actor, boolean complete)
   {
   }

   private final CarlService service;
   private final String alias;
   private final String standingPrincipal;
   private final String binding;
   private final Set<Long> audience;
   private final Supplier<Provider> providers;

   /** Configuration is operator-owned and grants no permissions by itself. */
   public CalendarAgendaService(CarlService service, String alias, String standingPrincipal, String binding, Set<Long> audience, Supplier<Provider> providers)
   {
      if(binding == null || !binding.matches("[a-f0-9]{64}") || alias == null || !alias.matches("[a-z][a-z0-9-]{0,39}") || standingPrincipal == null || standingPrincipal.isBlank() || audience == null || audience.isEmpty() || audience.size() > 16 || audience.stream().anyMatch(id -> id <= 0))
      {
         throw new IllegalArgumentException("A fixed calendar alias, standing principal and explicit bounded audience are required");
      }
      this.service = java.util.Objects.requireNonNull(service);
      this.alias = alias;
      this.binding = binding;
      this.standingPrincipal = standingPrincipal;
      this.audience = Set.copyOf(audience);
      this.providers = java.util.Objects.requireNonNull(providers);
   }



   /** Reads a complete bounded window; failed reads retain previous local data and its last-success time. */
   public Map<String, Object> synchronize(String principal, UUID request, LocalDate from, LocalDate through)
   {
      var restore = CarlService.nestedDeadline(Duration.ofSeconds(30));
      try
      {
         return synchronizeWindow(principal, request, from, through);
      }
      finally
      {
         restore.run();
      }
   }



   private Map<String, Object> synchronizeWindow(String principal, UUID request, LocalDate from, LocalDate through)
   {
      if(request == null || from == null || through == null || from.getYear() < 1900 || through.getYear() > 2200 || through.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, through) > 89)
      {
         throw new IllegalArgumentException("Choose an explicit calendar interval of at most ninety days");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("alias", alias, "binding", binding, "from", from, "through", through, "audience", audience.stream().sorted().toList())));
      Frame frame = service.transaction(c ->
      {
         var actor = authorize(c, principal, null);
         CarlService.rows(c, "SELECT pg_advisory_xact_lock(hashtext(?))", "carl-agenda:" + actor.householdId() + ":" + alias);
         var existing = CarlService.rows(c, "SELECT x.record_id,x.collection_binding,r.revision FROM carl_calendar_connection x JOIN carl_record r ON r.id=x.record_id WHERE r.household_id=? AND x.calendar_identity=?", actor.householdId(), alias);
         long connection;
         if(existing.isEmpty())
         {
            connection = CarlService.record(c, actor, "CALENDAR", "PRIVATE", "Shared Synology calendar", "Operator-configured shared calendar; provider text is untrusted data");
            CarlService.execute(c, "INSERT INTO carl_calendar_connection(record_id,provider,calendar_identity,sync_state,collection_binding) VALUES(?,'CALDAV',?,'NOT_CONNECTED',?)", connection, alias, binding);
            grants(c, connection);
         }
         else
         {
            connection = CarlService.number(existing.getFirst(), "record_id");
            var recipients = CarlService.rows(c, "SELECT member_id FROM carl_grant WHERE record_id=? AND details", connection).stream().map(row -> CarlService.number(row, "member_id")).collect(java.util.stream.Collectors.toSet());
            if(!binding.equals(existing.getFirst().get("collection_binding")) || !recipients.equals(audience))
            {
               throw new SecurityException("Connection or shared audience changed; explicit operator migration is required");
            }
            if(CarlService.rows(c, "SELECT id FROM carl_calendar_connection_view WHERE principal=? AND id=?", principal, connection).size() != 1)
            {
               throw new SecurityException("Calendar connection unavailable");
            }
         }
         Long prior = CarlService.request(c, actor, request, "CALENDAR_SYNC", digest);
         long revision = CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", connection).getFirst(), "revision");
         if(prior == null)
         {
            CarlService.execute(c, "UPDATE carl_calendar_connection SET last_attempt=now() WHERE record_id=?", connection);
         }
         else
         {
            NativeMutationReceipt.beforeCalendar(c, actor, request);
            NativeMutationReceipt.after(c, CarlService.member(c, principal));
         }
         return new Frame(connection, revision, actor.permissionRevision(), actor, prior != null);
      });
      if(frame.complete())
      {
         return status(principal, frame.connection(), request);
      }
      Instant start = from.atStartOfDay(frame.actor().zone()).toInstant();
      Instant end = through.plusDays(1).atStartOfDay(frame.actor().zone()).toInstant();
      if(Duration.between(start, end).compareTo(Duration.ofDays(90)) > 0)
      {
         return failed(principal, request, frame, "WINDOW_EXCEEDS_PROVIDER_BOUND");
      }
      final List<CalDavClient.Resource> resources;
      try(var provider = providers.get())
      {
         if(!provider.components().contains("VEVENT"))
         {
            return failed(principal, request, frame, "VEVENT_UNSUPPORTED");
         }
         resources = provider.query(start, end);
         if(resources.size() > 1000)
         {
            return failed(principal, request, frame, "RESOURCE_BOUND");
         }
      }
      catch(CalDavClient.DavException failure)
      {
         return failed(principal, request, frame, failure.status() == 401 || failure.status() == 403 ? "CREDENTIALS_EXPIRED" : "PROVIDER_READ_FAILED");
      }
      catch(RuntimeException failure)
      {
         return failed(principal, request, frame, "PROVIDER_READ_FAILED");
      }
      try
      {
         service.transaction(c ->
         {
            var actor = authorize(c, principal, frame);
            lockConnection(c, frame);
            NativeMutationReceipt.beforeCalendar(c, actor, request);
            var seenSeries = new HashSet<String>();
            var occurrences = new ArrayList<AgendaDecoder.Occurrence>();
            for(var resource : resources)
            {
               var ids = new HashMap<String, UUID>();
               for(String raw : AgendaDecoder.identities(resource.calendar()))
               {
                  if(!seenSeries.add(raw) || seenSeries.size() > 1000)
                  {
                     throw new IllegalArgumentException("Conflicting duplicate calendar series");
                  }
                  CarlService.execute(c, "INSERT INTO carl_calendar_series(connection_id,provider_uid,stable_id) VALUES(?,?,?) ON CONFLICT(connection_id,provider_uid) DO NOTHING", frame.connection(), raw, UUID.randomUUID());
                  ids.put(raw, UUID.fromString(CarlService.rows(c, "SELECT stable_id FROM carl_calendar_series WHERE connection_id=? AND provider_uid=?", frame.connection(), raw).getFirst().get("stable_id").toString()));
               }
               occurrences.addAll(AgendaDecoder.decode(resource.calendar(), resource.href(), start, end, actor.zone(), new AgendaDecoder.Access(ids, ids.keySet())));
               if(occurrences.size() > 1000)
               {
                  throw new IllegalArgumentException("Narrow the agenda window; occurrence bound exceeded");
               }
            }
            Set<Long> retained = new HashSet<>();
            for(var event : occurrences)
            {
               retained.add(save(c, actor, frame.connection(), event));
            }
            var prior = CarlService.rows(c, "SELECT record_id FROM carl_calendar_event WHERE connection_id=? AND NOT snapshot_absent AND ((start_at<? AND end_at>?) OR (all_day_start<=? AND all_day_end_exclusive>?)) LIMIT 1001", frame.connection(), java.sql.Timestamp.from(end), java.sql.Timestamp.from(start), through, from);
            if(prior.size() > 1000)
            {
               throw new IllegalArgumentException("Stored agenda exceeds supported window bounds");
            }
            for(var row : prior)
            {
               long id = CarlService.number(row, "record_id");
               if(!retained.contains(id))
               {
                  CarlService.execute(c, "UPDATE carl_calendar_event SET snapshot_absent=true WHERE record_id=?", id);
                  CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
               }
            }
            CarlService.execute(c, "UPDATE carl_calendar_connection SET sync_state='CURRENT',last_success=now(),last_attempt=now(),failure_code=NULL,coverage_from=?,coverage_through=? WHERE record_id=?", from, through, frame.connection());
            CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", frame.connection());
            CarlService.complete(c, request, frame.connection(), "COMPLETE", "Read-only calendar projection synchronized for the requested interval");
            NativeMutationReceipt.after(c, CarlService.member(c, principal));
            return null;
         });
      }
      catch(IllegalArgumentException unsupported)
      {
         return failed(principal, request, frame, "UNSUPPORTED_OR_CONFLICTING_CALENDAR_DATA");
      }
      return status(principal, frame.connection(), request);
   }



   private long save(Connection c, CarlService.Member actor, long connection, AgendaDecoder.Occurrence event) throws SQLException
   {
      var existing = CarlService.rows(c, "SELECT record_id FROM carl_calendar_event WHERE connection_id=? AND provider_event_id=? AND occurrence_id=?", connection, event.seriesId().toString(), event.occurrenceId());
      String title = event.title() == null || event.title().isBlank() ? "Untitled calendar event" : event.title();
      String evidence = event.description() == null ? "Calendar source; details may be restricted" : event.description();
      CarlService.bounded(title, 2000, "calendar title");
      CarlService.bounded(evidence, 20000, "calendar evidence");
      long id = existing.isEmpty() ? CarlService.record(c, actor, "CALENDAR", "PRIVATE", title, evidence) : CarlService.number(existing.getFirst(), "record_id");
      if(existing.isEmpty())
      {
         grants(c, id);
      }
      else
      {
         CarlService.execute(c, "UPDATE carl_record SET title=?,evidence=?,revision=revision+1 WHERE id=?", title, evidence, id);
      }
      boolean busyOnly = event.source() == null;
      CarlService.execute(c, "INSERT INTO carl_calendar_event(record_id,connection_id,provider_event_id,occurrence_id,start_at,end_at,source_zone,all_day_start,all_day_end_exclusive,cancelled,transparent,free_busy_only,source_reference,snapshot_absent,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,false,now()) ON CONFLICT(record_id) DO UPDATE SET start_at=EXCLUDED.start_at,end_at=EXCLUDED.end_at,source_zone=EXCLUDED.source_zone,all_day_start=EXCLUDED.all_day_start,all_day_end_exclusive=EXCLUDED.all_day_end_exclusive,cancelled=EXCLUDED.cancelled,transparent=EXCLUDED.transparent,free_busy_only=EXCLUDED.free_busy_only,source_reference=EXCLUDED.source_reference,snapshot_absent=false,observed_at=now()", id, connection, event.seriesId().toString(), event.occurrenceId(), event.allDay() ? null : java.sql.Timestamp.from(event.start()), event.allDay() ? null : java.sql.Timestamp.from(event.end()), event.sourceZone(), event.allDay() ? event.start().atZone(actor.zone()).toLocalDate() : null,
         event.allDay() ? event.end().atZone(actor.zone()).toLocalDate() : null, event.canceled(), !event.busy(), busyOnly, busyOnly ? null : event.source().toString());
      return id;
   }



   private CarlService.Member authorize(Connection c, String principal, Frame frame) throws SQLException
   {
      var before = CarlService.manager(c, principal, "CALENDAR");
      CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR UPDATE", before.householdId());
      var actor = CarlService.manager(c, principal, "CALENDAR");
      var standing = CarlService.manager(c, standingPrincipal, "CALENDAR");
      if(actor.householdId() != before.householdId() || standing.householdId() != actor.householdId() || !audience.contains(actor.id()) || !audience.contains(standing.id()) || frame != null && (frame.actor().householdId() != actor.householdId() || frame.epoch() != actor.permissionRevision()))
      {
         throw new SecurityException("Calendar membership or permissions changed");
      }
      for(long recipient : audience)
      {
         if(CarlService.rows(c, "SELECT m.id FROM carl_member m JOIN carl_permission p ON p.member_id=m.id AND p.domain='CALENDAR' AND p.details WHERE m.id=? AND m.household_id=? AND m.active", recipient, actor.householdId()).size() != 1)
         {
            throw new SecurityException("Configured calendar audience is no longer authorized");
         }
      }
      return actor;
   }



   private static void lockConnection(Connection c, Frame frame) throws SQLException
   {
      var row = CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=? FOR UPDATE", frame.connection());
      if(row.size() != 1 || CarlService.number(row.getFirst(), "revision") != frame.revision())
      {
         throw new IllegalArgumentException("A newer synchronization changed this connection");
      }
   }



   private void grants(Connection c, long record) throws SQLException
   {
      for(long member : audience)
      {
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,?,true)", record, member);
      }
   }



   private Map<String, Object> failed(String principal, UUID request, Frame frame, String code)
   {
      service.transaction(c ->
      {
         var actor = authorize(c, principal, frame);
         NativeMutationReceipt.beforeCalendar(c, actor, request);
         var current = CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=? FOR UPDATE", frame.connection()).getFirst();
         if(CarlService.number(current, "revision") == frame.revision())
         {
            CarlService.execute(c, "UPDATE carl_calendar_connection SET sync_state=?,failure_code=?,last_attempt=now() WHERE record_id=?", code.equals("CREDENTIALS_EXPIRED") ? "EXPIRED" : "FAILED", code, frame.connection());
            CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", frame.connection());
         }
         CarlService.complete(c, request, frame.connection(), "PARTIAL", "Read failed or was superseded; previous local projection retained");
         NativeMutationReceipt.after(c, CarlService.member(c, principal));
         return null;
      });
      return status(principal, frame.connection(), request);
   }



   private Map<String, Object> status(String principal, long connection, UUID request)
   {
      return service.transaction(c ->
      {
         CarlService.member(c, principal);
         var rows = CarlService.rows(c, "SELECT * FROM carl_calendar_connection_view WHERE principal=? AND id=?", principal, connection);
         if(rows.size() != 1)
         {
            throw new SecurityException("Calendar connection unavailable");
         }
         var result = new LinkedHashMap<>(rows.getFirst());
         result.remove("principal");
         result.put("requestStatus", CarlService.rows(c, "SELECT status FROM carl_request WHERE id=?", request).getFirst().get("status"));
         result.put("scope", "Configured shared calendar and requested window only; private event details remain excluded");
         return result;
      });
   }
}
