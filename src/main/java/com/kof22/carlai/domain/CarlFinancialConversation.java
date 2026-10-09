/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentcore.runtime.ConversationTurn;


/** Closed financial report requests over Carl's existing authoritative services; no record-edit tools. */
final class CarlFinancialConversation
{
   static final String CONTRACT = """
      Additional report-only proposals, all with evidence quoting an exact human USER excerpt:
      {"operation":"RENTAL_REPORT","properties":[123],"from":"YYYY-MM-DD","through":"YYYY-MM-DD","asOf":"YYYY-MM-DD","evidence":"..."}
      {"operation":"RENTAL_STRESS","scenario":123,"evidence":"..."}
      {"operation":"TAX_PACKET","properties":[123],"alternatives":[],"references":[],"taxYear":2026,"asOf":"YYYY-MM-DD","evidence":"..."}
      {"operation":"BALANCE_SHEET","accounts":[],"properties":[{"property":123,"valuation":"PROPERTY_ESTIMATE|LINKED_ACCOUNT"}],"asOf":"YYYY-MM-DD","maximumAgeDays":30,"evidence":"..."}
      {"operation":"EXPENSE_FORECAST","expenses":[123],"actuals":[],"from":"YYYY-MM-DD","through":"YYYY-MM-DD","asOf":"YYYY-MM-DD","currency":"USD","evidence":"..."}
      {"operation":"INVESTMENT_SCENARIO","goal":123,"currency":"USD","firstMonth":"YYYY-MM","months":12,"initialCapital":"1000.00","monthlyContribution":"100.00","monthlyReturn":"0.00","assetFeeRate":"0.00","initialFee":"0.00","monthlyFee":"0.00","evidence":"..."}
      Select only currently common-authorized catalog records explicitly named by the human (or 'record 123'). Clarify ambiguous names.
      Dates/years and valuation choices must come from the human, never from an assumed locale, tax rule or model guess.
      Quote date roles 'from YYYY-MM-DD', 'through YYYY-MM-DD', 'as of YYYY-MM-DD'; tax uses 'tax year YYYY'.
      Balance age requires 'maximum age N days'. Investment first month uses 'from YYYY-MM' and horizon 'N months'.
      Investment amounts/rates require human labels: 'initial capital USD 1000.00', 'monthly contribution USD 100.00',
      'monthly return decimal fraction 0.00', 'asset fee rate decimal fraction 0.00', 'initial fee USD 0.00', 'monthly fee USD 0.00'.
      Rates require explicit decimal-fraction units; percentages, basis points and unspecified rate units require CLARIFY, never implicit conversion.
      Missing required choices or assumptions require CLARIFY; never manufacture zero fees or expected returns.
      Investment context is read from the saved goal. Do not select readiness, risk tolerance or tax treatment.
      Stress only replays a saved reviewed scenario; do not create or alter its assumptions.
      Tax selects supplied alternatives and dated primary-source records, never fetches sources or invents treatment/rules.
      Tax as-of date means 00:00 UTC on that explicit date for reference applicability, not historical reconstruction.
      All results are scoped conditional reports, not complete household statements or qualified financial/tax recommendations.
      """;
   private static final Set<String> OPERATIONS = Set.of("RENTAL_REPORT", "RENTAL_STRESS", "TAX_PACKET", "BALANCE_SHEET", "EXPENSE_FORECAST", "INVESTMENT_SCENARIO");
   private static final List<String> CATALOGS = List.of("properties", "rentalScenarios", "taxProperties", "taxAlternatives", "taxReferences", "financialGoals", "accounts", "expenses", "expenseActuals");
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private final CarlService service;

   CarlFinancialConversation(CarlService service)
   {
      this.service = service;
   }



   static boolean supports(String operation)
   {
      return OPERATIONS.contains(operation);
   }



