/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentadmin.client.FamilyAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** The shared adjective describes an explicit request; only verified transport can grant its audience. */
class CarlPlanConversationIntentTest
{
   @ParameterizedTest
   @ValueSource(strings = {"Create a shared draft plan titled Family review plan from comparison 123.", "Please create a shared plan titled Family review plan from comparison 123.", "Can you create the shared financial plan titled Family review plan from comparison 123?"})
   void sharedCreationRetainsExplicitCurrentIntent(String human)
   {
      assertTrue(CarlPlanConversation.currentIntent("PLAN_CREATE", human));
   }



   @ParameterizedTest
   @ValueSource(strings = {"Do not create a shared draft plan from comparison 123.", "What if I create a shared draft plan from comparison 123?", "Bob said: \"Create a shared draft plan from comparison 123.\"", "Create a shared draft plan from comparison 123, but don't create it."})
   void quotedHypotheticalAndNegatedSharedCreationCannotAuthorize(String human)
   {
      assertTrue(!CarlPlanConversation.currentIntent("PLAN_CREATE", human));
   }



   @Test
   void privateTransportCannotAcquireSharedAudienceFromText()
   {
      var context = new ClientWorkflow.Context(new FamilyAccess.Member("1", "1", "alice", "1"), UUID.randomUUID(), false, Set.of("1"));
      var proposal = new ObjectMapper().createObjectNode().put("operation", "PLAN_CREATE").put("sourceArtifact", 123).put("title", "Family review plan").put("reason", "Human request");
      var result = new CarlPlanConversation(null, null).execute(context, UUID.randomUUID(), proposal, "Create a shared draft plan titled Family review plan from comparison 123.", () ->
      {
         throw new AssertionError("Sharing text cannot authorize or access a private source");
      });
      assertEquals("CLARIFICATION", result.output().path("kind").asText());
   }
}
