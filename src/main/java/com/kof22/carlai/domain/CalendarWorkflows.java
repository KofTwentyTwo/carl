
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import javax.sql.DataSource;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import com.kof22.carlai.calendar.CalendarPublicationService;
import com.kof22.carlai.calendar.ReminderStatus;


/** Explicit calendar operations over fixed operator-configured collections and verified plan authority. */
public final class CalendarWorkflows
{
   /** Bounds provider reads for one agenda window; remaining reminders are reported, never silently dropped. */
   static final int REMINDER_READ_LIMIT = 50;

   private final Map<String, Function<String, CalendarPublicationService>> factories;
   private final Map<String, CalendarAgendaService> agendas;
   private final CarlService service;
   private final Semaphore capacity = new Semaphore(2);

   /** Factories are trusted application configuration, never model/provider input. */
   public CalendarWorkflows(Map<String, Function<String, CalendarPublicationService>> factories)
   {
      this(factories, Map.of());
   }



   /** Shares a bounded execution pool between explicit publication and agenda reads. */
   public CalendarWorkflows(Map<String, Function<String, CalendarPublicationService>> factories, Map<String, CalendarAgendaService> agendas)
   {
      this(factories, agendas, null);
   }



   /** Optional domain wiring retains remote reminder observations for explicit human review. */
   public CalendarWorkflows(Map<String, Function<String, CalendarPublicationService>> factories, Map<String, CalendarAgendaService> agendas, CarlService service)
   {
      this.service = service;
      this.factories = Map.copyOf(factories);
      this.agendas = Map.copyOf(agendas);
   }



