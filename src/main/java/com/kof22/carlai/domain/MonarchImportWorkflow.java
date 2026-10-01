/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/** Durable human preview/apply workflow. Source labels are mappings, never identities. */
public final class MonarchImportWorkflow
{
   private final CarlService db;
   private final FinancialRecords finance;
   /*******************************************************************************
    * Keeps uploaded source evidence and human review state inside Carl.
    ******************************************************************************/
   public MonarchImportWorkflow(CarlService db)
   {
      this.db = db;
      finance = new FinancialRecords(db);
   }



   /*******************************************************************************
    * Checks current financial management authority before accepting upload bytes.
    ******************************************************************************/
   public void uploadPermission(String principal)
   {
      db.transaction(c -> CarlService.manager(c, principal, "FINANCE"));
   }



   /*******************************************************************************
    * Binds bounded uploaded bytes to the authenticated member, not the filename.
    ******************************************************************************/
   public void storeUpload(String principal, String reference, byte[] contents)
   {
      if(contents.length > 20_000_000 || reference == null || reference.length() > 1000)
      {
         throw new IllegalArgumentException("Upload exceeds supported bound");
      }
      db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         CarlService.execute(c, "INSERT INTO carl_upload(reference,member_id,contents) VALUES(?,?,?)", reference, member.id(), contents);
         return null;
      });
   }



   /*******************************************************************************
    * Rechecks current financial authority and upload ownership before reading source bytes.
    ******************************************************************************/
   public byte[] upload(String principal, String reference)
   {
      return db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         var rows = CarlService.rows(c, "SELECT contents FROM carl_upload WHERE reference=? AND member_id=?", reference, member.id());
         if(rows.size() != 1)
         {
            throw new SecurityException("Upload unavailable");
         }
         return (byte[]) rows.getFirst().get("contents");
      });
   }



   /*******************************************************************************
    * Saves an explicit export-label mapping to an accessible local financial account.
    ******************************************************************************/
   public void mapAccount(String principal, String sourceLabel, long accountId)
   {
      CarlService.bounded(sourceLabel, 1000, "source account label");
      db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         CarlService.requireRecord(c, principal, accountId);
         if(CarlService.rows(c, "SELECT record_id FROM carl_account WHERE record_id=?", accountId).isEmpty())
         {
            throw new IllegalArgumentException("Financial account required");
         }
         CarlService.execute(c, "INSERT INTO carl_monarch_mapping(member_id,source_label,account_id) VALUES(?,?,?) ON CONFLICT(member_id,source_label) DO UPDATE SET account_id=EXCLUDED.account_id", member.id(), sourceLabel, accountId);
         return null;
      });
   }



   /*******************************************************************************
    * Validates bounded source input before any domain records are changed.
    ******************************************************************************/
   public UUID preview(String principal, List<String> references)
   {
      if(references.isEmpty() || references.size() > 2)
      {
         throw new IllegalArgumentException("Select one or both Settings exports");
      }
      String transactions = null;
      String balances = null;
      for(String reference : references)
      {
         String csv = decode(upload(principal, reference));
         var rows = BillCsv.parse(csv);
         if(rows.isEmpty())
         {
            throw new IllegalArgumentException("Empty file");
         }
         int columns = rows.getFirst().size();
         if(columns == 11 && transactions == null)
         {
            MonarchCsv.transactions(csv);
            transactions = reference;
         }
         else if(columns == 3 && balances == null)
         {
            MonarchCsv.balances(csv);
            balances = reference;
         }
         else
         {
            throw new IllegalArgumentException("Choose at most one transaction export and one balance export");
         }
      }
      UUID id = UUID.randomUUID();
      String transactionRef = transactions;
      String balanceRef = balances;
      db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         CarlService.execute(c, "INSERT INTO carl_import_review(id,member_id,transaction_reference,balance_reference,status) VALUES(?,?,?,?,'PREVIEW')", id, member.id(), transactionRef, balanceRef);
         return null;
      });
      return id;
   }



   /*******************************************************************************
    * Shows counts, mapping gaps and source conflicts without changing domain facts.
    ******************************************************************************/
   public String describe(String principal, UUID reviewId)
   {
      var review = review(principal, reviewId);
      var mapping = mappings(principal);
      var text = new StringBuilder();
      if(review.get("transaction_reference") != null)
      {
         var preview = finance.previewTransactions(principal, decode(upload(principal, review.get("transaction_reference").toString())), mapping);
         @SuppressWarnings("unchecked")
         var parsed = (MonarchCsv.Preview<MonarchCsv.TransactionRow>) preview.get("preview");
         text.append("Transactions: ").append(parsed.rows().size()).append(" source rows; errors: ").append(parsed.errors()).append("\n")
            .append("Unmapped account labels: ").append(preview.get("unmappedAccounts")).append("\nChanged source IDs: ").append(preview.get("changedSourceIds")).append("\n");
      }
      if(review.get("balance_reference") != null)
      {
         var balances = MonarchCsv.balances(resolvedBalances(principal, reviewId, decode(upload(principal, review.get("balance_reference").toString()))));
         text.append("Balances: ").append(balances.rows().size()).append(" observations; errors: ").append(balances.errors()).append("\n")
            .append("Unmapped balance account labels: ").append(balances.rows().stream().map(MonarchCsv.BalanceRow::account).distinct().filter(label -> !mapping.containsKey(label)).toList()).append("\n");
      }
      return text.append("Map accounts using Map Monarch Account. Resolve conflicting logical balance rows explicitly. Source changes require confirmation. Missing rows never delete stored facts.").toString();
   }



   /*******************************************************************************
    * Preserves a human-selected conflicting observation and the reason for that selection.
    ******************************************************************************/
   public void resolveBalance(String principal, UUID reviewId, int selectedRow, String reason)
   {
      CarlService.bounded(reason, 2000, "resolution reason");
      var review = review(principal, reviewId);
      if(review.get("balance_reference") == null)
      {
         throw new IllegalArgumentException("No balance file");
      }
      var records = BillCsv.parse(decode(upload(principal, review.get("balance_reference").toString())));
      if(selectedRow < 2 || selectedRow > records.size())
      {
         throw new IllegalArgumentException("Choose a logical data row shown by preview");
      }
      var row = records.get(selectedRow - 1);
      if(row.size() != 3 || !MonarchCsv.balances("Date,Balance,Account\n" + csvRow(row)).valid())
      {
         throw new IllegalArgumentException("Malformed selected row");
      }
      db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         CarlService.execute(c, "INSERT INTO carl_balance_resolution(review_id,source_label,observation_date,selected_logical_row,member_id,reason) VALUES(?,?,?,?,?,?) ON CONFLICT(review_id,source_label,observation_date) DO UPDATE SET selected_logical_row=EXCLUDED.selected_logical_row,reason=EXCLUDED.reason",
            reviewId, row.get(2), LocalDate.parse(row.getFirst()), selectedRow, member.id(), reason);
         return null;
      });
   }



   /*******************************************************************************
    * Commits reviewed transaction data while retaining unresolved balance observations for later review.
    ******************************************************************************/
   public String apply(String principal, UUID reviewId, boolean acceptRevisions)
   {
      var review = review(principal, reviewId);
      if(review.get("status").equals("COMPLETE"))
      {
         return review.get("result").toString();
      }
      var mapping = mappings(principal);
      var result = new StringBuilder();
      boolean partial = false;
      if(review.get("transaction_reference") != null)
      {
         long batch = finance.importTransactions(principal, logical(reviewId, "transactions"), decode(upload(principal, review.get("transaction_reference").toString())), mapping, acceptRevisions);
         result.append("Transaction batch ").append(batch).append(" committed. ");
      }
      if(review.get("balance_reference") != null)
      {
         String resolved = resolvedBalances(principal, reviewId, decode(upload(principal, review.get("balance_reference").toString())));
         var preview = MonarchCsv.balances(resolved);
         if(!preview.valid())
         {
            partial = true;
            result.append("Balance observations retained for review: ").append(preview.errors());
         }
         else
         {
            try
            {
               long batch = finance.importBalances(principal, logical(reviewId, "balances"), resolved, mapping, acceptRevisions);
               result.append("Balance batch ").append(batch).append(" committed. Original uploaded rows and explicit resolution evidence retained.");
            }
            catch(IllegalArgumentException reviewRequired)
            {
               partial = true;
               result.append("Balance observations retained for review: ").append(reviewRequired.getMessage());
            }
         }
      }
      String status = partial ? (review.get("transaction_reference") == null ? "NEEDS_REVIEW" : "PARTIAL") : "COMPLETE";
      db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         CarlService.execute(c, "UPDATE carl_import_review SET status=?,result=? WHERE id=? AND member_id=?", status, result.toString(), reviewId, member.id());
         return null;
      });
      return status + ": " + result;
   }



   private String resolvedBalances(String principal, UUID id, String original)
   {
      var choices = db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         return CarlService.rows(c, "SELECT source_label,observation_date,selected_logical_row FROM carl_balance_resolution WHERE review_id=? AND member_id=?", id, member.id());
      });
      var selected = new LinkedHashMap<String, Integer>();
      choices.forEach(row -> selected.put(row.get("source_label") + "\u001f" + row.get("observation_date"), ((Number) row.get("selected_logical_row")).intValue()));
      if(selected.isEmpty())
      {
         return original;
      }
      var records = BillCsv.parse(original);
      var result = new StringBuilder("Date,Balance,Account\n");
      for(int i = 1; i < records.size(); i++)
      {
         var row = records.get(i);
         if(row.size() != 3)
         {
            result.append(csvRow(row));
            continue;
         }
         Integer chosen = selected.get(row.get(2) + "\u001f" + row.getFirst());
         if(chosen == null || chosen == i + 1)
         {
            result.append(csvRow(row));
         }
      }
      return result.toString();
   }



   private Map<String, Object> review(String principal, UUID id)
   {
      return db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         var rows = CarlService.rows(c, "SELECT * FROM carl_import_review WHERE id=? AND member_id=?", id, member.id());
         if(rows.size() != 1)
         {
            throw new SecurityException("Import review unavailable");
         }
         return rows.getFirst();
      });
   }



   private Map<String, Long> mappings(String principal)
   {
      return db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         var result = new LinkedHashMap<String, Long>();
         for(var row : CarlService.rows(c, "SELECT source_label,account_id FROM carl_monarch_mapping WHERE member_id=?", member.id()))
         {
            result.put(row.get("source_label").toString(), CarlService.number(row, "account_id"));
         }
         return result;
      });
   }



   private static String decode(byte[] bytes)
   {
      try
      {
         return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      }
      catch(java.nio.charset.CharacterCodingException invalid)
      {
         throw new IllegalArgumentException("CSV must be UTF-8");
      }
   }



   private static String csvRow(List<String> fields)
   {
      return fields.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(",")) + "\n";
   }



   private static UUID logical(UUID review, String kind)
   {
      return UUID.nameUUIDFromBytes((review + ":" + kind).getBytes(StandardCharsets.UTF_8));
   }
}
