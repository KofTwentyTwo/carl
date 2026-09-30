
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/** Dated human-reviewed cash assumptions and conditional purchase assessments over Carl's state. */
public final class CashPlans
{
   private final CarlService service;

   /** Uses the same current domain permissions as administration and conversation. */
   public CashPlans(CarlService service)
   {
      this.service = service;
   }

   /** Explicit human attestations are recorded evidence, not independently verified facts. */
   public record Assumptions(String currency, LocalDate from, LocalDate through, BigDecimal openingCash, BigDecimal reserve, BigDecimal discretionaryCap,
      boolean balancesResolved, boolean obligationsCovered, boolean scopeComplete, boolean reserveConfirmed, boolean selectedPlansIncluded, boolean incomeSupported)
   {
   }

   /** Creates one permission-scoped cash forecast with exact amounts and selected reserve/goal cap. */
   public long create(String principal, String title, String visibility, String evidence, Assumptions values)
   {
      if(values == null || values.from() == null || values.from().getYear() < 1900 || values.from().getYear() > 2200 || values.discretionaryCap() == null || values.discretionaryCap().signum() < 0)
      {
         throw new IllegalArgumentException("Explicit bounded dated cash assumptions are required");
      }
      CashFlow.project(values.currency(), values.openingCash(), values.reserve(), values.from(), values.through(), List.of());
      if(values.discretionaryCap().stripTrailingZeros().scale() > java.util.Currency.getInstance(values.currency()).getDefaultFractionDigits())
      {
         throw new IllegalArgumentException("Discretionary cap exceeds currency precision");
      }
      return service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_cash_plan(record_id,currency,from_date,through_date,opening_cash,reserve_floor,discretionary_cap,balances_resolved,obligations_covered,scope_complete,reserve_confirmed,selected_plans_included,income_supported) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", id, values.currency(), values.from(), values.through(), values.openingCash(), values.reserve(), values.discretionaryCap(), values.balancesResolved(), values.obligationsCovered(), values.scopeComplete(), values.reserveConfirmed(), values.selectedPlansIncluded(), values.incomeSupported());
         CarlService.bump(c, actor.householdId());
         return id;
      });
   }



   /** Adds one explicitly evidenced signed cash movement without counting reserve earmarks as expenses. */
   public void event(String principal, long plan, UUID event, LocalDate date, BigDecimal signedAmount, String title, String evidence)
   {
      CarlService.bounded(title, 500, "cash movement title");
      CarlService.bounded(evidence, 4000, "cash movement evidence");
      if(event == null)
      {
         throw new IllegalArgumentException("A stable cash event identifier is required");
      }
      service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         var rows = CarlService.rows(c, "SELECT * FROM carl_cash_plan_view WHERE principal=? AND id=?", principal, plan);
         if(rows.size() != 1)
         {
            throw new SecurityException("Cash plan unavailable");
         }
         var current = rows.getFirst();
         CashFlow.project(current.get("currency").toString(), (BigDecimal) current.get("opening_cash"), (BigDecimal) current.get("reserve_floor"), LocalDate.parse(current.get("from_date").toString()), LocalDate.parse(current.get("through_date").toString()), List.of(new CashFlow.Event(event.toString(), date, current.get("currency").toString(), signedAmount)));
         var saved = CarlService.rows(c, "INSERT INTO carl_cash_event(id,plan_id,event_date,signed_amount,title,evidence,created_by) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING RETURNING id", event, plan, date, signedAmount, title, evidence, actor.id());
         if(saved.isEmpty())
         {
            var prior = CarlService.rows(c, "SELECT * FROM carl_cash_event WHERE id=?", event).getFirst();
            if(CarlService.number(prior, "plan_id") != plan || !prior.get("event_date").toString().equals(date.toString()) || ((BigDecimal) prior.get("signed_amount")).compareTo(signedAmount) != 0 || !prior.get("title").equals(title) || !prior.get("evidence").equals(evidence))
            {
               throw new IllegalArgumentException("Cash event ID conflicts with prior input");
            }
            return null;
         }
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", plan);
         CarlService.bump(c, actor.householdId());
         return null;
      });
   }



   /** Persists a conditional cash-purchase assessment; credit availability is never an input. */
   public long assess(CarlService.Scope scope, UUID request, long plan, LocalDate purchaseDate, BigDecimal allInPrice, String purpose, boolean allInCostsKnown)
   {
      CarlService.bounded(purpose, 500, "purchase purpose");
      long epoch = service.member(scope.principal()).permissionRevision();
      String digest = BillCsv.hash(CarlService.json(Map.of("plan", plan, "date", purchaseDate, "price", allInPrice, "purpose", purpose, "allInCostsKnown", allInCostsKnown)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var current = service.view(scope, "cashPlans").stream().filter(row -> CarlService.number(row, "id") == plan).findFirst().orElseThrow(() -> new SecurityException("Cash plan unavailable"));
      var events = service.transaction(c -> CarlService.rows(c, "SELECT * FROM carl_cash_event WHERE plan_id=? ORDER BY event_date,id LIMIT 10001", plan));
      if(events.size() > 10000)
      {
         throw new IllegalArgumentException("Narrow the cash plan to at most 10000 movements");
      }
      var movements = new ArrayList<CashFlow.Event>();
      for(var event : events)
      {
         movements.add(new CashFlow.Event(event.get("id").toString(), LocalDate.parse(event.get("event_date").toString()), current.get("currency").toString(), (BigDecimal) event.get("signed_amount")));
      }
      LocalDate dataAsOf = service.dataAsOf(scope.principal());
      var expenses = new ExpenseRecords(service).cashInputs(scope, plan, dataAsOf);
      if(expenses.epoch() != epoch)
      {
         throw new SecurityException("Cash forecast permissions changed; regenerate the assessment");
      }
      movements.addAll(expenses.projection().cashEvents());
      BigDecimal selectedReserve = expenses.openingReserveEarmarks().add(expenses.projection().actualReserveEarmarks()).add(expenses.projection().remainingReserveEarmarks());
      BigDecimal protectedReserve = ((BigDecimal) current.get("reserve_floor")).add(selectedReserve);
      var sources = new LinkedHashMap<Long, Long>(expenses.sources());
      Long expensePlanRevision = sources.put(plan, CarlService.number(current, "revision"));
      if(expensePlanRevision != null && expensePlanRevision.longValue() != CarlService.number(current, "revision"))
      {
         throw new IllegalArgumentException("Cash forecast changed; regenerate the assessment");
      }
      var conditions = new PurchaseAffordability.Conditions((Boolean) current.get("balances_resolved"), (Boolean) current.get("obligations_covered") && expenses.completeForSelectedInputs(), (Boolean) current.get("scope_complete"), allInCostsKnown, (Boolean) current.get("reserve_confirmed"), (Boolean) current.get("selected_plans_included"), (Boolean) current.get("income_supported"));
      var budget = PurchaseAffordability.budget(current.get("currency").toString(), (BigDecimal) current.get("opening_cash"), protectedReserve, LocalDate.parse(current.get("from_date").toString()), LocalDate.parse(current.get("through_date").toString()), purchaseDate, (BigDecimal) current.get("discretionary_cap"), movements, conditions);
      var classification = PurchaseAffordability.classify(budget, allInPrice);
      var facts = new LinkedHashMap<String, Object>();
      facts.put("purpose", purpose);
      facts.put("allInPrice", allInPrice);
      facts.put("classification", classification);
      facts.put("budget", budget);
      facts.put("sourcePlan", current);
      facts.put("cashMovementEvidence", events);
      facts.put("selectedExpenseProjection", expenses.projection());
      facts.put("expenseCoverageGaps", expenses.gaps());
      facts.put("expenseAsOf", dataAsOf);
      facts.put("openingExpenseReserveEarmarks", expenses.openingReserveEarmarks());
      facts.put("protectedReserve", protectedReserve);
      facts.put("reservePolicy", "Selected reserve earmarks are additional to the base reserve floor and protected throughout the interval; they are not expenses. Select only earmarks excluded from the base floor. Manual cash events must not duplicate selected expense sources; this remains a reviewed scope assumption.");
      facts.put("evidenceStatus", "Human-supplied assumptions, not independent verification. Daily closing forecast only; intraday timing and later obligations remain uncertain.");
      String label = classification == PurchaseAffordability.Classification.UNDETERMINED ? "Incomplete purchase assessment — missing evidence" : "Conditional cash purchase assessment — review assumptions";
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", purchaseDate, LocalDate.parse(current.get("through_date").toString()), CarlService.json(facts), "", "NOT_REQUESTED", "No purchase, financing application or payment occurs. Supported cash budget is conditional on the recorded scope and human evidence; available credit is not budget.", sources, null, label, digest, epoch);
   }
}