   /** Reads only named deployment settings; opening transports is deferred until an authorized operation. */
   public static CalendarWorkflows configured(DataSource source, Map<String, String> environment)
   {
      var factories = new LinkedHashMap<String, Function<String, CalendarPublicationService>>();
      var authority = new PlanCalendarAuthority();
      var domain = new CarlService(source, java.time.Clock.systemUTC());
      var agendas = new LinkedHashMap<String, CalendarAgendaService>();
      for(String alias : java.util.List.of("events", "reminders"))
      {
         String prefix = alias.equals("events") ? "CARL_CALDAV_EVENTS" : "CARL_CALDAV_REMINDERS";
         String endpoint = environment.get(prefix + "_COLLECTION");
         if(endpoint == null || endpoint.isBlank())
         {
            continue;
         }
         URI uri = URI.create(endpoint);
         if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
         {
            throw new IllegalArgumentException("Configured CalDAV collections require HTTPS without embedded credentials or query parameters");
         }
         String actor = required(environment, "CARL_CALDAV_STANDING_PRINCIPAL");
         String username = required(environment, "CARL_CALDAV_USERNAME");
         String password = required(environment, "CARL_CALDAV_PASSWORD");
         Set<Long> audience;
         try
         {
            audience = java.util.Arrays.stream(required(environment, prefix + "_AUDIENCE_MEMBERS").split(",")).map(String::strip).map(Long::parseLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if(audience.isEmpty() || audience.size() > 16 || audience.stream().anyMatch(id -> id <= 0))
            {
               throw new IllegalArgumentException();
            }
         }
         catch(RuntimeException invalid)
         {
            throw new IllegalArgumentException("Configured shared calendar audience requires explicit valid member IDs");
         }
         if(alias.equals("events"))
         {
            agendas.put(alias, new CalendarAgendaService(domain, alias, actor, BillCsv.hash(uri.toString()), audience, () ->
            {
               var client = new com.kof22.carlai.calendar.CalDavClient(uri, username, password.toCharArray(), false);
               return new CalendarAgendaService.Provider()
               {
                  @Override
                  public Set<String> components()
                  {
                     return client.componentTypes();
                  }



                  @Override
                  public java.util.List<com.kof22.carlai.calendar.CalDavClient.Resource> query(java.time.Instant from, java.time.Instant through)
                  {
                     return client.query(from, through, "VEVENT");
                  }



                  @Override
                  public void close()
                  {
                     client.close();
                  }
               };
            }));
         }
         String component = alias.equals("events") ? "VEVENT" : "VTODO";
         factories.put(alias, caller -> new CalendarPublicationService(source, uri, component, audience, actor, username, password.toCharArray(), false,
            (connection, standing, plan, step, version, recipients, action) ->
            {
               // Both initiating caller and standing authority are checked under the same epoch/plan locks.
               authority.load(connection, caller, plan, step, version, recipients, action);
               var shared = authority.load(connection, standing, plan, step, version, recipients, action);
               if(CarlService.member(connection, caller).householdId() != CarlService.member(connection, standing).householdId())
               {
                  throw new SecurityException("Calendar initiating caller and standing authority must share the configured household");
               }
               return shared;
            }));
      }
      return new CalendarWorkflows(factories, agendas, domain);
   }



   /** Provides configured collection labels without exposing endpoints or credentials. */
   public Set<String> collections()
   {
      return factories.keySet();
   }



   /** Lists configured event collections eligible for bounded agenda synchronization. */
   public Set<String> agendaCollections()
   {
      return agendas.keySet();
   }



   /** Human-initiated read-only provider synchronization, with explicit bounded dates; Carl-published reminders due in the window are read back alongside events. */
   public Map<String, Object> synchronizeAgenda(String principal, String collection, UUID request, java.time.LocalDate from, java.time.LocalDate through)
   {
      var agenda = agendas.get(collection);
      if(agenda == null)
      {
         throw new IllegalArgumentException("Event collection is not configured for agenda reads");
      }
      if(!capacity.tryAcquire())
      {
         throw new IllegalStateException("Calendar operation capacity reached");
      }
      try
      {
         var events = agenda.synchronize(principal, request, from, through);
         var reminders = factories.get("reminders");
         if(reminders == null || service == null)
         {
            return events;
         }
         var result = new LinkedHashMap<String, Object>(events);
         result.put("reminders", readBackReminders(principal, reminders, from, through));
         return result;
      }
      finally
      {
         capacity.release();
      }
   }



   /** Reads back only Carl-published, unretired VTODO mappings in plans the caller can currently see. */
   private Map<String, Object> readBackReminders(String principal, Function<String, CalendarPublicationService> factory, java.time.LocalDate from, java.time.LocalDate through)
   {
      var items = new ArrayList<Map<String, Object>>();
      boolean truncated;
      try(var publication = factory.apply(principal))
      {
         var due = service.transaction(c ->
         {
            CarlService.manager(c, principal, "CALENDAR");
            return CarlService.rows(c, "SELECT m.plan_id,m.step_id,p.version FROM carl_calendar_mapping m JOIN carl_plan_view p ON p.id=m.plan_id AND p.principal=? JOIN carl_plan_step s ON s.id=m.step_id AND s.plan_id=m.plan_id WHERE m.collection_key=? AND m.component='VTODO' AND NOT m.retired AND m.last_success IS NOT NULL AND s.due_date BETWEEN ? AND ? ORDER BY s.due_date,m.step_id LIMIT ?", principal, publication.collectionKey(), from, through, REMINDER_READ_LIMIT + 1);
         });
         truncated = due.size() > REMINDER_READ_LIMIT;
         for(var row : due.subList(0, Math.min(due.size(), REMINDER_READ_LIMIT)))
         {
            items.add(readBack(principal, publication, CarlService.number(row, "plan_id"), UUID.fromString(String.valueOf(row.get("step_id"))), Math.toIntExact(CarlService.number(row, "version"))));
         }
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("collection", "reminders");
      result.put("items", List.copyOf(items));
      result.put("truncated", truncated);
      result.put("meaning", "Published reminders due in this window were read back from the shared reminders collection. Reported completion is an untrusted provider observation for human review, not verified financial completion.");
      return result;
   }



   private Map<String, Object> readBack(String principal, CalendarPublicationService publication, long plan, UUID step, int version)
   {
      var item = new LinkedHashMap<String, Object>();
      item.put("plan", plan);
      item.put("step", step.toString());
      try
      {
         var observed = publication.synchronize(plan, step, version);
         item.put("state", observed.state());
         if(observed.calendar() != null)
         {
            try
            {
               String remote = ReminderStatus.decode(step, observed.calendar()).state();
               item.put("reminderObservation", new ReminderObservations(service).capture(principal, plan, step, version, observed));
               item.put("remoteState", remote);
            }
            catch(IllegalArgumentException invalid)
            {
               item.put("reviewState", "UNSUPPORTED_OR_CHANGED_OBSERVATION");
            }
         }
      }
      catch(SQLException | SecurityException | IllegalArgumentException | IllegalStateException unavailable)
      {
         // Authority, plan revision or capacity changed for this item; the remaining reminders are still read.
         item.put("state", "NOT_READ");
      }
      return item;
   }



   /** Executes only a named plan operation; provider text never becomes a verified financial check-in. */
   public Map<String, Object> execute(String principal, String collection, String operation, UUID request, long plan, UUID step, int version)
   {
      var factory = factories.get(collection);
      if(factory == null)
      {
         throw new IllegalStateException("The selected shared calendar or reminders collection is not configured");
      }
      if(!Set.of("PUBLISH", "RETIRE", "RECONCILE", "SYNCHRONIZE", "STATUS").contains(operation) || request == null || step == null || version < 1)
      {
         throw new IllegalArgumentException("A fixed calendar operation and current plan/task identity are required");
      }
      if(operation.equals("STATUS"))
      {
         if(service == null)
         {
            throw new IllegalStateException("Domain calendar status is not configured");
         }
         return service.transaction(c ->
         {
            CarlService.manager(c, principal, "CALENDAR");
            if(CarlService.rows(c, "SELECT id FROM carl_plan_view WHERE principal=? AND id=? AND version=?", principal, plan, version).size() != 1)
            {
               throw new SecurityException("Current plan unavailable");
            }
            return Map.of("state", "CURRENT_LOCAL_OBSERVATIONS", "operations", CarlService.rows(c, "SELECT * FROM carl_calendar_operation_view WHERE principal=? AND plan_id=? AND step_id=? ORDER BY updated_at DESC LIMIT 51", principal, plan, step), "reminderObservations", CarlService.rows(c, "SELECT * FROM carl_reminder_observation_view WHERE principal=? AND plan_id=? AND step_id=? ORDER BY observed_at DESC LIMIT 51", principal, plan, step), "limit", 51, "meaning", "Most recent bounded local history; this does not refresh the provider or verify a financial action.");
         });
      }
      if(!capacity.tryAcquire())
      {
         throw new IllegalStateException("Calendar operation capacity reached; retry the same request later");
      }
      var restoreDeadline = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try(var publication = factory.apply(principal))
      {
         if(operation.equals("SYNCHRONIZE"))
         {
            var observed = publication.synchronize(plan, step, version);
            var result = new LinkedHashMap<String, Object>();
            result.put("state", observed.state());
            result.put("meaning", "Remote state is an observation, not verified financial completion.");
            if(service != null && collection.equals("reminders") && observed.calendar() != null)
            {
               try
               {
                  result.put("reminderObservation", new ReminderObservations(service).capture(principal, plan, step, version, observed));
               }
               catch(IllegalArgumentException invalid)
               {
                  result.put("reviewState", "UNSUPPORTED_OR_CHANGED_OBSERVATION");
                  result.put("reviewMeaning", "Remote data was retained but cannot be applied to the current plan. Review the source and synchronize again.");
               }
            }
            return result;
         }
         var outcome = switch(operation)
         {
            case "PUBLISH" -> publication.publish(request, plan, step, version);
            case "RETIRE" -> publication.retire(request, plan, step, version);
            default -> publication.reconcile(request, plan, step, version);
         };
         return Map.of("request", outcome.request().toString(), "state", outcome.status(), "diagnostic", outcome.diagnostic() == null ? "" : outcome.diagnostic());
      }
      catch(SQLException failure)
      {
         throw new IllegalStateException("Calendar operation outcome requires reconciliation");
      }
      finally
      {
         restoreDeadline.run();
         capacity.release();
      }
   }



   private static String required(Map<String, String> environment, String name)
   {
      String value = environment.get(name);
      if(value == null || value.isBlank())
      {
         throw new IllegalArgumentException("Missing configured calendar setting " + name);
      }
      return value;
   }
}
