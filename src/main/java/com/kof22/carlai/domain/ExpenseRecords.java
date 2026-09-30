/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;


/** Durable, permission-scoped expenses and evidenced settlements for Carl's cash plans. */
public final class ExpenseRecords
{
   /** Supplied recurrence and amount certainty; null amount remains unknown. */
   public record Schedule(String currency, ExpenseForecast.Cadence cadence, LocalDate firstDue, LocalDate lastDue,
      BigDecimal baseAmount, Map<Integer, BigDecimal> seasonalAmounts, ExpenseForecast.Kind kind, ExpenseForecast.Basis basis, Long property)
   {
      /** Keeps supplied seasonality immutable. */
      public Schedule
      {
         seasonalAmounts = Map.copyOf(seasonalAmounts);
      }
   }



   /** Current calculated facts and source revisions; completeness only covers selected inputs. */
   public record CashInputs(long epoch, ExpenseForecast.Projection projection, BigDecimal openingReserveEarmarks, Map<Long, Long> sources, List<String> gaps, boolean completeForSelectedInputs)
   {
      /** Retains immutable calculation evidence. */
      public CashInputs
      {
         sources = Map.copyOf(sources);
         gaps = List.copyOf(gaps);
      }
   }
   private static final Set<String> VIEWS = Set.of("carl_expense_view", "carl_expense_actual_view", "carl_expense_settlement_view", "carl_cash_plan_view", "carl_rental_property_view", "carl_transaction_view");
   private final CarlService service;
   /** Shares Carl's verified identity, transactions and persistence. */
   public ExpenseRecords(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** Records an explicitly reviewed recurring expense without external business action. */
   public long create(String principal, UUID request, String title, String visibility, String evidence, Schedule values)
   {
      validate(values);
      String digest = digest(Map.of("op", "EXPENSE_CREATE", "title", title, "visibility", visibility, "evidence", evidence, "schedule", values));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         property(c, principal, values);
         Long prior = CarlService.request(c, actor, request, "EXPENSE_CREATE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_expense_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_expense(record_id,currency,cadence,first_due,last_due,base_amount,kind,basis,property_id) VALUES(?,?,?,?,?,?,?,?,?)", id, values.currency(), values.cadence().name(), values.firstDue(), values.lastDue(), values.baseAmount(), values.kind().name(), values.basis().name(), values.property());
         seasons(c, id, values);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Human-supplied obligation; no external mutation");
         return id;
      });
   }



   /** Preserves prior values and rejects changes until dependent settlements are reviewed. */
   public void correct(String principal, UUID request, long expense, long expectedRevision, Schedule values, String reason)
   {
      validate(values);
      CarlService.bounded(reason, 2000, "expense correction reason");
      String digest = digest(Map.of("op", "EXPENSE_CORRECT", "expense", expense, "revision", expectedRevision, "schedule", values, "reason", reason));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var old = require(c, principal, "carl_expense_view", expense);
         property(c, principal, values);
         old.put("seasonalAmounts", obligation(c, old).seasonalAmounts());
         if(CarlService.request(c, actor, request, "EXPENSE_CORRECT", digest) != null)
         {
            return null;
         }
         if(CarlService.number(old, "revision") != expectedRevision)
         {
            throw new IllegalArgumentException("Expense changed; review current values");
         }
         if(!CarlService.rows(c, "SELECT record_id FROM carl_expense_settlement WHERE expense_id=? AND active LIMIT 1", expense).isEmpty())
         {
            throw new IllegalArgumentException("Explicitly remove/review active occurrence settlements before changing the schedule");
         }
         CarlService.execute(c, "UPDATE carl_expense SET currency=?,cadence=?,first_due=?,last_due=?,base_amount=?,kind=?,basis=?,property_id=? WHERE record_id=?", values.currency(), values.cadence().name(), values.firstDue(), values.lastDue(), values.baseAmount(), values.kind().name(), values.basis().name(), values.property(), expense);
         CarlService.execute(c, "DELETE FROM carl_expense_season WHERE expense_id=?", expense);
         seasons(c, expense, values);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", expense, actor.id(), reason, CarlService.json(old), CarlService.json(values));
         touch(c, expense);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, expense, "COMPLETE", "Attributed local correction; original evidence retained");
         return null;
      });
   }



   /** Stores an attributed human payment assertion, separately from imported evidence. */
   public long manualActual(String principal, UUID request, LocalDate paid, String currency, BigDecimal amount, ExpenseForecast.Kind kind, String visibility, String evidence)
   {
      actual(paid, currency, amount, kind);
      String digest = digest(Map.of("op", "EXPENSE_MANUAL_ACTUAL", "date", paid, "currency", currency, "amount", amount, "kind", kind, "visibility", visibility, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         Long prior = CarlService.request(c, actor, request, "EXPENSE_ACTUAL", digest);
         if(prior != null)
         {
            require(c, principal, "carl_expense_actual_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, "Manually asserted expense payment", evidence);
         CarlService.execute(c, "INSERT INTO carl_expense_actual(record_id,paid_date,currency,amount,kind) VALUES(?,?,?,?,?)", id, paid, currency, amount, kind.name());
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Human assertion; not independently verified payment");
         return id;
      });
   }



   /** Classifies an accessible imported cash outflow while retaining its source revision. */
   public long classifyActual(String principal, UUID request, long transaction, ExpenseForecast.Kind kind, String visibility, String evidence)
   {
      String digest = digest(Map.of("op", "EXPENSE_SOURCE_ACTUAL", "transaction", transaction, "kind", kind, "visibility", visibility, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var source = require(c, principal, "carl_transaction_view", transaction);
         Long prior = CarlService.request(c, actor, request, "EXPENSE_ACTUAL", digest);
         if(prior != null)
         {
            require(c, principal, "carl_expense_actual_view", prior);
            return prior;
         }
         BigDecimal signed = (BigDecimal) source.get("amount");
         if(signed.signum() >= 0)
         {
            throw new IllegalArgumentException("Expense actual requires an evidenced outflow; refunds/income are distinct");
         }
         LocalDate paid = LocalDate.parse(source.get("effective_date").toString());
         actual(paid, source.get("currency").toString(), signed.negate(), kind);
         if(!CarlService.rows(c, "SELECT record_id FROM carl_expense_actual WHERE transaction_id=?", transaction).isEmpty())
         {
            throw new IllegalArgumentException("Source already classified; review existing evidence rather than duplicate it");
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, "Classified expense outflow", evidence);
         CarlService.execute(c, "INSERT INTO carl_expense_actual(record_id,transaction_id,transaction_revision,paid_date,currency,amount,kind) VALUES(?,?,?,?,?,?,?)", id, transaction, revision(c, transaction), paid, source.get("currency"), signed.negate(), kind.name());
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Imported outflow classification; no external action");
         return id;
      });
   }



   /** Refreshes changed imported evidence after explicit removal of dependent settlement links. */
   public void refreshActual(String principal, UUID request, long actual, long expectedRevision, String evidence)
   {
      CarlService.bounded(evidence, 4000, "actual source review evidence");
      String digest = digest(Map.of("op", "EXPENSE_REFRESH_ACTUAL", "actual", actual, "revision", expectedRevision, "evidence", evidence));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var old = require(c, principal, "carl_expense_actual_view", actual);
         if(CarlService.request(c, actor, request, "EXPENSE_REFRESH_ACTUAL", digest) != null)
         {
            return null;
         }
         if(CarlService.number(old, "revision") != expectedRevision || old.get("transaction_id") == null)
         {
            throw new IllegalArgumentException("Review current imported actual before refreshing its source");
         }
         if(!CarlService.rows(c, "SELECT record_id FROM carl_expense_settlement WHERE actual_id=? AND active LIMIT 1", actual).isEmpty())
         {
            throw new IllegalArgumentException("Explicitly remove/review active settlements before refreshing their payment source");
         }
         long transaction = CarlService.number(old, "transaction_id");
         var source = require(c, principal, "carl_transaction_view", transaction);
         BigDecimal signed = (BigDecimal) source.get("amount");
         if(signed.signum() >= 0)
         {
            throw new IllegalArgumentException("Updated source is not an expense outflow; cannot assert a payment");
         }
         LocalDate paid = LocalDate.parse(source.get("effective_date").toString());
         ExpenseForecast.Kind kind = ExpenseForecast.Kind.valueOf(old.get("kind").toString());
         actual(paid, source.get("currency").toString(), signed.negate(), kind);
         CarlService.execute(c, "UPDATE carl_expense_actual SET transaction_revision=?,paid_date=?,currency=?,amount=? WHERE record_id=?", revision(c, transaction), paid, source.get("currency"), signed.negate(), actual);
         var changed = require(c, principal, "carl_expense_actual_view", actual);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", actual, actor.id(), evidence, CarlService.json(old), CarlService.json(changed));
         touch(c, actual);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, actual, "COMPLETE", "Attributed source refresh; prior values retained, no external payment action");
         return null;
      });
   }



   /** Explicitly associates an evidenced payment with a known scheduled occurrence. */
   public long settle(String principal, UUID request, long expense, LocalDate due, long actual, BigDecimal amount, String evidence)
   {
      CarlService.bounded(evidence, 4000, "settlement evidence");
      String digest = digest(Map.of("op", "EXPENSE_SETTLE", "expense", expense, "due", due, "actual", actual, "amount", amount, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var obligation = require(c, principal, "carl_expense_view", expense);
         var payment = require(c, principal, "carl_expense_actual_view", actual);
         Long prior = CarlService.request(c, actor, request, "EXPENSE_SETTLE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_expense_settlement_view", prior);
            return prior;
         }
         if(Boolean.TRUE.equals(payment.get("source_stale")))
         {
            throw new IllegalArgumentException("Payment source changed; classification requires review");
         }
         if(!obligation.get("currency").equals(payment.get("currency")) || !obligation.get("kind").equals(payment.get("kind")))
         {
            throw new IllegalArgumentException("Settlement currency and type must match");
         }
         if(!CarlService.rows(c, "SELECT s.record_id FROM carl_expense_settlement s WHERE s.active AND(s.expense_id=? OR s.actual_id=?) AND NOT EXISTS(SELECT 1 FROM carl_expense_settlement_view v WHERE v.id=s.record_id AND v.principal=?) LIMIT 1", expense, actual, principal).isEmpty())
         {
            throw unavailable();
         }
         var generated = ExpenseForecast.project(obligation.get("currency").toString(), due, due, due, List.of(obligation(c, obligation)), List.of(), List.of());
         if(generated.occurrences().size() != 1 || generated.occurrences().getFirst().expectedAmount() == null)
         {
            throw new IllegalArgumentException("Settlement requires a known scheduled occurrence amount");
         }
         BigDecimal applied = money(obligation.get("currency").toString(), amount);
         BigDecimal paid = (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(amount),0) AS total FROM carl_expense_settlement WHERE actual_id=? AND active", actual).getFirst().get("total");
         BigDecimal dueApplied = (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(amount),0) AS total FROM carl_expense_settlement WHERE expense_id=? AND due_date=? AND active", expense, due).getFirst().get("total");
         if(paid.add(applied).compareTo((BigDecimal) payment.get("amount")) > 0 || dueApplied.add(applied).compareTo(generated.occurrences().getFirst().expectedAmount()) > 0)
         {
            throw new IllegalArgumentException("Settlement exceeds actual payment or scheduled amount");
         }
         String visibility = CarlService.rows(c, "SELECT visibility FROM carl_record WHERE id=?", expense).getFirst().get("visibility").equals("FAMILY") && CarlService.rows(c, "SELECT visibility FROM carl_record WHERE id=?", actual).getFirst().get("visibility").equals("FAMILY") ? "FAMILY" : "PRIVATE";
         long id = CarlService.record(c, actor, "FINANCE", visibility, "Expense occurrence settlement", evidence);
         CarlService.execute(c, "INSERT INTO carl_expense_settlement(record_id,expense_id,due_date,actual_id,amount) VALUES(?,?,?,?,?)", id, expense, due, actual, applied);
         touch(c, expense);
         touch(c, actual);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Explicit evidenced occurrence link; original payment retained");
         return id;
      });
   }



   /** Removes an occurrence link with attribution instead of deleting history. */
   public void unsettle(String principal, UUID request, long settlement, String reason)
   {
      CarlService.bounded(reason, 2000, "settlement correction reason");
      String digest = digest(Map.of("op", "EXPENSE_UNSETTLE", "settlement", settlement, "reason", reason));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var old = require(c, principal, "carl_expense_settlement_view", settlement);
         if(CarlService.request(c, actor, request, "EXPENSE_UNSETTLE", digest) != null)
         {
            return null;
         }
         if(Boolean.TRUE.equals(old.get("active")))
         {
            CarlService.execute(c, "UPDATE carl_expense_settlement SET active=false WHERE record_id=?", settlement);
            CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", settlement, actor.id(), reason, CarlService.json(old), "Inactive; retained history");
            touch(c, settlement);
            touch(c, CarlService.number(old, "expense_id"));
            touch(c, CarlService.number(old, "actual_id"));
            CarlService.bump(c, actor.householdId());
         }
         CarlService.complete(c, request, settlement, "COMPLETE", "Attributed settlement removal; no external payment action");
         return null;
      });
   }



   /** Selects expense evidence for a cash plan; later assessments recheck current access. */
   public void attachCashPlan(String principal, UUID request, long plan, Set<Long> expenses, Set<Long> actuals, String evidence)
   {
      ids(expenses, 1000);
      ids(actuals, 10_000);
      CarlService.bounded(evidence, 4000, "cash expense selection evidence");
      String digest = digest(Map.of("op", "CASH_EXPENSE_ATTACH", "plan", plan, "expenses", expenses.stream().sorted().toList(), "actuals", actuals.stream().sorted().toList(), "evidence", evidence));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var target = require(c, principal, "carl_cash_plan_view", plan);
         if(CarlService.request(c, actor, request, "CASH_EXPENSE_ATTACH", digest) != null)
         {
            return null;
         }
         for(long id : expenses)
         {
            if(!target.get("currency").equals(require(c, principal, "carl_expense_view", id).get("currency")))
            {
               throw new IllegalArgumentException("Cash plan and selected expenses require one currency");
            }
         }
         for(long id : actuals)
         {
            if(!target.get("currency").equals(require(c, principal, "carl_expense_actual_view", id).get("currency")))
            {
               throw new IllegalArgumentException("Cash plan and selected actuals require one currency");
            }
         }
         var before = new LinkedHashMap<String, Object>();
         before.put("expenses", CarlService.rows(c, "SELECT expense_id FROM carl_cash_expense_selection WHERE record_id=? ORDER BY expense_id", plan));
         before.put("actuals", CarlService.rows(c, "SELECT actual_id FROM carl_cash_expense_actual_selection WHERE record_id=? ORDER BY actual_id", plan));
         CarlService.execute(c, "DELETE FROM carl_cash_expense_selection WHERE record_id=?", plan);
         CarlService.execute(c, "DELETE FROM carl_cash_expense_actual_selection WHERE record_id=?", plan);
         for(long id : expenses.stream().sorted().toList())
         {
            CarlService.execute(c, "INSERT INTO carl_cash_expense_selection(record_id,expense_id) VALUES(?,?)", plan, id);
         }
         for(long id : actuals.stream().sorted().toList())
         {
            CarlService.execute(c, "INSERT INTO carl_cash_expense_actual_selection(record_id,actual_id) VALUES(?,?)", plan, id);
         }
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", plan, actor.id(), evidence, CarlService.json(before), CarlService.json(Map.of("expenses", expenses.stream().sorted().toList(), "actuals", actuals.stream().sorted().toList())));
         touch(c, plan);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, plan, "COMPLETE", "Expense inputs selected; avoid duplicate manual cash events");
         return null;
      });
   }



   /** Reads selected typed records through the audience's intersection of permissions. */
   public List<Map<String, Object>> records(CarlService.Scope scope, String kind)
   {
      String view = switch(kind)
      {
         case "expenses" -> "carl_expense_view";
         case "actuals" -> "carl_expense_actual_view";
         case "settlements" -> "carl_expense_settlement_view";
         default -> throw new IllegalArgumentException("Unknown expense record type");
      };
      return service.transaction(c ->
      {
         lockScope(c, scope);
         return scoped(c, scope, view, "", List.of(), 1000);
      });
   }



   /** Calculates cash-plan inputs without hiding mutations in a read. */
   public CashInputs cashInputs(CarlService.Scope scope, long plan, LocalDate asOf)
   {
      return service.transaction(c ->
      {
         lockScope(c, scope);
         var rows = scoped(c, scope, "carl_cash_plan_view", " AND id=?", List.of(plan), 1);
         if(rows.size() != 1)
         {
            throw unavailable();
         }
         var target = rows.getFirst();
         var expenseRows = CarlService.rows(c, "SELECT expense_id FROM carl_cash_expense_selection WHERE record_id=? ORDER BY expense_id LIMIT 1001", plan);
         var actualRows = CarlService.rows(c, "SELECT actual_id FROM carl_cash_expense_actual_selection WHERE record_id=? ORDER BY actual_id LIMIT 10001", plan);
         if(expenseRows.size() > 1000 || actualRows.size() > 10000)
         {
            throw new IllegalArgumentException("Cash expense selections exceed supported bounds");
         }
         Set<Long> expenses = expenseRows.stream().map(row -> CarlService.number(row, "expense_id")).collect(java.util.stream.Collectors.toSet());
         Set<Long> actuals = actualRows.stream().map(row -> CarlService.number(row, "actual_id")).collect(java.util.stream.Collectors.toSet());
         var input = snapshot(c, scope, expenses, actuals, target.get("currency").toString(), LocalDate.parse(target.get("from_date").toString()), LocalDate.parse(target.get("through_date").toString()), asOf);
         var sources = new HashMap<>(input.sources());
         sources.put(plan, CarlService.number(target, "revision"));
         return new CashInputs(input.epoch(), input.projection(), input.openingReserveEarmarks(), sources, input.gaps(), input.completeForSelectedInputs());
      });
   }



   /** Saves a reproducible scoped forecast with explicit source-coverage limitations. */
   public long report(CarlService.Scope scope, UUID request, Set<Long> expenses, Set<Long> actuals, LocalDate from, LocalDate through, LocalDate asOf)
   {
      ids(expenses, 1000);
      ids(actuals, 10_000);
      if(expenses.isEmpty() && actuals.isEmpty())
      {
         throw new IllegalArgumentException("Select expense or actual evidence");
      }
      String digest = digest(Map.of("op", "EXPENSE_REPORT", "expenses", expenses.stream().sorted().toList(), "actuals", actuals.stream().sorted().toList(), "from", from, "through", through, "asOf", asOf));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var input = service.transaction(c ->
      {
         lockScope(c, scope);
         String currency = !expenses.isEmpty() ? require(c, scope.principal(), "carl_expense_view", expenses.stream().sorted().findFirst().orElseThrow()).get("currency").toString() : require(c, scope.principal(), "carl_expense_actual_view", actuals.stream().sorted().findFirst().orElseThrow()).get("currency").toString();
         return snapshot(c, scope, expenses, actuals, currency, from, through, asOf);
      });
      var facts = new LinkedHashMap<String, Object>();
      facts.put("formulaVersion", "expense-schedule-v1");
      facts.put("from", from);
      facts.put("through", through);
      facts.put("asOf", asOf);
      facts.put("projection", input.projection());
      facts.put("openingReserveEarmarks", input.openingReserveEarmarks());
      facts.put("exceptions", input.gaps());
      facts.put("scope", "Selected authorized obligations and actuals only; partial household source coverage");
      facts.put("evidenceStatus", "Manual payment assertions and imported classifications are distinct from independent verification; no tax treatment inferred");
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", from, through, CarlService.json(facts), "", "NOT_REQUESTED", "Reserve earmarks are separate from net cash expenses. Coverage is limited to selected evidence; no payment or external action occurs.", input.sources(), null, input.completeForSelectedInputs() ? "Conditional expense forecast — selected scope only" : "Incomplete expense forecast — missing or stale evidence", digest, input.epoch());
   }



   private CashInputs snapshot(Connection c, CarlService.Scope scope, Set<Long> selectedExpenses, Set<Long> selectedActuals, String currency, LocalDate from, LocalDate through, LocalDate asOf) throws SQLException
   {
      long epoch = lockScope(c, scope);
      ids(selectedExpenses, 1000);
      ids(selectedActuals, 10_000);
      var sources = new HashMap<Long, Long>();
      var gaps = new ArrayList<String>();
      var obligations = new ArrayList<ExpenseForecast.Obligation>();
      for(long id : selectedExpenses.stream().sorted().toList())
      {
         var row = shared(c, scope, "carl_expense_view", id);
         if(!currency.equals(row.get("currency")))
         {
            throw new IllegalArgumentException("Separate expense forecasts by currency");
         }
         sources.put(id, CarlService.number(row, "revision"));
         obligations.add(obligation(c, row));
         if(row.get("property_id") != null)
         {
            long property = CarlService.number(row, "property_id");
            var parent = shared(c, scope, "carl_rental_property_view", property);
            sources.put(property, CarlService.number(parent, "revision"));
            for(String key : List.of("asset_account_id", "debt_account_id"))
            {
               if(parent.get(key) != null)
               {
                  long account = CarlService.number(parent, key);
                  sources.put(account, revision(c, account));
               }
            }
         }
      }
      var rawLinks = new ArrayList<Map<String, Object>>();
      for(long expense : selectedExpenses.stream().sorted().toList())
      {
         rawLinks.addAll(CarlService.rows(c, "SELECT record_id FROM carl_expense_settlement WHERE expense_id=? AND active AND due_date>=? AND due_date<=? ORDER BY record_id LIMIT 100001", expense, from, through));
         if(rawLinks.size() > 100_000)
         {
            throw new IllegalArgumentException("Narrow expense forecast; settlement bound exceeded");
         }
      }
      var links = new ArrayList<Map<String, Object>>();
      var actualIds = new java.util.HashSet<>(selectedActuals);
      for(var raw : rawLinks)
      {
         var link = shared(c, scope, "carl_expense_settlement_view", CarlService.number(raw, "record_id"));
         links.add(link);
         actualIds.add(CarlService.number(link, "actual_id"));
      }
      if(actualIds.size() > 10_000)
      {
         throw new IllegalArgumentException("Narrow expense forecast; actual bound exceeded");
      }
      var actuals = new ArrayList<ExpenseForecast.Actual>();
      var available = new java.util.HashSet<Long>();
      for(long id : actualIds.stream().sorted().toList())
      {
         var row = shared(c, scope, "carl_expense_actual_view", id);
         if(!currency.equals(row.get("currency")))
         {
            throw new IllegalArgumentException("Separate actual expense forecasts by currency");
         }
         sources.put(id, CarlService.number(row, "revision"));
         if(row.get("transaction_id") != null)
         {
            long transaction = CarlService.number(row, "transaction_id");
            var source = shared(c, scope, "carl_transaction_view", transaction);
            sources.put(transaction, revision(c, transaction));
            long account = CarlService.number(source, "account_id");
            sources.put(account, revision(c, account));
         }
         if(Boolean.TRUE.equals(row.get("source_stale")))
         {
            gaps.add("Actual " + id + " source changed; excluded until reviewed");
            continue;
         }
         available.add(id);
         actuals.add(new ExpenseForecast.Actual(Long.toString(id), LocalDate.parse(row.get("paid_date").toString()), currency, money(currency, (BigDecimal) row.get("amount")), ExpenseForecast.Kind.valueOf(row.get("kind").toString()), "Actual record " + id));
      }
      var settlements = new ArrayList<ExpenseForecast.Settlement>();
      // Several attributed payments may link the same actual and occurrence; aggregate their current active amount.
      var amounts = new LinkedHashMap<List<String>, BigDecimal>();
      for(var link : links)
      {
         long id = CarlService.number(link, "id");
         sources.put(id, CarlService.number(link, "revision"));
         long actual = CarlService.number(link, "actual_id");
         if(!available.contains(actual))
         {
            gaps.add("Settlement " + id + " source requires review; excluded");
            continue;
         }
         var key = List.of(link.get("expense_id") + "@" + link.get("due_date"), Long.toString(actual));
         amounts.merge(key, (BigDecimal) link.get("amount"), BigDecimal::add);
      }
      amounts.forEach((key, amount) -> settlements.add(new ExpenseForecast.Settlement(key.getFirst(), key.get(1), amount)));
      var projection = ExpenseForecast.project(currency, from, through, asOf, obligations, actuals, settlements);
      // Actual IDs are unique even when multiple occurrence settlements reference the same reserve.
      BigDecimal openingReserve = actuals.stream().filter(actual -> actual.kind() == ExpenseForecast.Kind.RESERVE_EARMARK && actual.paid().isBefore(from)).map(ExpenseForecast.Actual::amount).reduce(BigDecimal.ZERO.setScale(Currency.getInstance(currency).getDefaultFractionDigits()), BigDecimal::add);
      return new CashInputs(epoch, projection, openingReserve, sources, gaps, gaps.isEmpty() && projection.cashCoverageComplete());
   }



   private static ExpenseForecast.Obligation obligation(Connection c, Map<String, Object> row) throws SQLException
   {
      var seasons = new TreeMap<Integer, BigDecimal>();
      for(var item : CarlService.rows(c, "SELECT month,amount FROM carl_expense_season WHERE expense_id=? ORDER BY month", CarlService.number(row, "id")))
      {
         seasons.put(Math.toIntExact(CarlService.number(item, "month")), (BigDecimal) item.get("amount"));
      }
      return new ExpenseForecast.Obligation(row.get("id").toString(), row.get("currency").toString(), ExpenseForecast.Cadence.valueOf(row.get("cadence").toString()), LocalDate.parse(row.get("first_due").toString()), row.get("last_due") == null ? null : LocalDate.parse(row.get("last_due").toString()), (BigDecimal) row.get("base_amount"), seasons, ExpenseForecast.Kind.valueOf(row.get("kind").toString()), ExpenseForecast.Basis.valueOf(row.get("basis").toString()), "Expense record " + row.get("id"));
   }



   private static void seasons(Connection c, long id, Schedule values) throws SQLException
   {
      for(var entry : new TreeMap<>(values.seasonalAmounts()).entrySet())
      {
         CarlService.execute(c, "INSERT INTO carl_expense_season(expense_id,month,amount) VALUES(?,?,?)", id, entry.getKey(), entry.getValue());
      }
   }



   private static void validate(Schedule values)
   {
      if(values == null)
      {
         throw new IllegalArgumentException("Explicit expense schedule is required");
      }
      ExpenseForecast.project(values.currency(), values.firstDue(), values.firstDue(), values.firstDue(), List.of(new ExpenseForecast.Obligation("validate", values.currency(), values.cadence(), values.firstDue(), values.lastDue(), values.baseAmount(), values.seasonalAmounts(), values.kind(), values.basis(), "Validation")), List.of(), List.of());
      if(values.property() != null && values.property() <= 0)
      {
         throw new IllegalArgumentException("Explicit property identity is required");
      }
   }



   private static void property(Connection c, String principal, Schedule values) throws SQLException
   {
      if(values.property() != null && !require(c, principal, "carl_rental_property_view", values.property()).get("currency").equals(values.currency()))
      {
         throw new IllegalArgumentException("Property and expense currency must match");
      }
   }



   private static void actual(LocalDate paid, String currency, BigDecimal amount, ExpenseForecast.Kind kind)
   {
      ExpenseForecast.project(currency, paid, paid, paid, List.of(), List.of(new ExpenseForecast.Actual("validate", paid, currency, amount, kind, "Validation")), List.of());
      money(currency, amount);
   }



   private static BigDecimal money(String currency, BigDecimal amount)
   {
      if(amount == null || amount.precision() > 18 || Math.abs((long) amount.scale()) > 18 || amount.abs().compareTo(new BigDecimal("100000000000000")) > 0)
      {
         throw new IllegalArgumentException("Expense amount precision/scale exceeds supported bounds");
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4 || amount.signum() <= 0)
      {
         throw new IllegalArgumentException("Bounded positive expense amount is required");
      }
      try
      {
         return amount.setScale(scale, java.math.RoundingMode.UNNECESSARY);
      }
      catch(ArithmeticException invalid)
      {
         throw new IllegalArgumentException("Expense exceeds currency precision", invalid);
      }
   }



   private static void ids(Set<Long> values, int maximum)
   {
      if(values == null || values.size() > maximum || values.stream().anyMatch(id -> id == null || id <= 0))
      {
         throw new IllegalArgumentException("Bounded persisted expense identities are required");
      }
   }



   private static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var first = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", first.householdId());
      var current = CarlService.manager(c, principal, "FINANCE");
      if(current.householdId() != first.householdId())
      {
         throw unavailable();
      }
      return current;
   }



   private static long lockScope(Connection c, CarlService.Scope scope) throws SQLException
   {
      var actor = CarlService.member(c, scope.principal());
      long epoch = CarlService.number(CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", actor.householdId()).getFirst(), "permission_revision");
      for(String principal : scope.audience())
      {
         if(CarlService.member(c, principal).householdId() != actor.householdId())
         {
            throw unavailable();
         }
      }
      return epoch;
   }



   private static List<Map<String, Object>> scoped(Connection c, CarlService.Scope scope, String view, String clause, List<Object> values, int limit) throws SQLException
   {
      if(!VIEWS.contains(view))
      {
         throw new IllegalArgumentException("Unregistered expense view");
      }
      Map<Long, Map<String, Object>> allowed = null;
      for(String principal : scope.audience().stream().sorted().toList())
      {
         var params = new ArrayList<Object>();
         params.add(principal);
         params.addAll(values);
         var rows = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=?" + clause + " ORDER BY id LIMIT " + (limit + 1), params.toArray());
         if(rows.size() > limit)
         {
            throw new IllegalArgumentException("Expense query exceeds supported bound");
         }
         var current = new LinkedHashMap<Long, Map<String, Object>>();
         for(var row : rows)
         {
            row.remove("principal");
            current.put(CarlService.number(row, "id"), row);
         }
         if(allowed == null)
         {
            allowed = current;
         }
         else
         {
            allowed.entrySet().removeIf(entry -> !entry.getValue().equals(current.get(entry.getKey())));
         }
      }
      return allowed == null ? List.of() : List.copyOf(allowed.values());
   }



   private static Map<String, Object> shared(Connection c, CarlService.Scope scope, String view, long id) throws SQLException
   {
      var found = scoped(c, scope, view, " AND id=?", List.of(id), 1);
      if(found.size() != 1)
      {
         throw unavailable();
      }
      return found.getFirst();
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      return shared(c, CarlService.Scope.privateFor(principal), view, id);
   }



   private static long revision(Connection c, long id) throws SQLException
   {
      return CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", id).getFirst(), "revision");
   }



   private static void touch(Connection c, long id) throws SQLException
   {
      CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
   }



   private static String digest(Object value)
   {
      return BillCsv.hash(CarlService.json(value));
   }



   private static SecurityException unavailable()
   {
      return new SecurityException("Expense evidence unavailable");
   }
}