   Map<String, Object> catalog(CarlService.Scope scope)
   {
      var catalog = new LinkedHashMap<String, Object>();
      for(String kind : CATALOGS)
      {
         List<Map<String, Object>> values;
         try
         {
            values = service.view(scope, kind);
         }
         catch(SecurityException unavailable)
         {
            values = List.of();
         }
         catalog.put(kind, values.stream().map(row ->
         {
            var safe = new LinkedHashMap<String, Object>();
            for(String key : List.of("id", "title", "currency", "kind", "review_state", "goal_type", "property_id", "tax_year"))
            {
               if(row.get(key) != null)
               {
                  safe.put(key, row.get(key));
               }
            }
            return safe;
         }).toList());
      }
      return catalog;
   }



   CarlConversation.Outcome execute(JsonNode proposal, List<ConversationTurn> history, UUID request, Supplier<CarlService.Scope> authorized)
   {
      try
      {
         String operation = text(proposal, "operation");
         String evidence = text(proposal, "evidence");
         if(evidence.length() > 4000 || history.stream().filter(turn -> turn.role() == ConversationTurn.Role.USER).noneMatch(turn -> turn.content().contains(evidence)))
         {
            throw new Clarify();
         }
         var scope = authorized.get();
         long artifact = switch(operation)
         {
            case "RENTAL_REPORT" -> rental(proposal, evidence, scope, request);
            case "RENTAL_STRESS" -> stress(proposal, evidence, scope, request);
            case "TAX_PACKET" -> tax(proposal, evidence, scope, request);
            case "BALANCE_SHEET" -> balance(proposal, evidence, scope, request);
            case "EXPENSE_FORECAST" -> expense(proposal, evidence, scope, request);
            case "INVESTMENT_SCENARIO" -> investment(proposal, evidence, scope, request);
            default -> throw new IllegalArgumentException("Unsupported report");
         };
         scope = authorized.get();
         var output = JSON.createObjectNode().put("kind", operation).put("message", "Saved conditional report for the currently authorized selected scope. No external action occurred; model narration was not requested.");
         output.set("calculation", summary(scope, artifact, operation));
         return new CarlConversation.Outcome(artifact, "PARTIAL", output);
      }
      catch(Clarify missing)
      {
         return new CarlConversation.Outcome(null, "COMPLETE", JSON.createObjectNode().put("kind", "CLARIFICATION").put("message", "Please explicitly name the permitted records and required dated assumptions. Use from/through/as of dates, tax year or maximum age where applicable. Investment examples need the horizon, currency and separately labeled capital, contribution, assumed monthly return, asset fee rate, initial fee and monthly fee; missing values are not zero."));
      }
   }



   private long rental(JsonNode p, String evidence, CarlService.Scope scope, UUID request)
   {
      exact(p, "operation", "properties", "from", "through", "asOf", "evidence");
      var properties = selected(p.get("properties"), "properties", scope, evidence, 20);
      if(properties.isEmpty())
      {
         throw new Clarify();
      }
      return new RentalRecords(service).report(scope, request, properties, date(p, "from", "from", evidence), date(p, "through", "through", evidence), date(p, "asOf", "as of", evidence));
   }



   private long stress(JsonNode p, String evidence, CarlService.Scope scope, UUID request)
   {
      exact(p, "operation", "scenario", "evidence");
      long id = id(p.get("scenario"));
      chosen(scope, "rentalScenarios", id, evidence);
      return new RentalStress(service).report(scope, request, id);
   }



   private long tax(JsonNode p, String evidence, CarlService.Scope scope, UUID request)
   {
      exact(p, "operation", "properties", "alternatives", "references", "taxYear", "asOf", "evidence");
      var properties = selected(p.get("properties"), "taxProperties", scope, evidence, 20);
      if(properties.isEmpty())
      {
         throw new Clarify();
      }
      int year = integer(p, "taxYear", 1900, 2200);
      requireToken(evidence, "tax year " + year);
      return new TaxPlanningRecords(service).packet(scope, request, year, date(p, "asOf", "as of", evidence).atStartOfDay(ZoneOffset.UTC).toInstant(), List.copyOf(properties), List.copyOf(selected(p.get("alternatives"), "taxAlternatives", scope, evidence, 5)), List.copyOf(selected(p.get("references"), "taxReferences", scope, evidence, 10)));
   }



