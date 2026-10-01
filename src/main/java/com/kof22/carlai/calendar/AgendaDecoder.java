
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.TimeZoneUpdater;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.RRule;


/***************************************************************************
 ** Deterministic bounded agenda projection. Access must be established before
 ** supplying a resource; free/busy projection deliberately omits its text/link.
 ** Unsupported recurrence forms fail instead of implying an empty calendar.
 ***************************************************************************/
public final class AgendaDecoder
{
   /** Stable series and canonical occurrence key used only inside trusted calendar authorization. */
   public record EventKey(String uid, String occurrenceId)
   {
   }



   /** Explicit stable identities, series visibility and occurrence-specific private grants. */
   public record Access(Map<String, UUID> seriesIds, Set<String> detailUids, Set<EventKey> privateDetailGrants)
   {
      /** Builds a scope without any private occurrence detail grants. */
      public Access(Map<String, UUID> seriesIds, Set<String> detailUids)
      {
         this(seriesIds, detailUids, Set.of());
      }



      /** Copies and validates caller-provided calendar authorization grants. */
      public Access
      {
         if(seriesIds == null || detailUids == null || privateDetailGrants == null || privateDetailGrants.size() > 2000 || seriesIds.size() > 1000 || !seriesIds.keySet().containsAll(detailUids))
         {
            throw new IllegalArgumentException("Verified event identities and per-event detail grants are required");
         }
         seriesIds = Map.copyOf(seriesIds);
         detailUids = Set.copyOf(detailUids);
         privateDetailGrants = Set.copyOf(privateDetailGrants);
         for(EventKey grant : privateDetailGrants)
         {
            if(grant.occurrenceId() == null || !detailUids.contains(grant.uid()))
            {
               throw new IllegalArgumentException("Private detail grants require an authorized series and occurrence");
            }
         }
      }
   }



   /** Authorized agenda projection without raw provider identifiers. */
   public record Occurrence(UUID seriesId, String occurrenceId, Instant start, Instant end, String sourceZone,
      boolean allDay, String title, String description, boolean canceled, boolean busy, URI source)
   {
   }



   /** A deterministic pair of overlapping authorized agenda occurrences. */
   public record Overlap(Occurrence first, Occurrence second)
   {
   }

   private AgendaDecoder()
   {
   }



   /** Extracts bounded provider identities inside trusted ingestion, never as caller authorization. */
   public static Set<String> identities(String text)
   {
      bound(text);
      try
      {
         var calendar = new CalendarBuilder().build(new StringReader(text));
         if(calendar.validate().hasErrors())
         {
            throw new IllegalArgumentException("Calendar data failed validation");
         }
         var identities = new HashSet<String>();
         for(var component : calendar.getComponents())
         {
            if(component instanceof VEvent event)
            {
               String uid = value(event, "UID");
               if(uid == null || uid.isBlank() || uid.length() > 500 || event.getProperties("UID").size() != 1)
               {
                  throw new IllegalArgumentException("Bounded unique event identity required");
               }
               identities.add(uid);
            }
         }
         if(identities.size() > 1000)
         {
            throw new IllegalArgumentException("Calendar series bound exceeded");
         }
         return Set.copyOf(identities);
      }
      catch(java.io.IOException | net.fortuna.ical4j.data.ParserException failure)
      {
         throw new IllegalArgumentException("Calendar data cannot be parsed", failure);
      }
   }



