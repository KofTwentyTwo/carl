/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Human reviewed classification of one bounded selection, never inferred source category rules. */
public final class BulkTransactionClassification
{
   /** A source-pinned human-readable preview is not authorization to modify records. */
   public record Preview(int count, String token, String summary, List<Map<String, Object>> records)
   {
      /** Keeps the actual preview rows immutable across the user review pause. */
      public Preview
      {
         records = records.stream().map(row -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
      }
   }
   private final CarlService service;
   /** Reuses Carl's authoritative classifier and transaction boundary. */
   public BulkTransactionClassification(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** Validates every explicitly selected ID before QQQ can remove unreadable rows from its load. */
   public List<Long> selection(String principal, List<Long> selected)
   {
      var ids = validate(UUID.randomUUID(), selected, "UNCLASSIFIED", "Selection validation", "Validate actual selected IDs");
      return service.transaction(c ->
      {
         CarlService.manager(c, principal, "FINANCE");
         records(c, principal, ids, false, true);
         return ids;
      });
   }



   /** Reads current selected records and explicit target values without changing any record. */
   public Preview preview(String principal, UUID request, List<Long> selected, String classification, String category, String reason)
   {
      var ids = validate(request, selected, classification, category, reason);
      return service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         var records = records(c, principal, ids, false, true);
         String token = token(request, ids, classification, category, reason, actor.permissionRevision(), records);
         var summary = new StringBuilder("Preview only — no classification has been applied.\nSelected transactions: " + ids.size() + "\nTarget classification: " + classification + "\nTarget category: " + category + "\nReason: " + reason + "\n\nChanges:\n");
         for(var row : records)
         {
            summary.append("Record ").append(row.get("id")).append(": ").append(row.get("classification")).append(" / ").append(row.get("category")).append(" → ").append(classification).append(" / ").append(category).append('\n');
         }
         summary.append("\nAll selected corrections commit together with your attribution. Original imported source categories, notes and evidence stay unchanged. Account identities, liquidity and ownership still require their separate review. No money moves and no available-cash claim is made.");
         return new Preview(ids.size(), token, summary.toString(), records);
      });
   }



   /** Rechecks all authoritative guards; retries of a completed exact request create no extra corrections. */
   public int apply(String principal, UUID request, List<Long> selected, String classification, String category, String reason, String previewToken, boolean confirmed)
   {
      var ids = validate(request, selected, classification, category, reason);
      if(!confirmed || previewToken == null || !previewToken.matches("[a-f0-9]{64}"))
      {
         throw new IllegalArgumentException("Explicit preview confirmation required");
      }
      String digest = hash(Map.of("ids", ids, "classification", classification, "category", category, "reason", reason, "preview", previewToken));
      return service.transaction(c ->
      {
         var initial = CarlService.manager(c, principal, "FINANCE");
         CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
         var actor = CarlService.manager(c, principal, "FINANCE");
         Long prior = CarlService.request(c, actor, request, "BULK_TRANSACTION_CLASSIFICATION", digest);
         if(prior != null)
         {
            records(c, principal, ids, false, false);
            return Math.toIntExact(prior);
         }
         var records = records(c, principal, ids, true, true);
         String current = token(request, ids, classification, category, reason, actor.permissionRevision(), records);
         if(!java.security.MessageDigest.isEqual(current.getBytes(java.nio.charset.StandardCharsets.UTF_8), previewToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
         {
            throw new IllegalArgumentException("Selected records or permissions changed; review a fresh preview before applying");
         }
         var finance = new FinancialRecords(service);
         for(long id : ids)
         {
            finance.classify(c, principal, id, classification, category, reason);
         }
         CarlService.complete(c, request, ids.size(), "COMPLETE", "Human-confirmed selected transaction classifications committed atomically; source evidence preserved");
         return ids.size();
      });
   }



   private static List<Long> validate(UUID request, List<Long> selected, String classification, String category, String reason)
   {
      java.util.Objects.requireNonNull(request, "Logical request ID required");
      if(selected == null || selected.isEmpty() || selected.size() > 100 || selected.stream().anyMatch(id -> id == null || id <= 0) || selected.stream().distinct().count() != selected.size())
      {
         throw new IllegalArgumentException("Select one to 100 distinct transaction records");
      }
      if(!Set.of("INCOME", "EXPENSE", "UNCLASSIFIED").contains(classification))
      {
         throw new IllegalArgumentException("Bulk classification supports INCOME, EXPENSE or UNCLASSIFIED; reviewed transfer pairing and debt/capital treatment use separate processes");
      }
      CarlService.bounded(category, 200, "category");
      CarlService.bounded(reason, 2000, "classification reason");
      return selected.stream().sorted().toList();
   }



   private static List<Map<String, Object>> records(Connection c, String principal, List<Long> ids, boolean lock, boolean rejectTransfers) throws SQLException
   {
      var records = new ArrayList<Map<String, Object>>();
      for(long id : ids)
      {
         var rows = CarlService.rows(c, "SELECT v.*,r.revision AS record_revision,ar.revision AS account_revision,coalesce((SELECT max(s.version) FROM carl_transaction_source s WHERE s.transaction_id=v.id),0) AS source_version FROM carl_transaction_view v JOIN carl_record r ON r.id=v.id JOIN carl_record ar ON ar.id=v.account_id JOIN carl_account_view a ON a.id=v.account_id AND a.principal=v.principal WHERE v.principal=? AND v.id=?", principal, id);
         if(rows.size() != 1)
         {
            throw new SecurityException("Selected transaction or account unavailable");
         }
         var row = new LinkedHashMap<>(rows.getFirst());
         row.remove("principal");
         records.add(row);
      }
      if(lock)
      {
         for(long account : records.stream().mapToLong(row -> CarlService.number(row, "account_id")).distinct().sorted().toArray())
         {
            CarlService.rows(c, "SELECT record_id FROM carl_account WHERE record_id=? FOR UPDATE", account);
            CarlService.rows(c, "SELECT id FROM carl_record WHERE id=? FOR UPDATE", account);
         }
         for(long id : ids)
         {
            CarlService.rows(c, "SELECT record_id FROM carl_transaction WHERE record_id=? FOR UPDATE", id);
            CarlService.rows(c, "SELECT id FROM carl_record WHERE id=? FOR UPDATE", id);
         }
         return records(c, principal, ids, false, rejectTransfers);
      }
      if(rejectTransfers && records.stream().anyMatch(row -> row.get("transfer_key") != null))
      {
         throw new IllegalArgumentException("Unpair and review both transfer legs before classifying selected records");
      }
      return List.copyOf(records);
   }



   private static String hash(Map<String, Object> fields)
   {
      return BillCsv.hash(CarlService.json(new java.util.TreeMap<>(fields)));
   }



   private static String token(UUID request, List<Long> ids, String classification, String category, String reason, long epoch, List<Map<String, Object>> records)
   {
      return hash(Map.of("request", request, "ids", ids, "classification", classification, "category", category, "reason", reason, "permissionRevision", epoch, "records", records));
   }
}