   private long balance(JsonNode p, String evidence, CarlService.Scope scope, UUID request)
   {
      exact(p, "operation", "accounts", "properties", "asOf", "maximumAgeDays", "evidence");
      var accounts = selected(p.get("accounts"), "accounts", scope, evidence, 20);
      if(!p.path("properties").isArray() || p.get("properties").size() > 20)
      {
         throw new Clarify();
      }
      var properties = new ArrayList<BalanceSheets.PropertyChoice>();
      var ids = new LinkedHashSet<Long>();
      for(var choice : p.get("properties"))
      {
         exact(choice, "property", "valuation");
         long property = id(choice.get("property"));
         if(!ids.add(property))
         {
            throw new Clarify();
         }
         var selected = chosen(scope, "properties", property, evidence);
         String value = text(choice, "valuation");
         if(!token(evidence, selected.get("title") + " using " + value) && !token(evidence, "record " + property + " using " + value))
         {
            throw new Clarify();
         }
         properties.add(new BalanceSheets.PropertyChoice(property, BalanceSheets.Valuation.valueOf(value)));
      }
      if(properties.isEmpty() && accounts.isEmpty())
      {
         throw new Clarify();
      }
      int age = integer(p, "maximumAgeDays", 0, 3660);
      requireToken(evidence, "maximum age " + age + " days");
      return new BalanceSheets(service).report(scope, request, date(p, "asOf", "as of", evidence), age, List.copyOf(accounts), properties);
   }



   private long expense(JsonNode p, String evidence, CarlService.Scope scope, UUID request)
   {
      exact(p, "operation", "expenses", "actuals", "from", "through", "asOf", "currency", "evidence");
      String currency = text(p, "currency");
      requireToken(evidence, currency);
      var expenses = selected(p.get("expenses"), "expenses", scope, evidence, 20);
      var actuals = selected(p.get("actuals"), "expenseActuals", scope, evidence, 20);
      if(expenses.isEmpty() && actuals.isEmpty())
      {
         throw new Clarify();
      }
      for(var group : Map.of("expenses", expenses, "expenseActuals", actuals).entrySet())
      {
         for(long id : group.getValue())
         {
            if(!currency.equals(chosen(scope, group.getKey(), id, evidence).get("currency")))
            {
               throw new Clarify();
            }
         }
      }
      return new ExpenseRecords(service).report(scope, request, expenses, actuals, date(p, "from", "from", evidence), date(p, "through", "through", evidence), date(p, "asOf", "as of", evidence));
   }



   private long investment(JsonNode p, String evidence, CarlService.Scope scope, UUID request)
   {
      exact(p, "operation", "goal", "currency", "firstMonth", "months", "initialCapital", "monthlyContribution", "monthlyReturn", "assetFeeRate", "initialFee", "monthlyFee", "evidence");
      long goal = id(p.get("goal"));
      if(!"INVESTMENT".equals(chosen(scope, "financialGoals", goal, evidence).get("goal_type")))
      {
         throw new Clarify();
      }
      String currency = text(p, "currency");
      String first = text(p, "firstMonth");
      requireToken(evidence, "from " + first);
      int months = integer(p, "months", 1, 600);
      requireToken(evidence, months + " months");
      var initial = decimal(p, "initialCapital", "initial capital " + currency, evidence, false);
      var contribution = decimal(p, "monthlyContribution", "monthly contribution " + currency, evidence, false);
      var rate = decimal(p, "monthlyReturn", "monthly return", evidence, true);
      var assetFee = decimal(p, "assetFeeRate", "asset fee rate", evidence, false);
      var initialFee = decimal(p, "initialFee", "initial fee " + currency, evidence, false);
      var monthlyFee = decimal(p, "monthlyFee", "monthly fee " + currency, evidence, false);
      var assumption = new InvestmentPlanning.Assumption(request.toString(), rate, assetFee, initialFee, monthlyFee, "Human conversation request " + request);
      return new FinancialGoals(service).scenario(scope, request, goal, currency, YearMonth.parse(first), months, initial, contribution, assumption, evidence);
   }



