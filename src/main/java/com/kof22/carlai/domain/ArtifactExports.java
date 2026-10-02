/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.carlai.report.FinancialReportFigures;
import com.kof22.carlai.report.PlanPdfRenderer;


/** Protected local report copies and inert PDF downloads from immutable authorized artifacts. */
public final class ArtifactExports
{
   /** Download representations never authorize a financial action. */
   public enum Format
   {
      PDF, TEXT
   }



   private record Snapshot(Map<String, Object> artifact, long member, long epoch, String digest)
   {
   }

   private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private static final Set<String> KINDS = Set.of("HOUSEHOLD_REPORT", "BILL_REPORT", "BILL_COMPARISON", "CALENDAR_REPORT", "VENDOR_REPORT", "FINANCIAL_PLAN", "TAX_PACKET");
   private final CarlService service;
   /** Shares current domain access with the administrative and conversational interfaces. */
   public ArtifactExports(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** A UI copy is freshly authorized; no unprotected artifact URL is issued. */
   public String copy(String principal, long artifact)
   {
      return service.transaction(c -> text(snapshot(c, principal, artifact).artifact()));
   }



   /** Saves exact bytes for one request; changed or conflicting requests never silently replace a file. */
   public UUID generate(String principal, UUID request, long artifact, Format format)
   {
      java.util.Objects.requireNonNull(request);
      java.util.Objects.requireNonNull(format);
      var frozen = service.transaction(c ->
      {
         var snapshot = snapshot(c, principal, artifact);
         var prior = CarlService.rows(c, "SELECT artifact_id,requester_id,format FROM carl_report_export WHERE id=?", request);
         if(!prior.isEmpty())
         {
            var row = prior.getFirst();
            if(CarlService.number(row, "artifact_id") != artifact || CarlService.number(row, "requester_id") != snapshot.member() || !row.get("format").equals(format.name()))
            {
               throw new IllegalArgumentException("Report export request conflicts with prior input");
            }
            checked(c, principal, request);
            return null;
         }
         return snapshot;
      });
      if(frozen == null)
      {
         return request;
      }
      byte[] content = format == Format.PDF ? pdf(frozen.artifact()) : text(frozen.artifact()).getBytes(StandardCharsets.UTF_8);
      if(content.length > 4_000_000)
      {
         throw new IllegalArgumentException("Narrow the report; download exceeds the 4 MB bound");
      }
      service.transaction(c ->
      {
         var current = snapshot(c, principal, artifact);
         if(current.epoch() != frozen.epoch() || !current.digest().equals(frozen.digest()))
         {
            throw new IllegalArgumentException("Report inputs or permissions changed during export; regenerate from current evidence");
         }
         CarlService.execute(c, "INSERT INTO carl_report_export(id,artifact_id,requester_id,permission_revision,format,snapshot_digest,content) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING", request, artifact, current.member(), current.epoch(), format.name(), current.digest(), content);
         var stored = checked(c, principal, request);
         if(!stored.get("format").equals(format.name()) || CarlService.number(stored, "artifact_id") != artifact)
         {
            throw new IllegalArgumentException("Report export request conflicts with prior input");
         }
         return null;
      });
      return request;
   }



   /** Rechecks current membership, exact artifact/source access, permission epoch and every source revision. */
   public byte[] load(String principal, UUID request, Format format)
   {
      return service.transaction(c ->
      {
         var actor = CarlService.member(c, principal);
         var row = checked(c, principal, request);
         if(!row.get("format").equals(format.name()))
         {
            throw new SecurityException("Report download unavailable");
         }
         NativeReadScope.check(actor);
         return (byte[]) row.get("content");
      });
   }



   /** Returns explicit bounded byte pages or a manifest, reauthorizing every retrieval. */
   public Map<String, Object> page(String principal, UUID request, Format format, int offset, int limit)
   {
      if(offset < 0 || offset > 4_000_000 || limit < 0 || limit > 65536 || limit == 0 && offset != 0)
      {
         throw new IllegalArgumentException("Choose a bounded export byte page");
      }
      return service.transaction(c ->
      {
         var row = checked(c, principal, request);
         if(!row.get("format").equals(format.name()))
         {
            throw new SecurityException("Report download unavailable");
         }
         byte[] content = (byte[]) row.get("content");
         if(offset > content.length)
         {
            throw new IllegalArgumentException("Offset exceeds saved export");
         }
         var result = new java.util.LinkedHashMap<String, Object>();
         result.put("artifactId", CarlService.number(row, "artifact_id"));
         result.put("exportRequest", request.toString());
         result.put("format", format.name());
         result.put("mediaType", format == Format.PDF ? "application/pdf" : "text/plain; charset=UTF-8");
         result.put("totalBytes", content.length);
         result.put("sha256", java.util.HexFormat.of().formatHex(digest(content)));
         result.put("offset", offset);
         result.put("limit", limit);
         result.put("downloadWorkflow", "report-download");
         if(limit > 0)
         {
            int end = Math.min(content.length, offset + limit);
            result.put("contentBase64", java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(content, offset, end)));
            result.put("nextOffset", end < content.length ? end : null);
         }
         return result;
      });
   }



