/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Human budgets and transactions share the existing financial records and permissions. */
public final class BudgetRecords
{
   private final CarlService service;
   /** Shares authoritative persistence and identity. */
   public BudgetRecords(CarlService service)
   {
      this.service = service;
   }



   /** Saves human-origin evidence separately from immutable imported source observations. */
   public long manualTransaction(String principal, UUID request, long account, LocalDate date, BigDecimal amount, String classification, String category, String title, String evidence)
   {
      date(date);
      CarlService.bounded(category, 200, "category");
      CarlService.bounded(evidence, 4000, "manual evidence");
      if(!Set.of("INCOME", "EXPENSE", "DEBT_PRINCIPAL", "DEBT_INTEREST", "CAPITAL", "UNCLASSIFIED").contains(classification))
      {
         throw new IllegalArgumentException("Explicit classification required; transfers use paired account legs");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("account", account, "date", date, "amount", amount, "classification", classification, "category", category, "title", title, "evidence", evidence)));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var source = require(c, principal, "carl_account_view", account);
         String currency = source.get("currency").toString();
         BigDecimal exact = money(amount, currency, true);
         Long prior = CarlService.request(c, actor, request, "MANUAL_TRANSACTION", digest);
         if(prior != null)
         {
            require(c, principal, "carl_transaction_view", prior);
            return prior;
         }
         String visibility = CarlService.rows(c, "SELECT visibility FROM carl_record WHERE id=?", account).getFirst().get("visibility").toString();
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,source_id,classification,category) VALUES(?,?,?,?,?,?,?,?)", id, account, date, exact, currency, "manual:" + request, classification, category);
         CarlService.execute(c, "INSERT INTO carl_manual_transaction_origin(transaction_id,request_id,member_id,account_id,effective_date,amount,currency,classification,category,evidence) VALUES(?,?,?,?,?,?,?,?,?,?)", id, request, actor.id(), account, date, exact, currency, classification, category, evidence);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Human-entered transaction; not independently verified");
         return id;
      });
   }



   /** Creates a category allowance without inferring available cash. */
   public long create(String principal, UUID request, String title, String visibility, String category, LocalDate from, LocalDate through, BigDecimal amount, String currency, String evidence)
   {
      date(from);
      date(through);
      if(through.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, through) > 365)
      {
         throw new IllegalArgumentException("Budget interval must contain at most 366 dates");
      }
      CarlService.bounded(category, 200, "category");
      CarlService.bounded(evidence, 4000, "budget evidence");
      BigDecimal exact = money(amount, currency, false);
      String digest = BillCsv.hash(CarlService.json(Map.of("title", title, "visibility", visibility, "category", category, "from", from, "through", through, "amount", exact, "currency", currency, "evidence", evidence)));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         Long prior = CarlService.request(c, actor, request, "CATEGORY_BUDGET", digest);
         if(prior != null)
         {
            require(c, principal, "carl_budget_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_budget(record_id,category,period_start,period_end,amount,currency) VALUES(?,?,?,?,?,?)", id, category, from, through, exact, currency);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Explicit category budget; affordability unqualified");
         return id;
      });
   }



   /** Corrects amount with a revision check and attributed before/after evidence. */
   public void correct(String principal, UUID request, long id, long expectedRevision, BigDecimal amount, String reason)
   {
      CarlService.bounded(reason, 4000, "budget correction reason");
      String digest = BillCsv.hash(CarlService.json(Map.of("id", id, "revision", expectedRevision, "amount", amount, "reason", reason)));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var before = require(c, principal, "carl_budget_view", id);
         BigDecimal exact = money(amount, before.get("currency").toString(), false);
         Long prior = CarlService.request(c, actor, request, "BUDGET_CORRECTION", digest);
         if(prior != null)
         {
            return null;
         }
         if(CarlService.number(before, "revision") != expectedRevision)
         {
            throw new IllegalArgumentException("Budget changed; reload before correcting");
         }
         CarlService.execute(c, "UPDATE carl_budget SET amount=? WHERE record_id=?", exact, id);
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", id, actor.id(), reason, CarlService.json(before), CarlService.json(Map.of("amount", exact)));
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Attributed budget correction");
         return null;
      });
   }



   /** Computes a scoped variance, excluding principal and transfers from spending. */
   public Map<String, Object> variance(CarlService.Scope scope, long id)
   {
      var restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         return calculate(scope, id);
      }
      finally
      {
         restore.run();
      }
   }



   private Map<String, Object> calculate(CarlService.Scope scope, long id)
   {
      return service.transaction(c ->
      {
         var actor = CarlService.member(c, scope.principal());
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", actor.householdId());
         var budget = require(c, scope.principal(), "carl_budget_view", id);
         for(String recipient : scope.audience())
         {
            require(c, recipient, "carl_budget_view", id);
         }
         String currency = budget.get("currency").toString();
         BigDecimal actual = money(BigDecimal.ZERO, currency, false);
         int unknown = 0;
         int excluded = 0;
         var sources = new java.util.ArrayList<Long>();
         var rows = CarlService.rows(c, "SELECT * FROM carl_transaction_view WHERE principal=? AND effective_date BETWEEN ? AND ? AND currency=? AND category=? ORDER BY id LIMIT 5001", scope.principal(), LocalDate.parse(budget.get("period_start").toString()), LocalDate.parse(budget.get("period_end").toString()), currency, budget.get("category"));
         if(rows.size() > 5000)
         {
            throw new IllegalArgumentException("Narrow interval; more than 5000 matching transactions");
         }
         var common = rows.stream().map(row -> CarlService.number(row, "id")).collect(java.util.stream.Collectors.toSet());
         for(String recipient : scope.audience())
         {
            if(recipient.equals(scope.principal()))
            {
               continue;
            }
            var visible = CarlService.rows(c, "SELECT id FROM carl_transaction_view WHERE principal=? AND effective_date BETWEEN ? AND ? AND currency=? AND category=? ORDER BY id LIMIT 5001", recipient, LocalDate.parse(budget.get("period_start").toString()), LocalDate.parse(budget.get("period_end").toString()), currency, budget.get("category"));
            if(visible.size() > 5000)
            {
               throw new IllegalArgumentException("Narrow shared budget interval");
            }
            common.retainAll(visible.stream().map(row -> CarlService.number(row, "id")).collect(java.util.stream.Collectors.toSet()));
         }
         for(var row : rows)
         {
            long transaction = CarlService.number(row, "id");
            if(!common.contains(transaction))
            {
               continue;
            }
            sources.add(transaction);
            String kind = row.get("classification").toString();
            if(Set.of("EXPENSE", "DEBT_INTEREST").contains(kind) && row.get("transfer_key") == null)
            {
               actual = actual.subtract((BigDecimal) row.get("amount"));
            }
            else if(kind.equals("UNCLASSIFIED"))
            {
               unknown++;
            }
            else
            {
               excluded++;
            }
         }
         var result = new LinkedHashMap<String, Object>();
         result.put("budgetId", id);
         result.put("category", budget.get("category"));
         result.put("currency", currency);
         result.put("from", budget.get("period_start"));
         result.put("through", budget.get("period_end"));
         result.put("budget", money((BigDecimal) budget.get("amount"), currency, false));
         result.put("actualSpending", actual.setScale(Currency.getInstance(currency).getDefaultFractionDigits()));
         result.put("remainingBudget", ((BigDecimal) budget.get("amount")).subtract(actual).setScale(Currency.getInstance(currency).getDefaultFractionDigits()));
         result.put("unclassifiedCount", unknown);
         result.put("excludedNonExpenseCount", excluded);
         result.put("sources", sources);
         result.put("status", "PARTIAL");
         result.put("limitations", "Accessible matching category records only; source coverage unverified. Refunds reduce spending. Transfers, debt principal, capital and income excluded; unclassified entries require review. No available-cash or household-completeness claim.");
         return result;
      });
   }



   static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var before = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", before.householdId());
      var actor = CarlService.manager(c, principal, "FINANCE");
      if(actor.householdId() != before.householdId())
      {
         throw new SecurityException("Membership changed");
      }
      return actor;
   }



   static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=? AND id=?", principal, id);
      if(rows.size() != 1)
      {
         throw new SecurityException("Record unavailable");
      }
      return rows.getFirst();
   }



   static void date(LocalDate date)
   {
      if(date == null || date.getYear() < 1900 || date.getYear() > 2200)
      {
         throw new IllegalArgumentException("Explicit supported date required");
      }
   }



   static BigDecimal money(BigDecimal value, String currency, boolean signed)
   {
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(value == null || scale < 0 || scale > 4 || value.abs().compareTo(new BigDecimal("1000000000000")) > 0 || value.stripTrailingZeros().scale() > scale || value.scale() < 0 || (!signed && value.signum() < 0) || (signed && value.signum() == 0))
      {
         throw new IllegalArgumentException("Explicit exact-currency amount required");
      }
      return value.setScale(scale);
   }
}
