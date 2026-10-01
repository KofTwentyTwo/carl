/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


/** Saved educational tradeoffs retain distinct debt costs, uncertain returns and explicit owner goals. */
public final class GoalTradeoffs
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private static final Set<String> STRATEGIES = Set.of("CURRENT_PAYMENT", "MINIMUM_ONLY", "AVALANCHE", "SNOWBALL", "USER_DIRECTED");
   private static final String LIMITS = "Educational assumptions only; affordability remains unqualified. Debt costs are conditional arithmetic under supplied contract terms; investment growth is uncertain and may be negative. Horizon costs are not lifetime savings. No net-return optimizer, guaranteed yield, suitability decision, product recommendation, professional approval, order, payment or transfer. Owner priorities and investment-stage readiness are explicit; no universal debt-free prerequisite is inferred.";
   private final CarlService service;

   /** Shares authoritative records, current permissions and immutable financial artifacts. */
   public GoalTradeoffs(CarlService service)
   {
      this.service = service;
   }



   /** Combines two saved scenarios only for their exact existing audience and stated assumptions. */
   public long compare(CarlService.Scope scope, UUID request, long debtArtifact, long investmentArtifact,
      String debtStrategy, Set<Long> goals, String evidence)
   {
      return service.transaction(c -> compare(c, scope, request, debtArtifact, investmentArtifact, debtStrategy, goals, evidence));
   }



   /** Persists the same guarded comparison in an existing completion transaction; callers own its commit or rollback. */
   long compare(Connection c, CarlService.Scope scope, UUID request, long debtArtifact, long investmentArtifact,
      String debtStrategy, Set<Long> goals, String evidence) throws SQLException
   {
      CarlService.bounded(evidence, 4000, "tradeoff evidence");
      if(scope == null || request == null || debtStrategy == null || !STRATEGIES.contains(debtStrategy)
         || goals == null || goals.isEmpty() || goals.size() > 20 || goals.stream().anyMatch(id -> id == null || id < 1)
         || evidence == null || evidence.isBlank() || debtArtifact == investmentArtifact)
      {
         throw new IllegalArgumentException("Select saved debt/investment scenarios, a supported strategy, one to twenty goals and explicit evidence");
      }
      Set<Long> selected = Set.copyOf(goals);
      String digest = BillCsv.hash(CarlService.json(Map.of("operation", "GOAL_TRADEOFF", "debt", debtArtifact, "investment", investmentArtifact,
         "strategy", debtStrategy, "goals", selected.stream().sorted().toList(), "evidence", evidence, "audience", scope.audience().stream().sorted().toList())));
      var actor = CarlService.member(c, scope.principal());
      var household = CarlService.rows(c, "SELECT revision,permission_revision FROM carl_household WHERE id=? FOR UPDATE", actor.householdId()).getFirst();
      actor = CarlService.member(c, scope.principal());
      var debt = child(c, scope, actor.householdId(), debtArtifact);
      var investment = child(c, scope, actor.householdId(), investmentArtifact);
      var sources = new TreeMap<Long, Long>();
      flatten(c, debtArtifact, sources);
      flatten(c, investmentArtifact, sources);
      var priorities = new ArrayList<Map<String, Object>>();
      for(long goal : selected.stream().sorted().toList())
      {
         for(String recipient : scope.audience())
         {
            CarlService.requireRecord(c, recipient, goal);
         }
         var found = CarlService.rows(c, "SELECT * FROM carl_financial_goal_view WHERE principal=? AND id=?", scope.principal(), goal);
         if(found.size() != 1)
         {
            throw new SecurityException("Goal unavailable to comparison audience");
         }
         priorities.add(found.getFirst());
         source(sources, goal, CarlService.number(found.getFirst(), "revision"));
      }
      priorities.sort(Comparator.<Map<String, Object>>comparingInt(row -> ((Number) row.get("priority")).intValue()).thenComparingLong(row -> CarlService.number(row, "id")));
      for(var entry : sources.entrySet())
      {
         for(String recipient : scope.audience())
         {
            CarlService.requireRecord(c, recipient, entry.getKey());
         }
         var current = CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=? FOR SHARE", entry.getKey());
         if(current.size() != 1 || CarlService.number(current.getFirst(), "revision") != entry.getValue())
         {
            throw new IllegalArgumentException("Regenerate stale child assumptions before comparing goals");
         }
      }
      var debtFacts = facts(debt);
      var investmentFacts = facts(investment);
      JsonNode projection = investmentFacts.path("projection");
      if(!debtFacts.path("comparisons").isObject() || !projection.isObject() || !projection.has("ownerContext") || !projection.has("assumedNetGrowth"))
      {
         throw new IllegalArgumentException("Choose a saved debt comparison and educational investment scenario");
      }
      Long prior = CarlService.request(c, actor, request, "GOAL_TRADEOFF", digest);
      if(prior != null)
      {
         child(c, scope, actor.householdId(), prior);
         return prior;
      }
      var reasons = new ArrayList<String>();
      var debtInputs = debtFacts.path("inputs");
      boolean portfolio = debtInputs.isObject();
      JsonNode currency = portfolio ? debtInputs.path("currency") : debtFacts.path("currency");
      if(!currency.isTextual() || !currency.equals(projection.path("currency")))
      {
         reasons.add("Different or missing currency assumptions");
      }
      LocalDate start = date(portfolio ? debtInputs.path("asOf") : debtFacts.path("asOf"));
      LocalDate investmentStart = month(projection.path("firstMonth"));
      if(start == null || investmentStart == null || !start.equals(investmentStart))
      {
         reasons.add("Different or missing start dates; monthly investment contributions occur at month end");
      }
      int horizon = portfolio ? debtInputs.path("horizon").asInt(0) : horizon(debt, start);
      int investmentHorizon = projection.path("requestedMonths").asInt(0);
      if(horizon < 1 || horizon != investmentHorizon)
      {
         reasons.add("Different or missing horizon assumptions");
      }
      BigDecimal budget = budget(debtFacts, portfolio, horizon);
      var months = projection.path("months");
      if(budget == null || !months.isArray() || months.size() != investmentHorizon)
      {
         reasons.add("Missing or incomplete comparable monthly budget/contribution schedule");
      }
      else
      {
         for(var period : months)
         {
            if(!period.path("contribution").isNumber() || budget.compareTo(period.path("contribution").decimalValue()) != 0)
            {
               reasons.add("Different monthly budget and investment contribution assumptions");
               break;
            }
         }
      }
      if(CarlService.number(debt, "source_revision") != CarlService.number(investment, "source_revision"))
      {
         reasons.add("Different household source snapshots; regenerate both scenarios from the same snapshot");
      }
      gaps(reasons, debtFacts.path("gaps"), "Debt input: ");
      gaps(reasons, debtFacts.path("missingInputs"), "Debt input: ");
      if(Set.of("CURRENT_PAYMENT", "USER_DIRECTED").contains(debtStrategy))
      {
         gaps(reasons, debtFacts.path("additionalPaymentStrategyGaps"), "Payment strategy: ");
      }
      gaps(reasons, projection.path("gaps"), "Investment context: ");
      if(!"PROJECTED_UNDER_ASSUMPTIONS".equals(projection.path("projectionStatus").asText()))
      {
         reasons.add("Incomplete investment projection under supplied cost assumptions");
      }
      var strategy = debtFacts.path("comparisons").path(debtStrategy);
      var payoff = strategy.has("payoff") ? strategy.path("payoff") : strategy;
      if(!payoff.isObject() || !payoff.path("interest").isNumber() || !payoff.path("feasible").asBoolean()
         || payoff.has("determined") && !payoff.path("determined").asBoolean())
      {
         reasons.add("Selected debt strategy is missing, infeasible or incomplete");
      }
      for(var offer : CarlService.rows(c, "SELECT o.record_id,o.as_of,o.expires_on FROM carl_financing_offer o JOIN carl_artifact_source s ON s.source_id=o.record_id WHERE s.artifact_id=?", debtArtifact))
      {
         LocalDate today = service.reportTime().atZone(actor.zone()).toLocalDate();
         if(today.isBefore(LocalDate.parse(offer.get("as_of").toString())) || today.isAfter(LocalDate.parse(offer.get("expires_on").toString())))
         {
            reasons.add("Financing offer " + offer.get("record_id") + " is expired or not current at comparison time");
         }
      }
      ObjectNode debtCosts = summary(payoff, List.of("interest", "fees", "monthlyFees", "financedFees", "upfrontCashFees", "originalPrincipal", "paidOff", "feasible", "determined", "negativeAmortization"));
      debtCosts.put("strategy", debtStrategy);
      debtCosts.put("costBasis", "CONDITIONAL_CONTRACT_COSTS_OVER_REQUESTED_HORIZON");
      ObjectNode uncertain = summary(projection, List.of("currency", "firstMonth", "requestedMonths", "assumptions", "ownerContext", "endingHypotheticalValue", "contributions", "fees", "assumedNetGrowth", "projectionStatus", "ownerSelectedInvestmentStage", "gaps", "limitations"));
      uncertain.put("growthBasis", "UNCERTAIN_ASSUMPTION_MAY_LOSE_VALUE");
      var result = new LinkedHashMap<String, Object>();
      result.put("calculationVersion", "goal-tradeoffs-v1");
      result.put("debtArtifact", debtArtifact);
      result.put("investmentArtifact", investmentArtifact);
      result.put("goals", priorities);
      result.put("comparisonStatus", reasons.isEmpty() ? "COMPARABLE_UNDER_ASSUMPTIONS" : "INCOMPARABLE");
      result.put("incomparableReasons", reasons.stream().distinct().toList());
      result.put("debtKnownCosts", debtCosts);
      result.put("investmentUncertainGrowth", uncertain);
      result.put("budgetQualification", "UNQUALIFIED_ASSUMPTION");
      result.put("recommendationStatus", "EDUCATIONAL_ONLY");
      result.put("evidence", evidence);
      result.put("limitations", LIMITS);
      String encoded = CarlService.json(result);
      if(encoded.getBytes(StandardCharsets.UTF_8).length > 131072)
      {
         throw new IllegalArgumentException("Tradeoff report exceeds 128 KiB; narrow the selected goals or context");
      }
      String label = reasons.isEmpty() ? "Educational goal tradeoff — affordability unqualified" : "Incomplete goal tradeoff — incomparable assumptions";
      long id = CarlService.record(c, actor, "FINANCE", "PRIVATE", "Goal tradeoff", "Explicit authenticated tradeoff request " + request);
      LocalDate from = start == null ? LocalDate.parse(debt.get("period_start").toString()) : start;
      CarlService.execute(c, "INSERT INTO carl_artifact(record_id,request_id,kind,period_start,period_end,facts,narrative,limitations,narration_state,source_revision,formula_version,status_label,permission_revision) VALUES(?,?,'FINANCIAL_PLAN',?,?,?,'',?,'NOT_REQUESTED',?,'goal-tradeoffs-v1',?,?)", id, request, from, from.plusMonths(Math.max(horizon, 1)), encoded, LIMITS, CarlService.number(household, "revision"), label, actor.permissionRevision());
      for(var entry : sources.entrySet())
      {
         CarlService.execute(c, "INSERT INTO carl_artifact_source(artifact_id,source_id,source_revision) VALUES(?,?,?)", id, entry.getKey(), entry.getValue());
      }
      for(String recipient : scope.audience())
      {
         long member = CarlService.member(c, recipient).id();
         CarlService.execute(c, "INSERT INTO carl_artifact_audience(artifact_id,member_id) VALUES(?,?)", id, member);
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,?,true)", id, member);
      }
      CarlService.complete(c, request, id, reasons.isEmpty() ? "COMPLETE" : "PARTIAL", label);
      return id;
   }



   private static Map<String, Object> child(Connection c, CarlService.Scope scope, long household, long id) throws SQLException
   {
      var audience = new java.util.TreeSet<String>();
      for(var row : CarlService.rows(c, "SELECT m.principal FROM carl_artifact_audience a JOIN carl_member m ON m.id=a.member_id WHERE a.artifact_id=?", id))
      {
         audience.add(row.get("principal").toString());
      }
      if(!audience.equals(scope.audience()))
      {
         throw new SecurityException("Saved scenarios require the same exact authorized audience");
      }
      Map<String, Object> artifact = null;
      for(String recipient : scope.audience())
      {
         if(CarlService.member(c, recipient).householdId() != household)
         {
            throw new SecurityException("Comparison audience unavailable");
         }
         var found = CarlService.rows(c, "SELECT v.*,f.source_revision,f.permission_revision FROM carl_artifact_view v JOIN carl_artifact f ON f.record_id=v.id WHERE v.principal=? AND v.id=? AND v.kind='FINANCIAL_PLAN'", recipient, id);
         if(found.size() != 1)
         {
            throw new SecurityException("Current financial scenario unavailable");
         }
         if(Boolean.TRUE.equals(found.getFirst().get("stale")))
         {
            throw new IllegalArgumentException("Regenerate stale saved scenario before comparing");
         }
         if(recipient.equals(scope.principal()))
         {
            artifact = found.getFirst();
         }
      }
      if(artifact == null)
      {
         throw new SecurityException("Requester must belong to the explicit audience");
      }
      return artifact;
   }



   private static void flatten(Connection c, long artifact, Map<Long, Long> sources) throws SQLException
   {
      source(sources, artifact, CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", artifact).getFirst(), "revision"));
      var inherited = CarlService.rows(c, "WITH RECURSIVE inherited AS (SELECT source_id,source_revision FROM carl_artifact_source WHERE artifact_id=? UNION SELECT s.source_id,s.source_revision FROM carl_artifact_source s JOIN inherited i ON s.artifact_id=i.source_id) SELECT source_id,source_revision FROM inherited LIMIT 1001", artifact);
      if(inherited.size() > 1000)
      {
         throw new IllegalArgumentException("Narrow the source graph; at most 1000 retained source records");
      }
      for(var row : inherited)
      {
         source(sources, CarlService.number(row, "source_id"), CarlService.number(row, "source_revision"));
      }
   }



   private static void source(Map<Long, Long> sources, long id, long revision)
   {
      Long prior = sources.putIfAbsent(id, revision);
      if(prior != null && prior != revision)
      {
         throw new IllegalArgumentException("Conflicting child source snapshots; regenerate both scenarios");
      }
   }



   private static JsonNode facts(Map<String, Object> artifact)
   {
      try
      {
         return JSON.readTree(artifact.get("facts").toString());
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalArgumentException("Invalid stored financial scenario", invalid);
      }
   }



   private static ObjectNode summary(JsonNode input, List<String> fields)
   {
      ObjectNode summary = JSON.createObjectNode();
      for(String field : fields)
      {
         if(input.has(field))
         {
            summary.set(field, input.path(field));
         }
      }
      return summary;
   }



   private static void gaps(List<String> reasons, JsonNode gaps, String prefix)
   {
      if(gaps.isArray())
      {
         gaps.forEach(gap -> reasons.add(prefix + gap.asText()));
      }
   }



   private static BigDecimal budget(JsonNode facts, boolean portfolio, int horizon)
   {
      if(!portfolio)
      {
         return facts.path("monthlyBudget").isNumber() ? facts.path("monthlyBudget").decimalValue() : null;
      }
      var budgets = facts.path("inputs").path("budgets");
      if(budgets.size() != 1 || budgets.path(0).path("firstMonth").asInt() != 1 || !budgets.path(0).path("amount").isNumber() || horizon < 1)
      {
         return null;
      }
      return budgets.path(0).path("amount").decimalValue();
   }



   private static LocalDate date(JsonNode value)
   {
      try
      {
         return LocalDate.parse(value.asText());
      }
      catch(java.time.DateTimeException invalid)
      {
         return null;
      }
   }



   private static LocalDate month(JsonNode value)
   {
      try
      {
         return YearMonth.parse(value.asText()).atDay(1);
      }
      catch(java.time.DateTimeException invalid)
      {
         return null;
      }
   }



   private static int horizon(Map<String, Object> artifact, LocalDate from)
   {
      if(from == null)
      {
         return 0;
      }
      LocalDate through = LocalDate.parse(artifact.get("period_end").toString());
      long months = java.time.temporal.ChronoUnit.MONTHS.between(from, through);
      return months > 0 && months <= 600 && from.plusMonths(months).equals(through) ? (int) months : 0;
   }
}
