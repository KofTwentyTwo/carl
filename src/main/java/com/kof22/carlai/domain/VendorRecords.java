/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Human-maintained vendor facts and immutable unsent draft revisions, sharing Carl's authority. */
public final class VendorRecords
{
   /** Explicit vendor corrections; contact verification remains an attributed human assertion. */
   public record Vendor(String title, String category, String contact, boolean verified)
   {
   }



   /** Local work state; recorded commitments require evidence and are never external actions. */
   public record Work(String title, String status, Long assignee, LocalDate followUp, String commitmentEvidence)
   {
   }

   private final CarlService service;
   /** Reuses the authoritative household database and identity/access services. */
   public VendorRecords(CarlService service)
   {
      this.service = service;
   }



   /** Corrects a local vendor through an optimistic, attributed and idempotent human operation. */
   public void correctVendor(String principal, UUID request, long vendor, long expectedRevision, Vendor values, String reason)
   {
      if(values == null)
      {
         throw new IllegalArgumentException("Explicit vendor fields required");
      }
      CarlService.bounded(values.title(), 2000, "vendor title");
      CarlService.bounded(values.category(), 200, "vendor category");
      optional(values.contact(), 2000, "contact");
      if(values.verified())
      {
         CarlService.bounded(values.contact(), 2000, "verified contact");
      }
      CarlService.bounded(reason, 4000, "correction evidence");
      String digest = digest(Map.of("vendor", vendor, "revision", expectedRevision, "values", values, "reason", reason));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var old = require(c, principal, "carl_vendor_view", vendor);
         if(CarlService.request(c, actor, request, "VENDOR_CORRECTION", digest) != null)
         {
            return null;
         }
         revision(old, expectedRevision);
         CarlService.execute(c, "UPDATE carl_vendor SET category=?,contact=?,contact_verified=? WHERE record_id=?", values.category(), values.contact(), values.verified(), vendor);
         CarlService.execute(c, "UPDATE carl_record SET title=?,revision=revision+1 WHERE id=?", values.title(), vendor);
         correction(c, actor, vendor, old, require(c, principal, "carl_vendor_view", vendor), reason);
         CarlService.complete(c, request, vendor, "COMPLETE", "Attributed local vendor correction; no communication sent");
         return null;
      });
   }



   /** Maintains an existing work item without changing its vendor, visibility or authorized audience. */
   public void correctWork(String principal, UUID request, long work, long expectedRevision, Work values, String reason)
   {
      if(values == null || values.status() == null || !Set.of("FAMILY_RESPONSE", "VENDOR_RESPONSE", "INFORMATION_NEEDED", "COMPLETE").contains(values.status()))
      {
         throw new IllegalArgumentException("Explicit supported work state required");
      }
      CarlService.bounded(values.title(), 2000, "work title");
      CarlService.bounded(reason, 4000, "work correction evidence");
      optional(values.commitmentEvidence(), 4000, "commitment evidence");
      if(values.followUp() != null && (values.followUp().getYear() < 1900 || values.followUp().getYear() > 2200))
      {
         throw new IllegalArgumentException("Bounded follow-up date required");
      }
      String digest = digest(Map.of("work", work, "revision", expectedRevision, "values", values, "reason", reason));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var old = require(c, principal, "carl_work_view", work);
         if(CarlService.request(c, actor, request, "VENDOR_WORK_CORRECTION", digest) != null)
         {
            return null;
         }
         revision(old, expectedRevision);
         if(values.assignee() != null)
         {
            var members = CarlService.rows(c, "SELECT principal FROM carl_member WHERE id=? AND household_id=? AND active", values.assignee(), actor.householdId());
            if(members.size() != 1)
            {
               throw new SecurityException("Work assignee unavailable");
            }
            require(c, members.getFirst().get("principal").toString(), "carl_work_view", work);
         }
         CarlService.execute(c, "UPDATE carl_work_item SET status=?,assigned_member=?,follow_up=?,commitment_evidence=? WHERE record_id=?", values.status(), values.assignee(), values.followUp(), values.commitmentEvidence(), work);
         CarlService.execute(c, "UPDATE carl_record SET title=?,revision=revision+1 WHERE id=?", values.title(), work);
         correction(c, actor, work, old, require(c, principal, "carl_work_view", work), reason);
         CarlService.complete(c, request, work, "COMPLETE", "Attributed work update; completion is a human assertion");
         return null;
      });
   }



   /** Creates an immutable human-edited draft, retaining the exact original audience and grounded sources. */
   public long editDraft(String principal, UUID request, long draft, int expectedVersion, String text, String reason)
   {
      CarlService.bounded(text, 100000, "draft text");
      CarlService.bounded(reason, 4000, "draft edit evidence");
      String digest = digest(Map.of("draft", draft, "version", expectedVersion, "text", text, "reason", reason));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var old = draft(c, principal, draft);
         Long prior = CarlService.request(c, actor, request, "VENDOR_DRAFT_EDIT", digest);
         if(prior != null)
         {
            draft(c, principal, prior);
            return prior;
         }
         if(Boolean.TRUE.equals(old.get("stale")))
         {
            throw new IllegalArgumentException("Draft sources changed; generate a newly grounded draft before editing");
         }
         var lineage = CarlService.rows(c, "SELECT root_id FROM carl_draft_revision WHERE artifact_id=?", draft);
         long root = lineage.isEmpty() ? draft : CarlService.number(lineage.getFirst(), "root_id");
         if(lineage.isEmpty())
         {
            CarlService.execute(c, "INSERT INTO carl_draft_chain(root_id,head_id,version) VALUES(?,?,1)", root, draft);
            CarlService.execute(c, "INSERT INTO carl_draft_revision(artifact_id,root_id,parent_id,version,edited_by,reason,human_edited) SELECT ?,?,NULL,1,owner_id,'Original generated draft',false FROM carl_record WHERE id=?", draft, root, draft);
         }
         var chain = CarlService.rows(c, "SELECT * FROM carl_draft_chain WHERE root_id=? FOR UPDATE", root).getFirst();
         if(CarlService.number(chain, "head_id") != draft || CarlService.number(chain, "version") != expectedVersion || expectedVersion < 1 || expectedVersion >= 1000)
         {
            throw new IllegalArgumentException("Draft version changed; review the current version before editing");
         }
         var audience = CarlService.rows(c, "SELECT m.id,m.principal FROM carl_artifact_audience a JOIN carl_member m ON m.id=a.member_id WHERE a.artifact_id=? ORDER BY m.id", draft);
         for(var member : audience)
         {
            draft(c, member.get("principal").toString(), draft);
         }
         var original = CarlService.rows(c, "SELECT * FROM carl_artifact WHERE record_id=?", draft).getFirst();
         long id = CarlService.record(c, actor, "VENDORS", "PRIVATE", "Vendor draft version " + (expectedVersion + 1), "Human edit: " + reason);
         long householdRevision = CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_household WHERE id=?", actor.householdId()).getFirst(), "revision");
         String limits = "Human-edited text is an attributed assertion, not independent factual verification. Review against retained source references before human use. Draft — not sent; Carl cannot send or make commitments.";
         CarlService.execute(c, "INSERT INTO carl_artifact(record_id,request_id,kind,version,facts,narrative,limitations,narration_state,source_revision,formula_version,intended_recipient,status_label,permission_revision) VALUES(?,?,'VENDOR_DRAFT',?,?,?,?,'NOT_REQUESTED',?,'carl-vendor-draft-v1',?,'Draft — not sent; human edited',?)", id, request, expectedVersion + 1, original.get("facts"), text, limits, householdRevision, original.get("intended_recipient"), actor.permissionRevision());
         CarlService.execute(c, "INSERT INTO carl_artifact_source(artifact_id,source_id,source_revision) SELECT ?,source_id,source_revision FROM carl_artifact_source WHERE artifact_id=?", id, draft);
         for(var member : audience)
         {
            CarlService.execute(c, "INSERT INTO carl_artifact_audience(artifact_id,member_id) VALUES(?,?)", id, member.get("id"));
            CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,?,true)", id, member.get("id"));
         }
         CarlService.execute(c, "INSERT INTO carl_draft_revision(artifact_id,root_id,parent_id,version,edited_by,reason,human_edited) VALUES(?,?,?,?,?,?,true)", id, root, draft, expectedVersion + 1, actor.id(), reason);
         CarlService.execute(c, "UPDATE carl_draft_chain SET head_id=?,version=? WHERE root_id=?", id, expectedVersion + 1, root);
         CarlService.complete(c, request, id, "COMPLETE", "Immutable human-edited draft; not sent");
         return id;
      });
   }



   /** Lists the latest hundred version summaries; fetch text through an authorized single-version copy. */
   public List<Map<String, Object>> history(String principal, long draft)
   {
      return history(principal, draft, 1001, 100);
   }



   /** Pages immutable version metadata without materializing every historical draft body. */
   public List<Map<String, Object>> history(String principal, long draft, int beforeVersion, int limit)
   {
      if(beforeVersion < 1 || beforeVersion > 1001 || limit < 1 || limit > 100)
      {
         throw new IllegalArgumentException("History requires a version cursor and page size 1–100");
      }
      return service.transaction(c ->
      {
         reader(c, principal);
         var current = draft(c, principal, draft, false);
         var lineage = CarlService.rows(c, "SELECT root_id FROM carl_draft_revision WHERE artifact_id=?", draft);
         if(lineage.isEmpty())
         {
            current.keySet().retainAll(Set.of("id", "version", "status_label", "stale", "created_at"));
            return beforeVersion > 1 ? List.of(current) : List.of();
         }
         var versions = CarlService.rows(c, "SELECT artifact_id FROM carl_draft_revision WHERE root_id=? AND version<? ORDER BY version DESC LIMIT ?", lineage.getFirst().get("root_id"), beforeVersion, limit);
         var result = new java.util.ArrayList<Map<String, Object>>();
         for(var version : versions)
         {
            long id = CarlService.number(version, "artifact_id");
            var permitted = draft(c, principal, id, false);
            permitted.keySet().retainAll(Set.of("id", "version", "status_label", "stale", "created_at"));
            permitted.putAll(CarlService.rows(c, "SELECT root_id,parent_id,edited_by,reason,human_edited,created_at AS edited_at FROM carl_draft_revision WHERE artifact_id=?", id).getFirst());
            result.add(permitted);
         }
         return result;
      });
   }



   /** Provides a clearly labeled, inert copy surface for a single saved version. */
   public String copy(String principal, long draft)
   {
      return service.transaction(c ->
      {
         reader(c, principal);
         return copy(c, principal, draft);
      });
   }



   /** Stores an authenticated version-specific plain-text download; identity is never taken from its URL. */
   public UUID export(String principal, UUID request, long draft)
   {
      service.transaction(c ->
      {
         var actor = reader(c, principal);
         if(Boolean.TRUE.equals(draft(c, principal, draft).get("stale")))
         {
            throw new IllegalArgumentException("Draft sources changed; generate a current draft before export");
         }
         String text = copy(c, principal, draft);
         byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
         if(bytes.length > 500000)
         {
            throw new IllegalArgumentException("Draft export exceeds 500000 bytes");
         }
         CarlService.execute(c, "INSERT INTO carl_vendor_draft_export(id,artifact_id,requester_id,permission_revision,content) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING", request, draft, actor.id(), actor.permissionRevision(), bytes);
         var prior = CarlService.rows(c, "SELECT artifact_id,requester_id FROM carl_vendor_draft_export WHERE id=?", request).getFirst();
         if(CarlService.number(prior, "artifact_id") != draft || CarlService.number(prior, "requester_id") != actor.id())
         {
            throw new IllegalArgumentException("Export request conflicts with previous input");
         }
         return null;
      });
      return request;
   }



   /** Rechecks the requesting principal, permission epoch and every source on actual download. */
   public byte[] load(String principal, UUID export)
   {
      return service.transaction(c ->
      {
         var actor = reader(c, principal);
         var rows = CarlService.rows(c, "SELECT * FROM carl_vendor_draft_export WHERE id=? AND requester_id=? AND permission_revision=?", export, actor.id(), actor.permissionRevision());
         if(rows.size() != 1)
         {
            throw new SecurityException("Draft export unavailable");
         }
         if(Boolean.TRUE.equals(draft(c, principal, CarlService.number(rows.getFirst(), "artifact_id")).get("stale")))
         {
            throw new IllegalArgumentException("Export sources changed; review the draft and create a current export");
         }
         NativeReadScope.check(actor);
         return ((byte[]) rows.getFirst().get("content")).clone();
      });
   }



   private static String copy(Connection c, String principal, long id) throws SQLException
   {
      var value = draft(c, principal, id);
      var recipient = CarlService.rows(c, "SELECT intended_recipient FROM carl_artifact WHERE record_id=?", id).getFirst().get("intended_recipient");
      var sources = CarlService.rows(c, "SELECT source_id,source_revision FROM carl_artifact_source WHERE artifact_id=? ORDER BY source_id", id);
      return "Carl AI — Draft — not sent\nVersion: " + value.get("version") + " / record " + id
         + "\nRecipient: " + (recipient == null ? "Not selected/verified — incomplete" : recipient)
         + "\nSource status: " + (Boolean.TRUE.equals(value.get("stale")) ? "STALE — regenerate before use" : "Recorded sources unchanged")
         + "\n" + value.get("status_label") + "\n" + value.get("limitations") + "\n\n"
         + value.get("narrative") + "\n\nSource references: " + CarlService.json(sources) + "\n";
   }



   private static Map<String, Object> draft(Connection c, String principal, long id) throws SQLException
   {
      return draft(c, principal, id, true);
   }



   private static Map<String, Object> draft(Connection c, String principal, long id, boolean body) throws SQLException
   {
      var value = require(c, principal, "carl_artifact_view", id, body ? "*" : "id,version,status_label,stale,created_at,kind");
      if(!"VENDOR_DRAFT".equals(value.get("kind")))
      {
         throw new IllegalArgumentException("Select a saved vendor draft");
      }
      // The artifact view includes current epoch/audience checks; enforce typed source dependencies too.
      for(var source : CarlService.rows(c, "SELECT s.source_id,r.domain FROM carl_artifact_source s JOIN carl_record r ON r.id=s.source_id WHERE artifact_id=?", id))
      {
         long record = CarlService.number(source, "source_id");
         if(!CarlService.rows(c, "SELECT record_id FROM carl_work_item WHERE record_id=?", record).isEmpty())
         {
            require(c, principal, "carl_work_view", record);
         }
         else if(!CarlService.rows(c, "SELECT record_id FROM carl_vendor WHERE record_id=?", record).isEmpty())
         {
            require(c, principal, "carl_vendor_view", record);
         }
         else
         {
            throw new SecurityException("Unsupported draft evidence source");
         }
      }
      return value;
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      return require(c, principal, view, id, "*");
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id, String columns) throws SQLException
   {
      CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT " + columns + " FROM " + view + " WHERE principal=? AND id=?", principal, id);
      if(rows.size() != 1)
      {
         throw new SecurityException("Vendor record unavailable");
      }
      return new LinkedHashMap<>(rows.getFirst());
   }



   private static CarlService.Member reader(Connection c, String principal) throws SQLException
   {
      var first = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", first.householdId());
      var current = CarlService.member(c, principal);
      if(current.householdId() != first.householdId())
      {
         throw new SecurityException("Household access changed");
      }
      return current;
   }



   private static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var first = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", first.householdId());
      var current = CarlService.manager(c, principal, "VENDORS");
      if(current.householdId() != first.householdId())
      {
         throw new SecurityException("Household access changed");
      }
      return current;
   }



   private static void correction(Connection c, CarlService.Member actor, long id, Object before, Object after, String reason) throws SQLException
   {
      CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", id, actor.id(), reason, CarlService.json(before), CarlService.json(after));
      CarlService.bump(c, actor.householdId());
   }



   private static void revision(Map<String, Object> old, long expected)
   {
      if(CarlService.number(old, "revision") != expected)
      {
         throw new IllegalArgumentException("Record changed; review current revision");
      }
   }



   private static String digest(Object value)
   {
      return BillCsv.hash(CarlService.json(value));
   }



   private static void optional(String value, int maximum, String label)
   {
      if(value != null && value.length() > maximum)
      {
         throw new IllegalArgumentException(label + " exceeds supported length");
      }
   }
}
