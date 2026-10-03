/* Copyright (C) 2026 KofTwentyTwo */
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Read-only native dashboards over current explicit permissions and authoritative financial sources. */
public final class DashboardFacts
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
   private final CarlService service;

   /** Uses the consumer's authoritative service and bounded synthetic-test clock. */
   public DashboardFacts(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** Authorizes dropdowns before any protected selection labels are loaded; no dashboard cache. */
   public void requireAccess(CarlService.Scope scope)
   {
      service.transaction(c ->
      {
         authorize(c, scope);
         return null;
      });
   }



   /** Exact directional category totals are shared by the cash-flow table and Sankey chart. */
   public Map<String, Object> cashFlow(CarlService.Scope scope, LocalDate from, LocalDate through, String currency)
   {
      if(currency == null || from == null || through == null || from.getYear() < 1900 || through.getYear() > 2200
         || through.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, through) >= 366)
      {
         throw new IllegalArgumentException("Select an explicit currency and an inclusive reporting interval of at most 366 days");
      }
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Unsupported currency precision");
      }
      return service.transaction(c ->
      {
         authorize(c, scope);
         var parameters = new ArrayList<Object>(List.of(scope.principal(), from, through, currency));
         String permitted = intersection(scope, "carl_transaction_view", "t", parameters);
         var transactions = CarlService.rows(c, "SELECT t.*,ac.kind,ac.kind AS account_kind,ac.review_state,ac.ownership_share,ac.liquid FROM carl_transaction_view t JOIN carl_account_view ac ON ac.id=t.account_id AND ac.principal=t.principal WHERE t.principal=? AND t.effective_date>=? AND t.effective_date<=? AND t.currency=?" + permitted + " ORDER BY t.id LIMIT 100001", parameters.toArray());
         if(transactions.size() > 100000)
         {
            throw new IllegalArgumentException("Narrow the reporting interval; at most 100000 transaction records");
         }
         var excluded = new java.util.HashSet<Long>();
         var gaps = new java.util.LinkedHashSet<String>();
         var pairs = new java.util.HashMap<String, List<Map<String, Object>>>();
         for(var row : transactions)
         {
            if("TRANSFER".equals(row.get("classification")) && row.get("transfer_key") != null)
            {
               pairs.computeIfAbsent(row.get("transfer_key").toString(), ignored -> new ArrayList<>()).add(row);
            }
            else if("TRANSFER".equals(row.get("classification")))
            {
               gaps.add("Transfer movement has no matched pair identifier; retained as a cash movement exception.");
            }
         }
         for(var pair : pairs.values())
         {
            if(pair.size() == 2 && ((BigDecimal) pair.get(0).get("amount")).add((BigDecimal) pair.get(1).get("amount")).signum() == 0
               && CarlService.number(pair.get(0), "account_id") != CarlService.number(pair.get(1), "account_id")
               && "CASH".equals(pair.get(0).get("account_kind")) && "CASH".equals(pair.get(1).get("account_kind"))
               && BalanceSheets.reviewedAccount(pair.get(0)) && BalanceSheets.reviewedAccount(pair.get(1)))
            {
               pair.forEach(row -> excluded.add(CarlService.number(row, "id")));
            }
            else
            {
               gaps.add("Transfer pair is not fully visible and matched between cash accounts in this selected interval; retained as a cash movement exception, excluded from ordinary income/expense classification. Cash legs of bank-to-card/loan/investment transfers remain in cash movement totals; principal/interest allocation and capital treatment are unknown without explicit classification.");
            }
         }
         var totals = new java.util.TreeMap<String, Map<String, Object>>();
         var nonCash = new java.util.TreeMap<String, Map<String, Object>>();
         var unreviewed = new java.util.TreeMap<String, Map<String, Object>>();
         int unreviewedRecords = 0;
         BigDecimal zero = BigDecimal.ZERO.setScale(scale);
         BigDecimal inflows = zero;
         BigDecimal outflows = zero;
         BigDecimal spending = zero;
         BigDecimal refunds = zero;
         int unclassified = 0;
         LocalDate newest = null;
         for(var row : transactions)
         {
            LocalDate dated = LocalDate.parse(row.get("effective_date").toString());
            newest = newest == null || newest.isBefore(dated) ? dated : newest;
            if(excluded.contains(CarlService.number(row, "id")))
            {
               continue;
            }
            BigDecimal amount = ((BigDecimal) row.get("amount")).setScale(scale, java.math.RoundingMode.UNNECESSARY);
            String classification = row.get("classification").toString();
            if(classification.equals("UNCLASSIFIED"))
            {
               unclassified++;
            }
            boolean reviewed = BalanceSheets.reviewedAccount(row);
            if(!reviewed)
            {
               unreviewedRecords++;
            }
            if(amount.signum() == 0)
            {
               continue;
            }
            boolean incoming = amount.signum() > 0;
            if(reviewed && "EXPENSE".equals(classification))
            {
               if(incoming)
               {
                  refunds = refunds.add(amount);
               }
               else
               {
                  spending = spending.subtract(amount);
               }
            }
            boolean cash = reviewed && "CASH".equals(row.get("account_kind"));
            if(cash && incoming)
            {
               inflows = inflows.add(amount);
            }
            else if(cash)
            {
               outflows = outflows.subtract(amount);
            }
            String category = row.get("category").toString();
            String direction = incoming ? "INFLOW" : "OUTFLOW";
            String key = row.get("account_kind") + "\u0000" + classification + "\u0000" + category + "\u0000" + direction;
            var group = (!reviewed ? unreviewed : cash ? totals : nonCash).computeIfAbsent(key, ignored -> new LinkedHashMap<>(Map.of("classification", classification, "category", category, "direction", direction, "accountKind", row.get("account_kind"),
               "label", (reviewed ? label(classification, incoming) : "Unreviewed account source movement") + ": " + category, "amount", zero, "records", 0L)));
            group.put("amount", ((BigDecimal) group.get("amount")).add(amount.abs()));
            group.put("records", ((Long) group.get("records")) + 1);
         }
         if(totals.size() + nonCash.size() + unreviewed.size() > 1000)
         {
            throw new IllegalArgumentException("Narrow the selected period; at most 1000 distinct directional categories");
         }
         if(unclassified > 0)
         {
            gaps.add("Unclassified records: " + unclassified + "; sign alone never establishes income, spending or taxable treatment.");
         }
         if(transactions.isEmpty())
         {
            gaps.add("No accessible transactions in this currency and interval; absence does not establish confirmed household activity.");
         }
         gaps.add("Source coverage is unconfirmed; these figures describe accessible records only. Imported timestamps and current classification do not prove complete account coverage.");
         var result = new LinkedHashMap<String, Object>();
         result.put("from", from.toString());
         result.put("through", through.toString());
         result.put("currency", currency);
         result.put("displayZone", CarlService.member(c, scope.principal()).zone().toString());
         result.put("generatedAt", service.reportTime().toString());
         result.put("latestMovementDate", newest == null ? null : newest.toString());
         boolean accountReviewPending = unreviewedRecords > 0;
         result.put("inflows", accountReviewPending ? null : inflows);
         result.put("outflows", accountReviewPending ? null : outflows);
         result.put("netMovement", accountReviewPending ? null : inflows.subtract(outflows));
         result.put("knownReviewedCashInflows", inflows);
         result.put("knownReviewedCashOutflows", outflows);
         result.put("unreviewedAccountRecords", unreviewedRecords);
         result.put("unreviewedAccountMovements", List.copyOf(unreviewed.values()));
         if(accountReviewPending)
         {
            gaps.add("Account review required for " + unreviewedRecords + " source movements; cash totals are unknown until kind, ownership and liquidity are confirmed. These records are neither qualified cash nor non-cash movements and remain separately visible.");
         }
         result.put("classifiedSpendingAllAccountKinds", accountReviewPending ? null : spending);
         result.put("classifiedExpenseRefundsAllAccountKinds", accountReviewPending ? null : refunds);
         result.put("classifiedNetSpendingAllAccountKinds", accountReviewPending ? null : spending.subtract(refunds));
         result.put("records", transactions.size());
         result.put("excludedTransferLegs", excluded.size());
         result.put("flows", List.copyOf(totals.values()));
         result.put("nonCashAccountMovements", List.copyOf(nonCash.values()));
         if(!nonCash.isEmpty())
         {
            gaps.add("Non-cash account movements are listed separately and excluded from cash totals; credit-card charges, loan and investment ledger amounts are not spendable cash.");
         }
         result.put("gaps", List.copyOf(gaps));
         result.put("scope", "Accessible records in the explicitly selected audience intersection; partial coverage, not complete household cash flow.");
         result.put("definition", "Signed CASH-kind account movements only, without an inferred opening cash balance. Other account kinds are listed separately and excluded from cash totals. Borrowing/principal reversals and capital proceeds remain separate from classified ordinary income; refunds and reversals retain their direction. Internal transfers are excluded only when both matched CASH-kind legs are authorized and inside this interval. Available credit and net worth are not spendable cash.");
         bounded(result);
         return result;
      });
   }



   /** Bounded labels from the current protected audience intersection; saved history remains visibly stale. */
   public List<SavedBalanceSheet> savedBalanceSheets(CarlService.Scope scope, Long selectedId)
   {
      if(selectedId != null && selectedId <= 0)
      {
         throw new IllegalArgumentException("Positive saved balance-sheet ID required");
      }
      return service.transaction(c ->
      {
         authorize(c, scope);
         var parameters = new ArrayList<Object>(List.of(scope.principal()));
         String selected = "";
         if(selectedId != null)
         {
            selected = " AND v.id=?";
            parameters.add(selectedId);
         }
         var rows = CarlService.rows(c, "SELECT v.id,v.facts::jsonb->>'asOf' AS as_of,v.stale,v.status_label FROM carl_artifact_view v WHERE v.principal=? AND v.kind='FINANCIAL_PLAN' AND v.facts::jsonb->>'calculationVersion'='selected-balance-sheet-v1'" + selected + intersection(scope, "carl_artifact_view", "v", parameters) + " ORDER BY v.id DESC LIMIT 1001", parameters.toArray());
         if(rows.size() > 1000)
         {
            throw new IllegalArgumentException("Select a saved balance sheet by ID; more than 1000 eligible saved reports");
         }
         var choices = new ArrayList<SavedBalanceSheet>();
         for(var row : rows)
         {
            String asOf = LocalDate.parse(row.get("as_of").toString()).toString();
            String status = row.get("status_label").toString();
            if(status.length() > 200 || status.chars().anyMatch(Character::isISOControl))
            {
               throw new IllegalArgumentException("Invalid saved balance-sheet status label");
            }
            choices.add(new SavedBalanceSheet(((Number) row.get("id")).longValue(), asOf, Boolean.parseBoolean(row.get("stale").toString()), status));
         }
         return List.copyOf(choices);
      });
   }

   /** Only the fields needed for native choices; no narrative or underlying source facts. */
   public record SavedBalanceSheet(long id, String asOf, boolean stale, String status)
   {
   }

   /** Presents an explicitly selected immutable balance-sheet calculation without mutating state on reads. */
   public Map<String, Object> balanceSheet(CarlService.Scope scope, long artifact)
   {
      return service.transaction(c ->
      {
         authorize(c, scope);
         var saved = artifact(c, scope, artifact);
         JsonNode facts = parse(saved);
         if(!"selected-balance-sheet-v1".equals(facts.path("calculationVersion").asText()))
         {
            throw new IllegalArgumentException("Select a saved balance sheet created from explicit account/property valuation choices");
         }
         return presentation(saved, facts);
      });
   }



   /** Separates current human tasks, saved projections and scoped observed-effect reports. */
   public Map<String, Object> plan(CarlService.Scope scope, long plan)
   {
      return service.transaction(c ->
      {
         authorize(c, scope);
         Map<String, Object> current = null;
         for(String recipient : scope.audience())
         {
            var rows = CarlService.rows(c, "SELECT * FROM carl_plan_view WHERE principal=? AND id=?", recipient, plan);
            if(rows.size() != 1)
            {
               throw new SecurityException("Plan unavailable to current audience");
            }
            current = rows.getFirst();
         }
         if(current == null)
         {
            throw new SecurityException("Explicit audience required");
         }
         long source = CarlService.number(current, "source_artifact_id");
         var sourceArtifact = artifact(c, scope, source);
         var steps = CarlService.rows(c, "SELECT * FROM carl_plan_step WHERE plan_id=? ORDER BY due_date,id LIMIT 101", plan);
         if(steps.size() > 100)
         {
            throw new IllegalArgumentException("Plan exceeds the supported task limit");
         }
         var args = new ArrayList<Object>(List.of(scope.principal(), plan));
         String permitted = intersection(scope, "carl_plan_effect_view", "e", args);
         var expectations = CarlService.rows(c, "SELECT e.* FROM carl_plan_effect_view e WHERE e.principal=? AND e.plan_id=?" + permitted + " ORDER BY e.id LIMIT 101", args.toArray());
         if(expectations.size() > 100)
         {
            throw new IllegalArgumentException("Narrow plan expectations before viewing this dashboard");
         }
         var observations = new ArrayList<Map<String, Object>>();
         var candidates = CarlService.rows(c, "SELECT v.* FROM carl_artifact_view v WHERE v.principal=? AND v.kind='FINANCIAL_PLAN' AND v.facts::jsonb->>'calculationVersion'='plan-effects-v1' AND v.facts::jsonb->'expectation'->>'plan_id'=? ORDER BY v.id DESC LIMIT 101", scope.principal(), Long.toString(plan));
         if(candidates.size() > 100)
         {
            throw new IllegalArgumentException("Narrow plan observations before viewing this dashboard");
         }
         for(var candidate : candidates)
         {
            long id = CarlService.number(candidate, "id");
            boolean permittedToAll = true;
            for(String recipient : scope.audience())
            {
               permittedToAll &= !CarlService.rows(c, "SELECT id FROM carl_artifact_view WHERE principal=? AND id=?", recipient, id).isEmpty();
            }
            if(permittedToAll)
            {
               var observedFacts = parse(candidate);
               var observation = presentation(candidate, observedFacts);
               JsonNode savedVersion = observedFacts.path("expectation").path("plan_version");
               observation.put("currentPlanVersion", current.get("version"));
               observation.put("currentPlanVersionMatches", savedVersion.isIntegralNumber() ? savedVersion.asLong() == CarlService.number(current, "version") : null);
               observations.add(observation);
            }
         }
         var goals = new ArrayList<Object>(List.of(scope.principal()));
         String selectedGoals = intersection(scope, "carl_financial_goal_view", "g", goals);
         var currentGoals = CarlService.rows(c, "SELECT g.* FROM carl_financial_goal_view g WHERE g.principal=?" + selectedGoals + " ORDER BY g.priority,g.id LIMIT 101", goals.toArray());
         if(currentGoals.size() > 100)
         {
            throw new IllegalArgumentException("Narrow goal records before viewing this dashboard");
         }
         var result = new LinkedHashMap<String, Object>();
         current.remove("principal");
         result.put("plan", current);
         result.put("steps", steps);
         result.put("currentGoals", currentGoals);
         result.put("projected", presentation(sourceArtifact, parse(sourceArtifact)));
         result.put("expectations", expectations);
         result.put("observations", observations);
         result.put("generatedAt", service.reportTime().toString());
         result.put("scope", "Current accessible plan audience; projections, human reports, selected observations and calendar checkboxes are distinct evidence.");
         result.put("boundary", "Reported completion does not verify payment or debt reduction. Observation matches do not agree or execute a plan. Changed goals or source facts require explicit regeneration and rebase.");
         bounded(result);
         return result;
      });
   }



   private static String intersection(CarlService.Scope scope, String view, String alias, List<Object> values)
   {
      StringBuilder result = new StringBuilder();
      for(String recipient : scope.audience().stream().sorted().toList())
      {
         result.append(" AND EXISTS(SELECT 1 FROM ").append(view).append(" visible WHERE visible.id=").append(alias).append(".id AND visible.principal=?)");
         values.add(recipient);
      }
      return result.toString();
   }



   private static void authorize(Connection c, CarlService.Scope scope) throws SQLException
   {
      BalanceSheets.lock(c, scope);
      for(String recipient : scope.audience())
      {
         var actor = CarlService.member(c, recipient);
         if(CarlService.rows(c, "SELECT member_id FROM carl_permission WHERE member_id=? AND domain='FINANCE' AND details", actor.id()).isEmpty())
         {
            throw new SecurityException("Current financial dashboard access required");
         }
         if(recipient.equals(scope.principal()))
         {
            NativeReadScope.check(actor);
         }
      }
   }



   private static Map<String, Object> artifact(Connection c, CarlService.Scope scope, long id) throws SQLException
   {
      Map<String, Object> saved = null;
      for(String recipient : scope.audience())
      {
         var found = CarlService.rows(c, "SELECT * FROM carl_artifact_view WHERE principal=? AND id=? AND kind='FINANCIAL_PLAN'", recipient, id);
         if(found.size() != 1)
         {
            throw new SecurityException("Selected financial artifact unavailable");
         }
         saved = found.getFirst();
      }
      if(saved == null)
      {
         throw new SecurityException("Explicit audience required");
      }
      return saved;
   }



   private Map<String, Object> presentation(Map<String, Object> saved, JsonNode facts)
   {
      var result = new LinkedHashMap<String, Object>();
      for(String field : List.of("id", "created_at", "period_start", "period_end", "status_label", "stale", "limitations"))
      {
         result.put(field, saved.get(field) == null ? null : saved.get(field).toString());
      }
      result.put("facts", facts);
      result.put("generatedAt", service.reportTime().toString());
      bounded(result);
      return result;
   }



   private static JsonNode parse(Map<String, Object> saved)
   {
      try
      {
         return JSON.readTree(saved.get("facts").toString());
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalArgumentException("Saved financial facts are invalid", invalid);
      }
   }



   private static void bounded(Object value)
   {
      if(CarlService.json(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
      {
         throw new IllegalArgumentException("Dashboard exceeds 256 KiB; narrow the interval, plan or selected balance-sheet sources");
      }
   }



   private static String label(String classification, boolean incoming)
   {
      return switch(classification)
      {
         case "INCOME" -> incoming ? "Ordinary income" : "Income reversal";
         case "EXPENSE" -> incoming ? "Expense refund" : "Ordinary expenses";
         case "DEBT_PRINCIPAL" -> incoming ? "Borrowing or principal reversals" : "Debt principal";
         case "DEBT_INTEREST" -> incoming ? "Interest refund" : "Debt interest";
         case "CAPITAL" -> incoming ? "Capital proceeds" : "Capital movements";
         case "TRANSFER" -> "Transfer exception";
         default -> "Unclassified";
      };
   }
}