   /** Expands supported bounded recurrence rules under explicit event-detail authorization. */
   public static List<Occurrence> decode(String text, URI source, Instant from, Instant through, ZoneId floatingZone, Access access)
   {
      if(text == null || source == null || from == null || through == null || floatingZone == null || access == null || !from.isBefore(through)
         || Duration.between(from, through).compareTo(Duration.ofDays(90)) > 0 || new TimeZoneUpdater().isEnabled())
      {
         throw new IllegalArgumentException("A bounded calendar interval, source and explicit display zone are required");
      }
      bound(text);
      try
      {
         var calendar = new CalendarBuilder().build(new StringReader(text));
         if(calendar.validate().hasErrors())
         {
            throw new IllegalArgumentException("Calendar data failed validation");
         }
         Map<String, VEvent> masters = new HashMap<>();
         Map<String, Map<String, VEvent>> overrides = new HashMap<>();
         for(var component : calendar.getComponents())
         {
            if(!(component instanceof VEvent event))
            {
               continue;
            }
            String uid = value(event, "UID");
            if(uid == null || uid.isBlank() || uid.length() > 500 || event.getProperties("UID").size() != 1)
            {
               throw new IllegalArgumentException("Calendar event has no unique bounded identity");
            }
            DateProperty<?> recurrence = date(event, "RECURRENCE-ID", false);
            if(recurrence == null)
            {
               if(masters.putIfAbsent(uid, event) != null)
               {
                  throw new IllegalArgumentException("Calendar contains duplicate series masters");
               }
            }
            else
            {
               if(recurrence.getParameter(Parameter.RANGE).isPresent())
               {
                  throw new IllegalArgumentException("Range-changing recurrence requires separately qualified provider expansion");
               }
               String key = key(recurrence.getDate(), floatingZone);
               if(overrides.computeIfAbsent(uid, ignored -> new HashMap<>()).putIfAbsent(key, event) != null)
               {
                  throw new IllegalArgumentException("Calendar contains conflicting occurrence overrides");
               }
            }
         }
         if(!access.seriesIds().keySet().containsAll(masters.keySet()))
         {
            throw new IllegalArgumentException("Calendar contains an event without a verified scoped application identity");
         }
         if(!masters.keySet().containsAll(overrides.keySet()))
         {
            throw new IllegalArgumentException("Calendar occurrence is missing its series master");
         }
         var result = new ArrayList<Occurrence>();
         for(var entry : masters.entrySet())
         {
            VEvent master = entry.getValue();
            validateRules(master);
            Temporal seed = date(master, "DTSTART", true).getDate();
            if(seed instanceof LocalDate day && master.getProperty("DTEND").isEmpty() && master.getProperty("DURATION").isEmpty())
            {
               master.add(new net.fortuna.ical4j.model.property.DtEnd<>(day.plusDays(1)));
            }
            actual(master, entry.getKey(), key(seed, floatingZone), source, floatingZone, access);
            Temporal endShape = inShape(through, seed, floatingZone);
            if(endShape instanceof LocalDate day && !through.equals(day.atStartOfDay(floatingZone).toInstant()))
            {
               endShape = day.plusDays(1);
            }
            Period<Temporal> window = new Period<>(inShape(from, seed, floatingZone), endShape);
            Set<Period<Temporal>> periods = master.calculateRecurrenceSet(window);
            if(periods.size() > 1000)
            {
               throw new IllegalArgumentException("Calendar recurrence exceeds its occurrence limit");
            }
            Map<String, VEvent> replacements = overrides.getOrDefault(entry.getKey(), Map.of());
            var used = new HashSet<String>();
            var identities = new HashSet<String>();
            for(var period : periods)
            {
               String identity = key(period.getStart(), floatingZone);
               if(!identities.add(identity))
               {
                  throw new IllegalArgumentException("Calendar has conflicting durations for one occurrence");
               }
               VEvent replacement = replacements.get(identity);
               if(replacement != null)
               {
                  used.add(identity);
                  add(result, actual(replacement, entry.getKey(), identity, source, floatingZone, overrideAccess(master, entry.getKey(), identity, access)), from, through);
               }
               else
               {
                  add(result, occurrence(master, entry.getKey(), identity, period.getStart(), period.getEnd(), source, floatingZone, access), from, through);
               }
            }
            // An override can move into the requested interval from outside it.
            for(var replacement : replacements.entrySet())
            {
               if(!used.contains(replacement.getKey()))
               {
                  add(result, actual(replacement.getValue(), entry.getKey(), replacement.getKey(), source, floatingZone, overrideAccess(master, entry.getKey(), replacement.getKey(), access)), from, through);
               }
            }
         }
         result.sort(Comparator.comparing(Occurrence::start).thenComparing(Occurrence::seriesId).thenComparing(Occurrence::occurrenceId));
         return List.copyOf(result);
      }
      catch(IllegalArgumentException invalid)
      {
         throw invalid;
      }
      catch(Exception invalid)
      {
         throw new IllegalArgumentException("Calendar data could not be projected safely");
      }
   }



