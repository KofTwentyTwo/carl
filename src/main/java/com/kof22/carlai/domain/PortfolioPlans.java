/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Immutable reviewed financing allocations and whole-portfolio estimates; never financial execution. */
public final class PortfolioPlans
{
   /** Explicit destination assumptions, including payment priority input distinct from borrowing capacity. */
   public record Destination(long offer, LocalDate asOf, BigDecimal capacity, BigDecimal minimum,
      BigDecimal minimumFraction, BigDecimal monthlyFee, LocalDate firstPayment, BigDecimal proposedPayment)
   {
   }



   private record Snapshot(long epoch, PortfolioRestructuring.Inputs inputs, List<PortfolioRestructuring.Move> moves,
      Map<String, List<FinancialPlanning.PaymentTarget>> current, Map<String, List<FinancialPlanning.PaymentTarget>> proposed,
      Map<Long, Long> sources, List<Map<String, Object>> assumptions, List<String> gaps)
   {
   }

   private final CarlService service;
   private static final String LIMITS = "Hypothetical local analysis only; no application, transfer, payment or acceptance. Selected accessible debts only, never a claim of whole-household coverage. Budget is an explicit assumption, not qualified available cash: dated essentials, reserves and cash evidence remain required. Offer evidence/approval, collateral and closing/prepayment conditions need human review. Monthly estimates are not issuer quotes or guaranteed savings.";

   /** Shares authoritative state, current permissions and immutable artifacts. */
   public PortfolioPlans(CarlService service)
   {
      this.service = service;
   }



