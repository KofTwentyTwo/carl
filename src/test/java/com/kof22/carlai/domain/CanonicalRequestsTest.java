/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class CanonicalRequestsTest
{
   @Test
   void equivalentNestedMapsRetainTheirDigestAcrossInsertionOrders()
   {
      var first = new LinkedHashMap<String, Object>();
      first.put("z", Map.of("September", 20, "January", 10));
      first.put("a", "request");
      var nested = new LinkedHashMap<String, Object>();
      nested.put("January", 10);
      nested.put("September", 20);
      var second = new LinkedHashMap<String, Object>();
      second.put("a", "request");
      second.put("z", nested);
      assertEquals("{\"a\":\"request\",\"z\":{\"January\":10,\"September\":20}}", CarlService.json(first));
      assertEquals(BillCsv.hash(CarlService.json(first)), BillCsv.hash(CarlService.json(second)));
   }
}
