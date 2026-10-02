/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import java.util.Map;

import com.auth0.jwk.Jwk;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;


/** Test-only local identity verification for the actual native document conversation regression. */
public final class CarlDocumentConversationIdentityFixture
{
   private CarlDocumentConversationIdentityFixture()
   {
   }



   /** Uses controlled keys and local JWKS; no live identity provider is contacted. */
   public static BearerIdentity identity(String issuer, Jwk key)
   {
      return new BearerIdentity(issuer, "carl-admin", "controlled-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> key);
   }
}