   /** Finds nontransparent, noncancelled occurrence overlaps with deterministic instants. */
   public static List<Overlap> overlaps(List<Occurrence> rows)
   {
      if(rows == null || rows.size() > 2000)
      {
         throw new IllegalArgumentException("A bounded authorized agenda is required");
      }
      var sorted = rows.stream().filter(x -> x.busy() && !x.canceled()).sorted(Comparator.comparing(Occurrence::start)).toList();
      var result = new ArrayList<Overlap>();
      for(int i = 0; i < sorted.size(); i++)
      {
         for(int j = i + 1; j < sorted.size() && sorted.get(j).start().isBefore(sorted.get(i).end()); j++)
         {
            if(sorted.get(i).start().isBefore(sorted.get(j).end()))
            {
               result.add(new Overlap(sorted.get(i), sorted.get(j)));
               if(result.size() > 10_000)
               {
                  throw new IllegalArgumentException("Agenda conflicts exceed the reporting limit");
               }
            }
         }
      }
      return List.copyOf(result);
   }



   private static Occurrence actual(VEvent event, String uid, String identity, URI source, ZoneId zone, Access access)
   {
      Temporal start = date(event, "DTSTART", true).getDate();
      DateProperty<?> end = date(event, "DTEND", false);
      Temporal through;
      if(end != null)
      {
         through = end.getDate();
      }
      else if(event.getProperty("DURATION").isPresent())
      {
         net.fortuna.ical4j.model.property.Duration duration = event.getRequiredProperty("DURATION");
         through = start.plus(duration.getDuration());
      }
      else
      {
         through = start instanceof LocalDate day ? day.plusDays(1) : start;
      }
      return occurrence(event, uid, identity, start, through, source, zone, access);
   }



   private static boolean sensitive(VEvent event)
   {
      String classification = value(event, "CLASS");
      return classification != null && !"PUBLIC".equals(classification.strip().toUpperCase(java.util.Locale.ROOT));
   }



   private static Access overrideAccess(VEvent master, String uid, String occurrenceId, Access access)
   {
      if(!sensitive(master) || access.privateDetailGrants().contains(new EventKey(uid, occurrenceId)))
      {
         return access;
      }
      // An exception does not declassify a private series merely by omitting or changing CLASS.
      return new Access(access.seriesIds(), access.detailUids().stream().filter(value -> !value.equals(uid)).collect(java.util.stream.Collectors.toSet()), access.privateDetailGrants().stream().filter(grant -> !grant.uid().equals(uid)).collect(java.util.stream.Collectors.toSet()));
   }



   private static Occurrence occurrence(VEvent event, String uid, String identity, Temporal start, Temporal end, URI source, ZoneId zone, Access access)
   {
      boolean allDay = start instanceof LocalDate;
      Instant first = instant(start, zone);
      Instant last = instant(end, zone);
      if(last.isBefore(first) || Duration.between(first, last).compareTo(Duration.ofDays(366)) > 0)
      {
         throw new IllegalArgumentException("Calendar event interval is invalid or exceeds its bound");
      }
      String sourceZone = date(event, "DTSTART", true).getParameter(Parameter.TZID).map(p -> p.getValue())
         .orElse(start instanceof ZonedDateTime z ? z.getZone().getId() : start instanceof Instant || start instanceof OffsetDateTime ? "UTC" : zone.getId());
      boolean sensitive = sensitive(event);
      boolean details = access.detailUids().contains(uid) && (!sensitive || access.privateDetailGrants().contains(new EventKey(uid, identity)));
      return new Occurrence(access.seriesIds().get(uid), identity, first, last, details ? sourceZone : zone.getId(), allDay, details ? value(event, "SUMMARY") : "Busy",
         details ? value(event, "DESCRIPTION") : null, "CANCELLED".equals(value(event, "STATUS")),
         !"TRANSPARENT".equals(value(event, "TRANSP")), details ? source : null);
   }



   private static void add(List<Occurrence> rows, Occurrence row, Instant from, Instant through)
   {
      if(row.start().isBefore(through) && (row.end().isAfter(from) || row.start().equals(row.end()) && !row.start().isBefore(from)))
      {
         rows.add(row);
         if(rows.size() > 2000)
         {
            throw new IllegalArgumentException("Calendar projection exceeds its limit");
         }
      }
   }



   private static DateProperty<?> date(VEvent event, String name, boolean required)
   {
      var property = event.getProperty(name);
      if(property.isEmpty() && !required)
      {
         return null;
      }
      if(property.isEmpty() || !(property.get() instanceof DateProperty<?> date) || event.getProperties(name).size() != 1)
      {
         throw new IllegalArgumentException("Calendar event has a missing or ambiguous date");
      }
      return date;
   }