   private Set<Long> selected(JsonNode value, String kind, CarlService.Scope scope, String evidence, int maximum)
   {
      if(value == null || !value.isArray() || value.size() > maximum)
      {
         throw new Clarify();
      }
      var ids = new LinkedHashSet<Long>();
      for(var item : value)
      {
         long id = id(item);
         if(!ids.add(id))
         {
            throw new Clarify();
         }
         chosen(scope, kind, id, evidence);
      }
      return ids;
   }



   private Map<String, Object> chosen(CarlService.Scope scope, String kind, long id, String evidence)
   {
      var current = service.view(scope, kind);
      var selected = current.stream().filter(row -> CarlService.number(row, "id") == id).findFirst().orElseThrow(() -> new SecurityException("Selected report source unavailable"));
      String title = selected.get("title").toString();
      boolean named = token(evidence, title) && current.stream().filter(row -> title.equalsIgnoreCase(row.get("title").toString())).count() == 1;
      if(!named && !token(evidence, "record " + id))
      {
         throw new Clarify();
      }
      return selected;
   }



   JsonNode summary(CarlService.Scope scope, long artifact, String operation)
   {
      for(String principal : scope.audience())
      {
         service.artifact(principal, artifact);
      }
      var row = service.artifact(scope.principal(), artifact);
      JsonNode facts;
      try
      {
         String saved = row.get("facts").toString();
         if(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_097_152)
         {
            throw new IllegalArgumentException("Stored report exceeds presentation bound");
         }
         facts = JSON.readTree(saved);
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalStateException("Stored calculation unavailable", invalid);
      }
      var summary = JSON.createObjectNode().put("selectedScope", "Only explicitly selected currently permitted records; partial household coverage").put("detailWorkflow", "artifact-section").put("artifactId", artifact).put("stale", Boolean.TRUE.equals(row.get("stale"))).put("narrationState", row.get("narration_state").toString());
      summary.put("limitations", row.get("limitations").toString());
      var source = facts;
      List<String> fields = switch(operation)
      {
         case "RENTAL_REPORT" -> List.of("from", "through", "asOf", "calculationVersion", "exceptions", "evidenceStatus");
         case "RENTAL_STRESS" -> List.of("currency", "from", "through", "baselineCashAfterReserveTransfers", "hypotheticalVacancyLoss", "additionalRepair", "additionalSimpleInterest", "totalAdverseCashImpact", "hypotheticalCashAfterReserveTransfers", "coverageGaps");
         case "TAX_PACKET" -> List.of("taxCalculationStatus", "ruleQualification", "recommendedStructure", "referenceApplicabilityAsOf", "conditionalAlternatives", "sourceReviews", "gaps");
         case "BALANCE_SHEET" -> List.of("asOf", "maximumAgeDays", "knownSelectedNetWorthByCurrency", "knownSelectedLiquidBalancesByCurrency", "gaps", "temporalScope");
         case "INVESTMENT_SCENARIO" -> List.of("currency", "firstMonth", "requestedMonths", "endingHypotheticalValue", "contributions", "fees", "assumedNetGrowth", "projectionStatus", "recommendationStatus", "ownerSelectedInvestmentStage", "assumptions", "ownerContext", "gaps", "limitations");
         case "EXPENSE_FORECAST" -> List.of("knownScheduledExpenses", "actualExpenses", "remainingExpenses", "actualReserveEarmarks", "remainingReserveEarmarks", "cashCoverageComplete", "exceptions");
         default -> throw new IllegalArgumentException("Unknown report kind");
      };
      if(Set.of("INVESTMENT_SCENARIO", "EXPENSE_FORECAST").contains(operation))
      {
         source = facts.path("projection");
      }
      for(String field : fields)
      {
         if(source.has(field))
         {
            summary.set(field, source.get(field));
         }
      }
      if(operation.equals("RENTAL_REPORT"))
      {
         summary.set("wholePropertyPortfolioByCurrency", facts.path("calculation").path("wholePropertyPortfolioByCurrency"));
      }
      if(operation.equals("EXPENSE_FORECAST"))
      {
         for(String field : List.of("from", "through", "asOf", "openingReserveEarmarks"))
         {
            summary.set(field, facts.path(field));
         }
         summary.set("currency", facts.path("currency"));
      }
      if(operation.equals("TAX_PACKET"))
      {
         summary.put("alternativeCount", facts.path("conditionalAlternatives").size()).put("referenceCount", facts.path("sourceReviews").size());
         summary.set("preparation", new ArtifactPresentation(service).detail(scope, artifact, "/facts/preparation", 0, 25).path("section"));
      }
      if(summary.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
      {
         return JSON.createObjectNode().put("artifactId", artifact).put("summaryState", "DETAIL_REQUIRED").put("detailWorkflow", "artifact-section").put("message", "Saved report is too large for a conversational summary. Complete authorized facts remain available through protected section pages; no omitted figure is zero.");
      }
      return summary;
   }



   private static BigDecimal decimal(JsonNode p, String field, String label, String evidence, boolean signed)
   {
      String value = text(p, field);
      if(!value.matches((signed ? "-?" : "") + "(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,8})?"))
      {
         throw new Clarify();
      }
      boolean rate = field.equals("monthlyReturn") || field.equals("assetFeeRate");
      String phrase = label + (rate ? " decimal fraction " : " ") + value;
      requireToken(evidence, phrase);
      if(Pattern.compile(Pattern.quote(phrase) + "\\s*(?:%|‰|percent\\b|percentage\\b|pct\\b|basis\\s+points\\b|bps\\b)", Pattern.CASE_INSENSITIVE).matcher(evidence).find())
      {
         throw new Clarify();
      }
      return new BigDecimal(value);
   }



   private static LocalDate date(JsonNode p, String field, String label, String evidence)
   {
      String value = text(p, field);
      if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
      {
         throw new Clarify();
      }
      requireToken(evidence, label + " " + value);
      return LocalDate.parse(value);
   }



   private static String text(JsonNode p, String field)
   {
      if(!p.path(field).isTextual() || p.get(field).asText().isBlank())
      {
         throw new Clarify();
      }
      return p.get(field).asText();
   }



   private static int integer(JsonNode p, String field, int minimum, int maximum)
   {
      var value = p.path(field);
      if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum)
      {
         throw new Clarify();
      }
      return value.intValue();
   }



   private static long id(JsonNode value)
   {
      if(value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
      {
         throw new Clarify();
      }
      return value.longValue();
   }



   private static void exact(JsonNode p, String... fields)
   {
      var keys = new LinkedHashSet<String>();
      p.fieldNames().forEachRemaining(keys::add);
      if(!p.isObject() || !keys.equals(Set.of(fields)))
      {
         throw new IllegalArgumentException("Invalid closed report proposal");
      }
   }



   private static boolean token(String evidence, String value)
   {
      return Pattern.compile("(?<![\\p{L}0-9.,+\\-−])" + Pattern.quote(value) + "(?![\\p{L}0-9+\\-−]|[.,][0-9])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(evidence).find();
   }



   private static void requireToken(String evidence, String value)
   {
      if(!token(evidence, value))
      {
         throw new Clarify();
      }
   }

   private static final class Clarify extends RuntimeException
   {
      private static final long serialVersionUID = 1L;
   }
}
