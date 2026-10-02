/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.carlai.report.PlanPdfRenderer;


/** Generates inert PDFs from authorized plan snapshots and rechecks every download. */
public final class PlanExports
{
   private final CarlService service;
   private final PlanLifecycle plans;

   /** Uses the same authoritative plan permissions for export generation and retrieval. */
   public PlanExports(CarlService service)
   {
      this.service = service;
      this.plans = new PlanLifecycle(service);
   }



   /** Persists a bounded PDF for one stable request and source-plan version. */
   public UUID generate(String principal, UUID request, long plan)
   {
      var snapshot = plans.exportSnapshot(principal, plan);
      @SuppressWarnings("unchecked")
      var current = (Map<String, Object>) snapshot.get("plan");
      long version = CarlService.number(current, "version");
      var prior = service.transaction(c -> CarlService.rows(c, "SELECT plan_id,plan_version,requester_id FROM carl_plan_export WHERE id=?", request));
      if(!prior.isEmpty())
      {
         var row = prior.getFirst();
         if(CarlService.number(row, "plan_id") != plan || CarlService.number(row, "requester_id") != service.member(principal).id())
         {
            throw new IllegalArgumentException("Export request ID conflicts with prior input");
         }
         load(principal, request);
         return request;
      }
      var source = service.artifact(principal, CarlService.number(current, "source_artifact_id"));
      var tasks = new ArrayList<PlanPdfRenderer.Task>();
      @SuppressWarnings("unchecked")
      var steps = (List<Map<String, Object>>) snapshot.get("steps");
      for(var step : steps)
      {
         String assignee = step.get("assignee_label").toString();
         String state = step.get("status").toString();
         var status = switch(state)
         {
            case "VERIFIED_COMPLETE" -> PlanPdfRenderer.TaskStatus.COMPLETE;
            case "REPORTED_COMPLETE" -> PlanPdfRenderer.TaskStatus.IN_PROGRESS;
            case "BLOCKED" -> PlanPdfRenderer.TaskStatus.BLOCKED;
            default -> PlanPdfRenderer.TaskStatus.OPEN;
         };
         tasks.add(new PlanPdfRenderer.Task(step.get("title").toString(), assignee, LocalDate.parse(step.get("due_date").toString()), status,
            "Recorded state: " + state + ". Location: " + displayed(step.get("location"), "Not supplied") + ". Check-in: " + displayed(step.get("checkin"), "Not supplied") + ". Evidence record: " + displayed(step.get("evidence_record_id"), "Not supplied")));
      }
      var figures = new ArrayList<PlanPdfRenderer.Figure>();
      String asOf;
      try
      {
         var facts = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(source.get("facts").toString());
         asOf = facts.path("asOf").asText("Not supplied");
         figures.addAll(com.kof22.carlai.report.FinancialReportFigures.from(facts));
      }
      catch(IOException malformed)
      {
         throw new IllegalStateException("Stored source facts are invalid");
      }
      var sources = List.of(new PlanPdfRenderer.Source("Carl comparison " + source.get("id"), source.get("title").toString(), "Saved source snapshot; as-of " + asOf + "; current inputs stale: " + source.get("stale")));
      byte[] bytes;
      try
      {
         bytes = new PlanPdfRenderer().render(new PlanPdfRenderer.Report(current.get("title").toString(), "plan-" + plan, version, Instant.now(), "Current permitted plan audience; private data is not added from other members", asOf, current.get("state").toString(), figures, tasks, sources, List.of(source.get("limitations").toString(), "Financial steps are executed by people. Reported completion is not independent verification. This file is a dated snapshot, not a live balance or issuer payoff quote.")));
      }
      catch(IOException failure)
      {
         throw new IllegalStateException("PDF generation failed");
      }
      byte[] content = bytes;
      service.transaction(c ->
      {
         var actor = CarlService.member(c, principal);
         var latest = CarlService.rows(c, "SELECT * FROM carl_plan_view WHERE principal=? AND id=?", principal, plan);
         if(latest.size() != 1 || CarlService.number(latest.getFirst(), "version") != version || !latest.getFirst().get("source_stale").equals(current.get("source_stale")))
         {
            throw new IllegalArgumentException("Plan or permissions changed during export; review the current plan");
         }
         CarlService.execute(c, "INSERT INTO carl_plan_export(id,plan_id,plan_version,requester_id,permission_revision,content) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING", request, plan, version, actor.id(), actor.permissionRevision(), content);
         var stored = CarlService.rows(c, "SELECT plan_id,requester_id FROM carl_plan_export WHERE id=?", request).getFirst();
         if(CarlService.number(stored, "plan_id") != plan || CarlService.number(stored, "requester_id") != actor.id())
         {
            throw new IllegalArgumentException("Export request ID conflicts with prior input");
         }
         return null;
      });
      return request;
   }



   private static String displayed(Object value, String missing)
   {
      return value == null || value.toString().isBlank() ? missing : value.toString();
   }



   /** Returns bytes only after current membership, source permissions and household epoch checks. */
   public byte[] load(String principal, UUID id)
   {
      return service.transaction(c ->
      {
         var actor = CarlService.member(c, principal);
         var rows = CarlService.rows(c, "SELECT e.content FROM carl_plan_export e JOIN carl_plan_view p ON p.id=e.plan_id AND p.principal=? WHERE e.id=? AND e.permission_revision=?", principal, id, actor.permissionRevision());
         if(rows.size() != 1)
         {
            throw new SecurityException("Plan export unavailable");
         }
         NativeReadScope.check(actor);
         return (byte[]) rows.getFirst().get("content");
      });
   }
}
