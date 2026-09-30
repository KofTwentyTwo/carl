/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


/** Bounded purchase-choice summaries and explicit protected repayment pages. */
public final class PurchasePresentation
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private final CarlService service;
   /** Uses current artifact/source authorization for every audience member. */
   public PurchasePresentation(CarlService service)
   {
      this.service = service;
   }



   /** Leaves complete schedules in immutable storage and declares their explicit retrieval workflow. */
   public ObjectNode summary(CarlService.Scope scope, long artifact)
   {
      var result = (ObjectNode) facts(scope, artifact).deepCopy();
      for(var option : result.path("options"))
      {
         var value = (ObjectNode) option;
         value.put("paymentCount", value.path("payments").size());
         value.remove("payments");
      }
      result.put("artifactId", artifact);
      result.put("detailWorkflow", "purchase-detail");
      result.put("scheduleNotice", "Complete dated repayments are retained and available in permission-checked pages.");
      return result;
   }



   /** Returns a page of actual projected repayments without changing the saved assessment. */
   public ObjectNode detail(CarlService.Scope scope, long artifact, String option, int offset, int limit)
   {
      if(option == null || option.length() > 100 || offset < 0 || offset > 601 || limit < 1 || limit > 24)
      {
         throw new IllegalArgumentException("Choose an option and one to twenty-four payments per page");
      }
      JsonNode selected = null;
      var assessment = facts(scope, artifact);
      for(var value : assessment.path("options"))
      {
         if(value.path("id").asText().equals(option))
         {
            selected = value;
            break;
         }
      }
      if(selected == null)
      {
         throw new IllegalArgumentException("Payment option unavailable in this saved assessment");
      }
      var payments = selected.path("payments");
      ObjectNode result = JSON.createObjectNode();
      result.put("artifactId", artifact);
      result.set("currency", assessment.path("currency"));
      result.put("option", option);
      result.put("offset", offset);
      result.put("totalPayments", payments.size());
      var page = result.putArray("payments");
      for(int n = offset; n < Math.min(payments.size(), offset + limit); n++)
      {
         page.add(payments.get(n));
      }
      if(offset + page.size() < payments.size())
      {
         result.put("nextOffset", offset + page.size());
      }
      else
      {
         result.putNull("nextOffset");
      }
      return result;
   }



   private JsonNode facts(CarlService.Scope scope, long artifact)
   {
      for(String principal : scope.audience())
      {
         service.artifact(principal, artifact);
      }
      var saved = service.artifact(scope.principal(), artifact);
      try
      {
         var result = JSON.readTree(saved.get("facts").toString()).path("purchaseOptions");
         if(!result.isObject())
         {
            throw new IllegalArgumentException("Choose a saved purchase-options assessment");
         }
         ((ObjectNode) result).put("stale", Boolean.TRUE.equals(saved.get("stale")));
         return result;
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalStateException("Invalid preserved purchase assessment", invalid);
      }
   }
}
