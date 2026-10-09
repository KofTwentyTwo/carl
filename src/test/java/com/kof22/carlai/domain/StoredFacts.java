/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;


/*******************************************************************************
 ** Artifact facts are stored as jsonb, whose text form is normalized; tests
 ** compare compact JSON with exact decimals instead of storage spacing.
 *******************************************************************************/
final class StoredFacts
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

   private StoredFacts()
   {
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String compact(Object facts)
   {
      try
      {
         return JSON.readTree(facts.toString()).toString();
      }
      catch(JsonProcessingException invalid)
      {
         throw new IllegalStateException("Stored facts are not JSON", invalid);
      }
   }
}
