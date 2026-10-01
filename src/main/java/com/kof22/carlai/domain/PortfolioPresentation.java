/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


/** Bounded portfolio summaries and explicit immutable-detail pages with current audience authorization. */
public final class PortfolioPresentation
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private final CarlService service;
   /** Uses the same authoritative artifact and source permissions as administration. */
   public PortfolioPresentation(CarlService service)
   {
      this.service = service;
   }



   /** Returns fixed financial totals and explicit links to full details, never silently shortened facts. */
   public ObjectNode summary(CarlService.Scope scope, long id)
   {
      Map<String, Object> artifact = authorized(scope, id);
      JsonNode facts = facts(artifact);
      ObjectNode result = JSON.createObjectNode();
      result.put("artifactId", id);
      result.put("status", artifact.get("status_label").toString());
      result.put("stale", Boolean.TRUE.equals(artifact.get("stale")));
      result.put("limitations", artifact.get("limitations").toString());
      result.set("asOf", facts.path("asOf"));
      result.set("currency", facts.path("inputs").path("currency"));
      result.set("scope", facts.path("scope"));
      result.set("budgetQualification", facts.path("budgetQualification"));
      result.put("sourceCount", facts.path("sources").size());
      result.put("gapCount", facts.path("gaps").size());
      var totals = result.putObject("strategies");
      facts.path("comparisons").fields().forEachRemaining(entry ->
      {
         if(!entry.getKey().endsWith("_VS_CURRENT"))
         {
            var value = entry.getValue();
            var summary = totals.putObject(entry.getKey());
            for(String field : Set.of("determined", "feasible", "paidOff", "originalPrincipal", "debtAfterFinancedFees", "financedFees", "upfrontCashFees", "interest", "monthlyFees", "payoffDate"))
            {
               summary.set(field, value.path(field));
            }
            summary.put("calculatedMonths", value.path("months").size());
         }
      });
      var comparisons = result.putObject("comparedWithCurrent");
      facts.path("comparisons").fields().forEachRemaining(entry ->
      {
         if(entry.getKey().endsWith("_VS_CURRENT"))
         {
            var comparison = comparisons.putObject(entry.getKey());
            for(String field : Set.of("fullPayoffCostsCompared", "candidateMinusBaselineCost", "candidateMinusBaselineUpfrontCash", "breakEvenMonth", "limitations"))
            {
               comparison.set(field, entry.getValue().path(field));
            }
         }
      });
      result.put("detailWorkflow", "portfolio-detail");
      result.put("detailSections", "assumptions, sources, gaps, months, payoffDates, cashDifferences; each page rechecks current access");
      return result;
   }



   /** Pages preserved source facts or schedules; the response states total count and next offset. */
   public ObjectNode detail(CarlService.Scope scope, long id, String section, String strategy, int offset, int limit)
   {
      if(offset < 0 || offset > 25000 || limit < 1 || limit > 10 || !Set.of("assumptions", "sources", "gaps", "months", "payoffDates", "cashDifferences").contains(section))
      {
         throw new IllegalArgumentException("Choose a supported section and a page of one to ten items");
      }
      JsonNode facts = facts(authorized(scope, id));
      JsonNode items;
      if(Set.of("months", "payoffDates", "cashDifferences").contains(section))
      {
         if(strategy == null || !Set.of("CURRENT_PAYMENT", "MINIMUM_ONLY", "AVALANCHE", "SNOWBALL", "USER_DIRECTED").contains(strategy))
         {
            throw new IllegalArgumentException("Choose a supported strategy");
         }
         if(section.equals("cashDifferences"))
         {
            items = facts.path("comparisons").path(strategy + "_VS_CURRENT").path("cashDifferences");
         }
         else if(section.equals("payoffDates"))
         {
            var dates = JSON.createArrayNode();
            var entries = new java.util.TreeMap<String, JsonNode>();
            facts.path("comparisons").path(strategy).path("payoffDates").fields().forEachRemaining(entry -> entries.put(entry.getKey(), entry.getValue()));
            entries.forEach((debt, date) -> dates.addObject().put("debt", debt).set("payoffDate", date));
            items = dates;
         }
         else
         {
            items = facts.path("comparisons").path(strategy).path("months");
         }
      }
      else
      {
         if(strategy != null)
         {
            throw new IllegalArgumentException("Strategy applies only to schedule pages");
         }
         items = section.equals("assumptions") ? JSON.createArrayNode().add(facts.path("inputs")).add(facts.path("budgetEvidence")) : facts.path(section);
      }
      if(!items.isArray())
      {
         throw new IllegalArgumentException("This comparison has no calculated schedule; inspect gaps");
      }
      ObjectNode result = JSON.createObjectNode();
      result.put("artifactId", id);
      result.put("section", section);
      result.put("offset", offset);
      result.put("totalItems", items.size());
      if(strategy != null)
      {
         result.put("strategy", strategy);
      }
      var page = result.putArray("items");
      for(int n = offset; n < Math.min(items.size(), offset + limit); n++)
      {
         page.add(items.get(n));
      }
      if(offset + page.size() < items.size())
      {
         result.put("nextOffset", offset + page.size());
      }
      else
      {
         result.putNull("nextOffset");
      }
      if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072)
      {
         throw new IllegalArgumentException("Detail page exceeds 128 KiB; request fewer items or review the protected native artifact");
      }
      return result;
   }



   private Map<String, Object> authorized(CarlService.Scope scope, long id)
   {
      for(String principal : scope.audience())
      {
         service.artifact(principal, id);
      }
      return service.artifact(scope.principal(), id);
   }



   private static JsonNode facts(Map<String, Object> artifact)
   {
      try
      {
         JsonNode facts = JSON.readTree(artifact.get("facts").toString());
         if(!facts.has("budgetQualification") || !facts.has("comparisons"))
         {
            throw new IllegalArgumentException("Choose a saved portfolio comparison");
         }
         return facts;
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalArgumentException("Invalid stored portfolio", invalid);
      }
   }
}
