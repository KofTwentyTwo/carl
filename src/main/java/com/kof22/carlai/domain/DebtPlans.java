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
import java.util.Set;
import java.util.UUID;


/** Authorized persisted debt assumptions and source-linked deterministic payoff comparisons. */
public final class DebtPlans
{
   private final CarlService service;

   /** Shares Carl's authoritative services and current access rules. */
   public DebtPlans(CarlService service)
   {
      this.service = service;
   }



   /** Records explicitly supplied current debt terms; never infers principal or APR from CSV sign. */
   public void terms(String principal, long account, LocalDate asOf, BigDecimal balance, BigDecimal minimum,
      BigDecimal minimumFraction, BigDecimal apr, BigDecimal monthlyFee, String evidence)
   {
      if(asOf == null)
      {
         throw new IllegalArgumentException("A dated debt statement or explicit assumption is required");
      }
      CarlService.bounded(evidence, 4000, "debt terms evidence");
      service.transaction(c ->
      {
         var member = CarlService.manager(c, principal, "FINANCE");
         CarlService.requireRecord(c, principal, account);
         var rows = CarlService.rows(c, "SELECT currency,kind FROM carl_account WHERE record_id=?", account);
         if(rows.size() != 1 || !Set.of("CREDIT_CARD", "LOAN").contains(rows.getFirst().get("kind")))
         {
            throw new IllegalArgumentException("Choose a debt account");
         }
         new FinancialPlanning.Debt(Long.toString(account), rows.getFirst().get("currency").toString(), balance, minimum, minimumFraction, List.of(new FinancialPlanning.Rate(1, apr)), monthlyFee);
         var prior = CarlService.rows(c, "SELECT * FROM carl_debt_terms WHERE account_id=?", account);
         CarlService.execute(c, "INSERT INTO carl_debt_terms(account_id,minimum_amount,minimum_rule,evidence,principal_balance,balance_as_of,minimum_fraction) VALUES(?,?,'MAX_FIXED_OR_FRACTION',?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET minimum_amount=EXCLUDED.minimum_amount,evidence=EXCLUDED.evidence,principal_balance=EXCLUDED.principal_balance,balance_as_of=EXCLUDED.balance_as_of,minimum_fraction=EXCLUDED.minimum_fraction", account, minimum, evidence, balance, asOf, minimumFraction);
         CarlService.execute(c, "INSERT INTO carl_debt_rate(account_id,effective_date,annual_rate,monthly_fee,evidence) VALUES(?,?,?,?,?) ON CONFLICT(account_id,effective_date) DO UPDATE SET annual_rate=EXCLUDED.annual_rate,monthly_fee=EXCLUDED.monthly_fee,evidence=EXCLUDED.evidence", account, asOf, apr, monthlyFee, evidence);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", account, member.id(), "Human supplied debt statement/assumptions", CarlService.json(prior), CarlService.json(Map.of("asOf", asOf.toString(), "principal", balance, "minimum", minimum, "fraction", minimumFraction, "apr", apr, "fee", monthlyFee, "evidence", evidence)));
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", account);
         CarlService.bump(c, member.householdId());
         return null;
      });
   }



