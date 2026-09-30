
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Owner-selected priorities and evidenced investment context, independent of inferred balance thresholds. */
public final class FinancialGoals
{
   private final CarlService service;

   /** Uses authoritative household permissions and artifact persistence. */
   public FinancialGoals(CarlService service)
   {
      this.service = service;
   }



   /** Records an explicit human priority; no financial balance automatically changes its stage. */
   public long create(String principal, String title, String kind, int priority, String visibility, String evidence)
   {
      if(!Set.of("DEBT_FREEDOM", "RENTAL_TAX", "INVESTMENT", "OTHER").contains(kind) || priority < 1 || priority > 100)
      {
         throw new IllegalArgumentException("An explicit goal category and priority are required");
      }
      return service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_financial_goal(record_id,goal_type,priority,selected_by) VALUES(?,?,?,?)", id, kind, priority, actor.id());
         CarlService.bump(c, actor.householdId());
         return id;
      });
   }



   /** Saves explicit investment assumptions with human attribution; missing context stays missing. */
   public void context(String principal, long goal, String currency, InvestmentPlanning.Context context, String evidence)
   {
      CarlService.bounded(evidence, 4000, "investment context evidence");
      if(context == null || currency == null)
      {
         throw new IllegalArgumentException("Explicit currency and owner context are required");
      }
      // The calculation validates bounded context without treating it as suitable product advice.
      InvestmentPlanning.project(currency, YearMonth.of(2026, 1), 1, BigDecimal.ZERO,
         List.of(), new InvestmentPlanning.Assumption("validation", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "human-context"), context);
      service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         var prior = CarlService.rows(c, "SELECT * FROM carl_financial_goal_view WHERE principal=? AND id=? AND goal_type='INVESTMENT'", principal, goal);
         if(prior.size() != 1)
         {
            throw new SecurityException("Investment goal unavailable");
         }
         CarlService.execute(c, "UPDATE carl_financial_goal SET currency=?,investment_stage_selected=?,horizon_months=?,risk_context=?,reserve_need=?,tax_context_supplied=?,holdings_complete=?,costs_complete=?,context_evidence=?,updated_at=now() WHERE record_id=?", currency, context.ownerSelectedInvestmentStage(), context.horizonMonths(), context.riskTolerance(), context.cashReserve(), context.taxContextSupplied(), context.holdingsComplete(), context.costsComplete(), evidence, goal);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", goal, actor.id(), "Explicit owner investment context", CarlService.json(prior), CarlService.json(context));
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", goal);
         CarlService.bump(c, actor.householdId());
         return null;
      });
   }



   /** Persists hypothetical return/fee scenarios with all source-context limitations. */
   public long scenario(CarlService.Scope scope, UUID request, long goal, String currency, YearMonth firstMonth, int months, BigDecimal initialCapital, BigDecimal monthlyContribution, InvestmentPlanning.Assumption assumption, String assumptionEvidence)
   {
      CarlService.bounded(assumptionEvidence, 4000, "investment scenario evidence");
      long epoch = service.member(scope.principal()).permissionRevision();
      if(months < 1 || months > 600 || firstMonth == null || monthlyContribution == null)
      {
         throw new IllegalArgumentException("Bounded explicit scenario assumptions are required");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("goal", goal, "currency", currency, "firstMonth", firstMonth, "months", months, "initial", initialCapital, "contribution", monthlyContribution, "assumption", assumption, "evidence", assumptionEvidence)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var saved = service.view(scope, "financialGoals").stream().filter(row -> CarlService.number(row, "id") == goal && "INVESTMENT".equals(row.get("goal_type"))).findFirst().orElseThrow(() -> new SecurityException("Investment goal unavailable"));
      if(saved.get("currency") != null && !currency.equals(saved.get("currency")))
      {
         throw new IllegalArgumentException("Scenario currency must match confirmed context");
      }
      var context = new InvestmentPlanning.Context((Boolean) saved.get("investment_stage_selected"), saved.get("horizon_months") == null ? null : ((Number) saved.get("horizon_months")).intValue(), (String) saved.get("risk_context"), (BigDecimal) saved.get("reserve_need"), (Boolean) saved.get("tax_context_supplied"), (Boolean) saved.get("holdings_complete"), (Boolean) saved.get("costs_complete"), "goal:" + goal);
      var contributions = new ArrayList<InvestmentPlanning.Contribution>();
      for(int month = 0; month < months; month++)
      {
         contributions.add(new InvestmentPlanning.Contribution("hypothetical:" + month, firstMonth.plusMonths(month), monthlyContribution));
      }
      var projection = InvestmentPlanning.project(currency, firstMonth, months, initialCapital, contributions, assumption, context);
      String facts = CarlService.json(Map.of("goal", saved, "assumption", assumption, "assumptionEvidence", assumptionEvidence, "projection", projection));
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", firstMonth.atDay(1), firstMonth.plusMonths(months - 1L).atEndOfMonth(), facts, "", "NOT_REQUESTED", "Educational assumptions only. Contributions require a separate current cash/debt/reserve plan; no product recommendation, order or transfer.", Map.of(goal, CarlService.number(saved, "revision")), null, projection.gaps().isEmpty() ? "Educational investment scenario — uncertain returns" : "Incomplete investment context — educational scenario only", digest, epoch);
   }
}
