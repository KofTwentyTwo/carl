/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Financial facts, source revisions and explicit classification; no external account credentials. */
public final class FinancialRecords
{
   private final CarlService db;
   /*******************************************************************************
    * Shares Carl authorization and transaction boundaries for financial capabilities.
    ******************************************************************************/
   public FinancialRecords(CarlService db)
   {
      this.db = db;
   }



   /*******************************************************************************
    * Requires explicit account type, currency, ownership share and evidence.
    ******************************************************************************/
   public long createAccount(String principal, String title, String kind, String currency, boolean liquid, BigDecimal ownershipShare, String visibility, String evidence)
   {
      Currency.getInstance(currency);
      if(!Set.of("CASH", "CREDIT_CARD", "LOAN", "INVESTMENT", "OTHER_ASSET").contains(kind)
         || ownershipShare == null || ownershipShare.signum() <= 0 || ownershipShare.compareTo(BigDecimal.ONE) > 0)
      {
         throw new IllegalArgumentException("Explicit account kind and ownership share required");
      }
      return db.transaction(c ->
      {
         var member = mutationMember(c, principal);
         long id = CarlService.record(c, member, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_account(record_id,kind,currency,liquid,ownership_share) VALUES(?,?,?,?,?)", id, kind, currency, liquid, ownershipShare);
         CarlService.bump(c, member.householdId());
         return id;
      });
   }



   /*******************************************************************************
    * Records a human qualification of economic identity and ownership without rewriting source evidence.
    ******************************************************************************/
   public void reviewAccount(String principal, long accountId, String kind, boolean liquid, BigDecimal ownershipShare, String reason)
   {
      if(!Set.of("CASH", "CREDIT_CARD", "LOAN", "INVESTMENT", "OTHER_ASSET").contains(kind)
         || ownershipShare == null || ownershipShare.signum() <= 0 || ownershipShare.compareTo(BigDecimal.ONE) > 0
         || ownershipShare.stripTrailingZeros().scale() > 10)
      {
         throw new IllegalArgumentException("Explicit account kind and ownership share required");
      }
      CarlService.bounded(reason, 2000, "economic identity and ownership review evidence");
      db.transaction(c ->
      {
         var member = mutationMember(c, principal);
         CarlService.requireRecord(c, principal, accountId);
         var before = CarlService.rows(c, "SELECT * FROM carl_account WHERE record_id=? FOR UPDATE", accountId);
         if(before.size() != 1)
         {
            throw new IllegalArgumentException("Financial account required");
         }
         CarlService.execute(c, "UPDATE carl_account SET kind=?,liquid=?,ownership_share=?,review_state='CONFIRMED' WHERE record_id=?", kind, liquid, ownershipShare, accountId);
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", accountId);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", accountId, member.id(), reason,
            CarlService.json(before), CarlService.json(CarlService.rows(c, "SELECT * FROM carl_account WHERE record_id=?", accountId)));
         CarlService.bump(c, member.householdId());
         return null;
      });
   }