   /** Stores an immutable human-reviewed destination and source-to-offer allocations with attribution. */
   public long createMove(String principal, UUID request, String title, String visibility, Destination destination,
      Map<Long, BigDecimal> allocations, String evidence)
   {
      CarlService.bounded(evidence, 4000, "reviewed allocation evidence");
      if(destination == null || allocations == null || allocations.isEmpty() || allocations.size() > 100)
      {
         throw new IllegalArgumentException("A destination and bounded explicit source allocations are required");
      }
      ids(allocations.keySet(), 100);
      date(destination.asOf());
      if(destination.firstPayment() == null || destination.firstPayment().isBefore(destination.asOf()) || destination.firstPayment().isAfter(destination.asOf().plusMonths(1)))
      {
         throw new IllegalArgumentException("An explicit first payment within one month is required");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("operation", "PORTFOLIO_MOVE", "title", title, "visibility", visibility, "destination", destination, "allocations", allocations, "evidence", evidence)));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         Long prior = CarlService.request(c, actor, request, "PORTFOLIO_MOVE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_portfolio_move_view", prior);
            return prior;
         }
         var offerRow = require(c, principal, "carl_financing_offer_view", destination.offer());
         var offer = offer(offerRow);
         if(Set.of("CONSOLIDATION", "REFINANCE").contains(offerRow.get("kind")) && (destination.minimum() == null || destination.minimum().compareTo(offer.monthlyPayment()) < 0))
         {
            throw new IllegalArgumentException("Fixed-loan destination minimum must preserve the recorded contractual monthly payment");
         }
         if(!destination.firstPayment().toString().equals(offerRow.get("first_payment").toString()))
         {
            throw new IllegalArgumentException("Destination must preserve the offer first-payment date");
         }
         var debts = new ArrayList<FinancialPlanning.Debt>();
         var revisions = new LinkedHashMap<Long, Long>();
         for(long id : allocations.keySet().stream().sorted().toList())
         {
            var row = require(c, principal, "carl_account_view", id);
            if(!offer.currency().equals(row.get("currency")))
            {
               throw new IllegalArgumentException("Source and destination currencies must match");
            }
            var terms = CarlService.rows(c, "SELECT * FROM carl_debt_terms WHERE account_id=?", id);
            if(terms.size() != 1 || terms.getFirst().get("principal_balance") == null || !destination.asOf().toString().equals(terms.getFirst().get("balance_as_of").toString()))
            {
               throw new IllegalArgumentException("Source debt requires explicit same-date principal evidence");
            }
            BigDecimal balance = (BigDecimal) terms.getFirst().get("principal_balance");
            debts.add(new FinancialPlanning.Debt(Long.toString(id), offer.currency(), balance, BigDecimal.ONE, BigDecimal.ZERO, List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO)), BigDecimal.ZERO));
            revisions.put(id, revision(c, id));
         }
         new FinancialPlanning.Debt("destination", offer.currency(), offer.principal().add(offer.financedFee()), destination.minimum(), destination.minimumFraction(), offer.rates(), destination.monthlyFee());
         new FinancialPlanning.PaymentTarget(1, destination.proposedPayment());
         var stringAllocations = new LinkedHashMap<String, BigDecimal>();
         allocations.forEach((id, amount) -> stringAllocations.put(id.toString(), amount));
         FinancingScenarios.transfer(debts, stringAllocations, offer, destination.capacity());
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_portfolio_move(record_id,offer_id,offer_revision,as_of,capacity,minimum_amount,minimum_fraction,monthly_fee,first_payment,proposed_payment,reviewed_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)", id, destination.offer(), CarlService.number(offerRow, "revision"), destination.asOf(), destination.capacity(), destination.minimum(), destination.minimumFraction(), destination.monthlyFee(), destination.firstPayment(), destination.proposedPayment(), actor.id());
         for(long account : allocations.keySet().stream().sorted().toList())
         {
            CarlService.execute(c, "INSERT INTO carl_portfolio_allocation(move_id,account_id,account_revision,amount) VALUES(?,?,?,?)", id, account, revisions.get(account), allocations.get(account));
         }
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Reviewed hypothetical allocation; no money moved");
         return id;
      });
   }



   /** Returns only move projections and allocations identically available to every report recipient. */
   public List<Map<String, Object>> records(CarlService.Scope scope)
   {
      return service.transaction(c ->
      {
         lock(c, scope);
         var result = new ArrayList<Map<String, Object>>();
         var found = CarlService.rows(c, "SELECT id FROM carl_portfolio_move_view WHERE principal=? ORDER BY id LIMIT 1001", scope.principal());
         if(found.size() > 1000)
         {
            throw new IllegalArgumentException("Narrow the portfolio query");
         }
         for(var row : found)
         {
            long id = CarlService.number(row, "id");
            boolean available = true;
            for(String actor : scope.audience())
            {
               available &= !CarlService.rows(c, "SELECT id FROM carl_portfolio_move_view WHERE principal=? AND id=?", actor, id).isEmpty();
            }
            if(available)
            {
               var projected = scoped(c, scope, "carl_portfolio_move_view", id);
               projected.put("allocations", CarlService.rows(c, "SELECT account_id,account_revision,amount FROM carl_portfolio_allocation WHERE move_id=? ORDER BY account_id", id));
               result.add(projected);
            }
         }
         return result;
      });
   }



   /** Saves the actual selected baseline and candidate strategies over identical dated input facts. */
   public long compare(CarlService.Scope scope, UUID request, Set<Long> accounts, Set<Long> moves, LocalDate asOf,
      String currency, BigDecimal budget, int horizon, FinancialPlanning.Strategy rollover, String evidence)
   {
      ids(accounts, 100);
      ids(moves, 20);
      date(asOf);
      if(accounts.isEmpty() || horizon < 1 || horizon > 600 || rollover == null)
      {
         throw new IllegalArgumentException("Select debts, an explicit rollover assumption and a bounded horizon");
      }
      if((5L * accounts.size() + 4L * moves.size()) * horizon > 25_000)
      {
         throw new IllegalArgumentException("Narrow portfolio or horizon; maximum 25000 projected debt-period rows across strategies");
      }
      new FinancialPlanning.PaymentTarget(1, budget);
      CarlService.bounded(evidence, 4000, "budget assumption evidence");
      String digest = BillCsv.hash(CarlService.json(Map.of("operation", "PORTFOLIO_COMPARISON", "accounts", accounts.stream().sorted().toList(), "moves", moves.stream().sorted().toList(), "asOf", asOf, "currency", currency, "budget", budget, "horizon", horizon, "rollover", rollover, "evidence", evidence)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      var snapshot = service.transaction(c -> snapshot(c, scope, accounts, moves, asOf, currency, budget, horizon, evidence));
      var results = new LinkedHashMap<String, Object>();
      var resultGaps = new ArrayList<>(snapshot.gaps());
      if(snapshot.gaps().isEmpty())
      {
         var baseline = PortfolioRestructuring.project(snapshot.inputs(), List.of(), new PortfolioRestructuring.Policy(DebtPortfolio.Mode.CURRENT_PAYMENT, snapshot.current(), FinancialPlanning.Strategy.MINIMUM_ONLY));
         results.put("CURRENT_PAYMENT", baseline);
         calculationGaps(resultGaps, "CURRENT_PAYMENT", baseline);
         for(var mode : List.of(DebtPortfolio.Mode.MINIMUM_ONLY, DebtPortfolio.Mode.AVALANCHE, DebtPortfolio.Mode.SNOWBALL, DebtPortfolio.Mode.USER_DIRECTED))
         {
            var candidate = PortfolioRestructuring.project(snapshot.inputs(), snapshot.moves(), new PortfolioRestructuring.Policy(mode, mode == DebtPortfolio.Mode.USER_DIRECTED ? snapshot.proposed() : Map.of(), rollover));
            results.put(mode.name(), candidate);
            calculationGaps(resultGaps, mode.name(), candidate);
            results.put(mode.name() + "_VS_CURRENT", PortfolioRestructuring.compare(baseline, candidate));
         }
      }
      String facts = CarlService.json(Map.of("scope", "Selected accessible portfolio only", "asOf", asOf, "budgetQualification", "UNQUALIFIED_ASSUMPTION", "budgetEvidence", evidence, "sources", snapshot.assumptions(), "inputs", snapshot.inputs(), "comparisons", results, "gaps", resultGaps));
      if(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_000_000)
      {
         throw new IllegalArgumentException("Narrow portfolio or horizon; maximum report facts size is 2 MB");
      }
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", asOf, asOf.plusMonths(horizon), facts, "", "NOT_REQUESTED", LIMITS, snapshot.sources(), null, resultGaps.isEmpty() ? "Portfolio comparison — affordability unqualified" : "Incomplete portfolio comparison — review input gaps", digest, snapshot.epoch());
   }



   private static void calculationGaps(List<String> gaps, String label, PortfolioRestructuring.Projection value)
   {
      for(String gap : value.gaps())
      {
         gaps.add(label + ": " + gap);
      }
      if(!value.determined() || !value.feasible() || !value.paidOff())
      {
         gaps.add(label + ": incomplete payoff or infeasible/unsupported assumptions; horizon costs are not lifetime savings");
      }
   }



   private Snapshot snapshot(Connection c, CarlService.Scope scope, Set<Long> accounts, Set<Long> selectedMoves,
      LocalDate asOf, String currency, BigDecimal budget, int horizon, String evidence) throws SQLException
   {
      long epoch = lock(c, scope);
      var debts = new ArrayList<FinancialPlanning.Debt>();
      var dates = new LinkedHashMap<String, LocalDate>();
      var current = new LinkedHashMap<String, List<FinancialPlanning.PaymentTarget>>();
      var proposed = new LinkedHashMap<String, List<FinancialPlanning.PaymentTarget>>();
      var fees = new LinkedHashMap<String, List<FinancialPlanning.PaymentTarget>>();
      var sources = new LinkedHashMap<Long, Long>();
      var assumptions = new ArrayList<Map<String, Object>>();
      var gaps = new ArrayList<String>();
      int rateRows = 0;
      for(long id : accounts.stream().sorted().toList())
      {
         var account = scoped(c, scope, "carl_account_view", id);
         if(!currency.equals(account.get("currency")) || !Set.of("CREDIT_CARD", "LOAN").contains(account.get("kind")))
         {
            throw new IllegalArgumentException("Select same-currency debt accounts only");
         }
         sources.put(id, revision(c, id));
         var terms = CarlService.rows(c, "SELECT * FROM carl_debt_terms WHERE account_id=?", id);
         var profiles = CarlService.rows(c, "SELECT * FROM carl_debt_payment_profile WHERE account_id=?", id);
         var rates = CarlService.rows(c, "SELECT * FROM carl_debt_rate WHERE account_id=? ORDER BY effective_date LIMIT 1002", id);
         rateRows += rates.size();
         if(rates.size() > 1001 || rateRows > 2000)
         {
            throw new IllegalArgumentException("Debt rate history exceeds supported bound");
         }
         assumptions.add(Map.of("account", account, "terms", terms, "payments", profiles, "allKnownRatesAndFees", rates));
         if(terms.size() != 1 || terms.getFirst().get("principal_balance") == null || !asOf.toString().equals(terms.getFirst().get("balance_as_of").toString()) || profiles.size() != 1 || !asOf.toString().equals(profiles.getFirst().get("as_of").toString()))
         {
            gaps.add("Account " + id + " requires same-date balance and current/proposed payment evidence");
            continue;
         }
         Map<String, Object> initial = null;
         for(var rate : rates)
         {
            if(!LocalDate.parse(rate.get("effective_date").toString()).isAfter(asOf))
            {
               initial = rate;
            }
         }
         if(initial == null)
         {
            gaps.add("Account " + id + " lacks an effective starting APR/fee");
            continue;
         }
         var rateProfile = new ArrayList<FinancialPlanning.Rate>();
         var feeProfile = new ArrayList<FinancialPlanning.PaymentTarget>();
         rateProfile.add(new FinancialPlanning.Rate(1, (BigDecimal) initial.get("annual_rate")));
         feeProfile.add(new FinancialPlanning.PaymentTarget(1, (BigDecimal) initial.get("monthly_fee")));
         for(var rate : rates)
         {
            LocalDate effective = LocalDate.parse(rate.get("effective_date").toString());
            if(!effective.isAfter(asOf) || !effective.isBefore(asOf.plusMonths(horizon)))
            {
               continue;
            }
            int month = -1;
            for(int n = 1; n < horizon; n++)
            {
               if(asOf.plusMonths(n).equals(effective))
               {
                  month = n + 1;
                  break;
               }
            }
            if(month == -1)
            {
               gaps.add("Account " + id + " has an intra-period APR/fee change at " + effective + "; monthly timing cannot represent it");
            }
            else
            {
               rateProfile.add(new FinancialPlanning.Rate(month, (BigDecimal) rate.get("annual_rate")));
               feeProfile.add(new FinancialPlanning.PaymentTarget(month, (BigDecimal) rate.get("monthly_fee")));
            }
         }
         var term = terms.getFirst();
         var profile = profiles.getFirst();
         String key = Long.toString(id);
         debts.add(new FinancialPlanning.Debt(key, currency, (BigDecimal) term.get("principal_balance"), (BigDecimal) term.get("minimum_amount"), (BigDecimal) term.get("minimum_fraction"), rateProfile, (BigDecimal) initial.get("monthly_fee")));
         fees.put(key, feeProfile);
         dates.put(key, LocalDate.parse(profile.get("first_payment_date").toString()));
         current.put(key, List.of(new FinancialPlanning.PaymentTarget(1, (BigDecimal) profile.get("current_payment"))));
         proposed.put(key, List.of(new FinancialPlanning.PaymentTarget(1, (BigDecimal) profile.get("proposed_payment"))));
      }
      var moves = new ArrayList<PortfolioRestructuring.Move>();
      for(long id : selectedMoves.stream().sorted().toList())
      {
         var move = scoped(c, scope, "carl_portfolio_move_view", id);
         var offerRow = scoped(c, scope, "carl_financing_offer_view", CarlService.number(move, "offer_id"));
         sources.put(id, CarlService.number(move, "revision"));
         sources.put(CarlService.number(offerRow, "id"), CarlService.number(offerRow, "revision"));
         var allocations = CarlService.rows(c, "SELECT account_id,account_revision,amount FROM carl_portfolio_allocation WHERE move_id=? ORDER BY account_id", id);
         assumptions.add(Map.of("move", move, "offer", offerRow, "allocations", allocations));
         if(Boolean.TRUE.equals(move.get("source_stale")) || !asOf.toString().equals(move.get("as_of").toString()))
         {
            gaps.add("Move " + id + " requires a new human review after source/date changes");
         }
         if(asOf.isBefore(LocalDate.parse(offerRow.get("as_of").toString())) || asOf.isAfter(LocalDate.parse(offerRow.get("expires_on").toString())))
         {
            gaps.add("Offer " + offerRow.get("id") + " is not current at the selected date");
         }
         var selected = new LinkedHashMap<String, BigDecimal>();
         for(var allocation : allocations)
         {
            long account = CarlService.number(allocation, "account_id");
            if(!accounts.contains(account))
            {
               throw new IllegalArgumentException("Every allocated source must belong to the original selected portfolio");
            }
            selected.put(Long.toString(account), (BigDecimal) allocation.get("amount"));
         }
         var offer = offer(offerRow);
         moves.add(new PortfolioRestructuring.Move(offer, selected, (BigDecimal) move.get("capacity"), (BigDecimal) move.get("minimum_amount"), (BigDecimal) move.get("minimum_fraction"), (BigDecimal) move.get("monthly_fee"), LocalDate.parse(move.get("first_payment").toString()), move.get("evidence").toString()));
         proposed.put(offer.id(), List.of(new FinancialPlanning.PaymentTarget(1, (BigDecimal) move.get("proposed_payment"))));
      }
      var inputs = new PortfolioRestructuring.Inputs(currency, asOf, debts, dates, List.of(new FinancialPlanning.PaymentTarget(1, budget)), horizon, fees, evidence);
      return new Snapshot(epoch, inputs, moves, current, proposed, sources, assumptions, gaps);
   }



   private static FinancingScenarios.Offer offer(Map<String, Object> row)
   {
      var promotion = new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.valueOf(row.get("promotion").toString()), ((Number) row.get("promo_months")).intValue(), (BigDecimal) row.get("deferred_apr"), (Boolean) row.get("allocation_confirmed"));
      var rates = new ArrayList<FinancialPlanning.Rate>();
      rates.add(new FinancialPlanning.Rate(1, (BigDecimal) row.get("initial_apr")));
      if(promotion.kind() != FinancingScenarios.PromotionKind.NONE)
      {
         rates.add(new FinancialPlanning.Rate(promotion.months() + 1, (BigDecimal) row.get("post_promo_apr")));
      }
      return new FinancingScenarios.Offer("offer:" + row.get("id"), row.get("currency").toString(), (BigDecimal) row.get("financed_principal"), (BigDecimal) row.get("financed_fee"), (BigDecimal) row.get("cash_fee"), (BigDecimal) row.get("monthly_payment"), ((Number) row.get("term_months")).intValue(), rates, promotion, FinancingScenarios.Evidence.valueOf(row.get("evidence_class").toString()));
   }



   private static Map<String, Object> scoped(Connection c, CarlService.Scope scope, String view, long id) throws SQLException
   {
      var row = require(c, scope.principal(), view, id);
      row.remove("principal");
      for(String actor : scope.audience())
      {
         var other = require(c, actor, view, id);
         other.remove("principal");
         if(!row.equals(other))
         {
            throw new SecurityException("Portfolio input unavailable to audience");
         }
      }
      return row;
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=? AND id=?", principal, id);
      if(rows.size() != 1)
      {
         throw new SecurityException("Portfolio input unavailable");
      }
      return rows.getFirst();
   }



   private static long lock(Connection c, CarlService.Scope scope) throws SQLException
   {
      var actor = CarlService.member(c, scope.principal());
      long epoch = CarlService.number(CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", actor.householdId()).getFirst(), "permission_revision");
      for(String principal : scope.audience())
      {
         if(CarlService.member(c, principal).householdId() != actor.householdId())
         {
            throw new SecurityException("Portfolio audience unavailable");
         }
      }
      return epoch;
   }



   private static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var actor = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", actor.householdId());
      var current = CarlService.manager(c, principal, "FINANCE");
      if(current.householdId() != actor.householdId())
      {
         throw new SecurityException("Portfolio manager scope changed");
      }
      return current;
   }



   private static long revision(Connection c, long id) throws SQLException
   {
      return CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", id).getFirst(), "revision");
   }



   private static void date(LocalDate value)
   {
      if(value == null || value.getYear() < 1900 || value.getYear() > 2200)
      {
         throw new IllegalArgumentException("Explicit supported as-of date required");
      }
   }



   private static void ids(Set<Long> values, int maximum)
   {
      if(values == null || values.size() > maximum || values.stream().anyMatch(id -> id == null || id <= 0))
      {
         throw new IllegalArgumentException("Bounded distinct persisted identities required");
      }
   }
}