   /** Records effective-dated rates and fees without replacing the authoritative statement balance. */
   public void rateChange(String principal, UUID request, long account, long expectedRevision, LocalDate effective,
      BigDecimal apr, BigDecimal monthlyFee, String evidence)
   {
      CarlService.bounded(evidence, 4000, "rate-change evidence");
      if(effective == null || effective.getYear() < 1900 || effective.getYear() > 2200)
      {
         throw new IllegalArgumentException("An explicit bounded effective date is required");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("account", account, "revision", expectedRevision, "effective", effective,
         "apr", apr, "monthlyFee", monthlyFee, "evidence", evidence)));
      service.transaction(c ->
      {
         var actor = CarlService.member(c, principal);
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", actor.householdId());
         actor = CarlService.manager(c, principal, "FINANCE");
         var accounts = CarlService.rows(c, "SELECT * FROM carl_account_view WHERE principal=? AND id=?", principal, account);
         if(accounts.size() != 1 || !Set.of("CREDIT_CARD", "LOAN").contains(accounts.getFirst().get("kind")))
         {
            throw new SecurityException("Debt account unavailable");
         }
         new FinancialPlanning.Debt(Long.toString(account), accounts.getFirst().get("currency").toString(), BigDecimal.ZERO,
            BigDecimal.ONE, BigDecimal.ZERO, List.of(new FinancialPlanning.Rate(1, apr)), monthlyFee);
         if(CarlService.request(c, actor, request, "DEBT_RATE_CHANGE", digest) != null)
         {
            return null;
         }
         var revision = CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", account).getFirst();
         if(CarlService.number(revision, "revision") != expectedRevision)
         {
            throw new IllegalArgumentException("Debt changed; review the current statement and rates");
         }
         var terms = CarlService.rows(c, "SELECT balance_as_of FROM carl_debt_terms WHERE account_id=?", account);
         if(terms.size() != 1 || terms.getFirst().get("balance_as_of") == null || effective.isBefore(LocalDate.parse(terms.getFirst().get("balance_as_of").toString())))
         {
            throw new IllegalArgumentException("Record a dated statement first; the rate change cannot precede it");
         }
         var before = CarlService.rows(c, "SELECT * FROM carl_debt_rate WHERE account_id=? AND effective_date=?", account, effective);
         CarlService.execute(c, "INSERT INTO carl_debt_rate(account_id,effective_date,annual_rate,monthly_fee,evidence) VALUES(?,?,?,?,?) ON CONFLICT(account_id,effective_date) DO UPDATE SET annual_rate=EXCLUDED.annual_rate,monthly_fee=EXCLUDED.monthly_fee,evidence=EXCLUDED.evidence", account, effective, apr, monthlyFee, evidence);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", account, actor.id(), "Human supplied effective-dated rate/fee change; statement retained", CarlService.json(before), CarlService.json(Map.of("effective", effective, "apr", apr, "monthlyFee", monthlyFee, "evidence", evidence)));
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", account);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, account, "COMPLETE", "Statement retained; dependent plans require explicit recomputation");
         return null;
      });
   }



   /** Retains human-supplied current/proposed payments and dates without inferring them from transfers. */
   public void paymentProfile(String principal, long account, LocalDate asOf, LocalDate firstPayment, BigDecimal currentPayment, BigDecimal proposedPayment, FinancialPlanning.Strategy rollover, String evidence)
   {
      CarlService.bounded(evidence, 4000, "payment profile evidence");
      if(asOf == null || firstPayment == null || asOf.getYear() < 1900 || asOf.getYear() > 2200 || firstPayment.isBefore(asOf) || firstPayment.isAfter(asOf.plusMonths(1)) || rollover == null)
      {
         throw new IllegalArgumentException("Explicit as-of and first-payment dates within one month are required");
      }
      new FinancialPlanning.PaymentTarget(1, currentPayment);
      new FinancialPlanning.PaymentTarget(1, proposedPayment);
      service.transaction(c ->
      {
         var actor = CarlService.manager(c, principal, "FINANCE");
         CarlService.requireRecord(c, principal, account);
         if(CarlService.rows(c, "SELECT account_id FROM carl_debt_terms WHERE account_id=?", account).isEmpty())
         {
            throw new IllegalArgumentException("Record debt statement terms first");
         }
         var prior = CarlService.rows(c, "SELECT * FROM carl_debt_payment_profile WHERE account_id=?", account);
         var after = Map.of("asOf", asOf.toString(), "firstPayment", firstPayment.toString(), "currentPayment", currentPayment, "proposedPayment", proposedPayment, "rollover", rollover.name(), "evidence", evidence);
         CarlService.execute(c, "INSERT INTO carl_debt_payment_profile(account_id,as_of,first_payment_date,current_payment,proposed_payment,rollover,evidence,updated_by) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET as_of=EXCLUDED.as_of,first_payment_date=EXCLUDED.first_payment_date,current_payment=EXCLUDED.current_payment,proposed_payment=EXCLUDED.proposed_payment,rollover=EXCLUDED.rollover,evidence=EXCLUDED.evidence,updated_by=EXCLUDED.updated_by,updated_at=now()", account, asOf, firstPayment, currentPayment, proposedPayment, rollover.name(), evidence, actor.id());
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", account, actor.id(), "Human supplied current and proposed payment assumptions", CarlService.json(prior), CarlService.json(after));
         CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", account);
         CarlService.bump(c, actor.householdId());
         return null;
      });
   }



   /** Saves an explicitly assumed payment-budget comparison; it is not an affordability determination. */
   public long compare(CarlService.Scope scope, UUID requestId, LocalDate asOf, String currency, BigDecimal monthlyBudget, int horizon, String budgetEvidence)
   {
      CarlService.bounded(budgetEvidence, 4000, "payment budget evidence");
      if(asOf == null || asOf.getYear() < 1900 || asOf.getYear() > 2200 || horizon < 1 || horizon > 600 || monthlyBudget == null || monthlyBudget.signum() <= 0)
      {
         throw new IllegalArgumentException("An explicit positive budget, date and bounded projection horizon are required");
      }
      long permissionRevision = service.member(scope.principal()).permissionRevision();
      String digest = BillCsv.hash(CarlService.json(Map.of("asOf", asOf.toString(), "currency", currency, "budget", monthlyBudget, "horizon", horizon, "evidence", budgetEvidence)));
      Long prior = service.claimArtifact(scope, requestId, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var sources = new LinkedHashMap<Long, Long>();
      var assumptions = new ArrayList<Map<String, Object>>();
      var debts = new ArrayList<FinancialPlanning.Debt>();
      var gaps = new ArrayList<String>();
      var paymentGaps = new ArrayList<String>();
      var dates = new LinkedHashMap<String, LocalDate>();
      var currentTargets = new LinkedHashMap<String, List<FinancialPlanning.PaymentTarget>>();
      var proposedTargets = new LinkedHashMap<String, List<FinancialPlanning.PaymentTarget>>();
      var rolloverPolicies = new java.util.HashSet<FinancialPlanning.Strategy>();
      for(var account : service.view(scope, "accounts"))
      {
         if(!Set.of("CREDIT_CARD", "LOAN").contains(account.get("kind")) || !currency.equals(account.get("currency")))
         {
            continue;
         }
         long id = CarlService.number(account, "id");
         service.transaction(c ->
         {
            for(String audience : scope.audience())
            {
               CarlService.requireRecord(c, audience, id);
            }
            sources.put(id, CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", id).getFirst(), "revision"));
            var terms = CarlService.rows(c, "SELECT d.*,r.revision FROM carl_debt_terms d JOIN carl_record r ON r.id=d.account_id WHERE d.account_id=?", id);
            if(terms.isEmpty() || terms.getFirst().get("principal_balance") == null)
            {
               gaps.add("Missing explicit debt terms for account " + id);
               return null;
            }
            var term = terms.getFirst();
            LocalDate dated = LocalDate.parse(term.get("balance_as_of").toString());
            if(!dated.equals(asOf))
            {
               gaps.add("Account " + id + " balance is dated " + dated + "; comparable projection requires selected as-of date");
               return null;
            }
            var rates = CarlService.rows(c, "SELECT * FROM carl_debt_rate WHERE account_id=? AND effective_date<=? ORDER BY effective_date DESC LIMIT 1", id, asOf);
            if(rates.isEmpty())
            {
               gaps.add("Missing APR evidence for account " + id);
               return null;
            }
            var rate = rates.getFirst();
            debts.add(new FinancialPlanning.Debt(Long.toString(id), currency, (BigDecimal) term.get("principal_balance"), (BigDecimal) term.get("minimum_amount"), (BigDecimal) term.get("minimum_fraction"), List.of(new FinancialPlanning.Rate(1, (BigDecimal) rate.get("annual_rate"))), (BigDecimal) rate.get("monthly_fee")));
            sources.put(id, CarlService.number(term, "revision"));
            assumptions.add(Map.of("accountId", id, "asOf", asOf.toString(), "evidence", term.get("evidence"), "rateEvidence", rate.get("evidence")));
            var profiles = CarlService.rows(c, "SELECT * FROM carl_debt_payment_profile WHERE account_id=?", id);
            if(profiles.size() != 1 || !asOf.toString().equals(profiles.getFirst().get("as_of").toString()))
            {
               paymentGaps.add("Account " + id + " needs explicitly dated current/proposed payments and first payment date");
            }
            else
            {
               var profile = profiles.getFirst();
               dates.put(Long.toString(id), LocalDate.parse(profile.get("first_payment_date").toString()));
               currentTargets.put(Long.toString(id), List.of(new FinancialPlanning.PaymentTarget(1, (BigDecimal) profile.get("current_payment"))));
               proposedTargets.put(Long.toString(id), List.of(new FinancialPlanning.PaymentTarget(1, (BigDecimal) profile.get("proposed_payment"))));
               rolloverPolicies.add(FinancialPlanning.Strategy.valueOf(profile.get("rollover").toString()));
               assumptions.add(Map.of("accountId", id, "paymentEvidence", profile.get("evidence"), "paymentDate", profile.get("first_payment_date")));
            }

            return null;
         });
      }
      var results = new LinkedHashMap<String, Object>();
      if(gaps.isEmpty() && !debts.isEmpty())
      {
         for(var strategy : List.of(FinancialPlanning.Strategy.AVALANCHE, FinancialPlanning.Strategy.SNOWBALL, FinancialPlanning.Strategy.MINIMUM_ONLY))
         {
            results.put(strategy.name(), FinancialPlanning.payoff(debts, currency, monthlyBudget, strategy, horizon));
         }
      }
      else if(debts.isEmpty())
      {
         gaps.add("No complete authorized debt inputs for this currency/date");
      }
      if(gaps.isEmpty() && paymentGaps.isEmpty() && !debts.isEmpty())
      {
         var budgets = List.of(new FinancialPlanning.PaymentTarget(1, monthlyBudget));
         var current = DebtPortfolio.solve(debts, currency, asOf, dates, budgets, DebtPortfolio.Mode.CURRENT_PAYMENT, currentTargets, FinancialPlanning.Strategy.MINIMUM_ONLY, horizon);
         results.put("CURRENT_PAYMENT", current);
         if(rolloverPolicies.size() == 1)
         {
            var proposed = DebtPortfolio.solve(debts, currency, asOf, dates, budgets, DebtPortfolio.Mode.USER_DIRECTED, proposedTargets, rolloverPolicies.iterator().next(), horizon);
            results.put("USER_DIRECTED", proposed);
            if(current.payoff().feasible() && proposed.payoff().feasible())
            {
               results.put("USER_DIRECTED_VS_CURRENT", DebtPortfolio.compare(current, proposed));
            }
         }
         else
         {
            paymentGaps.add("Choose one explicit common rollover policy for the proposed portfolio");
         }
      }
      String facts = CarlService.json(Map.of("scope", "Authorized debt accounts only", "asOf", asOf.toString(), "currency", currency, "monthlyBudget", monthlyBudget, "budgetEvidence", budgetEvidence, "assumptions", assumptions, "comparisons", results, "missingInputs", gaps, "additionalPaymentStrategyGaps", paymentGaps));
      String limits = "Monthly estimates under explicit fixed current terms. Budget is a supplied assumption after essentials/reserves, not verified affordability. No issuer payoff quote, refinancing offer or payment action. Only same-date complete scoped inputs are compared. Current/proposed payment schedules are explicitly supplied estimates; cash released is relative to the current-payment baseline, not new income. Dated essential spending/reserve qualification remains required.";
      return service.saveArtifact(scope, requestId, "FINANCIAL_PLAN", asOf, asOf.plusMonths(horizon), facts, "", "NOT_REQUESTED", limits, sources, null, gaps.isEmpty() ? "Debt comparison — assumptions require review" : "Incomplete debt comparison — missing inputs", digest, permissionRevision);
   }
}