   /*******************************************************************************
    * Identifies missing account mappings and changed source IDs before import.
    ******************************************************************************/
   public Map<String, Object> previewTransactions(String principal, String csv, Map<String, Long> mapping)
   {
      var preview = MonarchCsv.transactions(csv);
      return db.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         var changes = new ArrayList<String>();
         var missing = new java.util.TreeSet<String>();
         for(var row : preview.rows())
         {
            if(!mapping.containsKey(row.account()))
            {
               missing.add(row.account());
               continue;
            }
            account(c, principal, mapping.get(row.account()));
            var previous = latest(c, member.householdId(), row.id());
            if(!previous.isEmpty())
            {
               long existingId = CarlService.number(previous.getFirst(), "transaction_id");
               requireTransaction(c, principal, existingId);
               if(!previous.getFirst().get("payload_digest").equals(row.identity()))
               {
                  changes.add(row.id());
               }
            }
         }
         return Map.of("preview", preview, "unmappedAccounts", missing, "changedSourceIds", changes, "changesRequireConfirmation", !changes.isEmpty(),
            "semantics", "No absent row is deleted; source Owner and Reviewed fields never change permissions; classifications remain explicit.");
      });
   }



   /*******************************************************************************
    * Preserves immutable source revisions and never deletes rows absent from an export.
    ******************************************************************************/
   public long importTransactions(String principal, UUID requestId, String csv, Map<String, Long> mapping, boolean acceptSourceRevisions)
   {
      var preview = MonarchCsv.transactions(csv);
      if(!preview.valid())
      {
         throw new IllegalArgumentException("Atomic import rejected: " + preview.errors());
      }
      return db.transaction(c ->
      {
         var member = mutationMember(c, principal);
         NativeMutationReceipt.beforeMonarchTransaction(c, member, requestId);
         String digest = BillCsv.hash(preview.contentIdentity() + ":" + mappingIdentity(mapping, preview.rows().stream().map(row -> row.account()).toList()));
         Long prior = CarlService.request(c, member, requestId, "MONARCH_TRANSACTIONS", digest);
         if(prior != null)
         {
            NativeMutationReceipt.after(c, CarlService.member(c, principal));
            return prior;
         }
         long batch = batch(c, member, requestId, "Monarch Transactions", preview.contentIdentity(), preview.rows().size());
         boolean changed = false;
         for(var row : preview.rows())
         {
            Long accountId = mapping.get(row.account());
            if(accountId == null)
            {
               throw new IllegalArgumentException("Every source account label requires an explicit local account mapping");
            }
            var account = account(c, principal, accountId);
            var previous = latest(c, member.householdId(), row.id());
            int version = 1;
            long transactionId;
            if(!previous.isEmpty())
            {
               var old = previous.getFirst();
               transactionId = CarlService.number(old, "transaction_id");
               requireTransaction(c, principal, transactionId);
               if(old.get("payload_digest").equals(row.identity()))
               {
                  var transaction = CarlService.rows(c, "SELECT account_id FROM carl_transaction WHERE record_id=?", transactionId).getFirst();
                  if(CarlService.number(transaction, "account_id") != accountId)
                  {
                     throw new IllegalArgumentException("Existing transaction has different account mapping; explicit reassignment process required");
                  }
                  continue;
               }
               if(!acceptSourceRevisions)
               {
                  throw new IllegalArgumentException("Changed source Id requires preview and explicit revision confirmation");
               }
               var locked = CarlService.rows(c, "SELECT transfer_key FROM carl_transaction WHERE record_id=? FOR UPDATE", transactionId).getFirst();
               if(locked.get("transfer_key") != null)
               {
                  throw new IllegalArgumentException("Unpair and review the transfer before accepting source revisions");
               }
               version = ((Number) old.get("version")).intValue() + 1;
               CarlService.execute(c, "UPDATE carl_transaction SET account_id=?,effective_date=?,amount=?,currency=?,import_id=? WHERE record_id=?", accountId, row.date(), row.amount(), account.get("currency"), batch, transactionId);
               CarlService.execute(c, "UPDATE carl_record SET revision=revision+1,title=? WHERE id=?", row.merchant(), transactionId);
            }
            else
            {
               transactionId = CarlService.record(c, member, "FINANCE", (String) account.get("visibility"), row.merchant(), "Monarch source Id " + row.id() + "; account mapping explicitly selected by importing member");
               CarlService.execute(c, "INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,source_id,classification,category,import_id) VALUES(?,?,?,?,?,?,'UNCLASSIFIED',?,?)",
                  transactionId, accountId, row.date(), row.amount(), account.get("currency"), row.id(), row.category(), batch);
            }
            changed = true;
            CarlService.execute(c, "INSERT INTO carl_transaction_source(household_id,external_id,transaction_id,version,payload_digest,import_id,logical_row,account_label,merchant,category,original_statement,notes,tags,owner_label,reviewed,amount,effective_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
               member.householdId(), row.id(), transactionId, version, row.identity(), batch, row.row(), row.account(), row.merchant(), row.category(), row.statement(), row.notes(), row.tags(), row.owner(), row.reviewed(), row.amount(), row.date());
         }
         if(changed)
         {
            CarlService.bump(c, member.householdId());
         }
         CarlService.complete(c, requestId, batch, "COMPLETE", "Source revisions preserved; absent transactions unchanged");
         NativeMutationReceipt.after(c, CarlService.member(c, principal));
         return batch;
      });
   }



   /*******************************************************************************
    * Retains dated observations; conflicting source values require explicit review.
    ******************************************************************************/
   public long importBalances(String principal, UUID requestId, String csv, Map<String, Long> mapping, boolean acceptChangedObservation)
   {
      var preview = MonarchCsv.balances(csv);
      if(!preview.valid())
      {
         throw new IllegalArgumentException("Ambiguous balance import rejected: " + preview.errors());
      }
      return db.transaction(c ->
      {
         var member = mutationMember(c, principal);
         Long prior = CarlService.request(c, member, requestId, "MONARCH_BALANCES", BillCsv.hash(preview.contentIdentity() + ":" + mappingIdentity(mapping, preview.rows().stream().map(row -> row.account()).toList())));
         if(prior != null)
         {
            return prior;
         }
         long batch = batch(c, member, requestId, "Monarch Balances", preview.contentIdentity(), preview.rows().size());
         var changedAccounts = new java.util.LinkedHashSet<Long>();
         for(var row : preview.rows())
         {
            Long accountId = mapping.get(row.account());
            if(accountId == null)
            {
               throw new IllegalArgumentException("Explicit local account mapping required");
            }
            account(c, principal, accountId);
            var old = CarlService.rows(c, "SELECT amount FROM carl_balance WHERE account_id=? AND as_of=? AND basis='CURRENT'", accountId, row.date());
            if(!old.isEmpty() && ((BigDecimal) old.getFirst().get("amount")).compareTo(row.balance()) != 0 && !acceptChangedObservation)
            {
               throw new IllegalArgumentException("Changed daily balance needs explicit confirmation; original observation remains preserved");
            }
            CarlService.execute(c, "INSERT INTO carl_balance_source(account_id,as_of,amount,import_id,logical_row,account_label) VALUES(?,?,?,?,?,?) ON CONFLICT(account_id,as_of,import_id) DO NOTHING", accountId, row.date(), row.balance(), batch, row.row(), row.account());
            if(!old.isEmpty() && ((BigDecimal) old.getFirst().get("amount")).compareTo(row.balance()) == 0)
            {
               continue;
            }
            changedAccounts.add(accountId);
            CarlService.execute(c, "INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(?,?,?,'CURRENT',?) ON CONFLICT(account_id,as_of,basis) DO UPDATE SET amount=EXCLUDED.amount,evidence=EXCLUDED.evidence",
               accountId, row.date(), row.balance(), "Monarch dated balance, batch " + batch + ", logical row " + row.row() + "; currency/account explicitly mapped; observation time not supplied");
         }
         if(!changedAccounts.isEmpty())
         {
            for(long accountId : changedAccounts)
            {
               CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", accountId);
            }
            CarlService.bump(c, member.householdId());
         }
         CarlService.complete(c, requestId, batch, "COMPLETE", "Dated observations imported; no unavailable intraday timestamp inferred");
         return batch;
      });
   }



   /*******************************************************************************
    * Records a human classification with before/after attribution.
    ******************************************************************************/
   public void classify(String principal, long transactionId, String classification, String category, String reason)
   {
      db.transaction(c ->
      {
         classify(c, principal, transactionId, classification, category, reason);
         return null;
      });
   }



   /** Uses the caller's existing transaction so bounded bulk corrections remain atomic. */
   void classify(Connection c, String principal, long transactionId, String classification, String category, String reason) throws SQLException
   {
      if(!Set.of("INCOME", "EXPENSE", "DEBT_PRINCIPAL", "DEBT_INTEREST", "CAPITAL", "UNCLASSIFIED").contains(classification))
      {
         throw new IllegalArgumentException("Unsupported classification");
      }
      CarlService.bounded(reason, 2000, "classification reason");
      CarlService.bounded(category, 200, "category");
      var member = mutationMember(c, principal);
      CarlService.requireRecord(c, principal, transactionId);
      var before = CarlService.rows(c, "SELECT * FROM carl_transaction WHERE record_id=? FOR UPDATE", transactionId);
      if(before.size() != 1)
      {
         throw new SecurityException("Transaction unavailable");
      }
      account(c, principal, CarlService.number(before.getFirst(), "account_id"));
      if(before.getFirst().get("transfer_key") != null)
      {
         throw new IllegalArgumentException("Unpair and review both transfer legs before reclassification");
      }
      CarlService.execute(c, "UPDATE carl_transaction SET classification=?,category=? WHERE record_id=?", classification, category, transactionId);
      CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", transactionId, member.id(), reason, CarlService.json(before), CarlService.json(Map.of("classification", classification, "category", category)));
      CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", transactionId);
      CarlService.bump(c, member.householdId());
   }



   /*******************************************************************************
    * Pairs explicit equal opposite legs without inferring duplicates from amount and date alone.
    ******************************************************************************/
   public void pairTransfer(String principal, long outgoing, long incoming, String reason)
   {
      if(outgoing == incoming)
      {
         throw new IllegalArgumentException("Two distinct transfer legs required");
      }
      CarlService.bounded(reason, 2000, "transfer review reason");
      db.transaction(c ->
      {
         var member = mutationMember(c, principal);
         CarlService.requireRecord(c, principal, outgoing);
         CarlService.requireRecord(c, principal, incoming);
         CarlService.rows(c, "SELECT record_id FROM carl_transaction WHERE record_id IN (?,?) ORDER BY record_id FOR UPDATE", outgoing, incoming);
         var a = CarlService.rows(c, "SELECT * FROM carl_transaction WHERE record_id=?", outgoing).getFirst();
         var b = CarlService.rows(c, "SELECT * FROM carl_transaction WHERE record_id=?", incoming).getFirst();
         account(c, principal, CarlService.number(a, "account_id"));
         account(c, principal, CarlService.number(b, "account_id"));
         if(a.get("transfer_key") != null || b.get("transfer_key") != null)
         {
            throw new IllegalArgumentException("Already paired transfers must be explicitly unpaired before a new match");
         }
         if(!a.get("currency").equals(b.get("currency")) || ((BigDecimal) a.get("amount")).add((BigDecimal) b.get("amount")).signum() != 0
            || ((BigDecimal) a.get("amount")).signum() >= 0 || CarlService.number(a, "account_id") == CarlService.number(b, "account_id"))
         {
            throw new IllegalArgumentException("Transfer legs need distinct accounts, equal opposite amounts and one currency");
         }
         String key = UUID.randomUUID().toString();
         for(long id : List.of(outgoing, incoming))
         {
            CarlService.execute(c, "UPDATE carl_transaction SET classification='TRANSFER',transfer_key=? WHERE record_id=?", key, id);
            CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
            CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", id, member.id(), reason, CarlService.json(id == outgoing ? a : b), "Explicitly paired internal transfer " + key);
         }
         CarlService.bump(c, member.householdId());
         return null;
      });
   }



   /** Removes a reviewed pair atomically; both legs become unclassified and retain attribution. */
   public void unpairTransfer(String principal, long transactionId, String reason)
   {
      CarlService.bounded(reason, 2000, "transfer review reason");
      db.transaction(c ->
      {
         var member = mutationMember(c, principal);
         requireTransaction(c, principal, transactionId);
         var selected = CarlService.rows(c, "SELECT transfer_key FROM carl_transaction WHERE record_id=?", transactionId).getFirst();
         Object key = selected.get("transfer_key");
         if(key == null)
         {
            throw new IllegalArgumentException("Selected transaction is not paired");
         }
         var legs = CarlService.rows(c, "SELECT * FROM carl_transaction WHERE transfer_key=? ORDER BY record_id FOR UPDATE", key);
         if(legs.size() != 2 || legs.stream().noneMatch(row -> CarlService.number(row, "record_id") == transactionId))
         {
            throw new IllegalArgumentException("Transfer changed or is inconsistent; review required");
         }
         for(var leg : legs)
         {
            long id = CarlService.number(leg, "record_id");
            requireTransaction(c, principal, id);
            CarlService.execute(c, "UPDATE carl_transaction SET classification='UNCLASSIFIED',transfer_key=NULL WHERE record_id=?", id);
            CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
            CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", id, member.id(), reason, CarlService.json(leg), "Transfer unpaired; classification requires review");
         }
         CarlService.bump(c, member.householdId());
         return null;
      });
   }



   /*******************************************************************************
    * Separates signed scoped balances, liquidity and classified flows with coverage limitations.
    ******************************************************************************/
   public Map<String, Object> overview(CarlService.Scope scope, LocalDate from, LocalDate through)
   {
      var accounts = db.view(scope, "accounts");
      if(from == null || through == null || through.isBefore(from) || through.isAfter(from.plusYears(2)))
      {
         throw new IllegalArgumentException("Choose a reporting interval of at most two years");
      }
      var activity = db.transaction(c ->
      {
         long household = CarlService.member(c, scope.principal()).householdId();
         StringBuilder predicate = new StringBuilder("t.principal=? AND t.effective_date>=? AND t.effective_date<=?");
         var parameters = new ArrayList<Object>(List.of(scope.principal(), from, through));
         for(String recipient : scope.audience())
         {
            if(CarlService.member(c, recipient).householdId() != household)
            {
               throw new SecurityException("Audience unavailable");
            }
            predicate.append(" AND EXISTS(SELECT 1 FROM carl_transaction_view permitted WHERE permitted.id=t.id AND permitted.principal=?)");
            parameters.add(recipient);
         }
         var totals = CarlService.rows(c, "SELECT t.currency,t.classification,ac.review_state,count(*) AS records,sum(t.amount) AS total FROM carl_transaction_view t JOIN carl_account_view ac ON ac.id=t.account_id AND ac.principal=t.principal WHERE " + predicate + " GROUP BY t.currency,t.classification,ac.review_state", parameters.toArray());
         var sample = CarlService.rows(c, "SELECT t.* FROM carl_transaction_view t WHERE " + predicate + " ORDER BY effective_date DESC,id DESC LIMIT 50", parameters.toArray());
         sample.forEach(row -> row.remove("principal"));
         return Map.of("totals", totals, "sample", sample);
      });
      var transactions = activity.get("sample");
      var balances = new LinkedHashMap<String, BigDecimal>();
      var liquid = new LinkedHashMap<String, BigDecimal>();
      var flows = new LinkedHashMap<String, BigDecimal>();
      var gaps = new ArrayList<String>();
      for(var account : accounts)
      {
         if(!BalanceSheets.reviewedAccount(account))
         {
            gaps.add("Account " + account.get("id") + " needs account review; observed balances are excluded from attributed balances and liquidity until kind, ownership and liquidity are confirmed.");
            continue;
         }
         if(account.get("balance") == null)
         {
            gaps.add("Missing balance for account " + account.get("id"));
            continue;
         }
         BigDecimal owned = ((BigDecimal) account.get("balance")).multiply((BigDecimal) account.get("ownership_share"));
         balances.merge(account.get("currency").toString(), owned, BigDecimal::add);
         if(Boolean.TRUE.equals(account.get("liquid")))
         {
            liquid.merge(account.get("currency").toString(), owned, BigDecimal::add);
         }
      }
      long transactionCount = 0;
      for(var total : activity.get("totals"))
      {
         long count = CarlService.number(total, "records");
         transactionCount += count;
         if(!"CONFIRMED".equals(total.get("review_state")))
         {
            gaps.add(count + " " + total.get("currency") + " source transactions need account review; excluded from qualified classified flows.");
            continue;
         }
         if(total.get("classification").equals("UNCLASSIFIED"))
         {
            gaps.add(count + " unclassified " + total.get("currency") + " transactions; signed amounts are not assumed income or spending");
         }
         else if(!total.get("classification").equals("TRANSFER"))
         {
            flows.put(total.get("currency") + ":" + total.get("classification"), (BigDecimal) total.get("total"));
         }
      }
      if(transactionCount > transactions.size())
      {
         gaps.add("Showing the latest " + transactions.size() + " of " + transactionCount + " authorized transactions; qualified classified totals include reviewed account records in the interval");
      }
      return Map.of("scope", "Authorized accounts only; balances are signed assets/liabilities, partial coverage must not imply complete household net worth",
         "signedBalancesByCurrency", balances, "liquidBalancesByCurrency", liquid, "classifiedFlows", flows, "gaps", gaps, "accounts", accounts, "transactions", transactions, "transactionCount", transactionCount);
   }



   /*******************************************************************************
    * Exposes unexplained differences between two dated balances and known intervening activity.
    ******************************************************************************/
   public Map<String, Object> reconcile(String principal, long accountId, LocalDate openingDate, LocalDate closingDate)
   {
      if(!closingDate.isAfter(openingDate))
      {
         throw new IllegalArgumentException("Closing date must follow opening date");
      }
      return db.transaction(c ->
      {
         var account = account(c, principal, accountId);
         var opening = CarlService.rows(c, "SELECT amount FROM carl_balance WHERE account_id=? AND as_of=? ORDER BY id DESC", accountId, openingDate);
         var closing = CarlService.rows(c, "SELECT amount FROM carl_balance WHERE account_id=? AND as_of=? ORDER BY id DESC", accountId, closingDate);
         if(opening.isEmpty() || closing.isEmpty())
         {
            return Map.of("status", "INCOMPLETE", "reason", "Both dated observations are required");
         }
         BigDecimal activity = (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(amount),0) AS amount FROM carl_transaction_view WHERE principal=? AND account_id=? AND effective_date>? AND effective_date<=?", principal, accountId, openingDate, closingDate).getFirst().get("amount");
         BigDecimal start = (BigDecimal) opening.getFirst().get("amount");
         BigDecimal end = (BigDecimal) closing.getFirst().get("amount");
         return Map.of("currency", account.get("currency"), "opening", start, "activity", activity, "closing", end, "unexplainedDifference", end.subtract(start.add(activity)),
            "limitation", "Activity includes only caller-permitted transactions and may be partial. Daily observation times are unknown; same-day ordering, missing activity and valuation changes require review");
      });
   }



   /*******************************************************************************
    * Financial writes lock household before account and transaction rows. Recheck
    * membership after waiting so serialization cannot retain revoked permissions.
    ******************************************************************************/
   static CarlService.Member mutationMember(Connection c, String principal) throws SQLException
   {
      var initial = CarlService.manager(c, principal, "FINANCE");
      CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
      var current = CarlService.manager(c, principal, "FINANCE");
      if(current.householdId() != initial.householdId())
      {
         throw new SecurityException("Household changed; retry the request");
      }
      return current;
   }



   private static void requireTransaction(Connection c, String principal, long id) throws SQLException
   {
      if(CarlService.rows(c, "SELECT id FROM carl_transaction_view WHERE principal=? AND id=?", principal, id).size() != 1)
      {
         throw new SecurityException("Transaction unavailable");
      }
   }



   private static Map<String, Object> account(Connection c, String principal, long id) throws SQLException
   {
      CarlService.requireRecord(c, principal, id);
      var rows = CarlService.rows(c, "SELECT a.*,r.visibility FROM carl_account a JOIN carl_record r ON r.id=a.record_id WHERE a.record_id=?", id);
      if(rows.size() != 1)
      {
         throw new SecurityException("Account unavailable");
      }
      return rows.getFirst();
   }



   private static List<Map<String, Object>> latest(Connection c, long household, String id) throws SQLException
   {
      return CarlService.rows(c, "SELECT * FROM carl_transaction_source WHERE household_id=? AND external_id=? ORDER BY version DESC LIMIT 1", household, id);
   }



   private static String mappingIdentity(Map<String, Long> mapping, List<String> labels)
   {
      var used = new java.util.TreeMap<String, Long>();
      labels.forEach(label -> used.put(label, mapping.get(label)));
      return CarlService.json(used);
   }



   private static long batch(Connection c, CarlService.Member member, UUID request, String name, String digest, int count) throws SQLException
   {
      var prior = CarlService.rows(c, "SELECT id FROM carl_import WHERE household_id=? AND member_id=? AND source_name=? AND content_digest=?", member.householdId(), member.id(), name, digest);
      if(!prior.isEmpty())
      {
         return CarlService.number(prior.getFirst(), "id");
      }
      return CarlService.insert(c, "INSERT INTO carl_import(household_id,member_id,source_name,content_digest,request_id,row_count) VALUES(?,?,?,?,?,?) RETURNING id", member.householdId(), member.id(), name, digest, request, count);
   }
}
