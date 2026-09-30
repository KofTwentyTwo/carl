/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Bounded protected summaries and explicit navigation of complete saved report facts. */
public final class ArtifactPresentation
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private final CarlService service;
   /** Rechecks all current recipients on every page; no bearer-free artifact links. */
   public ArtifactPresentation(CarlService service)
   {
      this.service = service;
   }



   /** Metadata and section descriptors only; complete values use explicit protected pages. */
   public JsonNode summary(CarlService.Scope scope, long id)
   {
      var artifact = authorized(scope, id);
      var result = JSON.createObjectNode();
      result.put("artifactId", id);
      for(String key : java.util.List.of("kind", "status_label", "narration_state", "stale", "period_start", "period_end", "created_at"))
      {
         result.set(key, JSON.valueToTree(artifact.get(key)));
      }
      result.put("detailWorkflow", "artifact-section");
      result.put("detailRoot", "");
      result.put("meaning", "Saved scoped snapshot. Retrieve complete facts and narrative through explicit protected section pages; no omitted data is represented as zero.");
      result.set("root", page(tree(artifact), "", 0, 25));
      return result;
   }



   /** RFC6901 paths navigate only this already-authorized immutable report, with explicit continuation. */
   public JsonNode detail(CarlService.Scope scope, long id, String pointer, int offset, int limit)
   {
      if(pointer == null || pointer.length() > 1000 || (!pointer.isEmpty() && !pointer.startsWith("/")) || pointer.split("/", -1).length > 20 || offset < 0 || offset > 2_097_152 || limit < 1 || limit > 4096)
      {
         throw new IllegalArgumentException("Bounded report pointer and page required");
      }
      var root = tree(authorized(scope, id));
      var node = root.at(pointer);
      if(node.isMissingNode())
      {
         throw new IllegalArgumentException("Report section unavailable");
      }
      if(node.isContainerNode() && limit > 25)
      {
         throw new IllegalArgumentException("Collection pages allow at most 25 entries");
      }
      var result = JSON.createObjectNode().put("artifactId", id);
      result.set("section", page(node, pointer, offset, limit));
      if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072)
      {
         throw new IllegalArgumentException("Report page exceeds bounded response; use a smaller page");
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



   private static JsonNode tree(Map<String, Object> artifact)
   {
      try
      {
         String facts = artifact.get("facts").toString();
         if(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_097_152)
         {
            throw new IllegalArgumentException("Saved facts exceed section bound");
         }
         var root = JSON.createObjectNode();
         root.set("facts", JSON.readTree(facts));
         root.put("narrative", artifact.get("narrative").toString());
         root.put("limitations", artifact.get("limitations").toString());
         return root;
      }
      catch(java.io.IOException invalid)
      {
         throw new IllegalStateException("Stored report facts invalid", invalid);
      }
   }



   private static JsonNode page(JsonNode node, String pointer, int offset, int limit)
   {
      var result = JSON.createObjectNode().put("path", pointer).put("type", node.getNodeType().name()).put("offset", offset);
      int total = node.isContainerNode() ? node.size() : node.isTextual() ? node.asText().codePointCount(0, node.asText().length()) : 1;
      if(offset > total)
      {
         throw new IllegalArgumentException("Offset exceeds section length");
      }
      result.put("totalItems", total);
      int end = Math.min(total, offset + limit);
      if(end < total)
      {
         result.put("nextOffset", end);
      }
      else
      {
         result.putNull("nextOffset");
      }
      if(node.isContainerNode())
      {
         var entries = result.putArray("entries");
         var fields = node.isObject() ? node.fieldNames() : null;
         for(int i = 0; i < total; i++)
         {
            String key = fields == null ? Integer.toString(i) : fields.next();
            if(i < offset || i >= end)
            {
               continue;
            }
            JsonNode child = fields == null ? node.get(i) : node.get(key);
            var entry = entries.addObject().put("key", key).put("path", pointer + "/" + key.replace("~", "~0").replace("/", "~1")).put("type", child.getNodeType().name());
            if(child.isContainerNode())
            {
               entry.put("totalItems", child.size());
            }
            else if(child.isTextual() && child.asText().length() > 256)
            {
               entry.put("textLength", child.asText().codePointCount(0, child.asText().length())).put("valueRequiresPage", true);
            }
            else
            {
               entry.set("value", child);
            }
         }
      }
      else if(node.isTextual())
      {
         String value = node.asText();
         int begin = value.offsetByCodePoints(0, offset);
         int finish = value.offsetByCodePoints(0, end);
         result.put("value", value.substring(begin, finish));
         result.put("offsetUnit", "Unicode code points");
      }
      else if(offset == 0)
      {
         result.set("value", node);
      }
      return result;
   }
}