   private static String value(VEvent event, String property)
   {
      return event.getProperty(property).map(p -> p.getValue()).orElse(null);
   }



   private static Temporal inShape(Instant instant, Temporal seed, ZoneId zone)
   {
      if(seed instanceof LocalDate)
      {
         return instant.atZone(zone).toLocalDate();
      }
      if(seed instanceof LocalDateTime)
      {
         return instant.atZone(zone).toLocalDateTime();
      }
      if(seed instanceof ZonedDateTime z)
      {
         return instant.atZone(z.getZone());
      }
      if(seed instanceof OffsetDateTime offset)
      {
         return instant.atOffset(offset.getOffset());
      }
      return instant;
   }



   private static Instant instant(Temporal temporal, ZoneId zone)
   {
      if(temporal instanceof LocalDate day)
      {
         return day.atStartOfDay(zone).toInstant();
      }
      if(temporal instanceof LocalDateTime local)
      {
         if(zone.getRules().getValidOffsets(local).size() != 1)
         {
            throw new IllegalArgumentException("Floating event time is ambiguous at a daylight-saving transition");
         }
         return local.atZone(zone).toInstant();
      }
      return Instant.from(temporal);
   }



   private static String key(Temporal temporal, ZoneId zone)
   {
      return temporal instanceof LocalDate ? temporal.toString() : instant(temporal, zone).toString();
   }



   private static void validateRules(VEvent event)
   {
      if(event.getProperties("RRULE").size() > 1 || !event.getProperties("EXRULE").isEmpty())
      {
         throw new IllegalArgumentException("Multiple/exception rules require separately qualified provider expansion");
      }
      for(var property : event.getProperties("RRULE"))
      {
         Recur<?> rule = ((RRule<?>) property).getRecur();
         if(rule.getCount() > 2000)
         {
            throw new IllegalArgumentException("Counted recurrence exceeds its bounded expansion limit");
         }
         if(!Set.of(net.fortuna.ical4j.transform.recurrence.Frequency.DAILY, net.fortuna.ical4j.transform.recurrence.Frequency.WEEKLY, net.fortuna.ical4j.transform.recurrence.Frequency.MONTHLY, net.fortuna.ical4j.transform.recurrence.Frequency.YEARLY).contains(rule.getFrequency())
            || rule.getHourList().size() > 1 || rule.getMinuteList().size() > 1 || rule.getSecondList().size() > 1)
         {
            throw new IllegalArgumentException("High-frequency recurrence requires separately qualified provider expansion");
         }
      }
   }



   private static void bound(String text)
   {
      if(text.length() > 500_000 || text.getBytes(StandardCharsets.UTF_8).length > 500_000)
      {
         throw new IllegalArgumentException("Calendar resource exceeds its limit");
      }
      String[] lines = text.replaceAll("\\r?\\n[ \\t]", "").split("\\r?\\n", -1);
      if(lines.length > 10_000)
      {
         throw new IllegalArgumentException("Calendar property count exceeds its limit");
      }
      var stack = new ArrayDeque<String>();
      int components = 0;
      int listedDates = 0;
      for(String line : lines)
      {
         if(line.length() > 20_000)
         {
            throw new IllegalArgumentException("Calendar property exceeds its limit");
         }
         String upper = line.toUpperCase(java.util.Locale.ROOT);
         if(upper.startsWith("RDATE") || upper.startsWith("EXDATE"))
         {
            listedDates += 1 + (int) line.chars().filter(c -> c == ',').count();
            if(listedDates > 1000)
            {
               throw new IllegalArgumentException("Calendar recurrence date list exceeds its limit");
            }
         }
         if(upper.startsWith("BEGIN:"))
         {
            stack.push(upper.substring(6));
            if(++components > 1000 || stack.size() > 4)
            {
               throw new IllegalArgumentException("Calendar component complexity exceeds its limit");
            }
         }
         else if(upper.startsWith("END:") && (stack.isEmpty() || !stack.pop().equals(upper.substring(4))))
         {
            throw new IllegalArgumentException("Calendar component structure is invalid");
         }
      }
      if(!stack.isEmpty())
      {
         throw new IllegalArgumentException("Calendar component is unterminated");
      }
   }
}
