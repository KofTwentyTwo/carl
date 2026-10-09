/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


/** Source-linked preparation plans that preserve unknown facts instead of inventing payoff assumptions. */
public final class ReadinessPlans
{
   private static final Map<String, String> VIEWS = Map.ofEntries(
      Map.entry("accounts", "carl_account_view"), Map.entry("debts", "carl_debt_view"),
      Map.entry("budgets", "carl_budget_view"), Map.entry("cashPlans", "carl_cash_plan_view"),
      Map.entry("financialGoals", "carl_financial_goal_view"), Map.entry("properties", "carl_rental_property_view"),
      Map.entry("rentalUnits", "carl_rental_unit_view"), Map.entry("expenses", "carl_expense_view"),
      Map.entry("financingOffers", "carl_financing_offer_view"), Map.entry("taxProperties", "carl_tax_property_view"),
      Map.entry("documents", "carl_document_view"));
   private final CarlService service;

   /** Uses the same records, permissions and persisted plan lifecycle as financial comparisons. */
   public ReadinessPlans(CarlService service)
   {
      this.service = Objects.requireNonNull(service);
   }

   /** The saved evidence and its unagreed, human-led plan; neither implies financial execution. */
   public record Result(long artifact, long plan)
   {
   }

   /** Generates a private draft from current records, without fabricated terms, offers or task dates. */
   public Result generate(CarlService.Scope scope, UUID request, String title)
   {
      Objects.requireNonNull(request);
      CarlService.bounded(title, 500, "Readiness plan title");
      if(scope.audience().size() != 1)
      {
         throw new IllegalArgumentException("Readiness plan generation is requester-only; sharing requires an explicit authorized workflow");
      }
      return service.transaction(c -> generate(c, scope, request, title));
   }



   private Result generate(Connection c, CarlService.Scope scope, UUID request, String title) throws SQLException
   {
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      var member = CarlService.manager(c, scope.principal(), "FINANCE");
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", member.householdId());
      Long previous = CarlService.request(c, member, request, "GENERATE_READINESS_PLAN", BillCsv.hash(title));
      if(previous != null)
      {
         var saved = CarlService.rows(c, "SELECT source_artifact_id FROM carl_plan_view WHERE principal=? AND id=?", scope.principal(), previous);
         if(saved.size() != 1)
         {
            throw new SecurityException("Readiness plan unavailable");
         }
         return new Result(CarlService.number(saved.getFirst(), "source_artifact_id"), previous);
      }
      var sources = new LinkedHashMap<Long, Long>();
      var inventory = new LinkedHashMap<String, Integer>();
      var records = new LinkedHashMap<String, List<Map<String, Object>>>();
      for(String kind : List.of("accounts", "debts", "budgets", "cashPlans", "financialGoals", "properties", "rentalUnits", "expenses", "financingOffers", "taxProperties", "documents"))
      {
         var found = CarlService.rows(c, "SELECT v.*,r.revision AS source_record_revision FROM " + VIEWS.get(kind) + " v JOIN carl_record r ON r.id=v.id WHERE v.principal=? ORDER BY v.id LIMIT 1001", scope.principal());
         if(found.size() > 1000)
         {
            throw new IllegalArgumentException("Readiness inventory exceeds 1000 records in one domain; use a narrower review scope");
         }
         records.put(kind, found);
         inventory.put(kind, found.size());
         for(var row : found)
         {
            long id = CarlService.number(row, "id");
            long revision = CarlService.number(row, "source_record_revision");
            row.remove("principal");
            row.remove("source_record_revision");
            sources.put(id, revision);
         }
      }
      var reviews = new ArrayList<Map<String, Object>>();
      for(var account : records.get("accounts"))
      {
         if(!"CONFIRMED".equals(account.get("review_state")))
         {
            reviews.add(Map.of("recordId", account.get("id"), "sourceLabel", account.get("title"),
               "requiredFacts", List.of("Economic account identity and any aliases", "Account owner and ownership share", "Account kind and liquidity", "Savings bucket parent, if applicable")));
         }
      }
      var gaps = new ArrayList<String>();
      if(!reviews.isEmpty())
      {
         gaps.add("Review imported account identities, ownership, aliases and savings bucket parents before qualified household totals.");
      }
      if(inventory.get("debts") == 0)
      {
         gaps.add("Supply current debt statement principal, APR, minimum-payment rule, fees and promotional expiration; transaction signs are not debt terms.");
      }
      if(inventory.get("cashPlans") == 0)
      {
         gaps.add("Confirm opening spendable cash, protected reserve, recurring income and expenses, and total debt-service budget; available credit is never budget.");
      }
      if(inventory.get("budgets") == 0 || inventory.get("expenses") == 0)
      {
         gaps.add("Review recurring expenses and category budgets; historical transactions do not establish approved future budgets or bill due dates.");
      }
      if(inventory.get("properties") == 0 || inventory.get("rentalUnits") == 0)
      {
         gaps.add("Supply property and unit identity, ownership, mortgage terms, rents, taxes, insurance, maintenance and reserve facts before rental plans.");
      }
      if(inventory.get("financialGoals") == 0)
      {
         gaps.add("Record the owner's financial priorities; the model cannot select them on the owner's behalf.");
      }
      gaps.add("This preparation plan does not establish a complete debt inventory, payoff date, purchase budget, tax recommendation or agreed execution schedule.");
      if(inventory.get("documents") > 0)
      {
         gaps.add("Supplied historical documents are unverified supporting evidence; they do not establish present debt terms, ownership, cash or tax treatment.");
      }
      var facts = new LinkedHashMap<String, Object>();
      facts.put("planType", "FINANCIAL_READINESS");
      facts.put("title", title);
      facts.put("scope", "Currently permitted local records; household coverage is not assumed complete");
      facts.put("inventory", inventory);
      facts.put("accountReview", reviews);
      facts.put("confirmedPriorities", records.get("financialGoals"));
      facts.put("suppliedDocuments", records.get("documents"));
      facts.put("gaps", gaps);
      facts.put("milestones", List.of("Review account identity and reconcile source evidence", "Collect dated debt terms and recurring expenses", "Confirm cash reserve and sustainable debt-service budget", "Compare payoff scenarios using confirmed inputs", "Document rental and tax facts", "Agree assignments and dates before execution"));
      facts.put("execution", "Human-led draft; no financial/vendor actions, assignments, deadlines or commitments have been made");
      String narrative = "Financial readiness draft\n\n" + String.join("\n", gaps);
      UUID artifactRequest = UUID.nameUUIDFromBytes(("carl-readiness-artifact:" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      long artifact = service.saveArtifact(c, scope, artifactRequest, "FINANCIAL_PLAN", null, null, CarlService.json(facts), narrative,
         "NOT_REQUESTED", String.join(" ", gaps), sources, null, "Incomplete financial readiness — human review required", BillCsv.hash(title), member.permissionRevision());
      UUID planRequest = UUID.nameUUIDFromBytes(("carl-readiness-plan:" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      long plan = new PlanLifecycle(service).create(c, scope.principal(), planRequest, artifact, title, "Explicit human readiness-generation request; financial assumptions and task dates remain unconfirmed");
      CarlService.complete(c, request, plan, "PARTIAL", "Source-linked readiness draft saved; missing facts require human review");
      return new Result(artifact, plan);
   }
}