   private static byte[] digest(byte[] content)
   {
      try
      {
         return java.security.MessageDigest.getInstance("SHA-256").digest(content);
      }
      catch(java.security.NoSuchAlgorithmException unavailable)
      {
         throw new IllegalStateException("SHA-256 unavailable", unavailable);
      }
   }



   private static Snapshot snapshot(Connection c, String principal, long artifact) throws SQLException
   {
      var initial = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", initial.householdId());
      var actor = CarlService.member(c, principal);
      if(actor.householdId() != initial.householdId())
      {
         throw new SecurityException("Report download unavailable");
      }
      var rows = CarlService.rows(c, "SELECT * FROM carl_artifact_view WHERE principal=? AND id=?", principal, artifact);
      if(rows.size() != 1 || !KINDS.contains(rows.getFirst().get("kind")))
      {
         throw new SecurityException("Report download unavailable");
      }
      var row = rows.getFirst();
      row.remove("principal");
      var sources = CarlService.rows(c, "SELECT s.source_id,r.revision FROM carl_artifact_source s JOIN carl_record r ON r.id=s.source_id WHERE s.artifact_id=? ORDER BY s.source_id LIMIT 2001", artifact);
      if(sources.size() > 2000)
      {
         throw new IllegalArgumentException("Narrow the report; source reference count exceeds the export bound");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("artifact", row, "currentSources", sources)));
      return new Snapshot(row, actor.id(), actor.permissionRevision(), digest);
   }



   private static Map<String, Object> checked(Connection c, String principal, UUID request) throws SQLException
   {
      var actor = CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT * FROM carl_report_export WHERE id=? AND requester_id=?", request, actor.id());
      if(rows.size() != 1)
      {
         throw new SecurityException("Report download unavailable");
      }
      var row = rows.getFirst();
      var current = snapshot(c, principal, CarlService.number(row, "artifact_id"));
      if(current.epoch() != CarlService.number(row, "permission_revision") || !current.digest().equals(row.get("snapshot_digest")))
      {
         throw new SecurityException("Report inputs or access changed; generate a new authorized export");
      }
      return row;
   }



   private static JsonNode facts(Map<String, Object> artifact)
   {
      try
      {
         return JSON.readTree(artifact.get("facts").toString());
      }
      catch(IOException invalid)
      {
         throw new IllegalStateException("Stored report facts are invalid", invalid);
      }
   }



   private static String text(Map<String, Object> artifact)
   {
      var facts = facts(artifact);
      var text = new StringBuilder("Carl AI — ").append(artifact.get("title")).append("\nRecord ").append(artifact.get("id")).append(" / version ").append(artifact.get("version")).append("\nGenerated ").append(artifact.get("created_at")).append("\nStatus: ").append(artifact.get("status_label")).append(" / narration: ").append(artifact.get("narration_state")).append("\nScope: ").append(facts.path("scope").asText("Saved authorized artifact audience only")).append("\nSources currently stale: ").append(artifact.get("stale")).append("\nVerified figures\n");
      for(var figure : FinancialReportFigures.from(facts))
      {
         text.append(figure.label()).append(": ").append(com.kof22.carlai.report.MoneyPresentation.format(figure.amount(), figure.currency())).append("\n").append(figure.evidence()).append('\n');
      }
      text.append("\nNarration / interpretation\n").append(artifact.get("narrative")).append("\nLimitations\n").append(artifact.get("limitations")).append("\nSupporting deterministic snapshot\n").append(com.kof22.carlai.report.MoneyPresentation.humanFacts(facts).toPrettyString());
      if(text.length() > 400_000)
      {
         throw new IllegalArgumentException("Narrow the report; maximum 400000 characters for a complete copy");
      }
      return text.toString();
   }



   private static byte[] pdf(Map<String, Object> artifact)
   {
      var facts = facts(artifact);
      var sources = new ArrayList<PlanPdfRenderer.Source>();
      String narrative = artifact.get("narrative").toString();
      sections(sources, "Narration", "Model interpretation — separate from verified figures", narrative);
      for(String key : List.of("bills", "calendar", "calendarConnections", "vendorWork", "properties", "events", "connections", "workItems", "vendors", "recordDifferences"))
      {
         for(var row : facts.path(key))
         {
            String title = row.path("title").asText(row.path("description").asText("Saved source record"));
            sections(sources, key + ":" + row.path("id").asText(row.path("beforeRecord").asText("Unknown")), title, sourceDetails(row));
         }
      }
      for(String period : List.of("before", "after"))
      {
         for(var row : facts.path(period).path("bills"))
         {
            sections(sources, period + " bill:" + row.path("id").asText(), row.path("title").asText("Saved bill"), sourceDetails(row));
         }
      }
      if(sources.isEmpty())
      {
         sources.add(new PlanPdfRenderer.Source("record:" + artifact.get("id"), "Saved deterministic calculation", "Source references and full inputs are available in the authenticated report copy. No external source is fetched by this PDF."));
      }
      var limitations = new ArrayList<String>();
      limitations.add(artifact.get("limitations").toString().isBlank() ? "Limitations were not supplied; complete coverage is not established." : artifact.get("limitations").toString());
      limitations.add("Source data currently stale: " + artifact.get("stale") + ". This file is a dated authorized snapshot; financial actions remain human-led. Available credit is not spending budget.");
      for(String key : List.of("coverageLimitations", "gaps", "exceptions", "missingInputs", "expenseCoverageGaps"))
      {
         if(facts.has(key) && !facts.path(key).isEmpty())
         {
            for(var gap : facts.path(key))
            {
               String detail = gap.isTextual() ? gap.asText() : sourceDetails(gap);
               if(!limitations.contains(detail))
               {
                  limitations.add(detail);
               }
            }
         }
      }
      try
      {
         return new PlanPdfRenderer().render(new PlanPdfRenderer.Report(artifact.get("title").toString(), "report-" + artifact.get("id"), CarlService.number(artifact, "version"), Instant.parse(artifact.get("created_at").toString()), facts.path("scope").asText("Saved authorized artifact audience only"), facts.path("asOf").asText("Source as-of not supplied"), artifact.get("status_label") + "; narration " + artifact.get("narration_state"), FinancialReportFigures.from(facts), List.of(), sources, limitations));
      }
      catch(IOException failure)
      {
         throw new IllegalStateException("Report PDF generation failed", failure);
      }
   }



   private static void sections(List<PlanPdfRenderer.Source> sources, String reference, String title, String detail)
   {
      for(int offset = 0, part = 1; offset < detail.length(); part++)
      {
         int end = Math.min(detail.length(), offset + 6000);
         if(end < detail.length() && Character.isHighSurrogate(detail.charAt(end - 1)))
         {
            end--;
         }
         sources.add(new PlanPdfRenderer.Source(reference + " / part " + part, title, detail.substring(offset, end)));
         offset = end;
         if(sources.size() > 100)
         {
            throw new IllegalArgumentException("Narrow the report; maximum 100 complete supporting sections in PDF");
         }
      }
   }



   private static String sourceDetails(JsonNode row)
   {
      row = com.kof22.carlai.report.MoneyPresentation.humanFacts(row);
      var text = new StringBuilder();
      for(String key : List.of("vendor_label", "category", "amount", "currency", "due_date", "status", "payment_evidence", "evidence", "start_at", "end_at", "all_day_start", "all_day_end_exclusive", "source_zone", "observed_at", "provider", "sync_state", "last_success", "failure_code", "follow_up", "assignee_id", "commitment", "commitment_evidence", "contact", "contact_verified", "locality", "ownership_share", "legal_owner", "market_value", "valuation_date", "beforeRecord", "afterRecord", "beforeAmount", "afterAmount", "difference", "vendorLabel"))
      {
         if(row.has(key))
         {
            text.append(key.replace('_', ' ')).append(": ").append(row.path(key).isNull() ? "Not supplied" : row.path(key).asText()).append('\n');
         }
      }
      return text.isEmpty() ? "See the saved authorized report for complete source facts." : text.toString();
   }
}
