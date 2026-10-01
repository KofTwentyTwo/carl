/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


/** Source-backed focused reports are local derived records, never external actions. */
public final class FocusedReports
{
   /** Supported bounded report domains. */
   public enum Focus
   {
      BILLS, CALENDAR, VENDORS
   }
   private final CarlService service;
   /** Shares Carl's authoritative services and configured clock. */
   public FocusedReports(CarlService service)
   {
      this.service = service;
   }



   /** Produces an explicitly requested protected report, with optional narration. */
   public long generate(CarlService.Scope scope, UUID request, Focus focus, LocalDate from, LocalDate through, CarlService.Narrator narrator)
   {
      Runnable restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         return generateBounded(scope, request, focus, from, through, narrator);
      }
      finally
      {
         restore.run();
      }
   }



   private long generateBounded(CarlService.Scope scope, UUID request, Focus focus, LocalDate from, LocalDate through, CarlService.Narrator narrator)
   {
      CarlService.interval(from, through);
      Objects.requireNonNull(focus);
      long epoch = service.member(scope.principal()).permissionRevision();
      String kind = switch(focus)
      {
         case BILLS -> "BILL_REPORT";
         case CALENDAR -> "CALENDAR_REPORT";
         case VENDORS -> "VENDOR_REPORT";
      };
      String digest = BillCsv.hash(focus + ":" + from + ":" + through);
      Long prior = service.claimArtifact(scope, request, kind, digest);
      if(prior != null)
      {
         requireResult(scope, prior);
         return prior;
      }
      var facts = new LinkedHashMap<String, Object>();
      var sources = new LinkedHashMap<Long, Long>();
      var gaps = new ArrayList<String>();
      facts.put("focus", focus);
      facts.put("from", from);
      facts.put("through", through);
      facts.put("scope", "Only records accessible to every intended recipient; whole-household coverage is unverified.");
      gaps.add("Authorized source coverage is not independently complete; totals do not establish the whole household position.");
      switch(focus)
      {
         case BILLS ->
         {
            facts.putAll(service.billSummary(scope, from, through));
            @SuppressWarnings("unchecked")
            var bills = (List<Map<String, Object>>) facts.get("bills");
            source(sources, bills);
            LocalDate today = service.reportTime().atZone(service.member(scope.principal()).zone()).toLocalDate();
            facts.put("statusAsOf", today);
            facts.put("overdueUnpaidRecordIds", bills.stream().filter(row -> "UNPAID".equals(row.get("status")) && row.get("due_date") != null && LocalDate.parse(row.get("due_date").toString()).isBefore(today)).map(row -> row.get("id")).toList());
            if(!((List<?>) facts.get("missingOrUncertainRecordIds")).isEmpty())
            {
               gaps.add("Bills have missing dates, amounts or uncertain status; absent values are not zero.");
            }
            facts.put("paymentEvidenceRule", "PAID_ASSERTED is a human assertion, not independently verified payment.");
         }
         case CALENDAR ->
         {
            ZoneId zone = service.member(scope.principal()).zone();
            var events = service.reportCalendar(scope, from, through);
            var connections = service.view(scope, "calendarConnections");
            var conflicts = CarlService.calendarOverlaps(events, zone);
            facts.put("events", events);
            facts.put("connections", connections);
            facts.put("apparentOverlaps", conflicts);
            facts.put("displayZone", zone.toString());
            source(sources, events);
            source(sources, connections);
            if(connections.isEmpty())
            {
               gaps.add("No authorized calendar connection; an empty agenda does not prove free time.");
            }
            for(var connection : connections)
            {
               if(!"CURRENT".equals(connection.get("sync_state")) || connection.get("last_success") == null || connection.get("coverage_from") == null || connection.get("coverage_through") == null || LocalDate.parse(connection.get("coverage_from").toString()).isAfter(from) || LocalDate.parse(connection.get("coverage_through").toString()).isBefore(through))
               {
                  gaps.add("Connection " + connection.get("id") + " has incomplete or stale coverage; last successful sync: " + connection.get("last_success"));
               }
            }
            if(events.stream().anyMatch(row -> "Busy".equals(row.get("title"))))
            {
               gaps.add("Some events permit free/busy only; their private details are omitted.");
            }
            if(Boolean.TRUE.equals(conflicts.get("truncated")))
            {
               gaps.add("Apparent overlap list reached its bound; narrow the interval.");
            }
         }
         case VENDORS ->
         {
            var vendors = service.view(scope, "vendors");
            var work = service.view(scope, "work");
            facts.put("vendors", vendors);
            facts.put("workItems", work);
            facts.put("followUpsInPeriod", work.stream().filter(row -> row.get("follow_up") != null && !LocalDate.parse(row.get("follow_up").toString()).isBefore(from) && !LocalDate.parse(row.get("follow_up").toString()).isAfter(through)).toList());
            facts.put("awaitingFamily", work.stream().filter(row -> "FAMILY_RESPONSE".equals(row.get("status"))).toList());
            facts.put("awaitingVendor", work.stream().filter(row -> "VENDOR_RESPONSE".equals(row.get("status"))).toList());
            facts.put("missingInformation", work.stream().filter(row -> "INFORMATION_NEEDED".equals(row.get("status"))).toList());
            facts.put("periodMeaning", "Current authorized work plus follow-ups due in the selected interval; this is not a historical vendor ledger.");
            facts.put("commitmentRule", "Only recorded commitment evidence is factual; a suggested follow-up is not an agreement or a send.");
            source(sources, vendors);
            source(sources, work);
         }
         default -> throw new IllegalArgumentException("Unsupported report focus");
      }
      return persist(scope, request, kind, from, through, facts, gaps, sources, narrator, digest, epoch);
   }



   /** Compares named source groups only when both selected intervals have matching, unambiguous coverage. */
   public long compareBills(CarlService.Scope scope, UUID request, LocalDate beforeFrom, LocalDate beforeThrough, LocalDate afterFrom, LocalDate afterThrough, CarlService.Narrator narrator)
   {
      Runnable restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         return compareBounded(scope, request, beforeFrom, beforeThrough, afterFrom, afterThrough, narrator);
      }
      finally
      {
         restore.run();
      }
   }



   private long compareBounded(CarlService.Scope scope, UUID request, LocalDate beforeFrom, LocalDate beforeThrough, LocalDate afterFrom, LocalDate afterThrough, CarlService.Narrator narrator)
   {
      CarlService.interval(beforeFrom, beforeThrough);
      CarlService.interval(afterFrom, afterThrough);
      if(!beforeThrough.isBefore(afterFrom))
      {
         throw new IllegalArgumentException("Choose two non-overlapping ordered bill periods");
      }
      long epoch = service.member(scope.principal()).permissionRevision();
      String digest = BillCsv.hash(beforeFrom + ":" + beforeThrough + ":" + afterFrom + ":" + afterThrough);
      Long prior = service.claimArtifact(scope, request, "BILL_COMPARISON", digest);
      if(prior != null)
      {
         requireResult(scope, prior);
         return prior;
      }
      var before = service.billSummary(scope, beforeFrom, beforeThrough);
      var after = service.billSummary(scope, afterFrom, afterThrough);
      @SuppressWarnings("unchecked")
      var oldRows = (List<Map<String, Object>>) before.get("bills");
      @SuppressWarnings("unchecked")
      var newRows = (List<Map<String, Object>>) after.get("bills");
      var sources = new LinkedHashMap<Long, Long>();
      source(sources, oldRows);
      source(sources, newRows);
      var gaps = new ArrayList<String>();
      gaps.add("Comparison covers authorized selected records only; source completeness and household-wide totals are not established.");
      var oldGroups = groups(oldRows);
      var newGroups = groups(newRows);
      boolean comparable = java.time.temporal.ChronoUnit.DAYS.between(beforeFrom, beforeThrough) == java.time.temporal.ChronoUnit.DAYS.between(afterFrom, afterThrough)
         && !oldGroups.isEmpty() && oldGroups.keySet().equals(newGroups.keySet())
         && ((List<?>) before.get("missingOrUncertainRecordIds")).isEmpty() && ((List<?>) after.get("missingOrUncertainRecordIds")).isEmpty();
      var differences = new ArrayList<Map<String, Object>>();
      for(String key : oldGroups.keySet())
      {
         var left = oldGroups.get(key);
         var right = newGroups.get(key);
         if(left.size() != 1 || right == null || right.size() != 1)
         {
            comparable = false;
            continue;
         }
         var a = left.getFirst();
         var b = right.getFirst();
         if(a.get("amount") == null || b.get("amount") == null || a.get("due_date") == null || b.get("due_date") == null || a.get("id").equals(b.get("id")))
         {
            comparable = false;
            continue;
         }
         differences.add(Map.of("beforeRecord", a.get("id"), "afterRecord", b.get("id"), "currency", a.get("currency"), "status", a.get("status"), "vendorLabel", a.get("vendor_label"), "beforeAmount", a.get("amount"), "afterAmount", b.get("amount"), "difference", ((BigDecimal) b.get("amount")).subtract((BigDecimal) a.get("amount"))));
      }
      if(!comparable)
      {
         gaps.add("Unequal periods, changed coverage, duplicate source groups or missing facts prevent a comparable period total; review each period separately.");
         differences.clear();
      }
      var facts = new LinkedHashMap<String, Object>();
      facts.put("before", before);
      facts.put("after", after);
      facts.put("comparableSelectedRecords", comparable);
      facts.put("recordDifferences", differences);
      var totalDifferences = new LinkedHashMap<String, BigDecimal>();
      differences.forEach(row -> totalDifferences.merge(row.get("currency") + ":" + row.get("status"), (BigDecimal) row.get("difference"), BigDecimal::add));
      facts.put("selectedCurrencyStatusDifferences", totalDifferences);
      facts.put("comparisonRule", "Exact matching supplied vendor label, bill title, currency and payment status, one record per group and equal-length intervals. Labels do not prove identical usage or tariff.");
      facts.put("causes", "Not established. No change in usage, rate, fees, household income or future expense is inferred from the amount difference.");
      return persist(scope, request, "BILL_COMPARISON", beforeFrom, afterThrough, facts, gaps, sources, narrator, digest, epoch);
   }



   private long persist(CarlService.Scope scope, UUID request, String kind, LocalDate from, LocalDate through, Map<String, Object> facts, List<String> gaps, Map<Long, Long> sources, CarlService.Narrator narrator, String digest, long epoch)
   {
      facts.put("coverageLimitations", List.copyOf(gaps));
      // Timestamp comes from the same configured application clock as all bill summaries.
      facts.put("generatedAt", service.reportTime().toString());
      String verified = CarlService.json(facts);
      if(verified.getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
      {
         throw new IllegalArgumentException("Report exceeds 512 KiB; narrow the source scope or interval");
      }
      String narrative = "";
      String state = "NOT_REQUESTED";
      if(narrator != null)
      {
         try
         {
            narrative = Objects.requireNonNull(narrator.narrate(verified));
            CarlService.bounded(narrative, 100000, "report narration");
            state = "COMPLETE";
         }
         catch(Exception failure)
         {
            narrative = "Narration failed; deterministic source facts remain available.";
            state = "FAILED";
         }
      }
      return service.saveArtifact(scope, request, kind, from, through, verified, narrative, state, String.join(" ", gaps), sources, null, "Incomplete focused report — review authorized coverage", digest, epoch);
   }



   private void requireResult(CarlService.Scope scope, long id)
   {
      for(String principal : scope.audience())
      {
         service.artifact(principal, id);
      }
   }



   /** Renders a bounded plain-text review while preserving complete facts in the protected artifact. */
   public String presentation(String principal, long id)
   {
      var artifact = service.artifact(principal, id);
      if(!List.of("BILL_REPORT", "CALENDAR_REPORT", "VENDOR_REPORT", "BILL_COMPARISON").contains(artifact.get("kind")))
      {
         throw new IllegalArgumentException("Select a focused report");
      }
      try
      {
         var facts = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(artifact.get("facts").toString());
         var text = new StringBuilder("Carl AI — ").append(artifact.get("kind")).append("\nReport ").append(id).append(" / ").append(artifact.get("status_label")).append("\nGenerated: ").append(facts.path("generatedAt").asText()).append("\n").append(artifact.get("limitations"));
         for(String totals : List.of("totals", "selectedCurrencyStatusDifferences"))
         {
            var fields = facts.path(totals).properties();
            if(!fields.isEmpty())
            {
               text.append("\n").append(totals.equals("totals") ? "Deterministic totals by currency and status:" : "Differences over comparable selected records:");
            }
            for(var field : fields)
            {
               text.append("\n").append(field.getKey().replace(':', ' ')).append(": ").append(com.kof22.carlai.report.MoneyPresentation.format(field.getValue().decimalValue(), field.getKey().split(":", 2)[0]));
            }
         }
         for(String section : List.of("bills", "events", "workItems", "recordDifferences"))
         {
            var records = facts.path(section);
            if(!records.isArray())
            {
               continue;
            }
            text.append("\n").append(section).append(" (source records: ").append(records.size()).append("):");
            int shown = 0;
            for(var rawRecord : records)
            {
               var record = com.kof22.carlai.report.MoneyPresentation.humanFacts(rawRecord);
               if(shown++ == 100)
               {
                  text.append("\nMore records are retained in the protected report; this review shows the first 100.");
                  break;
               }
               text.append("\n");
               for(String field : List.of("id", "title", "currency", "amount", "status", "due_date", "start_at", "end_at", "all_day_start", "all_day_end_exclusive", "follow_up", "beforeRecord", "afterRecord", "beforeAmount", "afterAmount", "difference"))
               {
                  if(record.hasNonNull(field))
                  {
                     text.append(field).append("=").append(record.get(field).asText()).append("; ");
                  }
               }
            }
         }
         if(facts.has("causes"))
         {
            text.append("\n").append(facts.get("causes").asText());
         }
         if(!"NOT_REQUESTED".equals(artifact.get("narration_state")))
         {
            text.append("\nNarration: ").append(artifact.get("narration_state")).append("\n").append(artifact.get("narrative"));
         }
         if(text.length() > 64000)
         {
            throw new IllegalArgumentException("Review exceeds display bound; use a narrower report");
         }
         return text.toString();
      }
      catch(com.fasterxml.jackson.core.JsonProcessingException invalid)
      {
         throw new IllegalStateException("Stored report facts are invalid");
      }
   }



   private static void source(Map<Long, Long> sources, List<Map<String, Object>> rows)
   {
      rows.forEach(row -> sources.put(CarlService.number(row, "id"), CarlService.number(row, "revision")));
   }



   private static Map<String, List<Map<String, Object>>> groups(List<Map<String, Object>> rows)
   {
      var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
      for(var row : rows)
      {
         String key = CarlService.json(List.of(row.get("vendor_label"), row.get("title"), row.get("currency"), row.get("status")));
         groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
      }
      return groups;
   }

}
